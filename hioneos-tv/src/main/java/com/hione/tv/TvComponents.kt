package com.hione.tv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

val HioneOrange = Color(0xFFFFAB2E)
val HioneTeal = Color(0xFF00FFC2)
val HionePink = Color(0xFF8EC5FF)
val HioneMuted = Color(0xFF8FA3A8)
val HioneGreen = Color(0xFF00FF88)
val HioneCardBg = Color(0x240A181C)
val HioneCardBorder = Color(0x3300FFC2)

/**
 * Focusable TV Button with distinct high-visibility focus ring and scale animation.
 * Ensures the TV remote D-Pad moves the control point across interactive elements naturally.
 */
@Composable
fun TvFocusableButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    icon: ImageVector? = null,
    primary: Boolean = false,
    height: Dp = 48.dp,
    shape: Shape = RoundedCornerShape(12.dp),
    fontSize: TextUnit = 14.sp,
    selected: Boolean = false
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.08f else 1.0f,
        label = "btnScale"
    )

    val containerColor = when {
        isFocused -> if (primary) Color(0xFFFFC066) else Color(0x44FFFFFF)
        selected -> HioneOrange
        primary -> HioneOrange
        else -> Color(0x22FFFFFF)
    }

    val contentColor = when {
        selected && !isFocused -> Color.Black
        primary && !isFocused -> Color.Black
        isFocused -> if (primary) Color.Black else Color.White
        else -> Color.White
    }

    val borderColor = when {
        isFocused -> HioneOrange
        selected -> HioneOrange.copy(alpha = 0.8f)
        else -> Color(0x22FFFFFF)
    }

    val borderWidth = when {
        isFocused -> 3.dp
        selected -> 2.dp
        else -> 1.dp
    }

    Button(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        border = BorderStroke(borderWidth, borderColor),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        modifier = modifier
            .height(height)
            .scale(scale)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    modifier = Modifier.size(18.dp),
                    tint = contentColor
                )
                if (!label.isNullOrBlank()) {
                    Spacer(Modifier.width(6.dp))
                }
            }
            if (!label.isNullOrBlank()) {
                Text(
                    text = label,
                    fontSize = fontSize,
                    fontWeight = if (isFocused || selected || primary) FontWeight.ExtraBold else FontWeight.Bold,
                    color = contentColor,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

/**
 * Top Navigation Tab Button specifically for switching view modes with D-Pad focus.
 */
@Composable
fun TvModeTab(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.08f else 1.0f,
        label = "tabScale"
    )

    val containerColor = when {
        isFocused -> if (selected) Color(0xFFFFC066) else Color(0x55FFFFFF)
        selected -> HioneOrange
        else -> Color(0x18FFFFFF)
    }

    val contentColor = when {
        selected -> Color.Black
        isFocused -> Color.White
        else -> HioneMuted
    }

    val borderColor = when {
        isFocused -> HioneOrange
        selected -> HioneOrange
        else -> Color(0x22FFFFFF)
    }

    Button(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        border = BorderStroke(if (isFocused) 2.5.dp else 1.dp, borderColor),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        modifier = modifier
            .height(40.dp)
            .scale(scale)
    ) {
        Text(
            text = title,
            fontSize = 12.5.sp,
            fontWeight = if (selected || isFocused) FontWeight.ExtraBold else FontWeight.SemiBold,
            color = contentColor,
            letterSpacing = 0.5.sp
        )
    }
}

@Composable
fun TvDataChip(
    text: String,
    color: Color = HioneTeal,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.15f))
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 11.sp,
            fontFamily = OrbitronFont,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp
        )
    }
}

/**
 * Authentic Hione Hits "Wavy play bar" ported to Android TV:
 * The played portion of the seek bar is a live sine ribbon whose amplitude reacts
 * in real-time to the PCM audio waveform, with drop shadow, glass dome knob,
 * and specular reflection at the playhead.
 */
