package com.hione.launcher.weather

import com.hione.launcher.Mascot
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * Data model for the Hione Weather TILE (the quilt widget + its detail sheet).
 *
 * Every measured field is NULLABLE. A null means "the source did not give us this value" and
 * every renderer shows an honest "—" for it. There are deliberately NO default temperatures,
 * conditions or AQI values anywhere in this model — a snapshot only ever contains what a real
 * API response contained. Storage is metric/SI (°C, km/h, mm, hPa); [WxUnits] converts at
 * display time only.
 */

enum class WxUnits(val tempSuffix: String, val label: String) {
    METRIC("°C", "°C · km/h · mm"),
    IMPERIAL("°F", "°F · mph · in")
}

/** A resolved position + where it came from (shown in the sheet so the user knows). */
data class WxLocation(
    val lat: Double,
    val lon: Double,
    val name: String,
    val provider: String
)

/** One Open-Meteo geocoding hit. */
data class WxPlace(
    val name: String,
    val region: String,
    val country: String,
    val lat: Double,
    val lon: Double
) {
    val label: String get() = listOf(name, region, country).filter { it.isNotBlank() }.joinToString(", ")
}

data class WxCurrent(
    val observedMs: Long? = null,
    val tempC: Double? = null,
    val feelsC: Double? = null,
    val humidityPct: Int? = null,
    val windKmh: Double? = null,
    val gustKmh: Double? = null,
    val windDirDeg: Int? = null,
    val pressureHpa: Double? = null,
    val precipMm: Double? = null,
    val cloudPct: Int? = null,
    val wmoCode: Int? = null,
    val isDay: Boolean? = null,
    val uvIndex: Double? = null
)

data class WxHour(
    val epochMs: Long,
    val tempC: Double? = null,
    val feelsC: Double? = null,
    val humidityPct: Int? = null,
    val precipMm: Double? = null,
    val precipProbPct: Int? = null,
    val windKmh: Double? = null,
    val gustKmh: Double? = null,
    val windDirDeg: Int? = null,
    val wmoCode: Int? = null,
    val isDay: Boolean? = null
)

data class WxDay(
    val epochMs: Long,
    val wmoCode: Int? = null,
    val maxC: Double? = null,
    val minC: Double? = null,
    val precipMm: Double? = null,
    val precipProbPct: Int? = null,
    val windMaxKmh: Double? = null,
    val sunriseMs: Long? = null,
    val sunsetMs: Long? = null,
    val uvMax: Double? = null
)

/** Open-Meteo air-quality snapshot. All nullable — the AQ model does not cover every spot on earth. */
data class WxAir(
    val observedMs: Long? = null,
    val usAqi: Int? = null,
    val euAqi: Int? = null,
    val pm25: Double? = null,
    val pm10: Double? = null,
    val ozone: Double? = null
)

data class WxSnapshot(
    val fetchedMs: Long,
    val lat: Double,
    val lon: Double,
    val placeName: String,
    val tzId: String?,
    /** Short source tag for the tile chip: "Open-Meteo" or "Windy·GFS". */
    val source: String,
    /** Long-form provenance for the sheet, including partial-failure notes. */
    val sourceDetail: String,
    val current: WxCurrent,
    val hourly: List<WxHour>,
    val daily: List<WxDay>,
    val air: WxAir?
) {
    val timeZone: TimeZone get() = tzId?.let { TimeZone.getTimeZone(it) } ?: TimeZone.getDefault()

    /** Today's astro row, if the daily list covers it. */
    fun todayAstro(nowMs: Long = System.currentTimeMillis()): WxDay? {
        val tz = timeZone
        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = tz }
        val today = dayFmt.format(Date(nowMs))
        return daily.firstOrNull { dayFmt.format(Date(it.epochMs)) == today } ?: daily.firstOrNull()
    }

    /** Whether [ms] falls between sunrise and sunset of its day (null when astro is unknown). */
    fun isDayAt(ms: Long): Boolean? {
        val tz = timeZone
        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = tz }
        val key = dayFmt.format(Date(ms))
        val d = daily.firstOrNull { dayFmt.format(Date(it.epochMs)) == key } ?: return null
        val sr = d.sunriseMs ?: return null
        val ss = d.sunsetMs ?: return null
        return ms in sr..ss
    }
}

