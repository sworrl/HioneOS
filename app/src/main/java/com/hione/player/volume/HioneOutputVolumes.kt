package com.hione.player.volume

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hione.player.HioneMirrorOutput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One volume per output while playing to several at once.
 *
 * THE PROBLEM. Android keeps a separate STREAM_MUSIC index for every output device, and the
 * volume keys (and the knob, and setStreamVolume) only move the index of the device the policy
 * currently routes music to. With a USB headset plugged in that is the USB device, so the keys
 * drove the USB pair while the 4.4mm pair, which HioneMirrorOutput pins the bit-perfect track to,
 * sat at whatever level it was last left at and never moved. Measured 2026-10-09: "the 4.4 were
 * SUPER quiet and never changed with the meter".
 *
 * WHAT THIS DOES.
 *  - LINKED: any change to the music volume, from any source, is a step on one device. While
 *    sharing, the same step is applied to every other shared output, so they rise and fall
 *    together and keep their balance. The knob and keys become a master.
 *  - PER OUTPUT: [setLevel] sets one device's own index, for the sliders in the volume HUD, which
 *    is how the balance gets set in the first place.
 *
 * HOW. AudioManager.getDeviceVolume / setDeviceVolume (Android 14, @SystemApi, needs
 * MODIFY_AUDIO_SETTINGS_PRIVILEGED or MODIFY_AUDIO_ROUTING, which this platform-signed app
 * declares). Reached by reflection because the public SDK stubs do not carry system APIs. If the
 * platform refuses them, [available] goes false and the HUD shows the outputs without sliders
 * rather than sliders that do nothing.
 */
object HioneOutputVolumes {

    private const val TAG = "HioneOutputVolumes"

    data class Output(
        val id: Int,
        val label: String,
        val type: Int,
        val index: Int,
        val max: Int,
        /** Carries the bit-perfect track; the others are mirrors. */
        val primary: Boolean,
    ) {
        val pct: Int get() = if (max <= 0) 0 else index * 100 / max
    }

    private val _outputs = MutableStateFlow<List<Output>>(emptyList())
    /** The shared outputs, primary first. Empty when only one output is playing. */
    val outputs: StateFlow<List<Output>> = _outputs

    @Volatile var available: Boolean = true
        private set

    private var app: Context? = null
    private val main = Handler(Looper.getMainLooper())
    /** Last index we know each shared device was at, so a change can be read as a step. */
    private val snapshot = HashMap<Int, Int>()
    private var devices: List<AudioDeviceInfo> = emptyList()

