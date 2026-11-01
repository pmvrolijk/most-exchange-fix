package nl.lamia.most.exchange.fixadapter.orderentry.outbound

import nl.lamia.most.exchange.client.ClOrdId
import nl.lamia.most.exchange.client.OrderFate
import nl.lamia.most.exchange.client.ReportAction
import nl.lamia.most.exchange.client.SendResult
import nl.lamia.most.exchange.fixadapter.orderentry.inbound.ArchivedSends
import nl.lamia.most.exchange.fixadapter.orderentry.inbound.OrderVenue
import nl.lamia.most.exchange.fixadapter.orderentry.inbound.Texts
import nl.lamia.most.exchange.fixadapter.orderentry.store.CancelState
import nl.lamia.most.exchange.fixadapter.orderentry.store.InsertResult
import nl.lamia.most.exchange.fixadapter.orderentry.store.OrderStore
import nl.lamia.most.exchange.fixadapter.orderentry.store.RequestRow
import nl.lamia.most.exchange.fixadapter.orderentry.store.RowKind
import nl.lamia.most.exchange.sbe.ClientExecutionReportDecoder
import nl.lamia.most.exchange.sbe.ClientExecutionReportEncoder
import nl.lamia.most.exchange.sbe.Enrichment
import nl.lamia.most.exchange.sbe.ExecType
import nl.lamia.most.exchange.sbe.MessageHeaderEncoder
import nl.lamia.most.exchange.sbe.RejectReason
import nl.lamia.most.exchange.sbe.RequestStatus
import nl.lamia.most.exchange.sbe.Side
import nl.lamia.most.exchange.sbe.SmpStrategy
import org.agrona.concurrent.UnsafeBuffer
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Order entry's outbound path (Design.md §3.3, §3.4, §5.3; CLAUDE.md non-negotiable 1), against
 * reports encoded as the gateway sends them (most-exchange `MatchingEngineService`'s rules: a cancel
 * is answered with the cancel's `clOrdId`, an unsolicited cancel with the order's).
 */
class OutboundReportsTest {

    @TempDir
    lateinit var dir: File

    private lateinit var store: OrderStore
    private val client = RecordingClient()
    private val venue = CancelVenue()
    private lateinit var delivery: ArchiveModel
    private lateinit var sends: ArchivedSends
    private lateinit var outbound: OutboundReports

    /** How far the archive has recorded the engine's inbound stream (§6.1): all of it, unless a test says. */
    private var recorded = Long.MAX_VALUE

    @BeforeTest
    fun open() {
        store = OrderStore.open(dir).also { it.load(TODAY) }
        delivery = ArchiveModel(store) // a new process: what waited for the archive is gone
        sends = ArchivedSends(store, venue, { recorded }) { row, text -> outbound.refuse(row, text) }
        outbound = OutboundReports(store, client, sends, delivery)
        outbound.recover()
    }

    /** One duty cycle: what is owed, then what is to send. */
    private fun cycle() {
        outbound.doWork()
        sends.doWork()
    }

    @AfterTest
    fun close() = store.close()

    private fun restart() {
        store.close()
        open()
    }

    // ---------------------------------------------------------------- rows, as the inbound path writes them

    private fun order(seq: Long, fix: String = "ORD-$seq", security: Int = ACME): RequestRow = store.newRow().apply {
        kind = RowKind.ORDER
        participantId = P
        clOrdId = seq
        fixClOrdId(fix)
        entryTradingDate = TODAY
        securityId = security
        shardId = if (security == OTHER_SHARD_SECURITY) 1 else 0
        side = Side.BUY
        price = 100_00000000L
        origQty = 10_00000000L
        leavesQty = 10_00000000L
        timeInForce = '0'.code.toByte()
        expireDate = TODAY
    }.also { assertEquals(InsertResult.NEW, store.insert(it)) }

    private fun cancel(seq: Long, order: RequestRow, state: CancelState = CancelState.SENT, fix: String = "CXL-$seq"): RequestRow =
        store.newRow().apply {
            kind = RowKind.CANCEL
            participantId = P
            clOrdId = seq
            fixClOrdId(fix)
            entryTradingDate = TODAY
            shardId = order.shardId
            origClOrdId = order.clOrdId
            cancelState = state
        }.also {
            assertEquals(InsertResult.NEW, store.insert(it))
            order.pendingCancel = it
        }

    private fun row(seq: Long) = assertNotNull(store.find(P, seq))

    // ---------------------------------------------------------------- reports, as the gateway sends them

