package com.hione.launcher.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * Tactile Haptic Texture Engine for HioneOS.
 *
 * Delivers multi-stage textured physical sensations:
 * - "Bumpy But Good" Like Heart Waveform (ratcheted gear ridges + smooth tactile decay)
 * - Crisp Micro-Tick (ratchet clicks for buttons / history items)
 * - Dislike / Downward Stepped Texture
 */
object HioneTactileHaptics {

    /**
     * Rich "bumpy but good" tactile texture.
     * Simulates micro-notches with tactile amplitude crests.
     */
    fun playBumpyLikeTexture(context: Context) {
        val vib = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (!vib.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                // Waveform: 7 micro-ridges ramping up then trailing smoothly
                val timings = longArrayOf(0, 12, 14, 16, 12, 22, 14, 28, 14, 20, 12, 14, 10, 8)
                val amplitudes = intArrayOf(0, 95, 0, 155, 0, 215, 0, 255, 0, 185, 0, 125, 0, 65)
                if (vib.hasAmplitudeControl()) {
                    vib.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
                } else {
                    vib.vibrate(VibrationEffect.createWaveform(timings, -1))
                }
            } catch (_: Throwable) {
                vib.vibrate(VibrationEffect.createOneShot(45L, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } else {
            @Suppress("DEPRECATION")
            vib.vibrate(longArrayOf(0, 15, 20, 25, 20, 35), -1)
        }
    }

    /**
     * Crisp mechanical ratchet tick for button presses & list items.
     */
    fun playRatchetTick(context: Context) {
        val vib = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val timings = longArrayOf(0, 8, 10, 10)
                val amplitudes = intArrayOf(0, 180, 0, 240)
                if (vib.hasAmplitudeControl()) {
                    vib.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
                } else {
                    vib.vibrate(VibrationEffect.createOneShot(12L, VibrationEffect.DEFAULT_AMPLITUDE))
                }
            } catch (_: Throwable) {}
        }
    }

    /**
     * Downward rumble texture for unliking / removing.
     */
    fun playBumpyDislikeTexture(context: Context) {
        val vib = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val timings = longArrayOf(0, 24, 16, 18, 14, 12, 10, 8)
                val amplitudes = intArrayOf(0, 240, 0, 170, 0, 100, 0, 40)
                if (vib.hasAmplitudeControl()) {
                    vib.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
                } else {
                    vib.vibrate(VibrationEffect.createWaveform(timings, -1))
                }
            } catch (_: Throwable) {}
        }
    }
}
