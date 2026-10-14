package com.hione.launcher.bpm

import com.hione.launcher.Mascot
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.launcher.AudiowideFont
import com.hione.launcher.audio.HioneSeasonalAudioEngine
import com.hione.launcher.haptics.HioneTactileHaptics
import com.hione.launcher.ui.swipeUpFromBottomToDismiss
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// =========================================================================
// 🌸 ULTRA KAWAII SWEET PASTEL PALETTE & JUICY ARCADE THEME
// =========================================================================
private val KawaiiSakuraPink get() = Mascot.color(0xFFFF85B3)
private val KawaiiHotPink get() = Mascot.color(0xFFFF3385)
private val KawaiiHioneMint get() = Mascot.color(0xFFFFAB2E)
private val KawaiiSoftTeal get() = Mascot.color(0xFF66E0D8)
private val KawaiiLavender = Color(0xFFDFB8FF)
private val KawaiiGoldenHoney = Color(0xFFFFD166)
private val KawaiiPeach = Color(0xFFFF9E80)
private val KawaiiCardBg = Color(0xEE140A18)
private val KawaiiSurface = Color(0xF2200E26)
private val KawaiiTextMuted = Color(0xFFD8B4E2)

/**
 * Oki Hione Ultra-Kawaii BPM Observatory & Project DIVA Rhythm Arcade.
 * Packed with juicy tactile visual feedback, rainbow fever gauges, SSS+ performance grades,
 * particle explosions, and high-readability typography for the HiBy M500 DAP screen.
 */
@Composable
fun HioneBpmObservatoryModal(
    onClose: () -> Unit,
    bpmState: HioneBpmEngine.BpmState
) {
    // A fresh set every time the stage opens: full-ish room, neutral mood, no chaos running.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        HioneStagePerformance.beginSet()
        // Session-scoped secret conditions ("…in one session") count from this stage visit.
        HioneSecrets.beginSession()
    }

    val ctx = LocalContext.current
    val bpmDb = remember { HioneBpmDatabase.getInstance(ctx) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        HioneBeatClickerEngine.init(ctx)
        HioneBpmSeasonsEngine.init(ctx)
        // Loads the saved timing offset and probes what the platform will tell us about output
        // latency. Idempotent.
        HioneRhythmCalibration.init(ctx)
        HioneSecrets.init(ctx)
        HioneSongRecords.init(ctx)
    }

    // Modal Mode: 0 = Kawaii Beat Match, 1 = Sweet Leek Clicker, 2 = Producer Skills, 3 = Skins & Textures, 4 = Quests & DB, 5 = Seasons
    val selectedModeState = remember { mutableIntStateOf(0) }
    val selectedMode by selectedModeState

    var activeMediaLink by remember { mutableStateOf<com.hione.launcher.lockscreen.HioneMediaLink?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            activeMediaLink = com.hione.launcher.lockscreen.HioneMediaLink.active(ctx)
            delay(500L)
        }
    }

    val isSessionPlaying = activeMediaLink?.isPlaying == true
    val isPlaying = bpmState.isPlaying || isSessionPlaying

    // hasLiveTempo gates every READOUT; the 120 fallback below only feeds the rhythm-game engine's
    // default tempo and is never printed as a measurement.
    val hasLiveTempo = bpmState.bpm.isFinite() && bpmState.bpm in 20f..999f
    val liveBpm = if (hasLiveTempo) bpmState.bpm else 120f
    val beatIntervalMs = bpmState.beatIntervalMs.coerceIn(60L, 3000L)

    val cr = ctx.contentResolver
    // Session-agnostic track identification: prefer active MediaSession (Spotify, Tidal, etc.),
    // then fall back to Settings.Global keys written by Hione Music.
    val trackTitle: String? = remember(activeMediaLink?.title, activeMediaLink?.packageName, isPlaying, bpmState.dominantColor, bpmState.bpm) {
        val linkTitle = activeMediaLink?.title?.takeIf { it.isNotBlank() }
        if (linkTitle != null) {
            linkTitle
        } else {
            try { android.provider.Settings.Global.getString(cr, "hione_now_playing_title")?.takeIf { it.isNotBlank() } } catch (_: Throwable) { null }
        }
    }
    val trackArtist: String? = remember(activeMediaLink?.artist, activeMediaLink?.packageName, isPlaying, bpmState.dominantColor, bpmState.bpm) {
        val linkArtist = activeMediaLink?.artist?.takeIf { it.isNotBlank() }
        if (linkArtist != null) {
            linkArtist
        } else {
            try { android.provider.Settings.Global.getString(cr, "hione_now_playing_artist")?.takeIf { it.isNotBlank() } } catch (_: Throwable) { null }
        }
    }

    val calculatedTapBpmState = remember { mutableStateOf<Float?>(null) }
    var calculatedTapBpm by calculatedTapBpmState
    val dbStatsState = remember { mutableStateOf<Map<String, Any>>(emptyMap()) }
    var dbStats by dbStatsState

    // A new track is a new level: fresh run, that song's personal best, and per-track secret runs.
    LaunchedEffect(trackArtist, trackTitle) {
        val key = HioneSongRecords.keyFor(trackArtist, trackTitle)
        HioneSongRecords.beginRun(key)
        HioneSecrets.onTrackChanged(key)
    }

    // Auto-resolve canonical BPM from SQLite / Preseeded dictionary on track change
    LaunchedEffect(trackArtist, trackTitle) {
        dbStats = bpmDb.getCalibrationStats()
        // No published track = nothing to look up. Never resolve (or broadcast) a tempo for a
        // track identity we invented.
        if (trackTitle == null || trackArtist == null) return@LaunchedEffect
        val rec = bpmDb.resolveCanonicalBpm(trackArtist, trackTitle)
        if (rec != null && rec.canonicalBpm > 0f) {
            calculatedTapBpm = rec.canonicalBpm
            try {
                android.provider.Settings.Global.putFloat(ctx.contentResolver, "hione_live_bpm", rec.canonicalBpm)
                android.provider.Settings.Global.putInt(ctx.contentResolver, "hione_beat_interval_ms", (60000f / rec.canonicalBpm).toInt())
                val intent = android.content.Intent("com.hione.action.BPM_UPDATE").apply {
                    putExtra("bpm", rec.canonicalBpm)
                    putExtra("beat_interval_ms", (60000f / rec.canonicalBpm).toLong())
                    putExtra("is_playing", isPlaying)
                }
                ctx.sendBroadcast(intent)
            } catch (_: Throwable) {}
        }
    }

    // High-Precision Metronome & Approach Clocks
    var beatTick by remember { mutableLongStateOf(0L) }
    var lastBeatEpochMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(isPlaying, beatIntervalMs, bpmState.lastPulseEpochMs) {
        if (!isPlaying) {
            beatTick = 0L
            return@LaunchedEffect
        }
        while (true) {
            lastBeatEpochMs = SystemClock.elapsedRealtime()
            beatTick += 1
            delay(beatIntervalMs)
        }
    }

    // Incremental Game Loop Tick (200ms interval for 0 lag)
    LaunchedEffect(liveBpm, isPlaying) {
        while (true) {
            delay(200L)
            HioneBeatClickerEngine.tick(0.2, if (isPlaying) liveBpm else 120f)
        }
    }

    // Approach Ring Phase: 1.6f -> 0.0f
    val beatApproachAnim = remember { Animatable(1f) }
    val beatPulseScale = remember { Animatable(1f) }
    LaunchedEffect(isPlaying, beatTick) {
        if (!isPlaying || beatTick == 0L) {
            beatApproachAnim.snapTo(1f)
            beatPulseScale.snapTo(1f)
            return@LaunchedEffect
        }
        launch {
            beatApproachAnim.snapTo(1.6f)
            beatApproachAnim.animateTo(0f, tween(beatIntervalMs.toInt().coerceAtLeast(60), easing = LinearEasing))
        }
        launch {
            beatPulseScale.snapTo(1.18f)
            beatPulseScale.animateTo(1.0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
        }
    }

    // Game Economy & Customization State
    val leekCount by HioneBeatClickerEngine.leeks.collectAsState()
    val clickerBuildings by HioneBeatClickerEngine.buildings.collectAsState()
    val clickerSkills by HioneBeatClickerEngine.skills.collectAsState()
    val currentSkin by HioneBeatClickerEngine.currentSkin.collectAsState()
    val achievements by HioneBeatClickerEngine.achievements.collectAsState()
    val clickerCombo by HioneBeatClickerEngine.combo.collectAsState()
    val feverEnergy by HioneBeatClickerEngine.feverEnergy.collectAsState()
    val feverSeconds by HioneBeatClickerEngine.feverSeconds.collectAsState()
    val goldenLeekVisible by HioneBeatClickerEngine.goldenLeekVisible.collectAsState()
    val floatingTexts by HioneBeatClickerEngine.floatingTexts.collectAsState()
    val activeParticles by HioneBeatClickerEngine.particles.collectAsState()

    val activeSkinPrimary = Color(currentSkin.primaryColor)
    val activeSkinAccent = Color(currentSkin.accentColor)

    val currentSeason by HioneBpmSeasonsEngine.currentSeason.collectAsState()
    val lifetimeStats by HioneBpmSeasonsEngine.lifetime.collectAsState()

    // Real-Time Hit Timing State
    // State holders (not `by` delegates) so the extracted sub-composables below can share the
    // exact same MutableState objects and keep read-after-write semantics inside tap callbacks.
    val tapTimestamps = remember { mutableStateListOf<Long>() }
    val tapCounterState = remember { mutableIntStateOf(0) }
    val timingOffsetMsState = remember { mutableIntStateOf(0) }
    val judgmentTitleState = remember { mutableStateOf("") }
    val judgmentColorState = remember { mutableStateOf(KawaiiSakuraPink) }
    val lastTapTimeMsState = remember { mutableLongStateOf(0L) }
    val perfectShockwaveTriggerState = remember { mutableIntStateOf(0) }

    BackHandler(enabled = true) {
        HioneBeatClickerEngine.saveState()
        onClose()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xD90E0514))
            .clickable {
                HioneBeatClickerEngine.saveState()
                onClose()
            }
            .swipeUpFromBottomToDismiss(onDismiss = {
                HioneBeatClickerEngine.saveState()
                onClose()
            }),
        contentAlignment = Alignment.BottomCenter
    ) {
        // The game sheet. Its palette is the SKIN's own (Cyber Mirai is a near-black synth panel,
        // Honey Sweet is warm amber, Hologram 39 is deep teal), not one fixed pink for every skin —
        // a skin that only repaints the node in the middle of an identical sheet is a recolour.
        val shakeX by rememberGameShake()
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.96f)
                .graphicsLayer { translationX = shakeX }
                .clickable(enabled = false) {}
                .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(Color(currentSkin.sheetTop), Color(currentSkin.sheetBottom), Color(0xFF05020A))
                    )
                )
                .border(
                    BorderStroke(
                        2.5.dp,
                        Brush.linearGradient(
                            listOf(
                                if (feverSeconds > 0) KawaiiGoldenHoney else activeSkinPrimary,
                                Color(currentSkin.glowColor),
                                activeSkinAccent
                            )
                        )
                    ),
                    RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
                )
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            // Floating Kawaii Heart & Star Particles Canvas
            KawaiiDreamyHeartCanvas(
                modifier = Modifier.fillMaxSize(),
                isFever = feverSeconds > 0,
                ambient = currentSkin.ambient,
                skinPrimary = activeSkinPrimary,
                skinAccent = activeSkinAccent
            )

            // Dynamic Hit Particles System
            KawaiiJuicyParticleOverlay(
                particles = activeParticles,
                burst = currentSkin.burst,
                modifier = Modifier.fillMaxSize()
            )

            // Layout Column (Single Page, 0 Scroll)
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // 1. TOP HEADER & CUTE MODE PILLS + FEVER GAUGE
                BpmObservatoryHeader(
                    selectedModeState = selectedModeState,
                    currentSeason = currentSeason,
                    activeSkinPrimary = activeSkinPrimary,
                    feverEnergy = feverEnergy,
                    feverSeconds = feverSeconds,
                    onClose = onClose
                )

                // 2. HERO SECTION: TIMING & TRACK CARDS
                if (selectedMode == 0) {
                    BpmHeroRhythmMatchCard(
                        isPlaying = isPlaying,
                        hasLiveTempo = hasLiveTempo,
                        liveBpm = liveBpm,
                        beatIntervalMs = beatIntervalMs,
                        trackTitle = trackTitle,
                        trackArtist = trackArtist,
                        activeSkinPrimary = activeSkinPrimary,
                        timingOffsetMs = timingOffsetMsState.intValue,
                        judgmentColor = judgmentColorState.value,
                        lastTapTimeMs = lastTapTimeMsState.longValue
                    )
                } else if (selectedMode == 1) {
                    BpmHeroHarvestCard(
                        liveBpm = liveBpm,
                        leekCount = leekCount,
                        clickerCombo = clickerCombo,
                        feverSeconds = feverSeconds
                    )
                } else if (selectedMode == 2) {
                    BpmHeroSkillsCard(clickerSkills = clickerSkills)
                } else if (selectedMode == 3) {
                    BpmHeroSkinsCard(currentSkin = currentSkin, activeSkinPrimary = activeSkinPrimary)
                } else if (selectedMode == 4) {
                    BpmHeroQuestsCard(achievements = achievements, dbStats = dbStats)
                } else {
                    BpmHeroSeasonsCard(currentSeason = currentSeason, lifetimeStats = lifetimeStats)
                }

                // 3. CENTER: PROJECT DIVA APPROACH RING & ULTRA KAWAII BEAT NODE
                BpmBeatNodeStage(
                    approachRadius = beatApproachAnim.value,
                    pulseScale = beatPulseScale.value,
                    isPlaying = isPlaying,
                    hasLiveTempo = hasLiveTempo,
                    liveBpm = liveBpm,
                    beatIntervalMs = beatIntervalMs,
                    lastBeatEpochMs = lastBeatEpochMs,
                    goldenLeekVisible = goldenLeekVisible,
                    skin = currentSkin,
                    feverSeconds = feverSeconds,
                    clickerCombo = clickerCombo,
                    floatingTexts = floatingTexts,
                    trackArtist = trackArtist,
                    trackTitle = trackTitle,
                    bpmDb = bpmDb,
                    coroutineScope = coroutineScope,
                    tapTimestamps = tapTimestamps,
                    tapCounterState = tapCounterState,
                    calculatedTapBpmState = calculatedTapBpmState,
                    timingOffsetMsState = timingOffsetMsState,
                    judgmentTitleState = judgmentTitleState,
                    judgmentColorState = judgmentColorState,
                    lastTapTimeMsState = lastTapTimeMsState,
                    perfectShockwaveTriggerState = perfectShockwaveTriggerState,
                    dbStatsState = dbStatsState
                )

                // 4. BOTTOM DOCK (Modes 0..5)
                if (selectedMode == 1 || selectedMode == 2 || selectedMode == 3 || selectedMode == 4) {
                    BpmGameDockCards(
                        selectedMode = selectedMode,
                        leekCount = leekCount,
                        clickerBuildings = clickerBuildings,
                        clickerSkills = clickerSkills,
                        currentSkin = currentSkin,
                        achievements = achievements
                    )
                } else {
                    BpmCalibrationFooter(
                        isPlaying = isPlaying,
                        hasLiveTempo = hasLiveTempo,
                        liveBpm = liveBpm,
                        leekCount = leekCount,
                        clickerCombo = clickerCombo,
                        currentSeason = currentSeason,
                        trackArtist = trackArtist,
                        trackTitle = trackTitle,
                        bpmDb = bpmDb,
                        coroutineScope = coroutineScope,
                        tapCounterState = tapCounterState,
                        calculatedTapBpmState = calculatedTapBpmState,
                        dbStatsState = dbStatsState
                    )
                }
            }

            // Game-feel layers on top of everything: the big centre pop, then the unlock moment.
            HioneGamePopLayer(Modifier.fillMaxSize().padding(bottom = 120.dp))
            HioneCelebrationOverlay()
        }
    }
}

