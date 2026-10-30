package nl.lamia.most.exchange.fixadapter.orderentry.artio

import nl.lamia.most.exchange.fixadapter.codecs.builder.ResendRequestEncoder
import nl.lamia.most.exchange.fixadapter.orderentry.store.OrderStore
import org.agrona.DirectBuffer
import org.agrona.collections.Long2ObjectHashMap
import org.agrona.concurrent.EpochClock
import org.agrona.concurrent.SystemEpochClock
import uk.co.real_logic.artio.session.Session

/** What [ReceiveGaps] reads of a session and asks of it: Artio's `Session` in production, a model in tests. */
interface GapSessions<S> {
    fun isActive(session: S): Boolean
    fun sequenceIndex(session: S): Int
    fun lastReceived(session: S): Int

    /** Sends a ResendRequest for [from] to [to]: Artio's position, or below 1 if it did not take it. */
    fun resendRequest(session: S, from: Int, to: Int): Long
    fun logout(session: S, text: String)
}

/** Artio's own. */
object ArtioGapSessions : GapSessions<Session> {
    private val request = ResendRequestEncoder()

    override fun isActive(session: Session) = session.isActive
    override fun sequenceIndex(session: Session) = session.sequenceIndex()
    override fun lastReceived(session: Session) = session.lastReceivedMsgSeqNum()

    override fun resendRequest(session: Session, from: Int, to: Int): Long {
        request.reset() // non-negotiable 10
        return session.trySend(request.beginSeqNo(from).endSeqNo(to))
    }

    override fun logout(session: Session, text: String) {
        session.logoutAndDisconnect(text.toByteArray(Charsets.US_ASCII))
    }
}

/**
 * The library's own count of what each order-entry session has received (Design.md §2, "A gap Artio
 * leaves open"; §8.21; CLAUDE.md non-negotiable 13). Artio counts a message above the expected number
 * as received as soon as it asks for a resend, and forgets the gap if the connection ends first, or
 * stalls if the client answers short. So the library tracks the last MsgSeqNum it has seen in order,
 * and asks for what is missing with its own ResendRequest: at once after a Logon whose gap is from
 * before it, otherwise after [waitMs] without progress. After [maxRequests] without progress the
 * session is logged out, saying what is missing; the gap is kept for the next Logon.
 *
 * Never rewinds Artio's count: that starts a new `sequenceIndex`, and Artio's replayer then gap-fills
 * over every message sent before it (spike (i)).
 *
 * Single-threaded, the order-entry library's thread. Allocation-free while no gap is open.
 */
