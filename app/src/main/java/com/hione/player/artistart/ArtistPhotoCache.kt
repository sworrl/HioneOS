package com.hione.player.artistart

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.hione.player.ArtistGroup
import com.hione.player.ArtistTransliterationStore
import com.hione.player.canonicalArtistKey
import com.hione.player.safeQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Real-artist-photo store. Lookup order for an artist (all off the main thread):
 *
 *  memory LRU → disk WebP (`files/artistphoto_v1/<sha1(key)>.webp`) → user-curated local file
 *  (artist.jpg / folder.jpg next to the music, see [findLocalOverride]) → online
 *  ([ArtistPhotoResolver], at most 2 concurrent lookups, gated by the Settings toggles).
 *
 * Misses are remembered in a persistent index (SharedPreferences `hione_artist_photo_index`,
 * canonical artist key → JSON) for [NEG_TTL_MS] (30 days) so an artist with no photo is not
 * re-queried every time the list scrolls; network failures / "not allowed right now" (offline,
 * Wi-Fi-only) are only remembered in memory for [TRANSIENT_TTL_MS]. A user's "not this artist"
 * rejection is stored as a permanent miss until the cache is cleared.
 *
 * Keys are [canonicalArtistKey]s — the same identity the library uses to merge "The Beatles" /
 * "Beatles, The" / accent variants — so one photo covers every spelling of the artist.
 */
object ArtistPhotoCache {
    private const val DIR = "artistphoto_v1"
    private const val INDEX_PREF = "hione_artist_photo_index"
    private const val NEG_TTL_MS = 30L * 24 * 60 * 60 * 1000
    private const val TRANSIENT_TTL_MS = 10 * 60_000L
    private const val MAX_PX = 640

    private val mem = object : LruCache<String, ImageBitmap>((Runtime.getRuntime().maxMemory() / 1024 / 10).toInt()) {
        override fun sizeOf(key: String, v: ImageBitmap) = (v.width * v.height * 4) / 1024
    }
    private val transientMiss = ConcurrentHashMap<String, Long>()
    private val inFlight = ConcurrentHashMap<String, Deferred<ImageBitmap?>>()
    private val awaiters = ConcurrentHashMap<String, Int>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val netGate = Semaphore(2)

    /** Bumped on clear/reject so composables holding a photo re-evaluate. */
    var version by mutableIntStateOf(0)
        private set

    // ---------------------------------------------------------------------------------------------
    // Keys / names
    // ---------------------------------------------------------------------------------------------

    /** Stable cache key for an ArtistGroup's (display-formatted, mutable) name. */
    fun keyFor(ctx: Context, displayName: String): String {
        val base = searchNames(ctx, displayName).firstOrNull() ?: displayName
        return canonicalArtistKey(base, ctx).ifBlank { displayName.trim().lowercase() }
    }

    /**
     * Spellings worth searching for a display name, best first. Undoes the list's display
     * formatting ("Beatles, The" → "The Beatles", "花冷え。 (Hanabie)" → both halves) and adds an
     * ICU transliteration for non-Latin names so a Japanese-tagged act can still hit its English
     * Wikidata label.
     */
    fun searchNames(ctx: Context, displayName: String): List<String> {
        var base = displayName.trim()
        var alt: String? = null
        Regex("^(.*\\S)\\s*\\(([^()]+)\\)\\s*$").find(base)?.let { m ->
            val inner = m.groupValues[2].trim()
            if (inner.any { it in 'a'..'z' || it in 'A'..'Z' }) { base = m.groupValues[1].trim(); alt = inner }
        }
        Regex("^(.*\\S)\\s*,\\s*the$", RegexOption.IGNORE_CASE).find(base)?.let { m -> base = "The " + m.groupValues[1].trim() }
        val out = ArrayList<String>(3)
        if (base.isNotBlank()) out += base
        alt?.let { out += it }
        if (base.any { it.code > 0x024F }) {
            val latin = runCatching { ArtistTransliterationStore.getEnglishName(ctx, base) }.getOrDefault("")
            if (latin.isNotBlank()) out += latin
        }
        return out.distinctBy { it.lowercase() }
    }

    // ---------------------------------------------------------------------------------------------
    // Reads
    // ---------------------------------------------------------------------------------------------

    fun peek(key: String): ImageBitmap? = mem.get(key)