/**
 * Rainbow Sugar Fever Energy Progress Bar with Shimmering Pulse.
 */
@Composable
private fun KawaiiSugarFeverGauge(
    feverEnergy: Float,
    feverSeconds: Int,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "FeverShimmer")
    val shimmerOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmer"
    )

    Box(
        modifier = modifier
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(Color(0x40000000))
            .border(1.dp, if (feverSeconds > 0) KawaiiGoldenHoney else KawaiiSakuraPink.copy(alpha = 0.5f), RoundedCornerShape(5.dp))
    ) {
        val frac = if (feverSeconds > 0) 1f else (feverEnergy / 100f).coerceIn(0f, 1f)
        val animatedFrac by animateFloatAsState(targetValue = frac, animationSpec = spring(stiffness = Spring.StiffnessMediumLow), label = "feverFrac")

        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(animatedFrac)
                .clip(RoundedCornerShape(5.dp))
                .background(
                    if (feverSeconds > 0) {
                        Brush.horizontalGradient(
                            listOf(KawaiiGoldenHoney, KawaiiHotPink, KawaiiHioneMint, KawaiiLavender)
                        )
                    } else {
                        Brush.horizontalGradient(
                            listOf(KawaiiSakuraPink, KawaiiHotPink, KawaiiHioneMint)
                        )
                    }
                )
        )
    }
}

/**
 * Visual Rhythm Timing Calibration Gauge.
 */
@Composable
private fun KawaiiTimingDeviationBar(
    offsetMs: Int,
    windows: HioneRhythmTiming.Windows,
    modifier: Modifier = Modifier
) {
    // The bar now DRAWS the real hit windows instead of a decorative 20% band: the player can
    // see how much room each tier actually has, and watch those bands widen or tighten when the
    // adaptive difficulty tier changes or a Timing Window Expander tier is bought. A scale you
    // can see is a scale you can aim at.
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(14.dp)
    ) {
        val w = size.width
        val h = size.height
        val midX = w / 2f
        // Full scale = the OK window plus a little headroom, so a MISS still lands on the bar.
        val scaleMs = (windows.okMs * 1.25f).coerceAtLeast(40f)
        fun halfWidthPx(ms: Int): Float = (ms / scaleMs) * (w / 2f)

        drawLine(
            color = Color(0x44FFFFFF),
            start = Offset(0f, h / 2f),
            end = Offset(w, h / 2f),
            strokeWidth = 3f,
            cap = StrokeCap.Round
        )

        // Widest tier first so the tighter ones paint on top.
        fun band(ms: Int, color: Color) {
            val half = halfWidthPx(ms)
            drawRect(color = color, topLeft = Offset(midX - half, 0f), size = Size(half * 2f, h))
        }
        band(windows.okMs, KawaiiLavender.copy(alpha = 0.12f))
        band(windows.goodMs, KawaiiSoftTeal.copy(alpha = 0.18f))
        band(windows.greatMs, KawaiiSakuraPink.copy(alpha = 0.25f))
        band(windows.perfectMs, KawaiiHotPink.copy(alpha = 0.45f))

        drawLine(
            color = Color.White,
            start = Offset(midX, 0f),
            end = Offset(midX, h),
            strokeWidth = 2f
        )

        val fraction = (offsetMs / scaleMs).coerceIn(-1f, 1f)
        val pointerX = midX + (fraction * (w / 2f - 6f))

        val pointerColor = when {
            abs(offsetMs) <= windows.perfectMs -> KawaiiHotPink
            abs(offsetMs) <= windows.greatMs -> KawaiiSakuraPink
            offsetMs < 0 -> KawaiiPeach
            else -> KawaiiSoftTeal
        }

        drawCircle(color = pointerColor, radius = 6f, center = Offset(pointerX, h / 2f))
        drawCircle(color = Color.White, radius = 3f, center = Offset(pointerX, h / 2f))
    }
}

// =========================================================================
// 🎨 SKIN GEOMETRY — the beat node's silhouette, per skin
//
// A skin that only swaps a hue is not a skin. These build the actual outline of the node and
// of its approach ring, so Cyber Mirai is a hexagon closing on a hexagon while Snow Crystal is
// a six-point snowflake — recognisable from across the room, not on a colour chip.
// =========================================================================

private fun polygonPath(cx: Float, cy: Float, r: Float, sides: Int, rotationDeg: Float): Path {
    val p = Path()
    for (i in 0 until sides) {
        val a = Math.toRadians((rotationDeg + i * 360.0 / sides)).toFloat()
        val x = cx + r * cos(a)
        val y = cy + r * sin(a)
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    p.close()
    return p
}

private fun starPath(cx: Float, cy: Float, rOuter: Float, rInner: Float, points: Int, rotationDeg: Float): Path {
    val p = Path()
    val steps = points * 2
    for (i in 0 until steps) {
        val r = if (i % 2 == 0) rOuter else rInner
        val a = Math.toRadians((rotationDeg + i * 360.0 / steps)).toFloat()
        val x = cx + r * cos(a)
        val y = cy + r * sin(a)
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    p.close()
    return p
}

private fun skinOutline(
    shape: HioneBeatClickerEngine.NodeShape,
    cx: Float,
    cy: Float,
    r: Float
): Path = when (shape) {
    HioneBeatClickerEngine.NodeShape.ORB -> Path().apply {
        addOval(androidx.compose.ui.geometry.Rect(cx - r, cy - r, cx + r, cy + r))
    }
    HioneBeatClickerEngine.NodeShape.DIAMOND -> polygonPath(cx, cy, r, 4, -90f)
    HioneBeatClickerEngine.NodeShape.HEX -> polygonPath(cx, cy, r, 6, -90f)
    HioneBeatClickerEngine.NodeShape.STAR -> starPath(cx, cy, r, r * 0.46f, 5, -90f)
    HioneBeatClickerEngine.NodeShape.SNOWFLAKE -> starPath(cx, cy, r, r * 0.55f, 6, -90f)
    HioneBeatClickerEngine.NodeShape.HEART -> Path().apply {
        moveTo(cx, cy + r * 0.85f)
        cubicTo(cx - r * 1.25f, cy + r * 0.05f, cx - r * 0.75f, cy - r * 1.05f, cx, cy - r * 0.45f)
        cubicTo(cx + r * 0.75f, cy - r * 1.05f, cx + r * 1.25f, cy + r * 0.05f, cx, cy + r * 0.85f)
        close()
    }
}

/** The same silhouette as a clip/border [Shape] for the filled core of the node. */
private fun skinNodeShape(shape: HioneBeatClickerEngine.NodeShape): Shape = GenericShape { size, _ ->
    val cx = size.width / 2f
    val cy = size.height / 2f
    val r = minOf(size.width, size.height) / 2f
    addPath(skinOutline(shape, cx, cy, r))
}

/**
 * The PERFECT hit effect, one per skin. Same trigger, five completely different reads:
 * an expanding ring, shards thrown outward, concentric splash rings, a soft velvet swell,
 * or frost cracking away from the node.
 */
private fun DrawScope.drawSkinHitEffect(
    skin: HioneBeatClickerEngine.BpmSkin,
    center: Offset,
    baseRadius: Float,
    progress: Float,
    strokePx: Float
) {
    val alpha = (1f - progress).coerceIn(0f, 1f)
    val color = Color(skin.glowColor).copy(alpha = alpha * 0.85f)
    when (skin.hitEffect) {
        HioneBeatClickerEngine.HitEffect.RING ->
            drawCircle(color, baseRadius + progress * 70f, center, style = Stroke(width = strokePx))
        HioneBeatClickerEngine.HitEffect.SHARD -> {
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0).toFloat()
                val inner = baseRadius + progress * 26f
                val outer = inner + 22f * (1f - progress)
                drawLine(
                    color,
                    Offset(center.x + cos(a) * inner, center.y + sin(a) * inner),
                    Offset(center.x + cos(a) * outer, center.y + sin(a) * outer),
                    strokeWidth = strokePx, cap = StrokeCap.Round
                )
            }
        }
        HioneBeatClickerEngine.HitEffect.SPLASH -> {
            for (i in 0 until 3) {
                val p = (progress + i * 0.18f).coerceAtMost(1f)
                drawCircle(
                    Color(skin.accentColor).copy(alpha = (1f - p) * 0.55f),
                    baseRadius + p * 52f, center, style = Stroke(width = strokePx * 0.8f)
                )
            }
        }
        HioneBeatClickerEngine.HitEffect.VELVET_PULSE ->
            drawPath(
                skinOutline(skin.nodeShape, center.x, center.y, baseRadius * (1f + progress * 0.55f)),
                Color(skin.primaryColor).copy(alpha = alpha * 0.30f)
            )
        HioneBeatClickerEngine.HitEffect.FROST_CRACK -> {
            for (i in 0 until 6) {
                val a = Math.toRadians(i * 60.0 + 15.0).toFloat()
                val r1 = baseRadius + progress * 18f
                val r2 = r1 + 34f * progress
                val midA = a + 0.16f
                drawLine(
                    color,
                    Offset(center.x + cos(a) * r1, center.y + sin(a) * r1),
                    Offset(center.x + cos(midA) * ((r1 + r2) / 2f), center.y + sin(midA) * ((r1 + r2) / 2f)),
                    strokeWidth = strokePx * 0.7f, cap = StrokeCap.Round
                )
                drawLine(
                    color,
                    Offset(center.x + cos(midA) * ((r1 + r2) / 2f), center.y + sin(midA) * ((r1 + r2) / 2f)),
                    Offset(center.x + cos(a - 0.1f) * r2, center.y + sin(a - 0.1f) * r2),
                    strokeWidth = strokePx * 0.7f, cap = StrokeCap.Round
                )
            }
        }
        // Hologram 39: horizontal slices of the silhouette jump sideways, like a bad signal.
        HioneBeatClickerEngine.HitEffect.GLITCH -> {
            val shift = (1f - progress) * 22f
            for (i in 0 until 4) {
                val y = center.y - baseRadius + (i + 0.5f) * (baseRadius * 2f / 4f)
                val dx = if (i % 2 == 0) shift else -shift
                drawLine(
                    (if (i % 2 == 0) Color(skin.primaryColor) else Color(skin.accentColor)).copy(alpha = alpha * 0.8f),
                    Offset(center.x - baseRadius * 1.3f + dx, y), Offset(center.x + baseRadius * 1.3f + dx, y),
                    strokeWidth = strokePx * 1.6f
                )
            }
            drawPath(
                skinOutline(skin.nodeShape, center.x + shift * 0.5f, center.y, baseRadius * (1f + progress * 0.4f)),
                Color(skin.accentColor).copy(alpha = alpha * 0.5f), style = Stroke(width = strokePx)
            )
        }
    }
}

