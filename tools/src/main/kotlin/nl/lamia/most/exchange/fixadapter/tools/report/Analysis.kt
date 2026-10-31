package nl.lamia.most.exchange.fixadapter.tools.report

import nl.lamia.most.exchange.fixadapter.tools.perf.Kind
import nl.lamia.most.exchange.fixadapter.tools.perf.Lateness
import nl.lamia.most.exchange.fixadapter.tools.perf.PerfLog
import nl.lamia.most.exchange.fixadapter.tools.perf.PerfLogFormat

/** What a received message told its request (Design.md §10.2), in the report's order. */
enum class Outcome(val label: String) {
    NEW_ACK("new ack"),
    PARTIAL_FILL("partial fill"),
    FILL("fill"),
    RESTING_PARTIAL_FILL("resting partial fill"),
    RESTING_FILL("resting fill"),
    CANCEL_ACK("cancel ack"),
    REJECT("reject"),
    CANCEL_REJECT("cancel reject"),
}

/** Why a received message was counted and not timed. */
enum class Untimed(val label: String) {
    POSS_DUP("PossDup=Y"),
    UNKNOWN("OrdStatusUnknown=Y"),
    SESSION_REJECT("35=3"),
    BUSINESS_REJECT("35=j"),
    WARMUP("the discard pass's"),
    NOT_OURS("names no request of the run"),
    UNSOLICITED_CANCEL("CANCELED, not for a cancel"),
    UNMATCHED_FILL("a fill no cross targeted"),
    OTHER_EXEC_TYPE("another ExecType"),
}

/** One outcome's timed samples, in arrival order. */
class Samples {
    var size = 0; private set
    var request = IntArray(64); private set
    var arrival = LongArray(64); private set
    var service = LongArray(64); private set
    var response = LongArray(64); private set

    fun add(request: Int, arrival: Long, service: Long, response: Long) {
        if (size == this.request.size) {
            val n = size * 2
            this.request = this.request.copyOf(n)
            this.arrival = this.arrival.copyOf(n)
            this.service = this.service.copyOf(n)
            this.response = this.response.copyOf(n)
        }
        this.request[size] = request
        this.arrival[size] = arrival
        this.service[size] = service
        this.response[size] = response
        size++
    }

    fun serviceSorted(): LongArray = service.copyOf(size).also { it.sort() }
    fun responseSorted(): LongArray = response.copyOf(size).also { it.sort() }
}

/**
 * A log, attributed (Design.md §10.2): each received message to the request that caused it, a
 * resting order's fill to the cross that targeted it, and every message the discard pass caused cut
 * from every series at once.
 */
class Analysis(val log: PerfLog) {
    val sends = log.sends
    val warmup = log.warmup
    val lateness = Lateness(sends.scheduled, sends.firstAttempt, sends.lastAttempt, sends.sent)
    val samples: Map<Outcome, Samples> = Outcome.entries.associateWith { Samples() }
    val untimed: MutableMap<Untimed, Int> = Untimed.entries.associateWith { 0 }.toMutableMap()
    val received = log.receives.size

    /** The measured requests: from the end of the discard pass. */
    val measured: IntRange = warmup until sends.size

