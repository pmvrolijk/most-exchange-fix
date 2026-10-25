package nl.lamia.most.exchange.fixadapter.orderentry.inbound

import io.aeron.logbuffer.ControlledFragmentHandler.Action
import nl.lamia.most.exchange.client.SendResult
import nl.lamia.most.exchange.fixadapter.codecs.Constants
import nl.lamia.most.exchange.fixadapter.orderentry.store.CancelState
import nl.lamia.most.exchange.fixadapter.orderentry.store.OrderStore
import nl.lamia.most.exchange.fixadapter.orderentry.store.RequestRow
import nl.lamia.most.exchange.fixadapter.orderentry.store.RowKind
import nl.lamia.most.exchange.sbe.Side
import nl.lamia.most.exchange.sbe.SmpStrategy
import org.agrona.concurrent.UnsafeBuffer
import org.junit.jupiter.api.io.TempDir
import uk.co.real_logic.artio.util.MessageTypeEncoding
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Order entry's inbound path (Design.md §3.3, §5.1, §5.2, §5.4), against a model of the SDK's
 * session (most-exchange Adapters.md §2: its `SendResult`s) and of the directory.
 */
class InboundOrdersTest {

    @TempDir
    lateinit var dir: File

    private lateinit var store: OrderStore
    private val venue = VenueModel()
    private val replies = RecordingReplies()
    private val securities = SecuritiesModel()
    private val collisions = mutableListOf<Long>()
    private lateinit var inbound: InboundOrders

    @BeforeTest
    fun open() {
        store = OrderStore.open(dir).also { it.load(TODAY) }
        inbound = InboundOrders(store, venue, securities, { TODAY }, { collisions += it })
        venue.store = store
    }

    @AfterTest
    fun close() = store.close()

    /** The adapter restarted: the same store directory, a new process's state. */
    private fun restart() {
        store.close()
        open()
    }

    private fun deliver(type: String, seq: Int, fields: List<Pair<Int, String>>, possDup: Boolean = false, sequenceIndex: Int = 0): Action {
        val bytes = fix(type, seq, fields, possDup)
        return inbound.onMessage(P, replies, sequenceIndex, MessageTypeEncoding.packMessageType(type), UnsafeBuffer(bytes), 0, bytes.size)
    }

    /** [base] with each of [extra] replacing its tag's value, [OMIT] removing it, or appended. */
    private fun fields(base: List<Pair<Int, String>>, extra: Array<out Pair<Int, String>>): List<Pair<Int, String>> {
        val overrides = extra.toMap()
        return base.map { (tag, value) -> tag to (overrides[tag] ?: value) }.filter { it.second != OMIT } +
            extra.filter { (tag, value) -> base.none { it.first == tag } && value != OMIT }
    }

    private fun order(seq: Int, clOrdId: String = "ORD-$seq", vararg extra: Pair<Int, String>, possDup: Boolean = false): Action =
        deliver("D", seq, fields(listOf(11 to clOrdId, 55 to "ACME", 54 to "1", 60 to TIME, 38 to "10", 40 to "2", 44 to "100.25"), extra), possDup)

    private fun cancel(seq: Int, origClOrdId: String, clOrdId: String = "CXL-$seq", vararg extra: Pair<Int, String>, possDup: Boolean = false): Action =
        deliver("F", seq, fields(listOf(41 to origClOrdId, 11 to clOrdId, 55 to "ACME", 54 to "1", 60 to TIME), extra), possDup)

    /** The exchange acknowledged the order: what slice 3's report handling will write. */
    private fun acknowledge(clOrdId: String, exchangeOrderId: Long = 9001L, status: Char = '0') {
        val row = assertNotNull(store.findByFix(clOrdId))
        row.acknowledged = true
        row.exchangeOrderId = exchangeOrderId
        row.ordStatus = status.code.toByte()
        store.update(row)
    }

    private fun OrderStore.findByFix(clOrdId: String): RequestRow? {
        val bytes = clOrdId.toByteArray(Charsets.US_ASCII)
        return findByFixClOrdId(P, UnsafeBuffer(bytes), 0, bytes.size)
    }

    // ================================================================ 35=D, §5.1

