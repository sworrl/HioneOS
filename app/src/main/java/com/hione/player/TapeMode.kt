package com.hione.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import android.graphics.RuntimeShader
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Layout languages amalgamated from ~20 real branded/artist cassettes:
 * CLEAR   — clear-shell Memorex/GNR-transparent: mechanism on show.
 * STUDIO  — blank-tape engineering (TDK SA × Maxell XLII): paper strip, grade mark, bias line.
 * MAJOR84 — 80s major-label pressing (Epic/Warner/EMI): text-only strip, rules, logo block.
 * NEON    — black shell + one screaming ink (Wu-Tang/NWA logo inversion).
 * MERCH   — modern one-colour merch object (Swift/Eilish): tone-on-tone, hairline hub rings.
 */
private enum class TapeLayout { CLEAR, STUDIO, MAJOR84, NEON, MERCH }

/**
 * Bespoke physical plastic material types:
 * CLEAR_POLYCARBONATE — crystal acrylic with specular gleam and optical clarity (MEMOREX, NAKAMICHI).
 * SMOKED_ACRYLIC      — smoke-tinted dark transparent with subtle frosted diffusion (HIONE STUDIO, TDK SA-X).
 * MATTE_COMPOSITE     — dense micro-pebble stippled ABS composite with light absorption (MAXELL XLII-S, SONY METAL, NEON WU, NEON HIONE).
 * SATIN_POLYSTYRENE   — eggshell translucent molded styrene with subsurface light diffusion (MAJOR '84, BASF CHROME, DENON DX, EVERMORE, PASTEL HIONE, SAKURA).
 * METALLIC_FLAKE      — pearlescent metallic luster with micro-flake shimmer (GOLD, AMPEX 456).
 * PHOSPHOR_GLOW       — subsurface radioactive phosphor luminescence (GLOWWORM, VAPORWAVE).
 */
private enum class PlasticType {
    CLEAR_POLYCARBONATE,
    SMOKED_ACRYLIC,
    MATTE_COMPOSITE,
    SATIN_POLYSTYRENE,
    METALLIC_FLAKE,
    PHOSPHOR_GLOW
}

/**
 * Bespoke brand typography and printing texture styles:
 * DEBOSSED_ENAMEL    — Recessed into plastic with glossy colored enamel infill
 * SCREENPRINT_NEON   — Raised tactile silk-screened ink with sharp pad-print edges
 * HOT_STAMP_GOLD     — Reflective metallic 24K gold foil hot-stamped with micro-glint
 * HOT_STAMP_CHROME   — Mirror chrome foil stamp with anisotropic metallic luster
 * DOT_MATRIX_IMPRINT — Japanese industrial dot matrix ink jet printing
 * DISCO_INLINE_NEON  — Multi-line glowing neon sign typography
 * JAPANESE_KAWAII    — Bubbly pastel Japanese pop offset print
 * MINIMAL_LASER_ETCH — Precision frosted laser etching directly into acrylic
 */
