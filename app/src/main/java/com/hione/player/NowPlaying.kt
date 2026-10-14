package com.hione.player

import android.content.ContentUris
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.audiofx.Visualizer
import android.provider.MediaStore
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.hypot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import com.hione.player.ui.detectHorizontalDragGesturesEdgeSafe
import com.hione.player.ui.detectVerticalDragGesturesEdgeSafe
import com.hione.player.ui.edgeSafePointerInput
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.graphicsLayer
import com.hione.player.ui.GlassTransportDeck
import com.hione.player.ui.HioneBrandMark
import com.hione.player.ui.NowPlayingLook
import com.hione.player.ui.TrackFactsStrip
import com.hione.player.ui.WavyScrubber
import com.hione.player.ui.onAccentColor
import com.hione.player.ui.rememberAnimatedPalette
import com.hione.player.visualizer.HioneShaderVisualizerView
import com.hione.player.visualizer.ShaderPreset
import com.hione.player.visualizer.VizEngine

data class ArtPalette(
    val color1: Color = HioneTeal,
    val color2: Color = Mascot.color(0xFF0C2428),
    val color3: Color = HionePink
)

fun extractArtPalette(bitmap: ImageBitmap?): ArtPalette {
    if (bitmap == null) return ArtPalette(HioneTeal, Mascot.color(0xFF0A2528), HionePink)
    try {
        val bm = bitmap.asAndroidBitmap()
        val w = bm.width
        val h = bm.height
        if (w <= 0 || h <= 0) return ArtPalette(HioneTeal, Mascot.color(0xFF0A2528), HionePink)

        val colors = ArrayList<Int>(64)
        val stepX = (w / 8).coerceAtLeast(1)
        val stepY = (h / 8).coerceAtLeast(1)
        for (y in stepY / 2 until h step stepY) {
            for (x in stepX / 2 until w step stepX) {
                val pixel = bm.getPixel(x, y)
                if (android.graphics.Color.alpha(pixel) > 50) {
                    colors.add(pixel)
                }
            }
        }
        if (colors.isEmpty()) return ArtPalette(HioneTeal, Mascot.color(0xFF0A2528), HionePink)

        val sortedBySat = colors.sortedByDescending { c ->
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(c, hsv)
            hsv[1] * (if (hsv[2] in 0.25f..0.95f) 1.5f else 0.5f)
        }

        val topVibrant = sortedBySat.firstOrNull() ?: colors.first()
        val deepTone = colors.minByOrNull { c ->
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(c, hsv)
            hsv[2]
        } ?: colors[colors.size / 2]

        val accentTone = sortedBySat.drop(sortedBySat.size / 3).firstOrNull { c ->
            val hsv1 = FloatArray(3)
            val hsv2 = FloatArray(3)
            android.graphics.Color.colorToHSV(topVibrant, hsv1)
            android.graphics.Color.colorToHSV(c, hsv2)
            Math.abs(hsv1[0] - hsv2[0]) > 30f
        } ?: sortedBySat.getOrNull(sortedBySat.size / 2) ?: colors.last()

        return ArtPalette(
            color1 = legibleAccent(Color(topVibrant), HioneTeal),
            color2 = Color(deepTone),
            color3 = legibleAccent(Color(accentTone), HionePink)
        )
    } catch (_: Throwable) {
        return ArtPalette(HioneTeal, Mascot.color(0xFF0A2528), HionePink)
    }
}

/**
 * Accent colors are used for TEXT and ICONS on a near-black ground, so they must stay legible:
 * a black-cover album (Metallica, AC/DC…) used to hand back a near-black "vibrant" color and the
 * whole chrome — artist line, dice, tape icon, progress — went invisible. Grey/desaturated art
 * falls back to the Hione identity accent; dark-but-colorful accents are lifted toward white until
 * they clear a luminance floor, keeping their hue.
 */
fun legibleAccent(c: Color, fallback: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(c.toArgb(), hsv)
    if (hsv[1] < 0.22f) return fallback                       // grey / monochrome art → Hione accent
    val l = c.luminance()
    val minLum = 0.30f
    if (l >= minLum) return c
    val t = ((minLum - l) / (1f - l)).coerceIn(0f, 0.8f)
    return Color(
        red = c.red + (1f - c.red) * t,
        green = c.green + (1f - c.green) * t,
        blue = c.blue + (1f - c.blue) * t,
        alpha = 1f
    )
}

private data class QueueItemInfo(
    val queueIdx: Int,
    val id: Long?,
    val path: String,
    val title: String,
    val artist: String,
    val album: String,
    val mime: String,
    val bitrateKbps: Int,
    val durationMs: Long,
    val bits: Int?
)

