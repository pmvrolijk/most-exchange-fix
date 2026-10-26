package nl.lamia.most.exchange.fixadapter.orderentry.artio

import io.aeron.Aeron
import io.aeron.AeronCounters
import io.aeron.archive.status.RecordingPos
import io.aeron.driver.status.StreamCounter
import org.agrona.concurrent.EpochClock
import org.agrona.concurrent.SystemEpochClock
import org.agrona.concurrent.status.CountersReader
import java.util.function.LongSupplier

/**
 * How far the archive has recorded the engine's inbound stream, the one every message reaches the
 * library on (Design.md §6.1, §8.20): the `RecordingPos` counter of Artio's `inboundPublication`. At
 * `fileSyncLevel=1` it passes no byte that is not fsynced (§6.4). A request is sent only once it has
 * reached its message's position (CLAUDE.md non-negotiable 11).
 *
 * The engine has one inbound publication (Artio 0.182 `EngineContext.inboundPublication`), and it is
 * the only publisher on [streamId] in the adapter's driver, so it is found from Aeron's counters as
 * the one `pub-lmt` counter on that stream with a `RecordingPos` (spike `RecoveryReplaySpikeTest` (g)).
 * More than one is an error. Messages the engine replays to the library are published on it too, so
 * every position the library is handed is in its position space.
 *
 * [UNKNOWN] until found, looked for at most every [lookupIntervalMs]; looked for again if its
 * recording stops. Single-threaded; allocates only while it is looking.
 */
class InboundRecording(
    private val counters: CountersReader,
    private val streamId: Int,
    private val clock: EpochClock = SystemEpochClock.INSTANCE,
    private val lookupIntervalMs: Long = 1L,
) : LongSupplier {
    private var counterId = CountersReader.NULL_COUNTER_ID
    private var recordingId = Aeron.NULL_VALUE.toLong()
    private var nextLookupMs = 0L
    private var last = UNKNOWN

    override fun getAsLong(): Long {
        if (counterId != CountersReader.NULL_COUNTER_ID && !RecordingPos.isActive(counters, counterId, recordingId)) {
            counterId = CountersReader.NULL_COUNTER_ID
        }
        if (counterId == CountersReader.NULL_COUNTER_ID) {
            val now = clock.time()
            if (now < nextLookupMs) return last
            nextLookupMs = now + lookupIntervalMs
            val found = find() ?: return last
            counterId = found
            recordingId = RecordingPos.getRecordingId(counters, found)
        }
        val position = counters.getCounterValue(counterId)
        if (position > last) last = position
        return last
    }

    private fun find(): Int? {
        var recorded = CountersReader.NULL_COUNTER_ID
        var count = 0
        counters.forEach { id, typeId, key, _ ->
            if (typeId == AeronCounters.DRIVER_PUBLISHER_LIMIT_TYPE_ID && key.getInt(StreamCounter.STREAM_ID_OFFSET) == streamId) {
                val recording = RecordingPos.findCounterIdBySession(counters, key.getInt(StreamCounter.SESSION_ID_OFFSET), Aeron.NULL_VALUE.toLong())
                if (recording != CountersReader.NULL_COUNTER_ID) {
                    recorded = recording
                    count++
                }
            }
        }
        check(count <= 1) { "$count recorded publications on the inbound stream $streamId (Design.md §6.1)" }
        return if (count == 0) null else recorded
    }

    companion object {
        const val UNKNOWN = -1L
    }
}
