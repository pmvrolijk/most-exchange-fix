package nl.lamia.most.exchange.fixadapter.spikes

import uk.co.real_logic.artio.builder.ResendRequestEncoder
import uk.co.real_logic.artio.library.FixLibrary
import uk.co.real_logic.artio.session.Session
import uk.co.real_logic.artio.messages.SessionState
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Spike (i), Design.md §8.21, decided 2026-10-29: a session whose resend Artio will never complete
 * is rewound to the start of its gap (`Session.tryUpdateLastReceivedSequenceNumber`, which starts a
 * new `sequenceIndex`) and logged out, so that the next Logon asks for the gap again. What it must
 * show before it is built:
 * - the client's next Logon draws a ResendRequest from the rewound number, and what the client
 *   resends reaches the library, valid;
 * - the outbound MsgSeqNum carries on: the acceptor's Logon follows its Logout, it does not restart;
 * - a client's ResendRequest for the acceptor's messages from before the new `sequenceIndex` is
 *   still answered with those messages, not gap-filled over.
 *
 * (i1) a stall, (h4): the client answers a resend short, the session is rewound while logged on.
 * (i2) (h3): the connection ends with the gap open, the session is rewound offline.
 * (i3) as (i2), with the engine restarted between the rewind and the Logon.
 *
 * Found: the first two hold, the third does not. Artio's replayer answers a ResendRequest only from
 * the session's current `sequenceIndex`, so every acceptor message from before the rewind is
 * gap-filled over. A 35=8 the client missed at the disconnect would be lost for good.
 *
 * So, with no rewind, the library asks for the gap itself, with its own ResendRequest. A PossDup
 * message below the expected number reaches the library valid, and a PossDup gap fill there is
 * ignored (`Session.onGapFill`). In a stall, the resend's first message is the end of Artio's own
 * request range, which clears `awaitingResend`:
 * (i4) the stall of (i1), made up while logged on;
 * (i5) the open gap of (i2), made up at the next Logon.
 */
class RewindSpikeTest {

    @TempDir
    lateinit var dir: File

    /** What reached the library, in order: the ClOrdID, `*` if PossDup, the index, and whether valid. */
    private val seen = mutableListOf<Seen>()

    private data class Seen(val clOrdId: String, val possDup: Boolean, val index: Int, val valid: Boolean) {
        override fun toString() = clOrdId + (if (possDup) "*" else "") + "@$index" + (if (valid) "" else " invalid")
    }

    private fun library(node: FixNode) = Library(node, "order-entry", claim = { true }, replayFrom = { it + 1 }) { _, m, index, ok ->
        val id = m[11] ?: return@Library
        seen += Seen(id, m.possDup, index, ok)
    }