/**
 * Project DIVA Approach Ring & Kawaii Beat Node with Squash & Stretch + Perfect Shockwaves.
 */
@Composable
private fun KawaiiProjectDivaBeatNode(
    approachRadius: Float,
    pulseScale: Float,
    isPlaying: Boolean,
    feverActive: Boolean,
    perfectShockwaveTrigger: Int,
    skin: HioneBeatClickerEngine.BpmSkin,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    // The node's silhouette, its approach ring and its hit effect all come from the skin, so
    // switching skin changes the thing the player is literally staring at while they tap.
    val nodeShape = remember(skin) { skinNodeShape(skin.nodeShape) }
    val coroutineScope = rememberCoroutineScope()
    val touchScale = remember { Animatable(1f) }
    val rippleAnim = remember { Animatable(0f) }
    val shockwaveAnim = remember { Animatable(0f) }

    LaunchedEffect(perfectShockwaveTrigger) {
        if (perfectShockwaveTrigger > 0) {
            shockwaveAnim.snapTo(0f)
            shockwaveAnim.animateTo(1f, tween(400, easing = LinearOutSlowInEasing))
        }
    }

    Box(
        modifier = modifier
            .size(140.dp)
            .scale(touchScale.value * pulseScale)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        coroutineScope.launch {
                            touchScale.snapTo(0.85f)
                            touchScale.animateTo(1.0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                        }
                        coroutineScope.launch {
                            rippleAnim.snapTo(0f)
                            rippleAnim.animateTo(1f, tween(320, easing = LinearOutSlowInEasing))
                        }
                        onTap()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val baseR = (size.minDimension / 2f) * 0.65f

            // Approach Ring — follows the skin's silhouette (a hex closes on a hex).
            if (isPlaying && approachRadius > 0.01f) {
                val currentApproachR = baseR + (approachRadius * 26f)
                drawPath(
                    skinOutline(skin.nodeShape, c.x, c.y, currentApproachR),
                    color = if (feverActive) KawaiiGoldenHoney.copy(alpha = 0.8f) else Color(skin.primaryColor).copy(alpha = 0.85f),
                    style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round)
                )
                drawPath(
                    skinOutline(skin.nodeShape, c.x, c.y, currentApproachR),
                    color = Color.White.copy(alpha = 0.6f),
                    style = Stroke(width = 1.2.dp.toPx())
                )
            }

            // Tap Ripple
            if (rippleAnim.value > 0.01f && rippleAnim.value < 0.99f) {
                val r = baseR + (rippleAnim.value * 35f)
                val alpha = (1f - rippleAnim.value) * 0.9f
                drawPath(
                    skinOutline(skin.nodeShape, c.x, c.y, r),
                    color = Color(skin.accentColor).copy(alpha = alpha),
                    style = Stroke(width = 4.dp.toPx() * (1f - rippleAnim.value))
                )
            }

            // Perfect hit effect — one per skin (ring / shards / splash / velvet / frost).
            if (shockwaveAnim.value > 0.01f && shockwaveAnim.value < 0.99f) {
                drawSkinHitEffect(skin, c, baseR, shockwaveAnim.value, 2.5.dp.toPx())
            }
        }

        Box(
            modifier = Modifier
                .size(104.dp)
                .clip(nodeShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color.White,
                            if (feverActive) KawaiiGoldenHoney else Color(skin.primaryColor),
                            Color(skin.accentColor).copy(alpha = 0.55f)
                        ),
                        center = Offset(34f, 34f),
                        radius = 105f
                    )
                )
                .border(
                    BorderStroke(
                        2.5.dp,
                        Brush.linearGradient(listOf(Color.White, Color(skin.accentColor)))
                    ),
                    nodeShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // The face of the node is the skin's own glyph alphabet. Fever shows the
                // mascot's own mark: a leek for Hione, the coda mark for every other pack.
                if (feverActive && !Mascot.hasHioneLore) {
                    com.hione.launcher.CodaMarkGlyph(22.dp, ring = Mascot.primaryDeep, pip = Mascot.accent)
                } else Text(
                    if (feverActive) "✨🥬✨" else skin.burstGlyphs.take(3).joinToString(""),
                    fontSize = 18.sp
                )
                Text(
                    text = if (skin.judgmentArcade) "TAP BEAT" else "tap beat",
                    color = Color.White,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = if (skin.judgmentArcade) AudiowideFont else null,
                    letterSpacing = (0.5f + skin.judgmentSpacing).sp
                )
            }
        }
    }
}

/**
 * Dynamic Tap Juice Particle Explosions.
 */
@Composable
private fun KawaiiJuicyParticleOverlay(
    particles: List<HioneBeatClickerEngine.JuiceParticle>,
    burst: HioneBeatClickerEngine.BurstStyle,
    modifier: Modifier = Modifier
) {
    // Each skin's burst MOVES differently, not just in a different colour: petals fall and sway,
    // sparks shoot straight out and die fast, bubbles rise and swell, ribbons swirl around the
    // node, crystals hang and twinkle. This is the difference you notice without being told.
    val durationMs = when (burst) {
        HioneBeatClickerEngine.BurstStyle.SPARK_SHOT -> 380
        HioneBeatClickerEngine.BurstStyle.BUBBLE_RISE -> 900
        HioneBeatClickerEngine.BurstStyle.CRYSTAL_DRIFT -> 1000
        else -> 620
    }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        particles.forEach { p ->
            key(p.id) {
                val animProgress = remember { Animatable(0f) }
                LaunchedEffect(p.id) {
                    animProgress.animateTo(
                        1f,
                        tween(
                            durationMs,
                            easing = if (burst == HioneBeatClickerEngine.BurstStyle.SPARK_SHOT) LinearEasing
                            else LinearOutSlowInEasing
                        )
                    )
                }
                val t = animProgress.value
                var x = p.x + p.vx * t
                var y = p.y + p.vy * t
                var scale = 1f
                when (burst) {
                    // Gravity plus a sideways sway — a petal never falls straight.
                    HioneBeatClickerEngine.BurstStyle.PETAL_FALL -> {
                        y += 70f * t * t
                        x += sin(t * 9f + p.vx) * 9f
                        scale = 1f - 0.2f * t
                    }
                    // Straight, fast, and gone.
                    HioneBeatClickerEngine.BurstStyle.SPARK_SHOT -> {
                        x = p.x + p.vx * t * 1.7f
                        y = p.y + p.vy * t * 1.7f
                        scale = 1f - 0.55f * t
                    }
                    // Slow buoyant rise, swelling as it goes.
                    HioneBeatClickerEngine.BurstStyle.BUBBLE_RISE -> {
                        y = p.y + p.vy * t * 0.35f - 60f * t
                        x += sin(t * 5f + p.vy) * 6f
                        scale = 1f + 0.45f * t
                    }
                    // Orbit the node while drifting outward.
                    HioneBeatClickerEngine.BurstStyle.RIBBON_SWIRL -> {
                        val ang = t * 3.4f
                        val rad = 18f + 46f * t
                        x = p.x + cos(ang + p.vx * 0.05f) * rad
                        y = p.y + sin(ang + p.vx * 0.05f) * rad
                        scale = 1f - 0.15f * t
                    }
                    // Hangs in the air, drifting down, twinkling out.
                    HioneBeatClickerEngine.BurstStyle.CRYSTAL_DRIFT -> {
                        x = p.x + p.vx * t * 0.5f
                        y = p.y + p.vy * t * 0.5f + 26f * t
                        scale = 1f - 0.1f * t
                    }
                }
                val fade = (1f - t).coerceIn(0f, 1f)
                val twinkle =
                    if (burst == HioneBeatClickerEngine.BurstStyle.CRYSTAL_DRIFT)
                        (0.55f + 0.45f * sin(t * 22f)).coerceIn(0f, 1f)
                    else 1f

                Text(
                    text = p.emoji,
                    color = Color(p.colorHex),
                    fontSize = p.sizeDp.sp,
                    modifier = Modifier
                        .offset(x = x.dp, y = y.dp)
                        .graphicsLayer {
                            alpha = fade * twinkle
                            scaleX = scale
                            scaleY = scale
                        }
                )
            }
        }
    }
}

/**
 * Ambient backdrop — one treatment per skin, drawn behind the whole sheet.
 *
 * Every skin used to share the same drifting hearts, which is most of why they read as "the same
 * screen, different tint". Now Sakura rains petals, Cyber Mirai runs a vertical hex/data rain,
 * Honey Sweet floats fat bubbles upward, Gothic Lolita hangs a slow damask veil of diamonds, and
 * Snow Crystal drifts snow sideways.
 */
@Composable
private fun KawaiiDreamyHeartCanvas(
    modifier: Modifier = Modifier,
    isFever: Boolean,
    ambient: HioneBeatClickerEngine.AmbientStyle,
    skinPrimary: Color,
    skinAccent: Color
) {
    val infiniteTransition = rememberInfiniteTransition(label = "DreamyCanvas")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(
                when (ambient) {
                    HioneBeatClickerEngine.AmbientStyle.HEX_RAIN -> 2200
                    HioneBeatClickerEngine.AmbientStyle.DAMASK_VEIL -> 7000
                    else -> 4500
                },
                easing = LinearEasing
            ),
            RepeatMode.Restart
        ),
        label = "phase"
    )

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val count = if (ambient == HioneBeatClickerEngine.AmbientStyle.HEX_RAIN) 22 else 16

        for (i in 0 until count) {
            val seed = i * 142.3f
            val baseX = (seed % w)
            val baseY = ((seed * 2.1f) % h)
            val scale = (0.5f + (i % 4) * 0.15f)
            val tint = when {
                isFever -> if (i % 2 == 0) KawaiiGoldenHoney else skinAccent
                i % 3 == 0 -> skinPrimary
                i % 3 == 1 -> skinAccent
                else -> KawaiiLavender
            }

            when (ambient) {
                // Petals tumble downward with a sideways sway.
                HioneBeatClickerEngine.AmbientStyle.PETALS -> {
                    val y = (baseY + (phase * h * 0.6f) + (i * 18f)).mod(h)
                    val x = baseX + sin((phase * 6.28f) + i) * 16f
                    val alpha = (0.2f + 0.5f * sin(phase * 3.14f + i)).coerceIn(0.1f, 0.7f)
                    drawKawaiiHeart(Offset(x, y), 14f * scale, tint.copy(alpha = alpha))
                }
                // Vertical data rain: short bright hex dashes falling fast.
                HioneBeatClickerEngine.AmbientStyle.HEX_RAIN -> {
                    val y = (baseY - (phase * h * 1.6f) + (i * 31f)).mod(h)
                    val len = 26f * scale
                    drawLine(
                        tint.copy(alpha = 0.28f),
                        Offset(baseX, y), Offset(baseX, y + len),
                        strokeWidth = 2f, cap = StrokeCap.Round
                    )
                    drawPath(
                        polygonPath(baseX, y + len, 5f * scale, 6, -90f),
                        tint.copy(alpha = 0.5f), style = Stroke(width = 1.4f)
                    )
                }
                // Fat syrup bubbles rising and wobbling.
                HioneBeatClickerEngine.AmbientStyle.HONEY_BUBBLES -> {
                    val y = (baseY - (phase * h * 0.5f) + (i * 23f)).mod(h)
                    val x = baseX + sin((phase * 4.2f) + i * 1.7f) * 12f
                    val r = (7f + (i % 5) * 4f) * scale
                    drawCircle(tint.copy(alpha = 0.18f), r, Offset(x, y))
                    drawCircle(tint.copy(alpha = 0.45f), r, Offset(x, y), style = Stroke(width = 1.4f))
                    drawCircle(Color.White.copy(alpha = 0.35f), r * 0.28f, Offset(x - r * 0.3f, y - r * 0.3f))
                }
                // A slow, heavy veil: stacked damask diamonds barely moving.
                HioneBeatClickerEngine.AmbientStyle.DAMASK_VEIL -> {
                    val y = (baseY + (phase * h * 0.12f) + (i * 29f)).mod(h)
                    val r = 16f * scale
                    drawPath(polygonPath(baseX, y, r, 4, -90f), tint.copy(alpha = 0.16f))
                    drawPath(
                        polygonPath(baseX, y, r * 1.6f, 4, -90f),
                        tint.copy(alpha = 0.22f), style = Stroke(width = 1.2f)
                    )
                }
                // Snow crossing the screen sideways as it falls.
                HioneBeatClickerEngine.AmbientStyle.SNOW_DRIFT -> {
                    val y = (baseY + (phase * h * 0.45f) + (i * 21f)).mod(h)
                    val x = (baseX + phase * w * 0.35f).mod(w)
                    val r = 9f * scale
                    drawPath(
                        starPath(x, y, r, r * 0.42f, 6, -90f),
                        tint.copy(alpha = 0.45f), style = Stroke(width = 1.3f)
                    )
                }
                // A projection grid: scanlines rolling down and faint vertical pylons.
                HioneBeatClickerEngine.AmbientStyle.HOLO_GRID -> {
                    val y = (baseY + phase * h).mod(h)
                    drawLine(tint.copy(alpha = 0.10f), Offset(0f, y), Offset(w, y), strokeWidth = 1.2f)
                    if (i % 4 == 0) drawLine(tint.copy(alpha = 0.07f), Offset(baseX, 0f), Offset(baseX, h), strokeWidth = 1f)
                }
            }
        }
    }
}

