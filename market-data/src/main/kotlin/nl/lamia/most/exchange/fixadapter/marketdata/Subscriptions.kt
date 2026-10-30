package nl.lamia.most.exchange.fixadapter.marketdata

import io.aeron.logbuffer.ControlledFragmentHandler.Action
import nl.lamia.most.exchange.client.AggregatedBook
import nl.lamia.most.exchange.client.DepthFeedAssembler
import nl.lamia.most.exchange.client.DepthFeedListener
import nl.lamia.most.exchange.client.DirectoryClient
import nl.lamia.most.exchange.client.RoutedSecurity
import nl.lamia.most.exchange.fixadapter.codecs.builder.MarketDataIncrementalRefreshEncoder
import nl.lamia.most.exchange.fixadapter.codecs.builder.MarketDataRequestRejectEncoder
import nl.lamia.most.exchange.fixadapter.codecs.builder.MarketDataSnapshotFullRefreshEncoder
import nl.lamia.most.exchange.fixadapter.codecs.builder.MDIncGrpEncoder
import nl.lamia.most.exchange.fixadapter.codecs.decoder.MarketDataRequestDecoder
import org.agrona.AsciiSequenceView
import org.agrona.collections.Int2ObjectHashMap
import org.agrona.collections.Long2LongHashMap

/**
 * Market data subscriptions (Design.md §4, "Subscriptions"): 35=V in, 35=W and 35=X out, from the
 * SDK's [assembler], whose listener this is.
 *
 * A 35=V creates a subscription per instrument, owing a 35=W, or is answered with a 35=Y. Every 35=W
 * goes from [doWork], once the book is trusted: the first one, the one after the feed resynchronised a
 * book, and the one after Artio refused a 35=X to a client that fell behind. A subscription owing a
 * 35=W gets no 35=X. 35=X goes from the listener, as each change is applied.
 *
 * Single-threaded, the market-data library's. The SDK's book allocates as it applies an update, and
 * reading its levels allocates too; the 35=V path allocates freely (rare).
 */
class Subscriptions(private val directory: DirectoryClient) : DepthFeedListener {

    /** The SDK's book builder, telling this what it applied. Feed it with the SDK's `DepthFeedDecoder`. */
    val assembler = DepthFeedAssembler(listener = this)

    private class Subscription(
        val sessionId: Long,
        val mdReqId: String,
        val security: View,
        val topOnly: Boolean,
        val bids: Boolean,
        val offers: Boolean,
        val trades: Boolean,
        val snapshotOnly: Boolean,
        val out: FixOut,
    ) {
        var owesSnapshot = true

        /**
         * Live when its book stopped being trusted (§4). No 35=X goes meanwhile, because the book is
         * null; at resynchronisation it owes a fresh 35=W unless the book is as its clients hold it.
         */
        var suspended = false
        fun wants(isBid: Boolean) = if (isBid) bids else offers
        val live: Boolean get() = !owesSnapshot
    }

    /**
     * One security's subscriptions, and the book as its live clients hold it: every level's aggregate
     * and orders, the best of each side, and the last trade.
     */
    private class View(val security: RoutedSecurity) {
        val subscriptions = ArrayList<Subscription>()
        val securityIdText = security.securityId.toString()

        /** Whether the held book is what the live clients have been told. */
        var current = false
        val bidQty = Long2LongHashMap(NONE)
        val askQty = Long2LongHashMap(NONE)
        val bidOrders = Long2LongHashMap(NONE)
        val askOrders = Long2LongHashMap(NONE)
        val bidTop = Top()
        val askTop = Top()
        var tradePrice = DepthFeedAssembler.NO_PRICE
        var tradeQty = 0L

        fun qty(isBid: Boolean) = if (isBid) bidQty else askQty
        fun orders(isBid: Boolean) = if (isBid) bidOrders else askOrders
        fun top(isBid: Boolean) = if (isBid) bidTop else askTop

