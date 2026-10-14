package com.hione.player

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

private const val THUMB_PX = 320
private const val HIRES_PX = 800

private fun artDir(ctx: Context): File {
    val dir = File(ctx.filesDir, "artcache_v4")
    if (!dir.exists()) {
        dir.mkdirs()
        runCatching { File(ctx.filesDir, "artcache").deleteRecursively() }
        runCatching { File(ctx.filesDir, "artcache_v2").deleteRecursively() }
        runCatching { File(ctx.filesDir, "artcache_v3").deleteRecursively() }
        runCatching { File(ctx.cacheDir, "hud").deleteRecursively() }
    }
    return dir
}

internal fun thumbFile(ctx: Context, id: Long) = File(artDir(ctx), "$id.webp")
internal fun hiresFile(ctx: Context, id: Long) = File(artDir(ctx), "${id}_hi.webp")

/**
 * Two-tier album-art cache built for INSTANT display and 100% ACCURATE album art matching:
 *  - Memory LruCache holding decoded thumbs + hi-res
 *  - WebP disk cache in `artcache_v4` (older versions are deleted on first use)
 *  - Sources and their order: see [resolveArt]. Pictures shared across many albums are
 *    skipped (see SharedCoverGuard in AlbumArtGuard.kt).
 */
object AlbumArtCache {
    private val cacheSize = (Runtime.getRuntime().maxMemory() / 1024 / 3).toInt()
    private val mem = object : LruCache<String, ImageBitmap>(cacheSize) {
        override fun sizeOf(key: String, v: ImageBitmap) = (v.width * v.height * 4) / 1024
    }
    private const val MISS_TTL_MS = 5 * 60_000L
    private val misses = java.util.concurrent.ConcurrentHashMap<String, Long>()
    internal val artDispatcher = java.util.concurrent.Executors.newFixedThreadPool(4) { r ->
        Thread({
            android.os.Process.setThreadPriority(
                android.os.Process.THREAD_PRIORITY_BACKGROUND + android.os.Process.THREAD_PRIORITY_MORE_FAVORABLE
            )
            r.run()
        }, "hione-art").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + artDispatcher)
    internal val gate = Semaphore(4)
    @Volatile private var prewarmed = false

    data class PrecacheProgress(
        val active: Boolean = false,
        val processed: Int = 0,
        val total: Int = 0,
        val newlyCached: Int = 0
    )
    val precacheProgress = kotlinx.coroutines.flow.MutableStateFlow(PrecacheProgress())

    /**
     * Bumped when art arrives in the background (an online cover after a miss). Every
     * AlbumArtImage reads it, so rows that drew the placeholder load again.
     */
    var version by mutableIntStateOf(0)
        private set

    fun get(id: Long): ImageBitmap? = mem.get("$id")
    fun getHi(id: Long): ImageBitmap? = mem.get("$id#hi") ?: mem.get("$id")

    /**
     * Fast synchronous lookup: checks memory LRU first, then cached WebP on disk,
     * then sibling tracks on the same album. Prevents placeholder flash and pop-in
     * by delivering cached art on frame 1.
     */
    fun getSync(ctx: Context, id: Long): ImageBitmap? {
        mem.get("$id")?.let { return it }
        if (id <= 0 || isMiss("$id")) return null
        try {
            val f = thumbFile(ctx, id)
            if (f.exists() && f.length() > 0) {
                val b = BitmapFactory.decodeFile(f.absolutePath, thumbDecodeOptions())
                if (b != null) {
                    val img = b.asImageBitmap()
                    put("$id", img)
                    return img
                }
            }
            // Sibling fallback on frame 1: if another track on the same album is cached, use it immediately
            val sibs = AlbumScope.siblings(ctx, id, limit = 8)
            for ((sibId, _) in sibs) {
                val sm = mem.get("$sibId")
                if (sm != null) {
                    put("$id", sm)
                    return sm
                }
                val sf = thumbFile(ctx, sibId)
                if (sf.exists() && sf.length() > 0) {
                    val b = BitmapFactory.decodeFile(sf.absolutePath, thumbDecodeOptions())
                    if (b != null) {
                        val img = b.asImageBitmap()
                        put("$id", img)
                        put("$sibId", img)
                        runCatching { sf.copyTo(f, overwrite = true) }
                        return img
                    }
                }
            }
        } catch (_: Throwable) {}
        return null
    }

