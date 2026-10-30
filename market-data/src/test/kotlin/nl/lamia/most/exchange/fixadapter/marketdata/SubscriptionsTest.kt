package nl.lamia.most.exchange.fixadapter.marketdata

import io.aeron.logbuffer.ControlledFragmentHandler.Action
import nl.lamia.most.exchange.client.DepthFeedAssembler
import nl.lamia.most.exchange.client.DirectoryClient
import nl.lamia.most.exchange.fixadapter.codecs.decoder.MarketDataRequestDecoder
import nl.lamia.most.exchange.fixadapter.session.DirectoryBroadcast
import nl.lamia.most.exchange.fixadapter.session.TestSecurity
import nl.lamia.most.exchange.fixadapter.session.TestShard
import nl.lamia.most.exchange.fixadapter.session.fixMessage
import uk.co.real_logic.artio.util.MutableAsciiBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Market data subscriptions (Design.md §4, "Subscriptions"), against the SDK's assembler driven message
 * by message, the directory fed one broadcast, and a recorder for each client's session.
 */
class SubscriptionsTest {

    private val directory = DirectoryClient().also {
        DirectoryBroadcast(1, listOf(TestShard(0, "aeron:ipc", 1, "aeron:ipc", 2)), listOf(
            TestSecurity(ACME, 0, "ACME", "NL0000000042"),
            TestSecurity(BETA, 0, "BETA", "NL0000000043"),
        )).feed(it)
    }
    private val subs = Subscriptions(directory)
    private val feed: DepthFeedAssembler get() = subs.assembler
    private val out = Recorder()

    // ------------------------------------------------------------ the feed, as the SDK's decoder hands it over

    private var l2 = 0L
    private var snap = 0L

    /** A complete snapshot cycle of [security] at the current incremental sequence: levels (isBid, price, qty). */
    private fun snapshot(security: Int = ACME, vararg levels: Triple<Boolean, Long, Long>, trade: Pair<Long, Long>? = null) {
        feed.onSnapshotBegin(security, 0, ++snap, l2, trade?.first ?: DepthFeedAssembler.NO_PRICE, trade?.second ?: 0, levels.size)
        for ((isBid, price, qty) in levels) feed.onSnapshotLevel(security, 0, ++snap, isBid, price, qty, 1)
        feed.onSnapshotEnd(security, 0, ++snap, l2, levels.size)
    }

    private fun depth(isBid: Boolean, price: Long, qty: Long, orders: Int = 1, security: Int = ACME) =
        feed.onDepthUpdate(security, 0, ++l2, isBid, price, qty, orders)

    private fun trade(price: Long, qty: Long, security: Int = ACME) = feed.onLastTrade(security, 0, 1, price, qty)

    /** A gap on shard 0's incremental stream: every book on it stops being trusted. On BETA, so ACME sees only the gap. */
    private fun gap() {
        feed.onDepthUpdate(BETA, 0, ++l2, true, px(1), 0, 0) // the tracker needs a sequence to compare with
        l2 += 5
        feed.onDepthUpdate(BETA, 0, ++l2, true, px(1), 0, 0)
    }

    // ------------------------------------------------------------ requests

    private var seq = 2

    private fun request(vararg fields: Pair<Int, String>): MarketDataRequestDecoder {
        val bytes = fixMessage("V", listOf(49 to "FEED1", 56 to "MOST", 34 to "${seq++}", 52 to "20261030-12:00:00.000") + fields.toList())
        return MarketDataRequestDecoder().apply {
            decode(MutableAsciiBuffer(bytes), 0, bytes.size)
            assertTrue(validate(), "the backend validates first: ${rejectReason()} on ${invalidTagId()}")
        }
    }

