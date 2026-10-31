package nl.lamia.most.exchange.fixadapter.tools.report

import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * `report.html` (Design.md §10.2): one self-contained page, inline SVG and an inline script for the
 * hover layer, no external requests. Light, dark and print, from one set of colour tokens.
 */
object Html {
    // The validated reference palette (dataviz): eight categorical slots, light then dark.
    private val light = listOf("#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4", "#008300", "#6250d6", "#e34948")
    private val dark = listOf("#3987e5", "#d95926", "#199e70", "#c98500", "#d55181", "#008300", "#9085e9", "#e66767")

    /** An outcome keeps its colour on every chart (colour follows the entity). */
    fun slot(o: Outcome) = o.ordinal + 1

    fun page(run: Run, logName: String, other: Run?, otherName: String?): String = buildString {
        val title = "Latency report"
        append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
        append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        append("<title>").append(title).append("</title><style>").append(css()).append("</style></head><body><main>")
        append("<h1>fix perf: ").append(e(run.props["symbol"].orEmpty())).append(" at ")
            .append("%.0f".format(run.offered)).append("/s offered</h1>")
        append("<p class=\"sub\">").append(e(logName)).append(" · ").append(e(run.props["started"].orEmpty()))
            .append(" · ").append(run.a.measured.count()).append(" measured requests, ").append(run.a.warmup).append(" discarded</p>")
        summary(run)
        for (w in run.warnings) append("<p class=\"warn\"><b>Note</b> ").append(e(w)).append("</p>")
        provenance(run)
        latencyTable(run)
        pacingTable(run)
        throughputTable(run)
        charts(run)
        if (other != null) compare(run, logName, other, otherName.orEmpty())
        append("</main><script>").append(SCRIPT).append("</script></body></html>")
    }

    private fun StringBuilder.summary(run: Run) {
        val all = run.pacing.first()
        append("<section class=\"tiles\">")
        tile("offered", "%.0f/s".format(run.offered))
        tile("achieved", "%.0f/s".format(run.achieved))
        for (o in run.outcomes.take(3)) {
            tile("${o.label} response p99", Format.ns(run.response.getValue(o).p(99.0).toDouble()))
            tile("${o.label} service p99", Format.ns(run.service.getValue(o).p(99.0).toDouble()))
        }
        tile("lateness p99", Format.ns(all.lateness.p(99.0).toDouble()))
        tile(
            "lateness owed to",
            "sender %.0f%% · system %.0f%%".format(Text.share(all.totalSender, all.totalLateness), Text.share(all.totalSystem, all.totalLateness)),
        )
        append("</section>")
    }

    private fun StringBuilder.tile(label: String, value: String) {
        append("<div class=\"tile\"><span>").append(e(label)).append("</span><b>").append(e(value)).append("</b></div>")
    }

    private fun StringBuilder.provenance(run: Run) {
        append("<details><summary>Provenance</summary><div class=\"scroll\"><table class=\"kv\"><tbody>")
        val keys = listOf("command", "started", "commit", "host", "cpu", "cpus", "os", "jvm", "maxHeap", "busySpin", "sender", "target",
            "symbol", "schedule", "offeredRate", "count", "mix", "mid", "tick", "levels", "qty", "prefix")
        for (k in keys) row(k, run.props[k].orEmpty())
        row("exchange", run.exchange.ifEmpty { "(not stated: --exchange)" })
        row("achieved rate", "%.1f/s".format(run.achieved))
        row("send thread allocated while paced", if (run.a.log.senderAllocated < 0) "not measured" else "%,d bytes".format(run.a.log.senderAllocated))
        append("</tbody></table></div></details>")
    }

    private fun StringBuilder.row(k: String, v: String) {
        append("<tr><th>").append(e(k)).append("</th><td>").append(e(v)).append("</td></tr>")
    }

