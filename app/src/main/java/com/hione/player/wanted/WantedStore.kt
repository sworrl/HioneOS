package com.hione.player.wanted

import android.content.ContentValues
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hione.player.FastLibraryStore
import com.hione.player.LikeStore
import com.hione.player.PlayerPreferences
import com.hione.player.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The "wanted" list: songs hearted on the FM radio, kept with where and when they were heard.
 *
 * Every row is a liked song. When the library has it, the row is IN_LIBRARY and the track itself
 * goes into Liked Songs. When it does not, the row stays WANTED, which is the "liked, don't have
 * it, go get it" list. Matching runs when a like arrives and after every library scan
 * ([onLibraryChanged], called from FastLibraryStore.saveAsync).
 */
object WantedStore {
    private const val TAG = "HioneWanted"
    const val ACTION_FM_TRACK_LIKED = "com.hione.player.action.FM_TRACK_LIKED"

    private val _items = MutableStateFlow<List<WantedItem>>(emptyList())
    val items: StateFlow<List<WantedItem>> = _items

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lock = Any()
    @Volatile private var loaded = false

    /** Load once for the UI. Cheap; safe to call from every screen that shows a count. */
    fun ensureLoaded(ctx: Context) {
        if (loaded) return
        loaded = true
        refreshAsync(ctx)
    }

    fun refreshAsync(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch { publish(app) }
    }

    private fun publish(ctx: Context) {
        _items.value = runCatching { WantedDb.get(ctx).all() }.getOrElse {
            Log.w(TAG, "read failed: $it"); emptyList()
        }
    }

    // ================================================================= FM like in

    data class FmLike(
        val id: String,
        val title: String,
        val artist: String,
        val album: String?,
        val coverUrl: String?,
        val webUrl: String?,
        val station: String,
        val freqKHz: Int,
        val heardAtMs: Long,
        val lat: Double?,
        val lon: Double?,
        val source: String,
    )

    /** Called off the main thread from [FmTrackLikedReceiver]. */
    fun ingest(ctx: Context, like: FmLike) {
        val app = ctx.applicationContext
        if (like.title.isBlank() || like.artist.isBlank()) return
        val db = WantedDb.get(app)
        val key = WantedMatch.key(like.title, like.artist)
        synchronized(lock) {
            if (db.byId(like.id) != null) return@synchronized            // FM resend, already have it
            if (db.isTombstoned(like.id)) return@synchronized            // deleted here on purpose
            val dup = db.byNormKey(key)
            if (dup != null) {
                // Same song liked again (another station, another day): one row, counted.
                db.update(dup.id, ContentValues().apply {
                    put("heard_count", dup.heardCount + 1)
                    if (dup.status == WantedStatus.DISMISSED) put("status", WantedStatus.WANTED.name)
                    if (dup.coverUrl.isNullOrBlank() && !like.coverUrl.isNullOrBlank()) put("cover_url", like.coverUrl)
                    if (dup.webUrl.isNullOrBlank() && !like.webUrl.isNullOrBlank()) put("web_url", like.webUrl)
                })
                return@synchronized
            }
            val now = System.currentTimeMillis()
            db.insert(
                WantedItem(
                    id = like.id, title = like.title.trim(), artist = like.artist.trim(),
                    album = like.album?.trim()?.ifBlank { null },
                    coverUrl = like.coverUrl?.ifBlank { null }, webUrl = like.webUrl?.ifBlank { null },
                    source = like.source, station = like.station.trim(), freqKHz = like.freqKHz,
                    heardAtMs = if (like.heardAtMs > 0) like.heardAtMs else now,
                    lat = like.lat, lon = like.lon, placeName = null,
                    status = WantedStatus.WANTED, addedAt = now, updatedAt = now,
                    matchedTrackId = null, autoLiked = false, heardCount = 1, normKey = key,
                )
            )
            Log.i(TAG, "wanted: ${like.artist} - ${like.title} (${like.station} ${like.freqKHz})")
        }
        val tracks = FastLibraryStore.peek() ?: runCatching { FastLibraryStore.loadSync(app) }.getOrNull()
        if (tracks != null) matchAll(app, tracks) else publish(app)
        if (like.lat != null && like.lon != null) scope.launch { fillPlaceNames(app) }
    }

    /** The radio un-hearted it: drop the row, and the library like it caused (if any). */
    fun unlike(ctx: Context, id: String, title: String, artist: String) {
        val app = ctx.applicationContext
        val db = WantedDb.get(app)
        synchronized(lock) {
            val row = db.byId(id) ?: db.byNormKey(WantedMatch.key(title, artist)) ?: return@synchronized
            if (row.autoLiked) row.matchedTrackId?.let { unlikeTrack(app, it) }
            db.delete(row.id, tombstone = false)
            Log.i(TAG, "un-wanted: ${row.artist} - ${row.title}")
        }
        publish(app)
    }

