package nl.lamia.most.exchange.fixadapter.orderentry.artio

import org.agrona.collections.Long2ObjectHashMap
import org.agrona.concurrent.EpochClock
import org.agrona.concurrent.SystemEpochClock
import uk.co.real_logic.artio.Reply
import uk.co.real_logic.artio.library.FixLibrary
import uk.co.real_logic.artio.messages.SessionReplyStatus
import uk.co.real_logic.artio.session.Session

/** What [ReportSessions] reads of a session: Artio's `Session` in production, a model in tests. */
interface SessionView<S> {
    fun id(session: S): Long
    fun isActive(session: S): Boolean
    fun sequenceIndex(session: S): Int
    fun lastSent(session: S): Int
    fun lastReceived(session: S): Int
}

/** The library's session handover, as [ReportSessions] uses it: `FixLibrary`'s in production. */
interface SessionHandover<S> {
    fun requestSession(sessionId: Long, replayFrom: Int, replayIndex: Int, timeoutMs: Long): Reply<SessionReplyStatus>
    fun releaseToGateway(session: S, timeoutMs: Long): Reply<SessionReplyStatus>
}

/** Artio's own. */
object ArtioSessions : SessionView<Session> {
    override fun id(session: Session) = session.id()
    override fun isActive(session: Session) = session.isActive
    override fun sequenceIndex(session: Session) = session.sequenceIndex()
    override fun lastSent(session: Session) = session.lastSentMsgSeqNum()
    override fun lastReceived(session: Session) = session.lastReceivedMsgSeqNum()

    fun handover(library: FixLibrary): SessionHandover<Session> = object : SessionHandover<Session> {
        override fun requestSession(sessionId: Long, replayFrom: Int, replayIndex: Int, timeoutMs: Long) =
            library.requestSession(sessionId, replayFrom, replayIndex, timeoutMs)

        override fun releaseToGateway(session: Session, timeoutMs: Long) = library.releaseToGateway(session, timeoutMs)
    }
}

/**
 * The `Session` each participant's reports are written on (Design.md §6.3), held by the order-entry
 * library whether its client is logged on or not. Artio numbers every message on it; offline, it
 * archives without sending, and Artio's own resend delivers at the next Logon.
 *
 * - **At startup**, before the acceptor binds, every configured session Artio knows is requested
 *   offline ([requestAtStartup]), replaying the archived inbound messages past what the store has
 *   handled: recovery of lost rows (Design.md §8.17). No client can be logged on yet. Until the
 *   request has completed with the session held, the participant [isReplaying]: its messages are
 *   the archive's. Artio replays them before it completes the request (spike (f)).
 * - **Once a logged-on session stops being active** (its logout under way, or its disconnect not yet
 *   seen), nothing is written to it: [session] is null. Written then, a report is numbered by the
 *   library on a session the engine is taking back (found 2026-10-25, below).
 * - **At a disconnect** the engine takes the session back. It is requested back **replaying from the
 *   last message received + 1**, at its `sequenceIndex`. Offline, there is nothing to replay: Artio
 *   answers `SEQUENCE_NUMBER_TOO_HIGH` and still hands it over. But a client that logs back on before
 *   the request reaches the engine is handed over online, with the request's replay: with
 *   `NO_MESSAGE_REPLAY`, a 35=D it sent first would never reach the library (Artio 0.182
 *   `Framer.onRequestFixSession`). With the replay it comes, as with the claim of §2.
 * - **A session handed back numbered below what the library last wrote on it is not used.** The
 *   engine reads the offline session's last MsgSeqNum from its sequence index, and waits only for its
 *   own stream to be indexed, not the library's (`HandoverOfflineFixSession.checkLoggerUpToDate`).
 *   A message the library wrote just before the disconnect can be missing from it: the next one would
 *   reuse its MsgSeqNum, and the client would never get the first. It is released and requested again
 *   after [retryMs], by when the index has caught up.
 * - **Any other outcome** keeps the reports waiting, and the request is made again after [retryMs],
 *   unless the session has arrived meanwhile by the claim of §2 (the client logged on).
 *
 * Single-threaded, the order-entry library's thread. Allocation-free while nothing is pending.
 */
