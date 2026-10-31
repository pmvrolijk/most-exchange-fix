package nl.lamia.most.exchange.fixadapter.trace

import org.agrona.concurrent.EpochNanoClock
import org.agrona.concurrent.MessageHandler
import org.agrona.concurrent.OffsetEpochNanoClock
import org.agrona.concurrent.UnsafeBuffer
import org.agrona.concurrent.ringbuffer.OneToOneRingBuffer
import org.agrona.concurrent.ringbuffer.RingBufferDescriptor
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/** The trace file's layout (Design.md §9.3): a header, then fixed records. */
object TraceFormat {
    const val MAGIC = 0x434152545453_4F4DL // "MOSTTRAC", little-endian
    const val VERSION = 1
    const val HEADER_SIZE = 4096
    const val RECORD_SIZE = 40

    // Header offsets.
    const val H_MAGIC = 0
    const val H_VERSION = 8
    const val H_RECORD_SIZE = 12
    const val H_START_NANO = 16
    const val H_START_EPOCH_NANOS = 24
    const val H_DROPPED = 32
    const val H_CLEAN = 40

    // Record offsets.
    const val R_STAGE = 0
    const val R_EXEC_TYPE = 1 // REPORT's ExecType; OUT's and DURABLE's shard
    const val R_PARTICIPANT = 8
    const val R_CL_ORD_ID = 16
    const val R_TIME = 24
    const val R_EXTRA = 32

    // Stages: Agrona's ring needs a message type above 0.
    const val IN: Byte = 1
    const val GATE: Byte = 2
    const val SENT: Byte = 3
    const val REPORT: Byte = 4
    const val OUT: Byte = 5
    const val DURABLE: Byte = 6

    fun name(stage: Byte): String = when (stage) {
        IN -> "IN"
        GATE -> "GATE"
        SENT -> "SENT"
        REPORT -> "REPORT"
        OUT -> "OUT"
        DURABLE -> "DURABLE"
        else -> "stage $stage"
    }
}

/**
 * The trace ring (Design.md §9.3): stage events written on one thread, the order-entry library's,
 * which never waits for them, and drained to a file by a thread of its own. A full ring drops the
 * event and counts it. [OFF] costs its caller one branch: check [enabled] before reading a clock.
 */
class Trace private constructor(file: File?, ringBytes: Int, epochClock: EpochNanoClock?) : AutoCloseable {
    val enabled = file != null

    private val ring: OneToOneRingBuffer?
    private val channel: FileChannel?
    private val drainer: Thread?
    private val dropped = AtomicLong()
    @Volatile private var running = true
    private val out: ByteBuffer = ByteBuffer.allocateDirect(if (file != null) 1 shl 16 else 0).order(ByteOrder.LITTLE_ENDIAN)
    private val copy = MessageHandler { _, buffer, index, length ->
        if (out.remaining() < length) flush()
        buffer.getBytes(index, out, out.position(), length)
        out.position(out.position() + length)
    }

    init {
        if (file == null) {
            ring = null
            channel = null
            drainer = null
        } else {
            require(ringBytes > 0 && Integer.bitCount(ringBytes) == 1) { "the trace ring's size must be a power of two, not $ringBytes" }
            // Agrona's ring takes a message of at most an eighth of its capacity.
            require(ringBytes / 8 >= TraceFormat.RECORD_SIZE) { "the trace ring must be at least 512 bytes, not $ringBytes" }
            ring = OneToOneRingBuffer(UnsafeBuffer(ByteBuffer.allocateDirect(ringBytes + RingBufferDescriptor.TRAILER_LENGTH)))
            file.absoluteFile.parentFile?.mkdirs()
            channel = FileChannel.open(file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
            val header = ByteBuffer.allocate(TraceFormat.HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            // Artio's receive timestamp is its engine's epoch clock's; this pair, read off that very clock,
            // converts it to nanoTime. Another instance has its own offset, off by up to a millisecond.
            val epochClock = epochClock ?: OffsetEpochNanoClock()
            header.putLong(TraceFormat.H_MAGIC, TraceFormat.MAGIC)
                .putInt(TraceFormat.H_VERSION, TraceFormat.VERSION)
                .putInt(TraceFormat.H_RECORD_SIZE, TraceFormat.RECORD_SIZE)
                .putLong(TraceFormat.H_START_NANO, System.nanoTime())
                .putLong(TraceFormat.H_START_EPOCH_NANOS, epochClock.nanoTime())
            channel.write(header, 0)
            channel.position(TraceFormat.HEADER_SIZE.toLong())
            drainer = Thread(::drain, "trace-drainer").apply {
                isDaemon = true
                start()
            }
        }
    }

    /** Events the ring had no room for. */
    val droppedEvents: Long get() = dropped.get()

    /** One event (Design.md §9.3). Call only when [enabled]; from one thread only. */
    fun event(stage: Byte, participantId: Long, clOrdId: Long, timeNs: Long, extra: Long, execType: Byte = 0) {
        val ring = ring ?: return
        val index = ring.tryClaim(stage.toInt(), TraceFormat.RECORD_SIZE)
        if (index <= 0) {
            dropped.lazySet(dropped.get() + 1)
            return
        }
        val b = ring.buffer()
        b.putByte(index + TraceFormat.R_STAGE, stage)
        b.putByte(index + TraceFormat.R_EXEC_TYPE, execType)
        b.putLong(index + TraceFormat.R_PARTICIPANT, participantId)
        b.putLong(index + TraceFormat.R_CL_ORD_ID, clOrdId)
        b.putLong(index + TraceFormat.R_TIME, timeNs)
        b.putLong(index + TraceFormat.R_EXTRA, extra)
        ring.commit(index)
    }

    private fun drain() {
        val ring = checkNotNull(ring)
        while (running) {
            if (ring.read(copy, 1024) == 0) {
                flush()
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1))
            }
        }
    }

