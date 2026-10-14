package com.hione.sysbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * The front status light (SGM31324), on behalf of Hione Music. The player decides what to show
 * (HionePulse there); this receiver checks each value against a fixed list and sets the property.
 *
 * Init drives the light. HiBy's /vendor/etc/init/hw/init.hiby.led.rc writes led_pattern whenever
 * vendor.audio.hiby.hw.led, .hw.sample_quality, .charging or .chargetype change. Those properties
 * are vendor_audio_prop, which system_app may set and platform_app may not, so the player asks here.
 *
 * Extras, each optional, applied in this order:
 *   beat     20 hex digits: the chip's registers 0 to 9, set as vendor.audio.hione.led_beat. The
 *            image's hione_led_beat.rc (HIONEOS_LED_BPM=1) writes it to write_pattern while
 *            sample_quality is "beat", the light is on and the battery is not charging.
 *   quality  vendor.audio.hiby.hw.sample_quality: none, low, standard, high, dsd, mqa, mqastudio,
 *            mqb (HiBy's patterns 1 to 5 and 8 to 10), or beat, which only images with
 *            hione_led_beat.rc accept (ro.vendor.audio.hione.led_beat=1).
 *   led      vendor.audio.hiby.hw.led: on or off. Kept in Settings.Global hione_status_light and
 *            applied again at every boot, because HiBy's rc switches the light on at each boot.
 *
 * Charging keeps priority on its own: every format rule, HiBy's and ours, needs charging=no, and
 * the charging rules do not look at sample_quality.
 */
class LedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_LED) return
        intent.getStringExtra(EXTRA_BEAT)?.let { raw ->
            val v = raw.trim().lowercase()
            if (validBeat(v)) set(PROP_BEAT, v) else Log.w(TAG, "refused beat=$raw")
        }
        intent.getStringExtra(EXTRA_QUALITY)?.let { v ->
            when {
                v !in QUALITIES -> Log.w(TAG, "refused quality=$v (allowed: $QUALITIES)")
                v == "beat" && get(PROP_BEAT_RC) != "1" -> Log.w(TAG, "refused quality=beat: this image has no hione_led_beat.rc")
                else -> set(PROP_QUALITY, v)
            }
        }
        intent.getStringExtra(EXTRA_LED)?.let { v ->
            if (v != "on" && v != "off") { Log.w(TAG, "refused led=$v"); return@let }
            runCatching { Settings.Global.putString(context.contentResolver, GLOBAL_SWITCH, v) }
            set(PROP_LED, v)
        }
    }

    companion object {
        const val TAG = "HioneSysBridge"
        const val ACTION_LED = "com.hione.sysbridge.LED"
        const val EXTRA_BEAT = "beat"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_LED = "led"

        const val PROP_LED = "vendor.audio.hiby.hw.led"
        const val PROP_QUALITY = "vendor.audio.hiby.hw.sample_quality"
        const val PROP_BEAT = "vendor.audio.hione.led_beat"
        /** Set to 1 by hione_led_beat.rc at boot. */
        const val PROP_BEAT_RC = "ro.vendor.audio.hione.led_beat"
        /** The last switch the player asked for, "on" or "off". Unset = the image default. */
        const val GLOBAL_SWITCH = "hione_status_light"

        val QUALITIES = setOf("none", "low", "standard", "high", "dsd", "mqa", "mqastudio", "mqb", "beat")

        /**
         * Ten register bytes as hex. Reg 0 bit 7 is the chip's test mode and must stay 0; reg 9
         * bits 2:1 must read 11 (datasheet). Everything else is the chip's normal range.
         */
        fun validBeat(v: String): Boolean {
            if (v.length != 20 || v.any { it !in '0'..'9' && it !in 'a'..'f' }) return false
            val reg0 = v.substring(0, 2).toInt(16)
            val reg9 = v.substring(18, 20).toInt(16)
            return (reg0 and 0x80) == 0 && (reg9 and 0x06) == 0x06
        }

        /** Put back the switch the player last asked for. Run at process start and at boot. */
        fun restore(context: Context) {
            val v = runCatching { Settings.Global.getString(context.contentResolver, GLOBAL_SWITCH) }.getOrNull()
            if (v == "on" || v == "off") set(PROP_LED, v)
        }

        private fun get(key: String): String = runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
                .invoke(null, key) as String
        }.getOrDefault("")

        /** Skips a value that is already set, so a repeat costs no init trigger and no I2C traffic. */
        private fun set(key: String, value: String) {
            if (get(key) == value) return
            val r = runCatching {
                Class.forName("android.os.SystemProperties").getMethod("set", String::class.java, String::class.java)
                    .invoke(null, key, value)
            }
            Log.i(TAG, "led $key=$value -> ${if (r.isSuccess) "ok" else r.exceptionOrNull()?.cause ?: r.exceptionOrNull()}")
        }
    }
}
