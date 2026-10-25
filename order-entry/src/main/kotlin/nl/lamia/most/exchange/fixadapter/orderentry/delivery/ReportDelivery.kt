package nl.lamia.most.exchange.fixadapter.orderentry.delivery

import nl.lamia.most.exchange.client.SessionStore
import nl.lamia.most.exchange.fixadapter.orderentry.outbound.Delivery
import nl.lamia.most.exchange.fixadapter.orderentry.store.OrderStore
import nl.lamia.most.exchange.fixadapter.orderentry.store.RequestRow
import org.agrona.collections.Int2ObjectHashMap
import org.agrona.collections.Long2ObjectHashMap
import java.util.function.LongSupplier

/**
 * A `reportSeq` consumed only behind the archive (Design.md §6.2; CLAUDE.md non-negotiable 7).
 *
 * Per shard and participant, the durable first-missing `reportSeq` is the lowest of:
 * - the SDK's own, which [sessionStore]'s `save` hands over: a **ceiling**, never stored as is;
 * - the lowest `reportSeq` whose 35=8 the archive has not reached yet ([sent]). Every report below
 *   the ceiling was either delivered here, and so has a position, or stood in for by a completed
 *   mass status, which has none to wait for;
 * - the lowest still unconfirmed when the library last reconnected: a position is compared only with
 *   the counter of the connection it was written on, so those never are, until a restart.
 *
 * It is stored when it moves, after the row updates of the reports it passes, which were written as
 * each report was applied (most-exchange Adapters.md §5; the WAL's prefix keeps them).
 *
 * Rows changed by a 35=8 or 35=9 with no `reportSeq` are written once the archive has it
 * ([writeWhenArchived]).
 *
 * [archived] is the archive's recorded position of the library's outbound stream on the current
 * connection, negative when unknown. Positions taken on one connection rise in the order they were
 * taken. Single-threaded, the order-entry library's thread; allocation-free once its rings have grown.
 */
