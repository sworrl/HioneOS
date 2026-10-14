package com.hione.launcher.weather

import android.content.Context
import android.util.Log
import org.json.JSONArray
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * NOAA Weather Radio transmitters near you, for a weather radio, scanner or ham handheld.
 *
 * Nothing on this device can receive them: NWR is 162.400-162.550 MHz and the FM tuner stops at
 * 108 MHz. This is reference data, the same list the FM app uses (FmWeatherRadio), bundled as
 * assets/nwr_transmitters.json (NWS station listing, US public domain).
 */
object HioneNoaaRadio {
    private const val TAG = "HioneNoaaRadio"
    private const val ASSET = "nwr_transmitters.json"

    data class Transmitter(
        val call: String,
        val mhz: Double,
        /** WX1..WX7, the channel label weather radios use. */
        val wx: String,
        val site: String,
        val city: String,
        val state: String,
        val lat: Double,
        val lon: Double,
        val watts: Int?,
        val wfo: String?,
        /** NORMAL, DEGRADED or OUT OF SERVICE, as NOAA last listed it. */
        val status: String,
        val distanceKm: Double = 0.0,
    ) {
        val inService: Boolean get() = status == "NORMAL" || status == "DEGRADED"
        val mhzLabel: String get() = String.format(Locale.US, "%.3f", mhz)
        val kmLabel: String get() = if (distanceKm < 10) String.format(Locale.US, "%.1f km", distanceKm) else "${distanceKm.toInt()} km"
        /** "KWN35 WX3 162.475" for Settings.Global and widgets. */
        val shortLabel: String get() = "$call $wx $mhzLabel"
        val place: String get() = listOf(site.ifBlank { city }, state).filter { it.isNotBlank() }.joinToString(", ")
    }

    @Volatile private var all: List<Transmitter>? = null

    private fun load(ctx: Context): List<Transmitter> {
        all?.let { return it }
        val list = runCatching {
            val a = JSONArray(ctx.assets.open(ASSET).bufferedReader().use { it.readText() })
            List(a.length()) { i ->
                val o = a.getJSONObject(i)
                Transmitter(
                    call = o.getString("call"), mhz = o.getDouble("mhz"), wx = o.optString("wx"),
                    site = o.optString("site"), city = o.optString("city"), state = o.optString("state"),
                    lat = o.getDouble("lat"), lon = o.getDouble("lon"),
                    watts = if (o.has("watts")) o.optInt("watts") else null,
                    wfo = o.optString("wfo").ifBlank { null }, status = o.optString("status", "NORMAL"),
                )
            }
        }.onFailure { Log.w(TAG, "no NWR list: $it") }.getOrDefault(emptyList())
        all = list
        return list
    }

    fun km(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val dLat = Math.toRadians(bLat - aLat)
        val dLon = Math.toRadians(bLon - aLon)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * 6371.0 * asin(min(1.0, sqrt(h)))
    }

    /**
     * Closest transmitters, in-service first among those within reach. A transmitter much past
     * ~120 km is not realistically hearable, so the list stops there unless nothing is closer.
     */
    fun nearest(ctx: Context, lat: Double, lon: Double, count: Int = 5): List<Transmitter> {
        val sorted = load(ctx).asSequence()
            .map { it.copy(distanceKm = km(lat, lon, it.lat, it.lon)) }
            .sortedBy { it.distanceKm }
            .take(count * 3)
            .toList()
        if (sorted.isEmpty()) return emptyList()
        val usable = sorted.filter { it.inService }
        val near = usable.filter { it.distanceKm <= 120.0 }
        return (near.ifEmpty { usable.take(1) } + sorted.filter { !it.inService && it.distanceKm <= 120.0 })
            .distinctBy { it.call + it.mhz }
            .take(count)
    }
}
