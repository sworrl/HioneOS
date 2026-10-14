package com.hione.systemui

import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/*
 * HioneGlass: the HioneOS "liquid glass" toolkit. One file on purpose: another HioneOS module can
 * copy it whole (it only needs the Hione color vals from Theme.kt, HioneMotion and HionePowerProfile,
 * or local stand-ins for them).
 *
 * WHAT IT IS. The Hione look (dark teal glass, teal/cyan/pink neon, cyber rims) with depth added:
 *   body       vertical gradient of the glass tint, a little clearer at the top than the bottom
 *   specular   a soft sheen over the top half plus a bright hairline along the top edge only
 *   rim        a "refraction" stroke: teal bright at the top-left, almost gone mid-way, pink at
 *              the bottom-right, the way light bends through the edge of a real pane
 *   caustic    one faint pink bloom inside the bottom-right corner
 *   shadow     a soft contact shadow drawn OUTSIDE the shape, mostly below it
 *   glow       a neon halo outside the shape that fades in with the active state
 *   press      the surface sinks (scale + squash), the shadow tightens and a frost bloom spreads
 *              from the touch point; release springs back past rest (the liquid rebound)
 *
 * WHY NO PER-TILE BLUR. Compose's blur / RenderEffect blurs a layer's OWN pixels, not what is
 * behind it, and an offscreen blur per tile every frame is exactly what this GPU cannot afford.
 * Real backdrop blur happens once, at the window: HioneShadeWindow asks SurfaceFlinger for
 * blur-behind (API 31+, only when the device reports cross-window blur). [backdropBlur] tells
 * surfaces whether that is live so they can pick a clearer tint ([HioneGlassStyle.opacity] is the
 * solid fallback, [HioneGlass.panelStyle] picks per mode). Everything else here is gradients and
 * strokes, cached per size in drawWithCache and redrawn only when an animated input changes.
 *
 * API
 *   Modifier.hioneGlass(shape, style, accent, accent2, active = { 0..1 }, pressed = press, dimmed)
 *       The whole look. `active` (a lambda) and `pressed` (a HionePressState) are read in the DRAW
 *       phase, so an animating value never recomposes the caller.
 *   rememberHionePress(interactionSource) / Modifier.hionePressScale(press)
 *       Press physics for anything clickable: share the interactionSource with the clickable.
 *   HioneGlassStyle presets: HioneGlass.Panel, Card, Tile, Chip, Groove (recessed slider track).
 *   HioneGlassSlider(...)
 *       Thick glass slider: recessed groove, accent-filled glass fill carrying the icon, a liquid
 *       bead thumb that stretches with drag speed and wobbles back.
 *   HioneGlass.CanvasGlass
 *       The same sheen + edge + rim for android.graphics Views (track HUD, volume HUD).
 *
 * CONTRAST. Text goes on the glass, never on the bare blur: panels keep >= 0.8 opacity over a
 * >= 0.4 black scrim even with blur on, which holds HioneWhite / HioneTextSecondary above roughly
 * 4.5:1 against a white app behind. Accent-filled (active) glass uses HioneDarkBg text.
 */

@Immutable
data class HioneGlassStyle(
    val bodyTop: Color,
    val bodyBottom: Color,
    /** Body opacity at rest. */
    val opacity: Float,
    val rimWidth: Dp = 1.dp,
    /** 0..1 strength of the refraction rim. */
    val rim: Float = 1f,
    /** 0..1 strength of the top sheen and top-edge hairline. */
    val specular: Float = 1f,
    /** Extent of the soft contact shadow below the shape; 0 = none. */
    val shadow: Dp = 0.dp,
    /** Extent of the neon halo when active; 0 = none. */
    val glow: Dp = 0.dp,
    /** A groove instead of a raised pane: shade at the top, light along the bottom edge. */
    val recessed: Boolean = false
)

