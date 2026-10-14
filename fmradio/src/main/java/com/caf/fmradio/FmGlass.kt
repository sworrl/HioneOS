package com.caf.fmradio

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A small frosted-glass look for panels and buttons, kept local to this app.
 *
 * Real background blur is not affordable here: RenderEffect blur on a full-screen panel costs a
 * whole extra pass per frame on this GPU. What sells "glass" on a dark UI is cheaper than blur
 * anyway: a translucent fill so the artwork shows through, a specular band along the top edge
 * where light would catch a curved surface, and a rim that picks up colour from its
 * surroundings (teal into pink here).
 *
 * A Modifier.Node rather than drawWithCache, on purpose. drawWithCache takes a lambda, a lambda
 * is a new object every recomposition, and a new modifier element means "invalidate the draw".
 * So every glass panel in a composable that recomposed (the dial card does, on every signal
 * poll) forced a window frame even when nothing it draws had changed, and a window frame here
 * is the expensive thing. This element compares by value: same colours, same shape, no
 * invalidation. Brushes and the outline are rebuilt only when the size or a parameter changes.
 */
fun Modifier.glass(
    shape: Shape,
    accent: Color = HioneTeal,
    accent2: Color = HionePink,
    fill: Color = Color(0xB80A1E26),
    rimAlpha: Float = 0.55f,
    rimWidth: Dp = 1.dp,
    /** 0..1: how strongly the top highlight shows. Selected/active surfaces get more. */
    shine: Float = 1f,
): Modifier = this
    .clip(shape)
    .then(GlassElement(shape, accent, accent2, fill, rimAlpha, rimWidth, shine))

private data class GlassElement(
    val shape: Shape,
    val accent: Color,
    val accent2: Color,
    val fill: Color,
    val rimAlpha: Float,
    val rimWidth: Dp,
    val shine: Float,
) : ModifierNodeElement<GlassNode>() {
    override fun create() = GlassNode(this)
    override fun update(node: GlassNode) = node.set(this)
}

private class GlassNode(private var spec: GlassElement) : Modifier.Node(), DrawModifierNode {
    private var cachedFor: Size = Size.Unspecified
    private var cachedDir: LayoutDirection? = null
    private var outline: Outline? = null
    private var specular: Brush? = null
    private var pool: Brush? = null
    private var rim: Brush? = null
    private var stroke: Stroke? = null

    fun set(s: GlassElement) {
        if (s == spec) return
        spec = s
        cachedFor = Size.Unspecified
        invalidateDraw()
    }

    override fun ContentDrawScope.draw() {
        val s = spec
        if (size != cachedFor || layoutDirection != cachedDir) {
            cachedFor = size
            cachedDir = layoutDirection
            outline = s.shape.createOutline(size, layoutDirection, this)
            specular = Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.13f * s.shine),
                0.42f to Color.White.copy(alpha = 0.02f * s.shine),
                1f to Color.Transparent,
                endY = size.height,
            )
            // A faint coloured glow pooled at the bottom, as light through tinted glass does.
            pool = Brush.verticalGradient(
                0f to Color.Transparent, 1f to s.accent.copy(alpha = 0.10f * s.shine),
                startY = size.height * 0.45f, endY = size.height,
            )
            rim = Brush.linearGradient(
                listOf(s.accent.copy(alpha = s.rimAlpha), s.accent2.copy(alpha = s.rimAlpha * 0.55f),
                       s.accent.copy(alpha = s.rimAlpha * 0.25f)),
                start = Offset.Zero, end = Offset(size.width, size.height),
            )
            // The outline is drawn inside the clip, so half its width is cut away: double it.
            stroke = Stroke(width = s.rimWidth.toPx() * 2f)
        }
        val o = outline!!
        if (s.fill.alpha > 0f) drawOutline(o, s.fill)
        drawOutline(o, specular!!)
        drawOutline(o, pool!!)
        drawContent()
        drawOutline(o, rim!!, style = stroke!!)
    }
}

/**
 * Tap / long-press with a soft squash instead of a ripple.
 *
 * A ripple on a dark translucent surface reads as a grey smear; a small spring scale reads as a
 * physical button under a thumb, which is how this device is used. The scale is applied in a
 * graphics layer, so pressing only re-renders the layer, never recomposes the content. Long
 * press gets a haptic tick so the user knows the second action fired without looking.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.pressable(
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    pressedScale: Float = 0.94f,
    onClick: () -> Unit,
): Modifier {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) pressedScale else 1f,
        spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
        label = "press",
    )
    val haptics = LocalHapticFeedback.current
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .combinedClickable(
            interactionSource = src,
            indication = null,
            enabled = enabled,
            onLongClick = onLongClick?.let { lc -> { haptics.performHapticFeedback(HapticFeedbackType.LongPress); lc() } },
            onClick = onClick,
        )
}