        fun hold(isBid: Boolean, price: Long, qty: Long, orderCount: Int) {
            if (qty <= 0L) {
                qty(isBid).remove(price)
                orders(isBid).remove(price)
            } else {
                qty(isBid).put(price, qty)
                orders(isBid).put(price, orderCount.toLong())
            }
        }

        /** Whether [book] is exactly what the live clients hold. Allocates: at a resynchronisation only. */
        fun matches(book: AggregatedBook): Boolean {
            if ((book.lastTradePrice ?: DepthFeedAssembler.NO_PRICE) != tradePrice || (book.lastTradePrice != null && book.lastTradeQty != tradeQty)) return false
            for (isBid in SIDES) {
                val levels = book.levels(isBid, Int.MAX_VALUE)
                if (levels.size != qty(isBid).size) return false
                for (level in levels) {
                    if (qty(isBid).get(level.price) != level.qty || orders(isBid).get(level.price) != level.orders.toLong()) return false
                }
            }
            return true
        }
    }

    private class Top {
        var present = false
        var price = 0L
        var qty = 0L
        var orders = 0

        fun set(level: AggregatedBook.Level?) {
            present = level != null
            if (level != null) {
                price = level.price
                qty = level.qty
                orders = level.orders
            }
        }

        fun sameAs(level: AggregatedBook.Level?) =
            if (level == null) !present else present && price == level.price && qty == level.qty && orders == level.orders
    }

    private val views = Int2ObjectHashMap<View>()
    private val all = ArrayList<Subscription>()

    private val snapshot = MarketDataSnapshotFullRefreshEncoder()
    private val incremental = MarketDataIncrementalRefreshEncoder()
    private val reject = MarketDataRequestRejectEncoder()
    private val mdReqId = AsciiSequenceView()

    // ------------------------------------------------------------------ 35=V

    /** A 35=V from [sessionId]'s client, answered on [out]. The request has passed `validate()`. */
    fun onRequest(sessionId: Long, request: MarketDataRequestDecoder, out: FixOut): Action {
        val id = request.mDReqIDAsString()
        if (request.subscriptionRequestType() == UNSUBSCRIBE) {
            remove { it.sessionId == sessionId && it.mdReqId == id }
            return Action.CONTINUE
        }
        val snapshotOnly = request.subscriptionRequestType() == SNAPSHOT
        var bids = false
        var offers = false
        var trades = false
        var otherEntry = false
        for (entry in request.mDEntryTypesGroupIterator()) {
            when (entry.mDEntryType()) {
                BID -> bids = true
                OFFER -> offers = true
                TRADE -> trades = true
                else -> otherEntry = true
            }
        }
        val refusal = when {
            request.hasAggregatedBook() && !request.aggregatedBook() -> UNSUPPORTED_AGGREGATED_BOOK to "only aggregated books are served"
            request.marketDepth() != 0 && request.marketDepth() != 1 -> UNSUPPORTED_MARKET_DEPTH to "MarketDepth 0 or 1 only"
            !snapshotOnly && (!request.hasMDUpdateType() || request.mDUpdateType() != INCREMENTAL) ->
                UNSUPPORTED_MD_UPDATE_TYPE to "MDUpdateType 1, incremental, only"
            otherEntry -> UNSUPPORTED_MD_ENTRY_TYPE to "MDEntryType 0, 1 or 2 only"
            request.hasNoTradingSessionsGroupCounter() && request.noTradingSessionsGroupCounter() > 0 ->
                UNSUPPORTED_TRADING_SESSION_ID to "no TradingSessionID is served"
            request.hasScope() -> UNSUPPORTED_SCOPE to "no Scope is served"
            request.hasOpenCloseSettlFlag() -> UNSUPPORTED_OPEN_CLOSE_SETTLE_FLAG to "no OpenCloseSettlFlag is served"
            request.hasMDImplicitDelete() && request.mDImplicitDelete() -> UNSUPPORTED_MD_IMPLICIT_DELETE to "MDImplicitDelete N only"
            all.any { it.sessionId == sessionId && it.mdReqId == id } -> DUPLICATE_MD_REQ_ID to "MDReqID $id is already subscribed"
            else -> null
        }
        if (refusal != null) return reject(request, refusal.first, refusal.second, out)

        val securities = ArrayList<RoutedSecurity>()
        for (instrument in request.relatedSymGroupIterator()) {
            val security = when {
                instrument.hasSecurityID() -> when (if (instrument.hasSecurityIDSource()) instrument.securityIDSourceAsString() else "") {
                    "8" -> instrument.securityIDAsString().toIntOrNull()?.let { directory.routeFor(it) }
                    "4" -> instrument.securityIDAsString().let { isin -> directory.securities.firstOrNull { it.isin == isin } }
                    else -> null
                }
                instrument.hasSymbol() -> directory.routeForSymbol(instrument.symbolAsString())
                else -> null
            } ?: return reject(request, UNKNOWN_SYMBOL, "unknown security", out)
            securities += security
        }
        val topOnly = request.marketDepth() == 1
        for (security in securities) {
            val view = views.computeIfAbsent(security.securityId) { View(security) }
            val s = Subscription(sessionId, id, view, topOnly, bids, offers, trades, snapshotOnly, out)
            view.subscriptions += s
            all += s
        }
        return Action.CONTINUE
    }

