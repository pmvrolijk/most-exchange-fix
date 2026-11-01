package nl.lamia.most.exchange.fixadapter.orderentry

import nl.lamia.most.exchange.fixadapter.orderentry.store.RequestRow
import nl.lamia.most.exchange.client.ClOrdId
import nl.lamia.most.exchange.client.GatewayEndpoint
import nl.lamia.most.exchange.fixadapter.codecs.Constants
import nl.lamia.most.exchange.fixadapter.codecs.builder.NewOrderSingleEncoder
import nl.lamia.most.exchange.fixadapter.codecs.builder.OrderCancelReplaceRequestEncoder
import nl.lamia.most.exchange.fixadapter.codecs.builder.OrderCancelRequestEncoder
import nl.lamia.most.exchange.fixadapter.codecs.builder.OrderMassStatusRequestEncoder
import nl.lamia.most.exchange.fixadapter.codecs.builder.OrderStatusRequestEncoder
import nl.lamia.most.exchange.fixadapter.codecs.builder.TestRequestEncoder
import nl.lamia.most.exchange.fixadapter.orderentry.OrderEntryRig.Companion.shard
import nl.lamia.most.exchange.fixadapter.session.DirectoryBroadcast
import nl.lamia.most.exchange.fixadapter.session.TestSecurity
import nl.lamia.most.exchange.fixadapter.orderentry.inbound.Texts
import nl.lamia.most.exchange.fixadapter.orderentry.store.OrderStore
import nl.lamia.most.exchange.fixadapter.trace.Trace
import nl.lamia.most.exchange.fixadapter.trace.TraceFormat
import nl.lamia.most.exchange.fixadapter.trace.TraceLog
import nl.lamia.most.exchange.fixadapter.orderentry.store.RowKind
import nl.lamia.most.exchange.fixadapter.session.AcceptorRig
import nl.lamia.most.exchange.fixadapter.session.FixAcceptor
import nl.lamia.most.exchange.fixadapter.session.FixMessage
import nl.lamia.most.exchange.fixadapter.session.Initiator
import nl.lamia.most.exchange.fixadapter.session.Polled
import nl.lamia.most.exchange.fixadapter.session.TestSessions.ADAPTER
import nl.lamia.most.exchange.fixadapter.session.TestSessions.CLIENT1
import nl.lamia.most.exchange.fixadapter.session.TestSessions.CLIENT2
import nl.lamia.most.exchange.fixadapter.session.TestSessions.SECRET1
import nl.lamia.most.exchange.fixadapter.session.TestSessions.SECRET2
import nl.lamia.most.exchange.fixadapter.session.fixMessage
import nl.lamia.most.exchange.fixadapter.session.pollFor
import nl.lamia.most.exchange.fixadapter.session.pollUntil
import nl.lamia.most.exchange.sbe.ExecType
import nl.lamia.most.exchange.sbe.Side
import org.agrona.concurrent.UnsafeBuffer
import org.junit.jupiter.api.io.TempDir
import uk.co.real_logic.artio.Reply
import uk.co.real_logic.artio.fields.UtcTimestampEncoder
import uk.co.real_logic.artio.session.Session
import java.io.File
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Order entry wired (Design.md §2, §3.2, §3.3, §3.5): a real Artio initiator logs on to the
 * acceptor, its messages reach the order-entry library and the inbound path, and orders leave
 * through the SDK's `OrderEntrySession` over Aeron, to a model of each shard's gateway.
 */
class OrderEntryBackendTest {

    @TempDir
    lateinit var dir: File

    private val port = AcceptorRig.freePort()
    private val adapterDir get() = File(dir, "adapter")
    private val closing = ArrayDeque<AutoCloseable>()
    private val client by lazy { Initiator(File(dir, "client")).also { closing.addFirst(it) } }

    /** The adapter and client sessions still up, logged out before closing: an initiator closed logged on waits ~10 s. */
    private var live: Adapter? = null
    private val sessions = mutableListOf<Session>()

    @AfterTest
    fun close() {
        live?.takeUnless { it.closed }?.let { adapter ->
            for (session in sessions) {
                if (!session.isConnected) continue
                runCatching {
                    pollUntil(*adapter.all(), timeoutMs = 2_000, what = "logout sent") { session.startLogout() > 0 }
                    pollUntil(*adapter.all(), timeoutMs = 2_000, what = "logged out") { !session.isConnected }
                }
            }
        }
        while (closing.isNotEmpty()) closing.removeFirst().close()
    }

    private class Adapter(val rig: OrderEntryRig, val exchange: ExchangeModel) : AutoCloseable {
        var closed = false
            private set

        override fun close() {
            if (closed) return
            closed = true
            exchange.close()
            rig.close()
        }

        /** Stopped with no clean-stop mark, as after a crash (Design.md §8.21). */
        fun crash() {
            if (closed) return
            closed = true
            exchange.close()
            rig.closeAsCrashed()
        }
    }

    /** The adapter, then the exchange on its driver; [broadcast] null means no directory yet. */
    private fun start(broadcast: DirectoryBroadcast? = TWO_SHARDS, gateways: Map<Int, List<GatewayEndpoint>> = emptyMap()): Adapter {
        val rig = OrderEntryRig(adapterDir, port, gateways)
        val exchange = try {
            ExchangeModel(rig.fixConfig.aeronDir, broadcast)
        } catch (e: Throwable) {
            rig.close()
            throw e
        }
        broadcast?.shards?.forEach { exchange.gateway(it) }
        return Adapter(rig, exchange).also {
            closing.addFirst(it)
            live = it
        }
    }

    private fun Adapter.all() = arrayOf<Polled>(rig, exchange, client)

    /** Until every shard's SDK session has finished its opening mass status (most-exchange Adapters.md §1). */
    private fun Adapter.awaitExchange(shards: Int = 2) =
        pollUntil(*all(), what = "the SDK's sessions ready") { rig.backend.readyShards == shards }

    private fun <T> Adapter.await(reply: Reply<T>, what: String): T {
        pollUntil(*all(), what = what) { !reply.isExecuting }
        check(reply.hasCompleted()) { "$what: ${reply.state()} ${reply.error() ?: ""}" }
        return reply.resultIfPresent()
    }

    private fun Adapter.logon(
        target: String = CLIENT1, initialSent: Int? = null, initialReceived: Int? = null, reset: Boolean = false,
    ): Session {
        pollUntil(*all(), what = "libraries connected") { rig.backend.library.isConnected && client.library.isConnected }
        val (user, secret) = if (target == CLIENT1) "client1" to SECRET1 else "client2" to SECRET2
        return await(client.logon(port, target, user, secret, initialSent, initialReceived, reset), "logon of $target").also { sessions += it }
    }

    private fun Session.messages(): List<FixMessage> = client.received[id()].orEmpty()

    private fun Adapter.awaitReply(session: Session, what: String, test: (FixMessage) -> Boolean): FixMessage {
        pollUntil(*all(), what = what) { session.messages().any(test) }
        return session.messages().first(test)
    }

    private fun Adapter.send(session: Session, encoder: uk.co.real_logic.artio.builder.Encoder) =
        pollUntil(*all(), what = "sent ${encoder.messageType()}") { session.trySend(encoder) > 0 }

    private val timestamp = UtcTimestampEncoder()

    private fun order(clOrdId: String, symbol: String = "ACME") = NewOrderSingleEncoder().apply {
        clOrdID(clOrdId)
        side('1')
        transactTime(timestamp.buffer(), timestamp.encode(System.currentTimeMillis()))
        ordType('2')
        instrument().symbol(symbol)
        orderQtyData().orderQty(10L, 0)
        price(10025L, 2)
    }

    /** A 35=D written byte for byte, at the session's next MsgSeqNum: for what no encoder writes. */
    private fun Adapter.sendRaw(session: Session, vararg body: Pair<Int, String>, sendingTime: Instant = Instant.now()) {
        val seq = session.lastSentMsgSeqNum() + 1
        val time = DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS").withZone(ZoneOffset.UTC).format(sendingTime)
        val bytes = fixMessage("D", listOf(49 to CLIENT1, 56 to ADAPTER, 34 to "$seq", 52 to time) + body.toList())
        pollUntil(*all(), what = "raw 35=D sent") { session.trySend(UnsafeBuffer(bytes), 0, bytes.size, seq, Constants.NEW_ORDER_SINGLE_MESSAGE) > 0 }
    }

    private fun Adapter.gateway(shardId: Int) = exchange.gateway(shard(shardId))

