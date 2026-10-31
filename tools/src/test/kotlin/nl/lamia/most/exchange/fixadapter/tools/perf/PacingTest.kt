package nl.lamia.most.exchange.fixadapter.tools.perf

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Design.md §10.1: the schedule is fixed before the first send and never re-based; each message's
 * lateness is queued + sender late + pushed back + send cost, and its queueing is traced to its root.
 * Every expected value here is worked out by hand from that clause.
 */
class PacingTest {
    @Test
    fun `a rate schedule puts message i at i times 10^9 over N ns, exactly, with no drift`() {
        val s = Schedule.rate(4, 4)
        assertContentEquals(longArrayOf(0, 250_000_000, 500_000_000, 750_000_000), LongArray(4) { s.offsetNs(it) })
        // 1/3 s does not divide into ns: offset i is computed from i, never by adding 333_333_333.
        val third = Schedule.rate(3, 301)
        assertEquals(333_333_333L, third.offsetNs(1))
        assertEquals(1_000_000_000L, third.offsetNs(3))
        assertEquals(100_000_000_000L, third.offsetNs(300))
    }

    @Test
    fun `a step schedule puts each message its step's delay after the one before, the first at 0`() {
        val s = Schedule.steps("10 3, 2.5 2")
        // 0, 10, 20 µs; then 2.5 µs after the last of the first step: 22.5, 25 µs.
        assertContentEquals(longArrayOf(0, 10_000, 20_000, 22_500, 25_000), LongArray(5) { s.offsetNs(it) })
        assertEquals(5, s.count)
        assertThrows<IllegalArgumentException> { Schedule.steps("10") }
        assertThrows<IllegalArgumentException> { Schedule.steps("10 0") }
    }

    @Test
    fun `a sender behind its schedule sends back to back, never re-based, and its lateness is its own`() {
        val clock = FakeClock()
        // A 3.5 µs stall (a GC, say) while deciding the first message; each poll then takes 10 ns.
        val sender = FakeSender(clock, pollNs = 10, prepareNs = mapOf(0 to 3_500L))
        Pacer(Schedule.steps("1 3"), clock, sender).run(startNs = 0)

        assertContentEquals(longArrayOf(0, 1_000, 2_000), sender.scheduled.toLongArray())
        assertContentEquals(longArrayOf(3_500, 3_510, 3_520), sender.sent.toLongArray())
        val l = sender.lateness()
        assertContentEquals(longArrayOf(0, 2_500, 1_510), l.queued)
        assertContentEquals(longArrayOf(3_500, 10, 10), l.senderLate)
        assertContentEquals(longArrayOf(0, 0, 0), l.pushedBack)
        // All of it is the generator's: the queueing inherits message 0's sender lateness.
        assertContentEquals(doubleArrayOf(3_500.0, 2_510.0, 1_520.0), l.senderShare)
        assertContentEquals(doubleArrayOf(0.0, 0.0, 0.0), l.systemShare)
    }

    @Test
    fun `a refused message is pushed back, and the message queued behind it inherits the system's share`() {
        val clock = FakeClock()
        // Message 1 is refused 15 times; each poll between attempts takes 100 ns.
        val sender = FakeSender(clock, pollNs = 100, refuse = mapOf(1 to 15))
        Pacer(Schedule.steps("1 3"), clock, sender).run(startNs = 0)

        assertContentEquals(longArrayOf(0, 1_000, 2_000), sender.scheduled.toLongArray())
        assertContentEquals(longArrayOf(0, 1_000, 2_600), sender.firstAttempt.toLongArray())
        assertContentEquals(longArrayOf(0, 2_500, 2_600), sender.sent.toLongArray())
        assertContentEquals(listOf(0, 15, 0), sender.refusals)
        val l = sender.lateness()
        assertContentEquals(longArrayOf(0, 1_500, 600), l.lateness)
        assertContentEquals(longArrayOf(0, 0, 500), l.queued)
        assertContentEquals(longArrayOf(0, 0, 100), l.senderLate)
        assertContentEquals(longArrayOf(0, 1_500, 0), l.pushedBack)
        // Message 2: its 500 ns queued behind message 1 is the system's; its 100 ns poll is the sender's.
        assertContentEquals(doubleArrayOf(0.0, 0.0, 100.0), l.senderShare)
        assertContentEquals(doubleArrayOf(0.0, 1_500.0, 500.0), l.systemShare)
    }