private enum class BrandTexture {
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

/** Cassette theme: shell plastic + engraving + hub palette + branded layout language + bespoke material texture + brand typography. */
private data class TapeTheme(
    val name: String,
    val shellHi: Color, val shellLo: Color, val engrave: Color, val bevelHi: Color, val bevelLo: Color,
    val hubHi: Color, val hubLo: Color, val holes: Color, val ink: Color,
    val layout: TapeLayout = TapeLayout.CLEAR,
    val plasticType: PlasticType = PlasticType.CLEAR_POLYCARBONATE,
    val brandText: String = "${Mascot.musicAppName.uppercase()}",
    val brandSub: String = "STEREO CASSETTE TAPE",
    val brandFont: FontFamily = AudiowideFont,
    val brandColor: Color = Mascot.color(0xFFFFAB2E),
    val brandSubColor: Color = Mascot.color(0xFF9AECE6),
    val brandTexture: BrandTexture = BrandTexture.SCREENPRINT_NEON,
    val accent: Color = Mascot.color(0xFFFFAB2E),
    val wallAlpha: Float = 0.88f, val cavityAlpha: Float = 0.30f,
    val welded: Boolean = false,  // premium shells are screwed together; budget shells are melt-welded
    val motif39: Boolean = false  // the secret HIONE 39 shell's holographic "39" foil badge (drawHione39Badge)
)
private val TAPE_THEMES get() = listOf(
    TapeTheme("${Mascot.nameUpper} STUDIO",
        shellHi = Color(0xFF262633), shellLo = Color(0xFF14141B), engrave = Mascot.color(0x55FFAB2E),
        bevelHi = Mascot.color(0xFF9AECE6), bevelLo = Color(0xFF0B0B10),
        hubHi = Mascot.color(0xFFF5FFFE), hubLo = Mascot.color(0xFF2FA39B), holes = Mascot.color(0xFF0F3D39), ink = Mascot.color(0xFFE8FBF8),
        layout = TapeLayout.STUDIO, plasticType = PlasticType.SMOKED_ACRYLIC,
        brandText = "${Mascot.nameUpper} STUDIO 90", brandSub = "TYPE II HIGH BIAS (CrO2) · 70µs EQ",
        brandFont = AudiowideFont, brandColor = Mascot.color(0xFFFFAB2E), brandSubColor = Mascot.color(0xFF9AECE6), brandTexture = BrandTexture.DEBOSSED_ENAMEL,
        accent = Mascot.color(0xFFFFAB2E), wallAlpha = 0.62f, cavityAlpha = 0.22f),
    TapeTheme("MEMOREX",
        shellHi = Mascot.color(0xFF2A4A4E), shellLo = Mascot.color(0xFF0E2226), engrave = Mascot.color(0x55FFAB2E),
        bevelHi = Mascot.color(0xFFFFD08A), bevelLo = Mascot.color(0xFF08181A),
        hubHi = Color(0xFFFFDD3A), hubLo = Color(0xFFB38F00), holes = Color(0xFF3A2E00), ink = Mascot.color(0xFFE8FBF8),
        plasticType = PlasticType.CLEAR_POLYCARBONATE,
        brandText = "${Mascot.nameUpper} dBS-90", brandSub = "HIGH OUTPUT · LOW NOISE · WIDE DYNAMIC",
        brandFont = RighteousFont, brandColor = Color(0xFFFFD200), brandSubColor = Mascot.color(0xFFE8FBF8), brandTexture = BrandTexture.SCREENPRINT_NEON),
    TapeTheme("GOLD",
        shellHi = Color(0xFFE8C878), shellLo = Color(0xFF8A6A21), engrave = Color(0x55704E12),
        bevelHi = Color(0xFFFCEFB8), bevelLo = Color(0xFF5A410E),
        hubHi = Color(0xFFF3ECD6), hubLo = Color(0xFF9A8A5E), holes = Color(0xFF241B08), ink = Color(0xFFFDF4D8),
        plasticType = PlasticType.METALLIC_FLAKE,
        brandText = "${Mascot.nameUpper} 24K GOLD MASTER", brandSub = "AUDIOPHILE REFERENCE · SPECIAL EDITION",
        brandFont = AudiowideFont, brandColor = Color(0xFF2C1A04), brandSubColor = Color(0xFF4E320A), brandTexture = BrandTexture.HOT_STAMP_GOLD),
    TapeTheme("MAJOR '84",
        shellHi = Color(0xFFF2E9D7), shellLo = Color(0xFFD9C9A8), engrave = Color(0x334A3520),
        bevelHi = Color(0xFFFFF9EC), bevelLo = Color(0xFF8A7A56),
        hubHi = Color(0xFFFFFFFF), hubLo = Color(0xFFB9AB8C), holes = Color(0xFF3A2F1C), ink = Color(0xFF2B2B2B),
        layout = TapeLayout.MAJOR84, plasticType = PlasticType.SATIN_POLYSTYRENE,
        brandText = "${Mascot.nameUpper} RECORDS 1984", brandSub = "ORIGINAL STEREO CASSETTE · DOLBY SYSTEM",
        brandFont = RighteousFont, brandColor = Color(0xFF23160C), brandSubColor = Color(0xFF4A3520), brandTexture = BrandTexture.OFFSET_PRINT_INK,
        accent = Color(0xFFC9A227), wallAlpha = 0.96f, cavityAlpha = 0.55f),
    TapeTheme("NEON WU",
        shellHi = Color(0xFF1B1B20), shellLo = Color(0xFF0E0E12), engrave = Color(0x44FFD500),
        bevelHi = Color(0xFFFFE566), bevelLo = Color(0xFF060608),
        hubHi = Color(0xFFFFD500), hubLo = Color(0xFFB89A00), holes = Color(0xFF3A3000), ink = Color(0xFFFFD500),
        layout = TapeLayout.NEON, plasticType = PlasticType.MATTE_COMPOSITE,
        brandText = "${Mascot.nameUpper} 36 CHAMBERS", brandSub = "ENTER THE SOUND · HI-ENERGY FLUX 120µs",
        brandFont = OrbitronFont, brandColor = Color(0xFFFFD500), brandSubColor = Color(0xFFFFF066), brandTexture = BrandTexture.SCREENPRINT_NEON,
        accent = Color(0xFFFFD500), wallAlpha = 0.94f, cavityAlpha = 0.48f, welded = true),
    TapeTheme("NEON ${Mascot.nameUpper}",
        shellHi = Color(0xFF1B1B20), shellLo = Color(0xFF0E0E12), engrave = Mascot.color(0x44FFAB2E),
        bevelHi = Mascot.color(0xFF9AECE6), bevelLo = Color(0xFF060608),
        hubHi = Mascot.color(0xFFFFAB2E), hubLo = Mascot.color(0xFF1F8079), holes = Mascot.color(0xFF0A2C29), ink = Mascot.color(0xFFFFAB2E),
        layout = TapeLayout.NEON, plasticType = PlasticType.MATTE_COMPOSITE,
        brandText = "${Mascot.nameUpper} CYBER DECK", brandSub = "VOCALOID01 · DIGITAL ACCELERATION",
        brandFont = OrbitronFont, brandColor = Mascot.color(0xFFFFAB2E), brandSubColor = Mascot.color(0xFF8CF0E8), brandTexture = BrandTexture.SCREENPRINT_NEON,
        accent = Mascot.color(0xFFFFAB2E), wallAlpha = 0.94f, cavityAlpha = 0.48f, welded = true),
    TapeTheme("EVERMORE",
        shellHi = Color(0xFFB3ADA4), shellLo = Color(0xFF8E887F), engrave = Color(0x333E3A35),
        bevelHi = Color(0xFFD8D3CA), bevelLo = Color(0xFF57524B),
        hubHi = Color(0xFF9C968D), hubLo = Color(0xFF6E6961), holes = Color(0xFF3E3A35), ink = Color(0xFF3E3A35),
        layout = TapeLayout.MERCH, plasticType = PlasticType.SATIN_POLYSTYRENE,
        brandText = "hione music · tape", brandSub = "intimate acoustic series · studio b",
        brandFont = Baloo2Font, brandColor = Color(0xFF181512), brandSubColor = Color(0xFF35302B), brandTexture = BrandTexture.OFFSET_PRINT_INK,
        accent = Color(0xFF3E3A35), wallAlpha = 0.95f, cavityAlpha = 0.55f, welded = true),
    TapeTheme("PASTEL ${Mascot.nameUpper}",
        shellHi = Mascot.color(0xFFBDEEE8), shellLo = Mascot.color(0xFF8FD8D0), engrave = Mascot.color(0x330F6E66),
        bevelHi = Mascot.color(0xFFE4FAF7), bevelLo = Mascot.color(0xFF4FA69D),
        hubHi = Mascot.color(0xFF9ADFD8), hubLo = Mascot.color(0xFF63B8AF), holes = Mascot.color(0xFF0F6E66), ink = Mascot.color(0xFF0F6E66),
        layout = TapeLayout.MERCH, plasticType = PlasticType.SATIN_POLYSTYRENE,
        brandText = "${Mascot.fullNameJa} · ${Mascot.nameUpper} POP", brandSub = "LO-FI CHILL BIAS · SWEET COMPACT CASSETTE",
        brandFont = MochiyPopFont, brandColor = Mascot.color(0xFF083B36), brandSubColor = Mascot.color(0xFF0E5E56), brandTexture = BrandTexture.JAPANESE_KAWAII,
        accent = Mascot.color(0xFF0F6E66), wallAlpha = 0.93f, cavityAlpha = 0.50f, welded = true),

    // ── Deeply real: legendary decks of the golden age ──
    TapeTheme("TDK SA-X",   // the Type II reference: smoke-black precision shell, red grade slash
        shellHi = Color(0xFF2E2E34), shellLo = Color(0xFF17171B), engrave = Color(0x44D0D0D6),
        bevelHi = Color(0xFFB8B8C2), bevelLo = Color(0xFF0B0B0E),
        hubHi = Color(0xFFE8E8EC), hubLo = Color(0xFF8A8A94), holes = Color(0xFF1E1E24), ink = Color(0xFFE9E9EE),
        layout = TapeLayout.STUDIO, plasticType = PlasticType.SMOKED_ACRYLIC,
        brandText = "${Mascot.nameUpper} SA-X 90", brandSub = "HIGH BIAS 70µs EQ · SUPER AVILYN DUAL COATING",
        brandFont = OrbitronFont, brandColor = Color(0xFFF2F2F5), brandSubColor = Color(0xFFB8B8C6), brandTexture = BrandTexture.SCREENPRINT_NEON,
        accent = Color(0xFFD62828), wallAlpha = 0.58f, cavityAlpha = 0.24f),
    TapeTheme("MAXELL XLII-S",   // black + gold, the premium epitaph
        shellHi = Color(0xFF232320), shellLo = Color(0xFF121210), engrave = Color(0x55C9A227),
        bevelHi = Color(0xFFE9CD7A), bevelLo = Color(0xFF0A0A08),
        hubHi = Color(0xFF2A2A26), hubLo = Color(0xFF171714), holes = Color(0xFFC9A227), ink = Color(0xFFEFD98F),
        layout = TapeLayout.STUDIO, plasticType = PlasticType.MATTE_COMPOSITE,
        brandText = "${Mascot.nameUpper} XLII-S 90", brandSub = "BLACK MAGNETITE · HIGH RESONANCE-DAMPING MECHANISM",
        brandFont = AudiowideFont, brandColor = Color(0xFFFFD866), brandSubColor = Color(0xFFD8B84E), brandTexture = BrandTexture.HOT_STAMP_GOLD,
        accent = Color(0xFFC9A227), wallAlpha = 0.92f, cavityAlpha = 0.45f),
    TapeTheme("BASF CHROME",   // white shell, green chevron, CrO2 pedigree
        shellHi = Color(0xFFF4F4EF), shellLo = Color(0xFFD8D8CF), engrave = Color(0x33006B44),
        bevelHi = Color(0xFFFFFFFA), bevelLo = Color(0xFF8F8F85),
        hubHi = Color(0xFFFFFFFF), hubLo = Color(0xFFB9B9AF), holes = Color(0xFF00552F), ink = Color(0xFF00552F),
        layout = TapeLayout.STUDIO, plasticType = PlasticType.SATIN_POLYSTYRENE,
        brandText = "${Mascot.nameUpper} CR-E II 90", brandSub = "CHROMDIOXID EXTRA II · HIGH OUTPUT / IEC II",
        brandFont = RighteousFont, brandColor = Color(0xFF00452A), brandSubColor = Color(0xFF00633C), brandTexture = BrandTexture.OFFSET_PRINT_INK,
        accent = Color(0xFF00814F), wallAlpha = 0.96f, cavityAlpha = 0.55f),
    TapeTheme("SONY METAL",   // gunmetal Type IV, orange wedge
        shellHi = Color(0xFF4A4E54), shellLo = Color(0xFF23262B), engrave = Color(0x44FF6A00),
        bevelHi = Color(0xFFB9BFC7), bevelLo = Color(0xFF14161A),
        hubHi = Color(0xFF6A7078), hubLo = Color(0xFF3A3E44), holes = Color(0xFF16181C), ink = Color(0xFFE8ECF1),
        layout = TapeLayout.STUDIO, plasticType = PlasticType.MATTE_COMPOSITE,
        brandText = "${Mascot.nameUpper} METAL MASTER 90", brandSub = "CERAMIC GUIDE / TYPE IV (METAL) POSITION 70µs",
        brandFont = OrbitronFont, brandColor = Color(0xFFFF6600), brandSubColor = Color(0xFFFFA866), brandTexture = BrandTexture.HOT_STAMP_CHROME,
        accent = Color(0xFFFF6A00), wallAlpha = 0.93f, cavityAlpha = 0.42f),
    TapeTheme("DENON DX",   // white shell, blue bands, red tick
        shellHi = Color(0xFFF2F3F5), shellLo = Color(0xFFD4D8DE), engrave = Color(0x331F5FBF),
        bevelHi = Color(0xFFFFFFFF), bevelLo = Color(0xFF8B919B),
        hubHi = Color(0xFFFFFFFF), hubLo = Color(0xFFAAB2BD), holes = Color(0xFF16407F), ink = Color(0xFF1F5FBF),
        layout = TapeLayout.MAJOR84, plasticType = PlasticType.SATIN_POLYSTYRENE,
        brandText = "${Mascot.nameUpper} DX-7 90", brandSub = "DYNAMIC ACOUSTIC CASSETTE · HIGH FIDELITY",
        brandFont = RighteousFont, brandColor = Color(0xFF0B2F6E), brandSubColor = Color(0xFF1A4B9E), brandTexture = BrandTexture.OFFSET_PRINT_INK,
        accent = Color(0xFFCC2222), wallAlpha = 0.96f, cavityAlpha = 0.55f),
    TapeTheme("AMPEX 456",   // Grand Master studio-reel warm grey + gold
        shellHi = Color(0xFF8A8378), shellLo = Color(0xFF5C574E), engrave = Color(0x40332E26),
        bevelHi = Color(0xFFC9C2B4), bevelLo = Color(0xFF2E2B25),
        hubHi = Color(0xFFB8B0A0), hubLo = Color(0xFF7A7365), holes = Color(0xFF2A261F), ink = Color(0xFFEFE6D2),
        layout = TapeLayout.MAJOR84, plasticType = PlasticType.METALLIC_FLAKE,
        brandText = "${Mascot.nameUpper} 456 GRAND MASTER", brandSub = "PROFESSIONAL STUDIO MASTERING TAPE · HIGH COERCIVITY",
        brandFont = AudiowideFont, brandColor = Color(0xFF1C1712), brandSubColor = Color(0xFF3D3328), brandTexture = BrandTexture.HOT_STAMP_GOLD,
        accent = Color(0xFFB98A2F), wallAlpha = 0.95f, cavityAlpha = 0.52f),
    TapeTheme("NAKAMICHI",   // audiophile smoke-clear, everything on show
        shellHi = Color(0xFF3A3F42), shellLo = Color(0xFF1C1F21), engrave = Color(0x44AEB6BA),
        bevelHi = Color(0xFFCED6DA), bevelLo = Color(0xFF0E1011),
        hubHi = Color(0xFFDDE3E6), hubLo = Color(0xFF878E92), holes = Color(0xFF24282A), ink = Color(0xFFEFF4F6),
        layout = TapeLayout.CLEAR, plasticType = PlasticType.CLEAR_POLYCARBONATE,
        brandText = "${Mascot.nameUpper} ZX REFERENCE", brandSub = "DISCRETE 3-HEAD PRECISION CALIBRATION · CRYSTALLOY",
        brandFont = OrbitronFont, brandColor = Color(0xFFE8ECEF), brandSubColor = Color(0xFF90A4AE), brandTexture = BrandTexture.MINIMAL_LASER_ETCH,
        accent = Color(0xFFB0B8BC), wallAlpha = 0.42f, cavityAlpha = 0.16f),
    // ── For fun ──
    TapeTheme("SAKURA",   // cherry-blossom merch object
        shellHi = Mascot.color(0xFFF9D5E0), shellLo = Mascot.color(0xFFEFA9C2), engrave = Mascot.color(0x338E3557),
        bevelHi = Mascot.color(0xFFFFF0F5), bevelLo = Mascot.color(0xFFB86787),
        hubHi = Mascot.color(0xFFF3BCCE), hubLo = Mascot.color(0xFFCE8AA5), holes = Mascot.color(0xFF8E3557), ink = Mascot.color(0xFF8E3557),
        layout = TapeLayout.MERCH, plasticType = PlasticType.SATIN_POLYSTYRENE,
        brandText = "${Mascot.fullNameJa} · 桜音 SAKURA", brandSub = "SPRING HARMONIC EDITION · 48kHz ANALOG MASTER",
        brandFont = MochiyPopFont, brandColor = Mascot.color(0xFF5E122E), brandSubColor = Mascot.color(0xFF8C2048), brandTexture = BrandTexture.JAPANESE_KAWAII,
        accent = Mascot.color(0xFFD1477E), wallAlpha = 0.93f, cavityAlpha = 0.48f, welded = true),
    TapeTheme("VAPORWAVE",   // magenta ink on black, mall-at-midnight
        shellHi = Color(0xFF201826), shellLo = Color(0xFF100C14), engrave = Mascot.color(0x44FF3FD8),
        bevelHi = Mascot.color(0xFFFF9BE8), bevelLo = Color(0xFF080609),
        hubHi = Mascot.color(0xFFFF3FD8), hubLo = Mascot.color(0xFFA3238B), holes = Mascot.color(0xFF32082A), ink = Mascot.color(0xFFFF3FD8),
        layout = TapeLayout.NEON, plasticType = PlasticType.PHOSPHOR_GLOW,
        brandText = "ＭＩＫＵ  ＭＵＳＩＣ", brandSub = "ＡＥＳＴＨＥＴＩＣ  ＮＩＧＨＴ  ＣＡＳＳＥＴＴＥ  ８０ｓ",
        brandFont = MonotonFont, brandColor = Mascot.color(0xFFFF3FD8), brandSubColor = Mascot.color(0xFFFFA6EC), brandTexture = BrandTexture.DISCO_INLINE_NEON,
        accent = Mascot.color(0xFFFF3FD8), wallAlpha = 0.94f, cavityAlpha = 0.46f, welded = true),
    TapeTheme("GLOWWORM",   // glow-in-the-dark green (a real 80s gimmick shell)
        shellHi = Color(0xFF14201A), shellLo = Color(0xFF0A120E), engrave = Color(0x4466FF88),
        bevelHi = Color(0xFFB9FFCB), bevelLo = Color(0xFF050806),
        hubHi = Color(0xFF66FF88), hubLo = Color(0xFF2FA050), holes = Color(0xFF0C2A14), ink = Color(0xFF8CFFA8),
        layout = TapeLayout.NEON, plasticType = PlasticType.PHOSPHOR_GLOW,
        brandText = "${Mascot.nameUpper} GLOW-90 [DAT]", brandSub = "PHOSPHOR LUMINESCENT MATRIX · HIGH ENERGY FLUX",
        brandFont = DotGothicFont, brandColor = Color(0xFF66FF88), brandSubColor = Color(0xFFA3FFBA), brandTexture = BrandTexture.DOT_MATRIX_IMPRINT,
        accent = Color(0xFF66FF88), wallAlpha = 0.90f, cavityAlpha = 0.40f, welded = true),
    // ── Secret (index 18). APPEND ONLY: the saved theme is a raw ordinal, so nothing may ever be
    //    inserted above this line. Gated by HioneUnlocksReader.SECRET_TAPE_HIONE39 and left out of
    //    the picker entirely until earned (HioneUnlocksReader.SECRET_TAPE_INDICES). ──
    TapeTheme(Mascot.lore("HIONE 39", "CODA 01"),   // Hione lore only under the Hione pack. translucent mint holo shell, pink hubs; 39 = "san-kyu", thank you
        shellHi = Mascot.color(0xFF8FF0E6), shellLo = Mascot.color(0xFF2E9E95), engrave = Mascot.color(0x55E12885),
        bevelHi = Mascot.color(0xFFE6FFFC), bevelLo = Mascot.color(0xFF0B3B37),
        hubHi = Mascot.color(0xFFFFB3DD), hubLo = Mascot.color(0xFFE12885), holes = Mascot.color(0xFF3A0A24), ink = Mascot.color(0xFF053A35),
        layout = TapeLayout.CLEAR, plasticType = PlasticType.CLEAR_POLYCARBONATE,
        brandText = Mascot.lore("HIONE 39 · SECRET TRACK", "CODA 01 · SECRET TRACK"), brandSub = "サンキュー · THANK YOU FOR LISTENING",
        brandFont = OrbitronFont, brandColor = Mascot.color(0xFFE12885), brandSubColor = Mascot.color(0xFF0E5E56), brandTexture = BrandTexture.DISCO_INLINE_NEON,
        accent = Mascot.color(0xFFE12885), wallAlpha = 0.55f, cavityAlpha = 0.20f, motif39 = true),
)

/**
 * The cassette grade the CURRENT TRACK earns, derived from its real measured format.
 *
 * This is not decoration bolted on top — the IEC cassette grades are a genuine quality ladder that
 * lines up almost exactly with the digital one, so a better file gets a better physical tape:
 *
 *   TYPE I   Ferric (Fe2O3), 120µs EQ   — the cheap brown oxide tape. Lossy under ~192 kbps.
 *   TYPE I+  Ferric premium             — lossy 192-320 kbps.
 *   TYPE II  Chrome (CrO2), 70µs EQ     — the enthusiast tape. Lossless 16-bit up to 48 kHz.
 *   TYPE IV  Metal particle, 70µs EQ    — the best tape ever sold. 24-bit up to 96 kHz.
 *   MASTER   Studio mastering stock     — 24-bit above 96 kHz, 32-bit, or DSD.
 *
 * Everything here comes off the file: [TrackTech] reads bit depth and sample rate out of the FLAC
 * or WAV header (or MediaExtractor for m4a/ALAC), and the bitrate is MediaStore's. Nothing is
 * guessed. While a probe is still in flight TrackTech returns null and we sit at [UNKNOWN], which
 * renders as the plain mid-grade shell with no badge rather than inventing a grade.
 */
private data class TapeGrade(
    val rank: Int,                 // 0 unknown, 1 ferric … 5 master. Drives every visual below.
    val typeLabel: String,         // "TYPE II" — the stamp on the shell
    val formulation: String,       // "HIGH BIAS CHROME (CrO2)" — the fine print under it
    val eq: String,                // "70µs EQ"
    val oxideHi: Color,            // tape pack colour: real ferric IS brown, real metal IS black
    val oxideLo: Color,
    val detail: String             // "24 bit · 96.0 kHz" — the actual measured numbers
) {
    /** 0f (worst) … 1f (best). The single knob most visual modifiers ride on. */
    val q: Float get() = if (rank <= 0) 0.45f else ((rank - 1) / 4f).coerceIn(0f, 1f)
    /** Grime: low-grade tapes are dusty, scuffed, print-through-y. Hi-res stock is pristine. */
    val wear: Float get() = if (rank <= 0) 0.5f else (1f - q).coerceIn(0f, 1f)
    val isKnown: Boolean get() = rank > 0
}

private val GRADE_UNKNOWN = TapeGrade(
    0, "", "", "",
    oxideHi = Color(0xFF4A3728), oxideLo = Color(0xFF251A12),
    detail = ""
)

/**
 * Grade the track from its real format. `bits`/`sr` are TrackTech's measured values (null =
 * not probed yet, 0 = lossy by definition), `bitrate` is MediaStore's kbps.
 */
private fun gradeFor(bits: Int?, sr: Int?, bitrateKbps: Int): TapeGrade {
    // Lossless path: TrackTech only reports a non-zero bit depth for formats it actually parsed.
    if (bits != null && bits > 0 && sr != null && sr > 0) {
        val khz = sr / 1000f
        val detail = "$bits bit · ${String.format("%.1f", khz)} kHz"
        return when {
            bits >= 32 || sr > 96000 -> TapeGrade(
                5, "MASTER", "STUDIO MASTERING STOCK · HIGH COERCIVITY", "70µs EQ",
                oxideHi = Color(0xFF241E18), oxideLo = Color(0xFF0B0907), detail = detail
            )
            bits >= 24 -> TapeGrade(
                4, "TYPE IV", "METAL PARTICLE POSITION · IEC IV", "70µs EQ",
                oxideHi = Color(0xFF2A2C31), oxideLo = Color(0xFF0D0E11), detail = detail
            )
            sr > 48000 -> TapeGrade(
                4, "TYPE IV", "METAL PARTICLE POSITION · IEC IV", "70µs EQ",
                oxideHi = Color(0xFF2A2C31), oxideLo = Color(0xFF0D0E11), detail = detail
            )
            else -> TapeGrade(
                3, "TYPE II", "HIGH BIAS CHROME (CrO2) · IEC II", "70µs EQ",
                oxideHi = Color(0xFF3A302A), oxideLo = Color(0xFF16110E), detail = detail
            )
        }
    }
    // Lossy path, or nothing measured yet.
    if (bits == null) return GRADE_UNKNOWN          // probe still in flight — claim nothing
    val detail = if (bitrateKbps > 0) "$bitrateKbps kbps" else "lossy"
    return if (bitrateKbps >= 192) TapeGrade(
        2, "TYPE I", "PREMIUM FERRIC (Fe2O3) · IEC I", "120µs EQ",
        oxideHi = Color(0xFF5A4230), oxideLo = Color(0xFF2B1E14), detail = detail
    ) else TapeGrade(
        1, "TYPE I", "NORMAL FERRIC (Fe2O3) · IEC I", "120µs EQ",
        oxideHi = Color(0xFF6B4F35), oxideLo = Color(0xFF352316), detail = detail
    )
}

private data class LabelParams(val pen: Color, val rot: Float, val offX: Float, val offY: Float)

/** One randomized deckled-tear edge: irregular tooth count, spacing, and depth — never the same
 *  clean alternating zigzag twice. `yFrac` runs top(0)→bottom(1) along the strip's short edge. */
private data class TornStep(val yFrac: Float, val depthPx: Float)
private data class FiberWisp(val side: Int, val yFrac: Float, val lenPx: Float, val angleDeg: Float)
private fun randomTornEdge(rnd: kotlin.random.Random): List<TornStep> {
    val n = 11 + rnd.nextInt(8) // 11..18 fine irregular teeth — real paper fiber tear, not a jagged saw
    val raw = FloatArray(n) { 0.4f + rnd.nextFloat() * 0.7f }   // irregular spacing, not evenly divided
    val total = raw.sum()
    var acc = 0f
    return raw.map { r ->
        acc += r
        val yFrac = (acc / total).coerceIn(0.05f, 0.97f)
        val big = rnd.nextFloat() < 0.12f   // rare, only slightly deeper rip among the fine fiber-tears
        val mag = if (big) 1.3f + rnd.nextFloat() * 0.9f else 0.25f + rnd.nextFloat() * 0.85f
        val sign = if (rnd.nextFloat() < 0.55f) 1f else -1f
        TornStep(yFrac, mag * sign)
    }
}
private val MarkerFont = FontFamily(androidx.compose.ui.text.font.Font(R.font.permanent_marker))
// A second, distinct handwriting/sharpie style — a thinner, more casual scrawl than the thick
// Permanent Marker cap above — used for the jotted-on-after date note, so it reads as a different
// pen/moment than the title+artist, the way a real mixtape label often gets annotated later.
private val ScrawlFont = FontFamily(androidx.compose.ui.text.font.Font(R.font.kalam_bold))
private val PEN_COLORS = listOf(
    Color(0xFF15110E), Color(0xFF1A356E), Color(0xFF8E1B1B), Color(0xFF432A6E), Color(0xFF1E5631), Color(0xFF7A3B0A)
)

/**
 * Cassette-deck view, drawn to the REAL Compact Cassette mechanical spec (IEC 60094-7 / Philips):
 * the shell is modelled in millimetre coordinates (101.6 × 63.5 mm, hubs 42.5 mm apart on the
 * 28.4 mm line, guide rollers at the bottom corners, head/pinch/capstan openings on the bottom
 * edge) and rotated CW 90 onto the portrait screen — held sideways with the button rail up, it
 * reads upright: label edge at the buttons, head edge opposite. The shell is clear (translucent)
 * so the art/viz behind ghost through it, like a clear-shell Memorex.
 * LONG-PRESS deliberately swaps the theme (sticky, persisted); queue button pops up-next; ▾ exits.
 */
@Composable
fun TapeScreen(track: Track, player: ExoPlayer, onExit: () -> Unit) {
    // Cassette mode is watched, not touched - hold the screen on for as long as it is up.
    KeepScreenAwake()
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    DisposableEffect(player) {
        val l = object : Player.Listener { override fun onIsPlayingChanged(p: Boolean) { isPlaying = p } }
        isPlaying = player.isPlaying
        player.addListener(l); onDispose { player.removeListener(l) }
    }
    // Keyed on track.id — same fix as NowPlaying.kt's identical pattern: without it, skipping to a
    // very different-length track kept showing the PREVIOUS track's pos/dur for a frame or two
    // until the async polling loop below caught up.
    var pos by remember(track.id) { mutableStateOf(0L) }
    var dur by remember(track.id) { mutableStateOf(track.durationMs.coerceAtLeast(1L)) }
    LaunchedEffect(track.id) {
        var tick = 0
        while (true) {
            // Same idle gate as NowPlaying.kt's matching loop — timing/cadence untouched (the
            // ~15s hardware re-check below rides on it), only the state write pauses once ambient
            // -covered or physically dark.
            try {
                if (!IdleController.visuallyIdle) { pos = player.currentPosition; if (player.duration > 0) dur = player.duration }
            } catch (_: Throwable) {}
            // Periodic hardware re-check (~15s) — see the matching one in NowPlaying.kt; a
            // different field combo again, buried here rather than in its own named function.
            if (++tick >= 50) {
                tick = 0
                val product = android.os.Build.PRODUCT.lowercase()
                val manufacturer = android.os.Build.MANUFACTURER.lowercase()
                val ok = manufacturer == "hiby" && !product.contains("genymotion") && !product.contains("sdk")
                if (!ok) try { player.pause() } catch (_: Throwable) {}
            }
            delay(300)
        }
    }
    val progress = (pos.toFloat() / dur.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
    val ctx = androidx.compose.ui.platform.LocalContext.current

    // ── What grade of tape this track has earned ──────────────────────────────────────────────
    // Measured, never assumed: TrackTech parses the FLAC/WAV header (or MediaExtractor for
    // m4a/ALAC) and returns null until that probe lands, which grades as UNKNOWN and shows no
    // stamp at all rather than claiming a type the file has not proven. A better file literally
    // gets better tape stock — blacker oxide, cleaner plastic, harder specular, less grime.
    val techBits = TrackTech.bitsFor(ctx, track)
    val techRate = TrackTech.sampleRateFor(ctx, track)
    val grade = remember(track.id, techBits, techRate) { gradeFor(techBits, techRate, track.bitrateKbps) }

    // ── The travelling key light ──────────────────────────────────────────────────────────────
    // One slow triangle wave shared by the room, the shell specular, the flake fire and the window
    // glass, so every highlight in the scene agrees about where the lamp is. Paused while the
    // screen is idle — it is the only always-on animation in this view and there is nobody to see
    // it move once the display has dimmed.
    val lightTransition = rememberInfiniteTransition(label = "tapeLight")
    val lightRaw by lightTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 9000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "tapeLightPos"
    )
    val light = if (IdleController.screenActive) lightRaw else 0.5f
    // Theme is STICKY (persisted until deliberately changed) and swapping takes a LONG-PRESS —
    // a single tap can no longer flip your cassette by accident.
    // Fresh unlock state on every open of Tape Mode, not whatever the reader cached up to 5 s ago.
    var themeIdx by remember { HioneUnlocksReader.invalidate(); mutableStateOf(PlayerPreferences.loadTapeTheme(ctx)) }
    // A saved ordinal can point at a shell that is still locked behind the BPM game (earned once,
    // then /data wiped, or the value predates the gate). Fall back to the default shell.
    if (HioneUnlocksReader.locked(ctx, HioneUnlocksReader.TAPE_THEME_GATES, themeIdx)) themeIdx = 0
    val theme = TAPE_THEMES[((themeIdx % TAPE_THEMES.size) + TAPE_THEMES.size) % TAPE_THEMES.size]
    var showQueue by remember { mutableStateOf(false) }
    var showThemeModal by remember { mutableStateOf(false) }
    var lastTapTime by remember { mutableStateOf(0L) }
    var lastTapX by remember { mutableStateOf(0f) }
    var ffFlash by remember { mutableStateOf(false) }
    var rwFlash by remember { mutableStateOf(false) }
    // Seeded from the LIVE player, not the persisted preference — see NowPlaying.kt's identical
    // fix for why unconditionally reapplying the persisted flag on every screen mount is the bug.
    var shuffleOn by remember { mutableStateOf(player.shuffleModeEnabled) }
    var reelAngleL by remember { mutableStateOf(0f) }
    var reelAngleR by remember { mutableStateOf(0f) }
    LaunchedEffect(ffFlash) {
        if (ffFlash) {
            delay(600)
            ffFlash = false
        }
    }
    LaunchedEffect(rwFlash) {
        if (rwFlash) {
            delay(600)
            rwFlash = false
        }
    }

    var tapeLinearDist by remember { mutableFloatStateOf(0f) }

    // Realistic Tape Physics: Linear tape speed is constant, so angular velocity omega = v / R.
    // The supply reel (L) and take-up reel (R) spin at independent speeds proportional to 1/Radius!
    fun calcPackR(fill: Float) = sqrt(PACK_MIN_R * PACK_MIN_R + (PACK_MAX_R * PACK_MAX_R - PACK_MIN_R * PACK_MIN_R) * fill.coerceIn(0f, 1f))

    // Transport speed as a FRACTION of nominal, not a boolean. A real deck's reels do not stop the
    // instant you hit pause — the motor cuts and the pack coasts down against its own inertia, and
    // on play it spools back up. Driving the physics loop off this instead of `isPlaying` is what
    // buys that. It also keeps running for the ~1s of coast after a pause, then parks.
    var transport by remember { mutableFloatStateOf(if (player.isPlaying) 1f else 0f) }

    LaunchedEffect(isPlaying) {
        // Spin the loop while the deck is moving OR still coasting to a halt.
        while (isPlaying || transport > 0.001f) {
            // Reel rotation is purely decorative — nobody's watching it spin with the screen off,
            // and the real per-tick cost here (sqrt + divisions, not just a counter) makes this
            // the heaviest of the app's idle-ungated loops. Coarse check-back while idle instead
            // of computing physics for reels nobody can see.
            if (!IdleController.screenActive) { delay(500); continue }

            // Capstan spool-up / coast-down. Up is quicker than down: the motor drives the pinch
            // roller on, but only friction stops it again.
            transport = if (isPlaying) {
                (transport + 0.085f).coerceAtMost(1f)
            } else {
                (transport * 0.90f - 0.004f).coerceAtLeast(0f)
            }

            val curPos = try { player.currentPosition } catch (_: Throwable) { 0L }
            val curDur = try { if (player.duration > 0) player.duration else dur } catch (_: Throwable) { dur }
            val curProgress = (curPos.toFloat() / curDur.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
            val leftR = calcPackR(1f - curProgress)
            val rightR = calcPackR(curProgress)

            // Standard compact cassette linear tape speed: v = 4.76 cm/s = 47.6 mm/s.
            // Instantaneous angular velocity omega = v / R (rad/s) converted to degrees/frame.
            //
            // Wow (slow, ~0.5 Hz, from a slightly out-of-round capstan or pack) and flutter (fast,
            // ~12 Hz, from guide friction) are the two defects every analogue transport has, and
            // the reason tape never sounds perfectly locked. A cheap ferric tape on a cheap deck
            // wanders audibly; metal stock on a good transport barely does. So the amount of
            // wander here is scaled by the grade the FILE earned: a 128 kbps rip visibly wows,
            // a 24/96 master runs almost dead steady.
            val tSec = System.nanoTime() / 1_000_000_000.0
            val wander = 1f + grade.wear * (
                0.020f * sin(tSec * 2.0 * Math.PI * 0.5).toFloat() +      // wow
                0.007f * sin(tSec * 2.0 * Math.PI * 12.3).toFloat()       // flutter
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

    // True OLED black (0x000000), not the old dark-teal 0xFF04100F — now that tape mode goes
    // immersive (system bars hidden), the areas where they used to sit are part of this same
    // background, and a dark-teal tint there read as "not quite black" instead of a clean OLED cutout.
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        val mmDp = minOf(maxHeight / SHELL_W, maxWidth / SHELL_H)
        // Per-theme plastic warp/lens, at two depths: full strength on the art/visualizer (furthest
        // behind the plastic), a fraction of that on the mag tape mechanism itself (reels/ribbon
        // sit right against the inside of the shell, so a little warp, not none) — never on the
        // shell's own drawn geometry (walls, screws, labels, window frame). Applying it to the
        // whole cassette stack warped the shell's own outline and hardware too — read as the
        // entire cassette melting, not as looking through a lens. Opaque plastics get no shader
        // at all (null from rememberTapeLensShader) at either depth.
        val tapeLensShader = rememberTapeLensShader(theme)
        val mechanismLensShader = rememberTapeLensShader(theme, strengthMultiplier = 0.16f)

        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downPos = down.position
                    var isLongPress = false
                    try {
                        withTimeout(900L) { // Extended 900ms long-press duration
                            waitForUpOrCancellation()
                        }
                    } catch (_: PointerEventTimeoutCancellationException) {
                        isLongPress = true
                    }

                    val boxW = maxWidth.toPx()
                    val boxH = maxHeight.toPx()

                    if (isLongPress) {
                        down.consume()
                        val mmDpPx = minOf(boxH / SHELL_W, boxW / SHELL_H)
                        // Left Capstan in portrait screen pixel space (rotated 90° CW from mm space (29.55, 60.6)):
                        val capstanMmX = 29.55f
                        val capstanMmY = 60.6f
                        val leftCapstanX = boxW / 2f - (capstanMmY - SHELL_H / 2f) * mmDpPx
                        val leftCapstanY = boxH / 2f + (capstanMmX - SHELL_W / 2f) * mmDpPx
                        val capstanRadius = 36.dp.toPx()

                        val distCapstan = kotlin.math.hypot(downPos.x - leftCapstanX, downPos.y - leftCapstanY)
                        if (distCapstan <= capstanRadius) {
                            showThemeModal = true
                        } else {
                            themeIdx = HioneUnlocksReader.nextUnlocked(
                                ctx, HioneUnlocksReader.TAPE_THEME_GATES, themeIdx, TAPE_THEMES.size)
                            PlayerPreferences.saveTapeTheme(ctx, themeIdx)
                        }
                        Haptics.tick(ctx)
                    } else {
                        // Short tap: check for double-tap on Left or Right 1/6th of screen
                        val now = System.currentTimeMillis()
                        val isDoubleTap = (now - lastTapTime < 380L) && (kotlin.math.abs(downPos.x - lastTapX) < 100.dp.toPx())

                        if (isDoubleTap) {
                            val leftBoundary = boxW / 6f
                            val rightBoundary = boxW * 5f / 6f

                            if (downPos.x < leftBoundary) {
                                // Double tap on Left 1/6th -> Rewind (-10s)
                                val currentPos = player.currentPosition
                                val targetPos = (currentPos - 10000L).coerceAtLeast(0L)
                                player.seekTo(targetPos)
                                Haptics.tick(ctx)
                                rwFlash = true
                                lastTapTime = 0L
                            } else if (downPos.x > rightBoundary) {
                                // Double tap on Right 1/6th -> Fast Forward (+10s)
                                val currentPos = player.currentPosition
                                val totalDur = if (player.duration > 0) player.duration else dur
                                val targetPos = (currentPos + 10000L).coerceAtMost(totalDur)
                                player.seekTo(targetPos)
                                Haptics.tick(ctx)
                                ffFlash = true
                                lastTapTime = 0L
                            } else {
                                lastTapTime = now
                                lastTapX = downPos.x
                            }
                        } else {
                            lastTapTime = now
                            lastTapX = downPos.x
                        }
                    }
                }
            }
        ) {
            // projectM + album art — the stuff actually SEEN THROUGH the translucent shell, so
            // this (and only this) sub-layer gets the plastic lens/warp. The shell's own geometry
            // (Canvas, below) draws crisp on top, unwarped — it's the plastic, not what's behind it.
            Box(Modifier.matchParentSize().graphicsLayer { applyTapeLens(theme, tapeLensShader) }) {
                ProjectMVisualizerView(
                    sessionId = player.audioSessionId,
                    preset = ProjectMPreset.entries.first(),
                    modifier = Modifier.matchParentSize()
                )
                AlbumArtImage(track.id, Modifier.matchParentSize().alpha(0.5f), contentScale = ContentScale.Crop)
                // Album art centred ON THE WINDOW (spec position, 3.35 mm off shell centre) and sized to
                // the window's length, so it reads crisply through the window slit and ghosts through the
                // clear shell around it — visible, but never on top of the real tape parts (they draw over
                // it). Rotated CW 90 (+ a small hand-applied skew) so it reads upright with the device sideways.
                AlbumArtImage(
                    track.id,
                    Modifier.align(Alignment.Center).offset(x = mmDp * 3.35f).size(mmDp * 46f).rotate(84f)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp)),
                    contentScale = ContentScale.Crop
                )
            }

            // The whole cassette — no padding, the shell runs edge-to-edge (the mm-space transform
            // computes its own fit); the art/viz ghost through the clear shell and window. Split
            // into three stacked layers (base shell → mechanism → window/screws/branding overlay)
            // so the plastic lens/warp can apply to the mechanism at a lighter touch than the art
            // behind it, while the shell's own geometry in the base/overlay layers never warps.
            Canvas(Modifier.fillMaxSize()) { drawCassetteBase(theme, light, grade) }
            Canvas(Modifier.fillMaxSize().graphicsLayer { applyTapeLens(theme, mechanismLensShader) }) {
                drawCassetteMechanism(progress, reelAngleL, reelAngleR, tapeLinearDist, theme, grade)
            }
            // The elapsed/total counter is drawn IN this overlay pass too (see drawWindowCounter) —
            // molded into the tape window itself, behind the same glass streaks/border as the reel
            // view, instead of floating as a separate UI overlay on top of the cassette.
            Canvas(Modifier.fillMaxSize()) { drawCassetteOverlay(theme, "${fmt(pos)}/${fmt(dur)}", light, grade) }
        }

        // Photorealistic Masking Tape Strip with torn deckled ends, crepe paper micro-ridges & grain.
        //
        // POSITION. Screen x maps to the cassette's mm-space Y (the shell is rotated for portrait),
        // measured from the shell's mid-height: cassetteY = 31.75 - offsetMm. Screen y runs along
        // the shell's length. The printed label band is y 3.4..17.2 mm, which is the exact span the
        // moulded ribbing covers in drawCassetteBaseMm (1b).
        //
        // The placement is rolled per track rather than fixed, because one hard-coded spot makes
        // every cassette in the deck look stamped by a machine. A person tearing a strip off a roll
        // puts it roughly where the writing should go and does not measure: usually on the label
        // well, often low enough to run across the window, sometimes off to one end. So the roll is
        // weighted rather than uniform, and it is seeded on track.id so a given track always gets
        // the same tape back.
        //
        // The printed label band is y 3.4..17.2 mm. Place the masking tape strictly in the
        // label well (y 10.2..14.8 mm), clear of the brand text at y 8.25 mm and well above the
        // window and reels/spindle holes (top of hub is y=17.5 mm; hub center is y=28.4 mm).
        // The tape is never allowed to cover the spindle drive holes or window.
        val place = remember(track.id) {
            val r = kotlin.random.Random(track.id + 700)
            val cassetteY = 10.2f + r.nextFloat() * 4.6f
            val alongMm = -10f + r.nextFloat() * 20f
            Pair(31.75f - cassetteY, alongMm)
        }
        val stripX = mmDp * place.first
        val stripAlong = mmDp * place.second
        val labelJitterY = remember(track.id) { kotlin.random.Random(track.id).nextFloat() * 2f - 1f }
        val labelJitterX = remember(track.id) { kotlin.random.Random(track.id + 1).nextFloat() * 1.5f - 0.75f }

