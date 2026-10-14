package com.caf.fmradio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/** Something on the preset bar or in the station list: a frequency, and what it probably is. */
data class PresetTarget(val khz: Int, val station: FmStationCatalogue.Station?, val isPreset: Boolean)

/** How many predicted stations ride along after the user's own presets. */
private const val NEARBY_ON_BAR = 14

/**
 * The quick-select bar.
 *
 * Two tiers, in order: the user's presets (starred), then what the catalogue says should come
 * in where the device is now. The nearby tier is filtered to stations predicted or measured to
 * be receivable, best first: a station 90 km away behind a ridge does not belong one tap from
 * the dial just because it is licensed. The whole list, including the ones out of reach, is in
 * the station sheet behind the last chip.
 *
 * Every chip is a 56 dp thumb target, colour-coded per station, with the reception gauge
 * (odds arc + line-of-sight glyph). Tap tunes; long-press opens the station's details, which is
 * where a preset is removed. The row snaps to chip edges so a fling never parks a chip half
 * off-screen, and it scrolls the tuned station into view when the dial moves.
 */
@Composable
fun FmPresetBar(
    favorites: List<Int>,
    nearby: List<FmStationCatalogue.Station>,
    frequencyKHz: Int,
    hasPosition: Boolean,
    onDetails: (PresetTarget) -> Unit,
    onAllStations: () -> Unit,
) {
    val items = remember(favorites, nearby) {
        // distinct(): lazy keys must be unique, and a duplicated preset would crash the row.
        val presets = favorites.distinct().map { PresetTarget(it, stationOn(it, nearby), true) }
        // If nothing has a verdict yet (no position, no ERP data), fall back to the catalogue's
        // own ordering rather than show an empty tier.
        val rated = nearby.any { it.reach != FmReach.Verdict.UNKNOWN }
        val predicted = nearby.asSequence()
            .filter { s -> favorites.none { abs(it - s.khz) <= 60 } }
            .filter { if (rated) it.reach.inReach else true }
            .sortedWith(compareBy<FmStationCatalogue.Station>({ it.reach.rank() }, { -(it.receptionScore ?: 0f) }, { it.distanceKm }))
            .distinctBy { it.khz }
            .take(NEARBY_ON_BAR)
            .map { PresetTarget(it.khz, it, false) }
            .toList()
        presets to predicted
    }
    val (presets, predicted) = items

    if (presets.isEmpty() && predicted.isEmpty()) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (!hasPosition) "No presets yet. Tap the star to save a station. Local stations show up once the device knows where it is."
                else "No presets yet. Tap the star on the dial to save the current station.",
                color = HioneTextSecondary, fontSize = 8.sp, lineHeight = 11.sp,
                modifier = Modifier.weight(1f).padding(end = 8.dp), maxLines = 3
            )
            AllStationsChip(onAllStations)
        }
        return
    }

    val listState = rememberLazyListState()
    val fling = rememberSnapFlingBehavior(listState, SnapPosition.Start)

    // Keep the tuned chip on screen. Index math mirrors the item order below.
    LaunchedEffect(frequencyKHz, presets, predicted) {
        val pi = presets.indexOfFirst { abs(it.khz - frequencyKHz) <= 60 }
        val ni = predicted.indexOfFirst { abs(it.khz - frequencyKHz) <= 60 }
        val idx = when {
            pi >= 0 -> pi
            ni >= 0 -> presets.size + (if (presets.isNotEmpty()) 1 else 0) + ni
            else -> -1
        }
        if (idx < 0) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo
        val fully = visible.any { it.index == idx && it.offset >= 0 &&
            it.offset + it.size <= listState.layoutInfo.viewportEndOffset }
        if (!fully) listState.animateScrollToItem(idx)
    }

    LazyRow(
        state = listState,
        flingBehavior = fling,
        modifier = Modifier.fillMaxWidth().height(58.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(presets, key = { _, t -> "p${t.khz}" }) { _, t ->
            PresetChip(t, abs(t.khz - frequencyKHz) <= 60, onDetails)
        }
        if (presets.isNotEmpty() && predicted.isNotEmpty()) {
            item(key = "sep") { TierMarker() }
        }
        itemsIndexed(predicted, key = { _, t -> "n${t.khz}" }) { _, t ->
            PresetChip(t, abs(t.khz - frequencyKHz) <= 60, onDetails)
        }
        item(key = "all") { AllStationsChip(onAllStations) }
    }
}

