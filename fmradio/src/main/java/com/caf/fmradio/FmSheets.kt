package com.caf.fmradio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/** A scrim plus a bottom card. Written by hand rather than with ModalBottomSheet because this
 *  activity runs fully immersive with no system bars, and the sheet component wants insets the
 *  window does not have. It does honour the IME inset, so the station search field rides up
 *  above the keyboard instead of being covered by it. */
@Composable
fun FmSheetFrame(
    onDismiss: () -> Unit,
    maxHeight: androidx.compose.ui.unit.Dp = 520.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    Box(
        Modifier.fillMaxSize().background(Color(0xCC000000))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            )
            .imePadding(),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            Modifier.fillMaxWidth()
                .heightIn(max = maxHeight)
                .glass(shape, accent = HioneCyan, accent2 = HionePink, fill = Color(0xF5061219), rimAlpha = 0.7f)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Box(
                Modifier.align(Alignment.CenterHorizontally).width(44.dp).height(4.dp)
                    .clip(RoundedCornerShape(2.dp)).background(HioneCyan.copy(alpha = 0.5f))
            )
            Spacer(Modifier.height(10.dp))
            content()
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(
        text.uppercase(Locale.US), color = HioneCyan, fontSize = 11.sp,
        fontWeight = FontWeight.Bold, fontFamily = AudiowideFont, letterSpacing = 1.sp
    )
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun SheetNote(text: String) {
    Text(text, color = HioneTextSecondary, fontSize = 8.sp, lineHeight = 11.sp)
}

// ---------------------------------------------------------------------------- scan results

@Composable
fun ColumnScope.FmScanResults(st: FmState, onTuned: () -> Unit) {
    SheetNote("Stations found by the last scan of ${st.band.label}: every stop is a frequency the tuner itself locked onto, with the signal measured at that moment.")
    Spacer(Modifier.height(6.dp))
    if (st.scanResults.isEmpty()) {
        SheetNote(
            "Nothing yet. Tap the scan key to sweep the band. With no antenna attached a sweep " +
                "finds nothing."
        )
        return
    }
    LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        items(st.scanResults) { hit ->
            val current = hit.freqKHz == st.frequencyKHz
            val fav = st.favorites.contains(hit.freqKHz)
            val stn = stationOn(hit.freqKHz, st.nearbyStations)
            val color = stn?.let { stationColor(it.call) } ?: frequencyColor(hit.freqKHz)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    .glass(RoundedCornerShape(12.dp), accent = color, rimAlpha = if (current) 1f else 0.3f,
                           fill = if (current) color.copy(alpha = 0.16f) else Color(0x990A1E26), shine = 0.6f)
                    .pressable(pressedScale = 0.98f) { FmRadioManager.tune(hit.freqKHz); onTuned() }
                    .padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    String.format(Locale.US, "%.1f", hit.freqKHz / 1000.0),
                    color = if (current) HioneCyan else Color.White,
                    fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont,
                    modifier = Modifier.width(64.dp)
                )
                Column(Modifier.weight(1f)) {
                    Text(stn?.label ?: "not in the catalog here", color = if (stn != null) color else HioneTextSecondary,
                         fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                    Text(
                        buildString {
                            append("RSSI ").append(hit.rssi?.toString() ?: "—")
                            hit.snr?.let { append("   SNR ").append(it) }
                            stn?.genre?.let { append("   ").append(it) }
                        },
                        color = HioneTextSecondary, fontSize = 8.sp, fontFamily = AudiowideFont
                    )
                }
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).pressable { FmRadioManager.togglePreset(hit.freqKHz) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (fav) Icons.Default.Star else Icons.Default.StarBorder, "Preset",
                        tint = if (fav) HioneNeonPink else Color.Gray, modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------- settings

@Composable
fun FmSettings(st: FmState) {
    SheetTitle("Tuner settings")
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("REGION", color = HioneTextSecondary, fontSize = 7.5.sp, fontFamily = AudiowideFont)
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(if (st.bandAuto) HioneCyan.copy(alpha = 0.2f) else Color.Transparent)
                .clickable { FmRadioManager.setBandAuto() }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Automatic", color = if (st.bandAuto) HioneCyan else Color.White,
                fontSize = 10.sp, fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (st.bandAuto) st.band.label + (st.bandAutoReason?.let { " · $it" } ?: "")
                else "Japan or North America, from where you are",
                color = HioneTextSecondary, fontSize = 8.sp
            )
        }
        FmBandPlan.entries.forEach { plan ->
            val sel = plan == st.band && !st.bandAuto
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(if (sel) HioneCyan.copy(alpha = 0.2f) else Color.Transparent)
                    .clickable { FmRadioManager.setBand(plan) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    plan.label, color = if (sel) HioneCyan else Color.White,
                    fontSize = 10.sp, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "%.1f–%.1f MHz · %d kHz · %d µs".format(
                        plan.lowKHz / 1000.0, plan.highKHz / 1000.0, plan.stepKHz, plan.emphasisUs
                    ),
                    color = HioneTextSecondary, fontSize = 8.sp
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        SheetNote(
            "The region sets the band limits, channel spacing, de-emphasis and RDS standard, " +
                "all of which the chip only reads when it is enabled. Changing it therefore " +
                "power-cycles the tuner. Automatic picks Japan (76.0-95.0 MHz, 100 kHz) when the " +
                "nearest stations in the catalog are Japanese, and North America when they are " +
                "US, Canadian or Mexican. Before the device knows where it is, it goes by the " +
                "SIM country, or a Japanese locale together with the Tokyo time zone."
        )

        Spacer(Modifier.height(12.dp))
        Text("CHANNEL GRID", color = HioneTextSecondary, fontSize = 7.5.sp, fontFamily = AudiowideFont)
        Spacer(Modifier.height(4.dp))
        FmChannelGrid.entries.forEach { g ->
            val sel = g == st.channelGrid
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(if (sel) HioneCyan.copy(alpha = 0.2f) else Color.Transparent)
                    .clickable { FmRadioManager.setChannelGrid(g) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(g.label, color = if (sel) HioneCyan else Color.White,
                     fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(g.detail, color = HioneTextSecondary, fontSize = 7.5.sp)
            }
        }
        Spacer(Modifier.height(3.dp))
        SheetNote(
            "North America puts every FM channel on an odd tenth, 200 kHz apart. Most of the " +
                "rest of the world uses every tenth at 100 kHz. Automatic follows the region " +
                "above and is right unless you are chasing something unusual."
        )

        Spacer(Modifier.height(12.dp))
        Text("SEEK SENSITIVITY", color = HioneTextSecondary, fontSize = 7.5.sp, fontFamily = AudiowideFont)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            QualcommFmHardwareEngine.SENSITIVITY_LABELS.forEachIndexed { i, label ->
                val sel = i == st.seekSensitivity
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                        .background(if (sel) HioneCyan else Color(0x22FFC56B))
                        .border(1.dp, if (sel) HioneCyan else CyberGlassBorder, RoundedCornerShape(8.dp))
                        .clickable { FmRadioManager.setSeekSensitivity(i) }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label, color = if (sel) Color.Black else HioneTextSecondary,
                        fontSize = 8.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont
                    )
                }
            }
        }
        Spacer(Modifier.height(3.dp))
        SheetNote("How strong a carrier has to be before seek will stop on it.")

        Spacer(Modifier.height(10.dp))
        SettingSwitch(
            "Stereo",
            "Ask for stereo decoding. The chip drops to mono by itself on a weak signal, and the " +
                "indicator above shows what it actually did, not what was requested.",
            st.stereoRequested
        ) { FmRadioManager.setStereo(it) }

        SettingSwitch(
            "Soft mute",
            "Let the tuner attenuate between stations instead of handing you full-scale hiss.",
            st.softMute
        ) { FmRadioManager.setSoftMute(it) }

        SettingSwitch(
            "Follow alternative frequencies",
            "Use the RDS AF list to hop to a stronger transmitter carrying the same program. " +
                "Off by default: it retunes without being asked, which is not always wanted.",
            st.afJump
        ) { FmRadioManager.setAfJump(it) }
        Spacer(Modifier.height(10.dp))
        FmBackgroundSetting()
    }
}

