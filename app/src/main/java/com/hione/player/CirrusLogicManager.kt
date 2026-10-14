package com.hione.player

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Hardware controller for the HiBy M500's Dual Cirrus Logic CS43198 DAC
 * and audiophile subsystem via Direct Kernel Sysfs (`/sys/devices/platform/sa_sound_setting/`),
 * HAL System Properties, AudioRouting HAL, and Android Global Settings.
 */
object CirrusLogicManager {
    private const val TAG = "CirrusLogicManager"
    const val SYSFS_BASE = "/sys/devices/platform/sa_sound_setting"

    enum class DigitalFilter(val id: String, val label: String, val description: String) {
        FAST_LINEAR("fast_rolloff_phase_compensated", "Fast Roll-off, Phase Compensated", "Linear phase with a sharp cutoff"),
        FAST_MINIMUM("fast_rolloff_low_latency", "Fast Roll-off, Low Latency", "Minimum phase with a sharp cutoff and low delay"),
        SLOW_LINEAR("slow_rolloff_phase_compensated", "Slow Roll-off, Phase Compensated", "Linear phase with a gentle cutoff"),
        SLOW_MINIMUM("slow_rolloff_low_latency", "Slow Roll-off, Low Latency", "Minimum phase with a gentle cutoff and little pre-ringing"),
        NOS("nos", "NOS (non-oversampling)", "Skips the DAC's oversampling filter. Softer top end, no pre-ringing. HiBy's driver masked this off through firmware 1.20. ${Mascot.osName} ships HiBy's fixed 1.30 driver.")
    }

    enum class GainMode(val id: String, val label: String, val sysfsValue: String, val description: String) {
        LOW("low", "Low (-12 dB)", "low", "Digital offset of -12 dB on the 3.5 and 4.4 mm outputs. More volume steps for sensitive IEMs. Same noise floor."),
        HIGH("high", "High (0 dB)", "high", "No digital offset. For louder output use High power, which switches in the external amp stage.")
    }

    enum class OutputMode(val id: String, val label: String, val sysfsValue: String, val icon: String, val description: String) {
        AUTO("auto", "Auto (wired or Bluetooth)", "auto", "⚡", "Plays to whatever is plugged in or paired"),
        BAL_HEADPHONE_OUT("bal_po", "4.4mm Balanced (BAL PO)", "bal_po", "🎧", "Always use the 4.4mm balanced output"),
        HEADPHONE_OUT("po", "3.5mm Single-Ended (PO)", "po", "🎧", "Always use the 3.5mm headphone output"),
        BLUETOOTH("bt", "Bluetooth Audio (A2DP / Speaker)", "bt", "🔊", "Always play to the connected Bluetooth speaker or headphones"),
        LINE_OUT("lo", "Line Out (LO / BAL LO)", "lo", "📻", "Fixed-level line out for an external amp"),
        USB_DAC("usb", "USB-C Audio / UAC2 DAC", "usb", "💻", "Play to a USB-C DAC or audio device")
    }

    enum class AudioShareTarget(val id: String, val label: String, val description: String) {
        DUAL_44_AND_BT("dual_44_bt", "4.4mm Balanced DAC + Bluetooth Speaker", "Plays on 4.4mm and a Bluetooth device at the same time"),
        DUAL_35_AND_BT("dual_35_bt", "3.5mm Single-Ended DAC + Bluetooth Speaker", "Plays on 3.5mm and a Bluetooth device at the same time"),
        DUAL_PHYSICAL("dual_phy", "Both Physical Ports (3.5mm + 4.4mm Balanced)", "Plays on the 3.5mm and 4.4mm jacks at the same time"),
        WIRED_AND_USB("wired_usb", "Wired DAC + USB-C External DAC", "Plays on the internal CS43198 DAC and a USB-C DAC at the same time")
    }

