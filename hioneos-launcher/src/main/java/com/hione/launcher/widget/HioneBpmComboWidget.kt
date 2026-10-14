package com.hione.launcher.widget

import com.hione.launcher.Mascot
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
 * Combined BPM widget: live now-playing tempo (HioneBpmWidget's read) on top, the tap-tempo orb +
 * tapped readout (HioneBpmTapperWidget's session, shared via its SharedPreferences store) below.
 * Taps broadcast ACTION_TAP back to this provider; the shared handler re-renders the whole
 * BPM widget family.
 */
class HioneBpmComboWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(context, mgr, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == HioneBpmTapperWidget.ACTION_TAP) {
            HioneBpmTapperWidget.registerTap(context)
        } else {
            super.onReceive(context, intent)
        }
    }

    companion object {
        private var lastPushMs = 0L

        /** Throttled like HioneBpmWidget — the live half also refreshes on per-beat pulses. */
        fun pushUpdate(context: Context) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastPushMs < 1000L) return
            lastPushMs = now
            val app = context.applicationContext
            val mgr = AppWidgetManager.getInstance(app)
            val ids = mgr.getAppWidgetIds(ComponentName(app, HioneBpmComboWidget::class.java))
            ids.forEach { render(app, mgr, it) }
        }

        private fun render(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_hione_bpm_combo)

            // ---- Live now-playing half ---------------------------------------------------
            val cr = ctx.contentResolver
            val link = com.hione.launcher.lockscreen.HioneMediaLink.active(ctx)
            val title = link?.title?.takeIf { it.isNotBlank() }
                ?: try { Settings.Global.getString(cr, "hione_now_playing_title") ?: "" } catch (_: Throwable) { "" }
            val playing = (link?.isPlaying == true) || try { Settings.Global.getInt(cr, "hione_is_playing", 0) == 1 } catch (_: Throwable) { false }
            val intervalMs = try { Settings.Global.getInt(cr, "hione_beat_interval_ms", 0) } catch (_: Throwable) { 0 }
            val liveBpm = if (intervalMs > 0) 60_000 / intervalMs else 0

            v.setTextViewText(R.id.widget_combo_title, title.ifEmpty { "${Mascot.musicAppName.uppercase()}" })
            v.setTextViewText(
                R.id.widget_combo_live,
                if (liveBpm > 0) (if (playing) "♪ $liveBpm BPM" else "❚❚ $liveBpm BPM") else "— BPM"
            )
            v.setTextColor(
                R.id.widget_combo_live,
                if (playing) Mascot.argb(0xFFFF4FA3.toInt()) else Mascot.argb(0xFFFFE0B0.toInt())
            )

            // ---- Tapper half (shared session store) ---------------------------------------
            val (tapBpm, taps) = HioneBpmTapperWidget.tapSnapshot(ctx)
            v.setTextViewText(
                R.id.widget_combo_tapped,
                if (tapBpm > 0f) "TAP ${tapBpm.toInt()} BPM" else "TAP — BPM"
            )
            v.setTextViewText(
                R.id.widget_combo_count,
                if (taps > 0) "$taps TAP${if (taps == 1) "" else "S"}" else "TAP IN"
            )

            v.setOnClickPendingIntent(
                R.id.widget_combo_tap_btn,
                HioneBpmTapperWidget.tapIntent(ctx, HioneBpmComboWidget::class.java, 7012)
            )
            // Tapping a BPM widget opens the game itself, not just the home screen.
            v.setOnClickPendingIntent(R.id.widget_combo_root, com.hione.launcher.bpm.HioneBpmGameActivity.pendingIntent(ctx, "widget"))
            mgr.updateAppWidget(id, v)
        }
    }
}