    @Test
    fun `(i1) a stalled resend, rewound while logged on, then logged out -- the next Logon asks again`() {
        val port = FixNode.freePort()
        FixNode(File(dir, "i1"), port, fileSyncLevel = 0).use { node ->
            library(node).use { oe ->
                pollUntil(oe, what = "library connected") { oe.library.isConnected }
                val first = RawClient(port)
                first.logon(1)
                pollUntil(oe, what = "the Logon answered") { first.received("A").isNotEmpty() }
                pollUntil(oe, what = "claimed") { oe.session("RAW")?.state() == SessionState.ACTIVE }
                val session = oe.session("RAW")!!
                pollUntil(oe, what = "E1 sent") { session.trySend(Messages.executionReport("E1")) > 0 }
                pollUntil(oe, what = "E1 received") { first.received("8").isNotEmpty() }
                val e1Seq = first.received("8").single()[34]

                // (h4): a gap at 3-4, new orders during the resend, then an answer that stops short.
                first.order(2, "A2")
                first.order(5, "A5")
                pollUntil(oe, what = "the acceptor's ResendRequest") { first.received("2").isNotEmpty() }
                first.order(6, "A6")
                first.order(7, "A7")
                pollFor(oe, 200)
                first.order(3, "A3", possDup = true)
                first.gapFill(4, 5)
                pollFor(oe, 200)
                first.order(8, "A8")
                first.order(9, "A9")
                pollFor(oe, 500)
                assertTrue(session.awaitingResend(), "precondition: the stall of (h4)")
                println("FINDING (i1): stalled -- seen $seen; lastReceived ${session.lastReceivedMsgSeqNum()}, index ${session.sequenceIndex()}, lastSent ${session.lastSentMsgSeqNum()}")

                val indexBefore = session.sequenceIndex()
                pollUntil(oe, what = "the rewind") { session.tryUpdateLastReceivedSequenceNumber(4) > 0 }
                println("FINDING (i1): rewound -- lastReceived ${session.lastReceivedMsgSeqNum()}, index ${session.sequenceIndex()}, awaitingResend ${session.awaitingResend()}, lastSent ${session.lastSentMsgSeqNum()}")
                pollUntil(oe, what = "the Logout") { session.logoutAndDisconnect("resend incomplete".toByteArray()) > 0 }
                pollUntil(oe, what = "the connection closed") { first.closed }
                val logoutSeq = first.received("5").single()[34]!!.toInt()
                first.close()
                pollUntil(oe, what = "the library saw the disconnect") { oe.disconnects.isNotEmpty() }

                val mark = seen.size
                RawClient(port).use { second ->
                    second.logon(10)
                    pollUntil(oe, what = "the second Logon answered") { second.received("A").isNotEmpty() }
                    pollUntil(oe, timeoutMs = 3_000, what = "a ResendRequest at the second Logon") { second.received("2").isNotEmpty() }
                    val request = second.received("2").first()
                    for (seq in 5..9) second.order(seq, "A$seq", possDup = true)
                    second.gapFill(10, 11)
                    pollFor(oe, 300)
                    second.order(11, "A11")
                    pollFor(oe, 300)
                    second.send("2", 12, listOf(7 to "1", 16 to "0"))
                    pollFor(oe, 1_000)

                    val now = oe.session("RAW")!!
                    val after = seen.drop(mark)
                    println("FINDING (i1): second Logon -- its Logon 34=${second.received("A").first()[34]} after the Logout at $logoutSeq; ResendRequest 7=${request[7]} 16=${request[16]}")
                    println("FINDING (i1): after -- seen $after; awaitingResend ${now.awaitingResend()}, index ${now.sequenceIndex()} (was $indexBefore), lastReceived ${now.lastReceivedMsgSeqNum()}")
                    println("FINDING (i1): the client's resend of 1-0 -- ${second.all().drop(2).map { "35=${it[35]} 34=${it[34]}" + (it[43]?.let { " 43=$it" } ?: "") + (it[17]?.let { " 17=$it" } ?: "") + (it[36]?.let { " 36=$it" } ?: "") }}")

                    assertEquals(logoutSeq + 1, second.received("A").first()[34]!!.toInt(), "the outbound MsgSeqNum carries on")
                    assertEquals("5", request[7], "the next Logon asks from the rewound number")
                    assertEquals(listOf("A5*", "A6*", "A7*", "A8*", "A9*", "A11"), after.filter { it.valid }.map { it.clOrdId + if (it.possDup) "*" else "" })
                    assertFalse(now.awaitingResend())
                    assertTrue(after.filter { it.valid }.all { it.index == indexBefore + 1 }, "the resent messages are at the new index")
                    // Artio 0.182: the replayer queries only the session's current index (Replayer.java:226).
                    assertGapFilledOver(second, "E1", e1Seq)
                }
            }
        }
    }

    @Test
    fun `(i2) a connection that ends with its gap open, rewound offline -- the next Logon asks again`() = endsWithGapOpen(File(dir, "i2"), restart = false)

    @Test
    fun `(i3) as (i2), with the engine restarted between the rewind and the Logon`() = endsWithGapOpen(File(dir, "i3"), restart = true)

