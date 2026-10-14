package com.caf.fmradio

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/** Which bottom sheet is up. Some carry what they are about. */
private sealed interface FmSheet {
    data object None : FmSheet
    data object Settings : FmSheet
    data object Diagnostics : FmSheet
    data object Keypad : FmSheet
    data object SongHistory : FmSheet
    data class Stations(val tab: StationsTab) : FmSheet
    data class Detail(val target: PresetTarget) : FmSheet
}

/** One spacing step between the screen's blocks, so the rhythm is the same everywhere. */
private val GAP = 4.dp

@Composable
fun HioneFMRadioScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val st by FmRadioManager.state.collectAsState()
    var sheet by remember { mutableStateOf<FmSheet>(FmSheet.None) }
    var pathExpanded by rememberSaveable { mutableStateOf(false) }
    // Derived once per change of the scan list, not per recomposition: a fresh list every time
    // would defeat skipping of the whole spectrum panel.
    val scanHits = remember(st.scanResults) { st.scanResults.map { it.freqKHz } }

    BackHandler(enabled = sheet != FmSheet.None) { sheet = FmSheet.None }

    Box(Modifier.fillMaxSize().background(CyberDarkBg)) {
        FmVisualizerBackground(fallback = { FmBackdrop() })

        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            FmHeaderBar(st.isHeadsetPlugged, st.weatherAlerts, onBack,
                onAlerts = { sheet = FmSheet.Stations(StationsTab.WEATHER) }) { sheet = FmSheet.Settings }
            Spacer(Modifier.height(GAP))
            FmFrequencyCard(
                st,
                onKeypad = { sheet = FmSheet.Keypad },
                onStation = { s -> sheet = FmSheet.Detail(PresetTarget(s.khz, s, st.favorites.any { kotlin.math.abs(it - s.khz) <= 60 })) },
            )
            Spacer(Modifier.height(GAP))
            FmSignalMeter(st, Modifier.pressable(pressedScale = 0.98f) { sheet = FmSheet.Diagnostics })
            Spacer(Modifier.height(GAP))

            Box(Modifier.weight(1f).fillMaxWidth()) {
                RadioWaterfallSpectrum(
                    currentFreqKHz = st.frequencyKHz,
                    band = st.band,
                    isPowerOn = st.isPowerOn,
                    isHardwareOnline = st.isHardwareOnline,
                    hardwareError = st.hardwareError,
                    signalHistory = st.signalHistory,
                    bandProfile = st.bandProfile,
                    bandHistory = st.bandHistory,
                    favorites = st.favorites,
                    scanHits = scanHits,
                    cataloguedStations = st.nearbyStations,
                    skin = st.sdrSkin,
                    rowTimes = st.bandHistoryTimes,
                    mode = st.viewMode,
                    isScanning = st.isScanning,
                    onCycleSkin = { FmRadioManager.cycleSdrSkin() },
                    onTuneFreq = { FmRadioManager.tune(it) }
                )
                FmViewModeChips(st.viewMode, Modifier.align(Alignment.TopEnd).padding(top = 18.dp, end = 6.dp))
                SongIdCard(
                    st.songId,
                    onHistory = { sheet = FmSheet.SongHistory },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(6.dp),
                )
            }

            // The terrain between here and whatever is tuned, once a profile exists. Folded to
            // one line by default: the preset chips already carry the line-of-sight verdict, and
            // the full cross-section is 80 dp the analyser can use when you are not aiming.
            st.tunedProfile?.let { prof ->
                Spacer(Modifier.height(GAP))
                // The engine's annotated copy carries the measurement; the profile's own is raw.
                val tunedRated = st.tunedStation?.takeIf { it.call == prof.station.call }
                FmPathStrip(prof, tunedRated, pathExpanded) { pathExpanded = !pathExpanded }
            }

            Spacer(Modifier.height(GAP + 1.dp))
            FmTransportRow(st.isPowerOn, ctx)
            Spacer(Modifier.height(GAP))
            FmToolRow(
                stereoRequested = st.stereoRequested,
                isScanning = st.isScanning,
                scanProgress = st.scanProgress,
                isRecording = st.isRecording,
                recordedBytes = st.recordedBytes,
                songId = st.songId,
                hasScanResults = st.scanResults.isNotEmpty(),
                onScan = { if (st.isScanning) FmRadioManager.cancelSeek() else FmRadioManager.scanBand() },
                onStations = { sheet = FmSheet.Stations(StationsTab.STATIONS) },
                onRecord = { Toast.makeText(ctx, FmRadioManager.toggleRecording(ctx), Toast.LENGTH_SHORT).show() },
                onSongHistory = { sheet = FmSheet.SongHistory },
            )
            Spacer(Modifier.height(GAP))
            FmPresetBar(
                favorites = st.favorites,
                nearby = st.nearbyStations,
                frequencyKHz = st.frequencyKHz,
                hasPosition = st.listenerPlace != null,
                onDetails = { sheet = FmSheet.Detail(it) },
                onAllStations = { sheet = FmSheet.Stations(StationsTab.STATIONS) },
            )
            Spacer(Modifier.height(2.dp))
            FmGesturePill(ctx)
        }
    }

    // Transient volume readout. The wheel drives the tuner's own gain, so without this a turn
    // of the wheel changes something invisible. It shows on change and fades.
    FmVolumeHud(st.fmVolumeLevel)

    val close = { sheet = FmSheet.None }
    when (val sh = sheet) {
        FmSheet.None -> Unit
        FmSheet.Settings -> FmSheetFrame(close) { FmSettings(st) }
        FmSheet.Diagnostics -> FmSheetFrame(close) { FmDiagnosticsPanel(st) }
        FmSheet.Keypad -> FmSheetFrame(close) { FmKeypad(st, close) }
        FmSheet.SongHistory -> FmSheetFrame(close, maxHeight = 560.dp) { FmSongHistory(st.songId) }
        is FmSheet.Stations -> FmSheetFrame(close, maxHeight = 600.dp) {
            FmStationsSheet(st, sh.tab, onTuned = close, onDetails = { sheet = FmSheet.Detail(it) })
        }
        is FmSheet.Detail -> FmSheetFrame(close, maxHeight = 560.dp) { FmStationDetail(st, sh.target, close) }
    }
}

