package com.hione.wheel

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Where the clicker sound goes, as on the 2004 Settings > Clicker menu.
 * SPEAKER plays on the sonification path, which has its own volume, the way the piezo in the
 * case ignored the music volume. HEADPHONES mixes the tick into the music output at the music
 * volume. The M500 has no speaker of its own, so both end up in whatever output is active.
 */
enum class ClickRoute(val label: String) {
    OFF("Off"), SPEAKER("Speaker"), HEADPHONES("Headphones"), BOTH("Both");

    companion object {
        /** The values the era's Clicker setting offered. See docs/clickwheel-era-research.md. */
        fun choices(e: Era): List<ClickRoute> =
            if (e == Era.COLOR_2004) listOf(OFF, SPEAKER, HEADPHONES, BOTH) else listOf(OFF, onRoute(e))

        /** What "On" meant on the On/Off eras: speaker only on 2001, speaker and headphones later. */
        fun onRoute(e: Era): ClickRoute = when (e) {
            Era.MONO_2001 -> SPEAKER
            Era.COLOR_2004 -> SPEAKER
            Era.VIDEO_2005, Era.CLASSIC_2007 -> BOTH
        }

        fun default(e: Era): ClickRoute = onRoute(e)
    }
}

/**
 * Haptic pulse lengths and spacing, all in one place for tuning. The M500 motor is a plain DC
 * motor on a GPIO (timed_gpio): no amplitude, no braking, no overdrive. Pulses under about 20 ms
 * reach the HAL but are not felt, and the motor coasts for a while after it stops. See
 * hioneos/docs/m500-haptics.md for the measurements.
 */
object HapticTuning {
    /** One wheel step. */
    const val STEP_MS = 22L
    /** One of the four switches under the click wheel ring (2004 on). */
    const val RING_MS = 32L
    /** The separate buttons around the 2001 wheel. */
    const val SIDE_MS = 32L
    /** The center button's dome switch: the firmest click. */
    const val CENTER_MS = 36L
    /** Coast-down allowance after a pulse before the next step pulse may start. */
    const val COAST_MS = 15L
    /** Never more step pulses than one per this many ms (about 22 a second). */
    const val STEP_GAP_MS = 45L
}

/** The physical thing being emulated by a haptic pulse. */
enum class Feel(val ms: Long) {
    STEP(HapticTuning.STEP_MS),
    RING(HapticTuning.RING_MS),
    CENTER(HapticTuning.CENTER_MS),
    SIDE(HapticTuning.SIDE_MS),
}

/**
 * The wheel's clicker and the haptic ticks.
 *
 * Sound: the originals had no motor. They ticked a piezo element in the case, driven with a short
 * square-wave burst. Each era's tick is synthesized here (no sampled audio): a few square cycles
 * at the drive frequency Rockbox uses for that hardware, shaped by a resonant band-pass that
 * stands in for the piezo disc.
 *
 * Playback: one low-latency streaming AudioTrack per route, kept running while the wheel is in
 * use and mixed on our own audio thread. Starting a track costs 50 to 150 ms on the M500 (an
 * audio policy round trip and the output waking up), so the stream starts when an era opens
 * and on any input, and stops after 20 idle seconds. A tick then waits at most one small buffer.
 *
 * Haptics: short on/off pulses. The M500 motor has no amplitude control and no effects, so the
 * feel comes from pulse length only. Pulses use the touch usage, so the system's touch feedback
 * switch can still silence them.
 */
