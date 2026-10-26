package nl.lamia.most.exchange.fixadapter.spikes

import io.aeron.Aeron
import io.aeron.AeronCounters
import io.aeron.archive.status.RecordingPos
import io.aeron.driver.status.StreamCounter
import org.agrona.concurrent.status.CountersReader
import org.junit.jupiter.api.io.TempDir
import uk.co.real_logic.artio.library.FixLibrary
import uk.co.real_logic.artio.library.SessionConfiguration
import uk.co.real_logic.artio.session.Session
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Recovery of lost rows, Design.md §8.17 and §8.20 (decided 2026-10-26, Rationale §12), read in
 * Artio 0.182's source and asked here of a real engine:
 *
 * - **(f) the startup replay.** After a restart, an offline `requestSession` for a session nobody is
 *   logged on to, replaying from an earlier (`sequenceIndex`, MsgSeqNum), hands the library the
 *   archived inbound messages from there (`HandoverOfflineFixSession` → `CatchupReplayer`). What
 *   arrives, in which order against the acquisition, marked how, and across a 141=Y reset;
 * - **(g) the gap in R§4.** When the library handles an inbound 35=D, has the archive recorded it?
 *   At `fileSyncLevel=1` the archive's `RecordingPos` passes no unsynced byte (§6.4), so a 35=D
 *   handled ahead of it can be sent to the exchange and then lost to a host crash with its row.
 */
class RecoveryReplaySpikeTest {

    @TempDir
    lateinit var dir: File

    private val port = FixNode.freePort()
    private val acceptorDir get() = File(dir, "acceptor")

    @Test
    fun `(f) an offline requestSession after a restart replays the archived 35=Ds from the MsgSeqNum asked for`() {
        FixNode(File(dir, "initiator"), fileSyncLevel = 0).use { initiator ->
            Library(initiator, "clients").use { clients ->
                pollUntil(clients, what = "client library") { clients.library.isConnected }

                // First life: OE1 logs on, sends five orders, logs out; the adapter shuts down cleanly.
                val sent = firstLife(clients) { client, all -> listOf(sendOrders(client, all, "D", 5)) }.single()

                // Second life: the order-entry library asks for OE1 offline, replaying from D3.
                val from = sent.getValue("D3")
                secondLife(clients, replayIndex = 0, replayFrom = from) { oe, status, all ->
                    println("FINDING (f): requestSession from (0, $from) -> $status")
                    println("FINDING (f): the library saw, in order:\n  " + oe.trace.joinToString("\n  "))
                    val orders = oe.received.values.flatten().filter { it.msgType == "D" }
                    assertEquals(listOf("D3", "D4", "D5"), orders.map { it[11] }, "the 35=Ds from the one asked for")
                    assertEquals(listOf(sent["D3"], sent["D4"], sent["D5"]), orders.map { it.seqNum })
                    assertTrue(oe.trace.first().startsWith("ACQUIRED"), "the session is acquired before its replay arrives")

                    // The session is then the library's offline session as before: written on, and
                    // its client's next Logon follows what was written.
                    val offline = oe.sessions.values.single()
                    val lastSent = offline.lastSentMsgSeqNum()
                    pollUntil(*all, what = "R1 offline") { offline.trySend(Messages.executionReport("R1")) > 0 }
                    val client = await(clients.library.initiate(Messages.logon("OE1", port)), *all, what = "the next logon")
                    pollUntil(*all, what = "R1 resent") { clients.messages(client.id()).any { it[17] == "R1" } }
                    val before = oe.received.values.flatten().size
                    pollUntil(*all, what = "D6 sent") { client.trySend(Messages.newOrder("D6")) > 0 }
                    pollUntil(*all, what = "D6 live") { oe.received.values.flatten().drop(before).any { it[11] == "D6" } }
                    println("FINDING (f): then live: R1 resent at ${clients.messages(client.id()).last { it[17] == "R1" }.seqNum} (written at ${lastSent + 1}); " +
                        "the library saw ${oe.trace.takeLast(4)}")
                    logout(client, *all)
                }
            }
        }
    }

