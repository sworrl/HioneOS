package com.hione.tools.clock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.service.dreams.DreamService
import android.view.View
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Screen saver ("daydream"). Stock DeskClock provided the default one; this keeps the option
 * alive when it is gone. Plain View drawing, not Compose: a DreamService has no lifecycle
 * owner for Compose to hang off, and redrawing one line of text once a second does not need it.
 *
 * Dim teal on black, and the text drifts to a new spot every minute so nothing burns in.
 */
class ClockDream : DreamService() {
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = false
        setContentView(DreamClockView(this))
    }
}

private class DreamClockView(ctx: Context) : View(ctx) {
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(57, 197, 187); typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL); textAlign = Paint.Align.CENTER
    }
    private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(126, 154, 160); textAlign = Paint.Align.CENTER
    }
    private val alarmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 64, 129); textAlign = Paint.Align.CENTER
    }
    private var driftX = 0f
    private var driftY = 0f
    private var lastMinute = -1
    private var alarmText: String? = null

    private val tick = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, 1000 - System.currentTimeMillis() % 1000)
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(tick) }
    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val c = Calendar.getInstance()
        val minute = c.get(Calendar.MINUTE)
        if (minute != lastMinute) {
            lastMinute = minute
            val r = java.util.Random(SystemClock.elapsedRealtime())
            driftX = (r.nextFloat() - 0.5f) * width * 0.3f
            driftY = (r.nextFloat() - 0.5f) * height * 0.4f
            // Read the store once a minute, not on every frame.
            alarmText = AlarmMath.soonest(ClockStore.alarms(context))?.second
                ?.takeIf { it - System.currentTimeMillis() < 24 * 3_600_000L }
                ?.let { "Alarm " + ClockFormat.whenText(context, it) }
        }
        val cx = width / 2f + driftX
        val cy = height / 2f + driftY
        timePaint.textSize = width * 0.22f
        datePaint.textSize = width * 0.05f
        alarmPaint.textSize = width * 0.045f
        canvas.drawText(ClockFormat.time(context, c.get(Calendar.HOUR_OF_DAY), minute), cx, cy, timePaint)
        canvas.drawText(SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date()), cx, cy + datePaint.textSize * 2f, datePaint)
        alarmText?.let { canvas.drawText(it, cx, cy + datePaint.textSize * 4f, alarmPaint) }
    }
}
