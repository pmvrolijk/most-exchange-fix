package nl.lamia.most.exchange.fixadapter

import nl.lamia.most.exchange.fixadapter.orderentry.OrderEntryBackend
import nl.lamia.most.exchange.fixadapter.orderentry.OrderEntryConfig
import nl.lamia.most.exchange.fixadapter.orderentry.TradingCalendar
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
 * The adapter's process (Design.md §1, §7): the node file in, then the acceptor, the order-entry
 * backend on its own thread, and only then the bind, so no Logon reaches an adapter about to refuse
 * to start (§3.2).
 *
 * `fix.*` system properties override the node file's keys (§2). The market-data backend is not
 * built yet (Status §3): its sessions log on, and nothing serves them.
 */
class Adapter(properties: Properties, private val log: (String) -> Unit = System.err::println) : AutoCloseable {

    val node: FixNodeConfig = FixNodeConfig.parse(properties)
    private val orderEntryConfig = OrderEntryConfig.parse(properties)
    val calendar: TradingCalendar = TradingCalendar.parse(properties)
    private val idle: IdleStrategy = idleStrategy(properties.getProperty(IDLE_STRATEGY))

    val acceptor = FixAcceptor(node, bindAtStartup = false)
    private var backend: OrderEntryBackend? = null
    private var runner: AgentRunner? = null
    val orderEntry: OrderEntryBackend get() = checkNotNull(backend)

    init {
        try {
            val backend = OrderEntryBackend(acceptor, orderEntryConfig, calendar, log = log).also { backend = it }
            runner = AgentRunner(idle, { e -> log("order-entry: ${e.stackTraceToString()}") }, null, backend)
                .also { AgentRunner.startOnThread(it) }
            acceptor.bind()
        } catch (e: Throwable) {
            close()
            throw e
        }
        val roles = acceptor.sessions.all.groupingBy { it.role }.eachCount()
        log(
            "adapter: started, listening on ${node.bindAddress}:${node.port}; " +
                "${roles[Role.ORDER_ENTRY] ?: 0} order-entry sessions, ${roles[Role.MARKET_DATA] ?: 0} market-data (not served yet); " +
                "trading date ${calendar.asInt}, idle ${idle.alias()}",
        )
    }

    /** The backend's thread first, so nothing polls a library while the engine and driver go. */
    override fun close() {
        CloseHelper.quietClose(runner)
        CloseHelper.quietClose(backend)
        CloseHelper.quietClose(acceptor)
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
