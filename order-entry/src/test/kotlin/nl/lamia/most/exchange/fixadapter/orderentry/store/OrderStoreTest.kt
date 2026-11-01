package nl.lamia.most.exchange.fixadapter.orderentry.store

import nl.lamia.most.exchange.client.ClOrdId
import nl.lamia.most.exchange.client.OrderFate
import nl.lamia.most.exchange.sbe.Side
import org.agrona.concurrent.UnsafeBuffer
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The order store of Design.md §6.1, keyed by the client's ClOrdID since schema 8 (§3.2, §8.30), and
 * the position, epoch and `sequenceIndex` of §3.2.
 */
class OrderStoreTest {

    @TempDir
    lateinit var dir: File

    private var store: OrderStore? = null

    private fun open(hasher: ClOrdIdHasher = ClOrdIdHasher.DEFAULT, tradingDate: Int = TODAY): OrderStore {
        store?.close()
        return OrderStore.open(dir, hasher).also { it.load(tradingDate); store = it }
    }

    /** A clean close, then the same directory opened again, as an adapter restart does. */
    private fun restart(tradingDate: Int = TODAY, hasher: ClOrdIdHasher = ClOrdIdHasher.DEFAULT) = open(hasher, tradingDate)

    @AfterTest
    fun close() {
        store?.close()
    }

    private fun OrderStore.order(
        participantId: Long, position: Long, fixClOrdId: String, entered: Int = TODAY,
        fill: RequestRow.() -> Unit = {},
    ): RequestRow = newRow().apply {
        kind = RowKind.ORDER
        this.participantId = participantId
        this.position = position
        fixClOrdId(fixClOrdId)
        entryTradingDate = entered
        securityId = 42
        shardId = 0
        side = Side.BUY
        price = 100_25000000L
        origQty = 10_00000000L
        leavesQty = 10_00000000L
        timeInForce = '0'.code.toByte()
        expireDate = entered
        ordStatus = 'A'.code.toByte()
        fill()
    }

    /** A cancel of the order whose ClOrdID is [orig], which need not be stored. */
    private fun OrderStore.cancel(
        participantId: Long, position: Long, fixClOrdId: String, orig: String, entered: Int = TODAY,
        fill: RequestRow.() -> Unit = {},
    ): RequestRow = newRow().apply {
        kind = RowKind.CANCEL
        this.participantId = participantId
        this.position = position
        fixClOrdId(fixClOrdId)
        entryTradingDate = entered
        shardId = 0
        origClOrdId(orig)
        cancelState = CancelState.SENT
        fill()
    }

    private fun OrderStore.findByFix(participantId: Long, fixClOrdId: String): RequestRow? {
        val bytes = fixClOrdId.toByteArray(Charsets.US_ASCII)
        return findByFixClOrdId(participantId, UnsafeBuffer(bytes), 0, bytes.size)
    }

    /** As a report carries it: `char[20]`, NUL-padded, here at offset 4 of a larger buffer. */
    private fun OrderStore.findReported(participantId: Long, clOrdId: String): RequestRow? {
        val buffer = UnsafeBuffer(ByteArray(32))
        ClOrdId.of(clOrdId).writeTo(buffer, 4)
        return findByClOrdIdField(participantId, buffer, 4)
    }

    // ---------------------------------------------------------------- a row survives a restart

    @Test
    fun `an order's row survives a restart with every field of §6_1`() {
        val s = open()
        val row = s.order(P1, pos(0, 7), "ORD-7") {
            acknowledged = true
            exchangeOrderId = 9_000_000_123L
            side = Side.SELL
            securityId = 77
            shardId = 3
            price = 99_12345678L
            origQty = 5_00000000L
            cumQty = 2_00000000L
            leavesQty = 3_00000000L
            notionalHi = 1L
            notionalLo = -2L
            timeInForce = '6'.code.toByte()
            expireDate = 20261231
            smpId = 1234L
            smpStrategy = 2
            ordStatus = '1'.code.toByte()
            lastReportSeq = 815L
            ambiguous = true
            owed = OrderFate.AMBIGUOUS
        }
        assertEquals(InsertResult.NEW, s.insert(row))

        val back = assertNotNull(restart().findByFix(P1, "ORD-7"))
        assertEquals(RowKind.ORDER, back.kind)
        assertEquals("ORD-7", back.fixClOrdIdAsString())
        assertEquals(pos(0, 7), back.position, "the position of §3.2, kept on the row")
        assertEquals(0, back.epoch)
        assertEquals(TODAY, back.entryTradingDate)
        assertTrue(back.acknowledged)
        assertEquals(9_000_000_123L, back.exchangeOrderId)
        assertEquals(Side.SELL, back.side)
        assertEquals(77, back.securityId)
        assertEquals(3, back.shardId)
        assertEquals(99_12345678L, back.price)
        assertEquals(5_00000000L, back.origQty)
        assertEquals(2_00000000L, back.cumQty)
        assertEquals(3_00000000L, back.leavesQty)
        assertEquals(1L, back.notionalHi)
        assertEquals(-2L, back.notionalLo, "the low word is unsigned: every bit survives")
        assertEquals('6'.code.toByte(), back.timeInForce)
        assertEquals(20261231, back.expireDate)
        assertEquals(1234L, back.smpId)
        assertEquals(2.toByte(), back.smpStrategy)
        assertEquals('1'.code.toByte(), back.ordStatus)
        assertEquals(815L, back.lastReportSeq)
        assertTrue(back.ambiguous)
        assertEquals(OrderFate.AMBIGUOUS, back.owed)
    }

