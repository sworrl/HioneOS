package com.hione.player.profiles

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * A "listening profile": the headphone (or speaker) you are listening on, the settings that suit
 * it, and the changes you made to those settings.
 *
 * The shape follows what the user asked for literally. [recommended] is what we ship for that
 * hardware; [overrides] holds ONLY the fields the user changed away from it; the settings that
 * actually get applied are [effective] = recommended ⊕ overrides. Keeping the two apart is what
 * makes "reset to recommended" a matter of dropping the overrides, and what lets a later data
 * refresh (a new AutoEq measurement, say) reach every field the user never touched.
 */
data class ListeningProfile(
    val id: String,
    val name: String,
    val builtIn: Boolean,
    val hardware: Hardware,
    val recommended: DspSettings,
    val overrides: DspSettings = DspSettings(),
    val autoSwitch: List<AutoSwitchRule> = emptyList(),
    val source: ProfileSource = ProfileSource(),
) {
    val effective: DspSettings get() = recommended.overlay(overrides)
    val hasOverrides: Boolean get() = !overrides.isEmpty()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("builtIn", builtIn)
        put("hardware", hardware.toJson())
        put("recommended", recommended.toJson())
        put("overrides", overrides.toJson())
        put("autoSwitch", JSONArray().apply { autoSwitch.forEach { put(it.toJson()) } })
        put("source", source.toJson())
    }

    companion object {
        fun fromJson(o: JSONObject): ListeningProfile = ListeningProfile(
            id = o.getString("id"),
            name = o.optString("name", o.getString("id")),
            builtIn = o.optBoolean("builtIn", false),
            hardware = Hardware.fromJson(o.optJSONObject("hardware") ?: JSONObject()),
            recommended = DspSettings.fromJson(o.optJSONObject("recommended")),
            overrides = DspSettings.fromJson(o.optJSONObject("overrides")),
            autoSwitch = o.optJSONArray("autoSwitch").objects().mapNotNull { AutoSwitchRule.fromJson(it) },
            source = ProfileSource.fromJson(o.optJSONObject("source")),
        )
    }
}

enum class HardwareType(val id: String, val label: String) {
    IEM("iem", "IEM"),
    EARBUD("earbud", "Earbud"),
    TWS("tws", "True wireless"),
    OVER_EAR("over-ear", "Over-ear"),
    ON_EAR("on-ear", "On-ear"),
    /** A car or stereo aux input, an external amp: line level, no headphone curve. */
    LINE_OUT("line-out", "Line out / car aux"),
    SPEAKER("speaker", "Speaker"),
    OTHER("other", "Other");

    companion object {
        fun of(id: String?): HardwareType = entries.firstOrNull { it.id == id } ?: OTHER
    }
}

/** How the hardware plugs in. WIRED means "either jack": the M500 cannot tell a headphone apart. */
enum class Connection(val id: String, val label: String) {
    WIRED("wired", "Wired"),
    WIRED_35("wired-3.5", "3.5 mm"),
    WIRED_44("wired-4.4", "4.4 mm balanced"),
    BLUETOOTH("bluetooth", "Bluetooth"),
    WIRED_AND_BLUETOOTH("wired+bluetooth", "Wired or Bluetooth"),
    USB("usb", "USB");

    val isWired: Boolean get() = this == WIRED || this == WIRED_35 || this == WIRED_44 || this == WIRED_AND_BLUETOOTH
    val isBluetooth: Boolean get() = this == BLUETOOTH || this == WIRED_AND_BLUETOOTH

    companion object {
        fun of(id: String?): Connection = entries.firstOrNull { it.id == id } ?: WIRED
    }
}