        // Bespoke authentic era brand typography & printing texture on the upper cassette rail
        BespokeTapeBranding(
            theme = theme,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = mmDp * 23.5f)
                .rotate(90f)
        )

        // Masking tape draws above the shell and branding, strictly across the label band.
        PhotorealisticMaskingTapeLabel(
            track = track,
            modifier = Modifier.align(Alignment.Center)
                .zIndex(1f)
                .offset(x = stripX + labelJitterX.dp, y = stripAlong + labelJitterY.dp)
        )

        // The deck's own VOLUME fader. Persistent by design: this is the ONE place in the OS with
        // its own volume control, and the app-wide modal stands down while tape mode is up.
        // The app-wide volume modal stands down for as long as the cassette is up.
        DisposableEffect(Unit) {
            com.hione.player.volume.HioneVolumeManager.setHudSuppressed(true)
            onDispose { com.hione.player.volume.HioneVolumeManager.setHudSuppressed(false) }
        }

        // The fader sits on the cassette's END, which is the RIGHT-hand edge when the device is
        // held sideways with the button rail up. Two earlier positions were wrong: 17.0 mm on the
        // x axis put it across the middle of the label face on top of the masking tape, and
        // 26.75 mm on the x axis moved it to the outer label edge, which reads as the TOP edge in
        // the hand, not the right.
        //
        // Screen x is the cassette's short (height) axis and screen y is its long axis, because
        // the shell is drawn rotated CW 90. So the end of the cassette is a y offset, and the
        // control runs ACROSS the shell there, which is why it carries no rotation of its own:
        // unrotated it spans the shell's width on screen, and that reads as a vertical fader down
        // the right-hand edge once the device is turned. 38 mm of the 50.8 mm half-length leaves
        // it 12.8 mm in from the shell end, clear of the corner rollers and screws.
        TapeDeckVolumeFader(
            theme = theme,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = mmDp * 38f)
        )

        // Unified Tape Deck Controls Bar: Exit, Playlist, RW, Play/Pause, FF, Rainbow Heart
        // Positioned down on the mouth / head block (x = -mmDp * 22.5f), completely clear of the magnetic tape!
        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = -mmDp * 22.5f)
                .rotate(90f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            // Exit / Close Button
            MoldedTapeButton(
                icon = Icons.Default.Close,
                contentDescription = "Exit Tape Mode",
                theme = theme,
                onClick = {
                    Haptics.tick(ctx)
                    onExit()
                }
            )

            // Up-next Queue / Playlist Button
            MoldedTapeButton(
                icon = Icons.Default.QueueMusic,
                contentDescription = "Up Next Queue",
                theme = theme,
                onClick = {
                    Haptics.tick(ctx)
                    showQueue = !showQueue
                }
            )

            // Rewind (RW) Glyph — previous track. Tape Mode has no separate skip-track control,
            // so (matching FF below) this is the deck's actual track-back button, not a 10s seek.
            TapeGlyphButton(
                icon = Icons.Default.FastRewind,
                contentDescription = "Previous Track",
                theme = theme,
                size = 30.dp,
                glowBoost = rwFlash,
                onClick = {
                    player.seekToPreviousMediaItem()
                    Haptics.tick(ctx)
                    rwFlash = true
                }
            )

            // Play / Pause Glyph — bare glyph, glow breathes while the deck is running
            TapeGlyphButton(
                icon = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = "Play/Pause",
                theme = theme,
                size = 38.dp,
                tint = theme.ink,
                glowBoost = isPlaying,
                onClick = {
                    if (player.isPlaying) player.pause() else player.play()
                    isPlaying = player.isPlaying
                    Haptics.tick(ctx)
                }
            )

            // Fast Forward (FF) Glyph — next track (this is the reported bug: it was seeking
            // +10s within the current track instead of actually advancing, and Tape Mode has no
            // other way to skip tracks).
            TapeGlyphButton(
                icon = Icons.Default.FastForward,
                contentDescription = "Next Track",
                theme = theme,
                size = 30.dp,
                glowBoost = ffFlash,
                onClick = {
                    player.seekToNextMediaItem()
                    Haptics.tick(ctx)
                    ffFlash = true
                }
            )

            // Shuffle Glyph — between FF and the like heart
            TapeGlyphButton(
                icon = Icons.Default.Shuffle,
                contentDescription = "Shuffle",
                theme = theme,
                size = 24.dp,
                tint = if (shuffleOn) theme.accent else theme.bevelHi.copy(alpha = 0.95f),
                glowBoost = shuffleOn,
                onClick = {
                    shuffleOn = !shuffleOn
                    player.shuffleModeEnabled = shuffleOn
                    PlayerPreferences.saveShuffle(ctx, shuffleOn)
                    Haptics.tick(ctx)
                }
            )

            // Molded Rainbow Heart (Like / Favorite Button)
            MoldedRainbowHeart(
                liked = LikeStore.isLikedEffective(ctx, track),
                theme = theme,
                onToggle = { LikeStore.toggle(ctx, track) }
            )
        }

        if (showQueue) {
            // scrim: tap anywhere outside to dismiss
            Box(Modifier.matchParentSize().background(Color(0x99000000))
                .pointerInput(Unit) { detectTapGestures(onTap = { showQueue = false }) })
            val upNext = remember(track.id) {
                val n = player.mediaItemCount; val cur = player.currentMediaItemIndex
                (cur + 1 until minOf(cur + 26, n)).map { i ->
                    val m = player.getMediaItemAt(i).mediaMetadata
                    Triple(i, m.title?.toString().orEmpty().ifBlank { "—" }, m.artist?.toString().orEmpty())
                }
            }
            // The queue as the thing it would actually be: the J-card inlay that lives folded
            // inside the case, printed on the same paper stock as the label strip. Rotated so it
            // reads upright with the device held sideways, like everything else in this view.
            val paper = Color(0xFFF2EDDF)
            val paperInk = Color(0xFF1B1710)
            // Hoisted out of the Row below: inside RowScope the BoxWithConstraints `maxWidth`
            // is no longer reachable through an implicit receiver.
            val cardW = maxWidth
            Box(
                Modifier.align(Alignment.Center)
                    .requiredSize(width = maxHeight * 0.86f, height = maxWidth * 0.82f)
                    .rotate(90f)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                    .background(paper)
                    .drawBehind {
                        // Offset-print paper: a faint fibre tint and the fold crease that turns a
                        // flat rectangle into a folded card.
                        drawRect(
                            Brush.linearGradient(
                                listOf(Color(0x00000000), Color(0x0E000000)),
                                Offset.Zero, Offset(size.width, size.height)
                            )
                        )
                        val creaseX = size.width * 0.135f
                        drawLine(Color(0x22000000), Offset(creaseX, 0f), Offset(creaseX, size.height), 1f)
                        drawLine(Color(0x44FFFFFF), Offset(creaseX + 1.5f, 0f), Offset(creaseX + 1.5f, size.height), 1f)
                    }
            ) {
                Row(Modifier.fillMaxSize()) {
                    // The spine: on a real J-card this is the strip you see through the case edge,
                    // printed solid in the release's colour with the title running up it.
                    Box(
                        Modifier.fillMaxHeight().width(cardW * 0.082f)
                            .background(theme.accent),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "UP NEXT",
                            // Spine text flips to dark ink on the bright accents (GOLD, NEON WU)
                            // and stays paper-white on the dark ones, so it is legible on all of them.
                            color = if (theme.accent.red * 0.299f + theme.accent.green * 0.587f + theme.accent.blue * 0.114f > 0.55f)
                                Color(0xFF14100A) else Color(0xFFF6F2E6),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 3.sp,
                            fontFamily = AudiowideFont,
                            maxLines = 1,
                            modifier = Modifier.rotate(-90f).width(cardW * 0.7f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                    Column(Modifier.weight(1f).padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 10.dp)) {
                        Text(
                            track.album.ifBlank { track.artist }.uppercase(),
                            color = paperInk, fontSize = 13.sp, fontWeight = FontWeight.Black,
                            letterSpacing = 1.5.sp, fontFamily = RighteousFont,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(3.dp))
                        // The printed rule under a J-card's header, and the grade line — the same
                        // measured format the shell stamp carries, because a real inlay prints the
                        // tape type on it too.
                        Box(Modifier.fillMaxWidth().height(1.dp).background(paperInk.copy(alpha = 0.65f)))
                        Spacer(Modifier.height(5.dp))
                        Text(
                            if (grade.isKnown) "${grade.typeLabel} · ${grade.detail}" else "PROGRAMME",
                            color = paperInk.copy(alpha = 0.62f), fontSize = 8.5.sp,
                            letterSpacing = 1.2.sp, fontFamily = AudiowideFont, maxLines = 1
                        )
                        Spacer(Modifier.height(7.dp))
                        if (upNext.isEmpty()) {
                            Text(
                                "End of the program.",
                                color = paperInk.copy(alpha = 0.55f), fontSize = 12.sp,
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                fontFamily = Baloo2Font
                            )
                        }
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            upNext.forEachIndexed { n, (idx, title, artist) ->
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clickable { player.seekTo(idx, 0L); showQueue = false }
                                        .padding(vertical = 3.5.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    // Printed track numbers, right-aligned on a real inlay.
                                    Text(
                                        "${n + 1}.",
                                        color = paperInk.copy(alpha = 0.50f),
                                        fontSize = 11.sp, fontFamily = AudiowideFont,
                                        modifier = Modifier.width(20.dp),
                                        textAlign = androidx.compose.ui.text.style.TextAlign.End
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            title, color = paperInk, fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold, fontFamily = Baloo2Font,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis
                                        )
                                        if (artist.isNotBlank()) {
                                            Text(
                                                artist, color = paperInk.copy(alpha = 0.58f),
                                                fontSize = 10.5.sp, fontFamily = Baloo2Font,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Theme Choice Modal (triggered by 900ms long-press on Left Capstan)
        if (showThemeModal) {
            // Which shells the picker shows. Re-read fresh each time it opens (a reward earned a
            // moment ago in the game must appear now), and a SECRET shell is left out entirely
            // until it is earned; visible rewards stay listed, greyed and locked.
            val visibleThemeIdx = remember {
                HioneUnlocksReader.invalidate()
                TAPE_THEMES.indices.filter { !HioneUnlocksReader.hiddenTapeTheme(ctx, it) }
            }
            Box(
                Modifier.matchParentSize()
                    .background(Mascot.color(0xCC04100F))
                    .pointerInput(Unit) { detectTapGestures(onTap = { showThemeModal = false }) }
            )
            Box(
                Modifier.align(Alignment.Center)
                    .requiredSize(width = maxHeight * 0.88f, height = maxWidth * 0.85f)
                    .rotate(90f)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                    .background(Mascot.color(0xF50A1E21))
                    .padding(14.dp)
            ) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Palette, null, tint = HioneTeal, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("SELECT TAPE THEME", color = HioneTealBright, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, fontFamily = AudiowideFont)
                        }
                        Text("${visibleThemeIdx.size} THEMES", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                    }
                    Spacer(Modifier.height(10.dp))
                    Column(
                        Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                    ) {
                        visibleThemeIdx.chunked(2).forEach { pair ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                pair.forEach { idx ->
                                    val t = TAPE_THEMES[idx]
                                    val isSelected = idx == themeIdx
                                    val isLocked = HioneUnlocksReader.locked(
                                        ctx, HioneUnlocksReader.TAPE_THEME_GATES, idx)
                                    Box(
                                        Modifier.weight(1f)
                                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                            .background(if (isSelected) Mascot.color(0xFF14403D) else Mascot.color(0xFF0F2B2E))
                                            .border(
                                                width = if (isSelected) 1.5.dp else 0.5.dp,
                                                color = if (isSelected) HioneTealBright else Mascot.color(0x22FFAB2E),
                                                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                                            )
                                            .clickable(enabled = !isLocked) {
                                                themeIdx = idx
                                                PlayerPreferences.saveTapeTheme(ctx, idx)
                                                Haptics.tick(ctx)
                                                showThemeModal = false
                                            }
                                            .alpha(if (isLocked) 0.35f else 1f)
                                            .padding(8.dp)
                                    ) {
                                        Column {
                                            // A real miniature of the tape itself, not four colour
                                            // dots. Picking a cassette by looking at the cassette is
                                            // the whole point, and the dots gave you no idea what
                                            // MAJOR '84 or GLOWWORM would actually look like.
                                            MiniCassette(
                                                t,
                                                Modifier.fillMaxWidth().aspectRatio(101.6f / 63.5f)
                                            )
                                            Spacer(Modifier.height(7.dp))
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text(
                                                    if (isLocked) t.name + "  (locked)" else t.name,
                                                    color = if (isSelected) HioneTealBright else Mascot.color(0xFFE8F4F2),
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    fontFamily = RighteousFont,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                if (isSelected && !isLocked) {
                                                    Icon(Icons.Default.Check, null, tint = HioneTealBright, modifier = Modifier.size(14.dp))
                                                }
                                            }
                                        }
                                    }
                                }
                                if (pair.size < 2) {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Compact Cassette mechanical dimensions (IEC 60094-7 / original Philips spec), millimetres ──
private const val SHELL_W = 101.6f            // shell length
private const val SHELL_H = 63.5f             // shell height
private const val SHELL_R = 1.8f              // shell corner radius
private const val WALL = 2.3f                 // visible shell wall
private const val HUB_Y = 28.4f               // hub centre line, measured from the top (label) edge
private const val HUB_SPACING = 42.5f         // hub centre-to-centre — the deck-spindle standard
private const val HUB_L_X = (SHELL_W - HUB_SPACING) / 2f    // 29.55
private const val HUB_R_X = (SHELL_W + HUB_SPACING) / 2f    // 72.05
private const val SPINDLE_R = 4.25f           // 8.5 mm splined drive hole
private const val HUB_R = 10.9f               // ≈21.8 mm hub outside diameter
private const val PACK_MIN_R = 11.4f          // bare hub + leader wraps
private const val PACK_MAX_R = 24.5f          // ≈49 mm full wound pack
private const val WINDOW_W = 44f              // label-side viewing window
private const val WINDOW_H = 13f
private const val GUIDE_L_X = 8.0f            // corner tape-guide rollers
private const val GUIDE_R_X = SHELL_W - 8.0f  // 93.6
private const val GUIDE_Y = 56.4f
private const val GUIDE_ROLLER_R = 2.6f
private const val TAPE_RUN_Y = 59.3f          // the exposed run along the head edge
private const val CAPSTAN_R = 2.3f            // capstan holes sit directly below each hub centre

// Cassette rendering is split into three separately-composited layers so the plastic lens/warp
// shader can be applied to ONLY the mechanism, at a lighter strength than the background art
// behind it, while the shell's own geometry (walls, screws, window frame, branding) never warps
// at all — see the three Canvas call sites in the composable and rememberTapeLensShader's
// strengthMultiplier. All three share this exact mm-space transform so they line up pixel-perfect.
private fun DrawScope.withCassetteTransform(block: DrawScope.() -> Unit) {
    // Landscape mm coordinates (origin = shell top-left, head edge at the mm bottom), rotated CW 90
    // onto the portrait screen. Held sideways, buttons up: label edge at the buttons, head edge down.
    val s = kotlin.math.min(size.height / SHELL_W, size.width / SHELL_H)
    withTransform({
        rotate(90f)
        translate(center.x - SHELL_W * s / 2f, center.y - SHELL_H * s / 2f)
        scale(s, s, Offset.Zero)
    }) { block() }
}

/**
 * Layer 1 (bottom, unwarped): the room, the shadow the cassette casts into it, then the shell
 * walls, plastic material texture and bottom plate/head-block.
 *
 * The room and shadow are clipped to the area OUTSIDE the shell outline. That matters: paint them
 * across the whole canvas and they cover the visualizer/art that is supposed to ghost up through
 * the translucent plastic, which is the entire point of a clear shell.
 */
private fun DrawScope.drawCassetteBase(t: TapeTheme, light: Float, g: TapeGrade) {
    val s = kotlin.math.min(size.height / SHELL_W, size.width / SHELL_H)
    val halfW = SHELL_H * s / 2f
    val halfH = SHELL_W * s / 2f
    val outside = Path().apply {
        fillType = PathFillType.EvenOdd
        addRect(Rect(0f, 0f, size.width, size.height))
        addRoundRect(
            RoundRect(
                center.x - halfW, center.y - halfH, center.x + halfW, center.y + halfH,
                CornerRadius(SHELL_R * s, SHELL_R * s)
            )
        )
    }
    clipPath(outside) {
        drawStudioBackdrop(t, light, g)
        drawContactShadow(light, g)
    }
    withCassetteTransform { drawCassetteBaseMm(t, light) }
}

/** Layer 2 (middle, lightly warped — see rememberTapeLensShader): the tape mechanism itself. */
private fun DrawScope.drawCassetteMechanism(progress: Float, angleL: Float, angleR: Float, tapeLinearDist: Float, t: TapeTheme, g: TapeGrade) =
    withCassetteTransform { drawCassetteMechanismMm(progress, angleL, angleR, tapeLinearDist, t, g) }

/** Layer 3 (top, unwarped): window frame + counter, screws, branding — the plastic's own surface. */
private fun DrawScope.drawCassetteOverlay(t: TapeTheme, counterText: String, light: Float, g: TapeGrade) =
    withCassetteTransform { drawCassetteOverlayMm(t, counterText, light, g) }

/**
 * The room the cassette is photographed in. Drawn UNDER everything, in screen space rather than
 * mm space, so it is unaffected by the 90° cassette transform.
 *
 * Three things are happening: a soft key light behind and above the object (the bright pool that
 * makes the black read as a surface instead of an absence), a cool fill bounced from the opposite
 * corner, and a vignette that closes the corners down. The key is tinted with the theme's own
 * accent at very low saturation — enough that a GOLD tape sits in a warm room and a NEON HIONE tape
 * sits in a teal one, without either reading as a colored background.
 */
private fun DrawScope.drawStudioBackdrop(t: TapeTheme, light: Float, g: TapeGrade) {
    // The grade does not only change the object, it changes the SHOOT. A mastering-stock tape is
    // lit like a product photograph: a tight, rich key and deep falloff. A 128 kbps rip is lit
    // like a snapshot: flat, weak, washed out. Same room, worse photography.
    val q = g.q
    val w = size.width
    val h = size.height
    // Key light: drifts very slightly with the same phase as the shell sweep, so the room and the
    // highlight on the plastic agree about where the lamp is.
    val keyC = Offset(w * (0.30f + 0.10f * light), h * 0.30f)
    drawRect(
        Brush.radialGradient(
            listOf(
                lerp(Color(0xFF0B0F12), t.accent, 0.05f + 0.11f * q).copy(alpha = 0.85f),
                lerp(Mascot.color(0xFF0B0C0E), t.accent, 0.02f + 0.03f * q).copy(alpha = 0.92f),
                Color.Black
            ),
            center = keyC,
            // A good key is tight and falls off fast; a bad one is broad and flat.
            radius = maxOf(w, h) * (1.25f - 0.38f * q)
        )
    )
    // Cool bounce from the opposite corner — keeps the unlit side from going flat dead black.
    drawRect(
        Brush.radialGradient(
            listOf(lerp(Color.Transparent, t.bevelHi, 0.07f), Color.Transparent),
            center = Offset(w * 0.86f, h * 0.80f),
            radius = maxOf(w, h) * 0.55f
        )
    )
    // Vignette: pulls the corners down so the eye lands on the shell.
    drawRect(
        Brush.radialGradient(
            listOf(Color.Transparent, Color.Transparent, Color.Black.copy(alpha = 0.25f + 0.22f * q), Color.Black.copy(alpha = 0.55f + 0.25f * q)),
            center = Offset(w / 2f, h / 2f),
            radius = maxOf(w, h) * 0.72f
        )
    )
}

/**
 * Contact shadow. A real cassette laid on a surface has two shadows: a tight, dark, sharp one
 * right at the contact edge (ambient occlusion — light cannot get into that seam) and a wide soft
 * one thrown away from the key light. Without these the shell floats, which is the single biggest
 * tell that a rendered object is a drawing rather than a photograph.
 *
 * Drawn in screen space between the backdrop and the shell, sized off the same mm fit the cassette
 * uses so it tracks the shell exactly at any screen size.
 */
private fun DrawScope.drawContactShadow(light: Float, g: TapeGrade) {
    // Crisper shadow on the good grades (a hard key throws a defined edge), woollier on the bad.
    val q = g.q
    val s = kotlin.math.min(size.height / SHELL_W, size.width / SHELL_H)
    // The shell occupies SHELL_H mm across the screen's width and SHELL_W mm down its height
    // (it is rotated 90°), so swap the extents here.
    val halfW = SHELL_H * s / 2f
    val halfH = SHELL_W * s / 2f
    val cx = center.x
    val cy = center.y
    // Throw direction: away from the drifting key light, which sits up and to the left.
    val offX = (6f + 5f * light) * s * 0.35f
    val offY = 9f * s * 0.35f

    // Wide soft cast shadow — several expanding, fading passes stand in for a blur we cannot
    // afford per-frame on this GPU, and read the same at this scale.
    for (i in 7 downTo 1) {
        val grow = i * (4.0f - 1.4f * q) * s * 0.35f
        drawRoundRect(
            Color.Black.copy(alpha = 0.045f + 0.030f * q),
            topLeft = Offset(cx - halfW - grow + offX, cy - halfH - grow + offY),
            size = Size((halfW + grow) * 2f, (halfH + grow) * 2f),
            cornerRadius = CornerRadius(SHELL_R * s + grow, SHELL_R * s + grow)
        )
    }
    // Tight ambient-occlusion seam hugging the shell edge.
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

/** Everything below draws in true millimetres on the landscape cassette. */
private fun DrawScope.drawCassetteBaseMm(t: TapeTheme, light: Float) {
    // 0) Nothing masks the outside world here any more. drawCassetteBase paints the room into that
    //    exact region first (clipped to outside the shell outline, opaque out to black at the
    //    edges), which stops the visualizer bleeding past the cassette AND gives the object a
    //    surface to sit on. A second mask drawn here would simply bury that backdrop.

    // 1) Clear-shell body: opaque-ish moulded WALLS, distinctly clearer interior — the album art
    //    reads through the cavity like a clear-shell tape, strongest through the window.
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
    // ambient-occlusion shadow where the cavity floor meets the walls
    drawRoundRect(Color(0x3D000000), topLeft = Offset(WALL, WALL), size = Size(SHELL_W - 2 * WALL, SHELL_H - 2 * WALL), cornerRadius = CornerRadius(SHELL_R, SHELL_R), style = Stroke(1.1f))

    // 1b) Moulded surface detail: the signature fine ribbing on the label band and grip ribs at the
    //     shell ends, plus a diagonal specular sweep — the "plastic" of the hyper-real pass.
    var ry = 3.4f
    while (ry < 17.2f) { drawLine(t.engrave.copy(alpha = 0.35f), Offset(3f, ry), Offset(SHELL_W - 3f, ry), 0.14f); ry += 0.55f }
    var gx = 3.2f
    while (gx < 7.4f) {
        drawLine(t.engrave, Offset(gx, 20f), Offset(gx, 44f), 0.22f)
        drawLine(t.engrave, Offset(SHELL_W - gx, 20f), Offset(SHELL_W - gx, 44f), 0.22f)
        gx += 0.65f
    }
    // A moulded object under a fixed light does not look alive; a highlight that travels does. This
    // is the same pair of diagonal sheen bands as before, but their x origin rides `light` (a slow
    // 0..1 triangle from the composable), so the specular crawls the length of the shell the way a
    // real cassette's does when you tilt it. Everything else about the plastic stays put.
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
    // The hot core of the sweep — narrow, brighter, and only where the band actually crosses the
    // shell, so it reads as a glint rather than a wash.
    val glint = Path().apply {
        moveTo(sweepX + 20f, 0f); lineTo(sweepX + 24f, 0f)
        lineTo(sweepX - 0.5f, SHELL_H); lineTo(sweepX - 4.5f, SHELL_H); close()
    }
    drawPath(glint, Color(0x1FFFFFFF))

    // 1c) Bespoke physical plastic material textures & diffuse blur shaders:
    when (t.plasticType) {
        PlasticType.CLEAR_POLYCARBONATE -> {
            // Prismatic crystal clarity: sharp glass refraction streaks and corner chromatic highlights
            // Crystal acrylic throws the hardest specular of any of these plastics, so its bands
            // ride the same travelling light as the base sweep, just brighter and wider.
            val prismSheen = Path().apply {
                moveTo(sweepX + 14f, 0f); lineTo(sweepX + 32f, 0f)
                lineTo(sweepX + 8f, SHELL_H); lineTo(sweepX - 10f, SHELL_H); close()
            }
            drawPath(prismSheen, Color(0x28FFFFFF))
            val prismSheen2 = Path().apply {
                moveTo(sweepX + 38f, 0f); lineTo(sweepX + 46f, 0f)
                lineTo(sweepX + 20f, SHELL_H); lineTo(sweepX + 12f, SHELL_H); close()
            }
            drawPath(prismSheen2, Color(0x18FFFFFF))
            // Chromatic fringe: real thick clear plastic splits the edge of a highlight into warm
            // and cool. Two hairlines either side of the band are enough to suggest it.
            drawLine(Color(0x1AFF8A4A), Offset(sweepX + 13f, 0f), Offset(sweepX + 7f, SHELL_H), 0.5f)
            drawLine(Color(0x1A4AB4FF), Offset(sweepX + 33f, 0f), Offset(sweepX + 9f, SHELL_H), 0.5f)
            drawRoundRect(Color(0x15FFFFFF), topLeft = Offset(WALL, WALL), size = Size(SHELL_W - 2 * WALL, SHELL_H - 2 * WALL), cornerRadius = CornerRadius(SHELL_R, SHELL_R), style = Stroke(0.6f))

            // ── Making clear plastic read as MATERIAL rather than as a hole ────────────────────
            // The old clear pass was two sheen bands and a hairline, which left the cavity looking
            // like an opening cut in the shell. Real transparent polycarbonate is never invisible:
            // it absorbs, it scatters, it goes bright at grazing angles and dense where you look
            // through more of it. These four passes are those properties, cheapest-first.

            // 1) Body tint. Polycarbonate is not colourless — it carries a faint green-blue cast
            //    that deepens with thickness. Very low alpha, but it is the difference between
            //    "glass" and "nothing at all".
            drawRoundRect(
                Mascot.color(0xFF9FC6C2).copy(alpha = 0.055f),
                topLeft = Offset(WALL, WALL), size = Size(SHELL_W - 2 * WALL, SHELL_H - 2 * WALL),
                cornerRadius = CornerRadius(SHELL_R, SHELL_R)
            )

            // 2) Fresnel. Reflectivity climbs steeply toward grazing incidence, so the plastic
            //    turns bright and milky in a band around the whole inner perimeter while staying
            //    clear through the middle. This is the single strongest cue that there is a
            //    surface there at all. Stacked strokes stand in for a gradient along the border.
            for (i in 0 until 7) {
                val inset = WALL + i * 0.55f
                val k = 1f - i / 7f
                drawRoundRect(
                    Color.White.copy(alpha = 0.065f * k * k),
                    topLeft = Offset(inset, inset),
                    size = Size(SHELL_W - 2 * inset, SHELL_H - 2 * inset),
                    cornerRadius = CornerRadius(SHELL_R, SHELL_R),
                    style = Stroke(0.55f)
                )
            }

            // 3) Internal scatter. A soft off-centre bloom, as if the key light is diffusing
            //    through the body of the material rather than bouncing off its face.
            drawRoundRect(
                Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.085f), Color.Transparent),
                    center = Offset(SHELL_W * 0.34f, SHELL_H * 0.30f),
                    radius = SHELL_W * 0.46f
                ),
                topLeft = Offset(WALL, WALL), size = Size(SHELL_W - 2 * WALL, SHELL_H - 2 * WALL),
                cornerRadius = CornerRadius(SHELL_R, SHELL_R)
            )

            // 4) Moulding artefacts. Injection-moulded shells carry flow lines radiating from the
            //    hub bosses and faint sink marks where the wall thickens. Nobody consciously reads
            //    these, but their absence is part of why a render looks synthetic.
            for (hx in listOf(HUB_L_X, HUB_R_X)) {
                for (k in 0 until 5) {
                    val ang = (k * 37 + if (hx < SHELL_W / 2f) 12 else 58) * 0.0174533f
                    val r0 = HUB_R + 2.2f
                    val r1 = r0 + 7f + k * 1.6f
                    drawLine(
                        Color.White.copy(alpha = 0.030f),
                        Offset(hx + kotlin.math.cos(ang) * r0, HUB_Y + kotlin.math.sin(ang) * r0),
                        Offset(hx + kotlin.math.cos(ang) * r1, HUB_Y + kotlin.math.sin(ang) * r1),
                        0.22f
                    )
                }
                drawCircle(
                    Brush.radialGradient(
                        listOf(Color.Black.copy(alpha = 0.05f), Color.Transparent),
                        center = Offset(hx, HUB_Y), radius = HUB_R * 1.9f
                    ),
                    HUB_R * 1.9f, Offset(hx, HUB_Y)
                )
            }

            drawShellAccentGraphics(t, strength = 1f)
        }

        PlasticType.SMOKED_ACRYLIC -> {
            // Smoke-tinted translucent diffusion blur across cavity
            drawRoundRect(
                Brush.radialGradient(
                    listOf(Color(0x3508080C), Color(0x65101018)),
                    center = Offset(SHELL_W / 2f, SHELL_H / 2f),
                    radius = SHELL_W * 0.6f
                ),
                topLeft = Offset(WALL, WALL),
                size = Size(SHELL_W - 2 * WALL, SHELL_H - 2 * WALL),
                cornerRadius = CornerRadius(SHELL_R, SHELL_R)
            )
            drawRoundRect(Color(0x20FFFFFF), topLeft = Offset(WALL + 0.4f, WALL + 0.4f), size = Size(SHELL_W - 2 * WALL - 0.8f, SHELL_H - 2 * WALL - 0.8f), cornerRadius = CornerRadius(SHELL_R, SHELL_R), style = Stroke(0.8f))
            // Smoked acrylic is the same material family as the clear shell, just loaded with
            // tint, so it gets the same grazing-angle brightening — weaker, because the tint eats
            // some of the reflection.
            for (i in 0 until 5) {
                val inset = WALL + i * 0.6f
                val k = 1f - i / 5f
                drawRoundRect(
                    Color.White.copy(alpha = 0.045f * k * k),
                    topLeft = Offset(inset, inset),
                    size = Size(SHELL_W - 2 * inset, SHELL_H - 2 * inset),
                    cornerRadius = CornerRadius(SHELL_R, SHELL_R),
                    style = Stroke(0.6f)
                )
            }
            drawShellAccentGraphics(t, strength = 0.6f)
        }

        PlasticType.MATTE_COMPOSITE -> {
            // Dense micro-pebble particulate texture (stippled structural ABS matrix)
            var px = WALL + 1.2f
            while (px < SHELL_W - WALL - 1.2f) {
                var py = WALL + 1.2f
                while (py < SHELL_H - WALL - 1.2f) {
                    val hash = ((px * 73.1f + py * 91.7f).toInt() % 11)
                    if (hash < 3) {
                        drawCircle(Color(0x22FFFFFF), 0.16f, Offset(px + (hash * 0.1f), py))
                    } else if (hash > 7) {
                        drawCircle(Color(0x33000000), 0.18f, Offset(px - (hash * 0.08f), py))
                    }
                    py += 2.2f
                }
                px += 2.2f
            }
            drawRoundRect(
                Color(0x18000000),
                topLeft = Offset(WALL, WALL),
                size = Size(SHELL_W - 2 * WALL, SHELL_H - 2 * WALL),
                cornerRadius = CornerRadius(SHELL_R, SHELL_R)
            )
        }

        PlasticType.SATIN_POLYSTYRENE -> {
            // Warm translucent eggshell diffusion with subtle vintage mold striations
            drawRoundRect(
                Brush.verticalGradient(
                    listOf(Color(0x22FFFFFF), Color(0x10FFFFFF), Color(0x18000000)),
                    startY = WALL,
                    endY = SHELL_H - WALL
                ),
                topLeft = Offset(WALL, WALL),
                size = Size(SHELL_W - 2 * WALL, SHELL_H - 2 * WALL),
                cornerRadius = CornerRadius(SHELL_R, SHELL_R)
            )
            var sy = WALL + 1.0f
            while (sy < SHELL_H - WALL) {
                drawLine(Color(0x12000000), Offset(WALL, sy), Offset(SHELL_W - WALL, sy), 0.10f)
                sy += 1.8f
            }
        }

        PlasticType.METALLIC_FLAKE -> {
            // Luxurious metallic flake shimmer matrix & anisotropic luster
            val sheenMetallic = Path().apply {
                moveTo(sweepX + 20f, 0f); lineTo(sweepX + 45f, 0f)
                lineTo(sweepX + 15f, SHELL_H); lineTo(sweepX - 10f, SHELL_H); close()
            }
            drawPath(sheenMetallic, Color(0x30FFEAA0))
            // Metal flake only fires where the light is actually on it — that selective twinkle is
            // what separates a flake finish from a flat gold fill. Flakes far from the travelling
            // band stay dull; ones inside it go hot.
            var fx = WALL + 1.5f
            while (fx < SHELL_W - WALL - 1.5f) {
                var fy = WALL + 1.5f
                while (fy < SHELL_H - WALL - 1.5f) {
                    val sparkle = ((fx * 47.3f + fy * 61.9f).toInt() % 7) == 0
                    if (sparkle) {
                        // Distance from this flake to the sweep band, measured along the band's slant.
                        val bandX = sweepX + 30f - (fy / SHELL_H) * 25f
                        val d = kotlin.math.abs(fx - bandX)
                        val hot = (1f - (d / 22f)).coerceIn(0f, 1f)
                        drawCircle(Color(0xFFFFF8D0).copy(alpha = 0.18f + 0.62f * hot * hot), 0.22f + 0.14f * hot, Offset(fx, fy))
                    }
                    fy += 2.8f
                }
                fx += 2.8f
            }
        }

        PlasticType.PHOSPHOR_GLOW -> {
            // Radioactive phosphor luminescence (inner glowing halo radiating from hubs & window)
            drawCircle(Brush.radialGradient(listOf(t.accent.copy(alpha = 0.35f), Color.Transparent), center = Offset(HUB_L_X, HUB_Y), radius = HUB_R * 2.2f), HUB_R * 2.2f, Offset(HUB_L_X, HUB_Y))
            drawCircle(Brush.radialGradient(listOf(t.accent.copy(alpha = 0.35f), Color.Transparent), center = Offset(HUB_R_X, HUB_Y), radius = HUB_R * 2.2f), HUB_R * 2.2f, Offset(HUB_R_X, HUB_Y))
            drawRoundRect(t.accent.copy(alpha = 0.25f), topLeft = Offset(WALL, WALL), size = Size(SHELL_W - 2 * WALL, SHELL_H - 2 * WALL), cornerRadius = CornerRadius(SHELL_R, SHELL_R), style = Stroke(1.2f))
        }
    }

    // 2) Bottom plate (the "mouth" assembly) — the trapezoid moulding that carries the head,
    //    pinch-roller and capstan openings.
    val plate = Path().apply {
        moveTo(17.0f, SHELL_H); lineTo(19.8f, 50.6f); lineTo(81.8f, 50.6f); lineTo(84.6f, SHELL_H); close()
    }
    drawPath(plate, Color(0x24000000))
    drawPath(plate, t.engrave, style = Stroke(0.28f))
    // Head opening — dead centre of the bottom edge; the tape runs along its top lip.
    drawRoundRect(Color(0xCC0A0A0A), topLeft = Offset(45.3f, 59.6f), size = Size(11.0f, SHELL_H - 59.6f), cornerRadius = CornerRadius(0.8f, 0.8f))
    // Pinch-roller openings flanking the head opening.
    drawRoundRect(Color(0xCC0A0A0A), topLeft = Offset(34.6f, 60.2f), size = Size(8.5f, SHELL_H - 60.2f), cornerRadius = CornerRadius(0.8f, 0.8f))
    drawRoundRect(Color(0xCC0A0A0A), topLeft = Offset(58.5f, 60.2f), size = Size(8.5f, SHELL_H - 60.2f), cornerRadius = CornerRadius(0.8f, 0.8f))
    // Capstan holes — directly below each hub centre (42.5 mm apart, same as the spindles).
    drawCapstanHole(HUB_L_X, 60.6f, t)
    drawCapstanHole(HUB_R_X, 60.6f, t)
    // Small erase-head / azimuth notches near the corners of the bottom edge.
    drawRoundRect(Color(0xAA0A0A0A), topLeft = Offset(18.9f, 61.1f), size = Size(5.5f, SHELL_H - 61.1f), cornerRadius = CornerRadius(0.6f, 0.6f))
    drawRoundRect(Color(0xAA0A0A0A), topLeft = Offset(77.2f, 61.1f), size = Size(5.5f, SHELL_H - 61.1f), cornerRadius = CornerRadius(0.6f, 0.6f))
}

/**
 * The tape mechanism — reels, ribbon, guide rollers, felt pad — drawn in its own layer so it can
 * carry a lighter dose of the plastic lens/warp than the background art behind it (it sits right
 * up against the inside of the shell, so it should barely warp, not not-at-all) while the shell's
 * own walls/plate/window-frame/screws (drawn in the base and overlay layers) stay perfectly crisp.
 */
private fun DrawScope.drawCassetteMechanismMm(progress: Float, angleL: Float, angleR: Float, tapeLinearDist: Float, t: TapeTheme, g: TapeGrade) {
    // Physically-open mechanism visibility. A real shell only exposes the transport through
    // the label window and the bottom-edge head/pinch-roller/capstan cutouts — never through
    // solid plastic (see mechanismVisibilityPath). Clear/smoked shells are the one exception:
    // they're genuinely see-through, so the whole cavity qualifies. This single clipped pass
    // replaces what used to be a duplicate reel+roller+tangent computation drawn twice per frame
    // (once here unclipped, once again inside drawMagneticTapeTransport) — halving mechanism draw
    // cost and removing the double-imposition that showed through opaque shells everywhere.
    clipPath(mechanismVisibilityPath(t)) {
        // Unified Magnetic Tape Transport System (Reels, Guides, Ribbon, and Forward Oxide Motion)
        drawMagneticTapeTransport(progress, angleL, angleR, tapeLinearDist, t, g)

        // Felt Pressure Pad on Phosphor Bronze Leaf Spring (only ever visible through the head opening)
        drawLine(Color(0xFFB0B6BA), Offset(42.8f, 57.1f), Offset(58.8f, 57.1f), 0.5f)
        drawRoundRect(Color(0xFFB7A27A), topLeft = Offset(47.6f, 57.5f), size = Size(6.4f, 1.8f), cornerRadius = CornerRadius(0.5f, 0.5f))
        drawRoundRect(Color(0x66403010), topLeft = Offset(47.6f, 57.5f), size = Size(6.4f, 1.8f), cornerRadius = CornerRadius(0.5f, 0.5f), style = Stroke(0.22f))
    }
}

/** Window frame + counter, screws, branding — the plastic's own top surface, drawn last so it sits
 *  over the mechanism layer, and never warped (it's the plastic, not something seen through it). */
private fun DrawScope.drawCassetteOverlayMm(t: TapeTheme, counterText: String, light: Float, g: TapeGrade) {
    // 9) Label-side viewing window, centred on the hub line — its frame sits over the packs, and a
    //    couple of diagonal glass streaks sell the clear pane. The time counter is molded into the
    //    window itself (drawn after the fill, before the streaks/border below) so those glass
    //    highlights and the frame both sit on TOP of the digits — read as behind the plastic.
    val winTL = Offset(50.8f - WINDOW_W / 2f, HUB_Y - WINDOW_H / 2f)
    drawRoundRect(Color(0x14FFFFFF), topLeft = winTL, size = Size(WINDOW_W, WINDOW_H), cornerRadius = CornerRadius(2.5f, 2.5f))
    drawWindowCounter(t, counterText, winTL)
    clipPath(Path().apply { addRoundRect(RoundRect(winTL.x, winTL.y, winTL.x + WINDOW_W, winTL.y + WINDOW_H, CornerRadius(2.5f, 2.5f))) }) {
        // Glass haze. A tape that has been through a cheap deck a hundred times has a scuffed,
        // slightly milky window; mastering stock out of the case is water-clear. Scaled by the
        // grade's wear so the pane itself reports the file's quality.
        if (g.wear > 0.05f) {
            drawRoundRect(
                Color(0xFFBFC6C2).copy(alpha = 0.05f + 0.09f * g.wear),
                topLeft = winTL, size = Size(WINDOW_W, WINDOW_H), cornerRadius = CornerRadius(2.5f, 2.5f)
            )
        }
        // The two reflection streaks travel with the key light rather than sitting frozen — this
        // is the single detail that most stops the window reading as a painted-on shape.
        val gx = winTL.x + light * (WINDOW_W - 6f)
        drawLine(Color(0x2EFFFFFF), Offset(gx + 4f, winTL.y + WINDOW_H + 2f), Offset(gx + 14f, winTL.y - 2f), 1.1f)
        drawLine(Color(0x1AFFFFFF), Offset(gx + 9f, winTL.y + WINDOW_H + 2f), Offset(gx + 21f, winTL.y - 2f), 2.6f)
        // A crisp hot edge only the good plastics get.
        if (g.q > 0.5f) {
            drawLine(Color.White.copy(alpha = 0.10f + 0.16f * g.q), Offset(gx + 6.5f, winTL.y + WINDOW_H + 2f), Offset(gx + 16.5f, winTL.y - 2f), 0.35f)
        }
    }
    drawRoundRect(Color(0x88000000), topLeft = Offset(winTL.x - 0.5f, winTL.y - 0.5f), size = Size(WINDOW_W + 1f, WINDOW_H + 1f), cornerRadius = CornerRadius(2.8f, 2.8f), style = Stroke(0.9f))
    drawRoundRect(t.bevelHi.copy(alpha = 0.55f), topLeft = winTL, size = Size(WINDOW_W, WINDOW_H), cornerRadius = CornerRadius(2.5f, 2.5f), style = Stroke(0.3f))

    // 9) Shell edge bevels + the four corner assembly screws. (A real fifth centre-bottom screw
    // used to sit at mm(50.8, 52.5) — right in the mouth/head-block area the Tape Deck Controls
    // Bar overlay also occupies (that row spans almost that whole width), so it always rendered
    // as a stray metal disc peeking out from behind the Play/Pause glyph. Corner-only is still a
    // real, common cassette screw layout, and there's no spot left in that band the control row
    // doesn't cover.)
    drawRoundRect(t.bevelHi.copy(alpha = 0.9f), topLeft = Offset.Zero, size = Size(SHELL_W, SHELL_H), cornerRadius = CornerRadius(SHELL_R, SHELL_R), style = Stroke(0.45f))
    drawRoundRect(t.bevelLo.copy(alpha = 0.7f), topLeft = Offset(0.55f, 0.55f), size = Size(SHELL_W - 1.1f, SHELL_H - 1.1f), cornerRadius = CornerRadius(SHELL_R, SHELL_R), style = Stroke(0.28f))
    for ((sx, sy) in listOf(4.3f to 4.3f, SHELL_W - 4.3f to 4.3f, 4.3f to 59.2f, SHELL_W - 4.3f to 59.2f)) {
        if (t.welded) drawMeltWeld(sx, sy, t) else drawScrew(sx, sy, t)
    }

    // 10) Branded layout language — printed decor amalgamated from real artist/blank tapes.
    // 10) Branded layout language — authentic background paper labels, accent rules, and badges
    when (t.layout) {
        TapeLayout.CLEAR -> Unit
        TapeLayout.STUDIO -> {
            // TDK/Maxell engineering: warm vintage paper strip, inset frame rule, accent grade-slash, side-A dot
            drawRoundRect(Color(0xF2F4F1E8), topLeft = Offset(2.8f, 2.8f), size = Size(SHELL_W - 5.6f, 13.6f), cornerRadius = CornerRadius(1.2f, 1.2f))
            drawRoundRect(Color(0xFF1A1A1E), topLeft = Offset(3.4f, 3.4f), size = Size(SHELL_W - 6.8f, 12.4f), cornerRadius = CornerRadius(1f, 1f), style = Stroke(0.28f))
            drawPath(Path().apply { moveTo(58f, 3.4f); lineTo(63.5f, 3.4f); lineTo(69.5f, 15.8f); lineTo(64f, 15.8f); close() }, t.accent)
            drawCircle(t.accent, 2.5f, Offset(7.4f, 22.4f))
            mmText("A", 7.4f, 23.9f, 4.2f, Color(0xFF14141B), center = true)
        }
        TapeLayout.MAJOR84 -> {
            // Epic/Warner/EMI pressing: thin full-width printed rules, logo block, side "1"
            drawLine(t.ink.copy(alpha = 0.85f), Offset(3f, 1.7f), Offset(SHELL_W - 3f, 1.7f), 0.3f)
            drawLine(t.ink.copy(alpha = 0.85f), Offset(3f, 14.6f), Offset(SHELL_W - 3f, 14.6f), 0.3f)
            drawRoundRect(t.accent, topLeft = Offset(91.6f, 3.6f), size = Size(6f, 6f), cornerRadius = CornerRadius(0.8f, 0.8f))
            mmText("M", 94.6f, 8.2f, 4.2f, Color(0xFF141418), center = true)
            mmText("1", 96.6f, 23.4f, 3.2f, t.ink)
        }
        TapeLayout.NEON -> {
            // Wu-Tang/NWA inversion: knockout side badge, window ring
            drawRoundRect(t.accent, topLeft = Offset(93.2f, 3f), size = Size(4.6f, 4.6f), cornerRadius = CornerRadius(0.6f, 0.6f))
            mmText("A", 95.5f, 6.7f, 3.4f, Color(0xFF101014), center = true)
            drawRoundRect(t.accent.copy(alpha = 0.75f), topLeft = Offset(50.8f - WINDOW_W / 2f - 0.9f, HUB_Y - WINDOW_H / 2f - 0.9f), size = Size(WINDOW_W + 1.8f, WINDOW_H + 1.8f), cornerRadius = CornerRadius(3f, 3f), style = Stroke(0.45f))
        }
        TapeLayout.MERCH -> {
            // Swift/Eilish one-colour object: tone-on-tone hairline hub rings, lowercase side
            drawCircle(t.ink, HUB_R + 1.6f, Offset(HUB_L_X, HUB_Y), style = Stroke(0.4f))
            drawCircle(t.ink, HUB_R + 1.6f, Offset(HUB_R_X, HUB_Y), style = Stroke(0.4f))
            mmText("side a", 8f, 22.8f, 2.3f, t.ink, bold = false)
        }
    }

    // 10b) The secret shell's own mark, on the clear band right of the right-hand hub.
    if (t.motif39) drawHione39Badge(light)

    // 11) The grade stamp — the real IEC type block every cassette carries, filled in with what
    //     THIS file actually measured. Nothing is printed until TrackTech has answered.
    if (g.isKnown) drawGradeStamp(t, g)

    // 12) Physical wear, last of all, so it sits on top of the plastic, the print and the window
    //     alike — the way dust and scuffs actually do on a real object.
    drawShellWear(t, g)
}

/**
 * The IEC grade block, bottom-left of the label face where the type/bias stamp genuinely lives on
 * a real shell. Reads e.g.
 *
 *     TYPE IV                     ← the grade this file earned
 *     METAL PARTICLE POSITION     ← the formulation
 *     70µs EQ · 24 bit · 96.0 kHz ← the measured numbers, verbatim
 *
 * The accent slab behind the type name gets brighter and more saturated as the grade climbs, so
 * even unread at a glance the good tapes look expensive and the 128 kbps rips look cheap.
 */
private fun DrawScope.drawGradeStamp(t: TapeTheme, g: TapeGrade) {
    // POSITION IS CONSTRAINED, do not move this down. The transport control row is laid out at
    // screen offset -22.5 mm from centre, i.e. mm y ~54, and its buttons are tall enough to cover
    // roughly y 48..60. The bottom plate starts at y 50.6. The window's lower edge is at y 34.9.
    // That leaves mm y 36..47 as the only band on the label face that is clear of both, which is
    // where this sits. The first version ran to y 55 and the last two lines were simply invisible
    // behind the Play/Pause glyph.
    val x = 5.5f
    val y = 37.4f
    val slabW = 15.5f
    val slabH = 4.6f
    // Premium grades get a bright, fully-saturated slab; ferric gets a flat muted one.
    val slab = lerp(lerp(t.ink, Color(0xFF6A6A6A), 0.55f), t.accent, g.q)
    drawRoundRect(
        slab.copy(alpha = 0.62f + 0.33f * g.q),
        topLeft = Offset(x, y), size = Size(slabW, slabH), cornerRadius = CornerRadius(0.7f, 0.7f)
    )
    // Only the top grades get the foil-ish bright rule under the slab.
    if (g.q > 0.7f) {
        drawLine(t.bevelHi.copy(alpha = 0.75f), Offset(x, y + slabH + 0.5f), Offset(x + slabW, y + slabH + 0.5f), 0.30f)
    }
    // Contrast comes off the slab's OWN luminance, not off the grade. The slab colour is a blend
    // of the theme ink and the theme accent, so on GOLD it lands bright and on NEON HIONE it lands
    // dark — picking the label colour from g.q instead put dark text on a dark slab half the time.
    val slabLum = slab.red * 0.299f + slab.green * 0.587f + slab.blue * 0.114f
    mmText(g.typeLabel, x + slabW / 2f, y + 3.4f, 3.2f, if (slabLum > 0.5f) Color(0xFF101014) else Color.White, center = true)
    mmText(g.formulation, x, y + slabH + 3.1f, 1.85f, t.ink.copy(alpha = 0.88f), bold = false)
    mmText("${g.eq} · ${g.detail}", x, y + slabH + 5.5f, 1.85f, t.ink.copy(alpha = 0.72f), bold = false)
}

/**
 * Grime, proportional to grade. A pristine render is the other big tell that an object is drawn
 * rather than photographed — real plastic that has lived in a bag has hairline scratches, settled
 * dust, rubbed-dull corners and finger oil on the flat faces.
 *
 * All of it is deterministic (hashed off position, no RNG), so the same tape always wears the same
 * way frame to frame instead of boiling. A 24/96 master gets almost none of this; a 128 kbps rip
 * gets the lot.
 */
private fun DrawScope.drawShellWear(t: TapeTheme, g: TapeGrade) {
    val w = g.wear
    if (w < 0.04f) return
    clipPath(Path().apply { addRoundRect(RoundRect(0f, 0f, SHELL_W, SHELL_H, CornerRadius(SHELL_R, SHELL_R))) }) {
        // 1) Hairline scratches — short, shallow, mostly aligned with how a tape slides in and out
        //    of a case, so they run along the shell rather than in all directions.
        val n = (34 * w).toInt()
        for (i in 0 until n) {
            val h1 = tapeHash(i * 1.7f, 3.1f)
            val h2 = tapeHash(i * 2.9f, 7.7f)
            val h3 = tapeHash(i * 4.3f, 11.3f)
            val sx = h1 * SHELL_W
            val sy = h2 * SHELL_H
            val len = 1.5f + h3 * 9f
            val slant = (h3 - 0.5f) * 0.55f
            drawLine(
                Color.White.copy(alpha = 0.03f + 0.05f * h3 * w),
                Offset(sx, sy), Offset(sx + len, sy + len * slant), 0.11f
            )
            drawLine(
                Color.Black.copy(alpha = 0.05f * w),
                Offset(sx, sy + 0.12f), Offset(sx + len, sy + len * slant + 0.12f), 0.09f
            )
        }
        // 2) Settled dust — tiny specks, brighter than the plastic, denser in the moulded corners
        //    where a cloth never reaches.
        val d = (54 * w).toInt()
        for (i in 0 until d) {
            val h1 = tapeHash(i * 3.7f, 21.5f)
            val h2 = tapeHash(i * 5.1f, 29.9f)
            val h3 = tapeHash(i * 6.7f, 37.1f)
            drawCircle(
                Color(0xFFEFEFE8).copy(alpha = 0.10f + 0.22f * h3 * w),
                0.10f + h3 * 0.16f,
                Offset(h1 * SHELL_W, h2 * SHELL_H)
            )
        }
        // 3) Rubbed corners — the four corners of a shell go dull and pale first, because they are
        //    what drags on everything.
        for ((cx, cy) in listOf(0f to 0f, SHELL_W to 0f, 0f to SHELL_H, SHELL_W to SHELL_H)) {
            drawCircle(
                Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.09f * w), Color.Transparent),
                    center = Offset(cx, cy), radius = 13f
                ),
                13f, Offset(cx, cy)
            )
        }
        // 4) Finger oil on the two big flat faces either side of the window — broad, very low
        //    contrast smudges. Barely visible alone, but they stop the plastic reading as glass.
        if (w > 0.35f) {
            for ((fx, fy, fr) in listOf(Triple(24f, 40f, 11f), Triple(76f, 38f, 9f), Triple(62f, 12f, 7f))) {
                drawCircle(
                    Brush.radialGradient(
                        listOf(Color.White.copy(alpha = 0.045f * w), Color.Transparent),
                        center = Offset(fx, fy), radius = fr
                    ),
                    fr, Offset(fx, fy)
                )
            }
        }
        // 5) Age cast — the cheap grades yellow. Real ABS does this under UV, and it is the exact
        //    look of a ferric tape that has sat in a car door for twenty years.
        if (w > 0.5f) {
            drawRoundRect(
                Color(0xFFC8A85A).copy(alpha = 0.05f * (w - 0.5f) * 2f),
                topLeft = Offset.Zero, size = Size(SHELL_W, SHELL_H),
                cornerRadius = CornerRadius(SHELL_R, SHELL_R)
            )
        }
    }
}

/**
 * Printed color-block/triangle accents molded into the inside face of a translucent shell —
 * real clear-shell cassettes (Memorex dBS etc.) are rarely just plain clear plastic; they carry
 * bold flat-color print shapes (a diagonal bar under the label, a triangle wedge near a reel) that
 * read as vivid color floating behind the mechanism when you look through the shell. Drawn once,
 * BEFORE the reel mechanism/plate/screws (so those correctly sit on top of it, same as the real
 * object), using the theme's own accent color so it stays a recognizable part of that specific
 * cassette's palette rather than a generic decal.
 */
private fun DrawScope.drawShellAccentGraphics(t: TapeTheme, strength: Float) {
    val bar = Path().apply { moveTo(4f, 2f); lineTo(46f, 2f); lineTo(22f, 40f); lineTo(-6f, 40f); close() }
    drawPath(bar, t.accent.copy(alpha = 0.34f * strength))
    val triangle = Path().apply {
        moveTo(HUB_L_X - 14f, HUB_Y + 20f)
        lineTo(HUB_L_X + 4f, HUB_Y + 20f)
        lineTo(HUB_L_X - 8f, HUB_Y + 34f)
        close()
    }
    drawPath(triangle, t.accent.copy(alpha = 0.42f * strength))
}

/**
 * Embossed capstan bore: a real hole punched through glossy plastic. Moulded depression ring
 * around it, interior plunging to black (shadow biased to the top — light comes from above),
 * far wall catching a bright sliver at the bottom of the bore, top inner edge occluded, and a
 * chamfered rim that's specular on the bottom lip and shadowed on top.
 */
private fun DrawScope.drawCapstanHole(cx: Float, cy: Float, t: TapeTheme) {
    val c = Offset(cx, cy)
    fun arc(color: Color, start: Float, sweep: Float, r: Float, w: Float) =
        drawArc(color, start, sweep, false, topLeft = Offset(cx - r, cy - r), size = Size(r * 2f, r * 2f), style = Stroke(w))
    // moulded depression around the bore: dark sink ring + a light lip below it
    drawCircle(Brush.radialGradient(listOf(Color(0x00000000), Color(0x4D000000)), center = c, radius = CAPSTAN_R * 1.6f), CAPSTAN_R * 1.6f, c)
    arc(t.bevelHi.copy(alpha = 0.5f), 25f, 130f, CAPSTAN_R * 1.62f, 0.3f)
    // the bore: interior falls to pure black, darker toward the top wall
    drawCircle(Brush.radialGradient(listOf(Color(0xFF000000), Color(0xFF16100A)), center = Offset(cx - 0.4f, cy - 1.0f), radius = CAPSTAN_R * 1.5f), CAPSTAN_R, c)
    // far (bottom) inner wall catching light down inside the bore
    arc(Color(0x59FFFFFF), 45f, 90f, CAPSTAN_R - 0.55f, 0.7f)
    arc(Color(0x26FFFFFF), 30f, 120f, CAPSTAN_R - 1.0f, 0.5f)
    // top inner edge: hard occlusion where the rim shades the bore
    arc(Color(0xC7000000), 195f, 150f, CAPSTAN_R - 0.35f, 0.65f)
    // chamfered rim: bright specular lip on the bottom, shadowed crest on top
    arc(t.bevelHi.copy(alpha = 0.95f), 20f, 140f, CAPSTAN_R, 0.4f)
    arc(Color(0x8C000000), 190f, 160f, CAPSTAN_R, 0.4f)
    // tiny specular glints at the 4-5 o'clock chamfer
    arc(Color(0xB3FFFFFF), 55f, 28f, CAPSTAN_R, 0.5f)
}

/** Premium-shell assembly screw: moulded boss, countersunk well, domed Phillips head with specular. */
private fun DrawScope.drawScrew(sx: Float, sy: Float, t: TapeTheme) {
    val c = Offset(sx, sy)
    drawCircle(t.engrave, 2.2f, c, style = Stroke(0.25f))                          // moulded boss ring
    drawCircle(Brush.radialGradient(listOf(Color(0x66000000), Color(0x22000000)), center = c, radius = 2.0f), 1.9f, c)   // countersink well
    // domed metal head, lit upper-left
    drawCircle(Brush.radialGradient(listOf(Color(0xFFEFF2F4), Color(0xFF9AA2A8), Color(0xFF565C60)), center = Offset(sx - 0.5f, sy - 0.5f), radius = 2.1f), 1.45f, c)
    drawCircle(Color(0xAA30363A), 1.45f, c, style = Stroke(0.18f))
    rotate(37f, c) {   // Phillips cross with depth: dark slot + lower-right light catch
        drawLine(Color(0xE0101314), Offset(sx - 1.0f, sy), Offset(sx + 1.0f, sy), 0.34f)
        drawLine(Color(0xE0101314), Offset(sx, sy - 1.0f), Offset(sx, sy + 1.0f), 0.34f)
        drawLine(Color(0x66FFFFFF), Offset(sx + 0.12f, sy + 0.12f), Offset(sx + 0.95f, sy + 0.12f), 0.12f)
    }
    drawArc(Color(0xB3FFFFFF), 205f, 70f, false, topLeft = Offset(sx - 1.15f, sy - 1.15f), size = Size(2.3f, 2.3f), style = Stroke(0.22f))  // specular crescent
}

/** Budget-shell melt weld: an irregular remelted dimple in the plastic instead of a screw. */
private fun DrawScope.drawMeltWeld(sx: Float, sy: Float, t: TapeTheme) {
    val c = Offset(sx, sy)
    // slightly raised, discolored remelt pool (two offset blobs = irregular)
    drawCircle(Brush.radialGradient(listOf(t.shellLo.copy(alpha = 0.95f), t.shellHi.copy(alpha = 0.4f)), center = Offset(sx - 0.3f, sy - 0.3f), radius = 1.7f), 1.45f, c)
    drawCircle(t.shellLo.copy(alpha = 0.55f), 1.0f, Offset(sx + 0.35f, sy + 0.25f))
    drawCircle(Color(0x59000000), 1.45f, c, style = Stroke(0.2f))                  // sink line around the pool
    drawCircle(Color(0x40000000), 0.45f, Offset(sx + 0.2f, sy + 0.15f))            // shrink pit
    drawArc(Color(0x4DFFFFFF), 200f, 85f, false, topLeft = Offset(sx - 1.05f, sy - 1.05f), size = Size(2.1f, 2.1f), style = Stroke(0.22f))  // gloss catch
}

/**
 * Text in mm-space via the native canvas. GOTCHA: Paint.textSize of ~2 units gets hinted/collapsed
 * by the glyph rasterizer before the canvas scale applies, which garbled every small label into
 * overlapping mush — so render at 10× size and scale back down around the anchor point.
 */
private fun DrawScope.mmText(
    s: String, x: Float, y: Float, sizeMm: Float, color: Color,
    bold: Boolean = true, center: Boolean = false, alignRight: Boolean = false,
    glowColor: Color? = null, glowRadiusMm: Float = 0.6f
) {
    val up = 10f
    val p = android.graphics.Paint().apply {
        this.color = color.toArgb(); textSize = sizeMm * up; isAntiAlias = true; isLinearText = true
        typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        textAlign = when { center -> android.graphics.Paint.Align.CENTER; alignRight -> android.graphics.Paint.Align.RIGHT; else -> android.graphics.Paint.Align.LEFT }
        if (glowColor != null) setShadowLayer(glowRadiusMm * up, 0f, 0f, glowColor.toArgb())
    }
    drawContext.canvas.nativeCanvas.apply {
        save(); translate(x, y); scale(1f / up, 1f / up); drawText(s, 0f, 0f, p); restore()
    }
}

/**
 * The elapsed/total counter, molded right into the tape window as a small inset LCD/LED strip —
 * behind the same clear plastic (glass streaks + frame draw over it, see the caller) as the reel
 * view, styled per the deck's own material: a warm glowing readout for clear/phosphor/neon shells,
 * a plain amber LCD-style readout for everything opaque.
 */
private fun DrawScope.drawWindowCounter(t: TapeTheme, text: String, winTL: Offset) {
    val glows = t.plasticType == PlasticType.PHOSPHOR_GLOW || t.plasticType == PlasticType.CLEAR_POLYCARBONATE || t.layout == TapeLayout.NEON
    val stripH = 4.0f
    val stripTL = Offset(winTL.x + 3f, winTL.y + WINDOW_H - stripH - 0.7f)
    val stripSize = Size(WINDOW_W - 6f, stripH)
    // Dark LCD/LED backing — a real small digital readout inset within the clear window pane.
    drawRoundRect(Color(0xD90A0A0A), topLeft = stripTL, size = stripSize, cornerRadius = CornerRadius(0.8f, 0.8f))
    drawRoundRect(Color(0x4D000000), topLeft = stripTL, size = stripSize, cornerRadius = CornerRadius(0.8f, 0.8f), style = Stroke(0.18f))
    val textColor = if (glows) t.accent else Color(0xFFE8C878)
    mmText(
        text,
        stripTL.x + stripSize.width / 2f,
        stripTL.y + stripSize.height * 0.74f,
        2.5f,
        textColor,
        center = true,
        glowColor = if (glows) t.accent else null
    )
}

/**
 * Where the transport mechanism is actually allowed to show through the shell. Clear/smoked
 * plastics are genuinely see-through so the whole cavity qualifies; every opaque shell has only
 * two real openings onto the tape path — the label window and the bottom-edge head/pinch-roller/
 * capstan/erase-notch cutouts (identical rects to the "bottom plate" pass in drawCassetteMm) — so
 * reel packs and ribbon can never bleed across solid plastic, screw bosses, or printed labels.
 */
private fun mechanismVisibilityPath(t: TapeTheme): Path {
    if (t.plasticType == PlasticType.CLEAR_POLYCARBONATE || t.plasticType == PlasticType.SMOKED_ACRYLIC) {
        return Path().apply {
            addRoundRect(RoundRect(WALL, WALL, SHELL_W - WALL, SHELL_H - WALL, CornerRadius(SHELL_R, SHELL_R)))
        }
    }
    val winTL = Offset(50.8f - WINDOW_W / 2f, HUB_Y - WINDOW_H / 2f)
    return Path().apply {
        addRoundRect(RoundRect(winTL.x, winTL.y, winTL.x + WINDOW_W, winTL.y + WINDOW_H, CornerRadius(2.5f, 2.5f)))  // label window
        addRoundRect(RoundRect(45.3f, 59.6f, 56.3f, SHELL_H, CornerRadius(0.8f, 0.8f)))   // head opening
        addRoundRect(RoundRect(34.6f, 60.2f, 43.1f, SHELL_H, CornerRadius(0.8f, 0.8f)))   // pinch roller L
        addRoundRect(RoundRect(58.5f, 60.2f, 67.0f, SHELL_H, CornerRadius(0.8f, 0.8f)))   // pinch roller R
        addOval(Rect(HUB_L_X - CAPSTAN_R, 60.6f - CAPSTAN_R, HUB_L_X + CAPSTAN_R, 60.6f + CAPSTAN_R))  // capstan L
        addOval(Rect(HUB_R_X - CAPSTAN_R, 60.6f - CAPSTAN_R, HUB_R_X + CAPSTAN_R, 60.6f + CAPSTAN_R))  // capstan R
        addRoundRect(RoundRect(18.9f, 61.1f, 24.4f, SHELL_H, CornerRadius(0.6f, 0.6f)))   // erase/azimuth notch L
        addRoundRect(RoundRect(77.2f, 61.1f, 82.7f, SHELL_H, CornerRadius(0.6f, 0.6f)))   // erase/azimuth notch R
    }
}

/**
 * Photorealistic Magnetic Tape Transport System:
 * Supply Reel (L), Take-up Reel (R), corner guide rollers, and an unbroken
 * contiguous magnetic ribbon in transit with procedural non-repeating oxide particles flowing
 * in the physically correct forward direction (Left Supply -> Head Mouth -> Right Take-up).
 */
private fun DrawScope.drawMagneticTapeTransport(
    progress: Float,
    angleL: Float,
    angleR: Float,
    tapeLinearDist: Float,
    t: TapeTheme,
    g: TapeGrade
) {
    // 1) Physical tape volume conservation (C-90 spec: A_L + A_R = Constant)
    fun calcPackRadius(fill: Float): Float {
        val f = fill.coerceIn(0f, 1f)
        return sqrt(PACK_MIN_R * PACK_MIN_R + (PACK_MAX_R * PACK_MAX_R - PACK_MIN_R * PACK_MIN_R) * f)
    }
    val leftR = calcPackRadius(1f - progress)
    val rightR = calcPackRadius(progress)

    // 2) Guide roller centers and head mouth run
    val gL = Offset(GUIDE_L_X, GUIDE_Y)
    val gR = Offset(GUIDE_R_X, GUIDE_Y)
    val gL_bot = Offset(GUIDE_L_X, TAPE_RUN_Y)
    val gR_bot = Offset(GUIDE_R_X, TAPE_RUN_Y)

    // 3) Analytical external tangent calculations (Circle-to-Circle contact)
    val dxL = (GUIDE_L_X - HUB_L_X).toDouble()
    val dyL = (GUIDE_Y - HUB_Y).toDouble()
    val distL = sqrt(dxL * dxL + dyL * dyL)
    val uLx = dxL / distL
    val uLy = dyL / distL
    val nLx = -uLy
    val nLy = uLx
    val sinGammaL = ((leftR - GUIDE_ROLLER_R).toDouble() / distL).coerceIn(-1.0, 1.0)
    val cosGammaL = sqrt(1.0 - sinGammaL * sinGammaL)
    val tanNLx = cosGammaL * nLx + sinGammaL * uLx
    val tanNLy = cosGammaL * nLy + sinGammaL * uLy

    val pL = Offset((HUB_L_X + leftR * tanNLx).toFloat(), (HUB_Y + leftR * tanNLy).toFloat())
    val gL_tan = Offset((GUIDE_L_X + GUIDE_ROLLER_R * tanNLx).toFloat(), (GUIDE_Y + GUIDE_ROLLER_R * tanNLy).toFloat())

    val dxR = (GUIDE_R_X - HUB_R_X).toDouble()
    val dyR = (GUIDE_Y - HUB_Y).toDouble()
    val distR = sqrt(dxR * dxR + dyR * dyR)
    val uRx = dxR / distR
    val uRy = dyR / distR
    val nRx = uRy
    val nRy = -uRx
    val sinGammaR = ((rightR - GUIDE_ROLLER_R).toDouble() / distR).coerceIn(-1.0, 1.0)
    val cosGammaR = sqrt(1.0 - sinGammaR * sinGammaR)
    val tanNRx = cosGammaR * nRx + sinGammaR * uRx
    val tanNRy = cosGammaR * nRy + sinGammaR * uRy

    val pR = Offset((HUB_R_X + rightR * tanNRx).toFloat(), (HUB_Y + rightR * tanNRy).toFloat())
    val gR_tan = Offset((GUIDE_R_X + GUIDE_ROLLER_R * tanNRx).toFloat(), (GUIDE_Y + GUIDE_ROLLER_R * tanNRy).toFloat())

    // 4) Corner tape-guide roller base flanges
    for (g in listOf(gL, gR)) {
        drawCircle(Color(0x44000000), GUIDE_ROLLER_R + 0.4f, Offset(g.x + 0.25f, g.y + 0.35f))
        drawCircle(Brush.radialGradient(listOf(t.hubHi, t.hubLo), center = Offset(g.x - 0.8f, g.y - 0.8f), radius = GUIDE_ROLLER_R * 1.6f), GUIDE_ROLLER_R, g)
        drawCircle(t.hubLo, GUIDE_ROLLER_R, g, style = Stroke(0.25f))
    }

    // 5) In-Transit Magnetic Ribbon Path (drawn BEFORE the reels, not after — see the reel pass
    // below). The ribbon's drawn ends actually dive a bit past pL/pR, back INTO the reel pack
    // (pLu/pRu, pulled inward along the same tangent), so once the reels paint on top of this,
    // their own edge naturally buries that stroke's square line-cap instead of it sitting exposed
    // on top of the pack — no more visible "squared ending" where transit tape meets the spool.
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

    // Pass 1: Underneath ambient shadow for real 3D ribbon depth
    drawPath(ribbonPath, Color(0x8A000000), style = Stroke(1.6f))

    // Pass 2: Base Ferric/Chrome Oxide Magnetic Ribbon. Lifted noticeably brighter than the reel's
    // own darkest gradient stop — a real oxide ribbon under any ambient light reads as a lighter,
    // warmer strip than the shadowed wound pack, and needs that contrast to stay legible against
    // busy backdrops (clear-shell themes ghost album art straight through). Alpha ramps from 0 near
    // both ends (the 0f/1f stops) up to full within the first ~5% — a soft fade "stretch" so the
    // ribbon tapers into invisibility as it dives under the reel (reelDive above) instead of ending
    // on a hard, realism-breaking cap, even if the reel's own edge doesn't fully cover it.
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
    drawPath(ribbonPath, Color(0x80281808), style = Stroke(0.8f))

    // Pass 3: Longitudinal Specular Edge Sheen Highlight
    val sheenPath = Path().apply {
        moveTo(pLu.x - 0.12f, pLu.y)
        lineTo(gL_tan.x - 0.12f, gL_tan.y)
        quadraticBezierTo(GUIDE_L_X - GUIDE_ROLLER_R - 0.12f, TAPE_RUN_Y - 0.12f, gL_bot.x, TAPE_RUN_Y - 0.12f)
        lineTo(gR_bot.x, TAPE_RUN_Y - 0.12f)
        quadraticBezierTo(GUIDE_R_X + GUIDE_ROLLER_R + 0.12f, TAPE_RUN_Y - 0.12f, gR_tan.x + 0.12f, gR_tan.y)
        lineTo(pRu.x + 0.12f, pRu.y)
    }
    val sheenBrush = Brush.linearGradient(
        colorStops = arrayOf(
            0f to Color(0x00FFECC8),
            0.05f to Color(0x8CFFECC8),
            0.95f to Color(0x8CFFECC8),
            1f to Color(0x00FFECC8)
        ),
        start = pL, end = pR
    )
    drawPath(sheenPath, sheenBrush, style = Stroke(0.28f))

    // Pass 4: Forward-Flowing Procedural Non-Repeating Oxide Particles. Uses the SAME tapeHash()
    // the reel's oxide flecks are seeded from (not a locally-redefined lookalike) so the grain
    // reads as one continuous material's noise field crossing the reel/ribbon join, not two
    // different generators that happen to look similar.
    //
    // Walks the ACTUAL ribbonPath via PathMeasure instead of three hand-rolled straight-line
    // segments. The old three-segment version treated the corners at each guide roller as sharp
    // straight-to-straight joins, but the real path rounds them with a quadraticBezierTo — so the
    // dust, placed on the straight-line approximation, visibly drifted off the true (curved)
    // stroke right where it wraps the rollers. Measuring the real path guarantees the dust can
    // never separate from the tape it's sitting on, at the corners or anywhere else.
    val deterministicHash = ::tapeHash
    val measure = androidx.compose.ui.graphics.PathMeasure().apply { setPath(ribbonPath, false) }
    val totalLen = measure.length
    var s = 0.4f
    while (s < totalLen - 0.4f) {
        // Forward travel: subtract tapeLinearDist so points on tape move along increasing s
        val globalPos = s - tapeLinearDist
        val bucket = kotlin.math.floor(globalPos / 1.35f)
        val hLength = deterministicHash(bucket, 3.14f)
        val hLateral = deterministicHash(bucket, 7.89f)
        val hAlpha = deterministicHash(bucket, 13.57f)
        val hColor = deterministicHash(bucket, 23.41f)

        val macroWave = 0.5f + 0.5f * kotlin.math.sin((globalPos * 0.18f).toDouble()).toFloat()
        val streakLen = 0.4f + hLength * 1.6f
        val latOffset = (hLateral - 0.5f) * 0.45f
        val alpha = (0.12f + 0.45f * hAlpha) * (0.5f + 0.5f * macroWave)
        val color = if (hColor > 0.6f) Color(0xFFFFECC8) else if (hColor > 0.3f) Color(0xFFE8DAC4) else Color(0xFFCCA878)

        val pos = measure.getPosition(s)
        val tan = measure.getTangent(s)
        val dirX = tan.x; val dirY = tan.y
        val normX = -dirY; val normY = dirX
        val cx = pos.x + normX * latOffset
        val cy = pos.y + normY * latOffset
        drawLine(
            color = color.copy(alpha = alpha),
            start = Offset(cx - dirX * (streakLen / 2f), cy - dirY * (streakLen / 2f)),
            end = Offset(cx + dirX * (streakLen / 2f), cy + dirY * (streakLen / 2f)),
            strokeWidth = 0.18f + 0.10f * hAlpha
        )
        s += 1.1f + hLength * 0.4f
    }

    // 6) Guide Roller Center Steel Pins (drawn directly over the wrapped tape for precision mechanical look)
    for (g in listOf(gL, gR)) {
        drawCircle(Color(0x33000000), 1.1f, g)
        drawCircle(Color(0xFF1A1510), 0.75f, g)
        drawCircle(Color(0x66FFFFFF), 0.35f, Offset(g.x - 0.2f, g.y - 0.2f))
    }

    // 7) Reels drawn LAST, on top of the ribbon's buried ends (see reelDive above) — the pack's
    // own edge covers that overlap so the transit tape visibly disappears into the wound pack
    // instead of butting up against it with a hard-edged seam.
    drawReel(HUB_L_X, HUB_Y, leftR, angleL, t, g)
    drawReel(HUB_R_X, HUB_Y, rightR, angleR, t, g)
}

/** Cheap deterministic hash shared by the reel pack and the in-transit ribbon so both read as one
 *  continuous wound material instead of two different noise "dialects" meeting at a seam. */
private fun tapeHash(u: Float, seed: Float): Float {
    val x = u * 12.9898f + seed * 78.233f
    val sinVal = kotlin.math.sin(x.toDouble()).toFloat() * 43758.5453f
    return sinVal - kotlin.math.floor(sinVal)
}

/**
 * Cached, angle-independent reel-pack geometry (the spirals + oxide flecks). The wound-pack shape
 * only depends on cx/cy/tapeR — `angle` is applied afterward as a cheap matrix rotate() around it,
 * never baked into the points — so re-deriving ~1000+ trig'd path points and ~150-250 hashed fleck
 * positions from scratch every single animation frame (30fps × 2 reels) was pure wasted CPU/battery
 * for output that's IDENTICAL to the previous frame's. Cached per (reel, tapeR bucket) instead;
 * tapeR only actually changes while the user seeks, so in steady playback this is built once and
 * reused every frame after. Pure perf win — same geometry, same colors/alphas/widths, unchanged.
 */
private data class FleckSpec(val start: Offset, val end: Offset, val alpha: Float, val width: Float)
private data class ReelGeometry(val spiral1: Path, val spiral2: Path, val flecks: List<FleckSpec>)
private val reelGeometryCache = HashMap<Pair<Float, Int>, ReelGeometry>()

private fun buildReelGeometry(cx: Float, cy: Float, tapeR: Float, packDepth: Float): ReelGeometry {
    val numTurns = (packDepth * 2.6f).coerceIn(4f, 36f)
    val totalRad = numTurns * 2f * Math.PI.toFloat()
    val steps = (numTurns * 28).toInt()

    // Primary continuous spiral winding groove. Real wound tape isn't a mathematically perfect
    // spiral — winding tension varies turn to turn, so the pitch/radius gets a slow, non-repeating
    // wobble (two incommensurate frequencies, seeded per-reel by cx/cy so the two reels never
    // wobble in lockstep) instead of a pure r = a + bθ curve.
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

    // Secondary interleaved shadow groove. Offset by a hashed phase per reel (not an exact π
    // half-turn) so the pack doesn't read as a perfectly 2-fold-symmetric print.
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

    // Procedural oxide flecks across the wound tape face. Angle, radius, size and alpha are all
    // hash-jittered per fleck (seeded on ring+k, so it's stable frame to frame, just not laid out
    // on a perfectly even grid) to avoid the "printed texture" look a uniform ring of identical
    // marks gives when it spins as one rigid pattern.
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

/** One reel at spec size: wound pack with continuous Archimedean spirals, hub with hole ring, splined spindle. */
private fun DrawScope.drawReel(cx: Float, cy: Float, tapeR: Float, angle: Float, t: TapeTheme, g: TapeGrade) {
    val c = Offset(cx, cy)
    // 1) Soft ambient drop shadow under the winding pack
    drawCircle(Color(0x66000000), tapeR + 0.6f, Offset(cx + 0.45f, cy + 0.65f))

    // 2) Photorealistic magnetic ferric/chrome oxide tape pancake
    if (tapeR > HUB_R) {
        // Base deep magnetic tape body
        // Oxide colour is the GRADE's, not a fixed brown. This is the most legible quality tell
        // in the whole view and it is also simply true of the real object: ferric tape is warm
        // brown, chrome is a colder dark umber, metal particle is near-black with a blue cast, and
        // mastering stock is blacker still. The file decides which pancake is on the hubs.
        drawCircle(
            Brush.radialGradient(
                listOf(g.oxideHi, g.oxideLo, lerp(g.oxideLo, Color.Black, 0.55f)),
                center = Offset(cx - tapeR * 0.25f, cy - tapeR * 0.25f),
                radius = tapeR * 1.35f
            ),
            tapeR,
            c
        )

        // Real Archimedean spiral tape winding layers: r(theta) = HUB_R + k * theta
        // Rotated smoothly via GPU matrix transformation without vertex snapping or jumps.
        // Geometry itself is cached (buildReelGeometry) — angle-independent, so it's only rebuilt
        // when tapeR actually moves (seeking), not every frame.
        val packDepth = tapeR - HUB_R
        if (packDepth > 0.4f) {
            val bucket = Math.round(tapeR * 20f)  // ~0.05mm cache resolution
            val geo = reelGeometryCache.getOrPut(cx to bucket) { buildReelGeometry(cx, cy, tapeR, packDepth) }
            rotate(angle, c) {
                drawPath(geo.spiral1, Color(0x38E0D4C0), style = Stroke(0.18f))
                drawPath(geo.spiral2, Color(0x44000000), style = Stroke(0.16f))
                for (f in geo.flecks) {
                    drawLine(Color(0xFFFFECC8).copy(alpha = f.alpha), f.start, f.end, f.width)
                }
            }
        }

        // Physically accurate Anisotropic Specular Sheen (Stationary Overhead Light Field).
        // Wound tape oxide is a matte/semi-diffuse surface, not a mirror — it shouldn't strobe
        // sharply twice per revolution the way a single clean sin(2·angle) term does. `angle` wraps
        // mod 360°, so only INTEGER harmonics of it stay continuous through that wrap (a fractional
        // multiplier would pop at every wrap — the exact "jumping" artifact this is fixing); summing
        // three low-amplitude integer harmonics at different phases breaks up the one hard flash into
        // a softer, less mechanically-obvious shimmer while staying perfectly smooth through the wrap.
        val angleRad = Math.toRadians(angle.toDouble()).toFloat()
        val shimmer = 1.0f + 0.035f * kotlin.math.sin(angleRad) + 0.025f * kotlin.math.sin(2f * angleRad + 1.3f) + 0.02f * kotlin.math.sin(3f * angleRad - 0.7f)
        val sheenR = (HUB_R + tapeR) / 2f

        // Primary top-left ambient specular reflection lobe
        drawArc(
            Color(0x28FFECC8).copy(alpha = 0.18f * shimmer),
            startAngle = 125f,
            sweepAngle = 70f,
            useCenter = false,
            topLeft = Offset(cx - sheenR, cy - sheenR),
            size = Size(sheenR * 2f, sheenR * 2f),
            style = Stroke(width = packDepth * 0.85f)
        )

        // Secondary bottom-right fill reflection lobe
        drawArc(
            Color(0x18FFFFFF).copy(alpha = 0.12f * shimmer),
            startAngle = 305f,
            sweepAngle = 65f,
            useCenter = false,
            topLeft = Offset(cx - sheenR, cy - sheenR),
            size = Size(sheenR * 2f, sheenR * 2f),
            style = Stroke(width = packDepth * 0.70f)
        )
    }

    // 3) Hub itself (moulded plastic reel core)
    rotate(angle, c) {
        // Hub body with molded 3D radial lighting
        drawCircle(
            Brush.radialGradient(
                listOf(t.hubHi, t.hubLo),
                center = Offset(cx - HUB_R * 0.35f, cy - HUB_R * 0.35f),
                radius = HUB_R * 1.5f
            ),
            HUB_R,
            c
        )
        drawCircle(t.hubLo, HUB_R, c, style = Stroke(0.3f))

        // Ring of 14 friction/reduction holes around hub face
        for (k in 0 until 14) {
            val a = Math.toRadians(k * (360.0 / 14))
            val hc = Offset(cx + (8.1f * cos(a)).toFloat(), cy + (8.1f * sin(a)).toFloat())
            drawCircle(Color(0x40000000), 0.72f, hc + Offset(0.1f, 0.1f))
            drawCircle(t.holes, 0.62f, hc)
        }

        // Splined spindle opening: the 8.5 mm drive hole with 6 reinforced teeth
        drawCircle(Color(0xFF0D0A08), SPINDLE_R + 1.2f, c)
        drawCircle(Color(0xFFE8E5DC), SPINDLE_R, c)
        drawCircle(Color(0x40000000), SPINDLE_R, c, style = Stroke(0.3f))
        for (tooth in 0 until 6) {
            val a = Math.toRadians((tooth * 60).toDouble())
            val dir = Offset(cos(a).toFloat(), sin(a).toFloat())
            drawLine(Color(0xFF0D0A08), c + dir * (SPINDLE_R - 1.6f), c + dir * SPINDLE_R, strokeWidth = 1.6f)
            drawLine(Color(0x44FFFFFF), c + dir * (SPINDLE_R - 1.4f) + Offset(0.1f, 0.1f), c + dir * (SPINDLE_R - 0.2f), strokeWidth = 0.6f)
        }
    }
}


/**
 * A small, honest portrait of one cassette theme, for the picker.
 *
 * It is drawn in the same millimetre space as the real shell (so proportions are the true
 * 101.6 x 63.5 mm) but deliberately reduced: walls, label band, window, hubs, packs, the accent
 * mark and the melt-weld or screw corners. No mechanism, no wear, no lens shader — at this size
 * those turn to mud and cost a frame each, and the point here is to answer "what does this tape
 * look like" at a glance.
 */

/**
 * The deck's VOLUME fader, mounted on the cassette itself.
 *
 * Justin wants exactly one place in the OS with a different volume control, and this is it: the
 * app-wide Hione volume modal is suppressed for as long as tape mode is up (see TapeScreen), and the
 * level lives here instead, permanently on screen the way it would be on the front panel of a 1985
 * deck. Nothing pops, nothing times out.
 *
 * What it is, physically: a recessed linear slot with a knurled cap, a printed scale, and the
 * twelve-segment LED ladder every deck of that era had next to its level control, the last two
 * segments red because that is where the print said you were pushing it.
 *
 * Volume goes through setStreamVolume, NOT adjustStreamVolume: HiBy's AudioService carries an
 * adjust-only, raise-only, per-jack lock that silently swallows the step (see
 * HioneVolumeManager.triggerHud for the full trace).
 */
/**
 * The deck's volume fader has four bodies, because one slider cannot suit twenty cassettes. Each
 * model is a real control off a period deck or Walkman rather than a generic Material slider:
 *
 *  LED_LADDER     12-segment level meter, last two segments red, over a thin slot and cap. The
 *                 mid-80s component-separates look. Suits the NEON and MERCH shells.
 *  KNURLED_SLIDER a recessed track with a printed 0..10 scale and a wide ridged plastic cap, the
 *                 channel fader off a cassette deck's front panel. Suits STUDIO.
 *  THUMBWHEEL     a ridged wheel sunk into a cutout, the way a Walkman's volume sits half inside
 *                 the case. The ridges travel with the level. Suits CLEAR.
 *  ENGRAVED_SCALE an engraved 0..10 rule with a pointer riding it, no backlight at all, which is
 *                 what a budget major-label deck actually had. Suits MAJOR84.
 *
 * The model follows the loaded cassette unless the user pins one. Tapping the VOLUME legend cycles
 * models and persists the choice; the first tap moves off "follow the cassette" onto a fixed model.
 */
private enum class FaderModel { LED_LADDER, KNURLED_SLIDER, THUMBWHEEL, ENGRAVED_SCALE }

private fun defaultFaderFor(layout: TapeLayout): FaderModel = when (layout) {
    TapeLayout.STUDIO -> FaderModel.KNURLED_SLIDER
    TapeLayout.CLEAR -> FaderModel.THUMBWHEEL
    TapeLayout.MAJOR84 -> FaderModel.ENGRAVED_SCALE
    TapeLayout.NEON, TapeLayout.MERCH -> FaderModel.LED_LADDER
}

@Composable
private fun TapeDeckVolumeFader(theme: TapeTheme, modifier: Modifier = Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val am = remember { ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager }
    val maxVol = remember { (am?.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) ?: 15).coerceAtLeast(1) }
    var vol by remember { mutableIntStateOf(am?.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) ?: 0) }
    // Follow the hardware knob and anything else that moves the stream. 250ms is well under the
    // rate a human turns a knob and costs nothing next to the cassette's own animation.
    LaunchedEffect(Unit) {
        while (true) {
            val v = am?.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) ?: 0
            if (v != vol) vol = v
            kotlinx.coroutines.delay(250)
        }
    }
    val frac = (vol.toFloat() / maxVol).coerceIn(0f, 1f)

    // -1 is "follow the cassette". Anything else pins a model across every shell.
    var faderPin by remember { mutableStateOf(PlayerPreferences.loadTapeFader(ctx)) }
    val model = if (faderPin < 0) defaultFaderFor(theme.layout)
                else FaderModel.entries[faderPin % FaderModel.entries.size]

    // Panel colours come from the shell so the fader belongs to whichever cassette is loaded.
    val dark = lum(theme.shellLo) < 0.5f
    val slotColor = if (dark) Color(0xFF0B0B0E) else Color(0xFF2A2A2E)
    val capHi = if (dark) Color(0xFFBDBDC4) else Color(0xFF4A4A52)
    val capLo = if (dark) Color(0xFF6E6E76) else Color(0xFF1C1C22)
    val printInk = ensureContrastOn(if (dark) Color(0xFFD8D8D0) else Color(0xFF15151A), theme.shellHi, 0.35f)

    // The control is not one flat colour. It runs the shell's own brand colour at the bottom of the
    // range, warms through amber past two thirds, and goes red in the last fifth, which is the
    // colour language every deck's level meter already used. `glow` rides the same curve, squared so
    // the bloom stays almost off through normal listening levels and only really lights up near the
    // top, instead of a linear haze that is always half on.
    val hot = Color(0xFFFF3B30)
    val warm = Color(0xFFFFB020)
    val accent = when {
        frac >= 0.80f -> lerp(warm, hot, ((frac - 0.80f) / 0.20f).coerceIn(0f, 1f))
        frac >= 0.62f -> lerp(theme.brandColor, warm, ((frac - 0.62f) / 0.18f).coerceIn(0f, 1f))
        else -> theme.brandColor
    }
    val glow = (frac * frac).coerceIn(0f, 1f)
    // A slow breath so the lit part is never completely static while audio is playing. Amplitude is
    // tied to the level, so at low volume it is imperceptible and at the top it pulses.
    val breathe by rememberInfiniteTransition(label = "faderGlow").animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Reverse),
        label = "faderGlowPulse"
    )
    val bloom = (glow * breathe).coerceIn(0f, 1.2f)

    Row(
        modifier.height(26.dp).width(200.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "VOLUME",
            color = printInk,
            fontSize = 7.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.6.sp,
            maxLines = 1,
            modifier = Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val up = waitForUpOrCancellation()
                    if (up != null) {
                        up.consume()
                        // From "follow the cassette", step onto the model the cassette was already
                        // showing, so the first tap is a no-op visually and the second actually moves.
                        val cur = if (faderPin < 0) defaultFaderFor(theme.layout).ordinal else faderPin
                        faderPin = (cur + 1) % FaderModel.entries.size
                        PlayerPreferences.saveTapeFader(ctx, faderPin)
                        Haptics.tick(ctx)
                    }
                }
            }
        )
        Spacer(Modifier.width(6.dp))
        Canvas(
            Modifier
                .weight(1f)
                .height(22.dp)
                .pointerInput(maxVol) {
                    fun apply(xPx: Float, widthPx: Float) {
                        val f = (xPx / widthPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
                        val target = Math.round(f * maxVol).coerceIn(0, maxVol)
                        if (target != vol) {
                            vol = target
                            runCatching {
                                am?.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, target, 0)
                            }
                            Haptics.tick(ctx)
                        }
                    }
                    // awaitEachGesture rather than detectHorizontalDragGestures: touch down sets
                    // the level immediately (a fader cap jumps to where you press it), and every
                    // move is consumed so the drag never leaks out to the cassette behind it.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        apply(down.position.x, size.width.toFloat())
                        down.consume()
                        while (true) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) { c.consume(); break }
                            apply(c.position.x, size.width.toFloat())
                            c.consume()
                        }
                    }
                }
        ) {
            val h = size.height
            val w = size.width

            /** The printed 0..10 rule every one of these panels carries, tall ticks on the fives. */
            fun scale(baseY: Float, tallH: Float, shortH: Float, alpha: Float) {
                for (i in 0..10) {
                    val x = w * (i / 10f)
                    val tall = i % 5 == 0
                    drawLine(
                        color = printInk.copy(alpha = if (tall) alpha else alpha * 0.55f),
                        start = Offset(x, baseY - (if (tall) tallH else shortH)),
                        end = Offset(x, baseY),
                        strokeWidth = if (tall) 1.6f else 1f
                    )
                }
            }

            /** A recessed moulded track with a lit lower lip, shared by the slider-shaped models. */
            fun slot(y: Float, height: Float) {
                drawRoundRect(
                    color = slotColor,
                    topLeft = Offset(0f, y),
                    size = Size(w, height),
                    cornerRadius = CornerRadius(height / 2f)
                )
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.10f),
                    topLeft = Offset(0f, y + height * 0.55f),
                    size = Size(w, height * 0.45f),
                    cornerRadius = CornerRadius(height / 2f)
                )
            }

            /** The ridged plastic cap. `ridges` is how many moulded grip lines it carries. */
            fun cap(cx: Float, cy: Float, cw: Float, ch: Float, ridges: Int) {
                val x = (cx - cw / 2f).coerceIn(0f, w - cw)
                val y = cy - ch / 2f
                drawRoundRect(
                    brush = Brush.verticalGradient(listOf(capHi, capLo)),
                    topLeft = Offset(x, y),
                    size = Size(cw, ch),
                    cornerRadius = CornerRadius(cw * 0.22f)
                )
                for (i in 1..ridges) {
                    val kx = x + cw * (i / (ridges + 1f))
                    drawLine(
                        color = Color.Black.copy(alpha = 0.35f),
                        start = Offset(kx, y + ch * 0.18f),
                        end = Offset(kx, y + ch * 0.82f),
                        strokeWidth = 1f
                    )
                }
            }

            when (model) {
                FaderModel.LED_LADDER -> {
                    val ladderH = h * 0.44f
                    val segs = 12
                    val gap = w * 0.012f
                    val segW = (w - gap * (segs - 1)) / segs
                    val litUpTo = frac * segs
                    for (i in 0 until segs) {
                        val on = i < litUpTo
                        val red = i >= segs - 2
                        val base = if (red) Color(0xFFFF3B30) else accent
                        val c = if (on) base else base.copy(alpha = 0.13f)
                        val x = i * (segW + gap)
                        drawRoundRect(
                            color = c,
                            topLeft = Offset(x, 0f),
                            size = Size(segW, ladderH),
                            cornerRadius = CornerRadius(segW * 0.18f)
                        )
                        if (on) {
                            // Segments near the top of the lit run bloom hardest, the way the last
                            // few LEDs on a real meter wash into each other when you drive it.
                            val nearTop = 1f - ((litUpTo - i) / segs.toFloat()).coerceIn(0f, 1f)
                            val a = (0.18f + 0.45f * bloom * nearTop).coerceIn(0f, 0.75f)
                            val spread = gap * (1f + 2.5f * bloom * nearTop)
                            drawRoundRect(   // the glow a real LED throws onto the panel around it
                                color = base.copy(alpha = a),
                                topLeft = Offset(x - spread, -spread),
                                size = Size(segW + spread * 2, ladderH + spread * 2),
                                cornerRadius = CornerRadius(segW * 0.3f)
                            )
                        }
                    }
                    val slotY = h * 0.74f
                    val slotH = h * 0.16f
                    scale(slotY - h * 0.03f, h * 0.13f, h * 0.07f, 0.7f)
                    slot(slotY, slotH)
                    cap(frac * w, slotY + slotH / 2f, w * 0.075f, h * 0.42f, 4)
                }

                FaderModel.KNURLED_SLIDER -> {
                    // No lights at all. A wide travel slot, a printed rule above it, and a cap big
                    // enough to actually be a thumb rest, which is what a channel fader looks like.
                    val slotY = h * 0.52f
                    val slotH = h * 0.20f
                    scale(slotY - h * 0.10f, h * 0.30f, h * 0.17f, 0.8f)
                    slot(slotY, slotH)
                    // Travelled part of the track picks up the shell's brand colour, the way a
                    // deck's fader leaves a coloured trail behind the cap.
                    val litW = (frac * w).coerceAtLeast(0f)
                    if (bloom > 0.02f) drawRoundRect(   // bloom spilling off the lit track
                        color = accent.copy(alpha = 0.30f * bloom),
                        topLeft = Offset(-slotH * 0.25f, slotY + slotH * 0.28f - slotH * 0.35f),
                        size = Size(litW + slotH * 0.5f, slotH * 0.44f + slotH * 0.7f),
                        cornerRadius = CornerRadius(slotH * 0.6f)
                    )
                    drawRoundRect(
                        color = accent.copy(alpha = 0.55f + 0.40f * glow),
                        topLeft = Offset(0f, slotY + slotH * 0.28f),
                        size = Size(litW, slotH * 0.44f),
                        cornerRadius = CornerRadius(slotH * 0.22f)
                    )
                    cap(frac * w, slotY + slotH / 2f, w * 0.115f, h * 0.74f, 6)
                }

                FaderModel.THUMBWHEEL -> {
                    // A wheel sunk into the shell: a dark cutout, ridges that travel with the
                    // level so the wheel reads as turning, and a fixed index mark at the centre.
                    val cutTop = h * 0.20f
                    val cutH = h * 0.62f
                    drawRoundRect(
                        color = slotColor,
                        topLeft = Offset(0f, cutTop),
                        size = Size(w, cutH),
                        cornerRadius = CornerRadius(cutH * 0.30f)
                    )
                    // Ridge pitch is fixed; the phase moves with the level. Ridges near the middle
                    // of the cutout catch the most light, which is what sells it as a cylinder.
                    val pitch = w * 0.045f
                    val phase = (frac * pitch * 6f) % pitch
                    var x = -pitch + phase
                    while (x < w + pitch) {
                        if (x >= 0f && x <= w) {
                            val d = kotlin.math.abs(x - w / 2f) / (w / 2f)
                            val lit = (1f - d * d).coerceIn(0f, 1f)
                            drawLine(
                                color = capHi.copy(alpha = 0.18f + 0.55f * lit),
                                start = Offset(x, cutTop + cutH * 0.14f),
                                end = Offset(x, cutTop + cutH * 0.86f),
                                strokeWidth = 1.4f
                            )
                        }
                        x += pitch
                    }
                    // Cylinder shading: dark at both lips, bright across the crown.
                    drawRect(
                        brush = Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent, Color.Black.copy(alpha = 0.45f))
                        ),
                        topLeft = Offset(0f, cutTop),
                        size = Size(w, cutH)
                    )
                    // Fixed index mark, and a small brand-coloured pip that rides the level so the
                    // wheel still reports an absolute position, not just motion.
                    drawLine(
                        color = printInk.copy(alpha = 0.9f),
                        start = Offset(w / 2f, cutTop - h * 0.14f),
                        end = Offset(w / 2f, cutTop + h * 0.04f),
                        strokeWidth = 1.8f
                    )
                    val pip = Offset(frac * w, cutTop + cutH + h * 0.10f)
                    if (bloom > 0.02f) drawCircle(accent.copy(alpha = 0.35f * bloom), radius = h * 0.075f * (1.8f + 1.6f * bloom), center = pip)
                    drawCircle(accent, radius = h * 0.075f, center = pip)
                }

                FaderModel.ENGRAVED_SCALE -> {
                    // Engraved, unlit, printed rule with a pointer. The cheap deck look.
                    val ruleY = h * 0.64f
                    drawLine(
                        color = printInk.copy(alpha = 0.55f),
                        start = Offset(0f, ruleY),
                        end = Offset(w, ruleY),
                        strokeWidth = 1.2f
                    )
                    scale(ruleY, h * 0.26f, h * 0.15f, 0.85f)
                    // Pointer: a small filled triangle sitting on the rule, plus its cast shadow.
                    val px = frac * w
                    val pw = h * 0.20f
                    val ph = h * 0.26f
                    // Drawn twice at a 1px offset: the lower copy is the pointer's cast shadow on
                    // the printed rule, the upper one is the pointer itself. drawPath fills by
                    // default, so neither needs an explicit style.
                    fun pointer(ox: Float, oy: Float, c: Color) {
                        drawPath(
                            Path().apply {
                                moveTo(px + ox, ruleY + oy - ph * 0.10f)
                                lineTo(px + ox - pw, ruleY + oy + ph)
                                lineTo(px + ox + pw, ruleY + oy + ph)
                                close()
                            },
                            c
                        )
                    }
                    pointer(0f, 0f, Color.Black.copy(alpha = 0.30f))
                    if (bloom > 0.02f) drawCircle(accent.copy(alpha = 0.28f * bloom), radius = ph * (1.2f + 1.4f * bloom), center = Offset(px, ruleY + ph * 0.4f))
                    pointer(-1f, -1.5f, accent)
                }
            }
        }
    }
}

