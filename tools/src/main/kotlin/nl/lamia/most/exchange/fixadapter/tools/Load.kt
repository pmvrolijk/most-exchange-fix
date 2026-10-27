package nl.lamia.most.exchange.fixadapter.tools

import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * `fix load`: new orders at a paced rate, buys and sells across a price band so some trade, and a
 * share of cancels of this run's open orders. Then a drain, and a summary of each order's outcome as
 * the client last heard it: what an e2e script asserts on.
 *
 * With `--reconnect`, a dropped session is logged on again (an adapter restarting under it), and
 * the load carries on. MsgSeqNum persists, so the adapter resends what the client missed.
 *
 * Not the measuring sender of Design.md §10.1: it records no latencies.
 */
class Load(args: Args) {
    private val symbol = args.required("symbol")
    private val count = args.int("count")
    private val rate = args.int("rate")
    private val priceMin = args.decimal("price-min")
    private val priceMax = args.decimal("price-max")
    private val qtyMin = args.int("qty-min", 1)
    private val qtyMax = args.int("qty-max", 10)
    private val cancelPct = args.int("cancel-pct", 0)
    private val prefix = args.string("prefix", "L${System.currentTimeMillis() / 1000}-")
    private val drainMs = (args.double("drain", 10.0) * 1000).toLong()
    private val reconnectMs = (args.double("reconnect", 0.0) * 1000).toLong()
    private val verbose = args.flag("verbose")

    init {
        require(count > 0 && rate > 0) { "--count and --rate must be positive" }
        require(priceMin <= priceMax) { "--price-min is above --price-max" }
        require(cancelPct in 0..100) { "--cancel-pct must be 0 to 100" }
        args.rejectUnused()
    }

    private class Order(val clOrdId: String, val side: Char, val qty: BigDecimal) {
        var reports = 0
        var ordStatus: String? = null
        var unknown = false
        var text: String? = null
        var cancelSent = false
        val open get() = ordStatus == "0" || ordStatus == "1"
    }

    private val orders = LinkedHashMap<String, Order>()
    private val cancels = LinkedHashMap<String, String?>() // cancel ClOrdID -> outcome, null until answered
    private var trades = 0
    private var sessionRejects = 0
    private var businessRejects = 0
    private var possDups = 0
    private val random = Random(42)

    private fun onMessage(m: FixMessage) {
        if (verbose && m.msgType != "0" && m.msgType != "8") println("in: 34=${m[34]} ${Describe.line(m)} ${if (m.msgType in setOf("2", "4", "A")) m.fields else ""}")
        when (m.msgType) {
            "8" -> {
                if (m[43] == "Y") possDups++
                if (m[150] == "F") trades++
                val id = m[11] ?: return
                val order = orders[id] ?: m[41]?.let { orders[it] }
                if (id in cancels && m[150] == "4") cancels[id] = "CANCELED"
                if (id in cancels && m[150] == "8") cancels[id] = "REJECTED"
                order ?: return
                order.reports++
                order.ordStatus = m[39]
                order.unknown = m[5001] == "Y"
                if (m[39] == "8") order.text = m[58]
            }
            "9" -> m[11]?.let { if (it in cancels) cancels[it] = "CANCEL_REJECTED" }
            "3" -> {
                sessionRejects++
                println(Describe.line(m))
            }
            "j" -> {
                businessRejects++
                println(Describe.line(m))
            }
            "5" -> println(Describe.line(m))
        }
    }

    fun run(state: File, logon: Logon): Int {
        FixClient(state, ::onMessage).use { client ->
            client.logon(logon)
            println("load: logged on as ${logon.senderCompId}; $count orders at $rate/s on $symbol, ${cancelPct}% cancels")
            val intervalNs = TimeUnit.SECONDS.toNanos(1) / rate
            val start = System.nanoTime()
            var sent = 0
            var reconnects = 0
            var nextReport = start + TimeUnit.SECONDS.toNanos(1)
            while (sent < count) {
                if (client.disconnected || client.session?.isConnected == false) {
                    reconnect(client, logon)
                    reconnects++
                }
                val now = System.nanoTime()
                if (now - start >= sent * intervalNs) {
                    if (sendOne(client, sent)) sent++
                }
                if (now >= nextReport) {
                    println("load: ${(now - start) / 1_000_000_000}s sent $sent, answered ${orders.values.count { it.reports > 0 }}, trades $trades")
                    nextReport += TimeUnit.SECONDS.toNanos(1)
                }
                if (client.poll() == 0) Thread.onSpinWait()
            }
            val elapsed = (System.nanoTime() - start) / 1e9
            println("load: sent ${orders.size} orders and ${cancels.size} cancels in ${"%.1f".format(elapsed)} s")
            drain(client, logon)
            summary(reconnects)
        }
        return 0
    }

