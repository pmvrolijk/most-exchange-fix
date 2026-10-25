package nl.lamia.most.exchange.fixadapter.orderentry

import io.aeron.Aeron
import io.aeron.FragmentAssembler
import io.aeron.Subscription
import io.aeron.logbuffer.ControlledFragmentHandler.Action
import nl.lamia.most.exchange.client.AeronGatewayLink
import nl.lamia.most.exchange.client.DirectoryClient
import nl.lamia.most.exchange.client.GatewayEndpoint
import nl.lamia.most.exchange.client.GatewayLink
import nl.lamia.most.exchange.client.OrderEntryListener
import nl.lamia.most.exchange.client.OrderEntrySession
import nl.lamia.most.exchange.client.SessionStore
import nl.lamia.most.exchange.fixadapter.codecs.Constants
import nl.lamia.most.exchange.fixadapter.orderentry.artio.ArtioReplies
import nl.lamia.most.exchange.fixadapter.orderentry.exchange.DirectoryIndex
import nl.lamia.most.exchange.fixadapter.orderentry.exchange.ShardVenue
import nl.lamia.most.exchange.fixadapter.orderentry.inbound.InboundOrders
import nl.lamia.most.exchange.fixadapter.orderentry.inbound.Texts
import nl.lamia.most.exchange.fixadapter.orderentry.store.OrderStore
import nl.lamia.most.exchange.fixadapter.session.FixAcceptor
import nl.lamia.most.exchange.fixadapter.session.Role
import nl.lamia.most.exchange.fixadapter.session.RoleClaims
import org.agrona.CloseHelper
import org.agrona.DirectBuffer
import org.agrona.concurrent.Agent
import uk.co.real_logic.artio.library.FixLibrary
import uk.co.real_logic.artio.library.OnMessageInfo
import uk.co.real_logic.artio.library.SessionAcquireHandler
import uk.co.real_logic.artio.library.SessionHandler
import uk.co.real_logic.artio.messages.DisconnectReason
import uk.co.real_logic.artio.session.Session
import java.util.function.IntSupplier

/**
 * The order-entry backend (Design.md §3): its Artio library, the order store, the directory, and an
 * `OrderEntrySession` per shard, in one duty cycle on one thread (§3.5). Call [doWork] from that
 * thread, and only from it.
 *
 * Constructed after the [acceptor]'s engine has started and before it binds: the store is loaded and
 * the startup guards run (§3.2), and construction fails if they refuse. The SDK's links run on the
 * acceptor's embedded media driver (§3.5).
 *
 * Reports, fates and the durable `reportSeq` are [listener]'s and [sessionStore]'s: slices 3 and 4.
 * [links] makes a gateway's link; tests may stand a model of the exchange in for Aeron's.
 */
