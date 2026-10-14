package com.hione.player.ui

import android.view.View
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * HioneOS has NO stock navigation bar. System navigation is the com.hione.systemui accessibility
 * gesture layer: swipe up from the bottom edge = home / recents, a swipe in from either side edge
 * = back, a pull down from the top edge = the Hione notification shade. The OS never sees Compose's
 * `consume()` — but every in-app drag detector that claims a touch beginning inside one of those
 * bands makes the app do its OWN thing on the same finger (dismiss Now Playing, skip a preset,
 * pop the mini player open, close a modal...), which is exactly the "Hione Music grabs gestures"
 * complaint. So the rule, app-wide: a drag that BEGINS inside a system-gesture edge band belongs
 * to the OS and the app must not react to it. This file is the single definition of the bands
 * and the only drag detectors the app's consumers are allowed to use.
 *
 * The bands are measured in WINDOW space (the whole 3.5" display, since the activity draws
 * edge-to-edge and the modal dialogs are full-window), not in the composable's own bounds — a
 * scrubber sitting 10dp above the bottom of the screen is still inside the home-swipe band.
 *
 * `Modifier.systemGestureExclusion` is deliberately NOT used anywhere in the app (the a11y layer
 * doesn't honour it, and on a stock nav it would be the #1 way to break navigation).
 */
object SystemGestureEdges {
    /** Top-edge pull = Hione shade. */
    val TOP = 24.dp
    /** Bottom-edge swipe up = home / recents. Widest band: the pill zone plus a fat-finger margin. */
    val BOTTOM = 32.dp
    /** Side-edge swipe in = back. */
    val SIDE = 20.dp
}

enum class GestureEdge { TOP, BOTTOM, START, END }

val ALL_GESTURE_EDGES: Set<GestureEdge> = setOf(GestureEdge.TOP, GestureEdge.BOTTOM, GestureEdge.START, GestureEdge.END)
val VERTICAL_GESTURE_EDGES: Set<GestureEdge> = setOf(GestureEdge.TOP, GestureEdge.BOTTOM)
val SIDE_GESTURE_EDGES: Set<GestureEdge> = setOf(GestureEdge.START, GestureEdge.END)

/**
 * Knows where one pointer-input node sits inside its window, so a local pointer position can be
 * tested against the system-gesture bands. Created and kept up to date by [edgeSafePointerInput];
 * never construct one by hand.
 */
class EdgeGuard internal constructor() {
    internal var coords: LayoutCoordinates? = null
    internal var view: View? = null
    internal var density: Density = Density(1f)
    /** Which bands this consumer yields to. Default: all four. */
    var edges: Set<GestureEdge> = ALL_GESTURE_EDGES

    /**
     * True when [local] — a pointer position in this node's own coordinate space — lies inside one
     * of the guarded system-gesture bands of the window. Fails OPEN (false) when the node isn't laid
     * out yet, so a consumer can never be bricked by a missing measurement.
     */
    fun isInSystemGestureEdge(local: Offset): Boolean = edgeAt(local) != null

    /** The band [local] falls in, or null when it's in the app-owned interior. */
    fun edgeAt(local: Offset): GestureEdge? {
        if (edges.isEmpty()) return null
        val c = coords?.takeIf { it.isAttached } ?: return null
        val root = view?.rootView ?: return null
        val w = root.width.toFloat()
        val h = root.height.toFloat()
        if (w <= 0f || h <= 0f) return null
        val p = c.localToWindow(local)
        val top = with(density) { SystemGestureEdges.TOP.toPx() }
        val bottom = with(density) { SystemGestureEdges.BOTTOM.toPx() }
        val side = with(density) { SystemGestureEdges.SIDE.toPx() }
        return when {
            GestureEdge.TOP in edges && p.y <= top -> GestureEdge.TOP
            GestureEdge.BOTTOM in edges && p.y >= h - bottom -> GestureEdge.BOTTOM
            GestureEdge.START in edges && p.x <= side -> GestureEdge.START
            GestureEdge.END in edges && p.x >= w - side -> GestureEdge.END
            else -> null
        }
    }
}

/**
 * `pointerInput` that hands the block an [EdgeGuard] resolved for THIS node. Pair it with the
 * `...EdgeSafe` detectors below (or call `guard.isInSystemGestureEdge(down.position)` right after
 * `awaitFirstDown` in a hand-rolled gesture and `return@awaitEachGesture` when it's true —
 * bailing before anything is consumed leaves the whole gesture untouched for the OS).
 */
fun Modifier.edgeSafePointerInput(
    key1: Any?,
    edges: Set<GestureEdge> = ALL_GESTURE_EDGES,
    block: suspend PointerInputScope.(EdgeGuard) -> Unit
): Modifier = composed {
    val view = LocalView.current
    val density = LocalDensity.current
    val guard = remember { EdgeGuard() }
    guard.view = view
    guard.density = density
    guard.edges = edges
    this
        .onGloballyPositioned { guard.coords = it }
        .pointerInput(key1) { block(guard) }
}

/**
 * Drop-in for `detectVerticalDragGestures` that ignores drags beginning in a system-gesture band,
 * and can additionally decline the gesture at touch slop via [accept] (e.g. `{ it > 0f }` for a
 * downward-only swipe) — declining consumes nothing, so the touch falls through untouched.
 */
suspend fun PointerInputScope.detectVerticalDragGesturesEdgeSafe(
    guard: EdgeGuard,
    onDragStart: (Offset) -> Unit = {},
    onDragEnd: () -> Unit = {},
    onDragCancel: () -> Unit = {},
    accept: (overSlop: Float) -> Boolean = { true },
    onVerticalDrag: (change: PointerInputChange, dragAmount: Float) -> Unit
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (guard.isInSystemGestureEdge(down.position)) return@awaitEachGesture
        var overSlop = 0f
        val drag = awaitVerticalTouchSlopOrCancellation(down.id) { change, over ->
            if (accept(over)) {
                change.consume()
                overSlop = over
            }
        } ?: return@awaitEachGesture
        onDragStart(drag.position)
        onVerticalDrag(drag, overSlop)
        val completed = verticalDrag(drag.id) { change ->
            onVerticalDrag(change, change.positionChange().y)
            change.consume()
        }
        if (completed) onDragEnd() else onDragCancel()
    }
}