private fun DrawScope.drawKawaiiHeart(center: Offset, size: Float, color: Color) {
    val path = Path().apply {
        moveTo(center.x, center.y + size * 0.5f)
        cubicTo(
            center.x - size, center.y - size * 0.3f,
            center.x - size * 0.5f, center.y - size,
            center.x, center.y - size * 0.4f
        )
        cubicTo(
            center.x + size * 0.5f, center.y - size,
            center.x + size, center.y - size * 0.3f,
            center.x, center.y + size * 0.5f
        )
        close()
    }
    drawPath(path, color)
}

@Composable
private fun KawaiiModeTabPill(
    label: String,
    isSelected: Boolean,
    activeColor: Color,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) activeColor.copy(alpha = 0.35f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp)
    ) {
        Text(
            text = label,
            color = if (isSelected) Color.White else KawaiiTextMuted,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Black,
            fontFamily = AudiowideFont
        )
    }
}

@Composable
private fun KawaiiStatBadge(label: String, value: String, accent: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x33000000))
            .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = KawaiiTextMuted, fontSize = 9.5.sp, fontFamily = AudiowideFont)
            Text(value, color = accent, fontSize = 14.5.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
        }
    }
}


/**
 * Mode 0 hero: live BPM orb, now-playing card and millisecond timing-deviation gauge.
 *
 * Extracted from [HioneBpmObservatoryModal] because that composable compiled to ~25.8k dex
 * instructions — over ART's 16384-instruction JIT ceiling — so it was re-interpreted on every
 * recomposition and pinned the main thread. Keep each piece well under that limit.
 */
@Composable
private fun BpmHeroRhythmMatchCard(
    isPlaying: Boolean,
    hasLiveTempo: Boolean,
    liveBpm: Float,
    beatIntervalMs: Long,
    trackTitle: String?,
    trackArtist: String?,
    activeSkinPrimary: Color,
    timingOffsetMs: Int,
    judgmentColor: Color,
    lastTapTimeMs: Long
) {
    // MODE 0: KAWAII BEAT MATCH HERO (LIVE BPM + PRECISION OFFSET GAUGE + SSS+ GRADE)
    // The windows drawn here are the exact ones the tap handler judges against — same tier,
    // same skill bonus, same tempo clamp — so the gauge can never flatter the scoring.
    val rhythmTier by HioneBeatClickerEngine.rhythmTier.collectAsState()
    val tempoLock by HioneTempoLock.tempo.collectAsState()
    val tempoSource by HioneBpmEngine.source.collectAsState()
    // MIRROR STAGE is a real rule: early reads as late on the bar and in the coaching line.
    val chaos by HioneStagePerformance.chaos.collectAsState()
    val shownOffset = if (chaos.mirrorsBar) -timingOffsetMs else timingOffsetMs
    // The active chaos mutator really does change the windows, so the bar the player is reading
    // and the judgment they get are computed from the same number.
    val windows = HioneRhythmTiming.windowsFor(
        beatPeriodMs = beatIntervalMs,
        leniency = rhythmTier.leniency * HioneStagePerformance.windowScale(),
        bonusMs = HioneBeatClickerEngine.timingWindowBonusMs
    )
    Row(
        Modifier
            .fillMaxWidth()
            .height(124.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: Kawaii BPM Orb Badge
        Box(
            Modifier
                .size(124.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(
                    Brush.radialGradient(
                        listOf(activeSkinPrimary.copy(alpha = 0.35f), Color(0x22140A18))
                    )
                )
                .border(1.5.dp, activeSkinPrimary.copy(alpha = 0.7f), RoundedCornerShape(22.dp)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (isPlaying) "💖" else "⏸️", fontSize = 16.sp)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (isPlaying && hasLiveTempo) String.format(Locale.US, "%.0f", liveBpm) else "--",
                        color = Color.White,
                        fontSize = 35.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont
                    )
                }
                Text(
                    // Both tempo sources hold one value per track: the live lock by hysteresis, the
                    // player's analysis because it is one number per file. Say which one it is.
                    text = when {
                        !isPlaying -> "PAUSED"
                        tempoLock.isLocked && tempoSource == HioneBpmEngine.TempoSource.LIVE_LOCK -> "LOCKED"
                        hasLiveTempo && tempoSource == HioneBpmEngine.TempoSource.PLAYER_ANALYSIS -> "ANALYZED"
                        else -> "LISTENING…"
                    },
                    color = if (isPlaying) activeSkinPrimary else KawaiiTextMuted,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = AudiowideFont
                )
                Text(
                    // The tempo readout is HELD by HioneTempoLock, so this number does not twitch
                    // between neighbouring BPMs while a track plays at one tempo. Before a lock
                    // exists it says so, rather than showing a per-onset estimate as fact.
                    text = when {
                        !isPlaying -> "Nothing playing"
                        isPlaying && hasLiveTempo -> "${liveBpm.toInt()} BPM · ${beatIntervalMs}ms"
                        else -> tempoLock.label
                    },
                    color = KawaiiSoftTeal,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }

        // Right: Live Track Card + Rhythm Timing Deviation Gauge + Arcade Grade
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0x33000000))
                .border(1.5.dp, KawaiiHioneMint.copy(alpha = 0.5f), RoundedCornerShape(22.dp))
                .padding(8.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = when {
                            !isPlaying && trackTitle == null -> "${Mascot.osName} Player"
                            !isPlaying && trackTitle != null -> "$trackTitle (paused)"
                            trackTitle != null -> "$trackTitle"
                            else -> "Unknown track"
                        },
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = when {
                            !isPlaying && trackArtist == null -> "Nothing playing"
                            trackArtist != null -> trackArtist
                            else -> "Another app is playing, no track info"
                        },
                        color = KawaiiHioneMint,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
                // Arcade Performance Grade Badge + the ADAPTIVE DIFFICULTY tier underneath it.
                // The tier is shown, never silent: a game that quietly moves the goalposts feels
                // broken, while one that says "🌱 PRACTICE → 💖 DIVA" makes tightening a reward.
                Column(horizontalAlignment = Alignment.End) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Mascot.color(0x44FF3385))
                            .border(1.dp, KawaiiHotPink, RoundedCornerShape(10.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = HioneBeatClickerEngine.sessionGrade,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = AudiowideFont
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${rhythmTier.badge} ${rhythmTier.label} ±${windows.perfectMs}ms",
                        color = KawaiiGoldenHoney,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Visual Rhythm Timing Calibration Gauge + live combo ladder
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("EARLY ◀", color = KawaiiPeach, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                    Text(
                        // The deadzone already flattened this to 0 if the tap was within about a
                        // frame of the beat, so "ON BEAT" here means genuinely unmeasurable-off,
                        // not "close enough".
                        if (lastTapTimeMs > 0) HioneRhythmTiming.nudgeHint(shownOffset, windows) else "TAP ON BEAT",
                        color = judgmentColor,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont
                    )
                    Text("▶ LATE", color = KawaiiSoftTeal, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(3.dp))
                // The highway moved DOWN to the beat node (see the node's call site): notes have to
                // arrive under the thumb that taps them, not in a strip the eye has to leave.
                // Horizontal Target Bar with Deviation Pointer
                KawaiiTimingDeviationBar(offsetMs = shownOffset, windows = windows)
                Spacer(Modifier.height(2.dp))
                BpmComboLadderLine()
                Spacer(Modifier.height(4.dp))
                // The show: the crowd you can actually lose, Hione reacting, and whatever chaos is
                // running. Its own file, because this composable is already near the JIT ceiling.
                HioneStageStrip()
            }
        }
    }
}

/** 0..6: how many rungs of the combo ladder [combo] has climbed. Drives the highway's rails. */
private fun comboRungOf(combo: Int): Int = when {
    combo >= 128 -> 6
    combo >= 64 -> 5
    combo >= 32 -> 4
    combo >= 16 -> 3
    combo >= 8 -> 2
    combo >= 4 -> 1
    else -> 0
}

/**
 * The combo ladder in one line: where you are, what it is worth, and what the next rung costs.
 *
 * Risk/reward needs to be READABLE. "x17 combo" alone says nothing; "x17 · 3.0x yield · 15 more
 * → x4" turns the next fifteen taps into a decision the player can choose to make. The shields
 * on the right are the mercy rule made visible, so a break feels survivable rather than random.
 *
 * Kept as its own composable: HioneBpmObservatoryModal was already split because a Compose
 * function over ART's 16384-instruction JIT ceiling runs interpreted forever.
 */
