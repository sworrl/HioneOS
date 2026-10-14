package com.hione.settings

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Usb
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * DAC controls for HioneOS Settings. Filter, gain, DRE and high power are the only
 * sa_sound_setting nodes that change hardware on the M500, and they go through [DacBridge].
 * Output mode, balance and DSD compensation are not real knobs on this firmware
 * (hioneos/docs/hiby-audio-knobs.md, sections 3.2 and 5). Their writes are left as they were.
 */
object CirrusLogicManager {
    private const val TAG = "HioneOS_CS43198"
    private const val SYSFS_BASE = "/sys/devices/platform/sa_sound_setting"

    enum class DigitalFilter(val id: String, val label: String, val description: String) {
        FAST_LINEAR("fast_rolloff_phase_compensated", "Fast Roll-off, Phase Compensated", "Linear phase. The standard reference filter"),
        FAST_MINIMUM("fast_rolloff_low_latency", "Fast Roll-off, Low Latency", "Minimum phase with low group delay"),
        SLOW_LINEAR("slow_rolloff_phase_compensated", "Slow Roll-off, Phase Compensated", "Linear phase with a gentler roll-off"),
        SLOW_MINIMUM("slow_rolloff_low_latency", "Slow Roll-off, Low Latency", "Minimum phase, gentle roll-off, little pre-ringing"),
        NOS("nos", "Non-Oversampling (NOS)", "Skips the DAC's digital oversampling filter")
    }

    enum class GainMode(val id: String, val label: String, val sysfsValue: String, val description: String) {
        LOW("low", "Low (-12 dB)", "low", "Digital offset of -12 dB on the 3.5 and 4.4 mm outputs. More volume steps for sensitive IEMs."),
        HIGH("high", "High (0 dB)", "high", "No digital offset. For louder output use High power.")
    }

    enum class OutputMode(val id: String, val label: String, val sysfsValue: String, val icon: ImageVector, val description: String) {
        AUTO("auto", "Auto (wired or Bluetooth)", "auto", Icons.Default.Autorenew, "Plays to whichever wired port or Bluetooth device is connected"),
        BAL_HEADPHONE_OUT("bal_po", "4.4mm Balanced (BAL PO)", "bal_po", Icons.Default.Headphones, "Always use the 4.4mm balanced output"),
        HEADPHONE_OUT("po", "3.5mm Single-Ended (PO)", "po", Icons.Default.Headphones, "Always use the 3.5mm headphone output"),
        BLUETOOTH("bt", "Bluetooth Audio (A2DP / Speaker)", "bt", Icons.Default.Bluetooth, "Always send audio to the connected Bluetooth device"),
        LINE_OUT("lo", "Line Out (LO / BAL LO)", "lo", Icons.Default.Cable, "Fixed-level line output for an external amp"),
        USB_DAC("usb", "USB-C Audio / UAC2 DAC", "usb", Icons.Default.Usb, "Send audio to an external USB-C DAC")
    }

    enum class AudioShareTarget(val id: String, val label: String, val description: String) {
        DUAL_44_AND_BT("dual_44_bt", "4.4mm Balanced + Bluetooth", "Plays to 4.4mm balanced and Bluetooth at the same time"),
        DUAL_35_AND_BT("dual_35_bt", "3.5mm Single-Ended + Bluetooth", "Plays to 3.5mm and Bluetooth at the same time"),
        DUAL_PHYSICAL("dual_phy", "Both Wired Ports (3.5mm + 4.4mm)", "Plays to the 3.5mm and 4.4mm ports at the same time"),
        WIRED_AND_USB("wired_usb", "Wired + USB-C DAC", "Plays to the internal CS43198 and an external USB-C DAC at the same time")
    }

    /**
     * REAL kernel read only. Returns null when the node is missing / unreadable / empty. (It used
     * to fall back to a SharedPreferences shadow written by our own setters and callers treated
     * that as a kernel value — a fabricated "hardware" reading.)
     */
    private fun readSysfs(ctx: Context, node: String): String? {
        return try {
            val file = File("$SYSFS_BASE/$node")
            if (file.exists() && file.canRead()) file.readText().trim().takeIf { it.isNotEmpty() } else null
        } catch (_: Throwable) { null }
    }