    /** Credit info for a cached photo (null = no photo / miss). */
    fun info(ctx: Context, key: String): ArtistPhotoInfo? =
        prefs(ctx).getString(key, null)?.let { ArtistPhotoInfo.fromJson(it) }

    /** True when the user explicitly rejected the online photo for this artist. */
    fun isRejected(ctx: Context, key: String): Boolean =
        prefs(ctx).getString(key, null)?.let { runCatching { JSONObject(it).optBoolean("rejected") }.getOrDefault(false) } ?: false

    /**
     * Get the photo, loading it if needed. Concurrent callers for the same artist (many list rows
     * during a scroll, list + detail) share one job; when the LAST caller stops waiting (the row
     * scrolled off-screen) the job is cancelled, so a fast fling through hundreds of artists does
     * not leave hundreds of Wikidata lookups queued in the background — only what's on screen
     * gets looked up. null = show the fallback. Throws only the caller's own cancellation.
     */
    suspend fun load(ctx: Context, key: String, artist: ArtistGroup): ImageBitmap? {
        mem.get(key)?.let { return it }
        if (isTransientMiss(key)) return null
        val app = ctx.applicationContext
        repeat(3) {
            // LAZY so the job can't finish (and remove itself) before getOrPut has published it.
            val job = inFlight.getOrPut(key) {
                scope.async(start = CoroutineStart.LAZY) {
                    try { loadUncached(app, key, artist) }
                    catch (_: Throwable) { null }
                    finally { inFlight.remove(key) }
                }
            }
            awaiters.merge(key, 1, Int::plus)
            job.start()
            try {
                return job.await()
            } catch (e: CancellationException) {
                if (!currentCoroutineContext().isActive) throw e // our own caller went away
                // The shared job was cancelled by its previous last awaiter just as we joined — retry.
            } finally {
                val left = awaiters.merge(key, -1, Int::plus) ?: 0
                if (left <= 0) {
                    awaiters.remove(key)
                    if (job.isActive) job.cancel()
                }
            }
        }
        return null
    }

    private suspend fun loadUncached(ctx: Context, key: String, artist: ArtistGroup): ImageBitmap? {
        mem.get(key)?.let { return it }

        // 1. Disk cache (+ persisted miss)
        val entry = prefs(ctx).getString(key, null)
        if (entry != null) {
            val info = ArtistPhotoInfo.fromJson(entry)
            if (info != null) {
                decodeFile(diskFile(ctx, key))?.let { return remember(key, it) }
                // Index says we have it but the file is gone (or a local file moved): re-source.
                if (info.source == "local") {
                    decodeLocal(ctx, info.imageUrl)?.let { bmp -> return store(ctx, key, bmp, info) }
                    prefs(ctx).edit().remove(key).apply()
                } else if (networkAllowed(ctx)) {
                    fetchImage(ctx, key, info)?.let { return it }
                    return null
                } else { markTransient(key); return null }
            } else {
                val o = runCatching { JSONObject(entry) }.getOrNull()
                if (o != null) {
                    if (o.optBoolean("rejected")) return null
                    if (System.currentTimeMillis() - o.optLong("miss") < NEG_TTL_MS) return null
                }
            }
        }

        // 2. User-curated local file next to the music (offline, always wins over the internet)
        findLocalOverride(ctx, key, artist)?.let { (bmp, info) -> return store(ctx, key, bmp, info) }

        // 3. Online
        if (!networkAllowed(ctx)) { markTransient(key); return null }
        val names = searchNames(ctx, artist.name)
        val info = try {
            netGate.withPermit { ArtistPhotoResolver.resolve(ctx, names) }
        } catch (_: ArtistPhotoResolver.TransientFailure) { markTransient(key); return null }
        catch (e: CancellationException) { throw e } // scrolled away — not a miss of any kind
        catch (_: Throwable) { markTransient(key); return null }
        if (info == null) { markMiss(ctx, key); return null }
        // Persist the match before the download: if this job is cancelled mid-download (row
        // scrolled away) the next load finds the index entry and only re-fetches the bytes.
        prefs(ctx).edit().putString(key, info.toJson()).apply()
        return fetchImage(ctx, key, info)
    }

