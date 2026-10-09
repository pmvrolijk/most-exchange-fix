package nl.lamia.most.exchange.fixadapter.session

import uk.co.real_logic.artio.engine.EngineConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Artio and the exchange's SDK must resolve to one Aeron, and it must be the exchange's
 * (docs/Rationale.md §2). Gradle picks the highest version asked for, so bumping Artio alone would
 * quietly move the adapter onto an Aeron the exchange has never run against. This fails instead.
 */
class DependencyAlignmentTest {

    @Test
    fun `the Aeron on the classpath is the exchange's, 1_53_0`() {
        // most-exchange gradle/libs.versions.toml, `aeron`. Move both pins together. Read by name:
        // AeronVersion's supertype lives in aeron-annotations, which is not on the compile classpath.
        val version = Class.forName("io.aeron.AeronVersion").getField("VERSION").get(null)
        assertEquals("1.53.0", version)
    }

    @Test
    fun `Artio's engine configuration loads`() {
        EngineConfiguration().close()
    }
}