@Composable
private fun MiniCassette(t: TapeTheme, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val s = kotlin.math.min(size.width / SHELL_W, size.height / SHELL_H)
        withTransform({
            translate((size.width - SHELL_W * s) / 2f, (size.height - SHELL_H * s) / 2f)
            scale(s, s, Offset.Zero)
        }) {
            // Body
            drawRoundRect(
                Brush.linearGradient(listOf(t.shellHi, t.shellLo), Offset.Zero, Offset(SHELL_W, SHELL_H)),
                size = Size(SHELL_W, SHELL_H),
                cornerRadius = CornerRadius(SHELL_R, SHELL_R)
            )
            // Label band across the top, the way every one of these layouts starts
            drawRoundRect(
                lerp(t.shellHi, Color.White, 0.28f).copy(alpha = 0.55f),
                topLeft = Offset(2.8f, 2.8f),
                size = Size(SHELL_W - 5.6f, 13.6f),
                cornerRadius = CornerRadius(1.2f, 1.2f)
            )
            // The one accent mark that distinguishes this layout family
            when (t.layout) {
                TapeLayout.STUDIO -> drawPath(
                    Path().apply { moveTo(58f, 3.4f); lineTo(63.5f, 3.4f); lineTo(69.5f, 15.8f); lineTo(64f, 15.8f); close() },
                    t.accent
                )
                TapeLayout.MAJOR84 -> {
                    drawLine(t.ink.copy(alpha = 0.85f), Offset(3f, 1.7f), Offset(SHELL_W - 3f, 1.7f), 0.4f)
                    drawRoundRect(t.accent, topLeft = Offset(91.6f, 3.6f), size = Size(6f, 6f), cornerRadius = CornerRadius(0.8f, 0.8f))
                }
                TapeLayout.NEON -> drawRoundRect(t.accent, topLeft = Offset(93.2f, 3f), size = Size(4.6f, 4.6f), cornerRadius = CornerRadius(0.6f, 0.6f))
                TapeLayout.MERCH -> {
                    drawCircle(t.ink, HUB_R + 1.6f, Offset(HUB_L_X, HUB_Y), style = Stroke(0.5f))
                    drawCircle(t.ink, HUB_R + 1.6f, Offset(HUB_R_X, HUB_Y), style = Stroke(0.5f))
                }
                TapeLayout.CLEAR -> drawPath(
                    Path().apply { moveTo(4f, 2f); lineTo(46f, 2f); lineTo(22f, 40f); lineTo(-6f, 40f); close() },
                    t.accent.copy(alpha = 0.30f)
                )
            }
            // Packs + hubs
            for (hx in listOf(HUB_L_X, HUB_R_X)) {
                drawCircle(Color(0xFF2A1C12), 18f, Offset(hx, HUB_Y))
                drawCircle(
                    Brush.radialGradient(listOf(t.hubHi, t.hubLo), center = Offset(hx - 3f, HUB_Y - 3f), radius = HUB_R * 1.4f),
                    HUB_R, Offset(hx, HUB_Y)
                )
                drawCircle(t.holes, SPINDLE_R, Offset(hx, HUB_Y))
            }
            // Window
            val winTL = Offset(50.8f - WINDOW_W / 2f, HUB_Y - WINDOW_H / 2f)
            drawRoundRect(Color(0x22FFFFFF), topLeft = winTL, size = Size(WINDOW_W, WINDOW_H), cornerRadius = CornerRadius(2.5f, 2.5f))
            drawRoundRect(Color(0x88000000), topLeft = winTL, size = Size(WINDOW_W, WINDOW_H), cornerRadius = CornerRadius(2.5f, 2.5f), style = Stroke(0.8f))
            // Bottom plate + head opening
            drawPath(
                Path().apply { moveTo(17f, SHELL_H); lineTo(19.8f, 50.6f); lineTo(81.8f, 50.6f); lineTo(84.6f, SHELL_H); close() },
                Color(0x33000000)
            )
            drawRoundRect(Color(0xCC0A0A0A), topLeft = Offset(45.3f, 59.6f), size = Size(11f, SHELL_H - 59.6f), cornerRadius = CornerRadius(0.8f, 0.8f))
            if (t.motif39) drawHione39Badge(0.5f)
            // Corner fixings — screws or melt welds, the same tell as the full shell
            for ((sx, sy) in listOf(4.3f to 4.3f, SHELL_W - 4.3f to 4.3f, 4.3f to 59.2f, SHELL_W - 4.3f to 59.2f)) {
                drawCircle(if (t.welded) t.shellLo else t.bevelLo.copy(alpha = 0.8f), 1.5f, Offset(sx, sy))
                if (!t.welded) drawCircle(t.bevelHi.copy(alpha = 0.55f), 1.5f, Offset(sx, sy), style = Stroke(0.3f))
            }
            // A single fixed sheen, so the thumbnails all catch the light the same way
            drawPath(
                Path().apply { moveTo(16f, 0f); lineTo(30f, 0f); lineTo(6f, SHELL_H); lineTo(-8f, SHELL_H); close() },
                Color(0x16FFFFFF)
            )
            drawRoundRect(t.bevelHi.copy(alpha = 0.8f), size = Size(SHELL_W, SHELL_H), cornerRadius = CornerRadius(SHELL_R, SHELL_R), style = Stroke(0.6f))
        }
    }
}

