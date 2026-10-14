package com.hione.tools.ui

import com.hione.tools.Mascot
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Static backdrop: obsidian plus two soft color pools. Drawn with drawBehind so it is a single
 *  cheap draw call with no layers and never recomposes. */
@Composable
fun HioneBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(Hione.Bg)
                drawCircle(
                    Brush.radialGradient(
                        listOf(Hione.Teal.copy(alpha = 0.16f), Color.Transparent),
                        center = Offset(size.width * 0.1f, size.height * 0.05f),
                        radius = size.width * 0.95f
                    ),
                    radius = size.width * 0.95f,
                    center = Offset(size.width * 0.1f, size.height * 0.05f)
                )
                drawCircle(
                    Brush.radialGradient(
                        listOf(Hione.Pink.copy(alpha = 0.10f), Color.Transparent),
                        center = Offset(size.width * 0.95f, size.height * 0.92f),
                        radius = size.width * 0.9f
                    ),
                    radius = size.width * 0.9f,
                    center = Offset(size.width * 0.95f, size.height * 0.92f)
                )
            },
        content = content
    )
}

/** The "liquid glass" panel: a dark tinted base, a white sheen that fades downward, a rim
 *  that shifts from the accent to pink, and a one-pixel highlight along the top edge. */
fun Modifier.glass(
    shape: Shape = RoundedCornerShape(20.dp),
    accent: Color = Hione.Teal,
    tint: Color = Mascot.color(0x990B1519),
    rimAlpha: Float = 0.45f,
): Modifier = this
    .clip(shape)
    .background(tint)
    .background(Brush.verticalGradient(listOf(Hione.GlassTop, Hione.GlassBottom)))
    .border(
        1.dp,
        Brush.linearGradient(
            listOf(accent.copy(alpha = rimAlpha), Color.White.copy(alpha = 0.07f), Hione.Pink.copy(alpha = rimAlpha * 0.6f))
        ),
        shape
    )
    .drawBehind {
        val inset = size.width * 0.12f
        drawLine(
            Brush.horizontalGradient(
                listOf(Color.Transparent, Color.White.copy(alpha = 0.22f), Color.Transparent),
                startX = inset, endX = size.width - inset
            ),
            start = Offset(inset, 1.5f), end = Offset(size.width - inset, 1.5f), strokeWidth = 1.2f
        )
    }

@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    accent: Color = Hione.Teal,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier.glass(shape, accent).padding(padding), content = content)
}

/** Press feedback that costs one float animation: shrink a little while held. */
@Composable
fun Modifier.pressable(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.95f else 1f, spring(stiffness = 900f), label = "press")
    return this
        .scale(s)
        .combinedClickable(
            interactionSource = source,
            indication = null,
            enabled = enabled,
            onLongClick = onLongClick,
            onClick = onClick
        )
}

/** Glass button. `filled` swaps the frosted fill for a solid accent gradient for the one
 *  primary action on a screen. Minimum height 52dp: this is used with thumbs on a 4.7" panel. */
