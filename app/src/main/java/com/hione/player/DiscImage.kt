package com.hione.player

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Whole-disc ("CD image") rip detection + labeling + optional splitting.
 *
 * Confirmed on the card (2026-08-25): ~495 album folders hold ONE big audio file for the whole CD
 * ("00 Remanufacture.flac", "00 CDImage.flac", "00 CDImage.1.flac" / ".2.flac" for multi-disc,
 * "00.flac", "00 00.flac", "00 <Artist> - <Album>.flac") and only ~6 of them have a .cue beside the
 * image. So the cue-less case is the MAIN case: detection is filename / duration / track-count
 * based and costs nothing beyond fields MediaStore already gave us — no decoding.
 *
 *  • Detection (any of): track-number-00 style filename · a single audio file ≥ 18 min whose album
 *    holds ≤ 2 files · title ≈ album title · filename ≈ "<artist> - <album>" · a sibling .cue whose
 *    FILE line names this audio file.
 *  • Every image is flagged [Track.isDiscImage] (persisted in the binary index, so it survives
 *    rescans) and stays playable as one item. Multi-file images (".1", ".2") become discs 1, 2… of
 *    one album.
 *  • Where a splitter can produce a real track list, the image is replaced by VIRTUAL tracks —
 *    [Track.parentId] + [Track.clipStartMs]/[Track.clipEndMs] — which mediaItemFor() plays
 *    through Media3's clipping configuration (seek to INDEX 01, end at the next index).
 *    Splitters run in [splitters] order, first non-empty answer wins:
 *      1. [CueSplitter] — a .cue beside the image (the rip's own track list).
 *      2. [com.hione.player.discsplit.TracksTxtSplitter] — a user-written tracks.txt (manual override).
 *      3. [com.hione.player.discsplit.MusicBrainzSplitter] — cue-less images looked up online by
 *         artist + album + exact running time; answers are cached on disk and applied LAZILY (the
 *         image shows as one item until the lookup lands and bumps ScanProgress.generation).
 */
object DiscImage {
    private const val TAG = "DiscImage"
    const val MIN_IMAGE_MS = 18 * 60_000L

    /**
     * No single SONG runs this long. Past it, "one file, one disc" is safe on duration alone.
     * Below it we need a second, structural signal, because 18-40 minute single files are ordinary:
     * DJ mixes, live sets, podcasts, prog epics, single-movement classical.
     */
    const val VERY_LONG_IMAGE_MS = 42 * 60_000L

    /** "00 Something.flac", "00.flac", "00_x", "00-x", "00 CDImage.2.flac" */
    private val IMAGE_NAME_RE = Regex("(?i)^00(?:[ ._-]|$)")
    /** Explicit whole-disc naming conventions, which are worth more than a leading "00". */
    private val CDIMAGE_NAME_RE = Regex("(?i)(cdimage|\\bimage\\b|full[ ._-]?album|whole[ ._-]?(disc|cd)|\\(disc ?\\d\\))")
    /** ".1.flac" / ".2.ape" multi-disc image suffix → disc number. */
    private val MULTI_DISC_RE = Regex("(?i)\\.(\\d)\\.(flac|ape|wav|wv|dsf|dff|m4a|mp3)$")
    private val NON_ALNUM_RE = Regex("[^\\p{L}\\p{Nd}]+")

    /** Pluggable "turn one image into its real tracks" strategies, tried in order. */
    interface WholeDiscSplitter {
        val name: String
        /** Return the virtual tracks for [image], or null if this splitter can't split it. */
        fun split(ctx: Context, image: Track): List<Track>?
        /** Called once before / after each [apply] pass on the calling thread (optional). */
        fun beginPass(ctx: Context) {}
        fun endPass(ctx: Context) {}
    }

    val splitters: MutableList<WholeDiscSplitter> = mutableListOf(
        CueSplitter,
        com.hione.player.discsplit.TracksTxtSplitter,
        com.hione.player.discsplit.MusicBrainzSplitter,
    )

    /** [split] = images that became virtual tracks (by any splitter); [images] − [split] still show as one item. */
    data class Report(var images: Int = 0, var withCue: Int = 0, var virtualTracks: Int = 0, var split: Int = 0)
    @Volatile var lastReport: Report = Report()
        private set

    private fun norm(s: String): String = NON_ALNUM_RE.replace(s.lowercase(), "")

    /** Post-pass over a freshly queried library. Flags images, assigns multi-file disc numbers,
     *  and swaps in virtual tracks where a splitter can produce them. O(n), no I/O except for a
     *  small directory listing per image candidate (skipped when the SD isn't file-readable). */
    fun apply(ctx: Context, tracks: List<Track>): List<Track> {
        if (tracks.isEmpty()) return tracks
        val report = Report()
        val t0 = android.os.SystemClock.elapsedRealtime()
        var cueLookups = 0; var cueMs = 0L; var splitMs = 0L; var splitCalls = 0
        // Files per album folder (disc subfolders collapsed) — the "single big file" signal.
        val folderCounts = HashMap<String, Int>(tracks.size / 8 + 16)
        for (t in tracks) if (t.path.isNotBlank()) {
            val k = folderKey(t.path); folderCounts[k] = (folderCounts[k] ?: 0) + 1
        }
        val tPre = android.os.SystemClock.elapsedRealtime()
        val canReadFiles = HioneStorageAccess.hasAllFilesAccess()
        val cueDirCache = HashMap<String, List<File>>()
        val out = ArrayList<Track>(tracks.size + 64)
        for (sp in splitters) runCatching { sp.beginPass(ctx) }.onFailure { Log.w(TAG, "${sp.name} beginPass failed", it) }
        for (t in tracks) {
            val name = if (t.path.isNotBlank()) t.path.substringAfterLast('/') else ""
            val base = name.substringBeforeLast('.')
            val folderN = folderCounts[folderKey(t.path)] ?: 1
            val longEnough = t.durationMs >= MIN_IMAGE_MS
            val veryLong = t.durationMs >= VERY_LONG_IMAGE_MS

            // A sibling .cue is the one DEFINITIVE signal, so resolve it before deciding rather
            // than after. Gated on longEnough so this stays one cached dir listing per candidate
            // folder, not per track in the library.
            var cue = t.cuePath
            // Only where an image could plausibly live: a whole-disc rip sits in a folder with a
            // handful of files, not a 40-track folder. Listing every long track's directory on the
            // SD card cost 23 seconds a launch; this keeps it to the few that can matter.
            if (cue.isBlank() && canReadFiles && longEnough && folderN <= 6) {
                val c0 = android.os.SystemClock.elapsedRealtime()
                cue = findCue(t.path, cueDirCache) ?: ""
                cueMs += android.os.SystemClock.elapsedRealtime() - c0; cueLookups++
            }

            /*
             * WHY THIS IS STRICTER THAN IT WAS (2026-09-17, Justin: "some of the whole disk one
             * file markers are not correct").
             *
             * Every old rule could fire on a single weak signal:
             *  · `^00` matched with NO duration check at all, so a 40-second "00 Intro.mp3" and any
             *    album that numbers its tracks from 00 were marked whole-disc rips.
             *  · `>=18min && folderN <= 2` marked every long single file sitting on its own: DJ
             *    mixes, live sets, podcasts, prog tracks, one-movement classical.
             *  · `>=18min && title == album` marked long single-track releases, where the track
             *    being named after the release is completely normal.
             *
             * Now: a cue sheet decides it outright, a naming convention counts only alongside a
             * plausible duration, and duration alone only counts past 42 minutes, which no single
             * song reaches. A track that carries a real track number in a folder full of other
             * audio is never an image no matter what it is called.
             */
            // ONLY for tracks long enough to be an image. These were evaluated eagerly for all
            // 16.6k tracks (my 2026-09-17 rewrite hoisted them out of the `when`), and norm() is a
            // Unicode-class regex pass: four of them per track was 21 of the pass's 24 seconds.
            // A track under 18 minutes cannot be an image, so it never needs its name inspected.
            val imageName = longEnough && name.isNotBlank() &&
                (IMAGE_NAME_RE.containsMatchIn(name) || CDIMAGE_NAME_RE.containsMatchIn(name))
            val artistAlbumName = longEnough && t.album.isNotBlank() &&
                (norm(base) == norm("${t.artist} - ${t.album}") || norm(base) == norm("${t.albumArtist} - ${t.album}"))
            // Sits among siblings AND is numbered like one of them: that is a track, not a disc.
            val numberedAmongSiblings = folderN > 2 && t.trackNumber > 0
            val looksImage = when {
                cue.isNotBlank() && longEnough -> true
                numberedAmongSiblings -> false
                imageName && longEnough -> true
                artistAlbumName && longEnough -> true
                veryLong && folderN <= 2 -> true
                veryLong && t.album.isNotBlank() && norm(t.title) == norm(t.album) -> true
                else -> false
            }
            if (!looksImage && !t.isDiscImage) { out.add(t); continue }
            report.images++
            val disc = MULTI_DISC_RE.find(name)?.groupValues?.get(1)?.toIntOrNull() ?: t.discNumber
            if (cue.isBlank() && canReadFiles) cue = findCue(t.path, cueDirCache) ?: ""
            if (cue.isNotBlank()) report.withCue++
            val image = t.copy(isDiscImage = true, discNumber = disc, cuePath = cue,
                trackNumber = if (t.trackNumber == 0) 1 else t.trackNumber)
            var virtual: List<Track>? = null
            val s0 = android.os.SystemClock.elapsedRealtime()
            for (sp in splitters) {
                splitCalls++
                virtual = runCatching { sp.split(ctx, image) }.onFailure { Log.w(TAG, "${sp.name} failed on ${t.path}", it) }.getOrNull()
                if (!virtual.isNullOrEmpty()) break
            }
            splitMs += android.os.SystemClock.elapsedRealtime() - s0
            if (!virtual.isNullOrEmpty()) { report.split++; report.virtualTracks += virtual.size; out.addAll(virtual) } else out.add(image)
        }
        lastReport = report
        for (sp in splitters) runCatching { sp.endPass(ctx) }.onFailure { Log.w(TAG, "${sp.name} endPass failed", it) }
        if (report.images > 0) Log.i(TAG, "Whole-disc images: ${report.images} (with cue: ${report.withCue}, split: ${report.split}, virtual tracks: ${report.virtualTracks})")
        Log.i(TAG, "apply: total=${android.os.SystemClock.elapsedRealtime() - t0}ms folderCountPass=${tPre - t0}ms cueLookups=$cueLookups (${cueMs}ms) " +
            "splitterCalls=$splitCalls (${splitMs}ms) tracks=${tracks.size}")
        return out
    }

    private val DISC_DIR_RE = Regex("(?i)^(cd|disc|disk)\\s*\\.?\\s*\\d{1,2}$")
    // PERF: apply() calls this TWICE per track (folder-count pass, then the main pass) and each
    // call allocated a File, walked to the parent, regex-matched and lowercased an absolutePath —
    // ~22k times on an 11k-track library, every time the library is (re)queried. The result depends
    // only on the parent directory, so it is memoized on that: ~1 computation per album folder.
    private val folderKeyCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private fun folderKey(path: String): String {
        if (path.isBlank()) return ""
        val dirKey = path.substringBeforeLast('/', path)
        folderKeyCache[dirKey]?.let { return it }
        val computed = folderKeyUncached(path)
        folderKeyCache[dirKey] = computed
        return computed
    }
    private fun folderKeyUncached(path: String): String {
        var dir = File(path).parentFile ?: return ""
        if (DISC_DIR_RE.matches(dir.name.trim())) dir = dir.parentFile ?: dir
        return dir.absolutePath.lowercase()
    }

    /** The .cue that references [audioPath] (FILE "…" line), or the folder's only cue when the
     *  folder holds a single audio file. Directory listings are cached per call. */
    private fun findCue(audioPath: String, cache: HashMap<String, List<File>>): String? {
        if (audioPath.isBlank()) return null
        return try {
            val f = File(audioPath)
            val dir = f.parentFile ?: return null
            val cues = cache.getOrPut(dir.absolutePath) {
                dir.listFiles { x -> x.isFile && x.name.endsWith(".cue", ignoreCase = true) }?.toList() ?: emptyList()
            }
            if (cues.isEmpty()) return null
            val want = f.name.lowercase()
            // Exact FILE "<name>" reference first.
            for (c in cues) {
                val head = runCatching { c.inputStream().use { ins -> String(ins.readNBytes(8192), Charsets.UTF_8) } }.getOrNull() ?: continue
                if (head.lowercase().contains("\"$want\"")) return c.absolutePath
            }
            // Then a stem match: "00 Arch Enemy - Wages Of Sin.flac" ↔ "Arch Enemy - Wages Of Sin [FLAC].cue" /
            // FILE "Arch Enemy - Wages Of Sin.wav" — rips rename the audio and keep a WAV-era cue.
            val audioStem = stemKey(f.name)
            if (audioStem.isNotBlank()) {
                for (c in cues) {
                    if (stemKey(c.name) == audioStem) return c.absolutePath
                    val head = runCatching { c.inputStream().use { ins -> String(ins.readNBytes(8192), Charsets.UTF_8) } }.getOrNull() ?: continue
                    val fileLine = head.lines().firstOrNull { it.trim().startsWith("FILE ", true) } ?: continue
                    val ref = fileLine.substringAfter('"', "").substringBefore('"')
                    if (ref.isNotBlank() && stemKey(ref) == audioStem) return c.absolutePath
                }
            }
            // Single cue + this is the only audio file in the folder → it's ours even if the FILE line names a .wav
            if (cues.size == 1) {
                val audioCount = dir.listFiles { x -> x.isFile && x.extension.lowercase() in AUDIO_EXTS }?.size ?: 0
                if (audioCount == 1) return cues[0].absolutePath
            }
            null
        } catch (_: Throwable) { null }
    }
    private val AUDIO_EXTS = setOf("flac", "ape", "wav", "wv", "dsf", "dff", "m4a", "mp3", "ogg", "opus", "tta", "mpc")
    private val STEM_EXT_RE = Regex("(?i)\\.(flac|ape|wav|wv|dsf|dff|m4a|mp3|ogg|opus|tta|mpc|cue)$")
    private val STEM_TAG_RE = Regex("\\[[^\\]]*\\]|\\([^)]*\\)")
    /** "00 Arch Enemy - Wages Of Sin.flac" / "Arch Enemy - Wages Of Sin [FLAC].cue" → "archenemywagesofsin" */
    private fun stemKey(name: String): String {
        var s = name
        while (STEM_EXT_RE.containsMatchIn(s)) s = STEM_EXT_RE.replace(s, "")
        s = STEM_TAG_RE.replace(s, "")
        s = s.replace(Regex("^\\s*00[ ._-]*"), "")
        return norm(s)
    }

    /** Human label for a track row: "Whole disc · 1 file · 47:12" (cue-less image). */
    fun rowLabel(t: Track): String? {
        if (!t.isDiscImage || t.parentId != 0L) return null
        val s = t.durationMs / 1000
        val disc = if (t.discNumber > 1) "disc ${t.discNumber} · " else ""
        return "Whole disc · ${disc}1 file · ${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }
}

/** .cue sheet → virtual tracks (INDEX 01 offsets). */
object CueSplitter : DiscImage.WholeDiscSplitter {
    override val name = "cue"

    data class Entry(val number: Int, val title: String, val performer: String, val startMs: Long)

    private val TIME_RE = Regex("(\\d+):(\\d{1,2}):(\\d{1,2})")

    fun parse(file: File): List<Entry> {
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Entry>()
        var num = 0; var title = ""; var performer = ""; var start: Long? = null; var inTrack = false
        var globalPerformer = ""
        fun flush() { if (inTrack && start != null) out.add(Entry(num, title.ifBlank { "Track $num" }, performer.ifBlank { globalPerformer }, start!!)) }
        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("TRACK ", true) -> { flush(); inTrack = true; start = null; title = ""; performer = ""
                    num = line.substring(6).trim().substringBefore(' ').toIntOrNull() ?: (num + 1) }
                line.startsWith("TITLE ", true) -> { val v = line.substring(6).trim().trim('"'); if (inTrack) title = v }
                line.startsWith("PERFORMER ", true) -> { val v = line.substring(10).trim().trim('"'); if (inTrack) performer = v else globalPerformer = v }
                line.startsWith("INDEX 01", true) -> TIME_RE.find(line)?.let { m ->
                    val (mm, ss, ff) = m.destructured
                    start = (mm.toLong() * 60 + ss.toLong()) * 1000L + ff.toLong() * 1000L / 75L
                }
            }
        }
        flush()
        return out.sortedBy { it.startMs }
    }

    override fun split(ctx: Context, image: Track): List<Track>? {
        if (image.cuePath.isBlank()) return null
        val entries = parse(File(image.cuePath))
        if (entries.size < 2) return null
        val total = image.durationMs
        return entries.mapIndexed { i, e ->
            val end = entries.getOrNull(i + 1)?.startMs ?: total
            val dur = if (end > e.startMs) end - e.startMs else 0L
            image.copy(
                id = -(image.id * 1000L + (i + 1)),          // stable synthetic id: parent id + slot
                title = e.title,
                artist = e.performer.ifBlank { image.artist },
                durationMs = dur,
                sizeBytes = 0L,
                trackNumber = e.number.takeIf { it > 0 } ?: (i + 1),
                isDiscImage = true,
                parentId = image.id,
                clipStartMs = e.startMs,
                clipEndMs = if (end > e.startMs && end < total) end else 0L
            )
        }
    }
}
