package nl.lamia.most.exchange.fixadapter.orderentry.delivery

import nl.lamia.most.exchange.client.ClOrdId
import nl.lamia.most.exchange.client.OrderFate
import nl.lamia.most.exchange.fixadapter.orderentry.store.InsertResult
import nl.lamia.most.exchange.fixadapter.orderentry.store.OrderStore
import nl.lamia.most.exchange.fixadapter.orderentry.store.RequestRow
import nl.lamia.most.exchange.fixadapter.orderentry.store.RowKind
import nl.lamia.most.exchange.sbe.Side
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A `reportSeq` consumed only behind the archive (Design.md §6.2, CLAUDE.md non-negotiable 7): the
 * durable first-missing is the lowest of the SDK's ceiling, the lowest `reportSeq` whose 35=8 the
 * archive has not reached, and what was unconfirmed at a library reconnect. Checked against what the
 * store holds after a restart, as the next process would read it.
 */
class ReportDeliveryTest {

    @TempDir
    lateinit var dir: File

    private lateinit var store: OrderStore
    private lateinit var delivery: ReportDelivery

    /** The archive's recorded position of the library's stream; -1 unknown. */
    private var archived = -1L

    @BeforeTest
    fun open() {
        store = OrderStore.open(dir).also { it.load(TODAY) }
        delivery = ReportDelivery(store) { archived }
    }

    @AfterTest
    fun close() = store.close()

    /** What the next process reads: the store closed and opened again. */
    private fun stored(participantId: Long = P, shardId: Int = 0): Long {
        store.close()
        open()
        return store.firstMissing(participantId, shardId)
    }

    private fun save(firstMissing: Long, shardId: Int = 0, participantId: Long = P) =
        delivery.sessionStore(shardId).save(participantId, firstMissing)

    private fun sent(reportSeq: Long, position: Long, shardId: Int = 0, participantId: Long = P) =
        delivery.sent(shardId, participantId, reportSeq, position)

    @Test
    fun `the first missing reportSeq stays below every report whose 35=8 the archive has not reached`() {
        sent(1, 100)
        sent(2, 200)
        sent(3, 300)
        save(4)
        archived = 150
        delivery.doWork()
        assertEquals(2L, delivery.durable(0, P), "1 is archived; 2's 35=8 at 200 is not")
        archived = 299
        delivery.doWork()
        assertEquals(3L, delivery.durable(0, P))
        archived = 300
        delivery.doWork()
        assertEquals(4L, stored(), "all three archived: the SDK's own")
    }

    @Test
    fun `nothing passes the SDK's ceiling, however far the archive is`() {
        sent(1, 100)
        sent(2, 200)
        save(2) // 2 not counted by the SDK yet
        archived = 10_000
        delivery.doWork()
        assertEquals(2L, stored())
    }

    @Test
    fun `an unknown archive position confirms nothing`() {
        sent(1, 100)
        save(2)
        archived = -1
        delivery.doWork()
        assertEquals(1L, stored())
    }

    @Test
    fun `a report filling a gap holds the sequence at its own reportSeq until archived`() {
        // 1, 2, 4, 5 live; 3 comes last, by the fence's resend (most-exchange Adapters.md §4).
        sent(1, 100)
        sent(2, 200)
        sent(4, 300)
        sent(5, 400)
        sent(3, 500)
        save(6)
        archived = 250
        delivery.doWork()
        assertEquals(3L, delivery.durable(0, P), "3 waits behind 4 and 5, taken earlier: the lowest, not the oldest")
        archived = 400
        delivery.doWork()
        assertEquals(3L, delivery.durable(0, P), "4 and 5 are archived, 3 is not")
        archived = 500
        delivery.doWork()
        assertEquals(6L, stored())
    }

    @Test
    fun `a mass status's nextSeq moves it on at once when nothing waits, and not past what does`() {
        // The status stands in for the reports the ring lost: they were never delivered, so none waits.
        save(10)
        delivery.doWork()
        assertEquals(10L, delivery.durable(0, P))
        sent(12, 100)
        save(20)
        archived = 50
        delivery.doWork()
        assertEquals(12L, stored())
    }

    @Test
    fun `each shard and participant has its own sequence`() {
        sent(1, 100, shardId = 0)
        sent(1, 200, shardId = 1)
        sent(1, 300, shardId = 0, participantId = Q)
        save(2, shardId = 0)
        save(2, shardId = 1)
        save(2, shardId = 0, participantId = Q)
        archived = 200
        delivery.doWork()
        assertEquals(2L, delivery.durable(0, P))
        assertEquals(2L, delivery.durable(1, P))
        assertEquals(1L, delivery.durable(0, Q), "Q's 35=8 at 300 is not archived")
        assertEquals(2L, stored(P, 1))
        assertEquals(1L, store.firstMissing(Q, 0))
    }

