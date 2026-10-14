package com.caf.fmradio

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Whether the hill between you and a transmitter is actually in the way.
 *
 * The station catalogue can say a 50 kW transmitter is 12 km off, and in this terrain that
 * tells you very little: the ranking it produces is an upper bound that assumes a clear path,
 * and a clear path is the exception in a hollow. This is the part that looks at the ground.
 *
 * It samples real elevations along the great circle to the transmitter, draws the line of
 * sight with the usual 4/3-earth refraction allowance, and measures how much of the first
 * Fresnel zone survives. The 60% rule is the convention: clearance below 0.6 of the first
 * Fresnel radius starts costing signal, and an obstruction that crosses the line of sight
 * itself is diffraction loss rather than attenuation.
 *
 * A ridge in the way is not the end of a station. FM bends over hills, and 20 to 30 dB of
 * diffraction loss from a strong transmitter 12 km off still leaves a loud signal. So this only
 * supplies a loss in dB to [FmReach]; it never decides on its own that a station is out of
 * reach, and a measured reading on the tuner always outranks it.
 *
 * NOTHING HERE IS SYNTHESISED. With no network and no cached profile it returns null and the
 * UI says it does not know, because a terrain plot invented from a straight line between two
 * points would look exactly as convincing as a real one and be worth nothing.
 */
object FmFresnel {

    private const val TAG = "FmFresnel"
    private const val C = 299_792_458.0
    private const val EARTH_R = 6_371_000.0
    /** Refraction allowance. 4/3 is the standard temperate-atmosphere k-factor. */
    private const val K_FACTOR = 4.0 / 3.0
    /** Where a listener's ear is, above the ground they are standing on. */
    private const val RX_HEIGHT_M = 1.5
    /** Clearance below this fraction of the first Fresnel radius is where loss starts. */
    const val FRESNEL_RULE = 0.6

    /** Open-Meteo takes a batch of coordinates and needs no key, same as the weather tile. */
    private const val ELEVATION_API = "https://api.open-meteo.com/v1/elevation"
    private const val MAX_PER_REQUEST = 100
    private const val TIMEOUT_MS = 12_000
    private const val CACHE_DIR = "hione_fresnel_v2"

    data class Point(
        /** Metres along the path from the listener. */
        val distM: Double,
        val lat: Double,
        val lon: Double,
        /** Ground elevation, metres above sea level. Real, from the elevation service. */
        val groundM: Double,
        /** Straight line from the listener's antenna to the transmitter's, at this distance. */
        val sightM: Double,
        /** First Fresnel zone radius here. */
        val fresnelM: Double,
        /** sightM - groundM. Negative means the ground is above the line of sight. */
        val clearanceM: Double,
    ) {
        /** Clearance as a fraction of the first Fresnel radius; 1.0 is a fully clear zone. */
        val fresnelFraction: Double get() = if (fresnelM <= 0.0) 1.0 else clearanceM / fresnelM
    }

    data class Profile(
        val station: FmStationCatalogue.Station,
        val listenerLat: Double,
        val listenerLon: Double,
        val points: List<Point>,
        /** Ground elevation under the listener. */
        val listenerGroundM: Double,
        /** Transmitting antenna, metres above sea level (see [txAntennaAmslM]). */
        val txAntennaM: Double,
        val fetchedAtMs: Long,
    ) {
        val distanceKm: Double get() = (points.lastOrNull()?.distM ?: 0.0) / 1000.0

        /** The worst point on the path, which is the one drawn and described. */
        val worst: Point? get() = points.minByOrNull { it.fresnelFraction }

        /** True when something actually crosses the straight line, not just the zone. */
        val blocked: Boolean get() = points.any { it.clearanceM < 0.0 }

        /** Bullington's equivalent knife edge for the whole path: (v, loss in dB). */
        private val edge: Pair<Double, Double> by lazy {
            val lambda = C / (station.khz * 1000.0)
            bullington(
                DoubleArray(points.size) { points[it].distM },
                DoubleArray(points.size) { points[it].groundM },
                listenerGroundM + RX_HEIGHT_M, txAntennaM, lambda,
            )
        }

        /** Fresnel-Kirchhoff v of the equivalent edge. Above about -0.78 the path costs signal. */
        val diffractionV: Double get() = edge.first

        /**
         * Diffraction loss over the terrain, in dB relative to free space (ITU-R P.526).
         *
         * One equivalent knife edge for the whole path (Bullington), never a sum over every
         * hill: summing edges counts the same shadow several times, and in this terrain that
         * turns a station you can hear into one the model calls dead. A single edge is optimistic
         * for a path over several separate ridges, which is the safer mistake here because a
         * measured reading always overrides the prediction.
         */
        val diffractionLossDb: Double get() = edge.second

        val verdict: String get() {
            val f = worst?.fresnelFraction ?: return "unknown"
            return when {
                f >= 1.0 -> "clear path"
                f >= FRESNEL_RULE -> "clear enough"
                f >= 0.0 -> "grazing the ridge"
                else -> "behind a ridge"
            }
        }
    }