    /** A 35=V: snapshot plus updates unless [type] says otherwise, every level, bids offers and trades on ACME. */
    private fun subscribe(
        id: String = "MD-1", type: Char = '1', depth: Int = 0, entryTypes: List<String> = listOf("0", "1", "2"),
        instruments: List<List<Pair<Int, String>>> = listOf(listOf(55 to "ACME")), extra: List<Pair<Int, String>> = emptyList(),
        updateType: String? = "1", session: Long = 1L, to: Recorder = out,
    ): Action {
        val fields = listOf(262 to id, 263 to "$type", 264 to "$depth") + (updateType?.let { listOf(265 to it) } ?: emptyList()) + extra +
            listOf(267 to "${entryTypes.size}") + entryTypes.map { 269 to it } +
            listOf(146 to "${instruments.size}") + instruments.flatten()
        return subs.onRequest(session, request(*fields.toTypedArray()), to)
    }

    private fun work() = repeat(3) { subs.doWork() }

    private fun Map<Int, String>.level() = listOf(this[279], this[269], this[270], this[271]).filterNotNull()

    // ============================================================ 35=W

    @Test
    fun `a subscription is answered with a 35=W of the book, bids then offers best first, then the last trade`() {
        snapshot(ACME, Triple(true, px(99), qty(5)), Triple(true, px(100), qty(10)), Triple(false, px(101), qty(7)), Triple(false, px(102), qty(3)), trade = px(100) to qty(2))

        assertEquals(Action.CONTINUE, subscribe())
        work()

        val w = out.ofType("W").single()
        assertEquals(listOf("MD-1", "ACME", "42", "8"), listOf(262, 55, 48, 22).map { w.first(it) })
        assertEquals(
            listOf(
                listOf("0", "100", "10"), listOf("0", "99", "5"),
                listOf("1", "101", "7"), listOf("1", "102", "3"),
                listOf("2", "100", "2"),
            ),
            w.entries().map { listOf(it[269], it[270], it[271]).filterNotNull() },
        )
        assertEquals(listOf("1", "1", "1", "1"), w.entries().take(4).map { it[346] }, "NumberOfOrders on each level")
    }

    @Test
    fun `at MarketDepth 1 the 35=W holds only the best level of each side`() {
        snapshot(ACME, Triple(true, px(99), qty(5)), Triple(true, px(100), qty(10)), Triple(false, px(101), qty(7)), Triple(false, px(102), qty(3)))
        subscribe(depth = 1, entryTypes = listOf("0", "1"))
        work()
        assertEquals(listOf(listOf("0", "100"), listOf("1", "101")), out.ofType("W").single().entries().map { listOf(it[269]!!, it[270]!!) })
    }

    @Test
    fun `only the entry types asked for are sent`() {
        snapshot(ACME, Triple(true, px(100), qty(10)), Triple(false, px(101), qty(7)), trade = px(100) to qty(2))
        subscribe(entryTypes = listOf("1"))
        work()
        depth(isBid = true, price = px(100), qty = qty(12))
        trade(px(101), qty(1))
        depth(isBid = false, price = px(101), qty = qty(9))

        assertEquals(listOf(listOf("1", "101")), out.ofType("W").single().entries().map { listOf(it[269]!!, it[270]!!) })
        assertEquals(listOf(listOf("1", "1", "101", "9")), out.ofType("X").map { it.entries().single().level() })
    }

    @Test
    fun `an empty book is a 35=W with no entries`() {
        snapshot(ACME)
        subscribe()
        work()
        val w = out.ofType("W").single()
        assertEquals("0", w.first(268))
        assertEquals(emptyList(), w.entries())
    }

    @Test
    fun `a request naming two instruments gets a 35=W for each, found by symbol, SecurityID with 22=8, and ISIN`() {
        snapshot(ACME)
        snapshot(BETA, Triple(true, px(50), qty(1)))
        // Symbol is the group's delimiter, so a client naming SecurityID sends one too: [N/A].
        subscribe(id = "A", instruments = listOf(listOf(55 to "ACME"), listOf(55 to "[N/A]", 48 to "43", 22 to "8")))
        subscribe(id = "B", instruments = listOf(listOf(55 to "[N/A]", 48 to "NL0000000043", 22 to "4")))
        work()
        assertEquals(listOf("A" to "ACME", "A" to "BETA", "B" to "BETA"), out.ofType("W").map { it.first(262) to it.first(55) }.sortedBy { "${it.first}${it.second}" })
    }