    private suspend fun fetchImage(ctx: Context, key: String, info: ArtistPhotoInfo): ImageBitmap? {
        val bytes = try {
            netGate.withPermit {
                ArtistPhotoResolver.downloadBytes(info.imageUrl)
                    // Special:FilePath?width= can fail for odd formats; the plain file always works.
                    ?: if (info.imageUrl.contains("?width=")) ArtistPhotoResolver.downloadBytes(info.imageUrl.substringBefore("?width=")) else null
            }
        } catch (_: ArtistPhotoResolver.TransientFailure) { markTransient(key); return null }
        catch (e: CancellationException) { throw e }
        catch (_: Throwable) { markTransient(key); return null }
        val bmp = bytes?.let { decodeSampled(it) }
        if (bmp == null) { markMiss(ctx, key); return null }
        return store(ctx, key, bmp, info)
    }

    // ---------------------------------------------------------------------------------------------
    // Local override: artist.jpg / folder.jpg the user placed next to the music
    // ---------------------------------------------------------------------------------------------

    private val ARTIST_FILE_NAMES = listOf("artist.jpg", "artist.jpeg", "artist.png", "artist.webp",
        "Artist.jpg", "Artist.jpeg", "Artist.png", "Artist.webp", "artist.JPG", "artist.PNG")
    private val FOLDER_FILE_NAMES = listOf("folder.jpg", "folder.jpeg", "folder.png", "folder.webp",
        "Folder.jpg", "Folder.png", "cover.jpg", "cover.png", "artist_photo.jpg", "artist_photo.png")

