package nl.lamia.most.exchange.fixadapter.tools.perf

import org.agrona.IoUtil
import org.agrona.concurrent.UnsafeBuffer
import java.io.File
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * The sender's log (Design.md §10.1): a memory-mapped file, sized before the first send, so the
 * sender only stores numbers while it runs. A header, then fixed-size records:
 *
 * - **send**, one per message: index, kind, side, flags (discard pass, substituted), the index it
 *   acts on, refusals, the price in ticks, *S*, *A*, *Z*, and the send cost *Z* − *T*;
 * - **receive**, one per 35=8, 35=9, 35=3 and 35=j: the first character of MsgType, ExecType,
 *   OrdStatus, flags (PossDup, OrdStatusUnknown), the indexes named by 11 and 41, and the arrival.
 *
 * A full log stops recording and counts what it could not keep, in the header: never silently.
 */
object PerfLogFormat {
    const val MAGIC = 0x4652455054534F4DL // "MOSTPERF", little-endian
    const val VERSION = 1
    const val HEADER_SIZE = 64 * 1024
    const val RECORD_SIZE = 48

    // Header offsets.
    const val H_MAGIC = 0
    const val H_VERSION = 8
    const val H_RECORD_SIZE = 12
    const val H_CAPACITY = 16
    const val H_COUNT = 24
    const val H_DROPPED = 32
    const val H_T0_WALL_MS = 40
    const val H_T0_NANO = 48
    const val H_WARMUP = 56
    const val H_TEXT_LENGTH = 60
    const val H_TEXT = 64
    const val H_END_NANO = 1024 * 32 // written at close; 0 if the sender died
    const val H_SENDER_ALLOCATED = H_END_NANO + 8 // bytes the send thread allocated during the paced run; -1 unknown
    const val MAX_TEXT = H_END_NANO - H_TEXT

    // Record offsets.
    const val R_TYPE = 0
    const val TYPE_SEND: Byte = 1
    const val TYPE_RECEIVE: Byte = 2

    const val S_KIND = 1
    const val S_SIDE = 2
    const val S_FLAGS = 3
    const val S_INDEX = 4
    const val S_TARGET = 8
    const val S_REFUSALS = 12
    const val S_SCHEDULED = 16
    const val S_FIRST_ATTEMPT = 24
    const val S_SENT = 32
    const val S_PRICE_TICKS = 40
    const val S_SEND_COST = 44 // Z − T, the trySend that took it, in ns

    const val R_MSG_TYPE = 1
    const val R_EXEC_TYPE = 2
    const val R_ORD_STATUS = 3
    const val R_INDEX = 4
    const val R_ORIG_INDEX = 8
    const val R_FLAGS = 12
    const val R_ARRIVAL = 16

    const val SEND_WARMUP = 1
    const val SEND_SUBSTITUTED = 2
    const val RECEIVE_POSS_DUP = 1
    const val RECEIVE_UNKNOWN = 2

    /** A field that was absent, or a ClOrdID not of this run. */
    const val NO_INDEX = -1
    const val ABSENT: Byte = 0
}

/**
 * Writes a log. Mapping it and touching every page happens here, before *T*₀, so no page fault lands
 * in the run; [start] then stamps *T*₀.
 */
class PerfLogWriter(file: File, val capacity: Long, text: String, warmup: Int) : AutoCloseable {
    private val mapped: MappedByteBuffer
    private val buffer: UnsafeBuffer
    private var count = 0L
    private var dropped = 0L