    private fun StringBuilder.latencyTable(run: Run) {
        append("<h2>Latency by outcome (µs)</h2>")
        append("<p class=\"note\">Service time from the send Artio took; response time from the scheduled send. ")
        append("A resting order's fill is timed from the cross that hit it.</p>")
        append("<div class=\"scroll\"><table><thead><tr><th>outcome</th><th></th>")
        for (p in PERCENTILES) append("<th>p").append(pct(p)).append("</th>")
        append("<th>max</th><th>count</th></tr></thead><tbody>")
        for (o in run.outcomes) for ((label, d) in listOf("service" to run.service.getValue(o), "response" to run.response.getValue(o))) {
            append("<tr><td><i class=\"key s").append(slot(o)).append("\"></i>").append(e(o.label)).append("</td><td>").append(label).append("</td>")
            for (p in PERCENTILES) append("<td>").append(Format.us(d.p(p))).append("</td>")
            append("<td>").append(Format.us(d.max)).append("</td><td>").append(d.count).append("</td></tr>")
        }
        append("</tbody></table></div>")
    }

    private fun StringBuilder.pacingTable(run: Run) {
        append("<h2>Pacing: lateness from the schedule (µs)</h2>")
        append("<p class=\"note\">Lateness = queued (behind the message before) + sender late (the sender's thread elsewhere) + ")
        append("pushed back (Artio refused it) + send cost (the trySend Artio took, encoding on the sender's thread). Its shares trace ")
        append("the queueing to its root: the <b>sender</b> (the generator was the bottleneck) or the <b>system</b> (the client's ")
        append("engine and everything after it).</p>")
        append("<div class=\"scroll\"><table><thead><tr><th>kind</th><th>count</th><th>p50</th><th>p99</th><th>p99.9</th><th>max</th>")
        append("<th>queued p99</th><th>sender late p99</th><th>pushed back p99</th><th>send cost p99</th><th>sender share p99</th><th>system share p99</th>")
        append("<th>owed to sender</th><th>owed to system</th><th>refusals</th></tr></thead><tbody>")
        for (p in run.pacing) {
            append("<tr><td>").append(e(p.name)).append("</td><td>").append(p.count).append("</td>")
            for (v in listOf(p.lateness.p(50.0), p.lateness.p(99.0), p.lateness.p(99.9), p.lateness.max, p.queued.p(99.0), p.senderLate.p(99.0),
                p.pushedBack.p(99.0), p.sendCost.p(99.0), p.senderShare.p(99.0), p.systemShare.p(99.0))) append("<td>").append(Format.us(v)).append("</td>")
            append("<td>").append("%.1f%%".format(Text.share(p.totalSender, p.totalLateness))).append("</td>")
            append("<td>").append("%.1f%%".format(Text.share(p.totalSystem, p.totalLateness))).append("</td>")
            append("<td>").append(p.refusals).append("</td></tr>")
        }
        append("</tbody></table></div>")
    }

    private fun StringBuilder.throughputTable(run: Run) {
        append("<h2>Throughput</h2>")
        append("<p class=\"note\">Rate is (count − 1) / span. A stage that stalls keeps its mean rate while its gap p99 and max blow out.</p>")
        append("<div class=\"scroll\"><table><thead><tr><th>stream</th><th>count</th><th>span (s)</th><th>msg/s</th>")
        append("<th>gap p50 (µs)</th><th>gap p99 (µs)</th><th>gap max (µs)</th></tr></thead><tbody>")
        for (t in run.throughput) {
            append("<tr><td>").append(e(t.stream)).append("</td><td>").append(t.count).append("</td><td>").append("%.3f".format(t.spanNs / 1e9))
                .append("</td><td>").append(if (t.ratePerSecond.isNaN()) "-" else "%.1f".format(t.ratePerSecond)).append("</td><td>")
                .append(Format.us(t.gapP50)).append("</td><td>").append(Format.us(t.gapP99)).append("</td><td>").append(Format.us(t.gapMax)).append("</td></tr>")
        }
        append("</tbody></table></div>")
    }