    @Test
    fun `a limit order is written to the store, then sent with the session's participant, the derived clOrdId, and its fields converted`() {
        assertEquals(Action.CONTINUE, order(7))
        val sent = venue.orders.single()
        assertEquals(P, sent.participantId, "the session's participant (§2)")
        assertEquals((0L shl 32) or 7L, sent.clOrdId, "(sequenceIndex << 32) | MsgSeqNum (§3.2)")
        assertEquals(ACME, sent.securityId)
        assertEquals(Side.BUY, sent.side)
        assertEquals(100_25000000L, sent.price, "PriceCodec, 8 implied decimals")
        assertEquals(10_00000000L, sent.qty)
        assertEquals(TODAY, sent.expireDate, "59 absent is a day order: the adapter's trading date (§3.6)")
        assertEquals(0L, sent.smpId, "2362 absent → 0, the participant")
        assertEquals(SmpStrategy.CANCEL_AGGRESSOR, sent.smpStrategy, "2964 absent → CANCEL_AGGRESSOR")
        assertTrue(sent.storedBeforeSend, "the row is written before newOrder (§6.1)")
        assertTrue(replies.sent.isEmpty())

        val row = assertNotNull(store.find(P, 7L))
        assertEquals("ORD-7", row.fixClOrdIdAsString())
        assertEquals(RowKind.ORDER, row.kind)
        assertEquals(TODAY, row.entryTradingDate)
        assertFalse(row.sendPending)
        assertTrue(row.isInFlight)
    }

    @Test
    fun `the derived clOrdId carries Artio's sequenceIndex`() {
        deliver("D", 7, listOf(11 to "ORD-7", 55 to "ACME", 54 to "2", 60 to TIME, 38 to "1", 40 to "2", 44 to "1"), sequenceIndex = 3)
        assertEquals((3L shl 32) or 7L, venue.orders.single().clOrdId)
        assertEquals(Side.SELL, venue.orders.single().side)
    }

    @Test
    fun `a party on the message does not choose the participant`() {
        order(1, extra = arrayOf(453 to "1", 448 to "999", 447 to "D", 452 to "3"))
        assertEquals(P, venue.orders.single().participantId)
    }

    @Test
    fun `GTC, GTD with its ExpireDate, and SMP are mapped as §5_1 says`() {
        order(1, extra = arrayOf(59 to "1"))
        order(2, extra = arrayOf(59 to "6", 432 to "20261231", 2362 to "77", 2964 to "2"))
        order(3, extra = arrayOf(59 to "0", 2964 to "1"))
        val (gtc, gtd, day) = venue.orders
        assertEquals(0, gtc.expireDate, "GTC → 0")
        assertEquals(20261231, gtd.expireDate, "GTD → 432, forwarded unchecked")
        assertEquals(77L, gtd.smpId)
        assertEquals(SmpStrategy.CANCEL_RESTING, gtd.smpStrategy, "2964=2 → CANCEL_RESTING")
        assertEquals(TODAY, day.expireDate)
        assertEquals(SmpStrategy.CANCEL_AGGRESSOR, day.smpStrategy, "2964=1 → CANCEL_AGGRESSOR")
        assertEquals('6'.code.toByte(), store.find(P, 2L)?.timeInForce, "stored, to be echoed (§5.3)")
    }

    @Test
    fun `the security is 48 with 22=8, an ISIN with 22=4, or 55 through the directory, 48 first`() {
        order(1, extra = arrayOf(55 to OMIT, 48 to "$ACME", 22 to "8"))
        order(2, extra = arrayOf(55 to OMIT, 48 to "NL0000000001", 22 to "4"))
        order(3, extra = arrayOf(55 to "OTHER"))
        order(4, extra = arrayOf(55 to "OTHER", 48 to "$ACME", 22 to "8"))
        assertEquals(listOf(ACME, ACME, OTHER, ACME), venue.orders.map { it.securityId })
    }

