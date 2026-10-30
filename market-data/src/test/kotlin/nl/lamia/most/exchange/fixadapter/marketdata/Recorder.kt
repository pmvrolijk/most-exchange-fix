package nl.lamia.most.exchange.fixadapter.marketdata

import uk.co.real_logic.artio.builder.Encoder
import uk.co.real_logic.artio.util.MutableAsciiBuffer

/** One FIX message as sent, its fields in order, repeating groups kept. */
typealias Sent = List<Pair<Int, String>>

fun Sent.first(tag: Int): String? = firstOrNull { it.first == tag }?.second

fun Sent.all(tag: Int): List<String> = filter { it.first == tag }.map { it.second }

/** The entries of a 35=W or 35=X: each starts at its 269 (35=W) or 279 (35=X). */
fun Sent.entries(): List<Map<Int, String>> {
    val starts = if (any { it.first == 279 }) 279 else 269
    val out = mutableListOf<MutableMap<Int, String>>()
    var inGroup = false
    for ((tag, value) in this) {
        if (tag == 268) inGroup = true
        if (!inGroup || tag == 268 || tag == 10) continue
        if (tag == starts) out += mutableMapOf()
        out.lastOrNull()?.put(tag, value)
    }
    return out
}

/** Artio's session as a backend's answers reach it, for the tests: what was sent, and refusals on demand. */
class Recorder(private val target: String = "FEED1") : FixOut {
    val sent = mutableListOf<Sent>()

    /** While true, every send is refused, as Artio refuses a back-pressured one. */
    var refusing = false
    var refuseNext = 0
    private val buffer = MutableAsciiBuffer(ByteArray(1 shl 20))

    override fun send(encoder: Encoder): Boolean {
        if (refusing) return false
        if (refuseNext > 0) {
            refuseNext--
            return false
        }
        encoder.header().senderCompID("MOST").targetCompID(target).msgSeqNum(sent.size + 2).sendingTime("20261030-12:00:00.000".toByteArray())
        val result = encoder.encode(buffer, 0)
        sent += buffer.getAscii(Encoder.offset(result), Encoder.length(result)).split('\u0001')
            .filter { '=' in it }.map { it.substringBefore('=').toInt() to it.substringAfter('=') }
        return true
    }

    fun ofType(msgType: String): List<Sent> = sent.filter { it.first(35) == msgType }
}