    internal fun put(key: String, b: ImageBitmap) { mem.put(key, b) }
    internal fun forget(id: Long) { mem.remove("$id"); mem.remove("$id#hi"); misses.remove("$id"); misses.remove("$id#hi") }
    /** Online art landed for these tracks: drop their misses and redraw. */
    internal fun onArrived(ids: List<Long>) {
        ids.forEach { misses.remove("$it"); misses.remove("$it#hi") }
        android.os.Handler(android.os.Looper.getMainLooper()).post { version++ }
    }
    internal fun isMiss(key: String): Boolean {
        val at = misses[key] ?: return false
        if (android.os.SystemClock.elapsedRealtime() - at > MISS_TTL_MS) { misses.remove(key); return false }
        return true
    }
    internal fun markMiss(key: String) { misses[key] = android.os.SystemClock.elapsedRealtime() }
    /** Forget every recorded miss — after a rescan, after storage access is granted, etc. */
    fun clearMisses() { misses.clear(); clearFolderArtIndex(); clearAlbumScope(); SharedCoverGuard.clear() }

    fun prewarm(ctx: Context, tracks: List<Track>, memWarm: Int = 60) {
        if (prewarmed || tracks.isEmpty()) return
        prewarmed = true
        val app = ctx.applicationContext
        scope.launch {
            val sample = tracks.take(memWarm)
            sample.forEach { tr ->
                try {
                    loadArtThumb(app, tr.id, tr.path)
                } catch (_: Throwable) {}
            }
            // Kick off complete library precaching in the background
            prewarmAll(app, tracks)
        }
    }

    /**
     * Exhaustive background precache across the entire library.
     * Iterates by unique album / directory, decodes once, and clones
     * the 320px WebP thumb to all sibling tracks so every track in
     * the library has instantaneous frame 1 art with zero pop-in.
     */
    fun prewarmAll(ctx: Context, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val app = ctx.applicationContext
        scope.launch {
            kotlinx.coroutines.delay(500L)
            val albumGroups = tracks.groupBy { tr ->
                val parentDir = tr.path.substringBeforeLast('/', "")
                if (parentDir.isNotBlank()) parentDir else "${tr.album}\u0000${tr.artist}"
            }
            val total = tracks.size
            precacheProgress.value = PrecacheProgress(active = true, processed = 0, total = total, newlyCached = 0)
            var count = 0
            var processed = 0

            for ((_, group) in albumGroups) {
                if (group.isEmpty()) continue
                val lead = group.first()
                val leadFile = thumbFile(app, lead.id)
                val leadReady = leadFile.exists() && leadFile.length() > 0
                val bmp = if (leadReady) {
                    null
                } else {
                    loadArtThumb(app, lead.id, lead.path, keepInMemory = true)
                }
                if (bmp != null) count++

                val srcFile = if (leadFile.exists() && leadFile.length() > 0) leadFile else null
                for (tr in group) {
                    processed++
                    if (tr.id != lead.id) {
                        try {
                            val tf = thumbFile(app, tr.id)
                            if (!tf.exists() || tf.length() == 0L) {
                                if (srcFile != null) {
                                    srcFile.copyTo(tf, overwrite = true)
                                    count++
                                }
                            }
                            if (bmp != null) put("${tr.id}", bmp)
                        } catch (_: Throwable) {}
                    }
                }

                if (processed % 20 == 0 || processed == total) {
                    precacheProgress.value = PrecacheProgress(
                        active = processed < total,
                        processed = processed,
                        total = total,
                        newlyCached = count
                    )
                    kotlinx.coroutines.delay(8L)
                }
            }
            precacheProgress.value = PrecacheProgress(active = false, processed = total, total = total, newlyCached = count)
        }
    }
}

