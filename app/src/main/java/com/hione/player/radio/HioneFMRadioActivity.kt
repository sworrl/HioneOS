package com.hione.player.radio

import com.hione.player.Mascot
import com.hione.player.mascotPainter
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.caf.fmradio.IFMRadioService
import com.caf.fmradio.IFMRadioServiceCallbacks
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.player.AudiowideFont
import com.hione.player.CrashSentinel
import com.hione.player.R
import com.hione.player.*
import com.hione.player.ui.detectDragGesturesEdgeSafe
import com.hione.player.ui.edgeSafePointerInput
import dalvik.system.PathClassLoader
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.*
import kotlin.math.*

/** Defaults = nothing tuned / nothing measured. rssi 0 and empty RDS strings until the tuner reports. */
data class FmState(
    val isPowerOn: Boolean = false,
    val frequencyKHz: Int = 87500, // band floor; the engine publishes the real tuned frequency
    val isStereo: Boolean = false,
    val isMuted: Boolean = false,
    val isRecording: Boolean = false,
    val rssi: Int = 0,
    val stationName: String = "",
    val radioText: String = "",
    val isHeadsetPlugged: Boolean = false,
    val favorites: List<Int> = emptyList()
)

/**
 * Direct Qualcomm Snapdragon Hardware FM Radio Manager.
 * Uses QualcommFmHardwareEngine to route hardware FM tuner PCM directly to the CS43198 DAC.
 */
object FmRadioManager {
    private const val TAG = "HioneDirectFmEngine"
    private var engine: QualcommFmHardwareEngine? = null
    private val _state = MutableStateFlow(FmState())
    val state: StateFlow<FmState> = _state
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    fun initAndPowerOn(ctx: Context) {
        if (engine == null) {
            engine = QualcommFmHardwareEngine(ctx.applicationContext).also { eng ->
                scope.launch {
                    launch { eng.isPoweredOn.collect { on -> _state.value = _state.value.copy(isPowerOn = on) } }
                    launch { eng.currentFrequencyKHz.collect { freq -> _state.value = _state.value.copy(frequencyKHz = freq) } }
                    launch { eng.isStereo.collect { stereo -> _state.value = _state.value.copy(isStereo = stereo) } }
                    launch { eng.isMuted.collect { muted -> _state.value = _state.value.copy(isMuted = muted) } }
                    launch { eng.rssi.collect { r -> _state.value = _state.value.copy(rssi = r) } }
                    launch { eng.stationName.collect { name -> _state.value = _state.value.copy(stationName = name) } }
                    launch { eng.radioText.collect { rt -> _state.value = _state.value.copy(radioText = rt) } }
                    launch { eng.presets.collect { favs -> _state.value = _state.value.copy(favorites = favs) } }
                }
            }
        }
        engine?.powerOn()
    }

    fun tune(ctx: Context, newFreqKHz: Int) {
        engine?.tune(newFreqKHz)
    }

    fun seek(next: Boolean) {
        engine?.seek(next)
    }

    fun toggleFavorite(freqKHz: Int) {
        val favs = _state.value.favorites.toMutableList()
        if (favs.contains(freqKHz)) {
            favs.remove(freqKHz)
        } else {
            favs.add(freqKHz)
            favs.sort()
        }
        _state.value = _state.value.copy(favorites = favs)
        engine?.presets?.value = favs
    }

    fun toggleRecording(ctx: Context) {
        // There is no recorder behind this button (no tuner access from this package), so the
        // state must never claim "recording". Kept as a no-op rather than a fake toggle.
        android.util.Log.w(TAG, "FM recording is not available in this package")
        _state.value = _state.value.copy(isRecording = false)
    }

    fun toggleMute() {
        if (_state.value.isMuted) engine?.unmute() else engine?.mute()
    }

    fun toggleStereo() {
        engine?.toggleStereo()
    }

    fun stop(ctx: Context? = null) {
        engine?.powerOff()
        _state.value = _state.value.copy(isPowerOn = false)
    }
}

class HioneFMRadioActivity : ComponentActivity() {