    @Test
    fun `a snapshot-only request gets its 35=W and nothing after it`() {
        snapshot(ACME, Triple(true, px(100), qty(10)))
        subscribe(type = '0', updateType = null)
        work()
        depth(isBid = true, price = px(100), qty = qty(12))
        work()
        assertEquals(1, out.ofType("W").size)
        assertEquals(emptyList(), out.ofType("X"))
    }

    // ============================================================ 35=X

    @Test
    fun `at MarketDepth 0 a new price is 279=0, a new aggregate at a held price 1, and a level gone 2`() {
        snapshot(ACME, Triple(true, px(100), qty(10)))
        subscribe()
        work()

        depth(isBid = true, price = px(99), qty = qty(4))
        depth(isBid = true, price = px(100), qty = qty(12), orders = 3)
        depth(isBid = true, price = px(99), qty = 0)

        val x = out.ofType("X")
        assertEquals(listOf(listOf("0", "0", "99", "4"), listOf("1", "0", "100", "12"), listOf("2", "0", "99")), x.map { it.entries().single().level() })
        assertEquals("3", x[1].entries().single()[346])
        assertTrue(x.all { it.first(262) == "MD-1" && it.entries().single()[55] == "ACME" }, "MDReqID, and the instrument on each entry")
    }

    @Test
    fun `at MarketDepth 1 a new best is a delete and a new, the same best with a new size a change, and a change below the best nothing`() {
        snapshot(ACME, Triple(true, px(100), qty(10)), Triple(true, px(99), qty(5)))
        subscribe(depth = 1, entryTypes = listOf("0", "1"))
        work()

        depth(isBid = true, price = px(99), qty = qty(6))          // below the best
        depth(isBid = true, price = px(100), qty = qty(11))        // the best, a new size
        depth(isBid = true, price = px(101), qty = qty(2))         // a new best
        depth(isBid = true, price = px(101), qty = 0)              // the best goes: 100 is best again
        depth(isBid = false, price = px(103), qty = qty(1))        // the first offer
        depth(isBid = false, price = px(103), qty = 0)             // the offers empty

        assertEquals(
            listOf(
                listOf(listOf("1", "0", "100", "11")),
                listOf(listOf("2", "0", "100"), listOf("0", "0", "101", "2")),
                listOf(listOf("2", "0", "101"), listOf("0", "0", "100", "11")),
                listOf(listOf("0", "1", "103", "1")),
                listOf(listOf("2", "1", "103")),
            ),
            out.ofType("X").map { x -> x.entries().map { it.level() } },
        )
    }

    @Test
    fun `a trade is a 35=X entry 269=2, 279=0, with its price and size`() {
        snapshot(ACME)
        subscribe()
        work()
        trade(px(100), qty(3))
        assertEquals(listOf("0", "2", "100", "3"), out.ofType("X").single().entries().single().level())
    }

    @Test
    fun `each subscriber gets its own 35=X, under its own MDReqID`() {
        val other = Recorder("FEED2")
        snapshot(ACME)
        subscribe(id = "MINE")
        subscribe(id = "THEIRS", session = 2L, to = other)
        work()
        depth(isBid = true, price = px(100), qty = qty(1))
        assertEquals(listOf("MINE"), out.ofType("X").map { it.first(262) })
        assertEquals(listOf("THEIRS"), other.ofType("X").map { it.first(262) })
    }

    // ============================================================ a book not trusted, a slow client

    @Test
    fun `a subscription to a book not yet trusted gets its 35=W once it is, and no 35=X before`() {
        subscribe()
        work()
        depth(isBid = true, price = px(100), qty = qty(1))         // no image yet: not applied
        work()
        assertEquals(emptyList(), out.sent)

        snapshot(ACME, Triple(true, px(100), qty(10)))
        work()
        assertEquals(listOf(listOf("0", "100", "10")), out.ofType("W").single().entries().map { listOf(it[269]!!, it[270]!!, it[271]!!) })
    }

