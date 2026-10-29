package nl.lamia.most.exchange.fixadapter.spikes

import uk.co.real_logic.artio.builder.HeartbeatEncoder
import uk.co.real_logic.artio.library.FixLibrary
import uk.co.real_logic.artio.session.Session
import java.io.File

/**
 * The acceptor of spike (h), Design.md §8.21, in its own JVM so that it can be SIGKILLed. Its library
 * claims OE1 at its Logon, replaying from the Logon + 1, as the adapter's `RoleClaims` does. Driven by
 * lines on stdin, answering on stdout:
 *
 * - `<dir> <port> <fresh>` as arguments; prints `READY` once its library is connected;
 * - `STREAM <micros>`: writes a 35=8 on the claimed session every `micros`, until killed;
 * - `STATE`: the session as the restarted engine knows it, before any Logon: looked up, requested
 *   offline, and printed as `STATE lastSent=<n> lastReceived=<m>`;
 * - `SKIP <k> <write>`: on that offline session, `lastSentMsgSeqNum(lastSent + k)`, and with `write`
 *   true a Heartbeat after it, so the number reaches the archive and the engine's index. Prints
 *   `SKIPPED lastSent=<n>`;
 * - `QUIT`: closes cleanly, prints `CLOSED`.
 */
fun main(args: Array<String>) {
    val dir = File(args[0])
    val port = args[1].toInt()
    val fresh = args[2].toBoolean()

    val acceptor = FixNode(dir, port, fileSyncLevel = 1, fresh = fresh)
    val oe = Library(acceptor, "order-entry", claim = { true }, replayFrom = { it + 1 })
    pollUntil(oe, what = "library connected") { oe.library.isConnected }
    println("READY")

    val input = System.`in`.bufferedReader()
    var streaming = false
    var intervalNs = 0L
    var nextAt = 0L
    var streamed = 0
    var offline: Session? = null
    var disconnects = 0
    while (true) {
        while (!input.ready()) {
            var work = oe.poll()
            if (streaming && System.nanoTime() >= nextAt) {
                val session = oe.session("OE1")
                if (session != null && session.isActive && session.trySend(Messages.executionReport("S${streamed + 1}")) > 0) {
                    streamed++
                    nextAt += intervalNs
                    work++
                }
            }
            while (disconnects < oe.disconnects.size) println("DISCONNECT ${oe.disconnects[disconnects++]}")
            if (work == 0) Thread.onSpinWait()
        }
        val command = input.readLine()?.split(' ') ?: break
        when (command[0]) {
            "QUIT" -> {
                oe.close()
                acceptor.close()
                println("CLOSED")
                return
            }
            "STREAM" -> {
                intervalNs = command[1].toLong() * 1_000L
                nextAt = System.nanoTime()
                streaming = true
                println("STREAMING")
            }
            "STATE" -> {
                val lookup = acceptor.engine.lookupSessionId(Messages.ACCEPTOR_COMP_ID, "OE1", null, null, null, null)
                while (lookup.isExecuting) oe.poll()
                val sessionId = checkNotNull(lookup.resultIfPresent()) { "OE1 unknown to the engine: ${lookup.state()} ${lookup.error()}" }
                val status = await(
                    oe.library.requestSession(sessionId, FixLibrary.NO_MESSAGE_REPLAY, FixLibrary.NO_MESSAGE_REPLAY, Library.TIMEOUT_MS),
                    oe, what = "the offline session",
                )
                val session = oe.sessions.getValue(sessionId)
                offline = session
                println("STATE lastSent=${session.lastSentMsgSeqNum()} lastReceived=${session.lastReceivedMsgSeqNum()} request=$status")
            }
            "SKIP" -> {
                val session = checkNotNull(offline) { "STATE first" }
                val k = command[1].toInt()
                session.lastSentMsgSeqNum(session.lastSentMsgSeqNum() + k)
                if (command[2].toBoolean()) {
                    pollUntil(oe, what = "the heartbeat written offline") { session.trySend(HeartbeatEncoder()) > 0 }
                }
                // Let the archive and the engine's indexer see it.
                val until = System.nanoTime() + 1_000_000_000L
                while (System.nanoTime() < until) oe.poll()
                println("SKIPPED lastSent=${session.lastSentMsgSeqNum()}")
            }
        }
    }
}
