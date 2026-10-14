package com.hione.systemui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import java.util.Locale
import kotlin.math.abs

/**
 * HioneOS Universal Volume HUD Overlay for 3rd-party apps (Spotify, Tidal, Apple Music, Chrome, etc.).
 *
 * Hione Music (com.hione.player) and Hione Launcher (com.hione.launcher) render their own in-activity
 * Compose volume HUDs directly on their surfaces. When a 3rd-party app like Spotify is foregrounded,
 * those activities are paused/hidden, leaving the user with no visual feedback when rotating the
 * hardware volume knob or adjusting system volume.
 *
 * This accessibility overlay bridges that gap by projecting the exact HioneOS cyberpunk volume HUD
 * into an interactive, non-focus-stealing floating window over any 3rd-party application.
 *
 * Supports both styles configured in Hione Settings:
 *   1. right_bar     — Sleek vertical neon bar anchored on the upper-right edge near the physical roller.
 *   2. center_modal  — Holographic centered capsule with danger zone meter (Hione Music style).
 *
 * Suppressed when:
 *   • HiBy legacy fullscreen volume dialog is enabled (Settings.Global hiby_volume_dialog_enable == 1)
 *   • One of our self-drawing surfaces is foregrounded (com.hione.player, com.hione.launcher, lockscreen)
 *   • Display is off / non-interactive, or keyguard is locked
 *   • Master toggle is off (Settings.Global hione_volume_hud_overlay_enabled == 0)
 */