    // ================================================================ the order path, §3.3, §3.5

    @Test
    fun `a 35=D reaches its security's shard as a NewOrderSingle for the session's participant, with its own ClOrdID`() {
        val adapter = start()
        adapter.awaitExchange()
        val session = adapter.logon()
        adapter.send(session, order("ORD-A", "ACME"))
        val acmeSeq = session.lastSentMsgSeqNum()
        adapter.send(session, order("ORD-B", "BETA"))
        val betaSeq = session.lastSentMsgSeqNum()
        pollUntil(*adapter.all(), what = "both orders at their gateways") { adapter.gateway(0).orders.size == 1 && adapter.gateway(1).orders.size == 1 }

        val acme = adapter.gateway(0).orders.single()
        assertEquals(1001L, acme.participantId, "the session's participant (§2)")
        assertEquals("ORD-A", acme.clOrdId, "the client's ClOrdID as sent, the exchange's id since schema 8 (§3.2)")
        // The row keeps its position: the acceptor's sequenceIndex, 0 for a session never reset, and the MsgSeqNum.
        assertEquals(acmeSeq.toLong(), adapter.rig.backend.store.findByFix(1001L, "ORD-A")?.position, "never sent (§3.2)")
        assertEquals(42, acme.securityId)
        assertEquals(Side.BUY, acme.side)
        assertEquals(100_25000000L, acme.price)
        assertEquals(10_00000000L, acme.qty)
        assertEquals(OrderEntryRig.TODAY, acme.expireDate, "a day order: the adapter's trading date (§3.6)")
        assertEquals(43, adapter.gateway(1).orders.single().securityId, "BETA's shard is 1")
        assertEquals("ORD-B", adapter.gateway(1).orders.single().clOrdId)
        assertEquals(betaSeq.toLong(), adapter.rig.backend.store.findByFix(1001L, "ORD-B")?.position)
        assertTrue(session.messages().none { it.msgType == "8" || it.msgType == "j" || it.msgType == "3" }, "${session.messages()}")
    }

    @Test
    fun `each session's orders carry its own participant`() {
        val adapter = start()
        adapter.awaitExchange()
        val one = adapter.logon(CLIENT1)
        val two = adapter.logon(CLIENT2)
        adapter.send(one, order("ONE"))
        adapter.send(two, order("TWO"))
        pollUntil(*adapter.all(), what = "two orders") { adapter.gateway(0).orders.size == 2 }
        assertEquals(setOf(1001L, 1002L), adapter.gateway(0).orders.map { it.participantId }.toSet())
    }

    @Test
    fun `before the directory a 35=D is rejected exchange unavailable, in a 35=8 as §3_3 writes it`() {
        val adapter = start(broadcast = null)
        val session = adapter.logon()
        adapter.send(session, order("EARLY-1"))
        val report = adapter.awaitReply(session, "the reject") { it.msgType == "8" }
        assertEquals("EARLY-1", report[11])
        assertEquals("NONE", report[37], "no order at the exchange")
        assertEquals("A0.${report.seqNum}", report[17], "ExecID A<sequenceIndex>.<MsgSeqNum> of this 35=8 (§5.3)")
        assertEquals("8", report[150])
        assertEquals("8", report[39])
        assertEquals("99", report[103])
        assertEquals(Texts.EXCHANGE_UNAVAILABLE, report[58])
        assertEquals("1", report[54])
        assertEquals("ACME", report[55])
        assertEquals("10", report[38])
        assertEquals("100.25", report[44])
        for (tag in listOf(14, 151, 6)) assertEquals(0.0, assertNotNull(report[tag], "tag $tag").toDouble(), "tag $tag")
        assertNotNull(report[60], "TransactTime")
        assertTrue(adapter.exchange.gateways.values.all { it.orders.isEmpty() })
    }

    @Test
    fun `two local rejects carry distinct ExecIDs`() {
        val adapter = start(broadcast = null)
        val session = adapter.logon()
        adapter.send(session, order("EARLY-1"))
        adapter.send(session, order("EARLY-2"))
        pollUntil(*adapter.all(), what = "both rejects") { session.messages().count { it.msgType == "8" } == 2 }
        val ids = session.messages().filter { it.msgType == "8" }.map { it[17] }
        assertEquals(2, ids.toSet().size, "$ids")
    }

    @Test
    fun `a 35=F naming no order is a 35=9 with CxlRejReason 1, OrdStatus 8 and OrderID NONE`() {
        val adapter = start()
        adapter.awaitExchange()
        val session = adapter.logon()
        adapter.send(session, OrderCancelRequestEncoder().apply {
            origClOrdID("NO-SUCH")
            clOrdID("CXL-1")
            side('1')
            transactTime(timestamp.buffer(), timestamp.encode(System.currentTimeMillis()))
            instrument().symbol("ACME")
        })
        val reject = adapter.awaitReply(session, "the cancel reject") { it.msgType == "9" }
        assertEquals("CXL-1", reject[11])
        assertEquals("NO-SUCH", reject[41])
        assertEquals("NONE", reject[37])
        assertEquals("8", reject[39], "FIX 4.4: unknown order → Rejected")
        assertEquals("1", reject[434], "CxlRejResponseTo: a cancel")
        assertEquals("1", reject[102])
        assertEquals(Texts.UNKNOWN_ORDER, reject[58])
        assertEquals(null, reject[5001])
    }

    @Test
    fun `a 35=F of an order not yet acknowledged is parked, and names its status in a refusal`() {
        val adapter = start()
        adapter.awaitExchange()
        val session = adapter.logon()
        adapter.send(session, order("ORD-1"))
        pollUntil(*adapter.all(), what = "the order sent") { adapter.gateway(0).orders.size == 1 }
        fun cancel(id: String) = OrderCancelRequestEncoder().apply {
            origClOrdID("ORD-1")
            clOrdID(id)
            side('1')
            transactTime(timestamp.buffer(), timestamp.encode(System.currentTimeMillis()))
            instrument().symbol("ACME")
        }
        adapter.send(session, cancel("CXL-1"))
        adapter.send(session, cancel("CXL-2"))
        val reject = adapter.awaitReply(session, "the second cancel refused") { it.msgType == "9" }
        assertEquals("CXL-2", reject[11])
        assertEquals("3", reject[102], "already pending (§3.3)")
        assertEquals("A", reject[39], "pending new: no status sent yet, and never 8 for an order that may be open")
        assertEquals("NONE", reject[37], "not acknowledged")
        assertTrue(adapter.gateway(0).cancels.isEmpty(), "parked until the order's first report")
    }

    @Test
    fun `a 35=F that does not match its order is answered as for an unknown order, OrderID NONE and OrdStatus 8`() {
        val adapter = start()
        adapter.awaitExchange()
        val session = adapter.logon()
        adapter.send(session, order("ORD-1"))
        adapter.send(session, OrderCancelRequestEncoder().apply {
            origClOrdID("ORD-1")
            clOrdID("CXL-1")
            side('2') // the order is a buy
            transactTime(timestamp.buffer(), timestamp.encode(System.currentTimeMillis()))
            instrument().symbol("ACME")
        })
        val reject = adapter.awaitReply(session, "the mismatch refused") { it.msgType == "9" }
        assertEquals("1", reject[102])
        assertEquals(Texts.ORDER_MISMATCH, reject[58])
        assertEquals("NONE", reject[37])
        assertEquals("8", reject[39], "CxlRejReason 1: FIX 4.4 says Rejected, whatever the row")
    }

    @Test
    fun `35=G is a 35=9 with CxlRejResponseTo 2 and CxlRejReason 2`() {
        val adapter = start()
        adapter.awaitExchange()
        val session = adapter.logon()
        adapter.send(session, OrderCancelReplaceRequestEncoder().apply {
            origClOrdID("ORD-1")
            clOrdID("RPL-1")
            side('1')
            transactTime(timestamp.buffer(), timestamp.encode(System.currentTimeMillis()))
            ordType('2')
            instrument().symbol("ACME")
            orderQtyData().orderQty(5L, 0)
            price(10025L, 2)
        })
        val reject = adapter.awaitReply(session, "the replace reject") { it.msgType == "9" }
        assertEquals("2", reject[434])
        assertEquals("2", reject[102])
        assertEquals(Texts.REPLACE_NOT_SUPPORTED, reject[58])
    }

