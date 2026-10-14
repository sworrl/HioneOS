package com.hione.player

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * External USB DAC OUTPUT (the M500 driving a USB dongle/desktop DAC over OTG).
 *
 * The audio-policy `usb` module is present on this build (usb_audio_policy_configuration.xml), so
 * when a USB sink enumerates we:
 *   1. route the shared ExoPlayer to it (`setPreferredAudioDevice`, on the player's thread — same
 *      pattern as HioneCarAudioRouter, which keeps its veto: in car mode with "Block USB Audio" on,
 *      the car router wins and we do NOT grab the route);
 *   2. on Android 14 ask the framework for a BIT-PERFECT mixer for that device
 *      (AudioManager.getSupportedMixerAttributes + setPreferredMixerAttributes) so USB playback
 *      bypasses the mixer/SRC exactly like the internal DACs' DirectPCM path. HioneDirectAudioSink
 *      already opens AudioTracks at the source's native rate/depth, which is what the bit-perfect
 *      mixer requires to engage;
 *   3. optionally hold STREAM_MUSIC at max ("volume passthrough" — let the DAC's own knob do
 *      volume; previous index restored when the DAC leaves or the toggle turns off).
 *
 * State is Compose-observable for the Sound Settings card and the "USB DAC" quality chips.
 */
object HioneUsbDacOutput {
    private const val TAG = "HioneUsbDac"
    private const val PREFS = "hione_usb_dac"

    data class UsbDac(
        val id: Int,
        val name: String,
        val type: Int,
        val sampleRates: IntArray,
        val encodings: IntArray,
        val channelCounts: IntArray,
    ) {
        val ratesLabel: String
            get() = if (sampleRates.isEmpty()) "any rate" else {
                val mx = sampleRates.max()
                "up to ${if (mx % 1000 == 0) "${mx / 1000}" else "%.1f".format(mx / 1000f)} kHz"
            }
        val bitsLabel: String
            get() = when {
                encodings.contains(android.media.AudioFormat.ENCODING_PCM_32BIT) -> "32-bit"
                encodings.contains(android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED) -> "24-bit"
                encodings.contains(android.media.AudioFormat.ENCODING_PCM_FLOAT) -> "float"
                encodings.isEmpty() -> ""
                else -> "16-bit"
            }
    }

    var device by mutableStateOf<UsbDac?>(null); private set
    /** True once we actually pointed the player at the USB sink. */
    var routed by mutableStateOf(false); private set
    /** "BIT-PERFECT" when the API-34 preferred mixer engaged, "SHARED" otherwise, "" when n/a. */
    var mixerMode by mutableStateOf(""); private set

    var preferUsb by mutableStateOf(true); private set
    var volumePassthrough by mutableStateOf(false); private set

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var app: Context? = null
    @Volatile private var savedVolumeIndex: Int = -1
    @Volatile private var registered = false

