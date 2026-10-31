package nl.lamia.most.exchange.fixadapter.tools

import io.aeron.archive.Archive
import io.aeron.archive.ArchiveThreadingMode
import io.aeron.archive.ArchivingMediaDriver
import io.aeron.archive.client.AeronArchive
import io.aeron.driver.MediaDriver
import io.aeron.driver.ThreadingMode
import io.aeron.logbuffer.ControlledFragmentHandler.Action
import nl.lamia.most.exchange.fixadapter.codecs.FixDictionaryImpl
import org.agrona.CloseHelper
import org.agrona.DirectBuffer
import org.agrona.concurrent.BackoffIdleStrategy
import org.agrona.concurrent.BusySpinIdleStrategy
import org.agrona.concurrent.IdleStrategy
import uk.co.real_logic.artio.builder.Encoder
import uk.co.real_logic.artio.engine.EngineConfiguration
import uk.co.real_logic.artio.engine.FixEngine
import uk.co.real_logic.artio.library.FixLibrary
import uk.co.real_logic.artio.library.LibraryConfiguration
import uk.co.real_logic.artio.library.OnMessageInfo
import uk.co.real_logic.artio.library.SessionAcquireHandler
import uk.co.real_logic.artio.library.SessionConfiguration
import uk.co.real_logic.artio.library.SessionHandler
import uk.co.real_logic.artio.messages.DisconnectReason
import uk.co.real_logic.artio.session.Session
import java.io.File
import java.util.concurrent.TimeUnit

/** Where and as whom the client logs on. */
class Logon(
    val host: String,
    val port: Int,
    val senderCompId: String,
    val targetCompId: String,
    val username: String,
    val password: String,
    /** 141=Y: both sides start again at MsgSeqNum 1. */
    val reset: Boolean = false,
)

/** A valid application or session message as Artio hands it over: the raw bytes, nothing parsed. */
fun interface RawHandler {
    fun onMessage(buffer: DirectBuffer, offset: Int, length: Int)
}

/**
 * One FIX client session (Design.md §10.1): its own media driver, Artio engine and library under
 * [stateDir]. MsgSeqNum is persisted there, so a later run with the same directory continues the
 * session as a real client would, and is resent what it missed while away.
 *
 * With [busySpin], the driver, the archive and the engine spin rather than back off (`fix perf`).
 *
 * Single-threaded: call everything from the thread that made it.
 */
class FixClient(stateDir: File, private val busySpin: Boolean, private val onRaw: RawHandler) : AutoCloseable {
    constructor(stateDir: File, onMessage: (FixMessage) -> Unit) :
        this(stateDir, false, RawHandler { buffer, offset, length -> onMessage(FixMessage.parse(buffer.getStringWithoutLengthAscii(offset, length))) })

    private val aeronDir = File(stateDir, "aeron").path

    // An archive, as a real client's engine keeps one: it holds what the client sent, to answer the
    // adapter's ResendRequest, and the sequence numbers that carry the session across runs.
    private val driver = ArchivingMediaDriver.launch(
        MediaDriver.Context().aeronDirectoryName(aeronDir).threadingMode(ThreadingMode.SHARED)
            .dirDeleteOnStart(true).dirDeleteOnShutdown(true)
            .apply { if (busySpin) sharedIdleStrategy(BusySpinIdleStrategy()) },
        Archive.Context().aeronDirectoryName(aeronDir).archiveDir(File(stateDir, "archive"))
            .threadingMode(ArchiveThreadingMode.SHARED).deleteArchiveOnStart(false)
            .apply { if (busySpin) idleStrategySupplier { BusySpinIdleStrategy() } }
            .controlChannelEnabled(false).replicationChannel("aeron:udp?endpoint=localhost:0")
            .archiveClientContext(AeronArchive.Context().controlResponseChannel("aeron:ipc")),
    )
    private val engine = try {
        FixEngine.launch(EngineConfiguration().apply {
            bindAtStartup(false)
            libraryAeronChannel("aeron:ipc")
            logFileDir(File(stateDir, "logs").path)
            deleteLogFileDirOnStart(false)
            // Artio's default is one path in the temp directory, shared by every engine on the host.
            monitoringFile(File(stateDir, "engine-monitoring").path)
            acceptorfixDictionary(FixDictionaryImpl::class.java)
            if (busySpin) {
                framerIdleStrategy(BusySpinIdleStrategy())
                archiverIdleStrategy(BusySpinIdleStrategy())
            }
            aeronContext().aeronDirectoryName(aeronDir)
            aeronArchiveContext()
                .aeronDirectoryName(aeronDir)
                .controlRequestChannel(AeronArchive.Configuration.localControlChannel())
                .controlRequestStreamId(AeronArchive.Configuration.localControlStreamId())
                .controlResponseChannel("aeron:ipc")
        })
    } catch (e: Throwable) {
        CloseHelper.quietClose(driver)
        throw e
    }
    private val library: FixLibrary = try {
        FixLibrary.connect(LibraryConfiguration().apply {
            libraryAeronChannels(listOf("aeron:ipc"))
            aeronContext().aeronDirectoryName(aeronDir)
            monitoringFile(File(stateDir, "library-monitoring").path)
            sessionAcquireHandler(SessionAcquireHandler { _, _ -> Handler() })
        })
    } catch (e: Throwable) {
        CloseHelper.quietCloseAll(engine, driver)
        throw e
    }

