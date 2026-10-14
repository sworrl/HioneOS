package com.hione.player.stats

import com.hione.player.Mascot
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.player.CrashSentinel
import com.hione.player.CyberDarkBg
import com.hione.player.HioneNeonPink
import com.hione.player.HioneTeal
import com.hione.player.HioneTealBright
import com.hione.player.Muted
import com.hione.player.Surface1
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Settings → "Listening stats" screen: totals, top-10s, time-of-day, outputs, CSV export. */
class ListeningStatsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        CrashSentinel.install(this)
        super.onCreate(savedInstanceState)
        setContent { ListeningStatsScreen(onBack = { finish() }) }
    }
}

private val CardShape = RoundedCornerShape(18.dp)
private const val EXPORT_AUTHORITY = "com.hione.player.statsexport"

/** The row in Settings that opens the stats screen + the two logging toggles. Drop-in for a
 *  LazyColumn item in MainActivity's settings list. */
@Composable
fun ListeningStatsSettingsCard(ctx: Context) {
    var statsOn by remember { mutableStateOf(StatsPreferences.isStatsEnabled(ctx)) }
    var locOn by remember { mutableStateOf(StatsPreferences.isLocationEnabled(ctx)) }
    val hasPerm = remember { StatsPreferences.hasCoarseLocationPermission(ctx) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(Brush.verticalGradient(listOf(Surface1, Mascot.color(0xFF071B20))))
            .border(1.dp, HioneTealBright.copy(alpha = 0.35f), CardShape)
            .padding(16.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { ctx.startActivity(Intent(ctx, ListeningStatsActivity::class.java)) }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.BarChart, "Listening stats", tint = HioneTealBright, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Listening stats", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("Every listen logged: top tracks, hours, streaks and discoveries", color = Muted, fontSize = 11.5.sp, lineHeight = 15.sp)
            }
            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = HioneTealBright, modifier = Modifier.size(18.dp).rotate(180f))
        }
        // Hione Rewind — Wrapped-style story cards over the same listen DB (HioneRewindActivity).
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { HioneRewindActivity.launch(ctx) }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.AutoAwesome, "${Mascot.name} Rewind", tint = HioneNeonPink, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("${Mascot.name} Rewind", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("Your week, month, year and all-time recap as story cards you can share", color = Muted, fontSize = 11.5.sp, lineHeight = 15.sp)
            }
            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = HioneNeonPink, modifier = Modifier.size(18.dp).rotate(180f))
        }
        Spacer(Modifier.height(8.dp))
        StatsToggleRow("Log listens", "Keep a per-listen history on this device (nothing leaves the M500)", statsOn) {
            statsOn = it; StatsPreferences.setStatsEnabled(ctx, it)
        }
        StatsToggleRow(
            "Log coarse location",
            if (!hasPerm) "Location permission not granted, so this stays off"
            else "One cached last-known fix per track, refreshed at most every 10 min. Never turns on GPS.",
            locOn, enabled = hasPerm
        ) { locOn = it; StatsPreferences.setLocationEnabled(ctx, it) }
    }
}

@Composable
private fun StatsToggleRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) Mascot.color(0xFFE8F4F2) else Muted, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Muted, fontSize = 11.sp, lineHeight = 14.sp)
        }
        Spacer(Modifier.width(10.dp))
        // Pill toggle in the Hione teal language (kept self-contained: MainActivity's HeartToggle is private).
        Box(
            Modifier
                .width(44.dp).height(24.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (checked && enabled) HioneTealBright.copy(alpha = 0.28f) else Mascot.color(0xFF123438))
                .border(1.dp, if (checked && enabled) HioneTealBright.copy(alpha = 0.7f) else Mascot.color(0xFF1E4A50), RoundedCornerShape(12.dp))
        ) {
            Box(
                Modifier
                    .padding(start = if (checked) 22.dp else 3.dp, top = 3.dp)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(if (checked && enabled) HioneTealBright else Mascot.color(0xFF3A6B70))
            )
        }
    }
}

// ============================================================================================ screen

private enum class TopKind { TRACKS, ARTISTS, ALBUMS }

