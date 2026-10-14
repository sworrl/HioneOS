package com.hione.launcher.bpm

import com.hione.launcher.Mascot
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * OS-Level Real-Time BPM Client for HioneOS SystemUI & Launcher.
 * Collects live tempo, beat pulses, and dominant album colors from HioneMusic.
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
    const val EXTRA_PHASE_LOCKED = "phase_locked"

    // Every value that enters BpmState is clamped to these — the receiver is necessarily
    // RECEIVER_EXPORTED (pulses come from com.hione.player), so ANY app can broadcast these
    // actions. A bogus sender putting tempo in the wrong unit (e.g. beats-per-second ≈ 0.5,
    // or a raw beat interval) must never reach UI readouts as if it were BPM.
    private const val MIN_BPM = 20f
    private const val MAX_BPM = 999f
    private const val MIN_INTERVAL_MS = 60L
    private const val MAX_INTERVAL_MS = 4000L

    data class BpmState(
        // 0 = no tempo has been reported yet. Readouts must treat anything outside 20..999 as "—".
        val bpm: Float = 0f,
        val beatIntervalMs: Long = 500L,
        val isPlaying: Boolean = false,
        val dominantColor: Int = Mascot.argb(0xFFFFC56B.toInt()),
        val lastPulseEpochMs: Long = 0L
    )

    private val _state = MutableStateFlow(BpmState())
    val state: StateFlow<BpmState> = _state.asStateFlow()

    private var receiver: BroadcastReceiver? = null

    /** Finite + in-range tempo, else the previous known-good value. */
    private fun sanitizeBpm(candidate: Float, fallback: Float): Float =
        if (candidate.isFinite() && candidate in MIN_BPM..MAX_BPM) candidate else fallback

    /** Positive, sane beat interval, else derived from the (already sanitized) tempo; a 0 tempo
     *  (nothing reported yet) keeps the animation-only 500 ms default. */
    private fun sanitizeInterval(candidate: Long, bpm: Float): Long =
        if (candidate in MIN_INTERVAL_MS..MAX_INTERVAL_MS) candidate
        else if (bpm <= 0f) 500L
        else (60_000f / bpm).toLong().coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)

    // Which path last set the tempo. A tempo published by com.hione.player comes from offline
    // file analysis of a known track and is authoritative; the live detector's is inferred from
    // the output mix. clearLiveTempo() must only ever drop the latter.
    @Volatile private var lastTempoFromLiveDetector = false

    /**
     * When the live detector last published a LOCKED tempo. While that is recent, the live lock
     * owns the beat grid and com.hione.player's pulses are ignored.
     *
     * WHY. Two beat sources used to write the same state: the player's file-analysis pulse loop
     * (constant tempo, but its phase is wherever the loop happened to start) and the live
     * detector's onset lock (phase taken from the real kick in the output mix). Every pulse from
     * either overwrote bpm AND lastPulseEpochMs, so when both ran the readout flipped between the
     * two estimates (128 ↔ 131) and the note highway's phase jumped back and forth between two
     * grids — exactly the "it keeps jumping between BPMs" report. One owner at a time fixes both:
     * the live lock when it has one (its phase is the audible one), the player otherwise.
     */
    @Volatile private var lastLiveLockMs = 0L
    private const val LIVE_OWNERSHIP_MS = 4_000L

    enum class TempoSource { NONE, PLAYER_ANALYSIS, LIVE_LOCK }

    private val _source = MutableStateFlow(TempoSource.NONE)
    /** Which path the current tempo came from, so a readout can say "locked" honestly for both. */
    val source: StateFlow<TempoSource> = _source.asStateFlow()

    /**
     * Phase-locked beat anchor. A pulse used to SET the grid (lastPulseEpochMs = now), so every
     * bit of timestamp noise moved every note on the highway and every judgment with it. That
     * noise is large here: live onsets are only seen when a Visualizer FFT frame arrives (the
     * capture rate is ~20 Hz, so tens of ms of quantisation), and a broadcast from the player is
     * delivered whenever the system gets to it. A real beat does not wobble like that.
     *
     * So a pulse now NUDGES the grid: predict where this beat should be from the previous anchor
     * and the period, and move a quarter of the way toward the observed time. Random jitter
     * averages out; a genuine phase error (a seek, a skipped pulse) still converges within a few
     * beats; and a long gap or a tempo change just restarts from the observed pulse. The result
     * is still measured, only less noisy — it never adds a beat that was not heard.
     */
    private fun smoothedAnchor(prevAnchor: Long, prevPeriodMs: Long, periodMs: Long, now: Long): Long {
        if (prevAnchor <= 0L || periodMs <= 0L || kotlin.math.abs(prevPeriodMs - periodMs) > 3L) return now
        val since = now - prevAnchor
        if (since < 0L || since > periodMs * 4L) return now
        val n = Math.round(since.toDouble() / periodMs.toDouble())
        val predicted = prevAnchor + n * periodMs
        val err = now - predicted
        // An error near half a beat is ambiguous (which beat was it?) — trust the observation.
        if (kotlin.math.abs(err) > periodMs / 3L) return now
        return predicted + (err * PHASE_GAIN).toLong()
    }
    private const val PHASE_GAIN = 0.25

    private fun liveOwnsGrid(): Boolean =
        !playerOwnsGrid() &&
            lastLiveLockMs != 0L && System.currentTimeMillis() - lastLiveLockMs < LIVE_OWNERSHIP_MS &&
            HioneTempoLock.tempo.value.isLocked

    /**
     * When the last phase-locked pulse from com.hione.player arrived. Hione Music now tracks beats
     * on its own decoded PCM (BTrack, seeded with the track's analysed tempo) and stamps every
     * pulse with the time the beat leaves the DAC. That beats anything this process can infer
     * from the Visualizer's 20 Hz FFT, and the Visualizer cannot hear Hione Music's bit-perfect
     * DIRECT output at all. While such pulses keep coming the player owns the grid and the live
     * detector stands down.
     */
    @Volatile private var lastPlayerLockMs = 0L
    private const val PLAYER_OWNERSHIP_MS = 4_000L

    fun playerOwnsGrid(): Boolean =
        lastPlayerLockMs != 0L && System.currentTimeMillis() - lastPlayerLockMs < PLAYER_OWNERSHIP_MS

    /** Live output-mix detector reports a beat at [bpm] — authoritative "audio is playing". */
    fun pushLivePulse(bpm: Float) {
        if (playerOwnsGrid()) { pushLivePlaying(true); return }
        val prev = _state.value
        val b = sanitizeBpm(bpm, prev.bpm)
        lastTempoFromLiveDetector = true
        lastLiveLockMs = System.currentTimeMillis()
        _source.value = TempoSource.LIVE_LOCK
        val interval = sanitizeInterval((60_000f / b).toLong(), b)
        _state.value = prev.copy(
            bpm = b,
            beatIntervalMs = interval,
            isPlaying = true,
            lastPulseEpochMs = smoothedAnchor(prev.lastPulseEpochMs, prev.beatIntervalMs, interval, System.currentTimeMillis())
        )
    }

    /**
     * The live detector lost its tempo lock (track change, or output stopped). Drop the tempo
     * rather than leaving the PREVIOUS track's BPM on screen looking like the current one —
     * a stale number presented as live is the same lie as an invented one. A tempo that came
     * from the player's own file analysis is left alone.
     */
    fun clearLiveTempo() {
        if (!lastTempoFromLiveDetector) return
        lastLiveLockMs = 0L
        val prev = _state.value
        if (prev.bpm == 0f && prev.lastPulseEpochMs == 0L) return
        _source.value = TempoSource.NONE
        _state.value = prev.copy(bpm = 0f, beatIntervalMs = 500L, lastPulseEpochMs = 0L)
    }

    /** Live detector reports whether the DAC is actually outputting audio (gates "ALSA Standby"). */
    fun pushLivePlaying(playing: Boolean) {
        val prev = _state.value
        if (prev.isPlaying != playing) _state.value = prev.copy(isPlaying = playing)
    }

    fun startListening(context: Context) {
        if (receiver != null) return
        val cr = context.contentResolver

        // Initial state from Settings.Global — reads sanitized like broadcasts (stale keys
        // from older builds may hold values in the wrong unit).
        // Missing/garbage key = 0 (no tempo yet), NOT a presumed 120.
        val initBpm = sanitizeBpm(
            try { Settings.Global.getFloat(cr, "hione_live_bpm", 0f) } catch (_: Throwable) { 0f },
            0f
        )
        val initInterval = sanitizeInterval(
            try { Settings.Global.getInt(cr, "hione_beat_interval_ms", 500).toLong() } catch (_: Throwable) { 500L },
            initBpm
        )
        val initPlaying = try { Settings.Global.getInt(cr, "hione_is_playing", 0) == 1 } catch (_: Throwable) { false }
        val initColor = try { Settings.Global.getInt(cr, "hione_album_dominant_color", Mascot.argb(0xFFFFC56B.toInt())) } catch (_: Throwable) { Mascot.argb(0xFFFFC56B.toInt()) }

        _state.value = BpmState(
            bpm = initBpm,
            beatIntervalMs = initInterval,
            isPlaying = initPlaying,
            dominantColor = initColor
        )

        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                // Nothing in here may throw: an exported receiver that throws crashes the
                // whole launcher process on a hostile/malformed broadcast.
                try {
                    when (intent.action) {
                        ACTION_BPM_UPDATE -> {
                            val prev = _state.value
                            val playing0 = intent.getBooleanExtra(EXTRA_IS_PLAYING, prev.isPlaying)
                            val color0 = intent.getIntExtra(EXTRA_DOMINANT_COLOR, prev.dominantColor)
                            if (liveOwnsGrid()) {
                                // The live lock owns the tempo right now; take only the
                                // play state and colour from the player. See lastLiveLockMs.
                                _state.value = prev.copy(isPlaying = playing0, dominantColor = color0)
                                return
                            }
                            lastTempoFromLiveDetector = false
                            val bpm = sanitizeBpm(intent.getFloatExtra(EXTRA_BPM, prev.bpm), prev.bpm)
                            val interval = sanitizeInterval(
                                intent.getLongExtra(EXTRA_BEAT_INTERVAL_MS, prev.beatIntervalMs), bpm
                            )
                            val playing = intent.getBooleanExtra(EXTRA_IS_PLAYING, prev.isPlaying)
                            val color = intent.getIntExtra(EXTRA_DOMINANT_COLOR, prev.dominantColor)
                            _source.value = if (bpm > 0f) TempoSource.PLAYER_ANALYSIS else TempoSource.NONE
                            _state.value = prev.copy(
                                bpm = bpm,
                                beatIntervalMs = interval,
                                isPlaying = playing,
                                dominantColor = color
                            )
                        }
                        ACTION_BPM_PULSE -> {
                            val prev = _state.value
                            // Claim the grid before the ownership test below, or a live lock taken
                            // a moment earlier would keep the player's exact pulses out for good.
                            if (intent.getBooleanExtra(EXTRA_PHASE_LOCKED, false) &&
                                intent.getLongExtra(EXTRA_BEAT_EPOCH_MS, 0L) > 0L) {
                                lastPlayerLockMs = System.currentTimeMillis()
                            }
                            if (liveOwnsGrid()) {
                                // Ignored: a second phase source would drag the beat grid back and
                                // forth between two clocks. See lastLiveLockMs.
                                return
                            }
                            lastTempoFromLiveDetector = false
                            val bpm = sanitizeBpm(intent.getFloatExtra(EXTRA_BPM, prev.bpm), prev.bpm)
                            val interval = sanitizeInterval(
                                intent.getLongExtra(EXTRA_BEAT_INTERVAL_MS, prev.beatIntervalMs), bpm
                            )
                            val playing = intent.getBooleanExtra(EXTRA_IS_PLAYING, true)
                            val color = intent.getIntExtra(EXTRA_DOMINANT_COLOR, prev.dominantColor)
                            // A phase-locked pulse says exactly when its beat is heard. Use that
                            // time as the anchor: it is already smoothed on the player's side, and
                            // the broadcast's own delivery delay is not part of it.
                            val beatEpoch = intent.getLongExtra(EXTRA_BEAT_EPOCH_MS, 0L)
                            val locked = intent.getBooleanExtra(EXTRA_PHASE_LOCKED, false)
                            val now = System.currentTimeMillis()
                            val exact = locked && beatEpoch > 0L && kotlin.math.abs(now - beatEpoch) < 2_000L
                            if (exact) lastPlayerLockMs = now
                            _state.value = prev.copy(
                                bpm = bpm,
                                beatIntervalMs = interval,
                                isPlaying = playing,
                                dominantColor = color,
                                lastPulseEpochMs = if (exact) beatEpoch
                                    else smoothedAnchor(prev.lastPulseEpochMs, prev.beatIntervalMs, interval, now)
                            )
                            if (bpm > 0f) _source.value = TempoSource.PLAYER_ANALYSIS
                        }
                    }
                } catch (t: Throwable) {
                    // Malformed extras from a foreign sender — keep last good state, say so once
                    // per occurrence instead of dying or going silent.
                    Log.w(TAG, "Dropped malformed BPM broadcast (${intent.action})", t)
                }
                // Keep the placed BPM widgets live. pushUpdate throttles itself (pulses arrive
                // every beat) and no-ops with none placed; never let it take down the receiver.
                try {
                    val app = ctx.applicationContext
                    com.hione.launcher.widget.HioneBpmWidget.pushUpdate(app)
                    com.hione.launcher.widget.HioneBpmComboWidget.pushUpdate(app)
                } catch (_: Throwable) {}
            }
        }

        val filter = IntentFilter().apply {
            addAction(ACTION_BPM_UPDATE)
            addAction(ACTION_BPM_PULSE)
        }
        try {
            // RECEIVER_EXPORTED is REQUIRED on Android 13+ for cross-app broadcasts (the pulses
            // come from com.hione.player). The old flag-less register threw SecurityException on
            // Android 14 — swallowed by this catch — so the receiver never attached and the badge
            // sat frozen at the 120 BPM default forever. That was the whole "BPM locked" bug.
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(r, filter)
            }
            receiver = r
        } catch (t: Throwable) {
            // Never crash the launcher over this, but never hide it either — an unregistered
            // receiver means the whole BPM UI silently freezes at defaults (seen once already,
            // see the comment above). One warning per attempt; startListening retries on next call.
            Log.w(TAG, "BPM receiver registration failed — live tempo UI will run on defaults", t)
        }

        // Live output-mix beat detection so the counter "sees ALSA" for ANY source (Spotify etc),
        // not only Hione's offline file analysis. Idempotent; fails soft without RECORD_AUDIO.
        try { HioneLiveBeatDetector.start(context.applicationContext) } catch (_: Throwable) {}
    }
}
