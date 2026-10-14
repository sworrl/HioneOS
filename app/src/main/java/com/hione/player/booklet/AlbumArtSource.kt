package com.hione.player.booklet

import android.content.Context
import java.io.File

/**
 * What a piece of physical-package art IS — drives both the order pages are presented in (front
 * cover → booklet → inlay → disc → back, the way you'd actually leaf through a jewel case) and the
 * skeuomorphic chrome each page gets in the viewer (spine on a cover, hub hole on a disc, gutter
 * shadow on a booklet page).
 */
enum class ArtKind(val label: String) {
    FRONT("Front cover"),
    BOOKLET("Booklet"),
    INLAY("Inlay"),
    DISC("Disc"),
    BACK("Back cover"),
    SPINE("Spine"),
    OTHER("Extra art");
}

/** Where the pixels for one page come from. Every variant here is something that exists on this
 *  device right now — the viewer never invents art. */
sealed class ArtSourceRef {
    /** A plain image file (jpg/png/webp/bmp) in or under the album folder. */
    data class ImageFile(val file: File) : ArtSourceRef()

    /** One page of a PDF booklet, rendered lazily via android.graphics.pdf.PdfRenderer. */
    data class PdfPage(val file: File, val pageIndex: Int, val pageCount: Int) : ArtSourceRef()

    /** The picture embedded in the audio file's tags (APIC / FLAC PICTURE) — used ONLY as the front
     *  cover when the folder carries no cover image at all, so an album still has a "sleeve". */
    data class Embedded(val trackId: Long, val trackPath: String) : ArtSourceRef()

    // ---------------------------------------------------------------------------------------
    // Online auto-sourcing plug-in point (NOT implemented — separate roadmap item):
    //   data class Remote(val url: String, val cached: File?) : ArtSourceRef()
    // A remote AlbumArtSource would resolve `Remote` refs to a file under
    // BookletImageLoader.remoteCacheDir(ctx) and hand them back as ImageFile so the loader and
    // viewer stay unchanged. Keep the cache small (LRU by size), fetch on Wi-Fi only, never
    // block discovery on the network — local results must render first.
    // ---------------------------------------------------------------------------------------
}

/** One leaf of the package: a kind, where it comes from, and a short human title. */
data class ArtPage(val kind: ArtKind, val ref: ArtSourceRef, val title: String) {
    /** Stable identity for caches and pager keys. */
    val key: String = when (ref) {
        is ArtSourceRef.ImageFile -> "img:${ref.file.absolutePath}:${ref.file.length()}:${ref.file.lastModified()}"
        is ArtSourceRef.PdfPage -> "pdf:${ref.file.absolutePath}:${ref.file.length()}:${ref.file.lastModified()}#${ref.pageIndex}"
        is ArtSourceRef.Embedded -> "emb:${ref.trackId}:${ref.trackPath}"
    }
    val isPdf: Boolean get() = ref is ArtSourceRef.PdfPage
}

/** Everything discovered for one album, already in presentation order. */
data class AlbumArtPackage(val pages: List<ArtPage>, val foldersSearched: List<File>) {
    val isEmpty: Boolean get() = pages.isEmpty()
    val pdfCount: Int get() = pages.mapNotNull { (it.ref as? ArtSourceRef.PdfPage)?.file }.distinct().size
    val onlyEmbeddedCover: Boolean get() = pages.size == 1 && pages[0].ref is ArtSourceRef.Embedded
    fun count(kind: ArtKind): Int = pages.count { it.kind == kind }
    fun firstIndexOf(kind: ArtKind): Int = pages.indexOfFirst { it.kind == kind }

    companion object {
        val EMPTY = AlbumArtPackage(emptyList(), emptyList())
    }
}

/** What the viewer knows about the album it is asking for. Folders are the album's own directory
 *  (or directories — multi-disc sets spread over CD1/CD2 subfolders plus their parent). */
data class AlbumArtQuery(
    val folders: List<File>,
    val album: String,
    val artist: String,
    val representativeTrackId: Long = 0L,
    val representativeTrackPath: String = "",
) {
    val cacheKey: String get() = folders.joinToString("|") { it.absolutePath } + "#" + representativeTrackId
}

/**
 * A provider of physical-package art for an album.
 *
 * Today there is exactly one implementation, [LocalFolderArtSource], which reads what is actually
 * sitting next to the audio files. An online source (MusicBrainz Cover Art Archive, Discogs, fan
 * scan archives…) would implement this same interface and be registered in [AlbumArtSources];
 * the viewer, loader and entry button are all source-agnostic.
 */
interface AlbumArtSource {
    /** Short stable id, e.g. "local", "caa". */
    val id: String

    /** Runs on Dispatchers.IO. Must be cheap, must never throw, must only return art that exists. */
    suspend fun discover(ctx: Context, query: AlbumArtQuery): AlbumArtPackage
}

/**
 * Registry + merge point. Sources are consulted in order; a later source may only ADD pages of
 * kinds the earlier ones did not provide (so a downloaded back cover never shadows the user's own
 * scan). Results are memoised briefly so re-opening an album screen doesn't re-list the folder.
 */
object AlbumArtSources {
    private val sources: List<AlbumArtSource> = listOf(
        LocalFolderArtSource(),
        // RemoteCoverArtSource(),   ← online auto-sourcing plugs in here (see ArtSourceRef.Remote)
    )

    private const val TTL_MS = 3 * 60_000L
    private val cache = android.util.LruCache<String, Pair<Long, AlbumArtPackage>>(48)

    /** Non-blocking peek at a recent result (for the album-screen button's first frame). */
    fun peek(query: AlbumArtQuery): AlbumArtPackage? {
        val hit = cache.get(query.cacheKey) ?: return null
        return if (android.os.SystemClock.elapsedRealtime() - hit.first < TTL_MS) hit.second else null
    }

    fun invalidate() { cache.evictAll() }

    suspend fun discover(ctx: Context, query: AlbumArtQuery): AlbumArtPackage {
        peek(query)?.let { return it }
        var merged = AlbumArtPackage.EMPTY
        for (src in sources) {
            val part = try { src.discover(ctx.applicationContext, query) } catch (_: Throwable) { AlbumArtPackage.EMPTY }
            merged = if (merged.isEmpty) part else merge(merged, part)
        }
        cache.put(query.cacheKey, android.os.SystemClock.elapsedRealtime() to merged)
        return merged
    }

    private fun merge(base: AlbumArtPackage, extra: AlbumArtPackage): AlbumArtPackage {
        if (extra.isEmpty) return base
        val have = base.pages.map { it.kind }.toSet()
        val keys = base.pages.map { it.key }.toSet()
        val added = extra.pages.filter { it.kind !in have && it.key !in keys }
        if (added.isEmpty()) return base
        return AlbumArtPackage(
            pages = LocalFolderArtSource.orderForPresentation(base.pages + added),
            foldersSearched = (base.foldersSearched + extra.foldersSearched).distinct()
        )
    }
}
