package nl.lamia.most.exchange.fixadapter.orderentry

import nl.lamia.most.exchange.client.GatewayEndpoint
import nl.lamia.most.exchange.client.ShardRoute
import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Order entry's keys in the node file (Design.md §2, "Configuration and logon"; §3.5). */
class OrderEntryConfigTest {

    private fun properties(vararg entries: Pair<String, String>) = Properties().apply {
        setProperty("fix.storeDir", "/var/most-fix/store")
        setProperty("fix.directoryChannel", "aeron:udp?endpoint=239.10.0.1:40000")
        setProperty("fix.directoryStreamId", "100")
        for ((k, v) in entries) if (v.isEmpty()) remove(k) else setProperty(k, v)
    }

    private fun refused(vararg entries: Pair<String, String>): String =
        assertFailsWith<IllegalArgumentException> { OrderEntryConfig.parse(properties(*entries)) }.message.orEmpty()

    private val route = ShardRoute(3, "aeron:udp?endpoint=gw:20000", 10, "aeron:udp?endpoint=adapter:20001", 11)

    @Test
    fun `the store and the directory's channel and stream are read`() {
        val config = OrderEntryConfig.parse(properties())
        assertEquals(File("/var/most-fix/store"), config.storeDir)
        assertEquals("aeron:udp?endpoint=239.10.0.1:40000", config.directoryChannel)
        assertEquals(100, config.directoryStreamId)
    }

    @Test
    fun `each is required, and a refusal names its key`() {
        for (key in listOf("fix.storeDir", "fix.directoryChannel", "fix.directoryStreamId")) {
            val message = refused(key to "")
            assertTrue(key in message, message)
        }
        assertTrue("fix.directoryStreamId" in refused("fix.directoryStreamId" to "one hundred"))
    }

    @Test
    fun `without listed gateways a shard's are the directory's one endpoint`() {
        assertEquals(listOf(GatewayEndpoint.of(route)), OrderEntryConfig.parse(properties()).gatewaysFor(route))
    }

    @Test
    fun `listed gateways replace the directory's for their shard only, in their order`() {
        val config = OrderEntryConfig.parse(
            properties(
                *gateway(3, 1, "node-b"),
                *gateway(3, 0, "node-a"),
            ),
        )
        assertEquals(
            listOf(
                GatewayEndpoint("aeron:udp?endpoint=node-a:20000", 10, "aeron:udp?endpoint=adapter-a:20001", 11),
                GatewayEndpoint("aeron:udp?endpoint=node-b:20000", 10, "aeron:udp?endpoint=adapter-b:20001", 11),
            ),
            config.gatewaysFor(route),
        )
        val other = ShardRoute(4, "aeron:udp?endpoint=gw4:20000", 10, "aeron:udp?endpoint=adapter:20004", 11)
        assertEquals(listOf(GatewayEndpoint.of(other)), config.gatewaysFor(other))
    }

    @Test
    fun `a listed gateway missing a field, a gap in the numbering, or a malformed key is refused`() {
        val full = gateway(3, 0, "node-a")
        val message = refused(*full.filterNot { it.first.endsWith("executionReportStreamId") }.toTypedArray())
        assertTrue("fix.shard.3.gateway.0.executionReportStreamId" in message, message)

        assertTrue("fix.shard.3.gateway" in refused(*full, *gateway(3, 2, "node-c")), "0 and 2, no 1")
        assertTrue("fix.shard.3.gateway.0.orderEntryStreamId" in refused(*full, "fix.shard.3.gateway.0.orderEntryStreamId" to "ten"))
        assertTrue("fix.shard.3.gateway.0.channel" in refused(*full, "fix.shard.3.gateway.0.channel" to "x"))
        assertTrue("fix.shard.x.gateway.0.orderEntryChannel" in refused("fix.shard.x.gateway.0.orderEntryChannel" to "aeron:ipc"))
    }

    private companion object {
        fun gateway(shard: Int, n: Int, node: String) = arrayOf(
            "fix.shard.$shard.gateway.$n.orderEntryChannel" to "aeron:udp?endpoint=$node:20000",
            "fix.shard.$shard.gateway.$n.orderEntryStreamId" to "10",
            "fix.shard.$shard.gateway.$n.executionReportChannel" to "aeron:udp?endpoint=adapter-${node.removePrefix("node-")}:20001",
            "fix.shard.$shard.gateway.$n.executionReportStreamId" to "11",
        )
    }
}
