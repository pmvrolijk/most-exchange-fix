package nl.lamia.most.exchange.fixadapter.tools

import nl.lamia.most.exchange.fixadapter.codecs.builder.MarketDataRequestEncoder
import nl.lamia.most.exchange.fixadapter.codecs.builder.NewOrderSingleEncoder
import nl.lamia.most.exchange.fixadapter.codecs.builder.SecurityListRequestEncoder
import nl.lamia.most.exchange.fixadapter.codecs.builder.OrderCancelRequestEncoder
import nl.lamia.most.exchange.fixadapter.codecs.builder.OrderMassStatusRequestEncoder
import nl.lamia.most.exchange.fixadapter.codecs.builder.OrderStatusRequestEncoder
import uk.co.real_logic.artio.fields.UtcTimestampEncoder
import java.io.File
import java.math.BigDecimal
import kotlin.system.exitProcess

private const val USAGE = """usage: fix <command> [options]

Connection, every command:
  --port N                The adapter's FIX port (required)
  --host HOST             Default localhost
  --sender ID             The client's CompID (required)
  --target ID             The adapter's CompID, default MOST
  --user NAME             553 (required)
  --password SECRET | --password-file FILE   554 (one required)
  --state DIR             The client's own state: MsgSeqNum persists here between runs (required)
  --reset                 Log on with 141=Y

Commands:
  send         --symbol S --side buy|sell --price P --qty Q --clordid ID [--tif day|gtc|ioc|fok] [--follow SECONDS]
  cancel       --clordid ID --orig ID --symbol S --side buy|sell --qty Q [--follow SECONDS]
  status       --clordid ID --symbol S --side buy|sell [--id ID] [--follow SECONDS]          35=H
  mass-status  [--side buy|sell] [--id ID] [--follow SECONDS]                                 35=AF, type 7
  listen       [--follow SECONDS]          Log on and print what arrives: reports owed while away, say
  load         --symbol S --count N --rate PER_SECOND --price-min P --price-max P [--qty-min Q] [--qty-max Q]
               [--cancel-pct N] [--prefix ID] [--drain SECONDS] [--reconnect SECONDS] [--settle SECONDS] [--verbose]

Market data, as a market-data session (it starts at MsgSeqNum 1 at every Logon, so these log on with 141=Y):
  securities   [--id ID] [--follow SECONDS]                                                    35=x, all securities
  md           --symbol S[,S...] [--depth 0|1] [--entries bid,offer,trade] [--snapshot] [--id ID] [--follow SECONDS]
               35=V: snapshot plus updates, unless --snapshot

Every application message received is printed on one line (ExecutionReport ... ExecType=TRADE ...).
--follow defaults to 3 seconds."""

fun main(argv: Array<String>) {
    if (argv.isEmpty() || argv[0] in setOf("-h", "--help", "help")) {
        println(USAGE)
        exitProcess(if (argv.isEmpty()) 2 else 0)
    }
    val command = argv[0]
    val args = try {
        Args.parse(argv.drop(1), flags = setOf("reset", "verbose", "snapshot"))
    } catch (e: IllegalArgumentException) {
        System.err.println("fix: ${e.message}")
        exitProcess(2)
    }
    val code = try {
        run(command, args)
    } catch (e: IllegalArgumentException) {
        System.err.println("fix: ${e.message}")
        2
    } catch (e: IllegalStateException) {
        System.err.println("fix: ${e.message}")
        1
    }
    exitProcess(code)
}

private fun run(command: String, args: Args): Int {
    val logon = Logon(
        host = args.string("host", "localhost"),
        port = args.int("port"),
        senderCompId = args.required("sender"),
        targetCompId = args.string("target", "MOST"),
        username = args.required("user"),
        password = args.optional("password") ?: args.optional("password-file")?.let { File(it).readText().trim() }
            ?: throw IllegalArgumentException("--password or --password-file is required"),
        reset = args.flag("reset") || command in setOf("securities", "md"),
    )
    val state = File(args.required("state"))
    val followMs = (args.double("follow", 3.0) * 1000).toLong()
    if (command == "load") return Load(args).run(state, logon)

    val encoder = when (command) {
        "send" -> newOrder(args)
        "cancel" -> cancel(args)
        "status" -> OrderStatusRequestEncoder().apply {
            clOrdID(args.required("clordid"))
            instrument().symbol(args.required("symbol"))
            side(side(args.required("side")))
            args.optional("id")?.let { ordStatusReqID(it) }
        }
        "mass-status" -> OrderMassStatusRequestEncoder().apply {
            massStatusReqID(args.string("id", "MS-${System.currentTimeMillis()}"))
            massStatusReqType(7)
            args.optional("side")?.let { side(side(it)) }
        }
        "listen" -> null
        "securities" -> SecurityListRequestEncoder().apply {
            securityReqID(args.string("id", "SL-${System.currentTimeMillis()}"))
            securityListRequestType(4)
        }
        "md" -> marketDataRequest(args)
        else -> throw IllegalArgumentException("unknown command: $command\n$USAGE")
    }
    args.rejectUnused()
    FixClient(state) { m -> if (!Describe.isSessionLevel(m)) println(Describe.line(m)) }.use { client ->
        client.logon(logon)
        println("logged on as ${logon.senderCompId}")
        encoder?.let(client::send)
        client.pollFor(followMs)
    }
    return 0
}

