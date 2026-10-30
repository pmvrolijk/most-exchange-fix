package nl.lamia.most.exchange.fixadapter.marketdata

import io.aeron.logbuffer.ControlledFragmentHandler.Action
import nl.lamia.most.exchange.client.DirectoryClient
import nl.lamia.most.exchange.fixadapter.codecs.decoder.SecurityListRequestDecoder
import nl.lamia.most.exchange.fixadapter.session.DirectoryBroadcast
import nl.lamia.most.exchange.fixadapter.session.TestSecurity
import nl.lamia.most.exchange.fixadapter.session.TestShard
import nl.lamia.most.exchange.fixadapter.session.fixMessage
import uk.co.real_logic.artio.builder.Encoder
import uk.co.real_logic.artio.util.MutableAsciiBuffer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 35=x → 35=y (Design.md §4, "35=y as built"), against the SDK's directory fed one broadcast, and a
 * recorder for Artio's session.
 */
class SecurityListsTest {

    private val shard = TestShard(0, "aeron:ipc", 1, "aeron:ipc", 2)

    private fun directory(vararg securities: TestSecurity) = DirectoryClient().also {
        if (securities.isNotEmpty()) DirectoryBroadcast(1, listOf(shard), securities.toList()).feed(it)
    }

    private fun request(type: Int = 4, seq: Int = 2): SecurityListRequestDecoder {
        val bytes = fixMessage("x", listOf(49 to "FEED1", 56 to "MOST", 34 to "$seq", 52 to "20261030-12:00:00.000", 320 to "SL-1", 559 to "$type"))
        return SecurityListRequestDecoder().apply { decode(MutableAsciiBuffer(bytes), 0, bytes.size) }
    }

    @Test
    fun `all securities are listed in securityId order, each with the fields §4 names, in one 35=y`() {
        // Ids 4 and 3: the SDK's directory is a HashMap, which hands these back as 4, 3. Ids whose
        // hash order is their numeric order would let a missing sort pass.
        val lists = SecurityLists(directory(
            TestSecurity(4, 0, "BETA", "NL0000000043", currency = "USD", name = "Beta Inc", tickSize = 500_000L),
            TestSecurity(3, 0, "ACME", "NL0000000042", currency = "EUR", name = "Acme NV", tickSize = 1_000_000L),
        ))
        val out = Recorder()

        assertEquals(Action.CONTINUE, lists.answer(1L, request(), out))

        val y = out.sent.single()
        assertEquals("y", y.first(35))
        assertEquals(listOf("SL-1", "SL-1", "0", "2", "Y", "2"), listOf(320, 322, 560, 393, 893, 146).map { y.first(it) })
        assertEquals(listOf("ACME", "BETA"), y.all(55))
        assertEquals(listOf("3", "4"), y.all(48))
        assertEquals(listOf("8", "8"), y.all(22), "SecurityIDSource 8, the exchange's securityId")
        assertEquals(listOf("NL0000000042", "NL0000000043"), y.all(455))
        assertEquals(listOf("4", "4"), y.all(456), "the ISIN as an alternative id")
        assertEquals(listOf("Acme NV", "Beta Inc"), y.all(107))
        assertEquals(listOf("EUR", "USD"), y.all(15))
        assertEquals(listOf("0.01", "0.005"), y.all(969), "the tick, from 8 implied decimals")
    }

    @Test
    fun `a universe larger than a fragment goes in 35=ys of at most that many, the last marked`() {
        val securities = (1..250).map { TestSecurity(it, 0, "S$it", "NL%010d".format(it)) }
        val out = Recorder()

        SecurityLists(directory(*securities.toTypedArray())).answer(1L, request(), out)

        assertEquals(listOf(100, 100, 50), out.sent.map { it.all(55).size })
        assertEquals(listOf("N", "N", "Y"), out.sent.map { it.first(893) })
        assertEquals(listOf("250", "250", "250"), out.sent.map { it.first(393) })
        assertEquals((1..250).map { "$it" }, out.sent.flatMap { it.all(48) })
    }

    @Test
    fun `a 35=y Artio refuses aborts the 35=x, and its redelivery goes on from the fragment refused`() {
        val securities = (1..250).map { TestSecurity(it, 0, "S$it", "NL%010d".format(it)) }
        val lists = SecurityLists(directory(*securities.toTypedArray()))
        val out = Recorder()
        val x = request()

        out.sent.clear()
        // The first fragment goes, the second is refused.
        val first = object : FixOut {
            var calls = 0
            override fun send(encoder: Encoder): Boolean = if (++calls == 2) false else out.send(encoder)
        }
        assertEquals(Action.ABORT, lists.answer(1L, x, first))
        assertEquals(Action.CONTINUE, lists.answer(1L, x, out))

        assertEquals((1..250).map { "$it" }, out.sent.flatMap { it.all(48) }, "each security once, in order")
        assertEquals(3, out.sent.size)
    }

    @Test
    fun `a SecurityListRequestType other than 4 is answered SecurityRequestResult 1, with no list`() {
        val out = Recorder()
        SecurityLists(directory(TestSecurity(42, 0, "ACME", "NL0000000042"))).answer(1L, request(type = 0), out)

        val y = out.sent.single()
        assertEquals("1", y.first(560))
        assertEquals(null, y.first(146))
    }

    @Test
    fun `before the first directory the answer is SecurityRequestResult 4, temporarily unavailable`() {
        val out = Recorder()
        SecurityLists(directory()).answer(1L, request(), out)

        val y = out.sent.single()
        assertEquals(listOf("SL-1", "4", null), listOf(320, 560, 146).map { y.first(it) })
    }
}