    private val buffer = UnsafeBuffer(ByteArray(256))
    private val decoder = ClientExecutionReportDecoder()
    private val encoder = ClientExecutionReportEncoder()
    private val headerEncoder = MessageHeaderEncoder()
    private val headerDecoder = nl.lamia.most.exchange.sbe.MessageHeaderDecoder()
    private var nextSeq = 1L

    @Suppress("LongParameterList")
    private fun report(
        clOrdId: Long, execType: ExecType, exchangeOrderId: Long = 9000L + clOrdId,
        price: Long = 100_00000000L, lastQty: Long = 0L, leavesQty: Long = 10_00000000L, cumQty: Long = 0L,
        reason: RejectReason = RejectReason.NONE, reportSeq: Long = if (execType == ExecType.ORDER_STATUS) 0L else nextSeq++,
        enrichment: Enrichment = Enrichment.KNOWN, securityId: Int = ACME, shard: Int = if (securityId == OTHER_SHARD_SECURITY) 1 else 0,
    ): ReportAction {
        encoder.wrapAndApplyHeader(buffer, 0, headerEncoder)
            .participantId(P).exchangeOrderId(exchangeOrderId).price(price).lastQty(lastQty)
            .leavesQty(leavesQty).cumQty(cumQty).origQty(10_00000000L).securityId(securityId).rejectReason(reason)
            .execType(execType).side(Side.BUY).enrichment(enrichment).reportSeq(reportSeq)
        // Schema 8 bridge (Status §3 item 12, step 1): the derived id as decimal text, written with
        // nothing allocated, so the allocation test below measures the adapter alone.
        ClOrdId.encodeDecimal(clOrdId, buffer, encoder.offset() + ClientExecutionReportEncoder.clOrdIdEncodingOffset())
        ClOrdId.encodeDecimal(clOrdId, buffer, encoder.offset() + ClientExecutionReportEncoder.origClOrdIdEncodingOffset())
        decoder.wrapAndApplyHeader(buffer, 0, headerDecoder)
        return outbound.onReport(shard, decoder)
    }

    private fun sent8() = client.sent.filterIsInstance<Sent8>()
    private fun sent9() = client.sent.filterIsInstance<Sent9>()

    // ================================================================ order reports, §5.3

    @Test
    fun `a NEW is a 35=8 ExecType 0, ExecID its reportSeq, and the order is acknowledged`() {
        order(1)
        assertEquals(ReportAction.CONSUMED, report(1, ExecType.NEW, exchangeOrderId = 777L))
        val r = sent8().single()
        assertEquals(Sent8("ORD-1", null, 1L, 777L, '0', '0', 0L, 0L, 0L, 10_00000000L, 0L, null, null, false), r)
        assertTrue(row(1).acknowledged)
        assertEquals(777L, row(1).exchangeOrderId)
        assertEquals(1L, row(1).lastReportSeq)
    }

    @Test
    fun `fills are ExecType F, OrdStatus 1 then 2, with the engine's quantities and the average price`() {
        order(1)
        report(1, ExecType.NEW)
        report(1, ExecType.TRADE, price = 100_00000001L, lastQty = 3_00000000L, leavesQty = 7_00000000L, cumQty = 3_00000000L)
        report(1, ExecType.TRADE, price = 100_00000002L, lastQty = 7_00000000L, leavesQty = 0L, cumQty = 10_00000000L)
        val (first, second) = sent8().drop(1)
        assertEquals('F', first.execType)
        assertEquals('1', first.ordStatus)
        assertEquals(100_00000001L, first.lastPx)
        assertEquals(3_00000000L, first.lastQty)
        assertEquals(100_00000001L, first.avgPx)
        assertEquals('2', second.ordStatus)
        assertEquals(10_00000000L, second.cumQty)
        assertEquals(0L, second.leavesQty)
        assertEquals(100_00000002L, second.avgPx, "100.000000017, half up at 8 decimals")
        assertEquals('2'.code.toByte(), row(1).ordStatus)
    }

    @Test
    fun `an exchange reject is ExecType 8 with OrdRejReason mapped and the reason's name as Text`() {
        for ((seq, reason, code) in listOf(
            Triple(1L, RejectReason.UNKNOWN_SECURITY, 1), Triple(2L, RejectReason.MARKET_CLOSED, 2),
            Triple(3L, RejectReason.BOOK_CAPACITY, 99), Triple(4L, RejectReason.ORDER_EXPIRED, 99),
        )) {
            order(seq)
            report(seq, ExecType.REJECTED, exchangeOrderId = 0L, leavesQty = 0L, reason = reason)
            val r = sent8().last()
            assertEquals('8', r.execType)
            assertEquals('8', r.ordStatus)
            assertEquals(code, r.ordRejReason)
            assertEquals(reason.name, r.text)
            assertEquals(0L, r.orderId, "OrderID NONE")
        }
    }

