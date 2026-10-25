package nl.lamia.most.exchange.fixadapter.orderentry.exchange

import nl.lamia.most.exchange.client.GatewayEndpoint
import nl.lamia.most.exchange.client.OrderEntrySession
import nl.lamia.most.exchange.client.RoutedSecurity
import nl.lamia.most.exchange.client.SendResult
import nl.lamia.most.exchange.fixadapter.orderentry.inbound.OrderVenue
import nl.lamia.most.exchange.sbe.Side
import nl.lamia.most.exchange.sbe.SmpStrategy
import org.agrona.collections.Int2IntHashMap
import org.agrona.collections.Int2ObjectHashMap

/**
 * The exchange as the inbound path sends to it (Design.md §3.5): one `OrderEntrySession` per shard,
 * each holding every configured order-entry participant, and a request routed to its security's.
 *
 * A security's shard is remembered from every directory it was in: one that leaves the universe
 * still routes a cancel of an order open on it. A security never seen, or a shard with no session,
 * is `NO_GATEWAY`. Single-threaded, the order-entry library's thread.
 */
class ShardVenue : OrderVenue {
    private val shardBySecurity = Int2IntHashMap(DirectoryIndex.NO_SHARD)
    private val sessionByShard = Int2ObjectHashMap<OrderEntrySession>()
    private val sessions = ArrayList<OrderEntrySession>()
    private val endpointsByShard = Int2ObjectHashMap<List<GatewayEndpoint>>()

    fun has(shardId: Int): Boolean = sessionByShard.containsKey(shardId)

    /** The gateways [shardId]'s session was created with. */
    fun endpoints(shardId: Int): List<GatewayEndpoint>? = endpointsByShard.get(shardId)

    fun add(shardId: Int, session: OrderEntrySession, endpoints: List<GatewayEndpoint>) {
        check(!has(shardId)) { "shard $shardId already has a session" }
        sessionByShard.put(shardId, session)
        endpointsByShard.put(shardId, endpoints)
        sessions += session
    }

    /** Adds each security's shard from a new directory. Nothing is forgotten. */
    fun learn(securities: Collection<RoutedSecurity>) {
        for (s in securities) shardBySecurity.put(s.securityId, s.shardId)
    }

    /** Every shard's `doWork`, in the order they were added. */
    fun doWork(): Int {
        var work = 0
        for (i in 0 until sessions.size) work += sessions[i].doWork()
        return work
    }

    override fun newOrder(
        participantId: Long, clOrdId: Long, securityId: Int, side: Side, price: Long, qty: Long,
        expireDate: Int, smpId: Long, smpStrategy: SmpStrategy,
    ): SendResult {
        val session = sessionFor(securityId) ?: return SendResult.NO_GATEWAY
        return session.newOrder(participantId, clOrdId, securityId, side, price, qty, expireDate, smpId, smpStrategy)
    }

    override fun cancel(participantId: Long, clOrdId: Long, origClOrdId: Long, exchangeOrderId: Long, securityId: Int, side: Side): SendResult {
        val session = sessionFor(securityId) ?: return SendResult.NO_GATEWAY
        return session.cancel(participantId, clOrdId, origClOrdId, exchangeOrderId, securityId, side)
    }

    private fun sessionFor(securityId: Int): OrderEntrySession? {
        val shard = shardBySecurity.get(securityId)
        return if (shard == DirectoryIndex.NO_SHARD) null else sessionByShard.get(shard)
    }
}