    private fun endsWithGapOpen(runDir: File, restart: Boolean) {
        val name = if (restart) "i3" else "i2"
        val port = FixNode.freePort()
        var node = FixNode(runDir, port, fileSyncLevel = 0)
        var oe = library(node)
        try {
            pollUntil(oe, what = "library connected") { oe.library.isConnected }
            val first = RawClient(port)
            first.logon(1)
            pollUntil(oe, what = "the Logon answered") { first.received("A").isNotEmpty() }
            pollUntil(oe, what = "claimed") { oe.session("RAW")?.state() == SessionState.ACTIVE }
            val online = oe.session("RAW")!!
            val sessionId = online.id()
            pollUntil(oe, what = "E1 sent") { online.trySend(Messages.executionReport("E1")) > 0 }
            pollUntil(oe, what = "E1 received") { first.received("8").isNotEmpty() }
            val e1Seq = first.received("8").single()[34]

            // (h3): a gap at 3-4, and the connection ends before the client answers the request.
            first.order(2, "A2")
            first.order(5, "A5")
            pollUntil(oe, what = "the acceptor's ResendRequest") { first.received("2").isNotEmpty() }
            first.close()
            pollUntil(oe, what = "the library saw the disconnect") { oe.disconnects.isNotEmpty() }
            println("FINDING ($name): disconnected with the gap open -- seen $seen; lastReceived ${online.lastReceivedMsgSeqNum()}, index ${online.sequenceIndex()}, lastSent ${online.lastSentMsgSeqNum()}")

            val status = await(
                oe.library.requestSession(sessionId, FixLibrary.NO_MESSAGE_REPLAY, FixLibrary.NO_MESSAGE_REPLAY, Library.TIMEOUT_MS),
                oe, what = "the offline session",
            )
            val offline: Session = oe.sessions.getValue(sessionId)
            println("FINDING ($name): offline -- $status, ${offline.state()}, lastReceived ${offline.lastReceivedMsgSeqNum()}, index ${offline.sequenceIndex()}, lastSent ${offline.lastSentMsgSeqNum()}")
            val indexBefore = offline.sequenceIndex()
            pollUntil(oe, what = "the rewind") { offline.tryUpdateLastReceivedSequenceNumber(2) > 0 }
            pollFor(oe, 200)
            println("FINDING ($name): rewound offline -- lastReceived ${offline.lastReceivedMsgSeqNum()}, index ${offline.sequenceIndex()}, lastSent ${offline.lastSentMsgSeqNum()}")
            val lastSent = offline.lastSentMsgSeqNum()

            if (restart) {
                oe.close()
                node.close()
                node = FixNode(runDir, port, fileSyncLevel = 0, fresh = false)
                oe = library(node)
                pollUntil(oe, what = "library connected again") { oe.library.isConnected }
            }

            val mark = seen.size
            RawClient(port).use { second ->
                second.logon(6)
                pollUntil(oe, what = "the second Logon answered") { second.received("A").isNotEmpty() }
                pollUntil(oe, timeoutMs = 3_000, what = "a ResendRequest at the second Logon") { second.received("2").isNotEmpty() }
                val request = second.received("2").first()
                for (seq in 3..5) second.order(seq, "A$seq", possDup = true)
                second.gapFill(6, 7)
                pollFor(oe, 300)
                second.order(7, "A7")
                pollFor(oe, 300)
                second.send("2", 8, listOf(7 to "1", 16 to "0"))
                pollFor(oe, 1_000)

                val now = oe.sessions.getValue(sessionId)
                val after = seen.drop(mark)
                println("FINDING ($name): second Logon -- its Logon 34=${second.received("A").first()[34]}, the last sent before $lastSent; ResendRequest 7=${request[7]} 16=${request[16]}")
                println("FINDING ($name): after -- seen $after; awaitingResend ${now.awaitingResend()}, index ${now.sequenceIndex()} (was $indexBefore), lastReceived ${now.lastReceivedMsgSeqNum()}")
                println("FINDING ($name): the client's resend of 1-0 -- ${second.all().drop(2).map { "35=${it[35]} 34=${it[34]}" + (it[43]?.let { " 43=$it" } ?: "") + (it[17]?.let { " 17=$it" } ?: "") + (it[36]?.let { " 36=$it" } ?: "") }}")

                assertEquals(lastSent + 1, second.received("A").first()[34]!!.toInt(), "the outbound MsgSeqNum carries on")
                assertEquals("3", request[7], "the next Logon asks from the rewound number")
                assertEquals(listOf("A3*", "A4*", "A5*", "A7"), after.filter { it.valid }.map { it.clOrdId + if (it.possDup) "*" else "" })
                assertFalse(now.awaitingResend())
                assertTrue(after.filter { it.valid }.all { it.index == indexBefore + 1 }, "the resent messages are at the new index")
                assertGapFilledOver(second, "E1", e1Seq)
            }
        } finally {
            oe.close()
            node.close()
        }
    }

