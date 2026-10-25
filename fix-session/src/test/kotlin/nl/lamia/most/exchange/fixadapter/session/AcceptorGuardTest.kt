package nl.lamia.most.exchange.fixadapter.session

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Design.md §5.4: without Artio's unknown-field rejection the acceptor refuses to start. Artio reads
 * the switch once per JVM, so this runs only in the `testAcceptorGuard` task's JVM, started without it.
 */
class AcceptorGuardTest {

    @TempDir
    lateinit var dir: File

    @Test
    fun `an acceptor in a JVM without unknown-field rejection refuses to start, before its driver`() {
        assumeTrue(System.getProperty(CodecValidation.REJECT_UNKNOWN_FIELD) == null, "only in testAcceptorGuard's JVM")
        val config = AcceptorRig.nodeConfig(dir, AcceptorRig.freePort())

        val refusal = assertFailsWith<IllegalStateException> { FixAcceptor(config) }

        assertTrue(CodecValidation.REJECT_UNKNOWN_FIELD in refusal.message!!, refusal.message)
        assertFalse(File(config.aeronDir).exists(), "no media driver was started")
    }
}
