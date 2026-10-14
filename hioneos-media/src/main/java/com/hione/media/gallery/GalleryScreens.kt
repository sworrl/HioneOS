package com.hione.media.gallery

import com.hione.media.Mascot
import android.app.Activity
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.media.camera.CameraActivity
import com.hione.media.ui.BareIconButton
import com.hione.media.ui.GlassButton
import com.hione.media.ui.GlassIconButton
import com.hione.media.ui.GlassSegments
import com.hione.media.ui.LabelStyle
import com.hione.media.ui.MediaPerms
import com.hione.media.ui.MessagePane
import com.hione.media.ui.HioneHeader
import com.hione.media.ui.HioneSounds
import com.hione.media.ui.SoundSettingsSheet
import com.hione.media.ui.HionePink
import com.hione.media.ui.HioneSurface2
import com.hione.media.ui.HioneTeal
import com.hione.media.ui.HioneWhite
import com.hione.media.ui.TitleStyle
import com.hione.media.ui.formatDuration
import com.hione.media.ui.glass
import com.hione.media.ui.hioneBackground
import com.hione.media.ui.pressable
import com.hione.media.ui.rememberPermissionGate
import kotlinx.coroutines.flow.collectLatest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@Composable
fun GalleryApp(mode: GalleryMode, onPicked: (List<Uri>) -> Unit) {
    val ctx = LocalContext.current
    val picking = mode is GalleryMode.Pick
    val filter = (mode as? GalleryMode.Pick)?.filter ?: MediaFilter.ALL
    val multiPick = (mode as? GalleryMode.Pick)?.multiple == true

    val gate = rememberPermissionGate(MediaPerms.visual)
    LaunchedEffect(Unit) {
        HioneSounds.preload(ctx, "gallery")
        if (!MediaPerms.anyVisual(ctx)) gate.request()
    }
    var showSettings by remember { mutableStateOf(false) }

    var items by remember { mutableStateOf<List<MediaItem>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(gate.version, reload, filter) {
        items = if (MediaPerms.anyVisual(ctx)) MediaRepo.query(ctx, filter) else emptyList()
    }
    LaunchedEffect(Unit) { MediaRepo.changes(ctx).collectLatest { reload++ } }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var album by remember { mutableStateOf<Album?>(null) }
    var viewer by remember { mutableStateOf<Pair<List<MediaItem>, Int>?>(null) }
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }

    val deleter = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            selected = emptySet()
            MediaActions.deletedCue()
        }
    }

    val all = items
    val shown: List<MediaItem> = when {
        all == null -> emptyList()
        album != null -> all.filter { it.bucketId == album!!.bucketId }
        else -> all
    }

    BackHandler(enabled = selected.isNotEmpty() || album != null) {
        if (selected.isNotEmpty()) selected = emptySet() else album = null
    }

    fun onItemClick(list: List<MediaItem>, item: MediaItem) {
        when {
            picking && !multiPick -> onPicked(listOf(item.uri))
            selected.isNotEmpty() || (picking && multiPick) ->
                selected = if (item.id in selected) selected - item.id else selected + item.id
            else -> viewer = list to list.indexOf(item)
        }
    }

    Box(Modifier.fillMaxSize().hioneBackground()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // Header
            when {
                selected.isNotEmpty() -> HioneHeader(
                    title = "${selected.size} selected",
                    leading = { BareIconButton(Icons.Rounded.Close, "Clear selection") { selected = emptySet() } },
                    actions = {
                        if (picking) {
                            GlassButton("Done", filled = true, modifier = Modifier.padding(end = 4.dp)) {
                                val byId = all.orEmpty().associateBy { it.id }
                                onPicked(selected.mapNotNull { byId[it]?.uri })
                            }
                        } else {
                            val sel = all.orEmpty().filter { it.id in selected }
                            BareIconButton(Icons.Rounded.Share, "Share") { MediaActions.share(ctx, sel) }
                            BareIconButton(Icons.Rounded.Delete, "Delete", tint = HionePink) {
                                MediaActions.deleteRequest(ctx, sel.map { it.uri })?.let {
                                    deleter.launch(IntentSenderRequest.Builder(it).build())
                                }
                            }
                        }
                    }
                )
                album != null -> HioneHeader(
                    title = album!!.name,
                    subtitle = "${shown.size} items",
                    leading = { BareIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back") { album = null } }
                )
                else -> HioneHeader(
                    title = if (picking) pickTitle(filter, multiPick) else "Gallery",
                    leading = if (picking) ({ BareIconButton(Icons.Rounded.Close, "Cancel") { onPicked(emptyList()) } }) else null,
                    actions = {
                        if (!picking) {
                            BareIconButton(Icons.Rounded.PhotoCamera, "Open camera", tint = HioneTeal) {
                                ctx.startActivity(android.content.Intent(ctx, CameraActivity::class.java))
                            }
                            BareIconButton(Icons.Rounded.Settings, "Sound settings") { showSettings = true }
                        }
                    }
                )
            }

            if (MediaPerms.partialVisual(ctx)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).glass(16.dp, HionePink).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Showing only the photos you picked.", color = HioneWhite, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    GlassButton("Change", onClick = gate.request)
                }
            }

            Box(Modifier.weight(1f)) {
                when {
                    !MediaPerms.anyVisual(ctx) -> MessagePane(
                        Icons.Rounded.PhotoLibrary,
                        "Photos are locked",
                        "Allow access to photos and videos so the gallery can show them.",
                        "Allow"
                    ) { gate.request() }
                    all == null -> {}
                    all.isEmpty() -> MessagePane(
                        Icons.Rounded.PhotoLibrary,
                        "Nothing here yet",
                        "Photos and videos you take or save will show up here."
                    )
                    album != null -> MediaGrid(shown, headers = false, selected = selected, selecting = selected.isNotEmpty() || multiPick,
                        onClick = { onItemClick(shown, it) },
                        onLong = { if (!picking || multiPick) selected = selected + it.id })
                    tab == 0 -> MediaGrid(shown, headers = true, selected = selected, selecting = selected.isNotEmpty() || multiPick,
                        onClick = { onItemClick(shown, it) },
                        onLong = { if (!picking || multiPick) selected = selected + it.id })
                    else -> AlbumGrid(MediaRepo.albums(all)) { album = it }
                }

                if (album == null && selected.isEmpty() && !all.isNullOrEmpty()) {
                    GlassSegments(
                        listOf("Photos", "Albums"),
                        tab,
                        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 14.dp)
                    ) { tab = it }
                }
            }
        }

        viewer?.let { (list, idx) ->
            ViewerScreen(list, idx, secure = false, onClose = { viewer = null })
        }
        if (showSettings) {
            SoundSettingsSheet("gallery", "Gallery", "wallpaper_set", showTimerCue = false) { showSettings = false }
        }
    }
}