class HioneVolumeHud(
    private val ctx: Context,
    private val windowManager: WindowManager
) {
    companion object {
        const val TAG = "HioneVolumeHud"
        const val GLOBAL_ENABLED = "hione_volume_hud_overlay_enabled"
        const val KEY_STYLE = "hione_volume_hud_style"
        const val HIBY_ENABLE_KEY = "hiby_volume_dialog_enable"
        private const val AUTO_HIDE_MS = 2400L

        // Known packages that render their own internal volume overlay
        private val SELF_DRAWING_PACKAGES = setOf(
            "com.hione.player",
            "com.hione.launcher"
        )
    }

    private val main = Handler(Looper.getMainLooper())
    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    private var view: VolumeHudView? = null
    private var currentStyle: String = "right_bar"
    private var lastForegroundPkg: String? = null
    private var lastForegroundCls: String? = null

    private val hideRunnable = Runnable { hide() }

    fun setForeground(pkg: String?, cls: String?) {
        if (pkg != null) lastForegroundPkg = pkg
        if (cls != null) lastForegroundCls = cls
        if (isSelfDrawingSurface(lastForegroundPkg, lastForegroundCls)) {
            hideImmediately()
        }
    }

    private fun isSelfDrawingSurface(pkg: String?, cls: String?): Boolean {
        if (pkg != null && pkg in SELF_DRAWING_PACKAGES) return true
        if (cls != null && (cls.contains("Lockscreen", ignoreCase = true) || cls.contains("Aod", ignoreCase = true))) {
            return true
        }
        return false
    }

    private fun isEnabled(): Boolean = runCatching {
        Settings.Global.getInt(ctx.contentResolver, GLOBAL_ENABLED, 1) == 1
    }.getOrDefault(true)

    private fun isHibyDialogEnabled(): Boolean = runCatching {
        Settings.Global.getInt(ctx.contentResolver, HIBY_ENABLE_KEY, 0) == 1
    }.getOrDefault(false)

    private fun style(): String = runCatching {
        Settings.Global.getString(ctx.contentResolver, KEY_STYLE) ?: "right_bar"
    }.getOrDefault("right_bar")

    fun suppressed(): Boolean {
        if (!isEnabled()) return true
        if (isHibyDialogEnabled()) return true

        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm?.isInteractive == false) return true

        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        if (km?.isKeyguardLocked == true) return true

        // The LIVE top task, not only the cached window event. The service's notificationTimeout
        // (100ms) merges window-state events, so after leaving Hione Music for a third-party app
        // the cache could still say Hione Music and the HUD stayed suppressed in that app.
        val live = liveTop()
        val fgPkg = live?.packageName ?: lastForegroundPkg
        val fgCls = live?.className ?: lastForegroundCls
        if (isSelfDrawingSurface(fgPkg, fgCls)) return true

        return false
    }

    private var liveTopAt = 0L
    private var liveTopCached: android.content.ComponentName? = null

    /** Top activity from getRunningTasks, cached 300ms so a fast knob spin is one binder call, not ten. */
    private fun liveTop(): android.content.ComponentName? {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - liveTopAt > 300L) {
            liveTopCached = runCatching { HioneTaskStack.topTask(ctx)?.second }.getOrNull()
            liveTopAt = now
        }
        return liveTopCached
    }

    /**
     * Distance in px between the right screen edge and the right bar. The bar used to sit 4dp
     * from the edge, inside AOSP's back-gesture zone, so for the 2.4s it was up a right-edge back
     * swipe landed on the HUD instead. Now it starts just inside that zone: the framework's
     * config_backGestureInset times the user's right-side scale, the same numbers
     * EdgeBackGestureHandler uses (50px on this device). Falls back to 30dp.
     */
    private fun rightBarInsetPx(): Int {
        val id = ctx.resources.getIdentifier("config_backGestureInset", "dimen", "android")
        val base = if (id != 0) runCatching { ctx.resources.getDimensionPixelSize(id) }.getOrDefault(0) else 0
        val scale = runCatching {
            Settings.Secure.getFloat(ctx.contentResolver, "back_gesture_inset_scale_right", 1f)
        }.getOrDefault(1f)
        val px = (base * scale).toInt()
        return (if (px > 0) px else dp(30f).toInt()) + dp(4f).toInt()
    }

    fun stepVolume(delta: Int) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val stream = AudioManager.STREAM_MUSIC
        val max = am.getStreamMaxVolume(stream)
        val cur = am.getStreamVolume(stream)
        val target = (cur + delta).coerceIn(0, max)
        am.setStreamVolume(stream, target, 0)
        onVolumeChanged()
    }

    fun setVolumePct(pct: Int) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val stream = AudioManager.STREAM_MUSIC
        val max = am.getStreamMaxVolume(stream).coerceAtLeast(1)
        val target = ((pct / 100f) * max).toInt().coerceIn(0, max)
        am.setStreamVolume(stream, target, 0)
        onVolumeChanged()
    }

    fun toggleMute() {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val stream = AudioManager.STREAM_MUSIC
        val isMuted = if (Build.VERSION.SDK_INT >= 23) am.isStreamMute(stream) else am.getStreamVolume(stream) == 0
        if (Build.VERSION.SDK_INT >= 23) {
            am.adjustStreamVolume(
                stream,
                if (isMuted) AudioManager.ADJUST_UNMUTE else AudioManager.ADJUST_MUTE,
                0
            )
        } else {
            am.setStreamMute(stream, !isMuted)
        }
        onVolumeChanged()
    }

    fun onVolumeChanged(delta: Int = 0) {
        if (suppressed()) return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val stream = AudioManager.STREAM_MUSIC
        val max = am.getStreamMaxVolume(stream).coerceAtLeast(1)
        val cur = am.getStreamVolume(stream)
        val isMuted = cur == 0 || (Build.VERSION.SDK_INT >= 23 && am.isStreamMute(stream))
        val pct = ((cur.toFloat() / max.toFloat()) * 100f).toInt().coerceIn(0, 100)

        val st = style()
        main.post {
            show(pct, isMuted, st)
        }
    }

    private fun show(pct: Int, isMuted: Boolean, st: String) {
        if (suppressed()) return
        val isCenterModal = (st == "center_modal")

        // If the style changed while a HUD is visible, tear down to re-anchor WindowManager params
        if (view != null && currentStyle != st) {
            hideImmediately()
        }
        currentStyle = st

        val v = view ?: VolumeHudView(ctx, isCenterModal).also { hv ->
            val params = if (isCenterModal) {
                WindowManager.LayoutParams(
                    dp(256f).toInt(), dp(78f).toInt(),
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    windowFlags(),
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = dp(36f).toInt()
                }
            } else {
                WindowManager.LayoutParams(
                    dp(58f).toInt(), dp(246f).toInt(),
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    windowFlags(),
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                    y = dp(92f).toInt()
                    x = rightBarInsetPx()
                }
            }
            try {
                windowManager.addView(hv, params)
                view = hv
                hv.slideIn()
            } catch (t: Throwable) {
                Log.w(TAG, "addView failed: $t")
                return
            }
        }

        v.bind(pct, isMuted)
        scheduleHide(AUTO_HIDE_MS)
    }

    private fun windowFlags(): Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

    private fun scheduleHide(ms: Long) {
        main.removeCallbacks(hideRunnable)
        main.postDelayed(hideRunnable, ms)
    }

    fun hide() {
        main.removeCallbacks(hideRunnable)
        val v = view ?: return
        v.slideOut {
            runCatching { windowManager.removeView(v) }
            if (view === v) view = null
        }
    }

    fun hideImmediately() {
        main.removeCallbacks(hideRunnable)
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
    }

    fun destroy() = hideImmediately()

    // ------------------------------------------------------------------ the UI view

    private inner class VolumeHudView(context: Context, val isModal: Boolean) : View(context) {
        private var pct: Int = 0
        private var isMuted: Boolean = false
        private var isDanger: Boolean = false

        private var slideProgress: Float = 1f   // 1f = hidden, 0f = fully visible
        private var scaleProgress: Float = 0.9f
        private var alphaProgress: Float = 0f
        private var hazardGlow: Float = 0.8f

        private var enterAnim: ValueAnimator? = null
        private var exitAnim: ValueAnimator? = null
        private var hazardAnim: ValueAnimator? = null

        private var downX = 0f
        private var downY = 0f
        private var isDragging = false

        // Paints
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1.5f)
        }
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(85, 4, 20, 30)
        }
        private val trackBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(0.9f)
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val buttonBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(2f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val badgeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1f)
            color = Color.argb(242, 255, 255, 255)
        }

        private val cardPath = Path()
        private val glassFx = HioneGlass.CanvasGlass(density)
        private val cardRect = RectF()
        private val trackRect = RectF()
        private val fillRect = RectF()
        private val badgeRect = RectF()

        init {
            isClickable = true
            isFocusable = false
            startHazardPulse()
        }

        private fun startHazardPulse() {
            hazardAnim?.cancel()
            hazardAnim = ValueAnimator.ofFloat(0.65f, 1.0f).apply {
                duration = 320L
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener {
                    hazardGlow = it.animatedValue as Float
                    if (isDanger) invalidate()
                }
                start()
            }
        }

        fun bind(p: Int, m: Boolean) {
            pct = p.coerceIn(0, 100)
            isMuted = m
            isDanger = (pct >= 80)
            invalidate()
        }

        fun slideIn() {
            enterAnim?.cancel()
            exitAnim?.cancel()
            if (isModal) {
                scaleProgress = 0.9f
                alphaProgress = 0f
                enterAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 140L
                    interpolator = OvershootInterpolator(0.8f)
                    addUpdateListener {
                        val f = it.animatedValue as Float
                        scaleProgress = 0.9f + (0.1f * f)
                        alphaProgress = f
                        invalidate()
                    }
                    start()
                }
            } else {
                slideProgress = 1f
                enterAnim = ValueAnimator.ofFloat(1f, 0f).apply {
                    duration = 160L
                    interpolator = OvershootInterpolator(0.8f)
                    addUpdateListener {
                        slideProgress = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
            }
        }

        fun slideOut(onEnd: () -> Unit) {
            enterAnim?.cancel()
            exitAnim?.cancel()
            if (isModal) {
                exitAnim = ValueAnimator.ofFloat(alphaProgress, 0f).apply {
                    duration = 180L
                    interpolator = DecelerateInterpolator()
                    addUpdateListener {
                        alphaProgress = it.animatedValue as Float
                        scaleProgress = 0.9f + (0.1f * alphaProgress)
                        invalidate()
                    }
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            hazardAnim?.cancel()
                            onEnd()
                        }
                    })
                    start()
                }
            } else {
                exitAnim = ValueAnimator.ofFloat(slideProgress, 1.2f).apply {
                    duration = 200L
                    interpolator = DecelerateInterpolator()
                    addUpdateListener {
                        slideProgress = it.animatedValue as Float
                        invalidate()
                    }
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            hazardAnim?.cancel()
                            onEnd()
                        }
                    })
                    start()
                }
            }
        }

        private fun sampleVolumeColor(pct: Int, muted: Boolean): Int {
            if (muted) return 0xFFFF5252.toInt()
            return when {
                pct >= 85 -> 0xFFFF1744.toInt() // Danger Red / Coral
                pct >= 70 -> Mascot.argb(0xFF6FB0FF.toInt()) // Neon Pink
                pct >= 50 -> Mascot.argb(0xFFB9C2D0.toInt()) // Electric Purple
                pct >= 25 -> Mascot.argb(0xFFFFC56B.toInt()) // Hione Cyan
                else -> 0xFF00FF88.toInt()      // Cyber Emerald
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.save()

            if (isModal) {
                val cx = width / 2f
                val cy = height / 2f
                canvas.scale(scaleProgress, scaleProgress, cx, cy)
                val alphaInt = (alphaProgress * 255).toInt().coerceIn(0, 255)
                drawCenterModal(canvas, alphaInt)
            } else {
                canvas.translate(slideProgress * (width + dp(8f)), 0f)
                drawRightBar(canvas)
            }

            canvas.restore()
        }

        // =========================================================================
        // STYLE 1: RIGHT-EDGE SLIDING CYBER BAR
        // =========================================================================
        private fun drawRightBar(canvas: Canvas) {
            val w = width.toFloat() - dp(4f)
            val h = height.toFloat() - dp(6f)
            val left = dp(2f)
            val top = dp(6f)
            val right = left + w
            val bottom = top + h

            cardRect.set(left, top, right, bottom)
            val cut = dp(12f)
            cardPath.reset()
            cardPath.moveTo(cardRect.left + cut, cardRect.top)
            cardPath.lineTo(cardRect.right - cut, cardRect.top)
            cardPath.lineTo(cardRect.right, cardRect.top + cut)
            cardPath.lineTo(cardRect.right, cardRect.bottom - cut)
            cardPath.lineTo(cardRect.right - cut, cardRect.bottom)
            cardPath.lineTo(cardRect.left + cut, cardRect.bottom)
            cardPath.lineTo(cardRect.left, cardRect.bottom - cut)
            cardPath.lineTo(cardRect.left, cardRect.top + cut)
            cardPath.close()

            // Card background
            val bgStart = if (isDanger) Mascot.argb(0xF5240810.toInt()) else Mascot.argb(0xF008202D.toInt())
            val bgEnd = if (isDanger) 0xFA140205.toInt() else Mascot.argb(0xFA030D14.toInt())
            bgPaint.shader = LinearGradient(0f, top, 0f, bottom, bgStart, bgEnd, Shader.TileMode.CLAMP)
            canvas.drawPath(cardPath, bgPaint)

            // Neon border
            val dynColor = sampleVolumeColor(pct, isMuted)
            val glowBrightness = (pct / 100f).coerceIn(0.4f, 1.0f)
            borderPaint.strokeWidth = dp(1.0f + glowBrightness * 0.8f)
            val borderColors = if (isDanger) {
                intArrayOf(0xFFFF1744.toInt(), Mascot.argb(0xFFFF007F.toInt()), 0xFFFF5252.toInt())
            } else {
                intArrayOf(dynColor, Mascot.argb(0xFFFFC56B.toInt()), 0xFF7C4DFF.toInt())
            }
            borderPaint.shader = LinearGradient(0f, top, 0f, bottom, borderColors, null, Shader.TileMode.CLAMP)
            canvas.drawPath(cardPath, borderPaint)
            // HioneGlass sheen + top-edge light; the neon border above stays the rim
            glassFx.drawOver(canvas, cardPath, cardRect, dynColor, withRim = false)

            val cx = cardRect.centerX()

            // 1. Top Plus Button (+)
            val plusY = top + dp(18f)
            val btnR = dp(13f)
            buttonBgPaint.color = if (isDanger) 0x44FF1744.toInt() else (dynColor and 0x00FFFFFF) or 0x40000000
            canvas.drawCircle(cx, plusY, btnR, buttonBgPaint)
            iconPaint.color = if (isDanger) 0xFFFF1744.toInt() else dynColor
            val plusArm = dp(5f)
            canvas.drawLine(cx - plusArm, plusY, cx + plusArm, plusY, iconPaint)
            canvas.drawLine(cx, plusY - plusArm, cx, plusY + plusArm, iconPaint)

            // 2. Middle Vertical Gauge
            val gw = dp(22f)
            val gy = top + dp(38f)
            val gh = h - dp(106f)
            val gx = cx - gw / 2f
            trackRect.set(gx, gy, gx + gw, gy + gh)
            canvas.drawRoundRect(trackRect, dp(11f), dp(11f), trackPaint)

            val trackBorderColor = if (isDanger) {
                Color.argb((hazardGlow * 255).toInt(), 255, 23, 68)
            } else {
                Color.argb((glowBrightness * 255).toInt(), Color.red(dynColor), Color.green(dynColor), Color.blue(dynColor))
            }
            trackBorderPaint.color = trackBorderColor
            canvas.drawRoundRect(trackRect, dp(11f), dp(11f), trackBorderPaint)

            // Fill
            val frac = (pct / 100f).coerceIn(0f, 1f)
            val fillHeight = gh * frac
            val topY = gy + gh - fillHeight
            if (fillHeight > 0f) {
                canvas.save()
                canvas.clipRect(gx, topY, gx + gw, gy + gh)
                fillRect.set(gx, topY, gx + gw, gy + gh)

                val fillColors = if (isMuted) {
                    intArrayOf(0xFFFF5252.toInt(), 0xFFB71C1C.toInt())
                } else {
                    intArrayOf(
                        0xFFFF1744.toInt(),
                        0xFFFF5252.toInt(),
                        Mascot.argb(0xFFFF3399.toInt()),
                        0xFF9D4EDD.toInt(),
                        Mascot.argb(0xFFFFC56B.toInt()),
                        Mascot.argb(0xFFFFC56B.toInt())
                    )
                }
                val fillPositions = if (isMuted) null else floatArrayOf(0.00f, 0.12f, 0.25f, 0.50f, 0.75f, 1.00f)
                fillPaint.shader = LinearGradient(0f, gy, 0f, gy + gh, fillColors, fillPositions, Shader.TileMode.CLAMP)
                canvas.drawRoundRect(trackRect, dp(11f), dp(11f), fillPaint)

                // Radial glow at the thumb
                glowPaint.shader = RadialGradient(
                    cx, topY, gw * 1.8f,
                    intArrayOf(dynColor, (dynColor and 0x00FFFFFF) or 0x66000000, Color.TRANSPARENT),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawCircle(cx, topY, gw * 1.8f, glowPaint)

                canvas.restore()
                // Liquid bead thumb riding the fill level (outside the fill clip so it can sit proud)
                glassFx.drawBead(canvas, cx, topY.coerceIn(gy + dp(5f), gy + gh - dp(5f)), gw / 2f + dp(1f), dp(5f), dynColor)
            }

            // 3. Volume % Text
            val textY = gy + gh + dp(15f)
            textPaint.textSize = dp(8.5f)
            textPaint.color = if (isDanger || isMuted) 0xFFFF5252.toInt() else Color.WHITE
            canvas.drawText(if (isMuted) "MUTE" else "$pct%", cx, textY, textPaint)

            // 4. Minus Button (-)
            val minusY = bottom - dp(36f)
            buttonBgPaint.color = (dynColor and 0x00FFFFFF) or 0x40000000
            canvas.drawCircle(cx, minusY, btnR, buttonBgPaint)
            iconPaint.color = dynColor
            canvas.drawLine(cx - plusArm, minusY, cx + plusArm, minusY, iconPaint)

            // 5. Mute Button
            val muteY = bottom - dp(14f)
            val muteR = dp(10f)
            buttonBgPaint.color = if (isMuted) 0x66FF1744.toInt() else (dynColor and 0x00FFFFFF) or 0x33000000
            canvas.drawCircle(cx, muteY, muteR, buttonBgPaint)
            iconPaint.color = if (isMuted) 0xFFFF5252.toInt() else dynColor
            // Small speaker glyph
            val spkLeft = cx - dp(4f)
            val spkRight = cx + dp(2f)
            canvas.drawLine(spkLeft, muteY - dp(3f), spkLeft, muteY + dp(3f), iconPaint)
            canvas.drawLine(spkLeft, muteY, spkRight, muteY, iconPaint)
            if (isMuted) {
                // Diagonal strike-through for mute
                canvas.drawLine(cx - dp(4f), muteY - dp(4f), cx + dp(4f), muteY + dp(4f), iconPaint)
            }

            // 6. Danger Badge (>= 80%)
            if (isDanger) {
                val bw = dp(46f)
                val bh = dp(12f)
                val bx = cx - bw / 2f
                val by = top - dp(4f)
                badgeRect.set(bx, by, bx + bw, by + bh)

                badgeBgPaint.shader = LinearGradient(bx, by, bx + bw, by,
                    0xFFFF1744.toInt(), Mascot.argb(0xFFFF007F.toInt()), Shader.TileMode.CLAMP)
                canvas.drawRoundRect(badgeRect, dp(3f), dp(3f), badgeBgPaint)
                canvas.drawRoundRect(badgeRect, dp(3f), dp(3f), badgeBorderPaint)

                textPaint.textSize = dp(6.5f)
                textPaint.color = Color.WHITE
                canvas.drawText(">80dB", cx, by + dp(9f), textPaint)
            }
        }

        // =========================================================================
        // STYLE 2: CENTER CYBER ARC / MODAL (HIONE MUSIC STYLE)
        // =========================================================================
        private fun drawCenterModal(canvas: Canvas, alpha: Int) {
            val w = width.toFloat() - dp(8f)
            val h = height.toFloat() - dp(6f)
            val left = dp(4f)
            val top = dp(3f)
            val right = left + w
            val bottom = top + h

            cardRect.set(left, top, right, bottom)
            val cornerR = dp(20f)

            // Background
            val bgStart = if (isDanger) 0xF033050C.toInt() else Mascot.argb(0xE6081A24.toInt())
            val bgEnd = if (isDanger) 0xF5180004.toInt() else Mascot.argb(0xF2030D13.toInt())
            bgPaint.shader = LinearGradient(0f, top, 0f, bottom, bgStart, bgEnd, Shader.TileMode.CLAMP)
            bgPaint.alpha = alpha
            canvas.drawRoundRect(cardRect, cornerR, cornerR, bgPaint)

            // Border
            borderPaint.strokeWidth = dp(1.5f)
            val borderColors = if (isDanger) {
                intArrayOf(Mascot.argb(0xFFFF0044.toInt()), Color.argb((hazardGlow * 255).toInt(), 255, 85, 0))
            } else {
                intArrayOf(Mascot.argb(0xFFFFAB2E.toInt()), Mascot.argb(0xFFFF4FA3.toInt()))
            }
            borderPaint.shader = LinearGradient(left, top, right, top, borderColors, null, Shader.TileMode.CLAMP)
            borderPaint.alpha = alpha
            canvas.drawRoundRect(cardRect, cornerR, cornerR, borderPaint)
            glassFx.drawOver(canvas, cardRect, cornerR, Mascot.argb(0xFFFFAB2E.toInt()), Mascot.argb(0xFFFF4FA3.toInt()), alpha, withRim = false)

            // Left: Speaker Circle (34dp)
            val iconCx = left + dp(28f)
            val iconCy = cardRect.centerY()
            val iconR = dp(17f)
            buttonBgPaint.color = if (isDanger) Mascot.argb(0x44FF0044.toInt()) else Mascot.argb(0x33FFAB2E.toInt())
            buttonBgPaint.alpha = (alpha * 0.7f).toInt()
            canvas.drawCircle(iconCx, iconCy, iconR, buttonBgPaint)

            // Speaker Icon
            iconPaint.color = if (isDanger) 0xFFFF2255.toInt() else Mascot.argb(0xFFFFAB2E.toInt())
            iconPaint.alpha = alpha
            val sArm = dp(5f)
            canvas.drawLine(iconCx - sArm, iconCy - sArm, iconCx - sArm, iconCy + sArm, iconPaint)
            canvas.drawLine(iconCx - sArm, iconCy, iconCx + dp(2f), iconCy, iconPaint)
            canvas.drawLine(iconCx + dp(2f), iconCy - dp(6f), iconCx + dp(2f), iconCy + dp(6f), iconPaint)
            if (isMuted || pct == 0) {
                canvas.drawLine(iconCx - dp(6f), iconCy - dp(6f), iconCx + dp(6f), iconCy + dp(6f), iconPaint)
            }

            // Right: Text block and Progress Bar
            val textLeft = iconCx + iconR + dp(14f)

            // Large Volume Text
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.textSize = dp(18f)
            textPaint.color = if (isDanger) Mascot.argb(0xFFFF3366.toInt()) else Color.WHITE
            textPaint.alpha = alpha
            val numStr = String.format(Locale.US, "%d", pct)
            val numW = textPaint.measureText(numStr)
            val baselineY = top + dp(30f)
            canvas.drawText(numStr, textLeft, baselineY, textPaint)

            // Subtext "/ 100" or "/ 100 [HIGH]"
            textPaint.textSize = dp(10.5f)
            textPaint.color = if (isDanger) Mascot.argb(0xFFFF88AA.toInt()) else Mascot.argb(0xFFFFE0B0.toInt())
            textPaint.alpha = alpha
            val subText = if (isDanger) " / 100 [HIGH]" else " / 100"
            canvas.drawText(subText, textLeft + numW + dp(4f), baselineY - dp(1f), textPaint)

            // Horizontal Progress Bar
            val trackW = w - dp(84f)
            val trackH = dp(5f)
            val barTop = baselineY + dp(7f)
            trackRect.set(textLeft, barTop, textLeft + trackW, barTop + trackH)
            trackPaint.color = Color.argb(50, 255, 255, 255)
            trackPaint.alpha = (alpha * 0.3f).toInt()
            canvas.drawRoundRect(trackRect, dp(3f), dp(3f), trackPaint)

            val fillW = trackW * (pct / 100f).coerceIn(0f, 1f)
            if (fillW > 0f) {
                fillRect.set(textLeft, barTop, textLeft + fillW, barTop + trackH)
                val fillColors = if (isDanger) {
                    intArrayOf(Mascot.argb(0xFFFF0055.toInt()), 0xFFFF8800.toInt())
                } else {
                    intArrayOf(Mascot.argb(0xFFFFAB2E.toInt()), Mascot.argb(0xFFFF4FA3.toInt()))
                }
                fillPaint.shader = LinearGradient(textLeft, barTop, textLeft + trackW, barTop, fillColors, null, Shader.TileMode.CLAMP)
                fillPaint.alpha = alpha
                canvas.drawRoundRect(fillRect, dp(3f), dp(3f), fillPaint)
                glassFx.drawBead(canvas, textLeft + fillW, barTop + trackH / 2f, dp(7f), dp(5f),
                    if (isDanger) Mascot.argb(0xFFFF0055.toInt()) else Mascot.argb(0xFFFF4FA3.toInt()), alpha)
            }
        }

        // =========================================================================
        // TOUCH INTERACTION (DRAGGING, BUTTON TAPS, DISMISS SWIPES)
        // =========================================================================
        override fun onTouchEvent(event: MotionEvent): Boolean {
            scheduleHide(AUTO_HIDE_MS)

            if (isModal) {
                return handleModalTouch(event)
            } else {
                return handleRightBarTouch(event)
            }
        }

        private fun handleModalTouch(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x
                    downY = e.y
                    isDragging = false
                    // Tap speaker circle to toggle mute
                    val iconCx = dp(4f) + dp(28f)
                    if (abs(e.x - iconCx) < dp(24f)) {
                        toggleMute()
                        HioneHaptics.tick(this)
                        return true
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.y - downY
                    val dx = e.x - downX
                    if (dy < -dp(16f) && abs(dy) > abs(dx)) {
                        // Swipe upward dismisses modal
                        hide()
                        return true
                    }
                    // Scrub progress bar horizontally
                    val textLeft = dp(4f) + dp(56f)
                    val trackW = width.toFloat() - dp(84f)
                    if (e.x in textLeft..(textLeft + trackW)) {
                        isDragging = true
                        val newPct = (((e.x - textLeft) / trackW) * 100f).toInt().coerceIn(0, 100)
                        setVolumePct(newPct)
                        HioneHaptics.tick(this)
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging && abs(e.x - downX) < dp(8f) && abs(e.y - downY) < dp(8f)) {
                        // Tap progress bar directly
                        val textLeft = dp(4f) + dp(56f)
                        val trackW = width.toFloat() - dp(84f)
                        if (e.x in textLeft..(textLeft + trackW)) {
                            val newPct = (((e.x - textLeft) / trackW) * 100f).toInt().coerceIn(0, 100)
                            setVolumePct(newPct)
                            HioneHaptics.tick(this)
                        }
                    }
                    return true
                }
            }
            return super.onTouchEvent(e)
        }

        private fun handleRightBarTouch(e: MotionEvent): Boolean {
            val top = dp(6f)
            val h = height.toFloat() - dp(6f)
            val bottom = top + h

            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x
                    downY = e.y
                    isDragging = false

                    // Plus button tap (top region)
                    if (e.y < top + dp(32f)) {
                        stepVolume(+1)
                        HioneHaptics.tick(this)
                        return true
                    }

                    // Minus button tap
                    if (e.y in (bottom - dp(48f))..(bottom - dp(24f))) {
                        stepVolume(-1)
                        HioneHaptics.tick(this)
                        return true
                    }

                    // Mute button tap
                    if (e.y > bottom - dp(24f)) {
                        toggleMute()
                        HioneHaptics.tick(this)
                        return true
                    }

                    // Gauge drag start
                    val gy = top + dp(38f)
                    val gh = h - dp(106f)
                    if (e.y in gy..(gy + gh)) {
                        isDragging = true
                        val newPct = ((1f - (e.y - gy) / gh) * 100f).toInt().coerceIn(0, 100)
                        setVolumePct(newPct)
                        HioneHaptics.tick(this)
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.x - downX
                    if (dx > dp(20f) && abs(dx) > abs(e.y - downY)) {
                        // Swipe right toward screen edge dismisses HUD
                        hide()
                        return true
                    }

                    val gy = top + dp(38f)
                    val gh = h - dp(106f)
                    if (isDragging || e.y in gy..(gy + gh)) {
                        isDragging = true
                        val newPct = ((1f - (e.y - gy) / gh) * 100f).toInt().coerceIn(0, 100)
                        setVolumePct(newPct)
                        HioneHaptics.tick(this)
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    isDragging = false
                    return true
                }
            }
            return super.onTouchEvent(e)
        }
    }
}
