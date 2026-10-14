package com.hione.launcher.bpm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Game-feel events: the centre "pop" text and the sheet shake.
 *
 * These are the two cheapest pieces of juice that do the most work. A rung crossing, a fever, a
 * lucky note or a record has to land in the player's PERIPHERAL vision, because on a hit their
 * eye is already on the next note — a small floating number does not register there, a big pop
 * and a few pixels of shake do.
 *
 * Engine code fires these; the modal's FX layer renders them. Plain state flows with an id, so a
 * repeat of the same text still re-triggers, and nothing here allocates per frame.
 */
object HioneGameFx {
    data class Pop(val id: Long, val text: String, val color: Long, val big: Boolean)

    private val _pop = MutableStateFlow<Pop?>(null)
    val pop: StateFlow<Pop?> = _pop.asStateFlow()

    /** (sequence, intensity 0..1). The sequence makes back-to-back shakes distinct events. */
    private val _shake = MutableStateFlow(0L to 0f)
    val shake: StateFlow<Pair<Long, Float>> = _shake.asStateFlow()

    private var seq = 0L

    @Synchronized
    fun pop(text: String, color: Long, big: Boolean = false) {
        seq++
        _pop.value = Pop(seq, text, color, big)
    }

    @Synchronized
    fun shake(intensity: Float) {
        seq++
        _shake.value = seq to intensity.coerceIn(0f, 1f)
    }
}
