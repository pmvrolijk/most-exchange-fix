package nl.lamia.most.exchange.fixadapter.session

import nl.lamia.most.exchange.fixadapter.codecs.Constants
import nl.lamia.most.exchange.fixadapter.codecs.CxlRejResponseTo
import nl.lamia.most.exchange.fixadapter.codecs.ExecType
import nl.lamia.most.exchange.fixadapter.codecs.OrdStatus
import nl.lamia.most.exchange.fixadapter.codecs.SelfMatchPreventionInstruction
import nl.lamia.most.exchange.fixadapter.codecs.Side
import uk.co.real_logic.artio.builder.Encoder
import nl.lamia.most.exchange.fixadapter.codecs.builder.ExecutionReportEncoder
import nl.lamia.most.exchange.fixadapter.codecs.builder.OrderCancelRejectEncoder
import nl.lamia.most.exchange.fixadapter.codecs.decoder.NewOrderSingleDecoder
import nl.lamia.most.exchange.fixadapter.codecs.decoder.MarketDataRequestDecoder
import nl.lamia.most.exchange.fixadapter.codecs.decoder.SecurityListRequestDecoder
import nl.lamia.most.exchange.fixadapter.codecs.builder.MarketDataIncrementalRefreshEncoder
import nl.lamia.most.exchange.fixadapter.codecs.builder.MarketDataSnapshotFullRefreshEncoder
import nl.lamia.most.exchange.fixadapter.codecs.builder.SecurityListEncoder
import uk.co.real_logic.artio.util.MutableAsciiBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The adapter's dictionary (Design.md §5.4), through the codecs Artio generates from it. The
 * decoder's `validate()` is what the backend answers a 35=3 from, so its verdict is the contract.
 */
class DictionaryTest {

    @Test
    fun `the dictionary holds the session messages, 35=j, the order-entry types and the market-data types, and no others`() {
        val types = Constants::class.java.fields
            .filter { it.name.endsWith("_MESSAGE_AS_STR") }
            .map { it.get(null) as String }
            .toSet()
        // §5.4 for order entry; §4 for market data: 35=x and V in, 35=y, W, X and Y out.
        assertEquals(setOf("0", "1", "2", "3", "4", "5", "A", "j", "D", "F", "G", "H", "AF", "8", "9", "x", "V", "y", "W", "X", "Y"), types)
    }

    // ---------------------------------------------------------------- market data, §4

    @Test
    fun `a 35=V for snapshot plus updates, every level, bids offers and trades on one symbol, is valid`() {
        val request = decodeRequest()
        assertTrue(request.validate(), "${request.rejectReason() to request.invalidTagId()}")
        assertEquals("MD-1", request.mDReqIDAsString())
        assertEquals('1', request.subscriptionRequestType())
        assertEquals(0, request.marketDepth())
        assertEquals(1, request.mDUpdateType())
        assertEquals(listOf('0', '1', '2'), request.mDEntryTypesGroupIterator().map { it.mDEntryType() })
        assertEquals(listOf("ACME"), request.relatedSymGroupIterator().map { it.symbolAsString() })
    }

    @Test
    fun `a 35=V asking for a FIX 4·4 entry type the adapter does not serve is valid, for the backend to answer with a 35=Y`() {
        val request = decodeRequest(entryTypes = listOf("4"))
        assertTrue(request.validate(), "${request.rejectReason() to request.invalidTagId()}")
    }

    @Test
    fun `a 35=V with an entry type outside FIX 4·4's code set is refused with SessionRejectReason 5 on tag 269`() {
        val request = decodeRequest(entryTypes = listOf("Z"))
        assertFalse(request.validate())
        assertEquals(VALUE_IS_INCORRECT to 269, request.rejectReason() to request.invalidTagId())
    }

    @Test
    fun `a 35=x for all securities is valid`() {
        val bytes = fixMessage("x", HEADER + listOf(320 to "SL-1", 559 to "4"))
        val request = SecurityListRequestDecoder().apply { decode(MutableAsciiBuffer(bytes), 0, bytes.size) }
        assertTrue(request.validate(), "${request.rejectReason() to request.invalidTagId()}")
        assertEquals(4, request.securityListRequestType())
    }

