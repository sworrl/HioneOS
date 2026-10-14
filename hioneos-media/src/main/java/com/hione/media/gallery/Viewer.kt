package com.hione.media.gallery

import android.Manifest
import android.app.Activity
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.view.SurfaceView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.hione.media.ui.BareIconButton
import com.hione.media.ui.GlassIconButton
import com.hione.media.ui.LabelStyle
import com.hione.media.ui.HioneMuted
import com.hione.media.ui.HionePink
import com.hione.media.ui.HioneTeal
import com.hione.media.ui.HioneWhite
import com.hione.media.ui.TitleStyle
import com.hione.media.ui.formatBytes
import com.hione.media.ui.formatDuration
import com.hione.media.ui.glass
import com.hione.media.ui.hasPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import androidx.media3.common.MediaItem as ExoItem

/**
 * Full-screen viewer shared by the gallery grid and every external VIEW/REVIEW intent.
 *
 * [secure] is the lock-screen review mode: no share, edit, wallpaper or delete, because each of
 * those either opens another app over the keyguard or changes data without the user unlocking.
 */
@Composable
fun ViewerScreen(
    initial: List<MediaItem>,
    startIndex: Int,
    secure: Boolean,
    onClose: () -> Unit
) {
    val ctx = LocalContext.current
    var items by remember(initial) { mutableStateOf(initial) }
    if (items.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val pager = rememberPagerState(startIndex.coerceIn(0, items.lastIndex)) { items.size }
    var chrome by remember { mutableStateOf(true) }
    var info by remember { mutableStateOf(false) }
    val current = items.getOrNull(pager.currentPage) ?: items.last()

    var pendingDelete by remember { mutableStateOf<MediaItem?>(null) }
    val deleter = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val gone = pendingDelete
        pendingDelete = null
        if (r.resultCode == Activity.RESULT_OK && gone != null) {
            MediaActions.deletedCue()
            items = items.filterNot { it.uri == gone.uri }
            if (items.isEmpty()) onClose()
        }
    }

    BackHandler {
        if (info) info = false else onClose()
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
            key = { items[it].uri.toString() }
        ) { page ->
            val item = items[page]
            val isCurrent = pager.currentPage == page && !pager.isScrollInProgress
            if (item.isVideo) {
                VideoPage(item, isCurrent = pager.currentPage == page, chrome = chrome, onTap = { chrome = !chrome })
            } else {
                ImagePage(item, isCurrent = isCurrent, onTap = { chrome = !chrome })
            }
        }

        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = onClose)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(current.name, color = HioneWhite, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (current.dateTaken > 0) {
                        Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(current.dateTaken)), style = LabelStyle, maxLines = 1)
                    }
                }
                if (items.size > 1) Text("${pager.currentPage + 1} / ${items.size}", style = LabelStyle)
            }
        }

        AnimatedVisibility(
            chrome && !info,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Row(
                Modifier
                    .navigationBarsPadding()
                    .padding(12.dp)
                    .glass(28.dp)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (!secure) {
                    BareIconButton(Icons.Rounded.Share, "Share") { MediaActions.share(ctx, listOf(current)) }
                    if (!current.isVideo) {
                        BareIconButton(Icons.Rounded.Crop, "Edit") { MediaActions.openEditor(ctx, current.uri) }
                        BareIconButton(Icons.Rounded.Wallpaper, "Set as wallpaper") { MediaActions.openWallpaper(ctx, current.uri) }
                    }
                }
                BareIconButton(Icons.Rounded.Info, "Info") { info = true }
                if (!secure && MediaActions.isMediaStore(current.uri)) {
                    BareIconButton(Icons.Rounded.Delete, "Delete", tint = HionePink) {
                        val req = MediaActions.deleteRequest(ctx, listOf(current.uri))
                        if (req != null) {
                            pendingDelete = current
                            deleter.launch(IntentSenderRequest.Builder(req).build())
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            info,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            InfoPanel(current, onClose = { info = false })
        }
    }
}

@Composable
private fun ImagePage(item: MediaItem, isCurrent: Boolean, onTap: () -> Unit) {
    val ctx = LocalContext.current
    // Grid thumbnail first (usually already cached), then the full decode replaces it. That way a
    // swipe never shows an empty black page while the next photo decodes.
    val thumb = remember(item.uri) { if (item.id >= 0) Thumbs.cached(item, GRID_THUMB_PX)?.asImageBitmap() else null }
    val full by produceState<ImageBitmap?>(null, item.uri, item.dateModified) {
        value = MediaActions.decodeImageBitmap(ctx, item.uri)
    }
    val shown = full ?: thumb
    if (shown == null) {
        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) })
        return
    }
    ZoomableImage(shown, isCurrent, onTap)
}

/**
 * Pinch, pan and double-tap zoom that cooperates with the pager. At 1x a one-finger drag is left
 * unconsumed so the pager swipes; zoomed in, drags pan the photo, and once the photo's edge is
 * reached a fresh drag in that direction is left to the pager again, which is how stock
 * galleries feel.
 */
