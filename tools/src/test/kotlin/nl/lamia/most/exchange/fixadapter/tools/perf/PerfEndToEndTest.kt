package nl.lamia.most.exchange.fixadapter.tools.perf

import io.aeron.logbuffer.ControlledFragmentHandler.Action
import nl.lamia.most.exchange.fixadapter.codecs.builder.ExecutionReportEncoder
import nl.lamia.most.exchange.fixadapter.codecs.builder.OrderCancelRejectEncoder
import io.aeron.archive.ArchiveThreadingMode
import io.aeron.driver.ThreadingMode
import nl.lamia.most.exchange.fixadapter.session.AcceptorRig
import nl.lamia.most.exchange.fixadapter.session.FixAcceptor
import nl.lamia.most.exchange.fixadapter.session.FixMessage
import nl.lamia.most.exchange.fixadapter.session.Role
import nl.lamia.most.exchange.fixadapter.session.RoleClaims
import nl.lamia.most.exchange.fixadapter.session.TestSessions
import nl.lamia.most.exchange.fixadapter.tools.Args
import nl.lamia.most.exchange.fixadapter.tools.Logon
import nl.lamia.most.exchange.fixadapter.tools.report.Analysis
import nl.lamia.most.exchange.fixadapter.tools.report.Outcome
import nl.lamia.most.exchange.fixadapter.tools.report.Run
import nl.lamia.most.exchange.fixadapter.tools.report.Untimed
import org.agrona.CloseHelper
import org.agrona.DirectBuffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import uk.co.real_logic.artio.builder.Encoder
import uk.co.real_logic.artio.library.FixLibrary
import uk.co.real_logic.artio.library.OnMessageInfo
import uk.co.real_logic.artio.library.SessionAcquireHandler
import uk.co.real_logic.artio.library.SessionHandler
import uk.co.real_logic.artio.messages.DisconnectReason
import uk.co.real_logic.artio.session.Session
import java.io.File
import java.math.BigDecimal
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `fix perf` end to end (Design.md §10.1–§10.2): a real Artio initiator, through the adapter's real
 * acceptor, to a venue that answers as FIX 4.4 says a limit order book does: a 35=D is acked, then
 * matched price then time; a 35=F cancels a resting order, or gets a 35=9. The venue is written from
 * FIX, not from the sender.
 */
class PerfEndToEndTest {
    @TempDir
    lateinit var dir: File

    private val stop = AtomicBoolean(false)
    private val ready = CountDownLatch(1)
    private var venueThread: Thread? = null
    private var venueError: Throwable? = null
    private val port = AcceptorRig.freePort()

    @BeforeEach
    fun startVenue() {
        venueThread = Thread({
            try {
                val adapterDir = File(dir, "adapter")
                val acceptor = FixAcceptor(AcceptorRig.nodeConfig(adapterDir, port)) { driver, archive ->
                    driver.threadingMode(ThreadingMode.SHARED).dirDeleteOnShutdown(true)
                    archive.threadingMode(ArchiveThreadingMode.SHARED)
                }
                acceptor.use {
                    Venue(acceptor).use { venue ->
                        ready.countDown()
                        while (!stop.get()) if (venue.poll() == 0) Thread.onSpinWait()
                    }
                }
            } catch (e: Throwable) {
                venueError = e
                ready.countDown()
            }
        }, "venue").apply { start() }
        check(ready.await(30, TimeUnit.SECONDS)) { "the venue did not start" }
        venueError?.let { throw it }
    }

    @AfterEach
    fun stopVenue() {
        stop.set(true)
        venueThread?.join(30_000)
    }

    private fun perf(vararg extra: String): Pair<Perf, Int> {
        val argv = listOf("--symbol", "TEST", "--mid", "100", "--tick", "0.01", "--levels", "5", "--qty", "10",
            "--log", File(dir, "run.log").path, "--settle", "0.5", "--drain", "10",
            "--taker-sender", TestSessions.CLIENT2, "--taker-user", "client2", "--taker-password", TestSessions.SECRET2) + extra
        val perf = Perf(Args.parse(argv, setOf("busy-spin")), argv.joinToString(" "))
        val logon = Logon("localhost", port, TestSessions.CLIENT1, TestSessions.ADAPTER, "client1", TestSessions.SECRET1, reset = true)
        return perf to perf.run(File(dir, "client"), logon)
    }