    @Test
    fun `a ClOrdID of every allowed character and the full 20 survives a restart, byte for byte`() {
        // Design.md §3.2: 1 to 20 of A-Z a-z 0-9 - _ + ; : . /, the row's key since schema 8.
        val longest = "Az09-_+;:./abcdeXYZ9"
        val s = open()
        s.insert(s.order(P1, pos(0, 1), longest))
        s.insert(s.order(P1, pos(0, 2), "A"))
        val again = restart()
        assertEquals(longest, again.findByFix(P1, longest)?.fixClOrdIdAsString())
        assertEquals(20, again.findByFix(P1, longest)?.fixClOrdIdLength)
        assertEquals("A", again.findByFix(P1, "A")?.fixClOrdIdAsString())
        assertEquals(1, again.findByFix(P1, "A")?.fixClOrdIdLength)
    }

    @Test
    fun `a row takes no ClOrdID the exchange cannot carry`() {
        // The inbound path refuses one first (§3.3); the store's key has room for 20 bytes only.
        val row = RequestRow()
        assertFailsWith<IllegalArgumentException> { row.fixClOrdId("X".repeat(21)) }
        assertFailsWith<IllegalArgumentException> { row.fixClOrdId("") }
    }

    @Test
    fun `a cancel has its own row, pointing at its order by ClOrdID, and it survives a restart`() {
        val s = open()
        s.insert(s.order(P1, pos(0, 7), "ORD-7"))
        s.insert(s.cancel(P1, pos(0, 8), "CXL-8", orig = "ORD-7") { cancelState = CancelState.PARKED })

        val again = restart()
        val back = assertNotNull(again.findByFix(P1, "CXL-8"))
        assertEquals(RowKind.CANCEL, back.kind)
        assertEquals("CXL-8", back.fixClOrdIdAsString())
        assertEquals("ORD-7", back.origClOrdIdAsString())
        assertEquals(CancelState.PARKED, back.cancelState, "a parked cancel survives a restart (§3.3)")
        assertSame(again.findByFix(P1, "ORD-7"), again.orderOf(back))
    }

    @Test
    fun `an update is written through and survives a restart`() {
        val s = open()
        val row = s.order(P1, pos(0, 7), "ORD-7")
        s.insert(row)
        row.acknowledged = true
        row.exchangeOrderId = 55L
        row.ordStatus = '0'.code.toByte()
        row.lastReportSeq = 3L
        s.update(row)

        val back = assertNotNull(restart().findByFix(P1, "ORD-7"))
        assertEquals(55L, back.exchangeOrderId)
        assertEquals('0'.code.toByte(), back.ordStatus)
        assertEquals(3L, back.lastReportSeq)
    }

    // ---------------------------------------------------------------- lookups and insert outcomes

