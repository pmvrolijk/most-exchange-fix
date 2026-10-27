package nl.lamia.most.exchange.fixadapter.orderentry.artio

import org.agrona.concurrent.CachedEpochClock
import uk.co.real_logic.artio.Reply
import uk.co.real_logic.artio.messages.SessionReplyStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The session each participant's reports are written on (Design.md §6.3), against a model of Artio
 * 0.182's handover as its source has it (`Framer.onRequestFixSession`, `HandoverOfflineFixSession`):
 * a request completes when the model says, the session is handed over with the numbers the engine's
 * index had, and a session already logged on again is handed over online.
 */
class ReportSessionsTest {

    /** A session as the library sees it. */
    class Model(val id: Long, var active: Boolean, var sequenceIndex: Int = 0, var lastSent: Int = 0, var lastReceived: Int = 0)

    private object View : SessionView<Model> {
        override fun id(session: Model) = session.id
        override fun isActive(session: Model) = session.active
        override fun sequenceIndex(session: Model) = session.sequenceIndex
        override fun lastSent(session: Model) = session.lastSent
        override fun lastReceived(session: Model) = session.lastReceived
    }

    class ModelReply : Reply<SessionReplyStatus> {
        private var state = Reply.State.EXECUTING
        private var result: SessionReplyStatus? = null

        fun complete(status: SessionReplyStatus) {
            state = Reply.State.COMPLETED
            result = status
        }

        override fun error(): Throwable? = null
        override fun resultIfPresent(): SessionReplyStatus? = result
        override fun state(): Reply.State = state
    }

    data class Request(val sessionId: Long, val replayFrom: Int, val replayIndex: Int, val reply: ModelReply)

    private class Handover : SessionHandover<Model> {
        val requests = mutableListOf<Request>()
        val releases = mutableListOf<Pair<Model, ModelReply>>()

        override fun requestSession(sessionId: Long, replayFrom: Int, replayIndex: Int, timeoutMs: Long): Reply<SessionReplyStatus> =
            ModelReply().also { requests += Request(sessionId, replayFrom, replayIndex, it) }

        override fun releaseToGateway(session: Model, timeoutMs: Long): Reply<SessionReplyStatus> =
            ModelReply().also { releases += session to it }
    }

    private val clock = CachedEpochClock()
    private val log = mutableListOf<String>()
    private val handover = Handover()
    private val sessions = ReportSessions(View, { log += it }, clock, retryMs = 100L).also { it.library = handover }

    /** The client logged on, claimed by the library (§2), then the reports of a working session. */
    private fun loggedOn(): Model {
        val online = Model(ID, active = true, sequenceIndex = 0, lastSent = 10, lastReceived = 7)
        sessions.acquired(P, online)
        return online
    }

    @Test
    fun `at startup each session Artio knows is requested offline replaying its archive from where recovery asks, and written to once handed over`() {
        // §8.17: from the participant's high-water + 1, here (sequenceIndex 2, MsgSeqNum 8).
        sessions.requestAtStartup(P, ID, replayFrom = 8, replayIndex = 2)
        assertEquals(listOf(ID to (8 to 2)), handover.requests.map { it.sessionId to (it.replayFrom to it.replayIndex) })
        assertNull(sessions.session(P), "not before the handover")
        assertTrue(sessions.isRequesting)
        assertTrue(sessions.isReplaying(P), "the replay is under way until the request completes")
        val offline = Model(ID, active = false, sequenceIndex = 2, lastSent = 4)
        sessions.acquired(P, offline) // Artio hands the session over before its replay (spike (f))
        assertTrue(sessions.isReplaying(P), "acquired, its replay still to come")
        handover.requests.single().reply.complete(SessionReplyStatus.OK)
        sessions.doWork()
        assertSame(offline, sessions.session(P))
        assertTrue(!sessions.isRequesting)
        assertTrue(!sessions.isReplaying(P) && !sessions.isAnyReplaying)
    }

    @Test
    fun `nothing past the replay's start is a success, the session handed over all the same`() {
        sessions.requestAtStartup(P, ID, replayFrom = 8, replayIndex = 0)
        val offline = Model(ID, active = false, lastSent = 4, lastReceived = 7)
        sessions.acquired(P, offline)
        handover.requests.single().reply.complete(SessionReplyStatus.SEQUENCE_NUMBER_TOO_HIGH)
        sessions.doWork()
        assertSame(offline, sessions.session(P))
        assertTrue(!sessions.isAnyReplaying)
        assertEquals(1, handover.requests.size, "not requested again")
    }

    @Test
    fun `a startup request that fails is still replaying, and is made again with the same replay`() {
        // The startup waits for every replay (§8.17): a failed one must not look finished.
        sessions.requestAtStartup(P, ID, replayFrom = 8, replayIndex = 2)
        handover.requests.single().reply.complete(SessionReplyStatus.OTHER_SESSION_OWNER)
        sessions.doWork()
        assertTrue(!sessions.isRequesting)
        assertTrue(sessions.isReplaying(P) && sessions.isAnyReplaying)
        clock.advance(100L)
        sessions.doWork()
        assertEquals(listOf(8 to 2, 8 to 2), handover.requests.map { it.replayFrom to it.replayIndex })
    }

    @Test
    fun `a disconnect leaves nothing to write on, and the session is requested back replaying from the last received + 1`() {
        val online = loggedOn()
        sessions.disconnected(P, online)
        assertNull(sessions.session(P), "§6.3: written to the Session the disconnect took, a report can reuse a MsgSeqNum")
        assertEquals(Request(ID, 8, 0, handover.requests.single().reply), handover.requests.single(),
            "a client back before the request is handed over online, with this replay: its 35=D must not be skipped")
        val offline = Model(ID, active = false, lastSent = 10, lastReceived = 7)
        sessions.acquired(P, offline)
        handover.requests.single().reply.complete(SessionReplyStatus.SEQUENCE_NUMBER_TOO_HIGH) // offline: nothing to replay
        sessions.doWork()
        assertSame(offline, sessions.session(P))
        assertTrue(log.isEmpty(), "$log")
    }

