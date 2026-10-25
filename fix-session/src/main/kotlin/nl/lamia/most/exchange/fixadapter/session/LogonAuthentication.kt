package nl.lamia.most.exchange.fixadapter.session

import nl.lamia.most.exchange.fixadapter.codecs.builder.LogoutEncoder
import uk.co.real_logic.artio.decoder.AbstractLogonDecoder
import uk.co.real_logic.artio.validation.AuthenticationProxy
import uk.co.real_logic.artio.validation.AuthenticationStrategy
import java.security.MessageDigest

/**
 * Lets a Logon in only when one configured session matches all of it: its CompIDs, its 553, and
 * the SHA-256 of its 554 (Design.md §2, "Configuration and logon"). Anything else gets a Logout
 * that does not say what failed. Runs on the engine's framer thread, once per Logon.
 */
class LogonAuthentication(private val sessions: ConfiguredSessions) : AuthenticationStrategy {

    override fun authenticateAsync(logon: AbstractLogonDecoder, authProxy: AuthenticationProxy) {
        if (authenticate(logon)) {
            authProxy.accept()
        } else {
            // Artio's reject reply, at MsgSeqNum 1 outside the session; one encoder per reject, as Artio asks.
            authProxy.reject(LogoutEncoder().text(REFUSED), LINGER_MS)
        }
    }

    override fun authenticate(logon: AbstractLogonDecoder): Boolean {
        val header = logon.header()
        // The client's SenderCompID is the session's targetCompId; its TargetCompID is the adapter's.
        val session = sessions.find(senderCompId = header.targetCompIDAsString(), targetCompId = header.senderCompIDAsString())
            ?: return false
        if (!logon.hasUsername() || !logon.hasPassword() || logon.usernameAsString() != session.username) return false
        val password = logon.password()
        val bytes = ByteArray(logon.passwordLength()) { password[it].code.toByte() } // FIX is bytes; Artio decodes them one to one
        return MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(bytes), session.passwordSha256)
    }

    companion object {
        const val REFUSED = "Logon refused"

        /** How long the connection stays open after the Logout, for it to leave. */
        const val LINGER_MS = 1_000L
    }
}
