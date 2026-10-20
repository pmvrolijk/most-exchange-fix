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
}
