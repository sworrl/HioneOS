package com.m500.hardware

import android.content.Context
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Controls for the HiBy M500's dual CS43198 DAC.
 *
 * Filter, gain, DRE and high power are the only sa_sound_setting nodes that change hardware on
 * this firmware. They go through [DacBridge] (com.hione.sysbridge). The other controls here
 * (output mode, balance, turbo, DSD compensation) are not real knobs on the M500: the driver
 * stubs them out (hioneos/docs/hiby-audio-knobs.md, section 3.2). Their writes are left as they
 * were and do nothing.
 */
object CirrusLogicManager {
    private const val TAG = "CirrusLogicManager"
    const val SYSFS_BASE = "/sys/devices/platform/sa_sound_setting"

    enum class DigitalFilter(val id: String, val label: String, val description: String) {
        FAST_LINEAR("fast_rolloff_phase_compensated", "Fast Roll-off, Phase Compensated", "Linear phase. The standard reference filter"),
        FAST_MINIMUM("fast_rolloff_low_latency", "Fast Roll-off, Low Latency", "Minimum phase with low group delay"),
        SLOW_LINEAR("slow_rolloff_phase_compensated", "Slow Roll-off, Phase Compensated", "Linear phase with a gentler roll-off"),
        SLOW_MINIMUM("slow_rolloff_low_latency", "Slow Roll-off, Low Latency", "Minimum phase, gentle roll-off, little pre-ringing"),
        NOS("nos", "Non-Oversampling (NOS)", "Skips the DAC's digital oversampling filter")
    }

    enum class GainMode(val id: String, val label: String, val description: String) {
        LOW("low", "Low (-12 dB)", "Digital offset of -12 dB on the 3.5 and 4.4 mm outputs. More volume steps for sensitive IEMs. Same noise floor."),
        HIGH("high", "High (0 dB)", "No digital offset. For louder output use High power, which switches in the external amp stage.")
    }

    enum class OutputMode(val id: String, val label: String, val description: String) {
        HEADPHONE_OUT("bal_po", "Headphone Out (PO)", "Variable output with volume control for 3.5mm SE & 4.4mm BAL"),
        LINE_OUT("bal_lo", "Line Out (LO)", "Fixed line level for an external amp")
    }

    /**
     * Raw kernel sysfs readings. A field is null when that node could NOT be read (missing node,
     * SELinux denial, empty) — never substituted with a guess. [isHardwareSynced] is true only
     * when every node was actually read; [readNodes] / [totalNodes] say how many were.
     */
    data class HardwareAuditState(
        val kernelFilter: String? = null,
        val kernelGain: String? = null,
        val kernelHighPower: String? = null,
        val kernelDre: String? = null,
        val kernelTurbo: String? = null,
        val kernelOutput: String? = null,
        val kernelBalance: String? = null,
        val readNodes: Int = 0,
        val totalNodes: Int = 7
    ) {
        val isHardwareSynced: Boolean get() = readNodes == totalNodes
        /** True when nothing at all could be read from the kernel — state is unknown. */
        val isUnknown: Boolean get() = readNodes == 0
    }

    /** True when the DAC sysfs directory exists and at least one control node is readable. */
    fun isSysfsReachable(): Boolean = try {
        val dir = File(SYSFS_BASE)
        dir.isDirectory && (dir.listFiles()?.any { it.canRead() } == true)
    } catch (_: Throwable) { false }

