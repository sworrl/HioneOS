package com.hione.launcher.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Bench/dev WiFi provisioning over adb — joins a network without touching the device:
 *
 *   adb shell am broadcast -a com.hione.launcher.WIFI_JOIN \
 *       --es ssid "My Network" --es psk "password" [--es sec WPA2|WPA3|Open] \
 *       com.hione.launcher
 *
 * The credential goes through the same [HioneNetworkService.connectToNetwork] path as the
 * Network Observatory UI (vault-persisted + autonomous auto-rejoin), so one broadcast makes
 * the network permanent. The manifest gates this receiver behind WRITE_SECURE_SETTINGS,
 * which on-device only shell (adb) and system hold — a sideloaded app can't feed us WiFi.
 */
class HioneDevWifiReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_WIFI_JOIN) return
        val ssid = intent.getStringExtra("ssid")?.trim().orEmpty()
        if (ssid.isEmpty()) {
            Log.w(TAG, "WIFI_JOIN without ssid extra — ignored")
            return
        }
        val psk = intent.getStringExtra("psk").orEmpty()
        val sec = intent.getStringExtra("sec")
            ?: if (psk.isEmpty()) "Open Network" else "WPA2 Personal"
        Log.i(TAG, "WIFI_JOIN: ssid=$ssid sec=$sec (psk ${if (psk.isEmpty()) "absent" else "provided"})")
        HioneNetworkService.connectToNetwork(context.applicationContext, ssid, psk, sec)
    }

    companion object {
        private const val TAG = "HioneDevWifiReceiver"
        const val ACTION_WIFI_JOIN = "com.hione.launcher.WIFI_JOIN"
    }
}
