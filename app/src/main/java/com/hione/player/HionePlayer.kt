package com.hione.player

interface HionePlayerListener {
    fun onIsPlayingChanged(isPlaying: Boolean) {}
    fun onMediaItemTransition(trackId: Long?, reason: Int) {}
    fun onMediaMetadataChanged() {}
    fun onPlayerError(error: Exception) {}
}

interface HionePlayer {
    val isPlaying: Boolean
    val currentPosition: Long
    val duration: Long
    val currentTrackId: Long?
    val currentMediaItemIndex: Int
    val mediaItemCount: Int
    var shuffleModeEnabled: Boolean
    var repeatMode: Int // 0=off, 1=one, 2=all
    val audioSessionId: Int

    fun play()
    fun pause()
    fun prepare()
    fun seekTo(positionMs: Long)
    fun seekToNextMediaItem()
    fun seekToPreviousMediaItem()

    // Track queue operations
    fun setTracks(tracks: List<Track>, startIndex: Int, startPositionMs: Long)
    fun addTracks(tracks: List<Track>)
    fun addTracks(index: Int, tracks: List<Track>)
    fun getTrackAt(index: Int): Track

    fun addListener(listener: HionePlayerListener)
    fun removeListener(listener: HionePlayerListener)
    fun release()

    fun setHandleAudioBecomingNoisy(enabled: Boolean)
}