object HioneGlass {
    /** Glass tint: Hione's dark teal, lighter at the top. */
    val TintTop get() = Mascot.color(0xFF123540)
    val TintBottom get() = Mascot.color(0xFF04131A)
    /** Specular light. Teal-white rather than pure white, so highlights stay in the Hione family. */
    val Frost get() = Mascot.color(0xFFD9FFFA)
    val ShadowInk get() = Mascot.color(0xFF000609)

    val Panel get() = HioneGlassStyle(Mascot.color(0xFF0C2731), Mascot.color(0xFF030C11), opacity = 0.97f, rimWidth = 1.2.dp, rim = 0.9f, specular = 0.6f)
    val Card = HioneGlassStyle(TintTop, TintBottom, opacity = 0.90f, rim = 0.75f, specular = 0.8f, shadow = 6.dp)
    val Tile = HioneGlassStyle(TintTop, TintBottom, opacity = 0.90f, rimWidth = 1.1.dp, rim = 0.85f, specular = 1f, shadow = 6.dp, glow = 7.dp)
    val Chip = HioneGlassStyle(TintTop, TintBottom, opacity = 0.88f, rim = 0.7f, specular = 0.9f, shadow = 3.dp)
    val Groove get() = HioneGlassStyle(Mascot.color(0xFF020A0E), Mascot.color(0xFF0A2129), opacity = 0.92f, rim = 0.45f, specular = 0.7f, recessed = true)

    /** The shade panel: clearer when SurfaceFlinger is blurring what is behind it. */
    fun panelStyle(blur: Boolean): HioneGlassStyle = if (blur) Panel.copy(opacity = 0.82f) else Panel

    /** Black scrim behind the panel. Lighter with blur on (the blur already separates layers). */
    fun scrimAlpha(blur: Boolean): Float = if (blur) 0.42f else 0.55f

    /** Window blur-behind radius for the shade, in dp. Modest: SurfaceFlinger cost scales with it. */
    const val BACKDROP_BLUR_DP = 22

    private val _backdropBlur = MutableStateFlow(false)
    /** True while the shade window really has SurfaceFlinger blur behind it. */
    val backdropBlur: StateFlow<Boolean> = _backdropBlur
    fun setBackdropBlur(on: Boolean) { _backdropBlur.value = on }

    // ------------------------------------------------------------------ android.graphics side

    /**
     * Sheen + top-edge hairline + refraction rim for Canvas-drawn surfaces. Shaders are rebuilt
     * only when the rect or colors change, so a HUD that only translates its canvas allocates
     * nothing per frame.
     */
    class CanvasGlass(private val density: Float) {
        private val sheen = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val last = RectF()
        private var lastA = 0
        private var lastB = 0
        private val inner = RectF()

        private fun rebuild(r: RectF, accent: Int, accent2: Int) {
            if (r == last && accent == lastA && accent2 == lastB && sheen.shader != null) return
            last.set(r); lastA = accent; lastB = accent2
            val frost = 0xD9FFFA
            sheen.shader = LinearGradient(0f, r.top, 0f, r.top + r.height() * 0.5f,
                intArrayOf((0x26 shl 24) or frost, 0x00000000), null, Shader.TileMode.CLAMP)
            edge.strokeWidth = density
            edge.shader = LinearGradient(0f, r.top, 0f, r.top + min(r.height() * 0.4f, 20f * density),
                intArrayOf((0x99 shl 24) or frost, 0x00000000), null, Shader.TileMode.CLAMP)
            rim.strokeWidth = 1.2f * density
            val a = accent and 0xFFFFFF; val b = accent2 and 0xFFFFFF
            rim.shader = LinearGradient(r.left, r.top, r.right, r.bottom,
                intArrayOf((0xDD shl 24) or a, (0x2A shl 24) or a, (0x1E shl 24) or b, (0xAA shl 24) or b),
                floatArrayOf(0f, 0.38f, 0.62f, 1f), Shader.TileMode.CLAMP)
        }