    @Test
    fun `an expiry is ExecType C, and an unsolicited cancel carries the order's own ClOrdID and the reason`() {
        order(1)
        order(2)
        report(1, ExecType.NEW)
        report(2, ExecType.NEW)
        report(1, ExecType.EXPIRED, leavesQty = 0L)
        report(2, ExecType.CANCELED, leavesQty = 0L, cumQty = 2_00000000L, reason = RejectReason.SELF_MATCH_PREVENTED)
        val (expired, smp) = sent8().drop(2)
        assertEquals('C' to 'C', expired.execType to expired.ordStatus)
        assertEquals("ORD-2", smp.clOrdId)
        assertNull(smp.origClOrdId)
        assertEquals('4' to '4', smp.execType to smp.ordStatus)
        assertEquals(2_00000000L, smp.cumQty, "the engine's, never origQty − leavesQty")
        assertEquals("SELF_MATCH_PREVENTED", smp.text)
    }

    @Test
    fun `a cancel's CANCELED is the order's 35=8 ExecType 4, naming the cancel and the order`() {
        val o = order(1)
        report(1, ExecType.NEW)
        cancel(2, o)
        report(2, ExecType.CANCELED, exchangeOrderId = 9001L, leavesQty = 0L, cumQty = 1_00000000L)
        val r = sent8().last()
        assertEquals("CXL-2", r.clOrdId)
        assertEquals("ORD-1", r.origClOrdId)
        assertEquals('4' to '4', r.execType to r.ordStatus)
        assertEquals(1_00000000L, r.cumQty)
        assertEquals('4'.code.toByte(), row(1).ordStatus)
        assertEquals(CancelState.ANSWERED, row(2).cancelState)
        assertNull(row(1).pendingCancel, "a new cancel may now be accepted")
    }

    @Test
    fun `a cancel the exchange rejects is a 35=9 with the order status, and too late for an order already finished`() {
        val o = order(1)
        report(1, ExecType.NEW)
        cancel(2, o)
        report(2, ExecType.REJECTED, exchangeOrderId = 0L, reason = RejectReason.MARKET_CLOSED)
        assertEquals(Sent9("CXL-2", "ORD-1", 9001L, '0', 2, "MARKET_CLOSED", false), sent9().last())

        cancel(3, o)
        report(1, ExecType.TRADE, lastQty = 10_00000000L, leavesQty = 0L, cumQty = 10_00000000L)
        report(3, ExecType.REJECTED, exchangeOrderId = 0L, reason = RejectReason.UNKNOWN_ORDER)
        assertEquals(Sent9("CXL-3", "ORD-1", 9001L, '2', 0, "UNKNOWN_ORDER", false), sent9().last(), "lost the race with the fill")

        val other = order(4)
        cancel(5, other)
        report(5, ExecType.REJECTED, exchangeOrderId = 0L, reason = RejectReason.UNKNOWN_ORDER)
        assertEquals(Sent9("CXL-5", "ORD-4", 0L, '8', 1, "UNKNOWN_ORDER", false), sent9().last(), "unknown order: NONE and 8 (§3.3)")
    }

    // ================================================================ taken only once sent, §6.2

    @Test
    fun `a 35=8 Artio refuses aborts the report, and its redelivery applies it once`() {
        order(1)
        report(1, ExecType.NEW)
        client.accepting = false
        assertEquals(ReportAction.ABORT, report(1, ExecType.TRADE, lastQty = 4_00000000L, leavesQty = 6_00000000L, cumQty = 4_00000000L, reportSeq = 2))
        assertEquals(0L, row(1).cumQty, "nothing changed")
        assertEquals(0L, row(1).notionalLo)
        client.accepting = true
        assertEquals(ReportAction.CONSUMED, report(1, ExecType.TRADE, lastQty = 4_00000000L, leavesQty = 6_00000000L, cumQty = 4_00000000L, reportSeq = 2))
        assertEquals(4_00000000L, row(1).cumQty)
        assertEquals(100_00000000L, sent8().last().avgPx)
    }

