package nl.lamia.most.exchange.fixadapter.orderentry.artio

import nl.lamia.most.exchange.fixadapter.orderentry.store.OrderStore
import nl.lamia.most.exchange.fixadapter.session.fixMessage
import org.agrona.concurrent.CachedEpochClock
import org.agrona.concurrent.UnsafeBuffer
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The library's own count of what a session has received, and its ResendRequests: Design.md §2, "A
 * gap Artio leaves open"; §8.21 (h3), (h4) and spike (i). Against a model of Artio's session: what it
 * hands the library, valid or not, and what it counts as received.
 */
class ReceiveGapsTest {

    @TempDir
    lateinit var dir: File

    private lateinit var store: OrderStore
    private val clock = CachedEpochClock().also { it.update(1_000_000L) }
    private val log = mutableListOf<String>()
    private lateinit var gaps: ReceiveGaps<Model>

    /** Artio's session as [ReceiveGaps] reads it. */
    class Model(var active: Boolean = true, var index: Int = 0, var lastReceived: Int = 0)

    private val requests = mutableListOf<Pair<Int, Int>>()
    private val logouts = mutableListOf<String>()
    private var takes = true

    private val view = object : GapSessions<Model> {
        override fun isActive(session: Model) = session.active
        override fun sequenceIndex(session: Model) = session.index
        override fun lastReceived(session: Model) = session.lastReceived
        override fun resendRequest(session: Model, from: Int, to: Int): Long {
            if (!takes) return -1L
            requests += from to to
            return 1L
        }
        override fun logout(session: Model, text: String) {
            logouts += text
            session.active = false
        }
    }

    @BeforeTest
    fun open() {
        store = OrderStore.open(dir).also { it.load(TODAY) }
        gaps = ReceiveGaps(store, view, { log += it }, clock)
    }

    @AfterTest
    fun close() = store.close()

    private fun restart() {
        store.close()
        store = OrderStore.open(dir).also { it.load(TODAY) }
        gaps = ReceiveGaps(store, view, { log += it }, clock)
    }

    /** A client Logon at [seq]: Artio counts it, then the library acquires the session, logged on. */
    private fun logon(session: Model, seq: Int) {
        session.lastReceived = seq
        session.active = true
        gaps.acquired(P, session)
        gaps.loggedOn(P, session)
    }

    /** An application message, as Artio hands it: [valid] false if Artio dropped it. */
    private fun message(session: Model, seq: Int, valid: Boolean = true, possDup: Boolean = false) {
        if (!possDup && seq > session.lastReceived) session.lastReceived = seq
        val bytes = fixMessage("D", listOf(49 to "CLIENT1", 56 to "MOST", 34 to "$seq") + (if (possDup) listOf(43 to "Y") else emptyList()) + listOf(11 to "C$seq"))
        gaps.onMessage(P, session.index, UnsafeBuffer(bytes), 0, bytes.size, valid)
    }

    private fun gapFill(session: Model, seq: Int, newSeqNo: Int) {
        val bytes = fixMessage("4", listOf(49 to "CLIENT1", 56 to "MOST", 34 to "$seq", 43 to "Y", 123 to "Y", 36 to "$newSeqNo"))
        gaps.onMessage(P, session.index, UnsafeBuffer(bytes), 0, bytes.size, true)
    }

    private fun after(ms: Long) {
        clock.advance(ms)
        gaps.doWork()
    }

    /** The stall of (h4): a gap at 3–4, 5–7 dropped during the resend, the answer stopping at 4, then 8–9 dropped. */
    private fun stall(session: Model) {
        logon(session, 1)
        message(session, 2)
        message(session, 5, valid = false)
        message(session, 6, valid = false)
        message(session, 7, valid = false)
        message(session, 3, possDup = true)
        gapFill(session, 4, 5)
        message(session, 8, valid = false)
        message(session, 9, valid = false)
    }

    @Test
    fun `a gap Artio's own resend fills is never asked for, and leaves nothing stored`() {
        val s = Model()
        logon(s, 1)
        message(s, 2)
        message(s, 5, valid = false)
        assertEquals(3 to 5, gaps.gapFrom(P) to gaps.gapTo(P), "open, the dropped message itself included")
        assertEquals(3, store.receiveGapFrom(P), "stored as it opens")
        message(s, 3, possDup = true)
        gapFill(s, 4, 5)
        message(s, 5, possDup = true)
        message(s, 6)
        after(5_000)
        assertEquals(emptyList(), requests)
        assertEquals(0, gaps.gapTo(P))
        assertEquals(0, store.receiveGapFrom(P), "deleted as it closes")
    }