    @Test
    fun `every request is answered and every report is timed against the request that caused it`() {
        val (perf, code) = perf("--rate", "2000", "--count", "2000", "--mix", "passive=60,cross=20,cancel=20", "--warmup", "200")
        assertEquals(0, code)
        venueError?.let { throw it }
        val a = Analysis(PerfLog.read(File(dir, "run.log")))
        val run = Run(a, null)
        assertEquals(2000, a.sends.size)
        assertEquals(0, run.unanswered)
        assertEquals(mapOf(Untimed.WARMUP to a.untimed.getValue(Untimed.WARMUP)), a.untimed.filterValues { it > 0 })

        // The schedule is fixed: every message's S is T₀ + its offset, whatever happened.
        for (i in 0 until a.sends.size) assertEquals(perf.schedule.offsetNs(i), a.sends.scheduled[i] - a.t0, "S of message $i")

        val measured = a.measured
        val kinds = measured.groupingBy { a.sends.kind[it] }.eachCount()
        val crosses = kinds[Kind.CROSS] ?: 0
        fun count(o: Outcome) = a.samples.getValue(o).size
        // A passive is acked; a cross is acked and filled, and fills the half of the resting order it took.
        assertEquals((kinds[Kind.PASSIVE] ?: 0) + crosses, count(Outcome.NEW_ACK))
        assertEquals(crosses, count(Outcome.FILL))
        assertEquals(0, count(Outcome.PARTIAL_FILL))
        assertEquals(crosses, count(Outcome.RESTING_PARTIAL_FILL) + count(Outcome.RESTING_FILL))
        assertTrue(count(Outcome.RESTING_PARTIAL_FILL) > 0 && count(Outcome.RESTING_FILL) > 0)
        // The sender's book is the venue's: every cancel finds its order.
        assertEquals(kinds[Kind.CANCEL] ?: 0, count(Outcome.CANCEL_ACK))
        assertEquals(0, count(Outcome.CANCEL_REJECT) + count(Outcome.REJECT))
        assertTrue(crosses > 0 && (kinds[Kind.CANCEL] ?: 0) > 0)

        // Every response is its service time plus the request's lateness, exactly.
        for (o in Outcome.entries) {
            val s = a.samples.getValue(o)
            for (k in 0 until s.size) assertEquals(s.service[k] + a.lateness.lateness[s.request[k]], s.response[k])
        }
        // Neither path allocates per message (§10.1). What the thread allocates is a fixed cost, the JIT's and
        // Artio's first use: ~10 KB for 2,000 messages, ~13 KB for 20,000 (2026-10-31). One 16-byte object a
        // message would be 32 KB here.
        assertTrue(perf.senderAllocated in 0 until 16 * 1024, "the send thread allocated ${perf.senderAllocated} bytes")
    }

    /** A FIX 4.4 limit order book, from the specification, with most-exchange's self-match prevention per session. */
    private class Venue(acceptor: FixAcceptor) : AutoCloseable {
        private val claims = RoleClaims(acceptor.sessions, Role.ORDER_ENTRY) { s, reply -> error("claim of ${s.name} failed: ${reply.state()}") }
        private val library: FixLibrary = FixLibrary.connect(acceptor.libraryConfiguration(claims).apply {
            sessionAcquireHandler(SessionAcquireHandler { session, _ -> Handler(session) })
        })
        private val outbox = ArrayDeque<Pair<Session, Encoder>>()

        /** An order and the session it came on: its reports go back there, whoever traded with it. */
        private class Order(val session: Session, val clOrdId: String, val side: Char, val price: BigDecimal, val qty: Long) {
            var leaves = qty
            val cum get() = qty - leaves
        }

        private val bids = ArrayList<Order>() // best first: highest price, then oldest
        private val asks = ArrayList<Order>()
        private var execId = 0

