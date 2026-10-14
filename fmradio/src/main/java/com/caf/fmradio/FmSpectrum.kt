package com.caf.fmradio

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.ln
import kotlin.math.roundToInt

/** Labels any closer together than this are dropped rather than drawn overlapping. */
private const val MIN_LABEL_GAP_DP = 26f
/** Hard cap: a crowded band here lists hundreds of stations and none of them would be legible. */
private const val MAX_BAND_LABELS = 14

/**
 * The tuner's big panel. Two different instruments share it, and they no longer share chrome:
 *
 * LIVE is an audio analyser of what is playing: 256 log-spaced FFT bins with peak hold, a log
 * frequency axis in Hz and a dB scale, and a spectrogram underneath. It is drawn on its own
 * surface and thread ([FmSpectrumSurfaceView]), so a spectrum frame never redraws the app
 * window. FM band furniture (call signs, the tuned-frequency cursor, the
 * MHz strip) is meaningless on an audio axis and is not drawn here.
 *
 * BAND is the swept RSSI of the whole band: x is frequency, peaks are labelled with the
 * catalogue's call signs, and tap or drag tunes.
 *
 * Nothing is synthesised: with the tuner off, starting, or silent the plot is flat. That is a
 * feature, not a gap — a flat trace while the tuner claims to be on is exactly the symptom that
 * says the audio path is not carrying anything.
 */
