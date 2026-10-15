package nl.lamia.most.exchange.fixadapter.spikes

import io.aeron.archive.Archive
import io.aeron.archive.ArchiveThreadingMode
import io.aeron.archive.ArchivingMediaDriver
import io.aeron.archive.client.AeronArchive
import io.aeron.driver.MediaDriver
import io.aeron.driver.ThreadingMode
import io.aeron.logbuffer.ControlledFragmentHandler.Action
import org.agrona.CloseHelper
import org.agrona.DirectBuffer
import org.agrona.ErrorHandler
import uk.co.real_logic.artio.ErrorHandlerFactory
import uk.co.real_logic.artio.Reply
import uk.co.real_logic.artio.engine.EngineConfiguration
import uk.co.real_logic.artio.engine.FixEngine
import uk.co.real_logic.artio.library.FixLibrary
import uk.co.real_logic.artio.library.LibraryConfiguration
import uk.co.real_logic.artio.library.OnMessageInfo
import uk.co.real_logic.artio.library.SessionAcquireHandler
import uk.co.real_logic.artio.library.SessionAcquiredInfo
import uk.co.real_logic.artio.library.SessionExistsHandler
import uk.co.real_logic.artio.library.SessionHandler
import uk.co.real_logic.artio.messages.DisconnectReason
import uk.co.real_logic.artio.session.Session
import uk.co.real_logic.artio.validation.AuthenticationStrategy
import uk.co.real_logic.artio.validation.SessionPersistenceStrategy
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * One side of a FIX connection as production runs it: an archiving media driver, its own Aeron
 * directory, and a [FixEngine] whose archive is fsynced at [fileSyncLevel] (Design.md §6.4). An
 * acceptor binds [port]; an initiator does not. Two nodes never share a driver, so their engines'
 * IPC streams cannot collide.
 *
 * [fresh] wipes the archive and Artio's logs; a restart after a crash passes false and keeps them.
 */
class FixNode(
    val dir: File,
    val port: Int? = null,
    fileSyncLevel: Int = 1,
    fresh: Boolean = true,
    authentication: AuthenticationStrategy = AuthenticationStrategy.none(),
    configure: EngineConfiguration.() -> Unit = {},
) : AutoCloseable {
    val aeronDir: String = File(dir, "aeron").absolutePath
    val archiveDir = File(dir, "archive")
    val logDir = File(dir, "logs")

    private val driver: ArchivingMediaDriver = ArchivingMediaDriver.launch(
        MediaDriver.Context()
            .aeronDirectoryName(aeronDir)
            .threadingMode(ThreadingMode.SHARED)
            .dirDeleteOnStart(true)
            .dirDeleteOnShutdown(true),
        Archive.Context()
            .aeronDirectoryName(aeronDir)
            .archiveDir(archiveDir)
            .deleteArchiveOnStart(fresh)
            .fileSyncLevel(fileSyncLevel)
            // Aeron 1.53 refuses a catalog synced less than the recordings it describes.
            .catalogFileSyncLevel(fileSyncLevel)
            .threadingMode(ArchiveThreadingMode.SHARED)
            // Local control only: Artio's engine is the archive's one client, over IPC.
            .controlChannelEnabled(false)
            .replicationChannel("aeron:udp?endpoint=localhost:0")
            .archiveClientContext(AeronArchive.Context().controlResponseChannel("aeron:ipc")),
    )

    val engine: FixEngine = FixEngine.launch(
        EngineConfiguration().apply {
            if (port != null) bindTo("localhost", port) else bindAtStartup(false)
            libraryAeronChannel("aeron:ipc")
            logFileDir(logDir.absolutePath)
            deleteLogFileDirOnStart(fresh)
            // Sequence numbers survive a logout and a restart; Artio's default resets them at logon.
            sessionPersistenceStrategy(SessionPersistenceStrategy.alwaysPersistent())
            authenticationStrategy(authentication)
            defaultHeartbeatIntervalInS(HEARTBEAT_S)
            errorHandlerFactory(PRINT_ERRORS)
            aeronContext().aeronDirectoryName(aeronDir)
            aeronArchiveContext()
                .aeronDirectoryName(aeronDir)
                .controlRequestChannel(AeronArchive.Configuration.localControlChannel())
                .controlRequestStreamId(AeronArchive.Configuration.localControlStreamId())
                .controlResponseChannel("aeron:ipc")
            configure()
        },
    )

    override fun close() {
        CloseHelper.closeAll(engine, driver)
    }

    companion object {
        const val HEARTBEAT_S = 5

        fun freePort(): Int = ServerSocket(0).use { it.localPort }
    }
}

/** Artio keeps its errors in a file nobody reads during a test; a spike prints them. */
val PRINT_ERRORS = ErrorHandlerFactory { _ ->
    ErrorHandler { error ->
        System.err.println("ARTIO ERROR: $error")
        error.printStackTrace()
    }
}

/** A FIX message as the spikes read it: tag to value, over a copy, so it outlives the callback. */
class FixMessage(val fields: Map<Int, String>, val position: Long) {
    val msgType: String get() = fields.getValue(35)
    val seqNum: Int get() = fields.getValue(34).toInt()
    val possDup: Boolean get() = fields[43] == "Y"
    operator fun get(tag: Int): String? = fields[tag]