@Composable
fun ListeningStatsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { StatsRepository(ctx) }
    val scope = rememberCoroutineScope()

    var period by remember { mutableStateOf(StatsRepository.Period.WEEK) }
    var topKind by remember { mutableStateOf(TopKind.TRACKS) }
    var rankBy by remember { mutableStateOf(StatsRepository.RankBy.QUALIFIED_PLAYS) }

    var totals by remember { mutableStateOf<StatsRepository.Totals?>(null) }
    var streak by remember { mutableStateOf<StatsRepository.Streak?>(null) }
    var discoveries by remember { mutableStateOf(0) }
    var hours by remember { mutableStateOf(LongArray(24)) }
    var days by remember { mutableStateOf(LongArray(7)) }
    var top by remember { mutableStateOf<List<StatsRepository.TopEntry>>(emptyList()) }
    var outputs by remember { mutableStateOf<List<StatsRepository.OutputBreakdown>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var exporting by remember { mutableStateOf(false) }

    LaunchedEffect(period) {
        loading = true
        totals = repo.totals(period)
        streak = repo.streak()
        discoveries = repo.discoveryCount(period)
        hours = repo.byHour(period)
        days = repo.byDayOfWeek(period)
        outputs = repo.outputs(period)
        loading = false
    }
    LaunchedEffect(period, topKind, rankBy) {
        top = when (topKind) {
            TopKind.TRACKS -> repo.topTracks(period, rankBy)
            TopKind.ARTISTS -> repo.topArtists(period, rankBy)
            TopKind.ALBUMS -> repo.topAlbums(period, rankBy)
        }
    }

    Box(Modifier.fillMaxSize().background(CyberDarkBg)) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Mascot.color(0xFF0A2A30), CyberDarkBg, Mascot.color(0xFF07161A)))
            )
        )
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = HioneTealBright,
                        modifier = Modifier.size(36.dp).clip(CircleShape).clickable { onBack() }.padding(6.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Text("LISTENING STATS", color = HioneTealBright, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                        Text("Your music, measured", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    Icon(
                        Icons.Default.Share, "Export CSV", tint = if (exporting) Muted else HioneTealBright,
                        modifier = Modifier.size(36.dp).clip(CircleShape).clickable(enabled = !exporting) {
                            exporting = true
                            scope.launch {
                                try {
                                    val f = repo.exportCsv()
                                    val uri = androidx.core.content.FileProvider.getUriForFile(ctx, EXPORT_AUTHORITY, f)
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/csv"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        putExtra(Intent.EXTRA_SUBJECT, f.name)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    Toast.makeText(ctx, "Saved ${f.absolutePath}", Toast.LENGTH_LONG).show()
                                    runCatching { ctx.startActivity(Intent.createChooser(send, "Share listening stats")) }
                                } catch (t: Throwable) {
                                    Toast.makeText(ctx, "Export failed: ${t.message}", Toast.LENGTH_LONG).show()
                                }
                                exporting = false
                            }
                        }.padding(7.dp)
                    )
                }
            }
            item { PeriodChips(period) { period = it } }
            item { Spacer(Modifier.height(12.dp)) }
            item { TotalsCard(totals, streak, discoveries, loading) }
            item { Spacer(Modifier.height(12.dp)) }
            item {
                StatsCard(title = "BY HOUR OF DAY") {
                    Histogram(values = hours, labels = listOf("0", "6", "12", "18", "23"), accent = HioneTealBright)
                    Spacer(Modifier.height(6.dp))
                    val peak = hours.indices.maxByOrNull { hours[it] } ?: 0
                    Text(
                        if (hours.all { it == 0L }) "No listens in this period yet." else "Peak hour: ${hourLabel(peak)} · ${fmtMinutes(hours[peak])} listened",
                        color = Muted, fontSize = 11.5.sp
                    )
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
            item {
                StatsCard(title = "BY DAY OF WEEK") {
                    Histogram(values = days, labels = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"), accent = HioneNeonPink, labelEvery = true)
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
            item {
                StatsCard(title = "TOP 10") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip("Tracks", topKind == TopKind.TRACKS) { topKind = TopKind.TRACKS }
                        Chip("Artists", topKind == TopKind.ARTISTS) { topKind = TopKind.ARTISTS }
                        Chip("Albums", topKind == TopKind.ALBUMS) { topKind = TopKind.ALBUMS }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip("Full plays", rankBy == StatsRepository.RankBy.QUALIFIED_PLAYS, small = true) { rankBy = StatsRepository.RankBy.QUALIFIED_PLAYS }
                        Chip("Minutes", rankBy == StatsRepository.RankBy.MINUTES, small = true) { rankBy = StatsRepository.RankBy.MINUTES }
                        Chip("All plays", rankBy == StatsRepository.RankBy.PLAYS, small = true) { rankBy = StatsRepository.RankBy.PLAYS }
                    }
                    Spacer(Modifier.height(10.dp))
                    if (top.isEmpty()) Text("Nothing here yet. Play something.", color = Muted, fontSize = 12.sp)
                    top.forEachIndexed { i, e -> TopRow(i + 1, e, rankBy) }
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
            if (outputs.isNotEmpty()) {
                item {
                    StatsCard(title = "OUTPUTS") {
                        val total = outputs.sumOf { it.playedMs }.coerceAtLeast(1L)
                        outputs.forEach { o ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(outputLabel(o.output), color = Mascot.color(0xFFE8F4F2), fontSize = 12.5.sp, modifier = Modifier.width(78.dp))
                                Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.06f))) {
                                    Box(Modifier.fillMaxWidth(o.playedMs.toFloat() / total).height(8.dp).background(HioneTeal))
                                }
                                Spacer(Modifier.width(8.dp))
                                Text("${fmtMinutes(o.playedMs)} · ${o.listens}", color = Muted, fontSize = 11.sp)
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
            item {
                Text(
                    "Full play = at least 94% heard without scrubbing forward. Minutes count time actually heard, not track length.",
                    color = Muted.copy(alpha = 0.85f), fontSize = 10.5.sp, lineHeight = 14.sp, modifier = Modifier.padding(bottom = 24.dp)
                )
            }
        }
    }
}

@Composable
private fun PeriodChips(period: StatsRepository.Period, onSelect: (StatsRepository.Period) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StatsRepository.Period.values().forEach { p -> Chip(p.label, period == p) { onSelect(p) } }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, small: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) HioneTealBright.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.05f))
            .border(1.dp, if (selected) HioneTealBright.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.10f), RoundedCornerShape(20.dp))
            .clickable { onClick() }
            .padding(horizontal = if (small) 10.dp else 14.dp, vertical = if (small) 5.dp else 8.dp)
    ) {
        Text(label, color = if (selected) HioneTealBright else Muted, fontSize = if (small) 11.sp else 12.5.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
private fun StatsCard(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(Brush.verticalGradient(listOf(Surface1, Mascot.color(0xFF071B20))))
            .border(1.dp, HioneTealBright.copy(alpha = 0.3f), CardShape)
            .padding(16.dp)
    ) {
        Text(title, color = HioneTealBright, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun TotalsCard(t: StatsRepository.Totals?, streak: StatsRepository.Streak?, discoveries: Int, loading: Boolean) {
    StatsCard(title = if (loading) "TOTALS · loading…" else "TOTALS") {
        val tt = t ?: StatsRepository.Totals(0, 0, 0, 0L, 0, 0, 0, 0, null, null)
        Row(Modifier.fillMaxWidth()) {
            BigStat(Modifier.weight(1f), fmtMinutes(tt.playedMs), "listened")
            BigStat(Modifier.weight(1f), tt.listens.toString(), "listens")
            BigStat(Modifier.weight(1f), tt.qualifiedListens.toString(), "full plays")
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            SmallStat(Modifier.weight(1f), "${(tt.skipRate * 100).toInt()}%", "skip rate")
            SmallStat(Modifier.weight(1f), tt.distinctTracks.toString(), "tracks")
            SmallStat(Modifier.weight(1f), tt.distinctArtists.toString(), "artists")
            SmallStat(Modifier.weight(1f), tt.distinctAlbums.toString(), "albums")
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            SmallStat(Modifier.weight(1f), discoveries.toString(), "discoveries")
            SmallStat(Modifier.weight(1f), tt.heartsGiven.toString(), "hearts")
            SmallStat(Modifier.weight(1f), "${streak?.currentDays ?: 0}d", "streak")
            SmallStat(Modifier.weight(1f), "${streak?.longestDays ?: 0}d", "best streak")
        }
        if (tt.firstListenAt != null) {
            Spacer(Modifier.height(8.dp))
            val f = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
            Text("Since ${f.format(Date(tt.firstListenAt))} · ${streak?.activeDays ?: 0} active days overall", color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun BigStat(modifier: Modifier, value: String, label: String) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label.uppercase(), color = HioneTealBright, fontSize = 9.5.sp, letterSpacing = 1.sp)
    }
}

@Composable
private fun SmallStat(modifier: Modifier, value: String, label: String) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Mascot.color(0xFFE8F4F2), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(label, color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun TopRow(rank: Int, e: StatsRepository.TopEntry, rankBy: StatsRepository.RankBy) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(26.dp).clip(CircleShape)
                .background(if (rank <= 3) HioneTealBright.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.06f)),
            contentAlignment = Alignment.Center
        ) { Text(rank.toString(), color = if (rank <= 3) HioneTealBright else Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!e.subtitle.isNullOrBlank()) Text(e.subtitle, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                when (rankBy) {
                    StatsRepository.RankBy.QUALIFIED_PLAYS -> "${e.qualifiedPlays}×"
                    StatsRepository.RankBy.MINUTES -> fmtMinutes(e.playedMs)
                    StatsRepository.RankBy.PLAYS -> "${e.plays}×"
                },
                color = HioneTealBright, fontSize = 13.sp, fontWeight = FontWeight.Bold
            )
            Text(
                when (rankBy) {
                    StatsRepository.RankBy.MINUTES -> "${e.qualifiedPlays} full · ${e.plays} plays"
                    else -> "${fmtMinutes(e.playedMs)} · ${e.plays} plays"
                },
                color = Muted, fontSize = 10.sp
            )
        }
    }
}

/** Minimal bar histogram — no chart library. Bars scale to the max bucket. */
@Composable
private fun Histogram(values: LongArray, labels: List<String>, accent: Color, labelEvery: Boolean = false) {
    val max = (values.maxOrNull() ?: 0L).coerceAtLeast(1L)
    Canvas(Modifier.fillMaxWidth().height(84.dp)) {
        val n = values.size
        if (n == 0) return@Canvas
        val gap = 3.dp.toPx()
        val w = (size.width - gap * (n - 1)) / n
        for (i in 0 until n) {
            val h = (values[i].toFloat() / max) * (size.height - 2.dp.toPx())
            val x = i * (w + gap)
            drawRoundRect(
                color = Color.White.copy(alpha = 0.05f),
                topLeft = Offset(x, 0f), size = Size(w, size.height), cornerRadius = CornerRadius(2.dp.toPx())
            )
            if (h > 0f) drawRoundRect(
                brush = Brush.verticalGradient(listOf(accent, accent.copy(alpha = 0.45f)), startY = size.height - h, endY = size.height),
                topLeft = Offset(x, size.height - h), size = Size(w, h), cornerRadius = CornerRadius(2.dp.toPx())
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (labelEvery) Arrangement.SpaceAround else Arrangement.SpaceBetween) {
        labels.forEach { Text(it, color = Muted, fontSize = 9.5.sp) }
    }
}

private fun hourLabel(h: Int): String = when {
    h == 0 -> "12 AM"; h < 12 -> "$h AM"; h == 12 -> "12 PM"; else -> "${h - 12} PM"
}

private fun outputLabel(o: String): String = when (o) {
    "bt" -> "Bluetooth"; "usb" -> "USB DAC"; "wired" -> "Wired"; "speaker" -> "Speaker"; "digital" -> "Digital"; "unknown" -> "Unknown"; else -> o.replaceFirstChar { it.uppercase() }
}

internal fun fmtMinutes(ms: Long): String {
    val min = ms / 60_000L
    return when {
        min < 60 -> "${min}m"
        min < 60 * 24 -> "${min / 60}h ${min % 60}m"
        else -> "${min / 1440}d ${(min % 1440) / 60}h"
    }
}
