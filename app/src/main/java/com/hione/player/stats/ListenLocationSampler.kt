package com.hione.player.stats

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import com.hione.player.HionePowerGovernor
import com.hione.player.weather.HioneWeatherService

/**
 * Battery-smart coarse location for listen rows.
 *
 * Rules (the user was explicit — no continuous GPS, ever):
 *  - NEVER requests location updates. Only [LocationManager.getLastKnownLocation] on the passive /
 *    network / fused providers — i.e. whatever fix some other app or the weather service already
 *    paid for. If nobody has produced a fix recently, the answer is null, not a GPS wake-up.
 *  - At most one sample per track start, and the result is cached for [CACHE_TTL_MS] (10 min) so a
 *    burst of short tracks costs one lookup, not ten.
 *  - Respects the power governor (no work while the screen is off / idle) and the user's
 *    location toggle ([StatsPreferences.isLocationEnabled]) — and degrades to null gracefully if the
 *    permission is missing or any provider throws.
 *  - Coordinates are rounded to 3 decimals (~100 m) before storage: enough for "where do I listen"
 *    heatmaps, not a precise breadcrumb trail.
 */
object ListenLocationSampler {
    private const val CACHE_TTL_MS = 10 * 60_000L
    private const val MAX_FIX_AGE_MS = 60 * 60_000L   // a last-known fix older than an hour isn't "where I am"

    data class Sample(val lat: Double, val lon: Double, val label: String?)

    @Volatile private var cached: Sample? = null
    @Volatile private var cachedAtElapsed = 0L
    @Volatile private var cachedNull = false   // remember "nothing available" too, so we don't re-poll every track

    /**
     * Cheap: returns the cached sample when fresh; otherwise does one getLastKnownLocation pass.
     * Safe to call from a background thread.
     *
     * [allowPoll] is [HionePowerGovernor.allowLocation], read by the CALLER on the main thread.
     * It used to be read here — but the governor's fields are Compose state and this runs on the
     * listen-stats executor, and "no stats code touches a state object off the main thread" is now
     * an absolute rule (see ListenSessionTracker's THREADING RULE). false = screen off / idle:
     * reuse whatever we had, don't poll.
     */
    fun sample(ctx: Context, allowPoll: Boolean): Sample? {
        if (!StatsPreferences.isLocationEnabled(ctx)) return null
        val now = SystemClock.elapsedRealtime()
        if (cachedAtElapsed != 0L && now - cachedAtElapsed < CACHE_TTL_MS) return if (cachedNull) null else cached
        if (!allowPoll) return cached

        val s = runCatching { probe(ctx) }.getOrNull()
        cached = s
        cachedNull = s == null
        cachedAtElapsed = now
        return s
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun probe(ctx: Context): Sample? {
        // 1) The weather service may already hold a labelled fix — free, and it carries a city.
        val gps = runCatching { HioneWeatherService.state.value.gps }.getOrNull()
        if (gps != null && gps.isLocked && (gps.latitude != 0.0 || gps.longitude != 0.0) &&
            System.currentTimeMillis() - gps.lastFixTime < MAX_FIX_AGE_MS) {
            return Sample(round3(gps.latitude), round3(gps.longitude), cleanLabel(gps.city))
        }

        // 2) Passive/network/fused last-known fix — no provider is started, nothing is requested.
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = buildList {
            add(LocationManager.PASSIVE_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
        }
        var best: Location? = null
        for (p in providers) {
            if (!lm.allProviders.contains(p)) continue
            val loc = (try { lm.getLastKnownLocation(p) } catch (_: Throwable) { null }) ?: continue
            if (System.currentTimeMillis() - loc.time > MAX_FIX_AGE_MS) continue
            if (best == null || loc.time > best.time) best = loc
        }
        val b = best ?: return null
        // A stale label from the weather service is still a better hint than nothing, but only if
        // its fix is the same neighbourhood as ours (within ~5 km); otherwise no label at all.
        val label = gps?.takeIf { it.isLocked && distanceKm(it.latitude, it.longitude, b.latitude, b.longitude) < 5.0 }
            ?.let { cleanLabel(it.city) }
        return Sample(round3(b.latitude), round3(b.longitude), label)
    }

    private fun cleanLabel(city: String): String? {
        val c = city.trim()
        if (c.isEmpty() || c.equals("Detecting Location...", true) || c.equals("Local Station", true)) return null
        return c
    }

    private fun round3(v: Double): Double = Math.round(v * 1000.0) / 1000.0

    private fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val out = FloatArray(1)
        return try { Location.distanceBetween(lat1, lon1, lat2, lon2, out); out[0] / 1000.0 } catch (_: Throwable) { Double.MAX_VALUE }
    }
}
