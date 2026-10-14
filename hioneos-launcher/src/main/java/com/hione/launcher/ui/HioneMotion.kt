package com.hione.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hione.launcher.haptics.HioneHaptics
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Shared motion vocabulary — Pixel timings, Hione bounce. */
object HioneMotion {
    /** Settles a dragged sheet / card into place (slight overshoot, no wobble). */
    val settle: SpringSpec<Float> = spring(dampingRatio = 0.82f, stiffness = 420f)
    /** Snappy return of a released control (icon scale back, rubber band). */
    val snapBack: SpringSpec<Float> = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
    /** Kawaii pop for long-press pick-ups (bouncier). */
    val pop: SpringSpec<Float> = spring(dampingRatio = 0.55f, stiffness = 900f)
    private const val OPEN_MS_FULL = 220
    private const val CLOSE_MS_FULL = 160
    const val PRESS_MS = 80
    /** ~120 ms in the low-power profiles (audio_only / idle), Pixel timings otherwise. */
    val OPEN_MS: Int get() = if (HionePowerProfile.isLowPower) 120 else OPEN_MS_FULL
    val CLOSE_MS: Int get() = if (HionePowerProfile.isLowPower) 110 else CLOSE_MS_FULL
    /** Stiffer springs settle in ~120 ms when low-power. */
    val settleNow: SpringSpec<Float> get() = if (HionePowerProfile.isLowPower) spring(dampingRatio = 1f, stiffness = 1400f) else settle
    val popNow: SpringSpec<Float> get() = if (HionePowerProfile.isLowPower) spring(dampingRatio = 1f, stiffness = 1400f) else pop
    /** Fling velocity (px/s) that commits a dismiss regardless of distance. */
    const val FLING_PX_S = 1200f
    const val SCRIM_ALPHA = 0.6f
}

/**
 * Uniform host for the launcher's full-screen modals: 220 ms scale 0.96→1 + fade on open,
 * 160 ms out, a consistent 0.6 scrim, and drag-to-dismiss that FOLLOWS the finger
 * (offset + fade) from the bottom band — release past 30 % of the height or a fling closes,
 * otherwise it springs back. The modal composable itself is untouched.
 */
@Composable
fun HioneModalHost(
    visible: Boolean,
    onDismiss: () -> Unit,
    dragToDismiss: Boolean = true,
    scrim: Boolean = true,
    content: @Composable () -> Unit
) {
    // A visible modal covers home: ambient home animators freeze (HioneAmbient) while it's up.
    androidx.compose.runtime.DisposableEffect(visible) {
        if (visible) HioneAmbient.pushCovered()
        onDispose { if (visible) HioneAmbient.popCovered() }
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(HioneMotion.OPEN_MS)) +
            scaleIn(initialScale = 0.96f, animationSpec = tween(HioneMotion.OPEN_MS, easing = FastOutSlowInEasing)),
        exit = fadeOut(tween(HioneMotion.CLOSE_MS)) +
            scaleOut(targetScale = 0.96f, animationSpec = tween(HioneMotion.CLOSE_MS, easing = FastOutSlowInEasing))
    ) {
        Box(Modifier.fillMaxSize()) {
            if (scrim) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = HioneMotion.SCRIM_ALPHA)))
            Box(
                Modifier
                    .fillMaxSize()
                    .then(if (dragToDismiss) Modifier.dragToDismissFollow(onDismiss = onDismiss) else Modifier)
            ) { content() }
        }
    }
}

/**
 * Finger-following dismiss for a modal placed on its ROOT container. A drag that starts in the
 * bottom 18 % of the composable and moves clearly UPWARD translates the content with the finger
 * (with fade); on release: fling (> [HioneMotion.FLING_PX_S]) or > 30 % of the height → animate
 * off-screen and call [onDismiss]; otherwise spring back. Runs in the Main pass after touch slop,
 * so inner scrollables/draggables that claim the gesture keep winning, and taps fall through.
 */
fun Modifier.dragToDismissFollow(
    enabled: Boolean = true,
    startBandFraction: Float = 0.18f,
    onDismiss: () -> Unit
): Modifier = composed {
    if (!enabled) return@composed this
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    var dismissing by remember { mutableFloatStateOf(0f) }
    val latestDismiss = rememberUpdatedState(onDismiss)
    this
        .graphicsLayer {
            translationY = offset.value
            val h = size.height.coerceAtLeast(1f)
            alpha = (1f - (abs(offset.value) / h) * 0.9f).coerceIn(0.05f, 1f)
        }
        .pointerInput(enabled) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                if (down.position.y < size.height * (1f - startBandFraction)) return@awaitEachGesture
                var total = 0f
                val slop = awaitVerticalTouchSlopOrCancellation(down.id) { change, over ->
                    if (over < 0f) { change.consume(); total = over }
                } ?: return@awaitEachGesture
                val tracker = VelocityTracker()
                tracker.addPosition(slop.uptimeMillis, slop.position)
                scope.launch { offset.snapTo(total) }
                verticalDrag(slop.id) { change ->
                    total += change.positionChange().y
                    change.consume()
                    tracker.addPosition(change.uptimeMillis, change.position)
                    val clamped = total.coerceAtMost(0f)
                    scope.launch { offset.snapTo(clamped) }
                }
                val vy = tracker.calculateVelocity().y
                val h = size.height.toFloat()
                val commit = vy < -HioneMotion.FLING_PX_S || -offset.value > h * 0.30f
                scope.launch {
                    if (commit) {
                        dismissing = 1f
                        HioneHaptics.confirm(ctx)
                        offset.animateTo(-h * 1.05f, tween(HioneMotion.CLOSE_MS, easing = FastOutSlowInEasing))
                        latestDismiss.value()
                        offset.snapTo(0f)
                        dismissing = 0f
                    } else {
                        if (abs(offset.value) > 8f) HioneHaptics.tick(ctx)
                        offset.animateTo(0f, HioneMotion.settleNow)
                    }
                }
            }
        }
}

/** Rubber-band resistance past a limit: travel beyond [limit] is scaled by [resistance]. */
fun rubberBand(value: Float, limit: Float, resistance: Float = 0.35f): Float =
    if (value > limit) value else limit + (value - limit) * resistance

/** One-shot scale pop (1 → [peak] → 1) driven from an event; returns the current scale. */
@Composable
fun rememberPopScale(trigger: Any?, peak: Float = 1.06f): Float {
    val scale = remember { Animatable(1f) }
    LaunchedEffect(trigger) {
        if (trigger == null) return@LaunchedEffect
        scale.snapTo(peak)
        scale.animateTo(1f, HioneMotion.popNow)
    }
    return scale.value
}
