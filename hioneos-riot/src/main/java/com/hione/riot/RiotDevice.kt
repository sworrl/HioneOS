package com.hione.riot

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

// Body colors, picked off the reference photo of a real unit: a dark slate gray, matte.
private val BodyTop = Color(0xFF5A6068)
private val BodyMid = Color(0xFF454A52)
private val BodyLow = Color(0xFF33373E)
private val BodyEdge = Color(0xFF23262B)
private val BodyShine = Color(0x33FFFFFF)
private val Recess = Color(0xFF1C1F23)
private val PadFace = Color(0xFF3A3F46)
private val PadRim = Color(0xFF5F656E)
private val Print = Color(0xFFB9BEC5)
private val PrintDim = Color(0xFF8E949C)

/**
 * Colors for the four LCD levels. Measured by eye off the TechTV footage: a pale mint panel
 * with dark gray-green ink when lit, a duller gray-green when the backlight is off. Contrast
 * (0 to 14, as on the Riot) moves the ink between faint and full.
 */
fun lcdPalette(lit: Boolean, contrast: Int, asleep: Boolean): IntArray {
    if (asleep) return IntArray(4) { 0xFF5E655C.toInt() }
    val bg = if (lit) 0xCFE2C6 else 0x9AA493
    val ink = 0x1C2820
    val strength = 0.40f + contrast.coerceIn(0, 14) / 14f * 0.60f
    fun mix(t: Float): Int {
        fun ch(a: Int, b: Int, sh: Int): Int {
            val x = (a shr sh) and 0xFF; val y = (b shr sh) and 0xFF
            return (x + ((y - x) * t)).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(bg, ink, 16) shl 16) or (ch(bg, ink, 8) shl 8) or ch(bg, ink, 0)
    }
    return intArrayOf(mix(0f), mix(0.28f * strength), mix(0.6f * strength), mix(strength))
}

/**
 * Where everything on the body sits, in screen pixels. The layout follows a photo of a real
 * Riot held in landscape: the LCD in the middle, the round four-way pad on the left with the
 * small silver button above and to its right, Select, the thumb wheel and Back stacked on the
 * right, "Rio" printed at the top right. Nothing else is on the face: Volume and the On / Lock
 * switch were on the real unit's edges, and the M500's own volume keys stand in for Volume.
 * The silver button is the Riot's Menu key; holding it leaves Riot mode.
 */
private class BodyGeom(val w: Float, val h: Float, density: Float) {
    // Largest whole-number LCD scale that still leaves room for the controls at the sides.
    val scale: Int = run {
        val side = 140f * density
        val rim = 40f * density
        var s = 1
        while ((s + 1) * Lcd.W <= w - 2 * side && (s + 1) * Lcd.H <= h - 2 * rim) s++
        s
    }
    val lcdW = (Lcd.W * scale).toFloat()
    val lcdH = (Lcd.H * scale).toFloat()
    val lcdX = ((w - lcdW) / 2f).toInt().toFloat()
    val lcdY = ((h - lcdH) / 2f).toInt().toFloat() + (h * 0.01f).toInt()
    val bezel = h * 0.034f
    val frame = Rect(lcdX - bezel, lcdY - bezel, lcdX + lcdW + bezel, lcdY + lcdH + bezel)

    val left = w * 0.008f
    val right = w - w * 0.008f
    val top = h * 0.035f
    val bottom = h - h * 0.015f
    val corner = h * 0.17f
    val dip = h * 0.06f

    val sideL = frame.left - left
    val sideR = right - frame.right

    // Left: the pad and the silver button.
    val padR = minOf(sideL * 0.38f, h * 0.14f)
    val padC = Offset(left + sideL * 0.50f, top + (bottom - top) * 0.50f)
    val powerR = h * 0.028f
    val powerC = Offset(padC.x - sideL * 0.04f, top + (bottom - top) * 0.20f)

    // Right: Select, the wheel, Back, the wordmark.
    val wheelR = minOf(sideR * 0.36f, h * 0.13f)
    val wheelC = Offset(frame.right + sideR * 0.56f, top + (bottom - top) * 0.50f)
    val keyR = h * 0.034f
    val selectC = Offset(frame.right + sideR * 0.50f, top + (bottom - top) * 0.25f)
    val backC = Offset(frame.right + sideR * 0.50f, top + (bottom - top) * 0.76f)
    val logoC = Offset(frame.right + sideR * 0.20f, top + (bottom - top) * 0.14f)