    private fun reject(request: MarketDataRequestDecoder, reason: Char, text: String, out: FixOut): Action {
        val y = reject
        y.reset()
        y.mDReqID(request.mDReqID(mdReqId)).mDReqRejReason(reason).text(text)
        return if (out.send(y)) Action.CONTINUE else Action.ABORT
    }

    /** The session ended: its subscriptions with it (§4). */
    fun onDisconnect(sessionId: Long) = remove { it.sessionId == sessionId }

    private inline fun remove(which: (Subscription) -> Boolean) {
        val each = all.iterator()
        while (each.hasNext()) {
            val s = each.next()
            if (!which(s)) continue
            each.remove()
            s.security.subscriptions.remove(s)
        }
    }

    // ------------------------------------------------------------------ 35=W

    /** Sends the 35=Ws owed, for books that are trusted. Returns how many went. */
    fun doWork(): Int {
        var sent = 0
        var i = 0
        while (i < all.size) {
            val s = all[i]
            if (s.owesSnapshot) {
                val book = assembler.book(s.security.security.securityId)
                if (book != null && sendSnapshot(s, book)) {
                    sent++
                    s.owesSnapshot = false
                    if (s.snapshotOnly) {
                        all.removeAt(i)
                        s.security.subscriptions.remove(s)
                        continue
                    }
                }
            }
            i++
        }
        return sent
    }

    private fun sendSnapshot(s: Subscription, book: AggregatedBook): Boolean {
        refresh(s.security, book)
        val limit = if (s.topOnly) 1 else Int.MAX_VALUE
        val bidLevels = if (s.bids) book.levels(isBid = true, limit) else emptyList()
        val askLevels = if (s.offers) book.levels(isBid = false, limit) else emptyList()
        val tradePrice = book.lastTradePrice
        val withTrade = s.trades && tradePrice != null
        val count = bidLevels.size + askLevels.size + if (withTrade) 1 else 0

        val w = snapshot
        w.reset()
        w.mDReqID(s.mdReqId)
        w.instrument().symbol(s.security.security.symbol).securityID(s.security.securityIdText).securityIDSource(SECURITY_ID_SOURCE)
        val group = w.mDFullGrp()
        if (count == 0) {
            group.noMDEntriesGroupCounter(0)
        } else {
            var entry = group.mDEntriesGroup(count)
            var written = 0
            for ((side, levels) in listOf(BID to bidLevels, OFFER to askLevels)) {
                for (level in levels) {
                    entry.mDEntryType(side).mDEntryPx(level.price, SCALE).mDEntrySize(level.qty, SCALE).numberOfOrders(level.orders)
                    if (++written < count) entry = entry.next()
                }
            }
            if (withTrade) entry.mDEntryType(TRADE).mDEntryPx(tradePrice, SCALE).mDEntrySize(book.lastTradeQty, SCALE)
        }
        return s.out.send(w)
    }