    /**
     * Live DAC readout. Every field is nullable: null = that node/property could not be read, and
     * the UI must render it as "—" (see [kernelFilterText] and friends). [isSysfsReadable] is true
     * only when at least one node actually came back — it is NOT a claim that the kernel state and
     * the app's settings agree, which is what the old `isHardwareSynced` (hardcoded true, with
     * per-node "typical" values standing in for unreadable nodes) pretended to report.
     */
    data class HardwareAuditState(
        val kernelFilter: String? = null,
        val kernelGain: String? = null,
        val kernelHighPower: String? = null,
        val kernelDre: String? = null,
        val kernelTurbo: String? = null,
        val kernelOutput: String? = null,
        val kernelBalance: String? = null,
        val isSysfsReadable: Boolean = false,
        /** Where the values above actually came from, so the UI can say so instead of guessing. */
        val source: Source = Source.NONE
    ) {
        enum class Source { NONE, SYSFS, VENDOR_SETTINGS }
        val kernelFilterText: String get() = kernelFilter ?: "—"
        val kernelGainText: String get() = kernelGain ?: "—"
        val kernelHighPowerText: String get() = kernelHighPower ?: "—"
        val kernelDreText: String get() = kernelDre ?: "—"
        val kernelTurboText: String get() = kernelTurbo ?: "—"
        val kernelOutputText: String get() = kernelOutput ?: "—"
        val kernelBalanceText: String get() = kernelBalance ?: "—"
    }

    /**
     * No-op kept for call-site compatibility. It used to chmod the sa_sound_setting sysfs nodes
     * through su, which never runs on HioneOS (no root) - and the DAC does not need it: every write
     * this object performs now goes through the audio HAL (AudioManager.setParameters) and
     * Settings, which are the paths the vendor stack actually reads.
     */
    fun init(ctx: Context) {
        // Intentionally empty - see KDoc.
    }