    /** The session logged on last; [sessions] has every one this client holds. */
    var session: Session? = null
        private set
    val sessions = ArrayList<Session>()

    /** A session's disconnect has been seen; cleared by the next [logon]. */
    var disconnected = false
        private set

    fun poll(): Int = library.poll(10)

    // Waiting, the client backs off rather than holding a core, unless it was asked to spin: a feed
    // that spins is a CPU load the measured adapter wears as its own (Design.md §8.22).
    private val idle: IdleStrategy = if (busySpin) BusySpinIdleStrategy() else BackoffIdleStrategy()

    /** Polls until [condition], or throws after [timeoutMs] naming [what]. */
    fun pollUntil(what: String, timeoutMs: Long = 15_000L, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out waiting for $what" }
            idle.idle(poll())
        }
    }

    /** Polls for [ms] whatever happens. */
    fun pollFor(ms: Long) {
        val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms)
        while (System.nanoTime() < until) idle.idle(poll())
    }

    fun logon(logon: Logon): Session {
        pollUntil("the client's library to connect") { library.isConnected }
        val reply = library.initiate(
            SessionConfiguration.builder()
                .address(logon.host, logon.port)
                .senderCompId(logon.senderCompId)
                .targetCompId(logon.targetCompId)
                .credentials(logon.username, logon.password)
                .fixDictionary(FixDictionaryImpl::class.java)
                .sequenceNumbersPersistent(true)
                .resetSeqNum(logon.reset)
                .build(),
        )
        pollUntil("the logon") { !reply.isExecuting }
        val session = reply.resultIfPresent() ?: throw IllegalStateException("logon refused: ${reply.state()} ${reply.error() ?: ""}")
        this.session = session
        sessions.removeIf { !it.isConnected }
        sessions += session
        disconnected = false
        return session
    }

    /** Sends [encoder] on the session, waiting out back-pressure. */
    fun send(encoder: Encoder) {
        val session = checkNotNull(session) { "not logged on" }
        pollUntil("${encoder.messageType()} to be sent") { session.trySend(encoder) > 0 || disconnected }
        check(!disconnected) { "disconnected before ${encoder.messageType()} was sent" }
    }

    /** Sends [encoder] once; false if Artio refused it for now (back-pressure). */
    fun trySend(encoder: Encoder): Boolean = checkNotNull(session).trySend(encoder) > 0

    fun logout() {
        for (s in sessions) logout(s)
        session?.let { if (it !in sessions) logout(it) }
    }

    private fun logout(session: Session) {
        if (!session.isConnected) return
        pollUntil("the logout to be sent", 5_000L) { session.startLogout() > 0 || !session.isConnected }
        runCatching { pollUntil("the logout", 10_000L) { !session.isConnected } }
    }

    override fun close() {
        runCatching { logout() }
        CloseHelper.closeAll(library, engine, driver)
    }

    private inner class Handler : SessionHandler {
        override fun onMessage(
            buffer: DirectBuffer, offset: Int, length: Int, libraryId: Int, session: Session,
            sequenceIndex: Int, messageType: Long, timestampInNs: Long, position: Long, messageInfo: OnMessageInfo,
        ): Action {
            if (messageInfo.isValid) onRaw.onMessage(buffer, offset, length)
            return Action.CONTINUE
        }

        override fun onTimeout(libraryId: Int, session: Session) = Unit
        override fun onSlowStatus(libraryId: Int, session: Session, hasBecomeSlow: Boolean) = Unit
        override fun onDisconnect(libraryId: Int, session: Session, reason: DisconnectReason): Action {
            disconnected = true
            return Action.CONTINUE
        }

        override fun onSessionStart(session: Session) = Unit
    }
}

/** A received FIX message as tag to value, and in order with repeats for its groups; the tools may allocate. */
class FixMessage(val fields: Map<Int, String>, val ordered: List<Pair<Int, String>> = emptyList()) {
    val msgType: String get() = fields[35].orEmpty()
    operator fun get(tag: Int): String? = fields[tag]

    /** Every value of [tag], in order: a repeating group's. */
    fun all(tag: Int): List<String> = ordered.filter { it.first == tag }.map { it.second }

    companion object {
        fun parse(text: String): FixMessage {
            val ordered = text.split('\u0001').filter { '=' in it }.map { it.substringBefore('=').toInt() to it.substringAfter('=') }
            return FixMessage(ordered.toMap(), ordered)
        }
    }
}