// ---------------------------------------------------------------------------------------------
// WMO weather-code mapping (Open-Meteo codes; Windy conditions are DERIVED into the same codes,
// see HioneWeatherTileSources.deriveWmoFromWindy — flagged in sourceDetail).
// ---------------------------------------------------------------------------------------------
object HioneWmo {
    fun text(code: Int?): String = when (code) {
        null -> "—"
        0 -> "Clear sky"
        1 -> "Mainly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45 -> "Fog"
        48 -> "Rime fog"
        51 -> "Light drizzle"
        53 -> "Drizzle"
        55 -> "Dense drizzle"
        56, 57 -> "Freezing drizzle"
        61 -> "Light rain"
        63 -> "Rain"
        65 -> "Heavy rain"
        66, 67 -> "Freezing rain"
        71 -> "Light snow"
        73 -> "Snow"
        75 -> "Heavy snow"
        77 -> "Snow grains / ice pellets"
        80 -> "Light showers"
        81 -> "Showers"
        82 -> "Violent showers"
        85 -> "Snow showers"
        86 -> "Heavy snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm with hail"
        else -> "Code $code"
    }

    /** Hione-styled glyph. Night clear/mainly-clear uses the moon. Unknown → "—" (never a fake sun). */
    fun icon(code: Int?, isDay: Boolean?): String {
        val day = isDay != false
        return when (code) {
            null -> "—"
            0 -> if (day) "☀️" else "🌙"
            1 -> if (day) "🌤️" else "🌙"
            2 -> if (day) "⛅" else "☁️"
            3 -> "☁️"
            45, 48 -> "🌫️"
            51, 53, 55 -> "🌦️"
            56, 57, 66, 67 -> "🌧️"
            61, 63, 65 -> "🌧️"
            71, 73, 75, 77 -> "❄️"
            80, 81, 82 -> "🌧️"
            85, 86 -> "🌨️"
            95 -> "⛈️"
            96, 99 -> "⛈️"
            else -> "❔"
        }
    }

    fun isPrecip(code: Int?): Boolean = code != null && (code in 51..67 || code in 71..77 || code in 80..86 || code in 95..99)
    fun isSnow(code: Int?): Boolean = code != null && (code in 71..77 || code in 85..86)
    fun isStorm(code: Int?): Boolean = code != null && code in 95..99
    fun isFog(code: Int?): Boolean = code == 45 || code == 48
}

// ---------------------------------------------------------------------------------------------
// Display formatting. Every function accepts null and renders "—".
// ---------------------------------------------------------------------------------------------
object WxFmt {
    const val DASH = "—"

    fun tempValue(c: Double?, u: WxUnits): Double? = c?.let { if (u == WxUnits.IMPERIAL) it * 9.0 / 5.0 + 32.0 else it }

    /** "21°" / "70°" / "—". */
    fun temp(c: Double?, u: WxUnits): String = tempValue(c, u)?.let { "${it.roundToInt()}°" } ?: DASH

    /** "21°C" / "70°F" / "—". */
    fun tempUnit(c: Double?, u: WxUnits): String = tempValue(c, u)?.let { "${it.roundToInt()}${u.tempSuffix}" } ?: DASH

    fun wind(kmh: Double?, u: WxUnits): String = kmh?.let {
        if (u == WxUnits.IMPERIAL) "${(it * 0.621371).roundToInt()} mph" else "${it.roundToInt()} km/h"
    } ?: DASH

    fun precip(mm: Double?, u: WxUnits): String = mm?.let {
        if (u == WxUnits.IMPERIAL) String.format(Locale.US, "%.2f in", it / 25.4) else String.format(Locale.US, "%.1f mm", it)
    } ?: DASH

