package com.hione.player

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * Public-domain NOAA/"Almanac for Computers" sunrise-sunset algorithm — the same well-tested
 * formula behind essentially every offline sunrise/sunset library. Accurate to within a minute or
 * two, which is all an alarm needs; no network dependency, so alarm scheduling never depends on
 * connectivity. Refraction+solar-radius correction (zenith 90.833°) matches "civil" sunrise/sunset
 * as commonly defined (sun's upper limb crossing the horizon).
 */
object SunCalc {
    private const val ZENITH = 90.833

    /** [sunriseMillis, sunsetMillis] for the LOCAL calendar date `dayStart` (should be midnight in
     *  the device's default timezone) at the given lat/lon, or null if the sun doesn't rise/set
     *  that day at that latitude (polar day/night — not a realistic case for M500 users, handled
     *  safely rather than crashing). */
    fun sunriseSunset(dayStart: Calendar, lat: Double, lon: Double): Pair<Long, Long>? {
        val rise = calc(dayStart, lat, lon, isSunrise = true) ?: return null
        val set = calc(dayStart, lat, lon, isSunrise = false) ?: return null
        return rise to set
    }

    private fun calc(dayStart: Calendar, lat: Double, lon: Double, isSunrise: Boolean): Long? {
        val dayOfYear = dayStart.get(Calendar.DAY_OF_YEAR)
        val lngHour = lon / 15.0
        val t = if (isSunrise) dayOfYear + ((6.0 - lngHour) / 24.0) else dayOfYear + ((18.0 - lngHour) / 24.0)

        val m = (0.9856 * t) - 3.289
        var l = m + (1.916 * sinDeg(m)) + (0.020 * sinDeg(2 * m)) + 282.634
        l = norm360(l)

        var ra = deg(atan(0.91764 * tan(rad(l))))
        ra = norm360(ra)
        val lQuadrant = floor(l / 90.0) * 90.0
        val raQuadrant = floor(ra / 90.0) * 90.0
        ra += (lQuadrant - raQuadrant)
        ra /= 15.0

        val sinDec = 0.39782 * sinDeg(l)
        val cosDec = cos(Math.asin(sinDec))

        val cosH = (cosDeg(ZENITH) - (sinDec * sinDeg(lat))) / (cosDec * cosDeg(lat))
        if (cosH > 1.0 || cosH < -1.0) return null

        var h = if (isSunrise) 360.0 - deg(acos(cosH)) else deg(acos(cosH))
        h /= 15.0

        val tLocal = h + ra - (0.06571 * t) - 6.622
        var utcHours = tLocal - lngHour
        utcHours = ((utcHours % 24) + 24) % 24

        val hour = utcHours.toInt()
        val minuteFull = (utcHours - hour) * 60
        val minute = minuteFull.toInt()
        val second = ((minuteFull - minute) * 60).toInt()

        val result = dayStart.clone() as Calendar
        result.timeZone = TimeZone.getTimeZone("UTC")
        result.set(Calendar.HOUR_OF_DAY, hour)
        result.set(Calendar.MINUTE, minute)
        result.set(Calendar.SECOND, second)
        result.set(Calendar.MILLISECOND, 0)
        return result.timeInMillis
    }

    private fun rad(v: Double) = Math.toRadians(v)
    private fun deg(v: Double) = Math.toDegrees(v)
    private fun sinDeg(v: Double) = sin(rad(v))
    private fun cosDeg(v: Double) = cos(rad(v))
    private fun norm360(v: Double): Double {
        var r = v % 360.0
        if (r < 0) r += 360.0
        return r
    }
}

/** Coarse, low-power location for [[SunCalc]] — reads the OS's last-known fix only (no active GPS
 *  request ever triggered by this app), refreshed at most once a day. Same "as little as it needs
 *  to be" battery rule already scoped for listening stats in memory m500-scrobble-location-todo. */
object AlarmLocation {
    private const val MIN_REFRESH_MS = 20L * 3600_000L

    fun refreshIfStale(ctx: android.content.Context) {
        val last = AlarmPreferences.loadLocationTimestamp(ctx)
        if (System.currentTimeMillis() - last < MIN_REFRESH_MS) return
        if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_COARSE_LOCATION)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        val lm = ctx.getSystemService(android.content.Context.LOCATION_SERVICE) as? android.location.LocationManager ?: return
        val providers = listOf(
            android.location.LocationManager.NETWORK_PROVIDER,
            android.location.LocationManager.PASSIVE_PROVIDER,
            android.location.LocationManager.GPS_PROVIDER
        )
        for (p in providers) {
            val loc = runCatching {
                if (lm.isProviderEnabled(p)) lm.getLastKnownLocation(p) else null
            }.getOrNull() ?: continue
            AlarmPreferences.saveCachedLocation(ctx, loc.latitude, loc.longitude)
            return
        }
    }
}
