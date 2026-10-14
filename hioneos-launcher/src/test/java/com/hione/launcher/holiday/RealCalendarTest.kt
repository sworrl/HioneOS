package com.hione.launcher.holiday

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** The shipped hioneos/mascot/holidays/calendar.json, parsed and run through the engine. */
class RealCalendarTest {
    private fun d(s: String) = LocalDate.parse(s)
    private val text = listOf("../../hioneos/mascot/holidays/calendar.json", "../hioneos/mascot/holidays/calendar.json")
        .map { File(it) }.first { it.isFile }.readText()
    private val cal = HolidayCalendar.parse(text)
    private fun date(id: String, y: Int) = cal.first { it.id == id }.rule.date(y)

    @Test fun everyHolidayParses() {
        val raw = org.json.JSONObject(text).getJSONArray("holidays").length()
        assertEquals(raw, cal.size)
        assertTrue(cal.all { it.rule.date(2026) != null })
    }

    @Test fun datesFromTheShippedRules() {
        assertEquals(d("2026-11-26"), date("thanksgiving", 2026))
        assertEquals(d("2026-05-25"), date("memorial_day", 2026))
        assertEquals(d("2026-01-19"), date("mlk_day", 2026))
        assertEquals(d("2026-05-04"), date("early_may_bank_holiday", 2026))
        assertEquals(d("2026-01-12"), date("coming_of_age_day", 2026))
        assertEquals(d("2026-07-20"), date("marine_day", 2026))
        assertEquals(d("2026-09-21"), date("respect_for_aged_day", 2026))
        assertEquals(d("2026-10-12"), date("sports_day", 2026))
        assertEquals(d("2026-03-20"), date("shunbun_no_hi", 2026))
        assertEquals(d("2026-09-23"), date("shubun_no_hi", 2026))
        assertEquals(d("2027-03-21"), date("shunbun_no_hi", 2027))
        assertEquals(d("2025-02-02"), date("setsubun", 2025))
        assertEquals(d("2026-02-03"), date("setsubun", 2026))
        assertEquals(d("2026-04-05"), date("easter", 2026))
    }

    @Test fun hioneArtAndHioneNone() {
        val h = cal.first { it.id == "halloween" }
        assertTrue(h.artFor("hione")!!.endsWith("home_720.jpg"))
        assertTrue(h.lockArtFor("hione")!!.endsWith("lock_720.jpg"))
        assertNull(h.artFor("hione"))
        // Hione pack on Halloween: the season, from its own slots
        val hione = HolidayEngine.activeArt(d("2026-10-31"), cal, HolidayScope("intl"), 40.0, "hione",
            mapOf("default" to "drawable:hione_wallpaper"), calendarSeasons = HolidayCalendar.parseSeasons(text))
        assertEquals("season", hione.kind)
        assertEquals("drawable:hione_wallpaper", hione.art)
        val hione = HolidayEngine.activeArt(d("2026-10-31"), cal, HolidayScope("intl"), 40.0, "hione", emptyMap())
        assertEquals("halloween", hione.key)
    }

    @Test fun regionHolidayBeatsIntlOnTheSameDay() {
        val jp = HolidayScope("local", setOf("JP", "intl"))
        assertEquals("shogatsu", HolidayEngine.activeHoliday(d("2027-01-01"), cal, jp)!!.holiday.id)
        assertEquals("new_years_day", HolidayEngine.activeHoliday(d("2027-01-01"), cal, HolidayScope("local", setOf("US", "intl")))!!.holiday.id)
        // shogatsu lasts 3 days
        assertEquals("shogatsu", HolidayEngine.activeHoliday(d("2027-01-03"), cal, jp)!!.holiday.id)
    }

    @Test fun calendarSeasonArtForHione() {
        val seasons = HolidayCalendar.parseSeasons(text)
        val a = HolidayEngine.seasonArt(d("2026-10-10"), 40.0, "hione", mapOf("default" to "x"), calendarSeasons = seasons)
        assertEquals("autumn", a.key)
        assertTrue(a.art!!.startsWith("art/seasons/fall/"))
        assertTrue(a.lockArt!!.endsWith("lock_720.jpg"))
        // winter has no calendar art: the pack slot
        assertEquals("x", HolidayEngine.seasonArt(d("2026-12-30"), 40.0, "hione", mapOf("default" to "x"), calendarSeasons = seasons).art)
    }
}
