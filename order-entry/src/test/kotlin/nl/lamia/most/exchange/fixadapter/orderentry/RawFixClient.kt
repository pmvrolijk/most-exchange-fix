package nl.lamia.most.exchange.fixadapter.orderentry

import nl.lamia.most.exchange.fixadapter.session.TestSessions
import nl.lamia.most.exchange.fixadapter.session.fixMessage
import java.net.Socket
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * A FIX 4.4 client written byte by byte, as CLIENT1 to the adapter, so every MsgSeqNum is the test's:
 * for the gaps and short resends Artio's own initiator never makes (Design.md §8.21, spikes (h4), (i)).
 * It answers nothing by itself.
 */
class RawFixClient(port: Int) : AutoCloseable {
    private val socket = Socket("localhost", port)
    private val inbox = ConcurrentLinkedQueue<Map<Int, String>>()
    private val time = DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS").withZone(ZoneOffset.UTC)

    /** Set once the adapter has closed the connection. */
    @Volatile var closed = false
        private set

    init {
        Thread {
            val input = socket.getInputStream()
            val text = StringBuilder()
            val buffer = ByteArray(8192)
            while (true) {
                val n = try { input.read(buffer) } catch (e: Exception) { -1 }
                if (n < 0) { closed = true; break }
                text.append(String(buffer, 0, n, Charsets.ISO_8859_1))
                while (true) {
                    val end = Regex("\u000110=\\d{3}\u0001").find(text) ?: break
                    val message = text.substring(0, end.range.last + 1)
                    text.delete(0, end.range.last + 1)
                    inbox += message.split('\u0001').filter { '=' in it }.associate { it.substringBefore('=').toInt() to it.substringAfter('=') }
                }
            }
        }.apply { isDaemon = true }.start()
    }

    fun received(msgType: String): List<Map<Int, String>> = inbox.filter { it[35] == msgType }

    fun logon(seq: Int) = send("A", seq, listOf(98 to "0", 108 to "30", 553 to "client1", 554 to TestSessions.SECRET1))

    fun order(seq: Int, possDup: Boolean = false, clOrdId: String = "C$seq") = send(
        "D", seq,
        listOf(11 to clOrdId, 55 to "ACME", 54 to "1", 60 to time.format(Instant.now()), 38 to "10", 40 to "2", 44 to "100.25"),
        possDup,
    )

    fun gapFill(seq: Int, newSeqNo: Int) = send("4", seq, listOf(123 to "Y", 36 to "$newSeqNo"), possDup = true)

    fun send(msgType: String, seq: Int, body: List<Pair<Int, String>>, possDup: Boolean = false) {
        val now = Instant.now()
        val header = mutableListOf(49 to TestSessions.CLIENT1, 56 to TestSessions.ADAPTER, 34 to "$seq")
        if (possDup) {
            header += 43 to "Y"
            header += 122 to time.format(now.minusSeconds(1))
        }
        header += 52 to time.format(now)
        socket.getOutputStream().write(fixMessage(msgType, header + body))
        socket.getOutputStream().flush()
    }

    override fun close() = socket.close()
}
