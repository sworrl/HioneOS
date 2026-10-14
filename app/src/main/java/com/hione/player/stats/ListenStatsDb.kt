package com.hione.player.stats

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Per-listen stats store — one row in [TABLE_LISTENS] for every time a track is played (fully,
 * partially, or skipped), plus the persisted Last.fm scrobble queue ([TABLE_SCROBBLE_QUEUE]).
 *
 * Deliberately its own SQLite file (`hione_listens.db`), separate from HioneMetricDatabase: that
 * one drops-and-recreates every table on upgrade, which would be catastrophic for a listening
 * history that is supposed to accumulate for years (Wrapped-style recaps). Upgrades here are
 * additive only — new columns via ALTER TABLE, never DROP.
 *
 * Plain SQLiteOpenHelper on purpose: no Room, no new dependencies. Every write goes through
 * [ListenSessionTracker]'s single background executor; reads come from [StatsRepository] on
 * Dispatchers.IO. WAL mode lets the stats screen read while a listen row is being finalised.
 *
 * Nothing in here is ever inferred or made up: a column is NULL when the value genuinely wasn't
 * available (no location fix, no cached sample rate, unknown output route).
 */
class ListenStatsDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "hione_listens.db"
        private const val DB_VERSION = 1

        const val TABLE_LISTENS = "listens"
        const val TABLE_SCROBBLE_QUEUE = "scrobble_queue"

        @Volatile private var instance: ListenStatsDb? = null

        fun get(context: Context): ListenStatsDb =
            instance ?: synchronized(this) {
                instance ?: ListenStatsDb(context).also { instance = it }
            }
    }

    /** A single listen. Mirrors the `listens` table 1:1 — see [onCreate] for column semantics. */
    data class Listen(
        val id: Long = 0L,
        val trackId: Long,
        val path: String?,
        val title: String,
        val artist: String,
        val album: String?,
        val durationMs: Long,
        val startedAt: Long,
        val endedAt: Long?,
        val playedMs: Long,
        val fraction: Float,
        val qualified: Boolean,
        val skipped: Boolean,
        val seekCount: Int,
        val source: String,
        val output: String?,
        val volume: Int?,
        val volumeMax: Int?,
        val sampleRateHz: Int?,
        val bitDepth: Int?,
        val lat: Double?,
        val lon: Double?,
        val locationLabel: String?,
        val dayOfWeek: Int,      // java.util.Calendar.DAY_OF_WEEK: 1 = Sunday … 7 = Saturday
        val hour: Int,           // 0..23, local time at start
        val heartsDuring: Int,   // heart taps that landed inside [startedAt, endedAt]
        val heartCountAfter: Int, // cumulative heart score for the track once this listen ended
        val endReason: String?   // "auto" (played through) | "skip" | "seek-to-other" | "stop" | "release" | "recovered"
    )

    /** One queued Last.fm scrobble (see ScrobbleManager). Persisted so an offline listen still
     *  reaches Last.fm when the M500 next finds a network. */
    data class QueuedScrobble(
        val id: Long = 0L,
        val listenId: Long?,
        val artist: String,
        val track: String,
        val album: String?,
        val durationSec: Int?,
        val timestampSec: Long,
        val attempts: Int,
        val lastError: String?,
        val nextAttemptAt: Long,
        val createdAt: Long
    )

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        runCatching { db.enableWriteAheadLogging() }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_LISTENS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                track_id INTEGER NOT NULL,
                path TEXT,
                title TEXT NOT NULL,
                artist TEXT NOT NULL,
                album TEXT,
                duration_ms INTEGER NOT NULL,
                started_at INTEGER NOT NULL,
                ended_at INTEGER,
                played_ms INTEGER NOT NULL DEFAULT 0,
                fraction REAL NOT NULL DEFAULT 0,
                qualified INTEGER NOT NULL DEFAULT 0,
                skipped INTEGER NOT NULL DEFAULT 0,
                seek_count INTEGER NOT NULL DEFAULT 0,
                source TEXT NOT NULL DEFAULT 'local',
                output TEXT,
                volume INTEGER,
                volume_max INTEGER,
                sample_rate_hz INTEGER,
                bit_depth INTEGER,
                lat REAL,
                lon REAL,
                location_label TEXT,
                day_of_week INTEGER NOT NULL,
                hour INTEGER NOT NULL,
                hearts_during INTEGER NOT NULL DEFAULT 0,
                heart_count_after INTEGER NOT NULL DEFAULT 0,
                end_reason TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_listens_started ON $TABLE_LISTENS(started_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_listens_track ON $TABLE_LISTENS(track_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_listens_artist ON $TABLE_LISTENS(artist)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_listens_album ON $TABLE_LISTENS(artist, album)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_SCROBBLE_QUEUE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                listen_id INTEGER,
                artist TEXT NOT NULL,
                track TEXT NOT NULL,
                album TEXT,
                duration_sec INTEGER,
                timestamp_sec INTEGER NOT NULL,
                attempts INTEGER NOT NULL DEFAULT 0,
                last_error TEXT,
                next_attempt_at INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_queue_next ON $TABLE_SCROBBLE_QUEUE(next_attempt_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Additive-only migrations. Never DROP: this is the user's listening history.
        onCreate(db)
    }

    // ------------------------------------------------------------------ listens: writes

    /** Opens a listen row the moment a track starts (ended_at NULL). Returns the row id so the
     *  tracker can checkpoint + finalise it. */
    fun openListen(l: Listen): Long {
        val cv = ContentValues().apply {
            put("track_id", l.trackId)
            put("path", l.path)
            put("title", l.title)
            put("artist", l.artist)
            put("album", l.album)
            put("duration_ms", l.durationMs)
            put("started_at", l.startedAt)
            putNullable("ended_at", l.endedAt)
            put("played_ms", l.playedMs)
            put("fraction", l.fraction)
            put("qualified", if (l.qualified) 1 else 0)
            put("skipped", if (l.skipped) 1 else 0)
            put("seek_count", l.seekCount)
            put("source", l.source)
            put("output", l.output)
            putNullable("volume", l.volume)
            putNullable("volume_max", l.volumeMax)
            putNullable("sample_rate_hz", l.sampleRateHz)
            putNullable("bit_depth", l.bitDepth)
            putNullable("lat", l.lat)
            putNullable("lon", l.lon)
            put("location_label", l.locationLabel)
            put("day_of_week", l.dayOfWeek)
            put("hour", l.hour)
            put("hearts_during", l.heartsDuring)
            put("heart_count_after", l.heartCountAfter)
            put("end_reason", l.endReason)
        }
        return writableDatabase.insert(TABLE_LISTENS, null, cv)
    }

    /** Periodic checkpoint (every ~30 s) so a hard process kill still leaves a truthful partial
     *  row rather than nothing. ended_at stays NULL until [finishListen]. */
    fun checkpointListen(id: Long, playedMs: Long, fraction: Float, qualified: Boolean, skipped: Boolean, seekCount: Int) {
        val cv = ContentValues().apply {
            put("played_ms", playedMs)
            put("fraction", fraction)
            put("qualified", if (qualified) 1 else 0)
            put("skipped", if (skipped) 1 else 0)
            put("seek_count", seekCount)
        }
        writableDatabase.update(TABLE_LISTENS, cv, "id = ?", arrayOf(id.toString()))
    }

    fun finishListen(
        id: Long, endedAt: Long, playedMs: Long, fraction: Float, qualified: Boolean, skipped: Boolean,
        seekCount: Int, heartsDuring: Int, heartCountAfter: Int, endReason: String,
        sampleRateHz: Int?, bitDepth: Int?, durationMs: Long? = null, title: String? = null, artist: String? = null, album: String? = null
    ) {
        val cv = ContentValues().apply {
            put("ended_at", endedAt)
            // Duration/metadata can arrive late (ExoPlayer reports TIME_UNSET at transition; tags
            // load after prepare) — refine what we know, never blank out what we had.
            if (durationMs != null && durationMs > 0) put("duration_ms", durationMs)
            if (!title.isNullOrBlank()) put("title", title)
            if (!artist.isNullOrBlank()) put("artist", artist)
            if (!album.isNullOrBlank()) put("album", album)
            put("played_ms", playedMs)
            put("fraction", fraction)
            put("qualified", if (qualified) 1 else 0)
            put("skipped", if (skipped) 1 else 0)
            put("seek_count", seekCount)
            put("hearts_during", heartsDuring)
            put("heart_count_after", heartCountAfter)
            put("end_reason", endReason)
            // Quality may have been probed by TrackTech during the listen — fill it if we have it
            // now and didn't at start. Never overwrite a known value with null.
            if (sampleRateHz != null) put("sample_rate_hz", sampleRateHz)
            if (bitDepth != null) put("bit_depth", bitDepth)
        }
        writableDatabase.update(TABLE_LISTENS, cv, "id = ?", arrayOf(id.toString()))
    }

    fun deleteListen(id: Long) { writableDatabase.delete(TABLE_LISTENS, "id = ?", arrayOf(id.toString())) }

    /** Rows left open by a crash / force-stop get closed with their last checkpoint. Their
     *  ended_at is unknown, so it's set to started_at + played_ms (a lower bound, never an
     *  inflated one) and end_reason = "recovered". Drops rows that never reached 1 s of play. */
    fun recoverOpenListens() {
        val db = writableDatabase
        db.delete(TABLE_LISTENS, "ended_at IS NULL AND played_ms < 1000", null)
        db.execSQL("UPDATE $TABLE_LISTENS SET ended_at = started_at + played_ms, end_reason = 'recovered' WHERE ended_at IS NULL")
    }

    /** Debug/maintenance: drop every listen shorter than [minPlayedMs] (noise from rapid skipping). */
    fun deleteShortListens(minPlayedMs: Long): Int =
        writableDatabase.delete(TABLE_LISTENS, "played_ms < ? AND ended_at IS NOT NULL", arrayOf(minPlayedMs.toString()))

    // ------------------------------------------------------------------ listens: reads

    fun query(sql: String, args: Array<String>? = null): Cursor = readableDatabase.rawQuery(sql, args)

    fun readListen(c: Cursor): Listen = Listen(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        trackId = c.getLong(c.getColumnIndexOrThrow("track_id")),
        path = c.getStringOrNull("path"),
        title = c.getString(c.getColumnIndexOrThrow("title")),
        artist = c.getString(c.getColumnIndexOrThrow("artist")),
        album = c.getStringOrNull("album"),
        durationMs = c.getLong(c.getColumnIndexOrThrow("duration_ms")),
        startedAt = c.getLong(c.getColumnIndexOrThrow("started_at")),
        endedAt = c.getLongOrNull("ended_at"),
        playedMs = c.getLong(c.getColumnIndexOrThrow("played_ms")),
        fraction = c.getFloat(c.getColumnIndexOrThrow("fraction")),
        qualified = c.getInt(c.getColumnIndexOrThrow("qualified")) != 0,
        skipped = c.getInt(c.getColumnIndexOrThrow("skipped")) != 0,
        seekCount = c.getInt(c.getColumnIndexOrThrow("seek_count")),
        source = c.getString(c.getColumnIndexOrThrow("source")),
        output = c.getStringOrNull("output"),
        volume = c.getIntOrNull("volume"),
        volumeMax = c.getIntOrNull("volume_max"),
        sampleRateHz = c.getIntOrNull("sample_rate_hz"),
        bitDepth = c.getIntOrNull("bit_depth"),
        lat = c.getDoubleOrNull("lat"),
        lon = c.getDoubleOrNull("lon"),
        locationLabel = c.getStringOrNull("location_label"),
        dayOfWeek = c.getInt(c.getColumnIndexOrThrow("day_of_week")),
        hour = c.getInt(c.getColumnIndexOrThrow("hour")),
        heartsDuring = c.getInt(c.getColumnIndexOrThrow("hearts_during")),
        heartCountAfter = c.getInt(c.getColumnIndexOrThrow("heart_count_after")),
        endReason = c.getStringOrNull("end_reason")
    )

    // ------------------------------------------------------------------ scrobble queue

    fun enqueueScrobble(q: QueuedScrobble): Long {
        val cv = ContentValues().apply {
            putNullable("listen_id", q.listenId)
            put("artist", q.artist)
            put("track", q.track)
            put("album", q.album)
            putNullable("duration_sec", q.durationSec)
            put("timestamp_sec", q.timestampSec)
            put("attempts", q.attempts)
            put("last_error", q.lastError)
            put("next_attempt_at", q.nextAttemptAt)
            put("created_at", q.createdAt)
        }
        return writableDatabase.insert(TABLE_SCROBBLE_QUEUE, null, cv)
    }

    /** Oldest-first batch of scrobbles that are due (next_attempt_at <= now). Last.fm accepts at
     *  most 50 per track.scrobble call. */
    fun dueScrobbles(now: Long, limit: Int = 50): List<QueuedScrobble> {
        val out = ArrayList<QueuedScrobble>()
        readableDatabase.rawQuery(
            "SELECT * FROM $TABLE_SCROBBLE_QUEUE WHERE next_attempt_at <= ? ORDER BY timestamp_sec ASC LIMIT ?",
            arrayOf(now.toString(), limit.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(
                QueuedScrobble(
                    id = c.getLong(c.getColumnIndexOrThrow("id")),
                    listenId = c.getLongOrNull("listen_id"),
                    artist = c.getString(c.getColumnIndexOrThrow("artist")),
                    track = c.getString(c.getColumnIndexOrThrow("track")),
                    album = c.getStringOrNull("album"),
                    durationSec = c.getIntOrNull("duration_sec"),
                    timestampSec = c.getLong(c.getColumnIndexOrThrow("timestamp_sec")),
                    attempts = c.getInt(c.getColumnIndexOrThrow("attempts")),
                    lastError = c.getStringOrNull("last_error"),
                    nextAttemptAt = c.getLong(c.getColumnIndexOrThrow("next_attempt_at")),
                    createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"))
                )
            )
        }
        return out
    }

    fun queueSize(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE_SCROBBLE_QUEUE", null).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }

    fun removeScrobbles(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (id in ids) db.delete(TABLE_SCROBBLE_QUEUE, "id = ?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Marks a failed attempt: bumps attempts, records the error, schedules the next retry. */
    fun deferScrobbles(ids: Collection<Long>, error: String, nextAttemptAt: Long) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (id in ids) {
                db.execSQL(
                    "UPDATE $TABLE_SCROBBLE_QUEUE SET attempts = attempts + 1, last_error = ?, next_attempt_at = ? WHERE id = ?",
                    arrayOf(error, nextAttemptAt, id)
                )
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Last.fm silently drops scrobbles older than ~14 days — purge anything that old so the
     *  queue can't grow unbounded while the device is offline for a month. Returns rows removed. */
    fun purgeStaleScrobbles(nowSec: Long, maxAgeSec: Long = 14L * 86_400L): Int =
        writableDatabase.delete(TABLE_SCROBBLE_QUEUE, "timestamp_sec < ?", arrayOf((nowSec - maxAgeSec).toString()))

    fun clearScrobbleQueue(): Int = writableDatabase.delete(TABLE_SCROBBLE_QUEUE, null, null)

    // ------------------------------------------------------------------ helpers

    private fun ContentValues.putNullable(key: String, v: Long?) { if (v == null) putNull(key) else put(key, v) }
    private fun ContentValues.putNullable(key: String, v: Int?) { if (v == null) putNull(key) else put(key, v) }
    private fun ContentValues.putNullable(key: String, v: Double?) { if (v == null) putNull(key) else put(key, v) }

    private fun Cursor.getStringOrNull(col: String): String? {
        val i = getColumnIndex(col); return if (i < 0 || isNull(i)) null else getString(i)
    }
    private fun Cursor.getLongOrNull(col: String): Long? {
        val i = getColumnIndex(col); return if (i < 0 || isNull(i)) null else getLong(i)
    }
    private fun Cursor.getIntOrNull(col: String): Int? {
        val i = getColumnIndex(col); return if (i < 0 || isNull(i)) null else getInt(i)
    }
    private fun Cursor.getDoubleOrNull(col: String): Double? {
        val i = getColumnIndex(col); return if (i < 0 || isNull(i)) null else getDouble(i)
    }
}