class Clicker(ctx: Context) {
    private val app = ctx.applicationContext
    private val hapticThread = HandlerThread("hionepod-haptics", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val hh = Handler(hapticThread.looper)

    private val rate: Int = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
        .takeIf { it in 8000..192000 } ?: 48000
    private val burst: Int = try {
        (app.getSystemService(AudioManager::class.java)
            .getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toInt() ?: 0)
    } catch (_: Throwable) { 0 }.let { if (it in 32..4096) it else rate / 500 }

    @Volatile private var pcm: ShortArray = synth(Era.MONO_2001)

    var era: Era = Era.MONO_2001
        set(v) { if (field != v || pcmFor != v) { field = v; pcm = synth(v); pcmFor = v } }
    private var pcmFor: Era? = Era.MONO_2001

    @Volatile var route: ClickRoute = ClickRoute.SPEAKER

    /** HionePod's own Haptics setting. The system switch is checked on top of this. */
    @Volatile var haptics: Boolean = true

    // ---- vibrator ----
    private val vibrator: Vibrator? = try {
        if (Build.VERSION.SDK_INT >= 31) app.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") app.getSystemService(Vibrator::class.java)
    } catch (_: Throwable) { null }
    private val canVibrate = vibrator?.hasVibrator() == true
    private val effects = Feel.entries.associateWith {
        VibrationEffect.createOneShot(it.ms, VibrationEffect.DEFAULT_AMPLITUDE)
    }
    private val touchAttrs: VibrationAttributes? =
        if (Build.VERSION.SDK_INT >= 33) VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH) else null
    @Suppress("DEPRECATION")
    private val legacyAttrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build()

    // Rate limits, in System.nanoTime (the clock AudioTimestamp uses). UI thread only.
    private var lastSoundAt = 0L
    private var lastBuzzAt = 0L
    private var buzzUntil = 0L

    // System touch feedback switch, read at most twice a second.
    @Volatile private var sysCheckedAt = 0L
    @Volatile private var sysOn = true

    private val debug get() = Log.isLoggable(TAG, Log.DEBUG)

    /** A finger is on the wheel: get the stream going so the first tick is on time. */
    fun wake() = engine.wake()

    /** HionePod left the screen: let the stream stop now instead of after the idle time. */
    fun sleep() = engine.sleep()

    /** One wheel step moved something: a tick and, if not too soon after the last, a pulse. */
    fun step() {
        val at = System.nanoTime()
        sound(at, false)
        buzz(Feel.STEP, at)
    }

    /** A switch closed: the tick plus that switch's pulse. */
    fun press(feel: Feel) {
        val at = System.nanoTime()
        sound(at, true)
        buzz(feel, at)
    }

    /** A tick with no pulse (volume keys: the key already has its own feel). */
    fun soundOnly() = sound(System.nanoTime(), false)