    init {
        val size = PerfLogFormat.HEADER_SIZE + capacity * PerfLogFormat.RECORD_SIZE
        require(size <= Int.MAX_VALUE) { "a log of $capacity records is over 2 GiB; run fewer messages" }
        file.absoluteFile.parentFile?.mkdirs()
        file.delete()
        mapped = IoUtil.mapNewFile(file, size)
        mapped.order(ByteOrder.LITTLE_ENDIAN)
        buffer = UnsafeBuffer(mapped)
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= PerfLogFormat.MAX_TEXT) { "the run's description is over ${PerfLogFormat.MAX_TEXT} bytes" }
        with(PerfLogFormat) {
            buffer.putLong(H_MAGIC, MAGIC)
            buffer.putInt(H_VERSION, VERSION)
            buffer.putInt(H_RECORD_SIZE, RECORD_SIZE)
            buffer.putLong(H_CAPACITY, capacity)
            buffer.putInt(H_WARMUP, warmup)
            buffer.putInt(H_TEXT_LENGTH, bytes.size)
            buffer.putBytes(H_TEXT, bytes)
        }
        var page = PAGE
        while (page < buffer.capacity()) {
            buffer.putByte(page, 0)
            page += PAGE
        }
    }

    fun start(t0WallMs: Long, t0Nano: Long) {
        buffer.putLong(PerfLogFormat.H_T0_WALL_MS, t0WallMs)
        buffer.putLong(PerfLogFormat.H_T0_NANO, t0Nano)
    }

    val recorded: Long get() = count
    val droppedRecords: Long get() = dropped

    private fun claim(): Int {
        if (count == capacity) {
            dropped++
            buffer.putLong(PerfLogFormat.H_DROPPED, dropped)
            return -1
        }
        return (PerfLogFormat.HEADER_SIZE + count * PerfLogFormat.RECORD_SIZE).toInt()
    }

    private fun commit() {
        count++
        buffer.putLong(PerfLogFormat.H_COUNT, count)
    }

    fun send(
        index: Int, kind: Byte, side: Byte, flags: Int, target: Int, refusals: Int, priceTicks: Int,
        scheduledNs: Long, firstAttemptNs: Long, sentNs: Long, sendCostNs: Long = 0,
    ) {
        val at = claim()
        if (at < 0) return
        with(PerfLogFormat) {
            buffer.putByte(at + R_TYPE, TYPE_SEND)
            buffer.putByte(at + S_KIND, kind)
            buffer.putByte(at + S_SIDE, side)
            buffer.putByte(at + S_FLAGS, flags.toByte())
            buffer.putInt(at + S_INDEX, index)
            buffer.putInt(at + S_TARGET, target)
            buffer.putInt(at + S_REFUSALS, refusals)
            buffer.putLong(at + S_SCHEDULED, scheduledNs)
            buffer.putLong(at + S_FIRST_ATTEMPT, firstAttemptNs)
            buffer.putLong(at + S_SENT, sentNs)
            buffer.putInt(at + S_PRICE_TICKS, priceTicks)
            buffer.putInt(at + S_SEND_COST, sendCostNs.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
        }
        commit()
    }

    fun receive(msgType: Byte, execType: Byte, ordStatus: Byte, flags: Int, index: Int, origIndex: Int, arrivalNs: Long) {
        val at = claim()
        if (at < 0) return
        with(PerfLogFormat) {
            buffer.putByte(at + R_TYPE, TYPE_RECEIVE)
            buffer.putByte(at + R_MSG_TYPE, msgType)
            buffer.putByte(at + R_EXEC_TYPE, execType)
            buffer.putByte(at + R_ORD_STATUS, ordStatus)
            buffer.putInt(at + R_INDEX, index)
            buffer.putInt(at + R_ORIG_INDEX, origIndex)
            buffer.putByte(at + R_FLAGS, flags.toByte())
            buffer.putLong(at + R_ARRIVAL, arrivalNs)
        }
        commit()
    }

    /** The run has ended cleanly at [endNano]; the send thread allocated [senderAllocated] bytes while paced (-1: unknown). */
    fun finish(endNano: Long, senderAllocated: Long = -1) {
        buffer.putLong(PerfLogFormat.H_SENDER_ALLOCATED, senderAllocated)
        buffer.putLong(PerfLogFormat.H_END_NANO, endNano)
    }

    private companion object {
        const val PAGE = 4096
    }

    override fun close() {
        mapped.force()
        IoUtil.unmap(mapped)
    }
}