    private fun flush() {
        out.flip()
        while (out.hasRemaining()) checkNotNull(channel).write(out)
        out.clear()
    }

    /** Stops the drainer, writes what is left, and stamps the header with the drop count and a clean end. */
    override fun close() {
        val ring = ring ?: return
        running = false
        drainer?.join()
        do {
            val read = ring.read(copy, 1024)
        } while (read > 0)
        flush()
        val channel = checkNotNull(channel)
        val tail = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        tail.putLong(0, dropped.get()).putInt(8, 1)
        channel.write(tail, TraceFormat.H_DROPPED.toLong())
        channel.force(true)
        channel.close()
    }

    companion object {
        const val FILE = "fix.trace.file"
        const val RING_BYTES = "fix.trace.ringBytes"
        const val DEFAULT_RING_BYTES = 16 * 1024 * 1024

        val OFF = Trace(null, 0, null)

        /** [epochClock]: the Artio engine's, so its receive timestamps convert (`engine.configuration().epochNanoClock()`). */
        fun open(file: File, ringBytes: Int = DEFAULT_RING_BYTES, epochClock: EpochNanoClock? = null) = Trace(file, ringBytes, epochClock)

        /** From the node file's `fix.trace.*` keys: [OFF] unless `fix.trace.file` names a file. */
        fun fromProperties(properties: java.util.Properties, epochClock: EpochNanoClock? = null): Trace {
            val file = properties.getProperty(FILE)?.trim()?.takeIf { it.isNotEmpty() } ?: return OFF
            val bytes = properties.getProperty(RING_BYTES)?.trim()?.let {
                it.toIntOrNull() ?: throw IllegalArgumentException("$RING_BYTES must be a number of bytes, not $it")
            } ?: DEFAULT_RING_BYTES
            return open(File(file), bytes, epochClock)
        }
    }
}

/** A trace file read back (Design.md §9.3), as arrays: what `fix report --trace` joins to the sender's log. */
class TraceLog private constructor(
    val startNano: Long,
    val startEpochNanos: Long,
    val dropped: Long,
    val clean: Boolean,
    val size: Int,
) {
    val stage = ByteArray(size)
    val execType = ByteArray(size)
    val participant = LongArray(size)
    val clOrdId = LongArray(size)
    val time = LongArray(size)
    val extra = LongArray(size)

    /** Artio's receive timestamp (its epoch clock) as `nanoTime`, by the pair sampled at the start. */
    fun epochToNano(epochNanos: Long): Long = epochNanos - startEpochNanos + startNano

    companion object {
        fun read(file: File): TraceLog {
            FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
                val all = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()).order(ByteOrder.LITTLE_ENDIAN)
                require(channel.size() >= TraceFormat.HEADER_SIZE && all.getLong(TraceFormat.H_MAGIC) == TraceFormat.MAGIC) { "$file is not a trace file" }
                require(all.getInt(TraceFormat.H_VERSION) == TraceFormat.VERSION) { "$file is trace version ${all.getInt(TraceFormat.H_VERSION)}" }
                val n = ((channel.size() - TraceFormat.HEADER_SIZE) / TraceFormat.RECORD_SIZE).toInt()
                val t = TraceLog(
                    all.getLong(TraceFormat.H_START_NANO), all.getLong(TraceFormat.H_START_EPOCH_NANOS),
                    all.getLong(TraceFormat.H_DROPPED), all.getInt(TraceFormat.H_CLEAN) == 1, n,
                )
                for (r in 0 until n) {
                    val at = TraceFormat.HEADER_SIZE + r * TraceFormat.RECORD_SIZE
                    t.stage[r] = all.get(at + TraceFormat.R_STAGE)
                    t.execType[r] = all.get(at + TraceFormat.R_EXEC_TYPE)
                    t.participant[r] = all.getLong(at + TraceFormat.R_PARTICIPANT)
                    t.clOrdId[r] = all.getLong(at + TraceFormat.R_CL_ORD_ID)
                    t.time[r] = all.getLong(at + TraceFormat.R_TIME)
                    t.extra[r] = all.getLong(at + TraceFormat.R_EXTRA)
                }
                return t
            }
        }
    }
}