    @Test
    fun `a 35=D with a tag outside the dictionary is a 35=3 naming it, and never reaches the gateway`() {
        val adapter = start()
        adapter.awaitExchange()
        val session = adapter.logon()
        adapter.sendRaw(session, 11 to "BAD-1", 55 to "ACME", 54 to "1", 60 to "20261026-09:00:00.000", 38 to "10", 40 to "2", 44 to "100.25", 9999 to "x")
        val reject = adapter.awaitReply(session, "the session reject") { it.msgType == "3" }
        assertEquals("D", reject[372])
        assertEquals("9999", reject[371])
        assertEquals("0", reject[373], "invalid tag number (§5.4)")
        pollFor(200, *adapter.all())
        assertTrue(adapter.gateway(0).orders.isEmpty())
    }

    @Test
    fun `a message Artio's session layer refuses never reaches the gateway`() {
        val adapter = start()
        adapter.awaitExchange()
        val session = adapter.logon()
        // A SendingTime an hour old: Artio answers it itself (SessionRejectReason 10) and marks it invalid.
        adapter.sendRaw(
            session, 11 to "STALE-1", 55 to "ACME", 54 to "1", 60 to "20261026-09:00:00.000", 38 to "10", 40 to "2", 44 to "100.25",
            sendingTime = Instant.now().minusSeconds(3_600),
        )
        val reject = adapter.awaitReply(session, "Artio's reject") { it.msgType == "3" || it.msgType == "5" }
        println("Artio answered a stale SendingTime with $reject (${reject.fields})")
        pollFor(300, *adapter.all())
        assertTrue(adapter.gateway(0).orders.isEmpty(), "not forwarded")
        assertTrue(session.messages().none { it.msgType == "8" }, "nor answered by the backend: ${session.messages()}")
    }

    @Test
    fun `session messages are Artio's, never answered with a 35=j`() {
        val adapter = start()
        adapter.awaitExchange()
        val session = adapter.logon()
        adapter.send(session, TestRequestEncoder().testReqID("PING-1"))
        adapter.awaitReply(session, "the heartbeat") { it.msgType == "0" && it[112] == "PING-1" }
        pollFor(300, *adapter.all())
        assertTrue(session.messages().none { it.msgType == "j" || it.msgType == "3" }, "${session.messages()}")
    }

    // ================================================================ reports, §5.3

    private fun decimal(text: String?) = assertNotNull(text).toBigDecimal()

    private fun assertDecimal(expected: String, actual: String?, what: String) =
        assertEquals(0, expected.toBigDecimal().compareTo(decimal(actual)), "$what: $actual")

    private fun Adapter.reports(session: Session, after: Int = 0) = session.messages().filter { it.msgType == "8" && it.seqNum > after }

    @Test
    fun `an order's acknowledgement and fills reach the client as 35=8s, ExecID the reportSeq`() {
        val adapter = start()
        adapter.awaitExchange()
        val session = adapter.logon()
        adapter.send(session, order("ORD-1"))
        pollUntil(*adapter.all(), what = "the order at the gateway") { adapter.gateway(0).orders.size == 1 }
        val o = adapter.gateway(0).orders.single()
        val g = adapter.gateway(0)
        g.report(1001L, o.clOrdId, ExecType.NEW, 5001L, price = o.price, leavesQty = o.qty, origQty = o.qty)
        g.report(1001L, o.clOrdId, ExecType.TRADE, 5001L, price = 100_20000000L, lastQty = 4_00000000L, leavesQty = 6_00000000L, cumQty = 4_00000000L, origQty = o.qty)
        g.report(1001L, o.clOrdId, ExecType.TRADE, 5001L, price = 100_30000000L, lastQty = 6_00000000L, leavesQty = 0L, cumQty = 10_00000000L, origQty = o.qty)
        pollUntil(*adapter.all(), what = "three 35=8s") { adapter.reports(session).size == 3 }
        val (ack, partial, full) = adapter.reports(session)
        assertEquals(listOf("1", "2", "3"), listOf(ack, partial, full).map { it[17] }, "ExecID: the reportSeq (§5.3)")
        assertEquals(listOf("0", "F", "F"), listOf(ack, partial, full).map { it[150] })
        assertEquals(listOf("0", "1", "2"), listOf(ack, partial, full).map { it[39] })
        for (r in listOf(ack, partial, full)) {
            assertEquals("ORD-1", r[11])
            assertEquals("5001", r[37])
            assertEquals("1", r[54])
            assertEquals("ACME", r[55])
            assertEquals("42", r[48])
            assertEquals("8", r[22])
            assertDecimal("10", r[38], "OrderQty")
            assertDecimal("100.25", r[44], "Price")
            assertNotNull(r[60])
        }
        assertDecimal("100.3", full[31], "LastPx")
        assertDecimal("6", full[32], "LastQty")
        assertDecimal("10", full[14], "CumQty")
        assertDecimal("0", full[151], "LeavesQty")
        assertDecimal("100.26", full[6], "AvgPx: (4 × 100.2 + 6 × 100.3) / 10")
        assertEquals(null, ack[31], "no LastPx on an acknowledgement")
    }

    @Test
    fun `with the trace on, a 35=D and its report leave IN, GATE, SENT, REPORT, OUT and DURABLE, in order, under its clOrdId`() {
        // Design.md §9.3.
        val adapter = start()
        val traceFile = File(dir, "trace.bin")
        val trace = Trace.open(traceFile, 1 shl 16, adapter.rig.acceptor.engine.configuration().epochNanoClock())
        adapter.rig.backend.trace = trace
        adapter.awaitExchange()
        val session = adapter.logon()
        adapter.send(session, order("ORD-T"))
        val msgSeqNum = session.lastSentMsgSeqNum()
        pollUntil(*adapter.all(), what = "the order at the gateway") { adapter.gateway(0).orders.size == 1 }
        val o = adapter.gateway(0).orders.single()
        val reportSeq = adapter.gateway(0).report(1001L, o.clOrdId, ExecType.NEW, 5001L, price = o.price, leavesQty = o.qty, origQty = o.qty)
        pollUntil(*adapter.all(), what = "the 35=8") { adapter.reports(session).size == 1 }
        // The durable reportSeq moves once the archive has the 35=8 (§6.2).
        pollUntil(*adapter.all(), what = "the reportSeq durable") { adapter.rig.backend.delivery.durable(0, 1001L) > reportSeq }
        adapter.close()
        trace.close()

        val t = TraceLog.read(traceFile)
        assertTrue(t.clean)
        assertEquals(0L, t.dropped)
        val events = (0 until t.size).filter { t.participant[it] == 1001L }
        assertEquals(
            listOf(TraceFormat.IN, TraceFormat.GATE, TraceFormat.SENT, TraceFormat.REPORT, TraceFormat.OUT, TraceFormat.DURABLE),
            events.map { t.stage[it] }.distinct(),
            events.map { TraceFormat.name(t.stage[it]) }.toString(),
        )
        val stages = listOf(TraceFormat.IN, TraceFormat.GATE, TraceFormat.SENT, TraceFormat.REPORT, TraceFormat.OUT, TraceFormat.DURABLE)
        val first = stages.associateWith { stage -> events.first { t.stage[it] == stage } }
        for (stage in listOf(TraceFormat.IN, TraceFormat.GATE, TraceFormat.SENT, TraceFormat.REPORT, TraceFormat.OUT)) {
            // The request's position, whose low 32 bits are the 35=D's MsgSeqNum (§3.2, §9.3).
            assertEquals(msgSeqNum.toLong(), t.position[first.getValue(stage)], TraceFormat.name(stage))
        }
        val times = stages.map { t.time[first.getValue(it)] }
        assertEquals(times.sorted(), times, "in order")
        assertEquals(ExecType.NEW.value(), t.execType[first.getValue(TraceFormat.REPORT)])
        assertEquals(reportSeq, t.extra[first.getValue(TraceFormat.REPORT)])
        assertEquals(reportSeq, t.extra[first.getValue(TraceFormat.OUT)])
        assertTrue(t.extra[first.getValue(TraceFormat.DURABLE)] > reportSeq, "DURABLE: the first-missing past the report's reportSeq")
        val received = t.epochToNano(t.extra[first.getValue(TraceFormat.IN)])
        val handled = t.time[first.getValue(TraceFormat.IN)]
        assertTrue(handled - received in 0L..1_000_000_000L, "Artio's receive, converted by its own clock, before the handler: ${handled - received} ns")
    }

    // ================================================================ 35=H and 35=AF, §3.3

    private fun statusOf(clOrdId: String, reqId: String) = OrderStatusRequestEncoder().apply {
        clOrdID(clOrdId)
        ordStatusReqID(reqId)
        side('1')
        instrument().symbol("ACME")
    }