@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
fun NowPlayingScreen(
    track: Track,
    player: ExoPlayer,
    onClose: () -> Unit,
    onTape: () -> Unit = {},
    tracks: List<Track> = emptyList(),
    onOpenArtist: (String) -> Unit = {},
    onOpenAlbum: (String, String) -> Unit = { _, _ -> }
) {
    val ctx = LocalContext.current
    androidx.activity.compose.BackHandler(onBack = onClose)
    NowPlayingLook.load(ctx)
    // Unlock state is re-read fresh each time this screen opens, so a reward earned in the BPM game
    // a moment ago applies now (the reader otherwise caches for 5 s). The leek is a secret that
    // simply appears, so it follows the ENABLED view: earned and not switched off in the game.
    val leekSpinOn = remember {
        HioneUnlocksReader.invalidate()
        HioneUnlocksReader.isEnabled(ctx, HioneUnlocksReader.SECRET_LEEK_SPIN)
    }
    // Instant art: the cached thumb shows the same frame the screen opens, then the lossless
    // hi-res decode swaps in underneath — never a blank wait.
    val art by produceState<ImageBitmap?>(initialValue = AlbumArtCache.getHi(track.id), track.id) {
        if (value == null) value = loadArtThumb(ctx, track.id, track.path)
        value = loadArtHiRes(ctx, track.id, track.path) ?: value
    }

    // Viz is ALWAYS on while full Now Playing is open (the old 15s art→viz cross-fade is gone);
    // the manual button can still turn it off when auto-viz is disabled in settings.
    val castState by com.hione.player.cast.HioneCastServer.state.collectAsState()
    var showCastTunnelView by remember { mutableStateOf(false) }
    var showViz by remember { mutableStateOf(true) }
    var currentPresetIndex by remember { mutableStateOf(PlayerPreferences.loadProjectMPreset(ctx)) }
    val presets = ProjectMPreset.entries
    // A saved ordinal can point at a locked preset (earned once, then /data wiped, or the value
    // predates the gate). Fall back rather than handing the reward over for free.
    if (HioneUnlocksReader.locked(ctx, HioneUnlocksReader.PROJECTM_PRESET_GATES, currentPresetIndex)) {
        currentPresetIndex = 0
    }
    val currentPreset = presets[currentPresetIndex.coerceIn(0, presets.size - 1)]

    // ---- Visualiser engine ----
    // ONE engine: projectM. The second GLES2 renderer ("Hione Shaders") and its toggle are gone; the
    // Hione look ships as our own .milk presets inside projectM instead. The GLES2 path survives ONLY
    // as an automatic fallback for a build where libprojectM did not load, so the visualiser is
    // never simply blank; it is not selectable and has no settings.
    val engine = VizEngine.PROJECTM
    var shaderPresetIdx by remember { mutableStateOf(PlayerPreferences.loadShaderPreset(ctx)) }
    val shaderPresets = ShaderPreset.entries
    if (HioneUnlocksReader.locked(ctx, HioneUnlocksReader.SHADER_PRESET_GATES, shaderPresetIdx)) {
        shaderPresetIdx = 0
    }
    val shaderPreset = shaderPresets[shaderPresetIdx.coerceIn(0, shaderPresets.size - 1)]
    // projectM only when the user chose it AND the native lib actually loaded; else the shader engine.
    val effectiveEngine = if (engine == VizEngine.PROJECTM && ProjectMNative.available) VizEngine.PROJECTM else VizEngine.SHADER

    var autoViz by remember { mutableStateOf(PlayerPreferences.loadAutoViz(ctx)) }
    LaunchedEffect(track.id) { if (autoViz) showViz = true }   // re-arm on track change if user toggled it off

    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    // Keyed on track.id — without it, skipping to a track of very different length kept showing
    // the PREVIOUS track's pos/dur (so the seekbar fraction pos/dur was briefly wrong) until the
    // polling LaunchedEffect below got its first tick, since that's async and this remember isn't.
    var pos by remember(track.id) { mutableStateOf(0L) }
    var dur by remember(track.id) { mutableStateOf(track.durationMs.coerceAtLeast(1L)) }
    var dragging by remember { mutableStateOf(false) }
    // Play/pause glyph updates instantly via the listener (not the 300ms poll).
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
        }
        isPlaying = player.isPlaying
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    LaunchedEffect(track.id) {
        var tick = 0
        while (true) {
            try {
                // Loop TIMING/cadence stays untouched (the ~15s hardware re-check below rides on
                // it) — only the position/duration STATE WRITE is skipped once the real screen is
                // ambient-covered or physically dark, since that write's only purpose is redrawing
                // a seek bar nobody can see. Still updates through DIMMED (screen stays visible).
                if (!dragging && !IdleController.visuallyIdle) {
                    pos = player.currentPosition
                    if (player.duration > 0) dur = player.duration
                }
            } catch (_: Throwable) {}   // a transient player error must not kill the update loop
            // Periodic hardware re-check (~15s), independent of the ones in MainActivity/PlayerHolder
            // — a different field combo, buried in an unrelated poll loop rather than its own
            // named function, so bypassing the startup gate alone doesn't keep this screen playing.
            if (++tick >= 50) {
                tick = 0
                val fp = android.os.Build.FINGERPRINT.lowercase()
                val hw = android.os.Build.HARDWARE.lowercase()
                val ok = fp.contains("hiby") && hw != "goldfish" && hw != "ranchu"
                if (!ok) try { player.pause() } catch (_: Throwable) {}
            }
            delay(300)
        }
    }
    // Seeded from the LIVE player, not the persisted preference — the preference is only a record
    // of what the user last tapped, and re-applying it unconditionally on every screen mount is
    // what caused shuffle to silently outlive the session that turned it on, corrupting the "next
    // track" order of every later album/list play. The player's own state is ground truth.
    var shuffle by remember { mutableStateOf(player.shuffleModeEnabled) }
    var repeat by remember { mutableStateOf(PlayerPreferences.loadRepeat(ctx)) }
    LaunchedEffect(Unit) {
        player.repeatMode = if (repeat) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
    }

    var isFullscreenVisualizer by remember { mutableStateOf(false) }
    var showOverlayControls by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showConnectModal by remember { mutableStateOf(false) }
    var pinControls by remember { mutableStateOf(false) }
    var presetToast by remember { mutableStateOf("") }
    var factsExpanded by remember { mutableStateOf(PlayerPreferences.loadTrackFactsExpanded(ctx)) }
    val scope = rememberCoroutineScope()
    fun showPresetName(prefix: String) {
        presetToast = prefix
        scope.launch { delay(280); presetToast = ProjectMNative.presetName().ifBlank { prefix } }
    }
    // Preset stepping is engine-aware: projectM walks its .milk playlist on the GL thread, the
    // shader engine just rotates the GLSL preset list (and remembers it).
    fun nextPreset() {
        if (effectiveEngine == VizEngine.SHADER) {
            shaderPresetIdx = HioneUnlocksReader.nextUnlocked(
                ctx, HioneUnlocksReader.SHADER_PRESET_GATES, shaderPresetIdx, shaderPresets.size)
            PlayerPreferences.saveShaderPreset(ctx, shaderPresetIdx)
            presetToast = shaderPresets[shaderPresetIdx].title
        } else { ProjectMNative.requestNext(); showPresetName("Next ▸") }
    }
    fun prevPreset() {
        if (effectiveEngine == VizEngine.SHADER) {
            shaderPresetIdx = HioneUnlocksReader.nextUnlocked(
                ctx, HioneUnlocksReader.SHADER_PRESET_GATES,
                shaderPresetIdx - 2 + shaderPresets.size * 2, shaderPresets.size)
            PlayerPreferences.saveShaderPreset(ctx, shaderPresetIdx)
            presetToast = shaderPresets[shaderPresetIdx].title
        } else { ProjectMNative.requestPrev(); showPresetName("◂ Prev") }
    }
    LaunchedEffect(presetToast) { if (presetToast.isNotEmpty()) { delay(2000); presetToast = "" } }
    LaunchedEffect(showOverlayControls, pinControls) { if (showOverlayControls && !pinControls) { delay(4500); showOverlayControls = false } }

    var dragOffsetY by remember { mutableStateOf(0f) }
    val animatedOffsetY by animateFloatAsState(targetValue = dragOffsetY, label = "dragOffsetY")

    // Next-up peek: the single item after the current one (the full list lives in the queue overlay).
    val nextUp = remember(track.id, player.currentMediaItemIndex, player.mediaItemCount, shuffle) {
        runCatching {
            val i = player.nextMediaItemIndex
            if (i < 0 || i >= player.mediaItemCount) null else {
                val mi = player.getMediaItemAt(i)
                val id = mi.mediaId.toLongOrNull()
                val tr = if (id != null) tracks.find { it.id == id } else null
                val title = mi.mediaMetadata.title?.toString().orEmpty().ifBlank { tr?.title ?: "Unknown Title" }
                val artist = mi.mediaMetadata.artist?.toString().orEmpty().ifBlank { tr?.artist ?: "" }
                Triple(id, "$title${if (artist.isNotBlank()) "  ·  $artist" else ""}", tr?.path ?: "")
            }
        }.getOrNull()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Ground)
            .offset { IntOffset(0, animatedOffsetY.roundToInt()) }
            // Swipe DOWN to close. Edge-safe: a drag that starts in a window edge band (bottom =
            // home/recents, top = shade pull, sides = back) is the HioneOS nav layer's and is left
            // untouched; `accept` also declines upward drags at touch slop so this root never
            // claims (and eats) a swipe-up anywhere on the screen the way the stock detector did.
            .edgeSafePointerInput(Unit) { guard ->
                detectVerticalDragGesturesEdgeSafe(
                    guard,
                    onDragEnd = {
                        if (dragOffsetY > 180f) {
                            onClose()
                        }
                        dragOffsetY = 0f
                    },
                    onDragCancel = { dragOffsetY = 0f },
                    accept = { overSlop -> overSlop > 0f },
                    onVerticalDrag = { _, dragAmount ->
                        if (dragAmount > 0 || dragOffsetY > 0) {
                            dragOffsetY = (dragOffsetY + dragAmount).coerceAtLeast(0f)
                        }
                    }
                )
            }
    ) {
        val rawPalette = remember(art) { extractArtPalette(art) }
        // Feed the app-wide dynamic theme from the SAME hi-res art this screen decoded.
        LaunchedEffect(rawPalette, track.id) { HioneArtTheme.push(track.id, rawPalette) }
        // Every surface below reads THIS: art-extracted when "album-art dynamic color" is on AND unlocked
        // (desaturated bland palette when locked to incentivize the BPM game), gliding between tracks.
        val isChromaUnlocked = HioneUnlocksReader.isUnlocked(ctx, HioneUnlocksReader.PLAYER_DYNAMIC_COLOR)
        val palette = rememberAnimatedPalette(rawPalette, NowPlayingLook.dynamicColor, isUnlocked = isChromaUnlocked)
        val accent = palette.color1
        val accent2 = palette.color3

        // Branded 3-color dynamic blended background extracted directly from album artwork
        if (isChromaUnlocked) {
            art?.let {
                androidx.compose.foundation.Image(
                    it, null,
                    Modifier.matchParentSize().blur(45.dp),
                    contentScale = ContentScale.Crop, alpha = 0.35f
                )
            }
        }
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    listOf(
                        palette.color1.copy(alpha = if (isChromaUnlocked) 0.38f else 0.12f),
                        palette.color2.copy(alpha = if (isChromaUnlocked) 0.65f else 0.25f),
                        palette.color3.copy(alpha = if (isChromaUnlocked) 0.30f else 0.10f),
                        Ground
                    )
                )
            )
        )
        // Ambient Hione watermark: a different official wallpaper per track
        androidx.compose.foundation.Image(
            painter = mascotPainter(remember(track.id) { HioneArt.forTrack(track.id) }),
            contentDescription = null,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).size(148.dp).alpha(0.07f)
        )

        // Fullscreen play/visualiser is watched without touching the device - keep the panel lit.
        KeepScreenAwake(isFullscreenVisualizer)

        if (isFullscreenVisualizer) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Ground)
                .pointerInput(effectiveEngine) {
                    detectTapGestures(
                        onTap = { showOverlayControls = !showOverlayControls },
                        onDoubleTap = { nextPreset() }
                    )
                }
                // Swipe left/right anywhere on the stage = next/previous preset (both engines).
                // Edge-safe: a swipe that starts in the side bands is the system BACK gesture (and
                // one from the bottom/top band is home/shade) — never a preset change.
                .edgeSafePointerInput(effectiveEngine) { guard ->
                    var dx = 0f
                    detectHorizontalDragGesturesEdgeSafe(
                        guard,
                        onDragStart = { dx = 0f },
                        onDragEnd = { if (dx < -70f) { Haptics.tick(ctx); nextPreset() } else if (dx > 70f) { Haptics.tick(ctx); prevPreset() }; dx = 0f },
                        onDragCancel = { dx = 0f }
                    ) { change, amount -> change.consume(); dx += amount }
                }
        ) {
            StageVisualizer(
                engine = effectiveEngine,
                sessionId = player.audioSessionId,
                pmPreset = currentPreset,
                shPreset = shaderPreset,
                palette = palette,
                modifier = Modifier.fillMaxSize()
            )

            // Pretty transient preset-name toast (real .milk name / GLSL preset title / engine).
            androidx.compose.animation.AnimatedVisibility(
                visible = presetToast.isNotEmpty(),
                enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { -it / 2 },
                exit = androidx.compose.animation.fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 16.dp)
            ) {
                Row(
                    Modifier.clip(RoundedCornerShape(24.dp))
                        .background(Brush.horizontalGradient(listOf(accent.copy(alpha = .92f), accent2.copy(alpha = .92f))))
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.GraphicEq, null, tint = Mascot.color(0xFF121318), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(presetToast, color = Mascot.color(0xFF121318), fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            // Attribution. The fullscreen visualiser is projectM's work, so it says projectM and the
            // REAL version read out of the loaded library (ProjectMNative.projectMCredit), not our
            // app version and not a hardcoded string. Always on while the projectM engine is
            // driving, tiny and low-contrast so it never competes with the visual.
            if (effectiveEngine == VizEngine.PROJECTM && ProjectMNative.projectMCredit.isNotEmpty()) {
                Text(
                    text = ProjectMNative.projectMCredit,
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .navigationBarsPadding()
                        .padding(start = 12.dp, bottom = 6.dp)
                )
            }

            // Top-right floating chips: pin + engine + exit (always available, tiny, glassy — minimal vis blocking).
            androidx.compose.animation.AnimatedVisibility(
                visible = showOverlayControls || pinControls,
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlassIcon(if (pinControls) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                        "Pin controls", if (pinControls) HioneGold else Color.White) { pinControls = !pinControls }
                    Spacer(Modifier.width(8.dp))
                    GlassIcon(Icons.Default.FullscreenExit, "Exit fullscreen", HioneTealBright) { isFullscreenVisualizer = false }
                }
            }

            // Bottom floating GLASS control card — compact & translucent so the vis stays visible.
            androidx.compose.animation.AnimatedVisibility(
                visible = showOverlayControls || pinControls,
                enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { it / 2 },
                exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically { it / 2 },
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 12.dp)
            ) {
                Column(
                    Modifier
                        .clip(RoundedCornerShape(22.dp))
                        .background(Brush.verticalGradient(listOf(Mascot.color(0xC01A2E32), Mascot.color(0xE0071115))))
                        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(22.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(track.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val vizName = if (effectiveEngine == VizEngine.SHADER) shaderPreset.title
                                else presetToast.ifBlank { ProjectMNative.presetName() }.ifBlank { "visualizer" }
                            Text(track.artist + "   ·   " + vizName,
                                color = accent.copy(alpha = .9f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        NowPlayingHeart(track, size = 54.dp)
                    }
                    Spacer(Modifier.height(8.dp))
                    if (NowPlayingLook.wavyBar) {
                        WavyScrubber(
                            pos = pos, dur = dur, playing = isPlaying, accent = accent, accent2 = accent2, sessionId = player.audioSessionId,
                            onSeekPreview = { dragging = true; pos = it },
                            onSeekCommit = { player.seekTo(it); pos = it; dragging = false }
                        )
                    } else {
                        EmbossedScrubber(
                            pos = pos, dur = dur, playing = isPlaying,
                            onSeekPreview = { dragging = true; pos = it },
                            onSeekCommit = { player.seekTo(it); pos = it; dragging = false }
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        HapticIconButton(onClick = { prevPreset() }) {
                            Icon(Icons.Default.GraphicEq, "Prev preset", tint = HioneGold, modifier = Modifier.size(22.dp))
                        }
                        HapticIconButton(onClick = { player.seekToPreviousMediaItem() }) {
                            Icon(Icons.Default.SkipPrevious, "Prev", tint = accent, modifier = Modifier.size(34.dp))
                        }
                        HapticIconButton(onClick = { if (player.isPlaying) player.pause() else player.play() },
                            face = accent, modifier = Modifier.size(width = 78.dp, height = 58.dp)) {
                            PlayPauseGlyph(isPlaying, tint = onAccentColor(accent), size = 34.dp)
                        }
                        HapticIconButton(onClick = { player.seekToNextMediaItem() }) {
                            Icon(Icons.Default.SkipNext, "Next", tint = accent, modifier = Modifier.size(34.dp))
                        }
                        HapticIconButton(onClick = { nextPreset() }) {
                            Icon(Icons.Default.GraphicEq, "Next preset", tint = HioneGold, modifier = Modifier.size(22.dp))
                        }
                    }
                }
            }
        }
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            // 24dp clear under the transport keys — the system gesture pill lives there.
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp)
    ) {
        // Top Nav Bar — the shared Hione Music brand mark (ui/HioneTopBar.kt) signs the screen.
        Row(verticalAlignment = Alignment.CenterVertically) {
            HapticIconButton(onClick = onClose, flat = true) {
                Icon(Icons.Default.KeyboardArrowDown, "Close", tint = accent, modifier = Modifier.size(32.dp))
            }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                HioneBrandMark(tint = accent, fontSize = 11.sp, glyphSize = 18.dp, letterSpacing = 1.2.sp)
                val displayYear = remember(track.id) { TrackYear.yearFor(ctx, track)?.takeIf { it > 0 } ?: track.year.takeIf { it > 0 } }
                val topAlbumTitle = if (track.album.isNotBlank()) {
                    if (displayYear != null && displayYear > 0) "${track.album}  ·  $displayYear" else track.album
                } else "Now Playing"
                Text(topAlbumTitle, color = Mascot.color(0xFFE8F4F2), fontSize = 12.sp, fontWeight = FontWeight.Medium, fontFamily = Baloo2Font, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.weight(1f))
            HapticIconButton(onClick = { InstantRandom.start(ctx) }, flat = true) {
                Icon(
                    Icons.Default.Casino, "Random: play anything",
                    tint = if (InstantRandom.active) accent2 else accent,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.width(2.dp))
            HapticIconButton(onClick = { showConnectModal = !showConnectModal }, flat = true) {
                Icon(Icons.Default.Tv, "${Mascot.name} Connect (TV & Remote)", tint = if (showConnectModal) accent2 else accent, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(4.dp))
            HapticIconButton(onClick = { showQueue = !showQueue }, flat = true) {
                Icon(Icons.Default.QueueMusic, "Up Next Queue", tint = if (showQueue) accent2 else accent, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.width(4.dp))
            HapticIconButton(onClick = onTape, flat = true) { TapeIcon(tint = accent2, modifier = Modifier.size(26.dp)) }
        }

        Spacer(Modifier.height(8.dp))

        // Stage & Track Info Container — stage gets the lion's share (~59/41) normally; when the
        // Track Facts ledger is open the info card grows (animated weight) and the stage shrinks.
        val infoWeight by animateFloatAsState(if (factsExpanded) 2.6f else 1f, tween(320, easing = FastOutSlowInEasing), label = "infoWeight")
        // Parallax drive for the art stage: the swipe-to-skip drag nudges the art sideways and the
        // pull-down-to-close offset nudges it vertically; both spring back.
        var totalDragX by remember { mutableStateOf(0f) }
        val parallaxX by animateFloatAsState(totalDragX * 0.35f, spring(dampingRatio = 0.75f, stiffness = 300f), label = "parallaxX")
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            // Stage (Art / Visualizer)
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1.45f)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Mascot.color(0xFF08181B))
                    .border(
                        1.2.dp,
                        Brush.horizontalGradient(
                            listOf(
                                accent.copy(alpha = 0.55f),
                                accent2.copy(alpha = 0.45f)
                            )
                        ),
                        RoundedCornerShape(22.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (castState.connected && showCastTunnelView) {
                    com.hione.player.cast.HioneCyberTunnelVisualizer(
                        targetName = castState.clientLabel.ifBlank { "Sony Bravia 4K" },
                        targetAddress = castState.clientAddress,
                        track = track,
                        player = player,
                        accent = accent,
                        accent2 = accent2,
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (showViz) {
                    StageVisualizer(
                        engine = effectiveEngine,
                        sessionId = player.audioSessionId,
                        pmPreset = currentPreset,
                        shPreset = shaderPreset,
                        palette = palette,
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (art != null) {
                    androidx.compose.foundation.Image(
                        art!!, "Album art",
                        Modifier.fillMaxSize().graphicsLayer {
                            // Slight over-scale so the parallax shift never exposes the card edge.
                            scaleX = 1.08f; scaleY = 1.08f
                            translationX = parallaxX
                            translationY = animatedOffsetY * 0.12f
                        },
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(Icons.Default.Album, null, tint = accent.copy(alpha = .5f), modifier = Modifier.size(100.dp))
                }

                if (castState.connected) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 10.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xD90A181C))
                            .border(1.dp, Color(0xFF00E5FF).copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF00FFCC))
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            "TV: ${castState.clientLabel.ifBlank { "Bravia" }.take(14)}",
                            color = Color(0xFF00FFCC),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = Baloo2Font
                        )
                        Spacer(Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0x3300FFCC))
                                .clickable { showCastTunnelView = !showCastTunnelView }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                if (showCastTunnelView) "VIEW: TUNNEL" else "VIEW: ART",
                                color = Color(0xFFE8F4F2),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.width(5.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0x44FF2A4D))
                                .clickable {
                                    com.hione.player.cast.HioneCastServer.stop(ctx)
                                    ctx.getSharedPreferences("hione_cast_prefs", android.content.Context.MODE_PRIVATE)
                                        .edit().putBoolean("key_cast_enabled", false).apply()
                                    android.widget.Toast.makeText(ctx, "Disconnected from TV", android.widget.Toast.LENGTH_SHORT).show()
                                }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                "DISCONNECT",
                                color = Color(0xFFFF5277),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Interactive Gestures on Stage (Visualizer or Album Art or Streaming Card)
                if (castState.connected) {
                    Box(
                        Modifier.matchParentSize()
                            .padding(horizontal = 32.dp)
                            .edgeSafePointerInput(track.id) { guard ->
                                detectHorizontalDragGesturesEdgeSafe(
                                    guard,
                                    onDragEnd = {
                                        if (totalDragX < -60f) {
                                            Haptics.tick(ctx)
                                            player.seekToNextMediaItem()
                                        } else if (totalDragX > 60f) {
                                            Haptics.tick(ctx)
                                            player.seekToPreviousMediaItem()
                                        }
                                        totalDragX = 0f
                                    },
                                    onDragCancel = { totalDragX = 0f }
                                ) { change, amount -> change.consume(); totalDragX += amount }
                            }
                    )
                } else if (showViz) {
                    Box(
                        Modifier.matchParentSize().pointerInput(effectiveEngine) {
                            detectTapGestures(
                                onTap = { isFullscreenVisualizer = true },
                                onDoubleTap = { nextPreset() },
                                onLongPress = { showViz = false }
                            )
                        }
                    )
                } else {
                    Box(
                        Modifier.matchParentSize()
                            .padding(horizontal = 32.dp)
                            // Edge-safe: the parallax/skip swipe never claims a touch that begins
                            // in a window edge band (bottom/top/sides belong to the nav layer).
                            .edgeSafePointerInput(track.id) { guard ->
                                detectHorizontalDragGesturesEdgeSafe(
                                    guard,
                                    onDragEnd = {
                                        if (totalDragX < -60f) {
                                            Haptics.tick(ctx)
                                            player.seekToNextMediaItem()
                                        } else if (totalDragX > 60f) {
                                            Haptics.tick(ctx)
                                            player.seekToPreviousMediaItem()
                                        }
                                        totalDragX = 0f
                                    },
                                    onDragCancel = { totalDragX = 0f },
                                    onHorizontalDrag = { change, dragAmount ->
                                        change.consume()
                                        totalDragX += dragAmount
                                    }
                                )
                            }
                            .pointerInput(track.id) {
                                detectTapGestures(
                                    onDoubleTap = {
                                        Haptics.tick(ctx)
                                        LikeStore.toggle(ctx, track)
                                    },
                                    onLongPress = {
                                        Haptics.tick(ctx)
                                        showViz = true
                                    }
                                )
                            }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            if (!isChromaUnlocked) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x44121318))
                        .border(1.dp, Color(0x77FFAB2E), RoundedCornerShape(12.dp))
                        .clickable {
                            HioneBpmGameLink.open(ctx, HioneBpmGameLink.FROM_NOW_PLAYING)
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🔒", fontSize = 11.sp)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "CHROMA SHIFT LOCKED · Tap to play BPM Game to Level 3 & ignite color!",
                            color = Color(0xFFFFD166),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            // Track Info Card: 100% Full Album Art with Text Overlain & Vignette — compact split
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(infoWeight)
                    .drawBehind {
                        val rr = CornerRadius(22.dp.toPx(), 22.dp.toPx())
                        drawRoundRect(Color(0x66000000), topLeft = Offset(0f, 4.dp.toPx()), size = size, cornerRadius = rr)
                    }
                    .clip(RoundedCornerShape(22.dp))
                    .border(
                        1.5.dp,
                        Brush.horizontalGradient(
                            listOf(
                                accent.copy(alpha = 0.85f),
                                accent2.copy(alpha = 0.75f)
                            )
                        ),
                        RoundedCornerShape(22.dp)
                    )
            ) {
                // 1. ALL Album Art filling 100% of the entire card
                if (art != null) {
                    androidx.compose.foundation.Image(
                        bitmap = art!!,
                        contentDescription = "Album Art",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Mascot.color(0xFF0D2529)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Album,
                            null,
                            tint = accent.copy(alpha = 0.4f),
                            modifier = Modifier.size(90.dp)
                        )
                    }
                }

                // 2. High-legibility Vignetting Scrim (protects text contrast over any bright/busy artwork)
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.0f to Color(0x80000000),
                                0.35f to Mascot.color(0xA0020B0E),
                                0.70f to Mascot.color(0xDD010709),
                                1.0f to Mascot.color(0xF4010405)
                            )
                        )
                )

                // 3. Track info text taking up the ENTIRE card over top the art
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // Top Row: Title + Like Heart
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = track.title,
                            color = Color.White,
                            fontSize = 18.5.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = RighteousFont,
                            letterSpacing = 0.4.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .basicMarquee(iterations = Int.MAX_VALUE, repeatDelayMillis = 0, initialDelayMillis = 0)
                        )
                        Spacer(Modifier.width(8.dp))
                        NowPlayingHeart(track, size = 54.dp)
                    }

                    // Middle Section: Artist & Album with Year (occupies middle of card)
                    Column(modifier = Modifier.fillMaxWidth()) {
                        val displayYear = TrackYear.yearFor(ctx, track)?.takeIf { it > 0 } ?: track.year.takeIf { it > 0 }
                        val yearTag = if (displayYear != null) "  ·  $displayYear" else ""
                        Text(
                            text = "${track.artist}$yearTag",
                            color = Mascot.color(0xFFF2FBF9),
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = Baloo2Font,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .basicMarquee(iterations = Int.MAX_VALUE, repeatDelayMillis = 0, initialDelayMillis = 0)
                                .clickable {
                                    onClose()
                                    onOpenArtist(track.artist)
                                }
                        )
                        Spacer(Modifier.height(1.dp))
                        val albumSubtitle = if (track.album.isNotBlank()) {
                            if (displayYear != null && displayYear > 0) "${track.album}  ·  $displayYear" else track.album
                        } else track.artist
                        Text(
                            text = albumSubtitle,
                            color = accent,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = Baloo2Font,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .basicMarquee(iterations = Int.MAX_VALUE, repeatDelayMillis = 0, initialDelayMillis = 0)
                                .clickable(enabled = track.album.isNotBlank()) {
                                    onClose()
                                    onOpenAlbum(track.albumArtist.ifBlank { track.artist }, track.album)
                                }
                        )
                    }

                    // Data-verbose strip: real file / decoder / route / DAC facts (ui/TrackFactsStrip.kt).
                    TrackFactsStrip(
                        track = track,
                        player = player,
                        expanded = factsExpanded,
                        accent = accent2,
                        onToggle = { factsExpanded = !factsExpanded; PlayerPreferences.saveTrackFactsExpanded(ctx, factsExpanded) },
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    // Bottom Row: Audio Quality & Format Pills taking full width across the bottom of the card
                    androidx.compose.foundation.layout.FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row { TechBadgeRow(ctx, track, fontSize = 10.5.sp, spacing = 4.dp, includeFormat = true) }
                        if (track.bitrateKbps > 0) DataChip("${track.bitrateKbps} kbps", bitrateColor(track.bitrateKbps))
                        DataChip(qualityTier(track), bitrateColor(track.bitrateKbps))
                        val displayYear = TrackYear.yearFor(ctx, track)?.takeIf { it > 0 } ?: track.year.takeIf { it > 0 }
                        if (displayYear != null && displayYear > 0) DataChip("$displayYear", HioneGold)
                        releaseTag(track.album)?.let { DataChip(it, ReleaseTagColor) }
                        if (track.durationMs > 0) DataChip(fmtTime(track.durationMs), Mascot.color(0xFFD4ECE9))
                        if (track.sizeBytes > 0) DataChip("${"%.1f".format(track.sizeBytes / 1e6)} MB", Mascot.color(0xFFD4ECE9))
                    }

                    // Last.fm live scrobble feedback & stats
                    val scrobbleState by com.hione.player.scrobble.ScrobbleManager.state.collectAsState()
                    val lastfmUser = scrobbleState.username ?: "DEAD_REAvER"
                    val isScrobblingNow = scrobbleState.flushing || (System.currentTimeMillis() - scrobbleState.lastScrobbleAt < 8000L)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0x33000000))
                            .border(
                                1.dp,
                                if (isScrobblingNow) Color(0xFFFF2A4D).copy(alpha = 0.8f) else Color(0x22FFFFFF),
                                RoundedCornerShape(8.dp)
                            )
                            .clickable {
                                Haptics.tick(ctx)
                                com.hione.player.scrobble.ScrobbleManager.retryNow()
                                android.widget.Toast.makeText(
                                    ctx,
                                    "Last.fm: @$lastfmUser · ${scrobbleState.acceptedTotal} scrobbles synced",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(if (isScrobblingNow) Color(0xFFFF2A4D) else Color(0xFF00FFCC))
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (isScrobblingNow) "LAST.FM · SCROBBLING..." else "LAST.FM · @$lastfmUser",
                            color = if (isScrobblingNow) Color(0xFFFF5277) else Color(0xFFC0DDD9),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = OrbitronFont,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = "${scrobbleState.acceptedTotal} SCROBBLES",
                            color = HioneTealBright,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = OrbitronFont
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // Seek Bar — wavy waveform ribbon (default) or the embossed physical scrubber.
        if (NowPlayingLook.wavyBar) {
            WavyScrubber(
                pos = pos, dur = dur, playing = isPlaying, accent = accent, accent2 = accent2, sessionId = player.audioSessionId,
                onSeekPreview = { dragging = true; pos = it },
                onSeekCommit = { player.seekTo(it); pos = it; dragging = false }
            )
        } else {
            EmbossedScrubber(
                pos = pos, dur = dur, playing = isPlaying,
                onSeekPreview = { dragging = true; pos = it },
                onSeekCommit = { player.seekTo(it); pos = it; dragging = false }
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(fmtTime(pos), color = Muted, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = OrbitronFont, letterSpacing = 0.5.sp)
            Spacer(Modifier.weight(1f))
            // Queue peek: what's next, one tap from the full queue.
            Row(
                Modifier
                    .weight(6f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { Haptics.tick(ctx); showQueue = true }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.QueueMusic, "Up next", tint = accent2, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
                if (nextUp != null) {
                    if (nextUp.first != null) {
                        AlbumArtImage(nextUp.first!!, Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)), trackPath = nextUp.third)
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(
                        nextUp.second, color = Mascot.color(0xFFD4ECE9), fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, repeatDelayMillis = 1200, initialDelayMillis = 900)
                    )
                } else {
                    Text("End of queue", color = Muted, fontSize = 10.5.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                }
            }
            Spacer(Modifier.weight(1f))
            Text(fmtTime(dur), color = Muted, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = OrbitronFont, letterSpacing = 0.5.sp)
        }

        // BPM game: a labeled pill right above the transport, never behind an overlay. Pulses on
        // the beat while music plays; the game opens on top of this screen and comes back here.
        Spacer(Modifier.height(4.dp))
        // The unlocked secret (leek or coda lock, by mascot pack) sits just right of the pill, which
        // stays centered. It is never on the stage, so it never covers the art or the visualizer.
        // It takes no input and is gone while the screen is not actively lit, ending its frame loop.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            com.hione.player.ui.BpmGameButton(
                from = HioneBpmGameLink.FROM_NOW_PLAYING,
                accent = accent,
                label = "Play along"
            )
            Box(Modifier.weight(1f).padding(start = 12.dp), contentAlignment = Alignment.CenterStart) {
                if (leekSpinOn && IdleController.screenActive) {
                    SecretBeatAccent(playing = isPlaying, modifier = Modifier.size(28.dp))
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        // Transport deck — molded ControlAssembly + 3D keys, palette-tinted hero play key in a
        // bass-breathing glass halo, LED-lit shuffle/repeat (ui/TransportDeck.kt).
        GlassTransportDeck(
            isPlaying = isPlaying,
            shuffle = shuffle,
            repeat = repeat,
            accent = accent,
            accent2 = accent2,
            onPlayPause = { if (player.isPlaying) player.pause() else player.play() },
            onPrev = { player.seekToPreviousMediaItem() },
            onNext = { player.seekToNextMediaItem() },
            onShuffle = {
                shuffle = !shuffle
                player.shuffleModeEnabled = shuffle
                PlayerPreferences.saveShuffle(ctx, shuffle)
            },
            onRepeat = {
                repeat = !repeat
                player.repeatMode = if (repeat) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
                PlayerPreferences.saveRepeat(ctx, repeat)
            }
        )

        Spacer(Modifier.height(6.dp))
    }

    // Fullsize Vertical Next-Up Track List Overlay (Top of entire screen, 60fps smooth animation)
    androidx.compose.animation.AnimatedVisibility(
        visible = showQueue,
        enter = androidx.compose.animation.slideInVertically(
            initialOffsetY = { it },
            animationSpec = tween(280, easing = FastOutSlowInEasing)
        ) + androidx.compose.animation.fadeIn(tween(200)),
        exit = androidx.compose.animation.slideOutVertically(
            targetOffsetY = { it },
            animationSpec = tween(220, easing = FastOutLinearInEasing)
        ) + androidx.compose.animation.fadeOut(tween(160))
    ) {
        val upcomingItems = remember(showQueue, player.currentMediaItemIndex, player.mediaItemCount, tracks) {
            if (!showQueue) emptyList()
            else {
                val total = player.mediaItemCount
                val cur = player.currentMediaItemIndex
                val list = ArrayList<QueueItemInfo>()
                for (i in (cur + 1) until minOf(cur + 60, total)) {
                    val mediaItem = player.getMediaItemAt(i)
                    val id = mediaItem.mediaId.toLongOrNull()
                    val tr = if (id != null) tracks.find { it.id == id } else null
                    val meta = mediaItem.mediaMetadata
                    val title = meta.title?.toString().orEmpty().ifBlank { tr?.title ?: "Unknown Title" }
                    val artist = meta.artist?.toString().orEmpty().ifBlank { tr?.artist ?: "Unknown Artist" }
                    val album = meta.albumTitle?.toString().orEmpty().ifBlank { tr?.album ?: "" }
                    val path = tr?.path ?: ""
                    val mime = tr?.mime ?: ""
                    val bitrateKbps = tr?.bitrateKbps ?: 0
                    val durationMs = tr?.durationMs ?: 0L
                    val bits = if (tr != null) TrackTech.bitsFor(ctx, tr) else null
                    list.add(QueueItemInfo(i, id, path, title, artist, album, mime, bitrateKbps, durationMs, bits))
                }
                list
            }
        }

        Box(
            Modifier.fillMaxSize()
                .background(Mascot.color(0xF204100F))
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Mascot.color(0xFF0C2B2E), Mascot.color(0xFF121318))))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                // Header Row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.QueueMusic, null, tint = HionePink, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("UP NEXT QUEUE", color = HionePink, fontSize = 16.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp, fontFamily = AudiowideFont)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val count = player.mediaItemCount
                        val curIdx = player.currentMediaItemIndex
                        val remaining = (count - curIdx - 1).coerceAtLeast(0)
                        Text("$remaining tracks left", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.width(8.dp))
                        HapticIconButton(onClick = { showQueue = false }, flat = true) {
                            Icon(Icons.Default.Close, "Close Queue", tint = Color.White, modifier = Modifier.size(24.dp))
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Currently Playing Header Card
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Brush.horizontalGradient(listOf(Mascot.color(0xFF14403D), Mascot.color(0xFF0F2B2E))))
                        .border(1.dp, HioneTeal.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(46.dp).clip(RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                        if (art != null) androidx.compose.foundation.Image(art!!, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        else Icon(Icons.Default.MusicNote, null, tint = HioneTeal, modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("NOW PLAYING", color = HioneTealBright, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.Default.GraphicEq, null, tint = HioneTealBright, modifier = Modifier.size(12.dp))
                        }
                        Text(track.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(track.artist + "  ·  " + track.album, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Upcoming Queue Track List
                if (upcomingItems.isEmpty()) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.MusicNote, null, tint = Muted.copy(alpha = 0.5f), modifier = Modifier.size(48.dp))
                            Spacer(Modifier.height(8.dp))
                            Text("End of Queue", color = Muted, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text("Add more songs from Songs or Albums tab", color = Muted.copy(alpha = 0.85f), fontSize = 12.sp)
                        }
                    }
                } else {
                    Column(
                        Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                    ) {
                        upcomingItems.forEachIndexed { itemIndex, item ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        player.seekTo(item.queueIdx, 0L)
                                        showQueue = false
                                    }
                                    .padding(horizontal = 8.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Track Queue Index (#1, #2, #3...)
                                Text(
                                    "#${itemIndex + 1}",
                                    color = HioneTeal.copy(alpha = 0.8f),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.width(32.dp)
                                )

                                // Track Art Thumbnail
                                Box(
                                    Modifier.size(42.dp).clip(RoundedCornerShape(8.dp)).background(Mascot.color(0xFF0F2B2E)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (item.id != null) {
                                        AlbumArtImage(item.id, Modifier.fillMaxSize(), trackPath = item.path)
                                    } else {
                                        Icon(Icons.Default.MusicNote, null, tint = HioneTeal.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
                                    }
                                }

                                Spacer(Modifier.width(10.dp))

                                // Title & Artist + Album Details
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        item.title,
                                        color = Mascot.color(0xFFE8F4F2),
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "${item.artist}${if (item.album.isNotBlank()) "  ·  ${item.album}" else ""}",
                                        color = Muted,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Spacer(Modifier.width(6.dp))

                                // Tech Metrics & Duration Pills
                                Column(horizontalAlignment = Alignment.End) {
                                    if (item.durationMs > 0) {
                                        Text(
                                            fmtTime(item.durationMs),
                                            color = Muted,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (item.mime.isNotBlank()) {
                                            val fmt = item.mime.substringAfterLast('/').uppercase()
                                            Text(
                                                fmt,
                                                color = formatColor(item.mime),
                                                fontSize = 9.5.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        if (item.bits != null && item.bits > 0) {
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                "${item.bits}-BIT",
                                                color = TrackTech.color(item.bits),
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Black
                                            )
                                        } else if (item.bitrateKbps > 0) {
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                "${item.bitrateKbps}k",
                                                color = bitrateColor(item.bitrateKbps),
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold
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
    }

    if (showConnectModal) {
        HioneConnectModal(
            context = ctx,
            onClose = { showConnectModal = false }
        )
    }
    }
}

/**
 * The one place that decides which GPU engine draws the stage. projectM = the native Milkdrop
 * engine (ProjectMVisualizer.kt, NDK); SHADER = the GLES2 GLSL presets (visualizer/). A future
 * backend (projectM 4, a preset importer…) is one more branch here.
 */
@Composable
private fun StageVisualizer(
    engine: VizEngine,
    sessionId: Int,
    pmPreset: ProjectMPreset,
    shPreset: ShaderPreset,
    palette: ArtPalette,
    modifier: Modifier = Modifier
) {
    when (engine) {
        VizEngine.PROJECTM -> ProjectMVisualizerView(sessionId = sessionId, preset = pmPreset, modifier = modifier)
        VizEngine.SHADER -> HioneShaderVisualizerView(
            sessionId = sessionId, preset = shPreset,
            accent = palette.color1, accent2 = palette.color3, modifier = modifier
        )
    }
}

@Composable
fun HioneConnectModal(context: android.content.Context, onClose: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onClose)
    // Status dot = the API server's real running state (green up / grey down).
    val apiState by com.hione.player.api.HioneApiServer.state.collectAsState()
    val serverUp = apiState.running

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.75f))
            .clickable(onClick = onClose),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(20.dp))
                .background(Mascot.color(0xFF041017))
                .border(
                    1.5.dp,
                    Brush.horizontalGradient(listOf(HioneTeal, HionePink)),
                    RoundedCornerShape(20.dp)
                )
                .clickable(enabled = false) {}
                .padding(18.dp)
        ) {
            // Header
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Status dot = the API server's real running state (green up / grey down).
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (serverUp) Color(0xFF00FF88) else Color(0xFF666666))
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${Mascot.nameUpper} CONNECT",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = AudiowideFont,
                        letterSpacing = 1.sp
                    )
                }
                HapticIconButton(onClick = onClose, flat = true) {
                    Icon(Icons.Default.Close, "Close", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }

            Text(
                "Control playback from a phone or play on a TV. Both are off until you turn them on here.",
                color = Muted,
                fontSize = 11.5.sp,
                modifier = Modifier.padding(vertical = 4.dp)
            )

            Spacer(Modifier.height(12.dp))

            // The same card as Settings, Wi-Fi Remote: on/off, pairing code and QR, TV cast.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                com.hione.player.api.HioneApiSettingsCard(context)
            }
        }
    }
}

/**
 * A physical-feeling seek control: a recessed (embossed) groove with a raised, glossy fill whose
 * gradient subtly shifts hue with progress (teal → warmer) and dims when paused, plus a soft shimmer
 * that travels while playing, and a raised knob with a highlight/shadow. Drag or tap to seek.
 */
@Composable
fun EmbossedScrubber(
    pos: Long, dur: Long, playing: Boolean,
    onSeekPreview: (Long) -> Unit, onSeekCommit: (Long) -> Unit
) {
    val d = dur.coerceAtLeast(1L)
    val hapticCtx = androidx.compose.ui.platform.LocalContext.current   // seek detents
    val prog = (pos.toFloat() / d).coerceIn(0f, 1f)   // raw (works with device animations off)
    // Manual shimmer loop (ignores animator_duration_scale = 0).
    var shimmer by remember { mutableStateOf(0f) }
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        // Decorative shimmer, same idle gate as the heart pulses — pointless with the screen off.
        while (true) {
            if (IdleController.screenActive) { shimmer = (shimmer + 0.02f) % 1f; delay(32) } else delay(500)
        }
    }

    // Subtle color story: hue drifts teal→pink as the track progresses; paused desaturates.
    val base = lerp(HioneTeal, HionePink, prog * 0.55f)
    val active = if (playing) base else lerp(base, Muted, 0.55f)
    val brighter = lerp(active, Color.White, 0.32f)
    val darker = lerp(active, Color.Black, 0.36f)

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            // Edge-safe: a drag from the side bands is the system back swipe, not a scrub.
            .edgeSafePointerInput(d) { guard ->
                var frac = 0f
                detectHorizontalDragGesturesEdgeSafe(
                    guard,
                    onDragStart = { o -> frac = (o.x / size.width).coerceIn(0f, 1f); com.hione.player.Haptics.seekDetent(hapticCtx, (frac * d).toLong(), d, start = true); onSeekPreview((frac * d).toLong()) },
                    onDragEnd = { onSeekCommit((frac * d).toLong()) },
                    onDragCancel = { onSeekCommit((frac * d).toLong()) }
                ) { change, _ -> frac = (change.position.x / size.width).coerceIn(0f, 1f); com.hione.player.Haptics.seekDetent(hapticCtx, (frac * d).toLong(), d); onSeekPreview((frac * d).toLong()) }
            }
            .pointerInput(d) {
                detectTapGestures { o -> onSeekCommit(((o.x / size.width).coerceIn(0f, 1f) * d).toLong()) }
            }
    ) {
        val w = size.width; val cy = size.height / 2f
        val th = 14.dp.toPx(); val r = th / 2f
        val fillW = (w * prog).coerceIn(0f, w)

        // Recessed groove (embossed inward): dark base + top inner shadow + bottom light edge.
        drawRoundRect(Mascot.color(0xFF06201F), topLeft = Offset(0f, cy - r), size = Size(w, th), cornerRadius = CornerRadius(r, r))
        drawRoundRect(
            Brush.verticalGradient(listOf(Color(0x77000000), Color(0x00000000)), startY = cy - r, endY = cy + r * 0.4f),
            topLeft = Offset(0f, cy - r), size = Size(w, th), cornerRadius = CornerRadius(r, r)
        )
        drawLine(Color(0x1FFFFFFF), Offset(r, cy + r - 1.2f), Offset(w - r, cy + r - 1.2f), 1.4f, cap = StrokeCap.Round)

        // Raised glossy fill: vertical gradient (top highlight → base → bottom shadow).
        if (fillW > r) {
            drawRoundRect(
                Brush.verticalGradient(listOf(brighter, active, darker), startY = cy - r, endY = cy + r),
                topLeft = Offset(0f, cy - r), size = Size(fillW, th), cornerRadius = CornerRadius(r, r)
            )
            drawLine(brighter.copy(alpha = 0.75f), Offset(r, cy - r + 2f), Offset(fillW - r, cy - r + 2f), 1.4f, cap = StrokeCap.Round)
            if (playing) {   // soft travelling shimmer
                val sx = shimmer * fillW
                drawCircle(Color.White.copy(alpha = 0.12f), r * 1.4f, Offset(sx.coerceIn(0f, fillW), cy))
            }
        }

        // Raised knob with drop shadow + rim light + highlight.
        val tx = (w * prog).coerceIn(r, w - r)
        val kr = 11.dp.toPx()
        drawCircle(Color(0x66000000), kr + 2.5f, Offset(tx, cy + 2f))
        drawCircle(Brush.verticalGradient(listOf(brighter, active, darker), startY = cy - kr, endY = cy + kr), kr, Offset(tx, cy))
        drawCircle(Color.White.copy(alpha = 0.55f), kr, Offset(tx, cy), style = Stroke(1.2f))
        drawCircle(Color.White.copy(alpha = 0.6f), kr * 0.30f, Offset(tx - kr * 0.32f, cy - kr * 0.32f))
    }
}

@Composable private fun GlassIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, tint: Color, onClick: () -> Unit
) {
    val ctx = LocalContext.current
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.45f))
            .clickable { Haptics.tick(ctx); onClick() },
        contentAlignment = Alignment.Center
    ) { Icon(icon, desc, tint = tint, modifier = Modifier.size(22.dp)) }
}