    /**
     * Nodes SELinux refuses us, remembered for the life of the process.
     *
     * These sysfs files are denied to our domain (avc: denied { read } ... scontext=platform_app
     * tcontext=sysfs). The policy cannot change while we run, so a failure is permanent — but this
     * retried on EVERY call, and each attempt costs a kernel audit record. Measured on-device:
     * ~120 denials/second streaming into logcat and the player burning ~44% of a core while idle
     * with the screen off, which is what made lists scroll at a few frames per second.
     * Probe once per node, then never again.
     */
    private val unreadableNodes = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )

    private fun readSysfs(node: String): String? {
        if (node in unreadableNodes) return null
        try {
            val file = File("$SYSFS_BASE/$node")
            if (file.exists()) {
                val txt = file.readText().trim()
                if (txt.isNotEmpty()) return txt
            }
        } catch (_: Throwable) {}
        // No su fallback: the node is either world-readable (handled above) or it is not readable
        // by this app at all, and null is the honest answer - the audit UI renders it as "-".
        unreadableNodes.add(node)
        return null
    }

    // Filter, gain, DRE and high power read persist.vendor.audio.hione.* first: what the DAC was
    // last told through com.hione.sysbridge, shared with the Hardware app, HioneOS Settings, the
    // launcher and the SystemUI tiles. Then the old sources.
    fun getDigitalFilter(ctx: Context): DigitalFilter {
        com.hione.player.profiles.HibyDacBridge.get(com.hione.player.profiles.HibyDacBridge.PROP_FILTER)?.let { v ->
            DigitalFilter.values().firstOrNull { it.id == v.lowercase() }?.let { return it }
        }
        val kernelVal = readSysfs("digital_filter")
        if (!kernelVal.isNullOrBlank()) {
            DigitalFilter.values().firstOrNull { it.id == kernelVal.lowercase() }?.let { return it }
        }
        val cr = ctx.contentResolver
        val raw = Settings.Global.getString(cr, "vendor.audio.hiby.hw.digital_filter")
            ?: Settings.Global.getString(cr, "vendor.audio.hiby.digital_filter")
            ?: Settings.Global.getString(cr, "hw.digital_filter")
            ?: ""
        return DigitalFilter.values().firstOrNull { it.id == raw.trim().lowercase() } ?: DigitalFilter.FAST_LINEAR
    }

    suspend fun setDigitalFilter(ctx: Context, filter: DigitalFilter) = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        runCatching { Settings.Global.putString(cr, "vendor.audio.hiby.hw.digital_filter", filter.id) }
        runCatching { Settings.Global.putString(cr, "vendor.audio.hiby.digital_filter", filter.id) }
        runCatching { Settings.Global.putString(cr, "hw.digital_filter", filter.id) }

        // The HAL parameter is the route that actually lands on this hardware. (The old
        // echo-to-sysfs / setprop line ran through su, which does not exist on HioneOS: the
        // Settings row that [getDigitalFilter] reads back changed while the DAC did not.)
        HioneDirectAudio.pushToHal(ctx, "vendor.audio.hiby.hw.digital_filter", filter.id)
        HioneDirectAudio.pushToHal(ctx, "vendor.audio.hiby.digital_filter", filter.id)
        // Neither line above reaches the DAC: the framework turns the hw.* key into a property
        // nothing reads, and the HAL ignores the other. The node is written by init when
        // vendor.audio.hiby.digital_filter (no "hw") changes, which only system_server may set.
        // UNVERIFIED on hardware - see com.hione.player.profiles.HibyDacBridge / DspKnob.FILTER.
        com.hione.player.profiles.HibyDacBridge.init(ctx)
        com.hione.player.profiles.HibyDacBridge.set(com.hione.player.profiles.HibyDacBridge.PROP_FILTER, filter.id)
    }

    fun getGainMode(ctx: Context): GainMode {
        com.hione.player.profiles.HibyDacBridge.get(com.hione.player.profiles.HibyDacBridge.PROP_GAIN)?.let { v ->
            GainMode.values().firstOrNull { it.sysfsValue == v.lowercase() }?.let { return it }
        }
        val kernelVal = readSysfs("gain")
        if (!kernelVal.isNullOrBlank()) {
            GainMode.values().firstOrNull { it.sysfsValue == kernelVal.lowercase() }?.let { return it }
        }
        val cr = ctx.contentResolver
        val g = Settings.Global.getString(cr, "vendor.audio.hiby.hw.gain")
            ?: Settings.Global.getString(cr, "vendor.audio.hiby.gain") ?: ""
        return GainMode.values().firstOrNull { it.sysfsValue == g.trim().lowercase() } ?: GainMode.HIGH
    }

    suspend fun setGainMode(ctx: Context, gain: GainMode) = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        // Explicit user choice: the best-audio enforcer re-applies this instead of forcing HIGH.
        HioneDirectAudio.rememberUserGain(ctx, gain.sysfsValue)
        runCatching { Settings.Global.putString(cr, "vendor.audio.hiby.hw.gain", gain.sysfsValue) }
        runCatching { Settings.Global.putString(cr, "vendor.audio.hiby.gain", gain.sysfsValue) }
        com.hione.player.profiles.HibyDacBridge.init(ctx)
        com.hione.player.profiles.HibyDacBridge.set(com.hione.player.profiles.HibyDacBridge.PROP_GAIN, gain.sysfsValue)

        // Apply it for real. The old path shelled out to su (echo > sysfs / setprop) which is a
        // guaranteed no-op on HioneOS (no root) - the Settings rows changed but the DAC stayed on
        // whatever gain it booted with (found live 2026-09-11: hw.gain=low while the UI said
        // otherwise, IEMs quiet). The HAL accepts these as parameters on the platform key.
        HioneDirectAudio.pushToHal(ctx, "vendor.audio.hiby.hw.gain", gain.sysfsValue)
        HioneDirectAudio.pushToHal(ctx, "vendor.audio.hiby.gain", gain.sysfsValue)
    }

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

    fun isDreEnabled(ctx: Context): Boolean {
        com.hione.player.profiles.HibyDacBridge.get(com.hione.player.profiles.HibyDacBridge.PROP_DRE)?.let { return it == "dremode_enable" }
        val kernelVal = readSysfs("dre_mode")
        if (kernelVal != null) return kernelVal == "dremode_enable" || kernelVal == "1" || kernelVal.equals("on", true)
        val cr = ctx.contentResolver
        return try { Settings.Global.getInt(cr, "vendor.audio.hiby.hw.dre", 0) == 1 } catch (_: Throwable) { false }
    }

    suspend fun setDreEnabled(ctx: Context, enabled: Boolean) = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        HioneDirectAudio.rememberUserDre(ctx, enabled)
        val v = if (enabled) 1 else 0
        val sysfsStr = if (enabled) "dremode_enable" else "dremode_disable"
        runCatching { Settings.Global.putInt(cr, "vendor.audio.hiby.hw.dre", v) }
        // Root-free route that actually applies (the old su/sysfs line was a no-op on HioneOS), so the
        // toggle's state matches the DAC instead of only matching a Settings row.
        HioneDirectAudio.pushToHal(ctx, "vendor.audio.hiby.hw.dre", v.toString())
        HioneDirectAudio.pushToHal(ctx, "vendor.audio.hiby.hw.dre_mode", sysfsStr)
        // The real switch (init -> sa_sound_setting/dre_mode). The Global row is what HiBy's
        // AudioService re-applies at boot; the bridge applies it now. See HibyDacBridge.
        runCatching { Settings.Global.putString(cr, "vendor.audio.hiby.dre_mode", sysfsStr) }
        com.hione.player.profiles.HibyDacBridge.init(ctx)
        com.hione.player.profiles.HibyDacBridge.set(com.hione.player.profiles.HibyDacBridge.PROP_DRE, sysfsStr)
    }

    fun isHighPowerEnabled(ctx: Context): Boolean {
        com.hione.player.profiles.HibyDacBridge.get(com.hione.player.profiles.HibyDacBridge.PROP_HIGH_POWER)?.let { return it == "hpower_enable" }
        val kernelVal = readSysfs("high_power_mode")
        if (kernelVal != null) return kernelVal == "hpower_enable" || kernelVal == "1" || kernelVal.equals("on", true)
        val cr = ctx.contentResolver
        return try { Settings.Global.getInt(cr, "vendor.audio.hiby.hw.high_power", 0) == 1 } catch (_: Throwable) { false }
    }

    suspend fun setHighPowerEnabled(ctx: Context, enabled: Boolean) = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        HioneDirectAudio.rememberUserHighPower(ctx, enabled)
        val v = if (enabled) 1 else 0
        val sysfsStr = if (enabled) "hpower_enable" else "hpower_disable"
        runCatching { Settings.Global.putInt(cr, "vendor.audio.hiby.hw.high_power", v) }
        HioneDirectAudio.pushToHal(ctx, "vendor.audio.hiby.hw.high_power", v.toString())
        HioneDirectAudio.pushToHal(ctx, "vendor.audio.hiby.hw.high_power_mode", sysfsStr)
        // The real switch (init -> sa_sound_setting/high_power_mode), live now and at boot.
        runCatching { Settings.Global.putString(cr, "vendor.audio.hiby.high_power", sysfsStr) }
        com.hione.player.profiles.HibyDacBridge.init(ctx)
        com.hione.player.profiles.HibyDacBridge.set(com.hione.player.profiles.HibyDacBridge.PROP_HIGH_POWER, sysfsStr)
    }

    fun getDsdGainCompensate(ctx: Context): Boolean {
        val cr = ctx.contentResolver
        return try { Settings.Global.getInt(cr, "vendor.audio.hiby.hw.dsd_gain_comp", 1) == 1 } catch (_: Throwable) { true }
    }

    suspend fun setDsdGainCompensate(ctx: Context, enabled: Boolean) = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        val v = if (enabled) 1 else 0
        runCatching { Settings.Global.putInt(cr, "vendor.audio.hiby.hw.dsd_gain_comp", v) }
        HioneDirectAudio.pushToHal(ctx, "vendor.audio.hiby.hw.dsd_gain_comp", v.toString())
    }

    /** Read a system property root-free (platform-signed app, android.os.SystemProperties). */
    private fun sysProp(key: String): String? = runCatching {
        val c = Class.forName("android.os.SystemProperties")
        (c.getMethod("get", String::class.java, String::class.java).invoke(null, key, "") as String)
            .trim().takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** Read one `vendor.audio.hiby.*` value out of Settings.Global. */
    private fun vendorSetting(ctx: Context, key: String): String? = runCatching {
        Settings.Global.getString(ctx.contentResolver, "vendor.audio.hiby.$key")?.trim()
            ?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /**
     * Reads whatever the DAC actually exposes right now. Nodes that cannot be read come back null
     * (rendered "—"); nothing is substituted. The turbo property is read through SystemProperties
     * rather than a `getprop` shell-out, which needed su and therefore always failed on HioneOS.
     *
     * TWO SOURCES, in order. The sysfs nodes under sa_sound_setting are the closest thing to
     * ground truth, but SELinux denies them to platform_app on this device (avc: denied { read }
     * ... scontext=platform_app tcontext=sysfs) and no amount of signing changes that, because the
     * policy keys on the domain. That is why this panel showed nothing but dashes.
     *
     * The same state is mirrored into `Settings.Global` under `vendor.audio.hiby.*`, and that is
     * not a consolation prize: it is the namespace HiBy's own audio HAL reads and writes, so it is
     * what the hardware is actually being told to do. Falling back to it is a real measurement of
     * a real value, not a substituted "typical" one, which is the thing the no-fake-data rule
     * forbids. [source] records which one answered so the UI never claims to be reading the kernel
     * when it is reading settings.
     */
    fun getLiveHardwareAudit(ctx: Context? = null): HardwareAuditState {
        val filter = readSysfs("digital_filter")
        val gain = readSysfs("gain")
        val hp = readSysfs("high_power_mode")
        val dre = readSysfs("dre_mode")
        val turbo = sysProp("vendor.audio.hiby.hw.audio_turbo")
        val out = readSysfs("out_mode")
        val bal = readSysfs("lr_balance")
        val sysfsOk = listOf(filter, gain, hp, dre, out, bal).any { it != null }
        if (sysfsOk || ctx == null) {
            return HardwareAuditState(
                kernelFilter = filter, kernelGain = gain, kernelHighPower = hp, kernelDre = dre,
                kernelTurbo = turbo, kernelOutput = out, kernelBalance = bal,
                isSysfsReadable = sysfsOk,
                source = if (sysfsOk) HardwareAuditState.Source.SYSFS else HardwareAuditState.Source.NONE
            )
        }
        // The bridge's persist properties first: what the DAC was last told.
        val bridge = com.hione.player.profiles.HibyDacBridge
        val sFilter = bridge.get(bridge.PROP_FILTER) ?: vendorSetting(ctx, "digital_filter") ?: vendorSetting(ctx, "hw.digital_filter")
        val sGain = bridge.get(bridge.PROP_GAIN) ?: vendorSetting(ctx, "gain") ?: vendorSetting(ctx, "hw.gain")
        val sHp = bridge.get(bridge.PROP_HIGH_POWER) ?: vendorSetting(ctx, "high_power_mode") ?: vendorSetting(ctx, "high_power")
        val sDre = bridge.get(bridge.PROP_DRE) ?: vendorSetting(ctx, "dre_mode")
        val sOut = vendorSetting(ctx, "hw.bal_po_lo_switch")
        val sBal = vendorSetting(ctx, "hw.balance")
        val any = listOf(sFilter, sGain, sHp, sDre, sOut, sBal).any { it != null }
        return HardwareAuditState(
            kernelFilter = sFilter, kernelGain = sGain, kernelHighPower = sHp, kernelDre = sDre,
            kernelTurbo = turbo, kernelOutput = sOut, kernelBalance = sBal,
            isSysfsReadable = false,
            source = if (any) HardwareAuditState.Source.VENDOR_SETTINGS else HardwareAuditState.Source.NONE
        )
    }
}