    @Test
    fun `a report at or below the row's last reportSeq is sent again with its ExecID and applied no second time`() {
        order(1)
        report(1, ExecType.NEW, reportSeq = 1)
        report(1, ExecType.TRADE, lastQty = 4_00000000L, leavesQty = 6_00000000L, cumQty = 4_00000000L, reportSeq = 2)
        val notionalBefore = row(1).notionalLo
        report(1, ExecType.TRADE, lastQty = 4_00000000L, leavesQty = 6_00000000L, cumQty = 4_00000000L, reportSeq = 2) // a resend
        val (first, again) = sent8().drop(1)
        assertEquals(first, again, "the same 35=8, ExecID 2: the client drops it")
        assertEquals(notionalBefore, row(1).notionalLo, "the fill counted once")
        assertEquals(listOf(1L, 2L, 2L), delivery.sent.map { it.reportSeq }, "the resent 35=8 must reach the archive too: the first may not have")
    }

    @Test
    fun `a participant whose session the library does not hold has its report held`() {
        // Design.md §6.3: from a disconnect until the session is back offline.
        order(1)
        client.held = false
        assertEquals(ReportAction.ABORT, report(1, ExecType.NEW))
        assertTrue(client.sent.isEmpty())
        assertTrue(!row(1).acknowledged)
        assertTrue(delivery.sent.isEmpty())
    }

    // ================================================================ behind the archive, §6.2

    @Test
    fun `a sequenced report's 35=8 waits for the archive with its shard, reportSeq and position`() {
        order(1)
        order(2, security = OTHER_SHARD_SECURITY)
        report(1, ExecType.NEW, reportSeq = 7)
        report(2, ExecType.NEW, reportSeq = 3, securityId = OTHER_SHARD_SECURITY)
        assertEquals(
            listOf(Delivered(0, P, 7L, client.positions[0]), Delivered(1, P, 3L, client.positions[1])),
            delivery.sent,
        )
        assertTrue(delivery.waiting.isEmpty(), "its row is written at once: the stored reportSeq passes it only later")
        restart()
        assertTrue(row(1).acknowledged)
    }

    @Test
    fun `a refused 35=8 hands nothing to wait for`() {
        order(1)
        client.accepting = false
        report(1, ExecType.NEW)
        assertTrue(delivery.sent.isEmpty())
    }

    @Test
    fun `a fate's answer is stored still owing until the archive has it, and a restart before then gives it again`() {
        order(1)
        delivery.keepingUp = false
        outbound.onSettled(P, 1L, OrderFate.NEVER_SEQUENCED)
        outbound.doWork()
        assertEquals(1, sent8().size)
        assertNull(row(1).owed, "answered, in memory")
        restart() // the archive never had it: as after a host crash
        outbound.doWork()
        assertEquals(2, sent8().size, "given again: no resend from the exchange would bring it back")
        assertEquals(Texts.NOT_SEQUENCED_ORDER, sent8().last().text)
        delivery.archiveAll()
        restart()
        outbound.doWork()
        assertEquals(2, sent8().size, "once archived, the row is written answered")
        assertEquals('8'.code.toByte(), row(1).ordStatus)
    }

    @Test
    fun `a status restatement, a gateway's own reject and a parked cancel's answer are written only once archived`() {
        order(1)
        report(1, ExecType.NEW)
        order(2)
        val o3 = order(3)
        cancel(4, o3, CancelState.PARKED)
        delivery.keepingUp = false
        report(1, ExecType.ORDER_STATUS, leavesQty = 6_00000000L, cumQty = 4_00000000L) // fills the client was not told
        report(2, ExecType.REJECTED, exchangeOrderId = 0L, leavesQty = 0L, reportSeq = 0L) // the gateway's, outside the sequence
        report(3, ExecType.REJECTED, exchangeOrderId = 0L, leavesQty = 0L, reason = RejectReason.MARKET_CLOSED)
        outbound.doWork() // the parked cancel: too late
        assertEquals(CancelState.ANSWERED, row(4).cancelState)
        restart()
        assertEquals(0L, row(1).cumQty, "the restatement: told again by the next mass status")
        assertEquals(0.toByte(), row(2).ordStatus, "the gateway's reject: in flight again, so the SDK settles it")
        assertTrue(row(2).isInFlight)
        assertEquals('8'.code.toByte(), row(3).ordStatus, "a sequenced reject is written at once")
        assertEquals(CancelState.PARKED, row(4).cancelState, "the cancel: answered again")
    }

    @Test
    fun `a report for a clOrdId the store does not hold is counted and consumed`() {
        assertEquals(ReportAction.CONSUMED, report(42, ExecType.NEW))
        assertEquals(1L, outbound.lostRows)
        assertTrue(client.sent.isEmpty())
    }

    @Test
    fun `an unknown enrichment sends the quantities the store holds, and is counted`() {
        order(1)
        report(1, ExecType.NEW)
        report(1, ExecType.TRADE, lastQty = 2_00000000L, leavesQty = 8_00000000L, cumQty = 0L, enrichment = Enrichment.UNKNOWN)
        assertEquals(2_00000000L, sent8().last().cumQty, "the store's own count, not the report's")
        assertEquals(1L, outbound.unknownEnrichment)
    }

