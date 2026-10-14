package com.hione.media.recorder

import com.hione.media.Mascot
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Environment
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import com.hione.media.R
import com.hione.media.ui.HioneSounds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** ARMING: the file exists and the start cue is playing; the microphone opens when it ends. */
enum class RecStatus { IDLE, ARMING, RECORDING, PAUSED }

data class RecUi(
    val status: RecStatus = RecStatus.IDLE,
    val elapsedMs: Long = 0,
    val description: String = "",
    val lastSaved: Uri? = null,
    val savedSeq: Int = 0,
    val error: String? = null
)

/**
 * In-process state shared by the service and the activity. Both live in one process, so a
 * StateFlow is all the plumbing needed; no binder interface to keep in sync.
 *
 * The waveform is a ring of recent meter readings. It is written 20 times a second and the
 * canvas reads it on draw, keyed on [waveTick], so the screen redraws without recomposing the
 * whole recorder.
 */
object RecorderBus {
    val state = MutableStateFlow(RecUi())
    const val WAVE_SIZE = 96
    val wave = FloatArray(WAVE_SIZE)
    @Volatile var waveHead = 0
    val waveTick = MutableStateFlow(0L)
    val level = MutableStateFlow(0f)

    fun clearWave() { wave.fill(0f); waveHead = 0; waveTick.value++ }
    fun push(v: Float) {
        wave[waveHead] = v
        waveHead = (waveHead + 1) % WAVE_SIZE
        waveTick.value++
        level.value = v
    }
}

/**
 * Microphone foreground service. Recording lives here, not in the activity, so it carries on
 * with the screen off or another app in front. Type "microphone" is what Android 14 requires
 * for background capture; it has to be started while the activity is visible, which is the
 * only way the user can start a recording anyway.
 */
class RecorderService : Service() {

