package com.hione.launcher.ui

import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Remembers WHICH icon the user tapped so the launch transition can scale the new window up
 * out of that icon (Pixel "open from icon"), and so the icon can settle with a small bounce
 * when the user comes back home.
 *
 * Icons call [set] on press (window-space bounds + package), [launchApp] takes the bounds with
 * [take] (one-shot), and the activity's onResume calls [onHomeReturn] which bumps [homeReturnTick];
 * the icon whose package matches [lastPackage] plays the settle bounce.
 */
object HioneLaunchSource {
    @Volatile private var bounds: Rect? = null
    @Volatile var lastPackage: String? = null
        private set

    private val _homeReturnTick = MutableStateFlow(0L)
    val homeReturnTick: StateFlow<Long> = _homeReturnTick.asStateFlow()

    fun set(rect: Rect?, packageName: String?) {
        bounds = rect
        if (packageName != null) lastPackage = packageName
    }

    /** Consume the pending icon bounds (null if the launch did not start from an icon). */
    fun take(): Rect? { val b = bounds; bounds = null; return b }

    fun onHomeReturn() { _homeReturnTick.value = System.currentTimeMillis() }
}