    // ================================================================ ORDER_STATUS, §5.3

    @Test
    fun `a status that changes nothing the client was told is not sent`() {
        order(1)
        report(1, ExecType.NEW)
        client.sent.clear()
        report(1, ExecType.ORDER_STATUS)
        assertTrue(client.sent.isEmpty())
    }

    @Test
    fun `a status of an order never acknowledged is sent as ExecType I, and acknowledges it`() {
        order(1)
        report(1, ExecType.ORDER_STATUS, exchangeOrderId = 555L)
        val r = sent8().single()
        assertEquals('I' to '0', r.execType to r.ordStatus)
        assertEquals(555L, r.orderId)
        assertEquals(0L, r.execId, "an ORDER_STATUS is outside the sequence: the adapter's ExecID")
        assertTrue(row(1).acknowledged)
    }

    @Test
    fun `a status showing fills the client was not told says so, with AvgPx of the fills known`() {
        order(1)
        report(1, ExecType.NEW)
        report(1, ExecType.TRADE, price = 101_00000000L, lastQty = 2_00000000L, leavesQty = 8_00000000L, cumQty = 2_00000000L)
        report(1, ExecType.ORDER_STATUS, leavesQty = 5_00000000L, cumQty = 5_00000000L)
        val r = sent8().last()
        assertEquals('I' to '1', r.execType to r.ordStatus)
        assertEquals(5_00000000L to 5_00000000L, r.cumQty to r.leavesQty)
        assertEquals(101_00000000L, r.avgPx)
        assertEquals("fills not reported individually: CumQty 2 to 5, prices unknown", r.text)
        assertEquals(1L, outbound.lostFills)
    }

    // ================================================================ fates, §3.3, §3.4

    @Test
    fun `a NEVER_SEQUENCED order is rejected, OrdRejReason 99, from the duty cycle`() {
        order(1)
        outbound.onSettled(P, 1L, OrderFate.NEVER_SEQUENCED)
        assertTrue(client.sent.isEmpty(), "owed, not sent from the SDK's callback")
        outbound.doWork()
        val r = sent8().single()
        assertEquals('8' to '8', r.execType to r.ordStatus)
        assertEquals(99, r.ordRejReason)
        assertEquals(Texts.NOT_SEQUENCED_ORDER, r.text)
        assertEquals(0L, r.execId)
        assertNull(row(1).owed)
        assertEquals('8'.code.toByte(), row(1).ordStatus)
    }

    @Test
    fun `an AMBIGUOUS order is restated as last told with OrdStatusUnknown, never rejected or terminal`() {
        order(1)
        order(2)
        report(2, ExecType.NEW)
        report(2, ExecType.TRADE, lastQty = 3_00000000L, leavesQty = 7_00000000L, cumQty = 3_00000000L)
        client.sent.clear()
        outbound.onSettled(P, 1L, OrderFate.AMBIGUOUS)
        outbound.doWork()
        val unacked = sent8().single()
        assertEquals('I' to 'A', unacked.execType to unacked.ordStatus, "pending new: all the client was told")
        assertTrue(unacked.statusUnknown)
        assertEquals("STATUS UNKNOWN: not open at the exchange; fills since CumQty=0 unrecoverable until reconciled", unacked.text)
        assertEquals(10_00000000L, unacked.leavesQty)
        assertTrue(row(1).ambiguous)
        for (r in client.sent.filterIsInstance<Sent8>()) assertTrue(r.ordStatus !in "248C" && r.execType != '8')
    }

    @Test
    fun `an owed fate survives a restart, and is sent once the session is held again`() {
        order(1)
        client.held = false
        outbound.onSettled(P, 1L, OrderFate.NEVER_SEQUENCED)
        outbound.doWork()
        assertTrue(client.sent.isEmpty())
        restart()
        client.held = true
        outbound.doWork()
        assertEquals(Texts.NOT_SEQUENCED_ORDER, sent8().single().text)
    }

    @Test
    fun `a cancel's fates are 35=9s, and leave the order as it was`() {
        val o = order(1)
        report(1, ExecType.NEW)
        cancel(2, o)
        outbound.onSettled(P, 2L, OrderFate.NEVER_SEQUENCED)
        outbound.doWork()
        assertEquals(Sent9("CXL-2", "ORD-1", 9001L, '0', 99, Texts.NOT_SEQUENCED_CANCEL, false), sent9().single())
        assertNull(row(1).pendingCancel, "it may be cancelled again")
        cancel(3, o)
        outbound.onSettled(P, 3L, OrderFate.AMBIGUOUS)
        outbound.doWork()
        assertEquals(Sent9("CXL-3", "ORD-1", 9001L, '0', 99, Texts.CANCEL_STATUS_UNKNOWN, true), sent9().last())
        assertEquals('0'.code.toByte(), row(1).ordStatus)
    }

