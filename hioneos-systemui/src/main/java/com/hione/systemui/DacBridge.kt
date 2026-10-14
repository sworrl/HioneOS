package com.hione.systemui

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * The one path that moves the M500 DAC on HioneOS. Same shape as Hione Music's HibyDacBridge,
 * and the same as com.m500.hardware.DacBridge.
 *
 * Apps cannot touch /sys/devices/platform/sa_sound_setting (SELinux), there is no su, and
 * setprop or `settings put` from an app change nothing the DAC reads. com.hione.sysbridge runs as
 * system_app: it takes a whitelisted value, sets persist.vendor.audio.hione.<knob>, and the image's
 * hione_audio.rc writes the sysfs node, now and again at every boot. See
 * hioneos/docs/hiby-audio-knobs.md.
 *
 * [get] reads the persist property, which platform apps may read. That is the value the hardware
 * was last told, shared by Hione Music, this app and the SystemUI tiles.
 */
object DacBridge {
    private const val TAG = "DacBridge"
    private const val BRIDGE = "com.hione.sysbridge"
    private const val ACTION = "com.hione.sysbridge.AUDIO"

    const val HIGH_POWER = "high_power"
    const val DRE = "dre_mode"
    const val FILTER = "digital_filter"
    const val GAIN = "gain"

    /** Must match com.hione.sysbridge.AudioReceiver.KNOBS. */
    private val ALLOWED: Map<String, Set<String>> = mapOf(
        HIGH_POWER to setOf("hpower_enable", "hpower_disable"),
        DRE to setOf("dremode_enable", "dremode_disable"),
        FILTER to setOf(
            "fast_rolloff_low_latency", "fast_rolloff_phase_compensated",
            "slow_rolloff_low_latency", "slow_rolloff_phase_compensated", "nos",
        ),
        GAIN to setOf("low", "middle", "high"),
    )

    enum class Result { CONFIRMED, NOT_CONFIRMED, NO_BRIDGE, REFUSED }

    /** Plain text for a failed set, or null when it worked. */
    @Volatile var lastProblem: String? = null
        private set

    fun available(ctx: Context): Boolean =
        runCatching { ctx.packageManager.getPackageInfo(BRIDGE, 0); true }.getOrDefault(false)

    private fun sysprop(key: String): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            .invoke(null, key) as String
    }.getOrNull()?.trim()?.ifBlank { null }

    /** What the hardware was last told for [knob], or null when nothing has set it yet. */
    fun get(knob: String): String? = sysprop("persist.vendor.audio.hione.$knob")

    /** Send [value] through the bridge and read it back. Blocks up to a second: not on the main thread. */
    fun set(ctx: Context, knob: String, value: String): Result {
        val r = when {
            ALLOWED[knob]?.contains(value) != true -> Result.REFUSED
            !available(ctx) -> Result.NO_BRIDGE
            else -> {
                runCatching {
                    ctx.applicationContext.sendBroadcast(Intent(ACTION).setPackage(BRIDGE).putExtra(knob, value))
                }.onFailure { Log.w(TAG, "bridge broadcast failed: $it") }
                var ok = false
                for (i in 0 until 10) {
                    if (get(knob) == value) { ok = true; break }
                    Thread.sleep(100)
                }
                if (ok) Result.CONFIRMED else Result.NOT_CONFIRMED
            }
        }
        lastProblem = message(r, knob, value)
        Log.i(TAG, "$knob=$value -> $r")
        return r
    }

    fun message(r: Result, knob: String, value: String): String? = when (r) {
        Result.CONFIRMED -> null
        Result.NO_BRIDGE -> "The HioneOS system bridge (com.hione.sysbridge) is not installed, so DAC settings cannot be applied."
        Result.NOT_CONFIRMED -> "Sent $knob=$value to the system bridge, but the DAC setting did not change. Nothing was applied."
        Result.REFUSED -> "$value is not a value the DAC accepts for $knob."
    }
}
