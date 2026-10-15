package nl.lamia.most.exchange.fixadapter.spikes

import io.aeron.Aeron
import io.aeron.AeronCounters
import io.aeron.archive.status.RecordingPos
import io.aeron.driver.status.StreamCounter
import org.agrona.concurrent.status.CountersReader
import uk.co.real_logic.artio.library.LibraryConfiguration
import java.io.File

/** Where a library's outbound stream stands in the archive (Design.md §6.2). */
object Archived {

    /**
     * The Aeron session id of the library's outbound publication, the one `Session.trySend` writes to
     * and the archive records, or null while it is not being recorded yet.
     *
     * Artio 0.182 keeps that publication private, so this reads Aeron's public counters (Design §6.2):
     * - Artio connects the library's Aeron client with `configuration.aeronContext()` itself, so once
     *   the library is connected that context's `clientId()` is the client's;
     * - the driver gives each publication a `pub-lmt` counter, owned by the client that added it,
     *   whose key holds the publication's session id and stream id (`StreamCounter`);
     * - on the library's outbound stream that client has one publication, or two for a moment: when a
     *   library connects to an engine that already has a stopped recording, the engine has it replace
     *   its publication with one that extends that recording, and the old one lingers. Only the one
     *   the archive records has a `RecordingPos` counter.
     *
     * Look it up again after every library (re)connect: the publication can change then.
     */
    fun outboundSessionId(counters: CountersReader, configuration: LibraryConfiguration): Int? {
        val clientId = configuration.aeronContext().clientId()
        val stream = configuration.outboundLibraryStream()
        val publications = mutableListOf<Int>()
        counters.forEach { counterId, typeId, key, _ ->
            if (typeId == AeronCounters.DRIVER_PUBLISHER_LIMIT_TYPE_ID &&
                counters.getCounterOwnerId(counterId) == clientId &&
                key.getInt(StreamCounter.STREAM_ID_OFFSET) == stream
            ) publications += key.getInt(StreamCounter.SESSION_ID_OFFSET)
        }
        val recorded = publications.filter { RecordingPos.findCounterIdBySession(counters, it, Aeron.NULL_VALUE.toLong()) != CountersReader.NULL_COUNTER_ID }
        check(recorded.size <= 1) { "client $clientId has ${recorded.size} recorded publications on stream $stream: $recorded" }
        return recorded.singleOrNull()
    }

    /** The `RecordingPos` counter of the library's outbound stream, or [CountersReader.NULL_COUNTER_ID]. */
    fun counterId(counters: CountersReader, configuration: LibraryConfiguration): Int =
        outboundSessionId(counters, configuration)?.let { RecordingPos.findCounterIdBySession(counters, it, Aeron.NULL_VALUE.toLong()) }
            ?: CountersReader.NULL_COUNTER_ID

    /** A recording's segment files, in order, as one ISO-8859-1 string: FIX is ASCII. */
    fun segmentBytes(archiveDir: File, recordingId: Long): String =
        archiveDir.listFiles { f -> f.name.startsWith("$recordingId-") && f.name.endsWith(".rec") }!!
            .sortedBy { it.name.substringAfter('-').substringBefore('.').toLong() }
            .joinToString("") { String(it.readBytes(), Charsets.ISO_8859_1) }
}