    /** True when the DAC sysfs directory exists and at least one control node is readable. */
    fun isSysfsReachable(): Boolean = try {
        val dir = File(SYSFS_BASE)
        dir.isDirectory && (dir.listFiles()?.any { it.canRead() } == true)
    } catch (_: Throwable) { false }

    /** True when any of the HiBy DAC Settings.Global keys has ever been written (so a fallback value exists). */
    fun hasPersistedDacSettings(ctx: Context): Boolean = try {
        val cr = ctx.contentResolver
        listOf("vendor.audio.hiby.hw.digital_filter", "vendor.audio.hiby.digital_filter", "vendor.audio.hiby.hw.gain",
            "vendor.audio.hiby.gain", "vendor.audio.hiby.hw.dre", "vendor.audio.hiby.hw.high_power")
            .any { Settings.Global.getString(cr, it) != null }
    } catch (_: Throwable) { false }

    fun getOutputMode(ctx: Context): OutputMode {
        val prefs = ctx.getSharedPreferences("hione_dac_settings", Context.MODE_PRIVATE)
        val saved = prefs.getString("out_mode", "auto") ?: "auto"
        return OutputMode.values().firstOrNull { it.sysfsValue == saved } ?: OutputMode.AUTO
    }

    suspend fun setOutputMode(ctx: Context, mode: OutputMode) = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        val prefs = ctx.getSharedPreferences("hione_dac_settings", Context.MODE_PRIVATE)
        prefs.edit().putString("out_mode", mode.sysfsValue).apply()
        runCatching { Settings.Global.putString(cr, "vendor.audio.hiby.hw.output_mode", mode.sysfsValue) }