// ---------------------------------------------------------------------------- header

@Composable
private fun FmHeaderBar(
    headsetPlugged: Boolean,
    alerts: List<FmWeatherRadio.Alert>?,
    onBack: () -> Unit,
    onAlerts: () -> Unit,
    onSettings: () -> Unit,
) {
    // This board has no internal antenna, so the headphone cable is it. The chip reports what
    // is actually plugged in rather than a decorative "ANTENNA OK".
    val label = if (headsetPlugged) "ANTENNA OK" else "PLUG HEADSET"
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CyberIconButton(Icons.Default.ArrowBackIosNew, "Back", 40.dp, false, onBack)
        Text(
            "HIONE CYBER FM TUNER", color = HioneCyan, fontSize = 11.sp,
            fontWeight = FontWeight.Bold, fontFamily = AudiowideFont, letterSpacing = 1.sp
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Active NWS alerts here: a badge you can tap straight into the weather tab.
            if (!alerts.isNullOrEmpty()) {
                val c = alertColor(worstSeverity(alerts))
                Row(
                    Modifier.heightIn(min = 32.dp)
                        .glass(RoundedCornerShape(10.dp), accent = c, accent2 = c, fill = c.copy(alpha = 0.22f), rimAlpha = 1f)
                        .pressable(onClick = onAlerts)
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Warning, "Weather alerts", tint = c, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("${alerts.size}", color = c, fontSize = 8.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                }
                Spacer(Modifier.width(5.dp))
            }
            CyberChip(label, headsetPlugged)
            Spacer(Modifier.width(5.dp))
            CyberIconButton(Icons.Default.Tune, "Settings", 40.dp, false, onSettings)
        }
    }
}

@Composable
fun CyberChip(text: String, ok: Boolean, accent: Color = HioneCyan) {
    val c = if (ok) accent else HioneNeonPink
    Box(
        Modifier
            .glass(RoundedCornerShape(9.dp), accent = c, accent2 = c, fill = c.copy(alpha = 0.16f), rimAlpha = 0.9f, shine = 0.7f)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text, color = c, fontSize = 7.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
    }
}

/**
 * The round glass key used across the screen. [size] is the visible disc; the touch target is
 * never smaller than 48 dp regardless, because a 36 dp disc is a fine visual weight for a
 * secondary key and a poor target for a thumb.
 */
@Composable
fun CyberIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    size: Dp,
    active: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).pressable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.size(size).glass(
                CircleShape, accent = HioneCyan, accent2 = HionePink,
                fill = if (active) HioneCyan.copy(alpha = 0.26f) else Color(0xB00A1E26),
                rimAlpha = if (active) 1f else 0.4f, shine = if (active) 1.4f else 0.9f,
            ),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, label, tint = if (active) HioneCyan else HioneTextSecondary, modifier = Modifier.size(size * 0.48f))
        }
    }
}

