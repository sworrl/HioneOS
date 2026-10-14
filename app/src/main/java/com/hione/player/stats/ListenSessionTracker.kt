package com.hione.player.stats

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.hione.player.FastLibraryStore
import com.hione.player.LikeStore
import com.hione.player.HionePlayQualifier
import com.hione.player.PlayerPreferences
import com.hione.player.Track
import com.hione.player.TrackTech
import com.hione.player.scrobble.ScrobbleManager
import java.util.Calendar
import java.util.concurrent.Executors

/**
 * The ONE hook into playback for both the per-listen stats DB and Last.fm scrobbling.
 *
 * Attached once from PlayerHolder.ensure() (and reset from PlayerHolder.release()); it registers
 * its own Player.Listener on the shared ExoPlayer and runs a 1 s main-thread tick while playing,
 * so no other shared file needs per-event edits. All ExoPlayer reads happen on the main thread
 * (Media3's threading contract); every DB write is handed to a single background executor in
 * order, so "open row → checkpoints → finish row" can never race.
 *
 * A listen = one continuous occupancy of the player by one MediaItem. It opens on
 * onMediaItemTransition (or the first isPlaying=true if the item was already current), and
 * closes on the next transition, STATE_ENDED, or release. played_ms is accumulated from wall-
 * clock deltas while isPlaying — NOT from position — so seeking/scrubbing can't inflate it.
 *
 * Qualification mirrors HionePlayQualifier exactly (>= 94% heard, no forward scrub > 5 s); we read
 * its state on every tick because PlayerHolder's own listener resets it for the NEW track before
 * ours sees the transition.
 *
 * Scrobble rule (Last.fm's): track > 30 s, and played >= min(50% of duration, 4 min). Checked on
 * the tick; enqueued once per listen with the listen's start time as the timestamp.
 *
 * THREADING RULE (2026-09-11, the "Unsupported concurrent change during composition" crash-loop):
 * nothing this object runs on [io] may touch a Compose state object, directly or transitively.
 * The one place that did was the bit-depth / sample-rate lookup: TrackTech kept its results in a
 * SnapshotStateMap, so calling TrackTech.bitsFor / sampleRateFor from [io] wrote Compose state
 * from a background thread at the exact moment MainActivity's first composition was reading AND
 * writing that same map (badges + `remember { computeQualityBreakdown(...) }`) — one state object
 * written by composition and outside it, which is precisely what Recomposer.applyAndCheck throws
 * on. TrackTech is now main-thread-confined internally, and this object additionally never calls
 * it off the main thread: the tech values are sampled by [refreshTech] from the 1 s tick (main
 * thread, outside composition) into plain volatile Ints that the io tasks read. Keep it that way.
 */
object ListenSessionTracker {
    private const val TAG = "ListenTracker"
    private const val TICK_MS = 1_000L
    private const val CHECKPOINT_EVERY_TICKS = 30
    private const val SEEK_SKIP_JUMP_MS = 5_000L      // same as HionePlayQualifier
    private const val QUALIFY_THRESHOLD = HionePlayQualifier.THRESHOLD

