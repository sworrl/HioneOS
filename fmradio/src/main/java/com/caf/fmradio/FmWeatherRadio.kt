package com.caf.fmradio

import android.content.Context
import android.util.Log
import org.json.JSONArray
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * NOAA Weather Radio transmitters near you, for a weather radio or a ham handheld.
 *
 * THIS TUNER CANNOT RECEIVE THEM, and the UI must say so rather than offer a button that does
 * nothing. NWR is 162.400–162.550 MHz. The Si4705 tunes 64–108 MHz (its FM_TUNE_FREQ argument
 * is range-checked in silicon), and HiBy's driver sets the seek band top to 107.9 MHz; the
 * weather-band part in that family is the Si4707, which this board does not have. So this is
 * reference data: which WX channel to set a scanner to here, and how far the transmitter is.
 *
 * Data: NWS's own station listing (tools/radiodb/build_nwr.py), US federal public domain,
 * bundled as assets/nwr_transmitters.json (~1,000 transmitters, 180 KB).
 */
object FmWeatherRadio {

    private const val TAG = "FmWeatherRadio"
    private const val ASSET = "nwr_transmitters.json"

    data class Transmitter(
        val call: String,
        val mhz: Double,
        /** WX1..WX7, the channel label weather radios and scanners use. */
        val wx: String,
        /** The area NOAA says it serves. */
        val site: String,
        /** Where the tower stands; sometimes a peak rather than a town. */
        val city: String,
        val state: String,
        val lat: Double,
        val lon: Double,
        val watts: Int?,
        /** The forecast office that programs it. */
        val wfo: String?,
        /** NORMAL, DEGRADED or OUT OF SERVICE, as NOAA last listed it. */
        val status: String,
        val distanceKm: Double = 0.0,
    ) {
        val inService: Boolean get() = status == "NORMAL" || status == "DEGRADED"
    }

    @Volatile private var all: List<Transmitter>? = null

    private fun load(ctx: Context): List<Transmitter> {
        all?.let { return it }
        val list = runCatching {
            val text = ctx.assets.open(ASSET).bufferedReader().use { it.readText() }
            val a = JSONArray(text)
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

    private fun km(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val dLat = Math.toRadians(bLat - aLat)
        val dLon = Math.toRadians(bLon - aLon)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * 6371.0 * asin(min(1.0, sqrt(h)))
    }

    data class Alert(
        val event: String,
        val headline: String?,
        val severity: String?,
        val urgency: String?,
        val areas: String?,
        val effective: String?,
        val expires: String?,
        val description: String?,
        val instruction: String?,
        val sender: String?,
    )

    /**
     * Active NWS alerts for a point: the same watches and warnings NOAA Weather Radio reads out,
     * as data from api.weather.gov (the NWS's own public API). Blocking; null when the service
     * cannot be reached, empty when there is nothing active.
     */
    fun alerts(lat: Double, lon: Double): List<Alert>? = runCatching {
        val r = FmNet.request(
            "https://api.weather.gov/alerts/active?point=%.4f,%.4f".format(java.util.Locale.US, lat, lon),
            headers = mapOf(
                // NWS asks every client to identify itself.
                "User-Agent" to "HioneOS FM (https://github.com/sworrl/HioneOS)",
                "Accept" to "application/geo+json",
            ),
        )
        if (r.code != 200) { Log.w(TAG, "NWS alerts: HTTP ${r.code}"); return@runCatching null }
        val feats = org.json.JSONObject(r.text).optJSONArray("features") ?: return@runCatching emptyList<Alert>()
        List(feats.length()) { i ->
            val p = feats.getJSONObject(i).getJSONObject("properties")
            fun s(k: String) = p.optString(k).ifBlank { null }
            Alert(s("event") ?: "Alert", s("headline"), s("severity"), s("urgency"), s("areaDesc"),
                s("effective"), s("expires"), s("description"), s("instruction"), s("senderName"))
        }
    }.onFailure { Log.w(TAG, "NWS alerts failed: $it") }.getOrNull()

    /**
     * The nearest [n] transmitters within [radiusKm], nearest first. A 1 kW NWR site reaches a
     * handheld at roughly 60–70 km over open ground, so 120 km covers anything plausible.
     */
    fun nearest(ctx: Context, lat: Double, lon: Double, n: Int = 8, radiusKm: Double = 120.0): List<Transmitter> =
        load(ctx).asSequence()
            .map { it.copy(distanceKm = km(lat, lon, it.lat, it.lon)) }
            .filter { it.distanceKm <= radiusKm }
            .sortedBy { it.distanceKm }
            .take(n)
            .toList()
}