    /** Makes [view]'s held levels and tops the trusted book's, if they are not already. */
    private fun refresh(view: View, book: AggregatedBook) {
        if (view.current) return
        for (isBid in SIDES) {
            view.qty(isBid).clear()
            view.orders(isBid).clear()
            val levels = book.levels(isBid, Int.MAX_VALUE)
            for (level in levels) view.hold(isBid, level.price, level.qty, level.orders)
            view.top(isBid).set(levels.firstOrNull())
        }
        view.tradePrice = book.lastTradePrice ?: DepthFeedAssembler.NO_PRICE
        view.tradeQty = book.lastTradeQty
        view.current = true
    }

    // ------------------------------------------------------------------ the listener: 35=X

    override fun onDepthChanged(securityId: Int, isBid: Boolean, price: Long, aggregateQty: Long, orderCount: Int) {
        val view = views[securityId] ?: return
        val book = assembler.book(securityId) ?: return
        if (!view.current) {
            refresh(view, book) // the book already holds this change: nothing to tell from a held state we did not have
            return
        }
        val held = view.qty(isBid).containsKey(price)
        val action = when {
            aggregateQty <= 0L -> DELETE
            held -> CHANGE
            else -> NEW
        }
        view.hold(isBid, price, aggregateQty, orderCount)

        val top = view.top(isBid)
        val newTop = book.levels(isBid, 1).firstOrNull()
        val topChanged = !top.sameAs(newTop)
        val oldPresent = top.present
        val oldPrice = top.price
        val oldQty = top.qty
        val oldOrders = top.orders
        top.set(newTop)

        val subscriptions = view.subscriptions
        for (i in 0 until subscriptions.size) {
            val s = subscriptions[i]
            if (!s.live || !s.wants(isBid)) continue
            val x = startIncremental(s)
            if (!s.topOnly) {
                level(x.mDIncGrp().mDEntriesGroup(1), view, action, isBid, price, aggregateQty, orderCount)
            } else {
                if (!topChanged) continue
                val group = x.mDIncGrp()
                when {
                    newTop == null -> level(group.mDEntriesGroup(1), view, DELETE, isBid, oldPrice, 0, 0)
                    oldPresent && oldPrice == newTop.price -> level(group.mDEntriesGroup(1), view, CHANGE, isBid, newTop.price, newTop.qty, newTop.orders)
                    oldPresent -> level(level(group.mDEntriesGroup(2), view, DELETE, isBid, oldPrice, oldQty, oldOrders).next(), view, NEW, isBid, newTop.price, newTop.qty, newTop.orders)
                    else -> level(group.mDEntriesGroup(1), view, NEW, isBid, newTop.price, newTop.qty, newTop.orders)
                }
            }
            send(s, x)
        }
    }

    override fun onTrade(securityId: Int, price: Long, qty: Long) {
        val view = views[securityId] ?: return
        // Told only on a trusted book: otherwise the next 35=W, or the comparison at resynchronisation, carries it.
        if (assembler.book(securityId) == null || !view.current) return
        view.tradePrice = price
        view.tradeQty = qty
        val subscriptions = view.subscriptions
        for (i in 0 until subscriptions.size) {
            val s = subscriptions[i]
            if (!s.live || !s.trades) continue
            val x = startIncremental(s)
            x.mDIncGrp().mDEntriesGroup(1).mDUpdateAction(NEW).mDEntryType(TRADE).mDEntryPx(price, SCALE).mDEntrySize(qty, SCALE)
                .instrument().symbol(view.security.symbol).securityID(view.securityIdText).securityIDSource(SECURITY_ID_SOURCE)
            send(s, x)
        }
    }