    /** The top edge dips toward the middle and the ends flare out, the Riot's hourglass. */
    fun topY(x: Float): Float {
        val t = ((x - left) / (right - left)).coerceIn(0f, 1f)
        return top + dip * sin(PI.toFloat() * t)
    }
    fun bottomY(x: Float): Float {
        val t = ((x - left) / (right - left)).coerceIn(0f, 1f)
        return bottom - dip * sin(PI.toFloat() * t)
    }

    fun bodyPath(): Path = Path().apply {
        val n = 64
        val c = corner
        moveTo(left, topY(left) + c)
        quadraticTo(left, topY(left), left + c, topY(left + c))
        for (i in 0..n) { val x = left + c + (right - left - 2 * c) * i / n; lineTo(x, topY(x)) }
        quadraticTo(right, topY(right), right, topY(right) + c)
        lineTo(right, bottomY(right) - c)
        quadraticTo(right, bottomY(right), right - c, bottomY(right - c))
        for (i in 0..n) { val x = right - c - (right - left - 2 * c) * i / n; lineTo(x, bottomY(x)) }
        quadraticTo(left, bottomY(left), left, bottomY(left) - c)
        close()
    }
}

@Composable
fun RiotDevice(shell: RiotShell, onPower: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val density = LocalDensity.current
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val g = remember(wPx, hPx) { BodyGeom(wPx, hPx, density.density) }
        val pressed = remember { mutableStateMapOf<String, Boolean>() }
        val spin = remember { mutableFloatStateOf(0f) }

        // The body and everything printed or molded on it.
        Canvas(Modifier.fillMaxSize()) {
            drawBody(g, pressed, spin.floatValue)
        }

        fun Modifier.at(r: Rect): Modifier = this
            .offset { IntOffset(r.left.toInt(), r.top.toInt()) }
            .size(with(density) { r.width.toDp() }, with(density) { r.height.toDp() })
        fun circle(c: Offset, r: Float) = Rect(c.x - r, c.y - r, c.x + r, c.y + r)

        // The LCD itself.
        LcdView(shell, g.scale, Modifier.at(Rect(g.lcdX, g.lcdY, g.lcdX + g.lcdW, g.lcdY + g.lcdH)))

        // Silver button: Menu on a press, out of Riot mode on a hold.
        PowerTouch(shell, Modifier.at(circle(g.powerC, g.powerR * 2.4f)), pressed, onPower)
        // Four-way pad.
        PadTouch(shell, Modifier.at(circle(g.padC, g.padR * 1.08f)), g.padR * 1.08f, pressed)
        KeyTouch(shell, Key.SELECT, "select", Modifier.at(circle(g.selectC, g.keyR * 2.0f)), pressed)
        KeyTouch(shell, Key.BACK, "back", Modifier.at(circle(g.backC, g.keyR * 2.0f)), pressed)
        WheelTouch(shell, Modifier.at(Rect(g.wheelC.x - g.wheelR * 1.25f, g.wheelC.y - g.wheelR * 1.45f,
            minOf(wPx, g.wheelC.x + g.wheelR * 1.25f), g.wheelC.y + g.wheelR * 1.45f)), g.wheelR, spin)
    }
}

// ---- drawing ------------------------------------------------------------------------------------

