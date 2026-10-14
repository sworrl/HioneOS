package com.hione.player.bpm

import com.hione.player.Mascot
import com.hione.player.safeQuery
import com.hione.player.HionePowerGovernor
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.media3.common.MediaItem
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Hione Music's tempo and beat publisher for the rest of HioneOS.
 *
 * TEMPO. Per track, from [HioneTempoAnalyzer]: the cached result, the file's BPM tag when it reads
 * 60..200, or a whole-track QM-DSP TempoTrackV2 analysis, folded into 70..180 BPM. Constant for
 * the track. While that is still running for a new track, the tempo comes from the live beat
 * tracker instead (see below), so the readout fills in within a few seconds either way.
 *
 * BEAT PHASE. From [HioneBeatClock], which BTrack drives from the PCM the player is writing
 * ([HioneBeatTap]), seeded with the file tempo. The pulse loop fires BPM_PULSE on that grid, at
 * the moment each beat leaves the DAC, and each pulse carries the beat's exact time so a receiver
 * does not have to trust broadcast delivery time.
 *
 * PUBLISHED.
 *  - Settings.Global hione_live_bpm, hione_beat_interval_ms, hione_is_playing (as before) and
 *    hione_now_playing_bpm, the track tempo the lockscreen badge reads. Nothing wrote that key
 *    before, so the lockscreen badge always showed "— BPM".
 *  - BPM_UPDATE on tempo or play-state change. BPM_PULSE on every beat with extras
 *    beat_epoch_ms (System.currentTimeMillis time of the beat), beat_elapsed_nanos,
 *    phase_locked (true when the grid follows the audio) and tempo_source.
 *  - [beatAnchorElapsedNanos] / [beatGridIntervalNanos] for in-app beat visuals.
 */
object HioneBpmEngine {
    private const val TAG = "HioneBpmEngine"
    const val ACTION_BPM_UPDATE = "com.hione.action.BPM_UPDATE"
    const val ACTION_BPM_PULSE = "com.hione.action.BPM_PULSE"
    const val EXTRA_BPM = "bpm"
    const val EXTRA_BEAT_INTERVAL_MS = "beat_interval_ms"
    const val EXTRA_IS_PLAYING = "is_playing"
    const val EXTRA_DOMINANT_COLOR = "dominant_color"
    const val EXTRA_BEAT_EPOCH_MS = "beat_epoch_ms"
    const val EXTRA_BEAT_ELAPSED_NANOS = "beat_elapsed_nanos"
    const val EXTRA_PHASE_LOCKED = "phase_locked"
    const val EXTRA_TEMPO_SOURCE = "tempo_source"

    /** File confidence at which BTrack is held on the file tempo instead of only started there. */
    private const val FIX_TEMPO_CONFIDENCE = 0.6f

    private val bpmCache = ConcurrentHashMap<String, HioneTempoDb.Row>()
    private var beatJob: Job? = null
    private var resolveJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // 0 = "no tempo known"; OS surfaces render that as "—". Never a placeholder 120.
    @Volatile var currentBpm: Float = 0f
        private set
    @Volatile var beatIntervalMs: Long = 0L
        private set
    @Volatile var isPlaying: Boolean = false
        private set
    @Volatile var dominantColor: Int = Mascot.argb(0xFFFFAB2E.toInt())
        private set

    /**
     * The beat grid the pulse broadcasts are fired on, for in-app beat visuals that must flash
     * together with them: beat n fires at [beatAnchorElapsedNanos] + n * [beatGridIntervalNanos]
     * (SystemClock.elapsedRealtimeNanos time base). [beatGridIntervalNanos] is 0 whenever no pulse
     * is running (paused, unknown tempo, screen-off governor), and a consumer must then show no
     * beat at all.
     */
    @Volatile var beatAnchorElapsedNanos: Long = 0L
        private set
    @Volatile var beatGridIntervalNanos: Double = 0.0
        private set

    @Volatile private var npTitle: String = ""
    @Volatile private var npArtist: String = ""

