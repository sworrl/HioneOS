package com.hione.riot

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

data class Song(
    val id: Long, val title: String, val artist: String, val album: String, val year: Int,
    val track: Int, val dateAdded: Long, val durationMs: Long, val size: Long, val mime: String,
    val bitrate: Int
)

class Album(val name: String, val artist: String, val songs: List<Song>)
class Artist(val name: String, val albums: List<Album>, val songs: List<Song>)
class Genre(val id: Long, val name: String)
class Playlist(var name: String, val ids: MutableList<Long>)

/**
 * The library as the Riot saw it: everything sorted by tag, Albums / Artists / Songs reached by
 * first letter, plus genres. Read from MediaStore, whose ids are the same ids Hione Music plays,
 * so a pick here is handed straight to Hione Music by id.
 */
class RiotLibrary(private val ctx: Context, private val prefs: RiotPrefs) {
    @Volatile var songs: List<Song> = emptyList(); private set
    @Volatile var albums: List<Album> = emptyList(); private set
    @Volatile var artists: List<Artist> = emptyList(); private set
    @Volatile var loaded = false; private set
    @Volatile var error: String? = null; private set
    private var byId: Map<Long, Song> = emptyMap()

    fun song(id: Long) = byId[id]

    /** "The Walls" becomes "Walls, The" when the "The" Filter is on, and sorts under W. */
    fun display(name: String): String =
        if (prefs.theFilter && name.length > 4 && name.startsWith("The ", ignoreCase = true)) name.substring(4) + ", The" else name

    fun letterOf(name: String): Char {
        val c = display(name).trimStart().firstOrNull()?.uppercaseChar() ?: '#'
        return if (c in 'A'..'Z') c else '#'
    }

    private fun key(name: String) = display(name).lowercase()

