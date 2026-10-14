package com.hione.launcher

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object CirrusLogicManager {
    private const val TAG = "HioneOS_CS43198"
    private const val SYSFS_BASE = "/sys/devices/platform/sa_sound_setting"

    enum class DigitalFilter(val id: String, val label: String, val description: String) {
        FAST_LINEAR("fast_rolloff_phase_compensated", "Fast Roll-off, Phase Compensated", "Linear phase with a sharp cutoff"),
        FAST_MINIMUM("fast_rolloff_low_latency", "Fast Roll-off, Low Latency", "Minimum phase with a sharp cutoff and low delay"),
        SLOW_LINEAR("slow_rolloff_phase_compensated", "Slow Roll-off, Phase Compensated", "Linear phase with a gentle cutoff"),
        SLOW_MINIMUM("slow_rolloff_low_latency", "Slow Roll-off, Low Latency", "Minimum phase with a gentle cutoff and little pre-ringing"),
        NOS("nos", "Non-Oversampling (NOS)", "Skips the DAC's digital oversampling filter")
    }

    enum class GainMode(val id: String, val label: String, val sysfsValue: String, val description: String) {
        // Figures from the driver (hioneos/docs/hiby-audio-knobs.md): a digital offset on the 3.5 and
        // 4.4 mm outputs, low -12 dB, high 0 dB. Not an analog gain change.
        LOW("low", "Low (-12 dB)", "low", "Digital offset of -12 dB on the 3.5 and 4.4 mm outputs. More volume steps for sensitive IEMs."),
        HIGH("high", "High (0 dB)", "high", "No digital offset. For louder output use High power.")
    }

    enum class OutputMode(val id: String, val label: String, val sysfsValue: String, val icon: String, val description: String) {
        AUTO("auto", "Auto (wired or Bluetooth)", "auto", "⚡", "Plays to whatever is plugged in or paired"),
        BAL_HEADPHONE_OUT("bal_po", "4.4mm Balanced (BAL PO)", "bal_po", "🎧", "Always use the 4.4mm balanced output"),
        HEADPHONE_OUT("po", "3.5mm Single-Ended (PO)", "po", "🎧", "Always use the 3.5mm headphone output"),
        BLUETOOTH("bt", "Bluetooth Audio (A2DP / Speaker)", "bt", "🔊", "Always play to the connected Bluetooth device"),
        LINE_OUT("lo", "Line Out (LO / BAL LO)", "lo", "📻", "Fixed-level line out for an external amp"),
        USB_DAC("usb", "USB-C Audio / UAC2 DAC", "usb", "💻", "Play to a USB-C DAC")
    }

    enum class AudioShareTarget(val id: String, val label: String, val description: String) {
        DUAL_44_AND_BT("dual_44_bt", "4.4mm Balanced DAC + Bluetooth Speaker", "Plays on 4.4mm and a Bluetooth device at the same time"),
        DUAL_35_AND_BT("dual_35_bt", "3.5mm Single-Ended DAC + Bluetooth Speaker", "Plays on 3.5mm and a Bluetooth device at the same time"),
        DUAL_PHYSICAL("dual_phy", "Both Physical Ports (3.5mm + 4.4mm Balanced)", "Plays on 3.5mm and 4.4mm at the same time"),
        WIRED_AND_USB("wired_usb", "Wired DAC + USB-C External DAC", "Plays on the internal CS43198 and a USB-C DAC at the same time")
    }

    /**
     * Where a value came from. This is the whole point of the class's read path: the DAC controls
     * used to fall back to our OWN SharedPreferences mirror and hand the result back through the
     * "honest" display readers, so the UI re-read what WE had last written and called it a
     * hardware state. Re-reading proved nothing.
     */
    enum class Source {
        /**
         * What the DAC was last told: persist.vendor.audio.hione.* (set by com.hione.sysbridge, then
         * written to the sysfs node by the image's hione_audio.rc). Apps cannot read the node
         * itself, so this is the closest real source. Shared by every HioneOS DAC screen.
         */
        SYSFS,
        /** Read from the vendor.audio.hiby.* Settings.Global row the HiBy audio HAL consumes. */
        VENDOR_SETTING,
        /** Our own prefs mirror: what the user ASKED for. Says nothing about the hardware. */
        USER_REQUEST
    }

    /** A value plus where it came from, so a surface can say "measured" vs "requested". */
    data class Reading<T>(val value: T, val source: Source) {
        /** True when this came from a real source, not from our own prefs mirror. */
        val isMeasured: Boolean get() = source != Source.USER_REQUEST
        /** True only when the kernel node itself was read back. */
        val isKernelVerified: Boolean get() = source == Source.SYSFS
    }

    /**
     * REAL kernel read only. Null when the node is missing / unreadable / empty. It must never
     * fall back to anything we wrote ourselves.
     */
    private fun readKernelNode(node: String): String? = try {
        val file = File("$SYSFS_BASE/$node")
        if (file.exists() && file.canRead()) file.readText().trim().takeIf { it.isNotEmpty() } else null
    } catch (_: Throwable) { null }

    /** The prefs mirror: what the user last ASKED for. Only ever a [Source.USER_REQUEST] tier. */
    private fun readRequestedPref(ctx: Context, node: String): String? = try {
        ctx.getSharedPreferences("hione_dac_settings", Context.MODE_PRIVATE)
            .getString(node, null)?.trim()?.takeIf { it.isNotEmpty() }
    } catch (_: Throwable) { null }

    /** A vendor.audio.hiby.* Settings.Global row (the HAL reads these). Null when unset. */
    private fun readVendorSetting(ctx: Context, vararg keys: String): String? {
        for (k in keys) {
            val v = try { Settings.Global.getString(ctx.contentResolver, k) } catch (_: Throwable) { null }
            if (!v.isNullOrBlank()) return v.trim()
        }
        return null
    }

    fun getOutputMode(ctx: Context): OutputMode {
        val prefs = ctx.getSharedPreferences("hione_dac_settings", Context.MODE_PRIVATE)
        val saved = prefs.getString("out_mode", "auto") ?: "auto"
        return OutputMode.values().firstOrNull { it.sysfsValue == saved } ?: OutputMode.AUTO
    }

    /**
     * Route switching. Every branch pushes its key=value pair to the audio HAL through
     * [AudioManager.setParameters], which is the channel that reaches the DAC on this unit.
     *
     * Each branch also used to run a RootShell twin of the same write (`echo … > out_mode;
     * setprop …`). There is no su on HioneOS, so that line never executed; the HAL push beside it
     * was already doing the whole job. Removed rather than kept "in case root appears", because a
     * dead shell fork per route change is pure cost and reading it suggests the HAL push is only
     * half the story. See [HioneHalAudio].
     */
    suspend fun setOutputMode(ctx: Context, mode: OutputMode) = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        val prefs = ctx.getSharedPreferences("hione_dac_settings", Context.MODE_PRIVATE)
        prefs.edit().putString("out_mode", mode.sysfsValue).apply()
        runCatching { Settings.Global.putString(cr, "vendor.audio.hiby.hw.output_mode", mode.sysfsValue) }

        val am = ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        when (mode) {
            OutputMode.BAL_HEADPHONE_OUT -> {
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
                am?.setParameters("routing=128;vendor.audio.hiby.hw.output_mode=bt")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && am != null) {
                    val btDev = am.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_HEARING_AID || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                    }
                    if (btDev != null) am.setCommunicationDevice(btDev)
                }
            }
            OutputMode.LINE_OUT -> {
                am?.setParameters("routing=8;vendor.audio.hiby.hw.output_mode=lo")
            }
            OutputMode.USB_DAC -> {
                am?.setParameters("routing=16384;vendor.audio.hiby.hw.output_mode=usb")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && am != null) {
                    val usbDev = am.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE
                    }
                    if (usbDev != null) am.setCommunicationDevice(usbDev)
                }
            }
            OutputMode.AUTO -> {
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

    fun isAudioShareEnabled(ctx: Context): Boolean {
        val prefs = ctx.getSharedPreferences("hione_dac_settings", Context.MODE_PRIVATE)
        return prefs.getBoolean("audio_share_enabled", false)
    }

    suspend fun setAudioShareEnabled(ctx: Context, enabled: Boolean) = withContext(Dispatchers.IO) {
        val prefs = ctx.getSharedPreferences("hione_dac_settings", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("audio_share_enabled", enabled).apply()
        val v = if (enabled) 1 else 0
        runCatching { Settings.Global.putInt(ctx.contentResolver, "hione_audio_share_enabled", v) }
        val am = ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        if (enabled) {
            val target = getAudioShareTarget(ctx)
            when (target) {
                AudioShareTarget.DUAL_44_AND_BT -> {
                    am?.setParameters("vendor.audio.dual_output=1;vendor.audio.bt_dual_stream=1;vendor.audio.hiby.hw.output_mode=bal_po")
                }
                AudioShareTarget.DUAL_35_AND_BT -> {
                    am?.setParameters("vendor.audio.dual_output=1;vendor.audio.bt_dual_stream=1;vendor.audio.hiby.hw.output_mode=po")
                }
                AudioShareTarget.DUAL_PHYSICAL -> {
                    am?.setParameters("vendor.audio.dual_output=1")
                }
                AudioShareTarget.WIRED_AND_USB -> {
                    am?.setParameters("vendor.audio.usb_mirror=1")
                }
            }
        } else {
            am?.setParameters("vendor.audio.dual_output=0;vendor.audio.bt_dual_stream=0;vendor.audio.usb_mirror=0")
            // Restore current single output mode
            setOutputMode(ctx, getOutputMode(ctx))
        }
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

    // ---- Filter, gain, DRE and high power: the four knobs that move hardware ----
    // Writes go through com.hione.sysbridge ([DacBridge]). Reads come from
    // persist.vendor.audio.hione.* first, the same values Hione Music, the Hardware app, HioneOS
    // Settings and the SystemUI tiles show. See hioneos/docs/hiby-audio-knobs.md.

    // Explicit user choices. Hione Music's ensureBestAudio re-applies these on every player start
    // and pushes its defaults for any knob without a row. Same keys as com.hione.player.HioneDirectAudio.
    private const val KEY_USER_GAIN = "hione_audio_user_gain"
    private const val KEY_USER_DRE = "hione_audio_user_dre"
    private const val KEY_USER_HIGH_POWER = "hione_audio_user_high_power"

    /** Send one knob through the bridge. A failure is shown as a toast, since callers here ignore the result. */
    private fun bridged(ctx: Context, knob: String, value: String): DacBridge.Result {
        val r = DacBridge.set(ctx, knob, value)
        val msg = DacBridge.lastProblem
        if (r != DacBridge.Result.CONFIRMED && msg != null) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching { android.widget.Toast.makeText(ctx.applicationContext, msg, android.widget.Toast.LENGTH_LONG).show() }
            }
        }
        return r
    }

    /**
     * Tiered read: kernel node, then the vendor Settings.Global row the HAL consumes, then our own
     * prefs mirror (tagged [Source.USER_REQUEST] — a request, NOT a hardware state). Null when no
     * tier has anything. Display surfaces use this and must honour [Reading.isMeasured].
     */
    fun readDigitalFilter(ctx: Context): Reading<DigitalFilter>? {
        DacBridge.get(DacBridge.FILTER)?.let { v ->
            DigitalFilter.values().firstOrNull { it.id == v.lowercase() }
                ?.let { return Reading(it, Source.SYSFS) }
        }
        readVendorSetting(ctx, "vendor.audio.hiby.hw.digital_filter",
            "vendor.audio.hiby.digital_filter", "hw.digital_filter")?.let { v ->
            DigitalFilter.values().firstOrNull { it.id == v.lowercase() }
                ?.let { return Reading(it, Source.VENDOR_SETTING) }
        }
        readRequestedPref(ctx, "digital_filter")?.let { v ->
            DigitalFilter.values().firstOrNull { it.id == v.lowercase() }
                ?.let { return Reading(it, Source.USER_REQUEST) }
        }
        return null
    }

    /** Control-UI getter: falls back to a preset so the picker has a selection. Never a measurement. */
    fun getDigitalFilter(ctx: Context): DigitalFilter =
        readDigitalFilter(ctx)?.value ?: DigitalFilter.FAST_LINEAR

    suspend fun setDigitalFilter(ctx: Context, filter: DigitalFilter) = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        runCatching { Settings.Global.putString(cr, "vendor.audio.hiby.hw.digital_filter", filter.id) }
        runCatching { Settings.Global.putString(cr, "vendor.audio.hiby.digital_filter", filter.id) }
        runCatching { Settings.Global.putString(cr, "hw.digital_filter", filter.id) }

        bridged(ctx, DacBridge.FILTER, filter.id)
    }

    /** Tiered read — see [readDigitalFilter]. Null when nothing anywhere reports a gain. */
    fun readGainMode(ctx: Context): Reading<GainMode>? {
        DacBridge.get(DacBridge.GAIN)?.let { v ->
            GainMode.values().firstOrNull { it.sysfsValue == v.lowercase() }
                ?.let { return Reading(it, Source.SYSFS) }
        }
        readVendorSetting(ctx, "vendor.audio.hiby.hw.gain", "vendor.audio.hiby.gain")?.let { v ->
            GainMode.values().firstOrNull { it.sysfsValue == v.lowercase() }
                ?.let { return Reading(it, Source.VENDOR_SETTING) }
        }
        readRequestedPref(ctx, "gain")?.let { v ->
            GainMode.values().firstOrNull { it.sysfsValue == v.lowercase() }
                ?.let { return Reading(it, Source.USER_REQUEST) }
        }
        return null
    }

    /** Control-UI getter: falls back to a preset so the toggle has a selection. Never a measurement. */
    fun getGainMode(ctx: Context): GainMode = readGainMode(ctx)?.value ?: GainMode.HIGH

    /** Gain only. High power is its own switch, as in Hione Music. */
    suspend fun setGainMode(ctx: Context, mode: GainMode) = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        val prefs = ctx.getSharedPreferences("hione_dac_settings", Context.MODE_PRIVATE)
        prefs.edit().putString("gain", mode.sysfsValue).apply()
        runCatching { Settings.Global.putString(cr, KEY_USER_GAIN, mode.sysfsValue) }
        runCatching { Settings.Global.putString(cr, "vendor.audio.hiby.hw.gain", mode.sysfsValue) }
        runCatching { Settings.Global.putString(cr, "vendor.audio.hiby.gain", mode.sysfsValue) }

        bridged(ctx, DacBridge.GAIN, mode.sysfsValue)
    }

    private fun parseEnabled(v: String, onToken: String): Boolean =
        v == onToken || v == "1" || v.equals("on", true) || v.contains("enable")

    /** Tiered read — see [readDigitalFilter]. Null when nothing anywhere reports DRE. */
    fun readDre(ctx: Context): Reading<Boolean>? {
        DacBridge.get(DacBridge.DRE)?.let { return Reading(it == "dremode_enable", Source.SYSFS) }
        readVendorSetting(ctx, "vendor.audio.hiby.hw.dre", "vendor.audio.hiby.dre_mode")
            ?.let { return Reading(parseEnabled(it, "dremode_enable"), Source.VENDOR_SETTING) }
        readRequestedPref(ctx, "dre_mode")
            ?.let { return Reading(parseEnabled(it, "dremode_enable"), Source.USER_REQUEST) }
        return null
    }

    /** Control-UI getter: false when unknown, so the switch has a position. Never a measurement. */
    fun isDreEnabled(ctx: Context): Boolean = readDre(ctx)?.value ?: false

    suspend fun setDreEnabled(ctx: Context, enabled: Boolean) = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        val v = if (enabled) 1 else 0
        val sysfsStr = if (enabled) "dremode_enable" else "dremode_disable"
        val prefs = ctx.getSharedPreferences("hione_dac_settings", Context.MODE_PRIVATE)
        prefs.edit().putString("dre_mode", sysfsStr).apply()
        runCatching { Settings.Global.putInt(cr, KEY_USER_DRE, v) }
        runCatching { Settings.Global.putInt(cr, "vendor.audio.hiby.hw.dre", v) }
        runCatching { Settings.Global.putString(cr, "vendor.audio.hiby.dre_mode", sysfsStr) }
        bridged(ctx, DacBridge.DRE, sysfsStr)
    }

    /** Tiered read — see [readDigitalFilter]. Null when nothing anywhere reports high power. */
    fun readHighPower(ctx: Context): Reading<Boolean>? {
        DacBridge.get(DacBridge.HIGH_POWER)?.let { return Reading(it == "hpower_enable", Source.SYSFS) }
        readVendorSetting(ctx, "vendor.audio.hiby.hw.high_power", "vendor.audio.hiby.high_power_mode",
            "vendor.audio.hiby.high_power")?.let { return Reading(parseEnabled(it, "hpower_enable"), Source.VENDOR_SETTING) }
        readRequestedPref(ctx, "high_power_mode")
            ?.let { return Reading(parseEnabled(it, "hpower_enable"), Source.USER_REQUEST) }
        return null
    }

    /** Control-UI getter: false when unknown, so the switch has a position. Never a measurement. */
    fun isHighPowerEnabled(ctx: Context): Boolean = readHighPower(ctx)?.value ?: false

    suspend fun setHighPowerEnabled(ctx: Context, enabled: Boolean) = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        val v = if (enabled) 1 else 0
        val sysfsStr = if (enabled) "hpower_enable" else "hpower_disable"
        val prefs = ctx.getSharedPreferences("hione_dac_settings", Context.MODE_PRIVATE)
        prefs.edit().putString("high_power_mode", sysfsStr).apply()
        runCatching { Settings.Global.putInt(cr, KEY_USER_HIGH_POWER, v) }
        runCatching { Settings.Global.putInt(cr, "vendor.audio.hiby.hw.high_power", v) }
        runCatching { Settings.Global.putString(cr, "vendor.audio.hiby.high_power", sysfsStr) }
        runCatching { Settings.Global.putString(cr, "vendor.audio.hiby.high_power_mode", sysfsStr) }
        // The same volume step is louder with the external amp stage in: lower music to 30% first.
        if (enabled && DacBridge.get(DacBridge.HIGH_POWER) != "hpower_enable" && DacBridge.available(ctx)) {
            runCatching {
                val am = ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val safe = (am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 0.3f).toInt().coerceAtLeast(1)
                if (am.getStreamVolume(AudioManager.STREAM_MUSIC) > safe) {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, safe, 0)
                }
            }
        }
        bridged(ctx, DacBridge.HIGH_POWER, sysfsStr)
    }

    /** Tiered read — see [readDigitalFilter]. Null when nothing anywhere reports a balance. */
    fun readBalance(ctx: Context): Reading<Int>? {
        readKernelNode("lr_balance")?.toIntOrNull()?.let { return Reading(it, Source.SYSFS) }
        readVendorSetting(ctx, "vendor.audio.hiby.hw.balance")?.toIntOrNull()
            ?.let { return Reading(it, Source.VENDOR_SETTING) }
        readRequestedPref(ctx, "lr_balance")?.toIntOrNull()
            ?.let { return Reading(it, Source.USER_REQUEST) }
        return null
    }

    /** Control-UI getter: 0 (centre) when unknown, so the slider has a position. Never a measurement. */
    fun getBalance(ctx: Context): Int = readBalance(ctx)?.value ?: 0

    suspend fun setBalance(ctx: Context, balance: Int) = withContext(Dispatchers.IO) {
        val clamped = balance.coerceIn(-10, 10)
        val cr = ctx.contentResolver
        val prefs = ctx.getSharedPreferences("hione_dac_settings", Context.MODE_PRIVATE)
        prefs.edit().putString("lr_balance", clamped.toString()).apply()
        runCatching { Settings.Global.putInt(cr, "vendor.audio.hiby.hw.balance", clamped) }
        // The HAL parameter is the route that actually lands on this hardware. What used to be
        // here was a RootShell line (echo > sysfs / setprop), and there is no su on HioneOS, so the
        // Settings row above changed while the DAC did not and the UI echoed the choice back as
        // applied. See [HioneHalAudio].
        HioneHalAudio.push(ctx, "vendor.audio.hiby.hw.balance", clamped)
        HioneHalAudio.push(ctx, "vendor.audio.hiby.lr_balance", clamped)
    }

    fun getDsdGainCompensate(ctx: Context): Boolean {
        val cr = ctx.contentResolver
        return try { Settings.Global.getInt(cr, "vendor.audio.hiby.hw.dsd_gain_comp", 1) == 1 } catch (_: Throwable) { true }
    }

    suspend fun setDsdGainCompensate(ctx: Context, enabled: Boolean) = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        val v = if (enabled) 1 else 0
        runCatching { Settings.Global.putInt(cr, "vendor.audio.hiby.hw.dsd_gain_comp", v) }
        // The HAL parameter is the route that actually lands on this hardware. What used to be
        // here was a RootShell line (echo > sysfs / setprop), and there is no su on HioneOS, so the
        // Settings row above changed while the DAC did not and the UI echoed the choice back as
        // applied. See [HioneHalAudio].
        HioneHalAudio.push(ctx, "vendor.audio.hiby.hw.dsd_gain_comp", v)
    }

    // ---- Honest (nullable) readers for DISPLAY surfaces ------------------------------------
    // These report REAL sources ONLY: the kernel sysfs node, or the vendor.audio.hiby.* Settings
    // .Global row the HiBy audio HAL consumes. They used to go through a reader that fell back to
    // our own "hione_dac_settings" SharedPreferences — the exact values our own setters had just
    // written — so "re-reading the DAC" returned our last write and proved nothing. null now means
    // the hardware genuinely cannot be read; render "—", never a guess.
    // For "what the user asked for", use the read*() functions and check Reading.isMeasured.

    fun getDigitalFilterOrNull(ctx: Context): DigitalFilter? =
        readDigitalFilter(ctx)?.takeIf { it.isMeasured }?.value

    fun getGainModeOrNull(ctx: Context): GainMode? =
        readGainMode(ctx)?.takeIf { it.isMeasured }?.value

    fun isDreEnabledOrNull(ctx: Context): Boolean? =
        readDre(ctx)?.takeIf { it.isMeasured }?.value

    fun isHighPowerEnabledOrNull(ctx: Context): Boolean? =
        readHighPower(ctx)?.takeIf { it.isMeasured }?.value

    fun getBalanceOrNull(ctx: Context): Int? =
        readBalance(ctx)?.takeIf { it.isMeasured }?.value

    /** Raw kernel node dump. "N/A" = that node could not be read; no prefs mirror is consulted. */
    fun getLiveHardwareAudit(ctx: Context): Map<String, String> {
        val audit = mutableMapOf<String, String>()
        // Apps cannot read these nodes. The persist property is what the DAC was last told.
        audit["applied_filter"] = DacBridge.get(DacBridge.FILTER) ?: "N/A"
        audit["applied_gain"] = DacBridge.get(DacBridge.GAIN) ?: "N/A"
        audit["applied_dre"] = DacBridge.get(DacBridge.DRE) ?: "N/A"
        audit["applied_high_power"] = DacBridge.get(DacBridge.HIGH_POWER) ?: "N/A"
        audit["kernel_sysfs_turbo"] = readKernelNode("audio_turbo") ?: readKernelNode("turbo") ?: "N/A"
        audit["kernel_sysfs_out_mode"] = readKernelNode("out_mode") ?: "N/A"
        audit["kernel_sysfs_balance"] = readKernelNode("lr_balance") ?: "N/A"
        // getprop never needed root; reading it through a su shell that does not exist turned two
        // readable properties into "N/A" on every audit. SystemProperties.get is the direct read.
        audit["prop_hw_filter"] = HioneHalAudio.sysProp("vendor.audio.hiby.hw.digital_filter") ?: "N/A"
        audit["prop_hw_gain"] = HioneHalAudio.sysProp("vendor.audio.hiby.hw.gain") ?: "N/A"
        return audit
    }
}