    /** Last item handed to [onPlaybackChanged], replayed when the power governor lets work run again. */
    @Volatile private var lastMediaItem: MediaItem? = null
    @Volatile private var governorListenerArmed = false
    @Volatile private var scanScheduled = false
    /** Track key the clock and tap were last reset for. */
    @Volatile private var clockTrackKey: String? = null
    /** File row of the current track, for re-seeding the phase after a seek. */
    @Volatile private var currentRow: HioneTempoDb.Row? = null

    private fun armGovernorListener(context: Context) {
        if (governorListenerArmed) return
        governorListenerArmed = true
        val app = context.applicationContext
        HionePowerGovernor.addListener { _ ->
            val allow = HionePowerGovernor.allowBackgroundWork
            HioneBeatTap.setWanted(allow && isPlaying)
            if (allow && isPlaying && beatJob?.isActive != true) {
                lastMediaItem?.let { onPlaybackChanged(app, it, true) }
            }
        }
    }

    private fun keyOf(item: MediaItem): String {
        val clip = item.clippingConfiguration.startPositionMs
        return item.mediaId + "#" + clip
    }

    fun onPlaybackChanged(
        context: Context,
        mediaItem: MediaItem?,
        playing: Boolean,
        albumArtColor: Int? = null
    ) {
        armGovernorListener(context)
        HioneTempoAnalyzer.attach(context)
        if (!scanScheduled) {
            scanScheduled = true
            HioneBpmScanJob.schedule(context.applicationContext)
        }
        isPlaying = playing
        lastMediaItem = mediaItem
        if (albumArtColor != null) dominantColor = albumArtColor
        npTitle = mediaItem?.mediaMetadata?.title?.toString() ?: ""
        npArtist = mediaItem?.mediaMetadata?.artist?.toString() ?: ""

        // A different track: the old phase and the old tempo are meaningless now.
        val key = mediaItem?.let { keyOf(it) }
        if (key != clockTrackKey) {
            clockTrackKey = key
            currentRow = null
            val cached = key?.let { bpmCache[it] }
            val bpm = HioneTempoAnalyzer.shownBpm(cached)
            HioneBeatClock.reset(bpm)
            HioneBeatTap.seed(bpm, bpm > 0f && (cached?.confidence ?: 0f) >= FIX_TEMPO_CONFIDENCE)
            currentRow = cached
            currentBpm = bpm
        }

        if (mediaItem == null || !playing) {
            HioneBeatTap.setWanted(false)
            stopBeatPulse(context)
            // Nothing loaded means there is no output format to report. The sink publishes
            // hione_now_playing_format when it builds an AudioTrack but has no stop hook of its
            // own, so without this the last track's format would outlive the session and the
            // lockscreen badge would describe audio that is not playing.
            if (mediaItem == null) com.hione.player.HioneNowPlayingFormat.clear(context)
            publishState(context, currentBpm, false)
            return
        }
        // Power governor: analysis, the live tracker and the beat pulse are screen-facing work,
        // skipped while the screen is off and replayed by [armGovernorListener] when it is back.
        // The play state itself is always published.
        if (!HionePowerGovernor.allowBackgroundWork) {
            HioneBeatTap.setWanted(false)
            stopBeatPulse(context)
            publishState(context, currentBpm, true)
            return
        }

        HioneBeatTap.setWanted(true)
        // The player's position right now, for placing the file grid. Read here because this
        // runs on the player's thread; the analysis below may finish seconds later.
        val posMs = runCatching { com.hione.player.PlayerHolder.player?.currentPosition }.getOrNull() ?: -1L
        val posAtNs = SystemClock.elapsedRealtimeNanos()

        publishState(context, currentBpm, true)
        startBeatPulse(context)

        resolveJob?.cancel()
        resolveJob = scope.launch {
            val row = withContext(Dispatchers.IO) { resolveRow(context, mediaItem) }
            if (key != clockTrackKey) return@launch
            currentRow = row
            val bpm = HioneTempoAnalyzer.shownBpm(row) .takeIf { it > 0f } ?: dictionaryBpm(row, mediaItem)
            if (bpm != currentBpm || HioneBeatClock.grid.bpm != bpm) {
                HioneBeatClock.reset(bpm)
                HioneBeatTap.seed(bpm, bpm > 0f && (row?.confidence ?: 0f) >= FIX_TEMPO_CONFIDENCE)
            }
            currentBpm = bpm
            // Analysed, no steady beat: nothing for the live tracker to follow either.
            if (row != null && bpm <= 0f) HioneBeatTap.setWanted(false)
            if (row != null && bpm > 0f && row.firstBeatMs >= 0 && posMs >= 0 && isPlaying) {
                val nowNs = SystemClock.elapsedRealtimeNanos()
                val positionNow = posMs + (nowNs - posAtNs) / 1_000_000L
                HioneBeatClock.seedFromFileGrid(row.firstBeatMs, positionNow, nowNs)
            }
            beatIntervalMs = if (bpm > 0f) (60_000f / bpm).toLong().coerceIn(60L, 4000L) else 0L
            publishState(context, bpm, isPlaying)
        }
    }

