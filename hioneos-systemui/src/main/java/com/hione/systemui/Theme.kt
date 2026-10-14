package com.hione.systemui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val HioneTeal get() = Mascot.color(0xFFFFAB2E)
val HioneTealBright get() = Mascot.color(0xFFFFC56B)
val HionePink get() = Mascot.color(0xFF8EC5FF)
val HionePinkBright get() = Mascot.color(0xFF6FB0FF)
val HionePurple get() = Mascot.color(0xFFB9C2D0)
val HioneGold = Color(0xFFFFD54F)
val HioneCyan get() = Mascot.color(0xFFFFC56B)
val HioneNeonPink get() = Mascot.color(0xFF6FB0FF)
val HioneTextPrimary get() = Mascot.color(0xFFFFF3E0)
val HioneTextSecondary get() = Mascot.color(0xFFFFD59A)
val HioneDarkBg get() = Mascot.color(0xFF0C0D10)
val HioneSurface1 get() = Mascot.color(0xFF071922)
val HioneSurface2 get() = Mascot.color(0xFF0E242C)
val HioneCardBg get() = Mascot.color(0xFF06141B)
val HioneMuted = Color(0xFF8BA6A9)
val HioneWhite get() = Mascot.color(0xFFF0FDFB)
val CyberGlassBorder get() = Mascot.color(0x33FFC56B)
val CyberGlassCard get() = Mascot.color(0xDD0A1E26)
val AudiowideFont = androidx.compose.ui.text.font.FontFamily.Monospace
val OrbitronFont = androidx.compose.ui.text.font.FontFamily.Monospace

fun Modifier.hioneTile(isActive: Boolean) = this
    .clip(RoundedCornerShape(14.dp))
    .background(
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = if (isActive) 0.22f else 0.10f),
                Color.Transparent,
                Color.Black.copy(alpha = 0.60f)
            )
        )
    )
    .padding(0.8.dp)
    .clip(RoundedCornerShape(13.dp))
    .background(
        if (isActive)
            Brush.verticalGradient(listOf(Mascot.color(0xFF0E3842), Mascot.color(0xFF082228), Mascot.color(0xFF030E12)))
        else
            Brush.verticalGradient(listOf(Mascot.color(0xFF0B2129), Mascot.color(0xFF05131A), Mascot.color(0xFF02090D)))
    )
    .border(
        1.1.dp,
        if (isActive)
            Brush.linearGradient(listOf(HioneTealBright, HionePurple, HioneTealBright))
        else
            Brush.verticalGradient(listOf(CyberGlassBorder.copy(alpha = 0.5f), Color.Transparent, CyberGlassBorder.copy(alpha = 0.25f))),
        RoundedCornerShape(13.dp)
    )

fun Modifier.hioneCard(
    borderColor: Color = HioneTeal.copy(alpha = 0.35f),
    cornerRadius: Int = 16
) = this
    .clip(RoundedCornerShape(cornerRadius.dp))
    .background(HioneCardBg)
    .border(1.dp, borderColor, RoundedCornerShape(cornerRadius.dp))

fun Modifier.hioneHeroCard(
    startColor: Color = Mascot.color(0xFF0D2529),
    endColor: Color = Mascot.color(0xFF071215)
) = this
    .clip(RoundedCornerShape(18.dp))
    .background(Brush.verticalGradient(listOf(startColor, endColor)))
    .border(1.2.dp, HioneTealBright.copy(alpha = 0.5f), RoundedCornerShape(18.dp))

fun Modifier.hioneGlassCard() = this
    .clip(RoundedCornerShape(16.dp))
    .background(
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = 0.18f),
                Color.Transparent,
                Color.Black.copy(alpha = 0.55f)
            )
        )
    )
    .padding(0.9.dp)
    .clip(RoundedCornerShape(15.dp))
    .background(
        Brush.verticalGradient(
            listOf(
                Mascot.color(0xEE0D2630),
                Mascot.color(0xFF07171E),
                Mascot.color(0xFA030B0F)
            )
        )
    )
    .border(
        1.1.dp,
        Brush.linearGradient(
            listOf(
                HioneTealBright.copy(alpha = 0.85f),
                HionePurple.copy(alpha = 0.45f),
                HionePinkBright.copy(alpha = 0.65f)
            )
        ),
        RoundedCornerShape(15.dp)
    )
