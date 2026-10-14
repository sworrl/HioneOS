package com.hione.launcher.volume
import com.hione.launcher.Mascot
import com.hione.launcher.*

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.launcher.AudiowideFont
import com.hione.launcher.HioneCyan
import com.hione.launcher.theme.HioneDiurnalTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

enum class VolumeHudStyle(val key: String, val title: String, val description: String) {
    RIGHT_CYBER_BAR(
        "right_bar",
        "Right-edge bar",
        "A vertical bar on the right edge of the screen (default)"
    ),
    CENTER_CYBER_MODAL(
        "center_modal",
        "Center popup",
        "A centered popup with a loudness warning meter (${Mascot.musicAppName} style)"
    )
}

data class VolumeState(
    // maxVolume == 0 means AudioManager has not been read yet; consumers show "—", not a preset 50%.
    val volumePct: Int = 0,
    val maxVolume: Int = 0,
    val currentVolume: Int = 0,
    val isMuted: Boolean = false,
    val isHudVisible: Boolean = false
)

object HioneVolumeManager {
    private val _state = MutableStateFlow(VolumeState())
    val state: StateFlow<VolumeState> = _state

    private val _hudStyle = MutableStateFlow(VolumeHudStyle.RIGHT_CYBER_BAR)
    val hudStyle: StateFlow<VolumeHudStyle> = _hudStyle

    private var hudDismissJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isRegistered = false

    private const val PREFS = "hione_volume_prefs"
    private const val KEY_USE_HIBY = "use_hiby_volume_dialog"
    private const val KEY_HUD_STYLE = "hione_volume_hud_style"
    private const val HIBY_ENABLE_KEY = "hiby_volume_dialog_enable"
    private const val HIBY_INDICATOR_KEY = "hiby_volume_dialog_indicator"

