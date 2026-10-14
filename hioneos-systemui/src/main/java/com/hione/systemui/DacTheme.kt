package com.hione.systemui

// DAC theme: one pure function from the applied DAC settings to a palette.
//
// The same file (only the package line differs) lives in hardware-settings, hioneos-systemui and
// Hione Music (app). Keep them identical so the Hardware app, the status bar badge, the SystemUI
// tiles and Hione Music's DAC panel always show the same colors for the same settings.
//
// Mapping:
//   filter      -> base hue. NOS 32 (amber), fast/low latency 190 (cyan), fast/phase compensated
//                  168 (teal), slow/low latency 268 (violet), slow/phase compensated 318
//                  (magenta). Unknown: 205 with almost no saturation (grey blue).
//   gain        -> saturation and lightness. low 0.50 / 0.60, middle 0.68 / 0.60, high 0.86 / 0.58.
//   DRE on      -> secondary accent 55 degrees round the wheel, saturated. Off: the base hue, muted.
//   high power  -> glow 0.9 and pulse on. Off: glow 0.3, no pulse.
// Every text color is pushed lighter until it reaches 4.5:1 against the background tint.
//
// Inputs are the raw property values (persist.vendor.audio.hione.* or the hione_dac_state string):
//   filter  fast_rolloff_low_latency | fast_rolloff_phase_compensated | slow_rolloff_low_latency |
//           slow_rolloff_phase_compensated | nos | null
//   gain    low | middle | high | null
//   dre, hp true | false | null (null = never set)

/** Colors are ARGB ints so View code and Compose (Color(int)) can both use them. */
data class DacPalette(
    /** Main accent. Text-safe on [background]. */
    val primary: Int,
    /** Second accent (gradient stop, borders). Text-safe on [background]. */
    val secondary: Int,
    /** Dark tinted background for cards and the badge. */
    val background: Int,
    /** Text color to draw on top of [primary]. */
    val onPrimary: Int,
    /** 0..1, border and halo strength. */
    val glow: Float,
    /** True when high power is on: surfaces may pulse the glow slowly. */
    val pulse: Boolean,
    /** The combo in plain words, for a header. */
    val label: String,
    /** Short form for the status bar badge, e.g. "NOS · HI · DRE · HP". Empty when nothing is set. */
    val badge: String,
)

/** Parsed hione_dac_state. */
data class DacState(val filter: String?, val gain: String?, val dre: Boolean?, val hp: Boolean?) {
    val isEmpty: Boolean get() = filter == null && gain == null && dre == null && hp == null
}

