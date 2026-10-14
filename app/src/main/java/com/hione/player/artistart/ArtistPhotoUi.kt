package com.hione.player.artistart

import com.hione.player.Mascot
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Portrait
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.player.ArtistGroup
import com.hione.player.HionePink
import com.hione.player.HioneTeal
import com.hione.player.HioneTealBright
import com.hione.player.Muted
import com.hione.player.Surface1
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shows the artist's real photo when one is known (memory/disk/local file, or fetched online per
 * the Settings toggles); otherwise renders [fallback] — the caller's existing album-art mosaic /
 * placeholder — so nothing about the current look changes for artists without a photo.
 * Never blocks: a synchronous memory-cache hit paints immediately, everything else is IO work.
 */
@Composable
fun ArtistPhotoImage(
    artist: ArtistGroup,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    fallback: @Composable () -> Unit
) {
    val ctx = LocalContext.current
    val key = remember(artist.name) { ArtistPhotoCache.keyFor(ctx, artist.name) }
    val version = ArtistPhotoCache.version
    var img by remember(key, version) { mutableStateOf<ImageBitmap?>(ArtistPhotoCache.peek(key)) }
    if (img == null) {
        LaunchedEffect(key, version) {
            img = withContext(Dispatchers.IO) { ArtistPhotoCache.load(ctx, key, artist) }
        }
    }
    val bmp = img
    if (bmp != null) {
        Image(bitmap = bmp, contentDescription = "Artist photo", modifier = modifier, contentScale = contentScale)
    } else {
        fallback()
    }
}

/**
 * Small "ⓘ" badge for the artist detail header — only visible when a real photo is in use — that
 * opens the attribution dialog (file, author, license, source page) plus a "not this artist"
 * escape hatch that drops the photo and stops auto-fetching it for this artist.
 */
@Composable
fun ArtistPhotoCreditButton(artist: ArtistGroup, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val key = remember(artist.name) { ArtistPhotoCache.keyFor(ctx, artist.name) }
    val version = ArtistPhotoCache.version
    var info by remember(key, version) { mutableStateOf<ArtistPhotoInfo?>(null) }
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(key, version) {
        // Shares the header image's in-flight job, so the badge only appears once a photo is
        // actually on screen — never for a match whose bytes haven't arrived (or failed).
        info = withContext(Dispatchers.IO) {
            if (ArtistPhotoCache.load(ctx, key, artist) != null) ArtistPhotoCache.info(ctx, key) else null
        }
    }
    val i = info ?: return
    Box(
        modifier
            .size(26.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(Mascot.color(0x66041416))
            .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(13.dp))
            .clickable { show = true },
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.Info, "Photo credit", tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(15.dp))
    }
    if (show) ArtistPhotoCreditDialog(ctx, key, i, onDismiss = { show = false })
}

@Composable
private fun ArtistPhotoCreditDialog(ctx: Context, key: String, info: ArtistPhotoInfo, onDismiss: () -> Unit) {
    val sourceLine = when (info.source) {
        "wikidata" -> "Wikimedia Commons via Wikidata" + (if (info.entityId.isNotBlank()) " (${info.entityId})" else "")
        "wikipedia" -> "Wikipedia article image"
        "local" -> "Your own file next to the music"
        else -> info.source
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        titleContentColor = Color.White,
        textContentColor = Mascot.color(0xFFE8F4F2),
        title = { Text("Artist photo credit", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                CreditRow("Matched", info.entityLabel.ifBlank { "—" })
                CreditRow("Source", sourceLine)
                CreditRow("File", info.fileName.ifBlank { "—" })
                if (info.author.isNotBlank()) CreditRow("Author", info.author)
                CreditRow(
                    "License",
                    info.license.ifBlank { if (info.source == "local") "—" else "See the source page" },
                    link = info.licenseUrl.takeIf { it.isNotBlank() }, ctx = ctx
                )
                if (info.pageUrl.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Open source page ↗", color = HioneTealBright, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { openUrl(ctx, info.pageUrl) }
                    )
                }
                if (info.source != "local") {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Photos are matched by exact artist name on Wikidata/Wikipedia. If this is the wrong person, reject it below and the album-art fallback returns.",
                        color = Muted, fontSize = 11.5.sp
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = HioneTealBright) } },
        dismissButton = {
            if (info.source != "local") {
                TextButton(onClick = {
                    ArtistPhotoCache.reject(ctx, key)
                    android.widget.Toast.makeText(ctx, "Photo removed for this artist", android.widget.Toast.LENGTH_SHORT).show()
                    onDismiss()
                }) { Text("Not this artist", color = HionePink) }
            }
        }
    )
}