    fun load() {
        val out = ArrayList<Song>()
        try {
            val cols = arrayListOf(
                MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.YEAR, MediaStore.Audio.Media.TRACK,
                MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.MIME_TYPE
            )
            if (Build.VERSION.SDK_INT >= 30) cols.add(MediaStore.Audio.Media.BITRATE)
            ctx.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols.toTypedArray(),
                MediaStore.Audio.Media.IS_MUSIC + " != 0", null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val artist = c.getString(2)?.takeUnless { it.isBlank() || it == "<unknown>" } ?: "Unknown Artist"
                    out.add(Song(
                        id = c.getLong(0), title = c.getString(1) ?: "Untitled", artist = artist,
                        album = c.getString(3)?.takeUnless { it.isBlank() } ?: "Unknown Album",
                        year = c.getInt(4), track = c.getInt(5) % 1000, dateAdded = c.getLong(6),
                        durationMs = c.getLong(7), size = c.getLong(8), mime = c.getString(9) ?: "",
                        bitrate = if (cols.size > 10) c.getInt(10) else 0
                    ))
                }
            }
            error = null
        } catch (t: Throwable) {
            Log.w("RiotLibrary", "load: $t")
            error = "Can't read music."
        }
        rebuild(out)
        loaded = true
    }

    /** Re-sorts with the current "The" Filter. */
    fun resort() = rebuild(songs)

    private fun rebuild(all: List<Song>) {
        val s = all.sortedBy { key(it.title) }
        val alb = all.groupBy { it.album to it.artist }
            .map { (k, v) -> Album(k.first, k.second, v.sortedWith(compareBy({ it.track }, { key(it.title) }))) }
            .sortedBy { key(it.name) }
        val art = all.groupBy { it.artist }.map { (name, v) ->
            val a = alb.filter { it.artist == name }
            Artist(name, a, v.sortedBy { key(it.title) })
        }.sortedBy { key(it.name) }
        byId = all.associateBy { it.id }
        songs = s; albums = alb; artists = art
    }

    fun genres(): List<Genre> {
        val out = ArrayList<Genre>()
        runCatching {
            ctx.contentResolver.query(
                MediaStore.Audio.Genres.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Genres._ID, MediaStore.Audio.Genres.NAME), null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val n = c.getString(1)?.trim().orEmpty()
                    if (n.isNotEmpty()) out.add(Genre(c.getLong(0), n))
                }
            }
        }
        return out.distinctBy { it.name.lowercase() }.sortedBy { it.name.lowercase() }
    }

    fun genreSongs(g: Genre): List<Song> {
        val ids = ArrayList<Long>()
        runCatching {
            ctx.contentResolver.query(
                MediaStore.Audio.Genres.Members.getContentUri("external", g.id),
                arrayOf(MediaStore.Audio.Genres.Members.AUDIO_ID), null, null, null
            )?.use { c -> while (c.moveToNext()) ids.add(c.getLong(0)) }
        }
        return ids.mapNotNull { byId[it] }
    }

    // ---- play counts (kept by Riot mode itself, while it is running) ---------------------------

    private val counts: SharedPreferences = ctx.getSharedPreferences("riot_counts", Context.MODE_PRIVATE)

    fun countPlay(id: Long) {
        val k = id.toString()
        val v = counts.getString(k, null)?.split(',')
        val n = (v?.getOrNull(0)?.toIntOrNull() ?: 0) + 1
        counts.edit().putString(k, "$n,${System.currentTimeMillis() / 1000}").apply()
    }

    private fun stats(): Map<Long, Pair<Int, Long>> = counts.all.mapNotNull { (k, v) ->
        val id = k.toLongOrNull() ?: return@mapNotNull null
        val p = (v as? String)?.split(',') ?: return@mapNotNull null
        id to ((p.getOrNull(0)?.toIntOrNull() ?: 0) to (p.getOrNull(1)?.toLongOrNull() ?: 0L))
    }.toMap()

    fun clearCounts() = counts.edit().clear().apply()

    /** Most played first. Only songs Riot mode has seen play count. */
    fun topSongs(n: Int): List<Song> = stats().entries.sortedByDescending { it.value.first }
        .mapNotNull { byId[it.key] }.take(n)

    fun favoriteAlbums(): List<Album> {
        val st = stats()
        return albums.map { a -> a to a.songs.sumOf { st[it.id]?.first ?: 0 } }
            .filter { it.second > 0 }.sortedByDescending { it.second }.take(40).map { it.first }
    }

    fun favoriteArtists(): List<Artist> {
        val st = stats()
        return artists.map { a -> a to a.songs.sumOf { st[it.id]?.first ?: 0 } }
            .filter { it.second > 0 }.sortedByDescending { it.second }.take(40).map { it.first }
    }

    // ---- Rio DJ -------------------------------------------------------------------------------

    private fun fill(pool: List<Song>, minutes: Int): List<Song> {
        if (minutes <= 0) return pool
        val out = ArrayList<Song>()
        var ms = 0L
        for (s in pool) { if (ms >= minutes * 60_000L) break; out.add(s); ms += s.durationMs.coerceAtLeast(1000) }
        return out
    }

    /** A random mix of favorites; with no history yet, of everything. [minutes] 0 = Everything. */
    fun entertainMe(minutes: Int): List<Song> {
        val fav = topSongs(200)
        val pool = (if (fav.size >= 5) fav else songs).shuffled()
        return fill(pool, minutes)
    }

    fun playAll(): List<Song> = albums.flatMap { it.songs }

    fun newMusic(days: Int): List<Song> {
        val since = System.currentTimeMillis() / 1000 - days * 86_400L
        return songs.filter { it.dateAdded >= since }.sortedByDescending { it.dateAdded }
    }

    /** Songs not heard in the window, least played first. */
    fun memoryLane(days: Int): List<Song> {
        val since = System.currentTimeMillis() / 1000 - days * 86_400L
        val st = stats()
        return songs.filter { (st[it.id]?.second ?: 0L) < since }
            .sortedBy { st[it.id]?.first ?: 0 }.take(250).shuffled()
    }

    /** [decade] like 1990, or a single [year]. */
    fun soundsOf(decade: Int?, year: Int?): List<Song> = songs.filter {
        if (year != null) it.year == year else decade != null && it.year in decade until decade + 10
    }.shuffled()

    fun randomPlay(minutes: Int): List<Song> = fill(songs.shuffled(), minutes)
}