    @Test
    fun `a request is found by its ClOrdID, as a FIX message, a report and the SDK hold it, within its participant`() {
        // §6.1 "In memory": one key since schema 8, read from either kind of bytes.
        val s = open()
        val row = s.order(P1, pos(0, 7), "ORD-7")
        s.insert(row)
        assertSame(row, s.findByFix(P1, "ORD-7"))
        assertSame(row, s.findReported(P1, "ORD-7"))
        assertSame(row, s.find(P1, ClOrdId.of("ORD-7")))
        assertNull(s.findByFix(P2, "ORD-7"), "another participant's ClOrdID space")
        assertNull(s.findReported(P2, "ORD-7"))
        assertNull(s.find(P2, ClOrdId.of("ORD-7")))
        assertNull(s.findByFix(P1, "ORD-70"))
        assertNull(s.findByFix(P1, "ORD-"))
        assertNull(s.findByFix(P1, "ORD-7\u0000"), "a NUL is no character of an id, so no padding matches it")
        assertNull(s.findByFix(P1, "ORD-7".padEnd(21, 'x')), "too long to be any row's")
        assertNull(s.findByFix(P1, ""))
    }

    @Test
    fun `a hash collision is never a false duplicate`() {
        // Every ClOrdID hashes alike, so every lookup walks one chain and must compare the words.
        val s = open(hasher = { _, _, _ -> 17L })
        val a = s.order(P1, pos(0, 1), "A")
        val b = s.order(P1, pos(0, 2), "B")
        val c = s.cancel(P1, pos(0, 3), "C", orig = "A")
        assertEquals(InsertResult.NEW, s.insert(a))
        assertEquals(InsertResult.NEW, s.insert(b))
        assertEquals(InsertResult.NEW, s.insert(c))
        assertSame(a, s.findByFix(P1, "A"))
        assertSame(b, s.findReported(P1, "B"))
        assertSame(c, s.find(P1, ClOrdId.of("C")))
        assertSame(a, s.orderOf(c))
        assertNull(s.findByFix(P1, "D"))

        val again = restart(hasher = { _, _, _ -> 17L })
        assertEquals(pos(0, 2), again.findByFix(P1, "B")?.position)
    }

    @Test
    fun `inserting the very request again is 'same', and leaves the row as it is`() {
        // §6.1: the ClOrdID held at the same position and epoch, a redelivery, a PossDup resend or a replay.
        val s = open()
        val row = s.order(P1, pos(0, 7), "ORD-7")
        s.insert(row)
        row.lastReportSeq = 9L
        s.update(row)

        val redelivered = s.order(P1, pos(0, 7), "ORD-7")
        assertEquals(InsertResult.SAME, s.insert(redelivered))
        s.release(redelivered)
        assertSame(row, s.findByFix(P1, "ORD-7"))
        assertEquals(9L, restart().findByFix(P1, "ORD-7")?.lastReportSeq, "not overwritten by the second insert")
    }

    @Test
    fun `a ClOrdID another request holds is a duplicate, across orders and cancels, and nothing is written`() {
        val s = open()
        s.insert(s.order(P1, pos(0, 7), "ORD-7"))
        assertEquals(InsertResult.DUPLICATE_CLORDID, s.insert(s.order(P1, pos(0, 8), "ORD-7")))
        assertEquals(
            InsertResult.DUPLICATE_CLORDID,
            s.insert(s.cancel(P1, pos(0, 9), "ORD-7", orig = "ORD-7")),
            "cancels share the session's ClOrdID namespace (§6.1)",
        )
        assertEquals(InsertResult.NEW, s.insert(s.order(P2, pos(0, 8), "ORD-7")), "another participant's namespace")

        val again = restart()
        assertEquals(pos(0, 7), again.findByFix(P1, "ORD-7")?.position)
        assertEquals(2, again.size)
    }

    @Test
    fun `a position another ClOrdID holds is a new request, never a collision`() {
        // §8.15 superseded by schema 8 (R§17): after Artio's state is lost, MsgSeqNum 1 comes again.
        // Keyed by the ClOrdID, both rows are held, and nothing blocks the session.
        val s = open()
        s.insert(s.order(P1, pos(0, 1), "ORD-1"))
        assertEquals(InsertResult.NEW, s.insert(s.order(P1, pos(0, 1), "NEW-1")))
        val again = restart()
        assertEquals("ORD-1", again.findByFix(P1, "ORD-1")?.fixClOrdIdAsString())
        assertEquals("NEW-1", again.findByFix(P1, "NEW-1")?.fixClOrdIdAsString())
    }

    // ---------------------------------------------------------------- retention