    /** ITU-R P.526 knife-edge loss J(v), dB. Zero below v = -0.78. */
    fun knifeEdgeDb(v: Double): Double =
        if (v <= -0.78) 0.0
        else (6.9 + 20 * log10(sqrt((v - 0.1) * (v - 0.1) + 1.0) + v - 0.1)).coerceAtLeast(0.0)

    /**
     * Bullington equivalent knife edge (ITU-R P.526-15, 4.5.1 without the smooth-earth term).
     * [distM] runs from the listener (0) to the transmitter (last), [groundM] is the terrain
     * there, [rxAntM] and [txAntM] are antenna heights above sea level. Returns (v, loss dB).
     *
     * If no terrain point rises above the straight line, v is the largest one along the path
     * (the closest the ground comes to the line, in Fresnel terms). Otherwise the steepest
     * ray from each end is found and v is taken where those two rays cross.
     */
    fun bullington(
        distM: DoubleArray, groundM: DoubleArray, rxAntM: Double, txAntM: Double, lambdaM: Double,
    ): Pair<Double, Double> {
        val n = distM.size
        if (n < 3) return Double.NEGATIVE_INFINITY to 0.0
        val d = distM[n - 1]
        if (d <= 0.0) return Double.NEGATIVE_INFINITY to 0.0
        val ce = 1.0 / (K_FACTOR * EARTH_R)
        // Terrain raised by the earth's bulge, so the ends can be joined with straight lines.
        fun hEff(i: Int) = groundM[i] + ce * distM[i] * (d - distM[i]) / 2.0
        val sLos = (txAntM - rxAntM) / d
        var sRx = Double.NEGATIVE_INFINITY
        for (i in 1 until n - 1) sRx = max(sRx, (hEff(i) - rxAntM) / distM[i])
        val v: Double
        if (sRx < sLos) {
            var vMax = Double.NEGATIVE_INFINITY
            for (i in 1 until n - 1) {
                val di = distM[i]; val dj = d - di
                val h = hEff(i) - (rxAntM * dj + txAntM * di) / d
                vMax = max(vMax, h * sqrt(2.0 * d / (lambdaM * di * dj)))
            }
            v = vMax
        } else {
            var sTx = Double.NEGATIVE_INFINITY
            for (i in 1 until n - 1) sTx = max(sTx, (hEff(i) - txAntM) / (d - distM[i]))
            val sum = (sRx + sTx).coerceAtLeast(1e-9)
            val db = ((txAntM - rxAntM + sTx * d) / sum).coerceIn(1.0, d - 1.0)
            val h = rxAntM + sRx * db - (rxAntM * (d - db) + txAntM * db) / d
            v = h * sqrt(2.0 * d / (lambdaM * db * (d - db)))
        }
        return v to knifeEdgeDb(v)
    }

    /**
     * The transmitting antenna's height above sea level. The licence's RCAMSL when it is
     * present and sane for the ground the elevation service reports there; otherwise the
     * ground plus HAAT, which is only an approximation (HAAT is over AVERAGE terrain, so a
     * mast on a hilltop comes out too high and one in a valley too low). WVAQ is the example:
     * RCAMSL 535 m, ground 424 m, HAAT 152 m, so ground + HAAT put the antenna 41 m high.
     */
    fun txAntennaAmslM(st: FmStationCatalogue.Station, txGroundM: Double): Double {
        st.rcamslM?.takeIf { it > txGroundM - 60.0 && it < txGroundM + 700.0 }?.let { return it }
        val haat = st.haatM?.takeIf { it > 0.0 } ?: 30.0
        return txGroundM + haat
    }