@Composable
fun RadioWaterfallSpectrum(
    currentFreqKHz: Int,
    band: FmBandPlan,
    isPowerOn: Boolean,
    isHardwareOnline: Boolean,
    hardwareError: String?,
    signalHistory: FloatArray,
    bandProfile: FloatArray,
    bandHistory: List<FloatArray>,
    favorites: List<Int>,
    scanHits: List<Int>,
    /**
     * Stations the catalogue says transmit within reach of where the device actually is.
     * Labels the BAND view's peaks; best-scoring first, thinned so they stay legible.
     */
    cataloguedStations: List<FmStationCatalogue.Station> = emptyList(),
    /** Which receiver-panel convention BAND draws. Colour never varies; geometry does. */
    skin: SdrSkin = SdrSkin.CLASSIC,
    /** Sweep times, newest first, parallel to bandHistory. Empty when nothing has swept. */
    rowTimes: List<Long> = emptyList(),
    mode: QualcommFmHardwareEngine.ViewMode = QualcommFmHardwareEngine.ViewMode.LIVE,
    /** A band scan draws on the live surface whatever the view mode: see ScanArt. */
    isScanning: Boolean = false,
    onCycleSkin: () -> Unit = {},
    onTuneFreq: (Int) -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF0C0D10))
            .border(1.2.dp, CyberGlassBorder, RoundedCornerShape(14.dp))
    ) {
        if (mode == QualcommFmHardwareEngine.ViewMode.LIVE || isScanning) {
            LivePanel()
        } else {
            BandPanel(currentFreqKHz, band, signalHistory, bandProfile, bandHistory, favorites,
                      scanHits, cataloguedStations, skin, rowTimes, onCycleSkin, onTuneFreq)
        }
        Text(
            when {
                isHardwareOnline -> "HARDWARE ACTIVE"
                hardwareError != null -> "TUNER ERROR"
                isPowerOn -> "STARTING…"
                else -> "STANDBY"
            },
            color = when {
                isHardwareOnline -> HionePink
                hardwareError != null -> HioneNeonPink
                isPowerOn -> CyberAmber
                else -> Color.Gray
            },
            fontSize = 7.5.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.TopEnd).padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

// ============================================================================ LIVE

/**
 * LIVE is a SurfaceView ([FmSpectrumSurfaceView]) with its own render thread, inset inside the
 * panel's rounded frame. The inset is deliberate: the surface is a plain rectangle composited
 * under the window, and 5 dp keeps its square corners inside the 14 dp rounded rim whether or
 * not this Android version clips the punched hole to the rounded shape. The frame, the status
 * label and the overlays above it are ordinary, static window content.
 */
@Composable
private fun LivePanel() {
    AndroidView(
        factory = { FmSpectrumSurfaceView(it) },
        modifier = Modifier.fillMaxSize().padding(5.dp),
    )
}

// ============================================================================ BAND

@Composable
private fun BandPanel(
    currentFreqKHz: Int,
    band: FmBandPlan,
    signalHistory: FloatArray,
    bandProfile: FloatArray,
    bandHistory: List<FloatArray>,
    favorites: List<Int>,
    scanHits: List<Int>,
    cataloguedStations: List<FmStationCatalogue.Station>,
    skin: SdrSkin,
    rowTimes: List<Long>,
    onCycleSkin: () -> Unit,
    onTuneFreq: (Int) -> Unit,
) {
    val textMeasurer = rememberTextMeasurer()
    // A band sweep gives RSSI per channel across the whole band, which is the display this
    // panel wants: x is already frequency, so the sweep drops straight onto the same axis as
    // the tuning strip, the presets and the marker.
    val hasBand = bandProfile.isNotEmpty()

    val low = band.lowKHz.toFloat()
    val span = (band.highKHz - band.lowKHz).toFloat()
    val step = band.stepKHz

    // No rounding here. Snapping belongs to the engine, which anchors the grid to the band's
    // low limit; doing it here as "nearest multiple of step" rounded from zero, which on a
    // 200 kHz grid can only ever produce even tenths and made 100.1 untunable.
    fun freqAt(fracX: Float): Int = (low + fracX.coerceIn(0f, 1f) * span).roundToInt()

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(band) {
                detectTapGestures { offset -> onTuneFreq(freqAt(offset.x / size.width)) }
            }
            .pointerInput(band) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onTuneFreq(freqAt(change.position.x / size.width))
                }
            }
    ) {
        val fftPath = remember { Path() }
        val fillPath = remember { Path() }
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val fftHeight = h * 0.42f
            val scaleHeight = 10.dp.toPx()
            val waterfallHeight = h * 0.60f - scaleHeight

            val gutter = rssiGutterWidth(skin)
            val plotW = w - gutter
            fun xOf(khz: Int) = gutter + ((khz - low) / span).coerceIn(0f, 1f) * plotW

            for (g in 1..3) {
                val gy = fftHeight * g / 4f
                drawLine(HioneTeal.copy(alpha = 0.10f), Offset(0f, gy), Offset(w, gy), strokeWidth = 1f)
            }

            // The tuned channel, as a block the width of one channel rather than a bare line,
            // so you can see what the receiver is actually sitting on relative to its neighbours.
            val halfCh = (step / 2f / span) * plotW
            val cx = xOf(currentFreqKHz)
            if (skin.showsPassband) {
                drawPassband(cx, halfCh, fftHeight, HioneTeal)
            } else {
                drawRect(HioneTeal.copy(alpha = 0.16f), Offset(cx - halfCh, 0f), Size(halfCh * 2f, fftHeight))
            }

            if (hasBand && skin.showsSurface) {
                drawSurfaceWaterfall(bandHistory.take(14), fftHeight, waterfallHeight, w)
            } else if (hasBand) {
                // Band waterfall: each row is one sweep, x is frequency, newest at the top.
                // Capped: each row is one drawRect per channel, and sweeps arrive seconds apart,
                // so this redraws rarely; twelve rows is plenty of history to read.
                val rows = bandHistory.take(12)
                val rh = if (rows.isEmpty()) 0f else waterfallHeight / rows.size.coerceAtLeast(1)
                for (r in rows.indices) {
                    val prof = rows[r]
                    if (prof.isEmpty()) continue
                    val cw = plotW / prof.size
                    val y = fftHeight + r * rh
                    for (i in prof.indices) {
                        drawRect(FmWaterfallPalette.lut(prof[i]), Offset(gutter + i * cw, y), Size(cw + 0.5f, rh + 0.5f))
                    }
                }
                if (skin.showsTimestamps) {
                    drawWaterfallTimestamps(textMeasurer, rowTimes.take(rows.size), fftHeight, rh, HioneTeal)
                }
            } else {
                // Nothing swept yet: one column per poll of the tuned frequency's own RSSI, so
                // the panel still responds while you aim the wire.
                val cols = signalHistory.size
                if (cols > 1) {
                    val colW = w / cols
                    for (i in 0 until cols) {
                        val v = signalHistory[i].coerceIn(0f, 1f)
                        val barH = v * (waterfallHeight - 2f)
                        drawRect(FmWaterfallPalette.lut(v), Offset(w - (i + 1) * colW, fftHeight + (waterfallHeight - barH)), Size(colW + 0.5f, barH))
                    }
                }
            }

            val scaleTop = fftHeight + waterfallHeight
            drawRect(Color(0xFF020A0F), topLeft = Offset(0f, scaleTop), size = Size(w, scaleHeight))
            scanHits.forEach { f ->
                drawLine(HioneTeal.copy(alpha = 0.7f), Offset(xOf(f), scaleTop + scaleHeight * 0.45f),
                    Offset(xOf(f), scaleTop + scaleHeight), strokeWidth = 1.5.dp.toPx())
            }
            favorites.forEach { f ->
                drawLine(HionePink.copy(alpha = 0.9f), Offset(xOf(f), scaleTop),
                    Offset(xOf(f), scaleTop + scaleHeight), strokeWidth = 1.5.dp.toPx())
            }
            // Catalogue ticks sit under the scan/favourite marks and are dimmer, so a station
            // the device has actually heard still reads louder than one that is merely listed.
            cataloguedStations.forEach { st ->
                val sx = xOf(st.khz)
                drawLine(HioneTeal.copy(alpha = 0.22f), Offset(sx, scaleTop + scaleHeight * 0.2f),
                    Offset(sx, scaleTop + scaleHeight), strokeWidth = 1f)
            }
            val tickStepMHz = if (span > 20000f) 4 else 2
            var mhzTick = ((band.lowKHz / 1000) / tickStepMHz + 1) * tickStepMHz
            while (mhzTick * 1000 <= band.highKHz) {
                val sx = xOf(mhzTick * 1000)
                drawLine(HioneTeal.copy(alpha = 0.3f), Offset(sx, scaleTop + scaleHeight * 0.55f),
                    Offset(sx, scaleTop + scaleHeight), strokeWidth = 1f)
                mhzTick += tickStepMHz
            }

            fftPath.reset(); fillPath.reset()
            var drew = false
            if (hasBand && !skin.showsSurface) {
                // Live trace of the newest sweep: RSSI against frequency, so peaks sit directly
                // above the frequency that produced them and can be tapped.
                val n = bandProfile.size
                val dx = plotW / (n - 1).coerceAtLeast(1)
                for (i in 0 until n) {
                    val y = fftHeight - (bandProfile[i].coerceIn(0f, 1f) * (fftHeight - 6f))
                    if (i == 0) fftPath.moveTo(gutter, y) else fftPath.lineTo(gutter + i * dx, y)
                }
                drew = n > 1
            } else if (!hasBand) {
                val n = signalHistory.size
                if (n > 1) {
                    val dx = plotW / (n - 1)
                    for (i in 0 until n) {
                        val x = w - i * dx
                        val y = fftHeight - (signalHistory[i].coerceIn(0f, 1f) * (fftHeight - 6f))
                        if (i == 0) fftPath.moveTo(x, y) else fftPath.lineTo(x, y)
                    }
                    drew = true
                }
            }
            if (drew) {
                fillPath.addPath(fftPath); fillPath.lineTo(w, fftHeight); fillPath.lineTo(gutter, fftHeight); fillPath.close()
                drawPath(fillPath, Brush.verticalGradient(listOf(Color(0x88FFC56B), Color(0x11FFC56B)), 0f, fftHeight))
                drawPath(fftPath, HioneTeal, style = Stroke(width = 2.dp.toPx()))
            }

            if (skin.showsAxis) {
                drawRssiAxis(textMeasurer, gutter, fftHeight, QualcommFmHardwareEngine.SWEEP_RSSI_FULL_SCALE, HioneTeal)
            }

            // The tuned frequency. Only meaningful on a frequency axis, so only drawn here.
            drawLine(HionePink, Offset(cx, 0f), Offset(cx, h), strokeWidth = 2.dp.toPx())
        }

        // Call signs over the band, which is the difference between a spectrum and a dial you
        // can read. Capped hard and thinned by collision, better-scoring station first.
        if (cataloguedStations.isNotEmpty()) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val bandWidth = maxWidth
                val placed = remember(cataloguedStations, band, bandWidth, skin) {
                    val out = ArrayList<Pair<FmStationCatalogue.Station, Dp>>()
                    val minGap = MIN_LABEL_GAP_DP
                    for (st in cataloguedStations) {
                        if (st.khz < band.lowKHz || st.khz > band.highKHz) continue
                        val x = bandWidth * ((st.khz - band.lowKHz).toFloat() / span)
                        if (out.any { kotlin.math.abs((it.second - x).value) < minGap }) continue
                        out.add(st to x)
                        if (out.size >= (if (skin.labelsEverything) MAX_BAND_LABELS * 3 else MAX_BAND_LABELS)) break
                    }
                    out
                }
                for ((st, x) in placed) {
                    val tuned = kotlin.math.abs(st.khz - currentFreqKHz) <= 60
                    Text(
                        if (st.isJapan) st.chipLabel else st.call,
                        color = if (tuned) HionePink else stationColor(st.call).copy(alpha = 0.7f),
                        fontSize = 6.5.sp,
                        fontWeight = if (tuned) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        modifier = Modifier.align(Alignment.BottomStart).offset(x = x - 13.dp, y = -1.dp)
                    )
                }
            }
        }

        Text(
            if (hasBand) "${skin.label} · RSSI vs FREQUENCY · TAP A PEAK TO TUNE"
            else "${skin.label} · ${band.lowKHz / 1000}–${band.highKHz / 1000} MHz · FIRST SWEEP…",
            color = HioneTeal.copy(alpha = 0.85f), fontSize = 7.5.sp, fontWeight = FontWeight.Bold,
            // The header doubles as the skin control. A dedicated row of buttons would eat
            // height this panel does not have, and the label already says which one is on.
            modifier = Modifier.clickable { onCycleSkin() }.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

// ============================================================================ record

@Composable
fun FmRecordButton(isRecording: Boolean, onClick: () -> Unit) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp)) {
        if (isRecording) RecordPulse()
        Box(
            Modifier.size(44.dp)
                .then(
                    if (isRecording) Modifier.clip(CircleShape)
                        .background(Brush.verticalGradient(listOf(Color(0xFFFF1744), Color(0xFFB71C1C))))
                        .border(1.5.dp, Color(0xFFFF5252), CircleShape)
                    else Modifier.glass(CircleShape, accent = Color(0xFFFF5252), accent2 = HioneTeal, rimAlpha = 0.5f)
                )
                .pressable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (isRecording) {
                Icon(Icons.Default.Stop, "Stop recording", tint = Color.White, modifier = Modifier.size(18.dp))
            } else {
                Box(Modifier.size(14.dp).clip(CircleShape).background(Color(0xFFFF1744)))
            }
        }
    }
}

/**
 * The pulsing ring, only composed while recording. An infinite transition keeps requesting
 * frames for as long as it exists, even animating 1 to 1, so it must not exist when idle; and
 * the scale is applied in a graphics layer so the pulse never re-lays-out or recomposes.
 */
@Composable
private fun RecordPulse() {
    val transition = rememberInfiniteTransition(label = "recPulse")
    val pulse = transition.animateFloat(
        initialValue = 1f, targetValue = 1.25f,
        animationSpec = infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulseScale"
    )
    Box(
        Modifier.size(42.dp)
            .graphicsLayer { scaleX = pulse.value; scaleY = pulse.value }
            .clip(CircleShape)
            .background(Color(0xFFFF1744).copy(alpha = 0.35f))
            .border(1.5.dp, Color(0xFFFF1744), CircleShape)
    )
}
