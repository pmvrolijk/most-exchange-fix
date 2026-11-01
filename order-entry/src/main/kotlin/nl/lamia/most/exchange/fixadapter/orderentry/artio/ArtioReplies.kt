package nl.lamia.most.exchange.fixadapter.orderentry.artio

import nl.lamia.most.exchange.fixadapter.codecs.builder.ExecutionReportEncoder
import nl.lamia.most.exchange.fixadapter.codecs.builder.OrderCancelRejectEncoder
import nl.lamia.most.exchange.fixadapter.codecs.decoder.NewOrderSingleDecoder
import nl.lamia.most.exchange.fixadapter.codecs.decoder.OrderCancelReplaceRequestDecoder
import nl.lamia.most.exchange.fixadapter.codecs.decoder.OrderCancelRequestDecoder
import nl.lamia.most.exchange.fixadapter.codecs.decoder.OrderStatusRequestDecoder
import nl.lamia.most.exchange.fixadapter.orderentry.exchange.DirectoryIndex
import nl.lamia.most.exchange.fixadapter.orderentry.inbound.ClientReplies
import nl.lamia.most.exchange.fixadapter.orderentry.inbound.Texts
import nl.lamia.most.exchange.fixadapter.orderentry.outbound.OutgoingExecution
import nl.lamia.most.exchange.fixadapter.orderentry.store.RequestRow
import nl.lamia.most.exchange.fixadapter.session.SessionRejects
import org.agrona.AsciiSequenceView
import org.agrona.concurrent.EpochClock
import org.agrona.concurrent.SystemEpochClock
import org.agrona.concurrent.UnsafeBuffer
import uk.co.real_logic.artio.builder.Encoder
import uk.co.real_logic.artio.fields.UtcTimestampEncoder
import uk.co.real_logic.artio.session.Session
import uk.co.real_logic.artio.util.MutableAsciiBuffer

/**
 * The inbound path's own answers (Design.md §3.3, "The adapter's own answers"), written on Artio's
 * [session] and numbered by it. [session] is set by the handler before each message.
 *
 * A send Artio refuses (back-pressured) returns false, and the inbound message is aborted. Each
 * encoder is reset before use: its setters for views and buffers wrap the caller's bytes rather than
 * copy them, and a reset points every field back at the encoder's own buffer. Allocation-free once
 * each [logout] Text has been seen.
 */
class ArtioReplies(directory: DirectoryIndex, private val clock: EpochClock = SystemEpochClock.INSTANCE) : ClientReplies {
    lateinit var session: Session

    private val statuses = ExecutionReportWriter(directory, clock)

    private val rejects = SessionRejects()
    private val execution = ExecutionReportEncoder()
    private val cancelReject = OrderCancelRejectEncoder()

    private val view = AsciiSequenceView()
    private val execId = MutableAsciiBuffer(ByteArray(32))
    private val orderId = MutableAsciiBuffer(ByteArray(24))
    private val text = MutableAsciiBuffer(ByteArray(256))
    private val transactTime = UtcTimestampEncoder()

    override fun sessionReject(refSeqNum: Int, refMsgType: AsciiSequenceView, refTagId: Int, reason: Int): Boolean =
        rejects.sessionReject(session, refSeqNum, refMsgType, refTagId, reason)

    override fun businessReject(refSeqNum: Int, refMsgType: AsciiSequenceView, reason: Int, text: String): Boolean =
        rejects.businessReject(session, refSeqNum, refMsgType, reason, text)

    override fun rejectOrder(order: NewOrderSingleDecoder, ordRejReason: Int, text: String): Boolean {
        val e = execution
        e.reset()
        e.orderID(NONE, 0, NONE.capacity())
        e.execID(execId, 0, nextExecId())
        e.execType(REJECTED).ordStatus(REJECTED).ordRejReason(ordRejReason)
        e.text(this.text, 0, this.text.putStringWithoutLengthAscii(0, text))
        e.leavesQty(0L, 0).cumQty(0L, 0).avgPx(0L, 0)
        e.clOrdID(order.clOrdID(view))
        e.side(order.side())
        val instrument = e.instrument()
        if (order.hasSymbol()) instrument.symbol(order.symbol(view))
        if (order.hasSecurityID()) instrument.securityID(order.securityID(view))
        if (order.hasSecurityIDSource()) instrument.securityIDSource(order.securityIDSource(view))
        if (order.hasOrderQty()) e.orderQtyData().orderQty(order.orderQty())
        if (order.hasPrice()) e.price(order.price())
        if (order.hasTimeInForce()) e.timeInForce(order.timeInForce())
        if (order.hasExpireDate()) order.expireDate(view).let { e.expireDate(it.buffer(), it.offset(), it.length) }
        e.transactTime(transactTime.buffer(), transactTime.encode(clock.time()))
        return send(e)
    }

    override fun rejectCancel(
        cancel: OrderCancelRequestDecoder, order: RequestRow?, cxlRejReason: Int, text: String, statusUnknown: Boolean,
    ): Boolean {
        val c = startCancelReject(order, cxlRejReason, CANCEL)
        c.clOrdID(cancel.clOrdID(view))
        c.origClOrdID(cancel.origClOrdID(view))
        if (statusUnknown && order != null) {
            c.ordStatusUnknown(true)
            c.text(this.text, 0, StatusUnknownText.write(this.text, 0, order.cumQty))
        } else {
            c.text(this.text, 0, this.text.putStringWithoutLengthAscii(0, text))
        }
        return send(c)
    }