// Embossed 3D metric pill: raised tinted face lit from the top, specular hairline, drop shadow.
@Composable fun DataChip(text: String, color: Color) = Text(
    text, color = color, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, fontFamily = OrbitronFont, letterSpacing = 0.5.sp,
    modifier = Modifier
        .drawBehind {
            val rr = CornerRadius(8.dp.toPx(), 8.dp.toPx())
            drawRoundRect(Color(0x4D000000), topLeft = androidx.compose.ui.geometry.Offset(0f, 1.8f), size = size, cornerRadius = rr)
            drawRoundRect(
                Brush.verticalGradient(listOf(color.copy(alpha = 0.38f), color.copy(alpha = 0.10f))),
                cornerRadius = rr)
            drawRoundRect(
                Brush.verticalGradient(listOf(Color(0x59FFFFFF), Color(0x00FFFFFF)), endY = size.height * 0.65f),
                cornerRadius = rr, style = Stroke(1.1f))
        }
        .padding(horizontal = 9.dp, vertical = 4.dp)
)

private fun qualityTier(t: Track): String {
    val fmt = t.mime.substringAfterLast('/').lowercase()
    return when {
        fmt in setOf("dsd", "dsf", "dff") -> "DSD"
        t.bitrateKbps >= 2500 -> "HI-RES"
        t.bitrateKbps >= 900 || fmt in setOf("flac", "x-flac", "wav", "x-wav", "alac") -> "LOSSLESS"
        t.bitrateKbps in 1 until 900 -> "LOSSY"
        else -> "AUDIO"
    }
}

