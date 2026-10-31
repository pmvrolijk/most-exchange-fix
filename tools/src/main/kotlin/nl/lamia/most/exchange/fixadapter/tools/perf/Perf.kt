package nl.lamia.most.exchange.fixadapter.tools.perf

import nl.lamia.most.exchange.fixadapter.codecs.builder.NewOrderSingleEncoder
import nl.lamia.most.exchange.fixadapter.codecs.builder.OrderCancelRequestEncoder
import nl.lamia.most.exchange.fixadapter.tools.Args
import nl.lamia.most.exchange.fixadapter.tools.FixClient
import nl.lamia.most.exchange.fixadapter.tools.Logon
import nl.lamia.most.exchange.fixadapter.tools.RawHandler
import org.agrona.DirectBuffer
import uk.co.real_logic.artio.builder.Encoder
import uk.co.real_logic.artio.fields.UtcTimestampEncoder
import java.io.File
import java.math.BigDecimal
import java.net.InetAddress
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * `fix perf`, the measuring sender (Design.md §10.1): new orders and cancels to a fixed schedule,
 * every send and every report recorded in a memory-mapped log for `fix report`.
 */
class Perf(args: Args, private val argv: String) {
    private val symbol = args.required("symbol")
    val schedule: Schedule = run {
        val steps = args.optional("schedule")
        val rate = args.optional("rate")
        require((steps == null) != (rate == null)) { "give one of --rate (with --count) or --schedule" }
        if (steps != null) Schedule.steps(steps) else Schedule.rate(args.int("rate"), args.int("count"))
    }
    private val mixSpec = args.string("mix", "passive=60,cross=20,cancel=20")
    private val mixPercent = parseMix(mixSpec)
    private val mid = args.decimal("mid")
    private val tick = args.decimal("tick", "0.01")
    private val levels = args.int("levels", 5)
    private val qty = args.int("qty", 10).toLong()
    private val warmup = args.int("warmup", 0)
    private val prefixText = args.string("prefix", "P${java.lang.Long.toString(System.currentTimeMillis() / 1000, 36)}-")
    private val logFile = File(args.required("log"))
    private val drainMs = (args.double("drain", 10.0) * 1000).toLong()
    private val settleMs = (args.double("settle", 1.0) * 1000).toLong()
    private val busySpin = args.flag("busy-spin")
    private val seed = args.int("seed", 42)
    private val exchange = args.string("exchange", "")
    private val capacity = args.optional("log-records")?.toLong() ?: (schedule.count * 5L + 1024)
    // SelfMatchPreventionID (2362) on passive orders and on crosses: different, or most-exchange's
    // self-match prevention cancels every cross on the participant's own orders (Design.md §10.1).
    private val smpIds = args.string("smp-ids", "1,2").split(',').map {
        it.trim().toLongOrNull()?.takeIf { v -> v > 0 } ?: throw IllegalArgumentException("--smp-ids takes two positive integers, not $it")
    }

    init {
        require(warmup in 0 until schedule.count) { "--warmup must be below the message count" }
        require(prefixText.isNotEmpty() && !prefixText.last().isDigit()) { "--prefix must not end with a digit" }
        require(prefixText.length + 9 <= 20) { "--prefix is too long for a ClOrdID" }
        require(tick.signum() > 0) { "--tick must be above 0" }
        require(smpIds.size == 2 && smpIds[0] != smpIds[1]) { "--smp-ids takes two different ids: the passive orders', the crosses'" }
        require(mid.subtract(tick.multiply(BigDecimal(levels))).signum() > 0) { "--mid is too close to 0 for --levels ticks" }
        args.rejectUnused()
    }

    // Prices as mantissa and scale: mid + ticks · tick, at the finer of the two scales.
    private val scale = maxOf(mid.scale(), tick.scale(), 0)
    private val midMantissa = mid.setScale(scale).unscaledValue().longValueExact()
    private val tickMantissa = tick.setScale(scale).unscaledValue().longValueExact()

    /** Bytes the send thread allocated during the paced run (Artio's library poll included). */
    var senderAllocated = -1L
        private set