    private fun massStatus(reqId: String) = OrderMassStatusRequestEncoder().apply {
        massStatusReqID(reqId)
        massStatusReqType(7)
    }

    @Test
    fun `a 35=H is answered from the store, an order known or not, as FIX 4_4 writes it`() {
        val adapter = start()
        adapter.awaitExchange()
        val session = adapter.logon()
        adapter.send(session, order("ORD-1"))
        pollUntil(*adapter.all(), what = "the order at the gateway") { adapter.gateway(0).orders.size == 1 }
        val o = adapter.gateway(0).orders.single()
        adapter.gateway(0).report(1001L, o.clOrdId, ExecType.NEW, 5001L, price = o.price, leavesQty = o.qty, origQty = o.qty)
        pollUntil(*adapter.all(), what = "the acknowledgement") { adapter.reports(session).size == 1 }

        adapter.send(session, statusOf("ORD-1", "Q-1"))
        val known = adapter.awaitReply(session, "the status") { it.msgType == "8" && it[790] == "Q-1" }
        assertEquals("I" to "0", known[150] to known[39])
        assertEquals("ORD-1" to "5001", known[11] to known[37])
        assertEquals("ACME" to "42", known[55] to known[48])
        assertDecimal("10", known[151], "LeavesQty")
        assertTrue(assertNotNull(known[17]).startsWith("A"), "the adapter's own ExecID")

        adapter.send(session, statusOf("NOWHERE", "Q-2"))
        val unknown = adapter.awaitReply(session, "the unknown order's status") { it.msgType == "8" && it[790] == "Q-2" }
        assertEquals(Triple("I", "8", "5"), Triple(unknown[150], unknown[39], unknown[103]))
        assertEquals("NOWHERE" to "NONE", unknown[11] to unknown[37])
        assertEquals(Texts.UNKNOWN_ORDER, unknown[58])
        assertEquals(1, adapter.gateway(0).orders.size, "nothing sent for either")
    }

    @Test
    fun `after a 141=Y Logon a 35=AF states the session's open orders, as the client recovers by itself`() {
        // §2: a reset discards the resend; the client asks, and is answered from the store.
        val adapter = start()
        adapter.awaitExchange()
        val first = adapter.logon()
        adapter.send(first, order("ORD-1"))
        adapter.send(first, order("ORD-2"))
        pollUntil(*adapter.all(), what = "the orders at the gateway") { adapter.gateway(0).orders.size == 2 }
        pollUntil(*adapter.all(), what = "logout sent") { first.startLogout() > 0 }
        pollUntil(*adapter.all(), what = "logged out") { !first.isConnected }

        val reset = adapter.logon(reset = true)
        adapter.send(reset, massStatus("MS-1"))
        pollUntil(*adapter.all(), what = "both statuses") { reset.messages().count { it[584] == "MS-1" } == 2 }
        val answers = reset.messages().filter { it[584] == "MS-1" }
        assertEquals(listOf("ORD-1", "ORD-2"), answers.map { it[11] })
        assertTrue(answers.all { it.msgType == "8" && it[150] == "I" && it[39] == "A" && it[911] == "2" })
        assertEquals(listOf("N", "Y"), answers.map { it[912] })
    }

    @Test
    fun `a 35=AF with no open order is a 35=j, no open orders`() {
        val adapter = start()
        adapter.awaitExchange()
        val session = adapter.logon()
        adapter.send(session, massStatus("MS-1"))
        val j = adapter.awaitReply(session, "the 35=j") { it.msgType == "j" }
        assertEquals(Triple("AF", "0", Texts.NO_OPEN_ORDERS), Triple(j[372], j[380], j[58]))
    }

    @Test
    fun `a cancel goes to the exchange with the order's ids, and its CANCELED comes back naming both`() {
        val adapter = start()
        adapter.awaitExchange()
        val session = adapter.logon()
        adapter.send(session, order("ORD-1"))
        pollUntil(*adapter.all(), what = "the order at the gateway") { adapter.gateway(0).orders.size == 1 }
        val o = adapter.gateway(0).orders.single()
        adapter.gateway(0).report(1001L, o.clOrdId, ExecType.NEW, 5001L, price = o.price, leavesQty = o.qty, origQty = o.qty)
        pollUntil(*adapter.all(), what = "the acknowledgement") { adapter.reports(session).size == 1 }
        adapter.send(session, cancelOf("ORD-1", "CXL-1"))
        pollUntil(*adapter.all(), what = "the cancel at the gateway") { adapter.gateway(0).cancels.size == 1 }
        val c = adapter.gateway(0).cancels.single()
        assertEquals(o.clOrdId, c.origClOrdId)
        assertEquals(5001L, c.exchangeOrderId)
        assertEquals("CXL-1", c.clOrdId, "the 35=F's own ClOrdID (§5.2)")
        adapter.gateway(0).report(1001L, c.clOrdId, ExecType.CANCELED, 5001L, price = o.price, leavesQty = 0L, cumQty = 0L, origQty = o.qty)
        pollUntil(*adapter.all(), what = "the cancel's 35=8") { adapter.reports(session).size == 2 }
        val done = adapter.reports(session).last()
        assertEquals("4" to "4", done[150] to done[39])
        assertEquals("CXL-1" to "ORD-1", done[11] to done[41])
    }

    @Test
    fun `a cancel sent before the acknowledgement is parked, then sent once it comes`() {
        val adapter = start()
        adapter.awaitExchange()
        val session = adapter.logon()
        adapter.send(session, order("ORD-1"))
        pollUntil(*adapter.all(), what = "the order at the gateway") { adapter.gateway(0).orders.size == 1 }
        adapter.send(session, cancelOf("ORD-1", "CXL-1"))
        pollFor(200, *adapter.all())
        assertTrue(adapter.gateway(0).cancels.isEmpty(), "parked (§3.3)")
        val o = adapter.gateway(0).orders.single()
        adapter.gateway(0).report(1001L, o.clOrdId, ExecType.NEW, 5001L, price = o.price, leavesQty = o.qty, origQty = o.qty)
        pollUntil(*adapter.all(), what = "the parked cancel sent") { adapter.gateway(0).cancels.size == 1 }
        assertEquals(5001L, adapter.gateway(0).cancels.single().exchangeOrderId)
    }

    @Test
    fun `an order unanswered at a restart is restored, fenced, and rejected not sequenced`() {
        var (sent, received) = start().use { adapter ->
            adapter.awaitExchange()
            val session = adapter.logon()
            adapter.send(session, order("ORD-1"))
            pollUntil(*adapter.all(), what = "the order at the gateway") { adapter.gateway(0).orders.size == 1 }
            pollUntil(*adapter.all(), what = "logout sent") { session.startLogout() > 0 }
            pollUntil(*adapter.all(), what = "logged out") { !session.isConnected }
            session.lastSentMsgSeqNum() to session.lastReceivedMsgSeqNum()
        }
        // The gateway never answered it. After the restart the SDK fences, the resend completes with
        // nothing for it: never sequenced (most-exchange Adapters.md §5).
        start().use { adapter ->
            adapter.awaitExchange()
            val session = adapter.logon(initialSent = sent + 1, initialReceived = received + 1)
            val reject = adapter.awaitReply(session, "the reject") { it.msgType == "8" && it.seqNum > received }
            assertEquals("ORD-1", reject[11])
            assertEquals("8" to "8", reject[150] to reject[39])
            assertEquals("99", reject[103])
            assertEquals(Texts.NOT_SEQUENCED_ORDER, reject[58])
            assertTrue(assertNotNull(reject[17]).startsWith("A"), "the adapter's own ExecID")
            assertEquals(1, adapter.rig.log.count { "requests restored in flight" in it }, "on its own shard only: ${adapter.rig.log}")
            pollFor(300, *adapter.all())
            assertEquals(1, session.messages().count { it.seqNum > received && it[11] == "ORD-1" && it[150] == "8" }, "settled once")
        }
    }

