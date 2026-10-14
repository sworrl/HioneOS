package com.hione.player

import android.content.Context
import android.hardware.input.InputManager
import android.util.Log
import android.view.InputDevice

/**
 * Root-free input locking via InputManager.disableInputDevice — the platform-signed replacement
 * for the old RootShell `chmod 000 /dev/input/eventN` + `setprop vendor...disable_touch` paths, which
 * SILENTLY NO-OP on the unrooted HioneOS build (standing directive: no su; platform perms instead).
 * disableInputDevice/enableInputDevice are @hide, reached by reflection; the calling app must hold
 * android.permission.DISABLE_INPUT_DEVICES (signature — granted by the Falcon platform key).
 *
 * Known devices on the M500 (getevent):
 *   Goodix-CTP        = touchscreen digitizer
 *   ring-keys         = rotary volume wheel (/dev/input/event2)
 *   gpio-keys-hiby    = physical transport/power keys (/dev/input/event1)
 */
object HioneInputLock {
    private const val TAG = "HioneInputLock"
    const val DEV_TOUCH = "Goodix-CTP"
    const val DEV_WHEEL = "ring-keys"
    const val DEV_KEYS = "gpio-keys-hiby"
    const val DEV_POWER = "qpnp_pon"      // power button (/dev/input/event0), KEY_POWER only

    private fun im(ctx: Context): InputManager? =
        ctx.getSystemService(Context.INPUT_SERVICE) as? InputManager

    private fun idsByName(ctx: Context, name: String): List<Int> {
        val mgr = im(ctx) ?: return emptyList()
        val out = ArrayList<Int>()
        try {
            val ids: IntArray = mgr.inputDeviceIds
            for (id in ids) {
                val dev: InputDevice? = mgr.getInputDevice(id)
                if (dev != null && dev.name.contains(name, ignoreCase = true)) out.add(id)
            }
        } catch (_: Throwable) {}
        return out
    }

    private fun setDeviceEnabled(ctx: Context, id: Int, enabled: Boolean): Boolean {
        val mgr = im(ctx) ?: return false
        return try {
            val m = InputManager::class.java.getMethod(
                if (enabled) "enableInputDevice" else "disableInputDevice", Int::class.javaPrimitiveType
            )
            m.isAccessible = true
            m.invoke(mgr, id)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "setDeviceEnabled($id,$enabled) failed: $t")
            false
        }
    }

    /** Enable/disable every input device matching [name]. Returns how many were toggled. */
    fun setByName(ctx: Context, name: String, enabled: Boolean): Int {
        var n = 0
        for (id in idsByName(ctx, name)) if (setDeviceEnabled(ctx, id, enabled)) n++
        Log.i(TAG, "setByName($name, enabled=$enabled) -> $n device(s)")
        return n
    }

    @Volatile private var touchLockedNow = false

    /**
     * True only while the digitizer is REALLY disabled: a matching input device was found and
     * InputManager.disableInputDevice accepted the call. UI that needs to know whether the
     * hardware is inhibited (and can therefore skip an in-app touch-blocking overlay) must read
     * this rather than assuming from a preference.
     */
    val touchHardwareLocked: Boolean get() = touchLockedNow

    fun setTouch(ctx: Context, enabled: Boolean): Int {
        val n = setByName(ctx, DEV_TOUCH, enabled)
        touchLockedNow = !enabled && n > 0
        return n
    }

    fun setKeys(ctx: Context, enabled: Boolean) = setByName(ctx, DEV_KEYS, enabled)
    fun setWheel(ctx: Context, enabled: Boolean) = setByName(ctx, DEV_WHEEL, enabled)
    fun setPower(ctx: Context, enabled: Boolean) = setByName(ctx, DEV_POWER, enabled)

    /** Unlock everything (used on boot / unlock). */
    fun enableAll(ctx: Context) {
        setTouch(ctx, true); setKeys(ctx, true); setWheel(ctx, true); setPower(ctx, true)
    }
}
