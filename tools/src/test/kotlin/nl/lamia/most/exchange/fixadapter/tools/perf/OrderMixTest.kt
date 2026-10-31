package nl.lamia.most.exchange.fixadapter.tools.perf

import nl.lamia.most.exchange.fixadapter.tools.perf.OrderMix.Companion.BUY
import nl.lamia.most.exchange.fixadapter.tools.perf.OrderMix.Companion.SELL
import org.agrona.concurrent.UnsafeBuffer
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Design.md §10.1, "Order mix", and the log's ClOrdID and receive path. */
class OrderMixTest {
    private var next = 0

    /** Prepares and commits message [next]; returns what was decided. */
    private fun OrderMix.send(kind: Byte): Decided {
        prepare(kind)
        val d = Decided(next, this.kind, substituted, side, priceTicks, orderQty, target, targetQty)
        commit(next++)
        return d
    }

    private data class Decided(val index: Int, val kind: Byte, val substituted: Boolean, val side: Byte, val ticks: Int, val qty: Long, val target: Int, val targetQty: Long)

    @Test
    fun `passive orders alternate buy and sell, from 1 to levels ticks away from mid`() {
        val mix = OrderMix(16, levels = 2, qty = 10)
        val decided = List(5) { mix.send(Kind.PASSIVE) }
        assertEquals(listOf(BUY, SELL, BUY, SELL, BUY), decided.map { it.side })
        assertEquals(listOf(-1, 1, -2, 2, -1), decided.map { it.ticks })
        assertTrue(decided.all { it.qty == 10L && it.kind == Kind.PASSIVE && it.target == -1 })
        assertEquals(5, mix.restingCount)
    }

    @Test
    fun `a cross takes the best opposite order, price then time, a partial fill of it and then a fill`() {
        val mix = OrderMix(16, levels = 2, qty = 10)
        mix.send(Kind.PASSIVE) // #0 buy -1
        mix.send(Kind.PASSIVE) // #1 sell +1
        mix.send(Kind.PASSIVE) // #2 buy -2
        mix.send(Kind.PASSIVE) // #3 sell +2
        mix.send(Kind.PASSIVE) // #4 buy -1, behind #0 at the best bid

        val c1 = mix.send(Kind.CROSS) // the first cross is a buy: the best offer, #1 at +1
        assertEquals(Decided(5, Kind.CROSS, false, BUY, 1, 5, 1, 0), c1)
        val c2 = mix.send(Kind.CROSS) // a sell: the best bid, #0 (older than #4) at -1
        assertEquals(Decided(6, Kind.CROSS, false, SELL, -1, 5, 0, 0), c2)
        val c3 = mix.send(Kind.CROSS) // a buy: #1 again, its other half, which fills it
        assertEquals(Decided(7, Kind.CROSS, false, BUY, 1, 5, 1, 0), c3)
        val c4 = mix.send(Kind.CROSS) // a sell: #0's other half, which fills it
        assertEquals(Decided(8, Kind.CROSS, false, SELL, -1, 5, 0, 0), c4)
        val c5 = mix.send(Kind.CROSS) // a buy: #1 is gone, so the best offer is #3 at +2
        assertEquals(Decided(9, Kind.CROSS, false, BUY, 2, 5, 3, 0), c5)
        val c6 = mix.send(Kind.CROSS) // a sell: #0 is gone, the best bid is #4 at -1 now
        assertEquals(Decided(10, Kind.CROSS, false, SELL, -1, 5, 4, 0), c6)
    }

    @Test
    fun `an odd quantity is halved rounded up, then what remains`() {
        val mix = OrderMix(8, levels = 1, qty = 7)
        mix.send(Kind.PASSIVE) // #0 buy 7
        assertEquals(4L, mix.send(Kind.CROSS).qty) // a sell (nothing to buy from): 4 of 7
        assertEquals(3L, mix.send(Kind.CROSS).qty) // the 3 left
        assertEquals(0, mix.restingCount)
    }

    @Test
    fun `a cross with nothing opposite takes the other side, and with nothing at all is a passive`() {
        val mix = OrderMix(8, levels = 1, qty = 1)
        val empty = mix.send(Kind.CROSS)
        assertEquals(Kind.PASSIVE, empty.kind)
        assertTrue(empty.substituted)
        // #0 is a resting buy; the next cross would be a buy (no cross yet), finds no offer, and sells.
        val c = mix.send(Kind.CROSS)
        assertEquals(Decided(1, Kind.CROSS, false, SELL, -1, 1, 0, 0), c)
        assertEquals(0, mix.restingCount)
    }

