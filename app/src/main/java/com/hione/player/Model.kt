package com.hione.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * Ghost-row guard for MediaStore rows that outlived the actual file (deletes/moves done outside
 * the scanner) — but only reliable for the PRIMARY storage volume. Confirmed live on-device
 * (2026-08-17, `adb shell run-as com.hione.player test -e <sd-card-path>` → "MISSING" on a file
 * that genuinely exists and that MediaStore itself correctly lists) that `java.io.File.exists()`
 * cannot see removable/secondary storage (SD cards) at all from this app's process on API 33+
 * without MANAGE_EXTERNAL_STORAGE — scoped storage blocks raw filesystem access to anything
 * outside the app's own sandbox and primary external storage; only MediaStore/ContentResolver
 * (a privileged system service) can still see removable media. Applying the ghost check
 * unconditionally there was silently dropping THOUSANDS of perfectly real SD-card tracks from
 * the library (reported live: MediaStore had ~5970 audio rows on the SD card, the app surfaced
 * only ~4000) — worse than the rare stale-ghost-row case this check exists to catch. So: verify
 * via File I/O only for paths under primary storage (where it's reliable); trust MediaStore's own
 * row for everything else rather than risk mass-dropping a removable-media library.
 */
fun mediaStoreRowLikelyValid(path: String): Boolean {
    if (path.isBlank()) return true
    // With MANAGE_EXTERNAL_STORAGE granted (see requestAllFilesAccessIfNeeded in MainActivity),
    // java.io.File works reliably on every volume — verify everywhere, which is what actually
    // catches real ghost rows. Without it, only primary storage is checkable; anything else falls
    // back to trusting MediaStore's row rather than mass-dropping a removable-media library.
    if (android.os.Build.VERSION.SDK_INT >= 30 && android.os.Environment.isExternalStorageManager()) {
        return java.io.File(path).exists()
    }
    val primaryRoot = android.os.Environment.getExternalStorageDirectory()?.absolutePath
    if (primaryRoot == null || !path.startsWith(primaryRoot)) return true // not on primary storage — can't verify via File I/O, trust MediaStore
    return java.io.File(path).exists()
}

data class Track(
    val id: Long, val title: String, val artist: String, val album: String,
    val durationMs: Long, val sizeBytes: Long, val bitrateKbps: Int, val mime: String,
    val path: String = "", val year: Int = 0, val albumId: Long = 0L, val trackNumber: Int = 0,
    val albumArtist: String = "", val dateAddedSec: Long = 0L,
    /** Disc number for multi-disc sets (0 = unknown/single disc). MediaStore packs it as
     *  disc*1000+track in the TRACK column; kept separately so disc order survives grouping. */
    val discNumber: Int = 0,
    /** Whole-CD image rip (one file = the entire disc). See DiscImage. */
    val isDiscImage: Boolean = false,
    /** Sibling .cue sheet for a disc image, "" if none. */
    val cuePath: String = "",
    /** For a VIRTUAL track cut from a disc image: the image's MediaStore id (0 = a real file). */
    val parentId: Long = 0L,
    /** Virtual track clip window inside the parent image (ms); clipEndMs 0 = to the end. */
    val clipStartMs: Long = 0L,
    val clipEndMs: Long = 0L,
)

data class ArtistGroup(val name: String, val tracks: List<Track>) {
    // PERF (scroll jank, 2026-09-17): these were `get()` accessors, i.e. a fresh O(tracks) pass
    // (plus, for albumCount, two whole intermediate lists) on EVERY read. The artist list row
    // reads albumCount in its subtitle, so a fling re-walked a prolific artist's entire track
    // list once per row bind, on the main thread, purely to render "N albums". `tracks` is an
    // immutable val, so the answer can never change for a given group — compute it once, lazily,
    // and hand back the same value forever. PUBLICATION mode: these are read from the UI thread
    // and from background grouping/media-session code, and a duplicate race-computation is
    // harmless (same input, same answer) whereas a lock per read is not free.
    val albumCount: Int by lazy(LazyThreadSafetyMode.PUBLICATION) {
        tracks.mapTo(HashSet<String>()) { it.album }.size
    }
    /** Most recent add-time across the artist's tracks — an artist reads as "new" from the moment
     *  its newest track landed, not its oldest. */
    val dateAddedSec: Long by lazy(LazyThreadSafetyMode.PUBLICATION) {
        tracks.maxOfOrNull { it.dateAddedSec } ?: 0L
    }
}

/** The track whose art represents this artist wherever a single piece of cover art is shown
 *  (list rows, hero headers, "Newly Added" cards) — the user's own pin if they've set one and
 *  it's still in this artist's tracks, else just the first track (the old, arbitrary default).
 *  Keyed on canonicalArtistKey(), NOT the raw display name — `name` here is already
 *  formatArtistDisplayName()'d (PREFIX/SUFFIX/STRIP "The" placement), which changes if the user
 *  ever flips the "sort ignoring The" display mode; a pin keyed on that mutable display string
 *  would silently stop matching (and leak an orphaned prefs entry) the moment they did. */
fun ArtistGroup.coverTrack(ctx: android.content.Context): Track? {
    val pinnedId = PlayerPreferences.loadArtistCoverTrack(ctx, canonicalArtistKey(name, ctx))
    if (pinnedId != null) tracks.firstOrNull { it.id == pinnedId }?.let { return it }
    return tracks.firstOrNull()
}

data class AlbumGroup(val name: String, val artist: String, val tracks: List<Track>) {
    // Same PERF change as ArtistGroup above: every album grid tile reads hasDiscImage and
    // unsplitImageCount while binding, and each of these was a full O(tracks) pass per read.
    /** True when this album is (at least partly) a whole-CD image rip. */
    val hasDiscImage: Boolean by lazy(LazyThreadSafetyMode.PUBLICATION) { tracks.any { it.isDiscImage } }
    /** Cue-less images still shown as ONE file (no real track list). */
    val unsplitImageCount: Int by lazy(LazyThreadSafetyMode.PUBLICATION) {
        tracks.count { it.isDiscImage && it.parentId == 0L }
    }
    /** Number of physical disc images behind this album (multi-file ".1/.2" images count each). */
    val discImageFiles: Int by lazy(LazyThreadSafetyMode.PUBLICATION) {
        tracks.filter { it.isDiscImage }.map { if (it.parentId != 0L) it.parentId else it.id }.distinct().size
    }
    /** Earliest year present on the album (0 if none) — used to order albums chronologically. */
    val year: Int by lazy(LazyThreadSafetyMode.PUBLICATION) {
        tracks.mapNotNull { it.year.takeIf { y -> y > 0 } }.minOrNull() ?: 0
    }
    /** Most recent add-time across the album's tracks (same reasoning as ArtistGroup above). */
    val dateAddedSec: Long by lazy(LazyThreadSafetyMode.PUBLICATION) {
        tracks.maxOfOrNull { it.dateAddedSec } ?: 0L
    }
}

/**
 * "New" indicator prominence for a track/album/artist, per the exact rule requested: subtle
 * within 7 days of being added, LESS subtle (louder) within the first 24 hours, and MORE subtle
 * (quieter) once the item has actually been played — the freshness signal fades once it's served
 * its purpose. Nothing shown past the 7-day window.
 */
object Newness {
    private const val DAY_SEC = 86_400L
    const val WINDOW_SEC = 7 * DAY_SEC

    /** True while an item is still inside the 7-day "new" window — the same cutoff `prominence`
     *  itself uses, exposed separately for callers (e.g. Home's "Newly Added" section) that just
     *  need a yes/no filter rather than a prominence value. */
    fun isNew(dateAddedSec: Long, nowSec: Long): Boolean =
        dateAddedSec > 0L && (nowSec - dateAddedSec) in 0..WINDOW_SEC

    /** 0f = show nothing; otherwise a 0..1 prominence driving the indicator's alpha/size/pulse. */
    fun prominence(dateAddedSec: Long, nowSec: Long, played: Boolean): Float {
        if (dateAddedSec <= 0L) return 0f
        val age = nowSec - dateAddedSec
        if (age < 0 || age > WINDOW_SEC) return 0f
        val boosted = age <= DAY_SEC
        return when {
            boosted && !played -> 1.0f    // brand new, untouched — the loudest state
            boosted && played -> 0.35f    // brand new but already played — dampened
            !played -> 0.55f              // within the week, unplayed — the baseline "subtle"
            else -> 0.16f                 // within the week AND already played — as quiet as it gets
        }
    }
}

// Real single-artist names that would otherwise be false-positively torn apart by the
// collaboration-delimiter splitting below, because the ACTUAL name happens to contain a word or
// symbol also used as a collab separator elsewhere. No generic rule can tell "Denzel Curry and
// Kingpin Skinny Pimp" (a collab tag, should split) apart from "Fear, and Loathing in Las Vegas"
// (one real band's actual name, must NOT split) — found by manually auditing the on-device
// library, where both of these were confirmed genuinely mis-split. Checked case-insensitively,
// so add more here as they turn up rather than trying to out-clever the regex.
private val NO_SPLIT_ARTIST_NAMES = setOf(
    "author & punisher",
    "fear, and loathing in las vegas"
)

// All artist-normalization regexes are hoisted to top-level vals — compiled ONCE, not on every
// call. These run per-track (thousands of times) inside a synchronous `remember{}` grouping pass
// on the main thread; recompiling a handful of regexes (plus java.text.Normalizer) per call on
// every track was slow enough on a 3000+ track library to trip Android's ANR watchdog and make
// the app fail to launch — verified live, this is not a hypothetical.
private val PAREN_FEAT_RE = Regex("(?i)\\s*[\\[\\(](feat|ft|featuring)\\b.*?[\\)\\]]")
private val ARTIST_SPLIT_RE =
    Regex("(?i)\\s*(?:\\bfeat\\.?\\b|\\bft\\.?\\b|\\bfeaturing\\b|\\bwith\\b|\\band\\b|\\bw/|[&/+,;|]|\\bx\\b|\u00d7|\\bvs\\.?\\b|[–—])\\s*")
private val DISC_FOLDER_RE = Regex("(?i)^(cd|disc|disk|vol\\.?|volume)\\s*\\.?\\s*\\d{1,3}$|^\\d{1,2}$")
private val YEAR_ALBUM_RE = Regex("^(19|20)\\d{2}\\s*[-–—:].+")
private val YEAR_ONLY_RE = Regex("^(?:19|20)\\d{2}(?:[-/]\\d{2,4})?$|^\\[(?:19|20)\\d{2}\\]$|^\\((?:19|20)\\d{2}\\)$")
private val TRAILING_BRACKET_RE = Regex("\\s*[\\[(][^\\])]*[\\])]\\s*$")
private val TRAILING_THE_RE = Regex("(?i),?\\s+the$")
private val COMBINING_MARKS_RE = Regex("\\p{Mn}+")

// Characters bands use as STAND-INS FOR LETTERS, folded to the letter they are drawn as.
//
// This is the difference between a name being findable and not. "$uicide Boy$" went through the
// non-letter strip below, which turned every "$" into a space, and keyed as "uicide boy" - the
// leading S simply gone. Searching "Suicide Boys" matched nothing, and a second rip tagged
// "$uicideboy$" keyed differently again, so the same band sat in the library as two artists
// neither of which could be found by typing their name.
//
// DIGITS ARE DELIBERATELY NOT IN HERE. Folding 0->o, 1->i, 3->e and so on would also rewrite
// "Blink-182", "2 Unlimited", "Eiffel 65" and "3 Doors Down", where the digits ARE the name.
// Leetspeak that leans on digits stays unfolded; that is the conservative half of the trade and
// it is better than inventing an artist called "Blink-IBZ". Only glyphs that are never part of a
// real name on their own are listed, so folding them cannot destroy information.
private val STYLIZED_LETTER_FOLD: Map<Char, String> = mapOf(
    '$' to "s", '\u00a7' to "s",                              // $, section sign
    '@' to "a",
    '\u20ac' to "e", '\u00a2' to "c",                         // euro, cent
    '\u00a3' to "l", '\u00a5' to "y",                         // pound, yen
    '\u0192' to "f", '\u00b5' to "u",                         // florin, micro
    '\u00f8' to "o", '\u00d8' to "o",                         // slashed o - not decomposable by NFD
    '\u00df' to "ss", '\u00e6' to "ae", '\u00c6' to "ae",     // eszett, ash
    '\u0153' to "oe", '\u0152' to "oe",                       // ligature oe
    '\u00fe' to "th", '\u00f0' to "d", '\u0111' to "d",      // thorn, eth, d-stroke
    '\u0142' to "l", '\u0141' to "l",                         // l-stroke
    '\u2020' to "t", '\u2021' to "t",                         // dagger, double dagger
    '\u00a9' to "c", '\u00ae' to "r",                         // copyright, registered
    '\u0394' to "a", '\u2206' to "a",                         // deltas, drawn as an A
)

// Glyphs that stand in for a letter ONLY in the middle of a word, and are ordinary punctuation
// at either end. "P!nk" is a letter; "Snap!" and "Ballyhoo!" are not, and both are real artists
// in the library this was tested against - folding these unconditionally turned them into
// "snapi" and "ballyhooi". So they fold only with a letter on BOTH sides.
// Not '|': ARTIST_SPLIT_RE already treats it as a collaboration separator ("A|B"), and the split
// runs before this fold, so a pipe never reaches here. Not accented letters either - those are
// real letters and NFD handles them a few lines down.
private val INNER_ONLY_FOLD: Map<Char, String> = mapOf(
    '!' to "i", '\u00a1' to "i",
)

// UPPERCASE Cyrillic and Greek letters that are drawn like Latin capitals, which is how a lot of
// band names get their look: "NIИ" is Nine Inch Nails with a reversed N, and keyed as "ni" before
// this because the mixed-script branch below simply deleted anything non-ASCII.
//
// Only uppercase, and only applied when the name ALSO has ASCII letters in it. Lowercase Cyrillic
// is not a homoglyph of anything Latin, and a genuinely Russian or Greek name has no ASCII in it
// at all - those must keep going to the transliteration store untouched rather than being mangled
// into a lookalike spelling here.
private val HOMOGLYPH_FOLD: Map<Char, String> = mapOf(
    '\u0418' to "n", '\u042f' to "r",                          // И, Я - reversed N and R
    '\u0410' to "a", '\u0412' to "b", '\u0415' to "e",         // А, В, Е
    '\u041a' to "k", '\u041c' to "m", '\u041d' to "h",         // К, М, Н
    '\u041e' to "o", '\u0420' to "p", '\u0421' to "c",         // О, Р, С
    '\u0422' to "t", '\u0423' to "y", '\u0425' to "x",         // Т, У, Х
    '\u0391' to "a", '\u0392' to "b", '\u0395' to "e",         // Alpha, Beta, Epsilon
    '\u0396' to "z", '\u0397' to "h", '\u0399' to "i",         // Zeta, Eta, Iota
    '\u039a' to "k", '\u039c' to "m", '\u039d' to "n",         // Kappa, Mu, Nu
    '\u039f' to "o", '\u03a1' to "p", '\u03a4' to "t",         // Omicron, Rho, Tau
    '\u03a7' to "x",                                            // Chi
)

private fun foldStylizedLetters(s: String): String {
    val needsPlain = s.any { STYLIZED_LETTER_FOLD.containsKey(it) }
    val needsInner = s.any { INNER_ONLY_FOLD.containsKey(it) }
    if (!needsPlain && !needsInner) return s
    val sb = StringBuilder(s.length + 4)
    for ((i, c) in s.withIndex()) {
        val plain = STYLIZED_LETTER_FOLD[c]
        if (plain != null) { sb.append(plain); continue }
        val inner = INNER_ONLY_FOLD[c]
        if (inner != null &&
            i > 0 && i < s.length - 1 &&
            s[i - 1].isLetter() && s[i + 1].isLetter()) {
            sb.append(inner)
            continue
        }
        sb.append(c)
    }
    return sb.toString()
}

/** Latin-lookalike capitals, folded only for a name that is already mostly ASCII. See above. */
private fun foldHomoglyphs(s: String): String {
    if (s.none { HOMOGLYPH_FOLD.containsKey(it) }) return s
    if (s.none { it.code < 128 && it.isLetter() }) return s
    val sb = StringBuilder(s.length)
    for (c in s) sb.append(HOMOGLYPH_FOLD[c] ?: c.toString())
    return sb.toString()
}

private val ALL_SPACE_RE = Regex("\\s+")
private val NON_LETTER_DIGIT_RE = Regex("[^\\p{L}\\p{Nd}\\s]")
private val WHITESPACE_RUN_RE = Regex("\\s+")

private val normalizeArtistCache = java.util.concurrent.ConcurrentHashMap<String, String>()

fun normalizeArtistName(rawArtist: String): String {
    normalizeArtistCache[rawArtist]?.let { return it }
    val res = normalizeArtistNameUncached(rawArtist)
    normalizeArtistCache[rawArtist] = res
    return res
}

private fun normalizeArtistNameUncached(rawArtist: String): String {
    if (rawArtist.isBlank()) return "Unknown Artist"
    if (rawArtist.trim().lowercase() in NO_SPLIT_ARTIST_NAMES) return rawArtist.trim()

    // 1. Remove parenthetical feature tags e.g. (feat. XYZ), [ft. ABC]
    var clean = PAREN_FEAT_RE.replace(rawArtist, "")

    // 2. Split on collaboration delimiters: feat., ft., featuring, with, and, &, /, +, x, vs.,
    val parts = clean.split(ARTIST_SPLIT_RE).filter { it.isNotBlank() }

    if (parts.isNotEmpty()) {
        clean = parts[0].trim()
    }

    // 3. Trim extra quotes, hyphens, and whitespace
    clean = clean.trim(' ', '"', '\'', '-', ',')

    return clean.ifBlank { rawArtist.trim() }
}

// Folder names that mean "you've walked off the top of the music tree" — a storage volume root
// (exFAT/FAT volume ids like "EAFF-98FE"), the emulated-storage scaffolding, or a mount point.
// Confirmed live (2026-08-25): an album folder dropped FLAT at the SD root ("Music/2 Unlimited -
// Get Ready (1992) (FLAC)/…") walked past the generic "Music" folder and returned the volume id
// "EAFF-98FE" as the ARTIST — hundreds of tracks landed under a phantom artist named after the
// card. Path inference must stop dead here, never climb above it.
private val STORAGE_ROOT_NAMES = setOf("storage", "emulated", "0", "sdcard", "self", "media_rw", "mnt", "primary", "external_sd", "sdcard1", "")
private val VOLUME_ID_RE = Regex("^[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}$")
private fun isStorageRootFolder(f: java.io.File): Boolean {
    val n = f.name.trim()
    return n.lowercase() in STORAGE_ROOT_NAMES || VOLUME_ID_RE.matches(n) || f.parentFile == null
}

// Scene-release / ingest-staging folder names are NOT artist names: "1988-2022 Lords Of Acid",
// "(1998) Eiffel 65 - Blue (First Press)", "2.Unlimited-Get.Ready-(BYTE.5006)-CDS-FLAC-1991-WRE",
// "[24bit 96kHz Vinyl Rip]". Strip the leading year/year-range decoration first; if what's left is
// empty, or the name carries codec/format/source tokens, it's a release folder and yields no artist.
private val LEADING_YEAR_DECOR_RE = Regex("^[\\[(]?(?:19|20)\\d{2}(?:\\s*[-–—/]\\s*(?:19|20)?\\d{2,4})?[\\])]?\\s*[-–—:.]?\\s*")
private val SCENE_TOKEN_RE = Regex("(?i)(?:\\b(?:flac|alac|mp3|aac|ogg|opus|wav|dsd|dsf|dff|web|cds?|cdm|cdr|rip|vinyl|remaster(?:ed)?|bootleg|lossless|hi-?res|scene|proper|repack|retail|promo)\\b|\\b\\d{1,2}\\s*-?\\s*bit\\b|\\b\\d{2,3}(?:\\.\\d)?\\s*k(?:hz)?\\b|\\b24-?(?:44|48|88|96|176|192)\\b|\\b16-?44\\b|[\\[(][^\\])]*[\\])])")
private fun cleanArtistCandidate(raw: String): String? {
    var c = raw.trim()
    c = LEADING_YEAR_DECOR_RE.replace(c, "").trim()
    if (c.isBlank()) return null
    // Flat "Artist - Album…" names: only the ARTIST half is a candidate.
    if (c.contains(" - ")) c = c.substringBefore(" - ").trim()
    c = TRAILING_BRACKET_RE.replace(c, "").trim().trim('-', '_', '.', ' ')
    if (c.isBlank() || c.length < 2) return null
    if (YEAR_ONLY_RE.matches(c)) return null
    if (c.lowercase() in GENERIC_FOLDER_NAMES) return null
    // Dotted scene names ("2.Unlimited-Get.Ready.For.This-(BYTE.5006)-CDS-FLAC-1991-WRE") and
    // anything still carrying format/source tags are release names, not artists.
    if (SCENE_TOKEN_RE.containsMatchIn(c)) return null
    if (c.count { it == '.' } >= 2 && !c.contains(' ')) return null
    return c
}

private val GENERIC_FOLDER_NAMES = setOf(
    "music", "download", "downloads", "audio", "sdcard", "internal storage", "0", "media",
    "files", "unknown", "albums", "album", "recordings", "recording", "tracks", "songs",
    "singles", "single", "eps", "ep", "discography", "discographies", "author", "artists", "artist",
    "flac", "mp3", "lossless", "hires", "hi-res", "cd", "cds", "vinyl", "cassette"
)

// PERF: the cache used to be keyed on "$path::$albumTitle", i.e. one entry (and one full
// parent-folder walk with regex matching) PER TRACK — on an 11k-track library that is 11k File
// walks per grouping pass, and .artists()/.albums() each run one. The uncached implementation
// below reads nothing but `path`'s PARENT FOLDER (albumTitle is accepted for source compatibility
// but never referenced), so every track in the same album folder must produce the same answer.
// Keyed on the parent directory now: same results, ~1 walk per album folder instead of per track.
private val inferPathCache = java.util.concurrent.ConcurrentHashMap<String, String>()

fun inferAlbumArtistFromPath(path: String, albumTitle: String): String? {
    if (path.isBlank()) return null
    val cacheKey = path.substringBeforeLast('/', path)
    inferPathCache[cacheKey]?.let { return if (it.isEmpty()) null else it }
    val res = inferAlbumArtistFromPathUncached(path, albumTitle)
    inferPathCache[cacheKey] = res ?: ""
    return res
}

private fun inferAlbumArtistFromPathUncached(path: String, albumTitle: String): String? {
    try {
        val file = java.io.File(path)
        var curr = file.parentFile ?: return null

        // 1. Walk past disc/volume subfolders (CD 1, Disc 2, etc.)
        var hops = 0
        while (hops < 4 && DISC_FOLDER_RE.matches(curr.name.trim())) {
            curr = curr.parentFile ?: return null
            hops++
        }
        if (isStorageRootFolder(curr)) return null

        // Now `curr` is the Album folder (e.g. "1999 - The Gift Of Game [JP]")
        val albumFolder = curr
        var artistFolder = albumFolder.parentFile

        // Walk past generic intermediate container folders (e.g. "Albums", "Discography", "Singles", "EP")
        // — but NEVER past a storage root: "Music" directly under the card root means the album folder
        // sits flat at the top of the tree and there simply is no artist folder to read.
        hops = 0
        while (artistFolder != null && hops < 4 && !isStorageRootFolder(artistFolder) &&
            artistFolder.name.trim().lowercase() in GENERIC_FOLDER_NAMES) {
            artistFolder = artistFolder.parentFile
            hops++
        }
        if (artistFolder != null && isStorageRootFolder(artistFolder)) artistFolder = null

        // 2. Check artistFolder (e.g. "Crazy Town - Discography 1999-2015 [FLAC]" or "Crazy Town")
        if (artistFolder != null) {
            val aName = artistFolder.name.trim()
            if (aName.lowercase() !in GENERIC_FOLDER_NAMES && aName.length > 1 && !YEAR_ALBUM_RE.matches(aName)) {
                cleanArtistCandidate(aName)?.let { return it }
            }
        }

        // 3. Check if the albumFolder itself is "Artist - Album" (e.g. "Crazy Town - The Gift of Game")
        val pName = albumFolder.name.trim()
        if (pName.contains(" - ") && !YEAR_ALBUM_RE.matches(pName)) {
            cleanArtistCandidate(pName)?.let { return it }
        }
    } catch (_: Throwable) {}
    return null
}

object ArtistTransliterationStore {
    private var file: java.io.File? = null
    private val cache = HashMap<String, String>()
    @Volatile private var loaded = false

    @Synchronized private fun ensure(ctx: android.content.Context) {
        if (loaded) return
        file = java.io.File(ctx.filesDir, "artist_trans.json")
        runCatching {
            val f = file!!
            if (f.exists()) {
                val o = org.json.JSONObject(f.readText())
                o.keys().forEach { k -> cache[k] = o.getString(k) }
            }
        }
        loaded = true
    }

    @Volatile private var lastPersistAtMs = 0L

    // getEnglishName() calls persist() on every single new cache entry — on a library with K
    // distinct non-cached artist strings (the whole first grouping pass on a multi-script library),
    // that used to mean K full-cache-serialize-and-rewrite calls, each one writing a file that's
    // grown by one more entry than the last: O(K²) total. Throttled to at most once per 2s here —
    // this cache is a regenerable transliteration lookup (cheap ICU call), so worst case losing the
    // last couple of seconds of new entries to a process death is a non-issue, and it's a much
    // better trade than blocking the grouping pass with dozens of rewrites of a growing file.
    @Synchronized private fun persist() {
        val f = file ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastPersistAtMs < 2000L) return
        runCatching {
            val o = org.json.JSONObject()
            cache.forEach { (k, v) -> o.put(k, v) }
            f.writeText(o.toString())
            lastPersistAtMs = now
        }
    }

    fun getEnglishName(ctx: android.content.Context?, rawArtist: String): String {
        if (ctx == null) return ""
        val trimmed = rawArtist.trim()
        if (trimmed.isBlank()) return ""
        ensure(ctx)

        synchronized(this) {
            if (cache.containsKey(trimmed)) {
                return cache[trimmed] ?: ""
            }
        }

        // Check if string contains non-Latin / CJK / Japanese / Cyrillic / Extended characters
        val hasNonLatin = trimmed.any { c ->
            val code = c.code
            code > 0x024F || (code in 0x00C0..0x017F)
        }

        if (!hasNonLatin) {
            synchronized(this) {
                cache[trimmed] = ""
                persist()
            }
            return ""
        }

        val transliterated = try {
            val trans = android.icu.text.Transliterator.getInstance("Any-Latin; Title")
            val res = trans.transliterate(trimmed).trim()
            if (res.equals(trimmed, ignoreCase = true)) "" else res
        } catch (_: Throwable) {
            ""
        }

        synchronized(this) {
            cache[trimmed] = transliterated
            persist()
        }
        return transliterated
    }
}