    /** Whether the system's touch feedback switch is on. Without it, pulses are dropped. */
    fun systemHapticsOn(): Boolean {
        val now = System.nanoTime()
        if (now - sysCheckedAt > 500_000_000L) {
            sysCheckedAt = now
            sysOn = try {
                val cr = app.contentResolver
                // System touch vibration, and the HioneOS haptics switch (unset counts as on).
                Settings.System.getInt(cr, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0 &&
                    Settings.Global.getInt(cr, "hione_haptics", 1) != 0
            } catch (_: Throwable) { true }
        }
        return sysOn
    }

    fun release() {
        engine.shutdown()
        hapticThread.quitSafely()
    }

    // ---- sound -------------------------------------------------------------------------------

    private fun sound(at: Long, force: Boolean) {
        val r = route
        if (r == ClickRoute.OFF) return
        // A tick that lands while the last one still sounds is dropped, as Rockbox does on the
        // real piezo (it ignores a click while one is playing). Button presses always sound.
        if (!force && at - lastSoundAt < MIN_SOUND_GAP_NS) return
        lastSoundAt = at
        engine.trigger(at, r)
    }

    private val engine = Engine()

    private class Hit(val at: Long, val route: ClickRoute)

    /** The audio thread: mixes ticks into one or two running low-latency streams. */
    private inner class Engine {
        private val lock = Object()
        private val queue = ConcurrentLinkedQueue<Hit>()
        @Volatile private var lastUse = 0L
        @Volatile private var quit = false
        private var thread: Thread? = null
        private var speaker: AudioTrack? = null
        private var media: AudioTrack? = null
        private val ts = AudioTimestamp()
        private val lat = ArrayList<Double>()

        fun wake() {
            if (route == ClickRoute.OFF) return
            lastUse = System.nanoTime()
            ensure()
        }

        fun sleep() { lastUse = 0L }

        fun trigger(at: Long, r: ClickRoute) {
            queue.add(Hit(at, r))
            lastUse = at
            ensure()
        }

        fun shutdown() {
            quit = true
            synchronized(lock) { lock.notifyAll() }
        }

        private fun ensure() {
            synchronized(lock) {
                if (thread == null && !quit) {
                    thread = Thread({ run() }, "hionepod-clicker").apply { start() }
                }
            }
        }

        private fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val chunk = burst
            val sp = ShortArray(chunk)
            val md = ShortArray(chunk)
            // Voices: start frame offsets into pcm, per route. Small fixed pools.
            val spPos = IntArray(4) { -1 }
            val mdPos = IntArray(4) { -1 }
            var written = 0L
            var spOn = false
            var mdOn = false
            val pendingTimes = ArrayList<Pair<Long, Long>>() // (event time, first frame)
            val startedAt = System.nanoTime()
            try {
                while (!quit) {
                    // New ticks start at the top of the next chunk.
                    while (true) {
                        val hit = queue.poll() ?: break
                        // A tick that could not start within LATE_NS (the stream was still
                        // waking up) is dropped: a late click feels worse than a missing one.
                        if (System.nanoTime() - hit.at > LATE_NS) {
                            if (debug) Log.d(TAG, "tick dropped, ${(System.nanoTime() - hit.at) / 1_000_000} ms late")
                            continue
                        }
                        val toSp = hit.route == ClickRoute.SPEAKER || hit.route == ClickRoute.BOTH
                        val toMd = hit.route == ClickRoute.HEADPHONES || hit.route == ClickRoute.BOTH
                        if (toSp) { if (!spOn) { spOn = start(true) }; add(spPos) }
                        if (toMd) { if (!mdOn) { mdOn = start(false) }; add(mdPos) }
                        if (debug) pendingTimes.add(hit.at to written)
                    }
                    val idle = System.nanoTime() - lastUse > IDLE_NS
                    if (idle && spPos.all { it < 0 } && mdPos.all { it < 0 }) break
                    // Warm the route that is set even before the first tick.
                    val r = route
                    if (!spOn && (r == ClickRoute.SPEAKER || r == ClickRoute.BOTH)) spOn = start(true)
                    if (!mdOn && (r == ClickRoute.HEADPHONES || r == ClickRoute.BOTH)) mdOn = start(false)
                    if (!spOn && !mdOn) { Thread.sleep(2); continue }
                    val p = pcm
                    if (spOn) { mix(sp, spPos, p); speaker?.write(sp, 0, chunk, AudioTrack.WRITE_BLOCKING) }
                    if (mdOn) { mix(md, mdPos, p); media?.write(md, 0, chunk, AudioTrack.WRITE_BLOCKING) }
                    written += chunk
                    if (pendingTimes.isNotEmpty()) report(pendingTimes, if (spOn) speaker else media)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "clicker stream stopped: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                for (t in listOfNotNull(speaker, media)) try { t.pause(); t.flush() } catch (_: Throwable) { }
                if (debug) Log.d(TAG, "stream idle after ${(System.nanoTime() - startedAt) / 1_000_000} ms")
                synchronized(lock) {
                    thread = null
                    if (quit) { speaker?.release(); media?.release(); speaker = null; media = null }
                }
                if (!quit && queue.isNotEmpty()) ensure()
            }
        }

        private fun add(pos: IntArray) {
            val free = pos.indexOfFirst { it < 0 }
            pos[if (free >= 0) free else 0] = 0
        }

        private fun mix(out: ShortArray, pos: IntArray, p: ShortArray) {
            java.util.Arrays.fill(out, 0)
            for (v in pos.indices) {
                var at = pos[v]
                if (at < 0) continue
                var i = 0
                while (i < out.size && at < p.size) {
                    val s = out[i] + p[at]
                    out[i] = s.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    i++; at++
                }
                pos[v] = if (at >= p.size) -1 else at
            }
        }

        private fun start(speakerRoute: Boolean): Boolean {
            val t = (if (speakerRoute) speaker else media) ?: build(speakerRoute)?.also {
                if (speakerRoute) speaker = it else media = it
            } ?: return false
            return try {
                val t0 = System.nanoTime()
                t.play()
                if (debug) Log.d(TAG, "stream start ${if (speakerRoute) "speaker" else "media"} " +
                    "${(System.nanoTime() - t0) / 1000} us, buffer ${t.bufferSizeInFrames} frames, " +
                    "fast=${t.performanceMode == AudioTrack.PERFORMANCE_MODE_LOW_LATENCY}")
                true
            } catch (e: Throwable) { false }
        }

        private fun build(speakerRoute: Boolean): AudioTrack? = try {
            val usage = if (speakerRoute) AudioAttributes.USAGE_ASSISTANCE_SONIFICATION else AudioAttributes.USAGE_MEDIA
            val content = if (speakerRoute) AudioAttributes.CONTENT_TYPE_SONIFICATION else AudioAttributes.CONTENT_TYPE_MUSIC
            val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(content).build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(max(min, burst * 2 * 4))
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build().also {
                    // Keep only a few bursts queued: that queue is the tick's latency.
                    try { it.bufferSizeInFrames = burst * 3 } catch (_: Throwable) { }
                    it.setVolume(if (speakerRoute) SPEAKER_GAIN else HEADPHONE_GAIN)
                    Log.i(TAG, "clicker stream ${if (speakerRoute) "speaker" else "media"}: $rate Hz, " +
                        "burst $burst, buffer ${it.bufferSizeInFrames} of ${it.bufferCapacityInFrames} frames, " +
                        "fast=${it.performanceMode == AudioTrack.PERFORMANCE_MODE_LOW_LATENCY}")
                }
        } catch (t: Throwable) {
            Log.w(TAG, "clicker track failed: ${t.javaClass.simpleName}: ${t.message}"); null
        }

        /** Input event to first tick frame at the output, from the track's timestamp. */
        private fun report(pending: ArrayList<Pair<Long, Long>>, t: AudioTrack?) {
            t ?: return
            if (!t.getTimestamp(ts) || ts.framePosition <= 0) return
            val it = pending.iterator()
            while (it.hasNext()) {
                val (at, frame) = it.next()
                val out = ts.nanoTime + (frame - ts.framePosition) * 1_000_000_000L / rate
                val ms = (out - at) / 1e6
                lat.add(ms)
                if (lat.size > 400) lat.removeAt(0)
                val s = lat.sorted()
                Log.d(TAG, "tick latency %.1f ms (median %.1f, min %.1f, max %.1f, n=%d)".format(
                    ms, s[s.size / 2], s.first(), s.last(), s.size))
                it.remove()
            }
        }
    }

    /**
     * One piezo tick. Drive: [Voice.cycles] square cycles at [Voice.drive] Hz (Rockbox's hardware
     * click values). Body: an RBJ band-pass at the piezo's resonance, plus a little of the
     * high-passed drive for the edge. Peaks near 3 kHz, down 20 dB within about 4 to 5 ms.
     */
    private fun synth(e: Era): ShortArray {
        val v = Voice.of(e)
        val burstN = (v.cycles / v.drive * rate).toInt()
        val n = burstN + (TAIL_S * rate).toInt()
        val x = DoubleArray(n) { i ->
            if (i >= burstN) 0.0 else if (sin(2 * PI * v.drive * i / rate) >= 0) 1.0 else -1.0
        }
        // Band-pass, constant 0 dB peak gain.
        val w = 2 * PI * v.res / rate
        val alpha = sin(w) / (2 * v.q)
        val a0 = 1 + alpha
        val b0 = alpha / a0; val b2 = -alpha / a0
        val a1 = -2 * cos(w) / a0; val a2 = (1 - alpha) / a0
        // One-pole high-pass at 800 Hz: a piezo passes almost nothing low.
        val rc = 1.0 / (2 * PI * 800.0); val hpA = rc / (rc + 1.0 / rate)
        val y = DoubleArray(n)
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        var hpX = 0.0; var hpY = 0.0
        var peak = 1e-9
        for (i in 0 until n) {
            val s = x[i]
            val bp = b0 * s + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = s; y2 = y1; y1 = bp
            hpY = hpA * (hpY + s - hpX); hpX = s
            y[i] = bp + v.edge * hpY
            peak = max(peak, abs(y[i]))
        }
        // Trim the silent end, then a 0.3 ms fade so the buffer never ends on a step.
        var end = n
        while (end > burstN && abs(y[end - 1]) / peak < 0.01) end--
        val fade = max(1, (0.0003 * rate).toInt())
        return ShortArray(end) { i ->
            val f = if (i >= end - fade) (end - i).toDouble() / fade else 1.0
            (y[i] / peak * f * 0.9 * Short.MAX_VALUE).toInt().toShort()
        }
    }

    /**
     * Per-era drive. 2004 and 2005 ran on PortalPlayer chips: Rockbox drives that piezo at
     * period 91 (about 1 kHz) for 4 ms. 2007 (Samsung S5L8702): 4 cycles at 1.25 kHz. Rockbox has
     * no hardware click for the 2001 models, so 2001 uses the PortalPlayer drive with one cycle
     * less (inferred, not measured). Resonance values are typical for small piezo discs.
     */
    private class Voice(val drive: Double, val cycles: Int, val res: Double, val q: Double, val edge: Double) {
        companion object {
            fun of(e: Era) = when (e) {
                Era.MONO_2001 -> Voice(1000.0, 3, 3200.0, 5.0, 0.35)
                Era.COLOR_2004, Era.VIDEO_2005 -> Voice(1000.0, 4, 3000.0, 6.0, 0.35)
                Era.CLASSIC_2007 -> Voice(1250.0, 4, 3750.0, 6.0, 0.35)
            }
        }
    }

    // ---- haptics -----------------------------------------------------------------------------

    private fun buzz(feel: Feel, at: Long) {
        if (!haptics || !canVibrate) return
        if (!systemHapticsOn()) return
        // A new vibration cuts the running one, and the motor coasts after it stops. Steps that
        // land inside the last pulse plus its coast-down, or sooner than STEP_GAP_MS after the
        // last step pulse, are skipped (the sound still plays), so a fast spin stays a row of
        // separate ticks instead of one hum.
        if (feel == Feel.STEP && (at - lastBuzzAt < HapticTuning.STEP_GAP_MS * 1_000_000L || at < buzzUntil)) {
            if (debug) Log.d(TAG, "pulse STEP skipped (rate)")
            return
        }
        lastBuzzAt = at
        buzzUntil = at + (feel.ms + HapticTuning.COAST_MS) * 1_000_000L
        val fx = effects[feel] ?: return
        hh.post {
            try {
                if (touchAttrs != null && Build.VERSION.SDK_INT >= 33) vibrator?.vibrate(fx, touchAttrs)
                else @Suppress("DEPRECATION") vibrator?.vibrate(fx, legacyAttrs)
            } catch (_: Throwable) { }
            if (debug) Log.d(TAG, "pulse ${feel.name} ${feel.ms} ms sent ${(System.nanoTime() - at) / 1000} us after input")
        }
    }

    companion object {
        private const val TAG = "HionePodClick"
        private const val TAIL_S = 0.008
        /** 8 ms: a tick with its ring-down is about that long. Faster than any real spin. */
        private const val MIN_SOUND_GAP_NS = 8_000_000L
        /**
         * The stream stops this long after the last tick, touch or key. Waking it costs 50 to
         * 150 ms on the M500, so it starts when an era opens or HionePod comes back, and stays up
         * while the wheel is in use.
         */
        private const val IDLE_NS = 20_000_000_000L
        private const val LATE_NS = 60_000_000L
        private const val SPEAKER_GAIN = 0.55f
        private const val HEADPHONE_GAIN = 0.30f
    }
}