        /**
         * Draw over an already-filled rounded card, before its content. [alpha] 0..255 fades the
         * whole effect; [withRim] false keeps a surface's own neon border instead of the refraction rim.
         */
        fun drawOver(canvas: android.graphics.Canvas, r: RectF, radius: Float, accent: Int, accent2: Int = Mascot.argb(0xFF6FB0FF.toInt()), alpha: Int = 255, withRim: Boolean = true) {
            rebuild(r, accent, accent2)
            sheen.alpha = alpha; edge.alpha = alpha; rim.alpha = alpha
            canvas.drawRoundRect(r, radius, radius, sheen)
            val hw = rim.strokeWidth / 2f
            inner.set(r.left + hw, r.top + hw, r.right - hw, r.bottom - hw)
            if (withRim) canvas.drawRoundRect(inner, radius - hw, radius - hw, rim)
            inner.set(r.left + density, r.top + density, r.right - density, r.bottom - density)
            canvas.drawRoundRect(inner, radius - density, radius - density, edge)
        }

        /** Same, for an arbitrary card path (the chamfered volume bar) whose bounds are [r]. */
        fun drawOver(canvas: android.graphics.Canvas, path: android.graphics.Path, r: RectF, accent: Int, accent2: Int = Mascot.argb(0xFF6FB0FF.toInt()), alpha: Int = 255, withRim: Boolean = true) {
            rebuild(r, accent, accent2)
            sheen.alpha = alpha; edge.alpha = alpha; rim.alpha = alpha
            canvas.drawPath(path, sheen)
            if (withRim) canvas.drawPath(path, rim)
            canvas.save()
            canvas.translate(0f, density)
            canvas.clipPath(path)
            canvas.drawPath(path, edge)
            canvas.restore()
        }

        private val beadShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0x47000000 }
        private val beadBody = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val beadRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val beadDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xD9FFFFFF.toInt() }
        private val beadRect = RectF()

        /** Liquid bead thumb (the Compose slider's bead, for Canvas): frost core, accent rim, specular dot. */
        fun drawBead(canvas: android.graphics.Canvas, cx: Float, cy: Float, rx: Float, ry: Float, accent: Int, alpha: Int = 255) {
            beadRect.set(cx - rx, cy - ry + ry * 0.18f, cx + rx, cy + ry + ry * 0.18f)
            beadShadow.alpha = (0x47 * alpha) / 255
            canvas.drawRoundRect(beadRect, ry, ry, beadShadow)
            beadRect.set(cx - rx, cy - ry, cx + rx, cy + ry)
            beadBody.shader = android.graphics.RadialGradient(
                cx - rx * 0.25f, cy - ry * 0.35f, max(rx, ry) * 1.25f,
                intArrayOf(Mascot.argb(0xFFD9FFFA.toInt()), Mascot.argb(0xEBD9FFFA.toInt()), (accent and 0xFFFFFF) or (0xF2 shl 24)),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
            )
            beadBody.alpha = alpha
            canvas.drawRoundRect(beadRect, ry, ry, beadBody)
            beadRim.strokeWidth = density
            beadRim.color = accent; beadRim.alpha = alpha
            canvas.drawRoundRect(beadRect, ry, ry, beadRim)
            beadDot.alpha = (0xD9 * alpha) / 255
            canvas.drawCircle(cx - rx * 0.32f, cy - ry * 0.42f, min(rx, ry) * 0.22f, beadDot)
        }
    }
}

// ---------------------------------------------------------------------- the modifier

/**
 * The shape again, inset by [inset] (negative = outset), positioned at the origin; the caller
 * translates by the inset. For RoundedCornerShape the corner radius stays the same, which reads
 * as a slightly tighter halo; fine at these sizes.
 */
private fun CacheDrawScope.outlineInset(shape: Shape, inset: Float): Outline =
    shape.createOutline(
        Size(max(0f, size.width - 2 * inset), max(0f, size.height - 2 * inset)),
        layoutDirection, this
    )

