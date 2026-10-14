package com.hione.launcher.lockscreen

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.Rating
import android.os.Bundle
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri

/**
 * Session-agnostic now-playing link. Wraps a platform [android.media.session.MediaController] so the
 * lockscreen / now-playing HUD render and control the CURRENTLY ACTIVE media app in Hione style —
 * Spotify, Tidal, YouTube, anything — not just com.hione.player. Method names mirror the media3
 * controller the lockscreen previously used, so its transport call-sites are unchanged.
 *
 * Needs android.permission.MEDIA_CONTENT_CONTROL (signature — held via the Falcon platform key).
 */
class HioneMediaLink(private val c: MediaController) {
    val packageName: String get() = c.packageName ?: ""
    val title: String? get() = c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        ?: c.metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
    val artist: String? get() = c.metadata?.let {
        it.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: it.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: it.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
    }
    val album: String? get() = c.metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM)
    val mediaId: String? get() = c.metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
    val durationMs: Long get() = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
    val positionMs: Long get() = c.playbackState?.position ?: 0L
    val isPlaying: Boolean get() = c.playbackState?.state == PlaybackState.STATE_PLAYING

    /** Bitmap when the app embeds art, else a Uri, else null. Coil consumes any of them. */
    val artwork: Any? get() {
        val md = c.metadata ?: return null
        val bmp: Bitmap? = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (bmp != null) return bmp
        val uri = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: md.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
        return uri?.let { runCatching { Uri.parse(it) }.getOrNull() }
    }

    // ================================================================= like / save
    //
    // The lockscreen heart used to ALWAYS broadcast to com.hione.player, so on a Spotify (or Tidal,
    // Qobuz, Deezer) session it did nothing at all: the broadcast went to an app that had never
    // heard of the track. A third-party session has to be liked through ITS OWN session.
    //
    // Two standard routes, checked in order, and NOTHING is guessed:
    //   1. ACTION_SET_RATING with a heart or thumbs rating type. The documented way, and the one
    //      the metadata can also be read back from.
    //   2. A custom action the session ITSELF declares. We match the action ids the session
    //      publishes against save/like wording; we never send an id the session did not advertise.
    //      Spotify's is in this family, which is how a "save to Liked Songs" button works in
    //      Android Auto and on a watch.
    // If a session offers neither, [likeSupport] is NONE and the heart is hidden rather than drawn
    // as a control that silently does nothing.

    enum class LikeSupport { NONE, RATING, CUSTOM_ACTION }

    /** Words a save/like custom action id or name uses. Matched case-insensitively. */
    private val LIKE_TOKENS = listOf(
        "add_to_collection", "addtocollection", "add-to-collection",
        "remove_from_collection", "removefromcollection",
        "favorite", "favourite", "like", "love", "heart",
        "thumbs_up", "thumbup", "thumbs-up", "save"
    )

    private fun likeActions(): List<PlaybackState.CustomAction> =
        c.playbackState?.customActions.orEmpty().filter { a ->
            val hay = (a.action + " " + a.name).lowercase()
            LIKE_TOKENS.any { hay.contains(it) }
        }

    val likeSupport: LikeSupport get() {
        val st = c.playbackState ?: return LikeSupport.NONE
        val ratingType = runCatching { c.ratingType }.getOrDefault(Rating.RATING_NONE)
        val canRate = (st.actions and PlaybackState.ACTION_SET_RATING) != 0L &&
            (ratingType == Rating.RATING_HEART || ratingType == Rating.RATING_THUMB_UP_DOWN)
        if (canRate) return LikeSupport.RATING
        if (likeActions().isNotEmpty()) return LikeSupport.CUSTOM_ACTION
        return LikeSupport.NONE
    }

    /**
     * Liked state AS THE SESSION REPORTS IT, or null when it does not report one.
     *
     * null is not false. A session that exposes a save action but no user rating cannot tell us
     * whether the track is already saved, and drawing an empty heart in that case would be
     * claiming it is not saved. The caller shows a neutral heart for null.
     */
    val likedFromSession: Boolean? get() {
        val r = runCatching { c.metadata?.getRating(MediaMetadata.METADATA_KEY_USER_RATING) }.getOrNull()
            ?: return null
        if (!r.isRated) return null
        return when (r.ratingStyle) {
            Rating.RATING_HEART -> r.hasHeart()
            Rating.RATING_THUMB_UP_DOWN -> r.isThumbUp
            else -> null
        }
    }

    /**
     * Like or unlike on the ACTIVE session. Returns true when something was actually sent.
     *
     * Never reports success for a session that had nothing to send to. A silent false is what the
     * caller needs in order to say so rather than animate a heart that means nothing.
     */
    fun toggleLike(makeLiked: Boolean): Boolean {
        val st = c.playbackState ?: return false
        val ratingType = runCatching { c.ratingType }.getOrDefault(Rating.RATING_NONE)
        if ((st.actions and PlaybackState.ACTION_SET_RATING) != 0L) {
            val rating = when (ratingType) {
                Rating.RATING_HEART -> Rating.newHeartRating(makeLiked)
                Rating.RATING_THUMB_UP_DOWN -> Rating.newThumbRating(makeLiked)
                else -> null
            }
            if (rating != null) {
                return runCatching { c.transportControls.setRating(rating); true }.getOrDefault(false)
            }
        }
        // Prefer the action whose wording matches the DIRECTION we want, so "remove from
        // collection" is not fired when the user is trying to save.
        val actions = likeActions()
        if (actions.isEmpty()) return false
        val removeish = listOf("remove", "unlike", "unsave", "unfavorite", "unfavourite", "thumbs_down")
        val pick = if (makeLiked) {
            actions.firstOrNull { a ->
                val hay = (a.action + " " + a.name).lowercase()
                removeish.none { hay.contains(it) }
            } ?: actions.first()
        } else {
            actions.firstOrNull { a ->
                val hay = (a.action + " " + a.name).lowercase()
                removeish.any { hay.contains(it) }
            } ?: actions.first()
        }
        return runCatching { c.transportControls.sendCustomAction(pick, Bundle.EMPTY); true }
            .getOrDefault(false)
    }

    /** Every custom action the session advertises, for the log line when a like has nowhere to go. */
    fun customActionIds(): List<String> = c.playbackState?.customActions.orEmpty().map { it.action }

    // media3-compatible names so the lockscreen transport call-sites need no edits.
    fun play() { runCatching { c.transportControls.play() } }
    fun pause() { runCatching { c.transportControls.pause() } }
    fun seekToNextMediaItem() { runCatching { c.transportControls.skipToNext() } }
    fun seekToPreviousMediaItem() { runCatching { c.transportControls.skipToPrevious() } }
    fun seekTo(ms: Long) { runCatching { c.transportControls.seekTo(ms) } }

    companion object {
        private const val HIONE = "com.hione.player"
        /** The active session to surface: prefer a PLAYING one (our player first), else any session. */
        fun active(ctx: Context): HioneMediaLink? = try {
            val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            val sessions = msm?.getActiveSessions(null) ?: emptyList()
            val playing = sessions.filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            val pick = playing.firstOrNull { it.packageName == HIONE }
                ?: playing.firstOrNull()
                ?: sessions.firstOrNull { it.packageName == HIONE }
                ?: sessions.firstOrNull()
            pick?.let { HioneMediaLink(it) }
        } catch (_: Throwable) { null }
    }
}