    private fun StringBuilder.charts(run: Run) {
        if (run.outcomes.isEmpty()) return
        append("<h2>Distributions</h2><div class=\"grid\">")
        for ((label, dists) in listOf("Response time" to run.response, "Service time" to run.service)) {
            append(percentileChart("pct-${label.first()}", "$label, percentile distribution", run.outcomes.map { Triple(it.label, slot(it), dists.getValue(it).sorted) }))
        }
        for ((label, dists) in listOf("Response time" to run.response, "Service time" to run.service)) {
            append(cdfChart("cdf-${label.first()}", "$label, CDF", run.outcomes.map { Triple(it.label, slot(it), dists.getValue(it).sorted) }))
        }
        for ((label, dists) in listOf("Response time" to run.response, "Service time" to run.service)) {
            append(histogramChart("hist-${label.first()}", "$label, histogram (log bins)", run.outcomes.map { Triple(it.label, slot(it), dists.getValue(it).sorted) }))
        }
        append("</div>")

        append("<h2>Over the run</h2>")
        append("<p class=\"note\">Each request's response (its colour) and service (grey) time against when it arrived. The lowest and highest point of each slice of time is drawn, so no outlier is thinned away.</p>")
        append("<div class=\"grid small\">")
        val a = run.a
        val spanS = max(1e-3, ((a.log.endNano.takeIf { it > 0 } ?: a.sends.sent.lastOrNull() ?: a.t0) - a.t0) / 1e9)
        val allLat = run.outcomes.flatMap { listOf(run.response.getValue(it).sorted, run.service.getValue(it).sorted) }
        val yAxis = Axis.latency("latency", allLat.minOf { it.firstOrNull()?.toDouble() ?: 1.0 }, allLat.maxOf { it.lastOrNull()?.toDouble() ?: 10.0 })
        val xAxis = Axis.linear("seconds since T₀", 0.0, spanS, { "%.3g".format(it) })
        for (o in run.outcomes) {
            val s = a.samples.getValue(o)
            val xs = DoubleArray(s.size) { (s.arrival[it] - a.t0) / 1e9 }
            val service = extremesPerBucket(xs, DoubleArray(s.size) { s.service[it].toDouble() }, 300)
            val response = extremesPerBucket(xs, DoubleArray(s.size) { s.response[it].toDouble() }, 300)
            append(Chart("scatter-${o.name}", o.label, xAxis, yAxis, listOf(Series("service", 0, service), Series("response", slot(o), response)),
                Chart.Kind.POINTS, width = 420, height = 240).render())
        }
        append("</div><div class=\"grid\">")
        append(throughputChart(run, spanS))
        append(latenessChart(run, xAxis))
        append("</div>")
    }

    private fun nines(n: Int) = max(1, min(6, ceil(log10(max(10, n).toDouble())).toInt()))

    private fun percentileChart(id: String, title: String, series: List<Triple<String, Int, LongArray>>): String {
        val n = series.maxOf { it.third.size }
        val nines = nines(n)
        val xAxis = Axis.percentile(nines)
        val yAxis = Axis.latency("latency", series.minOf { it.third.first().toDouble() }, series.maxOf { it.third.last().toDouble() })
        val steps = 240
        val lines = series.map { (name, slot, sorted) ->
            val top = log10(sorted.size.toDouble()).coerceAtMost(nines.toDouble())
            Series(name, slot, (0..steps).map { k ->
                val xv = top * k / steps
                doubleArrayOf(xv, Percentile.of(sorted, 100 * (1 - 10.0.pow(-xv))).toDouble())
            })
        }
        val columns = (0..nines * 4).map { k ->
            val xv = k / 4.0
            val p = 100 * (1 - 10.0.pow(-xv))
            Triple(xv, "p" + pct(p), series.map { (_, _, sorted) -> if (log10(sorted.size.toDouble()) + 1e-9 < xv) "-" else Format.ns(Percentile.of(sorted, p).toDouble()) })
        }
        return Chart(id, title, xAxis, yAxis, lines, hoverColumns = columns).render()
    }