/**
 * The HioneOS glass surface. Clips content to [shape]; shadow and halo are drawn outside it, so
 * leave a few dp of room (or no clipping parent) around raised surfaces.
 *
 * @param active 0..1, read at draw time: 0 = frosted neutral glass, 1 = accent-filled lit glass.
 * @param pressed press state from [rememberHionePress]; null for a surface that is not a button.
 * @param dimmed unavailable: everything drops back, no glow.
 */
fun Modifier.hioneGlass(
    shape: Shape = RoundedCornerShape(20.dp),
    style: HioneGlassStyle = HioneGlass.Card,
    accent: Color = HioneTealBright,
    accent2: Color = HionePinkBright,
    active: () -> Float = { 0f },
    pressed: HionePressState? = null,
    dimmed: Boolean = false
): Modifier = this
    // -------- outside the shape: contact shadow + active halo
    .drawWithCache {
        val shadowPx = style.shadow.toPx()
        val glowPx = style.glow.toPx()
        val bands = 4
        val shadowOutlines = if (shadowPx > 0f) List(bands) { i ->
            val step = shadowPx / bands
            (i + 0.5f) * step to outlineInset(shape, -(i + 0.5f) * step)
        } else emptyList()
        val glowOutlines = if (glowPx > 0f) List(3) { i ->
            val step = glowPx / 3
            (i + 0.5f) * step to outlineInset(shape, -(i + 0.5f) * step)
        } else emptyList()
        val shadowAlphas = floatArrayOf(0.22f, 0.13f, 0.07f, 0.03f)
        val glowAlphas = floatArrayOf(0.30f, 0.14f, 0.05f)
        onDrawBehind {
            val p = (pressed?.fraction ?: 0f).coerceIn(0f, 1f)
            if (shadowOutlines.isNotEmpty()) {
                val stepW = shadowPx / bands
                val dy = shadowPx * 0.45f * (1f - 0.6f * p)
                val k = (if (dimmed) 0.5f else 1f) * (1f - 0.55f * p)
                shadowOutlines.forEachIndexed { i, (out, o) ->
                    translate(-out, -out + dy) {
                        drawOutline(o, HioneGlass.ShadowInk.copy(alpha = shadowAlphas[i] * k), style = Stroke(stepW))
                    }
                }
            }
            val a = if (dimmed) 0f else active().coerceIn(0f, 1f)
            if (glowOutlines.isNotEmpty() && a > 0.01f) {
                val stepW = glowPx / 3
                val boost = 1f + 0.5f * p
                glowOutlines.forEachIndexed { i, (out, o) ->
                    translate(-out, -out) {
                        drawOutline(o, accent.copy(alpha = (glowAlphas[i] * a * boost).coerceAtMost(1f)), style = Stroke(stepW))
                    }
                }
            }
        }
    }
    .clip(shape)
    // -------- inside the shape: body, press bloom, content, specular, rim
    .drawWithCache {
        val w = size.width; val h = size.height
        val rimW = style.rimWidth.toPx()
        val rimOutline = outlineInset(shape, rimW / 2f)
        val edgeOutline = outlineInset(shape, 1.dp.toPx())
        val edgeH = min(h * 0.45f, 22.dp.toPx())
        val sheen = Brush.verticalGradient(
            0f to HioneGlass.Frost.copy(alpha = 0.16f * style.specular),
            0.5f to Color.Transparent,
            startY = 0f, endY = h
        )
        val topEdge = Brush.verticalGradient(
            listOf(HioneGlass.Frost.copy(alpha = 0.62f * style.specular), Color.Transparent),
            startY = 0f, endY = edgeH
        )
        val grooveShade = Brush.verticalGradient(
            listOf(HioneGlass.ShadowInk.copy(alpha = 0.6f), Color.Transparent),
            startY = 0f, endY = h * 0.45f
        )
        val grooveLip = Brush.verticalGradient(
            listOf(Color.Transparent, HioneGlass.Frost.copy(alpha = 0.32f * style.specular)),
            startY = h * 0.55f, endY = h
        )
        val caustic = Brush.radialGradient(
            listOf(accent2.copy(alpha = 0.13f * style.rim), Color.Transparent),
            center = Offset(w * 0.92f, h * 0.95f), radius = max(w, h) * 0.55f
        )
        val bodyTopDim = style.bodyTop.copy(alpha = style.opacity * 0.7f)
        val bodyBottomDim = style.bodyBottom.copy(alpha = style.opacity * 0.7f)
        onDrawWithContent {
            val a = if (dimmed) 0f else active().coerceIn(0f, 1f)
            val p = (pressed?.fraction ?: 0f).coerceIn(0f, 1f)
            // body
            if (dimmed) {
                drawRect(Brush.verticalGradient(listOf(bodyTopDim, bodyBottomDim)))
            } else {
                val top = lerp(style.bodyTop, accent, a * 0.92f)
                val bottom = lerp(style.bodyBottom, lerp(accent, HioneGlass.ShadowInk, 0.42f), a * 0.9f)
                val op = style.opacity + (0.97f - style.opacity) * a
                drawRect(Brush.verticalGradient(listOf(top.copy(alpha = op * 0.94f), bottom.copy(alpha = op))))
            }
            if (style.recessed) drawRect(grooveShade) else drawRect(caustic)
            // press bloom from the touch point
            if (p > 0.01f && pressed != null) {
                val c = pressed.point.takeIf { it.isSpecified() } ?: Offset(w / 2f, h / 2f)
                val r = max(w, h) * (0.45f + 0.55f * p)
                drawCircle(
                    Brush.radialGradient(listOf(HioneGlass.Frost.copy(alpha = 0.26f * p), Color.Transparent), center = c, radius = r),
                    radius = r, center = c
                )
            }
            // specular sheen sits BEHIND the content so it never hazes text or icons
            val specK = if (dimmed) 0.35f else 1f - 0.4f * p
            if (!style.recessed) drawRect(sheen, alpha = specK * (1f - 0.5f * a))
            drawContent()
            // edge light goes on top: it lives in the outer 1-2dp where content never is
            if (style.recessed) {
                drawRect(grooveLip, alpha = specK)
            } else {
                translate(1.dp.toPx(), 1.dp.toPx()) { drawOutline(edgeOutline, topEdge, alpha = specK, style = Stroke(1.dp.toPx())) }
            }
            // refraction rim
            val rimK = (if (dimmed) 0.35f else style.rim) * (1f + 0.35f * a)
            val rim = Brush.linearGradient(
                0f to accent.copy(alpha = (0.85f * rimK).coerceAtMost(1f)),
                0.38f to accent.copy(alpha = 0.16f * rimK + 0.3f * a),
                0.62f to accent2.copy(alpha = 0.10f * rimK + 0.15f * a),
                1f to accent2.copy(alpha = (0.6f * rimK).coerceAtMost(1f)),
                start = Offset.Zero, end = Offset(w, h)
            )
            translate(rimW / 2f, rimW / 2f) { drawOutline(rimOutline, rim, style = Stroke(rimW)) }
        }
    }

