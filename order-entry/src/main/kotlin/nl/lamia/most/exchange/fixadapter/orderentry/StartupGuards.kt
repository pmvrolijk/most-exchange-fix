package nl.lamia.most.exchange.fixadapter.orderentry

import nl.lamia.most.exchange.fixadapter.orderentry.store.OrderStore
import nl.lamia.most.exchange.fixadapter.session.ConfiguredSessions
import nl.lamia.most.exchange.fixadapter.session.Role
import uk.co.real_logic.artio.engine.SessionInfo

/**
 * Finds Artio's state lost (Design.md §3.2, "Artio's state lost"): against Artio's own list of the
 * sessions it knows, after the store is loaded and before the acceptor binds. Reads only; the backend
 * starts each participant's new epoch.
 *
 * For each configured order-entry session:
 * - not known to Artio (or its `sequenceIndex` still unknown), while the store holds a position of
 *   the participant's epoch;
 * - known, with a `sequenceIndex` below the one stored for the participant.
 *
 * A received MsgSeqNum below the high-water at the same `sequenceIndex` is not a loss: it is what a
 * host crash leaves when RocksDB kept a row the archive lost, and the startup replay rebuilds from
 * there (§8.17). A new epoch would forget the receive gap and replay the archive from its start.
 */
internal object StartupGuards {

    /** [participantId]'s Artio state, found lost; [sessionId] the session Artio knows for it, if it does. */
    class Loss(val participantId: Long, val sessionId: Long, val why: String) {
        /** Artio knows the session: the epoch starts at its position once it is handed over. */
        val isKnown: Boolean get() = sessionId != NOT_KNOWN
    }

    const val NOT_KNOWN = SessionInfo.UNK_SESSION.toLong()

    fun losses(known: List<SessionInfo>, sessions: ConfiguredSessions, store: OrderStore): List<Loss> {
        val losses = ArrayList<Loss>()
        for (session in sessions.all) {
            if (session.role != Role.ORDER_ENTRY) continue
            val participantId = checkNotNull(session.participantId)
            val info = known.firstOrNull {
                val key = it.sessionKey()
                key != null && key.localCompId() == session.senderCompId && key.remoteCompId() == session.targetCompId
            }
            val sequenceIndex = info?.sequenceIndex() ?: SessionInfo.UNKNOWN_SEQUENCE_INDEX
            val stored = store.highestSequenceIndex(participantId)
            if (sequenceIndex == SessionInfo.UNKNOWN_SEQUENCE_INDEX) {
                if (store.holdsPositions(participantId)) {
                    losses += Loss(participantId, NOT_KNOWN, "session $session: Artio does not know it, yet the order store holds its positions")
                }
            } else if (sequenceIndex < stored) {
                losses += Loss(participantId, info!!.sessionId(), "session $session: Artio knows it at sequenceIndex $sequenceIndex, below the stored $stored")
            }
        }
        return losses
    }
}