    private fun cdfChart(id: String, title: String, series: List<Triple<String, Int, LongArray>>): String {
        val lo = series.minOf { it.third.first().toDouble() }
        val hi = series.maxOf { it.third.last().toDouble() }
        val xAxis = Axis.latency("latency", lo, hi).let { Axis("latency", it.min, it.max, true, it.ticks) }
        val yAxis = Axis.linear("fraction at or below", 0.0, 1.0, { "%.1f".format(it) })
        val lines = series.map { (name, slot, sorted) ->
            val step = max(1, sorted.size / 400)
            val pts = ArrayList<DoubleArray>()
            var i = 0
            while (i < sorted.size) {
                pts += doubleArrayOf(sorted[i].toDouble(), (i + 1).toDouble() / sorted.size)
                i += step
            }
            pts += doubleArrayOf(sorted.last().toDouble(), 1.0)
            Series(name, slot, pts)
        }
        val columns = logSteps(xAxis.min, xAxis.max, 48).map { xv ->
            Triple(xv, "at or below ${Format.ns(xv)}", series.map { (_, _, sorted) -> "%.2f%%".format(100.0 * upperBound(sorted, xv.toLong()) / sorted.size) })
        }
        return Chart(id, title, xAxis, yAxis, lines, hoverColumns = columns).render()
    }

    private fun histogramChart(id: String, title: String, series: List<Triple<String, Int, LongArray>>): String {
        val lo = max(1.0, series.minOf { it.third.first().toDouble() })
        val hi = max(lo * 1.01, series.maxOf { it.third.last().toDouble() })
        val xAxis = Axis.latency("latency", lo, hi)
        val bins = 60
        val edges = DoubleArray(bins + 1) { lo * (hi / lo).pow(it.toDouble() / bins) }
        val counts = series.map { (_, _, sorted) ->
            IntArray(bins).also { c -> for (v in sorted) c[(edges.binarySearchBin(v.toDouble())).coerceIn(0, bins - 1)]++ }
        }
        val yAxis = Axis.linear("count", 0.0, counts.maxOf { it.max() }.toDouble(), { "%.0f".format(it) })
        val lines = series.mapIndexed { s, (name, slot, _) ->
            Series(name, slot, (0..bins).map { b -> doubleArrayOf(edges[b], if (b < bins) counts[s][b].toDouble() else 0.0) })
        }
        val columns = (0 until bins).map { b ->
            Triple(edges[b], "${Format.ns(edges[b])} to ${Format.ns(edges[b + 1])}", counts.map { it[b].toString() })
        }
        return Chart(id, title, xAxis, yAxis, lines, Chart.Kind.STEP, hoverColumns = columns).render()
    }

    private fun throughputChart(run: Run, spanS: Double): String {
        val a = run.a
        val buckets = 120
        val width = spanS / buckets
        val sent = DoubleArray(buckets)
        val received = DoubleArray(buckets)
        for (i in a.measured) sent[((a.sends.sent[i] - a.t0) / 1e9 / width).toInt().coerceIn(0, buckets - 1)]++
        for (o in run.outcomes) {
            val s = a.samples.getValue(o)
            for (k in 0 until s.size) received[((s.arrival[k] - a.t0) / 1e9 / width).toInt().coerceIn(0, buckets - 1)]++
        }
        val toRate = { c: DoubleArray -> (0 until buckets).map { doubleArrayOf((it + 0.5) * width, c[it] / width) } }
        val xAxis = Axis.linear("seconds since T₀", 0.0, spanS, { "%.3g".format(it) })
        val yAxis = Axis.linear("messages per second", 0.0, max(sent.max(), received.max()) / width, { "%,.0f".format(it) })
        val columns = (0 until buckets).map {
            Triple((it + 0.5) * width, "%.3f s".format((it + 0.5) * width), listOf("%,.0f/s".format(sent[it] / width), "%,.0f/s".format(received[it] / width)))
        }
        return Chart("throughput", "Throughput: requests sent and timed reports received", xAxis, yAxis,
            listOf(Series("sent", 1, toRate(sent)), Series("received", 2, toRate(received))), hoverColumns = columns).render()
    }

