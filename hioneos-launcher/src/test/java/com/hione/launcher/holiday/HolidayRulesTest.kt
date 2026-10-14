package com.hione.launcher.holiday

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.THURSDAY
import java.time.LocalDate

class HolidayRulesTest {
    private fun d(s: String) = LocalDate.parse(s)

    // ── US ──────────────────────────────────────────────────────────────────────────────────
    private val thanksgiving = DateRule.NthWeekday(11, THURSDAY, 4)
    private val memorial = DateRule.NthWeekday(5, MONDAY, -1)
    private val mlk = DateRule.NthWeekday(1, MONDAY, 3)

    @Test fun usThanksgivingIsFourthThursdayOfNovember() {
        assertEquals(d("2024-11-28"), thanksgiving.date(2024))
        assertEquals(d("2025-11-27"), thanksgiving.date(2025))
        assertEquals(d("2026-11-26"), thanksgiving.date(2026))
        assertEquals(d("2029-11-22"), thanksgiving.date(2029))
    }

    @Test fun usMemorialDayIsLastMondayOfMay() {
        assertEquals(d("2024-05-27"), memorial.date(2024))
        assertEquals(d("2025-05-26"), memorial.date(2025))
        assertEquals(d("2026-05-25"), memorial.date(2026))
        assertEquals(d("2027-05-31"), memorial.date(2027))
    }

    @Test fun usMlkIsThirdMondayOfJanuary() {
        assertEquals(d("2024-01-15"), mlk.date(2024))
        assertEquals(d("2025-01-20"), mlk.date(2025))
        assertEquals(d("2026-01-19"), mlk.date(2026))
    }

    @Test fun usFederalObservance() {
        assertEquals(d("2026-07-03"), HolidayRules.usObserved(d("2026-07-04"))) // Saturday -> Friday
        assertEquals(d("2027-07-05"), HolidayRules.usObserved(d("2027-07-04"))) // Sunday -> Monday
        assertEquals(d("2025-07-04"), HolidayRules.usObserved(d("2025-07-04")))
    }

    // ── UK ──────────────────────────────────────────────────────────────────────────────────
    @Test fun easterSunday() {
        assertEquals(d("2019-04-21"), HolidayRules.easterSunday(2019))
        assertEquals(d("2024-03-31"), HolidayRules.easterSunday(2024))
        assertEquals(d("2025-04-20"), HolidayRules.easterSunday(2025))
        assertEquals(d("2026-04-05"), HolidayRules.easterSunday(2026))
        assertEquals(d("2038-04-25"), HolidayRules.easterSunday(2038))
    }

    @Test fun ukBankHolidays() {
        val earlyMay = DateRule.NthWeekday(5, MONDAY, 1)
        val spring = DateRule.NthWeekday(5, MONDAY, -1)
        val summer = DateRule.NthWeekday(8, MONDAY, -1)
        assertEquals(d("2024-05-06"), earlyMay.date(2024))
        assertEquals(d("2025-05-05"), earlyMay.date(2025))
        assertEquals(d("2026-05-04"), earlyMay.date(2026))
        assertEquals(d("2024-05-27"), spring.date(2024))
        assertEquals(d("2025-05-26"), spring.date(2025))
        assertEquals(d("2024-08-26"), summer.date(2024))
        assertEquals(d("2025-08-25"), summer.date(2025))
        assertEquals(d("2024-04-01"), DateRule.Easter(1).date(2024))
        assertEquals(d("2025-04-21"), DateRule.Easter(1).date(2025))
        assertEquals(d("2026-04-06"), DateRule.Easter(1).date(2026))
        assertEquals(d("2024-03-29"), DateRule.Easter(-2).date(2024))
        assertEquals(d("2025-04-18"), DateRule.Easter(-2).date(2025))
    }

    @Test fun ukChristmasAndBoxingDaySubstitutes() {
        fun obs(year: Int) = HolidayRules.ukObserved(
            mapOf(LocalDate.of(year, 12, 25) to "xmas", LocalDate.of(year, 12, 26) to "boxing")
        )
        // 2021: Saturday and Sunday -> Monday 27 and Tuesday 28
        assertEquals(d("2021-12-27"), obs(2021)[d("2021-12-25")])
        assertEquals(d("2021-12-28"), obs(2021)[d("2021-12-26")])
        // 2022: Christmas on Sunday, Boxing Day on Monday stays -> Christmas moves to Tuesday 27
        assertEquals(d("2022-12-27"), obs(2022)[d("2022-12-25")])
        assertEquals(d("2022-12-26"), obs(2022)[d("2022-12-26")])
        // 2020: Boxing Day on Saturday -> Monday 28
        assertEquals(d("2020-12-28"), obs(2020)[d("2020-12-26")])
        // New Year's Day 2022 on Saturday -> Monday 3 January
        assertEquals(d("2022-01-03"), HolidayRules.ukObserved(mapOf(d("2022-01-01") to "ny"))[d("2022-01-01")])
    }

