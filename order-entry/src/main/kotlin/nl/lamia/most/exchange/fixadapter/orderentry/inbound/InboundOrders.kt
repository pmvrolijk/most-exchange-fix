package nl.lamia.most.exchange.fixadapter.orderentry.inbound

import io.aeron.logbuffer.ControlledFragmentHandler.Action
import nl.lamia.most.exchange.client.PriceCodec
import nl.lamia.most.exchange.fixadapter.codecs.Constants
import nl.lamia.most.exchange.fixadapter.codecs.decoder.HeaderDecoder
import nl.lamia.most.exchange.fixadapter.codecs.decoder.InstrumentDecoder
import nl.lamia.most.exchange.fixadapter.codecs.decoder.NewOrderSingleDecoder
import nl.lamia.most.exchange.fixadapter.codecs.decoder.OrderCancelReplaceRequestDecoder
import nl.lamia.most.exchange.fixadapter.codecs.decoder.OrderCancelRequestDecoder
import nl.lamia.most.exchange.fixadapter.codecs.decoder.OrderMassStatusRequestDecoder
import nl.lamia.most.exchange.fixadapter.codecs.decoder.OrderStatusRequestDecoder
import nl.lamia.most.exchange.fixadapter.orderentry.artio.StatusUnknownText
import nl.lamia.most.exchange.fixadapter.orderentry.outbound.Notional
import nl.lamia.most.exchange.fixadapter.orderentry.outbound.OutgoingExecution
import nl.lamia.most.exchange.fixadapter.orderentry.store.CancelState
import nl.lamia.most.exchange.fixadapter.orderentry.store.InsertResult
import nl.lamia.most.exchange.fixadapter.orderentry.store.OrderStore
import nl.lamia.most.exchange.fixadapter.orderentry.store.RequestRow
import nl.lamia.most.exchange.fixadapter.orderentry.store.RowKind
import nl.lamia.most.exchange.sbe.Side
import org.agrona.AsciiSequenceView
import org.agrona.DirectBuffer
import uk.co.real_logic.artio.util.MutableAsciiBuffer
import java.util.function.IntSupplier

/**
 * Order entry's inbound path (Design.md §3.3, §5.1, §5.2): a FIX application message from an
 * order-entry session, to a request on its way to the exchange, or an answer given here.
 *
 * Called on the order-entry library's thread (§3.5), for the session's own [participantId] (§2),
 * with Artio's `sequenceIndex` for the message and its position on the engine's inbound stream. A
 * request is stored and handed to [sends], which sends it once the archive has the message (§6.1,
 * §8.20); the message is then consumed. The returned [Action] is Artio's: [Action.ABORT], for an
 * answer Artio refused, has the message delivered again, and every path is safe to run again
 * (§3.3). A request answered here raises the store's high-water, so a restart does not replay it
 * (§8.17). Allocation-free on the way to the exchange, apart from a row the store's pool does not have.
 *
 * **Recovery** ([onReplayed], §8.17): the archive's messages, replayed at startup past the store's
 * high-water. Nothing is sent and nothing is answered. What the message and the store decide is
 * decided again; a request that passes is rebuilt in flight, its security left unresolved until the
 * directory arrives ([resolveRecovered]).
 */