    private fun setupSystemBars() {
        try {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
            val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            // Never the stock nav bar: our accessibility home pill IS the navigation on this
            // device, and showing both puts a dead white pill next to the live one.
            insetsController.hide(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
            insetsController.show(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            insetsController.isAppearanceLightStatusBars = false
            insetsController.isAppearanceLightNavigationBars = false
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            window.statusBarColor = android.graphics.Color.TRANSPARENT
        } catch (_: Throwable) {}
    }

    private var handedOff = false

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashSentinel.install(this)
        super.onCreate(savedInstanceState)
        setupSystemBars()
        // SELinux only lets the package com.caf.fmradio (seinfo=platform → vendor_fm_app) open
        // /dev/radio0. This package can never tune: its engine used to fall to a V4L2 "fallback"
        // that made a sub-second noise and then kept a fake tuner on screen. So this entry now
        // HANDS OFF to the real Hione FM app when it's installed, and otherwise says so honestly —
        // it never starts the in-app engine and never touches the HiBy FM audio-path props.
        val real = realFmComponent()
        if (real != null) {
            handedOff = true
            runCatching {
                startActivity(
                    Intent().setComponent(real)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                )
            }.onFailure { handedOff = false }
            if (handedOff) { finish(); return }
        }
        setContent {
            FmTunerUnavailableScreen(onBack = { finish() })
        }
    }

    /**
     * Whatever `com.caf.fmradio` is installed AND enabled — HiBy's stock FM2 (launcher `.FMRadio`)
     * today, the Hione FM build once it ships bundled in the image. Both run in the vendor_fm_app
     * SELinux domain, the only one allowed to open /dev/radio0; this package never can.
     */
    private fun realFmComponent(): android.content.ComponentName? {
        val pm = packageManager
        val ai = runCatching { pm.getApplicationInfo(REAL_FM_PACKAGE, 0) }.getOrNull() ?: return null
        if (!ai.enabled) return null
        return pm.getLaunchIntentForPackage(REAL_FM_PACKAGE)?.component
    }

    companion object {
        const val REAL_FM_PACKAGE = "com.caf.fmradio"
    }

    override fun onResume() {
        super.onResume()
        setupSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) setupSystemBars()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!handedOff) runCatching { FmRadioManager.stop(this) }
    }
}

/** Honest no-tuner state: this package is not allowed to open the FM hardware and the real Hione FM
 *  app isn't installed — no simulated tuner, no noise, no fake signal bars. */