private fun pickTitle(f: MediaFilter, multi: Boolean): String {
    val what = when (f) {
        MediaFilter.IMAGES -> if (multi) "photos" else "a photo"
        MediaFilter.VIDEOS -> if (multi) "videos" else "a video"
        MediaFilter.ALL -> if (multi) "items" else "an item"
    }
    return "Pick $what"
}

private sealed interface GridRow {
    data class Header(val label: String) : GridRow
    data class Cell(val item: MediaItem) : GridRow
}

private val monthFmt = SimpleDateFormat("MMMM yyyy", Locale.getDefault())

private fun withHeaders(list: List<MediaItem>): List<GridRow> {
    val out = ArrayList<GridRow>(list.size + 24)
    val cal = Calendar.getInstance()
    var lastKey = -1
    for (it in list) {
        cal.timeInMillis = it.dateTaken
        val key = cal.get(Calendar.YEAR) * 12 + cal.get(Calendar.MONTH)
        if (key != lastKey) {
            out += GridRow.Header(monthFmt.format(cal.time))
            lastKey = key
        }
        out += GridRow.Cell(it)
    }
    return out
}

@Composable
private fun MediaGrid(
    list: List<MediaItem>,
    headers: Boolean,
    selected: Set<Long>,
    selecting: Boolean,
    onClick: (MediaItem) -> Unit,
    onLong: (MediaItem) -> Unit
) {
    val rows = remember(list, headers) { if (headers) withHeaders(list) else list.map { GridRow.Cell(it) } }
    val state = rememberLazyGridState()
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 2.dp, end = 2.dp, bottom = 90.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items(
            rows,
            key = { r -> when (r) { is GridRow.Header -> "h:${r.label}"; is GridRow.Cell -> r.item.id } },
            span = { r -> if (r is GridRow.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
            contentType = { r -> if (r is GridRow.Header) 0 else 1 }
        ) { r ->
            when (r) {
                is GridRow.Header -> Text(
                    r.label,
                    style = TitleStyle.copy(fontSize = 13.sp, color = HioneTeal),
                    modifier = Modifier.padding(start = 10.dp, top = 14.dp, bottom = 6.dp)
                )
                is GridRow.Cell -> ThumbCell(
                    r.item,
                    selected = r.item.id in selected,
                    selecting = selecting,
                    onClick = { onClick(r.item) },
                    onLong = { onLong(r.item) }
                )
            }
        }
    }
}

@Composable
fun ThumbCell(
    item: MediaItem,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLong: () -> Unit
) {
    val ctx = LocalContext.current
    var bmp by remember(item.id, item.dateModified) { mutableStateOf(Thumbs.cached(item, GRID_THUMB_PX)?.asImageBitmap()) }
    if (bmp == null) {
        LaunchedEffect(item.id, item.dateModified) {
            bmp = Thumbs.load(ctx, item, GRID_THUMB_PX)?.asImageBitmap()
        }
    }
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .background(HioneSurface2)
            .pressable(onLongClick = onLong, onClick = onClick)
    ) {
        bmp?.let { ThumbImage(it, Modifier.fillMaxSize().padding(if (selected) 8.dp else 0.dp).clip(RoundedCornerShape(if (selected) 8.dp else 0.dp))) }
        if (item.isVideo) {
            Row(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.PlayArrow, null, tint = HioneWhite, modifier = Modifier.size(14.dp))
                Text(formatDuration(item.durationMs), color = HioneWhite, fontSize = 11.sp)
            }
        }
        if (selecting) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp)
                    .clip(CircleShape)
                    .background(if (selected) HioneTeal else Color.Black.copy(alpha = 0.35f))
                    .border(1.5.dp, HioneWhite, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (selected) Icon(Icons.Rounded.CheckCircle, null, tint = Mascot.color(0xFF00201D), modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun ThumbImage(bmp: ImageBitmap, modifier: Modifier) {
    Image(bmp, null, contentScale = ContentScale.Crop, modifier = modifier)
}

@Composable
private fun AlbumGrid(albums: List<Album>, onOpen: (Album) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 90.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(albums, key = { it.bucketId }) { a ->
            val ctx = LocalContext.current
            var bmp by remember(a.cover.id, a.cover.dateModified) { mutableStateOf(Thumbs.cached(a.cover, ALBUM_THUMB_PX)?.asImageBitmap()) }
            if (bmp == null) LaunchedEffect(a.cover.id) { bmp = Thumbs.load(ctx, a.cover, ALBUM_THUMB_PX)?.asImageBitmap() }
            Column(Modifier.pressable { onOpen(a) }.glass(16.dp).padding(6.dp)) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(HioneSurface2)) {
                    bmp?.let { ThumbImage(it, Modifier.fillMaxSize()) }
                }
                Spacer(Modifier.height(6.dp))
                Text(a.name, color = HioneWhite, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
                Text("${a.count}", style = LabelStyle, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
            }
        }
    }
}

private const val ALBUM_THUMB_PX = 320