    /** Samples for a path: about one per 150 m (the elevation grid is ~90 m), 60 to 200. */
    fun samplesFor(totalM: Double): Int = (totalM / 150.0).toInt().coerceIn(60, MAX_PER_REQUEST * 2)

    /**
     * Build a profile from ground elevations already in hand. Pure geometry, no I/O, so the
     * same code serves a fresh fetch, the disk cache and the unit tests.
     */
    fun build(
        st: FmStationCatalogue.Station,
        lat: Double, lon: Double,
        coords: List<Pair<Double, Double>>,
        distM: List<Double>,
        ground: List<Double>,
        txAntM: Double,
        fetchedAtMs: Long,
    ): Profile {
        val n = ground.size
        val totalM = distM.last()
        val rxAnt = ground.first() + RX_HEIGHT_M
        val lambda = C / (st.khz * 1000.0)
        val points = ArrayList<Point>(n)
        for (i in 0 until n) {
            val d1 = distM[i]
            val d2 = totalM - d1
            // Straight line between the two antennas, less the earth's bulge under it.
            val bulge = (d1 * d2) / (2 * K_FACTOR * EARTH_R)
            val sight = rxAnt + (txAntM - rxAnt) * (d1 / totalM) - bulge
            val r1 = if (d1 <= 0.0 || d2 <= 0.0) 0.0 else sqrt(lambda * d1 * d2 / totalM)
            points.add(Point(d1, coords[i].first, coords[i].second, ground[i], sight, r1, sight - ground[i]))
        }
        return Profile(st, lat, lon, points, ground.first(), txAntM, fetchedAtMs)
    }

    // ---------------------------------------------------------------- geometry

    private fun haversineM(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val dLat = Math.toRadians(bLat - aLat)
        val dLon = Math.toRadians(bLon - aLon)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_R * asin(min(1.0, sqrt(h)))
    }

    /** Great-circle interpolation, so a 130 km path does not drift off the real line. */
    private fun interpolate(
        aLat: Double, aLon: Double, bLat: Double, bLon: Double, f: Double,
    ): Pair<Double, Double> {
        val p1 = Math.toRadians(aLat); val l1 = Math.toRadians(aLon)
        val p2 = Math.toRadians(bLat); val l2 = Math.toRadians(bLon)
        val d = 2 * asin(sqrt(
            sin((p2 - p1) / 2).let { it * it } +
                cos(p1) * cos(p2) * sin((l2 - l1) / 2).let { it * it }))
        if (d == 0.0) return aLat to aLon
        val A = sin((1 - f) * d) / sin(d)
        val B = sin(f * d) / sin(d)
        val x = A * cos(p1) * cos(l1) + B * cos(p2) * cos(l2)
        val y = A * cos(p1) * sin(l1) + B * cos(p2) * sin(l2)
        val z = A * sin(p1) + B * sin(p2)
        return Math.toDegrees(atan2(z, sqrt(x * x + y * y))) to Math.toDegrees(atan2(y, x))
    }

    // ---------------------------------------------------------------- elevation

    private fun cacheFile(ctx: Context, lat: Double, lon: Double, st: FmStationCatalogue.Station): File {
        // Rounded to about 100 m, so standing still reuses the profile and walking a block
        // does not invalidate a 60-sample fetch.
        val key = "%.3f_%.3f_%s_%d".format(java.util.Locale.US, lat, lon, st.call, st.khz)
        // v2: sample count scales with distance. The v1 cache held 60 fixed samples.
        File(ctx.cacheDir, "hione_fresnel").takeIf { it.exists() }?.deleteRecursively()
        return File(File(ctx.cacheDir, CACHE_DIR).apply { mkdirs() }, "$key.json")
    }