    @Test
    fun `retention drops only rows entered before today, finished, owing nothing, and past every resend`() {
        val s = open(tradingDate = YESTERDAY)
        s.saveFirstMissing(P1, 0, 100L)
        val kept = mutableListOf<String>()
        val dropped = mutableListOf<String>()
        var seq = 1
        fun put(row: RequestRow, expectKept: Boolean) {
            s.insert(row)
            (if (expectKept) kept else dropped) += row.fixClOrdIdAsString()
        }
        fun acked(status: Char, name: String, fill: RequestRow.() -> Unit = {}) =
            s.order(P1, pos(0, seq++), name, entered = YESTERDAY) {
                acknowledged = true; ordStatus = status.code.toByte(); lastReportSeq = 50L; fill()
            }

        put(acked('2', "filled"), expectKept = false)
        put(acked('4', "canceled"), expectKept = false)
        put(acked('C', "expired"), expectKept = false)
        put(acked('8', "rejected"), expectKept = false)
        put(s.cancel(P1, pos(0, seq++), "cxl-answered", orig = "filled", entered = YESTERDAY) {
            cancelState = CancelState.ANSWERED; lastReportSeq = 51L
        }, expectKept = false)

        put(acked('2', "filled-today") { entryTradingDate = TODAY }, expectKept = true)
        put(acked('0', "open-gtc") { timeInForce = '1'.code.toByte() }, expectKept = true)
        put(acked('1', "partly-filled"), expectKept = true)
        put(acked('A', "pending-new"), expectKept = true)
        put(acked('1', "ambiguous") { ambiguous = true }, expectKept = true)
        put(acked('8', "owed") { owed = OrderFate.NEVER_SEQUENCED }, expectKept = true)
        put(acked('2', "report-resendable") { lastReportSeq = 100L }, expectKept = true)
        put(s.cancel(P1, pos(0, seq++), "cxl-sent", orig = "open-gtc", entered = YESTERDAY), expectKept = true)
        put(s.cancel(P1, pos(0, seq++), "cxl-parked", orig = "pending-new", entered = YESTERDAY) {
            cancelState = CancelState.PARKED
        }, expectKept = true)

        val today = restart(TODAY)
        for (name in kept) assertNotNull(today.findByFix(P1, name), "$name is kept")
        for (name in dropped) assertNull(today.findByFix(P1, name), "$name is dropped")

        // Dropped from RocksDB too, not just from memory.
        val yesterdayAgain = restart(YESTERDAY)
        for (name in dropped) assertNull(yesterdayAgain.findByFix(P1, name), "$name stays dropped")
        assertEquals(kept.size, yesterdayAgain.size)
    }

    @Test
    fun `retention reads each participant's own first-missing reportSeq`() {
        val s = open(tradingDate = YESTERDAY)
        s.saveFirstMissing(P1, 0, 100L)
        s.saveFirstMissing(P2, 0, 10L)
        for (p in longArrayOf(P1, P2)) {
            s.insert(s.order(p, pos(0, 1), "filled", entered = YESTERDAY) {
                acknowledged = true; ordStatus = '2'.code.toByte(); lastReportSeq = 50L
            })
        }
        val today = restart(TODAY)
        assertNull(today.findByFix(P1, "filled"), "50 < P1's 100")
        assertNotNull(today.findByFix(P2, "filled"), "50 ≥ P2's 10: a resend can still bring its report")
    }

    @Test
    fun `retention compares a row with its own shard's first-missing reportSeq`() {
        // Design.md §6.1: each shard's engine numbers the participant's reports on its own, so a
        // report of a row on shard 1 can come again until shard 1's sequence has passed it.
        val s = open(tradingDate = YESTERDAY)
        s.saveFirstMissing(P1, 0, 100L)
        s.saveFirstMissing(P1, 1, 10L)
        for ((seq, shard) in listOf(1 to 0, 2 to 1)) {
            s.insert(s.order(P1, pos(0, seq), "filled-on-$shard", entered = YESTERDAY) {
                shardId = shard; acknowledged = true; ordStatus = '2'.code.toByte(); lastReportSeq = 50L
            })
        }
        val today = restart(TODAY)
        assertNull(today.findByFix(P1, "filled-on-0"), "50 < shard 0's 100")
        assertNotNull(today.findByFix(P1, "filled-on-1"), "50 ≥ shard 1's 10: a resend can still bring its report")
    }

    // ---------------------------------------------------------------- requests in flight at startup