    fun init(ctx: Context) {
        if (app != null) return
        val a = ctx.applicationContext
        app = a
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                val stream = i?.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) ?: -1
                if (stream == AudioManager.STREAM_MUSIC) onMusicVolumeChanged()
            }
        }
        runCatching { a.registerReceiver(receiver, IntentFilter("android.media.VOLUME_CHANGED_ACTION")) }
        refresh()
    }

    /** Re-read which outputs are shared and where each one's volume is. HioneMirrorOutput calls this. */
    fun refresh() {
        val a = app ?: return
        main.post {
            val planned = HioneMirrorOutput.plannedOutputs(a)
            devices = if (planned.size >= 2) planned else emptyList()
            snapshot.keys.retainAll(devices.map { it.id }.toSet())
            for (d in devices) readIndex(d)?.let { snapshot[d.id] = it }
            publish()
        }
    }

    /** Set one output's own level, 0..its max. For the per-output sliders. */
    fun setLevel(id: Int, index: Int) {
        val d = devices.firstOrNull { it.id == id } ?: return
        if (writeIndex(d, index)) snapshot[id] = index.coerceIn(0, maxIndex())
        publish()
    }

    /**
     * The music volume moved somewhere. If exactly one shared output changed, by the
     * snapshot, apply the same step to the rest. Our own writes update the snapshot first,
     * so the broadcasts they cause find nothing new and cannot loop.
     */
    private fun onMusicVolumeChanged() {
        if (devices.isEmpty()) return
        val now = devices.associate { it.id to (readIndex(it) ?: snapshot[it.id] ?: 0) }
        val changed = now.filter { (id, idx) -> snapshot[id] != null && snapshot[id] != idx }
        if (changed.size == 1) {
            val (srcId, srcIdx) = changed.entries.first()
            val step = srcIdx - snapshot[srcId]!!
            val max = maxIndex()
            Log.i(TAG, "step $step on ${label(devices.first { it.id == srcId })}, applying to the other outputs")
            snapshot[srcId] = srcIdx
            for (d in devices) if (d.id != srcId) {
                val target = ((snapshot[d.id] ?: now[d.id] ?: 0) + step).coerceIn(0, max)
                if (writeIndex(d, target)) snapshot[d.id] = target
            }
        } else {
            snapshot.putAll(now)
        }
        publish()
    }

    private fun publish() {
        val max = maxIndex()
        _outputs.value = devices.mapIndexed { i, d ->
            Output(d.id, label(d), d.type, snapshot[d.id] ?: 0, max, primary = i == 0)
        }
    }

    private fun maxIndex(): Int {
        val am = app?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return 15
        return am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    }

    /** What the user calls it: the jack, or the product name without the "USB-Audio - " prefix. */
    fun label(d: AudioDeviceInfo): String {
        val name = d.productName?.toString().orEmpty()
        return when (d.type) {
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES ->
                if (name.contains("balance", ignoreCase = true)) "4.4mm" else "3.5mm"
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY ->
                "USB " + name.removePrefix("USB-Audio - ").ifBlank { "audio" }
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER ->
                name.ifBlank { "Bluetooth" }
            else -> name.ifBlank { "Output ${d.type}" }
        }
    }

    // ------------------------------------------------------------------ the system API, by reflection

    private val viClass by lazy { Class.forName("android.media.VolumeInfo") }
    private val adaClass by lazy { Class.forName("android.media.AudioDeviceAttributes") }

    private fun ada(d: AudioDeviceInfo): Any =
        adaClass.getConstructor(AudioDeviceInfo::class.java).newInstance(d)

    private fun volumeInfo(index: Int?): Any {
        val bc = Class.forName("android.media.VolumeInfo\$Builder")
        val b = bc.getConstructor(Int::class.javaPrimitiveType).newInstance(AudioManager.STREAM_MUSIC)
        val intArg = Int::class.javaPrimitiveType
        // Same range as the stream, so the platform's rescale is the identity.
        bc.getMethod("setMinVolumeIndex", intArg).invoke(b, 0)
        bc.getMethod("setMaxVolumeIndex", intArg).invoke(b, maxIndex())
        if (index != null) bc.getMethod("setVolumeIndex", intArg).invoke(b, index)
        return bc.getMethod("build").invoke(b)!!
    }

    private fun readIndex(d: AudioDeviceInfo): Int? = runCatching {
        val am = app!!.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val vi = AudioManager::class.java.getMethod("getDeviceVolume", viClass, adaClass)
            .invoke(am, volumeInfo(null), ada(d))
        viClass.getMethod("getVolumeIndex").invoke(vi) as Int
    }.onFailure { fail("getDeviceVolume", it) }.getOrNull()

    private fun writeIndex(d: AudioDeviceInfo, index: Int): Boolean = runCatching {
        val am = app!!.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        AudioManager::class.java.getMethod("setDeviceVolume", viClass, adaClass)
            .invoke(am, volumeInfo(index.coerceIn(0, maxIndex())), ada(d))
        true
    }.onFailure { fail("setDeviceVolume", it) }.getOrDefault(false)

    private var reported = false
    private fun fail(what: String, t: Throwable) {
        val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
        if (cause is SecurityException || cause is NoSuchMethodException || cause is ClassNotFoundException) {
            available = false
        }
        if (!reported) { reported = true; Log.w(TAG, "$what unavailable: $cause") }
    }
}