    /** True if the user has opted into HiBy's stock fullscreen volume dialog. Default false. */
    fun useHibyDialog(ctx: Context): Boolean =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_USE_HIBY, false)

    /** Persist + apply the HiBy-vs-Hione volume-UI choice. true = HiBy's fullscreen dialog. */
    fun setUseHibyDialog(ctx: Context, enabled: Boolean) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_USE_HIBY, enabled).apply()
        applyHibyDialogState(ctx)
    }

    fun getVolumeHudStyle(ctx: Context): VolumeHudStyle {
        val raw = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_HUD_STYLE, VolumeHudStyle.RIGHT_CYBER_BAR.key)
        val style = VolumeHudStyle.values().firstOrNull { it.key == raw } ?: VolumeHudStyle.RIGHT_CYBER_BAR
        _hudStyle.value = style
        runCatching { android.provider.Settings.Global.putString(ctx.contentResolver, KEY_HUD_STYLE, style.key) }
        return style
    }

    fun setVolumeHudStyle(ctx: Context, style: VolumeHudStyle) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_HUD_STYLE, style.key).apply()
        // Mirror to Settings.Global so HioneSystemUI's over-3rd-party-app volume HUD uses the same style.
        runCatching { android.provider.Settings.Global.putString(ctx.contentResolver, KEY_HUD_STYLE, style.key) }
        _hudStyle.value = style
    }

    /** Enforce the current choice at the OS level. */
    private fun applyHibyDialogState(ctx: Context) {
        val v = if (useHibyDialog(ctx)) 1 else 0
        writeGlobalInt(ctx, HIBY_ENABLE_KEY, v)
        writeGlobalInt(ctx, HIBY_INDICATOR_KEY, v)
    }

    private fun writeGlobalInt(ctx: Context, key: String, value: Int) {
        val ok = try {
            Settings.Global.putInt(ctx.applicationContext.contentResolver, key, value)
        } catch (_: Throwable) { false }
        if (!ok) {
            scope.launch(Dispatchers.IO) {
                try { Runtime.getRuntime().exec(arrayOf("su", "-c", "settings put global $key $value")).waitFor() }
                catch (_: Throwable) {}
            }
        }
    }

    @Volatile private var lastReportedVolume = -1

    fun init(ctx: Context) {
        updateFromSystem(ctx)
        getVolumeHudStyle(ctx)
        applyHibyDialogState(ctx)
        if (isRegistered) return
        isRegistered = true

        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                val changed = updateFromSystem(ctx)
                if (changed) {
                    triggerHud(ctx)
                }
            }
        }
        try {
            val volumeUri = Settings.System.getUriFor("volume_music_speaker")
                ?: Settings.System.getUriFor("volume_music")
                ?: Settings.System.CONTENT_URI
            ctx.applicationContext.contentResolver.registerContentObserver(
                volumeUri,
                true,
                observer
            )
        } catch (_: Throwable) {}

        val volumeReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: android.content.Intent?) {
                val changed = updateFromSystem(ctx)
                if (intent?.action == "android.media.VOLUME_CHANGED_ACTION") {
                    val stream = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
                    val now = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1)
                    val prev = intent.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", -1)
                    if (stream == AudioManager.STREAM_MUSIC && now != prev) triggerHud(ctx)
                }
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction("android.media.VOLUME_CHANGED_ACTION")
            addAction("android.media.MASTER_VOLUME_CHANGED_ACTION")
            addAction("android.media.RINGER_MODE_CHANGED")
        }
        try {
            ctx.applicationContext.registerReceiver(volumeReceiver, filter)
        } catch (_: Throwable) {}
    }

    fun updateFromSystem(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val isMuted = cur == 0 || (android.os.Build.VERSION.SDK_INT >= 23 && am.isStreamMute(AudioManager.STREAM_MUSIC))
        val pct = ((cur.toFloat() / max.toFloat()) * 100f).toInt().coerceIn(0, 100)

        val changed = (lastReportedVolume != -1 && lastReportedVolume != cur)
        lastReportedVolume = cur

        _state.value = _state.value.copy(
            volumePct = pct,
            maxVolume = max,
            currentVolume = cur,
            isMuted = isMuted
        )
        return changed
    }

    fun triggerHud(ctx: Context, delta: Int = 0) {
        if (delta != 0) {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (am != null) {
                // Step with setStreamVolume (the slider path), NOT adjustStreamVolume: HiBy's
                // AudioService.adjustStreamVolume() carries an adjust-only, raise-only, per-jack
                // "lock max" gate (balanced 40 / 3.5mm 50 / USB 80, armed by the vendor
                // vendor.audio.hw.volume_lock / volum_tips_ce_flag globals) that silently swallows
                // the step; setStreamVolume has no such gate and reaches the active output on every
                // jack. See HioneNotificationShadeService.handleVolumeKnob for the full trace.
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                val target = (cur + (if (delta > 0) 1 else -1)).coerceIn(0, max)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                updateFromSystem(ctx)
            }
        } else {
            updateFromSystem(ctx)
        }

        val wasVisible = _state.value.isHudVisible
        _state.value = _state.value.copy(isHudVisible = true)

        if (!wasVisible) {
        }

        hudDismissJob?.cancel()
        hudDismissJob = scope.launch {
            delay(2400L)
            _state.value = _state.value.copy(isHudVisible = false)
        }
    }

    fun setVolume(ctx: Context, targetPct: Int) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val targetVal = ((targetPct / 100f) * max).toInt().coerceIn(0, max)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, targetVal, 0)
        updateFromSystem(ctx)
        _state.value = _state.value.copy(volumePct = targetPct.coerceIn(0, 100), isHudVisible = true)

        hudDismissJob?.cancel()
        hudDismissJob = scope.launch {
            delay(2400L)
            _state.value = _state.value.copy(isHudVisible = false)
        }
    }

    fun toggleMute(ctx: Context) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val currentlyMuted = _state.value.isMuted
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            am.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (currentlyMuted) AudioManager.ADJUST_UNMUTE else AudioManager.ADJUST_MUTE,
                0
            )
        } else {
            am.setStreamMute(AudioManager.STREAM_MUSIC, !currentlyMuted)
        }
        updateFromSystem(ctx)
        triggerHud(ctx)
    }

    fun handleKeyEvent(ctx: Context, keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                triggerHud(ctx, +1)
                return true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                triggerHud(ctx, -1)
                return true
            }
            KeyEvent.KEYCODE_VOLUME_MUTE -> {
                toggleMute(ctx)
                return true
            }
        }
        return false
    }

    fun handleKeyDown(keyCode: Int, context: Context): Boolean = handleKeyEvent(context, keyCode)
}

@Composable
fun CyberVolumeBadge(
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier
) = HioneVolumeQuiltBadge(onClick = onClick, modifier = modifier)

