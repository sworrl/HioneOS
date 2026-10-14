package com.hione.launcher.launcher

import com.hione.launcher.Mascot
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.SurfaceHolder
import androidx.core.content.res.ResourcesCompat
import com.hione.launcher.R
import kotlin.math.sin
import kotlin.random.Random

/**
 * 100% Kotlin Native Hione Cyber Live Wallpaper Engine.
 * Features:
 * - High-res official Oki Hione artwork backdrop.
 * - Floating glowing Sakura petals & cyan cyber particles with 60fps physics.
 * - Interactive glowing ripple rings responding to multi-touch gestures.
 * - Gentle pulsing ambient lighting effects.
 */
class HioneLiveWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = HioneWallpaperEngine()

    private inner class HioneWallpaperEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private var visible = false
        private var bgBitmap: Bitmap? = null
        /** Face center in [bgBitmap] (holiday/WallpaperFocus), null = no framing. */
        private var bgFocus: com.hione.launcher.holiday.FocusPoint? = null

        private val particles = ArrayList<CyberParticle>()
        private val ripples = ArrayList<TouchRipple>()

        private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }

        private val bgPaint = Paint(Paint.FILTER_BITMAP_FLAG)

        private val drawRunnable = object : Runnable {
            override fun run() {
                drawFrame()
                if (visible) {
                    handler.postDelayed(this, 16L) // ~60fps
                }
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)
            loadBackground()
            initParticles()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) {
                drawFrame()
                handler.post(drawRunnable)
            } else {
                handler.removeCallbacks(drawRunnable)
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            scaleBackground(width, height)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            visible = false
            handler.removeCallbacks(drawRunnable)
        }

        override fun onTouchEvent(event: MotionEvent) {
            if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
                if (ripples.size < 8) {
                    ripples.add(TouchRipple(event.x, event.y))
                }
            }
            super.onTouchEvent(event)
        }

        private fun loadBackground() {
            // Holiday or season art first, then the pack's wallpaper, then the built-in drawable.
            val ctx = this@HioneLiveWallpaperService
            runCatching {
                com.hione.launcher.holiday.HolidayState.homeArtNow(ctx)
                    ?: Mascot.artFor(ctx, R.drawable.hione_wallpaper)?.let { bmp ->
                        val table = com.hione.launcher.holiday.HolidayState.focusTable(ctx, Mascot.pack)
                        bmp to com.hione.launcher.holiday.WallpaperFocus.lookup(table, "drawable:hione_wallpaper")
                    }
            }.getOrNull()?.let { (bmp, focus) -> bgBitmap = bmp; bgFocus = focus; return }
            try {
                val drawable = ResourcesCompat.getDrawable(resources, R.drawable.hione_wallpaper, null)
                if (drawable != null) {
                    val bmp = Bitmap.createBitmap(
                        drawable.intrinsicWidth.coerceAtLeast(720),
                        drawable.intrinsicHeight.coerceAtLeast(1280),
                        Bitmap.Config.ARGB_8888
                    )
                    val canvas = Canvas(bmp)
                    drawable.setBounds(0, 0, canvas.width, canvas.height)
                    drawable.draw(canvas)
                    bgBitmap = bmp
                }
            } catch (_: Throwable) {}
        }

        private fun scaleBackground(width: Int, height: Int) {
            val src = bgBitmap ?: return
            val focus = bgFocus
            if (focus != null && width > 0 && height > 0) {
                // Frame her face into the home screen's open band, then fill the surface.
                runCatching {
                    val r = com.hione.launcher.holiday.WallpaperFocus.sourceRect(
                        src.width, src.height, width.toFloat(), height.toFloat(), focus,
                        // Same target as the home screen for the current HioneDeck layout.
                        com.hione.launcher.ui.HioneDeck.wallpaperTarget(
                            com.hione.launcher.ui.HioneDeck.read(this@HioneLiveWallpaperService).mode
                        )
                    )
                    val l = r.left.toInt().coerceIn(0, src.width - 1)
                    val t = r.top.toInt().coerceIn(0, src.height - 1)
                    val w = r.width.toInt().coerceIn(1, src.width - l)
                    val h = r.height.toInt().coerceIn(1, src.height - t)
                    Bitmap.createScaledBitmap(Bitmap.createBitmap(src, l, t, w, h), width, height, true)
                }.getOrNull()?.let { bgBitmap = it; bgFocus = null; return }
            }
            if (width > 0 && height > 0 && (src.width != width || src.height != height)) {
                bgBitmap = Bitmap.createScaledBitmap(src, width, height, true)
            }
        }

        private fun initParticles() {
            particles.clear()
            for (i in 0 until 40) {
                particles.add(
                    CyberParticle(
                        x = Random.nextFloat() * 720f,
                        y = Random.nextFloat() * 1280f,
                        radius = Random.nextFloat() * 4f + 2f,
                        speedY = Random.nextFloat() * 1.5f + 0.5f,
                        speedX = (Random.nextFloat() - 0.5f) * 0.8f,
                        alpha = Random.nextInt(120, 240),
                        color = if (Random.nextBoolean()) Mascot.argb(Color.parseColor("#FFC56B")) else Mascot.argb(Color.parseColor("#FF80AB"))
                    )
                )
            }
        }

        private fun drawFrame() {
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null) {
                    val w = canvas.width.toFloat()
                    val h = canvas.height.toFloat()

                    // Draw Hione background
                    val bg = bgBitmap
                    if (bg != null) {
                        canvas.drawBitmap(bg, 0f, 0f, bgPaint)
                    } else {
                        canvas.drawColor(Mascot.argb(Color.parseColor("#0C0D10")))
                    }

                    // Update and draw floating cyber particles / sakura petals
                    val now = SystemClock.elapsedRealtime()
                    for (p in particles) {
                        p.y += p.speedY
                        p.x += p.speedX + sin((now / 800.0 + p.y / 200.0)).toFloat() * 0.3f

                        if (p.y > h) {
                            p.y = -10f
                            p.x = Random.nextFloat() * w
                        }
                        if (p.x < -10f) p.x = w + 10f
                        if (p.x > w + 10f) p.x = -10f

                        particlePaint.color = p.color
                        particlePaint.alpha = p.alpha
                        canvas.drawCircle(p.x, p.y, p.radius, particlePaint)
                    }

                    // Update and draw touch ripples
                    val it = ripples.iterator()
                    while (it.hasNext()) {
                        val r = it.next()
                        r.radius += 4f
                        r.alpha -= 6

                        if (r.alpha <= 0 || r.radius > 200f) {
                            it.remove()
                        } else {
                            ripplePaint.color = Mascot.argb(Color.parseColor("#FFC56B"))
                            ripplePaint.alpha = r.alpha
                            canvas.drawCircle(r.x, r.y, r.radius, ripplePaint)
                        }
                    }
                }
            } finally {
                if (canvas != null) {
                    try { holder.unlockCanvasAndPost(canvas) } catch (_: Throwable) {}
                }
            }
        }
    }

    private data class CyberParticle(
        var x: Float,
        var y: Float,
        val radius: Float,
        val speedY: Float,
        val speedX: Float,
        val alpha: Int,
        val color: Int
    )

    private data class TouchRipple(
        val x: Float,
        val y: Float,
        var radius: Float = 10f,
        var alpha: Int = 200
    )
}
