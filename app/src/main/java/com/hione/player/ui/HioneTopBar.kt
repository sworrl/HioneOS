package com.hione.player.ui

import com.hione.player.Mascot
import com.hione.player.mascotPainter
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.player.AudiowideFont
import com.hione.player.HapticIconButton
import com.hione.player.HioneArt
import com.hione.player.HioneTealBright

/**
 * System-wide Standard Oki Hione Branded Back Button.
 * Replicates the exact size, placement, and springy haptic tactile feel across every screen.
 */
@Composable
fun HioneBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = HioneTealBright
) {
    HapticIconButton(
        onClick = onClick,
        flat = true,
        modifier = modifier.size(38.dp)
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back",
            tint = tint,
            modifier = Modifier.size(24.dp)
        )
    }
}

/**
 * THE Hione Music brand mark — the chibi-hearts glyph plus the Audiowide "Hione Music" wordmark —
 * one definition used by every title bar (home header, Now Playing, settings pages) so the
 * branding is identical everywhere instead of each screen hand-rolling its own header text.
 * [wordmark] = false gives just the glyph for cramped bars.
 */
@Composable
fun HioneBrandMark(
    modifier: Modifier = Modifier,
    tint: Color = HioneTealBright,
    fontSize: TextUnit = 15.sp,
    glyphSize: Dp = 24.dp,
    wordmark: Boolean = true,
    text: String = "${Mascot.musicAppName}",
    letterSpacing: TextUnit = 0.5.sp,
    glyphAlpha: Float = 1f
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = mascotPainter(HioneArt.chibiHearts),
            contentDescription = "${Mascot.musicAppName}",
            modifier = Modifier.size(glyphSize).alpha(glyphAlpha)
        )
        if (wordmark) {
            Spacer(Modifier.width(5.dp))
            Text(
                text,
                color = tint,
                fontSize = fontSize,
                fontWeight = FontWeight.Bold,
                fontFamily = AudiowideFont,
                letterSpacing = letterSpacing,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * System-wide Standard Oki Hione Top Bar Header.
 * Combines the branded back button, Audiowide title, the Hione brand glyph, and optional actions.
 */
@Composable
fun HioneTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    titleColor: Color = Color.White,
    showBrand: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HioneBackButton(onClick = onBack)
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            color = titleColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = AudiowideFont,
            letterSpacing = 1.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (showBrand) {
            // Glyph-only on sub-pages: the page title owns the bar, the mascot just signs it.
            HioneBrandMark(wordmark = false, glyphSize = 22.dp, glyphAlpha = 0.9f)
            Spacer(Modifier.width(6.dp))
        }
        actions()
    }
}
