package nl.lamia.most.exchange.fixadapter.spikes

import org.junit.jupiter.api.io.TempDir
import uk.co.real_logic.artio.session.Session
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Spike (b), Design.md §8.13 and §6.4: after a crash, does Artio still resend a 35=8 that reached the
 * fsynced archive? Artio finds a message to resend through its replay index, and the next MsgSeqNum
 * through its sequence index. Both are memory-mapped files Artio does not fsync. At startup
 * `Indexer.catchIndexUp` replays each recording **the index already knows of** from the index's last
 * position to the recording's stop position.
 *
 * The reports are written as Design §6.3 writes them, to the logged-out session, so the client has
 * not seen them and must have them resent. Three crashes:
 * - **process:** SIGKILL. The page cache survives, so the index is as Artio left it;
 * - **host, index lost:** SIGKILL, then Artio's log directory put back as it was before the reports,
 *   which is what unsynced index pages are after a power loss. The library had already sent on its
 *   recording (it took the session and answered its logout), so the index knows the recording;
 * - **host, index lost, recording new to it:** the same, but the session stayed engine-owned: the
 *   reports are the library's first messages, so the index never heard of its recording;
 * - **process, straight after a restart:** the adapter restarts while the client is away, connects a
 *   new library, writes what it owes at once, and dies within seconds. Artio's sequence index is an
 *   in-memory buffer written down (and fsynced) every `indexFileStateFlushTimeoutInMs`, 10 s by
 *   default, so no file yet names the new library's recording.
 */
class CrashRecoverySpikeTest {

    @TempDir
    lateinit var dir: File

    private val port = FixNode.freePort()
    private val reports = 5

    @Test
    fun `a process crash -- the archived reports are resent`() = crash(claim = true, loseIndex = false)

    @Test
    fun `a host crash that loses the index -- the archived reports are resent`() = crash(claim = true, loseIndex = true)

    @Test
    fun `a host crash that loses the index of a recording it had never indexed -- the archived reports are resent`() =
        crash(claim = false, loseIndex = true)

    @Test
    fun `a process crash seconds after a restart, before the index is first written -- the archived reports are resent`() {
        val acceptorDir = File(dir, "acceptor")
        FixNode(File(dir, "initiator"), fileSyncLevel = 0).use { initiator ->
            Library(initiator, "clients").use { clients ->
                pollUntil(clients, what = "client library connected") { clients.library.isConnected }

                // First life, closed cleanly: the client logs on and off.
                var child = Child(acceptorDir, port, claim = false, fresh = true)
                child.await("READY", clients)
                val client = await(clients.library.initiate(Messages.logon("OE1", port)), clients, what = "logon")
                pollUntil(clients, what = "logout") { client.startLogout() > 0 }
                pollUntil(clients, what = "disconnected") { !client.isConnected }
                val lastSent = client.lastReceivedMsgSeqNum()
                child.send("QUIT")
                child.await("CLOSED", clients)
                child.kill()

                // Second life: a new library writes what is owed at once, and the process dies.
                child = Child(acceptorDir, port, claim = false, fresh = false)
                child.await("READY", clients)
                child.send("WRITE $lastSent $reports")
                println("FINDING (b): ${child.await("ARCHIVED", clients)}, then SIGKILL")
                child.kill()

                val restartAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(MARK_FILE_TIMEOUT_MS)
                while (System.nanoTime() < restartAt) if (clients.poll() == 0) Thread.sleep(1)
                child = Child(acceptorDir, port, claim = false, fresh = false)
                try {
                    child.await("READY", clients)
                    expectResent(clients, client, lastSent)
                } finally {
                    child.kill()
                }
            }
        }
    }

