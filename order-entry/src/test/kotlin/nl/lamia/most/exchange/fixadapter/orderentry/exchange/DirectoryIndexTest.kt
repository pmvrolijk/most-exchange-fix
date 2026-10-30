package nl.lamia.most.exchange.fixadapter.orderentry.exchange

import nl.lamia.most.exchange.fixadapter.session.DirectoryBroadcast
import nl.lamia.most.exchange.fixadapter.session.TestSecurity
import nl.lamia.most.exchange.fixadapter.session.TestShard
import nl.lamia.most.exchange.client.DirectoryClient
import nl.lamia.most.exchange.fixadapter.orderentry.inbound.SecurityLookup
import org.agrona.concurrent.UnsafeBuffer
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The adapter's index of the directory (Design.md §3.5, §5.1). */
class DirectoryIndexTest {
    private val directory = DirectoryClient()
    private val index = DirectoryIndex()

    private fun symbol(text: String) = text.toByteArray(Charsets.US_ASCII).let { index.bySymbol(UnsafeBuffer(it), 0, it.size) }
    private fun isin(text: String) = text.toByteArray(Charsets.US_ASCII).let { index.byIsin(UnsafeBuffer(it), 0, it.size) }

    @Test
    fun `before a complete directory the index is not ready and knows no security`() {
        assertFalse(index.update(directory))
        assertFalse(index.isReady)
        assertEquals(SecurityLookup.NONE, symbol("ACME"))
        assertFalse(index.isKnown(42))
        assertEquals(DirectoryIndex.NO_SHARD, index.shardOf(42))
    }

    @Test
    fun `a security is found by Symbol, by ISIN and by id, with its shard`() {
        broadcast(1, ACME, BETA).feed(directory)
        assertTrue(index.update(directory))
        assertTrue(index.isReady)
        assertEquals(42, symbol("ACME"))
        assertEquals(43, symbol("BETA"))
        assertEquals(42, isin("NL0000000042"))
        assertEquals(43, isin("NL0000000043"))
        assertTrue(index.isKnown(42))
        assertEquals(0, index.shardOf(42))
        assertEquals(1, index.shardOf(43))
    }

    @Test
    fun `only the exact bytes match`() {
        broadcast(1, ACME).feed(directory)
        index.update(directory)
        for (near in listOf("ACM", "ACMEX", "acme", "ACME ", "", "NL0000000042")) assertEquals(SecurityLookup.NONE, symbol(near), near)
        assertEquals(SecurityLookup.NONE, isin("ACME"))
        assertFalse(index.isKnown(43))
        assertFalse(index.isKnown(-1))
    }

    @Test
    fun `the index is rebuilt only when the directory's version changes, and then replaces the old one`() {
        broadcast(1, ACME).feed(directory)
        assertTrue(index.update(directory))
        broadcast(1, ACME).feed(directory) // the next cycle of the same universe
        assertFalse(index.update(directory), "same version: not rebuilt")
        broadcast(2, BETA).feed(directory)
        assertTrue(index.update(directory))
        assertEquals(2L, index.version)
        assertEquals(SecurityLookup.NONE, symbol("ACME"), "gone from the universe")
        assertEquals(43, symbol("BETA"))
    }

    @Test
    fun `a truncated broadcast leaves the index as it was`() {
        broadcast(1, ACME).feed(directory)
        index.update(directory)
        var sent = 0
        broadcast(2, BETA).messages { b, o, l -> if (sent++ < 2) directory.onDirectoryMessage(b, o, l); true } // no End
        assertFalse(index.update(directory))
        assertEquals(42, symbol("ACME"))
    }

    @Test
    fun `every one of a thousand securities is found`() {
        val many = (1..1000).map { TestSecurity(it, it % 4, "SYM$it", "NL%010d".format(it)) }
        DirectoryBroadcast(1, (0..3).map(::shard), many).feed(directory)
        index.update(directory)
        for (s in many) {
            assertEquals(s.securityId, symbol(s.symbol))
            assertEquals(s.securityId, isin(s.isin))
            assertEquals(s.shardId, index.shardOf(s.securityId))
        }
        assertEquals(SecurityLookup.NONE, symbol("SYM1001"))
    }

    @Test
    fun `lookups allocate nothing`() {
        broadcast(1, ACME, BETA).feed(directory)
        index.update(directory)
        val acme = UnsafeBuffer("ACME".toByteArray())
        val isinBytes = UnsafeBuffer("NL0000000043".toByteArray())
        val unknown = UnsafeBuffer("ZZZZ".toByteArray())
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        var sum = 0L
        repeat(20_000) { sum += index.bySymbol(acme, 0, 4) + index.byIsin(isinBytes, 0, 12) + index.bySymbol(unknown, 0, 4) + index.shardOf(42) } // warm
        // As InboundOrdersTest: the JVM allocates on this thread now and then by itself (224 bytes
        // once here). A lookup that allocates shows in every window; one of five at 0 is the claim.
        val windows = (0 until 5).map {
            val before = threads.currentThreadAllocatedBytes
            repeat(20_000) { sum += index.bySymbol(acme, 0, 4) + index.byIsin(isinBytes, 0, 12) + index.bySymbol(unknown, 0, 4) + index.shardOf(42) }
            threads.currentThreadAllocatedBytes - before
        }
        assertTrue(0L in windows, "bytes allocated per window of 80,000 lookups: $windows")
        assertTrue(sum != 0L)
    }

    private companion object {
        val ACME = TestSecurity(42, 0, "ACME", "NL0000000042")
        val BETA = TestSecurity(43, 1, "BETA", "NL0000000043")

        fun shard(id: Int) = TestShard(id, "aeron:ipc", 1000 + id, "aeron:ipc", 2000 + id)

        fun broadcast(version: Long, vararg securities: TestSecurity) = DirectoryBroadcast(version, listOf(shard(0), shard(1)), securities.toList())
    }
}
