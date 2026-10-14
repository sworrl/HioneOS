package com.hione.systemui.statusbar

import com.hione.systemui.Mascot
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text

/**
 * HioneOS unified status-bar badge system.
 *
 * Design intent: every badge in the top bar reads as one family — they share the
 * same "connective tissue" (bar height, cut-corner geometry, layered glass depth,
 * specular top-light, holographic rim) — while each keeps its own identity through
 * its [accentColor] and glyph. Connected, but individual.
 *
 * This is the single source of truth for status-bar badge chrome. Individual
 * badges are thin wrappers over [HioneStatusBadge]; do not re-roll bespoke chrome.
 */
object HioneBrand {
    // Palette — aligned with the launcher's Cyber tokens so the OS reads as one skin.
    val Cyan get() = Mascot.color(0xFFFFC56B)
    val NeonPink get() = Mascot.color(0xFF6FB0FF)
    val Gold = Color(0xFFFFD54F)
    val Green = Color(0xFF00FF7F)
    val TextPrimary get() = Mascot.color(0xFFFFF3E0)
    val TextSecondary get() = Mascot.color(0xFFFFD59A)
    val GlassBorder get() = Mascot.color(0x33FFC56B)

    // TODO(font): ship Audiowide/Orbitron TTFs to this module's res/font and
    // replace these — launcher/systemui/settings currently fall back to monospace.
    val DisplayFont = FontFamily.Monospace

    /**
     * Branding tokens with defined roles:
     *  - [IdentityMark] "//01" is the CV01 identity/brand mark — logos, splash,
     *    section headers, the "this is HioneOS" stamp. Use where a wordmark goes.
     *  - [AccentNumber] "39" ("Mi-Ku") is the accent/easter-egg number — counters,
     *    flourishes, the March-9 wink. Use as garnish, never as the primary mark.
     */
    const val IdentityMark = "//01"
    const val AccentNumber = "39"
}

/** Shared bar geometry so every badge lines up on one baseline. */
object HioneStatusBarMetrics {
    val BadgeHeight = 20.dp
    val BadgeCorner = 5.dp
    val BadgeGap = 5.dp
    val GlyphText = 9.sp
    val LabelText = 9.sp
}

/**
 * The one badge primitive. Layered top-down: outer specular glass highlight →
 * inner tinted glass body → holographic accent rim. Height and corner are fixed
 * from [HioneStatusBarMetrics] so the family stays visually locked together.
 */
@Composable
fun HioneStatusBadge(
    accentColor: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: Shape = CutCornerShape(HioneStatusBarMetrics.BadgeCorner),
    bodyGradient: List<Color> = listOf(Mascot.color(0xEE0A222C), Mascot.color(0xFF041218)),
    content: @Composable RowScope.() -> Unit
) {
    Box(
        modifier = modifier
            .height(HioneStatusBarMetrics.BadgeHeight)
            .clip(shape)
            // Specular top-light shared by the whole family.
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.16f),
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.55f)
                    )
                )
            )
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .padding(0.8.dp)
                .clip(shape)
                .background(Brush.verticalGradient(bodyGradient))
                .border(
                    BorderStroke(
                        0.9.dp,
                        Brush.linearGradient(
                            listOf(
                                accentColor.copy(alpha = 0.95f),
                                HioneBrand.GlassBorder.copy(alpha = 0.35f),
                                accentColor.copy(alpha = 0.7f)
                            )
                        )
                    ),
                    shape
                )
                .padding(horizontal = 4.5.dp, vertical = 1.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                content = content
            )
        }
    }
}

/** A short glyph/value label in the family font, tinted to a badge's accent. */
@Composable
fun HioneBadgeLabel(
    text: String,
    color: Color,
    weight: FontWeight = FontWeight.Black,
    sizeSp: androidx.compose.ui.unit.TextUnit = HioneStatusBarMetrics.LabelText
) {
    Text(
        text = text,
        color = color,
        fontSize = sizeSp,
        fontWeight = weight,
        fontFamily = HioneBrand.DisplayFont,
        textAlign = TextAlign.Center
    )
}

/**
 * The HioneOS identity stamp ("//01"). Anchors the left edge of the status bar so
 * the whole strip is unmistakably ours. Uses the same badge chrome so it sits in
 * the family rather than floating apart.
 */
@Composable
fun HioneIdentityBadge(onClick: (() -> Unit)? = null) {
    HioneStatusBadge(
        accentColor = HioneBrand.Cyan,
        onClick = onClick,
        bodyGradient = listOf(Mascot.color(0x33FFC56B), Mascot.color(0xFF041218))
    ) {
        HioneBadgeLabel(HioneBrand.IdentityMark, HioneBrand.Cyan, sizeSp = 9.5.sp)
    }
}

/**
 * Now-playing badge. Real title flows in from the media bridge; the animated
 * equalizer bars are the shared "audio is live" motif reused from the launcher.
 * When [title] is null it collapses to nothing (caller should gate on playback).
 */
@Composable
fun HioneNowPlayingBadge(
    title: String?,
    onClick: (() -> Unit)? = null
) {
    if (title == null) return
    val t = rememberInfiniteTransition(label = "np")
    val b1 by t.animateFloat(0.25f, 1.0f, infiniteRepeatable(tween(350), RepeatMode.Reverse), label = "b1")
    val b2 by t.animateFloat(0.85f, 0.3f, infiniteRepeatable(tween(450), RepeatMode.Reverse), label = "b2")
    val b3 by t.animateFloat(0.4f, 0.95f, infiniteRepeatable(tween(300), RepeatMode.Reverse), label = "b3")

    HioneStatusBadge(
        accentColor = HioneBrand.NeonPink,
        onClick = onClick,
        bodyGradient = listOf(Mascot.color(0x446FB0FF), Mascot.color(0xFF160410)),
        shape = CutCornerShape(4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(1.dp),
            modifier = Modifier.height(8.dp)
        ) {
            Box(Modifier.width(1.5.dp).height((8 * b1).dp).clip(RoundedCornerShape(0.4.dp)).background(HioneBrand.NeonPink))
            Box(Modifier.width(1.5.dp).height((8 * b2).dp).clip(RoundedCornerShape(0.4.dp)).background(HioneBrand.Cyan))
            Box(Modifier.width(1.5.dp).height((8 * b3).dp).clip(RoundedCornerShape(0.4.dp)).background(HioneBrand.Green))
        }
        Spacer(Modifier.width(3.dp))
        HioneBadgeLabel(title.take(18), HioneBrand.TextPrimary, weight = FontWeight.Bold, sizeSp = 8.5.sp)
    }
}

/** Generic metric badge (clock, thermal, volume, battery, DAC, BPM …). */
@Composable
fun HioneMetricBadge(
    accentColor: Color,
    value: String,
    modifier: Modifier = Modifier,
    glyph: String? = null,
    onClick: (() -> Unit)? = null
) {
    HioneStatusBadge(accentColor = accentColor, onClick = onClick, modifier = modifier) {
        if (glyph != null) {
            HioneBadgeLabel(glyph, accentColor, sizeSp = HioneStatusBarMetrics.GlyphText)
            Spacer(Modifier.width(3.dp))
        }
        HioneBadgeLabel(value, HioneBrand.TextPrimary, weight = FontWeight.Bold)
    }
}
