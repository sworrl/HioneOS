package com.hione.systemui

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

fun cyber24BitColorShift(phaseDeg: Float, saturation: Float = 0.88f, brightness: Float = 1.0f, alpha: Float = 1.0f): Color {
    val normHue = (phaseDeg % 360f + 360f) % 360f
    val hsv = floatArrayOf(normHue, saturation.coerceIn(0f, 1f), brightness.coerceIn(0f, 1f))
    val argb = android.graphics.Color.HSVToColor((alpha.coerceIn(0f, 1f) * 255).toInt(), hsv)
    return Color(argb)
}

@Composable
fun CyberHeartGlyph(
    color: Color,
    size: androidx.compose.ui.unit.Dp = 9.5.dp
) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.5f, h * 0.88f)
            cubicTo(w * 0.08f, h * 0.58f, 0f, h * 0.38f, 0f, h * 0.24f)
            cubicTo(0f, h * 0.08f, w * 0.18f, 0f, w * 0.36f, 0f)
            cubicTo(w * 0.44f, 0f, w * 0.5f, h * 0.08f, w * 0.5f, h * 0.16f)
            cubicTo(w * 0.5f, h * 0.08f, w * 0.56f, 0f, w * 0.64f, 0f)
            cubicTo(w * 0.82f, 0f, w, h * 0.08f, w, h * 0.24f)
            cubicTo(w, h * 0.38f, w * 0.92f, h * 0.58f, w * 0.5f, h * 0.88f)
            close()
        }
        drawPath(path = path, color = color)
    }
}

@Composable
fun KawaiiHeartColon(
    color: Color,
    scale: Float,
    size: androidx.compose.ui.unit.Dp = 9.5.dp,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
        modifier = modifier
            .padding(horizontal = 3.dp)
            .scale(scale)
    ) {
        CyberHeartGlyph(color = color, size = size)
        CyberHeartGlyph(color = color, size = size)
    }
}

