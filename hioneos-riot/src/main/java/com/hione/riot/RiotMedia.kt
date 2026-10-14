package com.hione.riot

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

/**
 * Hione Music stays the player. This is only its remote control.
 *
 * It connects to Hione Music's Media3 MediaLibraryService (com.hione.player PlaybackService), the
 * same service Android Auto browses. Playing works by handing MediaStore ids to setMediaItems;
 * Hione Music resolves each id against its own library in onSetMediaItems, so the queue, the
 * EQ, the output routing and the lockscreen card all stay Hione Music's.
 */
class RiotMedia(private val ctx: Context) {

    data class Now(
        val connected: Boolean = false,
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val mediaId: String = "",
        val playing: Boolean = false,
        val loaded: Boolean = false,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val index: Int = 0,
        val queue: List<String> = emptyList(),
        val shuffle: Boolean = false,
        val repeat: Int = Player.REPEAT_MODE_OFF
    )

    private var future: ListenableFuture<MediaBrowser>? = null
    private var browser: MediaBrowser? = null
    var onTrackStarted: ((String) -> Unit)? = null

    fun connect(onChange: () -> Unit) {
        if (future != null) return
        val token = SessionToken(ctx, ComponentName(PLAYER_PKG, PLAYER_SERVICE))
        val f = MediaBrowser.Builder(ctx, token).buildAsync()
        future = f
        f.addListener({
            val b = runCatching { f.get() }.getOrNull()
            if (b == null) {
                Log.w(TAG, "Hione Music did not accept the connection")
                future = null
                onChange()
                return@addListener
            }
            browser = b
            b.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = onChange()
                override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                    item?.mediaId?.let { onTrackStarted?.invoke(it) }
                }
            })
            onChange()
        }, ContextCompat.getMainExecutor(ctx))
    }

    fun release() {
        future?.let { MediaBrowser.releaseFuture(it) }
        future = null
        browser = null
    }

    val connected get() = browser != null

    fun snapshot(withQueue: Boolean): Now {
        val b = browser ?: return Now()
        val m = b.mediaMetadata
        val q = if (withQueue) List(b.mediaItemCount) { i ->
            val mm = b.getMediaItemAt(i).mediaMetadata
            (mm.title ?: mm.displayTitle ?: "").toString()
        } else emptyList()
        return Now(
            connected = true,
            title = (m.title ?: m.displayTitle ?: "").toString(),
            artist = (m.artist ?: m.albumArtist ?: "").toString(),
            album = (m.albumTitle ?: "").toString(),
            mediaId = b.currentMediaItem?.mediaId ?: "",
            playing = b.isPlaying,
            loaded = b.mediaItemCount > 0,
            positionMs = b.currentPosition.coerceAtLeast(0L),
            durationMs = b.duration.let { if (it < 0) 0L else it },
            index = b.currentMediaItemIndex,
            queue = q,
            shuffle = b.shuffleModeEnabled,
            repeat = b.repeatMode
        )
    }

    // ---- transport ----------------------------------------------------------------------------

    fun play() { browser?.let { if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.play() } }
    fun playPause() { browser?.let { if (it.isPlaying) it.pause() else play() } }
    fun stop() { browser?.let { it.pause(); it.seekTo(0L) } }
    fun next() { browser?.seekToNext() }
    fun prev() { browser?.let { if (it.currentPosition > 3000L) it.seekTo(0L) else it.seekToPrevious() } }
    fun seekBy(ms: Long) {
        browser?.let { it.seekTo((it.currentPosition + ms).coerceIn(0L, it.duration.coerceAtLeast(0L))) }
    }
    fun jumpTo(index: Int) { browser?.let { if (index in 0 until it.mediaItemCount) { it.seekToDefaultPosition(index); play() } } }
    fun setShuffle(on: Boolean) { browser?.shuffleModeEnabled = on }
    fun setRepeat(mode: Int) { browser?.repeatMode = mode }

    /**
     * Loads [ids] (MediaStore ids) into Hione Music's queue at [start]. The Riot loaded a playlist
     * and then waited for SELECT or PLAY, so [autoplay] is false for menu picks.
     */
    fun load(ids: List<Long>, start: Int = 0, autoplay: Boolean = false): Boolean {
        val b = browser ?: return false
        if (ids.isEmpty()) return false
        // A very long list is cut to a window around the pick: Hione Music resolves each id
        // against its library, and thousands of them is a lot of binder for one press.
        val from = (start - 250).coerceAtLeast(0)
        val to = (start + 750).coerceAtMost(ids.size)
        val items = ids.subList(from, to).map { MediaItem.Builder().setMediaId(it.toString()).build() }
        b.setMediaItems(items, start - from, 0L)
        b.prepare()
        if (autoplay) b.play()
        return true
    }

    fun volume(): Pair<Int, Int> {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return 0 to 15
        return am.getStreamVolume(AudioManager.STREAM_MUSIC) to am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    }

    /**
     * One press of the Riot's Volume key. The M500's music stream has 100 steps, which would take
     * ten presses to light one of the bar's ten segments, so a press moves 1/25 of the range.
     * Flags 0: no system volume panel, the Riot's own bar shows it.
     */
    fun adjustVolume(dir: Int) {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val step = maxOf(1, (max + 24) / 25)
        val next = (cur + (if (dir > 0) step else -step)).coerceIn(0, max)
        if (next != cur) runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0) }
    }

    companion object {
        private const val TAG = "RiotMedia"
        const val PLAYER_PKG = "com.hione.player"
        const val PLAYER_SERVICE = "com.hione.player.PlaybackService"
    }
}