@Composable
fun AlbumArtImage(
    trackId: Long,
    modifier: Modifier = Modifier,
    trackPath: String = "",
    contentScale: ContentScale = ContentScale.Crop,
    fallbackIcon: @Composable () -> Unit = {
        androidx.compose.foundation.Image(
            painter = mascotPainter(HioneArt.audiophile),
            contentDescription = "${Mascot.name} Art",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    },
    year: Int = 0
) {
    Box(modifier) {
        val artMod = Modifier.fillMaxSize()
        val artVersion = AlbumArtCache.version
        val ctx = LocalContext.current
        val cached = remember(trackId, artVersion) { AlbumArtCache.getSync(ctx, trackId) }
        if (cached != null) {
            Image(bitmap = cached, contentDescription = "Album art", modifier = artMod, contentScale = contentScale)
        } else {
            var art by remember(trackId) { mutableStateOf<ImageBitmap?>(null) }

            if (trackId > 0 && !AlbumArtCache.isMiss("$trackId")) {
                LaunchedEffect(trackId, trackPath, artVersion) {
                    art = loadArtThumb(ctx, trackId, trackPath)
                }
            }

            if (art != null) {
                Image(bitmap = art!!, contentDescription = "Album art", modifier = artMod, contentScale = contentScale)
            } else {
                Box(modifier = artMod.background(Mascot.color(0xFF0A2022)), contentAlignment = Alignment.Center) { fallbackIcon() }
            }
        }
        if (year in 1000..2999) AlbumArtYearBadge(year, Modifier.align(Alignment.BottomStart))
    }
}

/** The year badge itself: a small dark chip so it reads over any sleeve, light or dark. */
@Composable
private fun AlbumArtYearBadge(year: Int, modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(4.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
            .background(Mascot.color(0xCC121318))
            .padding(horizontal = 4.dp, vertical = 1.dp)
    ) {
        androidx.compose.material3.Text(
            text = year.toString(),
            color = HioneTealBright,
            fontSize = 9.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            maxLines = 1
        )
    }
}

/** Forget everything cached for one track (memory, disk, HUD copy), so the next load re-resolves. */
internal fun dropCachedArt(ctx: Context, id: Long) {
    AlbumArtCache.forget(id)
    runCatching { thumbFile(ctx, id).delete() }
    runCatching { hiresFile(ctx, id).delete() }
    runCatching { File(File(ctx.cacheDir, "hud"), "art_$id.jpg").delete() }
}

/**
 * Thumbs are LOSSY WebP now.
 *
 * They were lossless: 320px squares averaging 86KB and peaking at 200KB, 175MB on disk for 1.6k of
 * them. Lossless WebP is the slow decoder path, and the in-memory cache only holds ~160 of these
 * bitmaps, so a scroll through a 17k-track library was a stream of cache misses each paying an
 * 86KB lossless decode on the art pool before the row could show anything. That is "the album art
 * loads slowly every time" (2026-09-19). At q=88 the same thumb is 8 to 15KB and decodes several
 * times faster; on a 3.2 inch panel the difference is not visible. Hi-res stays lossless because it
 * is the full Now Playing stage and there is one of it.
 */
private fun writeWebp(bmp: Bitmap, f: File, lossless: Boolean = false) {
    try {
        f.outputStream().use {
            if (Build.VERSION.SDK_INT >= 30) {
                if (lossless) bmp.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, it)
                else bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, 88, it)
            } else @Suppress("DEPRECATION") bmp.compress(Bitmap.CompressFormat.WEBP, if (lossless) 100 else 88, it)
        }
    } catch (_: Throwable) { runCatching { f.delete() } }
}

/** Legacy lossless thumbs above this size get re-encoded lossy the first time they are read. */
private const val FAT_THUMB_BYTES = 40_000L

/**
 * Thumb decode options: RGB_565 halves the bitmap's memory (200KB instead of 400KB at 320px), so
 * the LruCache holds twice as many rows' art before it starts evicting. Album art at 16-bit on a
 * list row is indistinguishable from 32-bit at this size and density.
 */
private fun thumbDecodeOptions() = BitmapFactory.Options().apply {
    inPreferredConfig = Bitmap.Config.RGB_565
}

private fun decodeSampled(bytes: ByteArray, target: Int): Bitmap? {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    var s = 1
    while (opts.outWidth / s > target || opts.outHeight / s > target) s *= 2
    val o2 = BitmapFactory.Options().apply { inSampleSize = s }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o2)
}

