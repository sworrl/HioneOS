package com.android.systemui

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * AOSP-standard SystemUIService endpoint invoked by Android system_server on boot.
 * Keeps HioneOS SystemUI services, Cyber Status Bar HUD, and Shade active.
 */
class SystemUIService : Service() {

    companion object {
        private const val TAG = "HioneSystemUIService"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "HioneOS SystemUIService initialized by system_server.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "HioneOS SystemUIService running (START_STICKY).")
        return START_STICKY
    }

    // NOTE: this used to start com.hione.launcher's HioneSystemGestureService, which drew a
    // SECOND navigation pill. That service is gone and there is exactly ONE nav pill now —
    // the one HioneNotificationShadeService (accessibility) draws. Do not re-add a starter here.

    override fun onBind(intent: Intent?): IBinder? = null
}