// ---------------------------------------------------------------------------- frequency

@Composable
private fun FmFrequencyCard(st: FmState, onKeypad: () -> Unit, onStation: (FmStationCatalogue.Station) -> Unit) {
    val isFav = st.favorites.contains(st.frequencyKHz)
    val online = st.isHardwareOnline
    val statusColor = when {
        online -> HioneCyan
        st.hardwareError != null -> HioneNeonPink
        st.isPowerOn -> CyberAmber
        else -> Color.Gray
    }
    val pty = FmRadioManager.programmeTypeName(st.programmeType, st.band)
    val tuned = st.tunedStation
    val accent = tuned?.let { stationColor(it.call) } ?: HioneCyan

    Column(
        Modifier.fillMaxWidth()
            .glass(
                RoundedCornerShape(16.dp), accent = if (st.isPowerOn) accent else Color.White, accent2 = HionePink,
                fill = Color(0xC80A1E26), rimAlpha = if (st.isPowerOn) 0.9f else 0.2f, rimWidth = 1.2.dp,
            )
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(statusColor))
            Spacer(Modifier.width(5.dp))
            Text(
                when {
                    online -> {
                        val mode = when (st.isStereo) { true -> "STEREO"; false -> "MONO"; null -> "FM" }
                        "$mode · ${st.band.label}"
                    }
                    st.hardwareError != null -> "TUNER ERROR"
                    st.isPowerOn -> "STARTING TUNER…"
                    else -> "STANDBY"
                },
                color = statusColor, fontSize = 8.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont,
                modifier = Modifier.weight(1f), maxLines = 1,
            )
            if (tuned != null && tuned.reach != FmReach.Verdict.UNKNOWN) {
                Text(tuned.reach.shortLabel, color = tuned.reach.color, fontSize = 7.5.sp,
                     fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
            }
        }

        // Dial row: keypad and preset star flank the number as full-size thumb targets.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(CircleShape).pressable(onClick = onKeypad), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Dialpad, "Enter a frequency", tint = HioneTextSecondary, modifier = Modifier.size(22.dp))
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
                Text(
                    fmtMhz(st.frequencyKHz),
                    color = if (st.isPowerOn) Color.White else Color.Gray,
                    fontSize = 34.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont, letterSpacing = 1.sp
                )
                Spacer(Modifier.width(4.dp))
                Text("MHz", color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                     fontFamily = AudiowideFont, modifier = Modifier.padding(bottom = 6.dp))
            }
            Box(
                Modifier.size(48.dp).clip(CircleShape).pressable { FmRadioManager.togglePreset(st.frequencyKHz) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (isFav) Icons.Default.Star else Icons.Default.StarBorder,
                    if (isFav) "Remove preset" else "Save as preset",
                    tint = if (isFav) HioneNeonPink else HioneTextSecondary, modifier = Modifier.size(24.dp)
                )
            }
        }

        // RDS name when decoded; otherwise what the catalogue says is licensed on this channel
        // here, so the dial is not blank while the antenna hunts. Never an invented station.
        val headline = when {
            st.stationName.isNotEmpty() && pty != null -> "${st.stationName} · $pty"
            st.stationName.isNotEmpty() -> st.stationName
            tuned != null -> buildString {
                append(tuned.label)
                tuned.altName?.let { append(" (").append(it).append(")") }
                listOfNotNull(tuned.city, tuned.state).joinToString(", ").takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
            }
            st.rdsAvailable == false -> "No RDS on this station"
            online -> "No RDS name"
            else -> "—"
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp)
                .then(if (tuned != null) Modifier.pressable(pressedScale = 0.98f) { onStation(tuned) } else Modifier),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
        ) {
            Text(headline, color = HioneTextPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                 maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            tuned?.genre?.let { g ->
                Spacer(Modifier.width(6.dp))
                Text(g.uppercase(Locale.US), color = Color.Black, fontSize = 6.5.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                     modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(genreColor(g)).padding(horizontal = 4.dp, vertical = 1.dp))
            }
        }
        // The engine parks a "no RDS on <freq>" message in radioText when nothing decodes, which
        // the headline already says. Only show the second line when it is real radio text.
        val showsRealRadioText = st.stationName.isNotEmpty() && st.radioText.isNotBlank() &&
            !st.radioText.startsWith("No RDS") && !st.radioText.startsWith("Live tuner")
        val subline = when {
            showsRealRadioText -> st.radioText
            tuned?.format != null -> tuned.format
            else -> null
        }
        if (subline != null) {
            Text(subline, color = HioneTextSecondary, fontSize = 8.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                 modifier = Modifier.padding(horizontal = 10.dp))
        }
    }
}