/** Settings, presets and Riot playlists. One SharedPreferences file, nothing shared. */
class RiotPrefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("riot", Context.MODE_PRIVATE)

    var contrast: Int
        get() = p.getInt("contrast", 7)
        set(v) = p.edit().putInt("contrast", v).apply()
    /** Index into [BACKLIGHT]. */
    var backlight: Int
        get() = p.getInt("backlight", 3)
        set(v) = p.edit().putInt("backlight", v).apply()
    /** Index into [POWER_SAVER]. */
    var powerSaver: Int
        get() = p.getInt("power_saver", 1)
        set(v) = p.edit().putInt("power_saver", v).apply()
    var theFilter: Boolean
        get() = p.getBoolean("the_filter", false)
        set(v) = p.edit().putBoolean("the_filter", v).apply()
    var bass: Int
        get() = p.getInt("bass", 0)
        set(v) = p.edit().putInt("bass", v).apply()
    var treble: Int
        get() = p.getInt("treble", 0)
        set(v) = p.edit().putInt("treble", v).apply()
    var radioMode: Boolean
        get() = p.getBoolean("radio_mode", false)
        set(v) = p.edit().putBoolean("radio_mode", v).apply()
    var fmKHz: Int
        get() = p.getInt("fm_khz", 98100)
        set(v) = p.edit().putInt("fm_khz", v).apply()

    /** Eight FM presets in kHz; 0 is "empty". */
    fun presets(): IntArray = IntArray(8) { p.getInt("preset_$it", 0) }
    fun setPreset(i: Int, khz: Int) = p.edit().putInt("preset_$i", khz).apply()
    /** Set once the FM app's starred stations have been offered to the empty preset slots. */
    var presetsSeeded: Boolean
        get() = p.getBoolean("presets_seeded", false)
        set(v) = p.edit().putBoolean("presets_seeded", v).apply()
    /** Set once every slot has shown up in the FM app's presets (an FM app that takes them). */
    var presetsSynced: Boolean
        get() = p.getBoolean("presets_synced", false)
        set(v) = p.edit().putBoolean("presets_synced", v).apply()

    fun playlists(): MutableList<Playlist> = runCatching {
        val a = JSONArray(p.getString("playlists", "[]"))
        MutableList(a.length()) { i ->
            val o = a.getJSONObject(i)
            val ids = o.getJSONArray("ids")
            Playlist(o.getString("name"), MutableList(ids.length()) { ids.getLong(it) })
        }
    }.getOrDefault(mutableListOf())

    fun savePlaylists(list: List<Playlist>) {
        val a = JSONArray()
        for (pl in list) a.put(JSONObject().put("name", pl.name).put("ids", JSONArray(pl.ids)))
        p.edit().putString("playlists", a.toString()).apply()
    }

    /** "Delete Everything!" for the parts Riot mode owns: its playlists and its preferences. */
    fun wipe() = p.edit().clear().apply()

    companion object {
        val BACKLIGHT = listOf("Always Off", "1 second", "2 seconds", "5 seconds", "Always On")
        val BACKLIGHT_MS = listOf(0L, 1000L, 2000L, 5000L, Long.MAX_VALUE)
        val POWER_SAVER = listOf("1 minute", "2 minutes", "5 minutes", "15 minutes", "Never")
        val POWER_SAVER_MS = listOf(60_000L, 120_000L, 300_000L, 900_000L, Long.MAX_VALUE)
    }
}
