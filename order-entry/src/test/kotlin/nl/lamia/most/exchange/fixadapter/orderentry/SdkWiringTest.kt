package nl.lamia.most.exchange.fixadapter.orderentry

import nl.lamia.most.exchange.client.ReportLedger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The SDK resolves -- from the sibling checkout through the composite build, or from the registry --
 * and its report ledger keeps a sequence per participant (most-exchange Design.md §5).
 */
class SdkWiringTest {

    @Test
    fun `the SDK's report ledger is on the classpath and counts a participant's sequence`() {
        val ledger = ReportLedger(longArrayOf(7L))
        ledger.accept(7L, 1L)
        ledger.accept(7L, 2L)
        assertEquals(3L, ledger.firstMissing(ledger.indexOf(7L)))
    }
}