// ---------------------------------------------------------------------------- path strip

@Composable
private fun FmPathStrip(
    prof: FmFresnel.Profile,
    rated: FmStationCatalogue.Station?,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    if (expanded) {
        Box(Modifier.pressable(pressedScale = 0.99f, onClick = onToggle)) {
            FmFresnelProfileView(prof, Modifier.height(82.dp), rated = rated)
        }
        return
    }
    val los = Los.of(prof.worst?.fresnelFraction)
    val s = rated ?: prof.station
    Row(
        Modifier.fillMaxWidth().height(32.dp)
            .glass(RoundedCornerShape(12.dp), accent = s.reach.takeIf { it != FmReach.Verdict.UNKNOWN }?.color ?: los.color,
                   rimAlpha = 0.45f, shine = 0.6f)
            .pressable(pressedScale = 0.98f, onClick = onToggle)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReceptionGauge(s.receptionScore, s.reach, prof.worst?.fresnelFraction, Modifier.size(24.dp), strokeDp = 2f)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "PATH TO ${prof.station.label} · ${"%.1f".format(Locale.US, prof.distanceKm)} km · ${prof.verdict}",
                color = HioneTeal.copy(alpha = 0.9f), fontSize = 8.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                pathLevels(s, prof), color = HioneTextSecondary, fontSize = 7.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (s.reach != FmReach.Verdict.UNKNOWN) {
            Text(s.reach.shortLabel, color = s.reach.color, fontSize = 8.sp, fontWeight = FontWeight.Bold)
        }
        Icon(Icons.Default.ExpandMore, "Show the terrain profile", tint = HioneTextSecondary, modifier = Modifier.size(18.dp))
    }
}

/** "measured 45 dBµV · predicted 41 (terrain -23 dB)": the reading first, the model second. */
internal fun pathLevels(s: FmStationCatalogue.Station, prof: FmFresnel.Profile): String = buildList {
    s.measuredDbuv?.let { add("measured $it dBµV") }
    val terrain = if (prof.diffractionLossDb > 0.5) String.format(Locale.US, " (terrain -%.0f dB)", prof.diffractionLossDb) else ""
    val predicted = s.predictedDbuv
    when {
        predicted != null -> add(String.format(Locale.US, "predicted %.0f dBµV", predicted) + terrain)
        terrain.isNotEmpty() -> add("predicted$terrain")
    }
    if (s.measuredDbuv == null) add("not measured yet")
}.joinToString(" · ")

// ---------------------------------------------------------------------------- transport

@Composable
private fun FmTransportRow(isPowerOn: Boolean, ctx: Context) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CyberIconButton(Icons.Default.FastRewind, "Seek down", 46.dp, false) { FmRadioManager.seek(false) }
        CyberIconButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Step down", 40.dp, false) { FmRadioManager.step(false) }

        // Power. Starting the service rather than the engine directly is what keeps the tuner
        // alive after this activity goes away.
        Box(
            Modifier.size(56.dp)
                .then(
                    if (isPowerOn) Modifier.clip(CircleShape)
                        .background(Brush.verticalGradient(listOf(HioneCyan, Color(0xFF006978))))
                    else Modifier
                )
                .glass(
                    CircleShape, accent = HioneCyan, accent2 = HionePink,
                    fill = if (isPowerOn) Color.Transparent else Color(0xC00A1E26),
                    rimAlpha = if (isPowerOn) 1f else 0.5f, rimWidth = 1.5.dp, shine = 1.6f,
                )
                .pressable { if (isPowerOn) HioneFmService.stop(ctx) else HioneFmService.start(ctx) },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.PowerSettingsNew, if (isPowerOn) "Turn the radio off" else "Turn the radio on",
                tint = if (isPowerOn) Color.Black else HioneTextSecondary, modifier = Modifier.size(26.dp)
            )
        }

        CyberIconButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Step up", 40.dp, false) { FmRadioManager.step(true) }
        CyberIconButton(Icons.Default.FastForward, "Seek up", 46.dp, false) { FmRadioManager.seek(true) }
    }
}

// ---------------------------------------------------------------------------- tools

