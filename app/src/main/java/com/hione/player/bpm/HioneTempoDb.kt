package com.hione.player.bpm

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

/**
 * Per-file tempo cache. One row per analysed file (or disc-image clip), keyed by path plus the
 * clip start, and valid only while the file's size and mtime still match: a re-encode, a re-tag
 * or a replaced file is analysed again.
 *
 * Rows with bpm = 0 are kept too. They record "analysed, no steady tempo found", so the library
 * job does not decode the same ambient track every night.
 */
class HioneTempoDb private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, DB_NAME, null, DB_VERSION) {

    data class Row(
        val path: String,
        val clipStartMs: Long,
        val size: Long,
        val mtime: Long,
        /** Folded display tempo, 0 = none found. */
        val bpm: Float,
        val rawBpm: Float,
        val confidence: Float,
        /** First grid beat in ms from the start of the file or clip, -1 when unknown (tag source). */
        val firstBeatMs: Double,
        val source: String,
        val analyzerVersion: Int,
        val analyzedAt: Long,
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE tempo (
                path TEXT NOT NULL,
                clip_start_ms INTEGER NOT NULL DEFAULT 0,
                size INTEGER NOT NULL,
                mtime INTEGER NOT NULL,
                bpm REAL NOT NULL,
                raw_bpm REAL NOT NULL,
                confidence REAL NOT NULL,
                first_beat_ms REAL NOT NULL,
                source TEXT NOT NULL,
                analyzer_version INTEGER NOT NULL,
                analyzed_at INTEGER NOT NULL,
                PRIMARY KEY (path, clip_start_ms))"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS tempo")
        onCreate(db)
    }

    /** The cached row if it still describes the file on disk and came from the current analyzer. */
    fun lookup(path: String, clipStartMs: Long = 0L): Row? {
        val f = File(path)
        val size = f.length()
        val mtime = f.lastModified()
        if (size <= 0L) return null
        val row = runCatching {
            readableDatabase.query(
                "tempo", null, "path = ? AND clip_start_ms = ?", arrayOf(path, clipStartMs.toString()),
                null, null, null
            ).use { c ->
                if (!c.moveToFirst()) null else Row(
                    path = c.getString(c.getColumnIndexOrThrow("path")),
                    clipStartMs = c.getLong(c.getColumnIndexOrThrow("clip_start_ms")),
                    size = c.getLong(c.getColumnIndexOrThrow("size")),
                    mtime = c.getLong(c.getColumnIndexOrThrow("mtime")),
                    bpm = c.getFloat(c.getColumnIndexOrThrow("bpm")),
                    rawBpm = c.getFloat(c.getColumnIndexOrThrow("raw_bpm")),
                    confidence = c.getFloat(c.getColumnIndexOrThrow("confidence")),
                    firstBeatMs = c.getDouble(c.getColumnIndexOrThrow("first_beat_ms")),
                    source = c.getString(c.getColumnIndexOrThrow("source")),
                    analyzerVersion = c.getInt(c.getColumnIndexOrThrow("analyzer_version")),
                    analyzedAt = c.getLong(c.getColumnIndexOrThrow("analyzed_at")),
                )
            }
        }.getOrNull() ?: return null
        if (row.size != size || row.mtime != mtime) return null
        if (row.analyzerVersion != HioneTempoAnalyzer.VERSION) return null
        return row
    }

    fun store(row: Row) {
        val v = ContentValues().apply {
            put("path", row.path)
            put("clip_start_ms", row.clipStartMs)
            put("size", row.size)
            put("mtime", row.mtime)
            put("bpm", row.bpm)
            put("raw_bpm", row.rawBpm)
            put("confidence", row.confidence)
            put("first_beat_ms", row.firstBeatMs)
            put("source", row.source)
            put("analyzer_version", row.analyzerVersion)
            put("analyzed_at", row.analyzedAt)
        }
        runCatching { writableDatabase.insertWithOnConflict("tempo", null, v, SQLiteDatabase.CONFLICT_REPLACE) }
    }

    /** Paths among [paths] that have no current row, in the order given. */
    fun missing(paths: List<String>): List<String> = paths.filter { lookup(it) == null }

    /** path -> whole-number tempo for every whole-file row at or above [minConfidence]. No file checks. */
    fun allShown(minConfidence: Float): Map<String, Int> = runCatching {
        val out = HashMap<String, Int>()
        readableDatabase.rawQuery(
            "SELECT path, bpm FROM tempo WHERE clip_start_ms = 0 AND bpm > 0 AND confidence >= ?",
            arrayOf(minConfidence.toString())
        ).use { c -> while (c.moveToNext()) out[c.getString(0)] = Math.round(c.getFloat(1)) }
        out as Map<String, Int>
    }.getOrDefault(emptyMap())

    fun count(): Long = runCatching {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM tempo", null).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
    }.getOrDefault(0L)

    companion object {
        private const val DB_NAME = "hione_tempo.db"
        private const val DB_VERSION = 1

        @Volatile private var instance: HioneTempoDb? = null

        fun get(ctx: Context): HioneTempoDb =
            instance ?: synchronized(this) { instance ?: HioneTempoDb(ctx).also { instance = it } }
    }
}