    @Test
    fun `the requests in flight at startup are orders not acknowledged and cancels sent, unless settled`() {
        val s = open()
        s.insert(s.order(P1, pos(0, 1), "unacked"))
        s.insert(s.order(P1, pos(0, 2), "acked") { acknowledged = true; ordStatus = '0'.code.toByte() })
        s.insert(s.order(P2, pos(0, 3), "unacked-p2"))
        s.insert(s.cancel(P1, pos(0, 4), "cxl-sent", orig = "acked"))
        s.insert(s.cancel(P1, pos(0, 5), "cxl-parked", orig = "unacked") { cancelState = CancelState.PARKED })
        s.insert(s.cancel(P1, pos(0, 6), "cxl-answered", orig = "acked") { cancelState = CancelState.ANSWERED })
        // Settled by the SDK before the restart (§3.4, §6.2): handing them back could settle them
        // again, an AMBIGUOUS order as NEVER_SEQUENCED.
        s.insert(s.order(P1, pos(0, 7), "fate-owed") { owed = OrderFate.AMBIGUOUS })
        s.insert(s.order(P1, pos(0, 8), "rejected-unsequenced") { ordStatus = '8'.code.toByte() })
        s.insert(s.order(P1, pos(0, 9), "ambiguous-unacked") { ambiguous = true })
        s.insert(s.cancel(P1, pos(0, 10), "cxl-fate-owed", orig = "acked") { owed = OrderFate.NEVER_SEQUENCED })

        val inFlight = mutableSetOf<Pair<Long, String>>()
        restart().forEachInFlight { row -> inFlight += row.participantId to row.fixClOrdIdAsString() }
        assertEquals(setOf(P1 to "unacked", P2 to "unacked-p2", P1 to "cxl-sent"), inFlight)
    }

    @Test
    fun `after a restart an order links to its pending cancel, parked or sent, never an answered one`() {
        val s = open()
        s.insert(s.order(P1, pos(0, 1), "ord-parked"))
        s.insert(s.order(P1, pos(0, 2), "ord-sent") { acknowledged = true })
        s.insert(s.order(P1, pos(0, 3), "ord-answered") { acknowledged = true })
        s.insert(s.cancel(P1, pos(0, 11), "cxl-parked", orig = "ord-parked") { cancelState = CancelState.PARKED })
        s.insert(s.cancel(P1, pos(0, 12), "cxl-sent", orig = "ord-sent"))
        s.insert(s.cancel(P1, pos(0, 13), "cxl-answered", orig = "ord-answered") { cancelState = CancelState.ANSWERED })

        val again = restart()
        assertEquals("cxl-parked", again.findByFix(P1, "ord-parked")?.pendingCancel?.fixClOrdIdAsString())
        assertEquals("cxl-sent", again.findByFix(P1, "ord-sent")?.pendingCancel?.fixClOrdIdAsString())
        assertNull(again.findByFix(P1, "ord-answered")?.pendingCancel)
    }

    @Test
    fun `whether a send is still owed is this process's alone, and is not stored`() {
        val s = open()
        s.insert(s.order(P1, pos(0, 1), "ORD-1") { sendPending = true })
        assertTrue(s.findByFix(P1, "ORD-1")!!.sendPending)
        assertFalse(restart().findByFix(P1, "ORD-1")!!.sendPending, "after a restart a stored request may have been sent")
    }

    // ---------------------------------------------------------------- meta: first missing, sequenceIndex, epoch

    @Test
    fun `the durable first-missing reportSeq is 1 until saved, then survives a restart, per participant and shard`() {
        val s = open()
        assertEquals(1L, s.firstMissing(P1, 0))
        s.saveFirstMissing(P1, 0, 42L)
        s.saveFirstMissing(P1, 1, 7L)
        s.saveFirstMissing(P1, 0, 43L)
        val again = restart()
        assertEquals(43L, again.firstMissing(P1, 0))
        assertEquals(7L, again.firstMissing(P1, 1), "shard 1's own sequence")
        assertEquals(1L, again.firstMissing(P1, 2))
        assertEquals(1L, again.firstMissing(P2, 0))
    }

    @Test
    fun `the adapter refuses to start below the highest sequenceIndex stored for the participant`() {
        // Until Status §3 item 12 step 5 turns the refusal into a new epoch (Design.md §3.2).
        val s = open()
        s.recordSequenceIndex(P1, 3)
        s.recordSequenceIndex(P1, 2) // never lowers
        val again = restart()
        assertEquals(3, again.highestSequenceIndex(P1))
        val refusal = assertFailsWith<IllegalStateException> { again.requireSequenceIndex(P1, 2) }
        assertTrue("sequenceIndex" in refusal.message.orEmpty(), refusal.message)
        again.requireSequenceIndex(P1, 3)
        again.requireSequenceIndex(P1, 4)
        again.requireSequenceIndex(P2, 0)
    }

