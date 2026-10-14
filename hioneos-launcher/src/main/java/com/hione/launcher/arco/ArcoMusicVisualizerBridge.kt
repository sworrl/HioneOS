package com.hione.launcher.arco

import android.content.Context
import android.util.Log
import com.hione.launcher.bpm.HioneBpmEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/**
 * Bridges HioneOS's OS-level BPM engine ([HioneBpmEngine], fed by the
 * `com.hione.action.BPM_UPDATE` / `BPM_PULSE` broadcasts and the
 * `hione_live_bpm` Settings.Global key — see HioneBpmEngine.kt) into arco's
 * music-visualizer feed (`POST /api/audio`, consumed by themes like
 * `music_spectrum` / `music_bass_pulse`; API_REFERENCE.md §2).
 *
 * TODO(monolith): this synthesizes an approximate envelope + a fake 8-band
 * spread from BPM/beat-interval only — HioneOS's BPM engine does not currently
 * broadcast real per-band spectral data (it broadcasts tempo + a beat pulse
 * timestamp + a dominant album color, not an FFT). Real 8-band EQ data would
 * need to be captured where the actual audio session lives (HioneMusic /
 * HioneLauncherActivity's playback stack) and broadcast the same way BPM
 * already is, e.g. a new `com.hione.action.EQ_BANDS` intent with a
 * `float[8] bands` extra — then swap the synthesis below for a direct
 * pass-through. Until that exists, this is a clearly-labeled placeholder,
 * not real audio analysis.
 */
object ArcoMusicVisualizerBridge {
    private const val TAG = "ArcoMusicVisualizerBridge"
    private const val PUSH_INTERVAL_MS = 33L // ~30 FPS, matches WEBSOCKET_PROTOCOL.md's audio_spectrum broadcast rate

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    /** Starts the bridge loop (idempotent). Actual pushing only happens while
     * both [ArcoClient.musicVisualizerBridgeEnabled] is on and the BPM engine
     * reports something playing — this is safe to call unconditionally from
     * app/Activity startup. */
    fun start(context: Context) {
        HioneBpmEngine.startListening(context)
        if (job?.isActive == true) return
        job = scope.launch {
            var lastPulseSeen = 0L
            while (isActive) {
                delay(PUSH_INTERVAL_MS)
                if (!ArcoClient.musicVisualizerBridgeEnabled.value) continue
                if (!ArcoClient.isPaired && !ArcoClient.isDirectKeyConfigured) continue

                val bpmState = HioneBpmEngine.state.value
                if (!bpmState.isPlaying) continue

                val intervalMs = bpmState.beatIntervalMs.coerceIn(240L, 1600L)
                val elapsed = (System.currentTimeMillis() - bpmState.lastPulseEpochMs).coerceAtLeast(0L)
                val phase = (elapsed.toFloat() / intervalMs.toFloat()).coerceIn(0f, 1f)
                // Sharp attack at the beat, quadratic decay — mirrors the
                // reference client's badge envelope shape (HioneNowPlayingBadge).
                val envelope = (1f - phase) * (1f - phase)
                val isFreshPulse = bpmState.lastPulseEpochMs != lastPulseSeen
                if (isFreshPulse) lastPulseSeen = bpmState.lastPulseEpochMs

                val level = (0.18f + 0.75f * envelope).coerceIn(0f, 1f)
                val beat = if (isFreshPulse) 1f else (envelope * envelope)
                val bands = (0 until 8).map { i ->
                    val spread = 0.5f + 0.5f * sin((phase * PI * 2 + i * 0.6f).toFloat())
                    (level * (0.55f + 0.45f * spread)).coerceIn(0f, 1f)
                }

                ArcoClient.pushAudio(level, beat, bands).onFailure {
                    Log.v(TAG, "audio push skipped: ${it.message}")
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