    @Test
    fun `a book that stops being trusted sends no 35=X, and a fresh 35=W once it is trusted again`() {
        snapshot(ACME, Triple(true, px(100), qty(10)))
        subscribe()
        work()
        gap()
        depth(isBid = true, price = px(100), qty = qty(12))
        trade(px(100), qty(1))                                     // a trade too: the next 35=W carries it
        work()
        assertEquals(1, out.ofType("W").size)
        assertEquals(emptyList(), out.ofType("X"))

        snapshot(ACME, Triple(true, px(100), qty(15)))
        work()
        depth(isBid = true, price = px(100), qty = qty(16))

        assertEquals(2, out.ofType("W").size)
        assertEquals(listOf(listOf("0", "100", "15"), listOf("2", "100", "1")), out.ofType("W").last().entries().map { listOf(it[269]!!, it[270]!!, it[271]!!) }, "the trade missed in the gap, as the last trade")
        assertEquals(listOf(listOf("1", "0", "100", "16")), out.ofType("X").map { it.entries().single().level() })
    }

    @Test
    fun `an image that leaves the book as its clients hold it sends no fresh 35=W, and the 35=X go on`() {
        // The market-data process repeats its snapshot every cycle; on a quiet book each is the same.
        snapshot(ACME, Triple(true, px(100), qty(10)), trade = px(100) to qty(2))
        subscribe()
        work()
        repeat(3) { snapshot(ACME, Triple(true, px(100), qty(10)), trade = px(100) to qty(2)) }
        work()
        depth(isBid = true, price = px(100), qty = qty(11))

        assertEquals(1, out.ofType("W").size)
        assertEquals(listOf(listOf("1", "0", "100", "11")), out.ofType("X").map { it.entries().single().level() })
    }

    @Test
    fun `a trade while the book was not trusted is a change, and the fresh 35=W carries it`() {
        snapshot(ACME, Triple(true, px(100), qty(10)))
        subscribe()
        work()
        gap()
        trade(px(100), qty(4))
        snapshot(ACME, Triple(true, px(100), qty(10)), trade = px(100) to qty(4))
        work()

        assertEquals(emptyList(), out.ofType("X"))
        assertEquals(listOf(listOf("0", "100", "10"), listOf("2", "100", "4")), out.ofType("W").last().entries().map { listOf(it[269]!!, it[270]!!, it[271]!!) })
    }

    @Test
    fun `an image that changes the book while it was not trusted sends a fresh 35=W`() {
        snapshot(ACME, Triple(true, px(100), qty(10)))
        subscribe()
        work()
        // Begun over the trusted book: an update arrives while it is staged, buffered, then applied at its end.
        feed.onSnapshotBegin(ACME, 0, ++snap, l2, DepthFeedAssembler.NO_PRICE, 0, 1)
        feed.onSnapshotLevel(ACME, 0, ++snap, true, px(100), qty(10), 1)
        depth(isBid = true, price = px(99), qty = qty(3))
        feed.onSnapshotEnd(ACME, 0, ++snap, l2 - 1, 1)
        work()

        assertEquals(emptyList(), out.ofType("X"), "the buffered update was never told")
        assertEquals(listOf(listOf("100", "10"), listOf("99", "3")), out.ofType("W").last().entries().map { listOf(it[270]!!, it[271]!!) })
        assertEquals(2, out.ofType("W").size)
    }

    @Test
    fun `a 35=X Artio refuses stops the subscription's 35=X until a fresh 35=W, sent once Artio takes messages again`() {
        snapshot(ACME, Triple(true, px(100), qty(10)))
        subscribe()
        work()
        out.refusing = true
        depth(isBid = true, price = px(100), qty = qty(11))        // refused: missed
        work()
        out.refusing = false
        depth(isBid = true, price = px(100), qty = qty(12))        // not sent: the subscription owes a 35=W
        assertEquals(emptyList(), out.ofType("X"))

        work()
        depth(isBid = true, price = px(100), qty = qty(13))

        assertEquals(listOf("10", "12"), out.ofType("W").map { it.entries().single()[271] })
        assertEquals(listOf("13"), out.ofType("X").map { it.entries().single()[271] })
    }

    @Test
    fun `a 35=W Artio refuses is sent again by the next duty cycle`() {
        snapshot(ACME, Triple(true, px(100), qty(10)))
        out.refuseNext = 1
        subscribe()
        subs.doWork()
        assertEquals(emptyList(), out.sent)
        subs.doWork()
        assertEquals(1, out.ofType("W").size)
    }