    @Test
    fun `(i4) a stalled resend, made up by the library's own ResendRequest while logged on`() {
        val port = FixNode.freePort()
        FixNode(File(dir, "i4"), port, fileSyncLevel = 0).use { node ->
            library(node).use { oe ->
                pollUntil(oe, what = "library connected") { oe.library.isConnected }
                RawClient(port).use { client ->
                    client.logon(1)
                    pollUntil(oe, what = "the Logon answered") { client.received("A").isNotEmpty() }
                    pollUntil(oe, what = "claimed") { oe.session("RAW")?.state() == SessionState.ACTIVE }
                    val session = oe.session("RAW")!!
                    pollUntil(oe, what = "E1 sent") { session.trySend(Messages.executionReport("E1")) > 0 }
                    pollUntil(oe, what = "E1 received") { client.received("8").isNotEmpty() }
                    val e1Seq = client.received("8").single()[34]

                    // The stall of (h4), as in (i1).
                    client.order(2, "A2")
                    client.order(5, "A5")
                    pollUntil(oe, what = "the acceptor's ResendRequest") { client.received("2").isNotEmpty() }
                    client.order(6, "A6")
                    client.order(7, "A7")
                    pollFor(oe, 200)
                    client.order(3, "A3", possDup = true)
                    client.gapFill(4, 5)
                    pollFor(oe, 200)
                    client.order(8, "A8")
                    client.order(9, "A9")
                    pollFor(oe, 500)
                    assertTrue(session.awaitingResend(), "precondition: the stall of (h4)")
                    val indexBefore = session.sequenceIndex()

                    // The first message dropped was 5; Artio has counted up to 9.
                    val mark = seen.size
                    val request = ResendRequestEncoder().beginSeqNo(5).endSeqNo(session.lastReceivedMsgSeqNum())
                    pollUntil(oe, what = "the library's ResendRequest") { session.trySend(request) > 0 }
                    pollUntil(oe, what = "it reached the client") { client.received("2").size == 2 }
                    val asked = client.received("2")[1]
                    for (seq in 5..9) client.order(seq, "A$seq", possDup = true)
                    pollFor(oe, 300)
                    client.order(10, "A10")
                    pollFor(oe, 300)
                    client.send("2", 11, listOf(7 to "1", 16 to "0"))
                    pollFor(oe, 1_000)

                    val after = seen.drop(mark)
                    println("FINDING (i4): the library asked 7=${asked[7]} 16=${asked[16]}; after -- seen $after; awaitingResend ${session.awaitingResend()}, index ${session.sequenceIndex()} (was $indexBefore), lastReceived ${session.lastReceivedMsgSeqNum()}, state ${session.state()}")
                    println("FINDING (i4): the client's resend of 1-0 -- ${client.all().drop(2).map { "35=${it[35]} 34=${it[34]}" + (it[43]?.let { " 43=$it" } ?: "") + (it[17]?.let { " 17=$it" } ?: "") + (it[36]?.let { " 36=$it" } ?: "") }}")

                    assertEquals(listOf("A5*", "A6*", "A7*", "A8*", "A9*", "A10"), after.filter { it.valid }.map { it.clOrdId + if (it.possDup) "*" else "" })
                    assertFalse(session.awaitingResend(), "the stall is over")
                    assertEquals(indexBefore, session.sequenceIndex(), "no new index")
                    assertEquals(SessionState.ACTIVE, session.state(), "still logged on")
                    assertResentToClient(client, "E1", e1Seq)
                }
            }
        }
    }

