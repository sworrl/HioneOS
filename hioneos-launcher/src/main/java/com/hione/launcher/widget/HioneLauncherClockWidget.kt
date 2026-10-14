package com.hione.launcher.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import com.hione.launcher.R

/**
 * Clock badge as an individual system App Widget. The layout's TextClock views tick on their own
 * (no polling, no service), so the provider only has to wire the tap target. Named
 * HioneLauncherClockWidget because the app module already ships a HioneClockWidget.
 */
class HioneLauncherClockWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(context, mgr, it) }
    }

    companion object {
        fun pushUpdate(context: Context) {
            val app = context.applicationContext
            val mgr = AppWidgetManager.getInstance(app)
            val ids = mgr.getAppWidgetIds(ComponentName(app, HioneLauncherClockWidget::class.java))
            ids.forEach { render(app, mgr, it) }
        }

        private fun render(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_hione_clock)
            v.setOnClickPendingIntent(R.id.widget_clock_root, HioneBatteryWidget.launchLauncher(ctx))
            mgr.updateAppWidget(id, v)
        }
    }
}
