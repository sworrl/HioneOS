package com.hione.launcher

object PlayerHolder {
    data class PlayerSnapshot(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val isPlaying: Boolean = false,
        val durationMs: Long = 0L,
        val positionMs: Long = 0L,
        val trackId: Long? = null
    )
    fun snapshot(): PlayerSnapshot? = null
}
