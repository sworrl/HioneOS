package com.hione.settings.home

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.UserManager
import android.util.Log

/**
 * Stand-in for stock Settings' FallbackHome.
 *
 * On a file-encrypted device the real launcher cannot start until the user's storage is
 * unlocked, so the system needs a direct-boot-aware HOME activity to show in the meantime.
 * Stock Settings provides one (priority -1000). If stock Settings is removed and nothing else
 * provides it, boot has no home to show until unlock. This activity is shipped DISABLED and
 * [syncWithStock] enables it only when the stock one is gone, so the two never compete
 * (two equal HOME candidates would put a chooser on the boot screen).
 */
class HioneFallbackHome : Activity() {
    private var receiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(android.graphics.Color.BLACK)
        if (getSystemService(UserManager::class.java).isUserUnlocked) { finish(); return }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { finish() }
        }
        androidx.core.content.ContextCompat.registerReceiver(this, receiver!!, IntentFilter(Intent.ACTION_USER_UNLOCKED), androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onDestroy() {
        receiver?.let { runCatching { unregisterReceiver(it) } }
        super.onDestroy()
    }

    @Deprecated("Back does nothing on the boot placeholder")
    override fun onBackPressed() {}

    companion object {
        private const val STOCK = "com.android.settings"
        private const val STOCK_CLASS = "com.android.settings.FallbackHome"

        /** Enable ours only when stock FallbackHome is missing or disabled. Takes effect next boot. */
        fun syncWithStock(ctx: Context) {
            val pm = ctx.packageManager
            val stockOk = try {
                val ai = pm.getActivityInfo(ComponentName(STOCK, STOCK_CLASS), PackageManager.MATCH_DIRECT_BOOT_AWARE or PackageManager.MATCH_DIRECT_BOOT_UNAWARE)
                ai.enabled && ai.applicationInfo.enabled &&
                    pm.getComponentEnabledSetting(ComponentName(STOCK, STOCK_CLASS)).let {
                        it != PackageManager.COMPONENT_ENABLED_STATE_DISABLED && it != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    }
            } catch (_: Throwable) { false }
            val ours = ComponentName(ctx, HioneFallbackHome::class.java)
            val want = if (stockOk) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT else PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            if (pm.getComponentEnabledSetting(ours) != want) {
                runCatching { pm.setComponentEnabledSetting(ours, want, PackageManager.DONT_KILL_APP) }
                    .onFailure { Log.w("HioneSettings", "fallback home toggle failed: ${it.message}") }
                Log.i("HioneSettings", "fallback home ${if (stockOk) "left to stock Settings" else "enabled (stock one missing)"}")
            }
        }
    }
}
