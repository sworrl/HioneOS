package com.hione.sysbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Second pass of [BootSeeds] at boot. The first pass runs when the persistent process starts.
 * LOCKED_BOOT_COMPLETED is only delivered to direct-boot-aware components, which this one is not,
 * so in practice BOOT_COMPLETED is what arrives. It is listed so the filter matches the stock
 * receivers' pattern if the package ever becomes direct-boot aware.
 */
class BootSeedsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                val pending = goAsync()
                val app = context.applicationContext
                Thread({
                    try {
                        BootSeeds.apply(app, intent.action!!.substringAfterLast('.'))
                        // Again here: an image with hione_led.rc switches the light off at boot_completed.
                        LedReceiver.restore(app)
                    } finally { pending.finish() }
                }, "HioneBootSeeds").start()
            }
        }
    }
}
