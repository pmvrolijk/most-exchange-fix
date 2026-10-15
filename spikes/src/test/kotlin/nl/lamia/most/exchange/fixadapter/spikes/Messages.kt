package nl.lamia.most.exchange.fixadapter.spikes

import uk.co.real_logic.artio.ExecType
import uk.co.real_logic.artio.OrdStatus
import uk.co.real_logic.artio.OrdType
import uk.co.real_logic.artio.Side
import uk.co.real_logic.artio.builder.ExecutionReportEncoder
import uk.co.real_logic.artio.builder.NewOrderSingleEncoder
import uk.co.real_logic.artio.library.SessionConfiguration

/** The messages the spikes send, from Artio's own FIX 4.4 session dictionary. */
object Messages {
    const val ACCEPTOR_COMP_ID = "MOST"
    private val transactTime = "20261015-12:00:00.000".toByteArray()

    fun newOrder(clOrdId: String): NewOrderSingleEncoder = NewOrderSingleEncoder().apply {
        clOrdID(clOrdId)
        instrument().symbol("SPIKE")
        side(Side.BUY)
        transactTime(transactTime)
        orderQtyData().orderQty(10, 0)
        ordType(OrdType.LIMIT)
        price(100_25000000L, 8) // a fixed-point price goes into FIX as is (Design §8.11)
    }

    /** A 35=8 whose ExecID names it, so each one is recognisable when it comes back on a resend. */
    fun executionReport(execId: String): ExecutionReportEncoder = ExecutionReportEncoder().apply {
        orderID("O-$execId")
        execID(execId)
        execType(ExecType.NEW)
        ordStatus(OrdStatus.NEW)
        instrument().symbol("SPIKE")
        side(Side.BUY)
    }

    fun logon(compId: String, port: Int): SessionConfiguration = SessionConfiguration.builder()
        .address("localhost", port)
        .senderCompId(compId)
        .targetCompId(ACCEPTOR_COMP_ID)
        .credentials(compId, "secret")
        .sequenceNumbersPersistent(true)
        .build()
}