    /** Nothing to set up. The DAC knobs go through [DacBridge], which needs no sysfs access. */
    fun init(ctx: Context) {}

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
        val rootOut = RootShell.execOut("cat $SYSFS_BASE/$node")?.trim()
        if (!rootOut.isNullOrBlank()) return rootOut
        unreadableNodes.add(node)
        return null
    }

    // ---- The four knobs that move hardware: filter, gain, DRE, high power ----
    // Writes go through com.hione.sysbridge (DacBridge). Reads come from
    // persist.vendor.audio.hione.*, the same values Hione Music and the SystemUI tiles show. The
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
        return GainMode.values().firstOrNull { it.id == raw.lowercase() }
    }

    /** Gain only. High power is its own switch, as in Hione Music. */
    suspend fun setGainMode(ctx: Context, gain: GainMode): DacBridge.Result = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        runCatching { Settings.Global.putString(cr, KEY_USER_GAIN, gain.id) }
        runCatching { Settings.Global.putString(cr, "vendor.audio.hiby.hw.gain", gain.id) }
        runCatching { Settings.Global.putString(cr, "vendor.audio.hiby.gain", gain.id) }
        DacBridge.set(ctx, DacBridge.GAIN, gain.id)
    }

    /** Persist property first, then the Settings.Global rows. Null = nothing says. */
    fun isDreEnabled(ctx: Context): Boolean? {
        DacBridge.get(DacBridge.DRE)?.let { return it == "dremode_enable" }
        val raw = globalString(ctx, "vendor.audio.hiby.dre_mode") ?: return null
        return raw == "dremode_enable"
    }

    suspend fun setDreEnabled(ctx: Context, enabled: Boolean): DacBridge.Result = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        val cmd = if (enabled) "dremode_enable" else "dremode_disable"
        runCatching { Settings.Global.putInt(cr, KEY_USER_DRE, if (enabled) 1 else 0) }
        runCatching { Settings.Global.putString(cr, "vendor.audio.hiby.dre_mode", cmd) }
        runCatching { Settings.Global.putInt(cr, "vendor.audio.hiby.hw.dre", if (enabled) 1 else 0) }
        DacBridge.set(ctx, DacBridge.DRE, cmd)
    }

    /** Persist property first, then the Settings.Global rows. Null = nothing says. */
    fun isHighPowerEnabled(ctx: Context): Boolean? {
        DacBridge.get(DacBridge.HIGH_POWER)?.let { return it == "hpower_enable" }
        val raw = globalString(ctx, "vendor.audio.hiby.high_power", "vendor.audio.hiby.high_power_mode") ?: return null
        return raw == "hpower_enable"
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
                val am = ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                val safe = (am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) * 0.3f).toInt().coerceAtLeast(1)
                if (am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) > safe) {
                    am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, safe, 0)
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

    fun isTurboEnabled(ctx: Context): Boolean {
        val kernelVal = readSysfs("turbo")
        if (!kernelVal.isNullOrBlank()) {
            return kernelVal == "1" || kernelVal.contains("on") || kernelVal.contains("enable")
        }
        val cr = ctx.contentResolver
        val raw = Settings.Global.getInt(cr, "vendor.audio.hiby.hw.turbo", 0)
        return raw == 1
    }

    suspend fun setTurboEnabled(ctx: Context, enabled: Boolean) = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        val strVal = if (enabled) "on" else "off"
        val intVal = if (enabled) 1 else 0

        runCatching { Settings.Global.putInt(cr, "vendor.audio.hiby.hw.turbo", intVal) }

        RootShell.execFast(
            "echo ${if (enabled) "1" else "0"} > $SYSFS_BASE/turbo; " +
            "settings put global vendor.audio.hiby.hw.turbo $intVal; " +
            "setprop vendor.audio.hiby.hw.turbo $strVal"
        )
        Log.i(TAG, "Applied Turbo Mode: $strVal")
    }

    /** Kernel sysfs first, then the HiBy Settings.Global keys. Null = not set anywhere (never guessed). */
    fun getDsdGainCompensate(ctx: Context): Int? {
        val kernelVal = readSysfs("dsd_compensate") ?: readSysfs("dac_dsd_gain")
        if (!kernelVal.isNullOrBlank()) {
            kernelVal.toIntOrNull()?.let { return it }
        }
        val cr = ctx.contentResolver
        return Settings.Global.getString(cr, "vendor.audio.hiby.hw.dac_dsd_gain")?.trim()?.toIntOrNull()
            ?: Settings.Global.getString(cr, "vendor.audio.hiby.dsd_compensate")?.trim()?.toIntOrNull()
    }

    suspend fun setDsdGainCompensate(ctx: Context, db: Int) = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        val safe = db.coerceIn(0, 6)

        runCatching { Settings.Global.putInt(cr, "vendor.audio.hiby.hw.dac_dsd_gain", safe) }
        runCatching { Settings.Global.putInt(cr, "vendor.audio.hiby.dsd_compensate", safe) }

        RootShell.execFast(
            "echo $safe > $SYSFS_BASE/dsd_compensate; " +
            "echo $safe > $SYSFS_BASE/dac_dsd_gain; " +
            "settings put global vendor.audio.hiby.hw.dac_dsd_gain $safe; " +
            "settings put global vendor.audio.hiby.dsd_compensate $safe; " +
            "setprop vendor.audio.hiby.hw.dac_dsd_gain $safe; " +
            "setprop vendor.audio.hiby.dsd_compensate $safe"
        )
        Log.i(TAG, "Applied DSD Gain Compensation: ${safe}dB")
    }

    /** Kernel sysfs first, then the HiBy Settings.Global keys. Null = no real source says (never guessed). */
    fun getOutputMode(ctx: Context): OutputMode? {
        val kernelVal = readSysfs("bal_po_lo_switch") ?: readSysfs("po_lo_switch")
        if (!kernelVal.isNullOrBlank()) {
            return if (kernelVal.contains("lo")) OutputMode.LINE_OUT else OutputMode.HEADPHONE_OUT
        }
        val cr = ctx.contentResolver
        val raw = Settings.Global.getString(cr, "vendor.audio.hiby.hw.bal_po_lo_switch")
            ?: Settings.Global.getString(cr, "vendor.audio.hiby.bal_po_lo_switch") ?: return null
        return if (raw.contains("lo")) OutputMode.LINE_OUT else OutputMode.HEADPHONE_OUT
    }

    suspend fun setOutputMode(ctx: Context, mode: OutputMode) = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver

        runCatching { Settings.Global.putString(cr, "vendor.audio.hiby.hw.bal_po_lo_switch", mode.id) }
        runCatching { Settings.Global.putString(cr, "vendor.audio.hiby.bal_po_lo_switch", mode.id) }

        RootShell.execFast(
            "echo ${mode.id} > $SYSFS_BASE/bal_po_lo_switch; " +
            "echo ${if (mode == OutputMode.LINE_OUT) "lo" else "po"} > $SYSFS_BASE/po_lo_switch; " +
            "settings put global vendor.audio.hiby.hw.bal_po_lo_switch ${mode.id}; " +
            "settings put global vendor.audio.hiby.bal_po_lo_switch ${mode.id}; " +
            "setprop vendor.audio.hiby.hw.bal_po_lo_switch ${mode.id}; " +
            "setprop vendor.audio.hiby.bal_po_lo_switch ${mode.id}"
        )
        Log.i(TAG, "Applied Output Routing: ${mode.id}")
    }

    /**
     * Null when NO real source reports a balance. [getBalance] falls back to 0 so callers that need
     * a number have one, but display surfaces must use this: an unreadable node was being printed
     * as "Center" next to an enabled slider, i.e. an unknown shown as a measurement.
     */
    fun getBalanceOrNull(ctx: Context): Int? {
        readSysfs("lrbalance")?.toIntOrNull()?.let { return it }
        val cr = ctx.contentResolver
        return try {
            Settings.Global.getString(cr, "vendor.audio.hiby.hw.lrbalance")?.trim()?.toIntOrNull()
                ?: Settings.Global.getString(cr, "vendor.audio.hiby.lrbalance")?.trim()?.toIntOrNull()
        } catch (_: Throwable) { null }
    }

    fun getBalance(ctx: Context): Int {
        val kernelVal = readSysfs("lrbalance")
        if (!kernelVal.isNullOrBlank()) {
            kernelVal.toIntOrNull()?.let { return it }
        }
        val cr = ctx.contentResolver
        return Settings.Global.getInt(cr, "vendor.audio.hiby.hw.lrbalance",
            Settings.Global.getInt(cr, "vendor.audio.hiby.lrbalance", 0))
    }

    suspend fun setBalance(ctx: Context, balance: Int) = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        val safe = balance.coerceIn(-10, 10)

        runCatching { Settings.Global.putInt(cr, "vendor.audio.hiby.hw.lrbalance", safe) }
        runCatching { Settings.Global.putInt(cr, "vendor.audio.hiby.lrbalance", safe) }

        RootShell.execFast(
            "echo $safe > $SYSFS_BASE/lrbalance; " +
            "settings put global vendor.audio.hiby.hw.lrbalance $safe; " +
            "settings put global vendor.audio.hiby.lrbalance $safe; " +
            "setprop vendor.audio.hiby.hw.lrbalance $safe; " +
            "setprop vendor.audio.hiby.lrbalance $safe"
        )
        Log.i(TAG, "Applied L/R Balance: $safe")
    }

    /**
     * Reads the live hardware state directly from kernel sysfs for truthful verification in the UI.
     * Nodes that cannot be read stay null (previously they were silently replaced by defaults and
     * the result was always flagged "synced" — a fabricated audit).
     */
    fun getLiveHardwareAudit(): HardwareAuditState {
        // The four live knobs cannot be read from sysfs by an app. The persist property is what
        // the hardware was last told.
        val kFilter = DacBridge.get(DacBridge.FILTER)
        val kGain = DacBridge.get(DacBridge.GAIN)
        val kHp = DacBridge.get(DacBridge.HIGH_POWER)
        val kDre = DacBridge.get(DacBridge.DRE)
        val kTurbo = readSysfs("turbo")
        val kOutput = readSysfs("bal_po_lo_switch") ?: readSysfs("po_lo_switch")
        val kBal = readSysfs("lrbalance")
        val read = listOf(kFilter, kGain, kHp, kDre, kTurbo, kOutput, kBal).count { it != null }

        return HardwareAuditState(
            kernelFilter = kFilter,
            kernelGain = kGain,
            kernelHighPower = kHp,
            kernelDre = kDre,
            kernelTurbo = kTurbo,
            kernelOutput = kOutput,
            kernelBalance = kBal,
            readNodes = read,
            totalNodes = 7
        )
    }
}