class ReceiveGaps<S : Any>(
    private val store: OrderStore,
    private val view: GapSessions<S>,
    private val log: (String) -> Unit,
    private val clock: EpochClock = SystemEpochClock.INSTANCE,
    private val waitMs: Long = 1_000L,
    private val maxRequests: Int = 3,
) {
    private class Track<S>(val participantId: Long) {
        var session: S? = null

        /** [index], [inOrder] and [high] are set: from the store or from Artio, at the first acquisition. */
        var known = false
        var index = 0

        /** The last MsgSeqNum seen with nothing missing below it. */
        var inOrder = 0

        /** The highest MsgSeqNum seen, or counted by Artio at the start. */
        var high = 0

        /** The gap's last MsgSeqNum, 0 if none is open; it starts at [inOrder] + 1. */
        var gapTo = 0

        /** The gap was open when the session last stopped being active, or at the start: Artio has forgotten it. */
        var carried = false
        var askAtMs = NEVER
        var requests = 0
    }

    private val byParticipant = Long2ObjectHashMap<Track<S>>()
    private val all = ArrayList<Track<S>>()

    /** ResendRequests the library has sent of its own. */
    var requested = 0L
        private set

    /** Sessions logged out with a gap still open after [maxRequests]. */
    var loggedOut = 0L
        private set

    /** The first MsgSeqNum of the participant's open gap, 0 if none: for tests and `/status`. */
    fun gapFrom(participantId: Long): Int = byParticipant.get(participantId)?.let { if (it.gapTo == 0) 0 else it.inOrder + 1 } ?: 0

    fun gapTo(participantId: Long): Int = byParticipant.get(participantId)?.gapTo ?: 0

    /** The library has the participant's session, online or offline: counts start from the store or from Artio. */
    fun acquired(participantId: Long, session: S) {
        val t = track(participantId)
        t.session = session
        if (!t.known || view.sequenceIndex(session) != t.index) start(t, session)
    }

    /** The participant's client has logged on: its Logon counts, and a gap from before it is asked for at once. */
    fun loggedOn(participantId: Long, session: S) {
        val t = track(participantId)
        t.session = session
        if (!t.known || view.sequenceIndex(session) > t.index) start(t, session)
        val logon = view.lastReceived(session)
        seen(t, logon, logon, valid = true)
        t.requests = 0
        if (t.gapTo != 0) {
            t.askAtMs = if (t.carried) clock.time() else clock.time() + waitMs
        }
        t.carried = false
    }

    /** The participant's session stopped: a gap open now is one Artio will not ask for again. */
    fun disconnected(participantId: Long) {
        val t = byParticipant.get(participantId) ?: return
        if (t.gapTo != 0) t.carried = true
        t.askAtMs = NEVER
    }

    /**
     * A message Artio handed the library on the participant's session, [valid] or not, session
     * messages included. Not one the startup replay brings: those are the archive's, already counted.
     */
    fun onMessage(participantId: Long, sequenceIndex: Int, buffer: DirectBuffer, offset: Int, length: Int, valid: Boolean) {
        val t = byParticipant.get(participantId) ?: return
        if (!t.known || sequenceIndex < t.index) return
        if (!scan(buffer, offset, length)) return
        if (sequenceIndex > t.index) {
            // A reset (§3.3): the gap's numbers are gone with its index.
            reset(t, sequenceIndex, scannedSeqNum - 1)
        }
        if (scannedIsSequenceReset && valid) {
            val end = scannedNewSeqNo - 1
            if (!scannedGapFill && end > t.inOrder) {
                // Reset mode: the client's numbers move on, whatever MsgSeqNum it carries.
                t.inOrder = end
                progress(t)
                if (t.gapTo != 0 && t.inOrder >= t.gapTo) close(t)
                if (t.high < t.inOrder) t.high = t.inOrder
                return
            }
            seen(t, scannedSeqNum, maxOf(scannedSeqNum, end), valid)
            return
        }
        seen(t, scannedSeqNum, scannedSeqNum, valid)
    }

    /**
     * A message the startup replay brings (§8.17) that the library dropped: not PossDup, numbered within
     * the participant's stored gap at its `sequenceIndex`. Artio's archive holds every inbound message,
     * the ones it marked invalid included, and the replay hands them back as valid. One the library
     * dropped never reached the exchange, and is asked for again after the Logon; rebuilt in flight, it
     * would be rejected "not sequenced" while its resend counts as a duplicate.
     */
    fun droppedBeforeRestart(participantId: Long, sequenceIndex: Int, buffer: DirectBuffer, offset: Int, length: Int): Boolean {
        val from = store.receiveGapFrom(participantId)
        if (from == 0 || store.receiveGapIndex(participantId) != sequenceIndex) return false
        if (!scan(buffer, offset, length) || scannedPossDup) return false
        return scannedSeqNum in from..store.receiveGapTo(participantId)
    }

    /** Asks for what is still missing, or logs the session out after [maxRequests]. */
    fun doWork(): Int {
        var work = 0
        val now = clock.time()
        for (i in 0 until all.size) {
            val t = all[i]
            if (t.gapTo == 0 || now < t.askAtMs) continue
            val session = t.session ?: continue
            if (!view.isActive(session)) continue
            val from = t.inOrder + 1
            if (t.requests >= maxRequests) {
                loggedOut++
                val text = "messages $from to ${t.gapTo} not received; resend them"
                log("order entry: participant ${t.participantId}: $text; logged out after ${t.requests} ResendRequests (Design.md §2)")
                view.logout(session, text)
                t.requests = 0
                t.askAtMs = NEVER
                work++
                continue
            }
            if (view.resendRequest(session, from, t.gapTo) > 0) {
                requested++
                t.requests++
                t.askAtMs = now + waitMs
                log("order entry: participant ${t.participantId}: messages $from to ${t.gapTo} missing; ResendRequest ${t.requests} (Design.md §2)")
                work++
            }
        }
        return work
    }

    // ------------------------------------------------------------------ counting

    private fun start(t: Track<S>, session: S) {
        val index = view.sequenceIndex(session)
        val received = view.lastReceived(session)
        t.known = true
        t.index = index
        t.requests = 0
        t.askAtMs = NEVER
        val from = store.receiveGapFrom(t.participantId)
        if (from != 0 && store.receiveGapIndex(t.participantId) == index) {
            t.inOrder = from - 1
            t.gapTo = store.receiveGapTo(t.participantId)
            t.high = maxOf(t.gapTo, received)
            t.carried = true
        } else {
            if (from != 0) store.clearReceiveGap(t.participantId)
            t.inOrder = received
            t.high = received
            t.gapTo = 0
            t.carried = false
        }
    }

    private fun reset(t: Track<S>, index: Int, inOrder: Int) {
        if (t.gapTo != 0) {
            log("order entry: participant ${t.participantId}: messages ${t.inOrder + 1} to ${t.gapTo} dropped with sequenceIndex ${t.index}, reset to $index")
            store.clearReceiveGap(t.participantId)
        }
        t.index = index
        t.inOrder = maxOf(inOrder, 0)
        t.high = t.inOrder
        t.gapTo = 0
        t.carried = false
        t.requests = 0
        t.askAtMs = NEVER
    }

    /** MsgSeqNums [seqNum] to [end] seen: one message, or a gap fill's range. */
    private fun seen(t: Track<S>, seqNum: Int, end: Int, valid: Boolean) {
        if (seqNum <= t.inOrder) return
        if (seqNum == t.inOrder + 1) {
            t.inOrder = end
            if (t.gapTo != 0) {
                progress(t)
                if (t.inOrder >= t.gapTo) close(t)
            }
            if (t.high < t.inOrder) t.high = t.inOrder
            return
        }
        if (!valid) {
            // Dropped above the next expected number: missing, itself included.
            grow(t, end)
            return
        }
        // Valid above every number seen: Artio counted the ones before it, which the library has not seen.
        val top = maxOf(t.gapTo, t.high)
        if (seqNum > top + 1) grow(t, seqNum - 1)
        if (t.high < end) t.high = end
    }

    private fun grow(t: Track<S>, to: Int) {
        if (to <= t.gapTo) return
        val opened = t.gapTo == 0
        t.gapTo = to
        store.saveReceiveGap(t.participantId, t.index, t.inOrder + 1, to)
        if (opened) {
            t.requests = 0
            t.askAtMs = clock.time() + waitMs
        }
    }

    private fun progress(t: Track<S>) {
        t.requests = 0
        t.askAtMs = clock.time() + waitMs
    }

    private fun close(t: Track<S>) {
        if (t.inOrder < t.high) t.inOrder = t.high
        t.gapTo = 0
        t.carried = false
        t.askAtMs = NEVER
        store.clearReceiveGap(t.participantId)
    }

    private fun track(participantId: Long): Track<S> =
        byParticipant.get(participantId) ?: Track<S>(participantId).also {
            byParticipant.put(participantId, it)
            all += it
        }

    // ------------------------------------------------------------------ the header, read in place

    private var scannedSeqNum = 0
    private var scannedIsSequenceReset = false
    private var scannedGapFill = false
    private var scannedNewSeqNo = 0
    private var scannedPossDup = false

    /** Reads MsgSeqNum, and a SequenceReset's GapFillFlag and NewSeqNo. False if there is no MsgSeqNum. */
    private fun scan(buffer: DirectBuffer, offset: Int, length: Int): Boolean {
        scannedSeqNum = 0
        scannedIsSequenceReset = false
        scannedGapFill = false
        scannedNewSeqNo = 0
        scannedPossDup = false
        val end = offset + length
        var i = offset
        while (i < end) {
            var tag = 0
            while (i < end) {
                val b = buffer.getByte(i++).toInt()
                if (b == '='.code) break
                tag = tag * 10 + (b - '0'.code)
            }
            val start = i
            while (i < end && buffer.getByte(i) != SOH) i++
            when (tag) {
                MSG_TYPE -> scannedIsSequenceReset = i - start == 1 && buffer.getByte(start) == '4'.code.toByte()
                MSG_SEQ_NUM -> scannedSeqNum = digits(buffer, start, i)
                NEW_SEQ_NO -> scannedNewSeqNo = digits(buffer, start, i)
                GAP_FILL_FLAG -> scannedGapFill = i - start == 1 && buffer.getByte(start) == 'Y'.code.toByte()
                POSS_DUP_FLAG -> scannedPossDup = i - start == 1 && buffer.getByte(start) == 'Y'.code.toByte()
            }
            i++ // the SOH
        }
        return scannedSeqNum > 0
    }

    private fun digits(buffer: DirectBuffer, from: Int, to: Int): Int {
        var value = 0
        for (k in from until to) {
            val d = buffer.getByte(k) - '0'.code.toByte()
            if (d !in 0..9) return 0
            value = value * 10 + d
        }
        return value
    }

    private companion object {
        const val NEVER = Long.MAX_VALUE
        const val SOH: Byte = 1
        const val MSG_TYPE = 35
        const val MSG_SEQ_NUM = 34
        const val NEW_SEQ_NO = 36
        const val GAP_FILL_FLAG = 123
        const val POSS_DUP_FLAG = 43
    }
}
