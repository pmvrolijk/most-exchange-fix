package nl.lamia.most.exchange.fixadapter.orderentry.outbound

/**
 * An order's cumulative notional, `price × lastQty` summed over its fills, in 128 bits (Design.md
 * §5.3): both factors are fixed-point at 8 decimals, so the product is at 16, and a day of fills
 * overflows 64 bits. Mutable and allocation-free; the row stores the two words.
 */
class Notional {
    var hi = 0L
        private set

    /** Unsigned. */
    var lo = 0L
        private set

    fun set(hi: Long, lo: Long) {
        this.hi = hi
        this.lo = lo
    }

    /** Adds one fill. Both are positive, fixed-point at 8 decimals. */
    fun add(price: Long, lastQty: Long) {
        require(price >= 0L && lastQty >= 0L) { "price $price, lastQty $lastQty" }
        val productLo = price * lastQty
        val productHi = Math.multiplyHigh(price, lastQty)
        val sum = lo + productLo
        val carry = if (java.lang.Long.compareUnsigned(sum, lo) < 0) 1L else 0L
        lo = sum
        hi += productHi + carry
    }

    /**
     * AvgPx at 8 decimals: the notional over [cumQty], rounded half up; 0 with no fill. The quotient
     * is a price, so it fits 63 bits.
     */
    fun average(cumQty: Long): Long {
        if (cumQty <= 0L) return 0L
        // Long division, a bit at a time: 128 steps, no allocation. The remainder stays below
        // 2 × cumQty < 2^64, so it is compared unsigned.
        var remainder = 0L
        var quotient = 0L
        for (bit in 127 downTo 0) {
            val next = if (bit >= 64) (hi ushr (bit - 64)) and 1L else (lo ushr bit) and 1L
            remainder = (remainder shl 1) or next
            quotient = quotient shl 1
            if (java.lang.Long.compareUnsigned(remainder, cumQty) >= 0) {
                remainder -= cumQty
                quotient = quotient or 1L
            }
        }
        return if (java.lang.Long.compareUnsigned(remainder shl 1, cumQty) >= 0) quotient + 1L else quotient
    }
}