private fun heartPath(w: Float, h: Float): androidx.compose.ui.graphics.Path =
    androidx.compose.ui.graphics.Path().apply {
        moveTo(0.5f * w, 0.86f * h)
        cubicTo(0.34f * w, 0.72f * h, 0.06f * w, 0.54f * h, 0.06f * w, 0.31f * h)
        cubicTo(0.06f * w, 0.11f * h, 0.33f * w, 0.06f * h, 0.5f * w, 0.27f * h)
        cubicTo(0.67f * w, 0.06f * h, 0.94f * w, 0.11f * h, 0.94f * w, 0.31f * h)
        cubicTo(0.94f * w, 0.54f * h, 0.66f * w, 0.72f * h, 0.5f * w, 0.86f * h)
        close()
    }

/** Subtle "lub-dub" heartbeat envelope (two soft thumps per cycle), returns ~0..1. */
private fun heartbeat(x: Float): Float {
    fun bump(c: Float, wdt: Float) = kotlin.math.exp((-((x - c) * (x - c)) / (2 * wdt * wdt)).toDouble()).toFloat()
    return bump(0.10f, 0.040f) + 0.6f * bump(0.26f, 0.05f)
}

/** The three like tiers (LikeStore) — each gets a visually distinct heart so "I liked this song"
 *  and "I liked this whole album/artist" never look identical at a glance, even though they're all
 *  the same underlying rainbow-heart animation. TRACK is the original glyph, unchanged. */