    override fun toString() = "35=$msgType 34=$seqNum" + (if (possDup) " 43=Y" else "") + (fields[11]?.let { " 11=$it" } ?: "")

    companion object {
        fun parse(buffer: DirectBuffer, offset: Int, length: Int, position: Long): FixMessage {
            val text = buffer.getStringWithoutLengthAscii(offset, length)
            val fields = text.split('\u0001').filter { it.contains('=') }
                .associate { it.substringBefore('=').toInt() to it.substringAfter('=') }
            return FixMessage(fields, position)
        }
    }
}

/**
 * A [FixLibrary] on [node]'s engine that records what reaches it, by session id. [claim] decides,
 * from a session's remote CompID, whether this library requests a session the engine announces
 * (Artio's engine-owned acceptance), and from which received sequence number it asks for a replay.
 */
class Library(
    node: FixNode,
    val name: String,
    private val claim: (remoteCompId: String) -> Boolean = { false },
    private val replayFrom: (logonReceivedSeqNum: Int) -> Int = { FixLibrary.NO_MESSAGE_REPLAY },
) : AutoCloseable {
    val received = LinkedHashMap<Long, MutableList<FixMessage>>()
    val sessions = LinkedHashMap<Long, Session>()
    val announced = mutableListOf<String>()
    val disconnects = mutableListOf<DisconnectReason>()

    /** By session id: the last MsgSeqNum the session had sent when it disconnected. */
    val lastSentAtDisconnect = LinkedHashMap<Long, Int>()
    private val pendingClaims = mutableListOf<Reply<*>>()

    private val handler = object : SessionHandler {
        override fun onMessage(
            buffer: DirectBuffer, offset: Int, length: Int, libraryId: Int, session: Session,
            sequenceIndex: Int, messageType: Long, timestampInNs: Long, position: Long, messageInfo: OnMessageInfo,
        ): Action {
            received.getOrPut(session.id()) { mutableListOf() } += FixMessage.parse(buffer, offset, length, position)
            return Action.CONTINUE
        }

        override fun onTimeout(libraryId: Int, session: Session) = Unit
        override fun onSlowStatus(libraryId: Int, session: Session, hasBecomeSlow: Boolean) = Unit
        override fun onDisconnect(libraryId: Int, session: Session, reason: DisconnectReason): Action {
            disconnects += reason
            lastSentAtDisconnect[session.id()] = session.lastSentMsgSeqNum()
            return Action.CONTINUE
        }

        override fun onSessionStart(session: Session) = Unit
    }

    /** Kept: after the connect, its Aeron context names the library's Aeron client ([Archived]). */
    val configuration: LibraryConfiguration = LibraryConfiguration().apply {
        libraryAeronChannels(listOf("aeron:ipc"))
        libraryName(name)
        errorHandlerFactory(PRINT_ERRORS)
        sessionAcquireHandler(SessionAcquireHandler { session: Session, _: SessionAcquiredInfo ->
            sessions[session.id()] = session
            handler
        })
        sessionExistsHandler(SessionExistsHandler { library, sessionId, _, _, _, remoteCompId, _, _, logonReceivedSeqNum, logonSequenceIndex ->
            announced += remoteCompId
            if (claim(remoteCompId)) {
                val from = replayFrom(logonReceivedSeqNum)
                pendingClaims += library.requestSession(
                    sessionId, from, if (from == FixLibrary.NO_MESSAGE_REPLAY) FixLibrary.NO_MESSAGE_REPLAY else logonSequenceIndex,
                    TIMEOUT_MS,
                )
            }
        })
        aeronContext().aeronDirectoryName(node.aeronDir)
    }

    val library: FixLibrary = FixLibrary.connect(configuration)

    fun poll(): Int = library.poll(FRAGMENT_LIMIT)

    fun messages(sessionId: Long): List<FixMessage> = received[sessionId].orEmpty()

    fun session(remoteCompId: String): Session? = sessions.values.firstOrNull { it.compositeKey().remoteCompId() == remoteCompId }

    override fun close() {
        CloseHelper.close(library)
    }

    companion object {
        const val FRAGMENT_LIMIT = 10
        const val TIMEOUT_MS = 10_000L
    }
}

/** Polls every library until [condition] holds, or fails after [timeoutMs] with [what]. */
fun pollUntil(vararg libraries: Library, timeoutMs: Long = 10_000L, what: String, condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    while (!condition()) {
        check(System.nanoTime() < deadline) { "timed out waiting for: $what" }
        var work = 0
        for (l in libraries) work += l.poll()
        if (work == 0) Thread.onSpinWait()
    }
}

/** Polls [libraries] until [reply] completes, and returns its result. */
fun <T> await(reply: Reply<T>, vararg libraries: Library, what: String): T {
    pollUntil(*libraries, what = what) { !reply.isExecuting }
    check(reply.hasCompleted()) { "$what: ${reply.state()} ${reply.error() ?: ""}" }
    return reply.resultIfPresent()
}
