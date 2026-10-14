package com.hione.launcher.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Pixel-style app-drawer sheet physics. `progress` = 0 closed … 1 fully open. The sheet is
 * translated by (1 - progress) * height, the scrim fades with progress, and the grid gets a
 * small parallax. Driven three ways:
 *   • the home swipe-up hands its drag here ([dragBy]/[release]) so the sheet FOLLOWS the finger;
 *   • the drawer's own nested-scroll pull-down ([pullDownBy]/[releasePull]) closes it the same way;
 *   • programmatic [open]/[close] (back arrow, app launch) spring it home in ~200 ms.
 * [isSettledOpen]/[isSettledClosed] tell the host which boolean state to publish.
 */
class DrawerSheetState(private val scope: CoroutineScope) {
    val progress = Animatable(0f)
    var heightPx: Float = 1f
    var dragging by mutableStateOf(false)
        private set
    private var job: Job? = null

    /** Composition gate: keep the sheet composed while it is at all visible or being dragged. */
    val visible: Boolean get() = progress.value > 0.001f || dragging

    private val settleSpring = spring<Float>(dampingRatio = 0.86f, stiffness = 380f)
    private val settleFast = spring<Float>(dampingRatio = 1f, stiffness = 1400f)
    private val spec get() = if (HionePowerProfile.isLowPower) settleFast else settleSpring

    fun open() { job?.cancel(); job = scope.launch { progress.animateTo(1f, spec) } }
    fun close() { job?.cancel(); job = scope.launch { progress.animateTo(0f, spec) } }
    fun snapClosed() { job?.cancel(); job = scope.launch { progress.snapTo(0f) } }

    /** Finger moved by [dyPx] (negative = up) while opening from the home screen. */
    fun dragBy(dyPx: Float) {
        job?.cancel()
        dragging = true
        val target = (progress.value + (-dyPx / heightPx)).coerceIn(0f, 1f)
        job = scope.launch { progress.snapTo(target) }
    }

    /** Finger lifted with vertical velocity [vyPxS] (negative = up). Returns true if it opens. */
    fun release(vyPxS: Float): Boolean {
        dragging = false
        val opens = vyPxS < -900f || (vyPxS < 900f && progress.value > 0.35f)
        if (opens) open() else close()
        return opens
    }

    /** Drawer content pulled down past its scroll top by [dyPx] (positive = down). */
    fun pullDownBy(dyPx: Float) {
        job?.cancel()
        dragging = true
        val target = (progress.value - dyPx / heightPx).coerceIn(0f, 1f)
        job = scope.launch { progress.snapTo(target) }
    }

    /** Pull released with velocity [vyPxS] (positive = down). Returns true if it closes. */
    fun releasePull(vyPxS: Float): Boolean {
        dragging = false
        val closes = vyPxS > 900f || (vyPxS > -900f && progress.value < 0.72f)
        if (closes) close() else open()
        return closes
    }
}

@Composable
fun rememberDrawerSheetState(): DrawerSheetState {
    val scope = rememberCoroutineScope()
    return remember { DrawerSheetState(scope) }
}
