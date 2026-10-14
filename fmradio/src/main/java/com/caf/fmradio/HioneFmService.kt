package com.caf.fmradio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Keeps the tuner running when the activity is not on screen.
 *
 * Before this existed the engine lived in the activity and `onDestroy` powered the chip down, so
 * the radio stopped the moment you went Home — which on a device whose whole point is listening
 * is not a radio. The service owns the power state now; the activity is just a view onto it.
 *
 * It is a foreground service with a media-playback type because that is what it is: it holds an
 * AudioTrack and audio focus. Android 14 requires the type to be declared both here and in the
 * manifest, and requires the matching FOREGROUND_SERVICE_MEDIA_PLAYBACK permission.
 */
class HioneFmService : Service() {

    companion object {
        private const val TAG = "HioneFmService"
        private const val CHANNEL_ID = "hione_fm"
        private const val NOTIFICATION_ID = 0x4D46   // 'MF'

        const val ACTION_START = "com.caf.fmradio.service.START"
        const val ACTION_STOP = "com.caf.fmradio.service.STOP"
        const val ACTION_SEEK_UP = "com.caf.fmradio.service.SEEK_UP"
        const val ACTION_SEEK_DOWN = "com.caf.fmradio.service.SEEK_DOWN"
        const val ACTION_TOGGLE_MUTE = "com.caf.fmradio.service.TOGGLE_MUTE"

        /** Start the tuner and the notification that keeps it alive. */
        fun start(ctx: Context) {
            val i = Intent(ctx, HioneFmService::class.java).setAction(ACTION_START)
            runCatching { ctx.startForegroundService(i) }
                .onFailure { Log.w(TAG, "could not start the FM service: $it") }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.startService(Intent(ctx, HioneFmService::class.java).setAction(ACTION_STOP)) }
        }
    }

    /** Exactly the fields the notification renders; anything else must not trigger a re-post. */
    private data class NotifKey(
        val on: Boolean, val khz: Int, val station: String,
        val muted: Boolean, val error: Boolean, val radioText: String,
    )

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // The radio is a media source whenever the tuner is on, not only while Android Auto is
        // connected, so the session exists from the moment the service does.
        FmMediaSession.ensure(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                FmRadioManager.powerOff()
                stopSelfAndForeground()
                return START_NOT_STICKY
            }
            ACTION_SEEK_UP -> FmRadioManager.seek(true)
            ACTION_SEEK_DOWN -> FmRadioManager.seek(false)
            ACTION_TOGGLE_MUTE -> FmRadioManager.toggleMute()
            else -> FmRadioManager.initAndPowerOn(applicationContext)
        }

        goForeground(buildNotification())
        if (watcher == null) {
            watcher = scope.launch {
                // powerOn() flips the engine's flag synchronously but the mirrored UI state is
                // updated by a collector, so the first emission here can still say "off". Wait
                // until the tuner has actually been seen running before treating "off" as a
                // reason to shut down, or the service stops itself the moment it starts.
                var sawRunning = false
                // Re-post ONLY when something the notification actually shows has changed.
                //
                // This used to notify() on every FmState emission. That was survivable until
                // the state grew signal history, per-channel band data and a diagnostics block,
                // at which point a 750 ms poll produced several emissions and the service was
                // rebuilding a four-action Notification about fourteen times a second. It cost
                // two cores between the coroutine workers and NotificationService, and it was
                // the whole of the "laggy as hell" regression: the UI was fine, the binder
                // traffic behind it was not.
                FmRadioManager.state
                    .map { st ->
                        NotifKey(st.isPowerOn, st.frequencyKHz, st.stationName,
                                 st.isMuted, st.hardwareError != null, st.radioText)
                    }
                    .distinctUntilChanged()
                    .collect { key ->
                        if (key.on) sawRunning = true
                        if (sawRunning && !key.on) stopSelfAndForeground()
                        else notificationManager().notify(NOTIFICATION_ID, buildNotification())
                    }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        watcher?.cancel(); watcher = null
        super.onDestroy()
    }

    private fun stopSelfAndForeground() {
        watcher?.cancel(); watcher = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun goForeground(n: Notification) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
        }.onFailure { Log.w(TAG, "startForeground refused: $it") }
    }

    private fun notificationManager() = getSystemService(NotificationManager::class.java)

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "FM tuner", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while the FM tuner is on"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        runCatching { notificationManager().createNotificationChannel(ch) }
    }

    private fun action(name: String, label: String): Notification.Action {
        val pi = PendingIntent.getService(
            this, name.hashCode(),
            Intent(this, HioneFmService::class.java).setAction(name),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Action.Builder(null, label, pi).build()
    }

    private fun buildNotification(): Notification {
        val st = FmRadioManager.state.value
        val freq = String.format(Locale.US, "%.1f MHz", st.frequencyKHz / 1000.0)
        // Only what is actually known: the RDS name if one has been decoded, otherwise the
        // engine's own status line. No invented station names in the shade either.
        val line = when {
            st.stationName.isNotEmpty() -> st.stationName
            st.hardwareError != null -> "Tuner error"
            st.radioText.isNotBlank() -> st.radioText
            else -> "No RDS name"
        }

        val content = PendingIntent.getActivity(
            this, 0,
            Intent(this, HioneFMRadioActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_fm)
            .setContentTitle(freq)
            .setContentText(line)
            .setContentIntent(content)
            .setOngoing(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(ACTION_SEEK_DOWN, "Seek down"))
            .addAction(action(ACTION_TOGGLE_MUTE, if (st.isMuted) "Unmute" else "Mute"))
            .addAction(action(ACTION_SEEK_UP, "Seek up"))
            .addAction(action(ACTION_STOP, "Off"))
            .build()
    }
}
