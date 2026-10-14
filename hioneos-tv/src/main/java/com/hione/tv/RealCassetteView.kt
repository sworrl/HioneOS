package com.hione.tv

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.player.ProjectMNative
import kotlinx.coroutines.delay
import kotlin.math.*

// ── IEC 60094-7 Compact Cassette Mechanical Specification (mm) ──
private const val SHELL_W = 101.6f
private const val SHELL_H = 63.5f
private const val SHELL_R = 1.8f
private const val WALL = 2.3f
private const val HUB_Y = 28.4f
private const val HUB_SPACING = 42.5f
private const val HUB_L_X = (SHELL_W - HUB_SPACING) / 2f
private const val HUB_R_X = (SHELL_W + HUB_SPACING) / 2f
private const val SPINDLE_R = 4.25f
private const val HUB_R = 10.9f
private const val PACK_MIN_R = 11.4f
private const val PACK_MAX_R = 24.5f
private const val WINDOW_W = 44f
private const val WINDOW_H = 13f
private const val GUIDE_L_X = 8.0f
private const val GUIDE_R_X = SHELL_W - 8.0f
private const val GUIDE_Y = 56.4f
private const val GUIDE_ROLLER_R = 2.6f
private const val TAPE_RUN_Y = 59.3f
private const val CAPSTAN_R = 2.3f

enum class TapeLayout { CLEAR, STUDIO, MAJOR84, NEON, MERCH }

enum class PlasticType {
    CLEAR_POLYCARBONATE,
    SMOKED_ACRYLIC,
    MATTE_COMPOSITE,
    SATIN_POLYSTYRENE,
    METALLIC_FLAKE,
    PHOSPHOR_GLOW
}

enum class BrandTexture {
    DEBOSSED_ENAMEL,
    SCREENPRINT_NEON,
    OFFSET_PRINT_INK,
    HOT_STAMP_GOLD,
    HOT_STAMP_CHROME,
    DOT_MATRIX_IMPRINT,
    DISCO_INLINE_NEON,
    JAPANESE_KAWAII,
    MINIMAL_LASER_ETCH
}

data class TapeTheme(
    val name: String,
    val shellHi: Color,
    val shellLo: Color,
    val engrave: Color,
    val bevelHi: Color,
    val bevelLo: Color,
    val hubHi: Color,
    val hubLo: Color,
    val holes: Color,
    val ink: Color,
    val layout: TapeLayout = TapeLayout.CLEAR,
    val plasticType: PlasticType = PlasticType.CLEAR_POLYCARBONATE,
    val brandText: String = "HIONE MUSIC",
    val brandSub: String = "STEREO CASSETTE TAPE",
    val brandFont: FontFamily = AudiowideFont,
    val brandColor: Color = Color(0xFFFFAB2E),
    val brandSubColor: Color = Color(0xFF9AECE6),
    val brandTexture: BrandTexture = BrandTexture.SCREENPRINT_NEON,
    val accent: Color = Color(0xFFFFAB2E),
    val wallAlpha: Float = 0.88f,
    val cavityAlpha: Float = 0.30f,
    val welded: Boolean = false,
    val motif39: Boolean = false
)

