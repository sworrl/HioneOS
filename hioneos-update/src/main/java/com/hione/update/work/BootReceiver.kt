package com.hione.update.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Nothing launches Hione Update on a fresh image, so without this the daily check would only start
 * once someone opened the app. Boot (and our own update) starts the process; schedule() is
 * idempotent.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                runCatching { UpdateWorker.schedule(context.applicationContext) }
                // After a restart into (or back from) a new system slot: report how it went.
                if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
                    runCatching { com.hione.update.ota.AbUpdater.afterBoot(context.applicationContext) }
                }
            }
        }
    }
}
