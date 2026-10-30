package nl.lamia.most.exchange.fixadapter.session

import nl.lamia.most.exchange.client.DirectoryClient
import nl.lamia.most.exchange.sbe.DirectoryBeginEncoder
import nl.lamia.most.exchange.sbe.DirectoryEndEncoder
import nl.lamia.most.exchange.sbe.MessageHeaderEncoder
import nl.lamia.most.exchange.sbe.SecurityEntryEncoder
import nl.lamia.most.exchange.sbe.ShardEntryEncoder
import org.agrona.DirectBuffer
import org.agrona.concurrent.UnsafeBuffer

/** A shard as the directory lists it: its gateway's two endpoints (most-exchange `ShardRoute`). */
data class TestShard(
    val shardId: Int,
    val orderEntryChannel: String,
    val orderEntryStreamId: Int,
    val executionReportChannel: String,
    val executionReportStreamId: Int,
)

/** A security as the directory lists it; [tickSize] fixed-point, 8 implied decimals (0.01 by default). */
data class TestSecurity(
    val securityId: Int,
    val shardId: Int,
    val symbol: String,
    val isin: String,
    val currency: String = "EUR",
    val name: String = symbol,
    val tickSize: Long = 1_000_000L,
)

/**
 * One directory broadcast as most-exchange's discovery sends it (its Design.md §7): DirectoryBegin,
 * a ShardEntry per shard, a SecurityEntry per security, DirectoryEnd, each its own message.
 */
class DirectoryBroadcast(val version: Long, val shards: List<TestShard>, val securities: List<TestSecurity>) {
    private val buffer = UnsafeBuffer(ByteArray(1024))
    private val header = MessageHeaderEncoder()

    /** Hands each message of the broadcast, in order, to [sink]; [sink] returns false to stop. */
    fun messages(sink: (DirectBuffer, Int, Int) -> Boolean): Boolean {
        val begin = DirectoryBeginEncoder().wrapAndApplyHeader(buffer, 0, header)
            .universeVersion(version).shardCount(shards.size).securityCount(securities.size)
        if (!sink(buffer, 0, MessageHeaderEncoder.ENCODED_LENGTH + begin.encodedLength())) return false
        for (s in shards) {
            val e = ShardEntryEncoder().wrapAndApplyHeader(buffer, 0, header)
                .shardId(s.shardId).orderEntryStreamId(s.orderEntryStreamId).executionReportStreamId(s.executionReportStreamId)
                .orderEntryChannel(s.orderEntryChannel).executionReportChannel(s.executionReportChannel)
            if (!sink(buffer, 0, MessageHeaderEncoder.ENCODED_LENGTH + e.encodedLength())) return false
        }
        for (s in securities) {
            val e = SecurityEntryEncoder().wrapAndApplyHeader(buffer, 0, header)
                .priceFloor(1L).tickSize(s.tickSize).securityId(s.securityId).shardId(s.shardId).levelCount(10)
                .symbol(s.symbol).isin(s.isin).currency(s.currency).name(s.name)
            if (!sink(buffer, 0, MessageHeaderEncoder.ENCODED_LENGTH + e.encodedLength())) return false
        }
        val end = DirectoryEndEncoder().wrapAndApplyHeader(buffer, 0, header).universeVersion(version)
        return sink(buffer, 0, MessageHeaderEncoder.ENCODED_LENGTH + end.encodedLength())
    }

    fun feed(client: DirectoryClient) {
        messages { b, o, l -> client.onDirectoryMessage(b, o, l); true }
    }
}
