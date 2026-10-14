package com.hione.player.discsplit

import com.hione.player.Mascot
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.player.DiscImage
import com.hione.player.DiscImageColor
import com.hione.player.HionePink
import com.hione.player.HioneTeal
import com.hione.player.HioneTealBright
import com.hione.player.Muted
import com.hione.player.Surface1
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings → "Disc Images" card: the two online-lookup switches, live counters from the last
 * library pass ("N of M disc images split · X pending · Y unmatched"), the last worker event, and
 * "Re-check unmatched" / "Reset all lookups". Drop into the settings LazyColumn as
 * `item { com.hione.player.discsplit.DiscSplitSettingsCard(ctx) }`.
 */
@Composable
fun DiscSplitSettingsCard(ctx: Context) {
    var online by remember { mutableStateOf(DiscSplitPrefs.splitOnline(ctx)) }
    var wifiOnly by remember { mutableStateOf(DiscSplitPrefs.wifiOnly(ctx)) }
    val version = MusicBrainzSplitter.version           // bumps on every pass / lookup / reset
    var cacheCounts by remember { mutableStateOf(Triple(0, 0, 0)) }
    LaunchedEffect(version) { cacheCounts = withContext(Dispatchers.IO) { DiscSplitCache.counts(ctx) } }

    val report = DiscImage.lastReport
    val stats = MusicBrainzSplitter.stats
    val lastEvent = MusicBrainzSplitter.lastEvent
    val queued = MusicBrainzSplitter.queued
    val parked = MusicBrainzSplitter.isParked

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.verticalGradient(listOf(Surface1, Mascot.color(0xFF0A2A33))))
            .border(1.dp, HioneTeal.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Album, "Disc images", tint = DiscImageColor, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Whole-Disc Images", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Albums ripped as ONE file for the whole CD are split into their real tracks. A .cue sheet or a " +
                "tracks.txt next to the file always wins and works offline. Otherwise the disc is looked up on " +
                "MusicBrainz by artist, album and exact running time. With no confident match the image stays one " +
                "playable item, never a guess.",
            color = Muted, fontSize = 12.sp
        )
        Spacer(Modifier.height(10.dp))

        DiscToggleRow(
            title = "Split disc images online",
            subtitle = if (online) "Looks up cue-less images on MusicBrainz (1 request/s, in the background; misses remembered 30 days)"
                       else "Off: no network use. Splits already found still apply",
            checked = online
        ) { online = it; DiscSplitPrefs.setSplitOnline(ctx, it) }
        DiscToggleRow(
            title = "Wi-Fi only",
            subtitle = "Skip lookups on mobile data / hotspot",
            checked = wifiOnly
        ) { wifiOnly = it; DiscSplitPrefs.setWifiOnly(ctx, it) }

        Spacer(Modifier.height(10.dp))
        val (matchedOnDisk, unmatchedOnDisk, errorsOnDisk) = cacheCounts
        val pendingShown = if (online) stats.pending else 0
        Text(
            "${report.split} of ${report.images} disc image${if (report.images == 1) "" else "s"} split · " +
                "$pendingShown pending · ${stats.unmatched} unmatched",
            color = Mascot.color(0xFFE8F4F2), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold
        )
        val detail = buildString {
            append("${report.withCue} with cue · ${stats.matched} via MusicBrainz · ${report.virtualTracks} virtual tracks")
            if (matchedOnDisk + unmatchedOnDisk + errorsOnDisk > 0)
                append(" · cache: $matchedOnDisk matched / $unmatchedOnDisk unmatched / $errorsOnDisk errors")
            if (online && queued > 0) append(" · $queued queued")
            if (online && parked && queued > 0) append(if (wifiOnly) " (waiting for Wi-Fi)" else " (waiting for network)")
            if (!online && stats.pending > 0) append(" · ${stats.pending} not looked up (lookups off)")
        }
        Text(detail, color = Muted, fontSize = 11.sp)
        if (lastEvent.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(lastEvent, color = HioneTealBright, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            DiscActionChip(
                label = "Re-check unmatched", icon = Icons.Default.Refresh, tint = HioneTealBright, fill = Mascot.color(0x22FFAB2E),
                enabled = online
            ) {
                val n = MusicBrainzSplitter.recheckUnmatched(ctx)
                Toast.makeText(ctx, if (n == 0) "Nothing to re-check" else "Re-checking $n disc image${if (n == 1) "" else "s"}", Toast.LENGTH_SHORT).show()
            }
            Spacer(Modifier.width(8.dp))
            DiscActionChip(label = "Reset all lookups", icon = Icons.Default.Delete, tint = HionePink, fill = Mascot.color(0x228EC5FF), enabled = true) {
                val n = MusicBrainzSplitter.resetAll(ctx)
                Toast.makeText(ctx, "Forgot $n lookup${if (n == 1) "" else "s"}. Images re-split on the next refresh", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

@Composable
private fun DiscToggleRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
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

@Composable
private fun DiscActionChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    fill: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val alpha = if (enabled) 1f else 0.4f
    Row(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(fill.copy(alpha = fill.alpha * alpha))
            .border(1.dp, tint.copy(alpha = 0.5f * alpha), RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = tint.copy(alpha = alpha), modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, color = tint.copy(alpha = alpha), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}