// Confirmed source-tag typos/variant spellings, found by manually auditing the on-device library
// (e.g. "Breaking Bejamin" vs "Breaking Benjamin" — 12 tracks tagged with the typo). Hardcoded
// and hand-verified rather than fuzzy-matched: fuzzy string matching here would risk silently
// merging genuinely different artists with similar names. Keys are the fully-normalized form
// (lowercase, punctuation-stripped, whitespace-collapsed) — add more here as they're found.
private val ARTIST_KEY_ALIASES = mapOf(
    "breaking bejamin" to "breaking benjamin",
    "the beastie boys" to "beastie boys",
    "beastie boys the" to "beastie boys"
)

// canonicalArtistKey does several regex passes + Unicode normalization + (for non-Latin names) an
// ArtistTransliterationStore lookup — real work, not free. .artists() below calls it 3-4 times per
// TRACK just to compare/group the same handful of candidate strings (tag artist, album artist,
// path-inferred artist), and the exact same string values get recomputed yet again by the
// trailing .groupBy. On a small library that's noise; on a large one (tens of thousands of
// tracks) it's real, avoidable CPU — memoized here so every caller benefits, not just .artists().
private val canonicalArtistKeyCache = java.util.concurrent.ConcurrentHashMap<String, String>()

fun canonicalArtistKey(artistName: String, ctx: android.content.Context? = null): String {
    canonicalArtistKeyCache[artistName]?.let { return it }
    val result = canonicalArtistKeyUncached(artistName, ctx)
    canonicalArtistKeyCache[artistName] = result
    return result
}

