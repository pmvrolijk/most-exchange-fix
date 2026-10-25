package nl.lamia.most.exchange.fixadapter.orderentry.inbound

import org.agrona.AsciiSequenceView

/** FIX values read in place, without allocating (Design.md §5.1). */
internal object FixParse {
    const val INVALID_DATE = -1

    /** Digits only, at least one, > 0, fitting an `int64`; else -1. */
    fun positiveLong(view: AsciiSequenceView): Long {
        val length = view.length
        if (length == 0) return -1L
        var value = 0L
        for (i in 0 until length) {
            val digit = view[i] - '0'
            if (digit !in 0..9) return -1L
            if (value > (Long.MAX_VALUE - digit) / 10) return -1L
            value = value * 10 + digit
        }
        return if (value > 0L) value else -1L
    }

    /** A LocalMktDate, `YYYYMMDD`, as that number; [INVALID_DATE] unless 8 digits with month 1–12 and day 1–31. */
    fun localMktDate(view: AsciiSequenceView): Int {
        if (view.length != 8) return INVALID_DATE
        var value = 0
        for (i in 0 until 8) {
            val digit = view[i] - '0'
            if (digit !in 0..9) return INVALID_DATE
            value = value * 10 + digit
        }
        val month = value / 100 % 100
        val day = value % 100
        return if (month in 1..12 && day in 1..31) value else INVALID_DATE
    }
}
