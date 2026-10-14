package com.hione.systemui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.isActive
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Quick-settings tiles as glass buttons. Three states, readable at a glance on a 4" screen:
 *   on           accent-filled lit glass, dark ink, neon halo
 *   off          frosted dark glass, light ink, refraction rim only
 *   unavailable  dimmed glass, muted ink, no halo; a tap still tries (the codec may answer now)
 * Press: sinks and squashes, shadow tightens, frost blooms from the finger; release rebounds.
 * The active state animates on the settle spring, label/subtitle changes slide in.
 */

private val GridTileShape = RoundedCornerShape(22.dp)
private val CompactTileShape = RoundedCornerShape(24.dp)

private fun stateText(t: QsTile) = when {
    !t.isAvailable -> "unavailable"
    t.isActive -> "on"
    else -> "off"
}

/** Full-grid tile: icon well, label, live secondary text. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GlassGridTile(
    t: QsTile,
    modifier: Modifier,
    accentBright: Color = HioneTealBright,
    onOpensUi: () -> Unit = {}
) {
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val press = rememberHionePress(interaction)
    val on = t.isActive && t.isAvailable
    val activeAnim = animateFloatAsState(if (on) 1f else 0f, HioneMotion.settle(), label = "tileActive")
    // A tile with its own accent (the DAC tiles, from dacTheme) fades between palettes.
    val acc by animateColorAsState(if (t.accent != 0) Color(t.accent) else accentBright, HioneMotion.ease(), label = "tileAccent")
    val ink by animateColorAsState(if (on) HioneDarkBg else HioneWhite, HioneMotion.ease(), label = "tileInk")
    val subInk by animateColorAsState(
        when { on -> HioneDarkBg.copy(alpha = 0.78f); !t.isAvailable -> HioneMuted; else -> HioneTextSecondary },
        HioneMotion.ease(), label = "tileSubInk"
    )
    val iconInk by animateColorAsState(
        when { on -> HioneDarkBg; !t.isAvailable -> HioneMuted; else -> acc },
        HioneMotion.ease(), label = "tileIconInk"
    )
    Row(
        modifier
            .height(64.dp)
            .hionePressScale(press)
            .hioneGlass(GridTileShape, HioneGlass.Tile, accent = acc, active = { activeAnim.value }, pressed = press, dimmed = !t.isAvailable)
            .semantics { stateDescription = stateText(t) }
            .combinedClickable(
                interactionSource = interaction, indication = null, role = Role.Switch,
                onClick = { HioneHaptics.confirm(view); tap(t, onOpensUi) },
                onLongClick = t.onLongClick?.let { lc -> { HioneHaptics.pop(view); lc(); if (t.longPressOpensUi) onOpensUi() } }
            )
            .padding(start = 10.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val pulse = rememberBeatPulse(t.beatMs)
        IconWell(t.icon, t.label, iconInk, on, 34.dp, pulse = { pulse.value })
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(t.label, color = ink, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            AnimatedContent(
                targetState = t.subtitle,
                transitionSpec = {
                    (slideInVertically(HioneMotion.ease()) { it / 2 } + fadeIn(HioneMotion.ease()))
                        .togetherWith(slideOutVertically(HioneMotion.ease()) { -it / 2 } + fadeOut(HioneMotion.ease(120)))
                },
                label = "tileSubtitle"
            ) { sub ->
                Text(sub, color = subInk, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Quick-row tile in the collapsed shade: icon over a short caps label. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GlassCompactTile(
    t: QsTile,
    modifier: Modifier,
    accentBright: Color = HioneTealBright,
    onOpensUi: () -> Unit = {}
) {
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val press = rememberHionePress(interaction)
    val on = t.isActive && t.isAvailable
    val activeAnim = animateFloatAsState(if (on) 1f else 0f, HioneMotion.settle(), label = "compactActive")
    val acc by animateColorAsState(if (t.accent != 0) Color(t.accent) else accentBright, HioneMotion.ease(), label = "compactAccent")
    val ink by animateColorAsState(
        when { on -> HioneDarkBg; !t.isAvailable -> HioneMuted; else -> HioneTextSecondary },
        HioneMotion.ease(), label = "compactInk"
    )
    val iconInk by animateColorAsState(
        when { on -> HioneDarkBg; !t.isAvailable -> HioneMuted; else -> acc },
        HioneMotion.ease(), label = "compactIconInk"
    )
    Column(
        modifier
            .height(56.dp)
            .hionePressScale(press)
            .hioneGlass(CompactTileShape, HioneGlass.Tile, accent = acc, active = { activeAnim.value }, pressed = press, dimmed = !t.isAvailable)
            .semantics { stateDescription = stateText(t) }
            .combinedClickable(
                interactionSource = interaction, indication = null, role = Role.Switch,
                onClickLabel = t.label,
                onClick = { HioneHaptics.confirm(view); tap(t, onOpensUi) },
                onLongClick = t.onLongClick?.let { lc -> { HioneHaptics.pop(view); lc(); if (t.longPressOpensUi) onOpensUi() } }
            ),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        val pulse = rememberBeatPulse(t.beatMs)
        Icon(t.icon, t.label, tint = iconInk, modifier = Modifier.size(20.dp).beatScale { pulse.value })
        Spacer(Modifier.height(3.dp))
        val short = QsTileOrder.spec(t.id)?.short ?: t.label.uppercase().take(10)
        Text(
            short, color = ink, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, letterSpacing = 0.5.sp, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 3.dp)
        )
    }
}

/** Tap: a tile that opens another app's screen collapses the shade first, then acts. */
private fun tap(t: QsTile, onOpensUi: () -> Unit) {
    if (t.clickOpensUi) { onOpensUi(); t.onClick() } else t.onClick()
}

