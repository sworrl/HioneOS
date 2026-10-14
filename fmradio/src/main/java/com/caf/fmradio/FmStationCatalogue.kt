package com.caf.fmradio

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.provider.Settings
import android.util.Log
import java.io.File
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Every broadcast station in North America and every FM station in Japan, and which of them
 * should reach you here.
 *
 * The North American rows are FCC public-record data and the Japanese rows come from Japanese
 * Wikipedia and MLIT place data (see tools/radiodb/README.md). The catalogue is
 * baked into the image at /system_ext/etc/hione/stations.sqlite. Each row carries the
 * transmitter's coordinates, ERP and HAAT, so "what is on 100.1 here" is a lookup rather than
 * a guess, and so the band display can label a peak with the call sign that produced it.
 *
 * It is a catalogue, not a prediction of what you will hear. A station listed as reachable may
 * be blocked by a ridge — this device lives in Appalachian terrain where that is the normal
 * case, not the exception — and terrain is not modelled here yet. So the ranking below is
 * honest about what it is: transmitter power and height against distance, which is an upper
 * bound on what is possible, not a promise.
 */
object FmStationCatalogue {

    private const val TAG = "FmStationCatalogue"
    private val PATHS = listOf(
        "/system_ext/etc/hione/stations.sqlite",
        "/system/etc/hione/stations.sqlite",
        "/data/local/tmp/stations.sqlite",      // for trying a newer catalogue without a flash
    )

