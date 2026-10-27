package nl.lamia.most.exchange.fixadapter.orderentry

import java.io.File
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The adapter's trading date (Design.md §3.6, "As built"): before the roll, the first trading day on
 * or after today; from the roll on, the first after it. Every expected date is worked out by hand
 * from that rule, on the 2026 calendar: 2026-10-27 is a Tuesday, 2026-12-25 a Friday.
 */
class TradingCalendarTest {

    private val amsterdam = ZoneId.of("Europe/Amsterdam")
    private val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
    private val christmas = LocalDate.of(2026, 12, 25)

    private var now = 0L

    private fun calendar(zone: ZoneId = amsterdam, holidays: Set<LocalDate> = setOf(christmas), at: String = "2026-10-27T10:00"): TradingCalendar {
        now = local(at, zone)
        return TradingCalendar(zone, LocalTime.of(18, 0), weekdays, holidays) { now }
    }

    private fun local(at: String, zone: ZoneId = amsterdam): Long = LocalDateTime.parse(at).atZone(zone).toInstant().toEpochMilli()

    private fun dateAt(at: String, calendar: TradingCalendar = calendar()): Int {
        now = local(at)
        return calendar.asInt
    }

    @Test
    fun `before the roll a trading day names itself`() {
        assertEquals(20261027, dateAt("2026-10-27T10:00"))
        assertEquals(20261027, dateAt("2026-10-27T00:00"))
        assertEquals(20261027, dateAt("2026-10-27T17:59:59.999"))
    }

    @Test
    fun `from the roll on the date is the next trading day`() {
        val calendar = calendar()
        assertEquals(20261028, dateAt("2026-10-27T18:00", calendar))
        assertEquals(20261028, dateAt("2026-10-27T23:59", calendar))
        assertEquals(20261028, dateAt("2026-10-28T09:00", calendar))
    }

    @Test
    fun `a weekend names the Monday after it, from Friday's roll on`() {
        val calendar = calendar()
        assertEquals(20261030, dateAt("2026-10-30T17:00", calendar))
        assertEquals(20261102, dateAt("2026-10-30T19:00", calendar))
        assertEquals(20261102, dateAt("2026-10-31T10:00", calendar))
        assertEquals(20261102, dateAt("2026-11-01T23:00", calendar))
        assertEquals(20261102, dateAt("2026-11-02T09:00", calendar))
    }

    @Test
    fun `a holiday is not a trading day`() {
        val calendar = calendar()
        assertEquals(20261228, dateAt("2026-12-24T18:30", calendar))
        assertEquals(20261228, dateAt("2026-12-25T09:00", calendar))
        // Without the holiday, Christmas Eve's roll names Christmas.
        assertEquals(20261225, dateAt("2026-12-24T18:30", calendar(holidays = emptySet())))
    }

    @Test
    fun `the roll is the zone's local time`() {
        // 17:30 UTC on 2026-10-27 is 18:30 in Amsterdam (CET, +01:00, since 2026-10-25).
        now = Instant.parse("2026-10-27T17:30:00Z").toEpochMilli()
        val inAmsterdam = TradingCalendar(amsterdam, LocalTime.of(18, 0), weekdays, emptySet()) { now }
        val inUtc = TradingCalendar(ZoneId.of("UTC"), LocalTime.of(18, 0), weekdays, emptySet()) { now }
        assertEquals(20261028, inAmsterdam.asInt)
        assertEquals(20261027, inUtc.asInt)
    }

    @Test
    fun `a zone ahead of UTC rolls on its own date`() {
        // Tokyo is +09:00: 08:00 there on Wednesday the 28th is still Tuesday the 27th in UTC.
        val tokyo = ZoneId.of("Asia/Tokyo")
        now = local("2026-10-28T05:00", tokyo)
        val calendar = TradingCalendar(tokyo, LocalTime.of(6, 0), weekdays, emptySet()) { now }
        assertEquals(20261028, calendar.asInt)
        now = local("2026-10-28T08:00", tokyo)
        assertEquals(20261029, calendar.asInt)
    }

    @Test
    fun `the date follows the clock across the roll, and back if the clock is set back`() {
        val calendar = calendar(at = "2026-10-27T17:59:59.999")
        assertEquals(20261027, calendar.asInt)
        now += 1
        assertEquals(20261028, calendar.asInt)
        now += 86_400_000L
        assertEquals(20261029, calendar.asInt)
        now = local("2026-10-27T12:00")
        assertEquals(20261027, calendar.asInt)
    }

