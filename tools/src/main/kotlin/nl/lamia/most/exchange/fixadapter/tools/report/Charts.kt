package nl.lamia.most.exchange.fixadapter.tools.report

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** A data series: [slot] is its categorical colour, 1 to 8, or 0 for the muted ink. */
class Series(val name: String, val slot: Int, val points: List<DoubleArray>, val dashed: Boolean = false)

/** An axis over data values; log axes take values above 0. */
class Axis(val label: String, val min: Double, val max: Double, val log: Boolean, val ticks: List<Pair<Double, String>>) {
    fun fraction(v: Double): Double =
        if (log) (log10(v) - log10(min)) / (log10(max) - log10(min)) else (v - min) / (max - min)

    companion object {
        /** A log axis of nanoseconds, labelled in ns, µs, ms and s, at each decade (and 2, 5 if narrow). */
        fun latency(label: String, minNs: Double, maxNs: Double): Axis {
            val lo = 10.0.pow(floor(log10(max(1.0, minNs))))
            val hi = 10.0.pow(ceil(log10(max(lo * 10, maxNs))))
            val ticks = ArrayList<Pair<Double, String>>()
            var d = lo
            val narrow = hi / lo <= 100
            while (d <= hi * 1.0001) {
                ticks += d to Format.ns(d)
                if (narrow && d < hi) {
                    ticks += 2 * d to Format.ns(2 * d)
                    ticks += 5 * d to Format.ns(5 * d)
                }
                d *= 10
            }
            return Axis(label, lo, hi, true, ticks)
        }

        fun linear(label: String, min: Double, max: Double, format: (Double) -> String, steps: Int = 5): Axis {
            val hi = if (max <= min) min + 1 else max
            val step = niceStep((hi - min) / steps)
            val top = ceil(hi / step) * step
            val ticks = generateSequence(min) { it + step }.takeWhile { it <= top + step / 2 }.map { it to format(it) }.toList()
            return Axis(label, min, top, false, ticks)
        }

        private fun niceStep(raw: Double): Double {
            val mag = 10.0.pow(floor(log10(raw)))
            val f = raw / mag
            return mag * when {
                f <= 1 -> 1.0
                f <= 2 -> 2.0
                f <= 5 -> 5.0
                else -> 10.0
            }
        }

        /** The HDR percentile axis: x = log₁₀(1 / (1 − p)), labelled 0%, 90%, 99%, 99.9% ... */
        fun percentile(nines: Int): Axis {
            val labels = listOf("0%", "90%", "99%", "99.9%", "99.99%", "99.999%", "99.9999%")
            return Axis("percentile", 0.0, nines.toDouble(), false, (0..nines).map { it.toDouble() to labels[min(it, labels.size - 1)] })
        }
    }
}

object Format {
    fun ns(v: Double): String = when {
        v >= 1e9 -> trim(v / 1e9) + " s"
        v >= 1e6 -> trim(v / 1e6) + " ms"
        v >= 1e3 -> trim(v / 1e3) + " µs"
        else -> trim(v) + " ns"
    }

    /** A latency in µs for a table cell: three significant figures at least. */
    fun us(ns: Long): String = us(ns.toDouble())

    fun us(ns: Double): String {
        val us = ns / 1000.0
        return when {
            us >= 1000 -> "%.0f".format(us)
            us >= 100 -> "%.1f".format(us)
            us >= 10 -> "%.2f".format(us)
            else -> "%.3f".format(us)
        }
    }

    private fun trim(v: Double): String = if (v == floor(v)) "%.0f".format(v) else "%.3g".format(v)

