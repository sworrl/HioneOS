package com.hione.systemui

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import java.io.File
import kotlin.math.abs

/**
 * Now-Playing HUD: a Pixel-11-style compact card that slides in over ANY app when Hione Music
 * changes track (broadcast `com.hione.player.action.TRACK_CHANGED`), drawn in the accessibility
 * overlay layer so it needs no window permission and never takes focus.
 *
 *  · top of screen under the 20dp shade-pull strip, 16dp side margins, 72dp tall, 20dp corners
 *  · dark glass, glow in the album art's dominant colour, art thumb, title/artist marquee,
 *    quality chip, ♥ when liked, progress hairline
 *  · slides in 200ms with overshoot, auto-hides after 3.5s (6s once touched)
 *  · tap = open Hione Music · ✕ / swipe up = dismiss · swipe down = dismiss + open the shade
 *  · gated by Settings.Global hione_track_hud_enabled (default 1), suppressed when screen off,
 *    keyguard locked, the HioneOS lockscreen/AOD is up, or one of our own surfaces is on top
 */
class HioneTrackHud(
    private val ctx: Context,
    private val windowManager: WindowManager,
    private val openShade: (Int) -> Unit
) {
    companion object {
        const val TAG = "HioneTrackHud"
        const val ACTION_TRACK_CHANGED = "com.hione.player.action.TRACK_CHANGED"
        const val ACTION_DEBUG = "com.hione.systemui.action.DEBUG_TRACK_HUD"
        const val GLOBAL_ENABLED = "hione_track_hud_enabled"
        private const val AUTO_HIDE_MS = 3500L
        private const val AUTO_HIDE_TOUCHED_MS = 6000L
        private const val TOP_STRIP_DP = 20f
        private const val HEIGHT_DP = 72f
    }

    data class Payload(
        val title: String, val artist: String, val album: String,
        val durationMs: Long, val positionMs: Long, val artUri: String?,
        val quality: String?, val liked: Boolean, val isPlaying: Boolean,
        val accent: Int = 0,
        /** The app a tap opens and whose own screen already shows this. Hione Music unless the
         *  sender says otherwise; the FM tuner (com.caf.fmradio) sends its own for radio songs. */
        val openPackage: String = "com.hione.player",
    ) {
        companion object {
            fun from(i: Intent) = Payload(
                title = i.getStringExtra("title") ?: "",
                artist = i.getStringExtra("artist") ?: "",
                album = i.getStringExtra("album") ?: "",
                durationMs = i.getLongExtra("durationMs", i.getIntExtra("durationMs", 0).toLong()),
                positionMs = i.getLongExtra("positionMs", i.getIntExtra("positionMs", 0).toLong()),
                artUri = i.getStringExtra("artUri"),
                quality = i.getStringExtra("quality"),
                liked = i.getBooleanExtra("liked", false),
                isPlaying = i.getBooleanExtra("isPlaying", true),
                accent = i.getIntExtra("accent", 0),
                openPackage = i.getStringExtra("openPackage")?.takeIf { it.isNotBlank() } ?: "com.hione.player",
            )
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private var view: HudView? = null
    private var hideAt = 0L
    private val hideRunnable = Runnable { hide() }

    fun isEnabled(): Boolean =
        runCatching { Settings.Global.getInt(ctx.contentResolver, GLOBAL_ENABLED, 1) == 1 }.getOrDefault(true)

    /** Everything that should suppress a pop-over: screen off, keyguard, HioneOS lock/AOD, our own surfaces. */
    private fun suppressed(openPackage: String = "com.hione.player"): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm?.isInteractive == false) return true
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        if (km?.isKeyguardLocked == true) return true
        val top = runCatching { HioneTaskStack.topTask(ctx)?.second }.getOrNull()
        val cls = top?.className ?: ""
        if (top?.packageName == ctx.packageName) return true            // shade / recents / power menu
        if (top?.packageName == openPackage) return true                // the sending app is showing it already
        if (cls.contains("Lockscreen", true) || cls.contains("Aod", true)) return true
        return false
    }

    fun show(p: Payload) {
        if (!isEnabled()) { Log.i(TAG, "disabled — ignoring"); return }
        if (suppressed(p.openPackage)) { Log.i(TAG, "suppressed (screen/keyguard/own surface on top)"); return }
        main.post {
            val v = view ?: HudView(ctx).also { hv ->
                val dm = ctx.resources.displayMetrics
                val params = WindowManager.LayoutParams(
                    dm.widthPixels - dp(32f).toInt(), dp(HEIGHT_DP).toInt(),
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT
                ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(TOP_STRIP_DP + 16f).toInt() }
                try { windowManager.addView(hv, params) } catch (t: Throwable) { Log.w(TAG, "addView: $t"); return@post }
                view = hv
                hv.slideIn()
            }
            v.bind(p)
            scheduleHide(AUTO_HIDE_MS)
        }
    }

    private fun scheduleHide(ms: Long) {
        main.removeCallbacks(hideRunnable)
        hideAt = SystemClock.elapsedRealtime() + ms
        main.postDelayed(hideRunnable, ms)
    }

    fun hide() {
        main.removeCallbacks(hideRunnable)
        val v = view ?: return
        v.slideOut { runCatching { windowManager.removeView(v) }; if (view === v) view = null }
    }

    fun destroy() {
        main.removeCallbacks(hideRunnable)
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
    }

    // ------------------------------------------------------------------ the card

    private inner class HudView(context: Context) : View(context) {
        private var p: Payload? = null
        private var art: Bitmap? = null
        private var accent = Mascot.argb(0xFFFFAB2E.toInt())
        private var boundAt = 0L
        private var marquee = 0f
        private var marqueeAnim: ValueAnimator? = null
        private var slide = -1f          // -1 hidden above, 0 resting
        private var dragY = 0f           // upward finger-follow while swiping to dismiss (≤ 0)
        private var returnAnim: ValueAnimator? = null
        private var downX = 0f; private var downY = 0f; private var swiped = false

        private val glass = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Mascot.argb(0xFFF0FDFB.toInt()); textSize = dp(14f); isFakeBoldText = true }
        private val artistPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Mascot.argb(0xFFFFD59A.toInt()); textSize = dp(12f) }
        private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(10f); isFakeBoldText = true }
        private val heartPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Mascot.argb(0xFF6FB0FF.toInt()); textSize = dp(13f); textAlign = Paint.Align.CENTER }
        private val xPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8BA6A9.toInt(); textSize = dp(14f); textAlign = Paint.Align.CENTER }
        private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val rect = RectF()
        private val artDst = Rect()
        private val cardR = dp(20f)
        private val glassFx = HioneGlass.CanvasGlass(density)

        init { isClickable = true }

        fun bind(np: Payload) {
            p = np; boundAt = SystemClock.elapsedRealtime()
            art = null; accent = if (np.accent != 0) np.accent else (HioneAccent.accent.value.takeIf { it != 0 } ?: Mascot.argb(0xFFFFAB2E.toInt()))
            invalidate()
            val uri = np.artUri
            if (!uri.isNullOrBlank()) Thread {
                val bmp = loadArt(uri)
                val col = HioneMediaHub.dominantColor(bmp)
                post { if (p === np) { art = bmp; accent = col; invalidate() } }
            }.start()
            startMarqueeIfNeeded()
        }

        private fun loadArt(uri: String): Bitmap? = runCatching {
            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
            val raw = if (uri.startsWith("content://") || uri.startsWith("file://")) {
                context.contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it, null, opts) }
            } else {
                val f = File(uri); if (f.exists()) BitmapFactory.decodeFile(f.absolutePath, opts) else null
            }
            raw?.let { Bitmap.createScaledBitmap(it, dp(56f).toInt(), dp(56f).toInt(), true) }
        }.getOrNull()

        private fun startMarqueeIfNeeded() { marqueeAnim?.cancel(); marquee = 0f; invalidate() }

        /** Marquee offset for the title line: still for 1.6s after bind, then a slow ping-pong. */
        private fun marqueeOffset(line: String, avail: Float): Float {
            val tw = titlePaint.measureText(line)
            if (tw <= avail || HionePowerProfile.lowPower) return 0f
            val span = tw - avail + dp(24f)
            val period = ((span / dp(22f)) * 1000f).toLong().coerceIn(2000L, 12000L)
            val t = SystemClock.elapsedRealtime() - boundAt - 1600L
            if (t < 0) return 0f
            val phase = t % (2 * period)
            return if (phase < period) span * phase / period else span * (1f - (phase - period).toFloat() / period)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); startMarqueeIfNeeded() }

        fun slideIn() {
            ValueAnimator.ofFloat(-1f, 0f).apply {
                duration = HioneMotion.ms(200).toLong(); interpolator = HioneMotion.overshoot(0.8f)   // ≈1.05 overshoot
                addUpdateListener { slide = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        fun slideOut(end: () -> Unit) {
            marqueeAnim?.cancel(); returnAnim?.cancel()
            val from = slide + dragY / (height + dp(40f)).coerceAtLeast(1f)
            dragY = 0f
            ValueAnimator.ofFloat(from, -1.2f).apply {
                duration = HioneMotion.ms(160).toLong(); interpolator = HioneMotion.decel()
                addUpdateListener { slide = it.animatedValue as Float; invalidate() }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: android.animation.Animator) { end() }
                })
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val pp = p ?: return
            val w = width.toFloat(); val h = height.toFloat()
            canvas.translate(0f, slide * (h + dp(40f)) + dragY.coerceAtMost(0f))
            // glow halo in the art's colour
            glow.shader = RadialGradient(w * 0.18f, h * 0.5f, w * 0.7f, intArrayOf(accent and 0x55FFFFFF, 0x00000000), null, Shader.TileMode.CLAMP)
            rect.set(-dp(6f), -dp(6f), w + dp(6f), h + dp(6f)); canvas.drawRoundRect(rect, cardR + dp(6f), cardR + dp(6f), glow)
            // glass card
            glass.shader = LinearGradient(0f, 0f, w, h, intArrayOf(Mascot.argb(0xF60B222A.toInt()), Mascot.argb(0xFA061319.toInt())), null, Shader.TileMode.CLAMP)
            rect.set(0f, 0f, w, h); canvas.drawRoundRect(rect, cardR, cardR, glass)
            // HioneGlass sheen, top-edge light and refraction rim (art accent -> Hione pink), under the content
            glassFx.drawOver(canvas, rect, cardR, accent, Mascot.argb(0xFF6FB0FF.toInt()))
            // art
            val pad = dp(8f); val artSz = dp(56f)
            artDst.set(pad.toInt(), pad.toInt(), (pad + artSz).toInt(), (pad + artSz).toInt())
            canvas.save()
            // subtle 3° tilt that straightens as the card lands
            canvas.rotate(-3f * (-slide).coerceIn(0f, 1f), artDst.exactCenterX(), artDst.exactCenterY())
            val artPath = android.graphics.Path().apply { addRoundRect(RectF(artDst), dp(14f), dp(14f), android.graphics.Path.Direction.CW) }
            canvas.clipPath(artPath)
            val a = art
            if (a != null) canvas.drawBitmap(a, null, artDst, artPaint)
            else { fillPaint.color = (accent and 0x00FFFFFF) or 0x66000000; canvas.drawRect(artDst, fillPaint); heartPaint.textSize = dp(22f); canvas.drawText("♪", artDst.exactCenterX(), artDst.exactCenterY() + dp(8f), heartPaint); heartPaint.textSize = dp(13f) }
            canvas.restore()
            // text block (clipped, marquee)
            val tx = pad + artSz + dp(12f)
            val textRight = w - dp(44f) - dp(8f)
            canvas.save(); canvas.clipRect(tx, 0f, textRight, h)
            val line = "${pp.title}   ·   ${pp.artist}"
            marquee = marqueeOffset(line, textRight - tx)
            canvas.drawText(line, tx - marquee, dp(24f), titlePaint)
            canvas.drawText(pp.album.ifBlank { pp.artist }, tx, dp(41f), artistPaint)
            canvas.restore()
            // quality chip + heart, bottom row of the text block
            var cx = tx
            pp.quality?.takeIf { it.isNotBlank() }?.let { q ->
                chipPaint.color = accent
                val cw = chipPaint.measureText(q) + dp(12f)
                rect.set(cx, dp(49f), cx + cw, dp(63f))
                fillPaint.color = (accent and 0x00FFFFFF) or 0x33000000; canvas.drawRoundRect(rect, dp(7f), dp(7f), fillPaint)
                canvas.drawText(q, cx + dp(6f), dp(59.5f), chipPaint)
                cx += cw + dp(8f)
            }
            if (pp.liked) canvas.drawText("♥", cx + dp(7f), dp(60f), heartPaint)
            // ✕ (44dp target at the right)
            canvas.drawText("✕", w - dp(22f), h / 2f + dp(5f), xPaint)
            // progress hairline
            val dur = pp.durationMs.coerceAtLeast(1L)
            val pos = pp.positionMs + (if (pp.isPlaying) SystemClock.elapsedRealtime() - boundAt else 0L)
            val frac = (pos.toFloat() / dur).coerceIn(0f, 1f)
            progressPaint.color = accent
            rect.set(cardR, h - dp(3f), cardR + (w - 2 * cardR) * frac, h - dp(1f)); canvas.drawRoundRect(rect, dp(1f), dp(1f), progressPaint)
            if (titlePaint.measureText(line) > textRight - tx && !HionePowerProfile.lowPower) postInvalidateOnAnimation()
            else if (pp.isPlaying) postInvalidateDelayed(HionePowerProfile.pollMs(500L))
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; swiped = false; returnAnim?.cancel(); dragY = 0f; scheduleHide(AUTO_HIDE_TOUCHED_MS); return true }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.y - downY
                    if (dy < 0f) {
                        // upward: the card rides the finger (rubber-banded past 48dp)
                        val lim = dp(48f)
                        dragY = if (-dy <= lim) dy else -(lim + (-dy - lim) * 0.25f)
                        if (!swiped && -dy > dp(12f)) { swiped = true; HioneHaptics.tick(this) }
                        invalidate()
                    } else if (!swiped && dy > dp(24f) && dy > abs(e.x - downX)) {
                        swiped = true
                        HioneHaptics.confirm(this)
                        hide(); openShade(dy.toInt())
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragY < 0f) {
                        if (-dragY >= dp(24f)) { HioneHaptics.confirm(this); hide() }
                        else {
                            returnAnim?.cancel()
                            returnAnim = ValueAnimator.ofFloat(dragY, 0f).apply {
                                duration = HioneMotion.ms(220).toLong(); interpolator = HioneMotion.overshoot(1.3f)
                                addUpdateListener { dragY = it.animatedValue as Float; invalidate() }
                                start()
                            }
                        }
                        return true
                    }
                    if (!swiped) {
                        HioneHaptics.confirm(this)
                        if (e.x > width - dp(44f)) hide()
                        else {
                            runCatching {
                                context.packageManager.getLaunchIntentForPackage(p?.openPackage ?: "com.hione.player")
                                    ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }?.let { context.startActivity(it) }
                            }
                            hide()
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> { dragY = 0f; invalidate(); return true }
            }
            return super.onTouchEvent(e)
        }
    }
}
