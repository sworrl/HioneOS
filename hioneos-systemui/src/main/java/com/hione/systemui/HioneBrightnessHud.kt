package com.hione.systemui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Brightness HUD: a warm glass bar across the top of the screen, just under the status bar.
 *
 * It is deliberately nothing like the volume HUD, which is a vertical teal/pink bar on the right
 * edge (or a small teal capsule). This one is horizontal and full width, amber to warm white,
 * with a sun whose rays grow with the level, and it slides down from the top instead of in from
 * the side. Same HioneGlass sheen and rim (HioneGlass.CanvasGlass), warm accent instead of teal.
 *
 *   [ sun ]  Brightness ..................  62%  [AUTO]
 *            [=========amber -> white====o------]
 *
 *  - Drag anywhere on the bar to set the level. Tap AUTO to switch the camera-based auto
 *    brightness (Settings.Global m500_ambient_auto_brightness, owned by hardware-settings).
 *  - Hides 2s after the last change or touch. The window only exists while shown, so it never
 *    sits over the status bar pull or an app's toolbar for longer than that.
 *  - It is touchable while shown: every way it can appear is the user doing something to the
 *    brightness, so the next touch is most likely them reaching for it.
 *  - Drawn with android.graphics on one View; the slide and fade are RenderNode properties
 *    (translationY / alpha), so animating it never re-runs onDraw.
 *
 * Shown by (see HioneNotificationShadeService): the brightness keys, the stock SystemUI
 * BrightnessDialog (closed and replaced by this), and the broadcast [ACTION_SHOW], which any
 * HioneOS app can send after changing brightness, and which is the way to bring it up from adb.
 * Not shown while the screen is off, the keyguard is up, or the Hione shade is open (the shade
 * has its own slider).
 */