    // ================================================================= library match

    /** Hook for FastLibraryStore.saveAsync: the library just changed, re-match everything. */
    fun onLibraryChanged(ctx: Context, tracks: List<Track>) {
        val app = ctx.applicationContext
        scope.launch { runCatching { matchAll(app, tracks) }.onFailure { Log.w(TAG, "match failed: $it") } }
    }

    private fun matchAll(ctx: Context, tracks: List<Track>) {
        val db = WantedDb.get(ctx)
        val rows = db.all().filter { it.status != WantedStatus.DISMISSED }
        if (rows.isEmpty()) { publish(ctx); return }
        val index = WantedMatch.Index(tracks)
        val ids = tracks.mapTo(HashSet(tracks.size)) { it.id }
        var changed = 0
        synchronized(lock) {
            for (row in rows) {
                // Already tied to a track that still exists (found earlier, or linked by hand).
                if (row.status == WantedStatus.IN_LIBRARY && row.matchedTrackId != null && row.matchedTrackId in ids) continue
                val hit = index.find(row.title, row.artist)
                if (hit != null) {
                    val auto = likeTrack(ctx, hit)
                    db.update(row.id, ContentValues().apply {
                        put("status", WantedStatus.IN_LIBRARY.name)
                        put("matched_track_id", hit.id)
                        // Keep an earlier auto-like flag if this is just MediaStore renumbering.
                        put("auto_liked", if (auto || row.autoLiked) 1 else 0)
                    })
                    changed++
                } else if (row.status == WantedStatus.IN_LIBRARY) {
                    // The file went away: it is wanted again.
                    db.update(row.id, ContentValues().apply {
                        put("status", WantedStatus.WANTED.name)
                        putNull("matched_track_id")
                    })
                    changed++
                }
            }
        }
        if (changed > 0) Log.i(TAG, "library match: $changed rows changed")
        publish(ctx)
    }

    /** Puts a track into Liked Songs. Returns true when this call is what liked it. */
    private fun likeTrack(ctx: Context, t: Track): Boolean {
        LikeStore.init(ctx)
        if (PlayerPreferences.loadLikedTracks(ctx).contains(t.id)) return false
        // The user said no to this exact track before; a radio like does not overrule that.
        if (PlayerPreferences.loadRefusedTracks(ctx).contains(t.id)) return false
        PlayerPreferences.setHeartCount(ctx, t.id, PlayerPreferences.getHeartCount(ctx, t.id).coerceAtLeast(1))
        PlayerPreferences.saveLikedTrackMeta(ctx, t.id, t.title, t.artist)
        Handler(Looper.getMainLooper()).post { if (!LikeStore.liked.contains(t.id)) LikeStore.liked.add(t.id) }
        return true
    }

    private fun unlikeTrack(ctx: Context, id: Long) {
        PlayerPreferences.setHeartCount(ctx, id, 0)
        PlayerPreferences.clearHeartEvents(ctx, id)
        PlayerPreferences.removeLikedTrackMeta(ctx, id)
        Handler(Looper.getMainLooper()).post { LikeStore.liked.remove(id) }
    }

    // ================================================================= user actions

    fun setStatus(ctx: Context, id: String, status: WantedStatus) {
        val app = ctx.applicationContext
        scope.launch {
            WantedDb.get(app).update(id, ContentValues().apply { put("status", status.name) })
            if (status == WantedStatus.ACQUIRED) {
                // They may already have copied it in; check now instead of waiting for a scan.
                FastLibraryStore.peek()?.let { matchAll(app, it); return@launch }
            }
            publish(app)
        }
    }

    /** "This one is it": tie a wanted row to a library track by hand. */
    fun link(ctx: Context, id: String, track: Track) {
        val app = ctx.applicationContext
        scope.launch {
            val auto = likeTrack(app, track)
            WantedDb.get(app).update(id, ContentValues().apply {
                put("status", WantedStatus.IN_LIBRARY.name)
                put("matched_track_id", track.id)
                put("auto_liked", if (auto) 1 else 0)
            })
            publish(app)
        }
    }

    fun delete(ctx: Context, id: String) {
        val app = ctx.applicationContext
        scope.launch {
            WantedDb.get(app).delete(id, tombstone = true)
            publish(app)
        }
    }

    // ================================================================= place names

