package nl.lamia.most.exchange.fixadapter.tools

/** One line per application message, with names a script can grep for (`ExecType=TRADE`). */
object Describe {
    val EXEC_TYPES = mapOf(
        "0" to "NEW", "4" to "CANCELED", "5" to "REPLACED", "6" to "PENDING_CANCEL", "8" to "REJECTED",
        "A" to "PENDING_NEW", "C" to "EXPIRED", "F" to "TRADE", "I" to "ORDER_STATUS", "3" to "DONE_FOR_DAY",
    )
    val ORD_STATUSES = mapOf(
        "0" to "NEW", "1" to "PARTIALLY_FILLED", "2" to "FILLED", "3" to "DONE_FOR_DAY", "4" to "CANCELED",
        "6" to "PENDING_CANCEL", "8" to "REJECTED", "A" to "PENDING_NEW", "C" to "EXPIRED", "E" to "PENDING_REPLACE",
    )
    private val SIDES = mapOf("1" to "BUY", "2" to "SELL")
    private val SESSION_TYPES = setOf("0", "1", "2", "4", "A")

    fun isSessionLevel(m: FixMessage) = m.msgType in SESSION_TYPES

    fun line(m: FixMessage): String {
        val parts = ArrayList<String>()
        fun add(name: String, tag: Int, names: Map<String, String>? = null) {
            val v = m[tag] ?: return
            parts += "$name=${names?.get(v) ?: v}"
        }
        when (m.msgType) {
            "8" -> {
                parts += "ExecutionReport"
                add("ClOrdID", 11); add("OrigClOrdID", 41); add("OrderID", 37); add("ExecID", 17)
                add("ExecType", 150, EXEC_TYPES); add("OrdStatus", 39, ORD_STATUSES); add("Side", 54, SIDES)
                add("Symbol", 55); add("OrderQty", 38); add("Price", 44)
                add("LastQty", 32); add("LastPx", 31); add("CumQty", 14); add("LeavesQty", 151); add("AvgPx", 6)
                add("OrdRejReason", 103); add("OrdStatusUnknown", 5001)
                add("OrdStatusReqID", 790); add("MassStatusReqID", 584); add("TotNumReports", 911); add("LastRptRequested", 912)
                add("Text", 58)
            }
            "9" -> {
                parts += "OrderCancelReject"
                add("ClOrdID", 11); add("OrigClOrdID", 41); add("OrderID", 37); add("OrdStatus", 39, ORD_STATUSES)
                add("CxlRejReason", 102); add("Text", 58)
            }
            "3" -> {
                parts += "Reject"
                add("RefSeqNum", 45); add("RefTagID", 371); add("RefMsgType", 372); add("SessionRejectReason", 373); add("Text", 58)
            }
            "j" -> {
                parts += "BusinessMessageReject"
                add("RefSeqNum", 45); add("RefMsgType", 372); add("BusinessRejectRefID", 379); add("BusinessRejectReason", 380); add("Text", 58)
            }
            "5" -> {
                parts += "Logout"
                add("MsgSeqNum", 34); add("Text", 58)
            }
            else -> parts += "35=${m.msgType}"
        }
        if (m[43] == "Y") parts += "PossDup"
        return parts.joinToString(" ")
    }
}
