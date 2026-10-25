package nl.lamia.most.exchange.fixadapter.orderentry.inbound

import io.aeron.logbuffer.ControlledFragmentHandler.Action
import nl.lamia.most.exchange.client.PriceCodec
import nl.lamia.most.exchange.client.SendResult
import nl.lamia.most.exchange.fixadapter.codecs.Constants
import nl.lamia.most.exchange.fixadapter.codecs.decoder.HeaderDecoder
import nl.lamia.most.exchange.fixadapter.codecs.decoder.InstrumentDecoder
import nl.lamia.most.exchange.fixadapter.codecs.decoder.NewOrderSingleDecoder
import nl.lamia.most.exchange.fixadapter.codecs.decoder.OrderCancelReplaceRequestDecoder
import nl.lamia.most.exchange.fixadapter.codecs.decoder.OrderCancelRequestDecoder
import nl.lamia.most.exchange.fixadapter.orderentry.store.CancelState
import nl.lamia.most.exchange.fixadapter.orderentry.store.InsertResult
import nl.lamia.most.exchange.fixadapter.orderentry.store.OrderStore
import nl.lamia.most.exchange.fixadapter.orderentry.store.RequestRow
import nl.lamia.most.exchange.fixadapter.orderentry.store.RowKind
import nl.lamia.most.exchange.sbe.Side
import nl.lamia.most.exchange.sbe.SmpStrategy
import org.agrona.AsciiSequenceView
import org.agrona.DirectBuffer
import uk.co.real_logic.artio.util.MutableAsciiBuffer
import java.util.function.IntSupplier

/**
 * Order entry's inbound path (Design.md §3.3, §5.1, §5.2): a FIX application message from an
 * order-entry session, to a request on its way to the exchange, or an answer given here.
 *
 * Called on the order-entry library's thread (§3.5), for the session's own [participantId] (§2),
 * with Artio's `sequenceIndex` for the message. The returned [Action] is Artio's: [Action.ABORT]
 * has the message delivered again, and every path is safe to run again (§3.3). Allocation-free on
 * the way to the exchange, apart from a row the store's pool does not have.
 */
