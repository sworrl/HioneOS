package com.hione.player.ui

import com.hione.player.Mascot
import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.hione.player.ArtPalette
import com.hione.player.PlayerPreferences

/**
 * Process-wide flags for the Now Playing look (backed by PlayerPreferences, loaded once). Kept as
 * Compose state so a toggle flipped in Settings restyles an already-open Now Playing screen live.
 */
object NowPlayingLook {
    var dynamicColor by mutableStateOf(true)
        private set
    var wavyBar by mutableStateOf(true)
        private set
    @Volatile private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        dynamicColor = PlayerPreferences.loadDynamicColor(ctx)
        wavyBar = PlayerPreferences.loadWavyBar(ctx)
    }

    fun setDynamicColor(ctx: Context, on: Boolean) { dynamicColor = on; PlayerPreferences.saveDynamicColor(ctx, on) }
    fun setWavyBar(ctx: Context, on: Boolean) { wavyBar = on; PlayerPreferences.saveWavyBar(ctx, on) }

    fun isDynamicColorUnlocked(ctx: Context): Boolean =
        com.hione.player.HioneUnlocksReader.isUnlocked(ctx, com.hione.player.HioneUnlocksReader.PLAYER_DYNAMIC_COLOR)
}

/** Desaturates a Color heavily towards a bland, moody dark charcoal/slate. */
fun desaturateColor(c: Color, factor: Float = 0.08f): Color {
    val gray = 0.299f * c.red + 0.587f * c.green + 0.114f * c.blue
    // Subtle cool slate bias to avoid completely flat gray
    val slateR = gray * 0.92f
    val slateG = gray * 0.96f
    val slateB = gray * 1.04f
    return Color(
        red = (slateR + (c.red - gray) * factor).coerceIn(0f, 1f),
        green = (slateG + (c.green - gray) * factor).coerceIn(0f, 1f),
        blue = (slateB + (c.blue - gray) * factor).coerceIn(0f, 1f),
        alpha = c.alpha
    )
}

fun desaturatePalette(p: ArtPalette, factor: Float = 0.08f): ArtPalette = ArtPalette(
    color1 = desaturateColor(p.color1, factor),
    color2 = desaturateColor(p.color2, factor * 0.6f),
    color3 = desaturateColor(p.color3, factor)
)

/**
 * The palette every Now Playing surface (background gradient, wavy bar, transport deck, chips)
 * reads: the art-extracted palette when dynamic color is on AND unlocked, the bland desaturated
 * palette when locked (to incentivize the BPM game), and the Hione identity palette otherwise.
 */
@Composable
fun rememberAnimatedPalette(
    target: ArtPalette,
    dynamic: Boolean,
    isUnlocked: Boolean = true,
    durationMs: Int = 700
): ArtPalette {
    val t = when {
        !isUnlocked -> desaturatePalette(target, factor = 0.07f)
        dynamic -> target
        else -> ArtPalette()
    }
    val c1 by animateColorAsState(t.color1, tween(durationMs), label = "npC1")
    val c2 by animateColorAsState(t.color2, tween(durationMs + 200), label = "npC2")
    val c3 by animateColorAsState(t.color3, tween(durationMs), label = "npC3")
    return ArtPalette(c1, c2, c3)
}

/** Text/glyph color that stays readable on top of [accent] (dark ink on bright faces, white on deep ones). */
fun onAccentColor(accent: Color): Color = if (accent.luminance() > 0.45f) Mascot.color(0xFF07201F) else Color.White