/**
 * PERF (scroll jank, 2026-09-17): which file in a directory IS the cover was re-resolved for every
 * single track in that directory — up to 16 File.exists() stat()s on the SD card, plus a full
 * listFiles() with a regex over it, per track. An album of 15 tracks paid it 15 times for the
 * identical answer. Memoised per directory ("" = looked, found nothing). Cleared alongside the
 * art misses (AlbumArtCache.clearMisses) so a rescan or newly-granted storage access re-looks.
 */

// ============================================================ album scoping for art correctness
//
// Three failures Justin called out: art MISSING, art WRONG, and two different albums showing the
// SAME art. All three come from the two "whole folder / whole albumId" fallbacks being applied
// without checking that the folder or the albumId actually holds ONE album.
//
//  · WRONG/DUPED: a directory holding several albums (an artist folder, a compilation dump) has one
//    cover.jpg, and every track in it got that cover. Likewise MediaStore collapses albums that
//    share a title+artist into one albumId, so both of them get the first one's art.
//  · MISSING: when the embedded picture is absent and the folder/MediaStore fallbacks legitimately
//    have nothing, the track was marked a permanent-ish miss even though a SIBLING TRACK on the
//    same album usually has the picture embedded.
//
// The index below answers "is this directory one album?" and "is this albumId one album?" from the
// library the app already has in memory, and is dropped whenever a scan lands (clearMisses).
private object AlbumScope {
    @Volatile private var builtFor = 0
    private val dirSingleAlbum = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val albumIdSingle = java.util.concurrent.ConcurrentHashMap<Long, Boolean>()
    /** album key -> track ids on that album, so a sibling's embedded picture can be borrowed. */
    private val albumTracks = java.util.concurrent.ConcurrentHashMap<String, List<Pair<Long, String>>>()
    private val trackAlbumKey = java.util.concurrent.ConcurrentHashMap<Long, String>()
    /** id -> MediaStore albumId, so albumIdIsOneAlbum is a map lookup and not a 17k-track scan. */
    private val trackAlbumId = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    fun clear() {
        builtFor = 0; dirSingleAlbum.clear(); albumIdSingle.clear(); albumTracks.clear()
        trackAlbumKey.clear(); trackAlbumId.clear()
    }

    private fun key(t: Track): String {
        val who = t.albumArtist.ifBlank { t.artist }.trim().lowercase()
        return t.album.trim().lowercase() + "\u0000" + who
    }

    @Synchronized
    private fun build(ctx: Context) {
        // peek(), never loadSync(): this runs on the art threads during launch, and a disk
        // deserialization of the whole library from here would race the real load and double the
        // memory. If the library is not in memory yet, every answer below is "unknown" = allow.
        val lib = FastLibraryStore.peek() ?: return
        if (lib.isEmpty() || lib.size == builtFor) return
        val perDir = HashMap<String, MutableSet<String>>()
        val perAlbumId = HashMap<Long, MutableSet<String>>()
        val perAlbum = HashMap<String, MutableList<Pair<Long, String>>>()
        for (t in lib) {
            if (t.parentId != 0L) continue          // virtual cue cuts share their parent's art
            val k = key(t)
            trackAlbumKey[t.id] = k
            if (t.albumId != 0L) trackAlbumId[t.id] = t.albumId
            perAlbum.getOrPut(k) { mutableListOf() }.add(t.id to t.path)
            if (t.path.isNotBlank()) {
                val dir = t.path.substringBeforeLast('/', "")
                if (dir.isNotEmpty()) perDir.getOrPut(dir) { mutableSetOf() }.add(k)
            }
            if (t.albumId != 0L) perAlbumId.getOrPut(t.albumId) { mutableSetOf() }.add(k)
        }
        perDir.forEach { (d, ks) -> dirSingleAlbum[d] = ks.size <= 1 }
        perAlbumId.forEach { (id, ks) -> albumIdSingle[id] = ks.size <= 1 }
        perAlbum.forEach { (k, v) -> albumTracks[k] = v }
        builtFor = lib.size
    }

