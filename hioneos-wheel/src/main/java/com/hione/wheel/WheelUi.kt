package com.hione.wheel

import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Battery for the title bars, fed by the activity's sticky battery broadcast. */
object Power {
    var pct = 100
    var charging = false
}

private val noFilter = Paint().apply { isFilterBitmap = false; isAntiAlias = false; isDither = false }

/**
 * The skin: the scaled screen up top, the wheel below, and two quiet links under the wheel.
 * [frame] is the redraw counter; reading it in draw scopes makes those redraw on change.
 */
@Composable
fun SkinShell(
    skin: Skin,
    frame: MutableIntState,
    onEras: () -> Unit,
    onExit: () -> Unit,
) {
    skin.stackVersion.intValue
    val st = skin.style
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val renderer = remember(st) { Renderer(ctx, st) }
    val view = LocalView.current
    val density = LocalDensity.current
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(st.caseTop), Color(st.caseBot))))
    ) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(28.dp))
            val pw = with(density) { (st.w * st.scale).toDp() }
            val ph = with(density) { (st.h * st.scale).toDp() }
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(st.bezel))
                    .padding(9.dp)
            ) {
                Box(Modifier.size(pw, ph)) {
                    // Reused every frame: the panel redraws on each wheel detent and clock tick.
                    val dst = remember { Rect() }
                    Canvas(Modifier.size(pw, ph)) {
                        frame.intValue
                        Power.let { renderer.batteryPct = it.pct; renderer.charging = it.charging }
                        renderer.render(skin, System.currentTimeMillis())
                        drawIntoCanvas {
                            dst.set(0, 0, size.width.toInt(), size.height.toInt())
                            it.nativeCanvas.drawBitmap(renderer.bitmap, null, dst, noFilter)
                        }
                    }
                    skin.stackVersion.intValue
                    val top = skin.top
                    if (top is CoverFlowScreen) CoverFlowLayer(skin, top, renderer, st, frame)
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                val d = min(maxWidth.value * 0.84f, maxHeight.value * 0.94f).dp
                ClickWheel(
                    st, d, view,
                    onScroll = { n -> skin.onScroll(n, view); frame.intValue++ },
                    onDown = { skin.clicker.wake() },
                    onClick = { b -> skin.clicker.press(feelOf(skin.era, b)) },
                    onButton = { b -> skin.onButton(b, view); frame.intValue++ },
                    onHold = { b -> skin.onHold(b, view).also { frame.intValue++ } },
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(bottom = 14.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                QuietLink("Eras", Color(st.wheelLabel), onEras)
                QuietLink("Exit", Color(st.wheelLabel), onExit)
            }
        }
    }
    // The clock: per frame while something animates, four times a second otherwise.
    LaunchedEffect(skin) {
        var last = 0L
        var odd = false
        while (true) {
            val t = skin.top
            val animating = t is BrickScreen || t is SlideshowScreen || t is BootScreen ||
                (t is MenuScreen && t.split)
            if (animating) {
                androidx.compose.runtime.withFrameNanos { ns ->
                    if (t is BrickScreen) {
                        val dt = if (last == 0L) 0f else (ns - last) / 1e9f
                        t.game.step(dt)
                    }
                    last = ns
                }
                odd = !odd
                // Split-screen pans are slow; 30 fps is plenty for them.
                if (t is MenuScreen && odd) continue
            } else {
                last = 0L
                delay(if (t is StopwatchScreen) 100L else 250L)
            }
            skin.tick(System.currentTimeMillis())
            frame.intValue++
        }
    }
}

@Composable
private fun QuietLink(label: String, color: Color, onClick: () -> Unit) {
    BasicText(
        label,
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 8.dp),
        style = TextStyle(color = color, fontSize = 15.sp, fontFamily = SansFamily, fontWeight = FontWeight.Bold)
    )
}

val PixelFamily = FontFamily(Font(R.font.pixel_operator_bold))
val SansFamily = FontFamily(Font(R.font.pt_sans), Font(R.font.pt_sans_bold, FontWeight.Bold))

