package com.hione.tv

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

enum class TvViewMode(val title: String) {
    HITS_UI("Hits 4K"),
    CASSETTE("Cassette"),
    VISUALIZER("Visualizer")
}

class HioneTvActivity : ComponentActivity() {

    private lateinit var client: HioneCastClient
    private lateinit var bridgeServer: HioneTvBridgeServer
    private var currentMode by mutableStateOf(TvViewMode.HITS_UI)
    private var themeIndex by mutableIntStateOf(0)
    private var presetIndex by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        client = HioneCastClient(applicationContext)
        client.start()

        bridgeServer = HioneTvBridgeServer(applicationContext, client)
        bridgeServer.start()

        setContent {
            TvRootScreen(
                client = client,
                mode = currentMode,
                themeIndex = themeIndex,
                presetIndex = presetIndex,
                lastUserActionTime = lastUserActionTime.longValue,
                onSelectMode = { currentMode = it },
                onCycleTheme = { themeIndex = (themeIndex + 1) % REAL_TAPE_THEMES.size },
                onCyclePreset = { delta ->
                    val size = VisualizerPreset.entries.size
                    presetIndex = (presetIndex + delta + size) % size
                }
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bridgeServer.stop()
        client.stop()
    }

    private val lastUserActionTime = mutableLongStateOf(System.currentTimeMillis())

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            lastUserActionTime.longValue = System.currentTimeMillis()
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    if (currentMode != TvViewMode.HITS_UI) {
                        currentMode = TvViewMode.HITS_UI
                        return true
                    }
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { client.send("playpause"); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { client.send("play"); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { client.send("pause"); return true }
                KeyEvent.KEYCODE_MEDIA_NEXT -> { client.send("next"); return true }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { client.send("prev"); return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_STEP_FORWARD -> { client.send("forward"); return true }
                KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD -> { client.send("rewind"); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}

@Composable
fun TvRootScreen(
    client: HioneCastClient,
    mode: TvViewMode,
    themeIndex: Int,
    presetIndex: Int,
    lastUserActionTime: Long,
    onSelectMode: (TvViewMode) -> Unit,
    onCycleTheme: () -> Unit,
    onCyclePreset: (Int) -> Unit
) {
    val state by client.state.collectAsStateWithLifecycle()
    val visuals by client.visuals.collectAsStateWithLifecycle()

    val infinitePulse = rememberInfiniteTransition(label = "beaconPulse")
    val beaconAlpha by infinitePulse.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "beaconAlpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF040E10))
    ) {
        if (state.needsCode) {
            PairDialog(error = state.pairError, onPair = { client.pairWith(it) })
            return@Box
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // Unified Top Navigation Bar (Hidden in Fullscreen Visualizer mode for edge-to-edge view)
            if (mode != TvViewMode.VISUALIZER) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 48.dp, end = 48.dp, top = 24.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left Brand & Telemetry Badge
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(
                                    (if (state.connected) Color(0xFF00FF88) else Color(0xFFFF3366))
                                        .copy(alpha = beaconAlpha)
                                )
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "HIONE HITS 4K",
                            color = HioneOrange,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = AudiowideFont,
                            letterSpacing = 1.sp
                        )
                        Spacer(Modifier.width(16.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0x18FFFFFF))
                                .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (state.connected) "LINKED TO ${state.host}" else "STANDBY BEACON",
                                color = if (state.connected) Color(0xFF00FF88) else HioneOrange,
                                fontSize = 12.sp,
                                fontFamily = OrbitronFont,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "TV: ${state.tvIp}:8796",
                            color = HioneMuted,
                            fontSize = 12.sp,
                            fontFamily = OrbitronFont
                        )
                    }

                    // Right Focusable View Mode Tabs
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TvViewMode.entries.forEach { m ->
                            TvModeTab(
                                title = m.title,
                                selected = m == mode,
                                onClick = { onSelectMode(m) }
                            )
                        }
                    }
                }
            }

            // Main Content Area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when (mode) {
                    TvViewMode.HITS_UI -> {
                        HitsView(
                            client = client,
                            state = state,
                            visuals = visuals,
                            onCommand = { client.send(it) },
                            onSwitchMode = onSelectMode
                        )
                    }
                    TvViewMode.CASSETTE -> {
                        RealCassetteView(
                            client = client,
                            state = state,
                            rms = visuals.rms,
                            selectedThemeIndex = themeIndex,
                            onCycleTheme = onCycleTheme,
                            onCommand = { client.send(it) },
                            onBackToHits = { onSelectMode(TvViewMode.HITS_UI) }
                        )
                    }
                    TvViewMode.VISUALIZER -> {
                        VisualizerView(
                            client = client,
                            state = state,
                            visuals = visuals,
                            presetIndex = presetIndex,
                            lastUserActionTime = lastUserActionTime,
                            onCommand = { client.send(it) },
                            onCyclePreset = onCyclePreset,
                            onBackToHits = { onSelectMode(TvViewMode.HITS_UI) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PairDialog(error: String, onPair: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    Box(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF0A181C))
                .padding(36.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Link with M500 DAP", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
            Spacer(Modifier.height(10.dp))
            Text(
                "On the M500, turn on 'Cast to a TV' under Settings. Enter the 6-digit code shown.",
                color = Color(0xFF8FA3A8),
                fontSize = 16.sp
            )
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { v -> code = v.filter { it.isDigit() }.take(6) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 28.sp),
                    modifier = Modifier.width(240.dp)
                )
                Spacer(Modifier.width(16.dp))
                TvFocusableButton(
                    onClick = { if (code.length == 6) onPair(code) },
                    label = "Link Device",
                    primary = true,
                    height = 54.dp
                )
            }
            if (error.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                Text(error, color = Color(0xFFFFB020), fontSize = 15.sp)
            }
        }
    }
}
