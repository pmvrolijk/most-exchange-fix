package nl.lamia.most.exchange.fixadapter.session

import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The sessions file of Design.md §2, "Configuration and logon". */
class SessionsConfigTest {

    private val digest = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08" // SHA-256 of "test"

    private fun sessions(vararg lines: String): ConfiguredSessions =
        ConfiguredSessions.parse(Properties().apply { load(lines.joinToString("\n").reader()) }, "test")

    private val oe1 = arrayOf(
        "session.OE1.senderCompId=MOST", "session.OE1.targetCompId=CLIENT1", "session.OE1.username=client1",
        "session.OE1.passwordSha256=$digest", "session.OE1.role=order-entry", "session.OE1.participantId=1001",
    )
    private val md1 = arrayOf(
        "session.MD1.senderCompId=MOST", "session.MD1.targetCompId=FEED1", "session.MD1.username=feed1",
        "session.MD1.passwordSha256=$digest", "session.MD1.role=market-data",
    )

    @Test
    fun `each session is found by the adapter's CompID and the client's, with its role and participant`() {
        val all = sessions(*oe1, *md1)
        val oe = all.find(senderCompId = "MOST", targetCompId = "CLIENT1")!!
        assertEquals(Role.ORDER_ENTRY, oe.role)
        assertEquals(1001L, oe.participantId)
        assertEquals("client1", oe.username)
        val md = all.find(senderCompId = "MOST", targetCompId = "FEED1")!!
        assertEquals(Role.MARKET_DATA, md.role)
        assertNull(md.participantId)
        assertNull(all.find(senderCompId = "CLIENT1", targetCompId = "MOST"), "CompIDs are not interchangeable")
    }

    @Test
    fun `an order-entry session without a participantId is refused`() =
        refused("participantId", *oe1.filterNot { "participantId" in it }.toTypedArray())

    @Test
    fun `a market-data session with a participantId is refused`() =
        refused("participantId", *md1, "session.MD1.participantId=1001")

    @Test
    fun `two sessions with the same CompIDs are refused`() =
        refused("CLIENT1", *oe1, *oe1.map { it.replace("OE1", "OE2").replace("1001", "1002") }.toTypedArray())

    @Test
    fun `two order-entry sessions for one participant are refused -- reports route back one to one`() =
        refused("1001", *oe1, *oe1.map { it.replace("OE1", "OE2").replace("CLIENT1", "CLIENT2") }.toTypedArray())

    @Test
    fun `a passwordSha256 that is not 64 hex digits is refused`() {
        refused("passwordSha256", *oe1.map { it.replace(digest, digest.dropLast(1)) }.toTypedArray())
        refused("passwordSha256", *oe1.map { it.replace(digest, digest.dropLast(1) + "g") }.toTypedArray())
    }

    @Test
    fun `an unknown role is refused`() = refused("role", *oe1.map { it.replace("order-entry", "drop-copy") }.toTypedArray())

    @Test
    fun `a session without a username is refused`() = refused("username", *oe1.filterNot { "username" in it }.toTypedArray())

    @Test
    fun `a file with no session is refused`() = refused("no session", "fix.port=9880")

    private fun refused(mentioning: String, vararg lines: String) {
        val error = assertFailsWith<IllegalArgumentException> { sessions(*lines) }
        assertTrue(mentioning in error.message.orEmpty(), "'${error.message}' should mention $mentioning")
    }
}