val REAL_TAPE_THEMES = listOf(
    TapeTheme(
        name = "HIONE STUDIO 90",
        shellHi = Color(0xFF262633), shellLo = Color(0xFF14141B), engrave = Color(0x55FFAB2E),
        bevelHi = Color(0xFF9AECE6), bevelLo = Color(0xFF0B0B10),
        hubHi = Color(0xFFF5FFFE), hubLo = Color(0xFF2FA39B), holes = Color(0xFF0F3D39), ink = Color(0xFFE8FBF8),
        layout = TapeLayout.STUDIO, plasticType = PlasticType.SMOKED_ACRYLIC,
        brandText = "HIONE STUDIO 90", brandSub = "TYPE II HIGH BIAS (CrO2) · 70µs EQ",
        brandFont = AudiowideFont, brandColor = Color(0xFFFFAB2E), brandSubColor = Color(0xFF9AECE6),
        brandTexture = BrandTexture.DEBOSSED_ENAMEL, accent = Color(0xFFFFAB2E),
        wallAlpha = 0.62f, cavityAlpha = 0.22f
    ),
    TapeTheme(
        name = "MEMOREX dBS-90",
        shellHi = Color(0xFF2A4A4E), shellLo = Color(0xFF0E2226), engrave = Color(0x55FFAB2E),
        bevelHi = Color(0xFFFFD08A), bevelLo = Color(0xFF08181A),
        hubHi = Color(0xFFFFDD3A), hubLo = Color(0xFFB38F00), holes = Color(0xFF3A2E00), ink = Color(0xFFE8FBF8),
        plasticType = PlasticType.CLEAR_POLYCARBONATE,
        brandText = "HIONE dBS-90", brandSub = "HIGH OUTPUT · LOW NOISE · WIDE DYNAMIC",
        brandFont = RighteousFont, brandColor = Color(0xFFFFD200), brandSubColor = Color(0xFFE8FBF8),
        brandTexture = BrandTexture.SCREENPRINT_NEON, accent = Color(0xFFFFD200)
    ),
    TapeTheme(
        name = "24K GOLD MASTER",
        shellHi = Color(0xFFE8C878), shellLo = Color(0xFF8A6A21), engrave = Color(0x55704E12),
        bevelHi = Color(0xFFFCEFB8), bevelLo = Color(0xFF5A410E),
        hubHi = Color(0xFFF3ECD6), hubLo = Color(0xFF9A8A5E), holes = Color(0xFF241B08), ink = Color(0xFFFDF4D8),
        plasticType = PlasticType.METALLIC_FLAKE,
        brandText = "HIONE 24K GOLD MASTER", brandSub = "AUDIOPHILE REFERENCE · SPECIAL EDITION",
        brandFont = AudiowideFont, brandColor = Color(0xFF2C1A04), brandSubColor = Color(0xFF4E320A),
        brandTexture = BrandTexture.HOT_STAMP_GOLD, accent = Color(0xFFFFD700)
    ),
    TapeTheme(
        name = "MAJOR '84",
        shellHi = Color(0xFFF2E9D7), shellLo = Color(0xFFD9C9A8), engrave = Color(0x334A3520),
        bevelHi = Color(0xFFFFF9EC), bevelLo = Color(0xFF8A7A56),
        hubHi = Color(0xFFFFFFFF), hubLo = Color(0xFFB9AB8C), holes = Color(0xFF3A2F1C), ink = Color(0xFF2B2B2B),
        layout = TapeLayout.MAJOR84, plasticType = PlasticType.SATIN_POLYSTYRENE,
        brandText = "HIONE RECORDS 1984", brandSub = "ORIGINAL STEREO CASSETTE · DOLBY SYSTEM",
        brandFont = RighteousFont, brandColor = Color(0xFF23160C), brandSubColor = Color(0xFF4A3520),
        brandTexture = BrandTexture.OFFSET_PRINT_INK, accent = Color(0xFFC9A227),
        wallAlpha = 0.96f, cavityAlpha = 0.55f
    ),
    TapeTheme(
        name = "NEON WU-TAPE",
        shellHi = Color(0xFF1B1B20), shellLo = Color(0xFF0E0E12), engrave = Color(0x44FFD500),
        bevelHi = Color(0xFFFFE566), bevelLo = Color(0xFF060608),
        hubHi = Color(0xFFFFD500), hubLo = Color(0xFFB89A00), holes = Color(0xFF3A3000), ink = Color(0xFFFFD500),
        layout = TapeLayout.NEON, plasticType = PlasticType.MATTE_COMPOSITE,
        brandText = "HIONE 36 CHAMBERS", brandSub = "ENTER THE SOUND · HI-ENERGY FLUX 120µs",
        brandFont = OrbitronFont, brandColor = Color(0xFFFFD500), brandSubColor = Color(0xFFFFF066),
        brandTexture = BrandTexture.SCREENPRINT_NEON, accent = Color(0xFFFFD500),
        wallAlpha = 0.94f, cavityAlpha = 0.48f, welded = true
    ),
    TapeTheme(
        name = "NEON CYBER DECK",
        shellHi = Color(0xFF1B1B20), shellLo = Color(0xFF0E0E12), engrave = Color(0x44FFAB2E),
        bevelHi = Color(0xFF9AECE6), bevelLo = Color(0xFF060608),
        hubHi = Color(0xFFFFAB2E), hubLo = Color(0xFF1F8079), holes = Color(0xFF0A2C29), ink = Color(0xFFFFAB2E),
        layout = TapeLayout.NEON, plasticType = PlasticType.MATTE_COMPOSITE,
        brandText = "HIONE CYBER DECK", brandSub = "DIGITAL ACCELERATION · DIRECT BIT-PERFECT",
        brandFont = OrbitronFont, brandColor = Color(0xFFFFAB2E), brandSubColor = Color(0xFF8CF0E8),
        brandTexture = BrandTexture.SCREENPRINT_NEON, accent = Color(0xFFFFAB2E),
        wallAlpha = 0.94f, cavityAlpha = 0.48f, welded = true
    ),
    TapeTheme(
        name = "TDK SA-X 90",
        shellHi = Color(0xFF2E2E34), shellLo = Color(0xFF17171B), engrave = Color(0x44D0D0D6),
        bevelHi = Color(0xFFB8B8C2), bevelLo = Color(0xFF0B0B0E),
        hubHi = Color(0xFFE8E8EC), hubLo = Color(0xFF8A8A94), holes = Color(0xFF1E1E24), ink = Color(0xFFE9E9EE),
        layout = TapeLayout.STUDIO, plasticType = PlasticType.SMOKED_ACRYLIC,
        brandText = "HIONE SA-X 90", brandSub = "HIGH BIAS 70µs EQ · SUPER AVILYN DUAL COATING",
        brandFont = OrbitronFont, brandColor = Color(0xFFF2F2F5), brandSubColor = Color(0xFFB8B8C6),
        brandTexture = BrandTexture.SCREENPRINT_NEON, accent = Color(0xFFD62828),
        wallAlpha = 0.58f, cavityAlpha = 0.24f
    ),
    TapeTheme(
        name = "MAXELL XLII-S",
        shellHi = Color(0xFF232320), shellLo = Color(0xFF121210), engrave = Color(0x55C9A227),
        bevelHi = Color(0xFFE9CD7A), bevelLo = Color(0xFF0A0A08),
        hubHi = Color(0xFF2A2A26), hubLo = Color(0xFF171714), holes = Color(0xFFC9A227), ink = Color(0xFFEFD98F),
        layout = TapeLayout.STUDIO, plasticType = PlasticType.MATTE_COMPOSITE,
        brandText = "HIONE XLII-S 90", brandSub = "BLACK MAGNETITE · HIGH RESONANCE DAMPING",
        brandFont = AudiowideFont, brandColor = Color(0xFFFFD866), brandSubColor = Color(0xFFD8B84E),
        brandTexture = BrandTexture.HOT_STAMP_GOLD, accent = Color(0xFFC9A227),
        wallAlpha = 0.92f, cavityAlpha = 0.45f
    ),
    TapeTheme(
        name = "BASF CHROME EXTRA",
        shellHi = Color(0xFFF4F4EF), shellLo = Color(0xFFD8D8CF), engrave = Color(0x33006B44),
        bevelHi = Color(0xFFFFFFFA), bevelLo = Color(0xFF8F8F85),
        hubHi = Color(0xFFFFFFFF), hubLo = Color(0xFFB9B9AF), holes = Color(0xFF00552F), ink = Color(0xFF00552F),
        layout = TapeLayout.STUDIO, plasticType = PlasticType.SATIN_POLYSTYRENE,
        brandText = "HIONE CR-E II 90", brandSub = "CHROMDIOXID EXTRA II · HIGH OUTPUT / IEC II",
        brandFont = RighteousFont, brandColor = Color(0xFF00452A), brandSubColor = Color(0xFF00633C),
        brandTexture = BrandTexture.OFFSET_PRINT_INK, accent = Color(0xFF00814F),
        wallAlpha = 0.96f, cavityAlpha = 0.55f
    ),
    TapeTheme(
        name = "SONY METAL MASTER",
        shellHi = Color(0xFF4A4E54), shellLo = Color(0xFF23262B), engrave = Color(0x44FF6A00),
        bevelHi = Color(0xFFB9BFC7), bevelLo = Color(0xFF14161A),
        hubHi = Color(0xFF6A7078), hubLo = Color(0xFF3A3E44), holes = Color(0xFF16181C), ink = Color(0xFFE8ECF1),
        layout = TapeLayout.STUDIO, plasticType = PlasticType.MATTE_COMPOSITE,
        brandText = "HIONE METAL MASTER 90", brandSub = "CERAMIC GUIDE / TYPE IV (METAL) 70µs",
        brandFont = OrbitronFont, brandColor = Color(0xFFFF6600), brandSubColor = Color(0xFFFFA866),
        brandTexture = BrandTexture.HOT_STAMP_CHROME, accent = Color(0xFFFF6A00),
        wallAlpha = 0.93f, cavityAlpha = 0.42f
    ),
    TapeTheme(
        name = "NAKAMICHI ZX REF",
        shellHi = Color(0xFF3A3F42), shellLo = Color(0xFF1C1F21), engrave = Color(0x44AEB6BA),
        bevelHi = Color(0xFFCED6DA), bevelLo = Color(0xFF0E1011),
        hubHi = Color(0xFFDDE3E6), hubLo = Color(0xFF878E92), holes = Color(0xFF24282A), ink = Color(0xFFEFF4F6),
        layout = TapeLayout.CLEAR, plasticType = PlasticType.CLEAR_POLYCARBONATE,
        brandText = "HIONE ZX REFERENCE", brandSub = "DISCRETE 3-HEAD PRECISION CALIBRATION · CRYSTALLOY",
        brandFont = OrbitronFont, brandColor = Color(0xFFE8ECEF), brandSubColor = Color(0xFF90A4AE),
        brandTexture = BrandTexture.MINIMAL_LASER_ETCH, accent = Color(0xFFB0B8BC),
        wallAlpha = 0.42f, cavityAlpha = 0.16f
    ),
    TapeTheme(
        name = "VAPORWAVE 80s",
        shellHi = Color(0xFF201826), shellLo = Color(0xFF100C14), engrave = Color(0x44FF3FD8),
        bevelHi = Color(0xFFFF9BE8), bevelLo = Color(0xFF080609),
        hubHi = Color(0xFFFF3FD8), hubLo = Color(0xFFA3238B), holes = Color(0xFF32082A), ink = Color(0xFFFF3FD8),
        layout = TapeLayout.NEON, plasticType = PlasticType.PHOSPHOR_GLOW,
        brandText = "ＨＩＯＮＥ  ＭＵＳＩＣ", brandSub = "ＡＥＳＴＨＥＴＩＣ  ＮＩＧＨＴ  ＣＡＳＳＥＴＴＥ  ８０ｓ",
        brandFont = MonotonFont, brandColor = Color(0xFFFF3FD8), brandSubColor = Color(0xFFFFA6EC),
        brandTexture = BrandTexture.DISCO_INLINE_NEON, accent = Color(0xFFFF3FD8),
        wallAlpha = 0.94f, cavityAlpha = 0.46f, welded = true
    )
)

data class TapeGrade(
    val rank: Int,
    val typeLabel: String,
    val formulation: String,
    val eq: String,
    val oxideHi: Color,
    val oxideLo: Color,
    val detail: String
) {
    val q: Float get() = if (rank <= 0) 0.45f else ((rank - 1) / 4f).coerceIn(0f, 1f)
    val wear: Float get() = if (rank <= 0) 0.5f else (1f - q).coerceIn(0f, 1f)
    val isKnown: Boolean get() = rank > 0
}

fun computeTapeGrade(bits: Int, sr: Int): TapeGrade {
    val khz = if (sr > 0) sr / 1000f else 44.1f
    val depth = if (bits > 0) bits else 16
    val detail = "$depth-bit · ${"%.1f".format(khz)} kHz"
    return when {
        depth >= 32 || sr > 96000 -> TapeGrade(
            5, "MASTER", "STUDIO MASTERING STOCK · HIGH COERCIVITY", "70µs EQ",
            oxideHi = Color(0xFF241E18), oxideLo = Color(0xFF0B0907), detail = detail
        )
        depth >= 24 || sr > 48000 -> TapeGrade(
            4, "TYPE IV", "METAL PARTICLE POSITION · IEC IV", "70µs EQ",
            oxideHi = Color(0xFF2A2C31), oxideLo = Color(0xFF0D0E11), detail = detail
        )
        sr >= 44100 -> TapeGrade(
            3, "TYPE II", "HIGH BIAS CHROME (CrO2) · IEC II", "70µs EQ",
            oxideHi = Color(0xFF3A302A), oxideLo = Color(0xFF16110E), detail = detail
        )
        else -> TapeGrade(
            2, "TYPE I", "PREMIUM FERRIC (Fe2O3) · IEC I", "120µs EQ",
            oxideHi = Color(0xFF5A4230), oxideLo = Color(0xFF2B1E14), detail = detail
        )
    }
}

