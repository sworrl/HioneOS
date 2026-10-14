package com.hione.player.car

import com.hione.player.Mascot
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import androidx.core.graphics.drawable.IconCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.hione.player.PlayerHolder
import com.hione.player.R
import java.io.File

class HioneCarPlayerScreen(carContext: CarContext) : Screen(carContext) {

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            invalidate()
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            invalidate()
        }
    }

    private fun safeGetPlayer(): Player? = PlayerHolder.player

    private fun getAlbumArt(path: String?): Bitmap? {
        if (path == null || !File(path).exists()) return null
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(path)
            val art = retriever.embeddedPicture
            retriever.release()
            com.hione.player.SharedCoverGuard.init(carContext)
            if (art != null && !com.hione.player.SharedCoverGuard.isSuspectBytes(art)) {
                BitmapFactory.decodeByteArray(art, 0, art.size)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    override fun onGetTemplate(): Template {
        val player = safeGetPlayer()
        val isPlaying = player?.isPlaying == true
        val title = player?.mediaMetadata?.title?.toString() ?: "Nothing playing"
        val artist = player?.mediaMetadata?.artist?.toString() ?: "HioneMusic"
        val path = player?.currentMediaItem?.localConfiguration?.uri?.path
        
        val bitmap = getAlbumArt(path)
        val carIcon = if (bitmap != null) {
            CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build()
        } else {
            CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_search_head_hione)).build()
        }

        val row = Row.Builder()
            .setTitle(title)
            .addText(artist)
            .setImage(carIcon)
            .build()

        val playPauseAction = Action.Builder()
            .setIcon(
                CarIcon.Builder(
                    IconCompat.createWithResource(
                        carContext,
                        if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                    )
                ).build()
            )
            .setOnClickListener {
                if (isPlaying) player?.pause() else player?.play()
                invalidate()
            }
            .build()

        val skipPrevAction = Action.Builder()
            .setIcon(
                CarIcon.Builder(
                    IconCompat.createWithResource(carContext, R.drawable.ic_skip_prev)
                ).build()
            )
            .setOnClickListener {
                player?.seekToPrevious()
                invalidate()
            }
            .build()

        val skipNextAction = Action.Builder()
            .setIcon(
                CarIcon.Builder(
                    IconCompat.createWithResource(carContext, R.drawable.ic_skip_next)
                ).build()
            )
            .setOnClickListener {
                player?.seekToNext()
                invalidate()
            }
            .build()

        val pane = Pane.Builder()
            .addRow(row)
            .addAction(skipPrevAction)
            .addAction(playPauseAction)
            .addAction(skipNextAction)
            .build()

        return PaneTemplate.Builder(pane)
            .setHeaderAction(Action.APP_ICON)
            .setTitle("${Mascot.musicAppName}")
            .build()
    }

    init {
        lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                safeGetPlayer()?.addListener(playerListener)
            }
            override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
                safeGetPlayer()?.removeListener(playerListener)
            }
        })
    }
}