    fun pressure(hpa: Double?, u: WxUnits): String = hpa?.let {
        if (u == WxUnits.IMPERIAL) String.format(Locale.US, "%.2f inHg", it * 0.02953) else "${it.roundToInt()} hPa"
    } ?: DASH

    fun pct(v: Int?): String = v?.let { "$it%" } ?: DASH

    fun uv(v: Double?): String = v?.let { String.format(Locale.US, "%.1f", it) } ?: DASH

    fun compass(deg: Int?): String {
        if (deg == null) return DASH
        val dirs = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
        val idx = ((((deg % 360) + 360) % 360) / 22.5 + 0.5).toInt() % 16
        return dirs[idx]
    }

    private fun fmt(pattern: String, tz: TimeZone) = SimpleDateFormat(pattern, Locale.US).apply { timeZone = tz }

    /** "15h" style hour label in the forecast's own time zone. */
    fun hourLabel(ms: Long, tz: TimeZone): String = fmt("H", tz).format(Date(ms)) + "h"

    /** "06:12" clock. */
    fun clock(ms: Long?, tz: TimeZone): String = ms?.let { fmt("HH:mm", tz).format(Date(it)) } ?: DASH

    /** "Mon" / "Today". */
    fun dayLabel(ms: Long, tz: TimeZone, nowMs: Long = System.currentTimeMillis()): String {
        val d = fmt("yyyy-MM-dd", tz)
        return if (d.format(Date(ms)) == d.format(Date(nowMs))) "Today" else fmt("EEE", tz).format(Date(ms))
    }

    /** "Mon 08/31". */
    fun dayDate(ms: Long, tz: TimeZone): String = fmt("EEE MM/dd", tz).format(Date(ms))

    /** Data age in words. */
    fun age(fetchedMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val d = (nowMs - fetchedMs).coerceAtLeast(0L)
        val m = d / 60_000L
        return when {
            m < 1 -> "just now"
            m < 60 -> "$m min ago"
            m < 60 * 24 -> "${m / 60} h ago"
            else -> "${m / (60 * 24)} d ago"
        }
    }

    /** US EPA AQI category name. */
    fun aqiCategory(usAqi: Int?): String = when {
        usAqi == null -> DASH
        usAqi <= 50 -> "Good"
        usAqi <= 100 -> "Moderate"
        usAqi <= 150 -> "Unhealthy (sensitive)"
        usAqi <= 200 -> "Unhealthy"
        usAqi <= 300 -> "Very unhealthy"
        else -> "Hazardous"
    }

    /** ARGB tint for the AQI category (grey when unknown). */
    fun aqiColorArgb(usAqi: Int?): Long = when {
        usAqi == null -> 0xFF8BA6A9
        usAqi <= 50 -> 0xFF7CE38B
        usAqi <= 100 -> 0xFFFFD54F
        usAqi <= 150 -> 0xFFFFA040
        usAqi <= 200 -> 0xFFFF5252
        usAqi <= 300 -> 0xFFB9C2D0
        else -> 0xFFC2185B
    }
}

