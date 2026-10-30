package nl.lamia.most.exchange.fixadapter.spikes

import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Spike (h4), Design.md §8.21: what an accepted session does with a client's new messages that
 * arrive while the acceptor is waiting for a resend. Artio 0.182's `Session.checkSeqNoChange` marks
 * a message above the requested range invalid, which the adapter drops (non-negotiable 10), but
 * `checkNormalSeqNoChange` still counts it as received. A gap fill that then ends below that count
 * leaves `awaitingResend` set, and every later message invalid. Read from the source, checked here.
 *
 * A raw-socket client, so every MsgSeqNum is the test's:
 * 1. Logon 1, 35=D A2 at 2;
 * 2. 35=D A5 at 5: a gap at 3 and 4, so the acceptor asks for a resend;
 * 3. 35=D A6 and A7, new, before answering it, as a client that sends straight after its Logon does;
 * 4. the answer: A3 as PossDup, a gap fill over 4, and, if [complete], A5, A6, A7 again as PossDup
 *    (with EndSeqNo 0, FIX asks for everything up to the client's last); otherwise nothing more, as
 *    the e2e's Artio initiator answered;
 * 5. 35=D A8 and A9, new.
 *
 * Each of Artio's settings for accepted sessions: the default (an open-ended request),
 * `acceptedSessionClosedResendInterval`, and `acceptedSessionSendRedundantResendRequests`.
 */
class ResendStallSpikeTest {

    @TempDir
    lateinit var dir: File

    @Test
    fun `(h4) new messages during a resend -- which reach the library, by Artio setting and client`() {
        val results = mutableListOf<String>()
        val outcomes = mutableListOf<Triple<String, Boolean, Outcome>>()
        for ((name, configure) in listOf<Pair<String, uk.co.real_logic.artio.engine.EngineConfiguration.() -> Unit>>(
            "default" to {},
            "closedResendInterval" to { acceptedSessionClosedResendInterval(true) },
            "sendRedundantResendRequests" to { acceptedSessionSendRedundantResendRequests(true) },
        )) {
            for (complete in listOf(true, false)) {
                val outcome = run(File(dir, "$name-$complete"), configure, complete)
                outcomes += Triple(name, complete, outcome)
                results += "$name, ${if (complete) "complete" else "short"} resend: $outcome"
            }
        }
        results.forEach { println("FINDING (h4): $it") }
        for ((name, complete, o) in outcomes) {
            if (complete) {
                // A resend up to the client's last message: every order reaches the library once, valid.
                assertEquals(listOf("A2", "A3", "A5", "A6", "A7", "A8", "A9"), o.valid.map { it.trimEnd('*') }, "$name, complete")
                assertFalse(o.awaitingResend, "$name, complete")
            } else {
                // Artio 0.182: stalled. What came during the resend was counted as received but never
                // valid, and so is everything after it, until the client reconnects.
                assertEquals(listOf("A2", "A3"), o.valid.map { it.trimEnd('*') }, "$name, short")
                assertTrue(o.awaitingResend, "$name, short: still awaiting the resend")
            }
        }
    }

    private class Outcome(val valid: List<String>, val invalid: List<String>, val requests: List<String>, val awaitingResend: Boolean, val lastReceived: Int?) {
        override fun toString() = "valid $valid, invalid $invalid; ResendRequests $requests; awaitingResend $awaitingResend, lastReceived $lastReceived"
    }

    private fun run(runDir: File, configure: uk.co.real_logic.artio.engine.EngineConfiguration.() -> Unit, complete: Boolean): Outcome {
        val port = FixNode.freePort()
        FixNode(runDir, port, fileSyncLevel = 0, configure = configure).use { acceptor ->
            val valid = mutableListOf<String>()
            val invalid = mutableListOf<String>()
            Library(acceptor, "order-entry", claim = { true }, replayFrom = { it + 1 }) { _, m, _, ok ->
                val id = m[11] ?: return@Library
                if (ok) valid += id + (if (m.possDup) "*" else "") else invalid += id + (if (m.possDup) "*" else "")
            }.use { oe ->
                pollUntil(oe, what = "library connected") { oe.library.isConnected }
                RawClient(port).use { client ->
                    client.send("A", 1, listOf(98 to "0", 108 to "30"))
                    pollUntil(oe, what = "the Logon answered") { client.received("A").isNotEmpty() }
                    pollUntil(oe, what = "claimed") { oe.session("RAW") != null }
                    client.order(2, "A2")
                    client.order(5, "A5")
                    pollUntil(oe, what = "the acceptor's ResendRequest") { client.received("2").isNotEmpty() }
                    client.order(6, "A6")
                    client.order(7, "A7")
                    pollFor(oe, 200)
                    client.order(3, "A3", possDup = true)
                    client.send("4", 4, listOf(123 to "Y", 36 to "5"), possDup = true)
                    if (complete) {
                        client.order(5, "A5", possDup = true)
                        client.order(6, "A6", possDup = true)
                        client.order(7, "A7", possDup = true)
                    }
                    pollFor(oe, 200)
                    client.order(8, "A8")
                    client.order(9, "A9")
                    pollFor(oe, 1_500)
                    val session = oe.session("RAW")
                    val requests = client.received("2").map { "7=${it[7]} 16=${it[16]}" }
                    return Outcome(valid.toList(), invalid.toList(), requests, session?.awaitingResend() ?: false, session?.lastReceivedMsgSeqNum())
                }
            }
        }
    }

    private fun pollFor(library: Library, ms: Long) {
        val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms)
        while (System.nanoTime() < until) if (library.poll() == 0) Thread.sleep(1)
    }
}