fun sampleVolumeGradientColor(pct: Int, isMuted: Boolean): Color {
    if (isMuted) return Color(0xFFFF5252)
    return when {
        pct >= 85 -> com.hione.launcher.ui.HioneIdentity.Coral // Danger Red
        pct >= 70 -> Mascot.color(0xFF6FB0FF) // Neon Pink
        pct >= 50 -> Mascot.color(0xFFB9C2D0) // Electric Purple
        pct >= 25 -> Mascot.color(0xFFFFC56B) // Hione Cyan
        else -> Color(0xFF00FF88)      // Cyber Emerald
    }
}

@Composable
fun HioneVolumeQuiltBadge(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val volState by HioneVolumeManager.state.collectAsState()
    val isDanger = volState.volumePct >= 80
    val dynamicColor = sampleVolumeGradientColor(volState.volumePct, volState.isMuted)

    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    val effectiveScale = 1.0f + (fontScale - 1.0f) * 0.20f
    val badgeFontSize = (7.8f / fontScale * effectiveScale).sp

    val infiniteTransition = rememberInfiniteTransition(label = "VolPulse")
    val hazardGlow by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "VolHazard"
    )

    val badgeBorderColor = if (isDanger) dynamicColor.copy(alpha = hazardGlow) else dynamicColor.copy(alpha = 0.9f)
    val badgeShape = remember { CutCornerShape(4.dp) }

    CyberBespokeBadge(
        onClick = onClick,
        modifier = modifier,
        accentColor = dynamicColor,
        shape = badgeShape,
        gradient = if (isDanger) listOf(Color(0x66FF1744), Color(0xFF200508)) else listOf(dynamicColor.copy(alpha = 0.28f), Mascot.color(0xFF030D14)),
        borderAlphaBase = if (isDanger) hazardGlow else 0.9f
    ) {
        Icon(
            imageVector = when {
                volState.isMuted || volState.volumePct == 0 -> Icons.AutoMirrored.Filled.VolumeMute
                volState.volumePct < 40 -> Icons.AutoMirrored.Filled.VolumeDown
                else -> Icons.AutoMirrored.Filled.VolumeUp
            },
            contentDescription = "Volume",
            tint = dynamicColor,
            modifier = Modifier.size(10.dp)
        )
        Spacer(Modifier.width(2.5.dp))
        Text(
            text = if (volState.isMuted) "MUTE" else "${volState.volumePct}%",
            color = Color.White,
            fontSize = badgeFontSize,
            fontWeight = FontWeight.Black,
            fontFamily = AudiowideFont,
            maxLines = 1
        )
    }
}

/**
 * Universal Dual-Style HioneOS System Volume HUD Overlay.
 *
 * Option 1 (Default): Vertical Right-Edge Sliding Cyber Bar.
 * Option 2 (Hione Music Style): Centered Holographic Cyber Arc & Danger Zone Modal.
 */