private fun canonicalArtistKeyUncached(artistName: String, ctx: android.content.Context? = null): String {
    var clean = normalizeArtistName(artistName).trim()
    if (clean.startsWith("the ", ignoreCase = true)) {
        clean = clean.substring(4).trim()
    }
    // Also strip a TRAILING ", The" / " The" (a common alternate/"sort name" convention — some
    // rips tag "Crystal Method, The" or "Crystal Method The" instead of "The Crystal Method").
    // Without this, that variant keyed as "...method the" and never merged with the prefixed form.
    clean = TRAILING_THE_RE.replace(clean, "").trim()
    clean = clean.trim('.', '!', '?', '-', ',', '"', '\'', ' ')
    // Resolve Latin-lookalike capitals first, or the ASCII-only filter below deletes them and
    // "NIИ" becomes "NI". Only touches names that already contain ASCII letters.
    clean = foldHomoglyphs(clean)
    if (clean.any { it.code > 127 }) {
        // If the tag already mixes non-Latin script with a human-provided Latin reading (e.g.
        // "花冷え hanabie" vs a plain "花冷え" elsewhere), trust that existing Latin text rather
        // than re-transliterating the whole mixed string — ICU run on the mixed form doesn't
        // reliably land on the same output as ICU run on the pure-CJK form, which was the
        // difference between those two staying as separate groups.
        val asciiOnly = clean.filter { it.code in 32..126 }.trim()
        if (asciiOnly.any { it.isLetter() }) {
            clean = asciiOnly
        } else {
            // Transliterate non-Latin CJK (Japanese, Chinese, Korean, Cyrillic) to Latin so
            // Japanese & English spellings merge into one group.
            val latin = ArtistTransliterationStore.getEnglishName(ctx, clean)
            if (latin.isNotBlank()) {
                clean = latin.trim().trim('.', '!', '?', '-', ',', '"', '\'', ' ')
            }
        }
    }
    // Fold accents (é -> e) so differently-tagged rips of the same artist (with/without
    // diacritics is a common inconsistency across sources) collapse to one key. Normalizer.
    // normalize can throw IllegalArgumentException on malformed/unpaired UTF-16 surrogates —
    // plausible in a library with scene-release/bootleg tags — and every OTHER risky call in this
    // file is defensively wrapped except this one was not. Root-caused live (2026-08-17): this
    // being unguarded meant one bad artist string anywhere in the library could throw uncaught out
    // of the whole eager .artists() flatMap/groupBy/map chain, and since MainActivity's
    // LaunchedEffect around that call had no try/catch either, artistGroups silently froze on
    // whatever it last computed successfully — "The Crystal Method" (and its whole SD-card tree)
    // never appeared as a searchable artist because the entire grouping pass had stopped updating
    // well before that content was even scanned in, not because of any actual grouping-logic bug.
    clean = runCatching { COMBINING_MARKS_RE.replace(java.text.Normalizer.normalize(clean, java.text.Normalizer.Form.NFD), "") }.getOrDefault(clean)
    // Strip everything that isn't a letter/digit/space (smart quotes, stray punctuation, zero-
    // width/invisible characters some taggers inject) and collapse whitespace runs to one space —
    // "Denzel  Curry" (double space) and "Denzel Curry" must key identically, and currently didn't.
    // Fold letter-substitute glyphs BEFORE the strip below, which would otherwise turn them
    // into spaces and lose the letter: "$uicide Boy$" keyed as "uicide boy" until this existed.
    clean = foldStylizedLetters(clean)
    clean = WHITESPACE_RUN_RE.replace(NON_LETTER_DIGIT_RE.replace(clean, " ").trim(), " ").lowercase()
    return ARTIST_KEY_ALIASES[clean] ?: clean
}

