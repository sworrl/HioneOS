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
 * Native Tape Mode Widget for Hione Music.
 * Renders a full spec-exact cassette tape with spinning hubs, active album art, track progress,
 * and transport controls directly on the home screen.
 */
class HioneTapeWidget : AppWidgetProvider() {

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
            // Off the BroadcastReceiver's main-thread onReceive — WidgetUpdateExecutor already
            // covers both widgets and coalesces with whatever PlayerHolder's own Player.Listener
            // is about to fire for this same play/pause/seek anyway.
            WidgetUpdateExecutor.push(context)
        }
    }

    companion object {
        private const val ACTION_TOGGLE = "com.hione.player.widget.tape.TOGGLE"
        private const val ACTION_NEXT = "com.hione.player.widget.tape.NEXT"
        private const val ACTION_PREV = "com.hione.player.widget.tape.PREV"

        fun pushUpdate(context: Context, snapshot: PlayerHolder.PlayerSnapshot? = PlayerHolder.snapshot()) {
            val app = context.applicationContext
            val mgr = AppWidgetManager.getInstance(app)
            val ids = mgr.getAppWidgetIds(ComponentName(app, HioneTapeWidget::class.java))
            ids.forEach { render(app, mgr, it, snapshot) }
        }

        private fun broadcast(ctx: Context, action: String): PendingIntent =
            PendingIntent.getBroadcast(
                ctx, action.hashCode(),
                Intent(ctx, HioneTapeWidget::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

        private fun render(ctx: Context, mgr: AppWidgetManager, id: Int, snapshot: PlayerHolder.PlayerSnapshot?) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_tape)
            val title = snapshot?.title?.ifBlank { null } ?: "${Mascot.musicAppName}"
            val artist = snapshot?.artist?.ifBlank { null } ?: "nothing playing"
            val album = snapshot?.album?.ifBlank { null } ?: ""
            val isPlaying = snapshot?.isPlaying == true

            val dur = snapshot?.durationMs ?: 0L
            val pos = snapshot?.positionMs ?: 0L
            val progress = if (dur > 0L) pos.toFloat() / dur.toFloat() else 0f

            val trackId = snapshot?.trackId
            var artBmp: android.graphics.Bitmap? = null
            if (trackId != null) {
                runCatching {
                    val uri = Uri.parse("content://media/external/audio/media/$trackId")
                    artBmp = ctx.contentResolver.loadThumbnail(uri, Size(180, 180), null)
                }
            }

            val tapeBmp = MiniTapeRenderer.renderMiniTape(
                context = ctx,
                width = 640,
                height = 400,
                title = title,
                artist = artist,
                album = album,
                progress = progress,
                isPlaying = isPlaying,
                albumArtBmp = artBmp
            )

            v.setImageViewBitmap(R.id.widget_tape_img, tapeBmp)
            // Transport glyphs are now baked directly into tapeBmp by MiniTapeRenderer (see
            // drawToggleGlyph/drawPrevGlyph/drawNextGlyph) — widget_tape_toggle is a plain
            // transparent <View> tap target, not an ImageView, so no setImageViewResource here.

            // Click body to open App into Tape Mode
            val openAppIntent = Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("open_tape_mode", true)
            }
            val openPending = PendingIntent.getActivity(
                ctx, 1001, openAppIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            v.setOnClickPendingIntent(R.id.widget_tape_root, openPending)

            v.setOnClickPendingIntent(R.id.widget_tape_prev, broadcast(ctx, ACTION_PREV))
            v.setOnClickPendingIntent(R.id.widget_tape_toggle, broadcast(ctx, ACTION_TOGGLE))
            v.setOnClickPendingIntent(R.id.widget_tape_next, broadcast(ctx, ACTION_NEXT))

            mgr.updateAppWidget(id, v)
        }
    }
}
