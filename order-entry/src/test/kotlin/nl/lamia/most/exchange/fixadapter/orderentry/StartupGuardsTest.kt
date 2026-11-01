package nl.lamia.most.exchange.fixadapter.orderentry

import nl.lamia.most.exchange.fixadapter.orderentry.store.OrderStore
import nl.lamia.most.exchange.fixadapter.orderentry.store.RowKind
import nl.lamia.most.exchange.fixadapter.session.ConfiguredSession
import nl.lamia.most.exchange.fixadapter.session.ConfiguredSessions
import nl.lamia.most.exchange.fixadapter.session.Role
import uk.co.real_logic.artio.engine.SessionInfo
import uk.co.real_logic.artio.session.CompositeKey
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Design.md §3.2, "Artio's state lost": found before the bind, from Artio's own list of the sessions
 * it knows. A configured order-entry session Artio does not know while the store holds positions of
 * the participant's epoch, or knows below the stored `sequenceIndex`. Nothing else is a loss: a
 * received number below the high-water at the same `sequenceIndex` is a host crash's lost tail, which
 * the startup recovery rebuilds (§8.17).
 */
class StartupGuardsTest {

    @TempDir
    lateinit var dir: File
    private var store: OrderStore? = null

    @AfterTest
    fun close() {
        store?.close()
    }

    private fun store(): OrderStore = OrderStore.open(File(dir, "store")).also {
        it.load(TODAY)
        store = it
    }

    private fun OrderStore.order(participantId: Long, sequenceIndex: Int, msgSeqNum: Int, fixClOrdId: String) {
        insert(
            newRow().apply {
                kind = RowKind.ORDER
                this.participantId = participantId
                position = (sequenceIndex.toLong() shl 32) or msgSeqNum.toLong()
                fixClOrdId(fixClOrdId)
                entryTradingDate = TODAY
                origQty = 1L
                leavesQty = 1L
            },
        )
    }

    @Test
    fun `a session Artio does not know, while the store holds a position of its participant's epoch, is a loss Artio cannot hand over`() {
        val s = store()
        s.order(P1, 0, 5, "OLD-5")
        val losses = StartupGuards.losses(known = emptyList(), SESSIONS, s)
        val loss = losses.single()
        assertEquals(P1, loss.participantId)
        assertFalse(loss.isKnown, "nothing to request offline: the new epoch starts at (0, 1) at once")
        assertTrue("does not know" in loss.why, loss.why)
    }

    @Test
    fun `a session Artio does not know is new, not a loss, while the store holds no position of the epoch`() {
        val s = store()
        assertTrue(StartupGuards.losses(emptyList(), SESSIONS, s).isEmpty(), "a fresh store")
        s.saveFirstMissing(P1, 0, 9L) // the exchange's sequence, not Artio's
        s.order(P2, 0, 5, "OLD-5")
        s.beginEpoch(P2, start = 0L) // found lost before; the client has not logged on since
        assertTrue(StartupGuards.losses(emptyList(), SESSIONS, s).isEmpty(), "a first-missing, and an earlier epoch's rows")
    }

    @Test
    fun `a session Artio knows below the stored sequenceIndex is a loss, handed over to start its epoch at Artio's position`() {
        val s = store()
        s.recordSequenceIndex(P1, 2)
        val loss = StartupGuards.losses(listOf(info(77L, CLIENT1, sequenceIndex = 1)), SESSIONS, s).single()
        assertEquals(P1, loss.participantId)
        assertTrue(loss.isKnown)
        assertEquals(77L, loss.sessionId)
        assertTrue("sequenceIndex 1" in loss.why && "stored 2" in loss.why, loss.why)
    }

    @Test
    fun `a session Artio knows at or above the stored sequenceIndex is no loss, whatever the high-water`() {
        val s = store()
        s.recordSequenceIndex(P1, 2)
        s.order(P1, 2, 900, "ORD-900") // Artio may have received less: a host crash's tail (§8.17)
        assertTrue(StartupGuards.losses(listOf(info(77L, CLIENT1, sequenceIndex = 2)), SESSIONS, s).isEmpty())
        assertTrue(StartupGuards.losses(listOf(info(77L, CLIENT1, sequenceIndex = 3)), SESSIONS, s).isEmpty())
    }

    @Test
    fun `a session whose sequenceIndex Artio does not know counts as not known`() {
        val s = store()
        s.order(P1, 0, 5, "OLD-5")
        val loss = StartupGuards.losses(listOf(info(77L, CLIENT1, SessionInfo.UNKNOWN_SEQUENCE_INDEX)), SESSIONS, s).single()
        assertFalse(loss.isKnown)
    }

    @Test
    fun `only order-entry sessions are checked, each against its own participant`() {
        val s = store()
        s.order(P2, 0, 5, "OLD-5")
        val losses = StartupGuards.losses(listOf(info(77L, CLIENT1, 0)), SESSIONS, s)
        assertEquals(listOf(P2), losses.map { it.participantId }, "CLIENT1 is known and holds nothing; MD1 is market data's")
    }

    private fun info(sessionId: Long, client: String, sequenceIndex: Int) = object : SessionInfo {
        override fun sessionId() = sessionId
        override fun sequenceIndex() = sequenceIndex
        override fun sessionKey() = object : CompositeKey {
            override fun localCompId() = ADAPTER
            override fun localSubId() = ""
            override fun localLocationId() = ""
            override fun remoteCompId() = client
            override fun remoteSubId() = ""
            override fun remoteLocationId() = ""
        }
    }

    private companion object {
        const val TODAY = 20261026
        const val P1 = 1001L
        const val P2 = 1002L
        const val ADAPTER = "MOST"
        const val CLIENT1 = "CLIENT1"
        val SESSIONS = ConfiguredSessions(
            listOf(
                ConfiguredSession("client1", ADAPTER, CLIENT1, "u1", ByteArray(32), Role.ORDER_ENTRY, P1),
                ConfiguredSession("client2", ADAPTER, "CLIENT2", "u2", ByteArray(32), Role.ORDER_ENTRY, P2),
                ConfiguredSession("md1", ADAPTER, "MD1", "u3", ByteArray(32), Role.MARKET_DATA, null),
            ),
        )
    }
}
