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
    fun `the dictionary holds the session messages, 35=j and the order-entry types, and no others`() {
        val types = Constants::class.java.fields
            .filter { it.name.endsWith("_MESSAGE_AS_STR") }
            .map { it.get(null) as String }
            .toSet()
        assertEquals(setOf("0", "1", "2", "3", "4", "5", "A", "j", "D", "F", "G", "H", "AF", "8", "9"), types)
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
