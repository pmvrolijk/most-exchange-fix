package nl.lamia.most.exchange.fixadapter.spikes

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import uk.co.real_logic.artio.library.SessionConfiguration
import uk.co.real_logic.artio.session.Session
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spike (h), Design.md §8.21: after a SIGKILL, does the restarted engine reuse a MsgSeqNum the client
 * has already seen, and can the adapter move the number on past it before the client logs on?
 *
 * Archiving is off the send path (§6.4): Artio's framer writes the library's message to the socket,
 * and the archive records the same stream on its own. A message can reach the client and die with
 * the process before the archive has it. The restarted engine numbers from what its indexer finds in
 * the archive.
 */
class SequenceReuseSpikeTest {

    @TempDir
    lateinit var dir: File

    private val port = FixNode.freePort()

    @Test
    fun `(h1) a SIGKILL while streaming -- the restarted engine numbers below what the client received`() {
        crashWhileStreaming(skip = null)
    }

    @Test
    fun `(h2a) moving the offline session's lastSentMsgSeqNum on, without writing -- what the Logon is numbered`() {
        crashWhileStreaming(skip = SKIP to false)
    }

    @Test
    fun `(h2b) moving lastSentMsgSeqNum on and writing a Heartbeat offline -- the client sees a gap, not a reuse`() {
        crashWhileStreaming(skip = SKIP to true)
    }

    /**
     * (h3), no crash: a client's Logon leaves a gap in what it sent, and the acceptor's Logon is below
     * what the client expects, so the client logs out at once. At the next Logon, does the acceptor
     * still ask for the gap?
     */
    @Test
    fun `(h3) a gap the acceptor asked for in a session the client ended at once -- asked for again at the next Logon`() {
        FixNode(File(dir, "acceptor"), port).use { acceptor ->
            Library(acceptor, "order-entry", claim = { true }, replayFrom = { it + 1 }).use { oe ->
                FixNode(File(dir, "initiator"), fileSyncLevel = 0).use { initiator ->
                    Library(initiator, "clients").use { clients ->
                        val all = arrayOf(oe, clients)
                        pollUntil(*all, what = "libraries connected") { oe.library.isConnected && clients.library.isConnected }

                        // 1. A plain session: a Logon, one 35=D, a Logout.
                        val first = await(clients.library.initiate(Messages.logon("OE1", port)), *all, what = "logon 1")
                        pollUntil(*all, what = "A1 sent") { first.trySend(Messages.newOrder("A1")) > 0 }
                        pollUntil(*all, what = "A1 received") { oe.received.values.flatten().any { it[11] == "A1" } }
                        logout(first, *all)
                        val clientSent = first.lastSentMsgSeqNum()
                        val clientReceived = first.lastReceivedMsgSeqNum()
                        println("FINDING (h3): after session 1 the client has sent $clientSent and received $clientReceived")

                        // 2. The client skips one MsgSeqNum (a 35=D the acceptor never got) and expects
                        // more than the acceptor sent: as after the adapter's crash, it logs out at once.
                        val before = clients.received.values.sumOf { it.size }
                        val second = clients.library.initiate(
                            logon(initialSent = clientSent + 2, initialReceived = clientReceived + 5),
                        )
                        pollUntil(*all, what = "logon 2 settled") { !second.isExecuting }
                        pollFor(2_000L, *all)
                        println("FINDING (h3): logon 2 ${second.state()} ${second.error() ?: ""}")
                        println("FINDING (h3): the client got ${clients.received.values.flatten().drop(before)}")
                        println("FINDING (h3): the acceptor's library saw ${oe.trace.takeLast(6)}")
                        val acceptorSession = oe.session("OE1")
                        println("FINDING (h3): the acceptor's session expects ${acceptorSession?.lastReceivedMsgSeqNum()?.plus(1)} next; it has sent ${acceptorSession?.lastSentMsgSeqNum()}")

                        // 3. The client logs on again, accepting the acceptor's numbers.
                        val sentNow = clientSent + 3 // its Logon at +2, then its Logout at +3
                        val mid = clients.received.values.sumOf { it.size }
                        val traceMid = oe.trace.size
                        val third = clients.library.initiate(logon(initialSent = sentNow + 1, initialReceived = null))
                        pollUntil(*all, what = "logon 3 settled") { !third.isExecuting }
                        pollFor(3_000L, *all)
                        println("FINDING (h3): logon 3 ${third.state()} ${third.error() ?: ""}")
                        val got = clients.received.values.flatten().drop(mid)
                        println("FINDING (h3): the client got $got")
                        println("FINDING (h3): the acceptor's library saw ${oe.trace.drop(traceMid)}")
                        val askedAgain = got.any { it.msgType == "2" && it[7]?.toInt() == clientSent + 1 }
                        println("FINDING (h3): the gap at ${clientSent + 1} asked for again: $askedAgain")
                        // Artio 0.182 forgets it: a resend asked for in a session that ends before it
                        // is answered is not asked for again. Flips if a bump changes that.
                        assertTrue(got.none { it.msgType == "2" }, "no ResendRequest at the next Logon")
                        assertTrue(third.hasCompleted(), "the third Logon is accepted with the gap still open")
                    }
                }
            }
        }
    }

