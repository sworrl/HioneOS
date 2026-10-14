package com.hione.sysbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * The DAC controls that actually reach hardware on the M500, on behalf of Hione Music.
 *
 * Mapped from the stock v1.00 firmware (hioneos/docs/hiby-audio-knobs.md): of the 31 nodes in
 * /sys/devices/platform/sa_sound_setting only four change anything on this board: high power
 * (the external amp stage), DRE, the CS43198 PCM filter and a digital output offset. Stock
 * reaches them through init triggers on vendor.audio.hiby.*, fed at boot by a system service
 * that skips any device not named "M500", which HioneOS is not. Nothing HioneOS did reached them.
 *
 * Here: persist.vendor.audio.hione.<knob> (vendor_audio_prop, which system_app may set). The
 * image's hione_audio.rc writes the node on each change and again at every boot, because persist
 * properties re-fire their triggers. Only the listed values are accepted. In particular
 * dac_type is never written: its store reports 0 bytes and loops any writer.
 *
 * Extras (each optional): high_power, dre_mode, digital_filter, gain.
 */
class AudioReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_AUDIO) return
        for ((extra, allowed) in KNOBS) {
            val v = intent.getStringExtra(extra) ?: continue
            if (v !in allowed) { Log.w(TAG, "refused $extra=$v (allowed: $allowed)"); continue }
            val ok = runCatching {
                Class.forName("android.os.SystemProperties").getMethod("set", String::class.java, String::class.java)
                    .invoke(null, "persist.vendor.audio.hione.$extra", v)
            }
            Log.i(TAG, "dac $extra=$v -> ${if (ok.isSuccess) "ok" else ok.exceptionOrNull()?.cause ?: ok.exceptionOrNull()}")
        }
        publishState(context)
    }

    companion object {
        const val TAG = "HioneSysBridge"

        /**
         * Settings.Global row every DAC UI observes (status bar badge, Hardware app, SystemUI tiles,
         * Hione Music). Built from the persist properties read back, so it holds what was applied,
         * not what was asked for. Format: "filter=nos;gain=high;dre=dremode_enable;hp=hpower_enable",
         * a value is empty when that knob was never set.
         */
        const val GLOBAL_DAC_STATE = "hione_dac_state"

        private fun prop(key: String): String = runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
                .invoke(null, key) as String
        }.getOrDefault("").trim()

        fun publishState(context: Context) {
            val state = "filter=${prop("persist.vendor.audio.hione.digital_filter")};" +
                "gain=${prop("persist.vendor.audio.hione.gain")};" +
                "dre=${prop("persist.vendor.audio.hione.dre_mode")};" +
                "hp=${prop("persist.vendor.audio.hione.high_power")}"
            val ok = runCatching {
                android.provider.Settings.Global.putString(context.contentResolver, GLOBAL_DAC_STATE, state)
            }
            Log.i(TAG, "$GLOBAL_DAC_STATE=$state -> ${if (ok.isSuccess) "ok" else ok.exceptionOrNull()}")
        }

        const val ACTION_AUDIO = "com.hione.sysbridge.AUDIO"
        val KNOBS: Map<String, Set<String>> = mapOf(
            "high_power" to setOf("hpower_enable", "hpower_disable"),
            "dre_mode" to setOf("dremode_enable", "dremode_disable"),
            "digital_filter" to setOf(
                "fast_rolloff_low_latency", "fast_rolloff_phase_compensated",
                "slow_rolloff_low_latency", "slow_rolloff_phase_compensated",
                // Real since the image patches the codec driver's filter mask (0xC0 -> 0xE0).
                "nos",
            ),
            // Digital offset on the 3.5 and 4.4 mm outputs: low -12 dB, middle -6 dB, high 0 dB.
            "gain" to setOf("low", "middle", "high"),
        )
    }
}
