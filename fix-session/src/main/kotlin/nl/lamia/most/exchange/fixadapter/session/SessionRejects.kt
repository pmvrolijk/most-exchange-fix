package nl.lamia.most.exchange.fixadapter.session

import nl.lamia.most.exchange.fixadapter.codecs.builder.BusinessMessageRejectEncoder
import nl.lamia.most.exchange.fixadapter.codecs.builder.RejectEncoder
import org.agrona.AsciiSequenceView
import uk.co.real_logic.artio.session.Session
import uk.co.real_logic.artio.util.MutableAsciiBuffer

/**
 * The two answers every backend gives a message it will not use (Design.md §5.4): a 35=3 for one its
 * decoder's `validate()` refused, and a 35=j for a type it does not serve. Written on the session
 * given, numbered by Artio.
 *
 * Returns false when Artio refused the send (back-pressured): the inbound message is then aborted
 * and delivered again. Each encoder is reset before use (non-negotiable 10). Allocation-free.
 */
class SessionRejects {
    private val reject = RejectEncoder()
    private val businessReject = BusinessMessageRejectEncoder()
    private val text = MutableAsciiBuffer(ByteArray(256))

    /** A 35=3: RefSeqNum, RefMsgType, RefTagID when there is one, and SessionRejectReason. */
    fun sessionReject(session: Session, refSeqNum: Int, refMsgType: AsciiSequenceView, refTagId: Int, reason: Int): Boolean {
        val r = reject
        r.reset()
        r.refSeqNum(refSeqNum).refMsgType(refMsgType).sessionRejectReason(reason)
        if (refTagId > 0) r.refTagID(refTagId)
        return session.trySend(r) >= 0
    }

    /** A 35=j: RefSeqNum, RefMsgType, BusinessRejectReason and a Text. */
    fun businessReject(session: Session, refSeqNum: Int, refMsgType: AsciiSequenceView, reason: Int, text: String): Boolean {
        val b = businessReject
        b.reset()
        b.refSeqNum(refSeqNum).refMsgType(refMsgType).businessRejectReason(reason)
        b.text(this.text, 0, this.text.putStringWithoutLengthAscii(0, text))
        return session.trySend(b) >= 0
    }
}
