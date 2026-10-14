package com.hione.launcher.bpm

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.abs
import kotlin.math.roundToInt
import org.json.JSONObject

/**
 * Oki Hione BPM & Rhythm Calibration Database.
 *
 * Features:
 * 1. Persistent track BPM storage with multi-source tracking (ONLINE_DB, USER_CALIBRATED, DSP_ANALYZER).
 * 2. Instant pre-seeded online database for popular Vocaloid, Pop, Rock, and Electronic tracks.
 * 3. High-precision tap telemetry logging (offset deviation, accuracy, combo, instantaneous BPM).
 * 4. Automatic Double-Time / Half-BPM octave resolution and calibration learning.
 */
class HioneBpmDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    data class TrackBpmRecord(
        val id: Long = 0,
        val artist: String,
        val title: String,
        val album: String = "",
        val canonicalBpm: Float,
        // Honest defaults. These used to mirror canonicalBpm and claim confidence 1.0 from
        // "USER_CALIBRATED" with tapCount 1, so ANY caller that omitted them silently recorded a
        // fabricated user calibration — and "USER CAL" in the stats row counts exactly that source.
        val rawDetectedBpm: Float = 0f,      // 0 = nothing was auto-detected
        val userTappedBpm: Float = 0f,       // 0 = nobody tapped this track
        val tempoMultiplier: Float = 1.0f,
        val confidence: Float = 0f,          // 0 = no confidence was ever computed
        val source: String = "UNKNOWN",
        val tapCount: Int = 0,
        val lastCalibratedEpoch: Long = System.currentTimeMillis()
    )

    data class TapTelemetryRecord(
        val id: Long = 0,
        val artist: String,
        val title: String,
        val tapEpochMs: Long,
        val targetBeatMs: Long,
        val deviationMs: Int,
        val accuracy: String,
        val instantaneousBpm: Float,
        val comboAtTap: Int,
        val isFever: Boolean
    )

    companion object {
        private const val TAG = "HioneBpmDatabase"
        private const val DB_NAME = "hione_bpm_calibration.db"
        private const val DB_VERSION = 1
        private val dbLock = ReentrantLock()

        @Volatile
        private var instance: HioneBpmDatabase? = null

        fun getInstance(context: Context): HioneBpmDatabase {
            return instance ?: synchronized(this) {
                instance ?: HioneBpmDatabase(context).also { instance = it }
            }
        }

        // High-Precision Pre-Seeded Global BPM Dictionary
        private val PRESEEDED_BPM_DICTIONARY = mapOf(
            "beck|cellphone's dead" to 108.0f,
            "beck|cellphones dead" to 108.0f,
            "beck|loser" to 85.0f,
            "beck|e-pro" to 96.0f,
            "supercell|world is mine" to 165.0f,
            "supercell feat. hatsune miku|world is mine" to 165.0f,
            "supercell|melt" to 170.0f,
            "supercell feat. hatsune miku|melt" to 170.0f,
            "kurousa-p|senbonzakura" to 154.0f,
            "whiteflame feat. hatsune miku|senbonzakura" to 154.0f,
            "kz|tell your world" to 150.0f,
            "livetune feat. hatsune miku|tell your world" to 150.0f,
            "lamaze-p|popipo" to 140.0f,
            "wowaka|rolling girl" to 195.0f,
            "wowaka|world's end dancehall" to 165.0f,
            "wowaka|two-faced lovers" to 210.0f,
            "wowaka|ura-omote lovers" to 210.0f,
            "wowaka|unknown mother goose" to 224.0f,
            "deko|ghost rule" to 210.0f,
            "deco*27|ghost rule" to 210.0f,
            "deco*27|vampire" to 132.0f,
            "deco*27|hibana" to 200.0f,
            "deco*27|otome dissection" to 135.0f,
            "samfree|luka luka night fever" to 160.0f,
            "giga-p|bring it on" to 145.0f,
            "giga-p|劣等上等" to 145.0f,
            "mitchie m|freely tomorrow" to 135.0f,
            "daft punk|around the world" to 121.0f,
            "daft punk|one more time" to 123.0f,
            "daft punk|get lucky" to 116.0f,
            "gorillaz|feel good inc" to 139.0f,
            "gorillaz|clint eastwood" to 84.0f
        )
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS track_bpm_calibration (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                artist TEXT NOT NULL,
                title TEXT NOT NULL,
                album TEXT,
                canonical_bpm REAL NOT NULL,
                raw_detected_bpm REAL,
                user_tapped_bpm REAL,
                tempo_multiplier REAL DEFAULT 1.0,
                confidence REAL DEFAULT 1.0,
                source TEXT NOT NULL,
                tap_count INTEGER DEFAULT 0,
                last_calibrated_epoch INTEGER NOT NULL,
                UNIQUE(artist, title)
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS bpm_tap_telemetry (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                artist TEXT,
                title TEXT,
                tap_epoch_ms INTEGER NOT NULL,
                target_beat_ms INTEGER NOT NULL,
                deviation_ms INTEGER NOT NULL,
                accuracy TEXT NOT NULL,
                instantaneous_bpm REAL NOT NULL,
                combo_at_tap INTEGER NOT NULL,
                is_fever INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS idx_track_lookup ON track_bpm_calibration(artist, title)
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Safe schema evolution
    }

    /**
     * Resolves the canonical BPM for a given track from SQLite cache, pre-seeded dictionary,
     * or online lookup, saving calculations and DSP load.
     */
    suspend fun resolveCanonicalBpm(artist: String, title: String): TrackBpmRecord? = withContext(Dispatchers.IO) {
        val cleanArtist = artist.trim().lowercase()
        val cleanTitle = title.trim().lowercase()
        val key = "$cleanArtist|$cleanTitle"

        // 1. Query Local SQLite Calibration Database
        dbLock.withLock {
            val db = readableDatabase
            val cursor = db.rawQuery(
                "SELECT * FROM track_bpm_calibration WHERE lower(artist) = ? AND lower(title) = ? LIMIT 1",
                arrayOf(cleanArtist, cleanTitle)
            )
            cursor.use { c ->
                if (c.moveToFirst()) {
                    return@withContext TrackBpmRecord(
                        id = c.getLong(c.getColumnIndexOrThrow("id")),
                        artist = c.getString(c.getColumnIndexOrThrow("artist")),
                        title = c.getString(c.getColumnIndexOrThrow("title")),
                        album = c.getString(c.getColumnIndexOrThrow("album")) ?: "",
                        canonicalBpm = c.getFloat(c.getColumnIndexOrThrow("canonical_bpm")),
                        rawDetectedBpm = c.getFloat(c.getColumnIndexOrThrow("raw_detected_bpm")),
                        userTappedBpm = c.getFloat(c.getColumnIndexOrThrow("user_tapped_bpm")),
                        tempoMultiplier = c.getFloat(c.getColumnIndexOrThrow("tempo_multiplier")),
                        confidence = c.getFloat(c.getColumnIndexOrThrow("confidence")),
                        source = c.getString(c.getColumnIndexOrThrow("source")),
                        tapCount = c.getInt(c.getColumnIndexOrThrow("tap_count")),
                        lastCalibratedEpoch = c.getLong(c.getColumnIndexOrThrow("last_calibrated_epoch"))
                    )
                }
            }
        }

        // 2. Query Pre-seeded Global Dictionary
        val preseeded = PRESEEDED_BPM_DICTIONARY.entries.firstOrNull { (k, _) ->
            key.contains(k) || k.contains(key) || (cleanTitle.contains(k.substringAfter('|')) && cleanArtist.contains(k.substringBefore('|')))
        }?.value

        if (preseeded != null && preseeded > 0f) {
            val record = TrackBpmRecord(
                artist = artist,
                title = title,
                canonicalBpm = preseeded,
                // Nothing was detected and nobody tapped — a dictionary constant is not a
                // measurement, and 0.99 "confidence" was invented out of nothing.
                rawDetectedBpm = 0f,
                userTappedBpm = 0f,
                confidence = 0f,
                source = "PRESEEDED_DICTIONARY"
            )
            saveTrackBpm(record)
            return@withContext record
        }

        // 3. Online SongBPM / MusicBrainz Query
        val onlineBpm = fetchOnlineBpm(artist, title)
        if (onlineBpm != null && onlineBpm in 40f..300f) {
            val record = TrackBpmRecord(
                artist = artist,
                title = title,
                canonicalBpm = onlineBpm,
                // A remote lookup is not a local detection and not a user tap.
                rawDetectedBpm = 0f,
                userTappedBpm = 0f,
                confidence = 0f,
                source = "ONLINE_MUSICBRAINZ"
            )
            saveTrackBpm(record)
            return@withContext record
        }

        return@withContext null
    }

    /**
     * Saves or updates calibrated BPM and multiplier for a track.
     */
    suspend fun saveTrackBpm(record: TrackBpmRecord) = withContext(Dispatchers.IO) {
        dbLock.withLock {
            val db = writableDatabase
            val values = ContentValues().apply {
                put("artist", record.artist)
                put("title", record.title)
                put("album", record.album)
                put("canonical_bpm", record.canonicalBpm)
                put("raw_detected_bpm", record.rawDetectedBpm)
                put("user_tapped_bpm", record.userTappedBpm)
                put("tempo_multiplier", record.tempoMultiplier)
                put("confidence", record.confidence)
                put("source", record.source)
                put("tap_count", record.tapCount)
                put("last_calibrated_epoch", record.lastCalibratedEpoch)
            }
            db.insertWithOnConflict("track_bpm_calibration", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    /**
     * Logs real-time tap telemetry for calibration analytics.
     */
    suspend fun logTapTelemetry(record: TapTelemetryRecord) = withContext(Dispatchers.IO) {
        dbLock.withLock {
            val db = writableDatabase
            val values = ContentValues().apply {
                put("artist", record.artist)
                put("title", record.title)
                put("tap_epoch_ms", record.tapEpochMs)
                put("target_beat_ms", record.targetBeatMs)
                put("deviation_ms", record.deviationMs)
                put("accuracy", record.accuracy)
                put("instantaneous_bpm", record.instantaneousBpm)
                put("combo_at_tap", record.comboAtTap)
                put("is_fever", if (record.isFever) 1 else 0)
            }
            db.insert("bpm_tap_telemetry", null, values)
        }
    }

    /**
     * Fetches recent calibration count & stats.
     */
    suspend fun getCalibrationStats(): Map<String, Any> = withContext(Dispatchers.IO) {
        dbLock.withLock {
            val db = readableDatabase
            var totalTracks = 0
            var userCalibrated = 0
            var totalTaps = 0
            var perfectTaps = 0

            db.rawQuery("SELECT count(*), sum(case when source='USER_CALIBRATED' then 1 else 0 end) FROM track_bpm_calibration", null).use { c ->
                if (c.moveToFirst()) {
                    totalTracks = c.getInt(0)
                    userCalibrated = c.getInt(1)
                }
            }

            db.rawQuery("SELECT count(*), sum(case when accuracy='PERFECT' then 1 else 0 end) FROM bpm_tap_telemetry", null).use { c ->
                if (c.moveToFirst()) {
                    totalTaps = c.getInt(0)
                    perfectTaps = c.getInt(1)
                }
            }

            return@withContext mapOf(
                "totalTracks" to totalTracks,
                "userCalibrated" to userCalibrated,
                "totalTaps" to totalTaps,
                // -1 = no taps logged, so there is no accuracy to report. It used to return 100,
                // which the ACCURACY badge rendered as a flawless "100%" for an empty table.
                "perfectAccuracyPct" to if (totalTaps > 0) (perfectTaps.toFloat() / totalTaps * 100).toInt() else -1
            )
        }
    }

    /**
     * Lightweight online BPM fetcher via MusicBrainz / AcousticBrainz / Open Audio Features API.
     */
    private fun fetchOnlineBpm(artist: String, title: String): Float? {
        return try {
            val encodedQuery = URLEncoder.encode("artist:\"$artist\" AND recording:\"$title\"", "UTF-8")
            val url = URL("https://musicbrainz.org/ws/2/recording/?query=$encodedQuery&fmt=json&limit=1")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.setRequestProperty("User-Agent", "HioneOS-DAP-Player/1.0 ( reaver@hione.local )")

            if (conn.responseCode == 200) {
                val jsonStr = conn.inputStream.bufferedReader().readText()
                val root = JSONObject(jsonStr)
                val recordings = root.optJSONArray("recordings")
                if (recordings != null && recordings.length() > 0) {
                    val rec = recordings.getJSONObject(0)
                    val bpmVal = rec.optDouble("bpm", 0.0)
                    if (bpmVal > 0.0) return bpmVal.toFloat()
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }
}