    @Test
    fun `35=W and 35=X carry their entries as FIX 4·4 numbers them`() {
        val snapshot = MarketDataSnapshotFullRefreshEncoder().apply {
            header().senderCompID("MOST").targetCompID("MD1").msgSeqNum(2).sendingTime(SENDING_TIME.toByteArray())
            mDReqID("MD-1")
            instrument().symbol("ACME")
            mDFullGrp().mDEntriesGroup(1).mDEntryType('0').mDEntryPx(10025L, 2).mDEntrySize(30L, 0).numberOfOrders(2)
        }
        val w = encode(snapshot)
        assertEquals(listOf("MD-1", "ACME", "1", "0", "100.25", "30", "2"), listOf(262, 55, 268, 269, 270, 271, 346).map { w[it] })

        val incremental = MarketDataIncrementalRefreshEncoder().apply {
            header().senderCompID("MOST").targetCompID("MD1").msgSeqNum(3).sendingTime(SENDING_TIME.toByteArray())
            mDReqID("MD-1")
            mDIncGrp().mDEntriesGroup(1).mDUpdateAction('2').mDEntryType('1').mDEntryPx(10050L, 2).apply { instrument().symbol("ACME") }
        }
        val x = encode(incremental)
        assertEquals(listOf("MD-1", "1", "2", "1", "100.5", "ACME"), listOf(262, 268, 279, 269, 270, 55).map { x[it] })
    }

    @Test
    fun `a 35=y entry carries the tick as MinPriceIncrement, tag 969 from FIX 5·0, beside FIX 4·4's instrument fields`() {
        val list = SecurityListEncoder().apply {
            header().senderCompID("MOST").targetCompID("MD1").msgSeqNum(2).sendingTime(SENDING_TIME.toByteArray())
            securityReqID("SL-1").securityResponseID("SL-1").securityRequestResult(0).totNoRelatedSym(1).lastFragment(true)
            secListGrp().relatedSymGroup(1).apply {
                instrument().symbol("ACME").securityID("42").securityIDSource("8").securityDesc("Acme NV")
                    .secAltIDGrp().securityAltIDGroup(1).securityAltID("NL0000000042").securityAltIDSource("4")
                currency("EUR")
                minPriceIncrement(1L, 2)
            }
        }
        val y = encode(list)
        assertEquals(
            listOf("SL-1", "0", "1", "ACME", "42", "8", "1", "NL0000000042", "4", "Acme NV", "EUR", "0.01"),
            listOf(320, 560, 146, 55, 48, 22, 454, 455, 456, 107, 15, 969).map { y[it] },
        )
    }

    private fun decodeRequest(entryTypes: List<String> = listOf("0", "1", "2")): MarketDataRequestDecoder {
        val body = listOf(262 to "MD-1", 263 to "1", 264 to "0", 265 to "1", 267 to "${entryTypes.size}") +
            entryTypes.map { 269 to it } + listOf(146 to "1", 55 to "ACME")
        val bytes = fixMessage("V", HEADER + body)
        return MarketDataRequestDecoder().apply { decode(MutableAsciiBuffer(bytes), 0, bytes.size) }
    }

    @Test
    fun `2964 carries the two instructions the exchange has, 1 CancelAggressive and 2 CancelPassive`() {
        for ((value, expected) in listOf("1" to SelfMatchPreventionInstruction.CANCEL_AGGRESSIVE, "2" to SelfMatchPreventionInstruction.CANCEL_PASSIVE)) {
            val order = decodeOrder(2964 to value)
            assertTrue(order.validate(), "2964=$value: ${order.rejection()}")
            assertEquals(expected, order.selfMatchPreventionInstructionAsEnum())
        }
    }

    @Test
    fun `2964=3, FIX Latest's CancelAggressivePassive, is refused with SessionRejectReason 5 on tag 2964`() {
        val order = decodeOrder(2964 to "3")
        assertFalse(order.validate())
        assertEquals(VALUE_IS_INCORRECT to 2964, order.rejection())
    }

    @Test
    fun `2362 is a string, as FIX Latest defines it`() {
        val order = decodeOrder(2362 to "SMP-7")
        assertTrue(order.validate(), "${order.rejection()}")
        assertEquals("SMP-7", order.selfMatchPreventionIDAsString())
    }

