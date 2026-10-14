package com.hione.launcher.menu

import com.hione.launcher.Mascot
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hione.launcher.*
import com.hione.launcher.ui.swipeUpFromBottomToDismiss

/**
 * Oki Hione Lifted AOSP Launcher3 Long-Press Desktop Options Menu.
 * Replaces stock AOSP OptionsPopupView with a bespoke, themed Kotlin Compose implementation
 * featuring Wallpaper & Style, Widgets, and HioneOS Home Settings.
 */
@Composable
fun HioneHomescreenLongpressMenu(
    onWallpaperAndStyle: () -> Unit,
    onQuiltOptions: () -> Unit = {},
    onWidgets: () -> Unit,
    onHomeSettings: () -> Unit,
    onOpenProfile: () -> Unit = {},
    onToggleArtworkPeek: () -> Unit = {},
    onDismiss: () -> Unit
) {
    // Pixel popup feel: the card scales 0.85 → 1 with a slight overshoot (~150 ms) and fades in.
    val appear = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.62f, stiffness = 1100f)) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x99000000))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
                // System-gesture-style dismiss: swipe up starting at the bottom edge of the scrim.
                .swipeUpFromBottomToDismiss(onDismiss = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        val sc = 0.85f + 0.15f * appear.value
                        scaleX = sc; scaleY = sc
                        alpha = appear.value.coerceIn(0f, 1f)
                    }
                    .width(260.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Mascot.color(0xF2071F2B),
                                Mascot.color(0xF5030F15)
                            )
                        )
                    )
                    .border(
                        BorderStroke(
                            1.2.dp,
                            Brush.verticalGradient(
                                listOf(
                                    HioneCyan.copy(alpha = 0.9f),
                                    CyberGlassBorder.copy(alpha = 0.35f),
                                    HioneNeonPink.copy(alpha = 0.7f)
                                )
                            )
                        ),
                        RoundedCornerShape(22.dp)
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {}
                    .padding(vertical = 10.dp, horizontal = 12.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Header Pill
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "HOME SCREEN",
                            color = HioneCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = AudiowideFont,
                            letterSpacing = 0.8.sp
                        )
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(HioneNeonPink)
                        )
                    }

                    HorizontalDivider(
                        color = CyberGlassBorder.copy(alpha = 0.4f),
                        thickness = 1.dp,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )

                    // 1. Wallpaper & Style (AOSP 1:1)
                    AospMenuItem(
                        title = "Wallpaper & style",
                        subtitle = "Themes, live art and colors",
                        icon = Icons.Default.Palette,
                        iconTint = HioneCyan,
                        onClick = {
                            onDismiss()
                            onWallpaperAndStyle()
                        }
                    )

                    // 2. Widgets (AOSP 1:1)
                    AospMenuItem(
                        title = "Widgets and panels",
                        subtitle = "Visualizers and meters",
                        icon = Icons.Default.Widgets,
                        iconTint = com.hione.launcher.ui.HioneIdentity.Gold,
                        onClick = {
                            onDismiss()
                            onWidgets()
                        }
                    )

                    // 3. Home Settings (AOSP 1:1)
                    AospMenuItem(
                        title = "Badges",
                        subtitle = "Order, size, rows and background",
                        icon = Icons.Default.Dashboard,
                        iconTint = HioneCyan,
                        onClick = {
                            onDismiss()
                            onQuiltOptions()
                        }
                    )
                    AospMenuItem(
                        title = "User profile",
                        subtitle = "Switch user, avatar and telemetry",
                        icon = Icons.Default.AccountCircle,
                        iconTint = HioneCyan,
                        onClick = {
                            onDismiss()
                            onOpenProfile()
                        }
                    )
                    AospMenuItem(
                        title = "Peek artwork",
                        subtitle = "Collapse widgets to reveal Hione",
                        icon = Icons.Default.Visibility,
                        iconTint = HioneNeonPink,
                        onClick = {
                            onDismiss()
                            onToggleArtworkPeek()
                        }
                    )
                    AospMenuItem(
                        title = "Home settings",
                        subtitle = "Dock, gestures and grid",
                        icon = Icons.Default.Tune,
                        iconTint = HioneNeonPink,
                        onClick = {
                            onDismiss()
                            onHomeSettings()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AospMenuItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconTint: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(iconTint.copy(alpha = 0.18f))
                .border(1.dp, iconTint.copy(alpha = 0.55f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = iconTint,
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(Modifier.width(12.dp))

        Column {
            Text(
                text = title,
                color = Color.White,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = AudiowideFont
            )
            Text(
                text = subtitle,
                color = HioneTextSecondary,
                fontSize = 9.sp
            )
        }
    }
}
