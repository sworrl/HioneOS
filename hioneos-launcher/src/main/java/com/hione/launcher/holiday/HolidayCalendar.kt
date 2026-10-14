package com.hione.launcher.holiday

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * One holiday from hioneos/mascot/holidays/calendar.json (bundled into the launcher's assets).
 *
 * Schema the parser reads. It is tolerant: field aliases in brackets, unknown fields ignored, a
 * holiday with a rule it cannot read is skipped, not fatal.
 * ```
 * {
 *   "schema": 1,
 *   "holidays": [
 *     {
 *       "id": "us_thanksgiving",                 stable id, used by the Custom checklist
 *       "name": "Thanksgiving",                  [title]
 *       "regions": ["US"],                       US, UK (GB accepted), JP, intl   [region: "US"]
 *       "rule": {"type": "nth_weekday", "month": 11, "weekday": "THU", "n": 4},
 *       "observed": "none",                      none | us | uk | jp   (jp: also takes part in
 *                                                the substitute and citizens' holiday rules)
 *       "window_days_before": 3,                 art shows from date-3 to date   [window]
 *       "tone": "festive",                       festive | plain | respectful (reminder the day before)
 *       "art": {"hione": "drawable:theme_cozy_cafe_wallpaper", "hione": "art/holidays/thanksgiving.webp",
 *               "default": "..."},              per pack id; a pack path or drawable:<name>
 *       "reminder": "Thanksgiving is today."     tokens like {mascot_name} [reminder_text, or {"text": ..}]
 *     }
 *   ]
 * }
 * ```
 * Rule forms: {"type": "fixed", "month": 12, "day": 25} · {"type": "nth_weekday", "month": 5,
 * "weekday": "MON", "n": -1} (n -1 = last) · {"type": "easter", "offset": 1} ·
 * {"type": "jp_vernal_equinox"} · {"type": "jp_autumnal_equinox"}. A string works too:
 * "fixed:12-25", "nth:11:THU:4", "last:5:MON", "easter:+1", "equinox:vernal", "equinox:autumnal",
 * or a plain "12-25" / "MM-DD" in a "date" field.
 */
data class Holiday(
    val id: String,
    val name: String,
    val regions: Set<String>,
    val rule: DateRule,
    val observed: String = "none",
    val windowDaysBefore: Int = 0,
    val tone: String = "festive",
    val art: Map<String, String> = emptyMap(),
    val reminder: String? = null,
    /** How many days the art stays up from the date (shogatsu 3, obon 4). */
    val days: Int = 1,
    /** Lockscreen art per pack id, when the calendar has a separate lock image. */
    val lockArt: Map<String, String> = emptyMap(),
) {
    /** The art for [packId]: the pack's own entry, then "default". */
    fun artFor(packId: String): String? = art[packId] ?: art["default"]
    fun lockArtFor(packId: String): String? = lockArt[packId] ?: lockArt["default"]

    /** True for a day of remembrance: the reminder goes out the day before, in a plain tone. */
    val respectful: Boolean get() = tone.equals("respectful", true) || tone.equals("solemn", true)
}

/** Season art from calendar.json, for [HolidayCalendar.CALENDAR_ART_PACK]. Images rotate by day of year. */
data class SeasonArt(val id: String, val homes: List<String>, val locks: List<String?>)

object HolidayCalendar {
    val REGIONS = listOf("intl", "US", "UK", "JP")
    /** calendar.json's art is drawn for this pack. Other packs use their own pack.json slots. */
    const val CALENDAR_ART_PACK = "hione"
    /** calendar.json _rules: window_days_before defaults to 3. */
    const val DEFAULT_WINDOW = 3

    fun parse(text: String): List<Holiday> {
        val t = text.trim()
        val arr: JSONArray = if (t.startsWith("[")) JSONArray(t) else {
            val o = JSONObject(t)
            o.optJSONArray("holidays") ?: o.optJSONArray("items") ?: JSONArray()
        }
        val out = ArrayList<Holiday>()
        for (i in 0 until arr.length()) {
            val h = arr.optJSONObject(i) ?: continue
            runCatching { parseOne(h) }.getOrNull()?.let { out += it }
        }
        return out
    }

    fun parseOne(h: JSONObject): Holiday? {
        val id = h.optString("id").ifBlank { return null }
        val rule = parseRule(h.opt("rule") ?: h.opt("date")) ?: return null
        val regions = LinkedHashSet<String>()
        (h.optJSONArray("regions") ?: h.optJSONArray("region"))?.let { a ->
            for (i in 0 until a.length()) normRegion(a.optString(i))?.let { regions += it }
        }
        if (regions.isEmpty()) (h.optString("regions").ifBlank { h.optString("region") }).split(',', ' ')
            .forEach { s -> normRegion(s)?.let { regions += it } }
        if (regions.isEmpty()) regions += "intl"
        val art = LinkedHashMap<String, String>()
        val lock = LinkedHashMap<String, String>()
        h.optJSONObject("art")?.let { a ->
            if (a.has("home") || a.has("home_720")) {
                // One art set (calendar.json): it is the art of CALENDAR_ART_PACK. The 720 px
                // files are the ones Hione Update ships.
                (a.optString("home_720").ifBlank { a.optString("home") }).ifBlank { null }?.let { art[CALENDAR_ART_PACK] = it }
                (a.optString("lock_720").ifBlank { a.optString("lock") }).ifBlank { null }?.let { lock[CALENDAR_ART_PACK] = it }
            } else {
                for (k in a.keys()) a.optString(k).ifBlank { null }?.let { art[k] = it }
            }
        }
        val reminder = when (val r = h.opt("reminder") ?: h.opt("reminder_text")) {
            is String -> r.ifBlank { null }
            is JSONObject -> r.optString("text").ifBlank { r.optString("en") }.ifBlank { null }
            else -> null
        }
        val window = when {
            h.has("window_days_before") -> h.optInt("window_days_before", DEFAULT_WINDOW)
            h.has("window") -> h.optInt("window", DEFAULT_WINDOW)
            else -> DEFAULT_WINDOW
        }
        val observed = h.optString("observed", "").ifBlank {
            when {
                "JP" in regions -> "jp"
                else -> "none"
            }
        }.lowercase()
        return Holiday(
            id = id,
            name = nameOf(h.opt("name")) ?: nameOf(h.opt("title")) ?: id,
            regions = regions,
            rule = rule,
            observed = observed,
            windowDaysBefore = window.coerceIn(0, 60),
            tone = h.optString("tone", "festive").ifBlank { "festive" }.lowercase(),
            art = art,
            reminder = reminder,
            days = h.optInt("days", 1).coerceIn(1, 31),
            lockArt = lock,
        )
    }

    /** A name string, or {"en": .., "ja": ..} (Japanese when the device language is Japanese). */
    private fun nameOf(v: Any?): String? = when (v) {
        is String -> v.ifBlank { null }
        is JSONObject -> {
            val ja = java.util.Locale.getDefault().language == "ja"
            (if (ja) v.optString("ja") else "").ifBlank { v.optString("en") }.ifBlank { null }
        }
        else -> null
    }

    /** Calendar seasons: [{"id": "fall", "months_north": [9,10,11], "art": [{"home_720": .., "lock_720": ..}]}]. */
    fun parseSeasons(text: String): List<SeasonArt> {
        val t = text.trim()
        if (!t.startsWith("{")) return emptyList()
        val arr = JSONObject(t).optJSONArray("seasons") ?: return emptyList()
        val out = ArrayList<SeasonArt>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = when (o.optString("id").lowercase()) {
                "fall", "autumn" -> "autumn"
                "winter" -> "winter"
                "spring" -> "spring"
                "summer" -> "summer"
                else -> continue
            }
            val homes = ArrayList<String>()
            val locks = ArrayList<String?>()
            o.optJSONArray("art")?.let { a ->
                for (j in 0 until a.length()) {
                    val x = a.optJSONObject(j) ?: continue
                    val home = x.optString("home_720").ifBlank { x.optString("home") }.ifBlank { null } ?: continue
                    homes += home
                    locks += x.optString("lock_720").ifBlank { x.optString("lock") }.ifBlank { null }
                }
            }
            out += SeasonArt(id, homes, locks)
        }
        return out
    }

    fun normRegion(s: String?): String? = when (s?.trim()?.uppercase()) {
        "US", "USA" -> "US"
        "UK", "GB", "GBR" -> "UK"
        "JP", "JPN", "JA" -> "JP"
        "INTL", "INTERNATIONAL", "GLOBAL", "WORLD", "ALL" -> "intl"
        else -> null
    }

    fun parseRule(r: Any?): DateRule? = when (r) {
        is JSONObject -> ruleFromObject(r)
        is String -> ruleFromString(r)
        else -> null
    }

    private fun ruleFromObject(o: JSONObject): DateRule? {
        val type = o.optString("type").lowercase().replace('-', '_')
        return when (type) {
            "fixed", "date" -> {
                if (o.has("date")) ruleFromString(o.getString("date"))
                else DateRule.Fixed(o.getInt("month"), o.getInt("day"))
            }
            "nth_weekday", "weekday", "nth" -> {
                val wd = weekdayOf(o.opt("weekday") ?: o.opt("dow")) ?: return null
                val n = o.optString("n", o.optString("nth", "1")).let {
                    if (it.equals("last", true)) -1 else it.toIntOrNull() ?: 1
                }
                DateRule.NthWeekday(o.getInt("month"), wd, n)
            }
            "last_weekday" -> DateRule.NthWeekday(o.getInt("month"), weekdayOf(o.opt("weekday")) ?: return null, -1)
            "easter", "easter_offset" -> DateRule.Easter(if (o.has("days")) o.optInt("days", 0) else o.optInt("offset", 0))
            "solar_term", "known" -> {
                val known = HashMap<Int, Pair<Int, Int>>()
                o.optJSONObject("known")?.let { k ->
                    for (y in k.keys()) {
                        val m = Regex("""^(\d{1,2})-(\d{1,2})$""").find(k.optString(y)) ?: continue
                        y.toIntOrNull()?.let { known[it] = m.groupValues[1].toInt() to m.groupValues[2].toInt() }
                    }
                }
                val u = o.optJSONObject("usual")
                DateRule.Known(known, u?.optInt("month", 2) ?: 2, u?.optInt("day", 3) ?: 3)
            }
            "jp_vernal_equinox", "vernal_equinox", "equinox_vernal" -> DateRule.JpVernalEquinox
            "jp_autumnal_equinox", "autumnal_equinox", "equinox_autumnal" -> DateRule.JpAutumnalEquinox
            "equinox", "jp_equinox" -> if (o.optString("which", o.optString("season")).lowercase().startsWith("a"))
                DateRule.JpAutumnalEquinox else DateRule.JpVernalEquinox
            else -> null
        }
    }

    private fun weekdayOf(v: Any?): DayOfWeek? = when (v) {
        is Number -> v.toInt().takeIf { it in 1..7 }?.let { DayOfWeek.of(it) }
        is String -> HolidayRules.weekday(v)
        else -> null
    }

    private fun ruleFromString(s0: String): DateRule? {
        val s = s0.trim()
        val p = s.split(':')
        return when (p[0].lowercase()) {
            "fixed" -> mmdd(p.getOrNull(1))
            "nth" -> DateRule.NthWeekday(p[1].toInt(), HolidayRules.weekday(p[2]) ?: return null,
                p[3].let { if (it.equals("last", true)) -1 else it.toInt() })
            "last" -> DateRule.NthWeekday(p[1].toInt(), HolidayRules.weekday(p[2]) ?: return null, -1)
            "easter" -> DateRule.Easter(p.getOrNull(1)?.removePrefix("+")?.toIntOrNull() ?: 0)
            "equinox" -> if (p.getOrNull(1)?.lowercase()?.startsWith("a") == true) DateRule.JpAutumnalEquinox else DateRule.JpVernalEquinox
            else -> mmdd(s)
        }
    }

    private fun mmdd(s: String?): DateRule? {
        val m = Regex("""^(?:\d{4}-)?(\d{1,2})-(\d{1,2})$""").find(s?.trim() ?: return null) ?: return null
        return DateRule.Fixed(m.groupValues[1].toInt(), m.groupValues[2].toInt())
    }
}

/** Which holidays the user sees: Settings.Global hione_holiday_scope and hione_holiday_custom. */
data class HolidayScope(
    /** intl (every region), local, custom, or off. */
    val mode: String = "local",
    /** Regions for local mode, from location, SIM and locale. "intl" is always added. */
    val localRegions: Set<String> = emptySet(),
    /** Holiday ids for custom mode. */
    val custom: Set<String> = emptySet(),
) {
    fun includes(h: Holiday): Boolean = when (mode) {
        "off" -> false
        "intl", "international", "all" -> true
        "custom" -> h.id in custom
        else -> h.regions.any { it == "intl" || it in localRegions }
    }

    companion object {
        fun parseCustom(s: String?): Set<String> =
            s.orEmpty().split(',', ';', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }
}
