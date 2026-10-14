package com.hione.player

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import com.hione.player.bpm.HioneBeatClock
import com.hione.player.bpm.HioneBpmEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The front status light (the Pulsar light) for Hione Music: the playing track's format tier,
 * optionally pulsing at the track's tempo, and blue idle when nothing plays.
 *
 * THE PATH. The light is an SGM31324 that only init can drive (/sys/class/leds is SELinux-denied
 * to apps). HiBy's /vendor/etc/init/hw/init.hiby.led.rc picks led_pattern from
 * vendor.audio.hiby.hw.led, .hw.sample_quality and .charging; those are vendor_audio_prop, which
 * this app (platform_app) may read but not set. com.hione.sysbridge (system_app) sets them for us
 * from a fixed list: broadcast com.hione.sysbridge.LED with extras led, quality and beat. Every
 * decision is made here, so it ships over the air; the bridge only checks values.
 *
 * FORMAT. The tier is TrackTech.trackTier, the same rule the quality badges count with, mapped to
 * HiBy's patterns (sgm31324 spec 7.2; the colours assume D1 red, D2 green, D3 blue, which is
 * inferred and still open):
 *   lossy          low        pattern 2   D1 0.75 mA + D2 0.25 mA
 *   CD lossless    standard   pattern 3   D2 0.25 mA + D3 0.125 mA
 *   studio hi-res  high       pattern 4   D1 0.875 mA + D2 0.125 mA
 *   master, DSD    mqb        pattern 10  D1 0.5 mA + D3 0.125 mA
 * DSD does not use HiBy's "dsd": pattern 5 switches the chip off and lights a white GPIO the M500
 * does not have, so it shows nothing (spec bug 10.7). MQA is never claimed; nothing here decodes it.
 * A lossless file whose header has not been read yet shows "standard" until it has.
 *
 * BEAT. The chip runs its own timers and takes no per-beat commands, so the light gets one
 * register set per tempo: the tier's colour on PWM1, with a flash period close to one beat (or 2
 * or 4 beats when one beat does not fit the chip's 128 ms period steps within 3 %), a short
 * on-time, an instant rise and a fall of about 40 % of a beat. It is written once per track or
 * tempo change, at most once every 8 s, and timed to land on a beat when the beat grid has a
 * phase. After that the chip runs free, so it drifts by the period rounding (up to a few percent).
 * Needs an image with hione_led_beat.rc (HIONEOS_LED_BPM=1, ro.vendor.audio.hione.led_beat=1); that
 * rc writes the registers to write_pattern while sample_quality is "beat". Without it the light
 * stays on the steady format colour.
 *
 * CHARGING keeps priority without anything here: HiBy's format rules and ours need charging=no.
 *
 * SWITCH. PlayerPreferences pulsar_light_enabled (default on). Off sets vendor.audio.hiby.hw.led
 * off, which also hides the charging patterns; the bridge keeps that choice across reboots.
 */
object HionePulse {
    private const val TAG = "HionePulse"
    private const val BRIDGE = "com.hione.sysbridge"
    private const val ACTION_LED = "com.hione.sysbridge.LED"
    private const val PROP_BEAT_RC = "ro.vendor.audio.hione.led_beat"

    /** While playing: how often the tier and the tempo are looked at again. */
    private const val POLL_MS = 3_000L
    /** Least time between two beat patterns for the same track. */
    private const val MIN_BEAT_GAP_MS = 8_000L
    /** A tempo from the live tracker alone must hold this long before it is written. */
    private const val LIVE_TEMPO_HOLD_MS = 3_000L
    /** Broadcast, bridge, init and I2C before the chip sees a write. An estimate, not measured. */
    private const val PATH_LATENCY_NS = 40_000_000L
    /** A lossless header still unread after this many polls keeps the provisional colour. */
    private const val MAX_TIER_POLLS = 10

    /** HiBy's pattern for a tier: the sample_quality value and the pattern's reg 4 and current bytes. */
    enum class Light(val quality: String, val reg4: Int, val d1: Int, val d2: Int, val d3: Int) {
        LOSSY("low", 0x45, 0x05, 0x01, 0x00),
        CD("standard", 0x54, 0x00, 0x01, 0x00),
        HI_RES("high", 0x45, 0x06, 0x00, 0x00),
        MASTER("mqb", 0x51, 0x03, 0x00, 0x00);

        companion object {
            fun ofTier(tier: Int): Light = when (tier) {
                4 -> MASTER
                3 -> HI_RES
                2 -> CD
                else -> LOSSY
            }
        }
    }

    private data class Want(val key: String, val uriPath: String, val mime: String, val playing: Boolean)

    private val scope = CoroutineScope(SupervisorJob() + Executors.newSingleThreadExecutor { r ->
        Thread(r, "hione-pulse").apply { isDaemon = true }
    }.asCoroutineDispatcher())
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var driver: Job? = null
    @Volatile private var app: Context? = null
    @Volatile private var want: Want? = null
    /** Cached [relayAvailable]; null = ask again. Cleared by [attach] and the switches. */
    @Volatile private var relayOk: Boolean? = null

