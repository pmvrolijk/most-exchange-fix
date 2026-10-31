package nl.lamia.most.exchange.fixadapter

import nl.lamia.most.exchange.fixadapter.marketdata.MarketDataBackend
import nl.lamia.most.exchange.fixadapter.marketdata.MarketDataConfig
import nl.lamia.most.exchange.fixadapter.orderentry.OrderEntryBackend
import nl.lamia.most.exchange.fixadapter.orderentry.OrderEntryConfig
import nl.lamia.most.exchange.fixadapter.orderentry.TradingCalendar
import nl.lamia.most.exchange.fixadapter.orderentry.store.OrderStore
import nl.lamia.most.exchange.fixadapter.session.ConfiguredSessions
import nl.lamia.most.exchange.fixadapter.session.FixAcceptor
import nl.lamia.most.exchange.fixadapter.session.FixNodeConfig
import nl.lamia.most.exchange.fixadapter.session.Role
import org.agrona.CloseHelper
import org.agrona.concurrent.AgentRunner
import org.agrona.concurrent.BackoffIdleStrategy
import org.agrona.concurrent.BusySpinIdleStrategy
import org.agrona.concurrent.IdleStrategy
import org.agrona.concurrent.SleepingMillisIdleStrategy
import org.agrona.concurrent.YieldingIdleStrategy
import java.util.Properties
import kotlin.system.exitProcess

/**
 * The adapter's process (Design.md §1, §7): the node file in, then order entry's acceptor and its
 * backend on its own thread; when a market-data session is configured, market data's own acceptor
 * (its port, its unsynced archive, §4) and its backend on another; and only then the binds, so no
 * Logon reaches an adapter about to refuse to start (§3.2).
 *
 * `fix.*` system properties override the node file's keys (§2).
 */
class Adapter(private val properties: Properties, private val log: (String) -> Unit = System.err::println) : AutoCloseable {

    val node: FixNodeConfig = FixNodeConfig.parse(properties)
    private val orderEntryConfig = OrderEntryConfig.parse(properties)
    val calendar: TradingCalendar = TradingCalendar.parse(properties)
    private val idle: IdleStrategy = idleStrategy(properties.getProperty(IDLE_STRATEGY))

    private val sessions = ConfiguredSessions.load(node.sessionsFile)

    /** Market data's keys, read only when a market-data session is configured, and before anything opens. */
    private val hasMarketData = sessions.all.any { it.role == Role.MARKET_DATA }
    private val marketDataConfig: MarketDataConfig? = if (hasMarketData) MarketDataConfig.parse(properties) else null
    val marketDataNode: FixNodeConfig? = if (hasMarketData) FixNodeConfig.parseMarketData(properties) else null

    /** Order entry's engine: the order-entry sessions only, its archive fsynced (§6.4). */
    val acceptor = FixAcceptor(node, sessions.ofRole(Role.ORDER_ENTRY), bindAtStartup = false)

    /** Market data's own engine (§4): the market-data sessions only, emptied at start. */
    val marketDataAcceptor: FixAcceptor? = try {
        marketDataNode?.let { FixAcceptor(it, sessions.ofRole(Role.MARKET_DATA), bindAtStartup = false, transient = true) }
    } catch (e: Throwable) {
        CloseHelper.quietClose(acceptor)
        throw e
    }

    private var backend: OrderEntryBackend? = null
    private var runner: AgentRunner? = null
    private var marketDataBackend: MarketDataBackend? = null
    private var marketDataRunner: AgentRunner? = null
    val orderEntry: OrderEntryBackend get() = checkNotNull(backend)
    val marketData: MarketDataBackend? get() = marketDataBackend

    init {
        try {
            val backend = OrderEntryBackend(acceptor, orderEntryConfig, calendar, log = log).also { backend = it }
            runner = AgentRunner(idle, { e -> log("order-entry: ${e.stackTraceToString()}") }, null, backend)
                .also { AgentRunner.startOnThread(it) }
            marketDataConfig?.let { config ->
                val md = MarketDataBackend(checkNotNull(marketDataAcceptor), config, log = log).also { marketDataBackend = it }
                // Its own idle strategy: one instance per thread.
                marketDataRunner = AgentRunner(idleStrategy(properties.getProperty(IDLE_STRATEGY)), { e -> log("market-data: ${e.stackTraceToString()}") }, null, md)
                    .also { AgentRunner.startOnThread(it) }
            }
            acceptor.bind()
            marketDataAcceptor?.bind()
        } catch (e: Throwable) {
            close()
            throw e
        }
        log(
            "adapter: started, listening on ${node.bindAddress}:${node.port} for ${acceptor.sessions.all.size} order-entry sessions" +
                (marketDataNode?.let { md -> ", ${md.bindAddress}:${md.port} for ${marketDataAcceptor?.sessions?.all?.size} market-data" } ?: "") +
                "; trading date ${calendar.asInt}, idle ${idle.alias()}",
        )
    }

    /**
     * The backends' threads first, so nothing polls a library while the engine and driver go. Then,
     * if all of it closed, the clean stop is marked (Design.md §8.21): Artio's engine waits at close
     * for its archive to record every message it sent, so nothing reached a client that the next
     * start does not number past.
     */
    override fun close() {
        val started = backend != null
        val closed = runCatching { CloseHelper.closeAll(marketDataRunner, runner, marketDataBackend, backend, marketDataAcceptor, acceptor) }
        closed.exceptionOrNull()?.let { log("adapter: not a clean stop: ${it.stackTraceToString()}") }
        if (started && closed.isSuccess) OrderStore.markStoppedCleanly(orderEntryConfig.storeDir)
    }

    companion object {
        const val IDLE_STRATEGY = "fix.idleStrategy"

        /** The library thread's idle strategy (Design.md §7): busy-spin unless the node file says otherwise. */
        fun idleStrategy(name: String?): IdleStrategy = when (name?.trim()?.lowercase()) {
            null, "", "busyspin" -> BusySpinIdleStrategy()
            "yielding" -> YieldingIdleStrategy()
            "backoff" -> BackoffIdleStrategy()
            "sleeping" -> SleepingMillisIdleStrategy(1)
            else -> throw IllegalArgumentException("$IDLE_STRATEGY must be busyspin, yielding, backoff or sleeping, not $name")
        }
    }
}

fun main(args: Array<String>) {
    if (args.size > 1) {
        System.err.println("usage: adapter [node file]")
        exitProcess(2)
    }
    val adapter = try {
        Adapter(FixNodeConfig.loadProperties(args.getOrNull(0)))
    } catch (e: Throwable) {
        System.err.println("adapter: refused to start: ${e.message ?: e}")
        e.printStackTrace()
        exitProcess(1)
    }
    Runtime.getRuntime().addShutdownHook(Thread({
        adapter.close()
        System.err.println("adapter: stopped")
    }, "adapter-shutdown"))
}
