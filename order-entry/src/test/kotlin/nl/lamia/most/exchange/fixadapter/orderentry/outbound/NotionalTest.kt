package nl.lamia.most.exchange.fixadapter.orderentry.outbound

import java.math.BigInteger
import java.math.RoundingMode
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** AvgPx (Design.md §5.3): the 128-bit notional over CumQty, rounded half up at 8 decimals. */
class NotionalTest {

    private fun big(hi: Long, lo: Long): BigInteger =
        BigInteger.valueOf(hi).shiftLeft(64).add(BigInteger(java.lang.Long.toUnsignedString(lo)))

    @Test
    fun `two fills average to their weighted price, rounded half up`() {
        val n = Notional()
        n.add(100_00000001L, 3_00000000L)
        n.add(100_00000002L, 7_00000000L)
        // (3 × 100.00000001 + 7 × 100.00000002) / 10 = 100.000000017 → 100.00000002
        assertEquals(100_00000002L, n.average(10_00000000L))
        assertEquals(100_00000001L, Notional().apply { add(100_00000001L, 1_00000000L) }.average(1_00000000L))
    }

    @Test
    fun `exactly half rounds up`() {
        val n = Notional()
        n.add(1L, 1L) // 1e-8 × 1e-8
        n.add(2L, 1L)
        assertEquals(2L, n.average(2L), "(1 + 2) / 2 = 1.5 units → 2")
    }

    @Test
    fun `no fill averages to 0`() {
        assertEquals(0L, Notional().average(0L))
    }

    @Test
    fun `it carries past 64 bits and agrees with BigInteger`() {
        val random = Random(42)
        repeat(200) {
            val n = Notional()
            var expected = BigInteger.ZERO
            var qty = 0L
            repeat(1 + random.nextInt(20)) {
                val price = random.nextLong(1L, 1_000_000_00000000L) // up to a million
                val lastQty = random.nextLong(1L, 10_000_000_00000000L / 20) // up to ten million in all
                n.add(price, lastQty)
                expected = expected.add(BigInteger.valueOf(price).multiply(BigInteger.valueOf(lastQty)))
                qty += lastQty
            }
            assertEquals(expected, big(n.hi, n.lo))
            val average = BigInteger(expected.toString()).toBigDecimal().divide(qty.toBigDecimal(), 0, RoundingMode.HALF_UP).toLong()
            assertEquals(average, n.average(qty))
        }
    }

    @Test
    fun `it reloads from the row's two words`() {
        val n = Notional()
        n.add(Long.MAX_VALUE / 2, 4L)
        val again = Notional().apply { set(n.hi, n.lo) }
        assertEquals(big(n.hi, n.lo), big(again.hi, again.lo))
        assertEquals(n.average(4L), again.average(4L))
    }
}
