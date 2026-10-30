package nl.lamia.most.exchange.fixadapter.marketdata

import java.util.Properties

/** One Aeron stream: a channel and a stream id. */
data class FeedStream(val channel: String, val streamId: Int)

/**
 * Market data's keys in the node file (Design.md §4): where the directory is broadcast, the same keys
 * order entry reads, and the exchange's three feeds, as most-exchange's own processes configure them.
 */
class MarketDataConfig(
    val directoryChannel: String,
    val directoryStreamId: Int,
    /** LastTrade and TopOfBook. */
    val l1: FeedStream,
    /** DepthUpdate, the incremental feed. */
    val l2: FeedStream,
    /** The L2 recovery feed: DepthSnapshotBegin, Level, End. */
    val snapshot: FeedStream,
) {
    companion object {
        const val DIRECTORY_CHANNEL = "fix.directoryChannel"
        const val DIRECTORY_STREAM_ID = "fix.directoryStreamId"

        fun parse(properties: Properties): MarketDataConfig {
            fun required(key: String): String =
                properties.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: throw IllegalArgumentException("missing required configuration key: $key")

            fun streamId(key: String): Int =
                required(key).toIntOrNull() ?: throw IllegalArgumentException("$key must be an Aeron stream id, a number")

            fun feed(name: String) = FeedStream(required("fix.md.$name.channel"), streamId("fix.md.$name.streamId"))

            return MarketDataConfig(
                directoryChannel = required(DIRECTORY_CHANNEL),
                directoryStreamId = streamId(DIRECTORY_STREAM_ID),
                l1 = feed("l1"),
                l2 = feed("l2"),
                snapshot = feed("snapshot"),
            )
        }
    }
}
