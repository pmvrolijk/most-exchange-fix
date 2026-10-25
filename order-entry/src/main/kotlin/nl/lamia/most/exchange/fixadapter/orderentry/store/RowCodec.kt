package nl.lamia.most.exchange.fixadapter.orderentry.store

import nl.lamia.most.exchange.client.OrderFate
import nl.lamia.most.exchange.sbe.Side
import org.agrona.DirectBuffer
import org.agrona.MutableDirectBuffer
import java.nio.ByteOrder

/**
 * A row's RocksDB value (Design.md §6.1). Versioned: a later version appends fields (§5.5) and
 * still reads this one. A version this code does not know is refused, never guessed at.
 *
 * Every code is explicit, never an enum's ordinal, so reordering an enum cannot change a stored row.
 */
internal object RowCodec {
    const val VERSION: Int = 1

    private val LE = ByteOrder.LITTLE_ENDIAN

    private const val VERSION_AT = 0 // u8
    private const val KIND_AT = 1 // u8
    private const val FLAGS_AT = 2 // u16
    private const val ENTRY_DATE_AT = 4 // i32
    private const val LAST_REPORT_SEQ_AT = 8 // i64
    private const val OWED_AT = 16 // u8
    private const val SIDE_AT = 17 // u8
    private const val TIME_IN_FORCE_AT = 18 // u8
    private const val SMP_STRATEGY_AT = 19 // u8
    private const val ORD_STATUS_AT = 20 // u8
    private const val CANCEL_STATE_AT = 21 // u8
    private const val CLORDID_LENGTH_AT = 22 // u16
    private const val EXCHANGE_ORDER_ID_AT = 24
    private const val SECURITY_ID_AT = 32 // i32
    private const val EXPIRE_DATE_AT = 36 // i32
    private const val PRICE_AT = 40
    private const val ORIG_QTY_AT = 48
    private const val CUM_QTY_AT = 56
    private const val LEAVES_QTY_AT = 64
    private const val NOTIONAL_HI_AT = 72
    private const val NOTIONAL_LO_AT = 80
    private const val SMP_ID_AT = 88
    private const val ORIG_CLORDID_AT = 96
    private const val SHARD_ID_AT = 104 // i32
    const val FIXED_LENGTH = 108 // the ClOrdID's bytes follow

    private const val FLAG_ACKNOWLEDGED = 1
    private const val FLAG_AMBIGUOUS = 2

    private const val KIND_ORDER = 1
    private const val KIND_CANCEL = 2
    private const val CANCEL_PARKED = 1
    private const val CANCEL_SENT = 2
    private const val CANCEL_ANSWERED = 3
    private const val OWED_NONE = 0
    private const val OWED_NEVER_SEQUENCED = 1
    private const val OWED_AMBIGUOUS = 2
    private const val SIDE_BUY = 1
    private const val SIDE_SELL = 2

    fun encodedLength(row: RequestRow): Int = FIXED_LENGTH + row.fixClOrdIdLength

    /** Writes [row] at offset 0 of [buffer], which must hold [encodedLength]; returns the length. */
    fun encode(row: RequestRow, buffer: MutableDirectBuffer): Int {
        buffer.putByte(VERSION_AT, VERSION.toByte())
        buffer.putByte(KIND_AT, (if (row.kind == RowKind.ORDER) KIND_ORDER else KIND_CANCEL).toByte())
        var flags = 0
        if (row.acknowledged) flags = flags or FLAG_ACKNOWLEDGED
        if (row.ambiguous) flags = flags or FLAG_AMBIGUOUS
        buffer.putShort(FLAGS_AT, flags.toShort(), LE)
        buffer.putInt(ENTRY_DATE_AT, row.entryTradingDate, LE)
        buffer.putLong(LAST_REPORT_SEQ_AT, row.lastReportSeq, LE)
        buffer.putByte(OWED_AT, owedCode(row.owed).toByte())
        buffer.putByte(SIDE_AT, (if (row.side == Side.SELL) SIDE_SELL else SIDE_BUY).toByte())
        buffer.putByte(TIME_IN_FORCE_AT, row.timeInForce)
        buffer.putByte(SMP_STRATEGY_AT, row.smpStrategy)
        buffer.putByte(ORD_STATUS_AT, row.ordStatus)
        buffer.putByte(CANCEL_STATE_AT, cancelCode(row.cancelState).toByte())
        buffer.putShort(CLORDID_LENGTH_AT, row.fixClOrdIdLength.toShort(), LE)
        buffer.putLong(EXCHANGE_ORDER_ID_AT, row.exchangeOrderId, LE)
        buffer.putInt(SECURITY_ID_AT, row.securityId, LE)
        buffer.putInt(EXPIRE_DATE_AT, row.expireDate, LE)
        buffer.putLong(PRICE_AT, row.price, LE)
        buffer.putLong(ORIG_QTY_AT, row.origQty, LE)
        buffer.putLong(CUM_QTY_AT, row.cumQty, LE)
        buffer.putLong(LEAVES_QTY_AT, row.leavesQty, LE)
        buffer.putLong(NOTIONAL_HI_AT, row.notionalHi, LE)
        buffer.putLong(NOTIONAL_LO_AT, row.notionalLo, LE)
        buffer.putLong(SMP_ID_AT, row.smpId, LE)
        buffer.putLong(ORIG_CLORDID_AT, row.origClOrdId, LE)
        buffer.putInt(SHARD_ID_AT, row.shardId, LE)
        val length = row.getFixClOrdId(buffer, FIXED_LENGTH)
        return FIXED_LENGTH + length
    }