    fun run(state: File, logon: Logon): Int {
        val log = PerfLogWriter(logFile, capacity, describe(logon), warmup)
        val sender = Sender(log)
        var sent = 0
        FixClient(state, busySpin, sender).use { client ->
            sender.client = client
            client.logon(logon)
            println("perf: logged on as ${logon.senderCompId}; ${schedule.count} messages, $schedule, mix $mixSpec, warmup $warmup")
            if (warmup == 0) println("perf: no discard pass (--warmup): the first messages carry the cold JVM")
            client.pollFor(settleMs)
            val t0Wall = System.currentTimeMillis()
            val t0 = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(10)
            log.start(t0Wall + 10, t0)
            val pacer = Pacer(schedule, NanoClock(System::nanoTime), sender)
            val allocatedBefore = threads.currentThreadAllocatedBytes
            sent = pacer.run(t0)
            val sendEnd = System.nanoTime()
            senderAllocated = threads.currentThreadAllocatedBytes - allocatedBefore
            println("perf: sent $sent of ${schedule.count} in ${"%.3f".format((sendEnd - t0) / 1e9)} s (scheduled ${"%.3f".format(schedule.spanNs / 1e9)} s)")
            val deadline = sendEnd + TimeUnit.MILLISECONDS.toNanos(drainMs)
            while (sender.unanswered > 0 && System.nanoTime() < deadline && !client.disconnected) {
                if (client.poll() == 0) Thread.onSpinWait()
            }
            client.pollFor(200)
            log.finish(System.nanoTime(), senderAllocated)
            println("perf: the send thread allocated $senderAllocated bytes while paced")
            println("perf: unanswered ${sender.unanswered}, substituted ${sender.substituted}, refusals ${sender.refusals}; log ${log.recorded} records, dropped ${log.droppedRecords}")
            if (client.disconnected) println("perf: the session dropped")
        }
        log.close()
        println("perf: wrote $logFile; fix report --log $logFile")
        return if (sent == schedule.count && log.droppedRecords == 0L) 0 else 1
    }

    /** The run's arguments and provenance, as `key=value` lines in the log's header. */
    private fun describe(logon: Logon): String {
        val rt = Runtime.getRuntime()
        return listOf(
            "command" to "fix perf $argv",
            "started" to Instant.now().toString(),
            "sender" to logon.senderCompId,
            "smpIds" to smpIds.joinToString(",") + " (passive, cross)",
            "target" to "${logon.host}:${logon.port} ${logon.targetCompId}",
            "symbol" to symbol,
            "schedule" to schedule.toString(),
            "offeredRate" to "%.1f".format(schedule.offeredRate),
            "count" to schedule.count.toString(),
            "mix" to mixSpec,
            "mid" to mid.toPlainString(),
            "tick" to tick.toPlainString(),
            "levels" to levels.toString(),
            "qty" to qty.toString(),
            "prefix" to prefixText,
            "busySpin" to busySpin.toString(),
            "exchange" to exchange,
            "commit" to command("git", "describe", "--always", "--dirty"),
            "host" to runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("unknown"),
            "os" to "${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}",
            "cpus" to rt.availableProcessors().toString(),
            "cpu" to cpuModel(),
            "jvm" to "${System.getProperty("java.vm.name")} ${System.getProperty("java.runtime.version")}",
            "maxHeap" to "${rt.maxMemory() / (1024 * 1024)} MiB",
        ).joinToString("\n") { "${it.first}=${it.second.replace('\n', ' ')}" }
    }