    @Test
    fun `(i5) a gap left open by a disconnect, made up by the library's own ResendRequest at the next Logon`() {
        val port = FixNode.freePort()
        FixNode(File(dir, "i5"), port, fileSyncLevel = 0).use { node ->
            library(node).use { oe ->
                pollUntil(oe, what = "library connected") { oe.library.isConnected }
                val first = RawClient(port)
                first.logon(1)
                pollUntil(oe, what = "the Logon answered") { first.received("A").isNotEmpty() }
                pollUntil(oe, what = "claimed") { oe.session("RAW")?.state() == SessionState.ACTIVE }
                val online = oe.session("RAW")!!
                pollUntil(oe, what = "E1 sent") { online.trySend(Messages.executionReport("E1")) > 0 }
                pollUntil(oe, what = "E1 received") { first.received("8").isNotEmpty() }
                val e1Seq = first.received("8").single()[34]

                // (h3), as in (i2): the last valid was 2, then 5 came and was dropped.
                first.order(2, "A2")
                first.order(5, "A5")
                pollUntil(oe, what = "the acceptor's ResendRequest") { first.received("2").isNotEmpty() }
                first.close()
                pollUntil(oe, what = "the library saw the disconnect") { oe.disconnects.isNotEmpty() }
                val indexBefore = online.sequenceIndex()
                val acquired = oe.trace.count { it.startsWith("ACQUIRED") }

                val mark = seen.size
                RawClient(port).use { second ->
                    second.logon(6)
                    pollUntil(oe, what = "the second Logon answered") { second.received("A").isNotEmpty() }
                    pollUntil(oe, what = "claimed again") { oe.trace.count { it.startsWith("ACQUIRED") } > acquired && oe.session("RAW")?.state() == SessionState.ACTIVE }
                    val session = oe.session("RAW")!!
                    pollFor(oe, 300)
                    val artioAsked = second.received("2").map { "7=${it[7]} 16=${it[16]}" }
                    val request = ResendRequestEncoder().beginSeqNo(3).endSeqNo(5)
                    pollUntil(oe, what = "the library's ResendRequest") { session.trySend(request) > 0 }
                    pollUntil(oe, what = "it reached the client") { second.received("2").size == artioAsked.size + 1 }
                    for (seq in 3..5) second.order(seq, "A$seq", possDup = true)
                    pollFor(oe, 300)
                    second.order(7, "A7")
                    pollFor(oe, 300)
                    second.send("2", 8, listOf(7 to "1", 16 to "0"))
                    pollFor(oe, 1_000)

                    val after = seen.drop(mark)
                    println("FINDING (i5): Artio asked at the Logon: $artioAsked; after -- seen $after; awaitingResend ${session.awaitingResend()}, index ${session.sequenceIndex()} (was $indexBefore), lastReceived ${session.lastReceivedMsgSeqNum()}, state ${session.state()}")
                    println("FINDING (i5): the client's resend of 1-0 -- ${second.all().drop(1).map { "35=${it[35]} 34=${it[34]}" + (it[43]?.let { " 43=$it" } ?: "") + (it[17]?.let { " 17=$it" } ?: "") + (it[36]?.let { " 36=$it" } ?: "") }}")

                    assertEquals(emptyList(), artioAsked, "(h3): Artio forgot the gap")
                    assertEquals(listOf("A3*", "A4*", "A5*", "A7"), after.filter { it.valid }.map { it.clOrdId + if (it.possDup) "*" else "" })
                    assertFalse(session.awaitingResend())
                    assertEquals(indexBefore, session.sequenceIndex(), "no new index")
                    assertEquals(SessionState.ACTIVE, session.state(), "still logged on")
                    assertResentToClient(second, "E1", e1Seq)
                }
            }
        }
    }

    /** The acceptor's report [execId] was not resent to [client]: a gap fill covered its MsgSeqNum. */
    private fun assertGapFilledOver(client: RawClient, execId: String, seq: String?) {
        assertTrue(client.received("8").none { it[17] == execId }, "$execId not resent")
        val fill = client.received("4").single()
        assertTrue(fill[34]!!.toInt() <= seq!!.toInt() && fill[36]!!.toInt() > seq.toInt(), "a gap fill over $seq: $fill")
    }

    /** The acceptor's report [execId] came again to [client] as PossDup under its first MsgSeqNum. */
    private fun assertResentToClient(client: RawClient, execId: String, seq: String?) {
        val again = client.received("8").filter { it[17] == execId && it[43] == "Y" }
        assertEquals(1, again.size, "$execId resent, not gap-filled over")
        assertEquals("Y", again.single()[43])
        assertEquals(seq, again.single()[34])
    }

    private fun pollFor(library: Library, ms: Long) {
        val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms)
        while (System.nanoTime() < until) if (library.poll() == 0) Thread.sleep(1)
    }
}