    /**
     * ORD-1 sent to the gateway, the client logged out, the adapter stopped; then the row lost, as a
     * host crash loses it from the unsynced WAL while the archive has the 35=D (§6.1, §8.20). Returns
     * the client's last sent and received MsgSeqNums, and the lost row's `clOrdId`.
     */
    private fun sentThenLost(): Triple<Int, Int, String> {
        val (sent, received) = start().use { adapter ->
            adapter.awaitExchange()
            val session = adapter.logon()
            adapter.send(session, order("ORD-1"))
            pollUntil(*adapter.all(), what = "the order at the gateway") { adapter.gateway(0).orders.size == 1 }
            pollUntil(*adapter.all(), what = "logout sent") { session.startLogout() > 0 }
            pollUntil(*adapter.all(), what = "logged out") { !session.isConnected }
            session.lastSentMsgSeqNum() to session.lastReceivedMsgSeqNum()
        }
        val clOrdId = OrderStore.open(OrderEntryRig.storeDir(adapterDir)).use { store ->
            store.load(OrderEntryRig.TODAY)
            var lost = ""
            store.forEach { if (it.kind == RowKind.ORDER) lost = it.fixClOrdIdAsString() }
            store.loseForTest(1001L, lost)
            lost
        }
        return Triple(sent, received, clOrdId)
    }

    @Test
    fun `a 35=D whose row a host crash lost is rebuilt from the archive before the bind, never sent again, and settled by the fence`() {
        // Design.md §8.17.
        val (sent, received, clOrdId) = sentThenLost()
        start().use { adapter ->
            val row = assertNotNull(adapter.rig.backend.store.findByFix(1001L, clOrdId), "rebuilt before the bind: ${adapter.rig.log}")
            assertEquals("ORD-1", row.fixClOrdIdAsString())
            adapter.awaitExchange()
            val session = adapter.logon(initialSent = sent + 1, initialReceived = received + 1)
            // The gateway never answered it: the fence settles it never sequenced (§3.4).
            val reject = adapter.awaitReply(session, "the reject") { it.msgType == "8" && it.seqNum > received }
            assertEquals("ORD-1", reject[11])
            assertEquals(Texts.NOT_SEQUENCED_ORDER, reject[58])
            assertEquals("ACME", reject[55], "its security resolved once the directory came")
            pollFor(300, *adapter.all())
            assertTrue(adapter.gateway(0).orders.isEmpty(), "never sent again: the exchange does not check clOrdId")
            assertEquals(1, session.messages().count { it.seqNum > received && it[11] == "ORD-1" }, "answered once")
            assertTrue(adapter.rig.log.any { "1 requests rebuilt from the archive" in it }, "${adapter.rig.log}")
        }
    }

    @Test
    fun `a recovered order whose security the directory no longer lists is refused unknown security, stating no security`() {
        // §8.17, confirmed 2026-10-27: OrdRejReason 99, "unknown security", and no 48.
        val (sent, received, _) = sentThenLost()
        start(broadcast = DirectoryBroadcast(1, TWO_SHARDS.shards, listOf(BETA))).use { adapter ->
            adapter.awaitExchange()
            val session = adapter.logon(initialSent = sent + 1, initialReceived = received + 1)
            val reject = adapter.awaitReply(session, "the reject") { it.msgType == "8" && it.seqNum > received }
            assertEquals("ORD-1", reject[11])
            assertEquals("8" to "8", reject[150] to reject[39])
            assertEquals("99", reject[103])
            assertEquals(Texts.UNKNOWN_SECURITY, reject[58])
            assertEquals(null to null, reject[48] to reject[55], "no securityId to state")
            pollFor(300, *adapter.all())
            assertTrue(adapter.exchange.gateways.values.all { it.orders.isEmpty() })
        }
    }

    @Test
    fun `rows two sessions lost are each rebuilt from that session's own archived messages, for its own participant`() {
        // Design.md §8.17: the startup replay of a participant's offline session rebuilds its rows.
        // Two sessions replay at once: Artio hands every offline session the same connection id.
        val orders = 20
        val lost = 5
        start().use { adapter ->
            adapter.awaitExchange()
            val one = adapter.logon(CLIENT1)
            val two = adapter.logon(CLIENT2)
            for (i in 1..orders) {
                adapter.send(one, order("ONE-$i"))
                adapter.send(two, order("TWO-$i"))
            }
            pollUntil(*adapter.all(), what = "every order at the gateway") { adapter.gateway(0).orders.size == 2 * orders }
            for (session in listOf(one, two)) {
                pollUntil(*adapter.all(), what = "logout sent") { session.startLogout() > 0 }
                pollUntil(*adapter.all(), what = "logged out") { !session.isConnected }
            }
        }
        // The last rows of each, lost as a host crash loses them while the archive has the 35=Ds.
        val expected = OrderStore.open(OrderEntryRig.storeDir(adapterDir)).use { store ->
            store.load(OrderEntryRig.TODAY)
            val byParticipant = mutableMapOf<Long, MutableList<Pair<Long, String>>>()
            store.forEach { if (it.kind == RowKind.ORDER) byParticipant.getOrPut(it.participantId) { mutableListOf() } += it.position to it.fixClOrdIdAsString() }
            byParticipant.mapValues { (participantId, rows) ->
                rows.sortedBy { it.first }.takeLast(lost).onEach { (_, fixClOrdId) -> store.loseForTest(participantId, fixClOrdId) }
            }
        }
        assertEquals(setOf(1001L, 1002L), expected.keys)

        start().use { adapter ->
            val store = adapter.rig.backend.store
            for ((participantId, rows) in expected) {
                for ((_, fixClOrdId) in rows) {
                    val row = assertNotNull(store.findByFix(participantId, fixClOrdId), "participant $participantId's $fixClOrdId rebuilt: ${adapter.rig.log}")
                    assertEquals(fixClOrdId, row.fixClOrdIdAsString(), "participant $participantId's own order")
                }
            }
            val prefixes = mapOf(1001L to "ONE-", 1002L to "TWO-")
            store.forEach { row ->
                assertTrue(row.fixClOrdIdAsString().startsWith(prefixes.getValue(row.participantId)), "${row.fixClOrdIdAsString()} held for participant ${row.participantId}")
            }
            assertTrue(adapter.rig.log.any { "${2 * lost} requests rebuilt from the archive" in it }, "${adapter.rig.log}")
        }
    }

    /** A Logon and a Logout; the client's sequence numbers after it, sent and received. */
    private fun Adapter.logOnAndOff(): Pair<Int, Int> {
        val session = logon()
        pollUntil(*all(), what = "logout sent") { session.startLogout() > 0 }
        pollUntil(*all(), what = "logged out") { !session.isConnected }
        pollUntil(*all(), what = "the session back offline") { rig.backend.sessions.session(1001L) != null }
        return session.lastSentMsgSeqNum() to session.lastReceivedMsgSeqNum()
    }

    @Test
    fun `after an unclean stop the adapter numbers on 10 000 past its last message, a gap fill covers the jump, and the session carries on`() {
        // Design.md §8.21: a 35=8 can reach the client and not the archive, and a restarted engine
        // numbering from the archive reuses its MsgSeqNum, which the client refuses.
        val (sent, received) = start().let { adapter ->
            adapter.awaitExchange()
            adapter.logOnAndOff().also { adapter.crash() }
        }
        start().use { adapter ->
            assertTrue(adapter.rig.log.any { "MsgSeqNum moved on from $received to ${received + 10_000}" in it }, "${adapter.rig.log}")
            adapter.awaitExchange()
            val session = adapter.logon(initialSent = sent + 1, initialReceived = received + 1)
            val logon = adapter.awaitReply(session, "the adapter's Logon") { it.msgType == "A" && it.seqNum > received }
            // The skip is 10 000 Heartbeats written offline (§8.29, decided 2026-10-31), so the Logon is next.
            assertEquals(received + 10_001, logon.seqNum, "the skip's Heartbeats written offline, then the Logon")
            val gapFill = adapter.awaitReply(session, "the gap fill") { it.msgType == "4" }
            assertEquals("Y", gapFill[123], "a gap fill, not a reset")
            assertEquals(received + 1, gapFill.seqNum, "from what the client expected")
            assertEquals(received + 10_002, gapFill[36]?.toInt(), "to past the Logon, in one message")
            adapter.send(session, order("AFTER"))
            pollUntil(*adapter.all(), what = "the order at its gateway") { adapter.gateway(0).orders.size == 1 }
            assertTrue(session.isConnected, "the session carried on")
        }
    }