    /**
     * Is the folder holding [trackPath] a single-album folder?
     *
     * UNKNOWN (library not loaded, or a path the library has never seen) returns TRUE: that is the
     * old behavior, and refusing folder art whenever the index is cold would turn a wrongness
     * problem into a missing-art problem on every cold start.
     */
    fun dirIsOneAlbum(ctx: Context, trackPath: String): Boolean {
        build(ctx)
        val dir = trackPath.substringBeforeLast('/', "")
        if (dir.isEmpty()) return true
        return dirSingleAlbum[dir] ?: true
    }

    /** Does this MediaStore albumId map to exactly one real album? Unknown = true, as above. */
    fun albumIdIsOneAlbum(ctx: Context, trackId: Long): Boolean {
        build(ctx)
        // Was lib.firstOrNull { it.id == trackId }: a 17k-object scan PER ART LOAD, on four art
        // threads at once, during launch. Now two hash lookups.
        val albumId = trackAlbumId[trackId] ?: return true
        return albumIdSingle[albumId] ?: true
    }

    /** Other tracks on the same album, nearest first, for borrowing an embedded picture. */
    fun siblings(ctx: Context, trackId: Long, limit: Int = 4): List<Pair<Long, String>> {
        build(ctx)
        val k = trackAlbumKey[trackId] ?: return emptyList()
        return (albumTracks[k] ?: emptyList()).filter { it.first != trackId }.take(limit)
    }
}

internal fun clearAlbumScope() = AlbumScope.clear()

/** Borrow the embedded picture from another track on the same album. */
private fun siblingEmbeddedArt(ctx: Context, trackId: Long, target: Int): Bitmap? {
    for ((id, path) in AlbumScope.siblings(ctx, trackId)) {
        val mmr = MediaMetadataRetriever()
        try {
            if (path.isNotBlank() && File(path).exists()) mmr.setDataSource(path)
            else mmr.setDataSource(ctx, ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id))
            mmr.embeddedPicture?.let { if (!SharedCoverGuard.isSuspectBytes(it)) return decodeSampled(it, target) }
        } catch (_: Throwable) {
        } finally { try { mmr.release() } catch (_: Throwable) {} }
    }
    return null
}

private val folderArtIndex = java.util.concurrent.ConcurrentHashMap<String, String>()

internal fun clearFolderArtIndex() = folderArtIndex.clear()

internal fun resolveFolderArtFile(trackPath: String): File? {
    if (trackPath.isBlank()) return null
    val file = File(trackPath)
    val dir = file.parentFile ?: return null
    folderArtIndex[dir.path]?.let { return if (it.isEmpty()) null else File(it) }
    var found: File? = null
    try {
        if (dir.exists() && dir.isDirectory) {
            val candidates = listOf(
                "cover.jpg", "cover.jpeg", "cover.png", "cover.webp",
                "folder.jpg", "folder.jpeg", "folder.png", "folder.webp",
                "front.jpg", "front.jpeg", "front.png", "front.webp",
                "album.jpg", "album.jpeg", "album.png", "album.webp"
            )
            for (name in candidates) {
                val imgFile = File(dir, name)
                if (imgFile.exists() && imgFile.isFile && imgFile.length() > 0) { found = imgFile; break }
            }
            if (found == null) {
                val imageFiles = dir.listFiles { f ->
                    f.isFile && (f.extension.equals("jpg", true) || f.extension.equals("jpeg", true) ||
                                 f.extension.equals("png", true) || f.extension.equals("webp", true))
                }
                if (imageFiles != null) {
                    // "AlbumArt_{…}_Large.jpg", "cover.1.jpg", "Front Cover.png" — any obvious art name.
                    found = imageFiles.firstOrNull { FOLDER_ART_NAME_RE.containsMatchIn(it.name) }
                        ?: imageFiles.singleOrNull()
                }
            }
        }
    } catch (_: Throwable) { found = null }
    folderArtIndex[dir.path] = found?.absolutePath ?: ""
    return found
}

