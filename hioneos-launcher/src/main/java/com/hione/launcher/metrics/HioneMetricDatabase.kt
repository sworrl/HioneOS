package com.hione.launcher.metrics
import com.hione.launcher.*

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Oki Hione Telemetry & Historical Metrics Database.
 * Persists high-granularity weather history, RF network telemetry (Wi-Fi/Cellular dBm),
 * CS43198 audio hardware telemetry, and launcher configuration keys.
 */
class HioneMetricDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "hione_metrics.db"
        private const val DB_VERSION = 1
        private val dbLock = ReentrantLock()

        @Volatile
        private var instance: HioneMetricDatabase? = null

        fun getInstance(context: Context): HioneMetricDatabase {
            return instance ?: synchronized(this) {
                instance ?: HioneMetricDatabase(context).also { instance = it }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE weather_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                temp_f REAL NOT NULL,
                feels_like_f REAL NOT NULL,
                humidity_pct INTEGER NOT NULL,
                wind_speed_mph REAL NOT NULL,
                wind_direction TEXT NOT NULL,
                wind_bearing_deg REAL NOT NULL,
                weather_code INTEGER NOT NULL,
                summary TEXT NOT NULL,
                surface_pressure_hpa REAL NOT NULL,
                uv_index REAL NOT NULL,
                precip_prob_pct INTEGER NOT NULL,
                latitude REAL NOT NULL,
                longitude REAL NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE network_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                transport TEXT NOT NULL,
                wifi_rssi_dbm INTEGER,
                wifi_ssid TEXT,
                wifi_freq_mhz INTEGER,
                wifi_link_speed_mbps INTEGER,
                cellular_dbm INTEGER,
                cellular_type TEXT,
                cellular_operator TEXT,
                is_connected INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE audio_telemetry_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                dac_mode TEXT NOT NULL,
                sample_rate INTEGER NOT NULL,
                bit_depth INTEGER NOT NULL,
                gain_mode TEXT NOT NULL,
                filter_mode TEXT NOT NULL,
                dre_enabled INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS launcher_meta_settings (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS song_play_weather_telemetry (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                track_id INTEGER NOT NULL,
                title TEXT,
                artist TEXT,
                album TEXT,
                temp_f REAL,
                feels_like_f REAL,
                humidity_pct INTEGER,
                wind_speed_mph REAL,
                weather_summary TEXT,
                weather_code INTEGER,
                is_day INTEGER,
                latitude REAL,
                longitude REAL,
                location_city TEXT,
                dac_sample_rate INTEGER,
                dac_gain TEXT,
                playback_duration_ms INTEGER
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS weather_history")
        db.execSQL("DROP TABLE IF EXISTS network_history")
        db.execSQL("DROP TABLE IF EXISTS audio_telemetry_history")
        db.execSQL("DROP TABLE IF EXISTS launcher_meta_settings")
        db.execSQL("DROP TABLE IF EXISTS song_play_weather_telemetry")
        onCreate(db)
    }

    // ================================================================
    // WEATHER HISTORY REPOSITORY
    // ================================================================

    data class WeatherRecord(
        val id: Long = 0,
        val timestamp: Long,
        val tempF: Float,
        val feelsLikeF: Float,
        val humidityPct: Int,
        val windSpeedMph: Float,
        val windDirection: String,
        val windBearingDeg: Float,
        val weatherCode: Int,
        val summary: String,
        val surfacePressureHpa: Float,
        val uvIndex: Float,
        val precipProbPct: Int,
        val latitude: Double,
        val longitude: Double
    )

    suspend fun insertWeather(record: WeatherRecord) = withContext(Dispatchers.IO) {
        dbLock.withLock {
            try {
                val db = writableDatabase
                val values = ContentValues().apply {
                    put("timestamp", record.timestamp)
                    put("temp_f", record.tempF)
                    put("feels_like_f", record.feelsLikeF)
                    put("humidity_pct", record.humidityPct)
                    put("wind_speed_mph", record.windSpeedMph)
                    put("wind_direction", record.windDirection)
                    put("wind_bearing_deg", record.windBearingDeg)
                    put("weather_code", record.weatherCode)
                    put("summary", record.summary)
                    put("surface_pressure_hpa", record.surfacePressureHpa)
                    put("uv_index", record.uvIndex)
                    put("precip_prob_pct", record.precipProbPct)
                    put("latitude", record.latitude)
                    put("longitude", record.longitude)
                }
                db.insert("weather_history", null, values)

                // Maintain max 500 historical points
                db.execSQL("DELETE FROM weather_history WHERE id NOT IN (SELECT id FROM weather_history ORDER BY id DESC LIMIT 500)")
            } catch (_: Throwable) {}
        }
    }

    suspend fun getRecentWeatherHistory(limit: Int = 24): List<WeatherRecord> = withContext(Dispatchers.IO) {
        dbLock.withLock {
            val list = mutableListOf<WeatherRecord>()
            try {
                val db = readableDatabase
                val cursor = db.rawQuery(
                    "SELECT id, timestamp, temp_f, feels_like_f, humidity_pct, wind_speed_mph, wind_direction, wind_bearing_deg, weather_code, summary, surface_pressure_hpa, uv_index, precip_prob_pct, latitude, longitude FROM weather_history ORDER BY timestamp DESC LIMIT ?",
                    arrayOf(limit.toString())
                )
                cursor.use { c ->
                    while (c.moveToNext()) {
                        list.add(
                            WeatherRecord(
                                id = c.getLong(0),
                                timestamp = c.getLong(1),
                                tempF = c.getFloat(2),
                                feelsLikeF = c.getFloat(3),
                                humidityPct = c.getInt(4),
                                windSpeedMph = c.getFloat(5),
                                windDirection = c.getString(6) ?: "N",
                                windBearingDeg = c.getFloat(7),
                                weatherCode = c.getInt(8),
                                summary = c.getString(9) ?: "Clear",
                                surfacePressureHpa = c.getFloat(10),
                                uvIndex = c.getFloat(11),
                                precipProbPct = c.getInt(12),
                                latitude = c.getDouble(13),
                                longitude = c.getDouble(14)
                            )
                        )
                    }
                }
            } catch (_: Throwable) {}
            list
        }
    }

    // ================================================================
    // NETWORK RF TELEMETRY REPOSITORY
    // ================================================================

    data class NetworkRecord(
        val id: Long = 0,
        val timestamp: Long,
        val transport: String,
        val wifiRssiDbm: Int?,
        val wifiSsid: String?,
        val wifiFreqMhz: Int?,
        val wifiLinkSpeedMbps: Int?,
        val cellularDbm: Int?,
        val cellularType: String?,
        val cellularOperator: String?,
        val isConnected: Boolean
    )

    suspend fun insertNetwork(record: NetworkRecord) = withContext(Dispatchers.IO) {
        dbLock.withLock {
            try {
                val db = writableDatabase
                val values = ContentValues().apply {
                    put("timestamp", record.timestamp)
                    put("transport", record.transport)
                    put("wifi_rssi_dbm", record.wifiRssiDbm)
                    put("wifi_ssid", record.wifiSsid)
                    put("wifi_freq_mhz", record.wifiFreqMhz)
                    put("wifi_link_speed_mbps", record.wifiLinkSpeedMbps)
                    put("cellular_dbm", record.cellularDbm)
                    put("cellular_type", record.cellularType)
                    put("cellular_operator", record.cellularOperator)
                    put("is_connected", if (record.isConnected) 1 else 0)
                }
                db.insert("network_history", null, values)
                db.execSQL("DELETE FROM network_history WHERE id NOT IN (SELECT id FROM network_history ORDER BY id DESC LIMIT 300)")
            } catch (_: Throwable) {}
        }
    }

    suspend fun getRecentNetworkHistory(limit: Int = 20): List<NetworkRecord> = withContext(Dispatchers.IO) {
        dbLock.withLock {
            val list = mutableListOf<NetworkRecord>()
            try {
                val db = readableDatabase
                val cursor = db.rawQuery(
                    "SELECT id, timestamp, transport, wifi_rssi_dbm, wifi_ssid, wifi_freq_mhz, wifi_link_speed_mbps, cellular_dbm, cellular_type, cellular_operator, is_connected FROM network_history ORDER BY timestamp DESC LIMIT ?",
                    arrayOf(limit.toString())
                )
                cursor.use { c ->
                    while (c.moveToNext()) {
                        list.add(
                            NetworkRecord(
                                id = c.getLong(0),
                                timestamp = c.getLong(1),
                                transport = c.getString(2) ?: "WIFI",
                                wifiRssiDbm = if (c.isNull(3)) null else c.getInt(3),
                                wifiSsid = c.getString(4),
                                wifiFreqMhz = if (c.isNull(5)) null else c.getInt(5),
                                wifiLinkSpeedMbps = if (c.isNull(6)) null else c.getInt(6),
                                cellularDbm = if (c.isNull(7)) null else c.getInt(7),
                                cellularType = c.getString(8),
                                cellularOperator = c.getString(9),
                                isConnected = c.getInt(10) == 1
                            )
                        )
                    }
                }
            } catch (_: Throwable) {}
            list
        }
    }

    // ================================================================
    // META SETTINGS KEY-VALUE STORE
    // ================================================================

    suspend fun setSetting(key: String, value: String) = withContext(Dispatchers.IO) {
        dbLock.withLock {
            try {
                val db = writableDatabase
                val values = ContentValues().apply {
                    put("key", key)
                    put("value", value)
                    put("updated_at", System.currentTimeMillis())
                }
                db.insertWithOnConflict("launcher_meta_settings", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            } catch (_: Throwable) {}
        }
    }

    suspend fun getSetting(key: String, defaultValue: String = ""): String = withContext(Dispatchers.IO) {
        dbLock.withLock {
            try {
                val db = readableDatabase
                val cursor = db.rawQuery("SELECT value FROM launcher_meta_settings WHERE key = ?", arrayOf(key))
                cursor.use { c ->
                    if (c.moveToFirst()) c.getString(0) else defaultValue
                }
            } catch (_: Throwable) {
                defaultValue
            }
        }
    }

    // ================================================================
    // SONG PLAYBACK + METEOROLOGICAL TELEMETRY
    // ================================================================

    data class SongPlayWeatherRecord(
        val id: Long = 0,
        val timestamp: Long = System.currentTimeMillis(),
        val trackId: Long,
        val title: String,
        val artist: String,
        val album: String,
        val tempF: Float,
        val feelsLikeF: Float,
        val humidityPct: Int,
        val windSpeedMph: Float,
        val weatherSummary: String,
        val weatherCode: Int,
        val isDay: Boolean,
        val latitude: Double,
        val longitude: Double,
        val locationCity: String,
        // 0 / blank = not reported by the player for this play; never a presumed 44.1k/HIGH.
        val dacSampleRate: Int = 0,
        val dacGain: String = "",
        val playbackDurationMs: Long = 0L
    )

    suspend fun insertSongPlayWeatherTelemetry(record: SongPlayWeatherRecord) = withContext(Dispatchers.IO) {
        dbLock.withLock {
            try {
                val db = writableDatabase
                val values = ContentValues().apply {
                    put("timestamp", record.timestamp)
                    put("track_id", record.trackId)
                    put("title", record.title)
                    put("artist", record.artist)
                    put("album", record.album)
                    put("temp_f", record.tempF)
                    put("feels_like_f", record.feelsLikeF)
                    put("humidity_pct", record.humidityPct)
                    put("wind_speed_mph", record.windSpeedMph)
                    put("weather_summary", record.weatherSummary)
                    put("weather_code", record.weatherCode)
                    put("is_day", if (record.isDay) 1 else 0)
                    put("latitude", record.latitude)
                    put("longitude", record.longitude)
                    put("location_city", record.locationCity)
                    put("dac_sample_rate", record.dacSampleRate)
                    put("dac_gain", record.dacGain)
                    put("playback_duration_ms", record.playbackDurationMs)
                }
                db.insert("song_play_weather_telemetry", null, values)
            } catch (_: Throwable) {}
        }
    }

    suspend fun getRecentSongPlayWeatherHistory(limit: Int = 50): List<SongPlayWeatherRecord> = withContext(Dispatchers.IO) {
        dbLock.withLock {
            val list = mutableListOf<SongPlayWeatherRecord>()
            try {
                val db = readableDatabase
                val cursor = db.rawQuery(
                    "SELECT id, timestamp, track_id, title, artist, album, temp_f, feels_like_f, humidity_pct, wind_speed_mph, weather_summary, weather_code, is_day, latitude, longitude, location_city, dac_sample_rate, dac_gain, playback_duration_ms FROM song_play_weather_telemetry ORDER BY timestamp DESC LIMIT ?",
                    arrayOf(limit.toString())
                )
                cursor.use { c ->
                    while (c.moveToNext()) {
                        list.add(
                            SongPlayWeatherRecord(
                                id = c.getLong(0),
                                timestamp = c.getLong(1),
                                trackId = c.getLong(2),
                                title = c.getString(3) ?: "",
                                artist = c.getString(4) ?: "",
                                album = c.getString(5) ?: "",
                                tempF = c.getFloat(6),
                                feelsLikeF = c.getFloat(7),
                                humidityPct = c.getInt(8),
                                windSpeedMph = c.getFloat(9),
                                weatherSummary = c.getString(10) ?: "",
                                weatherCode = c.getInt(11),
                                isDay = c.getInt(12) == 1,
                                latitude = c.getDouble(13),
                                longitude = c.getDouble(14),
                                locationCity = c.getString(15) ?: "",
                                dacSampleRate = c.getInt(16),
                                dacGain = c.getString(17) ?: "",
                                playbackDurationMs = c.getLong(18)
                            )
                        )
                    }
                }
            } catch (_: Throwable) {}
            list
        }
    }
}
