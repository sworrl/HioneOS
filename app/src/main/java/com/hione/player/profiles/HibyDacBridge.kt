package com.hione.player.profiles

import android.os.IBinder
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Sets the three vendor properties that actually move the M500's DAC, through HiBy's AudioService.
 *
 * WHY THIS ROUTE. /vendor/etc/init/hw/init.hiby.audio.rc writes the sa_sound_setting sysfs nodes
 * when, and only when, these properties change:
 *     vendor.audio.hiby.high_power      hpower_enable | hpower_disable
 *     vendor.audio.hiby.dre_mode        dremode_enable | dremode_disable
 *     vendor.audio.hiby.digital_filter  fast_rolloff_low_latency | ... (the kernel's own strings)
 * Hione apps run as platform_app, which SELinux lets set almost no properties (not
 * vendor_audio_prop). The writes the app already made — Settings.Global rows and
 * AudioManager.setParameters("vendor.audio.hiby.hw.*") — land on rows and properties that
 * nothing reads live (the audio HAL has no hiby keys; AudioService only re-reads high_power and
 * dre_mode at boot). HiBy added IAudioService.setProperties(key, value) to the framework, which
 * calls SystemProperties.set inside system_server with no permission check; system_server is
 * allowed vendor_audio_prop (HiBy's own boot code sets vendor.audio.hiby.high_power from there).
 * IAudioService is a hidden API; platform-signed apps are exempt from the hidden-API block.
 *
 * 2026-10-10 UPDATE: IAudioService.setProperties does not exist in the v1.00 framework HioneOS
 * ships (it is in a newer HiBy build), so this route returned UNAVAILABLE everywhere. The working
 * route is com.hione.sysbridge (system_app, allowed vendor_audio_prop): it sets
 * persist.vendor.audio.hione.<knob> from a whitelist and the image's hione_audio.rc writes the
 * sysfs node, now and at every boot. See hioneos/docs/hiby-audio-knobs.md.
 *
 * STATUS (old route, kept for reference): found by reading the firmware, NOT confirmed. [set] reads the property
 * back through IAudioService.getProperties, so the UI can say "property set" truthfully; whether
 * init then wrote the node (and whether the DAC changed audibly) still needs an on-device check:
 *     adb shell getprop vendor.audio.hiby.digital_filter
 *     adb shell cat /sys/devices/platform/sa_sound_setting/digital_filter
 */
object HibyDacBridge {
    private const val TAG = "HibyDacBridge"

    const val PROP_HIGH_POWER = "vendor.audio.hiby.high_power"
    const val PROP_DRE = "vendor.audio.hiby.dre_mode"
    const val PROP_FILTER = "vendor.audio.hiby.digital_filter"
    const val PROP_GAIN = "vendor.audio.hiby.gain"

    /** UNSUPPORTED: the value is not one the DAC driver acts on. */
    enum class Result { CONFIRMED, NOT_CONFIRMED, UNAVAILABLE, UNSUPPORTED }

    /** Last outcome per property, for the profile screen's honesty line. */
    val lastResults: MutableMap<String, Result> = ConcurrentHashMap()

    @Volatile private var app: android.content.Context? = null
    fun init(ctx: android.content.Context) { if (app == null) app = ctx.applicationContext }

    private const val BRIDGE = "com.hione.sysbridge"
    private const val ACTION = "com.hione.sysbridge.AUDIO"

    /** Our property name -> the sysbridge extra / persist.vendor.audio.hione.<extra> knob. */
    private val KNOB = mapOf(PROP_HIGH_POWER to "high_power", PROP_DRE to "dre_mode",
        PROP_FILTER to "digital_filter", PROP_GAIN to "gain")

    private val REAL_FILTERS = setOf("fast_rolloff_low_latency", "fast_rolloff_phase_compensated",
        "slow_rolloff_low_latency", "slow_rolloff_phase_compensated", "nos")

    val available: Boolean get() = app?.let { a ->
        runCatching { a.packageManager.getPackageInfo(BRIDGE, 0); true }.getOrDefault(false)
    } ?: false

    private fun sysprop(key: String): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            .invoke(null, key) as String
    }.getOrNull()?.ifBlank { null }

    /** What the hardware was last told for [key], or null when never set. */
    fun get(key: String): String? = KNOB[key]?.let { sysprop("persist.vendor.audio.hione.$it") }

    /**
     * Send [value] for [key] through the system bridge and read the property back. Blocking (it
     * polls for up to a second): call off the main thread.
     */
    fun set(key: String, value: String): Result {
        val knob = KNOB[key]
        val a = app
        val r = when {
            knob == null || a == null || !available -> Result.UNAVAILABLE
            key == PROP_FILTER && value !in REAL_FILTERS -> Result.UNSUPPORTED
            else -> {
                runCatching {
                    a.sendBroadcast(android.content.Intent(ACTION).setPackage(BRIDGE).putExtra(knob, value))
                }.onFailure { Log.w(TAG, "bridge broadcast failed: $it") }
                var ok = false
                for (i in 0 until 10) {
                    if (get(key) == value) { ok = true; break }
                    Thread.sleep(100)
                }
                if (ok) Result.CONFIRMED else Result.NOT_CONFIRMED
            }
        }
        lastResults[key] = r
        Log.i(TAG, "$key=$value -> $r")
        return r
    }
}