/**
 * Folder cover art, decoded DOWNSAMPLED. This used to be a bare `BitmapFactory.decodeFile` of the
 * full-size image: a 3000x3000 cover.jpg meant a 36 MB bitmap allocated (and then immediately
 * thrown away by the caller's rescale) for a 320px thumbnail — once per track, straight into the
 * GC churn that shows up as scroll stutter. [target] is the size the caller is about to scale to;
 * inSampleSize is chosen to stay at or above it on the SHORTER side, so the decoded image is never
 * softer than what the old full-size path produced.
 */
private fun findFolderArt(trackPath: String, target: Int = THUMB_PX): Bitmap? {
    val f = resolveFolderArtFile(trackPath) ?: return null
    if (!SharedCoverGuard.isSuspectFile(f)) return decodeSampledFile(f, target)
    // The folder's usual cover is a picture shared across many albums (see SharedCoverGuard).
    // Try the folder's other cover-named images ("cover (2).jpg", "front.png", ...), never
    // booklet pages or back covers.
    val dir = f.parentFile ?: return null
    val others = try {
        dir.listFiles { x -> x.isFile && x.path != f.path && FOLDER_ART_NAME_RE.containsMatchIn(x.name) }
    } catch (_: Throwable) { null } ?: return null
    for (o in others.sortedBy { it.name.lowercase() }) {
        if (!SharedCoverGuard.isSuspectFile(o)) return decodeSampledFile(o, target)
    }
    return null
}

private fun decodeSampledFile(f: File, target: Int): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        val short = minOf(bounds.outWidth, bounds.outHeight)
        if (short <= 0) return BitmapFactory.decodeFile(f.absolutePath)
        var s = 1
        while (short / (s * 2) >= target) s *= 2
        BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = s })
    } catch (_: Throwable) { null }
}

private val FOLDER_ART_NAME_RE = Regex("(?i)^(albumart|cover|folder|front|album)[^/]*\\.(jpe?g|png|webp)$")

/**
 * Folder art WITHOUT raw file access: scoped storage hides the SD card from java.io.File until
 * "All files access" is held, but MediaStore.Images has already indexed the cover.jpg sitting next
 * to the FLACs — so ask MediaStore for images in the track's directory and open them by URI.
 * (Confirmed live 2026-08-25: the app's manage-external-storage op was still "default", so every
 * album whose art lives only as a folder cover.jpg rendered the placeholder.)
 */
private fun findFolderArtViaMediaStore(ctx: Context, trackPath: String, target: Int = THUMB_PX): Bitmap? {
    if (trackPath.isBlank()) return null
    val dir = trackPath.substringBeforeLast('/', "")
    if (dir.isBlank()) return null
    try {
        val proj = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATA)
        val sel = "${MediaStore.Images.Media.DATA} LIKE ? AND ${MediaStore.Images.Media.DATA} NOT LIKE ?"
        val args = arrayOf("$dir/%", "$dir/%/%")
        ctx.contentResolver.safeQuery(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, sel, args, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            var best: Long = -1L; var bestRank = Int.MAX_VALUE; var count = 0; var only: Long = -1L
            while (c.moveToNext()) {
                count++
                val id = c.getLong(iId); only = id
                val name = c.getString(iName) ?: continue
                val lower = name.lowercase()
                val rank = when {
                    lower.startsWith("cover.") -> 0
                    lower.startsWith("folder.") -> 1
                    lower.startsWith("front") -> 2
                    lower.startsWith("album") -> 3
                    FOLDER_ART_NAME_RE.containsMatchIn(name) -> 4
                    else -> 9
                }
                if (rank < bestRank) { bestRank = rank; best = id }
            }
            val pick = if (best >= 0 && bestRank < 9) best else if (count == 1) only else -1L
            if (pick >= 0) {
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, pick)
                ctx.contentResolver.openInputStream(uri)?.use { ins ->
                    // PERF: was a full-size decodeStream — a 3000px folder cover became a 36 MB
                    // bitmap per track before the caller scaled it away. Same two-pass
                    // downsampling as decodeSampled (bytes, because a stream can't be re-read).
                    val bytes = ins.readBytes()
                    if (SharedCoverGuard.isSuspectBytes(bytes)) return null
                    return decodeSampled(bytes, target)
                }
            }
        }
    } catch (_: Throwable) {}
    return null
}