/**
 * The key for deciding two tags are the SAME artist, and for matching a typed search.
 *
 * It is [canonicalArtistKey] with the spaces taken out, because how a stylised name is broken
 * into words is not a fact about the artist: the same band is tagged "$uicide Boy$",
 * "$uicideboy$" and "Suicide Boys" across different rips, and all three have to land in one
 * place and be reachable by typing any of them. Dropping spaces is what makes the first two
 * agree with the third.
 *
 * Kept SEPARATE from canonicalArtistKey rather than replacing it, because that one is persisted:
 * liked artists and pinned artist covers are stored under it, and changing its output would
 * quietly orphan every entry a user already has. See [legacyArtistKey].
 */
private val artistMatchKeyCache = java.util.concurrent.ConcurrentHashMap<String, String>()

fun artistMatchKey(artistName: String, ctx: android.content.Context? = null): String {
    artistMatchKeyCache[artistName]?.let { return it }
    val out = ALL_SPACE_RE.replace(canonicalArtistKey(artistName, ctx), "")
    if (artistMatchKeyCache.size < 50_000) artistMatchKeyCache[artistName] = out
    return out
}

/**
 * What [canonicalArtistKey] produced before stylised letters were folded, for reading old data.
 *
 * A user who hearted "$uicide Boy$" has "uicide boy" written to their preferences. The fix
 * changes that key to "suicide boys", so anything that READS a persisted artist key has to try
 * this form too or the heart silently disappears. Writes always use the current key, so stored
 * data migrates as it is touched and this fallback goes quiet on its own.
 */
