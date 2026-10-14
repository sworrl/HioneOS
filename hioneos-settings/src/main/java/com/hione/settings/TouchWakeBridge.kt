package com.hione.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * Double-tap to wake. The Goodix touch chip watches for taps while the screen is off and sends a
 * power key when it sees a double tap, but only if HiBy's init trigger on
 * vendor.audio.hiby.tp_gesture has armed it. That property is vendor_audio_prop: this app
 * (platform_app) can read it but not set it, so com.hione.sysbridge (system_app) sets it.
 * See hione-sysbridge TouchWake.kt for the driver side.
 *
 * The choice lives in Settings.Global "vendor.audio.hiby.tp_gesture", the row stock HiBy Settings
 * used. Stock services.jar also sets the property from that row at every boot, so a saved choice
 * comes back after a reboot even on a bridge that predates TOUCH_WAKE.
 */
object TouchWakeBridge {
    private const val TAG = "TouchWakeBridge"
    private const val BRIDGE = "com.hione.sysbridge"
    private const val ACTION = "com.hione.sysbridge.TOUCH_WAKE"
    private const val EXTRA = "tp_gesture"
    private const val KEY = "vendor.audio.hiby.tp_gesture"

    enum class Result { APPLIED, SAVED_FOR_REBOOT, FAILED }

    /** On unless the user turned it off. An empty row means the default, which is on. */
    fun isOn(ctx: Context): Boolean =
        Settings.Global.getString(ctx.contentResolver, KEY) != "disable"

    private fun prop(): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            .invoke(null, KEY) as String
    }.getOrNull()?.trim()

    /** Save and apply. Blocks up to a second, so not on the main thread. */
    fun set(ctx: Context, on: Boolean): Result {
        val v = if (on) "enable" else "disable"
        val saved = runCatching { Settings.Global.putString(ctx.contentResolver, KEY, v) }
            .onFailure { Log.w(TAG, "could not save $KEY=$v: $it") }.isSuccess
        runCatching {
            ctx.applicationContext.sendBroadcast(Intent(ACTION).setPackage(BRIDGE).putExtra(EXTRA, v))
        }.onFailure { Log.w(TAG, "bridge broadcast failed: $it") }
        var applied = false
        for (i in 0 until 10) {
            if (prop() == v) { applied = true; break }
            Thread.sleep(100)
        }
        val r = when {
            applied -> Result.APPLIED
            saved -> Result.SAVED_FOR_REBOOT
            else -> Result.FAILED
        }
        Log.i(TAG, "$KEY=$v -> $r (property now '${prop()}')")
        return r
    }
}