@Composable
fun KawaiiAnimatedDigitPair(
    digits: String,
    color: Color,
    fontSize: androidx.compose.ui.unit.TextUnit = 32.sp,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        digits.forEachIndexed { idx, char ->
            AnimatedContent(
                targetState = char,
                transitionSpec = {
                    (slideInVertically(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessLow
                        )
                    ) { height -> -height } + fadeIn(tween(180)) + scaleIn(
                        initialScale = 0.65f,
                        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
                    )) togetherWith (slideOutVertically(
                        animationSpec = tween(220, easing = FastOutSlowInEasing)
                    ) { height -> height } + fadeOut(tween(160)) + scaleOut(
                        targetScale = 1.18f,
                        animationSpec = tween(220)
                    ))
                },
                label = "KawaiiDigit_${idx}_$char"
            ) { targetChar ->
                Text(
                    text = targetChar.toString(),
                    color = color,
                    fontSize = fontSize,
                    fontWeight = FontWeight.Black,
                    fontFamily = AudiowideFont,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}

@Composable
fun CyberPlasmaGlowClock(
    time: String,
    date: String
) {
    val infiniteTransition = rememberInfiniteTransition(label = "ClockPlasmaPulse")

    val colorShiftPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "24BitColorShift"
    )

    val secondTick by produceState(initialValue = (System.currentTimeMillis() / 1000) % 2) {
        while (true) {
            val sec = (System.currentTimeMillis() / 1000)
            value = sec % 2
            val msToNextSec = 1000L - (System.currentTimeMillis() % 1000L)
            delay(msToNextSec.coerceIn(50L, 1000L))
        }
    }

    val heartbeatScale = remember { Animatable(1.0f) }
    LaunchedEffect(secondTick) {
        heartbeatScale.snapTo(1.32f)
        heartbeatScale.animateTo(
            targetValue = 1.0f,
            animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing)
        )
    }

    val hoursShiftColor = cyber24BitColorShift(colorShiftPhase, saturation = 0.85f, brightness = 1.0f)
    val hoursCoreColor = cyber24BitColorShift(colorShiftPhase, saturation = 0.12f, brightness = 1.0f)

    val colonHarmonicOffset = if (secondTick == 0L) 25f else 165f
    val colonHue = colorShiftPhase + colonHarmonicOffset
    val colonColor = cyber24BitColorShift(colonHue, saturation = 0.95f, brightness = 1.0f)
    val colonHaloColor = cyber24BitColorShift(colonHue + 20f, saturation = 0.85f, brightness = 0.95f)

    val minutesShiftColor = cyber24BitColorShift(colorShiftPhase + 50f, saturation = 0.85f, brightness = 1.0f)
    val minutesCoreColor = cyber24BitColorShift(colorShiftPhase + 50f, saturation = 0.12f, brightness = 1.0f)

    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.40f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PlasmaAlpha"
    )

    val shimmerPhase by infiniteTransition.animateFloat(
        initialValue = -2.8f,
        targetValue = 2.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(3200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ChromaticPhase"
    )

    val particlePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ParticlePhase"
    )

    val scanlineY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ScanlineY"
    )

    val bar1 by infiniteTransition.animateFloat(initialValue = 0.25f, targetValue = 0.95f, animationSpec = infiniteRepeatable(tween(420), RepeatMode.Reverse), label = "b1")
    val bar2 by infiniteTransition.animateFloat(initialValue = 0.85f, targetValue = 0.20f, animationSpec = infiniteRepeatable(tween(580), RepeatMode.Reverse), label = "b2")
    val bar3 by infiniteTransition.animateFloat(initialValue = 0.35f, targetValue = 1.0f, animationSpec = infiniteRepeatable(tween(340), RepeatMode.Reverse), label = "b3")
    val bar4 by infiniteTransition.animateFloat(initialValue = 0.90f, targetValue = 0.45f, animationSpec = infiniteRepeatable(tween(510), RepeatMode.Reverse), label = "b4")
    val bar5 by infiniteTransition.animateFloat(initialValue = 0.20f, targetValue = 0.80f, animationSpec = infiniteRepeatable(tween(390), RepeatMode.Reverse), label = "b5")
    val bar6 by infiniteTransition.animateFloat(initialValue = 0.70f, targetValue = 0.30f, animationSpec = infiniteRepeatable(tween(620), RepeatMode.Reverse), label = "b6")

    val timeParts = remember(time) {
        if (time.contains(":")) {
            val idx = time.indexOf(":")
            Pair(time.substring(0, idx), time.substring(idx + 1))
        } else {
            Pair(time, "")
        }
    }

    Column {
        Box(contentAlignment = Alignment.CenterStart) {
            Canvas(
                modifier = Modifier
                    .size(width = 135.dp, height = 40.dp)
            ) {
                val particles = listOf(
                    Triple(0.12f, 0.25f, Mascot.color(0xFFFFC56B)),
                    Triple(0.28f, 0.65f, Mascot.color(0xFFFF007F)),
                    Triple(0.45f, 0.15f, Mascot.color(0xFF00FFCC)),
                    Triple(0.58f, 0.80f, Color(0xFFFFFFFF)),
                    Triple(0.72f, 0.35f, Mascot.color(0xFFFFC56B)),
                    Triple(0.85f, 0.70f, Mascot.color(0xFFFF007F)),
                    Triple(0.92f, 0.20f, Color(0xFF76FF03)),
                    Triple(0.38f, 0.90f, Mascot.color(0xFFFFC56B)),
                    Triple(0.65f, 0.45f, Color(0xFFFFD600))
                )

                particles.forEachIndexed { i, p ->
                    val baseX = p.first * size.width
                    val baseY = p.second * size.height
                    val driftY = (baseY - (particlePhase * size.height) + (i * 12f)) % size.height
                    val wobbleX = baseX + (kotlin.math.sin((particlePhase * 6.28f) + i) * 6f).toFloat()
                    val pAlpha = (kotlin.math.sin((particlePhase * 3.14f) + (i * 0.7f)).toFloat().coerceIn(0.15f, 0.9f)) * glowAlpha
                    val radius = if (i % 2 == 0) 2.2f else 1.5f

                    drawCircle(
                        color = p.third.copy(alpha = pAlpha),
                        radius = radius,
                        center = Offset(wobbleX, driftY)
                    )
                }

                val scanY = scanlineY * size.height
                drawLine(
                    brush = Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            hoursShiftColor.copy(alpha = 0.45f),
                            Color.White.copy(alpha = 0.75f),
                            minutesShiftColor.copy(alpha = 0.45f),
                            Color.Transparent
                        )
                    ),
                    start = Offset(0f, scanY),
                    end = Offset(size.width, scanY),
                    strokeWidth = 1.2f
                )
            }

            // Layer 2: Radiant Volumetric 24-Bit Plasma Halo
            Box(
                Modifier
                    .offset(x = 1.dp, y = 2.dp)
                    .size(width = 135.dp, height = 40.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                hoursShiftColor.copy(alpha = glowAlpha * 0.65f),
                                minutesShiftColor.copy(alpha = glowAlpha * 0.30f),
                                Color.Transparent
                            )
                        )
                    )
            )

            // Layer 3: Chromatic Aberration Fringe (Offset 1)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.offset(x = (-1.2).dp + (shimmerPhase * 0.25f).dp, y = (-0.7).dp)
            ) {
                KawaiiAnimatedDigitPair(
                    digits = timeParts.first,
                    color = cyber24BitColorShift(colorShiftPhase + 180f, saturation = 0.9f, brightness = 0.95f, alpha = glowAlpha * 0.80f),
                    fontSize = 36.sp
                )
                KawaiiHeartColon(
                    color = colonHaloColor.copy(alpha = 0.9f),
                    scale = heartbeatScale.value
                )
                KawaiiAnimatedDigitPair(
                    digits = timeParts.second,
                    color = cyber24BitColorShift(colorShiftPhase + 230f, saturation = 0.9f, brightness = 0.95f, alpha = glowAlpha * 0.80f),
                    fontSize = 36.sp
                )
            }

            // Layer 4: Chromatic Aberration Fringe (Offset 2)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.offset(x = (1.2).dp - (shimmerPhase * 0.25f).dp, y = (0.9).dp)
            ) {
                KawaiiAnimatedDigitPair(
                    digits = timeParts.first,
                    color = cyber24BitColorShift(colorShiftPhase + 290f, saturation = 0.9f, brightness = 0.95f, alpha = glowAlpha * 0.70f),
                    fontSize = 36.sp
                )
                KawaiiHeartColon(
                    color = colonHaloColor.copy(alpha = 0.85f),
                    scale = heartbeatScale.value
                )
                KawaiiAnimatedDigitPair(
                    digits = timeParts.second,
                    color = cyber24BitColorShift(colorShiftPhase + 340f, saturation = 0.9f, brightness = 0.95f, alpha = glowAlpha * 0.70f),
                    fontSize = 36.sp
                )
            }

            // Layer 5: Intense 24-Bit Neon Under-Glow
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.offset(x = 0.6.dp, y = 0.6.dp)
            ) {
                KawaiiAnimatedDigitPair(
                    digits = timeParts.first,
                    color = hoursShiftColor.copy(alpha = 0.95f),
                    fontSize = 36.sp
                )
                KawaiiHeartColon(
                    color = colonColor,
                    scale = heartbeatScale.value
                )
                KawaiiAnimatedDigitPair(
                    digits = timeParts.second,
                    color = minutesShiftColor.copy(alpha = 0.95f),
                    fontSize = 36.sp
                )
            }

            // Layer 6: Brilliant 24-Bit Holographic Luminous Core
            Row(verticalAlignment = Alignment.CenterVertically) {
                KawaiiAnimatedDigitPair(
                    digits = timeParts.first,
                    color = hoursCoreColor,
                    fontSize = 36.sp
                )
                KawaiiHeartColon(
                    color = colonColor,
                    scale = heartbeatScale.value
                )
                KawaiiAnimatedDigitPair(
                    digits = timeParts.second,
                    color = minutesCoreColor,
                    fontSize = 36.sp
                )
            }
        }

        Spacer(Modifier.height(2.dp))

        // Date Subheader
        Box(contentAlignment = Alignment.CenterStart) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val dateParts = date.split("/")
                if (dateParts.size == 3) {
                    Text(
                        text = dateParts[0],
                        color = Mascot.color(0xFF80FFFF),
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont,
                        letterSpacing = 0.6.sp
                    )
                    Text(
                        text = " / ",
                        color = Mascot.color(0xFFFF69B4),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont
                    )
                    Text(
                        text = dateParts[1],
                        color = Mascot.color(0xFF70FFF0),
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont,
                        letterSpacing = 0.6.sp
                    )
                    Text(
                        text = " / ",
                        color = Mascot.color(0xFFFF69B4),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont
                    )
                    Text(
                        text = dateParts[2],
                        color = Color(0xFFD1B3FF),
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont,
                        letterSpacing = 0.6.sp
                    )
                } else {
                    Text(
                        text = date,
                        color = HioneCyan,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont
                    )
                }

                Spacer(Modifier.width(8.dp))

                // 6-Channel Mini Audio Spectrum Phosphor Bars
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(1.5.dp),
                    modifier = Modifier.height(11.dp)
                ) {
                    Box(Modifier.width(2.dp).height((11 * bar1).dp).clip(RoundedCornerShape(0.5.dp)).background(Brush.verticalGradient(listOf(HioneCyan, Mascot.color(0xFF00FFCC)))))
                    Box(Modifier.width(2.dp).height((11 * bar2).dp).clip(RoundedCornerShape(0.5.dp)).background(Brush.verticalGradient(listOf(HioneNeonPink, Mascot.color(0xFF6FB0FF)))))
                    Box(Modifier.width(2.dp).height((11 * bar3).dp).clip(RoundedCornerShape(0.5.dp)).background(Brush.verticalGradient(listOf(Color(0xFF00FF7F), Color(0xFF76FF03)))))
                    Box(Modifier.width(2.dp).height((11 * bar4).dp).clip(RoundedCornerShape(0.5.dp)).background(Brush.verticalGradient(listOf(HioneCyan, Color(0xFF00B0FF)))))
                    Box(Modifier.width(2.dp).height((11 * bar5).dp).clip(RoundedCornerShape(0.5.dp)).background(Brush.verticalGradient(listOf(HioneNeonPink, Mascot.color(0xFFFF007F)))))
                    Box(Modifier.width(2.dp).height((11 * bar6).dp).clip(RoundedCornerShape(0.5.dp)).background(Brush.verticalGradient(listOf(Mascot.color(0xFF00FFCC), HioneCyan))))
                }
            }
        }
    }
}