    private fun latenessChart(run: Run, xAxis: Axis): String {
        val a = run.a
        val idx = a.measured.toList()
        val xs = DoubleArray(idx.size) { (a.sends.scheduled[idx[it]] - a.t0) / 1e9 }
        val sender = extremesPerBucket(xs, DoubleArray(idx.size) { a.lateness.senderShare[idx[it]] }, 300).filter { it[1] > 0 }
        val system = extremesPerBucket(xs, DoubleArray(idx.size) { a.lateness.systemShare[idx[it]] }, 300).filter { it[1] > 0 }
        val all = sender + system
        if (all.isEmpty()) return "<figure class=\"chart\"><figcaption>Lateness by share</figcaption><p class=\"note\">Every message was sent on schedule.</p></figure>"
        val yAxis = Axis.latency("lateness", all.minOf { it[1] }, all.maxOf { it[1] })
        return Chart("lateness", "Lateness by share, against the scheduled send", xAxis, yAxis,
            listOf(Series("owed to the sender", 2, sender), Series("owed to the system", 1, system)), Chart.Kind.POINTS).render()
    }

    private fun StringBuilder.compare(run: Run, name: String, other: Run, otherName: String) {
        append("<h2>Compared with ").append(e(otherName)).append("</h2>")
        val keys = listOf("commit", "host", "cpu", "jvm", "busySpin", "schedule", "mix", "symbol", "levels", "qty")
        val differ = keys.filter { run.props[it] != other.props[it] } + listOfNotNull("exchange".takeIf { run.exchange != other.exchange })
        if (differ.isNotEmpty()) {
            append("<p class=\"warn\"><b>Note</b> The runs differ in ").append(e(differ.joinToString())).append(": ")
            append(e(differ.joinToString("; ") { k -> "$k ${if (k == "exchange") run.exchange else run.props[k]} vs ${if (k == "exchange") other.exchange else other.props[k]}" }))
            append("</p>")
        }
        append("<p class=\"note\">A is ").append(e(name)).append(", B is ").append(e(otherName)).append(". Change is (B − A) / A.</p>")
        append("<div class=\"scroll\"><table><thead><tr><th>outcome</th><th></th>")
        for (p in listOf("p50", "p99", "p99.9", "max")) append("<th>A ").append(p).append("</th><th>B ").append(p).append("</th><th>change</th>")
        append("</tr></thead><tbody>")
        val both = run.outcomes.filter { it in other.outcomes }
        for (o in both) for ((label, pick) in listOf<Pair<String, (Run) -> Dist>>("service" to { r -> r.service.getValue(o) }, "response" to { r -> r.response.getValue(o) })) {
            val da = pick(run)
            val db = pick(other)
            append("<tr><td><i class=\"key s").append(slot(o)).append("\"></i>").append(e(o.label)).append("</td><td>").append(label).append("</td>")
            for (v in listOf({ d: Dist -> d.p(50.0) }, { d: Dist -> d.p(99.0) }, { d: Dist -> d.p(99.9) }, { d: Dist -> d.max })) {
                val va = v(da)
                val vb = v(db)
                append("<td>").append(Format.us(va)).append("</td><td>").append(Format.us(vb)).append("</td><td>")
                    .append(if (va == 0L) "-" else "%+.1f%%".format(100.0 * (vb - va) / va)).append("</td>")
            }
            append("</tr>")
        }
        append("</tbody></table></div><div class=\"grid small\">")
        for (o in both) {
            append(percentileChart("cmp-${o.name}", "${o.label}: response, A and B", listOf(
                Triple("A", 1, run.response.getValue(o).sorted), Triple("B", 2, other.response.getValue(o).sorted),
            )).replace("class=\"line s2\"", "class=\"line s2 dashed\"").replace("class=\"key s2\"", "class=\"key s2 dashed\""))
        }
        append("</div>")
    }

    private fun logSteps(lo: Double, hi: Double, n: Int) = (0..n).map { lo * (hi / lo).pow(it.toDouble() / n) }

