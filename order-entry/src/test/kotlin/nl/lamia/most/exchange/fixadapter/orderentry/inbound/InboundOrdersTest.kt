package nl.lamia.most.exchange.fixadapter.orderentry.inbound

import org.agrona.DirectBuffer
import nl.lamia.most.exchange.client.ClOrdId
import io.aeron.logbuffer.ControlledFragmentHandler.Action
import nl.lamia.most.exchange.client.SendResult
import nl.lamia.most.exchange.fixadapter.codecs.Constants
import nl.lamia.most.exchange.fixadapter.orderentry.outbound.ArchiveModel
import nl.lamia.most.exchange.fixadapter.orderentry.outbound.OutboundReports
import nl.lamia.most.exchange.fixadapter.orderentry.outbound.RecordingClient
import nl.lamia.most.exchange.fixadapter.orderentry.outbound.Sent8
import nl.lamia.most.exchange.fixadapter.orderentry.outbound.Sent9
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
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Order entry's inbound path (Design.md §3.3, §5.1, §5.2, §5.4) and its sends, made once the archive
 * has the request's 35=D/F (§6.1, §8.20; CLAUDE.md non-negotiables 2 and 11), against a model of the
 * SDK's session (most-exchange Adapters.md §2: its `SendResult`s) and of the directory. A refused
 * send is answered by the outbound path, which runs here as it does in the adapter.
 */
class InboundOrdersTest {

    @TempDir
    lateinit var dir: File

    private lateinit var store: OrderStore
    private val venue = VenueModel()
    private val replies = RecordingReplies()
    private val securities = SecuritiesModel()
    private lateinit var inbound: InboundOrders
    private lateinit var sends: ArchivedSends
    private lateinit var outbound: OutboundReports

    /** What the outbound path sent the client: the answers to refused sends. */
    private val client = RecordingClient()

    /** The engine's inbound stream: each delivered message's position, and how far the archive has recorded it. */
    private var position = 0L
    private var recorded = -1L

    @BeforeTest
    fun open() {
        store = OrderStore.open(dir).also { it.load(TODAY) }
        // A new process: a new inbound publication, its recording not yet found.
        position = 0L
        recorded = -1L
        sends = ArchivedSends(store, venue, { recorded }) { row, text -> outbound.refuse(row, text) }
        outbound = OutboundReports(store, client, sends, ArchiveModel(store))
        outbound.recover()
        inbound = InboundOrders(store, venue, securities, { TODAY }, sends)
        venue.store = store
    }

    @AfterTest
    fun close() = store.close()

    /** The adapter restarted: the same store directory, a new process's state. */
    private fun restart() {
        store.close()
        open()
    }

    /** Delivers a message; with [archived], the archive then records it and a duty cycle runs. */
    private fun deliver(
        type: String, seq: Int, fields: List<Pair<Int, String>>, possDup: Boolean = false, sequenceIndex: Int = 0, archived: Boolean = true,
    ): Action {
        val bytes = fix(type, seq, fields, possDup)
        position += 256L
        val action = inbound.onMessage(P, replies, sequenceIndex, MessageTypeEncoding.packMessageType(type), UnsafeBuffer(bytes), 0, bytes.size, position)
        if (archived) archive()
        return action
    }

    /** The archive records every message delivered so far, then a duty cycle runs. */
    private fun archive() {
        recorded = position
        cycle()
    }

    private fun cycle() {
        sends.doWork()
        outbound.doWork()
    }

    /** What the outbound path answered, as (ClOrdID, OrdStatus or CxlRejReason, Text). */
    private fun answered(): List<Triple<String, Any?, String?>> = client.sent.map {
        when (it) {
            is Sent8 -> Triple(it.clOrdId, it.ordStatus to it.ordRejReason, it.text)
            is Sent9 -> Triple(it.clOrdId, it.cxlRejReason, it.text)
            else -> error("$it")
        }
    }

    /** [base] with each of [extra] replacing its tag's value, [OMIT] removing it, or appended. */
    private fun fields(base: List<Pair<Int, String>>, extra: Array<out Pair<Int, String>>): List<Pair<Int, String>> {
        val overrides = extra.toMap()
        return base.map { (tag, value) -> tag to (overrides[tag] ?: value) }.filter { it.second != OMIT } +
            extra.filter { (tag, value) -> base.none { it.first == tag } && value != OMIT }
    }

    private fun order(seq: Int, clOrdId: String = "ORD-$seq", vararg extra: Pair<Int, String>, possDup: Boolean = false, archived: Boolean = true): Action =
        deliver("D", seq, fields(listOf(11 to clOrdId, 55 to "ACME", 54 to "1", 60 to TIME, 38 to "10", 40 to "2", 44 to "100.25"), extra), possDup, archived = archived)

    private fun cancel(
        seq: Int, origClOrdId: String, clOrdId: String = "CXL-$seq", vararg extra: Pair<Int, String>, possDup: Boolean = false, archived: Boolean = true,
    ): Action = deliver("F", seq, fields(listOf(41 to origClOrdId, 11 to clOrdId, 55 to "ACME", 54 to "1", 60 to TIME), extra), possDup, archived = archived)

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

