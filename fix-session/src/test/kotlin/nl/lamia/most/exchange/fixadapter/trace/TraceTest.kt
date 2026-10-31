package nl.lamia.most.exchange.fixadapter.trace

import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.management.ManagementFactory
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The trace ring of Design.md §9.3. */
class TraceTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `every event reads back from the file as written, in order, and the file ends clean`() {
        val file = File(dir, "trace.bin")
        Trace.open(file, 1 shl 16).use { t ->
            t.event(TraceFormat.IN, 1001L, (2L shl 32) or 7L, 100L, 555L)
            t.event(TraceFormat.GATE, 1001L, (2L shl 32) or 7L, 200L, 0L)
            t.event(TraceFormat.REPORT, 1001L, (2L shl 32) or 7L, 300L, 12L, 3)
            t.event(TraceFormat.DURABLE, 1002L, 0L, 400L, 13L, 1)
        }
        val log = TraceLog.read(file)
        assertTrue(log.clean)
        assertEquals(0L, log.dropped)
        assertEquals(4, log.size)
        assertContentEquals(byteArrayOf(TraceFormat.IN, TraceFormat.GATE, TraceFormat.REPORT, TraceFormat.DURABLE), log.stage)
        assertContentEquals(byteArrayOf(0, 0, 3, 1), log.execType)
        assertContentEquals(longArrayOf(1001L, 1001L, 1001L, 1002L), log.participant)
        assertContentEquals(longArrayOf((2L shl 32) or 7L, (2L shl 32) or 7L, (2L shl 32) or 7L, 0L), log.clOrdId)
        assertContentEquals(longArrayOf(100L, 200L, 300L, 400L), log.time)
        assertContentEquals(longArrayOf(555L, 0L, 12L, 13L), log.extra)
    }

    @Test
    fun `a full ring drops the event and counts it, never waits, and the file says how many`() {
        val file = File(dir, "trace.bin")
        val written = 200_000
        val dropped: Long
        // The smallest ring, about ten records: the drainer, waking each millisecond, cannot keep up.
        Trace.open(file, 512).use { t ->
            repeat(written) { t.event(TraceFormat.SENT, 1L, it.toLong(), it.toLong(), 0L) }
            dropped = t.droppedEvents
        }
        val log = TraceLog.read(file)
        assertTrue(dropped > 0, "some dropped")
        assertEquals(dropped, log.dropped)
        assertEquals(written.toLong(), log.size + log.dropped, "every event either in the file or counted")
        assertEquals(log.time.sorted(), log.time.toList(), "what was kept, in order")
    }

    @Test
    fun `writing an event allocates nothing`() {
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        Trace.open(File(dir, "trace.bin"), 1 shl 20).use { t ->
            var bytes = -1L
            repeat(4) { round ->
                val before = threads.currentThreadAllocatedBytes
                for (i in 0 until 10_000) t.event(TraceFormat.OUT, 1L, i.toLong(), i.toLong(), i.toLong(), 1)
                bytes = threads.currentThreadAllocatedBytes - before
                if (round < 3) Thread.sleep(20) // the drainer empties the ring between rounds
            }
            assertEquals(0L, bytes, "bytes allocated by 10,000 events, the JIT warm")
        }
    }

    @Test
    fun `off unless fix trace file names a file, and a ring size not a power of two is refused`() {
        val off = Trace.fromProperties(Properties())
        assertFalse(off.enabled)
        off.event(TraceFormat.IN, 1L, 1L, 1L, 1L) // does nothing, opens nothing
        assertEquals(0, dir.listFiles()!!.size)
        val on = Trace.fromProperties(Properties().apply { setProperty(Trace.FILE, File(dir, "t.bin").path) })
        on.use { assertTrue(it.enabled) }
        assertFailsWith<IllegalArgumentException> {
            Trace.fromProperties(Properties().apply {
                setProperty(Trace.FILE, File(dir, "u.bin").path)
                setProperty(Trace.RING_BYTES, "1000")
            })
        }
    }
}
