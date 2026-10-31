package nl.lamia.most.exchange.fixadapter.tools.report

import nl.lamia.most.exchange.fixadapter.tools.Args
import nl.lamia.most.exchange.fixadapter.tools.perf.Kind
import nl.lamia.most.exchange.fixadapter.tools.perf.PerfLog
import org.HdrHistogram.Histogram
import java.io.File
import java.io.PrintStream
import java.time.Instant
import java.time.ZoneOffset

/** The percentiles every table shows (Design.md §10.2). */
val PERCENTILES = doubleArrayOf(50.0, 90.0, 99.0, 99.9, 99.99)

/** One distribution, summarised. */
class Dist(val sorted: LongArray) {
    val count get() = sorted.size
    fun p(p: Double) = Percentile.of(sorted, p)
    val max get() = sorted.lastOrNull() ?: 0L
}

/** One request kind's lateness, its parts and shares (Design.md §10.1). */
class Pacing(a: Analysis, val name: String, kind: Byte?) {
    private val requests = a.requests(kind)
    val count = requests.size
    val lateness = Dist(LongArray(count) { a.lateness.lateness[requests[it]] }.also { it.sort() })
    val queued = Dist(LongArray(count) { a.lateness.queued[requests[it]] }.also { it.sort() })
    val senderLate = Dist(LongArray(count) { a.lateness.senderLate[requests[it]] }.also { it.sort() })
    val pushedBack = Dist(LongArray(count) { a.lateness.pushedBack[requests[it]] }.also { it.sort() })
    val sendCost = Dist(LongArray(count) { a.lateness.sendCost[requests[it]] }.also { it.sort() })
    val senderShare = Dist(LongArray(count) { a.lateness.senderShare[requests[it]].toLong() }.also { it.sort() })
    val systemShare = Dist(LongArray(count) { a.lateness.systemShare[requests[it]].toLong() }.also { it.sort() })
    val totalLateness = requests.sumOf { a.lateness.lateness[it].toDouble() }
    val totalSender = requests.sumOf { a.lateness.senderShare[it] }
    val totalSystem = requests.sumOf { a.lateness.systemShare[it] }
    val refusals = requests.sumOf { a.sends.refusals[it].toLong() }
    val substituted = requests.count { a.sends.substituted(it) }
}

/** A run, ready to print: its analysis, distributions and provenance. */
class Run(val a: Analysis, exchangeOverride: String?) {
    val props: Map<String, String> = a.log.properties
    val exchange: String = exchangeOverride ?: props["exchange"].orEmpty()
    val outcomes: List<Outcome> = Outcome.entries.filter { a.samples.getValue(it).size > 0 }
    val service: Map<Outcome, Dist> = outcomes.associateWith { Dist(a.samples.getValue(it).serviceSorted()) }
    val response: Map<Outcome, Dist> = outcomes.associateWith { Dist(a.samples.getValue(it).responseSorted()) }
    val pacing: List<Pacing> = listOf(Pacing(a, "all", null), Pacing(a, "passive", Kind.PASSIVE), Pacing(a, "cross", Kind.CROSS), Pacing(a, "cancel", Kind.CANCEL))
        .filter { it.count > 0 }
    val throughput: List<Throughput> = a.streams().map { (name, times) -> Throughput(name, times) }
    val offered: Double = throughput.first { it.stream == "scheduled" }.ratePerSecond
    val achieved: Double = throughput.first { it.stream == "sent" }.ratePerSecond
    /** Measured requests no 35=8 or 35=9 named. */
    val unanswered: Int = run {
        val answered = BooleanArray(a.sends.size)
        val r = a.log.receives
        for (k in 0 until r.size) {
            val i = r.index[k]
            if (i in 0 until a.sends.size && (r.msgType[k] == '8'.code.toByte() || r.msgType[k] == '9'.code.toByte())) answered[i] = true
        }
        a.measured.count { !answered[it] }
    }

    val date: String = Instant.ofEpochMilli(a.log.t0WallMs).atOffset(ZoneOffset.UTC).toLocalDate().toString()

