package com.hione.launcher.volume

import com.hione.launcher.Mascot
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.launcher.AudiowideFont
import com.hione.launcher.HioneCyan

/**
 * Settings Card for Global Volume HUD Layout & Dialog Engine.
 * Allows choosing between:
 * 1. Right-Edge Cyber Bar (Default HioneOS System HUD)
 * 2. Center Cyber Dial / Modal (Hione Music Bespoke HUD)
 * Plus optional toggle for HiBy legacy stock full-screen overlay.
 */
@Composable
fun HioneVolumeUiSettingRow(
    modifier: Modifier = Modifier,
    ctx: Context = LocalContext.current
) {
    var useHiby by remember { mutableStateOf(HioneVolumeManager.useHibyDialog(ctx)) }
    var selectedStyle by remember { mutableStateOf(HioneVolumeManager.getVolumeHudStyle(ctx)) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clipToRoundedGlass()
            .padding(14.dp)
    ) {
        Text(
            text = "VOLUME POPUP STYLE",
            color = HioneCyan,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = AudiowideFont,
            letterSpacing = 0.3.sp
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Pick the volume popup that shows when you change the volume:",
            color = Mascot.color(0xFF9EC4C7),
            fontSize = 11.5.sp,
            lineHeight = 15.sp
        )

        Spacer(Modifier.height(12.dp))

        // Option 1: Right-Edge Cyber Bar (Default)
        VolumeStyleOptionCard(
            title = "Right-edge bar (default)",
            description = "A vertical bar on the right edge. You can drag it.",
            isSelected = selectedStyle == VolumeHudStyle.RIGHT_CYBER_BAR,
            onClick = {
                selectedStyle = VolumeHudStyle.RIGHT_CYBER_BAR
                HioneVolumeManager.setVolumeHudStyle(ctx, VolumeHudStyle.RIGHT_CYBER_BAR)
                HioneVolumeManager.triggerHud(ctx)
            }
        )

        Spacer(Modifier.height(8.dp))

        // Option 2: Center Cyber Arc / Modal (Hione Music Style)
        VolumeStyleOptionCard(
            title = "Center popup",
            description = "A centered popup with a loudness warning meter (the original ${Mascot.musicAppName} style)",
            isSelected = selectedStyle == VolumeHudStyle.CENTER_CYBER_MODAL,
            onClick = {
                selectedStyle = VolumeHudStyle.CENTER_CYBER_MODAL
                HioneVolumeManager.setVolumeHudStyle(ctx, VolumeHudStyle.CENTER_CYBER_MODAL)
                HioneVolumeManager.triggerHud(ctx)
            }
        )

        Spacer(Modifier.height(14.dp))

        // Legacy HiBy Fullscreen Dialog Toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Stock HiBy fullscreen popup",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (useHiby) "On (the stock HiBy wheel popup)" else "Off (the ${Mascot.osName} popup is used)",
                    color = Mascot.color(0xFF7FB9C6),
                    fontSize = 10.sp
                )
            }
            Switch(
                checked = useHiby,
                onCheckedChange = {
                    useHiby = it
                    HioneVolumeManager.setUseHibyDialog(ctx, it)
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Mascot.color(0xFFFFC56B),
                    checkedTrackColor = Mascot.color(0x55FFC56B),
                    uncheckedThumbColor = Mascot.color(0xFF7FB9C6),
                    uncheckedTrackColor = Color(0x33FFFFFF)
                )
            )
        }
    }
}

@Composable
private fun VolumeStyleOptionCard(
    title: String,
    description: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isSelected) Mascot.color(0x33FFC56B) else Color(0x14FFFFFF),
                RoundedCornerShape(10.dp)
            )
            .border(
                if (isSelected) 1.2.dp else 0.8.dp,
                if (isSelected) HioneCyan else Color(0x28FFFFFF),
                RoundedCornerShape(10.dp)
            )
            .clickable { onClick() }
            .padding(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (isSelected) HioneCyan else Mascot.color(0xFF7FB9C6),
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = if (isSelected) Color.White else Mascot.color(0xFFD0E0E3),
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = description,
                    color = Color(0xFF8BA6AC),
                    fontSize = 11.sp,
                    lineHeight = 14.sp
                )
            }
        }
    }
}

private fun Modifier.clipToRoundedGlass(): Modifier = this
    .background(
        Brush.horizontalGradient(listOf(Mascot.color(0x22FFC56B), Color(0x11000000))),
        RoundedCornerShape(14.dp)
    )
    .border(1.2.dp, Mascot.color(0x33FFC56B), RoundedCornerShape(14.dp))