    @Test
    fun `(f) the replay spans a 141=Y reset, each message at its own sequenceIndex`() {
        FixNode(File(dir, "initiator"), fileSyncLevel = 0).use { initiator ->
            Library(initiator, "clients").use { clients ->
                pollUntil(clients, what = "client library") { clients.library.isConnected }

                val (before, after) = firstLife(clients) { client, all ->
                    val a = sendOrders(client, all, "A", 3)
                    logout(client, *all)
                    val reset = clients.library.initiate(
                        SessionConfiguration.builder().address("localhost", port).senderCompId("OE1")
                            .targetCompId(Messages.ACCEPTOR_COMP_ID).credentials("OE1", "secret")
                            .sequenceNumbersPersistent(true).resetSeqNum(true).build(),
                    )
                    val again = await(reset, *all, what = "the 141=Y logon")
                    listOf(a, sendOrders(again, all, "B", 2))
                }

                secondLife(clients, replayIndex = 0, replayFrom = before.getValue("A2")) { oe, status, _ ->
                    println("FINDING (f, reset): requestSession from (0, ${before["A2"]}) -> $status")
                    println("FINDING (f, reset): the library saw, in order:\n  " + oe.trace.joinToString("\n  "))
                    val orders = oe.trace.filter { it.startsWith("35=D") }
                    assertEquals(
                        listOf("A2" to 0, "A3" to 0, "B1" to 1, "B2" to 1),
                        orders.map { Regex("11=(\\S+)").find(it)!!.groupValues[1] to Regex("index=(\\d+)").find(it)!!.groupValues[1].toInt() },
                        "both sequences, each order at its own index ($after)",
                    )
                }
            }
        }
    }

    @Test
    fun `(g) the library handles an inbound 35=D before the fsynced archive has recorded it`() {
        FixNode(acceptorDir, port, fileSyncLevel = 1).use { acceptor ->
            FixNode(File(dir, "initiator"), fileSyncLevel = 0).use { initiator ->
                Aeron.connect(Aeron.Context().aeronDirectoryName(acceptor.aeronDir)).use { aeron ->
                    val counters = aeron.countersReader()
                    var ahead = 0
                    var handled = 0
                    var maxBehind = 0L
                    var counterId = CountersReader.NULL_COUNTER_ID
                    val samples = mutableListOf<String>()
                    val oe = Library(acceptor, "order-entry", claim = { true }, replayFrom = { it + 1 }, onReceived = { _, m, _, _ ->
                        if (m.msgType == "D") {
                            if (counterId == CountersReader.NULL_COUNTER_ID) counterId = inboundRecordingCounter(counters)
                            val recorded = if (counterId == CountersReader.NULL_COUNTER_ID) -1L else counters.getCounterValue(counterId)
                            handled++
                            if (handled <= 3 || handled % 100 == 0) samples += "${m[11]} at ${m.position}, recorded $recorded"
                            if (recorded < m.position) {
                                ahead++
                                maxBehind = maxOf(maxBehind, m.position - recorded)
                            }
                        }
                    })
                    oe.use {
                        Library(initiator, "clients").use { clients ->
                            val all = arrayOf(oe, clients)
                            pollUntil(*all, what = "libraries") { all.all { it.library.isConnected } }
                            val client = await(clients.library.initiate(Messages.logon("OE1", port)), *all, what = "logon")
                            pollUntil(*all, what = "claimed") { oe.session("OE1") != null }
                            // One at a time: the library is polling when each arrives, as the adapter's is.
                            for (k in 1..500) {
                                pollUntil(*all, what = "G$k sent") { client.trySend(Messages.newOrder("G$k")) > 0 }
                                pollUntil(*all, what = "G$k handled") { handled >= k }
                            }
                            println("FINDING (g): counter $counterId '${counters.getCounterLabel(counterId)}'; samples $samples")
                            println("FINDING (g): of $handled 35=Ds, $ahead were handled before the fsynced archive had recorded them " +
                                "(at most $maxBehind bytes ahead)")
                            assertTrue(ahead > 0, "the hazard did not show this run")
                            logout(client, *all)
                        }
                    }
                }
            }
        }
    }