    // ================================================================ the mass status rule, §3.4

    @Test
    fun `an acknowledged open order the completed status did not state, sequenced before it, becomes ambiguous`() {
        for (seq in 1L..4L) order(seq)
        order(5, security = OTHER_SHARD_SECURITY)
        report(5, ExecType.NEW, securityId = OTHER_SHARD_SECURITY) // reportSeq 1: early, but another shard's
        for (seq in 1L..4L) report(seq, ExecType.NEW) // reportSeq 2..5
        report(4, ExecType.CANCELED, leavesQty = 0L) // 4: terminal, reportSeq 6
        client.sent.clear()
        report(1, ExecType.ORDER_STATUS) // 1: stated
        outbound.onMassStatusComplete(0, P, RequestStatus.COMPLETE, nextSeq = 4L) // 3, at reportSeq 4: after the status
        outbound.doWork()
        assertEquals(listOf("ORD-2"), sent8().map { it.clOrdId }, "only 2: stated 1, too new 3, terminal 4, another shard's 5")
        assertTrue(sent8().single().statusUnknown)
        assertEquals('0', sent8().single().ordStatus)
    }

    @Test
    fun `a status that did not complete settles nothing, and a later status clears the flag`() {
        order(1)
        report(1, ExecType.NEW)
        outbound.onMassStatusComplete(0, P, RequestStatus.TRUNCATED, nextSeq = 10L)
        outbound.doWork()
        assertTrue(!row(1).ambiguous)
        outbound.onMassStatusComplete(0, P, RequestStatus.COMPLETE, nextSeq = 10L)
        outbound.doWork()
        assertTrue(row(1).ambiguous)
        report(1, ExecType.ORDER_STATUS)
        assertTrue(!row(1).ambiguous, "open after all (§3.4)")
        assertTrue(!sent8().last().statusUnknown)
    }

    // ================================================================ parked cancels, §3.3

    @Test
    fun `a parked cancel is sent after its order's first report, from the duty cycle, retried when back-pressured`() {
        val o = order(1)
        cancel(2, o, CancelState.PARKED)
        venue.next = SendResult.BACK_PRESSURED
        report(1, ExecType.NEW, exchangeOrderId = 777L)
        assertTrue(venue.cancels.isEmpty(), "not inside the report")
        cycle()
        assertEquals(CancelState.PARKED, row(2).cancelState)
        venue.next = SendResult.SENT
        cycle()
        assertEquals(listOf(SentCancel(2L, 1L, 777L)), venue.cancels.filter { it.result == SendResult.SENT }.map { it.copy(result = null) })
        assertEquals(CancelState.SENT, row(2).cancelState)
    }

    @Test
    fun `a parked cancel of an order rejected first is answered too late, after the reject`() {
        val o = order(1)
        cancel(2, o, CancelState.PARKED)
        report(1, ExecType.REJECTED, exchangeOrderId = 0L, leavesQty = 0L, reason = RejectReason.MARKET_CLOSED)
        outbound.doWork()
        assertEquals(listOf("8", "9"), client.sent.map { if (it is Sent8) "8" else "9" })
        assertEquals(Sent9("CXL-2", "ORD-1", 0L, '8', 0, Texts.TOO_LATE, false), sent9().single())
        assertEquals(CancelState.ANSWERED, row(2).cancelState)
        assertTrue(venue.cancels.isEmpty())
    }

    @Test
    fun `a parked cancel of an order settled ambiguous is answered status unknown`() {
        val o = order(1)
        cancel(2, o, CancelState.PARKED)
        outbound.onSettled(P, 1L, OrderFate.AMBIGUOUS)
        outbound.doWork()
        val r = sent9().single()
        assertEquals(99, r.cxlRejReason)
        assertTrue(r.statusUnknown)
        assertTrue(venue.cancels.isEmpty())
    }

    @Test
    fun `after a restart a parked cancel whose order is acknowledged is still sent`() {
        val o = order(1)
        cancel(2, o, CancelState.PARKED)
        venue.next = SendResult.BACK_PRESSURED
        report(1, ExecType.NEW)
        restart()
        venue.next = SendResult.SENT
        recorded = -1L // the new process has not found its inbound recording yet
        cycle()
        assertEquals(CancelState.SENT, row(2).cancelState, "loaded, it has no position in this process and goes at once (§6.1)")
    }

