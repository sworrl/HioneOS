package com.hione.settings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        // Exported for the system's boot broadcasts. Another app can still name this receiver
        // with some other action, so only the manifest's actions get through.
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Unit
            else -> return
        }
        Log.i("HioneOS_Settings", "Boot received, initializing HioneOS hardware & settings")
        // Dev builds only: a release build (ro.hione.release=1) leaves adb and developer options
        // to the user.
        val release = runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
                .invoke(null, "ro.hione.release") as String
        }.getOrDefault("") == "1"
        if (!release) try {
            android.provider.Settings.Global.putInt(context.contentResolver, android.provider.Settings.Global.ADB_ENABLED, 1)
            android.provider.Settings.Global.putInt(context.contentResolver, android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1)
            android.provider.Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 1)
        } catch (_: Throwable) {}

        // Root HAL Daemon initialization (dev only; no su on HioneOS, so this is a no-op anyway)
        if (!release) RootShell.execFast(
            "settings put global adb_enabled 1; " +
            "settings put global development_settings_enabled 1; " +
            "settings put global adb_wifi_enabled 1; " +
            "setprop persist.sys.usb.config mtp,adb; " +
            "setprop service.adb.tcp.port 5555; " +
            "setprop persist.service.adb.enable 1; " +
            "stop adbd 2>/dev/null; start adbd 2>/dev/null"
        )

        // Settings parity: claim the settings intents we tie with stock on (see PreferredRouting),
        // and keep a boot-time HOME placeholder available if stock FallbackHome is gone.
        if (intent?.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            val app = context.applicationContext
            val pending = goAsync()
            Thread {
                try {
                    com.hione.settings.home.HioneFallbackHome.syncWithStock(app)
                    com.hione.settings.route.PreferredRouting.claim(app)
                } catch (t: Throwable) {
                    Log.w("HioneOS_Settings", "settings routing setup failed", t)
                } finally { pending.finish() }
            }.start()
        }

    }
}