    // ============================================================ ending a subscription

    @Test
    fun `263=2 ends the session's subscription with that MDReqID, unanswered, and an unknown one is ignored`() {
        snapshot(ACME, Triple(true, px(100), qty(10)))
        subscribe(id = "MD-1")
        work()
        assertEquals(Action.CONTINUE, subscribe(id = "MD-1", type = '2', updateType = null))
        assertEquals(Action.CONTINUE, subscribe(id = "NOPE", type = '2', updateType = null))
        depth(isBid = true, price = px(100), qty = qty(12))
        work()
        assertEquals(listOf("W"), out.sent.map { it.first(35) })
    }

    @Test
    fun `a session's subscriptions end with it`() {
        snapshot(ACME, Triple(true, px(100), qty(10)))
        subscribe()
        work()
        subs.onDisconnect(1L)
        depth(isBid = true, price = px(100), qty = qty(12))
        work()
        assertEquals(listOf("W"), out.sent.map { it.first(35) })
    }

    // ============================================================ 35=Y

    private fun rejected(vararg request: () -> Action): List<Pair<String?, String?>> {
        out.sent.clear()
        for (r in request) assertEquals(Action.CONTINUE, r())
        return out.ofType("Y").map { it.first(262) to it.first(281) }
    }

    @Test
    fun `a request the adapter does not serve is a 35=Y with FIX 4·4's MDReqRejReason, and creates nothing`() {
        snapshot(ACME)
        assertEquals(listOf("R0" to "0"), rejected({ subscribe(id = "R0", instruments = listOf(listOf(55 to "ACME"), listOf(55 to "NOPE"))) }))
        assertEquals(listOf("R5" to "5"), rejected({ subscribe(id = "R5", depth = 5) }))
        assertEquals(listOf("R6" to "6", "R6b" to "6"), rejected({ subscribe(id = "R6", updateType = "0") }, { subscribe(id = "R6b", updateType = null) }))
        assertEquals(listOf("R7" to "7"), rejected({ subscribe(id = "R7", extra = listOf(266 to "N")) }))
        assertEquals(listOf("R8" to "8"), rejected({ subscribe(id = "R8", entryTypes = listOf("0", "4")) }))
        assertEquals(listOf("R9" to "9"), rejected({ subscribe(id = "R9", extra = listOf(386 to "1", 336 to "DAY")) }))
        assertEquals(listOf("RA" to "A"), rejected({ subscribe(id = "RA", extra = listOf(546 to "1")) }))
        assertEquals(listOf("RB" to "B"), rejected({ subscribe(id = "RB", extra = listOf(286 to "1")) }))
        assertEquals(listOf("RC" to "C"), rejected({ subscribe(id = "RC", extra = listOf(547 to "Y")) }))
        work()
        assertEquals(emptyList(), out.ofType("W"), "no subscription made")
    }

    @Test
    fun `an MDReqID already subscribed on the session is a 35=Y, MDReqRejReason 1, and the first goes on`() {
        snapshot(ACME, Triple(true, px(100), qty(10)))
        subscribe(id = "MD-1")
        work()
        assertEquals(listOf("MD-1" to "1"), rejected({ subscribe(id = "MD-1") }))
        depth(isBid = true, price = px(100), qty = qty(12))
        assertEquals(1, out.ofType("X").size)
    }

    @Test
    fun `a 35=Y carries a Text naming what was refused`() {
        subscribe(id = "R5", depth = 5)
        val y = out.ofType("Y").single()
        assertTrue(!y.first(58).isNullOrBlank(), "Text: $y")
    }

    @Test
    fun `a 35=Y Artio refuses aborts the 35=V`() {
        out.refuseNext = 1
        assertEquals(Action.ABORT, subscribe(id = "R5", depth = 5))
    }

    private companion object {
        const val ACME = 42
        const val BETA = 43

        /** A whole price, fixed point with 8 implied decimals. */
        fun px(whole: Long) = whole * 100_000_000L
        fun qty(whole: Long) = whole * 100_000_000L
    }
}