    @Test
    fun `the store holds state for a participant with a row, a sequenceIndex, a first-missing or a floor`() {
        // Design.md §3.2: what makes a session Artio does not know a loss of Artio's state.
        val s = open()
        s.recordSequenceIndex(P1, 0) // not stored: 0 is where every session starts
        s.findByFix(P1, "ORD-1")
        assertFalse(s.holdsState(P1))
        s.recordSequenceIndex(P1, 1)
        s.saveFirstMissing(P2, 1, 5L)
        assertEquals(InsertResult.NEW, s.insert(s.order(P4, pos(0, 7), "ORD-7")))
        s.handled(P6, pos(0, 3))
        val again = restart()
        for (p in listOf(P1, P2, P4, P6)) assertTrue(again.holdsState(p), "participant $p")
        assertFalse(again.holdsState(P5))
    }

    @Test
    fun `a new epoch starts the high-water, floor, sequenceIndex and receive gap again, keeps every row, and survives a restart`() {
        // Design.md §3.2: Artio's state lost, the adapter starts; positions compare within one epoch.
        val s = open()
        assertEquals(0, s.epoch(P1))
        s.insert(s.order(P1, pos(2, 9), "OLD-9"))
        s.handled(P1, pos(2, 12))
        s.recordSequenceIndex(P1, 2)
        s.saveReceiveGap(P1, sequenceIndex = 2, from = 13, to = 15)
        s.insert(s.order(P2, pos(0, 4), "OTHER-4"))

        s.beginEpoch(P1)
        assertEquals(1, s.epoch(P1))
        assertEquals(0L, s.highWater(P1), "the old positions are another Artio's")
        assertEquals(0, s.highestSequenceIndex(P1))
        assertEquals(0, s.receiveGapFrom(P1))
        assertEquals(pos(0, 1), s.replayFrom(P1), "the new Artio's archive, from its start")
        assertNotNull(s.findByFix(P1, "OLD-9"), "rows are kept: the duplicate check and every open order's state")
        assertEquals(pos(0, 5), s.replayFrom(P2), "another participant's epoch is its own")

        val row = s.order(P1, pos(0, 3), "NEW-3")
        s.insert(row)
        assertEquals(1, row.epoch, "a row takes its participant's epoch")
        assertEquals(pos(0, 3), s.highWater(P1))

        val again = restart()
        assertEquals(1, again.epoch(P1))
        assertEquals(pos(0, 3), again.highWater(P1), "OLD-9's higher position is of the earlier epoch")
        assertEquals(pos(0, 4), again.replayFrom(P1))
        assertEquals(0, again.receiveGapFrom(P1))
        assertEquals(0, again.findByFix(P1, "OLD-9")?.epoch)
        assertEquals(
            InsertResult.DUPLICATE_CLORDID, again.insert(again.order(P1, pos(2, 9), "OLD-9")),
            "the same position in another epoch is another request",
        )
    }

    // ---------------------------------------------------------------- recovery's high-water, §8.17

    @Test
    fun `the high-water is the highest position handled, a row held or a request answered with no row, and survives a restart`() {
        val s = open()
        assertEquals(0L, s.highWater(P1))
        s.insert(s.order(P1, pos(0, 3), "ORD-3"))
        s.insert(s.cancel(P1, pos(0, 5), "CXL-5", orig = "ORD-3"))
        assertEquals(pos(0, 5), s.highWater(P1), "a cancel's row counts as an order's does")
        s.handled(P1, pos(0, 4))
        assertEquals(pos(0, 5), s.highWater(P1), "never lowered")
        s.handled(P1, pos(0, 9))
        assertEquals(pos(0, 9), s.highWater(P1), "answered here with no row: a restart must not replay it")
        assertEquals(0L, s.highWater(P2), "per participant")
        assertEquals(pos(0, 9), restart().highWater(P1))
    }

    @Test
    fun `rows retention drops leave their highest position as the participant's floor, across restarts`() {
        val s = open(tradingDate = YESTERDAY)
        s.saveFirstMissing(P1, 0, 100L)
        for (seq in 1..3) {
            s.insert(s.order(P1, pos(0, seq), "OLD-$seq", entered = YESTERDAY) {
                acknowledged = true; ordStatus = '2'.code.toByte(); lastReportSeq = 50L
            })
        }
        val today = restart(TODAY)
        assertEquals(0, today.size, "all dropped")
        assertEquals(pos(0, 3), today.highWater(P1), "else the replay would bring the participant's whole history")
        assertEquals(pos(0, 3), restart(TODAY).highWater(P1), "kept in meta, the rows gone")
        assertEquals(pos(0, 4), restart(TODAY).replayFrom(P1))
    }