    fun init(ctx: Context) {
        if (registered) return
        val a = ctx.applicationContext
        app = a
        val p = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        preferUsb = p.getBoolean("prefer_usb", true)
        volumePassthrough = p.getBoolean("vol_passthrough", false)
        val am = a.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            am.registerAudioDeviceCallback(object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = rescan("added")
                override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = rescan("removed")
            }, main)
            registered = true
        } catch (t: Throwable) {
            Log.e(TAG, "device callback register failed", t)
        }
        rescan("init")
    }

    fun setPreferUsb(ctx: Context, on: Boolean) {
        init(ctx)
        preferUsb = on
        app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putBoolean("prefer_usb", on)?.apply()
        rescan("preferToggle")
    }

    fun setVolumePassthrough(ctx: Context, on: Boolean) {
        init(ctx)
        volumePassthrough = on
        app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putBoolean("vol_passthrough", on)?.apply()
        applyVolumePassthrough(on && routed)
    }

    private val USB_SINKS = setOf(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET)

    /** Re-evaluate, e.g. when HioneMirrorOutput starts or stops sharing. */
    fun refresh(reason: String) = rescan(reason)

    private fun rescan(reason: String) {
        val a = app ?: return
        val am = a.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val usb = runCatching {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.isSink && it.type in USB_SINKS }
        }.getOrNull()
        if (usb == null) {
            if (device != null) Log.i(TAG, "USB DAC gone ($reason)")
            device = null
            if (routed) unroute(a)
            return
        }
        device = UsbDac(
            id = usb.id,
            name = usb.productName?.toString().orEmpty().ifBlank { "USB Audio Device" },
            type = usb.type,
            sampleRates = usb.sampleRates,
            encodings = usb.encodings,
            channelCounts = usb.channelCounts,
        )
        Log.i(TAG, "USB DAC present ($reason): ${device!!.name} rates=${usb.sampleRates.joinToString()} encodings=${usb.encodings.joinToString()}")
        // Playing to every output at once (HioneMirrorOutput) owns the routing then: the player's
        // own track goes to the primary and the USB device gets a mirror. A bit-perfect mixer on
        // the USB device would refuse that mirror (it admits one format-matched track), so it
        // is cleared too. Note this does not unroute through setPreferredAudioDevice(null),
        // which would undo the primary the mirror just chose.
        if (HioneMirrorOutput.isSharing(a)) {
            if (routed || mixerMode.isNotEmpty()) {
                routed = false
                mixerMode = ""
                applyVolumePassthrough(false)
                if (Build.VERSION.SDK_INT >= 34) clearBitPerfectMixer(a)
            }
            Log.i(TAG, "sharing to every output; USB is a mirror, not the route ($reason)")
            return
        }
        // Car projection veto: in car mode USB carries video; the car router forces analog.
        val carBlocks = !PlayerPreferences.loadAllowUsbAudio(a) && HioneCarAudioRouter.isCarUiMode(a)
        if (preferUsb && !carBlocks) route(a, usb) else Log.i(TAG, "not routing (preferUsb=$preferUsb carBlocks=$carBlocks)")
    }

    private fun route(a: Context, usb: AudioDeviceInfo) {
        val player = PlayerHolder.player ?: run { Log.w(TAG, "no player yet"); return }
        val bitPerfect = if (Build.VERSION.SDK_INT >= 34) applyBitPerfectMixer(a, usb) else false
        mixerMode = if (bitPerfect) "BIT-PERFECT" else "SHARED"
        val looper = player.applicationLooper
        val apply = {
            runCatching {
                player.setPreferredAudioDevice(usb)
                routed = true
                Log.i(TAG, "routed player → USB '${usb.productName}' ($mixerMode)")
            }.onFailure { Log.e(TAG, "setPreferredAudioDevice failed", it) }
            Unit
        }
        if (Looper.myLooper() == looper) apply() else Handler(looper).post(apply)
        applyVolumePassthrough(volumePassthrough)
    }

    private fun unroute(a: Context) {
        routed = false
        mixerMode = ""
        applyVolumePassthrough(false)
        val player = PlayerHolder.player ?: return
        val looper = player.applicationLooper
        val apply = {
            runCatching { player.setPreferredAudioDevice(null) }
            Log.i(TAG, "route cleared (back to internal DACs)")
            Unit
        }
        if (Looper.myLooper() == looper) apply() else Handler(looper).post(apply)
        if (Build.VERSION.SDK_INT >= 34) clearBitPerfectMixer(a)
    }

    /** Android 14 bit-perfect USB: prefer a MIXER_BEHAVIOR_BIT_PERFECT mixer for USAGE_MEDIA on
     *  this device. The framework then routes format-matching AudioTracks around the mixer/SRC. */
    @androidx.annotation.RequiresApi(34)
    private fun applyBitPerfectMixer(a: Context, usb: AudioDeviceInfo): Boolean = runCatching {
        val am = a.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val supported = am.getSupportedMixerAttributes(usb)
        val bit = supported.firstOrNull { it.mixerBehavior == android.media.AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }
            ?: runCatching {
                // If the HAL did not pre-enumerate a bit-perfect configuration, construct one directly
                // with the DAC's highest bit-depth and sample rate:
                val bestRate = usb.sampleRates.maxOrNull() ?: 48000
                val enc = when {
                    usb.encodings.contains(AudioFormat.ENCODING_PCM_32BIT) -> AudioFormat.ENCODING_PCM_32BIT
                    usb.encodings.contains(AudioFormat.ENCODING_PCM_24BIT_PACKED) -> AudioFormat.ENCODING_PCM_24BIT_PACKED
                    else -> AudioFormat.ENCODING_PCM_16BIT
                }
                val format = AudioFormat.Builder()
                    .setSampleRate(bestRate)
                    .setEncoding(enc)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
                android.media.AudioMixerAttributes.Builder(format)
                    .setMixerBehavior(android.media.AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT)
                    .build()
            }.getOrNull()
        if (bit == null) {
            Log.i(TAG, "no bit-perfect mixer offered (supported=${supported.size})")
            return false
        }
        val attrs = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val ok = am.setPreferredMixerAttributes(attrs, usb, bit)
        Log.i(TAG, "setPreferredMixerAttributes bit-perfect → $ok (${bit.format})")
        ok
    }.getOrDefault(false)

    @androidx.annotation.RequiresApi(34)
    private fun clearBitPerfectMixer(a: Context) = runCatching {
        val am = a.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val attrs = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        // Best effort: we don't keep the device handle after removal; clearing needs it, so try
        // every USB device still listed (usually none — the DAC just unplugged).
        val am2 = app?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        am2?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.filter { it.type in USB_SINKS }?.forEach {
            runCatching { am.clearPreferredMixerAttributes(attrs, it) }
        }
    }.let { }

    /** Hold media volume at max while a DAC with its own volume control is attached. */
    private fun applyVolumePassthrough(on: Boolean) {
        val a = app ?: return
        val am = a.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        runCatching {
            if (on) {
                if (savedVolumeIndex < 0) savedVolumeIndex = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
                Log.i(TAG, "volume passthrough ON (saved index $savedVolumeIndex)")
            } else if (savedVolumeIndex >= 0) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolumeIndex, 0)
                Log.i(TAG, "volume passthrough OFF (restored $savedVolumeIndex)")
                savedVolumeIndex = -1
            }
        }
    }

    /** Short chip text for the quality-badge rows, or null when no USB DAC is engaged. */
    fun badgeText(): String? {
        val d = device ?: return null
        if (!routed) return null
        return "USB DAC"
    }

    fun statusLine(): String {
        val d = device ?: return "No USB DAC attached"
        val route = if (routed) "routed" + (if (mixerMode.isNotBlank()) " · $mixerMode" else "") else "attached, not routed"
        return "${d.name} · ${d.bitsLabel} ${d.ratesLabel} · $route"
    }
}