    @Test
    fun `every refusal of §5_1 is a 35=8 with its code and Text, never sent, and the ClOrdID not reserved`() {
        val cases = listOf(
            arrayOf(55 to OMIT) to (1 to Texts.UNKNOWN_SECURITY),
            arrayOf(55 to "NOPE") to (1 to Texts.UNKNOWN_SECURITY),
            arrayOf(55 to OMIT, 48 to "$ACME") to (1 to Texts.UNKNOWN_SECURITY),
            arrayOf(55 to OMIT, 48 to "$ACME", 22 to "1") to (1 to Texts.UNKNOWN_SECURITY),
            arrayOf(55 to OMIT, 48 to "424242", 22 to "8") to (1 to Texts.UNKNOWN_SECURITY),
            arrayOf(55 to OMIT, 48 to "XX0000000000", 22 to "4") to (1 to Texts.UNKNOWN_SECURITY),
            arrayOf(38 to OMIT) to (13 to Texts.INCORRECT_QUANTITY),
            arrayOf(38 to "0") to (13 to Texts.INCORRECT_QUANTITY),
            arrayOf(38 to "-5") to (13 to Texts.INCORRECT_QUANTITY),
            arrayOf(38 to "1.000000001") to (13 to Texts.INCORRECT_QUANTITY),
            arrayOf(40 to "1") to (99 to Texts.LIMIT_ORDERS_ONLY),
            arrayOf(44 to OMIT) to (99 to Texts.PRICE_REQUIRED),
            arrayOf(44 to "100.123456789") to (99 to Texts.PRICE_NOT_EXACT),
            arrayOf(54 to "5") to (99 to Texts.SIDE_ONLY),
            arrayOf(59 to "3") to (99 to Texts.TIME_IN_FORCE_ONLY),
            arrayOf(59 to "4") to (99 to Texts.TIME_IN_FORCE_ONLY),
            arrayOf(59 to "6") to (99 to Texts.EXPIRE_DATE_REQUIRED),
            arrayOf(59 to "6", 432 to "2026123") to (99 to Texts.EXPIRE_DATE_REQUIRED),
            arrayOf(59 to "6", 432 to "20261332") to (99 to Texts.EXPIRE_DATE_REQUIRED),
            arrayOf(2362 to "0") to (99 to Texts.SMP_ID_POSITIVE),
            arrayOf(2362 to "-3") to (99 to Texts.SMP_ID_POSITIVE),
            arrayOf(2362 to "SMP-7") to (99 to Texts.SMP_ID_POSITIVE),
            arrayOf(2362 to "99999999999999999999") to (99 to Texts.SMP_ID_POSITIVE),
        )
        var seq = 1
        for ((extra, expected) in cases) {
            val what = extra.joinToString { "${it.first}=${it.second}" }
            replies.sent.clear()
            assertEquals(Action.CONTINUE, order(seq, "SAME-ID", *extra), what)
            assertEquals(listOf<Reply>(Reply.OrderReject("SAME-ID", expected.first, expected.second)), replies.sent, what)
            assertTrue(venue.orders.isEmpty(), "$what: never sent")
            assertNull(store.find(P, seq.toLong()), "$what: no row")
            seq++
        }
        replies.sent.clear()
        order(seq, "SAME-ID")
        assertEquals(1, venue.orders.size, "the ClOrdID was never reserved")
        assertTrue(replies.sent.isEmpty())
    }

    @Test
    fun `a message validate() refuses is a 35=3 from the decoder's reason and tag, and goes no further`() {
        order(4, extra = arrayOf(2964 to "3"))
        order(5, extra = arrayOf(11 to OMIT))
        order(6, extra = arrayOf(9999 to "X"))
        assertEquals(
            listOf<Reply>(
                Reply.SessionReject(4, "D", 2964, 5),
                Reply.SessionReject(5, "D", 11, 1),
                Reply.SessionReject(6, "D", 9999, 0),
            ),
            replies.sent,
        )
        assertTrue(venue.orders.isEmpty())
        assertEquals(0, store.size)
    }

    // ================================================================ duplicates, the same request again

    @Test
    fun `a ClOrdID already held is a duplicate, OrdRejReason 6, and with PossDup=Y it is dropped`() {
        order(1, "ORD-A")
        order(2, "ORD-A")
        assertEquals(listOf<Reply>(Reply.OrderReject("ORD-A", 6, Texts.DUPLICATE_CLORDID)), replies.sent)
        replies.sent.clear()
        order(3, "ORD-A", possDup = true)
        assertTrue(replies.sent.isEmpty(), "a resend of a request already answered")
        assertEquals(1, venue.orders.size)
        assertNull(store.find(P, 2L))
        assertNull(store.find(P, 3L))
    }

    @Test
    fun `a back-pressured order is aborted, and sent once when Artio delivers it again`() {
        venue.next(SendResult.BACK_PRESSURED)
        assertEquals(Action.ABORT, order(1))
        val row = assertNotNull(store.find(P, 1L), "the row is written before the send")
        assertTrue(row.sendPending)
        assertEquals(Action.CONTINUE, order(1))
        assertEquals(listOf(SendResult.BACK_PRESSURED, SendResult.SENT), venue.orders.map { it.result })
        assertFalse(row.sendPending)
        assertTrue(replies.sent.isEmpty())
    }

    @Test
    fun `a request already sent is never sent again, whether resent with PossDup=Y or replayed after a restart`() {
        order(1)
        order(1, possDup = true)
        assertEquals(1, venue.orders.size, "PossDup resend")
        restart()
        order(1)
        order(1, possDup = true)
        assertEquals(1, venue.orders.size, "a claim's replay after a restart: the engine does not check clOrdId")
        assertTrue(replies.sent.isEmpty())
    }

