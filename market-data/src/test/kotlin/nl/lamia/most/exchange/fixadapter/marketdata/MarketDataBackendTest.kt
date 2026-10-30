package nl.lamia.most.exchange.fixadapter.marketdata

import io.aeron.Aeron
import io.aeron.ExclusivePublication
import io.aeron.archive.ArchiveThreadingMode
import io.aeron.driver.ThreadingMode
import nl.lamia.most.exchange.fixadapter.codecs.Constants
import nl.lamia.most.exchange.fixadapter.codecs.builder.SecurityListRequestEncoder
import nl.lamia.most.exchange.fixadapter.session.AcceptorRig
import nl.lamia.most.exchange.fixadapter.session.DirectoryBroadcast
import nl.lamia.most.exchange.fixadapter.session.FixAcceptor
import nl.lamia.most.exchange.fixadapter.session.FixMessage
import nl.lamia.most.exchange.fixadapter.session.Initiator
import nl.lamia.most.exchange.fixadapter.session.Polled
import nl.lamia.most.exchange.fixadapter.session.TestSecurity
import nl.lamia.most.exchange.fixadapter.session.TestSessions.ADAPTER
import nl.lamia.most.exchange.fixadapter.session.TestSessions.FEED1
import nl.lamia.most.exchange.fixadapter.session.TestSessions.FEED_SECRET
import nl.lamia.most.exchange.fixadapter.session.TestShard
import nl.lamia.most.exchange.fixadapter.session.fixMessage
import nl.lamia.most.exchange.fixadapter.session.pollUntil
import org.agrona.CloseHelper
import org.agrona.concurrent.UnsafeBuffer
import org.junit.jupiter.api.io.TempDir
import uk.co.real_logic.artio.session.Session
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The market-data backend wired (Design.md §4, §5.4): a real Artio initiator logs on as a market-data
 * client, and its messages reach the backend's library, with the exchange's directory broadcast over
 * Aeron on the adapter's driver.
 */
class MarketDataBackendTest {

    @TempDir
    lateinit var dir: File

    private val port = AcceptorRig.freePort()
    private val closing = ArrayDeque<AutoCloseable>()
    private val sessions = mutableListOf<Session>()

    /** The adapter's market-data side: the acceptor not yet bound, the backend on it, then the bind, as the adapter starts. */
    private inner class Rig(broadcast: DirectoryBroadcast?) : Polled, AutoCloseable {
        val acceptor = FixAcceptor(AcceptorRig.nodeConfig(File(dir, "adapter"), port), bindAtStartup = false) { driver, archive ->
            driver.threadingMode(ThreadingMode.SHARED).dirDeleteOnShutdown(true)
            archive.threadingMode(ArchiveThreadingMode.SHARED)
        }
        val log = mutableListOf<String>()
        val backend = MarketDataBackend(acceptor, CONFIG, log = { log += it })
        val exchange = DirectoryModel(acceptor.config.aeronDir, broadcast)
        val client = Initiator(File(dir, "client"))

        init {
            acceptor.bind()
        }

        val all: Array<Polled> get() = arrayOf(this, exchange, client)

        override fun poll(): Int = backend.doWork()

        fun logon(): Session {
            pollUntil(*all, what = "libraries connected") { backend.library.isConnected && client.library.isConnected }
            val reply = client.logon(port, FEED1, "feed1", FEED_SECRET)
            pollUntil(*all, what = "logon") { !reply.isExecuting }
            check(reply.hasCompleted()) { "logon: ${reply.state()} ${reply.error() ?: ""}" }
            return reply.resultIfPresent().also { sessions += it }
        }

        fun reply(session: Session, what: String, test: (FixMessage) -> Boolean): FixMessage {
            pollUntil(*all, what = what) { client.received[session.id()].orEmpty().any(test) }
            return client.received.getValue(session.id()).first(test)
        }

        override fun close() {
            for (session in sessions) {
                if (!session.isConnected) continue
                runCatching {
                    pollUntil(*all, timeoutMs = 2_000, what = "logout sent") { session.startLogout() > 0 }
                    pollUntil(*all, timeoutMs = 2_000, what = "logged out") { !session.isConnected }
                }
            }
            CloseHelper.closeAll(client, exchange, backend, acceptor)
        }
    }

    private fun start(broadcast: DirectoryBroadcast? = DIRECTORY) = Rig(broadcast).also { closing.addFirst(it) }

    @AfterTest
    fun close() {
        while (closing.isNotEmpty()) closing.removeFirst().close()
    }

    private fun Rig.send(session: Session, encoder: uk.co.real_logic.artio.builder.Encoder) =
        pollUntil(*all, what = "sent") { session.trySend(encoder) > 0 }

    private fun Rig.sendRaw(session: Session, msgType: String, messageType: Long, vararg body: Pair<Int, String>, seqGap: Int = 0) {
        val seq = session.lastSentMsgSeqNum() + 1 + seqGap
        val now = DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS").withZone(ZoneOffset.UTC).format(Instant.now())
        val bytes = fixMessage(msgType, listOf(49 to FEED1, 56 to ADAPTER, 34 to "$seq", 52 to now) + body.toList())
        pollUntil(*all, what = "raw 35=$msgType sent") { session.trySend(UnsafeBuffer(bytes), 0, bytes.size, seq, messageType) > 0 }
    }