class ReportDelivery(
    private val store: OrderStore,
    private val archived: LongSupplier,
) : Delivery {

    /** One shard's and participant's `reportSeq`s on their way to the archive. */
    private class Track(val shardId: Int, val participantId: Long, var durable: Long) {
        var ceiling = durable

        /** The lowest `reportSeq` still unconfirmed at a library reconnect; [NONE] if none. */
        var frozen = NONE

        // Reports taken, in position order: (reportSeq, position), from head to tail.
        var seqs = LongArray(INITIAL)
        var positions = LongArray(INITIAL)
        var head = 0L
        var tail = 0L

        // Indices into the ring whose reportSeqs rise from head to tail: the lowest pending is first.
        var lows = LongArray(INITIAL)
        var lowHead = 0L
        var lowTail = 0L

        fun add(seq: Long, position: Long) {
            if (tail - head == seqs.size.toLong()) grow()
            val mask = seqs.size - 1
            while (lowTail > lowHead && seqs[(lows[((lowTail - 1).toInt()) and mask].toInt()) and mask] >= seq) lowTail--
            seqs[tail.toInt() and mask] = seq
            positions[tail.toInt() and mask] = position
            lows[lowTail.toInt() and mask] = tail
            lowTail++
            tail++
        }

        /** Drops what the archive has; true if anything was. */
        fun confirm(archived: Long): Boolean {
            val mask = seqs.size - 1
            var any = false
            while (head < tail && positions[head.toInt() and mask] <= archived) {
                if (lowHead < lowTail && lows[lowHead.toInt() and mask] == head) lowHead++
                head++
                any = true
            }
            return any
        }

        fun lowestPending(): Long =
            if (lowHead == lowTail) NONE else seqs[lows[lowHead.toInt() and (seqs.size - 1)].toInt() and (seqs.size - 1)]

        fun freeze() {
            frozen = minOf(frozen, lowestPending())
            head = tail
            lowHead = lowTail
        }

        private fun grow() {
            val size = seqs.size
            val mask = size - 1
            val newSeqs = LongArray(size * 2)
            val newPositions = LongArray(size * 2)
            for (i in 0 until size) {
                newSeqs[i] = seqs[(head + i).toInt() and mask]
                newPositions[i] = positions[(head + i).toInt() and mask]
            }
            val newLows = LongArray(size * 2)
            val lowCount = (lowTail - lowHead).toInt()
            for (i in 0 until lowCount) newLows[i] = lows[(lowHead + i).toInt() and mask] - head
            seqs = newSeqs
            positions = newPositions
            lows = newLows
            tail -= head
            head = 0L
            lowHead = 0L
            lowTail = lowCount.toLong()
        }
    }

    private val tracks = Int2ObjectHashMap<Long2ObjectHashMap<Track>>()
    private val all = ArrayList<Track>()

    // Rows whose write waits for the archive, in position order.
    private var rows = arrayOfNulls<RequestRow>(INITIAL)
    private var rowPositions = LongArray(INITIAL)
    private var rowHead = 0L
    private var rowTail = 0L

    /** Rows not written, because the library reconnected before the archive confirmed their answer. */
    var rowsDropped = 0L
        private set

    /** The SDK's `SessionStore` for one shard's `OrderEntrySession`. */
    fun sessionStore(shardId: Int): SessionStore = object : SessionStore {
        override fun firstMissing(participantId: Long): Long = store.firstMissing(participantId, shardId)

        override fun save(participantId: Long, firstMissing: Long) {
            track(shardId, participantId).ceiling = firstMissing
        }
    }

    /** The durable first-missing `reportSeq` as last stored. */
    fun durable(shardId: Int, participantId: Long): Long = store.firstMissing(participantId, shardId)

    override fun sent(shardId: Int, participantId: Long, reportSeq: Long, position: Long) {
        track(shardId, participantId).add(reportSeq, position)
    }

    override fun writeWhenArchived(row: RequestRow, position: Long) {
        if (rowTail - rowHead == rows.size.toLong()) growRows()
        val mask = rows.size - 1
        rows[rowTail.toInt() and mask] = row
        rowPositions[rowTail.toInt() and mask] = position
        rowTail++
    }

    /**
     * The library connected again: nothing taken on the old connection is ever confirmed (§6.2).
     * Each track keeps its lowest unconfirmed `reportSeq` as a floor until a restart, and the rows
     * waiting stay as stored, to be answered again after it.
     */
    fun onLibraryConnect() {
        for (i in 0 until all.size) all[i].freeze()
        val mask = rows.size - 1
        while (rowHead < rowTail) {
            rows[rowHead.toInt() and mask] = null
            rowHead++
            rowsDropped++
        }
    }

    /** Confirms what the archive has, writes the rows it allows, and stores what moved. */
    fun doWork(): Int {
        val position = archived.asLong
        var work = 0
        if (position >= 0L) {
            val mask = rows.size - 1
            while (rowHead < rowTail && rowPositions[rowHead.toInt() and mask] <= position) {
                val at = rowHead.toInt() and mask
                store.update(rows[at]!!)
                rows[at] = null
                rowHead++
                work++
            }
        }
        for (i in 0 until all.size) {
            val t = all[i]
            if (position >= 0L) t.confirm(position)
            val target = minOf(t.ceiling, t.frozen, t.lowestPending())
            if (target != t.durable) {
                store.saveFirstMissing(t.participantId, t.shardId, target)
                t.durable = target
                work++
            }
        }
        return work
    }

    private fun track(shardId: Int, participantId: Long): Track {
        val byParticipant = tracks.get(shardId) ?: Long2ObjectHashMap<Track>().also { tracks.put(shardId, it) }
        return byParticipant.get(participantId)
            ?: Track(shardId, participantId, store.firstMissing(participantId, shardId)).also {
                byParticipant.put(participantId, it)
                all += it
            }
    }

    private fun growRows() {
        val size = rows.size
        val mask = size - 1
        val newRows = arrayOfNulls<RequestRow>(size * 2)
        val newPositions = LongArray(size * 2)
        for (i in 0 until size) {
            newRows[i] = rows[(rowHead + i).toInt() and mask]
            newPositions[i] = rowPositions[(rowHead + i).toInt() and mask]
        }
        rows = newRows
        rowPositions = newPositions
        rowTail -= rowHead
        rowHead = 0L
    }

    private companion object {
        const val NONE = Long.MAX_VALUE
        const val INITIAL = 1024
    }
}
