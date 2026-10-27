package nl.lamia.most.exchange.fixadapter.orderentry.artio

import nl.lamia.most.exchange.fixadapter.codecs.builder.OrderCancelRejectEncoder
import nl.lamia.most.exchange.fixadapter.orderentry.exchange.DirectoryIndex
import nl.lamia.most.exchange.fixadapter.orderentry.outbound.ClientReports
import nl.lamia.most.exchange.fixadapter.orderentry.outbound.OutgoingCancelReject
import nl.lamia.most.exchange.fixadapter.orderentry.outbound.OutgoingExecution
import org.agrona.concurrent.EpochClock
import org.agrona.concurrent.SystemEpochClock
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
    directory: DirectoryIndex,
    private val sessions: ReportSessions<Session>,
    clock: EpochClock = SystemEpochClock.INSTANCE,
) : ClientReports {
    private val executions = ExecutionReportWriter(directory, clock)
    private val cancelReject = OrderCancelRejectEncoder()
    private val orderId = MutableAsciiBuffer(ByteArray(24))

    override fun canSend(participantId: Long): Boolean = sessions.session(participantId) != null

    override fun send(report: OutgoingExecution): Long {
        val session = sessions.session(report.order.participantId) ?: return REFUSED
        return session.trySend(executions.encode(report, session))
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

    /** OrderID: the `exchangeOrderId`, or `NONE` for 0. Returns its length in [orderId]. */
    private fun orderId(exchangeOrderId: Long): Int =
        if (exchangeOrderId == 0L) orderId.putStringWithoutLengthAscii(0, "NONE") else orderId.putLongAscii(0, exchangeOrderId)

    private companion object {
        const val REFUSED = 0L
        const val CANCEL = '1'
    }
}