@Composable
fun WavyScrubber(
    pos: Long,
    dur: Long,
    playing: Boolean,
    accent: Color,
    accent2: Color,
    rms: Float = 0.2f,
    modifier: Modifier = Modifier
) {
    val d = dur.coerceAtLeast(1L)
    val prog = if (dur > 0L) (pos.toFloat() / d.toFloat()).coerceIn(0f, 1f) else 0f

    var phase by remember { mutableFloatStateOf(0f) }
    var amp by remember { mutableFloatStateOf(0.2f) }
    var frame by remember { mutableIntStateOf(0) }

    LaunchedEffect(playing, rms) {
        while (true) {
            if (playing) {
                val target = (0.22f + rms * 2.6f).coerceIn(0.22f, 1f)
                amp += (target - amp) * 0.28f
                phase += 0.19f
                frame++
                delay(33)
            } else {
                amp += (0.16f - amp) * 0.15f
                phase += 0.05f
                frame++
                delay(66)
            }
        }
    }

    val active = if (playing) accent else lerp(accent, HioneMuted, 0.5f)
    val active2 = if (playing) accent2 else lerp(accent2, HioneMuted, 0.5f)
    val brighter = lerp(active, Color.White, 0.35f)
    val darker = lerp(active, Color.Black, 0.4f)
    val ribbonBrush = remember(active, active2) { Brush.horizontalGradient(listOf(active, active2)) }
    val path = remember { Path() }
    val density = LocalDensity.current
    val glowStroke = remember(density) { with(density) { Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round) } }
    val ribbonStroke = remember(density) { with(density) { Stroke(width = 4.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round) } }
    val hiliteStroke = remember(density) { with(density) { Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round) } }
    val rimStroke = remember { Stroke(1.2f) }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
    ) {
        val tick = frame
        if (tick < 0) return@Canvas
        val w = size.width
        val cy = size.height / 2f
        val kr = 10.dp.toPx()
        val fillW = (w * prog).coerceIn(0f, w)

        // Recessed groove for the whole track length
        val gh = 5.dp.toPx()
        drawRoundRect(
            Color(0xFF06201F),
            topLeft = Offset(0f, cy - gh / 2f),
            size = Size(w, gh),
            cornerRadius = CornerRadius(gh / 2f, gh / 2f)
        )
        drawRoundRect(
            Brush.verticalGradient(listOf(Color(0x66000000), Color(0x00000000)), startY = cy - gh / 2f, endY = cy + gh / 4f),
            topLeft = Offset(0f, cy - gh / 2f),
            size = Size(w, gh),
            cornerRadius = CornerRadius(gh / 2f, gh / 2f)
        )
        drawLine(Color(0x1CFFFFFF), Offset(gh, cy + gh / 2f - 0.8f), Offset(w - gh, cy + gh / 2f - 0.8f), 1.2f, cap = StrokeCap.Round)

        // The ribbon: live sine wave over the played portion
        if (fillW > 2f) {
            val ampPx = amp * 8.5.dp.toPx()
            val wavelength = 32.dp.toPx()
            val step = 3f
            path.reset()
            var x = 0f
            var first = true
            while (x <= fillW) {
                val envIn = (x / wavelength).coerceIn(0f, 1f)
                val envOut = ((fillW - x) / (wavelength * 0.8f)).coerceIn(0f, 1f)
                val y = cy + sin((x / wavelength) * 2f * PI.toFloat() - phase) * ampPx * envIn * envOut
                if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
                x += step
            }
            if (x - step < fillW) path.lineTo(fillW, cy)
            drawPath(path, active.copy(alpha = 0.28f), style = glowStroke)
            drawPath(path, ribbonBrush, style = ribbonStroke)
            drawPath(path, brighter.copy(alpha = 0.55f), style = hiliteStroke)
        }

        // Glass pearl knob at playhead: drop shadow, gradient dome, rim light, specular dot
        val tx = fillW.coerceIn(kr, w - kr)
        drawCircle(Color(0x66000000), kr + 2.5f, Offset(tx, cy + 2f))
        drawCircle(Brush.verticalGradient(listOf(brighter, active, darker), startY = cy - kr, endY = cy + kr), kr, Offset(tx, cy))
        drawCircle(Color.White.copy(alpha = 0.6f), kr, Offset(tx, cy), style = rimStroke)
        drawCircle(Color.White.copy(alpha = 0.65f), kr * 0.30f, Offset(tx - kr * 0.32f, cy - kr * 0.32f))
    }
}