class ReportSessions<S : Any>(
    private val view: SessionView<S>,
    private val log: (String) -> Unit,
    private val clock: EpochClock = SystemEpochClock.INSTANCE,
    private val retryMs: Long = 100L,
    private val timeoutMs: Long = 10_000L,
) {
    /** Set once the library has connected: [FixLibrary.connect] runs its connect handler before it returns. */
    lateinit var library: SessionHandover<S>

    private class Held<S>(val participantId: Long) {
        /** The library's, online or offline; null from a disconnect until it is back. */
        var session: S? = null

        /** [session] has been seen active: once it stops being so, it is not written to. */
        var online = false
        var sessionId = NO_SESSION
        var request: Reply<SessionReplyStatus>? = null
        var release: Reply<SessionReplyStatus>? = null
        var replayFrom = FixLibrary.NO_MESSAGE_REPLAY
        var replayIndex = FixLibrary.NO_MESSAGE_REPLAY
        var retryAtMs = NO_RETRY

        /** The startup request, replaying the archive, has not yet completed with the session held (§8.17). */
        var replaying = false

        /** The last MsgSeqNum the library wrote on the session, at its `sequenceIndex`, as last seen. */
        var lastSent = 0
        var lastSentIndex = -1
    }

    private val byParticipant = Long2ObjectHashMap<Held<S>>()
    private val all = ArrayList<Held<S>>()

    /** `requestSession`s made, the startup ones included. */
    var requests = 0L
        private set

    /** Offline sessions handed back numbered behind the library's own last message, and released. */
    var staleHandovers = 0L
        private set

    /**
     * Sessions handed over online numbered at or below the library's own last MsgSeqNum: the engine
     * numbered the client's Logon from a lagging index, and a report the library wrote may never
     * reach the client (Design.md §8.19). Detected, not prevented.
     */
    var reusedSequence = 0L
        private set

    /** The participant's session to write on, online or offline; null while it may not be written to. */
    fun session(participantId: Long): S? {
        val h = byParticipant.get(participantId) ?: return null
        val session = h.session ?: return null
        if (view.isActive(session)) {
            h.online = true
            return session
        }
        return if (h.online) null else session
    }

    /** The participant's startup replay is under way: a message on its offline session is the archive's (§8.17). */
    fun isReplaying(participantId: Long): Boolean = byParticipant.get(participantId)?.replaying ?: false

    /** Any participant's startup replay still under way, retries included. */
    val isAnyReplaying: Boolean
        get() {
            for (i in 0 until all.size) if (all[i].replaying) return true
            return false
        }

    /** Any request or release still waiting for its reply. */
    val isRequesting: Boolean
        get() {
            for (i in 0 until all.size) if (all[i].request != null || all[i].release != null) return true
            return false
        }

    /**
     * The library has the participant's session, from a claim or a request, online or offline. An
     * offline one numbered below what the library last wrote is released and requested again.
     */
    fun acquired(participantId: Long, session: S) {
        val h = held(participantId)
        h.sessionId = view.id(session)
        h.retryAtMs = NO_RETRY
        val active = view.isActive(session)
        if (!active && view.sequenceIndex(session) == h.lastSentIndex && view.lastSent(session) < h.lastSent) {
            staleHandovers++
            log(
                "order entry: participant $participantId: offline session handed back at MsgSeqNum ${view.lastSent(session)}, " +
                    "below the ${h.lastSent} the library wrote; released and requested again (Design.md §6.3)",
            )
            h.session = null
            h.request = null // its reply no longer matters
            h.release = library.releaseToGateway(session, timeoutMs)
            return
        }
        if (active && h.lastSentIndex >= 0 && view.sequenceIndex(session) == h.lastSentIndex && view.lastSent(session) <= h.lastSent) {
            reusedSequence++
            log(
                "order entry: participant $participantId: session handed over online at MsgSeqNum ${view.lastSent(session)}, " +
                    "not above the ${h.lastSent} the library wrote: MsgSeqNums reused, reports may not reach the client (Design.md §8.19)",
            )
        }
        h.session = session
        h.online = active
    }

    /**
     * Requests [sessionId] offline before the acceptor binds (Design.md §6.3), replaying its archived
     * inbound messages from ([replayIndex], [replayFrom]) (§8.17). `SEQUENCE_NUMBER_TOO_HIGH`, nothing
     * past it, hands the session over all the same, and is a success.
     */
    fun requestAtStartup(participantId: Long, sessionId: Long, replayFrom: Int, replayIndex: Int) {
        val h = held(participantId)
        if (h.session != null) return
        h.sessionId = sessionId
        h.replaying = true
        request(h, replayFrom, replayIndex)
    }

    /** The engine took [session] back. Reports wait until it is requested back. */
    fun disconnected(participantId: Long, session: S) {
        val h = byParticipant.get(participantId) ?: return
        if (h.session !== session) return
        h.session = null
        h.online = false
        h.lastSent = view.lastSent(session)
        h.lastSentIndex = view.sequenceIndex(session)
        request(h, view.lastReceived(session) + 1, view.sequenceIndex(session))
    }

    /**
     * The library lost the engine: every session it held is the engine's again. Requested back,
     * replaying as after a disconnect, once the library reconnects ([onLibraryConnect]).
     */
    fun onLibraryDisconnect() {
        for (i in 0 until all.size) {
            val h = all[i]
            val session = h.session ?: continue
            h.session = null
            h.online = false
            h.request = null
            h.release = null
            h.lastSent = view.lastSent(session)
            h.lastSentIndex = view.sequenceIndex(session)
            h.replayFrom = view.lastReceived(session) + 1
            h.replayIndex = view.sequenceIndex(session)
            h.retryAtMs = AFTER_CONNECT
        }
    }

    /** The library has (re)connected. Nothing to do at the first connect: nothing is held yet. */
    fun onLibraryConnect() {
        for (i in 0 until all.size) {
            val h = all[i]
            if (h.retryAtMs == AFTER_CONNECT) h.retryAtMs = 0L
        }
    }

    /** Settles replies, and makes the requests owed. */
    fun doWork(): Int {
        var work = 0
        val now = clock.time()
        for (i in 0 until all.size) {
            val h = all[i]
            val release = h.release
            if (release != null) {
                if (release.isExecuting) continue
                h.release = null
                h.retryAtMs = now + retryMs
                work++
                continue
            }
            val request = h.request
            if (request != null) {
                if (request.isExecuting) continue
                h.request = null
                work++
                if (h.session != null) {
                    h.replaying = false
                } else {
                    log(
                        "order entry: participant ${h.participantId}: requestSession ${h.sessionId} -> " +
                            "${request.state()} ${request.resultIfPresent() ?: request.error() ?: ""}; reports wait, retried in $retryMs ms",
                    )
                    h.retryAtMs = now + retryMs
                }
            } else if (h.session == null && h.retryAtMs < AFTER_CONNECT && now >= h.retryAtMs) {
                request(h, h.replayFrom, h.replayIndex)
                work++
            }
        }
        return work
    }

    private fun request(h: Held<S>, replayFrom: Int, replayIndex: Int) {
        h.replayFrom = replayFrom
        h.replayIndex = replayIndex
        h.retryAtMs = NO_RETRY
        if (h.sessionId == NO_SESSION) return
        h.request = library.requestSession(h.sessionId, replayFrom, replayIndex, timeoutMs)
        requests++
    }

    private fun held(participantId: Long): Held<S> =
        byParticipant.get(participantId) ?: Held<S>(participantId).also {
            byParticipant.put(participantId, it)
            all += it
        }

    private companion object {
        const val NO_RETRY = Long.MAX_VALUE

        /** Held when the library lost the engine: requested back once it reconnects. */
        const val AFTER_CONNECT = Long.MAX_VALUE - 1
        const val NO_SESSION = Session.UNKNOWN.toLong()
    }
}
