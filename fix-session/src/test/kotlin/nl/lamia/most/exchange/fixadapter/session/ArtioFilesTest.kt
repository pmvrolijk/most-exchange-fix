package nl.lamia.most.exchange.fixadapter.session

import org.junit.jupiter.api.io.TempDir
import uk.co.real_logic.artio.library.SessionExistsHandler
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Design.md §2: Artio's files are under `fix.logDir`. Its monitoring files (counters and the error
 * log) default to one path in the system's temp directory, shared by every Artio engine and library
 * on the host: another starting there replaced the adapter's, and two starting at once failed.
 */
class ArtioFilesTest {

    @TempDir
    lateinit var dir: File

    @Test
    fun `the engine's and each library's monitoring files are under fix_logDir, and distinct`() {
        AcceptorRig(dir, AcceptorRig.freePort()).use { rig ->
            val logDir = rig.config.logDir.canonicalFile
            val engineFile = File(rig.acceptor.engine.configuration().monitoringFile()).canonicalFile
            assertEquals(logDir, engineFile.parentFile, "the engine's: $engineFile")
            assertTrue(engineFile.isFile, "$engineFile")

            val none = SessionExistsHandler { _, _, _, _, _, _, _, _, _, _ -> }
            val one = File(rig.acceptor.libraryConfiguration(none).monitoringFile()).canonicalFile
            val two = File(rig.acceptor.libraryConfiguration(none).monitoringFile()).canonicalFile
            assertEquals(logDir, one.parentFile, "a library's: $one")
            assertNotEquals(one, two, "two libraries of one engine")
            assertNotEquals(engineFile, one)
            // The rig's two libraries have connected: their files are there.
            assertTrue((logDir.listFiles().orEmpty().count { it.name.startsWith("library-") && it.name.endsWith("-monitoring") }) >= 2, logDir.list().orEmpty().joinToString())
        }
    }
}
