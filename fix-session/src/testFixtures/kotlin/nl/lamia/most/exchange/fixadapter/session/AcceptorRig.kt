package nl.lamia.most.exchange.fixadapter.session

import io.aeron.archive.ArchiveThreadingMode
import io.aeron.driver.MediaDriver
import io.aeron.driver.ThreadingMode
import io.aeron.logbuffer.ControlledFragmentHandler.Action
import nl.lamia.most.exchange.fixadapter.codecs.FixDictionaryImpl
import org.agrona.CloseHelper
import org.agrona.DirectBuffer
import uk.co.real_logic.artio.Reply
import uk.co.real_logic.artio.builder.Encoder
import nl.lamia.most.exchange.fixadapter.codecs.builder.LogonEncoder
import uk.co.real_logic.artio.engine.EngineConfiguration
import uk.co.real_logic.artio.engine.FixEngine
import uk.co.real_logic.artio.library.FixLibrary
import uk.co.real_logic.artio.library.LibraryConfiguration
import uk.co.real_logic.artio.library.OnMessageInfo
import uk.co.real_logic.artio.library.SessionAcquireHandler
import uk.co.real_logic.artio.library.SessionConfiguration
import uk.co.real_logic.artio.library.SessionExistsHandler
import uk.co.real_logic.artio.library.SessionHandler
import uk.co.real_logic.artio.messages.DisconnectReason
import uk.co.real_logic.artio.session.Session
import uk.co.real_logic.artio.util.MutableAsciiBuffer
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.HexFormat
import java.util.Properties
import java.util.concurrent.TimeUnit

/** The sessions the tests configure: an order-entry client, a second one, and a market-data client. */
object TestSessions {
    const val ADAPTER = "MOST"
    const val CLIENT1 = "CLIENT1"
    const val CLIENT2 = "CLIENT2"
    const val FEED1 = "FEED1"
    const val SECRET1 = "s3cret-one"
    const val SECRET2 = "s3cret-two"
    const val FEED_SECRET = "s3cret-feed"

    fun sha256(secret: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(secret.toByteArray()))

    val properties = """
        session.OE1.senderCompId=$ADAPTER
        session.OE1.targetCompId=$CLIENT1
        session.OE1.username=client1
        session.OE1.passwordSha256=${sha256(SECRET1)}
        session.OE1.role=order-entry
        session.OE1.participantId=1001
        session.OE2.senderCompId=$ADAPTER
        session.OE2.targetCompId=$CLIENT2
        session.OE2.username=client2
        session.OE2.passwordSha256=${sha256(SECRET2)}
        session.OE2.role=order-entry
        session.OE2.participantId=1002
        session.MD1.senderCompId=$ADAPTER
        session.MD1.targetCompId=$FEED1
        session.MD1.username=feed1
        session.MD1.passwordSha256=${sha256(FEED_SECRET)}
        session.MD1.role=market-data
    """.trimIndent()

    fun write(dir: File): File = File(dir, "sessions.properties").apply { parentFile.mkdirs(); writeText(properties) }
}

/**
 * The adapter's FIX side as the tests run it: a [FixAcceptor] on [dir], fileSyncLevel 0 and a
 * shared-thread driver, with one library per role claiming through [RoleClaims].
 */
class AcceptorRig(val dir: File, val port: Int) : AutoCloseable {
    val config: FixNodeConfig = nodeConfig(dir, port)

    val acceptor = FixAcceptor(config) { driver, archive ->
        driver.threadingMode(ThreadingMode.SHARED).dirDeleteOnShutdown(true)
        archive.threadingMode(ArchiveThreadingMode.SHARED)
    }
    val orderEntry = RoleLibrary(acceptor, Role.ORDER_ENTRY)
    val marketData = RoleLibrary(acceptor, Role.MARKET_DATA)

    override fun close() = CloseHelper.closeAll(orderEntry, marketData, acceptor)

    companion object {
        fun freePort(): Int = ServerSocket(0).use { it.localPort }

        /** The rig's node configuration, on [dir]: fileSyncLevel 0, the [TestSessions]. */
        fun nodeConfig(dir: File, port: Int): FixNodeConfig = FixNodeConfig.parse(Properties().apply {
            setProperty("fix.bindAddress", "localhost")
            setProperty("fix.port", port.toString())
            setProperty("fix.logDir", File(dir, "logs").path)
            setProperty("fix.archiveDir", File(dir, "archive").path)
            setProperty("fix.aeronDir", File(dir, "aeron").path)
            setProperty("fix.fileSyncLevel", "0")
            setProperty("fix.sessionsFile", TestSessions.write(dir).path)
        })
    }
}