/** Drop-in for `detectHorizontalDragGestures` that ignores drags beginning in a system-gesture band. */
suspend fun PointerInputScope.detectHorizontalDragGesturesEdgeSafe(
    guard: EdgeGuard,
    onDragStart: (Offset) -> Unit = {},
    onDragEnd: () -> Unit = {},
    onDragCancel: () -> Unit = {},
    onHorizontalDrag: (change: PointerInputChange, dragAmount: Float) -> Unit
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (guard.isInSystemGestureEdge(down.position)) return@awaitEachGesture
        var overSlop = 0f
        val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
            change.consume()
            overSlop = over
        } ?: return@awaitEachGesture
        onDragStart(drag.position)
        onHorizontalDrag(drag, overSlop)
        val completed = horizontalDrag(drag.id) { change ->
            onHorizontalDrag(change, change.positionChange().x)
            change.consume()
        }
        if (completed) onDragEnd() else onDragCancel()
    }
}

/** Drop-in for `detectDragGestures` (any direction) that ignores drags beginning in a system-gesture band. */
suspend fun PointerInputScope.detectDragGesturesEdgeSafe(
    guard: EdgeGuard,
    onDragStart: (Offset) -> Unit = {},
    onDragEnd: () -> Unit = {},
    onDragCancel: () -> Unit = {},
    onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (guard.isInSystemGestureEdge(down.position)) return@awaitEachGesture
        var overSlop = Offset.Zero
        val drag = awaitTouchSlopOrCancellation(down.id) { change, over ->
            change.consume()
            overSlop = over
        } ?: return@awaitEachGesture
        onDragStart(drag.position)
        onDrag(drag, overSlop)
        val completed = drag(drag.id) { change ->
            onDrag(change, change.positionChange())
            change.consume()
        }
        if (completed) onDragEnd() else onDragCancel()
    }
}

/**
 * For containers whose gesture handling can't take an [EdgeGuard] — `HorizontalPager`,
 * `LazyRow`, Material sliders — put this on the container. It watches the Initial pass (which
 * runs parent-first, before any child sees the event) and, for a touch that began in a guarded
 * band AND then moves past touch slop along that band's system axis (vertical for top/bottom,
 * horizontal for the sides), consumes the rest of that gesture so no descendant reacts to it.
 * Taps in the band still reach the children untouched (nothing is consumed until slop), and a
 * cross-axis drag (e.g. scrolling a list up from the side band) is left alone — the OS only cares
 * about the on-axis swipe.
 */
fun Modifier.yieldSystemGestureEdges(
    edges: Set<GestureEdge> = ALL_GESTURE_EDGES
): Modifier = edgeSafePointerInput(edges, edges) { guard ->
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val edge = guard.edgeAt(down.position) ?: return@awaitEachGesture
        val slop = viewConfiguration.touchSlop
        var total = Offset.Zero
        var stolen = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (stolen) {
                event.changes.forEach { it.consume() }
            } else {
                total += change.positionChange()
                val onAxis = when (edge) {
                    GestureEdge.TOP, GestureEdge.BOTTOM -> abs(total.y)
                    GestureEdge.START, GestureEdge.END -> abs(total.x)
                }
                val offAxis = when (edge) {
                    GestureEdge.TOP, GestureEdge.BOTTOM -> abs(total.x)
                    GestureEdge.START, GestureEdge.END -> abs(total.y)
                }
                if (onAxis > slop && onAxis >= offAxis) {
                    stolen = true
                    event.changes.forEach { it.consume() }
                } else if (offAxis > slop) {
                    // Clearly a cross-axis drag: not a system gesture — hands off for good.
                    break
                }
            }
            if (!change.pressed) break
        }
    }
}
