package com.hione.update.ota

import android.content.Context
import android.content.SharedPreferences

/** User settings and a little bookkeeping. Plain SharedPreferences. */
class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("hione_update", Context.MODE_PRIVATE)

    var channel: String
        get() = sp.getString("channel", OtaConfig.DEFAULT_CHANNEL)!!.takeIf { it in OtaConfig.CHANNELS } ?: OtaConfig.DEFAULT_CHANNEL
        set(v) = sp.edit().putString("channel", v).apply()

    /** Install app updates on their own after a scheduled check. */
    var autoInstall: Boolean
        get() = sp.getBoolean("auto_install", true)
        set(v) = sp.edit().putBoolean("auto_install", v).apply()

    var scheduleEnabled: Boolean
        get() = sp.getBoolean("schedule_enabled", true)
        set(v) = sp.edit().putBoolean("schedule_enabled", v).apply()

    var wifiOnly: Boolean
        get() = sp.getBoolean("wifi_only", true)
        set(v) = sp.edit().putBoolean("wifi_only", v).apply()

    var chargingOnly: Boolean
        get() = sp.getBoolean("charging_only", true)
        set(v) = sp.edit().putBoolean("charging_only", v).apply()

    /** Hours between scheduled checks. */
    var intervalHours: Int
        get() = sp.getInt("interval_hours", 24).coerceIn(6, 24 * 7)
        set(v) = sp.edit().putInt("interval_hours", v).apply()

    /** Server override for testing (a LAN box). Blank means the real server. */
    var baseUrlOverride: String
        get() = sp.getString("base_url", "") ?: ""
        set(v) = sp.edit().putString("base_url", v.trim()).apply()

    val baseUrl: String
        get() = baseUrlOverride.ifBlank { OtaConfig.DEFAULT_BASE_URL }.let { if (it.endsWith("/")) it else "$it/" }

    var lastCheckMillis: Long
        get() = sp.getLong("last_check", 0L)
        set(v) = sp.edit().putLong("last_check", v).apply()

    var lastResult: String
        get() = sp.getString("last_result", "") ?: ""
        set(v) = sp.edit().putString("last_result", v).apply()

    /**
     * Newest published_at accepted per channel (epoch seconds). A manifest older than this is
     * refused: it stops a stale or replayed manifest from walking a device back to old builds.
     */
    fun lastPublished(channel: String): Long = sp.getLong("published_$channel", 0L)
    fun setLastPublished(channel: String, epochSeconds: Long) =
        sp.edit().putLong("published_$channel", epochSeconds).apply()

    /** Last system update build the user was told about, so the notification is posted once. */
    var notifiedSystemBuild: String
        get() = sp.getString("notified_system_build", "") ?: ""
        set(v) = sp.edit().putString("notified_system_build", v).apply()

    var notifiedAvailableKey: String
        get() = sp.getString("notified_available", "") ?: ""
        set(v) = sp.edit().putString("notified_available", v).apply()

    /**
     * After a rollback the newer version is held back: automatic installs skip every versionCode
     * up to and including the one the user walked away from. A later release clears it.
     */
    fun heldVersion(pkg: String): Long = sp.getLong("held_$pkg", 0L)
    fun setHeldVersion(pkg: String, vc: Long) = sp.edit().putLong("held_$pkg", vc).apply()
    fun clearHeld(pkg: String) = sp.edit().remove("held_$pkg").apply()

    // A/B system update bookkeeping (AbUpdater). Kept across restarts so the app can tell after
    // a reboot whether the new slot came up, the bootloader went back, or the write was cut off.

    /** Build being installed through update_engine. Blank when nothing is pending. */
    var abPendingBuild: String
        get() = sp.getString("ab_pending_build", "") ?: ""
        set(v) = sp.edit().putString("ab_pending_build", v).commit().let { }

    /** Build the device ran when the install started. */
    var abFromBuild: String
        get() = sp.getString("ab_from_build", "") ?: ""
        set(v) = sp.edit().putString("ab_from_build", v).commit().let { }

    /** The payload file in /data/ota_package, deleted once the new build is up. */
    var abFile: String
        get() = sp.getString("ab_file", "") ?: ""
        set(v) = sp.edit().putString("ab_file", v).commit().let { }

    /** update_engine finished writing the other slot (UPDATED_NEED_REBOOT was reached). */
    var abApplied: Boolean
        get() = sp.getBoolean("ab_applied", false)
        set(v) = sp.edit().putBoolean("ab_applied", v).commit().let { }

    /** Settings.Global.BOOT_COUNT when the install started, to spot a restart. */
    var abBootCount: Int
        get() = sp.getInt("ab_boot_count", -1)
        set(v) = sp.edit().putInt("ab_boot_count", v).commit().let { }
}