    @Test
    fun `a request written but not sent before a restart is not sent by a replay either, the SDK settles it`() {
        venue.next(SendResult.BACK_PRESSURED)
        order(1)
        restart()
        order(1)
        assertEquals(listOf(SendResult.BACK_PRESSURED), venue.orders.map { it.result })
        assertTrue(assertNotNull(store.find(P, 1L)).isInFlight, "restored in flight, settled by the SDK (§3.4)")
    }

    // ================================================================ the SDK cannot send

    @Test
    fun `NOT_READY and NO_GATEWAY reject the order, exchange unavailable, and the row is rejected, not in flight`() {
        for ((seq, result) in listOf(1 to SendResult.NOT_READY, 2 to SendResult.NO_GATEWAY)) {
            venue.next(result)
            assertEquals(Action.CONTINUE, order(seq))
            val row = assertNotNull(store.find(P, seq.toLong()))
            assertEquals('8'.code.toByte(), row.ordStatus, "$result")
            assertFalse(row.isInFlight, "$result: never sent, so nothing for the SDK to settle")
            assertFalse(row.sendPending)
        }
        assertEquals(
            listOf<Reply>(Reply.OrderReject("ORD-1", 99, Texts.EXCHANGE_UNAVAILABLE), Reply.OrderReject("ORD-2", 99, Texts.EXCHANGE_UNAVAILABLE)),
            replies.sent,
        )
        replies.sent.clear()
        order(3, "ORD-1")
        assertEquals(listOf<Reply>(Reply.OrderReject("ORD-1", 6, Texts.DUPLICATE_CLORDID)), replies.sent, "a rejected order's ClOrdID is the day's")
    }

    @Test
    fun `UNKNOWN_PARTICIPANT rejects the order, participant not served`() {
        venue.next(SendResult.UNKNOWN_PARTICIPANT)
        order(1)
        assertEquals(listOf<Reply>(Reply.OrderReject("ORD-1", 99, Texts.PARTICIPANT_NOT_SERVED)), replies.sent)
    }

    @Test
    fun `a reject Artio refuses aborts the order, and its redelivery may then be sent, never both`() {
        venue.next(SendResult.NO_GATEWAY)
        replies.accepting = false
        assertEquals(Action.ABORT, order(1))
        replies.accepting = true
        assertEquals(Action.CONTINUE, order(1))
        assertEquals(listOf(SendResult.NO_GATEWAY, SendResult.SENT), venue.orders.map { it.result })
        assertTrue(replies.sent.isEmpty(), "the reject never went out")
        assertTrue(assertNotNull(store.find(P, 1L)).isInFlight)
    }

    // ================================================================ before the directory, §3.3

    @Test
    fun `before the directory a 35=D is rejected exchange unavailable, never unknown security, and nothing is stored`() {
        securities.isReady = false
        order(1)
        assertEquals(listOf<Reply>(Reply.OrderReject("ORD-1", 99, Texts.EXCHANGE_UNAVAILABLE)), replies.sent)
        assertTrue(venue.orders.isEmpty())
        assertNull(store.find(P, 1L), "no row: the client may retry with the same ClOrdID")
    }

    @Test
    fun `before the directory a 35=F is rejected exchange unavailable`() {
        order(1)
        acknowledge("ORD-1")
        securities.isReady = false
        replies.sent.clear()
        cancel(2, "ORD-1")
        assertEquals(listOf<Reply>(Reply.CancelReject("CXL-2", "ORD-1", 99, Texts.EXCHANGE_UNAVAILABLE)), replies.sent)
        assertTrue(venue.cancels.isEmpty())
        assertNull(store.find(P, 2L))
    }

    @Test
    fun `a cancel refused before the directory, for a collision or for the block names its order, so its status is stated`() {
        // Design.md §3.3, "The adapter's own answers": never OrdStatus 8 for an order that may be open.
        order(1)
        acknowledge("ORD-1")
        securities.isReady = false
        cancel(2, "ORD-1")
        securities.isReady = true
        order(3, "OLD-3")
        cancel(3, "ORD-1", "CXL-NEW") // MsgSeqNum 3 again: a collision
        cancel(4, "ORD-1") // blocked
        assertEquals(
            listOf(Texts.EXCHANGE_UNAVAILABLE, Texts.SEQUENCE_RESET_REQUIRED, Texts.SEQUENCE_RESET_REQUIRED),
            replies.sent.filterIsInstance<Reply.CancelReject>().map { it.text },
        )
        assertEquals(listOf<String?>("ORD-1", "ORD-1", "ORD-1"), replies.namedOrders)
    }

    // ================================================================ id collision, §8.15

