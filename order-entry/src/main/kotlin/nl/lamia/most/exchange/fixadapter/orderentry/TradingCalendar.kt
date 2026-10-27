package nl.lamia.most.exchange.fixadapter.orderentry

import java.io.File
import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.Properties
import java.util.function.IntSupplier
import java.util.function.LongSupplier

/**
 * The adapter's trading date, `YYYYMMDD`, the `expireDate` of a day order (Design.md §3.6). At a
 * local date and time in [zone]: the first trading day on or after the date if the time is before
 * [rollTime], and the first after it otherwise. A trading day is one of [weekdays] and not one of
 * [holidays].
 *
 * [getAsInt] is called on the order-entry thread for every day order, so it allocates nothing: the
 * date is recomputed only when [clock] (epoch milliseconds) leaves the span between two rolls it was
 * computed for.
 */
class TradingCalendar(
    private val zone: ZoneId,
    private val rollTime: LocalTime,
    private val weekdays: Set<DayOfWeek>,
    private val holidays: Set<LocalDate>,
    private val clock: LongSupplier = LongSupplier(System::currentTimeMillis),
) : IntSupplier {

    private var date = 0

    /** The span, in epoch milliseconds, over which [date] holds: from the last roll to the next. */
    private var validFrom = Long.MAX_VALUE
    private var validUntil = Long.MIN_VALUE

    init {
        require(weekdays.isNotEmpty()) { "the calendar trades on no weekday" }
        asInt
    }

    override fun getAsInt(): Int {
        val now = clock.asLong
        if (now < validFrom || now >= validUntil) compute(now)
        return date
    }

    private fun compute(now: Long) {
        val local = Instant.ofEpochMilli(now).atZone(zone)
        val today = local.toLocalDate()
        val rollToday = today.atTime(rollTime).atZone(zone).toInstant().toEpochMilli()
        val beforeRoll = now < rollToday
        if (beforeRoll) {
            validFrom = today.minusDays(1).atTime(rollTime).atZone(zone).toInstant().toEpochMilli()
            validUntil = rollToday
        } else {
            validFrom = rollToday
            validUntil = today.plusDays(1).atTime(rollTime).atZone(zone).toInstant().toEpochMilli()
        }
        var day = if (beforeRoll) today else today.plusDays(1)
        var searched = 0
        while (!isTradingDay(day)) {
            check(++searched <= 366) { "no trading day in the year from $today: weekdays $weekdays, ${holidays.size} holidays" }
            day = day.plusDays(1)
        }
        date = day.year * 10_000 + day.monthValue * 100 + day.dayOfMonth
    }

    private fun isTradingDay(day: LocalDate): Boolean = day.dayOfWeek in weekdays && day !in holidays

    companion object {
        const val ZONE = "fix.calendar.zone"
        const val ROLL_TIME = "fix.calendar.rollTime"
        const val WEEKDAYS = "fix.calendar.weekdays"
        const val HOLIDAYS_FILE = "fix.calendar.holidaysFile"

        private val WORKING_WEEK = listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
        private val DAY_NAMES = DayOfWeek.entries.associateBy { it.name.substring(0, 3) }
        private val ROLL_FORMAT = Regex("""\d{2}:\d{2}""")

        /** The calendar from the node file's `fix.calendar.*` keys (Design.md §3.6). */
        fun parse(properties: Properties, clock: LongSupplier = LongSupplier(System::currentTimeMillis)): TradingCalendar {
            fun required(key: String): String =
                properties.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: throw IllegalArgumentException("missing required configuration key: $key")

            val zone = try {
                ZoneId.of(required(ZONE))
            } catch (e: DateTimeException) {
                throw IllegalArgumentException("$ZONE: not a time zone: ${e.message}")
            }
            val roll = required(ROLL_TIME)
            val rollTime = try {
                require(ROLL_FORMAT.matches(roll)) { "$ROLL_TIME must be HH:mm, not $roll" }
                LocalTime.parse(roll)
            } catch (e: DateTimeParseException) {
                throw IllegalArgumentException("$ROLL_TIME must be HH:mm, not $roll")
            }
            val weekdays = properties.getProperty(WEEKDAYS)?.let { listed ->
                val names = listed.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                require(names.isNotEmpty()) { "$WEEKDAYS lists no weekday" }
                names.map { DAY_NAMES[it.uppercase()] ?: throw IllegalArgumentException("$WEEKDAYS: $it is not one of ${DAY_NAMES.keys}") }
            } ?: WORKING_WEEK
            val holidays = properties.getProperty(HOLIDAYS_FILE)?.trim()?.takeIf { it.isNotEmpty() }?.let(::holidays) ?: emptySet()
            return TradingCalendar(zone, rollTime, weekdays.toSet(), holidays, clock)
        }

        /** One ISO date per line, then optional words; `#` starts a comment. */
        private fun holidays(path: String): Set<LocalDate> {
            val file = File(path)
            require(file.isFile) { "$HOLIDAYS_FILE: no such file: $path" }
            return file.readLines().mapIndexedNotNull { i, line ->
                val text = line.substringBefore('#').trim()
                if (text.isEmpty()) return@mapIndexedNotNull null
                val first = text.split(Regex("""\s+"""), limit = 2)[0]
                try {
                    LocalDate.parse(first)
                } catch (e: DateTimeParseException) {
                    throw IllegalArgumentException("$path:${i + 1}: not an ISO date (2026-12-25): $first")
                }
            }.toSet()
        }
    }
}