    @Test
    fun `a parked cancel waits for its own 35=F to be archived, though its order's first report came first`() {
        val o = order(1)
        cancel(2, o, CancelState.PARKED).sendAfter = 5_000L // as the inbound path stores it
        recorded = 4_999L
        report(1, ExecType.NEW, exchangeOrderId = 777L)
        cycle()
        assertTrue(venue.cancels.isEmpty())
        recorded = 5_000L
        cycle()
        assertEquals(listOf(SentCancel(2L, 1L, 777L, SendResult.SENT)), venue.cancels)
    }

    @Test
    fun `a parked cancel on its way is sent once, and not answered here when its order turns ambiguous meanwhile`() {
        val o = order(1)
        cancel(2, o, CancelState.PARKED).sendAfter = 5_000L
        recorded = 0L
        report(1, ExecType.NEW, exchangeOrderId = 777L)
        cycle()
        outbound.onSettled(P, 1L, OrderFate.AMBIGUOUS) // the fate's answer owes the parked cancel again
        cycle()
        cycle()
        assertTrue(sent9().isEmpty(), "on its way: the exchange answers it")
        recorded = Long.MAX_VALUE
        cycle()
        assertEquals(listOf(SentCancel(2L, 1L, 777L, SendResult.SENT)), venue.cancels)
    }

    // ================================================================ refused sends, §3.3, §6.1

    @Test
    fun `a refused order is answered from its row, 35=8 OrdRejReason 99 with the refusal's Text, and is no longer in flight`() {
        order(1)
        outbound.refuse(row(1), Texts.EXCHANGE_UNAVAILABLE)
        assertTrue(!row(1).isInFlight, "owed: a restart does not hand it to the SDK")
        cycle()
        val r = sent8().single()
        assertEquals(Triple("ORD-1", '8', '8'), Triple(r.clOrdId, r.execType, r.ordStatus))
        assertEquals(99 to Texts.EXCHANGE_UNAVAILABLE, r.ordRejReason to r.text)
        assertEquals(0L to 0L, r.cumQty to r.leavesQty)
        assertEquals('8'.code.toByte(), row(1).ordStatus)
    }

    @Test
    fun `a refused cancel is answered 35=9 CxlRejReason 99 with the refusal's Text, and its order may be cancelled again`() {
        val o = order(1)
        report(1, ExecType.NEW, exchangeOrderId = 777L)
        cancel(2, o)
        outbound.refuse(row(2), Texts.PARTICIPANT_NOT_SERVED)
        cycle()
        assertEquals(Sent9("CXL-2", "ORD-1", 777L, '0', 99, Texts.PARTICIPANT_NOT_SERVED, false), sent9().single())
        assertEquals(CancelState.ANSWERED, row(2).cancelState)
        assertNull(row(1).pendingCancel)
    }

    @Test
    fun `a refusal not yet answered when the process stops is answered after the restart, with the fate's own Text`() {
        order(1)
        client.accepting = false
        outbound.refuse(row(1), Texts.EXCHANGE_UNAVAILABLE)
        cycle()
        restart()
        client.accepting = true
        cycle()
        val r = sent8().single()
        assertEquals('8' to 99, r.ordStatus to r.ordRejReason)
        assertEquals(Texts.NOT_SEQUENCED_ORDER, r.text, "the Text is in memory only (§6.1)")
    }

    // ================================================================ the library thread

    @Test
    fun `reports on their way to the client allocate nothing`() {
        val count = 12_000
        val spare = (1..count).map { store.newRow() }
        spare.forEach(store::release)
        for (seq in 1L..count) order(seq)
        fun run(from: Int, until: Int) {
            for (seq in from until until) {
                check(report(seq.toLong(), ExecType.NEW) == ReportAction.CONSUMED)
                check(report(seq.toLong(), ExecType.TRADE, lastQty = 1_00000000L, leavesQty = 9_00000000L, cumQty = 1_00000000L) == ReportAction.CONSUMED)
            }
        }
        client.keep = false
        delivery.keep = false
        run(1, 6_001)
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        // As InboundOrdersTest: an allocation per report shows in every window; one of five at 0 is the claim.
        val windows = (0 until 5).map { w ->
            val before = threads.currentThreadAllocatedBytes
            run(6_001 + w * 1_000, 7_001 + w * 1_000)
            threads.currentThreadAllocatedBytes - before
        }
        assertTrue(0L in windows, "bytes allocated per window of 2,000 reports applied and sent: $windows")
    }

    // ================================================================ models

