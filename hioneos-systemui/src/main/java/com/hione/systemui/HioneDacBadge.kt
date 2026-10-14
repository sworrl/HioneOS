package com.hione.systemui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.database.ContentObserver
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager

/**
 * The DAC badge: a small chip in the middle of the status bar that names the applied DAC settings
 * ("NOS · HI · DRE · HP") in every app, colored by [dacTheme].
 *
 * Source: Settings.Global `hione_dac_state`, written by com.hione.sysbridge after every change and
 * at boot. When that row is missing (an older bridge) it falls back to the persist properties.
 * Off switch: Settings.Global `hione_dac_badge` = 0 (QS tile "DAC badge"). Default on.
 *
 * Where it shows:
 *  - Centered in the status bar. The stock bar has the clock and notification icons at left and
 *    system icons at right, the Hione launcher/lockscreen row has the clock at left and temp, volume,
 *    Wi-Fi and battery at right, and the camera privacy dot is top right. The middle is free.
 *  - Only while the status bar is showing (WindowInsets of this window), or while the Hione launcher
 *    or lockscreen is in front: those hide the bars but draw their own row. Hidden on the AOD.
 *  - FLAG_NOT_TOUCHABLE, so the top pull strip and apps get every touch.
 *  - Added before the shade window, so the open shade covers it.
 */
class HioneDacBadge(private val ctx: Context, private val wm: WindowManager, private val barHeightPx: Int) {

    companion object {
        private const val TAG = "HioneDacBadge"
        const val KEY_STATE = "hione_dac_state"
        const val KEY_ENABLED = "hione_dac_badge"
        private const val LAUNCHER_PKG = "com.hione.launcher"

        fun isEnabled(ctx: Context): Boolean =
            runCatching { Settings.Global.getInt(ctx.contentResolver, KEY_ENABLED, 1) }.getOrDefault(1) != 0

        /** hione_dac_state, or the same thing built from the persist properties. */
        fun readState(ctx: Context): DacState {
            val raw = runCatching { Settings.Global.getString(ctx.contentResolver, KEY_STATE) }.getOrNull()
            if (!raw.isNullOrBlank()) return parseDacState(raw)
            return DacState(
                filter = DacBridge.get(DacBridge.FILTER),
                gain = DacBridge.get(DacBridge.GAIN),
                dre = DacBridge.get(DacBridge.DRE)?.let { it == "dremode_enable" },
                hp = DacBridge.get(DacBridge.HIGH_POWER)?.let { it == "hpower_enable" },
            )
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var view: BadgeView? = null
    private var attached = false

    private var enabled = true
    private var palette: DacPalette = dacTheme(null, null, null, null)
    private var statusBarShown = true
    private var ownRowInFront = false
    private var onAod = false
    private var ducked = false

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) { refresh() }
    }