    data class Station(
        val call: String,
        val service: String,          // FM, FX translator, FL low power, AM
        val khz: Int,
        val city: String?,
        val state: String?,
        val licensee: String?,
        val lat: Double,
        val lon: Double,
        val erpKw: Double?,
        val haatM: Double?,
        val callsignSince: String?,
        /** Great-circle kilometres from the listener. */
        val distanceKm: Double,
        /**
         * Crude reachability score in dB-ish units: power and height help, distance hurts.
         * Comparable between stations, meaningless as an absolute, and blind to terrain.
         */
        val score: Double,
        /** The station's programming as its Wikipedia infobox states it ("Classic rock"). */
        val format: String? = null,
        /** [format] folded into one of a few dozen buckets, for chips and filtering. */
        val genre: String? = null,
        /** Whether it should come in here; see [FmReach]. Filled in by the engine. */
        val reach: FmReach.Verdict = FmReach.Verdict.UNKNOWN,
        /** Predicted level at the tuner input, dBµV, after calibration. Null without ERP. */
        val predictedDbuv: Double? = null,
        /** RSSI the last band sweep read on this frequency, if one ran here recently. */
        val measuredDbuv: Int? = null,
        /**
         * Worst first-Fresnel-zone clearance on the path from here, as a fraction (1 = clear,
         * 0.6 = the conventional limit, below 0 = the ground cuts the line of sight). Null
         * until a terrain profile for this station has been obtained.
         */
        val fresnelFraction: Double? = null,
        /** FmFresnel's words for the path: "clear path", "grazing the ridge", ... */
        val terrainVerdict: String? = null,
        /** Diffraction loss over the terrain, dB (one equivalent edge); already in [predictedDbuv]. */
        val terrainLossDb: Double? = null,
        /** Antenna height above mean sea level from the licence (RCAMSL), for the terrain path. */
        val rcamslM: Double? = null,
        /** Where [measuredDbuv] came from: [MEASURED_TUNED] or [MEASURED_SWEEP]. */
        val measuredSource: String? = null,
        /** ISO country from the catalogue (US, CA, MX, JP, ...). */
        val country: String? = null,
        /** English or romaji name, where the source has one (Japanese rows: "NHK-FM Tokyo"). */
        val name: String? = null,
        /** Japanese name (Japanese rows: "NHK-FM 東京", "FMいるか"). */
        val nameJa: String? = null,
        /** The main station a relay carries (Japanese rows). */
        val parentCall: String? = null,
        /** How the transmitter was placed: "wikipedia", "oaza" or "municipality" (Japanese rows). */
        val sitePrecision: String? = null,
        /** ERP worked out from transmitter power by the catalogue's rule, not stated. */
        val erpEstimated: Boolean = false,
        /** HAAT from a class-rule mast height over real ground, not a licensed figure. */
        val haatEstimated: Boolean = false,
    ) {
        val isJapan: Boolean get() = country == "JP"

        /**
         * What a chip or row calls the station. A US station is its call sign. A Japanese row is
         * its name, because most Japanese relays have no public call sign of their own and the
         * catalogue's id for them is synthetic.
         */
        val label: String get() = when {
            isJapan -> nameJa ?: name ?: call
            else -> call
        }

        /** Short form for a 128 dp chip: "WDVE", or the Japanese name without the network prefix. */
        val chipLabel: String get() = when {
            isJapan -> (nameJa ?: name ?: call).replace("NHK-FM ", "NHK ")
            else -> call.substringBefore("-FM")
        }

        /** Second name for a Japanese row (romaji/English), null when there is none or no need. */
        val altName: String? get() = if (isJapan) name?.takeIf { it != nameJa } else null

        /** Words for [measuredSource], for the UI. */
        val measuredWhere: String get() = when (measuredSource) {
            MEASURED_TUNED -> "read while tuned here"
            MEASURED_SWEEP -> "last band sweep here"
            else -> "not measured here yet"
        }
        /**
         * 0..1 odds-style score for a gauge: where [predictedDbuv] (or the measurement, which
         * wins) sits between nothing (THRESHOLD - 10) and comfortable (LISTENABLE + 15).
         * A presentation number, not a probability.
         */
        val receptionScore: Float? get() {
            val v = measuredDbuv?.toDouble() ?: predictedDbuv ?: return null
            val lo = FmReach.THRESHOLD_DBUV - 10
            val hi = FmReach.LISTENABLE_DBUV + 15
            return ((v - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f)
        }
        val mhz: Double get() = khz / 1000.0
        /** How the UI should describe the kind of station, in words rather than FCC codes. */
        val kind: String get() = when {
            isJapan && service == "FX" -> "relay"
            isJapan && service == "FL" -> "community"
            isJapan && service == "FM" -> "main"
            service == "FX" -> "translator"
            service == "FL" -> "low power"
            service == "AM" -> "AM"
            else -> "full power"
        }
    }

    const val MEASURED_TUNED = "tuned"
    const val MEASURED_SWEEP = "sweep"

    @Volatile private var db: SQLiteDatabase? = null
    @Volatile private var missingReported = false
    @Volatile private var appContext: Context? = null

    /**
     * Hand the catalogue a context once, early, so it can fall back to a writable copy.
     *
     * Without this it can only try the baked path, and on this image that path is on a
     * read-only mount, which does not work. See [openAt].
     */
    fun prepare(ctx: Context) {
        if (appContext == null) appContext = ctx.applicationContext
    }

    private fun openAt(path: String): SQLiteDatabase? = runCatching {
        val d = SQLiteDatabase.openDatabase(
            path, null,
            // NO_LOCALIZED_COLLATORS stops Android creating its android_metadata table on open,
            // which is a WRITE and cannot succeed on a read-only mount.
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
        // openDatabase can return a handle that has not really connected yet, so prove it with
        // a statement. The failure we are guarding against shows up here, not above: on a
        // read-only filesystem the first real query blocks for SQLite's five second busy
        // timeout and then throws SQLiteDatabaseLockedException.
        d.rawQuery("SELECT COUNT(*) FROM station", null).use { it.moveToFirst() }
        d
    }.onFailure { Log.w(TAG, "could not use $path: $it") }.getOrNull()

    /**
     * A writable copy of the catalogue, made once.
     *
     * WHAT WAS MEASURED, on 0.1.14: /system_ext is mounted ro; openDatabase() on the baked
     * file reported success; the first query then threw SQLITE_BUSY exactly five seconds
     * later, which is SQLite's default busy timeout; and the band showed "0 stations" while
     * sitting on a 47,826 row catalogue and a known position. Ruled out: the file is
     * journal_mode=delete with no stray journal beside it, all 47,826 rows read fine on the
     * host, and adding an android_metadata table by hand changed nothing.
     *
     * WHAT IS NOT ESTABLISHED is the mechanism. The likely story is that Android's
     * SQLiteDatabase needs to write something when it opens a database even for reading, and
     * cannot on a read-only mount - but that was not proven, only inferred from the timeout.
     * The fix does not depend on being right about it: open the baked file, prove it with a
     * real query, and copy 8 MB into app storage once if that query does not come back.
     */
    private fun writableCopy(src: String): SQLiteDatabase? {
        val ctx = appContext ?: return null
        val dst = File(ctx.noBackupFilesDir, "stations.sqlite")
        val srcFile = File(src)
        if (!dst.exists() || dst.length() != srcFile.length()) {
            val ok = runCatching {
                srcFile.inputStream().use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
            }.onFailure { Log.w(TAG, "could not copy the catalogue to app storage: $it") }.isSuccess
            if (!ok) return null
            Log.i(TAG, "copied the catalogue to ${dst.path} (${dst.length()} bytes)")
        }
        return openAt(dst.path)
    }

    private fun open(): SQLiteDatabase? {
        db?.let { return it }
        for (p in PATHS) {
            val f = File(p)
            if (!f.exists()) continue
            // Try in place first: on a writable location this is free and avoids the copy.
            var opened = openAt(p)
            var via = p
            if (opened == null) {
                opened = writableCopy(p)
                if (opened != null) via = "a writable copy of $p"
            }
            if (opened != null) {
                db = opened
                Log.i(TAG, "catalogue open: $via")
                return opened
            }
        }
        if (!missingReported) {
            missingReported = true
            Log.w(TAG, "no station catalogue on this image; local station names unavailable")
        }
        return null
    }

    fun available(): Boolean = open() != null

    /**
     * Whether this catalogue has the format columns (tools/radiodb/add_formats.py). Older
     * images ship without them, and the queries below must work on both.
     */
    @Volatile private var columns: Set<String>? = null

    private fun columnsOf(d: SQLiteDatabase): Set<String> = columns ?: runCatching {
        d.rawQuery("PRAGMA table_info(station)", null).use { c ->
            val out = HashSet<String>()
            while (c.moveToNext()) out += c.getString(1)
            out as Set<String>
        }
    }.getOrDefault(emptySet()).also { columns = it }

    private fun formatsPresent(d: SQLiteDatabase): Boolean = "genre" in columnsOf(d)

    private fun col(d: SQLiteDatabase, c: String) = if (c in columnsOf(d)) c else "NULL"

    /** Column order is what [stationFrom] reads by index; missing columns read as NULL. */
    private fun selectColumns(d: SQLiteDatabase): String =
        "call,service,khz,city,state,licensee,lat,lon,erp_kw,haat_m,callsign_since," +
            (if (formatsPresent(d)) "format,genre" else "NULL,NULL") +
            "," + listOf("rcamsl_m", "country", "name", "name_ja", "parent_call", "site_precision",
                         "erp_estimated", "haat_estimated").joinToString(",") { col(d, it) }

    private fun stationFrom(c: android.database.Cursor, lat: Double, lon: Double): Station {
        val sLat = c.getDouble(6); val sLon = c.getDouble(7)
        val km = haversineKm(lat, lon, sLat, sLon)
        val erp = if (c.isNull(8)) null else c.getDouble(8)
        val haat = if (c.isNull(9)) null else c.getDouble(9)
        return Station(
            call = c.getString(0), service = c.getString(1), khz = c.getInt(2),
            city = c.getString(3), state = c.getString(4), licensee = c.getString(5),
            lat = sLat, lon = sLon, erpKw = erp, haatM = haat,
            callsignSince = c.getString(10), distanceKm = km,
            score = 10 * log10((erp ?: 0.01).coerceAtLeast(0.001)) +
                10 * log10((haat ?: 30.0).coerceAtLeast(1.0)) -
                20 * log10(km.coerceAtLeast(1.0)),
            format = c.getString(11), genre = c.getString(12),
            rcamslM = if (c.isNull(13)) null else c.getDouble(13),
            country = c.getString(14), name = c.getString(15), nameJa = c.getString(16),
            parentCall = c.getString(17), sitePrecision = c.getString(18),
            erpEstimated = !c.isNull(19) && c.getInt(19) != 0,
            haatEstimated = !c.isNull(20) && c.getInt(20) != 0,
        )
    }

    /**
     * Search the whole catalogue, not just what is near: call sign, city, format, genre,
     * licensee, or a frequency ("94.5", "945", "94.5 FM"). Nearest first.
     *
     * The nearby list answers "what is around me"; this answers "where is WDVE" and "what
     * plays country", which is what a search box is for.
     */
    fun search(query: String, lat: Double?, lon: Double?, limit: Int = 60): List<Station> {
        val d = open() ?: return emptyList()
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val freq = parseSearchFrequencyKHz(q)
        val like = "%${q.replace("%", "").replace("_", "")}%"
        val where = StringBuilder("service IN ('FM','FX','FL') AND lat IS NOT NULL AND (")
        val args = ArrayList<String>()
        if (freq != null) { where.append("khz BETWEEN ? AND ? OR "); args += (freq - 50).toString(); args += (freq + 50).toString() }
        where.append("call LIKE ? OR city LIKE ? OR licensee LIKE ?")
        repeat(3) { args += like }
        for (c in listOf("name", "name_ja")) if (c in columnsOf(d)) { where.append(" OR $c LIKE ?"); args += like }
        if (formatsPresent(d)) { where.append(" OR format LIKE ? OR genre LIKE ?"); repeat(2) { args += like } }
        where.append(")")
        val out = ArrayList<Station>()
        runCatching {
            d.rawQuery("SELECT ${selectColumns(d)} FROM station WHERE $where LIMIT 2000", args.toTypedArray()).use { c ->
                while (c.moveToNext()) out += stationFrom(c, lat ?: c.getDouble(6), lon ?: c.getDouble(7))
            }
        }.onFailure { Log.w(TAG, "search() failed: $it") }
        val exactCall = q.uppercase()
        return out.sortedWith(compareBy<Station>(
            { !(it.call == exactCall || it.call.substringBefore('-') == exactCall) },
            { if (lat == null) 0.0 else it.distanceKm },
        )).take(limit)
    }

    /**
     * A typed frequency as kHz: "94.5", "94.5 FM", "945" (94.5), "76.3" or "763" (Japan).
     * Anything outside 76.0-108.0 MHz is not a frequency here.
     */
    fun parseSearchFrequencyKHz(q: String): Int? =
        q.trim().removeSuffix("MHz").removeSuffix("FM").removeSuffix("fm").trim().toDoubleOrNull()?.let {
            when {
                it in 76.0..108.0 -> Math.round(it * 1000).toInt()
                it in 760.0..1080.0 -> Math.round(it * 100).toInt()     // "945" for 94.5
                else -> null
            }
        }

    /**
     * Which country's stations surround this spot: the most common country among the nearest
     * dozen catalogued FM transmitters within [radiusKm], or null when there are none. This is
     * what picks the band plan automatically, so a US listener near the coast is never moved to
     * Japan by a stray row and a listener in Japan is never left on 87.5-108.
     */
    fun countryNear(lat: Double, lon: Double, radiusKm: Double = 150.0): String? {
        val d = open() ?: return null
        if ("country" !in columnsOf(d)) return null
        val got = near(lat, lon, radiusKm = radiusKm, services = listOf("FM", "FX", "FL"), limit = 400)
            .sortedBy { it.distanceKm }.take(12)
        return got.mapNotNull { it.country }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
    }

    /** Where HioneLocationFusion last put us, or null if nothing has placed the device yet. */
    fun listenerPosition(ctx: Context): Pair<Double, Double>? = runCatching {
        val cr = ctx.contentResolver
        val lat = Settings.Global.getString(cr, "hione_loc_lat")?.toDoubleOrNull()
        val lon = Settings.Global.getString(cr, "hione_loc_lon")?.toDoubleOrNull()
        if (lat == null || lon == null) null else lat to lon
    }.getOrNull()

    private fun haversineKm(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(bLat - aLat)
        val dLon = Math.toRadians(bLon - aLon)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(min(1.0, sqrt(h)))
    }

    /**
     * Stations whose transmitters are within [radiusKm], best first.
     *
     * Bounded by a latitude/longitude box before the distance maths so the query uses the index
     * rather than walking 47,826 rows; at these radii the box is a negligible over-estimate.
     */
    fun near(
        lat: Double, lon: Double,
        radiusKm: Double = 140.0,
        services: List<String> = listOf("FM", "FX", "FL"),
        limit: Int = 80,
    ): List<Station> {
        val d = open() ?: return emptyList()
        val dLat = radiusKm / 111.0
        val dLon = radiusKm / (111.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.2))
        val placeholders = services.joinToString(",") { "?" }
        val args = (services + listOf(
            (lat - dLat).toString(), (lat + dLat).toString(),
            (lon - dLon).toString(), (lon + dLon).toString()
        )).toTypedArray()
        val out = ArrayList<Station>()
        runCatching {
            d.rawQuery(
                "SELECT ${selectColumns(d)} " +
                    "FROM station WHERE service IN ($placeholders) AND lat IS NOT NULL " +
                    "AND lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?", args
            ).use { c ->
                while (c.moveToNext()) {
                    val st = stationFrom(c, lat, lon)
                    if (st.distanceKm <= radiusKm) out += st
                }
            }
        }.onFailure { Log.w(TAG, "near() failed: $it") }
        return out.sortedByDescending { it.score }.take(limit)
    }

    /** The best candidate for one frequency, for labelling the dial and the band display. */
    fun atFrequency(lat: Double, lon: Double, khz: Int, toleranceKhz: Int = 60): Station? =
        near(lat, lon, radiusKm = 200.0, limit = 400)
            .filter { abs(it.khz - khz) <= toleranceKhz }
            .maxByOrNull { it.score }

    /** How many stations the catalogue holds, for the diagnostics sheet. */
    fun size(): Int = runCatching {
        open()?.rawQuery("SELECT COUNT(*) FROM station", null)?.use {
            if (it.moveToFirst()) it.getInt(0) else 0
        } ?: 0
    }.getOrDefault(0)
}
