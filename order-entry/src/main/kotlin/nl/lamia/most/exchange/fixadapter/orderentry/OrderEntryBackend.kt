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
import nl.lamia.most.exchange.client.OrderFate
import nl.lamia.most.exchange.client.ReportAction
import nl.lamia.most.exchange.client.OrderEntrySession
import nl.lamia.most.exchange.fixadapter.codecs.Constants
import nl.lamia.most.exchange.fixadapter.orderentry.artio.ArtioReplies
import nl.lamia.most.exchange.fixadapter.orderentry.artio.ArtioReports
import nl.lamia.most.exchange.fixadapter.orderentry.artio.InboundRecording
import nl.lamia.most.exchange.fixadapter.orderentry.artio.LibraryRecording
import nl.lamia.most.exchange.fixadapter.orderentry.artio.ArtioSessions
import nl.lamia.most.exchange.fixadapter.orderentry.artio.ReportSessions
import nl.lamia.most.exchange.fixadapter.orderentry.delivery.ReportDelivery
import nl.lamia.most.exchange.fixadapter.orderentry.outbound.OutboundReports
import nl.lamia.most.exchange.sbe.ClientExecutionReportDecoder
import nl.lamia.most.exchange.sbe.RequestStatus
import nl.lamia.most.exchange.fixadapter.orderentry.exchange.DirectoryIndex
import nl.lamia.most.exchange.fixadapter.orderentry.exchange.ShardVenue
import nl.lamia.most.exchange.fixadapter.orderentry.inbound.ArchivedSends
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
import uk.co.real_logic.artio.library.LibraryConnectHandler
import uk.co.real_logic.artio.library.OnMessageInfo
import uk.co.real_logic.artio.library.SessionAcquireHandler
import uk.co.real_logic.artio.library.SessionHandler
import uk.co.real_logic.artio.messages.DisconnectReason
import uk.co.real_logic.artio.messages.SessionReplyStatus
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
 * Reports and fates go to the client through the outbound path (§5.3), on each participant's session
 * online or offline (§6.3). Every session Artio knows is requested offline during construction, so it
 * must come before the acceptor binds. That request replays the session's archived inbound messages
 * past what the store has handled, and rows a host crash lost are rebuilt from them (§8.17). The durable `reportSeq` of each shard's SDK session advances
 * only behind the archive ([delivery], §6.2), and a request is sent only once the archive has its
 * 35=D or 35=F ([sends], §6.1, §8.20). [links] makes a gateway's link; tests may stand a model
 * of the exchange in for Aeron's.
 */
