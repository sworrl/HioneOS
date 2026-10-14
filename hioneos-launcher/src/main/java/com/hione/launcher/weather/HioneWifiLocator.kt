package com.hione.launcher.weather

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Where we are, worked out from the Wi-Fi we can hear.
 *
 * WHY THIS EXISTS (2026-09-27). Justin flew across the country and the weather stayed on the old
 * city. `dumpsys location` on the M500 explains it exactly:
 *
 *     network provider:  enabled=false  allowed=false
 *     gps provider:      mStarted=false, Number of location reports: 0, last location=null
 *
 * Google's network (Wi-Fi/cell) provider is disabled on this build, and GPS indoors on a DAP with
 * no sky view never fixes, let alone after a flight left its almanac stale. Every provider reported
 * `last location=null`, so the weather fell back to the stored last-good fix, which is the old
 * town. It was not a stale-cache bug; the device genuinely did not know where it was.
 *
 * This is the BeaconFix positioning chain (github.com/sworrl/beaconfix, src/locator.cpp) ported to
 * Kotlin. Two keyless services, in order:
 *
 *  1. BeaconDB (api.beacondb.net) — the open Wi-Fi geolocation database. Needs two access points.
 *     GeoIP and cell fallbacks are switched OFF in the request: we want a real Wi-Fi match or a
 *     clean miss, not a city-sized guess dressed up as a fix.
 *  2. Apple's Wi-Fi positioning (gs-loc.apple.com) — no account, no token, answers per BSSID, so
 *     one access point is enough. Request and reply are two small protobuf messages, hand-encoded
 *     below rather than pulling in a protobuf dependency for 40 bytes of wire format.
 *
 * Both need only that the device can HEAR Wi-Fi. Neither needs to be joined to it, neither needs
 * GPS, and neither needs Google Play services.
 *
 * WHAT IT WILL NOT DO. Access points whose SSID ends `_nomap` or contains `_optout` are the
 * opt-out convention and are never sent anywhere. Neither is anything the user marked as a home
 * network, or anything that looks like it travels with them (a phone hotspot, a car Wi-Fi), because
 * a beacon that moves with you tells you nothing about where you are and poisons the database for
 * everyone else.
 */
object HioneWifiLocator {
    private const val TAG = "HioneWifiLocator"
    private const val BEACONDB = "https://api.beacondb.net/v1/geolocate"
    private const val APPLE = "https://gs-loc.apple.com/clls/wloc"
    private const val TIMEOUT_MS = 15_000
    /** A BeaconDB answer wider than this is its GeoIP guess, not a Wi-Fi match. */
    private const val GEOIP_TELL_M = 5_000.0

    data class Fix(
        val lat: Double,
        val lon: Double,
        val accuracyM: Double,
        val provider: String,      // "beacondb" or "apple"
        val apsHeard: Int,
        val apsUsed: Int
    )

    /**
     * SSID patterns that mean "this access point travels with its owner".
     *
     * STARLINK is in here for a reason found the hard way. On 2026-09-27 the fix came back as
     * the user's home town while they were on the other side of the country, and it was
     * RIGHT about the beacons: of 28 access points the M500 could hear, four were his Starlink,
     * four more were his own named networks, and Apple has every one of them mapped where they
     * last sat still. A beacon that travels with you tells you nothing about where you are, and
     * asking about it gets you an answer about where you WERE. The stationary neighbours (the
     * T-Mobile and AT&T gateways in that same scan) are the ones that know.
     */
    private val MOBILE_RE = Regex(
        "(?i)(iphone|android.?ap|galaxy|pixel|hotspot|mifi|mobile.?hotspot|rv ?link|winnebago|" +
            "amtrak|united_?wifi|delta|gogo|sbb|db ?ice|bus ?wifi|tesla|ford|chevy|toyota|" +
            "starlink|dishy|direct-|chromecast|roku|firetv|nintendo|\\bvan\\b|camper|rv ?park ?guest)"
    )

    /**
     * Networks the user says travel with them, comma separated, case-insensitive substring match:
     *
     *   settings put global hione_wifi_home_ssids "Dick,AultCastle,🔬🧪"
     *
     * Whatever the device is CURRENTLY JOINED TO is treated as one of these automatically: you do
     * not carry a router you are not connected to, and the one you are connected to is almost
     * always yours.
     */
    private const val KEY_HOME_SSIDS = "hione_wifi_home_ssids"

