package nl.lamia.most.exchange.fixadapter.orderentry.outbound

import nl.lamia.most.exchange.fixadapter.orderentry.store.RequestRow
import uk.co.real_logic.artio.util.MutableAsciiBuffer

/**
 * The client as the outbound path writes to it (Design.md §5.3): a participant's FIX session. On
 * Artio's `Session` in production, numbered by it. A send returns false when it was refused
 * (back-pressured): nothing was sent, and the caller changes nothing.
 */
interface ClientReports {
    /**
     * The participant's session can be written to now. Until slice 4 delivers to the offline
     * session (§6.3), only a logged-on one can.
     */
    fun isOnline(participantId: Long): Boolean

    /** A 35=8. */
    fun send(report: OutgoingExecution): Boolean

    /** A 35=9, CxlRejResponseTo 1. */
    fun send(reject: OutgoingCancelReject): Boolean
}

/**
 * One 35=8 to send, filled and sent before the next: one instance, reused, so the report path
 * allocates nothing. Echoed fields come from [order]; the rest are set here.
 */
class OutgoingExecution {
    lateinit var order: RequestRow

    /** The cancel whose report this is: its ClOrdID is 11 and the order's 41. Null otherwise. */
    var cancel: RequestRow? = null

    /** The exchange's `reportSeq`, the ExecID; 0 for the adapter's own (`A<sequenceIndex>.<MsgSeqNum>`). */
    var reportSeq = 0L
    var exchangeOrderId = 0L
    var execType = '0'
    var ordStatus = '0'

    /** LastPx and LastQty, on a fill only; 0 otherwise. Fixed-point, 8 decimals. */
    var lastPx = 0L
    var lastQty = 0L
    var cumQty = 0L
    var leavesQty = 0L
    var avgPx = 0L

    /** OrdRejReason, or [NO_REASON]. */
    var ordRejReason = NO_REASON
    var statusUnknown = false
    val text = MutableAsciiBuffer(ByteArray(TEXT_CAPACITY))

    /** 0: no Text. */
    var textLength = 0

    fun reset(order: RequestRow) {
        this.order = order
        cancel = null
        reportSeq = 0L
        exchangeOrderId = order.exchangeOrderId
        execType = '0'
        ordStatus = '0'
        lastPx = 0L
        lastQty = 0L
        cumQty = order.cumQty
        leavesQty = order.leavesQty
        avgPx = 0L
        ordRejReason = NO_REASON
        statusUnknown = false
        textLength = 0
    }

    fun text(value: String) {
        textLength = text.putAscii(0, value)
    }

    companion object {
        const val NO_REASON = -1
        const val TEXT_CAPACITY = 256
    }
}

/** One 35=9 to send, of [cancel] for [order], as [OutgoingExecution] is reused. */
class OutgoingCancelReject {
    lateinit var cancel: RequestRow
    lateinit var order: RequestRow

    /** 0 when the order is not acknowledged, or CxlRejReason is 1: OrderID `NONE` (§3.3). */
    var exchangeOrderId = 0L
    var ordStatus = 'A'
    var cxlRejReason = 0
    var statusUnknown = false
    val text = MutableAsciiBuffer(ByteArray(OutgoingExecution.TEXT_CAPACITY))
    var textLength = 0

    fun reset(cancel: RequestRow, order: RequestRow) {
        this.cancel = cancel
        this.order = order
        exchangeOrderId = if (order.acknowledged) order.exchangeOrderId else 0L
        ordStatus = if (order.ordStatus.toInt() == 0) 'A' else order.ordStatus.toInt().toChar()
        cxlRejReason = 0
        statusUnknown = false
        textLength = 0
    }

    fun text(value: String) {
        textLength = text.putAscii(0, value)
    }
}
