package com.hione.player.taste

import android.content.Context
import android.provider.MediaStore
import com.hione.player.Track
import com.hione.player.safeQuery
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Track → genre, from the genre tag MediaStore indexed off the file itself (ID3 TCON / Vorbis
 * GENRE). This is REAL metadata the user's files carry — nothing inferred, nothing looked up
 * online. Tracks with no tag simply have no genre and contribute nothing to genre features.
 *
 * Built once per day (or when the library size changes) off the main thread via
 * MediaStore.Audio.Genres + Genres.Members, then cached in TasteDb so app start is one small
 * table read rather than N content-provider queries.
 */
object GenreIndex {
    private const val META_BUILT_AT = "genre_built_at"
    private const val META_BUILT_SIZE = "genre_built_size"
    private const val REBUILD_AFTER_MS = 24L * 3_600_000L

    @Volatile private var map: HashMap<Long, String>? = null
    private val building = AtomicBoolean(false)

    /** Genre for a track ("" if untagged/unknown). Virtual cue-split tracks inherit the image's. */
    fun genreOf(ctx: Context, track: Track): String {
        val m = map ?: loadCached(ctx)
        val id = if (track.parentId != 0L) track.parentId else track.id
        return m[id] ?: ""
    }

    fun genreOf(ctx: Context, trackId: Long): String = (map ?: loadCached(ctx))[trackId] ?: ""

    /** Normalised genre key for grouping ("Alt Rock" / "alt-rock" collapse together). */
    fun key(genre: String): String = genre.trim().lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun loadCached(ctx: Context): HashMap<Long, String> {
        synchronized(this) {
            map?.let { return it }
            val m = TasteDb.get(ctx).loadGenres()
            map = m
            return m
        }
    }

    /** Ensure the index is fresh for [tracks]. Cheap when nothing changed; the rebuild itself
     *  runs on the calling (background) thread. Call from Dispatchers.IO/Default only. */
    fun ensureFresh(ctx: Context, tracks: List<Track>) {
        val db = TasteDb.get(ctx)
        val builtAt = db.getMeta(META_BUILT_AT)?.toLongOrNull() ?: 0L
        val builtSize = db.getMeta(META_BUILT_SIZE)?.toIntOrNull() ?: -1
        val now = System.currentTimeMillis()
        val fresh = builtSize == tracks.size && now - builtAt < REBUILD_AFTER_MS
        if (fresh && (map ?: loadCached(ctx)).isNotEmpty()) return
        if (fresh && builtSize >= 0 && tracks.isNotEmpty() && builtAt > 0L) return   // built, genuinely empty (no tags)
        if (!building.compareAndSet(false, true)) return
        try {
            val built = build(ctx) ?: return
            db.replaceGenres(built)
            db.putMeta(META_BUILT_AT, now.toString())
            db.putMeta(META_BUILT_SIZE, tracks.size.toString())
            synchronized(this) { map = HashMap(built) }
        } finally {
            building.set(false)
        }
    }

    /** Null if MediaStore isn't available yet (early boot) — caller keeps the old cache. */
    private fun build(ctx: Context): HashMap<Long, String>? {
        val cr = ctx.contentResolver
        val out = HashMap<Long, String>()
        val genres = ArrayList<Pair<Long, String>>()
        cr.safeQuery(
            MediaStore.Audio.Genres.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Genres._ID, MediaStore.Audio.Genres.NAME),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1)?.trim().orEmpty()
                if (name.isBlank() || name.equals("<unknown>", true)) continue
                genres.add(c.getLong(0) to name)
            }
        } ?: return null
        for ((gid, name) in genres) {
            cr.safeQuery(
                MediaStore.Audio.Genres.Members.getContentUri("external", gid),
                arrayOf(MediaStore.Audio.Genres.Members.AUDIO_ID),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    // First tag wins if a file is listed under several genres.
                    if (!out.containsKey(id)) out[id] = name
                }
            }
        }
        return out
    }
}