    private fun travellingSsids(ctx: Context, wifi: WifiManager): List<String> {
        val out = ArrayList<String>()
        runCatching {
            android.provider.Settings.Global.getString(ctx.contentResolver, KEY_HOME_SSIDS)
                ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.let { out.addAll(it) }
        }
        runCatching {
            @Suppress("DEPRECATION")
            val joined = wifi.connectionInfo?.ssid?.trim('"')?.trim()
            if (!joined.isNullOrBlank() && joined != "<unknown ssid>") out.add(joined)
        }
        return out
    }

    /** True when this beacon is safe and useful to ask about. */
    private fun usable(r: ScanResult, travelling: List<String>): Boolean {
        val ssid = (r.SSID ?: "")
        if (ssid.endsWith("_nomap", true) || ssid.contains("_optout", true)) return false
        if (MOBILE_RE.containsMatchIn(ssid)) return false
        if (travelling.any { ssid.contains(it, ignoreCase = true) }) return false
        if (r.BSSID.isNullOrBlank()) return false
        // NOTE: no locally-administered-MAC filter. I added one and it was wrong. On this network
        // most of what the M500 hears (Starlink, the AT&T gear, the mesh nodes) advertises a
        // locally-administered BSSID, and those are perfectly ordinary mapped beacons. The bit
        // means "not burned in by the vendor", not "randomised".
        return true
    }

    /**
     * Take a position from the air. Blocking; call from a background thread.
     * Returns null when nothing could place us, which is a real answer and must not be faked.
     */
    @SuppressLint("MissingPermission")
    fun locate(ctx: Context): Fix? {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null

        // ASK FOR A SCAN FIRST. The cache is not good enough on this device: `cmd wifi
        // list-scan-results` showed a dozen access points with Age > 1000s, and an app reading
        // `scanResults` against results that stale got an EMPTY list (logged "heard 0 access
        // points" while the shell could see twelve). Android throttles startScan to four calls
        // per two minutes, so this asks, waits briefly for the broadcast, and uses whatever the
        // cache holds either way — a throttled refusal is not a failure, it just means the cache
        // is as fresh as we are allowed to make it.
        val fresh = requestScan(ctx.applicationContext, wifi)
        val all = runCatching { wifi.scanResults }.getOrNull().orEmpty()
        if (all.isEmpty()) Log.w(TAG, "scanResults empty (scan requested=$fresh); nothing to position from")
        val travelling = travellingSsids(ctx.applicationContext, wifi)
        val aps = all.filter { usable(it, travelling) }.sortedByDescending { it.level }
        val dropped = all.size - aps.size
        Log.i(TAG, "heard ${all.size} access points, ${aps.size} usable ($dropped excluded as travelling/opt-out" +
            (if (travelling.isNotEmpty()) "; yours: ${travelling.joinToString()}" else "") + ")")
        if (aps.isEmpty()) {
            Log.w(TAG, "every beacon in range travels with you; nothing stationary to position from")
            return null
        }

        if (aps.size >= 2) {
            queryBeaconDb(aps, all.size)?.let { return it }
        }
        return queryApple(aps, all.size)
    }

