package com.hione.launcher.ingest

import com.hione.launcher.Mascot
import com.hione.launcher.ui.gatedFloat
import com.hione.launcher.ui.gatedColor
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.launcher.AudiowideFont
import com.hione.launcher.CyberBespokeBadge
import com.hione.launcher.CyberGlassBorder
import com.hione.launcher.HioneCyan
import com.hione.launcher.HioneNeonPink

/**
 * Oki Hione Ingestion & File System Telemetry Badge for Top-Bar Quilt.
 * Displays real-time library ingestion state, track counter (e.g. 11k), format tiers (DSD/FLAC),
 * and live pulsing animation during active MediaScanner / Rsync runs.
 */
@Composable
fun HioneIngestionBadge(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { HioneIngestEngine.init(ctx) }

    val ingestState by HioneIngestEngine.state.collectAsState()

    val lowPowerGate by com.hione.launcher.ui.rememberAmbientGate()

    val infinitePulse = rememberInfiniteTransition(label = "IngestPulse")
    val pulseAlpha by infinitePulse.gatedFloat(lowPowerGate, 
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "IngestPulseAlpha"
    )

    val isScanning = ingestState.isScanning
    val accentColor = if (isScanning) Color(0xFFFFD54F) else Color(0xFF00FF88) // Cyber Emerald / Gold

    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    val effectiveScale = 1.0f + (fontScale - 1.0f) * 0.20f
    val badgeFontSize = (7.8f / fontScale * effectiveScale).sp

    val badgeShape = remember { CutCornerShape(4.dp) }

    CyberBespokeBadge(
        onClick = onClick,
        modifier = modifier,
        accentColor = accentColor,
        shape = badgeShape,
        gradient = listOf(
            if (isScanning) Color(0x33FFD54F) else Color(0x2800FF88),
            Mascot.color(0xFF031410)
        ),
        borderAlphaBase = if (isScanning) pulseAlpha else 0.85f
    ) {
        // Live LED indicator dot
        Box(
            Modifier
                .size(4.dp)
                .clip(CircleShape)
                .background(accentColor.copy(alpha = if (isScanning) pulseAlpha else 1f))
        )
        Spacer(Modifier.width(2.5.dp))

        // Ingestion Text
        val labelText = if (isScanning) {
            "ING ${(ingestState.scanProgress * 100).toInt()}%"
        } else {
            "FS ${ingestState.abbreviatedTracks}"
        }

        Text(
            text = labelText,
            color = if (isScanning) Color(0xFFFFD54F) else Color(0xFF00FF88),
            fontSize = badgeFontSize,
            fontWeight = FontWeight.Black,
            fontFamily = AudiowideFont,
            maxLines = 1
        )
    }
}