@Composable
private fun ZoomableImage(bitmap: ImageBitmap, isCurrent: Boolean, onTap: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(isCurrent) {
        if (!isCurrent) { scale = 1f; offset = Offset.Zero }
    }

    fun fitted(): Pair<Float, Float> {
        if (box.width == 0 || box.height == 0) return 0f to 0f
        val s = min(box.width / bitmap.width.toFloat(), box.height / bitmap.height.toFloat())
        return bitmap.width * s to bitmap.height * s
    }

    fun clamp(o: Offset, s: Float): Offset {
        val (fw, fh) = fitted()
        val mx = max(0f, (fw * s - box.width) / 2f)
        val my = max(0f, (fh * s - box.height) / 2f)
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { box = it }
            .pointerInput(bitmap) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { p ->
                        if (scale > 1.05f) {
                            scale = 1f; offset = Offset.Zero
                        } else {
                            val ns = 2.5f
                            val c = p - Offset(box.width / 2f, box.height / 2f)
                            offset = clamp(-c * (ns - 1f), ns)
                            scale = ns
                        }
                    }
                )
            }
            .pointerInput(bitmap) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val ev = awaitPointerEvent()
                        val fingers = ev.changes.count { it.pressed }
                        if (fingers >= 2 || scale > 1.01f) {
                            val zoom = ev.calculateZoom()
                            val pan = ev.calculatePan()
                            val c = ev.calculateCentroid(useCurrent = true) - Offset(box.width / 2f, box.height / 2f)
                            val ns = (scale * zoom).coerceIn(1f, 8f)
                            val raw = (offset - c) * (ns / scale) + c + pan
                            val clamped = clamp(raw, ns)
                            val pushingPastEdge = fingers < 2 && abs(raw.x - clamped.x) > 0.5f && abs(pan.x) > abs(pan.y)
                            scale = ns
                            offset = clamped
                            if (!pushingPastEdge) {
                                ev.changes.forEach { if (it.positionChange() != Offset.Zero) it.consume() }
                            }
                        }
                    } while (ev.changes.any { it.pressed })
                    if (scale < 1.02f) { scale = 1f; offset = Offset.Zero }
                }
            }
    ) {
        Image(
            bitmap,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale; scaleY = scale
                translationX = offset.x; translationY = offset.y
            }
        )
    }
}

/**
 * Video page. A player exists only for the page on screen, so swiping through an album full of
 * clips never holds more than one hardware decoder (this SoC has few). The SurfaceView path is
 * used instead of a TextureView: it skips a GPU copy per frame.
 */
@Composable
private fun VideoPage(item: MediaItem, isCurrent: Boolean, chrome: Boolean, onTap: () -> Unit) {
    val ctx = LocalContext.current
    val thumb by produceState<ImageBitmap?>(null, item.uri) {
        value = (if (item.id >= 0) Thumbs.load(ctx, item, GRID_THUMB_PX) else Thumbs.loadUri(ctx, item.uri, 512))?.asImageBitmap()
    }
    if (!isCurrent) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            thumb?.let { Image(it, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
            Icon48(Icons.Rounded.PlayArrow)
        }
        return
    }

    val player = remember(item.uri) {
        ExoPlayer.Builder(ctx).build().apply {
            setAudioAttributes(AudioAttributes.DEFAULT, true)
            setMediaItem(ExoItem.fromUri(item.uri))
            prepare()
            playWhenReady = true
        }
    }
    var playing by remember { mutableStateOf(true) }
    var aspect by remember { mutableFloatStateOf(if (item.width > 0 && item.height > 0) item.width / item.height.toFloat() else 16f / 9f) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(item.durationMs) }
    var started by remember { mutableStateOf(false) }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(player, owner) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                }
            }
            override fun onRenderedFirstFrame() { started = true }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && player.duration > 0) dur = player.duration
                if (state == Player.STATE_ENDED) { player.pause(); player.seekTo(0) }
            }
        }
        player.addListener(l)
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_PAUSE) player.pause() }
        owner.lifecycle.addObserver(obs)
        onDispose {
            owner.lifecycle.removeObserver(obs)
            player.removeListener(l)
            player.release()
        }
    }
    LaunchedEffect(player, playing) {
        while (true) {
            pos = player.currentPosition
            if (!playing) break
            delay(250)
        }
    }

    Box(
        Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) },
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { c -> SurfaceView(c).also { player.setVideoSurfaceView(it) } },
            modifier = Modifier.fillMaxWidth().aspectRatio(aspect.coerceIn(0.2f, 5f))
        )
        if (!started) thumb?.let { Image(it, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut()) {
            GlassIconButton(
                if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                if (playing) "Pause" else "Play",
                size = 72.dp
            ) { if (player.isPlaying) player.pause() else player.play() }
        }
        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Row(
                Modifier
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 84.dp)
                    .glass(20.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(formatDuration(pos), style = LabelStyle.copy(color = HioneWhite))
                Slider(
                    value = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f,
                    onValueChange = { f -> if (dur > 0) { pos = (f * dur).toLong(); player.seekTo(pos) } },
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    colors = SliderDefaults.colors(thumbColor = HioneTeal, activeTrackColor = HioneTeal, inactiveTrackColor = HioneMuted.copy(alpha = 0.4f))
                )
                Text(formatDuration(dur), style = LabelStyle.copy(color = HioneWhite))
            }
        }
    }
}

