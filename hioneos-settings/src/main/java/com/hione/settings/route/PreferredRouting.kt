package com.hione.settings.route

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.util.Log

/**
 * Makes HioneSettings the answer for settings intents where the stock app ties with it.
 *
 * Why this exists: PackageManager drops android:priority to 0 for apps that are not in a
 * priv-app directory (ComponentResolver.adjustPriority, checked in this device's services.jar),
 * and HioneSettings ships in /system/app. Where the stock filter is also 0, the two tie and the
 * system shows a chooser. A preferred-activity entry is the same thing the chooser's "Always"
 * button writes, so registering one per tied action removes the chooser.
 *
 * What it cannot fix: stock filters at priority 1 (most of them) still beat our 0. Only moving
 * HioneSettings to /system/priv-app (so our priority 10 counts) or disabling the stock component
 * changes that; see hioneos/docs/settings-parity.md.
 *
 * Needs SET_PREFERRED_APPLICATIONS (signature), which platform signing grants.
 */
object PreferredRouting {
    private const val TAG = "HioneSettingsRouting"

    data class Outcome(val claimed: Int, val stockWins: Int, val alreadyOurs: Int, val failed: Int)

    fun claim(ctx: Context): Outcome {
        val pm = ctx.packageManager
        val ours = ComponentName(ctx, SettingsRouterActivity::class.java).packageName
        var claimed = 0; var stockWins = 0; var mine = 0; var failed = 0
        for (key in StockMap.byAction.keys) {
            val parts = key.split('|')
            if ("mime" in parts) continue            // typed data (APN editor, documents): left to the resolver
            val action = parts[0]
            val scheme = parts.getOrNull(1)
            val probe = Intent(action).addCategory(Intent.CATEGORY_DEFAULT)
            if (scheme != null) probe.data = Uri.fromParts(scheme, "com.hione.settings", null)
            val all: List<ResolveInfo> = try {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY or PackageManager.GET_RESOLVED_FILTER)
            } catch (t: Throwable) { failed++; continue }
            if (all.size < 2) { if (all.firstOrNull()?.activityInfo?.packageName == ours) mine++; continue }
            val top = all.maxOf { it.priority }
            val best = all.filter { it.priority == top }
            val mineInfo = best.firstOrNull { it.activityInfo.packageName == ours }
            if (mineInfo == null) { stockWins++; continue }
            if (best.size == 1) { mine++; continue }
            try {
                val filter = IntentFilter(action).apply {
                    addCategory(Intent.CATEGORY_DEFAULT)
                    if (scheme != null) addDataScheme(scheme)
                }
                val match = if (scheme != null) IntentFilter.MATCH_CATEGORY_SCHEME else IntentFilter.MATCH_CATEGORY_EMPTY
                val set = best.map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }.toTypedArray()
                val target = ComponentName(ours, mineInfo.activityInfo.name)
                @Suppress("DEPRECATION")
                pm.addPreferredActivity(filter, match, set, target)
                claimed++
            } catch (t: Throwable) {
                failed++
                Log.w(TAG, "preferred $action failed: ${t.message}")
            }
        }
        val o = Outcome(claimed, stockWins, mine, failed)
        Log.i(TAG, "settings intent routing: $o")
        return o
    }
}
