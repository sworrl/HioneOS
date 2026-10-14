package com.hione.launcher.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** System gesture reserves on the M500 (SCREEN edges): the launcher must never react inside these bands. */
private val EDGE_TOP = 24.dp
private val EDGE_BOTTOM = 24.dp
private val EDGE_SIDE = 16.dp
private val TRIGGER = 40.dp
private val FOLLOW_SLOP = 12.dp

/**
 * Pixel-style home-screen vertical swipes: swipe UP anywhere on the workspace opens the app
 * drawer, swipe DOWN opens the (system) notification shade. IGNORES touches that start in the
 * SCREEN's top/bottom/side edge bands (window coordinates — not this node's own edges, which
 * on the workspace pager sit well above the dock), because those belong to the HioneOS system
 * gesture layer (shade pull, pill, edge back).
 *
 * Two modes:
 *  • one-shot ([onSwipeUp]/[onSwipeDown]) after [TRIGGER] of clearly-vertical travel;
 *  • finger-follow for the drawer: when [onDragUp] is given, an upward drag past [FOLLOW_SLOP]
 *    claims the gesture and streams every move (dy px) to it, then [onDragUpEnd] gets the
 *    release velocity (px/s, negative = up) so the sheet can settle. Swipe-down stays one-shot.
 * Runs in the Initial pass and consumes only once it fires/claims, so taps and long-presses on
 * icons underneath keep working.
 */
fun Modifier.homeVerticalSwipe(
    onSwipeUp: () -> Unit,
    onSwipeDown: () -> Unit,
    onDragUp: ((dyPx: Float) -> Unit)? = null,
    onDragUpEnd: ((vyPxS: Float) -> Unit)? = null
): Modifier = composed {
    val windowPos = remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val screenW = with(density) { config.screenWidthDp.dp.toPx() }
    val screenH = with(density) { config.screenHeightDp.dp.toPx() }
    this
        .onGloballyPositioned { c -> windowPos.value = try { c.positionInWindow() } catch (_: Throwable) { Offset.Zero } }
        .pointerInput(onDragUp != null) {
            val top = EDGE_TOP.toPx(); val bottom = EDGE_BOTTOM.toPx(); val side = EDGE_SIDE.toPx()
            val trig = TRIGGER.toPx(); val followSlop = FOLLOW_SLOP.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val p = down.position
                val wx = p.x + windowPos.value.x
                val wy = p.y + windowPos.value.y
                if (wy < top || wy > screenH - bottom || wx < side || wx > screenW - side) return@awaitEachGesture
                var fired = false
                var following = false
                var lastY = p.y
                val tracker = VelocityTracker()
                tracker.addPosition(down.uptimeMillis, down.position)
                while (true) {
                    val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    tracker.addPosition(change.uptimeMillis, change.position)
                    if (!change.pressed) {
                        if (following) onDragUpEnd?.invoke(tracker.calculateVelocity().y)
                        break
                    }
                    if (!following && change.isConsumed) break // an inner scrollable/drag claimed it
                    val dx = change.position.x - p.x
                    val dy = change.position.y - p.y
                    if (following) {
                        change.consume()
                        onDragUp?.invoke(change.position.y - lastY)
                        lastY = change.position.y
                        continue
                    }
                    if (onDragUp != null && dy < -followSlop && abs(dy) > abs(dx) * 1.4f) {
                        following = true
                        change.consume()
                        onDragUp.invoke(dy)            // catch up the travel already made
                        lastY = change.position.y
                        continue
                    }
                    if (!fired && abs(dy) > trig && abs(dy) > abs(dx) * 1.4f) {
                        fired = true
                        change.consume()
                        if (dy < 0) onSwipeUp() else onSwipeDown()
                        break
                    }
                }
            }
        }
}
