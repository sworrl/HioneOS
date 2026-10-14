package com.hione.player.wanted

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Songs liked on the FM radio. One row per song, de-duplicated by the FM app's like id and by a
 * normalized title+artist key (see [WantedMatch.key]).
 *
 * status:
 *   WANTED      liked on the radio, not in the library: the shopping list
 *   IN_LIBRARY  found in the library (matched_track_id) and added to Liked Songs
 *   ACQUIRED    the user says they got it; flips to IN_LIBRARY once a scan finds it
 *   DISMISSED   the user does not want it any more; never matched, never auto-liked
 *
 * auto_liked is 1 when THIS row is what put matched_track_id into Liked Songs, so un-liking the
 * song on the radio can take that like back without touching likes the user made on their own.
 *
 * Deleted ids go to wanted_tombstone so the FM app's "resend everything" cannot bring them back.
 */
class WantedDb private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "hione_wanted.db"
        private const val DB_VERSION = 1

        @Volatile private var instance: WantedDb? = null
        fun get(ctx: Context): WantedDb =
            instance ?: synchronized(this) { instance ?: WantedDb(ctx).also { instance = it } }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE wanted (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                artist TEXT NOT NULL,
                album TEXT,
                cover_url TEXT,
                web_url TEXT,
                source TEXT,
                station TEXT,
                freq_khz INTEGER NOT NULL DEFAULT 0,
                heard_at_ms INTEGER NOT NULL DEFAULT 0,
                lat REAL,
                lon REAL,
                place_name TEXT,
                status TEXT NOT NULL DEFAULT 'WANTED',
                added_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                matched_track_id INTEGER,
                auto_liked INTEGER NOT NULL DEFAULT 0,
                heard_count INTEGER NOT NULL DEFAULT 1,
                norm_key TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_wanted_norm ON wanted(norm_key)")
        db.execSQL("CREATE INDEX idx_wanted_status ON wanted(status)")
        db.execSQL("CREATE TABLE wanted_tombstone (id TEXT PRIMARY KEY, deleted_at INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 is the first schema. Future versions add ALTERs here; never drop user data.
    }

    // ------------------------------------------------------------------ reads

    fun all(): List<WantedItem> = readableDatabase.rawQuery(
        "SELECT * FROM wanted ORDER BY heard_at_ms DESC, added_at DESC", null
    ).use { c -> buildList { while (c.moveToNext()) add(c.toItem()) } }

    fun byId(id: String): WantedItem? = readableDatabase.rawQuery(
        "SELECT * FROM wanted WHERE id = ?", arrayOf(id)
    ).use { c -> if (c.moveToFirst()) c.toItem() else null }

    fun byNormKey(key: String): WantedItem? = readableDatabase.rawQuery(
        "SELECT * FROM wanted WHERE norm_key = ? LIMIT 1", arrayOf(key)
    ).use { c -> if (c.moveToFirst()) c.toItem() else null }

    fun isTombstoned(id: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM wanted_tombstone WHERE id = ?", arrayOf(id)
    ).use { it.moveToFirst() }

    // ------------------------------------------------------------------ writes

    fun insert(item: WantedItem) {
        writableDatabase.insertWithOnConflict("wanted", null, item.toValues(), SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun update(id: String, values: ContentValues) {
        values.put("updated_at", System.currentTimeMillis())
        writableDatabase.update("wanted", values, "id = ?", arrayOf(id))
    }

    fun delete(id: String, tombstone: Boolean) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("wanted", "id = ?", arrayOf(id))
            if (tombstone) {
                db.insertWithOnConflict("wanted_tombstone", null, ContentValues().apply {
                    put("id", id); put("deleted_at", System.currentTimeMillis())
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun Cursor.str(col: String): String? = getColumnIndex(col).let { if (it < 0 || isNull(it)) null else getString(it) }
    private fun Cursor.lng(col: String): Long? = getColumnIndex(col).let { if (it < 0 || isNull(it)) null else getLong(it) }
    private fun Cursor.dbl(col: String): Double? = getColumnIndex(col).let { if (it < 0 || isNull(it)) null else getDouble(it) }

    private fun Cursor.toItem() = WantedItem(
        id = str("id") ?: "",
        title = str("title") ?: "",
        artist = str("artist") ?: "",
        album = str("album"),
        coverUrl = str("cover_url"),
        webUrl = str("web_url"),
        source = str("source") ?: "",
        station = str("station") ?: "",
        freqKHz = (lng("freq_khz") ?: 0L).toInt(),
        heardAtMs = lng("heard_at_ms") ?: 0L,
        lat = dbl("lat"),
        lon = dbl("lon"),
        placeName = str("place_name"),
        status = WantedStatus.parse(str("status")),
        addedAt = lng("added_at") ?: 0L,
        updatedAt = lng("updated_at") ?: 0L,
        matchedTrackId = lng("matched_track_id"),
        autoLiked = (lng("auto_liked") ?: 0L) != 0L,
        heardCount = (lng("heard_count") ?: 1L).toInt(),
        normKey = str("norm_key") ?: "",
    )

    private fun WantedItem.toValues() = ContentValues().apply {
        put("id", id); put("title", title); put("artist", artist)
        put("album", album); put("cover_url", coverUrl); put("web_url", webUrl)
        put("source", source); put("station", station); put("freq_khz", freqKHz)
        put("heard_at_ms", heardAtMs)
        if (lat != null) put("lat", lat) else putNull("lat")
        if (lon != null) put("lon", lon) else putNull("lon")
        put("place_name", placeName); put("status", status.name)
        put("added_at", addedAt); put("updated_at", updatedAt)
        if (matchedTrackId != null) put("matched_track_id", matchedTrackId) else putNull("matched_track_id")
        put("auto_liked", if (autoLiked) 1 else 0); put("heard_count", heardCount)
        put("norm_key", normKey)
    }
}

enum class WantedStatus(val label: String) {
    WANTED("Wanted"), IN_LIBRARY("In library"), ACQUIRED("Acquired"), DISMISSED("Dismissed");

    companion object {
        fun parse(s: String?): WantedStatus = values().firstOrNull { it.name == s } ?: WANTED
    }
}

data class WantedItem(
    val id: String,
    val title: String,
    val artist: String,
    val album: String?,
    val coverUrl: String?,
    val webUrl: String?,
    val source: String,
    val station: String,
    val freqKHz: Int,
    val heardAtMs: Long,
    val lat: Double?,
    val lon: Double?,
    val placeName: String?,
    val status: WantedStatus,
    val addedAt: Long,
    val updatedAt: Long,
    val matchedTrackId: Long?,
    val autoLiked: Boolean,
    val heardCount: Int,
    val normKey: String,
)