    @Test
    fun `the SDK resumes each shard from that shard's stored first missing`() {
        save(5, shardId = 1)
        delivery.doWork()
        store.close()
        open()
        assertEquals(5L, delivery.sessionStore(1).firstMissing(P))
        assertEquals(1L, delivery.sessionStore(0).firstMissing(P))
    }

    @Test
    fun `a 35=8 taken before a library reconnect never moves it, until a restart`() {
        // §6.2: a position is compared only with the counter of the connection it was written on.
        sent(1, 100)
        sent(2, 200)
        save(3)
        archived = 100
        delivery.doWork()
        assertEquals(2L, delivery.durable(0, P))
        delivery.onLibraryConnect()
        archived = 10_000 // the new connection's counter: another stream, maybe another recording
        sent(3, 50)
        save(4)
        delivery.doWork()
        assertEquals(2L, stored(), "2 was never confirmed; a restart resends from it")
    }

    @Test
    fun `a row changed by an answer with no reportSeq is written once the archive has it`() {
        val row = order(1L)
        row.ordStatus = '8'.code.toByte()
        row.owed = null // answered NEVER_SEQUENCED, in memory
        delivery.writeWhenArchived(row, 300)
        archived = 299
        delivery.doWork()
        store.close()
        open()
        assertEquals(OrderFate.NEVER_SEQUENCED, assertNotNull(store.find(P, 1L)).owed, "still owed as stored")
        val again = assertNotNull(store.find(P, 1L))
        again.ordStatus = '8'.code.toByte()
        again.owed = null
        delivery.writeWhenArchived(again, 300)
        archived = 300
        delivery.doWork()
        store.close()
        open()
        assertNull(assertNotNull(store.find(P, 1L)).owed)
        assertEquals('8'.code.toByte(), store.find(P, 1L)?.ordStatus)
    }

    @Test
    fun `a row waiting at a library reconnect is not written, so it is answered again after a restart`() {
        val row = order(1L)
        row.owed = null
        delivery.writeWhenArchived(row, 100)
        delivery.onLibraryConnect()
        archived = 10_000
        delivery.doWork()
        assertEquals(1L, delivery.rowsDropped)
        store.close()
        open()
        assertEquals(OrderFate.NEVER_SEQUENCED, store.find(P, 1L)?.owed)
    }

    @Test
    fun `more reports waiting than the rings first hold are all kept`() {
        for (seq in 1L..5_000L) sent(seq, seq * 10)
        save(5_001)
        archived = 25_000
        delivery.doWork()
        assertEquals(2_501L, delivery.durable(0, P))
        for (k in 1L..3_000L) delivery.writeWhenArchived(order(k).also { it.owed = null }, 50_000 + k)
        archived = 51_000
        delivery.doWork()
        assertEquals(5_001L, delivery.durable(0, P))
        store.close()
        open()
        assertEquals(1_000, (1L..3_000L).count { store.find(P, it)?.owed == null })
    }

    @Test
    fun `reports on their way to the archive allocate nothing`() {
        var seq = 1L
        var position = 0L
        val sdk = delivery.sessionStore(0)
        fun run(count: Int) {
            for (i in 0 until count) {
                position += 200
                sent(seq++, position)
                sdk.save(P, seq)
                archived = position - 1_000
                delivery.doWork()
            }
        }
        run(20_000) // the rings grown, the maps filled
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        // As the other paths: an allocation per report shows in every window; one of five at 0 is the claim.
        val windows = (0 until 5).map {
            val before = threads.currentThreadAllocatedBytes
            run(2_000)
            threads.currentThreadAllocatedBytes - before
        }
        assertTrue(0L in windows, "bytes allocated per window of 2,000 reports: $windows")
    }

    /** The row of [order]`(n)`, by its ClOrdID (Design.md §6.1). */
    private fun OrderStore.find(participantId: Long, n: Long): RequestRow? = find(participantId, ClOrdId.of("ORD-$n"))

    private fun order(n: Long): RequestRow = store.newRow().apply {
        kind = RowKind.ORDER
        participantId = P
        position = n
        fixClOrdId("ORD-$n")
        entryTradingDate = TODAY
        securityId = 42
        shardId = 0
        side = Side.BUY
        price = 100_00000000L
        origQty = 1_00000000L
        leavesQty = 1_00000000L
        timeInForce = '0'.code.toByte()
        owed = OrderFate.NEVER_SEQUENCED
    }.also { assertEquals(InsertResult.NEW, store.insert(it)) }

    private companion object {
        const val P = 1001L
        const val Q = 1002L
        const val TODAY = 20261026
    }
}