/** Parse Settings.Global hione_dac_state, "filter=nos;gain=high;dre=dremode_enable;hp=hpower_enable". */
fun parseDacState(raw: String?): DacState {
    val m = raw.orEmpty().split(';').mapNotNull { part ->
        val i = part.indexOf('=')
        if (i <= 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim()
    }.toMap()
    fun v(k: String) = m[k]?.ifEmpty { null }
    return DacState(
        filter = v("filter"),
        gain = v("gain"),
        dre = v("dre")?.let { it == "dremode_enable" },
        hp = v("hp")?.let { it == "hpower_enable" },
    )
}

fun dacTheme(s: DacState): DacPalette = dacTheme(s.filter, s.gain, s.dre, s.hp)

fun dacTheme(filter: String?, gain: String?, dre: Boolean?, hp: Boolean?): DacPalette {
    val hue = when (filter) {
        "nos" -> 32f
        "fast_rolloff_low_latency" -> 190f
        "fast_rolloff_phase_compensated" -> 168f
        "slow_rolloff_low_latency" -> 268f
        "slow_rolloff_phase_compensated" -> 318f
        else -> 205f
    }
    val known = filter in DAC_FILTERS
    val (sat, light) = when (gain) {
        "low" -> 0.50f to 0.60f
        "middle" -> 0.68f to 0.60f
        "high" -> 0.86f to 0.58f
        else -> 0.60f to 0.60f
    }
    val s = if (known) sat else 0.12f
    val background = hsl(hue, if (known) 0.38f else 0.10f, 0.07f)
    val primary = readableOn(hue, s, light, background)
    val secondary = if (dre == true) readableOn((hue + 55f) % 360f, 0.80f, 0.62f, background)
                    else readableOn(hue, s * 0.35f, 0.66f, background)
    val onPrimary = if (dacContrast(0xFF000000.toInt(), primary) >= dacContrast(0xFFFFFFFF.toInt(), primary)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()

    val filterWords = when (filter) {
        "nos" -> "NOS"
        "fast_rolloff_low_latency" -> "Fast roll-off, low latency"
        "fast_rolloff_phase_compensated" -> "Fast roll-off, phase compensated"
        "slow_rolloff_low_latency" -> "Slow roll-off, low latency"
        "slow_rolloff_phase_compensated" -> "Slow roll-off, phase compensated"
        null -> "Filter not set"
        else -> filter
    }
    val gainWords = when (gain) { "low" -> "low gain"; "middle" -> "middle gain"; "high" -> "high gain"; null -> "gain not set"; else -> "gain $gain" }
    val dreWords = when (dre) { true -> "DRE on"; false -> "DRE off"; null -> "DRE not set" }
    val hpWords = when (hp) { true -> "high power on"; false -> "high power off"; null -> "high power not set" }

    val badge = listOfNotNull(
        when (filter) {
            "nos" -> "NOS"
            "fast_rolloff_low_latency" -> "FAST-LL"
            "fast_rolloff_phase_compensated" -> "FAST-PC"
            "slow_rolloff_low_latency" -> "SLOW-LL"
            "slow_rolloff_phase_compensated" -> "SLOW-PC"
            else -> null
        },
        when (gain) { "low" -> "LO"; "middle" -> "MID"; "high" -> "HI"; else -> null },
        if (dre == true) "DRE" else null,
        if (hp == true) "HP" else null,
    ).joinToString(" · ")

    return DacPalette(
        primary = primary,
        secondary = secondary,
        background = background,
        onPrimary = onPrimary,
        glow = if (hp == true) 0.9f else 0.3f,
        pulse = hp == true,
        label = "$filterWords, $gainWords, $dreWords, $hpWords",
        badge = badge,
    )
}

private val DAC_FILTERS = setOf(
    "nos", "fast_rolloff_low_latency", "fast_rolloff_phase_compensated",
    "slow_rolloff_low_latency", "slow_rolloff_phase_compensated",
)

/** The color for (hue, sat, light), made lighter in steps until it reaches 4.5:1 on [bg]. */
private fun readableOn(hue: Float, sat: Float, light: Float, bg: Int): Int {
    var l = light
    var c = hsl(hue, sat, l)
    while (dacContrast(c, bg) < 4.5f && l < 0.95f) {
        l += 0.02f
        c = hsl(hue, sat, l)
    }
    return c
}

private fun hsl(h: Float, s: Float, l: Float): Int {
    val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
    val hp = (h % 360f) / 60f
    val x = c * (1f - kotlin.math.abs(hp % 2f - 1f))
    val (r1, g1, b1) = when {
        hp < 1f -> Triple(c, x, 0f)
        hp < 2f -> Triple(x, c, 0f)
        hp < 3f -> Triple(0f, c, x)
        hp < 4f -> Triple(0f, x, c)
        hp < 5f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val m = l - c / 2f
    fun ch(v: Float) = ((v + m).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    return (0xFF shl 24) or (ch(r1) shl 16) or (ch(g1) shl 8) or ch(b1)
}

private fun luminance(argb: Int): Float {
    fun lin(c: Int): Float {
        val v = c / 255f
        return if (v <= 0.03928f) v / 12.92f else Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    }
    return 0.2126f * lin((argb shr 16) and 0xFF) + 0.7152f * lin((argb shr 8) and 0xFF) + 0.0722f * lin(argb and 0xFF)
}

/** WCAG contrast ratio of two opaque colors. */
fun dacContrast(a: Int, b: Int): Float {
    val la = luminance(a); val lb = luminance(b)
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}
