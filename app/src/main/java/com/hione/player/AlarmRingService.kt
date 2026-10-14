package com.hione.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.roundToInt

/** What was playing (if anything) right before an alarm took over the shared player, so a
 *  dismissed/snoozed alarm can hand playback back exactly where the user left it (queue, index,
 *  position, gain, repeat/shuffle mode) instead of leaving them on the alarm's queue.
 *  Process-lifetime singleton, same pattern as [[PlayerHolder]] — deliberately NOT persisted to
 *  disk: if the process dies mid-alarm there's nothing meaningful left to restore anyway (the
 *  player itself would be rebuilt from scratch). */
private object AlarmPlaybackSnapshot {
    data class Snapshot(
        val items: List<MediaItem>, val index: Int, val positionMs: Long, val wasPlaying: Boolean,
        val volume: Float, val repeatMode: Int, val shuffle: Boolean
    )
    @Volatile var saved: Snapshot? = null

    fun capture(player: androidx.media3.exoplayer.ExoPlayer) {
        if (saved != null) return // a second alarm overlapping the first — the first one's snapshot is the real "before"
        saved = Snapshot(
            items = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) },
            index = player.currentMediaItemIndex,
            positionMs = player.currentPosition,
            wasPlaying = player.isPlaying,
            volume = player.volume,
            repeatMode = player.repeatMode,
            shuffle = player.shuffleModeEnabled
        )
    }

    fun restoreAndClear(player: androidx.media3.exoplayer.ExoPlayer) {
        val s = saved ?: return
        saved = null
        player.volume = s.volume
        player.repeatMode = s.repeatMode
        player.shuffleModeEnabled = s.shuffle
        if (s.items.isNotEmpty()) {
            player.setMediaItems(s.items, s.index.coerceIn(0, s.items.lastIndex), s.positionMs)
            player.prepare()
            // Resume ONLY if music was genuinely playing when the alarm interrupted it (e.g. an
            // alarm used as a timer mid-listen); an alarm that woke a silent device leaves it paused.
            if (s.wasPlaying) player.play() else player.pause()
        } else {
            // Nothing was queued before. Don't leave the synthetic chime as the "last played"
            // item; real library music stays queued (paused) so the user can just hit play.
            if (player.currentMediaItem?.mediaId == HioneAlarmChime.MEDIA_ID) player.clearMediaItems() else player.pause()
        }
    }
}

/**
 * Foreground service that actually rings an alarm: builds the queue on the shared
 * [[PlayerHolder]] player (the normal bit-perfect path — no separate ringtone engine), fades the
 * gain in over the alarm's configured duration up to its volume cap, vibrates as a fallback that
 * doesn't depend on the library having anything to play, posts a full-screen-intent notification
 * so [[AlarmRingActivity]] shows over the lockscreen, and holds two wake locks: a PARTIAL one for
 * the CPU for the whole ring (audio decode keeps running no matter what the screen does) and a
 * timed SCREEN_BRIGHT|ACQUIRE_CAUSES_WAKEUP one to physically light the panel.
 *
 * Multiple alarms can legitimately overlap (two close-together trigger times) — every piece of
 * per-ring state is keyed by alarm id so a second alarm firing never stomps the first one's
 * in-flight state or steals its notification.
 *
 * Physical M500 side buttons while ringing (any path — this activity, MainActivity, the media
 * button receiver or the MediaSession): play/pause = snooze, next/prev = dismiss. See
 * [interceptMediaKey].
 */