@Composable
private fun SettingSwitch(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(detail, color = HioneTextSecondary, fontSize = 7.5.sp, lineHeight = 10.sp)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = HioneCyan,
                uncheckedThumbColor = HioneTextSecondary,
                uncheckedTrackColor = Color(0x22FFC56B),
            )
        )
    }
}

// ---------------------------------------------------------------------------- diagnostics

@Composable
fun FmDiagnosticsPanel(st: FmState) {
    val d = st.diagnostics
    val s = st.signal
    SheetTitle("Tuner diagnostics")
    SheetNote(
        "Everything on this page is read back from the driver or the audio HAL. A dash means " +
            "the question was asked and nothing came back, not that the value is zero."
    )
    Spacer(Modifier.height(8.dp))
    Column(Modifier.verticalScroll(rememberScrollState())) {
        DiagRow("JNI library", if (d.jniLoaded) "loaded" else "NOT loaded")
        DiagRow("FM SoC", d.socName ?: "—")
        DiagRow("Chip state", d.fmState?.toString() ?: "—")
        DiagRow("Antenna", if (st.isHeadsetPlugged) "headphone cable attached" else "none, the cable IS the antenna")
        DiagRow("Band plan", "${st.band.label} · ${st.band.lowKHz / 1000.0}–${st.band.highKHz / 1000.0} MHz")
        DiagRow("Tuned (app)", "${st.frequencyKHz} kHz")
        DiagRow("Tuned (V4L2 read)", d.driverFreqKHz?.let { "$it kHz" } ?: "—")
        Spacer(Modifier.height(8.dp))
        DiagRow("Audio route", "${d.routeLabel} (code ${d.routeCode})")
        DiagRow("handle_fm written", d.handleFmWritten?.let { "$it (0x${it.toString(16)})" } ?: "— (stopped)")
        DiagRow("HAL fm_status", when (d.halLoopback) { true -> "1 · FM session running"; false -> "0 · not running"; null -> "—" })
        DiagRow("fm_volume", d.fmVolumeLinear?.let { String.format(Locale.US, "%.4f", it) } ?: "—")
        DiagRow("SLIMbus ack", d.slimbusStatus?.toString() ?: "—")
        DiagRow("Driver mute", when (d.driverMuted) { true -> "muted"; false -> "open"; null -> "—" })
        DiagRow("App mute", if (st.isMuted) "muted" else "open")
        DiagRow("PCM level", if (st.isPowerOn) String.format(Locale.US, "%.4f RMS", st.audioLevel) else "—")
        DiagRow("Capture framing", when (st.captureFraming) {
            true -> "vendor 6-byte frames, decoded to ${FmCaptureFrame.DECODED_RATE_HZ / 1000} kHz mono"
            false -> "plain 16-bit PCM as advertised"
            null -> "—"
        })
        DiagRow("Spectrum range", "25 Hz to ${st.spectrumTopHz} Hz · 4096-pt FFT, 256 log bins")
        Spacer(Modifier.height(8.dp))
        // Two tuners, read separately. Only the one with an antenna will move with frequency.
        DiagRow("RSSI · Si4705 (radio0)", d.rssiSi4705?.toString() ?: "—")
        DiagRow("RSSI · Qualcomm (HCI)", d.rssiQualcomm?.toString() ?: "—")
        DiagRow("SINR · Qualcomm (HCI)", d.sinrQualcomm?.toString() ?: "—")
        DiagRow("RSSI in use", s.rssi?.toString() ?: st.rssi?.toString() ?: "—")
        DiagRow("SNR", s.snr?.toString() ?: "—")
        DiagRow("Multipath", s.multipath?.toString() ?: "—")
        DiagRow("Frequency offset", s.freqOffset?.toString() ?: "—")
        DiagRow("Locked (RSSI>1, SNR>0)", when (s.valid) { true -> "yes"; false -> "no"; null -> "—" })
        DiagRow("Stereo pilot", when (s.pilot) { true -> "present"; false -> "absent"; null -> "—" })
        DiagRow("Stereo blend", s.stereoBlendPct?.let { "$it %" } ?: "—")
        DiagRow("Audio siphon", when (st.siphonState) {
            QualcommFmHardwareEngine.SiphonState.UNTRIED -> "not tried yet (LIVE view)"
            QualcommFmHardwareEngine.SiphonState.PROBING -> "testing…"
            QualcommFmHardwareEngine.SiphonState.ACTIVE -> "live, real audio"
            QualcommFmHardwareEngine.SiphonState.FAILED -> "off: ${st.siphonReason ?: "failed"}"
        })
        if (st.siphonState == QualcommFmHardwareEngine.SiphonState.FAILED) {
            Text(
                "RETRY SIPHON",
                color = HioneTeal, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { FmRadioManager.retrySiphon() }.padding(vertical = 6.dp)
            )
        }
        DiagRow("RDS", when (st.rdsAvailable) { true -> "locked"; false -> "none"; null -> "—" })
        DiagRow("RDS PI", st.programmeId?.let { "0x${it.toString(16).uppercase(Locale.US)}" } ?: "—")
        DiagRow("RDS PTY", FmRadioManager.programmeTypeName(st.programmeType, st.band)
            ?: st.programmeType?.toString() ?: "—")
        st.hardwareError?.let {
            Spacer(Modifier.height(8.dp))
            Text("LAST ERROR", color = HioneNeonPink, fontSize = 7.5.sp, fontFamily = AudiowideFont)
            Text(it, color = HioneNeonPink, fontSize = 8.sp, lineHeight = 11.sp)
        }
    }
}

