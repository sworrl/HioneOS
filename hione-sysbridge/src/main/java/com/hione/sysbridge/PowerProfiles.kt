package com.hione.sysbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log

/**
 * CPU power profiles. Hione Music publishes the profile it wants in Settings.Global
 * hione_power_profile ("perf", "balanced", "audio_only" or "idle"). This class follows that row
 * and the charger, and sets vendor.usb.hione.power_profile; the image's hione_power.rc writes the
 * matching CPU frequency caps when the property changes.
 *
 * This replaces hione_powerd, a root shell loop that init never started: on this user build init
 * refuses a service with no SELinux domain, so the caps were never applied. init itself can write
 * the cpufreq nodes, and this package (system_app) can set vendor_usb_prop.
 *
 * While charging, the two saving profiles go up to balanced, as hione_powerd meant to.
 */
object PowerProfiles {
    private const val TAG = "HioneSysBridge"
    const val KEY_PROFILE = "hione_power_profile"
    const val PROP_PROFILE = "vendor.usb.hione.power_profile"
    private val KNOWN = setOf("perf", "balanced", "audio_only", "idle")

    private var started = false
    private var charging = false

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        app.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(KEY_PROFILE), false,
            object : ContentObserver(main) {
                override fun onChange(selfChange: Boolean) = evaluate(app)
            }
        )
        val sticky = app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val now = isCharging(i)
                if (now != charging) { charging = now; evaluate(app) }
            }
        }, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        charging = sticky?.let { isCharging(it) } ?: false
        evaluate(app)
    }

    private fun isCharging(i: Intent): Boolean {
        val s = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return s == BatteryManager.BATTERY_STATUS_CHARGING || s == BatteryManager.BATTERY_STATUS_FULL
    }

    @Synchronized
    private fun evaluate(context: Context) {
        val raw = runCatching { Settings.Global.getString(context.contentResolver, KEY_PROFILE) }.getOrNull()
        var p = if (raw in KNOWN) raw!! else "balanced"
        if (charging && (p == "audio_only" || p == "idle")) p = "balanced"
        if (getProp(PROP_PROFILE) == p) return
        setProp(PROP_PROFILE, p)
        Log.i(TAG, "power profile $p (asked=$raw charging=$charging)")
    }

    private fun getProp(key: String): String = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            .invoke(null, key) as String
    }.getOrDefault("")

    private fun setProp(key: String, value: String) {
        val r = runCatching {
            Class.forName("android.os.SystemProperties").getMethod("set", String::class.java, String::class.java)
                .invoke(null, key, value)
        }
        if (r.isFailure) Log.w(TAG, "could not set $key=$value: ${r.exceptionOrNull()?.cause ?: r.exceptionOrNull()}")
    }
}