class OrderEntryBackend(
    private val acceptor: FixAcceptor,
    config: OrderEntryConfig,
    private val listener: OrderEntryListener,
    tradingDate: IntSupplier,
    private val sessionStore: SessionStore = SessionStore.InMemory(),
    private val sessionConfig: OrderEntrySession.Config = OrderEntrySession.Config(),
    private val links: (Aeron, GatewayEndpoint) -> GatewayLink = ::AeronGatewayLink,
    private val log: (String) -> Unit = System.err::println,
) : Agent, AutoCloseable {

    private val config = config

    /** What this backend opened, closed in reverse. */
    private val resources = ArrayList<AutoCloseable>()

    private val participantIds: LongArray = acceptor.sessions.all
        .filter { it.role == Role.ORDER_ENTRY }.map { checkNotNull(it.participantId) }.toLongArray()

    val store: OrderStore = owned(OrderStore.open(config.storeDir))
    private val index = DirectoryIndex()
    private val venue = ShardVenue()
    private val replies = ArtioReplies()
    private val directory = DirectoryClient()
    val inbound: InboundOrders

    private val aeron: Aeron
    private val directorySubscription: Subscription
    private val directoryAssembler = FragmentAssembler { buffer, offset, length, _ -> directory.onDirectoryMessage(buffer, offset, length) }
    private val claims: RoleClaims
    val library: FixLibrary

    /** Shards whose directory endpoint has moved from the one their session was created with, as last logged. */
    private val movedLogged = HashSet<Int>()

    init {
        require(participantIds.isNotEmpty()) { "no order-entry session is configured" }
        try {
            store.load(tradingDate.asInt)
            StartupGuards.check(acceptor.engine.allSessions(), acceptor.sessions, store)
            inbound = InboundOrders(store, venue, index, tradingDate) { participantId ->
                log("participant $participantId: id collision, session blocked until its sequence is reset (Design.md §3.3)")
            }
            aeron = owned(Aeron.connect(Aeron.Context().aeronDirectoryName(acceptor.config.aeronDir)))
            directorySubscription = owned(aeron.addSubscription(config.directoryChannel, config.directoryStreamId))
            claims = RoleClaims(acceptor.sessions, Role.ORDER_ENTRY) { session, reply ->
                log("order entry: claim of $session failed: ${reply.state()} ${reply.error() ?: reply.resultIfPresent()}")
            }
            library = owned(
                FixLibrary.connect(
                    acceptor.libraryConfiguration(claims).apply {
                        libraryName("order-entry")
                        sessionAcquireHandler(SessionAcquireHandler { session, _ -> onAcquired(session) })
                    },
                ),
            )
        } catch (e: Throwable) {
            close()
            throw e
        }
    }

    override fun roleName(): String = "order-entry"

    override fun doWork(): Int {
        var work = library.poll(FRAGMENT_LIMIT)
        work += claims.poll()
        work += directorySubscription.poll(directoryAssembler, FRAGMENT_LIMIT)
        if (index.update(directory)) {
            onDirectory()
            work++
        }
        work += venue.doWork()
        return work
    }

    // ------------------------------------------------------------------ the directory, §3.5

    private fun onDirectory() {
        venue.learn(directory.securities)
        for (route in directory.shards) {
            val listed = venue.endpoints(route.shardId)
            if (listed == null) {
                val endpoints = config.gatewaysFor(route)
                val shardLinks = endpoints.map { endpoint -> links(aeron, endpoint).also { if (it is AutoCloseable) owned(it) } }
                venue.add(route.shardId, OrderEntrySession(shardLinks, participantIds, listener, sessionConfig, sessionStore), endpoints)
                log("order entry: shard ${route.shardId} through ${endpoints.joinToString()}")
            } else if (config.gateways[route.shardId] == null && listed.single() != GatewayEndpoint.of(route)) {
                if (movedLogged.add(route.shardId)) {
                    log("order entry: the directory moved shard ${route.shardId} to ${GatewayEndpoint.of(route)}; " +
                        "still on ${listed.single()} until restarted (Design.md §3.5)")
                }
            } else {
                movedLogged.remove(route.shardId)
            }
        }
    }

    // ------------------------------------------------------------------ sessions, §2, §3.2, §3.3

    private fun onAcquired(session: Session): SessionHandler {
        val key = session.compositeKey()
        val configured = acceptor.sessions.find(senderCompId = key.localCompId(), targetCompId = key.remoteCompId())
        val participantId = configured?.participantId
        if (configured == null || configured.role != Role.ORDER_ENTRY || participantId == null) {
            log("order entry: acquired $key, not a configured order-entry session; disconnected")
            session.logoutAndDisconnect()
            return Ignored
        }
        val sequenceIndex = session.sequenceIndex()
        if (sequenceIndex < store.highestSequenceIndex(participantId)) {
            // Artio's state lost while running: the derived ids would repeat (§3.2).
            store.block(participantId, sequenceIndex)
            log("participant $participantId: sequenceIndex $sequenceIndex below the stored ${store.highestSequenceIndex(participantId)}; blocked")
        } else {
            store.recordSequenceIndex(participantId, sequenceIndex)
            if (store.liftBlock(participantId, sequenceIndex)) {
                log("participant $participantId: back at sequenceIndex $sequenceIndex after a reset; block lifted (Design.md §3.3)")
            }
        }
        if (store.isBlocked(participantId)) {
            replies.session = session
            replies.logout(Texts.SEQUENCE_RESET_REQUIRED)
        }
        return Handler(participantId)
    }

    /** One order-entry session's messages, to the inbound path (§3.3, "What reaches the inbound path"). */
    private inner class Handler(private val participantId: Long) : SessionHandler {
        override fun onMessage(
            buffer: DirectBuffer, offset: Int, length: Int, libraryId: Int, session: Session,
            sequenceIndex: Int, messageType: Long, timestampInNs: Long, position: Long, messageInfo: OnMessageInfo,
        ): Action {
            if (!messageInfo.isValid || isSessionMessage(messageType)) return Action.CONTINUE
            if (sequenceIndex > store.highestSequenceIndex(participantId)) store.recordSequenceIndex(participantId, sequenceIndex)
            replies.session = session
            return inbound.onMessage(participantId, replies, sequenceIndex, messageType, buffer, offset, length)
        }

        override fun onTimeout(libraryId: Int, session: Session) = Unit
        override fun onSlowStatus(libraryId: Int, session: Session, hasBecomeSlow: Boolean) = Unit
        override fun onDisconnect(libraryId: Int, session: Session, reason: DisconnectReason): Action = Action.CONTINUE
        override fun onSessionStart(session: Session) = Unit
    }

    private object Ignored : SessionHandler {
        override fun onMessage(
            buffer: DirectBuffer, offset: Int, length: Int, libraryId: Int, session: Session,
            sequenceIndex: Int, messageType: Long, timestampInNs: Long, position: Long, messageInfo: OnMessageInfo,
        ): Action = Action.CONTINUE

        override fun onTimeout(libraryId: Int, session: Session) = Unit
        override fun onSlowStatus(libraryId: Int, session: Session, hasBecomeSlow: Boolean) = Unit
        override fun onDisconnect(libraryId: Int, session: Session, reason: DisconnectReason): Action = Action.CONTINUE
        override fun onSessionStart(session: Session) = Unit
    }

    override fun close() {
        for (i in resources.indices.reversed()) CloseHelper.quietClose(resources[i])
        resources.clear()
    }

    private fun <T : AutoCloseable> owned(resource: T): T = resource.also { resources += it }

    private companion object {
        const val FRAGMENT_LIMIT = 10

        fun isSessionMessage(messageType: Long): Boolean = when (messageType) {
            Constants.HEARTBEAT_MESSAGE, Constants.TEST_REQUEST_MESSAGE, Constants.RESEND_REQUEST_MESSAGE,
            Constants.REJECT_MESSAGE, Constants.SEQUENCE_RESET_MESSAGE, Constants.LOGOUT_MESSAGE, Constants.LOGON_MESSAGE,
            -> true
            else -> false
        }
    }
}
