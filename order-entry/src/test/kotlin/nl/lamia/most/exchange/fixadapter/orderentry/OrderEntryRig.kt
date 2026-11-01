package nl.lamia.most.exchange.fixadapter.orderentry

import io.aeron.Aeron
import io.aeron.ExclusivePublication
import io.aeron.Subscription
import io.aeron.archive.ArchiveThreadingMode
import io.aeron.driver.ThreadingMode
import io.aeron.logbuffer.FragmentHandler
import nl.lamia.most.exchange.client.GatewayEndpoint
import nl.lamia.most.exchange.fixadapter.session.DirectoryBroadcast
import nl.lamia.most.exchange.fixadapter.session.TestSecurity
import nl.lamia.most.exchange.fixadapter.session.TestShard
import nl.lamia.most.exchange.fixadapter.orderentry.store.OrderStore
import nl.lamia.most.exchange.fixadapter.session.AcceptorRig
import nl.lamia.most.exchange.fixadapter.session.FixAcceptor
import nl.lamia.most.exchange.fixadapter.session.Polled
import nl.lamia.most.exchange.sbe.MessageHeaderDecoder
import nl.lamia.most.exchange.sbe.MessageHeaderEncoder
import nl.lamia.most.exchange.sbe.NewOrderSingleDecoder
import nl.lamia.most.exchange.sbe.OrderCancelRequestDecoder
import nl.lamia.most.exchange.sbe.OrderMassStatusCompleteEncoder
import nl.lamia.most.exchange.sbe.OrderMassStatusRequestDecoder
import nl.lamia.most.exchange.sbe.ReportResendCompleteEncoder
import nl.lamia.most.exchange.sbe.ReportResendRequestDecoder
import nl.lamia.most.exchange.sbe.RequestStatus
import nl.lamia.most.exchange.sbe.Side
import org.agrona.CloseHelper
import org.agrona.concurrent.UnsafeBuffer
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The adapter's order-entry side as the tests run it, on [dir]: a [FixAcceptor] not yet bound, the
 * [OrderEntryBackend] built on it (store loaded, startup guards run), then the bind, as the adapter
 * starts (Design.md §3.2). fileSyncLevel 0, shared-thread driver.
 */
class OrderEntryRig(
    val dir: File,
    val port: Int,
    gateways: Map<Int, List<GatewayEndpoint>> = emptyMap(),
) : Polled, AutoCloseable {
    val fixConfig = AcceptorRig.nodeConfig(dir, port)
    val acceptor = FixAcceptor(fixConfig, bindAtStartup = false) { driver, archive ->
        driver.threadingMode(ThreadingMode.SHARED).dirDeleteOnShutdown(true)
        archive.threadingMode(ArchiveThreadingMode.SHARED)
    }
    val log = mutableListOf<String>()
    val backend: OrderEntryBackend = try {
        OrderEntryBackend(
            acceptor, OrderEntryConfig(storeDir(dir), "aeron:ipc", DIRECTORY_STREAM, gateways), { TODAY },
            log = { synchronized(log) { log += it } },
        )
    } catch (e: Throwable) {
        acceptor.close()
        throw e
    }

    init {
        acceptor.bind()
    }

    override fun poll(): Int = backend.doWork()

    /** A clean stop, as the adapter's: the backend, Artio's engine, then the mark (Design.md §8.21). */
    override fun close() {
        CloseHelper.closeAll(backend, acceptor)
        OrderStore.markStoppedCleanly(storeDir(dir))
    }

    /** Closed without the clean-stop mark: what the next start sees after a crash (§8.21). */
    fun closeAsCrashed() = CloseHelper.closeAll(backend, acceptor)

    companion object {
        const val TODAY = 20261026
        const val DIRECTORY_STREAM = 9100

        fun storeDir(dir: File) = File(dir, "store")

        /** Shard [id]'s gateway, as the tests' directory lists it: IPC, on the adapter's own driver. */
        fun shard(id: Int) = TestShard(id, "aeron:ipc", 9200 + id, "aeron:ipc", 9300 + id)
    }
}

