package com.hione.launcher.weather

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Force a fresh position, from outside the UI.
 *
 *   adb shell am broadcast -a com.hione.launcher.action.REFRESH_LOCATION -p com.hione.launcher
 *
 * Exists because the only way to re-fix used to be the hourly tick or a tap in the weather
 * observatory, which is no use when the question is "why does it still think I am in Colorado".
 * Logs the provider it came from and the distance moved, so a fix can be checked rather than
 * assumed.
 */
class HioneLocationReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION = "com.hione.launcher.action.REFRESH_LOCATION"
        private const val TAG = "HioneLocationReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        // Nothing here may throw: an exported receiver that throws takes the launcher down with it.
        try {
            if (intent.action != ACTION) return
            val app = context.applicationContext
            Log.i(TAG, "manual location refresh requested")
            HioneWeatherService.refreshLocationNow(app)
            HioneWeatherService.refreshWeather(app)
        } catch (t: Throwable) {
            Log.w(TAG, "refresh failed: ${t.message}")
        }
    }
}