/** MediaProvider's own per-album art (content://media/external/audio/albumart/<albumId>) — built by
 *  the platform scanner from ANY track's embedded picture or the folder cover, with full file
 *  access we may not have. Last resort before giving up. */
private fun loadMediaStoreAlbumArt(ctx: Context, trackId: Long, target: Int = THUMB_PX): Bitmap? {
    try {
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, trackId)
        var albumId = -1L
        ctx.contentResolver.safeQuery(uri, arrayOf(MediaStore.Audio.Media.ALBUM_ID), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) albumId = c.getLong(0)
        }
        if (albumId <= 0) return null
        val artUri = ContentUris.withAppendedId(android.net.Uri.parse("content://media/external/audio/albumart"), albumId)
        // PERF: downsampled rather than a full-size decode — see findFolderArtViaMediaStore.
        ctx.contentResolver.openInputStream(artUri)?.use { ins -> return decodeSampled(ins.readBytes(), target) }
    } catch (_: Throwable) {}
    return null
}


private fun scaleTo(full: Bitmap, target: Int): Bitmap {
    val s = maxOf(1, maxOf(full.width, full.height) / target)
    return if (s > 1) Bitmap.createScaledBitmap(full, full.width / s, full.height / s, true) else full
}

/**
 * The one source order for both thumb and hi-res art, every step album-scoped:
 *  1. the track's embedded picture
 *  2. an image in the track's own folder, only when that folder holds one album
 *  3. MediaStore's thumbnail for the track (its fallback scans the parent folder for any .jpg,
 *     so it gets the same one-album-folder rule)
 *  4. MediaStore album art, only when that albumId is one album
 *  5. another track on the same album's embedded picture
 *  6. a cover downloaded by artist + album + year (OnlineAlbumArt), if one is on disk
 * A picture that SharedCoverGuard has seen on many different albums is skipped at every step,
 * and for an album whose own picture is that (tainted), steps 3 and 4 are skipped because
 * MediaStore built them from the same picture. When a tainted album ends up with nothing, an
 * online lookup starts in the background and the art redraws when it lands.
 */
private fun resolveArt(ctx: Context, trackId: Long, trackPath: String, target: Int): Bitmap? {
    SharedCoverGuard.init(ctx)
    var tainted = SharedCoverGuard.isTainted(ctx, trackId)
    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, trackId)
    var bmp: Bitmap? = null

    val mmr = MediaMetadataRetriever()
    try {
        if (trackPath.isNotBlank() && File(trackPath).exists()) mmr.setDataSource(trackPath)
        else mmr.setDataSource(ctx, uri)
        val pic = mmr.embeddedPicture
        // A flagged picture in the track itself also marks the album, even before the library
        // index is built.
        if (pic != null) {
            if (SharedCoverGuard.isSuspectBytes(pic)) tainted = true
            else bmp = decodeSampled(pic, target)
        }
    } catch (_: Throwable) {
    } finally { try { mmr.release() } catch (_: Throwable) {} }

    val folderOk = trackPath.isNotBlank() && AlbumScope.dirIsOneAlbum(ctx, trackPath)
    if (bmp == null && folderOk) bmp = findFolderArt(trackPath, target)?.let { scaleTo(it, target) }
    if (bmp == null && folderOk) bmp = findFolderArtViaMediaStore(ctx, trackPath, target)?.let { scaleTo(it, target) }

    if (bmp == null && !tainted && folderOk && Build.VERSION.SDK_INT >= 29) {
        try { bmp = ctx.contentResolver.loadThumbnail(uri, Size(target, target), null) } catch (_: Throwable) {}
    }
    if (bmp == null && !tainted && AlbumScope.albumIdIsOneAlbum(ctx, trackId)) {
        bmp = loadMediaStoreAlbumArt(ctx, trackId, target)?.let { scaleTo(it, target) }
    }
    if (bmp == null) bmp = siblingEmbeddedArt(ctx, trackId, target)

    if (bmp == null && tainted) {
        val info = SharedCoverGuard.albumFor(trackId)
        if (info != null) {
            bmp = OnlineAlbumArt.cached(ctx, info, target)?.let { scaleTo(it, target) }
            if (bmp == null) OnlineAlbumArt.request(ctx, info) { ids -> AlbumArtCache.onArrived(ids) }
        }
    }
    return bmp
}