    @Test
    fun `the replay starts past the high-water, at no lower sequenceIndex than the stored one, and no later than an unresolved row`() {
        val s = open()
        assertEquals(pos(0, 1), s.replayFrom(P1), "nothing handled: from the start")
        s.insert(s.order(P1, pos(0, 7), "ORD-7"))
        assertEquals(pos(0, 8), s.replayFrom(P1))
        s.recordSequenceIndex(P1, 2)
        assertEquals(pos(2, 1), s.replayFrom(P1), "the stored index is written after every row of a lower one")

        s.insert(s.order(P2, pos(1, 5), "REC-5") { securityId = RequestRow.UNRESOLVED_SECURITY; shardId = RequestRow.NO_SHARD })
        s.insert(s.order(P2, pos(1, 9), "ORD-9"))
        assertTrue(s.findByFix(P2, "REC-5")!!.isUnresolved)
        assertEquals(pos(1, 5), restart().replayFrom(P2), "its message brought back, to resolve against the directory")
    }

    // ---------------------------------------------------------------- the receive gap, the clean stop

    @Test
    fun `an open receive gap survives a restart per participant with its sequenceIndex, and is gone once cleared`() {
        // Design.md §2, "A gap Artio leaves open": stored in meta while open, deleted when it closes.
        val s = open()
        assertEquals(0, s.receiveGapFrom(1001L), "none until saved")
        s.saveReceiveGap(1001L, sequenceIndex = 2, from = 3, to = 5)
        s.saveReceiveGap(1002L, sequenceIndex = 0, from = 7, to = 7)
        s.saveReceiveGap(1001L, sequenceIndex = 2, from = 3, to = 9) // grown
        val r = restart()
        assertEquals(Triple(2, 3, 9), Triple(r.receiveGapIndex(1001L), r.receiveGapFrom(1001L), r.receiveGapTo(1001L)))
        assertEquals(Triple(0, 7, 7), Triple(r.receiveGapIndex(1002L), r.receiveGapFrom(1002L), r.receiveGapTo(1002L)))
        r.clearReceiveGap(1001L)
        assertEquals(0, r.receiveGapFrom(1001L))
        val again = restart()
        assertEquals(0, again.receiveGapFrom(1001L), "the clear is written through")
        assertEquals(7, again.receiveGapFrom(1002L), "the other participant's is untouched")
    }

    @Test
    fun `a stop is clean only once marked after the close, and each start clears the mark durably`() {
        // Design §8.21: the adapter marks a clean stop after Artio's engine has closed; a start
        // without the mark is after a crash, and moves every session's MsgSeqNum on.
        assertFalse(open().stoppedCleanly, "a new store: no clean stop recorded")
        store?.close(); store = null
        OrderStore.markStoppedCleanly(dir)
        assertTrue(open().stoppedCleanly, "marked after the close")
        assertFalse(restart().stoppedCleanly, "the start cleared it: a close without the mark is a crash")
        store?.close(); store = null
        OrderStore.markStoppedCleanly(dir)
        val reopened = open()
        assertTrue(reopened.stoppedCleanly)
        assertEquals(0, reopened.size, "the mark is not a row")
        assertFalse(reopened.holdsState(0L), "nor any participant's state")
    }

    // ---------------------------------------------------------------- refusals at load

    @Test
    fun `a row of an unknown version is refused, and the store does not load`() {
        val s = open()
        val row = s.order(P1, pos(0, 1), "ORD-1")
        s.insert(row)
        s.corruptVersionForTest(row, 99)
        s.close()
        store = null
        val refusal = assertFailsWith<IllegalStateException> { OrderStore.open(dir).use { it.load(TODAY) } }
        assertTrue("version" in refusal.message.orEmpty(), refusal.message)
    }

    @Test
    fun `a store written before schema 8 is refused, and the refusal says the upgrade is a fresh start`() {
        // Design.md §6.1, §8.30: a v7 store has rows keyed by the derived clOrdId, and no format mark.
        val s = open()
        s.saveFirstMissing(P1, 0, 5L)
        s.forgetFormatForTest()
        s.close()
        store = null
        val refusal = assertFailsWith<IllegalStateException> { OrderStore.open(dir).use { it.load(TODAY) } }
        val message = refusal.message.orEmpty()
        assertTrue("schema 8" in message && "fresh start" in message, message)
    }

