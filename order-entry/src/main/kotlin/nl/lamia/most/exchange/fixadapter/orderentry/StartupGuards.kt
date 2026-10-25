package nl.lamia.most.exchange.fixadapter.orderentry

import nl.lamia.most.exchange.fixadapter.orderentry.store.OrderStore
import nl.lamia.most.exchange.fixadapter.session.ConfiguredSessions
import nl.lamia.most.exchange.fixadapter.session.Role
import uk.co.real_logic.artio.engine.SessionInfo

/**
 * The derived-id guards at startup (Design.md §3.2, "Where the guards run"): against Artio's own
 * list of the sessions it knows, after the store is loaded and before the acceptor binds.
 *
 * For each configured order-entry session:
 * - known to Artio: its `sequenceIndex` must not be below the one stored for its participant;
 * - not known (or its `sequenceIndex` still unknown): the store must hold nothing for its
 *   participant. Either way, failing means Artio's state was lost and derived ids would repeat.
 */
internal object StartupGuards {
    fun check(known: List<SessionInfo>, sessions: ConfiguredSessions, store: OrderStore) {
        for (session in sessions.all) {
            if (session.role != Role.ORDER_ENTRY) continue
            val participantId = checkNotNull(session.participantId)
            val info = known.firstOrNull {
                val key = it.sessionKey()
                key != null && key.localCompId() == session.senderCompId && key.remoteCompId() == session.targetCompId
            }
            val sequenceIndex = info?.sequenceIndex() ?: SessionInfo.UNKNOWN_SEQUENCE_INDEX
            if (sequenceIndex == SessionInfo.UNKNOWN_SEQUENCE_INDEX) {
                check(!store.holdsState(participantId)) {
                    "session $session: Artio does not know it, yet the order store holds state for participant " +
                        "$participantId. Artio's state was lost, and derived clOrdIds would repeat (Design.md §3.2). " +
                        "Refusing to start"
                }
            } else {
                store.requireSequenceIndex(participantId, sequenceIndex)
            }
        }
    }
}
