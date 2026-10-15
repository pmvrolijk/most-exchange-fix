package nl.lamia.most.exchange.fixadapter.spikes

import org.junit.jupiter.api.io.TempDir
import uk.co.real_logic.artio.Reply
import uk.co.real_logic.artio.library.FixLibrary
import uk.co.real_logic.artio.session.Session
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Design.md §6.3 under load: the order-entry library never stops writing 35=8s for OE1, while OE1
 * logs out and back on, again and again, sometimes at once. Reports go out live, on the offline
 * `Session` while the client is away, and through the handover in between: the engine hands the
 * reconnecting connection to the library holding the session offline, and that library keeps its
 * own last MsgSeqNum where the engine's index is behind it (`LibraryPoller.setupAcceptorSentSequenceNumber`).
 *
 * With engine-owned acceptance, a disconnect takes the session from the library: `LibraryPoller.onDisconnect`
 * closes it and caches it. `trySend` on that cached `Session` is still accepted, numbered and
 * archived, but a reconnect of a cached session skips the guard above, and the engine numbers
 * the Logon from its index, which lags the library's stream. So the adapter must hold reports after
 * a disconnect until `requestSession` has the session back offline (§6.3).
 *
 * What must hold, doing that: every 35=8 `trySend` accepted reaches the client, at the MsgSeqNum
 * Artio gave it, live or resent with PossDup=Y; no MsgSeqNum is used twice.
 */
class OfflineHandoverSpikeTest {

    @TempDir
    lateinit var dir: File

    private val port = FixNode.freePort()

    @Test
    fun `reports held after a disconnect until the session is requested back each reach the client once, at their MsgSeqNum`() {
        val run = handovers(reacquire = true)
        assertEquals(emptyList(), run.reused, "sends that reused a MsgSeqNum")
        assertEquals(emptySet(), run.missing, "35=8s accepted by trySend that never reached the client")
        assertEquals(emptyMap(), run.wrongSeq, "35=8s that arrived at another MsgSeqNum than they were sent at")
        assertEquals(emptyMap(), run.duplicatesWithoutPossDup, "35=8s that arrived twice without PossDup")
    }

    @Test
    fun `reports sent on the session the library lost at the disconnect reuse MsgSeqNums, and are lost`() {
        val run = handovers(reacquire = false)
        assertTrue(run.reused.isNotEmpty(), "the hazard did not show this run")
        assertTrue(run.missing.isNotEmpty(), "accepted, archived, never delivered")
    }

    private class Run(
        val reused: List<Pair<String, String>>,
        val missing: Set<String>,
        val wrongSeq: Map<String, List<FixMessage>>,
        val duplicatesWithoutPossDup: Map<String, List<FixMessage>>,
    )

