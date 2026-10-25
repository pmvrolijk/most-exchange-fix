package nl.lamia.most.exchange.fixadapter.orderentry.store

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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The order store of Design.md §6.1, and the `sequenceIndex` guard of §3.2. */
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
        participantId: Long, clOrdId: Long, fixClOrdId: String, entered: Int = TODAY,
        fill: RequestRow.() -> Unit = {},
    ): RequestRow = newRow().apply {
        kind = RowKind.ORDER
        this.participantId = participantId
        this.clOrdId = clOrdId
        fixClOrdId(fixClOrdId)
        entryTradingDate = entered
        securityId = 42
        side = Side.BUY
        price = 100_25000000L
        origQty = 10_00000000L
        leavesQty = 10_00000000L
        timeInForce = '0'.code.toByte()
        expireDate = entered
        ordStatus = 'A'.code.toByte()
        fill()
    }

    private fun OrderStore.cancel(
        participantId: Long, clOrdId: Long, fixClOrdId: String, origClOrdId: Long, entered: Int = TODAY,
        fill: RequestRow.() -> Unit = {},
    ): RequestRow = newRow().apply {
        kind = RowKind.CANCEL
        this.participantId = participantId
        this.clOrdId = clOrdId
        fixClOrdId(fixClOrdId)
        entryTradingDate = entered
        this.origClOrdId = origClOrdId
        cancelState = CancelState.SENT
        fill()
    }

    private fun OrderStore.findByFix(participantId: Long, fixClOrdId: String): RequestRow? {
        val bytes = fixClOrdId.toByteArray(Charsets.US_ASCII)
        return findByFixClOrdId(participantId, UnsafeBuffer(bytes), 0, bytes.size)
    }

    // ---------------------------------------------------------------- a row survives a restart

    @Test
    fun `an order's row survives a restart with every field of §6_1`() {
        val s = open()
        val row = s.order(P1, id(0, 7), "ORD-7") {
            acknowledged = true
            exchangeOrderId = 9_000_000_123L
            side = Side.SELL
            securityId = 77
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

        val back = assertNotNull(restart().find(P1, id(0, 7)))
        assertEquals(RowKind.ORDER, back.kind)
        assertEquals("ORD-7", back.fixClOrdIdAsString())
        assertEquals(TODAY, back.entryTradingDate)
        assertTrue(back.acknowledged)
        assertEquals(9_000_000_123L, back.exchangeOrderId)
        assertEquals(Side.SELL, back.side)
        assertEquals(77, back.securityId)
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
    fun `a cancel has its own row, pointing at its order, and it survives a restart`() {
        val s = open()
        s.insert(s.order(P1, id(0, 7), "ORD-7"))
        s.insert(s.cancel(P1, id(0, 8), "CXL-8", origClOrdId = id(0, 7)) { cancelState = CancelState.PARKED })

        val back = assertNotNull(restart().find(P1, id(0, 8)))
        assertEquals(RowKind.CANCEL, back.kind)
        assertEquals("CXL-8", back.fixClOrdIdAsString())
        assertEquals(id(0, 7), back.origClOrdId)
        assertEquals(CancelState.PARKED, back.cancelState, "a parked cancel survives a restart (§3.3)")
        assertNotNull(store!!.find(P1, id(0, 7)))
    }

    @Test
    fun `an update is written through and survives a restart`() {
        val s = open()
        val row = s.order(P1, id(0, 7), "ORD-7")
        s.insert(row)
        row.acknowledged = true
        row.exchangeOrderId = 55L
        row.ordStatus = '0'.code.toByte()
        row.lastReportSeq = 3L
        s.update(row)

        val back = assertNotNull(restart().find(P1, id(0, 7)))
        assertEquals(55L, back.exchangeOrderId)
        assertEquals('0'.code.toByte(), back.ordStatus)
        assertEquals(3L, back.lastReportSeq)
    }

    // ---------------------------------------------------------------- lookups and insert outcomes

    @Test
    fun `a request is found by its clOrdId and by its FIX ClOrdID, within its participant`() {
        val s = open()
        val row = s.order(P1, id(0, 7), "ORD-7")
        s.insert(row)
        assertSame(row, s.find(P1, id(0, 7)))
        assertSame(row, s.findByFix(P1, "ORD-7"))
        assertNull(s.find(P2, id(0, 7)), "another participant's clOrdId space")
        assertNull(s.findByFix(P2, "ORD-7"), "another participant's ClOrdID space")
        assertNull(s.findByFix(P1, "ORD-70"))
        assertNull(s.findByFix(P1, "ORD-"))
    }

    @Test
    fun `a hash collision is never a false duplicate`() {
        // Every ClOrdID hashes alike, so every lookup walks one chain and must compare bytes.
        val s = open(hasher = { _, _, _ -> 17L })
        val a = s.order(P1, id(0, 1), "A")
        val b = s.order(P1, id(0, 2), "B")
        val c = s.cancel(P1, id(0, 3), "C", origClOrdId = id(0, 1))
        assertEquals(InsertResult.NEW, s.insert(a))
        assertEquals(InsertResult.NEW, s.insert(b))
        assertEquals(InsertResult.NEW, s.insert(c))
        assertSame(a, s.findByFix(P1, "A"))
        assertSame(b, s.findByFix(P1, "B"))
        assertSame(c, s.findByFix(P1, "C"))
        assertNull(s.findByFix(P1, "D"))

        val again = restart(hasher = { _, _, _ -> 17L })
        assertEquals(id(0, 2), again.findByFix(P1, "B")?.clOrdId)
    }

    @Test
    fun `inserting the very request again is 'same', and leaves the row as it is`() {
        val s = open()
        val row = s.order(P1, id(0, 7), "ORD-7")
        s.insert(row)
        row.lastReportSeq = 9L
        s.update(row)

        val redelivered = s.order(P1, id(0, 7), "ORD-7")
        assertEquals(InsertResult.SAME, s.insert(redelivered), "back-pressure redelivery or a PossDup resend (§6.1)")
        s.release(redelivered)
        assertSame(row, s.find(P1, id(0, 7)))
        assertEquals(9L, restart().find(P1, id(0, 7))?.lastReportSeq, "not overwritten by the second insert")
    }

    @Test
    fun `a ClOrdID another row holds is a duplicate, across orders and cancels, and nothing is written`() {
        val s = open()
        s.insert(s.order(P1, id(0, 7), "ORD-7"))
        assertEquals(InsertResult.DUPLICATE_CLORDID, s.insert(s.order(P1, id(0, 8), "ORD-7")))
        assertEquals(
            InsertResult.DUPLICATE_CLORDID,
            s.insert(s.cancel(P1, id(0, 9), "ORD-7", origClOrdId = id(0, 7))),
            "cancels share the session's ClOrdID namespace (§6.1)",
        )
        assertEquals(InsertResult.NEW, s.insert(s.order(P2, id(0, 8), "ORD-7")), "another participant's namespace")

        val again = restart()
        assertNull(again.find(P1, id(0, 8)))
        assertNull(again.find(P1, id(0, 9)))
        assertEquals(id(0, 7), again.findByFix(P1, "ORD-7")?.clOrdId)
    }

    @Test
    fun `a clOrdId held by another ClOrdID is an id collision, and nothing is written`() {
        val s = open()
        s.insert(s.order(P1, id(0, 1), "ORD-1"))
        // Artio's state wiped at sequenceIndex 0, the client starting again from 1 (§3.2, §8.15).
        assertEquals(InsertResult.ID_COLLISION, s.insert(s.order(P1, id(0, 1), "NEW-1")))
        val again = restart()
        assertEquals("ORD-1", again.find(P1, id(0, 1))?.fixClOrdIdAsString())
        assertNull(again.findByFix(P1, "NEW-1"))
    }

    // ---------------------------------------------------------------- retention

    @Test
    fun `retention drops only rows entered before today, finished, owing nothing, and past every resend`() {
        val s = open(tradingDate = YESTERDAY)
        s.saveFirstMissing(P1, 100L)
        val kept = mutableListOf<String>()
        val dropped = mutableListOf<String>()
        var seq = 1L
        fun put(row: RequestRow, expectKept: Boolean) {
            s.insert(row)
            (if (expectKept) kept else dropped) += row.fixClOrdIdAsString()
        }
        fun acked(status: Char, name: String, fill: RequestRow.() -> Unit = {}) =
            s.order(P1, seq++, name, entered = YESTERDAY) {
                acknowledged = true; ordStatus = status.code.toByte(); lastReportSeq = 50L; fill()
            }

        put(acked('2', "filled"), expectKept = false)
        put(acked('4', "canceled"), expectKept = false)
        put(acked('C', "expired"), expectKept = false)
        put(acked('8', "rejected"), expectKept = false)
        put(s.cancel(P1, seq++, "cxl-answered", origClOrdId = 1L, entered = YESTERDAY) {
            cancelState = CancelState.ANSWERED; lastReportSeq = 51L
        }, expectKept = false)

        put(acked('2', "filled-today") { entryTradingDate = TODAY }, expectKept = true)
        put(acked('0', "open-gtc") { timeInForce = '1'.code.toByte() }, expectKept = true)
        put(acked('1', "partly-filled"), expectKept = true)
        put(acked('A', "pending-new"), expectKept = true)
        put(acked('1', "ambiguous") { ambiguous = true }, expectKept = true)
        put(acked('8', "owed") { owed = OrderFate.NEVER_SEQUENCED }, expectKept = true)
        put(acked('2', "report-resendable") { lastReportSeq = 100L }, expectKept = true)
        put(s.cancel(P1, seq++, "cxl-sent", origClOrdId = 1L, entered = YESTERDAY), expectKept = true)
        put(s.cancel(P1, seq++, "cxl-parked", origClOrdId = 1L, entered = YESTERDAY) {
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
        s.saveFirstMissing(P1, 100L)
        s.saveFirstMissing(P2, 10L)
        for (p in longArrayOf(P1, P2)) {
            s.insert(s.order(p, 1L, "filled", entered = YESTERDAY) {
                acknowledged = true; ordStatus = '2'.code.toByte(); lastReportSeq = 50L
            })
        }
        val today = restart(TODAY)
        assertNull(today.findByFix(P1, "filled"), "50 < P1's 100")
        assertNotNull(today.findByFix(P2, "filled"), "50 ≥ P2's 10: a resend can still bring its report")
    }

    // ---------------------------------------------------------------- requests in flight at startup

    @Test
    fun `the requests in flight at startup are orders not acknowledged and cancels sent, unless settled`() {
        val s = open()
        s.insert(s.order(P1, 1L, "unacked"))
        s.insert(s.order(P1, 2L, "acked") { acknowledged = true; ordStatus = '0'.code.toByte() })
        s.insert(s.order(P2, 3L, "unacked-p2"))
        s.insert(s.cancel(P1, 4L, "cxl-sent", origClOrdId = 2L))
        s.insert(s.cancel(P1, 5L, "cxl-parked", origClOrdId = 1L) { cancelState = CancelState.PARKED })
        s.insert(s.cancel(P1, 6L, "cxl-answered", origClOrdId = 2L) { cancelState = CancelState.ANSWERED })
        // Settled by the SDK before the restart (§3.4, §6.2): handing them back could settle them
        // again, an AMBIGUOUS order as NEVER_SEQUENCED.
        s.insert(s.order(P1, 7L, "fate-owed") { owed = OrderFate.AMBIGUOUS })
        s.insert(s.order(P1, 8L, "rejected-not-sequenced") { ordStatus = '8'.code.toByte() })
        s.insert(s.order(P1, 9L, "ambiguous-unacked") { ambiguous = true })
        s.insert(s.cancel(P1, 10L, "cxl-fate-owed", origClOrdId = 2L) { owed = OrderFate.NEVER_SEQUENCED })

        val inFlight = mutableSetOf<Pair<Long, Long>>()
        restart().forEachInFlight { row -> inFlight += row.participantId to row.clOrdId }
        assertEquals(setOf(P1 to 1L, P2 to 3L, P1 to 4L), inFlight)
    }

    // ---------------------------------------------------------------- meta: first missing, sequenceIndex

    @Test
    fun `the durable first-missing reportSeq is 1 until saved, then survives a restart, per participant`() {
        val s = open()
        assertEquals(1L, s.firstMissing(P1))
        s.saveFirstMissing(P1, 42L)
        val again = restart()
        assertEquals(42L, again.firstMissing(P1))
        assertEquals(1L, again.firstMissing(P2))
    }

    @Test
    fun `the adapter refuses to start below the highest sequenceIndex stored for the participant`() {
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

    // ---------------------------------------------------------------- refusals at load

    @Test
    fun `a row of an unknown version is refused, and the store does not load`() {
        val s = open()
        val row = s.order(P1, 1L, "ORD-1")
        s.insert(row)
        s.corruptVersionForTest(row, 99)
        s.close()
        store = null
        val refusal = assertFailsWith<IllegalStateException> { OrderStore.open(dir).use { it.load(TODAY) } }
        assertTrue("version" in refusal.message.orEmpty(), refusal.message)
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
        s.insert(s.order(P1, 1L, "FIRST-0001"))
        s.insert(s.order(P1, 2L, "MIDDLE-0002"))
        val filler = "F".repeat(1000)
        for (i in 0 until 100) s.insert(s.order(P1, 100L + i, "$filler-$i"))
        s.insert(s.order(P1, 3L, "LAST-0003"))
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
        val row = s.order(P1, id(0, 7), "ORD-7")
        s.insert(row)
        val name = UnsafeBuffer("ORD-7".toByteArray(Charsets.US_ASCII))
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        fun cycle(n: Int) {
            repeat(n) {
                check(s.find(P1, id(0, 7)) === row)
                check(s.findByFixClOrdId(P1, name, 0, 5) === row)
                row.lastReportSeq = it.toLong()
                s.update(row)
            }
        }
        cycle(20_000) // warm up: JIT, and the maps' first growth
        val before = threads.currentThreadAllocatedBytes
        cycle(10_000)
        val allocated = threads.currentThreadAllocatedBytes - before
        assertEquals(0L, allocated, "bytes allocated by 10,000 lookups and updates")
    }

    private fun ByteArray.indexOf(needle: ByteArray): Int? =
        (0..size - needle.size).firstOrNull { i -> needle.indices.all { this[i + it] == needle[it] } }

    private companion object {
        const val P1 = 1001L
        const val P2 = 1002L
        const val TODAY = 20261026
        const val YESTERDAY = 20261023

        /** The derived `clOrdId` of Design.md §3.2. */
        fun id(sequenceIndex: Int, msgSeqNum: Int) = (sequenceIndex.toLong() shl 32) or msgSeqNum.toLong()
    }
}