    /** The book stopped being trusted: its live subscriptions are suspended, no 35=X until it is again. */
    override fun onDesynchronised(securityId: Int) {
        val view = views[securityId] ?: return
        for (i in 0 until view.subscriptions.size) view.subscriptions[i].suspended = true
    }

    /**
     * The book is trusted again. Exactly as its live clients hold it (a quiet book's repeated image),
     * the 35=X resume; otherwise each suspended subscription owes a fresh 35=W (§4).
     */
    override fun onSynchronised(securityId: Int) {
        val view = views[securityId] ?: return
        val book = assembler.book(securityId) ?: return
        val unchanged = view.current && view.matches(book)
        if (!unchanged) view.current = false // refreshed from the book as the 35=Ws go
        for (i in 0 until view.subscriptions.size) {
            val s = view.subscriptions[i]
            if (s.suspended && !unchanged) s.owesSnapshot = true
            s.suspended = false
        }
    }

    private fun startIncremental(s: Subscription): MarketDataIncrementalRefreshEncoder {
        val x = incremental
        x.reset()
        x.mDReqID(s.mdReqId)
        return x
    }

    /** One level entry; a delete carries its price and no size. */
    private fun level(
        entry: MDIncGrpEncoder.MDEntriesGroupEncoder, view: View, action: Char, isBid: Boolean, price: Long, qty: Long, orders: Int,
    ): MDIncGrpEncoder.MDEntriesGroupEncoder {
        entry.mDUpdateAction(action).mDEntryType(if (isBid) BID else OFFER).mDEntryPx(price, SCALE)
        if (action != DELETE) entry.mDEntrySize(qty, SCALE).numberOfOrders(orders)
        entry.instrument().symbol(view.security.symbol).securityID(view.securityIdText).securityIDSource(SECURITY_ID_SOURCE)
        return entry
    }

    /** A 35=X Artio refuses: the client fell behind, and gets a fresh 35=W once it can (§4). */
    private fun send(s: Subscription, x: MarketDataIncrementalRefreshEncoder) {
        if (!s.out.send(x)) s.owesSnapshot = true
    }

    private companion object {
        val SIDES = booleanArrayOf(true, false)

        /** A price the held book has no level at. */
        const val NONE = Long.MIN_VALUE

        // SubscriptionRequestType (263).
        const val SNAPSHOT = '0'
        const val UNSUBSCRIBE = '2'

        /** MDUpdateType (265) 1. */
        const val INCREMENTAL = 1

        // MDEntryType (269).
        const val BID = '0'
        const val OFFER = '1'
        const val TRADE = '2'

        // MDUpdateAction (279).
        const val NEW = '0'
        const val CHANGE = '1'
        const val DELETE = '2'

        // MDReqRejReason (281).
        const val UNKNOWN_SYMBOL = '0'
        const val DUPLICATE_MD_REQ_ID = '1'
        const val UNSUPPORTED_MARKET_DEPTH = '5'
        const val UNSUPPORTED_MD_UPDATE_TYPE = '6'
        const val UNSUPPORTED_AGGREGATED_BOOK = '7'
        const val UNSUPPORTED_MD_ENTRY_TYPE = '8'
        const val UNSUPPORTED_TRADING_SESSION_ID = '9'
        const val UNSUPPORTED_SCOPE = 'A'
        const val UNSUPPORTED_OPEN_CLOSE_SETTLE_FLAG = 'B'
        const val UNSUPPORTED_MD_IMPLICIT_DELETE = 'C'

        const val SECURITY_ID_SOURCE = "8"

        /** The exchange's fixed point: 8 implied decimals. */
        const val SCALE = 8
    }
}