    @Test
    fun `an id collision is rejected and the session logged out, and its later orders are rejected until reset`() {
        order(5, "OLD-5")
        replies.sent.clear()
        // Artio's state wiped at sequenceIndex 0: the client's new MsgSeqNum 5 derives a held id.
        assertEquals(Action.CONTINUE, order(5, "NEW-5"))
        assertEquals(
            listOf<Reply>(Reply.OrderReject("NEW-5", 99, Texts.SEQUENCE_RESET_REQUIRED), Reply.Logout(Texts.SEQUENCE_RESET_REQUIRED)),
            replies.sent,
        )
        assertEquals(listOf(P), collisions)
        assertEquals("OLD-5", store.find(P, 5L)?.fixClOrdIdAsString(), "nothing written")
        assertTrue(inbound.isBlocked(P))

        replies.sent.clear()
        order(6, "NEW-6")
        assertEquals(listOf<Reply>(Reply.OrderReject("NEW-6", 99, Texts.SEQUENCE_RESET_REQUIRED)), replies.sent)
        assertEquals(1, venue.orders.size, "only OLD-5 was ever sent")
        assertNull(store.find(P, 6L))
    }

    @Test
    fun `the block is stored at the collision's sequenceIndex, and outlives a restart`() {
        order(5, "OLD-5")
        order(5, "NEW-5")
        assertTrue(store.isBlocked(P))
        assertFalse(store.liftBlock(P, 0), "found at sequenceIndex 0: only a higher one lifts it")
        restart()
        replies.sent.clear()
        order(6, "NEW-6")
        assertEquals(listOf<Reply>(Reply.OrderReject("NEW-6", 99, Texts.SEQUENCE_RESET_REQUIRED)), replies.sent)
        assertEquals(1, venue.orders.size)
    }

    @Test
    fun `a cancel's id collision is answered the same way`() {
        order(5, "OLD-5")
        acknowledge("OLD-5")
        order(6, "ORD-6")
        acknowledge("ORD-6")
        replies.sent.clear()
        cancel(5, "ORD-6", "CXL-NEW")
        assertEquals(
            listOf<Reply>(Reply.CancelReject("CXL-NEW", "ORD-6", 99, Texts.SEQUENCE_RESET_REQUIRED), Reply.Logout(Texts.SEQUENCE_RESET_REQUIRED)),
            replies.sent,
        )
        assertTrue(venue.cancels.isEmpty())
    }

    // ================================================================ 35=F, §5.2

    @Test
    fun `a cancel of an acknowledged order is sent with the stored exchangeOrderId, security and side`() {
        order(1)
        acknowledge("ORD-1", exchangeOrderId = 9001L)
        assertEquals(Action.CONTINUE, cancel(2, "ORD-1"))
        val sent = venue.cancels.single()
        assertEquals(P, sent.participantId)
        assertEquals(2L, sent.clOrdId, "the cancel's own derived id")
        assertEquals(1L, sent.origClOrdId)
        assertEquals(9001L, sent.exchangeOrderId)
        assertEquals(ACME, sent.securityId)
        assertEquals(Side.BUY, sent.side)
        assertTrue(sent.storedBeforeSend)

        val row = assertNotNull(store.find(P, 2L))
        assertEquals(RowKind.CANCEL, row.kind)
        assertEquals(CancelState.SENT, row.cancelState)
        assertEquals(1L, row.origClOrdId)
        assertEquals(row, store.find(P, 1L)?.pendingCancel)
        assertTrue(replies.sent.isEmpty())
    }

    @Test
    fun `an order's row keeps the shard it was sent to, and a cancel's its order's`() {
        // Design.md §6.1: its reports carry that shard's reportSeq, which retention compares.
        order(1, extra = arrayOf(55 to "OTHER"))
        assertEquals(5, assertNotNull(store.find(P, 1L)).shardId)
        acknowledge("ORD-1")
        cancel(2, "ORD-1", extra = arrayOf(55 to "OTHER"))
        assertEquals(5, assertNotNull(store.find(P, 2L)).shardId)
        restart()
        assertEquals(5 to 5, store.find(P, 1L)?.shardId to store.find(P, 2L)?.shardId)
    }

    @Test
    fun `a cancel needs only 41 and 54 to match, 37, 55 and 48 are checked only when sent`() {
        order(1)
        acknowledge("ORD-1", exchangeOrderId = 9001L)
        cancel(2, "ORD-1", extra = arrayOf(37 to "9001", 55 to OMIT, 48 to "$ACME", 22 to "8"))
        assertEquals(1, venue.cancels.size)
    }

    @Test
    fun `a cancel before the order's first report is parked, not sent, and survives a restart`() {
        order(1)
        assertEquals(Action.CONTINUE, cancel(2, "ORD-1"))
        assertTrue(venue.cancels.isEmpty())
        assertTrue(replies.sent.isEmpty())
        restart()
        val row = assertNotNull(store.find(P, 2L))
        assertEquals(CancelState.PARKED, row.cancelState)
        assertEquals(row, store.find(P, 1L)?.pendingCancel)
        assertFalse(row.isInFlight, "never sent")
    }

