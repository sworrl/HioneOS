package com.hione.player.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.RectangleShape
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild

/**
 * Glass panels, with the live backdrop blur OFF by default on this hardware.
 *
 * WHY. The main content area was a haze SOURCE and the header, the mini player and several
 * sheets were haze CHILDREN blurring whatever scrolled under them. Haze does that by recording
 * the whole source region into its own layer every frame and running a RenderEffect blur over it,
 * so every scroll frame drew the entire list twice and then blurred it. Profiled 2026-09-19 on the
 * artist list: draw was 43% of the UI thread, the largest single cost left after the accessibility
 * fix, and the Snapdragon 665 has no headroom for it. The panels sit on 90 to 95 percent opaque
 * gradients anyway, so the blur under them was barely visible.
 *
 * NOW. Blur is a runtime setting, `settings put global hione_glass_blur 1`, default off. Off, a
 * panel is the same shape with the same tint, no blur and no second draw of the content. The
 * option stays because the look is real; it is just not worth 40% of the frame budget here.
 */
object HioneGlass {
    private const val KEY = "hione_glass_blur"
    @Volatile private var cached: Boolean? = null

    fun blurEnabled(ctx: Context): Boolean {
        cached?.let { return it }
        val v = runCatching { Settings.Global.getInt(ctx.contentResolver, KEY, 0) == 1 }.getOrDefault(false)
        cached = v
        return v
    }

    /** Re-read the setting on the next call (settings changed in the UI). */
    fun invalidate() { cached = null }
}

/** Marks a region as blur source only when blur is on; otherwise a no-op. */
fun Modifier.hioneHazeSource(ctx: Context, state: HazeState): Modifier =
    if (HioneGlass.blurEnabled(ctx)) this.haze(state) else this

/**
 * A glass panel: blurred backdrop when blur is on, the panel's own tint at a matching opacity
 * when it is off. [tint] is the same color the HazeMaterials style was built from.
 */
fun Modifier.hioneGlassPanel(
    ctx: Context,
    state: HazeState,
    style: HazeStyle,
    tint: Color,
    shape: Shape = RectangleShape,
    fallbackAlpha: Float = 0.92f
): Modifier =
    if (HioneGlass.blurEnabled(ctx)) this.hazeChild(state = state, shape = shape, style = style)
    else this.clip(shape).background(tint.copy(alpha = fallbackAlpha))
