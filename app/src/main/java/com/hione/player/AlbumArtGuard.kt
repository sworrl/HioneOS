package com.hione.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.hione.player.artistart.ArtistPhotoPrefs
import com.hione.player.discsplit.MusicBrainzClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * One cover spread across many albums.
 *
 * Found on the M500 (2026-10-10): 15 Nine Inch Nails albums (Deep, Year Zero, Only, Fixed, The
 * Fragile: Deviations 1 and 1.5, Capital G, ...) carry the TRON: Ares soundtrack cover as the
 * embedded FLAC picture in every track, and the same bytes again as cover.jpg in each folder.
 * The art pipeline reads embedded art first, so it showed TRON: Ares for all of them. The files
 * were tagged that way before they reached the player, so no lookup order fixes it on its own.
 *
 * The guard: for an artist with several albums, take one fingerprint (MD5 of the picture bytes)
 * per album. A picture used by [MIN_ALBUMS] or more albums with different base titles is treated
 * as a mis-tag and never used as anyone's art. Base titles drop (...) and [...] so a 24-bit
 * re-rip, a deluxe edition or a [v2] single still counts as the same album and can share a cover.
 *
 * Albums whose own picture is flagged ("tainted") skip every source that is derived from that
 * picture (MediaStore thumbnails and album art) and fall through to other images in the album
 * folder, then to [OnlineAlbumArt].
 */
internal object SharedCoverGuard {
    private const val TAG = "HioneArtGuard"
    private const val MIN_ALBUMS = 4
    private const val PREF = "hione_art_guard_v1"
    private const val KEY_TAINTED = "tainted_albums"
    private const val KEY_SUSPECT = "suspect_fingerprints"

    data class AlbumInfo(
        val key: String,
        val album: String,
        val artist: String,
        val year: Int,
        val baseTitle: String,
        val rep: Pair<Long, String>,
        val trackIds: List<Long>,
    )

    @Volatile private var builtFor = 0
    private val trackAlbum = ConcurrentHashMap<Long, String>()
    private val albums = ConcurrentHashMap<String, AlbumInfo>()
    private val artistAlbums = ConcurrentHashMap<String, List<String>>()
    private val albumArtist = ConcurrentHashMap<String, String>()
    private val artistDone = ConcurrentHashMap<String, Boolean>()
    private val artistLocks = ConcurrentHashMap<String, Any>()
    private val suspect: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val tainted: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun clear() {
        builtFor = 0
        trackAlbum.clear(); albums.clear(); artistAlbums.clear(); albumArtist.clear()
        artistDone.clear(); fileFp.clear()
        // suspect/tainted are kept: they are facts about file bytes, and dropping them would let
        // one art load slip through before the artist pass runs again.
    }

    fun albumKey(t: Track): String {
        val who = t.albumArtist.ifBlank { t.artist }.trim().lowercase()
        return t.album.trim().lowercase() + "\u0000" + who
    }

