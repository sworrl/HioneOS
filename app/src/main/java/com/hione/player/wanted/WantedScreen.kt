package com.hione.player.wanted

import com.hione.player.Mascot
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.player.HioneGold
import com.hione.player.HionePink
import com.hione.player.HioneTeal
import com.hione.player.HioneTealBright
import com.hione.player.Muted
import com.hione.player.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** libSel key MainActivity routes to [WantedScreen]. Leading space so it can never collide with a playlist name. */
const val WANTED_LIB_KEY = " WANTED"

private val TextMain get() = Mascot.color(0xFFE8F4F2)
private val CardBg get() = Mascot.color(0xFF0C2629)

private fun statusColor(s: WantedStatus): Color = when (s) {
    WantedStatus.WANTED -> HioneGold
    WantedStatus.IN_LIBRARY -> HioneTeal
    WantedStatus.ACQUIRED -> HionePink
    WantedStatus.DISMISSED -> Muted
}

// ===================================================================== entry points

/** Library tab row under "Liked Songs", with a count of songs still wanted. */
@Composable
fun WantedLibRow(onClick: () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { WantedStore.ensureLoaded(ctx) }
    val items by WantedStore.items.collectAsState()
    val wanted = items.count { it.status == WantedStatus.WANTED }
    val sub = when {
        items.isEmpty() -> "Heart a song on the FM radio to add it here"
        wanted == 0 -> "${items.size} radio likes, all in your library"
        else -> "$wanted liked on the radio, not in your library"
    }
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)).background(Mascot.color(0xFF123438)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Radio, null, tint = HioneGold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Wanted", color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(sub, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (wanted > 0) {
            Box(
                Modifier.clip(RoundedCornerShape(10.dp)).background(HioneGold).padding(horizontal = 8.dp, vertical = 2.dp)
            ) { Text("$wanted", color = Color(0xFF2A1F00), fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(6.dp))
        }
        Icon(Icons.Default.ChevronRight, null, tint = Muted)
    }
}

