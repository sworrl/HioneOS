package com.hione.player

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Native Hione Clock Widget with Cyberpunk typography, date formatting, and primary app launcher.
 */
class HioneClockWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(context, mgr, it) }
    }

    companion object {
        fun pushUpdate(context: Context) {
            val app = context.applicationContext
            val mgr = AppWidgetManager.getInstance(app)
            val ids = mgr.getAppWidgetIds(ComponentName(app, HioneClockWidget::class.java))
            ids.forEach { render(app, mgr, it) }
        }

        private fun render(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_clock)
            val now = Date()

            val timeFormat = SimpleDateFormat("hh:mm", Locale.getDefault())
            val ampmFormat = SimpleDateFormat("a", Locale.getDefault())
            val dateFormat = SimpleDateFormat("EEEE, MMM d", Locale.getDefault())

            v.setTextViewText(R.id.widget_clock_time, timeFormat.format(now))
            v.setTextViewText(R.id.widget_clock_ampm, ampmFormat.format(now).uppercase(Locale.getDefault()))
            v.setTextViewText(R.id.widget_clock_date, dateFormat.format(now).uppercase(Locale.getDefault()))

            val openAppIntent = Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val openPending = PendingIntent.getActivity(
                ctx, 2001, openAppIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            v.setOnClickPendingIntent(R.id.widget_clock_root, openPending)

            mgr.updateAppWidget(id, v)
        }
    }
}