private fun DrawScope.drawBody(g: BodyGeom, pressed: Map<String, Boolean>, spin: Float) {
    val body = g.bodyPath()
    drawPath(body, Brush.verticalGradient(listOf(BodyTop, BodyMid, BodyLow), startY = g.top, endY = g.bottom))
    // Soft sheen along the upper half, as on the matte plastic in the photo.
    clipPath(body) {
        drawOval(Brush.radialGradient(listOf(BodyShine, Color.Transparent),
            center = Offset(g.w * 0.5f, g.top), radius = g.w * 0.55f),
            topLeft = Offset(-g.w * 0.05f, g.top - g.h * 0.5f), size = Size(g.w * 1.1f, g.h * 0.9f))
    }
    drawPath(body, BodyEdge, style = Stroke(width = g.h * 0.006f))

    // LCD bezel: a raised gray frame, a dark recess, then the glass.
    val f = g.frame
    val outer = Rect(f.left - g.h * 0.012f, f.top - g.h * 0.012f, f.right + g.h * 0.012f, f.bottom + g.h * 0.012f)
    drawRoundRect(Brush.verticalGradient(listOf(Color(0xFFE3E6E9), Color(0xFFA4AAB1), Color(0xFF7E848B)), outer.top, outer.bottom),
        outer.topLeft, outer.size, CornerRadius(g.h * 0.05f))
    drawRoundRect(Recess, f.topLeft, f.size, CornerRadius(g.h * 0.04f))
    // Green backlight bleeding round the edge of the glass.
    val glow = g.h * 0.012f
    drawRoundRect(Color(0x5596C882), Offset(g.lcdX - glow, g.lcdY - glow), Size(g.lcdW + glow * 2, g.lcdH + glow * 2), CornerRadius(glow))

    // Silver button.
    val sp = pressed["power"] == true
    drawCircle(Recess, g.powerR * 1.18f, g.powerC)
    drawCircle(Brush.radialGradient(
        if (sp) listOf(Color(0xFF9EA4AA), Color(0xFF6E747B)) else listOf(Color(0xFFF2F4F6), Color(0xFFA9AFB6), Color(0xFF7C8289)),
        center = g.powerC + Offset(-g.powerR * 0.3f, -g.powerR * 0.35f), radius = g.powerR * 1.4f), g.powerR, g.powerC)

    drawPad(g, pressed)
    roundKey(g.selectC, g.keyR, pressed["select"] == true) { c, r, col -> selectGlyph(c, r, col) }
    roundKey(g.backC, g.keyR, pressed["back"] == true) { c, r, col -> backGlyph(c, r, col) }
    drawWheel(g, spin)
    drawRioMark(g)
}

private fun DrawScope.printText(s: String, cx: Float, baseline: Float, size: Float, color: Color, bold: Boolean = true) {
    drawIntoCanvas { cv ->
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = android.graphics.Color.argb((color.alpha * 255).toInt(), (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt())
            textSize = size
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
            letterSpacing = 0.08f
        }
        cv.nativeCanvas.drawText(s, cx, baseline, p)
    }
}

/** The round four-way pad: Play/Pause top, Stop bottom, Backward left, Forward right. */
private fun DrawScope.drawPad(g: BodyGeom, pressed: Map<String, Boolean>) {
    val c = g.padC; val r = g.padR
    drawCircle(Recess, r * 1.07f, c)
    drawCircle(Brush.radialGradient(listOf(PadRim, PadFace), center = c + Offset(0f, -r * 0.4f), radius = r * 1.3f), r, c)
    // Shallow cross dividing the four zones.
    val groove = Color(0x33000000)
    for (a in listOf(45f, 135f)) {
        val rad = Math.toRadians(a.toDouble())
        val dx = (cos(rad) * r * 0.95f).toFloat(); val dy = (sin(rad) * r * 0.95f).toFloat()
        drawLine(groove, c + Offset(-dx, -dy), c + Offset(dx, dy), strokeWidth = r * 0.025f)
    }
    val press = mapOf("play" to Offset(0f, -1f), "stop" to Offset(0f, 1f), "rew" to Offset(-1f, 0f), "fwd" to Offset(1f, 0f))
    for ((k, d) in press) if (pressed[k] == true) {
        drawArc(Color(0x40000000), startAngle = when (k) { "play" -> 225f; "fwd" -> 315f; "stop" -> 45f; else -> 135f },
            sweepAngle = 90f, useCenter = true, topLeft = c - Offset(r, r), size = Size(r * 2, r * 2))
    }
    val gr = r * 0.13f
    padGlyph(c + Offset(0f, -r * 0.66f), gr, "play", pressed["play"] == true)
    padGlyph(c + Offset(0f, r * 0.66f), gr, "stop", pressed["stop"] == true)
    padGlyph(c + Offset(-r * 0.66f, 0f), gr, "rew", pressed["rew"] == true)
    padGlyph(c + Offset(r * 0.66f, 0f), gr, "fwd", pressed["fwd"] == true)
    // The middle is a plain dome, not a key.
    drawCircle(Brush.radialGradient(listOf(Color(0xFF4A5058), PadFace), center = c + Offset(0f, -r * 0.1f), radius = r * 0.3f), r * 0.26f, c)
}

