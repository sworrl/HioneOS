package com.hione.player.booklet

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.hione.player.Track
import com.hione.player.loadArtThumb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The only art source that ships today: what is physically on disk next to the music.
 *
 * Looks in the album folder(s) and in conventional art subfolders (scans/, artwork/, booklet/,
 * covers/…) for cover-, front-, folder-, back-, inlay-, disc-/cd-, spine- prefixed files, multi-page booklet
 * images (booklet-01.jpg … sorted naturally) and *.pdf booklets (each PDF page becomes its own
 * page). If — and only if — no cover image exists on disk, the picture embedded in the audio file's
 * tags is offered as the front cover so the sleeve still has a face.
 */
class LocalFolderArtSource : AlbumArtSource {
    override val id: String = "local"

    override suspend fun discover(ctx: Context, query: AlbumArtQuery): AlbumArtPackage = withContext(Dispatchers.IO) {
        val folders = expandFolders(query.folders)
        if (folders.isEmpty()) return@withContext AlbumArtPackage.EMPTY

        val seen = HashSet<String>()
        val found = ArrayList<ArtPage>()

        for (dir in folders) {
            scanDir(dir, inArtSubfolder = false, depth = 0, seen = seen, out = found)
        }

        var pages = orderForPresentation(dedupeFronts(found))
        if (pages.size > MAX_PAGES) pages = pages.take(MAX_PAGES)

        // No cover image on disk at all → fall back to the tag-embedded picture, but only if it
        // actually decodes (loadArtThumb is the app's normal art pipeline; null means no art).
        if (pages.none { it.kind == ArtKind.FRONT } && query.representativeTrackId > 0L) {
            val ok = try { loadArtThumb(ctx, query.representativeTrackId, query.representativeTrackPath) != null } catch (_: Throwable) { false }
            if (ok) {
                pages = listOf(ArtPage(ArtKind.FRONT, ArtSourceRef.Embedded(query.representativeTrackId, query.representativeTrackPath), "Embedded cover")) + pages
            }
        }
        AlbumArtPackage(pages, folders)
    }

    // ------------------------------------------------------------------ folder walking

    private fun expandFolders(input: List<File>): List<File> {
        val out = LinkedHashSet<File>()
        for (f in input) {
            val dir = try { if (f.isDirectory) f else f.parentFile } catch (_: Throwable) { null } ?: continue
            if (!isReadableDir(dir)) continue
            out += dir
            // Multi-disc rips keep the shared booklet one level up: …/Album/CD1/01.flac → …/Album/
            if (DISC_FOLDER_RE.matches(dir.name.trim())) {
                dir.parentFile?.takeIf { isReadableDir(it) && !isStorageRoot(it) }?.let { out += it }
            }
        }
        return out.take(MAX_FOLDERS)
    }

    private fun isReadableDir(d: File): Boolean = try { d.exists() && d.isDirectory && d.canRead() } catch (_: Throwable) { false }

    private fun scanDir(dir: File, inArtSubfolder: Boolean, depth: Int, seen: HashSet<String>, out: MutableList<ArtPage>) {
        if (depth > MAX_DEPTH || out.size >= MAX_PAGES) return
        val entries = try { dir.listFiles() } catch (_: Throwable) { null } ?: return
        val subfolderLabel = if (inArtSubfolder) dir.name else null
        for (f in entries.sortedWith(NATURAL_FILE_ORDER)) {
            if (out.size >= MAX_PAGES) return
            val name = f.name
            if (name.startsWith(".")) continue
            if (f.isDirectory) {
                val lower = name.lowercase()
                // Art subfolders, and disc subfolders of an album root (CD1/, Disc 2/) which may
                // carry their own disc.jpg — but never descend into arbitrary folders.
                if (lower in ART_SUBFOLDERS || (inArtSubfolder && depth < MAX_DEPTH)) {
                    scanDir(f, inArtSubfolder = true, depth = depth + 1, seen = seen, out = out)
                } else if (!inArtSubfolder && depth == 0 && DISC_FOLDER_RE.matches(name.trim())) {
                    scanDir(f, inArtSubfolder = false, depth = depth + 1, seen = seen, out = out)
                }
                continue
            }
            if (!f.isFile) continue
            val canon = try { f.canonicalPath } catch (_: Throwable) { f.absolutePath }
            if (!seen.add(canon)) continue
            val ext = name.substringAfterLast('.', "").lowercase()
            when {
                ext in IMAGE_EXTS -> {
                    val len = try { f.length() } catch (_: Throwable) { 0L }
                    if (len < MIN_IMAGE_BYTES) continue          // stubs / broken downloads
                    val base = name.substringBeforeLast('.')
                    if (SMALL_THUMB_RE.containsMatchIn(base)) continue   // WMP's AlbumArtSmall etc.
                    val kind = classify(base, inArtSubfolder)
                    out += ArtPage(kind, ArtSourceRef.ImageFile(f), pageTitle(base, subfolderLabel))
                }
                ext == "pdf" -> {
                    val count = pdfPageCount(f)
                    if (count <= 0) continue
                    val base = name.substringBeforeLast('.')
                    val n = count.coerceAtMost(MAX_PDF_PAGES)
                    for (i in 0 until n) {
                        if (out.size >= MAX_PAGES) break
                        out += ArtPage(ArtKind.BOOKLET, ArtSourceRef.PdfPage(f, i, count), "$base · p${i + 1}")
                    }
                }
            }
        }
    }

