package com.hione.player

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

class HioneExoPlayer(val player: ExoPlayer) : HionePlayer {

    override val isPlaying: Boolean
        get() = player.isPlaying

    override val currentPosition: Long
        get() = player.currentPosition

    override val duration: Long
        get() = player.duration

    override val currentTrackId: Long?
        get() = player.currentMediaItem?.mediaId?.toLongOrNull()

    override val currentMediaItemIndex: Int
        get() = player.currentMediaItemIndex

    override val mediaItemCount: Int
        get() = player.mediaItemCount

    override var shuffleModeEnabled: Boolean
        get() = player.shuffleModeEnabled
        set(value) { player.shuffleModeEnabled = value }

    override var repeatMode: Int
        get() = player.repeatMode
        set(value) { player.repeatMode = value }

    override val audioSessionId: Int
        get() = player.audioSessionId

    override fun play() {
        player.play()
    }

    override fun pause() {
        player.pause()
    }

    override fun prepare() {
        player.prepare()
    }

    override fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
    }

    override fun seekToNextMediaItem() {
        player.seekToNextMediaItem()
    }

    override fun seekToPreviousMediaItem() {
        player.seekToPreviousMediaItem()
    }

    override fun setTracks(tracks: List<Track>, startIndex: Int, startPositionMs: Long) {
        val items = tracks.map { MediaItem.Builder().setMediaId(it.id.toString()).setUri(it.path).build() }
        player.setMediaItems(items, startIndex, startPositionMs)
    }

    override fun addTracks(tracks: List<Track>) {
        val items = tracks.map { MediaItem.Builder().setMediaId(it.id.toString()).setUri(it.path).build() }
        player.addMediaItems(items)
    }

    override fun addTracks(index: Int, tracks: List<Track>) {
        val items = tracks.map { MediaItem.Builder().setMediaId(it.id.toString()).setUri(it.path).build() }
        player.addMediaItems(index, items)
    }

    override fun getTrackAt(index: Int): Track {
        val item = player.getMediaItemAt(index)
        return Track(
            id = item.mediaId.toLongOrNull() ?: 0L,
            title = item.mediaMetadata.title?.toString() ?: "",
            artist = item.mediaMetadata.artist?.toString() ?: "",
            album = item.mediaMetadata.albumTitle?.toString() ?: "",
            durationMs = 0L,
            sizeBytes = 0L,
            bitrateKbps = 0,
            mime = "",
            path = item.localConfiguration?.uri?.toString() ?: ""
        )
    }

    private val listenerMap = mutableMapOf<HionePlayerListener, Player.Listener>()

    override fun addListener(listener: HionePlayerListener) {
        val exoListener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                listener.onIsPlayingChanged(isPlaying)
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                listener.onMediaItemTransition(mediaItem?.mediaId?.toLongOrNull(), reason)
            }
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                listener.onMediaMetadataChanged()
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                listener.onPlayerError(error)
            }
        }
        listenerMap[listener] = exoListener
        player.addListener(exoListener)
    }

    override fun removeListener(listener: HionePlayerListener) {
        listenerMap.remove(listener)?.let { player.removeListener(it) }
    }

    override fun release() {
        player.release()
    }
    
    override fun setHandleAudioBecomingNoisy(enabled: Boolean) {
        player.setHandleAudioBecomingNoisy(enabled)
    }
}