/** Thumb pipeline: memory -> WebP disk -> [resolveArt]. */
suspend fun loadArtThumb(ctx: Context, trackId: Long, trackPath: String = "", keepInMemory: Boolean = true): ImageBitmap? =
    withContext(AlbumArtCache.artDispatcher) {
        AlbumArtCache.get(trackId)?.let { return@withContext it }
        if (AlbumArtCache.isMiss("$trackId")) return@withContext null
        // PERF: everything below is the expensive part (MediaMetadataRetriever on an SD-card FLAC,
        // full-size bitmap decode, rescale, WebP encode). Bounded to AlbumArtCache.gate's four
        // permits. Not applied to loadArtHiRes, which calls this function at the end and would
        // self-deadlock.
        AlbumArtCache.gate.withPermit {
        // Re-check after waiting for a permit: another coroutine may have loaded the very same
        // track meanwhile (a list row and the mini player commonly ask at once).
        AlbumArtCache.get(trackId)?.let { return@withContext it }
        if (AlbumArtCache.isMiss("$trackId")) return@withContext null
        try {
            thumbFile(ctx, trackId).takeIf { it.exists() }?.let { f ->
                BitmapFactory.decodeFile(f.absolutePath, thumbDecodeOptions())?.let { b ->
                    // A fat legacy lossless thumb gets re-saved lossy so its next read is fast.
                    if (f.length() > FAT_THUMB_BYTES) writeWebp(b, f)
                    val img = b.asImageBitmap()
                    if (keepInMemory) AlbumArtCache.put("$trackId", img)
                    return@withContext img
                }
            }
            val b = resolveArt(ctx, trackId, trackPath, THUMB_PX)
            if (b != null) {
                // Not to disk until the library index exists: before that the guard cannot tell
                // whether this album's picture belongs to another album.
                if (SharedCoverGuard.ready()) writeWebp(b, thumbFile(ctx, trackId))
                val img = b.asImageBitmap()
                if (keepInMemory) AlbumArtCache.put("$trackId", img)
                val tf = thumbFile(ctx, trackId)
                if (tf.exists() && tf.length() > 0) {
                    AlbumScope.siblings(ctx, trackId, limit = 16).forEach { (sibId, _) ->
                        val sf = thumbFile(ctx, sibId)
                        if (!sf.exists() || sf.length() == 0L) {
                            runCatching { tf.copyTo(sf, overwrite = true) }
                        }
                        if (keepInMemory) AlbumArtCache.put("$sibId", img)
                    }
                }
                img
            } else { AlbumArtCache.markMiss("$trackId"); null }
        } catch (_: Throwable) { null }
        }
    }

/** Hi-res pipeline for the full Now Playing stage: same sources and guards as the thumb. */
suspend fun loadArtHiRes(ctx: Context, trackId: Long, trackPath: String = ""): ImageBitmap? = withContext(AlbumArtCache.artDispatcher) {
    AlbumArtCache.getHi(trackId)?.let { if (it.width >= THUMB_PX + 1) return@withContext it }
    if (AlbumArtCache.isMiss("$trackId#hi")) return@withContext AlbumArtCache.get(trackId)
    try {
        hiresFile(ctx, trackId).takeIf { it.exists() }?.let { f ->
            BitmapFactory.decodeFile(f.absolutePath)?.let { b ->
                val img = b.asImageBitmap(); AlbumArtCache.put("$trackId#hi", img); return@withContext img
            }
        }
        val b = resolveArt(ctx, trackId, trackPath, HIRES_PX)
        if (b != null) {
            if (SharedCoverGuard.ready()) writeWebp(b, hiresFile(ctx, trackId), lossless = true)
            val img = b.asImageBitmap()
            AlbumArtCache.put("$trackId#hi", img)
            img
        } else { AlbumArtCache.markMiss("$trackId#hi"); loadArtThumb(ctx, trackId, trackPath) }
    } catch (_: Throwable) { AlbumArtCache.get(trackId) }
}
