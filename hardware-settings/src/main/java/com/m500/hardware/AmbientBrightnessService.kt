package com.m500.hardware

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Hosts the screen-on trigger for the camera-derived ambient light reading.
 *
 * ACTION_SCREEN_ON cannot be declared in a manifest (Android delivers it only
 * to runtime-registered receivers), so something resident has to hold the
 * registration. Started from BootCompletedReceiver.
 *
 * Sampling is deliberately tied to screen-on rather than a timer: see the
 * pocket/chicken-and-egg note in AmbientBrightnessManager. It also means the
 * camera is opened only when the user is actually looking at the device, which
 * keeps the Android 12+ camera privacy indicator from flashing at random.
 */
// Deliberately NOT the main looper. The Wi-Fi fix does a synchronous HTTP lookup and a Wi-Fi
// scan, so on the main thread every poll died in NetworkOnMainThreadException - caught by the
// runCatching inside HioneLocationFusion, which is why this failed silently for a whole image.
private val locationThread = android.os.HandlerThread(
    "hione-location", android.os.Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
private val locationTicker = android.os.Handler(locationThread.looper)

class AmbientBrightnessService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var lastSampleMs = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    // SCREEN_ON and USER_PRESENT arrive back to back on wake;
                    // two overlapping samples evict each other off the camera.
                    val now = System.currentTimeMillis()
                    if (now - lastSampleMs < 3000) return
                    lastSampleMs = now
                    if (!cameraTypeForeground) goForeground()
                    scope.launch {
                        try {
                            val applied = AmbientBrightnessManager.sampleAndApply(ctx)
                            if (applied != null) Log.i(TAG, "ambient brightness -> $applied")
                        } catch (t: Throwable) {
                            Log.w(TAG, "ambient sample failed", t)
                        }
                    }
                }
            }
        }
    }

    /** True once startForeground succeeded WITH the camera type (needed for
     *  camera access from the background). */
    @Volatile private var cameraTypeForeground = false

    /**
     * A14 forbids a camera-type FGS started from BOOT_COMPLETED unless the app
     * is while-in-use exempt (e.g. SYSTEM_ALERT_WINDOW appop granted) - it
     * throws SecurityException from startForeground (seen live: 2 boot crashes).
     * Try camera type first; degrade to specialUse instead of crashing, and
     * re-try the camera upgrade on each screen-on until it sticks.
     */
    private fun goForeground() {
        val notif = buildNotification()
        try {
            startForeground(NOTIF_ID, notif,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            cameraTypeForeground = true
        } catch (t: Throwable) {
            Log.w(TAG, "camera-type FGS refused (boot start?); degrading to specialUse")
            try {
                startForeground(NOTIF_ID, notif,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } catch (t2: Throwable) {
                Log.w(TAG, "specialUse FGS also refused; running non-foreground", t2)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Must be a FOREGROUND service: a plain background service in a
        // non-foreground app is killed by A14 within hours and the screen-on
        // receiver dies with it (verified: pidof empty, stale ServiceRecord).
        goForeground()
        if (!AmbientCamera.isAvailable(this)) {
            // No camera enumerable - the whole feature is inert on this
            // hardware. Stop rather than sit resident doing nothing.
            Log.w(TAG, "no camera on this device; ambient brightness disabled")
            stopSelf()
            return
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter)
        // This is the always-alive hardware daemon process - host the Fn pocket-lock watcher too.
        FnLockDaemon.start(this)

        // Position, fused from GNSS, the modem and Wi-Fi, published to Settings.Global for the
        // whole OS. Started here because this service is the one that is always alive.
        //
        // The Wi-Fi poll is paced by the library itself: it backs off to two hours while
        // nothing changes and tightens to two minutes after the device has moved, and a look
        // that recognises the same APs returns the previous fix in about a millisecond without
        // touching the network or the disk. So calling it on a slow tick is cheap, and the tick
        // exists only so a device that has been carried somewhere notices reasonably soon.
        //
        // It runs on locationThread, not the main looper: a cold look is a Wi-Fi scan plus an
        // HTTP lookup, which the main thread is not allowed to do and would not want to wait for.
        HioneLocationFusion.start(this)
        locationTicker.post(object : Runnable {
            override fun run() {
                HioneLocationFusion.pollWifi(this@AmbientBrightnessService)
                locationTicker.postDelayed(this, 60_000L)
            }
        })

        Log.i(TAG, "ambient brightness service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // While-in-use camera eligibility is stamped per start attempt: a
        // BOOT_COMPLETED start is ineligible forever, but a later start from a
        // TOP app (the HioneOS launcher pokes us on every resume) or shell is
        // eligible - retry the camera-type upgrade on each start command.
        if (!cameraTypeForeground) goForeground()
        return START_STICKY
    }

    override fun onDestroy() {
        try { unregisterReceiver(screenReceiver) } catch (_: Throwable) {}
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): android.app.Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Ambient light sensor",
                NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) })
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ambient light sensor")
            .setContentText("Adjusting brightness on wake")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    companion object {
        private const val TAG = "AmbientBrightnessSvc"
        private const val CHANNEL_ID = "ambient_brightness"
        private const val NOTIF_ID = 4102

        fun start(ctx: Context) {
            try {
                val i = Intent(ctx, AmbientBrightnessService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(i)
                } else {
                    ctx.startService(i)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "could not start ambient brightness service", t)
            }
        }
    }
}
