package nl.lamia.most.exchange.fixadapter.orderentry.artio

import io.aeron.Aeron
import io.aeron.AeronCounters
import io.aeron.archive.status.RecordingPos
import io.aeron.driver.status.StreamCounter
import org.agrona.concurrent.EpochClock
import org.agrona.concurrent.SystemEpochClock
import org.agrona.concurrent.status.CountersReader
import uk.co.real_logic.artio.library.LibraryConfiguration
import java.util.function.LongSupplier

/**
 * How far the archive has recorded the order-entry library's outbound stream, the one
 * `Session.trySend` positions are in (Design.md §6.2): the `RecordingPos` counter of the library's
 * own publication. At `fileSyncLevel=1` it passes no byte that is not fsynced (§6.4).
 *
 * Artio 0.182 keeps the publication private, so it is found from Aeron's public counters (decided
 * 2026-10-15; spike `LibraryRecordingSpikeTest`):
 * - Artio connects the library's Aeron client with [configuration]'s `aeronContext()`, so once the
 *   library is connected that context's `clientId()` is the client's;
 * - the driver gives each publication a `pub-lmt` counter owned by the client that added it, its key
 *   holding the publication's session and stream id (`StreamCounter`);
 * - on the outbound stream that client has one publication, or two for a moment when the engine has
 *   it extend a stopped recording. Only the one the archive records has a `RecordingPos`. More than
 *   one recorded is an error.
 *
 * Looked up afresh at every library connect ([onLibraryConnect]): a connection can be handed another
 * recording, even another library's old one. Until it is found, and once its recording stops, the
 * position is [UNKNOWN]. Single-threaded; [getAsLong] allocates only while it is looking (at most
 * every [lookupIntervalMs]).
 */
class LibraryRecording(
    private val counters: CountersReader,
    private val configuration: LibraryConfiguration,
    private val clock: EpochClock = SystemEpochClock.INSTANCE,
    private val lookupIntervalMs: Long = 1L,
) : LongSupplier {
    private var counterId = CountersReader.NULL_COUNTER_ID
    private var recordingId = Aeron.NULL_VALUE.toLong()
    private var nextLookupMs = 0L
    private var last = UNKNOWN

    /** The recording this connection's position is read from, or null before it is found. */
    val currentRecordingId: Long? get() = if (counterId == CountersReader.NULL_COUNTER_ID) null else recordingId

    /** A new library connection: its publication, and so its counter, may be another. */
    fun onLibraryConnect() {
        counterId = CountersReader.NULL_COUNTER_ID
        recordingId = Aeron.NULL_VALUE.toLong()
        nextLookupMs = 0L
        last = UNKNOWN
    }

    /** The archive's recorded position of this connection's stream, or [UNKNOWN]. Never goes back. */
    override fun getAsLong(): Long {
        if (counterId == CountersReader.NULL_COUNTER_ID) {
            val now = clock.time()
            if (now < nextLookupMs) return last
            nextLookupMs = now + lookupIntervalMs
            val found = find() ?: return last
            counterId = found
            recordingId = RecordingPos.getRecordingId(counters, found)
        }
        if (!RecordingPos.isActive(counters, counterId, recordingId)) return last // stopped: nothing more for this connection
        val position = counters.getCounterValue(counterId)
        if (position > last) last = position
        return last
    }

    private fun find(): Int? {
        val clientId = configuration.aeronContext().clientId()
        val stream = configuration.outboundLibraryStream()
        var recorded = CountersReader.NULL_COUNTER_ID
        var count = 0
        counters.forEach { id, typeId, key, _ ->
            if (typeId == AeronCounters.DRIVER_PUBLISHER_LIMIT_TYPE_ID &&
                counters.getCounterOwnerId(id) == clientId &&
                key.getInt(StreamCounter.STREAM_ID_OFFSET) == stream
            ) {
                val sessionId = key.getInt(StreamCounter.SESSION_ID_OFFSET)
                val recording = RecordingPos.findCounterIdBySession(counters, sessionId, Aeron.NULL_VALUE.toLong())
                if (recording != CountersReader.NULL_COUNTER_ID) {
                    recorded = recording
                    count++
                }
            }
        }
        check(count <= 1) { "Aeron client $clientId has $count recorded publications on stream $stream (Design.md §6.2)" }
        return if (count == 0) null else recorded
    }

    companion object {
        const val UNKNOWN = -1L
    }
}
