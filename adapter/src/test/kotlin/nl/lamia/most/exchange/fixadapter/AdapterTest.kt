package nl.lamia.most.exchange.fixadapter

import nl.lamia.most.exchange.fixadapter.codecs.builder.NewOrderSingleEncoder
import nl.lamia.most.exchange.fixadapter.orderentry.store.OrderStore
import nl.lamia.most.exchange.fixadapter.session.AcceptorRig
import nl.lamia.most.exchange.fixadapter.session.Initiator
import nl.lamia.most.exchange.fixadapter.session.TestSessions
import nl.lamia.most.exchange.fixadapter.session.pollUntil
import org.agrona.concurrent.BackoffIdleStrategy
import org.agrona.concurrent.BusySpinIdleStrategy
import org.junit.jupiter.api.io.TempDir
import uk.co.real_logic.artio.fields.UtcTimestampEncoder
import java.io.File
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The adapter as a process (Design.md §1, §7): started from the node file, order entry on its own thread. */
class AdapterTest {

    @TempDir
    lateinit var dir: File

    private val port = AcceptorRig.freePort()

    private fun properties(vararg entries: Pair<String, String>) = Properties().apply {
        setProperty("fix.bindAddress", "localhost")
        setProperty("fix.port", port.toString())
        setProperty("fix.logDir", File(dir, "logs").path)
        setProperty("fix.archiveDir", File(dir, "archive").path)
        setProperty("fix.aeronDir", File(dir, "aeron").path)
        setProperty("fix.fileSyncLevel", "0")
        setProperty("fix.sessionsFile", TestSessions.write(dir).path)
        setProperty("fix.storeDir", File(dir, "store").path)
        setProperty("fix.directoryChannel", "aeron:ipc")
        setProperty("fix.directoryStreamId", "9100")
        setProperty("fix.calendar.zone", "UTC")
        setProperty("fix.calendar.rollTime", "23:59")
        setProperty("fix.idleStrategy", "backoff")
        for ((k, v) in entries) if (v.isEmpty()) remove(k) else setProperty(k, v)
    }

    private fun listening(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("localhost", port), 1_000) }
        true
    } catch (e: ConnectException) {
        false
    }

    @Test
    fun `order entry runs on the adapter's own thread, so a client's 35=D before the directory is refused exchange unavailable`() {
        Adapter(properties()).use {
            Initiator(File(dir, "client")).use { client ->
                pollUntil(client, what = "the client's library connected") { client.library.isConnected }
                val logon = client.logon(port, TestSessions.CLIENT1, "client1", TestSessions.SECRET1)
                pollUntil(client, what = "logon") { !logon.isExecuting }
                val session = checkNotNull(logon.resultIfPresent()) { "logon: ${logon.state()} ${logon.error()}" }
                val timestamp = UtcTimestampEncoder()
                val order = NewOrderSingleEncoder().apply {
                    clOrdID("FIRST")
                    side('1')
                    transactTime(timestamp.buffer(), timestamp.encode(System.currentTimeMillis()))
                    ordType('2')
                    instrument().symbol("ACME")
                    orderQtyData().orderQty(10L, 0)
                    price(10025L, 2)
                }
                pollUntil(client, what = "35=D sent") { session.trySend(order) > 0 }
                pollUntil(client, what = "the 35=8") { client.received[session.id()].orEmpty().any { it.msgType == "8" } }
                val report = client.received.getValue(session.id()).first { it.msgType == "8" }
                assertEquals("FIRST", report[11])
                assertEquals("8", report[39])
                assertEquals("exchange unavailable", report[58])
            }
        }
    }

    @Test
    fun `a clean close marks the stop clean, after Artio's engine has closed`() {
        // Design.md §8.21: without the mark, the next start moves every session's MsgSeqNum on.
        Adapter(properties()).close()
        OrderStore.open(File(dir, "store")).use { store ->
            store.load(20261028)
            assertTrue(store.stoppedCleanly)
        }
    }

    @Test
    fun `a backend that refuses to start leaves nothing listening`() {
        val marketDataOnly = File(dir, "md-sessions.properties").apply {
            writeText(TestSessions.properties.lines().filter { it.startsWith("session.MD1.") }.joinToString("\n"))
        }
        val e = assertFailsWith<IllegalArgumentException> { Adapter(properties("fix.sessionsFile" to marketDataOnly.path)) }
        assertTrue("no order-entry session" in e.message.orEmpty(), e.message)
        assertTrue(!listening(), "port $port is listening")
    }

    @Test
    fun `a malformed calendar refuses to start before anything is opened`() {
        val e = assertFailsWith<IllegalArgumentException> { Adapter(properties("fix.calendar.rollTime" to "6pm")) }
        assertTrue("fix.calendar.rollTime" in e.message.orEmpty(), e.message)
        assertTrue(!File(dir, "aeron").exists(), "the media driver was started")
        assertTrue(!listening(), "port $port is listening")
    }

    @Test
    fun `the idle strategy is busy-spin unless the node file names another`() {
        assertIs<BusySpinIdleStrategy>(Adapter.idleStrategy(null))
        assertIs<BackoffIdleStrategy>(Adapter.idleStrategy("backoff"))
        val e = assertFailsWith<IllegalArgumentException> { Adapter.idleStrategy("lazy") }
        assertTrue("fix.idleStrategy" in e.message.orEmpty(), e.message)
    }
}
