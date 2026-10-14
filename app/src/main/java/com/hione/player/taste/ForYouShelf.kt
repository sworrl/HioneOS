package com.hione.player.taste

import com.hione.player.Mascot
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.player.AlbumArtImage
import com.hione.player.Haptics
import com.hione.player.LikeStore
import com.hione.player.HioneGold
import com.hione.player.HionePink
import com.hione.player.HioneTeal
import com.hione.player.HioneTealBright
import com.hione.player.Muted
import com.hione.player.OrbitronFont
import com.hione.player.PlayerHolder
import com.hione.player.Surface1
import com.hione.player.Track
import kotlinx.coroutines.delay

/*
 * "For You" home shelf + taste profile sheet + Hione Radio station sheet. All Compose UI for the
 * taste package lives here so MainActivity only needs one-line hooks (ForYouShelf in the home
 * list, StationHost at the App level, StationSeedButton in artist/album headers, and
 * StationFromTrackButton in the track long-press sheet).
 */

private val CardText get() = Mascot.color(0xFFE8F4F2)

// =============================================================================================
// Home shelf
// =============================================================================================

@Composable
fun ForYouShelf(
    tracks: List<Track>,
    onPlay: (List<Track>, Int) -> Unit,
    onOpenArtist: (String) -> Unit = {}
) {
    val ctx = LocalContext.current
    if (com.hione.player.HioneDbg.off(ctx, "taste_ui")) return
    val likedIds = LikeStore.liked.toList()
    var result by remember { mutableStateOf<TasteShelves.Result?>(null) }
    var showProfile by remember { mutableStateOf(false) }
    // Off the main thread, debounced like the daily-highlight computation above it — a burst
    // of heart taps must not rescore the library on every tap.
    val lastLiked = remember { mutableStateOf(likedIds) }
    LaunchedEffect(tracks, likedIds, StationEngine.active) {
        delay(600)
        // Hearts change the model but LikeStore doesn't know about it — mark the snapshot stale
        // only when the liked set actually changed (not on every recomposition).
        if (lastLiked.value != likedIds) { lastLiked.value = likedIds; TasteModel.invalidate() }
        result = runCatching { TasteShelves.compute(ctx, tracks) }.getOrNull()
    }

    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        // Header row: title + Radio + Taste profile
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, null, tint = HioneTealBright, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("For You", color = HioneTealBright, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill(text = "Radio", icon = { Icon(Icons.Default.Radio, null, tint = HionePink, modifier = Modifier.size(14.dp)) }, color = HionePink) {
                    Haptics.tick(ctx)
                    if (StationEngine.active) StationEngine.openSheet()
                    else if (StationEngine.start(ctx, StationEngine.Seed.MyTasteNow, tracks)) StationEngine.openSheet()
                    else android.widget.Toast.makeText(ctx, StationEngine.lastMessage.ifBlank { "Listen more to unlock ${Mascot.name} Radio" }, android.widget.Toast.LENGTH_SHORT).show()
                }
                Spacer(Modifier.width(8.dp))
                Pill(text = "Taste", icon = null, color = HioneTeal) { Haptics.tick(ctx); showProfile = true }
            }
        }

        val r = result
        if (StationEngine.active) StationOnAirCard(Modifier.padding(horizontal = 14.dp, vertical = 6.dp))

        if (r == null) {
            HonestNote("For You learns from what you play, heart and skip, and when. Rows open up as your listening builds up.")
        } else {
            if (r.signalCount < TasteShelves.PROFILE_MIN_SIGNAL) {
                HonestNote("Listen more to unlock. ${r.signalCount} of ${TasteShelves.PROFILE_MIN_SIGNAL} plays/hearts logged so far. Rows below open up as you go.")
            }
            for (row in r.rows) {
                if (row.locked) {
                    LockedRow(row)
                } else {
                    ShelfRow(row, tracks, onPlay)
                }
            }
        }
    }

    if (showProfile) TasteProfileSheet(tracks = tracks, onDismiss = { showProfile = false })
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShelfRow(row: TasteShelves.Row, library: List<Track>, onPlay: (List<Track>, Int) -> Unit) {
    val ctx = LocalContext.current
    val rowTracks = remember(row) { row.tracks }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.title, color = HioneTealBright, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            if (row.subtitle.isNotBlank()) Text(row.subtitle, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("Play ▸", color = HionePink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable { if (rowTracks.isNotEmpty()) onPlay(rowTracks, 0) })
    }
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp)) {
        items(row.items.size, key = { row.items[it].track.id }) { i ->
            val item = row.items[i]
            val t = item.track
            Column(
                Modifier.width(118.dp).padding(4.dp).combinedClickable(
                    onClick = { onPlay(rowTracks, i) },
                    onLongClick = {
                        Haptics.tick(ctx)
                        if (StationEngine.start(ctx, StationEngine.Seed.FromTrack(t), library)) StationEngine.openSheet()
                    }
                )
            ) {
                Box(Modifier.size(110.dp).clip(RoundedCornerShape(14.dp))) {
                    AlbumArtImage(trackId = t.id, modifier = Modifier.fillMaxSize(), trackPath = t.path)
                }
                Spacer(Modifier.height(4.dp))
                Text(t.title, color = CardText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(t.artist, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (item.why.isNotBlank()) Text(item.why, color = HioneTeal.copy(alpha = 0.8f), fontSize = 9.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 11.sp)
            }
        }
    }
}

@Composable
private fun LockedRow(row: TasteShelves.Row) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("🔒", fontSize = 12.sp)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(row.title, color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(row.unlockHint ?: "", color = Muted.copy(alpha = 0.85f), fontSize = 10.5.sp, lineHeight = 13.sp)
        }
    }
}