@Composable
private fun CreditRow(label: String, value: String, link: String? = null, ctx: Context? = null) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(label, color = Muted, fontSize = 12.sp, modifier = Modifier.width(64.dp))
        Text(
            value + (if (link != null) " ↗" else ""),
            color = if (link != null) HioneTealBright else Mascot.color(0xFFE8F4F2),
            fontSize = 12.5.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
            modifier = if (link != null && ctx != null) Modifier.clickable { openUrl(ctx, link) } else Modifier
        )
    }
}

private fun openUrl(ctx: Context, url: String) {
    runCatching {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        android.widget.Toast.makeText(ctx, url, android.widget.Toast.LENGTH_LONG).show()
    }
}

/** Settings > "Artist Photos" card: online toggle, Wi-Fi-only toggle, cache stats + clear. */
@Composable
fun ArtistPhotoSettingsCard(ctx: Context) {
    var online by remember { mutableStateOf(ArtistPhotoPrefs.fetchOnline(ctx)) }
    var wifiOnly by remember { mutableStateOf(ArtistPhotoPrefs.wifiOnly(ctx)) }
    var stats by remember { mutableStateOf(Triple(0, 0L, 0)) }
    var statsTick by remember { mutableStateOf(0) }
    LaunchedEffect(statsTick, ArtistPhotoCache.version) {
        stats = withContext(Dispatchers.IO) { ArtistPhotoCache.stats(ctx) }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.verticalGradient(listOf(Surface1, Mascot.color(0xFF0A2A33))))
            .border(1.dp, HioneTeal.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Portrait, "Artist photos", tint = HioneTealBright, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Artist Photos", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Artist lists and headers show a real photo from Wikimedia Commons / Wikipedia when the name matches exactly. " +
                "Otherwise the album art stays. An artist.jpg (or folder.jpg in a folder named after the artist) next to " +
                "your music always wins and works offline.",
            color = Muted, fontSize = 12.sp
        )
        Spacer(Modifier.height(10.dp))

        PhotoToggleRow(
            title = "Fetch artist photos online",
            subtitle = if (online) "Looks up unknown artists on Wikidata (max 2 at a time, misses remembered 30 days)"
                       else "Off: no network use. Cached and local photos still show",
            checked = online
        ) { online = it; ArtistPhotoPrefs.setFetchOnline(ctx, it) }
        PhotoToggleRow(
            title = "Only on Wi-Fi",
            subtitle = "Skip lookups on mobile data / hotspot",
            checked = wifiOnly
        ) { wifiOnly = it; ArtistPhotoPrefs.setWifiOnly(ctx, it) }

        Spacer(Modifier.height(10.dp))
        val (count, bytes, misses) = stats
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$count photo${if (count == 1) "" else "s"} · ${formatBytes(bytes)} cached · $misses remembered miss${if (misses == 1) "" else "es"}",
                color = Muted, fontSize = 11.5.sp, modifier = Modifier.weight(1f)
            )
            Row(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Mascot.color(0x228EC5FF))
                    .border(1.dp, HionePink.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                    .clickable {
                        ArtistPhotoCache.clearAll(ctx)
                        statsTick++
                        android.widget.Toast.makeText(ctx, "Artist photo cache cleared", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Delete, null, tint = HionePink, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Clear cache", color = HionePink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun PhotoToggleRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Mascot.color(0xFFE8F4F2), fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Muted, fontSize = 11.5.sp)
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked, onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = HionePink, checkedTrackColor = HioneTeal.copy(alpha = 0.6f),
                uncheckedThumbColor = Muted, uncheckedTrackColor = Mascot.color(0xFF0A2022)
            )
        )
    }
}

private fun formatBytes(b: Long): String = when {
    b >= 1024L * 1024 -> String.format("%.1f MB", b / (1024.0 * 1024))
    b >= 1024 -> "${b / 1024} KB"
    else -> "$b B"
}