    private class CancelVenue : OrderVenue {
        var next = SendResult.SENT
        override fun shardOf(securityId: Int) = if (securityId == OTHER_SHARD_SECURITY) 1 else 0
        val cancels = mutableListOf<SentCancel>()

        override fun newOrder(
            participantId: Long, clOrdId: Long, securityId: Int, side: Side, price: Long, qty: Long,
            expireDate: Int, smpId: Long, smpStrategy: SmpStrategy,
        ): SendResult = error("the outbound path sends no order")

        override fun cancel(participantId: Long, clOrdId: Long, origClOrdId: Long, exchangeOrderId: Long, securityId: Int, side: Side): SendResult {
            cancels += SentCancel(clOrdId, origClOrdId, exchangeOrderId, next)
            return next
        }
    }

    private companion object {
        const val P = 1001L
        const val ACME = 42
        const val OTHER_SHARD_SECURITY = 77
        const val TODAY = 20261026
    }
}

data class SentCancel(val clOrdId: Long, val origClOrdId: Long, val exchangeOrderId: Long, val result: SendResult? = null)

/** A 35=8 as the client would read it. [execId] is the reportSeq, 0 for the adapter's own; [orderId] 0 is `NONE`. */
data class Sent8(
    val clOrdId: String, val origClOrdId: String?, val execId: Long, val orderId: Long, val execType: Char, val ordStatus: Char,
    val lastPx: Long, val lastQty: Long, val cumQty: Long, val leavesQty: Long, val avgPx: Long,
    val ordRejReason: Int?, val text: String?, val statusUnknown: Boolean,
)

data class Sent9(
    val clOrdId: String, val origClOrdId: String, val orderId: Long, val ordStatus: Char, val cxlRejReason: Int,
    val text: String?, val statusUnknown: Boolean,
)

/**
 * Records what it is sent; [held] false is a session the library does not hold (§6.3), [accepting]
 * false a back-pressured Artio. Each message taken gets the next position, as Artio's stream would.
 */
class RecordingClient : ClientReports {
    val sent = mutableListOf<Any>()
    var held = true
    var accepting = true
    private var position = 0L

    /** Every position given out, in order, while [keep]. */
    val positions = mutableListOf<Long>()

    /** False: accept and keep nothing, for measuring the path. */
    var keep = true

    override fun canSend(participantId: Long) = held

    private fun next(): Long {
        position += 160L
        if (keep) positions += position
        return position
    }

    override fun send(report: OutgoingExecution): Long {
        if (!accepting) return -2L // Publication.BACK_PRESSURED
        if (!keep) return next()
        sent += Sent8(
            report.order.fixClOrdIdAsString().let { o -> report.cancel?.fixClOrdIdAsString() ?: o },
            report.cancel?.let { report.order.fixClOrdIdAsString() },
            report.reportSeq, report.exchangeOrderId, report.execType, report.ordStatus,
            report.lastPx, report.lastQty, report.cumQty, report.leavesQty, report.avgPx,
            report.ordRejReason.takeIf { it != OutgoingExecution.NO_REASON },
            if (report.textLength == 0) null else report.text.getStringWithoutLengthAscii(0, report.textLength),
            report.statusUnknown,
        )
        return next()
    }

    override fun send(reject: OutgoingCancelReject): Long {
        if (!accepting) return -2L
        if (!keep) return next()
        sent += Sent9(
            reject.cancel.fixClOrdIdAsString(), reject.order.fixClOrdIdAsString(), reject.exchangeOrderId, reject.ordStatus,
            reject.cxlRejReason, if (reject.textLength == 0) null else reject.text.getStringWithoutLengthAscii(0, reject.textLength),
            reject.statusUnknown,
        )
        return next()
    }
}

data class Delivered(val shardId: Int, val participantId: Long, val reportSeq: Long, val position: Long)

/**
 * The archive as the outbound path meets it (Design.md §6.2): with [keepingUp] it has every 35=8 at
 * once, so a row waiting for it is written straight away; without, rows wait until [archiveAll].
 */
class ArchiveModel(private val store: OrderStore) : Delivery {
    val sent = mutableListOf<Delivered>()
    val waiting = mutableListOf<RequestRow>()
    var keepingUp = true
    var keep = true

    override fun sent(shardId: Int, participantId: Long, reportSeq: Long, position: Long) {
        if (keep) sent += Delivered(shardId, participantId, reportSeq, position)
    }

    override fun writeWhenArchived(row: RequestRow, position: Long) {
        if (keepingUp) store.update(row) else waiting += row
    }

    fun archiveAll() {
        waiting.forEach(store::update)
        waiting.clear()
    }
}