@Composable
private fun BpmComboLadderLine() {
    val combo by HioneBeatClickerEngine.combo.collectAsState()
    val shields by HioneBeatClickerEngine.comboShields.collectAsState()
    val mult = HioneBeatClickerEngine.effectiveComboMultiplier(combo)
    val next = HioneBeatClickerEngine.nextComboRung(combo)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "x$combo · ${String.format(Locale.US, "%.1f", mult)}× YIELD",
            color = if (combo >= 16) KawaiiGoldenHoney else KawaiiTextMuted,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Black
        )
        Text(
            if (next != null) "${next.first} more → ${String.format(Locale.US, "%.1f", next.second)}×" else "TOP RUNG",
            color = KawaiiSakuraPink,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            if (shields > 0) "🛡️".repeat(shields) else "no shield",
            color = if (shields > 0) KawaiiHioneMint else KawaiiTextMuted,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * Today's Setlist — three daily goals, the session's reason to start and to come back.
 *
 * Every counter is judged taps only, and the accuracy goal reads "—" until there are enough
 * judged taps to state a rate honestly.
 */
@Composable
private fun BpmDailySetlistRow() {
    val daily by HioneBeatClickerEngine.dailySetlist.collectAsState()
    Row(
        Modifier.fillMaxWidth().padding(top = 3.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        BpmSetlistChip(
            "TAPS",
            "${daily.judgedTaps}/${HioneBeatClickerEngine.DailySetlist.GOAL_TAPS}",
            daily.tapsDone
        )
        BpmSetlistChip(
            "STREAK",
            "${daily.bestCombo}/${HioneBeatClickerEngine.DailySetlist.GOAL_COMBO}",
            daily.comboDone
        )
        BpmSetlistChip(
            "≥GREAT",
            if (daily.accuracyPct >= 0f) "${daily.accuracyPct.toInt()}%/${HioneBeatClickerEngine.DailySetlist.GOAL_ACCURACY_PCT.toInt()}%" else "—",
            daily.accuracyDone
        )
    }
}

/**
 * Tap-tempo vs detected-tempo agreement, stated honestly.
 *
 * BPM comparison must tolerate a few BPM (the detector is an estimator, not ground truth) and
 * must treat half/double-time as a match: a 170 BPM track tapped at 85 is CORRECT. With nothing
 * detected there is nothing to compare, and it says exactly that rather than implying a verdict.
 */
@Composable
private fun BpmTempoMatchLine(hasLiveTempo: Boolean, liveBpm: Float, tappedBpm: Float?) {
    if (tappedBpm == null) return
    val text: String
    val color: Color
    if (!hasLiveTempo) {
        text = "no detected tempo to compare against"
        color = KawaiiTextMuted
    } else {
        val m = HioneRhythmTiming.octaveMultiplier(tappedBpm, liveBpm)
        if (m != null) {
            text = "✓ matches detected ${liveBpm.toInt()} BPM (${HioneRhythmTiming.octaveLabel(m)}, ±${HioneRhythmTiming.bpmTolerance(liveBpm).toInt()} BPM)"
            color = KawaiiHioneMint
        } else {
            text = "✕ ${tappedBpm.toInt()} vs detected ${liveBpm.toInt()} BPM. Try /2 or x2"
            color = KawaiiPeach
        }
    }
    Text(
        text,
        color = color,
        fontSize = 9.5.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 2.dp)
    )
}

/**
 * A live thumbnail of what a skin actually looks like: its node silhouette, its ambient
 * treatment and its burst glyphs, drawn with its own palette. A colour swatch cannot show that
 * Cyber Mirai is a hexagon in data rain and Snow Crystal is a snowflake in drifting snow — and
 * "you can't tell the skins apart" is exactly the complaint this answers.
 */
@Composable
private fun BpmSkinPreview(skin: HioneBeatClickerEngine.BpmSkin, dimmed: Boolean) {
    val alpha = if (dimmed) 0.35f else 1f
    Box(
        Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Brush.verticalGradient(listOf(Color(skin.sheetTop), Color(skin.sheetBottom))))
            .border(1.dp, Color(skin.primaryColor).copy(alpha = 0.5f * alpha), RoundedCornerShape(10.dp))
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val r = size.minDimension * 0.26f
            val primary = Color(skin.primaryColor).copy(alpha = alpha)
            val accent = Color(skin.accentColor).copy(alpha = alpha)

            // Ambient signature, three marks in the skin's own idiom.
            when (skin.ambient) {
                HioneBeatClickerEngine.AmbientStyle.PETALS ->
                    for (i in 0 until 3) drawKawaiiHeart(
                        Offset(size.width * (0.2f + i * 0.3f), size.height * (0.16f + (i % 2) * 0.62f)),
                        5f, accent.copy(alpha = 0.55f * alpha)
                    )
                HioneBeatClickerEngine.AmbientStyle.HEX_RAIN ->
                    for (i in 0 until 4) drawLine(
                        accent.copy(alpha = 0.5f * alpha),
                        Offset(size.width * (0.12f + i * 0.25f), 2f),
                        Offset(size.width * (0.12f + i * 0.25f), size.height * (0.22f + (i % 3) * 0.16f)),
                        strokeWidth = 2f, cap = StrokeCap.Round
                    )
                HioneBeatClickerEngine.AmbientStyle.HONEY_BUBBLES ->
                    for (i in 0 until 3) drawCircle(
                        accent.copy(alpha = 0.45f * alpha),
                        4f + i * 2f,
                        Offset(size.width * (0.18f + i * 0.32f), size.height * (0.8f - i * 0.12f)),
                        style = Stroke(width = 1.2f)
                    )
                HioneBeatClickerEngine.AmbientStyle.DAMASK_VEIL ->
                    for (i in 0 until 3) drawPath(
                        polygonPath(size.width * (0.2f + i * 0.3f), size.height * (0.2f + (i % 2) * 0.6f), 6f, 4, -90f),
                        accent.copy(alpha = 0.35f * alpha)
                    )
                HioneBeatClickerEngine.AmbientStyle.SNOW_DRIFT ->
                    for (i in 0 until 3) drawPath(
                        starPath(size.width * (0.18f + i * 0.32f), size.height * (0.18f + (i % 2) * 0.64f), 5f, 2f, 6, -90f),
                        accent.copy(alpha = 0.6f * alpha), style = Stroke(width = 1f)
                    )
                HioneBeatClickerEngine.AmbientStyle.HOLO_GRID ->
                    for (i in 0 until 4) drawLine(
                        accent.copy(alpha = 0.45f * alpha),
                        Offset(2f, size.height * (0.15f + i * 0.22f)), Offset(size.width - 2f, size.height * (0.15f + i * 0.22f)),
                        strokeWidth = 1f
                    )
            }

            // The node silhouette itself — the headline difference.
            drawPath(skinOutline(skin.nodeShape, c.x, c.y, r), primary.copy(alpha = 0.55f * alpha))
            drawPath(
                skinOutline(skin.nodeShape, c.x, c.y, r * 1.5f),
                Color.White.copy(alpha = 0.5f * alpha), style = Stroke(width = 1.4f)
            )
        }
        Text(
            skin.burstGlyphs.take(2).joinToString(""),
            fontSize = 9.sp,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(2.dp)
                .graphicsLayer { this.alpha = alpha }
        )
    }
}

/**
 * "NEXT: <reward> · <progress> → <where>" — the single most important progression line in the
 * game, because it is the only one that says why the next hundred taps are worth doing.
 */
@Composable
private fun BpmNextUnlockLine() {
    val ctx = LocalContext.current
    // Recomputed whenever the lifetime stats move, i.e. on every judged tap.
    val lifetime by HioneBpmSeasonsEngine.lifetime.collectAsState()
    val next = remember(lifetime) { HioneUnlocks.nextLocked(ctx) }
    if (next == null) {
        Text(
            "Every reward earned. All ${HioneUnlocks.ALL.size} unlocked",
            color = KawaiiGoldenHoney,
            fontSize = 10.sp,
            fontWeight = FontWeight.Black,
            maxLines = 1
        )
        return
    }
    Column(Modifier.fillMaxWidth()) {
        Text(
            "NEXT: ${next.icon} ${next.title} → ${next.where}",
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        val frac = HioneUnlocks.progressFraction(next)
        Box(
            Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(0x33FFFFFF))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(frac)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(Brush.horizontalGradient(listOf(KawaiiHioneMint, KawaiiGoldenHoney)))
            )
        }
        Text(
            HioneUnlocks.progressLabel(next),
            color = KawaiiGoldenHoney,
            fontSize = 9.sp,
            fontWeight = FontWeight.Black,
            maxLines = 1
        )
    }
}

/**
 * One cross-app reward, locked or earned, with its destination spelled out.
 */