    /** Reads a value into [row], whose key fields the caller has set. Refuses what it cannot read. */
    fun decode(buffer: DirectBuffer, length: Int, row: RequestRow) {
        val where = "order store row (participant ${row.participantId}, clOrdId ${row.clOrdId})"
        check(length >= 1) { "$where is empty" }
        val version = buffer.getByte(VERSION_AT).toInt() and 0xff
        check(version == VERSION) { "$where has version $version; this adapter reads version $VERSION only" }
        check(length >= FIXED_LENGTH) { "$where is $length bytes, shorter than version $VERSION's $FIXED_LENGTH" }
        val idLength = buffer.getShort(CLORDID_LENGTH_AT, LE).toInt() and 0xffff
        check(length == FIXED_LENGTH + idLength) { "$where is $length bytes; its ClOrdID needs ${FIXED_LENGTH + idLength}" }

        row.kind = when (val kind = buffer.getByte(KIND_AT).toInt()) {
            KIND_ORDER -> RowKind.ORDER
            KIND_CANCEL -> RowKind.CANCEL
            else -> error("$where has kind $kind")
        }
        val flags = buffer.getShort(FLAGS_AT, LE).toInt()
        row.acknowledged = flags and FLAG_ACKNOWLEDGED != 0
        row.ambiguous = flags and FLAG_AMBIGUOUS != 0
        row.entryTradingDate = buffer.getInt(ENTRY_DATE_AT, LE)
        row.lastReportSeq = buffer.getLong(LAST_REPORT_SEQ_AT, LE)
        row.owed = when (val owed = buffer.getByte(OWED_AT).toInt()) {
            OWED_NONE -> null
            OWED_NEVER_SEQUENCED -> OrderFate.NEVER_SEQUENCED
            OWED_AMBIGUOUS -> OrderFate.AMBIGUOUS
            else -> error("$where owes fate $owed")
        }
        row.side = when (val side = buffer.getByte(SIDE_AT).toInt()) {
            SIDE_BUY -> Side.BUY
            SIDE_SELL -> Side.SELL
            else -> error("$where has side $side")
        }
        row.timeInForce = buffer.getByte(TIME_IN_FORCE_AT)
        row.smpStrategy = buffer.getByte(SMP_STRATEGY_AT)
        row.ordStatus = buffer.getByte(ORD_STATUS_AT)
        row.cancelState = when (val state = buffer.getByte(CANCEL_STATE_AT).toInt()) {
            CANCEL_PARKED -> CancelState.PARKED
            CANCEL_SENT -> CancelState.SENT
            CANCEL_ANSWERED -> CancelState.ANSWERED
            else -> error("$where has cancel state $state")
        }
        row.exchangeOrderId = buffer.getLong(EXCHANGE_ORDER_ID_AT, LE)
        row.securityId = buffer.getInt(SECURITY_ID_AT, LE)
        row.expireDate = buffer.getInt(EXPIRE_DATE_AT, LE)
        row.price = buffer.getLong(PRICE_AT, LE)
        row.origQty = buffer.getLong(ORIG_QTY_AT, LE)
        row.cumQty = buffer.getLong(CUM_QTY_AT, LE)
        row.leavesQty = buffer.getLong(LEAVES_QTY_AT, LE)
        row.notionalHi = buffer.getLong(NOTIONAL_HI_AT, LE)
        row.notionalLo = buffer.getLong(NOTIONAL_LO_AT, LE)
        row.smpId = buffer.getLong(SMP_ID_AT, LE)
        row.origClOrdId = buffer.getLong(ORIG_CLORDID_AT, LE)
        row.shardId = buffer.getInt(SHARD_ID_AT, LE)
        row.fixClOrdId(buffer, FIXED_LENGTH, idLength)
    }

    private fun owedCode(fate: OrderFate?): Int = when (fate) {
        null -> OWED_NONE
        OrderFate.NEVER_SEQUENCED -> OWED_NEVER_SEQUENCED
        OrderFate.AMBIGUOUS -> OWED_AMBIGUOUS
    }

    private fun cancelCode(state: CancelState): Int = when (state) {
        CancelState.PARKED -> CANCEL_PARKED
        CancelState.SENT -> CANCEL_SENT
        CancelState.ANSWERED -> CANCEL_ANSWERED
    }
}
