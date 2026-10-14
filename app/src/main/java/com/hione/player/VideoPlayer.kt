package com.hione.player

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.view.TextureView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay

data class VideoItem(
    val id: Long,
    val title: String,
    val path: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val dateAddedSec: Long,
)

/** Mirrors queryTracks()'s exact shape/defensiveness, MediaStore.Video instead of .Audio. */
fun queryVideos(ctx: Context): List<VideoItem> {
    val out = ArrayList<VideoItem>()
    runCatching {
        val proj = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.TITLE, MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DURATION, MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.WIDTH, MediaStore.Video.Media.HEIGHT, MediaStore.Video.Media.DATE_ADDED,
        )
        ctx.contentResolver.safeQuery(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj, null, null, "${MediaStore.Video.Media.TITLE} ASC"
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val iT = c.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
            val iPath = c.getColumnIndex(MediaStore.Video.Media.DATA)
            val iDur = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val iSize = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val iW = c.getColumnIndex(MediaStore.Video.Media.WIDTH)
            val iH = c.getColumnIndex(MediaStore.Video.Media.HEIGHT)
            val iDate = c.getColumnIndex(MediaStore.Video.Media.DATE_ADDED)
            while (c.moveToNext()) {
                val path = if (iPath >= 0 && !c.isNull(iPath)) c.getString(iPath) ?: "" else ""
                val rawTitle = if (!c.isNull(iT)) c.getString(iT) else null
                out.add(
                    VideoItem(
                        id = c.getLong(iId),
                        title = rawTitle?.ifBlank { null } ?: path.substringAfterLast('/').ifBlank { "Video" },
                        path = path,
                        durationMs = c.getLong(iDur),
                        sizeBytes = c.getLong(iSize),
                        width = if (iW >= 0) c.getInt(iW) else 0,
                        height = if (iH >= 0) c.getInt(iH) else 0,
                        dateAddedSec = if (iDate >= 0) c.getLong(iDate) else 0L,
                    )
                )
            }
        }
    }
    return out
}

/** A separate ExoPlayer instance from the music one — deliberately not sharing PlayerHolder.player.
 *  Mixing video into the music queue/MediaSession/widgets/scrobbling/tape-mode infrastructure would
 *  be a mess (a movie showing up in shuffle, "liked songs", the lockscreen music widget...). Video
 *  playback pauses music on entry and lets the user resume it themselves on exit — simple, doesn't
 *  touch the music queue's state at all. */
object VideoPlayerHolder {
    var player: ExoPlayer? = null
        private set

    fun ensure(ctx: Context): ExoPlayer {
        player?.let { return it }
        val p = ExoPlayer.Builder(ctx.applicationContext).build()
        player = p
        return p
    }

    fun release() {
        player?.release()
        player = null
    }
}

// Direction of the 90° rotation — flip the sign if this lands the controls/orientation backwards
// for how you actually hold the device to watch. Named here so it's a one-line change either way.
private const val VIDEO_ROTATION_DEG = 90f

/**
 * Fullscreen video playback WITHOUT rotating the device/window — same idea as the app's fullscreen
 * visualizer (Activity stays portrait-locked the whole time), except the content itself is sized
 * and rotated as if it were landscape so it fills the portrait screen edge-to-edge: the box holding
 * the video is measured with WIDTH/HEIGHT SWAPPED relative to the real portrait screen, then rotated
 * 90° around its own center — the classic "landscape content in a portrait-locked container" trick.
 * Custom transport controls are built as a normal landscape bottom bar and rotate along with the
 * video as one rigid unit, landing on what becomes the screen's new physical edge after rotation.
 */