/**
 * Icon pulse at [beatMs] (0 = none): a quick 70ms swell, then an ease back over the rest of the
 * beat. Runs only while the shade is actually showing (lifecycle RESUMED: the shade window drops
 * to STARTED when hidden, and its composition stays alive, so without this the loop would keep
 * the frame clock busy behind a closed shade) and never in the quiet power profiles. The value is
 * read in graphicsLayer, so a beat redraws the icon and recomposes nothing.
 */
@Composable
private fun rememberBeatPulse(beatMs: Int): Animatable<Float, AnimationVector1D> {
    val pulse = remember { Animatable(0f) }
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val running = beatMs > 0 && lifecycleState.isAtLeast(Lifecycle.State.RESUMED) && !HioneMotion.quiet
    LaunchedEffect(running, beatMs) {
        if (!running) { pulse.snapTo(0f); return@LaunchedEffect }
        while (isActive) {
            pulse.animateTo(1f, tween(70))
            pulse.animateTo(0f, tween((beatMs - 70).coerceAtLeast(60), easing = FastOutSlowInEasing))
        }
    }
    return pulse
}

private fun Modifier.beatScale(pulse: () -> Float): Modifier = graphicsLayer {
    val s = 1f + 0.22f * pulse()
    scaleX = s; scaleY = s
}

/** Round well the tile icon sits in: a darker dimple on lit glass, a faint teal one on dark glass. */
@Composable
private fun IconWell(icon: ImageVector, desc: String, tint: Color, on: Boolean, size: Dp, pulse: () -> Float = { 0f }) {
    Box(
        Modifier
            .size(size)
            .hioneGlass(
                CircleShape,
                if (on) HioneGlass.Groove.copy(bodyTop = HioneDarkBg.copy(alpha = 0.30f), bodyBottom = HioneDarkBg.copy(alpha = 0.12f), opacity = 1f, rim = 0.2f)
                else HioneGlass.Groove.copy(opacity = 0.75f, rim = 0.5f)
            ),
        contentAlignment = Alignment.Center
    ) { Icon(icon, desc, tint = tint, modifier = Modifier.size(18.dp).beatScale(pulse)) }
}

/** Round glass icon button (header chevron, footer actions). 40dp target minimum. */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    accent: Color = tint,
    onClick: () -> Unit
) {
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val press = rememberHionePress(interaction)
    Box(
        modifier
            .size(size)
            .hionePressScale(press, depth = 0.10f)
            .hioneGlass(CircleShape, HioneGlass.Chip, accent = accent, pressed = press)
            .clickable(
                interactionSource = interaction, indication = null, role = Role.Button,
                onClickLabel = contentDescription,
                onClick = { HioneHaptics.confirm(view); onClick() }
            ),
        contentAlignment = Alignment.Center
    ) { Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(size * 0.5f)) }
}

/** Pill-shaped glass text button. [filled] = accent-lit (primary action). */
@Composable
fun GlassChipButton(
    text: String,
    modifier: Modifier = Modifier,
    accent: Color = HioneTealBright,
    filled: Boolean = false,
    textColor: Color = if (filled) HioneDarkBg else accent,
    height: Dp = 36.dp,
    onClick: () -> Unit
) {
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val press = rememberHionePress(interaction)
    val lit = animateFloatAsState(if (filled) 1f else 0f, HioneMotion.settle(), label = "chipLit")
    Box(
        modifier
            .height(height)
            .hionePressScale(press, depth = 0.07f)
            .hioneGlass(RoundedCornerShape(height / 2), HioneGlass.Chip, accent = accent, active = { lit.value }, pressed = press)
            .clickable(
                interactionSource = interaction, indication = null, role = Role.Button,
                onClick = { HioneHaptics.confirm(view); onClick() }
            )
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = textColor, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, maxLines = 1)
    }
}
