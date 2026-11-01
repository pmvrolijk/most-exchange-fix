package nl.lamia.most.exchange.fixadapter.orderentry.store

import nl.lamia.most.exchange.client.ClOrdId
import org.agrona.DirectBuffer
import org.agrona.collections.Long2LongHashMap
import org.agrona.collections.Long2ObjectHashMap
import org.agrona.concurrent.UnsafeBuffer
import org.rocksdb.ColumnFamilyDescriptor
import org.rocksdb.ColumnFamilyHandle
import org.rocksdb.ColumnFamilyOptions
import org.rocksdb.DBOptions
import org.rocksdb.RocksDB
import org.rocksdb.WALRecoveryMode
import org.rocksdb.WriteBatch
import org.rocksdb.WriteOptions
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The order store (Design.md §6.1): a row per request in RocksDB, keyed by its participant and its
 * ClOrdID (since schema 8, §3.2), every read served from memory; per participant and shard the
 * durable first-missing `reportSeq` (§6.2); and per participant the highest `sequenceIndex`, the
 * epoch, recovery's floor and the open receive gap (§2, §3.2, §8.17). Keyed by `participantId`, never
 * Artio's session id, which a wipe of Artio's state changes.
 *
 * Single-threaded: the order-entry library's thread owns it (§3.5). After [open], call [load] once,
 * then use it. A lookup, an insert and an update allocate nothing once the maps have grown.
 *
 * **The persistence argument rests on two settings here** (Rationale §4, CLAUDE.md non-negotiable 7):
 * - the WAL is written but **not fsynced** (`WriteOptions.sync` false). A process crash loses
 *   nothing; a host crash loses a tail, which Artio's fsynced archive restates or which errs toward
 *   a resend;
 * - **point-in-time WAL recovery**, so that tail is a suffix: the store comes back as a prefix of
 *   its writes, never with holes. A durable `reportSeq` written after the row updates it covers
 *   then implies them. Both column families are in this one database and share its WAL, so the
 *   prefix spans them. `OrderStoreTest` holds this.
 */