    /**
     * Looks, for the first few distinct directories the artist's tracks live in, for
     *   - `artist.*` in the track's own folder (album folder), and
     *   - `artist.*` / `folder.*` / `cover.*` in the PARENT folder, but only when that parent folder
     *     is named after the artist (canonical key match) — otherwise a random upper-level
     *     folder.jpg would be mistaken for the artist.
     * Tries raw File I/O first, then MediaStore.Images by exact path (SD cards without all-files
     * access are only visible through MediaStore — same lesson as AlbumArtImage's folder art).
     */
    private fun findLocalOverride(ctx: Context, key: String, artist: ArtistGroup): Pair<Bitmap, ArtistPhotoInfo>? {
        val dirs = artist.tracks.asSequence().map { it.path }.filter { it.isNotBlank() }
            .map { it.substringBeforeLast('/', "") }.filter { it.isNotBlank() }.distinct().take(10).toList()
        if (dirs.isEmpty()) return null
        val candidates = ArrayList<String>()
        for (d in dirs) {
            ARTIST_FILE_NAMES.forEach { candidates += "$d/$it" }
            val dirName = d.substringAfterLast('/')
            if (canonicalArtistKey(dirName, ctx) == key) FOLDER_FILE_NAMES.forEach { candidates += "$d/$it" }
            val parent = d.substringBeforeLast('/', "")
            if (parent.isNotBlank() && canonicalArtistKey(parent.substringAfterLast('/'), ctx) == key) {
                ARTIST_FILE_NAMES.forEach { candidates += "$parent/$it" }
                FOLDER_FILE_NAMES.forEach { candidates += "$parent/$it" }
            }
        }
        val distinct = candidates.distinct()
        for (p in distinct) {
            val f = File(p)
            if (f.isFile && f.length() > 0) {
                decodeFile(f)?.let { return it to localInfo(p) }
            }
        }
        // MediaStore.Images by exact path — chunked OR of DATA = ? (SQLite arg limit is generous, keep it modest)
        try {
            distinct.chunked(40).forEach { chunk ->
                val sel = chunk.joinToString(" OR ") { "${MediaStore.Images.Media.DATA} = ?" }
                ctx.contentResolver.safeQuery(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATA), sel, chunk.toTypedArray(), null
                )?.use { c ->
                    // Prefer the order of `distinct` (artist.* before folder.*), not cursor order.
                    val found = HashMap<String, Long>()
                    while (c.moveToNext()) { found[c.getString(1) ?: continue] = c.getLong(0) }
                    for (p in chunk) {
                        val id = found[p] ?: continue
                        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                        ctx.contentResolver.openInputStream(uri)?.use { ins -> ins.readBytes() }
                            ?.let { decodeSampled(it) }?.let { return it to localInfo(p) }
                    }
                }
            }
        } catch (_: Throwable) {}
        return null
    }

    private fun localInfo(path: String) = ArtistPhotoInfo(
        source = "local", imageUrl = path, fileName = path.substringAfterLast('/'), pageUrl = "",
        license = "Your own file", entityLabel = path.substringBeforeLast('/').substringAfterLast('/')
    )

    private fun decodeLocal(ctx: Context, path: String): Bitmap? {
        decodeFile(File(path))?.let { return it }
        return try {
            ctx.contentResolver.safeQuery(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
                "${MediaStore.Images.Media.DATA} = ?", arrayOf(path), null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                    ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.let { decodeSampled(it) }
                } else null
            }
        } catch (_: Throwable) { null }
    }

    // ---------------------------------------------------------------------------------------------
    // Misses / gating
    // ---------------------------------------------------------------------------------------------

    private fun isTransientMiss(key: String): Boolean {
        val at = transientMiss[key] ?: return false
        if (android.os.SystemClock.elapsedRealtime() - at > TRANSIENT_TTL_MS) { transientMiss.remove(key); return false }
        return true
    }
    private fun markTransient(key: String) { transientMiss[key] = android.os.SystemClock.elapsedRealtime() }
    private fun markMiss(ctx: Context, key: String) {
        prefs(ctx).edit().putString(key, JSONObject().put("miss", System.currentTimeMillis()).toString()).apply()
    }
    /** Forget in-memory "couldn't right now" misses (network came back, toggles changed). */
    fun clearTransientMisses() { transientMiss.clear() }

    /** User said "that's not them": drop the photo and never auto-fetch it again for this key. */
    fun reject(ctx: Context, key: String) {
        mem.remove(key)
        runCatching { diskFile(ctx, key).delete() }
        prefs(ctx).edit().putString(key, JSONObject().put("miss", System.currentTimeMillis()).put("rejected", true).toString()).apply()
        version++
    }

    /** Wipe every cached photo, credit and miss (including rejections). */
    fun clearAll(ctx: Context) {
        mem.evictAll()
        transientMiss.clear()
        runCatching { File(ctx.filesDir, DIR).deleteRecursively() }
        prefs(ctx).edit().clear().apply()
        version++
    }

    /** (photo count, bytes on disk, remembered misses) for the Settings card. */
    fun stats(ctx: Context): Triple<Int, Long, Int> {
        val dir = File(ctx.filesDir, DIR)
        val files = dir.listFiles() ?: emptyArray()
        val misses = prefs(ctx).all.values.count { v -> (v as? String)?.let { it.contains("\"miss\"") } == true }
        return Triple(files.size, files.sumOf { it.length() }, misses)
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

    // ---------------------------------------------------------------------------------------------
    // Storage / decode helpers
    // ---------------------------------------------------------------------------------------------

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(INDEX_PREF, Context.MODE_PRIVATE)

    private fun diskFile(ctx: Context, key: String): File {
        val dir = File(ctx.filesDir, DIR)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, sha1(key) + ".webp")
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun remember(key: String, bmp: Bitmap): ImageBitmap {
        val img = bmp.asImageBitmap()
        mem.put(key, img)
        return img
    }

    private fun store(ctx: Context, key: String, bmp: Bitmap, info: ArtistPhotoInfo): ImageBitmap {
        val scaled = downscale(bmp)
        writeWebp(scaled, diskFile(ctx, key))
        prefs(ctx).edit().putString(key, info.toJson()).apply()
        return remember(key, scaled)
    }

    private fun downscale(b: Bitmap): Bitmap {
        val longest = maxOf(b.width, b.height)
        if (longest <= MAX_PX) return b
        val s = MAX_PX.toFloat() / longest
        return Bitmap.createScaledBitmap(b, maxOf(1, (b.width * s).toInt()), maxOf(1, (b.height * s).toInt()), true)
    }

    private fun writeWebp(bmp: Bitmap, f: File) {
        try {
            f.outputStream().use {
                if (Build.VERSION.SDK_INT >= 30) bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, 88, it)
                else @Suppress("DEPRECATION") bmp.compress(Bitmap.CompressFormat.WEBP, 88, it)
            }
        } catch (_: Throwable) { runCatching { f.delete() } }
    }

    private fun decodeFile(f: File): Bitmap? {
        if (!f.isFile || f.length() <= 0L) return null
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, opts)
            var s = 1
            while (opts.outWidth / s > MAX_PX || opts.outHeight / s > MAX_PX) s *= 2
            BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = s })
        } catch (_: Throwable) { null }
    }

    private fun decodeSampled(bytes: ByteArray): Bitmap? = try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) null else {
            var s = 1
            while (opts.outWidth / s > MAX_PX || opts.outHeight / s > MAX_PX) s *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
        }
    } catch (_: Throwable) { null }
}
