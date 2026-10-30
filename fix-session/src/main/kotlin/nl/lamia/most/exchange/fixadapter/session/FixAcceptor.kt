package nl.lamia.most.exchange.fixadapter.session

import io.aeron.archive.Archive
import io.aeron.archive.ArchivingMediaDriver
import io.aeron.archive.client.AeronArchive
import io.aeron.driver.MediaDriver
import nl.lamia.most.exchange.fixadapter.codecs.FixDictionaryImpl
import org.agrona.CloseHelper
import uk.co.real_logic.artio.engine.EngineConfiguration
import uk.co.real_logic.artio.engine.FixEngine
import uk.co.real_logic.artio.library.LibraryConfiguration
import uk.co.real_logic.artio.library.SessionExistsHandler
import uk.co.real_logic.artio.validation.PersistenceLevel
import uk.co.real_logic.artio.validation.SessionPersistenceStrategy
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * The adapter's FIX acceptor (Design.md §2, §6.4): an embedded archiving media driver, and Artio's
 * engine on it, bound to the configured port.
 *
 * - The archive and Artio's logs are on the configured directories, never deleted at start, and
 *   the archive is synced at `fileSyncLevel`, its catalog the same.
 * - Artio's monitoring files are there too, one per engine and library: by default every Artio
 *   process on the host shares one path in the temp directory.
 * - An order-entry session's sequence numbers are persistent: MsgSeqNum is the FIX session's, across
 *   logons and restarts. A market-data session's start again at 1 at every Logon (Design §4), so
 *   nothing old is resent. Sessions are accepted engine-owned, and each library claims those of its role.
 *
 * With [bindAtStartup] false the engine listens only once [bind] is called: the adapter binds after
 * its backends' startup checks (Design.md §3.2), so no Logon reaches an adapter about to refuse to
 * start.
 *
 * [configureDriver] is for test rigs (threading, say); production takes Aeron's `aeron.*` system properties.
 */
class FixAcceptor(
    val config: FixNodeConfig,
    val sessions: ConfiguredSessions = ConfiguredSessions.load(config.sessionsFile),
    bindAtStartup: Boolean = true,
    configureDriver: (MediaDriver.Context, Archive.Context) -> Unit = { _, _ -> },
) : AutoCloseable {

    init {
        CodecValidation.require()
    }

    private val driver: ArchivingMediaDriver = MediaDriver.Context()
        .aeronDirectoryName(config.aeronDir)
        // The directory is this process's own (`fix.aeronDir`): a stale one is from its last life.
        .dirDeleteOnStart(true)
        .let { driverContext ->
            val archiveContext = Archive.Context()
                .aeronDirectoryName(config.aeronDir)
                .archiveDir(config.archiveDir)
                .deleteArchiveOnStart(false)
                .fileSyncLevel(config.fileSyncLevel)
                // Aeron 1.53 refuses a catalog synced less than the recordings it describes.
                .catalogFileSyncLevel(config.fileSyncLevel)
                // Local control only: Artio's engine is the archive's one client, over IPC.
                .controlChannelEnabled(false)
                .replicationChannel("aeron:udp?endpoint=localhost:0")
                .archiveClientContext(AeronArchive.Context().controlResponseChannel(IPC))
            configureDriver(driverContext, archiveContext)
            ArchivingMediaDriver.launch(driverContext, archiveContext)
        }

    val engine: FixEngine = try {
        FixEngine.launch(
            EngineConfiguration().apply {
                bindTo(config.bindAddress, config.port)
                bindAtStartup(bindAtStartup)
                libraryAeronChannel(IPC)
                logFileDir(config.logDir.path)
                deleteLogFileDirOnStart(false)
                // Artio's default is one path in the temp directory for every engine on the host.
                monitoringFile(File(config.logDir, "engine-monitoring").path)
                // Artio's default resets sequence numbers at every logon (Design §2); market data's do (§4).
                sessionPersistenceStrategy(persistenceByRole(sessions))
                authenticationStrategy(LogonAuthentication(sessions))
                // The adapter's dictionary (Design §5.4); the engine hands it to each library with the session.
                acceptorfixDictionary(FixDictionaryImpl::class.java)
                aeronContext().aeronDirectoryName(config.aeronDir)
                aeronArchiveContext()
                    .aeronDirectoryName(config.aeronDir)
                    .controlRequestChannel(AeronArchive.Configuration.localControlChannel())
                    .controlRequestStreamId(AeronArchive.Configuration.localControlStreamId())
                    .controlResponseChannel(IPC)
            },
        )
    } catch (e: Throwable) {
        CloseHelper.quietClose(driver)
        throw e
    }

    private val libraries = AtomicInteger()

    /** A library on this engine, told of every session by [sessionExists]: a [RoleClaims], normally. */
    fun libraryConfiguration(sessionExists: SessionExistsHandler): LibraryConfiguration = LibraryConfiguration().apply {
        libraryAeronChannels(listOf(IPC))
        // As the engine's, under fix.logDir, and one per library.
        monitoringFile(File(config.logDir, "library-${libraries.incrementAndGet()}-monitoring").path)
        sessionExistsHandler(sessionExists)
        aeronContext().aeronDirectoryName(config.aeronDir)
    }

    /** Starts listening, for an acceptor created with `bindAtStartup` false. Waits for the engine to bind. */
    fun bind(timeoutMs: Long = 10_000L) {
        val reply = engine.bind()
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (reply.isExecuting) {
            check(System.nanoTime() < deadline) { "the engine did not bind within $timeoutMs ms" }
            Thread.onSpinWait()
        }
        check(reply.hasCompleted()) { "the engine did not bind: ${reply.state()} ${reply.error() ?: ""}" }
    }

    override fun close() = CloseHelper.closeAll(engine, driver)

    companion object {
        const val IPC = "aeron:ipc"

        /**
         * Persistent for an order-entry session, transient for a market-data one (Design §2, §4). A
         * Logon matching no configured session is refused by [LogonAuthentication] in any case.
         */
        fun persistenceByRole(sessions: ConfiguredSessions) = SessionPersistenceStrategy { logon ->
            val header = logon.header()
            val session = sessions.find(senderCompId = header.targetCompIDAsString(), targetCompId = header.senderCompIDAsString())
            if (session?.role == Role.MARKET_DATA) PersistenceLevel.TRANSIENT_SEQUENCE_NUMBERS else PersistenceLevel.PERSISTENT_SEQUENCE_NUMBERS
        }
    }
}
