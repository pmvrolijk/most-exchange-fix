package nl.lamia.most.exchange.fixadapter.marketdata

import io.aeron.Aeron
import io.aeron.FragmentAssembler
import io.aeron.Subscription
import io.aeron.logbuffer.ControlledFragmentHandler.Action
import nl.lamia.most.exchange.client.DepthFeedDecoder
import nl.lamia.most.exchange.client.DirectoryClient
import nl.lamia.most.exchange.fixadapter.codecs.Constants
import nl.lamia.most.exchange.fixadapter.codecs.decoder.HeaderDecoder
import nl.lamia.most.exchange.fixadapter.codecs.decoder.MarketDataRequestDecoder
import nl.lamia.most.exchange.fixadapter.codecs.decoder.SecurityListRequestDecoder
import nl.lamia.most.exchange.fixadapter.session.FixAcceptor
import nl.lamia.most.exchange.fixadapter.session.Role
import nl.lamia.most.exchange.fixadapter.session.RoleClaims
import nl.lamia.most.exchange.fixadapter.session.SessionRejects
import org.agrona.AsciiSequenceView
import org.agrona.CloseHelper
import org.agrona.DirectBuffer
import org.agrona.concurrent.Agent
import uk.co.real_logic.artio.library.FixLibrary
import uk.co.real_logic.artio.library.OnMessageInfo
import uk.co.real_logic.artio.library.SessionAcquireHandler
import uk.co.real_logic.artio.library.SessionHandler
import uk.co.real_logic.artio.messages.DisconnectReason
import uk.co.real_logic.artio.session.Session
import uk.co.real_logic.artio.util.MutableAsciiBuffer

/**
 * The market-data backend (Design.md §4): its own Artio library and duty cycle, so fan-out to market
 * data sessions never delays the order path. It claims the configured market-data sessions
 * ([RoleClaims]), follows the directory, and answers 35=x from it ([SecurityLists]). It subscribes to
 * the exchange's three feeds, assembled by the SDK, and serves 35=V from them ([Subscriptions]).
 *
 * As order entry's (non-negotiables 9, 10): Artio's invalid and session messages stop at the handler,
 * every application message is decoded and `validate()`d, a failure is a 35=3, and a type not served
 * is a 35=j. A reply Artio refuses aborts its message, which is delivered again.
 *
 * Construct after the acceptor's engine has started and before it binds; call [doWork] from one
 * thread only.
 */