    @Test
    fun `a cancel cancels the oldest resting order, at its original quantity, and with none is sent as a passive`() {
        val mix = OrderMix(16, levels = 1, qty = 4)
        mix.send(Kind.PASSIVE) // #0 buy
        mix.send(Kind.PASSIVE) // #1 sell
        mix.send(Kind.CROSS) // #2 buys 2 of #1
        assertEquals(Decided(3, Kind.CANCEL, false, BUY, -1, 4, 0, 4), mix.send(Kind.CANCEL))
        assertEquals(Decided(4, Kind.CANCEL, false, SELL, 1, 4, 1, 4), mix.send(Kind.CANCEL))
        val none = mix.send(Kind.CANCEL)
        assertEquals(Kind.PASSIVE, none.kind)
        assertTrue(none.substituted)
    }

    @Test
    fun `deciding and committing messages allocates nothing`() {
        val n = 100_000
        val kinds = byteArrayOf(Kind.PASSIVE, Kind.PASSIVE, Kind.CROSS, Kind.PASSIVE, Kind.CANCEL)
        // The same run five times, each on a new book. The first two pay the JIT's tier changes, a few
        // hundred bytes each whatever the run's length; from the third on, a run allocates 0 (seen 2026-10-31).
        var bytes = -1L
        repeat(5) {
            val mix = OrderMix(n, levels = 5, qty = 10)
            val before = threads.currentThreadAllocatedBytes
            for (i in 0 until n) {
                mix.prepare(kinds[i % kinds.size]); mix.commit(i)
            }
            bytes = threads.currentThreadAllocatedBytes - before
        }
        assertEquals(0L, bytes, "bytes allocated by $n messages decided and committed")
    }

    @Test
    fun `a received message's fields are read off the raw bytes, and a ClOrdID not of this run is no index`() {
        val fix = RawFix("P1-")
        fix.parse(raw("8=FIX.4.4|9=99|35=8|34=7|49=MOST|56=C|11=P1-12|41=P1-3|150=F|39=1|43=Y|5001=Y|10=000|"))
        assertEquals('8'.code.toByte(), fix.msgType)
        assertFalse(fix.longMsgType)
        assertEquals(12, fix.index)
        assertEquals(3, fix.origIndex)
        assertEquals('F'.code.toByte(), fix.execType)
        assertEquals('1'.code.toByte(), fix.ordStatus)
        assertTrue(fix.possDup && fix.unknown)

        fix.parse(raw("8=FIX.4.4|9=9|35=9|11=X-12|41=P1-|39=8|10=000|"))
        assertEquals('9'.code.toByte(), fix.msgType)
        assertEquals(PerfLogFormat.NO_INDEX, fix.index)
        assertEquals(PerfLogFormat.NO_INDEX, fix.origIndex)
        assertEquals(PerfLogFormat.ABSENT, fix.execType)
        assertFalse(fix.possDup || fix.unknown)

        fix.parse(raw("8=FIX.4.4|9=9|35=AE|11=P1-1x|10=000|"))
        assertTrue(fix.longMsgType)
        assertEquals(PerfLogFormat.NO_INDEX, fix.index)
    }

    @Test
    fun `a ClOrdID is the prefix and the index in decimal`() {
        val into = ByteArray(32)
        val prefix = "P1-".toByteArray()
        for (i in listOf(0, 7, 10, 999, 1_234_567, Int.MAX_VALUE)) {
            assertEquals("P1-$i", String(into, 0, encodeClOrdId(prefix, i, into)))
        }
    }

    @Test
    fun `reading a received message allocates nothing`() {
        val fix = RawFix("P1-")
        val buffer = raw("8=FIX.4.4|9=99|35=8|34=7|49=MOST|56=C|11=P1-12|41=P1-3|150=F|39=1|43=Y|5001=Y|10=000|")
        repeat(100_000) { fix.parse(buffer, 0, buffer.capacity()) }
        assertEquals(0L, allocated { repeat(100_000) { fix.parse(buffer, 0, buffer.capacity()) } })
    }

    private fun RawFix.parse(b: UnsafeBuffer) = parse(b, 0, b.capacity())

    private fun raw(text: String) = UnsafeBuffer(text.replace('|', '\u0001').toByteArray(Charsets.US_ASCII))

    companion object {
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

        /** Bytes [block] allocated on this thread. The first measurement in a JVM costs ~40 bytes itself, so this warms it. */
        fun allocated(block: () -> Unit): Long {
            measure {}
            measure {}
            return measure(block)
        }

        private fun measure(block: () -> Unit): Long {
            val id = Thread.currentThread().threadId()
            val before = threads.getThreadAllocatedBytes(id)
            block()
            return threads.getThreadAllocatedBytes(id) - before
        }
    }
}
