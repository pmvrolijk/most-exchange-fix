package nl.lamia.most.exchange.fixadapter.session

import nl.lamia.most.exchange.fixadapter.session.TestSessions.CLIENT1
import nl.lamia.most.exchange.fixadapter.session.TestSessions.SECRET1
import org.junit.jupiter.api.io.TempDir
import uk.co.real_logic.artio.Reply
import uk.co.real_logic.artio.session.Session
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Design.md §2: MsgSeqNum is the FIX session's, owned by Artio and persisted across logons and
 * adapter restarts. A Logon must match, or it is refused when too low; only 141=Y resets.
 */
class SequenceTest {

    @TempDir
    lateinit var dir: File

    private val port = AcceptorRig.freePort()
    private val acceptorDir get() = File(dir, "acceptor")

    private fun <T> await(reply: Reply<T>, vararg polled: Polled, what: String): T {
        pollUntil(*polled, what = what) { !reply.isExecuting }
        check(reply.hasCompleted()) { "$what: ${reply.state()} ${reply.error() ?: ""}" }
        return reply.resultIfPresent()
    }

    /** Logs CLIENT1 on and off; returns the client's sequence numbers after the logout. */
    private fun logOnAndOff(rig: AcceptorRig, client: Initiator, initialSent: Int? = null, initialReceived: Int? = null): Pair<Int, Int> {
        val all = arrayOf<Polled>(rig.orderEntry, rig.marketData, client)
        pollUntil(*all, what = "libraries connected") { rig.orderEntry.library.isConnected && client.library.isConnected }
        val session: Session = await(client.logon(port, CLIENT1, "client1", SECRET1, initialSent, initialReceived), *all, what = "logon")
        pollUntil(*all, what = "claimed") { rig.orderEntry.session(CLIENT1) != null }
        pollUntil(*all, what = "logout sent") { session.startLogout() > 0 }
        pollUntil(*all, what = "logged out") { !session.isConnected }
        return session.lastSentMsgSeqNum() to session.lastReceivedMsgSeqNum()
    }

    private fun acceptorLogons(client: Initiator) = client.received.values.flatten().filter { it.msgType == "A" }

    @Test
    fun `MsgSeqNum carries on across a logout and an adapter restart, with no reset`() {
        val (sent, received) = Initiator(File(dir, "initiator")).use { client ->
            val first = AcceptorRig(acceptorDir, port).use { rig -> logOnAndOff(rig, client) }
            println("after the first session the client had sent ${first.first} and received ${first.second}")

            // The adapter restarts on the same directories; the client logs on where it left off.
            AcceptorRig(acceptorDir, port).use { rig -> logOnAndOff(rig, client, first.first + 1, first.second + 1) }
            val logons = acceptorLogons(client)
            println("the acceptor's Logons: $logons")
            assertEquals(listOf(1, first.second + 1), logons.map { it.seqNum }, "the second Logon follows the first session's messages")
            first
        }
        assertTrue(sent >= 2 && received >= 2, "a Logon and a Logout each way")
    }

    @Test
    fun `a Logon below the expected MsgSeqNum is refused`() = Initiator(File(dir, "initiator")).use { client ->
        AcceptorRig(acceptorDir, port).use { rig ->
            val (sent, received) = logOnAndOff(rig, client)
            val all = arrayOf<Polled>(rig.orderEntry, rig.marketData, client)
            val reply = client.logon(port, CLIENT1, "client1", SECRET1, initialSent = sent, initialReceived = received + 1)
            pollUntil(*all, what = "the logon settled") { !reply.isExecuting }
            println("a Logon at $sent, expected ${sent + 1}: ${reply.state()} ${reply.error() ?: ""}")
            assertFalse(reply.hasCompleted(), "refused")
        }
    }

    @Test
    fun `a Logon with 141=Y resets both sides to 1`() = Initiator(File(dir, "initiator")).use { client ->
        AcceptorRig(acceptorDir, port).use { rig ->
            logOnAndOff(rig, client)
            val all = arrayOf<Polled>(rig.orderEntry, rig.marketData, client)
            await(client.logon(port, CLIENT1, "client1", SECRET1, reset = true), *all, what = "logon with 141=Y")
            pollFor(200, *all)
            val logon = acceptorLogons(client).last()
            assertEquals(1, logon.seqNum)
            assertEquals("Y", logon[141])
        }
    }
}