class AlarmRingService : Service() {
    private data class RingState(
        val alarm: Alarm,
        val preview: Boolean,
        var cpuLock: PowerManager.WakeLock?,
        var screenLock: PowerManager.WakeLock?,
        val fadeHandler: Handler,
        val autoDismissHandler: Handler
    )
    private val rings = HashMap<Int, RingState>()
    private var vibrator: Vibrator? = null
    /** STREAM_MUSIC index before we raised it to the audible floor (null = untouched). */
    private var streamVolumeToRestore: Int? = null
    private var streamVolumeFloorApplied: Int = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            // startForegroundService() demands startForeground() within seconds of THIS call or the
            // OS kills the process with a fatal exception — call it unconditionally, immediately,
            // before any lookup that could bail out early, so a missing/already-deleted alarm id
            // (TOCTOU against AlarmScheduler.cancel, or a corrupt alarms.json falling back to
            // emptyList in AlarmPreferences.loadAlarms) degrades to "notification briefly appears
            // then clears" instead of a crash.
            ACTION_RING -> {
                val alarmId = intent.getIntExtra(AlarmReceiver.EXTRA_ALARM_ID, -1)
                startForeground(NOTIF_ID_BASE, placeholderNotification())
                val alarm = AlarmPreferences.loadAlarms(this).firstOrNull { it.id == alarmId }
                startRinging(alarm, preview = false)
            }
            ACTION_PREVIEW -> {
                startForeground(NOTIF_ID_BASE, placeholderNotification())
                val alarm = runCatching { Alarm.fromJson(org.json.JSONObject(intent.getStringExtra(EXTRA_ALARM_JSON) ?: "")) }.getOrNull()
                startRinging(alarm?.let { a -> a.copy(id = PREVIEW_ID, label = a.label.ifBlank { "Alarm" } + " (preview)") }, preview = true)
            }
            ACTION_SNOOZE -> withFgsGuard(intent) { stopOneRing(intent.getIntExtra(AlarmReceiver.EXTRA_ALARM_ID, -1), snoozing = true) }
            ACTION_DISMISS -> withFgsGuard(intent) { stopOneRing(intent.getIntExtra(AlarmReceiver.EXTRA_ALARM_ID, -1), snoozing = false) }
            ACTION_SNOOZE_ALL -> withFgsGuard(intent) { rings.keys.toList().forEach { stopOneRing(it, snoozing = true) }; stopForegroundAndSelfIfIdle() }
            ACTION_DISMISS_ALL -> withFgsGuard(intent) { rings.keys.toList().forEach { stopOneRing(it, snoozing = false) }; stopForegroundAndSelfIfIdle() }
            else -> stopForegroundAndSelfIfIdle()
        }
        return START_NOT_STICKY
    }

    /** A control action that had to be delivered via startForegroundService() (rare — only when
     *  plain startService() was refused) must be answered with startForeground() or the OS kills
     *  us; the placeholder is then cancelled straight away if a real ring notification exists. */
    private inline fun withFgsGuard(intent: Intent, body: () -> Unit) {
        val viaFgs = intent.getBooleanExtra(EXTRA_VIA_FGS, false)
        if (viaFgs) startForeground(NOTIF_ID_BASE, placeholderNotification())
        body()
        if (viaFgs && rings.isNotEmpty()) (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID_BASE)
    }

    /** HioneOS runs with theater_mode_on=1 (charger-flap plug wakes strobed the screen; HiBy's
     *  framework ignores config_unplugTurnsOnScreen). Theater mode also blocks the alarm's
     *  ACQUIRE_CAUSES_WAKEUP path, so clear it for the duration of a ring and restore after. */
    private var theaterWasOn = false
    private fun suspendTheaterMode() {
        try {
            theaterWasOn = android.provider.Settings.Global.getInt(contentResolver, "theater_mode_on", 0) == 1
            if (theaterWasOn) android.provider.Settings.Global.putInt(contentResolver, "theater_mode_on", 0)
        } catch (_: Throwable) {}
    }
    private fun restoreTheaterMode() {
        try {
            if (theaterWasOn) android.provider.Settings.Global.putInt(contentResolver, "theater_mode_on", 1)
            theaterWasOn = false
        } catch (_: Throwable) {}
    }

    private fun startRinging(alarm: Alarm?, preview: Boolean) {
        suspendTheaterMode()
        if (alarm == null) {
            AlarmWakeHandoff.release()
            // The placeholder notification is only ever superseded by a REAL per-alarm notification
            // if a valid alarm was found. With another alarm already ringing, that supersession
            // never happens here, so the placeholder would otherwise sit in the shade forever.
            if (rings.isEmpty()) stopForegroundAndSelfIfIdle()
            else (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID_BASE)
            return
        }
        val alarmId = alarm.id
        // Re-entry guard: a duplicate ACTION_RING for an id that's already ringing (broadcast
        // redelivery, possible on a custom OEM ROM) must not overwrite the live RingState.
        if (rings.containsKey(alarmId)) { AlarmWakeHandoff.release(); return }
        if (!preview) AlarmPreferences.saveActiveSnooze(this, null)

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        // CPU lock for the WHOLE ring (+margin past auto-dismiss) — released explicitly on stop.
        val cpu = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hione:alarm:cpu:$alarmId").apply {
            setReferenceCounted(false)
            acquire(AUTO_DISMISS_AFTER_MS + 30_000L)
        }
        // Screen lock: sized to the fade-in (editable up to 300 s) plus real margin for the
        // full-screen-intent launch to land. ACQUIRE_CAUSES_WAKEUP is what actually lights a
        // sleeping panel; the flags are deprecated for normal apps but still fully functional and
        // are exactly what AOSP DeskClock relies on.
        @Suppress("DEPRECATION")
        val screen = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "hione:alarm:screen:$alarmId"
        ).apply {
            setReferenceCounted(false)
            acquire((alarm.fadeInSeconds + 45) * 1000L)
        }
        AlarmWakeHandoff.release() // we now hold our own locks; the receiver's bridge lock can go

        val state = RingState(alarm, preview, cpu, screen, Handler(Looper.getMainLooper()), Handler(Looper.getMainLooper()))
        rings[alarmId] = state
        publishRinging()

        startForeground(notifId(alarmId), buildNotification(alarm, nowPlaying = null))
        launchRingActivity(alarmId)
        raiseStreamVolumeFloorIfNeeded()
        if (alarm.vibrate) startVibrating()
        ringPlayer(alarm, state)

        // Safety net: an alarm nobody responds to (device out of reach, user asleep through it)
        // shouldn't ring/vibrate/hold a foreground notification forever — auto-dismiss same as a
        // manual dismiss after a generous timeout, still re-arming the next occurrence if it repeats.
        state.autoDismissHandler.postDelayed({ stopOneRing(alarmId, snoozing = false) }, AUTO_DISMISS_AFTER_MS)
    }

    /** Direct launch attempt in addition to the full-screen-intent notification. The FSI is the
     *  guaranteed "device asleep/locked" path (USE_FULL_SCREEN_INTENT); this covers the "screen
     *  already on, app in foreground" case where the system may suppress the FSI in favour of a
     *  heads-up. Background-activity-start policy may silently drop it — harmless (singleTop). */
    private fun launchRingActivity(alarmId: Int) {
        runCatching {
            startActivity(
                Intent(this, AlarmRingActivity::class.java)
                    .putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarmId)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }

    private fun ringPlayer(alarm: Alarm, state: RingState) {
        val player = PlayerHolder.ensure(this)
        runCatching { PlayerHolder.ensureSession(this) } // keep the MediaSession/HUD in step with what's playing
        AlarmPlaybackSnapshot.capture(player)
        // Library lookup (+ chime render on first use) is real work that shouldn't run on the main
        // thread during an alarm fire — it's exactly the moment that most wants to be responsive.
        // Queried on a background thread, then the actual ExoPlayer calls (must happen on the
        // player's own Looper thread, i.e. main) are posted back.
        Thread {
            val items: List<MediaItem> = runCatching {
                if (alarm.source == AlarmSource.HIONE_CHIME) listOf(HioneAlarmChime.mediaItem(this))
                else AlarmLibrary.tracksFor(this, alarm.source, alarm.sourceRef).map { mediaItemFor(it) }
                    .ifEmpty { listOf(HioneAlarmChime.mediaItem(this)) } // empty/unscanned library → never a silent alarm
            }.getOrElse { runCatching { listOf(HioneAlarmChime.mediaItem(this)) }.getOrDefault(emptyList()) }
            state.fadeHandler.post {
                if (!rings.containsKey(alarm.id)) return@post // dismissed/snoozed already while this was running
                if (items.isEmpty()) return@post // chime render failed too — vibration (already started) is the last fallback
                val cap = alarm.volumeCap.coerceIn(Alarm.MIN_VOLUME_CAP, 1f)
                player.shuffleModeEnabled = false
                player.repeatMode = Player.REPEAT_MODE_ALL // a single track / short chime must keep going until answered
                player.setMediaItems(items, 0, 0L)
                player.volume = if (alarm.fadeInSeconds <= 0) cap else 0f
                player.prepare()
                player.play()
                runCatching { updateNotificationNowPlaying(alarm, items.first()) }

                if (alarm.fadeInSeconds > 0) {
                    val steps = (alarm.fadeInSeconds * 4).coerceAtLeast(1) // one tick per 250 ms
                    var step = 0
                    val tick = object : Runnable {
                        override fun run() {
                            step++
                            // Smoothstep, not linear: perceived loudness ramps gently at the start
                            // instead of jumping from silence, and lands softly on the cap.
                            val x = (step.toFloat() / steps).coerceIn(0f, 1f)
                            player.volume = cap * (x * x * (3f - 2f * x))
                            if (step < steps) state.fadeHandler.postDelayed(this, 250L)
                        }
                    }
                    state.fadeHandler.postDelayed(tick, 250L)
                }
            }
        }.start()
    }

    /** If STREAM_MUSIC was turned all the way down at bedtime, a max-quality alarm at gain 0 is
     *  still silent. Lift the stream to a modest audible floor (35 % of max) and put it back
     *  afterwards — only if nobody touched the knob in between. Never lowers anything. */
    private fun raiseStreamVolumeFloorIfNeeded() {
        if (streamVolumeToRestore != null) return
        runCatching {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val floor = (max * 0.35f).roundToInt().coerceAtLeast(1)
            if (cur < floor) {
                streamVolumeToRestore = cur
                streamVolumeFloorApplied = floor
                am.setStreamVolume(AudioManager.STREAM_MUSIC, floor, 0)
            }
        }
    }

    private fun restoreStreamVolumeIfUntouched() {
        val prev = streamVolumeToRestore ?: return
        streamVolumeToRestore = null
        runCatching {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (am.getStreamVolume(AudioManager.STREAM_MUSIC) == streamVolumeFloorApplied) am.setStreamVolume(AudioManager.STREAM_MUSIC, prev, 0)
        }
        streamVolumeFloorApplied = -1
    }

    private fun startVibrating() {
        if (vibrator != null) return // already vibrating for an earlier concurrent alarm — one shared pattern is enough
        val v = if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (!v.hasVibrator()) return
        vibrator = v
        val pattern = longArrayOf(0, 700, 400) // wait, buzz, pause — repeats from index 1
        runCatching { v.vibrate(VibrationEffect.createWaveform(pattern, 1)) }
    }

    private fun stopVibratingIfNoneRinging() {
        if (rings.isEmpty()) { runCatching { vibrator?.cancel() }; vibrator = null }
    }

    private fun snooze(alarm: Alarm) = AlarmScheduler.scheduleSnooze(this, alarm)

    private fun dismiss(alarm: Alarm) {
        AlarmPreferences.saveActiveSnooze(this, null)
        if (alarm.repeatDays.isNotEmpty()) {
            AlarmScheduler.scheduleNext(this, alarm)
        } else {
            AlarmPreferences.updateAlarm(this, alarm.id) { it.copy(enabled = false) }
            AlarmScheduler.cancel(this, alarm.id)
        }
    }

    private fun stopOneRing(alarmId: Int, snoozing: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val state = rings.remove(alarmId)
        if (state == null) {
            // Duplicate stop (e.g. the same physical key reaching both the media-button receiver
            // and AlarmRingActivity): nothing to do, and crucially do NOT touch the player — the
            // user's restored playback must not get paused by a stale second dismiss.
            nm.cancel(notifId(alarmId))
            stopForegroundAndSelfIfIdle()
            return
        }
        state.fadeHandler.removeCallbacksAndMessages(null)
        state.autoDismissHandler.removeCallbacksAndMessages(null)
        state.screenLock?.let { runCatching { if (it.isHeld) it.release() } }
        state.cpuLock?.let { runCatching { if (it.isHeld) it.release() } }
        if (!state.preview) {
            if (snoozing) snooze(state.alarm) else dismiss(state.alarm)
        }
        publishRinging()
        // Handing the player back only makes sense once NO alarm is still actively ringing — with
        // two alarms overlapping, stopping the first must not yank the second one's music away.
        // Snooze restores too: the user may well listen to something during the snooze window,
        // and the re-fire takes a FRESH snapshot of whatever is playing then.
        if (rings.isEmpty()) {
            PlayerHolder.player?.let { p ->
                p.pause()
                AlarmPlaybackSnapshot.restoreAndClear(p)
            }
            restoreStreamVolumeIfUntouched()
        }
        stopVibratingIfNoneRinging()
        nm.cancel(notifId(alarmId))
        stopListeners.forEach { runCatching { it(alarmId) } }
        if (!state.preview) runCatching { AlarmUpcomingNotifier.refresh(this) }
        stopForegroundAndSelfIfIdle()
    }

    private fun stopForegroundAndSelfIfIdle() {
        if (rings.isNotEmpty()) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun publishRinging() {
        ringingIds = rings.keys.toSet()
        ringingLabel = rings.values.firstOrNull()?.alarm?.label
    }

    private fun placeholderNotification(): android.app.Notification {
        ensureChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("${Mascot.musicAppName} Alarm")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .build()
    }

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "${Mascot.musicAppName} alarm ringing"
                    setBypassDnd(true)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                    // Sound comes from the player itself (bit-perfect path) — never the channel.
                    setSound(null, null)
                    enableVibration(false)
                }
            )
        }
    }

    private fun updateNotificationNowPlaying(alarm: Alarm, item: MediaItem) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val title = item.mediaMetadata.title?.toString()
        val artist = item.mediaMetadata.artist?.toString()
        val np = listOfNotNull(title, artist).joinToString(" — ").ifBlank { null }
        nm.notify(notifId(alarm.id), buildNotification(alarm, np))
    }

    private fun buildNotification(alarm: Alarm, nowPlaying: String?): android.app.Notification {
        ensureChannel()
        val fullScreenIntent = PendingIntent.getActivity(
            this, alarm.id,
            Intent(this, AlarmRingActivity::class.java).putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarm.id)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val snoozePi = PendingIntent.getService(
            this, alarm.id * 10 + 1,
            Intent(this, AlarmRingService::class.java).setAction(ACTION_SNOOZE).putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarm.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val dismissPi = PendingIntent.getService(
            this, alarm.id * 10 + 2,
            Intent(this, AlarmRingService::class.java).setAction(ACTION_DISMISS).putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarm.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = nowPlaying?.let { "$it" } ?: "Ringing. Play/pause snoozes, next/prev dismisses"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(alarm.label.ifBlank { "Alarm" })
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fullScreenIntent, true)
            .setContentIntent(fullScreenIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Snooze ${alarm.snoozeMinutes}m", snoozePi)
            .addAction(0, "Dismiss", dismissPi)
            .build()
    }

    private fun notifId(alarmId: Int) = NOTIF_ID_BASE + alarmId

    override fun onDestroy() {
        restoreTheaterMode()
        rings.values.forEach {
            it.fadeHandler.removeCallbacksAndMessages(null)
            it.autoDismissHandler.removeCallbacksAndMessages(null)
            it.screenLock?.let { wl -> runCatching { if (wl.isHeld) wl.release() } }
            it.cpuLock?.let { wl -> runCatching { if (wl.isHeld) wl.release() } }
        }
        rings.clear()
        publishRinging()
        runCatching { vibrator?.cancel() }
        vibrator = null
        restoreStreamVolumeIfUntouched()
        AlarmWakeHandoff.release()
        super.onDestroy()
    }

    companion object {
        const val ACTION_RING = "com.hione.player.alarm.RING"
        const val ACTION_SNOOZE = "com.hione.player.alarm.SNOOZE"
        const val ACTION_DISMISS = "com.hione.player.alarm.DISMISS"
        const val ACTION_SNOOZE_ALL = "com.hione.player.alarm.SNOOZE_ALL"
        const val ACTION_DISMISS_ALL = "com.hione.player.alarm.DISMISS_ALL"
        /** Ring an unsaved alarm (JSON in [EXTRA_ALARM_JSON]) for real — same fade/volume/queue —
         *  without touching any schedule. Used by the editor's "Preview" button. */
        const val ACTION_PREVIEW = "com.hione.player.alarm.PREVIEW"
        const val EXTRA_ALARM_JSON = "alarm_json"
        private const val EXTRA_VIA_FGS = "via_fgs"
        const val PREVIEW_ID = 900_000
        private const val CHANNEL_ID = "hione_alarms"
        private const val NOTIF_ID_BASE = 7100
        private const val AUTO_DISMISS_AFTER_MS = 10 * 60_000L

        /** Ids of alarms ringing right now (empty = silent). Process-wide, read from any thread. */
        @Volatile var ringingIds: Set<Int> = emptySet()
            private set
        @Volatile var ringingLabel: String? = null
            private set
        fun isRinging(): Boolean = ringingIds.isNotEmpty()

        /** Called (main thread) with the alarm id whenever one ring stops, for UI teardown. */
        private val stopListeners = CopyOnWriteArraySet<(Int) -> Unit>()
        fun addStopListener(l: (Int) -> Unit) { stopListeners.add(l) }
        fun removeStopListener(l: (Int) -> Unit) { stopListeners.remove(l) }

        /** Send a control action to the running ring service from anywhere in the process. */
        fun send(ctx: Context, action: String, alarmId: Int? = null) {
            val app = ctx.applicationContext
            val i = Intent(app, AlarmRingService::class.java).setAction(action)
            if (alarmId != null) i.putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarmId)
            try {
                app.startService(i)
            } catch (_: IllegalStateException) {
                // Background-start refused (shouldn't happen while we hold a foreground service,
                // but a race with stopSelf() is possible): the FGS route always goes through.
                runCatching { ContextCompat.startForegroundService(app, i.putExtra(EXTRA_VIA_FGS, true)) }
            }
        }

        /**
         * M500 physical side buttons while an alarm rings — the single mapping every key path in
         * the app defers to (MainActivity.onKeyDown, HioneMediaButtonReceiver, the MediaSession
         * callback, AlarmRingActivity): play/pause (or headset hook) = snooze, next/prev (and
         * FF/REW/stop) = dismiss. Returns true when the key was consumed by a ringing alarm so
         * normal transport handling must NOT run; false (no alarm ringing / unrelated key) means
         * "carry on as usual". Cheap when nothing is ringing — one volatile read.
         */
        fun interceptMediaKey(ctx: Context, keyCode: Int): Boolean {
            if (ringingIds.isEmpty()) return false
            val action = when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> ACTION_SNOOZE_ALL
                KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND,
                KeyEvent.KEYCODE_MEDIA_STEP_FORWARD, KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD,
                KeyEvent.KEYCODE_MEDIA_STOP -> ACTION_DISMISS_ALL
                else -> return false
            }
            runCatching { Haptics.tick(ctx) }
            send(ctx, action)
            return true
        }

        /** Same as [interceptMediaKey] for a raw ACTION_MEDIA_BUTTON intent (MediaSession path).
         *  Only ACTION_DOWN acts; the matching ACTION_UP is swallowed so it can't leak through as
         *  a transport command once the alarm has stopped. */
        fun interceptMediaButtonIntent(ctx: Context, intent: Intent?): Boolean {
            if (ringingIds.isEmpty() || intent == null) return false
            @Suppress("DEPRECATION")
            val ev: KeyEvent = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT) ?: return false
            if (ev.action != KeyEvent.ACTION_DOWN) return isAlarmKey(ev.keyCode)
            return interceptMediaKey(ctx, ev.keyCode)
        }

        fun isAlarmKey(keyCode: Int) = when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_STEP_FORWARD, KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD, KeyEvent.KEYCODE_MEDIA_STOP -> true
            else -> false
        }
    }
}
