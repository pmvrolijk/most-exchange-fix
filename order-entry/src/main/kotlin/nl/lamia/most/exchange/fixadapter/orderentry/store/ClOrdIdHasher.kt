package nl.lamia.most.exchange.fixadapter.orderentry.store

/**
 * Hashes a ClOrdID's three words, as the SDK's `ClOrdId` reads them (Design.md §6.1): allocation-free,
 * whichever bytes they came from, a FIX message's or a report's. Lookups compare the words along the
 * hash's chain, so a collision costs a step, never a false duplicate. A test passes one that always
 * collides.
 */
internal fun interface ClOrdIdHasher {
    fun hash(word0: Long, word1: Long, word2: Long): Long

    companion object {
        /** The SDK's own mix for its in-flight table: each word multiplied in, then the high bits folded down. */
        val DEFAULT = ClOrdIdHasher { w0, w1, w2 ->
            var h = w0 * -0x61c8864680b583ebL
            h = (h xor w1) * -0x61c8864680b583ebL
            h = (h xor w2) * -0x61c8864680b583ebL
            h xor (h ushr 29)
        }
    }
}
