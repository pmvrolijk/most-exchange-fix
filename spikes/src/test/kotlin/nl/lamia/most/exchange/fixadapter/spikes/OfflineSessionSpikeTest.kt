package nl.lamia.most.exchange.fixadapter.spikes

import org.junit.jupiter.api.io.TempDir
import uk.co.real_logic.artio.library.FixLibrary
import uk.co.real_logic.artio.library.SessionConfiguration
import uk.co.real_logic.artio.session.Session
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * MsgSeqNum is the FIX session's, owned by Artio and persisted (Design.md §2, decided 2026-10-15).
 * So a report for a logged-out client goes through Artio's own `Session`, offline, rather than a
 * `SessionWriter` the adapter numbers: `FixLibrary.requestSession` on a disconnected session returns
 * an offline `Session`, and its `trySend` archives without sending.
 *
 * And the Logon rules of §2: above the expected MsgSeqNum, a resend is asked for; below, refused;
 * 141=Y resets both sides.
 */
class OfflineSessionSpikeTest {

    @TempDir
    lateinit var dir: File

    private val port = FixNode.freePort()

    private fun rig(test: (oe: Library, clients: Library, all: Array<Library>) -> Unit) {
        FixNode(File(dir, "acceptor"), port, fileSyncLevel = 0).use { acceptor ->
            FixNode(File(dir, "initiator"), fileSyncLevel = 0).use { initiator ->
                Library(acceptor, "order-entry", claim = { true }, replayFrom = { it + 1 }).use { oe ->
                    Library(initiator, "clients").use { clients ->
                        val all = arrayOf(oe, clients)
                        pollUntil(*all, what = "libraries connected") { all.all { it.library.isConnected } }
                        test(oe, clients, all)
                    }
                }
            }
        }
    }

    /** Logs OE1 on, sends it R1 live, logs it out; returns the client's session and the acceptor's. */
    private fun onlineThenOut(oe: Library, clients: Library, all: Array<Library>): Pair<Session, Session> {
        val client = await(clients.library.initiate(Messages.logon("OE1", port)), *all, what = "logon")
        pollUntil(*all, what = "claimed") { oe.session("OE1") != null }
        val session = oe.session("OE1")!!
        pollUntil(*all, what = "R1 sent") { session.trySend(Messages.executionReport("R1")) > 0 }
        pollUntil(*all, what = "R1 at the client") { clients.messages(client.id()).any { it[17] == "R1" } }
        pollUntil(*all, what = "logout sent") { client.startLogout() > 0 }
        pollUntil(*all, what = "acceptor saw the disconnect") { session.id() in oe.lastSentAtDisconnect }
        return client to session
    }

    @Test
    fun `reports sent on the offline Session are numbered by Artio and resent at the next logon`() = rig { oe, clients, all ->
        val (client, session) = onlineThenOut(oe, clients, all)
        val lastSent = oe.lastSentAtDisconnect.getValue(session.id())
        println("FINDING (offline): after the logout the library's Session is ${session.state()}, lastSent ${session.lastSentMsgSeqNum()}")

        val status = await(
            oe.library.requestSession(session.id(), FixLibrary.NO_MESSAGE_REPLAY, FixLibrary.NO_MESSAGE_REPLAY, Library.TIMEOUT_MS),
            *all, what = "the offline session",
        )
        val offline = oe.sessions.getValue(session.id())
        println("FINDING (offline): requestSession -> $status; Session ${offline.state()}, lastSent ${offline.lastSentMsgSeqNum()}, " +
            "same object ${offline === session}")
        for (execId in listOf("R2", "R3")) pollUntil(*all, what = "$execId sent offline") { offline.trySend(Messages.executionReport(execId)) > 0 }
        assertEquals(lastSent + 2, offline.lastSentMsgSeqNum(), "Artio numbered them")
        assertFalse(clients.messages(client.id()).any { it[17] == "R2" }, "nothing reaches a client that is not there")

        val before = clients.messages(client.id()).size
        await(clients.library.initiate(Messages.logon("OE1", port)), *all, what = "second logon")
        pollUntil(*all, what = "R2 and R3 at the client") { clients.messages(client.id()).count { it[17] == "R2" || it[17] == "R3" } >= 2 }
        settle(*all)
        val after = clients.messages(client.id()).drop(before)
        println("FINDING (offline): at the next logon the client received $after")
        assertEquals(lastSent + 3, after.first { it.msgType == "A" }.seqNum)
        assertEquals(listOf("R2" to lastSent + 1, "R3" to lastSent + 2), after.filter { it.msgType == "8" }.map { it[17] to it.seqNum })
        assertTrue(after.filter { it.msgType == "8" }.all { it.possDup })

        pollUntil(*all, what = "live again") { oe.sessions.values.any { it.isConnected } }
        val live = oe.sessions.values.first { it.isConnected }
        pollUntil(*all, what = "R4 sent") { live.trySend(Messages.executionReport("R4")) > 0 }
        pollUntil(*all, what = "R4 at the client") { clients.messages(client.id()).any { it[17] == "R4" } }
        assertEquals(lastSent + 4, clients.messages(client.id()).last { it[17] == "R4" }.seqNum)
    }

