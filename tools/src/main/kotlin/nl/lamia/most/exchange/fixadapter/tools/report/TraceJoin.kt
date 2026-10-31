package nl.lamia.most.exchange.fixadapter.tools.report

import nl.lamia.most.exchange.fixadapter.trace.TraceFormat
import nl.lamia.most.exchange.fixadapter.trace.TraceLog

/**
 * The adapter's trace joined to the sender's log (Design.md §9.3): each measured request found in
 * the trace by its MsgSeqNum, the low 32 bits of the adapter's `clOrdId`, and its service time cut at
 * each stage. The stages add up to the service time to the first report:
 *
 * - **client → engine**: the sender's send → Artio's receive at the adapter;
 * - **engine → handler**: Artio's receive → the backend's handler (`IN`);
 * - **archive gate**: `IN` → the send queue finds it past the archive (`GATE`);
 * - **send wait**: `GATE` → the SDK took it (`SENT`);
 * - **exchange**: `SENT` → its first report from the SDK (`REPORT`);
 * - **report out**: `REPORT` → its 35=8 taken by Artio (`OUT`);
 * - **adapter → client**: `OUT` → the sender's arrival.
 *
 * And, off the client's path, **durable**: `OUT` → the durable `reportSeq` past it.
 *
 * Times are `System.nanoTime()` on both sides: comparable on one host only.
 */
class TraceJoin(a: Analysis, trace: TraceLog, participantOption: Long?) {
    val participant: Long
    /** Measured requests found in the trace, and not. */
    val joined: Int
    val missing: Int
    val stages: List<Pair<String, Dist>>
    val durable: Dist
    /** Each stage's share of the summed service time, in [stages]' order. */
    val shares: DoubleArray
    val dropped = trace.dropped
    val clean = trace.clean

    init {
        val participants = (0 until trace.size).filter { trace.stage[it] == TraceFormat.IN }.map { trace.participant[it] }.toSortedSet()
        participant = participantOption ?: participants.singleOrNull()
            ?: throw IllegalArgumentException(
                if (participants.isEmpty()) "the trace has no IN events" else "the trace holds participants $participants: say which with --participant",
            )

        // Each clOrdId's first time at each stage, the first REPORT's and OUT's reportSeq; and the durable steps.
        class Stages {
            var inAt = 0L
            var engineAt = 0L
            var gate = 0L
            var sent = 0L
            var report = 0L
            var out = 0L
            var outSeq = 0L
            var outShard = 0
        }
        val byClOrdId = HashMap<Long, Stages>()
        // Per shard, in time order: when the durable first-missing reportSeq moved, and to what.
        val durableTimes = HashMap<Int, MutableList<Long>>()
        val durableFirstMissing = HashMap<Int, MutableList<Long>>()
        for (r in 0 until trace.size) {
            if (trace.participant[r] != participant) continue
            val t = trace.time[r]
            when (trace.stage[r]) {
                TraceFormat.DURABLE -> {
                    val shard = trace.execType[r].toInt() and 0xff
                    durableTimes.getOrPut(shard) { ArrayList() } += t
                    durableFirstMissing.getOrPut(shard) { ArrayList() } += trace.extra[r]
                    continue
                }
            }
            val s = byClOrdId.getOrPut(trace.clOrdId[r]) { Stages() }
            when (trace.stage[r]) {
                TraceFormat.IN -> if (s.inAt == 0L) {
                    s.inAt = t
                    s.engineAt = if (trace.extra[r] != 0L) trace.epochToNano(trace.extra[r]) else 0L
                }
                TraceFormat.GATE -> if (s.gate == 0L) s.gate = t
                TraceFormat.SENT -> if (s.sent == 0L) s.sent = t
                TraceFormat.REPORT -> if (s.report == 0L) s.report = t
                TraceFormat.OUT -> if (s.out == 0L) {
                    s.out = t
                    s.outSeq = trace.extra[r]
                    s.outShard = trace.execType[r].toInt() and 0xff
                }
            }
        }
        // MsgSeqNum → the clOrdIds with it (a new sequenceIndex reuses numbers): the one whose IN follows the send.
        val bySeqNum = HashMap<Int, MutableList<Long>>()
        for (id in byClOrdId.keys) bySeqNum.getOrPut((id and 0xffffffffL).toInt()) { ArrayList() } += id

        // The sender's first answer to each request: the end of the service time.
        val firstArrival = LongArray(a.sends.size)
        val r = a.log.receives
        for (k in 0 until r.size) {
            val i = r.index[k]
            if (i !in 0 until a.sends.size || r.possDup(k)) continue
            if (r.msgType[k] != '8'.code.toByte() && r.msgType[k] != '9'.code.toByte()) continue
            if (firstArrival[i] == 0L || r.arrival[k] < firstArrival[i]) firstArrival[i] = r.arrival[k]
        }

        // How far each shard's durable first-missing had reached by each step: never backwards.
        val durableReach = durableFirstMissing.mapValues { (_, steps) ->
            var max = Long.MIN_VALUE
            LongArray(steps.size) { k -> max = maxOf(max, steps[k]); max }
        }

        val names = listOf("client → engine", "engine → handler", "archive gate", "send wait", "exchange", "report out", "adapter → client")
        val values = names.map { ArrayList<Long>() }
        val durableValues = ArrayList<Long>()
        val sums = DoubleArray(names.size)
        var found = 0
        var lost = 0
        for (i in a.measured) {
            val z = a.sends.sent[i]
            val s = bySeqNum[a.sends.msgSeqNum[i]]?.map { byClOrdId.getValue(it) }?.filter { it.inAt >= z }?.minByOrNull { it.inAt }
            if (s == null || s.gate == 0L || s.sent == 0L || s.report == 0L || s.out == 0L || firstArrival[i] == 0L) {
                lost++
                continue
            }
            found++
            val engine = if (s.engineAt in z..s.inAt) s.engineAt else s.inAt // no usable receive time: all of it client → handler
            val cuts = longArrayOf(engine - z, s.inAt - engine, s.gate - s.inAt, s.sent - s.gate, s.report - s.sent, s.out - s.report, firstArrival[i] - s.out)
            for (k in cuts.indices) {
                values[k] += cuts[k]
                sums[k] += cuts[k].toDouble()
            }
            if (s.outSeq > 0L) {
                val reached = durableReach[s.outShard]
                val times = durableTimes[s.outShard]
                if (reached != null && times != null) {
                    // The first step whose first-missing (as far as it has reached) is past this reportSeq.
                    var lo = 0
                    var hi = reached.size
                    while (lo < hi) {
                        val mid = (lo + hi) ushr 1
                        if (reached[mid] > s.outSeq) hi = mid else lo = mid + 1
                    }
                    if (lo < reached.size && times[lo] >= s.out) durableValues += times[lo] - s.out
                }
            }
        }
        joined = found
        missing = lost
        stages = names.mapIndexed { k, name -> name to Dist(values[k].toLongArray().also { it.sort() }) }
        durable = Dist(durableValues.toLongArray().also { it.sort() })
        val total = sums.sum()
        shares = DoubleArray(names.size) { if (total > 0) sums[it] / total else 0.0 }
    }
}