        fun poll(): Int {
            var work = library.poll(10) + claims.poll()
            while (outbox.isNotEmpty()) {
                val (session, encoder) = outbox.peek()
                if (session.trySend(encoder) < 0) break
                outbox.poll()
                work++
            }
            return work
        }

        private fun report(o: Order, execType: Char, ordStatus: Char, clOrdId: String = o.clOrdId, orig: String? = null) {
            // A fresh encoder per reply: they wait in the outbox.
            val r = ExecutionReportEncoder()
            r.orderID(o.clOrdId).execID("E${++execId}").execType(execType).ordStatus(ordStatus).side(o.side)
                .clOrdID(clOrdId).leavesQty(o.leaves, 0).cumQty(o.cum, 0).avgPx(o.price.unscaledValue().toLong(), o.price.scale())
            orig?.let { r.origClOrdID(it) }
            r.instrument().symbol("TEST")
            outbox.add(o.session to r)
        }

        private fun onNewOrder(session: Session, m: FixMessage) {
            val side = m[54]!![0]
            val o = Order(session, m[11]!!, side, BigDecimal(m[44]!!), BigDecimal(m[38]!!).longValueExact())
            report(o, '0', '0')
            val book = if (side == '1') asks else bids
            while (o.leaves > 0 && book.isNotEmpty()) {
                val best = book[0]
                val crosses = if (side == '1') best.price <= o.price else best.price >= o.price
                if (!crosses) break
                if (best.session === o.session) {
                    // Self-match prevention, as most-exchange's default: one participant never trades with
                    // itself, and the aggressor is cancelled.
                    o.leaves = 0
                    report(o, '4', '4')
                    return
                }
                val q = minOf(o.leaves, best.leaves)
                o.leaves -= q
                best.leaves -= q
                report(o, 'F', if (o.leaves == 0L) '2' else '1')
                report(best, 'F', if (best.leaves == 0L) '2' else '1')
                if (best.leaves == 0L) book.removeAt(0)
            }
            if (o.leaves > 0) rest(o)
        }

        private fun rest(o: Order) {
            val book = if (o.side == '1') bids else asks
            val better = { other: Order -> if (o.side == '1') other.price >= o.price else other.price <= o.price }
            var at = 0
            while (at < book.size && better(book[at])) at++
            book.add(at, o)
        }

        private fun onCancel(session: Session, m: FixMessage) {
            val orig = m[41]!!
            val o = bids.firstOrNull { it.clOrdId == orig } ?: asks.firstOrNull { it.clOrdId == orig }
            if (o == null) {
                val r = OrderCancelRejectEncoder()
                r.orderID("NONE").clOrdID(m[11]!!).origClOrdID(orig).ordStatus('8').cxlRejResponseTo('1')
                outbox.add(session to r)
                return
            }
            (if (o.side == '1') bids else asks).remove(o)
            o.leaves = 0
            report(o, '4', '4', clOrdId = m[11]!!, orig = orig)
        }

        private inner class Handler(private val session: Session) : SessionHandler {
            override fun onMessage(
                buffer: DirectBuffer, offset: Int, length: Int, libraryId: Int, session: Session,
                sequenceIndex: Int, messageType: Long, timestampInNs: Long, position: Long, messageInfo: OnMessageInfo,
            ): Action {
                if (!messageInfo.isValid) return Action.CONTINUE
                val m = FixMessage.parse(buffer.getStringWithoutLengthAscii(offset, length))
                when (m.msgType) {
                    "D" -> onNewOrder(this.session, m)
                    "F" -> onCancel(this.session, m)
                }
                return Action.CONTINUE
            }

            override fun onTimeout(libraryId: Int, session: Session) = Unit
            override fun onSlowStatus(libraryId: Int, session: Session, hasBecomeSlow: Boolean) = Unit
            override fun onDisconnect(libraryId: Int, session: Session, reason: DisconnectReason) = Action.CONTINUE
            override fun onSessionStart(session: Session) = Unit
        }

        override fun close() = CloseHelper.close(library)
    }
}
