package nl.lamia.most.exchange.fixadapter.orderentry.store

import nl.lamia.most.exchange.client.OrderFate
import nl.lamia.most.exchange.sbe.Side
import org.agrona.DirectBuffer
import org.agrona.concurrent.UnsafeBuffer

/** What a row stands for (Design.md §6.1): a 35=D, or a 35=F with a row of its own. */
enum class RowKind { ORDER, CANCEL }

/** A cancel's progress. *Parked* waits for its order's first report (§3.3); only *sent* is in flight. */
enum class CancelState { PARKED, SENT, ANSWERED }

/** What [OrderStore.insert] found (Design.md §6.1). Only [NEW] writes anything. */
enum class InsertResult {
    NEW,

    /** The very request again, same `clOrdId` and ClOrdID: a back-pressure redelivery or a PossDup resend. */
    SAME,

    /** Another of the participant's rows, an order or a cancel, holds the FIX ClOrdID. */
    DUPLICATE_CLORDID,

    /** Another FIX ClOrdID holds the `clOrdId`: Artio's sequence went back (§3.2, §8.15). */
    ID_COLLISION,
}

/**
 * One request's row: an order's fields, or a cancel's. Mutable and pooled by [OrderStore], so the
 * library thread allocates nothing per order. Change it, then [OrderStore.update] it.
 *
 * Status fields are FIX's own codes (OrdStatus, TimeInForce, 2964), since they are what the client
 * was told. Prices and quantities are the exchange's fixed-point `int64` (CLAUDE.md non-negotiable 3).
 */
class RequestRow internal constructor() {
    var kind = RowKind.ORDER
    var participantId = 0L

    /** The exchange's id, derived (Design.md §3.2). */
    var clOrdId = 0L
    var entryTradingDate = 0

    /**
     * The shard the request went to, [NO_SHARD] if none: an order's security's at entry, a cancel's
     * order's. Its reports carry that shard's `reportSeq` (Design.md §6.1), which retention compares.
     */
    var shardId = NO_SHARD
    var lastReportSeq = 0L

    /** A fate whose 35=8 (or 35=9) is still to be sent (Design.md §6.2). */
    var owed: OrderFate? = null

    // An order's fields.
    var acknowledged = false
    var exchangeOrderId = 0L
    var securityId = 0
    var side: Side = Side.BUY
    var price = 0L
    var origQty = 0L
    var cumQty = 0L
    var leavesQty = 0L

    /** Cumulative notional, `price × lastQty` summed, 128 bits (Design.md §5.3). [notionalLo] is unsigned. */
    var notionalHi = 0L
    var notionalLo = 0L
    var timeInForce: Byte = 0
    var expireDate = 0

    /** 2362 as sent, 0 if absent. */
    var smpId = 0L

    /** 2964 as sent (1 or 2), 0 if absent. */
    var smpStrategy: Byte = 0

    /** The OrdStatus last sent to the client, 0 before any. */
    var ordStatus: Byte = 0
    var ambiguous = false

    // A cancel's fields.
    var origClOrdId = 0L
    var cancelState = CancelState.SENT

    private var fixId = ByteArray(INITIAL_CLORDID_CAPACITY)
    internal var fixIdView = UnsafeBuffer(fixId)
        private set

    var fixClOrdIdLength = 0
        private set

    // In memory only, never stored.

    /**
     * This process has the request to send, or on its way to be sent (Design.md §6.1): waiting for the
     * archive, back-pressured, or a parked cancel let go. False after a restart, when a stored request
     * may have been sent, and a request sent twice is two orders under one `clOrdId`.
     */
    var sendPending = false

    /**
     * The request's 35=D or 35=F's position on the engine's inbound stream, in this process; 0 if it
     * came from the store. It is sent once the archive has recorded that far (§6.1, §8.20).
     */
    var sendAfter = 0L

    /** A refused send's Text, for the reject owed as [owed] (§6.1); null for the fate's own. */
    var owedText: String? = null

    /** An order's cancel parked or sent, and not yet answered (§3.3). Rebuilt by [OrderStore.load]. */
    var pendingCancel: RequestRow? = null