    private val mainH = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "hione-listen-stats").apply { isDaemon = true } }

    @Volatile private var appCtx: Context? = null
    @Volatile private var attached = false
    @Volatile private var playerRef: ExoPlayer? = null

    /** Mutable per-listen state. Touched on the main thread; snapshot into the io tasks. */
    private class Session(
        val trackId: Long,
        val mediaId: String,
        var title: String,
        var artist: String,
        var album: String?,
        var durationMs: Long,
        val path: String?,
        val source: String,
        val startedAt: Long,
    ) {
        @Volatile var rowId: Long = -1L        // set on the io thread once the row is open (or -1 if stats off)
        var statsRow: Boolean = false
        var playedMs: Long = 0L
        var lastPlayingElapsed: Long = 0L      // SystemClock.elapsedRealtime() of the last accumulation point, 0 = paused
        var lastPosMs: Long = 0L
        var maxPosMs: Long = 0L
        var seekCount: Int = 0
        var skipped: Boolean = false
        var qualified: Boolean = false
        var nowPlayingSent: Boolean = false
        var scrobbleQueued: Boolean = false
        var ticks: Int = 0
        @Volatile var track: Track? = null     // resolved on io, read by refreshTech on main
        // Tech sampled on the MAIN thread only (see refreshTech) — the io tasks read these plain
        // values instead of calling TrackTech, which owns Compose state.
        @Volatile var techSampleRateHz: Int? = null
        @Volatile var techBits: Int? = null
        @Volatile var techResolved: Boolean = false
        val fraction: Float get() = if (durationMs > 0) (maxPosMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    }

    private var session: Session? = null
    private var trackIndex: Map<Long, Track>? = null
    private var trackIndexSource: List<Track>? = null

    // ------------------------------------------------------------------ lifecycle

    fun attach(context: Context, player: ExoPlayer) {
        // Kill switch kept for future bisects (`settings put global hione_dbg_off_stats 1`), but the
        // 2026-09-10 crash-loop that forced it on is fixed (TrackTech snapshot-map race, see the
        // THREADING RULE above) — so this subsystem is live again by default.
        if (com.hione.player.HioneDbg.off(context, "stats")) {
            Log.w(TAG, "stats disabled by hione_dbg_off_stats")
            return
        }
        if (attached) return
        attached = true
        val app = context.applicationContext
        appCtx = app
        playerRef = player
        // Defer EVERYTHING to a fresh main-loop message. attach() is reached from
        // PlayerHolder.ensure inside a composable remember{}; registering the listener and
        // starting a session synchronously here ran on the SAME frame as MainActivity's initial
        // composition and collided with the Compose snapshot ("Unsupported concurrent change
        // during composition" crash-loop, 2026-09-10). Posting guarantees the current composition
        // has committed first.
        mainH.post {
            player.addListener(listener)
            mainH.removeCallbacks(tick)
            mainH.postDelayed(tick, TICK_MS)
            io.execute {
                runCatching { ListenStatsDb.get(app).recoverOpenListens() }.onFailure { Log.w(TAG, "recover failed", it) }
                runCatching { ScrobbleManager.init(app) }.onFailure { Log.w(TAG, "scrobble init failed", it) }
            }
            if (player.isPlaying) player.currentMediaItem?.let { startSession(player, it) }
        }
    }

    /** From PlayerHolder.release(): close the current listen and forget the player. */
    fun reset() {
        val p = playerRef
        if (p != null) runCatching { p.removeListener(listener) }
        endSession("release")
        mainH.removeCallbacks(tick)
        playerRef = null
        attached = false
    }

    // ------------------------------------------------------------------ player events

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val p = playerRef ?: return
            val why = when (reason) {
                Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "auto"
                Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "auto"
                Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "skip"
                Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "stop"
                else -> "stop"
            }
            endSession(why)
            if (mediaItem != null) startSession(p, mediaItem)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val p = playerRef ?: return
            val s = session
            if (isPlaying) {
                if (s == null) { p.currentMediaItem?.let { startSession(p, it) }; return }
                if (s.lastPlayingElapsed == 0L) s.lastPlayingElapsed = SystemClock.elapsedRealtime()
                sendNowPlayingIfNeeded(s)
            } else if (s != null) {
                accumulate(s)
                s.lastPlayingElapsed = 0L
            }
        }

        override fun onPositionDiscontinuity(oldPos: Player.PositionInfo, newPos: Player.PositionInfo, reason: Int) {
            val s = session ?: return
            if (reason != Player.DISCONTINUITY_REASON_SEEK) return
            if (newPos.mediaItem?.mediaId != s.mediaId) return   // cross-item seek = a transition, handled above
            s.seekCount++
            if (newPos.positionMs - oldPos.positionMs > SEEK_SKIP_JUMP_MS) s.skipped = true
            s.lastPosMs = newPos.positionMs
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) endSession("auto")
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            val p = playerRef
            val s = session
            if (p != null && s != null && p.isPlaying) onTick(p, s)
            mainH.postDelayed(this, TICK_MS)
        }
    }

    // ------------------------------------------------------------------ session mechanics (main thread)

    private fun startSession(p: ExoPlayer, item: MediaItem) {
        val app = appCtx ?: return
        if (session != null) endSession("stop")
        val id = item.mediaId.toLongOrNull() ?: return
        val meta = item.mediaMetadata
        val uri = item.localConfiguration?.uri
        val scheme = uri?.scheme?.lowercase()
        val source = if (scheme == "http" || scheme == "https") "stream" else "local"
        val dur = p.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
        val s = Session(
            trackId = id, mediaId = item.mediaId,
            title = meta.title?.toString()?.trim().orEmpty(),
            artist = meta.artist?.toString()?.trim().orEmpty(),
            album = meta.albumTitle?.toString()?.trim()?.ifBlank { null },
            durationMs = dur,
            path = if (scheme == "file") uri?.path else null,
            source = source,
            startedAt = System.currentTimeMillis()
        )
        s.lastPlayingElapsed = if (p.isPlaying) SystemClock.elapsedRealtime() else 0L
        s.lastPosMs = p.currentPosition.coerceAtLeast(0L)
        s.maxPosMs = s.lastPosMs
        session = s

        val statsOn = StatsPreferences.isStatsEnabled(app)
        val output = runCatching { probeOutput(app) }.getOrNull()
        val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val vol = runCatching { am?.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull()
        val volMax = runCatching { am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrNull()
        val cal = Calendar.getInstance().apply { timeInMillis = s.startedAt }
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        // Power-governor gate read HERE (main thread): HionePowerGovernor's fields are Compose state,
        // and nothing on io may touch a state object — see the THREADING RULE on this object.
        val allowLocationPoll = runCatching { com.hione.player.HionePowerGovernor.allowLocation }.getOrDefault(false)

        io.execute {
            // Library lookup fills in whatever the MediaItem didn't carry (path, tags, duration).
            val t = resolveTrack(app, id)
            s.track = t
            // Sample the tech as soon as the Track exists, on the MAIN thread, instead of waiting
            // for the next tick — so even a short listen ends up with its bit depth / sample rate.
            if (t != null) mainH.post { refreshTech(s) }
            if (t != null) {
                if (s.title.isBlank()) s.title = t.title
                if (s.artist.isBlank()) s.artist = t.artist
                if (s.album == null && t.album.isNotBlank()) s.album = t.album
                if (s.durationMs <= 0L && t.durationMs > 0L) s.durationMs = t.durationMs
            }
            val path = s.path ?: t?.path?.ifBlank { null }
            // NO TrackTech here — it owns Compose state (see the THREADING RULE). The row opens with
            // whatever refreshTech has sampled on the main thread so far (usually nothing yet, since
            // the track was only just resolved above); finishListen fills the real values in, which
            // is what it already did for tracks whose probe completed mid-listen.
            val sr = s.techSampleRateHz
            val bits = s.techBits
            val loc = runCatching { ListenLocationSampler.sample(app, allowLocationPoll) }.getOrNull()
            if (!statsOn) return@execute
            try {
                val rowId = ListenStatsDb.get(app).openListen(
                    ListenStatsDb.Listen(
                        trackId = id, path = path, title = s.title.ifBlank { "Unknown" }, artist = s.artist, album = s.album,
                        durationMs = s.durationMs, startedAt = s.startedAt, endedAt = null, playedMs = 0L, fraction = 0f,
                        qualified = false, skipped = false, seekCount = 0, source = s.source, output = output,
                        volume = vol, volumeMax = volMax, sampleRateHz = sr, bitDepth = bits,
                        lat = loc?.lat, lon = loc?.lon, locationLabel = loc?.label,
                        dayOfWeek = dow, hour = hour, heartsDuring = 0, heartCountAfter = 0, endReason = null
                    )
                )
                s.rowId = rowId
                s.statsRow = rowId > 0
            } catch (t: Throwable) {
                Log.w(TAG, "openListen failed", t)
            }
        }
        if (p.isPlaying) sendNowPlayingIfNeeded(s)
    }

    private fun onTick(p: ExoPlayer, s: Session) {
        accumulate(s)
        val pos = p.currentPosition
        val dur = p.duration
        if (dur > 0 && dur != C.TIME_UNSET) s.durationMs = dur
        if (pos >= 0) { s.lastPosMs = pos; if (pos > s.maxPosMs) s.maxPosMs = pos }
        // Metadata can land after prepare for items that were built from a bare path.
        if (s.title.isBlank() || s.artist.isBlank()) {
            val m = p.mediaMetadata
            if (s.title.isBlank()) s.title = m.title?.toString()?.trim().orEmpty()
            if (s.artist.isBlank()) s.artist = m.artist?.toString()?.trim().orEmpty()
            if (s.album == null) s.album = m.albumTitle?.toString()?.trim()?.ifBlank { null }
        }
        // Sample bit depth / sample rate on the main thread (never from io): TrackTech mirrors its
        // results into a Compose state map, so this must stay on the composition's own thread.
        refreshTech(s)
        // Mirror the heart qualifier (authoritative for "qualified" while it still tracks this id).
        if (HionePlayQualifier.wasSkipped(s.trackId)) s.skipped = true
        if (HionePlayQualifier.isQualified(s.trackId)) s.qualified = true
        else if (!s.skipped && s.durationMs > 0 && s.maxPosMs >= (s.durationMs * QUALIFY_THRESHOLD).toLong()) s.qualified = true

        sendNowPlayingIfNeeded(s)

        // Last.fm rule: > 30 s long, played >= min(half, 4 min).
        if (!s.scrobbleQueued && s.durationMs > 30_000L && s.artist.isNotBlank() && s.title.isNotBlank()) {
            val threshold = minOf(s.durationMs / 2, 4 * 60_000L)
            if (s.playedMs >= threshold) {
                s.scrobbleQueued = true
                ScrobbleManager.enqueue(
                    listenId = s.rowId.takeIf { it > 0 }, artist = s.artist, track = s.title, album = s.album,
                    durationSec = (s.durationMs / 1000).toInt(), timestampSec = s.startedAt / 1000
                )
            }
        }

        s.ticks++
        if (s.ticks % CHECKPOINT_EVERY_TICKS == 0) {
            val snap = snapshot(s)
            io.execute {
                val app = appCtx ?: return@execute
                if (s.statsRow && s.rowId > 0) runCatching {
                    ListenStatsDb.get(app).checkpointListen(s.rowId, snap.playedMs, snap.fraction, snap.qualified, snap.skipped, snap.seekCount)
                }
                ScrobbleManager.onTick()
            }
        }
    }

    private fun endSession(reason: String) {
        val s = session ?: return
        session = null
        accumulate(s)
        s.lastPlayingElapsed = 0L
        val app = appCtx ?: return
        val endedAt = System.currentTimeMillis()
        // Final qualifier read while it may still hold this track's state (STATE_ENDED / release
        // paths don't reset it; the transition path already did, hence the tick mirror above).
        if (HionePlayQualifier.isQualified(s.trackId)) s.qualified = true
        if (HionePlayQualifier.wasSkipped(s.trackId)) s.skipped = true
        // Last main-thread chance to pick up a probe that finished since the previous tick.
        refreshTech(s)
        val snap = snapshot(s)
        io.execute {
            if (!s.statsRow || s.rowId <= 0) return@execute
            try {
                val db = ListenStatsDb.get(app)
                if (snap.playedMs < 1_000L) { db.deleteListen(s.rowId); return@execute }   // never counted as a listen
                val hearts = runCatching {
                    PlayerPreferences.getHeartEvents(app, s.trackId).count { (at, _, _) -> at in s.startedAt..endedAt }
                }.getOrDefault(0)
                val heartCount = runCatching { LikeStore.heartCount(app, s.trackId) }.getOrDefault(0)
                // Main-thread-sampled values only (refreshTech); never a TrackTech call from io.
                val sr = s.techSampleRateHz
                val bits = s.techBits
                db.finishListen(
                    id = s.rowId, endedAt = endedAt, playedMs = snap.playedMs, fraction = snap.fraction,
                    qualified = snap.qualified, skipped = snap.skipped, seekCount = snap.seekCount,
                    heartsDuring = hearts, heartCountAfter = heartCount, endReason = reason,
                    sampleRateHz = sr, bitDepth = bits, durationMs = snap.durationMs,
                    title = snap.title, artist = snap.artist, album = snap.album
                )
            } catch (t: Throwable) {
                Log.w(TAG, "finishListen failed", t)
            }
        }
    }

    private class Snap(val playedMs: Long, val fraction: Float, val qualified: Boolean, val skipped: Boolean, val seekCount: Int,
                       val durationMs: Long, val title: String, val artist: String, val album: String?)

    private fun snapshot(s: Session) = Snap(s.playedMs, s.fraction, s.qualified, s.skipped, s.seekCount, s.durationMs, s.title, s.artist, s.album)

    /** Fold elapsed wall-clock time since the last accumulation point into playedMs. */
    private fun accumulate(s: Session) {
        val last = s.lastPlayingElapsed
        if (last == 0L) return
        val now = SystemClock.elapsedRealtime()
        val delta = now - last
        // Guard against a suspended main thread delivering one giant delta: cap each accumulation
        // at 3 ticks. Deep sleep with the player "playing" would otherwise over-count.
        if (delta > 0) s.playedMs += minOf(delta, TICK_MS * 3)
        s.lastPlayingElapsed = now
    }

    /**
     * Sample TrackTech's bit depth / sample rate into the session's plain volatile fields.
     *
     * MAIN THREAD ONLY, and it asserts that rather than trusting its callers: TrackTech mirrors its
     * results into a Compose SnapshotStateMap, so reaching it from the [io] executor is what put one
     * state object in the hands of both composition and a background thread. A main-looper callback
     * (the 1 s tick, a player callback) can never be nested inside a composition pass, so sampling
     * here is safe; the io tasks then only ever read Ints.
     *
     * bitsFor returns null while the async probe is still running and 0 for "lossy, not applicable",
     * so the first non-null answer is final and we stop asking.
     */
    private fun refreshTech(s: Session) {
        if (s.techResolved) return
        val app = appCtx ?: return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            // Tripwire (silent unless hione_dbg_snaplog=1): a future caller that reaches this from a
            // background thread gets named in logcat instead of reintroducing the crash.
            com.hione.player.HioneDbg.expectMain(app, "ListenSessionTracker.refreshTech -> TrackTech")
            return
        }
        val t = s.track ?: return
        val bits = runCatching { TrackTech.bitsFor(app, t) }.getOrNull() ?: return
        s.techResolved = true
        s.techBits = bits.takeIf { it > 0 }
        s.techSampleRateHz = runCatching { TrackTech.sampleRateFor(app, t) }.getOrNull()
    }

    private fun sendNowPlayingIfNeeded(s: Session) {
        if (s.nowPlayingSent) return
        if (s.artist.isBlank() || s.title.isBlank()) return
        s.nowPlayingSent = true
        ScrobbleManager.nowPlaying(s.artist, s.title, s.album, (s.durationMs / 1000).toInt().takeIf { it > 0 })
    }

    // ------------------------------------------------------------------ helpers

    /** id → Track from the fast library cache; rebuilt only when the cached list instance changes. */
    private fun resolveTrack(app: Context, id: Long): Track? {
        val list = runCatching { FastLibraryStore.loadSync(app) }.getOrNull() ?: return null
        var idx = trackIndex
        if (idx == null || trackIndexSource !== list) {
            idx = HashMap<Long, Track>(list.size * 2).also { m -> for (t in list) m[t.id] = t }
            trackIndex = idx
            trackIndexSource = list
        }
        return idx[id]
    }

    /** Active output route as a short label. On API 33+ we ask the framework which device(s)
     *  media actually routes to; below that we fall back to "what's connected", preferring the
     *  routes Android itself would prefer (BT > USB > wired > speaker). null = couldn't tell. */
    private fun probeOutput(app: Context): String? {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        val devices: List<AudioDeviceInfo> = if (Build.VERSION.SDK_INT >= 33) {
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            runCatching { am.getAudioDevicesForAttributes(attrs) }.getOrNull()?.toList()
                ?: am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        } else am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        if (devices.isEmpty()) return null
        val types = devices.map { it.type }.toSet()
        return when {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP in types || AudioDeviceInfo.TYPE_BLUETOOTH_SCO in types ||
                (Build.VERSION.SDK_INT >= 33 && (AudioDeviceInfo.TYPE_BLE_HEADSET in types || AudioDeviceInfo.TYPE_BLE_SPEAKER in types)) -> "bt"
            AudioDeviceInfo.TYPE_USB_DEVICE in types || AudioDeviceInfo.TYPE_USB_HEADSET in types || AudioDeviceInfo.TYPE_USB_ACCESSORY in types -> "usb"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES in types || AudioDeviceInfo.TYPE_WIRED_HEADSET in types ||
                AudioDeviceInfo.TYPE_LINE_ANALOG in types || AudioDeviceInfo.TYPE_AUX_LINE in types -> "wired"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER in types -> "speaker"
            AudioDeviceInfo.TYPE_HDMI in types || AudioDeviceInfo.TYPE_LINE_DIGITAL in types -> "digital"
            else -> "other"
        }
    }
}