/** A library of [role] on [acceptor], recording what reaches it: the sessions it was told of and acquired, and their messages. */
class RoleLibrary(acceptor: FixAcceptor, role: Role) : Polled, AutoCloseable {
    val announced = mutableListOf<String>()
    val sessions = LinkedHashMap<Long, Session>()
    val received = LinkedHashMap<Long, MutableList<FixMessage>>()
    val claimFailures = mutableListOf<String>()
    val claims = RoleClaims(acceptor.sessions, role) { session, reply -> claimFailures += "${session.name}: ${reply.state()} ${reply.error() ?: reply.resultIfPresent()}" }

    val library: FixLibrary = FixLibrary.connect(acceptor.libraryConfiguration(claims.observedBy { announced += it }).apply {
        sessionAcquireHandler(SessionAcquireHandler { session, _ ->
            sessions[session.id()] = session
            recordingHandler(received)
        })
    })

    override fun poll(): Int = library.poll(10) + claims.poll()

    fun session(remoteCompId: String): Session? = sessions.values.firstOrNull { it.compositeKey().remoteCompId() == remoteCompId }

    override fun close() = CloseHelper.close(library)
}

/** Wraps a [SessionExistsHandler] to note each announced session's remote CompID first. */
fun SessionExistsHandler.observedBy(note: (String) -> Unit) = SessionExistsHandler { library, id, lc, ls, ll, rc, rs, rl, seq, index ->
    note(rc)
    onSessionExists(library, id, lc, ls, ll, rc, rs, rl, seq, index)
}

/** The client side: its own driver and engine, and a library that initiates. */
class Initiator(dir: File) : Polled, AutoCloseable {
    private val driver = MediaDriver.launch(
        MediaDriver.Context().aeronDirectoryName(File(dir, "aeron").path).threadingMode(ThreadingMode.SHARED)
            .dirDeleteOnStart(true).dirDeleteOnShutdown(true),
    )
    private val engine = FixEngine.launch(EngineConfiguration().apply {
        bindAtStartup(false)
        libraryAeronChannel("aeron:ipc")
        logFileDir(File(dir, "logs").path)
        monitoringFile(File(dir, "engine-monitoring").path) // not the host-wide default (ArtioFilesTest)
        logInboundMessages(false)
        logOutboundMessages(false)
        // The adapter's dictionary on the client side too: Artio's own codecs are not on the classpath.
        acceptorfixDictionary(FixDictionaryImpl::class.java)
        aeronContext().aeronDirectoryName(File(dir, "aeron").path)
    })
    val received = LinkedHashMap<Long, MutableList<FixMessage>>()
    val library: FixLibrary = FixLibrary.connect(LibraryConfiguration().apply {
        libraryAeronChannels(listOf("aeron:ipc"))
        aeronContext().aeronDirectoryName(File(dir, "aeron").path)
        monitoringFile(File(dir, "library-monitoring").path)
        sessionAcquireHandler(SessionAcquireHandler { _, _ -> recordingHandler(received) })
    })

    /**
     * Logs on as [targetCompId]'s client. The client keeps its own sequence numbers in memory:
     * pass [initialSent]/[initialReceived] to continue a session across logons.
     */
    fun logon(
        port: Int, targetCompId: String, username: String, password: String,
        initialSent: Int? = null, initialReceived: Int? = null, reset: Boolean = false,
    ): Reply<Session> = library.initiate(SessionConfiguration.builder()
        .address("localhost", port)
        .senderCompId(targetCompId)
        .targetCompId(TestSessions.ADAPTER)
        .credentials(username, password)
        .fixDictionary(FixDictionaryImpl::class.java)
        .resetSeqNum(reset)
        .apply {
            initialSent?.let { initialSentSequenceNumber(it) }
            initialReceived?.let { initialReceivedSequenceNumber(it) }
        }
        .build())

    override fun poll(): Int = library.poll(10)

    override fun close() = CloseHelper.closeAll(library, engine, driver)
}

interface Polled {
    fun poll(): Int
}

private fun recordingHandler(into: MutableMap<Long, MutableList<FixMessage>>) = object : SessionHandler {
    override fun onMessage(
        buffer: DirectBuffer, offset: Int, length: Int, libraryId: Int, session: Session,
        sequenceIndex: Int, messageType: Long, timestampInNs: Long, position: Long, messageInfo: OnMessageInfo,
    ): Action {
        into.getOrPut(session.id()) { mutableListOf() } += FixMessage.parse(buffer.getStringWithoutLengthAscii(offset, length))
        return Action.CONTINUE
    }

    override fun onTimeout(libraryId: Int, session: Session) = Unit
    override fun onSlowStatus(libraryId: Int, session: Session, hasBecomeSlow: Boolean) = Unit
    override fun onDisconnect(libraryId: Int, session: Session, reason: DisconnectReason) = Action.CONTINUE
    override fun onSessionStart(session: Session) = Unit
}