private fun Offset.isSpecified(): Boolean = this != Offset.Unspecified && !x.isNaN() && !y.isNaN()

// ---------------------------------------------------------------------- press physics

/** Animated press state shared by [hioneGlass] (bloom, shadow) and [hionePressScale] (sink). */
@Stable
class HionePressState internal constructor() {
    internal val anim = Animatable(0f)
    /** Where the finger went down, in the pressed node's coordinates. Draw-phase reads only. */
    var point: Offset = Offset.Unspecified
        internal set
    /** 0 at rest, 1 fully pressed; goes slightly negative on the release rebound. */
    val fraction: Float get() = anim.value
}

/**
 * Collects [interactionSource] (share it with the clickable) into a [HionePressState].
 * Down is a quick 70ms ease; release is an under-damped spring so the surface pops back a little
 * past rest. A tap that releases before the press is visible still plays a short dip, otherwise
 * quick taps would look like nothing happened.
 */
@Composable
fun rememberHionePress(interactionSource: InteractionSource): HionePressState {
    val state = remember { HionePressState() }
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { i ->
            when (i) {
                is PressInteraction.Press -> {
                    state.point = i.pressPosition
                    launch { state.anim.animateTo(1f, tween(HioneMotion.ms(70))) }
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> launch {
                    if (state.anim.value < 0.55f) state.anim.animateTo(0.7f, tween(HioneMotion.ms(50)))
                    state.anim.animateTo(
                        0f,
                        if (HioneMotion.quiet) tween(HioneMotion.QUICK) else spring(dampingRatio = 0.42f, stiffness = 520f)
                    )
                }
            }
        }
    }
    return state
}