class OrderEntryBackend(
    private val acceptor: FixAcceptor,
    config: OrderEntryConfig,
    tradingDate: IntSupplier,
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
    private val replies = ArtioReplies(index)
    val sessions = ReportSessions(ArtioSessions, log)
    private val reports = ArtioReports(index, sessions)
    private val directory = DirectoryClient()
    val inbound: InboundOrders
    val sends: ArchivedSends
    val outbound: OutboundReports
    val delivery: ReportDelivery
    private val recording: LibraryRecording

    /** Shards whose SDK session has finished its opening mass status (most-exchange Adapters.md §1). */
    var readyShards = 0
        private set

    private val aeron: Aeron
    private val directorySubscription: Subscription
    private val directoryAssembler = FragmentAssembler { buffer, offset, length, _ -> directory.onDirectoryMessage(buffer, offset, length) }
    private lateinit var claims: RoleClaims
    val library: FixLibrary

    /** Shards whose directory endpoint has moved from the one their session was created with, as last logged. */
    private val movedLogged = HashSet<Int>()

    init {
        require(participantIds.isNotEmpty()) { "no order-entry session is configured" }
        try {
            store.load(tradingDate.asInt)
            StartupGuards.check(acceptor.engine.allSessions(), acceptor.sessions, store)
            aeron = owned(Aeron.connect(Aeron.Context().aeronDirectoryName(acceptor.config.aeronDir)))
            val libraryConfiguration = acceptor.libraryConfiguration(claims()).apply {
                libraryName("order-entry")
                sessionAcquireHandler(SessionAcquireHandler { session, _ -> onAcquired(session) })
                libraryConnectHandler(Connection())
            }
            recording = LibraryRecording(aeron.countersReader(), libraryConfiguration)
            delivery = ReportDelivery(store, recording)
            sends = ArchivedSends(store, venue, InboundRecording(aeron.countersReader(), libraryConfiguration.inboundLibraryStream())) { row, text ->
                outbound.refuse(row, text)
            }
            inbound = InboundOrders(store, venue, index, tradingDate, sends) { participantId ->
                log("participant $participantId: id collision, session blocked until its sequence is reset (Design.md §3.3)")
            }
            outbound = OutboundReports(store, reports, sends, delivery, log)
            outbound.recover()
            directorySubscription = owned(aeron.addSubscription(config.directoryChannel, config.directoryStreamId))
            library = owned(FixLibrary.connect(libraryConfiguration))
            sessions.library = ArtioSessions.handover(library)
            acquireOffline()
        } catch (e: Throwable) {
            close()
            throw e
        }
    }

    override fun roleName(): String = "order-entry"

    override fun doWork(): Int {
        var work = library.poll(FRAGMENT_LIMIT)
        work += claims.poll()
        work += sessions.doWork()
        work += directorySubscription.poll(directoryAssembler, FRAGMENT_LIMIT)
        if (index.update(directory)) {
            onDirectory()
            work++
        }
        work += venue.doWork()
        work += outbound.doWork()
        work += sends.doWork()
        work += delivery.doWork()
        return work
    }

    private fun claims(): RoleClaims = RoleClaims(acceptor.sessions, Role.ORDER_ENTRY) { session, reply ->
        // Already the library's, offline: the engine announced its Logon all the same.
        val participantId = session.participantId
        if (reply.resultIfPresent() == SessionReplyStatus.OTHER_SESSION_OWNER && participantId != null && sessions.session(participantId) != null) {
            return@RoleClaims
        }
        log("order entry: claim of $session failed: ${reply.state()} ${reply.error() ?: reply.resultIfPresent()}")
    }.also { claims = it }

    /**
     * Every configured order-entry session Artio knows, requested offline before the acceptor binds,
     * so a report for a client not logged on is written to its session and resent at its Logon
     * (Design.md §6.3). Each replays its archived inbound messages from the participant's high-water
     * + 1, and the inbound path rebuilds the rows a host crash lost (§8.17). Waits until every replay
     * is complete with its session held: until the bind no client can log on.
     */
    private fun acquireOffline() {
        val deadline = System.nanoTime() + STARTUP_TIMEOUT_NS
        while (!library.isConnected) {
            check(System.nanoTime() < deadline) { "order entry: the library did not connect within ${STARTUP_TIMEOUT_NS / 1_000_000_000L} s" }
            library.poll(FRAGMENT_LIMIT)
        }
        for (info in acceptor.engine.allSessions()) {
            val key = info.sessionKey() ?: continue
            val configured = acceptor.sessions.find(senderCompId = key.localCompId(), targetCompId = key.remoteCompId()) ?: continue
            if (configured.role != Role.ORDER_ENTRY) continue
            val participantId = checkNotNull(configured.participantId)
            val from = store.replayFrom(participantId)
            sessions.requestAtStartup(participantId, info.sessionId(), replayFrom = (from and 0xffffffffL).toInt(), replayIndex = (from ushr 32).toInt())
        }
        while (sessions.isRequesting || sessions.isAnyReplaying) {
            check(System.nanoTime() < deadline) {
                "order entry: the offline sessions were not handed over, their archives replayed, within ${STARTUP_TIMEOUT_NS / 1_000_000_000L} s"
            }
            library.poll(FRAGMENT_LIMIT)
            sessions.doWork()
        }
        if (inbound.rebuilt > 0) {
            log("order entry: ${inbound.rebuilt} requests rebuilt from the archive, in flight once the directory names their shards (Design.md §8.17)")
        }
    }

    /** The library's connection to the engine (§6.2): positions are compared only on the connection they were taken on. */
    private inner class Connection : LibraryConnectHandler {
        override fun onConnect(library: FixLibrary) {
            recording.onLibraryConnect()
            delivery.onLibraryConnect()
            sessions.onLibraryConnect()
        }

        override fun onDisconnect(library: FixLibrary) {
            log("order entry: the library lost the engine; reports wait until it is back (Design.md §6.2)")
            sessions.onLibraryDisconnect()
        }
    }

    // ------------------------------------------------------------------ the directory, §3.5

    private fun onDirectory() {
        venue.learn(directory.securities)
        // Before any shard's session, so the requests recovery rebuilt are restored with the rest (§8.17).
        if (inbound.awaitingDirectory > 0) {
            val resolved = inbound.resolveRecovered { row, text ->
                log("order entry: participant ${row.participantId}: recovered request ${row.clOrdId} refused, $text (Design.md §8.17)")
                outbound.refuse(row, text)
            }
            log("order entry: $resolved recovered requests resolved against the directory")
        }
        for (route in directory.shards) {
            val listed = venue.endpoints(route.shardId)
            if (listed == null) {
                val endpoints = config.gatewaysFor(route)
                val shardLinks = endpoints.map { endpoint -> links(aeron, endpoint).also { if (it is AutoCloseable) owned(it) } }
                val session = OrderEntrySession(shardLinks, participantIds, ShardListener(route.shardId), sessionConfig, delivery.sessionStore(route.shardId))
                restoreInFlight(route.shardId, session)
                venue.add(route.shardId, session, endpoints)
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

    /**
     * The store's requests in flight to [shardId], handed back before the session's first `doWork`
     * (Design.md §3.4, §6.1).
     */
    private fun restoreInFlight(shardId: Int, session: OrderEntrySession) {
        var restored = 0
        store.forEachInFlight { row ->
            if (row.shardId == shardId) {
                session.restoreInFlight(row.participantId, row.clOrdId)
                restored++
            }
        }
        if (restored > 0) log("order entry: shard $shardId: $restored requests restored in flight")
    }

    /** The SDK session of one shard, to the outbound path: a mass status settles that shard's orders only (§5.3). */
    private inner class ShardListener(private val shardId: Int) : OrderEntryListener {
        override fun onReport(report: ClientExecutionReportDecoder): ReportAction = outbound.onReport(shardId, report)

        override fun onSettled(participantId: Long, clOrdId: Long, fate: OrderFate) = outbound.onSettled(participantId, clOrdId, fate)

        override fun onMassStatusComplete(participantId: Long, status: RequestStatus, orderCount: Int, nextSeq: Long) =
            outbound.onMassStatusComplete(shardId, participantId, status, nextSeq)

        override fun onRequestRefused(participantId: Long, status: RequestStatus) =
            log("order entry: shard $shardId refused participant $participantId's request: $status")

        override fun onReady() {
            readyShards++
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
        sessions.acquired(participantId, session)
        // Offline (requested at startup or after a disconnect), its Logon comes later: onSessionStart.
        if (session.isActive) loggedOn(participantId, session)
        return Handler(participantId)
    }

    /** The participant's client is logged on (§3.2, §3.3): its `sequenceIndex` guarded, a blocked session logged out. */
    private fun loggedOn(participantId: Long, session: Session) {
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
    }

    /** One order-entry session's messages, to the inbound path (§3.3, "What reaches the inbound path"). */
    private inner class Handler(private val participantId: Long) : SessionHandler {
        override fun onMessage(
            buffer: DirectBuffer, offset: Int, length: Int, libraryId: Int, session: Session,
            sequenceIndex: Int, messageType: Long, timestampInNs: Long, position: Long, messageInfo: OnMessageInfo,
        ): Action {
            if (!messageInfo.isValid || isSessionMessage(messageType)) return Action.CONTINUE
            if (sequenceIndex > store.highestSequenceIndex(participantId)) store.recordSequenceIndex(participantId, sequenceIndex)
            // The archive's, replayed at startup (§8.17): no live message comes on an offline session.
            if (sessions.isReplaying(participantId) && !session.isActive) {
                // Past a block's index, its Logon came, and lifted it (§3.3); Logons are not replayed to here.
                store.liftBlock(participantId, sequenceIndex)
                return inbound.onReplayed(participantId, sequenceIndex, messageType, buffer, offset, length)
            }
            replies.session = session
            return inbound.onMessage(participantId, replies, sequenceIndex, messageType, buffer, offset, length, position)
        }

        override fun onTimeout(libraryId: Int, session: Session) = Unit
        override fun onSlowStatus(libraryId: Int, session: Session, hasBecomeSlow: Boolean) = Unit
        override fun onDisconnect(libraryId: Int, session: Session, reason: DisconnectReason): Action {
            // Requested here, inside the poll: ahead of a claim the engine announces in the same poll.
            sessions.disconnected(participantId, session)
            return Action.CONTINUE
        }

        /** A Logon on a session the library already held offline. */
        override fun onSessionStart(session: Session) = loggedOn(participantId, session)
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
        val STARTUP_TIMEOUT_NS = java.util.concurrent.TimeUnit.SECONDS.toNanos(10)

        fun isSessionMessage(messageType: Long): Boolean = when (messageType) {
            Constants.HEARTBEAT_MESSAGE, Constants.TEST_REQUEST_MESSAGE, Constants.RESEND_REQUEST_MESSAGE,
            Constants.REJECT_MESSAGE, Constants.SEQUENCE_RESET_MESSAGE, Constants.LOGOUT_MESSAGE, Constants.LOGON_MESSAGE,
            -> true
            else -> false
        }
    }
}