fun legacyArtistKey(artistName: String, ctx: android.content.Context? = null): String {
    var clean = normalizeArtistName(artistName).trim()
    if (clean.startsWith("the ", ignoreCase = true)) clean = clean.substring(4).trim()
    clean = TRAILING_THE_RE.replace(clean, "").trim()
    clean = clean.trim('.', '!', '?', '-', ',', '"', '\'', ' ')
    // Resolve Latin-lookalike capitals first, or the ASCII-only filter below deletes them and
    // "NIИ" becomes "NI". Only touches names that already contain ASCII letters.
    clean = foldHomoglyphs(clean)
    if (clean.any { it.code > 127 }) {
        val asciiOnly = clean.filter { it.code in 32..126 }.trim()
        if (asciiOnly.any { it.isLetter() }) clean = asciiOnly
    }
    clean = runCatching {
        COMBINING_MARKS_RE.replace(
            java.text.Normalizer.normalize(clean, java.text.Normalizer.Form.NFD), "")
    }.getOrDefault(clean)
    clean = WHITESPACE_RUN_RE.replace(NON_LETTER_DIGIT_RE.replace(clean, " ").trim(), " ").lowercase()
    return ARTIST_KEY_ALIASES[clean] ?: clean
}

/**
 * Fold any free text the way a search box should compare it: stylised letters resolved to the
 * letters they are drawn as, accents dropped, punctuation and spacing ignored entirely.
 *
 * Spacing has to go for the same reason as in [artistMatchKey] - "Suicide Boys" has to find
 * "$uicideboy$" - and once spacing is gone from the haystack it has to go from the needle too,
 * so both sides run through this.
 */
fun searchFold(text: String): String {
    if (text.isEmpty()) return text
    var t = foldStylizedLetters(text)
    t = runCatching {
        COMBINING_MARKS_RE.replace(
            java.text.Normalizer.normalize(t, java.text.Normalizer.Form.NFD), "")
    }.getOrDefault(t)
    return ALL_SPACE_RE.replace(NON_LETTER_DIGIT_RE.replace(t, " "), "").lowercase()
}

// Stable album identity for anything that needs to persist an album reference across sessions
// (liked-albums, taste affinity) — didn't exist anywhere before this. Album display names/artists
// are just formatted strings (AlbumGroup.name/.artist), the same kind of unstable identity that
// caused both the LazyGrid key-collision crash (two albums formatting to the same display string)
// and the artist-like desync (raw display name changing when the "ignore The" display setting
// flips). Built the same way: artist half goes through the existing canonicalArtistKey, album half
// gets the same accent-fold/punctuation-strip treatment, both guarded against the same
// Normalizer-on-malformed-surrogates crash class already fixed once in canonicalArtistKeyUncached.
private val canonicalAlbumKeyCache = java.util.concurrent.ConcurrentHashMap<String, String>()

fun canonicalAlbumKey(artist: String, album: String, ctx: android.content.Context? = null): String {
    // Cached, because LikeStore.likeOrigin asks for this from every heart in every list row on
    // every recomposition since album likes started inheriting to tracks (2026-09-17). Unicode
    // normalization plus three regex passes per row per frame is not free on this SoC.
    val cacheKey = artist + "\u0001" + album
    canonicalAlbumKeyCache[cacheKey]?.let { return it }
    val out = canonicalAlbumKeyUncached(artist, album, ctx)
    if (canonicalAlbumKeyCache.size < 50_000) canonicalAlbumKeyCache[cacheKey] = out
    return out
}

private fun canonicalAlbumKeyUncached(artist: String, album: String, ctx: android.content.Context?): String {
    var clean = album.trim().trim('.', '!', '?', '-', ',', '"', '\'', ' ')
    clean = runCatching { COMBINING_MARKS_RE.replace(java.text.Normalizer.normalize(clean, java.text.Normalizer.Form.NFD), "") }.getOrDefault(clean)
    clean = WHITESPACE_RUN_RE.replace(NON_LETTER_DIGIT_RE.replace(clean, " ").trim(), " ").lowercase()
    return "${canonicalArtistKey(artist, ctx)}::$clean"
}

fun formatArtistDisplayName(rawName: String, mode: String, englishName: String = ""): String {
    val trimmed = rawName.trim()
    val isThe = trimmed.startsWith("The ", ignoreCase = true)
    val baseDisplay = if (isThe) {
        val baseName = trimmed.substring(4).trim()
        when (mode) {
            "SUFFIX" -> "$baseName, The"
            "STRIP" -> baseName
            else -> "The $baseName"
        }
    } else trimmed

    return if (englishName.isNotBlank() && !englishName.equals(trimmed, ignoreCase = true)) {
        "$baseDisplay ($englishName)"
    } else {
        baseDisplay
    }
}

// PERF: formatArtistSortKey is a genuinely expensive key builder (Unicode NFD normalize + three
// regex passes + a possible ICU transliteration), and every caller reaches it through
// `sortedBy { formatArtistSortKey(...) }`. Kotlin's sortedBy is `sortedWith(compareBy(selector))`,
// which evaluates the selector ON EVERY COMPARISON — so sorting N names cost ~2·N·log2(N) full
// key builds instead of N (≈22x over on ~2000 artists, ≈27x over on an 11k-track song list).
// Memoized on (ignoreThe, rawName) so the work collapses back to one build per distinct name; the
// returned key is byte-identical, so sort order is unchanged. Same shape as canonicalArtistKeyCache
// above (ctx only feeds ArtistTransliterationStore, which is itself a process-wide cache).
private val artistSortKeyCache = java.util.concurrent.ConcurrentHashMap<String, String>()

fun formatArtistSortKey(rawName: String, ignoreThe: Boolean, ctx: android.content.Context? = null): String {
    val cacheKey = if (ignoreThe) "1\u0000$rawName" else "0\u0000$rawName"
    artistSortKeyCache[cacheKey]?.let { return it }
    val result = formatArtistSortKeyUncached(rawName, ignoreThe, ctx)
    artistSortKeyCache[cacheKey] = result
    return result
}

private fun formatArtistSortKeyUncached(rawName: String, ignoreThe: Boolean, ctx: android.content.Context? = null): String {
    var key = rawName.trim()
    if (ignoreThe) {
        if (key.startsWith("the ", ignoreCase = true)) key = key.substring(4).trim()
        // Also ignore a trailing ", The"/" The" sort-name convention (mirrors canonicalArtistKey)
        // — otherwise a group whose chosen display happened to be the suffixed form would sort
        // under "T" while every "The X"-prefixed neighbor sorted under "X".
        key = TRAILING_THE_RE.replace(key, "").trim()
    }
    // If the name has English in parentheses e.g. "花冷え。 (Hanabie)", or starts with non-ASCII:
    // extract the Latin transliteration so it sorts under 'H', not '#' at the bottom!
    if (key.contains("(") && key.contains(")")) {
        val insideParen = key.substringAfter("(").substringBefore(")").trim()
        if (insideParen.isNotBlank() && insideParen.any { it in 'a'..'z' || it in 'A'..'Z' }) {
            key = insideParen
        }
    } else if (key.any { it.code > 127 }) {
        // Same mixed-script preference as canonicalArtistKey: if the name already carries a
        // human-provided Latin reading alongside non-Latin script (no parens this time, e.g.
        // "花冷え hanabie"), sort on that rather than a fresh ICU transliteration of the whole
        // mixed string, which doesn't reliably match the pure-CJK form's transliteration.
        val asciiOnly = key.filter { it.code in 32..126 }.trim()
        key = if (asciiOnly.any { it.isLetter() }) {
            asciiOnly
        } else {
            ArtistTransliterationStore.getEnglishName(ctx, key).ifBlank { key }
        }
    }
    // Fold accents and collapse whitespace/punctuation noise the same way the grouping key does —
    // two artists that now MERGE into one group only sort consistently if this matches; it also
    // keeps standalone (ungrouped) names from landing in a slightly-off position relative to
    // their plain-ASCII neighbors just because of a stray accent or double space.
    // Same defensive wrap as canonicalArtistKeyUncached — Normalizer.normalize can throw on
    // malformed/unpaired UTF-16 surrogates, and this call was the other unguarded one that let a
    // single bad string abort the whole eager sortedBy() pass. See that function's comment for the
    // full story (this is the fix for the live-confirmed "Crystal Method missing from Artists" bug).
    key = runCatching { COMBINING_MARKS_RE.replace(java.text.Normalizer.normalize(key, java.text.Normalizer.Form.NFD), "") }.getOrDefault(key)
    key = WHITESPACE_RUN_RE.replace(NON_LETTER_DIGIT_RE.replace(key, " ").trim(), " ")
    return key.lowercase()
}

