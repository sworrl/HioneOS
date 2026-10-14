package com.hione.tools.clock

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

/** What is ringing right now, observed by [RingActivity] (same process). */
data class RingSnapshot(
    val alarms: List<ClockAlarm> = emptyList(),
    val timers: List<ClockTimer> = emptyList(),
    /** True while the sound comes from Hione Music rather than our own player. */
    val music: Boolean = false,
) {
    val isEmpty get() = alarms.isEmpty() && timers.isEmpty()
}

/**
 * Foreground service that rings alarms and expired timers: plays the tone on the ALARM stream
 * (so it is heard at alarm volume, through Do Not Disturb, and on the speaker even with
 * headphones in), fades it in, vibrates, holds the CPU awake, and posts the full-screen-intent
 * notification that puts [RingActivity] over the lock screen.
 *
 * Several things can ring at once (two alarms a minute apart, a timer during an alarm). They
 * share one sound and one notification; each is snoozed, dismissed or stopped on its own.
 */
class RingService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private val ringingAlarms = LinkedHashMap<Int, ClockAlarm>()
    private val ringingTimers = LinkedHashMap<Int, ClockTimer>()

    private var player: MediaPlayer? = null
    /** Key of what the current sound belongs to ("a:3", "t:1"), so a second ring does not
     *  restart a sound that is already right. */
    private var soundKey: String? = null
    private var musicMode = false
    private var vibrator: Vibrator? = null
    private var cpuLock: PowerManager.WakeLock? = null
    private var theaterWasOn = false
    private var musicVolumeToRestore: Int? = null
    private var musicVolumeApplied = -1
    private var inForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getIntExtra(ClockReceiver.EXTRA_ID, -1) ?: -1
        when (intent?.action) {
            ACTION_RING_ALARM -> {
                // startForegroundService() must be answered within seconds whatever happens
                // next, so go foreground before any lookup that could bail out.
                goForeground(placeholder())
                ringAlarm(id)
            }
            ACTION_RING_TIMER -> {
                goForeground(placeholder())
                ringTimer(id)
            }
            ACTION_SNOOZE -> targets(id).forEach { snooze(it) }
            ACTION_DISMISS -> {
                val keep = intent.getBooleanExtra(EXTRA_KEEP_MUSIC, false)
                targets(id).forEach { dismiss(it, keepMusic = keep) }
            }
            ACTION_TIMER_STOP -> {
                val ids = if (id == -1) ringingTimers.keys.toList() else listOf(id)
                ids.forEach { ringingTimers.remove(it); TimerScheduler.reset(applicationContext, it) }
            }
            ACTION_TIMER_ADD_MINUTE -> { ringingTimers.remove(id); TimerScheduler.addMinute(applicationContext, id) }
            ACTION_TIMER_HANDLED -> ringingTimers.remove(id)
        }
        WakeHandoff.release()
        afterChange()
        return START_NOT_STICKY
    }

    private fun targets(id: Int): List<Int> = if (id == -1) ringingAlarms.keys.toList() else listOf(id).filter { it in ringingAlarms }

    // ------------------------------------------------------------------ starting

    private fun ringAlarm(id: Int) {
        val ctx = applicationContext
        val a = ClockStore.alarm(ctx, id) ?: return
        if (ringingAlarms.containsKey(id)) return // duplicate delivery
        val now = System.currentTimeMillis()
        // A stale fire (alarm was edited or turned off after this broadcast was queued).
        if (!a.enabled) return
        if (a.snoozedUntil > now + 60_000) return
        // It fired: clear snooze/arm bookkeeping; a one-shot turns off now (snoozing turns it
        // back on). Then re-arm so a repeating alarm's next occurrence shows straight away.
        ClockStore.updateAlarm(ctx, id) { it.copy(snoozedUntil = 0, armedFor = 0, enabled = it.repeating, skipUntil = 0) }
        ClockNotify.nm(ctx).cancel(ClockNotify.idUpcoming(id))
        AlarmScheduler.sync(ctx)
        ringingAlarms[id] = a
        beginRinging()
        scheduleAutoSilence("a:$id")
    }

    private fun ringTimer(id: Int) {
        val t = ClockStore.timer(applicationContext, id) ?: return
        if (t.state != TimerState.EXPIRED) return
        ringingTimers[id] = t
        beginRinging()
        scheduleAutoSilence("t:$id")
    }

    private fun beginRinging() {
        if (cpuLock == null) {
            suspendTheaterMode()
            val pm = getSystemService(PowerManager::class.java)
            cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hione:clock:ring").apply {
                setReferenceCounted(false)
                acquire((ClockStore.autoSilenceMinutes(this@RingService).coerceAtLeast(1) + 2) * 60_000L)
            }
            // Lights the panel; deprecated for apps but still the thing that works, and what
            // stock DeskClock relied on. The ring activity's turnScreenOn keeps it on after.
            @Suppress("DEPRECATION")
            runCatching {
                pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE, "hione:clock:screen")
                    .apply { setReferenceCounted(false); acquire(30_000L) }
            }
        }
        refreshSound()
    }

    // ------------------------------------------------------------------ actions

    private fun snooze(id: Int) {
        val ctx = applicationContext
        val a = ringingAlarms.remove(id) ?: return
        val until = System.currentTimeMillis() + a.snoozeMinutes.coerceAtLeast(1) * 60_000L
        ClockStore.updateAlarm(ctx, id) { it.copy(snoozedUntil = until, enabled = true) }
        AlarmScheduler.sync(ctx)
        if (musicMode) HioneMusicBridge.pause(ctx)
    }

    private fun dismiss(id: Int, keepMusic: Boolean) {
        val ctx = applicationContext
        ringingAlarms.remove(id) ?: return
        ClockStore.updateAlarm(ctx, id) { cur ->
            if (!cur.repeating && cur.deleteAfterUse) null else cur.copy(snoozedUntil = 0)
        }
        AlarmScheduler.sync(ctx)
        if (musicMode && !keepMusic) HioneMusicBridge.pause(ctx)
        if (keepMusic) { musicMode = false; musicVolumeToRestore = null } // leave the music and its volume alone
    }

    private val autoSilence = HashMap<String, Runnable>()

    private fun scheduleAutoSilence(key: String) {
        val minutes = ClockStore.autoSilenceMinutes(this)
        if (minutes <= 0) return
        val r = Runnable {
            val ctx = applicationContext
            val id = key.substring(2).toInt()
            if (key.startsWith("a:")) {
                ringingAlarms[id]?.let { a ->
                    // Nobody answered: count it as missed (and tell them) rather than ringing on.
                    AlarmScheduler.postMissed(ctx, a, System.currentTimeMillis() - minutes * 60_000L)
                    dismiss(id, keepMusic = false)
                }
            } else if (ringingTimers.remove(id) != null) {
                TimerScheduler.reset(ctx, id)
            }
            afterChange()
        }
        autoSilence.put(key, r)?.let { main.removeCallbacks(it) }
        main.postDelayed(r, minutes * 60_000L)
    }

    private fun afterChange() {
        // Drop auto-silence timers for things no longer ringing.
        autoSilence.keys.toList().forEach { k ->
            val id = k.substring(2).toInt()
            val alive = if (k.startsWith("a:")) id in ringingAlarms else id in ringingTimers
            if (!alive) autoSilence.remove(k)?.let { main.removeCallbacks(it) }
        }
        if (ringingAlarms.isEmpty() && ringingTimers.isEmpty()) {
            stopEverything()
            return
        }
        refreshSound()
        publish()
        runCatching { ClockNotify.nm(this).notify(ClockNotify.ID_RING, ringNotification()) }
    }

    private fun publish() {
        _state.value = RingSnapshot(ringingAlarms.values.toList().reversed(), ringingTimers.values.toList().reversed(), musicMode)
    }

    // ------------------------------------------------------------------ sound

    /** Pick what should be sounding: the newest alarm wins over timers. */
    private fun refreshSound() {
        val alarm = ringingAlarms.values.lastOrNull()
        val timer = ringingTimers.values.lastOrNull()
        val key = when {
            alarm != null -> "a:${alarm.id}"
            timer != null -> "t:${timer.id}"
            else -> null
        }
        if (key == soundKey) return
        stopSound()
        soundKey = key
        val ctx = applicationContext
        when {
            alarm != null -> {
                if (alarm.vibrate) startVibration()
                if (alarm.sound != WakeSound.TONE && HioneMusicBridge.isInstalled(ctx)) {
                    startMusic(alarm)
                } else {
                    playTone(alarm.ringtone, alarm.gradualSeconds)
                }
            }
            timer != null -> {
                startVibration()
                playTone(ClockStore.timerRingtone(ctx), 0)
            }
        }
        publish()
        runCatching { ClockNotify.nm(this).notify(ClockNotify.ID_RING, ringNotification()) }
    }

    private fun startMusic(alarm: ClockAlarm) {
        val ctx = applicationContext
        musicMode = true
        raiseMusicFloor()
        if (alarm.sound == WakeSound.MUSIC_SHUFFLE) HioneMusicBridge.startShuffle(ctx) else HioneMusicBridge.resume(ctx)
        // Safety net: if nothing is audible after a few seconds (empty library, Hione Music not
        // allowed to start, a paused session with nothing queued), ring the normal tone.
        val key = soundKey
        main.postDelayed({
            if (soundKey == key && musicMode && !getSystemService(AudioManager::class.java).isMusicActive) {
                Log.w(TAG, "wake-to-music produced no audio, falling back to tone")
                musicMode = false
                playTone(alarm.ringtone, alarm.gradualSeconds)
                publish()
            }
        }, 9_000L)
    }

    private fun resolveTone(spec: String?): Uri? {
        if (spec == ClockAlarm.RINGTONE_SILENT) return null
        if (spec == ClockAlarm.RINGTONE_CHIME) return HioneChime.file(this)?.let { Uri.fromFile(it) }
        if (spec != null) return Uri.parse(spec)
        return runCatching { RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM) }.getOrNull()
            ?: Settings.System.DEFAULT_ALARM_ALERT_URI
    }

    private fun playTone(spec: String?, gradualSeconds: Int) {
        if (spec == ClockAlarm.RINGTONE_SILENT) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        // Try the chosen sound, then the system default, then our own chime: an alarm that
        // fails to load its ringtone must still make noise.
        val candidates = listOfNotNull(
            resolveTone(spec),
            if (spec != null) resolveTone(null) else null,
            HioneChime.file(this)?.let { Uri.fromFile(it) }
        ).distinct()
        for (uri in candidates) {
            val mp = MediaPlayer()
            val ok = runCatching {
                mp.setAudioAttributes(attrs)
                mp.setDataSource(this, uri)
                mp.isLooping = true
                mp.prepare()
                true
            }.getOrElse { Log.w(TAG, "cannot play $uri: $it"); false }
            if (!ok) { runCatching { mp.release() }; continue }
            player = mp
            if (gradualSeconds > 0) {
                mp.setVolume(0.02f, 0.02f)
                fadeIn(mp, gradualSeconds)
            }
            mp.start()
            return
        }
    }

    private fun fadeIn(mp: MediaPlayer, seconds: Int) {
        val steps = seconds * 4
        var step = 0
        val tick = object : Runnable {
            override fun run() {
                if (player !== mp) return
                step++
                val x = (step.toFloat() / steps).coerceIn(0f, 1f)
                // Squared ramp: loudness is perceived roughly logarithmically, so a linear gain
                // ramp jumps out of silence and then barely changes. This one creeps up first.
                val v = 0.02f + 0.98f * x * x
                runCatching { mp.setVolume(v, v) }
                if (step < steps) main.postDelayed(this, 250L)
            }
        }
        main.postDelayed(tick, 250L)
    }

    private fun stopSound() {
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
        runCatching { vibrator?.cancel() }
        vibrator = null
    }

    private fun startVibration() {
        val v = if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java).defaultVibrator
        else @Suppress("DEPRECATION") (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
        if (!v.hasVibrator()) return
        vibrator = v
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 600, 500), 1)
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            else @Suppress("DEPRECATION") v.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
    }

    /** Music volume all the way down at bedtime would make wake-to-music silent. Lift it to a
     *  modest floor for the ring and put it back afterwards if nobody touched it. */
    private fun raiseMusicFloor() {
        if (musicVolumeToRestore != null) return
        runCatching {
            val am = getSystemService(AudioManager::class.java)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val floor = (max * 0.35f).roundToInt().coerceAtLeast(1)
            if (cur < floor) {
                musicVolumeToRestore = cur; musicVolumeApplied = floor
                am.setStreamVolume(AudioManager.STREAM_MUSIC, floor, 0)
            }
        }
    }

    private fun restoreMusicVolume() {
        val prev = musicVolumeToRestore ?: return
        musicVolumeToRestore = null
        runCatching {
            val am = getSystemService(AudioManager::class.java)
            if (am.getStreamVolume(AudioManager.STREAM_MUSIC) == musicVolumeApplied) am.setStreamVolume(AudioManager.STREAM_MUSIC, prev, 0)
        }
    }

    // ------------------------------------------------------------------ theater mode

    /** HioneOS runs with theater_mode_on=1 so charger plugs do not strobe the screen awake.
     *  Theater mode also blocks ACQUIRE_CAUSES_WAKEUP and full-screen intents from lighting
     *  the panel, so it is lifted for the length of a ring. */
    private fun suspendTheaterMode() {
        runCatching {
            theaterWasOn = Settings.Global.getInt(contentResolver, "theater_mode_on", 0) == 1
            if (theaterWasOn) Settings.Global.putInt(contentResolver, "theater_mode_on", 0)
        }
    }

    private fun restoreTheaterMode() {
        runCatching { if (theaterWasOn) Settings.Global.putInt(contentResolver, "theater_mode_on", 1) }
        theaterWasOn = false
    }

    // ------------------------------------------------------------------ foreground + notification

    private fun goForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            val ok = runCatching { startForeground(ClockNotify.ID_RING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED); true }.getOrDefault(false)
            if (!ok) runCatching { startForeground(ClockNotify.ID_RING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) }
                .onFailure { Log.e(TAG, "startForeground failed", it) }
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ClockNotify.ID_RING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else startForeground(ClockNotify.ID_RING, n)
        inForeground = true
    }

    private fun placeholder(): Notification {
        ClockNotify.ensureChannels(this)
        return ClockNotify.builder(this, ClockNotify.CH_RING).setContentTitle("Clock").setCategory(Notification.CATEGORY_ALARM).build()
    }

    private fun ringNotification(): Notification {
        val alarm = ringingAlarms.values.lastOrNull()
        val timer = ringingTimers.values.lastOrNull()
        val fullScreen = PendingIntent.getActivity(
            this, 40_000,
            Intent(this, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = ClockNotify.builder(this, ClockNotify.CH_RING)
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)
        if (alarm != null) {
            b.setContentTitle(alarm.label.ifBlank { "Alarm" })
            b.setContentText(ClockFormat.timeFull(this, alarm.hour, alarm.minute) +
                if (ringingAlarms.size + ringingTimers.size > 1) "  ·  ${ringingAlarms.size + ringingTimers.size} ringing" else "")
            b.addAction(Notification.Action.Builder(null, "Snooze", servicePi(ACTION_SNOOZE, alarm.id, 41_000 + alarm.id)).build())
            b.addAction(Notification.Action.Builder(null, "Dismiss", servicePi(ACTION_DISMISS, alarm.id, 42_000 + alarm.id)).build())
        } else if (timer != null) {
            b.setContentTitle("Time's up")
            b.setContentText(timer.label.ifBlank { ClockFormat.duration(timer.lengthMs) + " timer" })
            b.addAction(Notification.Action.Builder(null, "Stop", servicePi(ACTION_TIMER_STOP, timer.id, 43_000 + timer.id)).build())
            b.addAction(Notification.Action.Builder(null, "+1 min", servicePi(ACTION_TIMER_ADD_MINUTE, timer.id, 44_000 + timer.id)).build())
        }
        return b.build()
    }

    private fun servicePi(action: String, id: Int, req: Int) = PendingIntent.getService(
        this, req, Intent(this, RingService::class.java).setAction(action).putExtra(ClockReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun stopEverything() {
        main.removeCallbacksAndMessages(null)
        autoSilence.clear()
        stopSound()
        soundKey = null
        musicMode = false
        restoreMusicVolume()
        restoreTheaterMode()
        runCatching { cpuLock?.let { if (it.isHeld) it.release() } }
        cpuLock = null
        _state.value = RingSnapshot()
        if (inForeground) { stopForeground(STOP_FOREGROUND_REMOVE); inForeground = false }
        ClockNotify.nm(this).cancel(ClockNotify.ID_RING)
        stopSelf()
    }

    override fun onDestroy() {
        if (cpuLock != null) stopEverything()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "HioneClock"
        const val ACTION_RING_ALARM = "com.hione.tools.clock.RING_ALARM"
        const val ACTION_RING_TIMER = "com.hione.tools.clock.RING_TIMER"
        const val ACTION_SNOOZE = "com.hione.tools.clock.SNOOZE"
        const val ACTION_DISMISS = "com.hione.tools.clock.DISMISS"
        const val ACTION_TIMER_STOP = "com.hione.tools.clock.TIMER_STOP"
        const val ACTION_TIMER_ADD_MINUTE = "com.hione.tools.clock.RING_TIMER_ADD_MINUTE"
        const val ACTION_TIMER_HANDLED = "com.hione.tools.clock.TIMER_HANDLED"
        const val EXTRA_KEEP_MUSIC = "keep_music"

        private val _state = MutableStateFlow(RingSnapshot())
        val state: StateFlow<RingSnapshot> get() = _state

        fun isRingingAlarm(id: Int) = _state.value.alarms.any { it.id == id }

        fun startRing(ctx: Context, action: String, id: Int) {
            runCatching {
                ContextCompat.startForegroundService(ctx, Intent(ctx, RingService::class.java).setAction(action).putExtra(ClockReceiver.EXTRA_ID, id))
            }.onFailure { Log.e(TAG, "could not start ring service", it); WakeHandoff.release() }
        }

        /** Control a running ring (from the ring screen, the API handler, or the UI). */
        fun send(ctx: Context, action: String, id: Int = -1, keepMusic: Boolean = false) {
            if (_state.value.isEmpty) return
            runCatching {
                ctx.startService(Intent(ctx, RingService::class.java).setAction(action).putExtra(ClockReceiver.EXTRA_ID, id).putExtra(EXTRA_KEEP_MUSIC, keepMusic))
            }
        }

        /** A ringing timer was reset or extended elsewhere: stop ringing it. */
        fun timerHandled(ctx: Context, id: Int) {
            if (_state.value.timers.none { it.id == id }) return
            send(ctx, ACTION_TIMER_HANDLED, id)
        }
    }
}