    fun baseTitle(album: String): String {
        val b = album.lowercase()
            .replace(Regex("[\\[(][^\\])]*[\\])]"), " ")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            // "Box Set CD2", "Live Disc 3": one release split into discs, which share a cover.
            .replace(Regex("\\b(cd|disc|disk|vol|volume|part|pt)\\s*\\d+\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return b.ifEmpty { album.trim().lowercase() }
    }

    @Synchronized
    private fun build() {
        val lib = FastLibraryStore.peek() ?: return
        if (lib.isEmpty() || lib.size == builtFor) return
        val byAlbum = LinkedHashMap<String, MutableList<Track>>()
        for (t in lib) {
            if (t.parentId != 0L) continue
            byAlbum.getOrPut(albumKey(t)) { mutableListOf() }.add(t)
        }
        val perArtist = HashMap<String, MutableList<String>>()
        for ((k, ts) in byAlbum) {
            val first = ts.firstOrNull { it.path.isNotBlank() } ?: ts.first()
            val artist = first.albumArtist.ifBlank { first.artist }.trim()
            val info = AlbumInfo(
                key = k,
                album = first.album.trim(),
                artist = artist,
                year = ts.firstOrNull { it.year in 1000..2999 }?.year ?: 0,
                baseTitle = baseTitle(first.album),
                rep = first.id to first.path,
                trackIds = ts.map { it.id },
            )
            albums[k] = info
            ts.forEach { trackAlbum[it.id] = k }
            val ak = artist.lowercase()
            albumArtist[k] = ak
            perArtist.getOrPut(ak) { mutableListOf() }.add(k)
        }
        perArtist.forEach { (a, ks) -> artistAlbums[a] = ks }
        builtFor = lib.size
    }

    fun albumFor(trackId: Long): AlbumInfo? {
        build()
        return trackAlbum[trackId]?.let { albums[it] }
    }

    @Volatile private var loaded = false

    /** Load the fingerprints flagged in earlier runs, so the guard works before the library is in memory. */
    fun init(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val prefs = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            suspect.addAll(prefs.getStringSet(KEY_SUSPECT, emptySet()).orEmpty())
            loaded = true
        }
    }

    /** True once the library index is built. Before that, album-level answers are unknown. */
    fun ready(): Boolean { build(); return builtFor > 0 }

    fun hasSuspects(): Boolean = suspect.isNotEmpty()
    fun isSuspect(fp: String?): Boolean = fp != null && fp.isNotEmpty() && fp in suspect
    fun isSuspectBytes(bytes: ByteArray?): Boolean = bytes != null && suspect.isNotEmpty() && fingerprint(bytes) in suspect
    /** path -> "mtime:fingerprint" for folder images, so a 15-track album hashes its cover once. */
    private val fileFp = ConcurrentHashMap<String, String>()

    fun isSuspectFile(f: File?): Boolean {
        if (f == null || suspect.isEmpty()) return false
        return try {
            val m = f.lastModified()
            val hit = fileFp[f.path]
            val fp = if (hit != null && hit.substringBefore(':').toLongOrNull() == m) hit.substringAfter(':')
            else fingerprint(f.readBytes()).also { fileFp[f.path] = "$m:$it" }
            fp in suspect
        } catch (_: Throwable) { false }
    }

    /**
     * Is this track's album one whose own picture is a shared mis-tag? Runs the artist pass the
     * first time an artist is seen (one picture read per album, cached on disk by path + mtime).
     */
    fun isTainted(ctx: Context, trackId: Long): Boolean {
        build()
        val k = trackAlbum[trackId] ?: return false
        val artist = albumArtist[k] ?: return false
        ensureArtist(ctx, artist)
        return k in tainted
    }

    private fun ensureArtist(ctx: Context, artist: String) {
        if (artistDone[artist] == true) return
        val lock = artistLocks.getOrPut(artist) { Any() }
        synchronized(lock) {
            if (artistDone[artist] == true) return
            val keys = artistAlbums[artist].orEmpty()
            if (keys.size >= MIN_ALBUMS) scanArtist(ctx, keys)
            artistDone[artist] = true
        }
    }

    private fun scanArtist(ctx: Context, keys: List<String>) {
        val prefs = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val edit = prefs.edit()
        val fpOf = HashMap<String, String>()
        for (k in keys) {
            val info = albums[k] ?: continue
            val fp = albumFingerprint(ctx, info, prefs, edit)
            if (fp.isNotEmpty()) fpOf[k] = fp
        }
        val titlesByFp = HashMap<String, MutableSet<String>>()
        fpOf.forEach { (k, fp) -> titlesByFp.getOrPut(fp) { mutableSetOf() }.add(albums[k]?.baseTitle ?: k) }
        val flagged = titlesByFp.filterValues { it.size >= MIN_ALBUMS }.keys
        if (flagged.isNotEmpty()) {
            suspect.addAll(flagged)
            val hit = fpOf.filterValues { it in flagged }.keys
            tainted.addAll(hit)
            // Thumbs cached before this pass ran hold the wrong picture. Drop them once, the first
            // time an album is seen tainted; after that the pipeline never writes them again.
            val known = prefs.getStringSet(KEY_TAINTED, emptySet()).orEmpty()
            val fresh = hit.filter { it !in known }
            fresh.forEach { k -> albums[k]?.trackIds?.forEach { id -> dropCachedArt(ctx, id) } }
            // Memory copies may come from before the library loaded (cold start), so always drop those.
            hit.forEach { k -> albums[k]?.trackIds?.forEach { id -> AlbumArtCache.forget(id) } }
            if (fresh.isNotEmpty()) edit.putStringSet(KEY_TAINTED, known + fresh)
            val knownFp = prefs.getStringSet(KEY_SUSPECT, emptySet()).orEmpty()
            if (!knownFp.containsAll(flagged)) edit.putStringSet(KEY_SUSPECT, knownFp + flagged)
            Log.i(TAG, "shared cover across ${hit.size} albums of ${albums[hit.first()]?.artist}: ${hit.mapNotNull { albums[it]?.album }}")
        }
        edit.apply()
    }

    /** MD5 of the album's own picture: embedded in its first track, else its folder image. */
    private fun albumFingerprint(
        ctx: Context, info: AlbumInfo,
        prefs: android.content.SharedPreferences, edit: android.content.SharedPreferences.Editor,
    ): String {
        val (id, path) = info.rep
        val mtime = if (path.isNotBlank()) runCatching { File(path).lastModified() }.getOrDefault(0L) else 0L
        val cacheKey = path.ifBlank { "id:$id" }
        prefs.getString(cacheKey, null)?.let { v ->
            val at = v.substringBefore(':').toLongOrNull()
            if (at == mtime) return v.substringAfter(':')
        }
        var fp = ""
        val mmr = MediaMetadataRetriever()
        try {
            if (path.isNotBlank() && File(path).exists()) mmr.setDataSource(path)
            else mmr.setDataSource(ctx, android.content.ContentUris.withAppendedId(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id))
            mmr.embeddedPicture?.let { fp = fingerprint(it) }
        } catch (_: Throwable) {
        } finally { try { mmr.release() } catch (_: Throwable) {} }
        if (fp.isEmpty() && path.isNotBlank()) {
            resolveFolderArtFile(path)?.let { f -> fp = runCatching { fingerprint(f.readBytes()) }.getOrDefault("") }
        }
        edit.putString(cacheKey, "$mtime:$fp")
        return fp
    }

    fun fingerprint(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) } + ":" + bytes.size
}

