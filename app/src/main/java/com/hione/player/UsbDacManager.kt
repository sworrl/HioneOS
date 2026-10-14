package com.hione.player

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "USB DAC MODE" — the M500 acting as a USB DAC for a PC/phone (Qualcomm standalone UAC2 gadget,
 * host → M500 → dual CS43198).
 *
 * How HiBy's own OS does it (decompiled from the stock Settings app, `connecteddevice.WorkModeActivity`,
 * 2026-08-25):
 *   ENTER  : Settings.Global `work_mode` = "dacin", then start `WorkModeActivity` (exported as
 *            action `android.settings.WORK_MODE_VIEW`). Its onCreate reads `work_mode` and, for
 *            "dacin", runs `changeUsbDac()`: `UsbBackend.setDefaultUsbFunctions(...)`, then
 *            `SystemProperties.set("vendor.usb.port_type","sink")` and
 *            `SystemProperties.set("vendor.usb.uac2.function.start","1")` — init.qcom.usb.rc
 *            reacts (`start usbaudio`, `start dwc3_rt_optimizer`) and switches `sys.usb.config` to
 *            `diag,uac2[,adb]`. Charging-from-host is `vendor.usb.input_suspend` (1 = no power).
 *   FORMAT : the live host stream is published in `sys.audio.uac.sample_rate` / `sys.audio.uac.sample_bit`.
 *   EXIT   : `changeAndroidMode()` = restore default USB functions (Global `usb_function_default`),
 *            `vendor.usb.port_type` back, `vendor.usb.uac2.function.start=0` (init: stop usbaudio),
 *            `UsbManager.setCurrentFunction("none", true)`, `AudioManager.setParameters("usbvalue0")`.
 *
 * What THIS app can and cannot do (verified against the device's sepolicy .cil):
 *   - We run as `platform_app`; only `system_app` may set `vendor_usb_prop` / `usb_control_prop`.
 *     So the vendor props are NOT settable from here — no simulation of that.
 *   - We CAN write `Settings.Global` (WRITE_SECURE_SETTINGS) and start the exported HiBy screen,
 *     which does the whole hardware switch as system_app → ENTER is 100% real.
 *   - EXIT from our side is best-effort: Global `work_mode`="android" + `UsbManager.setCurrentFunctions`
 *     (MANAGE_USB) puts the gadget back on MTP/ADB, but `vendor.usb.uac2.function.start` stays 1
 *     (usbaudio daemon keeps running) until HiBy's DAC screen "Android mode" button — or the
 *     `close_usbdac_activity` broadcast, which its receiver registers NOT_EXPORTED (system/self only).
 *     Missing bit for a complete in-app exit: a ROM init.rc trigger that runs the two vendor prop
 *     resets (init may set any prop) — that belongs to the image build, not this app.
 */
object UsbDacManager {
    private const val TAG = "UsbDacManager"
    const val KEY_WORK_MODE = "work_mode"
    const val MODE_DAC_IN = "dacin"
    const val MODE_ANDROID = "android"
    const val ACTION_WORK_MODE_VIEW = "android.settings.WORK_MODE_VIEW"
    const val ACTION_CLOSE_DAC_SCREEN = "close_usbdac_activity"
    private const val KEY_DEFAULT_FUNCTIONS = "usb_function_default"
    // @SystemApi values (not in the public stubs): UsbManager.ACTION_USB_STATE / FUNCTION_MTP.
    private const val ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE"
    private const val FUNCTION_MTP = 1L shl 2

    fun workMode(ctx: Context): String =
        runCatching { Settings.Global.getString(ctx.contentResolver, KEY_WORK_MODE) }.getOrNull() ?: MODE_ANDROID

    /** True while HiBy's work mode is "dacin" (the DAC screen is what actually flips the gadget). */
    fun isActive(ctx: Context): Boolean = workMode(ctx) == MODE_DAC_IN

    /** Whether the OS offers the HiBy DAC screen at all (stock Settings present). */
    fun isSupported(ctx: Context): Boolean =
        runCatching { ctx.packageManager.getPackageInfo("com.hione.sysbridge", 0); true }.getOrDefault(false) ||
            ctx.packageManager.resolveActivity(Intent(ACTION_WORK_MODE_VIEW), 0) != null

    private fun sysProp(key: String): String = runCatching {
        val c = Class.forName("android.os.SystemProperties")
        c.getMethod("get", String::class.java, String::class.java).invoke(null, key, "") as String
    }.getOrDefault("")

    /** Live host stream rate (Hz) from `sys.audio.uac.sample_rate`, 0 when nothing is streaming. */
    fun getSampleRate(ctx: Context): Int = sysProp("sys.audio.uac.sample_rate").toIntOrNull() ?: 0
    /** Live host stream depth (bits) from `sys.audio.uac.sample_bit`, 0 when nothing is streaming. */
    fun getBitDepth(ctx: Context): Int = sysProp("sys.audio.uac.sample_bit").toIntOrNull() ?: 0

    data class UsbState(
        val connected: Boolean,
        val configured: Boolean,
        val hostConnected: Boolean,
        val functions: String,
        val sysConfig: String,
        val uacActive: Boolean,
    )

    /** Snapshot of the USB gadget from the sticky ACTION_USB_STATE broadcast + `sys.usb.config`. */
    fun usbState(ctx: Context): UsbState {
        val sticky = runCatching {
            ctx.registerReceiver(null, IntentFilter(ACTION_USB_STATE))
        }.getOrNull()
        val connected = sticky?.getBooleanExtra("connected", false) ?: false
        val configured = sticky?.getBooleanExtra("configured", false) ?: false
        val host = sticky?.getBooleanExtra("host_connected", false) ?: false
        val fns = listOf("mtp", "ptp", "adb", "rndis", "midi", "audio_source", "ncm", "uvc")
            .filter { sticky?.getBooleanExtra(it, false) == true }
        val cfg = sysProp("sys.usb.config").ifBlank { sysProp("sys.usb.state") }
        return UsbState(
            connected = connected,
            configured = configured,
            hostConnected = host,
            functions = fns.joinToString(","),
            sysConfig = cfg,
            uacActive = cfg.contains("uac2") || sysProp("vendor.usb.uac2.function.start") == "1",
        )
    }

    fun statusLine(ctx: Context): String {
        val s = usbState(ctx)
        val mode = if (isActive(ctx)) "USB DAC mode" else "Android mode"
        val link = when {
            s.uacActive -> "UAC2 gadget up"
            s.sysConfig.isNotBlank() -> "gadget: ${s.sysConfig}"
            s.functions.isNotBlank() -> "gadget: ${s.functions}"
            else -> "gadget: unknown"
        }
        val cable = if (s.connected) (if (s.configured) "host connected" else "cable, not configured") else "no host"
        val fmt = getSampleRate(ctx).takeIf { it > 0 }?.let { r -> "${r / 1000} kHz / ${getBitDepth(ctx)}-bit" }
        return listOfNotNull(mode, link, cable, fmt).joinToString(" · ")
    }

    /**
     * Enter/leave HiBy USB DAC mode. Returns true when the request was handed to the OS
     * (enter: HiBy DAC screen started; leave: gadget put back on MTP/ADB + work_mode reset).
     */
    /**
     * Enter/leave USB DAC mode through com.hione.sysbridge, the HioneOS package that runs as the
     * system UID and so may set the vendor USB properties HiBy's sequence needs (see its
     * BridgeReceiver). This no longer depends on HiBy's Settings screen being in the image.
     * Returns true when the request was handed over; [isActive] reads the real result.
     */
    suspend fun setUsbDacMode(ctx: Context, enabled: Boolean, rate: Int = 0, bits: Int = 0): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                if (enabled && Settings.Global.getString(ctx.contentResolver, KEY_DEFAULT_FUNCTIONS).isNullOrBlank()) {
                    Settings.Global.putString(ctx.contentResolver, KEY_DEFAULT_FUNCTIONS, "4")   // MTP
                }
                ctx.sendBroadcast(
                    Intent("com.hione.sysbridge.USB_DAC").setPackage("com.hione.sysbridge").putExtra("enable", enabled),
                    "com.hione.permission.SYSTEM_BRIDGE"
                )
                Log.i(TAG, "USB DAC mode ${if (enabled) "on" else "off"} requested via com.hione.sysbridge")
                true
            }.onFailure { Log.e(TAG, "setUsbDacMode($enabled) failed", it) }.getOrDefault(false)
        }

    /** Put the gadget back on MTP (+ADB stays if enabled) through the public UsbManager API (MANAGE_USB). */
    private fun restoreAndroidUsb(ctx: Context): Boolean = runCatching {
        val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val mtp = FUNCTION_MTP
        // Hidden-but-stable @SystemApi pair; platform-signed apps are exempt from hidden-API checks.
        val cls = UsbManager::class.java
        cls.getMethod("setScreenUnlockedFunctions", Long::class.javaPrimitiveType).invoke(um, mtp)
        cls.getMethod("setCurrentFunctions", Long::class.javaPrimitiveType).invoke(um, mtp)
        true
    }.onFailure { Log.w(TAG, "UsbManager function restore failed: $it") }.getOrDefault(false)

    /** Open HiBy's DAC screen (where "Android mode" performs the complete vendor-level exit). */
    fun openHibyDacScreen(ctx: Context): Boolean = runCatching {
        ctx.startActivity(Intent(ACTION_WORK_MODE_VIEW).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    }.getOrDefault(false)
}
