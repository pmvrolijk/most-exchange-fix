package nl.lamia.most.exchange.fixadapter.tools.perf

/**
 * Each message's deviation from its schedule, attributed (Design.md §10.1). With *S* scheduled, *A*
 * the first attempt, *T* the last, *Z* the send and *Z*₋₁ the previous message's send, lateness
 * *Z* − *S* is
 *
 * - **queued** = max(*S*, *Z*₋₁) − *S*, waiting behind the message before it;
 * - **sender late** = *A* − max(*S*, *Z*₋₁), the sender's own thread elsewhere;
 * - **pushed back** = *T* − *A*, Artio not taking it;
 * - **send cost** = *Z* − *T*, the `trySend` that took it, on the sender's thread;
 *
 * and, tracing the queueing to its root, a **sender share** and a **system share**: each message's
 * queued time is split as the message before it was.
 *
 * Computed over every message of the run, the discard pass included: the chain runs through it.
 */
class Lateness(scheduled: LongArray, firstAttempt: LongArray, lastAttempt: LongArray, sent: LongArray) {
    val size = scheduled.size
    val lateness = LongArray(size)
    val queued = LongArray(size)
    val senderLate = LongArray(size)
    val pushedBack = LongArray(size)
    val sendCost = LongArray(size)
    val senderShare = DoubleArray(size)
    val systemShare = DoubleArray(size)

    init {
        require(firstAttempt.size == size && lastAttempt.size == size && sent.size == size)
        for (i in 0 until size) {
            val s = scheduled[i]
            val readyAt = if (i == 0) s else maxOf(s, sent[i - 1])
            require(firstAttempt[i] >= readyAt && lastAttempt[i] >= firstAttempt[i] && sent[i] >= lastAttempt[i]) {
                "message $i: its times are out of order (S=$s A=${firstAttempt[i]} T=${lastAttempt[i]} Z=${sent[i]}, " +
                    "previous Z=${if (i == 0) "-" else sent[i - 1]})"
            }
            queued[i] = readyAt - s
            senderLate[i] = firstAttempt[i] - readyAt
            pushedBack[i] = lastAttempt[i] - firstAttempt[i]
            sendCost[i] = sent[i] - lastAttempt[i]
            lateness[i] = sent[i] - s
            var inheritedSender = 0.0
            var inheritedSystem = 0.0
            if (queued[i] > 0) {
                // queued <= the previous message's lateness, because S >= S₋₁; so it is never 0 here.
                val previous = lateness[i - 1].toDouble()
                inheritedSender = queued[i] * senderShare[i - 1] / previous
                inheritedSystem = queued[i] * systemShare[i - 1] / previous
            }
            senderShare[i] = senderLate[i] + sendCost[i] + inheritedSender
            systemShare[i] = pushedBack[i] + inheritedSystem
        }
    }
}
