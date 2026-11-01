package nl.lamia.most.exchange.fixadapter.orderentry.inbound

import nl.lamia.most.exchange.fixadapter.codecs.decoder.NewOrderSingleDecoder
import nl.lamia.most.exchange.fixadapter.codecs.decoder.OrderCancelReplaceRequestDecoder
import nl.lamia.most.exchange.fixadapter.codecs.decoder.OrderCancelRequestDecoder
import nl.lamia.most.exchange.fixadapter.codecs.decoder.OrderStatusRequestDecoder
import nl.lamia.most.exchange.fixadapter.orderentry.outbound.OutgoingExecution
import nl.lamia.most.exchange.fixadapter.orderentry.store.RequestRow
import org.agrona.AsciiSequenceView

/** What the client was answered, as it would read it: the ids it sent, the codes, the Text. */
sealed interface Reply {
    data class SessionReject(val refSeqNum: Int, val refMsgType: String, val refTagId: Int, val reason: Int) : Reply
    data class BusinessReject(val refSeqNum: Int, val refMsgType: String, val reason: Int, val text: String) : Reply
    data class OrderReject(val clOrdId: String, val ordRejReason: Int, val text: String) : Reply
    data class CancelReject(
        val clOrdId: String, val origClOrdId: String, val cxlRejReason: Int, val text: String, val statusUnknown: Boolean = false,
    ) : Reply
    data class ReplaceReject(val clOrdId: String, val origClOrdId: String, val cxlRejReason: Int, val text: String) : Reply

    /** A 35=8 ExecType I from the store (§3.3): what it says of the order, and the ids it echoes. */
    data class Status(
        val clOrdId: String, val origClOrdId: String?, val ordStatus: Char, val orderId: Long, val cumQty: Long, val leavesQty: Long,
        val avgPx: Long, val statusUnknown: Boolean, val text: String?, val ordStatusReqId: String?, val massStatusReqId: String?,
        val totNumReports: Int, val lastRptRequested: Boolean,
    ) : Reply

    data class UnknownOrderStatus(val clOrdId: String, val side: Char, val ordStatusReqId: String?) : Reply
}

/** Records every reply; with [accepting] false it refuses each one, as a back-pressured Artio does. */
class RecordingReplies : ClientReplies {
    val sent = mutableListOf<Reply>()
    var accepting = true

    /** Accepts this many more replies, then refuses, as Artio back-pressured part way; null: [accepting] decides. */
    var acceptOnly: Int? = null

    /** For each 35=9 sent, the FIX ClOrdID of the order it names, or null: its status is stated from it. */
    val namedOrders = mutableListOf<String?>()

    private fun record(reply: Reply): Boolean {
        val left = acceptOnly
        val accept = if (left == null) accepting else left > 0
        if (left != null && left > 0) acceptOnly = left - 1
        if (accept) sent += reply
        return accept
    }

    override fun status(status: OutgoingExecution) = record(
        Reply.Status(
            status.cancel?.fixClOrdIdAsString() ?: status.order.fixClOrdIdAsString(),
            status.cancel?.let { status.order.fixClOrdIdAsString() },
            status.ordStatus, status.exchangeOrderId, status.cumQty, status.leavesQty, status.avgPx, status.statusUnknown,
            if (status.textLength > 0) status.text.getStringWithoutLengthAscii(0, status.textLength) else null,
            if (status.ordStatusReqIdLength > 0) status.ordStatusReqId.getStringWithoutLengthAscii(0, status.ordStatusReqIdLength) else null,
            if (status.massStatusReqIdLength > 0) status.massStatusReqId.getStringWithoutLengthAscii(0, status.massStatusReqIdLength) else null,
            status.totNumReports, status.lastRptRequested,
        ),
    )

    override fun unknownOrderStatus(request: OrderStatusRequestDecoder) = record(
        Reply.UnknownOrderStatus(request.clOrdIDAsString(), request.side(), if (request.hasOrdStatusReqID()) request.ordStatusReqIDAsString() else null),
    )

    override fun sessionReject(refSeqNum: Int, refMsgType: AsciiSequenceView, refTagId: Int, reason: Int) =
        record(Reply.SessionReject(refSeqNum, refMsgType.toString(), refTagId, reason))

    override fun businessReject(refSeqNum: Int, refMsgType: AsciiSequenceView, reason: Int, text: String) =
        record(Reply.BusinessReject(refSeqNum, refMsgType.toString(), reason, text))

    override fun rejectOrder(order: NewOrderSingleDecoder, ordRejReason: Int, text: String) =
        record(Reply.OrderReject(order.clOrdIDAsString(), ordRejReason, text))

    override fun rejectCancel(
        cancel: OrderCancelRequestDecoder, order: RequestRow?, cxlRejReason: Int, text: String, statusUnknown: Boolean,
    ) = record(Reply.CancelReject(cancel.clOrdIDAsString(), cancel.origClOrdIDAsString(), cxlRejReason, text, statusUnknown))
        .also { if (it) namedOrders += order?.fixClOrdIdAsString() }

    override fun rejectReplace(replace: OrderCancelReplaceRequestDecoder, order: RequestRow?, cxlRejReason: Int, text: String) =
        record(Reply.ReplaceReject(replace.clOrdIDAsString(), replace.origClOrdIDAsString(), cxlRejReason, text))
}

/** Accepts every reply and keeps nothing: for measuring the path, not its answers. */
object NoReplies : ClientReplies {
    override fun sessionReject(refSeqNum: Int, refMsgType: AsciiSequenceView, refTagId: Int, reason: Int) = true
    override fun businessReject(refSeqNum: Int, refMsgType: AsciiSequenceView, reason: Int, text: String) = true
    override fun rejectOrder(order: NewOrderSingleDecoder, ordRejReason: Int, text: String) = true
    override fun rejectCancel(cancel: OrderCancelRequestDecoder, order: RequestRow?, cxlRejReason: Int, text: String, statusUnknown: Boolean) = true
    override fun rejectReplace(replace: OrderCancelReplaceRequestDecoder, order: RequestRow?, cxlRejReason: Int, text: String) = true
    override fun status(status: OutgoingExecution) = true
    override fun unknownOrderStatus(request: OrderStatusRequestDecoder) = true
}

/**
 * A FIX 4.4 message of [msgType] with [fields] in the order given, between a correct BodyLength and
 * CheckSum: what no encoder writes, such as a tag outside the dictionary.
 */
fun fixMessage(msgType: String, fields: List<Pair<Int, String>>): ByteArray {
    val body = (listOf(35 to msgType) + fields).joinToString("") { (tag, value) -> "$tag=$value\u0001" }
    val head = "8=FIX.4.4\u00019=${body.toByteArray(Charsets.ISO_8859_1).size}\u0001"
    val checksum = (head + body).toByteArray(Charsets.ISO_8859_1).sumOf { it.toInt() and 0xFF } % 256
    return "$head${body}10=${"%03d".format(checksum)}\u0001".toByteArray(Charsets.ISO_8859_1)
}