/*
 * The volume row used to live here, between the transport and the tool rows. Removed: the M500
 * has a hardware volume wheel, which is a better control than a 4 mm slider on a 720 px screen.
 * The keypad key that used to sit in this row is gone too; the dial card has one beside the
 * frequency, which is where you look when you want to type one. Its slot went to song ID.
 */
@Composable
private fun FmToolRow(
    stereoRequested: Boolean,
    isScanning: Boolean,
    scanProgress: Float,
    isRecording: Boolean,
    recordedBytes: Long,
    songId: FmSongId.State,
    hasScanResults: Boolean,
    onScan: () -> Unit,
    onStations: () -> Unit,
    onRecord: () -> Unit,
    onSongHistory: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CyberIconButton(
            if (stereoRequested) Icons.Default.Headphones else Icons.Default.HearingDisabled,
            if (stereoRequested) "Stereo requested" else "Mono", 42.dp, stereoRequested
        ) { FmRadioManager.setStereo(!stereoRequested) }

        FmScanButton(isScanning, scanProgress, onScan)

        SongIdButton(songId, onHistory = onSongHistory, modifier = Modifier.widthIn(min = 104.dp))

        FmRecordButton(isRecording = isRecording, onClick = onRecord)

        CyberIconButton(Icons.AutoMirrored.Filled.FormatListBulleted, "Stations", 42.dp, hasScanResults, onStations)
    }
    if (isRecording) {
        val seconds = recordedBytes / (48000 * 2 * 2)
        Text(
            "REC %d:%02d · %.1f MB".format(seconds / 60, seconds % 60, recordedBytes / 1_048_576.0),
            color = Color(0xFFFF5252), fontSize = 7.5.sp, fontFamily = AudiowideFont
        )
    }
}

// ---------------------------------------------------------------------------- gesture pill

@Composable
private fun FmGesturePill(ctx: Context) {
    Box(
        Modifier.fillMaxWidth().height(13.dp).padding(bottom = 1.dp)
            .pointerInput(Unit) {
                var startTime = 0L
                var totalY = 0f
                detectDragGestures(
                    onDragStart = { startTime = android.os.SystemClock.elapsedRealtime(); totalY = 0f },
                    onDragEnd = {
                        val duration = android.os.SystemClock.elapsedRealtime() - startTime
                        if (totalY < -30f) {
                            if (duration >= 250L || totalY < -100f) {
                                ctx.packageManager.getLaunchIntentForPackage("com.hione.launcher")?.apply {
                                    putExtra("open_recents", true)
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                                }?.let { runCatching { ctx.startActivity(it) } }
                            } else {
                                runCatching {
                                    ctx.startActivity(Intent(Intent.ACTION_MAIN).apply {
                                        addCategory(Intent.CATEGORY_HOME)
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    })
                                }
                            }
                        }
                    },
                    onDrag = { change, amount -> change.consume(); totalY += amount.y }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.width(60.dp).height(3.5.dp).clip(RoundedCornerShape(2.dp))
                .background(HioneCyan.copy(alpha = 0.9f))
        )
    }
}

@Composable
private fun FmVolumeHud(level: Int) {
    var visible by remember { mutableStateOf(false) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(level) {
        if (first) { first = false; return@LaunchedEffect }   // do not flash on first compose
        visible = true
        kotlinx.coroutines.delay(1400)
        visible = false
    }
    if (!visible) return
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.glass(RoundedCornerShape(16.dp), accent = HioneCyan, fill = Color(0xEE02090D), rimAlpha = 0.9f)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("TUNER VOLUME", color = HioneTextSecondary, fontSize = 7.5.sp, fontFamily = AudiowideFont)
            Spacer(Modifier.height(5.dp))
            Text("$level", color = HioneCyan, fontSize = 26.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
            Spacer(Modifier.height(6.dp))
            Box(Modifier.width(150.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x33FFC56B))) {
                Box(Modifier.fillMaxWidth(level / 100f).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(HioneCyan))
            }
            Spacer(Modifier.height(4.dp))
            Text("independent of media volume", color = HioneTextSecondary, fontSize = 7.sp)
        }
    }
}

/**
 * LIVE | BAND. BAND sweeps the whole band over and over while it is selected, and the radio is
 * muted for each sweep (one chip, one frequency at a time), so it says so on the chip.
 */
