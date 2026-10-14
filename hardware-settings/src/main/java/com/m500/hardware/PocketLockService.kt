package com.m500.hardware

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat

class PocketLockService : Service() {
    private var windowManager: WindowManager? = null
    private var shieldOverlay: View? = null
    private var lastTapTime = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val isLocked = intent?.getBooleanExtra(EXTRA_LOCKED, false) ?: false
        val allowVol = intent?.getBooleanExtra(EXTRA_ALLOW_VOL, false) ?: false

        Log.i(TAG, "PocketLockService command: isLocked=$isLocked, allowVol=$allowVol")

        if (isLocked) {
            showSystemShield(allowVol)
        } else {
            hideSystemShield()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun showSystemShield(allowVolumeWheel: Boolean) {
        if (shieldOverlay != null) return
        val wm = windowManager ?: return

        try {
            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            }

            val params = WindowManager.LayoutParams(
                MATCH_PARENT,
                MATCH_PARENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.FILL
            }

            val rootLayout = FrameLayout(this).apply {
                setBackgroundColor(Color.parseColor("#E6050A0E"))
                isClickable = true
                isFocusable = true
                isFocusableInTouchMode = true
                setOnTouchListener { _, _ ->
                    // Swallow 100% of all touches system-wide across every app
                    true
                }
            }

            // Cyber Card Box
            val cardBg = GradientDrawable().apply {
                setColor(Color.parseColor("#0B141C"))
                cornerRadius = dp(24f)
                setStroke(dp(2f).toInt(), Color.parseColor("#FFB300"))
            }

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = cardBg
                setPadding(dp(24f).toInt(), dp(28f).toInt(), dp(24f).toInt(), dp(28f).toInt())
            }

            // Lock Icon
            val iconView = android.widget.ImageView(this).apply {
                setImageResource(android.R.drawable.ic_lock_lock)
                setColorFilter(Color.parseColor("#FFB300"))
            }
            card.addView(iconView, LinearLayout.LayoutParams(dp(40f).toInt(), dp(40f).toInt()))

            // Title
            val titleView = TextView(this).apply {
                text = "POCKET LOCK ACTIVE"
                setTextColor(Color.parseColor("#FFB300"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                typeface = android.graphics.Typeface.MONOSPACE
                paint.isFakeBoldText = true
                gravity = Gravity.CENTER
                setPadding(0, dp(14f).toInt(), 0, dp(6f).toInt())
            }
            card.addView(titleView)

            // Subtitle
            val subtitleView = TextView(this).apply {
                text = "Touchscreen & side buttons locked"
                setTextColor(Color.parseColor("#B3FFFFFF"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.CENTER
            }
            card.addView(subtitleView)

            if (allowVolumeWheel) {
                val volBg = GradientDrawable().apply {
                    setColor(Color.parseColor("#13222E"))
                    cornerRadius = dp(8f)
                    setStroke(dp(1f).toInt(), Mascot.argb(Color.parseColor("#4DFFC56B")))
                }
                val volView = TextView(this).apply {
                    text = "Volume wheel still works"
                    setTextColor(Mascot.argb(Color.parseColor("#FFC56B")))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    paint.isFakeBoldText = true
                    gravity = Gravity.CENTER
                    background = volBg
                    setPadding(dp(12f).toInt(), dp(6f).toInt(), dp(12f).toInt(), dp(6f).toInt())
                }
                val volParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                    topMargin = dp(14f).toInt()
                }
                card.addView(volView, volParams)
            }

            val cardParams = FrameLayout.LayoutParams(dp(290f).toInt(), WRAP_CONTENT, Gravity.CENTER)
            rootLayout.addView(card, cardParams)

            wm.addView(rootLayout, params)
            shieldOverlay = rootLayout
            Log.i(TAG, "Pure View System-Wide Touch Shield ADDED and ACTIVE")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to add system touch shield", e)
        }
    }

    private fun hideSystemShield() {
        try {
            shieldOverlay?.let {
                windowManager?.removeView(it)
                shieldOverlay = null
                Log.i(TAG, "System-Wide Touch Shield REMOVED")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to remove system touch shield", e)
        }
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    override fun onDestroy() {
        hideSystemShield()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(CHANNEL_ID, "Pocket Lock", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(chan)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Pocket lock is on")
            .setContentText("Touchscreen and side buttons locked")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val TAG = "PocketLockService"
        private const val CHANNEL_ID = "pocket_lock_service"
        private const val NOTIF_ID = 9012
        const val EXTRA_LOCKED = "EXTRA_LOCKED"
        const val EXTRA_ALLOW_VOL = "EXTRA_ALLOW_VOL"

        fun start(context: Context, isLocked: Boolean, allowVol: Boolean) {
            val intent = Intent(context, PocketLockService::class.java).apply {
                putExtra(EXTRA_LOCKED, isLocked)
                putExtra(EXTRA_ALLOW_VOL, allowVol)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to start PocketLockService", e)
            }
        }
    }
}
