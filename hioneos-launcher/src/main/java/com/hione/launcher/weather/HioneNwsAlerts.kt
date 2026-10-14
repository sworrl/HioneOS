package com.hione.launcher.weather

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Live National Weather Service alerts for where the device is: the same watches and warnings
 * NOAA Weather Radio reads out, fetched from api.weather.gov instead of the 162 MHz band (which
 * this hardware cannot tune). Plus the nearest NWR transmitters ([HioneNoaaRadio]).
 *
 * Refreshes at most every 10 minutes, sooner when the position moves more than 10 km. US only:
 * api.weather.gov has nothing for other countries, so outside the US this stays quiet and empty.
 *
 * Publishes a summary to Settings.Global for widgets and other HioneOS apps:
 *   hione_weather_alert_count     number of active alerts (0 when none or outside the US)
 *   hione_weather_alert_top       "Severe Thunderstorm Warning until 3:15 PM", "" when none
 *   hione_weather_alert_severity  Extreme / Severe / Moderate / Minor / Unknown, "" when none
 *   hione_weather_alert_updated   epoch ms of the last successful check
 *   hione_weather_nwr             "KWN35 WX3 162.475", "" when no transmitter is in reach
 *   hione_weather_nwr_km          distance to that transmitter, whole km
 *   hione_weather_alert_notify    1 (default) / 0: post a notification for new Severe/Extreme alerts
 */
object HioneNwsAlerts {
    private const val TAG = "HioneNwsAlerts"
    private const val UA = "HioneOS (https://github.com/sworrl/HioneOS)"
    private const val MIN_INTERVAL_MS = 10 * 60 * 1000L
    private const val MOVE_KM = 10.0
    private const val PREFS = "hione_nws_alerts"
    private const val CHANNEL = "hione_weather_alerts"

    const val KEY_COUNT = "hione_weather_alert_count"
    const val KEY_TOP = "hione_weather_alert_top"
    const val KEY_SEVERITY = "hione_weather_alert_severity"
    const val KEY_UPDATED = "hione_weather_alert_updated"
    const val KEY_NWR = "hione_weather_nwr"
    const val KEY_NWR_KM = "hione_weather_nwr_km"
    const val KEY_NOTIFY = "hione_weather_alert_notify"

    data class Alert(
        val id: String,
        val event: String,
        val headline: String,
        val severity: String,
        val urgency: String,
        val certainty: String,
        val areaDesc: String,
        val effectiveMs: Long,
        val expiresMs: Long,
        val endsMs: Long,
        val description: String,
        val instruction: String,
        val senderName: String,
    ) {
        val rank: Int get() = severityRank(severity)
        /** When it stops applying: "ends" if NWS gave one, else "expires". */
        val untilMs: Long get() = if (endsMs > 0) endsMs else expiresMs
        val shortLine: String get() = if (untilMs > 0) "$event until ${clock(untilMs)}" else event
    }