class MarketDataBackend(
    acceptor: FixAcceptor,
    config: MarketDataConfig,
    private val log: (String) -> Unit = System.err::println,
) : Agent, AutoCloseable {

    private val resources = ArrayList<AutoCloseable>()

    private val directory = DirectoryClient()
    private val securityLists = SecurityLists(directory)
    private val subscriptions = Subscriptions(directory)
    private val feedDecoder = DepthFeedDecoder(subscriptions.assembler)
    private val feedAssembler = FragmentAssembler { buffer, offset, length, _ -> feedDecoder.onMessage(buffer, offset, length) }
    private val feeds = ArrayList<Subscription>(3)
    private val rejects = SessionRejects()

    private val aeron: Aeron
    private val directorySubscription: Subscription
    private val directoryAssembler = FragmentAssembler { buffer, offset, length, _ -> directory.onDirectoryMessage(buffer, offset, length) }
    private val claims = RoleClaims(acceptor.sessions, Role.MARKET_DATA) { session, reply ->
        log("market data: claim of $session failed: ${reply.state()} ${reply.error() ?: reply.resultIfPresent()}")
    }
    val library: FixLibrary

    /** Whether a directory has arrived: until then a 35=x is answered "temporarily unavailable". */
    val hasDirectory: Boolean get() = directory.isReady

    init {
        try {
            aeron = owned(Aeron.connect(Aeron.Context().aeronDirectoryName(acceptor.config.aeronDir)))
            directorySubscription = owned(aeron.addSubscription(config.directoryChannel, config.directoryStreamId))
            for (feed in listOf(config.l2, config.snapshot, config.l1)) feeds += owned(aeron.addSubscription(feed.channel, feed.streamId))
            library = owned(FixLibrary.connect(acceptor.libraryConfiguration(claims).apply {
                libraryName("market-data")
                sessionAcquireHandler(SessionAcquireHandler { session, _ -> Handler(session) })
            }))
        } catch (e: Throwable) {
            close()
            throw e
        }
    }

    override fun roleName(): String = "market-data"

    override fun doWork(): Int {
        var work = library.poll(FRAGMENT_LIMIT)
        work += claims.poll()
        work += directorySubscription.poll(directoryAssembler, FRAGMENT_LIMIT)
        for (i in 0 until feeds.size) work += feeds[i].poll(feedAssembler, FEED_FRAGMENT_LIMIT)
        work += subscriptions.doWork()
        return work
    }

    private val message = MutableAsciiBuffer()
    private val header = HeaderDecoder()
    private val securityListRequest = SecurityListRequestDecoder()
    private val marketDataRequest = MarketDataRequestDecoder()
    private val msgType = AsciiSequenceView()

    /** One market-data session's messages (§4). */
    private inner class Handler(private val session: Session) : SessionHandler {
        private val out = FixOut { session.trySend(it) >= 0 }

        override fun onMessage(
            buffer: DirectBuffer, offset: Int, length: Int, libraryId: Int, session: Session,
            sequenceIndex: Int, messageType: Long, timestampInNs: Long, position: Long, messageInfo: OnMessageInfo,
        ): Action {
            if (!messageInfo.isValid || isSessionMessage(messageType)) return Action.CONTINUE
            message.wrap(buffer)
            return when (messageType) {
                Constants.SECURITY_LIST_REQUEST_MESSAGE -> {
                    val x = securityListRequest
                    x.reset()
                    x.decode(message, offset, length)
                    if (!x.validate()) {
                        answered(rejects.sessionReject(session, x.header().msgSeqNum(), x.header().msgType(msgType), x.invalidTagId(), x.rejectReason()))
                    } else {
                        securityLists.answer(session.id(), x, out)
                    }
                }
                Constants.MARKET_DATA_REQUEST_MESSAGE -> {
                    val v = marketDataRequest
                    v.reset()
                    v.decode(message, offset, length)
                    if (!v.validate()) {
                        answered(rejects.sessionReject(session, v.header().msgSeqNum(), v.header().msgType(msgType), v.invalidTagId(), v.rejectReason()))
                    } else {
                        subscriptions.onRequest(session.id(), v, out)
                    }
                }
                Constants.BUSINESS_MESSAGE_REJECT_MESSAGE -> Action.CONTINUE // never answered: no reject loop
                else -> {
                    header.reset()
                    header.decode(message, offset, length)
                    answered(rejects.businessReject(session, header.msgSeqNum(), header.msgType(msgType), UNSUPPORTED_MESSAGE_TYPE, UNSUPPORTED_TEXT))
                }
            }
        }

        override fun onTimeout(libraryId: Int, session: Session) = Unit
        override fun onSlowStatus(libraryId: Int, session: Session, hasBecomeSlow: Boolean) = Unit
        override fun onDisconnect(libraryId: Int, session: Session, reason: DisconnectReason): Action {
            subscriptions.onDisconnect(session.id()) // they end with the session (§4)
            return Action.CONTINUE
        }
        override fun onSessionStart(session: Session) = Unit
    }

    private fun answered(accepted: Boolean): Action = if (accepted) Action.CONTINUE else Action.ABORT

    override fun close() {
        for (i in resources.indices.reversed()) CloseHelper.quietClose(resources[i])
        resources.clear()
    }

    private fun <T : AutoCloseable> owned(resource: T): T = resource.also { resources += it }

    private companion object {
        const val FRAGMENT_LIMIT = 10
        const val FEED_FRAGMENT_LIMIT = 100

        /** BusinessRejectReason (380) 3. */
        const val UNSUPPORTED_MESSAGE_TYPE = 3
        const val UNSUPPORTED_TEXT = "unsupported message type"

        fun isSessionMessage(messageType: Long): Boolean = when (messageType) {
            Constants.HEARTBEAT_MESSAGE, Constants.TEST_REQUEST_MESSAGE, Constants.RESEND_REQUEST_MESSAGE,
            Constants.REJECT_MESSAGE, Constants.SEQUENCE_RESET_MESSAGE, Constants.LOGOUT_MESSAGE, Constants.LOGON_MESSAGE,
            -> true
            else -> false
        }
    }
}