    private inner class Sender(private val log: PerfLogWriter) : PacedSender, RawHandler {
        lateinit var client: FixClient
        private val mix = OrderMix(schedule.count, levels, qty)
        private val random = Random(seed)
        private val prefix = prefixText.toByteArray(Charsets.US_ASCII)
        private val symbolBytes = symbol.toByteArray(Charsets.US_ASCII)
        private val clOrdId = ByteArray(32)
        private val origClOrdId = ByteArray(32)
        private val timestamp = UtcTimestampEncoder()
        private val newOrder = NewOrderSingleEncoder()
        private val cancel = OrderCancelRequestEncoder()
        private var prepared: Encoder = newOrder
        private val smpPassive = smpIds[0].toString().toByteArray(Charsets.US_ASCII)
        private val smpPassiveLength = smpPassive.size
        private val smpCross = smpIds[1].toString().toByteArray(Charsets.US_ASCII)
        private val smpCrossLength = smpCross.size
        private val fix = RawFix(prefixText)
        private val answered = BooleanArray(schedule.count)
        private var sentCount = 0
        var unanswered = 0; private set
        var substituted = 0; private set
        var refusals = 0L; private set

        override fun prepare(index: Int) {
            val roll = random.nextInt(100)
            val wanted = when {
                roll < mixPercent[0] -> Kind.PASSIVE
                roll < mixPercent[0] + mixPercent[1] -> Kind.CROSS
                else -> Kind.CANCEL
            }
            mix.prepare(wanted)
            val length = encodeClOrdId(prefix, index, clOrdId)
            val tsLength = timestamp.encode(System.currentTimeMillis())
            val side = Char(mix.side.toInt())
            // Artio's byte[] setters wrap the array they are given: reset first, every time.
            if (mix.kind == Kind.CANCEL) {
                cancel.reset()
                cancel.origClOrdID(origClOrdId, encodeClOrdId(prefix, mix.target, origClOrdId))
                cancel.clOrdID(clOrdId, length)
                cancel.instrument().symbol(symbolBytes)
                cancel.side(side)
                cancel.transactTime(timestamp.buffer(), tsLength)
                cancel.orderQtyData().orderQty(mix.targetQty, 0)
                prepared = cancel
            } else {
                newOrder.reset()
                newOrder.clOrdID(clOrdId, length)
                newOrder.side(side)
                newOrder.transactTime(timestamp.buffer(), tsLength)
                newOrder.ordType('2')
                newOrder.instrument().symbol(symbolBytes)
                newOrder.orderQtyData().orderQty(mix.orderQty, 0)
                newOrder.price(midMantissa + mix.priceTicks * tickMantissa, scale)
                newOrder.selfMatchPreventionID(if (mix.kind == Kind.CROSS) smpCross else smpPassive, if (mix.kind == Kind.CROSS) smpCrossLength else smpPassiveLength)
                prepared = newOrder
            }
        }

        override fun trySend(): Boolean = client.trySend(prepared)

        override fun sent(index: Int, scheduledNs: Long, firstAttemptNs: Long, lastAttemptNs: Long, sentNs: Long, refusals: Int) {
            var flags = 0
            if (index < warmup) flags = flags or PerfLogFormat.SEND_WARMUP
            if (mix.substituted) {
                flags = flags or PerfLogFormat.SEND_SUBSTITUTED
                substituted++
            }
            log.send(index, mix.kind, mix.side, flags, mix.target, refusals, mix.priceTicks, scheduledNs, firstAttemptNs, sentNs, sentNs - lastAttemptNs)
            mix.commit(index)
            this.refusals += refusals
            sentCount++
            unanswered++
        }

        override fun poll(): Int = client.poll()

        override fun failed(): Boolean = client.disconnected

        override fun onMessage(buffer: DirectBuffer, offset: Int, length: Int) {
            val arrival = System.nanoTime()
            fix.parse(buffer, offset, length)
            if (fix.longMsgType) return
            when (fix.msgType) {
                MSG_EXECUTION_REPORT, MSG_CANCEL_REJECT, MSG_REJECT, MSG_BUSINESS_REJECT -> Unit
                else -> return
            }
            var flags = 0
            if (fix.possDup) flags = flags or PerfLogFormat.RECEIVE_POSS_DUP
            if (fix.unknown) flags = flags or PerfLogFormat.RECEIVE_UNKNOWN
            log.receive(fix.msgType, fix.execType, fix.ordStatus, flags, fix.index, fix.origIndex, arrival)
            val i = fix.index
            if ((fix.msgType == MSG_EXECUTION_REPORT || fix.msgType == MSG_CANCEL_REJECT) && i in 0 until sentCount && !answered[i]) {
                answered[i] = true
                unanswered--
            }
        }
    }

    companion object {
        private val threads = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        private const val MSG_EXECUTION_REPORT = '8'.code.toByte()
        private const val MSG_CANCEL_REJECT = '9'.code.toByte()
        private const val MSG_REJECT = '3'.code.toByte()
        private const val MSG_BUSINESS_REJECT = 'j'.code.toByte()

        /** `passive=P,cross=C,cancel=X`, in percent, summing to 100; as [passive, cross, cancel]. */
        fun parseMix(spec: String): IntArray {
            val out = IntArray(3)
            for (part in spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
                val key = part.substringBefore('=')
                val value = part.substringAfter('=', "").toIntOrNull()
                    ?: throw IllegalArgumentException("--mix takes passive=P,cross=C,cancel=X, not $part")
                require(value >= 0) { "--mix: $key cannot be negative" }
                when (key) {
                    "passive" -> out[0] = value
                    "cross" -> out[1] = value
                    "cancel" -> out[2] = value
                    else -> throw IllegalArgumentException("--mix knows passive, cross and cancel, not $key")
                }
            }
            require(out.sum() == 100) { "--mix must sum to 100, not ${out.sum()}" }
            return out
        }

        private fun command(vararg cmd: String): String = runCatching {
            val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            if (p.waitFor() == 0 && out.isNotEmpty()) out else "unknown"
        }.getOrDefault("unknown")

        private fun cpuModel(): String {
            val linux = File("/proc/cpuinfo")
            if (linux.canRead()) return linux.readLines().firstOrNull { it.startsWith("model name") }?.substringAfter(':')?.trim() ?: "unknown"
            return command("sysctl", "-n", "machdep.cpu.brand_string")
        }
    }
}