/** Which switch a press feels like on this era. */
fun feelOf(e: Era, b: Btn): Feel = when {
    b == Btn.SELECT -> Feel.CENTER
    e == Era.MONO_2001 -> Feel.SIDE
    else -> Feel.RING
}

/**
 * Steps per turn. Neither wheel had detents, so these follow Rockbox's drivers: 96 positions per
 * turn on all of them, one step every 6 on the 1G to 3G wheels (16 a turn, 22.5 degrees) and
 * every 4 on the click wheel (24 a turn, 15 degrees).
 */
fun detentsOf(e: Era): Int = if (e == Era.MONO_2001) 16 else 24

/**
 * The wheel. Drag around the ring to scroll (clockwise = down), tap the ring's
 * top/right/bottom/left for MENU / next / play-pause / previous, tap the middle to select.
 * Holding a button does that button's hold action (seek, light, hidden game).
 *
 * Like the originals, a touch that starts on the center is only ever a button (it never
 * scrolls), and a touch on the ring is a scroll once it has turned more than half a step,
 * otherwise a button press on release. The center "clicks" (sound and pulse) as it goes down,
 * the way its dome switch did. On 2001 the wheel and the buttons are separate parts: the
 * outer band is four buttons that never scroll, the inner wheel scrolls and never presses.
 */
@Composable
fun ClickWheel(
    st: Style,
    diameter: androidx.compose.ui.unit.Dp,
    view: View,
    onScroll: (Int) -> Unit,
    onDown: () -> Unit,
    onClick: (Btn) -> Unit,
    onButton: (Btn) -> Unit,
    onHold: (Btn) -> Boolean,
) {
    val scope = rememberCoroutineScope()
    val label = remember(st) {
        Paint().apply {
            isAntiAlias = true; typeface = Fonts.sansBold; color = st.wheelLabel; textAlign = Paint.Align.CENTER
        }
    }
    Canvas(
        Modifier
            .size(diameter)
            .pointerInput(st) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val outer = size.width / 2f
                    val inner = outer * CENTER_RATIO
                    val dx0 = down.position.x - cx
                    val dy0 = down.position.y - cy
                    val r0 = sqrt(dx0 * dx0 + dy0 * dy0)
                    if (r0 > outer * 1.04f) return@awaitEachGesture
                    onDown()
                    val onCenter = r0 < inner
                    val separate = st.era == Era.MONO_2001
                    val onButtonBand = separate && !onCenter && r0 > outer * BUTTON_BAND_2001
                    val canScroll = !onCenter && !onButtonBand
                    val canPress = onCenter || !separate || onButtonBand
                    val btn = if (onCenter) Btn.SELECT else buttonAt(dx0, dy0)
                    if (onCenter) onClick(Btn.SELECT)
                    var lastA = atan2(dy0, dx0)
                    var acc = 0f
                    var travel = 0f
                    var scrolling = false
                    var held = false
                    val detent = (2 * PI / detentsOf(st.era)).toFloat()
                    val holdJob: Job? = if (!canPress) null else scope.launch {
                        delay(holdMs(btn))
                        held = onHold(btn)
                        // A ring switch held down clicked when it closed; we only know now.
                        if (held && !onCenter) onClick(btn)
                        if (held && (btn == Btn.NEXT || btn == Btn.PREV)) while (true) { delay(260); onHold(btn) }
                    }
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        val dx = ch.position.x - cx
                        val dy = ch.position.y - cy
                        if (canScroll && !held) {
                            val a = atan2(dy, dx)
                            var d = a - lastA
                            if (d > PI) d -= (2 * PI).toFloat()
                            if (d < -PI) d += (2 * PI).toFloat()
                            lastA = a
                            acc += d
                            travel += abs(d)
                            if (!scrolling && travel > detent * 0.55f) { scrolling = true; holdJob?.cancel() }
                            if (scrolling) {
                                var n = 0
                                while (acc >= detent) { n++; acc -= detent }
                                while (acc <= -detent) { n--; acc += detent }
                                if (n != 0) onScroll(n)
                            }
                        }
                        ch.consume()
                    }
                    holdJob?.cancel()
                    if (canPress && !scrolling && !held) {
                        if (!onCenter) onClick(btn)
                        onButton(btn)
                    }
                }
            }
    ) {
        val r = size.width / 2f
        val c = Offset(r, r)
        // The ring, with a soft top light.
        drawCircle(Brush.verticalGradient(listOf(lighten(st.wheel, 0.06f), Color(st.wheel), darken(st.wheel, 0.05f))), r, c)
        drawCircle(Color(st.wheelEdge), r - 1.5f, c, style = Stroke(3f))
        if (st.era == Era.MONO_2001) {
            // The first wheel turned inside a ring of four separate buttons.
            drawCircle(Color(st.wheelEdge), r * BUTTON_BAND_2001, c, style = Stroke(2.5f))
        }
        val inner = r * CENTER_RATIO
        drawCircle(Brush.verticalGradient(listOf(lighten(st.center, 0.04f), Color(st.center), darken(st.center, 0.06f)),
            startY = c.y - inner, endY = c.y + inner), inner, c)
        drawCircle(Color(st.centerEdge), inner, c, style = Stroke(2.5f))
        val lr = if (st.era == Era.MONO_2001) r * 0.85f else r * 0.79f
        val unit = r / 14f
        val labelColor = Color(st.wheelLabel)
        drawIntoCanvas { cv ->
            label.textSize = unit * 2.0f
            cv.nativeCanvas.drawText("MENU", c.x, c.y - lr + unit * 0.75f, label)
        }
        // Next, previous, play/pause glyphs.
        skipGlyph(c.x + lr, c.y, unit, labelColor, forward = true)
        skipGlyph(c.x - lr, c.y, unit, labelColor, forward = false)
        playPauseGlyph(c.x, c.y + lr, unit, labelColor)
    }
}