private fun DrawScope.padGlyph(c: Offset, s: Float, kind: String, down: Boolean) {
    val col = if (down) PrintDim else Print
    fun tri(x0: Float, dir: Float) = Path().apply {
        moveTo(x0, c.y - s); lineTo(x0, c.y + s); lineTo(x0 + dir * s * 1.3f, c.y); close()
    }
    when (kind) {
        "play" -> {
            drawPath(tri(c.x - s * 1.25f, 1f), col)
            drawRect(col, Offset(c.x + s * 0.35f, c.y - s), Size(s * 0.38f, s * 2))
            drawRect(col, Offset(c.x + s * 1.0f, c.y - s), Size(s * 0.38f, s * 2))
        }
        "stop" -> drawRect(col, Offset(c.x - s * 0.85f, c.y - s * 0.85f), Size(s * 1.7f, s * 1.7f))
        "fwd" -> {
            drawPath(tri(c.x - s * 1.3f, 1f), col); drawPath(tri(c.x - s * 0.1f, 1f), col)
            drawRect(col, Offset(c.x + s * 1.2f, c.y - s), Size(s * 0.35f, s * 2))
        }
        "rew" -> {
            drawPath(tri(c.x + s * 1.3f, -1f), col); drawPath(tri(c.x + s * 0.1f, -1f), col)
            drawRect(col, Offset(c.x - s * 1.55f, c.y - s), Size(s * 0.35f, s * 2))
        }
    }
}

private fun DrawScope.roundKey(c: Offset, r: Float, down: Boolean, glyph: DrawScope.(Offset, Float, Color) -> Unit) {
    drawCircle(Recess, r * 1.16f, c)
    drawCircle(Brush.radialGradient(
        if (down) listOf(Color(0xFF3A3F46), Color(0xFF2E3238)) else listOf(Color(0xFF666C75), Color(0xFF3E434A)),
        center = c + Offset(-r * 0.25f, -r * 0.35f), radius = r * 1.4f), r, c)
    glyph(c, r, if (down) PrintDim else Print)
}

// Button legends from the guide: Select = dot in a ring, Back = an up arrow bent left.
private fun DrawScope.selectGlyph(c: Offset, r: Float, col: Color) {
    drawCircle(col, r * 0.42f, c, style = Stroke(width = r * 0.12f))
    drawCircle(col, r * 0.15f, c)
}

private fun DrawScope.backGlyph(c: Offset, r: Float, col: Color) {
    val s = r * 0.42f
    val p = Path().apply {
        moveTo(c.x + s * 0.7f, c.y + s * 0.9f)
        lineTo(c.x + s * 0.7f, c.y - s * 0.1f)
        quadraticTo(c.x + s * 0.7f, c.y - s * 0.55f, c.x + s * 0.2f, c.y - s * 0.55f)
        lineTo(c.x - s * 0.35f, c.y - s * 0.55f)
    }
    drawPath(p, col, style = Stroke(width = r * 0.12f))
    val head = Path().apply {
        moveTo(c.x - s * 0.95f, c.y - s * 0.55f); lineTo(c.x - s * 0.3f, c.y - s * 1.0f); lineTo(c.x - s * 0.3f, c.y - s * 0.1f); close()
    }
    drawPath(head, col)
}

/**
 * The thumb wheel: a dark disc set into the right side so only a crescent of its face shows,
 * with a ring of dimples near the rim that turns as you drag.
 */
