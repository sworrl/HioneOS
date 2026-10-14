package com.hione.player.launcher

import com.hione.player.Mascot
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import com.hione.player.R
import kotlin.math.sin

/**
 * Hione "Cyber Hologram" live wallpaper. The manifest declared this service (+ thumbnail and
 * hione_wallpaper_info.xml) but the class was missing, so the wallpaper crashed in the picker
 * (lint MissingClass). Minimal, battery-aware renderer: the static hione_wallpaper art with a
 * slow parallax drift (following home-screen page offset) and a gentle teal glow pulse for the
 * hologram feel. Rendering runs ONLY while the surface is visible — no drain when asleep or
 * behind an opaque app.
 */
class HioneLiveWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = HologramEngine()

    private inner class HologramEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private var visible = false
        private var art: Bitmap? = null
        private var width = 0
        private var height = 0
        private var xOffset = 0.5f
        private var startMs = 0L

        private val basePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        // Additive teal wash whose alpha pulses — the "hologram" glow. Kept subtle so text on the
        // launcher home stays legible.
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = HIONE_TEAL
            xfermode = android.graphics.PorterDuffXfermode(PorterDuff.Mode.SCREEN)
        }

        private val frame = object : Runnable {
            override fun run() {
                draw()
                if (visible) handler.postDelayed(this, FRAME_MS)
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            startMs = android.os.SystemClock.uptimeMillis()
            art = runCatching {
                Mascot.decodeResource(resources, R.drawable.hione_wallpaper)
            }.getOrNull()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            super.onSurfaceChanged(holder, format, w, h)
            width = w; height = h
            draw()
        }

        override fun onVisibilityChanged(v: Boolean) {
            visible = v
            handler.removeCallbacks(frame)
            if (v) handler.post(frame)
        }

        override fun onOffsetsChanged(
            xOff: Float, yOff: Float, xStep: Float, yStep: Float, xPix: Int, yPix: Int
        ) {
            xOffset = xOff
            draw()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            visible = false
            handler.removeCallbacks(frame)
            art?.recycle(); art = null
        }

        private fun draw() {
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas() ?: return
                canvas.drawColor(Color.BLACK)
                val bmp = art
                if (bmp != null && width > 0 && height > 0) {
                    // Cover-crop the art, then shift horizontally by ±PARALLAX_PX with the page.
                    val scale = maxOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
                    val drawW = bmp.width * scale
                    val drawH = bmp.height * scale
                    val slack = (drawW - width).coerceAtLeast(0f)
                    val left = -slack * xOffset - (PARALLAX_PX * (xOffset - 0.5f))
                    val top = (height - drawH) / 2f
                    canvas.drawBitmap(bmp, null, RectF(left, top, left + drawW, top + drawH), basePaint)
                }
                // Glow pulse (period ~6s), alpha 0..GLOW_MAX_ALPHA.
                val t = (android.os.SystemClock.uptimeMillis() - startMs) / 1000.0
                val pulse = (0.5 + 0.5 * sin(t * (2 * Math.PI / GLOW_PERIOD_S))).toFloat()
                glowPaint.alpha = (pulse * GLOW_MAX_ALPHA).toInt()
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), glowPaint)
            } finally {
                if (canvas != null) runCatching { holder.unlockCanvasAndPost(canvas) }
            }
        }
    }

    private companion object {
        const val FRAME_MS = 33L          // ~30fps; ample for a slow pulse, light on battery
        const val PARALLAX_PX = 60f
        const val GLOW_PERIOD_S = 6.0
        const val GLOW_MAX_ALPHA = 36     // out of 255 — subtle
        val HIONE_TEAL get() = Mascot.argb(Color.parseColor("#FFAB2E"))
    }
}
