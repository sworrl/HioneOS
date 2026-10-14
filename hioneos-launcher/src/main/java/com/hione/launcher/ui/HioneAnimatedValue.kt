package com.hione.launcher.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt

/** Number values tween over 250 ms (Pixel "counting" feel) instead of snapping. */
@Composable
fun animatedInt(target: Int, label: String = "animInt"): Int {
    val v by animateIntAsState(target, tween(250, easing = FastOutSlowInEasing), label = label)
    return v
}

@Composable
fun animatedFloat(target: Float, label: String = "animFloat"): Float {
    val v by animateFloatAsState(target, tween(250, easing = FastOutSlowInEasing), label = label)
    return v
}

/** Whole-number readout of an animated float (temperatures, percentages). */
@Composable
fun animatedRounded(target: Float, label: String = "animRound"): Int = animatedFloat(target, label).roundToInt()

/** Colour crossfade (250 ms) for status/thermal ladders. */
@Composable
fun animatedColor(target: Color, label: String = "animColor"): Color {
    val c by animateColorAsState(target, tween(250), label = label)
    return c
}
