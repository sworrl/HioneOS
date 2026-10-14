package com.m500.hardware

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.provider.Settings
import android.util.Log
import java.io.File

/**
 * CPU power profiles, no root. Hione Music publishes Settings.Global hione_power_profile
 * ("perf", "balanced", "audio_only" or "idle"). com.hione.sysbridge mirrors it to
 * vendor.usb.hione.power_profile and the image's hione_powerd.rc writes the cpufreq caps. While
 * charging, sysbridge runs audio_only and idle as balanced. An unset or unknown row runs as balanced.
 *
 * This app is platform-signed and holds WRITE_SECURE_SETTINGS, so it can write the same row.
 * Hione Music writes it again on its next profile change (playback, screen, charger), so a pick
 * made here holds until then.
 *
 * Replaces the old root path that set every core's scaling_governor to "performance" through su,
 * which never ran on this user build.
 */
object CpuPerformance {
    private const val TAG = "CpuPerformance"
    const val KEY_PROFILE = "hione_power_profile"

    data class Profile(val id: String, val label: String, val summary: String)

    val PROFILES = listOf(
        Profile("perf", "Performance", "No caps"),
        Profile("balanced", "Balanced", "Big cores capped a little"),
        Profile("audio_only", "Audio only", "Low caps for playback with the screen off"),
        Profile("idle", "Idle", "Lowest caps, for when nothing is playing"),
    )

    fun label(id: String?): String = PROFILES.firstOrNull { it.id == id }?.label ?: "Balanced"

    /** The raw row, or null when nothing has written it yet. */
    fun current(ctx: Context): String? =
        runCatching { Settings.Global.getString(ctx.contentResolver, KEY_PROFILE) }.getOrNull()

    /** What sysbridge actually applies: unknown -> balanced, saving profiles -> balanced on the charger. */
    fun effective(raw: String?, charging: Boolean): String {
        val p = if (PROFILES.any { it.id == raw }) raw!! else "balanced"
        return if (charging && (p == "audio_only" || p == "idle")) "balanced" else p
    }

    fun set(ctx: Context, id: String): Boolean {
        val ok = runCatching { Settings.Global.putString(ctx.contentResolver, KEY_PROFILE, id) }
            .onFailure { Log.w(TAG, "could not write $KEY_PROFILE=$id: $it") }
            .getOrDefault(false)
        if (ok) Log.i(TAG, "$KEY_PROFILE=$id")
        return ok
    }

    /** Same test sysbridge uses (status charging or full). */
    fun isCharging(ctx: Context): Boolean = runCatching {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val s = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        s == BatteryManager.BATTERY_STATUS_CHARGING || s == BatteryManager.BATTERY_STATUS_FULL
    }.getOrDefault(false)

    /** scaling_max_freq in kHz for policy0 (little cores) and policy4 (big cores). World-readable. */
    fun caps(): Pair<Int?, Int?> = readKhz(0) to readKhz(4)

    private fun readKhz(policy: Int): Int? = runCatching {
        File("/sys/devices/system/cpu/cpufreq/policy$policy/scaling_max_freq").readText().trim().toInt()
    }.getOrNull()

    fun formatKhz(khz: Int?): String = when {
        khz == null -> "unknown"
        khz >= 1_000_000 -> String.format("%.2f GHz", khz / 1_000_000f)
        else -> "${khz / 1000} MHz"
    }
}