    data class State(
        val alerts: List<Alert> = emptyList(),
        val radios: List<HioneNoaaRadio.Transmitter> = emptyList(),
        val inUs: Boolean = true,
        val lastCheckMs: Long = 0L,
        val error: String? = null,
        val hasLocation: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var loop: Job? = null
    private var lastFetchMs = 0L
    private var lastLat = Double.NaN
    private var lastLon = Double.NaN

    fun severityRank(s: String): Int = when (s.lowercase(Locale.US)) {
        "extreme" -> 4; "severe" -> 3; "moderate" -> 2; "minor" -> 1; else -> 0
    }

    fun clock(ms: Long): String {
        val now = System.currentTimeMillis()
        val sameDay = SimpleDateFormat("yyyyMMdd", Locale.US).let { it.format(Date(ms)) == it.format(Date(now)) }
        return SimpleDateFormat(if (sameDay) "h:mm a" else "EEE h:mm a", Locale.US).format(Date(ms))
    }

    @Synchronized
    fun start(ctx: Context) {
        if (loop != null) return
        val app = ctx.applicationContext
        restoreCache(app)
        loop = scope.launch {
            delay(5_000L)   // let the weather service find a position first
            while (isActive) {
                runCatching { tick(app, force = false) }.onFailure { Log.w(TAG, "tick failed: $it") }
                delay(60_000L)
            }
        }
    }

    /** Pull now regardless of the 10 minute limit (the refresh button). */
    fun refreshNow(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch { runCatching { tick(app, force = true) } }
    }

    fun notifyEnabled(ctx: Context): Boolean =
        runCatching { Settings.Global.getInt(ctx.contentResolver, KEY_NOTIFY, 1) != 0 }.getOrDefault(true)

    fun setNotifyEnabled(ctx: Context, on: Boolean) {
        runCatching { Settings.Global.putInt(ctx.contentResolver, KEY_NOTIFY, if (on) 1 else 0) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("notify", on).apply()
    }

    // ------------------------------------------------------------------ position

    private data class Point(val lat: Double, val lon: Double, val country: String)

    private fun position(): Point? {
        val g = HioneWeatherService.state.value.gps
        if (g.latitude != 0.0 || g.longitude != 0.0) return Point(g.latitude, g.longitude, g.country)
        HioneWeatherTileEngine.state.value.location?.let { return Point(it.lat, it.lon, "") }
        return null
    }

    /** US states and territories. A name wins when geocoding gave one; otherwise rough boxes. */
    private fun isUs(p: Point): Boolean {
        val c = p.country.trim().lowercase(Locale.US)
        if (c.isNotEmpty()) return c == "us" || c == "usa" || c == "united states" || c == "united states of america"
        val (la, lo) = p.lat to p.lon
        return (la in 24.3..49.6 && lo in -125.0..-66.8) ||    // lower 48
            (la in 51.0..71.6 && (lo in -180.0..-129.9 || lo in 172.0..180.0)) || // Alaska
            (la in 18.5..22.6 && lo in -161.0..-154.5) ||       // Hawaii
            (la in 17.5..18.7 && lo in -68.1..-64.4) ||         // Puerto Rico, USVI
            (la in 13.0..21.0 && lo in 144.0..146.5) ||         // Guam, N. Marianas
            (la in -14.8..-10.9 && lo in -171.2..-168.0)        // American Samoa
    }

    // ------------------------------------------------------------------ fetch

    private fun tick(ctx: Context, force: Boolean) {
        val p = position()
        if (p == null) {
            _state.value = _state.value.copy(hasLocation = false)
            return
        }
        val now = System.currentTimeMillis()
        val moved = lastLat.isNaN() || HioneNoaaRadio.km(lastLat, lastLon, p.lat, p.lon) > MOVE_KM
        val due = now - lastFetchMs >= MIN_INTERVAL_MS
        if (!force && !due && !(moved && now - lastFetchMs >= 60_000L)) {
            pruneExpired(ctx)
            return
        }
        val radios = HioneNoaaRadio.nearest(ctx, p.lat, p.lon)
        if (!isUs(p)) {
            lastFetchMs = now; lastLat = p.lat; lastLon = p.lon
            _state.value = State(alerts = emptyList(), radios = emptyList(), inUs = false, lastCheckMs = now, hasLocation = true)
            publish(ctx)
            return
        }
        val fetched = fetch(p.lat, p.lon)
        lastFetchMs = now; lastLat = p.lat; lastLon = p.lon
        if (fetched == null) {
            // Keep what we had; it is still the best we know until it expires.
            _state.value = _state.value.copy(radios = radios, error = "Couldn't reach the Weather Service", hasLocation = true)
            pruneExpired(ctx)
            return
        }
        _state.value = State(alerts = fetched, radios = radios, inUs = true, lastCheckMs = now, error = null, hasLocation = true)
        saveCache(ctx, fetched, now)
        publish(ctx)
        notifyNew(ctx, fetched)
    }

    private fun fetch(lat: Double, lon: Double): List<Alert>? {
        val url = URL(String.format(Locale.US, "https://api.weather.gov/alerts/active?point=%.4f,%.4f", lat, lon))
        val c = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 10000
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/geo+json")
        }
        return try {
            when (c.responseCode) {
                200 -> parse(JSONObject(c.inputStream.bufferedReader().use { it.readText() }))
                // A point the NWS does not cover (offshore, border): nothing to warn about.
                400, 404 -> emptyList()
                else -> { Log.w(TAG, "alerts HTTP ${c.responseCode}"); null }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "alerts fetch failed: ${t.message}"); null
        } finally {
            c.disconnect()
        }
    }

    private fun iso(s: String?): Long {
        if (s.isNullOrBlank() || s == "null") return 0L
        return runCatching { java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrDefault(0L)
    }

    private fun parse(root: JSONObject): List<Alert> {
        val features = root.optJSONArray("features") ?: return emptyList()
        val now = System.currentTimeMillis()
        val out = ArrayList<Alert>(features.length())
        for (i in 0 until features.length()) {
            val f = features.optJSONObject(i) ?: continue
            val p = f.optJSONObject("properties") ?: continue
            fun s(k: String) = p.optString(k).takeIf { it != "null" }.orEmpty().trim()
            val a = Alert(
                id = s("id").ifBlank { f.optString("id") },
                event = s("event").ifBlank { "Weather alert" },
                headline = s("headline"),
                severity = s("severity").ifBlank { "Unknown" },
                urgency = s("urgency"),
                certainty = s("certainty"),
                areaDesc = s("areaDesc"),
                effectiveMs = iso(p.optString("effective")),
                expiresMs = iso(p.optString("expires")),
                endsMs = iso(p.optString("ends")),
                description = s("description"),
                instruction = s("instruction"),
                senderName = s("senderName"),
            )
            if (s("messageType").equals("Cancel", true)) continue
            if (a.untilMs in 1 until now) continue
            out.add(a)
        }
        return out.distinctBy { it.id }.sortedWith(compareByDescending<Alert> { it.rank }.thenByDescending { it.effectiveMs })
    }

    // ------------------------------------------------------------------ cache

    private fun toJson(a: Alert) = JSONObject()
        .put("id", a.id).put("event", a.event).put("headline", a.headline).put("severity", a.severity)
        .put("urgency", a.urgency).put("certainty", a.certainty).put("area", a.areaDesc)
        .put("eff", a.effectiveMs).put("exp", a.expiresMs).put("ends", a.endsMs)
        .put("desc", a.description).put("instr", a.instruction).put("sender", a.senderName)

    private fun fromJson(o: JSONObject) = Alert(
        o.optString("id"), o.optString("event"), o.optString("headline"), o.optString("severity"),
        o.optString("urgency"), o.optString("certainty"), o.optString("area"), o.optLong("eff"),
        o.optLong("exp"), o.optLong("ends"), o.optString("desc"), o.optString("instr"), o.optString("sender"),
    )

    private fun saveCache(ctx: Context, list: List<Alert>, at: Long) {
        val a = JSONArray(); list.forEach { a.put(toJson(it)) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("alerts", a.toString()).putLong("at", at).apply()
    }

    private fun restoreCache(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val list = runCatching {
            val a = JSONArray(p.getString("alerts", "[]"))
            List(a.length()) { fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        val now = System.currentTimeMillis()
        _state.value = _state.value.copy(alerts = list.filter { it.untilMs == 0L || it.untilMs > now }, lastCheckMs = p.getLong("at", 0L))
        publish(ctx)
    }

    private fun pruneExpired(ctx: Context) {
        val now = System.currentTimeMillis()
        val cur = _state.value.alerts
        val kept = cur.filter { it.untilMs == 0L || it.untilMs > now }
        if (kept.size != cur.size) {
            _state.value = _state.value.copy(alerts = kept)
            publish(ctx)
        }
    }

    // ------------------------------------------------------------------ publish

    private fun publish(ctx: Context) {
        val st = _state.value
        val top = st.alerts.firstOrNull()
        val radio = st.radios.firstOrNull { it.inService }
        val cr = ctx.contentResolver
        fun put(k: String, v: String) = runCatching {
            if (Settings.Global.getString(cr, k) != v) Settings.Global.putString(cr, k, v)
        }
        put(KEY_COUNT, st.alerts.size.toString())
        put(KEY_TOP, top?.shortLine ?: "")
        put(KEY_SEVERITY, top?.severity ?: "")
        put(KEY_NWR, radio?.shortLabel ?: "")
        put(KEY_NWR_KM, radio?.distanceKm?.toInt()?.toString() ?: "")
        if (st.lastCheckMs > 0) put(KEY_UPDATED, st.lastCheckMs.toString())
        runCatching { com.hione.launcher.widget.HioneWeatherWidget.pushUpdate(ctx) }
    }

    // ------------------------------------------------------------------ notify

    private fun notifyNew(ctx: Context, list: List<Alert>) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = p.getStringSet("notified", emptySet())!!.toMutableSet()
        val fresh = list.filter { it.rank >= 3 && it.id !in seen }
        if (fresh.isEmpty()) return
        // Recorded even when notifications are off, so turning them back on does not replay old ones.
        seen.addAll(fresh.map { it.id })
        val trimmed = if (seen.size > 200) seen.toList().takeLast(150).toSet() else seen
        p.edit().putStringSet("notified", trimmed).apply()
        if (!notifyEnabled(ctx)) return
        ensureNotificationPermission(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Weather alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Severe and extreme National Weather Service alerts for where you are"
                enableVibration(true)
            })
        }
        val tap = com.hione.launcher.widget.HioneBatteryWidget.launchLauncher(ctx)
        for (a in fresh) {
            val body = buildString {
                if (a.headline.isNotBlank()) append(a.headline)
                if (a.instruction.isNotBlank()) append("\n\n").append(a.instruction.take(600))
                if (a.areaDesc.isNotBlank()) append("\n\n").append(a.areaDesc.take(300))
            }
            val b = android.app.Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(a.shortLine)
                .setContentText(a.headline.ifBlank { a.areaDesc })
                .setStyle(android.app.Notification.BigTextStyle().bigText(body))
                .setCategory(android.app.Notification.CATEGORY_ALARM)
                .setContentIntent(tap)
                .setAutoCancel(true)
                .setShowWhen(true)
                .setWhen(if (a.effectiveMs > 0) a.effectiveMs else System.currentTimeMillis())
            val left = a.untilMs - System.currentTimeMillis()
            if (a.untilMs > 0 && left > 0) b.setTimeoutAfter(left)
            runCatching { nm.notify(a.id.hashCode(), b.build()) }
                .onFailure { Log.w(TAG, "notify failed: $it") }
        }
    }

    /** The launcher is platform-signed with GRANT_RUNTIME_PERMISSIONS; grant itself POST_NOTIFICATIONS once. */
    internal fun ensureNotificationPermission(ctx: Context) {
        if (Build.VERSION.SDK_INT < 33) return
        val perm = "android.permission.POST_NOTIFICATIONS"
        if (ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) return
        runCatching {
            PackageManager::class.java.getMethod(
                "grantRuntimePermission", String::class.java, String::class.java, android.os.UserHandle::class.java
            ).invoke(ctx.packageManager, ctx.packageName, perm, android.os.Process.myUserHandle())
        }.onFailure { Log.w(TAG, "could not grant $perm: $it") }
    }
}