    @Test
    fun `a cancel of an unknown order, or naming a cancel, is a 35=9 with CxlRejReason 1`() {
        cancel(1, "NOPE")
        order(2)
        acknowledge("ORD-2")
        cancel(3, "ORD-2", "CXL-3")
        cancel(4, "CXL-3", "CXL-4")
        assertEquals(
            listOf<Reply>(Reply.CancelReject("CXL-1", "NOPE", 1, Texts.UNKNOWN_ORDER), Reply.CancelReject("CXL-4", "CXL-3", 1, Texts.UNKNOWN_ORDER)),
            replies.sent,
        )
        assertEquals(1, venue.cancels.size)
    }

    @Test
    fun `a cancel whose OrderID, Side or security does not match the order is a 35=9 with CxlRejReason 1`() {
        order(1)
        acknowledge("ORD-1", exchangeOrderId = 9001L)
        cancel(2, "ORD-1", extra = arrayOf(37 to "9002"))
        cancel(3, "ORD-1", extra = arrayOf(54 to "2"))
        cancel(4, "ORD-1", extra = arrayOf(55 to "OTHER"))
        cancel(5, "ORD-1", extra = arrayOf(55 to OMIT, 48 to "$OTHER", 22 to "8"))
        cancel(6, "ORD-1", extra = arrayOf(37 to "not-a-number"))
        assertEquals((2..6).map<Int, Reply> { Reply.CancelReject("CXL-$it", "ORD-1", 1, Texts.ORDER_MISMATCH) }, replies.sent)
        assertTrue(venue.cancels.isEmpty())
        assertNull(store.find(P, 1L)?.pendingCancel)
    }

    @Test
    fun `an OrderID on a cancel of an order not yet acknowledged cannot match`() {
        order(1)
        cancel(2, "ORD-1", extra = arrayOf(37 to "9001"))
        assertEquals(listOf<Reply>(Reply.CancelReject("CXL-2", "ORD-1", 1, Texts.ORDER_MISMATCH)), replies.sent)
    }

    @Test
    fun `a cancel of a terminal order is too late, CxlRejReason 0, answered here`() {
        order(1)
        acknowledge("ORD-1", status = '2')
        cancel(2, "ORD-1")
        assertEquals(listOf<Reply>(Reply.CancelReject("CXL-2", "ORD-1", 0, Texts.TOO_LATE)), replies.sent)
        assertTrue(venue.cancels.isEmpty())
    }

    @Test
    fun `a cancel of an ambiguous order is answered here with OrdStatusUnknown, never sent (§3_4)`() {
        order(1)
        acknowledge("ORD-1")
        store.find(P, 1L)!!.ambiguous = true
        cancel(2, "ORD-1")
        assertEquals(listOf<Reply>(Reply.CancelReject("CXL-2", "ORD-1", 99, Texts.STATUS_UNKNOWN, statusUnknown = true)), replies.sent)
        assertTrue(venue.cancels.isEmpty())
    }

    @Test
    fun `a second cancel while one is pending is CxlRejReason 3, and once the first is answered a new one goes`() {
        order(1)
        acknowledge("ORD-1")
        cancel(2, "ORD-1")
        cancel(3, "ORD-1")
        assertEquals(listOf<Reply>(Reply.CancelReject("CXL-3", "ORD-1", 3, Texts.ALREADY_PENDING)), replies.sent)
        assertEquals(1, venue.cancels.size)

        // Slice 3 answers the first: here, a reject of it by the exchange.
        val first = store.find(P, 2L)!!
        first.cancelState = CancelState.ANSWERED
        store.update(first)
        store.find(P, 1L)!!.pendingCancel = null
        cancel(4, "ORD-1")
        assertEquals(2, venue.cancels.size)
    }

    @Test
    fun `a parked cancel is pending too`() {
        order(1)
        cancel(2, "ORD-1")
        cancel(3, "ORD-1")
        assertEquals(listOf<Reply>(Reply.CancelReject("CXL-3", "ORD-1", 3, Texts.ALREADY_PENDING)), replies.sent)
    }

    @Test
    fun `a cancel's ClOrdID held by another request is a duplicate, CxlRejReason 6, and with PossDup=Y dropped`() {
        order(1)
        acknowledge("ORD-1")
        cancel(2, "ORD-1", "ORD-1")
        assertEquals(listOf<Reply>(Reply.CancelReject("ORD-1", "ORD-1", 6, Texts.DUPLICATE_CLORDID)), replies.sent)
        replies.sent.clear()
        cancel(3, "ORD-1", "ORD-1", possDup = true)
        assertTrue(replies.sent.isEmpty())
        assertTrue(venue.cancels.isEmpty())
    }