/**
 * Album art from the Cover Art Archive, for albums with no usable local picture after
 * [SharedCoverGuard]. Keyed by artist + album + year, never artist alone. MusicBrainz release
 * group search (through [MusicBrainzClient], which holds the 1 request/s limit), then the front
 * image at 500 px. Uses the same on/off and Wi-Fi only switches as artist photos.
 *
 * Never blocks an art load: [request] runs in the background and [onArrived] tells the art cache
 * to drop the miss and redraw.
 */
internal object OnlineAlbumArt {
    private const val TAG = "HioneOnlineArt"
    private const val DIR = "albumart_online_v1"
    private const val PREF = "hione_album_art_online_v1"
    private const val MISS_TTL_MS = 7L * 24 * 3600 * 1000
    private const val MAX_BYTES = 6 * 1024 * 1024
    private const val CAA = "https://coverartarchive.org/release-group/"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(1)
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun cacheKey(info: SharedCoverGuard.AlbumInfo): String =
        sha1(info.artist.lowercase() + "\u0000" + info.album.lowercase() + "\u0000" + info.year)

    private fun file(ctx: Context, key: String): File {
        val dir = File(ctx.filesDir, DIR)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "$key.jpg")
    }

    /** The downloaded cover if there is one on disk, decoded down to about [target] px. */
    fun cached(ctx: Context, info: SharedCoverGuard.AlbumInfo, target: Int): Bitmap? {
        val f = file(ctx, cacheKey(info))
        if (!f.exists() || f.length() == 0L) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, bounds)
            var s = 1
            while (minOf(bounds.outWidth, bounds.outHeight) / (s * 2) >= target) s *= 2
            BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = s })
        } catch (_: Throwable) { null }
    }

    /** Start a lookup for [info] unless one ran recently, is running, or the network is off. */
    fun request(ctx: Context, info: SharedCoverGuard.AlbumInfo, onArrived: (List<Long>) -> Unit) {
        val app = ctx.applicationContext
        val key = cacheKey(info)
        val prefs = app.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val missAt = prefs.getLong(key, 0L)
        if (missAt > 0 && System.currentTimeMillis() - missAt < MISS_TTL_MS) return
        if (!networkAllowed(app)) return
        if (!inFlight.add(key)) return
        scope.launch {
            try {
                val bytes = gate.withPermit { lookup(info) }
                if (bytes != null) {
                    file(app, key).writeBytes(bytes)
                    prefs.edit().remove(key).apply()
                    onArrived(info.trackIds)
                } else {
                    prefs.edit().putLong(key, System.currentTimeMillis()).apply()
                }
            } catch (t: Throwable) {
                // Network trouble is not a verdict on the album: no miss recorded, retried later.
                Log.w(TAG, "lookup failed for ${info.artist} / ${info.album}: ${t.message}")
            } finally {
                inFlight.remove(key)
            }
        }
    }

    private fun lookup(info: SharedCoverGuard.AlbumInfo): ByteArray? {
        val cleaned = info.album.replace(Regex("\\[[^\\]]*\\]"), " ").replace(Regex("\\s+"), " ").trim()
        val bare = info.album.replace(Regex("[\\[(][^\\])]*[\\])]"), " ").replace(Regex("\\s+"), " ").trim()
        val titles = listOf(cleaned, bare).filter { it.isNotBlank() }.distinct()
        for (title in titles) {
            val q = "releasegroup:${MusicBrainzClient.phrase(title)} AND artist:${MusicBrainzClient.phrase(info.artist)}"
            val res = MusicBrainzClient.getJson("release-group/?query=${MusicBrainzClient.enc(q)}&fmt=json&limit=8")
            val groups = res.optJSONArray("release-groups") ?: continue
            val picks = ArrayList<Pair<Int, String>>()
            for (i in 0 until groups.length()) {
                val g = groups.optJSONObject(i) ?: continue
                val rank = matchRank(g, info, title) ?: continue
                picks += rank to g.optString("id")
            }
            for ((_, mbid) in picks.sortedBy { it.first }.take(2)) {
                if (mbid.isBlank()) continue
                download("$CAA$mbid/front-500")?.let {
                    Log.i(TAG, "${info.artist} / ${info.album}: cover from release group $mbid")
                    return it
                }
            }
        }
        return null
    }

    /** Lower is better; null = not this album. Title and artist must both match. */
    private fun matchRank(g: JSONObject, info: SharedCoverGuard.AlbumInfo, title: String): Int? {
        val gTitle = norm(g.optString("title"))
        val exact = gTitle == norm(title) || gTitle == norm(info.album)
        val base = SharedCoverGuard.baseTitle(g.optString("title")) == info.baseTitle
        if (!exact && !base) return null
        val credits = g.optJSONArray("artist-credit")
        val want = norm(info.artist)
        var artistOk = false
        if (credits != null) for (i in 0 until credits.length()) {
            val c = credits.optJSONObject(i) ?: continue
            val n = norm(c.optString("name").ifBlank { c.optJSONObject("artist")?.optString("name").orEmpty() })
            if (n.isNotEmpty() && (n == want || want.contains(n) || n.contains(want))) { artistOk = true; break }
        }
        if (!artistOk) return null
        var rank = if (exact) 0 else 10
        val date = g.optString("first-release-date")
        if (info.year > 0 && !date.startsWith(info.year.toString())) rank += 5
        rank += (100 - g.optInt("score", 0)).coerceIn(0, 100) / 10
        return rank
    }

    private fun norm(s: String): String = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun download(url: String): ByteArray? {
        var u = URL(url)
        repeat(5) {
            val conn = u.openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 10_000
                conn.readTimeout = 20_000
                conn.setRequestProperty("User-Agent", MusicBrainzClient.USER_AGENT)
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: return null
                    u = URL(u, loc)
                    return@repeat
                }
                if (code == 404) return null
                if (code != 200) throw java.io.IOException("HTTP $code for $u")
                val out = java.io.ByteArrayOutputStream()
                conn.inputStream.use { ins ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > MAX_BYTES) return null
                    }
                }
                val bytes = out.toByteArray()
                val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, b)
                return if (b.outWidth > 0 && b.outHeight > 0) bytes else null
            } finally { conn.disconnect() }
        }
        return null
    }

    private fun networkAllowed(ctx: Context): Boolean {
        if (!ArtistPhotoPrefs.fetchOnline(ctx)) return false
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull() ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        if (ArtistPhotoPrefs.wifiOnly(ctx)) {
            return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        }
        return true
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