    /**
     * Trigger a scan and wait up to [waitMs] for the results broadcast. Returns true when a fresh
     * scan landed. Blocking, and safe to call when throttled: startScan simply returns false.
     */
    @SuppressLint("MissingPermission")
    private fun requestScan(ctx: Context, wifi: WifiManager, waitMs: Long = 8_000L): Boolean {
        val latch = java.util.concurrent.CountDownLatch(1)
        val rx = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, i: android.content.Intent?) { latch.countDown() }
        }
        return try {
            val filter = android.content.IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(rx, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag") ctx.registerReceiver(rx, filter)
            }
            val started = runCatching { wifi.startScan() }.getOrDefault(false)
            if (!started) { Log.i(TAG, "startScan refused (throttled); using the cache") ; return false }
            latch.await(waitMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            Log.w(TAG, "scan request failed: ${t.message}"); false
        } finally {
            runCatching { ctx.unregisterReceiver(rx) }
        }
    }

    // ───────────────────────────────────────────────────────────── BeaconDB

    private fun queryBeaconDb(aps: List<ScanResult>, heard: Int): Fix? = try {
        val arr = JSONArray()
        for (r in aps) {
            arr.put(JSONObject().apply {
                put("macAddress", r.BSSID)
                put("signalStrength", r.level)
                put("frequency", r.frequency)
            })
        }
        val body = JSONObject().apply {
            put("wifiAccessPoints", arr)
            // No GeoIP, no cell fallback. A miss should look like a miss.
            put("fallbacks", JSONObject().apply { put("ipf", false); put("lacf", false) })
        }.toString()

        val c = (URL(BEACONDB).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS; readTimeout = TIMEOUT_MS
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "HioneOS/1.0 (+https://github.com/sworrl/HioneOS)")
            doOutput = true
        }
        c.outputStream.use { it.write(body.toByteArray()) }
        val text = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        c.disconnect()

        val o = JSONObject(text)
        val loc = o.optJSONObject("location")
        val acc = o.optDouble("accuracy", -1.0)
        when {
            loc == null || !loc.has("lat") -> {
                Log.i(TAG, "BeaconDB: no match (${o.optJSONObject("error")?.optString("message") ?: "no location in reply"})")
                null
            }
            acc > GEOIP_TELL_M -> {
                // Its GeoIP fallback leaking through. That is not a Wi-Fi fix.
                Log.i(TAG, "BeaconDB: only a ${(acc / 1000).toInt()} km GeoIP guess, refusing it")
                null
            }
            else -> Fix(loc.getDouble("lat"), loc.getDouble("lng"), acc, "beacondb", heard, aps.size)
                .also { Log.i(TAG, "BeaconDB fix from ${aps.size} APs (±${acc.toInt()} m)") }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "BeaconDB failed: ${t.message}")
        null
    }

    // ───────────────────────────────────────────────────────────── Apple WPS
    // Hand-rolled protobuf. Apple's endpoint takes a length-prefixed blob of BSSIDs and answers
    // with the mapped position of each one it knows, plus a hundred neighbours it volunteers.

    private fun varint(value: Long): ByteArray {
        var v = value
        val out = ByteArrayOutputStream()
        do {
            var c = (v and 0x7f).toInt()
            v = v ushr 7
            if (v != 0L) c = c or 0x80
            out.write(c)
        } while (v != 0L)
        return out.toByteArray()
    }

    private fun lenField(tag: Int, data: ByteArray): ByteArray =
        varint((tag shl 3).toLong() or 2L) + varint(data.size.toLong()) + data

    private class Field(val tag: Int, val wire: Int, val v: Long, val d: ByteArray)

    private fun parse(b: ByteArray): List<Field> {
        val out = ArrayList<Field>()
        var i = 0
        fun readVarint(): Long? {
            var v = 0L; var s = 0
            while (i < b.size && s < 64) {
                val c = b[i++].toInt() and 0xff
                v = v or ((c and 0x7f).toLong() shl s); s += 7
                if (c and 0x80 == 0) return v
            }
            return null
        }
        while (i < b.size) {
            val k = readVarint() ?: break
            val tag = (k ushr 3).toInt(); val wire = (k and 7L).toInt()
            when (wire) {
                0 -> { val v = readVarint() ?: break; out.add(Field(tag, wire, v, ByteArray(0))) }
                2 -> {
                    val len = (readVarint() ?: break).toInt()
                    if (len < 0 || i + len > b.size) break
                    out.add(Field(tag, wire, 0, b.copyOfRange(i, i + len))); i += len
                }
                1 -> { if (i + 8 > b.size) break; i += 8 }
                5 -> { if (i + 4 > b.size) break; i += 4 }
                else -> break
            }
        }
        return out
    }

    /** Apple writes octets without leading zeros: "2:11:22:3:44:5". */
    private fun appleMac(mac: String): String =
        mac.split(':').joinToString(":") { (it.toIntOrNull(16) ?: 0).toString(16) }

    private fun canonMac(mac: String): String =
        mac.split(':').joinToString(":") { "%02x".format(it.toIntOrNull(16) ?: 0) }.lowercase()

    private fun queryApple(apsIn: List<ScanResult>, heard: Int): Fix? {
        val aps = apsIn.take(30)
        val pos = HashMap<String, Pair<Double, Double>>()
        val accs = ArrayList<Double>()
        // The framing length is a single byte, so ask in batches of ten.
        for (start in aps.indices step 10) {
            val batch = aps.subList(start, minOf(start + 10, aps.size))
            try {
                var body = ByteArray(0)
                for (r in batch) body += lenField(2, lenField(1, appleMac(r.BSSID).toByteArray(Charsets.ISO_8859_1)))
                body += varint((3L shl 3)) + varint(0) + varint((4L shl 3)) + varint(100)   // noise 0, signal 100

                var req = byteArrayOf(0, 1, 0, 5) + "en_US".toByteArray(Charsets.ISO_8859_1) +
                    byteArrayOf(0, 0x13) + "com.apple.locationd".toByteArray(Charsets.ISO_8859_1) +
                    byteArrayOf(0, 0x0a) + "8.1.12B411".toByteArray(Charsets.ISO_8859_1) +
                    byteArrayOf(0, 0, 0, 1, 0, 0, 0)
                req += byteArrayOf(body.size.toByte()) + body

                val c = (URL(APPLE).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = TIMEOUT_MS; readTimeout = TIMEOUT_MS
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    setRequestProperty("User-Agent", "locationd/1753.17 CFNetwork/711.1.12 Darwin/14.0.0")
                    doOutput = true
                }
                c.outputStream.use { it.write(req) }
                val reply = if (c.responseCode in 200..299) c.inputStream.readBytes() else ByteArray(0)
                c.disconnect()
                if (reply.size <= 10) continue

                for (dev in parse(reply.copyOfRange(10, reply.size))) {
                    if (dev.tag != 2 || dev.wire != 2) continue
                    var mac = ""; var lat = -180.0; var lon = -180.0; var hacc = -1.0
                    for (f in parse(dev.d)) {
                        if (f.tag == 1 && f.wire == 2) mac = String(f.d, Charsets.ISO_8859_1)
                        else if (f.tag == 2 && f.wire == 2) for (l in parse(f.d)) {
                            when (l.tag) {
                                1 -> lat = l.v.toDouble() / 1e8
                                2 -> lon = l.v.toDouble() / 1e8
                                3 -> hacc = l.v.toDouble()
                            }
                        }
                    }
                    // -180,-180 is Apple's "unknown"; 0,0 is nobody's house.
                    if (mac.isEmpty() || lat < -90 || lat > 90 || lon < -180 || lon > 180) continue
                    if (lat == 0.0 && lon == 0.0) continue
                    pos[canonMac(mac)] = lat to lon
                    if (hacc > 0) accs.add(hacc)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Apple batch failed: ${t.message}")
            }
        }
        if (pos.isEmpty()) { Log.i(TAG, "Apple knows none of these access points"); return null }

        // Our position is the signal-weighted centroid of the ones Apple placed.
        var sw = 0.0; var slat = 0.0; var slon = 0.0; var matched = 0
        for (r in aps) {
            val p = pos[canonMac(r.BSSID)] ?: continue
            val w = 10.0.pow(r.level / 20.0)
            sw += w; slat += w * p.first; slon += w * p.second; matched++
        }
        if (matched == 0 || sw <= 0) return null
        val lat = slat / sw; val lon = slon / sw

        // Accuracy: how far apart the placed beacons are, weighted the same way, floored by the
        // median of Apple's own per-beacon accuracy, widened when very few of them matched.
        var spread = 0.0
        for (r in aps) {
            val p = pos[canonMac(r.BSSID)] ?: continue
            val w = 10.0.pow(r.level / 20.0)
            val d = distanceM(lat, lon, p.first, p.second)
            spread += w * d * d
        }
        spread = sqrt(spread / sw)
        val medAcc = if (accs.isEmpty()) 100.0 else accs.sorted()[accs.size / 2]
        val acc = max(spread, medAcc).plus(if (matched < 3) 50.0 else 0.0).coerceIn(30.0, 1500.0)
        Log.i(TAG, "Apple fix from $matched of ${aps.size} APs (±${acc.toInt()} m)")
        return Fix(lat, lon, acc, "apple", heard, matched)
    }

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return 2 * r * Math.atan2(sqrt(a), sqrt(1 - a))
    }
}