// ── Photorealistic Masking Tape Textures & Deckled Ends ──
private data class TornStep(val yFrac: Float, val depthPx: Float)
private data class FiberWisp(val side: Int, val yFrac: Float, val lenPx: Float, val angleDeg: Float)

private fun randomTornEdge(rnd: kotlin.random.Random): List<TornStep> {
    val n = 11 + rnd.nextInt(8)
    val raw = FloatArray(n) { 0.4f + rnd.nextFloat() * 0.7f }
    val total = raw.sum()
    var acc = 0f
    return raw.map { r ->
        acc += r
        val yFrac = (acc / total).coerceIn(0.05f, 0.97f)
        val big = rnd.nextFloat() < 0.12f
        val mag = if (big) 1.3f + rnd.nextFloat() * 0.9f else 0.25f + rnd.nextFloat() * 0.85f
        val sign = if (rnd.nextFloat() < 0.55f) 1f else -1f
        TornStep(yFrac, mag * sign)
    }
}

private data class MaskingTapeStyle(
    val name: String,
    val paperBaseHi: Color,
    val paperBaseLo: Color,
    val ridgeColor: Color,
    val penColor: Color
)

private val MASKING_TAPE_STYLES = listOf(
    MaskingTapeStyle("Scotch Tan", Color(0xFFF7EED8), Color(0xFFE5D5B2), Color(0x228A6E3D), Color(0xFF14141E)),
    MaskingTapeStyle("Vintage Ivory", Color(0xFFFAF2E4), Color(0xFFDDD0B5), Color(0x25705A32), Color(0xFF1E2838)),
    MaskingTapeStyle("Off-White Draft", Color(0xFFFAFAFA), Color(0xFFEAE6DF), Color(0x1C606060), Color(0xFF0F1E36)),
    MaskingTapeStyle("Aqua Painter", Color(0xFFDCF5F2), Color(0xFFB8E8E3), Color(0x2224857D), Color(0xFF0C2B2E)),
    MaskingTapeStyle("Artist Cream", Color(0xFFFFF8EA), Color(0xFFF0E2C6), Color(0x208A744A), Color(0xFF281C10))
)

// ── Reel Geometry Cache (Archimedean Spirals & Oxide Flecks) ──
private data class FleckSpec(val start: Offset, val end: Offset, val alpha: Float, val width: Float)
private data class ReelGeometry(val spiral1: Path, val spiral2: Path, val flecks: List<FleckSpec>)
private val reelGeometryCache = HashMap<Pair<Float, Int>, ReelGeometry>()

private fun tapeHash(u: Float, seed: Float): Float {
    val x = u * 12.9898f + seed * 78.233f
    val sinVal = kotlin.math.sin(x.toDouble()).toFloat() * 43758.5453f
    return sinVal - kotlin.math.floor(sinVal)
}

private fun buildReelGeometry(cx: Float, cy: Float, tapeR: Float, packDepth: Float): ReelGeometry {
    val numTurns = (packDepth * 2.6f).coerceIn(4f, 36f)
    val totalRad = numTurns * 2f * Math.PI.toFloat()
    val steps = (numTurns * 28).toInt()

    val wobbleSeed = cx * 0.37f + cy * 0.71f
    fun woundR(t: Float, rad: Float): Float {
        val base = HUB_R + 0.35f + t * (packDepth - 0.35f)
        val wobble = 0.06f * packDepth * (
            kotlin.math.sin(rad * 0.9f + wobbleSeed) * kotlin.math.sin(rad * 0.211f + wobbleSeed * 1.7f) +
            0.4f * kotlin.math.sin(rad * 2.03f - wobbleSeed)
        )
        return base + wobble
    }

    val spiral1 = Path()
    var first = true
    for (i in 0..steps) {
        val ft = i.toFloat() / steps.toFloat()
        val rad = ft * totalRad
        val curR = woundR(ft, rad)
        val px = cx + curR * cos(rad)
        val py = cy + curR * sin(rad)
        if (first) { spiral1.moveTo(px, py); first = false } else spiral1.lineTo(px, py)
    }

    val phase2 = Math.PI.toFloat() * (0.85f + 0.3f * tapeHash(wobbleSeed, 5.2f))
    val spiral2 = Path()
    first = true
    for (i in 0..steps) {
        val ft = i.toFloat() / steps.toFloat()
        val rad = phase2 + ft * totalRad
        val curR = woundR(ft, rad)
        val px = cx + curR * cos(rad)
        val py = cy + curR * sin(rad)
        if (first) { spiral2.moveTo(px, py); first = false } else spiral2.lineTo(px, py)
    }

    val flecks = ArrayList<FleckSpec>()
    for (ring in 1..6) {
        val ringR = HUB_R + 0.8f + (packDepth - 1.2f) * (ring.toFloat() / 7f)
        val numFlecks = (ringR * 1.8f).toInt()
        for (k in 0 until numFlecks) {
            val seed = ring * 17.3f + k * 3.11f
            val jitterA = (tapeHash(seed, 1.1f) - 0.5f) * (2f * Math.PI.toFloat() / numFlecks) * 0.9f
            val fAngle = (k.toFloat() / numFlecks) * 2f * Math.PI.toFloat() + (ring * 1.618f) + jitterA
            val rJitter = (tapeHash(seed, 9.4f) - 0.5f) * 0.5f
            val fx = cx + (ringR + rJitter) * cos(fAngle)
            val fy = cy + (ringR + rJitter) * sin(fAngle)
            val fleckLen = 0.22f + tapeHash(seed, 4.6f) * 0.45f
            val fAlpha = 0.08f + tapeHash(seed, 12.2f) * 0.24f
            flecks.add(
                FleckSpec(
                    Offset(fx - sin(fAngle) * (fleckLen / 2f), fy + cos(fAngle) * (fleckLen / 2f)),
                    Offset(fx + sin(fAngle) * (fleckLen / 2f), fy - cos(fAngle) * (fleckLen / 2f)),
                    fAlpha, 0.14f + tapeHash(seed, 17.8f) * 0.08f
                )
            )
        }
    }
    return ReelGeometry(spiral1, spiral2, flecks)
}

// ── Contrast & Formatting Helpers ──
private fun fmt(ms: Long): String {
    val sec = (ms / 1000).coerceAtLeast(0)
    return "${sec / 60}:${(sec % 60).toString().padStart(2, '0')}"
}

private fun lum(c: Color): Float = c.red * 0.299f + c.green * 0.587f + c.blue * 0.114f

private fun brandStripBackground(t: TapeTheme): Color = when (t.layout) {
    TapeLayout.STUDIO -> Color(0xFFF4F1E8)
    TapeLayout.MAJOR84 -> t.shellHi
    else -> lerp(t.shellHi, t.bevelHi, 0.12f)
}

private fun ensureContrastOn(fg: Color, bg: Color, minDelta: Float): Color {
    val bgL = lum(bg)
    if (abs(lum(fg) - bgL) >= minDelta) return fg
    val target = if (bgL > 0.5f) Color.Black else Color.White
    var mix = 0.12f
    while (mix <= 1f) {
        val c = lerp(fg, target, mix)
        if (abs(lum(c) - bgL) >= minDelta) return c
        mix += 0.12f
    }
    return target
}

