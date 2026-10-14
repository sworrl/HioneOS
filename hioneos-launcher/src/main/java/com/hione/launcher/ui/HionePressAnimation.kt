package com.hione.launcher.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hione.launcher.HioneCyan
import com.hione.launcher.haptics.HioneHaptics
import kotlinx.coroutines.launch

/**
 * Pixel press physics with a Hione glow:
 *  - touch down: scale → [pressedScale] (0.92) in 80 ms ease-out
 *  - release:    spring back (medium bouncy) — the "settle"
 *  - a neon aura fades in while held; one ratchet tick on press
 */
fun Modifier.hionePressScale(
    pressedScale: Float = 0.92f,
    glowColor: Color = HioneCyan,
    hapticFeedback: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    onClick: (() -> Unit)? = null
): Modifier = composed {
    val ctx = LocalContext.current
    val actualSource = interactionSource ?: remember { MutableInteractionSource() }
    val isPressed by actualSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressedScale else 1f,
        animationSpec = if (isPressed) tween(HioneMotion.PRESS_MS, easing = FastOutSlowInEasing) else HioneMotion.snapBack,
        label = "hionePressScaleAnim"
    )

    val glowAlpha by animateFloatAsState(
        targetValue = if (isPressed) 0.85f else 0f,
        animationSpec = tween(durationMillis = if (isPressed) HioneMotion.PRESS_MS else 240),
        label = "hionePressGlowAnim"
    )

    LaunchedEffect(isPressed) {
        if (isPressed && hapticFeedback) HioneHaptics.tick(ctx)
    }

    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .drawWithContent {
            drawContent()
            if (glowAlpha > 0.01f) {
                drawRoundRect(
                    color = glowColor.copy(alpha = glowAlpha * 0.45f),
                    size = size,
                    cornerRadius = CornerRadius(16.dp.toPx(), 16.dp.toPx()),
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }
}

/**
 * App-icon interaction (Pixel feel):
 *  - press 0.92 in 80 ms, release springs back
 *  - long-press: 1.06 pop + [HioneHaptics.pop], then [onLongClick] (menu scales from the icon)
 *  - remembers the icon's window bounds in [HioneLaunchSource] on click so the app can scale up
 *    OUT of the icon, and plays a small settle bounce when the user returns home to it
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.hioneAppIconClickable(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    glowColor: Color = HioneCyan,
    launchPackage: String? = null
): Modifier = composed {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    var bounds by remember { mutableStateOf<Rect?>(null) }
    val pop = remember { Animatable(1f) }

    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = if (isPressed) tween(HioneMotion.PRESS_MS, easing = FastOutSlowInEasing) else HioneMotion.snapBack,
        label = "hioneAppIconScale"
    )


    // Return-home settle: the icon we launched from bounces once when the launcher resumes.
    val returnTick by HioneLaunchSource.homeReturnTick.collectAsState()
    LaunchedEffect(returnTick) {
        if (returnTick > 0L && launchPackage != null && HioneLaunchSource.lastPackage == launchPackage) {
            pop.snapTo(1.08f)
            pop.animateTo(1f, HioneMotion.snapBack)
        }
    }

    this
        .onGloballyPositioned { c -> bounds = try { c.boundsInWindow() } catch (_: Throwable) { null } }
        .graphicsLayer {
            val s = pressScale * pop.value
            scaleX = s
            scaleY = s
        }
        .combinedClickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = {
                // Haptic on the click itself: a LaunchedEffect on the pressed state misses quick
                // taps that press and release inside one frame.
                HioneHaptics.confirm(ctx)
                HioneLaunchSource.set(bounds, launchPackage)
                onClick()
            },
            onLongClick = onLongClick?.let { cb ->
                {
                    HioneLaunchSource.set(bounds, launchPackage)
                    HioneHaptics.pop(ctx)
                    scope.launch {
                        pop.snapTo(1.06f)
                        pop.animateTo(1f, HioneMotion.popNow)
                    }
                    cb()
                }
            }
        )
}
