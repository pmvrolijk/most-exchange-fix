package nl.lamia.most.exchange.fixadapter

import nl.lamia.most.exchange.fixadapter.codecs.builder.NewOrderSingleEncoder
import nl.lamia.most.exchange.fixadapter.codecs.builder.SecurityListRequestEncoder
import nl.lamia.most.exchange.fixadapter.orderentry.store.OrderStore
import nl.lamia.most.exchange.fixadapter.session.AcceptorRig
import nl.lamia.most.exchange.fixadapter.session.Initiator
import nl.lamia.most.exchange.fixadapter.session.TestSessions
import nl.lamia.most.exchange.fixadapter.session.pollUntil
import nl.lamia.most.exchange.fixadapter.session.rawLogon
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The adapter as a process (Design.md §1, §7): started from the node file, each backend on its own thread. */
class AdapterTest {

    @TempDir
    lateinit var dir: File

    private val port = AcceptorRig.freePort()
    private val mdPort = AcceptorRig.freePort()

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
        setProperty("fix.md.port", mdPort.toString())
        setProperty("fix.md.logDir", File(dir, "md-logs").path)
        setProperty("fix.md.archiveDir", File(dir, "md-archive").path)
        setProperty("fix.md.aeronDir", File(dir, "md-aeron").path)
        for ((feed, stream) in listOf("l1" to 9401, "l2" to 9402, "snapshot" to 9404)) {
            setProperty("fix.md.$feed.channel", "aeron:ipc")
            setProperty("fix.md.$feed.streamId", "$stream")
        }
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
    fun `market data runs on its own thread, so a market-data client's 35=x before the directory is answered temporarily unavailable`() {
        // Design.md §4: a market-data session configured, so the backend runs, its feeds from the node file.
        Adapter(properties()).use { adapter ->
            assertTrue(adapter.marketData != null, "the market-data backend runs")
            Initiator(File(dir, "client")).use { client ->
                pollUntil(client, what = "the client's library connected") { client.library.isConnected }
                val logon = client.logon(mdPort, TestSessions.FEED1, "feed1", TestSessions.FEED_SECRET)
                pollUntil(client, what = "logon") { !logon.isExecuting }
                val session = checkNotNull(logon.resultIfPresent()) { "logon: ${logon.state()} ${logon.error()}" }
                val request = SecurityListRequestEncoder().securityReqID("SL-1").securityListRequestType(4)
                pollUntil(client, what = "35=x sent") { session.trySend(request) > 0 }
                pollUntil(client, what = "the 35=y") { client.received[session.id()].orEmpty().any { it.msgType == "y" } }
                assertEquals("4", client.received.getValue(session.id()).first { it.msgType == "y" }[560])
            }
        }
    }

    @Test
    fun `each role logs on at its own port, and at the other is refused as an unknown session`() {
        // Design.md §4: market data has its own engine and port; each engine holds its role's sessions.
        // That each logs on at its own is the two tests above.
        Adapter(properties()).use {
            val feedAtOrderEntry = rawLogon(port, TestSessions.FEED1, TestSessions.ADAPTER, "feed1", TestSessions.FEED_SECRET)
            assertTrue(feedAtOrderEntry.closed && feedAtOrderEntry.messages.none { it.msgType == "A" }, "FEED1 at order entry's port: $feedAtOrderEntry")
            val clientAtMarketData = rawLogon(mdPort, TestSessions.CLIENT1, TestSessions.ADAPTER, "client1", TestSessions.SECRET1)
            assertTrue(clientAtMarketData.closed && clientAtMarketData.messages.none { it.msgType == "A" }, "CLIENT1 at market data's port: $clientAtMarketData")
        }
    }

    @Test
    fun `market data's engine starts with its logs and archive emptied, and order entry's are kept`() {
        // Design.md §2, §4: nothing in market data's is ever replayed; order entry's are durable (§6.4).
        val markers = listOf("md-logs", "md-archive", "logs", "archive").associateWith { File(File(dir, it).apply { mkdirs() }, "marker") }
        markers.values.forEach { it.writeText("from the last run") }
        Adapter(properties()).use {
            assertFalse(markers.getValue("md-logs").exists(), "market data's logs emptied")
            assertFalse(markers.getValue("md-archive").exists(), "market data's archive emptied")
            assertTrue(markers.getValue("logs").exists(), "order entry's logs kept")
            assertTrue(markers.getValue("archive").exists(), "order entry's archive kept")
        }
    }

    @Test
    fun `a market-data session configured without market data's port refuses to start before anything is opened`() {
        val e = assertFailsWith<IllegalArgumentException> { Adapter(properties("fix.md.port" to "")) }
        assertTrue("fix.md.port" in e.message.orEmpty(), e.message)
        assertFalse(listening(), "nothing listens")
    }

    @Test
    fun `a market-data session configured without the feeds' keys refuses to start before anything is opened`() {
        val e = assertFailsWith<IllegalArgumentException> { Adapter(properties("fix.md.l2.channel" to "")) }
        assertTrue("fix.md.l2.channel" in e.message.orEmpty(), e.message)
        assertTrue(!File(dir, "aeron").exists(), "the media driver was started")
        assertTrue(!listening(), "port $port is listening")
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
