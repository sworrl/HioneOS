package com.hione.launcher.widget

import com.hione.launcher.Mascot
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import com.hione.launcher.R
import com.hione.launcher.weather.HioneWeatherService
import kotlin.math.roundToInt

/**
 * Current weather as an individual system App Widget. Widgets render in the launcher process, so
 * the render is a synchronous read of HioneWeatherService.state.value — no fetch of its own.
 * Refreshed via [pushUpdate] from the service's fetch cycle. On a cold start it seeds from the
 * persisted last forecast; only when no fetch has EVER succeeded does it show an awaiting-data
 * placeholder instead of default values.
 */
class HioneWeatherWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(context, mgr, it) }
    }

    companion object {
        fun pushUpdate(context: Context) {
            val app = context.applicationContext
            val mgr = AppWidgetManager.getInstance(app)
            val ids = mgr.getAppWidgetIds(ComponentName(app, HioneWeatherWidget::class.java))
            ids.forEach { render(app, mgr, it) }
        }

        /**
         * Alert or weather radio line, read from the Settings.Global keys HioneNwsAlerts publishes
         * (so it is right even in a fresh process before the alert engine has run).
         */
        private fun renderAlertLine(ctx: Context, v: RemoteViews) {
            val cr = ctx.contentResolver
            fun g(k: String) = runCatching { android.provider.Settings.Global.getString(cr, k) }.getOrNull().orEmpty()
            val count = g(com.hione.launcher.weather.HioneNwsAlerts.KEY_COUNT).toIntOrNull() ?: 0
            val top = g(com.hione.launcher.weather.HioneNwsAlerts.KEY_TOP)
            val nwr = g(com.hione.launcher.weather.HioneNwsAlerts.KEY_NWR)
            when {
                count > 0 && top.isNotBlank() -> {
                    val sev = g(com.hione.launcher.weather.HioneNwsAlerts.KEY_SEVERITY)
                    v.setTextViewText(R.id.widget_weather_alert, if (count > 1) "$top +${count - 1}" else top)
                    v.setTextColor(R.id.widget_weather_alert, com.hione.launcher.weather.alertColor(sev).toArgb())
                    v.setViewVisibility(R.id.widget_weather_alert, android.view.View.VISIBLE)
                    v.setTextColor(R.id.widget_weather_temp, 0xFFFF5252.toInt())
                }
                nwr.isNotBlank() -> {
                    // "KWN35 WX3 162.475" -> "WX3 162.475"
                    v.setTextViewText(R.id.widget_weather_alert, "NOAA " + nwr.substringAfter(' '))
                    v.setTextColor(R.id.widget_weather_alert, 0xFF8BA6A9.toInt())
                    v.setViewVisibility(R.id.widget_weather_alert, android.view.View.VISIBLE)
                }
                else -> v.setViewVisibility(R.id.widget_weather_alert, android.view.View.GONE)
            }
        }

        private fun render(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_hione_weather)

            // The widget can render in a freshly-started launcher process before the service's
            // start() runs — seed from the persisted last forecast so it's never blank when one
            // exists. No-op if state already holds a fresher condition.
            if (HioneWeatherService.state.value.weather.lastUpdatedTime == 0L) {
                runCatching { HioneWeatherService.restoreLastWeather(ctx.applicationContext) }
            }
            val w = HioneWeatherService.state.value.weather
            val fresh = w.lastUpdatedTime > 0L
            v.setTextViewText(R.id.widget_weather_icon, if (fresh) w.icon else "🌐")
            v.setTextViewText(
                R.id.widget_weather_temp,
                if (fresh) "${w.tempF.roundToInt()}°F" else "--°"
            )
            v.setTextViewText(
                R.id.widget_weather_cond,
                if (fresh) w.summary else "AWAITING DATA"
            )
            // Severe conditions flip the temp readout red, matching the in-bar badge's urgency cue.
            v.setTextColor(
                R.id.widget_weather_temp,
                if (fresh && w.severeWarning != null) 0xFFFF5252.toInt() else Mascot.argb(0xFFFFAB2E.toInt())
            )

            renderAlertLine(ctx, v)

            v.setOnClickPendingIntent(R.id.widget_weather_root, HioneBatteryWidget.launchLauncher(ctx))
            mgr.updateAppWidget(id, v)
        }
    }
}
