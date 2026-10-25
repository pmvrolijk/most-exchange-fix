package nl.lamia.most.exchange.fixadapter.orderentry.artio

import nl.lamia.most.exchange.fixadapter.codecs.builder.ExecutionReportEncoder
import nl.lamia.most.exchange.fixadapter.codecs.builder.OrderCancelRejectEncoder
import nl.lamia.most.exchange.fixadapter.orderentry.exchange.DirectoryIndex
import nl.lamia.most.exchange.fixadapter.orderentry.outbound.ClientReports
import nl.lamia.most.exchange.fixadapter.orderentry.outbound.OutgoingCancelReject
import nl.lamia.most.exchange.fixadapter.orderentry.outbound.OutgoingExecution
import nl.lamia.most.exchange.sbe.Side
import org.agrona.concurrent.EpochClock
import org.agrona.concurrent.SystemEpochClock
import org.agrona.concurrent.UnsafeBuffer
import uk.co.real_logic.artio.fields.UtcTimestampEncoder
import uk.co.real_logic.artio.session.Session
import uk.co.real_logic.artio.util.MutableAsciiBuffer

/**
 * The outbound path's 35=8 and 35=9 (Design.md §5.3) on each participant's Artio [Session], numbered
 * by it, online or offline ([ReportSessions], §6.3). Each send returns the position Artio wrote it
 * at, in the library's outbound stream (§6.2).
 *
 * Encoders are reset before each use (CLAUDE.md non-negotiable 10). Allocation-free.
 */
class ArtioReports(
    private val directory: DirectoryIndex,
    private val sessions: ReportSessions<Session>,
    private val clock: EpochClock = SystemEpochClock.INSTANCE,
) : ClientReports {
    private val execution = ExecutionReportEncoder()
    private val cancelReject = OrderCancelRejectEncoder()

    private val execId = MutableAsciiBuffer(ByteArray(32))
    private val orderId = MutableAsciiBuffer(ByteArray(24))
    private val securityId = MutableAsciiBuffer(ByteArray(16))
    private val expireDate = MutableAsciiBuffer(ByteArray(8))
    private val smpId = MutableAsciiBuffer(ByteArray(24))
    private val symbol = UnsafeBuffer(ByteArray(0))
    private val transactTime = UtcTimestampEncoder()

    override fun canSend(participantId: Long): Boolean = sessions.session(participantId) != null

    override fun send(report: OutgoingExecution): Long {
        val session = sessions.session(report.order.participantId) ?: return REFUSED
        val order = report.order
        val e = execution
        e.reset()
        orderId(report.exchangeOrderId).let { e.orderID(orderId, 0, it) }
        e.execID(execId, 0, execId(session, report.reportSeq))
        e.execType(report.execType).ordStatus(report.ordStatus)
        val cancel = report.cancel
        if (cancel != null) {
            e.clOrdID(cancel.fixIdView, 0, cancel.fixClOrdIdLength)
            e.origClOrdID(order.fixIdView, 0, order.fixClOrdIdLength)
        } else {
            e.clOrdID(order.fixIdView, 0, order.fixClOrdIdLength)
        }
        e.side(if (order.side == Side.SELL) '2' else '1')
        instrument(e, order.securityId)
        e.orderQtyData().orderQty(order.origQty, SCALE)
        e.price(order.price, SCALE)
        val timeInForce = order.timeInForce.toInt().toChar()
        e.timeInForce(timeInForce)
        if (timeInForce == GTD) e.expireDate(expireDate, 0, expireDate.putIntAscii(0, order.expireDate))
        if (order.smpId != 0L) e.selfMatchPreventionID(smpId, 0, smpId.putLongAscii(0, order.smpId))
        if (order.smpStrategy.toInt() != 0) e.selfMatchPreventionInstruction(order.smpStrategy.toInt())
        if (report.execType == FILL) e.lastPx(report.lastPx, SCALE).lastQty(report.lastQty, SCALE)
        e.cumQty(report.cumQty, SCALE).leavesQty(report.leavesQty, SCALE).avgPx(report.avgPx, SCALE)
        if (report.ordRejReason != OutgoingExecution.NO_REASON) e.ordRejReason(report.ordRejReason)
        if (report.textLength > 0) e.text(report.text, 0, report.textLength)
        if (report.statusUnknown) e.ordStatusUnknown(true)
        e.transactTime(transactTime.buffer(), transactTime.encode(clock.time()))
        return session.trySend(e)
    }

    override fun send(reject: OutgoingCancelReject): Long {
        val session = sessions.session(reject.order.participantId) ?: return REFUSED
        val c = cancelReject
        c.reset()
        orderId(reject.exchangeOrderId).let { c.orderID(orderId, 0, it) }
        c.clOrdID(reject.cancel.fixIdView, 0, reject.cancel.fixClOrdIdLength)
        c.origClOrdID(reject.order.fixIdView, 0, reject.order.fixClOrdIdLength)
        c.ordStatus(reject.ordStatus).cxlRejResponseTo(CANCEL).cxlRejReason(reject.cxlRejReason)
        if (reject.textLength > 0) c.text(reject.text, 0, reject.textLength)
        if (reject.statusUnknown) c.ordStatusUnknown(true)
        return session.trySend(c)
    }

    /** 55 the directory's Symbol, 48 the `securityId` with 22=8 (§5.3). */
    private fun instrument(e: ExecutionReportEncoder, id: Int) {
        val i = e.instrument()
        directory.symbolOf(id)?.let {
            symbol.wrap(it)
            i.symbol(symbol, 0, it.size)
        }
        i.securityID(securityId, 0, securityId.putIntAscii(0, id))
        i.securityIDSource(SOURCE_EXCHANGE, 0, 1)
    }

    /** OrderID: the `exchangeOrderId`, or `NONE` for 0. Returns its length in [orderId]. */
    private fun orderId(exchangeOrderId: Long): Int =
        if (exchangeOrderId == 0L) orderId.putAscii(0, "NONE") else orderId.putLongAscii(0, exchangeOrderId)

    /** A sequenced report's `reportSeq`; else `A<sequenceIndex>.<MsgSeqNum>` of this message (§5.3). */
    private fun execId(session: Session, reportSeq: Long): Int {
        if (reportSeq > 0L) return execId.putLongAscii(0, reportSeq)
        var at = 0
        execId.putByte(at++, 'A'.code.toByte())
        at += execId.putIntAscii(at, session.sequenceIndex())
        execId.putByte(at++, '.'.code.toByte())
        at += execId.putIntAscii(at, session.lastSentMsgSeqNum() + 1)
        return at
    }

    private companion object {
        const val REFUSED = 0L
        const val SCALE = 8
        const val GTD = '6'
        const val FILL = 'F'
        const val CANCEL = '1'
        val SOURCE_EXCHANGE = UnsafeBuffer(byteArrayOf('8'.code.toByte()))
    }
}