/** True for any of the several spellings a "no real artist tag" ends up as by the time it
 *  reaches here: MediaStore's own placeholder, queryTracks()'s null-cursor fallback text, or
 *  normalizeArtistName's blank-input sentinel. Used to be a single exact string compare against
 *  ONE of these — missed the others, so genuinely untagged-but-well-organized-by-folder tracks
 *  (verified live: 57 tracks, e.g. a whole Godsmack album) got dumped into one catch-all "artist"
 *  instead of falling through to path inference like they should have. */
private fun isBlankArtistTag(s: String): Boolean {
    val t = s.trim().lowercase()
    return t.isEmpty() || t == "unknown" || t == "unknown artist" || t == "<unknown>" || YEAR_ONLY_RE.matches(t)
}

fun List<Track>.artists(
    ctx: android.content.Context? = null,
    ignoreThe: Boolean = true,
    displayMode: String = "PREFIX"
): List<ArtistGroup> =
    // Multi-membership: the tag artist always counts (when it's a real one); album_artist and the
    // folder-inferred artist ALSO count as separate memberships when they canonicalize to
    // something different — so a DJ-mix-album track keeps showing under its own credited artist
    // AND under the compiling artist's page (verified live: Orbital's & P.O.D.'s tracks on The
    // Crystal Method's "Community Service" mix CD now correctly show under both). This used to be
    // a strict priority chain (tag, else album_artist, else path) instead — safer against phantom
    // duplicate artists from a wrong path guess, but it meant a compilation/mix track could ONLY
    // ever show under its own tag, never under the album it's actually part of. The phantom-
    // duplicate risk that priority-chaining was protecting against is handled at the source now
    // (inferAlbumArtistFromPath's disc-folder/year-album/bracket-annotation fixes above), so it's
    // safe to let all three signals contribute again. Album grouping ([albums]) is unaffected —
    // it already prefers album_artist/path independently of this.
    let { all ->
    // Every canonical artist key that a real TAG (artist / album_artist) vouches for. The folder-
    // inferred name may only ADD a membership when it resolves to one of these — it can confirm a
    // compilation's compiling artist, it can never invent a brand-new artist out of a folder name.
    // (Untagged tracks are the one exception below: with no tag at all, the folder is all we have.)
    val knownKeys = HashSet<String>(all.size / 4 + 16)
    for (tr in all) {
        val a = normalizeArtistName(tr.artist)
        if (!isBlankArtistTag(a)) knownKeys.add(canonicalArtistKey(a, ctx))
        val aa = tr.albumArtist.trim()
        if (aa.isNotBlank()) { val n = normalizeArtistName(aa); if (!isBlankArtistTag(n)) knownKeys.add(canonicalArtistKey(n, ctx)) }
    }
    all.flatMap { tr ->
        val list = mutableListOf<Pair<String, Track>>()
        val art = normalizeArtistName(tr.artist)
        val artBlank = isBlankArtistTag(art)
        if (!artBlank) list.add(art to tr)

        val albArtRaw = tr.albumArtist.trim()
        val albArt = if (albArtRaw.isNotBlank()) normalizeArtistName(albArtRaw) else ""
        val albArtBlank = isBlankArtistTag(albArt)
        if (!albArtBlank && (artBlank || canonicalArtistKey(albArt, ctx) != canonicalArtistKey(art, ctx))) {
            list.add(albArt to tr)
        }

        val pathArtist = inferAlbumArtistFromPath(tr.path, tr.album)
        if (!pathArtist.isNullOrBlank()) {
            val normPathArt = normalizeArtistName(pathArtist)
            if (!isBlankArtistTag(normPathArt)) {
                val pathKey = canonicalArtistKey(normPathArt, ctx)
                val untagged = artBlank && albArtBlank
                val confirmsKnown = pathKey in knownKeys
                if ((untagged || confirmsKnown) &&
                    (artBlank || pathKey != canonicalArtistKey(art, ctx)) &&
                    (albArtBlank || pathKey != canonicalArtistKey(albArt, ctx))
                ) {
                    list.add(normPathArt to tr)
                }
            }
        }
        if (list.isEmpty()) list.add("Unknown Artist" to tr)
        list
    }
    }
    // Group on the spaceless key, so the same band tagged "$uicide Boy$", "$uicideboy$" and
    // "Suicide Boys" across three rips is ONE artist instead of three. The display name is still
    // chosen by majority vote below, so the group is labelled however most of the files spell it.
    .groupBy { (artistName, _) -> artistMatchKey(artistName, ctx) }
    .map { (_, pairs) ->
        // PERF: was a sortedWith whose comparator lowercased album+title on every comparison; the
        // keys are built once per track now (see sortArtistGroupTracks). Same stable order.
        val tracks = sortArtistGroupTracks(pairs.map { it.second }.distinctBy { it.id })
        val rawDisplay = pairs.map { it.first }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: tracks.first().artist
        val englishName = ArtistTransliterationStore.getEnglishName(ctx, rawDisplay)
        val formattedDisplay = formatArtistDisplayName(rawDisplay, displayMode, englishName)
        ArtistGroup(formattedDisplay, tracks)
    }
    .sortedBy { formatArtistSortKey(it.name, ignoreThe, ctx) }

// PERF: this comparator used to allocate inside every COMPARISON — two `String.lowercase()` calls
// plus a `java.io.File(path)` just to read its name, per compare, i.e. O(n log n) throwaway objects
// (this is a large slice of the allocation churn that showed up as ~30% combined GC-thread CPU at
// cold start). Decorate-sort-undecorate: each key is built exactly ONCE per track and the sort then
// only compares precomputed fields. `path.substringAfterLast('/')` is what `File(path).name`
// returns for these paths, and the sort is stable either way, so the resulting order is identical.
private class AlbumTrackSortKey(
    val disc: Int,
    val no: Int,
    val fileName: String,
    val title: String,
    val track: Track
)

fun sortAlbumTracks(tracks: List<Track>): List<Track> {
    val decorated = ArrayList<AlbumTrackSortKey>(tracks.size)
    for (tr in tracks) {
        val title = tr.title.lowercase()
        decorated.add(
            AlbumTrackSortKey(
                if (tr.discNumber > 0) tr.discNumber else 1,
                if (tr.trackNumber > 0) tr.trackNumber else Int.MAX_VALUE,
                // Fallback to filename if track numbers are missing or identical
                if (tr.path.isNotBlank()) tr.path.substringAfterLast('/').lowercase() else title,
                title,
                tr
            )
        )
    }
    decorated.sortWith(
        compareBy<AlbumTrackSortKey> { it.disc }.thenBy { it.no }.thenBy { it.fileName }.thenBy { it.title }
    )
    return decorated.map { it.track }
}

/** Same decorate-sort-undecorate treatment for [artists]' per-group track ordering (album, then
 *  track number, then title) — the old inline comparator lowercased album + title on every compare. */
private class ArtistTrackSortKey(
    val album: String,
    val no: Int,
    val title: String,
    val track: Track
)

private fun sortArtistGroupTracks(tracks: List<Track>): List<Track> {
    val decorated = ArrayList<ArtistTrackSortKey>(tracks.size)
    for (tr in tracks) {
        decorated.add(
            ArtistTrackSortKey(
                tr.album.lowercase(),
                if (tr.trackNumber > 0) tr.trackNumber else Int.MAX_VALUE,
                tr.title.lowercase(),
                tr
            )
        )
    }
    decorated.sortWith(
        compareBy<ArtistTrackSortKey> { it.album }.thenBy { it.no }.thenBy { it.title }
    )
    return decorated.map { it.track }
}

// A real album name essentially never starts with a bare leading track number ("01 This And
// That", "04. Some Song") — that pattern is the signature of a bad rip/tag write where the
// ALBUM field ended up holding a filename instead of the actual album title. Caught live: albums
// list polluted with these as their own bogus one-off "albums".
private val ALBUM_LOOKS_LIKE_FILENAME_RE = Regex("^(\\d{1,3})[\\s.\\-_]+")

