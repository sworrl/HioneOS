package com.m500.hardware

import android.content.Context
import android.database.ContentObserver
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log

/**
 * Always-on Fn pocket-lock applier, hosted in this app's foreground service so it survives the
 * music app being trimmed. Hione Music's HionePocketLockManager does the same thing (plus the HUD)
 * while its process is alive; both are idempotent, so double-application is harmless.
 *
 * Everything here is root-free on the platform-signed build:
 *  - touch / volume wheel / power button: InputManager.disableInputDevice (@hide, reflection,
 *    needs DISABLE_INPUT_DEVICE). gpio-keys-hiby is deliberately NEVER disabled: it also carries
 *    the Fn switch (SW_KEYPAD_SLIDE) that the HiBy framework turns into fn_status - disabling it
 *    would make unlock undetectable.
 *  - transport keys: Settings.Global button_lock=1 - the stock HiBy services.jar drops media keys
 *    on it (verified live, screen on AND off, all apps).
 *  - screen: PowerManager.goToSleep (DEVICE_POWER).
 */
object FnLockDaemon {
    private const val TAG = "FnLockDaemon"
    private const val DEV_TOUCH = "Goodix-CTP"
    private const val DEV_WHEEL = "ring-keys"
    private const val DEV_POWER = "qpnp_pon"
    private const val PREF_ALLOW_VOLUME = "m500_fn_allow_volume_wheel"   // 0/1, default 0
    private const val PREF_LOCK_POWER = "m500_fn_lock_power_button"      // 0/1, default 1

    @Volatile private var registered = false
    private var last = -1

    fun start(ctx: Context) {
        if (registered) return
        registered = true
        val app = ctx.applicationContext
        val cr = app.contentResolver
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                val s = runCatching { Settings.Global.getInt(cr, "fn_status", 0) }.getOrDefault(0)
                if (s != last) { last = s; apply(app, s == 1) }
            }
        }
        runCatching { cr.registerContentObserver(Settings.Global.getUriFor("fn_status"), false, obs) }
        val now = runCatching { Settings.Global.getInt(cr, "fn_status", 0) }.getOrDefault(0)
        last = now
        // Re-assert current state on start: if the switch is already engaged we lock; if not we make
        // sure nothing was left disabled by a previous process that died mid-lock.
        apply(app, now == 1)
        Log.i(TAG, "watching fn_status (now=$now)")
    }

    fun apply(ctx: Context, locked: Boolean) {
        val cr = ctx.contentResolver
        val mode = runCatching { Settings.Global.getString(cr, "fn_settings") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: "touch_and_key_lock"
        val allowVolume = runCatching { Settings.Global.getInt(cr, PREF_ALLOW_VOLUME, 0) == 1 }.getOrDefault(false)
        val lockPower = runCatching { Settings.Global.getInt(cr, PREF_LOCK_POWER, 1) == 1 }.getOrDefault(true)
        Log.i(TAG, "apply locked=$locked mode=$mode allowVolume=$allowVolume lockPower=$lockPower")
        if (!locked) {
            runCatching { Settings.Global.putInt(cr, "button_lock", 0) }
            setDevice(ctx, DEV_TOUCH, true); setDevice(ctx, DEV_WHEEL, true); setDevice(ctx, DEV_POWER, true)
            restoreTapToWake(ctx)
            return
        }
        suppressTapToWake(ctx)
        val lockKeys = mode != "touch_lock"
        val lockTouch = mode != "key_lock"
        runCatching { Settings.Global.putInt(cr, "button_lock", if (lockKeys) 1 else 0) }
        setDevice(ctx, DEV_TOUCH, !lockTouch)
        setDevice(ctx, DEV_WHEEL, allowVolume || mode == "touch_lock")
        setDevice(ctx, DEV_POWER, !lockPower)
        if (lockTouch) blankScreen(ctx)
    }

    private fun setDevice(ctx: Context, name: String, enabled: Boolean) {
        val im = ctx.getSystemService(Context.INPUT_SERVICE) as? InputManager ?: return
        var n = 0
        for (id in im.inputDeviceIds) {
            val dev = im.getInputDevice(id) ?: continue
            if (!dev.name.contains(name, ignoreCase = true)) continue
            try {
                val m = InputManager::class.java.getMethod(
                    if (enabled) "enableInputDevice" else "disableInputDevice", Int::class.javaPrimitiveType)
                m.invoke(im, id); n++
            } catch (t: Throwable) { Log.w(TAG, "setDevice($name,$enabled) failed: $t") }
        }
        Log.i(TAG, "setDevice($name, enabled=$enabled) -> $n")
    }

    // ---------------------------------------------------------------- tap to wake
    //
    // Sliding Fn back should leave the screen wakeable by touch, not only by the power button:
    // the lock blanks the screen, so without this you come out of pocket lock into a dark slab
    // that only a button press revives.
    //
    // The inverse matters more. While locked, tap to wake is exactly the thing that must not
    // work, or a pocket will light the screen all afternoon. So it is suppressed on lock and
    // put back on unlock.
    //
    // The user's own setting is preserved rather than forced on. Whatever double_tap_to_wake
    // was before the lock is stashed and restored, so someone who deliberately turned it off
    // does not find it switched on by having used the switch.

    private const val PREF_SAVED_DTW = "m500_fn_saved_double_tap_to_wake"   // -1 = nothing saved

    private fun suppressTapToWake(ctx: Context) {
        val cr = ctx.contentResolver
        runCatching {
            val already = Settings.Global.getInt(cr, PREF_SAVED_DTW, -1)
            if (already == -1) {
                val current = Settings.Secure.getInt(cr, "double_tap_to_wake", 0)
                Settings.Global.putInt(cr, PREF_SAVED_DTW, current)
            }
            Settings.Secure.putInt(cr, "double_tap_to_wake", 0)
            Log.i(TAG, "tap to wake suppressed for the lock")
        }.onFailure { Log.w(TAG, "could not suppress tap to wake: $it") }
    }

    private fun restoreTapToWake(ctx: Context) {
        val cr = ctx.contentResolver
        runCatching {
            // Default to ON when nothing was stashed: that is the behaviour asked for, a tap
            // wakes the screen after the switch releases it.
            val saved = Settings.Global.getInt(cr, PREF_SAVED_DTW, 1)
            Settings.Secure.putInt(cr, "double_tap_to_wake", saved)
            Settings.Global.putInt(cr, PREF_SAVED_DTW, -1)
            Log.i(TAG, "tap to wake restored to $saved")
        }.onFailure { Log.w(TAG, "could not restore tap to wake: $it") }
    }

    private fun blankScreen(ctx: Context) {
        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            val m = PowerManager::class.java.getMethod("goToSleep", Long::class.javaPrimitiveType)
            m.invoke(pm, SystemClock.uptimeMillis())
        } catch (t: Throwable) { Log.w(TAG, "goToSleep failed: $t") }
    }
}
