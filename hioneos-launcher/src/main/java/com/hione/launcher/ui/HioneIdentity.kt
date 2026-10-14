package com.hione.launcher.ui

import com.hione.launcher.Mascot
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.launcher.AudiowideFont
import com.hione.launcher.CyberDarkBg
import com.hione.launcher.CyberGlassBorder
import com.hione.launcher.CyberGlassCard
import com.hione.launcher.HioneCyan
import com.hione.launcher.HioneGold
import com.hione.launcher.HioneNeonPink
import com.hione.launcher.HioneTextPrimary
import com.hione.launcher.HioneTextSecondary

/**
 * HioneOS launcher identity tokens — the ONE place the Oki Hione / super-kawaii look is
 * defined. Every launcher surface (home, quilt, status bar, drawer, dock, lockscreen, AOD,
 * onboarding, modals, control center) reads colours / glyphs / text styles from here (plus
 * sizes from [HioneDimens], motion from [HioneMotion], haptics from
 * `com.hione.launcher.haptics.HioneHaptics`) instead of Material defaults or ad-hoc literals.
 *
 * Palette rules
 *  - Teal is Hione: [Teal] (#FFAB2E, her canonical hair/tie teal) for identity accents,
 *    [Cyan] (the existing neon #FFC56B) for glow / active states, [TealDeep] for surfaces.
 *  - Pink is affection: [Pink] (#8EC5FF) for hearts, likes, soft alerts; [PinkHot]
 *    (#8EC5FF) for emphasis.
 *  - Semantic states are still on-brand: [Leek] green = go/ok (her leek, not Material
 *    #00E676), [Gold] = caution, [Coral] = danger/hot (not Material #FF1744).
 *  - Kawaii garnish (hearts ♥, sparkles ✦, blush) is ≤ 12dp and only at moments of delight —
 *    like, unlock, drop, launch. Never as ambient noise.
 */
object HioneIdentity {
    // ---- Identity palette ----
    val Teal get() = Mascot.color(0xFFFFAB2E)
    val TealBright get() = Mascot.color(0xFFFFE0B0)
    val TealDeep get() = Mascot.color(0xFF1B1C22)
    val TealSurface get() = Mascot.color(0xFF16302D)
    val Cyan = HioneCyan
    val Pink get() = Mascot.color(0xFF8EC5FF)
    val PinkHot get() = Mascot.color(0xFF8EC5FF)
    val PinkNeon = HioneNeonPink
    val Lavender get() = Mascot.color(0xFFB9C2D0)
    val Cream = Color(0xFFFFF4E6)

    // ---- Pastel kawaii tints (backgrounds of badges / empty states) ----
    val PastelMint get() = Mascot.color(0xFFB9F1EA)
    val PastelPink get() = Mascot.color(0xFFFFD1DF)
    val PastelSky = Color(0xFFCFE9FF)
    val PastelLilac = Color(0xFFE5D4FF)

    // ---- Semantic (replaces Material #00E676 / #FFD600 / #FF1744 across the launcher) ----
    /** Go / OK / enabled — leek green. */
    val Leek = Color(0xFF7CE38B)
    /** Caution / medium. */
    val Gold = HioneGold
    /** Danger / hot / off — coral (pink-red, never Material red). */
    val Coral = Color(0xFFFF5C7A)
    /** Neutral "off" chrome. */
    val Muted = Color(0xFF8BA6A9)

    // ---- Surfaces ----
    val Bg = CyberDarkBg
    val Glass = CyberGlassCard
    val GlassBorder = CyberGlassBorder
    val Stitch = Teal.copy(alpha = 0.55f)
    val TextPrimary = HioneTextPrimary
    val TextSecondary = HioneTextSecondary

    // ---- Glyph set ----
    const val HEART = "♥"
    const val SPARKLE = "✦"
    const val STAR = "★"
    const val NOTE = "♪"
    const val BOLT = "⚡"
    const val MOON = "☾"
    const val LEEK = "🥬"
    const val BLUSH = "◕‿◕"
    /** Chibi faces for empty / error states. */
    const val CHIBI_HAPPY = "(◕‿◕✿)"
    const val CHIBI_SLEEPY = "(￣ o ￣) zzZ"
    const val CHIBI_OOPS = "(・_・;)"
    const val CHIBI_SEARCH = "(⊙_⊙)?"
    /** Lore numbers — used only as tiny easter eggs (serials, footers). */
    const val LORE_39 = "39"
    const val LORE_0831 = "08/31"

    // ---- Text styles (fonts from CyberTheme) ----
    val Title = TextStyle(fontFamily = AudiowideFont, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = TextPrimary)
    val Section = TextStyle(fontFamily = AudiowideFont, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = TextPrimary, letterSpacing = 0.5.sp)
    val Body = TextStyle(fontFamily = AudiowideFont, fontWeight = FontWeight.Normal, fontSize = 13.sp, color = TextPrimary)
    val Label = TextStyle(fontFamily = AudiowideFont, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = TextSecondary, letterSpacing = 0.4.sp)
    val Caption = TextStyle(fontFamily = AudiowideFont, fontWeight = FontWeight.Normal, fontSize = 11.sp, color = TextSecondary)

    /** Tile / badge accent for on-off state, kept on-brand. */
    fun stateAccent(on: Boolean): Color = if (on) Leek else PinkNeon
}

/**
 * Uniform empty / error state: a chibi face, one line of copy, optional hint. Used by the
 * drawer (no search results), recents, and modals when they have nothing to show.
 */
@Composable
fun HioneEmptyState(
    face: String,
    text: String,
    hint: String? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(face, color = HioneIdentity.Teal, fontSize = 22.sp, fontFamily = AudiowideFont, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(text, style = HioneIdentity.Body, textAlign = TextAlign.Center)
        if (hint != null) {
            Spacer(Modifier.height(4.dp))
            Text(hint, style = HioneIdentity.Caption, textAlign = TextAlign.Center)
        }
    }
}