/**
 * The exchange as the adapter's order entry meets it (most-exchange Adapters.md §1–§2), over real
 * Aeron on the adapter's driver: the directory, broadcast on a cycle as discovery does, and a gateway
 * per endpoint that records what it is sent, sends the reports a test asks for, and answers the
 * SDK's mass status and resend requests as the exchange does ([GatewayModel]).
 */
class ExchangeModel(aeronDir: String, var broadcast: DirectoryBroadcast?) : Polled, AutoCloseable {
    private val aeron = Aeron.connect(Aeron.Context().aeronDirectoryName(aeronDir))
    private val directory: ExclusivePublication = aeron.addExclusivePublication("aeron:ipc", OrderEntryRig.DIRECTORY_STREAM)
    private var nextBroadcastNs = 0L
    val gateways = LinkedHashMap<GatewayEndpoint, GatewayModel>()

    /** A gateway listening at [endpoint]. */
    fun gateway(endpoint: GatewayEndpoint): GatewayModel = gateways.getOrPut(endpoint) { GatewayModel(aeron, endpoint) }

    fun gateway(shard: TestShard): GatewayModel =
        gateway(GatewayEndpoint(shard.orderEntryChannel, shard.orderEntryStreamId, shard.executionReportChannel, shard.executionReportStreamId))

    override fun poll(): Int {
        var work = 0
        val now = System.nanoTime()
        val current = broadcast
        if (current != null && now >= nextBroadcastNs && directory.isConnected) {
            nextBroadcastNs = now + BROADCAST_INTERVAL_NS
            // A refused offer truncates the cycle; the SDK keeps the last whole one, and the next cycle comes.
            current.messages { b, o, l -> directory.offer(b, o, l) > 0 }
            work++
        }
        for (g in gateways.values) work += g.poll()
        return work
    }

    override fun close() {
        CloseHelper.closeAll(gateways.values)
        CloseHelper.closeAll(directory, aeron)
    }

    private companion object {
        val BROADCAST_INTERVAL_NS = TimeUnit.MILLISECONDS.toNanos(50)
    }
}

data class GatewayOrder(val participantId: Long, val clOrdId: String, val securityId: Int, val side: Side, val price: Long, val qty: Long, val expireDate: Int)

data class GatewayCancel(val participantId: Long, val clOrdId: String, val origClOrdId: String, val exchangeOrderId: Long, val securityId: Int)

/**
 * One order entry gateway: its order stream in, its report stream out. Its exchange as most-exchange's
 * Design.md §5 specifies it ("Report sequence and resend", "Order mass status"): every sequenced
 * report is kept in a ring per participant; a `ReportResendRequest` replays the retained ones from
 * `fromSeq` to `toSeq` inclusive (0: to the last), each with its own `reportSeq`, then completes with
 * `toSeq` echoed, `COMPLETE`, or `TRUNCATED` when some asked for are gone from the ring. A mass status
 * states the orders open here.
 */
class GatewayModel(aeron: Aeron, val endpoint: GatewayEndpoint) : Polled, AutoCloseable {
    private val requests: Subscription = aeron.addSubscription(endpoint.orderEntryChannel, endpoint.orderEntryStreamId)
    private val reports: ExclusivePublication = aeron.addExclusivePublication(endpoint.executionReportChannel, endpoint.executionReportStreamId)
    val orders = mutableListOf<GatewayOrder>()
    val cancels = mutableListOf<GatewayCancel>()
    var statusesAnswered = 0
        private set

    private val header = MessageHeaderDecoder()
    private val newOrder = NewOrderSingleDecoder()
    private val cancel = OrderCancelRequestDecoder()
    private val status = OrderMassStatusRequestDecoder()
    private val resend = ReportResendRequestDecoder()
    private val outHeader = MessageHeaderEncoder()
    private val out = UnsafeBuffer(ByteArray(256))
    private val owed = ArrayDeque<Pair<Int, Boolean>>() // (length of a reply staged in a buffer of its own, is status)
    private val staged = ArrayDeque<ByteArray>()