@Composable
private fun FmViewModeChips(mode: QualcommFmHardwareEngine.ViewMode, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (m in QualcommFmHardwareEngine.ViewMode.values()) {
            val on = mode == m
            Box(
                Modifier.heightIn(min = 30.dp)
                    .glass(RoundedCornerShape(8.dp), accent = HioneTeal, fill = if (on) HioneTeal else Color(0xCC03141B),
                           rimAlpha = if (on) 1f else 0.5f, shine = if (on) 1.2f else 0.6f)
                    .pressable { FmRadioManager.setViewMode(m) }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    when (m) {
                        QualcommFmHardwareEngine.ViewMode.LIVE -> "LIVE"
                        QualcommFmHardwareEngine.ViewMode.BAND -> if (on) "BAND · SCANNING" else "BAND"
                    },
                    color = if (on) Color.Black else HioneTeal,
                    fontSize = 8.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------- backdrop

/**
 * The artwork and its darkening gradient, baked once into one opaque bitmap at screen size.
 *
 * The JPEG lives in drawable/ (no density qualifier), so painterResource decoded it as mdpi and
 * scaled it to 1536x2752 on this xhdpi screen: a 17 MB texture sampled down every frame, plus a
 * full-screen gradient blended over it. Baked, it is one 720x1280 opaque blit (no blending) and
 * the gradient costs nothing per frame.
 */
@Composable
private fun FmBackdrop() {
    val ctx = LocalContext.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val img by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, w, h) {
            if (w <= 0 || h <= 0) return@produceState
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                runCatching { bakeBackdrop(ctx, w, h) }.getOrNull()
            }
        }
        img?.let {
            Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        }
    }
}

private fun bakeBackdrop(ctx: Context, w: Int, h: Int): androidx.compose.ui.graphics.ImageBitmap {
    val src = android.graphics.BitmapFactory.decodeResource(
        ctx.resources, R.drawable.hione_bg_fm, android.graphics.BitmapFactory.Options().apply { inScaled = false })
    val out = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(out)
    c.drawColor(0xFF0C0D10.toInt())
    // Centre crop.
    val scale = maxOf(w / src.width.toFloat(), h / src.height.toFloat())
    val dw = src.width * scale
    val dh = src.height * scale
    val dst = android.graphics.RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f)
    c.drawBitmap(src, null, dst, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
    src.recycle()
    val g = android.graphics.Paint().apply {
        shader = android.graphics.LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(0xCC0C0D10.toInt(), 0x77000000, 0xF50C0D10.toInt()), null,
            android.graphics.Shader.TileMode.CLAMP)
    }
    c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), g)
    out.setHasAlpha(false)
    return out.asImageBitmap()
}

// ---------------------------------------------------------------------------- scan button

/**
 * The scan key. Idle it is a plain glass key; scanning it becomes a little radar: a sweep that
 * turns, and a pink ring that fills with the sweep's real progress. Tap again to stop. The
 * rotation lives in a graphics layer and its animation only exists while a scan runs.
 */
@Composable
private fun FmScanButton(isScanning: Boolean, progress: Float, onClick: () -> Unit) {
    if (!isScanning) {
        CyberIconButton(Icons.Default.Radar, "Scan the band", 42.dp, false, onClick)
        return
    }
    val t = rememberInfiniteTransition(label = "radar")
    val angle = t.animateFloat(0f, 360f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "sweep")
    Box(Modifier.size(48.dp).pressable(onClick = onClick), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(42.dp).glass(CircleShape, accent = HionePink, accent2 = HioneCyan,
                fill = Color(0xD0101C28), rimAlpha = 1f, shine = 1.4f)
        )
        androidx.compose.foundation.Canvas(Modifier.size(42.dp).graphicsLayer { rotationZ = angle.value }) {
            drawArc(
                Brush.sweepGradient(listOf(Color.Transparent, HioneTeal.copy(alpha = 0.1f), HioneCyan.copy(alpha = 0.7f))),
                -90f, 90f, true, topLeft = androidx.compose.ui.geometry.Offset(4f, 4f),
                size = androidx.compose.ui.geometry.Size(size.width - 8f, size.height - 8f)
            )
        }
        androidx.compose.foundation.Canvas(Modifier.size(42.dp)) {
            val sw = 2.5.dp.toPx()
            drawArc(HionePink, -90f, 360f * progress.coerceIn(0f, 1f), false,
                topLeft = androidx.compose.ui.geometry.Offset(sw / 2, sw / 2),
                size = androidx.compose.ui.geometry.Size(size.width - sw, size.height - sw),
                style = androidx.compose.ui.graphics.drawscope.Stroke(sw, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
        Icon(Icons.Default.Close, "Stop the scan", tint = Color.White, modifier = Modifier.size(16.dp))
    }
}
