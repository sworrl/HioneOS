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
 * HioneOS charge limit. HioneSettings stores the limit in Settings.Global hione_charge_limit
 * (0 = off, or 80 / 85 / 90). This class watches that row and the battery, and sets
 * vendor.usb.hione.charge_hold to 1 when the level reaches the limit and back to 0 once it has
 * dropped 5% below it. The image's hione_charge.rc reacts to that property.
 *
 * What a hold does on this hardware (mp2731 charger, stock 1.20 driver, read from the module):
 * the driver exposes only two writable nodes, input_current_limit and charge_control_limit, and
 * clamps both to the chip's lowest step, 100 mA input and the minimum charge current. There is no
 * charge-off or HiZ node. So a hold cuts the charger to 100 mA instead of switching it off: with
 * the screen off the level holds roughly where it is, with the screen on it drifts down.
 *
 * vendor.usb.* is vendor_usb_prop, which system_app (this package) may set and init reads. It is
 * not persistent, and the charger nodes are re-initialised by the driver at every boot, so
 * charging always starts out normal after a reboot. The app is persistent, so the system restarts
 * it if it dies, and on start it releases any hold it can no longer justify.
 *
 * Its state goes to Settings.Global hione_charge_hold (0 / 1) so HioneSettings can show it.
 */
object ChargeLimiter {
    private const val TAG = "HioneSysBridge"
    const val KEY_LIMIT = "hione_charge_limit"
    const val KEY_HOLD = "hione_charge_hold"
    const val PROP_HOLD = "vendor.usb.hione.charge_hold"
    /** Charging resumes this many percent below the limit. */
    const val HYSTERESIS = 5
    private val ALLOWED = setOf(80, 85, 90)

    private var started = false
    private var holding = false
    private var lastBattery: Intent? = null

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        // A hold left over from a previous process is only kept if the battery still justifies it.
        holding = getProp(PROP_HOLD) == "1"
        app.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(KEY_LIMIT), false,
            object : ContentObserver(main) {
                override fun onChange(selfChange: Boolean) = evaluate(app, lastBattery)
            }
        )
        // ACTION_BATTERY_CHANGED is sticky: registering returns the current state right away.
        val sticky = app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = evaluate(app, i)
        }, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        evaluate(app, sticky)
    }

    private fun limit(context: Context): Int {
        val v = runCatching { Settings.Global.getInt(context.contentResolver, KEY_LIMIT, 0) }.getOrDefault(0)
        return if (v in ALLOWED) v else 0
    }

    @Synchronized
    private fun evaluate(context: Context, battery: Intent?) {
        if (battery != null) lastBattery = battery
        val b = lastBattery
        val lim = limit(context)
        val level = b?.let {
            val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val s = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (l >= 0 && s > 0) l * 100 / s else -1
        } ?: -1
        val plugged = (b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0

        val want = when {
            lim == 0 || level < 0 -> false        // off, or no reading: never hold blind
            !plugged -> false                      // nothing to hold; a replug starts fresh
            holding -> level > lim - HYSTERESIS    // keep holding until 5% below the limit
            else -> level >= lim
        }
        // Only write on a real change: each write makes the charger driver re-run its input
        // detection, the same as a replug. An unset property counts as released.
        val cur = getProp(PROP_HOLD)
        if (want && cur != "1") setProp(PROP_HOLD, "1")
        if (!want && cur == "1") setProp(PROP_HOLD, "0")
        if (want != holding || (want && cur != "1") || (!want && cur == "1"))
            Log.i(TAG, "charge ${if (want) "hold" else "release"} (limit=$lim level=$level plugged=$plugged)")
        holding = want
        publish(context, want)
    }

    private fun publish(context: Context, hold: Boolean) {
        val v = if (hold) 1 else 0
        runCatching {
            if (Settings.Global.getInt(context.contentResolver, KEY_HOLD, -1) != v)
                Settings.Global.putInt(context.contentResolver, KEY_HOLD, v)
        }
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
