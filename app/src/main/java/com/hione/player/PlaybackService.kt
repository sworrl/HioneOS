package com.hione.player

import android.content.Intent
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession

/**
 * Hosts the MediaSession so the OS shows our Hione-branded media control on the lockscreen
 * and in the notification shade (album art + title/artist come from each MediaItem's
 * MediaMetadata). Media3's DefaultMediaNotificationProvider posts/removes the foreground
 * notification automatically as playback starts and stops.
 *
 * This is a Media3 [MediaLibraryService] (a superset of MediaSessionService) so the SAME session
 * also serves Android Auto's browsable media tree — see [HioneLibraryCallback]. Android Auto
 * discovers it via the extra manifest intent-filter actions (MediaLibraryService +
 * android.media.browse.MediaBrowserService) and the automotive_app_desc media descriptor.
 */
class PlaybackService : MediaLibraryService() {
    companion object {
        private const val EARLY_CHANNEL = "hione_playback_early"
        /** Media3's DefaultMediaNotificationProvider uses id 1001; ours must differ so both can coexist. */
        private const val EARLY_NOTIFICATION_ID = 1002
    }

    override fun onCreate() {
        super.onCreate()
        // Permanently ensure Android framework recognizes device provisioning & user setup complete
        // (AOSP MediaSessionService drops all global hardware media buttons if user_setup_complete == 0)
        try {
            val cr = applicationContext.contentResolver
            android.provider.Settings.Secure.putInt(cr, "user_setup_complete", 1)
            android.provider.Settings.Global.putInt(cr, "device_provisioned", 1)
        } catch (_: Throwable) {}

        // Register the session up front. We drive the ExoPlayer directly from the Activity (no
        // MediaController), so onGetSession never fires on its own — without addSession() the
        // notification manager would never observe the player and no lockscreen control posts.
        addSession(PlayerHolder.ensureSession(this))
        try {
            com.hione.player.api.HioneApiServer.start(this)
        } catch (e: Throwable) {
            android.util.Log.e("PlaybackService", "Failed to start HioneApiServer", e)
        }

        // The TV cast listener is NOT started here any more. It runs only while the user has
        // casting turned on (Settings, Wi-Fi Remote) and stops itself after 15 minutes with no
        // TV. It lives in the process, not the Activity, so a cast still survives a swipe-away.

        // Feed the TV now-playing metadata and act on the transport it sends back.
        com.hione.player.cast.HioneCastBridge.attach(this)

        // Hold low-latency Wi-Fi lock to keep Qualcomm radio active and prevent network stalls
        val wm = applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        if (wm != null && wifiLock == null) {
            val mode = if (android.os.Build.VERSION.SDK_INT >= 29) {
                android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = runCatching {
                wm.createWifiLock(mode, "HionePlayback:LowLatency").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.getOrNull()
        }
    }

    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    /**
     * Go foreground IMMEDIATELY, before anything else.
     *
     * Media3 posts the real media notification (and with it the startForeground call) from its own
     * notification manager, lazily, when the player state next updates. Under memory pressure that
     * did not happen inside the platform's 5 second window and the OS killed the process:
     * "Context.startForegroundService() did not then call Service.startForeground()", ANR at
     * 22:49 on 2026-09-19 in the middle of a library rescan, 119k page faults in six seconds. A
     * placeholder notification posted synchronously here satisfies the contract on the first
     * line of onStartCommand; Media3 replaces it with the real one moments later.
     */
    private fun ensureForegroundNow() {
        try {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            if (android.os.Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(EARLY_CHANNEL) == null) {
                nm.createNotificationChannel(
                    android.app.NotificationChannel(EARLY_CHANNEL, "Playback", android.app.NotificationManager.IMPORTANCE_LOW)
                        .apply { setShowBadge(false) }
                )
            }
            val n = androidx.core.app.NotificationCompat.Builder(this, EARLY_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("${Mascot.musicAppName}")
                .setContentText("Starting playback service")
                .setOngoing(true)
                .setSilent(true)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
                .build()
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                startForeground(EARLY_NOTIFICATION_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else startForeground(EARLY_NOTIFICATION_ID, n)
        } catch (t: Throwable) {
            android.util.Log.w("PlaybackService", "early startForeground failed: $t")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForegroundNow()
        if (intent?.action == Intent.ACTION_MEDIA_BUTTON || intent?.action == "hiby_media_button_action") {
            PlayerHolder.ensureSession(this)
            PlayerHolder.ensureControllerConnected(this)
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession =
        PlayerHolder.ensureSession(this)

    // Media3 demotes the service out of foreground when playback pauses. On this device that
    // let the process go cached → the media session stopped answering ANY transport command
    // (hardware play/next/prev buttons dead unless the app was focused with the screen on).
    // While a queue is loaded, stay foreground so the session keeps responding paused,
    // backgrounded, and screen-off. It's our own DAP — the buttons must always work.
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        val keepAlive = startInForegroundRequired || (PlayerHolder.player?.mediaItemCount ?: 0) > 0
        super.onUpdateNotification(session, keepAlive)
    }

    // Swiping the app away with nothing playing tears everything down.
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = PlayerHolder.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            try {
                com.hione.player.api.HioneApiServer.stop(this)
            } catch (_: Throwable) {}
            try { com.hione.player.cast.HioneCastServer.stop(this) } catch (_: Throwable) {}
            // Swipe-away with nothing playing also parks the BLE phone remote (its Settings
            // toggle stays on, so it re-arms on next launch via startIfEnabled).
            try { com.hione.player.remote.HioneRemoteGattService.stop(this) } catch (_: Throwable) {}
            PlayerHolder.release()
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        try {
            com.hione.player.api.HioneApiServer.stop(this)
        } catch (_: Throwable) {}
        runCatching {
            wifiLock?.let { if (it.isHeld) it.release() }
            wifiLock = null
        }
        // Player lifecycle is owned by PlayerHolder; only release here on a real teardown.
        super.onDestroy()
    }
}