    @Test
    fun `a logged-on session is not written to once it stops being active`() {
        val online = loggedOn()
        assertSame(online, sessions.session(P))
        online.active = false // the client's Logout under way, the disconnect not yet seen
        assertNull(sessions.session(P))
    }

    @Test
    fun `an offline session whose client logs on is not written to once its logout is under way`() {
        val offline = Model(ID, active = false, lastSent = 4)
        sessions.requestAtStartup(P, ID, replayFrom = 1, replayIndex = 0)
        sessions.acquired(P, offline)
        assertSame(offline, sessions.session(P), "offline: written to, archived, resent at the Logon")
        offline.active = true // the client logged on
        assertSame(offline, sessions.session(P))
        offline.active = false // and is logging out
        assertNull(sessions.session(P))
    }

    @Test
    fun `a session handed over online not numbered above the library's last MsgSeqNum is counted as reused`() {
        // §8.19: the engine numbered the Logon from a lagging index. Nothing can undo it; it is seen.
        val online = loggedOn()
        sessions.disconnected(P, online)
        sessions.acquired(P, Model(ID, active = true, lastSent = 10, lastReceived = 8))
        assertEquals(1L, sessions.reusedSequence)
        sessions.disconnected(P, sessions.session(P)!!)
        sessions.acquired(P, Model(ID, active = true, lastSent = 11, lastReceived = 9))
        assertEquals(1L, sessions.reusedSequence, "11, above the 10 written: numbered on")
    }

    @Test
    fun `an offline session handed back below the library's last MsgSeqNum is released and requested again`() {
        val online = loggedOn()
        sessions.disconnected(P, online)
        // The engine's index had not yet seen the library's last message, its Logout at 10.
        val stale = Model(ID, active = false, lastSent = 9, lastReceived = 7)
        sessions.acquired(P, stale)
        assertNull(sessions.session(P), "the next report would reuse 10")
        assertEquals(listOf(stale), handover.releases.map { it.first })
        assertEquals(1L, sessions.staleHandovers)
        handover.requests.single().reply.complete(SessionReplyStatus.SEQUENCE_NUMBER_TOO_HIGH)
        handover.releases.single().second.complete(SessionReplyStatus.OK)
        sessions.doWork()
        assertEquals(1, handover.requests.size, "not at once: the index needs time to catch up")
        clock.update(99)
        sessions.doWork()
        assertEquals(1, handover.requests.size)
        clock.update(100)
        sessions.doWork()
        assertEquals(Request(ID, 8, 0, handover.requests.last().reply), handover.requests.last(), "the same replay as before")
        val caughtUp = Model(ID, active = false, lastSent = 10, lastReceived = 7)
        sessions.acquired(P, caughtUp)
        assertSame(caughtUp, sessions.session(P))
    }

    @Test
    fun `a session reset since, at a higher sequenceIndex, is not taken for a stale one`() {
        val online = loggedOn()
        sessions.disconnected(P, online)
        val reset = Model(ID, active = false, sequenceIndex = 1, lastSent = 1)
        sessions.acquired(P, reset)
        assertSame(reset, sessions.session(P))
        assertTrue(handover.releases.isEmpty())
    }

    @Test
    fun `a session the client has logged back on to is handed over online, and written to at once`() {
        val online = loggedOn()
        sessions.disconnected(P, online)
        val again = Model(ID, active = true, lastSent = 11, lastReceived = 9) // its Logon came first
        sessions.acquired(P, again)
        assertSame(again, sessions.session(P))
    }

    @Test
    fun `a refused request is made again after the retry interval, unless the session came by the claim meanwhile`() {
        val online = loggedOn()
        sessions.disconnected(P, online)
        handover.requests.single().reply.complete(SessionReplyStatus.SESSION_NOT_LOGGED_IN) // its Logon under way
        sessions.doWork()
        assertEquals(1, log.size, "logged: $log")
        clock.update(100)
        sessions.doWork()
        assertEquals(2, handover.requests.size, "asked again")
        assertEquals(8 to 0, handover.requests.last().replayFrom to handover.requests.last().replayIndex)

        handover.requests.last().reply.complete(SessionReplyStatus.OTHER_SESSION_OWNER) // the claim took it first
        val claimed = Model(ID, active = true, lastSent = 11, lastReceived = 9)
        sessions.acquired(P, claimed)
        sessions.doWork()
        clock.update(1_000)
        sessions.doWork()
        assertEquals(2, handover.requests.size, "held: nothing more to ask")
        assertSame(claimed, sessions.session(P))
    }

    @Test
    fun `a disconnect of a session no longer held changes nothing`() {
        val first = loggedOn()
        val second = Model(ID, active = true, lastSent = 12)
        sessions.acquired(P, second)
        sessions.disconnected(P, first)
        assertSame(second, sessions.session(P))
        assertTrue(handover.requests.isEmpty())
    }

    @Test
    fun `when the library loses the engine every session is requested back once it reconnects, as after a disconnect`() {
        loggedOn()
        sessions.onLibraryDisconnect()
        assertNull(sessions.session(P))
        sessions.doWork()
        assertTrue(handover.requests.isEmpty(), "not while the library is disconnected")
        sessions.onLibraryConnect()
        sessions.doWork()
        assertEquals(8 to 0, handover.requests.single().replayFrom to handover.requests.single().replayIndex)
    }

    private companion object {
        const val P = 1001L
        const val ID = 77L
    }
}