    /** Page count without rendering anything; 0 if the PDF is unreadable / encrypted. */
    private fun pdfPageCount(f: File): Int {
        var pfd: ParcelFileDescriptor? = null
        var r: PdfRenderer? = null
        return try {
            pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
            r = PdfRenderer(pfd)
            r.pageCount
        } catch (_: Throwable) { 0 } finally {
            try { r?.close() } catch (_: Throwable) {}
            try { pfd?.close() } catch (_: Throwable) {}
        }
    }

    // ------------------------------------------------------------------ classification

    private fun classify(base: String, inArtSubfolder: Boolean): ArtKind {
        val n = base.lowercase().replace(Regex("[_\\-.]+"), " ").trim()
        return when {
            BACK_RE.containsMatchIn(n) && !FRONT_WORD_RE.containsMatchIn(n) -> ArtKind.BACK
            INLAY_RE.containsMatchIn(n) -> ArtKind.INLAY
            SPINE_RE.containsMatchIn(n) -> ArtKind.SPINE
            DISC_RE.containsMatchIn(n) -> ArtKind.DISC
            BOOKLET_RE.containsMatchIn(n) -> ArtKind.BOOKLET
            FRONT_RE.containsMatchIn(n) -> ArtKind.FRONT
            inArtSubfolder -> ArtKind.BOOKLET      // unnamed scans in scans/ are booklet pages
            else -> ArtKind.OTHER
        }
    }

    private fun pageTitle(base: String, subfolder: String?): String {
        val clean = base.replace(Regex("[_]+"), " ").trim()
        return if (subfolder != null) "$subfolder / $clean" else clean
    }

    /** Several front candidates (cover.jpg AND folder.jpg AND front.png) are usually the same
     *  picture saved thrice by different rippers — keep the best-ranked one, drop byte-identical
     *  twins, demote genuinely different extras to OTHER so they still show up. */
    private fun dedupeFronts(pages: List<ArtPage>): List<ArtPage> {
        val fronts = pages.filter { it.kind == ArtKind.FRONT }
        if (fronts.size <= 1) return pages
        val ranked = fronts.sortedBy { frontRank(it.title) }
        val keep = ranked.first()
        val keepLen = (keep.ref as? ArtSourceRef.ImageFile)?.file?.length() ?: -1L
        val out = ArrayList<ArtPage>(pages.size)
        for (p in pages) {
            if (p.kind != ArtKind.FRONT) { out += p; continue }
            if (p === keep) { out += p; continue }
            val len = (p.ref as? ArtSourceRef.ImageFile)?.file?.length() ?: -2L
            if (len == keepLen) continue          // same bytes → same picture
            out += p.copy(kind = ArtKind.OTHER)
        }
        return out
    }

    private fun frontRank(title: String): Int {
        val t = title.lowercase()
        return when {
            t.startsWith("cover") -> 0
            t.startsWith("front") -> 1
            t.startsWith("folder") -> 2
            t.startsWith("album") -> 3
            else -> 5
        }
    }