// ── Composable: Authentic Real Cassette View ──
@Composable
fun RealCassetteView(
    client: HioneCastClient,
    state: HioneCastClient.ClientState,
    rms: Float,
    selectedThemeIndex: Int,
    onCycleTheme: () -> Unit,
    onCommand: (String) -> Unit,
    onBackToHits: () -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = REAL_TAPE_THEMES[selectedThemeIndex % REAL_TAPE_THEMES.size]
    val grade = remember(state.source.bytesPerSample, state.source.sampleRate) {
        computeTapeGrade(state.source.bytesPerSample * 8, state.source.sampleRate)
    }

    // Key light traveling across the cassette
    val lightTransition = rememberInfiniteTransition(label = "tapeLight")
    val light by lightTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 9000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "lightSweep"
    )

    // Realistic Tape Physics Simulation
    var reelAngleL by remember { mutableFloatStateOf(0f) }
    var reelAngleR by remember { mutableFloatStateOf(0f) }
    var tapeLinearDist by remember { mutableFloatStateOf(0f) }
    var transport by remember { mutableFloatStateOf(if (state.isPlaying) 1f else 0f) }

    fun calcPackR(fill: Float) = sqrt(PACK_MIN_R * PACK_MIN_R + (PACK_MAX_R * PACK_MAX_R - PACK_MIN_R * PACK_MIN_R) * fill.coerceIn(0f, 1f))

    val isPlaying = state.isPlaying
    val dur = state.durationMs
    val pos = state.positionMs
    val progress = if (dur > 0L) (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f) else 0f

    LaunchedEffect(isPlaying) {
        while (isPlaying || transport > 0.001f) {
            transport = if (isPlaying) {
                (transport + 0.085f).coerceAtMost(1f)
            } else {
                (transport * 0.90f - 0.004f).coerceAtLeast(0f)
            }

            val leftR = calcPackR(1f - progress)
            val rightR = calcPackR(progress)

            val tSec = System.nanoTime() / 1_000_000_000.0
            val wander = 1f + grade.wear * (
                0.020f * sin(tSec * 2.0 * Math.PI * 0.5).toFloat() +
                0.007f * sin(tSec * 2.0 * Math.PI * 12.3).toFloat()
            )
            val speed = 47.6f * transport * wander
            val dAngleL = (speed / leftR) * 0.032f * 57.2958f
            val dAngleR = (speed / rightR) * 0.032f * 57.2958f
            reelAngleL = (reelAngleL - dAngleL + 360f) % 360f
            reelAngleR = (reelAngleR - dAngleR + 360f) % 360f
            tapeLinearDist = (tapeLinearDist + speed * 0.032f + 100f) % 100f

            delay(32)
        }
        transport = 0f
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF03070C))
            .padding(horizontal = 40.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // ── CASSETTE STAGE ──
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            val maxH = maxHeight.value * 0.88f
            val maxW = maxWidth.value * 0.88f
            val mmScale = minOf(maxW / SHELL_W, maxH / SHELL_H)
            val cassetteWidth = (SHELL_W * mmScale).dp
            val cassetteHeight = (SHELL_H * mmScale).dp

            Box(
                modifier = Modifier.size(cassetteWidth, cassetteHeight),
                contentAlignment = Alignment.Center
            ) {
                // 1. Studio Backdrop lighting
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawStudioBackdrop(theme, light, grade)
                }

                // 2. Contact Shadow Canvas
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawContactShadowLandscape(light, grade)
                }

                // 3. ProjectM Visualizer Stage visible through transparent shell & window
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape((SHELL_R * mmScale).dp))
                ) {
                    if (ProjectMNative.available) {
                        ProjectMVisualizerView(
                            client = client,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                // 4. Cassette Base Canvas (Walls, specular sweep, plastic material texture, bottom plate)
                Canvas(modifier = Modifier.fillMaxSize()) {
                    withTvCassetteTransform {
                        drawCassetteBaseMm(theme, light)
                    }
                }

                // 5. Cassette Mechanism Canvas (Magnetic ribbon, guide rollers, Archimedean reels)
                Canvas(modifier = Modifier.fillMaxSize()) {
                    withTvCassetteTransform {
                        drawCassetteMechanismMm(progress, reelAngleL, reelAngleR, tapeLinearDist, theme, grade)
                    }
                }

                // 6. Cassette Overlay Canvas (Window frame, glass streaks, digital counter, screws, wear)
                val counterText = if (dur > 0L) "${fmt(pos)}/${fmt(dur)}" else fmt(pos)
                Canvas(modifier = Modifier.fillMaxSize()) {
                    withTvCassetteTransform {
                        drawCassetteOverlayMm(theme, counterText, light, grade)
                    }
                }

                // 7. Bespoke Tape Branding
                BespokeTapeBranding(
                    theme = theme,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = (mmScale * 5.6f).dp)
                )

                // 8. Photorealistic Masking Tape Strip with torn deckled ends & handwriting
                PhotorealisticMaskingTapeLabel(
                    title = state.title.ifBlank { "Untitled Track" },
                    artist = state.artist.ifBlank { "Unknown Artist" },
                    album = state.album,
                    seedId = (state.title.hashCode().toLong() and 0xFFFFFFFFL),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = (mmScale * 11.5f).dp)
                        .width((mmScale * 74f).dp)
                        .height((mmScale * 14.0f).dp)
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── GLASS TRANSPORT BAR (FOCUSABLE TV REMOTE CONTROLS) ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xCC050E11))
                .border(1.dp, HioneCardBorder, RoundedCornerShape(16.dp))
                .padding(horizontal = 24.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left Track Metadata & Format Grade Badge
            Column {
                Text(
                    text = state.title.ifBlank { "Cassette Deck Active" },
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${grade.typeLabel} · ${grade.detail} · ${theme.name}",
                        color = theme.accent,
                        fontSize = 12.sp,
                        fontFamily = OrbitronFont,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (dur > 0L) {
                        Text(
                            text = "  ·  ${fmt(pos)} / ${fmt(dur)}",
                            color = HioneMuted,
                            fontSize = 12.sp,
                            fontFamily = OrbitronFont,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Center & Right Focusable Remote Navigation Buttons
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                TvFocusableButton(
                    onClick = { onCommand("prev") },
                    icon = Icons.Default.SkipPrevious,
                    label = "Prev",
                    height = 42.dp
                )
                TvFocusableButton(
                    onClick = { onCommand("rewind") },
                    icon = Icons.Default.FastRewind,
                    label = "-15s",
                    height = 42.dp
                )
                TvFocusableButton(
                    onClick = { onCommand("playpause") },
                    icon = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    label = if (state.isPlaying) "Pause" else "Play",
                    primary = true,
                    height = 42.dp
                )
                TvFocusableButton(
                    onClick = { onCommand("forward") },
                    icon = Icons.Default.FastForward,
                    label = "+15s",
                    height = 42.dp
                )
                TvFocusableButton(
                    onClick = { onCommand("next") },
                    icon = Icons.Default.SkipNext,
                    label = "Next",
                    height = 42.dp
                )
                TvFocusableButton(
                    onClick = onCycleTheme,
                    icon = Icons.Default.Palette,
                    label = "Shell: ${theme.name}",
                    height = 42.dp,
                    fontSize = 12.sp
                )
                TvFocusableButton(
                    onClick = onBackToHits,
                    icon = Icons.Default.ArrowBack,
                    label = "Hits View",
                    height = 42.dp,
                    fontSize = 12.sp
                )
            }
        }
    }
}

// ── Transform & Drawing Implementations ──
private fun DrawScope.drawStudioBackdrop(t: TapeTheme, light: Float, g: TapeGrade) {
    val q = g.q
    val w = size.width
    val h = size.height
    val keyC = Offset(w * (0.30f + 0.10f * light), h * 0.30f)
    drawRect(
        Brush.radialGradient(
            listOf(
                lerp(Color(0xFF0B0F12), t.accent, 0.05f + 0.11f * q).copy(alpha = 0.85f),
                lerp(Color(0xFF0B0C0E), t.accent, 0.02f + 0.03f * q).copy(alpha = 0.92f),
                Color.Black
            ),
            center = keyC,
            radius = maxOf(w, h) * (1.25f - 0.38f * q)
        )
    )
    drawRect(
        Brush.radialGradient(
            listOf(lerp(Color.Transparent, t.bevelHi, 0.07f), Color.Transparent),
            center = Offset(w * 0.86f, h * 0.80f),
            radius = maxOf(w, h) * 0.55f
        )
    )
    drawRect(
        Brush.radialGradient(
            listOf(Color.Transparent, Color.Transparent, Color.Black.copy(alpha = 0.25f + 0.22f * q), Color.Black.copy(alpha = 0.55f + 0.25f * q)),
            center = Offset(w / 2f, h / 2f),
            radius = maxOf(w, h) * 0.72f
        )
    )
}

private fun DrawScope.withTvCassetteTransform(block: DrawScope.() -> Unit) {
    val s = size.width / SHELL_W
    translate(size.width / 2f, size.height / 2f) {
        scale(s, s, Offset.Zero) {
            translate(-SHELL_W / 2f, -SHELL_H / 2f) {
                block()
            }
        }
    }
}

private fun DrawScope.drawContactShadowLandscape(light: Float, g: TapeGrade) {
    val q = g.q
    val s = size.width / SHELL_W
    val halfW = size.width / 2f
    val halfH = size.height / 2f
    val cx = center.x
    val cy = center.y
    val offX = (6f + 5f * light) * s * 0.35f
    val offY = 9f * s * 0.35f

    for (i in 7 downTo 1) {
        val grow = i * (4.0f - 1.4f * q) * s * 0.35f
        drawRoundRect(
            Color.Black.copy(alpha = 0.045f + 0.030f * q),
            topLeft = Offset(cx - halfW - grow + offX, cy - halfH - grow + offY),
            size = Size((halfW + grow) * 2f, (halfH + grow) * 2f),
            cornerRadius = CornerRadius(SHELL_R * s + grow, SHELL_R * s + grow)
        )
    }
    for (i in 3 downTo 1) {
        val grow = i * 0.9f * s * 0.35f
        drawRoundRect(
            Color.Black.copy(alpha = 0.13f + 0.09f * q),
            topLeft = Offset(cx - halfW - grow, cy - halfH - grow + grow * 0.5f),
            size = Size((halfW + grow) * 2f, (halfH + grow) * 2f),
            cornerRadius = CornerRadius(SHELL_R * s + grow, SHELL_R * s + grow)
        )
    }
}

private fun DrawScope.drawCassetteBaseMm(t: TapeTheme, light: Float) {
    val wall = Path().apply {
        fillType = PathFillType.EvenOdd
        addRoundRect(RoundRect(0f, 0f, SHELL_W, SHELL_H, CornerRadius(SHELL_R, SHELL_R)))
        addRoundRect(RoundRect(WALL, WALL, SHELL_W - WALL, SHELL_H - WALL, CornerRadius(SHELL_R, SHELL_R)))
    }
    drawPath(wall, Brush.linearGradient(listOf(t.shellHi.copy(alpha = t.wallAlpha), t.shellLo.copy(alpha = t.wallAlpha)), Offset.Zero, Offset(SHELL_W, SHELL_H)))
    drawRoundRect(
        Brush.linearGradient(listOf(t.shellHi.copy(alpha = t.cavityAlpha * 0.85f), t.shellLo.copy(alpha = t.cavityAlpha)), Offset.Zero, Offset(SHELL_W, SHELL_H)),
        topLeft = Offset(WALL, WALL), size = Size(SHELL_W - 2 * WALL, SHELL_H - 2 * WALL), cornerRadius = CornerRadius(SHELL_R, SHELL_R)
    )
    drawRoundRect(Color(0x3D000000), topLeft = Offset(WALL, WALL), size = Size(SHELL_W - 2 * WALL, SHELL_H - 2 * WALL), cornerRadius = CornerRadius(SHELL_R, SHELL_R), style = Stroke(1.1f))

    var ry = 3.4f
    while (ry < 17.2f) { drawLine(t.engrave.copy(alpha = 0.35f), Offset(3f, ry), Offset(SHELL_W - 3f, ry), 0.14f); ry += 0.55f }
    var gx = 3.2f
    while (gx < 7.4f) {
        drawLine(t.engrave, Offset(gx, 20f), Offset(gx, 44f), 0.22f)
        drawLine(t.engrave, Offset(SHELL_W - gx, 20f), Offset(SHELL_W - gx, 44f), 0.22f)
        gx += 0.65f
    }

    val sweepX = -26f + light * 96f
    val sheen = Path().apply {
        moveTo(sweepX + 12f, 0f); lineTo(sweepX + 30f, 0f)
        lineTo(sweepX + 6f, SHELL_H); lineTo(sweepX - 12f, SHELL_H); close()
    }
    drawPath(sheen, Color(0x12FFFFFF))
    val sheen2 = Path().apply {
        moveTo(sweepX + 34f, 0f); lineTo(sweepX + 40f, 0f)
        lineTo(sweepX + 16f, SHELL_H); lineTo(sweepX + 10f, SHELL_H); close()
    }
    drawPath(sheen2, Color(0x0AFFFFFF))

    // Plastic materials
    when (t.plasticType) {
        PlasticType.CLEAR_POLYCARBONATE -> {
            val prismSheen = Path().apply {
                moveTo(sweepX + 14f, 0f); lineTo(sweepX + 32f, 0f)
                lineTo(sweepX + 8f, SHELL_H); lineTo(sweepX - 10f, SHELL_H); close()
            }
            drawPath(prismSheen, Color(0x28FFFFFF))
            drawRoundRect(Color(0xFF9FC6C2).copy(alpha = 0.055f), topLeft = Offset(WALL, WALL), size = Size(SHELL_W - 2 * WALL, SHELL_H - 2 * WALL), cornerRadius = CornerRadius(SHELL_R, SHELL_R))
            drawShellAccentGraphics(t, strength = 1f)
        }
        PlasticType.SMOKED_ACRYLIC -> {
            drawRoundRect(
                Brush.radialGradient(listOf(Color(0x3508080C), Color(0x65101018)), center = Offset(SHELL_W / 2f, SHELL_H / 2f), radius = SHELL_W * 0.6f),
                topLeft = Offset(WALL, WALL), size = Size(SHELL_W - 2 * WALL, SHELL_H - 2 * WALL), cornerRadius = CornerRadius(SHELL_R, SHELL_R)
            )
            drawShellAccentGraphics(t, strength = 0.6f)
        }
        PlasticType.MATTE_COMPOSITE -> {
            var px = WALL + 1.2f
            while (px < SHELL_W - WALL - 1.2f) {
                var py = WALL + 1.2f
                while (py < SHELL_H - WALL - 1.2f) {
                    val hash = ((px * 73.1f + py * 91.7f).toInt() % 11)
                    if (hash < 3) drawCircle(Color(0x22FFFFFF), 0.16f, Offset(px + (hash * 0.1f), py))
                    else if (hash > 7) drawCircle(Color(0x33000000), 0.18f, Offset(px - (hash * 0.08f), py))
                    py += 2.2f
                }
                px += 2.2f
            }
        }
        PlasticType.SATIN_POLYSTYRENE -> {
            drawRoundRect(
                Brush.verticalGradient(listOf(Color(0x22FFFFFF), Color(0x10FFFFFF), Color(0x18000000)), startY = WALL, endY = SHELL_H - WALL),
                topLeft = Offset(WALL, WALL), size = Size(SHELL_W - 2 * WALL, SHELL_H - 2 * WALL), cornerRadius = CornerRadius(SHELL_R, SHELL_R)
            )
        }
        PlasticType.METALLIC_FLAKE -> {
            val sheenMetallic = Path().apply {
                moveTo(sweepX + 20f, 0f); lineTo(sweepX + 45f, 0f)
                lineTo(sweepX + 15f, SHELL_H); lineTo(sweepX - 10f, SHELL_H); close()
            }
            drawPath(sheenMetallic, Color(0x30FFEAA0))
        }
        PlasticType.PHOSPHOR_GLOW -> {
            drawCircle(Brush.radialGradient(listOf(t.accent.copy(alpha = 0.35f), Color.Transparent), center = Offset(HUB_L_X, HUB_Y), radius = HUB_R * 2.2f), HUB_R * 2.2f, Offset(HUB_L_X, HUB_Y))
            drawCircle(Brush.radialGradient(listOf(t.accent.copy(alpha = 0.35f), Color.Transparent), center = Offset(HUB_R_X, HUB_Y), radius = HUB_R * 2.2f), HUB_R * 2.2f, Offset(HUB_R_X, HUB_Y))
        }
    }

    // Bottom plate (mouth assembly)
    val plate = Path().apply {
        moveTo(17.0f, SHELL_H); lineTo(19.8f, 50.6f); lineTo(81.8f, 50.6f); lineTo(84.6f, SHELL_H); close()
    }
    drawPath(plate, Color(0x24000000))
    drawPath(plate, t.engrave, style = Stroke(0.28f))
    drawRoundRect(Color(0xCC0A0A0A), topLeft = Offset(45.3f, 59.6f), size = Size(11.0f, SHELL_H - 59.6f), cornerRadius = CornerRadius(0.8f, 0.8f))
    drawRoundRect(Color(0xCC0A0A0A), topLeft = Offset(34.6f, 60.2f), size = Size(8.5f, SHELL_H - 60.2f), cornerRadius = CornerRadius(0.8f, 0.8f))
    drawRoundRect(Color(0xCC0A0A0A), topLeft = Offset(58.5f, 60.2f), size = Size(8.5f, SHELL_H - 60.2f), cornerRadius = CornerRadius(0.8f, 0.8f))
    drawCapstanHole(HUB_L_X, 60.6f, t)
    drawCapstanHole(HUB_R_X, 60.6f, t)
}

private fun DrawScope.drawCassetteMechanismMm(
    progress: Float, angleL: Float, angleR: Float, tapeLinearDist: Float, t: TapeTheme, g: TapeGrade
) {
    clipPath(mechanismVisibilityPath(t)) {
        drawMagneticTapeTransport(progress, angleL, angleR, tapeLinearDist, t, g)
        drawLine(Color(0xFFB0B6BA), Offset(42.8f, 57.1f), Offset(58.8f, 57.1f), 0.5f)
        drawRoundRect(Color(0xFFB7A27A), topLeft = Offset(47.6f, 57.5f), size = Size(6.4f, 1.8f), cornerRadius = CornerRadius(0.5f, 0.5f))
    }
}

private fun DrawScope.drawCassetteOverlayMm(t: TapeTheme, counterText: String, light: Float, g: TapeGrade) {
    val winTL = Offset(50.8f - WINDOW_W / 2f, HUB_Y - WINDOW_H / 2f)
    drawRoundRect(Color(0x14FFFFFF), topLeft = winTL, size = Size(WINDOW_W, WINDOW_H), cornerRadius = CornerRadius(2.5f, 2.5f))
    drawWindowCounter(t, counterText, winTL)

    clipPath(Path().apply { addRoundRect(RoundRect(winTL.x, winTL.y, winTL.x + WINDOW_W, winTL.y + WINDOW_H, CornerRadius(2.5f, 2.5f))) }) {
        val gx = winTL.x + light * (WINDOW_W - 6f)
        drawLine(Color(0x2EFFFFFF), Offset(gx + 4f, winTL.y + WINDOW_H + 2f), Offset(gx + 14f, winTL.y - 2f), 1.1f)
        drawLine(Color(0x1AFFFFFF), Offset(gx + 9f, winTL.y + WINDOW_H + 2f), Offset(gx + 21f, winTL.y - 2f), 2.6f)
    }
    drawRoundRect(Color(0x88000000), topLeft = Offset(winTL.x - 0.5f, winTL.y - 0.5f), size = Size(WINDOW_W + 1f, WINDOW_H + 1f), cornerRadius = CornerRadius(2.8f, 2.8f), style = Stroke(0.9f))
    drawRoundRect(t.bevelHi.copy(alpha = 0.55f), topLeft = winTL, size = Size(WINDOW_W, WINDOW_H), cornerRadius = CornerRadius(2.5f, 2.5f), style = Stroke(0.3f))

    drawRoundRect(t.bevelHi.copy(alpha = 0.9f), topLeft = Offset.Zero, size = Size(SHELL_W, SHELL_H), cornerRadius = CornerRadius(SHELL_R, SHELL_R), style = Stroke(0.45f))
    drawRoundRect(t.bevelLo.copy(alpha = 0.7f), topLeft = Offset(0.55f, 0.55f), size = Size(SHELL_W - 1.1f, SHELL_H - 1.1f), cornerRadius = CornerRadius(SHELL_R, SHELL_R), style = Stroke(0.28f))
    for ((sx, sy) in listOf(4.3f to 4.3f, SHELL_W - 4.3f to 4.3f, 4.3f to 59.2f, SHELL_W - 4.3f to 59.2f)) {
        if (t.welded) drawMeltWeld(sx, sy, t) else drawScrew(sx, sy, t)
    }

    when (t.layout) {
        TapeLayout.CLEAR -> Unit
        TapeLayout.STUDIO -> {
            drawRoundRect(Color(0xF2F4F1E8), topLeft = Offset(2.8f, 2.8f), size = Size(SHELL_W - 5.6f, 13.6f), cornerRadius = CornerRadius(1.2f, 1.2f))
            drawRoundRect(Color(0xFF1A1A1E), topLeft = Offset(3.4f, 3.4f), size = Size(SHELL_W - 6.8f, 12.4f), cornerRadius = CornerRadius(1f, 1f), style = Stroke(0.28f))
            drawPath(Path().apply { moveTo(58f, 3.4f); lineTo(63.5f, 3.4f); lineTo(69.5f, 15.8f); lineTo(64f, 15.8f); close() }, t.accent)
            drawCircle(t.accent, 2.5f, Offset(7.4f, 22.4f))
            mmText("A", 7.4f, 23.9f, 4.2f, Color(0xFF14141B), center = true)
        }
        TapeLayout.MAJOR84 -> {
            drawLine(t.ink.copy(alpha = 0.85f), Offset(3f, 1.7f), Offset(SHELL_W - 3f, 1.7f), 0.3f)
            drawLine(t.ink.copy(alpha = 0.85f), Offset(3f, 14.6f), Offset(SHELL_W - 3f, 14.6f), 0.3f)
            drawRoundRect(t.accent, topLeft = Offset(91.6f, 3.6f), size = Size(6f, 6f), cornerRadius = CornerRadius(0.8f, 0.8f))
            mmText("M", 94.6f, 8.2f, 4.2f, Color(0xFF141418), center = true)
        }
        TapeLayout.NEON -> {
            drawRoundRect(t.accent, topLeft = Offset(93.2f, 3f), size = Size(4.6f, 4.6f), cornerRadius = CornerRadius(0.6f, 0.6f))
            mmText("A", 95.5f, 6.7f, 3.4f, Color(0xFF101014), center = true)
        }
        TapeLayout.MERCH -> {
            drawCircle(t.ink, HUB_R + 1.6f, Offset(HUB_L_X, HUB_Y), style = Stroke(0.4f))
            drawCircle(t.ink, HUB_R + 1.6f, Offset(HUB_R_X, HUB_Y), style = Stroke(0.4f))
        }
    }

    if (g.isKnown) drawGradeStamp(t, g)
    drawShellWear(t, g)
}

private fun mechanismVisibilityPath(t: TapeTheme): Path {
    if (t.plasticType == PlasticType.CLEAR_POLYCARBONATE || t.plasticType == PlasticType.SMOKED_ACRYLIC) {
        return Path().apply {
            addRoundRect(RoundRect(WALL, WALL, SHELL_W - WALL, SHELL_H - WALL, CornerRadius(SHELL_R, SHELL_R)))
        }
    }
    val winTL = Offset(50.8f - WINDOW_W / 2f, HUB_Y - WINDOW_H / 2f)
    return Path().apply {
        addRoundRect(RoundRect(winTL.x, winTL.y, winTL.x + WINDOW_W, winTL.y + WINDOW_H, CornerRadius(2.5f, 2.5f)))
        addRoundRect(RoundRect(45.3f, 59.6f, 56.3f, SHELL_H, CornerRadius(0.8f, 0.8f)))
        addRoundRect(RoundRect(34.6f, 60.2f, 43.1f, SHELL_H, CornerRadius(0.8f, 0.8f)))
        addRoundRect(RoundRect(58.5f, 60.2f, 67.0f, SHELL_H, CornerRadius(0.8f, 0.8f)))
        addOval(Rect(HUB_L_X - CAPSTAN_R, 60.6f - CAPSTAN_R, HUB_L_X + CAPSTAN_R, 60.6f + CAPSTAN_R))
        addOval(Rect(HUB_R_X - CAPSTAN_R, 60.6f - CAPSTAN_R, HUB_R_X + CAPSTAN_R, 60.6f + CAPSTAN_R))
    }
}

private fun DrawScope.drawMagneticTapeTransport(
    progress: Float, angleL: Float, angleR: Float, tapeLinearDist: Float, t: TapeTheme, g: TapeGrade
) {
    fun calcPackRadius(fill: Float): Float {
        val f = fill.coerceIn(0f, 1f)
        return sqrt(PACK_MIN_R * PACK_MIN_R + (PACK_MAX_R * PACK_MAX_R - PACK_MIN_R * PACK_MIN_R) * f)
    }
    val leftR = calcPackRadius(1f - progress)
    val rightR = calcPackRadius(progress)

    val gL = Offset(GUIDE_L_X, GUIDE_Y)
    val gR = Offset(GUIDE_R_X, GUIDE_Y)
    val gL_bot = Offset(GUIDE_L_X, TAPE_RUN_Y)
    val gR_bot = Offset(GUIDE_R_X, TAPE_RUN_Y)

    val dxL = (GUIDE_L_X - HUB_L_X).toDouble()
    val dyL = (GUIDE_Y - HUB_Y).toDouble()
    val distL = sqrt(dxL * dxL + dyL * dyL)
    val uLx = dxL / distL; val uLy = dyL / distL
    val nLx = -uLy; val nLy = uLx
    val sinGammaL = ((leftR - GUIDE_ROLLER_R).toDouble() / distL).coerceIn(-1.0, 1.0)
    val cosGammaL = sqrt(1.0 - sinGammaL * sinGammaL)
    val tanNLx = cosGammaL * nLx + sinGammaL * uLx
    val tanNLy = cosGammaL * nLy + sinGammaL * uLy

    val pL = Offset((HUB_L_X + leftR * tanNLx).toFloat(), (HUB_Y + leftR * tanNLy).toFloat())
    val gL_tan = Offset((GUIDE_L_X + GUIDE_ROLLER_R * tanNLx).toFloat(), (GUIDE_Y + GUIDE_ROLLER_R * tanNLy).toFloat())

    val dxR = (GUIDE_R_X - HUB_R_X).toDouble()
    val dyR = (GUIDE_Y - HUB_Y).toDouble()
    val distR = sqrt(dxR * dxR + dyR * dyR)
    val uRx = dxR / distR; val uRy = dyR / distR
    val nRx = uRy; val nRy = -uRx
    val sinGammaR = ((rightR - GUIDE_ROLLER_R).toDouble() / distR).coerceIn(-1.0, 1.0)
    val cosGammaR = sqrt(1.0 - sinGammaR * sinGammaR)
    val tanNRx = cosGammaR * nRx + sinGammaR * uRx
    val tanNRy = cosGammaR * nRy + sinGammaR * uRy

    val pR = Offset((HUB_R_X + rightR * tanNRx).toFloat(), (HUB_Y + rightR * tanNRy).toFloat())
    val gR_tan = Offset((GUIDE_R_X + GUIDE_ROLLER_R * tanNRx).toFloat(), (GUIDE_Y + GUIDE_ROLLER_R * tanNRy).toFloat())

    for (guide in listOf(gL, gR)) {
        drawCircle(Color(0x44000000), GUIDE_ROLLER_R + 0.4f, Offset(guide.x + 0.25f, guide.y + 0.35f))
        drawCircle(Brush.radialGradient(listOf(t.hubHi, t.hubLo), center = Offset(guide.x - 0.8f, guide.y - 0.8f), radius = GUIDE_ROLLER_R * 1.6f), GUIDE_ROLLER_R, guide)
        drawCircle(t.hubLo, GUIDE_ROLLER_R, guide, style = Stroke(0.25f))
    }

    val reelDive = 1.8f
    val pLu = Offset((HUB_L_X + (leftR - reelDive).coerceAtLeast(2f) * tanNLx).toFloat(), (HUB_Y + (leftR - reelDive).coerceAtLeast(2f) * tanNLy).toFloat())
    val pRu = Offset((HUB_R_X + (rightR - reelDive).coerceAtLeast(2f) * tanNRx).toFloat(), (HUB_Y + (rightR - reelDive).coerceAtLeast(2f) * tanNRy).toFloat())
    val ribbonPath = Path().apply {
        moveTo(pLu.x, pLu.y)
        lineTo(gL_tan.x, gL_tan.y)
        quadraticBezierTo(GUIDE_L_X - GUIDE_ROLLER_R, TAPE_RUN_Y, gL_bot.x, gL_bot.y)
        lineTo(gR_bot.x, gR_bot.y)
        quadraticBezierTo(GUIDE_R_X + GUIDE_ROLLER_R, TAPE_RUN_Y, gR_tan.x, gR_tan.y)
        lineTo(pRu.x, pRu.y)
    }

    drawPath(ribbonPath, Color(0x8A000000), style = Stroke(1.6f))
    val ribbonEdgeTone = Color(0xFF6B4426)
    val ribbonMidTone = Color(0xFF7A4F2C)
    val ribbonBrush = Brush.linearGradient(
        colorStops = arrayOf(
            0f to ribbonEdgeTone.copy(alpha = 0f),
            0.05f to ribbonEdgeTone,
            0.5f to ribbonMidTone,
            0.95f to ribbonEdgeTone,
            1f to ribbonEdgeTone.copy(alpha = 0f)
        ),
        start = pL, end = pR
    )
    drawPath(ribbonPath, ribbonBrush, style = Stroke(1.15f))

    for (guide in listOf(gL, gR)) {
        drawCircle(Color(0x33000000), 1.1f, guide)
        drawCircle(Color(0xFF1A1510), 0.75f, guide)
        drawCircle(Color(0x66FFFFFF), 0.35f, Offset(guide.x - 0.2f, guide.y - 0.2f))
    }

    drawReel(HUB_L_X, HUB_Y, leftR, angleL, t, g)
    drawReel(HUB_R_X, HUB_Y, rightR, angleR, t, g)
}

private fun DrawScope.drawReel(cx: Float, cy: Float, tapeR: Float, angle: Float, t: TapeTheme, g: TapeGrade) {
    val c = Offset(cx, cy)
    drawCircle(Color(0x66000000), tapeR + 0.6f, Offset(cx + 0.45f, cy + 0.65f))

    if (tapeR > HUB_R) {
        drawCircle(
            Brush.radialGradient(listOf(g.oxideHi, g.oxideLo, lerp(g.oxideLo, Color.Black, 0.55f)), center = Offset(cx - tapeR * 0.25f, cy - tapeR * 0.25f), radius = tapeR * 1.35f),
            tapeR, c
        )

        val packDepth = tapeR - HUB_R
        if (packDepth > 0.4f) {
            val bucket = Math.round(tapeR * 20f)
            val geo = reelGeometryCache.getOrPut(cx to bucket) { buildReelGeometry(cx, cy, tapeR, packDepth) }
            rotate(angle, c) {
                drawPath(geo.spiral1, Color(0x38E0D4C0), style = Stroke(0.18f))
                drawPath(geo.spiral2, Color(0x44000000), style = Stroke(0.16f))
                for (f in geo.flecks) {
                    drawLine(Color(0xFFFFECC8).copy(alpha = f.alpha), f.start, f.end, f.width)
                }
            }
        }
    }

    rotate(angle, c) {
        drawCircle(
            Brush.radialGradient(listOf(t.hubHi, t.hubLo), center = Offset(cx - HUB_R * 0.35f, cy - HUB_R * 0.35f), radius = HUB_R * 1.5f),
            HUB_R, c
        )
        drawCircle(t.hubLo, HUB_R, c, style = Stroke(0.3f))

        for (k in 0 until 14) {
            val a = Math.toRadians(k * (360.0 / 14))
            val hc = Offset(cx + (8.1f * cos(a)).toFloat(), cy + (8.1f * sin(a)).toFloat())
            drawCircle(Color(0x40000000), 0.72f, hc + Offset(0.1f, 0.1f))
            drawCircle(t.holes, 0.62f, hc)
        }

        drawCircle(Color(0xFF0D0A08), SPINDLE_R + 1.2f, c)
        drawCircle(Color(0xFFE8E5DC), SPINDLE_R, c)
        drawCircle(Color(0x40000000), SPINDLE_R, c, style = Stroke(0.3f))
        for (tooth in 0 until 6) {
            val a = Math.toRadians((tooth * 60).toDouble())
            val dir = Offset(cos(a).toFloat(), sin(a).toFloat())
            drawLine(Color(0xFF0D0A08), c + dir * (SPINDLE_R - 1.6f), c + dir * SPINDLE_R, strokeWidth = 1.6f)
        }
    }
}

private fun DrawScope.drawWindowCounter(t: TapeTheme, text: String, winTL: Offset) {
    val stripH = 4.0f
    val stripTL = Offset(winTL.x + 3f, winTL.y + WINDOW_H - stripH - 0.7f)
    val stripSize = Size(WINDOW_W - 6f, stripH)
    drawRoundRect(Color(0xD90A0A0A), topLeft = stripTL, size = stripSize, cornerRadius = CornerRadius(0.8f, 0.8f))
    drawRoundRect(Color(0x4D000000), topLeft = stripTL, size = stripSize, cornerRadius = CornerRadius(0.8f, 0.8f), style = Stroke(0.18f))
    mmText(
        text,
        stripTL.x + stripSize.width / 2f,
        stripTL.y + stripSize.height * 0.74f,
        2.5f,
        t.accent,
        center = true
    )
}

private fun DrawScope.drawGradeStamp(t: TapeTheme, g: TapeGrade) {
    val x = 5.5f
    val y = 37.4f
    val slabW = 15.5f
    val slabH = 4.6f
    val slab = lerp(lerp(t.ink, Color(0xFF6A6A6A), 0.55f), t.accent, g.q)
    drawRoundRect(
        slab.copy(alpha = 0.62f + 0.33f * g.q),
        topLeft = Offset(x, y), size = Size(slabW, slabH), cornerRadius = CornerRadius(0.7f, 0.7f)
    )
    val slabLum = slab.red * 0.299f + slab.green * 0.587f + slab.blue * 0.114f
    mmText(g.typeLabel, x + slabW / 2f, y + 3.4f, 3.2f, if (slabLum > 0.5f) Color(0xFF101014) else Color.White, center = true)
    mmText(g.formulation, x, y + slabH + 3.1f, 1.85f, t.ink.copy(alpha = 0.88f), bold = false)
    mmText("${g.eq} · ${g.detail}", x, y + slabH + 5.5f, 1.85f, t.ink.copy(alpha = 0.72f), bold = false)
}

private fun DrawScope.drawShellWear(t: TapeTheme, g: TapeGrade) {
    val w = g.wear
    if (w < 0.04f) return
    clipPath(Path().apply { addRoundRect(RoundRect(0f, 0f, SHELL_W, SHELL_H, CornerRadius(SHELL_R, SHELL_R))) }) {
        val n = (28 * w).toInt()
        for (i in 0 until n) {
            val h1 = tapeHash(i * 1.7f, 3.1f); val h2 = tapeHash(i * 2.9f, 7.7f); val h3 = tapeHash(i * 4.3f, 11.3f)
            val sx = h1 * SHELL_W; val sy = h2 * SHELL_H; val len = 1.5f + h3 * 9f; val slant = (h3 - 0.5f) * 0.55f
            drawLine(Color.White.copy(alpha = 0.03f + 0.05f * h3 * w), Offset(sx, sy), Offset(sx + len, sy + len * slant), 0.11f)
        }
    }
}

private fun DrawScope.drawShellAccentGraphics(t: TapeTheme, strength: Float) {
    val bar = Path().apply { moveTo(4f, 2f); lineTo(46f, 2f); lineTo(22f, 40f); lineTo(-6f, 40f); close() }
    drawPath(bar, t.accent.copy(alpha = 0.34f * strength))
}

private fun DrawScope.drawCapstanHole(cx: Float, cy: Float, t: TapeTheme) {
    val c = Offset(cx, cy)
    drawCircle(Color(0xFF000000), CAPSTAN_R, c)
    drawCircle(t.bevelHi.copy(alpha = 0.75f), CAPSTAN_R, c, style = Stroke(0.35f))
}

private fun DrawScope.drawScrew(sx: Float, sy: Float, t: TapeTheme) {
    val c = Offset(sx, sy)
    drawCircle(t.engrave, 2.2f, c, style = Stroke(0.25f))
    drawCircle(Brush.radialGradient(listOf(Color(0xFFEFF2F4), Color(0xFF9AA2A8), Color(0xFF565C60)), center = Offset(sx - 0.5f, sy - 0.5f), radius = 2.1f), 1.45f, c)
    rotate(37f, c) {
        drawLine(Color(0xE0101314), Offset(sx - 1.0f, sy), Offset(sx + 1.0f, sy), 0.34f)
        drawLine(Color(0xE0101314), Offset(sx, sy - 1.0f), Offset(sx, sy + 1.0f), 0.34f)
    }
}

private fun DrawScope.drawMeltWeld(sx: Float, sy: Float, t: TapeTheme) {
    val c = Offset(sx, sy)
    drawCircle(Brush.radialGradient(listOf(t.shellLo.copy(alpha = 0.95f), t.shellHi.copy(alpha = 0.4f)), center = Offset(sx - 0.3f, sy - 0.3f), radius = 1.7f), 1.45f, c)
    drawCircle(Color(0x59000000), 1.45f, c, style = Stroke(0.2f))
}

private fun DrawScope.mmText(
    s: String, x: Float, y: Float, sizeMm: Float, color: Color,
    bold: Boolean = true, center: Boolean = false
) {
    val up = 10f
    val p = Paint().apply {
        this.color = color.toArgb(); textSize = sizeMm * up; isAntiAlias = true; isLinearText = true
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        textAlign = if (center) Paint.Align.CENTER else Paint.Align.LEFT
    }
    drawContext.canvas.nativeCanvas.apply {
        save(); translate(x, y); scale(1f / up, 1f / up); drawText(s, 0f, 0f, p); restore()
    }
}

// ── Composable: Bespoke Branding ──
@Composable
private fun BespokeTapeBranding(theme: TapeTheme, modifier: Modifier = Modifier) {
    val strip = brandStripBackground(theme)
    val brandInk = ensureContrastOn(theme.brandColor, strip, 0.42f)
    val brandSubInk = ensureContrastOn(theme.brandSubColor, strip, 0.30f)

    Column(
        modifier = modifier.wrapContentWidth(unbounded = true),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (theme.brandTexture) {
            BrandTexture.DEBOSSED_ENAMEL -> {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        theme.brandText,
                        color = Color(0xAA000000),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp,
                        fontFamily = theme.brandFont,
                        modifier = Modifier.offset(x = 0.8.dp, y = 0.8.dp)
                    )
                    Text(
                        theme.brandText,
                        color = brandInk,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp,
                        fontFamily = theme.brandFont
                    )
                }
            }
            BrandTexture.HOT_STAMP_GOLD, BrandTexture.HOT_STAMP_CHROME -> {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        theme.brandText,
                        color = Color(0x66000000),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp,
                        fontFamily = theme.brandFont,
                        modifier = Modifier.offset(x = 0.5.dp, y = 0.5.dp)
                    )
                    Text(
                        theme.brandText,
                        color = brandInk,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp,
                        fontFamily = theme.brandFont
                    )
                }
            }
            else -> {
                Text(
                    theme.brandText,
                    color = brandInk,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp,
                    fontFamily = theme.brandFont
                )
            }
        }
        Spacer(Modifier.height(1.dp))
        Text(
            theme.brandSub,
            color = brandSubInk,
            fontSize = 7.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
            fontFamily = OrbitronFont
        )
    }
}

