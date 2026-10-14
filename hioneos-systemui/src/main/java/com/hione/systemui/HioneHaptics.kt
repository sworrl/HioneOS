package com.hione.systemui

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.View

/**
 * One haptic vocabulary for every SystemUI surface:
 *   tick     light  — gesture claimed / step / volume knob detent / hold-ring quarter
 *   confirm  click  — commit (back, home, tile tap, quick switch, shade settles open)
 *   pop      heavy  — hold-to-recents, long press, destructive arm, clear-all
 *   reject   double — a gesture that could not be honoured
 *
 * The M500 motor is a plain on/off GPIO motor (timed_gpio): no amplitude control, no predefined
 * effects, no primitives. View.performHapticFeedback falls back to HiBy's flat 50 ms buzz for
 * every constant and CLOCK_TICK plays nothing, so every pulse here is a timed one-shot. Durations
 * and the tick gap come from m500/hioneos/docs/m500-haptics.md.
 *
 * Every pulse honours Settings.System.HAPTIC_FEEDBACK_ENABLED and Settings.Global "hione_haptics"
 * (default on). The View overloads use the view's context.
 */
object HioneHaptics {
    const val TICK_MS = 22L   // 10 ms reaches the HAL but cannot be felt
    const val CLICK_MS = 32L
    const val HEAVY_MS = 45L
    /** Two 35 ms pulses 80 ms apart: the gap has to outlast the motor's coast-down to read as two. */
    private val ERROR_PATTERN = longArrayOf(0, 35, 80, 35)
    /** A tick is dropped while the motor is still busy, and never fires closer than this. */
    const val TICK_GAP_MS = 45L
    const val GLOBAL_KEY = "hione_haptics"

    @Volatile private var busyUntil = 0L

    fun tick(v: View?) { v?.let { tick(it.context) } }
    fun confirm(v: View?) { v?.let { confirm(it.context) } }
    fun pop(v: View?) { v?.let { pop(it.context) } }
    fun reject(v: View?) { v?.let { reject(it.context) } }

    fun tick(ctx: Context) {
        if (SystemClock.uptimeMillis() < busyUntil) return
        play(ctx, VibrationEffect.createOneShot(TICK_MS, VibrationEffect.DEFAULT_AMPLITUDE), TICK_MS)
    }
    fun confirm(ctx: Context) = play(ctx, VibrationEffect.createOneShot(CLICK_MS, VibrationEffect.DEFAULT_AMPLITUDE), CLICK_MS)
    fun pop(ctx: Context) = play(ctx, VibrationEffect.createOneShot(HEAVY_MS, VibrationEffect.DEFAULT_AMPLITUDE), HEAVY_MS)
    fun reject(ctx: Context) = play(ctx, VibrationEffect.createWaveform(ERROR_PATTERN, -1), ERROR_PATTERN.sum())

    /** Direct pulse of [ms] for callers that want their own length. [amplitude] is ignored by the M500 motor. */
    fun buzz(ctx: Context, ms: Long = CLICK_MS, @Suppress("UNUSED_PARAMETER") amplitude: Int = VibrationEffect.DEFAULT_AMPLITUDE) =
        play(ctx, VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE), ms)

    fun enabled(ctx: Context): Boolean = try {
        val cr = ctx.contentResolver
        Settings.System.getInt(cr, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0 &&
            Settings.Global.getInt(cr, GLOBAL_KEY, 1) != 0
    } catch (_: Throwable) { true }

    /**
     * First run only: HiBy ships Touch vibration off (SettingsProvider overlay def_haptic_feedback
     * = false), which would mute every HioneOS haptic. The first time SystemUI starts with
     * "hione_haptics" unset, write it on and turn Touch vibration on. After that the user's
     * choices stand.
     */
    fun ensureDefaults(ctx: Context) {
        runCatching {
            val cr = ctx.contentResolver
            if (Settings.Global.getString(cr, GLOBAL_KEY) == null) {
                Settings.Global.putInt(cr, GLOBAL_KEY, 1)
                Settings.System.putInt(cr, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1)
            }
        }
    }

    private fun play(ctx: Context, effect: VibrationEffect, lengthMs: Long) {
        if (!enabled(ctx)) return
        runCatching {
            val vib = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (!vib.hasVibrator()) return
            busyUntil = SystemClock.uptimeMillis() + maxOf(lengthMs, TICK_GAP_MS)
            if (Build.VERSION.SDK_INT >= 33) vib.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
            else vib.vibrate(effect)
        }
    }
}