    @Test
    fun `a Logon above the expected MsgSeqNum is accepted, and the acceptor asks for a resend`() = rig { oe, clients, all ->
        val (client, _) = onlineThenOut(oe, clients, all)
        val expected = client.lastSentMsgSeqNum() + 1
        val reply = clients.library.initiate(logon().initialSentSequenceNumber(expected + 5).build())
        pollUntil(*all, what = "logon") { !reply.isExecuting }
        settle(*all)
        val acceptor = oe.sessions.values.last()
        val received = oe.messages(acceptor.id())
        println("FINDING (logon high): ${reply.state()}; the client received ${clients.messages(client.id()).takeLast(4)}")
        assertTrue(reply.hasCompleted(), "accepted")
        assertTrue(clients.messages(client.id()).any { it.msgType == "2" && it[7] == expected.toString() },
            "a ResendRequest from $expected; acceptor saw $received")
    }

    @Test
    fun `a Logon below the expected MsgSeqNum is refused`() = rig { oe, clients, all ->
        val (client, _) = onlineThenOut(oe, clients, all)
        val reply = clients.library.initiate(logon().initialSentSequenceNumber(1).build())
        pollUntil(*all, what = "logon") { !reply.isExecuting }
        settle(*all)
        val last = clients.messages(client.id()).takeLast(2)
        println("FINDING (logon low): ${reply.state()} ${reply.error() ?: ""}; the client last received $last")
        assertFalse(reply.hasCompleted(), "refused")
    }

    @Test
    fun `a Logon with 141=Y resets both sides to 1`() = rig { oe, clients, all ->
        val (client, session) = onlineThenOut(oe, clients, all)
        val reply = clients.library.initiate(logon().resetSeqNum(true).build())
        pollUntil(*all, what = "logon") { !reply.isExecuting }
        settle(*all)
        val logon = clients.messages(client.id()).last { it.msgType == "A" }
        println("FINDING (141=Y): ${reply.state()}; the acceptor's Logon $logon 141=${logon[141]}; was at ${oe.lastSentAtDisconnect[session.id()]}")
        assertTrue(reply.hasCompleted())
        assertEquals(1, logon.seqNum)
        assertEquals("Y", logon[141])
    }

    private fun logon(): SessionConfiguration.Builder = SessionConfiguration.builder()
        .address("localhost", port)
        .senderCompId("OE1")
        .targetCompId(Messages.ACCEPTOR_COMP_ID)
        .credentials("OE1", "secret")
        .sequenceNumbersPersistent(true)

    private fun settle(vararg libraries: Library) {
        val until = System.nanoTime() + 1_000_000_000L
        while (System.nanoTime() < until) libraries.forEach { it.poll() }
    }
}