private fun DrawScope.drawWheel(g: BodyGeom, spin: Float) {
    val c = g.wheelC; val r = g.wheelR
    val disc = Path().apply { addOval(Rect(c.x - r, c.y - r, c.x + r, c.y + r)) }
    val cover = Path().apply { addOval(Rect(c.x - r * 2.15f, c.y - r * 1.25f, c.x + r * 0.3f, c.y + r * 1.25f)) }
    val slot = Path().apply { op(disc, cover, PathOperation.Difference) }
    // The slot's shadow, a little bigger than the opening.
    val shadow = Path().apply {
        addOval(Rect(c.x - r * 1.06f, c.y - r * 1.06f, c.x + r * 1.06f, c.y + r * 1.06f))
    }
    val coverIn = Path().apply { addOval(Rect(c.x - r * 2.1f, c.y - r * 1.2f, c.x + r * 0.24f, c.y + r * 1.2f)) }
    drawPath(Path().apply { op(shadow, coverIn, PathOperation.Difference) }, Recess)
    clipPath(slot) {
        drawCircle(Brush.radialGradient(listOf(Color(0xFF3E434A), Color(0xFF24272C)), center = c + Offset(r * 0.3f, -r * 0.3f), radius = r * 1.3f), r, c)
        val n = 28
        val turn = spin / (r * 0.86f)
        for (i in 0 until n) {
            val a = 2 * PI.toFloat() * i / n + turn
            val p = c + Offset(cos(a) * r * 0.86f, sin(a) * r * 0.86f)
            drawCircle(Color(0xFF16181C), r * 0.045f, p)
            drawCircle(Color(0x30FFFFFF), r * 0.045f, p + Offset(r * 0.01f, r * 0.012f), style = Stroke(width = 1.5f))
        }
        drawCircle(Color(0x22000000), r * 0.6f, c, style = Stroke(width = r * 0.04f))
    }
    // The lip of the case over the left edge of the disc.
    val lip = Path().apply { op(Path().apply { addOval(Rect(c.x - r * 2.15f, c.y - r * 1.25f, c.x + r * 0.3f, c.y + r * 1.25f)) },
        Path().apply { addOval(Rect(c.x - r * 2.15f, c.y - r * 1.25f, c.x + r * 0.27f, c.y + r * 1.25f)) }, PathOperation.Difference) }
    clipPath(Path().apply { addOval(Rect(c.x - r * 1.06f, c.y - r * 1.06f, c.x + r * 1.06f, c.y + r * 1.06f)) }) {
        drawPath(lip, Color(0x40FFFFFF))
    }
}

/** "Rio" printed top right: heavy rounded letters with the bar over the o, in one light ink. */
private fun DrawScope.drawRioMark(g: BodyGeom) {
    val size = g.h * 0.05f
    drawIntoCanvas { cv ->
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(0xB9, 0xBE, 0xC5)
            textSize = size
            textAlign = Paint.Align.LEFT
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        }
        val wRi = p.measureText("Ri")
        val wo = p.measureText("o")
        val x0 = g.logoC.x - (wRi + wo) / 2f
        cv.nativeCanvas.drawText("Ri", x0, g.logoC.y, p)
        cv.nativeCanvas.drawText("o", x0 + wRi, g.logoC.y, p)
        // The macron over the o.
        val barY = g.logoC.y - size * 0.68f
        cv.nativeCanvas.drawRect(x0 + wRi + wo * 0.12f, barY, x0 + wRi + wo * 0.88f, barY + size * 0.11f, p)
    }
}

// ---- the LCD --------------------------------------------------------------------------------------

@Composable
private fun LcdView(shell: RiotShell, scale: Int, modifier: Modifier) {
    val bmp = remember { Bitmap.createBitmap(Lcd.W, Lcd.H, Bitmap.Config.ARGB_8888) }
    val img = remember(bmp) { bmp.asImageBitmap() }
    val buf = remember { IntArray(Lcd.W * Lcd.H) }
    Canvas(modifier.pointerInput(Unit) {
        // Touching the glass only lights the backlight, the way any key did.
        awaitEachGesture { awaitFirstDown(); shell.touchLcd() }
    }) {
        @Suppress("UNUSED_VARIABLE") val f = shell.frame.intValue
        val pal = lcdPalette(shell.lit, shell.prefs.contrast, shell.sleeping)
        val px = shell.lcd.px
        for (i in buf.indices) buf[i] = pal[px[i].toInt()]
        bmp.setPixels(buf, 0, Lcd.W, 0, 0, Lcd.W, Lcd.H)
        drawImage(img, dstOffset = IntOffset.Zero, dstSize = IntSize(Lcd.W * scale, Lcd.H * scale), filterQuality = FilterQuality.None)
        // A faint grid between pixels: the dot-matrix texture of a 2002 panel up close.
        if (scale >= 3) {
            val grid = Color(0x14000000)
            for (x in 1 until Lcd.W) drawLine(grid, Offset(x * scale.toFloat(), 0f), Offset(x * scale.toFloat(), size.height), 1f)
            for (y in 1 until Lcd.H) drawLine(grid, Offset(0f, y * scale.toFloat()), Offset(size.width, y * scale.toFloat()), 1f)
        }
    }
}

