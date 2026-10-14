package com.hione.player.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp

/** Fraction of the composable's height, measured from the bottom, where a dismiss swipe may begin. */
private const val BOTTOM_START_FRACTION = 0.15f

/** Cumulative upward travel required before the swipe dismisses — generous so grazes never fire. */
private val DISMISS_DISTANCE = 80.dp

/**
 * Swipe-up-from-the-bottom-to-dismiss gesture for HioneOS full-screen modals, matching the system
 * gesture-nav feel: a drag that STARTS in the bottom [BOTTOM_START_FRACTION] of this composable
 * and travels upward past [DISMISS_DISTANCE] calls [onDismiss] exactly once.
 *
 * Safe to place on the ROOT container of a modal:
 * - Runs in the Main pointer pass, so inner scrollables/draggables (which see events before this
 *   parent) that claim the gesture cancel it — inner scrolling and games are never broken.
 * - Nothing is consumed until the drag passes touch slop AND is moving upward, so taps, clicks,
 *   and downward drags fall through to the modal's own content untouched.
 * - Drags beginning above the bottom band are ignored entirely.
 * - Drags beginning inside a SYSTEM gesture band of the window (bottom 32dp = home/recents, the
 *   side/top bands too — see [SystemGestureEdges]) are left entirely to the HioneOS nav layer:
 *   the modal only answers a swipe that starts in its bottom 15% but ABOVE the OS's home strip.
 */
fun Modifier.swipeUpFromBottomToDismiss(
    enabled: Boolean = true,
    onDismiss: () -> Unit
): Modifier {
    if (!enabled) return this
    return edgeSafePointerInput(onDismiss) { guard ->
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            // The OS owns the window's edge bands — never arm on a touch that starts there.
            if (guard.isInSystemGestureEdge(down.position)) return@awaitEachGesture
            // Arm only when the touch lands in the bottom band of this composable.
            if (down.position.y < size.height * (1f - BOTTOM_START_FRACTION)) return@awaitEachGesture
            val dismissPx = DISMISS_DISTANCE.toPx()
            var totalDy = 0f
            // Wait for touch slop; claim the gesture only once movement is clearly UPWARD.
            // Declining to consume on downward/ambiguous movement leaves the events for inner
            // content, so this never steals a scroll or a tap.
            val slopChange = awaitVerticalTouchSlopOrCancellation(down.id) { change, overSlop ->
                if (overSlop < 0f) {
                    change.consume()
                    totalDy = overSlop
                }
            } ?: return@awaitEachGesture
            var dismissed = false
            if (totalDy <= -dismissPx) {
                dismissed = true
                onDismiss()
            }
            // Gesture is ours now: keep consuming until finger-up so the UI underneath
            // doesn't also react to the swipe, and fire onDismiss at the threshold.
            verticalDrag(slopChange.id) { change ->
                totalDy += change.positionChange().y
                change.consume()
                if (!dismissed && totalDy <= -dismissPx) {
                    dismissed = true
                    onDismiss()
                }
            }
        }
    }
}
