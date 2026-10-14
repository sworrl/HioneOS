package com.hione.player

import android.content.Context
import android.media.MediaExtractor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-track technical info (bit depth + sample rate) that MediaStore doesn't provide. FLAC/WAV
 * headers are parsed directly (a few hundred bytes of IO); everything else falls back to
 * MediaExtractor's track format. Results persist to a JSON cache so each file is only ever
 * inspected once, and are mirrored into a Compose state map so badges appear as they resolve.
 * Lossy formats resolve to 0/0 = no badge.
 *
 * THREADING (2026-09-11 crash fix — this object was the "Unsupported concurrent change during
 * composition" crash-loop):
 *  - [data] is the authoritative store. Plain concurrent map, readable and writable from ANY
 *    thread, and the only thing [persist] serializes.
 *  - [cache] is a Compose mirror whose ONLY purpose is to make a badge recompose when its entry
 *    lands. It is written EXCLUSIVELY from a freshly posted main-looper message (see [publish]):
 *    never off the main thread, and never nested inside a composition pass, because a posted
 *    Handler message cannot run inside one.
 *  Previously [cache] was the store, which meant composition itself wrote it (the lossy
 *  `Tech(0, 0)` short-circuit below runs inside `remember { computeQualityBreakdown(...) }` and
 *  inside badge composables) while the IO probe coroutine — and any background caller such as the
 *  listen-stats executor or the BLE remote's binder thread — wrote the SAME SnapshotStateMap from
 *  another thread. That is exactly the "modified by composition as well as being modified outside
 *  composition" condition Recomposer.applyAndCheck throws on.
 */
object TrackTech {
    data class Tech(val bits: Int, val sampleRateHz: Int)

    private val data = ConcurrentHashMap<Long, Tech>()
    private val cache = mutableStateMapOf<Long, Tech>()
    private val mainH = Handler(Looper.getMainLooper())
    private val inFlight = HashSet<Long>()
    // PERF (scroll jank, 2026-09-17): the probe scope used to be plain Dispatchers.IO, i.e. pool
    // threads at NORMAL priority. Header parsing + MediaExtractor + the JSON cache rewrite then
    // competed with the UI thread for CPU on a 4-little-core 665 exactly while the user was
    // flinging a list. Dedicated 2-thread pool pinned to THREAD_PRIORITY_BACKGROUND (the Linux
    // bg cgroup) instead: identical work, but the scheduler now always prefers the UI thread.
    private val probeExecutor = Executors.newFixedThreadPool(2) { r ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "hione-tech-probe").apply { isDaemon = true }
    }
    private val scope = CoroutineScope(SupervisorJob() + probeExecutor.asCoroutineDispatcher())
    private val ioGate = Semaphore(2)   // never hammer the SD card
    private var cacheFile: File? = null
    /** Application context captured on first use — lets a deferred probe start later without the
     *  caller having to hand a Context back in. */
    @Volatile private var appCtx: Context? = null
    @Volatile private var loaded = false

    // ---------------------------------------------------------------------------------------
    // Scroll gate (PERF, 2026-09-17)
    // ---------------------------------------------------------------------------------------
    // Before: every row scrolled into view called techFor(), which for an unprobed lossless file
    // launched an IO coroutine ON THE SPOT — from the main thread, inside composition. A single
    // fling through a few thousand rows therefore queued a few thousand coroutines (plus a
    // synchronized(inFlight) per row) while the fling was still running, and then spent minutes
    // grinding the SD card afterwards. Now a probe asked for WHILE A LIST IS SCROLLING is only
    // recorded, and the recorded set is drained the moment scrolling stops. The set keeps at most
    // DEFERRED_CAP of the MOST RECENT requests, which is exactly the tail of the fling — i.e.
    // what actually ended up on screen — so a long fling can no longer leave an unbounded
    // backlog behind it. Nothing is lost permanently: a dropped id is re-requested the next time
    // that row composes.
    private val scrollDepth = AtomicInteger(0)
    private const val DEFERRED_CAP = 600
    private val deferred = LinkedHashMap<Long, Track>()

    /** A library list started scrolling. Reference-counted: two lists can be alive at once
     *  (AnimatedContent keeps the outgoing screen mounted through the transition). */
    fun beginScroll() { scrollDepth.incrementAndGet() }

    /** A library list stopped scrolling — drain whatever was asked for during the fling. */
    fun endScroll() {
        if (scrollDepth.decrementAndGet() > 0) return
        scrollDepth.set(0)
        val pending = synchronized(deferred) {
            if (deferred.isEmpty()) return
            val snapshot = ArrayList(deferred.values)
            deferred.clear()
            snapshot
        }
        for (t in pending) startProbe(t)
    }

    private fun deferProbe(track: Track) {
        synchronized(deferred) {
            deferred.remove(track.id)          // re-insert so it counts as the most recent request
            deferred[track.id] = track
            while (deferred.size > DEFERRED_CAP) {
                val iter = deferred.keys.iterator()
                if (!iter.hasNext()) break
                iter.next(); iter.remove()
            }
        }
    }

    private fun isMain(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    /** Store a result. The authoritative map is written immediately (any thread); the Compose
     *  mirror is written from a fresh main-looper message, so composition can neither race it nor
     *  be the one writing it. */
    private fun publish(id: Long, t: Tech) {
        val prev = data.put(id, t)
        // Only a genuinely NEW/changed answer can change a group's figures — a repeat publish of
        // the same value must not mark every cached group breakdown stale.
        if (prev != t) techVersion.incrementAndGet()
        mainH.post { cache[id] = t }
    }

    private fun ensureLoaded(ctx: Context) {
        if (appCtx == null) appCtx = ctx.applicationContext
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            // New filename/schema (bits+sampleRate per track, was bits-only) — old cache is just
            // silently left behind and rebuilt in the background like a first run, same as always.
            cacheFile = File(ctx.filesDir, "track_tech2.json")
            val fromDisk = HashMap<Long, Tech>()
            runCatching {
                val o = JSONObject(cacheFile!!.readText())
                o.keys().forEach { k ->
                    val v = o.getJSONObject(k)
                    fromDisk[k.toLong()] = Tech(v.optInt("b", 0), v.optInt("sr", 0))
                }
            }
            data.putAll(fromDisk)
            loaded = true
            // One bulk mirror write on the main looper — NOT thousands of entries written from
            // whichever background thread happened to be the first caller (that bulk off-thread
            // write landing on top of the first composition was the crash's most reliable trigger).
            if (fromDisk.isNotEmpty()) mainH.post { cache.putAll(fromDisk) }
        }
    }

    // Confirmed live (2026-08-17, 10k+ track library): persist() re-serializes and rewrites the
    // WHOLE cache file — every entry probed so far, not just the new one. Calling it after EVERY
    // single track probe (the old behavior) is an O(n²) I/O blowup at library scale: probing track
    // 9,000 of 10,000 doesn't just write one new entry, it rewrites a file already containing 8,999
    // others, every single time. This is very likely the actual cause of the reported 15-20s stall
    // on a fresh/rebuilt cache (a schema change to this file's format — see ensureLoaded's comment —
    // silently forces a full-library reprobe for every existing install once). Debounce instead:
    // batch real disk writes to at most once per ~2s or every 50 new entries, whichever comes
    // first, and expose flushPending() for a clean write on backgrounding (called from MainActivity
    // onPause) so a burst of probes right before the app closes doesn't just get dropped.
    @Volatile private var lastPersistAt = 0L
    @Volatile private var dirtySincePersist = 0

    private fun schedulePersist() {
        dirtySincePersist++
        val now = System.currentTimeMillis()
        if (now - lastPersistAt < 2000L && dirtySincePersist < 50) return
        lastPersistAt = now
        dirtySincePersist = 0
        persist()
    }

    fun flushPending() {
        if (dirtySincePersist > 0) { dirtySincePersist = 0; lastPersistAt = System.currentTimeMillis(); persist() }
    }

    private fun persist() {
        val f = cacheFile ?: return
        runCatching {
            val o = JSONObject()
            // Iterate the plain map: persist() runs on the IO probe coroutine, and a
            // SnapshotStateMap must not be traversed from there.
            for ((k, v) in data) o.put(k.toString(), JSONObject().put("b", v.bits).put("sr", v.sampleRateHz))
            f.writeText(o.toString())
        }
    }

    /** Bit depth for a track, or null while unknown / 0 for not-applicable (lossy). */
    fun bitsFor(ctx: Context, track: Track): Int? = techFor(ctx, track)?.bits
    /** Sample rate in Hz, or null while unknown / not yet resolved. */
    fun sampleRateFor(ctx: Context, track: Track): Int? = techFor(ctx, track)?.sampleRateHz?.takeIf { it > 0 }

    /** Cached (already-probed) sample rate by track id - no file I/O; null when never probed. */
    fun cachedSampleRateFor(ctx: Context, trackId: Long): Int? {
        ensureLoaded(ctx)
        if (isMain()) cache[trackId]   // snapshot read only, so a composable recomposes when it lands
        return data[trackId]?.sampleRateHz?.takeIf { it > 0 }
    }

    private fun techFor(ctx: Context, track: Track): Tech? {
        ensureLoaded(ctx)
        // Touch the Compose mirror on the main thread ONLY to register the snapshot read: that is
        // what wakes a badge when publish() later fills this id in. The value itself always comes
        // from the plain map, so background callers never touch Compose state at all.
        if (isMain()) cache[track.id]
        data[track.id]?.let { return it }
        val lossless = track.mime.contains("flac", true) || track.mime.contains("wav", true) ||
            track.mime.contains("x-wav", true) || track.mime.contains("aiff", true) ||
            track.mime.contains("alac", true) || track.mime.contains("mp4", true) ||
            track.path.endsWith(".flac", true) || track.path.endsWith(".wav", true) ||
            track.path.endsWith(".aif", true) || track.path.endsWith(".aiff", true) ||
            track.path.endsWith(".m4a", true)
        // Lossy: answer immediately and remember it. The answer is returned from the local value,
        // never read back out of the (asynchronously mirrored) Compose map.
        if (!lossless) { val t = Tech(0, 0); publish(track.id, t); return t }
        // PERF: while a list is being flung, record the request instead of launching an IO
        // coroutine per row from inside composition (see the scroll gate above). endScroll()
        // drains it, publish() then wakes the row through its `cache[track.id]` snapshot read.
        if (scrollDepth.get() > 0) { deferProbe(track); return null }
        startProbe(track)
        return null
    }

    /** Queue the real header probe for one track (no-op if one is already in flight). The
     *  application context is captured once by [ensureLoaded]'s caller, so this needs no Context:
     *  it reuses [appCtx], which is set the first time anything asks for a track's tech. */
    private fun startProbe(track: Track) {
        val ctx = appCtx ?: return
        synchronized(inFlight) { if (!inFlight.add(track.id)) return }
        scope.launch {
            ioGate.withPermit {
                val t = runCatching { probe(ctx, track) }.getOrNull() ?: Tech(0, 0)
                publish(track.id, t)
                synchronized(inFlight) { inFlight.remove(track.id) }
                schedulePersist()
            }
        }
    }

    private fun probe(ctx: Context, track: Track): Tech {
        if (track.path.isNotBlank()) {
            val f = File(track.path)
            if (f.canRead() && f.length() > 24) {
                runCatching {
                    f.inputStream().use { stream ->
                        val head = ByteArray(4096.coerceAtMost(f.length().toInt()))
                        val readBytes = stream.read(head)
                        if (readBytes > 22) {
                            flacTech(head, readBytes)?.let { return it }
                            wavTech(head, readBytes)?.let { return it }
                        }
                    }
                }
            }
        }
        // MediaExtractor fallback (m4a/ALAC and anything unreadable directly). Prefer the real
        // file path so a track that isn't in MediaStore (freshly synced, SD-card, or a stale id)
        // can still get its hi-res badge; only fall back to the content:// id if the path fails.
        val ex = MediaExtractor()
        return try {
            val opened = track.path.isNotBlank() && runCatching { ex.setDataSource(track.path) }.isSuccess
            if (!opened) {
                ex.setDataSource(ctx, Uri.parse("content://media/external/audio/media/${track.id}"), null)
            }
            var bits = 0
            var sr = 0
            for (i in 0 until ex.trackCount) {
                val fmt = ex.getTrackFormat(i)
                for (key in listOf("bits-per-sample", "bit-width")) {
                    runCatching { if (fmt.containsKey(key)) bits = fmt.getInteger(key) }
                }
                runCatching {
                    if (fmt.containsKey(android.media.MediaFormat.KEY_SAMPLE_RATE)) sr = fmt.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
                }
                if (bits > 0 && sr > 0) break
            }
            // PCM-encoding hint (2=16bit, 3=8bit, 4=float32, 21/22 = 24/32-bit packed)
            if (bits == 0) {
                runCatching {
                    val fmt = ex.getTrackFormat(0)
                    if (fmt.containsKey(android.media.MediaFormat.KEY_PCM_ENCODING))
                        bits = when (fmt.getInteger(android.media.MediaFormat.KEY_PCM_ENCODING)) {
                            android.media.AudioFormat.ENCODING_PCM_16BIT -> 16
                            android.media.AudioFormat.ENCODING_PCM_8BIT -> 8
                            android.media.AudioFormat.ENCODING_PCM_FLOAT -> 32
                            android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
                            android.media.AudioFormat.ENCODING_PCM_32BIT -> 32
                            else -> 0
                        }
                }
            }
            Tech(bits, sr)
        } catch (_: Throwable) { Tech(0, 0) } finally { runCatching { ex.release() } }
    }

    /**
     * FLAC STREAMINFO parser: searches for "fLaC" marker (accounting for optional ID3v2 prepended header),
     * then extracts 20-bit sample rate and 5-bit bit depth.
     */
    private fun flacTech(h: ByteArray, len: Int = h.size): Tech? {
        var offset = 0
        // Check for ID3v2 tag prefix
        if (len >= 10 && h[0] == 'I'.code.toByte() && h[1] == 'D'.code.toByte() && h[2] == '3'.code.toByte()) {
            val id3Size = ((h[6].toInt() and 0x7F) shl 21) or
                          ((h[7].toInt() and 0x7F) shl 14) or
                          ((h[8].toInt() and 0x7F) shl 7) or
                          (h[9].toInt() and 0x7F)
            offset = 10 + id3Size
        }
        // Find "fLaC" marker
        while (offset + 22 <= len) {
            if (h[offset] == 'f'.code.toByte() && h[offset + 1] == 'L'.code.toByte() &&
                h[offset + 2] == 'a'.code.toByte() && h[offset + 3] == 'C'.code.toByte()) {
                val streamInfoOffset = offset + 4 // after fLaC marker
                // STREAMINFO block header is 4 bytes, so STREAMINFO data starts at streamInfoOffset + 4
                // Offset in STREAMINFO data: 10 bytes in is sample rate (20 bits) and bits per sample (5 bits)
                val d = streamInfoOffset + 4
                if (d + 14 <= len) {
                    val b18 = h[d + 10].toInt() and 0xFF
                    val b19 = h[d + 11].toInt() and 0xFF
                    val b20 = h[d + 12].toInt() and 0xFF
                    val b21 = h[d + 13].toInt() and 0xFF
                    val sr = (b18 shl 12) or (b19 shl 4) or (b20 ushr 4)
                    val bits = (((b20 and 0x01) shl 4) or (b21 ushr 4)) + 1
                    if (sr > 0 && bits in 8..64) return Tech(bits, sr)
                }
            }
            offset++
        }
        return null
    }

    /**
     * WAV RIFF chunk parser: searches for 'fmt ' subchunk and extracts LE sample rate and bit depth.
     */
    private fun wavTech(h: ByteArray, len: Int = h.size): Tech? {
        if (len < 36 || h[0] != 'R'.code.toByte() || h[1] != 'I'.code.toByte() ||
            h[2] != 'F'.code.toByte() || h[3] != 'F'.code.toByte()) return null
        var offset = 12
        while (offset + 16 <= len) {
            if (h[offset] == 'f'.code.toByte() && h[offset + 1] == 'm'.code.toByte() &&
                h[offset + 2] == 't'.code.toByte() && h[offset + 3] == ' '.code.toByte()) {
                val chunkSize = (h[offset + 4].toInt() and 0xFF) or
                                ((h[offset + 5].toInt() and 0xFF) shl 8) or
                                ((h[offset + 6].toInt() and 0xFF) shl 16) or
                                ((h[offset + 7].toInt() and 0xFF) shl 24)
                val sr = (h[offset + 12].toInt() and 0xFF) or
                         ((h[offset + 13].toInt() and 0xFF) shl 8) or
                         ((h[offset + 14].toInt() and 0xFF) shl 16) or
                         ((h[offset + 15].toInt() and 0xFF) shl 24)
                val bits = if (offset + 23 <= len) {
                    (h[offset + 22].toInt() and 0xFF) or ((h[offset + 23].toInt() and 0xFF) shl 8)
                } else 16
                if (sr > 0 && bits in 8..64) return Tech(bits, sr)
                offset += 8 + chunkSize
            } else {
                val chunkSize = if (offset + 8 <= len) {
                    (h[offset + 4].toInt() and 0xFF) or
                    ((h[offset + 5].toInt() and 0xFF) shl 8) or
                    ((h[offset + 6].toInt() and 0xFF) shl 16) or
                    ((h[offset + 7].toInt() and 0xFF) shl 24)
                } else 4
                offset += 8 + chunkSize.coerceAtLeast(1)
            }
        }
        return null
    }

    /** Badge color per depth: 16 teal, 24 gold, 32 pink / rainbow. */
    fun color(bitsPerSample: Int): Color = when {
        bitsPerSample >= 32 -> HionePink
        bitsPerSample >= 24 -> HioneGold
        bitsPerSample >= 16 -> HioneTeal
        else -> Muted
    }

    /** Sample-rate badge color — Hione teal-mint baseline, radiant gold for 96k, violet-pink for 192k, electric magenta for DXD. */
    fun rateColor(hz: Int): Color = when {
        hz >= 352_800 -> Mascot.color(0xFFFF3399) // DXD / extreme (352.8kHz+) — Hione Neon Magenta
        hz >= 176_400 -> Mascot.color(0xFFB9C2D0) // ultra hi-res (176.4/192kHz) — Holographic Violet / Rainbow
        hz >= 88_200 -> HioneGold           // hi-res (88.2/96kHz) — Radiant Gold
        hz > 0 -> HioneTealBright           // CD-standard (44.1/48kHz) — Hione Bright Teal / Mint
        else -> Muted
    }

    /** 1..3 — which bit-depth tier (1 = 16-bit Cyan Frost, 2 = 24-bit Radiant Gold, 3 = 32-bit Rainbow Heart). */
    fun bitTier(bitsPerSample: Int): Int = when {
        bitsPerSample >= 32 -> 3
        bitsPerSample >= 24 -> 2
        else -> 1
    }

    /** 1..4 — which sample-rate tier (1 = 44/48k Mint, 2 = 88/96k Gold Crystal, 3 = 176/192k Rainbow Heart, 4 = 352k+ Supernova). */
    fun rateTier(hz: Int): Int = when {
        hz >= 352_800 -> 4
        hz >= 176_400 -> 3
        hz >= 88_200 -> 2
        else -> 1
    }

    /** Kawaii micro-glyph icon prefix per bit-depth tier. */
    fun glyphForBitTier(tier: Int): String = when {
        tier >= 3 -> "♥"
        tier == 2 -> "✦"
        else -> "✧"
    }

    /** Kawaii micro-glyph icon prefix per sample-rate tier. */
    fun glyphForRateTier(tier: Int): String = when {
        tier >= 4 -> "♥✦"
        tier == 3 -> "♥"
        tier == 2 -> "✦"
        else -> "✧"
    }

    /** Tier-escalating brush for bit-depth (Prismatic Rainbow for Tier 3, Radiant Gold for Tier 2, Hione Teal for Tier 1). */
    fun bitBrush(bitsPerSample: Int): Brush = when {
        bitsPerSample >= 32 -> HioneBadgePalette.RainbowBrush
        bitsPerSample >= 24 -> HioneBadgePalette.HiResBrush
        else -> HioneBadgePalette.StandardBrush
    }

    /** Tier-escalating brush for sample-rate (Prismatic Rainbow for 192k+, Radiant Gold for 96k, Hione Mint for 44.1k). */
    fun rateBrush(hz: Int): Brush = when {
        hz >= 176_400 -> HioneBadgePalette.RainbowBrush
        hz >= 88_200 -> HioneBadgePalette.HiResBrush
        else -> HioneBadgePalette.StandardBrush
    }

    /** 1..3 — which format tier (1 = plain lossy MP3/OGG/OPUS Cyan Frost, 2 = higher-effort lossy
     *  AAC/M4A Radiant Gold, 3 = true lossless FLAC/WAV/ALAC/DSD Rainbow Heart) — same 3-tier
     *  escalation as bitTier/rateTier so the format chip finally reads with the same "this one's
     *  special" weight a 24-bit or 96kHz badge already carries, instead of always sitting flat at
     *  tier 1 regardless of whether the file is an MP3 or a lossless FLAC. */
    fun formatTier(mime: String): Int = when (mime.substringAfterLast('/').lowercase()) {
        "flac", "x-flac", "wav", "x-wav", "alac", "dsd", "dff", "dsf" -> 3
        "mp4", "m4a", "aac" -> 2
        else -> 1
    }

    /** 1..4 — overall audio quality tier (1 = Lossy, 2 = CD Lossless, 3 = Studio Hi-Res, 4 = Master/DSD). */
    fun trackTier(bits: Int, sampleRateHz: Int, mime: String): Int {
        val fmt = mime.substringAfterLast('/').uppercase().ifBlank { "AUDIO" }
        val isLossless = formatTier(mime) >= 3
        return when {
            sampleRateHz >= 176400 || bits >= 32 || fmt in MASTER_FORMATS -> 4
            sampleRateHz >= 88200 || bits >= 24 -> 3
            isLossless -> 2
            else -> 1
        }
    }

    /** Tier-escalating brush for format — mirrors bitBrush/rateBrush exactly. */
    fun formatBrush(mime: String): Brush = when (formatTier(mime)) {
        3 -> HioneBadgePalette.RainbowBrush
        2 -> HioneBadgePalette.HiResBrush
        else -> HioneBadgePalette.StandardBrush
    }

    /** Bit-depth badge shape — always the rounded diamond silhouette (4 points), unmistakably identifying bit depth at a glance. */
    fun bitShape(bitsPerSample: Int): Shape = BadgeShapes.diamond

    /** Sample-rate badge shape — always the rounded pentagon silhouette (5 points, point-up), unmistakably identifying sample rate at a glance. */
    fun rateShape(hz: Int): Shape = BadgeShapes.pentagon

    /** "44.1kHz" / "96kHz" / "192kHz" / "DSD64" — clean audiophile formatting. */
    fun formatSampleRate(hz: Int): String {
        val khz = hz / 1000.0
        return if (khz == kotlin.math.floor(khz)) "${khz.toInt()}kHz" else "%.1fkHz".format(khz)
    }

    /** Weighted 0..1 quality score for a whole artist/album, for the at-a-glance gauge on list rows. */
    fun weightedQuality(ctx: Context, tracks: List<Track>, sampleSize: Int = 8): Float? {
        if (tracks.isEmpty()) return null
        var sum = 0f
        var n = 0
        for (t in tracks.take(sampleSize)) {
            val bits = bitsFor(ctx, t) ?: continue
            if (bits <= 0) continue
            val sr = sampleRateFor(ctx, t)
            val bitScore = (bitTier(bits) - 1) / 2f
            val rateScore = if (sr != null && sr > 0) (rateTier(sr) - 1) / 3f else bitScore
            sum += bitScore * 0.5f + rateScore * 0.5f
            n++
        }
        return if (n == 0) null else (sum / n).coerceIn(0f, 1f)
    }

    /**
     * Determines whether an audio track is sourced from a Vinyl / LP rip based on
     * path, folder naming, title, and album metadata tags.
     */
    fun isVinyl(track: Track): Boolean = isVinyl(track.path, track.album, track.title)

    fun isVinyl(path: String, album: String = "", title: String = ""): Boolean {
        val target = "$path $album $title".lowercase()
        return target.contains("vinyl") ||
               target.contains("needledrop") ||
               target.contains("needle drop") ||
               target.contains("lp rip") ||
               target.contains("lp_rip") ||
               target.contains("lp-rip") ||
               target.contains("analog rip") ||
               target.contains("analog_rip") ||
               target.contains("[lp]") ||
               target.contains("(lp)") ||
               target.contains("12\" single") ||
               target.contains("12\" vinyl") ||
               target.contains("7\" single") ||
               target.contains("7\" vinyl") ||
               target.contains("turntable") ||
               target.contains("dmm rip")
    }

    data class QualityBreakdown(
        val maxBits: Int,
        val maxSampleRateHz: Int,
        val maxBitrateKbps: Int,
        val dominantFormat: String,
        val totalTracks: Int,
        val masterCount: Int,      // 176.4k+, 32-bit, DSD
        val studioHiResCount: Int, // 24-bit or 88.2k-96k
        val cdLosslessCount: Int,  // 16-bit 44.1k/48k FLAC/WAV/ALAC
        val lossyCount: Int,       // MP3, AAC, OGG
        val masterFraction: Float,
        val studioHiResFraction: Float,
        val cdLosslessFraction: Float,
        val lossyFraction: Float,
        val highestTier: Int,      // 1..4
        val summaryTag: String,
        val detailSummary: String,
        val specTag: String = "—",
        val badgeSymbol: String = "✧",
        val isVinylRip: Boolean = false
    )

    /** The "nothing to say yet" breakdown. Every consumer already early-returns on
     *  `totalTracks == 0`, so this doubles as the placeholder [qualityForGroup] hands back while a
     *  real one is still being computed off the main thread. Shared instance: it used to be
     *  rebuilt on every empty call. */
    val EMPTY_QUALITY = QualityBreakdown(
        maxBits = 0, maxSampleRateHz = 0, maxBitrateKbps = 0, dominantFormat = "FLAC",
        totalTracks = 0, masterCount = 0, studioHiResCount = 0, cdLosslessCount = 0,
        lossyCount = 0, masterFraction = 0f, studioHiResFraction = 0f, cdLosslessFraction = 0f,
        lossyFraction = 0f, highestTier = 1, summaryTag = "Standard", detailSummary = "No tracks",
        specTag = "No audio", badgeSymbol = "♪", isVinylRip = false
    )

    // ---------------------------------------------------------------------------------------
    // Group-level quality cache (PERF, 2026-09-17)
    // ---------------------------------------------------------------------------------------
    // computeQualityBreakdown() is O(tracks-in-the-group) and allocation-heavy (a format string
    // per track, a counting map per call). Artist rows and album tiles were calling it straight
    // from their item bodies inside `remember(group.tracks) { ... }` — which survives a
    // recomposition but NOT scrolling out of and back into view, so on a fling every row
    // re-walked its whole track list ON THE MAIN THREAD. For a prolific artist that is hundreds
    // of tracks per row, per bind. Worse, running on the main thread made techFor() register a
    // Compose snapshot read for EVERY track in the group, so one probe landing invalidated every
    // row containing that track, and it launched the probe coroutines from inside composition.
    //
    // Now: rows call [qualityForGroup], which is a map lookup. A miss schedules the real pass on
    // the background probe pool and returns EMPTY_QUALITY (renders as "nothing yet", the same
    // state the row already showed before its badges resolved); when it lands, the row wakes
    // through the `qMirror` snapshot read below. Keyed on the IDENTITY of the track list, which
    // is stable for as long as the group object is (i.e. until a rescan replaces it) and is
    // verified by reference before a cached value is handed back, so a hash collision can only
    // cost an extra recompute, never show the wrong badge.
    private class GroupQuality(
        val tracks: List<Track>,
        val breakdown: QualityBreakdown,
        /** [techVersion] at the time this was computed — a later value means new per-track tech
         *  has landed since, so the figures are worth recomputing. */
        val version: Int,
        val computedAt: Long
    )

    private val qData = ConcurrentHashMap<Int, GroupQuality>()
    private val qMirror = mutableStateMapOf<Int, QualityBreakdown>()
    private val qInFlight = HashSet<Int>()
    /** Bumped by [publish] whenever a track's bit depth / sample rate resolves. */
    private val techVersion = AtomicInteger(0)
    private const val Q_REFRESH_MIN_MS = 1200L

    fun qualityForGroup(ctx: Context, tracks: List<Track>): QualityBreakdown {
        if (tracks.isEmpty()) return EMPTY_QUALITY
        val key = System.identityHashCode(tracks)
        // Snapshot read (main thread only) so the row recomposes when the real value lands.
        if (isMain()) qMirror[key]
        val app = ctx.applicationContext
        val hit = qData[key]
        if (hit != null && hit.tracks === tracks) {
            val stale = hit.version != techVersion.get() &&
                System.currentTimeMillis() - hit.computedAt > Q_REFRESH_MIN_MS
            // Keep showing the value we have (no flicker back to "unknown") and refresh behind it.
            if (stale) scheduleGroupQuality(app, key, tracks)
            return hit.breakdown
        }
        scheduleGroupQuality(app, key, tracks)
        return EMPTY_QUALITY
    }

    private fun scheduleGroupQuality(app: Context, key: Int, tracks: List<Track>) {
        synchronized(qInFlight) { if (!qInFlight.add(key)) return }
        scope.launch {
            val bd = runCatching { computeQualityBreakdown(app, tracks) }.getOrDefault(EMPTY_QUALITY)
            // Sampled AFTER the pass on purpose: computing a breakdown itself resolves (and
            // publishes) the lossy tracks in the group, so sampling before would leave the result
            // permanently "stale" and refresh it forever on a timer.
            val v = techVersion.get()
            if (qData.size > 4000) qData.clear()   // a few rescans' worth of dead group lists
            qData[key] = GroupQuality(tracks, bd, v, System.currentTimeMillis())
            mainH.post { qMirror[key] = bd }
            synchronized(qInFlight) { qInFlight.remove(key) }
        }
    }

    /** Formats whose presence alone means "master tier" — hoisted out of the per-track loop in
     *  [computeQualityBreakdown], where it used to allocate a fresh List on every single track. */
    private val MASTER_FORMATS = setOf("DSD", "DSF", "DFF")

    /**
     * Calculates the comprehensive relative quality distribution across an entire Album or Artist.
     * Computes exact breakdown fractions for Master (192k+/32-bit/DSD), Studio Hi-Res (24-bit/96k),
     * CD Lossless (16-bit/44.1k), and Lossy (MP3/AAC).
     */
    fun computeQualityBreakdown(ctx: Context, tracks: List<Track>): QualityBreakdown {
        if (tracks.isEmpty()) return EMPTY_QUALITY

        var maxBits = 0
        var maxSr = 0
        var maxBr = 0
        var master = 0
        var studio = 0
        var cd = 0
        var lossy = 0
        var vinylCount = 0

        val formatCounts = mutableMapOf<String, Int>()

        for (t in tracks) {
            // Unknown = 0, never assumed "16-bit / 44.1 kHz": an unmeasured track can't raise the
            // album's max figures or its tier, it only counts by container (lossless vs lossy).
            val bits = bitsFor(ctx, t) ?: 0
            val sr = sampleRateFor(ctx, t) ?: 0
            val fmt = t.mime.substringAfterLast('/').uppercase().ifBlank { "AUDIO" }
            formatCounts[fmt] = (formatCounts[fmt] ?: 0) + 1

            if (bits > maxBits) maxBits = bits
            if (sr > maxSr) maxSr = sr
            if (t.bitrateKbps > maxBr) maxBr = t.bitrateKbps
            if (isVinyl(t)) vinylCount++

            val isLossless = formatTier(t.mime) >= 3
            when {
                sr >= 176400 || bits >= 32 || fmt in MASTER_FORMATS -> master++
                sr >= 88200 || bits >= 24 -> studio++
                isLossless -> cd++
                else -> lossy++
            }
        }

        val total = tracks.size.toFloat().coerceAtLeast(1f)
        val mFrac = master / total
        val sFrac = studio / total
        val cdFrac = cd / total
        val lFrac = lossy / total
        val isVinylRip = vinylCount > 0

        val dominantFmt = formatCounts.maxByOrNull { it.value }?.key ?: "FLAC"

        val highestTier = when {
            master > 0 -> 4
            studio > 0 -> 3
            cd > 0 -> 2
            else -> 1
        }

        val losslessPct = ((master + studio + cd) / total * 100).toInt()

        val summaryTag = when {
            isVinylRip && master > 0 -> "VINYL MASTER${if (maxSr > 0) " · ${formatSampleRate(maxSr)}" else ""}"
            isVinylRip && studio > 0 -> "VINYL HI-RES${if (maxBits > 0) " · $maxBits-BIT" else ""}"
            isVinylRip -> "VINYL RIP · $dominantFmt"
            master > 0 && master == tracks.size -> "100% STUDIO MASTER"
            master > 0 -> "UP TO ${if (maxBits > 0) "$maxBits-BIT " else ""}${if (maxSr > 0) formatSampleRate(maxSr) else "HI-RES"}"
            studio > 0 && studio == tracks.size -> "100% HI-RES${if (maxBits > 0) " $maxBits-BIT" else ""}"
            studio > 0 -> "UP TO ${if (maxSr > 0) formatSampleRate(maxSr) else "HI-RES"}${if (maxBits > 0) " $maxBits-BIT" else ""}"
            cd == tracks.size -> "100% LOSSLESS $dominantFmt"
            losslessPct > 0 -> "$losslessPct% LOSSLESS"
            else -> if (maxBr > 0) "$dominantFmt ${maxBr}k" else dominantFmt
        }

        // Spec strings only quote figures that were actually measured; "(rate unmeasured)" otherwise.
        val srTxt = if (maxSr > 0) formatSampleRate(maxSr) else "rate unmeasured"
        val bitsTxt = if (maxBits > 0) "$maxBits-bit" else "bit depth unmeasured"
        val detailSummary = when {
            isVinylRip -> "$vinylCount of ${tracks.size} tracks Analog Vinyl Rip ($bitsTxt/$srTxt)"
            master > 0 -> "$master of ${tracks.size} tracks Master Tier ($bitsTxt/$srTxt)"
            studio > 0 -> "$studio of ${tracks.size} tracks Studio Hi-Res ($bitsTxt/$srTxt)"
            cd > 0 -> "$cd of ${tracks.size} tracks lossless ($bitsTxt/$srTxt)"
            else -> "${tracks.size} tracks lossy audio"
        }

        val baseSpecTag = when {
            master > 0 -> "${if (maxBits > 0) "$maxBits-BIT · " else ""}${srTxt.uppercase()} $dominantFmt"
            studio > 0 -> "${if (maxBits > 0) "$maxBits-BIT · " else ""}${srTxt.uppercase()} $dominantFmt"
            cd > 0 -> "${if (maxBits > 0) "$maxBits-BIT · " else ""}${srTxt.uppercase()} $dominantFmt"
            else -> if (maxBr > 0) "$dominantFmt · ${maxBr}kbps" else "$dominantFmt · bitrate unmeasured"
        }
        val specTag = if (isVinylRip) "$baseSpecTag · ⊚ VINYL" else baseSpecTag

        val badgeSymbol = when {
            isVinylRip -> "⊚"
            highestTier == 4 -> "◆"
            highestTier == 3 -> "◇"
            highestTier == 2 -> "✧"
            else -> "♪"
        }

        return QualityBreakdown(
            maxBits = maxBits,
            maxSampleRateHz = maxSr,
            maxBitrateKbps = maxBr,
            dominantFormat = dominantFmt,
            totalTracks = tracks.size,
            masterCount = master,
            studioHiResCount = studio,
            cdLosslessCount = cd,
            lossyCount = lossy,
            masterFraction = mFrac,
            studioHiResFraction = sFrac,
            cdLosslessFraction = cdFrac,
            lossyFraction = lFrac,
            highestTier = highestTier,
            summaryTag = summaryTag,
            detailSummary = detailSummary,
            specTag = specTag,
            badgeSymbol = badgeSymbol,
            isVinylRip = isVinylRip
        )
    }
}