        val am = ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        when (mode) {
            OutputMode.BAL_HEADPHONE_OUT -> {
                RootShell.execFast(
                    "echo bal_po > $SYSFS_BASE/bal_po_lo_switch 2>/dev/null; " +
                    "echo bal_po > $SYSFS_BASE/dac_output_type 2>/dev/null; " +
                    "echo 0 > $SYSFS_BASE/bal_po_lo_switch 2>/dev/null; " +
                    "settings put global vendor.audio.hiby.hw.output_mode bal_po; " +
                    "setprop vendor.audio.hiby.hw.output_mode bal_po"
                )
                am?.setParameters("routing=4;vendor.audio.hiby.hw.output_mode=bal_po")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && am != null) {
                    val wiredDev = am.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                    }
                    if (wiredDev != null) am.setCommunicationDevice(wiredDev)
                    else am.clearCommunicationDevice()
                }
            }
            OutputMode.HEADPHONE_OUT -> {
                RootShell.execFast(
                    "echo po > $SYSFS_BASE/po_lo_switch 2>/dev/null; " +
                    "echo po > $SYSFS_BASE/dac_output_type 2>/dev/null; " +
                    "echo 0 > $SYSFS_BASE/po_lo_switch 2>/dev/null; " +
                    "settings put global vendor.audio.hiby.hw.output_mode po; " +
                    "setprop vendor.audio.hiby.hw.output_mode po"
                )
                am?.setParameters("routing=4;vendor.audio.hiby.hw.output_mode=po")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && am != null) {
                    val wiredDev = am.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                    }
                    if (wiredDev != null) am.setCommunicationDevice(wiredDev)
                    else am.clearCommunicationDevice()
                }
            }
            OutputMode.BLUETOOTH -> {
                RootShell.execFast("settings put global vendor.audio.hiby.hw.output_mode bt; setprop vendor.audio.hiby.hw.output_mode bt")
                am?.setParameters("routing=128;vendor.audio.hiby.hw.output_mode=bt")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && am != null) {
                    val btDev = am.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_HEARING_AID || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                    }
                    if (btDev != null) am.setCommunicationDevice(btDev)
                }
            }
            OutputMode.LINE_OUT -> {
                RootShell.execFast(
                    "echo lo > $SYSFS_BASE/po_lo_switch 2>/dev/null; " +
                    "echo bal_lo > $SYSFS_BASE/bal_po_lo_switch 2>/dev/null; " +
                    "echo lo > $SYSFS_BASE/dac_output_type 2>/dev/null; " +
                    "settings put global vendor.audio.hiby.hw.output_mode lo; " +
                    "setprop vendor.audio.hiby.hw.output_mode lo"
                )
                am?.setParameters("routing=8;vendor.audio.hiby.hw.output_mode=lo")
            }
            OutputMode.USB_DAC -> {
                RootShell.execFast("settings put global vendor.audio.hiby.hw.output_mode usb; setprop vendor.audio.hiby.hw.output_mode usb")
                am?.setParameters("routing=16384;vendor.audio.hiby.hw.output_mode=usb")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && am != null) {
                    val usbDev = am.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE
                    }
                    if (usbDev != null) am.setCommunicationDevice(usbDev)
                }
            }
            OutputMode.AUTO -> {
                RootShell.execFast("settings put global vendor.audio.hiby.hw.output_mode auto; setprop vendor.audio.hiby.hw.output_mode auto")
                am?.setParameters("vendor.audio.hiby.hw.output_mode=auto")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && am != null) {
                    am.clearCommunicationDevice()
                }
            }
        }
    }

    suspend fun swapOutputMode(ctx: Context): OutputMode {
        val cur = getOutputMode(ctx)
        val next = if (cur == OutputMode.BLUETOOTH) OutputMode.BAL_HEADPHONE_OUT else OutputMode.BLUETOOTH
        setOutputMode(ctx, next)
        return next
    }

    /**
     * Play to every connected output at once (not the built-in speaker). The player does the
     * work (com.hione.player.HioneMirrorOutput); this is only the switch, in Settings.Global so the
     * player sees it. Unset means ON.
     */
    fun isAudioShareEnabled(ctx: Context): Boolean =
        runCatching { Settings.Global.getInt(ctx.contentResolver, "hione_audio_share_enabled", 1) != 0 }
            .getOrDefault(true)

    /**
     * The vendor parameters this used to send (vendor.audio.dual_output, bt_dual_stream,
     * usb_mirror, and setprops of the same names) exist nowhere in this device's audio HAL or
     * its configs; nothing read them. Writing the switch is the whole job now.
     */
    suspend fun setAudioShareEnabled(ctx: Context, enabled: Boolean) = withContext(Dispatchers.IO) {
        ctx.getSharedPreferences("hione_dac_settings", Context.MODE_PRIVATE)
            .edit().putBoolean("audio_share_enabled", enabled).apply()
        runCatching { Settings.Global.putInt(ctx.contentResolver, "hione_audio_share_enabled", if (enabled) 1 else 0) }
        Unit
    }

    fun getAudioShareTarget(ctx: Context): AudioShareTarget {
        val prefs = ctx.getSharedPreferences("hione_dac_settings", Context.MODE_PRIVATE)
        val saved = prefs.getString("audio_share_target", AudioShareTarget.DUAL_44_AND_BT.id)
        return AudioShareTarget.values().firstOrNull { it.id == saved } ?: AudioShareTarget.DUAL_44_AND_BT
    }

    suspend fun setAudioShareTarget(ctx: Context, target: AudioShareTarget) = withContext(Dispatchers.IO) {
        val prefs = ctx.getSharedPreferences("hione_dac_settings", Context.MODE_PRIVATE)
        prefs.edit().putString("audio_share_target", target.id).apply()
        runCatching { Settings.Global.putString(ctx.contentResolver, "hione_audio_share_target", target.id) }
        if (isAudioShareEnabled(ctx)) {
            setAudioShareEnabled(ctx, true)
        }
    }

    // ---- The four knobs that move hardware: filter, gain, DRE, high power ----
    // Writes go through com.hione.sysbridge (DacBridge). Reads come from
    // persist.vendor.audio.hione.*, the same values Hione Music, the Hardware app and the SystemUI tiles show. The
    // Settings.Global rows are still written so older readers agree, and are the fallback when
    // the bridge has never set a knob.

    // Explicit user choices. Hione Music's ensureBestAudio re-applies these on every player start
    // and pushes its defaults for any knob without a row. Same keys as com.hione.player.HioneDirectAudio.
    private const val KEY_USER_GAIN = "hione_audio_user_gain"
    private const val KEY_USER_DRE = "hione_audio_user_dre"
    private const val KEY_USER_HIGH_POWER = "hione_audio_user_high_power"

    private fun globalString(ctx: Context, vararg keys: String): String? {
        val cr = ctx.contentResolver
        for (k in keys) {
            runCatching { Settings.Global.getString(cr, k) }.getOrNull()?.trim()?.ifEmpty { null }?.let { return it }
        }
        return null
    }

    /** Persist property first, then the Settings.Global rows. Null = nothing says. */
    fun getDigitalFilter(ctx: Context): DigitalFilter? {
        val raw = DacBridge.get(DacBridge.FILTER)
            ?: globalString(ctx, "vendor.audio.hiby.hw.digital_filter", "vendor.audio.hiby.digital_filter", "hw.digital_filter")
            ?: return null
        return DigitalFilter.values().firstOrNull { it.id == raw.lowercase() }
    }

    suspend fun setDigitalFilter(ctx: Context, filter: DigitalFilter): DacBridge.Result = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        runCatching { Settings.Global.putString(cr, "vendor.audio.hiby.hw.digital_filter", filter.id) }
        runCatching { Settings.Global.putString(cr, "vendor.audio.hiby.digital_filter", filter.id) }
        runCatching { Settings.Global.putString(cr, "hw.digital_filter", filter.id) }
        DacBridge.set(ctx, DacBridge.FILTER, filter.id)
    }

    /** Persist property first, then the Settings.Global rows. Null = nothing says, or "middle". */
    fun getGainMode(ctx: Context): GainMode? {
        val raw = DacBridge.get(DacBridge.GAIN)
            ?: globalString(ctx, "vendor.audio.hiby.hw.gain", "vendor.audio.hiby.gain")
            ?: return null
        return GainMode.values().firstOrNull { it.sysfsValue == raw.lowercase() }
    }

    /** Gain only. High power is its own switch, as in Hione Music. */
    suspend fun setGainMode(ctx: Context, mode: GainMode): DacBridge.Result = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        runCatching { Settings.Global.putString(cr, KEY_USER_GAIN, mode.sysfsValue) }
        runCatching { Settings.Global.putString(cr, "vendor.audio.hiby.hw.gain", mode.sysfsValue) }
        runCatching { Settings.Global.putString(cr, "vendor.audio.hiby.gain", mode.sysfsValue) }
        DacBridge.set(ctx, DacBridge.GAIN, mode.sysfsValue)
    }

    /** Persist property first, then the Settings.Global rows. False when nothing says. */
    fun isDreEnabled(ctx: Context): Boolean {
        DacBridge.get(DacBridge.DRE)?.let { return it == "dremode_enable" }
        return globalString(ctx, "vendor.audio.hiby.dre_mode") == "dremode_enable"
    }

    suspend fun setDreEnabled(ctx: Context, enabled: Boolean): DacBridge.Result = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        val cmd = if (enabled) "dremode_enable" else "dremode_disable"
        runCatching { Settings.Global.putInt(cr, KEY_USER_DRE, if (enabled) 1 else 0) }
        runCatching { Settings.Global.putString(cr, "vendor.audio.hiby.dre_mode", cmd) }
        runCatching { Settings.Global.putInt(cr, "vendor.audio.hiby.hw.dre", if (enabled) 1 else 0) }
        DacBridge.set(ctx, DacBridge.DRE, cmd)
    }

    /** Persist property first, then the Settings.Global rows. False when nothing says. */
    fun isHighPowerEnabled(ctx: Context): Boolean {
        DacBridge.get(DacBridge.HIGH_POWER)?.let { return it == "hpower_enable" }
        return globalString(ctx, "vendor.audio.hiby.high_power", "vendor.audio.hiby.high_power_mode") == "hpower_enable"
    }

    /**
     * High power switches in the external amp stage, so the same volume step gets louder. When it
     * goes from off to on, music volume is lowered to 30% first if it is above that.
     */
    suspend fun setHighPowerEnabled(ctx: Context, enabled: Boolean): DacBridge.Result = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        val cmd = if (enabled) "hpower_enable" else "hpower_disable"
        runCatching { Settings.Global.putInt(cr, KEY_USER_HIGH_POWER, if (enabled) 1 else 0) }
        runCatching { Settings.Global.putString(cr, "vendor.audio.hiby.high_power", cmd) }
        runCatching { Settings.Global.putString(cr, "vendor.audio.hiby.high_power_mode", cmd) }
        runCatching { Settings.Global.putInt(cr, "vendor.audio.hiby.hw.high_power", if (enabled) 1 else 0) }
        if (enabled && DacBridge.get(DacBridge.HIGH_POWER) != "hpower_enable" && DacBridge.available(ctx)) {
            runCatching {
                val am = ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val safe = (am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 0.3f).toInt().coerceAtLeast(1)
                if (am.getStreamVolume(AudioManager.STREAM_MUSIC) > safe) {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, safe, 0)
                }
            }
        }
        DacBridge.set(ctx, DacBridge.HIGH_POWER, cmd)
    }

    /** One line of what the DAC was last told, from the persist properties. */
    fun appliedSummary(): String {
        val filter = DacBridge.get(DacBridge.FILTER)?.let { v -> DigitalFilter.values().firstOrNull { it.id == v }?.label ?: v } ?: "not set"
        val gain = DacBridge.get(DacBridge.GAIN) ?: "not set"
        val dre = when (DacBridge.get(DacBridge.DRE)) { "dremode_enable" -> "on"; "dremode_disable" -> "off"; null -> "not set"; else -> "unknown" }
        val hp = when (DacBridge.get(DacBridge.HIGH_POWER)) { "hpower_enable" -> "on"; "hpower_disable" -> "off"; null -> "not set"; else -> "unknown" }
        return "Filter: $filter. Gain: $gain. DRE: $dre. High power: $hp."
    }

    fun getBalance(ctx: Context): Int {
        val kernelVal = (readSysfs(ctx, "lrbalance") ?: readSysfs(ctx, "lr_balance"))?.toIntOrNull()
        if (kernelVal != null) return kernelVal
        val cr = ctx.contentResolver
        return try { Settings.Global.getInt(cr, "vendor.audio.hiby.hw.balance", 0) } catch (_: Throwable) { 0 }
    }

    suspend fun setBalance(ctx: Context, balance: Int) = withContext(Dispatchers.IO) {
        val clamped = balance.coerceIn(-10, 10)
        val cr = ctx.contentResolver
        val prefs = ctx.getSharedPreferences("hione_dac_settings", Context.MODE_PRIVATE)
        prefs.edit().putString("lrbalance", clamped.toString()).apply()
        runCatching { Settings.Global.putInt(cr, "vendor.audio.hiby.hw.balance", clamped) }
        RootShell.execFast("echo $clamped > $SYSFS_BASE/lrbalance 2>/dev/null; settings put global vendor.audio.hiby.hw.balance $clamped; setprop vendor.audio.hiby.hw.balance $clamped")
    }

    /** Null when the key has never been set — the toggle must not show "on" from a made-up default. */
    fun getDsdGainCompensate(ctx: Context): Boolean? {
        val cr = ctx.contentResolver
        return try { Settings.Global.getString(cr, "vendor.audio.hiby.hw.dsd_gain_comp")?.trim()?.let { it == "1" } } catch (_: Throwable) { null }
    }

    suspend fun setDsdGainCompensate(ctx: Context, enabled: Boolean) = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        val v = if (enabled) 1 else 0
        runCatching { Settings.Global.putInt(cr, "vendor.audio.hiby.hw.dsd_gain_comp", v) }
        RootShell.execFast("settings put global vendor.audio.hiby.hw.dsd_gain_comp $v; setprop vendor.audio.hiby.hw.dsd_gain_comp $v")
    }

    fun getLiveHardwareAudit(ctx: Context): Map<String, String> {
        val audit = mutableMapOf<String, String>()
        // Apps cannot read these nodes. The persist property is what the DAC was last told.
        audit["applied_filter"] = DacBridge.get(DacBridge.FILTER) ?: "N/A"
        audit["applied_gain"] = DacBridge.get(DacBridge.GAIN) ?: "N/A"
        audit["applied_dre"] = DacBridge.get(DacBridge.DRE) ?: "N/A"
        audit["applied_high_power"] = DacBridge.get(DacBridge.HIGH_POWER) ?: "N/A"
        audit["kernel_sysfs_turbo"] = readSysfs(ctx, "turbo") ?: "N/A"
        audit["kernel_sysfs_out_mode"] = readSysfs(ctx, "bal_po_lo_switch") ?: "N/A"
        audit["kernel_sysfs_balance"] = readSysfs(ctx, "lrbalance") ?: "N/A"
        audit["prop_hw_filter"] = RootShell.execOut("getprop vendor.audio.hiby.hw.digital_filter") ?: "N/A"
        audit["prop_hw_gain"] = RootShell.execOut("getprop vendor.audio.hiby.hw.gain") ?: "N/A"
        return audit
    }
}