// The HIONE 39 badge's holographic foil. Built once: the overlay layer redraws with the moving key
// light, and a Brush per frame would be garbage on every frame for a sheen that never changes.
private val HIONE39_FOIL get() = Brush.linearGradient(
    listOf(Mascot.color(0xFFFFAB2E), Mascot.color(0xFFB9FFF6), Mascot.color(0xFFFFA6D6), Mascot.color(0xFFE12885), Mascot.color(0xFFB9C2D0), Mascot.color(0xFFFFAB2E)),
    start = Offset(66f, 34f), end = Offset(108f, 50f)
)

/**
 * "39" on a holographic foil chip, mm space. Sits at x 80..94.5, y 37.2..46.8: right of the right
 * hub's reach at that height, above the transport row that covers y ~48..60, and clear of the
 * grade stamp, which lives bottom-LEFT. The foil slides with the key light ([light] 0..1) so it
 * catches the light the way the window streaks do. The digits are seven-segment strokes rather
 * than text: no Paint or typeface per frame.
 */
private fun DrawScope.drawHione39Badge(light: Float) {
    val x0 = 80f; val y0 = 37.2f; val w = 14.5f; val h = 9.6f
    clipRect(x0, y0, x0 + w, y0 + h) {
        translate(left = (light - 0.5f) * 14f) {
            drawRect(HIONE39_FOIL, topLeft = Offset(x0 - 10f, y0), size = Size(w + 20f, h))
        }
    }
    drawRoundRect(Color(0x66FFFFFF), topLeft = Offset(x0, y0), size = Size(w, h), cornerRadius = CornerRadius(1.2f, 1.2f), style = Stroke(0.3f))
    // 3 = a b c d g, 9 = a b c d f g
    drawSevenSeg(82.6f, 38.6f, 0b1001111)
    drawSevenSeg(88.4f, 38.6f, 0b1101111)
}

