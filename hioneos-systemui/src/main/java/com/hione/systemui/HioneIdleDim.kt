package com.hione.systemui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * OS-WIDE IDLE DIM + TAP-TO-AWAKEN.
 *
 * The problem this solves: the panel had `screen_off_timeout = 15000` and NO dim stage, so the
 * screen cut straight from full brightness to black 15 seconds after the last touch. There is no
 * double-tap-to-wake on this hardware (`doze_pulse_on_double_tap` is null, no dt2w node/props), so
 * the touch panel almost certainly powers down with the display — a "tap to wake a dark screen"
 * design would simply not work here. The design is therefore: keep the display ON but take it very
 * dim, so the digitizer stays powered and ANY touch restores full brightness instantly.
 *
 * MECHANISM — real system backlight, not a dark overlay.
 * -----------------------------------------------------
 * The dim is applied by writing `Settings.System.SCREEN_BRIGHTNESS` (verified on-device: the panel
 * is in MANUAL mode with brightness 82/255, and writing 20 / 82 dims and restores the backlight
 * immediately). This was chosen over the obvious alternative (a full-screen black scrim window in
 * the accessibility overlay layer) because:
 *   · it dims the actual BACKLIGHT, so it genuinely saves power — a scrim saves nothing, the panel
 *     still burns the same current behind a black rectangle. Battery matters on this device.
 *   · it applies to every app without owning that app's window. `WindowManager.LayoutParams
 *     .screenBrightness` is a PER-WINDOW override; the launcher and lockscreen own windows but
 *     Spotify/Chrome/Settings do not, so a per-window approach could never be OS-wide.
 *   · it puts no window in front of everything else, so nothing can intercept, obscure or delay a
 *     touch. The waking touch reaches the app it was aimed at, untouched — no tap-twice.
 *
 * TOUCH DETECTION — a 1x1 px watcher window, never in the touch path.
 * ------------------------------------------------------------------
 * A one-pixel, fully transparent accessibility-overlay window carries FLAG_WATCH_OUTSIDE_TOUCH.
 * The input dispatcher delivers MotionEvent.ACTION_OUTSIDE to such a window on the DOWN of every
 * gesture that lands anywhere else on screen, WITHOUT consuming that gesture — the app below still
 * receives the whole stream normally. That gives "any touch, anywhere, in any app, at DOWN" for
 * the cost of one invisible pixel and zero interference. (It is added before the nav overlays so
 * the shade strip sits above it; a literal touch on pixel 0,0 is handled too, and returns false.)
 * Keys (including the volume knob) come through the service's own onKeyEvent, and coarse
 * accessibility events (click / scroll / app switch) are a third, redundant source.
 *
 * THE LADDER (all timings user-settable, see [HioneIdleDimSettings]):
 *   ACTIVE                       → the user's own brightness.
 *   DIM     after activeSec      → 25% of the user's brightness (min 4), ramped down over 800ms.
 *   AMBIENT after +dimSec        → 6% of the user's brightness (min 1), ramped over 800ms.
 *   OFF     after +ambientSec    → left to the PLATFORM: `screen_off_timeout` is set to the total
 *                                  ladder length, so Android's own power manager blanks the panel
 *                                  exactly when the ladder ends. We never hold a wakelock and never
 *                                  push the timeout out indefinitely, so the device can ALWAYS
 *                                  still sleep — this device has a history of idle-drain bugs.
 *
 * COSTS NOTHING WHILE IDLE: there is no polling loop of any kind. Each tier schedules exactly ONE
 * Handler.postDelayed for the next boundary; between boundaries the process is completely silent.
 * The 800ms ramp is ~13 posts and then stops. When the real display goes off (ACTION_SCREEN_OFF)
 * every callback is cancelled, the user's brightness is restored and nothing runs until the screen
 * comes back. Settings are read via ContentObserver, never re-polled.
 */
object HioneIdleDimSettings {
    /** Master switch. 1 = ladder runs (default). */
    const val KEY_ENABLED = "hione_idle_dim_enabled"
    /** Seconds of no interaction before the first dim step. */
    const val KEY_ACTIVE_SEC = "hione_idle_dim_active_sec"
    /** Seconds spent DIMMED before dropping to AMBIENT. */
    const val KEY_DIM_SEC = "hione_idle_dim_dim_sec"
    /** Seconds spent AMBIENT before the platform blanks the panel. */
    const val KEY_AMBIENT_SEC = "hione_idle_dim_ambient_sec"
    /** Published tier (0 ACTIVE / 1 DIM / 2 AMBIENT) so other HioneOS apps can stop animating. */
    const val KEY_TIER = "hione_idle_tier"

    /** The user's real brightness, stashed while we hold the panel dim. -1 = nothing stashed. */
    const val KEY_BASELINE = "hione_idle_dim_baseline"
    /** The user's real `screen_brightness_mode`, stashed while we force MANUAL. -1 = nothing. */
    const val KEY_BASELINE_MODE = "hione_idle_dim_baseline_mode"
    /** The user's real `screen_off_timeout` from before we extended it. -1 = nothing stashed. */
    const val KEY_STASHED_TIMEOUT = "hione_idle_dim_stashed_timeout"

    const val DEFAULT_ACTIVE_SEC = 30
    const val DEFAULT_DIM_SEC = 60
    const val DEFAULT_AMBIENT_SEC = 30

    const val TIER_ACTIVE = 0
    const val TIER_DIM = 1
    const val TIER_AMBIENT = 2

    /** Fraction of the user's own brightness used by each tier — relative, so a user who runs the
     *  panel at 20 doesn't get a "dim" step that is brighter than their normal level. */
    const val DIM_FRACTION = 0.25f
    const val AMBIENT_FRACTION = 0.06f
    const val DIM_FLOOR = 4
    const val AMBIENT_FLOOR = 1

    /** The three shipped timing presets the Quick Settings tile long-press cycles through. */
    val PRESETS: List<Triple<Int, Int, Int>> = listOf(
        Triple(15, 30, 15),                                              // Quick   — 60s to sleep
        Triple(DEFAULT_ACTIVE_SEC, DEFAULT_DIM_SEC, DEFAULT_AMBIENT_SEC), // Normal — 120s to sleep
        Triple(60, 120, 60)                                              // Relaxed — 240s to sleep
    )

    fun presetName(activeSec: Int, dimSec: Int, ambientSec: Int): String = when (Triple(activeSec, dimSec, ambientSec)) {
        PRESETS[0] -> "Quick"
        PRESETS[1] -> "Normal"
        PRESETS[2] -> "Relaxed"
        else -> "${activeSec}s/${dimSec}s/${ambientSec}s"
    }

    fun isEnabled(ctx: Context): Boolean =
        runCatching { Settings.Global.getInt(ctx.contentResolver, KEY_ENABLED, 1) == 1 }.getOrDefault(true)

    fun activeSec(ctx: Context): Int = readSec(ctx, KEY_ACTIVE_SEC, DEFAULT_ACTIVE_SEC)
    fun dimSec(ctx: Context): Int = readSec(ctx, KEY_DIM_SEC, DEFAULT_DIM_SEC)
    fun ambientSec(ctx: Context): Int = readSec(ctx, KEY_AMBIENT_SEC, DEFAULT_AMBIENT_SEC)

    private fun readSec(ctx: Context, key: String, def: Int): Int =
        runCatching { Settings.Global.getInt(ctx.contentResolver, key, def) }.getOrDefault(def).coerceIn(5, 1800)

    /** Write a whole preset at once (QS tile long-press). */
    fun applyPreset(ctx: Context, p: Triple<Int, Int, Int>) {
        runCatching {
            val cr = ctx.contentResolver
            Settings.Global.putInt(cr, KEY_ACTIVE_SEC, p.first)
            Settings.Global.putInt(cr, KEY_DIM_SEC, p.second)
            Settings.Global.putInt(cr, KEY_AMBIENT_SEC, p.third)
        }
    }
}

class HioneIdleDimController(
    private val ctx: Context,
    private val windowManager: WindowManager
) {
    companion object {
        private const val TAG = "HioneIdleDim"
        /** One brightness write every 60ms while ramping — ~13 writes for the whole 800ms fade.
         *  Deliberately NOT per-frame: every write is a ContentProvider transaction. */
        private const val RAMP_STEP_MS = 60L
        private const val RAMP_MS = 800L
        /** Never stash a screen_off_timeout below this as "the user's value" — Hione Music's own
         *  ScreenOffHelper temporarily shrinks it to 1000ms to force a sleep, and adopting that as
         *  the user's real timeout would permanently break the device's screen timeout. */
        private const val MIN_CREDIBLE_TIMEOUT_MS = 10_000
        private const val MAX_BRIGHTNESS = 255
    }

    private val cr get() = ctx.contentResolver
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var started = false
    private var tier = HioneIdleDimSettings.TIER_ACTIVE
    private var lastInteraction = SystemClock.elapsedRealtime()
    private var screenOn = true
    /** True while a foreground app owns its own idle ladder (see [setForeground]). */
    private var suppressed = false

    private var enabled = true
    private var activeSec = HioneIdleDimSettings.DEFAULT_ACTIVE_SEC
    private var dimSec = HioneIdleDimSettings.DEFAULT_DIM_SEC
    private var ambientSec = HioneIdleDimSettings.DEFAULT_AMBIENT_SEC

    /** The last brightness value WE wrote — used to tell our own writes apart from the user's. */
    private var lastWritten = -1

    private var sentinel: View? = null

    // ---------------------------------------------------------------- lifecycle

    fun start() {
        if (started) return
        started = true
        readSettings()
        // Crash recovery: if the process died while the panel was held dim, the stashed baseline is
        // still there and the backlight is still down. Put it back BEFORE anything else — a device
        // that boots to a 5/255 backlight looks bricked to the user.
        recoverStashedBrightness("start")
        applyScreenTimeout()
        addSentinel()
        registerReceivers()
        lastInteraction = SystemClock.elapsedRealtime()
        screenOn = runCatching {
            (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        }.getOrDefault(true)
        publishTier(HioneIdleDimSettings.TIER_ACTIVE)
        schedule()
        Log.i(TAG, "idle dim started enabled=$enabled ladder=${activeSec}/${dimSec}/${ambientSec}s screenOn=$screenOn")
    }

    fun destroy() {
        if (!started) return
        started = false
        main.removeCallbacks(stepRunnable)
        main.removeCallbacks(rampRunnable)
        restoreBaseline()
        runCatching { ctx.unregisterReceiver(screenReceiver) }
        runCatching { cr.unregisterContentObserver(settingsObserver) }
        runCatching { cr.unregisterContentObserver(brightnessObserver) }
        sentinel?.let { v -> runCatching { windowManager.removeView(v) } }
        sentinel = null
    }

    // ---------------------------------------------------------------- interaction

    /**
     * ANY interaction: touch DOWN, key, knob, click/scroll accessibility event. Restores the user's
     * brightness IMMEDIATELY (one write, no ramp — waking must feel instant, and a ramp up would
     * read as sluggish) and restarts the ladder from zero.
     */
    fun poke() {
        if (!started) return
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { poke() }; return }
        lastInteraction = SystemClock.elapsedRealtime()
        if (tier != HioneIdleDimSettings.TIER_ACTIVE) {
            main.removeCallbacks(rampRunnable)
            restoreBaseline()
            publishTier(HioneIdleDimSettings.TIER_ACTIVE)
        }
        if (screenOn) nudgePlatformUserActivity()
        schedule()
    }

    /**
     * Reset the PLATFORM's own user-activity timer as well, so its `screen_off_timeout` stays in
     * lockstep with our ladder.
     *
     * Needed because some of the input we react to never reaches the power manager: an
     * accessibility InputFilter runs BEFORE interceptKeyBeforeQueueing (where PhoneWindowManager
     * calls userActivity), so every key this service SWALLOWS — most importantly the volume knob,
     * which handleVolumeKnob() consumes — does not count as activity to Android. Without this, a
     * user spinning the knob would have the panel blank on them mid-turn.
     *
     * PowerManager.userActivity() is @hide (not in the public SDK 34 android.jar — verified), so it
     * is reached by reflection, with the Method cached: this runs on every touch. The permission it
     * needs, DEVICE_POWER, is already held (signature-level, granted by platform signing).
     */
    private var powerManager: PowerManager? = null
    private var userActivityMethod: java.lang.reflect.Method? = null
    private var userActivityUnavailable = false
    private fun nudgePlatformUserActivity() {
        if (userActivityUnavailable) return
        val pm = powerManager ?: runCatching {
            ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        }.getOrNull()?.also { powerManager = it } ?: return
        val m = userActivityMethod ?: runCatching {
            PowerManager::class.java.getMethod(
                "userActivity",
                java.lang.Long.TYPE, Integer.TYPE, Integer.TYPE
            )
        }.getOrNull()?.also { userActivityMethod = it }
        if (m == null) { userActivityUnavailable = true; return }
        // event 0 = USER_ACTIVITY_EVENT_OTHER, flags 0 = no "no change light" / "indirect".
        runCatching { m.invoke(pm, SystemClock.uptimeMillis(), 0, 0) }
            .onFailure { userActivityUnavailable = true; Log.w(TAG, "userActivity unavailable: $it") }
    }

    /**
     * Foreground app/class, from the service's TYPE_WINDOW_STATE_CHANGED handler. Two owners run
     * their own proven brightness ladders and must not be fought over:
     *   · com.hione.player — IdleController (ACTIVE→DIMMED→AMBIENT→OFF, holdAwake for tape mode and
     *     the fullscreen visualiser, which are things you SIT AND WATCH without touching).
     *   · the HioneOS lockscreen / AOD — HioneLockscreenPrefs' full→fade→off window lifecycle.
     * While one of those is up, this ladder stands down and hands the user's brightness back.
     */
    fun setForeground(pkg: String?, cls: String?) {
        // Hione Music used to be on this list, which meant the OS ladder stood down for the app the
        // device is actually used in, and the only dimming left was the player's per-window
        // brightness override. Justin asked for the ladder to run on the SYSTEM brightness, so this
        // service owns it everywhere now. The player still says when NOT to dim by publishing
        // Settings.Global hione_idle_hold_awake (tape mode, fullscreen visualiser) - things you sit
        // and watch without touching. The lockscreen/AOD keeps its own window lifecycle.
        val playerHolding = pkg == "com.hione.player" && runCatching {
            Settings.Global.getInt(cr, "hione_idle_hold_awake", 0) == 1
        }.getOrDefault(false)
        val isMediaOrGame = HioneAppClassifier.shouldSuppressIdleDim(ctx, pkg, cls)
        val owns = playerHolding || isMediaOrGame ||
            (cls != null && (cls.contains("Lockscreen", true) || cls.contains("Aod", true)))
        if (owns != suppressed) {
            suppressed = owns
            Log.i(TAG, "foreground owns its own ladder=$owns ($pkg/$cls, mediaOrGame=$isMediaOrGame)")
            if (suppressed) {
                main.removeCallbacks(stepRunnable); main.removeCallbacks(rampRunnable)
                restoreBaseline()
                publishTier(HioneIdleDimSettings.TIER_ACTIVE)
            }
        }
        // An app SWITCH is a user interaction — but only a real switch. TYPE_WINDOW_STATE_CHANGED
        // also fires for pop-overs and re-shows of the window that is already on top (the Now
        // Playing HUD re-appearing on every track change, for one), and treating those as
        // interaction would reset the ladder every few minutes and stop the screen ever dimming
        // while music plays. Comparing against the last window seen makes that impossible.
        val key = "$pkg/$cls"
        if (key != lastWindowKey) {
            lastWindowKey = key
            poke()                    // also re-arms the ladder when we come OUT of suppression
        }
    }

    private var lastWindowKey: String? = null

    // ---------------------------------------------------------------- the ladder

    private val stepRunnable = Runnable { step() }

    /** (Re)arm exactly ONE timer for the next tier boundary. No polling anywhere in this class. */
    private fun schedule() {
        main.removeCallbacks(stepRunnable)
        if (!enabled || !screenOn || suppressed) return
        val idleMs = SystemClock.elapsedRealtime() - lastInteraction
        val dimAt = activeSec * 1000L
        val ambientAt = dimAt + dimSec * 1000L
        val delay = when {
            idleMs < dimAt -> dimAt - idleMs
            idleMs < ambientAt -> ambientAt - idleMs
            else -> -1L        // AMBIENT is the last tier we drive; the platform owns screen-off.
        }
        if (delay > 0) main.postDelayed(stepRunnable, delay)
    }

    private fun step() {
        if (!enabled || !screenOn || suppressed) return
        val idleMs = SystemClock.elapsedRealtime() - lastInteraction
        val dimAt = activeSec * 1000L
        val ambientAt = dimAt + dimSec * 1000L
        val want = when {
            idleMs >= ambientAt -> HioneIdleDimSettings.TIER_AMBIENT
            idleMs >= dimAt -> HioneIdleDimSettings.TIER_DIM
            else -> HioneIdleDimSettings.TIER_ACTIVE
        }
        if (want != tier) {
            publishTier(want)
            when (want) {
                HioneIdleDimSettings.TIER_ACTIVE -> restoreBaseline()
                else -> rampBrightnessTo(targetBrightnessFor(want))
            }
        }
        schedule()
    }

    private fun publishTier(t: Int) {
        tier = t
        // Cross-process signal: the launcher's ambient gate freezes its animations on tier > 0.
        runCatching { Settings.Global.putInt(cr, HioneIdleDimSettings.KEY_TIER, t) }
    }

    // ---------------------------------------------------------------- brightness

    /** The user's real brightness: the stash if we already took one, else whatever is set now. */
    private fun baselineBrightness(): Int {
        val stashed = runCatching { Settings.Global.getInt(cr, HioneIdleDimSettings.KEY_BASELINE, -1) }.getOrDefault(-1)
        if (stashed in 1..MAX_BRIGHTNESS) return stashed
        return currentBrightness()
    }

    private fun currentBrightness(): Int = runCatching {
        Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
    }.getOrDefault(128).coerceIn(1, MAX_BRIGHTNESS)

    private fun targetBrightnessFor(t: Int): Int {
        val base = baselineBrightness()
        return when (t) {
            HioneIdleDimSettings.TIER_DIM ->
                (base * HioneIdleDimSettings.DIM_FRACTION).toInt().coerceAtLeast(HioneIdleDimSettings.DIM_FLOOR)
            else ->
                (base * HioneIdleDimSettings.AMBIENT_FRACTION).toInt().coerceAtLeast(HioneIdleDimSettings.AMBIENT_FLOOR)
        }.coerceIn(1, base.coerceAtLeast(1))
    }

    /**
     * Stash the user's brightness (and, if auto-brightness is on, their brightness MODE) before the
     * first dim step. Persisted in Settings.Global rather than a field so a process death mid-dim
     * is recoverable — see [recoverStashedBrightness].
     *
     * Auto-brightness: writing SCREEN_BRIGHTNESS while `screen_brightness_mode` = 1 fights the
     * auto-brightness service, which would simply write its own value back over ours. So we stash
     * the mode, force MANUAL for the duration of the dim, and restore the mode on wake. (Measured
     * on this device the mode is already 0/MANUAL, so this path normally does nothing.)
     */
    private fun stashBaselineIfNeeded() {
        val already = runCatching { Settings.Global.getInt(cr, HioneIdleDimSettings.KEY_BASELINE, -1) }.getOrDefault(-1)
        if (already in 1..MAX_BRIGHTNESS) return
        val cur = currentBrightness()
        runCatching { Settings.Global.putInt(cr, HioneIdleDimSettings.KEY_BASELINE, cur) }
        val mode = runCatching {
            Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        }.getOrDefault(Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        if (mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) {
            runCatching { Settings.Global.putInt(cr, HioneIdleDimSettings.KEY_BASELINE_MODE, mode) }
            runCatching {
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            }
        }
    }

    /** Put the user's brightness (and mode) back, instantly, and clear the stash. */
    private fun restoreBaseline() {
        val stashed = runCatching { Settings.Global.getInt(cr, HioneIdleDimSettings.KEY_BASELINE, -1) }.getOrDefault(-1)
        val mode = runCatching { Settings.Global.getInt(cr, HioneIdleDimSettings.KEY_BASELINE_MODE, -1) }.getOrDefault(-1)
        if (stashed in 1..MAX_BRIGHTNESS) {
            writeBrightness(stashed)
            runCatching { Settings.Global.putInt(cr, HioneIdleDimSettings.KEY_BASELINE, -1) }
        }
        if (mode >= 0) {
            runCatching { Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, mode) }
            runCatching { Settings.Global.putInt(cr, HioneIdleDimSettings.KEY_BASELINE_MODE, -1) }
        }
    }

    /** Same as [restoreBaseline] but only for the "we died while dimmed" case, with a log. */
    private fun recoverStashedBrightness(why: String) {
        val stashed = runCatching { Settings.Global.getInt(cr, HioneIdleDimSettings.KEY_BASELINE, -1) }.getOrDefault(-1)
        if (stashed in 1..MAX_BRIGHTNESS) {
            Log.w(TAG, "$why: found a stale brightness stash ($stashed) — the panel was left dim, restoring")
        }
        restoreBaseline()
    }

    private fun writeBrightness(value: Int) {
        val v = value.coerceIn(1, MAX_BRIGHTNESS)
        lastWritten = v
        runCatching { Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, v) }
            .onFailure { Log.w(TAG, "brightness write $v failed: $it") }
    }

    // --- ramp: an abrupt backlight jump reads as a glitch, so dimming eases down over RAMP_MS.
    private var rampFrom = 0
    private var rampTo = 0
    private var rampStart = 0L
    private val rampRunnable = object : Runnable {
        override fun run() {
            val t = ((SystemClock.elapsedRealtime() - rampStart).toFloat() / RAMP_MS).coerceIn(0f, 1f)
            val eased = t * t * (3f - 2f * t)                     // smoothstep
            writeBrightness((rampFrom + (rampTo - rampFrom) * eased).toInt())
            if (t < 1f) main.postDelayed(this, RAMP_STEP_MS)
        }
    }

    /** Ease the backlight from wherever it is to [target] over RAMP_MS. */
    private fun rampBrightnessTo(target: Int) {
        stashBaselineIfNeeded()
        main.removeCallbacks(rampRunnable)
        rampFrom = currentBrightness()
        rampTo = target
        if (rampFrom == rampTo) return
        rampStart = SystemClock.elapsedRealtime()
        main.post(rampRunnable)
    }

    // ---------------------------------------------------------------- settings / display state

    private fun readSettings() {
        enabled = HioneIdleDimSettings.isEnabled(ctx)
        activeSec = HioneIdleDimSettings.activeSec(ctx)
        dimSec = HioneIdleDimSettings.dimSec(ctx)
        ambientSec = HioneIdleDimSettings.ambientSec(ctx)
    }

    /**
     * The platform's own `screen_off_timeout` is set to the FULL ladder length, so Android blanks
     * the panel exactly when the ladder ends and the OFF stage costs us no code, no wakelock and no
     * goToSleep hack. Measured before this change: 15000ms — far shorter than any ladder, so the
     * screen cut to black before the first dim step could ever run.
     *
     * Default ladder 30 + 60 + 30 = 120s, so the timeout becomes 120000ms (2 minutes). Clamped to
     * 30s..10min: the device must ALWAYS still be able to sleep.
     * The user's original value is stashed once so disabling the feature puts it back.
     */
    private fun applyScreenTimeout() {
        val total = ((activeSec + dimSec + ambientSec) * 1000).coerceIn(30_000, 600_000)
        val cur = runCatching {
            Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, 15_000)
        }.getOrDefault(15_000)
        if (!enabled) { restoreScreenTimeout(); return }
        if (cur == total) return
        val stashed = runCatching {
            Settings.Global.getInt(cr, HioneIdleDimSettings.KEY_STASHED_TIMEOUT, -1)
        }.getOrDefault(-1)
        if (stashed == -1 && cur >= MIN_CREDIBLE_TIMEOUT_MS) {
            runCatching { Settings.Global.putInt(cr, HioneIdleDimSettings.KEY_STASHED_TIMEOUT, cur) }
        }
        runCatching { Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, total) }
            .onFailure { Log.w(TAG, "screen_off_timeout -> $total failed: $it") }
            .onSuccess { Log.i(TAG, "screen_off_timeout $cur -> $total (ladder ${activeSec}/${dimSec}/${ambientSec}s)") }
    }

    private fun restoreScreenTimeout() {
        val stashed = runCatching {
            Settings.Global.getInt(cr, HioneIdleDimSettings.KEY_STASHED_TIMEOUT, -1)
        }.getOrDefault(-1)
        if (stashed < MIN_CREDIBLE_TIMEOUT_MS) return
        runCatching { Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, stashed) }
        runCatching { Settings.Global.putInt(cr, HioneIdleDimSettings.KEY_STASHED_TIMEOUT, -1) }
    }

    private val settingsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            val wasEnabled = enabled
            readSettings()
            if (wasEnabled && !enabled) {
                main.removeCallbacks(stepRunnable); main.removeCallbacks(rampRunnable)
                restoreBaseline()
                publishTier(HioneIdleDimSettings.TIER_ACTIVE)
            }
            applyScreenTimeout()
            poke()
        }
    }

    /**
     * The user changing brightness themselves (shade slider, hardware settings) IS an interaction:
     * we adopt their new value as the baseline instead of stomping it on wake, and we wake. Our own
     * writes are filtered out by comparing against [lastWritten].
     */
    private val brightnessObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            val now = currentBrightness()
            if (now == lastWritten) return                       // our own ramp/restore write
            if (tier != HioneIdleDimSettings.TIER_ACTIVE) {
                runCatching { Settings.Global.putInt(cr, HioneIdleDimSettings.KEY_BASELINE, now) }
                lastWritten = now
                Log.i(TAG, "user changed brightness to $now while dimmed — adopted as the new baseline")
            }
            poke()
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    // The panel is physically dark: nothing to dim, nothing to watch. Cancel every
                    // callback and hand the user's brightness back, so the NEXT wake (power button,
                    // alarm, charger) shows their real level rather than our 5/255 ambient value.
                    screenOn = false
                    main.removeCallbacks(stepRunnable); main.removeCallbacks(rampRunnable)
                    restoreBaseline()
                    publishTier(HioneIdleDimSettings.TIER_ACTIVE)
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    screenOn = true
                    poke()
                }
            }
        }
    }

    private fun registerReceivers() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(screenReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                ctx.registerReceiver(screenReceiver, filter)
            }
        }.onFailure { Log.w(TAG, "screen receiver: $it") }
        runCatching {
            listOf(
                HioneIdleDimSettings.KEY_ENABLED, HioneIdleDimSettings.KEY_ACTIVE_SEC,
                HioneIdleDimSettings.KEY_DIM_SEC, HioneIdleDimSettings.KEY_AMBIENT_SEC
            ).forEach { k -> cr.registerContentObserver(Settings.Global.getUriFor(k), false, settingsObserver) }
            cr.registerContentObserver(
                Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), false, brightnessObserver
            )
        }.onFailure { Log.w(TAG, "settings observers: $it") }
    }

    // ---------------------------------------------------------------- touch sentinel

    /**
     * 1x1 transparent window with FLAG_WATCH_OUTSIDE_TOUCH at the very top-left pixel. It receives
     * ACTION_OUTSIDE on the DOWN of any gesture anywhere else on screen and consumes nothing, so
     * the waking touch still lands in whatever app the user aimed at — you never tap twice.
     * Added BEFORE the nav overlays so the shade strip is layered above this pixel; the direct-hit
     * case is still handled (poke, return false).
     */
    private fun addSentinel() {
        if (sentinel != null) return
        val v = object : View(ctx) {
            override fun onTouchEvent(event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_OUTSIDE, MotionEvent.ACTION_DOWN -> poke()
                }
                return false
            }
        }
        val params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSPARENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 0 }
        runCatching { windowManager.addView(v, params); sentinel = v }
            .onFailure { Log.w(TAG, "touch sentinel add failed: $it") }
    }
}