    /** Elevations for a list of coordinates, in order. Null if the service cannot be reached. */
    private fun fetchElevations(coords: List<Pair<Double, Double>>): List<Double>? {
        val out = ArrayList<Double>(coords.size)
        for (chunk in coords.chunked(MAX_PER_REQUEST)) {
            val lats = chunk.joinToString(",") { "%.5f".format(java.util.Locale.US, it.first) }
            val lons = chunk.joinToString(",") { "%.5f".format(java.util.Locale.US, it.second) }
            val body = runCatching {
                val r = FmNet.request("$ELEVATION_API?latitude=$lats&longitude=$lons")
                if (r.code != 200) {
                    Log.w(TAG, "elevation service returned HTTP ${r.code}")
                    return null
                }
                r.text
            }.onFailure { Log.w(TAG, "elevation fetch failed: $it") }.getOrNull() ?: return null
            val arr = runCatching { JSONObject(body).getJSONArray("elevation") }
                .onFailure { Log.w(TAG, "elevation reply was not what we expected: $it") }
                .getOrNull() ?: return null
            for (i in 0 until arr.length()) out.add(arr.getDouble(i))
        }
        return if (out.size == coords.size) out else null.also {
            Log.w(TAG, "elevation service returned ${out.size} of ${coords.size} points")
        }
    }

    // ---------------------------------------------------------------- the profile

    /**
     * Terrain profile between the listener and a transmitter. Blocking; call it off the main
     * thread. Returns null when the elevations cannot be obtained and nothing is cached.
     */
    fun profile(
        ctx: Context,
        lat: Double, lon: Double,
        st: FmStationCatalogue.Station,
        samples: Int? = null,
    ): Profile? {
        val cache = cacheFile(ctx, lat, lon, st)
        readCache(cache, st, lat, lon)?.let { return it }

        val totalM = haversineM(lat, lon, st.lat, st.lon)
        if (totalM < 50.0) return null
        val n = (samples ?: samplesFor(totalM)).coerceIn(16, MAX_PER_REQUEST * 2)
        val coords = (0 until n).map { i -> interpolate(lat, lon, st.lat, st.lon, i.toDouble() / (n - 1)) }
        val ground = fetchElevations(coords) ?: return null
        val dist = (0 until n).map { totalM * it / (n - 1) }
        val p = build(st, lat, lon, coords, dist, ground, txAntennaAmslM(st, ground.last()), System.currentTimeMillis())
        writeCache(cache, p)
        Log.i(TAG, "${st.call} at ${"%.1f".format(p.distanceKm)} km: ${p.verdict}, " +
            "tx antenna ${"%.0f".format(p.txAntennaM)} m asl, " +
            "worst clearance ${"%.0f".format(p.worst?.clearanceM ?: 0.0)} m " +
            "(${"%.0f".format((p.worst?.fresnelFraction ?: 0.0) * 100)}% of the first zone), " +
            "diffraction v ${"%.2f".format(p.diffractionV)} ~${"%.0f".format(p.diffractionLossDb)} dB")
        return p
    }

    // ---------------------------------------------------------------- caching

    private fun writeCache(f: File, p: Profile) {
        runCatching {
            val o = JSONObject()
            o.put("at", p.fetchedAtMs)
            o.put("txAnt", p.txAntennaM)
            o.put("ground", org.json.JSONArray(p.points.map { it.groundM }))
            o.put("lat", org.json.JSONArray(p.points.map { it.lat }))
            o.put("lon", org.json.JSONArray(p.points.map { it.lon }))
            o.put("dist", org.json.JSONArray(p.points.map { it.distM }))
            f.writeText(o.toString())
        }.onFailure { Log.w(TAG, "could not cache the profile: $it") }
    }

    /** The geometry is rebuilt from the cached ground, so a model fix applies to old fetches. */
    private fun readCache(
        f: File, st: FmStationCatalogue.Station, lat: Double, lon: Double,
    ): Profile? = runCatching {
        if (!f.exists()) return null
        val o = JSONObject(f.readText())
        val g = o.getJSONArray("ground"); val la = o.getJSONArray("lat")
        val lo = o.getJSONArray("lon"); val d = o.getJSONArray("dist")
        if (g.length() < 3) return null
        val ground = (0 until g.length()).map { g.getDouble(it) }
        build(st, lat, lon,
              (0 until g.length()).map { la.getDouble(it) to lo.getDouble(it) },
              (0 until g.length()).map { d.getDouble(it) },
              ground, txAntennaAmslM(st, ground.last()), o.getLong("at"))
    }.getOrNull()

    /** How much of the cache is on disk, for a diagnostics screen. */
    fun cacheBytes(ctx: Context): Long =
        File(ctx.cacheDir, CACHE_DIR).listFiles()?.sumOf { it.length() } ?: 0L
}