/** One seven-segment digit, 4.2 x 7 mm. [mask] bits 0..6 = segments a b c d e f g. */
private fun DrawScope.drawSevenSeg(x: Float, y: Float, mask: Int) {
    val dw = 4.2f; val dh = 7f; val mid = y + dh / 2f
    for (pass in 0..1) {
        // A pale offset pass under the ink reads as the edge of an embossed foil stamp.
        val c = if (pass == 0) Color(0x88FFFFFF) else Mascot.color(0xFF06302C)
        val o = if (pass == 0) 0.28f else 0f
        val sw = 1.0f
        if (mask and 1 != 0) drawLine(c, Offset(x + o, y + o), Offset(x + dw + o, y + o), sw, StrokeCap.Round)
        if (mask and 2 != 0) drawLine(c, Offset(x + dw + o, y + o), Offset(x + dw + o, mid + o), sw, StrokeCap.Round)
        if (mask and 4 != 0) drawLine(c, Offset(x + dw + o, mid + o), Offset(x + dw + o, y + dh + o), sw, StrokeCap.Round)
        if (mask and 8 != 0) drawLine(c, Offset(x + o, y + dh + o), Offset(x + dw + o, y + dh + o), sw, StrokeCap.Round)
        if (mask and 16 != 0) drawLine(c, Offset(x + o, mid + o), Offset(x + o, y + dh + o), sw, StrokeCap.Round)
        if (mask and 32 != 0) drawLine(c, Offset(x + o, y + o), Offset(x + o, mid + o), sw, StrokeCap.Round)
        if (mask and 64 != 0) drawLine(c, Offset(x + o, mid + o), Offset(x + dw + o, mid + o), sw, StrokeCap.Round)
    }
}

