package nl.lamia.most.exchange.fixadapter.orderentry

import nl.lamia.most.exchange.client.GatewayEndpoint
import nl.lamia.most.exchange.client.ShardRoute
import java.io.File
import java.util.Properties

/**
 * Order entry's keys in the node file (Design.md §2, "Configuration and logon"): the order store,
 * where the directory is broadcast, and optionally a shard's gateways (§3.5).
 */
class OrderEntryConfig(
    val storeDir: File,
    val directoryChannel: String,
    val directoryStreamId: Int,
    /** Per shard, the gateways listed in the node file, in order. */
    val gateways: Map<Int, List<GatewayEndpoint>> = emptyMap(),
) {
    /** The gateways listed for [route]'s shard, or else the directory's one endpoint (§3.5). */
    fun gatewaysFor(route: ShardRoute): List<GatewayEndpoint> = gateways[route.shardId] ?: listOf(GatewayEndpoint.of(route))

    companion object {
        const val STORE_DIR = "fix.storeDir"
        const val DIRECTORY_CHANNEL = "fix.directoryChannel"
        const val DIRECTORY_STREAM_ID = "fix.directoryStreamId"
        private const val SHARD_PREFIX = "fix.shard."
        private val GATEWAY_KEY = Regex("""fix\.shard\.(\d+)\.gateway\.(\d+)\.(orderEntryChannel|orderEntryStreamId|executionReportChannel|executionReportStreamId)""")

        fun parse(properties: Properties): OrderEntryConfig {
            fun required(key: String): String =
                properties.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: throw IllegalArgumentException("missing required configuration key: $key")

            fun streamId(key: String): Int =
                required(key).toIntOrNull() ?: throw IllegalArgumentException("$key must be an Aeron stream id, a number")

            // shard -> gateway number -> field -> key
            val listed = sortedMapOf<Int, java.util.SortedMap<Int, MutableMap<String, String>>>()
            for (key in properties.stringPropertyNames().filter { it.startsWith(SHARD_PREFIX) }) {
                val match = GATEWAY_KEY.matchEntire(key)
                    ?: throw IllegalArgumentException("$key: not fix.shard.<id>.gateway.<n>.<orderEntryChannel|orderEntryStreamId|executionReportChannel|executionReportStreamId>")
                val (shard, n, field) = match.destructured
                listed.getOrPut(shard.toInt()) { sortedMapOf() }.getOrPut(n.toInt()) { mutableMapOf() }[field] = key
            }
            val gateways = listed.mapValues { (shard, byNumber) ->
                require(byNumber.keys.toList() == (0 until byNumber.size).toList()) {
                    "fix.shard.$shard.gateway.<n>: numbered ${byNumber.keys}, not 0 to ${byNumber.size - 1}"
                }
                byNumber.map { (n, _) ->
                    fun key(field: String) = "fix.shard.$shard.gateway.$n.$field"
                    GatewayEndpoint(
                        required(key("orderEntryChannel")), streamId(key("orderEntryStreamId")),
                        required(key("executionReportChannel")), streamId(key("executionReportStreamId")),
                    )
                }
            }
            return OrderEntryConfig(
                storeDir = File(required(STORE_DIR)),
                directoryChannel = required(DIRECTORY_CHANNEL),
                directoryStreamId = streamId(DIRECTORY_STREAM_ID),
                gateways = gateways,
            )
        }
    }
}
