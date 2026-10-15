package nl.lamia.most.exchange.fixadapter.spikes

import org.junit.jupiter.api.io.TempDir
import uk.co.real_logic.artio.library.FixLibrary
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Spike (d), Design.md §8.13: can one Artio engine hand order-entry sessions to one library and
 * market-data sessions to another (§3.5, §4)? Accepted sessions start engine-owned; every library
 * hears of each one (`onSessionExists`) and claims its own with `requestSession`.
 *
 * The second question is the startup-ordering one: what becomes of a 35=D the client sends after its
 * logon but before the library has claimed the session.
 */
class RoutingSpikeTest {

    @TempDir
    lateinit var dir: File

    private val port = FixNode.freePort()

    @Test
    fun `one engine routes each session to the library of its role`() {
        FixNode(File(dir, "acceptor"), port, fileSyncLevel = 0).use { acceptor ->
            FixNode(File(dir, "initiator"), fileSyncLevel = 0).use { initiator ->
                Library(acceptor, "order-entry", claim = { it.startsWith("OE") }).use { oe ->
                    Library(acceptor, "market-data", claim = { it.startsWith("MD") }).use { md ->
                        Library(initiator, "clients").use { clients ->
                            val all = arrayOf(oe, md, clients)
                            pollUntil(*all, what = "libraries connected") { all.all { it.library.isConnected } }

                            await(clients.library.initiate(Messages.logon("OE1", port)), *all, what = "OE1 logon")
                            await(clients.library.initiate(Messages.logon("MD1", port)), *all, what = "MD1 logon")
                            pollUntil(*all, what = "both sessions claimed") { oe.sessions.size == 1 && md.sessions.size == 1 }

                            assertEquals(listOf("OE1"), oe.sessions.values.map { it.compositeKey().remoteCompId() })
                            assertEquals(listOf("MD1"), md.sessions.values.map { it.compositeKey().remoteCompId() })
                            assertEquals(setOf("OE1", "MD1"), oe.announced.toSet(), "every library hears of every session")

                            // And the claimed session is live in its library: an order reaches it.
                            val client = clients.session("MOST")!!
                            pollUntil(*all, what = "35=D sent") { client.trySend(Messages.newOrder("A1")) > 0 }
                            val oeSession = oe.session("OE1")!!
                            pollUntil(*all, what = "35=D at the order-entry library") {
                                oe.messages(oeSession.id()).any { it.msgType == "D" }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `a 35=D sent before the claim is silently lost to a library that claims without a replay`() {
        // FINDING 2026-10-15: the session comes over with lastReceived past the 35=D, so no gap is
        // ever seen. The client is never answered, and nothing says so.
        val (orders, lastReceived) = orderBeforeClaim(replayFrom = { FixLibrary.NO_MESSAGE_REPLAY })
        assertEquals(emptyList(), orders)
        assertEquals(2, lastReceived, "the logon was 1, the lost 35=D 2")
    }

    @Test
    fun `a 35=D sent before the claim is delivered when the claim replays from after the logon`() {
        val (orders, _) = orderBeforeClaim(replayFrom = { logonSeqNum -> logonSeqNum + 1 })
        assertEquals(listOf("EARLY"), orders)
    }

    /** The ClOrdIDs of the 35=Ds the library received, and its session's last received MsgSeqNum. */
    private fun orderBeforeClaim(replayFrom: (Int) -> Int): Pair<List<String?>, Int> {
        FixNode(File(dir, "acceptor"), port, fileSyncLevel = 0).use { acceptor ->
            FixNode(File(dir, "initiator"), fileSyncLevel = 0).use { initiator ->
                Library(acceptor, "order-entry", claim = { true }, replayFrom = replayFrom).use { oe ->
                    Library(initiator, "clients").use { clients ->
                        pollUntil(oe, clients, what = "libraries connected") { oe.library.isConnected && clients.library.isConnected }
                        // Only the client polls: the engine answers the logon, the library hears nothing yet.
                        val client = await(clients.library.initiate(Messages.logon("OE1", port)), clients, what = "logon")
                        pollUntil(clients, what = "35=D sent") { client.trySend(Messages.newOrder("EARLY")) > 0 }
                        val until = System.nanoTime() + 500_000_000L
                        while (System.nanoTime() < until) clients.poll() // let the engine receive and archive it

                        pollUntil(oe, clients, what = "session claimed") { oe.sessions.isNotEmpty() }
                        val session = oe.sessions.values.single()
                        val settleUntil = System.nanoTime() + 1_000_000_000L
                        while (System.nanoTime() < settleUntil) { oe.poll(); clients.poll() }

                        println("FINDING (d): the library got ${oe.messages(session.id())}; " +
                            "session lastReceived=${session.lastReceivedMsgSeqNum()}")
                        return oe.messages(session.id()).filter { it.msgType == "D" }.map { it[11] } to
                            session.lastReceivedMsgSeqNum()
                    }
                }
            }
        }
    }
}