class InboundOrders(
    private val store: OrderStore,
    private val venue: OrderVenue,
    private val securities: SecurityLookup,
    /** The adapter's trading date, `YYYYMMDD` (§3.6). */
    private val tradingDate: IntSupplier,
    /** An id collision (§3.3, §8.15), after the store has blocked the session: for counting and logging. */
    private val onIdCollision: (participantId: Long) -> Unit,
) {
    private val message = MutableAsciiBuffer()
    private val header = HeaderDecoder()
    private val newOrder = NewOrderSingleDecoder()
    private val cancel = OrderCancelRequestDecoder()
    private val replace = OrderCancelReplaceRequestDecoder()
    private val msgType = AsciiSequenceView()
    private val clOrdId = AsciiSequenceView()
    private val origClOrdId = AsciiSequenceView()
    private val field = AsciiSequenceView()
    private val field2 = AsciiSequenceView()

    var idCollisions = 0L
        private set

    /** Blocked after an id collision until its session is reset (§3.3). The store keeps it. */
    fun isBlocked(participantId: Long): Boolean = store.isBlocked(participantId)

    @Suppress("LongParameterList")
    fun onMessage(
        participantId: Long, replies: ClientReplies, sequenceIndex: Int, messageType: Long,
        buffer: DirectBuffer, offset: Int, length: Int,
    ): Action {
        message.wrap(buffer)
        return when (messageType) {
            Constants.NEW_ORDER_SINGLE_MESSAGE -> onNewOrder(participantId, replies, sequenceIndex, offset, length)
            Constants.ORDER_CANCEL_REQUEST_MESSAGE -> onCancel(participantId, replies, sequenceIndex, offset, length)
            Constants.ORDER_CANCEL_REPLACE_REQUEST_MESSAGE -> onReplace(participantId, replies, offset, length)
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
        if (store.isBlocked(participantId)) return answered(replies.rejectOrder(d, OTHER, Texts.SEQUENCE_RESET_REQUIRED))

        val held = store.find(participantId, id)
        if (held != null) {
            if (!held.fixClOrdIdEquals(clOrdId.buffer(), clOrdId.offset(), clOrdId.length)) {
                return collision(participantId, sequenceIndex, replies.rejectOrder(d, OTHER, Texts.SEQUENCE_RESET_REQUIRED), replies)
            }
            // The same request again: sent only if this process's own send did not go out (§3.3).
            return if (held.sendPending) sendOrder(held, d, replies) else Action.CONTINUE
        }
        if (!securities.isReady) return answered(replies.rejectOrder(d, OTHER, Texts.EXCHANGE_UNAVAILABLE))

        val securityId = securityOf(d)
        if (securityId == SecurityLookup.NONE) return answered(replies.rejectOrder(d, UNKNOWN_SYMBOL, Texts.UNKNOWN_SECURITY))
        val qty = if (d.hasOrderQty()) PriceCodec.fromDecimal(d.orderQty().value(), d.orderQty().scale()) else PriceCodec.INVALID
        if (qty == PriceCodec.INVALID || qty <= 0L) return answered(replies.rejectOrder(d, INCORRECT_QUANTITY, Texts.INCORRECT_QUANTITY))
        if (d.ordType() != '2') return answered(replies.rejectOrder(d, OTHER, Texts.LIMIT_ORDERS_ONLY))
        if (!d.hasPrice()) return answered(replies.rejectOrder(d, OTHER, Texts.PRICE_REQUIRED))
        val price = PriceCodec.fromDecimal(d.price().value(), d.price().scale())
        if (price == PriceCodec.INVALID) return answered(replies.rejectOrder(d, OTHER, Texts.PRICE_NOT_EXACT))
        val side = when (d.side()) {
            '1' -> Side.BUY
            '2' -> Side.SELL
            else -> return answered(replies.rejectOrder(d, OTHER, Texts.SIDE_ONLY))
        }
        val timeInForce = if (d.hasTimeInForce()) d.timeInForce() else '0'
        val expireDate = when (timeInForce) {
            '0' -> tradingDate.asInt
            '1' -> 0
            '6' -> if (d.hasExpireDate()) FixParse.localMktDate(d.expireDate(field)) else FixParse.INVALID_DATE
            else -> return answered(replies.rejectOrder(d, OTHER, Texts.TIME_IN_FORCE_ONLY))
        }
        if (expireDate == FixParse.INVALID_DATE) return answered(replies.rejectOrder(d, OTHER, Texts.EXPIRE_DATE_REQUIRED))
        val smpId = if (d.hasSelfMatchPreventionID()) FixParse.positiveLong(d.selfMatchPreventionID(field)) else 0L
        if (smpId < 0L) return answered(replies.rejectOrder(d, OTHER, Texts.SMP_ID_POSITIVE))

        val row = store.newRow()
        row.kind = RowKind.ORDER
        row.participantId = participantId
        row.clOrdId = id
        row.fixClOrdId(clOrdId.buffer(), clOrdId.offset(), clOrdId.length)
        row.entryTradingDate = tradingDate.asInt
        row.securityId = securityId
        row.side = side
        row.price = price
        row.origQty = qty
        row.leavesQty = qty
        row.timeInForce = timeInForce.code.toByte()
        row.expireDate = expireDate
        row.smpId = smpId
        row.smpStrategy = if (d.hasSelfMatchPreventionInstruction()) d.selfMatchPreventionInstruction().toByte() else 0
        row.sendPending = true
        return when (store.insert(row)) {
            InsertResult.NEW -> sendOrder(row, d, replies)
            InsertResult.DUPLICATE_CLORDID -> {
                store.release(row)
                if (isPossDup(d.header())) Action.CONTINUE
                else answered(replies.rejectOrder(d, DUPLICATE_ORDER, Texts.DUPLICATE_CLORDID))
            }
            // Ruled out by the lookup above; the store says so too if that ever changes.
            InsertResult.SAME, InsertResult.ID_COLLISION -> error("clOrdId $id held, yet not found")
        }
    }

    private fun sendOrder(row: RequestRow, d: NewOrderSingleDecoder, replies: ClientReplies): Action {
        val result = venue.newOrder(
            row.participantId, row.clOrdId, row.securityId, row.side, row.price, row.origQty,
            row.expireDate, row.smpId, if (row.smpStrategy.toInt() == 2) SmpStrategy.CANCEL_RESTING else SmpStrategy.CANCEL_AGGRESSOR,
        )
        return when (result) {
            SendResult.SENT -> {
                row.sendPending = false
                Action.CONTINUE
            }
            SendResult.BACK_PRESSURED -> Action.ABORT
            SendResult.NOT_READY, SendResult.NO_GATEWAY, SendResult.UNKNOWN_PARTICIPANT -> {
                // The reply first: refused, the message comes again with the row still to send.
                if (!replies.rejectOrder(d, OTHER, unavailable(result))) return Action.ABORT
                row.sendPending = false
                row.ordStatus = ORD_STATUS_REJECTED
                store.update(row)
                Action.CONTINUE
            }
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
        if (store.isBlocked(participantId)) return answered(replies.rejectCancel(c, orderNamed(participantId), OTHER, Texts.SEQUENCE_RESET_REQUIRED))

        val held = store.find(participantId, id)
        if (held != null) {
            if (!held.fixClOrdIdEquals(clOrdId.buffer(), clOrdId.offset(), clOrdId.length)) {
                return collision(participantId, sequenceIndex, replies.rejectCancel(c, orderNamed(participantId), OTHER, Texts.SEQUENCE_RESET_REQUIRED), replies)
            }
            if (!held.sendPending) return Action.CONTINUE
            val order = store.find(participantId, held.origClOrdId) ?: error("cancel ${held.clOrdId} without its order")
            return sendCancel(held, order, c, replies)
        }
        val order = orderNamed(participantId)
        if (!securities.isReady) return answered(replies.rejectCancel(c, order, OTHER, Texts.EXCHANGE_UNAVAILABLE))
        if (order == null) return answered(replies.rejectCancel(c, null, UNKNOWN_ORDER, Texts.UNKNOWN_ORDER))
        if (!matches(c, order)) return answered(replies.rejectCancel(c, order, UNKNOWN_ORDER, Texts.ORDER_MISMATCH))
        if (order.ambiguous) return answered(replies.rejectCancel(c, order, OTHER, Texts.STATUS_UNKNOWN, statusUnknown = true))
        if (RequestRow.isTerminal(order.ordStatus)) return answered(replies.rejectCancel(c, order, TOO_LATE, Texts.TOO_LATE))
        if (order.pendingCancel != null) return answered(replies.rejectCancel(c, order, ALREADY_PENDING, Texts.ALREADY_PENDING))

        val row = store.newRow()
        row.kind = RowKind.CANCEL
        row.participantId = participantId
        row.clOrdId = id
        row.fixClOrdId(clOrdId.buffer(), clOrdId.offset(), clOrdId.length)
        row.entryTradingDate = tradingDate.asInt
        row.origClOrdId = order.clOrdId
        // Before the order's first report there is no exchangeOrderId to cancel by: parked (§3.3).
        row.cancelState = if (order.acknowledged) CancelState.SENT else CancelState.PARKED
        row.sendPending = order.acknowledged
        return when (store.insert(row)) {
            InsertResult.NEW -> {
                order.pendingCancel = row
                if (row.cancelState == CancelState.PARKED) Action.CONTINUE else sendCancel(row, order, c, replies)
            }
            InsertResult.DUPLICATE_CLORDID -> {
                store.release(row)
                if (isPossDup(c.header())) Action.CONTINUE
                else answered(replies.rejectCancel(c, order, DUPLICATE_CLORDID, Texts.DUPLICATE_CLORDID))
            }
            InsertResult.SAME, InsertResult.ID_COLLISION -> error("clOrdId $id held, yet not found")
        }
    }

    private fun sendCancel(row: RequestRow, order: RequestRow, c: OrderCancelRequestDecoder, replies: ClientReplies): Action {
        val result = venue.cancel(row.participantId, row.clOrdId, order.clOrdId, order.exchangeOrderId, order.securityId, order.side)
        return when (result) {
            SendResult.SENT -> {
                row.sendPending = false
                Action.CONTINUE
            }
            SendResult.BACK_PRESSURED -> Action.ABORT
            SendResult.NOT_READY, SendResult.NO_GATEWAY, SendResult.UNKNOWN_PARTICIPANT -> {
                if (!replies.rejectCancel(c, order, OTHER, unavailable(result))) return Action.ABORT
                row.sendPending = false
                row.cancelState = CancelState.ANSWERED
                if (order.pendingCancel === row) order.pendingCancel = null
                store.update(row)
                Action.CONTINUE
            }
        }
    }

    /**
     * The order the cancel's OrigClOrdID names, or null: unknown, or a cancel's ClOrdID. A refusal
     * then states that order's status, never a reject for an order that may be open (§3.3).
     */
    private fun orderNamed(participantId: Long): RequestRow? =
        store.findByFixClOrdId(participantId, origClOrdId.buffer(), origClOrdId.offset(), origClOrdId.length)?.takeIf { it.kind == RowKind.ORDER }

    /** OrderID, Side and the security, where the cancel states them, must be the order's (§5.2). */
    private fun matches(c: OrderCancelRequestDecoder, order: RequestRow): Boolean {
        if (c.hasOrderID()) {
            if (!order.acknowledged || FixParse.positiveLong(c.orderID(field)) != order.exchangeOrderId) return false
        }
        val side = if (order.side == Side.SELL) '2' else '1'
        if (c.side() != side) return false
        if (c.hasSecurityID() || c.hasSymbol()) {
            if (securityOf(c) != order.securityId) return false
        }
        return true
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

    private fun isPossDup(h: HeaderDecoder): Boolean = h.hasPossDupFlag() && h.possDupFlag()

    private fun unavailable(result: SendResult): String =
        if (result == SendResult.UNKNOWN_PARTICIPANT) Texts.PARTICIPANT_NOT_SERVED else Texts.EXCHANGE_UNAVAILABLE

    private companion object {
        /** Design.md §3.2. */
        fun derive(sequenceIndex: Int, msgSeqNum: Int): Long = (sequenceIndex.toLong() shl 32) or (msgSeqNum.toLong() and 0xffffffffL)

        val ORD_STATUS_REJECTED = '8'.code.toByte()

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
        const val UNSUPPORTED_MESSAGE_TYPE = 3
    }
}