    /** The count of values at or below [v]. */
    private fun upperBound(sorted: LongArray, v: Long): Int {
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid] <= v) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun DoubleArray.binarySearchBin(v: Double): Int {
        var lo = 0
        var hi = size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (this[mid] <= v) lo = mid else hi = mid
        }
        return lo
    }

    private fun pct(p: Double): String = if (p == kotlin.math.floor(p)) "%.0f".format(p) else "%.6f".format(p).trimEnd('0').trimEnd('.')

    private fun e(s: String) = Format.escape(s)

    private fun css(): String = buildString {
        fun tokens(slots: List<String>, surface: String, page: String, ink: String, ink2: String, grid: String, axis: String) = buildString {
            append("--surface:$surface;--page:$page;--ink:$ink;--ink2:$ink2;--muted:#898781;--grid:$grid;--axis:$axis;")
            slots.forEachIndexed { i, c -> append("--s").append(i + 1).append(':').append(c).append(';') }
            append("--s0:#898781;")
        }
        val lightTokens = tokens(light, "#fcfcfb", "#f9f9f7", "#0b0b0b", "#52514e", "#e1e0d9", "#c3c2b7")
        val darkTokens = tokens(dark, "#1a1a19", "#0d0d0d", "#f0efec", "#c3c2b7", "#2c2c2a", "#383835")
        append(":root{color-scheme:light;").append(lightTokens).append("}")
        append("@media (prefers-color-scheme: dark){:root:not([data-theme=\"light\"]){color-scheme:dark;").append(darkTokens).append("}}")
        append(":root[data-theme=\"dark\"]{color-scheme:dark;").append(darkTokens).append("}")
        append("@media print{:root,:root[data-theme=\"dark\"]{color-scheme:light;").append(lightTokens).append("}.chart{break-inside:avoid}details{display:block}}")
        append(
            """
            body{margin:0;background:var(--page);color:var(--ink);font:14px/1.45 system-ui,-apple-system,"Segoe UI",sans-serif}
            main{max-width:1280px;margin:0 auto;padding:24px 16px 48px}
            h1{font-size:22px;margin:0 0 4px}h2{font-size:17px;margin:32px 0 6px}
            .sub,.note{color:var(--ink2);margin:0 0 10px}.note{font-size:13px;max-width:880px}
            .warn{border-left:3px solid var(--muted);padding:4px 10px;margin:8px 0;color:var(--ink2)}
            .tiles{display:flex;flex-wrap:wrap;gap:8px;margin:16px 0}
            .tile{background:var(--surface);border:1px solid var(--grid);border-radius:8px;padding:8px 12px;min-width:140px}
            .tile span{display:block;color:var(--ink2);font-size:12px}.tile b{font-size:18px;font-weight:600}
            details{margin:12px 0}summary{cursor:pointer;color:var(--ink2)}
            .scroll{overflow-x:auto}
            table{border-collapse:collapse;background:var(--surface);font-variant-numeric:tabular-nums;font-size:13px}
            th,td{border-bottom:1px solid var(--grid);padding:5px 10px;text-align:right;white-space:nowrap}
            th{color:var(--ink2);font-weight:600}td:first-child,th:first-child{text-align:left}
            table.kv th{text-align:left}table.kv td{text-align:left;white-space:normal;word-break:break-word}
            .grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,520px),1fr));gap:16px}
            .grid.small{grid-template-columns:repeat(auto-fit,minmax(min(100%,380px),1fr))}
            .chart{position:relative;margin:0;background:var(--surface);border:1px solid var(--grid);border-radius:8px;padding:10px 12px}
            .chart figcaption{font-weight:600;margin-bottom:4px}
            .chart svg{width:100%;height:auto;display:block;touch-action:none}
            .legend{display:flex;flex-wrap:wrap;gap:4px 14px;font-size:12px;color:var(--ink2);margin-bottom:4px}
            .key{display:inline-block;width:14px;height:3px;border-radius:2px;margin-right:6px;vertical-align:middle}
            .key.dashed{background:none!important;border-top:3px dashed var(--s2);height:0}
            .grid line.grid,line.grid{stroke:var(--grid);stroke-width:1}line.axis{stroke:var(--axis);stroke-width:1}
            text.tick{fill:var(--muted);font-size:11px;font-variant-numeric:tabular-nums}text.axis-label{fill:var(--ink2);font-size:11px}
            path.line{fill:none;stroke-width:2;stroke-linejoin:round}path.dashed{stroke-dasharray:6 4}
            .pts circle{opacity:.85}
            line.crosshair{stroke:var(--ink2);stroke-width:1}circle.nearest{fill:none;stroke:var(--ink);stroke-width:2}
            .tip{position:absolute;pointer-events:none;background:var(--surface);color:var(--ink);border:1px solid var(--axis);border-radius:6px;padding:6px 8px;font-size:12px;box-shadow:0 2px 8px rgba(0,0,0,.15);white-space:nowrap;z-index:2}
            .tip div{display:flex;align-items:center;gap:6px}.tip b{font-variant-numeric:tabular-nums}.tip .head{color:var(--ink2);margin-bottom:2px}
            """.trimIndent(),
        )
        for (i in 0..8) {
            append("path.line.s").append(i).append("{stroke:var(--s").append(i).append(")}")
            append(".key.s").append(i).append("{background:var(--s").append(i).append(")}")
            append(".pts.f").append(i).append(" circle{fill:var(--s").append(i).append(")}")
        }
    }

    /** The hover layer: a crosshair and every series' value at the nearest column, or the nearest point. */
    private val SCRIPT = """
        for (const fig of document.querySelectorAll('figure.chart')) {
          const json = fig.querySelector('script[type="application/json"]');
          const svg = fig.querySelector('svg');
          if (!json || !svg) continue;
          const data = JSON.parse(json.textContent);
          const tip = fig.querySelector('.tip'), cross = svg.querySelector('.crosshair'), dot = svg.querySelector('.nearest');
          const hide = () => { tip.hidden = true; cross.setAttribute('x1', -10); cross.setAttribute('x2', -10); dot.setAttribute('cx', -10); dot.setAttribute('cy', -10); };
          svg.addEventListener('pointerleave', hide);
          svg.addEventListener('pointermove', ev => {
            const r = svg.getBoundingClientRect(), k = data.w / r.width;
            const x = (ev.clientX - r.left) * k, y = (ev.clientY - r.top) * k;
            tip.replaceChildren();
            let ax, ay;
            if (data.mode === 'x') {
              if (!data.columns.length) return hide();
              let best = data.columns[0];
              for (const c of data.columns) if (Math.abs(c[0] - x) < Math.abs(best[0] - x)) best = c;
              cross.setAttribute('x1', best[0]); cross.setAttribute('x2', best[0]);
              const head = document.createElement('div'); head.className = 'head'; head.textContent = best[1]; tip.append(head);
              best[2].forEach((v, i) => {
                const row = document.createElement('div'), key = document.createElement('i'), b = document.createElement('b'), n = document.createElement('span');
                key.className = 'key s' + data.slots[i]; b.textContent = v; n.textContent = data.names[i];
                row.append(key, b, n); tip.append(row);
              });
              ax = best[0]; ay = y;
            } else {
              let best = null, bd = 24 * 24;
              for (const p of data.points) { const d = (p[0] - x) ** 2 + (p[1] - y) ** 2; if (d < bd) { bd = d; best = p; } }
              if (!best) return hide();
              dot.setAttribute('cx', best[0]); dot.setAttribute('cy', best[1]);
              const row = document.createElement('div'); row.textContent = best[2]; tip.append(row);
              ax = best[0]; ay = best[1];
            }
            tip.hidden = false;
            const fr = fig.getBoundingClientRect(), left = r.left - fr.left + ax / k, top = r.top - fr.top + ay / k;
            const flip = left + tip.offsetWidth + 16 > fig.clientWidth;
            tip.style.left = (flip ? left - tip.offsetWidth - 12 : left + 12) + 'px';
            tip.style.top = Math.max(0, top - tip.offsetHeight / 2) + 'px';
          });
        }
    """.trimIndent()
}
