package com.hione.media.ui

import com.hione.media.Mascot
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.media.R

// Same palette as hioneos-settings (canonical #FFAB2E teal, obsidian surfaces, diva pink), so the
// three apps sit next to Settings and the launcher without looking borrowed.
val HioneTeal get() = Mascot.color(0xFFFFAB2E)
val HioneTealBright get() = Mascot.color(0xFFFFC56B)
val HioneTealGlow get() = Mascot.color(0xFF56F0E0)
val HionePink get() = Mascot.color(0xFF8EC5FF)
val HionePinkSoft get() = Mascot.color(0xFF6FB0FF)
val HioneGold = Color(0xFFFFD54F)
val HioneBg get() = Mascot.color(0xFF0D0E11)
val HioneSurface1 get() = Mascot.color(0xFF0D1417)
val HioneSurface2 get() = Mascot.color(0xFF131E22)
val HioneMuted = Color(0xFF7E9AA0)
val HioneWhite get() = Mascot.color(0xFFF0FDFE)
val HioneDanger = Color(0xFFFF5370)

val Orbitron = FontFamily(Font(R.font.orbitron, FontWeight.Normal), Font(R.font.orbitron, FontWeight.Bold))

/** Minimum touch target. The M500 panel is 360x640dp; 48dp keeps controls thumb-sized. */
val TouchTarget = 48.dp

private val HioneColors: ColorScheme get() = darkColorScheme(
    primary = HioneTeal,
    onPrimary = Mascot.color(0xFF00201D),
    secondary = HionePink,
    onSecondary = Color.White,
    background = HioneBg,
    onBackground = HioneWhite,
    surface = HioneSurface1,
    onSurface = HioneWhite,
    surfaceVariant = HioneSurface2,
    onSurfaceVariant = HioneMuted,
    error = HioneDanger,
    outline = HioneTeal.copy(alpha = 0.4f)
)

@Composable
fun HioneTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = HioneColors, content = content)
}

val TitleStyle = TextStyle(fontFamily = Orbitron, fontWeight = FontWeight.Bold, fontSize = 18.sp, letterSpacing = 1.5.sp, color = HioneWhite)
val LabelStyle = TextStyle(fontSize = 13.sp, color = HioneMuted)
val BodyStyle = TextStyle(fontSize = 15.sp, color = HioneWhite)

/**
 * The "liquid glass" material, drawn rather than blurred. A real backdrop blur (RenderEffect)
 * re-renders whatever is underneath every frame; on this Snapdragon 665 the player app measured
 * that at ~40% of the frame budget, so here glass is a translucent tinted fill, a soft shadow
 * and a top-lit specular rim. Over photos and the camera preview that reads as frosted glass at
 * the cost of three rounded rects.
 */
fun Modifier.glass(
    corner: Dp = 18.dp,
    tint: Color = HioneTeal,
    fillAlpha: Float = 0.55f
): Modifier = this
    .clip(RoundedCornerShape(corner))
    .drawBehind {
        val rr = CornerRadius(corner.toPx(), corner.toPx())
        drawRoundRect(Mascot.color(0xFF061012).copy(alpha = fillAlpha), cornerRadius = rr)
        drawRoundRect(
            Brush.verticalGradient(listOf(tint.copy(alpha = 0.16f), Color.Transparent)),
            cornerRadius = rr
        )
        drawRoundRect(
            Brush.verticalGradient(
                listOf(Color.White.copy(alpha = 0.30f), tint.copy(alpha = 0.18f), Color.Transparent),
                endY = size.height
            ),
            cornerRadius = rr,
            style = Stroke(1.dp.toPx())
        )
    }

/** Round glass disc, for floating buttons over photos and the viewfinder. */
fun Modifier.glassCircle(tint: Color = HioneTeal, fillAlpha: Float = 0.5f): Modifier = this
    .clip(CircleShape)
    .drawBehind {
        val r = size.minDimension / 2f
        drawCircle(Mascot.color(0xFF061012).copy(alpha = fillAlpha), r)
        drawCircle(Brush.verticalGradient(listOf(tint.copy(alpha = 0.18f), Color.Transparent)), r)
        drawCircle(
            Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.35f), Color.Transparent)),
            r - 0.5.dp.toPx(),
            style = Stroke(1.dp.toPx())
        )
    }