    private fun crash(claim: Boolean, loseIndex: Boolean) {
        val acceptorDir = File(dir, "acceptor")
        FixNode(File(dir, "initiator"), fileSyncLevel = 0).use { initiator ->
            Library(initiator, "clients").use { clients ->
                pollUntil(clients, what = "client library connected") { clients.library.isConnected }

                // The adapter's first life: the client logs on and off; reports are written offline.
                var child = Child(acceptorDir, port, claim, fresh = true)
                child.await("READY")
                val client = await(clients.library.initiate(Messages.logon("OE1", port)), clients, what = "logon")
                pollUntil(clients, what = "logout") { client.startLogout() > 0 }
                pollUntil(clients, what = "disconnected") { !client.isConnected }
                val lastSent = client.lastReceivedMsgSeqNum()
                Thread.sleep(500) // the engine idle: its index flushed as far as it will go
                val snapshot = File(dir, "logs-before")
                if (loseIndex) File(acceptorDir, "logs").copyRecursively(snapshot)

                child.send("WRITE $lastSent $reports")
                val archived = child.await("ARCHIVED")
                child.kill()
                println("FINDING (b): $archived, then SIGKILL")
                if (loseIndex) {
                    File(acceptorDir, "logs").deleteRecursively()
                    snapshot.copyRecursively(File(acceptorDir, "logs"))
                }

                // FINDING: the archive's mark file carries a liveness timestamp, and a restart within
                // ~10 s of a SIGKILL refuses ("active mark file detected"), as the exchange's nodes do.
                // The client's library keeps polling meanwhile: an engine drops a library silent for ~10 s.
                val restartAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(MARK_FILE_TIMEOUT_MS)
                while (System.nanoTime() < restartAt) if (clients.poll() == 0) Thread.sleep(1)

                // The second life, on the same archive and logs.
                child = Child(acceptorDir, port, claim, fresh = false)
                try {
                    child.await("READY", clients)
                    expectResent(clients, client, lastSent)
                } finally {
                    child.kill()
                }
            }
        }
    }

    /** The client logs on again: the Logon follows the archived reports, and every one is resent. */
    private fun expectResent(clients: Library, client: Session, lastSent: Int) {
        val before = clients.messages(client.id()).size
        val relogon = clients.library.initiate(Messages.logon("OE1", port))
        pollUntil(clients, timeoutMs = 20_000L, what = "logon after the crash") { !relogon.isExecuting }
        println("FINDING (b): relogon ${relogon.state()} ${relogon.error() ?: ""}; client disconnects ${clients.disconnects}")
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (System.nanoTime() < until) clients.poll()
        val after = clients.messages(client.id()).drop(before)
        println("FINDING (b): after the restart the client received $after")

        assertEquals(lastSent + reports + 1, after.first { it.msgType == "A" }.seqNum,
            "the Logon follows the archived reports: no MsgSeqNum is reused")
        assertEquals(
            (1..reports).map { "C$it" to lastSent + it },
            after.filter { it.msgType == "8" }.map { it[17] to it.seqNum },
            "every archived report is resent",
        )
    }

    private companion object {
        const val MARK_FILE_TIMEOUT_MS = 11_000L
    }

    /** The acceptor's JVM: [CrashAcceptor], on this test's classpath. */
    private class Child(dir: File, port: Int, claim: Boolean, fresh: Boolean) {
        private val lines = LinkedBlockingQueue<String>()
        private val process: Process = ProcessBuilder(
            ProcessHandle.current().info().command().get(),
            "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
            "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
            "-cp", System.getProperty("java.class.path"),
            "nl.lamia.most.exchange.fixadapter.spikes.CrashAcceptorKt",
            dir.absolutePath, port.toString(), claim.toString(), fresh.toString(),
        ).redirectErrorStream(true).start()

        init {
            Thread {
                process.inputStream.bufferedReader().forEachLine { line ->
                    println("  [acceptor] $line")
                    lines.put(line)
                }
            }.apply { isDaemon = true }.start()
        }

        /** Waits for a line starting [prefix], polling [keepAlive] meanwhile. */
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

        /** SIGKILL: no shutdown hook, no close, nothing flushed by the process itself. */
        fun kill() {
            process.destroyForcibly()
            process.waitFor(10, TimeUnit.SECONDS)
        }
    }
}