@Composable
private fun DiagRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = HioneTextSecondary, fontSize = 8.5.sp, modifier = Modifier.width(132.dp))
        Text(value, color = Color.White, fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
    }
}

// ---------------------------------------------------------------------------- keypad

/** Direct frequency entry, because hunting 20 MHz with a step key is not a control surface. */
@Composable
fun ColumnScope.FmKeypad(st: FmState, onTuned: () -> Unit) {
    var entry by remember { mutableStateOf("") }
    val low = st.band.lowKHz / 1000.0
    val high = st.band.highKHz / 1000.0

    var note by remember { mutableStateOf<String?>(null) }
    fun commit() {
        val mhzValue = entry.toDoubleOrNull() ?: return
        val khz = Math.round(mhzValue * 1000).toInt()
        note = null
        when {
            khz in QualcommFmHardwareEngine.CHIP_LOW_KHZ..QualcommFmHardwareEngine.CHIP_HIGH_KHZ -> {
                // Engine snaps: the band-anchored grid inside the region's band, 50 kHz outside.
                FmRadioManager.tune(khz)
                onTuned()
            }
            khz > QualcommFmHardwareEngine.CHIP_HIGH_KHZ ->
                note = "This tuner stops at 108.0 MHz. We tested past it and the chip will not go higher."
            else ->
                note = "This tuner starts at 64.0 MHz."
        }
    }

    SheetTitle("Tune directly")
    Text(
        entry.ifEmpty { "—" },
        color = if (entry.isEmpty()) Color.Gray else Color.White,
        fontSize = 30.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont,
        modifier = Modifier.align(Alignment.CenterHorizontally)
    )
    Text(
        String.format(Locale.US, "%.1f to %.1f MHz in %d kHz steps. Anything from 64.0 to 108.0 tunes.", low, high, st.band.stepKHz),
        color = HioneTextSecondary, fontSize = 8.sp,
        modifier = Modifier.align(Alignment.CenterHorizontally)
    )
    note?.let {
        Text(it, color = HioneNeonPink, fontSize = 9.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp))
    }
    Spacer(Modifier.height(8.dp))

    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", ".", "0", "⌫")
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        keys.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                row.forEach { k ->
                    Box(
                        Modifier.weight(1f)
                            .glass(RoundedCornerShape(12.dp), accent = HioneCyan, rimAlpha = 0.4f, shine = 0.8f)
                            .pressable {
                                entry = when {
                                    k == "⌫" -> entry.dropLast(1)
                                    k == "." && entry.contains('.') -> entry
                                    entry.length >= 6 -> entry
                                    else -> entry + k
                                }
                            }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(k, color = HioneCyan, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                            fontFamily = AudiowideFont)
                    }
                }
            }
        }
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(HioneCyan).pressable { commit() }.padding(vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("TUNE", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Black,
                fontFamily = AudiowideFont)
        }
    }
}