    override fun rejectReplace(replace: OrderCancelReplaceRequestDecoder, order: RequestRow?, cxlRejReason: Int, text: String): Boolean {
        val c = startCancelReject(order, cxlRejReason, REPLACE)
        c.clOrdID(replace.clOrdID(view))
        c.origClOrdID(replace.origClOrdID(view))
        c.text(this.text, 0, this.text.putStringWithoutLengthAscii(0, text))
        return send(c)
    }

    override fun status(status: OutgoingExecution): Boolean = send(statuses.encode(status, session))

    override fun unknownOrderStatus(request: OrderStatusRequestDecoder): Boolean {
        val e = execution
        e.reset()
        e.orderID(NONE, 0, NONE.capacity())
        e.execID(execId, 0, nextExecId())
        e.execType(ORDER_STATUS).ordStatus(REJECTED).ordRejReason(UNKNOWN_ORDER_REASON)
        e.text(this.text, 0, this.text.putStringWithoutLengthAscii(0, Texts.UNKNOWN_ORDER))
        e.leavesQty(0L, 0).cumQty(0L, 0).avgPx(0L, 0)
        e.clOrdID(request.clOrdID(view))
        if (request.hasOrdStatusReqID()) e.ordStatusReqID(request.ordStatusReqID(view))
        e.side(request.side())
        val instrument = e.instrument()
        if (request.hasSymbol()) instrument.symbol(request.symbol(view))
        if (request.hasSecurityID()) instrument.securityID(request.securityID(view))
        if (request.hasSecurityIDSource()) instrument.securityIDSource(request.securityIDSource(view))
        e.transactTime(transactTime.buffer(), transactTime.encode(clock.time()))
        return send(e)
    }

    /**
     * OrderID and OrdStatus as Design.md §3.3 says: the order's when it is known, `NONE` and `8` for
     * an unknown order, CxlRejReason 1 (FIX 4.4's rule for 35=9), and `A` before any status was sent.
     */
    private fun startCancelReject(order: RequestRow?, cxlRejReason: Int, responseTo: Char): OrderCancelRejectEncoder {
        val c = cancelReject
        c.reset()
        val known = order != null && cxlRejReason != UNKNOWN_ORDER
        if (known && order.acknowledged) {
            c.orderID(orderId, 0, orderId.putLongAscii(0, order.exchangeOrderId))
        } else {
            c.orderID(NONE, 0, NONE.capacity())
        }
        val status = when {
            !known -> REJECTED
            order.ordStatus.toInt() == 0 -> PENDING_NEW
            else -> order.ordStatus.toInt().toChar()
        }
        c.ordStatus(status).cxlRejResponseTo(responseTo).cxlRejReason(cxlRejReason)
        return c
    }

    /** `A<sequenceIndex>.<MsgSeqNum>` of the message about to be sent (§5.3): Artio numbers it last sent + 1. */
    private fun nextExecId(): Int {
        var at = 0
        execId.putByte(at++, 'A'.code.toByte())
        at += execId.putIntAscii(at, session.sequenceIndex())
        execId.putByte(at++, '.'.code.toByte())
        at += execId.putIntAscii(at, session.lastSentMsgSeqNum() + 1)
        return at
    }

    private fun send(encoder: Encoder): Boolean = session.trySend(encoder) >= 0

    private companion object {
        val NONE = UnsafeBuffer("NONE".toByteArray(Charsets.US_ASCII))
        const val REJECTED = '8'
        const val ORDER_STATUS = 'I'

        /** OrdRejReason 5, unknown order. */
        const val UNKNOWN_ORDER_REASON = 5
        const val PENDING_NEW = 'A'
        const val CANCEL = '1'
        const val REPLACE = '2'
        const val UNKNOWN_ORDER = 1
    }
}

/**
 * Design.md §3.4's Text for an ambiguous order, with the CumQty last stated:
 * `STATUS UNKNOWN: not open at the exchange; fills since CumQty=<q> unrecoverable until reconciled`.
 */
object StatusUnknownText {
    private const val HEAD = "STATUS UNKNOWN: not open at the exchange; fills since CumQty="
    private const val TAIL = " unrecoverable until reconciled"

    /** Writes it at [offset]; returns its length. [cumQty] is fixed-point, 8 implied decimals. */
    fun write(buffer: MutableAsciiBuffer, offset: Int, cumQty: Long): Int {
        var at = offset + buffer.putStringWithoutLengthAscii(offset, HEAD)
        at += putDecimal(buffer, at, cumQty)
        at += buffer.putStringWithoutLengthAscii(at, TAIL)
        return at - offset
    }

    /** A fixed-point value as a FIX decimal, without trailing zeros: 10_50000000 is `10.5`. */
    internal fun putDecimal(buffer: MutableAsciiBuffer, offset: Int, value: Long): Int {
        require(value >= 0L) { "a quantity is never negative: $value" }
        var at = offset + buffer.putLongAscii(offset, value / SCALE)
        var fraction = value % SCALE
        if (fraction == 0L) return at - offset
        var digits = DECIMALS
        while (fraction % 10L == 0L) {
            fraction /= 10L
            digits--
        }
        buffer.putByte(at++, '.'.code.toByte())
        var divisor = 1L
        repeat(digits - 1) { divisor *= 10L }
        while (divisor > 0L) {
            buffer.putByte(at++, ('0'.code + (fraction / divisor % 10L).toInt()).toByte())
            divisor /= 10L
        }
        return at - offset
    }

    private const val DECIMALS = 8
    private const val SCALE = 100_000_000L
}