    /** When an `ORDER_STATUS` last stated this order, on the outbound path's clock; 0 never (§3.4). */
    var statedAt = 0L

    // The store's bookkeeping.
    internal var hash = 0L
    internal var nextSameHash: RequestRow? = null
    internal var stored = false

    /** Copies the FIX ClOrdID from the message's bytes. */
    fun fixClOrdId(buffer: DirectBuffer, offset: Int, length: Int) {
        require(length in 0..MAX_FIX_CLORDID_LENGTH) { "ClOrdID of $length bytes" }
        if (length > fixId.size) {
            fixId = ByteArray(maxOf(length, fixId.size * 2))
            fixIdView = UnsafeBuffer(fixId)
        }
        buffer.getBytes(offset, fixId, 0, length)
        fixClOrdIdLength = length
    }

    /** For tests and tools: allocates. */
    fun fixClOrdId(ascii: String) {
        val bytes = ascii.toByteArray(Charsets.US_ASCII)
        fixClOrdId(UnsafeBuffer(bytes), 0, bytes.size)
    }

    fun fixClOrdIdEquals(buffer: DirectBuffer, offset: Int, length: Int): Boolean {
        if (length != fixClOrdIdLength) return false
        for (i in 0 until length) {
            if (buffer.getByte(offset + i) != fixId[i]) return false
        }
        return true
    }

    /** Copies the FIX ClOrdID into [buffer]; returns its length. */
    fun getFixClOrdId(buffer: org.agrona.MutableDirectBuffer, offset: Int): Int {
        buffer.putBytes(offset, fixId, 0, fixClOrdIdLength)
        return fixClOrdIdLength
    }

    /** Allocates. */
    fun fixClOrdIdAsString(): String = String(fixId, 0, fixClOrdIdLength, Charsets.US_ASCII)

    /** In a state no report will change: an order terminal and not ambiguous, a cancel answered. */
    val isFinished: Boolean
        get() = when (kind) {
            RowKind.ORDER -> !ambiguous && isTerminal(ordStatus)
            RowKind.CANCEL -> cancelState == CancelState.ANSWERED
        }

    /**
     * Sent to the exchange and not yet answered or settled: handed back to the SDK at startup with
     * `restoreInFlight` (Design.md §6.1). A settled request is not, or it could be settled again.
     */
    val isInFlight: Boolean
        get() = owed == null && when (kind) {
            RowKind.ORDER -> !acknowledged && !ambiguous && !isTerminal(ordStatus)
            RowKind.CANCEL -> cancelState == CancelState.SENT
        }

    internal fun clear() {
        kind = RowKind.ORDER
        participantId = 0L
        clOrdId = 0L
        entryTradingDate = 0
        shardId = NO_SHARD
        lastReportSeq = 0L
        owed = null
        acknowledged = false
        exchangeOrderId = 0L
        securityId = 0
        side = Side.BUY
        price = 0L
        origQty = 0L
        cumQty = 0L
        leavesQty = 0L
        notionalHi = 0L
        notionalLo = 0L
        timeInForce = 0
        expireDate = 0
        smpId = 0L
        smpStrategy = 0
        ordStatus = 0
        ambiguous = false
        origClOrdId = 0L
        cancelState = CancelState.SENT
        fixClOrdIdLength = 0
        sendPending = false
        sendAfter = 0L
        owedText = null
        pendingCancel = null
        statedAt = 0L
        hash = 0L
        nextSameHash = null
        stored = false
    }

    companion object {
        /** The row stores the length in 16 bits. What a client may send is the backend's to bound. */
        const val MAX_FIX_CLORDID_LENGTH = 0xffff

        /** A request that reached no shard: refused before it was sent. */
        const val NO_SHARD = -1
        private const val INITIAL_CLORDID_CAPACITY = 32

        /** OrdStatus filled, canceled, expired, rejected. */
        fun isTerminal(ordStatus: Byte): Boolean = when (ordStatus.toInt().toChar()) {
            '2', '4', 'C', '8' -> true
            else -> false
        }
    }
}