/** Footer for the Liked Songs list: radio likes the library does not have. Renders nothing when there are none. */
@Composable
fun WantedNotInLibrarySection(onOpenAll: () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { WantedStore.ensureLoaded(ctx) }
    val items by WantedStore.items.collectAsState()
    val wanted = items.filter { it.status == WantedStatus.WANTED }
    if (wanted.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Not in your library", color = HioneGold, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("${wanted.size}", color = Muted, fontSize = 13.sp)
        }
        Text("Liked on the FM radio. Get these and they move up into your liked songs.",
            color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        wanted.take(5).forEach { w ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpenAll() }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                WantedCover(w.coverUrl, 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(w.title, color = TextMain, fontSize = 14.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(w.artist, color = Muted, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(WantedStore.heardLine(w), color = Muted.copy(alpha = 0.8f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Text(
            if (wanted.size > 5) "See all ${wanted.size}" else "Open the wanted list",
            color = HioneTealBright, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.clickable { onOpenAll() }.padding(horizontal = 16.dp, vertical = 10.dp)
        )
    }
}

// ===================================================================== the screen

private enum class Filter(val label: String, val status: WantedStatus?) {
    WANTED("Wanted", WantedStatus.WANTED),
    IN_LIBRARY("In library", WantedStatus.IN_LIBRARY),
    ACQUIRED("Acquired", WantedStatus.ACQUIRED),
    DISMISSED("Dismissed", WantedStatus.DISMISSED),
    ALL("All", null),
}

@Composable
fun WantedScreen(
    tracks: List<Track>,
    onBack: () -> Unit,
    onPlay: (List<Track>, Int) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        WantedStore.refreshAsync(ctx)
        withContext(Dispatchers.IO) { runCatching { WantedStore.fillPlaceNames(ctx) } }
    }
    val items by WantedStore.items.collectAsState()
    var filter by remember { mutableStateOf<Filter?>(null) }
    val effective = filter ?: if (items.any { it.status == WantedStatus.WANTED }) Filter.WANTED else Filter.ALL
    val shown = remember(items, effective) { items.filter { effective.status == null || it.status == effective.status } }
    var expanded by remember { mutableStateOf<String?>(null) }
    var shareMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<WantedItem?>(null) }
    val byId = remember(tracks) { tracks.associateBy { it.id } }
    // Built on first "Find in library" tap, not on open: folding 17k titles is not free.
    var index by remember(tracks) { mutableStateOf<WantedMatch.Index?>(null) }
    var found by remember { mutableStateOf<Map<String, List<Track>>>(emptyMap()) }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(start = 4.dp, end = 8.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = HioneTealBright) }
                Column(Modifier.weight(1f)) {
                    Text("Wanted", color = HioneTealBright, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Songs you liked on the FM radio", color = Muted, fontSize = 12.sp)
                }
                Box {
                    IconButton(onClick = { shareMenu = true }, enabled = items.isNotEmpty()) {
                        Icon(Icons.Default.Share, "Share", tint = if (items.isNotEmpty()) HioneTealBright else Muted)
                    }
                    DropdownMenu(expanded = shareMenu, onDismissRequest = { shareMenu = false }) {
                        DropdownMenuItem(text = { Text("Share wanted list") }, onClick = {
                            shareMenu = false
                            val list = items.filter { it.status == WantedStatus.WANTED }.ifEmpty { items }
                            shareText(ctx, WantedStore.exportText(list))
                        })
                        DropdownMenuItem(text = { Text("Export everything as CSV") }, onClick = {
                            shareMenu = false
                            scope.launch {
                                val f = withContext(Dispatchers.IO) { runCatching { WantedStore.exportCsv(ctx, items) }.getOrNull() }
                                if (f != null) shareCsv(ctx, f) else shareText(ctx, WantedStore.exportText(items))
                            }
                        })
                    }
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Filter.values().forEach { f ->
                    val n = if (f.status == null) items.size else items.count { it.status == f.status }
                    val sel = f == effective
                    Box(
                        Modifier.clip(RoundedCornerShape(16.dp))
                            .background(if (sel) HioneTeal.copy(alpha = 0.25f) else Mascot.color(0xFF0F2C30))
                            .border(1.dp, if (sel) HioneTeal else Mascot.color(0xFF1E4A4F), RoundedCornerShape(16.dp))
                            .clickable { filter = f }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) { Text("${f.label} $n", color = if (sel) HioneTealBright else Muted, fontSize = 13.sp) }
                }
            }
        }
        if (shown.isEmpty()) item {
            Text(
                if (items.isEmpty()) "Nothing yet. When the FM radio names a song, tap its heart and it shows up here with the station, the time and where you were."
                else "Nothing in this list.",
                color = Muted, fontSize = 14.sp, modifier = Modifier.padding(24.dp)
            )
        }
        items(shown, key = { it.id }) { w ->
            val open = expanded == w.id
            val matched = w.matchedTrackId?.let { byId[it] }
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp)).background(if (open) CardBg else Color.Transparent)
                    .clickable { expanded = if (open) null else w.id }
                    .padding(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WantedCover(w.coverUrl, 56.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(w.title, color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(w.artist, w.album).joinToString(" · "), color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(WantedStore.heardLine(w), color = Muted.copy(alpha = 0.85f), fontSize = 11.5.sp, maxLines = if (open) 3 else 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(8.dp))
                    StatusChip(w.status)
                }
                if (open) {
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (matched != null) ActionChip("Play", HioneTeal) { onPlay(listOf(matched), 0) }
                        w.webUrl?.let { url -> ActionChip("Open link", HioneTealBright) { openUrl(ctx, url) } }
                        ActionChip("Find in library", HioneTealBright) {
                            scope.launch {
                                val idx = index ?: withContext(Dispatchers.Default) { WantedMatch.Index(tracks) }.also { index = it }
                                val hits = withContext(Dispatchers.Default) { idx.search(tracks, w.title, w.artist) }
                                found = found + (w.id to hits)
                            }
                        }
                        if (w.status == WantedStatus.WANTED) ActionChip("Mark acquired", HionePink) { WantedStore.setStatus(ctx, w.id, WantedStatus.ACQUIRED) }
                        if (w.status == WantedStatus.ACQUIRED || w.status == WantedStatus.DISMISSED)
                            ActionChip("Want it again", HioneGold) { WantedStore.setStatus(ctx, w.id, WantedStatus.WANTED) }
                        if (w.status != WantedStatus.DISMISSED) ActionChip("Dismiss", Muted) { WantedStore.setStatus(ctx, w.id, WantedStatus.DISMISSED) }
                        ActionChip("Delete", Color(0xFFFF6B6B)) { confirmDelete = w }
                    }
                    found[w.id]?.let { hits ->
                        Spacer(Modifier.height(6.dp))
                        if (hits.isEmpty()) {
                            Text("No match in your library.", color = Muted, fontSize = 12.5.sp, modifier = Modifier.padding(4.dp))
                        } else hits.forEach { t ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f).clickable { onPlay(listOf(t), 0) }.padding(4.dp)) {
                                    Text(t.title, color = TextMain, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(listOf(t.artist, t.album).filter { it.isNotBlank() }.joinToString(" · "), color = Muted, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (w.matchedTrackId != t.id) ActionChip("This is it", HioneTeal) {
                                    WantedStore.link(ctx, w.id, t)
                                    found = found - w.id
                                }
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(96.dp)) }
    }

    confirmDelete?.let { w ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this song?") },
            text = { Text("${w.artist} - ${w.title} comes off the list. Liking it on the radio again adds it back.") },
            confirmButton = { TextButton(onClick = { WantedStore.delete(ctx, w.id); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StatusChip(s: WantedStatus) {
    val c = statusColor(s)
    Box(
        Modifier.clip(RoundedCornerShape(8.dp)).background(c.copy(alpha = 0.16f))
            .border(1.dp, c.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) { Text(s.label, color = c, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
}

@Composable
private fun ActionChip(label: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(14.dp)).border(1.dp, color.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
            .clickable { onClick() }.padding(horizontal = 11.dp, vertical = 6.dp)
    ) { Text(label, color = color, fontSize = 12.5.sp, maxLines = 1) }
}

// ===================================================================== share / links

private fun shareText(ctx: Context, text: String) {
    val i = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, "Wanted songs")
        .putExtra(Intent.EXTRA_TEXT, text)
    runCatching { ctx.startActivity(Intent.createChooser(i, "Share wanted songs").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun shareCsv(ctx: Context, f: File) {
    runCatching {
        // Same FileProvider the listening-stats CSV export uses (stats/ under app files).
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "com.hione.player.statsexport", f)
        val i = Intent(Intent.ACTION_SEND).setType("text/csv")
            .putExtra(Intent.EXTRA_SUBJECT, "Wanted songs")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(i, "Export wanted songs").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { shareText(ctx, f.readText()) }
}

private fun openUrl(ctx: Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

// ===================================================================== cover art

private object WantedArt {
    val mem = LruCache<String, ImageBitmap>(80)

    fun load(ctx: Context, url: String): ImageBitmap? {
        mem.get(url)?.let { return it }
        val dir = File(ctx.cacheDir, "wanted_art").apply { mkdirs() }
        val name = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        val f = File(dir, name)
        if (!f.exists() || f.length() == 0L) {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000; readTimeout = 8000; instanceFollowRedirects = true
                setRequestProperty("User-Agent", "HioneOS (https://github.com/sworrl/HioneOS)")
            }
            try {
                if (c.responseCode !in 200..299) return null
                val tmp = File(dir, "$name.tmp")
                c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                tmp.renameTo(f)
            } finally { c.disconnect() }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 256 && bounds.outHeight / (sample * 2) >= 256) sample *= 2
        val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return bmp.asImageBitmap().also { mem.put(url, it) }
    }
}

@Composable
fun WantedCover(url: String?, size: Dp) {
    val ctx = LocalContext.current
    var bmp by remember(url) { mutableStateOf(url?.let { WantedArt.mem.get(it) }) }
    LaunchedEffect(url) {
        if (url != null && bmp == null) bmp = withContext(Dispatchers.IO) { runCatching { WantedArt.load(ctx, url) }.getOrNull() }
    }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(Mascot.color(0xFF123438)),
        contentAlignment = Alignment.Center
    ) {
        val b = bmp
        if (b != null) Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Default.Radio, null, tint = HioneGold.copy(alpha = 0.8f), modifier = Modifier.size(size / 2))
    }
}