// ---------------------------------------------------------------------------------------------
// Hione's mood line — purely a function of REAL values in the snapshot; never invents weather.
// ---------------------------------------------------------------------------------------------
object HioneWeatherMood {
    fun line(s: WxSnapshot?): String {
        if (s == null) return "No weather yet. ${Mascot.name} is still waiting on it."
        val c = s.current
        val code = c.wmoCode
        val next6 = s.hourly.take(6)
        val rainSoon = next6.any { (it.precipProbPct ?: 0) >= 40 || (it.precipMm ?: 0.0) > 0.2 } ||
            (HioneWmo.isPrecip(code) && !HioneWmo.isSnow(code))
        val aqi = s.air?.usAqi
        val t = c.tempC
        val wind = c.windKmh ?: 0.0
        val uv = c.uvIndex
        return when {
            HioneWmo.isStorm(code) -> "Thunderstorms. ${Mascot.name} says stay inside and turn the music up."
            HioneWmo.isSnow(code) || next6.any { HioneWmo.isSnow(it.wmoCode) } -> "Snow. ${Mascot.name} says warm socks and a warm playlist."
            rainSoon -> "Rain soon. ${Mascot.name} says bring an umbrella."
            aqi != null && aqi > 100 -> "The air quality is poor today. ${Mascot.name} says mask up."
            t != null && t >= 32.0 -> "It's hot. ${Mascot.name} says drink water and find some shade."
            t != null && t <= 0.0 -> "It's below freezing. ${Mascot.name} says wrap up warm."
            wind >= 40.0 -> "It's windy. ${Mascot.name} says hold on to your hat."
            uv != null && uv >= 8.0 -> "High UV. ${Mascot.name} says wear sunscreen."
            HioneWmo.isFog(code) -> "Foggy. ${Mascot.name} says walk slow."
            c.isDay == false && code != null && code <= 1 -> "Clear night. ${Mascot.name} says look up at the stars."
            code != null && code <= 1 -> "Nice out. ${Mascot.name} says take the M500 for a walk."
            code != null -> "${Mascot.name} says it's a good day for a playlist."
            else -> "Location found, no conditions yet."
        }
    }
}

