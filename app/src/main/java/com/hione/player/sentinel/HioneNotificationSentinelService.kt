package com.hione.player.sentinel

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Oki Hione Notification Sentinel Service.
 * Automatically intercepts and dismisses intrusive carrier activation nags,
 * IMS VoLTE errors on data-only SIMs, and Google Fi setup prompts on the M500 DAP.
 */
class HioneNotificationSentinelService : NotificationListenerService() {
    companion object {
        private const val TAG = "HioneNotifSentinel"
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Notification Sentinel connected. Cleaning active nag notifications...")
        cleanActiveNags()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (isNagNotification(sbn)) {
            Log.i(TAG, "Dismissing nag notification from ${sbn.packageName}")
            try {
                cancelNotification(sbn.key)
            } catch (_: Throwable) {}
        }
    }

    private fun cleanActiveNags() {
        try {
            val active = activeNotifications ?: return
            for (sbn in active) {
                if (isNagNotification(sbn)) {
                    cancelNotification(sbn.key)
                }
            }
        } catch (_: Throwable) {}
    }

    private fun isNagNotification(sbn: StatusBarNotification): Boolean {
        val pkg = sbn.packageName.lowercase()
        val notif = sbn.notification ?: return false
        val extras = notif.extras
        val title = (extras?.getCharSequence(Notification.EXTRA_TITLE) ?: "").toString().lowercase()
        val text = (extras?.getCharSequence(Notification.EXTRA_TEXT) ?: "").toString().lowercase()
        val bigText = (extras?.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: "").toString().lowercase()
        val combined = "$pkg $title $text $bigText"

        return combined.contains("google fi") ||
                combined.contains("org.codeaurora.ims") ||
                combined.contains("carrierdefaultapp") ||
                combined.contains("managedprovisioning") ||
                combined.contains("sim not provisioned") ||
                combined.contains("finish setting up") ||
                combined.contains("finish activating") ||
                combined.contains("carrier setup") ||
                (pkg.contains("phone") && combined.contains("activate"))
    }
}