@Composable
private fun BpmRewardCard(reward: HioneUnlocks.Reward, earned: Boolean) {
    Box(
        Modifier
            .width(168.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0x33000000))
            .border(1.dp, if (earned) KawaiiGoldenHoney else Color(0x33FFFFFF), RoundedCornerShape(14.dp))
            .padding(6.dp)
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "${reward.icon} ${reward.title}",
                    color = if (earned) Color.White else KawaiiTextMuted,
                    fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Text(if (earned) "✓" else "○", fontSize = 11.sp)
            }
            // Where it lands, verbatim — a reward you cannot find is not a reward.
            Text(
                reward.where,
                color = KawaiiSoftTeal,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (earned) {
                Text("EARNED", color = KawaiiGoldenHoney, fontSize = 9.5.sp, fontWeight = FontWeight.Black)
            } else {
                Text(
                    HioneUnlocks.progressLabel(reward),
                    color = KawaiiGoldenHoney,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun BpmSetlistChip(label: String, value: String, done: Boolean) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (done) Color(0x33FFD166) else Color(0x22FFFFFF))
            .border(1.dp, if (done) KawaiiGoldenHoney else Color(0x33FFFFFF), RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            "${if (done) "✓" else "○"} $label $value",
            color = if (done) KawaiiGoldenHoney else KawaiiTextMuted,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * Latency calibration strip.
 *
 * The whole chain (mixer buffer + DAC ahead of the Visualizer capture, touch sampling on the way
 * back) adds a CONSTANT delay, which would otherwise make every honest tap read "late" forever.
 * The CALIBRATE chip applies the median of the player's own recent judged deviations — real
 * measured data, not a guess — and only appears once there are enough samples for a median to
 * mean something. The platform's latency figure is printed as information and never applied
 * silently, because on this device it is a lower bound (one mixer buffer), not the real value.
 */
@Composable
private fun BpmTimingCalibrationRow() {
    val ctx = LocalContext.current
    val offset by HioneRhythmCalibration.offsetMs.collectAsState()
    val suggestion by HioneRhythmCalibration.suggestionMs.collectAsState()
    val samples by HioneRhythmCalibration.sampleCount.collectAsState()
    val note by HioneRhythmCalibration.latencyNote.collectAsState()

    Row(
        Modifier.fillMaxWidth().padding(top = 3.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "OFFSET ${if (offset >= 0) "+" else ""}${offset}ms" + if (note.isNotEmpty()) " · $note" else "",
            color = KawaiiTextMuted,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        Spacer(Modifier.width(5.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Mascot.color(0x22FFAB2E))
                .border(1.dp, KawaiiHioneMint, RoundedCornerShape(8.dp))
                .clickable {
                    HioneRhythmCalibration.nudge(-5)
                    com.hione.launcher.haptics.HioneHaptics.tick(ctx)
                }
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) { Text("−5", color = KawaiiHioneMint, fontSize = 10.sp, fontWeight = FontWeight.Black) }
        Spacer(Modifier.width(3.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Mascot.color(0x22FFAB2E))
                .border(1.dp, KawaiiHioneMint, RoundedCornerShape(8.dp))
                .clickable {
                    HioneRhythmCalibration.nudge(5)
                    com.hione.launcher.haptics.HioneHaptics.tick(ctx)
                }
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) { Text("+5", color = KawaiiHioneMint, fontSize = 10.sp, fontWeight = FontWeight.Black) }
        if (suggestion != null) {
            Spacer(Modifier.width(5.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Mascot.color(0x55FF3385))
                    .border(1.dp, KawaiiHotPink, RoundedCornerShape(8.dp))
                    .clickable {
                        val applied = HioneRhythmCalibration.applySuggestion()
                        com.hione.launcher.haptics.HioneHaptics.confirm(ctx)
                        if (applied != null) {
                            android.widget.Toast.makeText(
                                ctx,
                                "Timing offset calibrated to ${if (applied >= 0) "+" else ""}${applied}ms from your last $samples judged taps",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Text(
                    "CALIBRATE ${if ((suggestion ?: 0) >= 0) "+" else ""}${suggestion}ms",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = AudiowideFont
                )
            }
        }
    }
}

/**
 * Mode 1 hero: sweet leek harvest totals, per-second yield and combo tier.
 *
 * Extracted from [HioneBpmObservatoryModal] because that composable compiled to ~25.8k dex
 * instructions — over ART's 16384-instruction JIT ceiling — so it was re-interpreted on every
 * recomposition and pinned the main thread. Keep each piece well under that limit.
 */
@Composable
private fun BpmHeroHarvestCard(
    liveBpm: Float,
    leekCount: Double,
    clickerCombo: Int,
    feverSeconds: Int
) {
    // MODE 1: HARVEST CLICKER HERO
    Column(
        Modifier
            .fillMaxWidth()
            .height(124.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0x33000000))
            .border(1.5.dp, if (feverSeconds > 0) KawaiiGoldenHoney else KawaiiSakuraPink, RoundedCornerShape(22.dp))
            .padding(9.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(Mascot.lore("LEEK HARVEST", "NOTE HARVEST"), color = KawaiiHioneMint, fontSize = 11.5.sp, fontWeight = FontWeight.Black)
                Text(
                    HioneBeatClickerEngine.formatNumber(leekCount),
                    color = if (feverSeconds > 0) KawaiiGoldenHoney else Color.White,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = AudiowideFont
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                val bps = HioneBeatClickerEngine.getEffectiveBps(liveBpm)
                Text(
                    "+${HioneBeatClickerEngine.formatNumber(bps)}/s",
                    color = KawaiiSoftTeal,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = AudiowideFont
                )
                Text(
                    if (feverSeconds > 0) "FEVER 77x (${feverSeconds}s)" else "BPM boost on",
                    color = if (feverSeconds > 0) KawaiiGoldenHoney else KawaiiSakuraPink,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (clickerCombo > 0) {
                val comboTier = when {
                    clickerCombo >= 50 -> "MAX COMBO"
                    clickerCombo >= 25 -> "BIG COMBO"
                    clickerCombo >= 10 -> "GOOD COMBO"
                    else -> "COMBO"
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(KawaiiHotPink.copy(alpha = 0.35f))
                        .border(1.dp, KawaiiSakuraPink, RoundedCornerShape(10.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text("$comboTier x$clickerCombo", color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.Black)
                }
            }
            Text(
                Mascot.lore("Total earned: ${HioneBeatClickerEngine.formatNumber(HioneBeatClickerEngine.totalEarned.collectAsState().value)} leeks", "Total earned: ${HioneBeatClickerEngine.formatNumber(HioneBeatClickerEngine.totalEarned.collectAsState().value)} notes"),
                color = KawaiiTextMuted,
                fontSize = 11.sp
            )
        }
    }
}

/**
 * Mode 2 hero: producer skill-tree summary card.
 *
 * Extracted from [HioneBpmObservatoryModal] because that composable compiled to ~25.8k dex
 * instructions — over ART's 16384-instruction JIT ceiling — so it was re-interpreted on every
 * recomposition and pinned the main thread. Keep each piece well under that limit.
 */
@Composable
private fun BpmHeroSkillsCard(
    clickerSkills: List<HioneBeatClickerEngine.SkillUpgrade>
) {
    // MODE 2: PRODUCER SKILLS TREE HERO
    Column(
        Modifier
            .fillMaxWidth()
            .height(124.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0x33000000))
            .border(1.5.dp, KawaiiSoftTeal, RoundedCornerShape(22.dp))
            .padding(9.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("SKILLS", color = KawaiiSoftTeal, fontSize = 11.5.sp, fontWeight = FontWeight.Black)
                Text("Upgrades", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
            }
            Text("${clickerSkills.sumOf { it.level }} Upgrades Active", color = KawaiiGoldenHoney, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            Mascot.lore("Upgrades widen the timing window, pull in golden leeks, make fever last longer, and add an auto-tapper.", "Upgrades widen the timing window, pull in golden notes, make fever last longer, and add an auto-tapper."),
            color = KawaiiTextMuted,
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Mode 3 hero: active aesthetic skin card.
 *
 * Extracted from [HioneBpmObservatoryModal] because that composable compiled to ~25.8k dex
 * instructions — over ART's 16384-instruction JIT ceiling — so it was re-interpreted on every
 * recomposition and pinned the main thread. Keep each piece well under that limit.
 */
@Composable
private fun BpmHeroSkinsCard(
    currentSkin: HioneBeatClickerEngine.BpmSkin,
    activeSkinPrimary: Color
) {
    // MODE 3: THEME SKINS & TEXTURES HERO
    Column(
        Modifier
            .fillMaxWidth()
            .height(124.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0x33000000))
            .border(1.5.dp, activeSkinPrimary, RoundedCornerShape(22.dp))
            .padding(9.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("SKINS", color = activeSkinPrimary, fontSize = 11.5.sp, fontWeight = FontWeight.Black)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(currentSkin.icon, fontSize = 20.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(currentSkin.displayName, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
                }
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(activeSkinPrimary.copy(alpha = 0.3f))
                    .border(1.dp, activeSkinPrimary, RoundedCornerShape(8.dp))
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            ) {
                Text("ACTIVE SKIN", color = Color.White, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text("Each skin changes the colors, particles and effects.", color = KawaiiTextMuted, fontSize = 11.sp)
    }
}

/**
 * Mode 4 hero: quest progress plus calibration-database stat badges.
 *
 * Extracted from [HioneBpmObservatoryModal] because that composable compiled to ~25.8k dex
 * instructions — over ART's 16384-instruction JIT ceiling — so it was re-interpreted on every
 * recomposition and pinned the main thread. Keep each piece well under that limit.
 */
@Composable
private fun BpmHeroQuestsCard(
    achievements: List<HioneBeatClickerEngine.Achievement>,
    dbStats: Map<String, Any>
) {
    // MODE 4: QUESTS & CALIBRATION DATABASE HERO
    Column(
        Modifier
            .fillMaxWidth()
            .height(124.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0x33000000))
            .border(1.5.dp, Color(0xFFFFD700), RoundedCornerShape(22.dp))
            .padding(9.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("QUESTS AND CALIBRATION", color = Color(0xFFFFD700), fontSize = 11.5.sp, fontWeight = FontWeight.Black)
                Text("Calibration Stats", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
            }
            Text("${achievements.count { it.isUnlocked }} of ${achievements.size} done", color = KawaiiHioneMint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        // What the player is working toward RIGHT NOW, with the exact requirement. Progression
        // that cannot be seen coming does not motivate anyone.
        BpmNextUnlockLine()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val totalCal = dbStats["totalTracks"] ?: 0
            val userCal = dbStats["userCalibrated"] ?: 0
            val totalTaps = dbStats["totalTaps"] ?: 0
            // -1 (or dbStats not loaded yet) = no taps logged → "—", not a fake 100 %.
            val accPct = (dbStats["perfectAccuracyPct"] as? Int) ?: -1
            KawaiiStatBadge("DB TRACKS", "$totalCal", KawaiiSoftTeal)
            KawaiiStatBadge("USER CAL", "$userCal", KawaiiSakuraPink)
            KawaiiStatBadge("LOGGED TAPS", "$totalTaps", KawaiiGoldenHoney)
            // Labelled PERFECT %, not "ACCURACY": the query behind it counts only accuracy='PERFECT'
            // rows, and with five graded tiers a GREAT is not a failure — calling the perfect rate
            // "accuracy" would understate a good player exactly as badly as it once overstated one.
            KawaiiStatBadge("PERFECT %", if (accPct >= 0) "$accPct%" else "—", KawaiiHioneMint)
        }
    }
}

/**
 * Mode 5 hero: season rank, season score and lifetime stat badges.
 *
 * Extracted from [HioneBpmObservatoryModal] because that composable compiled to ~25.8k dex
 * instructions — over ART's 16384-instruction JIT ceiling — so it was re-interpreted on every
 * recomposition and pinned the main thread. Keep each piece well under that limit.
 */
@Composable
private fun BpmHeroSeasonsCard(
    currentSeason: HioneBpmSeasonsEngine.SeasonStats,
    lifetimeStats: HioneBpmSeasonsEngine.LifetimeStats
) {
    // MODE 5: SEASONS & RANKS HERO
    Column(
        Modifier
            .fillMaxWidth()
            .height(124.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0x33000000))
            .border(1.5.dp, Color(currentSeason.rank.colorHex), RoundedCornerShape(22.dp))
            .padding(9.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(currentSeason.seasonName, color = KawaiiSoftTeal, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(currentSeason.rank.badge, fontSize = 26.sp)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        currentSeason.rank.title,
                        color = Color(currentSeason.rank.colorHex),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("SEASON SCORE", color = KawaiiTextMuted, fontSize = 10.sp)
                Text(
                    HioneBeatClickerEngine.formatNumber(currentSeason.seasonScore.toDouble()),
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = AudiowideFont
                )
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            KawaiiStatBadge("TOTAL TAPS", "${lifetimeStats.totalTaps}", KawaiiHioneMint)
            KawaiiStatBadge("MAX STREAK", "x${lifetimeStats.maxCombo}", KawaiiGoldenHoney)
            KawaiiStatBadge("PERFECTS", "${lifetimeStats.perfectHits}", KawaiiSakuraPink)
            KawaiiStatBadge("HIGH BPM", "${lifetimeStats.highestBpmLocked.toInt()}", KawaiiLavender)
        }
    }
}

/**
 * Center stage: golden-leek target, the Project DIVA beat node with its full tap-judgment
 * pipeline, floating numbers and the floating judgment title.
 *
 * The mutable tap/judgment state is passed in as the parent's own MutableState holders and
 * re-delegated below, so writes and the reads that follow them inside the single onTap
 * callback still observe the new value exactly as they did when this code lived inline.
 *
 * Extracted from [HioneBpmObservatoryModal] because that composable compiled to ~25.8k dex
 * instructions — over ART's 16384-instruction JIT ceiling — so it was re-interpreted on every
 * recomposition and pinned the main thread. Keep each piece well under that limit.
 */
@Composable
private fun ColumnScope.BpmBeatNodeStage(
    approachRadius: Float,
    pulseScale: Float,
    isPlaying: Boolean,
    hasLiveTempo: Boolean,
    liveBpm: Float,
    beatIntervalMs: Long,
    lastBeatEpochMs: Long,
    goldenLeekVisible: Boolean,
    skin: HioneBeatClickerEngine.BpmSkin,
    feverSeconds: Int,
    clickerCombo: Int,
    floatingTexts: List<HioneBeatClickerEngine.FloatingText>,
    trackArtist: String?,
    trackTitle: String?,
    bpmDb: HioneBpmDatabase,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    tapTimestamps: MutableList<Long>,
    tapCounterState: MutableIntState,
    calculatedTapBpmState: MutableState<Float?>,
    timingOffsetMsState: MutableIntState,
    judgmentTitleState: MutableState<String>,
    judgmentColorState: MutableState<Color>,
    lastTapTimeMsState: MutableLongState,
    perfectShockwaveTriggerState: MutableIntState,
    dbStatsState: MutableState<Map<String, Any>>
) {
    val ctx = LocalContext.current
    var tapCounter by tapCounterState
    var calculatedTapBpm by calculatedTapBpmState
    var timingOffsetMs by timingOffsetMsState
    var judgmentTitle by judgmentTitleState
    var judgmentColor by judgmentColorState
    var lastTapTimeMs by lastTapTimeMsState
    var perfectShockwaveTrigger by perfectShockwaveTriggerState
    var dbStats by dbStatsState
    // Tier of the last judged tap, so the highway's hit spark can wear the tier's colour.
    var lastAccuracy by remember { mutableStateOf<HitAccuracy?>(null) }
    val chaosNow by HioneStagePerformance.chaos.collectAsState()

    val userLevel by HioneBeatClickerEngine.userLevel.collectAsState()
    val userXp by HioneBeatClickerEngine.userXp.collectAsState()
    val activeLanes by HioneBeatClickerEngine.activeLanes.collectAsState()
    val maxLanes = HioneBeatClickerEngine.maxUnlockedLanes(userLevel)
    val xpNeeded = HioneBeatClickerEngine.xpRequiredForLevel(userLevel)
    var lastTappedLane by remember { mutableIntStateOf(0) }

    val performTap: (Int) -> Unit = { lane ->
        val now = SystemClock.elapsedRealtime()
        lastTapTimeMs = now
        lastTappedLane = lane

        // Tap tempo tracking (ignore <90ms simultaneous 2-thumb chord delta)
        val prevTap = tapTimestamps.lastOrNull() ?: 0L
        if (now - prevTap >= 90L) {
            if (tapTimestamps.isNotEmpty() && now - prevTap > 2000L) {
                tapTimestamps.clear()
                tapCounter = 0
            }
            tapTimestamps.add(now)
            tapCounter++
            if (tapTimestamps.size > 8) tapTimestamps.removeAt(0)
            if (tapTimestamps.size >= 2) {
                val intervals = (1 until tapTimestamps.size).map { (tapTimestamps[it] - tapTimestamps[it - 1]).toDouble() }
                val avg = intervals.average()
                if (avg > 0) calculatedTapBpm = (60_000.0 / avg).toFloat().coerceIn(20f, 999f)
            }
        }

        val tier = HioneBeatClickerEngine.rhythmTier.value
        val windows = HioneRhythmTiming.windowsFor(
            beatPeriodMs = beatIntervalMs,
            leniency = tier.leniency * HioneStagePerformance.windowScale(),
            bonusMs = HioneBeatClickerEngine.timingWindowBonusMs
        )
        val tapWallMs = System.currentTimeMillis()
        val calMs = HioneRhythmCalibration.offsetMs.value
        val targetEpochMs = tapWallMs - calMs
        HioneHighwayGrid.syncAnchor(HioneBpmEngine.state.value.lastPulseEpochMs, beatIntervalMs, tapWallMs)
        val absBeatIdx = HioneHighwayGrid.nearestBeatIndex(targetEpochMs, beatIntervalMs)
        val nearestBeatEpoch = HioneHighwayGrid.beatEpochAt(absBeatIdx, beatIntervalMs)
        val signedOffset: Int? = if (isPlaying && beatIntervalMs in 60L..3000L) {
            (targetEpochMs - nearestBeatEpoch).toInt()
        } else null

        val rawAccuracy: HitAccuracy? = signedOffset?.let { HioneRhythmTiming.judge(it, windows) }
        val reqLanes = if (activeLanes > 1) noteLanesForBeat(absBeatIdx.toInt(), activeLanes) else listOf(0)
        val wrongFret = activeLanes > 1 && rawAccuracy != null && rawAccuracy != HitAccuracy.MISS && lane !in reqLanes
        val accuracy: HitAccuracy? = if (wrongFret) HitAccuracy.MISS else rawAccuracy
        var powerChordCompleted = false
        if (accuracy != null && accuracy != HitAccuracy.MISS && !wrongFret) {
            val wasAllHit = HioneHighwayGrid.areAllLanesHit(absBeatIdx, reqLanes)
            HioneHighwayGrid.markLaneHit(absBeatIdx, lane)
            if (reqLanes.size > 1 && !wasAllHit && HioneHighwayGrid.areAllLanesHit(absBeatIdx, reqLanes)) {
                powerChordCompleted = true
            }
        }

        timingOffsetMs = if (signedOffset != null) HioneRhythmTiming.displayOffsetMs(signedOffset, windows) else 0

        com.hione.launcher.haptics.HioneHaptics.beat(ctx, accuracy?.hapticStrength ?: 0)
        lastAccuracy = accuracy

        if (accuracy == null || signedOffset == null) {
            HioneBeatClickerEngine.freeTap()
            judgmentTitle = if (isPlaying) "FREE TAP · ${HioneTempoLock.tempo.value.label}" else "FREE TAP · nothing playing"
            judgmentColor = KawaiiSoftTeal
            coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                HioneBeatClickerEngine.checkAchievements()
            }
        } else {
            val comboBefore = clickerCombo
            if (!wrongFret) {
                HioneRhythmCalibration.recordJudgedDeviation(signedOffset)
            }
            val chordMult = if (powerChordCompleted) 2.0f else 1.0f
            HioneBeatClickerEngine.tap(accuracy, liveBpm, tier.scoreScale * chordMult)
            if (powerChordCompleted) {
                HioneGameFx.pop("⚡ POWER CHORD! +2x", 0xFF00FFCC, big = true)
                com.hione.launcher.haptics.HioneHaptics.confirm(ctx)
            }
            val comboAfter = HioneBeatClickerEngine.combo.value
            if (HioneBeatClickerEngine.comboMultiplier(comboAfter) >
                HioneBeatClickerEngine.comboMultiplier(comboBefore)) {
                com.hione.launcher.haptics.HioneHaptics.confirm(ctx)
            }
            val pts = (((accuracy.seasonPoints * 5L +
                comboBefore * (if (accuracy.isGreatOrBetter) 15L else 5L)) * tier.scoreScale) * chordMult).toLong()
            HioneBpmSeasonsEngine.recordTap(
                accuracy, comboBefore, pts,
                if (hasLiveTempo) liveBpm else 0f
            )
            if (HioneSongRecords.onJudgedTap(accuracy, pts, HioneBeatClickerEngine.combo.value)) {
                HioneGameFx.pop("NEW BEST!", 0xFFFFD166, big = true)
                HioneGameFx.shake(0.7f)
                HioneStagePerformance.onNewBest()
                HioneSeasonalAudioEngine.playLevelUpFanfare()
            }
            val judgedBeatAt = System.currentTimeMillis() - HioneRhythmCalibration.offsetMs.value - signedOffset
            if (accuracy.isGreatOrBetter && HioneRhythmTiming.isLuckyBeat(judgedBeatAt, beatIntervalMs)) {
                HioneBeatClickerEngine.luckyNote()
                HioneStagePerformance.onLuckyNote()
            }

            coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                bpmDb.logTapTelemetry(
                    HioneBpmDatabase.TapTelemetryRecord(
                        artist = trackArtist ?: "",
                        title = trackTitle ?: "",
                        tapEpochMs = System.currentTimeMillis(),
                        targetBeatMs = beatIntervalMs,
                        deviationMs = signedOffset,
                        accuracy = accuracy.name,
                        instantaneousBpm = if (hasLiveTempo) liveBpm else 0f,
                        comboAtTap = comboBefore,
                        isFever = feverSeconds > 0
                    )
                )
                HioneBeatClickerEngine.checkAchievements()
                HioneUnlocks.evaluate(ctx)
                HioneSecrets.onJudgedTap(ctx, accuracy, if (hasLiveTempo) liveBpm else 0f, trackArtist, trackTitle)
                dbStats = bpmDb.getCalibrationStats()
            }

            val nearMs = if (accuracy == HitAccuracy.GREAT) HioneRhythmTiming.nearPerfectMissMs(signedOffset, windows) else null
            if (nearMs != null) HioneStagePerformance.onNearPerfect()
            val coach = HioneRhythmTiming.nudgeHint(if (chaosNow.mirrorsBar) -timingOffsetMs else timingOffsetMs, windows)
            val fretTargetLabel = reqLanes.joinToString("+") { "L${it + 1}" }
            judgmentTitle = when {
                wrongFret -> "WRONG FRET · HIT $fretTargetLabel"
                powerChordCompleted -> "⚡ POWER CHORD · ${accuracy.label}!"
                nearMs != null -> "SO CLOSE · ${nearMs}ms off PERFECT"
                else -> "${accuracy.label} · $coach"
            }
            judgmentColor = when (accuracy) {
                HitAccuracy.PERFECT -> KawaiiHotPink
                HitAccuracy.GREAT -> KawaiiSakuraPink
                HitAccuracy.GOOD -> KawaiiSoftTeal
                HitAccuracy.OK -> KawaiiGoldenHoney
                HitAccuracy.MISS -> Color(0xFFFF6B8B)
            }
            if (accuracy == HitAccuracy.PERFECT) perfectShockwaveTrigger++
        }
    }

    // Level Header & Multi-Lane Selector
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Brush.horizontalGradient(listOf(Color(0xFFFF3385), Color(0xFFFFAB2E))))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text("Lv. $userLevel", color = Color.White, fontWeight = FontWeight.Black, fontSize = 12.sp)
            }
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    "XP $userXp / $xpNeeded",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Box(
                    Modifier
                        .width(90.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0x33FFFFFF))
                ) {
                    val progressFraction = (userXp.toFloat() / xpNeeded.coerceAtLeast(1L)).coerceIn(0f, 1f)
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progressFraction)
                            .background(Color(0xFFFFD166))
                    )
                }
            }
        }

        // Multi-lane selector chips (1x up to maxUnlockedLanes)
        if (maxLanes > 1) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val availableLanes = listOf(1, 2, 3, 4, 5, 6, 8, 10).filter { it <= maxLanes }
                for (l in availableLanes) {
                    val isSel = activeLanes == l
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isSel) Color(0xFFFF3385) else Color(0x33FFFFFF))
                            .clickable { HioneBeatClickerEngine.setActiveLanes(l) }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "${l}x",
                            color = if (isSel) Color.White else Color.White.copy(alpha = 0.7f),
                            fontSize = 10.sp,
                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .weight(1f, fill = false)
            .padding(vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        // Golden Leek lucky target
        if (goldenLeekVisible) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 12.dp)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(KawaiiGoldenHoney, Color(0xFFFFA500))))
                    .border(2.dp, Color.White, CircleShape)
                    .clickable {
                        HioneBeatClickerEngine.tapGoldenLeek()
                        com.hione.launcher.haptics.HioneHaptics.like(ctx)
                    }
                    .padding(8.dp)
            ) {
                if (Mascot.hasHioneLore) Text("✨🥬✨", fontSize = 20.sp)
                else com.hione.launcher.CodaMarkGlyph(28.dp, ring = Color.White, pip = Mascot.primaryDeep)
            }
        }

        val npAccents by com.hione.launcher.ui.HioneNowPlayingAccent.colors.collectAsState()
        val laneAccent = if (npAccents.first != 0) Color(npAccents.first) else Color(skin.primaryColor)
        val laneAccent2 = if (npAccents.second != 0) Color(npAccents.second) else Color(skin.accentColor)

        HioneNoteHighway(
            lastPulseEpochMs = HioneBpmEngine.state.value.lastPulseEpochMs,
            beatIntervalMs = beatIntervalMs,
            calibrationMs = HioneRhythmCalibration.offsetMs.value,
            windows = HioneRhythmTiming.windowsFor(
                beatPeriodMs = beatIntervalMs,
                leniency = HioneBeatClickerEngine.rhythmTier.value.leniency * HioneStagePerformance.windowScale(),
                bonusMs = HioneBeatClickerEngine.timingWindowBonusMs
            ),
            lastTapMs = lastTapTimeMs,
            lastOffsetMs = timingOffsetMsState.intValue,
            accent = laneAccent,
            accent2 = laneAccent2,
            laneHeight = 220.dp,
            hitFraction = 0.82f,
            laneCount = activeLanes,
            lastTappedLane = lastTappedLane,
            isPlaying = isPlaying,
            onTapLane = { lane -> performTap(lane) },
            skin = skin,
            fever = feverSeconds > 0,
            chaos = chaosNow,
            comboRung = comboRungOf(clickerCombo),
            lastAccuracy = lastAccuracy
        ) {
            if (activeLanes == 1) {
                KawaiiProjectDivaBeatNode(
                    approachRadius = approachRadius,
                    pulseScale = pulseScale,
                    isPlaying = isPlaying,
                    feverActive = feverSeconds > 0,
                    perfectShockwaveTrigger = perfectShockwaveTrigger,
                    skin = skin,
                    onTap = { performTap(0) }
                )
            }
        }

        // Floating Numbers / Floating Texts
        floatingTexts.forEach { ft ->
            key(ft.id) {
                val animY = remember { Animatable(0f) }
                val animAlpha = remember { Animatable(1f) }
                LaunchedEffect(ft.id) {
                    launch { animY.animateTo(-40f, tween(600, easing = LinearOutSlowInEasing)) }
                    launch {
                        delay(150L)
                        animAlpha.animateTo(0f, tween(450, easing = LinearEasing))
                    }
                }
                Text(
                    text = ft.text,
                    color = if (ft.isCrit) KawaiiGoldenHoney else (if (ft.isFever) KawaiiHotPink else KawaiiSoftTeal),
                    fontSize = if (ft.isCrit) 16.sp else 13.5.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = AudiowideFont,
                    modifier = Modifier
                        .offset(x = ft.x.dp, y = (ft.y + animY.value).dp)
                        .graphicsLayer { alpha = animAlpha.value }
                )
            }
        }

        // Floating Judgment Title
        if (judgmentTitle.isNotEmpty()) {
            Text(
                // Typography is part of the skin: arcade skins shout in wide-tracked Audiowide,
                // the soft skins speak in the normal face. Same information, different voice.
                text = if (skin.judgmentArcade) judgmentTitle else judgmentTitle.lowercase(Locale.US),
                color = judgmentColor,
                fontSize = if (skin.judgmentArcade) 15.sp else 16.sp,
                fontWeight = FontWeight.Black,
                fontFamily = if (skin.judgmentArcade) AudiowideFont else null,
                letterSpacing = skin.judgmentSpacing.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-14).dp)
            )
        }
    }
}