    /**
     * The row of the request at [position] in the participant's epoch (Design.md §3.2): these tests
     * name a request by its MsgSeqNum. Scans, and allocates: never in a measured path.
     */
    private fun OrderStore.find(participantId: Long, position: Long): RequestRow? {
        var found: RequestRow? = null
        forEach { if (it.participantId == participantId && it.position == position && it.epoch == epoch(participantId)) found = it }
        return found
    }

    // ================================================================ 35=D, §5.1

    @Test
    fun `a limit order is written to the store, then sent with the session's participant, its own ClOrdID, and its fields converted`() {
        assertEquals(Action.CONTINUE, order(7))
        val sent = venue.orders.single()
        assertEquals(P, sent.participantId, "the session's participant (§2)")
        assertEquals("ORD-7", sent.clOrdId, "the client's ClOrdID as sent, the exchange's id since schema 8 (§3.2)")
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
    fun `a request's row keeps its position, Artio's sequenceIndex and the MsgSeqNum, which is never sent`() {
        deliver("D", 7, listOf(11 to "ORD-7", 55 to "ACME", 54 to "2", 60 to TIME, 38 to "1", 40 to "2", 44 to "1"), sequenceIndex = 3)
        assertEquals((3L shl 32) or 7L, store.findByFix("ORD-7")?.position, "(sequenceIndex << 32) | MsgSeqNum (§3.2)")
        assertEquals("ORD-7", venue.orders.single().clOrdId)
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
    fun `a back-pressured send stays on its row and the duty cycle tries it again, while Artio's message is consumed`() {
        // Non-negotiable 2: back-pressure is not loss; the FIX message is consumed once its row is stored.
        venue.next(SendResult.BACK_PRESSURED)
        assertEquals(Action.CONTINUE, order(1))
        val row = assertNotNull(store.find(P, 1L), "the row is written before the send")
        assertTrue(row.sendPending)
        assertEquals(Action.CONTINUE, order(1, archived = false), "the same message again sends nothing itself")
        assertEquals(listOf(SendResult.BACK_PRESSURED), venue.orders.map { it.result })
        cycle()
        assertEquals(listOf(SendResult.BACK_PRESSURED, SendResult.SENT), venue.orders.map { it.result })
        assertFalse(row.sendPending)
        assertTrue(replies.sent.isEmpty())
        assertTrue(client.sent.isEmpty())
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
        // From the duty cycle, through the outbound path (§6.1): 35=8 OrdStatus 8, OrdRejReason 99.
        assertEquals(
            listOf(Triple("ORD-1", '8' to 99, Texts.EXCHANGE_UNAVAILABLE), Triple("ORD-2", '8' to 99, Texts.EXCHANGE_UNAVAILABLE)),
            answered(),
        )
        assertTrue(replies.sent.isEmpty())
        order(3, "ORD-1")
        assertEquals(listOf<Reply>(Reply.OrderReject("ORD-1", 6, Texts.DUPLICATE_CLORDID)), replies.sent, "a rejected order's ClOrdID is the day's")
    }

    @Test
    fun `UNKNOWN_PARTICIPANT rejects the order, participant not served`() {
        venue.next(SendResult.UNKNOWN_PARTICIPANT)
        order(1)
        assertEquals(listOf(Triple("ORD-1", '8' to 99, Texts.PARTICIPANT_NOT_SERVED)), answered())
    }

    @Test
    fun `an answer of the inbound path's own that Artio refuses aborts the message, and its redelivery answers it once`() {
        // §3.3, "A reply Artio refuses": non-negotiable 2 for the adapter's own answers.
        replies.accepting = false
        assertEquals(Action.ABORT, order(1, extra = arrayOf(40 to "1")))
        replies.accepting = true
        assertEquals(Action.CONTINUE, order(1, extra = arrayOf(40 to "1")))
        assertEquals(listOf<Reply>(Reply.OrderReject("ORD-1", 99, Texts.LIMIT_ORDERS_ONLY)), replies.sent)
        assertNull(store.find(P, 1L))
    }

    @Test
    fun `a refusal's reject the client cannot take yet is sent once it can, and the request is never sent after it`() {
        venue.next(SendResult.NO_GATEWAY)
        client.accepting = false
        order(1)
        order(1, archived = false)
        cycle()
        assertTrue(client.sent.isEmpty())
        client.accepting = true
        cycle()
        assertEquals(listOf(Triple("ORD-1", '8' to 99, Texts.EXCHANGE_UNAVAILABLE)), answered())
        assertEquals(listOf(SendResult.NO_GATEWAY), venue.orders.map { it.result }, "refused once, answered once, never both")
        assertFalse(assertNotNull(store.find(P, 1L)).isInFlight)
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
    fun `a cancel refused before the directory names its order, so its status is stated`() {
        // Design.md §3.3, "The adapter's own answers": never OrdStatus 8 for an order that may be open.
        order(1)
        acknowledge("ORD-1")
        securities.isReady = false
        cancel(2, "ORD-1")
        securities.isReady = true
        assertEquals(listOf(Texts.EXCHANGE_UNAVAILABLE), replies.sent.filterIsInstance<Reply.CancelReject>().map { it.text })
        assertEquals(listOf<String?>("ORD-1"), replies.namedOrders)
    }

    // ================================================================ the client's ClOrdID, §3.2 (schema 8)

    @Test
    fun `a ClOrdID the exchange cannot carry is refused, OrdRejReason 99, never stored or sent`() {
        // Design.md §3.2, §3.3: 1 to 20 of A-Z a-z 0-9 - _ + ; : . /, else refused, never mapped.
        val unfit = listOf("X".repeat(21), "ORD 1", "ORD#1", "ORD@1", "ORD,1", "ORD*1")
        var seq = 1
        for (id in unfit) {
            replies.sent.clear()
            assertEquals(Action.CONTINUE, order(seq, id), id)
            assertEquals(listOf<Reply>(Reply.OrderReject(id, 99, Texts.CLORDID_NOT_FIT)), replies.sent, id)
            assertNull(store.find(P, seq.toLong()), "$id: no row")
            seq++
        }
        assertTrue(venue.orders.isEmpty(), "none sent")
        assertEquals(seq - 1L, store.highWater(P), "each answered here, so a restart replays none (§8.17)")
        order(seq, "Az09-_+;:./abcdeXYZ9") // every kind of character allowed, and all 20
        assertEquals("Az09-_+;:./abcdeXYZ9", venue.orders.single().clOrdId)
    }

    @Test
    fun `a cancel whose ClOrdID does not fit is CxlRejReason 99, and one whose OrigClOrdID does not fit names no order`() {
        order(1)
        acknowledge("ORD-1")
        replies.sent.clear()
        cancel(2, "ORD-1", "CXL 2")
        cancel(3, "ORD-1".padEnd(21, 'x'))
        assertEquals(
            listOf<Reply>(
                Reply.CancelReject("CXL 2", "ORD-1", 99, Texts.CLORDID_NOT_FIT),
                Reply.CancelReject("CXL-3", "ORD-1xxxxxxxxxxxxxxxx", 1, Texts.UNKNOWN_ORDER),
            ),
            replies.sent,
        )
        assertEquals(listOf<String?>("ORD-1", null), replies.namedOrders, "the first states its order's status (§3.3)")
        assertTrue(venue.cancels.isEmpty())
    }

    @Test
    fun `a request at a position another ClOrdID holds is a new request, sent, and nothing blocks the session`() {
        // §8.15 superseded by schema 8 (R§17): Artio's state lost, the client's MsgSeqNum 5 comes again.
        order(5, "OLD-5")
        replies.sent.clear()
        assertEquals(Action.CONTINUE, order(5, "NEW-5"))
        assertTrue(replies.sent.isEmpty(), "no reject, no Logout")
        order(6, "NEW-6")
        assertEquals(listOf("OLD-5", "NEW-5", "NEW-6"), venue.orders.map { it.clOrdId })
    }

    // ================================================================ 35=F, §5.2

    @Test
    fun `a cancel of an acknowledged order is sent with the stored exchangeOrderId, security and side`() {
        order(1)
        acknowledge("ORD-1", exchangeOrderId = 9001L)
        assertEquals(Action.CONTINUE, cancel(2, "ORD-1"))
        val sent = venue.cancels.single()
        assertEquals(P, sent.participantId)
        assertEquals("CXL-2", sent.clOrdId, "the cancel's own ClOrdID (§5.2)")
        assertEquals("ORD-1", sent.origClOrdId, "the order's")
        assertEquals(9001L, sent.exchangeOrderId)
        assertEquals(ACME, sent.securityId)
        assertEquals(Side.BUY, sent.side)
        assertTrue(sent.storedBeforeSend)

        val row = assertNotNull(store.find(P, 2L))
        assertEquals(RowKind.CANCEL, row.kind)
        assertEquals(CancelState.SENT, row.cancelState)
        assertEquals("ORD-1", row.origClOrdIdAsString())
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
        assertEquals(listOf(Triple("CXL-2", 99, Texts.EXCHANGE_UNAVAILABLE)), answered())
        assertEquals(CancelState.ANSWERED, store.find(P, 2L)?.cancelState)
        assertNull(store.find(P, 1L)?.pendingCancel)
        cancel(3, "ORD-1")
        assertEquals(listOf(SendResult.NO_GATEWAY, SendResult.SENT), venue.cancels.map { it.result })
    }

    @Test
    fun `a back-pressured cancel stays on its row and is sent once by the duty cycle, and a PossDup resend is dropped`() {
        order(1)
        acknowledge("ORD-1")
        venue.next(SendResult.BACK_PRESSURED)
        assertEquals(Action.CONTINUE, cancel(2, "ORD-1"))
        assertEquals(Action.CONTINUE, cancel(2, "ORD-1", archived = false))
        cancel(2, "ORD-1", possDup = true)
        assertEquals(listOf(SendResult.BACK_PRESSURED, SendResult.SENT), venue.cancels.map { it.result })
        assertTrue(replies.sent.isEmpty())
    }

    // ================================================================ sent once the archive has it, §6.1, §8.20

    @Test
    fun `a request is sent only once the archive has recorded its 35=D, and its message is consumed at once`() {
        // CLAUDE.md non-negotiable 11: the library sees a 35=D before the archive has fsynced it.
        assertEquals(Action.CONTINUE, order(1, archived = false))
        val row = assertNotNull(store.find(P, 1L), "stored, to send")
        assertTrue(row.sendPending)
        recorded = position - 1L
        cycle()
        assertTrue(venue.orders.isEmpty(), "one byte short of the message")
        recorded = position
        cycle()
        assertEquals(listOf(SendResult.SENT), venue.orders.map { it.result })
        assertFalse(row.sendPending)
    }

    @Test
    fun `requests go out in the order they came, as far as the archive has recorded`() {
        order(1, archived = false)
        val first = position
        order(2, archived = false)
        order(3, archived = false)
        recorded = first + 256L
        cycle()
        assertEquals(listOf("ORD-1", "ORD-2"), venue.orders.map { it.clOrdId })
        archive()
        assertEquals(listOf("ORD-1", "ORD-2", "ORD-3"), venue.orders.map { it.clOrdId })
    }

    @Test
    fun `while the inbound stream's recording is not known nothing is sent`() {
        order(1, archived = false)
        recorded = -1L // LibraryRecording.UNKNOWN
        cycle()
        assertTrue(venue.orders.isEmpty())
    }

    @Test
    fun `a back-pressured send holds the requests behind it, so they keep their order`() {
        venue.next(SendResult.BACK_PRESSURED)
        order(1, archived = false)
        order(2, archived = false)
        archive()
        assertEquals(listOf("ORD-1" to SendResult.BACK_PRESSURED), venue.orders.map { it.clOrdId to it.result })
        cycle()
        assertEquals(
            listOf("ORD-1" to SendResult.BACK_PRESSURED, "ORD-1" to SendResult.SENT, "ORD-2" to SendResult.SENT),
            venue.orders.map { it.clOrdId to it.result },
        )
    }

    @Test
    fun `a cancel of an acknowledged order waits for its own 35=F to be archived`() {
        order(1)
        acknowledge("ORD-1")
        cancel(2, "ORD-1", archived = false)
        cycle()
        assertTrue(venue.cancels.isEmpty())
        archive()
        assertEquals(listOf("CXL-2"), venue.cancels.map { it.clOrdId })
    }

    @Test
    fun `a request stored but not yet archived when the process stops is never sent by the next one, the SDK settles it`() {
        order(1, archived = false)
        restart()
        order(1) // the claim's replay
        assertTrue(venue.orders.isEmpty())
        assertTrue(assertNotNull(store.find(P, 1L)).isInFlight, "restored in flight, settled by the fence (§3.4)")
    }

    // ================================================================ recovery of lost rows, §8.17

    /** The archive replays a message at startup, before the directory has come. */
    private fun replay(type: String, seq: Int, fields: List<Pair<Int, String>>, possDup: Boolean = false, sequenceIndex: Int = 0): Action {
        securities.isReady = false
        val bytes = fix(type, seq, fields, possDup)
        return inbound.onReplayed(P, sequenceIndex, MessageTypeEncoding.packMessageType(type), UnsafeBuffer(bytes), 0, bytes.size)
    }

    private fun replayOrder(seq: Int, clOrdId: String = "ORD-$seq", vararg extra: Pair<Int, String>, possDup: Boolean = false): Action =
        replay("D", seq, fields(listOf(11 to clOrdId, 55 to "ACME", 54 to "1", 60 to TIME, 38 to "10", 40 to "2", 44 to "100.25"), extra), possDup)

    private fun replayCancel(seq: Int, origClOrdId: String, clOrdId: String = "CXL-$seq", vararg extra: Pair<Int, String>): Action =
        replay("F", seq, fields(listOf(41 to origClOrdId, 11 to clOrdId, 55 to "ACME", 54 to "1", 60 to TIME), extra))

    /** The directory arrives: what recovery rebuilt is resolved, refusals answered by the outbound path. */
    private fun directoryArrives(): Int {
        securities.isReady = true
        return inbound.resolveRecovered { row, text -> outbound.refuse(row, text) }.also { archive() }
    }

    @Test
    fun `a replayed 35=D with no row is rebuilt in flight, never sent and never answered, its shard resolved once the directory arrives`() {
        assertEquals(Action.CONTINUE, replayOrder(7, extra = arrayOf(59 to "1")))
        archive()
        val row = assertNotNull(store.find(P, 7L))
        assertTrue(row.isUnresolved, "the directory is not there yet")
        assertEquals(RequestRow.NO_SHARD, row.shardId)
        assertFalse(row.acknowledged)
        assertTrue(row.isInFlight, "the fence settles it (§3.4): a reject if never sequenced, its reports if it was")
        assertEquals(10_00000000L, row.origQty)
        assertEquals('1'.code.toByte(), row.timeInForce)
        assertEquals(1L, inbound.rebuilt)

        assertEquals(1, directoryArrives())
        assertEquals(ACME, row.securityId)
        assertEquals(2, row.shardId, "its security's shard, before that shard's restoreInFlight")
        assertTrue(row.isInFlight)
        assertEquals(ACME, restartedRow(7L).securityId, "stored resolved")
        assertTrue(venue.orders.isEmpty(), "the exchange does not check clOrdId: sent again, it is a second order")
        assertTrue(replies.sent.isEmpty() && client.sent.isEmpty(), "never answered")
    }

    private fun restartedRow(clOrdId: Long): RequestRow {
        restart()
        return assertNotNull(store.find(P, clOrdId))
    }

    @Test
    fun `what the message decides is decided again in recovery, and answered by no one`() {
        replay("D", 1, listOf(11 to "BAD-1", 55 to "ACME", 54 to "1", 60 to TIME, 38 to "10", 40 to "2", 44 to "1", 9999 to "x"))
        replayOrder(2, extra = arrayOf(38 to "0"))
        replayOrder(3, extra = arrayOf(40 to "1"))
        replayOrder(4, extra = arrayOf(59 to "6"))
        archive()
        assertEquals(0, store.size)
        assertTrue(replies.sent.isEmpty(), "${replies.sent}")
        assertEquals(0, directoryArrives())
    }

    @Test
    fun `a replayed request at or below the high-water with no row was handled already, and is skipped`() {
        store.handled(P, 5L)
        replayOrder(4)
        replayOrder(5)
        replayOrder(6)
        assertEquals(listOf(6L), listOf(4L, 5L, 6L).filter { store.find(P, it) != null })
    }

    @Test
    fun `a replayed duplicate ClOrdID is not rebuilt, PossDup or not`() {
        order(1, clOrdId = "ORD-A")
        venue.orders.clear()
        replayOrder(2, clOrdId = "ORD-A")
        replayOrder(3, clOrdId = "ORD-A", possDup = true)
        assertNull(store.find(P, 2L))
        assertNull(store.find(P, 3L))
        assertTrue(replies.sent.isEmpty())
    }

    @Test
    fun `a recovered order whose security the directory does not know is refused, OrdRejReason 99 unknown security`() {
        replayOrder(1, extra = arrayOf(55 to "NOPE"))
        directoryArrives()
        assertEquals(listOf(Triple("ORD-1", '8' to 99, Texts.UNKNOWN_SECURITY)), answered())
        val row = assertNotNull(store.find(P, 1L))
        assertEquals(RequestRow.NO_SECURITY, row.securityId, "stated with no 48")
        assertFalse(row.isInFlight)
        assertFalse(row.isUnresolved)
        assertTrue(venue.orders.isEmpty())
    }

    @Test
    fun `a recovery that stops before the directory loses nothing, the next start replays the unresolved row and resolves it`() {
        replayOrder(3)
        replayOrder(4)
        restart()
        assertEquals(3L, store.replayFrom(P), "no later than its lowest unresolved row")
        replayOrder(3)
        replayOrder(4)
        assertEquals(0L, inbound.rebuilt, "held already: the same request again")
        assertEquals(2, directoryArrives(), "their copies kept again")
        assertEquals(listOf(2, 2), listOf(3L, 4L).map { store.find(P, it)!!.shardId })
        assertEquals(5L, store.replayFrom(P))
    }

    @Test
    fun `a replayed cancel is rebuilt sent whatever its order's state, so it is never sent again`() {
        order(1)
        order(2)
        acknowledge("ORD-2")
        venue.orders.clear()
        replayCancel(3, "ORD-1")
        replayCancel(4, "ORD-2")
        val parkedLive = assertNotNull(store.find(P, 3L))
        val sentLive = assertNotNull(store.find(P, 4L))
        assertEquals(CancelState.SENT, parkedLive.cancelState, "parked live, or sent: a write lost after it may have acknowledged its order")
        assertEquals(CancelState.SENT, sentLive.cancelState)
        assertSame(parkedLive, store.find(P, 1L)?.pendingCancel)
        assertEquals(2, directoryArrives())
        assertEquals(listOf(2, 2), listOf(parkedLive.shardId, sentLive.shardId))
        assertTrue(parkedLive.isInFlight && sentLive.isInFlight)
        assertTrue(venue.cancels.isEmpty())
        assertTrue(client.sent.isEmpty())
    }

    @Test
    fun `a replayed cancel of an ambiguous order, or of one with a cancel pending, is rebuilt, since a lost write may have changed either`() {
        order(1)
        order(2)
        store.find(P, 1L)!!.ambiguous = true
        cancel(3, "ORD-2")
        replayCancel(4, "ORD-1")
        replayCancel(5, "ORD-2")
        assertNotNull(store.find(P, 4L))
        assertNotNull(store.find(P, 5L))
        assertEquals(3L, store.find(P, 2L)?.pendingCancel?.position, "the first stays the pending one")
    }

    @Test
    fun `a replayed cancel of a terminal order is too late again, and is not rebuilt`() {
        order(1)
        acknowledge("ORD-1", status = '2')
        replayCancel(2, "ORD-1")
        assertNull(store.find(P, 2L))
    }

    @Test
    fun `in recovery a cancel's OrderID is checked only against an order the store has acknowledged`() {
        order(1)
        order(2)
        acknowledge("ORD-2", exchangeOrderId = 9002L)
        replayCancel(3, "ORD-1", extra = arrayOf(37 to "9001"))
        replayCancel(4, "ORD-2", extra = arrayOf(37 to "1234"))
        assertNotNull(store.find(P, 3L), "its acknowledgement may be a write the crash lost")
        assertNull(store.find(P, 4L), "acknowledged with another OrderID: it was not this order's")
    }

    @Test
    fun `a recovered cancel naming another security than its order's is refused once the directory can tell`() {
        order(1)
        acknowledge("ORD-1")
        replayCancel(2, "ORD-1", extra = arrayOf(55 to "OTHER"))
        assertNotNull(store.find(P, 2L), "the directory is not there to compare")
        directoryArrives()
        assertEquals(listOf(Triple("CXL-2", 99, Texts.ORDER_MISMATCH)), answered())
        assertFalse(store.find(P, 2L)!!.isInFlight)
    }

    @Test
    fun `a recovered cancel of a recovered order the directory does not know is refused with its order`() {
        replayOrder(1, extra = arrayOf(55 to "NOPE"))
        replayCancel(2, "ORD-1")
        directoryArrives()
        assertEquals(
            listOf(Triple("ORD-1", '8' to 99, Texts.UNKNOWN_SECURITY), Triple("CXL-2", 99, Texts.UNKNOWN_SECURITY)),
            answered(),
        )
    }

    @Test
    fun `a 35=D or 35=F rejected here raises the high-water once Artio took the reject, so a restart does not replay it`() {
        order(1, extra = arrayOf(38 to "0"))
        assertEquals(1L, store.highWater(P))
        cancel(2, "NOWHERE")
        assertEquals(2L, store.highWater(P))
        replies.accepting = false
        assertEquals(Action.ABORT, order(3, extra = arrayOf(38 to "0")))
        assertEquals(2L, store.highWater(P), "refused, it is delivered again: not handled yet")
        replies.accepting = true
        securities.isReady = false
        order(4)
        assertEquals(4L, store.highWater(P), "exchange unavailable: decided from the directory, so it must not be decided again")
        restart()
        assertEquals(5L, store.replayFrom(P))
    }

    // ================================================================ 35=H and 35=AF, from the store, §3.3

    private fun statusRequest(seq: Int, clOrdId: String, vararg extra: Pair<Int, String>): Action =
        deliver("H", seq, fields(listOf(11 to clOrdId, 55 to "ACME", 54 to "1"), extra))

    private fun massStatusRequest(seq: Int, reqId: String = "MS-$seq", type: String = "7", vararg extra: Pair<Int, String>): Action =
        deliver("AF", seq, fields(listOf(584 to reqId, 585 to type), extra))

    private fun statuses() = replies.sent.filterIsInstance<Reply.Status>()

    /** The order's fills, as the outbound path would have stored them: [cum] at [price], AvgPx their notional over CumQty. */
    private fun filled(clOrdId: String, cum: Long, price: Long = 100_25000000L) {
        val row = assertNotNull(store.findByFix(clOrdId))
        row.cumQty = cum
        row.leavesQty = row.origQty - cum
        val n = nl.lamia.most.exchange.fixadapter.orderentry.outbound.Notional().apply { add(price, cum) }
        row.notionalHi = n.hi
        row.notionalLo = n.lo
        store.update(row)
    }

    @Test
    fun `a 35=H is answered from the store with a 35=8 ExecType I, pending new before any report, its OrdStatusReqID echoed`() {
        order(1)
        assertEquals(Action.CONTINUE, statusRequest(2, "ORD-1", 790 to "Q-1"))
        val s = statuses().single()
        assertEquals("ORD-1", s.clOrdId)
        assertEquals('A', s.ordStatus, "OrdStatus A before any status was sent (§3.4)")
        assertEquals(0L, s.orderId, "OrderID NONE until acknowledged")
        assertEquals(0L to 10_00000000L, s.cumQty to s.leavesQty, "0 and OrderQty, as entered")
        assertEquals("Q-1", s.ordStatusReqId)
        assertNull(s.massStatusReqId)
        assertEquals(1, venue.orders.size, "nothing sent for it")
    }

    @Test
    fun `a 35=H states what the client was last told, AvgPx over the fills known`() {
        order(1)
        acknowledge("ORD-1", exchangeOrderId = 9001L, status = '1')
        filled("ORD-1", cum = 4_00000000L)
        statusRequest(2, "ORD-1")
        val s = statuses().single()
        assertEquals('1', s.ordStatus)
        assertEquals(9001L, s.orderId)
        assertEquals(4_00000000L to 6_00000000L, s.cumQty to s.leavesQty)
        assertEquals(100_25000000L, s.avgPx)
        assertFalse(s.statusUnknown)
        assertNull(s.ordStatusReqId, "790 absent, none echoed")
    }

    @Test
    fun `a 35=H for an ambiguous order says so, with OrdStatusUnknown and §3_4's Text, never a terminal status`() {
        order(1)
        acknowledge("ORD-1", status = '0')
        store.findByFix("ORD-1")!!.ambiguous = true
        statusRequest(2, "ORD-1")
        val s = statuses().single()
        assertEquals('0', s.ordStatus)
        assertTrue(s.statusUnknown)
        assertTrue(s.text!!.startsWith("STATUS UNKNOWN"), s.text)
    }

    @Test
    fun `a 35=H naming a ClOrdID the store does not hold gets FIX 4_4's unknown-order answer`() {
        statusRequest(1, "NOWHERE", 790 to "Q-9", 54 to "2")
        assertEquals(listOf<Reply>(Reply.UnknownOrderStatus("NOWHERE", '2', "Q-9")), replies.sent)
    }

    @Test
    fun `a 35=H naming a cancel's ClOrdID gets its order's status, 11 the cancel's and 41 the order's`() {
        order(1)
        acknowledge("ORD-1")
        cancel(2, "ORD-1")
        statusRequest(3, "CXL-2")
        val s = statuses().single()
        assertEquals("CXL-2" to "ORD-1", s.clOrdId to s.origClOrdId)
        assertEquals('0', s.ordStatus)
    }

    @Test
    fun `a 35=H whose answer Artio refuses is delivered again`() {
        order(1)
        replies.accepting = false
        assertEquals(Action.ABORT, statusRequest(2, "ORD-1"))
    }

    @Test
    fun `a 35=AF of type 7 states every open order of the session, in the order they came, the last with LastRptRequested`() {
        order(1)
        order(2)
        order(3)
        order(4)
        acknowledge("ORD-2", status = '2') // filled: terminal, not open
        acknowledge("ORD-3", status = '1')
        store.findByFix("ORD-4")!!.ambiguous = true
        store.insert(store.newRow().apply { // another participant's
            kind = RowKind.ORDER; participantId = P + 1; position = 1L; fixClOrdId("THEIRS"); securityId = ACME; shardId = 2
        })
        replies.sent.clear()
        assertEquals(Action.CONTINUE, massStatusRequest(5, "MS-1"))
        val s = statuses()
        assertEquals(listOf("ORD-1", "ORD-3", "ORD-4"), s.map { it.clOrdId })
        assertEquals(listOf('A', '1', 'A'), s.map { it.ordStatus })
        assertEquals(listOf(false, false, true), s.map { it.statusUnknown }, "an ambiguous order is open, and says so")
        assertTrue(s.all { it.massStatusReqId == "MS-1" && it.totNumReports == 3 })
        assertEquals(listOf(false, false, true), s.map { it.lastRptRequested })
    }

    @Test
    fun `a 35=AF of type 1 states one security's open orders, and Side narrows either type`() {
        order(1)
        order(2, extra = arrayOf(55 to "OTHER"))
        order(3, extra = arrayOf(54 to "2"))
        massStatusRequest(4, "BY-SEC", "1", 55 to "ACME")
        massStatusRequest(5, "BY-ISIN", "1", 48 to "NL0000000002", 22 to "4")
        massStatusRequest(6, "SELLS", "7", 54 to "2")
        val by = statuses().groupBy({ it.massStatusReqId }, { it.clOrdId })
        assertEquals(listOf("ORD-1", "ORD-3"), by["BY-SEC"])
        assertEquals(listOf("ORD-2"), by["BY-ISIN"])
        assertEquals(listOf("ORD-3"), by["SELLS"])
    }

    @Test
    fun `a 35=AF with no open order matching is a 35=j, reason 0, no open orders`() {
        order(1)
        acknowledge("ORD-1", status = '4')
        massStatusRequest(2)
        assertEquals(listOf<Reply>(Reply.BusinessReject(2, "AF", 0, Texts.NO_OPEN_ORDERS)), replies.sent)
    }

    @Test
    fun `a 35=AF of type 1 with no security, one unknown, or before the directory is a 35=j saying which`() {
        order(1)
        massStatusRequest(2, type = "1")
        massStatusRequest(3, type = "1", extra = arrayOf(55 to "NOPE"))
        securities.isReady = false
        massStatusRequest(4, type = "1", extra = arrayOf(55 to "ACME"))
        assertEquals(
            listOf<Reply>(
                Reply.BusinessReject(2, "AF", 5, Texts.SECURITY_REQUIRED),
                Reply.BusinessReject(3, "AF", 2, Texts.UNKNOWN_SECURITY),
                Reply.BusinessReject(4, "AF", 4, Texts.EXCHANGE_UNAVAILABLE),
            ),
            replies.sent,
        )
    }

    @Test
    fun `a 35=AF of any other MassStatusReqType is refused by the dictionary, a 35=3 with SessionRejectReason 5`() {
        massStatusRequest(1, type = "8")
        assertEquals(listOf<Reply>(Reply.SessionReject(1, "AF", 585, 5)), replies.sent)
    }

    @Test
    fun `a 35=AF answer Artio refuses part way goes on from the next order when delivered again, nothing twice`() {
        for (seq in 1..4) order(seq)
        replies.acceptOnly = 2
        assertEquals(Action.ABORT, massStatusRequest(5, "MS-5"))
        replies.acceptOnly = null
        assertEquals(Action.CONTINUE, massStatusRequest(5, "MS-5"))
        val s = statuses()
        assertEquals(listOf("ORD-1", "ORD-2", "ORD-3", "ORD-4"), s.map { it.clOrdId })
        assertTrue(s.all { it.totNumReports == 4 })
        assertEquals(listOf(false, false, false, true), s.map { it.lastRptRequested })

        massStatusRequest(6, "MS-6")
        assertEquals(4, statuses().count { it.massStatusReqId == "MS-6" }, "a new request starts over")
    }

    @Test
    fun `a 35=H or 35=AF replayed from the archive is answered by no one`() {
        order(1)
        replay("H", 2, listOf(11 to "ORD-1", 55 to "ACME", 54 to "1"))
        replay("AF", 3, listOf(584 to "MS", 585 to "7"))
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
        val hotSends = ArchivedSends(store, quiet, { Long.MAX_VALUE }) { _, _ -> error("refused") }
        val hot = InboundOrders(store, quiet, QuietSecurities, { TODAY }, hotSends)
        val count = 32_000
        val orders = (1..count).map { fix("D", it, listOf(11 to "O$it", 55 to "ACME", 54 to "1", 60 to TIME, 38 to "10", 40 to "2", 44 to "100.25")) }
        val buffers = orders.map { UnsafeBuffer(it) }
        fun run(from: Int, until: Int) {
            for (i in from until until) {
                check(hot.onMessage(P, NoReplies, 0, Constants.NEW_ORDER_SINGLE_MESSAGE, buffers[i], 0, orders[i].size, i + 1L) == Action.CONTINUE)
                hotSends.doWork()
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

    @Test
    fun `a 35=H answered from the store allocates nothing`() {
        order(1)
        acknowledge("ORD-1", status = '1')
        store.findByFix("ORD-1")!!.ambiguous = true // the longest answer: §3.4's Text
        val request = fix("H", 2, listOf(11 to "ORD-1", 790 to "Q-1", 55 to "ACME", 54 to "1"))
        val buffer = UnsafeBuffer(request)
        fun run(times: Int) = repeat(times) {
            check(inbound.onMessage(P, NoReplies, 0, Constants.ORDER_STATUS_REQUEST_MESSAGE, buffer, 0, request.size, 0L) == Action.CONTINUE)
        }
        run(20_000)
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val windows = (0 until 5).map {
            val before = threads.currentThreadAllocatedBytes
            run(2_000)
            threads.currentThreadAllocatedBytes - before
        }
        assertTrue(0L in windows, "bytes allocated per window of 2,000 35=H answered: $windows")
    }

    // ================================================================ models

    private class SentOrder(
        val participantId: Long, val clOrdId: String, val securityId: Int, val side: Side, val price: Long, val qty: Long,
        val expireDate: Int, val smpId: Long, val smpStrategy: SmpStrategy, val result: SendResult, val storedBeforeSend: Boolean,
    )

    private class SentCancel(
        val participantId: Long, val clOrdId: String, val origClOrdId: String, val exchangeOrderId: Long, val securityId: Int,
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
            participantId: Long, clOrdId: DirectBuffer, securityId: Int, side: Side, price: Long, qty: Long,
            expireDate: Int, smpId: Long, smpStrategy: SmpStrategy,
        ): SendResult {
            val result = results.removeFirstOrNull() ?: SendResult.SENT
            val stored = store.findByClOrdIdField(participantId, clOrdId, 0) != null
            orders += SentOrder(participantId, ClOrdId.toString(clOrdId, 0), securityId, side, price, qty, expireDate, smpId, smpStrategy, result, stored)
            return result
        }

        override fun cancel(participantId: Long, clOrdId: DirectBuffer, origClOrdId: DirectBuffer, exchangeOrderId: Long, securityId: Int, side: Side): SendResult {
            val result = results.removeFirstOrNull() ?: SendResult.SENT
            val stored = store.findByClOrdIdField(participantId, clOrdId, 0) != null
            cancels += SentCancel(
                participantId, ClOrdId.toString(clOrdId, 0), ClOrdId.toString(origClOrdId, 0), exchangeOrderId, securityId, side, result, stored,
            )
            return result
        }
    }

    private class QuietVenue : OrderVenue {
        var sent = 0
        override fun shardOf(securityId: Int) = 0
        override fun newOrder(
            participantId: Long, clOrdId: DirectBuffer, securityId: Int, side: Side, price: Long, qty: Long,
            expireDate: Int, smpId: Long, smpStrategy: SmpStrategy,
        ): SendResult = SendResult.SENT.also { sent++ }

        override fun cancel(participantId: Long, clOrdId: DirectBuffer, origClOrdId: DirectBuffer, exchangeOrderId: Long, securityId: Int, side: Side) =
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
