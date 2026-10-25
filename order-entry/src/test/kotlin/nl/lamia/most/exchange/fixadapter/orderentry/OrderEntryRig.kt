package nl.lamia.most.exchange.fixadapter.orderentry

import io.aeron.Aeron
import io.aeron.ExclusivePublication
import io.aeron.Subscription
import io.aeron.archive.ArchiveThreadingMode
import io.aeron.driver.ThreadingMode
import io.aeron.logbuffer.FragmentHandler
import nl.lamia.most.exchange.client.GatewayEndpoint
import nl.lamia.most.exchange.client.OrderEntryListener
import nl.lamia.most.exchange.client.OrderFate
import nl.lamia.most.exchange.client.ReportAction
import nl.lamia.most.exchange.fixadapter.orderentry.exchange.DirectoryBroadcast
import nl.lamia.most.exchange.fixadapter.orderentry.exchange.TestSecurity
import nl.lamia.most.exchange.fixadapter.orderentry.exchange.TestShard
import nl.lamia.most.exchange.fixadapter.session.AcceptorRig
import nl.lamia.most.exchange.fixadapter.session.FixAcceptor
import nl.lamia.most.exchange.fixadapter.session.Polled
import nl.lamia.most.exchange.sbe.ClientExecutionReportDecoder
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
    val listener = RecordingListener()
    val log = mutableListOf<String>()
    val backend: OrderEntryBackend = try {
        OrderEntryBackend(
            acceptor, OrderEntryConfig(storeDir(dir), "aeron:ipc", DIRECTORY_STREAM, gateways), listener, { TODAY },
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

    override fun close() = CloseHelper.closeAll(backend, acceptor)

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
 * per endpoint that records what it is sent and answers the SDK's mass status and resend requests,
 * empty and `COMPLETE`. It sends no execution reports: those are slice 3's.
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

data class GatewayOrder(val participantId: Long, val clOrdId: Long, val securityId: Int, val side: Side, val price: Long, val qty: Long, val expireDate: Int)

data class GatewayCancel(val participantId: Long, val clOrdId: Long, val origClOrdId: Long, val exchangeOrderId: Long, val securityId: Int)

/** One order entry gateway: its order stream in, its report stream out. */
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
                val e = OrderMassStatusCompleteEncoder().wrapAndApplyHeader(out, 0, outHeader)
                    .participantId(status.participantId()).requestId(status.requestId()).nextSeq(1L).orderCount(0)
                    .securityId(status.securityId()).status(RequestStatus.COMPLETE)
                stage(MessageHeaderEncoder.ENCODED_LENGTH + e.encodedLength(), isStatus = true)
            }
            ReportResendRequestDecoder.TEMPLATE_ID -> {
                resend.wrap(buffer, body, header.blockLength(), header.version())
                val e = ReportResendCompleteEncoder().wrapAndApplyHeader(out, 0, outHeader)
                    .participantId(resend.participantId()).requestId(resend.requestId()).fromSeq(resend.fromSeq())
                    .nextSeq(resend.fromSeq()).oldestRetainedSeq(1L).replayedCount(0).status(RequestStatus.COMPLETE)
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

/** What the SDK told the adapter. Slice 3 handles reports; here they are only counted. */
class RecordingListener : OrderEntryListener {
    var ready = 0
        private set
    val settled = mutableListOf<Pair<Long, OrderFate>>()
    var reports = 0
        private set

    override fun onReport(report: ClientExecutionReportDecoder): ReportAction {
        reports++
        return ReportAction.CONSUMED
    }

    override fun onSettled(participantId: Long, clOrdId: Long, fate: OrderFate) {
        settled += clOrdId to fate
    }

    override fun onReady() {
        ready++
    }
}