@Composable
fun FmTunerUnavailableScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    Box(Modifier.fillMaxSize().background(CyberDarkBg)) {
        Image(
            painter = mascotPainter(id = R.drawable.hione_bg_fm),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alpha = 0.35f
        )
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("📻", fontSize = 54.sp)
            Spacer(Modifier.height(14.dp))
            Text(
                "FM TUNER UNAVAILABLE",
                color = Color(0xFFFF8A80), fontSize = 18.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "${Mascot.musicAppName} can't open the FM hardware itself (the tuner is reserved for the ${Mascot.name} FM app, com.caf.fmradio). Install the ${Mascot.name} FM app from the ${Mascot.osName} system image to listen to radio.",
                color = Mascot.color(0xFFD4ECE9), fontSize = 13.sp, lineHeight = 18.sp, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onBack,
                colors = ButtonDefaults.buttonColors(containerColor = Mascot.color(0x33FFAB2E)),
                border = BorderStroke(1.dp, Mascot.color(0xFFFFAB2E)),
                shape = RoundedCornerShape(12.dp)
            ) { Text("BACK", color = Color.White, fontWeight = FontWeight.Bold) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HioneFMRadioScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val fmState by FmRadioManager.state.collectAsState()
    var chibiReaction by remember { mutableStateOf("📻") }

    val mhzDisplay = String.format(Locale.US, "%.1f", fmState.frequencyKHz / 1000.0)
    val isFav = fmState.favorites.contains(fmState.frequencyKHz)

    Box(
        Modifier
            .fillMaxSize()
            .background(CyberDarkBg)
    ) {
        Image(
            painter = mascotPainter(id = R.drawable.hione_bg_fm),
            contentDescription = "${Mascot.name} artwork",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Mascot.color(0xCC0C0D10),
                            Color(0x77000000),
                            Mascot.color(0xF50C0D10)
                        )
                    )
                )
        )

        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header Bar
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                com.hione.player.ui.HioneBackButton(onClick = onBack)

                Text(
                    "${Mascot.nameUpper} FM",
                    color = HioneCyan,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = AudiowideFont,
                    letterSpacing = 1.sp
                )

                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (fmState.isHeadsetPlugged) Mascot.color(0x33FFC56B) else Mascot.color(0x336FB0FF))
                        .border(1.dp, if (fmState.isHeadsetPlugged) HioneCyan else HioneNeonPink, RoundedCornerShape(10.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Text(
                        if (fmState.isHeadsetPlugged) "ANTENNA OK" else "PLUG HEADSET",
                        color = if (fmState.isHeadsetPlugged) HioneCyan else HioneNeonPink,
                        fontSize = 7.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = AudiowideFont
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // Holographic Frequency Banner Card
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Mascot.color(0xDD0A1E26))
                    .border(1.2.dp, if (fmState.isPowerOn) HioneCyan else Color.White.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(if (fmState.isPowerOn) HioneCyan else Color.Gray)
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                // Stereo comes from the tuner's own flag, and RSSI only when the HAL
                                // actually reported one. The old label said "FM STEREO" whenever the
                                // tuner was powered and printed rssi even while it was still 0.
                                if (!fmState.isPowerOn) "STANDBY"
                                else (if (fmState.isStereo) "FM STEREO" else "FM MONO") +
                                    " • " + (if (fmState.rssi > 0) "${fmState.rssi} dBµV" else "RSSI —"),
                                color = if (fmState.isPowerOn) HioneCyan else Color.Gray,
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = AudiowideFont
                            )
                        }

                        IconButton(
                            onClick = { FmRadioManager.toggleFavorite(fmState.frequencyKHz) },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                if (isFav) Icons.Default.Star else Icons.Default.StarBorder,
                                contentDescription = "Favorite",
                                tint = if (isFav) HioneNeonPink else Color.Gray,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = mhzDisplay,
                            color = if (fmState.isPowerOn) Color.White else Color.Gray,
                            fontSize = 38.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = AudiowideFont,
                            letterSpacing = 1.sp
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = "MHz",
                            color = HioneCyan,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = AudiowideFont,
                            modifier = Modifier.padding(bottom = 5.dp)
                        )
                    }

                    Text(
                        text = if (fmState.stationName.isNotEmpty()) fmState.stationName else "No RDS station name",
                        color = HioneTextSecondary,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Mascot.color(0x33FFC56B))
                        .border(1.dp, HioneCyan, CircleShape)
                        .clickable {
                            val reactions = listOf("📻", "🎶", "💙", "⚡", "✨", "🎤", "🎧")
                            chibiReaction = reactions.random()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = mascotPainter(id = R.drawable.ic_search_head_hione),
                        contentDescription = "Chibi DJ",
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // ============================================================
            // EXPANDED REAL-TIME DSP AUDIO WATERFALL (FULL VERTICAL SPACE)
            // ============================================================
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                RadioWaterfallSpectrum(
                    currentFreqKHz = fmState.frequencyKHz,
                    isPowerOn = fmState.isPowerOn,
                    onTuneFreq = { newFreq -> FmRadioManager.tune(ctx, newFreq) }
                )
            }

            Spacer(Modifier.height(6.dp))

            // ============================================================
            // BOTTOM TUNING GLYPHS & RECORD BUTTON
            // Left & Right: Clean Cyber Glowing Vector Glyphs (Tap: 0.1 step, Hold: Auto-Seek)
            // ============================================================
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // LEFT GLYPH: Step Down on Tap, Auto-Seek Down on Long Press
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                listOf(Mascot.color(0x33FFC56B), Mascot.color(0x0004141E))
                            )
                        )
                        .combinedClickable(
                            onClick = { FmRadioManager.tune(ctx, fmState.frequencyKHz - 100) },
                            onLongClick = { FmRadioManager.seek(false) }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "‹",
                        color = HioneCyan,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Light,
                        fontFamily = AudiowideFont,
                        textAlign = TextAlign.Center
                    )
                }

                // CENTER RECORD BUTTON
                FmRecordButton(
                    isRecording = fmState.isRecording,
                    onClick = { FmRadioManager.toggleRecording(ctx) }
                )

                // RIGHT GLYPH: Step Up on Tap, Auto-Seek Up on Long Press
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                listOf(Mascot.color(0x33FFC56B), Mascot.color(0x0004141E))
                            )
                        )
                        .combinedClickable(
                            onClick = { FmRadioManager.tune(ctx, fmState.frequencyKHz + 100) },
                            onLongClick = { FmRadioManager.seek(true) }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "›",
                        color = HioneCyan,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Light,
                        fontFamily = AudiowideFont,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // Preset Favorite Stations
            LazyRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(fmState.favorites) { freq ->
                    val isCurrent = fmState.frequencyKHz == freq
                    val freqText = String.format(Locale.US, "%.1f", freq / 1000.0)
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isCurrent) HioneCyan else Mascot.color(0xDD0A1E26))
                            .border(1.dp, if (isCurrent) HioneCyan else CyberGlassBorder, RoundedCornerShape(10.dp))
                            .clickable { FmRadioManager.tune(ctx, freq) }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "$freqText MHz",
                            color = if (isCurrent) Color.Black else Color.White,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = AudiowideFont
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            // Pixel-style Hione gesture pill — DECORATIVE ONLY. It sits flush inside the window's
            // bottom 32dp, which is the HioneOS accessibility nav layer's home/recents band; this
            // strip used to run its own swipe-up → home / swipe-up-and-hold → recents emulation
            // on top of that, so one finger fired both (the launcher's recents intent AND the
            // system home). The OS owns that band (see ui/SystemGestureEdges.kt) — no handler here.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .padding(bottom = 1.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .width(64.dp)
                        .height(3.5.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(HioneCyan.copy(alpha = 0.9f))
                )
            }
        }
    }
}