    @Test
    fun `a stall is asked for after a second without progress, from the first missing to the highest dropped`() {
        val s = Model()
        stall(s)
        after(999)
        assertEquals(emptyList(), requests, "not before a second without progress")
        after(1)
        assertEquals(listOf(5 to 9), requests)
    }

    @Test
    fun `what the stall's request brings back closes the gap, and the session carries on in order`() {
        val s = Model()
        stall(s)
        after(1_000)
        for (seq in 5..9) message(s, seq, possDup = true)
        message(s, 10)
        after(5_000)
        assertEquals(listOf(5 to 9), requests, "asked once")
        assertEquals(0, gaps.gapTo(P))
        assertEquals(0, store.receiveGapFrom(P))
        assertEquals(emptyList(), logouts)
    }

    @Test
    fun `after three requests without progress the session is logged out naming the range, and the gap is kept`() {
        val s = Model()
        stall(s)
        after(1_000)
        after(1_000)
        after(1_000)
        assertEquals(List(3) { 5 to 9 }, requests)
        assertEquals(emptyList(), logouts)
        after(1_000)
        assertEquals(listOf("messages 5 to 9 not received; resend them"), logouts)
        assertEquals(3, requests.size, "a logout, not a fourth request")
        assertEquals(5 to 9, store.receiveGapFrom(P) to store.receiveGapTo(P), "kept for the next Logon")
    }

    @Test
    fun `progress resets the count of requests`() {
        val s = Model()
        stall(s)
        after(1_000)
        after(1_000)
        message(s, 5, possDup = true) // the client answers, part of the way
        after(1_000)
        after(1_000)
        after(1_000)
        assertEquals(emptyList(), logouts, "three more requests after the progress before a logout")
        assertEquals(listOf(5 to 9, 5 to 9, 6 to 9, 6 to 9, 6 to 9), requests)
        after(1_000)
        assertEquals(listOf("messages 6 to 9 not received; resend them"), logouts)
    }

    @Test
    fun `a gap open at a disconnect is asked for at once after the next Logon, which Artio does not ask for (h3)`() {
        val s = Model()
        logon(s, 1)
        message(s, 2)
        message(s, 5, valid = false)
        s.active = false
        gaps.disconnected(P)
        after(5_000)
        assertEquals(emptyList(), requests, "nothing is asked of a session not logged on")
        val back = Model(lastReceived = 5) // the engine's count: past the gap
        gaps.acquired(P, back.also { it.active = false })
        logon(back, 6)
        gaps.doWork()
        assertEquals(listOf(3 to 5), requests, "at once")
        for (seq in 3..5) message(back, seq, possDup = true)
        message(back, 7)
        after(5_000)
        assertEquals(listOf(3 to 5), requests)
        assertEquals(0, gaps.gapTo(P))
        assertEquals(0, store.receiveGapFrom(P))
    }

    @Test
    fun `a gap survives a restart, and is asked for at once after the first Logon`() {
        val s = Model()
        logon(s, 1)
        message(s, 2)
        message(s, 5, valid = false)
        restart()
        val offline = Model(active = false, lastReceived = 5)
        gaps.acquired(P, offline)
        logon(offline, 6)
        gaps.doWork()
        assertEquals(listOf(3 to 5), requests)
    }

    @Test
    fun `with no gap stored, counts start from Artio's last received, and a following Logon asks for nothing`() {
        val offline = Model(active = false, lastReceived = 40)
        gaps.acquired(P, offline)
        logon(offline, 41)
        message(offline, 42)
        after(5_000)
        assertEquals(emptyList(), requests)
        assertEquals(0, gaps.gapTo(P))
    }

    @Test
    fun `a Logon above the expected number is left to Artio's own resend for a second`() {
        val offline = Model(active = false, lastReceived = 40)
        gaps.acquired(P, offline)
        logon(offline, 45)
        assertEquals(41 to 44, gaps.gapFrom(P) to gaps.gapTo(P))
        after(999)
        assertEquals(emptyList(), requests)
        for (seq in 41..44) message(offline, seq, possDup = true)
        gapFill(offline, 45, 46)
        after(5_000)
        assertEquals(emptyList(), requests)
        assertEquals(0, gaps.gapTo(P))
    }

