package nl.lamia.most.exchange.fixadapter.session

import io.aeron.archive.Archive
import io.aeron.archive.ArchivingMediaDriver
import io.aeron.archive.client.AeronArchive
import io.aeron.driver.MediaDriver
import org.agrona.CloseHelper
import uk.co.real_logic.artio.engine.EngineConfiguration
import uk.co.real_logic.artio.engine.FixEngine
import uk.co.real_logic.artio.library.LibraryConfiguration
import uk.co.real_logic.artio.library.SessionExistsHandler
import uk.co.real_logic.artio.validation.SessionPersistenceStrategy

/**
 * The adapter's FIX acceptor (Design.md §2, §6.4): an embedded archiving media driver, and Artio's
 * engine on it, bound to the configured port.
 *
 * - The archive and Artio's logs are on the configured directories, never deleted at start, and
 *   the archive is synced at `fileSyncLevel`, its catalog the same.
 * - Sequence numbers are always persistent: MsgSeqNum is the FIX session's, across logons and
 *   restarts. Sessions are accepted engine-owned, and each library claims those of its role.
 *
 * [configureDriver] is for test rigs (threading, say); production takes Aeron's `aeron.*` system properties.
 */
class FixAcceptor(
    val config: FixNodeConfig,
    val sessions: ConfiguredSessions = ConfiguredSessions.load(config.sessionsFile),
    configureDriver: (MediaDriver.Context, Archive.Context) -> Unit = { _, _ -> },
) : AutoCloseable {

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
                libraryAeronChannel(IPC)
                logFileDir(config.logDir.path)
                deleteLogFileDirOnStart(false)
                // Artio's default resets sequence numbers at every logon (Design §2).
                sessionPersistenceStrategy(SessionPersistenceStrategy.alwaysPersistent())
                authenticationStrategy(LogonAuthentication(sessions))
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

    /** A library on this engine, told of every session by [sessionExists]: a [RoleClaims], normally. */
    fun libraryConfiguration(sessionExists: SessionExistsHandler): LibraryConfiguration = LibraryConfiguration().apply {
        libraryAeronChannels(listOf(IPC))
        sessionExistsHandler(sessionExists)
        aeronContext().aeronDirectoryName(config.aeronDir)
    }

    override fun close() = CloseHelper.closeAll(engine, driver)

    companion object {
        const val IPC = "aeron:ipc"
    }
}