    companion object {
        private const val MAX_FOLDERS = 10
        private const val MAX_DEPTH = 2
        private const val MAX_PAGES = 400
        private const val MAX_PDF_PAGES = 200
        private const val MIN_IMAGE_BYTES = 2 * 1024L

        private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "bmp")
        private val ART_SUBFOLDERS = setOf("scans", "scan", "artwork", "art", "booklet", "booklets", "covers", "cover", "images", "img", "pics", "extras", "scans and covers")

        private val DISC_FOLDER_RE = Regex("(?i)^(cd|disc|disk|dvd|vol\\.?|volume|side)\\s*\\.?\\s*[0-9a-d]{1,3}$|^\\d{1,2}$")
        private val STORAGE_ROOT_NAMES = setOf("storage", "emulated", "0", "sdcard", "self", "media_rw", "mnt", "primary", "music", "")
        private val VOLUME_ID_RE = Regex("^[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}$")
        private fun isStorageRoot(f: File): Boolean =
            f.name.lowercase() in STORAGE_ROOT_NAMES || VOLUME_ID_RE.matches(f.name) || f.parentFile == null

        private val SMALL_THUMB_RE = Regex("(?i)albumartsmall|_small$|\\bthumb(nail)?s?\\b")
        private val BACK_RE = Regex("\\b(back|rear|backcover|tray card)\\b")
        private val FRONT_WORD_RE = Regex("\\bfront\\b")
        private val INLAY_RE = Regex("\\b(inlay|inside|tray|inner|digipa[ck]k?|gatefold|jewel)\\b")
        private val SPINE_RE = Regex("\\bspine\\b")
        private val DISC_RE = Regex("^(disc|disk|cd|dvd|media|label|blu ?ray|sacd|vinyl|side [ab12])\\b|\\b(disc|disk|cd|label)\\s*\\d{1,2}\\b|\\bmedia\\b")
        private val BOOKLET_RE = Regex("\\b(booklet|book|page|pg|scan|leaflet|insert|liner|lyrics?|obi|poster|sleeve|matrix|sticker|slipcase|box)\\b")
        private val FRONT_RE = Regex("^(front|cover|folder|album|albumart|art)\\b")

        /** Natural ("booklet-2" before "booklet-10") ordering of files, case-insensitive. */
        private val NATURAL_FILE_ORDER = Comparator<File> { a, b -> naturalCompare(a.name, b.name) }

        fun naturalCompare(a: String, b: String): Int {
            var i = 0; var j = 0
            val la = a.length; val lb = b.length
            while (i < la && j < lb) {
                val ca = a[i]; val cb = b[j]
                if (ca.isDigit() && cb.isDigit()) {
                    var ei = i; while (ei < la && a[ei].isDigit()) ei++
                    var ej = j; while (ej < lb && b[ej].isDigit()) ej++
                    val na = a.substring(i, ei).trimStart('0'); val nb = b.substring(j, ej).trimStart('0')
                    if (na.length != nb.length) return na.length - nb.length
                    val c = na.compareTo(nb); if (c != 0) return c
                    i = ei; j = ej
                } else {
                    val c = ca.lowercaseChar().compareTo(cb.lowercaseChar()); if (c != 0) return c
                    i++; j++
                }
            }
            return (la - i) - (lb - j)
        }

        private val KIND_ORDER = listOf(ArtKind.FRONT, ArtKind.BOOKLET, ArtKind.INLAY, ArtKind.DISC, ArtKind.SPINE, ArtKind.BACK, ArtKind.OTHER)

        /** Front → booklet pages (natural order) → inlay → disc(s) → spine → back → extras. */
        fun orderForPresentation(pages: List<ArtPage>): List<ArtPage> =
            pages.withIndex().sortedWith(compareBy<IndexedValue<ArtPage>> { KIND_ORDER.indexOf(it.value.kind) }
                .thenComparator { x, y -> naturalCompare(x.value.title, y.value.title) }
                .thenBy { it.index }).map { it.value }

        /** The album folder(s) an album's tracks live in — distinct parents, disc subfolders included. */
        fun foldersFor(tracks: List<Track>): List<File> {
            val out = LinkedHashSet<File>()
            for (t in tracks) {
                if (t.path.isBlank()) continue
                val parent = try { File(t.path).parentFile } catch (_: Throwable) { null } ?: continue
                out += parent
                if (out.size >= MAX_FOLDERS) break
            }
            return out.toList()
        }
    }
}