/** Best-effort clean album name from a parent folder when the tag itself looks broken — strips a
 *  leading "Artist - " prefix (common flat-folder naming) and any trailing "(Year) [tags]" junk,
 *  same idea as the artist path-inference logic already applied elsewhere. */
private fun albumNameFromFolder(path: String): String? {
    if (path.isBlank()) return null
    val parent = java.io.File(path).parentFile?.name ?: return null
    var name = parent.trim()
    if (DISC_FOLDER_RE.matches(name)) {
        // A disc-number subfolder holds no album name of its own — climb one more level.
        name = java.io.File(path).parentFile?.parentFile?.name?.trim() ?: return null
    }
    if (" - " in name) name = name.substringAfter(" - ")
    name = TRAILING_BRACKET_RE.replace(name, "").trim()
    return name.takeIf { it.isNotBlank() && !ALBUM_LOOKS_LIKE_FILENAME_RE.containsMatchIn(it) }
}

// PERF: was recomputed per TRACK inside .albums()' groupBy — a File allocation, a parent walk with
// a regex per hop, and an absolutePath/lowercase per call, ~11k times per pass. The answer depends
// only on the track's parent directory, so memoize on that: ~1 walk per album folder.
private val albumFolderKeyCache = java.util.concurrent.ConcurrentHashMap<String, String>()

/** The folder an album's files live in, with any "CD 1"/"Disc 2" subfolder collapsed away — so a
 *  multi-disc set keyed by folder is ONE album, not one per disc. */
private fun albumFolderKey(path: String): String {
    if (path.isBlank()) return ""
    val dirKey = path.substringBeforeLast('/', path)
    albumFolderKeyCache[dirKey]?.let { return it }
    val computed = albumFolderKeyUncached(path)
    albumFolderKeyCache[dirKey] = computed
    return computed
}
private fun albumFolderKeyUncached(path: String): String {
    var dir = java.io.File(path).parentFile ?: return path.lowercase()
    var hops = 0
    while (hops < 3 && DISC_FOLDER_RE.matches(dir.name.trim())) { dir = dir.parentFile ?: break; hops++ }
    return dir.absolutePath.lowercase()
}

// PERF: NFD normalize + three regex passes, previously run once per TRACK in .albums()' groupBy
// even though a library's ~1.5k distinct album titles repeat across every track on the album.
// Memoized on the raw album string — same output, one build per distinct title.
private val albumTitleKeyCache = java.util.concurrent.ConcurrentHashMap<String, String>()

/** Normalized album-title half of an identity key: copy-suffix stripped ("[2132]", "(1)", "copy"),
 *  accent-folded, punctuation-stripped, lowercase. */
private fun albumTitleKey(album: String): String =
    albumTitleKeyCache.getOrPut(album) { albumTitleKeyUncached(album) }
private fun albumTitleKeyUncached(album: String): String {
    var clean = ALBUM_COPY_SUFFIX_RE.replace(album.trim(), "").trim().trim('.', '!', '?', '-', ',', '"', '\'', ' ')
    clean = runCatching { COMBINING_MARKS_RE.replace(java.text.Normalizer.normalize(clean, java.text.Normalizer.Form.NFD), "") }.getOrDefault(clean)
    return WHITESPACE_RUN_RE.replace(NON_LETTER_DIGIT_RE.replace(clean, " ").trim(), " ").lowercase()
}

fun List<Track>.albums(
    ctx: android.content.Context? = null,
    ignoreThe: Boolean = true
): List<AlbumGroup> =
    // Album identity is TAG-first now, folder-second. The old key was purely the parent folder,
    // which is exactly what an ingest pipeline dumping releases into flat/scene-named/per-disc
    // folders breaks: one boxset became ten "albums" (one per "Disc N" folder), duplicate copies in
    // "[2132]"-suffixed folders became separate tiles, and a single album split across two drops
    // showed up twice. Now:
    //   • album tag + album_artist tag present → key = albumArtist :: albumTitle (folder-independent;
    //     a release tag on the folder — "[Vinyl 24-192]" vs "[Master]" — is appended so two
    //     different pressings of the same title stay distinct tiles),
    //   • album tag present, no album_artist → key = albumTitle :: disc-collapsed folder (a VA
    //     compilation mustn't fragment by per-track artist, and two different "Greatest Hits" by
    //     different unnamed artists mustn't merge — the folder is the tiebreaker),
    //   • no album tag at all → the disc-collapsed folder, as before.
    groupBy { tr ->
        val titleKey = albumTitleKey(tr.album)
        val folderKey = albumFolderKey(tr.path)
        val folderName = if (folderKey.isNotBlank()) java.io.File(folderKey).name else ""
        val pressing = releaseTag(folderName) ?: releaseTag(tr.album) ?: ""
        val albArt = tr.albumArtist.trim()
        when {
            titleKey.isBlank() -> if (folderKey.isNotBlank()) "dir:$folderKey" else "id:${tr.albumId}"
            albArt.isNotBlank() && !isBlankArtistTag(albArt) ->
                "tag:" + canonicalArtistKey(normalizeArtistName(albArt), ctx) + "::" + titleKey + "::" + pressing
            else -> "dir:$folderKey::$titleKey::$pressing"
        }
    }
    .map { (_, tracks) ->
        val sortedTracks = sortAlbumTracks(tracks)
        val firstTrack = sortedTracks.first()

        // 1. Tagged Album Artist
        val taggedAlbumArt = tracks.firstOrNull { it.albumArtist.isNotBlank() }?.albumArtist
        
        // 2. Folder Hierarchy Inferred Artist
        val inferredArtist = tracks.mapNotNull { inferAlbumArtistFromPath(it.path, it.album) }.firstOrNull()

        val counts = tracks.map { normalizeArtistName(it.artist) }.filter { !isBlankArtistTag(it) }.groupingBy { it }.eachCount()
        val top = counts.maxByOrNull { it.value }
        // A clear per-track majority (≥ 70%) is the album's artist — it beats folder inference, which
        // is only a guess about how the files were dropped on the card, not what they are.
        val majority = top?.takeIf { it.value * 10 >= tracks.size * 7 }?.key
        val artistDisplay = when {
            !taggedAlbumArt.isNullOrBlank() && !isBlankArtistTag(taggedAlbumArt) -> normalizeArtistName(taggedAlbumArt)
            majority != null -> majority
            !inferredArtist.isNullOrBlank() && !isBlankArtistTag(inferredArtist) -> normalizeArtistName(inferredArtist)
            else -> {
                if (top != null && top.value * 10 >= tracks.size * 4) {
                    top.key
                } else if (tracks.size > 2) {
                    "Various Artists"
                } else {
                    val fallback = tracks.firstOrNull { !isBlankArtistTag(it.artist) }?.artist ?: firstTrack.artist
                    if (isBlankArtistTag(fallback)) "Unknown Artist" else fallback
                }
            }
        }

        // Attach resolved albumArtist to all tracks in this album if missing
        val finalTracks = sortedTracks.map { tr ->
            if (tr.albumArtist.isBlank()) tr.copy(albumArtist = artistDisplay) else tr
        }

        // If the tag itself looks like a mistagged filename ("01 This And That"), prefer a name
        // recovered from the folder it actually lives in — same "don't trust a metadata field
        // that's clearly wrong" principle as the bit-depth/year/artist fallbacks elsewhere.
        val rawAlbumName = firstTrack.album.trim()
        val displayName = if (rawAlbumName.isNotBlank() && !ALBUM_LOOKS_LIKE_FILENAME_RE.containsMatchIn(rawAlbumName)) {
            rawAlbumName
        } else {
            albumNameFromFolder(firstTrack.path) ?: rawAlbumName.ifBlank { "Unknown album" }
        }
        AlbumGroup(displayName, artistDisplay, finalTracks)
    }
    .let { mergeDuplicateAlbumCopies(it) }
    .sortedBy { formatArtistSortKey(it.name, ignoreThe, ctx) }

/** Strip folder-copy noise from an album title so duplicate rips collapse: a trailing "[1234]",
 *  "(1)", "copy", or "- copy" that an ingest/dedup appended, plus case/whitespace. The album TAG is
 *  usually clean ("Something Wild") while only the FOLDER carries the suffix, but normalize the
 *  title too in case the name was recovered from a suffixed folder. */