@Composable
fun VideoPlayerScreen(video: VideoItem, musicPlayer: ExoPlayer, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val player = remember { VideoPlayerHolder.ensure(ctx) }
    var isPlaying by remember { mutableStateOf(false) }
    var pos by remember { mutableStateOf(0L) }
    var dur by remember { mutableStateOf(video.durationMs.coerceAtLeast(1L)) }
    var buffering by remember { mutableStateOf(true) }
    var showControls by remember { mutableStateOf(true) }
    var dragging by remember { mutableStateOf(false) }

    DisposableEffect(video.id) {
        val wasMusicPlaying = musicPlayer.isPlaying
        if (wasMusicPlaying) musicPlayer.pause()
        val uri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, video.id.toString())
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.playWhenReady = true
        onDispose {
            player.stop()
            player.clearMediaItems()
            if (wasMusicPlaying) musicPlayer.play()
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(p: Boolean) { isPlaying = p }
            override fun onPlaybackStateChanged(state: Int) { buffering = state == Player.STATE_BUFFERING }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(video.id) {
        while (true) {
            if (!dragging) {
                pos = runCatching { player.currentPosition }.getOrDefault(pos)
                if (player.duration > 0) dur = player.duration
            }
            delay(300)
        }
    }
    LaunchedEffect(showControls, pos, isPlaying) {
        if (showControls && isPlaying) { delay(4000); showControls = false }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val portraitW = maxWidth
        val portraitH = maxHeight
        Box(
            Modifier
                .align(Alignment.Center)
                .size(width = portraitH, height = portraitW)
                .graphicsLayer { rotationZ = VIDEO_ROTATION_DEG }
        ) {
            AndroidView(
                factory = { c -> TextureView(c).apply { player.setVideoTextureView(this) } },
                modifier = Modifier.fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures(onTap = { showControls = !showControls }) }
            )

            if (buffering) {
                CircularProgressIndicator(color = HioneTealBright, modifier = Modifier.align(Alignment.Center).size(40.dp))
            }

            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn(), exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopStart).fillMaxWidth().padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(38.dp).clip(CircleShape).background(Mascot.color(0xCC041416))
                            .pointerInput(Unit) { detectTapGestures(onTap = { onClose() }) },
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Default.Close, "Close", tint = Color.White, modifier = Modifier.size(20.dp)) }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        video.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                    )
                }
            }

            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn(), exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            ) {
                Column(
                    Modifier.fillMaxWidth()
                        .background(Mascot.color(0xCC041416), RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Slider(
                        value = pos.toFloat().coerceIn(0f, dur.toFloat()),
                        onValueChange = { dragging = true; pos = it.toLong() },
                        onValueChangeFinished = { player.seekTo(pos); dragging = false },
                        valueRange = 0f..dur.toFloat().coerceAtLeast(1f),
                        colors = SliderDefaults.colors(thumbColor = HioneTealBright, activeTrackColor = HioneTeal, inactiveTrackColor = Mascot.color(0xFF1A3A3D))
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatMs(pos), color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp)
                        Text(formatMs(dur), color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp)
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(48.dp).clip(CircleShape)
                                .pointerInput(Unit) { detectTapGestures(onTap = { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)) }) },
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Default.Replay10, "Back 10s", tint = Color.White, modifier = Modifier.size(28.dp)) }
                        Spacer(Modifier.width(28.dp))
                        Box(
                            Modifier.size(64.dp).clip(CircleShape).background(HioneTeal)
                                .pointerInput(Unit) { detectTapGestures(onTap = { if (player.isPlaying) player.pause() else player.play() }) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/Pause", tint = Mascot.color(0xFF00201D), modifier = Modifier.size(34.dp))
                        }
                        Spacer(Modifier.width(28.dp))
                        Box(
                            Modifier.size(48.dp).clip(CircleShape)
                                .pointerInput(Unit) { detectTapGestures(onTap = { player.seekTo(player.currentPosition + 10_000) }) },
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Default.Forward10, "Forward 10s", tint = Color.White, modifier = Modifier.size(28.dp)) }
                    }
                }
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600; val m = (totalSec % 3600) / 60; val s = totalSec % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
}

/** Simple list — title, duration, resolution, tap to play. No thumbnails yet (v1: get playback
 *  working well first, thumbnails are a pure visual add-on for later). */
@Composable
fun VideoLibraryScreen(videos: List<VideoItem>, onOpen: (VideoItem) -> Unit, onClose: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(Ground)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                HapticIconButton(onClick = onClose, flat = true) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = HioneTealBright, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text("Videos", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
            }
            if (videos.isEmpty()) {
                HioneEmptyState(
                    title = "NO VIDEOS FOUND",
                    subtitle = "No video files in your library",
                    imageRes = HioneArt.chibiHearts,
                    modifier = Modifier.fillMaxSize().wrapContentSize(Alignment.Center)
                )
            } else {
                androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    items(videos.size, key = { videos[it].id }) { i ->
                        val v = videos[i]
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp).glassCard(corner = 14.dp)
                                .clickable { onOpen(v) }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(Mascot.color(0xFF123438)),
                                contentAlignment = Alignment.Center
                            ) { Icon(Icons.Default.PlayArrow, null, tint = HioneTeal.copy(alpha = .8f), modifier = Modifier.size(28.dp)) }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(v.title, color = Mascot.color(0xFFE8F4F2), fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val res = if (v.width > 0 && v.height > 0) "${v.width}×${v.height} · " else ""
                                Text("$res${formatMs(v.durationMs)}", color = Muted, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
