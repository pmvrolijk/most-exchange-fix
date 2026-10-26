package nl.lamia.most.exchange.fixadapter.orderentry.outbound

import nl.lamia.most.exchange.client.OrderFate
import nl.lamia.most.exchange.client.ReportAction
import nl.lamia.most.exchange.fixadapter.orderentry.artio.StatusUnknownText
import nl.lamia.most.exchange.fixadapter.orderentry.inbound.ArchivedSends
import nl.lamia.most.exchange.fixadapter.orderentry.inbound.Texts
import nl.lamia.most.exchange.fixadapter.orderentry.store.CancelState
import nl.lamia.most.exchange.fixadapter.orderentry.store.OrderStore
import nl.lamia.most.exchange.fixadapter.orderentry.store.RequestRow
import nl.lamia.most.exchange.fixadapter.orderentry.store.RowKind
import nl.lamia.most.exchange.sbe.ClientExecutionReportDecoder
import nl.lamia.most.exchange.sbe.Enrichment
import nl.lamia.most.exchange.sbe.ExecType
import nl.lamia.most.exchange.sbe.RejectReason
import nl.lamia.most.exchange.sbe.RequestStatus
import org.agrona.collections.Int2ObjectHashMap
import org.agrona.collections.Long2LongHashMap

/**
 * Order entry's outbound path (Design.md §3.3, §3.4, §5.3): what the SDK reports, to the client's
 * 35=8 and 35=9, and to the store.
 *
 * - **A report** is applied only once its 35=8 (or 35=9) was taken; refused, it is aborted and
 *   delivered again, with nothing changed (§6.2).
 * - **Fates, the mass status rule's restatements, refused sends, and parked cancels** are owed on
 *   their rows and worked off in [doWork], so a refusal never undoes a report already taken. A
 *   parked cancel let go is sent by [sends], once the archive has its 35=F (§6.1).
 * - **Behind the archive** (§6.2): a sequenced report's row is written at once, and [Delivery]
 *   holds its `reportSeq` back until the archive has the 35=8. Anything with no `reportSeq` has its
 *   row written only once the archive has it, since a crash before then could otherwise leave a
 *   row that says the client was told, and nothing the exchange would resend.
 *
 * Single-threaded, the order-entry library's thread (§3.5). Allocation-free on the report path.
 */