    /** The `RecordingPos` of the engine's inbound stream: the one publication on it in this driver. */
    private fun inboundRecordingCounter(counters: CountersReader): Int {
        var found = CountersReader.NULL_COUNTER_ID
        counters.forEach { counterId, typeId, key, _ ->
            if (typeId == AeronCounters.DRIVER_PUBLISHER_LIMIT_TYPE_ID && key.getInt(StreamCounter.STREAM_ID_OFFSET) == INBOUND_STREAM) {
                val c = RecordingPos.findCounterIdBySession(counters, key.getInt(StreamCounter.SESSION_ID_OFFSET), Aeron.NULL_VALUE.toLong())
                if (c != CountersReader.NULL_COUNTER_ID) found = c
            }
        }
        return found
    }

    /**
     * The adapter's first life: [run] drives OE1 against an order-entry library that claims it with
     * the replay of §2, then the adapter closes cleanly. Returns what [run] returned.
     */
    private fun <T> firstLife(clients: Library, run: (client: Session, all: Array<Library>) -> T): T =
        FixNode(acceptorDir, port, fileSyncLevel = 0).use { acceptor ->
            Library(acceptor, "order-entry", claim = { true }, replayFrom = { it + 1 }).use { oe ->
                val all = arrayOf(oe, clients)
                pollUntil(*all, what = "order-entry library") { oe.library.isConnected }
                val client = await(clients.library.initiate(Messages.logon("OE1", port)), *all, what = "logon")
                pollUntil(*all, what = "claimed") { oe.session("OE1") != null }
                val result = run(client, all)
                val last = clients.sessions.values.last { it.compositeKey().localCompId() == "OE1" }
                if (last.isConnected) logout(last, *all)
                result
            }
        }

    /**
     * The adapter's second life, on the first's archive and logs: a library that claims nothing asks
     * for OE1 offline, before any client is back, replaying from ([replayIndex], [replayFrom]).
     */
    private fun secondLife(
        clients: Library,
        replayIndex: Int,
        replayFrom: Int,
        check: (oe: Library, status: Any, all: Array<Library>) -> Unit,
    ) = FixNode(acceptorDir, port, fileSyncLevel = 0, fresh = false).use { acceptor ->
        Library(acceptor, "order-entry").use { oe ->
            val all = arrayOf(oe, clients)
            pollUntil(*all, what = "order-entry library") { oe.library.isConnected }
            val lookup = acceptor.engine.lookupSessionId(Messages.ACCEPTOR_COMP_ID, "OE1", null, null, null, null)
            pollUntil(*all, what = "lookup") { !lookup.isExecuting }
            val sessionId = checkNotNull(lookup.resultIfPresent()) { "OE1 unknown: ${lookup.state()} ${lookup.error()}" }
            val status = await(oe.library.requestSession(sessionId, replayFrom, replayIndex, Library.TIMEOUT_MS), *all, what = "the offline session")
            // Recovery ends when the reply does (Design §8.17): the replay must all be in by then.
            val atReply = oe.trace.size
            settle(*all)
            println("FINDING (f): ${oe.trace.size - atReply} messages arrived after the reply completed")
            assertEquals(atReply, oe.trace.size, "the replay is complete when the reply is")
            check(oe, status, all)
        }
    }

    /** Sends [count] 35=Ds named [prefix]1.. and waits until the acceptor's library has them all; their MsgSeqNums. */
    private fun sendOrders(client: Session, all: Array<Library>, prefix: String, count: Int): Map<String, Int> {
        val oe = all.first()
        val sent = LinkedHashMap<String, Int>()
        for (k in 1..count) {
            val id = "$prefix$k"
            pollUntil(*all, what = "$id sent") { client.trySend(Messages.newOrder(id)) > 0 }
            sent[id] = client.lastSentMsgSeqNum()
        }
        pollUntil(*all, what = "every $prefix at the acceptor") {
            oe.received.values.flatten().count { it.msgType == "D" && it[11]?.startsWith(prefix) == true } >= count
        }
        return sent
    }

    private fun logout(client: Session, vararg all: Library) {
        pollUntil(*all, what = "logout sent") { client.startLogout() > 0 }
        pollUntil(*all, what = "disconnected") { !client.isConnected }
    }

    private fun settle(vararg libraries: Library) {
        val until = System.nanoTime() + 1_000_000_000L
        while (System.nanoTime() < until) libraries.forEach { it.poll() }
    }

    private companion object {
        const val INBOUND_STREAM = 1 // Artio's DEFAULT_INBOUND_LIBRARY_STREAM, the engine's to its libraries
    }
}