// ---------------------------------------------------------------------------------------------
// JSON persistence (org.json — no extra deps). Absent/null fields round-trip as null.
// ---------------------------------------------------------------------------------------------
internal fun JSONObject.wxDbl(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key).takeUnless { it.isNaN() } else null
internal fun JSONObject.wxInt(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null
internal fun JSONObject.wxLong(key: String): Long? = if (has(key) && !isNull(key)) optLong(key) else null
internal fun JSONObject.wxStr(key: String): String? = if (has(key) && !isNull(key)) optString(key) else null
internal fun JSONObject.wxBool(key: String): Boolean? = if (has(key) && !isNull(key)) optBoolean(key) else null
internal fun JSONArray.wxDblAt(i: Int): Double? =
    if (i in 0 until length() && !isNull(i)) optDouble(i).takeUnless { it.isNaN() } else null
internal fun JSONArray.wxIntAt(i: Int): Int? = if (i in 0 until length() && !isNull(i)) optInt(i) else null
internal fun JSONArray.wxLongAt(i: Int): Long? = if (i in 0 until length() && !isNull(i)) optLong(i) else null
internal fun JSONObject.putN(key: String, v: Any?): JSONObject = if (v != null) put(key, v) else this

object WxJson {
    fun encode(s: WxSnapshot): String = JSONObject()
        .put("fetchedMs", s.fetchedMs)
        .put("lat", s.lat)
        .put("lon", s.lon)
        .put("place", s.placeName)
        .putN("tz", s.tzId)
        .put("source", s.source)
        .put("sourceDetail", s.sourceDetail)
        .put("current", encCurrent(s.current))
        .put("hourly", JSONArray().also { arr -> s.hourly.forEach { arr.put(encHour(it)) } })
        .put("daily", JSONArray().also { arr -> s.daily.forEach { arr.put(encDay(it)) } })
        .putN("air", s.air?.let { encAir(it) })
        .toString()

    fun decode(raw: String?): WxSnapshot? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val j = JSONObject(raw)
            val hourly = j.optJSONArray("hourly")
            val daily = j.optJSONArray("daily")
            WxSnapshot(
                fetchedMs = j.optLong("fetchedMs", 0L),
                lat = j.optDouble("lat", 0.0),
                lon = j.optDouble("lon", 0.0),
                placeName = j.optString("place", ""),
                tzId = j.wxStr("tz"),
                source = j.optString("source", "?"),
                sourceDetail = j.optString("sourceDetail", ""),
                current = j.optJSONObject("current")?.let { decCurrent(it) } ?: WxCurrent(),
                hourly = (0 until (hourly?.length() ?: 0)).mapNotNull { i -> hourly?.optJSONObject(i)?.let { decHour(it) } },
                daily = (0 until (daily?.length() ?: 0)).mapNotNull { i -> daily?.optJSONObject(i)?.let { decDay(it) } },
                air = j.optJSONObject("air")?.let { decAir(it) }
            )
        }.getOrNull()
    }

    private fun encCurrent(c: WxCurrent) = JSONObject()
        .putN("t", c.observedMs).putN("temp", c.tempC).putN("feels", c.feelsC).putN("rh", c.humidityPct)
        .putN("wind", c.windKmh).putN("gust", c.gustKmh).putN("dir", c.windDirDeg).putN("p", c.pressureHpa)
        .putN("pr", c.precipMm).putN("cloud", c.cloudPct).putN("code", c.wmoCode).putN("day", c.isDay).putN("uv", c.uvIndex)

    private fun decCurrent(j: JSONObject) = WxCurrent(
        observedMs = j.wxLong("t"), tempC = j.wxDbl("temp"), feelsC = j.wxDbl("feels"), humidityPct = j.wxInt("rh"),
        windKmh = j.wxDbl("wind"), gustKmh = j.wxDbl("gust"), windDirDeg = j.wxInt("dir"), pressureHpa = j.wxDbl("p"),
        precipMm = j.wxDbl("pr"), cloudPct = j.wxInt("cloud"), wmoCode = j.wxInt("code"), isDay = j.wxBool("day"), uvIndex = j.wxDbl("uv")
    )

    private fun encHour(h: WxHour) = JSONObject()
        .put("t", h.epochMs).putN("temp", h.tempC).putN("feels", h.feelsC).putN("rh", h.humidityPct)
        .putN("pr", h.precipMm).putN("pp", h.precipProbPct).putN("wind", h.windKmh).putN("gust", h.gustKmh)
        .putN("dir", h.windDirDeg).putN("code", h.wmoCode).putN("day", h.isDay)

    private fun decHour(j: JSONObject): WxHour? {
        val t = j.wxLong("t") ?: return null
        return WxHour(
            epochMs = t, tempC = j.wxDbl("temp"), feelsC = j.wxDbl("feels"), humidityPct = j.wxInt("rh"),
            precipMm = j.wxDbl("pr"), precipProbPct = j.wxInt("pp"), windKmh = j.wxDbl("wind"), gustKmh = j.wxDbl("gust"),
            windDirDeg = j.wxInt("dir"), wmoCode = j.wxInt("code"), isDay = j.wxBool("day")
        )
    }

    private fun encDay(d: WxDay) = JSONObject()
        .put("t", d.epochMs).putN("code", d.wmoCode).putN("max", d.maxC).putN("min", d.minC).putN("pr", d.precipMm)
        .putN("pp", d.precipProbPct).putN("wind", d.windMaxKmh).putN("sr", d.sunriseMs).putN("ss", d.sunsetMs).putN("uv", d.uvMax)

    private fun decDay(j: JSONObject): WxDay? {
        val t = j.wxLong("t") ?: return null
        return WxDay(
            epochMs = t, wmoCode = j.wxInt("code"), maxC = j.wxDbl("max"), minC = j.wxDbl("min"), precipMm = j.wxDbl("pr"),
            precipProbPct = j.wxInt("pp"), windMaxKmh = j.wxDbl("wind"), sunriseMs = j.wxLong("sr"), sunsetMs = j.wxLong("ss"), uvMax = j.wxDbl("uv")
        )
    }

    private fun encAir(a: WxAir) = JSONObject()
        .putN("t", a.observedMs).putN("us", a.usAqi).putN("eu", a.euAqi).putN("pm25", a.pm25).putN("pm10", a.pm10).putN("o3", a.ozone)

    private fun decAir(j: JSONObject) = WxAir(
        observedMs = j.wxLong("t"), usAqi = j.wxInt("us"), euAqi = j.wxInt("eu"), pm25 = j.wxDbl("pm25"), pm10 = j.wxDbl("pm10"), ozone = j.wxDbl("o3")
    )
}