// ── Composable: Photorealistic Masking Tape Label ──
@Composable
private fun PhotorealisticMaskingTapeLabel(
    title: String,
    artist: String,
    album: String,
    seedId: Long,
    modifier: Modifier = Modifier
) {
    val seed = remember(seedId) { kotlin.random.Random(seedId) }
    val style = remember(seedId) { MASKING_TAPE_STYLES[seed.nextInt(MASKING_TAPE_STYLES.size)] }
    val tearJitter = remember(seedId) {
        val r = kotlin.random.Random(seedId + 100)
        FloatArray(16) { r.nextFloat() * 4f - 2f }
    }
    val rightEdge = remember(seedId) { randomTornEdge(kotlin.random.Random(seedId + 300)) }
    val leftEdge = remember(seedId) { randomTornEdge(kotlin.random.Random(seedId + 400)) }
    val rotation = remember(seedId) { (seed.nextFloat() * 2.4f - 1.2f) }

    Box(
        modifier = modifier
            .rotate(rotation)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@Canvas

            val tapePath = Path().apply {
                moveTo(6f + tearJitter[0], 0f)
                lineTo(w - 6f + tearJitter[1], 0f)
                for (step in rightEdge) lineTo(w - 3f + step.depthPx, h * step.yFrac)
                lineTo(w - 6f + tearJitter[2], h)
                lineTo(6f + tearJitter[3], h)
                for (step in leftEdge.asReversed()) lineTo(3f + step.depthPx, h * step.yFrac)
                close()
            }

            drawPath(Path().apply { addPath(tapePath, Offset(1.8f, 2.5f)) }, Color(0x3B000000))

            val paperBrush = Brush.linearGradient(
                colors = listOf(style.paperBaseHi.copy(alpha = 0.95f), style.paperBaseLo.copy(alpha = 0.93f)),
                start = Offset.Zero, end = Offset(w, h)
            )
            drawPath(tapePath, paperBrush)

            var ry = 2.5f
            while (ry < h - 2f) {
                drawLine(color = style.ridgeColor, start = Offset(4f, ry), end = Offset(w - 4f, ry), strokeWidth = 0.65f)
                ry += 1.8f
            }

            drawLine(Color(0x35FFFFFF), Offset(4f, 0.8f), Offset(w - 4f, 0.8f), 0.8f)
            drawPath(tapePath, style.ridgeColor.copy(alpha = 0.35f), style = Stroke(0.5f))
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 2.dp)
        ) {
            ShrinkToFitText(
                text = title,
                color = style.penColor,
                fontFamily = MarkerFont,
                fontWeight = FontWeight.SemiBold,
                maxFontSize = 14.sp,
                minFontSize = 9.sp
            )
            if (artist.isNotBlank()) {
                ShrinkToFitText(
                    text = if (album.isNotBlank()) "$artist · $album" else artist,
                    color = style.penColor.copy(alpha = 0.85f),
                    fontFamily = MarkerFont,
                    maxFontSize = 9.5.sp,
                    minFontSize = 6.5.sp
                )
            }
        }
    }
}

@Composable
private fun ShrinkToFitText(
    text: String,
    color: Color,
    fontFamily: FontFamily,
    maxFontSize: TextUnit,
    minFontSize: TextUnit,
    fontWeight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier
) {
    var fontSize by remember(text) { mutableStateOf(maxFontSize) }
    Text(
        text = text,
        color = color,
        fontFamily = fontFamily,
        fontWeight = fontWeight,
        fontSize = fontSize,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        onTextLayout = { result ->
            if (result.hasVisualOverflow && fontSize > minFontSize) {
                fontSize = (fontSize.value - 1f).sp
            }
        },
        modifier = modifier
    )
}