@Composable
fun HioneCyberVolumeHudOverlay(
    ctx: Context,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(Unit) { HioneVolumeManager.init(ctx.applicationContext) }
    val volState by HioneVolumeManager.state.collectAsState()
    val hudStyle by HioneVolumeManager.hudStyle.collectAsState()

    val isDanger = volState.volumePct >= 80
    val dynamicColor = sampleVolumeGradientColor(volState.volumePct, volState.isMuted)

    val infiniteTransition = rememberInfiniteTransition(label = "VolumeHudGlow")
    val hazardPulse by infiniteTransition.animateFloat(
        initialValue = 0.65f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(320, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "HazardPulse"
    )

    val glowBrightness = (volState.volumePct / 100f).coerceIn(0.4f, 1.0f)

    if (hudStyle == VolumeHudStyle.RIGHT_CYBER_BAR) {
        // =========================================================================
        // STYLE 1 (DEFAULT): RIGHT-EDGE SLIDING CYBER BAR
        // =========================================================================
        AnimatedVisibility(
            visible = volState.isHudVisible,
            enter = fadeIn(tween(140)) + slideInHorizontally(tween(160)) { it },
            exit = fadeOut(tween(220)) + slideOutHorizontally(tween(220)) { it },
            modifier = modifier
        ) {
            Box(
                modifier = Modifier
                    .padding(end = 4.dp)
                    .width(52.dp)
                    .height(235.dp)
                    .clip(CutCornerShape(12.dp))
                    .background(
                        Brush.verticalGradient(
                            if (isDanger) listOf(Mascot.color(0xF5240810), Color(0xFA140205))
                            else listOf(Mascot.color(0xF008202D), Mascot.color(0xFA030D14))
                        )
                    )
                    .border(
                        (1.0f + glowBrightness * 0.8f).dp,
                        Brush.verticalGradient(
                            if (isDanger) listOf(com.hione.launcher.ui.HioneIdentity.Coral, Mascot.color(0xFFFF007F), Color(0xFFFF5252))
                            else listOf(dynamicColor, HioneCyan, Color(0xFF7C4DFF))
                        ),
                        CutCornerShape(12.dp)
                    )
                    .padding(vertical = 7.dp, horizontal = 4.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // Top: Plus Button
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(if (isDanger) Color(0x44FF1744) else dynamicColor.copy(alpha = 0.25f))
                            .clickable { HioneVolumeManager.triggerHud(ctx, +1) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Vol Up",
                            tint = if (isDanger) com.hione.launcher.ui.HioneIdentity.Coral else dynamicColor,
                            modifier = Modifier.size(15.dp)
                        )
                    }

                    // Center: Vertical Sliding Volume Core Gauge
                    Box(
                        modifier = Modifier
                            .width(22.dp)
                            .weight(1f)
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(Mascot.color(0x5504141E))
                            .border(
                                0.9.dp,
                                if (isDanger) com.hione.launcher.ui.HioneIdentity.Coral.copy(alpha = hazardPulse)
                                else dynamicColor.copy(alpha = glowBrightness),
                                RoundedCornerShape(11.dp)
                            )
                            .pointerInput(Unit) {
                                detectVerticalDragGestures { change, _ ->
                                    change.consume()
                                    val y = change.position.y
                                    val h = size.height.toFloat()
                                    val pct = ((1f - (y / h)) * 100f).toInt().coerceIn(0, 100)
                                    HioneVolumeManager.setVolume(ctx, pct)
                                }
                            }
                    ) {
                        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                            val w = size.width
                            val h = size.height
                            val frac = (volState.volumePct / 100f).coerceIn(0f, 1f)

                            val staticBrush = if (volState.isMuted) {
                                Brush.verticalGradient(
                                    listOf(Color(0xFFFF5252), Color(0xFFB71C1C)),
                                    startY = 0f,
                                    endY = h
                                )
                            } else {
                                Brush.verticalGradient(
                                    0.00f to com.hione.launcher.ui.HioneIdentity.Coral,
                                    0.12f to Color(0xFFFF5252),
                                    0.25f to Mascot.color(0xFFFF3399),
                                    0.50f to Color(0xFF9D4EDD),
                                    0.75f to Mascot.color(0xFFFFC56B),
                                    1.00f to Mascot.color(0xFFFFC56B),
                                    startY = 0f,
                                    endY = h
                                )
                            }

                            val fillHeight = h * frac
                            val topY = h - fillHeight
                            if (fillHeight > 0f) {
                                drawRoundRect(
                                    brush = staticBrush,
                                    topLeft = androidx.compose.ui.geometry.Offset(0f, topY),
                                    size = androidx.compose.ui.geometry.Size(w, fillHeight),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(11.dp.toPx(), 11.dp.toPx())
                                )

                                drawCircle(
                                    brush = Brush.radialGradient(
                                        listOf(dynamicColor, dynamicColor.copy(alpha = 0.4f), Color.Transparent),
                                        radius = w * 1.8f,
                                        center = androidx.compose.ui.geometry.Offset(w / 2f, topY)
                                    ),
                                    radius = w * 1.8f,
                                    center = androidx.compose.ui.geometry.Offset(w / 2f, topY)
                                )
                                drawLine(
                                    color = Color.White.copy(alpha = 0.95f),
                                    start = androidx.compose.ui.geometry.Offset(2.dp.toPx(), topY + 1.dp.toPx()),
                                    end = androidx.compose.ui.geometry.Offset(w - 2.dp.toPx(), topY + 1.dp.toPx()),
                                    strokeWidth = 2.dp.toPx(),
                                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                                )
                            }
                        }
                    }

                    // Volume % Text
                    Text(
                        text = if (volState.isMuted) "MUTE" else "${volState.volumePct}%",
                        color = if (isDanger) Color(0xFFFF5252) else Color.White,
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont,
                        textAlign = TextAlign.Center
                    )

                    // Bottom: Volume Minus Button
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(dynamicColor.copy(alpha = 0.25f))
                            .clickable { HioneVolumeManager.triggerHud(ctx, -1) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Remove,
                            contentDescription = "Vol Down",
                            tint = dynamicColor,
                            modifier = Modifier.size(15.dp)
                        )
                    }

                    // Mute Icon Toggle
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(if (volState.isMuted) Color(0x66FF1744) else dynamicColor.copy(alpha = 0.2f))
                            .clickable { HioneVolumeManager.toggleMute(ctx) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (volState.isMuted) Icons.AutoMirrored.Filled.VolumeMute else Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = "Mute",
                            tint = if (volState.isMuted) Color(0xFFFF5252) else dynamicColor,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }

                if (isDanger) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .offset(y = (-5).dp)
                            .clip(CutCornerShape(4.dp))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(com.hione.launcher.ui.HioneIdentity.Coral, Mascot.color(0xFFFF007F))
                                )
                            )
                            .border(1.dp, Color.White.copy(alpha = 0.95f), CutCornerShape(4.dp))
                            .padding(horizontal = 4.5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = ">80dB",
                            color = Color.White,
                            fontSize = 6.5.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = AudiowideFont
                        )
                    }
                }
            }
        }
    } else {
        // =========================================================================
        // STYLE 2: CENTER CYBER ARC / MODAL (HIONE MUSIC BESPOKE STYLE)
        // =========================================================================
        AnimatedVisibility(
            visible = volState.isHudVisible,
            enter = fadeIn(tween(120)) + scaleIn(tween(140, easing = LinearOutSlowInEasing), initialScale = 0.9f),
            exit = fadeOut(tween(180)) + scaleOut(tween(180, easing = FastOutLinearInEasing), targetScale = 0.9f),
            modifier = modifier
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 36.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            Brush.verticalGradient(
                                if (isDanger) listOf(Color(0xF033050C), Color(0xF5180004))
                                else listOf(Mascot.color(0xE6081A24), Mascot.color(0xF2030D13))
                            )
                        )
                        .border(
                            1.5.dp,
                            Brush.horizontalGradient(
                                if (isDanger) listOf(Mascot.color(0xFFFF0044), Color(0xFFFF5500).copy(alpha = hazardPulse))
                                else listOf(Mascot.color(0xFFFFAB2E), Mascot.color(0xFFFF4FA3))
                            ),
                            RoundedCornerShape(20.dp)
                        )
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(if (isDanger) Mascot.color(0x44FF0044) else Mascot.color(0x33FFAB2E)),
                            contentAlignment = Alignment.Center
                        ) {
                            val icon = when {
                                isDanger -> Icons.Default.Warning
                                volState.isMuted || volState.volumePct == 0 -> Icons.AutoMirrored.Filled.VolumeMute
                                volState.volumePct < 50 -> Icons.AutoMirrored.Filled.VolumeDown
                                else -> Icons.AutoMirrored.Filled.VolumeUp
                            }
                            Icon(
                                icon,
                                contentDescription = "Volume",
                                tint = if (isDanger) Color(0xFFFF2255) else Mascot.color(0xFFFFAB2E),
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Column {
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    text = String.format(Locale.US, "%d", volState.volumePct),
                                    color = if (isDanger) Mascot.color(0xFFFF3366) else Color.White,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = AudiowideFont
                                )
                                Text(
                                    text = if (isDanger) " / 100 [HIGH]" else " / 100",
                                    color = if (isDanger) Mascot.color(0xFFFF88AA) else Mascot.color(0xFFFFE0B0),
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = AudiowideFont,
                                    modifier = Modifier.padding(bottom = 2.dp, start = 2.dp)
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            // Dynamic Progress Bar
                            Box(
                                modifier = Modifier
                                    .width(110.dp)
                                    .height(5.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Color(0x33FFFFFF))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(fraction = (volState.volumePct / 100f).coerceIn(0f, 1f))
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(
                                            Brush.horizontalGradient(
                                                if (isDanger) listOf(Mascot.color(0xFFFF0055), Color(0xFFFF8800))
                                                else listOf(Mascot.color(0xFFFFAB2E), Mascot.color(0xFFFF4FA3))
                                            )
                                        )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
