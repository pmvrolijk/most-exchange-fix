package nl.lamia.most.exchange.fixadapter.marketdata

import io.aeron.logbuffer.ControlledFragmentHandler.Action
import nl.lamia.most.exchange.client.DirectoryClient
import nl.lamia.most.exchange.client.RoutedSecurity
import nl.lamia.most.exchange.fixadapter.codecs.builder.SecurityListEncoder
import nl.lamia.most.exchange.fixadapter.codecs.decoder.SecurityListRequestDecoder
import org.agrona.AsciiSequenceView
import uk.co.real_logic.artio.builder.Encoder

/** Where a backend's answers go: Artio's session, or a test's recorder. False when Artio refused it for now. */
fun interface FixOut {
    fun send(encoder: Encoder): Boolean
}

/**
 * 35=x → 35=y from the directory (Design.md §4): SecurityListRequestType 4, all securities, as
 * Symbol, SecurityID with SecurityIDSource 8, the ISIN as an alternative id, SecurityDesc, Currency
 * and the tick as MinPriceIncrement (969). At most [fragmentSize] entries per 35=y, in `securityId`
 * order, each with TotNoRelatedSym and LastFragment.
 *
 * A send Artio refuses returns [Action.ABORT]; the 35=x is delivered again and the answer goes on from
 * the fragment refused. Rare, so it may allocate: the directory is copied and sorted per request.
 */
class SecurityLists(private val directory: DirectoryClient, private val fragmentSize: Int = 100) {
    private val list = SecurityListEncoder()
    private val reqId = AsciiSequenceView()

    /** The answer under way, kept across Artio's refusals. */
    private var answering: Long = NONE
    private var entries: List<RoutedSecurity> = emptyList()
    private var next = 0

    /** Answers [request], from [sessionId]'s client, on [out]. The request has passed `validate()`. */
    fun answer(sessionId: Long, request: SecurityListRequestDecoder, out: FixOut): Action {
        val key = (sessionId shl 32) or (request.header().msgSeqNum().toLong() and 0xffffffffL)
        if (answering != key) {
            answering = key
            next = 0
            entries = if (request.securityListRequestType() == ALL_SECURITIES && directory.isReady) {
                directory.securities.sortedBy { it.securityId }
            } else {
                emptyList()
            }
        }
        val result = when {
            request.securityListRequestType() != ALL_SECURITIES -> UNSUPPORTED_REQUEST
            // The SDK's directory is ready once it holds a security: an empty one is not told apart.
            !directory.isReady -> TEMPORARILY_UNAVAILABLE
            else -> VALID
        }
        if (result != VALID) {
            if (!out.send(start(request, result))) return Action.ABORT
            answering = NONE
            return Action.CONTINUE
        }
        while (next < entries.size) {
            val count = minOf(fragmentSize, entries.size - next)
            val y = start(request, VALID).totNoRelatedSym(entries.size).lastFragment(next + count == entries.size)
            var entry = y.secListGrp().relatedSymGroup(count)
            for (i in next until next + count) {
                val s = entries[i]
                entry.instrument().symbol(s.symbol).securityID(s.securityId.toString()).securityIDSource(SECURITY_ID_SOURCE)
                    .securityDesc(s.name)
                    .secAltIDGrp().securityAltIDGroup(1).securityAltID(s.isin).securityAltIDSource(ISIN)
                entry.currency(s.currency)
                entry.minPriceIncrement(s.tickSize, PRICE_SCALE)
                if (i < next + count - 1) entry = entry.next()
            }
            if (!out.send(y)) return Action.ABORT
            next += count
        }
        answering = NONE
        entries = emptyList()
        return Action.CONTINUE
    }

    private fun start(request: SecurityListRequestDecoder, result: Int): SecurityListEncoder {
        val y = list
        y.reset()
        request.securityReqID(reqId)
        y.securityReqID(reqId).securityResponseID(reqId).securityRequestResult(result)
        return y
    }

    private companion object {
        const val NONE = -1L

        /** SecurityListRequestType (559) 4. */
        const val ALL_SECURITIES = 4

        // SecurityRequestResult (560).
        const val VALID = 0
        const val UNSUPPORTED_REQUEST = 1
        const val TEMPORARILY_UNAVAILABLE = 4

        /** SecurityIDSource 8: the exchange's own `securityId`. */
        const val SECURITY_ID_SOURCE = "8"

        /** SecurityAltIDSource 4: ISIN. */
        const val ISIN = "4"

        /** The exchange's fixed point: 8 implied decimals. */
        const val PRICE_SCALE = 8
    }
}