data class Hardware(
    val brand: String,
    val model: String,
    val type: HardwareType = HardwareType.OTHER,
    val connection: Connection = Connection.WIRED,
    val impedanceOhm: Double? = null,
    /** As published. Check [sensitivityUnit]: dB/mW and dB/V differ by 10·log10(1000/Z). */
    val sensitivity: Double? = null,
    val sensitivityUnit: String? = null,
    val discontinued: Boolean? = null,
) {
    val displayName: String get() = if (model.startsWith(brand, ignoreCase = true)) model else "$brand $model"

    fun specLine(): String = buildList {
        add(type.label)
        add(connection.label)
        impedanceOhm?.let { add("${fmt(it)} Ω") }
        if (sensitivity != null) add("${fmt(sensitivity)} ${sensitivityUnit ?: "dB"}")
        if (discontinued == true) add("discontinued")
    }.joinToString(" · ")

    fun toJson(): JSONObject = JSONObject().apply {
        put("brand", brand)
        put("model", model)
        put("type", type.id)
        put("connection", connection.id)
        impedanceOhm?.let { put("impedanceOhm", it) }
        sensitivity?.let { put("sensitivity", it) }
        sensitivityUnit?.let { put("sensitivityUnit", it) }
        discontinued?.let { put("discontinued", it) }
    }

    companion object {
        fun fromJson(o: JSONObject) = Hardware(
            brand = o.optString("brand", ""),
            model = o.optString("model", ""),
            type = HardwareType.of(o.optString("type", null)),
            connection = Connection.of(o.optString("connection", null)),
            impedanceOhm = o.optDoubleOrNull("impedanceOhm"),
            sensitivity = o.optDoubleOrNull("sensitivity"),
            sensitivityUnit = o.optStringOrNull("sensitivityUnit"),
            discontinued = if (o.has("discontinued") && !o.isNull("discontinued")) o.optBoolean("discontinued") else null,
        )
    }
}

/** Where a built-in profile's numbers came from, shown in the UI so nothing looks invented. */
data class ProfileSource(
    /** e.g. "AutoEq · oratory1990", or null when there is no measurement. */
    val eq: String? = null,
    val eqUrl: String? = null,
    val spec: String? = null,
    /** Plain-language reason for the recommended DAC settings (the rule that produced them). */
    val rationale: String? = null,
    val notes: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        eq?.let { put("eq", it) }
        eqUrl?.let { put("eqUrl", it) }
        spec?.let { put("spec", it) }
        rationale?.let { put("rationale", it) }
        notes?.let { put("notes", it) }
    }

    companion object {
        fun fromJson(o: JSONObject?) = if (o == null) ProfileSource() else ProfileSource(
            eq = o.optStringOrNull("eq"),
            eqUrl = o.optStringOrNull("eqUrl"),
            spec = o.optStringOrNull("spec"),
            rationale = o.optStringOrNull("rationale"),
            notes = o.optStringOrNull("notes"),
        )
    }
}

enum class EqFilterType(val code: String, val label: String) {
    PEAKING("PK", "Peak"),
    LOW_SHELF("LSC", "Low shelf"),
    HIGH_SHELF("HSC", "High shelf");

    companion object {
        /** AutoEq writes LSC/HSC; other tools write LS/HS/LSQ/HSQ for the same RBJ shelves. */
        fun of(code: String?): EqFilterType = when (code?.uppercase()) {
            "LSC", "LS", "LSQ", "LOW_SHELF" -> LOW_SHELF
            "HSC", "HS", "HSQ", "HIGH_SHELF" -> HIGH_SHELF
            else -> PEAKING
        }
    }
}

data class EqBand(
    val type: EqFilterType,
    val freqHz: Double,
    val gainDb: Double,
    val q: Double,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type.code); put("fc", freqHz); put("gain", gainDb); put("q", q)
    }

    companion object {
        fun fromJson(o: JSONObject) = EqBand(
            type = EqFilterType.of(o.optString("type", "PK")),
            freqHz = o.optDouble("fc", 1000.0),
            gainDb = o.optDouble("gain", 0.0),
            q = o.optDouble("q", 0.707).let { if (it.isNaN() || it <= 0.0) 0.707 else it },
        )
    }
}

/** The EQ curve without its on/off switch (that is [DspSettings.eqEnabled], overridable alone). */
data class EqCurve(
    val preampDb: Double = 0.0,
    val bands: List<EqBand> = emptyList(),
) {
    /** True when the curve, applied, would change nothing: every gain and the preamp are 0 dB. */
    val isFlat: Boolean get() = abs(preampDb) < 1e-9 && bands.all { abs(it.gainDb) < 1e-9 }

    fun toJson(): JSONObject = JSONObject().apply {
        put("preamp", preampDb)
        put("bands", JSONArray().apply { bands.forEach { put(it.toJson()) } })
    }

    fun sameAs(other: EqCurve?): Boolean {
        if (other == null) return false
        if (abs(preampDb - other.preampDb) > 0.005 || bands.size != other.bands.size) return false
        return bands.zip(other.bands).all { (a, b) ->
            a.type == b.type && abs(a.freqHz - b.freqHz) < 0.05 && abs(a.gainDb - b.gainDb) < 0.005 && abs(a.q - b.q) < 0.0005
        }
    }

    companion object {
        val FLAT = EqCurve()
        fun fromJson(o: JSONObject?): EqCurve? = o?.let {
            EqCurve(
                preampDb = it.optDouble("preamp", 0.0),
                bands = it.optJSONArray("bands").objects().map { b -> EqBand.fromJson(b) },
            )
        }
    }
}