class HioneBrightnessHud(
    private val ctx: Context,
    private val windowManager: WindowManager,
    private val isShadeOpen: () -> Boolean
) {
    companion object {
        const val TAG = "HioneBrightnessHud"
        const val ACTION_SHOW = "com.hione.systemui.action.SHOW_BRIGHTNESS_HUD"
        /** hardware-settings' camera-based auto brightness (AmbientBrightnessManager.KEY_ENABLED). */
        const val KEY_AUTO = "m500_ambient_auto_brightness"
        /** Same floor as the shade slider: below 8 the panel is effectively off. */
        const val MIN = 8
        const val MAX = 255
        private const val AUTO_HIDE_MS = 2000L
        private const val HEIGHT_DP = 66f
        private const val SIDE_MARGIN_DP = 10f
        /** Key step: 1/16 of the range, so 16 presses go end to end. */
        private const val KEY_STEPS = 16

        private const val AMBER = 0xFFFFB300.toInt()
        private const val AMBER_DEEP = 0xFFFF8F00.toInt()
        private const val GOLD = 0xFFFFD54F.toInt()
        private const val WARM_WHITE = 0xFFFFF8E1.toInt()
    }

    private val main = Handler(Looper.getMainLooper())
    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private var view: HudView? = null
    private val hideRunnable = Runnable { hide() }

    // Settings writes go through one background thread, coalesced: a fast drag produces many
    // values but only the newest one is ever written.
    private val io = Executors.newSingleThreadExecutor()
    private val pendingLevel = AtomicInteger(-1)

    // ------------------------------------------------------------------ settings

    fun readBrightness(): Int =
        runCatching { Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) }.getOrDefault(128)

    fun readAuto(): Boolean =
        runCatching { Settings.Global.getInt(ctx.contentResolver, KEY_AUTO, 1) == 1 }.getOrDefault(true)

    private fun writeBrightness(value: Int) {
        val v = value.coerceIn(MIN, MAX)
        if (pendingLevel.getAndSet(v) != -1) return          // a write is already queued; it takes the newest
        io.execute {
            val next = pendingLevel.getAndSet(-1)
            if (next < 0) return@execute
            runCatching {
                val cr = ctx.contentResolver
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, next)
            }.onFailure { RootShell.execFast("settings put system screen_brightness $next") }
        }
    }

    private fun writeAuto(on: Boolean) {
        io.execute {
            runCatching { Settings.Global.putInt(ctx.contentResolver, KEY_AUTO, if (on) 1 else 0) }
                .onFailure { RootShell.execFast("settings put global $KEY_AUTO " + (if (on) 1 else 0)) }
        }
    }

    private fun fracOf(b: Int) = ((b - MIN).toFloat() / (MAX - MIN)).coerceIn(0f, 1f)
    private fun levelOf(f: Float) = (MIN + f.coerceIn(0f, 1f) * (MAX - MIN)).roundToInt()

    // ------------------------------------------------------------------ show / hide

    private fun suppressed(): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm?.isInteractive == false) return true
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        if (km?.isKeyguardLocked == true) return true
        if (isShadeOpen()) return true
        return false
    }

    /** Brightness keys: one step up (+1) or down (-1), then show. */
    fun step(dir: Int) {
        val f = fracOf(readBrightness()) + dir.toFloat() / KEY_STEPS
        val next = levelOf(f)
        writeBrightness(next)
        show(next)
    }

    /** Show (or refresh) with [level], or the current setting when null. */
    fun show(level: Int? = null) {
        if (suppressed()) { Log.i(TAG, "suppressed (screen off, keyguard or shade open)"); return }
        val b = level ?: readBrightness()
        val auto = readAuto()
        main.post {
            val v = view ?: HudView(ctx).also { hv ->
                val dm = ctx.resources.displayMetrics
                val p = WindowManager.LayoutParams(
                    dm.widthPixels - 2 * dp(SIDE_MARGIN_DP).toInt(), dp(HEIGHT_DP).toInt(),
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    // Below the status bar, so the strip that opens the shade stays uncovered.
                    y = statusBarPx() + dp(6f).toInt()
                }
                try { windowManager.addView(hv, p) } catch (t: Throwable) { Log.w(TAG, "addView: $t"); return@post }
                view = hv
                hv.enter()
            }
            v.bind(fracOf(b), auto)
            scheduleHide()
        }
    }

    private fun statusBarPx(): Int {
        val id = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
        val px = if (id != 0) runCatching { ctx.resources.getDimensionPixelSize(id) }.getOrDefault(0) else 0
        return if (px > 0) px else dp(24f).toInt()
    }

    private fun scheduleHide() {
        main.removeCallbacks(hideRunnable)
        main.postDelayed(hideRunnable, AUTO_HIDE_MS)
    }

    fun hide() {
        main.removeCallbacks(hideRunnable)
        val v = view ?: return
        if (v.held) { scheduleHide(); return }               // never pull it out from under a finger
        v.exit { runCatching { windowManager.removeView(v) }; if (view === v) view = null }
    }

    fun hideImmediately() {
        main.removeCallbacks(hideRunnable)
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
    }

    fun destroy() {
        hideImmediately()
        io.shutdown()
    }

    // ------------------------------------------------------------------ the bar

    private inner class HudView(context: Context) : View(context) {
        private var frac = 0.5f
        private var auto = true
        var held = false
            private set
        private var downX = 0f
        private var onChip = false
        private var moved = false
        private var lastDecile = -1

        private val glassFx = HioneGlass.CanvasGlass(density)
        private val card = RectF()
        private val track = RectF()
        private val fill = RectF()
        private val chip = RectF()
        private val cardR = dp(22f)

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val groovePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 }
        private val grooveRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1f); color = 0x55FFB300 }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = dp(2f) }
        private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = GOLD; textSize = dp(10f); typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); letterSpacing = 0.12f
        }
        private val pctPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = WARM_WHITE; textSize = dp(16f); typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textAlign = Paint.Align.RIGHT
        }
        private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val chipText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = dp(10f); typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textAlign = Paint.Align.CENTER
        }

        init { isClickable = true; alpha = 0f }

        fun bind(f: Float, a: Boolean) { frac = f; auto = a; invalidate() }

        /** Slide down from above the screen edge and fade up. */
        fun enter() {
            translationY = -dp(HEIGHT_DP)
            animate().translationY(0f).alpha(1f)
                .setDuration(HioneMotion.ms(260).toLong())
                .setInterpolator(HioneMotion.overshoot(0.7f))
                .start()
        }

        fun exit(end: () -> Unit) {
            animate().translationY(-dp(HEIGHT_DP) * 0.8f).alpha(0f)
                .setDuration(HioneMotion.ms(180).toLong())
                .setInterpolator(HioneMotion.decel())
                .withEndAction(end)
                .start()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            card.set(0f, 0f, w.toFloat(), h.toFloat())
            bgPaint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
                intArrayOf(0xF2261B0B.toInt(), 0xF70D0905.toInt()), null, Shader.TileMode.CLAMP)
            val pad = dp(14f)
            val sunBox = dp(44f)
            val chipW = dp(48f); val chipH = dp(24f)
            val pctW = dp(48f)
            chip.set(w - pad - chipW, h / 2f - chipH / 2f, w - pad, h / 2f + chipH / 2f)
            val trackLeft = pad + sunBox + dp(6f)
            val trackRight = chip.left - pctW - dp(6f)
            val trackH = dp(12f)
            val ty = h * 0.64f
            track.set(trackLeft, ty - trackH / 2f, trackRight, ty + trackH / 2f)
            fillPaint.shader = LinearGradient(track.left, 0f, track.right, 0f,
                intArrayOf(AMBER_DEEP, GOLD, WARM_WHITE), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val h = height.toFloat()
            // warm glass card
            canvas.drawRoundRect(card, cardR, cardR, bgPaint)
            glassFx.drawOver(canvas, card, cardR, AMBER, WARM_WHITE)

            // sun: core grows a little, rays grow a lot and turn slightly with the level
            val cx = dp(14f) + dp(22f); val cy = h / 2f
            val core = dp(5.5f) + dp(2f) * frac
            corePaint.shader = RadialGradient(cx - core * 0.3f, cy - core * 0.3f, core * 1.6f,
                intArrayOf(WARM_WHITE, GOLD, AMBER), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, core, corePaint)
            val rayIn = core + dp(3f)
            val rayLen = dp(1.5f) + dp(6.5f) * frac
            rayPaint.color = AMBER
            rayPaint.alpha = (110 + 145 * frac).toInt()
            val turn = Math.toRadians(30.0 * frac)
            for (i in 0 until 8) {
                val ang = turn + i * Math.PI / 4
                val c = cos(ang).toFloat(); val s = sin(ang).toFloat()
                canvas.drawLine(cx + c * rayIn, cy + s * rayIn, cx + c * (rayIn + rayLen), cy + s * (rayIn + rayLen), rayPaint)
            }

            // label + level
            canvas.drawText("BRIGHTNESS", track.left, h * 0.36f, labelPaint)
            canvas.drawText("${(frac * 100).roundToInt()}%", chip.left - dp(8f), h / 2f + dp(6f), pctPaint)

            // track: recessed groove, warm fill, liquid bead
            val r = track.height() / 2f
            canvas.drawRoundRect(track, r, r, groovePaint)
            canvas.drawRoundRect(track, r, r, grooveRim)
            val fx = track.left + track.width() * frac
            fill.set(track.left, track.top, fx.coerceAtLeast(track.left + track.height()), track.bottom)
            canvas.drawRoundRect(fill, r, r, fillPaint)
            glassFx.drawBead(canvas, fx.coerceIn(track.left + dp(8f), track.right - dp(8f)), track.centerY(),
                dp(if (held) 10f else 8.5f), dp(if (held) 10f else 8.5f), AMBER)

            // AUTO chip
            val cr = chip.height() / 2f
            if (auto) {
                chipPaint.style = Paint.Style.FILL; chipPaint.color = AMBER
                chipText.color = 0xFF1A1206.toInt()
            } else {
                chipPaint.style = Paint.Style.STROKE; chipPaint.strokeWidth = dp(1.2f); chipPaint.color = 0x99FFB300.toInt()
                chipText.color = 0xCCFFD54F.toInt()
            }
            canvas.drawRoundRect(chip, cr, cr, chipPaint)
            canvas.drawText("AUTO", chip.centerX(), chip.centerY() + dp(3.5f), chipText)
        }

        private fun setFromX(x: Float) {
            val f = ((x - track.left) / track.width()).coerceIn(0f, 1f)
            frac = f
            val decile = (f * 10).toInt()
            if (decile != lastDecile) { if (lastDecile >= 0) HioneHaptics.tick(this); lastDecile = decile }
            writeBrightness(levelOf(f))
            invalidate()
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    main.removeCallbacks(hideRunnable)
                    held = true; moved = false; downX = e.x
                    lastDecile = (frac * 10).toInt()
                    // the chip gets a little extra room: it is a small target on a 4" screen
                    onChip = e.x >= chip.left - dp(8f)
                    if (!onChip) setFromX(e.x)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!moved && abs(e.x - downX) > dp(4f)) moved = true
                    if (moved) { onChip = false; setFromX(e.x) }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    held = false
                    if (onChip && !moved) {
                        auto = !auto
                        writeAuto(auto)
                        HioneHaptics.confirm(this)
                    }
                    invalidate()
                    scheduleHide()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    held = false; invalidate(); scheduleHide(); return true
                }
            }
            return super.onTouchEvent(e)
        }
    }
}
