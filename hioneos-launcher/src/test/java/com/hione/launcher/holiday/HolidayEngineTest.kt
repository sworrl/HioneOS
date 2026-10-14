package com.hione.launcher.holiday

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class HolidayEngineTest {
    private fun d(s: String) = LocalDate.parse(s)

    private val json = """
    {
      "schema": 1,
      "holidays": [
        {"id": "christmas", "name": "Christmas", "regions": ["intl"], "rule": {"type": "fixed", "month": 12, "day": 25},
         "window_days_before": 7, "tone": "festive",
         "art": {"hione": "drawable:theme_cozy_cafe_wallpaper", "hione": "art/holidays/christmas.webp"},
         "reminder": "Merry Christmas from {mascot_name}."},
        {"id": "us_thanksgiving", "name": "Thanksgiving", "regions": ["US"],
         "rule": {"type": "nth_weekday", "month": 11, "weekday": "THU", "n": 4}, "window_days_before": 3,
         "art": {"default": "art/holidays/thanksgiving.webp"}, "reminder": "Happy Thanksgiving."},
        {"id": "us_memorial", "name": "Memorial Day", "regions": ["US"], "rule": "last:5:MON", "tone": "respectful",
         "reminder": "Memorial Day is tomorrow."},
        {"id": "uk_early_may", "name": "Early May bank holiday", "region": "GB", "rule": "nth:5:MON:1", "observed": "uk"},
        {"id": "jp_tanabata", "name": "Tanabata", "regions": ["JP"], "date": "07-07", "window": 2,
         "art": {"hione": "art/holidays/tanabata.webp"}},
        {"id": "jp_vernal", "name": "Vernal Equinox Day", "regions": ["JP"], "rule": "equinox:vernal"},
        {"id": "broken", "name": "No rule"}
      ]
    }
    """.trimIndent()

    private val cal = HolidayCalendar.parse(json)
    private val seasons = mapOf("default" to "drawable:hione_wallpaper", "summer" to "s.webp", "winter" to "w.webp",
        "spring" to "sp.webp", "autumn" to "a.webp")
    private val intl = HolidayScope("intl")

    @Test fun parsesAllFormsAndSkipsBrokenEntries() {
        assertEquals(listOf("christmas", "us_thanksgiving", "us_memorial", "uk_early_may", "jp_tanabata", "jp_vernal"), cal.map { it.id })
        val uk = cal.first { it.id == "uk_early_may" }
        assertEquals(setOf("UK"), uk.regions)
        assertEquals(d("2026-05-04"), uk.rule.date(2026))
        assertEquals(d("2026-05-25"), cal.first { it.id == "us_memorial" }.rule.date(2026))
        assertEquals(d("2026-03-20"), cal.first { it.id == "jp_vernal" }.rule.date(2026))
        assertEquals("jp", cal.first { it.id == "jp_vernal" }.observed)
        assertEquals(2, cal.first { it.id == "jp_tanabata" }.windowDaysBefore)
    }

    @Test fun holidayWindowBeatsSeason() {
        val a = HolidayEngine.activeArt(d("2026-12-20"), cal, intl, 40.0, "hione", seasons)
        assertEquals("holiday", a.kind)
        assertEquals("christmas", a.key)
        assertEquals("art/holidays/christmas.webp", a.art)
        assertEquals(d("2026-12-25"), a.date)
        // one day before the window opens: the season
        val b = HolidayEngine.activeArt(d("2026-12-17"), cal, intl, 40.0, "hione", seasons)
        assertEquals("season", b.kind)
        assertEquals("winter", b.key)
        assertEquals("w.webp", b.art)
        // the day after: season again
        assertEquals("season", HolidayEngine.activeArt(d("2026-12-26"), cal, intl, 40.0, "hione", seasons).kind)
    }

    @Test fun holidayWithoutArtForThePackFallsThroughToSeason() {
        val a = HolidayEngine.activeArt(d("2026-07-06"), cal, intl, 40.0, "hione", seasons)
        assertEquals("season", a.kind)
        assertEquals("summer", a.key)
        val b = HolidayEngine.activeArt(d("2026-07-06"), cal, intl, 40.0, "hione", seasons)
        assertEquals("jp_tanabata", b.key)
        // "default" art serves every pack
        assertEquals("art/holidays/thanksgiving.webp",
            HolidayEngine.activeArt(d("2026-11-24"), cal, intl, 40.0, "hione", seasons).art)
    }

    @Test fun seasonsFlipInTheSouthernHemisphere() {
        assertEquals("summer", HolidayEngine.season(d("2026-07-15"), 35.0))
        assertEquals("winter", HolidayEngine.season(d("2026-07-15"), -33.9))
        assertEquals("summer", HolidayEngine.season(d("2026-01-10"), -33.9))
        assertEquals("spring", HolidayEngine.season(d("2026-04-01"), null))
        assertEquals("autumn", HolidayEngine.season(d("2026-04-01"), -37.8))
        assertEquals("sp.webp", HolidayEngine.activeArt(d("2026-10-10"), cal, HolidayScope("off"), -33.9, "hione", seasons).art)
    }

    @Test fun missingSeasonSlotUsesDefault() {
        val a = HolidayEngine.activeArt(d("2026-07-15"), emptyList(), intl, 10.0, "x", mapOf("default" to "drawable:hione_wallpaper"))
        assertEquals("drawable:hione_wallpaper", a.art)
    }

    @Test fun scopeFiltering() {
        val local = HolidayScope("local", setOf("UK", "intl"))
        val thanks = cal.first { it.id == "us_thanksgiving" }
        val xmas = cal.first { it.id == "christmas" }
        assertFalse(local.includes(thanks))
        assertTrue(local.includes(xmas))
        assertTrue(HolidayScope("intl").includes(thanks))
        assertFalse(HolidayScope("off").includes(xmas))
        val custom = HolidayScope("custom", custom = HolidayScope.parseCustom("us_thanksgiving, jp_tanabata"))
        assertTrue(custom.includes(thanks))
        assertFalse(custom.includes(xmas))
        // Thanksgiving window under a UK-local scope: no holiday, the season shows
        assertEquals("season", HolidayEngine.activeArt(d("2026-11-25"), cal, local, 51.5, "hione", seasons).kind)
        assertEquals("us_thanksgiving", HolidayEngine.activeArt(d("2026-11-25"), cal, HolidayScope("local", setOf("US")), 40.0, "hione", seasons).key)
    }

    @Test fun windowCrossesTheNewYear() {
        val ny = Holiday("new_year", "New Year", setOf("intl"), DateRule.Fixed(1, 1), windowDaysBefore = 3,
            art = mapOf("default" to "ny.webp"))
        val a = HolidayEngine.activeArt(d("2026-12-30"), listOf(ny), intl, 40.0, "hione", seasons)
        assertEquals("new_year", a.key)
        assertEquals(d("2027-01-01"), a.date)
    }

    @Test fun nearestHolidayWinsWhenWindowsOverlap() {
        val eve = Holiday("eve", "Eve", setOf("intl"), DateRule.Fixed(12, 24), windowDaysBefore = 1, art = mapOf("default" to "eve.webp"))
        val a = HolidayEngine.activeArt(d("2026-12-24"), cal + eve, intl, 40.0, "hione", seasons)
        assertEquals("eve", a.key)
    }

    @Test fun reminders() {
        // respectful: the day before
        assertEquals(listOf("us_memorial"), HolidayEngine.remindersDue(d("2026-05-24"), cal, intl).map { it.holiday.id })
        assertTrue(HolidayEngine.remindersDue(d("2026-05-25"), cal, intl).isEmpty())
        // festive: on the day
        assertEquals(listOf("christmas"), HolidayEngine.remindersDue(d("2026-12-25"), cal, intl).map { it.holiday.id })
        // out of scope: nothing
        assertTrue(HolidayEngine.remindersDue(d("2026-12-25"), cal, HolidayScope("off")).isEmpty())
        // no reminder text: nothing
        assertTrue(HolidayEngine.remindersDue(d("2026-05-04"), cal, intl).isEmpty())
    }

    @Test fun ukObservedOccurrence() {
        val boxing = Holiday("uk_boxing", "Boxing Day", setOf("UK"), DateRule.Fixed(12, 26), observed = "uk")
        val xmas = Holiday("uk_christmas", "Christmas Day", setOf("UK"), DateRule.Fixed(12, 25), observed = "uk")
        val off = HolidayEngine.daysOff(listOf(xmas, boxing), "UK", 2021)
        assertEquals(setOf(d("2021-12-27"), d("2021-12-28")), off)
    }

    @Test fun noCalendarMeansSeasonsOnly() {
        assertNull(HolidayEngine.activeHoliday(d("2026-12-25"), emptyList(), intl))
    }
}
