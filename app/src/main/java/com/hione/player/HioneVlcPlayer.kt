package com.hione.player

import android.content.Context
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

class HioneVlcPlayer(private val context: Context) : HionePlayer {
    val libvlc = LibVLC(context, ArrayList<String>().apply {
        add("--aout=opensles")
        add("--audio-resampler=soxr")
        add("--verbose=2")
    })
    
    val player = MediaPlayer(libvlc)
    private val listeners = mutableListOf<HionePlayerListener>()
    
    private val queue = mutableListOf<Track>()
    private var currentIndex = -1
    
    private var _shuffle = false
    private var _repeat = 0
    
    init {
        player.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    listeners.forEach { it.onIsPlayingChanged(true) }
                }
                MediaPlayer.Event.Paused -> {
                    listeners.forEach { it.onIsPlayingChanged(false) }
                }
                MediaPlayer.Event.EndReached -> {
                    // Play next track
                    if (currentIndex < queue.size - 1 || _repeat == 2) {
                        seekToNextMediaItem()
                    } else if (_repeat == 1) {
                        seekTo(0)
                        play()
                    }
                }
                MediaPlayer.Event.EncounteredError -> {
                    listeners.forEach { it.onPlayerError(Exception("VLC Encountered Error")) }
                }
            }
        }
    }

    override val isPlaying: Boolean
        get() = player.isPlaying

    override val currentPosition: Long
        get() = player.time

    override val duration: Long
        get() = player.length

    override val currentTrackId: Long?
        get() = if (currentIndex in queue.indices) queue[currentIndex].id else null

    override val currentMediaItemIndex: Int
        get() = currentIndex

    override val mediaItemCount: Int
        get() = queue.size

    override var shuffleModeEnabled: Boolean
        get() = _shuffle
        set(value) { _shuffle = value }

    override var repeatMode: Int
        get() = _repeat
        set(value) { _repeat = value }

    override val audioSessionId: Int
        get() = 0 // VLC doesn't expose AudioSessionId easily without reflection, may need custom handling

    override fun play() {
        if (!player.isPlaying) player.play()
    }

    override fun pause() {
        if (player.isPlaying) player.pause()
    }

    override fun prepare() {
        // VLC prepares on play
    }

    override fun seekTo(positionMs: Long) {
        player.time = positionMs
    }

    override fun seekToNextMediaItem() {
        if (queue.isEmpty()) return
        var next = currentIndex + 1
        if (next >= queue.size) {
            if (_repeat == 2) next = 0 else return
        }
        playIndex(next)
    }

    override fun seekToPreviousMediaItem() {
        if (queue.isEmpty()) return
        var prev = currentIndex - 1
        if (prev < 0) {
            if (_repeat == 2) prev = queue.size - 1 else prev = 0
        }
        playIndex(prev)
    }
    
    private fun playIndex(index: Int) {
        if (index !in queue.indices) return
        currentIndex = index
        val track = queue[index]
        val media = Media(libvlc, track.path)
        player.media = media
        player.play()
        listeners.forEach { it.onMediaItemTransition(track.id, 0) }
    }

    override fun setTracks(tracks: List<Track>, startIndex: Int, startPositionMs: Long) {
        queue.clear()
        queue.addAll(tracks)
        currentIndex = startIndex
        if (currentIndex in queue.indices) {
            val media = Media(libvlc, queue[currentIndex].path)
            player.media = media
            if (startPositionMs > 0) {
                // Time set after start
            }
        }
    }

    override fun addTracks(tracks: List<Track>) {
        queue.addAll(tracks)
    }

    override fun addTracks(index: Int, tracks: List<Track>) {
        queue.addAll(index, tracks)
    }

    override fun getTrackAt(index: Int): Track {
        return queue[index]
    }

    override fun addListener(listener: HionePlayerListener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: HionePlayerListener) {
        listeners.remove(listener)
    }

    override fun release() {
        player.release()
        libvlc.release()
    }

    override fun setHandleAudioBecomingNoisy(enabled: Boolean) {
        // Handled via audio manager
    }
}