/**
 * Press feedback: a short scale dip plus a haptic tick. Scale is a graphicsLayer property, so the
 * press animates on the render thread without recomposing or redrawing the content.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun Modifier.pressable(
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val haptic = LocalHapticFeedback.current
    val scale by animateFloatAsState(
        if (pressed) 0.92f else 1f,
        spring(dampingRatio = 0.6f, stiffness = 1400f),
        label = "press"
    )
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .combinedClickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onLongClick = onLongClick?.let { lc -> { haptic.performHapticFeedback(HapticFeedbackType.LongPress); lc() } },
            onClick = onClick
        )
}

@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = HioneWhite,
    accent: Color = HioneTeal,
    size: Dp = TouchTarget,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier
            .size(size)
            .pressable(enabled = enabled, onClick = onClick)
            .glassCircle(accent),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription, tint = if (enabled) tint else tint.copy(alpha = 0.35f), modifier = Modifier.size(size * 0.5f))
    }
}

/** Flat icon button with no disc, for dense toolbars. Still a full 48dp target. */
@Composable
fun BareIconButton(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = HioneWhite,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier.size(TouchTarget).clip(CircleShape).pressable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription, tint = if (enabled) tint else tint.copy(alpha = 0.35f), modifier = Modifier.size(24.dp))
    }
}

@Composable
fun GlassButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    accent: Color = HioneTeal,
    filled: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val base = modifier
        .height(TouchTarget)
        .pressable(enabled = enabled, onClick = onClick)
    val styled = if (filled) {
        base.clip(RoundedCornerShape(24.dp)).background(
            Brush.verticalGradient(listOf(accent, accent.copy(alpha = 0.75f)))
        )
    } else base.glass(24.dp, accent)
    Row(
        styled.padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        val fg = when {
            !enabled -> HioneMuted
            filled -> Mascot.color(0xFF00201D)
            else -> HioneWhite
        }
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Segmented chooser (Photo / Video, Timeline / Albums, M4A / WAV). */
@Composable
fun GlassSegments(
    options: List<String>,
    selected: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit
) {
    Row(modifier.glass(22.dp).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .height(40.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (on) HioneTeal.copy(alpha = 0.9f) else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    color = if (on) Mascot.color(0xFF00201D) else HioneWhite,
                    fontSize = 14.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
    }
}

/** Screen header: Orbitron title with an optional subtitle and trailing actions. */
@Composable
fun HioneHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    actions: @Composable () -> Unit = {}
) {
    Row(
        modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leading != null) leading() else Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(title.uppercase(), style = TitleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = LabelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
}

/** Full-screen message with an optional action, for empty states and missing permissions. */
@Composable
fun MessagePane(
    icon: ImageVector,
    title: String,
    body: String,
    action: String? = null,
    onAction: () -> Unit = {}
) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.glass(24.dp).padding(24.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, null, tint = HioneTeal, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(12.dp))
            Text(title, style = TitleStyle.copy(fontSize = 16.sp))
            Spacer(Modifier.height(8.dp))
            Text(body, style = BodyStyle.copy(color = HioneMuted, fontSize = 14.sp))
            if (action != null) {
                Spacer(Modifier.height(16.dp))
                GlassButton(action, filled = true, onClick = onAction)
            }
        }
    }
}

/** Page background: obsidian with a faint teal glow at the top, painted once per size change. */
fun Modifier.hioneBackground(): Modifier = this.drawBehind {
    drawRect(HioneBg)
    drawRect(
        Brush.radialGradient(
            listOf(HioneTeal.copy(alpha = 0.10f), Color.Transparent),
            center = Offset(size.width * 0.5f, 0f),
            radius = size.width * 1.1f
        )
    )
}

fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
    b >= 1L shl 10 -> "%.0f KB".format(b / (1L shl 10).toDouble())
    else -> "$b B"
}