    /** Per participant, the next `reportSeq` this gateway's exchange gives (most-exchange Design.md §5). */
    private val nextSeq = HashMap<Long, Long>()

    fun nextSeq(participantId: Long): Long = nextSeq[participantId] ?: 1L

    /** Orders this gateway's exchange holds open, per participant: what a mass status states. */
    private val open = HashMap<Long, LinkedHashMap<String, Pair<Long, Long>>>() // clOrdId -> (exchangeOrderId, leavesQty)

    /** Per participant, the ring: each retained report's bytes by `reportSeq`. */
    private val ring = HashMap<Long, HashMap<Long, ByteArray>>()

    /** Per participant, the lowest `reportSeq` the ring still holds: 1 until [lose]. */
    private val oldestRetained = HashMap<Long, Long>()

    /** The `ReportResendRequest`s this gateway answered, as (fromSeq, toSeq). */
    val resends = mutableListOf<Pair<Long, Long>>()

    /**
     * A reportSeq given and never sent, and gone from the ring by the time it is asked for, with every
     * report before it: the client sees a gap, and a resend from below it is TRUNCATED.
     */
    fun lose(participantId: Long) {
        val seq = nextSeq(participantId)
        nextSeq[participantId] = seq + 1
        oldestRetained[participantId] = seq + 1
        ring[participantId]?.keys?.removeIf { it <= seq }
    }

    /**
     * Sends a ClientExecutionReport, sequenced unless it is an ORDER_STATUS, and keeps it in the ring.
     * [dropped]: sequenced and kept, never sent, as a report the gateway dropped on the way. Returns
     * its reportSeq.
     */
    @Suppress("LongParameterList")
    fun report(
        participantId: Long, clOrdId: String, execType: nl.lamia.most.exchange.sbe.ExecType, exchangeOrderId: Long,
        price: Long = 0L, lastQty: Long = 0L, leavesQty: Long = 0L, cumQty: Long = 0L, origQty: Long = 0L, securityId: Int = 42,
        reason: nl.lamia.most.exchange.sbe.RejectReason = nl.lamia.most.exchange.sbe.RejectReason.NONE, side: Side = Side.BUY,
        origClOrdId: String = clOrdId, dropped: Boolean = false,
    ): Long {
        val seq = if (execType == nl.lamia.most.exchange.sbe.ExecType.ORDER_STATUS) 0L else nextSeq(participantId).also { nextSeq[participantId] = it + 1 }
        // Since schema 8 every report carries clOrdId and origClOrdId: a cancel's, the order's in 41 (§5.3).
        val e = nl.lamia.most.exchange.sbe.ClientExecutionReportEncoder().wrapAndApplyHeader(out, 0, outHeader)
            .participantId(participantId).clOrdId(clOrdId).origClOrdId(origClOrdId).exchangeOrderId(exchangeOrderId).price(price).lastQty(lastQty)
            .leavesQty(leavesQty).cumQty(cumQty).origQty(origQty).securityId(securityId).rejectReason(reason)
            .execType(execType).side(side).enrichment(nl.lamia.most.exchange.sbe.Enrichment.KNOWN).reportSeq(seq)
        val length = MessageHeaderEncoder.ENCODED_LENGTH + e.encodedLength()
        // In the ring whether or not it is sent; dropped, as on the way: only a resend brings it (§5).
        if (seq > 0L) ring.getOrPut(participantId) { HashMap() }[seq] = ByteArray(length).also { out.getBytes(0, it) }
        if (!dropped) stage(length, isStatus = false)
        val orders = open.getOrPut(participantId) { LinkedHashMap() }
        when (execType) {
            nl.lamia.most.exchange.sbe.ExecType.NEW, nl.lamia.most.exchange.sbe.ExecType.TRADE ->
                if (leavesQty > 0L) orders[clOrdId] = exchangeOrderId to leavesQty else orders.remove(clOrdId)
            nl.lamia.most.exchange.sbe.ExecType.ORDER_STATUS, nl.lamia.most.exchange.sbe.ExecType.REJECTED -> Unit
            else -> orders.remove(clOrdId)
        }
        return seq
    }