    private fun handovers(reacquire: Boolean): Run =
        FixNode(File(dir, "acceptor"), port, fileSyncLevel = 0).use { acceptor ->
            FixNode(File(dir, "initiator"), fileSyncLevel = 0).use { initiator ->
                Library(acceptor, "order-entry", claim = { true }, replayFrom = { it + 1 }).use { oe ->
                    Library(initiator, "clients").use { clients ->
                        val all = arrayOf(oe, clients)
                        pollUntil(*all, what = "libraries connected") { all.all { it.library.isConnected } }
                        val client = await(clients.library.initiate(Messages.logon("OE1", port)), *all, what = "logon")
                        pollUntil(*all, what = "claimed") { oe.session("OE1") != null }
                        val sender = Sender(oe, oe.session("OE1")!!.id(), reacquire)

                        fun pumpUntil(what: String, condition: () -> Boolean) =
                            pollUntil(*all, timeoutMs = 20_000L, what = what) { sender.pump(); condition() }

                        fun pumpFor(ms: Long) {
                            val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms)
                            pumpUntil("$ms ms of sending") { System.nanoTime() >= until }
                        }

                        val logonMillis = ArrayList<Long>()
                        pumpFor(LIVE_MS)
                        OFFLINE_MS.forEachIndexed { cycle, offlineMs ->
                            pumpUntil("logout $cycle sent") { client.startLogout() > 0 }
                            pumpUntil("OE1 out, $cycle") { !client.isConnected && oe.disconnects.size > cycle }
                            pumpFor(offlineMs)
                            val logonAt = System.nanoTime()
                            val reply = clients.library.initiate(Messages.logon("OE1", port))
                            pumpUntil("logon $cycle") { !reply.isExecuting }
                            logonMillis += TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - logonAt)
                            check(reply.hasCompleted()) { "logon $cycle: ${reply.state()} ${reply.error() ?: ""}" }
                            pumpFor(LIVE_MS)
                        }

                        // Stop sending; wait until the client has everything the acceptor sent.
                        pollUntil(*all, what = "the client caught up") {
                            client.isConnected && client.lastReceivedMsgSeqNum() >= sender.session.lastSentMsgSeqNum()
                        }
                        settle(*all)

                        val messages = clients.messages(client.id())
                        val received = messages.filter { it.msgType == "8" }
                        val byExecId = received.groupBy { it[17]!! }
                        val reused = sender.sent.zipWithNext().filter { (a, b) -> b.seq <= a.seq }
                        val run = Run(
                            reused = reused.map { (a, b) -> a.execId to b.execId },
                            missing = sender.sent.map { it.execId }.toSet() - byExecId.keys,
                            wrongSeq = byExecId.filter { (execId, copies) -> copies.any { it.seqNum != sender.seqByExecId[execId] } },
                            duplicatesWithoutPossDup = byExecId.filter { (_, copies) -> copies.drop(1).any { !it.possDup } },
                        )

                        val mode = if (reacquire) "held until requested back" else "sent on the lost session"
                        println("FINDING (handover, $mode): ${OFFLINE_MS.size} logout/logon cycles, offline ${OFFLINE_MS.toList()} ms; " +
                            "logons took ${logonMillis} ms; ${sender.sent.size} 35=8s accepted by trySend, by Session state ${sender.acceptedByState}; " +
                            "requestSession ${sender.requests}, holding reports for ${sender.holdsMicros} µs; trySend refusals ${sender.refusals}")
                        println("FINDING (handover, $mode): the client received ${received.size} 35=8s, " +
                            "${received.count { it.possDup }} resent with PossDup=Y; lastReceived ${client.lastReceivedMsgSeqNum()}, " +
                            "acceptor lastSent ${sender.session.lastSentMsgSeqNum()}; ${reused.size} sends reused a MsgSeqNum; " +
                            "${run.missing.size} never arrived, ${run.wrongSeq.size} at another MsgSeqNum, " +
                            "${run.duplicatesWithoutPossDup.size} twice without PossDup")
                        for ((a, b) in reused.take(2)) {
                            println("  reuse: $a then $b; around it:")
                            sender.timeline.filter { it.first in (a.index - 2)..(b.index + 1) }.forEach { println("    #${it.first} ${it.second}") }
                            println("    the client, around it: " + messages.filter { it.seqNum in (a.seq - 2)..(b.seq + 1) && !it.possDup }
                                .joinToString { "$it 17=${it[17]}" })
                        }
                        run
                    }
                }
            }
        }

    /**
     * Writes R1, R2, … [PERIOD_NS] apart, as the adapter would: never spins on a refusal. With
     * [reacquire], after each disconnect it holds (an ABORT, in the adapter) until `requestSession`
     * has the session back offline. Keeps a timeline of each send and of every change it sees
     * between sends in the Session's state, connection or last MsgSeqNum.
     */
    private class Sender(private val oe: Library, private val sessionId: Long, private val reacquire: Boolean) {
        data class Sent(val index: Int, val execId: String, val seq: Int, val state: String, val connectionId: Long) {
            override fun toString() = "#$index $execId 34=$seq $state conn $connectionId"
        }

        val session: Session get() = oe.sessions.getValue(sessionId)
        val sent = ArrayList<Sent>()
        val seqByExecId = HashMap<String, Int>()
        val acceptedByState = sortedMapOf<String, Int>()
        val refusals = sortedMapOf<Long, Int>()
        val requests = sortedMapOf<String, Int>()
        val timeline = ArrayList<Pair<Int, String>>()
        var held = 0
        val holdsMicros = ArrayList<Long>()
        private var requestedAt = 0L
        private var next = 1
        private var due = System.nanoTime()
        private var disconnectsSeen = 0
        private var request: Reply<*>? = null
        private var seen = observe()

        private fun observe() = Triple(session.state().name, session.connectionId(), session.lastSentMsgSeqNum())

        fun pump() {
            val now = observe()
            if (now != seen) {
                timeline += sent.size to "observed: ${now.first} conn ${now.second} lastSent ${now.third} (was ${seen.first} conn ${seen.second} lastSent ${seen.third})"
                seen = now
            }
            if (reacquire && !owned()) {
                held++
                return
            }
            val time = System.nanoTime()
            if (time < due) return
            val execId = "R$next"
            val position = session.trySend(Messages.executionReport(execId))
            if (position > 0) {
                val record = Sent(sent.size, execId, session.lastSentMsgSeqNum(), session.state().name, session.connectionId())
                sent += record
                timeline += record.index to "sent $record"
                seen = observe()
                seqByExecId[execId] = record.seq
                acceptedByState.merge(record.state, 1, Int::plus)
                next++
                due = time + PERIOD_NS
            } else {
                refusals.merge(position, 1, Int::plus) // retried at the next pump, as an ABORT would be
            }
        }

        /** Whether the library holds the session: after a disconnect, only once `requestSession` says so. */
        private fun owned(): Boolean {
            if (oe.disconnects.size > disconnectsSeen) {
                disconnectsSeen = oe.disconnects.size
                timeline += sent.size to "disconnect seen: requestSession"
                requestedAt = System.nanoTime()
                request = oe.library.requestSession(sessionId, FixLibrary.NO_MESSAGE_REPLAY, FixLibrary.NO_MESSAGE_REPLAY, Library.TIMEOUT_MS)
            }
            val pending = request ?: return true
            if (pending.isExecuting) return false
            val outcome = if (pending.hasCompleted()) "${pending.resultIfPresent()}" else "${pending.state()} ${pending.error() ?: ""}"
            requests.merge(outcome, 1, Int::plus)
            holdsMicros += TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - requestedAt)
            timeline += sent.size to "requestSession -> $outcome"
            request = null
            return true
        }
    }

    private fun settle(vararg libraries: Library) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (System.nanoTime() < until) libraries.forEach { it.poll() }
    }

    private companion object {
        /** How long the client stays away each cycle: 0 logs straight back on, racing the request. */
        val OFFLINE_MS = longArrayOf(200, 0, 50, 0, 200, 1, 100, 0, 200, 5)
        const val LIVE_MS = 300L
        const val PERIOD_NS = 200_000L // 5,000 reports a second
    }
}
