package nl.lamia.most.exchange.fixadapter.orderentry.inbound

import nl.lamia.most.exchange.client.SendResult
import nl.lamia.most.exchange.fixadapter.orderentry.store.CancelState
import nl.lamia.most.exchange.fixadapter.orderentry.store.OrderStore
import nl.lamia.most.exchange.fixadapter.orderentry.store.RequestRow
import nl.lamia.most.exchange.fixadapter.orderentry.store.RowKind
import nl.lamia.most.exchange.sbe.SmpStrategy
import java.util.function.LongSupplier

/**
 * Requests on their way to the exchange, each sent only once the archive has recorded its 35=D or
 * 35=F (Design.md §6.1, §8.20; CLAUDE.md non-negotiable 11). The library sees a message before the
 * archive has fsynced it, and the engine does not check `clOrdId`: sent earlier, a request lost to a
 * host crash with its row would be sent again by the client's PossDup resend.
 *
 * - **In the order they came**, from the duty cycle, as far as [recorded] has reached their
 *   [RequestRow.sendAfter]. [recorded] is the `RecordingPos` of the engine's inbound stream, negative
 *   while it is not known: then nothing is sent.
 * - **A back-pressured send** stays first and is tried again next cycle; the requests behind it wait,
 *   so a participant's requests keep their order (non-negotiable 2).
 * - **A refused send** (`NOT_READY`, `NO_GATEWAY`, `UNKNOWN_PARTICIPANT`) is handed to [refuse] with
 *   its Text: the outbound path owes and sends the reject (§3.3).
 *
 * Single-threaded, the order-entry library's thread (§3.5). Allocation-free once its queue has grown.
 */
class ArchivedSends(
    private val store: OrderStore,
    private val venue: OrderVenue,
    private val recorded: LongSupplier,
    private val refuse: (row: RequestRow, text: String) -> Unit,
) {
    private val queue = ArrayDeque<RequestRow>()

    /** Requests waiting for the archive or the exchange. */
    val waiting: Int get() = queue.size

    /**
     * A request to send, its row stored: a new one with [RequestRow.sendAfter] set, or a parked cancel
     * whose order has had its first report (§3.3), at its own 35=F's position, or at once if it was
     * loaded from the store. The caller queues a row once: [RequestRow.sendPending] says it is queued.
     */
    fun send(row: RequestRow) {
        row.sendPending = true
        queue.addLast(row)
    }

    fun doWork(): Int {
        if (queue.isEmpty()) return 0
        val recordedPosition = recorded.asLong
        var done = 0
        while (queue.isNotEmpty()) {
            val row = queue.first()
            // 0: loaded from the store, no position in this process; it goes at once (§6.1).
            if (row.sendAfter != 0L && row.sendAfter > recordedPosition) break
            when (val result = toExchange(row)) {
                SendResult.SENT -> {
                    row.sendPending = false
                    if (row.kind == RowKind.CANCEL && row.cancelState == CancelState.PARKED) {
                        row.cancelState = CancelState.SENT
                        store.update(row)
                    }
                }
                SendResult.BACK_PRESSURED -> break
                SendResult.NOT_READY, SendResult.NO_GATEWAY, SendResult.UNKNOWN_PARTICIPANT -> {
                    row.sendPending = false
                    refuse(row, if (result == SendResult.UNKNOWN_PARTICIPANT) Texts.PARTICIPANT_NOT_SERVED else Texts.EXCHANGE_UNAVAILABLE)
                }
            }
            queue.removeFirst()
            done++
        }
        return done
    }

    private fun toExchange(row: RequestRow): SendResult = when (row.kind) {
        RowKind.ORDER -> venue.newOrder(
            row.participantId, row.clOrdId, row.securityId, row.side, row.price, row.origQty,
            row.expireDate, row.smpId, if (row.smpStrategy.toInt() == 2) SmpStrategy.CANCEL_RESTING else SmpStrategy.CANCEL_AGGRESSOR,
        )
        RowKind.CANCEL -> {
            val order = store.find(row.participantId, row.origClOrdId) ?: error("cancel ${row.clOrdId} without its order")
            venue.cancel(row.participantId, row.clOrdId, order.clOrdId, order.exchangeOrderId, order.securityId, order.side)
        }
    }
}