    // Driver-only state (one coroutine on one thread touches these).
    private var sentLed: String? = null
    private var sentQuality: String? = null
    private var sentBeat: String? = null
    private var trackKey: String? = null
    private var light: Light = Light.CD
    private var lightFinal = false
    private var tierPolls = 0
    private var lastBeatAtMs = 0L
    private var beatLight: Light? = null
    private var beatTrack: String? = null
    private var liveCandidate: String? = null
    private var liveCandidateAtMs = 0L

    /** Whether the bridge on this image has the LED receiver. */
    fun relayAvailable(ctx: Context): Boolean = runCatching {
        ctx.packageManager.queryBroadcastReceivers(Intent(ACTION_LED).setPackage(BRIDGE), 0).isNotEmpty()
    }.getOrDefault(false)

    /** Whether the image can pulse the light (hione_led_beat.rc). */
    fun beatSupported(): Boolean = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            .invoke(null, PROP_BEAT_RC) as String
    }.getOrNull() == "1"

    /** Once, when the player is built: puts the light in step with the switch and with "nothing playing". */
    fun attach(ctx: Context) {
        app = ctx.applicationContext
        relayOk = null
        ensureDriver()
        kick()
    }

    /** Playback or track changed. Main thread; returns at once. */
    fun onPlayback(ctx: Context, item: MediaItem?, playing: Boolean) {
        if (app == null) app = ctx.applicationContext
        want = item?.let {
            val local = it.localConfiguration
            Want(it.mediaId, local?.uri?.path ?: "", local?.mimeType ?: "", playing)
        }
        ensureDriver()
        kick()
    }

    /** The player was released. */
    fun onStopped() {
        want = null
        if (app != null) { ensureDriver(); kick() }
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        PlayerPreferences.savePulsarEnabled(ctx, on)
        if (app == null) app = ctx.applicationContext
        relayOk = null
        ensureDriver()
        kick()
    }

    fun setBeatEnabled(ctx: Context, on: Boolean) {
        PlayerPreferences.savePulsarBeatEnabled(ctx, on)
        if (app == null) app = ctx.applicationContext
        ensureDriver()
        kick()
    }

    private fun kick() { wake.trySend(Unit) }

    @Synchronized
    private fun ensureDriver() {
        if (driver?.isActive == true) return
        driver = scope.launch {
            while (isActive) {
                val a = app
                val polling = a != null && runCatching { step(a) }
                    .onFailure { Log.w(TAG, "step failed: $it") }.getOrDefault(false)
                if (polling) withTimeoutOrNull(POLL_MS) { wake.receive() } else wake.receive()
            }
        }
    }

    /** One look at the state. Returns true while it should be looked at again on a timer. */
    private suspend fun step(a: Context): Boolean {
        val relay = relayOk ?: relayAvailable(a).also { relayOk = it }
        if (!relay) return false
        if (!PlayerPreferences.loadPulsarEnabled(a)) {
            send(a, led = "off")
            trackKey = null
            return false
        }
        // The bridge sets quality before led, so switching on never flashes a stale pattern.
        val w = want
        if (w == null || !w.playing) {
            send(a, quality = "none", led = "on")
            trackKey = null
            return false
        }
        if (w.key != trackKey) {
            trackKey = w.key
            tierPolls = 0
            liveCandidate = null
            resolveLight(a, w)
        } else if (!lightFinal) {
            resolveLight(a, w)
        }
        render(a)
        send(a, led = "on")
        return true
    }

    private fun resolveLight(a: Context, w: Want) {
        tierPolls++
        val path = w.uriPath.lowercase()
        if (path.endsWith(".dsf") || path.endsWith(".dff")) { light = Light.MASTER; lightFinal = true; return }
        val id = w.key.toLongOrNull()
        val track = id?.let { tid -> runCatching { FastLibraryStore.loadSync(a)?.firstOrNull { it.id == tid } }.getOrNull() }
        if (track == null) {
            // Not in the library (a file opened from elsewhere): container only.
            val ext = path.substringAfterLast('.', "")
            val mime = w.mime.ifBlank { "audio/$ext" }
            light = Light.ofTier(TrackTech.trackTier(0, 0, mime))
            lightFinal = true
            return
        }
        val bits = TrackTech.bitsFor(a, track)          // null until the header has been read
        val rate = TrackTech.sampleRateFor(a, track) ?: 0
        light = Light.ofTier(TrackTech.trackTier(bits ?: 0, rate, track.mime))
        lightFinal = bits != null || tierPolls >= MAX_TIER_POLLS
    }

    /** Steady format colour, or the beat pattern when the image, the switch and a tempo allow it. */
    private suspend fun render(a: Context) {
        val (bpm, fromFile) = tempo()
        val hex = if (bpm > 0f && beatSupported() && PlayerPreferences.loadPulsarBeatEnabled(a)) beatHex(light, bpm) else null
        if (hex == null) {
            send(a, quality = light.quality)
            return
        }
        if (hex == sentBeat && sentQuality == "beat" && beatTrack == trackKey) return
        val now = SystemClock.elapsedRealtime()
        if (!fromFile && hex != liveCandidate) { liveCandidate = hex; liveCandidateAtMs = now }
        val inBeat = sentQuality == "beat" && beatTrack == trackKey
        val wait = when {
            // A tempo from the live tracker alone has to hold first.
            !fromFile && now - liveCandidateAtMs < LIVE_TEMPO_HOLD_MS -> true
            // Same track and colour: a new tempo waits out the gap. A new colour goes at once.
            inBeat && light == beatLight && now - lastBeatAtMs < MIN_BEAT_GAP_MS -> true
            else -> false
        }
        if (wait) {
            // Meanwhile the steady colour, never the last track's pulse.
            if (!inBeat) send(a, quality = light.quality)
            return
        }
        alignToBeat()
        send(a, beat = hex, quality = "beat")
        lastBeatAtMs = SystemClock.elapsedRealtime()
        beatLight = light
        beatTrack = trackKey
    }

    /** The track's analysed tempo, else the live tracker's once it is phase-locked, else 0. */
    private fun tempo(): Pair<Float, Boolean> {
        val file = HioneBpmEngine.currentBpm
        if (file > 0f) return file to true
        val g = HioneBeatClock.grid
        if (g.bpm > 0f && g.phaseLocked) return g.bpm to false
        return 0f to false
    }

    /** Waits until just before the next beat, when the beat grid has a phase. */
    private suspend fun alignToBeat() {
        if (HioneBpmEngine.beatGridIntervalNanos <= 0.0) return
        val g = HioneBeatClock.grid
        if (g.periodNs <= 0.0 || g.anchorNs == 0L) return
        val now = SystemClock.elapsedRealtimeNanos()
        val next = g.nextBeatAtOrAfter(now + PATH_LATENCY_NS + 5_000_000L)
        val waitNs = next - PATH_LATENCY_NS - now
        if (waitNs in 1..2_000_000_000L) delay(waitNs / 1_000_000L)
    }

    /**
     * The ten registers (sgm31324 spec 2.4) for [light] pulsing at [bpm], as 20 hex digits, or
     * null when no period fits. Reg 1 period is 0.128 s x (n + 2), n = 1..127.
     */
    internal fun beatHex(light: Light, bpm: Float): String? {
        if (bpm !in 40f..250f) return null
        val beatS = 60.0 / bpm
        var bestN = -1
        var bestErr = Double.MAX_VALUE
        for (k in intArrayOf(1, 2, 4)) {
            val wantS = beatS * k
            val n = (wantS / 0.128).roundToInt() - 2
            if (n !in 1..127) continue
            val err = abs(0.128 * (n + 2) - wantS) / wantS
            if (err < bestErr) { bestErr = err; bestN = n }
            if (err <= 0.03) break
        }
        if (bestN < 0) return null
        val periodS = 0.128 * (bestN + 2)
        val onTime = (0.3 * beatS / periodS * 256).roundToInt().coerceIn(1, 255)
        val fall = (0.4 * beatS / 0.096).roundToInt().coerceIn(1, 15)
        // Every channel the pattern has on (01) runs on PWM1 (10) instead.
        var reg4 = 0x40
        for (shift in intArrayOf(0, 2, 4)) {
            if ((light.reg4 shr shift) and 0x3 == 0x1) reg4 = reg4 or (0x2 shl shift)
        }
        val regs = intArrayOf(
            0x18,           // 0: always on, ramp scale x1
            bestN,          // 1: S-curve ramp, flash period
            onTime,         // 2: PWM1 on-time, n/256 of the period
            0x00,           // 3: PWM2, unused
            reg4,           // 4: channels on PWM1, DEV_EN
            fall,           // 5: rise 1.5 ms, fall 96 ms x fall
            light.d1, light.d2, light.d3,   // 6-8: the tier pattern's currents
            0x06,           // 9: auto blink off
        )
        return regs.joinToString("") { "%02x".format(it) }
    }

    /** Sends what changed since the last send. Driver thread only. */
    private fun send(a: Context, led: String? = null, quality: String? = null, beat: String? = null) {
        val i = Intent(ACTION_LED).setPackage(BRIDGE)
        var any = false
        if (beat != null && beat != sentBeat) { i.putExtra("beat", beat); sentBeat = beat; any = true }
        if (quality != null && (quality != sentQuality || beat != null)) { i.putExtra("quality", quality); sentQuality = quality; any = true }
        if (led != null && led != sentLed) { i.putExtra("led", led); sentLed = led; any = true }
        if (!any) return
        runCatching { a.sendBroadcast(i) }
            .onSuccess { Log.i(TAG, "light: led=$led quality=$quality beat=$beat (${light.name})") }
            .onFailure {
                Log.w(TAG, "bridge broadcast failed: $it")
                sentLed = null; sentQuality = null; sentBeat = null
            }
    }
}