/**
 * Bottom dock for modes 1..4: buildings shop, skill upgrades, skin selector, quest list.
 *
 * Extracted from [HioneBpmObservatoryModal] because that composable compiled to ~25.8k dex
 * instructions — over ART's 16384-instruction JIT ceiling — so it was re-interpreted on every
 * recomposition and pinned the main thread. Keep each piece well under that limit.
 */
@Composable
private fun BpmGameDockCards(
    selectedMode: Int,
    leekCount: Double,
    clickerBuildings: List<HioneBeatClickerEngine.Building>,
    clickerSkills: List<HioneBeatClickerEngine.SkillUpgrade>,
    currentSkin: HioneBeatClickerEngine.BpmSkin,
    achievements: List<HioneBeatClickerEngine.Achievement>
) {
    val ctx = LocalContext.current
    // Re-read when the tab changes (and on every recomposition of this dock) so a reward earned
    // mid-session shows as EARNED without reopening the modal.
    val secretsFound by HioneSecrets.foundCount.collectAsState()
    val earnedUnlocks = remember(selectedMode, achievements, secretsFound) { HioneUnlocks.unlockedIds(ctx) }

    if (selectedMode == 1) {
        // MODE 1: BUILDINGS SHOP CARDS
        LazyRow(
            Modifier
                .fillMaxWidth()
                .height(72.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(clickerBuildings) { b ->
                val canAfford = leekCount >= b.currentCost
                Box(
                    Modifier
                        .width(145.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0x33000000))
                        .border(1.dp, if (canAfford) KawaiiHioneMint else Color(0x22FFFFFF), RoundedCornerShape(14.dp))
                        .clickable(enabled = canAfford) {
                            HioneBeatClickerEngine.buyBuilding(b.id)
                            com.hione.launcher.haptics.HioneHaptics.tick(ctx)
                        }
                        .padding(6.dp)
                ) {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${b.icon} ${b.name}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text("x${b.owned}", color = KawaiiSakuraPink, fontSize = 12.sp, fontWeight = FontWeight.Black)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                            Text("+${HioneBeatClickerEngine.formatNumber(b.baseBps)}/s", color = KawaiiSoftTeal, fontSize = 11.sp)
                            Text("${HioneBeatClickerEngine.formatNumber(b.currentCost)} leeks", color = if (canAfford) KawaiiGoldenHoney else KawaiiTextMuted, fontSize = 11.5.sp, fontWeight = FontWeight.Black)
                        }
                    }
                }
            }
        }
    } else if (selectedMode == 2) {
        // MODE 2: PRODUCER SKILLS UPGRADE CARDS
        LazyRow(
            Modifier
                .fillMaxWidth()
                .height(72.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(clickerSkills) { s ->
                val canAfford = leekCount >= s.currentCost && s.level < s.maxLevel
                Box(
                    Modifier
                        .width(155.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0x33000000))
                        .border(1.dp, if (canAfford) KawaiiSoftTeal else Color(0x22FFFFFF), RoundedCornerShape(14.dp))
                        .clickable(enabled = canAfford) {
                            HioneBeatClickerEngine.buySkill(s.id)
                            com.hione.launcher.haptics.HioneHaptics.like(ctx)
                        }
                        .padding(6.dp)
                ) {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${s.icon} ${s.name}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text("Lv.${s.level}", color = KawaiiSoftTeal, fontSize = 11.5.sp, fontWeight = FontWeight.Black)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                            Text(if (s.level >= s.maxLevel) "MAX" else "${HioneBeatClickerEngine.formatNumber(s.currentCost)} leeks", color = if (canAfford) KawaiiGoldenHoney else KawaiiTextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text(if (canAfford) "UPGRADE" else (if (s.level >= s.maxLevel) "MASTERED" else "LOCKED"), color = if (canAfford) KawaiiSoftTeal else KawaiiTextMuted, fontSize = 10.sp, fontWeight = FontWeight.Black)
                        }
                    }
                }
            }
        }
    } else if (selectedMode == 3) {
        // MODE 3: THEME SKINS SELECTOR
        LazyRow(
            Modifier
                .fillMaxWidth()
                .height(72.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // A secret skin is not shown locked — it is not shown at all until it is earned.
            items(HioneBeatClickerEngine.BpmSkin.entries.filter { !it.isSecret || it.isAvailable(ctx) }) { skin ->
                val isCurrent = currentSkin == skin
                // Availability is a real gate, and a locked card says exactly what earns it
                // instead of just being greyed out.
                val available = remember(skin, currentSkin) { skin.isAvailable(ctx) }
                val lockReq = remember(skin) {
                    skin.unlockId?.let { id -> HioneUnlocks.rewardFor(id) }
                }
                Box(
                    Modifier
                        .width(176.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0x33000000))
                        .border(
                            1.5.dp,
                            if (isCurrent) Color(skin.primaryColor)
                            else if (!available) Color(0x22FFFFFF) else Color(0x44FFFFFF),
                            RoundedCornerShape(14.dp)
                        )
                        .clickable {
                            if (HioneBeatClickerEngine.setSkin(ctx, skin)) {
                                com.hione.launcher.haptics.HioneHaptics.tick(ctx)
                            } else {
                                com.hione.launcher.haptics.HioneHaptics.reject(ctx)
                                android.widget.Toast.makeText(
                                    ctx,
                                    lockReq?.let { "Locked. ${it.title}: ${HioneUnlocks.progressLabel(it)}" }
                                        ?: "Locked",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                        .padding(6.dp)
                ) {
                    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                        // A REAL preview of the difference — the actual node silhouette, ambient
                        // treatment and burst glyphs — not a colour chip.
                        BpmSkinPreview(skin = skin, dimmed = !available)
                        Spacer(Modifier.width(7.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                            Text(
                                "${skin.icon} ${skin.displayName}".trim(),
                                color = if (available) Color.White else KawaiiTextMuted,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                skin.blurb,
                                color = KawaiiTextMuted,
                                fontSize = 8.5.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                when {
                                    !available && lockReq != null -> "Locked: ${HioneUnlocks.progressLabel(lockReq)}"
                                    isCurrent -> "ACTIVE"
                                    else -> "TAP TO APPLY"
                                },
                                color = if (isCurrent) Color(skin.primaryColor) else if (available) KawaiiSoftTeal else KawaiiGoldenHoney,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    } else if (selectedMode == 4) {
        // MODE 4: QUESTS & ACHIEVEMENTS LIST
        LazyRow(
            Modifier
                .fillMaxWidth()
                .height(72.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Secrets first: how many remain and today's clue (never the list), then every secret
            // already found with its on/off switch.
            item { BpmSecretMysteryCard() }
            items(HioneSecrets.ALL.filter { it.id in earnedUnlocks }) { sec -> BpmSecretCard(sec) }
            items(achievements) { a ->
                Box(
                    Modifier
                        .width(155.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0x33000000))
                        .border(1.dp, if (a.isUnlocked) Color(0xFFFFD700) else Color(0x22FFFFFF), RoundedCornerShape(14.dp))
                        .padding(6.dp)
                ) {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${a.icon} ${a.title}", color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text(if (a.isUnlocked) "✓" else "○", fontSize = 12.sp)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                            Text(a.desc, color = KawaiiTextMuted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            Spacer(Modifier.width(4.dp))
                            Text(Mascot.lore("+${HioneBeatClickerEngine.formatNumber(a.rewardLeeks)} leeks", "+${HioneBeatClickerEngine.formatNumber(a.rewardLeeks)} notes"), color = Color(0xFFFFD700), fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            // Cross-app rewards live in the same row as the quests: earned ones first so the
            // player sees what they already own, then the ladder ahead of them in order.
            items(HioneUnlocks.ALL) { r ->
                BpmRewardCard(reward = r, earned = r.id in earnedUnlocks)
            }
        }
    }
}

/**
 * Bottom dock for modes 0 and 5: stat badges plus the tap-tempo calibration controls
 * (lock BPM, /2, x2, DB lookup) and the hint line.
 *
 * Extracted from [HioneBpmObservatoryModal] because that composable compiled to ~25.8k dex
 * instructions — over ART's 16384-instruction JIT ceiling — so it was re-interpreted on every
 * recomposition and pinned the main thread. Keep each piece well under that limit.
 */
@Composable
private fun BpmCalibrationFooter(
    isPlaying: Boolean,
    hasLiveTempo: Boolean,
    liveBpm: Float,
    leekCount: Double,
    clickerCombo: Int,
    currentSeason: HioneBpmSeasonsEngine.SeasonStats,
    trackArtist: String?,
    trackTitle: String?,
    bpmDb: HioneBpmDatabase,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    tapCounterState: MutableIntState,
    calculatedTapBpmState: MutableState<Float?>,
    dbStatsState: MutableState<Map<String, Any>>
) {
    val ctx = LocalContext.current
    val tapCounter by tapCounterState
    var calculatedTapBpm by calculatedTapBpmState
    var dbStats by dbStatsState

    // MODE 0 / 5: CALIBRATION ACTION FOOTER & DB CONTROLS
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            KawaiiStatBadge(Mascot.lore("LEEK HARVEST", "NOTE HARVEST"), HioneBeatClickerEngine.formatNumber(leekCount), KawaiiGoldenHoney)
            KawaiiStatBadge("COMBO STREAK", "x$clickerCombo", if (clickerCombo >= 10) KawaiiGoldenHoney else KawaiiSakuraPink)
            KawaiiStatBadge("SEASON RANK", currentSeason.rank.badge, Color(currentSeason.rank.colorHex))
            KawaiiStatBadge("TAP TEMPO", if (calculatedTapBpm != null) "${calculatedTapBpm!!.toInt()}" else "--", KawaiiHioneMint)
        }

        // Today's Setlist goals — the reason to start a session and to come back tomorrow — and
        // the one seeded Daily Challenge that makes tomorrow a different day.
        BpmDailySetlistRow()
        BpmDailyChallengeLine()

        // Does the tapped tempo agree with what the detector heard? Octave-aware, so tapping
        // half-time on a 170 BPM track reads as CORRECT instead of wrong.
        BpmTempoMatchLine(hasLiveTempo = hasLiveTempo, liveBpm = liveBpm, tappedBpm = calculatedTapBpm)

        if (calculatedTapBpm != null) {
            val tapVal = calculatedTapBpm!!
            val halfVal = (tapVal / 2f).coerceIn(20f, 999f)
            val doubleVal = (tapVal * 2f).coerceIn(20f, 999f)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Mascot.color(0x55FF3385))
                        .border(1.dp, KawaiiHotPink, RoundedCornerShape(10.dp))
                        .clickable {
                            com.hione.launcher.haptics.HioneHaptics.like(ctx)
                            try {
                                android.provider.Settings.Global.putFloat(ctx.contentResolver, "hione_live_bpm", tapVal)
                                android.provider.Settings.Global.putInt(ctx.contentResolver, "hione_beat_interval_ms", (60000f / tapVal).toInt())
                                val intent = android.content.Intent("com.hione.action.BPM_UPDATE").apply {
                                    putExtra("bpm", tapVal)
                                    putExtra("beat_interval_ms", (60000f / tapVal).toLong())
                                    putExtra("is_playing", isPlaying)
                                }
                                ctx.sendBroadcast(intent)
                            } catch (_: Throwable) {}

                            // Save to SQLite Database — only for a track we can actually
                            // name. A calibration row keyed on an invented title would
                            // be served back later as a real user calibration.
                            if (trackArtist != null && trackTitle != null) {
                                coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    bpmDb.saveTrackBpm(
                                        HioneBpmDatabase.TrackBpmRecord(
                                            artist = trackArtist,
                                            title = trackTitle,
                                            canonicalBpm = tapVal,
                                            // 0 = nothing was auto-detected for this
                                            // track; don't store the game's 120f default
                                            // as a "raw detected" measurement.
                                            rawDetectedBpm = if (hasLiveTempo) liveBpm else 0f,
                                            userTappedBpm = tapVal,
                                            // Octave-aware and epsilon-tolerant: the detector is
                                            // an estimator, so "within a couple of BPM" is the
                                            // same tempo, and half/double-time is a MATCH with a
                                            // known multiplier rather than a disagreement. 1.0
                                            // when nothing was detected to relate it to.
                                            tempoMultiplier = if (hasLiveTempo)
                                                (HioneRhythmTiming.octaveMultiplier(tapVal, liveBpm) ?: 1.0f)
                                            else 1.0f,
                                            confidence = 1.0f,
                                            source = "USER_CALIBRATED",
                                            tapCount = tapCounter
                                        )
                                    )
                                    dbStats = bpmDb.getCalibrationStats()
                                }
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("LOCK BPM: ${tapVal.toInt()}", color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
                }
                Spacer(Modifier.width(5.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Mascot.color(0x33FFAB2E))
                        .border(1.dp, KawaiiHioneMint, RoundedCornerShape(10.dp))
                        .clickable {
                            calculatedTapBpm = halfVal
                            com.hione.launcher.haptics.HioneHaptics.tick(ctx)
                        }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                ) {
                    Text("/2 (${halfVal.toInt()})", color = KawaiiHioneMint, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                }
                Spacer(Modifier.width(4.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0x33DFB8FF))
                        .border(1.dp, KawaiiLavender, RoundedCornerShape(10.dp))
                        .clickable {
                            calculatedTapBpm = doubleVal
                            com.hione.launcher.haptics.HioneHaptics.tick(ctx)
                        }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                ) {
                    Text("x2 (${doubleVal.toInt()})", color = KawaiiLavender, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                }
                Spacer(Modifier.width(4.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Mascot.color(0x33FFC56B))
                        .border(1.dp, Mascot.color(0xFFFFC56B), RoundedCornerShape(10.dp))
                        .clickable {
                            com.hione.launcher.haptics.HioneHaptics.tick(ctx)
                            coroutineScope.launch {
                                if (trackArtist == null || trackTitle == null) {
                                    android.widget.Toast.makeText(ctx, "No track info, so nothing to look up", android.widget.Toast.LENGTH_SHORT).show()
                                    return@launch
                                }
                                val online = bpmDb.resolveCanonicalBpm(trackArtist, trackTitle)
                                if (online != null && online.canonicalBpm > 0f) {
                                    calculatedTapBpm = online.canonicalBpm
                                    android.widget.Toast.makeText(ctx, "Found: ${online.canonicalBpm.toInt()} BPM (${online.source})", android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    // There is no DSP analyser to fall back to; don't claim one.
                                    android.widget.Toast.makeText(ctx, "No BPM found in the local DB or dictionary", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                ) {
                    Text("LOOK UP", color = Mascot.color(0xFFFFC56B), fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                }
            }
        }

        // Constant output/touch latency trim — see BpmTimingCalibrationRow.
        BpmTimingCalibrationRow()

        Spacer(Modifier.height(3.dp))
        // This song's run against its personal best. Paused, the line says why nothing scores.
        if (isPlaying) BpmRunLine() else Text(
            text = "Paused. Taps only measure tempo and aren't scored.",
            color = KawaiiTextMuted,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}


/**
 * Top header: drag handle, the six gamified mode tab pills, close button and the rainbow
 * sugar fever gauge.
 *
 * Extracted from [HioneBpmObservatoryModal] because that composable compiled to ~25.8k dex
 * instructions — over ART's 16384-instruction JIT ceiling — so it was re-interpreted on every
 * recomposition and pinned the main thread. Keep each piece well under that limit.
 */
@Composable
private fun BpmObservatoryHeader(
    selectedModeState: MutableIntState,
    currentSeason: HioneBpmSeasonsEngine.SeasonStats,
    activeSkinPrimary: Color,
    feverEnergy: Float,
    feverSeconds: Int,
    onClose: () -> Unit
) {
    var selectedMode by selectedModeState

    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Cute Cloud Pill Handle
        Box(
            Modifier
                .width(46.dp)
                .height(5.5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(KawaiiSakuraPink.copy(alpha = 0.7f))
        )
        Spacer(Modifier.height(6.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 6 Gamified Mode Tabs (Horizontally Scrollable)
            LazyRow(
                Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0x40000000))
                    .border(1.2.dp, activeSkinPrimary.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item {
                    KawaiiModeTabPill("RHYTHM", selectedMode == 0, activeSkinPrimary) { selectedMode = 0 }
                }
                item {
                    KawaiiModeTabPill("HARVEST", selectedMode == 1, KawaiiGoldenHoney) { selectedMode = 1 }
                }
                item {
                    KawaiiModeTabPill("SKILLS", selectedMode == 2, KawaiiSoftTeal) { selectedMode = 2 }
                }
                item {
                    KawaiiModeTabPill("SKINS", selectedMode == 3, KawaiiLavender) { selectedMode = 3 }
                }
                item {
                    KawaiiModeTabPill("QUESTS", selectedMode == 4, Color(0xFFFFD700)) { selectedMode = 4 }
                }
                item {
                    KawaiiModeTabPill("${currentSeason.rank.badge} SEASONS", selectedMode == 5, Color(currentSeason.rank.colorHex)) { selectedMode = 5 }
                }
            }

            // Close button
            IconButton(
                onClick = {
                    HioneBeatClickerEngine.saveState()
                    onClose()
                },
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Mascot.color(0x33FF85B3))
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close",
                    tint = activeSkinPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        // Rainbow Sugar Fever Gauge
        KawaiiSugarFeverGauge(
            feverEnergy = feverEnergy,
            feverSeconds = feverSeconds,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
