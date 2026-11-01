package nl.lamia.most.exchange.fixadapter.orderentry.store

import nl.lamia.most.exchange.client.ClOrdId
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

    /**
     * The very request again, its ClOrdID held at the same position and epoch: a back-pressure
     * redelivery, a PossDup resend or a replay.
     */
    SAME,

    /** Another of the participant's requests, an order or a cancel, holds the FIX ClOrdID. */
    DUPLICATE_CLORDID,
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

    /**
     * The request's position (Design.md §3.2): `(sequenceIndex << 32) | MsgSeqNum` of its 35=D or 35=F.
     * Never sent: recovery's high-water, the order a 35=AF answers in, and the trace's join.
     */
    var position = 0L

    /** The participant's epoch when the row was inserted: positions compare only within one (§3.2). Set by the store. */
    var epoch = 0
        internal set
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
    var cancelState = CancelState.SENT

    /**
     * The FIX ClOrdID, since schema 8 the exchange's `clOrdId` and the row's key (§3.2, §6.1): its bytes
     * NUL-padded to 20, as the exchange carries it, and the three words the store compares.
     */
    private val fixId = ByteArray(ClOrdId.LENGTH)
    internal val fixIdView = UnsafeBuffer(fixId)

    var fixClOrdIdLength = 0
        private set
    internal var word0 = 0L
        private set
    internal var word1 = 0L
        private set
    internal var word2 = 0L
        private set

    /** A cancel's order's ClOrdID (FIX 41), as [fixIdView] holds the cancel's own; empty for an order. */
    private val origId = ByteArray(ClOrdId.LENGTH)
    internal val origIdView = UnsafeBuffer(origId)
    var origClOrdIdLength = 0
        private set
    internal var origWord0 = 0L
        private set
    internal var origWord1 = 0L
        private set
    internal var origWord2 = 0L
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

    /**
     * Copies the FIX ClOrdID from the message's bytes. The inbound path has checked it against the
     * exchange's rule (§3.2); a row takes none it cannot key on: 1 to 20 bytes, no NUL.
     */
    fun fixClOrdId(buffer: DirectBuffer, offset: Int, length: Int) {
        fixClOrdIdLength = copyId(buffer, offset, length, fixIdView)
        word0 = ClOrdId.word0(fixIdView, 0)
        word1 = ClOrdId.word1(fixIdView, 0)
        word2 = ClOrdId.word2(fixIdView, 0)
    }

    /** For tests and tools: allocates. */
    fun fixClOrdId(ascii: String) {
        val bytes = ascii.toByteArray(Charsets.US_ASCII)
        fixClOrdId(UnsafeBuffer(bytes), 0, bytes.size)
    }

    /** A cancel's order's ClOrdID, from the message's 41 or a stored row. */
    fun origClOrdId(buffer: DirectBuffer, offset: Int, length: Int) {
        origClOrdIdLength = copyId(buffer, offset, length, origIdView)
        origWord0 = ClOrdId.word0(origIdView, 0)
        origWord1 = ClOrdId.word1(origIdView, 0)
        origWord2 = ClOrdId.word2(origIdView, 0)
    }

    /** A cancel of [order]: its ClOrdID is the cancel's 41. */
    fun origClOrdId(order: RequestRow) = origClOrdId(order.fixIdView, 0, order.fixClOrdIdLength)

    /** For tests: allocates. */
    fun origClOrdId(ascii: String) {
        val bytes = ascii.toByteArray(Charsets.US_ASCII)
        origClOrdId(UnsafeBuffer(bytes), 0, bytes.size)
    }

    /** Allocates. */
    fun origClOrdIdAsString(): String = String(origId, 0, origClOrdIdLength, Charsets.US_ASCII)

    /** The ClOrdID as the SDK takes it, for a send or `restoreInFlight`. Allocates. */
    fun toClOrdId(): ClOrdId = ClOrdId.read(fixIdView, 0)

    /** The order's ClOrdID as the SDK takes it, for a cancel's send. Allocates. */
    fun origToClOrdId(): ClOrdId = ClOrdId.read(origIdView, 0)

    internal fun hasId(w0: Long, w1: Long, w2: Long): Boolean = word0 == w0 && word1 == w1 && word2 == w2

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

    /**
     * A row recovery rebuilt before the directory arrived (Design.md §8.17): its security, an order's,
     * or the check of the one a cancel names, waits for the directory.
     */
    val isUnresolved: Boolean get() = securityId == UNRESOLVED_SECURITY

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
        position = 0L
        epoch = 0
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
        cancelState = CancelState.SENT
        fixId.fill(0)
        fixClOrdIdLength = 0
        word0 = 0L
        word1 = 0L
        word2 = 0L
        origId.fill(0)
        origClOrdIdLength = 0
        origWord0 = 0L
        origWord1 = 0L
        origWord2 = 0L
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
        /** A request that reached no shard: refused before it was sent. */
        const val NO_SHARD = -1

        /** [securityId] of a recovered request whose security waits for the directory (§8.17). */
        const val UNRESOLVED_SECURITY = -2

        /** [securityId] of a recovered order the directory does not know: refused, and stated with no 48. */
        const val NO_SECURITY = -1

        /** Copies 1 to 20 bytes with no NUL into [to], NUL-padded; returns the length. */
        private fun copyId(buffer: DirectBuffer, offset: Int, length: Int, to: UnsafeBuffer): Int {
            require(length in 1..ClOrdId.LENGTH) { "a ClOrdID of $length bytes: the exchange carries 1 to ${ClOrdId.LENGTH}" }
            for (i in 0 until ClOrdId.LENGTH) {
                val b: Byte = if (i < length) buffer.getByte(offset + i) else 0
                require(i >= length || b.toInt() != 0) { "a ClOrdID with a NUL at $i" }
                to.putByte(i, b)
            }
            return length
        }

        /** OrdStatus filled, canceled, expired, rejected. */
        fun isTerminal(ordStatus: Byte): Boolean = when (ordStatus.toInt().toChar()) {
            '2', '4', 'C', '8' -> true
            else -> false
        }
    }
}
