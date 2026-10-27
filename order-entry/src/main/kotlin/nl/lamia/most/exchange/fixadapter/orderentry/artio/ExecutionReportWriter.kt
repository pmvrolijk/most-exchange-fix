package nl.lamia.most.exchange.fixadapter.orderentry.artio

import nl.lamia.most.exchange.fixadapter.codecs.builder.ExecutionReportEncoder
import nl.lamia.most.exchange.fixadapter.orderentry.exchange.DirectoryIndex
import nl.lamia.most.exchange.fixadapter.orderentry.outbound.OutgoingExecution
import nl.lamia.most.exchange.sbe.Side
import org.agrona.concurrent.EpochClock
import org.agrona.concurrent.UnsafeBuffer
import uk.co.real_logic.artio.fields.UtcTimestampEncoder
import uk.co.real_logic.artio.session.Session
import uk.co.real_logic.artio.util.MutableAsciiBuffer

/**
 * A 35=8 from an [OutgoingExecution] and its order's row (Design.md §5.3), for the session that will
 * number it: the outbound path's reports and fates, and the inbound path's answers to 35=H and 35=AF
 * (§3.3). One encoder, reset before each use (CLAUDE.md non-negotiable 10). Allocation-free.
 */
internal class ExecutionReportWriter(private val directory: DirectoryIndex, private val clock: EpochClock) {
    private val execution = ExecutionReportEncoder()
    private val execId = MutableAsciiBuffer(ByteArray(32))
    private val orderId = MutableAsciiBuffer(ByteArray(24))
    private val securityId = MutableAsciiBuffer(ByteArray(16))
    private val expireDate = MutableAsciiBuffer(ByteArray(8))
    private val smpId = MutableAsciiBuffer(ByteArray(24))
    private val symbol = UnsafeBuffer(ByteArray(0))
    private val transactTime = UtcTimestampEncoder()

    /** [report] encoded for [session], whose next MsgSeqNum an adapter's own ExecID names. */
    fun encode(report: OutgoingExecution, session: Session): ExecutionReportEncoder {
        val order = report.order
        val e = execution
        e.reset()
        e.orderID(orderId, 0, orderId(report.exchangeOrderId))
        e.execID(execId, 0, execId(session, report.reportSeq))
        e.execType(report.execType).ordStatus(report.ordStatus)
        val cancel = report.cancel
        if (cancel != null) {
            e.clOrdID(cancel.fixIdView, 0, cancel.fixClOrdIdLength)
            e.origClOrdID(order.fixIdView, 0, order.fixClOrdIdLength)
        } else {
            e.clOrdID(order.fixIdView, 0, order.fixClOrdIdLength)
        }
        if (report.ordStatusReqIdLength > 0) e.ordStatusReqID(report.ordStatusReqId, 0, report.ordStatusReqIdLength)
        if (report.massStatusReqIdLength > 0) {
            e.massStatusReqID(report.massStatusReqId, 0, report.massStatusReqIdLength)
            e.totNumReports(report.totNumReports).lastRptRequested(report.lastRptRequested)
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
        return e
    }

    /**
     * 55 the directory's Symbol, 48 the `securityId` with 22=8 (§5.3). None for a recovered order
     * the directory does not know (§8.17): there is no `securityId` to state.
     */
    private fun instrument(e: ExecutionReportEncoder, id: Int) {
        if (id < 0) return
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
        if (exchangeOrderId == 0L) orderId.putStringWithoutLengthAscii(0, "NONE") else orderId.putLongAscii(0, exchangeOrderId)

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
        const val SCALE = 8
        const val GTD = '6'
        const val FILL = 'F'
        val SOURCE_EXCHANGE = UnsafeBuffer(byteArrayOf('8'.code.toByte()))
    }
}
