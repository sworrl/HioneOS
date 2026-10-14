package com.hione.launcher.holiday

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

// Date rules for holidays. Pure java.time, no Android types, so the unit tests run on the JVM.

/** How a holiday's date is found in a given year. */
sealed class DateRule {
    abstract fun date(year: Int): LocalDate?

    /** Same month and day every year. */
    data class Fixed(val month: Int, val day: Int) : DateRule() {
        override fun date(year: Int): LocalDate? = runCatching { LocalDate.of(year, month, day) }.getOrNull()
    }

    /** The [n]th [weekday] of [month]. n = -1 is the last one. */
    data class NthWeekday(val month: Int, val weekday: DayOfWeek, val n: Int) : DateRule() {
        override fun date(year: Int): LocalDate = HolidayRules.nthWeekday(year, month, weekday, n)
    }

    /** Western Easter Sunday plus [offset] days (Good Friday -2, Easter Monday +1). */
    data class Easter(val offset: Int) : DateRule() {
        override fun date(year: Int): LocalDate = HolidayRules.easterSunday(year).plusDays(offset.toLong())
    }

    /** A table of known dates per year ("2026" -> "02-03"), with a usual date for other years (Setsubun). */
    data class Known(val known: Map<Int, Pair<Int, Int>>, val usualMonth: Int, val usualDay: Int) : DateRule() {
        override fun date(year: Int): LocalDate? {
            val (m, d) = known[year] ?: (usualMonth to usualDay)
            return runCatching { LocalDate.of(year, m, d) }.getOrNull()
        }
    }

    /** Japan's Vernal Equinox Day (Shunbun no Hi). */
    object JpVernalEquinox : DateRule() {
        override fun date(year: Int): LocalDate? = HolidayRules.jpVernalEquinox(year)
        override fun toString() = "JpVernalEquinox"
    }

    /** Japan's Autumnal Equinox Day (Shubun no Hi). */
    object JpAutumnalEquinox : DateRule() {
        override fun date(year: Int): LocalDate? = HolidayRules.jpAutumnalEquinox(year)
        override fun toString() = "JpAutumnalEquinox"
    }
}

object HolidayRules {

    /** The [n]th [weekday] of [month] in [year]. n = -1 (or any n < 1) gives the last one. */
    fun nthWeekday(year: Int, month: Int, weekday: DayOfWeek, n: Int): LocalDate {
        val first = LocalDate.of(year, month, 1)
        return if (n < 1) {
            first.with(TemporalAdjusters.lastInMonth(weekday))
        } else {
            first.with(TemporalAdjusters.dayOfWeekInMonth(n, weekday))
        }
    }

    /** Western (Gregorian) Easter Sunday, anonymous Gregorian algorithm (Meeus/Jones/Butcher). */
    fun easterSunday(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = ((h + l - 7 * m + 114) % 31) + 1
        return LocalDate.of(year, month, day)
    }

    /**
     * Vernal equinox day in March, the formula the Japanese almanac uses for 1980 to 2099:
     * floor(20.8431 + 0.242194 * (y - 1980) - floor((y - 1980) / 4)). Null outside that range.
     */
    fun jpVernalEquinox(year: Int): LocalDate? {
        if (year !in 1980..2099) return null
        val y = year - 1980
        val day = Math.floor(20.8431 + 0.242194 * y - Math.floor(y / 4.0)).toInt()
        return LocalDate.of(year, 3, day)
    }

    /** Autumnal equinox day in September: floor(23.2488 + 0.242194 * (y - 1980) - floor((y - 1980) / 4)). */
    fun jpAutumnalEquinox(year: Int): LocalDate? {
        if (year !in 1980..2099) return null
        val y = year - 1980
        val day = Math.floor(23.2488 + 0.242194 * y - Math.floor(y / 4.0)).toInt()
        return LocalDate.of(year, 9, day)
    }

    /**
     * Japan's extra days off, from the year's national holidays [base]:
     *  - substitute holiday (furikae kyujitsu): a holiday on a Sunday moves the day off to the next
     *    day that is not already a holiday;
     *  - citizens' holiday (kokumin no kyujitsu): a weekday between two holidays becomes one.
     * Returns only the extra days, keyed by date, with the id of the holiday they come from.
     */
    fun jpExtraDays(base: Map<LocalDate, String>): Map<LocalDate, String> {
        val out = LinkedHashMap<LocalDate, String>()
        val all = HashSet(base.keys)
        for ((d, id) in base.toSortedMap()) {
            if (d.dayOfWeek != DayOfWeek.SUNDAY) continue
            var n = d.plusDays(1)
            while (n in all) n = n.plusDays(1)
            out[n] = "${id}_substitute"
            all += n
        }
        for (d in base.keys.sorted()) {
            val mid = d.plusDays(1)
            val after = d.plusDays(2)
            if (mid !in all && after in base && mid.dayOfWeek != DayOfWeek.SUNDAY) {
                out[mid] = "jp_citizens_holiday"
                all += mid
            }
        }
        return out
    }

    /**
     * UK substitute days: a bank holiday on a Saturday or Sunday moves to the next weekday that is
     * not already a bank holiday, in date order. So with Christmas on Saturday and Boxing Day on
     * Sunday, they land on Monday and Tuesday. Returns base date to observed date for every bank
     * holiday in [base] (unchanged when it already falls on a weekday).
     */
    fun ukObserved(base: Map<LocalDate, String>): Map<LocalDate, LocalDate> {
        val taken = base.keys.filter { !isWeekend(it) }.toHashSet()
        val out = LinkedHashMap<LocalDate, LocalDate>()
        for (d in base.keys.sorted()) {
            if (!isWeekend(d)) {
                out[d] = d
                continue
            }
            var n = d.plusDays(1)
            while (isWeekend(n) || n in taken) n = n.plusDays(1)
            taken += n
            out[d] = n
        }
        return out
    }

    /** US federal observance: Saturday moves to Friday, Sunday to Monday. */
    fun usObserved(d: LocalDate): LocalDate = when (d.dayOfWeek) {
        DayOfWeek.SATURDAY -> d.minusDays(1)
        DayOfWeek.SUNDAY -> d.plusDays(1)
        else -> d
    }

    fun isWeekend(d: LocalDate) = d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY

    /** "MON".."SUN", "monday", or 1..7 (ISO, Monday = 1). */
    fun weekday(s: String): DayOfWeek? {
        val t = s.trim().uppercase()
        t.toIntOrNull()?.let { return if (it in 1..7) DayOfWeek.of(it) else null }
        return DayOfWeek.values().firstOrNull { it.name.startsWith(t.take(3)) && t.length >= 3 }
    }
}