    @Test
    fun `reading the date between rolls allocates nothing`() {
        val calendar = calendar()
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        var sum = 0L
        repeat(20_000) { sum += calendar.asInt; now++ } // warm, and the clock moves
        // As DirectoryIndexTest: the JVM allocates on this thread now and then by itself. A read that
        // allocates shows in every window; one of five at 0 is the claim.
        val windows = (0 until 5).map {
            val before = threads.currentThreadAllocatedBytes
            repeat(20_000) { sum += calendar.asInt; now++ }
            threads.currentThreadAllocatedBytes - before
        }
        assertTrue(0L in windows, "bytes allocated per window of 20,000 reads: $windows")
        assertTrue(sum != 0L)
    }

    @Test
    fun `no trading day within a year is refused`() {
        val mondays = generateSequence(LocalDate.of(2026, 10, 26)) { it.plusWeeks(1) }.take(60).toSet()
        val message = assertFailsWith<IllegalStateException> {
            now = local("2026-10-27T10:00")
            TradingCalendar(amsterdam, LocalTime.of(18, 0), setOf(DayOfWeek.MONDAY), mondays) { now }
        }.message.orEmpty()
        assertTrue("no trading day" in message, message)
    }

    // ------------------------------------------------------------------ the node file's keys

    private val dir: File = Files.createTempDirectory("calendar").toFile().apply { deleteOnExit() }

    private fun properties(vararg entries: Pair<String, String>) = Properties().apply {
        setProperty("fix.calendar.zone", "Europe/Amsterdam")
        setProperty("fix.calendar.rollTime", "18:00")
        for ((k, v) in entries) if (v.isEmpty()) remove(k) else setProperty(k, v)
    }

    private fun parsed(vararg entries: Pair<String, String>, at: String = "2026-10-27T10:00"): TradingCalendar {
        now = local(at)
        return TradingCalendar.parse(properties(*entries)) { now }
    }

    private fun refused(vararg entries: Pair<String, String>): String =
        assertFailsWith<IllegalArgumentException> { parsed(*entries) }.message.orEmpty()

    @Test
    fun `the zone and roll time are read, and the weekdays default to Monday to Friday`() {
        val calendar = parsed()
        assertEquals(20261027, calendar.asInt)
        now = local("2026-10-27T18:00")
        assertEquals(20261028, calendar.asInt)
        now = local("2026-10-31T10:00")
        assertEquals(20261102, calendar.asInt)
    }

    @Test
    fun `weekdays may be configured`() {
        val calendar = parsed("fix.calendar.weekdays" to "MON, TUE,WED,THU,FRI,SAT")
        now = local("2026-10-31T10:00")
        assertEquals(20261031, calendar.asInt)
        now = local("2026-10-31T18:00")
        assertEquals(20261102, calendar.asInt)
    }

    @Test
    fun `the holiday file is read, with comments and descriptions`() {
        val file = File(dir, "holidays.txt").apply {
            writeText(
                """
                # Christmas
                2026-12-25 Christmas Day
                2026-12-28   # a bridge day

                """.trimIndent(),
            )
        }
        val calendar = parsed("fix.calendar.holidaysFile" to file.path, at = "2026-12-24T18:30")
        assertEquals(20261229, calendar.asInt)
    }

    @Test
    fun `the zone and the roll time are required, and a refusal names its key`() {
        for (key in listOf("fix.calendar.zone", "fix.calendar.rollTime")) {
            val message = refused(key to "")
            assertTrue(key in message, message)
        }
    }

    @Test
    fun `a malformed key is refused, naming it`() {
        for ((key, value) in listOf(
            "fix.calendar.zone" to "Mars/Olympus",
            "fix.calendar.rollTime" to "6pm",
            "fix.calendar.rollTime" to "25:00",
            "fix.calendar.weekdays" to "MON,FUNDAY",
            "fix.calendar.weekdays" to " ",
            "fix.calendar.holidaysFile" to File(dir, "absent.txt").path,
        )) {
            val message = refused(key to value)
            assertTrue(key in message, "$key=$value: $message")
        }
    }

    @Test
    fun `a malformed holiday line is refused, naming the file and line`() {
        val file = File(dir, "bad.txt").apply { writeText("2026-12-25\n25-12-2026 Christmas\n") }
        val message = refused("fix.calendar.holidaysFile" to file.path)
        assertTrue("${file.path}:2" in message, message)
    }
}