@Composable
private fun Icon48(v: androidx.compose.ui.graphics.vector.ImageVector) {
    Box(Modifier.size(72.dp).glass(36.dp), contentAlignment = Alignment.Center) {
        androidx.compose.material3.Icon(v, null, tint = HioneWhite, modifier = Modifier.size(36.dp))
    }
}

private data class InfoRow(val label: String, val value: String)

@Composable
private fun InfoPanel(item: MediaItem, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val rows by produceState<List<InfoRow>>(emptyList(), item.uri) { value = loadInfo(ctx, item) }
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(10.dp)
            .glass(24.dp, fillAlpha = 0.85f)
            .padding(16.dp)
            .height(320.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("DETAILS", style = TitleStyle.copy(fontSize = 15.sp), modifier = Modifier.weight(1f))
            BareIconButton(Icons.Rounded.Close, "Close details", tint = HioneTeal, onClick = onClose)
        }
        Spacer(Modifier.height(4.dp))
        Column(Modifier.verticalScroll(rememberScrollState())) {
            rows.forEach { r ->
                Row(Modifier.padding(vertical = 5.dp)) {
                    Text(r.label, style = LabelStyle, modifier = Modifier.width(110.dp))
                    Text(r.value, color = HioneWhite, fontSize = 14.sp)
                }
            }
        }
    }
}

private suspend fun loadInfo(ctx: Context, item: MediaItem): List<InfoRow> = withContext(Dispatchers.IO) {
    val out = ArrayList<InfoRow>()
    out += InfoRow("Name", item.name)
    if (item.dateTaken > 0) out += InfoRow("Date", DateFormat.getDateTimeInstance().format(Date(item.dateTaken)))
    val size = if (item.size > 0) item.size else MediaActions.sizeOf(ctx, item.uri)
    if (size > 0) out += InfoRow("Size", formatBytes(size))
    if (item.width > 0) out += InfoRow("Resolution", "${item.width} x ${item.height}")
    if (item.isVideo && item.durationMs > 0) out += InfoRow("Length", formatDuration(item.durationMs))
    out += InfoRow("Type", item.mime)
    if (item.relativePath.isNotEmpty()) out += InfoRow("Folder", item.relativePath.trimEnd('/'))

    if (!item.isVideo) {
        // MediaStore redacts GPS from the stream unless we ask for the original, which needs
        // ACCESS_MEDIA_LOCATION. Without it the rest of the EXIF still reads fine.
        val src: Uri = if (MediaActions.isMediaStore(item.uri) && ctx.hasPermission(Manifest.permission.ACCESS_MEDIA_LOCATION)) {
            runCatching { MediaStore.setRequireOriginal(item.uri) }.getOrDefault(item.uri)
        } else item.uri
        runCatching {
            ctx.contentResolver.openInputStream(src)?.use { ins ->
                val ex = ExifInterface(ins)
                if (item.width <= 0) {
                    val w = ex.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                    val h = ex.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
                    if (w > 0) out += InfoRow("Resolution", "$w x $h")
                }
                val make = ex.getAttribute(ExifInterface.TAG_MAKE)?.trim()
                val model = ex.getAttribute(ExifInterface.TAG_MODEL)?.trim()
                listOfNotNull(make, model).joinToString(" ").takeIf { it.isNotBlank() }?.let { out += InfoRow("Camera", it) }
                ex.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0).takeIf { it > 0 }?.let { out += InfoRow("Aperture", "f/%.1f".format(it)) }
                ex.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0).takeIf { it > 0 }?.let {
                    out += InfoRow("Exposure", if (it < 1) "1/${(1 / it).toInt()} s" else "%.1f s".format(it))
                }
                ex.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0).takeIf { it > 0 }?.let { out += InfoRow("ISO", it.toString()) }
                ex.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0).takeIf { it > 0 }?.let { out += InfoRow("Focal length", "%.1f mm".format(it)) }
                when (ex.getAttributeInt(ExifInterface.TAG_FLASH, -1)) {
                    -1 -> {}
                    else -> out += InfoRow("Flash", if (ex.getAttributeInt(ExifInterface.TAG_FLASH, 0) and 1 == 1) "Fired" else "Off")
                }
                ex.latLong?.let { (lat, lon) -> out += InfoRow("Location", "%.5f, %.5f".format(lat, lon)) }
            }
        }
    }
    out
}

const val GRID_THUMB_PX = 224
