package com.hione.launcher.widget

import com.hione.launcher.Mascot
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.BatteryManager
import android.widget.RemoteViews
import com.hione.launcher.R

/**
 * Battery status as an individual system App Widget — a placeable, resizable version of the
 * launcher's in-bar battery badge. Reads the current level/charge from the sticky
 * ACTION_BATTERY_CHANGED broadcast on render, so it needs no running service. Tapping opens the
 * launcher (its battery observatory). Refreshed on the widget's own schedule, on battery/charge
 * changes (via [pushUpdate] from the launcher), and on the standard widget update tick.
 *
 * Reference implementation for the Hione badge-widget family: RemoteViews (matching the existing
 * HioneClockWidget pattern), shared hione_badge_widget_bg, badge-default sizing, live on-render read.
 */
class HioneBatteryWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(context, mgr, it) }
    }

    companion object {
        /** Re-render all live instances. Call from the launcher when battery/charge changes. */
        fun pushUpdate(context: Context) {
            val app = context.applicationContext
            val mgr = AppWidgetManager.getInstance(app)
            val ids = mgr.getAppWidgetIds(ComponentName(app, HioneBatteryWidget::class.java))
            ids.forEach { render(app, mgr, it) }
        }

        private fun render(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_hione_battery)

            val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = b?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            // -1 = the sticky battery broadcast was unavailable; rendered as "--%", never 100%.
            val pct = if (level >= 0 && scale > 0) (level * 100) / scale else -1

            // Hione palette: teal when healthy/charging, amber low, red critical.
            val tint = when {
                pct < 0 -> 0x80FFFFFF.toInt()
                charging -> 0xFF00E676.toInt()
                pct <= 15 -> 0xFFFF5252.toInt()
                pct <= 35 -> 0xFFFFD600.toInt()
                else -> Mascot.argb(0xFFFFAB2E.toInt())
            }
            val pctText = if (pct < 0) "--%" else "$pct%"
            v.setTextViewText(R.id.widget_battery_text, if (charging) "$pctText CHG" else pctText)
            v.setTextColor(R.id.widget_battery_text, if (pct in 0..15) tint else Mascot.argb(0xFFFFE0B0.toInt()))
            v.setInt(R.id.widget_battery_icon, "setColorFilter", tint)

            v.setOnClickPendingIntent(R.id.widget_battery_root, launchLauncher(ctx))
            mgr.updateAppWidget(id, v)
        }

        /** Open the HioneOS launcher (home) — the badge's tap target lives there. */
        internal fun launchLauncher(ctx: Context): PendingIntent {
            val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
                ?.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP }
                ?: Intent()
            return PendingIntent.getActivity(
                ctx, 7001, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
    }
}