/**
 * Palette definitions for the Kawaii Hione audio quality badge ecosystem.
 */
object HioneBadgePalette {
    // Iridescent Rainbow for Pinnacle Tier (192kHz+, 32-bit, DSD, DXD)
    val RainbowStops get() = listOf(
        Mascot.color(0xFFFFC56B), // Hione Neon Cyan / Mint
        Mascot.color(0xFF00B4D8), // Electric Sky Blue
        Color(0xFF9D4EDD), // Kawaii Holographic Violet
        Mascot.color(0xFFFF3399), // Hione Electric Pink / Magenta
        Color(0xFFFFD166), // Luminous Gold
        Mascot.color(0xFFFFC56B)  // Loop back
    )
    val RainbowBrush = Brush.linearGradient(RainbowStops)

    // Radiant Gold & Cyan Sparkle for Studio Master Tier (96kHz, 88.2kHz, 24-bit). Was briefly
    // recolored green/yellow to match the Pulsar Light's supposed stock HAL auto-behavior — that
    // behavior turned out not to exist at all (see [[m500-pulsar-light-dead-end]]/PulsarLight.kt's
    // history), and now that root gives full RGB control of the light, the causality runs the
    // OTHER way: the badges are the source of truth for "fancy," and PulsarLight reads ITS colors
    // from this palette (HioneBadgePalette), not the other way around. Reverted to the original.
    val HiResGoldStops get() = listOf(
        Color(0xFFFFD166), // Radiant Gold
        Color(0xFFFFA726), // Amber Glow
        Mascot.color(0xFFFFD08A), // Cyan Specular Glint
        Color(0xFFFFD166)
    )
    val HiResBrush = Brush.linearGradient(HiResGoldStops)

