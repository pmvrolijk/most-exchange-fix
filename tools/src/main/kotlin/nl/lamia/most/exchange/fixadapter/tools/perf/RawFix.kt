package nl.lamia.most.exchange.fixadapter.tools.perf

import org.agrona.DirectBuffer

/**
 * The fields of a received message the log keeps, read off the raw bytes (Design.md §10.1): MsgType
 * (35), ClOrdID (11) and OrigClOrdID (41) as this run's indexes, ExecType (150), OrdStatus (39),
 * PossDupFlag (43) and OrdStatusUnknown (5001). Reused, and allocation-free.
 *
 * A ClOrdID is this run's when it is [prefix] then decimal digits; anything else is
 * [PerfLogFormat.NO_INDEX].
 */
class RawFix(prefix: String) {
    private val prefix = prefix.toByteArray(Charsets.US_ASCII)

    var msgType: Byte = 0; private set
    /** MsgType is more than one character (none the log keeps is). */
    var longMsgType = false; private set
    var index = PerfLogFormat.NO_INDEX; private set
    var origIndex = PerfLogFormat.NO_INDEX; private set
    var execType: Byte = PerfLogFormat.ABSENT; private set
    var ordStatus: Byte = PerfLogFormat.ABSENT; private set
    var possDup = false; private set
    var unknown = false; private set

    fun parse(buffer: DirectBuffer, offset: Int, length: Int) {
        msgType = 0
        longMsgType = false
        index = PerfLogFormat.NO_INDEX
        origIndex = PerfLogFormat.NO_INDEX
        execType = PerfLogFormat.ABSENT
        ordStatus = PerfLogFormat.ABSENT
        possDup = false
        unknown = false
        val end = offset + length
        var p = offset
        while (p < end) {
            var tag = 0
            while (p < end) {
                val c = buffer.getByte(p++).toInt()
                if (c == '='.code) break
                tag = tag * 10 + (c - '0'.code)
            }
            val start = p
            while (p < end && buffer.getByte(p) != SOH) p++
            val valueLength = p - start
            p++ // the SOH
            if (valueLength == 0) continue
            when (tag) {
                35 -> {
                    msgType = buffer.getByte(start)
                    longMsgType = valueLength > 1
                }
                11 -> index = runIndex(buffer, start, valueLength)
                41 -> origIndex = runIndex(buffer, start, valueLength)
                150 -> execType = buffer.getByte(start)
                39 -> ordStatus = buffer.getByte(start)
                43 -> possDup = buffer.getByte(start) == 'Y'.code.toByte()
                5001 -> unknown = buffer.getByte(start) == 'Y'.code.toByte()
            }
        }
    }

    private fun runIndex(buffer: DirectBuffer, start: Int, length: Int): Int {
        if (length <= prefix.size || length - prefix.size > 9) return PerfLogFormat.NO_INDEX
        for (k in prefix.indices) if (buffer.getByte(start + k) != prefix[k]) return PerfLogFormat.NO_INDEX
        var value = 0
        for (k in start + prefix.size until start + length) {
            val d = buffer.getByte(k) - '0'.code.toByte()
            if (d !in 0..9) return PerfLogFormat.NO_INDEX
            value = value * 10 + d
        }
        return value
    }

    private companion object {
        const val SOH: Byte = 1
    }
}

/** Writes [prefix] then [index] in decimal into [into]; returns the length. Allocation-free. */
fun encodeClOrdId(prefix: ByteArray, index: Int, into: ByteArray): Int {
    System.arraycopy(prefix, 0, into, 0, prefix.size)
    var digits = 1
    var v = index
    while (v >= 10) {
        v /= 10
        digits++
    }
    var p = prefix.size + digits - 1
    v = index
    do {
        into[p--] = ('0'.code + v % 10).toByte()
        v /= 10
    } while (v > 0)
    return prefix.size + digits
}
