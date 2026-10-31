package nl.lamia.most.exchange.fixadapter.tools.perf

/** A request kind, as the log stores it. */
object Kind {
    const val PASSIVE: Byte = 1
    const val CROSS: Byte = 2
    const val CANCEL: Byte = 3

    fun name(kind: Byte): String = when (kind) {
        PASSIVE -> "passive"
        CROSS -> "cross"
        CANCEL -> "cancel"
        else -> "kind $kind"
    }
}

/**
 * The order mix (Design.md §10.1) and the sender's own book as predicted from what it sends.
 *
 * - A **passive** order rests: a buy at mid − *k* ticks, a sell at mid + *k* ticks, *k* from 1 to
 *   [levels], alternating sides, [qty] each.
 * - A **cross** takes the best of the sender's resting orders on the other side, price then time,
 *   at its price, for half its quantity rounded up or what remains, whichever is less: a partial
 *   fill of it, then a fill. Crosses alternate buy and sell; one with nothing opposite takes the
 *   other side, and one with nothing at all is sent as a passive.
 * - A **cancel** cancels the oldest resting order; with none, it is sent as a passive.
 *
 * [prepare] decides message *i* into the fields below; [commit] applies it to the book once it is
 * sent. Capacity is the run's message count, preallocated: nothing here allocates per message.
 */
class OrderMix(capacity: Int, private val levels: Int, private val qty: Long) {
    init {
        require(levels >= 1) { "--levels must be at least 1" }
        require(qty >= 1) { "--qty must be at least 1" }
    }

    // What prepare decided.
    var kind: Byte = 0; private set
    /** The kind asked for, when it fell back to a passive. */
    var substituted = false; private set
    var side: Byte = 0; private set
    /** Ticks from mid: negative below (bids), positive above (offers). */
    var priceTicks: Int = 0; private set
    var orderQty: Long = 0; private set
    /** The resting order a cross or cancel acts on, else -1. */
    var target: Int = -1; private set
    /** A cancel's target's original quantity (35=F's OrderQty). */
    var targetQty: Long = 0; private set

    // Per message index: the book's view of each resting order.
    private val remaining = LongArray(capacity)
    private val originalQty = LongArray(capacity)
    private val sideOf = ByteArray(capacity)
    private val levelOf = IntArray(capacity)
    // One FIFO per side and level, singly linked; and every resting order by age, doubly linked.
    private val nextInLevel = IntArray(capacity)
    private val heads = IntArray(2 * levels) { NONE }
    private val tails = IntArray(2 * levels) { NONE }
    private val olderThan = IntArray(capacity)
    private val newerThan = IntArray(capacity)
    private var oldest = NONE
    private var newest = NONE

    private var passives = 0
    private var crosses = 0
    private var resting = 0

    val restingCount: Int get() = resting

    fun prepare(wanted: Byte) {
        target = -1
        targetQty = 0
        substituted = false
        when (wanted) {
            Kind.CROSS -> {
                var crossSide: Byte = if (crosses % 2 == 0) BUY else SELL
                var taken = best(opposite(crossSide))
                if (taken == NONE) {
                    crossSide = opposite(crossSide)
                    taken = best(opposite(crossSide))
                }
                if (taken == NONE) return passive(substituted = true)
                kind = Kind.CROSS
                side = crossSide
                target = taken
                priceTicks = ticks(sideOf[taken], levelOf[taken])
                orderQty = minOf((originalQty[taken] + 1) / 2, remaining[taken])
            }
            Kind.CANCEL -> {
                if (oldest == NONE) return passive(substituted = true)
                kind = Kind.CANCEL
                target = oldest
                side = sideOf[oldest]
                priceTicks = ticks(side, levelOf[oldest])
                targetQty = originalQty[oldest]
                orderQty = targetQty
            }
            else -> passive(substituted = false)
        }
    }

    private fun passive(substituted: Boolean) {
        kind = Kind.PASSIVE
        this.substituted = substituted
        side = if (passives % 2 == 0) BUY else SELL
        val level = (passives / 2) % levels + 1
        priceTicks = ticks(side, level)
        orderQty = qty
    }

    /** Message [index], as [prepare] decided it, was sent: the book moves on. */
    fun commit(index: Int) {
        when (kind) {
            Kind.PASSIVE -> {
                passives++
                rest(index, side, kotlin.math.abs(priceTicks), orderQty)
            }
            Kind.CROSS -> {
                crosses++
                remaining[target] -= orderQty
                if (remaining[target] == 0L) remove(target)
            }
            Kind.CANCEL -> remove(target)
        }
    }

    private fun ticks(side: Byte, level: Int) = if (side == BUY) -level else level

    private fun queue(side: Byte, level: Int) = (if (side == BUY) 0 else levels) + level - 1

    /** The best resting order on [side]: the nearest level to mid, then the oldest; or [NONE]. */
    private fun best(side: Byte): Int {
        for (level in 1..levels) {
            val head = heads[queue(side, level)]
            if (head != NONE) return head
        }
        return NONE
    }

    private fun rest(index: Int, side: Byte, level: Int, qty: Long) {
        remaining[index] = qty
        originalQty[index] = qty
        sideOf[index] = side
        levelOf[index] = level
        val q = queue(side, level)
        nextInLevel[index] = NONE
        if (tails[q] == NONE) heads[q] = index else nextInLevel[tails[q]] = index
        tails[q] = index
        olderThan[index] = newest
        newerThan[index] = NONE
        if (newest == NONE) oldest = index else newerThan[newest] = index
        newest = index
        resting++
    }

    /** Removes [index], which is always its level's head: the oldest there. */
    private fun remove(index: Int) {
        val q = queue(sideOf[index], levelOf[index])
        check(heads[q] == index) { "order $index is not at the head of its level" }
        heads[q] = nextInLevel[index]
        if (heads[q] == NONE) tails[q] = NONE
        val older = olderThan[index]
        val newer = newerThan[index]
        if (older == NONE) oldest = newer else newerThan[older] = newer
        if (newer == NONE) newest = older else olderThan[newer] = older
        remaining[index] = 0
        resting--
    }

    companion object {
        const val BUY: Byte = '1'.code.toByte()
        const val SELL: Byte = '2'.code.toByte()
        private const val NONE = -1

        fun opposite(side: Byte): Byte = if (side == BUY) SELL else BUY
    }
}
