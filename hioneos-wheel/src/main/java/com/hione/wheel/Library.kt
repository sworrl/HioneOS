package com.hione.wheel

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.util.LruCache
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.util.concurrent.Executors

/**
 * The music library as the menus see it: one MediaStore query, grouped the way the old
 * Browse menus were (Artists, Albums, Songs, Genres, Composers). Track ids are MediaStore ids,
 * which are also Hione Music's media ids, so a list built here plays through Hione Music as is.
 */
class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val year: Int,
    val track: Int,
    val genre: String,
    val composer: String,
)

class Album(val id: Long, val title: String, val artist: String, val year: Int, val songs: List<Song>)

class Group(val name: String, val songs: List<Song>)

class Library(
    val songs: List<Song>,
    val albums: List<Album>,
    val artists: List<Group>,
    val genres: List<Group>,
    val composers: List<Group>,
) {
    private val byId = HashMap<Long, Song>(songs.size * 2).also { m -> for (s in songs) m[s.id] = s }
    fun song(id: Long): Song? = byId[id]

    /** Albums of one artist, in year order. */
    fun albumsOf(group: Group): List<Album> {
        val ids = LinkedHashSet<Long>()
        for (s in group.songs) ids.add(s.albumId)
        return albums.filter { it.id in ids }.sortedWith(compareBy({ it.year }, { sortKey(it.title) }))
    }

    companion object {
        val EMPTY = Library(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        private const val TAG = "HionePodLibrary"

        /** Sort the way the later players did: case-blind, a leading "The " ignored. */
        fun sortKey(s: String): String {
            val t = s.trim().lowercase()
            return if (t.startsWith("the ")) t.substring(4) else t
        }

        fun load(ctx: Context): Library {
            val out = ArrayList<Song>()
            val hasGenre = Build.VERSION.SDK_INT >= 30
            val proj = buildList {
                add(MediaStore.Audio.Media._ID)
                add(MediaStore.Audio.Media.TITLE)
                add(MediaStore.Audio.Media.ARTIST)
                add(MediaStore.Audio.Media.ALBUM)
                add(MediaStore.Audio.Media.ALBUM_ID)
                add(MediaStore.Audio.Media.DURATION)
                add(MediaStore.Audio.Media.YEAR)
                add(MediaStore.Audio.Media.TRACK)
                add(MediaStore.Audio.Media.COMPOSER)
                if (hasGenre) add("genre")
            }.toTypedArray()
            try {
                ctx.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj,
                    "${MediaStore.Audio.Media.IS_MUSIC}=1", null, null
                )?.use { c ->
                    val iG = if (hasGenre) c.getColumnIndex("genre") else -1
                    // Strings repeat a lot (17k tracks, a few thousand artists); share them.
                    val pool = HashMap<String, String>()
                    fun p(v: String?): String { val k = v ?: ""; return pool.getOrPut(k) { k } }
                    while (c.moveToNext()) {
                        val rawTrack = c.getInt(7)
                        out.add(
                            Song(
                                id = c.getLong(0),
                                title = c.getString(1)?.takeIf { it.isNotBlank() } ?: "Untitled",
                                artist = p(c.getString(2)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Unknown Artist"),
                                album = p(c.getString(3)?.takeIf { it.isNotBlank() } ?: "Unknown Album"),
                                albumId = c.getLong(4),
                                durationMs = c.getLong(5),
                                year = c.getInt(6),
                                track = if (rawTrack > 1000) rawTrack % 1000 else rawTrack,
                                genre = p(if (iG >= 0) c.getString(iG) else null),
                                composer = p(c.getString(8)),
                            )
                        )
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "MediaStore query failed", t)
            }
            out.sortBy { sortKey(it.title) }

            val albumMap = LinkedHashMap<Long, ArrayList<Song>>()
            for (s in out) albumMap.getOrPut(s.albumId) { ArrayList() }.add(s)
            val albums = albumMap.map { (id, list) ->
                list.sortWith(compareBy({ it.track }, { sortKey(it.title) }))
                val first = list[0]
                val artists = list.mapTo(HashSet()) { it.artist }
                Album(id, first.album, if (artists.size > 1) "Various Artists" else first.artist,
                    list.maxOf { it.year }, list)
            }.sortedBy { sortKey(it.title) }

            fun group(key: (Song) -> String): List<Group> {
                val m = HashMap<String, ArrayList<Song>>()
                for (s in out) { val k = key(s); if (k.isNotBlank()) m.getOrPut(k) { ArrayList() }.add(s) }
                return m.map { (k, v) -> Group(k, v) }.sortedBy { sortKey(it.name) }
            }
            return Library(out, albums, group { it.artist }, group { it.genre }, group { it.composer })
        }
    }
}

/**
 * Album art, small and cached. Loads off the main thread and calls [onLoaded] on the main thread
 * so the screen can redraw. Each bitmap is kept with its Compose wrapper so Cover Flow never
 * allocates one per frame.
 */
class ArtCache(private val ctx: Context, private val onLoaded: () -> Unit) {
    class Art(val bitmap: Bitmap, val image: ImageBitmap)

    private val cache = object : LruCache<Long, Art>(18 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: Art) = value.bitmap.byteCount
    }
    private val missing = HashSet<Long>()
    private val pending = HashSet<Long>()
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Returns the art if cached, else null and starts a load. Main thread only. */
    fun get(albumId: Long): Art? {
        if (albumId <= 0) return null
        cache.get(albumId)?.let { return it }
        if (albumId in missing || albumId in pending) return null
        pending.add(albumId)
        exec.execute {
            val bmp = loadBitmap(albumId)
            main.post {
                pending.remove(albumId)
                if (bmp == null) missing.add(albumId) else cache.put(albumId, Art(bmp, bmp.asImageBitmap()))
                onLoaded()
            }
        }
        return null
    }

    fun forget() { cache.evictAll(); missing.clear() }

    private fun loadBitmap(albumId: Long): Bitmap? {
        val px = 240
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, albumId)
                return ctx.contentResolver.loadThumbnail(uri, Size(px, px), null).let(::square)
            } catch (_: Throwable) { }
        }
        return try {
            val uri = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }?.let(::square)
        } catch (_: Throwable) { null }
    }

    /** Center-crop to a square at most 240 px, ARGB so it can be drawn anywhere. */
    private fun square(input: Bitmap): Bitmap {
        // The screens draw into a software canvas, which cannot take a HARDWARE bitmap.
        val src = if (Build.VERSION.SDK_INT >= 26 && input.config == Bitmap.Config.HARDWARE)
            input.copy(Bitmap.Config.ARGB_8888, false) else input
        val side = minOf(src.width, src.height)
        val x = (src.width - side) / 2
        val y = (src.height - side) / 2
        val target = minOf(side, 240)
        val cropped = Bitmap.createBitmap(src, x, y, side, side)
        val scaled = if (side == target) cropped else Bitmap.createScaledBitmap(cropped, target, target, true)
        return if (scaled.config == Bitmap.Config.ARGB_8888) scaled else scaled.copy(Bitmap.Config.ARGB_8888, false)
            ?: scaled
    }
}
