package com.hione.launcher.ui

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Home-screen "attention" gate for ambient eye-candy (dock aura, plasma clock colour sweep,
 * capsule shimmers, telemetry glows, weather bob…).
 *
 * The 2026-08-25 pass-C profile showed the idle home rendering 60 fps forever (1800 frames /
 * 30 s, p50 29 ms) purely from these infinite transitions. A Pixel launcher is *still* when
 * nobody is looking at it, so ambient animation now runs only while there is attention:
 *  - the user touched the launcher within [ATTENTION_MS], or
 *  - music is playing (the hearts pulse on the beat and the EQ badge want company), or
 *  - the launcher is being shown fresh (first [ATTENTION_MS] after resume),
 * and never while a drawer/modal covers home, while the launcher is stopped, or in the
 * low-power profiles. Outside that window every gated animator freezes at its rest value and
 * stops subscribing to the frame clock, so the only periodic redraw left is the 1 Hz clock /
 * hearts pulse.
 */
object HioneAmbient {
    const val ATTENTION_MS = 15_000L

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val _attention = MutableStateFlow(true)
    private val _playing = MutableStateFlow(false)
    private val _covered = MutableStateFlow(0)
    private var expiry: Job? = null

    /** True while the user is (recently) interacting. */
    val attention: StateFlow<Boolean> = _attention.asStateFlow()
    /** True while Hione Music reports playback (beat-synced badges stay lively). */
    val playing: StateFlow<Boolean> = _playing.asStateFlow()
    /** > 0 while the drawer or a modal covers the home surface. */
    val covered: StateFlow<Int> = _covered.asStateFlow()

    /** Call from the activity's touch dispatch / resume: (re)arms the attention window. */
    fun touch() {
        _attention.value = true
        expiry?.cancel()
        expiry = scope.launch {
            delay(ATTENTION_MS)
            _attention.value = false
        }
    }

    fun setPlaying(on: Boolean) { if (_playing.value != on) _playing.value = on }

    fun pushCovered() { _covered.value = _covered.value + 1 }
    fun popCovered() { _covered.value = (_covered.value - 1).coerceAtLeast(0) }

    /** Non-compose read (modifiers, draw lambdas). */
    val isActive: Boolean
        get() = (_attention.value || _playing.value) && _covered.value == 0 &&
            HionePowerProfile.visible.value && !HionePowerProfile.isLowPower &&
            // The OS-wide idle ladder has taken the panel dim: whatever is on screen is barely
            // visible and about to sleep, so no ambient animation is worth a frame.
            HioneIdleTier.tier.value == HioneIdleTier.TIER_ACTIVE
}

/**
 * Read-only mirror of the OS-wide idle ladder that HioneOS SystemUI runs
 * (hioneos-systemui/.../HioneIdleDim.kt): it publishes `Settings.Global hione_idle_tier` =
 * 0 ACTIVE / 1 DIM / 2 AMBIENT whenever the tier changes, and dims the real backlight.
 *
 * Cross-process by a ContentObserver, so this costs one callback per tier change (at most three
 * per idle cycle) and nothing at all in between - no polling, matching the ladder's own
 * event-driven design. If SystemUI is not running the key is simply absent and the tier stays
 * ACTIVE, i.e. the launcher behaves exactly as it did before.
 */
object HioneIdleTier {
    const val KEY = "hione_idle_tier"
    const val TIER_ACTIVE = 0

    private val _tier = MutableStateFlow(TIER_ACTIVE)
    val tier: StateFlow<Int> = _tier.asStateFlow()
    private var observer: ContentObserver? = null

    /** Idempotent; safe to call from anywhere that has a Context. */
    fun attach(ctx: Context) {
        if (observer != null) return
        val app = ctx.applicationContext
        fun read() {
            _tier.value = runCatching { Settings.Global.getInt(app.contentResolver, KEY, TIER_ACTIVE) }
                .getOrDefault(TIER_ACTIVE)
        }
        read()
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { read() }
        }
        // Only remember it if the registration actually took, so a failure can be retried on the
        // next composition rather than silently leaving the tier pinned at ACTIVE forever.
        runCatching { app.contentResolver.registerContentObserver(Settings.Global.getUriFor(KEY), false, obs) }
            .onSuccess { observer = obs }
    }
}

/**
 * Gate flag for [InfiniteTransition.gatedFloat] / [gatedColor]: TRUE = freeze. Combines the
 * low-power profile with the ambient attention window, so a call site changes from
 * `rememberLowPower()` to `rememberAmbientGate()` and nothing else.
 */
@Composable
fun rememberAmbientGate(): State<Boolean> {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // Attach the OS idle-tier mirror the first time any gated animation composes. Idempotent, so
    // the hundreds of call sites cost one registration in total.
    LaunchedEffect(Unit) { HioneIdleTier.attach(ctx) }
    val low by rememberLowPower()
    val attention by HioneAmbient.attention.collectAsState()
    val playing by HioneAmbient.playing.collectAsState()
    val covered by HioneAmbient.covered.collectAsState()
    val visible by HionePowerProfile.visible.collectAsState()
    val idleTier by HioneIdleTier.tier.collectAsState()
    return remember {
        derivedStateOf {
            // idleTier > 0 = the OS ladder has dimmed the backlight; freeze everything.
            low || covered > 0 || !visible || !(attention || playing) || idleTier != HioneIdleTier.TIER_ACTIVE
        }
    }
}