/** A log read back: every record, as arrays, for the report. */
class PerfLog private constructor(
    val text: String,
    val t0WallMs: Long,
    val t0Nano: Long,
    val endNano: Long,
    /** Bytes the send thread allocated during the paced run; -1 if not measured. */
    val senderAllocated: Long,
    val warmup: Int,
    val capacity: Long,
    val dropped: Long,
    val sends: Sends,
    val receives: Receives,
) {
    /** The sends, in index order: the sender writes them as it sends, one per index. */
    class Sends(n: Int) {
        val size = n
        val index = IntArray(n)
        val kind = ByteArray(n)
        val side = ByteArray(n)
        val flags = ByteArray(n)
        val target = IntArray(n)
        val refusals = IntArray(n)
        val priceTicks = IntArray(n)
        val scheduled = LongArray(n)
        val firstAttempt = LongArray(n)
        /** *T*: the `trySend` that took it began. */
        val lastAttempt = LongArray(n)
        val sent = LongArray(n)

        fun warmup(i: Int) = flags[i].toInt() and PerfLogFormat.SEND_WARMUP != 0
        fun substituted(i: Int) = flags[i].toInt() and PerfLogFormat.SEND_SUBSTITUTED != 0
    }

    class Receives(n: Int) {
        val size = n
        val msgType = ByteArray(n)
        val execType = ByteArray(n)
        val ordStatus = ByteArray(n)
        val flags = ByteArray(n)
        val index = IntArray(n)
        val origIndex = IntArray(n)
        val arrival = LongArray(n)

        fun possDup(i: Int) = flags[i].toInt() and PerfLogFormat.RECEIVE_POSS_DUP != 0
        fun unknown(i: Int) = flags[i].toInt() and PerfLogFormat.RECEIVE_UNKNOWN != 0
    }

    /** `key=value` lines of the header's text: the arguments and the provenance. */
    val properties: Map<String, String> by lazy {
        text.lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
    }

    companion object {
        fun read(file: File): PerfLog {
            FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
                val mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
                mapped.order(ByteOrder.LITTLE_ENDIAN)
                val b = UnsafeBuffer(mapped)
                with(PerfLogFormat) {
                    require(channel.size() >= HEADER_SIZE && b.getLong(H_MAGIC) == MAGIC) { "$file is not a fix perf log" }
                    require(b.getInt(H_VERSION) == VERSION) { "$file is log version ${b.getInt(H_VERSION)}, this reads $VERSION" }
                    require(b.getInt(H_RECORD_SIZE) == RECORD_SIZE) { "$file has ${b.getInt(H_RECORD_SIZE)}-byte records" }
                    val count = b.getLong(H_COUNT)
                    var sends = 0
                    var receives = 0
                    for (r in 0 until count) {
                        if (b.getByte((HEADER_SIZE + r * RECORD_SIZE).toInt() + R_TYPE) == TYPE_SEND) sends++ else receives++
                    }
                    val s = Sends(sends)
                    val v = Receives(receives)
                    var si = 0
                    var vi = 0
                    for (r in 0 until count) {
                        val at = (HEADER_SIZE + r * RECORD_SIZE).toInt()
                        if (b.getByte(at + R_TYPE) == TYPE_SEND) {
                            s.index[si] = b.getInt(at + S_INDEX)
                            check(s.index[si] == si) { "$file: send record $si is message ${s.index[si]}" }
                            s.kind[si] = b.getByte(at + S_KIND)
                            s.side[si] = b.getByte(at + S_SIDE)
                            s.flags[si] = b.getByte(at + S_FLAGS)
                            s.target[si] = b.getInt(at + S_TARGET)
                            s.refusals[si] = b.getInt(at + S_REFUSALS)
                            s.priceTicks[si] = b.getInt(at + S_PRICE_TICKS)
                            s.scheduled[si] = b.getLong(at + S_SCHEDULED)
                            s.firstAttempt[si] = b.getLong(at + S_FIRST_ATTEMPT)
                            s.sent[si] = b.getLong(at + S_SENT)
                            s.lastAttempt[si] = s.sent[si] - b.getInt(at + S_SEND_COST)
                            si++
                        } else {
                            v.msgType[vi] = b.getByte(at + R_MSG_TYPE)
                            v.execType[vi] = b.getByte(at + R_EXEC_TYPE)
                            v.ordStatus[vi] = b.getByte(at + R_ORD_STATUS)
                            v.flags[vi] = b.getByte(at + R_FLAGS)
                            v.index[vi] = b.getInt(at + R_INDEX)
                            v.origIndex[vi] = b.getInt(at + R_ORIG_INDEX)
                            v.arrival[vi] = b.getLong(at + R_ARRIVAL)
                            vi++
                        }
                    }
                    val text = ByteArray(b.getInt(H_TEXT_LENGTH)).also { b.getBytes(H_TEXT, it) }.toString(Charsets.UTF_8)
                    return PerfLog(
                        text, b.getLong(H_T0_WALL_MS), b.getLong(H_T0_NANO), b.getLong(H_END_NANO),
                        if (b.getLong(H_END_NANO) == 0L) -1 else b.getLong(H_SENDER_ALLOCATED), b.getInt(H_WARMUP),
                        b.getLong(H_CAPACITY), b.getLong(H_DROPPED), s, v,
                    )
                }
            }
        }
    }
}