    fun escape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun js(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("<", "\\u003c") + "\""
}

/**
 * An inline SVG chart: lines, step lines or points, one x and one y axis, recessive grid, a legend
 * for two or more series. Hover is the page's script: a crosshair over [hoverColumns] for lines, the
 * nearest point for scatters. Every value it shows is also in a table.
 */
class Chart(
    private val id: String,
    private val title: String,
    private val x: Axis,
    private val y: Axis,
    private val series: List<Series>,
    private val kind: Kind = Kind.LINE,
    private val width: Int = 560,
    private val height: Int = 300,
    /** For a line chart's crosshair: an x value, its label, and each series' value text. */
    private val hoverColumns: List<Triple<Double, String, List<String>>> = emptyList(),
) {
    enum class Kind { LINE, STEP, POINTS }

    private val left = 64.0
    private val right = 30.0
    private val top = 12.0
    private val bottom = 44.0
    private val plotW = width - left - right
    private val plotH = height - top - bottom

    private fun px(v: Double) = left + x.fraction(v).coerceIn(-0.02, 1.02) * plotW
    private fun py(v: Double) = top + (1 - y.fraction(v).coerceIn(-0.02, 1.02)) * plotH

    fun render(): String = buildString {
        append("<figure class=\"chart\" id=\"").append(id).append("\">")
        append("<figcaption>").append(Format.escape(title)).append("</figcaption>")
        if (series.size >= 2) {
            append("<div class=\"legend\">")
            for (s in series) {
                append("<span><i class=\"key s").append(s.slot).append(if (s.dashed) " dashed" else "").append("\"></i>")
                append(Format.escape(s.name)).append("</span>")
            }
            append("</div>")
        }
        append("<svg viewBox=\"0 0 ").append(width).append(' ').append(height).append("\" role=\"img\" aria-label=\"")
            .append(Format.escape(title)).append("\">")
        // Grid and ticks.
        for ((v, label) in y.ticks) {
            val yy = py(v)
            append("<line class=\"grid\" x1=\"").append(f(left)).append("\" x2=\"").append(f(left + plotW))
                .append("\" y1=\"").append(f(yy)).append("\" y2=\"").append(f(yy)).append("\"/>")
            append("<text class=\"tick\" x=\"").append(f(left - 6)).append("\" y=\"").append(f(yy + 4))
                .append("\" text-anchor=\"end\">").append(Format.escape(label)).append("</text>")
        }
        for ((v, label) in x.ticks) {
            val xx = px(v)
            append("<line class=\"grid\" x1=\"").append(f(xx)).append("\" x2=\"").append(f(xx))
                .append("\" y1=\"").append(f(top)).append("\" y2=\"").append(f(top + plotH)).append("\"/>")
            append("<text class=\"tick\" x=\"").append(f(xx)).append("\" y=\"").append(f(top + plotH + 16))
                .append("\" text-anchor=\"middle\">").append(Format.escape(label)).append("</text>")
        }
        append("<line class=\"axis\" x1=\"").append(f(left)).append("\" x2=\"").append(f(left + plotW))
            .append("\" y1=\"").append(f(top + plotH)).append("\" y2=\"").append(f(top + plotH)).append("\"/>")
        append("<text class=\"axis-label\" x=\"").append(f(left + plotW / 2)).append("\" y=\"").append(height - 6)
            .append("\" text-anchor=\"middle\">").append(Format.escape(x.label)).append("</text>")
        append("<text class=\"axis-label\" transform=\"translate(14 ").append(f(top + plotH / 2))
            .append(") rotate(-90)\" text-anchor=\"middle\">").append(Format.escape(y.label)).append("</text>")
        // Marks.
        append("<g>")
        for (s in series) {
            val pts = s.points.filter { (!x.log || it[0] > 0) && (!y.log || it[1] > 0) }
            if (pts.isEmpty()) continue
            when (kind) {
                Kind.POINTS -> {
                    append("<g class=\"pts f").append(s.slot).append("\">")
                    for (p in pts) {
                        append("<circle cx=\"").append(f(px(p[0]))).append("\" cy=\"").append(f(py(p[1]))).append("\" r=\"2\"/>")
                    }
                    append("</g>")
                }
                Kind.LINE, Kind.STEP -> {
                    append("<path class=\"line s").append(s.slot).append(if (s.dashed) " dashed" else "").append("\" d=\"")
                    var first = true
                    for (p in pts) {
                        val xx = px(p[0])
                        val yy = py(p[1])
                        if (first) append('M').append(f(xx)).append(',').append(f(yy))
                        else if (kind == Kind.STEP) append('H').append(f(xx)).append('V').append(f(yy))
                        else append('L').append(f(xx)).append(',').append(f(yy))
                        first = false
                    }
                    append("\"/>")
                }
            }
        }
        append("</g>")
        append("<line class=\"crosshair\" y1=\"").append(f(top)).append("\" y2=\"").append(f(top + plotH)).append("\" x1=\"-10\" x2=\"-10\"/>")
        append("<circle class=\"nearest\" r=\"5\" cx=\"-10\" cy=\"-10\"/>")
        append("</svg>")
        append("<div class=\"tip\" hidden></div>")
        append("<script type=\"application/json\">")
        append(hoverData())
        append("</script></figure>")
    }

    /** The hover layer's data: columns for a crosshair, or every point drawn for the nearest. */
    private fun hoverData(): String = buildString {
        if (kind == Kind.POINTS) {
            append("{\"mode\":\"xy\",\"w\":").append(width).append(",\"points\":[")
            var first = true
            for (s in series) for (p in s.points) {
                if ((x.log && p[0] <= 0) || (y.log && p[1] <= 0)) continue
                if (!first) append(',')
                first = false
                append('[').append(f(px(p[0]))).append(',').append(f(py(p[1]))).append(',')
                append(Format.js("${s.name}: ${Format.ns(p[1])} at ${"%.3f".format(p[0])} s")).append(']')
            }
            append("]}")
        } else {
            append("{\"mode\":\"x\",\"w\":").append(width).append(",\"names\":[")
            append(series.joinToString(",") { Format.js(it.name) }).append("],\"slots\":[")
            append(series.joinToString(",") { it.slot.toString() }).append("],\"columns\":[")
            append(hoverColumns.joinToString(",") { (xv, label, values) ->
                "[" + f(px(xv)) + "," + Format.js(label) + "," + values.joinToString(",", "[", "]") { Format.js(it) } + "]"
            })
            append("]}")
        }
    }

    private fun f(v: Double): String = "%.1f".format(java.util.Locale.ROOT, v)
}

/** Keeps a scatter legible and its outliers visible: per x bucket, the lowest and the highest point. */
fun extremesPerBucket(xs: DoubleArray, ys: DoubleArray, buckets: Int): List<DoubleArray> {
    if (xs.isEmpty()) return emptyList()
    val lo = xs.min()
    val hi = xs.max()
    val width = if (hi > lo) (hi - lo) / buckets else 1.0
    val minAt = IntArray(buckets + 1) { -1 }
    val maxAt = IntArray(buckets + 1) { -1 }
    for (i in xs.indices) {
        val b = ((xs[i] - lo) / width).toInt().coerceIn(0, buckets)
        if (minAt[b] < 0 || ys[i] < ys[minAt[b]]) minAt[b] = i
        if (maxAt[b] < 0 || ys[i] > ys[maxAt[b]]) maxAt[b] = i
    }
    val out = ArrayList<DoubleArray>()
    for (b in 0..buckets) {
        if (minAt[b] >= 0) out += doubleArrayOf(xs[minAt[b]], ys[minAt[b]])
        if (maxAt[b] >= 0 && maxAt[b] != minAt[b]) out += doubleArrayOf(xs[maxAt[b]], ys[maxAt[b]])
    }
    return out
}
