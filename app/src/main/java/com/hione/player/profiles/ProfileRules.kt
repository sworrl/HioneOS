package com.hione.player.profiles

import kotlin.math.log10
import kotlin.math.pow

/**
 * The rule that turns a headphone's published impedance and sensitivity into recommended DAC
 * settings. It is duplicated, deliberately and line for line, in
 * m500/tools/profiles/gen_listening_profiles.py, which bakes the built-ins; this copy exists for
 * custom profiles the user creates on the device. Change both or neither.
 *
 * THE RULE. Work out the voltage the headphone needs for 110 dB SPL peaks (loud listening with
 * headroom), from its sensitivity in dB SPL per volt:
 *     dB/V = dB/mW + 10·log10(1000 / Z)          (when the maker quotes dB/mW)
 *     Vneeded = 10^((110 − dB/V) / 20)
 *  - Vneeded < 0.5 V   → Low gain, High Power off  (nearly every IEM; sensitive portables)
 *  - 0.5 V to < 1.5 V  → High gain, High Power off (most 32–80 Ω full-size)
 *  - ≥ 1.5 V           → High gain, High Power on  (HD 600/650/800 class)
 * Without a sensitivity figure: IEMs and earbuds → Low/off; other wired headphones → by
 * impedance (≥ 150 Ω High/on, ≥ 50 Ω High/off, else Low/off).
 * Line out / car aux → Low/off: aux inputs expect consumer line level (roughly 1 to 2 V at
 * most), so the extra swing of High Power only risks clipping the input. Custom profiles only;
 * the built-ins have no line-out type, so gen_listening_profiles.py needs no copy of this line.
 * Bluetooth-only hardware gets no DAC settings at all: the M500's DAC is not in that signal path.
 *
 * Filter: fast roll-off, phase compensated (linear phase; flattest passband, the CS43198
 * default in this app). NOS and the slow filters are taste, so they are the user's to choose.
 * DRE: on, matching the app's standing best-audio policy (HioneDirectAudio.ensureBestAudio).
 * DSD gain compensation: not set (it does nothing on this firmware; see DspKnob).
 */
object ProfileRules {
    const val FILTER_DEFAULT = "fast_rolloff_phase_compensated"

    fun sensitivityDbPerVolt(hw: Hardware): Double? {
        val s = hw.sensitivity ?: return null
        return when (hw.sensitivityUnit?.lowercase()) {
            "db/v" -> s
            "db/mw" -> hw.impedanceOhm?.takeIf { it > 0 }?.let { z -> s + 10 * log10(1000.0 / z) }
            else -> null
        }
    }

    fun voltsFor110dB(hw: Hardware): Double? = sensitivityDbPerVolt(hw)?.let { 10.0.pow((110.0 - it) / 20.0) }

    /** [eq] is the measured correction when there is one; null ships flat with EQ off. */
    fun recommendedFor(hw: Hardware, eq: EqCurve?): DspSettings {
        val eqPart = if (eq != null && !eq.isFlat) DspSettings(eqEnabled = true, eq = eq)
        else DspSettings(eqEnabled = false, eq = EqCurve.FLAT)
        if (!hw.connection.isWired) return eqPart
        val (gain, highPower) = driveFor(hw)
        return eqPart.copy(
            digitalFilter = FILTER_DEFAULT,
            gain = gain,
            highPower = highPower,
            dre = true,
        )
    }

    private fun driveFor(hw: Hardware): Pair<String, Boolean> {
        if (hw.type == HardwareType.LINE_OUT) return "low" to false
        val v = voltsFor110dB(hw)
        if (v != null) return when {
            v < 0.5 -> "low" to false
            v < 1.5 -> "high" to false
            else -> "high" to true
        }
        if (hw.type == HardwareType.IEM || hw.type == HardwareType.EARBUD || hw.type == HardwareType.TWS) return "low" to false
        val z = hw.impedanceOhm ?: return "high" to false
        return when {
            z >= 150 -> "high" to true
            z >= 50 -> "high" to false
            else -> "low" to false
        }
    }

    fun rationale(hw: Hardware): String {
        if (!hw.connection.isWired) return "Bluetooth: the M500's DAC is not in this signal path, so only the EQ applies."
        if (hw.type == HardwareType.LINE_OUT) return "Line out into an aux input: low gain and High Power off. Aux inputs expect about 1 to 2 V at most. Set the M500 volume once and use the car's or amp's volume."
        val v = voltsFor110dB(hw)
        val (gain, hp) = driveFor(hw)
        val setting = "$gain gain and High Power ${if (hp) "on" else "off"}."
        return if (v != null) String.format(java.util.Locale.US, "Needs about %.2f V to reach 110 dB SPL peaks: %s", v, setting)
        else "No sensitivity spec. Going by type and impedance: $setting"
    }
}
