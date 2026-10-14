package com.hione.player

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Size
import android.widget.RemoteViews

/**
 * Native Modern Cyberpunk Now-Playing Card widget for Hione Music.
 */
class HioneMusicWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        val snapshot = PlayerHolder.snapshot() // safe here — onUpdate runs on the main thread
        ids.forEach { render(context, mgr, it, snapshot) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val p = PlayerHolder.player
        when (intent.action) {
            ACTION_TOGGLE -> p?.let { if (it.isPlaying) it.pause() else it.play() }
            ACTION_NEXT -> p?.seekToNextMediaItem()
            ACTION_PREV -> p?.seekToPreviousMediaItem()
        }
        if (intent.action in setOf(ACTION_TOGGLE, ACTION_NEXT, ACTION_PREV)) {
            // Off the BroadcastReceiver's main-thread onReceive — see WidgetUpdateExecutor.
            WidgetUpdateExecutor.push(context)
        }
    }

    companion object {
        private const val ACTION_TOGGLE = "com.hione.player.widget.TOGGLE"
        private const val ACTION_NEXT = "com.hione.player.widget.NEXT"
        private const val ACTION_PREV = "com.hione.player.widget.PREV"

        /** Re-render every instance of the widget — call on any playback state change. */
        fun pushUpdate(context: Context, snapshot: PlayerHolder.PlayerSnapshot? = PlayerHolder.snapshot()) {
            val app = context.applicationContext
            val mgr = AppWidgetManager.getInstance(app)
            val ids = mgr.getAppWidgetIds(ComponentName(app, HioneMusicWidget::class.java))
            ids.forEach { render(app, mgr, it, snapshot) }
        }

        private fun broadcast(ctx: Context, action: String): PendingIntent =
            PendingIntent.getBroadcast(
                ctx, action.hashCode(),
                Intent(ctx, HioneMusicWidget::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

        private fun render(ctx: Context, mgr: AppWidgetManager, id: Int, snapshot: PlayerHolder.PlayerSnapshot?) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_now_playing)
            val title = snapshot?.title?.ifBlank { null } ?: "${Mascot.musicAppName}"
            val artist = snapshot?.artist?.ifBlank { null } ?: "Nothing playing"
            val isPlaying = snapshot?.isPlaying == true

            val trackId = snapshot?.trackId
            var artBmp: android.graphics.Bitmap? = null
            if (trackId != null) {
                runCatching {
                    val uri = Uri.parse("content://media/external/audio/media/$trackId")
                    artBmp = ctx.contentResolver.loadThumbnail(uri, Size(120, 120), null)
                }
            }

            v.setTextViewText(R.id.widget_title, title)
            v.setTextViewText(R.id.widget_artist, artist)

            if (artBmp != null) {
                v.setImageViewBitmap(R.id.widget_art, artBmp)
            } else {
                v.setImageViewResource(R.id.widget_art, android.R.drawable.ic_media_play)
            }

            v.setImageViewResource(
                R.id.widget_toggle,
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            )

            // Click body to open App
            val openAppIntent = Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val openPending = PendingIntent.getActivity(
                ctx, 3001, openAppIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            v.setOnClickPendingIntent(R.id.widget_root, openPending)

            v.setOnClickPendingIntent(R.id.widget_prev, broadcast(ctx, ACTION_PREV))
            v.setOnClickPendingIntent(R.id.widget_toggle, broadcast(ctx, ACTION_TOGGLE))
            v.setOnClickPendingIntent(R.id.widget_next, broadcast(ctx, ACTION_NEXT))

            mgr.updateAppWidget(id, v)
        }
    }
}