    private val handler = FragmentHandler { buffer, offset, _, _ ->
        header.wrap(buffer, offset)
        val body = offset + MessageHeaderDecoder.ENCODED_LENGTH
        when (header.templateId()) {
            NewOrderSingleDecoder.TEMPLATE_ID -> {
                newOrder.wrap(buffer, body, header.blockLength(), header.version())
                orders += GatewayOrder(
                    newOrder.participantId(), newOrder.clOrdId(), newOrder.securityId(), newOrder.side(),
                    newOrder.price(), newOrder.qty(), newOrder.expireDate(),
                )
            }
            OrderCancelRequestDecoder.TEMPLATE_ID -> {
                cancel.wrap(buffer, body, header.blockLength(), header.version())
                cancels += GatewayCancel(cancel.participantId(), cancel.clOrdId(), cancel.origClOrdId(), cancel.exchangeOrderId(), cancel.securityId())
            }
            OrderMassStatusRequestDecoder.TEMPLATE_ID -> {
                status.wrap(buffer, body, header.blockLength(), header.version())
                val participant = status.participantId()
                val requestId = status.requestId()
                val securityId = status.securityId()
                val stated = open[participant].orEmpty().entries.toList()
                for ((clOrdId, held) in stated) {
                    report(participant, clOrdId, nl.lamia.most.exchange.sbe.ExecType.ORDER_STATUS, held.first, leavesQty = held.second, origQty = held.second)
                }
                val e = OrderMassStatusCompleteEncoder().wrapAndApplyHeader(out, 0, outHeader)
                    .participantId(participant).requestId(requestId).nextSeq(nextSeq(participant)).orderCount(stated.size)
                    .securityId(securityId).status(RequestStatus.COMPLETE)
                stage(MessageHeaderEncoder.ENCODED_LENGTH + e.encodedLength(), isStatus = true)
            }
            ReportResendRequestDecoder.TEMPLATE_ID -> {
                resend.wrap(buffer, body, header.blockLength(), header.version())
                val participant = resend.participantId()
                val fromSeq = resend.fromSeq()
                val toSeq = resend.toSeq()
                resends += fromSeq to toSeq
                val next = nextSeq(participant)
                val oldest = oldestRetained[participant] ?: 1L
                val last = if (toSeq == 0L) next - 1L else minOf(toSeq, next - 1L)
                var replayed = 0
                val retained = ring[participant].orEmpty()
                for (seq in maxOf(fromSeq, oldest)..last) {
                    val bytes = retained[seq] ?: continue
                    staged.addLast(bytes)
                    owed.addLast(bytes.size to false)
                    replayed++
                }
                val e = ReportResendCompleteEncoder().wrapAndApplyHeader(out, 0, outHeader)
                    .participantId(participant).requestId(resend.requestId()).fromSeq(fromSeq)
                    .toSeq(toSeq).nextSeq(next).oldestRetainedSeq(oldest).replayedCount(replayed)
                    .status(if (fromSeq < oldest && fromSeq < next) RequestStatus.TRUNCATED else RequestStatus.COMPLETE)
                stage(MessageHeaderEncoder.ENCODED_LENGTH + e.encodedLength(), isStatus = false)
            }
        }
    }

    private fun stage(length: Int, isStatus: Boolean) {
        staged.addLast(ByteArray(length).also { out.getBytes(0, it) })
        owed.addLast(length to isStatus)
    }

    override fun poll(): Int {
        var work = requests.poll(handler, 10)
        while (staged.isNotEmpty() && reports.offer(UnsafeBuffer(staged.first())) > 0) {
            staged.removeFirst()
            if (owed.removeFirst().second) statusesAnswered++
            work++
        }
        return work
    }

    override fun close() = CloseHelper.closeAll(requests, reports)
}