/**
 * Tuning dial. This is a band scale (87.5–108 MHz) with the tuned-carrier marker — NOT a spectrum:
 * the tuner exposes no FFT / per-bin RF data to this app, so the old "real-time DSP waterfall"
 * (a gaussian peak plus sine ripple, i.e. synthesised) is gone. Tap / drag still tunes.
 */
@Composable
fun RadioWaterfallSpectrum(
    currentFreqKHz: Int,
    isPowerOn: Boolean,
    onTuneFreq: (Int) -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(14.dp))
            .background(Mascot.color(0xFF0C0D10))
            .border(1.2.dp, CyberGlassBorder, RoundedCornerShape(14.dp))
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val frac = (offset.x / size.width).coerceIn(0f, 1f)
                    val targetKHz = (87500 + frac * (108000 - 87500)).roundToInt()
                    val roundedKHz = ((targetKHz + 50) / 100) * 100
                    onTuneFreq(roundedKHz)
                }
            }
            // Edge-safe: the dial spans the window width, so a drag that starts in a side band is
            // the system BACK swipe (bottom/top bands = home/shade) — not a tune.
            .edgeSafePointerInput(Unit) { guard ->
                detectDragGesturesEdgeSafe(guard) { change, _ ->
                    change.consume()
                    val frac = (change.position.x / size.width).coerceIn(0f, 1f)
                    val targetKHz = (87500 + frac * (108000 - 87500)).roundToInt()
                    val roundedKHz = ((targetKHz + 50) / 100) * 100
                    onTuneFreq(roundedKHz)
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val tunedFrac = ((currentFreqKHz - 87500f) / (108000f - 87500f)).coerceIn(0f, 1f)

            // 1. Band scale: a tick every 0.5 MHz, taller every 1 MHz, tallest every 5 MHz.
            val baseY = h * 0.62f
            drawLine(Mascot.color(0xFF004D5A), Offset(0f, baseY), Offset(w, baseY), strokeWidth = 1.dp.toPx())
            var khz = 87500
            while (khz <= 108000) {
                val x = ((khz - 87500f) / (108000f - 87500f)) * w
                val tick = when {
                    khz % 5000 == 0 -> h * 0.22f
                    khz % 1000 == 0 -> h * 0.13f
                    else -> h * 0.07f
                }
                drawLine(
                    color = if (isPowerOn) Mascot.color(0xFF00B4D8) else Color(0xFF335560),
                    start = Offset(x, baseY - tick),
                    end = Offset(x, baseY),
                    strokeWidth = (if (khz % 5000 == 0) 1.5f else 1f).dp.toPx()
                )
                khz += 500
            }

            // 2. Tuned carrier marker (the only "signal" this view can truthfully show).
            val markerX = tunedFrac * w
            drawLine(
                color = if (isPowerOn) HionePink else Color.Gray,
                start = Offset(markerX, 0f),
                end = Offset(markerX, h),
                strokeWidth = 2.dp.toPx()
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "FM BAND 87.5–108 MHz · TUNING DIAL",
                color = HioneTeal.copy(alpha = 0.85f),
                fontSize = 7.5.sp,
                fontWeight = FontWeight.Bold
            )

            Text(
                if (isPowerOn) "TUNER ON" else "STANDBY",
                color = if (isPowerOn) HionePink else Color.Gray,
                fontSize = 7.5.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun FmRecordButton(
    isRecording: Boolean,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "recPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isRecording) 1.25f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(54.dp)
    ) {
        if (isRecording) {
            Box(
                Modifier
                    .size((44 * pulseScale).dp)
                    .clip(CircleShape)
                    .background(Color(0xFFFF1744).copy(alpha = 0.35f))
                    .border(1.5.dp, Color(0xFFFF1744), CircleShape)
            )
        }

        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(
                    if (isRecording) Brush.verticalGradient(listOf(Color(0xFFFF1744), Color(0xFFB71C1C)))
                    else Brush.verticalGradient(listOf(Mascot.color(0xEE0A1E26), Mascot.color(0xFF0C0D10)))
                )
                .border(1.5.dp, if (isRecording) Color(0xFFFF5252) else CyberGlassBorder, CircleShape)
        ) {
            if (isRecording) {
                Icon(
                    Icons.Default.Stop,
                    contentDescription = "Stop Recording",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            } else {
                Box(
                    Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFF1744))
                )
            }
        }
    }
}