    private fun allSecurities(id: String) = SecurityListRequestEncoder().securityReqID(id).securityListRequestType(4)

    @Test
    fun `a market-data client's 35=x is answered with the directory's securities in a 35=y`() {
        val rig = start()
        pollUntil(*rig.all, what = "the directory") { rig.backend.hasDirectory }
        val session = rig.logon()
        rig.send(session, allSecurities("SL-1"))

        val y = rig.reply(session, "the 35=y") { it.msgType == "y" }
        assertEquals(listOf("SL-1", "0", "1", "Y", "ACME", "42", "8", "NL0000000042", "4", "EUR", "0.01"),
            listOf(320, 560, 393, 893, 55, 48, 22, 455, 456, 15, 969).map { y[it] })
    }

    @Test
    fun `a 35=x before the first directory is answered SecurityRequestResult 4, temporarily unavailable`() {
        val rig = start(broadcast = null)
        val session = rig.logon()
        rig.send(session, allSecurities("SL-1"))

        val y = rig.reply(session, "the 35=y") { it.msgType == "y" }
        assertEquals("4" to null, y[560] to y[146])
    }

    @Test
    fun `an order-entry message on a market-data session is a 35=j, unsupported message type`() {
        val rig = start()
        val session = rig.logon()
        rig.sendRaw(session, "D", Constants.NEW_ORDER_SINGLE_MESSAGE,
            11 to "ORD-1", 55 to "ACME", 54 to "1", 60 to "20261030-12:00:00.000", 38 to "10", 40 to "2", 44 to "100.25")

        val j = rig.reply(session, "the 35=j") { it.msgType == "j" }
        assertEquals(listOf("D", "3"), listOf(372, 380).map { j[it] })
    }

    @Test
    fun `a 35=x missing its SecurityListRequestType is a 35=3 naming tag 559, SessionRejectReason 1`() {
        val rig = start()
        val session = rig.logon()
        rig.sendRaw(session, "x", Constants.SECURITY_LIST_REQUEST_MESSAGE, 320 to "SL-1")

        val reject = rig.reply(session, "the 35=3") { it.msgType == "3" }
        assertEquals(listOf("x", "559", "1"), listOf(372, 371, 373).map { reject[it] })
        assertEquals(emptyList(), rig.client.received.getValue(session.id()).filter { it.msgType == "y" })
    }

    @Test
    fun `a message Artio's session layer refused is never answered by the backend`() {
        // Non-negotiable 10: Artio hands the library a message it has already dealt with, isValid()
        // false. Here a 35=x past a gap: Artio asks for a resend, and the client, which keeps no log,
        // gap-fills over it. The session stays up, so an answer from the backend would be seen.
        val rig = start()
        pollUntil(*rig.all, what = "the directory") { rig.backend.hasDirectory }
        val session = rig.logon()
        rig.sendRaw(session, "x", Constants.SECURITY_LIST_REQUEST_MESSAGE, 320 to "SL-1", 559 to "4", seqGap = 1)

        rig.reply(session, "Artio's ResendRequest") { it.msgType == "2" }
        nl.lamia.most.exchange.fixadapter.session.pollFor(500, *rig.all)
        assertTrue(session.isConnected, "still logged on")
        assertEquals(emptyList(), rig.client.received.getValue(session.id()).filter { it.msgType == "y" })
    }

    private companion object {
        const val DIRECTORY_STREAM = 9100
        val SHARD = TestShard(0, "aeron:ipc", 9200, "aeron:ipc", 9300)
        val DIRECTORY = DirectoryBroadcast(1, listOf(SHARD), listOf(TestSecurity(42, 0, "ACME", "NL0000000042")))
        val CONFIG = MarketDataConfig(
            "aeron:ipc", DIRECTORY_STREAM,
            l1 = FeedStream("aeron:ipc", 9401), l2 = FeedStream("aeron:ipc", 9402), snapshot = FeedStream("aeron:ipc", 9404),
        )
    }
}

/** The exchange's discovery, as market data meets it: the directory broadcast on a cycle, over Aeron on the adapter's driver. */
class DirectoryModel(aeronDir: String, private val broadcast: DirectoryBroadcast?) : Polled, AutoCloseable {
    private val aeron = Aeron.connect(Aeron.Context().aeronDirectoryName(aeronDir))
    private val directory: ExclusivePublication = aeron.addExclusivePublication("aeron:ipc", 9100)
    private var nextNs = 0L

    override fun poll(): Int {
        val current = broadcast ?: return 0
        val now = System.nanoTime()
        if (now < nextNs || !directory.isConnected) return 0
        nextNs = now + TimeUnit.MILLISECONDS.toNanos(50)
        current.messages { b, o, l -> directory.offer(b, o, l) > 0 }
        return 1
    }

    override fun close() = CloseHelper.closeAll(directory, aeron)
}
