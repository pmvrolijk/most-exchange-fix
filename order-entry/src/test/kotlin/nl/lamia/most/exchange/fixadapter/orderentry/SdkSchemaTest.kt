package nl.lamia.most.exchange.fixadapter.orderentry

import nl.lamia.most.exchange.client.ClOrdId
import nl.lamia.most.exchange.sbe.NewOrderSingleDecoder
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The adapter speaks the exchange's schema version 8 (Design.md §8.30). The SDK is published under one
 * `0.1.0-SNAPSHOT` whatever its schema, so a republish can move the wire under an unchanged build file.
 * Version 8 changed orders, cancels and reports in place: an exchange on another version drops what
 * the adapter sends. This fails instead, so a move is read in most-exchange's Adapters.md first.
 */
class SdkSchemaTest {

    @Test
    fun `the SDK on the classpath speaks schema version 8`() {
        assertEquals(8, NewOrderSingleDecoder.SCHEMA_VERSION)
        assertEquals(8, ClOrdId.SINCE_SCHEMA_VERSION)
    }
}