@Composable
private fun PresetChip(t: PresetTarget, selected: Boolean, onDetails: (PresetTarget) -> Unit) {
    val stn = t.station
    val color = stn?.let { stationColor(it.call) } ?: frequencyColor(t.khz)
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .width(128.dp)
            .height(56.dp)
            .glass(
                shape,
                accent = color,
                accent2 = if (selected) Color.White else HionePink,
                fill = if (selected) color.copy(alpha = 0.22f).compositeOverDark() else Color(0xC00A1E26),
                rimAlpha = if (selected) 1f else 0.5f,
                rimWidth = if (selected) 1.6.dp else 1.dp,
                shine = if (selected) 1.5f else 0.8f,
            )
            .pressable(onLongClick = { onDetails(t) }) { FmRadioManager.tune(t.khz) },
    ) {
        // Colour stripe: the station's identity, readable even at a glance from the side.
        Box(Modifier.align(Alignment.CenterStart).width(4.dp).fillMaxHeight().background(color))
        Row(
            Modifier.fillMaxSize().padding(start = 9.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ReceptionGauge(
                score = stn?.receptionScore,
                verdict = stn?.reach ?: FmReach.Verdict.UNKNOWN,
                fresnel = stn?.fresnelFraction,
                modifier = Modifier.size(34.dp),
            )
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stn?.chipLabel ?: fmtMhz(t.khz),
                        color = if (selected) Color.White else color,
                        fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont,
                        maxLines = 1, overflow = TextOverflow.Clip, modifier = Modifier.weight(1f, fill = false)
                    )
                    if (t.isPreset) {
                        Spacer(Modifier.width(2.dp))
                        Icon(Icons.Default.Star, "Preset", tint = HioneNeonPink, modifier = Modifier.size(10.dp))
                    }
                }
                Text(
                    if (stn != null) "${fmtMhz(t.khz)} · ${stn.distanceKm.toInt()} km" else "MHz · preset",
                    color = HioneTextPrimary.copy(alpha = 0.85f), fontSize = 8.5.sp, maxLines = 1,
                )
                val sub = stn?.genre ?: stn?.reach?.takeIf { it != FmReach.Verdict.UNKNOWN }?.shortLabel ?: stn?.kind
                if (sub != null) {
                    Text(
                        sub.uppercase(), color = if (stn?.genre != null) genreColor(stn.genre) else stn?.reach?.color ?: CyberMuted,
                        fontSize = 7.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (selected) {
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp)
                    .width(36.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(color)
            )
        }
    }
}

/** Divider between "yours" and "near you". */
@Composable
private fun TierMarker() {
    Column(
        Modifier.width(26.dp).height(56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Default.NearMe, null, tint = HioneTeal.copy(alpha = 0.8f), modifier = Modifier.size(14.dp))
        Spacer(Modifier.height(2.dp))
        Text("NEAR", color = HioneTeal.copy(alpha = 0.8f), fontSize = 6.sp, fontFamily = AudiowideFont)
    }
}

@Composable
private fun AllStationsChip(onClick: () -> Unit) {
    Column(
        Modifier.width(64.dp).height(56.dp)
            .glass(RoundedCornerShape(14.dp), accent = HioneTeal, rimAlpha = 0.45f, shine = 0.8f)
            .pressable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Default.Search, "All stations", tint = HioneTeal, modifier = Modifier.size(18.dp))
        Spacer(Modifier.height(2.dp))
        Text("ALL", color = HioneTeal, fontSize = 8.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
    }
}

/** A translucent tint flattened onto the card colour, so selection reads as fill, not a veil. */
private fun Color.compositeOverDark(): Color {
    val base = Color(0xE00A1E26)
    val a = alpha
    return Color(
        red = red * a + base.red * (1 - a),
        green = green * a + base.green * (1 - a),
        blue = blue * a + base.blue * (1 - a),
        alpha = base.alpha,
    )
}