/**
 * Every knob a profile can carry. Each field is nullable and null means "this profile does not
 * set it" — in [ListeningProfile.recommended] that leaves the device as it is (a Bluetooth
 * profile sets no DAC knobs, because the internal DAC is not in that signal path), and in
 * [ListeningProfile.overrides] it means "not changed by the user".
 *
 * How much of this actually reaches hardware on the M500 is NOT uniform; see [DspKnob] for the
 * per-knob finding. Unverified knobs are carried anyway so a profile survives a firmware that
 * does honour them, but the UI labels them.
 */
data class DspSettings(
    /** CirrusLogicManager.DigitalFilter.id. UNVERIFIED on hardware: see [DspKnob.FILTER]. */
    val digitalFilter: String? = null,
    /** CirrusLogicManager.GainMode.sysfsValue ("low"/"high"). UNVERIFIED: see [DspKnob.GAIN]. */
    val gain: String? = null,
    /** HiBy "High Power" output. Has a real path (init → sysfs); see [DspKnob.HIGH_POWER]. */
    val highPower: Boolean? = null,
    /** CS43198 Dynamic Range Enhancement. Has a real path; see [DspKnob.DRE]. */
    val dre: Boolean? = null,
    /** DSD gain compensation. NOT WIRED on this firmware; see [DspKnob.DSD_GAIN_COMP]. */
    val dsdGainComp: Boolean? = null,
    val eqEnabled: Boolean? = null,
    val eq: EqCurve? = null,
) {
    fun isEmpty(): Boolean = digitalFilter == null && gain == null && highPower == null && dre == null &&
        dsdGainComp == null && eqEnabled == null && eq == null

    /** recommended ⊕ overrides: every non-null field of [top] wins. */
    fun overlay(top: DspSettings): DspSettings = DspSettings(
        digitalFilter = top.digitalFilter ?: digitalFilter,
        gain = top.gain ?: gain,
        highPower = top.highPower ?: highPower,
        dre = top.dre ?: dre,
        dsdGainComp = top.dsdGainComp ?: dsdGainComp,
        eqEnabled = top.eqEnabled ?: eqEnabled,
        eq = top.eq ?: eq,
    )

    /**
     * The fields of [this] (a live snapshot) that differ from [base] (the recommended settings) —
     * i.e. exactly what "save my current settings as my defaults for this hardware" must store.
     * A field the snapshot could not read (null) is never recorded as a change.
     */
    fun diffAgainst(base: DspSettings): DspSettings = DspSettings(
        digitalFilter = digitalFilter?.takeIf { it != base.digitalFilter },
        gain = gain?.takeIf { it != base.gain },
        highPower = highPower?.takeIf { it != base.highPower },
        dre = dre?.takeIf { it != base.dre },
        dsdGainComp = dsdGainComp?.takeIf { it != base.dsdGainComp },
        eqEnabled = eqEnabled?.takeIf { it != (base.eqEnabled ?: false) },
        eq = eq?.takeIf { !it.sameAs(base.eq ?: EqCurve.FLAT) },
    )

    /** EQ that would actually be heard: on, and not flat. */
    val eqAudible: Boolean get() = eqEnabled == true && eq != null && !eq.isFlat

    fun toJson(): JSONObject = JSONObject().apply {
        digitalFilter?.let { put("digitalFilter", it) }
        gain?.let { put("gain", it) }
        highPower?.let { put("highPower", it) }
        dre?.let { put("dre", it) }
        dsdGainComp?.let { put("dsdGainComp", it) }
        eqEnabled?.let { put("eqEnabled", it) }
        eq?.let { put("eq", it.toJson()) }
    }

    companion object {
        fun fromJson(o: JSONObject?): DspSettings = if (o == null) DspSettings() else DspSettings(
            digitalFilter = o.optStringOrNull("digitalFilter"),
            gain = o.optStringOrNull("gain"),
            highPower = o.optBoolOrNull("highPower"),
            dre = o.optBoolOrNull("dre"),
            dsdGainComp = o.optBoolOrNull("dsdGainComp"),
            eqEnabled = o.optBoolOrNull("eqEnabled"),
            eq = EqCurve.fromJson(o.optJSONObject("eq")),
        )
    }
}