    @Test
    fun `the trySend Artio takes is the send cost, the sender's own, apart from the refusals before it`() {
        val clock = FakeClock()
        // Each trySend takes 30 ns; message 1 is refused twice; each poll takes 100 ns.
        val sender = FakeSender(clock, pollNs = 100, trySendNs = 30, refuse = mapOf(1 to 2))
        Pacer(Schedule.steps("1 2"), clock, sender).run(startNs = 0)

        // Message 0: due 0, tried at 0, taken at 30. Message 1: due 1000; polls bring the clock to 1030;
        // refused at 1030 (to 1060) and 1160 (to 1190), tried again at 1290, taken at 1320.
        assertContentEquals(longArrayOf(0, 1_030), sender.firstAttempt.toLongArray())
        assertContentEquals(longArrayOf(0, 1_290), sender.lastAttempt.toLongArray())
        assertContentEquals(longArrayOf(30, 1_320), sender.sent.toLongArray())
        val l = sender.lateness()
        assertContentEquals(longArrayOf(30, 320), l.lateness)
        assertContentEquals(longArrayOf(0, 30), l.senderLate)
        assertContentEquals(longArrayOf(0, 260), l.pushedBack)
        assertContentEquals(longArrayOf(30, 30), l.sendCost)
        assertContentEquals(doubleArrayOf(30.0, 60.0), l.senderShare)
        assertContentEquals(doubleArrayOf(0.0, 260.0), l.systemShare)
    }

    @Test
    fun `a message is decided once, however often it is refused`() {
        val clock = FakeClock()
        val sender = FakeSender(clock, pollNs = 100, refuse = mapOf(0 to 3, 1 to 2))
        Pacer(Schedule.steps("1 3"), clock, sender).run(startNs = 0)
        assertEquals(listOf(0, 1, 2), sender.prepared)
    }

    @Test
    fun `queueing is split as the message before it was`() {
        // Message 0: 50 ns late itself, 50 ns pushed back. Message 1, due at 20, waits for it until 100.
        val l = Lateness(longArrayOf(0, 20), longArrayOf(50, 110), longArrayOf(100, 110), longArrayOf(100, 110))
        assertContentEquals(longArrayOf(0, 80), l.queued)
        assertContentEquals(longArrayOf(50, 10), l.senderLate)
        assertContentEquals(longArrayOf(50, 0), l.pushedBack)
        assertContentEquals(doubleArrayOf(50.0, 10.0 + 40.0), l.senderShare)
        assertContentEquals(doubleArrayOf(50.0, 40.0), l.systemShare)
        for (i in 0..1) assertEquals(l.lateness[i].toDouble(), l.senderShare[i] + l.systemShare[i])
    }

    @Test
    fun `times out of order are refused, not attributed`() {
        assertThrows<IllegalArgumentException> { Lateness(longArrayOf(100), longArrayOf(50), longArrayOf(50), longArrayOf(120)) }
        assertThrows<IllegalArgumentException> { Lateness(longArrayOf(0), longArrayOf(50), longArrayOf(40), longArrayOf(60)) }
        assertThrows<IllegalArgumentException> { Lateness(longArrayOf(0), longArrayOf(50), longArrayOf(60), longArrayOf(55)) }
    }

    private class FakeClock : NanoClock {
        var now = 0L
        override fun nanoTime() = now
    }

    private class FakeSender(
        private val clock: FakeClock,
        private val pollNs: Long,
        private val prepareNs: Map<Int, Long> = emptyMap(),
        private val refuse: Map<Int, Int> = emptyMap(),
        private val trySendNs: Long = 0,
    ) : PacedSender {
        val prepared = ArrayList<Int>()
        val scheduled = ArrayList<Long>()
        val firstAttempt = ArrayList<Long>()
        val lastAttempt = ArrayList<Long>()
        val sent = ArrayList<Long>()
        val refusals = ArrayList<Int>()
        private var current = -1
        private var refusedSoFar = 0

        override fun prepare(index: Int) {
            prepared += index
            current = index
            refusedSoFar = 0
            clock.now += prepareNs[index] ?: 0
        }

        override fun trySend(): Boolean {
            clock.now += trySendNs
            if (refusedSoFar < (refuse[current] ?: 0)) {
                refusedSoFar++
                return false
            }
            return true
        }

        override fun sent(index: Int, scheduledNs: Long, firstAttemptNs: Long, lastAttemptNs: Long, sentNs: Long, refusals: Int) {
            scheduled += scheduledNs
            firstAttempt += firstAttemptNs
            lastAttempt += lastAttemptNs
            sent += sentNs
            this.refusals += refusals
        }

        override fun poll(): Int {
            clock.now += pollNs
            return 1
        }

        fun lateness() = Lateness(scheduled.toLongArray(), firstAttempt.toLongArray(), lastAttempt.toLongArray(), sent.toLongArray())
    }
}
