package nl.lamia.most.exchange.fixadapter.spikes

import io.aeron.Aeron
import io.aeron.archive.status.RecordingPos
import org.agrona.concurrent.status.CountersReader
import org.junit.jupiter.api.io.TempDir
import uk.co.real_logic.artio.library.FixLibrary
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Design.md §6.2 needs, once per library connection, the `RecordingPos` counter of that library's
 * outbound stream. Artio 0.182 keeps the publication private; [Archived.outboundSessionId] finds it
 * from Aeron's public counters instead. This checks it against what Artio holds, read by reflection
 * here only, as the oracle:
 * - with two libraries on one engine, both publishing on the same outbound stream id, as the
 *   order-entry and market-data libraries will;
 * - after a restart, when the engine has each library replace its publication with one that extends
 *   a stopped recording.
 */
class LibraryRecordingSpikeTest {

    @TempDir
    lateinit var dir: File

    private val port = FixNode.freePort()

    @Test
    fun `the public counters name each library's recorded publication, also after a restart extends it`() {
        val first = lives(fresh = true)
        val second = lives(fresh = false)
        println("FINDING (counters): first life $first; after the restart $second")
        assertEquals(first.values.toSet(), second.values.toSet(), "the restart extends the same recordings")
    }

    /** One life of the acceptor: returns, per library, its recording id. */
    private fun lives(fresh: Boolean): Map<String, Long> =
        FixNode(File(dir, "acceptor"), port, fresh = fresh).use { acceptor ->
            Library(acceptor, "order-entry").use { oe ->
                Library(acceptor, "market-data").use { md ->
                    Aeron.connect(Aeron.Context().aeronDirectoryName(acceptor.aeronDir)).use { aeron ->
                        val all = arrayOf(oe, md)
                        pollUntil(*all, what = "libraries connected") { all.all { it.library.isConnected } }
                        val counters = aeron.countersReader()
                        all.associate { library ->
                            pollUntil(*all, what = "${library.name}'s recording counter") {
                                Archived.counterId(counters, library.configuration) != CountersReader.NULL_COUNTER_ID
                            }
                            val found = Archived.outboundSessionId(counters, library.configuration)
                            val actual = outboundSessionIdByReflection(library.library)
                            println("FINDING (counters): ${library.name}, client ${library.configuration.aeronContext().clientId()}: " +
                                "public counters say session $found, Artio holds $actual")
                            assertEquals(actual, found, library.name)
                            library.name to RecordingPos.getRecordingId(counters, Archived.counterId(counters, library.configuration))
                        }.also { assertNotEquals(it.getValue("order-entry"), it.getValue("market-data")) }
                    }
                }
            }
        }

    /** The oracle: what Artio itself holds, `FixLibrary.poller.outboundPublication.sessionId()`. */
    private fun outboundSessionIdByReflection(library: FixLibrary): Int {
        val poller = FixLibrary::class.java.getDeclaredField("poller").apply { isAccessible = true }.get(library)
        val publication = poller.javaClass.getDeclaredField("outboundPublication").apply { isAccessible = true }.get(poller)
        return publication.javaClass.getMethod("sessionId").invoke(publication) as Int
    }
}