@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Hione.Teal,
    filled: Boolean = false,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(18.dp),
    minHeight: Dp = 52.dp,
    onLongClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit
) {
    val base = if (filled) {
        Modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.95f), accent.copy(alpha = 0.7f))))
            .border(1.dp, Color.White.copy(alpha = 0.25f), shape)
    } else Modifier.glass(shape, accent)
    Row(
        modifier
            .heightIn(min = minHeight)
            .pressable(onClick, onLongClick, enabled)
            .then(base)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

@Composable
fun GlassTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    accent: Color = Hione.Teal,
    filled: Boolean = false,
    enabled: Boolean = true,
) {
    GlassButton(onClick, modifier, accent, filled, enabled) {
        val fg = if (filled) Mascot.color(0xFF041513) else if (enabled) Hione.Text else Hione.Faint
        if (icon != null) {
            Icon(icon, null, tint = if (filled) fg else accent, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = fg, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1)
    }
}

/** Round icon button with a 48dp target. */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Hione.TealGlow,
    size: Dp = 48.dp,
    plain: Boolean = false,
) {
    Box(
        modifier
            .size(size)
            .pressable(onClick)
            .then(if (plain) Modifier.clip(CircleShape) else Modifier.glass(CircleShape)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
fun HioneTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            GlassIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
            Spacer(Modifier.width(12.dp))
        } else Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = androidx.compose.material3.MaterialTheme.typography.titleLarge, color = Hione.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, color = Hione.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

data class TabItem(val label: String, val icon: ImageVector)

/** Bottom tab bar with a sliding glow pill under the selected tab. 64dp tall for thumbs. */
@Composable
fun GlassTabBar(items: List<TabItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .height(64.dp)
            .glass(RoundedCornerShape(24.dp))
    ) {
        val w = maxWidth / items.size
        val x by animateDpAsState(w * selected, tween(220), label = "tab")
        Box(
            Modifier
                .offset(x = x)
                .width(w)
                .fillMaxHeight()
                .padding(5.dp)
                .clip(RoundedCornerShape(19.dp))
                .background(Brush.verticalGradient(listOf(Hione.Teal.copy(alpha = 0.32f), Hione.Teal.copy(alpha = 0.12f))))
                .border(1.dp, Hione.TealGlow.copy(alpha = 0.45f), RoundedCornerShape(19.dp))
        )
        Row(Modifier.fillMaxSize()) {
            items.forEachIndexed { i, item ->
                val sel = i == selected
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(i) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(item.icon, null, tint = if (sel) Hione.TealGlow else Hione.Muted, modifier = Modifier.size(24.dp))
                    Text(item.label, color = if (sel) Hione.Text else Hione.Muted, fontSize = 12.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                }
            }
        }
    }
}

/** Compact segmented control (view switchers, deg/rad). */
@Composable
fun GlassSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, height: Dp = 44.dp) {
    BoxWithConstraints(modifier.height(height).glass(RoundedCornerShape(16.dp))) {
        val w = maxWidth / options.size
        val x by animateDpAsState(w * selected, tween(200), label = "seg")
        Box(
            Modifier.offset(x = x).width(w).fillMaxHeight().padding(4.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Hione.Teal.copy(alpha = 0.28f))
                .border(1.dp, Hione.TealGlow.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
        )
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, label ->
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(i) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, color = if (i == selected) Hione.Text else Hione.Muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun HioneSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = Hione.Teal,
            checkedBorderColor = Hione.TealGlow,
            uncheckedThumbColor = Hione.Muted,
            uncheckedTrackColor = Hione.Surface2,
            uncheckedBorderColor = Hione.Faint,
        )
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = Hione.Teal) {
    Text(
        text.uppercase(),
        modifier = modifier.padding(start = 4.dp, top = 14.dp, bottom = 6.dp),
        color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp
    )
}

/** A full-width settings-style row: label (and optional value) left, optional trailing slot.
 *  56dp minimum so every row is a comfortable tap target. */
@Composable
fun GlassRow(
    title: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, tint = Hione.Teal, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = Hione.Text, fontSize = 16.sp)
            if (value != null) Text(value, color = Hione.Muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/** Dialog on a glass card. Buttons are laid out by the caller through [buttons]. */
@Composable
fun GlassDialog(
    onDismiss: () -> Unit,
    title: String? = null,
    scrollable: Boolean = true,
    buttons: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .padding(horizontal = 16.dp, vertical = 24.dp)
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .glass(RoundedCornerShape(26.dp), tint = Mascot.color(0xF00A1216))
                .padding(18.dp)
        ) {
            if (title != null) {
                Text(title, color = Hione.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
            }
            Column(
                Modifier.weight(1f, fill = false).let { if (scrollable) it.verticalScroll(rememberScrollState()) else it },
                content = content
            )
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), content = buttons)
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(84.dp).glass(CircleShape),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, tint = Hione.TealGlow, modifier = Modifier.size(40.dp)) }
        Spacer(Modifier.height(16.dp))
        Text(title, color = Hione.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        if (body != null) {
            Spacer(Modifier.height(6.dp))
            Text(body, color = Hione.Muted, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}
