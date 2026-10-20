package nl.lamia.most.exchange.fixadapter.session

import nl.lamia.most.exchange.fixadapter.session.TestSessions.ADAPTER
import nl.lamia.most.exchange.fixadapter.session.TestSessions.CLIENT1
import nl.lamia.most.exchange.fixadapter.session.TestSessions.FEED1
import nl.lamia.most.exchange.fixadapter.session.TestSessions.FEED_SECRET
import nl.lamia.most.exchange.fixadapter.session.TestSessions.SECRET1
import nl.lamia.most.exchange.fixadapter.session.TestSessions.SECRET2
import org.junit.jupiter.api.io.TempDir
import uk.co.real_logic.artio.OrdType
import uk.co.real_logic.artio.Reply
import uk.co.real_logic.artio.Side
import uk.co.real_logic.artio.builder.NewOrderSingleEncoder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Design.md §2, "Sessions and participants" and "Configuration and logon", against the real
 * acceptor: who is let in, what a refused client sees, and which library a session reaches.
 */
class LogonTest {

    @TempDir
    lateinit var dir: File

    private val port = AcceptorRig.freePort()

    private fun rig(test: (AcceptorRig, Initiator, Array<Polled>) -> Unit) =
        AcceptorRig(File(dir, "acceptor"), port).use { rig ->
            Initiator(File(dir, "initiator")).use { client ->
                val all = arrayOf<Polled>(rig.orderEntry, rig.marketData, client)
                pollUntil(*all, what = "libraries connected") {
                    rig.orderEntry.library.isConnected && rig.marketData.library.isConnected && client.library.isConnected
                }
                test(rig, client, all)
            }
        }

    private fun <T> await(reply: Reply<T>, vararg polled: Polled, what: String): T {
        pollUntil(*polled, what = what) { !reply.isExecuting }
        check(reply.hasCompleted()) { "$what: ${reply.state()} ${reply.error() ?: ""}" }
        return reply.resultIfPresent()
    }

    @Test
    fun `a configured client with its credentials is accepted, and reaches the library of its role only`() = rig { rig, client, all ->
        await(client.logon(port, CLIENT1, "client1", SECRET1), *all, what = "order-entry logon")
        await(client.logon(port, FEED1, "feed1", FEED_SECRET), *all, what = "market-data logon")
        pollUntil(*all, what = "both claimed") { rig.orderEntry.session(CLIENT1) != null && rig.marketData.session(FEED1) != null }
        pollFor(200, *all)

        assertNull(rig.orderEntry.session(FEED1), "the market-data session never reaches order entry")
        assertNull(rig.marketData.session(CLIENT1), "the order-entry session never reaches market data")
        assertEquals(listOf(CLIENT1, FEED1), rig.orderEntry.announced, "every library hears of every session")
        assertEquals(emptyList(), rig.orderEntry.claimFailures + rig.marketData.claimFailures)
    }

    @Test
    fun `a 35=D sent between the Logon and the claim reaches the order-entry library`() = rig { rig, client, all ->
        // Only the client polls: the engine answers the Logon, and the library has not yet heard of
        // the session, so it cannot claim it before the 35=D is in.
        val session = await(client.logon(port, CLIENT1, "client1", SECRET1), client, what = "logon")
        val order = NewOrderSingleEncoder().apply {
            clOrdID("FIRST")
            instrument().symbol("SPIKE")
            side(Side.BUY)
            transactTime("20261015-12:00:00.000".toByteArray())
            orderQtyData().orderQty(10, 0)
            ordType(OrdType.LIMIT)
            price(100_25000000L, 8)
        }
        pollUntil(client, what = "the 35=D sent") { session.trySend(order) > 0 }
        pollFor(500, client) // the engine receives and archives it
        assertEquals(emptyList(), rig.orderEntry.sessions.values.toList(), "not claimed yet")

        pollUntil(*all, what = "the 35=D at order entry") {
            rig.orderEntry.received.values.flatten().any { it.msgType == "D" && it[11] == "FIRST" }
        }
        assertEquals(emptyList(), rig.orderEntry.claimFailures)
    }

    @Test
    fun `a Logon is refused with a Logout that does not say why, unless one configured session matches all of it`() = rig { rig, _, all ->
        val refusals = mapOf(
            "the wrong password" to rawLogon(port, CLIENT1, ADAPTER, "client1", "not-the-secret", *all),
            "an unknown client CompID" to rawLogon(port, "STRANGER", ADAPTER, "client1", SECRET1, *all),
            "the wrong adapter CompID" to rawLogon(port, CLIENT1, "OTHER", "client1", SECRET1, *all),
            "another session's username and password" to rawLogon(port, CLIENT1, ADAPTER, "client2", SECRET2, *all),
            "the right password under another username" to rawLogon(port, CLIENT1, ADAPTER, "client2", SECRET1, *all),
            "no username or password" to rawLogon(port, CLIENT1, ADAPTER, null, null, *all),
        )
        pollFor(200, *all)
        for ((case, reply) in refusals) {
            println("refused, $case: $reply")
            assertEquals(listOf("5"), reply.messages.map { it.msgType }, "$case: a Logout and nothing else")
            assertEquals("Logon refused", reply.messages.single()[58], case)
            assertTrue(reply.closed, "$case: then the connection is closed")
        }
        assertEquals(emptyList(), rig.orderEntry.announced + rig.marketData.announced, "no refused session reaches a library")

        // The same Logon, byte for byte, with what the session is configured with: let in.
        val accepted = rawLogon(port, CLIENT1, ADAPTER, "client1", SECRET1, *all)
        assertEquals("A", accepted.messages.firstOrNull()?.msgType, "the control case: $accepted")
    }
}
