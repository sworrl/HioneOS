package com.m500.hardware

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootCompletedReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootCompletedReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            Log.i(TAG, "M500 Hardware Service booted: initializing hardware HAL, Pulsar LED & Pocket Lock")
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    Runtime.getRuntime().exec(arrayOf("su", "-c", "chmod 666 /sys/class/input/input*/inhibited /sys/devices/platform/soc/soc:gpio_keys_hiby/disabled_keys /sys/devices/platform/soc/soc:hiby,sound-plat/mute /sys/class/leds/sgm31324-leds/*")).waitFor()
                } catch (_: Throwable) {}


                // Ambient brightness service is deliberately NOT started here.
                // A14 stamps while-in-use camera eligibility on the service's
                // FIRST start, and a BOOT_COMPLETED start is ineligible forever
                // (verified live: even later TOP-context re-starts could not
                // upgrade the running process). The HioneOS launcher pokes the
                // service from onResume (TOP = eligible), and the baked boot
                // exec starts it from shell context (allow-listed = eligible).
            }
        }
    }
}