    @Test
    fun `after an unclean stop a report written offline is still resent at the client's next Logon`() {
        // Design.md §6.3: a report for a logged-out client is written offline and resent at its
        // Logon; §8.21: the skip costs the client one gap fill, nothing else. §8.29: a skip that left
        // a hole in Artio's replay index, whose lookup assumes one record per MsgSeqNum, had every
        // later resend answered with a gap fill instead.
        val (sent, received) = start().let { adapter ->
            adapter.awaitExchange()
            adapter.logOnAndOff().also { adapter.crash() }
        }
        start().use { adapter ->
            adapter.awaitExchange()
            val session = adapter.logon(initialSent = sent + 1, initialReceived = received + 1)
            adapter.awaitReply(session, "the gap fill over the skip") { it.msgType == "4" }
            adapter.send(session, order("AFTER"))
            pollUntil(*adapter.all(), what = "the order at its gateway") { adapter.gateway(0).orders.size == 1 }
            pollUntil(*adapter.all(), what = "logout sent") { session.startLogout() > 0 }
            pollUntil(*adapter.all(), what = "logged out") { !session.isConnected }
            val lastReceived = session.lastReceivedMsgSeqNum()
            pollUntil(*adapter.all(), what = "the session back offline") { adapter.rig.backend.sessions.session(1001L) != null }
            val o = adapter.gateway(0).orders.single()
            adapter.gateway(0).report(1001L, o.clOrdId, ExecType.NEW, 5001L, price = o.price, leavesQty = o.qty, origQty = o.qty)
            pollUntil(*adapter.all(), what = "reportSeq 1 consumed behind the archive (${adapter.rig.log})") {
                adapter.rig.backend.store.firstMissing(1001L, 0) == 2L
            }
            val again = adapter.logon(initialSent = session.lastSentMsgSeqNum() + 1, initialReceived = lastReceived + 1)
            val ack = adapter.awaitReply(again, "the acknowledgement resent") { it.msgType == "8" && it.seqNum > lastReceived }
            assertEquals(lastReceived + 1, ack.seqNum, "the report written offline, numbered on from the last sent")
            assertEquals("0", ack[150])
            assertEquals("Y", ack[43], "Artio's resend of what was archived offline, not a gap fill over it")
        }
    }

    @Test
    fun `after a clean stop the adapter numbers on from its last message`() {
        val (sent, received) = start().use { adapter ->
            adapter.awaitExchange()
            adapter.logOnAndOff()
        }
        start().use { adapter ->
            assertTrue(adapter.rig.log.none { "moved on" in it }, "${adapter.rig.log}")
            adapter.awaitExchange()
            val session = adapter.logon(initialSent = sent + 1, initialReceived = received + 1)
            val logon = adapter.awaitReply(session, "the adapter's Logon") { it.msgType == "A" && it.seqNum > received }
            assertEquals(received + 1, logon.seqNum)
            pollFor(300, *adapter.all())
            assertTrue(session.messages().none { it.msgType == "4" }, "${session.messages()}")
        }
    }

    @Test
    fun `a request rejected here is not replayed at a clean restart, so it is never rejected twice`() {
        // §8.17: the reject raised the high-water. "exchange unavailable" needed the directory, so a
        // replay would decide it again, as sent.
        val (sent, received) = start(broadcast = null).use { adapter ->
            val session = adapter.logon()
            adapter.send(session, order("ORD-1"))
            adapter.awaitReply(session, "the reject") { it[11] == "ORD-1" && it[58] == Texts.EXCHANGE_UNAVAILABLE }
            pollUntil(*adapter.all(), what = "logout sent") { session.startLogout() > 0 }
            pollUntil(*adapter.all(), what = "logged out") { !session.isConnected }
            session.lastSentMsgSeqNum() to session.lastReceivedMsgSeqNum()
        }
        start().use { adapter ->
            assertEquals(0, adapter.rig.backend.store.size, "nothing rebuilt: ${adapter.rig.log}")
            adapter.awaitExchange()
            val session = adapter.logon(initialSent = sent + 1, initialReceived = received + 1)
            pollFor(300, *adapter.all())
            assertTrue(session.messages().none { it.seqNum > received && it[11] == "ORD-1" }, "${session.messages()}")
        }
    }

    @Test
    fun `a report for a client logged out goes to its offline session, is consumed once archived, and is resent at its Logon`() {
        // Design.md §6.3: delivery does not wait for the client, nor depend on the exchange's ring.
        val adapter = start()
        adapter.awaitExchange()
        val session = adapter.logon()
        adapter.send(session, order("ORD-1"))
        pollUntil(*adapter.all(), what = "the order at the gateway") { adapter.gateway(0).orders.size == 1 }
        pollUntil(*adapter.all(), what = "logout sent") { session.startLogout() > 0 }
        pollUntil(*adapter.all(), what = "logged out") { !session.isConnected }
        val received = session.lastReceivedMsgSeqNum()
        pollUntil(*adapter.all(), what = "the session back offline") { adapter.rig.backend.sessions.session(1001L) != null }
        val o = adapter.gateway(0).orders.single()
        adapter.gateway(0).report(1001L, o.clOrdId, ExecType.NEW, 5001L, price = o.price, leavesQty = o.qty, origQty = o.qty)
        pollUntil(*adapter.all(), what = "reportSeq 1 consumed behind the archive (${adapter.rig.log})") {
            adapter.rig.backend.store.firstMissing(1001L, 0) == 2L
        }
        assertTrue(!session.isConnected, "while the client is still away")
        val again = adapter.logon(initialSent = session.lastSentMsgSeqNum() + 1, initialReceived = received + 1)
        val ack = adapter.awaitReply(again, "the acknowledgement resent") { it.msgType == "8" && it.seqNum > received }
        assertEquals(received + 1, ack.seqNum, "numbered by Artio, on from the last sent")
        assertEquals("1", ack[17])
        assertEquals("0", ack[150])
        assertEquals("Y", ack[43], "Artio's resend of what was archived offline")
    }

    @Test
    fun `after a restart a session is held offline before its client logs on, so an answer owed then reaches it at its Logon`() {
        val (sent, received) = start().use { adapter ->
            adapter.awaitExchange()
            val session = adapter.logon()
            adapter.send(session, order("ORD-1"))
            pollUntil(*adapter.all(), what = "the order at the gateway") { adapter.gateway(0).orders.size == 1 }
            pollUntil(*adapter.all(), what = "logout sent") { session.startLogout() > 0 }
            pollUntil(*adapter.all(), what = "logged out") { !session.isConnected }
            session.lastSentMsgSeqNum() to session.lastReceivedMsgSeqNum()
        }
        start().use { adapter ->
            // Requested offline before the bind (§6.3): held, though the client is not back.
            val offline = assertNotNull(adapter.rig.backend.sessions.session(1001L), "held at startup: requests ${adapter.rig.backend.sessions.requests}, engine ${adapter.rig.acceptor.engine.allSessions().map { it.sessionId() to it.sessionKey() }}, log ${adapter.rig.log}")
            assertTrue(!offline.isConnected)
            adapter.awaitExchange()
            // The SDK fences the restored order, the resend completes without it: never sequenced,
            // answered on the offline session while the client is away.
            pollUntil(*adapter.all(), what = "the reject written offline") { offline.lastSentMsgSeqNum() > received }
            val again = adapter.logon(initialSent = sent + 1, initialReceived = received + 1)
            val reject = adapter.awaitReply(again, "the reject resent") { it.msgType == "8" && it.seqNum > received }
            assertEquals(received + 1, reject.seqNum)
            assertEquals("Y", reject[43], "resent by Artio from the archive")
            assertEquals(Texts.NOT_SEQUENCED_ORDER, reject[58])
        }
    }

    @Test
    fun `a client that logs back on before the library has its session again loses no 35=D`() {
        // Design.md §6.3: the request after a disconnect replays from the last message received + 1.
        // A client back first is handed over online with that replay; with none, its 35=D is skipped.
        val adapter = start()
        adapter.awaitExchange()
        val session = adapter.logon()
        // The connection dropped, no Logout: the adapter is not polled from here, so its library has
        // not seen the disconnect. Artio's engine, on its own threads, owns the session from it,
        // accepts the Logon and the 35=D, and announces the session to that library.
        pollUntil(adapter.exchange, client, what = "disconnect requested") { session.requestDisconnect() > 0 }
        pollUntil(adapter.exchange, client, what = "disconnected") { !session.isConnected }
        val reply = client.logon(port, CLIENT1, "client1", SECRET1, session.lastSentMsgSeqNum() + 1, session.lastReceivedMsgSeqNum() + 1, false)
        pollUntil(adapter.exchange, client, what = "logged on again, engine-owned") { !reply.isExecuting }
        val again = reply.resultIfPresent().also { sessions += it }
        pollUntil(adapter.exchange, client, what = "a 35=D sent") { again.trySend(order("ORD-2")) > 0 }
        pollFor(200, adapter.exchange, client)
        pollUntil(*adapter.all(), what = "the 35=D at the gateway (${adapter.rig.log})") { adapter.gateway(0).orders.size == 1 }
        assertEquals("ORD-2", adapter.gateway(0).orders.single().clOrdId)
        assertEquals(again.lastSentMsgSeqNum().toLong(), adapter.rig.backend.store.findByFix(1001L, "ORD-2")?.position)
    }