    @Test
    fun `a standard FIX 4·4 field the adapter does not read is accepted and ignored`() {
        val order = decodeOrder(1 to "ACC-1", 58 to "free text", 21 to "1")
        assertTrue(order.validate(), "${order.rejection()}")
    }

    @Test
    fun `a FIX 4·4 tag not defined for 35=D is refused with SessionRejectReason 2`() {
        val order = decodeOrder(41 to "ORIG")
        assertFalse(order.validate())
        assertEquals(TAG_NOT_DEFINED_FOR_THIS_MESSAGE_TYPE to 41, order.rejection())
    }

    @Test
    fun `a tag the dictionary does not define is refused with SessionRejectReason 0`() {
        val order = decodeOrder(9999 to "X")
        assertFalse(order.validate())
        assertEquals(INVALID_TAG_NUMBER to 9999, order.rejection())
    }

    @Test
    fun `a value outside a FIX 4·4 code set is refused with SessionRejectReason 5`() {
        val order = decodeOrder(54 to "Z", replace = true)
        assertFalse(order.validate())
        assertEquals(VALUE_IS_INCORRECT to 54, order.rejection())
    }

    @Test
    fun `35=8 carries 2362 and 2964 echoed, and OrdStatusUnknown=Y as tag 5001`() {
        val report = ExecutionReportEncoder().apply {
            header().senderCompID("MOST").targetCompID("CLIENT1").msgSeqNum(7).sendingTime(SENDING_TIME.toByteArray())
            orderID("42")
            execID("1001")
            execType(ExecType.ORDER_STATUS)
            ordStatus(OrdStatus.NEW)
            instrument().symbol("X")
            side(Side.BUY)
            leavesQty(10, 0)
            cumQty(0, 0)
            avgPx(0, 0)
            selfMatchPreventionID("SMP-7")
            selfMatchPreventionInstruction(SelfMatchPreventionInstruction.CANCEL_PASSIVE)
            ordStatusUnknown(true)
        }
        val fields = encode(report)
        assertEquals("SMP-7", fields[2362])
        assertEquals("2", fields[2964])
        assertEquals("Y", fields[5001])
    }

    @Test
    fun `35=9 carries OrdStatusUnknown=Y as tag 5001`() {
        val reject = OrderCancelRejectEncoder().apply {
            header().senderCompID("MOST").targetCompID("CLIENT1").msgSeqNum(8).sendingTime(SENDING_TIME.toByteArray())
            orderID("42")
            clOrdID("CXL-1")
            origClOrdID("ORD-1")
            ordStatus(OrdStatus.NEW)
            cxlRejResponseTo(CxlRejResponseTo.ORDER_CANCEL_REQUEST)
            ordStatusUnknown(true)
        }
        assertEquals("Y", encode(reject)[5001])
    }

    private fun decodeOrder(vararg extra: Pair<Int, String>, replace: Boolean = false): NewOrderSingleDecoder {
        val base = listOf(
            11 to "ORD-1", 55 to "X", 54 to "1", 60 to SENDING_TIME, 38 to "10", 40 to "2", 44 to "100.25",
        )
        val body = if (replace) base.map { (tag, value) -> tag to (extra.toMap()[tag] ?: value) } else base + extra
        val bytes = fixMessage("D", HEADER + body)
        return NewOrderSingleDecoder().apply { decode(MutableAsciiBuffer(bytes), 0, bytes.size) }
    }

    private fun NewOrderSingleDecoder.rejection() = rejectReason() to invalidTagId()

    private fun encode(encoder: Encoder): Map<Int, String> {
        val buffer = MutableAsciiBuffer(ByteArray(8192))
        val result = encoder.encode(buffer, 0)
        return FixMessage.parse(buffer.getAscii(Encoder.offset(result), Encoder.length(result))).fields
    }

    companion object {
        // FIX 4.4 SessionRejectReason (373).
        const val INVALID_TAG_NUMBER = 0
        const val TAG_NOT_DEFINED_FOR_THIS_MESSAGE_TYPE = 2
        const val VALUE_IS_INCORRECT = 5

        const val SENDING_TIME = "20261020-12:00:00.000"
        val HEADER = listOf(49 to "CLIENT1", 56 to "MOST", 34 to "2", 52 to SENDING_TIME)
    }
}
