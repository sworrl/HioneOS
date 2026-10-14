package com.hione.launcher.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.widget.RemoteViews
import com.hione.launcher.R

/**
 * Gamified tap-tempo orb as an interactive system App Widget. Same algorithm as the
 * KawaiiWaifuTapTempoOrb in HioneBpmObservatoryModal: rolling window of the last 6 tap
 * timestamps, BPM = 60000 / mean(consecutive intervals), clamped 40..260. A gap over 2s
 * starts a fresh session so an old tempo can't pollute a new tap-in. RemoteViews can't run
 * code, so each tap is a broadcast back to this provider (ACTION_TAP) and the session lives
 * in SharedPreferences. The computed tempo is published to Settings.Global
 * (hione_beat_interval_ms + hione_tapped_bpm) so the rest of the system syncs to it.
 */
class HioneBpmTapperWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(context, mgr, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TAP) {
            registerTap(context)
        } else {
            super.onReceive(context, intent)
        }
    }

    companion object {
        const val ACTION_TAP = "com.hione.launcher.BPM_TAP"

        private const val PREFS = "hione_launcher_prefs"
        private const val KEY_TAP_TIMES = "bpm_tap_times"
        private const val KEY_TAPPED_BPM = "bpm_tapped_bpm"
        private const val SESSION_GAP_MS = 2000L
        private const val MAX_TAPS = 6

        fun pushUpdate(context: Context) {
            val app = context.applicationContext
            val mgr = AppWidgetManager.getInstance(app)
            val ids = mgr.getAppWidgetIds(ComponentName(app, HioneBpmTapperWidget::class.java))
            ids.forEach { render(app, mgr, it) }
        }

        /**
         * Shared tap handler for the tapper AND combo widgets: fold the tap into the rolling
         * session, compute/persist/publish the tempo, then re-render every BPM widget family
         * member (the display widget shows the published interval too).
         */
        internal fun registerTap(context: Context) {
            val app = context.applicationContext
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val now = SystemClock.elapsedRealtime()

            val times = (prefs.getString(KEY_TAP_TIMES, "") ?: "")
                .split(',')
                .mapNotNull { it.toLongOrNull() }
                .toMutableList()
            // New session on a >2s gap — or on a clock that ran backwards (reboot resets
            // elapsedRealtime, leaving stale future timestamps behind).
            if (times.isNotEmpty() && (now < times.last() || now - times.last() > SESSION_GAP_MS)) {
                times.clear()
            }
            times.add(now)
            while (times.size > MAX_TAPS) times.removeAt(0)

            var bpm = prefs.getFloat(KEY_TAPPED_BPM, 0f)
            if (times.size >= 2) {
                val avgInterval = (1 until times.size).map { times[it] - times[it - 1] }.average()
                if (avgInterval > 0) {
                    bpm = (60_000.0 / avgInterval).toFloat().coerceIn(40f, 260f)
                }
            }
            prefs.edit()
                .putString(KEY_TAP_TIMES, times.joinToString(","))
                .putFloat(KEY_TAPPED_BPM, bpm)
                .apply()

            // Sync the system tempo. Platform-signed launcher can write Settings.Global; never
            // fail the tap if it can't.
            if (bpm > 0f) {
                try {
                    val cr = app.contentResolver
                    Settings.Global.putInt(cr, "hione_beat_interval_ms", (60_000f / bpm).toInt())
                    Settings.Global.putFloat(cr, "hione_tapped_bpm", bpm)
                } catch (_: Throwable) {}
            }

            try { pushUpdate(app) } catch (_: Throwable) {}
            try { HioneBpmComboWidget.pushUpdate(app) } catch (_: Throwable) {}
            try { HioneBpmWidget.pushUpdate(app) } catch (_: Throwable) {}
        }

        /** Session snapshot for rendering: tapped BPM + taps in the current (non-expired) run. */
        internal fun tapSnapshot(ctx: Context): Pair<Float, Int> {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val bpm = prefs.getFloat(KEY_TAPPED_BPM, 0f)
            val times = (prefs.getString(KEY_TAP_TIMES, "") ?: "")
                .split(',')
                .mapNotNull { it.toLongOrNull() }
            val now = SystemClock.elapsedRealtime()
            val sessionTaps =
                if (times.isEmpty() || now < times.last() || now - times.last() > SESSION_GAP_MS) 0
                else times.size
            return bpm to sessionTaps
        }

        /** Broadcast back into the given provider — RemoteViews' only interactivity channel. */
        internal fun tapIntent(ctx: Context, provider: Class<*>, requestCode: Int): PendingIntent {
            val intent = Intent(ctx, provider).setAction(ACTION_TAP)
            return PendingIntent.getBroadcast(
                ctx, requestCode, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        private fun render(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_hione_bpm_tapper)

            val (bpm, taps) = tapSnapshot(ctx)
            v.setTextViewText(
                R.id.widget_tapper_value,
                if (bpm > 0f) "${bpm.toInt()} BPM" else "— BPM"
            )
            v.setTextViewText(
                R.id.widget_tapper_count,
                if (taps > 0) "$taps TAP${if (taps == 1) "" else "S"}" else "TAP IN"
            )

            v.setOnClickPendingIntent(
                R.id.widget_tapper_btn,
                tapIntent(ctx, HioneBpmTapperWidget::class.java, 7011)
            )
            v.setOnClickPendingIntent(R.id.widget_tapper_root, HioneBatteryWidget.launchLauncher(ctx))
            mgr.updateAppWidget(id, v)
        }
    }
}
