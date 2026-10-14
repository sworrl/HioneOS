package com.hione.systemui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide bridge between the navigation service's top strip (which owns the finger — an
 * accessibility overlay keeps the pointer for the whole gesture) and HioneShadeActivity, so the
 * shade can follow the finger 1:1 after it has been launched mid-pull, and settle with the real
 * release velocity. Both live in the com.hione.systemui process.
 *
 *   dragPx      finger travel below the touch-down point (px)
 *   fingerDown  true while the strip still holds the pointer
 *   velocityPxS vertical velocity at release (+ = downward)
 *   serial      bumps on every new pull so a stale release is never applied to a new panel
 */
object HioneShadeDrag {
    data class State(
        val fingerDown: Boolean = false,
        val dragPx: Float = 0f,
        val velocityPxS: Float = 0f,
        val serial: Int = 0
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    fun begin(dragPx: Float) { _state.value = State(true, dragPx, 0f, _state.value.serial + 1) }
    fun move(dragPx: Float) { val s = _state.value; if (s.fingerDown) _state.value = s.copy(dragPx = dragPx) }
    fun release(velocityPxS: Float) { val s = _state.value; if (s.fingerDown) _state.value = s.copy(fingerDown = false, velocityPxS = velocityPxS) }
    fun reset() { _state.value = State(serial = _state.value.serial) }
}