    /** Sends the next request; false if Artio refused it for now, so the schedule slips. */
    private fun sendOne(client: FixClient, i: Int): Boolean {
        if (cancelPct > 0 && random.nextInt(100) < cancelPct) {
            val target = orders.values.firstOrNull { it.open && !it.cancelSent }
            if (target != null) {
                val id = "${prefix}C$i"
                if (!client.trySend(cancel(id, target.clOrdId, symbol, target.side, target.qty))) return false
                target.cancelSent = true
                cancels[id] = null
                return true
            }
        }
        val side = if (random.nextBoolean()) '1' else '2'
        val ticks = priceMax.subtract(priceMin).movePointRight(2).toInt()
        val price = priceMin.add(BigDecimal(random.nextInt(ticks + 1)).movePointLeft(2)).setScale(2, RoundingMode.UNNECESSARY)
        val qty = BigDecimal(qtyMin + random.nextInt(qtyMax - qtyMin + 1))
        val id = "$prefix$i"
        if (!client.trySend(newOrder(id, symbol, side, price, qty))) return false
        orders[id] = Order(id, side, qty)
        return true
    }

    private fun reconnect(client: FixClient, logon: Logon) {
        check(reconnectMs > 0) { "the session dropped after ${orders.size} orders, and --reconnect is not set" }
        println("load: the session dropped; logging on again for up to ${reconnectMs / 1000} s")
        // Artio refuses a new initiate while the old session is still the library's: wait for its disconnect.
        runCatching { client.pollUntil("the old session's disconnect", 10_000L) { client.disconnected } }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(reconnectMs)
        while (true) {
            try {
                client.logon(logon)
                println("load: logged on again")
                return
            } catch (e: IllegalStateException) {
                check(System.nanoTime() < deadline) { "could not log on again within ${reconnectMs / 1000} s: ${e.message}" }
                client.pollFor(1_000)
            }
        }
    }

    /** Until every order has an answer that is not pending and not unknown, or [drainMs] passes. */
    private fun drain(client: FixClient, logon: Logon) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(drainMs)
        fun settled() = orders.values.all { it.reports > 0 && !it.unknown && it.ordStatus != "A" && it.ordStatus != "6" } &&
            cancels.values.all { it != null }
        while (!settled() && System.nanoTime() < deadline) {
            if ((client.disconnected || client.session?.isConnected == false) && reconnectMs > 0) reconnect(client, logon)
            if (client.poll() == 0) Thread.onSpinWait()
        }
    }

    private fun summary(reconnects: Int) {
        val unanswered = orders.values.filter { it.reports == 0 }
        val unknown = orders.values.filter { it.unknown }
        val byStatus = orders.values.filter { it.reports > 0 && !it.unknown }
            .groupingBy { Describe.ORD_STATUSES[it.ordStatus] ?: "${it.ordStatus}" }.eachCount().toSortedMap()
        val rejectTexts = orders.values.filter { it.ordStatus == "8" }.groupingBy { it.text ?: "-" }.eachCount()
        println("load: orders ${orders.size}: answered ${orders.size - unanswered.size}, unanswered ${unanswered.size}, unknown ${unknown.size}")
        println("load: last status $byStatus")
        if (rejectTexts.isNotEmpty()) println("load: rejected $rejectTexts")
        println(
            "load: trades $trades; cancels ${cancels.size}: ${cancels.values.groupingBy { it ?: "UNANSWERED" }.eachCount().toSortedMap()}; " +
                "35=3 $sessionRejects, 35=j $businessRejects, PossDup reports $possDups, reconnects $reconnects",
        )
        if (unanswered.isNotEmpty()) println("load: unanswered ${unanswered.take(10).joinToString { it.clOrdId }}${if (unanswered.size > 10) " ..." else ""}")
        if (unknown.isNotEmpty()) println("load: unknown ${unknown.take(10).joinToString { it.clOrdId }}${if (unknown.size > 10) " ..." else ""}")
    }
}