    @Test
    fun `a cancel the SDK cannot send is rejected, exchange unavailable, and the order may be cancelled again`() {
        order(1)
        acknowledge("ORD-1")
        venue.next(SendResult.NO_GATEWAY)
        cancel(2, "ORD-1")
        assertEquals(listOf<Reply>(Reply.CancelReject("CXL-2", "ORD-1", 99, Texts.EXCHANGE_UNAVAILABLE)), replies.sent)
        assertEquals(CancelState.ANSWERED, store.find(P, 2L)?.cancelState)
        assertNull(store.find(P, 1L)?.pendingCancel)
        cancel(3, "ORD-1")
        assertEquals(listOf(SendResult.NO_GATEWAY, SendResult.SENT), venue.cancels.map { it.result })
    }

    @Test
    fun `a back-pressured cancel is aborted and sent once when delivered again, and a PossDup resend is dropped`() {
        order(1)
        acknowledge("ORD-1")
        venue.next(SendResult.BACK_PRESSURED)
        assertEquals(Action.ABORT, cancel(2, "ORD-1"))
        assertEquals(Action.CONTINUE, cancel(2, "ORD-1"))
        cancel(2, "ORD-1", possDup = true)
        assertEquals(listOf(SendResult.BACK_PRESSURED, SendResult.SENT), venue.cancels.map { it.result })
        assertTrue(replies.sent.isEmpty())
    }

    // ================================================================ 35=G, other types

    @Test
    fun `a replace is rejected, CxlRejResponseTo 2 and CxlRejReason 2, replace not supported`() {
        order(1)
        deliver("G", 2, listOf(41 to "ORD-1", 11 to "RPL-2", 55 to "ACME", 54 to "1", 60 to TIME, 38 to "20", 40 to "2", 44 to "101"))
        assertEquals(listOf<Reply>(Reply.ReplaceReject("RPL-2", "ORD-1", 2, Texts.REPLACE_NOT_SUPPORTED)), replies.sent)
        assertEquals(1, venue.orders.size)
        assertNull(store.find(P, 2L))
    }

    @Test
    fun `a type order entry does not handle is a 35=j, reason 3, and a 35=j is never answered`() {
        deliver("UX", 1, listOf(58 to "x"))
        deliver("j", 2, listOf(45 to "1", 380 to "3"))
        assertEquals(listOf<Reply>(Reply.BusinessReject(1, "UX", 3, Texts.UNSUPPORTED_MESSAGE_TYPE)), replies.sent)
    }

    // ================================================================ the library thread allocates nothing

    @Test
    fun `an order on its way to the exchange allocates nothing`() {
        val quiet = QuietVenue()
        val hot = InboundOrders(store, quiet, QuietSecurities, { TODAY }, { })
        val count = 32_000
        val orders = (1..count).map { fix("D", it, listOf(11 to "O$it", 55 to "ACME", 54 to "1", 60 to TIME, 38 to "10", 40 to "2", 44 to "100.25")) }
        val buffers = orders.map { UnsafeBuffer(it) }
        fun run(from: Int, until: Int) {
            for (i in from until until) {
                check(hot.onMessage(P, NoReplies, 0, Constants.NEW_ORDER_SINGLE_MESSAGE, buffers[i], 0, orders[i].size) == Action.CONTINUE)
            }
        }
        // Rows come from the pool, and a store of live orders needs one per order: fill it first.
        // Agrona's maps resize at 0.65 of a power of two; 22,000 is past 21,299, and 32,000 short of
        // the next, 42,598. So only the path around the row is measured.
        val spare = (1..count).map { store.newRow() }
        spare.forEach(store::release)
        run(0, 22_000)
        // The JVM itself allocates on this thread now and then (a deoptimisation, say): seen once in
        // 10,000 orders, 640 bytes. An allocation per order shows in every window, so one window
        // of five at 0 bytes is the claim, and it cannot pass by chance.
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val windows = (0 until 5).map { w ->
            val before = threads.currentThreadAllocatedBytes
            run(22_000 + w * 2_000, 24_000 + w * 2_000)
            threads.currentThreadAllocatedBytes - before
        }
        assertEquals(count, quiet.sent)
        assertTrue(0L in windows, "bytes allocated per window of 2,000 orders decoded, validated, stored and sent: $windows")
    }

    // ================================================================ models

