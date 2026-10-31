package nl.lamia.most.exchange.fixadapter.tools.report

import nl.lamia.most.exchange.fixadapter.tools.Args
import nl.lamia.most.exchange.fixadapter.tools.perf.Kind
import nl.lamia.most.exchange.fixadapter.tools.perf.OrderMix.Companion.BUY
import nl.lamia.most.exchange.fixadapter.tools.perf.OrderMix.Companion.SELL
import nl.lamia.most.exchange.fixadapter.tools.perf.PerfLog
import nl.lamia.most.exchange.fixadapter.tools.perf.PerfLogFormat
import nl.lamia.most.exchange.fixadapter.tools.perf.PerfLogFormat.NO_INDEX
import nl.lamia.most.exchange.fixadapter.tools.perf.PerfLogWriter
import nl.lamia.most.exchange.fixadapter.trace.TraceFormat
import nl.lamia.most.exchange.fixadapter.trace.TraceLog
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Design.md §10.2: each received message is attributed to the request that caused it, a resting
 * order's fill to the cross that hit it, the discard pass cut from every series, and every message
 * not timed counted. The run below is written by hand; so is every expected value.
 */
class ReportTest {
    @TempDir
    lateinit var dir: File

    private fun b(c: Char) = c.code.toByte()

    /**
     * Six requests, the first the discard pass:
     * #0 passive buy (warmup), #1 passive sell, #2 a buy crossing #1, #3 cancel of #0,
     * #4 passive buy (rejected), #5 cancel of #4 (refused twice, then rejected).
     */
    private fun writeRun(file: File, capacity: Long = 64) = PerfLogWriter(file, capacity, "symbol=TEST\nmix=passive=60,cross=20,cancel=20\ncount=6\ncommit=abc123", warmup = 1).apply {
        start(t0WallMs = 1_700_000_000_000, t0Nano = 0)
        send(0, Kind.PASSIVE, BUY, PerfLogFormat.SEND_WARMUP, -1, 0, -1, 1000, 1000, 1100)
        receive(b('8'), b('0'), b('0'), 0, 0, NO_INDEX, 1500) // the warmup's ack
        send(1, Kind.PASSIVE, SELL, 0, -1, 0, 1, 2000, 2000, 2100)
        receive(b('8'), b('0'), b('0'), 0, 1, NO_INDEX, 2600) // new ack: service 500, response 600
        send(2, Kind.CROSS, BUY, 0, 1, 0, 1, 3000, 3000, 3200)
        receive(b('8'), b('F'), b('1'), 0, 1, NO_INDEX, 3100) // a fill of #1 before the cross was sent: no cross targeted it yet
        receive(b('8'), b('0'), b('0'), 0, 2, NO_INDEX, 3500) // new ack: 300, 500
        receive(b('8'), b('F'), b('2'), 0, 2, NO_INDEX, 3600) // the aggressor's fill: 400, 600
        receive(b('8'), b('F'), b('1'), 0, 1, NO_INDEX, 3700) // #1's partial fill, timed from #2: 500, 700
        send(3, Kind.CANCEL, BUY, 0, 0, 0, -1, 4000, 4050, 4100)
        receive(b('8'), b('4'), b('4'), 0, 3, 0, 4400) // cancel ack: 300, 400 (its target was the warmup's: still timed)
        send(4, Kind.PASSIVE, BUY, 0, -1, 0, -1, 5000, 5000, 5000)
        receive(b('8'), b('8'), b('8'), 0, 4, NO_INDEX, 5400) // reject: 400, 400
        send(5, Kind.CANCEL, BUY, 0, 4, 2, -1, 6000, 6000, 6300)
        receive(b('9'), PerfLogFormat.ABSENT, b('8'), 0, 5, 4, 6900) // cancel reject: 600, 900
        receive(b('8'), b('0'), b('0'), PerfLogFormat.RECEIVE_POSS_DUP, 1, NO_INDEX, 7000)
        receive(b('8'), b('I'), b('0'), PerfLogFormat.RECEIVE_UNKNOWN, 4, NO_INDEX, 7100)
        receive(b('3'), PerfLogFormat.ABSENT, PerfLogFormat.ABSENT, 0, NO_INDEX, NO_INDEX, 7200)
        receive(b('8'), b('0'), b('0'), 0, NO_INDEX, NO_INDEX, 7300)
        receive(b('8'), b('4'), b('4'), 0, 1, NO_INDEX, 7400) // CANCELED for a passive order
        receive(b('8'), b('F'), b('1'), 0, 4, NO_INDEX, 7500) // a fill of #4, which no cross targeted
        receive(b('8'), b('C'), b('C'), 0, 1, NO_INDEX, 7600) // expired
        receive(b('j'), PerfLogFormat.ABSENT, PerfLogFormat.ABSENT, 0, NO_INDEX, NO_INDEX, 7700)
        finish(8000)
        close()
    }

