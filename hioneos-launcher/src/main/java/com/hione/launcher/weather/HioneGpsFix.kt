package com.hione.launcher.weather

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * A single GPS fix, taken deliberately and then left alone.
 *
 * WHY THIS IS NOT JUST `requestLocationUpdates`. The M500 has real GNSS hardware: it declares
 * `android.hardware.location.gps`, the provider registers with `requires=satellite` and a real HAL
 * (`GNSS Hardware Model Name: aidl-impl`). The launcher used to use it, and it was pulled out
 * because it flattened the battery. On a device whose whole job is playing music for a day on one
 * charge, a continuously subscribed GPS is not a rounding error.
 *
 * So this never subscribes. It asks for ONE fix through `getCurrentLocation`, with a cancellation
 * signal and a hard timeout, and the radio goes back to sleep either way. The call site is the
 * weather refresh, which is already throttled, so the worst case is one bounded acquisition per
 * refresh window rather than a permanent listener.
 *
 * WHY IT IS WORTH DOING AT ALL. The previous code only ever called `getLastKnownLocation`, which
 * is a passive read of a cache somebody else has to fill. Nothing on this device fills it: the
 * dump showed `locations = 0` for every provider over ten hours and the GNSS service active for
 * 2.6 seconds total. That branch could never return anything, so position always fell through to
 * Wi-Fi scanning, which is the source with the known travelling-access-point failure mode. Asking
 * the satellites directly removes the guesswork when it works.
 *
 * WHY WI-FI STAYS. A GPS fix needs sky. This is a pocket player used indoors most of the time, so
 * acquisition will often fail, and it takes tens of seconds when it succeeds from cold. GPS is the
 * preferred source, not the only one, and the caller falls through on null exactly as before.
 */
object HioneGpsFix {

    private const val TAG = "HioneGpsFix"
    private const val PREFS = "hione_gps_fix"
    private const val K_LAT = "lat"
    private const val K_LON = "lon"
    private const val K_TIME = "time"

    /**
     * How long a fix stays good enough to reuse without waking the radio.
     *
     * Two hours is chosen against how this device actually moves: it travels by car or plane, in
     * which case the position is stale in minutes and the fix that matters is the one taken after
     * arrival, or it sits on a desk for days. A short window would burn the radio on a device that
     * has not moved; a long one would keep reporting the last airport. Two hours also comfortably
     * exceeds the weather refresh interval, so a stationary device normally answers from cache and
     * never powers the GNSS at all.
     */
    private const val MAX_FIX_AGE_MS = 2L * 60L * 60L * 1000L

    /**
     * Give up after this long and let Wi-Fi answer.
     *
     * A cold start with no almanac can legitimately take two or three minutes, but the user is
     * waiting on a weather tile, and a fix that arrives after the refresh has already completed is
     * worth nothing this pass. It gets written to the cache regardless if it lands late, so the
     * slow acquisition still pays off on the NEXT refresh.
     */
    private const val TIMEOUT_MS = 55_000L

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** The last fix we took, or null when there is none or it has aged out. */
    fun cached(ctx: Context, maxAgeMs: Long = MAX_FIX_AGE_MS): Location? {
        val p = prefs(ctx)
        val t = p.getLong(K_TIME, 0L)
        if (t <= 0L) return null
        val age = System.currentTimeMillis() - t
        if (age !in 0..maxAgeMs) return null
        val lat = java.lang.Double.longBitsToDouble(p.getLong(K_LAT, 0L))
        val lon = java.lang.Double.longBitsToDouble(p.getLong(K_LON, 0L))
        if (lat == 0.0 && lon == 0.0) return null
        return Location(LocationManager.GPS_PROVIDER).apply {
            latitude = lat; longitude = lon; time = t
        }
    }

    private fun store(ctx: Context, l: Location) {
        prefs(ctx).edit()
            .putLong(K_LAT, java.lang.Double.doubleToRawLongBits(l.latitude))
            .putLong(K_LON, java.lang.Double.doubleToRawLongBits(l.longitude))
            .putLong(K_TIME, if (l.time > 0L) l.time else System.currentTimeMillis())
            .apply()
    }

    /**
     * Return a GPS position: the cached one when it is fresh, otherwise one bounded acquisition.
     * Null means no fix this time, and the caller should fall through to its other sources.
     */
    @SuppressLint("MissingPermission")
    suspend fun acquire(ctx: Context): Location? {
        if (!hasPermission(ctx)) {
            Log.d(TAG, "no ACCESS_FINE_LOCATION, skipping")
            return null
        }
        cached(ctx)?.let {
            Log.d(TAG, "reusing cached fix, age ${(System.currentTimeMillis() - it.time) / 1000}s")
            return it
        }

        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        if (!runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)) {
            Log.d(TAG, "GPS provider disabled")
            return null
        }

        // getCurrentLocation is the single-shot API: it delivers at most one result and tears the
        // request down itself, which is the property that matters here. The CancellationSignal is
        // what enforces the timeout, and cancelling it releases the provider.
        val started = System.currentTimeMillis()
        val fix = suspendCancellableCoroutine<Location?> { cont ->
            val signal = CancellationSignal()
            val timer = java.util.Timer()
            timer.schedule(object : java.util.TimerTask() {
                override fun run() {
                    runCatching { signal.cancel() }
                    if (cont.isActive) cont.resume(null)
                }
            }, TIMEOUT_MS)

            cont.invokeOnCancellation {
                runCatching { signal.cancel() }
                runCatching { timer.cancel() }
            }

            runCatching {
                lm.getCurrentLocation(
                    LocationManager.GPS_PROVIDER,
                    signal,
                    ctx.mainExecutor
                ) { loc ->
                    runCatching { timer.cancel() }
                    if (cont.isActive) cont.resume(loc)
                }
            }.onFailure {
                runCatching { timer.cancel() }
                if (cont.isActive) cont.resume(null)
            }
        }

        val took = System.currentTimeMillis() - started
        if (fix == null) {
            Log.i(TAG, "no GPS fix after ${took / 1000}s, falling back")
            return null
        }
        store(ctx, fix)
        Log.i(TAG, "GPS fix in ${took / 1000}s: ${fix.latitude}, ${fix.longitude} (+/- ${fix.accuracy}m)")
        return fix
    }
}
