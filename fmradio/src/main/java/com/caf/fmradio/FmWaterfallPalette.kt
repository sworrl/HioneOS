package com.caf.fmradio

import androidx.compose.ui.graphics.Color

/**
 * The waterfall colour ramp.
 *
 * An SDR waterfall is conventionally navy to cyan to white, and that convention exists for a
 * reason: it is monotonic in perceived brightness, so a stronger signal always *looks*
 * stronger and the eye can rank two streaks without consulting a legend. Any replacement has
 * to keep that property or it stops being an instrument and becomes wallpaper.
 *
 * So this is Hione's palette arranged to satisfy the same constraint. Near-black navy for the
 * noise floor, through her teal, into cyan, and finally into her pink at the top of the scale
 * where the classic ramp would go white. Brightness rises the whole way; the hue shift is what
 * makes it hers.
 *
 * Interpolated rather than bucketed. Five hard colour bands turn a smooth noise floor into
 * visible contour steps, which reads as structure in the signal that is not there.
 */
object FmWaterfallPalette {

    /** Stops, in ascending signal order. Must stay monotonic in brightness. */
    private val STOPS = arrayOf(
        0.00f to Color(0xFF04080E),   // noise floor, navy-black
        0.18f to Color(0xFF07202C),   // deep water
        0.36f to Color(0xFF0E5A66),   // dark teal
        0.54f to Color(0xFFFFAB2E),   // Hione teal
        0.72f to Color(0xFFFFC56B),   // cyan
        0.88f to Color(0xFF8EC5FF),   // Hione pink, where the classic ramp goes white
        1.00f to Color(0xFFFFD9EC),   // hot
    )

    /** Colour for a normalised 0..1 signal level. */
    fun of(level: Float): Color {
        val v = level.coerceIn(0f, 1f)
        for (i in 0 until STOPS.size - 1) {
            val (p0, c0) = STOPS[i]
            val (p1, c1) = STOPS[i + 1]
            if (v <= p1) {
                val t = if (p1 > p0) (v - p0) / (p1 - p0) else 0f
                return Color(
                    red = c0.red + (c1.red - c0.red) * t,
                    green = c0.green + (c1.green - c0.green) * t,
                    blue = c0.blue + (c1.blue - c0.blue) * t,
                    alpha = 1f,
                )
            }
        }
        return STOPS.last().second
    }

    /** Precomputed ramp, because a waterfall asks for the same colours thousands of times a frame. */
    val LUT: Array<Color> = Array(128) { of(it / 127f) }

    fun lut(level: Float): Color = LUT[(level.coerceIn(0f, 1f) * 127f).toInt()]

    /**
     * The same ramp as packed ARGB ints, 256 steps, for writing straight into a bitmap row.
     * The live waterfall is a Bitmap rather than a grid of rects, and this is what fills it.
     */
    val ARGB: IntArray = IntArray(256) { i ->
        val c = of(i / 255f)
        (0xFF shl 24) or ((c.red * 255f + 0.5f).toInt() shl 16) or
            ((c.green * 255f + 0.5f).toInt() shl 8) or (c.blue * 255f + 0.5f).toInt()
    }
}