enum class LikeTier { TRACK, ALBUM, ARTIST }

@Composable
fun RainbowHeart(liked: Boolean, size: androidx.compose.ui.unit.Dp = 54.dp, onToggle: () -> Unit) =
    TieredRainbowHeart(LikeTier.TRACK, liked, size, onToggle)

/** Ring-wrapped heart — "the whole collection wrapped together," a step up from a single track. */
@Composable
fun AlbumRainbowHeart(liked: Boolean, size: androidx.compose.ui.unit.Dp = 54.dp, onToggle: () -> Unit) =
    TieredRainbowHeart(LikeTier.ALBUM, liked, size, onToggle)

/** Heart with a spark accent — "everything this person makes," the widest tier. */
@Composable
fun ArtistRainbowHeart(liked: Boolean, size: androidx.compose.ui.unit.Dp = 54.dp, onToggle: () -> Unit) =
    TieredRainbowHeart(LikeTier.ARTIST, liked, size, onToggle)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TieredRainbowHeart(
    tier: LikeTier,
    liked: Boolean,
    size: androidx.compose.ui.unit.Dp = 54.dp,
    onToggle: () -> Unit,
    earnable: Boolean = false,
    badgeCount: Int = 0,
    onLongPress: (() -> Unit)? = null
) {
    val ctx = LocalContext.current
    // PERF (scroll jank, 2026-09-17) — this was the single most expensive thing a liked list row
    // did. `phase`/`beat` tick at 60 Hz for as long as the heart is liked and on screen, and the
    // heartbeat scale below USED to be computed in this composable's body
    // (`val pulse = ...; .scale(pulse)`). That made `beat` a COMPOSITION-phase read, so every
    // liked heart recomposed 60 times a second — and with a heart on every liked artist/album/
    // track row plus the docked now-playing bar, a screenful of liked rows meant several hundred
    // full recompositions per second (each one re-allocating the Canvas lambda and re-running its
    // ~20 path/gradient draw ops) on the same main thread the list scrolls on.
    //
    // Both values are now read ONLY from the layer/draw phase: `beat` inside graphicsLayer{} and
    // `phase` inside the Canvas draw lambda. The animation is pixel-identical; it just no longer
    // recomposes anything. They're also float states now, so the 60 Hz ticks stop boxing a Float
    // per frame per heart.
    val phase = remember { mutableFloatStateOf(0f) }
    val beat = remember { mutableFloatStateOf(0f) }
    // PERF (2026-09-19): the pulse is for the big Now Playing heart only. A liked heart in a LIST
    // ROW used to run this 60 Hz loop too, and each tick re-recorded that row's graphics layer
    // and its Canvas: a screen with six liked artists was six layer re-records per frame, forever,
    // whether or not anything else was happening. That is a permanent tax on scrolling for an
    // animation nobody is looking at while they scroll. Small hearts are static now; the liked
    // state still reads as filled + glowing, it just does not beat.
    val animated = liked && size >= 36.dp
    LaunchedEffect(animated) {
        if (!animated) return@LaunchedEffect
        while (true) {
            if (IdleController.screenActive) {
                phase.floatValue = (phase.floatValue + 2.4f) % 360f
                beat.floatValue = (beat.floatValue + 0.014f) % 1f
                delay(16)
            } else delay(500)
        }
    }
    val label = when (tier) { LikeTier.TRACK -> "song"; LikeTier.ALBUM -> "album"; LikeTier.ARTIST -> "artist" }
    Box(
        Modifier
            .size(size)
            .graphicsLayer {
                val pulse = if (animated) 1f + 0.065f * heartbeat(beat.floatValue) else 1f
                scaleX = pulse; scaleY = pulse
            }
            .semantics { contentDescription = "${if (liked) "Unlike" else "Like"} $label"; role = Role.Checkbox; toggleableState = ToggleableState(liked) }
            .combinedClickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onLongClick = onLongPress?.let { { Haptics.heavy(ctx); it() } },
                onClick = { if (liked) Haptics.tick(ctx) else Haptics.heavy(ctx); onToggle() }   // like = firm, unlike = light
            ),
        contentAlignment = Alignment.Center
    ) {
        // Earnable-this-play ring: a soft green glow telling the user a heart is ready to spend.
        if (earnable) {
            Canvas(Modifier.size(size)) {
                drawCircle(Color(0x5500E676), radius = this.size.minDimension * 0.5f)
                drawCircle(Color(0xCC00E676), radius = this.size.minDimension * 0.48f, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.2f))
            }
        }
        Canvas(Modifier.size(size * 0.84f)) {
            val w = this.size.width
            val h = this.size.height
            val p = heartPath(w, h)
            // Draw-phase read (see the note at the top of this function): ticking `phase` now
            // invalidates only this Canvas's drawing, never composition.
            val ph = phase.floatValue

            if (liked) {
                // 1. 3D Physical Drop Shadow underneath the heart — same geometry as `p`, which
                //    used to be built a second time from scratch on every single frame.
                drawContext.canvas.save()
                drawContext.canvas.translate(0f, 3.5f)
                drawPath(p, Color(0x99000000))
                drawContext.canvas.restore()

                // 2. Ambient Chromatic Bloom / Aura behind the heart
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(
                            Color.hsv((ph + 120f) % 360f, 0.9f, 1f, 0.45f),
                            Color.Transparent
                        ),
                        center = Offset(w * 0.5f, h * 0.45f),
                        radius = w * 0.75f
                    )
                )

                // 3. Dynamic Rotating Rainbow Chromatic Core
                val cols = when (tier) {
                    LikeTier.TRACK -> (0..6).map { Color.hsv(((it * 52) + ph) % 360f, 0.88f, 1f) }
                    LikeTier.ALBUM -> (0..6).map { Color.hsv(((it * 52) + ph * 1.4f + 40f) % 360f, 0.80f, 0.98f) }
                    LikeTier.ARTIST -> (0..7).map { Color.hsv(((it * 46) + ph * 0.7f + 200f) % 360f, 0.92f, 1f) }
                }
                val brush = when (tier) {
                    LikeTier.TRACK -> Brush.linearGradient(cols, Offset(0f, h), Offset(w, 0f))
                    LikeTier.ALBUM -> Brush.linearGradient(cols, Offset(w, h), Offset(0f, 0f))
                    LikeTier.ARTIST -> Brush.radialGradient(cols, center = Offset(w * 0.5f, h * 0.42f), radius = w * 0.75f)
                }
                drawPath(p, brush)

                // 4. 3D Embossed Convex Shading & Specular Dome (Top-Left Light Source)
                // Top-left specular highlight rim (raised 3D crest)
                val highlightBrush = Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = 0.92f),
                        Color.White.copy(alpha = 0.35f),
                        Color.Transparent
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(w, h)
                )
                drawPath(p, highlightBrush, style = Stroke(width = 2.4f))

                // Bottom-right shadow rim (sunken 3D bottom bevel)
                val shadowRimBrush = Brush.linearGradient(
                    listOf(
                        Color.Transparent,
                        Color(0x80000000)
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(w, h)
                )
                drawPath(p, shadowRimBrush, style = Stroke(width = 2.2f))

                // Upper dual-lobe 3D gloss gleams
                drawOval(
                    brush = Brush.radialGradient(
                        listOf(Color.White.copy(alpha = 0.65f), Color.Transparent),
                        center = Offset(w * 0.28f, h * 0.26f),
                        radius = w * 0.20f
                    ),
                    topLeft = Offset(w * 0.16f, h * 0.16f),
                    size = Size(w * 0.24f, h * 0.18f)
                )
                drawOval(
                    brush = Brush.radialGradient(
                        listOf(Color.White.copy(alpha = 0.50f), Color.Transparent),
                        center = Offset(w * 0.70f, h * 0.26f),
                        radius = w * 0.18f
                    ),
                    topLeft = Offset(w * 0.60f, h * 0.16f),
                    size = Size(w * 0.20f, h * 0.16f)
                )

                // Distinct tier accents
                val minDim = minOf(w, h)
                when (tier) {
                    LikeTier.ALBUM -> {
                        drawCircle(
                            color = Color.White.copy(alpha = 0.75f),
                            radius = minDim * 0.64f,
                            center = Offset(w / 2f, h / 2f),
                            style = Stroke(width = 1.6f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4f, 4f)))
                        )
                    }
                    LikeTier.ARTIST -> {
                        val cx = w * 0.84f
                        val cy = h * 0.14f
                        val r = minDim * 0.16f
                        val spark = androidx.compose.ui.graphics.Path().apply {
                            moveTo(cx, cy - r)
                            lineTo(cx + r * 0.28f, cy - r * 0.28f)
                            lineTo(cx + r, cy)
                            lineTo(cx + r * 0.28f, cy + r * 0.28f)
                            lineTo(cx, cy + r)
                            lineTo(cx - r * 0.28f, cy + r * 0.28f)
                            lineTo(cx - r, cy)
                            lineTo(cx - r * 0.28f, cy - r * 0.28f)
                            close()
                        }
                        drawPath(spark, Color.White.copy(alpha = 0.95f))
                    }
                    LikeTier.TRACK -> {}
                }
            } else {
                // 3D Debossed Engraved Cavity when unliked
                // Top inset shadow — reuses `p` instead of rebuilding the identical path.
                drawContext.canvas.save()
                drawContext.canvas.translate(0f, 1.8f)
                drawPath(p, Color(0x95000000))
                drawContext.canvas.restore()

                // Soft teal halo so the un-liked heart still reads as a heart against the
                // dark card (the old #1B2F33-on-#0C0D10 deboss was near-invisible until tapped).
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Mascot.color(0xFFFFAB2E).copy(alpha = 0.22f), Color.Transparent),
                        center = Offset(w * 0.5f, h * 0.48f),
                        radius = w * 0.72f
                    )
                )

                // Satin metallic fill - lifted a few stops so it separates from the background
                val debossFill = Brush.verticalGradient(
                    listOf(
                        Mascot.color(0xFF2E4F55),
                        Mascot.color(0xFF16292E)
                    )
                )
                drawPath(p, debossFill)

                // Bottom bevel highlight (light catching the bottom rim of the engraved cavity)
                val bottomChamfer = Brush.verticalGradient(
                    listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = 0.45f)
                    )
                )
                drawPath(p, bottomChamfer, style = Stroke(width = 2.0f))

                // Crisp Hione-teal outline - the main visibility cue
                drawPath(p, Mascot.color(0xFFFFAB2E).copy(alpha = 0.95f), style = Stroke(width = 2.8f))
            }
        }
        // Cumulative heart-score badge — appears once a track has more than one heart (played
        // through and hearted multiple times). The count is what feeds TasteEngine.
        if (badgeCount > 1) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 3.dp, y = (-2).dp)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(Mascot.color(0xFFFF2277)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (badgeCount > 99) "99+" else badgeCount.toString(),
                    color = Color.White, fontSize = 8.5.sp, fontWeight = FontWeight.Black, maxLines = 1
                )
            }
        }
    }
}