    /** What a reader must know before trusting the figures. */
    val warnings: List<String> = buildList {
        if (a.warmup == 0) add("No discard pass (--warmup 0): the first messages carry the cold JVM.")
        if (a.log.dropped > 0) add("The log was full: ${a.log.dropped} records were not kept, so the run is incomplete.")
        if (a.log.endNano == 0L) add("The sender did not finish (no end time in the log): it was killed or failed.")
        if (a.sends.size < (props["count"]?.toIntOrNull() ?: a.sends.size)) add("Only ${a.sends.size} of ${props["count"]} messages were sent.")
        if (unanswered > 0) add("$unanswered measured requests had no 35=8 or 35=9.")
        val untimed = a.untimed.filterValues { it > 0 }.filterKeys { it != Untimed.WARMUP }
        if (untimed.isNotEmpty()) add("Counted, not timed: " + untimed.entries.joinToString { "${it.value} ${it.key.label}" } + ".")
        val sub = pacing.first().substituted
        if (sub > 0) add("$sub crosses or cancels had nothing to act on and were sent as passive orders.")
    }

    /** The row docs/Measurements.md needs; id and "idle?" are the reader's to fill. */
    fun measurementsRow(): String {
        val main = outcomes.firstOrNull() ?: return "(no timed outcomes)"
        val s = service.getValue(main)
        val r = response.getValue(main)
        return "| M? | $date | ${props["commit"]} | ${props["host"]}, ${props["cpu"]} | ? | ${props["jvm"]} | ${exchange.ifEmpty { "?" }} | " +
            "${"%.0f".format(offered)}/s | ${"%.0f".format(achieved)}/s | ${Format.us(s.p(50.0))} / ${Format.us(s.p(99.0))} µs | " +
            "${Format.us(r.p(50.0))} / ${Format.us(r.p(99.0))} µs | fix perf ${props["mix"]}, ${a.measured.count()} measured of ${a.sends.size}, ${main.label} |"
    }
}

object Report {
    fun run(args: Args): Int {
        val logFile = File(args.required("log"))
        val compareFile = args.optional("compare")?.let(::File)
        val exchange = args.optional("exchange")
        val out = File(args.string("out", File(logFile.absoluteFile.parentFile, logFile.nameWithoutExtension + "-report").path))
        args.rejectUnused()
        val run = Run(Analysis(PerfLog.read(logFile)), exchange)
        val other = compareFile?.let { Run(Analysis(PerfLog.read(it)), null) }
        out.mkdirs()
        Text.print(run, System.out)
        writeHgrm(run, out)
        writeCsv(run, File(out, "samples.csv"))
        File(out, "report.html").writeText(Html.page(run, logFile.name, other, compareFile?.name))
        println()
        println("Measurements row (docs/Measurements.md):")
        println(run.measurementsRow())
        println()
        println("report: ${File(out, "report.html")}")
        return 0
    }

    private fun writeHgrm(run: Run, out: File) {
        fun write(name: String, values: LongArray) {
            if (values.isEmpty()) return
            val h = Histogram(3)
            for (v in values) h.recordValue(maxOf(0L, v))
            PrintStream(File(out, "$name.hgrm")).use { h.outputPercentileDistribution(it, 1000.0) }
        }
        for (o in run.outcomes) {
            val slug = o.name.lowercase().replace('_', '-')
            write("$slug-service", run.service.getValue(o).sorted)
            write("$slug-response", run.response.getValue(o).sorted)
        }
        val all = run.pacing.first()
        write("lateness", all.lateness.sorted)
        write("lateness-sender-share", all.senderShare.sorted)
        write("lateness-system-share", all.systemShare.sorted)
    }