class OrderStore private constructor(
    private val db: RocksDB,
    private val dbOptions: DBOptions,
    private val cfOptions: ColumnFamilyOptions,
    private val handles: List<ColumnFamilyHandle>,
    private val hasher: ClOrdIdHasher,
) : AutoCloseable {

    private val orders = handles[1]
    private val meta = handles[2]
    private val writeOptions = WriteOptions().setSync(false)

    // Direct buffers: RocksDB's ByteBuffer put copies from them without a Java array.
    private val keyBytes = ByteBuffer.allocateDirect(ORDER_KEY_LENGTH)
    private val key = UnsafeBuffer(keyBytes)
    private var valueBytes = ByteBuffer.allocateDirect(INITIAL_VALUE_CAPACITY)
    private var value = UnsafeBuffer(valueBytes)
    private val metaKeyBytes = ByteBuffer.allocateDirect(META_SHARD_KEY_LENGTH)
    private val metaKey = UnsafeBuffer(metaKeyBytes)
    private val metaValueBytes = ByteBuffer.allocateDirect(RECEIVE_GAP_LENGTH)
    private val metaValue = UnsafeBuffer(metaValueBytes)

    /**
     * The adapter's last stop was clean: marked by [markStoppedCleanly] after Artio's engine closed,
     * and cleared by [load], durably, so a crash after this start is seen at the next (Design.md §8.21).
     */
    var stoppedCleanly = false
        private set

    private val participants = Long2ObjectHashMap<ParticipantRows>()
    private val pool = ArrayDeque<RequestRow>()
    private var loaded = false

    /** Rows held, every participant's. */
    var size = 0
        private set

    private class ParticipantRows {
        /** Hash of the ClOrdID's words → the first row of its chain ([RequestRow.nextSameHash]). */
        val byId = Long2ObjectHashMap<RequestRow>()
        var rows = 0

        /** Per shard: each shard's engine numbers the participant's reports on its own. */
        val firstMissing = Long2LongHashMap(1L)
        var highestSequenceIndex = 0

        /** Times Artio's state was found lost for the participant (§3.2): positions compare within one. */
        var epoch = 0

        /**
         * The highest position handled in this epoch: a row held, a row dropped, or a request answered
         * with no row (§8.17).
         */
        var highWater = 0L

        /** The part of [highWater] that no row held shows: stored in `meta`. */
        var floor = 0L

        /** The open receive gap (Design.md §2), [gapFrom] 0 if none. */
        var gapIndex = 0
        var gapFrom = 0
        var gapTo = 0
    }

    /**
     * Drops what retention allows (Design.md §6.1) and loads the rest. [tradingDate] is the adapter's
     * (§3.6), `YYYYMMDD`. Refuses a row it cannot read, and the adapter does not start.
     */
    fun load(tradingDate: Int): Int {
        check(!loaded) { "the order store is loaded once" }
        loaded = true
        val holdsAnything = loadMeta()
        if (!formatMarked) {
            // A store with entries and no mark was written before schema 8: its rows are keyed by the
            // derived clOrdId, and its archive's requests by it too (§6.1, §8.30).
            check(!holdsAnything && !hasRows()) {
                "the order store was written before the exchange's schema 8 (no format mark). The upgrade is a " +
                    "fresh start of the order store and Artio's state, fix.logDir and fix.archiveDir (Design.md §6.1, §8.30)"
            }
            db.put(meta, writeOptions, FORMAT_KEY, intValue(STORE_FORMAT))
            formatMarked = true
        }
        if (stoppedCleanly) {
            WriteOptions().setSync(true).use { synced -> db.delete(meta, synced, CLEAN_STOP_KEY) }
        }
        val drop = WriteBatch()
        val dropped = Long2LongHashMap(0L)
        try {
            db.newIterator(orders).use { rows ->
                rows.seekToFirst()
                while (rows.isValid) {
                    val k = rows.key()
                    check(k.size == ORDER_KEY_LENGTH) { "order store key of ${k.size} bytes" }
                    val keyBuffer = UnsafeBuffer(k)
                    val row = newRow()
                    row.participantId = keyBuffer.getLong(0, ByteOrder.BIG_ENDIAN)
                    val v = rows.value()
                    RowCodec.decode(UnsafeBuffer(v), v.size, row)
                    check(ClOrdId.sameBytes(keyBuffer, Long.SIZE_BYTES, row.fixIdView, 0)) {
                        "order store row of participant ${row.participantId}: its ClOrdID is not its key's"
                    }
                    if (isDroppable(row, tradingDate)) {
                        drop.delete(orders, k)
                        // The floor is of this epoch's positions only (§3.2).
                        val p = rowsOf(row.participantId)
                        if (row.epoch == p.epoch && row.position > dropped.get(row.participantId)) {
                            dropped.put(row.participantId, row.position)
                        }
                        release(row)
                    } else {
                        index(rowsOf(row.participantId), row)
                    }
                    rows.next()
                }
                rows.status()
            }
            // The floor goes in the batch that drops the rows: a crash keeps both or neither (§8.17).
            dropped.forEach { participantId, position ->
                val p = rowsOf(participantId)
                if (position > p.floor) {
                    drop.put(meta, metaKeyOf(META_FLOOR, participantId), longValue(position))
                    p.floor = position
                    p.highWater = maxOf(p.highWater, position)
                }
            }
            if (drop.count() > 0) db.write(writeOptions, drop)
        } finally {
            drop.close()
        }
        forEach { row ->
            if (row.kind == RowKind.CANCEL && row.cancelState != CancelState.ANSWERED) {
                orderOf(row)?.pendingCancel = row
            }
        }
        return size
    }

    private var formatMarked = false

    private fun hasRows(): Boolean = db.newIterator(orders).use { it.seekToFirst(); it.isValid }

    private fun isDroppable(row: RequestRow, tradingDate: Int): Boolean =
        row.entryTradingDate < tradingDate &&
            row.isFinished &&
            row.owed == null &&
            row.lastReportSeq < rowsOf(row.participantId).firstMissing.get(row.shardId.toLong())

    /** Loads `meta`; true if it holds anything besides the clean stop's mark and the format's. */
    private fun loadMeta(): Boolean {
        var holdsAnything = false
        db.newIterator(meta).use { entries ->
            entries.seekToFirst()
            while (entries.isValid) {
                val k = UnsafeBuffer(entries.key())
                val v = UnsafeBuffer(entries.value())
                if (k.getByte(0) == META_CLEAN_STOP) {
                    stoppedCleanly = true
                    entries.next()
                    continue
                }
                if (k.getByte(0) == META_FORMAT) {
                    val format = v.getInt(0, ByteOrder.LITTLE_ENDIAN)
                    check(format == STORE_FORMAT) { "the order store has format $format; this adapter reads format $STORE_FORMAT only" }
                    formatMarked = true
                    entries.next()
                    continue
                }
                holdsAnything = true
                val p = rowsOf(k.getLong(1, ByteOrder.BIG_ENDIAN))
                when (val type = k.getByte(0)) {
                    META_FIRST_MISSING -> {
                        check(k.capacity() == META_SHARD_KEY_LENGTH) { "order store first-missing key of ${k.capacity()} bytes" }
                        p.firstMissing.put(k.getInt(META_KEY_LENGTH, ByteOrder.BIG_ENDIAN).toLong(), v.getLong(0, ByteOrder.LITTLE_ENDIAN))
                    }
                    META_SEQUENCE_INDEX -> p.highestSequenceIndex = v.getInt(0, ByteOrder.LITTLE_ENDIAN)
                    META_EPOCH -> p.epoch = v.getInt(0, ByteOrder.LITTLE_ENDIAN)
                    META_FLOOR -> {
                        p.floor = v.getLong(0, ByteOrder.LITTLE_ENDIAN)
                        p.highWater = maxOf(p.highWater, p.floor)
                    }
                    META_RECEIVE_GAP -> {
                        p.gapIndex = v.getInt(0, ByteOrder.LITTLE_ENDIAN)
                        p.gapFrom = v.getInt(Int.SIZE_BYTES, ByteOrder.LITTLE_ENDIAN)
                        p.gapTo = v.getInt(2 * Int.SIZE_BYTES, ByteOrder.LITTLE_ENDIAN)
                    }
                    else -> error("order store meta entry of type $type")
                }
                entries.next()
            }
            entries.status()
        }
        return holdsAnything
    }

    // ------------------------------------------------------------------ rows

    /** A cleared row to fill and [insert], from the pool. */
    fun newRow(): RequestRow = (pool.removeLastOrNull() ?: RequestRow()).also { it.clear() }

    /** Hands back a row that was not inserted, or that [insert] did not take. */
    fun release(row: RequestRow) {
        check(!row.stored) { "a stored row is not released" }
        pool.addLast(row)
    }

    /**
     * Writes [row] and takes it, if it is [InsertResult.NEW]. Otherwise nothing is written, and the
     * row is still the caller's to [release].
     */
    fun insert(row: RequestRow): InsertResult {
        check(loaded) { "the order store is not loaded" }
        check(!row.stored) { "row already stored" }
        check(row.fixClOrdIdLength > 0) { "a row is keyed by its ClOrdID" }
        val p = rowsOf(row.participantId)
        row.epoch = p.epoch
        val held = findInChain(p, row.word0, row.word1, row.word2)
        if (held != null) {
            return if (held.epoch == row.epoch && held.position == row.position) InsertResult.SAME
            else InsertResult.DUPLICATE_CLORDID
        }
        write(row) // before indexing: a failed write leaves memory as it was
        index(p, row)
        return InsertResult.NEW
    }

    /** Writes a stored row's changed fields through to RocksDB. */
    fun update(row: RequestRow) {
        check(row.stored) { "row not stored" }
        write(row)
    }

    /** By the SDK's id: a settled request's (§3.4). Allocation-free. */
    fun find(participantId: Long, clOrdId: ClOrdId): RequestRow? {
        val p = participants.get(participantId) ?: return null
        return findInChain(p, clOrdId.word0, clOrdId.word1, clOrdId.word2)
    }

    /**
     * By the FIX ClOrdID's bytes, as a message's 11 or 41 holds them. One no row can hold (empty, over
     * 20 bytes, or with a NUL) finds nothing. Allocation-free.
     */
    fun findByFixClOrdId(participantId: Long, buffer: DirectBuffer, offset: Int, length: Int): RequestRow? {
        if (length < 1 || length > ClOrdId.LENGTH) return null
        val p = participants.get(participantId) ?: return null
        var w0 = 0L
        var w1 = 0L
        var w2 = 0L
        for (i in 0 until length) {
            val b = buffer.getByte(offset + i).toLong() and 0xff
            if (b == 0L) return null
            val shifted = b shl ((i and 7) * 8)
            when {
                i < 8 -> w0 = w0 or shifted
                i < 16 -> w1 = w1 or shifted
                else -> w2 = w2 or shifted
            }
        }
        return findInChain(p, w0, w1, w2)
    }

    /** By the `char[20]` at [offset], as a report carries `clOrdId` (§5.3). Allocation-free. */
    fun findByClOrdIdField(participantId: Long, buffer: DirectBuffer, offset: Int): RequestRow? {
        val p = participants.get(participantId) ?: return null
        return findInChain(p, ClOrdId.word0(buffer, offset), ClOrdId.word1(buffer, offset), ClOrdId.word2(buffer, offset))
    }

    /** The order a cancel row names, or null if the store does not hold it. Allocation-free. */
    fun orderOf(cancel: RequestRow): RequestRow? {
        if (cancel.origClOrdIdLength == 0) return null
        val p = participants.get(cancel.participantId) ?: return null
        return findInChain(p, cancel.origWord0, cancel.origWord1, cancel.origWord2)?.takeIf { it.kind == RowKind.ORDER }
    }

    /** The requests to hand back with `restoreInFlight` before the SDK's first `doWork` (§6.1). */
    fun forEachInFlight(action: (RequestRow) -> Unit) = forEach { if (it.isInFlight) action(it) }

    /** Every row held. Allocates an iterator per participant: for startup and operators, not the order path. */
    fun forEach(action: (RequestRow) -> Unit) {
        for (p in participants.values) forEachOf(p, action)
    }

    private inline fun forEachOf(p: ParticipantRows, action: (RequestRow) -> Unit) {
        for (head in p.byId.values) {
            var row: RequestRow? = head
            while (row != null) {
                val next = row.nextSameHash
                action(row)
                row = next
            }
        }
    }

    private fun findInChain(p: ParticipantRows, w0: Long, w1: Long, w2: Long): RequestRow? {
        var row = p.byId.get(hasher.hash(w0, w1, w2))
        while (row != null) {
            if (row.hasId(w0, w1, w2)) return row
            row = row.nextSameHash
        }
        return null
    }

    private fun index(p: ParticipantRows, row: RequestRow) {
        row.hash = hasher.hash(row.word0, row.word1, row.word2)
        row.nextSameHash = p.byId.get(row.hash)
        p.byId.put(row.hash, row)
        p.rows++
        if (row.epoch == p.epoch && row.position > p.highWater) p.highWater = row.position
        row.stored = true
        size++
    }

    private fun write(row: RequestRow) {
        key.putLong(0, row.participantId, ByteOrder.BIG_ENDIAN) // big-endian: rows iterate by participant
        key.putBytes(Long.SIZE_BYTES, row.fixIdView, 0, ClOrdId.LENGTH)
        val needed = RowCodec.encodedLength(row)
        if (needed > valueBytes.capacity()) {
            valueBytes = ByteBuffer.allocateDirect(Integer.highestOneBit(needed) shl 1)
            value = UnsafeBuffer(valueBytes)
        }
        val length = RowCodec.encode(row, value)
        put(orders, keyBytes, ORDER_KEY_LENGTH, valueBytes, length)
    }

    private fun put(family: ColumnFamilyHandle, k: ByteBuffer, keyLength: Int, v: ByteBuffer, valueLength: Int) {
        k.limit(keyLength).position(0)
        v.limit(valueLength).position(0)
        db.put(family, writeOptions, k, v)
    }

    // ------------------------------------------------------------------ meta

    /** The durable first-missing `reportSeq` of [participantId] on [shardId] (§6.2): 1 until saved. */
    fun firstMissing(participantId: Long, shardId: Int): Long = participants.get(participantId)?.firstMissing?.get(shardId.toLong()) ?: 1L

    /**
     * Stores the durable first-missing `reportSeq` of [participantId] on [shardId]. The caller writes
     * it only after the row updates of the reports it passes (§6.1, most-exchange Adapters.md §5): the
     * WAL's prefix then keeps them.
     */
    fun saveFirstMissing(participantId: Long, shardId: Int, firstMissing: Long) {
        metaValue.putLong(0, firstMissing, ByteOrder.LITTLE_ENDIAN)
        metaKey.putByte(0, META_FIRST_MISSING)
        metaKey.putLong(1, participantId, ByteOrder.BIG_ENDIAN)
        metaKey.putInt(META_KEY_LENGTH, shardId, ByteOrder.BIG_ENDIAN)
        put(meta, metaKeyBytes, META_SHARD_KEY_LENGTH, metaValueBytes, Long.SIZE_BYTES)
        rowsOf(participantId).firstMissing.put(shardId.toLong(), firstMissing)
    }

    fun highestSequenceIndex(participantId: Long): Int = participants.get(participantId)?.highestSequenceIndex ?: 0

    /** Raises the stored `sequenceIndex` (§3.2); a lower one is ignored. */
    fun recordSequenceIndex(participantId: Long, sequenceIndex: Int) {
        val p = rowsOf(participantId)
        if (sequenceIndex <= p.highestSequenceIndex) return
        metaValue.putInt(0, sequenceIndex, ByteOrder.LITTLE_ENDIAN)
        putMeta(META_SEQUENCE_INDEX, participantId, Int.SIZE_BYTES)
        p.highestSequenceIndex = sequenceIndex
    }

    /**
     * The guard of §3.2 until Status §3 item 12 step 5: Artio's [sequenceIndex] for the participant's
     * session below the stored one means Artio's state was lost. Step 5 starts a new epoch instead.
     */
    fun requireSequenceIndex(participantId: Long, sequenceIndex: Int) {
        val stored = highestSequenceIndex(participantId)
        check(sequenceIndex >= stored) {
            "participant $participantId: Artio's sequenceIndex $sequenceIndex is below the stored $stored. " +
                "Artio's state was lost (Design.md §3.2). Refusing to start"
        }
    }

    /** The participant's epoch (§3.2): 0 until Artio's state is first found lost. */
    fun epoch(participantId: Long): Int = participants.get(participantId)?.epoch ?: 0

    /**
     * Artio's state was found lost (§3.2): a new epoch, in which the high-water, the floor, the stored
     * `sequenceIndex` and the receive gap start again from Artio's new sequence. Every row is kept, its
     * position of the epoch it was taken in. One write, so a crash keeps all of it or none.
     */
    fun beginEpoch(participantId: Long) {
        val p = rowsOf(participantId)
        WriteBatch().use { batch ->
            batch.put(meta, metaKeyOf(META_EPOCH, participantId), intValue(p.epoch + 1))
            batch.delete(meta, metaKeyOf(META_SEQUENCE_INDEX, participantId))
            batch.delete(meta, metaKeyOf(META_FLOOR, participantId))
            batch.delete(meta, metaKeyOf(META_RECEIVE_GAP, participantId))
            db.write(writeOptions, batch)
        }
        p.epoch++
        p.highestSequenceIndex = 0
        p.floor = 0L
        p.highWater = 0L
        p.gapIndex = 0
        p.gapFrom = 0
        p.gapTo = 0
    }

    /**
     * The highest position handled for the participant in its epoch (Design.md §8.17): a row held, a
     * row retention dropped, or a request answered here with no row. 0 if none.
     */
    fun highWater(participantId: Long): Long = participants.get(participantId)?.highWater ?: 0L

    /**
     * A request answered here with no row, once Artio took the answer (§8.17): raises the stored
     * floor if [position] is above the high-water, so a restart does not replay it.
     */
    fun handled(participantId: Long, position: Long) {
        val p = rowsOf(participantId)
        if (position <= p.highWater) return
        metaValue.putLong(0, position, ByteOrder.LITTLE_ENDIAN)
        putMeta(META_FLOOR, participantId, Long.SIZE_BYTES)
        p.floor = position
        p.highWater = position
    }

    /**
     * The position the startup replay starts at (Design.md §8.17): the high-water + 1, no lower than
     * the stored `sequenceIndex`'s MsgSeqNum 1, and no higher than the participant's lowest row of this
     * epoch whose security is still unresolved. Allocates: for startup.
     */
    fun replayFrom(participantId: Long): Long {
        val p = participants.get(participantId) ?: return 1L
        var from = maxOf(p.highWater + 1L, (p.highestSequenceIndex.toLong() shl 32) or 1L)
        forEachOf(p) { row ->
            if (row.epoch == p.epoch && row.isUnresolved && row.position < from) from = row.position
        }
        return from
    }

    /**
     * Whether anything is stored for the participant: a row, a `sequenceIndex` above 0, a durable
     * first-missing above 1, or a floor. A configured session Artio does not know while this holds
     * means Artio's state was lost (Design.md §3.2).
     */
    fun holdsState(participantId: Long): Boolean {
        val p = participants.get(participantId) ?: return false
        return p.rows > 0 || p.highestSequenceIndex > 0 || p.floor > 0L || p.firstMissing.values.any { it > 1L }
    }

    /** The first MsgSeqNum of the participant's open receive gap (Design.md §2), 0 if none is stored. */
    fun receiveGapFrom(participantId: Long): Int = participants.get(participantId)?.gapFrom ?: 0

    /** Its last MsgSeqNum. */
    fun receiveGapTo(participantId: Long): Int = participants.get(participantId)?.gapTo ?: 0

    /** The `sequenceIndex` its numbers are at. */
    fun receiveGapIndex(participantId: Long): Int = participants.get(participantId)?.gapIndex ?: 0

    /** Stores the participant's open receive gap, replacing the one stored: written when it opens or grows. */
    fun saveReceiveGap(participantId: Long, sequenceIndex: Int, from: Int, to: Int) {
        require(from in 1..to) { "receive gap $from to $to" }
        metaValue.putInt(0, sequenceIndex, ByteOrder.LITTLE_ENDIAN)
        metaValue.putInt(Int.SIZE_BYTES, from, ByteOrder.LITTLE_ENDIAN)
        metaValue.putInt(2 * Int.SIZE_BYTES, to, ByteOrder.LITTLE_ENDIAN)
        putMeta(META_RECEIVE_GAP, participantId, RECEIVE_GAP_LENGTH)
        val p = rowsOf(participantId)
        p.gapIndex = sequenceIndex
        p.gapFrom = from
        p.gapTo = to
    }

    /** Deletes the participant's receive gap: it closed, or a reset dropped it. */
    fun clearReceiveGap(participantId: Long) {
        val p = participants.get(participantId) ?: return
        if (p.gapFrom == 0) return
        metaKey.putByte(0, META_RECEIVE_GAP)
        metaKey.putLong(1, participantId, ByteOrder.BIG_ENDIAN)
        metaKeyBytes.limit(META_KEY_LENGTH).position(0)
        db.delete(meta, writeOptions, metaKeyBytes)
        p.gapIndex = 0
        p.gapFrom = 0
        p.gapTo = 0
    }

    private fun putMeta(type: Byte, participantId: Long, valueLength: Int) {
        metaKey.putByte(0, type)
        metaKey.putLong(1, participantId, ByteOrder.BIG_ENDIAN)
        put(meta, metaKeyBytes, META_KEY_LENGTH, metaValueBytes, valueLength)
    }

    private fun rowsOf(participantId: Long): ParticipantRows =
        participants.get(participantId) ?: ParticipantRows().also { participants.put(participantId, it) }

    private fun metaKeyOf(type: Byte, participantId: Long): ByteArray =
        ByteArray(META_KEY_LENGTH).also { UnsafeBuffer(it).apply { putByte(0, type); putLong(1, participantId, ByteOrder.BIG_ENDIAN) } }

    private fun longValue(value: Long): ByteArray =
        ByteArray(Long.SIZE_BYTES).also { UnsafeBuffer(it).putLong(0, value, ByteOrder.LITTLE_ENDIAN) }

    private fun intValue(value: Int): ByteArray =
        ByteArray(Int.SIZE_BYTES).also { UnsafeBuffer(it).putInt(0, value, ByteOrder.LITTLE_ENDIAN) }

    /** Deletes a row from RocksDB alone, as a host crash loses it: for recovery's tests (§8.17). */
    internal fun loseForTest(participantId: Long, fixClOrdId: String) {
        key.putLong(0, participantId, ByteOrder.BIG_ENDIAN)
        ClOrdId.of(fixClOrdId).writeTo(key, Long.SIZE_BYTES)
        val k = ByteArray(ORDER_KEY_LENGTH)
        key.getBytes(0, k)
        db.delete(orders, writeOptions, k)
    }

    /** Deletes the format mark, as a store from before schema 8 has none (§8.30). */
    internal fun forgetFormatForTest() = db.delete(meta, writeOptions, FORMAT_KEY)

    /** Writes [row] with its version byte replaced: how a row from a later adapter looks to this one. */
    internal fun corruptVersionForTest(row: RequestRow, version: Int) {
        write(row)
        value.putByte(0, version.toByte())
        put(orders, keyBytes, ORDER_KEY_LENGTH, valueBytes, RowCodec.encodedLength(row))
    }

    override fun close() {
        writeOptions.close()
        for (handle in handles) handle.close()
        db.close()
        dbOptions.close()
        cfOptions.close()
    }

    companion object {
        /** The participant, big-endian, then the ClOrdID's 20 bytes (§6.1). */
        private const val ORDER_KEY_LENGTH = Long.SIZE_BYTES + ClOrdId.LENGTH
        private const val META_KEY_LENGTH = 1 + Long.SIZE_BYTES

        /** The first-missing key: the participant's, then its shard. */
        private const val META_SHARD_KEY_LENGTH = META_KEY_LENGTH + Int.SIZE_BYTES
        private const val INITIAL_VALUE_CAPACITY = 512
        private const val META_FIRST_MISSING: Byte = 1
        private const val META_SEQUENCE_INDEX: Byte = 2
        // 3 was an id collision's block, gone with schema 8 (§8.15).
        private const val META_FLOOR: Byte = 4
        private const val META_RECEIVE_GAP: Byte = 6
        private const val META_EPOCH: Byte = 8
        private const val RECEIVE_GAP_LENGTH = 3 * Int.SIZE_BYTES

        /** The node's, not a participant's: its key's participant is 0. */
        private const val META_CLEAN_STOP: Byte = 5
        private val CLEAN_STOP_KEY = ByteArray(META_KEY_LENGTH).also { it[0] = META_CLEAN_STOP }

        /** The node's: which layout the store is in. Format 2 is schema 8's, keyed by ClOrdID (§6.1, §8.30). */
        private const val META_FORMAT: Byte = 7
        private val FORMAT_KEY = ByteArray(META_KEY_LENGTH).also { it[0] = META_FORMAT }
        private const val STORE_FORMAT = 2

        /** Rationale §4: a crash leaves a prefix. Never relax this without rereading that section. */
        internal val WAL_RECOVERY_MODE = WALRecoveryMode.PointInTimeRecovery

        /**
         * Records that the adapter stopped cleanly (Design.md §8.21): called once Artio's engine has
         * closed, when its archive holds every message it sent, and with the store closed. Synced.
         */
        fun markStoppedCleanly(dir: File) {
            open(dir).use { store ->
                WriteOptions().setSync(true).use { synced -> store.db.put(store.meta, synced, CLEAN_STOP_KEY, ByteArray(0)) }
            }
        }

        /** Opens (creating if absent) the store in [dir]. Then [load] it. */
        fun open(dir: File): OrderStore = open(dir, ClOrdIdHasher.DEFAULT)

        internal fun open(dir: File, hasher: ClOrdIdHasher): OrderStore {
            RocksDB.loadLibrary()
            require(dir.isDirectory || dir.mkdirs()) { "cannot create the order store directory $dir" }
            val cfOptions = ColumnFamilyOptions()
            val dbOptions = DBOptions()
                .setCreateIfMissing(true)
                .setCreateMissingColumnFamilies(true)
                .setWalRecoveryMode(WAL_RECOVERY_MODE)
            val descriptors = listOf(
                ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, cfOptions),
                ColumnFamilyDescriptor("orders".toByteArray(), cfOptions),
                ColumnFamilyDescriptor("meta".toByteArray(), cfOptions),
            )
            val handles = ArrayList<ColumnFamilyHandle>(descriptors.size)
            val db = try {
                RocksDB.open(dbOptions, dir.path, descriptors, handles)
            } catch (e: Exception) {
                dbOptions.close()
                cfOptions.close()
                throw e
            }
            return OrderStore(db, dbOptions, cfOptions, handles, hasher)
        }
    }
}