    fun attach() {
        if (attached) return
        val v = BadgeView(ctx)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, barHeightPx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            title = "HioneDacBadge"
        }
        // No insets listener: an accessibility overlay is always told the status bar is hidden
        // (checked on the M500, over Google Play with the bar plainly showing), so the badge shows
        // everywhere except retro modes and the AOD. The "DAC badge" QS tile turns it off.
        runCatching { wm.addView(v, lp) }.onFailure { Log.w(TAG, "add: $it"); return }
        view = v
        attached = true
        val cr = ctx.contentResolver
        runCatching {
            cr.registerContentObserver(Settings.Global.getUriFor(KEY_STATE), false, observer)
            cr.registerContentObserver(Settings.Global.getUriFor(KEY_ENABLED), false, observer)
        }
        refresh()
    }

    fun detach() {
        if (!attached) return
        runCatching { ctx.contentResolver.unregisterContentObserver(observer) }
        view?.let { v -> v.stopAnim(); runCatching { wm.removeView(v) } }
        view = null
        attached = false
    }

    /** Called with the real top task (HioneNotificationShadeService.checkOwnBar). */
    fun setForeground(pkg: String?, cls: String?) {
        val own = pkg == LAUNCHER_PKG
        val aod = own && (cls?.contains("Aod", ignoreCase = true) == true)
        val duck = HioneAppClassifier.shouldDuckDacPill(ctx, pkg, cls)
        ownRowInFront = own; onAod = aod
        ducked = duck
        applyVisibility()
    }

    /**
     * Blackjack (our own DAC driver) is bound to the DAC at 0x30: the badge then starts with "BJ".
     * Read from the sysfs driver symlink (apps may read sysfs links, not sysfs files). A driver
     * cannot change without a reboot, so once per refresh is plenty.
     */
    private fun blackjackLoaded(): Boolean = runCatching {
        android.system.Os.readlink("/sys/bus/i2c/devices/0-0030/driver").substringAfterLast('/').startsWith("blackjack")
    }.getOrDefault(false)

    private var blackjack = false

    private fun refresh() {
        enabled = isEnabled(ctx)
        palette = dacTheme(readState(ctx))
        blackjack = blackjackLoaded()
        view?.setPalette(palette, blackjack)
        applyVisibility()
    }

    /** A retro mode (Riot mode, HionePod) is in front: no badge. */
    private var retro = false

    fun setRetro(on: Boolean) {
        if (on != retro) { retro = on; applyVisibility() }
    }

    private fun applyVisibility() {
        val v = view ?: return
        val show = enabled && !retro && !ducked && palette.badge.isNotEmpty() && !onAod && (statusBarShown || ownRowInFront)
        // Faded rather than GONE: a GONE root makes the window invisible to the window manager,
        // and then it may stop getting the insets that say the status bar came back.
        v.animate().cancel()
        v.animate().alpha(if (show) 1f else 0f).setDuration(if (show) 200L else 120L).start()
    }

    private inner class BadgeView(c: Context) : View(c) {
        private val d = c.resources.displayMetrics.density
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, c.resources.displayMetrics)
            letterSpacing = 0.04f
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val rect = RectF()
        private val padH = 7f * d
        private val pillH = 16f * d
        private val maxW = (130f * d).toInt()

        private var text = ""
        private var primary = palette.primary
        private var secondary = palette.secondary
        private var background = palette.background
        private var glow = palette.glow
        private var anim: ValueAnimator? = null

        fun stopAnim() { anim?.cancel() }

        fun setPalette(p: DacPalette, bj: Boolean = false) {
            val label = if (bj && p.badge.isNotEmpty()) "BJ · ${p.badge}" else p.badge
            val textChanged = label != text
            text = label
            val from = intArrayOf(primary, secondary, background)
            val fromGlow = glow
            val to = intArrayOf(p.primary, p.secondary, p.background)
            val ev = ArgbEvaluator()
            anim?.cancel()
            anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 350
                addUpdateListener {
                    val f = it.animatedValue as Float
                    primary = ev.evaluate(f, from[0], to[0]) as Int
                    secondary = ev.evaluate(f, from[1], to[1]) as Int
                    background = ev.evaluate(f, from[2], to[2]) as Int
                    glow = fromGlow + (p.glow - fromGlow) * f
                    invalidate()
                }
                start()
            }
            if (textChanged) requestLayout()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = (textPaint.measureText(text) + padH * 2 + 4f * d).toInt().coerceAtMost(maxW)
            setMeasuredDimension(w, barHeightPx)
        }

        override fun onDraw(canvas: Canvas) {
            if (text.isEmpty()) return
            val inset = 2f * d
            val top = (height - pillH) / 2f
            rect.set(inset, top, width - inset, top + pillH)
            val r = pillH / 2f
            fillPaint.color = (background and 0x00FFFFFF) or (0xE6 shl 24)
            canvas.drawRoundRect(rect, r, r, fillPaint)
            // Border: primary to secondary, so DRE (a different secondary) reads as a two-tone
            // edge. High power makes it thicker and adds a halo.
            borderPaint.strokeWidth = (0.8f + glow) * d
            borderPaint.shader = LinearGradient(rect.left, 0f, rect.right, 0f, primary, secondary, Shader.TileMode.CLAMP)
            borderPaint.setShadowLayer(glow * 4f * d, 0f, 0f, (primary and 0x00FFFFFF) or ((glow * 200).toInt().coerceIn(0, 255) shl 24))
            canvas.drawRoundRect(rect, r, r, borderPaint)
            textPaint.color = primary
            val avail = rect.width() - padH * 2
            val shown = if (textPaint.measureText(text) <= avail) text
                else text.take(textPaint.breakText(text, true, avail, null))
            val baseline = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(shown, rect.centerX() - textPaint.measureText(shown) / 2f, baseline, textPaint)
        }
    }
}