    init {
        // The crosses that targeted each resting order, in send order, and how many of them its fills
        // have been paired with so far: each fill takes the earliest unpaired one sent before it.
        val crossesOf = HashMap<Int, MutableList<Int>>()
        for (i in 0 until sends.size) if (sends.kind[i] == Kind.CROSS) crossesOf.getOrPut(sends.target[i]) { ArrayList() } += i
        val paired = HashMap<Int, Int>()

        val r = log.receives
        for (k in 0 until r.size) {
            val msgType = r.msgType[k].toInt().toChar()
            val at = r.arrival[k]
            val i = r.index[k]
            when {
                msgType == '3' -> count(Untimed.SESSION_REJECT)
                msgType == 'j' -> count(Untimed.BUSINESS_REJECT)
                r.possDup(k) -> count(Untimed.POSS_DUP)
                r.unknown(k) -> count(Untimed.UNKNOWN)
                i == PerfLogFormat.NO_INDEX || i !in 0 until sends.size -> count(Untimed.NOT_OURS)
                msgType == '9' -> time(Outcome.CANCEL_REJECT, i, at)
                msgType != '8' -> count(Untimed.OTHER_EXEC_TYPE)
                else -> when (r.execType[k].toInt().toChar()) {
                    '0' -> time(Outcome.NEW_ACK, i, at)
                    '8' -> time(Outcome.REJECT, i, at)
                    '4' -> if (sends.kind[i] == Kind.CANCEL) time(Outcome.CANCEL_ACK, i, at) else count(Untimed.UNSOLICITED_CANCEL)
                    'F' -> {
                        val full = r.ordStatus[k] == '2'.code.toByte()
                        when (sends.kind[i]) {
                            Kind.CROSS -> time(if (full) Outcome.FILL else Outcome.PARTIAL_FILL, i, at)
                            Kind.PASSIVE -> {
                                val crosses = crossesOf[i].orEmpty()
                                val next = paired[i] ?: 0
                                if (next < crosses.size && sends.sent[crosses[next]] <= at) {
                                    paired[i] = next + 1
                                    time(if (full) Outcome.RESTING_FILL else Outcome.RESTING_PARTIAL_FILL, crosses[next], at)
                                } else {
                                    count(Untimed.UNMATCHED_FILL)
                                }
                            }
                            else -> count(Untimed.UNMATCHED_FILL)
                        }
                    }
                    else -> count(Untimed.OTHER_EXEC_TYPE)
                }
            }
        }
    }

    private fun count(why: Untimed) {
        untimed[why] = untimed.getValue(why) + 1
    }

    private fun time(outcome: Outcome, request: Int, arrival: Long) {
        if (request < warmup) return count(Untimed.WARMUP)
        samples.getValue(outcome).add(request, arrival, arrival - sends.sent[request], arrival - sends.scheduled[request])
    }

    /** The measured requests of [kind] (null: every kind). */
    fun requests(kind: Byte?): IntArray = measured.filter { kind == null || sends.kind[it] == kind }.toIntArray()

    val t0: Long get() = log.t0Nano

    /** Event times of each stream, for the throughput table: scheduled, sent, then each outcome's arrivals. */
    fun streams(): List<Pair<String, LongArray>> {
        val out = ArrayList<Pair<String, LongArray>>()
        out += "scheduled" to LongArray(measured.count()) { sends.scheduled[warmup + it] }
        out += "sent" to LongArray(measured.count()) { sends.sent[warmup + it] }
        for ((o, s) in samples) if (s.size > 0) out += o.label to s.arrival.copyOf(s.size).also { it.sort() }
        return out
    }
}

/** Nearest-rank percentiles of a sorted array. */
object Percentile {
    fun of(sorted: LongArray, p: Double): Long {
        if (sorted.isEmpty()) return 0
        val rank = kotlin.math.ceil(sorted.size * p / 100.0).toInt() - 1
        return sorted[rank.coerceIn(0, sorted.size - 1)]
    }

    fun of(sorted: DoubleArray, p: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val rank = kotlin.math.ceil(sorted.size * p / 100.0).toInt() - 1
        return sorted[rank.coerceIn(0, sorted.size - 1)]
    }
}

/** A stream's rate and burstiness: (n − 1) / span, and the gaps between its events. */
class Throughput(val stream: String, times: LongArray) {
    val count = times.size
    val spanNs = if (times.size < 2) 0L else times.last() - times.first()
    val ratePerSecond = if (spanNs <= 0) Double.NaN else (count - 1) * 1e9 / spanNs
    private val gaps = LongArray(maxOf(0, times.size - 1)) { times[it + 1] - times[it] }.also { it.sort() }
    val gapP50 = Percentile.of(gaps, 50.0)
    val gapP99 = Percentile.of(gaps, 99.0)
    val gapMax = gaps.lastOrNull() ?: 0L
}