    @Test
    fun `a new store, or one holding only a clean stop, takes the format mark and loads again`() {
        store?.close(); store = null
        OrderStore.markStoppedCleanly(dir) // a store whose only entry is the mark
        open()
        assertEquals(0, restart().size, "marked at the first load, so a second load is not refused")
    }

    // ---------------------------------------------------------------- the WAL recovers a prefix

    @Test
    fun `after a torn WAL the store recovers a prefix of its writes, never a set with holes`() {
        // Rationale §4: the unsynced WAL is safe only because a crash leaves a prefix. Corrupting the
        // middle record of three stands in for a host crash that lost it; under point-in-time
        // recovery the third, written after it, must not come back.
        // RocksDB's log reader drops the rest of a 32 KiB block after a bad checksum in every mode,
        // so the last write is put blocks later: only point-in-time recovery stops before it.
        val s = open()
        s.insert(s.order(P1, pos(0, 1), "FIRST-0001"))
        s.insert(s.order(P1, pos(0, 2), "MIDDLE-0002"))
        for (i in 0 until 1_000) s.insert(s.order(P1, pos(0, 100 + i), "FILLER-$i"))
        s.insert(s.order(P1, pos(0, 3), "LAST-0003"))
        s.close()
        store = null

        val wal = dir.listFiles { f -> f.name.endsWith(".log") }.orEmpty().filter { it.length() > 0 }
        val marker = "MIDDLE-0002".toByteArray(Charsets.US_ASCII)
        val hit = wal.firstNotNullOfOrNull { file -> file.readBytes().indexOf(marker)?.let { file to it } }
        val (file, at) = assertNotNull(hit, "the rows are still only in the WAL: nothing was flushed")
        val bytes = file.readBytes()
        bytes[at] = (bytes[at].toInt() xor 0xff).toByte()
        file.writeBytes(bytes)

        val again = open()
        assertNotNull(again.findByFix(P1, "FIRST-0001"), "the prefix before the tear")
        assertNull(again.findByFix(P1, "MIDDLE-0002"), "the torn record")
        assertNull(again.findByFix(P1, "LAST-0003"), "a write after the tear is a hole if it comes back")
        assertEquals(1, again.size, "nothing after the tear, the filler included")
    }

    // ---------------------------------------------------------------- the library thread allocates nothing

    @Test
    fun `lookups and an update allocate nothing`() {
        val s = open()
        val row = s.order(P1, pos(0, 7), "ORD-7")
        s.insert(row)
        val cancel = s.cancel(P1, pos(0, 8), "CXL-8", orig = "ORD-7")
        s.insert(cancel)
        val name = UnsafeBuffer("ORD-7".toByteArray(Charsets.US_ASCII))
        val field = UnsafeBuffer(ByteArray(ClOrdId.LENGTH)).also { ClOrdId.of("ORD-7").writeTo(it, 0) }
        val id = ClOrdId.of("ORD-7")
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        fun cycle(n: Int) {
            repeat(n) {
                check(s.findByFixClOrdId(P1, name, 0, 5) === row)
                check(s.findByClOrdIdField(P1, field, 0) === row)
                check(s.find(P1, id) === row)
                check(s.orderOf(cancel) === row)
                row.lastReportSeq = it.toLong()
                s.update(row)
            }
        }
        cycle(20_000) // warm up: JIT, and the maps' first growth
        val before = threads.currentThreadAllocatedBytes
        cycle(10_000)
        val allocated = threads.currentThreadAllocatedBytes - before
        assertEquals(0L, allocated, "bytes allocated by 10,000 rounds of lookups and an update")
    }

    private fun ByteArray.indexOf(needle: ByteArray): Int? =
        (0..size - needle.size).firstOrNull { i -> needle.indices.all { this[i + it] == needle[it] } }

    private companion object {
        const val P1 = 1001L
        const val P2 = 1002L
        const val P4 = 1004L
        const val P5 = 1005L
        const val P6 = 1006L
        const val TODAY = 20261026
        const val YESTERDAY = 20261023

        /** A request's position (Design.md §3.2): `(sequenceIndex << 32) | MsgSeqNum` of its 35=D or 35=F. */
        fun pos(sequenceIndex: Int, msgSeqNum: Int) = (sequenceIndex.toLong() shl 32) or msgSeqNum.toLong()
    }
}
