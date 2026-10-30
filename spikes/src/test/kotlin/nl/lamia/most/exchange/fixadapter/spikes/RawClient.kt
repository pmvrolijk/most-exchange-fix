package nl.lamia.most.exchange.fixadapter.spikes

import java.net.Socket
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedQueue

/** A FIX 4.4 client written byte by byte, as `RAW` to `MOST`. */
class RawClient(port: Int) : AutoCloseable {
    private val socket = Socket("localhost", port)
    private val inbox = ConcurrentLinkedQueue<Map<Int, String>>()
    /** Set once the acceptor has closed the connection. */
    @Volatile var closed = false
        private set
    private val time = DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS").withZone(ZoneOffset.UTC)

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

    /** Everything received, in order. */
    fun all(): List<Map<Int, String>> = inbox.toList()

    fun logon(seq: Int) = send("A", seq, listOf(98 to "0", 108 to "30"))

    fun gapFill(seq: Int, newSeqNo: Int) = send("4", seq, listOf(123 to "Y", 36 to "$newSeqNo"), possDup = true)

    fun order(seq: Int, clOrdId: String, possDup: Boolean = false) = send(
        "D", seq,
        listOf(11 to clOrdId, 55 to "SPIKE", 54 to "1", 60 to time.format(Instant.now()), 38 to "10", 40 to "2", 44 to "100"),
        possDup,
    )

    fun send(msgType: String, seq: Int, body: List<Pair<Int, String>>, possDup: Boolean = false) {
        val now = Instant.now()
        val header = mutableListOf(35 to msgType, 49 to "RAW", 56 to Messages.ACCEPTOR_COMP_ID, 34 to "$seq")
        if (possDup) {
            header += 43 to "Y"
            header += 122 to time.format(now.minusSeconds(1))
        }
        header += 52 to time.format(now)
        val text = (header + body).joinToString("") { (t, v) -> "$t=$v\u0001" }
        val head = "8=FIX.4.4\u00019=${text.length}\u0001"
        val sum = (head + text).sumOf { it.code } % 256
        socket.getOutputStream().write((head + text + "10=%03d\u0001".format(sum)).toByteArray(Charsets.ISO_8859_1))
        socket.getOutputStream().flush()
    }

    override fun close() = socket.close()
}