@Composable
private fun HonestNote(text: String) {
    Box(
        Modifier.padding(horizontal = 14.dp, vertical = 6.dp).fillMaxWidth()
            .clip(RoundedCornerShape(14.dp)).background(Mascot.color(0xFF092124))
            .border(1.dp, HioneTeal.copy(alpha = 0.3f), RoundedCornerShape(14.dp)).padding(12.dp)
    ) {
        Text(text, color = Muted, fontSize = 11.5.sp, lineHeight = 15.sp)
    }
}

@Composable
private fun Pill(text: String, icon: (@Composable () -> Unit)?, color: Color, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(50))
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) { icon(); Spacer(Modifier.width(4.dp)) }
        Text(text, color = color, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StationOnAirCard(modifier: Modifier = Modifier) {
    val seed = StationEngine.seed ?: return
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(Brush.horizontalGradient(listOf(Mascot.color(0xFF1C081A), Mascot.color(0xFF0C2B2E))))
            .border(1.dp, HionePink.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .clickable { StationEngine.openSheet() }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Radio, null, tint = HionePink, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("${Mascot.nameUpper} RADIO · ON AIR", color = HionePink, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            Text("${seed.kind}: ${seed.label}", color = CardText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (StationEngine.nowWhy.isNotBlank()) Text(StationEngine.nowWhy, color = Muted, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("Discovery ${StationEngine.discovery}%", color = HioneTealBright, fontSize = 10.5.sp, fontFamily = OrbitronFont)
    }
}

// =============================================================================================
// Station entry points for other screens
// =============================================================================================

/** Round "radio" button for artist/album headers — starts a station seeded from that entity. */
@Composable
fun StationSeedButton(seed: () -> StationEngine.Seed, library: List<Track>? = null, size: Dp = 36.dp) {
    val ctx = LocalContext.current
    Box(
        modifier = Modifier.size(size).clip(RoundedCornerShape(size / 2))
            .background(Mascot.color(0xFF1C081A))
            .border(1.dp, HionePink.copy(alpha = 0.5f), RoundedCornerShape(size / 2))
            .clickable {
                Haptics.tick(ctx)
                if (StationEngine.start(ctx, seed(), library)) StationEngine.openSheet()
                else android.widget.Toast.makeText(ctx, StationEngine.lastMessage.ifBlank { "Couldn't start a station from this" }, android.widget.Toast.LENGTH_SHORT).show()
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.Radio, "${Mascot.name} Radio", tint = HionePink, modifier = Modifier.size(size / 2))
    }
}

/** Full-width action row for the track long-press sheet. */
@Composable
fun StationFromTrackButton(track: Track, onStarted: () -> Unit) {
    val ctx = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(Mascot.color(0xFF1C081A)).border(1.2.dp, HionePink.copy(alpha = 0.8f), RoundedCornerShape(14.dp))
            .clickable {
                Haptics.tick(ctx)
                if (StationEngine.start(ctx, StationEngine.Seed.FromTrack(track))) { StationEngine.openSheet(); onStarted() }
                else android.widget.Toast.makeText(ctx, StationEngine.lastMessage.ifBlank { "Couldn't start a station" }, android.widget.Toast.LENGTH_SHORT).show()
            }
            .padding(vertical = 11.dp, horizontal = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Radio, "${Mascot.name} Radio", tint = HionePink, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text("${Mascot.name} Radio from this song", color = HionePink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

/** Hosts the station sheet at App level so it can open from any screen. */
@Composable
fun StationHost() {
    if (StationEngine.sheetOpen) StationSheet(onDismiss = { StationEngine.closeSheet() })
}

// =============================================================================================
// Station sheet
// =============================================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val seed = StationEngine.seed
    val active = StationEngine.active
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Surface1) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Radio, null, tint = HionePink, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (active) "${Mascot.nameUpper} RADIO · ON AIR" else "${Mascot.nameUpper} RADIO · STOPPED", color = HionePink, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    Text(seed?.let { "${it.kind}: ${it.label}" } ?: "No station", color = HioneTealBright, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (StationEngine.lastMessage.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(StationEngine.lastMessage, color = HioneGold, fontSize = 11.5.sp)
            }

            // Why this — for what's playing right now.
            val nowId = TasteHooks.currentTrackId()
            val nowPick = StationEngine.picks.firstOrNull { it.track.id == nowId }
            if (active && nowPick != null) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AlbumArtImage(nowPick.track.id, Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)), trackPath = nowPick.track.path)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(nowPick.track.title, color = CardText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(nowPick.track.artist, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Why this: ${nowPick.why.ifBlank { "fits the seed" }}", color = HioneTeal, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            // Discovery slider
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("DISCOVERY", color = Muted, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text("${StationEngine.discovery}% unheard", color = HioneTealBright, fontSize = 11.sp, fontFamily = OrbitronFont)
            }
            Slider(
                value = StationEngine.discovery.toFloat(),
                onValueChange = { StationEngine.setDiscovery(ctx, it.toInt()) },
                valueRange = 0f..100f,
                colors = SliderDefaults.colors(thumbColor = HionePink, activeTrackColor = HionePink, inactiveTrackColor = HioneTeal.copy(alpha = 0.25f))
            )
            Text("0% = only things you've played · 100% = only tracks you've never played, still ranked by how close they are to the seed. Applies to the next refill.",
                color = Muted, fontSize = 10.5.sp, lineHeight = 13.sp)

            // Upcoming
            val upcoming = remember(StationEngine.picks, nowId) {
                val ps = StationEngine.picks
                val idx = ps.indexOfFirst { it.track.id == nowId }
                if (idx >= 0) ps.drop(idx + 1).take(5) else ps.take(5)
            }
            if (active && upcoming.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("UP NEXT", color = Muted, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                for (p in upcoming) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        AlbumArtImage(p.track.id, Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)), trackPath = p.track.path)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${p.track.title} · ${p.track.artist}", color = CardText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (p.why.isNotBlank()) Text(p.why, color = HioneTeal.copy(alpha = 0.8f), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            // Smart shuffle toggle lives here too (the station is where people look for it).
            Spacer(Modifier.height(12.dp))
            SmartShuffleRow()

            // Actions
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (active) {
                    ActionButton("Stop station", HionePink, Modifier.weight(1f), icon = { Icon(Icons.Default.Stop, null, tint = HionePink, modifier = Modifier.size(18.dp)) }) {
                        Haptics.tick(ctx); StationEngine.stop(ctx, restorePrevious = false); onDismiss()
                    }
                    if (StationEngine.hasPreviousQueue) ActionButton("Stop & restore queue", HioneTealBright, Modifier.weight(1f)) {
                        Haptics.tick(ctx); StationEngine.stop(ctx, restorePrevious = true); onDismiss()
                    }
                } else {
                    ActionButton("Start: my taste now", HionePink, Modifier.weight(1f), icon = { Icon(Icons.Default.Radio, null, tint = HionePink, modifier = Modifier.size(18.dp)) }) {
                        Haptics.tick(ctx)
                        if (!StationEngine.start(ctx, StationEngine.Seed.MyTasteNow)) android.widget.Toast.makeText(ctx, StationEngine.lastMessage, android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("Stopping leaves the current queue playing as a normal queue; nothing else changes.", color = Muted, fontSize = 10.5.sp)
        }
    }
}

@Composable
private fun ActionButton(text: String, color: Color, modifier: Modifier = Modifier, icon: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.10f))
            .border(1.2.dp, color, RoundedCornerShape(14.dp)).clickable(onClick = onClick)
            .padding(vertical = 11.dp, horizontal = 8.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) { icon(); Spacer(Modifier.width(6.dp)) }
        Text(text, color = color, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SmartShuffleRow() {
    val ctx = LocalContext.current
    val on = SmartShuffle.isEnabled(ctx)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Smart shuffle", color = CardText, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
            Text("Weights the normal shuffle by affinity and time of day, keeps artists apart. Off = stock random.", color = Muted, fontSize = 10.5.sp, lineHeight = 13.sp)
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = SmartShuffle.enabled || on,
            onCheckedChange = { SmartShuffle.setEnabled(ctx, it) },
            colors = SwitchDefaults.colors(checkedThumbColor = HionePink, checkedTrackColor = HionePink.copy(alpha = 0.4f))
        )
    }
}

// =============================================================================================
// Taste profile sheet
// =============================================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasteProfileSheet(tracks: List<Track>, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var snap by remember { mutableStateOf(TasteModel.peek()) }
    LaunchedEffect(tracks) { snap = runCatching { TasteModel.snapshot(ctx, tracks) }.getOrNull() ?: snap }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Surface1) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Text("TASTE PROFILE", color = HioneTealBright, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Text("What this device has actually heard", color = CardText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            val s = snap
            if (s == null) {
                Spacer(Modifier.height(10.dp))
                Text("Building the profile…", color = Muted, fontSize = 12.sp)
            } else {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Stat("${s.totalListens}", "listens")
                    Stat("${s.totalHearts}", "hearted")
                    Stat("${s.coocEdges}", "pairings")
                    Stat("${s.heatmap.sumOf { it.sum() } / 3_600_000L}h", "heard")
                }
                if (s.signalCount < TasteShelves.PROFILE_MIN_SIGNAL) {
                    Spacer(Modifier.height(10.dp))
                    Text("Listen more to unlock. ${s.signalCount} of ${TasteShelves.PROFILE_MIN_SIGNAL} plays/hearts so far. The shares below use what there is.",
                        color = HioneGold, fontSize = 11.5.sp, lineHeight = 14.sp)
                }
                val genres = remember(s) { TasteShelves.topGenres(s) }
                val artists = remember(s) { TasteShelves.topArtists(s) }
                Spacer(Modifier.height(14.dp))
                Text("TOP GENRES", color = Muted, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                if (genres.isEmpty()) Text("No genre tags in what you've played. Genres come from the files' own tags.", color = Muted, fontSize = 11.sp)
                for (g in genres) ShareBar(g, HioneTeal)
                Spacer(Modifier.height(12.dp))
                Text("TOP ARTISTS", color = Muted, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                if (artists.isEmpty()) Text("Nothing played yet.", color = Muted, fontSize = 11.sp)
                for (a in artists) ShareBar(a, HionePink)
                Spacer(Modifier.height(12.dp))
                Text("LISTENING HOURS", color = Muted, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text("Rows are Sun to Sat, columns are midnight to 11 PM. Brighter means more time heard.", color = Muted.copy(alpha = 0.85f), fontSize = 10.sp)
                Spacer(Modifier.height(6.dp))
                Heatmap(s.heatmap, Modifier.fillMaxWidth().height(96.dp))
                val slot = TasteDb.currentSlot()
                val slotShare = s.slotTotals.sum().let { tot -> if (tot > 0) 100 * s.slotTotals[slot] / tot else 0 }
                Spacer(Modifier.height(6.dp))
                Text("Right now is your ${TasteDb.SLOT_NAMES[slot]} · $slotShare% of your listening happens in this slot", color = HioneTealBright, fontSize = 11.sp)
                Spacer(Modifier.height(14.dp))
                SmartShuffleRow()
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = HioneTealBright, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = OrbitronFont)
        Text(label, color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun ShareBar(s: TasteShelves.Share, color: Color) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(s.name, color = CardText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("${Math.round(s.fraction * 100)}% · ${s.detail}", color = Muted, fontSize = 10.5.sp, fontFamily = OrbitronFont)
        }
        Spacer(Modifier.height(3.dp))
        Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(color.copy(alpha = 0.15f))) {
            Box(Modifier.fillMaxWidth(s.fraction.coerceIn(0.02f, 1f)).height(5.dp).background(color))
        }
    }
}

@Composable
private fun Heatmap(grid: Array<LongArray>, modifier: Modifier = Modifier) {
    val max = grid.maxOf { it.maxOrNull() ?: 0L }.coerceAtLeast(1L)
    Canvas(modifier) {
        val cols = 24; val rows = 7
        val gap = 1.5f
        val cw = (size.width - gap * (cols - 1)) / cols
        val ch = (size.height - gap * (rows - 1)) / rows
        for (d in 0 until rows) for (h in 0 until cols) {
            val v = grid[d][h].toFloat() / max
            val c = if (v <= 0f) HioneTeal.copy(alpha = 0.08f) else androidx.compose.ui.graphics.lerp(HioneTeal.copy(alpha = 0.25f), HionePink, v.coerceIn(0f, 1f))
            drawRect(color = c, topLeft = Offset(h * (cw + gap), d * (ch + gap)), size = Size(cw, ch))
        }
    }
}