private val timestamp = UtcTimestampEncoder()

private fun marketDataRequest(args: Args) = MarketDataRequestEncoder().apply {
    val snapshotOnly = args.flag("snapshot")
    mDReqID(args.string("id", "MD-${System.currentTimeMillis()}"))
    subscriptionRequestType(if (snapshotOnly) '0' else '1')
    marketDepth(args.int("depth", 0))
    if (!snapshotOnly) mDUpdateType(1)
    val types = args.string("entries", "bid,offer,trade").split(',').map {
        when (it.trim().lowercase()) {
            "bid" -> '0'
            "offer" -> '1'
            "trade" -> '2'
            else -> throw IllegalArgumentException("--entries takes bid, offer and trade, not $it")
        }
    }
    var entry = mDReqGrp().mDEntryTypesGroup(types.size)
    types.forEachIndexed { i, t -> entry.mDEntryType(t); if (i < types.size - 1) entry = entry.next() }
    val symbols = args.required("symbol").split(',').map { it.trim() }
    var instrument = instrmtMDReqGrp().relatedSymGroup(symbols.size)
    symbols.forEachIndexed { i, sym -> instrument.instrument().symbol(sym); if (i < symbols.size - 1) instrument = instrument.next() }
}

internal fun side(name: String): Char = when (name.lowercase()) {
    "buy" -> '1'
    "sell" -> '2'
    else -> throw IllegalArgumentException("--side must be buy or sell, not $name")
}

private fun tif(name: String): Char = when (name.lowercase()) {
    "day" -> '0'
    "gtc" -> '1'
    "ioc" -> '3'
    "fok" -> '4'
    else -> throw IllegalArgumentException("--tif must be day, gtc, ioc or fok, not $name")
}

internal fun newOrder(args: Args): NewOrderSingleEncoder = newOrder(
    args.required("clordid"), args.required("symbol"), side(args.required("side")),
    args.decimal("price"), args.decimal("qty"),
).apply { args.optional("tif")?.let { timeInForce(tif(it)) } }

internal fun newOrder(clOrdId: String, symbol: String, side: Char, price: BigDecimal, qty: BigDecimal) =
    NewOrderSingleEncoder().apply {
        clOrdID(clOrdId)
        side(side)
        transactTime(timestamp.buffer(), timestamp.encode(System.currentTimeMillis()))
        ordType('2')
        instrument().symbol(symbol)
        orderQtyData().orderQty(qty.unscaledValue().toLong(), qty.scale())
        price(price.unscaledValue().toLong(), price.scale())
    }

private fun cancel(args: Args) = cancel(
    args.required("clordid"), args.required("orig"), args.required("symbol"), side(args.required("side")), args.decimal("qty"),
)

internal fun cancel(clOrdId: String, orig: String, symbol: String, side: Char, qty: BigDecimal) =
    OrderCancelRequestEncoder().apply {
        origClOrdID(orig)
        clOrdID(clOrdId)
        instrument().symbol(symbol)
        side(side)
        transactTime(timestamp.buffer(), timestamp.encode(System.currentTimeMillis()))
        orderQtyData().orderQty(qty.unscaledValue().toLong(), qty.scale())
    }

/** `--key value` options and bare `--flag`s; every option must be used, so a typo is refused. */
class Args private constructor(private val values: Map<String, String>, private val flags: Set<String>) {
    private val used = HashSet<String>()

    fun optional(key: String): String? = values[key]?.also { used += key }
    fun required(key: String): String = optional(key) ?: throw IllegalArgumentException("--$key is required")
    fun string(key: String, default: String) = optional(key) ?: default
    fun int(key: String): Int = required(key).let { it.toIntOrNull() ?: throw IllegalArgumentException("--$key must be a number, not $it") }
    fun int(key: String, default: Int): Int = optional(key)?.let { it.toIntOrNull() ?: throw IllegalArgumentException("--$key must be a number, not $it") } ?: default
    fun double(key: String, default: Double): Double = optional(key)?.let { it.toDoubleOrNull() ?: throw IllegalArgumentException("--$key must be a number, not $it") } ?: default
    fun decimal(key: String): BigDecimal = required(key).let { it.toBigDecimalOrNull() ?: throw IllegalArgumentException("--$key must be a decimal, not $it") }
    fun decimal(key: String, default: String): BigDecimal = string(key, default).let { it.toBigDecimalOrNull() ?: throw IllegalArgumentException("--$key must be a decimal, not $it") }
    fun flag(key: String): Boolean = (key in flags).also { used += key }

    fun rejectUnused() {
        val unused = (values.keys + flags) - used
        require(unused.isEmpty()) { "unknown options: ${unused.joinToString { "--$it" }}" }
    }

    companion object {
        fun parse(argv: List<String>, flags: Set<String>): Args {
            val values = LinkedHashMap<String, String>()
            val set = HashSet<String>()
            var i = 0
            while (i < argv.size) {
                val arg = argv[i]
                require(arg.startsWith("--") && arg.length > 2) { "expected an option, not $arg" }
                val key = arg.substring(2)
                if (key in flags) {
                    set += key
                    i++
                } else {
                    require(i + 1 < argv.size) { "$arg needs a value" }
                    values[key] = argv[i + 1]
                    i += 2
                }
            }
            return Args(values, set)
        }
    }
}
