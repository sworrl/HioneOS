package com.hione.launcher.weather

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The parts of the KDE Windy Weather widget the weather screen did not have yet:
 *
 *  - Air quality AND pollen (Open-Meteo air-quality API, CAMS). The screen used to print "AQI —"
 *    forever because nothing fed it. Pollen only exists for Europe; elsewhere it stays null.
 *  - Open-Meteo 15-minute precipitation, the nowcast's fallback past the radar horizon.
 *  - Frost / freeze outlook for the coming night (now until 10:00 local).
 *  - Windy webcams near you (needs the webcam key).
 *  - Windy Point Forecast, GFS 3-hourly (needs the point key), shown next to the free forecast.
 *  - Rain-within-30-minutes and frost-tonight notifications.
 *
 * All free sources run with no key. Fetches happen when the weather screen opens and, for the
 * notifications, from a 10-minute background check that only runs while the launcher is visible
 * and only pulls radar when Open-Meteo says rain is within 3 hours.
 */
object HioneWeatherExtras {
    private const val TAG = "HioneWxExtras"
    private const val PREFS = "hione_weather_extras"
    private const val CHANNEL = "hione_weather_nowcast"
    private const val CORE_MS = 30 * 60_000L
    private const val WEBCAM_MS = 10 * 60_000L // webcam image links expire after about 10 minutes

    data class AirPollen(
        val usAqi: Int? = null,
        val pm25: Double? = null,
        val pm10: Double? = null,
        val ozone: Double? = null,
        val pollenKind: String? = null,
        /** grains/m3, the highest of the tracked pollen types */
        val pollenValue: Double? = null,
        val pollenLevel: String? = null
    )

    data class Frost(val level: String, val minC: Double, val atMs: Long, val text: String)

    data class Webcam(
        val id: String,
        val title: String,
        val place: String,
        val previewUrl: String,
        val detailUrl: String,
        val updatedMs: Long?
    )