    // Hione Teal & Mint Glass for Standard Tier (44.1kHz, 48kHz, 16-bit)
    val StandardCyanStops get() = listOf(
        Mascot.color(0xFFFFAB2E),
        Mascot.color(0xFFFFD08A),
        Mascot.color(0xFFFFAB2E)
    )
    val StandardBrush = Brush.linearGradient(StandardCyanStops)

    // Hione Twin-Tail Ribbon Gradient for Audio Format Tags (FLAC, WAV, ALAC)
    val FormatRibbonStops get() = listOf(
        Mascot.color(0xFFFFD08A),
        Mascot.color(0xFF8EC5FF),
        Mascot.color(0xFFFFD08A)
    )
    val FormatBrush = Brush.linearGradient(FormatRibbonStops)
}

/**
 * Bespoke Kawaii Geometric Silhouettes:
 *  - diamond: 4-point Diamond Gem for Bit Depth (16-bit, 24-bit, 32-bit)
 *  - pentagon: 5-point Pentagon Crest for Sample Rate (44.1k, 96k, 192k)
 *  - hexagon: 6-point Hexagon Ribbon for Audio Format (FLAC, WAV, DSD)
 */
object BadgeShapes {
    val pill: Shape = RoundedCornerShape(50)

    /**
     * Symmetrical rounded polygon generator with soft kawaii rounded corners.
     */
    fun roundedPolygon(sides: Int, cornerFraction: Float = 0.28f, rotationDeg: Float = -90f): Shape =
        GenericShape { size, _ ->
            val cx = size.width / 2f
            val cy = size.height / 2f
            // The polygon is built on the chip's HEIGHT and then stretched to its full WIDTH:
            // the two halves are pushed outward by `ext` and any vertex sitting on the vertical
            // centre line is split into a left+right pair (flat top/bottom edge). Previously the
            // polygon was inscribed in min(w,h) — a tiny square gem floating behind wide text,
            // which read as a badge drawn on top of another badge.
            val r = size.height / 2f * 0.94f
            val ext = ((size.width - size.height) / 2f).coerceAtLeast(0f)
            val corner = r * cornerFraction
            val angleStep = (2.0 * Math.PI) / sides
            val rot = Math.toRadians(rotationDeg.toDouble())
            val pts = ArrayList<Offset>(sides * 2)
            for (i in 0 until sides) {
                val a = rot + angleStep * i
                val px = (r * kotlin.math.cos(a)).toFloat()
                val py = (r * kotlin.math.sin(a)).toFloat()
                val onAxis = kotlin.math.abs(px) < r * 0.02f
                if (onAxis && ext > 0.5f) {
                    // Winding is clockwise (angle increasing, y-down): a top vertex is entered
                    // from the left, a bottom vertex from the right.
                    if (py < 0f) { pts += Offset(cx - ext, cy + py); pts += Offset(cx + ext, cy + py) }
                    else { pts += Offset(cx + ext, cy + py); pts += Offset(cx - ext, cy + py) }
                } else {
                    val shift = if (px < 0f) -ext else ext
                    pts += Offset(cx + shift + px, cy + py)
                }
            }
            for (i in pts.indices) {
                val prev = pts[(i - 1 + pts.size) % pts.size]
                val curr = pts[i]
                val next = pts[(i + 1) % pts.size]
                val towardPrev = prev - curr
                val towardNext = next - curr
                val d1 = towardPrev.getDistance().coerceAtLeast(0.001f)
                val d2 = towardNext.getDistance().coerceAtLeast(0.001f)
                val inCorner = corner.coerceAtMost(minOf(d1, d2) * 0.5f)
                val p1 = curr + towardPrev * (inCorner / d1)
                val p2 = curr + towardNext * (inCorner / d2)
                if (i == 0) moveTo(p1.x, p1.y) else lineTo(p1.x, p1.y)
                quadraticTo(curr.x, curr.y, p2.x, p2.y)
            }
            close()
        }

    /** Bit-depth shape: Rounded 4-point Diamond, point-up */
    val diamond: Shape = roundedPolygon(sides = 4, cornerFraction = 0.30f, rotationDeg = -90f)

    /** Sample-rate shape: Rounded 5-point Pentagon, point-up */
    val pentagon: Shape = roundedPolygon(sides = 5, cornerFraction = 0.26f, rotationDeg = -90f)

    /** File-format shape: Rounded 6-point Hexagon, flat-top */
    val hexagon: Shape = roundedPolygon(sides = 6, cornerFraction = 0.24f, rotationDeg = 0f)

    // Aliases
    val kawaiiCapsule: Shape = diamond
    val kawaiiFacetedGem: Shape = diamond
    val kawaiiHeartCrest: Shape = pentagon
    val kawaiiHexGem: Shape = hexagon
}