    private fun writeCsv(run: Run, file: File) {
        val a = run.a
        val l = a.lateness
        file.printWriter().use { w ->
            w.println("outcome,request,kind,scheduled_ns,first_attempt_ns,last_attempt_ns,sent_ns,arrival_ns,service_ns,response_ns,lateness_ns,queued_ns,sender_late_ns,pushed_back_ns,send_cost_ns,sender_share_ns,system_share_ns,refusals")
            for (o in run.outcomes) {
                val s = a.samples.getValue(o)
                for (k in 0 until s.size) {
                    val i = s.request[k]
                    w.println(
                        "${o.name},$i,${Kind.name(a.sends.kind[i])},${a.sends.scheduled[i] - a.t0},${a.sends.firstAttempt[i] - a.t0}," +
                            "${a.sends.lastAttempt[i] - a.t0},${a.sends.sent[i] - a.t0}," +
                            "${s.arrival[k] - a.t0},${s.service[k]},${s.response[k]},${l.lateness[i]},${l.queued[i]},${l.senderLate[i]},${l.pushedBack[i]},${l.sendCost[i]}," +
                            "${l.senderShare[i].toLong()},${l.systemShare[i].toLong()},${a.sends.refusals[i]}",
                    )
                }
            }
        }
    }
}

/** The tables on the terminal, as the gateway's analyzer prints them. */
object Text {
    fun print(run: Run, out: PrintStream) {
        val sep = "-".repeat(104)
        out.println("fix perf: ${run.props["symbol"]}, ${run.props["schedule"]}, mix ${run.props["mix"]}; ${run.a.measured.count()} measured, ${run.a.warmup} discarded")
        out.println("offered ${"%.1f".format(run.offered)}/s, achieved ${"%.1f".format(run.achieved)}/s")
        for (w in run.warnings) out.println("WARNING: $w")
        out.println("\nLatency (µs)")
        out.println(sep)
        out.println("%-22s %-9s %9s %9s %9s %9s %9s %10s %8s".format("outcome", "", "p50", "p90", "p99", "p99.9", "p99.99", "max", "count"))
        out.println(sep)
        for (o in run.outcomes) for ((label, d) in listOf("service" to run.service.getValue(o), "response" to run.response.getValue(o))) {
            out.println(
                "%-22s %-9s %9s %9s %9s %9s %9s %10s %8d".format(
                    o.label, label, *PERCENTILES.map { Format.us(d.p(it)) }.toTypedArray(), Format.us(d.max), d.count,
                ),
            )
        }
        out.println(sep)
        out.println("\nPacing: lateness from the schedule (µs), and who it is owed to")
        out.println(sep)
        out.println("%-9s %8s %9s %9s %10s %10s %11s %11s %10s %9s %9s %9s".format("kind", "count", "p50", "p99", "max", "queued99", "senderLt99", "pushedBk99", "sendCost99", "sender%", "system%", "refusals"))
        out.println(sep)
        for (p in run.pacing) {
            out.println(
                "%-9s %8d %9s %9s %10s %10s %11s %11s %10s %8.1f%% %8.1f%% %9d".format(
                    p.name, p.count, Format.us(p.lateness.p(50.0)), Format.us(p.lateness.p(99.0)), Format.us(p.lateness.max),
                    Format.us(p.queued.p(99.0)), Format.us(p.senderLate.p(99.0)), Format.us(p.pushedBack.p(99.0)), Format.us(p.sendCost.p(99.0)),
                    share(p.totalSender, p.totalLateness), share(p.totalSystem, p.totalLateness), p.refusals,
                ),
            )
        }
        out.println(sep)
        out.println("\nThroughput")
        out.println(sep)
        out.println("%-22s %8s %9s %10s %12s %12s %12s".format("stream", "count", "span (s)", "msg/s", "gap p50 µs", "gap p99 µs", "gap max µs"))
        out.println(sep)
        for (t in run.throughput) {
            out.println(
                "%-22s %8d %9.3f %10.1f %12s %12s %12s".format(
                    t.stream, t.count, t.spanNs / 1e9, t.ratePerSecond, Format.us(t.gapP50), Format.us(t.gapP99), Format.us(t.gapMax),
                ),
            )
        }
        out.println(sep)
    }

    fun share(part: Double, total: Double) = if (total <= 0) 0.0 else 100 * part / total
}
