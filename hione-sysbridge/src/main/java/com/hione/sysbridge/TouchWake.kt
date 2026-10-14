package com.hione.sysbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Double-tap to wake, the way stock HiBy OS did it.
 *
 * HOW THE HARDWARE DOES IT. The touch controller is a Goodix GT9xx (gt9xx.ko, i2c 1-005d). Its
 * device tree enables "goodix,slide-wakeup", which is why the Goodix-CTP input device lists
 * KEY_POWER. The driver keeps one global flag. When the panel blanks, gtp_suspend puts the chip in
 * doze (gesture) mode if that flag is set, otherwise it sends it to sleep. In doze the chip reports
 * a gesture code at 0x814B, and for a double tap (0xCC), a swipe or a letter gesture the driver
 * sends KEY_POWER down and up. Android reads that through Generic.kl as KEYCODE_POWER, so the wake
 * is a normal power-key wake.
 *
 * HOW THE FLAG IS SET. Only through init. HiBy's init.target.rc:
 *   on property:vendor.audio.hiby.tp_gesture=enable    write /proc/goodix_gesture 1
 *                                                      write .../1-005d/gesture_enable enable
 *   on property:...=disable                            the same with 0 / disable
 * gesture_enable compares the first 6 bytes with "enable". The flag is read at the next screen off,
 * so a change made while the screen is on applies the next time it goes off.
 *
 * WHAT STOCK DID. Settings > Gestures "Double click wake up" (ScreenWakeController) set the property
 * and stored the choice in Settings.Global "vendor.audio.hiby.tp_gesture". services.jar
 * (HibyAudioSettingInitUtils) set the property from that row at every boot and wrote "disable"
 * when the row was empty. HioneOS has no UI for it, so it was off.
 *
 * WHY HERE. vendor.audio.* is vendor_audio_prop. system_app may set it, platform_app (HioneSettings)
 * may not. init only runs a property trigger when the value changes, so [apply] sets the opposite
 * value first and then the wanted one.
 */
object TouchWake {
    private const val TAG = "HioneTouchWake"

    const val ACTION = "com.hione.sysbridge.TOUCH_WAKE"
    const val EXTRA = "tp_gesture"
    const val ENABLE = "enable"
    const val DISABLE = "disable"

    /** The property HiBy's rc watches. Also the Settings.Global row stock used for the choice. */
    const val PROP = "vendor.audio.hiby.tp_gesture"
    const val GLOBAL = PROP

    /** The user's choice, default on. */
    fun wanted(ctx: Context): String =
        if (Settings.Global.getString(ctx.contentResolver, GLOBAL) == DISABLE) DISABLE else ENABLE

    /** Store the choice and apply it now. */
    fun set(ctx: Context, value: String): Boolean {
        if (value != ENABLE && value != DISABLE) { Log.w(TAG, "refused $EXTRA=$value"); return false }
        runCatching { Settings.Global.putString(ctx.contentResolver, GLOBAL, value) }
            .onFailure { Log.w(TAG, "could not store $GLOBAL=$value: $it") }
        return apply(value)
    }

    /** Fire the init trigger for [value], even when the property already holds it. */
    fun apply(value: String): Boolean {
        val other = if (value == ENABLE) DISABLE else ENABLE
        val ok = setProp(other) && run { Thread.sleep(150); setProp(value) }
        val now = getProp()
        Log.i(TAG, "$PROP=$value -> ${if (ok && now == value) "ok" else "read back '$now'"}")
        return ok && now == value
    }

    /**
     * Boot. services.jar sets the property from the Global row early in boot, and the driver's
     * proc and sysfs nodes exist from probe, but the order against our process start is not fixed
     * and a refused write leaves no trace we can read (neither node can be read back). So apply
     * now and again after 20 and 60 seconds. Each pass is a few property sets, nothing more.
     * BootSeeds runs twice per boot, the retries run once per process.
     */
    private val bootStarted = AtomicBoolean(false)

    fun applyAtBoot(ctx: Context) {
        if (!bootStarted.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        Thread({
            for (delay in longArrayOf(0L, 20_000L, 40_000L)) {
                if (delay > 0) Thread.sleep(delay)
                runCatching { apply(wanted(app)) }.onFailure { Log.w(TAG, "boot apply failed: $it") }
            }
        }, "HioneTouchWake").start()
    }

    private fun setProp(v: String): Boolean = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("set", String::class.java, String::class.java)
            .invoke(null, PROP, v)
    }.onFailure {
        Log.w(TAG, "set $PROP=$v failed: ${(it as? java.lang.reflect.InvocationTargetException)?.targetException ?: it}")
    }.isSuccess

    private fun getProp(): String = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            .invoke(null, PROP) as String
    }.getOrDefault("").trim()
}

/** com.hione.sysbridge.TOUCH_WAKE with tp_gesture=enable|disable, from HioneSettings. */
class TouchWakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TouchWake.ACTION) return
        val v = intent.getStringExtra(TouchWake.EXTRA) ?: return
        val pending = goAsync()
        val app = context.applicationContext
        Thread({
            try { TouchWake.set(app, v) } finally { pending.finish() }
        }, "HioneTouchWake").start()
    }
}