    private fun resolveRow(context: Context, mediaItem: MediaItem): HioneTempoDb.Row? {
        val key = keyOf(mediaItem)
        bpmCache[key]?.let { return it }
        val path = resolvePath(context, mediaItem) ?: return null
        val clip = mediaItem.clippingConfiguration
        val start = clip.startPositionMs.coerceAtLeast(0L)
        val end = if (clip.endPositionMs == Long.MIN_VALUE || clip.endPositionMs <= 0L) 0L else clip.endPositionMs
        val row = HioneTempoAnalyzer.resolve(context, path, start, end) ?: return null
        bpmCache[key] = row
        return row
    }

    /**
     * Hand-entered tempos for a few songs, used only when neither a tag nor the analysis produced
     * one. These are the songs' published tempos, so they are not folded.
     */
    private fun dictionaryBpm(row: HioneTempoDb.Row?, item: MediaItem): Float {
        if (row != null && row.bpm > 0f && row.confidence >= HioneTempoAnalyzer.MIN_SHOW_CONFIDENCE) return row.bpm
        val title = item.mediaMetadata.title?.toString() ?: ""
        val artist = item.mediaMetadata.artist?.toString() ?: ""
        return findPreseededBpm(artist, title) ?: 0f
    }

    /** File path for the item: direct file URI, or resolve a content:// MediaStore uri to DATA. */
    private fun resolvePath(context: Context, mediaItem: MediaItem): String? {
        val uri = mediaItem.localConfiguration?.uri ?: return null
        if (uri.scheme == null || uri.scheme == "file") return uri.path
        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.safeQuery(
                    uri, arrayOf(android.provider.MediaStore.Audio.Media.DATA), null, null, null
                )?.use { c -> if (c.moveToFirst()) return c.getString(0) }
            }
        }
        return null
    }

    /** A user-entered tempo for the playing track; holds until the track changes. */
    fun calibrateBpm(context: Context, newBpm: Float) {
        val sanitized = newBpm.coerceIn(20f, 999f)
        currentBpm = sanitized
        beatIntervalMs = (60_000f / sanitized).toLong().coerceIn(60L, 4000L)
        HioneBeatClock.reset(sanitized)
        HioneBeatTap.seed(sanitized, true)
        publishState(context, sanitized, isPlaying)
        if (isPlaying) startBeatPulse(context)
    }

    private val PRESEEDED_BPM_DICTIONARY = mapOf(
        "beck|cellphone's dead" to 108.0f,
        "beck|cellphones dead" to 108.0f,
        "beck|loser" to 85.0f,
        "beck|e-pro" to 96.0f,
        "supercell|world is mine" to 165.0f,
        "supercell feat. hatsune miku|world is mine" to 165.0f,
        "supercell|melt" to 170.0f,
        "supercell feat. hatsune miku|melt" to 170.0f,
        "kurousa-p|senbonzakura" to 154.0f,
        "whiteflame feat. hatsune miku|senbonzakura" to 154.0f,
        "kz|tell your world" to 150.0f,
        "livetune feat. hatsune miku|tell your world" to 150.0f,
        "lamaze-p|popipo" to 140.0f,
        "wowaka|rolling girl" to 195.0f,
        "wowaka|world's end dancehall" to 165.0f,
        "wowaka|two-faced lovers" to 210.0f,
        "wowaka|ura-omote lovers" to 210.0f,
        "wowaka|unknown mother goose" to 224.0f,
        "deco*27|ghost rule" to 210.0f,
        "deco*27|vampire" to 132.0f,
        "deco*27|hibana" to 200.0f,
        "deco*27|otome dissection" to 135.0f,
        "samfree|luka luka night fever" to 160.0f,
        "giga-p|bring it on" to 145.0f,
        "mitchie m|freely tomorrow" to 135.0f,
        "daft punk|around the world" to 121.0f,
        "daft punk|one more time" to 123.0f,
        "daft punk|get lucky" to 116.0f,
        "gorillaz|feel good inc" to 139.0f,
        "gorillaz|clint eastwood" to 84.0f
    )

    private fun findPreseededBpm(artist: String, title: String): Float? {
        val a = artist.trim().lowercase()
        val t = title.trim().lowercase()
        if (a.isBlank() || t.isBlank()) return null
        PRESEEDED_BPM_DICTIONARY["$a|$t"]?.let { return it }
        for ((k, bpm) in PRESEEDED_BPM_DICTIONARY) {
            val parts = k.split("|")
            if (parts.size == 2) {
                val dictA = parts[0]
                val dictT = parts[1]
                if (dictA.isBlank() || dictT.isBlank()) continue
                if (t.contains(dictT) && (a.contains(dictA) || dictA.contains(a))) return bpm
            }
        }
        return null
    }

    /**
     * Fires BPM_PULSE on [HioneBeatClock]'s grid. The grid is re-read before every beat, so phase
     * corrections from the live tracker apply on the next beat without restarting anything.
     */
    private fun startBeatPulse(context: Context) {
        if (beatJob?.isActive == true) return
        beatJob = scope.launch {
            var lastBeatNs = 0L
            var flushEpoch = HioneBeatTap.flushCount
            var freeAnchorNs = 0L
            var lastPublishedBpm = currentBpm
            var lastLoggedGrid = ""
            try {
                while (isActive && isPlaying) {
                    // A seek: put the file grid back under the new position until BTrack relocks.
                    if (HioneBeatTap.flushCount != flushEpoch) {
                        flushEpoch = HioneBeatTap.flushCount
                        HioneBeatClock.onDiscontinuity()
                        val row = currentRow
                        if (row != null && row.firstBeatMs >= 0 && currentBpm > 0f) {
                            val pos = withContext(Dispatchers.Main) {
                                runCatching { com.hione.player.PlayerHolder.player?.currentPosition }.getOrNull()
                            }
                            if (pos != null && pos >= 0) HioneBeatClock.seedFromFileGrid(row.firstBeatMs, pos, SystemClock.elapsedRealtimeNanos())
                        }
                    }
                    var g = HioneBeatClock.grid
                    // The whole-file analysis ran and found no steady beat (ambient, rubato,
                    // spoken word): no tempo and no beat, rather than a live number that wanders.
                    val analysedNoBeat = currentBpm <= 0f && currentRow != null
                    if (analysedNoBeat) {
                        beatGridIntervalNanos = 0.0
                        if (lastPublishedBpm != 0f) { lastPublishedBpm = 0f; publishState(context, 0f, true) }
                        if (lastLoggedGrid != "no-beat") {
                            lastLoggedGrid = "no-beat"
                            android.util.Log.i(TAG, "beat grid: none, the file analysis found no steady beat")
                        }
                        delay(250)
                        continue
                    }
                    val state = "${g.source}/${g.phaseLocked}/${g.bpm}"
                    if (state != lastLoggedGrid) {
                        lastLoggedGrid = state
                        android.util.Log.i(TAG, "beat grid: source=${g.source} locked=${g.phaseLocked} " +
                            "bpm=${g.bpm} shown=$currentBpm")
                    }
                    // No file tempo yet: the live tracker's own tempo, once it has one.
                    if (currentBpm <= 0f && g.bpm > 0f && g.bpm != lastPublishedBpm) {
                        lastPublishedBpm = g.bpm
                        beatIntervalMs = (60_000f / g.bpm).toLong().coerceIn(60L, 4000L)
                        publishState(context, g.bpm, true)
                    }
                    if (g.periodNs <= 0.0) {
                        beatGridIntervalNanos = 0.0
                        delay(100)
                        continue
                    }
                    val now = SystemClock.elapsedRealtimeNanos()
                    if (g.anchorNs == 0L) {
                        // A tempo but no phase yet: run free from now, flagged as not phase-locked.
                        if (freeAnchorNs == 0L) freeAnchorNs = now
                        g = g.copy(anchorNs = freeAnchorNs)
                    } else {
                        freeAnchorNs = 0L
                    }
                    beatAnchorElapsedNanos = g.anchorNs
                    beatGridIntervalNanos = g.periodNs
                    var next = g.nextBeatAtOrAfter(now + 1_000_000L)
                    if (lastBeatNs != 0L && next - lastBeatNs < g.periodNs * 0.5) next = (next + g.periodNs).toLong()
                    val waitNs = next - SystemClock.elapsedRealtimeNanos()
                    if (waitNs > 0) delay((waitNs + 999_999L) / 1_000_000L)
                    if (!isActive || !isPlaying) break
                    lastBeatNs = next
                    val bpm = if (currentBpm > 0f) currentBpm else g.bpm
                    val epochMs = System.currentTimeMillis() + (next - SystemClock.elapsedRealtimeNanos()) / 1_000_000L
                    val intent = Intent(ACTION_BPM_PULSE).apply {
                        putExtra(EXTRA_BPM, bpm)
                        putExtra(EXTRA_BEAT_INTERVAL_MS, (g.periodNs / 1e6).toLong())
                        putExtra(EXTRA_IS_PLAYING, isPlaying)
                        putExtra(EXTRA_DOMINANT_COLOR, dominantColor)
                        putExtra(EXTRA_BEAT_EPOCH_MS, epochMs)
                        putExtra(EXTRA_BEAT_ELAPSED_NANOS, next)
                        putExtra(EXTRA_PHASE_LOCKED, g.phaseLocked && freeAnchorNs == 0L)
                        putExtra(EXTRA_TEMPO_SOURCE, g.source)
                    }
                    context.sendBroadcast(intent)
                }
            } finally {
                beatGridIntervalNanos = 0.0
            }
        }
    }

    private fun stopBeatPulse(context: Context) {
        beatJob?.cancel()
        beatJob = null
        beatGridIntervalNanos = 0.0
    }

    private fun publishState(context: Context, bpm: Float, playing: Boolean) {
        try {
            val cr = context.contentResolver
            // bpm <= 0 means "not known": publish 0 for both so a consumer renders "—" instead of
            // dividing by zero.
            val intervalMs = if (bpm > 0f) (60000f / bpm).toInt() else 0
            Settings.Global.putFloat(cr, "hione_live_bpm", if (bpm > 0f) bpm else 0f)
            Settings.Global.putInt(cr, "hione_beat_interval_ms", intervalMs)
            Settings.Global.putInt(cr, "hione_is_playing", if (playing) 1 else 0)
            Settings.Global.putInt(cr, "hione_album_dominant_color", dominantColor)
            // The track's own tempo, for the lockscreen BPM badge and the anatomical observatory.
            Settings.Global.putFloat(cr, "hione_now_playing_bpm", if (bpm > 0f) bpm else 0f)
            // Now-playing metadata for OS surfaces (launcher AOD face, BPM observatory).
            Settings.Global.putString(cr, "hione_now_playing_title", npTitle)
            Settings.Global.putString(cr, "hione_now_playing_artist", npArtist)
        } catch (_: Throwable) {}

        val intent = Intent(ACTION_BPM_UPDATE).apply {
            putExtra(EXTRA_BPM, if (bpm > 0f) bpm else 0f)
            putExtra(EXTRA_BEAT_INTERVAL_MS, if (bpm > 0f) (60000f / bpm).toLong() else 0L)
            putExtra(EXTRA_IS_PLAYING, playing)
            putExtra(EXTRA_DOMINANT_COLOR, dominantColor)
            putExtra(EXTRA_TEMPO_SOURCE, currentRow?.source ?: if (bpm > 0f) "live" else "none")
        }
        context.sendBroadcast(intent)
    }
}
