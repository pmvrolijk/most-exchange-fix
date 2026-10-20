package nl.lamia.most.exchange.fixadapter.session

import uk.co.real_logic.artio.Reply
import uk.co.real_logic.artio.library.FixLibrary
import uk.co.real_logic.artio.library.SessionExistsHandler
import uk.co.real_logic.artio.messages.SessionReplyStatus

/**
 * How a session reaches the library of its role (Design.md §2): every library hears of every
 * session the engine accepts, and claims the configured sessions of its own [role].
 *
 * The claim replays from the Logon's MsgSeqNum + 1. Without it, a message the client sends between
 * its Logon and the claim is never delivered, and no gap shows it (spike §8.13(d)).
 *
 * A claim is taken when it comes back `OK`, or `SEQUENCE_NUMBER_TOO_HIGH`: the session is then
 * acquired too, and the client had sent nothing after its Logon to replay, the usual case
 * (`FixLibrary.requestSession`). Any other outcome goes to [onClaimFailed], what to do about it
 * being the backend's: `MISSING_MESSAGES`, for one, acquires the session without its replay. Call
 * [poll] from the library's duty cycle.
 */
class RoleClaims(
    private val sessions: ConfiguredSessions,
    val role: Role,
    private val timeoutMs: Long = 10_000L,
    private val onClaimFailed: (ConfiguredSession, Reply<SessionReplyStatus>) -> Unit,
) : SessionExistsHandler {
    private val pending = ArrayList<Pair<ConfiguredSession, Reply<SessionReplyStatus>>>()

    override fun onSessionExists(
        library: FixLibrary,
        surrogateSessionId: Long,
        localCompId: String,
        localSubId: String?,
        localLocationId: String?,
        remoteCompId: String,
        remoteSubId: String?,
        remoteLocationId: String?,
        logonReceivedSequenceNumber: Int,
        logonSequenceIndex: Int,
    ) {
        val session = sessions.find(senderCompId = localCompId, targetCompId = remoteCompId) ?: return
        if (session.role != role) return
        pending += session to library.requestSession(surrogateSessionId, logonReceivedSequenceNumber + 1, logonSequenceIndex, timeoutMs)
    }

    /** Settles the claims that have finished; returns how many did. */
    fun poll(): Int {
        if (pending.isEmpty()) return 0
        var settled = 0
        val each = pending.iterator()
        while (each.hasNext()) {
            val (session, reply) = each.next()
            if (reply.isExecuting) continue
            each.remove()
            settled++
            if (!reply.hasCompleted() || reply.resultIfPresent() !in TAKEN) onClaimFailed(session, reply)
        }
        return settled
    }

    private companion object {
        val TAKEN = setOf(SessionReplyStatus.OK, SessionReplyStatus.SEQUENCE_NUMBER_TOO_HIGH)
    }
}
