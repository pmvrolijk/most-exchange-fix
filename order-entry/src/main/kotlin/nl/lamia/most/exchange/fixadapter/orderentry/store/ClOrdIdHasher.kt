package nl.lamia.most.exchange.fixadapter.orderentry.store

import org.agrona.DirectBuffer

/**
 * Hashes a FIX ClOrdID from the message's bytes, allocation-free (Design.md §6.1). Lookups compare
 * bytes along the hash's chain, so a collision costs a step, never a false duplicate. A test passes
 * one that always collides.
 */
internal fun interface ClOrdIdHasher {
    fun hash(buffer: DirectBuffer, offset: Int, length: Int): Long

    companion object {
        /** FNV-1a, then MurmurHash3's finaliser to spread FNV's weak low bits. */
        val DEFAULT = ClOrdIdHasher { buffer, offset, length ->
            var h = -0x340d631b7bdddcdbL // FNV-1a 64 offset basis
            for (i in 0 until length) {
                h = (h xor (buffer.getByte(offset + i).toLong() and 0xff)) * 0x100000001b3L
            }
            h = (h xor (h ushr 33)) * -0xae502812aa7333L
            h = (h xor (h ushr 33)) * -0x3b314601e57a13adL
            h xor (h ushr 33)
        }
    }
}