    private class SentOrder(
        val participantId: Long, val clOrdId: Long, val securityId: Int, val side: Side, val price: Long, val qty: Long,
        val expireDate: Int, val smpId: Long, val smpStrategy: SmpStrategy, val result: SendResult, val storedBeforeSend: Boolean,
    )

    private class SentCancel(
        val participantId: Long, val clOrdId: Long, val origClOrdId: Long, val exchangeOrderId: Long, val securityId: Int,
        val side: Side, val result: SendResult, val storedBeforeSend: Boolean,
    )

    /** The SDK session's sending side, as Adapters.md §2 states it: each call answers a `SendResult`. */
    private class VenueModel : OrderVenue {
        lateinit var store: OrderStore
        private val results = ArrayDeque<SendResult>()
        val orders = mutableListOf<SentOrder>()
        val cancels = mutableListOf<SentCancel>()

        fun next(result: SendResult) = results.addLast(result)

        /** ACME on shard 2, OTHER on shard 5, as a directory might put them. */
        override fun shardOf(securityId: Int) = when (securityId) {
            ACME -> 2
            OTHER -> 5
            else -> RequestRow.NO_SHARD
        }

        override fun newOrder(
            participantId: Long, clOrdId: Long, securityId: Int, side: Side, price: Long, qty: Long,
            expireDate: Int, smpId: Long, smpStrategy: SmpStrategy,
        ): SendResult {
            val result = results.removeFirstOrNull() ?: SendResult.SENT
            orders += SentOrder(participantId, clOrdId, securityId, side, price, qty, expireDate, smpId, smpStrategy, result, store.find(participantId, clOrdId) != null)
            return result
        }

        override fun cancel(participantId: Long, clOrdId: Long, origClOrdId: Long, exchangeOrderId: Long, securityId: Int, side: Side): SendResult {
            val result = results.removeFirstOrNull() ?: SendResult.SENT
            cancels += SentCancel(participantId, clOrdId, origClOrdId, exchangeOrderId, securityId, side, result, store.find(participantId, clOrdId) != null)
            return result
        }
    }

    private class QuietVenue : OrderVenue {
        var sent = 0
        override fun shardOf(securityId: Int) = 0
        override fun newOrder(
            participantId: Long, clOrdId: Long, securityId: Int, side: Side, price: Long, qty: Long,
            expireDate: Int, smpId: Long, smpStrategy: SmpStrategy,
        ): SendResult = SendResult.SENT.also { sent++ }

        override fun cancel(participantId: Long, clOrdId: Long, origClOrdId: Long, exchangeOrderId: Long, securityId: Int, side: Side) =
            SendResult.SENT.also { sent++ }
    }

    /** Every security is ACME, found without allocating: for measuring the path, not the directory. */
    private object QuietSecurities : SecurityLookup {
        override val isReady = true
        override fun isKnown(securityId: Int) = true
        override fun byIsin(buffer: org.agrona.DirectBuffer, offset: Int, length: Int) = ACME
        override fun bySymbol(buffer: org.agrona.DirectBuffer, offset: Int, length: Int) = ACME
    }

    /** The directory, as §5.1 uses it: securityId, ISIN and Symbol. */
    private class SecuritiesModel : SecurityLookup {
        private val ids = mapOf(ACME to ("NL0000000001" to "ACME"), OTHER to ("NL0000000002" to "OTHER"))

        /** False until a complete directory has arrived (§3.5). */
        override var isReady = true
        override fun isKnown(securityId: Int) = securityId in ids
        override fun byIsin(buffer: org.agrona.DirectBuffer, offset: Int, length: Int): Int {
            val isin = buffer.getStringWithoutLengthAscii(offset, length)
            return ids.entries.firstOrNull { it.value.first == isin }?.key ?: SecurityLookup.NONE
        }
        override fun bySymbol(buffer: org.agrona.DirectBuffer, offset: Int, length: Int): Int {
            val symbol = buffer.getStringWithoutLengthAscii(offset, length)
            return ids.entries.firstOrNull { it.value.second == symbol }?.key ?: SecurityLookup.NONE
        }
    }

    private companion object {
        const val P = 1001L
        const val ACME = 42
        const val OTHER = 43
        const val TODAY = 20261026
        const val TIME = "20261026-09:00:00.000"
        const val OMIT = "\u0000omit"

        fun fix(type: String, seq: Int, fields: List<Pair<Int, String>>, possDup: Boolean = false): ByteArray {
            val header = listOf(49 to "CLIENT1", 56 to "MOST", 34 to "$seq") +
                (if (possDup) listOf(43 to "Y") else emptyList()) +
                listOf(52 to TIME) +
                (if (possDup) listOf(122 to TIME) else emptyList())
            return fixMessage(type, header + fields)
        }
    }
}