// ---- touch ----------------------------------------------------------------------------------------

/** A key that reports down and up separately, so the shell can tell a tap from a hold. */
@Composable
private fun KeyTouch(shell: RiotShell, key: Key, name: String, modifier: Modifier, pressed: MutableMap<String, Boolean>) {
    Box(modifier.pointerInput(key) {
        awaitEachGesture {
            awaitFirstDown()
            pressed[name] = true
            shell.buttonFeedback()
            shell.keyDown(key)
            waitForUpOrCancellation()
            pressed[name] = false
            shell.keyUp(key)
        }
    })
}

/**
 * The silver button, which is the Riot's Menu key (top of the left column on the board). A
 * press is Menu. Held for [POWER_HOLD_MS] it leaves Riot mode, like holding a power button.
 */
@Composable
private fun PowerTouch(shell: RiotShell, modifier: Modifier, pressed: MutableMap<String, Boolean>, onPower: () -> Unit) {
    val scope = rememberCoroutineScope()
    Box(modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown()
            pressed["power"] = true
            shell.buttonFeedback()
            var fired = false
            val timer = scope.launch {
                delay(POWER_HOLD_MS)
                fired = true
                shell.buttonFeedback(strong = true)
                pressed["power"] = false
                onPower()
            }
            val up = waitForUpOrCancellation()
            timer.cancel()
            pressed["power"] = false
            if (!fired && up != null) { shell.keyDown(Key.MENU); shell.keyUp(Key.MENU) }
        }
    })
}

private const val POWER_HOLD_MS = 1200L

/** The four-way pad: the quarter under the finger picks the key. */
@Composable
private fun PadTouch(shell: RiotShell, modifier: Modifier, r: Float, pressed: MutableMap<String, Boolean>) {
    Box(modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            val dx = down.position.x - r; val dy = down.position.y - r
            if (hypot(dx, dy) > r) return@awaitEachGesture
            val a = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
            val (key, name) = when {
                a >= -45 && a < 45 -> Key.FWD to "fwd"
                a >= 45 && a < 135 -> Key.STOP to "stop"
                a >= -135 && a < -45 -> Key.PLAY to "play"
                else -> Key.REW to "rew"
            }
            pressed[name] = true
            shell.buttonFeedback()
            shell.keyDown(key)
            waitForUpOrCancellation()
            pressed[name] = false
            shell.keyUp(key)
        }
    })
}

/**
 * The thumb wheel. Drag up or down; each detent is one Scroll step with a short tick. A flick
 * keeps it turning for a moment, slowing down, like a free-spinning wheel.
 */
@Composable
private fun WheelTouch(shell: RiotShell, modifier: Modifier, r: Float, spin: androidx.compose.runtime.MutableFloatState) {
    val scope = rememberCoroutineScope()
    // One detent every two dimples.
    val step = r * 0.86f * 2f * PI.toFloat() / 14f
    val acc = remember { floatArrayOf(0f) }
    val fling = remember { arrayOfNulls<Job>(1) }
    fun feed(dy: Float) {
        spin.floatValue += dy
        acc[0] += dy
        while (acc[0] >= step) { shell.wheel(1); acc[0] -= step }
        while (acc[0] <= -step) { shell.wheel(-1); acc[0] += step }
    }
    Box(modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            fling[0]?.cancel()
            val vt = VelocityTracker()
            vt.addPosition(down.uptimeMillis, down.position)
            while (true) {
                val ev = awaitPointerEvent()
                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                vt.addPosition(ch.uptimeMillis, ch.position)
                if (!ch.pressed) break
                val d = ch.positionChange().y
                if (d != 0f) { ch.consume(); feed(d) }
            }
            var v = vt.calculateVelocity().y
            if (abs(v) > 600f) {
                fling[0] = scope.launch {
                    var last = System.nanoTime()
                    while (abs(v) > 150f) {
                        delay(16)
                        val now = System.nanoTime()
                        val dt = (now - last) / 1e9f; last = now
                        feed(v * dt)
                        v *= 0.90f
                    }
                }
            }
        }
    })
}
