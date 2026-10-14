package com.hione.sysbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.provider.Settings
import android.util.Log

/**
 * Things only the system_app domain may do, on behalf of the other Hione apps.
 *
 * USB DAC MODE (action com.hione.sysbridge.USB_DAC, extra "enable"). The M500 plugged into a
 * computer or phone shows up as a USB sound card and plays what it is sent, to the jack or to
 * Bluetooth. This is HiBy's own sequence, read out of their Settings app
 * (connecteddevice.WorkModeActivity changeUsbDac / changeAndroidMode, 2026-08-25), because it is
 * the one their firmware is built around:
 *
 *   on:  USB function 256 (HiBy's framework addition, labelled usb_use_dac), vendor.usb.port_type
 *        = sink (the previous value saved to Settings.Global the way HiBy does), charging from the
 *        host per vendor.usb.input_suspend, then vendor.usb.uac2.function.start=1, on which init
 *        starts /system/bin/usbaudio (root): it reads the host's stream from /dev/uac_sa and
 *        publishes the live format in sys.audio.uac.*.
 *   off: the default USB functions back, the port type back, uac2.function.start=0 (init stops
 *        usbaudio), the gadget dropped to none so Android re-applies MTP/ADB, and "usbvalue0" to
 *        the audio HAL.
 *
 * HioneOS apps are platform_app, which HiBy's policy does not let set vendor_usb_prop; that is why
 * the old `su` version never ran and why this package exists. Each step's result is logged.
 */
class BridgeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_USB_DAC) return
        val enable = intent.getBooleanExtra("enable", false)
        val app = context.applicationContext
        val pending = goAsync()
        Thread({
            try {
                val cr = app.contentResolver
                if (enable) {
                    setUsbFunctions(app, FUNCTION_DAC)
                    val port = get("vendor.usb.port_type")
                    if (!port.isNullOrEmpty() && port != "sink") {
                        Settings.Global.putString(cr, "vendor.usb.port_type", port)
                    }
                    if (port != "sink") set("vendor.usb.port_type", "sink")
                    val noPower = Settings.Global.getString(cr, "vendor.usb.input_suspend") == "1"
                    set("vendor.usb.input_suspend", if (noPower) "1" else "0")
                    set("vendor.usb.uac2.function.start", "1")
                    Settings.Global.putString(cr, "work_mode", "dacin")
                } else {
                    val def = Settings.Global.getString(cr, "usb_function_default")?.toLongOrNull() ?: 0L
                    setUsbFunctions(app, def)
                    Settings.Global.getString(cr, "vendor.usb.port_type")?.let {
                        if (it != get("vendor.usb.port_type")) set("vendor.usb.port_type", it)
                    }
                    set("vendor.usb.uac2.function.start", "0")
                    setUsbFunctions(app, 0L, currentOnly = true)
                    runCatching {
                        (app.getSystemService(Context.AUDIO_SERVICE) as AudioManager).setParameters("usbvalue0")
                    }
                    Settings.Global.putString(cr, "work_mode", "android")
                }
                Thread.sleep(2000)
                Log.i(TAG, "USB DAC ${if (enable) "on" else "off"}: sys.usb.state=${get("sys.usb.state")} " +
                    "usbaudio=${get("init.svc.usbaudio")} format=${get("sys.audio.uac.sample_rate")}/" +
                    "${get("sys.audio.uac.sample_bit")}")
            } catch (t: Throwable) {
                Log.e(TAG, "USB DAC switch failed", t)
            } finally {
                pending.finish()
            }
        }, "HioneSysBridge").start()
    }

    /**
     * UsbManager's function setters are @SystemApi; this package is the system UID, so it holds
     * MANAGE_USB. Default (screen-unlocked) functions are what HiBy sets; [currentOnly] is the
     * "drop to none" step of the exit, after which Android re-applies the defaults.
     */
    private fun setUsbFunctions(ctx: Context, functions: Long, currentOnly: Boolean = false) {
        val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val cls = UsbManager::class.java
        val r = runCatching {
            if (!currentOnly) cls.getMethod("setScreenUnlockedFunctions", Long::class.javaPrimitiveType).invoke(um, functions)
            cls.getMethod("setCurrentFunctions", Long::class.javaPrimitiveType).invoke(um, functions)
        }
        Log.i(TAG, "usb functions ${if (currentOnly) "current" else "default+current"}=$functions -> " +
            if (r.isSuccess) "ok" else "${r.exceptionOrNull()?.cause ?: r.exceptionOrNull()}")
    }

    private fun set(key: String, value: String) {
        val ok = runCatching {
            Class.forName("android.os.SystemProperties")
                .getMethod("set", String::class.java, String::class.java)
                .invoke(null, key, value)
        }
        Log.i(TAG, "setprop $key $value -> ${if (ok.isSuccess) "ok" else ok.exceptionOrNull()?.cause ?: ok.exceptionOrNull()}")
    }

    private fun get(key: String): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            .invoke(null, key) as String
    }.getOrNull()

    companion object {
        const val TAG = "HioneSysBridge"
        const val ACTION_USB_DAC = "com.hione.sysbridge.USB_DAC"
        /** HiBy's USB DAC gadget function bit (UsbDetailsFunctionsController: 256 = usb_use_dac). */
        const val FUNCTION_DAC = 256L
    }
}
