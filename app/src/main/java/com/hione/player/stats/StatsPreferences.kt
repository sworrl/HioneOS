package com.hione.player.stats

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * User switches for the per-listen stats database. Plain SharedPreferences — nothing here is a
 * secret. Location logging defaults ON only when the coarse-location permission is actually
 * granted (the user was explicit that location must be opt-in-by-permission and battery-smart);
 * until the user touches the toggle it simply tracks the permission state.
 */
object StatsPreferences {
    private const val FILE = "hione_listen_stats_prefs"
    private const val KEY_STATS_ENABLED = "stats_enabled"
    private const val KEY_LOCATION_ENABLED = "location_enabled"
    private const val KEY_LAST_EXPORT_AT = "last_export_at"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun hasCoarseLocationPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun isStatsEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_STATS_ENABLED, true)
    fun setStatsEnabled(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean(KEY_STATS_ENABLED, on).apply() }

    /** True only if the user hasn't turned it off AND the permission is granted right now — a
     *  revoked permission silently degrades to "no location", never to a crash or a prompt. */
    fun isLocationEnabled(ctx: Context): Boolean {
        val granted = hasCoarseLocationPermission(ctx)
        val p = prefs(ctx)
        val userChoice = if (p.contains(KEY_LOCATION_ENABLED)) p.getBoolean(KEY_LOCATION_ENABLED, granted) else granted
        return userChoice && granted
    }
    fun setLocationEnabled(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean(KEY_LOCATION_ENABLED, on).apply() }
    fun hasExplicitLocationChoice(ctx: Context): Boolean = prefs(ctx).contains(KEY_LOCATION_ENABLED)

    fun lastExportAt(ctx: Context): Long = prefs(ctx).getLong(KEY_LAST_EXPORT_AT, 0L)
    fun setLastExportAt(ctx: Context, at: Long) { prefs(ctx).edit().putLong(KEY_LAST_EXPORT_AT, at).apply() }
}