    data class State(
        val lat: Double = 0.0,
        val lon: Double = 0.0,
        val air: AirPollen? = null,
        val frost: Frost? = null,
        val om15: HioneNowcast.Om15? = null,
        val coreFetchedMs: Long = 0L,
        val coreError: String = "",
        val webcams: List<Webcam> = emptyList(),
        val webcamsFetchedMs: Long = 0L,
        val webcamError: String = "",
        val webcamBusy: Boolean = false,
        val windy: HioneWeatherTileSources.WindyForecast? = null,
        val windyFetchedMs: Long = 0L,
        val windyError: String = "",
        val windyBusy: Boolean = false
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val coreMutex = Mutex()
    private var loopJob: Job? = null

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun position(): Pair<Double, Double>? {
        val g = HioneWeatherService.state.value.gps
        return if (g.isLocked && (g.latitude != 0.0 || g.longitude != 0.0)) g.latitude to g.longitude else null
    }

    private fun moved(s: State, lat: Double, lon: Double) = abs(s.lat - lat) > 0.05 || abs(s.lon - lon) > 0.05

    // ------------------------------------------------------------------ settings

    fun notifyRain(ctx: Context) = prefs(ctx).getBoolean("notify_rain", true)
    fun notifyFrost(ctx: Context) = prefs(ctx).getBoolean("notify_frost", true)
    fun setNotifyRain(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("notify_rain", on).apply()
    fun setNotifyFrost(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("notify_frost", on).apply()
    /** The Windy card can be folded down to one line; remembered so it stays out of the way. */
    fun windyCardFolded(ctx: Context) = prefs(ctx).getBoolean("windy_card_folded", false)
    fun setWindyCardFolded(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("windy_card_folded", on).apply()

    // ------------------------------------------------------------------ lifecycle

    /** Background check for the rain/frost notifications. Idempotent; called from HioneWeatherService.start. */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        synchronized(this) {
            if (loopJob != null) return
            loopJob = scope.launch {
                delay(90_000L) // let the first location and forecast land
                while (isActive) {
                    runCatching { com.hione.launcher.ui.HionePowerProfile.awaitVisible() }
                    runCatching { backgroundTick(app) }.onFailure { Log.w(TAG, "background tick failed: ${it.message}") }
                    delay(10 * 60_000L)
                }
            }
        }
    }

    private suspend fun backgroundTick(app: Context) {
        val rain = notifyRain(app)
        val frost = notifyFrost(app)
        if (!rain && !frost) return
        val (lat, lon) = position() ?: return
        val s = _state.value
        if (moved(s, lat, lon) || System.currentTimeMillis() - s.coreFetchedMs > CORE_MS) refreshCore(app, lat, lon)
        if (frost) checkFrost(app)
        if (rain) {
            // Radar costs ~60 tile requests, so only when something wet is plausibly near:
            // Open-Meteo has rain within 3 h, or the last radar pass saw rain coming or close.
            val om = _state.value.om15
            val n = HioneNowcastEngine.state.value
            val now = System.currentTimeMillis()
            val omSoon = om?.firstAtMs?.let { it - now < 3 * 3_600_000L } == true
            val radarNear = n.result?.let { it.status == "now" || it.status == "eta" || (it.nearestKm ?: 999.0) < 150 } == true
            if ((omSoon || radarNear) && now - n.fetchedMs > 10 * 60_000L) HioneNowcastEngine.analyseNow(app, lat, lon)
        }
    }

    /** Weather screen opened: bring everything current. */
    fun refresh(ctx: Context, force: Boolean = false) {
        val app = ctx.applicationContext
        val (lat, lon) = position() ?: return
        scope.launch {
            val s = _state.value
            if (force || moved(s, lat, lon) || System.currentTimeMillis() - s.coreFetchedMs > CORE_MS) refreshCore(app, lat, lon)
            refreshWindy(app, lat, lon, force)
            checkFrost(app)
        }
        HioneNowcastEngine.refresh(app, lat, lon, force)
    }

    fun onKeysChanged(ctx: Context) {
        val app = ctx.applicationContext
        _state.update { it.copy(webcams = emptyList(), webcamsFetchedMs = 0L, webcamError = "", windy = null, windyFetchedMs = 0L, windyError = "") }
        val (lat, lon) = position() ?: return
        scope.launch {
            refreshWindy(app, lat, lon, true)
            refreshWebcamsNow(lat, lon)
        }
    }

    // ------------------------------------------------------------------ air quality, pollen, 15-minute rain, frost

    private suspend fun refreshCore(app: Context, lat: Double, lon: Double) {
        if (!coreMutex.tryLock()) return
        try {
            val la = String.format(Locale.US, "%.4f", lat)
            val lo = String.format(Locale.US, "%.4f", lon)
            val air = runCatching {
                val j = WxHttp.json("https://air-quality-api.open-meteo.com/v1/air-quality?latitude=$la&longitude=$lo" +
                    "&current=us_aqi,pm2_5,pm10,ozone,alder_pollen,birch_pollen,grass_pollen,mugwort_pollen,olive_pollen,ragweed_pollen&timezone=auto")
                shapeAir(j.optJSONObject("current"))
            }.onFailure { Log.w(TAG, "air quality failed: ${it.message}") }.getOrNull()

            var om15: HioneNowcast.Om15? = null
            var frost: Frost? = null
            var err = ""
            runCatching {
                val j = WxHttp.json("https://api.open-meteo.com/v1/forecast?latitude=$la&longitude=$lo" +
                    "&hourly=temperature_2m&forecast_hours=36&minutely_15=precipitation,weather_code&forecast_minutely_15=96" +
                    "&timezone=auto&timeformat=unixtime")
                val now = System.currentTimeMillis()
                om15 = shapeOm15(j.optJSONObject("minutely_15"), now)
                frost = frostOutlook(j.optJSONObject("hourly"), now)
            }.onFailure { err = it.message ?: "Open-Meteo failed"; Log.w(TAG, "Open-Meteo 15-minute fetch failed: $err") }

            _state.update {
                it.copy(
                    lat = lat, lon = lon,
                    air = air ?: it.air.takeIf { _ -> !moved(it, lat, lon) },
                    om15 = om15 ?: it.om15.takeIf { _ -> !moved(it, lat, lon) },
                    frost = if (om15 != null) frost else it.frost,
                    coreFetchedMs = if (om15 != null || air != null) System.currentTimeMillis() else it.coreFetchedMs,
                    coreError = err
                )
            }
            HioneNowcastEngine.resummarize()
        } finally {
            coreMutex.unlock()
        }
    }

    private fun shapeAir(c: org.json.JSONObject?): AirPollen? {
        if (c == null) return null
        val kinds = listOf("alder_pollen" to "Alder", "birch_pollen" to "Birch", "grass_pollen" to "Grass",
            "mugwort_pollen" to "Mugwort", "olive_pollen" to "Olive", "ragweed_pollen" to "Ragweed")
        var kind: String? = null
        var value: Double? = null
        for ((k, label) in kinds) {
            val v = c.wxDbl(k) ?: continue
            if (value == null || v > value) { value = v; kind = label }
        }
        val level = value?.let { v -> when { v >= 200 -> "very high"; v >= 50 -> "high"; v >= 10 -> "moderate"; else -> "low" } }
        return AirPollen(c.wxInt("us_aqi"), c.wxDbl("pm2_5"), c.wxDbl("pm10"), c.wxDbl("ozone"), kind, value, level)
    }

    private fun shapeOm15(m: org.json.JSONObject?, nowMs: Long): HioneNowcast.Om15? {
        val t = m?.optJSONArray("time") ?: return null
        val p = m.optJSONArray("precipitation")
        val c = m.optJSONArray("weather_code")
        var first: Long? = null
        var firstMm: Double? = null
        var wet = 0
        for (i in 0 until t.length()) {
            val ms = (t.wxLongAt(i) ?: continue) * 1000L
            if (ms + 15 * 60_000L < nowMs) continue
            val mm = p?.wxDblAt(i) ?: 0.0
            val code = c?.wxIntAt(i) ?: 0
            if (mm >= 0.1 || code in 51..67 || code in 80..82 || code >= 95) {
                wet++
                if (first == null) { first = ms; firstMm = mm }
            }
        }
        return HioneNowcast.Om15(nowMs, first, firstMm, wet)
    }

    /**
     * The coming night: now until the next 10:00 local. Air temperature at 2 m: frost can form on
     * the ground from ~2 C (36 F), a freeze is <= 0 C, a hard freeze <= -2.2 C (28 F, NWS).
     */
    private fun frostOutlook(h: org.json.JSONObject?, nowMs: Long): Frost? {
        val t = h?.optJSONArray("time") ?: return null
        val temp = h.optJSONArray("temperature_2m") ?: return null
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val end = (cal.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 10); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= nowMs) add(Calendar.DAY_OF_MONTH, 1)
        }.timeInMillis
        var minC: Double? = null
        var at = 0L
        for (i in 0 until t.length()) {
            val ms = (t.wxLongAt(i) ?: continue) * 1000L
            if (ms < nowMs - 3_600_000L || ms > end) continue
            val c = temp.wxDblAt(i) ?: continue
            if (minC == null || c < minC) { minC = c; at = ms }
        }
        val m = minC ?: return null
        val level = when { m <= -2.2 -> "hard freeze"; m <= 0.0 -> "freeze"; m <= 2.0 -> "frost"; else -> return null }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val word = when (level) { "hard freeze" -> "Hard freeze"; "freeze" -> "Freeze"; else -> "Frost possible" }
        val whenTxt = if (hour >= 10 || hour < 4) "tonight" else "this morning"
        return Frost(level, m, at, "$word $whenTxt")
    }

    // ------------------------------------------------------------------ Windy point forecast

    private suspend fun refreshWindy(app: Context, lat: Double, lon: Double, force: Boolean) {
        val key = HioneWindyKeys.ensureLoaded(app).point
        if (key.isBlank()) { _state.update { it.copy(windy = null, windyError = "") }; return }
        val s = _state.value
        if (!force && s.windy != null && !moved(s, lat, lon) && System.currentTimeMillis() - s.windyFetchedMs < CORE_MS) return
        if (s.windyBusy) return
        _state.update { it.copy(windyBusy = true) }
        try {
            val w = HioneWeatherTileSources.fetchWindy(lat, lon, key)
            Log.i(TAG, "Windy point forecast: ${w.hourly.size} steps, model ${w.model}" + if (!w.warning.isNullOrBlank()) ", warning set" else "")
            _state.update { it.copy(windy = w, windyFetchedMs = System.currentTimeMillis(), windyError = "", windyBusy = false) }
        } catch (t: Throwable) {
            val msg = t.message.orEmpty()
            Log.w(TAG, "Windy point forecast failed: ${msg.take(80)}")
            _state.update {
                it.copy(windyBusy = false, windyError = when {
                    msg.contains("HTTP 400") || msg.contains("HTTP 401") || msg.contains("HTTP 403") -> "Windy turned the key down. Check it on api.windy.com/keys."
                    msg.contains("HTTP 429") -> "Windy says the key is over its request limit for now."
                    t is java.net.UnknownHostException || t is java.net.SocketTimeoutException -> "Offline"
                    else -> "Windy did not answer (${msg.take(40)})"
                })
            }
        }
    }

    // ------------------------------------------------------------------ Windy webcams

    /** CAMS tab: fetch the nearby list if there is a key and the list is old (image links expire). */
    fun refreshWebcams(ctx: Context, force: Boolean = false) {
        val (lat, lon) = position() ?: return
        val s = _state.value
        if (!force && s.webcams.isNotEmpty() && !moved(s, lat, lon) && System.currentTimeMillis() - s.webcamsFetchedMs < WEBCAM_MS) return
        HioneWindyKeys.ensureLoaded(ctx)
        scope.launch { refreshWebcamsNow(lat, lon) }
    }

    private fun refreshWebcamsNow(lat: Double, lon: Double) {
        val key = HioneWindyKeys.state.value.webcam
        if (key.isBlank() || _state.value.webcamBusy) return
        _state.update { it.copy(webcamBusy = true) }
        try {
            val url = "https://api.windy.com/webcams/api/v3/webcams?nearby=" +
                String.format(Locale.US, "%.4f,%.4f,80", lat, lon) + "&limit=15&include=location,images,urls"
            val j = WxHttp.json(url, 10_000, mapOf("x-windy-api-key" to key))
            val arr = j.optJSONArray("webcams")
            val list = ArrayList<Webcam>()
            if (arr != null) for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val cur = o.optJSONObject("images")?.optJSONObject("current")
                val img = cur?.optString("preview").orEmpty().ifBlank { cur?.optString("thumbnail").orEmpty() }
                if (img.isBlank()) continue
                val loc = o.optJSONObject("location")
                val place = listOf(loc?.optString("city").orEmpty(), loc?.optString("region").orEmpty(), loc?.optString("country").orEmpty())
                    .filter { it.isNotBlank() }.distinct().joinToString(", ")
                val updated = o.optString("lastUpdatedOn").takeIf { it.isNotBlank() }?.let { iso ->
                    runCatching { java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()
                }
                list.add(Webcam(o.optString("webcamId", i.toString()), o.optString("title", "Webcam"), place, img,
                    o.optJSONObject("urls")?.optString("detail").orEmpty(), updated))
            }
            Log.i(TAG, "webcams nearby: ${list.size}")
            _state.update {
                it.copy(webcams = list, webcamsFetchedMs = System.currentTimeMillis(), webcamBusy = false,
                    webcamError = if (list.isEmpty()) "No webcams within 80 km." else "")
            }
        } catch (t: Throwable) {
            val code = (t as? WxHttp.HttpException)?.code
            Log.w(TAG, "webcams failed: ${code ?: t.javaClass.simpleName}")
            _state.update {
                it.copy(webcamBusy = false, webcamError = when (code) {
                    401, 403 -> "Windy turned the webcam key down. Check it on api.windy.com/keys."
                    429 -> "Windy says the webcam key is over its request limit for now."
                    null -> "Could not reach Windy."
                    else -> "Windy webcams answered HTTP $code."
                })
            }
        }
    }

    // ------------------------------------------------------------------ notifications

    @Volatile private var lastRainLevel = ""

    /** Called by the nowcast engine after every analysis. Notifies on the way in only, at most once an hour. */
    fun onNowcast(ctx: Context, s: HioneNowcast.Summary) {
        val was = lastRainLevel
        lastRainLevel = s.level
        if (!notifyRain(ctx)) return
        if (s.level != "now" && s.level != "soon") return
        if (was == "now" || was == "soon") return
        val p = prefs(ctx)
        if (System.currentTimeMillis() - p.getLong("last_rain_notify", 0L) < 60 * 60_000L) return
        p.edit().putLong("last_rain_notify", System.currentTimeMillis()).apply()
        val place = placeName()
        post(ctx, 0x5741_0001, if (s.level == "now") "Raining at $place" else "${s.short} at $place",
            s.text + if (s.detail.isNotBlank()) "\n" + s.detail else "")
    }

    private fun checkFrost(ctx: Context) {
        if (!notifyFrost(ctx)) return
        val f = _state.value.frost ?: return
        val cal = Calendar.getInstance()
        if (cal.get(Calendar.HOUR_OF_DAY) < 12) return // warn the afternoon or evening before
        val today = String.format(Locale.US, "%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
        val p = prefs(ctx)
        if (p.getString("last_frost_day", "") == today) return
        p.edit().putString("last_frost_day", today).apply()
        val low = WxFmt.tempUnit(f.minC, HioneWeatherTileEngine.state.value.units)
        val at = java.text.SimpleDateFormat("h a", Locale.US).format(java.util.Date(f.atMs))
        post(ctx, 0x5741_0002, "${f.text} at ${placeName()}", "Low of $low around $at. Cover plants, bring pets in.")
    }

    private fun placeName(): String {
        val g = HioneWeatherService.state.value.gps
        return g.city.ifBlank { "your spot" }
    }

    private fun post(ctx: Context, id: Int, title: String, body: String) {
        runCatching {
            HioneNwsAlerts.ensureNotificationPermission(ctx)
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Rain and frost", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Rain within 30 minutes from the radar nowcast, and frost or freeze nights"
                })
            }
            val n = android.app.Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body.lineSequence().first())
                .setStyle(android.app.Notification.BigTextStyle().bigText(body))
                .setContentIntent(com.hione.launcher.widget.HioneBatteryWidget.launchLauncher(ctx))
                .setAutoCancel(true)
                .setTimeoutAfter(3 * 3_600_000L)
                .build()
            nm.notify(id, n)
            Log.i(TAG, "notified: $title")
        }.onFailure { Log.w(TAG, "notify failed: $it") }
    }

    /** Whole degrees in the user's units, for chips. */
    fun tempLabel(c: Double) = WxFmt.tempUnit(c, HioneWeatherTileEngine.state.value.units)

    fun aqiLabel(a: AirPollen?) = a?.usAqi?.let { "$it" } ?: WxFmt.DASH

    fun pollenLabel(a: AirPollen?) = if (a?.pollenKind != null && a.pollenLevel != null) "${a.pollenKind} ${a.pollenLevel}" else null

    fun pollenDetail(a: AirPollen?) = a?.pollenValue?.let { "${it.roundToInt()} grains/m3" }
}
