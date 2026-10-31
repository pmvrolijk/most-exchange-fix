package nl.lamia.most.exchange.fixadapter.session

import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The node file of Design.md §2, "Configuration and logon". */
class NodeConfigTest {

    @TempDir
    lateinit var dir: File

    private val required = listOf(
        "fix.port=9880", "fix.logDir=/var/lib/most-fix/logs", "fix.archiveDir=/var/lib/most-fix/archive",
        "fix.sessionsFile=/etc/most-fix/sessions.properties",
    )

    private fun parse(vararg lines: String) = FixNodeConfig.parse(Properties().apply { load(lines.joinToString("\n").reader()) })

    @AfterTest
    fun clearOverrides() {
        System.clearProperty("fix.port")
    }

    @Test
    fun `the required keys, with the defaults of the rest`() {
        val config = parse(*required.toTypedArray())
        assertEquals(9880, config.port)
        assertEquals("0.0.0.0", config.bindAddress)
        assertEquals(File("/var/lib/most-fix/logs"), config.logDir)
        assertEquals(File("/var/lib/most-fix/archive"), config.archiveDir)
        assertEquals(1, config.fileSyncLevel, "fsynced unless a rig says otherwise (§6.4)")
        assertTrue(config.aeronDir.endsWith("-most-fix"), config.aeronDir)
        assertEquals(File("/etc/most-fix/sessions.properties"), config.sessionsFile)
    }

    @Test
    fun `each required key is required`() {
        for (key in required) {
            val name = key.substringBefore('=')
            val error = assertFailsWith<IllegalArgumentException> { parse(*(required - key).toTypedArray()) }
            assertTrue(name in error.message.orEmpty(), "'${error.message}' should name $name")
        }
    }

    @Test
    fun `a fix system property overrides its key in the file`() {
        val file = File(dir, "fix.properties").apply { writeText(required.joinToString("\n")) }
        System.setProperty("fix.port", "9999")
        assertEquals(9999, FixNodeConfig.load(file.path).port)
    }

    @Test
    fun `a fileSyncLevel other than 0, 1 or 2 is refused`() {
        assertFailsWith<IllegalArgumentException> { parse(*required.toTypedArray(), "fix.fileSyncLevel=3") }
    }

    // Market data's own engine (Design.md §2, §4), read when a market-data session is configured.
    private val marketData = listOf("fix.md.port=9881", "fix.md.logDir=/var/lib/most-fix/md-logs", "fix.md.archiveDir=/var/lib/most-fix/md-archive")

    private fun parseMarketData(vararg lines: String) =
        FixNodeConfig.parseMarketData(Properties().apply { load(lines.joinToString("\n").reader()) })

    @Test
    fun `market data's engine has its own port and directories, its archive unsynced, the rest from order entry's`() {
        val config = parseMarketData(*required.toTypedArray(), *marketData.toTypedArray(), "fix.bindAddress=10.0.0.5", "fix.aeronDir=/dev/shm/fix")
        assertEquals(9881, config.port)
        assertEquals("10.0.0.5", config.bindAddress, "fix.md.bindAddress defaults to fix.bindAddress")
        assertEquals(File("/var/lib/most-fix/md-logs"), config.logDir)
        assertEquals(File("/var/lib/most-fix/md-archive"), config.archiveDir)
        assertEquals("/dev/shm/fix-md", config.aeronDir)
        assertEquals(0, config.fileSyncLevel, "market data's archive is never fsynced unless told (§4)")
        assertEquals(File("/etc/most-fix/sessions.properties"), config.sessionsFile)
        val own = parseMarketData(*required.toTypedArray(), *marketData.toTypedArray(),
            "fix.md.bindAddress=10.0.0.6", "fix.md.aeronDir=/dev/shm/md", "fix.md.fileSyncLevel=1")
        assertEquals("10.0.0.6", own.bindAddress)
        assertEquals("/dev/shm/md", own.aeronDir)
        assertEquals(1, own.fileSyncLevel)
    }

    @Test
    fun `market data's port and directories are required, and none may be order entry's`() {
        for (key in marketData) {
            val name = key.substringBefore('=')
            val error = assertFailsWith<IllegalArgumentException> { parseMarketData(*required.toTypedArray(), *(marketData - key).toTypedArray()) }
            assertTrue(name in error.message.orEmpty(), "'${error.message}' should name $name")
        }
        for (clash in listOf("fix.md.port=9880", "fix.md.logDir=/var/lib/most-fix/logs", "fix.md.archiveDir=/var/lib/most-fix/archive",
            "fix.md.aeronDir=${FixNodeConfig.parse(Properties().apply { load(required.joinToString("\n").reader()) }).aeronDir}")) {
            val error = assertFailsWith<IllegalArgumentException>(clash) { parseMarketData(*required.toTypedArray(), *marketData.toTypedArray(), clash) }
            assertTrue(clash.substringBefore('=') in error.message.orEmpty(), "'${error.message}' should name ${clash.substringBefore('=')}")
        }
    }
}
