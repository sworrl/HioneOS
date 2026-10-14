package com.hione.launcher.weather

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The two weather sources behind the Hione Weather tile, plus geocoding.
 *
 *  - Open-Meteo (free, no key): forecast (current + hourly + daily + astro) and air quality
 *    (real US/EU AQI). ALWAYS available; the baseline every refresh runs.
 *  - Windy Point Forecast API v2 (needs the user's key): GFS point forecast in 3-hour steps.
 *    Used for current + hourly when a key is present; daily/astro/AQI still come from Open-Meteo
 *    because Windy's point API does not provide them.
 *
 * Everything parses defensively into nullable fields. A missing value stays null — it is never
 * replaced by a "reasonable" default.
 */
object HioneWeatherTileSources {
    private const val TAG = "HioneWxSources"
    private const val UA = "HioneOS-Launcher/1.0 (Hione weather tile)"

    private const val OM_FORECAST = "https://api.open-meteo.com/v1/forecast"
    private const val OM_AIR = "https://air-quality-api.open-meteo.com/v1/air-quality"
    private const val OM_GEOCODE = "https://geocoding-api.open-meteo.com/v1/search"
    private const val WINDY_POINT = "https://api.windy.com/api/point-forecast/v2"

    class SourceException(message: String) : Exception(message)

    /** Windy result: current (nearest model step) + the forward 3-hourly steps. */
    data class WindyForecast(
        val model: String,
        val current: WxCurrent,
        val hourly: List<WxHour>,
        val warning: String?
    )

    // ------------------------------------------------------------------ HTTP plumbing

    private fun readBody(conn: HttpURLConnection, error: Boolean): String {
        val stream = if (error) conn.errorStream else conn.inputStream
        if (stream == null) return ""
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
    }

    private fun httpGet(url: String, timeoutMs: Int = 8000): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            requestMethod = "GET"
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            if (code != 200) {
                val err = readBody(conn, true).take(160).replace('\n', ' ')
                throw SourceException("HTTP $code ${err.ifBlank { "" }}".trim())
            }
            return readBody(conn, false)
        } finally {
            conn.disconnect()
        }
    }

    private fun httpPostJson(url: String, body: String, timeoutMs: Int = 10000): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code != 200) {
                val err = readBody(conn, true).take(160).replace('\n', ' ')
                throw SourceException("HTTP $code ${err.ifBlank { "" }}".trim())
            }
            return readBody(conn, false)
        } finally {
            conn.disconnect()
        }
    }

    private fun fmtCoord(v: Double) = String.format(java.util.Locale.US, "%.4f", v)

    // ------------------------------------------------------------------ Open-Meteo forecast

    /**
     * Full Open-Meteo forecast. Throws [SourceException] (or an IO exception) on failure; never
     * returns a partially-invented snapshot. Times are requested as unix seconds so they are
     * directly comparable with Windy's millisecond timestamps.
     */
    fun fetchOpenMeteo(lat: Double, lon: Double): WxSnapshot {
        val url = OM_FORECAST +
            "?latitude=${fmtCoord(lat)}&longitude=${fmtCoord(lon)}" +
            "&current=temperature_2m,relative_humidity_2m,apparent_temperature,is_day,precipitation,weather_code," +
            "wind_speed_10m,wind_direction_10m,wind_gusts_10m,surface_pressure,cloud_cover,uv_index" +
            "&hourly=temperature_2m,apparent_temperature,relative_humidity_2m,precipitation_probability,precipitation," +
            "weather_code,wind_speed_10m,wind_gusts_10m,wind_direction_10m,is_day" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,precipitation_probability_max," +
            "wind_speed_10m_max,sunrise,sunset,uv_index_max" +
            "&timezone=auto&forecast_days=7&timeformat=unixtime&wind_speed_unit=kmh&precipitation_unit=mm&temperature_unit=celsius"
        val j = JSONObject(httpGet(url))
        if (j.optBoolean("error", false)) throw SourceException(j.optString("reason", "Open-Meteo error"))

        val tz = j.wxStr("timezone")
        val cur = j.optJSONObject("current")
        val current = WxCurrent(
            observedMs = cur?.wxLong("time")?.let { it * 1000L },
            tempC = cur?.wxDbl("temperature_2m"),
            feelsC = cur?.wxDbl("apparent_temperature"),
            humidityPct = cur?.wxInt("relative_humidity_2m"),
            windKmh = cur?.wxDbl("wind_speed_10m"),
            gustKmh = cur?.wxDbl("wind_gusts_10m"),
            windDirDeg = cur?.wxInt("wind_direction_10m"),
            pressureHpa = cur?.wxDbl("surface_pressure"),
            precipMm = cur?.wxDbl("precipitation"),
            cloudPct = cur?.wxInt("cloud_cover"),
            wmoCode = cur?.wxInt("weather_code"),
            isDay = cur?.wxInt("is_day")?.let { it == 1 },
            uvIndex = cur?.wxDbl("uv_index")
        )

        val nowMs = System.currentTimeMillis()
        val hourly = mutableListOf<WxHour>()
        j.optJSONObject("hourly")?.let { h ->
            val times = h.optJSONArray("time") ?: JSONArray()
            val temp = h.optJSONArray("temperature_2m")
            val feels = h.optJSONArray("apparent_temperature")
            val rh = h.optJSONArray("relative_humidity_2m")
            val pp = h.optJSONArray("precipitation_probability")
            val pr = h.optJSONArray("precipitation")
            val code = h.optJSONArray("weather_code")
            val ws = h.optJSONArray("wind_speed_10m")
            val wg = h.optJSONArray("wind_gusts_10m")
            val wd = h.optJSONArray("wind_direction_10m")
            val day = h.optJSONArray("is_day")
            for (i in 0 until times.length()) {
                val t = times.wxLongAt(i)?.let { it * 1000L } ?: continue
                // Keep from the current hour forward (the hour containing "now" included), 48 points.
                if (t < nowMs - 3_600_000L) continue
                if (hourly.size >= 48) break
                hourly.add(
                    WxHour(
                        epochMs = t,
                        tempC = temp?.wxDblAt(i),
                        feelsC = feels?.wxDblAt(i),
                        humidityPct = rh?.wxIntAt(i),
                        precipMm = pr?.wxDblAt(i),
                        precipProbPct = pp?.wxIntAt(i),
                        windKmh = ws?.wxDblAt(i),
                        gustKmh = wg?.wxDblAt(i),
                        windDirDeg = wd?.wxIntAt(i),
                        wmoCode = code?.wxIntAt(i),
                        isDay = day?.wxIntAt(i)?.let { it == 1 }
                    )
                )
            }
        }

        val daily = mutableListOf<WxDay>()
        j.optJSONObject("daily")?.let { d ->
            val times = d.optJSONArray("time") ?: JSONArray()
            val code = d.optJSONArray("weather_code")
            val mx = d.optJSONArray("temperature_2m_max")
            val mn = d.optJSONArray("temperature_2m_min")
            val pr = d.optJSONArray("precipitation_sum")
            val pp = d.optJSONArray("precipitation_probability_max")
            val ws = d.optJSONArray("wind_speed_10m_max")
            val sr = d.optJSONArray("sunrise")
            val ss = d.optJSONArray("sunset")
            val uv = d.optJSONArray("uv_index_max")
            for (i in 0 until times.length()) {
                val t = times.wxLongAt(i)?.let { it * 1000L } ?: continue
                daily.add(
                    WxDay(
                        epochMs = t,
                        wmoCode = code?.wxIntAt(i),
                        maxC = mx?.wxDblAt(i),
                        minC = mn?.wxDblAt(i),
                        precipMm = pr?.wxDblAt(i),
                        precipProbPct = pp?.wxIntAt(i),
                        windMaxKmh = ws?.wxDblAt(i),
                        sunriseMs = sr?.wxLongAt(i)?.let { it * 1000L },
                        sunsetMs = ss?.wxLongAt(i)?.let { it * 1000L },
                        uvMax = uv?.wxDblAt(i)
                    )
                )
            }
        }

        return WxSnapshot(
            fetchedMs = nowMs,
            lat = lat,
            lon = lon,
            placeName = "",
            tzId = tz,
            source = "Open-Meteo",
            sourceDetail = "Open-Meteo forecast API (current · hourly · daily · sunrise/sunset)",
            current = current,
            hourly = hourly,
            daily = daily,
            air = null
        )
    }

    /** Open-Meteo air quality (US + EU AQI, PM, ozone). Returns null on any failure — callers show "—". */
    fun fetchOpenMeteoAir(lat: Double, lon: Double): WxAir? = try {
        val url = OM_AIR +
            "?latitude=${fmtCoord(lat)}&longitude=${fmtCoord(lon)}" +
            "&current=us_aqi,european_aqi,pm2_5,pm10,ozone&timezone=auto&timeformat=unixtime"
        val j = JSONObject(httpGet(url))
        val c = j.optJSONObject("current")
        if (c == null) null else WxAir(
            observedMs = c.wxLong("time")?.let { it * 1000L },
            usAqi = c.wxInt("us_aqi"),
            euAqi = c.wxInt("european_aqi"),
            pm25 = c.wxDbl("pm2_5"),
            pm10 = c.wxDbl("pm10"),
            ozone = c.wxDbl("ozone")
        )
    } catch (t: Throwable) {
        Log.w(TAG, "Open-Meteo air quality failed: ${t.message}")
        null
    }

    // ------------------------------------------------------------------ Windy point forecast

    /** Finds the response key for a Windy parameter regardless of level suffix ("temp-surface"). */
    private fun JSONObject.windyArr(param: String): JSONArray? {
        val exact = optJSONArray("$param-surface")
        if (exact != null) return exact
        val iter = keys()
        while (iter.hasNext()) {
            val k = iter.next()
            if (k.startsWith("$param-")) return optJSONArray(k)
        }
        return null
    }

    private fun JSONObject.windyUnit(param: String): String {
        val u = optJSONObject("units") ?: return ""
        val exact = u.optString("$param-surface", "")
        if (exact.isNotEmpty()) return exact
        val iter = u.keys()
        while (iter.hasNext()) {
            val k = iter.next()
            if (k.startsWith("$param-")) return u.optString(k, "")
        }
        return ""
    }

    /**
     * Windy Point Forecast v2 (POST). Throws on HTTP/key errors so the caller can fall back to
     * Open-Meteo and record WHY in sourceDetail. Conditions (WMO code) are derived from the model's
     * precipitation type / amount and cloud layers, because Windy has no condition code.
     */
    fun fetchWindy(lat: Double, lon: Double, key: String, model: String = "gfs"): WindyForecast {
        val body = JSONObject()
            .put("lat", lat)
            .put("lon", lon)
            .put("model", model)
            .put("parameters", JSONArray(listOf("temp", "dewpoint", "precip", "wind", "windGust", "rh", "pressure", "ptype", "lclouds", "mclouds", "hclouds")))
            .put("levels", JSONArray(listOf("surface")))
            .put("key", key)
            .toString()
        val j = JSONObject(httpPostJson(WINDY_POINT, body))
        val ts = j.optJSONArray("ts") ?: throw SourceException("Windy: no timestamps in response")
        if (ts.length() == 0) throw SourceException("Windy: empty forecast")

        val temp = j.windyArr("temp")
        val tempUnit = j.windyUnit("temp")
        val rh = j.windyArr("rh")
        val windU = j.windyArr("wind_u")
        val windV = j.windyArr("wind_v")
        val windUnit = j.windyUnit("wind_u")
        val gust = j.windyArr("gust")
        val gustUnit = j.windyUnit("gust")
        val precip = j.windyArr("past3hprecip")
        val precipUnit = j.windyUnit("past3hprecip")
        val pressure = j.windyArr("pressure")
        val pressureUnit = j.windyUnit("pressure")
        val ptype = j.windyArr("ptype")
        val lcl = j.windyArr("lclouds")
        val mcl = j.windyArr("mclouds")
        val hcl = j.windyArr("hclouds")

        fun tempC(i: Int): Double? = temp?.wxDblAt(i)?.let { if (tempUnit.equals("K", true) || it > 150.0) it - 273.15 else it }
        fun toKmh(v: Double?, unit: String): Double? = v?.let {
            when {
                unit.contains("km", true) -> it
                unit.contains("mph", true) -> it * 1.609344
                unit.contains("kt", true) || unit.contains("knot", true) -> it * 1.852
                else -> it * 3.6 // m/s (Windy's default "m*s-1")
            }
        }
        fun toMm(v: Double?): Double? = v?.let { if (precipUnit.equals("mm", true)) it else it * 1000.0 } // default metres
        fun toHpa(v: Double?): Double? = v?.let { if (pressureUnit.equals("hPa", true) || it < 2000.0) it else it / 100.0 } // default Pa
        fun wind(i: Int): Pair<Double?, Int?> {
            val u = windU?.wxDblAt(i)
            val v = windV?.wxDblAt(i)
            if (u == null || v == null) return null to null
            val spd = toKmh(sqrt(u * u + v * v), windUnit)
            val dir = ((270.0 - Math.toDegrees(atan2(v, u))) % 360.0 + 360.0) % 360.0
            return spd to dir.roundToInt()
        }
        fun point(i: Int, t: Long): WxHour {
            val (spd, dir) = wind(i)
            val prMm = toMm(precip?.wxDblAt(i))
            return WxHour(
                epochMs = t,
                tempC = tempC(i),
                feelsC = null, // Windy point API has no apparent temperature — left null, never estimated
                humidityPct = rh?.wxDblAt(i)?.roundToInt(),
                precipMm = prMm,
                precipProbPct = null, // GFS point forecast has no probability field
                windKmh = spd,
                gustKmh = toKmh(gust?.wxDblAt(i), gustUnit),
                windDirDeg = dir,
                wmoCode = deriveWmoFromWindy(ptype?.wxIntAt(i), prMm, lcl?.wxDblAt(i), mcl?.wxDblAt(i), hcl?.wxDblAt(i)),
                isDay = null // filled by the engine from Open-Meteo astro
            )
        }

        val nowMs = System.currentTimeMillis()
        var nearestIdx = 0
        var nearestDelta = Long.MAX_VALUE
        val hourly = mutableListOf<WxHour>()
        for (i in 0 until ts.length()) {
            val t = ts.wxLongAt(i) ?: continue
            val delta = abs(t - nowMs)
            if (delta < nearestDelta) { nearestDelta = delta; nearestIdx = i }
            if (t >= nowMs - 90 * 60_000L && hourly.size < 24) hourly.add(point(i, t))
        }
        val nearestT = ts.wxLongAt(nearestIdx) ?: nowMs
        val near = point(nearestIdx, nearestT)
        val current = WxCurrent(
            observedMs = nearestT,
            tempC = near.tempC,
            feelsC = null,
            humidityPct = near.humidityPct,
            windKmh = near.windKmh,
            gustKmh = near.gustKmh,
            windDirDeg = near.windDirDeg,
            pressureHpa = toHpa(pressure?.wxDblAt(nearestIdx)),
            precipMm = near.precipMm,
            cloudPct = listOfNotNull(lcl?.wxDblAt(nearestIdx), mcl?.wxDblAt(nearestIdx), hcl?.wxDblAt(nearestIdx)).maxOrNull()?.roundToInt(),
            wmoCode = near.wmoCode,
            isDay = null,
            uvIndex = null
        )
        return WindyForecast(model = model, current = current, hourly = hourly, warning = j.wxStr("warning"))
    }

    /**
     * Map Windy's fields to the nearest WMO code so the same icon/text table applies.
     * ptype (Windy): 0 none · 1 rain · 3 freezing rain · 5 snow · 6 wet snow · 7 rain/ice mix · 8 ice pellets.
     * [precipMm3h] is the 3-hour accumulation. Returns null when nothing usable was provided.
     */
    fun deriveWmoFromWindy(ptype: Int?, precipMm3h: Double?, lcl: Double?, mcl: Double?, hcl: Double?): Int? {
        val rate = (precipMm3h ?: 0.0) / 3.0
        val wet = rate > 0.05
        if (wet || (ptype != null && ptype != 0 && (precipMm3h ?: 0.0) > 0.0)) {
            return when (ptype) {
                5, 6 -> when { rate >= 2.5 -> 75; rate >= 1.0 -> 73; else -> 71 }
                3 -> 66
                7 -> 67
                8 -> 77
                else -> when { rate >= 4.0 -> 65; rate >= 1.0 -> 63; rate >= 0.3 -> 61; else -> 51 }
            }
        }
        val clouds = listOfNotNull(lcl, mcl, hcl)
        if (clouds.isEmpty()) return null
        val total = clouds.maxOrNull() ?: return null
        return when {
            total >= 85.0 -> 3
            total >= 50.0 -> 2
            total >= 20.0 -> 1
            else -> 0
        }
    }

    // ------------------------------------------------------------------ Geocoding

    /** Open-Meteo geocoding search — empty list on failure or < 2 chars. */
    fun geocode(query: String): List<WxPlace> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        return try {
            val url = "$OM_GEOCODE?name=${URLEncoder.encode(q, "UTF-8")}&count=8&language=en&format=json"
            val j = JSONObject(httpGet(url, 6000))
            val arr = j.optJSONArray("results") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val lat = o.wxDbl("latitude") ?: return@mapNotNull null
                val lon = o.wxDbl("longitude") ?: return@mapNotNull null
                val name = o.optString("name", "")
                if (name.isBlank()) null else WxPlace(name, o.optString("admin1", ""), o.optString("country", ""), lat, lon)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Geocode failed: ${t.message}")
            emptyList()
        }
    }
}
