package nl.lamia.most.exchange.fixadapter.marketdata

import nl.lamia.most.exchange.client.DepthFeedAssembler
import kotlin.test.Test
import kotlin.test.assertNull

/**
 * The SDK's depth feed assembler resolves, and -- as most-exchange Design.md §5 requires of every
 * consumer -- offers no book before a snapshot has made one trustworthy.
 */
class SdkWiringTest {

    @Test
    fun `no book is offered before one has been assembled`() {
        assertNull(DepthFeedAssembler().book(1))
    }
}
