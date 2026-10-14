package com.hione.wheel

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

/**
 * Hione Music stays the player. This is a Media3 MediaBrowser against its MediaLibraryService
 * (com.hione.player/.PlaybackService, the same tree Android Auto browses). Track media ids there
 * are MediaStore ids, so lists built from [Library] play as they are: a multi-item setMediaItems
 * resolves each id to a playable item on Hione Music's side.
 *
 * When the session can't be reached, transport keys still work through the system media keys,
 * which go to whatever session is active.
 */
class PlayerLink(private val ctx: Context, private val onChange: () -> Unit) {
    private var future: ListenableFuture<MediaBrowser>? = null
    var browser: MediaBrowser? = null
        private set
    var failed = false
        private set
    private var shuffleSeekPending = false
    private val audio = ctx.getSystemService(AudioManager::class.java)

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (shuffleSeekPending && player.mediaItemCount > 1 &&
                events.contains(Player.EVENT_TIMELINE_CHANGED)) {
                shuffleSeekPending = false
                player.seekTo((0 until player.mediaItemCount).random(), 0L)
            }
            onChange()
        }
    }

    fun connect() {
        if (future != null) return
        failed = false
        val token = SessionToken(ctx, ComponentName(PLAYER_PKG, "$PLAYER_PKG.PlaybackService"))
        val f = MediaBrowser.Builder(ctx, token).buildAsync()
        future = f
        f.addListener({
            try {
                val b = f.get()
                browser = b
                b.addListener(listener)
            } catch (t: Throwable) {
                Log.w(TAG, "Hione Music session not reachable", t)
                failed = true
                future = null
            }
            onChange()
        }, ContextCompat.getMainExecutor(ctx))
    }

    fun release() {
        browser?.removeListener(listener)
        future?.let { MediaBrowser.releaseFuture(it) }
        future = null
        browser = null
    }

    val connected: Boolean get() = browser?.isConnected == true

    // ---- now playing -------------------------------------------------------------------------

    val hasItem: Boolean get() = (browser?.mediaItemCount ?: 0) > 0
    val title: String get() = browser?.mediaMetadata?.title?.toString().orEmpty()
    val artist: String get() = browser?.mediaMetadata?.artist?.toString().orEmpty()
    val album: String get() = browser?.mediaMetadata?.albumTitle?.toString().orEmpty()
    val mediaId: Long get() = browser?.currentMediaItem?.mediaId?.toLongOrNull() ?: -1L
    val isPlaying: Boolean get() = browser?.isPlaying == true
    val positionMs: Long get() = browser?.currentPosition?.coerceAtLeast(0L) ?: 0L
    val durationMs: Long get() = browser?.duration?.takeIf { it > 0 } ?: 0L
    val index: Int get() = (browser?.currentMediaItemIndex ?: 0) + 1
    val count: Int get() = browser?.mediaItemCount ?: 0
    val shuffle: Boolean get() = browser?.shuffleModeEnabled == true
    val repeat: Int get() = browser?.repeatMode ?: Player.REPEAT_MODE_OFF

    // ---- transport ---------------------------------------------------------------------------

    fun playPause() {
        val b = browser
        if (b == null) { mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); return }
        if (b.isPlaying) b.pause() else { if (b.playbackState == Player.STATE_IDLE) b.prepare(); b.play() }
    }

    fun next() {
        val b = browser ?: return mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
        b.seekToNext()
    }

    /** Like the old players: back to the start if past 3 seconds, else the previous track. */
    fun previous() {
        val b = browser ?: return mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        if (b.currentPosition > 3000) b.seekTo(0) else b.seekToPrevious()
    }

    fun seekBy(deltaMs: Long) {
        val b = browser ?: return
        val d = b.duration
        val target = (b.currentPosition + deltaMs).coerceAtLeast(0L)
        b.seekTo(if (d > 0) target.coerceAtMost(d - 500) else target)
    }

    fun seekTo(ms: Long) { browser?.seekTo(ms.coerceAtLeast(0L)) }

    fun setShuffle(on: Boolean) { browser?.shuffleModeEnabled = on }
    fun setRepeat(mode: Int) { browser?.repeatMode = mode }

    /**
     * Play [ids] starting at [start]. Long lists are sent as a window around the start, so a
     * 17,000-song Songs list doesn't make Hione Music resolve 17,000 ids at once.
     */
    fun playIds(ids: List<Long>, start: Int): Boolean {
        val b = browser ?: return false
        if (ids.isEmpty()) return false
        val from = if (ids.size <= WINDOW) 0 else (start - 50).coerceIn(0, ids.size - WINDOW)
        val to = minOf(ids.size, from + WINDOW)
        val items = ArrayList<MediaItem>(to - from)
        for (i in from until to) items.add(MediaItem.Builder().setMediaId(ids[i].toString()).build())
        // A lone id makes Hione Music queue that song's album from it, which is a fine way to
        // keep the music going after a one-song list.
        b.shuffleModeEnabled = false
        b.setMediaItems(items, start - from, 0L)
        b.prepare()
        b.play()
        return true
    }

    /** The "Shuffle Songs" menu item: every song, shuffled, starting somewhere random. */
    fun shuffleAll(): Boolean {
        val b = browser ?: return false
        b.shuffleModeEnabled = true
        shuffleSeekPending = true
        b.setMediaItems(listOf(MediaItem.Builder().setMediaId("[songs]").build()))
        b.prepare()
        b.play()
        return true
    }

    /** Browse Hione Music's own tree (playlists live only there). [cb] runs on the main thread. */
    fun children(parentId: String, cb: (List<MediaItem>) -> Unit) {
        val b = browser ?: return cb(emptyList())
        val f = b.getChildren(parentId, 0, 5000, null)
        f.addListener({
            val list: List<MediaItem> = try {
                val r: LibraryResult<com.google.common.collect.ImmutableList<MediaItem>> = f.get()
                r.value ?: emptyList()
            } catch (_: Throwable) { emptyList() }
            cb(list)
        }, ContextCompat.getMainExecutor(ctx))
    }

    // ---- volume ------------------------------------------------------------------------------

    val maxVolume: Int get() = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    val volume: Int get() = audio.getStreamVolume(AudioManager.STREAM_MUSIC)

    fun adjustVolume(steps: Int) {
        val v = (volume + steps).coerceIn(0, maxVolume)
        try { audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) } catch (_: Throwable) { }
    }

    private fun mediaKey(code: Int) {
        val t = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
    }

    companion object {
        const val PLAYER_PKG = "com.hione.player"
        private const val TAG = "HionePodLink"
        private const val WINDOW = 400
    }
}
