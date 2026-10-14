package com.hione.launcher.lockscreen

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.hione.launcher.AudiowideFont
import com.hione.launcher.HioneCyan
import com.hione.launcher.HioneNeonPink
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * HioneOS Always-On Display — the default AOD face: big clock, date, weather line, and the
 * currently-playing track. Opt-in via [HioneLockscreenPrefs] (off / while-charging / always).
 *
 * The M500's panel is an LCD (no per-pixel OLED self-emission), so "always on" here means the
 * panel held awake at minimum window brightness on a pure-black face — the cheapest possible
 * LCD state. The face drifts a few dp every minute anyway so the habit carries to any future
 * OLED hardware. A tap anywhere hands off to the real lockscreen.
 */
class HioneAodActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Live above the (stock) keyguard, keep the panel awake, at minimum brightness.
        setShowWhenLocked(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = 0.02f }

        setContent {
            val ctx = LocalContext.current
            val showWeather = remember { HioneLockscreenPrefs.getAodShowWeather(ctx) }
            val showNp = remember { HioneLockscreenPrefs.getAodShowNowPlaying(ctx) }

            var now by remember { mutableStateOf(Date()) }
            var driftStep by remember { mutableStateOf(0) }
            LaunchedEffect(Unit) {
                while (true) {
                    now = Date()
                    driftStep = (driftStep + 1) % 5
                    delay(60_000L)
                }
            }

            val weatherState by com.hione.launcher.weather.HioneWeatherService.state.collectAsState()
            val bpmState by com.hione.launcher.bpm.HioneBpmEngine.state.collectAsState()

            // Now-playing metadata published by Hione Music into Settings.Global.
            var npTitle by remember { mutableStateOf("") }
            var npArtist by remember { mutableStateOf("") }
            LaunchedEffect(bpmState.isPlaying) {
                while (true) {
                    npTitle = runCatching {
                        android.provider.Settings.Global.getString(ctx.contentResolver, "hione_now_playing_title") ?: ""
                    }.getOrDefault("")
                    npArtist = runCatching {
                        android.provider.Settings.Global.getString(ctx.contentResolver, "hione_now_playing_artist") ?: ""
                    }.getOrDefault("")
                    delay(5_000L)
                }
            }

            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        // Hand off to the real lockscreen — it is authoritative.
                        try {
                            startActivity(
                                android.content.Intent(this@HioneAodActivity, HioneLockscreenActivity::class.java)
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            )
                        } catch (_: Throwable) {}
                        finish()
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    // Gentle positional drift (a few dp per minute) — burn-in hygiene.
                    modifier = Modifier.padding(top = (driftStep * 6).dp)
                ) {
                    Text(
                        text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now),
                        color = HioneCyan.copy(alpha = 0.85f),
                        fontSize = 62.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont
                    )
                    Text(
                        text = SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(now),
                        color = Color(0xFF6F8F8C),
                        fontSize = 17.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = AudiowideFont
                    )

                    if (showWeather) {
                        val w = weatherState.weather
                        // Only a fetched condition (lastUpdatedTime > 0) is shown — never the model defaults.
                        if (w.lastUpdatedTime > 0L) {
                            Spacer(Modifier.height(14.dp))
                            Text(
                                text = "${w.icon} ${w.tempF.toInt()}°F · ${w.summary}",
                                color = Color(0xFF89ACA7),
                                fontSize = 16.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (showNp && bpmState.isPlaying && npTitle.isNotBlank()) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "$npTitle",
                            color = HioneNeonPink,
                            fontSize = 17.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        if (npArtist.isNotBlank()) {
                            Text(
                                text = npArtist,
                                color = Color(0xFF6F8F8C),
                                fontSize = 14.5.sp,
                                maxLines = 1
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        // 1-Line AOD Mini Beat Visualizer & BPM Badge
                        Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = if (bpmState.bpm in 40f..300f) "${bpmState.bpm.toInt()} BPM" else "— BPM",
                                color = HioneCyan.copy(alpha = 0.85f),
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = AudiowideFont
                            )
                            // Decorative beat pulse (period driven by the real beatIntervalMs) —
                            // NOT a spectrum analyser: no FFT/per-band audio data is read here.
                            val pulseAnim = rememberInfiniteTransition(label = "AodSpec")
                            val phase by pulseAnim.animateFloat(
                                initialValue = 0.2f,
                                targetValue = 0.85f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(durationMillis = (bpmState.beatIntervalMs / 2).toInt().coerceIn(120, 800)),
                                    repeatMode = RepeatMode.Reverse
                                ),
                                label = "aod_pulse"
                            )
                            Row(
                                modifier = Modifier.height(10.dp),
                                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.Bottom
                            ) {
                                repeat(6) { idx ->
                                    val barH = (phase * (0.4f + (idx % 3).toFloat() * 0.3f)).coerceIn(0.15f, 1f)
                                    Box(
                                        Modifier
                                            .width(2.dp)
                                            .fillMaxHeight(barH)
                                            .background(if (idx % 2 == 0) HioneCyan.copy(alpha = 0.7f) else HioneNeonPink.copy(alpha = 0.7f))
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
