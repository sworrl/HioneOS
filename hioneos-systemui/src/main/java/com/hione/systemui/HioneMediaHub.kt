package com.hione.systemui

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Log

/**
 * Active media session lookup for the shade's media card and the recents media chip.
 * Uses MediaSessionManager with our notification-listener component (works when the listener
 * is enabled; MEDIA_CONTENT_CONTROL — signature-level, granted to this platform-signed app —
 * covers the rest).
 */
object HioneMediaHub {
    private const val TAG = "HioneMedia"

    data class Now(
        val pkg: String,
        val appLabel: String,
        val appIcon: Drawable?,
        val title: String,
        val artist: String,
        val album: String,
        val art: Bitmap?,
        val isPlaying: Boolean,
        val positionMs: Long,
        val durationMs: Long,
        val controller: MediaController
    )

    fun controllers(ctx: Context): List<MediaController> = runCatching {
        val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        msm.getActiveSessions(ComponentName(ctx, HioneNotificationListenerService::class.java))
    }.onFailure { Log.w(TAG, "getActiveSessions: $it") }.getOrDefault(emptyList())

    /** Best session: playing first, Hione Music preferred, then anything with metadata. */
    fun now(ctx: Context): Now? {
        val cs = controllers(ctx).filter { it.metadata != null }
        if (cs.isEmpty()) return null
        val pick = cs.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING && it.packageName == "com.hione.player" }
            ?: cs.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: cs.firstOrNull { it.packageName == "com.hione.player" }
            ?: cs.first()
        val md = pick.metadata ?: return null
        val ps = pick.playbackState
        val pm = ctx.packageManager
        val ai = runCatching { pm.getApplicationInfo(pick.packageName, 0) }.getOrNull()
        val pos = ps?.let {
            val base = it.position
            if (it.state == PlaybackState.STATE_PLAYING) base + ((android.os.SystemClock.elapsedRealtime() - it.lastPositionUpdateTime) * it.playbackSpeed).toLong() else base
        } ?: 0L
        return Now(
            pkg = pick.packageName,
            appLabel = ai?.let { runCatching { pm.getApplicationLabel(it).toString() }.getOrNull() } ?: pick.packageName,
            appIcon = ai?.let { runCatching { pm.getApplicationIcon(it) }.getOrNull() },
            title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: "",
            artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: "",
            album = md.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "",
            art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
            isPlaying = ps?.state == PlaybackState.STATE_PLAYING,
            positionMs = pos.coerceAtLeast(0L),
            durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION),
            controller = pick
        )
    }

    /** Average colour of a downsampled bitmap, nudged toward the Hione palette for glows. */
    fun dominantColor(bmp: Bitmap?, fallback: Int = Mascot.argb(0xFFFFAB2E.toInt())): Int {
        bmp ?: return fallback
        return runCatching {
            val s = Bitmap.createScaledBitmap(bmp, 12, 12, true)
            var r = 0L; var g = 0L; var b = 0L; var n = 0
            for (y in 0 until 12) for (x in 0 until 12) {
                val c = s.getPixel(x, y); val a = (c ushr 24) and 0xFF
                if (a < 40) continue
                r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
            }
            if (n == 0) return fallback
            val hsv = FloatArray(3)
            android.graphics.Color.RGBToHSV((r / n).toInt(), (g / n).toInt(), (b / n).toInt(), hsv)
            hsv[1] = (hsv[1] * 1.25f).coerceIn(0.35f, 1f); hsv[2] = hsv[2].coerceIn(0.55f, 1f)
            android.graphics.Color.HSVToColor(hsv)
        }.getOrDefault(fallback)
    }
}