/** 2001: the inner edge of the button ring, as a fraction of the wheel's radius. */
private const val BUTTON_BAND_2001 = 0.70f
private const val CENTER_RATIO = 0.36f
/** Seeking starts quickly; the light and the hidden game want a deliberate hold. */
private fun holdMs(b: Btn) = when (b) {
    Btn.NEXT, Btn.PREV -> 600L
    Btn.SELECT -> 1500L
    else -> 1200L
}

private fun buttonAt(dx: Float, dy: Float): Btn =
    if (abs(dx) > abs(dy)) { if (dx > 0) Btn.NEXT else Btn.PREV } else { if (dy < 0) Btn.MENU else Btn.PLAY }

private fun lighten(c: Int, f: Float) = Color(c).let { Color(it.red + (1 - it.red) * f, it.green + (1 - it.green) * f, it.blue + (1 - it.blue) * f) }
private fun darken(c: Int, f: Float) = Color(c).let { Color(it.red * (1 - f), it.green * (1 - f), it.blue * (1 - f)) }

private fun androidx.compose.ui.graphics.drawscope.DrawScope.tri(x: Float, y: Float, s: Float, color: Color, right: Boolean) {
    val p = androidx.compose.ui.graphics.Path()
    if (right) { p.moveTo(x, y - s); p.lineTo(x + s * 1.2f, y); p.lineTo(x, y + s) }
    else { p.moveTo(x, y - s); p.lineTo(x - s * 1.2f, y); p.lineTo(x, y + s) }
    p.close()
    drawPath(p, color)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.skipGlyph(x: Float, y: Float, u: Float, color: Color, forward: Boolean) {
    val s = u * 0.75f
    if (forward) {
        tri(x - s * 2.2f, y, s, color, true)
        tri(x - s * 1.0f, y, s, color, true)
        drawRect(color, Offset(x + s * 0.25f, y - s), androidx.compose.ui.geometry.Size(s * 0.38f, s * 2))
    } else {
        tri(x + s * 2.2f, y, s, color, false)
        tri(x + s * 1.0f, y, s, color, false)
        drawRect(color, Offset(x - s * 0.63f, y - s), androidx.compose.ui.geometry.Size(s * 0.38f, s * 2))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.playPauseGlyph(x: Float, y: Float, u: Float, color: Color) {
    val s = u * 0.75f
    tri(x - s * 1.5f, y, s, color, true)
    drawRect(color, Offset(x + s * 0.3f, y - s), androidx.compose.ui.geometry.Size(s * 0.42f, s * 2))
    drawRect(color, Offset(x + s * 1.05f, y - s), androidx.compose.ui.geometry.Size(s * 0.42f, s * 2))
}

/**
 * Cover Flow's covers, drawn over the panel's black stage. Each cover is a graphicsLayer turned
 * with rotationY; the animated position is read only inside the layer blocks, so a spin
 * re-renders layers without recomposing or allocating per frame.
 */
@Composable
private fun CoverFlowLayer(skin: Skin, s: CoverFlowScreen, renderer: Renderer, st: Style, frame: MutableIntState) {
    // Recompose as the selection moves so the window of covers follows it.
    frame.intValue
    val pos = remember(s) { Animatable(s.sel.toFloat()) }
    LaunchedEffect(s, s.sel) {
        pos.animateTo(s.sel.toFloat(), spring(dampingRatio = 0.92f, stiffness = Spring.StiffnessMediumLow))
    }
    val flip by animateFloatAsState(if (s.flipped) 1f else 0f, tween(380), label = "flip")
    val backImage = remember(renderer) { renderer.back.asImageBitmap() }
    val density = LocalDensity.current
    val scale = st.scale
    val coverNative = 112
    val coverPx = coverNative * scale
    val reflPx = (coverPx * 0.42f).roundToInt()
    val coverDp = with(density) { coverPx.toDp() }
    val heightDp = with(density) { (coverPx + reflPx).toDp() }
    val left = (st.w * scale - coverPx) / 2
    val topPx = 40 * scale
    val spreadNear = coverPx * 0.62f
    val spreadFar = coverPx * 0.24f
    val fade = remember(reflPx) {
        Brush.verticalGradient(listOf(Color(0x8C000000), Color.Black), startY = coverPx.toFloat(), endY = (coverPx + reflPx).toFloat())
    }
    val noArtFill = remember(coverPx) {
        Brush.verticalGradient(listOf(Color(0xFF8E959D), Color(0xFF4A5057)), 0f, coverPx.toFloat())
    }
    val window = 6
    for (k in -window..window) {
        val idx = s.sel + k
        val album = s.albums.getOrNull(idx) ?: continue
        key(idx) {
            Canvas(
                Modifier
                    .offset { IntOffset(left, topPx) }
                    .size(coverDp, heightDp)
                    .zIndex(-abs(k).toFloat())
                    .graphicsLayer {
                        val off = idx - pos.value
                        val a = abs(off)
                        val sign = if (off < 0) -1f else 1f
                        translationX = if (a < 1f) off * spreadNear else sign * (spreadNear + (a - 1f) * spreadFar)
                        val turn = -off.coerceIn(-1f, 1f) * 62f
                        val isCenter = idx == s.sel
                        rotationY = turn + if (isCenter) flip * 180f else 0f
                        val base = if (a < 1f) 1f - 0.18f * a else 0.82f
                        val grow = if (isCenter) 1f + 0.22f * flip else 1f
                        scaleX = base * grow
                        scaleY = base * grow
                        cameraDistance = 7f * density.density
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, coverPx / 2f / (coverPx + reflPx))
                        alpha = if (a > window - 0.5f) 0f else 1f
                    }
            ) {
                frame.intValue
                val art = skin.art.get(album.id)
                val showBack = idx == s.sel && flip > 0.5f
                if (showBack) {
                    // Past 90 degrees we see the back, mirrored; un-mirror it.
                    scale(-1f, 1f, Offset(coverPx / 2f, coverPx / 2f)) {
                        drawImage(backImage, srcOffset = IntOffset.Zero,
                            srcSize = IntSize(renderer.backSize, renderer.backSize),
                            dstOffset = IntOffset.Zero, dstSize = IntSize(coverPx, coverPx))
                    }
                    return@Canvas
                }
                if (art != null) {
                    val iw = art.bitmap.width
                    val ih = art.bitmap.height
                    drawImage(art.image, srcOffset = IntOffset.Zero, srcSize = IntSize(iw, ih),
                        dstOffset = IntOffset.Zero, dstSize = IntSize(coverPx, coverPx))
                    val srcTop = (ih * (1f - reflPx.toFloat() / coverPx)).toInt()
                    withTransform({ scale(1f, -1f, Offset(0f, coverPx + reflPx / 2f)) }) {
                        drawImage(art.image, srcOffset = IntOffset(0, srcTop), srcSize = IntSize(iw, ih - srcTop),
                            dstOffset = IntOffset(0, coverPx), dstSize = IntSize(coverPx, reflPx))
                    }
                } else {
                    drawRect(noArtFill,
                        size = androidx.compose.ui.geometry.Size(coverPx.toFloat(), coverPx.toFloat()))
                    drawRect(Color(0xFF3A3F45), topLeft = Offset(0f, coverPx.toFloat()),
                        size = androidx.compose.ui.geometry.Size(coverPx.toFloat(), reflPx.toFloat()))
                }
                drawRect(fade, topLeft = Offset(0f, coverPx.toFloat()),
                    size = androidx.compose.ui.geometry.Size(coverPx.toFloat(), reflPx.toFloat()))
            }
        }
    }
}

/** The era picker: four eras, locked ones as "???" with their hint. */
@Composable
fun EraPicker(
    unlocked: Set<Era>,
    forced: Boolean,
    onPick: (Era) -> Unit,
    onExit: () -> Unit,
) {
    val ink = Color(0xFF1B1E23)
    val lcd = Color(0xFFC4D5E2)
    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFFF7F8F9), Color(0xFFD5D9DD))))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF9EA4AA))
                .padding(8.dp)
        ) {
            Column(
                Modifier.fillMaxWidth().background(lcd).padding(vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                BasicText(Brand.NAME, style = TextStyle(color = ink, fontSize = 44.sp, fontFamily = PixelFamily))
                BasicText("Pick an era", style = TextStyle(color = ink, fontSize = 20.sp, fontFamily = PixelFamily))
            }
        }
        Spacer(Modifier.height(18.dp))
        for (era in Era.entries) {
            val open = forced || era in unlocked
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (open) Color.White else Color(0xFFE6E8EB))
                    .border(1.dp, Color(0xFFB8BDC2), RoundedCornerShape(14.dp))
                    .clickable(enabled = open) { onPick(era) }
                    .padding(horizontal = 18.dp, vertical = 14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(14.dp).clip(CircleShape)
                            .background(if (open) Mascot.color(0xFFFFAB2E) else Color(0xFFA7ADB3))
                    )
                    Spacer(Modifier.width(12.dp))
                    BasicText(
                        if (open) era.title else "???",
                        style = TextStyle(color = ink, fontSize = 26.sp, fontFamily = PixelFamily)
                    )
                }
                Spacer(Modifier.height(4.dp))
                BasicText(
                    if (open) era.blurb else "Locked. ${era.hint}",
                    style = TextStyle(color = Color(0xFF555B62), fontSize = 16.sp, fontFamily = SansFamily)
                )
            }
        }
        if (unlocked.isEmpty() && !forced) {
            Spacer(Modifier.height(10.dp))
            BasicText(
                "Each era is a secret in the BPM game. Play along and they will turn up.",
                style = TextStyle(color = Color(0xFF555B62), fontSize = 15.sp, fontFamily = SansFamily, textAlign = TextAlign.Center)
            )
        }
        Spacer(Modifier.height(22.dp))
        BasicText(
            "Exit to ${Mascot.name} home",
            Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(Mascot.color(0xFFFFAB2E))
                .clickable(onClick = onExit)
                .padding(horizontal = 28.dp, vertical = 12.dp),
            style = TextStyle(color = Color.White, fontSize = 18.sp, fontFamily = SansFamily, fontWeight = FontWeight.Bold)
        )
        Spacer(Modifier.height(18.dp))
        BasicText(
            Brand.TRIBUTE,
            style = TextStyle(color = Color(0xFF6B7178), fontSize = 13.sp, fontFamily = SansFamily, textAlign = TextAlign.Center)
        )
    }
}
