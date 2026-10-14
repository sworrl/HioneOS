package com.caf.fmradio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Thunderstorm
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

enum class StationsTab(val label: String) { STATIONS("STATIONS"), SCANNED("SCANNED"), WEATHER("WEATHER") }

private enum class StationSort(val label: String) { REACH("BEST"), DISTANCE("NEAREST"), FREQUENCY("MHz") }

/**
 * Every station, searchable, by what it plays and whether it reaches you.
 *
 * With an empty query this is the nearby list the engine already holds (no database work).
 * Typing searches the whole catalogue: call, city, licensee, format, genre or a frequency like
 * "94.5", debounced so a word is one query rather than one per letter, and run on IO because it
 * is blocking SQLite.
 *
 * Stations are grouped by verdict rather than mixed: the ones that should come in here first,
 * then the unrated ones, then "out of reach here" folded away. That is the answer to "why am I
 * being shown Pittsburgh": those stations are licensed within range of somewhere you have been,
 * and the reach model says they will not make it here, so they are listed as such instead of
 * sitting between the locals.
 */
@Composable
fun ColumnScope.FmStationsSheet(
    st: FmState,
    initialTab: StationsTab,
    onTuned: () -> Unit,
    onDetails: (PresetTarget) -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (t in StationsTab.entries) {
            val on = t == tab
            val count = when (t) {
                StationsTab.STATIONS -> null
                StationsTab.SCANNED -> st.scanResults.size.takeIf { it > 0 }
                StationsTab.WEATHER -> st.weatherRadios.size.takeIf { it > 0 }
            }
            Box(
                Modifier.weight(1f).height(40.dp)
                    .glass(RoundedCornerShape(12.dp), accent = if (t == StationsTab.WEATHER) CyberAmber else HioneTeal,
                           fill = if (on) HioneTeal.copy(alpha = 0.25f) else Color(0x880A1E26),
                           rimAlpha = if (on) 1f else 0.35f, shine = if (on) 1.3f else 0.6f)
                    .pressable { tab = t },
                contentAlignment = Alignment.Center
            ) {
                val alerts = if (t == StationsTab.WEATHER) st.weatherAlerts.orEmpty() else emptyList()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        t.label + (count?.let { " · $it" } ?: ""),
                        color = if (on) Color.White else HioneTextSecondary,
                        fontSize = 8.5.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont,
                    )
                    // Active NWS alerts: a badge in the worst severity's colour.
                    if (alerts.isNotEmpty()) {
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "!${alerts.size}", color = Color.Black, fontSize = 7.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(alertColor(worstSeverity(alerts)))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    when (tab) {
        StationsTab.STATIONS -> StationsTabContent(st, onTuned, onDetails)
        StationsTab.SCANNED -> FmScanResults(st, onTuned)
        StationsTab.WEATHER -> FmWeatherRadioList(st)
    }
}

@Composable
private fun ColumnScope.StationsTabContent(
    st: FmState,
    onTuned: () -> Unit,
    onDetails: (PresetTarget) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var genre by rememberSaveable { mutableStateOf<String?>(null) }
    var sort by rememberSaveable { mutableStateOf(StationSort.REACH) }
    var showFar by rememberSaveable { mutableStateOf(false) }
    var searched by remember { mutableStateOf<List<FmStationCatalogue.Station>?>(null) }
    var searching by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty()) { searched = null; searching = false; return@LaunchedEffect }
        searching = true
        delay(250)
        searched = withContext(Dispatchers.IO) { runCatching { FmRadioManager.searchStations(q) }.getOrDefault(emptyList()) }
        searching = false
    }

    // Deduplicated on what the list keys by: the catalogue can carry the same call on the same
    // channel twice (an auxiliary licence), and a repeated lazy key is a crash, not a glitch.
    val raw = searched ?: st.nearbyStations
    val source = remember(raw) { raw.distinctBy { it.call + "@" + it.khz } }
    val genres = remember(source) {
        source.mapNotNull { it.genre }.groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }.map { it.key to it.value }
    }
    val filtered = remember(source, genre, sort) {
        val list = if (genre == null) source else source.filter { it.genre == genre }
        when (sort) {
            StationSort.REACH -> list.sortedWith(compareBy<FmStationCatalogue.Station>(
                { it.reach.rank() }, { -(it.receptionScore ?: 0f) }, { it.distanceKm }))
            StationSort.DISTANCE -> list.sortedBy { it.distanceKm }
            StationSort.FREQUENCY -> list.sortedBy { it.khz }
        }
    }
    val near = filtered.filter { it.reach.inReach }
    val unrated = filtered.filter { it.reach == FmReach.Verdict.UNKNOWN }
    val far = filtered.filter { it.reach.outOfReach }

    // Search field.
    Row(
        Modifier.fillMaxWidth().height(46.dp)
            .glass(RoundedCornerShape(23.dp), accent = HioneTeal, accent2 = HionePink, rimAlpha = 0.7f)
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Search, null, tint = HioneTeal, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text("Call, city, format, or 94.5", color = HioneTextSecondary.copy(alpha = 0.6f), fontSize = 11.sp)
            }
            BasicTextField(
                value = query,
                onValueChange = { query = it.take(40) },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 12.sp),
                cursorBrush = SolidColor(HionePink),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Box(Modifier.size(40.dp).clip(CircleShape).pressable { query = ""; focus.clearFocus() },
                contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Close, "Clear", tint = HioneTextSecondary, modifier = Modifier.size(18.dp))
            }
        }
    }
    Spacer(Modifier.height(6.dp))

    // Sort + genre filters on one scrolling row: vertical space is the scarce thing here.
    LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(StationSort.entries) { s ->
            FilterChip(s.label, s == sort, HioneCyan) { sort = s }
        }
        item { Box(Modifier.width(1.dp).height(30.dp).background(HioneTeal.copy(alpha = 0.3f))) }
        item { FilterChip("ALL", genre == null, HioneTeal) { genre = null } }
        items(genres, key = { it.first }) { (g, n) ->
            FilterChip("${g.uppercase(Locale.US)} $n", genre == g, genreColor(g)) { genre = if (genre == g) null else g }
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(
        when {
            searching -> "Searching the catalog…"
            searched != null -> "${source.size} match${if (source.size == 1) "" else "es"} for \"${query.trim()}\""
            st.listenerPlace == null -> "No position yet, so nothing is rated for reach."
            else -> buildString {
                append("${source.size} licensed near you")
                if (st.reachCalibratedFrom > 0) {
                    append(String.format(Locale.US, " · reach calibrated on %d heard (%+.0f dB)",
                        st.reachCalibratedFrom, st.reachOffsetDb))
                }
                if (genres.isEmpty() && source.isNotEmpty()) append(" · formats not in this catalog yet")
            }
        },
        color = HioneTextSecondary, fontSize = 7.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(4.dp))

    // An empty search that found nothing near you but did find far stations should show them.
    val farOpen = showFar || (searched != null && near.isEmpty() && unrated.isEmpty())
    LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (near.isNotEmpty()) {
            item(key = "h-near") { GroupHeader("IN REACH HERE", near.size, HioneTeal) }
            items(near, key = { "n" + it.call + "@" + it.khz }) { StationRow(it, st, false, onTuned, onDetails) }
        }
        if (unrated.isNotEmpty()) {
            item(key = "h-unrated") { GroupHeader("NOT RATED", unrated.size, CyberMuted) }
            items(unrated, key = { "u" + it.call + "@" + it.khz }) { StationRow(it, st, false, onTuned, onDetails) }
        }
        if (far.isNotEmpty()) {
            item(key = "h-far") {
                GroupHeader("OUT OF REACH HERE", far.size, HionePink, expandable = true, expanded = farOpen) { showFar = !farOpen }
            }
            if (farOpen) items(far, key = { "f" + it.call + "@" + it.khz }) { StationRow(it, st, true, onTuned, onDetails) }
        }
        if (filtered.isEmpty() && !searching) {
            item {
                Text(
                    if (searched != null) "Nothing in the catalog matches." else "No stations listed near here yet.",
                    color = HioneTextPrimary, fontSize = 9.sp, modifier = Modifier.padding(vertical = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun FilterChip(label: String, on: Boolean, accent: Color, onClick: () -> Unit) {
    Box(
        Modifier.height(32.dp)
            .glass(RoundedCornerShape(16.dp), accent = accent, fill = if (on) accent.copy(alpha = 0.28f) else Color(0x880A1E26),
                   rimAlpha = if (on) 1f else 0.35f, shine = if (on) 1.2f else 0.5f)
            .pressable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (on) Color.White else accent, fontSize = 8.sp, fontWeight = FontWeight.Bold,
             fontFamily = AudiowideFont, maxLines = 1)
    }
}

@Composable
private fun GroupHeader(
    title: String, count: Int, accent: Color,
    expandable: Boolean = false, expanded: Boolean = true, onToggle: () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = if (expandable) 44.dp else 24.dp)
            .then(if (expandable) Modifier.clip(RoundedCornerShape(10.dp)).pressable(pressedScale = 0.98f, onClick = onToggle) else Modifier)
            .padding(top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(accent))
        Spacer(Modifier.width(6.dp))
        Text("$title · $count", color = accent, fontSize = 8.5.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
        Spacer(Modifier.weight(1f))
        if (expandable) {
            Text(if (expanded) "HIDE" else "SHOW", color = accent, fontSize = 8.sp, fontFamily = AudiowideFont)
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = accent, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun StationRow(
    s: FmStationCatalogue.Station,
    st: FmState,
    dim: Boolean,
    onTuned: () -> Unit,
    onDetails: (PresetTarget) -> Unit,
) {
    val color = stationColor(s.call)
    val tuned = abs(s.khz - st.frequencyKHz) <= 60 && st.tunedStation?.call == s.call
    val fav = st.favorites.any { abs(it - s.khz) <= 60 }
    // A station outside the tuner's band (search can find them) cannot be tuned.
    val tunable = s.khz in st.band.lowKHz..st.band.highKHz
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp)
            .alpha(if (dim) 0.6f else 1f)
            .glass(RoundedCornerShape(12.dp), accent = color, rimAlpha = if (tuned) 1f else 0.3f,
                   fill = if (tuned) color.copy(alpha = 0.16f) else Color(0x990A1E26), shine = if (tuned) 1.2f else 0.5f)
            .pressable(pressedScale = 0.98f, onLongClick = { onDetails(PresetTarget(s.khz, s, fav)) }) {
                if (tunable) { FmRadioManager.tune(s.khz); onTuned() } else onDetails(PresetTarget(s.khz, s, fav))
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).height(60.dp).background(color))
        Spacer(Modifier.width(8.dp))
        ReceptionGauge(s.receptionScore, s.reach, s.fresnelFraction, Modifier.size(36.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(s.label, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont, maxLines = 1,
                     overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(6.dp))
                Text(fmtMhz(s.khz), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                if (s.service != "FM") {
                    Spacer(Modifier.width(4.dp))
                    Text(s.kind, color = HioneTextSecondary, fontSize = 7.5.sp)
                }
            }
            Text(
                listOfNotNull(s.altName, listOfNotNull(s.city, s.state).joinToString(", ").ifBlank { null },
                              "${s.distanceKm.toInt()} km").joinToString(" · "),
                color = HioneTextPrimary.copy(alpha = 0.8f), fontSize = 8.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                s.genre?.let { g ->
                    Text(
                        g.uppercase(Locale.US), color = Color.Black, fontSize = 6.5.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(genreColor(g)).padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    s.format ?: if (s.genre == null) "format not listed" else "",
                    color = HioneTextSecondary, fontSize = 7.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 4.dp)) {
            Text(s.reach.shortLabel, color = s.reach.color, fontSize = 7.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
            Text(Los.of(s.fresnelFraction).label, color = Los.of(s.fresnelFraction).color, fontSize = 7.sp)
        }
        Box(
            Modifier.size(44.dp).clip(CircleShape).pressable { FmRadioManager.togglePreset(s.khz) },
            contentAlignment = Alignment.Center
        ) {
            Icon(if (fav) Icons.Default.Star else Icons.Default.StarBorder, if (fav) "Remove preset" else "Add preset",
                 tint = if (fav) HioneNeonPink else HioneTextSecondary, modifier = Modifier.size(20.dp))
        }
    }
}

// ============================================================================ detail

/**
 * One station in full: everything the catalogue and the reach/terrain models know, and the two
 * actions a preset chip's long-press promises (tune, add/remove preset).
 */
@Composable
fun ColumnScope.FmStationDetail(st: FmState, target: PresetTarget, onClose: () -> Unit) {
    // Prefer the live copy: terrain and measurements land in the engine's list after the sheet opens.
    val listed = target.station?.let { t -> st.nearbyStations.firstOrNull { it.call == t.call } ?: t }
    // Stations the background prefetch skipped (search results, far ones) get their terrain
    // fetched here, once, when the sheet opens on them.
    var fetched by remember(target) { mutableStateOf<FmStationCatalogue.Station?>(null) }
    LaunchedEffect(target, listed?.fresnelFraction) {
        if (listed != null && listed.fresnelFraction == null) {
            fetched = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { FmRadioManager.terrainFor(listed) }
        }
    }
    val s = if (listed?.fresnelFraction == null) fetched ?: listed else listed
    val color = s?.let { stationColor(it.call) } ?: frequencyColor(target.khz)
    val fav = st.favorites.any { abs(it - target.khz) <= 60 }
    val tunable = target.khz in st.band.lowKHz..st.band.highKHz

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(5.dp).height(48.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(s?.label ?: "${fmtMhz(target.khz)} MHz", color = color, fontSize = 18.sp,
                 fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
            Text(
                if (s != null) listOfNotNull(s.altName, "${fmtMhz(s.khz)} MHz", s.kind,
                    listOfNotNull(s.city, s.state).joinToString(", ").ifBlank { null }).joinToString(" · ")
                else "A preset the station catalog has no entry for here.",
                color = HioneTextPrimary, fontSize = 9.sp,
            )
        }
        if (s != null) ReceptionGauge(s.receptionScore, s.reach, s.fresnelFraction, Modifier.size(52.dp), strokeDp = 4f)
    }
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (tunable) {
            DetailAction("TUNE", HioneCyan, Modifier.weight(1f)) { FmRadioManager.tune(target.khz); onClose() }
        }
        DetailAction(if (fav) "REMOVE PRESET" else "ADD PRESET", if (fav) HioneNeonPink else HionePink, Modifier.weight(1f)) {
            FmRadioManager.togglePreset(target.khz)
            if (fav) onClose()
        }
    }
    Spacer(Modifier.height(10.dp))
    if (s == null) return
    Column(Modifier.verticalScroll(rememberScrollState()).weight(1f, fill = false)) {
        val los = Los.of(s.fresnelFraction)
        DetailRow("Reach here", s.reach.label, s.reach.color)
        DetailRow("Reception odds", s.receptionScore?.let { "${(it * 100).toInt()} / 100" } ?: "—")
        DetailRow("Measured", s.measuredDbuv?.let { "$it dBµV (${s.measuredWhere})" } ?: s.measuredWhere)
        DetailRow("Predicted", s.predictedDbuv?.let { String.format(Locale.US, "%.0f dBµV, a model estimate", it) } ?: "—")
        DetailRow("Line of sight", buildString {
            append(s.terrainVerdict ?: los.label)
            s.fresnelFraction?.let { append(String.format(Locale.US, " · %.0f%% of first Fresnel zone", it * 100)) }
        }, los.color)
        s.terrainLossDb?.takeIf { it > 0.5 }?.let { DetailRow("Terrain loss", String.format(Locale.US, "~%.0f dB predicted, diffraction over the ridge", it)) }
        DetailRow("Distance", String.format(Locale.US, "%.1f km", s.distanceKm))
        DetailRow("Power", s.erpKw?.let {
            String.format(Locale.US, "%.2f kW ERP", it) + if (s.erpEstimated) ", estimated from transmitter power" else ""
        } ?: "—")
        DetailRow("Antenna height", listOfNotNull(
            s.haatM?.takeIf { it > 0 }?.let { "${it.toInt()} m above average terrain" },
            s.rcamslM?.let { "${it.toInt()} m above sea level" },
        ).joinToString(" · ").ifEmpty { "—" } + if (s.haatEstimated) " (mast height estimated)" else "")
        if (s.isJapan) {
            DetailRow("Call sign", if ('_' in s.call) "none published for this relay" else s.call)
            s.parentCall?.let { DetailRow("Relays", it) }
            DetailRow("Site", when (s.sitePrecision) {
                "wikipedia" -> "transmitter coordinates"
                "oaza" -> "placed by district, within a km or two"
                "municipality" -> "placed at the town, can be several km off"
                "oaza-hill", "municipality-hill" -> "placed on the high ground near the town, can be a few km off"
                else -> "—"
            })
        }
        DetailRow("Format", s.format ?: "—")
        DetailRow("Genre", s.genre ?: "—", genreColor(s.genre))
        DetailRow("Licensee", s.licensee ?: "—")
        s.callsignSince?.let { DetailRow("Call sign since", it) }
        Spacer(Modifier.height(4.dp))
        Text(
            "Reach is a calibrated prediction from power, height, distance and (when fetched) the " +
                "terrain profile. A reading from the tuner here, while tuned or in a band sweep, overrides it.",
            color = HioneTextSecondary, fontSize = 7.5.sp, lineHeight = 10.sp,
        )
    }
}

@Composable
private fun DetailAction(label: String, accent: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(48.dp)
            .glass(RoundedCornerShape(14.dp), accent = accent, fill = accent.copy(alpha = 0.18f), rimAlpha = 0.9f, shine = 1.2f)
            .pressable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
    }
}

@Composable
private fun DetailRow(label: String, value: String, valueColor: Color = Color.White) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = HioneTextSecondary, fontSize = 9.sp, modifier = Modifier.width(108.dp))
        Text(value, color = valueColor, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}