private val ALBUM_COPY_SUFFIX_RE =
    Regex("""\s*(?:\[[0-9]{1,6}\]|\((?:disc\s*)?[0-9]{1,3}\)|-?\s*copy(?:\s*[0-9]+)?)\s*$""", RegexOption.IGNORE_CASE)
private fun normalizeAlbumTitleForDedup(name: String): String =
    name.trim().let { ALBUM_COPY_SUFFIX_RE.replace(it, "") }.trim().lowercase()

/** Merge album groups that are the SAME release duplicated across folders — e.g. an ingest that
 *  copied "Something Wild", "Something Wild [2312]", "Something Wild [2313]". The base grouping is
 *  folder-keyed (which correctly separates distinct albums) but that also splits genuine duplicate
 *  copies into 3 tiles; this collapses copies sharing the same normalized album name + album artist
 *  into one, deduping tracks by track-number + title so each song appears once. Distinct albums that
 *  merely share a name are NOT merged unless the album artist also matches. */
private fun mergeDuplicateAlbumCopies(groups: List<AlbumGroup>): List<AlbumGroup> {
    if (groups.size < 2) return groups
    val byKey = LinkedHashMap<String, MutableList<AlbumGroup>>()
    for (g in groups) {
        val key = normalizeAlbumTitleForDedup(g.name) + " " + g.artist.trim().lowercase()
        byKey.getOrPut(key) { mutableListOf() }.add(g)
    }
    return byKey.values.map { copies ->
        if (copies.size == 1) copies[0]
        else {
            val primary = copies.maxByOrNull { it.tracks.size } ?: copies[0]
            val mergedTracks = sortAlbumTracks(
                copies.flatMap { it.tracks }
                    .distinctBy { "${it.discNumber}/${it.trackNumber} ${it.title.trim().lowercase()}" }
            )
            AlbumGroup(primary.name, primary.artist, mergedTracks)
        }
    }
}

// ---- Hione palette ----
val HioneTeal get() = Mascot.color(0xFFFFAB2E)
val HioneTealBright get() = Mascot.color(0xFFFFD08A)
val HionePink get() = Mascot.color(0xFF8EC5FF)
val HioneGold = Color(0xFFFFCF6B)
val Ground get() = Mascot.color(0xFF121318)
val Surface1 get() = Mascot.color(0xFF0C2B2E)
val Muted = Color(0xFF89ACA7)

/** Dynamic per-level metric color: hotter = better. */
fun bitrateColor(kbps: Int): Color = when {
    kbps <= 0 -> Muted
    kbps < 320 -> Color(0xFF7E8C8A)      // lossy
    kbps < 1000 -> HioneTeal              // lossless-ish
    kbps < 2500 -> HioneTealBright        // hi-res
    else -> HioneGold                     // top-tier / DSD-class
}

// Recognizes the pressing/edition disambiguation tags an ingest pipeline stamps into an album's
// own title/folder when the SAME album exists in more than one form — "Led Zeppelin IV [Vinyl
// 24-192]" vs "[Master 24-192]", "Jagged Little Pill (Collector Edition)", "Complete Discography
// (Audiophile Vinyl FLAC)". First match wins, checked most-specific-first so e.g. "Audiophile
// Vinyl" doesn't just register as plain "Vinyl".
private val RELEASE_TAG_PATTERNS = listOf(
    Regex("(?i)audiophile\\s+vinyl") to "AUDIOPHILE VINYL",
    Regex("(?i)\\bvinyl\\b") to "VINYL RIP",
    Regex("(?i)studio\\s+master") to "STUDIO MASTER",
    Regex("(?i)\\bmaster\\b") to "MASTER",
    Regex("(?i)\\bstudio\\b") to "STUDIO",
    Regex("(?i)collector'?s?\\s+edition") to "COLLECTOR'S ED.",
    Regex("(?i)anniversary\\s+edition") to "ANNIVERSARY ED.",
    Regex("(?i)\\bdeluxe\\b") to "DELUXE",
    Regex("(?i)\\bremaster(ed)?\\b") to "REMASTER",
    Regex("(?i)\\bbootleg\\b") to "BOOTLEG",
)

// PERF: a linear scan of every release-tag regex over the string. .albums()' groupBy calls this
// TWICE per track (folder name + album tag), so ~22k regex sweeps per pass over a handful of
// distinct strings. Memoized; "" is the stored stand-in for "no tag" (the map can't hold nulls).
private val releaseTagCache = java.util.concurrent.ConcurrentHashMap<String, String>()

/** The pressing/edition tag for an album, if its title/folder carries one — null for a plain
 *  single-release album (the common case; most albums don't need this). */
fun releaseTag(albumName: String): String? {
    val hit = releaseTagCache.getOrPut(albumName) {
        RELEASE_TAG_PATTERNS.firstOrNull { (re, _) -> re.containsMatchIn(albumName) }?.second ?: ""
    }
    return if (hit.isEmpty()) null else hit
}

/** Color for a release-tag chip — a distinct violet/lavender family, separate from every other
 *  badge dimension's palette (bit-depth teal/gold/pink, sample-rate silver/sky/violet/red, format
 *  green) so a "which pressing is this" tag never gets mistaken for a quality-tier badge. */
val ReleaseTagColor = Color(0xFFCE93D8)

/** Whole-CD image rip badge — warm amber, distinct from every quality/pressing badge family. */
val DiscImageColor = Color(0xFFFFB74D)

fun formatColor(mime: String): Color = when (mime.substringAfterLast('/').lowercase()) {
    "flac", "x-flac", "wav", "x-wav", "dsd", "dff", "dsf" -> HioneTealBright
    "mp4", "m4a", "aac", "alac" -> HioneTeal
    "mpeg", "mp3", "ogg", "opus" -> Color(0xFF7E8C8A)
    else -> Muted
}

private val metaSpanCache = android.util.LruCache<Long, AnnotatedString>(1000)
private val metricSpanCache = android.util.LruCache<Long, AnnotatedString>(1000)

/** Primary line: Artist · Album · Year */
fun trackMetaSpan(t: Track, resolvedYear: Int? = null): AnnotatedString {
    val yr = (resolvedYear?.takeIf { it > 0 }) ?: (t.year.takeIf { it > 0 })
    val cacheKey = if (yr != null) t.id * 31 + yr else t.id
    metaSpanCache.get(cacheKey)?.let { return it }
    val res = buildAnnotatedString {
        fun sep() = withStyle(SpanStyle(color = Color(0xFF3A5450))) { append("  ·  ") }
        DiscImage.rowLabel(t)?.let { lbl ->
            withStyle(SpanStyle(color = DiscImageColor, fontWeight = FontWeight.Bold)) { append(lbl) }
            sep()
        }
        withStyle(SpanStyle(color = Mascot.color(0xFFD4E8E5), fontWeight = FontWeight.Normal)) { append(t.artist) }
        if (t.album.isNotBlank()) { sep(); withStyle(SpanStyle(color = Muted)) { append(t.album) } }
        if (yr != null) { sep(); withStyle(SpanStyle(color = HioneGold, fontWeight = FontWeight.SemiBold)) { append("$yr") } }
    }
    metaSpanCache.put(cacheKey, res)
    return res
}

/** Secondary line: Bitrate · Duration · Size · Format */
fun metricSpans(t: Track): AnnotatedString {
    metricSpanCache.get(t.id)?.let { return it }
    val res = buildAnnotatedString {
        fun sep() = withStyle(SpanStyle(color = Color(0xFF3A5450))) { append("  ·  ") }
        var added = false
        if (t.bitrateKbps > 0) {
            withStyle(SpanStyle(color = bitrateColor(t.bitrateKbps), fontWeight = FontWeight.Medium)) { append("${t.bitrateKbps} kbps") }
            added = true
        }
        val fmt = t.mime.substringAfterLast('/').uppercase()
        if (fmt.isNotEmpty()) {
            if (added) sep()
            withStyle(SpanStyle(color = formatColor(t.mime), fontWeight = FontWeight.SemiBold)) { append(fmt) }
            added = true
        }
        if (t.durationMs > 0) {
            if (added) sep()
            val s = t.durationMs / 1000
            withStyle(SpanStyle(color = Muted)) { append("${s / 60}:${(s % 60).toString().padStart(2, '0')}") }
            added = true
        }
        if (t.sizeBytes > 0) {
            if (added) sep()
            withStyle(SpanStyle(color = Muted)) { append("${"%.1f".format(t.sizeBytes / 1e6)} MB") }
        }
    }
    metricSpanCache.put(t.id, res)
    return res
}
