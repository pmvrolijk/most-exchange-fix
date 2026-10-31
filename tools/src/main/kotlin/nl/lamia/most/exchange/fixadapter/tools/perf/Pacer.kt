package nl.lamia.most.exchange.fixadapter.tools.perf

/** The clock every time in the log is read from; `System.nanoTime()` outside the tests. */
fun interface NanoClock {
    fun nanoTime(): Long
}

/** What the [Pacer] drives: one message prepared once, then tried until taken. */
interface PacedSender {
    /** Decides message [index] (once, at its first attempt). */
    fun prepare(index: Int)

    /** Offers the prepared message; false if it was refused for now (back-pressure). */
    fun trySend(): Boolean

    /**
     * The prepared message was taken: tried first at [firstAttemptNs], last at [lastAttemptNs] (the
     * `trySend` that took it began), and taken by [sentNs]. Record it and move the sender's state on.
     */
    fun sent(index: Int, scheduledNs: Long, firstAttemptNs: Long, lastAttemptNs: Long, sentNs: Long, refusals: Int)

    /** Receives whatever has arrived; the count of work done. */
    fun poll(): Int

    /** The run cannot go on (a disconnect, say). */
    fun failed(): Boolean = false
}

/**
 * The send loop (Design.md §10.1): message *i* is due at [startNs] + [Schedule.offsetNs]. Its first
 * attempt is the first `trySend` at or after that, its send the one taken. Between attempts it polls,
 * so the receive times stay honest. It never waits for a message that is late: it sends at once.
 *
 * Returns the number of messages sent, which is short of the schedule only if [PacedSender.failed].
 */
class Pacer(private val schedule: Schedule, private val clock: NanoClock, private val sender: PacedSender) {
    fun run(startNs: Long): Int {
        var i = 0
        var firstAttempt = NOT_YET
        var refusals = 0
        var due = startNs + schedule.offsetNs(0)
        while (i < schedule.count) {
            if (sender.failed()) return i
            val now = clock.nanoTime()
            if (now - due >= 0) {
                // Deciding the message is the sender's own work, so it is before the first attempt.
                if (firstAttempt == NOT_YET) sender.prepare(i)
                val attempt = clock.nanoTime()
                if (firstAttempt == NOT_YET) firstAttempt = attempt
                if (sender.trySend()) {
                    sender.sent(i, due, firstAttempt, attempt, clock.nanoTime(), refusals)
                    i++
                    firstAttempt = NOT_YET
                    refusals = 0
                    if (i < schedule.count) due = startNs + schedule.offsetNs(i)
                } else {
                    refusals++
                }
            }
            if (sender.poll() == 0) Thread.onSpinWait()
        }
        return i
    }

    private companion object {
        const val NOT_YET = Long.MIN_VALUE
    }
}