/**
 * Now-Playing heart with the earn-per-play model: a heart is earnable only after the current play
 * passes 94% without skipping (HionePlayQualifier). Tap when earnable = +1 to the cumulative heart
 * score; long-press = clear all hearts. Shows the count badge and an earnable glow ring.
 */
@Composable
fun NowPlayingHeart(track: Track, size: androidx.compose.ui.unit.Dp = 54.dp) {
    val ctx = LocalContext.current
    var count by remember(track.id) { mutableStateOf(LikeStore.heartCount(ctx, track.id)) }
    var earnable by remember(track.id) { mutableStateOf(HionePlayQualifier.isHeartable(track.id)) }
    // Reading the state lists here is what makes the heart follow an album like without a poll.
    val origin = LikeStore.likeOrigin(ctx, track)
    val likedNow = origin == LikeStore.LikeOrigin.TRACK || origin == LikeStore.LikeOrigin.ALBUM
    // Cheap 1s poll (same cadence as the progress bar) keeps earnable/count fresh across the play.
    LaunchedEffect(track.id) {
        while (true) {
            earnable = HionePlayQualifier.isHeartable(track.id)
            count = LikeStore.heartCount(ctx, track.id)
            delay(1000)
        }
    }
    TieredRainbowHeart(
        // Inherited from the album shows the ALBUM ring, so "liked because the album is" is
        // distinguishable at a glance from "liked on its own" without a second control.
        tier = if (origin == LikeStore.LikeOrigin.ALBUM) LikeTier.ALBUM else LikeTier.TRACK,
        liked = likedNow,
        size = size,
        earnable = earnable,
        badgeCount = count,
        onToggle = {
            // Toggles the EFFECTIVE state. Un-hearting a track the album covers records a refusal
            // rather than doing nothing; hearting one promotes it to a like of its own.
            LikeStore.toggleEffective(ctx, track)
            count = LikeStore.heartCount(ctx, track.id); earnable = HionePlayQualifier.isHeartable(track.id)
        },
        onLongPress = {
            LikeStore.clearOverride(ctx, track)
            count = LikeStore.heartCount(ctx, track.id); earnable = HionePlayQualifier.isHeartable(track.id)
            val msg = if (LikeStore.isAlbumLiked(track.artist.ifBlank { track.albumArtist }, track.album, ctx))
                "Following the album again" else "Hearts cleared"
            android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    )
}

/** Live FFT visualizer via the Android Visualizer API, drawn as a Hione-teal mirrored bar spectrum. */
@Composable
private fun VisualizerView(sessionId: Int, modifier: Modifier = Modifier) {
    val bars = remember { mutableStateOf(FloatArray(48)) }
    DisposableEffect(sessionId) {
        var viz: Visualizer? = null
        try {
            viz = Visualizer(sessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, wf: ByteArray?, sr: Int) {}
                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, sr: Int) {
                        if (fft == null) return
                        val n = bars.value.size
                        val out = FloatArray(n)
                        val step = (fft.size / 2) / n
                        for (i in 0 until n) {
                            val idx = i * step * 2 + 2
                            val re = if (idx < fft.size) fft[idx].toInt() else 0
                            val im = if (idx + 1 < fft.size) fft[idx + 1].toInt() else 0
                            out[i] = (hypot(re.toFloat(), im.toFloat()) / 90f).coerceIn(0f, 1f)
                        }
                        bars.value = out
                    }
                }, Visualizer.getMaxCaptureRate() / 2, false, true)
                enabled = true
            }
        } catch (_: Throwable) {}
        onDispose { try { viz?.enabled = false; viz?.release() } catch (_: Throwable) {} }
    }
    val brush = Brush.verticalGradient(listOf(HionePink, HioneTealBright, HioneTeal))
    Canvas(modifier.background(Mascot.color(0xFF06171A))) {
        val n = bars.value.size
        val gap = 3.dp.toPx()
        val bw = (size.width - gap * (n - 1)) / n
        val midY = size.height / 2f
        for (i in 0 until n) {
            val h = (bars.value[i] * size.height * 0.9f).coerceAtLeast(2f)
            val x = i * (bw + gap)
            drawRoundRect(
                brush = brush,
                topLeft = Offset(x, midY - h / 2f),
                size = androidx.compose.ui.geometry.Size(bw, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(bw / 2, bw / 2)
            )
        }
    }
}