/** The button sinks while pressed: uniform shrink plus a touch of vertical squash. */
fun Modifier.hionePressScale(press: HionePressState, depth: Float = 0.06f): Modifier = graphicsLayer {
    val f = press.fraction
    scaleX = 1f - depth * f
    scaleY = 1f - depth * 1.25f * f
}

// ---------------------------------------------------------------------- slider

/**
 * Thick glass slider (Android 12+ shape, Hione materials). The fill is a lit glass pill that
 * carries [icon]; the thumb is a liquid bead at its end that stretches along the drag in
 * proportion to speed and wobbles back when the finger slows. Tap jumps, drag tracks.
 */
@Composable
fun HioneGlassSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    icon: ImageVector? = null,
    contentDescription: String? = null,
    accent: Color = HioneTeal,
    accentBright: Color = HioneTealBright,
    accent2: Color = HionePinkBright,
    height: Dp = 44.dp,
    onValueChangeFinished: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val stretch = remember { Animatable(0f) }
    val held = remember { Animatable(0f) }
    val currentValue by rememberUpdatedState(value)
    val onChange by rememberUpdatedState(onValueChange)
    val onFinished by rememberUpdatedState(onValueChangeFinished)
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    fun frac(v: Float) = ((v - valueRange.start) / span).coerceIn(0f, 1f)

    Box(
        modifier
            .height(height)
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription }
            .hioneGlass(RoundedCornerShape(50), HioneGlass.Groove, accent = accentBright, accent2 = accent2)
            .pointerInput(valueRange) {
                fun valueAt(x: Float): Float {
                    val pad = size.height / 2f
                    val f = ((x - pad) / (size.width - 2 * pad).coerceAtLeast(1f)).coerceIn(0f, 1f)
                    return valueRange.start + f * span
                }
                detectTapGestures(
                    onPress = {
                        scope.launch { held.animateTo(1f, tween(HioneMotion.ms(90))) }
                        // false = a drag took the pointer over; the drag's end releases the bead.
                        if (tryAwaitRelease()) scope.launch { held.animateTo(0f, HioneMotion.bouncy()) }
                    },
                    onTap = { o -> onChange(valueAt(o.x)); onFinished() }
                )
            }
            .pointerInput(valueRange) {
                fun valueAt(x: Float): Float {
                    val pad = size.height / 2f
                    val f = ((x - pad) / (size.width - 2 * pad).coerceAtLeast(1f)).coerceIn(0f, 1f)
                    return valueRange.start + f * span
                }
                val fullStretchPx = 14.dp.toPx()
                detectHorizontalDragGestures(
                    onDragStart = { o ->
                        scope.launch { held.animateTo(1f, tween(HioneMotion.ms(90))) }
                        onChange(valueAt(o.x))
                    },
                    onDragEnd = {
                        scope.launch { held.animateTo(0f, HioneMotion.bouncy()) }
                        onFinished()
                    },
                    onDragCancel = {
                        scope.launch { held.animateTo(0f, HioneMotion.bouncy()) }
                        onFinished()
                    },
                    onHorizontalDrag = { change, dx ->
                        change.consume()
                        onChange(valueAt(change.position.x))
                        if (!HioneMotion.quiet) scope.launch {
                            val target = (dx / fullStretchPx).coerceIn(-1f, 1f)
                            stretch.snapTo(stretch.value * 0.5f + target * 0.5f)
                            stretch.animateTo(0f, spring(dampingRatio = 0.38f, stiffness = 260f))
                        }
                    }
                )
            }
            .drawWithCache {
                val h = size.height
                val inset = 4.dp.toPx()
                val pillH = h - 2 * inset
                val fillBrushCache = Brush.horizontalGradient(listOf(accent, accentBright), startX = inset, endX = size.width - inset)
                val fillSheen = Brush.verticalGradient(
                    listOf(HioneGlass.Frost.copy(alpha = 0.38f), Color.Transparent),
                    startY = inset, endY = inset + pillH * 0.55f
                )
                onDrawBehind {
                    val f = frac(currentValue)
                    val w = size.width
                    val fillW = pillH + (w - 2 * inset - pillH) * f
                    val r = CornerRadius(pillH / 2f)
                    // fill: lit glass pill
                    drawRoundRect(fillBrushCache, Offset(inset, inset), Size(fillW, pillH), r, alpha = 0.92f)
                    drawRoundRect(fillSheen, Offset(inset, inset), Size(fillW, pillH), r)
                    // liquid bead
                    drawLiquidBead(
                        center = Offset(inset + fillW - pillH / 2f, h / 2f),
                        radius = pillH / 2f - 2.dp.toPx(),
                        stretch = stretch.value,
                        held = held.value,
                        rim = accentBright,
                        rim2 = accent2
                    )
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        if (icon != null) {
            Icon(
                icon, null, tint = HioneDarkBg,
                modifier = Modifier.padding(start = height / 2 - 9.dp).size(18.dp)
            )
        }
    }
}