    // ── JP ──────────────────────────────────────────────────────────────────────────────────
    @Test fun jpHappyMondays() {
        val comingOfAge = DateRule.NthWeekday(1, MONDAY, 2)
        val marine = DateRule.NthWeekday(7, MONDAY, 3)
        val aged = DateRule.NthWeekday(9, MONDAY, 3)
        val sports = DateRule.NthWeekday(10, MONDAY, 2)
        assertEquals(d("2024-01-08"), comingOfAge.date(2024))
        assertEquals(d("2025-01-13"), comingOfAge.date(2025))
        assertEquals(d("2026-01-12"), comingOfAge.date(2026))
        assertEquals(d("2024-07-15"), marine.date(2024))
        assertEquals(d("2025-07-21"), marine.date(2025))
        assertEquals(d("2026-07-20"), marine.date(2026))
        assertEquals(d("2024-09-16"), aged.date(2024))
        assertEquals(d("2025-09-15"), aged.date(2025))
        assertEquals(d("2026-09-21"), aged.date(2026))
        assertEquals(d("2024-10-14"), sports.date(2024))
        assertEquals(d("2025-10-13"), sports.date(2025))
        assertEquals(d("2026-10-12"), sports.date(2026))
    }

    @Test fun jpEquinoxes() {
        val vernal = mapOf(2024 to "03-20", 2025 to "03-20", 2026 to "03-20", 2027 to "03-21",
            2028 to "03-20", 2029 to "03-20", 2030 to "03-20")
        val autumnal = mapOf(2024 to "09-22", 2025 to "09-23", 2026 to "09-23", 2027 to "09-23",
            2028 to "09-22", 2029 to "09-23", 2030 to "09-23")
        for ((y, md) in vernal) assertEquals("vernal $y", d("$y-$md"), HolidayRules.jpVernalEquinox(y))
        for ((y, md) in autumnal) assertEquals("autumnal $y", d("$y-$md"), HolidayRules.jpAutumnalEquinox(y))
        assertNull(HolidayRules.jpVernalEquinox(2100))
        assertNull(HolidayRules.jpAutumnalEquinox(1979))
    }

    /** Japan's national holidays as the calendar would list them. */
    private val jp = listOf(
        h("jp_new_year", DateRule.Fixed(1, 1)),
        h("jp_coming_of_age", DateRule.NthWeekday(1, MONDAY, 2)),
        h("jp_foundation", DateRule.Fixed(2, 11)),
        h("jp_emperor", DateRule.Fixed(2, 23)),
        h("jp_vernal", DateRule.JpVernalEquinox),
        h("jp_showa", DateRule.Fixed(4, 29)),
        h("jp_constitution", DateRule.Fixed(5, 3)),
        h("jp_greenery", DateRule.Fixed(5, 4)),
        h("jp_children", DateRule.Fixed(5, 5)),
        h("jp_marine", DateRule.NthWeekday(7, MONDAY, 3)),
        h("jp_mountain", DateRule.Fixed(8, 11)),
        h("jp_aged", DateRule.NthWeekday(9, MONDAY, 3)),
        h("jp_autumnal", DateRule.JpAutumnalEquinox),
        h("jp_sports", DateRule.NthWeekday(10, MONDAY, 2)),
        h("jp_culture", DateRule.Fixed(11, 3)),
        h("jp_labour", DateRule.Fixed(11, 23)),
    )

    private fun h(id: String, r: DateRule) = Holiday(id, id, setOf("JP"), r, observed = "jp")

    private fun jpExtras(year: Int): Map<LocalDate, String> =
        HolidayRules.jpExtraDays(jp.mapNotNull { x -> x.rule.date(year)?.let { it to x.id } }.toMap())

    @Test fun jpSubstituteHolidays() {
        assertEquals(
            mapOf(d("2024-02-12") to "jp_foundation_substitute", d("2024-05-06") to "jp_children_substitute",
                d("2024-08-12") to "jp_mountain_substitute",
                d("2024-09-23") to "jp_autumnal_substitute", d("2024-11-04") to "jp_culture_substitute"),
            jpExtras(2024),
        )
        assertEquals(
            mapOf(d("2025-02-24") to "jp_emperor_substitute", d("2025-05-06") to "jp_greenery_substitute",
                d("2025-11-24") to "jp_labour_substitute"),
            jpExtras(2025),
        )
    }

    @Test fun jp2026SubstituteAndCitizensHoliday() {
        // Constitution Day on Sunday: May 4 and 5 are holidays already, so the day off is May 6.
        // Respect for the Aged (Mon 21) and the equinox (Wed 23) sandwich Tuesday 22.
        assertEquals(
            mapOf(d("2026-05-06") to "jp_constitution_substitute", d("2026-09-22") to "jp_citizens_holiday"),
            jpExtras(2026),
        )
        val off = HolidayEngine.daysOff(jp, "JP", 2026)
        assertTrue(d("2026-09-22") in off)
        assertTrue(d("2026-05-06") in off)
        assertEquals(18, off.size)
    }

    @Test fun weekdayParsing() {
        assertEquals(THURSDAY, HolidayRules.weekday("THU"))
        assertEquals(MONDAY, HolidayRules.weekday("monday"))
        assertEquals(MONDAY, HolidayRules.weekday("1"))
        assertNull(HolidayRules.weekday("xx"))
    }
}