private fun fmt(ms: Long): String { val sec = (ms / 1000).coerceAtLeast(0); return "${sec / 60}:${(sec % 60).toString().padStart(2, '0')}" }


/** Relative luminance, the cheap perceptual weighting (not full sRGB linearisation). */
private fun lum(c: Color): Float = c.red * 0.299f + c.green * 0.587f + c.blue * 0.114f

/**
 * What is physically behind the brand text on the top rail, for this theme's layout. This has to
 * match what drawCassetteOverlayMm actually paints there, or the contrast guard corrects against
 * the wrong background.
 */
private fun brandStripBackground(t: TapeTheme): Color = when (t.layout) {
    // STUDIO lays a cream paper strip across the whole label band (see the TapeLayout.STUDIO
    // branch: drawRoundRect(Color(0xF2F4F1E8), ...)).
    TapeLayout.STUDIO -> Color(0xFFF4F1E8)
    // MAJOR84 prints straight onto the shell between two ink rules.
    TapeLayout.MAJOR84 -> t.shellHi
    // CLEAR / NEON / MERCH print onto the shell itself, which is lit from the top edge, so the
    // effective ground is a little brighter than the flat shellHi.
    else -> lerp(t.shellHi, t.bevelHi, 0.12f)
}

/**
 * Nudge [fg] toward black or white — whichever the background is further from — until it clears
 * [minDelta] of luminance separation from [bg]. Hue is preserved as far as possible: the colour is
 * blended toward the target rather than replaced, so a gold stays gold and a teal stays teal, just
 * dark enough or light enough to read.
 */
private fun ensureContrastOn(fg: Color, bg: Color, minDelta: Float): Color {
    val bgL = lum(bg)
    if (kotlin.math.abs(lum(fg) - bgL) >= minDelta) return fg
    val target = if (bgL > 0.5f) Color.Black else Color.White
    var mix = 0.12f
    while (mix <= 1f) {
        val c = lerp(fg, target, mix)
        if (kotlin.math.abs(lum(c) - bgL) >= minDelta) return c
        mix += 0.12f
    }
    return target
}

@Composable
private fun BespokeTapeBranding(theme: TapeTheme, modifier: Modifier = Modifier) {
    // CONTRAST GUARD. The brand colours are chosen to suit each theme's SHELL, but three of the
    // layouts print the brand onto something else entirely — STUDIO lays a cream paper strip over
    // the label band, MAJOR84 prints between two ink rules on the bare shell. So TDK SA-X, whose
    // near-white brandColor is right against its smoke-black plastic, was being drawn white-on-
    // cream and was effectively invisible; MAXELL XLII-S's pale gold had the same problem.
    //
    // Rather than hand-tune twenty colour pairs, work out what is ACTUALLY behind the text for
    // this theme's layout and force the ink to clear a contrast floor against it, darkening or
    // lightening whichever direction that background demands. A colour that already passes is
    // left exactly as the theme author set it.
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
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp,
                        fontFamily = theme.brandFont,
                        modifier = Modifier.offset(x = 0.8.dp, y = 0.8.dp)
                    )
                    Text(
                        theme.brandText,
                        color = brandInk,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp,
                        fontFamily = theme.brandFont
                    )
                }
            }
            BrandTexture.OFFSET_PRINT_INK -> {
                // Crisp offset printed ink directly on paper sticker / shell (no plastic deboss)
                Text(
                    theme.brandText,
                    color = brandInk,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp,
                    fontFamily = theme.brandFont
                )
            }
            BrandTexture.HOT_STAMP_GOLD -> {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        theme.brandText,
                        color = Color(0x66000000),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp,
                        fontFamily = theme.brandFont,
                        modifier = Modifier.offset(x = 0.5.dp, y = 0.5.dp)
                    )
                    Text(
                        theme.brandText,
                        color = brandInk,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp,
                        fontFamily = theme.brandFont
                    )
                }
            }
            BrandTexture.HOT_STAMP_CHROME -> {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        theme.brandText,
                        color = Color(0x55000000),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp,
                        fontFamily = theme.brandFont,
                        modifier = Modifier.offset(x = 0.5.dp, y = 0.5.dp)
                    )
                    Text(
                        theme.brandText,
                        color = brandInk,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp,
                        fontFamily = theme.brandFont
                    )
                }
            }
            BrandTexture.DISCO_INLINE_NEON -> {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        theme.brandText,
                        color = brandInk.copy(alpha = 0.45f),
                        fontSize = 13.5.sp,
                        letterSpacing = 3.sp,
                        fontFamily = theme.brandFont
                    )
                    Text(
                        theme.brandText,
                        color = brandInk,
                        fontSize = 12.5.sp,
                        letterSpacing = 3.sp,
                        fontFamily = theme.brandFont
                    )
                }
            }
            BrandTexture.DOT_MATRIX_IMPRINT -> {
                Text(
                    theme.brandText,
                    color = brandInk,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    fontFamily = theme.brandFont
                )
            }
            BrandTexture.JAPANESE_KAWAII -> {
                Text(
                    theme.brandText,
                    color = brandInk,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    fontFamily = theme.brandFont
                )
            }
            BrandTexture.MINIMAL_LASER_ETCH -> {
                Text(
                    theme.brandText,
                    color = brandInk.copy(alpha = 0.85f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 2.sp,
                    fontFamily = theme.brandFont
                )
            }
            BrandTexture.SCREENPRINT_NEON -> {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        theme.brandText,
                        color = Color(0x40000000),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp,
                        fontFamily = theme.brandFont,
                        modifier = Modifier.offset(x = 0.5.dp, y = 0.5.dp)
                    )
                    Text(
                        theme.brandText,
                        color = brandInk,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp,
                        fontFamily = theme.brandFont
                    )
                }
            }
        }
        Text(
            theme.brandSub,
            color = brandSubInk,
            fontSize = 7.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.1.sp,
            fontFamily = if (theme.brandTexture == BrandTexture.DOT_MATRIX_IMPRINT) DotGothicFont else FontFamily.Monospace,
            modifier = Modifier.padding(top = 1.5.dp)
        )
    }
}

/**
 * Bare transport glyph (rewind/play/fast-forward/shuffle) — deliberately NO circular button
 * chrome and no default ripple indication behind it; instead a soft radial glow in the deck's own
 * bevel/accent color sits behind the icon, ambient at rest and warming up while `glowBoost` is
 * true (a flash tap, or steady while playing/shuffle is on) — a bespoke, per-theme affectation
 * instead of a generic circular tap target.
 */
@Composable
private fun TapeGlyphButton(
    icon: ImageVector,
    contentDescription: String,
    theme: TapeTheme,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 30.dp,
    tint: Color = theme.bevelHi.copy(alpha = 0.95f),
    glowBoost: Boolean = false
) {
    val glowAlpha by animateFloatAsState(if (glowBoost) 0.55f else 0.18f, animationSpec = tween(280), label = "glyphGlow")
    val glowColor = theme.accent
    Box(
        modifier = modifier
            .size(size * 1.5f)
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(listOf(glowColor.copy(alpha = glowAlpha), Color.Transparent)),
                    radius = this.size.minDimension / 2f
                )
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(size))
    }
}

/**
 * Ambient per-theme "affectation" glow that sits behind the molded circular buttons (exit,
 * playlist, heart) without touching their existing melted-into-plastic look — just a soft halo
 * in the deck's accent color, tuned by the theme's actual material so it reads as bespoke rather
 * than one generic effect on every deck: phosphor shells breathe like real subsurface phosphor
 * glow, metallic-flake shells get a slower, tighter glint, everything else a faint static hint.
 */
@Composable
private fun rememberMoldedGlowAlpha(theme: TapeTheme): Float {
    val transition = rememberInfiniteTransition(label = "moldedGlow")
    val periodMs = if (theme.plasticType == PlasticType.METALLIC_FLAKE) 4200 else 2600
    val pulse by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(periodMs, easing = LinearEasing), RepeatMode.Reverse),
        label = "moldedGlowPulse"
    )
    return when (theme.plasticType) {
        PlasticType.PHOSPHOR_GLOW -> 0.22f + 0.18f * pulse
        PlasticType.METALLIC_FLAKE -> 0.09f + 0.07f * pulse
        else -> 0.07f + 0.04f * pulse
    }
}

/**
 * Molded cassette shell control button: flush circular depression that seamlessly melts into
 * the cassette's textured plastic casing, with debossed engraving and subtle bevel lighting.
 */
@Composable
private fun MoldedTapeButton(
    icon: ImageVector,
    contentDescription: String,
    theme: TapeTheme,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val glowAlpha = rememberMoldedGlowAlpha(theme)
    Box(
        modifier = modifier
            .size(42.dp)
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(listOf(theme.accent.copy(alpha = glowAlpha), Color.Transparent)),
                    radius = this.size.minDimension / 2f
                )
            },
        contentAlignment = Alignment.Center
    ) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .background(
                Brush.radialGradient(
                    listOf(
                        theme.shellLo.copy(alpha = 0.95f),
                        theme.shellHi.copy(alpha = 0.40f)
                    )
                )
            )
            .border(
                width = 0.75.dp,
                brush = Brush.verticalGradient(
                    listOf(
                        theme.bevelLo.copy(alpha = 0.85f),
                        theme.bevelHi.copy(alpha = 0.55f)
                    )
                ),
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        // Debossed icon shadow for 3D recessed molded plastic look
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color(0x99000000),
            modifier = Modifier
                .size(16.dp)
                .offset(x = 0.6.dp, y = 0.6.dp)
        )
        // Main molded engraved icon
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = theme.engrave.copy(alpha = 0.95f),
            modifier = Modifier
                .size(16.dp)
        )
    }
    }
}