/** Liquid bead: frost core, accent rim, specular dot; stretches along x with velocity. */
private fun DrawScope.drawLiquidBead(center: Offset, radius: Float, stretch: Float, held: Float, rim: Color, rim2: Color) {
    val grow = 1f + 0.12f * held
    val s = abs(stretch)
    val rx = radius * grow * (1f + 0.45f * s)
    val ry = radius * grow * (1f - 0.16f * s)
    // the bead lags behind its motion, like a drop being dragged
    val cx = center.x - sign(stretch) * s * radius * 0.35f
    val topLeft = Offset(cx - rx, center.y - ry)
    val sz = Size(rx * 2, ry * 2)
    val cr = CornerRadius(ry, ry)
    drawRoundRect(
        Color.Black.copy(alpha = 0.28f), topLeft + Offset(0f, radius * 0.18f), sz, cr
    )
    drawRoundRect(
        Brush.radialGradient(
            listOf(HioneGlass.Frost, HioneGlass.Frost.copy(alpha = 0.92f), rim.copy(alpha = 0.95f)),
            center = Offset(cx - rx * 0.25f, center.y - ry * 0.35f), radius = max(rx, ry) * 1.25f
        ),
        topLeft, sz, cr
    )
    drawRoundRect(
        Brush.linearGradient(listOf(rim, rim2.copy(alpha = 0.7f)), start = topLeft, end = topLeft + Offset(sz.width, sz.height)),
        topLeft, sz, cr, style = Stroke(1.2f * (radius / 14f).coerceIn(0.8f, 1.6f))
    )
    drawCircle(Color.White.copy(alpha = 0.85f), radius = radius * 0.2f, center = Offset(cx - rx * 0.32f, center.y - ry * 0.42f))
}