    /**
     * Best-effort town name for rows that have coordinates but no place yet. Geocoder first (no
     * network of ours), then OpenStreetMap's reverse lookup. Failures leave the row alone and the
     * UI falls back to rounded coordinates.
     */
    fun fillPlaceNames(ctx: Context) {
        val app = ctx.applicationContext
        val db = WantedDb.get(app)
        val todo = db.all().filter { it.placeName == null && it.lat != null && it.lon != null }.take(10)
        if (todo.isEmpty()) return
        val cache = HashMap<String, String?>()
        for (row in todo) {
            val k = String.format(Locale.US, "%.2f,%.2f", row.lat, row.lon)
            val name = cache.getOrPut(k) { reverseGeocode(app, row.lat!!, row.lon!!) } ?: continue
            db.update(row.id, ContentValues().apply { put("place_name", name) })
        }
        publish(app)
    }

    @Suppress("DEPRECATION")
    private fun reverseGeocode(ctx: Context, lat: Double, lon: Double): String? {
        runCatching {
            if (android.location.Geocoder.isPresent()) {
                val a = android.location.Geocoder(ctx, Locale.US).getFromLocation(lat, lon, 1)?.firstOrNull()
                val n = a?.locality ?: a?.subAdminArea
                if (!n.isNullOrBlank()) return n
            }
        }
        return runCatching {
            val url = URL(String.format(Locale.US,
                "https://nominatim.openstreetmap.org/reverse?format=json&lat=%.4f&lon=%.4f&zoom=10", lat, lon))
            val c = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000; readTimeout = 5000
                setRequestProperty("User-Agent", "HioneOS (https://github.com/sworrl/HioneOS)")
            }
            try {
                if (c.responseCode != 200) return@runCatching null
                val a = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optJSONObject("address")
                listOf("city", "town", "village", "hamlet", "county").firstNotNullOfOrNull { k ->
                    a?.optString(k)?.ifBlank { null }
                }
            } finally { c.disconnect() }
        }.getOrNull()
    }

    // ================================================================= text + export

    fun stationLabel(it: WantedItem): String {
        val mhz = if (it.freqKHz > 0) String.format(Locale.US, "%.1f", it.freqKHz / 1000.0) else ""
        return listOf(it.station.ifBlank { if (mhz.isNotEmpty()) "FM" else "" }, mhz).filter { s -> s.isNotBlank() }.joinToString(" ")
    }

    fun whenLabel(ms: Long): String =
        if (ms <= 0) "" else SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date(ms))

    fun placeLabel(it: WantedItem): String? = when {
        !it.placeName.isNullOrBlank() -> it.placeName
        it.lat != null && it.lon != null -> String.format(Locale.US, "%.2f, %.2f", it.lat, it.lon)
        else -> null
    }

    /** "Heard on WXYZ 101.9 · Oct 10, 1:14 AM · near <town>" */
    fun heardLine(it: WantedItem): String {
        val parts = ArrayList<String>(3)
        stationLabel(it).ifBlank { null }?.let { s -> parts.add("Heard on $s") }
        whenLabel(it.heardAtMs).ifBlank { null }?.let { w -> parts.add(w) }
        placeLabel(it)?.let { p -> parts.add("near $p") }
        if (it.heardCount > 1) parts.add("liked ${it.heardCount} times")
        return parts.joinToString(" · ")
    }

    /** Plain list for pasting into a note or a shopping message. */
    fun exportText(items: List<WantedItem>): String = buildString {
        append("Songs I liked on the radio and don't have yet\n\n")
        items.forEach { w ->
            append("- ").append(w.artist).append(" - ").append(w.title)
            w.album?.let { a -> append(" (").append(a).append(")") }
            append("\n    ").append(heardLine(w))
            w.webUrl?.let { u -> append("\n    ").append(u) }
            append('\n')
        }
    }

    fun exportCsv(ctx: Context, items: List<WantedItem>): File {
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "stats").apply { mkdirs() }
        val f = File(dir, "hione_wanted.csv")
        fun q(s: String?): String = "\"" + (s ?: "").replace("\"", "\"\"") + "\""
        val iso = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        f.bufferedWriter().use { w ->
            w.write("status,artist,title,album,station,freq_mhz,heard_at,place,lat,lon,times_liked,source,link\n")
            for (it in items) {
                w.write(listOf(
                    q(it.status.label), q(it.artist), q(it.title), q(it.album), q(it.station),
                    if (it.freqKHz > 0) String.format(Locale.US, "%.1f", it.freqKHz / 1000.0) else "",
                    q(if (it.heardAtMs > 0) iso.format(Date(it.heardAtMs)) else ""), q(it.placeName),
                    it.lat?.let { v -> String.format(Locale.US, "%.4f", v) } ?: "",
                    it.lon?.let { v -> String.format(Locale.US, "%.4f", v) } ?: "",
                    it.heardCount.toString(), q(it.source), q(it.webUrl),
                ).joinToString(","))
                w.write("\n")
            }
        }
        return f
    }
}
