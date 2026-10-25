package nl.lamia.most.exchange.fixadapter.orderentry.inbound

import nl.lamia.most.exchange.client.SendResult
import nl.lamia.most.exchange.fixadapter.codecs.decoder.NewOrderSingleDecoder
import nl.lamia.most.exchange.fixadapter.codecs.decoder.OrderCancelReplaceRequestDecoder
import nl.lamia.most.exchange.fixadapter.codecs.decoder.OrderCancelRequestDecoder
import nl.lamia.most.exchange.fixadapter.orderentry.store.RequestRow
import nl.lamia.most.exchange.sbe.Side
import nl.lamia.most.exchange.sbe.SmpStrategy
import org.agrona.AsciiSequenceView
import org.agrona.DirectBuffer

/**
 * The exchange as the inbound path sends to it: behind it, an `OrderEntrySession` per shard, chosen
 * by the security's shard (Design.md §3.5). The results are the SDK's own.
 */
interface OrderVenue {
    @Suppress("LongParameterList")
    fun newOrder(
        participantId: Long, clOrdId: Long, securityId: Int, side: Side, price: Long, qty: Long,
        expireDate: Int, smpId: Long, smpStrategy: SmpStrategy,
    ): SendResult

    @Suppress("LongParameterList")
    fun cancel(participantId: Long, clOrdId: Long, origClOrdId: Long, exchangeOrderId: Long, securityId: Int, side: Side): SendResult
}

/** The directory's securities (Design.md §5.1). Lookups by bytes allocate nothing. */
interface SecurityLookup {
    /** A complete directory has arrived. Until then every request is refused "exchange unavailable" (§3.3). */
    val isReady: Boolean

    fun isKnown(securityId: Int): Boolean

    /** The `securityId` of an ISIN (22=4), or [NONE]. */
    fun byIsin(buffer: DirectBuffer, offset: Int, length: Int): Int

    /** The `securityId` of a Symbol (55), or [NONE]. */
    fun bySymbol(buffer: DirectBuffer, offset: Int, length: Int): Int

    companion object {
        const val NONE = -1
    }
}

/**
 * The answers the inbound path gives a FIX session itself, never sent to the exchange. Each returns
 * false when Artio refused the message (back-pressured): the inbound message is then aborted and
 * delivered again (Design.md §3.3). Implemented on Artio's `Session`, which numbers each one.
 *
 * Texts are [Texts]' constants. A reply echoes what it can from the request's own decoder, and from
 * the order's row where there is one.
 */
interface ClientReplies {
    /** 35=3 (§5.4). */
    fun sessionReject(refSeqNum: Int, refMsgType: AsciiSequenceView, refTagId: Int, reason: Int): Boolean

    /** 35=j. */
    fun businessReject(refSeqNum: Int, refMsgType: AsciiSequenceView, reason: Int, text: String): Boolean

    /** 35=8, ExecType 8, OrdStatus 8, for a 35=D refused here (§5.1, §3.3). */
    fun rejectOrder(order: NewOrderSingleDecoder, ordRejReason: Int, text: String): Boolean

    /**
     * 35=9, CxlRejResponseTo 1, for a 35=F refused here. [order] is the order it names, if known;
     * [statusUnknown] sets OrdStatusUnknown=Y, on an ambiguous order (§3.4).
     */
    fun rejectCancel(
        cancel: OrderCancelRequestDecoder, order: RequestRow?, cxlRejReason: Int, text: String, statusUnknown: Boolean = false,
    ): Boolean

    /** 35=9, CxlRejResponseTo 2 (§3.3). */
    fun rejectReplace(replace: OrderCancelReplaceRequestDecoder, order: RequestRow?, cxlRejReason: Int, text: String): Boolean

    /** A Logout with [text], then the disconnect. */
    fun logout(text: String)
}

/** The Texts the inbound path answers with (Design.md §3.3, §5.1). Fixed, so a client can match on them. */
object Texts {
    const val UNKNOWN_SECURITY = "unknown security"
    const val INCORRECT_QUANTITY = "incorrect quantity"
    const val LIMIT_ORDERS_ONLY = "limit orders only"
    const val PRICE_REQUIRED = "price required"
    const val PRICE_NOT_EXACT = "price not exact at 8 decimals"
    const val SIDE_ONLY = "side 1 or 2 only"
    const val TIME_IN_FORCE_ONLY = "time in force 0, 1 or 6 only"
    const val EXPIRE_DATE_REQUIRED = "ExpireDate required for GTD"
    const val SMP_ID_POSITIVE = "SelfMatchPreventionID must be a positive integer"
    const val DUPLICATE_CLORDID = "duplicate ClOrdID"
    const val SEQUENCE_RESET_REQUIRED = "session sequence reset required"
    const val EXCHANGE_UNAVAILABLE = "exchange unavailable"
    const val PARTICIPANT_NOT_SERVED = "participant not served"
    const val UNKNOWN_ORDER = "unknown order"
    const val ORDER_MISMATCH = "does not match the order"
    const val TOO_LATE = "too late to cancel"
    const val ALREADY_PENDING = "already pending cancel"
    const val STATUS_UNKNOWN = "STATUS UNKNOWN"
    const val REPLACE_NOT_SUPPORTED = "replace not supported"
    const val UNSUPPORTED_MESSAGE_TYPE = "unsupported message type"
}
