package com.hione.launcher.usb

import android.content.Context
import android.content.SharedPreferences

object HioneUsbPreferences {
    private const val PREFS_NAME = "hione_usb_prefs"
    private const val KEY_SUPPRESS_UNTIL = "suppress_until_ms"
    private const val KEY_REMEMBER_FOREVER = "remember_forever"
    private const val KEY_PREFERRED_MODE = "preferred_mode"

    const val MODE_DAC = "dac"
    const val MODE_MTP = "mtp"
    const val MODE_CHARGE = "charge"

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun shouldShowPrompt(context: Context): Boolean {
        val prefs = getPrefs(context)
        if (prefs.getBoolean(KEY_REMEMBER_FOREVER, false)) return false
        val suppressUntil = prefs.getLong(KEY_SUPPRESS_UNTIL, 0L)
        return System.currentTimeMillis() > suppressUntil
    }

    fun getSavedMode(context: Context): String =
        getPrefs(context).getString(KEY_PREFERRED_MODE, MODE_MTP) ?: MODE_MTP

    fun savePreference(context: Context, mode: String, dismissOption: DismissOption) {
        val prefs = getPrefs(context).edit()
        prefs.putString(KEY_PREFERRED_MODE, mode)
        val now = System.currentTimeMillis()

        when (dismissOption) {
            DismissOption.ONCE -> {
                prefs.putBoolean(KEY_REMEMBER_FOREVER, false)
                prefs.putLong(KEY_SUPPRESS_UNTIL, 0L)
            }
            DismissOption.HOURS_24 -> {
                prefs.putBoolean(KEY_REMEMBER_FOREVER, false)
                prefs.putLong(KEY_SUPPRESS_UNTIL, now + (24 * 3600 * 1000L))
            }
            DismissOption.DAYS_7 -> {
                prefs.putBoolean(KEY_REMEMBER_FOREVER, false)
                prefs.putLong(KEY_SUPPRESS_UNTIL, now + (7 * 24 * 3600 * 1000L))
            }
            DismissOption.FOREVER -> {
                prefs.putBoolean(KEY_REMEMBER_FOREVER, true)
                prefs.putLong(KEY_SUPPRESS_UNTIL, Long.MAX_VALUE)
            }
        }
        prefs.apply()
    }

    enum class DismissOption(val label: String) {
        ONCE("Ask next time"),
        HOURS_24("Don't ask for 24 hours"),
        DAYS_7("Don't ask for 7 days"),
        FOREVER("Always remember on this PC")
    }
}
