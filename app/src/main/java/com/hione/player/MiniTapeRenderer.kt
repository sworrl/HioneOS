package com.hione.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Renders a lightweight, spec-exact Compact Cassette tape onto a Bitmap for the lockscreen/home widget.
 * Stripped of visualizer background to minimize system I/O and CPU overhead.
 */
object MiniTapeRenderer {

    private const val SHELL_W_MM = 101.6f
    private const val SHELL_H_MM = 63.5f
    private const val HUB_L_X_MM = 29.55f
    private const val HUB_R_X_MM = 72.05f
    private const val HUB_Y_MM = 31.75f
    private const val HUB_R_MM = 10.9f
    private const val PACK_MIN_R_MM = 11.5f
    private const val PACK_MAX_R_MM = 24.5f
    private const val WINDOW_W_MM = 44.0f
    private const val WINDOW_H_MM = 14.0f
    private const val GUIDE_L_X_MM = 8.0f
    private const val GUIDE_R_X_MM = SHELL_W_MM - 8.0f
    private const val GUIDE_Y_MM = 56.4f
    private const val GUIDE_ROLLER_R_MM = 2.6f
    private const val TAPE_RUN_Y_MM = 59.3f

    fun renderMiniTape(
        context: Context,
        width: Int = 640,
        height: Int = 400,
        title: String,
        artist: String,
        album: String,
        progress: Float,
        isPlaying: Boolean,
        albumArtBmp: Bitmap? = null
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val s = width / SHELL_W_MM
        val shellH = SHELL_H_MM * s
        val offsetY = (height - shellH) / 2f

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // 0) NO opaque background fill — the bitmap starts fully transparent (ARGB_8888 default)
        // and stays that way outside the shell's own rounded silhouette. This is "JUST a cassette"
        // floating on whatever's behind the widget (lockscreen wallpaper, home screen), not a dark
        // rectangle with a cassette drawn inside it.

        // 1) Cassette Shell Body (Precision Smoke Black)
        val shellRect = RectF(0f, offsetY, width.toFloat(), offsetY + shellH)
        val cornerR = 3.5f * s

        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(
            0f, offsetY, width.toFloat(), offsetY + shellH,
            Color.parseColor("#26262B"), Color.parseColor("#141417"),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(shellRect, cornerR, cornerR, paint)
        paint.shader = null

        // Outer Bevel Highlight & Shadow
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.8f * s
        paint.color = Color.parseColor("#4A4A54")
        canvas.drawRoundRect(shellRect, cornerR, cornerR, paint)

        val innerRect = RectF(shellRect)
        innerRect.inset(1.5f * s, 1.5f * s)
        paint.color = Color.parseColor("#0B0B0E")
        paint.strokeWidth = 0.5f * s
        canvas.drawRoundRect(innerRect, cornerR, cornerR, paint)

        // 2) Printed Label Band (Top section)
        val labelRect = RectF(3f * s, offsetY + 3f * s, (SHELL_W_MM - 3f) * s, offsetY + 18f * s)
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#F4F4EF")
        canvas.drawRoundRect(labelRect, 1.5f * s, 1.5f * s, paint)

        // Teal Accent Grade Slash on Label
        val slashPath = Path().apply {
            moveTo(labelRect.left + 55f * s, labelRect.top)
            lineTo(labelRect.left + 61f * s, labelRect.top)
            lineTo(labelRect.left + 67f * s, labelRect.bottom)
            lineTo(labelRect.left + 61f * s, labelRect.bottom)
            close()
        }
        paint.color = Mascot.argb(Color.parseColor("#FFAB2E"))
        canvas.drawPath(slashPath, paint)

        // Track Title & Artist Text on Label
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#141418")
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textSize = 3.6f * s
        val displayTitle = title.take(28)
        canvas.drawText(displayTitle, labelRect.left + 2.5f * s, labelRect.top + 5.2f * s, paint)

        paint.color = Color.parseColor("#5A5A62")
        paint.typeface = Typeface.DEFAULT
        paint.textSize = 2.6f * s
        val displayArtist = if (artist.isNotBlank()) artist.take(32) else "${Mascot.musicAppName}"
        canvas.drawText(displayArtist, labelRect.left + 2.5f * s, labelRect.top + 9.5f * s, paint)

        paint.color = Color.parseColor("#141418")
        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        paint.textSize = 2.2f * s
        canvas.drawText("MM-90 · HIGH BIAS 70µs", labelRect.left + 2.5f * s, labelRect.top + 13.6f * s, paint)

        // 3) Center Viewing Window & Album Art
        val winW = WINDOW_W_MM * s
        val winH = WINDOW_H_MM * s
        val winCX = 50.8f * s
        val winCY = offsetY + HUB_Y_MM * s
        val winRect = RectF(winCX - winW / 2f, winCY - winH / 2f, winCX + winW / 2f, winCY + winH / 2f)

        // Window Frame Outline
        paint.style = Paint.Style.FILL
        paint.color = Mascot.argb(Color.parseColor("#0F1416"))
        canvas.drawRoundRect(winRect, 2.5f * s, 2.5f * s, paint)

        // Album Art inside Window (stripped viz BG for light IO footprint!)
        canvas.save()
        val windowClipPath = Path().apply { addRoundRect(winRect, 2f * s, 2f * s, Path.Direction.CW) }
        canvas.clipPath(windowClipPath)

        if (albumArtBmp != null && !albumArtBmp.isRecycled) {
            val artRect = RectF(winRect.left, winRect.top - (winW - winH) / 2f, winRect.right, winRect.bottom + (winW - winH) / 2f)
            canvas.drawBitmap(albumArtBmp, null, artRect, paint)
            // Soft dark tint over art so reels and window read clearly
            paint.color = Color.parseColor("#33000000")
            canvas.drawRect(winRect, paint)
        } else {
            paint.color = Mascot.argb(Color.parseColor("#0C2B2E"))
            canvas.drawRect(winRect, paint)
        }
        canvas.restore()

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.6f * s
        paint.color = Mascot.argb(Color.parseColor("#FFD08A"))
        canvas.drawRoundRect(winRect, 2.5f * s, 2.5f * s, paint)

        // 4) Reels & Wound Tape Packs
        val clX = HUB_L_X_MM * s
        val clY = offsetY + HUB_Y_MM * s
        val crX = HUB_R_X_MM * s
        val crY = offsetY + HUB_Y_MM * s

        val clampedProgress = progress.coerceIn(0f, 1f)
        val leftR = sqrt(PACK_MIN_R_MM * PACK_MIN_R_MM + (PACK_MAX_R_MM * PACK_MAX_R_MM - PACK_MIN_R_MM * PACK_MIN_R_MM) * (1f - clampedProgress)) * s
        val rightR = sqrt(PACK_MIN_R_MM * PACK_MIN_R_MM + (PACK_MAX_R_MM * PACK_MAX_R_MM - PACK_MIN_R_MM * PACK_MIN_R_MM) * clampedProgress) * s

        // Supply Pack (Left)
        drawReelPack(canvas, clX, clY, leftR, s, paint)
        // Take-up Pack (Right)
        drawReelPack(canvas, crX, crY, rightR, s, paint)

        // 5) Tape Strand (Off supply pack -> corner guide -> bottom run -> corner guide -> take-up pack)
        drawTapeStrand(canvas, clX, clY, leftR, crX, crY, rightR, offsetY, s, paint)

        // 6) Hub Faces & Drive Teeth
        drawHubFace(canvas, clX, clY, HUB_R_MM * s, s, paint)
        drawHubFace(canvas, crX, crY, HUB_R_MM * s, s, paint)

        // 7) Bottom Plate (Head & Pinch Roller Mechanism)
        val platePath = Path().apply {
            moveTo(17.0f * s, offsetY + SHELL_H_MM * s)
            lineTo(19.8f * s, offsetY + 50.6f * s)
            lineTo(81.8f * s, offsetY + 50.6f * s)
            lineTo(84.6f * s, offsetY + SHELL_H_MM * s)
            close()
        }
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#1C1C22")
        canvas.drawPath(platePath, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.4f * s
        paint.color = Color.parseColor("#383842")
        canvas.drawPath(platePath, paint)

        // Head opening cutout
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#0A0A0A")
        val headRect = RectF(45.3f * s, offsetY + 59.6f * s, 56.3f * s, offsetY + SHELL_H_MM * s)
        canvas.drawRoundRect(headRect, 0.8f * s, 0.8f * s, paint)

        // Pinch roller cutouts
        val rollerL = RectF(34.6f * s, offsetY + 60.2f * s, 43.1f * s, offsetY + SHELL_H_MM * s)
        val rollerR = RectF(58.5f * s, offsetY + 60.2f * s, 67.0f * s, offsetY + SHELL_H_MM * s)
        canvas.drawRoundRect(rollerL, 0.8f * s, 0.8f * s, paint)
        canvas.drawRoundRect(rollerR, 0.8f * s, 0.8f * s, paint)

        // Corner Screws
        drawScrew(canvas, 4.3f * s, offsetY + 4.3f * s, s, paint)
        drawScrew(canvas, (SHELL_W_MM - 4.3f) * s, offsetY + 4.3f * s, s, paint)
        drawScrew(canvas, 4.3f * s, offsetY + 59.2f * s, s, paint)
        drawScrew(canvas, (SHELL_W_MM - 4.3f) * s, offsetY + 59.2f * s, s, paint)

        // 8) Transport glyphs — drawn INTO the cassette artwork itself (bold Hione teal, backlit
        // glow) instead of a separate floating Android button row on top of it, so the widget
        // reads as "just a cassette" that happens to be touch-reactive, not a cassette-shaped
        // background behind a normal button bar. Positioned to match the three tap-target columns
        // in widget_tape.xml (weights 1 : 1.4 : 1 across the full width) — prev center ≈14.9mm,
        // toggle center = shell's own horizontal center (50.8mm), next center ≈86.7mm — sitting in
        // the bottom mechanism-plate band, clear of the label/reels/window above.
        val glyphY = offsetY + 57f * s
        drawPrevGlyph(canvas, 14.9f * s, glyphY, 5.2f * s, s, paint)
        drawToggleGlyph(canvas, 50.8f * s, glyphY, 6.4f * s, isPlaying, s, paint)
        drawNextGlyph(canvas, 86.7f * s, glyphY, 5.2f * s, s, paint)

        return bitmap
    }

    /** Backlit glow behind a glyph — a soft low-alpha teal disc, then the crisp glyph on top. Cheap
     *  (two/three draw calls), reused by all three transport glyphs for a consistent "lit control"
     *  look against the dark mechanism plate. */
    private fun glowBacking(canvas: Canvas, cx: Float, cy: Float, r: Float, paint: Paint) {
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            cx, cy, r * 1.6f,
            Mascot.argb(Color.parseColor("#44FFAB2E")), Mascot.argb(Color.parseColor("#00FFAB2E")),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r * 1.6f, paint)
        paint.shader = null
    }

    private fun drawPrevGlyph(canvas: Canvas, cx: Float, cy: Float, r: Float, s: Float, paint: Paint) {
        glowBacking(canvas, cx, cy, r, paint)
        paint.style = Paint.Style.FILL
        paint.color = Mascot.argb(Color.parseColor("#FFD08A"))
        for (i in 0 until 2) {
            val ox = cx - r * 0.55f + i * r * 0.85f
            val tri = Path().apply {
                moveTo(ox + r * 0.55f, cy - r * 0.7f)
                lineTo(ox - r * 0.55f, cy)
                lineTo(ox + r * 0.55f, cy + r * 0.7f)
                close()
            }
            canvas.drawPath(tri, paint)
        }
    }

    private fun drawNextGlyph(canvas: Canvas, cx: Float, cy: Float, r: Float, s: Float, paint: Paint) {
        glowBacking(canvas, cx, cy, r, paint)
        paint.style = Paint.Style.FILL
        paint.color = Mascot.argb(Color.parseColor("#FFD08A"))
        for (i in 0 until 2) {
            val ox = cx + r * 0.55f - i * r * 0.85f
            val tri = Path().apply {
                moveTo(ox - r * 0.55f, cy - r * 0.7f)
                lineTo(ox + r * 0.55f, cy)
                lineTo(ox - r * 0.55f, cy + r * 0.7f)
                close()
            }
            canvas.drawPath(tri, paint)
        }
    }

    private fun drawToggleGlyph(canvas: Canvas, cx: Float, cy: Float, r: Float, isPlaying: Boolean, s: Float, paint: Paint) {
        glowBacking(canvas, cx, cy, r, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#FFFFFF")
        if (isPlaying) {
            val barW = r * 0.42f
            val barH = r * 1.3f
            canvas.drawRoundRect(RectF(cx - r * 0.55f, cy - barH / 2f, cx - r * 0.55f + barW, cy + barH / 2f), barW * 0.3f, barW * 0.3f, paint)
            canvas.drawRoundRect(RectF(cx + r * 0.13f, cy - barH / 2f, cx + r * 0.13f + barW, cy + barH / 2f), barW * 0.3f, barW * 0.3f, paint)
        } else {
            val tri = Path().apply {
                moveTo(cx - r * 0.5f, cy - r * 0.75f)
                lineTo(cx + r * 0.75f, cy)
                lineTo(cx - r * 0.5f, cy + r * 0.75f)
                close()
            }
            canvas.drawPath(tri, paint)
        }
    }

    private fun drawReelPack(canvas: Canvas, cx: Float, cy: Float, r: Float, s: Float, paint: Paint) {
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            cx - r * 0.3f, cy - r * 0.3f, r * 1.3f,
            Color.parseColor("#2A2016"), Color.parseColor("#120C07"),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, paint)
        paint.shader = null

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.3f * s
        paint.color = Color.parseColor("#0A0805")
        canvas.drawCircle(cx, cy, r, paint)
    }

    private fun drawHubFace(canvas: Canvas, cx: Float, cy: Float, hubR: Float, s: Float, paint: Paint) {
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#E8E8EC")
        canvas.drawCircle(cx, cy, hubR, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.4f * s
        paint.color = Color.parseColor("#8A8A94")
        canvas.drawCircle(cx, cy, hubR, paint)

        // Center Spindle Hole (8.5 mm)
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#14100C")
        canvas.drawCircle(cx, cy, 4.25f * s, paint)

        // Splined drive teeth
        paint.color = Color.parseColor("#EBEFE0")
        for (i in 0 until 6) {
            val a = Math.toRadians((i * 60).toDouble())
            val tx = cx + (3.8f * s * cos(a)).toFloat()
            val ty = cy + (3.8f * s * sin(a)).toFloat()
            canvas.drawCircle(tx, ty, 0.7f * s, paint)
        }
    }

    private fun drawTapeStrand(
        canvas: Canvas,
        clX: Float, clY: Float, leftR: Float,
        crX: Float, crY: Float, rightR: Float,
        offsetY: Float, s: Float, paint: Paint
    ) {
        val gLx = GUIDE_L_X_MM * s
        val gRx = GUIDE_R_X_MM * s
        val gY = offsetY + GUIDE_Y_MM * s
        val rollerR = GUIDE_ROLLER_R_MM * s
        val tapeRunY = offsetY + TAPE_RUN_Y_MM * s

        // Tangent points on left & right reel packs
        val pL = calculateTangentPoint(clX, clY, gLx - rollerR, gY, leftR, outward = -1f)
        val pR = calculateTangentPoint(crX, crY, gRx + rollerR, gY, rightR, outward = 1f)

        val strandPath = Path().apply {
            moveTo(pL.x, pL.y)
            lineTo(gLx - rollerR, gY)
            quadTo(gLx - rollerR, tapeRunY, gLx, tapeRunY)
            lineTo(gRx, tapeRunY)
            quadTo(gRx + rollerR, tapeRunY, gRx + rollerR, gY)
            lineTo(pR.x, pR.y)
        }

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.85f * s
        paint.color = Color.parseColor("#E62A1B0E")
        canvas.drawPath(strandPath, paint)

        // Blending dots at tangent junctions so seam disappears
        paint.style = Paint.Style.FILL
        canvas.drawCircle(pL.x, pL.y, 1.2f * s, paint)
        canvas.drawCircle(pR.x, pR.y, 1.2f * s, paint)
    }

    private fun calculateTangentPoint(cx: Float, cy: Float, px: Float, py: Float, r: Float, outward: Float): PointF {
        val dx = px - cx; val dy = py - cy
        val d = sqrt(dx * dx + dy * dy)
        if (d <= r + 0.1f) return PointF(cx + dx / d * r, cy + dy / d * r)
        val base = atan2(dy.toDouble(), dx.toDouble())
        val phi = acos((r / d).toDouble())
        val p1 = PointF(cx + (r * cos(base + phi)).toFloat(), cy + (r * sin(base + phi)).toFloat())
        val p2 = PointF(cx + (r * cos(base - phi)).toFloat(), cy + (r * sin(base - phi)).toFloat())
        return if ((p1.x - cx) * outward > (p2.x - cx) * outward) p1 else p2
    }

    private class PointF(val x: Float, val y: Float)

    private fun drawScrew(canvas: Canvas, sx: Float, sy: Float, s: Float, paint: Paint) {
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#B8B8C2")
        canvas.drawCircle(sx, sy, 1.2f * s, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.25f * s
        paint.color = Color.parseColor("#17171B")
        canvas.drawCircle(sx, sy, 1.2f * s, paint)
        // Thread slot
        canvas.drawLine(sx - 0.7f * s, sy - 0.4f * s, sx + 0.7f * s, sy + 0.4f * s, paint)
    }
}
