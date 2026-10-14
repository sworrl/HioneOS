package com.caf.fmradio

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * The LIVE analyser and spectrogram, drawn on their own surface and their own thread.
 *
 * WHY A SURFACE. Drawn in Compose, every spectrum frame (25 a second) invalidated the app
 * window, and on this GPU a window frame means re-blending the full-screen artwork and every
 * translucent panel: ~13 ms of GPU per frame, 70% of frames janky. A SurfaceView is a separate
 * buffer that SurfaceFlinger composites under the window (through a hole the window punches),
 * so posting a new analyser frame touches nothing in the app window at all. The window only
 * redraws when something in it actually changes.
 *
 * THE THREAD. A HandlerThread at display priority runs its own Choreographer. Each vsync it
 * looks at [FmRadioManager.spectrum]'s latest value (a StateFlow read; thread-safe) and only if
 * it is a new frame, or the finger readout moved, or the size changed, does it lock the canvas,
 * draw, and post. Between spectrum frames it costs a callback and a reference compare. With no
 * audio it draws the tuned station's RSSI history instead, re-drawn only when that changes.
 * The vsync loop stops entirely when the view is not visible or the surface is gone.
 *
 * Everything that is a function of size (paths' x positions, grid, label layout, gradients)
 * is computed once per size on the render thread. The spectrogram is a [bins]x[ROWS] bitmap
 * used as a ring: one row written per frame, two blits to draw it newest-on-top.
 */
@SuppressLint("ViewConstructor")
class FmSpectrumSurfaceView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    private var renderer: Renderer? = null
    private var visibleNow = false

    init {
        holder.addCallback(this)
        // Opaque: the renderer fills every pixel, and an opaque layer is cheaper to composite
        // (and eligible for a hardware overlay).
        holder.setFormat(PixelFormat.OPAQUE)
        // The projectM background is also a surface at the default layer; without this their
        // order is undefined and the background can land on top of the analyser.
        setZOrderMediaOverlay(true)
    }

    override fun surfaceCreated(h: SurfaceHolder) {
        renderer = Renderer(h, resources.displayMetrics.density, resources.displayMetrics.scaledDensity).also {
            it.start()
            it.setActive(visibleNow)
        }
    }

    override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {
        renderer?.resize(width, height)
    }

    override fun surfaceDestroyed(h: SurfaceHolder) {
        // Must not return while the thread could still be drawing into this surface.
        renderer?.quitBlocking()
        renderer = null
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        visibleNow = isVisible
        renderer?.setActive(isVisible)
    }

    /** Hold to read: the x goes to the render thread, which draws the cursor and readout. */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                renderer?.setCursor(e.x)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> renderer?.setCursor(-1f)
        }
        return true
    }

    // ======================================================================== render thread

    private class Renderer(
        private val holder: SurfaceHolder,
        private val density: Float,
        private val scaledDensity: Float,
    ) : Choreographer.FrameCallback {

        private val thread = HandlerThread("FmSpectrum", Process.THREAD_PRIORITY_DISPLAY)
        private lateinit var handler: Handler
        private var choreographer: Choreographer? = null

        // Written from the UI thread, read on the render thread.
        @Volatile private var cursorX = -1f
        @Volatile private var dirty = true
        @Volatile private var width = 0
        @Volatile private var height = 0
        @Volatile private var active = false
        @Volatile private var alive = true
        private var looping = false
        private val drawLock = Any()

        // Render-thread state.
        private var lastFrame: FmRadioManager.SpectrumFrame? = null
        private var lastHistory: FloatArray? = null
        private var lastCursor = -1f
        private var layoutW = -1
        private var layoutH = -1
        private var lastAgeSec = -1L

        private val bins = QualcommFmHardwareEngine.SPECTRUM_BINS
        private val wf = Bitmap.createBitmap(bins, ROWS, Bitmap.Config.ARGB_8888).also {
            it.eraseColor(FmWaterfallPalette.ARGB[0])
        }
        private val row = IntArray(bins)
        private val rowTimes = LongArray(ROWS)
        private var head = 0
        private var filled = 0

        private val trace = Path()
        private val fill = Path()
        private val peakLines = FloatArray(bins * 4)
        private val binX = FloatArray(bins)
        private val srcA = Rect()
        private val dstA = Rect()
        private val srcB = Rect()
        private val dstB = Rect()
        private val tmpRect = RectF()

        // Layout, recomputed per size.
        private var gutter = 0f; private var top = 0f; private var plotW = 0f; private var plotH = 0f
        private var wfTop = 0f; private var wfH = 0f
        private var fLo = 25f; private var fHi = 16_000f
        private val minorX = ArrayList<Float>()
        private val majorX = ArrayList<Float>()
        private val majorLabel = ArrayList<String>()
        private val dbY = FloatArray(DB_LINES.size)

        private val bgPaint = Paint().apply { color = BG }
        private val gridPaint = Paint().apply { color = argb(0.10f, TEAL); strokeWidth = 1f }
        private val majorPaint = Paint().apply { color = argb(0.22f, TEAL); strokeWidth = 1f }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = argb(0.75f, TEAL) }
        private val dimLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = argb(0.5f, TEAL) }
        private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = argb(0.85f, TEAL); isFakeBoldText = true
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val tracePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = TEAL_BRIGHT; strokeJoin = Paint.Join.ROUND
        }
        private val peakPaint = Paint().apply { color = 0xE68EC5FF.toInt() }
        private val wfPaint = Paint().apply { isFilterBitmap = true }
        private val cursorPaint = Paint().apply { color = 0xB3FFFFFF.toInt() }
        private val readoutBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE60C0D10.toInt() }
        private val readoutText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); isFakeBoldText = true }
        private val barPaint = Paint()
        private var scanning = false
        private val scan = ScanArt(density, scaledDensity)

        fun start() {
            thread.start()
            handler = Handler(thread.looper)
            handler.post { choreographer = Choreographer.getInstance() }
        }

        fun resize(w: Int, h: Int) { width = w; height = h; dirty = true }

        fun setCursor(x: Float) { cursorX = x }

        fun setActive(on: Boolean) {
            active = on
            if (on) { dirty = true; handler.post { ensureLooping() } }
        }

        fun quitBlocking() {
            alive = false
            synchronized(drawLock) { }      // wait out a draw in progress
            handler.post { choreographer?.removeFrameCallback(this); looping = false }
            thread.quitSafely()
            runCatching { thread.join(500) }
        }

        private fun ensureLooping() {
            if (looping || !alive || !active) return
            val c = choreographer ?: return
            looping = true
            c.postFrameCallback(this)
        }

        override fun doFrame(frameTimeNanos: Long) {
            if (!alive || !active) { looping = false; return }
            choreographer?.postFrameCallback(this)

            // A band scan takes the panel over and animates every vsync while it runs. It only
            // ever costs this surface's buffer; the app window is not involved.
            val st = FmRadioManager.state.value
            if (st.isScanning) {
                if (!scanning) scan.begin()
                scanning = true
                frame { c, w, h -> scan.draw(c, st, w, h, frameTimeNanos) }
                return
            }
            if (scanning) { scanning = false; dirty = true }

            val f = FmRadioManager.spectrum.value
            val hasAudio = f.level.isNotEmpty()
            val newFrame = f !== lastFrame
            if (newFrame && hasAudio) push(f)
            val hist = if (hasAudio) null else FmRadioManager.state.value.signalHistory
            val cx = cursorX
            val ageSec = if (hasAudio) ageSeconds() else -1L
            val need = dirty || newFrame || cx != lastCursor || hist !== lastHistory || ageSec != lastAgeSec
            if (!need) return
            lastFrame = f; lastHistory = hist; lastCursor = cx; lastAgeSec = ageSec; dirty = false
            draw(f, hist, cx, ageSec)
        }

        private fun push(f: FmRadioManager.SpectrumFrame) {
            val lut = FmWaterfallPalette.ARGB
            val lv = f.level
            for (i in 0 until bins) {
                // A little contrast: the bottom of the dB range is hiss and should sit in the
                // navy, or the whole spectrogram turns into teal fog.
                val v = (((if (i < lv.size) lv[i] else 0f) - 0.12f) * 1.18f).coerceIn(0f, 1f)
                row[i] = lut[(v * 255f).toInt()]
            }
            head = (head - 1 + ROWS) % ROWS
            wf.setPixels(row, 0, bins, 0, head, bins, 1)
            rowTimes[head] = SystemClock.elapsedRealtime()
            if (filled < ROWS) filled++
            val b = f.binHz
            if (b.size >= 2 && (b.first() != fLo || b.last() != fHi)) {
                fLo = b.first(); fHi = b.last(); layoutW = -1
            }
        }

        private fun ageSeconds(): Long {
            if (filled < 2) return -1L
            val oldest = rowTimes[(head + filled - 1) % ROWS]
            return (SystemClock.elapsedRealtime() - oldest) / 1000L
        }

        private fun relayout(w: Int, h: Int) {
            layoutW = w; layoutH = h
            val d = density
            gutter = 24f * d
            top = 16f * d
            plotW = w - gutter - 4f * d
            plotH = h * 0.46f - top
            wfTop = top + plotH + 12f * d
            wfH = h - wfTop - 2f * d
            for (i in 0 until bins) binX[i] = gutter + plotW * i / (bins - 1)
            val logSpan = ln(fHi / fLo)
            fun xOf(hz: Float) = gutter + plotW * (ln(hz / fLo) / logSpan)
            minorX.clear(); majorX.clear(); majorLabel.clear()
            for (hz in MINOR_HZ) if (hz in fLo..fHi) minorX += xOf(hz)
            for (hz in MAJOR_HZ) if (hz in fLo..fHi) { majorX += xOf(hz); majorLabel += hzLabel(hz) }
            for (k in DB_LINES.indices) dbY[k] = top + plotH * (1f - (DB_LINES[k] - FLOOR_DB) / -FLOOR_DB)
            val sd = scaledDensity
            labelPaint.textSize = 6f * sd
            dimLabelPaint.textSize = 5.5f * sd
            titlePaint.textSize = 7f * sd
            readoutText.textSize = 8f * sd
            tracePaint.strokeWidth = 1.4f * d
            peakPaint.strokeWidth = 1.6f * d
            fillPaint.shader = LinearGradient(
                0f, top, 0f, top + plotH,
                intArrayOf(argb(0.55f, PINK), argb(0.40f, CYAN), argb(0.04f, TEAL)),
                floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP,
            )
        }

        private fun draw(f: FmRadioManager.SpectrumFrame, hist: FloatArray?, cx: Float, ageSec: Long) {
            frame { canvas, w, h ->
                if (hist == null) drawAnalyser(canvas, f, cx, ageSec) else drawFallback(canvas, hist, w, h)
            }
        }

        /** Lock, clear to the panel colour, draw, post. Guarded so a dying surface is never touched. */
        private inline fun frame(block: (Canvas, Int, Int) -> Unit) {
            synchronized(drawLock) {
                if (!alive) return
                val w = width; val h = height
                if (w <= 0 || h <= 0) return
                if (w != layoutW || h != layoutH) relayout(w, h)
                val canvas: Canvas = try {
                    holder.surface?.takeIf { it.isValid }?.let {
                        runCatching { holder.lockHardwareCanvas() }.getOrNull() ?: holder.lockCanvas()
                    }
                } catch (_: Exception) { null } ?: return
                try {
                    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)
                    block(canvas, w, h)
                } finally {
                    runCatching { holder.unlockCanvasAndPost(canvas) }
                }
            }
        }

        private fun drawAnalyser(c: Canvas, f: FmRadioManager.SpectrumFrame, cx: Float, ageSec: Long) {
            val d = density
            c.drawText("LIVE · FFT 4096 · ${hzLabel(fLo)}–${hzLabel(fHi)}Hz · HOLD TO READ", 4f * d, 9f * d, titlePaint)
            // Grid.
            for (k in DB_LINES.indices) {
                val y = dbY[k]
                c.drawLine(gutter, y, gutter + plotW, y, gridPaint)
                val s = DB_LINES[k].toInt().toString()
                val tw = labelPaint.measureText(s)
                c.drawText(s, gutter - tw - 3f, (y + labelPaint.textSize * 0.35f).coerceIn(top + 2f, top + plotH), labelPaint)
            }
            c.drawText("dBFS", 2f, top + plotH + dimLabelPaint.textSize + 1f, dimLabelPaint)
            for (x in minorX) c.drawLine(x, top, x, top + plotH, gridPaint)
            for (i in majorX.indices) {
                val x = majorX[i]
                c.drawLine(x, top, x, top + plotH + 3f, majorPaint)
                val s = majorLabel[i]
                val tw = labelPaint.measureText(s)
                c.drawText(s, (x - tw / 2f).coerceIn(gutter, layoutW - tw), top + plotH + labelPaint.textSize + 2f, labelPaint)
            }

            // Trace, fill and peak hold.
            val lv = f.level
            val pk = f.peak
            val n = minOf(lv.size, bins)
            if (n >= 2) {
                val base = top + plotH
                trace.rewind(); fill.rewind()
                fill.moveTo(binX[0], base)
                for (i in 0 until n) {
                    val y = base - lv[i].coerceIn(0f, 1f) * plotH
                    if (i == 0) trace.moveTo(binX[i], y) else trace.lineTo(binX[i], y)
                    fill.lineTo(binX[i], y)
                }
                fill.lineTo(binX[n - 1], base); fill.close()
                c.drawPath(fill, fillPaint)
                c.drawPath(trace, tracePaint)
                if (pk.size >= n) {
                    val half = (plotW / bins).coerceAtLeast(1.5f) * 0.45f
                    var k = 0
                    for (i in 0 until n) {
                        val v = pk[i]
                        if (v <= 0.02f) continue
                        val y = base - v.coerceIn(0f, 1f) * plotH
                        peakLines[k++] = binX[i] - half; peakLines[k++] = y
                        peakLines[k++] = binX[i] + half; peakLines[k++] = y
                    }
                    if (k > 0) c.drawLines(peakLines, 0, k, peakPaint)
                }
            }

            // Spectrogram, newest row on top. Same x mapping: the bins are log-uniform.
            if (wfH > 0f) {
                val rowH = wfH / ROWS
                val firstRows = ROWS - head
                val x0 = gutter.roundToInt(); val x1 = (gutter + plotW).roundToInt()
                val y0 = wfTop.roundToInt()
                val h1 = (firstRows * rowH).roundToInt()
                srcA.set(0, head, bins, ROWS); dstA.set(x0, y0, x1, y0 + h1)
                c.drawBitmap(wf, srcA, dstA, wfPaint)
                if (head > 0) {
                    srcB.set(0, 0, bins, head); dstB.set(x0, y0 + h1, x1, (wfTop + wfH).roundToInt())
                    c.drawBitmap(wf, srcB, dstB, wfPaint)
                }
                c.drawText("now", 2f, wfTop + dimLabelPaint.textSize, dimLabelPaint)
                if (ageSec >= 1) c.drawText("-${ageSec}s", 2f, wfTop + wfH - 2f, dimLabelPaint)
            }

            // Finger readout.
            if (cx >= gutter && cx <= gutter + plotW && n >= 2) {
                val frac = (cx - gutter) / plotW
                val hz = fLo * exp(frac * ln(fHi / fLo))
                val bi = (frac * (n - 1)).roundToInt().coerceIn(0, n - 1)
                val db = (FLOOR_DB * (1f - lv[bi])).roundToInt()
                c.drawRect(cx - 0.5f * d, top, cx + 0.5f * d, wfTop + wfH, cursorPaint)
                val txt = (if (hz >= 1000f) String.format(java.util.Locale.US, "%.2f kHz", hz / 1000f)
                           else "${hz.roundToInt()} Hz") + " · $db dB"
                val tw = readoutText.measureText(txt)
                val bx = (cx - tw / 2f).coerceIn(gutter, layoutW - tw - 4f)
                tmpRect.set(bx - 4f, top + 2f, bx + tw + 4f, top + 6f + readoutText.textSize)
                c.drawRoundRect(tmpRect, 6f, 6f, readoutBg)
                c.drawText(txt, bx, top + 2f + readoutText.textSize, readoutText)
            }
        }

        /**
         * No audio reaching the app (a wired route without a verified siphon): the tuned
         * station's own RSSI over time, which at least moves when the antenna does.
         */
        private fun drawFallback(c: Canvas, hist: FloatArray, w: Int, h: Int) {
            val d = density
            c.drawText("LIVE · SIGNAL OF THE TUNED STATION · NO AUDIO TO ANALYZE ON THIS OUTPUT", 4f * d, 9f * d, titlePaint)
            val t = 16f * d
            val traceH = h * 0.42f
            for (g in 1..3) {
                val gy = t + traceH * g / 4f
                c.drawLine(0f, gy, w.toFloat(), gy, gridPaint)
            }
            val cols = hist.size
            if (cols < 2) return
            val barTop = t + traceH + 6f * d
            val barH = h - barTop - 4f * d
            val colW = w.toFloat() / cols
            for (i in 0 until cols) {
                val v = hist[i].coerceIn(0f, 1f)
                barPaint.color = FmWaterfallPalette.ARGB[(v * 255f).toInt()]
                val x = w - (i + 1) * colW
                c.drawRect(x, barTop + barH - v * barH, x + colW + 0.5f, barTop + barH, barPaint)
            }
            trace.rewind()
            val dx = w.toFloat() / (cols - 1)
            for (i in 0 until cols) {
                val x = w - i * dx
                val y = t + traceH - hist[i].coerceIn(0f, 1f) * (traceH - 4f)
                if (i == 0) trace.moveTo(x, y) else trace.lineTo(x, y)
            }
            tracePaint.color = TEAL
            c.drawPath(trace, tracePaint)
            tracePaint.color = TEAL_BRIGHT
        }
    }

    // ======================================================================== scan art

    /**
     * What the panel shows while the band is being swept: an honest instrument with something
     * fun layered under it.
     *
     * The instrument: a band axis in MHz, the RSSI of every channel measured so far drawn as a
     * ribbon, a scan head at the exact channel the chip is on (derived from the engine's
     * progress, the same arithmetic the sweep loop uses), a readout of frequency, last RSSI and
     * channel count, what the sweep is doing right now, and every hit marked with a burst and its
     * call sign from the catalogue. The audio is muted for the sweep and the panel says so.
     *
     * The fun: a field of teal and pink streaks drifting through a slowly turning flow field,
     * sped up by the signal the sweep has found under them and lit by the scan head as it
     * passes; and the ribbon doubled into three phase-shifted strands that breathe. All of it is
     * a few hundred line and path calls a frame on this thread, with no allocation.
     */
    private class ScanArt(private val d: Float, private val sd: Float) {
        private val n = 140
        private val px = FloatArray(n); private val py = FloatArray(n)
        private val vx = FloatArray(n); private val vy = FloatArray(n)
        private val rnd = java.util.Random(39)
        private var seeded = false
        private var headX = -1f
        private val hitBirth = HashMap<Int, Long>()
        private val hitName = HashMap<Int, String>()
        private var lastHitKhz = -1
        private var lastHitAt = 0L
        private val ribbon = Path()
        private val ribbonFill = Path()

        private val streak = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeWidth = 1.6f * d }
        private val ribbonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d; strokeJoin = Paint.Join.ROUND }
        private val ribbonFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val beamPaint = Paint()
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val corePaint = Paint().apply { color = 0xFFFFD9EC.toInt(); strokeWidth = 1.5f * d }
        private val tickPaint = Paint().apply { color = argb(0.35f, TEAL); strokeWidth = 1f }
        private val axisText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = argb(0.7f, TEAL); textSize = 6.5f * sd }
        private val hudBig = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = 9f * sd; isFakeBoldText = true }
        private val hudLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TEAL_BRIGHT; textSize = 7.5f * sd }
        private val hudFound = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PINK; textSize = 7.5f * sd; isFakeBoldText = true }
        private val hudMuted = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD54F.toInt(); textSize = 6.5f * sd }
        private val hitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PINK; style = Paint.Style.STROKE; strokeWidth = 1.5f * d }
        private val hitDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PINK }
        private val hitText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = 6.5f * sd; isFakeBoldText = true }
        private var shadersFor = -1f

        fun begin() {
            hitBirth.clear(); hitName.clear(); lastHitKhz = -1; lastHitAt = 0L; headX = -1f; seeded = false
        }

        private fun ensureShaders(h: Float) {
            if (shadersFor == h) return
            shadersFor = h
            val bw = 22f * d
            beamPaint.shader = LinearGradient(-bw, 0f, bw, 0f,
                intArrayOf(0, argb(0.45f, PINK), argb(0.85f, 0xFFFFD9EC.toInt()), argb(0.45f, CYAN), 0),
                floatArrayOf(0f, 0.38f, 0.5f, 0.62f, 1f), Shader.TileMode.CLAMP)
            glowPaint.shader = android.graphics.RadialGradient(0f, 0f, 30f * d,
                intArrayOf(argb(0.9f, 0xFFFFD9EC.toInt()), argb(0.35f, PINK), 0), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
        }

        fun draw(c: Canvas, st: FmState, w: Int, h: Int, nanos: Long) {
            val t = (nanos / 1_000_000L % 3_600_000L) / 1000f
            val now = SystemClock.elapsedRealtime()
            val band = st.band
            val span = (band.highKHz - band.lowKHz).toFloat().coerceAtLeast(1f)
            val channels = (band.highKHz - band.lowKHz) / band.stepKHz + 1
            val idx = ((st.scanProgress * channels).roundToInt() - 1).coerceIn(0, channels - 1)
            val khz = band.lowKHz + idx * band.stepKHz
            val m = 8f * d
            val plotW = w - 2 * m
            fun xOf(k: Int) = m + (k - band.lowKHz) / span * plotW
            val artTop = 36f * d
            val axisH = 14f * d
            val base = h - axisH - 4f * d
            val amp = (base - artTop) * 0.8f
            ensureShaders(h.toFloat())

            // Scan head eases toward the channel being measured, so it glides rather than ticks.
            val target = xOf(khz)
            headX = if (headX < 0f) target else headX + (target - headX) * 0.35f

            val prof = st.bandProfile
            val measured = minOf(idx + 1, prof.size)

            // ---- particle field
            if (!seeded) {
                for (i in 0 until n) { px[i] = m + rnd.nextFloat() * plotW; py[i] = artTop + rnd.nextFloat() * (base - artTop) }
                seeded = true
            }
            val sigma2 = (42f * d) * (42f * d) * 2f
            for (i in 0 until n) {
                val x = px[i]; val y = py[i]
                val a = (kotlin.math.sin(x * 0.011f / d + t * 0.6f) + kotlin.math.cos(y * 0.016f / d - t * 0.45f)) * Math.PI.toFloat()
                // Energy from the signal measured under this particle: busy band, busy air.
                val ch = (((x - m) / plotW) * (channels - 1)).roundToInt().coerceIn(0, channels - 1)
                val e = if (ch < measured) prof[ch] else 0.05f
                val sp = (0.35f + 2.2f * e) * d
                vx[i] = vx[i] * 0.85f + kotlin.math.cos(a) * sp * 0.15f + 0.25f * d * 0.15f
                vy[i] = vy[i] * 0.85f + kotlin.math.sin(a) * sp * 0.15f
                var nx = x + vx[i] * 4f; var ny = y + vy[i] * 4f
                if (nx < m) nx += plotW; if (nx > m + plotW) nx -= plotW
                if (ny < artTop) ny += base - artTop; if (ny > base) ny -= base - artTop
                px[i] = nx; py[i] = ny
                val dx = nx - headX
                val glow = exp(-(dx * dx) / sigma2)
                val alpha = (0.18f + 0.35f * e + 0.6f * glow).coerceAtMost(1f)
                streak.color = argb(alpha, if (i % 3 == 0) PINK else if (i % 3 == 1) TEAL else CYAN)
                val tail = 5f + 7f * glow
                if (kotlin.math.abs(vx[i] * tail) < plotW / 4) c.drawLine(nx - vx[i] * tail, ny - vy[i] * tail, nx, ny, streak)
            }

            // ---- the measured band as three breathing strands
            if (measured >= 2) {
                for (j in 0 until 3) {
                    ribbon.rewind()
                    for (i in 0 until measured) {
                        val x = xOf(band.lowKHz + i * band.stepKHz)
                        val p = prof[i].coerceIn(0f, 1f)
                        val wobble = kotlin.math.sin(i * 0.21f + t * (1.3f + j * 0.5f) + j * 2.1f) * (2f + 5f * p) * d * (if (j == 0) 0.3f else 1f)
                        val y = base - p * amp + wobble
                        if (i == 0) ribbon.moveTo(x, y) else ribbon.lineTo(x, y)
                    }
                    if (j == 0) {
                        ribbonFill.rewind(); ribbonFill.addPath(ribbon)
                        ribbonFill.lineTo(xOf(band.lowKHz + (measured - 1) * band.stepKHz), base)
                        ribbonFill.lineTo(m, base); ribbonFill.close()
                        ribbonFillPaint.shader = null
                        ribbonFillPaint.color = argb(0.16f, TEAL)
                        c.drawPath(ribbonFill, ribbonFillPaint)
                    }
                    ribbonPaint.color = when (j) { 0 -> TEAL_BRIGHT; 1 -> argb(0.45f, CYAN); else -> argb(0.4f, PINK) }
                    c.drawPath(ribbon, ribbonPaint)
                }
            }

            // ---- hits: a burst when they land, then a marker with the call sign
            for (hit in st.scanResults) {
                if (hit.freqKHz !in hitBirth) {
                    hitBirth[hit.freqKHz] = now
                    hitName[hit.freqKHz] = stationOn(hit.freqKHz, st.nearbyStations)?.let { if (it.isJapan) it.chipLabel else it.call } ?: fmtMhz(hit.freqKHz)
                    lastHitKhz = hit.freqKHz; lastHitAt = now
                }
            }
            var label = 0
            for ((k, born) in hitBirth) {
                val x = xOf(k)
                val ci = ((k - band.lowKHz) / band.stepKHz).coerceIn(0, channels - 1)
                val y = base - (if (ci < prof.size) prof[ci] else 0f) * amp
                val age = now - born
                if (age < 900) {
                    val f = age / 900f
                    hitPaint.alpha = ((1f - f) * 255).toInt()
                    c.drawCircle(x, y, 4f * d + f * 24f * d, hitPaint)
                    for (r in 0 until 6) {
                        val ang = r * (Math.PI.toFloat() / 3f) + f
                        val r0 = 6f * d + f * 14f * d; val r1 = r0 + 6f * d
                        c.drawLine(x + kotlin.math.cos(ang) * r0, y + kotlin.math.sin(ang) * r0,
                                   x + kotlin.math.cos(ang) * r1, y + kotlin.math.sin(ang) * r1, hitPaint)
                    }
                }
                c.drawCircle(x, y, 2.6f * d, hitDot)
                val name = hitName[k] ?: continue
                val tw = hitText.measureText(name)
                val ly = y - 6f * d - (label % 2) * 9f * d
                c.drawText(name, (x - tw / 2f).coerceIn(m, w - m - tw), ly.coerceAtLeast(artTop + 8f * d), hitText)
                label++
            }

            // ---- the scan head: a light the field reacts to
            c.save(); c.translate(headX, 0f)
            c.drawRect(-22f * d, artTop - 6f * d, 22f * d, base, beamPaint)
            c.restore()
            c.drawLine(headX, artTop - 6f * d, headX, base + 3f * d, corePaint)
            val headY = base - (if (idx < prof.size && idx < measured) prof[idx] else 0f) * amp
            c.save(); c.translate(headX, headY)
            c.drawCircle(0f, 0f, 30f * d, glowPaint)
            c.restore()

            // ---- band axis
            val step = if (span > 20_000f) 4 else 2
            var mhzTick = (band.lowKHz / 1000 / step + 1) * step
            while (mhzTick * 1000 <= band.highKHz) {
                val x = xOf(mhzTick * 1000)
                c.drawLine(x, base + 2f * d, x, base + 6f * d, tickPaint)
                val s = mhzTick.toString()
                c.drawText(s, x - axisText.measureText(s) / 2f, base + 6f * d + axisText.textSize, axisText)
                mhzTick += step
            }

            // ---- readout: where, what, how far along, and the honest bit about audio
            val rssiIdx = (measured - 1).coerceAtLeast(0)
            val rssi = if (prof.isNotEmpty() && measured > 0) (prof[rssiIdx] * QualcommFmHardwareEngine.SWEEP_RSSI_FULL_SCALE).roundToInt() else null
            c.drawText("SCANNING ${fmtMhz(khz)} MHz · RSSI ${rssi ?: "—"} · ${idx + 1}/$channels", 4f * d, 11f * d, hudBig)
            if (lastHitKhz > 0 && now - lastHitAt < 1600) {
                c.drawText("station found: ${hitName[lastHitKhz] ?: ""} ${fmtMhz(lastHitKhz)}", 4f * d, 21f * d, hudFound)
            } else {
                c.drawText(if (idx == 0) "muting audio, starting the sweep" else "measuring signal · ${hitBirth.size} found so far",
                           4f * d, 21f * d, hudLine)
            }
            c.drawText("audio muted while scanning", 4f * d, 30f * d, hudMuted)
        }
    }

    companion object {
        /** Spectrogram depth. At the engine's 25 frames a second, six seconds of history. */
        private const val ROWS = 150
        /** The engine maps −90..0 dBFS to 0..1. */
        private const val FLOOR_DB = -90f
        private val DB_LINES = floatArrayOf(0f, -20f, -40f, -60f, -80f)
        /** Labelled frequencies; the eye reads decades and their halves, not bin numbers. */
        private val MAJOR_HZ = floatArrayOf(50f, 100f, 200f, 500f, 1000f, 2000f, 5000f, 10000f)
        private val MINOR_HZ = floatArrayOf(30f, 40f, 60f, 70f, 80f, 90f, 300f, 400f, 600f, 700f, 800f, 900f,
            3000f, 4000f, 6000f, 7000f, 8000f, 9000f, 15000f)

        private const val BG = 0xFF0C0D10.toInt()
        private const val TEAL = 0xFFFFAB2E.toInt()
        private const val TEAL_BRIGHT = 0xFFFFD08A.toInt()
        private const val CYAN = 0xFFFFC56B.toInt()
        private const val PINK = 0xFF8EC5FF.toInt()

        private fun argb(alpha: Float, rgb: Int): Int =
            ((alpha.coerceIn(0f, 1f) * 255f).roundToInt() shl 24) or (rgb and 0xFFFFFF)

        fun hzLabel(hz: Float): String {
            if (hz < 1000f) return hz.roundToInt().toString()
            val k = hz / 1000f
            // 15.68 kHz (the top bin at a 32 kHz capture) reads better as "16k", 2000 as "2k".
            if (k >= 10f) return "${k.roundToInt()}k"
            val tenths = (k * 10f).roundToInt()
            return if (tenths % 10 == 0) "${tenths / 10}k" else "${tenths / 10}.${tenths % 10}k"
        }
    }
}
