package nl.lamia.most.exchange.fixadapter.orderentry.store

import org.agrona.DirectBuffer
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
 * The order store (Design.md §6.1): a row per request in RocksDB, every read served from memory,
 * and per participant the durable first-missing `reportSeq` (§6.2) and the highest `sequenceIndex`
 * (§3.2). Keyed by `participantId`, never Artio's session id, which a wipe of Artio's state changes.
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
    private val metaKeyBytes = ByteBuffer.allocateDirect(META_KEY_LENGTH)
    private val metaKey = UnsafeBuffer(metaKeyBytes)
    private val metaValueBytes = ByteBuffer.allocateDirect(Long.SIZE_BYTES)
    private val metaValue = UnsafeBuffer(metaValueBytes)

    private val participants = Long2ObjectHashMap<ParticipantRows>()
    private val pool = ArrayDeque<RequestRow>()
    private var loaded = false

    /** Rows held, every participant's. */
    var size = 0
        private set

    private class ParticipantRows {
        val byClOrdId = Long2ObjectHashMap<RequestRow>()

        /** Hash of the FIX ClOrdID → the first row of its chain ([RequestRow.nextSameHash]). */
        val byFixHash = Long2ObjectHashMap<RequestRow>()
        var firstMissing = 1L
        var highestSequenceIndex = 0
    }

    /**
     * Drops what retention allows (Design.md §6.1) and loads the rest. [tradingDate] is the adapter's
     * (§3.6), `YYYYMMDD`. Refuses a row it cannot read, and the adapter does not start.
     */
    fun load(tradingDate: Int): Int {
        check(!loaded) { "the order store is loaded once" }
        loaded = true
        loadMeta()
        val drop = WriteBatch()
        try {
            db.newIterator(orders).use { rows ->
                rows.seekToFirst()
                while (rows.isValid) {
                    val k = rows.key()
                    check(k.size == ORDER_KEY_LENGTH) { "order store key of ${k.size} bytes" }
                    val keyBuffer = UnsafeBuffer(k)
                    val row = newRow()
                    row.participantId = keyBuffer.getLong(0, ByteOrder.BIG_ENDIAN)
                    row.clOrdId = keyBuffer.getLong(Long.SIZE_BYTES, ByteOrder.BIG_ENDIAN)
                    val v = rows.value()
                    RowCodec.decode(UnsafeBuffer(v), v.size, row)
                    if (isDroppable(row, tradingDate)) {
                        drop.delete(orders, k)
                        release(row)
                    } else {
                        index(rowsOf(row.participantId), row)
                    }
                    rows.next()
                }
                rows.status()
            }
            if (drop.count() > 0) db.write(writeOptions, drop)
        } finally {
            drop.close()
        }
        return size
    }

    private fun isDroppable(row: RequestRow, tradingDate: Int): Boolean =
        row.entryTradingDate < tradingDate &&
            row.isFinished &&
            row.owed == null &&
            row.lastReportSeq < rowsOf(row.participantId).firstMissing

    private fun loadMeta() {
        db.newIterator(meta).use { entries ->
            entries.seekToFirst()
            while (entries.isValid) {
                val k = UnsafeBuffer(entries.key())
                val v = UnsafeBuffer(entries.value())
                val p = rowsOf(k.getLong(1, ByteOrder.BIG_ENDIAN))
                when (val type = k.getByte(0)) {
                    META_FIRST_MISSING -> p.firstMissing = v.getLong(0, ByteOrder.LITTLE_ENDIAN)
                    META_SEQUENCE_INDEX -> p.highestSequenceIndex = v.getInt(0, ByteOrder.LITTLE_ENDIAN)
                    else -> error("order store meta entry of type $type")
                }
                entries.next()
            }
            entries.status()
        }
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
        val p = rowsOf(row.participantId)
        val held = p.byClOrdId.get(row.clOrdId)
        if (held != null) {
            return if (held.fixClOrdIdEquals(row.fixIdView, 0, row.fixClOrdIdLength)) InsertResult.SAME
            else InsertResult.ID_COLLISION
        }
        if (findInChain(p, hashOf(row), row.fixIdView, 0, row.fixClOrdIdLength) != null) {
            return InsertResult.DUPLICATE_CLORDID
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

    fun find(participantId: Long, clOrdId: Long): RequestRow? = participants.get(participantId)?.byClOrdId?.get(clOrdId)

    /** By the FIX ClOrdID's bytes, as the message holds them. Allocation-free. */
    fun findByFixClOrdId(participantId: Long, buffer: DirectBuffer, offset: Int, length: Int): RequestRow? {
        val p = participants.get(participantId) ?: return null
        return findInChain(p, hasher.hash(buffer, offset, length), buffer, offset, length)
    }

    /** The requests to hand back with `restoreInFlight` before the SDK's first `doWork` (§6.1). */
    fun forEachInFlight(action: (RequestRow) -> Unit) = forEach { if (it.isInFlight) action(it) }

    /** Every row held. Allocates an iterator per participant: for startup and operators, not the order path. */
    fun forEach(action: (RequestRow) -> Unit) {
        for (p in participants.values) {
            for (row in p.byClOrdId.values) action(row)
        }
    }

    private fun hashOf(row: RequestRow): Long = hasher.hash(row.fixIdView, 0, row.fixClOrdIdLength)

    private fun findInChain(p: ParticipantRows, hash: Long, buffer: DirectBuffer, offset: Int, length: Int): RequestRow? {
        var row = p.byFixHash.get(hash)
        while (row != null) {
            if (row.fixClOrdIdEquals(buffer, offset, length)) return row
            row = row.nextSameHash
        }
        return null
    }

    private fun index(p: ParticipantRows, row: RequestRow) {
        row.hash = hashOf(row)
        row.nextSameHash = p.byFixHash.get(row.hash)
        p.byFixHash.put(row.hash, row)
        p.byClOrdId.put(row.clOrdId, row)
        row.stored = true
        size++
    }

    private fun write(row: RequestRow) {
        key.putLong(0, row.participantId, ByteOrder.BIG_ENDIAN) // big-endian: rows iterate by participant
        key.putLong(Long.SIZE_BYTES, row.clOrdId, ByteOrder.BIG_ENDIAN)
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

    /** The durable first-missing `reportSeq` (§6.2): 1 until saved. */
    fun firstMissing(participantId: Long): Long = participants.get(participantId)?.firstMissing ?: 1L

    /**
     * Stores the durable first-missing `reportSeq`. The caller writes it only after the row updates
     * of the reports it passes (§6.1, most-exchange Adapters.md §5): the WAL's prefix then keeps them.
     */
    fun saveFirstMissing(participantId: Long, firstMissing: Long) {
        metaValue.putLong(0, firstMissing, ByteOrder.LITTLE_ENDIAN)
        putMeta(META_FIRST_MISSING, participantId, Long.SIZE_BYTES)
        rowsOf(participantId).firstMissing = firstMissing
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
     * The derived-id guard (Design.md §3.2): Artio's [sequenceIndex] for the participant's session
     * below the stored one means Artio's state was wiped, and derived `clOrdId`s would repeat.
     */
    fun requireSequenceIndex(participantId: Long, sequenceIndex: Int) {
        val stored = highestSequenceIndex(participantId)
        check(sequenceIndex >= stored) {
            "participant $participantId: Artio's sequenceIndex $sequenceIndex is below the stored $stored. " +
                "Artio's state was lost, and derived clOrdIds would repeat (Design.md §3.2). Refusing to start"
        }
    }

    private fun putMeta(type: Byte, participantId: Long, valueLength: Int) {
        metaKey.putByte(0, type)
        metaKey.putLong(1, participantId, ByteOrder.BIG_ENDIAN)
        put(meta, metaKeyBytes, META_KEY_LENGTH, metaValueBytes, valueLength)
    }

    private fun rowsOf(participantId: Long): ParticipantRows =
        participants.get(participantId) ?: ParticipantRows().also { participants.put(participantId, it) }

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
        private const val ORDER_KEY_LENGTH = 2 * Long.SIZE_BYTES
        private const val META_KEY_LENGTH = 1 + Long.SIZE_BYTES
        private const val INITIAL_VALUE_CAPACITY = 512
        private const val META_FIRST_MISSING: Byte = 1
        private const val META_SEQUENCE_INDEX: Byte = 2

        /** Rationale §4: a crash leaves a prefix. Never relax this without rereading that section. */
        internal val WAL_RECOVERY_MODE = WALRecoveryMode.PointInTimeRecovery

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
