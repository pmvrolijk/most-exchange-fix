package nl.lamia.most.exchange.fixadapter.spikes

import io.aeron.Aeron
import io.aeron.archive.status.RecordingPos
import org.agrona.concurrent.status.CountersReader
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spike (a), Design.md §8.13 and §6.2: the durable `reportSeq` may pass a report once the archive's
 * recorded position of the library's outbound stream is at or past the position `Session.trySend`
 * returned for its 35=8. Two things must hold for that:
 *
 * 1. **Source, Aeron 1.53.0:** `RecordingWriter.onBlock` writes and then, at `fileSyncLevel` ≥ 1,
 *    calls `FileChannel.force(false)` before returning; `RecordingSession.record` sets the
 *    `RecordingPos` counter only after `blockPoll` returns. So the counter never passes an unsynced
 *    byte. (Level 1 syncs data, not metadata: segment files are pre-sized, so the length a read
 *    needs does not change.) This test cannot watch an fsync; the source is the evidence for it.
 * 2. **Here:** that `trySend`'s position and the counter count the same stream, so "counter ≥
 *    position" means "this 35=8 is in the segment file". Checked by reading the file.
 */
class RecordingPositionSpikeTest {

    @TempDir
    lateinit var dir: File

    private val port = FixNode.freePort()

    @Test
    fun `once RecordingPos reaches a 35=8's trySend position, that 35=8 is in the archive's segment file`() {
        FixNode(File(dir, "acceptor"), port, fileSyncLevel = 1).use { acceptor ->
            FixNode(File(dir, "initiator"), fileSyncLevel = 0).use { initiator ->
                Library(acceptor, "order-entry", claim = { true }, replayFrom = { it + 1 }).use { oe ->
                    Library(initiator, "clients").use { clients ->
                        Aeron.connect(Aeron.Context().aeronDirectoryName(acceptor.aeronDir)).use { aeron ->
                            val all = arrayOf(oe, clients)
                            pollUntil(*all, what = "libraries connected") { all.all { it.library.isConnected } }
                            await(clients.library.initiate(Messages.logon("OE1", port)), *all, what = "logon")
                            pollUntil(*all, what = "claimed") { oe.session("OE1") != null }
                            val session = oe.session("OE1")!!

                            val counters = aeron.countersReader()
                            pollUntil(*all, what = "the library's recording counter") {
                                Archived.counterId(counters, oe.configuration) != CountersReader.NULL_COUNTER_ID
                            }
                            val counterId = Archived.counterId(counters, oe.configuration)
                            val publicationSessionId = Archived.outboundSessionId(counters, oe.configuration)
                            val recordingId = RecordingPos.getRecordingId(counters, counterId)

                            val positions = LinkedHashMap<String, Long>()
                            for (k in 1..20) {
                                val execId = "P$k"
                                var position = 0L
                                pollUntil(*all, what = "$execId sent") {
                                    position = session.trySend(Messages.executionReport(execId))
                                    position > 0
                                }
                                positions[execId] = position
                            }
                            val last = positions.values.max()
                            pollUntil(*all, what = "RecordingPos reaches $last") { counters.getCounterValue(counterId) >= last }

                            // Every 35=8 at or below the recorded position is in the segment files.
                            val recorded = counters.getCounterValue(counterId)
                            val bytes = Archived.segmentBytes(acceptor.archiveDir, recordingId)
                            val missing = positions.filter { (execId, position) ->
                                position <= recorded && !bytes.contains("\u0001" + "17=$execId\u0001")
                            }
                            println("FINDING (a): recording $recordingId of publication session $publicationSessionId; " +
                                "trySend positions ${positions.values.first()}..$last; RecordingPos $recorded; " +
                                "segment bytes ${bytes.length}")
                            assertEquals(emptyMap(), missing, "35=8s the counter claims but the file lacks")
                            assertTrue(recorded >= last)
                        }
                    }
                }
            }
        }
    }

}
