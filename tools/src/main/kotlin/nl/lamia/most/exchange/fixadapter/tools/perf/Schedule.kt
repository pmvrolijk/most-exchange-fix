package nl.lamia.most.exchange.fixadapter.tools.perf

/**
 * When each message is due, fixed before the first send (Design.md §10.1): message *i* at *T*₀ +
 * [offsetNs]. Never re-based on an actual send, so a sender behind it catches up back to back and
 * its lateness is counted rather than hidden.
 *
 * A schedule is steps of `<delayMicros> <count>`: [count] messages, each its step's delay after the
 * one before, the run's first message at offset 0. `--rate N` is the one step `10⁶/N N`, computed
 * exactly (offset *i* · 10⁹ / *N*) so it does not drift.
 */
class Schedule private constructor(
    private val delaysNs: LongArray,
    private val counts: IntArray,
    private val ratePerSecond: Long,
) {
    val count: Int = counts.sum()
    private val firstIndex = IntArray(counts.size)
    private val firstOffsetNs = LongArray(counts.size)

    init {
        require(counts.isNotEmpty() && counts.all { it > 0 }) { "every step needs a count above 0" }
        require(delaysNs.all { it >= 0 }) { "a delay cannot be negative" }
        var index = 0
        var offset = 0L
        for (s in counts.indices) {
            if (s > 0) offset += (counts[s - 1] - 1) * delaysNs[s - 1] + delaysNs[s]
            firstIndex[s] = index
            firstOffsetNs[s] = offset
            index += counts[s]
        }
    }

    fun offsetNs(i: Int): Long {
        require(i in 0 until count) { "message $i is outside the schedule's $count" }
        if (ratePerSecond > 0) return i * 1_000_000_000L / ratePerSecond
        var s = counts.size - 1
        while (firstIndex[s] > i) s--
        return firstOffsetNs[s] + (i - firstIndex[s]) * delaysNs[s]
    }

    /** The offset of the last message: the schedule's length. */
    val spanNs: Long get() = offsetNs(count - 1)

    /** Messages per second the schedule offers over its span, the first message included. */
    val offeredRate: Double get() = if (spanNs == 0L) Double.POSITIVE_INFINITY else (count - 1) * 1e9 / spanNs

    override fun toString(): String =
        if (ratePerSecond > 0) "$ratePerSecond/s x $count"
        else counts.indices.joinToString(",") { "${delaysNs[it] / 1000.0}us x ${counts[it]}" }

    companion object {
        fun rate(perSecond: Int, count: Int): Schedule {
            require(perSecond > 0) { "--rate must be above 0" }
            return Schedule(longArrayOf(1_000_000_000L / perSecond), intArrayOf(count), perSecond.toLong())
        }

        /** `"<delayMicros> <count>[,<delayMicros> <count>...]"`; the delay may have decimals. */
        fun steps(spec: String): Schedule {
            val steps = spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            require(steps.isNotEmpty()) { "--schedule is empty" }
            val delays = LongArray(steps.size)
            val counts = IntArray(steps.size)
            steps.forEachIndexed { s, step ->
                val parts = step.split(Regex("\\s+"))
                require(parts.size == 2) { "a --schedule step is <delayMicros> <count>, not '$step'" }
                val micros = parts[0].toBigDecimalOrNull() ?: throw IllegalArgumentException("not a delay: ${parts[0]}")
                delays[s] = micros.movePointRight(3).toLong()
                counts[s] = parts[1].toIntOrNull() ?: throw IllegalArgumentException("not a count: ${parts[1]}")
            }
            return Schedule(delays, counts, 0)
        }
    }
}