    companion object {
        const val ACTION_START = "com.hione.media.recorder.START"
        const val ACTION_PAUSE = "com.hione.media.recorder.PAUSE"
        const val ACTION_RESUME = "com.hione.media.recorder.RESUME"
        const val ACTION_STOP = "com.hione.media.recorder.STOP"
        const val ACTION_DISCARD = "com.hione.media.recorder.DISCARD"
        const val EXTRA_FORMAT = "format"
        const val EXTRA_MAX_BYTES = "max_bytes"
        private const val CHANNEL = "recording"
        private const val NOTIF_ID = 39

        fun send(ctx: Context, action: String, block: Intent.() -> Unit = {}) {
            val i = Intent(ctx, RecorderService::class.java).setAction(action).apply(block)
            if (action == ACTION_START) ctx.startForegroundService(i) else ctx.startService(i)
        }

        val state: StateFlow<RecUi> get() = RecorderBus.state
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var engine: RecordingEngine? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    private var ticker: Job? = null
    private var armJob: Job? = null
    private var resumeJob: Job? = null
    private var wake: PowerManager.WakeLock? = null
    private var startedAt = 0L
    private var accumulated = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        HioneSounds.preload(this, "recorder")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(
                RecFormat.entries.getOrElse(intent.getIntExtra(EXTRA_FORMAT, 0)) { RecFormat.M4A },
                intent.getLongExtra(EXTRA_MAX_BYTES, 0L)
            )
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_STOP -> finish(keep = true)
            ACTION_DISCARD -> finish(keep = false)
            else -> if (engine == null) stopSelf()
        }
        // A killed recorder cannot be resumed meaningfully; do not have the system restart it.
        return START_NOT_STICKY
    }

    private fun start(format: RecFormat, maxBytes: Long) {
        if (engine != null) return
        startForeground(NOTIF_ID, notification(RecStatus.RECORDING, ""), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)

        val name = "Recording_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + "." + format.ext
        val cv = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, format.mime)
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_RECORDINGS + "/")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val u = runCatching {
            contentResolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), cv)
        }.onFailure { Log.e("HioneRecorder", "insert failed", it) }.getOrNull()
        val fd = u?.let { runCatching { contentResolver.openFileDescriptor(it, "rw") }.getOrNull() }
        if (u == null || fd == null) {
            u?.let { runCatching { contentResolver.delete(it, null, null) } }
            fail("Could not create the recording file.")
            return
        }
        val onLimit: () -> Unit = { scope.launch { finish(keep = true) } }
        val e = if (format == RecFormat.WAV) WavEngine(fd, maxBytes, onLimit)
        else MediaRecorderEngine(this, fd, format, maxBytes, onLimit)
        engine = e; pfd = fd; uri = u
        RecorderBus.clearWave()
        RecorderBus.state.update { it.copy(status = RecStatus.ARMING, elapsedMs = 0, description = e.description, error = null) }
        armJob = scope.launch {
            // The cue plays before the microphone opens, so the chime or "Recording started"
            // does not end up as the first second of every recording.
            delay(cueBefore("recording_started", "start"))
            begin(e, fd, u)
        }
    }

    private fun begin(e: RecordingEngine, fd: ParcelFileDescriptor, u: Uri) {
        val ok = runCatching { e.start() }.onFailure { Log.e("HioneRecorder", "start failed", it) }.isSuccess
        if (!ok) {
            engine = null; pfd = null; uri = null
            runCatching { e.stop() }
            runCatching { fd.close() }
            runCatching { contentResolver.delete(u, null, null) }
            fail("The microphone could not be opened. Another app may be using it.")
            return
        }
        accumulated = 0; startedAt = SystemClock.elapsedRealtime()
        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HioneMedia:recorder").apply { acquire(6 * 60 * 60 * 1000L) }
        RecorderBus.state.update { it.copy(status = RecStatus.RECORDING, elapsedMs = 0) }
        ticker = scope.launch {
            var n = 0
            while (isActive) {
                val st = RecorderBus.state.value.status
                if (st == RecStatus.RECORDING) {
                    RecorderBus.push(e.pollLevel())
                    val el = accumulated + (SystemClock.elapsedRealtime() - startedAt)
                    RecorderBus.state.update { it.copy(elapsedMs = el) }
                    // The notification only shows whole seconds; refreshing it 20x a second
                    // would just churn SystemUI.
                    if (++n % 20 == 0) notify(RecStatus.RECORDING, el)
                }
                delay(50)
            }
        }
    }

    private fun pause() {
        val e = engine ?: return
        if (RecorderBus.state.value.status != RecStatus.RECORDING) return
        e.pause()
        accumulated += SystemClock.elapsedRealtime() - startedAt
        RecorderBus.state.update { it.copy(status = RecStatus.PAUSED, elapsedMs = accumulated) }
        RecorderBus.push(0f)
        notify(RecStatus.PAUSED, accumulated)
        // After the pause has taken effect, so the cue is not recorded.
        cueAfter("paused", "pause")
    }

    private fun resume() {
        val e = engine ?: return
        if (RecorderBus.state.value.status != RecStatus.PAUSED || resumeJob?.isActive == true) return
        resumeJob = scope.launch {
            // Same rule as starting: cue first, then open up again.
            delay(cueBefore("resumed", "start"))
            if (engine !== e || RecorderBus.state.value.status != RecStatus.PAUSED) return@launch
            e.resume()
            startedAt = SystemClock.elapsedRealtime()
            RecorderBus.state.update { it.copy(status = RecStatus.RECORDING) }
            notify(RecStatus.RECORDING, accumulated)
        }
    }

    /**
     * Cue played before the microphone (re)opens: the spoken line when the recorder speaks,
     * otherwise the chime. Returns how long to wait for it to finish (0 if nothing played).
     */
    private suspend fun cueBefore(line: String, chime: String): Long {
        if (HioneSounds.speaks("recorder") && HioneSounds.speak("recorder", line)) return HioneSounds.voiceDurationMs("recorder", line)
        if (HioneSounds.sfx("recorder", chime)) return HioneSounds.sfxDurationMs("recorder", chime)
        return 0L
    }

    /** Cue after the microphone is closed or paused; nothing to wait for. */
    private fun cueAfter(line: String, chime: String) {
        if (HioneSounds.speaks("recorder")) HioneSounds.say("recorder", line) else HioneSounds.sfx("recorder", chime)
    }

    private fun finish(keep: Boolean) {
        val e = engine ?: run { stopSelf(); return }
        engine = null
        armJob?.cancel()
        resumeJob?.cancel()
        ticker?.cancel()
        e.stop()
        // A stop within a few milliseconds of start leaves an empty or header-only file. Nothing
        // worth keeping, and an unplayable entry in the list is worse than none.
        val bytes = runCatching { pfd?.statSize ?: 0L }.getOrDefault(0L)
        val keepIt = keep && bytes > 1024
        runCatching { pfd?.close() }
        pfd = null
        wake?.let { if (it.isHeld) it.release() }
        val u = uri
        uri = null
        var saved: Uri? = null
        if (u != null) {
            if (keepIt) {
                // Clearing IS_PENDING publishes the file and makes MediaProvider scan it for
                // duration and tags.
                runCatching { contentResolver.update(u, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null) }
                saved = u
            } else {
                runCatching { contentResolver.delete(u, null, null) }
            }
        }
        RecorderBus.push(0f)
        RecorderBus.state.update {
            it.copy(status = RecStatus.IDLE, lastSaved = saved ?: it.lastSaved, savedSeq = if (saved != null) it.savedSeq + 1 else it.savedSeq)
        }
        if (saved != null) cueAfter("saved", "stop")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun fail(msg: String) {
        RecorderBus.state.update { it.copy(status = RecStatus.IDLE, error = msg) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // Process teardown while recording (task swiped away with the activity): keep what was
        // captured rather than leaving a pending row that MediaProvider deletes a week later.
        if (engine != null) finish(keep = true)
        scope.cancel()
        super.onDestroy()
    }

    // ---------------- notification ----------------

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.rec_channel), NotificationManager.IMPORTANCE_LOW).apply {
                    description = getString(R.string.rec_channel_desc)
                    setShowBadge(false)
                }
            )
        }
    }

    private fun notify(status: RecStatus, elapsed: Long) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(status, com.hione.media.ui.formatDuration(elapsed)))
    }

    private fun pi(action: String, code: Int) = PendingIntent.getService(
        this, code, Intent(this, RecorderService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun notification(status: RecStatus, elapsed: String): Notification {
        ensureChannel()
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, RecorderActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(if (status == RecStatus.PAUSED) "Recording paused" else "Recording")
            .setContentText(elapsed)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setColor(Mascot.argb(0xFFFFAB2E.toInt()))
            .setContentIntent(open)
        if (status == RecStatus.PAUSED) {
            b.addAction(Notification.Action.Builder(null, "Resume", pi(ACTION_RESUME, 1)).build())
        } else {
            b.addAction(Notification.Action.Builder(null, "Pause", pi(ACTION_PAUSE, 2)).build())
        }
        b.addAction(Notification.Action.Builder(null, "Stop", pi(ACTION_STOP, 3)).build())
        return b.build()
    }
}