    @Test
    fun `a log reads back as written, and a full one counts what it could not keep`() {
        val file = File(dir, "run.log")
        writeRun(file)
        val log = PerfLog.read(file)
        assertEquals(6, log.sends.size)
        assertEquals(17, log.receives.size)
        assertEquals(1, log.warmup)
        assertEquals(1_700_000_000_000, log.t0WallMs)
        assertEquals(8000, log.endNano)
        assertEquals("TEST", log.properties["symbol"])
        assertContentEquals(longArrayOf(1000, 2000, 3000, 4000, 5000, 6000), log.sends.scheduled)
        assertContentEquals(intArrayOf(-1, -1, 1, 0, -1, 4), log.sends.target)
        assertEquals(2, log.sends.refusals[5])
        assertTrue(log.sends.warmup(0) && !log.sends.warmup(1))
        assertEquals(0L, log.dropped)

        val small = File(dir, "small.log")
        writeRun(small, capacity = 10)
        val cut = PerfLog.read(small)
        assertEquals(10, cut.sends.size + cut.receives.size)
        assertEquals(23L - 10, cut.dropped)
    }

    @Test
    fun `each message is timed against the request that caused it, a resting fill against its cross`() {
        val file = File(dir, "run.log")
        writeRun(file)
        val a = Analysis(PerfLog.read(file))
        fun timed(o: Outcome): List<Triple<Int, Long, Long>> {
            val s = a.samples.getValue(o)
            return List(s.size) { Triple(s.request[it], s.service[it], s.response[it]) }
        }
        assertEquals(listOf(Triple(1, 500L, 600L), Triple(2, 300L, 500L)), timed(Outcome.NEW_ACK))
        assertEquals(listOf(Triple(2, 400L, 600L)), timed(Outcome.FILL))
        assertEquals(listOf(Triple(2, 500L, 700L)), timed(Outcome.RESTING_PARTIAL_FILL))
        assertEquals(listOf(Triple(3, 300L, 400L)), timed(Outcome.CANCEL_ACK))
        assertEquals(listOf(Triple(4, 400L, 400L)), timed(Outcome.REJECT))
        assertEquals(listOf(Triple(5, 600L, 900L)), timed(Outcome.CANCEL_REJECT))
        assertEquals(emptyList(), timed(Outcome.PARTIAL_FILL))
        assertEquals(emptyList(), timed(Outcome.RESTING_FILL))

        assertEquals(
            mapOf(
                Untimed.POSS_DUP to 1, Untimed.UNKNOWN to 1, Untimed.SESSION_REJECT to 1, Untimed.BUSINESS_REJECT to 1,
                Untimed.WARMUP to 1, Untimed.NOT_OURS to 1, Untimed.UNSOLICITED_CANCEL to 1, Untimed.UNMATCHED_FILL to 2,
                Untimed.OTHER_EXEC_TYPE to 1,
            ),
            a.untimed.toMap(),
        )
    }

    @Test
    fun `a resting order's fills pair with the crosses that hit it in order, never with a later cross`() {
        val file = File(dir, "pairs.log")
        PerfLogWriter(file, 32, "count=4", warmup = 0).apply {
            start(0, 0)
            send(0, Kind.PASSIVE, SELL, 0, -1, 0, 1, 0, 0, 0)
            send(1, Kind.CROSS, BUY, 0, 0, 0, 1, 1000, 1000, 1100)
            send(2, Kind.CROSS, BUY, 0, 0, 0, 1, 1200, 1200, 1300)
            // #1's fill of #0 arrives after #2 was sent; #2's after that.
            receive(b('8'), b('F'), b('1'), 0, 0, NO_INDEX, 1302)
            receive(b('8'), b('F'), b('2'), 0, 0, NO_INDEX, 1500)
            // A third fill of #0: both crosses are matched, so no cross is left for it.
            receive(b('8'), b('F'), b('2'), 0, 0, NO_INDEX, 1600)
            finish(2000)
            close()
        }
        val a = Analysis(PerfLog.read(file))
        val partial = a.samples.getValue(Outcome.RESTING_PARTIAL_FILL)
        val fill = a.samples.getValue(Outcome.RESTING_FILL)
        assertEquals(listOf(1), List(partial.size) { partial.request[it] })
        assertEquals(listOf(202L), List(partial.size) { partial.service[it] })
        assertEquals(listOf(2), List(fill.size) { fill.request[it] })
        assertEquals(listOf(200L), List(fill.size) { fill.service[it] })
        assertEquals(1, a.untimed.getValue(Untimed.UNMATCHED_FILL))
    }