class InboundOrders(
    private val store: OrderStore,
    private val venue: OrderVenue,
    private val securities: SecurityLookup,
    /** The adapter's trading date, `YYYYMMDD` (§3.6). */
    private val tradingDate: IntSupplier,
    private val sends: ArchivedSends,
    /** An id collision (§3.3, §8.15), after the store has blocked the session: for counting and logging. */
    private val onIdCollision: (participantId: Long) -> Unit,
) {
    private val message = MutableAsciiBuffer()
    private val header = HeaderDecoder()
    private val newOrder = NewOrderSingleDecoder()
    private val cancel = OrderCancelRequestDecoder()
    private val replace = OrderCancelReplaceRequestDecoder()
    private val statusRequest = OrderStatusRequestDecoder()
    private val massStatusRequest = OrderMassStatusRequestDecoder()
    private val status = OutgoingExecution()
    private val notional = Notional()
    private val msgType = AsciiSequenceView()
    private val clOrdId = AsciiSequenceView()
    private val origClOrdId = AsciiSequenceView()
    private val field = AsciiSequenceView()
    private val field2 = AsciiSequenceView()

    var idCollisions = 0L
        private set

    /** Blocked after an id collision until its session is reset (§3.3). The store keeps it. */
    fun isBlocked(participantId: Long): Boolean = store.isBlocked(participantId)

    /** The message in hand's position on the engine's inbound stream. */
    private var position = 0L

    /** The message in hand is the archive's, replayed at startup (§8.17). */
    private var recovering = false

    /** Rows recovery rebuilt, with a copy of their message, until the directory resolves them (§8.17). */
    private val unresolved = ArrayList<Pair<RequestRow, ByteArray>>()

    /** Requests recovery rebuilt in flight, in this process. */
    var rebuilt = 0L
        private set

    /** Rebuilt rows waiting for the directory. */
    val awaitingDirectory: Int get() = unresolved.size

    /**
     * A message the archive replayed at startup to the participant's offline session (§8.17), as
     * [onMessage] takes one, in recovery: never sent, never answered.
     */
    fun onReplayed(participantId: Long, sequenceIndex: Int, messageType: Long, buffer: DirectBuffer, offset: Int, length: Int): Action {
        recovering = true
        try {
            return onMessage(participantId, Unanswered, sequenceIndex, messageType, buffer, offset, length, 0L)
        } finally {
            recovering = false
        }
    }

    /**
     * The directory has arrived (§8.17): each row recovery rebuilt gets its security and shard, before
     * any shard's session is created and its requests are restored in flight. A request that cannot
     * have reached the exchange is handed to [refuse] with its Text. Returns the rows resolved.
     */
    fun resolveRecovered(refuse: (row: RequestRow, text: String) -> Unit): Int {
        check(securities.isReady) { "the directory is not ready" }
        val count = unresolved.size
        for ((row, bytes) in unresolved) {
            message.wrap(bytes)
            when (row.kind) {
                RowKind.ORDER -> resolveOrder(row, bytes.size, refuse)
                RowKind.CANCEL -> resolveCancel(row, bytes.size, refuse)
            }
        }
        unresolved.clear()
        return count
    }

    private fun resolveOrder(row: RequestRow, length: Int, refuse: (RequestRow, String) -> Unit) {
        val d = newOrder
        d.reset()
        d.decode(message, 0, length)
        val securityId = securityOf(d)
        if (securityId == SecurityLookup.NONE) {
            row.securityId = RequestRow.NO_SECURITY
            refuse(row, Texts.UNKNOWN_SECURITY) // stores the row, owing its reject
            return
        }
        row.securityId = securityId
        row.shardId = venue.shardOf(securityId)
        store.update(row)
        if (row.shardId == RequestRow.NO_SHARD) refuse(row, Texts.EXCHANGE_UNAVAILABLE)
    }

    private fun resolveCancel(row: RequestRow, length: Int, refuse: (RequestRow, String) -> Unit) {
        val c = cancel
        c.reset()
        c.decode(message, 0, length)
        val order = store.find(row.participantId, row.origClOrdId) ?: error("cancel ${row.clOrdId} without its order")
        row.securityId = 0
        when {
            order.shardId == RequestRow.NO_SHARD || order.isUnresolved -> {
                row.shardId = RequestRow.NO_SHARD
                refuse(row, order.owedText ?: Texts.EXCHANGE_UNAVAILABLE)
            }
            (c.hasSecurityID() || c.hasSymbol()) && securityOf(c) != order.securityId -> refuse(row, Texts.ORDER_MISMATCH)
            else -> {
                row.shardId = order.shardId
                store.update(row)
            }
        }
    }

    /** A rebuilt row, kept with a copy of its message until the directory arrives. */
    private fun keepUnresolved(row: RequestRow, offset: Int, length: Int) {
        val bytes = ByteArray(length)
        message.getBytes(offset, bytes)
        unresolved += row to bytes
    }

    @Suppress("LongParameterList")
    fun onMessage(
        participantId: Long, replies: ClientReplies, sequenceIndex: Int, messageType: Long,
        buffer: DirectBuffer, offset: Int, length: Int, position: Long,
    ): Action {
        message.wrap(buffer)
        this.position = position
        return when (messageType) {
            Constants.NEW_ORDER_SINGLE_MESSAGE -> onNewOrder(participantId, replies, sequenceIndex, offset, length)
            Constants.ORDER_CANCEL_REQUEST_MESSAGE -> onCancel(participantId, replies, sequenceIndex, offset, length)
            Constants.ORDER_CANCEL_REPLACE_REQUEST_MESSAGE -> onReplace(participantId, replies, offset, length)
            Constants.ORDER_STATUS_REQUEST_MESSAGE -> onStatusRequest(participantId, replies, offset, length)
            Constants.ORDER_MASS_STATUS_REQUEST_MESSAGE -> onMassStatusRequest(participantId, replies, sequenceIndex, offset, length)
            Constants.BUSINESS_MESSAGE_REJECT_MESSAGE -> Action.CONTINUE // never answered: no reject loop
            else -> unsupported(replies, offset, length)
        }
    }

    // ------------------------------------------------------------------ 35=D

    private fun onNewOrder(participantId: Long, replies: ClientReplies, sequenceIndex: Int, offset: Int, length: Int): Action {
        val d = newOrder
        d.reset()
        d.decode(message, offset, length)
        if (!d.validate()) return sessionReject(replies, d.header(), d.invalidTagId(), d.rejectReason())

        val id = derive(sequenceIndex, d.header().msgSeqNum())
        d.clOrdID(clOrdId)
        if (store.isBlocked(participantId)) return refused(participantId, id, replies.rejectOrder(d, OTHER, Texts.SEQUENCE_RESET_REQUIRED))

        val held = store.find(participantId, id)
        if (held != null) {
            if (!held.fixClOrdIdEquals(clOrdId.buffer(), clOrdId.offset(), clOrdId.length)) {
                return collision(participantId, sequenceIndex, replies.rejectOrder(d, OTHER, Texts.SEQUENCE_RESET_REQUIRED), replies)
            }
            // Rebuilt by a recovery that stopped before the directory came: its copy is kept again (§8.17).
            if (recovering && held.isUnresolved) keepUnresolved(held, offset, length)
            // The same request again: already sent, on its way, or the SDK's to settle (§3.3).
            return Action.CONTINUE
        }
        // Handled already with no row: answered here, or dropped (§8.17).
        if (recovering && id <= store.highWater(participantId)) return Action.CONTINUE

        val securityId = if (recovering) {
            RequestRow.UNRESOLVED_SECURITY // the directory is not there yet (§8.17)
        } else {
            if (!securities.isReady) return refused(participantId, id, replies.rejectOrder(d, OTHER, Texts.EXCHANGE_UNAVAILABLE))
            securityOf(d).also {
                if (it == SecurityLookup.NONE) return refused(participantId, id, replies.rejectOrder(d, UNKNOWN_SYMBOL, Texts.UNKNOWN_SECURITY))
            }
        }
        val qty = if (d.hasOrderQty()) PriceCodec.fromDecimal(d.orderQty().value(), d.orderQty().scale()) else PriceCodec.INVALID
        if (qty == PriceCodec.INVALID || qty <= 0L) return refused(participantId, id, replies.rejectOrder(d, INCORRECT_QUANTITY, Texts.INCORRECT_QUANTITY))
        if (d.ordType() != '2') return refused(participantId, id, replies.rejectOrder(d, OTHER, Texts.LIMIT_ORDERS_ONLY))
        if (!d.hasPrice()) return refused(participantId, id, replies.rejectOrder(d, OTHER, Texts.PRICE_REQUIRED))
        val price = PriceCodec.fromDecimal(d.price().value(), d.price().scale())
        if (price == PriceCodec.INVALID) return refused(participantId, id, replies.rejectOrder(d, OTHER, Texts.PRICE_NOT_EXACT))
        val side = when (d.side()) {
            '1' -> Side.BUY
            '2' -> Side.SELL
            else -> return refused(participantId, id, replies.rejectOrder(d, OTHER, Texts.SIDE_ONLY))
        }
        val timeInForce = if (d.hasTimeInForce()) d.timeInForce() else '0'
        val expireDate = when (timeInForce) {
            '0' -> tradingDate.asInt
            '1' -> 0
            '6' -> if (d.hasExpireDate()) FixParse.localMktDate(d.expireDate(field)) else FixParse.INVALID_DATE
            else -> return refused(participantId, id, replies.rejectOrder(d, OTHER, Texts.TIME_IN_FORCE_ONLY))
        }
        if (expireDate == FixParse.INVALID_DATE) return refused(participantId, id, replies.rejectOrder(d, OTHER, Texts.EXPIRE_DATE_REQUIRED))
        val smpId = if (d.hasSelfMatchPreventionID()) FixParse.positiveLong(d.selfMatchPreventionID(field)) else 0L
        if (smpId < 0L) return refused(participantId, id, replies.rejectOrder(d, OTHER, Texts.SMP_ID_POSITIVE))

        val row = store.newRow()
        row.kind = RowKind.ORDER
        row.participantId = participantId
        row.clOrdId = id
        row.fixClOrdId(clOrdId.buffer(), clOrdId.offset(), clOrdId.length)
        row.entryTradingDate = tradingDate.asInt
        row.securityId = securityId
        row.shardId = if (recovering) RequestRow.NO_SHARD else venue.shardOf(securityId)
        row.side = side
        row.price = price
        row.origQty = qty
        row.leavesQty = qty
        row.timeInForce = timeInForce.code.toByte()
        row.expireDate = expireDate
        row.smpId = smpId
        row.smpStrategy = if (d.hasSelfMatchPreventionInstruction()) d.selfMatchPreventionInstruction().toByte() else 0
        row.sendAfter = position
        return when (store.insert(row)) {
            InsertResult.NEW -> {
                if (recovering) rebuilt(row, offset, length) else sends.send(row)
                Action.CONTINUE
            }
            InsertResult.DUPLICATE_CLORDID -> {
                store.release(row)
                if (isPossDup(d.header())) Action.CONTINUE
                else refused(participantId, id, replies.rejectOrder(d, DUPLICATE_ORDER, Texts.DUPLICATE_CLORDID))
            }
            // Ruled out by the lookup above; the store says so too if that ever changes.
            InsertResult.SAME, InsertResult.ID_COLLISION -> error("clOrdId $id held, yet not found")
        }
    }

    // ------------------------------------------------------------------ 35=F

    private fun onCancel(participantId: Long, replies: ClientReplies, sequenceIndex: Int, offset: Int, length: Int): Action {
        val c = cancel
        c.reset()
        c.decode(message, offset, length)
        if (!c.validate()) return sessionReject(replies, c.header(), c.invalidTagId(), c.rejectReason())

        val id = derive(sequenceIndex, c.header().msgSeqNum())
        c.clOrdID(clOrdId)
        c.origClOrdID(origClOrdId)
        if (store.isBlocked(participantId)) {
            return refused(participantId, id, replies.rejectCancel(c, orderNamed(participantId), OTHER, Texts.SEQUENCE_RESET_REQUIRED))
        }

        val held = store.find(participantId, id)
        if (held != null) {
            if (!held.fixClOrdIdEquals(clOrdId.buffer(), clOrdId.offset(), clOrdId.length)) {
                return collision(participantId, sequenceIndex, replies.rejectCancel(c, orderNamed(participantId), OTHER, Texts.SEQUENCE_RESET_REQUIRED), replies)
            }
            if (recovering && held.isUnresolved) keepUnresolved(held, offset, length)
            return Action.CONTINUE
        }
        if (recovering && id <= store.highWater(participantId)) return Action.CONTINUE
        val order = orderNamed(participantId)
        if (!recovering && !securities.isReady) return refused(participantId, id, replies.rejectCancel(c, order, OTHER, Texts.EXCHANGE_UNAVAILABLE))
        if (order == null) return refused(participantId, id, replies.rejectCancel(c, null, UNKNOWN_ORDER, Texts.UNKNOWN_ORDER))
        if (!matches(c, order)) return refused(participantId, id, replies.rejectCancel(c, order, UNKNOWN_ORDER, Texts.ORDER_MISMATCH))
        // In recovery the store may predate the cancel: a lost write can have cleared ambiguity or a
        // pending cancel since (§8.17). Terminal is final, so it is decided again.
        if (!recovering && order.ambiguous) {
            return refused(participantId, id, replies.rejectCancel(c, order, OTHER, Texts.STATUS_UNKNOWN, statusUnknown = true))
        }
        if (RequestRow.isTerminal(order.ordStatus)) return refused(participantId, id, replies.rejectCancel(c, order, TOO_LATE, Texts.TOO_LATE))
        if (!recovering && order.pendingCancel != null) {
            return refused(participantId, id, replies.rejectCancel(c, order, ALREADY_PENDING, Texts.ALREADY_PENDING))
        }

        val row = store.newRow()
        row.kind = RowKind.CANCEL
        row.participantId = participantId
        row.clOrdId = id
        row.fixClOrdId(clOrdId.buffer(), clOrdId.offset(), clOrdId.length)
        row.entryTradingDate = tradingDate.asInt
        row.origClOrdId = order.clOrdId
        row.shardId = order.shardId
        // Before the order's first report there is no exchangeOrderId to cancel by: parked (§3.3).
        // Recovered, it may have been sent: sent, so it is never sent again (§8.17).
        row.cancelState = if (recovering || order.acknowledged) CancelState.SENT else CancelState.PARKED
        if (recovering) row.securityId = RequestRow.UNRESOLVED_SECURITY // the security it names, checked then
        row.sendAfter = position
        return when (store.insert(row)) {
            InsertResult.NEW -> {
                if (order.pendingCancel == null) order.pendingCancel = row
                if (recovering) rebuilt(row, offset, length) else if (row.cancelState == CancelState.SENT) sends.send(row)
                Action.CONTINUE
            }
            InsertResult.DUPLICATE_CLORDID -> {
                store.release(row)
                if (isPossDup(c.header())) Action.CONTINUE
                else refused(participantId, id, replies.rejectCancel(c, order, DUPLICATE_CLORDID, Texts.DUPLICATE_CLORDID))
            }
            InsertResult.SAME, InsertResult.ID_COLLISION -> error("clOrdId $id held, yet not found")
        }
    }

    /**
     * The order the cancel's OrigClOrdID names, or null: unknown, or a cancel's ClOrdID. A refusal
     * then states that order's status, never a reject for an order that may be open (§3.3).
     */
    private fun orderNamed(participantId: Long): RequestRow? =
        store.findByFixClOrdId(participantId, origClOrdId.buffer(), origClOrdId.offset(), origClOrdId.length)?.takeIf { it.kind == RowKind.ORDER }

    /**
     * OrderID, Side and the security, where the cancel states them, must be the order's (§5.2). In
     * recovery an OrderID is checked only against an order the store has acknowledged, since the
     * acknowledgement may be a write the crash lost, and the security once the directory is there.
     */
    private fun matches(c: OrderCancelRequestDecoder, order: RequestRow): Boolean {
        if (c.hasOrderID()) {
            if (order.acknowledged) {
                if (FixParse.positiveLong(c.orderID(field)) != order.exchangeOrderId) return false
            } else if (!recovering) {
                return false
            }
        }
        val side = if (order.side == Side.SELL) '2' else '1'
        if (c.side() != side) return false
        if (!recovering && (c.hasSecurityID() || c.hasSymbol())) {
            if (securityOf(c) != order.securityId) return false
        }
        return true
    }

    // ------------------------------------------------------------------ 35=H, 35=AF, §3.3

    private fun onStatusRequest(participantId: Long, replies: ClientReplies, offset: Int, length: Int): Action {
        val h = statusRequest
        h.reset()
        h.decode(message, offset, length)
        if (!h.validate()) return sessionReject(replies, h.header(), h.invalidTagId(), h.rejectReason())
        h.clOrdID(clOrdId)
        val row = store.findByFixClOrdId(participantId, clOrdId.buffer(), clOrdId.offset(), clOrdId.length)
            ?: return answered(replies.unknownOrderStatus(h))
        // A cancel's ClOrdID: its order's status, 11 the cancel's and 41 the order's.
        val order = if (row.kind == RowKind.CANCEL) store.find(participantId, row.origClOrdId) ?: error("cancel ${row.clOrdId} without its order") else row
        val e = stated(order)
        if (row.kind == RowKind.CANCEL) e.cancel = row
        if (h.hasOrdStatusReqID()) h.ordStatusReqID(field).let { e.ordStatusReqId(it.buffer(), it.offset(), it.length) }
        return answered(replies.status(e))
    }

    /** The 35=AF being answered, kept across Artio's refusals so its redelivery goes on (§3.3). */
    private val massStatus = MassStatusAnswer()

    private class MassStatusAnswer {
        var participantId = 0L
        var sequenceIndex = -1
        var msgSeqNum = 0
        var clOrdIds = LongArray(16)
        var size = 0
        var next = 0

        fun add(clOrdId: Long) {
            if (size == clOrdIds.size) clOrdIds = clOrdIds.copyOf(size * 2)
            clOrdIds[size++] = clOrdId
        }

        fun isFor(participantId: Long, sequenceIndex: Int, msgSeqNum: Int) =
            this.participantId == participantId && this.sequenceIndex == sequenceIndex && this.msgSeqNum == msgSeqNum

        fun clear() {
            sequenceIndex = -1
            size = 0
            next = 0
        }
    }

    private fun onMassStatusRequest(participantId: Long, replies: ClientReplies, sequenceIndex: Int, offset: Int, length: Int): Action {
        val af = massStatusRequest
        af.reset()
        af.decode(message, offset, length)
        if (!af.validate()) return sessionReject(replies, af.header(), af.invalidTagId(), af.rejectReason())
        val m = massStatus
        val msgSeqNum = af.header().msgSeqNum()
        if (!m.isFor(participantId, sequenceIndex, msgSeqNum)) {
            m.clear()
            var securityId = SecurityLookup.NONE
            if (af.massStatusReqType() == MASS_STATUS_SECURITY) {
                if (!af.hasSecurityID() && !af.hasSymbol()) {
                    return massReject(replies, af, CONDITIONALLY_REQUIRED_FIELD_MISSING, Texts.SECURITY_REQUIRED)
                }
                if (!securities.isReady) return massReject(replies, af, APPLICATION_NOT_AVAILABLE, Texts.EXCHANGE_UNAVAILABLE)
                securityId = securityOf(af)
                if (securityId == SecurityLookup.NONE) return massReject(replies, af, UNKNOWN_SECURITY, Texts.UNKNOWN_SECURITY)
            }
            val side = if (!af.hasSide()) Side.NULL_VAL else if (af.side() == '2') Side.SELL else Side.BUY
            store.forEach { row ->
                if (row.participantId != participantId || row.kind != RowKind.ORDER || RequestRow.isTerminal(row.ordStatus)) return@forEach
                if (securityId != SecurityLookup.NONE && row.securityId != securityId) return@forEach
                if (side != Side.NULL_VAL && row.side != side) return@forEach
                m.add(row.clOrdId)
            }
            if (m.size == 0) return massReject(replies, af, OTHER_BUSINESS_REASON, Texts.NO_OPEN_ORDERS)
            java.util.Arrays.sort(m.clOrdIds, 0, m.size) // in the order they came (§3.2)
            m.participantId = participantId
            m.sequenceIndex = sequenceIndex
            m.msgSeqNum = msgSeqNum
        }
        val total = m.size
        val reqId = af.massStatusReqID(field)
        while (m.next < total) {
            val order = store.find(participantId, m.clOrdIds[m.next]) ?: error("order ${m.clOrdIds[m.next]} left the store")
            val e = stated(order)
            e.massStatusReqId(reqId.buffer(), reqId.offset(), reqId.length)
            e.totNumReports = total
            e.lastRptRequested = m.next == total - 1
            if (!replies.status(e)) return Action.ABORT // delivered again, and goes on from here
            m.next++
        }
        m.clear()
        return Action.CONTINUE
    }

    private fun massReject(replies: ClientReplies, af: OrderMassStatusRequestDecoder, reason: Int, text: String): Action {
        massStatus.clear()
        return answered(replies.businessReject(af.header().msgSeqNum(), af.header().msgType(msgType), reason, text))
    }

    /**
     * [order] as the client was last told it (§3.3, "What a status states"): OrdStatus `A` before any,
     * AvgPx over the fills known, and an ambiguous order's flag and Text (§3.4).
     */
    private fun stated(order: RequestRow): OutgoingExecution {
        val e = status
        e.reset(order)
        e.execType = 'I'
        e.ordStatus = if (order.ordStatus.toInt() == 0) 'A' else order.ordStatus.toInt().toChar()
        notional.set(order.notionalHi, order.notionalLo)
        e.avgPx = notional.average(order.cumQty)
        if (order.ambiguous) {
            e.statusUnknown = true
            e.textLength = StatusUnknownText.write(e.text, 0, order.cumQty)
        }
        return e
    }

    // ------------------------------------------------------------------ 35=G, the rest

    private fun onReplace(participantId: Long, replies: ClientReplies, offset: Int, length: Int): Action {
        val r = replace
        r.reset()
        r.decode(message, offset, length)
        if (!r.validate()) return sessionReject(replies, r.header(), r.invalidTagId(), r.rejectReason())
        r.origClOrdID(origClOrdId)
        val order = store.findByFixClOrdId(participantId, origClOrdId.buffer(), origClOrdId.offset(), origClOrdId.length)
        return answered(replies.rejectReplace(r, order, BROKER_OPTION, Texts.REPLACE_NOT_SUPPORTED))
    }

    private fun unsupported(replies: ClientReplies, offset: Int, length: Int): Action {
        header.reset()
        header.decode(message, offset, length)
        return answered(replies.businessReject(header.msgSeqNum(), header.msgType(msgType), UNSUPPORTED_MESSAGE_TYPE, Texts.UNSUPPORTED_MESSAGE_TYPE))
    }

    // ------------------------------------------------------------------ shared

    /** 48 with 22=8 or 22=4, else 55, through the directory (§5.1). */
    private fun securityOf(i: InstrumentDecoder): Int {
        if (i.hasSecurityID()) {
            if (!i.hasSecurityIDSource()) return SecurityLookup.NONE
            val source = i.securityIDSource(field)
            val securityId = i.securityID(field2)
            if (source.length != 1) return SecurityLookup.NONE
            return when (source[0]) {
                '8' -> {
                    val value = FixParse.positiveLong(securityId)
                    if (value in 0..Int.MAX_VALUE.toLong() && securities.isKnown(value.toInt())) value.toInt() else SecurityLookup.NONE
                }
                '4' -> securities.byIsin(securityId.buffer(), securityId.offset(), securityId.length)
                else -> SecurityLookup.NONE
            }
        }
        if (i.hasSymbol()) {
            val symbol = i.symbol(field)
            return securities.bySymbol(symbol.buffer(), symbol.offset(), symbol.length)
        }
        return SecurityLookup.NONE
    }

    private fun collision(participantId: Long, sequenceIndex: Int, rejected: Boolean, replies: ClientReplies): Action {
        if (!rejected) return Action.ABORT
        idCollisions++
        store.block(participantId, sequenceIndex)
        onIdCollision(participantId)
        replies.logout(Texts.SEQUENCE_RESET_REQUIRED)
        return Action.CONTINUE
    }

    private fun sessionReject(replies: ClientReplies, h: HeaderDecoder, tag: Int, reason: Int): Action =
        answered(replies.sessionReject(h.msgSeqNum(), h.msgType(msgType), tag, reason))

    private fun answered(accepted: Boolean): Action = if (accepted) Action.CONTINUE else Action.ABORT

    /** A 35=D or 35=F answered here with no row: once Artio took the answer, a restart does not replay it (§8.17). */
    private fun refused(participantId: Long, id: Long, accepted: Boolean): Action {
        if (accepted) store.handled(participantId, id)
        return answered(accepted)
    }

    private fun rebuilt(row: RequestRow, offset: Int, length: Int) {
        rebuilt++
        keepUnresolved(row, offset, length)
    }

    /** Recovery's replies: none is sent (§8.17). */
    private object Unanswered : ClientReplies {
        override fun sessionReject(refSeqNum: Int, refMsgType: AsciiSequenceView, refTagId: Int, reason: Int) = true
        override fun businessReject(refSeqNum: Int, refMsgType: AsciiSequenceView, reason: Int, text: String) = true
        override fun rejectOrder(order: NewOrderSingleDecoder, ordRejReason: Int, text: String) = true
        override fun rejectCancel(cancel: OrderCancelRequestDecoder, order: RequestRow?, cxlRejReason: Int, text: String, statusUnknown: Boolean) = true
        override fun rejectReplace(replace: OrderCancelReplaceRequestDecoder, order: RequestRow?, cxlRejReason: Int, text: String) = true
        override fun status(status: OutgoingExecution) = true
        override fun unknownOrderStatus(request: OrderStatusRequestDecoder) = true
        override fun logout(text: String) = Unit
    }

    private fun isPossDup(h: HeaderDecoder): Boolean = h.hasPossDupFlag() && h.possDupFlag()

    private companion object {
        /** Design.md §3.2. */
        fun derive(sequenceIndex: Int, msgSeqNum: Int): Long = (sequenceIndex.toLong() shl 32) or (msgSeqNum.toLong() and 0xffffffffL)

        // OrdRejReason (103).
        const val UNKNOWN_SYMBOL = 1
        const val DUPLICATE_ORDER = 6
        const val INCORRECT_QUANTITY = 13
        const val OTHER = 99

        // CxlRejReason (102).
        const val TOO_LATE = 0
        const val UNKNOWN_ORDER = 1
        const val BROKER_OPTION = 2
        const val ALREADY_PENDING = 3
        const val DUPLICATE_CLORDID = 6

        // BusinessRejectReason (380).
        const val OTHER_BUSINESS_REASON = 0
        const val UNKNOWN_SECURITY = 2
        const val UNSUPPORTED_MESSAGE_TYPE = 3
        const val APPLICATION_NOT_AVAILABLE = 4
        const val CONDITIONALLY_REQUIRED_FIELD_MISSING = 5

        /** MassStatusReqType (585) 1; the other served is 7, all orders (§3.3). */
        const val MASS_STATUS_SECURITY = 1
    }
}