/**
 * What each DAC knob is worth on this firmware, from the reverse-engineering done for this
 * feature (2026-10-09, against m500-system-archive: vendor init rc, audio.primary.bengal.so, the
 * framework AudioManager/AudioService, and the vendor_dlkm kernel modules).
 *
 * The only things on the vendor side that move the DAC are three init property triggers in
 * /vendor/etc/init/hw/init.hiby.audio.rc:
 *     vendor.audio.hiby.high_power=*      → /sys/devices/platform/sa_sound_setting/high_power_mode
 *     vendor.audio.hiby.dre_mode=*        → .../dre_mode
 *     vendor.audio.hiby.digital_filter=*  → .../digital_filter
 * The audio HAL contains no "hiby" string at all, so AudioManager.setParameters keys that the
 * framework does not intercept go nowhere. Platform apps cannot set vendor_audio_prop under
 * SELinux; HiBy's AudioService.setProperties (system_server, no permission check) can, which is
 * the route [HibyDacBridge] takes.
 */
enum class DspKnob(val label: String, val truth: Truth, val finding: String) {
    FILTER(
        "Digital filter", Truth.UNVERIFIED,
        "Written to the init-triggered property through AudioService, which reaches the sysfs node. " +
            "Not yet confirmed by ear or by reading the node back. The CS43198 kernel driver only names " +
            "the four roll-off filters; the string \"nos\" does not appear in it, so NOS may be ignored."
    ),
    GAIN(
        "Gain", Truth.UNVERIFIED,
        "The framework turns this into the property vendor.audio.hiby.hw.gain, but nothing on this " +
            "firmware reads that property: no init trigger, nothing in the audio HAL. The kernel has a " +
            "gain node that nothing writes. On the M500 the real output-level switch is High Power."
    ),
    HIGH_POWER(
        "High power", Truth.PATH_FOUND,
        "init writes sa_sound_setting/high_power_mode when vendor.audio.hiby.high_power changes, and " +
            "AudioService restores it at boot from Settings.Global. This is the stock Low/High Power switch."
    ),
    DRE(
        "DRE", Truth.PATH_FOUND,
        "init writes sa_sound_setting/dre_mode when vendor.audio.hiby.dre_mode changes; restored at boot " +
            "the same way as High Power."
    ),
    DSD_GAIN_COMP(
        "DSD gain compensation", Truth.NOT_WIRED,
        "The key this app writes (vendor.audio.hiby.hw.dsd_gain_comp) is read by nothing, and even the " +
            "framework's own dsd_compensate property has no consumer on the M500. Kept so profiles " +
            "carry it, but it does nothing here."
    );

    enum class Truth(val badge: String) { PATH_FOUND("REAL PATH"), UNVERIFIED("UNVERIFIED"), NOT_WIRED("NO EFFECT") }
}

/** How a profile claims an output when it connects. */
data class AutoSwitchRule(val kind: Kind, val value: String) {
    enum class Kind(val id: String, val label: String) {
        BT_NAME("bt-name", "Bluetooth name"),
        BT_ADDRESS("bt-address", "Bluetooth address"),
        USB_NAME("usb-name", "USB device"),
        /** Manual pin: wired jacks cannot identify the headphone, so the user says which port. */
        WIRED_PORT("wired-port", "Wired port");

        companion object {
            fun of(id: String?) = entries.firstOrNull { it.id == id }
        }
    }

    val label: String get() = "${kind.label}: $value"

    fun toJson(): JSONObject = JSONObject().apply { put("kind", kind.id); put("value", value) }

    companion object {
        fun fromJson(o: JSONObject): AutoSwitchRule? {
            val k = Kind.of(o.optString("kind", null)) ?: return null
            val v = o.optString("value", "").trim()
            return if (v.isEmpty()) null else AutoSwitchRule(k, v)
        }
    }
}

// ---------------------------------------------------------------------------------- JSON helpers

internal fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optJSONObject(it) }
}

internal fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null

internal fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null

internal fun JSONObject.optBoolOrNull(key: String): Boolean? =
    if (has(key) && !isNull(key)) optBoolean(key) else null

internal fun fmt(v: Double): String =
    if (abs(v - Math.rint(v)) < 1e-9) v.toLong().toString() else String.format(java.util.Locale.US, "%.1f", v)