    @Test
    fun `a valid message above every number seen opens a gap up to the one before it`() {
        val s = Model()
        logon(s, 1)
        message(s, 2)
        message(s, 5) // Artio took it in order: it had counted 3 and 4, which never reached the library
        assertEquals(3 to 4, gaps.gapFrom(P) to gaps.gapTo(P))
        message(s, 6)
        assertEquals(4, gaps.gapTo(P), "what is seen above the gap does not widen it")
        after(1_000)
        assertEquals(listOf(3 to 4), requests)
        message(s, 3, possDup = true)
        message(s, 4, possDup = true)
        message(s, 7)
        assertEquals(0, gaps.gapTo(P))
        after(5_000)
        assertEquals(1, requests.size)
    }

    @Test
    fun `a message at a higher sequenceIndex drops the gap, its numbers gone with the reset`() {
        val s = Model()
        stall(s)
        s.index = 1
        s.lastReceived = 0
        message(s, 1)
        assertEquals(0, gaps.gapTo(P))
        assertEquals(0, store.receiveGapFrom(P))
        after(5_000)
        assertEquals(emptyList(), requests)
    }

    @Test
    fun `a Logon at a higher sequenceIndex drops a stored gap`() {
        val s = Model()
        logon(s, 1)
        message(s, 2)
        message(s, 5, valid = false)
        gaps.disconnected(P)
        val reset = Model(index = 1)
        logon(reset, 1)
        gaps.doWork()
        assertEquals(emptyList(), requests)
        assertEquals(0, store.receiveGapFrom(P))
    }

    @Test
    fun `a request Artio does not take is made again in the next duty cycle, and is not counted`() {
        val s = Model()
        stall(s)
        takes = false
        after(1_000)
        assertEquals(emptyList(), requests)
        takes = true
        gaps.doWork()
        assertEquals(listOf(5 to 9), requests)
        after(1_000)
        after(1_000)
        assertEquals(3, requests.size)
        assertEquals(emptyList(), logouts, "the refused one did not count")
    }

    @Test
    fun `the startup replay skips a message the library dropped, and only that`() {
        // Design.md §2: Artio's archive holds what it marked invalid, and its replay hands it back as valid.
        val s = Model()
        stall(s) // gap 5 to 9 at index 0, stored
        restart()
        fun dropped(seq: Int, index: Int = 0, possDup: Boolean = false): Boolean {
            val bytes = fixMessage("D", listOf(49 to "CLIENT1", 56 to "MOST", 34 to "$seq") + (if (possDup) listOf(43 to "Y") else emptyList()) + listOf(11 to "C$seq"))
            return gaps.droppedBeforeRestart(P, index, UnsafeBuffer(bytes), 0, bytes.size)
        }
        assertEquals(listOf(5, 6, 7, 8, 9), (1..12).filter { dropped(it) })
        assertTrue(!dropped(5, possDup = true), "a resend's copy is the archive's to replay")
        assertTrue(!dropped(5, index = 1), "another sequenceIndex's numbers")
    }

    @Test
    fun `counting messages in order, with no gap, allocates nothing`() {
        val s = Model()
        logon(s, 1)
        val messages = (2..4_001).map { seq -> UnsafeBuffer(fixMessage("D", listOf(49 to "CLIENT1", 56 to "MOST", 34 to "$seq", 11 to "C$seq"))) }
        var next = 0
        fun run(n: Int) = repeat(n) {
            val m = messages[next++ % messages.size]
            if (next > messages.size) return@repeat
            gaps.onMessage(P, 0, m, 0, m.capacity(), true)
            gaps.doWork()
        }
        run(2_000)
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val before = threads.currentThreadAllocatedBytes
        run(2_000)
        val allocated = threads.currentThreadAllocatedBytes - before
        assertEquals(0, gaps.gapTo(P))
        assertTrue(allocated == 0L, "bytes allocated for 2,000 messages: $allocated")
    }

    private companion object {
        const val P = 1001L
        const val TODAY = 20261026
    }
}