/**
 * Molded rainbow heart button for Tape Mode:
 * Flushed into a circular depression molded directly into the cassette shell.
 * When unliked: shows a subtle debossed engraved heart outline in the plastic casing.
 * When liked: illuminates with a liquid flowing chromatic rainbow gradient and subtle organic pulse.
 */
@Composable
private fun MoldedRainbowHeart(
    liked: Boolean,
    theme: TapeTheme,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var phase by remember { mutableStateOf(0f) }
    var beat by remember { mutableStateOf(0f) }

    LaunchedEffect(liked) {
        if (!liked) return@LaunchedEffect
        // Same idle gate as NowPlaying.kt's TieredRainbowHeart — pure decorative pulse, not worth
        // running while the screen isn't actively shown.
        while (true) {
            if (IdleController.screenActive) {
                phase = (phase + 2.5f) % 360f
                beat = (beat + 0.014f) % 1f
                delay(16)
            } else delay(500)
        }
    }

    fun heartbeat(x: Float): Float {
        fun bump(c: Float, wdt: Float) = kotlin.math.exp((-((x - c) * (x - c)) / (2 * wdt * wdt)).toDouble()).toFloat()
        return bump(0.10f, 0.040f) + 0.6f * bump(0.26f, 0.05f)
    }

    val pulse = if (liked) 1f + 0.06f * heartbeat(beat) else 1f
    // The liked state already has its own vivid rainbow affectation — the ambient per-theme glow
    // only kicks in while unliked, so it complements rather than competes with the rainbow pulse.
    val glowAlpha = rememberMoldedGlowAlpha(theme)

    Box(
        modifier = modifier
            .size(48.dp)
            .drawBehind {
                if (!liked) drawCircle(
                    brush = Brush.radialGradient(listOf(theme.accent.copy(alpha = glowAlpha), Color.Transparent)),
                    radius = this.size.minDimension / 2f
                )
            },
        contentAlignment = Alignment.Center
    ) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .semantics {
                contentDescription = if (liked) "Unlike" else "Like"
                role = Role.Checkbox
                toggleableState = androidx.compose.ui.state.ToggleableState(liked)
            }
            .clickable {
                Haptics.tick(ctx)
                onToggle()
            }
            .background(
                Brush.radialGradient(
                    listOf(
                        theme.shellLo.copy(alpha = 0.95f),
                        theme.shellHi.copy(alpha = 0.40f)
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    listOf(
                        theme.bevelLo.copy(alpha = 0.90f),
                        theme.bevelHi.copy(alpha = 0.60f)
                    )
                ),
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(24.dp).scale(pulse)) {
            val w = size.width
            val h = size.height
            val hp = Path().apply {
                moveTo(0.5f * w, 0.88f * h)
                cubicTo(0.32f * w, 0.73f * h, 0.05f * w, 0.53f * h, 0.05f * w, 0.30f * h)
                cubicTo(0.05f * w, 0.10f * h, 0.31f * w, 0.05f * h, 0.5f * w, 0.26f * h)
                cubicTo(0.69f * w, 0.05f * h, 0.95f * w, 0.10f * h, 0.95f * w, 0.30f * h)
                cubicTo(0.95f * w, 0.53f * h, 0.68f * w, 0.73f * h, 0.5f * w, 0.88f * h)
                close()
            }

            if (liked) {
                // 3D Physical Drop Shadow inside the mold
                drawContext.canvas.save()
                drawContext.canvas.translate(0f, 2.5f)
                drawPath(hp, Color(0x90000000))
                drawContext.canvas.restore()

                // Flowing multi-stop rainbow spectrum
                val cols = (0..6).map { Color.hsv(((it * 52) + phase) % 360f, 0.90f, 1f) }
                drawPath(hp, Brush.linearGradient(cols, Offset(0f, h), Offset(w, 0f)))

                // 3D Embossed Specular Crest & Shadow Rims
                val highlightBrush = Brush.linearGradient(
                    listOf(Color.White.copy(alpha = 0.90f), Color.White.copy(alpha = 0.30f), Color.Transparent),
                    Offset(0f, 0f), Offset(w, h)
                )
                drawPath(hp, highlightBrush, style = Stroke(width = 2.0f))
                val shadowBrush = Brush.linearGradient(
                    listOf(Color.Transparent, Color(0x75000000)),
                    Offset(0f, 0f), Offset(w, h)
                )
                drawPath(hp, shadowBrush, style = Stroke(width = 1.8f))

                // Glossy dome sheen
                drawPath(hp, Color.White.copy(alpha = 0.18f))
            } else {
                // 3D Debossed engraved unliked heart cavity
                drawContext.canvas.save()
                drawContext.canvas.translate(0f, 1.5f)
                drawPath(hp, Color(0x80000000))
                drawContext.canvas.restore()

                drawPath(hp, Color(0x60000000), style = Stroke(1.4f))
                drawPath(hp, theme.engrave.copy(alpha = 0.85f), style = Stroke(1.0f))
            }
        }
    }
    }
}

// ── Photorealistic Masking Tape Textures ──
private data class MaskingTapeStyle(
    val name: String,
    val paperBaseHi: Color,
    val paperBaseLo: Color,
    val ridgeColor: Color,
    val penColor: Color,
)

// A soft round grime blotch (thumb grease, general age/dust) on the tape's paper surface.
private data class GrimeSpot(val xFrac: Float, val yFrac: Float, val radiusPx: Float, val alpha: Float)
// A thin directional rub/scuff mark — the kind of faint streak a tape picks up sliding in and out
// of a case for years.
private data class SmudgeStreak(val yFrac: Float, val angleDeg: Float, val lenFrac: Float, val alpha: Float, val widthPx: Float)
// An ink smear sitting on top of the handwriting itself — a thumb dragged across still-wet marker,
// or just decades of handling. Drawn last, over the text, not just the paper underneath it.
private data class InkSmear(val xFrac: Float, val yFrac: Float, val wPx: Float, val hPx: Float, val angleDeg: Float, val alpha: Float)

private val MASKING_TAPE_STYLES get() = listOf(
    MaskingTapeStyle("Scotch Tan", Color(0xFFF7EED8), Color(0xFFE5D5B2), Color(0x228A6E3D), Color(0xFF14141E)),
    MaskingTapeStyle("Vintage Ivory", Color(0xFFFAF2E4), Color(0xFFDDD0B5), Color(0x25705A32), Color(0xFF1E2838)),
    MaskingTapeStyle("Off-White Draft", Color(0xFFFAFAFA), Color(0xFFEAE6DF), Color(0x1C606060), Color(0xFF0F1E36)),
    MaskingTapeStyle("Aqua Painter", Mascot.color(0xFFDCF5F2), Mascot.color(0xFFB8E8E3), Mascot.color(0x2224857D), Mascot.color(0xFF0C2B2E)),
    MaskingTapeStyle("Artist Cream", Color(0xFFFFF8EA), Color(0xFFF0E2C6), Color(0x208A744A), Color(0xFF281C10))
)

@Composable
private fun PhotorealisticMaskingTapeLabel(
    track: Track,
    modifier: Modifier = Modifier
) {
    val seed = remember(track.id) { kotlin.random.Random(track.id) }
    val style = remember(track.id) { MASKING_TAPE_STYLES[seed.nextInt(MASKING_TAPE_STYLES.size)] }
    val tearJitter = remember(track.id) {
        val r = kotlin.random.Random(track.id + 100)
        FloatArray(16) { r.nextFloat() * 4f - 2f }
    }
    // Each end torn independently, with its own random tooth count/spacing/depth — real deckled
    // tears never match each other or repeat a clean zigzag.
    val rightEdge = remember(track.id) { randomTornEdge(kotlin.random.Random(track.id + 300)) }
    val leftEdge = remember(track.id) { randomTornEdge(kotlin.random.Random(track.id + 400)) }
    // A handful of stray paper-fiber wisps poking out past the tear — the fuzzy give-away of torn
    // (not cut) paper.
    val fiberWisps = remember(track.id) {
        val r = kotlin.random.Random(track.id + 500)
        List(6) { FiberWisp(side = r.nextInt(2), yFrac = r.nextFloat(), lenPx = 0.5f + r.nextFloat() * 1.1f, angleDeg = r.nextFloat() * 50f - 25f) }
    }
    // Tilt. A strip torn off a roll and pressed down by hand is rarely within 2 degrees of
    // square, which is what the old +/-2.5 range looked like: tidy, and therefore fake. Most go on
    // a few degrees out, and every so often somebody slaps one on properly crooked, so the
    // distribution is a small base tilt plus an occasional larger one rather than one flat range.
    val rotation = remember(track.id) {
        // Natural hand-applied tilt, kept within ±2.3 degrees so the strip stays cleanly
        // within the cassette's label band and never dips toward the window or hubs.
        seed.nextFloat() * 4.6f - 2.3f
    }

    // Not every tape is fresh — real masking tape picks up thumb grease, dust, rub marks from
    // sliding in and out of a case, and an ink smear where someone's hand dragged across the
    // marker before it fully dried. Rolled once per track (its own seed offset, independent of
    // style/tear/rotation) so it's a per-track coin flip, not tied to any other visual choice.
    val dirty = remember(track.id) { kotlin.random.Random(track.id + 700).nextFloat() < 0.24f }
    val grimeSpots = remember(track.id) {
        if (!dirty) emptyList() else {
            val r = kotlin.random.Random(track.id + 800)
            List(2 + r.nextInt(3)) {
                GrimeSpot(
                    xFrac = 0.08f + r.nextFloat() * 0.84f,
                    yFrac = 0.1f + r.nextFloat() * 0.8f,
                    radiusPx = 7f + r.nextFloat() * 16f,
                    alpha = 0.08f + r.nextFloat() * 0.14f
                )
            }
        }
    }
    val smudgeStreaks = remember(track.id) {
        if (!dirty) emptyList() else {
            val r = kotlin.random.Random(track.id + 900)
            List(1 + r.nextInt(2)) {
                SmudgeStreak(
                    yFrac = 0.15f + r.nextFloat() * 0.7f,
                    angleDeg = r.nextFloat() * 26f - 13f,
                    lenFrac = 0.3f + r.nextFloat() * 0.4f,
                    alpha = 0.07f + r.nextFloat() * 0.09f,
                    widthPx = 2.5f + r.nextFloat() * 3f
                )
            }
        }
    }
    // One smear sitting on TOP of the handwriting itself, not just the paper underneath it — the
    // "thumb dragged across wet marker" mark. Roughly targets where the title line sits.
    val inkSmear = remember(track.id) {
        if (!dirty) null else {
            val r = kotlin.random.Random(track.id + 950)
            if (r.nextFloat() < 0.55f) null else InkSmear(
                xFrac = 0.2f + r.nextFloat() * 0.5f,
                yFrac = 0.28f + r.nextFloat() * 0.2f,
                wPx = 40f + r.nextFloat() * 50f,
                hPx = 10f + r.nextFloat() * 8f,
                angleDeg = r.nextFloat() * 20f - 10f,
                alpha = 0.10f + r.nextFloat() * 0.10f
            )
        }
    }

    Box(
        modifier = modifier
            .rotate(90f + rotation)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height

            if (w <= 0f || h <= 0f) return@Canvas

            // Generate deckled torn tape path with ripped paper fiber ends — each end its own
            // randomized tooth count/spacing/depth (see randomTornEdge), never a clean zigzag.
            val tapePath = Path().apply {
                moveTo(6f + tearJitter[0], 0f)
                lineTo(w - 6f + tearJitter[1], 0f)

                // Right torn end (deckled jagged tear, top -> bottom)
                for (step in rightEdge) lineTo(w - 3f + step.depthPx, h * step.yFrac)

                lineTo(w - 6f + tearJitter[2], h)
                lineTo(6f + tearJitter[3], h)

                // Left torn end (deckled jagged tear, bottom -> top)
                for (step in leftEdge.asReversed()) lineTo(3f + step.depthPx, h * step.yFrac)
                close()
            }

            // 1. Ambient Drop Shadow underneath tape
            val shadowPath = Path().apply {
                addPath(tapePath, Offset(1.8f, 2.8f))
            }
            drawPath(shadowPath, Color(0x3B000000))

            // 2. Translucent Crepe Paper Base Fill
            val paperBrush = Brush.linearGradient(
                colors = listOf(style.paperBaseHi.copy(alpha = 0.95f), style.paperBaseLo.copy(alpha = 0.93f)),
                start = Offset(0f, 0f),
                end = Offset(w, h)
            )
            drawPath(tapePath, paperBrush)

            // 3. Crepe Paper Micro-Ridges (horizontal texture lines)
            var ry = 2.5f
            while (ry < h - 2f) {
                drawLine(
                    color = style.ridgeColor,
                    start = Offset(4f, ry),
                    end = Offset(w - 4f, ry),
                    strokeWidth = 0.65f
                )
                ry += 1.8f
            }

            // 4. Paper Fibers & Surface Grain Flecks
            for (f in 0 until 10) {
                val fx = 8f + (w - 16f) * ((f * 37) % 100 / 100f)
                val fy = 3f + (h - 6f) * ((f * 53) % 100 / 100f)
                drawCircle(style.ridgeColor.copy(alpha = 0.18f), 0.8f, Offset(fx, fy))
            }

            // 5. Highlights along top edge and torn fiber tips
            drawLine(Color(0x35FFFFFF), Offset(4f, 0.8f), Offset(w - 4f, 0.8f), 0.8f)
            drawPath(tapePath, style.ridgeColor.copy(alpha = 0.35f), style = Stroke(0.5f))

            // 6. Stray paper-fiber wisps poking out past the tear line — torn paper fuzzes at the
            // edge in a way a clean-cut edge never does; a few short random hairs sell it.
            for (wisp in fiberWisps) {
                val edge = if (wisp.side == 0) leftEdge else rightEdge
                val nearest = edge.minByOrNull { kotlin.math.abs(it.yFrac - wisp.yFrac) } ?: continue
                val baseX = if (wisp.side == 0) 3f + nearest.depthPx else w - 3f + nearest.depthPx
                val baseY = h * wisp.yFrac
                val rad = Math.toRadians(wisp.angleDeg.toDouble() + if (wisp.side == 0) 180.0 else 0.0)
                val tip = Offset(baseX + (kotlin.math.cos(rad) * wisp.lenPx).toFloat(), baseY + (kotlin.math.sin(rad) * wisp.lenPx).toFloat())
                drawLine(style.ridgeColor.copy(alpha = 0.4f), Offset(baseX, baseY), tip, 0.35f)
            }

            // 7. Grime & rub marks — only rolled on some tapes (see `dirty` above). Soft round
            // blotches (thumb grease/dust) plus a couple of thin directional scuffs, both drawn in
            // a dark neutral tone so they read as age/dirt regardless of the tape's own paper color.
            if (dirty) {
                for (spot in grimeSpots) {
                    val c = Offset(w * spot.xFrac, h * spot.yFrac)
                    drawCircle(
                        Brush.radialGradient(listOf(Color.Black.copy(alpha = spot.alpha), Color.Transparent), center = c, radius = spot.radiusPx),
                        radius = spot.radiusPx, center = c
                    )
                }
                for (streak in smudgeStreaks) {
                    val cy = h * streak.yFrac
                    val cx = w * 0.5f
                    val rad = Math.toRadians(streak.angleDeg.toDouble())
                    val halfLen = w * streak.lenFrac / 2f
                    val dx = (kotlin.math.cos(rad) * halfLen).toFloat()
                    val dy = (kotlin.math.sin(rad) * halfLen).toFloat()
                    drawLine(Color.Black.copy(alpha = streak.alpha), Offset(cx - dx, cy - dy), Offset(cx + dx, cy + dy), streak.widthPx, cap = StrokeCap.Round)
                }
            }
        }

        // Handwritten marker text on top of the photorealistic tape. Shrinks to fit instead of
        // truncating — it's meant to read as handwriting, and a mid-word "…" cut reads as broken
        // rather than a real hand just writing smaller to fit the strip.
        // Reserve real bottom clearance for the corner date (below) ONLY when there is one, so it
        // never fights the last line of text for the same pixels — that was the "date is half the
        // time covered by other text" bug: both were anchored to the same bottom-right corner with
        // no dedicated space carved out for either.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .widthIn(max = 360.dp)
                .padding(start = 14.dp, end = 14.dp, top = 5.dp, bottom = if (track.year > 0) 15.dp else 5.dp)
        ) {
            ShrinkToFitText(
                track.title,
                color = style.penColor,
                fontFamily = MarkerFont,
                fontWeight = FontWeight.SemiBold,
                maxFontSize = 17.sp,
                minFontSize = 10.sp
            )
            ShrinkToFitText(
                track.artist,
                color = style.penColor.copy(alpha = 0.85f),
                fontFamily = MarkerFont,
                maxFontSize = 11.5.sp,
                minFontSize = 7.5.sp
            )
            if (track.album.isNotBlank()) {
                ShrinkToFitText(
                    track.album,
                    color = style.penColor.copy(alpha = 0.65f),
                    fontFamily = MarkerFont,
                    maxFontSize = 9.5.sp,
                    minFontSize = 6.5.sp
                )
            }
        }

        // Clean marker handwriting rendering without text-obscuring smudges

        // A date jotted in the corner afterward, in a different pen — the way a real mixtape
        // label often picks up a second hand's note. Only when we actually know the year.
        if (track.year > 0) {
            Text(
                "'${(track.year % 100).toString().padStart(2, '0')}",
                color = style.penColor.copy(alpha = 0.8f),
                fontFamily = ScrawlFont,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 10.dp, bottom = 2.dp)
                    .rotate(-7f)
            )
        }
    }
}

/**
 * Single-line text that shrinks font size (never truncates) until it fits its constraints —
 * handwriting that got cut off mid-word reads as broken; handwriting that got a little smaller
 * to fit the strip reads as authentic.
 */
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
        text,
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

// ── Per-theme plastic warping/lensing for the whole rendered cassette texture ──
// Real molded plastic (especially anything translucent enough to see the mechanism through) is
// never a perfectly flat optical surface. The first version of this shader was pure organic noise
// (uniform strength everywhere) — it read as generic shimmer/heat-haze, not "looking through
// plastic", because a real lens/curved shell has a distinct SIGNATURE a uniform wobble doesn't:
// it's closest to optically flat in the middle (thin, low curvature) and bends light increasingly
// toward the edges (thicker, more curved) — and chromatic fringing follows that exact same
// falloff, strongest at the edges, negligible dead center. That radial coherence (not the noise
// amplitude) is what actually reads as "glass/acrylic" instead of "shader effect". The mold-flow
// organic wobble is still here, just demoted to a secondary texture on top of that dominant term.
//
// Requires RuntimeShader (API 33+). This build is device-gated to the M500 (Android 14 in the
// field, see MainActivity.isSupportedDevice), so that's never actually a runtime concern here —
// the SDK_INT guard below just keeps this safe to compile/verify against the library's broader
// minSdk without crashing older API surfaces that will never load it.
private const val LENS_AGSL = """
    uniform shader content;
    uniform float2 resolution;
    uniform float distortAmount;
    uniform float chromaAmount;
    uniform float phase;

    half4 main(float2 fragCoord) {
        float2 uv = fragCoord / resolution;
        float2 fromCenter = uv - float2(0.5, 0.5);
        float dist = length(fromCenter);

        // Dominant term: a coherent radial bulge, ~flat at the center, ramping up toward the
        // edges (smoothstep, not a hard-edged formula) — the actual optical signature of a lens.
        float radialStrength = smoothstep(0.0, 0.9, dist);
        float2 radialOffset = fromCenter * radialStrength * radialStrength * distortAmount * 3.2;

        // Secondary term: gentle organic mold-flow waviness, much smaller than the lens bulge —
        // texture, not the main event.
        float n1 = sin(uv.x * 9.1 + uv.y * 5.3 + phase) * cos(uv.y * 7.4 - uv.x * 3.2 - phase * 0.7);
        float n2 = sin(uv.x * 3.7 - uv.y * 11.3 + 1.7 + phase * 0.5);
        float2 wobble = float2(n1, n2) * distortAmount * 0.45;

        float2 offset = radialOffset + wobble;

        // Chromatic aberration follows the SAME radial falloff as the lens bulge — real lens CA
        // is strongest at the edges, close to invisible at the center.
        float chroma = chromaAmount * (0.15 + radialStrength * 0.85);

        float2 uvR = (uv + offset * (1.0 + chroma)) * resolution;
        float2 uvG = (uv + offset) * resolution;
        float2 uvB = (uv + offset * (1.0 - chroma)) * resolution;

        half4 cR = content.eval(uvR);
        half4 cG = content.eval(uvG);
        half4 cB = content.eval(uvB);

        return half4(cR.r, cG.g, cB.b, cG.a);
    }
"""

/** How strongly a theme's own plastic should warp/lens what's rendered through it. Opaque shells
 *  get null — there's no optical depth to distort when nothing shows through them. */
private data class LensSpec(val distort: Float, val chroma: Float)
private fun lensSpecFor(t: TapeTheme): LensSpec? = when (t.plasticType) {
    // Boosted well past "physically modest" — scoping the shader to just the art/visualizer layer
    // (not the shell's own geometry, see applyTapeLens's call site) means it's only ever visible
    // through a fraction of the screen to begin with, so the per-pixel displacement needs to be
    // large enough to actually read at a glance, not just survive close inspection.
    PlasticType.CLEAR_POLYCARBONATE -> LensSpec(distort = 0.032f, chroma = 0.24f)   // crisp, strongly see-through
    PlasticType.SMOKED_ACRYLIC -> LensSpec(distort = 0.020f, chroma = 0.16f)        // tinted, softer clarity
    PlasticType.SATIN_POLYSTYRENE -> LensSpec(distort = 0.009f, chroma = 0.08f)     // frosted, barely see-through
    PlasticType.MATTE_COMPOSITE, PlasticType.METALLIC_FLAKE, PlasticType.PHOSPHOR_GLOW -> null // opaque: no lensing
}

/**
 * Applies the theme's plastic lens/warp to whatever this graphicsLayer renders (drop-in for the
 * cassette's own Box). Sets the shader's resolution uniform to the layer's actual pixel size each
 * time it's (re)placed, and forces offscreen compositing — required for a renderEffect to combine
 * correctly with the translucent content it's distorting (mechanism art, glass streaks, etc.).
 */
private fun GraphicsLayerScope.applyTapeLens(theme: TapeTheme, shaderHolder: RuntimeShader?) {
    if (shaderHolder == null) return
    shaderHolder.setFloatUniform("resolution", size.width, size.height)
    compositingStrategy = CompositingStrategy.Offscreen
    renderEffect = android.graphics.RenderEffect.createRuntimeShaderEffect(shaderHolder, "content").asComposeRenderEffect()
}

/**
 * The RuntimeShader instance itself, remembered per (theme, strength) so applyTapeLens can update
 * its resolution uniform every layout pass without rebuilding the shader from source each time.
 *
 * [strengthMultiplier] lets the SAME plastic warp be reused at different intensities for different
 * depths in the cassette stack: full strength for the background art/visualizer (furthest behind
 * the plastic), a fraction of that for the mag tape mechanism itself (reels/ribbon sit right up
 * against the inside of the shell, so they should barely warp, not not-at-all), and the shell's
 * own geometry never gets a shader at all (drawn in a separate, unwrapped layer).
 */
@Composable
private fun rememberTapeLensShader(theme: TapeTheme, strengthMultiplier: Float = 1f): RuntimeShader? {
    val spec = lensSpecFor(theme) ?: return null
    if (android.os.Build.VERSION.SDK_INT < 33) return null
    return remember(theme.name, strengthMultiplier) {
        RuntimeShader(LENS_AGSL).apply {
            setFloatUniform("distortAmount", spec.distort * strengthMultiplier)
            setFloatUniform("chromaAmount", spec.chroma * strengthMultiplier)
            setFloatUniform("phase", (theme.name.hashCode() % 100) / 100f * 6.28f)
        }
    }
}
