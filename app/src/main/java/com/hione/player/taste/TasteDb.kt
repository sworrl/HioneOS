package com.hione.player.taste

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Calendar
import java.util.concurrent.Executors

/**
 * On-device SQLite store behind the taste engine. Everything here is derived from what the
 * user actually did on this device — there is no network, no remote "music DNA" — and every
 * row is something a "why this?" explanation can point back at:
 *
 *  - `listens`  one row per play that ended (track transition / queue end): how much of it was
 *               heard, whether it was skipped, the wall-clock hour/day it happened, and a
 *               session id so plays that happened together can be related.
 *  - `cooc`     the session co-occurrence graph: (a,b) pairs of tracks heard close together in
 *               the same session, weighted by how close. Feeds "people-who-played-this-also-
 *               played" style similarity — except the only "people" is the owner.
 *  - `genres`   cache of MediaStore's genre tag per track (real file metadata, see GenreIndex).
 *  - `meta`     small key/value bookkeeping (genre index build time, library size, etc).
 *
 * Writes go through a single-thread executor so the player listener that feeds this never
 * blocks the main thread on disk; reads happen on whatever background dispatcher the caller
 * uses (the model builds off Dispatchers.Default).
 */
class TasteDb private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "hione_taste.db"
        private const val DB_VERSION = 1
        /** Session boundary: a gap this long between one listen ending and the next starting. */
        const val SESSION_GAP_MS = 20L * 60_000L
        /** Co-occurrence window: how many previous listens in the session a new listen links to. */
        const val COOC_WINDOW = 5
        /** A listen below this fraction, ended by the user, counts as a skip. */
        const val SKIP_FRACTION = 0.5f
        /** A listen at/above this fraction counts as "played through". */
        const val COMPLETE_FRACTION = 0.9f

        @Volatile private var instance: TasteDb? = null
        fun get(ctx: Context): TasteDb = instance ?: synchronized(this) {
            instance ?: TasteDb(ctx).also { instance = it }
        }

        val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "hione-taste-db").apply { isDaemon = true } }

        /** Six coarse day-part slots used by the time-of-day profile (finer than AM/PM, coarser
         *  than 24 hours so a few weeks of listening is enough to say something). */
        fun slotOf(hour: Int): Int = when (hour) {
            in 0..5 -> 0     // late night
            in 6..9 -> 1     // morning
            in 10..13 -> 2   // midday
            in 14..17 -> 3   // afternoon
            in 18..21 -> 4   // evening
            else -> 5        // night
        }
        val SLOT_NAMES = listOf("late night", "morning", "midday", "afternoon", "evening", "night")
        fun currentSlot(): Int = slotOf(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
        fun currentDow(): Int = Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1   // 0=Sunday
    }

    data class Listen(
        val trackId: Long,
        val startedAt: Long,
        val endedAt: Long,
        val fraction: Float,
        val listenedMs: Long,
        val skipped: Boolean,
        val auto: Boolean,          // ended by reaching the end (vs user next / queue change)
        val sessionId: Long,
        val hour: Int,
        val dow: Int
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE listens (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                track_id INTEGER NOT NULL,
                started_at INTEGER NOT NULL,
                ended_at INTEGER NOT NULL,
                fraction REAL NOT NULL,
                listened_ms INTEGER NOT NULL,
                skipped INTEGER NOT NULL,
                auto INTEGER NOT NULL,
                session_id INTEGER NOT NULL,
                hour INTEGER NOT NULL,
                dow INTEGER NOT NULL,
                slot INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX idx_listens_track ON listens(track_id)")
        db.execSQL("CREATE INDEX idx_listens_session ON listens(session_id)")
        db.execSQL("CREATE INDEX idx_listens_ended ON listens(ended_at)")
        db.execSQL(
            """CREATE TABLE cooc (
                a INTEGER NOT NULL,
                b INTEGER NOT NULL,
                weight REAL NOT NULL,
                last_at INTEGER NOT NULL,
                PRIMARY KEY (a, b)
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX idx_cooc_b ON cooc(b)")
        db.execSQL("CREATE TABLE genres (track_id INTEGER PRIMARY KEY, genre TEXT NOT NULL)")
        db.execSQL("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 only so far — future schema changes migrate here (never drop the listens log).
    }

    // ---- meta -------------------------------------------------------------------------------

    fun getMeta(k: String): String? = runCatching {
        readableDatabase.rawQuery("SELECT v FROM meta WHERE k=?", arrayOf(k)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    fun putMeta(k: String, v: String) {
        runCatching {
            writableDatabase.execSQL("INSERT OR REPLACE INTO meta(k, v) VALUES(?, ?)", arrayOf(k, v))
        }
    }

    // ---- listens ----------------------------------------------------------------------------

    /** Inserts a listen and links it into the co-occurrence graph with the previous listens of
     *  the same session. Synchronous — call from [writer]. */
    fun insertListen(l: Listen) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val cv = ContentValues().apply {
                put("track_id", l.trackId)
                put("started_at", l.startedAt)
                put("ended_at", l.endedAt)
                put("fraction", l.fraction)
                put("listened_ms", l.listenedMs)
                put("skipped", if (l.skipped) 1 else 0)
                put("auto", if (l.auto) 1 else 0)
                put("session_id", l.sessionId)
                put("hour", l.hour)
                put("dow", l.dow)
                put("slot", slotOf(l.hour))
            }
            db.insert("listens", null, cv)
            if (!l.skipped && l.fraction >= SKIP_FRACTION) {
                // Link to the last COOC_WINDOW non-skipped listens of this session (excluding
                // this track itself). Closer neighbours weigh more: 1, 1/2, 1/3 ...
                db.rawQuery(
                    "SELECT track_id FROM listens WHERE session_id=? AND skipped=0 AND track_id<>? ORDER BY id DESC LIMIT ?",
                    arrayOf(l.sessionId.toString(), l.trackId.toString(), COOC_WINDOW.toString())
                ).use { c ->
                    var dist = 1
                    val seen = HashSet<Long>()
                    while (c.moveToNext()) {
                        val other = c.getLong(0)
                        if (!seen.add(other)) { dist++; continue }
                        val a = minOf(other, l.trackId); val b = maxOf(other, l.trackId)
                        val w = 1f / dist
                        // Two-step upsert rather than ON CONFLICT DO UPDATE: minSdk 26 ships
                        // SQLite 3.18, which predates the UPSERT clause.
                        val updated = db.compileStatement("UPDATE cooc SET weight = weight + ?, last_at = ? WHERE a=? AND b=?").let { st ->
                            st.bindDouble(1, w.toDouble()); st.bindLong(2, l.endedAt); st.bindLong(3, a); st.bindLong(4, b)
                            st.executeUpdateDelete()
                        }
                        if (updated == 0) {
                            db.execSQL("INSERT INTO cooc(a, b, weight, last_at) VALUES(?, ?, ?, ?)", arrayOf(a, b, w, l.endedAt))
                        }
                        dist++
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** (endedAt, sessionId) of the most recent listen — used to decide session continuity. */
    fun lastListenEnd(): Pair<Long, Long>? = runCatching {
        readableDatabase.rawQuery("SELECT ended_at, session_id FROM listens ORDER BY id DESC LIMIT 1", null).use { c ->
            if (c.moveToFirst()) c.getLong(0) to c.getLong(1) else null
        }
    }.getOrNull()

    fun listenCount(): Int = runCatching {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM listens", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }.getOrDefault(0)

    /** Per-track aggregate of the listen log. */
    data class TrackAgg(
        var listens: Int = 0,
        var completed: Int = 0,
        var skips: Int = 0,
        var fractionSum: Float = 0f,
        var listenedMs: Long = 0L,
        var lastEnd: Long = 0L,
        var recent30: Int = 0,           // completed listens in the last 30 days
        val slotCounts: IntArray = IntArray(6)
    )

    fun trackAggregates(nowMs: Long): HashMap<Long, TrackAgg> {
        val out = HashMap<Long, TrackAgg>()
        runCatching {
            val db = readableDatabase
            db.rawQuery(
                "SELECT track_id, COUNT(*), SUM(CASE WHEN fraction>=$COMPLETE_FRACTION THEN 1 ELSE 0 END), " +
                    "SUM(skipped), SUM(fraction), SUM(listened_ms), MAX(ended_at), " +
                    "SUM(CASE WHEN fraction>=$COMPLETE_FRACTION AND ended_at>=? THEN 1 ELSE 0 END) " +
                    "FROM listens GROUP BY track_id",
                arrayOf((nowMs - 30L * 86_400_000L).toString())
            ).use { c ->
                while (c.moveToNext()) {
                    val a = TrackAgg(
                        listens = c.getInt(1), completed = c.getInt(2), skips = c.getInt(3),
                        fractionSum = c.getFloat(4), listenedMs = c.getLong(5), lastEnd = c.getLong(6),
                        recent30 = c.getInt(7)
                    )
                    out[c.getLong(0)] = a
                }
            }
            db.rawQuery("SELECT track_id, slot, COUNT(*) FROM listens WHERE skipped=0 GROUP BY track_id, slot", null).use { c ->
                while (c.moveToNext()) {
                    val a = out[c.getLong(0)] ?: continue
                    val s = c.getInt(1)
                    if (s in 0..5) a.slotCounts[s] += c.getInt(2)
                }
            }
        }
        return out
    }

    /** 7x24 grid of listened milliseconds (dow x hour) for the heatmap. */
    fun hourHeatmap(): Array<LongArray> {
        val grid = Array(7) { LongArray(24) }
        runCatching {
            readableDatabase.rawQuery("SELECT dow, hour, SUM(listened_ms) FROM listens GROUP BY dow, hour", null).use { c ->
                while (c.moveToNext()) {
                    val d = c.getInt(0); val h = c.getInt(1)
                    if (d in 0..6 && h in 0..23) grid[d][h] = c.getLong(2)
                }
            }
        }
        return grid
    }

    /** Total listens per day-part slot (for the time-of-day lift baseline). */
    fun slotTotals(): IntArray {
        val out = IntArray(6)
        runCatching {
            readableDatabase.rawQuery("SELECT slot, COUNT(*) FROM listens WHERE skipped=0 GROUP BY slot", null).use { c ->
                while (c.moveToNext()) { val s = c.getInt(0); if (s in 0..5) out[s] = c.getInt(1) }
            }
        }
        return out
    }

    /** Track ids listened to (not skipped) in the last [windowMs], most recent first, distinct. */
    fun recentTrackIds(nowMs: Long, windowMs: Long, limit: Int): List<Long> {
        val out = ArrayList<Long>()
        val seen = HashSet<Long>()
        runCatching {
            readableDatabase.rawQuery(
                "SELECT track_id FROM listens WHERE ended_at>=? ORDER BY id DESC LIMIT ?",
                arrayOf((nowMs - windowMs).toString(), (limit * 3).toString())
            ).use { c ->
                while (c.moveToNext() && out.size < limit) { val id = c.getLong(0); if (seen.add(id)) out.add(id) }
            }
        }
        return out
    }

    // ---- co-occurrence ----------------------------------------------------------------------

    /** Neighbours of [trackId] in the co-occurrence graph: other track → accumulated weight. */
    fun neighbours(trackId: Long, limit: Int = 200): Map<Long, Float> {
        val out = HashMap<Long, Float>()
        runCatching {
            readableDatabase.rawQuery(
                "SELECT a, b, weight FROM cooc WHERE a=? OR b=? ORDER BY weight DESC LIMIT ?",
                arrayOf(trackId.toString(), trackId.toString(), limit.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    val a = c.getLong(0); val b = c.getLong(1)
                    out[if (a == trackId) b else a] = c.getFloat(2)
                }
            }
        }
        return out
    }

    fun coocEdgeCount(): Int = runCatching {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM cooc", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }.getOrDefault(0)

    // ---- genres cache -----------------------------------------------------------------------

    fun loadGenres(): HashMap<Long, String> {
        val out = HashMap<Long, String>()
        runCatching {
            readableDatabase.rawQuery("SELECT track_id, genre FROM genres", null).use { c ->
                while (c.moveToNext()) out[c.getLong(0)] = c.getString(1)
            }
        }
        return out
    }

    fun replaceGenres(map: Map<Long, String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("genres", null, null)
            val st = db.compileStatement("INSERT INTO genres(track_id, genre) VALUES(?, ?)")
            for ((id, g) in map) { st.bindLong(1, id); st.bindString(2, g); st.executeInsert() }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
