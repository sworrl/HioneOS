package com.hione.launcher.widget

import com.hione.launcher.Mascot
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.SystemClock
import android.widget.RemoteViews
import com.hione.launcher.R

/**
 * STREAM_MUSIC volume as an individual system App Widget. Live on-render read from AudioManager;
 * refreshed via [pushUpdate] from HioneVolumeManager on every volume change (lightly throttled —
 * knob/rotary spins fire updates in bursts). Tapping fires [ACTION_SHOW_HUD] to this receiver,
 * which raises the launcher's volume HUD via HioneVolumeManager.triggerHud — so a tap on the home
 * screen (launcher foreground) shows the same modal the in-bar badge does, no Activity plumbing.
 */
class HioneVolumeWidget : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_SHOW_HUD) {
            runCatching {
                com.hione.launcher.volume.HioneVolumeManager.triggerHud(context.applicationContext)
            }
            pushUpdate(context)
        }
        super.onReceive(context, intent)
    }

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(context, mgr, it) }
    }

    companion object {
        const val ACTION_SHOW_HUD = "com.hione.launcher.SHOW_VOLUME_HUD"
        private var lastPushMs = 0L

        fun pushUpdate(context: Context) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastPushMs < 300L) return
            lastPushMs = now
            val app = context.applicationContext
            val mgr = AppWidgetManager.getInstance(app)
            val ids = mgr.getAppWidgetIds(ComponentName(app, HioneVolumeWidget::class.java))
            ids.forEach { render(app, mgr, it) }
        }

        private fun render(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_hione_volume)

            val am = ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val cur = am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
            val max = (am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 1).coerceAtLeast(1)
            val pct = ((cur * 100) / max).coerceIn(0, 100)
            val muted = cur == 0

            // Follows the spectral volume gradient's mood: teal low, pink hot, red danger/mute.
            val tint = when {
                muted -> 0xFFFF5252.toInt()
                pct >= 80 -> 0xFFFF5252.toInt()
                pct >= 50 -> Mascot.argb(0xFFFF4FA3.toInt())
                else -> Mascot.argb(0xFFFFAB2E.toInt())
            }
            v.setTextViewText(R.id.widget_volume_icon, if (muted) "🔇" else "🔊")
            v.setTextViewText(R.id.widget_volume_text, if (muted) "MUTE" else "VOL $pct%")
            v.setTextColor(R.id.widget_volume_text, tint)

            val tap = PendingIntent.getBroadcast(
                ctx, 7009,
                Intent(ctx, HioneVolumeWidget::class.java).setAction(ACTION_SHOW_HUD),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            v.setOnClickPendingIntent(R.id.widget_volume_root, tap)
            mgr.updateAppWidget(id, v)
        }
    }
}
