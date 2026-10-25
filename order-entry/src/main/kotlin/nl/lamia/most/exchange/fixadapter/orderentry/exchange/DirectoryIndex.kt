package nl.lamia.most.exchange.fixadapter.orderentry.exchange

import nl.lamia.most.exchange.client.DirectoryClient
import nl.lamia.most.exchange.client.RoutedSecurity
import nl.lamia.most.exchange.fixadapter.orderentry.inbound.SecurityLookup
import org.agrona.DirectBuffer
import org.agrona.collections.Int2IntHashMap
import org.agrona.collections.Int2ObjectHashMap

/**
 * The directory as order entry reads it (Design.md §3.5, §5.1): a security by its id, ISIN or
 * Symbol, and the shard that hosts it. The SDK's `DirectoryClient` keys its tables by `String`, so
 * every lookup would allocate one. This index is rebuilt from it only when its version changes,
 * which allocates, and is read without allocating.
 *
 * Single-threaded: the order-entry library's thread (§3.5).
 */
class DirectoryIndex : SecurityLookup {
    private var ready = false

    /** The directory version this index was built from; meaningful once [isReady]. */
    var version = 0L
        private set

    private var shardBySecurity = Int2IntHashMap(NO_SHARD)

    /** Every Symbol ever listed, by `securityId`: a report echoes it after the security has left. */
    private val symbols = Int2ObjectHashMap<ByteArray>()
    private var bySymbol = BytesTable.EMPTY
    private var byIsin = BytesTable.EMPTY

    override val isReady: Boolean get() = ready

    /** Rebuilds from [directory] if it holds a complete directory of another version. True if it did. */
    fun update(directory: DirectoryClient): Boolean {
        if (!directory.isReady || (ready && directory.version == version)) return false
        rebuild(directory.securities)
        version = directory.version
        ready = true
        return true
    }

    /** The shard hosting [securityId], or [NO_SHARD]. */
    fun shardOf(securityId: Int): Int = shardBySecurity.get(securityId)

    /** [securityId]'s Symbol as ASCII bytes, or null if no directory listed it. */
    fun symbolOf(securityId: Int): ByteArray? = symbols.get(securityId)

    override fun isKnown(securityId: Int): Boolean = shardBySecurity.get(securityId) != NO_SHARD

    override fun byIsin(buffer: DirectBuffer, offset: Int, length: Int): Int = byIsin.get(buffer, offset, length)

    override fun bySymbol(buffer: DirectBuffer, offset: Int, length: Int): Int = bySymbol.get(buffer, offset, length)

    private fun rebuild(securities: Collection<RoutedSecurity>) {
        val shards = Int2IntHashMap(securities.size * 2, org.agrona.collections.Hashing.DEFAULT_LOAD_FACTOR, NO_SHARD)
        for (s in securities) {
            shards.put(s.securityId, s.shardId)
            symbols.put(s.securityId, s.symbol.toByteArray(Charsets.US_ASCII))
        }
        shardBySecurity = shards
        bySymbol = BytesTable.of(securities.map { it.symbol to it.securityId })
        byIsin = BytesTable.of(securities.filter { it.isin.isNotEmpty() }.map { it.isin to it.securityId })
    }

    companion object {
        const val NO_SHARD = -1
    }
}

/**
 * ASCII keys to an `int`, open addressing, built once and then only read: a lookup hashes and
 * compares the caller's bytes in place.
 */
internal class BytesTable private constructor(private val keys: Array<ByteArray?>, private val values: IntArray) {
    private val mask = keys.size - 1

    fun get(buffer: DirectBuffer, offset: Int, length: Int): Int {
        if (keys.isEmpty()) return SecurityLookup.NONE
        var slot = hash(buffer, offset, length) and mask
        while (true) {
            val key = keys[slot] ?: return SecurityLookup.NONE
            if (matches(key, buffer, offset, length)) return values[slot]
            slot = (slot + 1) and mask
        }
    }

    private fun matches(key: ByteArray, buffer: DirectBuffer, offset: Int, length: Int): Boolean {
        if (key.size != length) return false
        for (i in 0 until length) if (key[i] != buffer.getByte(offset + i)) return false
        return true
    }

    companion object {
        val EMPTY = BytesTable(emptyArray(), IntArray(0))

        fun of(entries: List<Pair<String, Int>>): BytesTable {
            if (entries.isEmpty()) return EMPTY
            var capacity = 2
            while (capacity < entries.size * 2) capacity = capacity shl 1
            val keys = arrayOfNulls<ByteArray>(capacity)
            val values = IntArray(capacity)
            for ((text, value) in entries) {
                val key = text.toByteArray(Charsets.US_ASCII)
                var slot = hash(key) and (capacity - 1)
                while (keys[slot] != null) {
                    require(!keys[slot].contentEquals(key)) { "the directory lists $text twice" }
                    slot = (slot + 1) and (capacity - 1)
                }
                keys[slot] = key
                values[slot] = value
            }
            return BytesTable(keys, values)
        }

        // FNV-1a, then a final mix so nearby keys spread across the table.
        private fun hash(key: ByteArray): Int {
            var h = FNV_OFFSET
            for (b in key) h = (h xor (b.toInt() and 0xff)) * FNV_PRIME
            return mix(h)
        }

        private fun hash(buffer: DirectBuffer, offset: Int, length: Int): Int {
            var h = FNV_OFFSET
            for (i in 0 until length) h = (h xor (buffer.getByte(offset + i).toInt() and 0xff)) * FNV_PRIME
            return mix(h)
        }

        private fun mix(h: Int): Int {
            var x = h
            x = x xor (x ushr 16)
            x *= -0x7a143595
            x = x xor (x ushr 13)
            return x
        }

        private const val FNV_OFFSET = -0x7ee3623b // 0x811c9dc5
        private const val FNV_PRIME = 0x01000193
    }
}