/** A FIX message as the tests read it: tag to value. */
class FixMessage(val fields: Map<Int, String>) {
    val msgType: String get() = fields.getValue(35)
    val seqNum: Int get() = fields.getValue(34).toInt()
    operator fun get(tag: Int): String? = fields[tag]
    override fun toString() = "35=$msgType 34=${fields[34]}" + (fields[58]?.let { " 58=$it" } ?: "")

    companion object {
        fun parse(text: String) = FixMessage(
            text.split('\u0001').filter { it.contains('=') }.associate { it.substringBefore('=').toInt() to it.substringAfter('=') },
        )
    }
}

/** Polls until [condition] holds, or fails after [timeoutMs] with [what]. */
fun pollUntil(vararg polled: Polled, timeoutMs: Long = 10_000L, what: String, condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    while (!condition()) {
        check(System.nanoTime() < deadline) { "timed out waiting for: $what" }
        var work = 0
        for (p in polled) work += p.poll()
        if (work == 0) Thread.onSpinWait()
    }
}

/** Polls for [ms], whatever happens: to show that something does not. */
fun pollFor(ms: Long, vararg polled: Polled) {
    val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms)
    while (System.nanoTime() < until) polled.forEach { it.poll() }
}

/**
 * A Logon written straight to the socket, and everything the acceptor answers until it closes the
 * connection: what a refused client sees, byte for byte. [polled] keeps the libraries going.
 */
fun rawLogon(
    port: Int, senderCompId: String, targetCompId: String, username: String?, password: String?, vararg polled: Polled,
): RawReply {
    val logon = LogonEncoder().apply {
        header().senderCompID(senderCompId).targetCompID(targetCompId).msgSeqNum(1)
            // Now: a stale SendingTime is refused for itself, whatever the credentials.
            .sendingTime(DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS").withZone(ZoneOffset.UTC).format(Instant.now()).toByteArray())
        encryptMethod(0)
        heartBtInt(10)
        username?.let { username(it) }
        password?.let { password(it) }
    }
    val buffer = MutableAsciiBuffer(ByteArray(1024))
    val result = logon.encode(buffer, 0)
    val bytes = ByteArray(Encoder.length(result))
    buffer.getBytes(Encoder.offset(result), bytes)
    return rawExchange(port, bytes, *polled)
}

/** A Logon at [seqNum], now, as raw [fixMessage] bytes: for a Logon no encoder can write. */
fun rawLogonFields(
    senderCompId: String, targetCompId: String, username: String, password: String, seqNum: Int = 1, vararg extra: Pair<Int, String>,
) =
    fixMessage(
        "A",
        listOf(
            49 to senderCompId, 56 to targetCompId, 34 to seqNum.toString(),
            52 to DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS").withZone(ZoneOffset.UTC).format(Instant.now()),
            98 to "0", 108 to "10", 553 to username, 554 to password,
        ) + extra,
    )

/**
 * A FIX 4.4 message of [msgType] with [fields] in the order given, between a correct BodyLength
 * and CheckSum: what no encoder writes, such as a tag outside the dictionary.
 */
fun fixMessage(msgType: String, fields: List<Pair<Int, String>>): ByteArray {
    val body = (listOf(35 to msgType) + fields).joinToString("") { (tag, value) -> "$tag=$value\u0001" }
    val head = "8=FIX.4.4\u00019=${body.toByteArray(Charsets.ISO_8859_1).size}\u0001"
    val checksum = (head + body).toByteArray(Charsets.ISO_8859_1).sumOf { it.toInt() and 0xFF } % 256
    return "$head${body}10=${"%03d".format(checksum)}\u0001".toByteArray(Charsets.ISO_8859_1)
}

/** Writes [bytes] to a new connection and reads everything the acceptor answers until it closes it. */
fun rawExchange(port: Int, bytes: ByteArray, vararg polled: Polled): RawReply {
    Socket().use { socket ->
        socket.connect(InetSocketAddress("localhost", port), 5_000)
        socket.soTimeout = 1
        socket.getOutputStream().write(bytes)
        val reply = StringBuilder()
        val read = ByteArray(4096)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            polled.forEach { it.poll() }
            val n = try {
                socket.getInputStream().read(read)
            } catch (_: SocketTimeoutException) {
                continue
            }
            if (n < 0) return RawReply(messages(reply), closed = true)
            reply.append(String(read, 0, n, Charsets.ISO_8859_1))
        }
        return RawReply(messages(reply), closed = false)
    }
}

class RawReply(val messages: List<FixMessage>, val closed: Boolean) {
    override fun toString() = "$messages, ${if (closed) "then closed" else "still open"}"
}

private fun messages(text: CharSequence): List<FixMessage> =
    Regex("8=FIX.*?\u000110=\\d{3}\u0001").findAll(text).map { FixMessage.parse(it.value) }.toList()