class OutboundReports(
    private val store: OrderStore,
    private val clients: ClientReports,
    private val sends: ArchivedSends,
    private val delivery: Delivery,
    private val log: (String) -> Unit = {},
) {
    private val execution = OutgoingExecution()
    private val cancelReject = OutgoingCancelReject()
    private val notional = Notional()

    /** Rows owing an action: a fate's answer, a restatement, a parked cancel to send or answer. */
    private val owing = ArrayList<RequestRow>()

    /** Counts each `ORDER_STATUS` applied, so a mass status's completion knows which rows it stated. */
    private var statusClock = 0L

    /** Per shard, per participant: [statusClock] at its last completed mass status. */
    private val lastCompletion = Int2ObjectHashMap<Long2LongHashMap>()

    var lostRows = 0L
        private set
    var lostFills = 0L
        private set
    var unknownEnrichment = 0L
        private set

    /** After the store is loaded: what a previous process left owing. */
    fun recover() {
        store.forEach { row -> if (isOwing(row)) owing += row }
    }

    // ------------------------------------------------------------------ reports, §5.3

    /** The shard whose SDK session is delivering the report in hand: its `reportSeq` is that shard's. */
    private var shardId = 0

    /** A report from [shardId]'s SDK session. */
    fun onReport(shardId: Int, report: ClientExecutionReportDecoder): ReportAction {
        this.shardId = shardId
        val participantId = report.participantId()
        val row = store.find(participantId, report.clOrdId())
        if (row == null) {
            lostRows++
            log("participant $participantId: report for clOrdId ${report.clOrdId()}, which the store does not hold; consumed (Design.md §8.17)")
            return ReportAction.CONSUMED
        }
        if (!clients.canSend(participantId)) return ReportAction.ABORT
        val reportSeq = report.reportSeq()
        val again = reportSeq != 0L && reportSeq <= row.lastReportSeq
        return when (row.kind) {
            RowKind.ORDER -> if (report.execType() == ExecType.ORDER_STATUS) onStatus(row, report) else onOrderReport(row, report, again)
            RowKind.CANCEL -> {
                val order = store.find(participantId, row.origClOrdId) ?: error("cancel ${row.clOrdId} without its order")
                onCancelReport(row, order, report, again)
            }
        }
    }

    private fun onOrderReport(order: RequestRow, report: ClientExecutionReportDecoder, again: Boolean): ReportAction {
        val e = execution
        e.reset(order)
        e.reportSeq = report.reportSeq()
        if (report.exchangeOrderId() != 0L) e.exchangeOrderId = report.exchangeOrderId()
        val known = report.enrichment() != Enrichment.UNKNOWN
        notional.set(order.notionalHi, order.notionalLo)
        var cum = if (known) report.cumQty() else order.cumQty
        val leaves = report.leavesQty()
        when (report.execType()) {
            ExecType.NEW -> {
                e.execType = '0'
                e.ordStatus = '0'
            }
            ExecType.TRADE -> {
                e.execType = 'F'
                e.ordStatus = if (leaves > 0L) '1' else '2'
                e.lastPx = report.price()
                e.lastQty = report.lastQty()
                if (!known) cum = order.cumQty + report.lastQty()
                if (!again) notional.add(report.price(), report.lastQty())
            }
            ExecType.CANCELED -> {
                e.execType = '4'
                e.ordStatus = '4'
                reasonText(e, report.rejectReason())
            }
            ExecType.EXPIRED -> {
                e.execType = 'C'
                e.ordStatus = 'C'
            }
            ExecType.REJECTED -> {
                e.execType = '8'
                e.ordStatus = '8'
                e.ordRejReason = ordRejReason(report.rejectReason())
                e.text(report.rejectReason().name)
            }
            else -> {
                log("participant ${order.participantId}: report of ExecType ${report.execType()} for order ${order.clOrdId}; consumed")
                return ReportAction.CONSUMED
            }
        }
        if (!known) unknownEnrichment++
        e.cumQty = cum
        e.leavesQty = leaves
        e.avgPx = notional.average(cum)
        val position = clients.send(e)
        if (position <= 0L) return ReportAction.ABORT
        if (again) {
            delivered(report, position)
            return ReportAction.CONSUMED
        }

        val firstReport = !order.acknowledged && !RequestRow.isTerminal(order.ordStatus)
        order.ordStatus = e.ordStatus.code.toByte()
        order.cumQty = cum
        order.leavesQty = leaves
        order.notionalHi = notional.hi
        order.notionalLo = notional.lo
        if (e.exchangeOrderId != 0L) {
            order.acknowledged = true
            order.exchangeOrderId = e.exchangeOrderId
        }
        order.lastReportSeq = maxOf(order.lastReportSeq, report.reportSeq())
        order.ambiguous = false
        write(order, report.reportSeq(), position)
        delivered(report, position)
        if (firstReport) owePendingCancel(order)
        return ReportAction.CONSUMED
    }

    /** A status is state, not an event (§5.3): sent only when it changes what the client was told. */
    private fun onStatus(order: RequestRow, report: ClientExecutionReportDecoder): ReportAction {
        val known = report.enrichment() != Enrichment.UNKNOWN
        if (!known) unknownEnrichment++
        val cum = if (known) report.cumQty() else order.cumQty
        val leaves = report.leavesQty()
        val status = if (cum > 0L) '1' else '0'
        val changed = !order.acknowledged || order.ambiguous || cum != order.cumQty || leaves != order.leavesQty ||
            status.code.toByte() != order.ordStatus
        if (changed) {
            val e = execution
            e.reset(order)
            e.exchangeOrderId = report.exchangeOrderId()
            e.execType = 'I'
            e.ordStatus = status
            e.cumQty = cum
            e.leavesQty = leaves
            notional.set(order.notionalHi, order.notionalLo)
            e.avgPx = notional.average(order.cumQty) // the fills known (§5.3)
            if (cum > order.cumQty) {
                var at = e.text.putAscii(0, LOST_FILLS_HEAD)
                at += StatusUnknownText.putDecimal(e.text, at, order.cumQty)
                at += e.text.putAscii(at, " to ")
                at += StatusUnknownText.putDecimal(e.text, at, cum)
                at += e.text.putAscii(at, LOST_FILLS_TAIL)
                e.textLength = at
            }
            val position = clients.send(e)
            if (position <= 0L) return ReportAction.ABORT
            if (cum > order.cumQty) lostFills++
            val firstReport = !order.acknowledged
            order.acknowledged = true
            order.exchangeOrderId = report.exchangeOrderId()
            order.ordStatus = status.code.toByte()
            order.cumQty = cum
            order.leavesQty = leaves
            order.ambiguous = false
            delivery.writeWhenArchived(order, position) // an ORDER_STATUS is outside the sequence
            if (firstReport) owePendingCancel(order)
        }
        order.statedAt = ++statusClock
        return ReportAction.CONSUMED
    }

    private fun onCancelReport(cancel: RequestRow, order: RequestRow, report: ClientExecutionReportDecoder, again: Boolean): ReportAction {
        when (report.execType()) {
            ExecType.CANCELED -> {
                val e = execution
                e.reset(order)
                e.cancel = cancel
                e.reportSeq = report.reportSeq()
                if (report.exchangeOrderId() != 0L) e.exchangeOrderId = report.exchangeOrderId()
                e.execType = '4'
                e.ordStatus = '4'
                val known = report.enrichment() != Enrichment.UNKNOWN
                if (!known) unknownEnrichment++
                e.cumQty = if (known) report.cumQty() else order.cumQty
                e.leavesQty = 0L
                notional.set(order.notionalHi, order.notionalLo)
                e.avgPx = notional.average(e.cumQty)
                val position = clients.send(e)
                if (position <= 0L) return ReportAction.ABORT
                if (again) {
                    delivered(report, position)
                    return ReportAction.CONSUMED
                }
                order.ordStatus = '4'.code.toByte()
                order.cumQty = e.cumQty
                order.leavesQty = 0L
                order.ambiguous = false
                order.lastReportSeq = maxOf(order.lastReportSeq, report.reportSeq())
                write(order, report.reportSeq(), position)
                answered(cancel, order, report.reportSeq(), position)
                delivered(report, position)
            }
            ExecType.REJECTED -> {
                val r = cancelReject
                r.reset(cancel, order)
                val reason = report.rejectReason()
                r.cxlRejReason = when {
                    reason == RejectReason.UNKNOWN_ORDER && RequestRow.isTerminal(order.ordStatus) -> TOO_LATE
                    else -> cxlRejReason(reason)
                }
                if (r.cxlRejReason == UNKNOWN_ORDER) {
                    r.exchangeOrderId = 0L
                    r.ordStatus = '8'
                }
                r.text(reason.name)
                val position = clients.send(r)
                if (position <= 0L) return ReportAction.ABORT
                if (!again) answered(cancel, order, report.reportSeq(), position)
                delivered(report, position)
            }
            else -> log("participant ${cancel.participantId}: report of ExecType ${report.execType()} for cancel ${cancel.clOrdId}; consumed")
        }
        return ReportAction.CONSUMED
    }

    /** A sequenced report's 35=8 is on its way: its `reportSeq` waits for the archive (§6.2). */
    private fun delivered(report: ClientExecutionReportDecoder, position: Long) {
        val reportSeq = report.reportSeq()
        if (reportSeq > 0L) delivery.sent(shardId, report.participantId(), reportSeq, position)
    }

    /**
     * A row changed by a report: written now if the report is sequenced, since the stored `reportSeq`
     * passes it only later (most-exchange Adapters.md §5); else only once the archive has its answer.
     */
    private fun write(row: RequestRow, reportSeq: Long, position: Long) {
        if (reportSeq > 0L) store.update(row) else delivery.writeWhenArchived(row, position)
    }

    // ------------------------------------------------------------------ fates and the mass status, §3.3, §3.4

    /**
     * A request the SDK would not send (§3.3, §6.1): its reject is owed on the row as a
     * `NEVER_SEQUENCED` fate with [text], so it is no longer in flight. The Text is in memory only.
     */
    fun refuse(row: RequestRow, text: String) {
        row.owedText = text
        onSettled(row.participantId, row.clOrdId, OrderFate.NEVER_SEQUENCED)
    }

    /** The SDK settled a request that had no report: its answer is owed on the row (§6.2). */
    fun onSettled(participantId: Long, clOrdId: Long, fate: OrderFate) {
        val row = store.find(participantId, clOrdId)
        if (row == null) {
            lostRows++
            log("participant $participantId: $fate for clOrdId $clOrdId, which the store does not hold (Design.md §8.17)")
            return
        }
        row.owed = fate
        store.update(row)
        owing += row
    }

    /**
     * A mass status completed on [shardId] (§3.4): every acknowledged order of [participantId] on
     * that shard the store holds open, that the status did not state, and whose last report is below
     * [nextSeq], is ambiguous. Its restatement is owed.
     */
    fun onMassStatusComplete(shardId: Int, participantId: Long, status: RequestStatus, nextSeq: Long) {
        val byParticipant = lastCompletion.get(shardId) ?: Long2LongHashMap(NEVER).also { lastCompletion.put(shardId, it) }
        val since = byParticipant.get(participantId).let { if (it == NEVER) 0L else it }
        byParticipant.put(participantId, statusClock)
        if (status != RequestStatus.COMPLETE) return
        store.forEach { row ->
            if (row.participantId != participantId || row.kind != RowKind.ORDER) return@forEach
            if (!row.acknowledged || row.ambiguous || row.owed != null || RequestRow.isTerminal(row.ordStatus)) return@forEach
            if (row.shardId != shardId) return@forEach
            if (row.statedAt > since) return@forEach
            if (row.lastReportSeq >= nextSeq) return@forEach
            row.owed = OrderFate.AMBIGUOUS
            store.update(row)
            owing += row
            log("participant $participantId: order ${row.clOrdId} open in the store, not stated by the mass status: ambiguous (Design.md §3.4)")
        }
    }

    // ------------------------------------------------------------------ the duty cycle

    /** Works off what is owed, in order, as far as the clients and the exchange take it. */
    fun doWork(): Int {
        if (owing.isEmpty()) return 0
        var done = 0
        var i = 0
        while (i < owing.size) {
            val row = owing[i]
            if (act(row)) {
                owing.removeAt(i)
                done++
            } else {
                i++
            }
        }
        return done
    }

    /** One owed action; true when it is done (or nothing is owed any more). */
    private fun act(row: RequestRow): Boolean = when (row.kind) {
        RowKind.ORDER -> {
            val fate = row.owed
            if (fate == null) true else if (!clients.canSend(row.participantId)) false else answerOrderFate(row, fate)
        }
        RowKind.CANCEL -> {
            val order = store.find(row.participantId, row.origClOrdId) ?: error("cancel ${row.clOrdId} without its order")
            val fate = row.owed
            when {
                fate != null -> clients.canSend(row.participantId) && answerCancelFate(row, order, fate)
                row.cancelState != CancelState.PARKED -> true
                else -> actOnParked(row, order)
            }
        }
    }

    private fun answerOrderFate(order: RequestRow, fate: OrderFate): Boolean {
        val e = execution
        e.reset(order)
        when (fate) {
            OrderFate.NEVER_SEQUENCED -> {
                e.execType = '8'
                e.ordStatus = '8'
                e.ordRejReason = OTHER
                e.cumQty = 0L
                e.leavesQty = 0L
                e.text(order.owedText ?: Texts.NOT_SEQUENCED_ORDER)
            }
            OrderFate.AMBIGUOUS -> restateUnknown(e, order)
        }
        val position = clients.send(e)
        if (position <= 0L) return false
        when (fate) {
            OrderFate.NEVER_SEQUENCED -> {
                order.ordStatus = '8'.code.toByte()
                order.leavesQty = 0L
            }
            OrderFate.AMBIGUOUS -> order.ambiguous = true
        }
        order.owed = null
        order.owedText = null
        // Stored still owing until the archive has the answer: a crash before then gives it again.
        delivery.writeWhenArchived(order, position)
        owePendingCancel(order)
        return true
    }

    /** §3.4: exactly what the client was last told, with OrdStatusUnknown=Y. Never a terminal state. */
    private fun restateUnknown(e: OutgoingExecution, order: RequestRow) {
        e.execType = 'I'
        e.ordStatus = if (order.ordStatus.toInt() == 0) 'A' else order.ordStatus.toInt().toChar()
        notional.set(order.notionalHi, order.notionalLo)
        e.avgPx = notional.average(order.cumQty)
        e.statusUnknown = true
        e.textLength = StatusUnknownText.write(e.text, 0, order.cumQty)
    }

    private fun answerCancelFate(cancel: RequestRow, order: RequestRow, fate: OrderFate): Boolean {
        val r = cancelReject
        r.reset(cancel, order)
        r.cxlRejReason = OTHER
        when (fate) {
            OrderFate.NEVER_SEQUENCED -> r.text(cancel.owedText ?: Texts.NOT_SEQUENCED_CANCEL)
            OrderFate.AMBIGUOUS -> {
                r.statusUnknown = true
                r.text(Texts.CANCEL_STATUS_UNKNOWN)
            }
        }
        val position = clients.send(r)
        if (position <= 0L) return false
        cancel.owed = null
        cancel.owedText = null
        answered(cancel, order, 0L, position)
        return true
    }

    /** A parked cancel whose order has had its first report (§3.3): sent, or answered here. */
    private fun actOnParked(cancel: RequestRow, order: RequestRow): Boolean {
        if (cancel.sendPending) return true // queued once, on its way: the exchange answers it
        val r = cancelReject
        when {
            order.ambiguous -> {
                if (!clients.canSend(cancel.participantId)) return false
                r.reset(cancel, order)
                r.cxlRejReason = OTHER
                r.statusUnknown = true
                r.textLength = StatusUnknownText.write(r.text, 0, order.cumQty)
            }
            RequestRow.isTerminal(order.ordStatus) -> {
                if (!clients.canSend(cancel.participantId)) return false
                r.reset(cancel, order)
                r.cxlRejReason = TOO_LATE
                r.text(Texts.TOO_LATE)
            }
            !order.acknowledged -> return true // not yet: queued again by the order's first report
            else -> {
                sends.send(cancel)
                return true
            }
        }
        val position = clients.send(r)
        if (position <= 0L) return false
        answered(cancel, order, 0L, position)
        return true
    }

    /** The cancel's answer was taken at [position]; [reportSeq] 0 for one with no `reportSeq`. */
    private fun answered(cancel: RequestRow, order: RequestRow, reportSeq: Long, position: Long) {
        cancel.cancelState = CancelState.ANSWERED
        cancel.lastReportSeq = maxOf(cancel.lastReportSeq, reportSeq)
        write(cancel, reportSeq, position)
        if (order.pendingCancel === cancel) order.pendingCancel = null
    }

    private fun owePendingCancel(order: RequestRow) {
        val cancel = order.pendingCancel ?: return
        if (cancel.cancelState == CancelState.PARKED && cancel !in owing) owing += cancel
    }

    private fun isOwing(row: RequestRow): Boolean {
        if (row.owed != null) return true
        if (row.kind != RowKind.CANCEL || row.cancelState != CancelState.PARKED) return false
        val order = store.find(row.participantId, row.origClOrdId) ?: return false
        return order.acknowledged || order.ambiguous || RequestRow.isTerminal(order.ordStatus)
    }

    private fun reasonText(e: OutgoingExecution, reason: RejectReason) {
        if (reason != RejectReason.NONE && reason != RejectReason.NULL_VAL) e.text(reason.name)
    }

    private companion object {
        const val NEVER = -1L
        const val LOST_FILLS_HEAD = "fills not reported individually: CumQty "
        const val LOST_FILLS_TAIL = ", prices unknown"

        // CxlRejReason (102).
        const val TOO_LATE = 0
        const val UNKNOWN_ORDER = 1
        const val EXCHANGE_OPTION = 2
        const val OTHER = 99

        /** §5.3: UNKNOWN_SECURITY → 1, MARKET_CLOSED → 2, every other → 99. */
        fun ordRejReason(reason: RejectReason): Int = when (reason) {
            RejectReason.UNKNOWN_SECURITY -> 1
            RejectReason.MARKET_CLOSED -> 2
            else -> OTHER
        }

        /** §5.3: UNKNOWN_ORDER → 1, MARKET_CLOSED → 2, every other → 99. */
        fun cxlRejReason(reason: RejectReason): Int = when (reason) {
            RejectReason.UNKNOWN_ORDER -> UNKNOWN_ORDER
            RejectReason.MARKET_CLOSED -> EXCHANGE_OPTION
            else -> OTHER
        }
    }
}