    @Test
    fun `the discard pass is cut from the lateness and the throughput as well as the latencies`() {
        val file = File(dir, "run.log")
        writeRun(file)
        val run = Run(Analysis(PerfLog.read(file)), null)
        val all = run.pacing.first()
        assertEquals("all", all.name)
        assertEquals(5, all.count)
        // #1 100 pushed back, #2 200, #3 50 late + 50 pushed back, #4 on time, #5 300 pushed back.
        assertEquals(700.0, all.totalLateness)
        assertEquals(50.0, all.totalSender)
        assertEquals(650.0, all.totalSystem)
        assertEquals(2L, all.refusals)
        val scheduled = run.throughput.first { it.stream == "scheduled" }
        assertEquals(5, scheduled.count)
        assertEquals(4000L, scheduled.spanNs)
        assertEquals(1e6, scheduled.ratePerSecond)
        assertEquals(1000L, scheduled.gapMax)
        assertEquals(0, run.unanswered)
    }

    @Test
    fun `the report is one self-contained page, with an hgrm per series and a CSV of every timed sample`() {
        val file = File(dir, "run.log")
        writeRun(file)
        val out = File(dir, "out")
        Report.run(Args.parse(listOf("--log", file.path, "--out", out.path, "--exchange", "one node, IPC"), emptySet()))
        val html = File(out, "report.html").readText()
        for (o in listOf("new ack", "fill", "resting partial fill", "cancel ack", "reject", "cancel reject")) assertTrue(o in html, o)
        assertTrue("one node, IPC" in html)
        // No external requests: no URL anywhere a browser would fetch one.
        assertFalse(Regex("""(src|href)\s*=\s*["']?(https?:)?//""").containsMatchIn(html))
        assertFalse("@import" in html || "url(" in html)
        assertTrue(File(out, "new-ack-service.hgrm").readText().contains("Value"))
        assertTrue(File(out, "new-ack-response.hgrm").exists())
        assertTrue(File(out, "lateness-system-share.hgrm").exists())
        val csv = File(out, "samples.csv").readLines()
        assertEquals(1 + 7, csv.size)
        assertTrue(csv.any { it.startsWith("RESTING_PARTIAL_FILL,2,cross,") })
    }

    @Test
    fun `a comparison names where the runs' provenance differs`() {
        val a = File(dir, "a.log")
        writeRun(a)
        val b = File(dir, "b.log")
        PerfLogWriter(b, 16, "symbol=TEST\nmix=passive=60,cross=20,cancel=20\ncount=2\ncommit=def456", warmup = 0).apply {
            start(1, 0)
            send(0, Kind.PASSIVE, BUY, 0, -1, 0, -1, 1000, 1000, 1000)
            receive(b('8'), b('0'), b('0'), 0, 0, NO_INDEX, 1300)
            send(1, Kind.PASSIVE, SELL, 0, -1, 0, 1, 2000, 2000, 2000)
            receive(b('8'), b('0'), b('0'), 0, 1, NO_INDEX, 2500)
            finish(3000)
            close()
        }
        val out = File(dir, "cmp")
        Report.run(Args.parse(listOf("--log", a.path, "--compare", b.path, "--out", out.path), emptySet()))
        val html = File(out, "report.html").readText()
        assertTrue("Compared with b.log" in html)
        assertTrue("commit abc123 vs def456" in html)
    }

    /** A trace file written by hand (Design.md §9.3), its clocks' pair known: nanoTime 0 is epoch 1,000,000. */
    private fun writeTrace(file: File, vararg events: TraceEvent) {
        val b = java.nio.ByteBuffer.allocate(TraceFormat.HEADER_SIZE + events.size * TraceFormat.RECORD_SIZE).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.putLong(TraceFormat.H_MAGIC, TraceFormat.MAGIC).putInt(TraceFormat.H_VERSION, TraceFormat.VERSION)
            .putInt(TraceFormat.H_RECORD_SIZE, TraceFormat.RECORD_SIZE).putLong(TraceFormat.H_START_NANO, 0L)
            .putLong(TraceFormat.H_START_EPOCH_NANOS, EPOCH).putInt(TraceFormat.H_CLEAN, 1)
        events.forEachIndexed { k, e ->
            val at = TraceFormat.HEADER_SIZE + k * TraceFormat.RECORD_SIZE
            b.put(at + TraceFormat.R_STAGE, e.stage).put(at + TraceFormat.R_EXEC_TYPE, e.byte)
                .putLong(at + TraceFormat.R_PARTICIPANT, e.participant).putLong(at + TraceFormat.R_CL_ORD_ID, e.clOrdId)
                .putLong(at + TraceFormat.R_TIME, e.time).putLong(at + TraceFormat.R_EXTRA, e.extra)
        }
        file.writeBytes(b.array())
    }

