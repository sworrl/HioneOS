package com.hione.systemui

import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.view.animation.OvershootInterpolator
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer

/**
 * THE motion spec for every HioneOS SystemUI surface (shade, recents, pill/back, HUD, power
 * menu). Pixel-11 timing with Hione springs; every value honours Settings.Global
 * `hione_power_profile` — in audio_only / idle everything collapses to 120ms tweens with no
 * overshoot and no glow animation (see [HionePowerProfile]).
 *
 *   press      0.95 scale in 80ms, spring back (bouncy)
 *   settle     spring stiffness 400 / damping 0.85  (shade rest, panel release, list gap close)
 *   bouncy     spring 380 / 0.62                   (pop-backs, card return)
 *   snappy     spring 900 / 0.90                   (quick corrections)
 *   ease       FastOutSlowIn tween, 200ms default  (fades, colour, height)
 *   stagger    40ms per item
 */
object HioneMotion {
    const val QUICK = 120
    const val FAST = 150
    const val NORMAL = 200
    const val SETTLE = 250
    const val SLOW = 320
    const val PRESS_SCALE = 0.95f
    const val STAGGER_MS = 40L
    const val SETTLE_STIFFNESS = 400f
    const val SETTLE_DAMPING = 0.85f

    val quiet: Boolean get() = HionePowerProfile.lowPower
    fun ms(normal: Int): Int = HionePowerProfile.ms(normal)

    fun <T> settle(): FiniteAnimationSpec<T> =
        if (quiet) tween(QUICK) else spring(dampingRatio = SETTLE_DAMPING, stiffness = SETTLE_STIFFNESS)
    fun <T> bouncy(): FiniteAnimationSpec<T> =
        if (quiet) tween(QUICK) else spring(dampingRatio = 0.62f, stiffness = 380f)
    fun <T> snappy(): FiniteAnimationSpec<T> =
        if (quiet) tween(QUICK) else spring(dampingRatio = 0.9f, stiffness = 900f)
    fun <T> ease(duration: Int = NORMAL): TweenSpec<T> = tween(ms(duration), easing = FastOutSlowInEasing)

    /** View-side: overshoot unless quiet. tension 1.1 ≈ 8% overshoot, 0.8 ≈ 5%. */
    fun overshoot(tension: Float = 1.1f): Interpolator = if (quiet) DecelerateInterpolator() else OvershootInterpolator(tension)
    fun decel(): Interpolator = DecelerateInterpolator()
}

/** Pixel press feedback: 0.95 scale while pressed, springs back on release. */
fun Modifier.pressScale(interactionSource: InteractionSource, pressed: Float = HioneMotion.PRESS_SCALE): Modifier = composed {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (isPressed) pressed else 1f,
        if (isPressed) tween(80) else HioneMotion.bouncy(),
        label = "pressScale"
    )
    graphicsLayer { scaleX = scale; scaleY = scale }
}
