package com.m500.hardware

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import org.sworrl.beaconfix.lite.Heard
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.sworrl.beaconfix.lite.android.BeaconFixLite

/**
 * Where the device is, from every source it has, published once for the whole OS.
 *
 * The M500 has three ways to know: a GNSS receiver, a 4G modem, and the Wi-Fi it can hear.
 * Each is wrong in a different way. GNSS is the most accurate and the most expensive, and
 * indoors it is often simply absent. The modem's network location is cheap and coarse. Wi-Fi
 * positioning through BeaconFix Lite is cheap after the first lookup, works indoors, and is
 * the only one of the three that gets *better* the more you use a place.
 *
 * So this does not pick a favourite. It takes whichever fix is best right now, judged on
 * accuracy and age together, and says which one it used. Anything that wants a position reads
 * one set of Settings.Global keys and does not care how it was obtained.
 *
 * BATTERY. This is the thing that burned a Pixel: BeaconFix proper held GNSS at high accuracy
 * every 2.5 seconds for days. Nothing here polls GNSS. It registers for passive updates, so it
 * sees any fix some *other* app already paid for and costs nothing when nothing else is asking.
 * Wi-Fi is consulted on the Lite library's own schedule, which backs off to two hours while
 * nothing changes and tightens to two minutes when the device has moved. An active GNSS request
 * happens only when something explicitly asks for a sharp fix, and stops as soon as it has one.
 */
object HioneLocationFusion {

    private const val TAG = "HioneLocationFusion"

    /** Published for any HioneOS app. Read these; do not request location yourself. */
    const val KEY_LAT = "hione_loc_lat"
    const val KEY_LON = "hione_loc_lon"
    const val KEY_ACC = "hione_loc_accuracy_m"
    const val KEY_SRC = "hione_loc_source"          // gnss | network | wifi | none
    const val KEY_AT = "hione_loc_time"             // epoch ms of the fix itself
    const val KEY_DETAIL = "hione_loc_detail"       // human sentence, for a diagnostics screen

    /**
     * How much staleness an accuracy advantage is worth.
     *
     * A 10 m GNSS fix from five minutes ago usually beats a 90 m Wi-Fi fix from now, but not if
     * you have been walking. Scoring both together, rather than always preferring one sensor,
     * is what stops the position jumping between sources that each think they are right.
     */
    private const val METRES_PER_SECOND_STALE = 1.2

    /**
     * How old a Wi-Fi scan may be and still be used to place us.
     *
     * This matters more than it looks. Once Wi-Fi is associated the framework stops scanning,
     * so on a device sitting still the scan cache goes stale and stays stale - measured at over
     * sixteen minutes here while seven access points were in range. With a tighter window the
     * library correctly reported "no fix from 0 APs" forever, which read exactly like having no
     * Wi-Fi at all. Ten minutes is long enough to survive that and short enough that a scan
     * taken somewhere else has usually aged out before it can place us there.
     */
    private const val SCAN_MAX_AGE_MS = 10 * 60 * 1000L

    /** How long to wait for a scan we asked for before going with whatever is cached. */
    private const val SCAN_WAIT_MS = 8_000L

    /** Below this, a published position is good enough that no active request is worth it. */
    private const val BOOTSTRAP_IF_OLDER_MS = 6 * 60 * 60 * 1000L

    /** An active one-shot fix gives up after this. GNSS indoors usually never answers. */
    private const val ONESHOT_TIMEOUT_MS = 90_000L

    private data class Candidate(val lat: Double, val lon: Double, val accM: Double,
                                 val atMs: Long, val source: String, val detail: String) {
        fun cost(nowMs: Long): Double =
            accM + ((nowMs - atMs).coerceAtLeast(0L) / 1000.0) * METRES_PER_SECOND_STALE
    }

    @Volatile private var lite: BeaconFixLite? = null
    @Volatile private var lastPublished: Candidate? = null
    @Volatile private var nextWifiAt = 0L