    private data class TraceEvent(val stage: Byte, val participant: Long, val clOrdId: Long, val time: Long, val extra: Long = 0L, val byte: Byte = 0)

    @Test
    fun `the adapter's trace cuts each request's service time at its stages, joined by MsgSeqNum`() {
        val log = File(dir, "joined.log")
        PerfLogWriter(log, 32, "count=3", warmup = 0).apply {
            start(0, 0)
            send(0, Kind.PASSIVE, BUY, 0, -1, 0, -1, 1000, 1000, 1100, msgSeqNum = 5)
            receive(b('8'), b('0'), b('0'), 0, 0, NO_INDEX, 2100)
            send(1, Kind.CANCEL, BUY, 0, 0, 0, -1, 2000, 2000, 2050, msgSeqNum = 6)
            receive(b('8'), b('4'), b('4'), 0, 1, 0, 3000)
            send(2, Kind.PASSIVE, SELL, 0, -1, 0, 1, 3900, 3900, 4000, msgSeqNum = 9) // not in the trace
            receive(b('8'), b('0'), b('0'), 0, 2, NO_INDEX, 4500)
            finish(5000)
            close()
        }
        val trace = File(dir, "trace.bin")
        val p = 7L
        writeTrace(
            trace,
            // Another participant's request, the same clOrdId, first in the file: the trace holds two.
            TraceEvent(TraceFormat.IN, 8L, 5L, 1150, EPOCH + 1120), TraceEvent(TraceFormat.GATE, 8L, 5L, 1160),
            // #0, clOrdId 5 (sequenceIndex 0): Artio received it at 1200, the handler at 1300.
            TraceEvent(TraceFormat.IN, p, 5L, 1300, EPOCH + 1200), TraceEvent(TraceFormat.GATE, p, 5L, 1500),
            TraceEvent(TraceFormat.SENT, p, 5L, 1600), TraceEvent(TraceFormat.REPORT, p, 5L, 1900, 1L),
            TraceEvent(TraceFormat.OUT, p, 5L, 2000, 1L),
            // #1, clOrdId 6; a GATE again after a back-pressured cycle: the first counts.
            TraceEvent(TraceFormat.IN, p, 6L, 2250, EPOCH + 2200), TraceEvent(TraceFormat.GATE, p, 6L, 2400),
            TraceEvent(TraceFormat.GATE, p, 6L, 2420), TraceEvent(TraceFormat.SENT, p, 6L, 2450),
            TraceEvent(TraceFormat.DURABLE, p, 0L, 2500, 2L),
            TraceEvent(TraceFormat.REPORT, p, 6L, 2800, 2L), TraceEvent(TraceFormat.OUT, p, 6L, 2900, 2L),
            TraceEvent(TraceFormat.DURABLE, p, 0L, 3500, 3L),
        )
        val a = Analysis(PerfLog.read(log))
        assertFailsWith<IllegalArgumentException> { TraceJoin(a, TraceLog.read(trace), null) }
        val j = TraceJoin(a, TraceLog.read(trace), p)
        assertEquals(2, j.joined)
        assertEquals(1, j.missing)
        // By hand: #0 100, 100, 200, 100, 300, 100, 100 (= 2100 − 1100); #1 150, 50, 150, 50, 350, 100, 100 (= 3000 − 2050).
        assertEquals(
            listOf(
                "client → engine" to listOf(100L, 150L), "engine → handler" to listOf(50L, 100L), "archive gate" to listOf(150L, 200L),
                "send wait" to listOf(50L, 100L), "exchange" to listOf(300L, 350L), "report out" to listOf(100L, 100L),
                "adapter → client" to listOf(100L, 100L),
            ),
            j.stages.map { (name, d) -> name to d.sorted.toList() },
        )
        assertEquals(listOf(500L, 600L), j.durable.sorted.toList(), "OUT → the first DURABLE past its reportSeq")
        val total = 1950.0
        assertContentEquals(doubleArrayOf(250 / total, 150 / total, 350 / total, 150 / total, 650 / total, 200 / total, 200 / total), j.shares)
    }

    private companion object {
        const val EPOCH = 1_000_000L
    }
}
