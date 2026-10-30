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
import nl.lamia.most.exchange.sbe.DepthSnapshotBeginEncoder
import nl.lamia.most.exchange.sbe.DepthSnapshotEndEncoder
import nl.lamia.most.exchange.sbe.DepthSnapshotLevelEncoder
import nl.lamia.most.exchange.sbe.DepthUpdateEncoder
import nl.lamia.most.exchange.sbe.LastTradeEncoder
import nl.lamia.most.exchange.sbe.MessageHeaderEncoder
import nl.lamia.most.exchange.sbe.Side
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
        val feeds = FeedModel(acceptor.config.aeronDir)
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
            CloseHelper.closeAll(client, feeds, exchange, backend, acceptor)
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

    @Test
    fun `a 35=V is answered with a 35=W of the book the feeds built, then a 35=X for each change and trade`() {
        val rig = start()
        pollUntil(*rig.all, what = "the directory and the feeds connected") { rig.backend.hasDirectory && rig.feeds.isConnected }
        rig.feeds.snapshot(42, Triple(true, 10_025_000_000L, 10_00000000L))
        val session = rig.logon()
        rig.sendRaw(session, "V", Constants.MARKET_DATA_REQUEST_MESSAGE,
            262 to "MD-1", 263 to "1", 264 to "0", 265 to "1", 267 to "3", 269 to "0", 269 to "1", 269 to "2", 146 to "1", 55 to "ACME")

        val w = rig.reply(session, "the 35=W") { it.msgType == "W" }
        assertEquals(listOf("MD-1", "ACME", "1", "0", "100.25", "10"), listOf(262, 55, 268, 269, 270, 271).map { w[it] })

        rig.feeds.depth(42, isBid = false, price = 10_050_000_000L, qty = 3_00000000L)
        val x = rig.reply(session, "the 35=X for the offer") { it.msgType == "X" }
        assertEquals(listOf("MD-1", "0", "1", "100.5", "3", "ACME"), listOf(262, 279, 269, 270, 271, 55).map { x[it] })

        rig.feeds.trade(42, price = 10_050_000_000L, qty = 1_00000000L)
        val t = rig.reply(session, "the 35=X for the trade") { it.msgType == "X" && it[269] == "2" }
        assertEquals(listOf("0", "100.5", "1"), listOf(279, 270, 271).map { t[it] })
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

/**
 * most-exchange's market-data process, as the adapter meets it (its Design.md "The L2 recovery feed"):
 * the L2 increments, the snapshot cycle and the L1 stream's trades, in SBE over Aeron on the adapter's
 * driver, sequenced per stream as the process numbers them. Shard 0.
 */
class FeedModel(aeronDir: String) : AutoCloseable {
    private val aeron = Aeron.connect(Aeron.Context().aeronDirectoryName(aeronDir))
    private val l1: ExclusivePublication = aeron.addExclusivePublication("aeron:ipc", 9401)
    private val l2: ExclusivePublication = aeron.addExclusivePublication("aeron:ipc", 9402)
    private val snapshots: ExclusivePublication = aeron.addExclusivePublication("aeron:ipc", 9404)
    private val buffer = UnsafeBuffer(ByteArray(256))
    private val header = MessageHeaderEncoder()
    private var l1Seq = 0L
    private var l2Seq = 0L
    private var snapSeq = 0L

    val isConnected: Boolean get() = l1.isConnected && l2.isConnected && snapshots.isConnected

    private fun offer(publication: ExclusivePublication, length: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (publication.offer(buffer, 0, MessageHeaderEncoder.ENCODED_LENGTH + length) < 0) {
            check(System.nanoTime() < deadline) { "the feed would not take a message" }
        }
    }

    /** A complete snapshot cycle for [securityId] at the current L2 sequence: levels (isBid, price, qty). */
    fun snapshot(securityId: Int, vararg levels: Triple<Boolean, Long, Long>) {
        offer(snapshots, DepthSnapshotBeginEncoder().wrapAndApplyHeader(buffer, 0, header).securityId(securityId).shardId(0)
            .seqNum(++snapSeq).l2SeqNum(l2Seq).lastTradePrice(Long.MIN_VALUE).lastTradeQty(0).levelCount(levels.size).encodedLength())
        for ((isBid, price, qty) in levels) {
            offer(snapshots, DepthSnapshotLevelEncoder().wrapAndApplyHeader(buffer, 0, header).securityId(securityId).shardId(0)
                .seqNum(++snapSeq).side(if (isBid) Side.BUY else Side.SELL).price(price).aggregateQty(qty).orderCount(1).encodedLength())
        }
        offer(snapshots, DepthSnapshotEndEncoder().wrapAndApplyHeader(buffer, 0, header).securityId(securityId).shardId(0)
            .seqNum(++snapSeq).l2SeqNum(l2Seq).levelCount(levels.size).encodedLength())
    }

    fun depth(securityId: Int, isBid: Boolean, price: Long, qty: Long) =
        offer(l2, DepthUpdateEncoder().wrapAndApplyHeader(buffer, 0, header).securityId(securityId).shardId(0).seqNum(++l2Seq)
            .side(if (isBid) Side.BUY else Side.SELL).price(price).aggregateQty(qty).orderCount(1).encodedLength())

    fun trade(securityId: Int, price: Long, qty: Long) =
        offer(l1, LastTradeEncoder().wrapAndApplyHeader(buffer, 0, header).securityId(securityId).shardId(0).seqNum(++l1Seq)
            .price(price).qty(qty).aggressorSide(Side.BUY).encodedLength())

    override fun close() = CloseHelper.closeAll(l1, l2, snapshots, aeron)
}