    @Test
    fun `a mass status after a truncated resend on one shard settles only that shard's orders`() {
        val adapter = start()
        adapter.awaitExchange()
        val session = adapter.logon()
        adapter.send(session, order("ORD-A", "ACME"))
        adapter.send(session, order("ORD-B", "BETA"))
        pollUntil(*adapter.all(), what = "both orders at their gateways") { adapter.gateway(0).orders.size == 1 && adapter.gateway(1).orders.size == 1 }
        val a = adapter.gateway(0).orders.single()
        val b = adapter.gateway(1).orders.single()
        adapter.gateway(0).report(1001L, a.clOrdId, ExecType.NEW, 5001L, price = a.price, leavesQty = a.qty, origQty = a.qty, securityId = 42)
        // Shard 1 loses a report: the SDK sees the gap, fences, the resend is truncated, and a mass
        // status of shard 1 follows (most-exchange Adapters.md §4). It states B, the one order open there.
        val statusesBefore = adapter.gateway(1).statusesAnswered
        adapter.gateway(1).lose(1001L)
        adapter.gateway(1).truncateNextResend = true
        adapter.gateway(1).report(1001L, b.clOrdId, ExecType.NEW, 6001L, price = b.price, leavesQty = b.qty, origQty = b.qty, securityId = 43)
        pollUntil(*adapter.all(), what = "shard 1's mass status") { adapter.gateway(1).statusesAnswered > statusesBefore }
        pollFor(300, *adapter.all())
        val reports = adapter.reports(session)
        assertEquals(2, reports.size, "the two acknowledgements, and nothing else: $reports")
        assertTrue(reports.none { it[5001] == "Y" }, "shard 0's open order is not shard 1's status's to settle")
        // Each shard's sequence stored on its own (§6.1): shard 0 consumed 1, shard 1 resumed past the status.
        pollUntil(*adapter.all(), what = "each shard's reportSeq stored") {
            adapter.rig.backend.store.firstMissing(1001L, 0) == 2L && adapter.rig.backend.store.firstMissing(1001L, 1) == adapter.gateway(1).nextSeq(1001L)
        }
    }

    private fun cancelOf(origClOrdId: String, clOrdId: String) = OrderCancelRequestEncoder().apply {
        origClOrdID(origClOrdId)
        clOrdID(clOrdId)
        side('1')
        transactTime(timestamp.buffer(), timestamp.encode(System.currentTimeMillis()))
        instrument().symbol("ACME")
    }

    // ================================================================ a gap Artio leaves open, §2, §8.21

    private fun Adapter.polled() = arrayOf<Polled>(rig, exchange)

    private fun Adapter.ready() = pollUntil(*polled(), what = "the SDK's sessions ready") { rig.backend.readyShards == 2 }

    /** A raw client logged on at [seq], the Logon answered. */
    private fun Adapter.rawLogon(seq: Int): RawFixClient = RawFixClient(port).also { c ->
        closing.addFirst(c)
        c.logon(seq)
        pollUntil(*polled(), what = "the raw client's Logon answered") { c.received("A").isNotEmpty() }
    }

    /** The MsgSeqNums of the 35=Ds that reached shard 0's gateway, from their rows' positions (§3.2). */
    private fun Adapter.sequenced(): List<Int> =
        gateway(0).orders.map { (assertNotNull(rig.backend.store.findByFix(it.participantId, it.clOrdId)).position and 0xffffffffL).toInt() }

    private fun OrderStore.findByFix(participantId: Long, clOrdId: String): RequestRow? = find(participantId, ClOrdId.of(clOrdId))

    /** Spike (h4)'s client: a gap at 3–4, new orders during the resend, an answer that stops at 4, then more. */
    private fun Adapter.answerShort(c: RawFixClient) {
        c.order(2)
        c.order(5)
        pollUntil(*polled(), what = "Artio's ResendRequest") { c.received("2").isNotEmpty() }
        c.order(6)
        c.order(7)
        pollFor(200, *polled())
        c.order(3, possDup = true)
        c.gapFill(4, 5)
        pollFor(200, *polled())
        c.order(8)
        c.order(9)
    }

    @Test
    fun `a client that answers a resend short is asked again by the adapter, and every order reaches its gateway once, logged on`() {
        start().use { adapter ->
            adapter.ready()
            val c = adapter.rawLogon(1)
            adapter.answerShort(c)
            pollUntil(*adapter.polled(), timeoutMs = 5_000, what = "the adapter's own ResendRequest") { c.received("2").size == 2 }
            val asked = c.received("2")[1]
            assertEquals("5" to "9", asked[7] to asked[16], "from the first dropped to the last")
            for (seq in 5..9) c.order(seq, possDup = true)
            c.order(10)
            pollUntil(*adapter.polled(), what = "every order at its gateway") { adapter.sequenced().size >= 8 }
            pollFor(500, *adapter.polled())
            assertEquals(listOf(2, 3, 5, 6, 7, 8, 9, 10), adapter.sequenced().sorted(), "each once")
            assertTrue(!c.closed, "still logged on")
            assertEquals(1L, adapter.rig.backend.gaps.requested)
        }
    }

    @Test
    fun `a 35=D in a gap left open by a disconnect is asked for after the next Logon, and reaches its gateway`() {
        start().use { adapter ->
            adapter.ready()
            val first = adapter.rawLogon(1)
            first.order(2)
            first.order(5)
            pollUntil(*adapter.polled(), what = "Artio's ResendRequest") { first.received("2").isNotEmpty() }
            first.close()
            pollFor(500, *adapter.polled())
            val second = adapter.rawLogon(6)
            pollUntil(*adapter.polled(), timeoutMs = 2_000, what = "the adapter's ResendRequest after the Logon") { second.received("2").isNotEmpty() }
            val asked = second.received("2").single()
            assertEquals("3" to "5", asked[7] to asked[16], "the gap Artio forgot (h3)")
            for (seq in 3..5) second.order(seq, possDup = true)
            second.order(7)
            pollUntil(*adapter.polled(), what = "every order at its gateway") { adapter.sequenced().size >= 5 }
            pollFor(500, *adapter.polled())
            assertEquals(listOf(2, 3, 4, 5, 7), adapter.sequenced().sorted())
        }
    }

    @Test
    fun `a gap left open survives a restart, is asked for after the first Logon, and its 35=Ds reach the gateway`() {
        start().use { adapter ->
            adapter.ready()
            val first = adapter.rawLogon(1)
            first.order(2)
            first.order(5)
            pollUntil(*adapter.polled(), what = "Artio's ResendRequest") { first.received("2").isNotEmpty() }
            first.close()
            pollUntil(*adapter.polled(), what = "the order at its gateway") { adapter.sequenced().isNotEmpty() }
            pollFor(500, *adapter.polled())
        }
        start().use { adapter ->
            adapter.ready()
            val second = adapter.rawLogon(6)
            pollUntil(*adapter.polled(), timeoutMs = 2_000, what = "the adapter's ResendRequest after the Logon") { second.received("2").isNotEmpty() }
            val asked = second.received("2").single()
            assertEquals("3" to "5", asked[7] to asked[16])
            for (seq in 3..5) second.order(seq, possDup = true)
            second.order(7)
            pollUntil(*adapter.polled(), what = "the orders at the gateway") { adapter.sequenced().size >= 4 }
            pollFor(500, *adapter.polled())
            assertEquals(listOf(3, 4, 5, 7), adapter.sequenced().sorted(), "after the restart: the gap's and the new one, nothing sent twice or rebuilt")
            assertTrue(adapter.rig.log.none { "rebuilt" in it }, "${adapter.rig.log}")
        }
    }