private fun fmtTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

@Composable
private fun StreamingToTargetStage(
    targetName: String,
    targetAddress: String,
    track: Track,
    accent: Color,
    accent2: Color,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "castPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    val ringAlpha by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ringAlpha"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        accent.copy(alpha = 0.22f),
                        Mascot.color(0xFF08181B)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        ) {
            // Pulsing TV / Cast Icon Emblem
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(96.dp)
            ) {
                // Expanding ripple ring
                Box(
                    modifier = Modifier
                        .size(86.dp * pulseScale)
                        .clip(CircleShape)
                        .border(1.5.dp, accent.copy(alpha = ringAlpha * 0.6f), CircleShape)
                )
                // Middle glow circle
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.15f))
                        .border(1.5.dp, accent.copy(alpha = 0.85f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Tv,
                        contentDescription = "Streaming to TV",
                        tint = accent,
                        modifier = Modifier.size(34.dp)
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // Subtitle
            Text(
                text = "STREAMING TO",
                color = accent.copy(alpha = 0.85f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.5.sp
            )

            Spacer(Modifier.height(4.dp))

            // TV / Target Name
            Text(
                text = targetName,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (targetAddress.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = targetAddress,
                    color = Color(0x99FFFFFF),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(Modifier.height(14.dp))

            // Bit-Perfect Lossless Telemetry Pill
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x33000000))
                    .border(1.dp, accent2.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF00FF88))
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "BIT-PERFECT DIRECT LINK · 44.1 kHz",
                        color = Color(0xFFD0F0EA),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    )
                }
            }
        }
    }
}