    fun start(ctx: Context) {
        val app = ctx.applicationContext
        if (lite == null) {
            // SSIDs that move with the device would otherwise be learned as fixed landmarks and
            // drag every later fix toward wherever they were first heard.
            lite = runCatching {
                BeaconFixLite(app, travelling = travellingSsids(app), scanMaxAgeMs = SCAN_MAX_AGE_MS)
            }.onFailure { Log.w(TAG, "BeaconFix Lite unavailable: $it") }.getOrNull()
        }
        registerPassive(app)
        bootstrap(app)
    }

    /**
     * Get one fix the expensive way, but only if we have nothing worth keeping.
     *
     * Passive updates alone are not enough to ever start. They only report fixes some other app
     * paid for, and on this device nothing else asks - so a fresh install could sit forever
     * knowing nothing, with Wi-Fi unable to help because it had never been told where any of
     * the access points it can hear actually are. One real fix breaks that circle: it publishes
     * a position and gives the Wi-Fi database something to learn against.
     *
     * Both requests are single-shot and remove themselves, so this is one fix, not a GNSS
     * engine left running. That distinction is the whole battery story: holding GNSS at high
     * accuracy is what flattened a phone in a day on the original BeaconFix.
     */
    @SuppressLint("MissingPermission")
    private fun bootstrap(ctx: Context) {
        val have = lastKnown(ctx)
        if (have != null && System.currentTimeMillis() - have.atMs < BOOTSTRAP_IF_OLDER_MS) return
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        // Network first: on a data SIM it is a cell-tower lookup that answers in seconds and
        // costs almost nothing. GNSS in parallel, because when it does answer it is far better.
        for (provider in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)) {
            if (!runCatching { lm.isProviderEnabled(provider) }.getOrDefault(false)) continue
            requestOneShot(ctx, lm, provider)
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestOneShot(ctx: Context, lm: LocationManager, provider: String) {
        val main = android.os.Handler(Looper.getMainLooper())
        // One listener object, so the timeout and the fix can both unregister the same thing.
        val listener = object : LocationListener {
            private var done = false
            override fun onLocationChanged(loc: Location) {
                if (done) return
                done = true
                runCatching { lm.removeUpdates(this) }
                Log.i(TAG, "bootstrap fix from $provider")
                onSystemFix(ctx, loc)
            }
            override fun onProviderDisabled(p: String) {}
            override fun onProviderEnabled(p: String) {}
            fun giveUp() {
                if (done) return
                done = true
                runCatching { lm.removeUpdates(this) }
                Log.i(TAG, "bootstrap: $provider did not answer in " +
                    "${ONESHOT_TIMEOUT_MS / 1000}s; leaving it to passive and Wi-Fi")
            }
        }
        runCatching {
            lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            main.postDelayed({ listener.giveUp() }, ONESHOT_TIMEOUT_MS)
            Log.i(TAG, "bootstrap: asked $provider for one fix")
        }.onFailure { Log.w(TAG, "bootstrap: $provider unavailable: $it") }
    }

    /**
     * Names the user has said travel with them, plus the obvious ones.
     *
     * This device lives in an RV, where most of what it hears is its own gear. Lite already
     * screens hotspots and vehicle Wi-Fi by name; this adds whatever else the user listed.
     */
    private fun travellingSsids(ctx: Context): List<String> =
        runCatching {
            Settings.Global.getString(ctx.contentResolver, "hione_travelling_ssids")
                ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        }.getOrDefault(emptyList())

    /**
     * Passive GNSS and network: we are told about fixes other apps requested, and pay nothing
     * when nobody is asking. This is the difference between knowing where you are and keeping
     * the GNSS engine hot.
     */
    @SuppressLint("MissingPermission")
    private fun registerPassive(ctx: Context) {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        runCatching {
            lm.requestLocationUpdates(
                LocationManager.PASSIVE_PROVIDER, 30_000L, 50f
            ) { loc -> onSystemFix(ctx, loc) }
        }.onFailure { Log.w(TAG, "passive updates unavailable: $it") }
    }

    private fun onSystemFix(ctx: Context, loc: Location) {
        val src = when (loc.provider) {
            LocationManager.GPS_PROVIDER -> "gnss"
            LocationManager.NETWORK_PROVIDER -> "network"
            else -> loc.provider ?: "system"
        }
        consider(ctx, Candidate(
            loc.latitude, loc.longitude,
            if (loc.hasAccuracy()) loc.accuracy.toDouble() else 500.0,
            loc.time, src, "$src fix, ±${loc.accuracy.toInt()} m"))
    }

    /**
     * Ask Wi-Fi where we are, on Lite's own pacing.
     *
     * Call it as often as you like: it returns immediately unless the library says it is worth
     * looking again. A repeat look in the same place costs about a millisecond and touches
     * neither the network nor the disk.
     */
    fun pollWifi(ctx: Context) {
        val l = lite ?: return
        // This blocks on a Wi-Fi scan and an HTTP lookup. Calling it on the main thread throws
        // NetworkOnMainThreadException, which the runCatching below would swallow into a single
        // warning - so say it plainly instead of failing quietly for another whole image.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.e(TAG, "pollWifi called on the main thread; it blocks on a scan and a network " +
                "lookup. Call it from a background looper.")
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now < nextWifiAt) return
        runCatching {
            // Ask for a scan rather than hoping one is cached. An associated device stops
            // scanning on its own, so without this the library is handed results that are
            // minutes old, discards them all, and reports hearing nothing.
            requestFreshScan(ctx)
            var fix = l.locate()
            nextWifiAt = now + l.nextCheckMs
            var stale = false
            if (fix == null) {
                // The library's own path discards access points whose scan is older than its
                // age window, and on this device that throws away everything: the Wi-Fi stack
                // does not scan at all once associated, so the newest results are whatever boot
                // produced. Measured here: seven access points in range, last scan 38 minutes
                // old, startScan() refused, so the age filter left nothing and the result was
                // indistinguishable from having no Wi-Fi.
                //
                // Those access points are still real and still where they were. So hand them to
                // the locator directly, with the connected one included, and let the caller see
                // from the detail string that the sighting is not fresh.
                val heard = heardNow(ctx)
                if (heard.isNotEmpty()) {
                    fix = runCatching { l.locator.locate(heard) }
                        .onFailure { Log.w(TAG, "direct locate failed: $it") }.getOrNull()
                    stale = true
                }
            }
            if (fix == null) {
                Log.d(TAG, "wifi could not place us (heard nothing it knows)")
                return
            }
            consider(ctx, Candidate(
                fix.lat, fix.lon, fix.accM, fix.timeMs, "wifi",
                "wifi fix from ${fix.used} of ${fix.heard} APs, ±${fix.accM.toInt()} m, " +
                    "integrity ${fix.integrity}" +
                    if (stale) " (from the last scan this device managed, not a fresh one)" else ""))
        }.onFailure { Log.w(TAG, "wifi locate failed: $it") }
    }

    /**
     * Every access point we can name right now, fresh or not.
     *
     * The cached scan plus the one we are associated to. The connected AP is worth adding by
     * hand because it is the single sighting we can be certain is current - if the device has
     * been carried somewhere since the last scan, it is the one entry that says so, and the
     * locator's own agreement check is what notices the rest no longer fit.
     */
    @SuppressLint("MissingPermission")
    private fun heardNow(ctx: Context): List<Heard> {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return emptyList()
        val out = LinkedHashMap<String, Heard>()
        runCatching {
            for (r in wifi.scanResults.orEmpty()) {
                val bssid = r.BSSID ?: continue
                out[bssid.lowercase()] = Heard(bssid, r.level, r.frequency, r.SSID ?: "")
            }
        }.onFailure { Log.w(TAG, "could not read scan results: $it") }
        runCatching {
            @Suppress("DEPRECATION")
            val info = wifi.connectionInfo
            val bssid = info?.bssid
            // "02:00:00:00:00:00" is what the framework hands back when it is withholding the
            // BSSID; treating it as an access point would poison the lookup.
            if (bssid != null && bssid != "02:00:00:00:00:00" && bssid.contains(':')) {
                @Suppress("DEPRECATION")
                out[bssid.lowercase()] = Heard(bssid, info.rssi, info.frequency,
                                               info.ssid?.trim('"') ?: "")
            }
        }.onFailure { Log.w(TAG, "could not read the connected AP: $it") }
        if (out.isNotEmpty()) Log.i(TAG, "placing from ${out.size} access point(s) by hand")
        return out.values.toList()
    }

    /**
     * Trigger a Wi-Fi scan and wait briefly for the results broadcast.
     *
     * startScan() is deprecated and throttled to a few calls per two minutes, and returns false
     * when it refuses. That is fine: a refusal means a recent scan already exists, which is
     * exactly what we wanted. Either way we go on to locate() with whatever is in the cache.
     */
    @SuppressLint("MissingPermission")
    private fun requestFreshScan(ctx: Context) {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        if (!wifi.isWifiEnabled) return
        val latch = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) = latch.countDown()
        }
        runCatching {
            ctx.applicationContext.registerReceiver(
                receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
                Context.RECEIVER_NOT_EXPORTED)
            @Suppress("DEPRECATION")
            val accepted = wifi.startScan()
            if (accepted) latch.await(SCAN_WAIT_MS, TimeUnit.MILLISECONDS)
        }.onFailure { Log.w(TAG, "could not ask for a Wi-Fi scan: $it") }
        runCatching { ctx.applicationContext.unregisterReceiver(receiver) }
    }

    /** Take the new fix only if it actually beats what is already published. */
    private fun consider(ctx: Context, c: Candidate) {
        val now = System.currentTimeMillis()
        val best = lastPublished
        if (best != null && best.cost(now) <= c.cost(now)) return
        lastPublished = c
        publish(ctx, c)
    }

    private fun publish(ctx: Context, c: Candidate) {
        val cr = ctx.contentResolver
        runCatching {
            Settings.Global.putString(cr, KEY_LAT, "%.7f".format(java.util.Locale.US, c.lat))
            Settings.Global.putString(cr, KEY_LON, "%.7f".format(java.util.Locale.US, c.lon))
            Settings.Global.putString(cr, KEY_ACC, "%.1f".format(java.util.Locale.US, c.accM))
            Settings.Global.putString(cr, KEY_SRC, c.source)
            Settings.Global.putLong(cr, KEY_AT, c.atMs)
            Settings.Global.putString(cr, KEY_DETAIL, c.detail)
            Log.i(TAG, "position: ${c.detail}")
        }.onFailure { Log.w(TAG, "could not publish the position: $it") }
    }

    /** What anything else in HioneOS should call. Null when nothing has placed us yet. */
    data class Fix(val lat: Double, val lon: Double, val accM: Double,
                   val source: String, val atMs: Long)

    fun lastKnown(ctx: Context): Fix? = runCatching {
        val cr = ctx.contentResolver
        val lat = Settings.Global.getString(cr, KEY_LAT)?.toDoubleOrNull() ?: return null
        val lon = Settings.Global.getString(cr, KEY_LON)?.toDoubleOrNull() ?: return null
        Fix(lat, lon,
            Settings.Global.getString(cr, KEY_ACC)?.toDoubleOrNull() ?: 1000.0,
            Settings.Global.getString(cr, KEY_SRC) ?: "unknown",
            Settings.Global.getLong(cr, KEY_AT, 0L))
    }.getOrNull()
}
