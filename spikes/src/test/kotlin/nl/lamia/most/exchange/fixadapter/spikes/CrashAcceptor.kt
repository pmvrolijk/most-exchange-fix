package nl.lamia.most.exchange.fixadapter.spikes

import io.aeron.Aeron
import io.aeron.archive.status.RecordingPos
import org.agrona.concurrent.status.CountersReader
import uk.co.real_logic.artio.library.FixLibrary
import java.io.File

/**
 * The acceptor of spike (b), in its own JVM so that it can be killed with SIGKILL. Driven by lines on
 * stdin, answering on stdout:
 *
 * - `<dir> <port> <claim> <fresh>` as arguments. `claim` makes its library take the session, so the
 *   library's own recording carries the logon's admin traffic; without it the session stays
 *   engine-owned and the library's first messages are the reports. `fresh` wipes the archive and logs.
 * - prints `READY` once its library is connected;
 * - on `WRITE <lastSent> <count>`, sends `count` 35=8s on the offline session OE1, as Design §6.3
 *   does: found by its CompIDs (`FixEngine.lookupSessionId`), acquired offline
 *   (`FixLibrary.requestSession`), and numbered by Artio, which it checks against `lastSent`. Waits
 *   until `RecordingPos` passes the last one, and prints `ARCHIVED <lastSeq>`. Then it idles until
 *   killed;
 * - on `QUIT`, closes everything cleanly and prints `CLOSED`.
 */
fun main(args: Array<String>) {
    val dir = File(args[0])
    val port = args[1].toInt()
    val claim = args[2].toBoolean()
    val fresh = args[3].toBoolean()

    val acceptor = FixNode(dir, port, fileSyncLevel = 1, fresh = fresh)
    val oe = Library(acceptor, "order-entry", claim = { claim }, replayFrom = { it + 1 })
    val aeron = Aeron.connect(Aeron.Context().aeronDirectoryName(acceptor.aeronDir))
    pollUntil(oe, what = "library connected") { oe.library.isConnected }
    println("READY")

    val input = System.`in`.bufferedReader()
    var announced = 0
    var disconnects = 0
    while (true) {
        while (!input.ready()) {
            if (oe.poll() == 0) Thread.sleep(1)
            while (announced < oe.announced.size) println("SESSION ${oe.announced[announced++]}")
            while (disconnects < oe.disconnects.size) println("DISCONNECT ${oe.disconnects[disconnects++]}")
        }
        val command = input.readLine()?.split(' ') ?: break
        if (command[0] == "QUIT") {
            oe.close()
            aeron.close()
            acceptor.close()
            println("CLOSED")
            return
        }
        if (command[0] != "WRITE") continue
        val lastSent = command[1].toInt()
        val count = command[2].toInt()

        val lookup = acceptor.engine.lookupSessionId(Messages.ACCEPTOR_COMP_ID, "OE1", null, null, null, null)
        while (lookup.isExecuting) oe.poll()
        val sessionId = checkNotNull(lookup.resultIfPresent()) { "OE1 unknown to the engine: ${lookup.state()} ${lookup.error()}" }
        val session = oe.sessions[sessionId] ?: run {
            val status = await(
                oe.library.requestSession(sessionId, FixLibrary.NO_MESSAGE_REPLAY, FixLibrary.NO_MESSAGE_REPLAY, Library.TIMEOUT_MS),
                oe, what = "the offline session",
            )
            println("OFFLINE requestSession $status")
            oe.sessions.getValue(sessionId)
        }
        println("OFFLINE ${session.state()} lastSent ${session.lastSentMsgSeqNum()} (the client last received $lastSent)")
        var last = 0L
        for (k in 1..count) {
            pollUntil(oe, what = "C$k sent offline") {
                last = session.trySend(Messages.executionReport("C$k"))
                last > 0
            }
        }
        check(session.lastSentMsgSeqNum() == lastSent + count) { "Artio numbered up to ${session.lastSentMsgSeqNum()}" }
        val counters = aeron.countersReader()
        pollUntil(oe, what = "the library's recording counter") {
            Archived.counterId(counters, oe.configuration) != CountersReader.NULL_COUNTER_ID
        }
        val counterId = Archived.counterId(counters, oe.configuration)
        pollUntil(oe, what = "archived") { counters.getCounterValue(counterId) >= last }
        println("ARCHIVED ${lastSent + count} recording ${RecordingPos.getRecordingId(counters, counterId)} position $last")
    }
}