    @Test
    fun `a stall left by a disconnect is asked for after a restart, its dropped 35=Ds in the archive counted by neither the replay nor recovery`() {
        // The 35=Ds Artio dropped during the stall are in its archive, and the startup replay hands them back as valid.
        start().use { adapter ->
            adapter.ready()
            val first = adapter.rawLogon(1)
            adapter.answerShort(first)
            pollFor(300, *adapter.polled())
            first.close()
            pollUntil(*adapter.polled(), what = "the orders before the stall at the gateway") { adapter.sequenced().size >= 2 }
            pollFor(500, *adapter.polled())
            assertEquals(listOf(2, 3), adapter.sequenced().sorted())
        }
        start().use { adapter ->
            adapter.ready()
            val second = adapter.rawLogon(10)
            pollUntil(*adapter.polled(), timeoutMs = 2_000, what = "the adapter's ResendRequest after the Logon") { second.received("2").isNotEmpty() }
            val asked = second.received("2").single()
            assertEquals("5" to "9", asked[7] to asked[16])
            for (seq in 5..9) second.order(seq, possDup = true)
            second.order(11)
            pollUntil(*adapter.polled(), what = "the orders at the gateway") { adapter.sequenced().size >= 6 }
            pollFor(500, *adapter.polled())
            assertEquals(listOf(5, 6, 7, 8, 9, 11), adapter.sequenced().sorted())
            assertTrue(adapter.rig.log.none { "rebuilt" in it }, "${adapter.rig.log}")
        }
    }

    @Test
    fun `a client that never fills the gap is logged out after three requests, with a Text naming the range`() {
        start().use { adapter ->
            adapter.ready()
            val c = adapter.rawLogon(1)
            c.order(2)
            c.order(5)
            pollUntil(*adapter.polled(), timeoutMs = 8_000, what = "the logout") { c.closed }
            assertEquals(4, c.received("2").size, "Artio's own, then the adapter's three")
            assertEquals("messages 3 to 5 not received; resend them", c.received("5").single()[58])
            assertEquals(3 to 5, adapter.rig.backend.store.receiveGapFrom(1001L) to adapter.rig.backend.store.receiveGapTo(1001L), "kept")
        }
    }

    @Test
    fun `a session whose messages all arrive in order is never asked for a resend by the adapter`() {
        start().use { adapter ->
            adapter.ready()
            val c = adapter.rawLogon(1)
            for (seq in 2..6) c.order(seq)
            c.send("0", 7, emptyList())
            c.send("1", 8, listOf(112 to "PING"))
            pollUntil(*adapter.polled(), what = "the Heartbeat answering the TestRequest") { c.received("0").isNotEmpty() }
            c.order(9)
            pollFor(2_500, *adapter.polled())
            assertEquals(emptyList(), c.received("2"))
            assertEquals(listOf(2, 3, 4, 5, 6, 9), adapter.sequenced().sorted())
            assertEquals(0L, adapter.rig.backend.gaps.requested)
        }
    }

    // ================================================================ gateways, §3.5

    @Test
    fun `a shard's listed gateways replace the directory's endpoint`() {
        val listed = GatewayEndpoint("aeron:ipc", 9400, "aeron:ipc", 9401)
        val adapter = start(gateways = mapOf(0 to listOf(listed)))
        adapter.exchange.gateway(listed)
        adapter.awaitExchange()
        val session = adapter.logon()
        adapter.send(session, order("ORD-1"))
        pollUntil(*adapter.all(), what = "the order at the listed gateway") { adapter.exchange.gateway(listed).orders.size == 1 }
        assertTrue(adapter.gateway(0).orders.isEmpty(), "the directory's endpoint is not used")
        assertEquals(0, adapter.gateway(0).statusesAnswered)
    }

    @Test
    fun `a shard that appears in a later directory gets its session`() {
        val adapter = start(broadcast = DirectoryBroadcast(1, listOf(shard(0)), listOf(ACME)))
        adapter.awaitExchange(shards = 1)
        adapter.gateway(1)
        adapter.exchange.broadcast = TWO_SHARDS.let { DirectoryBroadcast(2, it.shards, it.securities) }
        adapter.awaitExchange(shards = 2)
        val session = adapter.logon()
        adapter.send(session, order("ORD-B", "BETA"))
        pollUntil(*adapter.all(), what = "BETA at shard 1") { adapter.gateway(1).orders.size == 1 }
    }

    // ================================================================ the guards, §3.2

    @Test
    fun `the adapter refuses to start, unbound, when Artio does not know a session the store holds rows for`() {
        OrderStore.open(OrderEntryRig.storeDir(adapterDir)).use { store ->
            store.load(OrderEntryRig.TODAY)
            store.insert(row(store, 1001L, 5L, "OLD-5"))
        }
        val config = AcceptorRig.nodeConfig(adapterDir, port)
        FixAcceptor(config, bindAtStartup = false).use { acceptor ->
            val refusal = assertFailsWith<IllegalStateException> {
                OrderEntryBackend(acceptor, OrderEntryConfig(OrderEntryRig.storeDir(adapterDir), "aeron:ipc", 9100), { OrderEntryRig.TODAY })
            }
            assertTrue("1001" in refusal.message.orEmpty() && "Artio does not know" in refusal.message.orEmpty(), refusal.message)
            assertFailsWith<ConnectException>("nothing listens") { Socket().use { it.connect(InetSocketAddress("localhost", port), 1_000) } }
        }
    }

    @Test
    fun `the adapter refuses to start when Artio's sequenceIndex is below the stored one`() {
        start().use { adapter ->
            adapter.awaitExchange()
            val session = adapter.logon()
            pollUntil(*adapter.all(), what = "logout sent") { session.startLogout() > 0 }
            pollUntil(*adapter.all(), what = "logged out") { !session.isConnected }
        }
        OrderStore.open(OrderEntryRig.storeDir(adapterDir)).use { store ->
            store.load(OrderEntryRig.TODAY)
            store.recordSequenceIndex(1001L, 2)
        }
        val refusal = assertFailsWith<IllegalStateException> { OrderEntryRig(adapterDir, port) }
        assertTrue("sequenceIndex" in refusal.message.orEmpty(), refusal.message)
    }

    // ================================================================ no id collision since schema 8, §8.15 superseded

    @Test
    fun `a 35=D at a position an earlier life's rows hold reaches the exchange with its own ClOrdID, and the session stays logged on`() {
        // Design.md §3.2, R§17: what was an id collision is a new request, never a block.
        // A first life: Artio comes to know CLIENT1's session, at sequenceIndex 0.
        val (sent, received) = start().use { adapter ->
            adapter.awaitExchange()
            val session = adapter.logon()
            pollUntil(*adapter.all(), what = "logout sent") { session.startLogout() > 0 }
            pollUntil(*adapter.all(), what = "logged out") { !session.isConnected }
            session.lastSentMsgSeqNum() to session.lastReceivedMsgSeqNum()
        }
        // Rows from an earlier life of the same sequence: every position the client takes next is held.
        OrderStore.open(OrderEntryRig.storeDir(adapterDir)).use { store ->
            store.load(OrderEntryRig.TODAY)
            for (n in 1..sent + 20) store.insert(row(store, 1001L, n.toLong(), "WIPED-$n"))
        }
        // Loading cleared the clean-stop mark; this is a clean restart (§8.21).
        OrderStore.markStoppedCleanly(OrderEntryRig.storeDir(adapterDir))

        start().use { adapter ->
            adapter.awaitExchange()
            val session = adapter.logon(initialSent = sent + 1, initialReceived = received + 1)
            adapter.send(session, order("NEW-1"))
            pollUntil(*adapter.all(), what = "the order at the gateway (${adapter.rig.log})") { adapter.gateway(0).orders.size == 1 }
            assertEquals("NEW-1", adapter.gateway(0).orders.single().clOrdId)
            pollFor(200, *adapter.all())
            assertTrue(session.isConnected, "not logged out")
            assertTrue(
                session.messages().none { (it.msgType == "8" || it.msgType == "5") && it.seqNum > received },
                "no reject, no Logout: ${session.messages()}",
            )
        }
    }

    private fun row(store: OrderStore, participantId: Long, position: Long, fixClOrdId: String) = store.newRow().apply {
        kind = RowKind.ORDER
        this.participantId = participantId
        this.position = position
        fixClOrdId(fixClOrdId)
        entryTradingDate = OrderEntryRig.TODAY
        securityId = 42
        side = Side.BUY
        price = 100_00000000L
        origQty = 1_00000000L
        leavesQty = 1_00000000L
        timeInForce = '1'.code.toByte()
        acknowledged = true
        exchangeOrderId = 7_000L + position
        ordStatus = '0'.code.toByte()
    }

    private companion object {
        val ACME = TestSecurity(42, 0, "ACME", "NL0000000042")
        val BETA = TestSecurity(43, 1, "BETA", "NL0000000043")
        val TWO_SHARDS = DirectoryBroadcast(1, listOf(shard(0), shard(1)), listOf(ACME, BETA))
    }
}
