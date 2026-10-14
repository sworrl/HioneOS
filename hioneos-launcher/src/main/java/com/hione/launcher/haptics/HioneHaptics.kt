package com.hione.launcher.haptics

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings

/**
 * The ONE launcher haptics vocabulary:
 *  - [tick]    light detent — press, hover, list step. Rate limited.
 *  - [confirm] click — an action committed (drop, toggle on, unlock, dock tap)
 *  - [pop]     heavy — long-press pick-up / menu appear
 *  - [reject]  double buzz — a gesture that was refused
 *  - [like], [unlock], [beat] — the few signature patterns
 *
 * The M500 motor is a plain on/off GPIO motor (timed_gpio): no amplitude control, no predefined
 * effects, no primitives, so amplitude waveforms and createPredefined do nothing useful on it.
 * Every pulse is a timed one-shot or an on/off pattern. Durations and the tick gap come from
 * m500/hioneos/docs/m500-haptics.md.
 *
 * Every call honours the system "Touch vibration" toggle (Settings.System.HAPTIC_FEEDBACK_ENABLED),
 * the HioneOS toggle (Settings.Global "hione_haptics", default on) and the launcher's own
 * `haptics_enabled` pref.
 */
object HioneHaptics {
    private const val PREFS = "hione_launcher_prefs"
    private const val KEY = "haptics_enabled"
    const val GLOBAL_KEY = "hione_haptics"

    const val TICK_MS = 22L   // 10 ms reaches the HAL but cannot be felt
    const val CLICK_MS = 32L
    const val HEAVY_MS = 45L
    /** A tick is dropped while the motor is still busy, and never fires closer than this. */
    const val TICK_GAP_MS = 45L
    /** Two 35 ms pulses 80 ms apart: the gap has to outlast the motor's coast-down to read as two. */
    private val ERROR_PATTERN = longArrayOf(0, 35, 80, 35)
    /** Light then firm: "added". */
    private val LIKE_PATTERN = longArrayOf(0, 22, 60, 40)
    /** Three rising pulses for a secret or reward unlock. */
    private val UNLOCK_PATTERN = longArrayOf(0, 22, 70, 22, 70, 45)

    @Volatile private var busyUntil = 0L

    fun enabled(ctx: Context): Boolean = try {
        val cr = ctx.contentResolver
        Settings.System.getInt(cr, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0 &&
            Settings.Global.getInt(cr, GLOBAL_KEY, 1) != 0 &&
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)
    } catch (_: Throwable) { true }

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    }

    fun tick(ctx: Context) {
        if (SystemClock.uptimeMillis() < busyUntil) return
        oneShot(ctx, TICK_MS)
    }

    fun confirm(ctx: Context) = oneShot(ctx, CLICK_MS)

    fun pop(ctx: Context) = oneShot(ctx, HEAVY_MS)

    fun reject(ctx: Context) = pattern(ctx, ERROR_PATTERN)

    /** Like-heart. Was a seven-ridge amplitude texture, which this motor cannot play. */
    fun like(ctx: Context) = pattern(ctx, LIKE_PATTERN)

    /** A BPM-game secret or reward was just earned. */
    fun unlock(ctx: Context) = pattern(ctx, UNLOCK_PATTERN)

    /**
     * Rhythm-game beat hit: one pulse that lands on the tap and is over well before the next beat.
     *  - [strength] 2 = PERFECT: heavy
     *  - [strength] 1 = GOOD:    tick
     *  - [strength] 0 = MISS:    nothing (no amplitude control, so there is no faint version)
     * Not rate limited: beats are always further apart than the tick gap.
     */
    fun beat(ctx: Context, strength: Int) {
        when {
            strength >= 2 -> oneShot(ctx, HEAVY_MS)
            strength == 1 -> oneShot(ctx, TICK_MS)
        }
    }

    private fun oneShot(ctx: Context, ms: Long) =
        play(ctx, VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE), ms)

    private fun pattern(ctx: Context, timings: LongArray) =
        play(ctx, VibrationEffect.createWaveform(timings, -1), timings.sum())

    private fun play(ctx: Context, effect: VibrationEffect, lengthMs: Long) {
        if (!enabled(ctx)) return
        try {
            val v = (ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.takeIf { it.hasVibrator() } ?: return
            busyUntil = SystemClock.uptimeMillis() + maxOf(lengthMs, TICK_GAP_MS)
            if (Build.VERSION.SDK_INT >= 33) v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
            else v.vibrate(effect)
        } catch (_: Throwable) {}
    }
}