    private fun logon(initialSent: Int?, initialReceived: Int?): SessionConfiguration = SessionConfiguration.builder()
        .address("localhost", port)
        .senderCompId("OE1")
        .targetCompId(Messages.ACCEPTOR_COMP_ID)
        .credentials("OE1", "secret")
        .sequenceNumbersPersistent(true)
        .apply {
            initialSent?.let { initialSentSequenceNumber(it) }
            initialReceived?.let { initialReceivedSequenceNumber(it) }
        }
        .build()

    private fun logout(session: Session, vararg libraries: Library) {
        pollUntil(*libraries, what = "logout") { session.startLogout() > 0 }
        pollUntil(*libraries, what = "disconnected") { !session.isConnected }
    }

    private fun pollFor(ms: Long, vararg libraries: Library) {
        val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms)
        while (System.nanoTime() < until) libraries.forEach { it.poll() }
    }

    /**
     * The client logs on to a [StreamingAcceptor], which streams 35=8s at it until a SIGKILL. The
     * acceptor restarts on the same archive and logs, and the engine's view of the session is read
     * before any Logon. Then, with [skip] (k, write), the offline session's number is moved on; and the
     * client logs on again.
     */
    private fun crashWhileStreaming(skip: Pair<Int, Boolean>?) {
        for (attempt in 1..ATTEMPTS) {
            if (crashWhileStreaming(skip, File(dir, "attempt-$attempt"), attempt)) return
        }
        assumeTrue(false, "not reproduced in $ATTEMPTS attempts: the archive had every message the client received")
    }

    /** One crash; false if the archive had every message the client received, so nothing to see. */
    private fun crashWhileStreaming(skip: Pair<Int, Boolean>?, attemptDir: File, attempt: Int): Boolean {
        val acceptorDir = File(attemptDir, "acceptor")
        val port = FixNode.freePort()
        FixNode(File(attemptDir, "initiator"), fileSyncLevel = 0).use { initiator ->
            Library(initiator, "clients").use { clients ->
                pollUntil(clients, what = "client library connected") { clients.library.isConnected }
                var child = Child(acceptorDir, port, fresh = true)
                child.await("READY", clients)
                val client = await(clients.library.initiate(Messages.logon("OE1", port)), clients, what = "logon")
                child.send("STREAM $STREAM_MICROS")
                child.await("STREAMING", clients)
                // Killed at a different moment each attempt.
                val target = STREAMED + attempt * 137
                pollUntil(clients, timeoutMs = 30_000L, what = "streamed reports") { clients.messages(client.id()).count { it.msgType == "8" } >= target }
                child.kill()
                pollUntil(clients, what = "the client's disconnect") { !client.isConnected }
                val clientLast = client.lastReceivedMsgSeqNum()

                val restartAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(MARK_FILE_TIMEOUT_MS)
                while (System.nanoTime() < restartAt) if (clients.poll() == 0) Thread.sleep(1)
                child = Child(acceptorDir, port, fresh = false)
                try {
                    child.await("READY", clients)
                    child.send("STATE")
                    val state = child.await("STATE", clients)
                    val engineLast = Regex("lastSent=(\\d+)").find(state)!!.groupValues[1].toInt()
                    println("FINDING (h): attempt $attempt: the client received up to $clientLast; the restarted engine has sent $engineLast: ${clientLast - engineLast} reached the client and not the archive")
                    if (clientLast <= engineLast) return false

                    if (skip != null) {
                        child.send("SKIP ${skip.first} ${skip.second}")
                        println("FINDING (h): ${child.await("SKIPPED", clients)}")
                    }
                    val before = clients.messages(client.id()).size
                    val relogon = clients.library.initiate(Messages.logon("OE1", port))
                    pollUntil(clients, timeoutMs = 20_000L, what = "the logon after the crash") { !relogon.isExecuting }
                    val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                    while (System.nanoTime() < until) clients.poll()
                    val after = clients.messages(client.id()).drop(before)
                    println("FINDING (h): relogon ${relogon.state()} ${relogon.error() ?: ""}; still connected ${relogon.resultIfPresent()?.isConnected}")
                    println("FINDING (h): the client then received ${after.take(8)}${if (after.size > 8) " ... (${after.size})" else ""}")
                    after.filter { it.msgType == "4" || it.msgType == "5" }.forEach { println("FINDING (h):   ${it.fields}") }
                    val logon = after.first { it.msgType == "A" }
                    if (skip == null) {
                        assertEquals(engineLast + 1, logon.seqNum, "the restarted engine's Logon reuses a MsgSeqNum the client has")
                        assertTrue(relogon.hasErrored(), "the client refuses the Logon: MsgSeqNum too low")
                    } else {
                        val (k, write) = skip
                        val expected = engineLast + k + (if (write) 1 else 0) + 1
                        assertEquals(expected, logon.seqNum, "the Logon numbered past the skip")
                        assertTrue(relogon.hasCompleted() && relogon.resultIfPresent().isConnected, "the client stays logged on")
                        val gapFill = after.first { it.msgType == "4" }
                        assertEquals("Y", gapFill[123], "a gap fill, not a reset")
                        assertEquals(clientLast + 1, gapFill.seqNum, "from the client's next expected")
                        assertEquals(expected + 1, gapFill[36]?.toInt(), "to past the Logon: one message covers the skip")
                    }
                    return true
                } finally {
                    child.kill()
                }
            }
        }
    }

    private companion object {
        const val STREAMED = 1_500
        const val STREAM_MICROS = 2_000 // 500 a second: the e2e's two loads' reports, roughly
        const val ATTEMPTS = 5
        const val SKIP = 10_000
        const val MARK_FILE_TIMEOUT_MS = 11_000L
    }

    /** The acceptor's JVM: [StreamingAcceptor], on this test's classpath. */
    private class Child(dir: File, port: Int, fresh: Boolean) {
        private val lines = LinkedBlockingQueue<String>()
        private val process: Process = ProcessBuilder(
            ProcessHandle.current().info().command().get(),
            "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
            "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
            "-cp", System.getProperty("java.class.path"),
            "nl.lamia.most.exchange.fixadapter.spikes.StreamingAcceptorKt",
            dir.absolutePath, port.toString(), fresh.toString(),
        ).redirectErrorStream(true).start()

        init {
            Thread {
                process.inputStream.bufferedReader().forEachLine { line ->
                    println("  [acceptor] $line")
                    lines.put(line)
                }
            }.apply { isDaemon = true }.start()
        }

        fun await(prefix: String, keepAlive: Library? = null): String {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (System.nanoTime() < deadline) {
                val line = lines.poll(1, TimeUnit.MILLISECONDS)
                if (line == null) keepAlive?.poll() else if (line.startsWith(prefix)) return line
            }
            error("the acceptor never said $prefix")
        }

        fun send(line: String) {
            process.outputStream.write("$line\n".toByteArray())
            process.outputStream.flush()
        }

        fun kill() {
            process.destroyForcibly()
            process.waitFor(10, TimeUnit.SECONDS)
        }
    }
}
