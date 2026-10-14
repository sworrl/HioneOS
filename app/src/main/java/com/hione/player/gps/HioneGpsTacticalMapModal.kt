package com.hione.player.gps

import com.hione.player.Mascot
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hione.player.AudiowideFont
import com.hione.player.OrbitronFont
import com.hione.player.ui.swipeUpFromBottomToDismiss
import com.hione.player.ui.detectDragGesturesEdgeSafe
import com.hione.player.ui.edgeSafePointerInput
import com.hione.player.weather.HioneWeatherService
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.*

/**
 * Offline-First Tactical Topo Map Tile Cache Manager.
 * Pre-caches a 50km radius bounding box of high-detail topographical map tiles
 * to local device storage whenever a data connection is detected.
 */
object HioneTopoTileCache {
    private const val TAG = "HioneTopoTileCache"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastCachedLat: Double = 0.0
    private var lastCachedLon: Double = 0.0
    private var isDownloading = false

    fun getCacheDir(ctx: Context): File {
        val dir = File(ctx.cacheDir, "hione_map_tiles")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getTileCount(ctx: Context): Int {
        return getCacheDir(ctx).listFiles()?.size ?: 0
    }

    fun getCacheSizeBytes(ctx: Context): Long {
        return getCacheDir(ctx).listFiles()?.sumOf { it.length() } ?: 0L
    }

    /**
     * Converts GPS Lat/Lon to OpenStreetMap / Topo Tile X/Y at given zoom level.
     */
    fun latLonToTile(lat: Double, lon: Double, zoom: Int): Pair<Int, Int> {
        val latRad = Math.toRadians(lat)
        val n = 1 shl zoom
        val xtile = ((lon + 180.0) / 360.0 * n).toInt()
        val ytile = ((1.0 - asinh(tan(latRad)) / Math.PI) / 2.0 * n).toInt()
        return xtile to ytile
    }

    fun getCachedTile(ctx: Context, zoom: Int, x: Int, y: Int): Bitmap? {
        val file = File(getCacheDir(ctx), "tile_${zoom}_${x}_${y}.png")
        if (file.exists() && file.length() > 0) {
            return try {
                BitmapFactory.decodeFile(file.absolutePath)
            } catch (_: Throwable) { null }
        }
        return null
    }

    /**
     * Pre-cache 50km surroundings when online.
     */
    fun prefetch50kmRadius(ctx: Context, lat: Double, lon: Double) {
        if (lat == 0.0 && lon == 0.0) return
        if (isDownloading) return
        val distMovedKm = hypot((lat - lastCachedLat) * 111.0, (lon - lastCachedLon) * 85.0)
        if (distMovedKm < 20.0 && getTileCount(ctx) > 20) return

        isDownloading = true
        lastCachedLat = lat
        lastCachedLon = lon

        scope.launch {
            try {
                val cacheDir = getCacheDir(ctx)
                // Download zoom levels 11, 12, 13
                val zooms = listOf(11, 12, 13)
                for (z in zooms) {
                    val (cx, cy) = latLonToTile(lat, lon, z)
                    val radiusTiles = if (z == 11) 2 else if (z == 12) 3 else 4
                    for (dx in -radiusTiles..radiusTiles) {
                        for (dy in -radiusTiles..radiusTiles) {
                            val tx = cx + dx
                            val ty = cy + dy
                            val file = File(cacheDir, "tile_${z}_${tx}_${ty}.png")
                            if (!file.exists()) {
                                fetchTile(z, tx, ty, file)
                                delay(40)
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Topo tile prefetch exception", e)
            } finally {
                isDownloading = false
            }
        }
    }

    private fun fetchTile(z: Int, x: Int, y: Int, dest: File) {
        try {
            // Carto Dark / OpenTopoMap mirror
            val urlStr = "https://basemaps.cartocdn.com/dark_all/$z/$x/$y.png"
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.setRequestProperty("User-Agent", "HatsuneHioneDAP/2.0")
            if (conn.responseCode == 200) {
                conn.inputStream.use { input ->
                    FileOutputStream(dest).use { output ->
                        input.copyTo(output)
                    }
                }
            }
        } catch (_: Throwable) {}
    }
}

/**
 * Standalone GPS & Tactical Topographical Map Modal.
 * High-density cyber telemetry cockpit that fits completely on $480 \times 854$ viewport without scrolling.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HioneGpsTacticalMapModal(
    onDismissRequest: () -> Unit
) {
    val ctx = LocalContext.current
    val weatherState by HioneWeatherService.state.collectAsState()
    val gps = weatherState.gps

    var selectedTab by remember { mutableIntStateOf(0) } // 0: Tactical Map, 1: Telemetry HUD
    var mapZoom by remember { mutableIntStateOf(12) }
    var panOffsetX by remember { mutableFloatStateOf(0f) }
    var panOffsetY by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(gps.latitude, gps.longitude) {
        if (gps.isLocked && gps.latitude != 0.0) {
            HioneTopoTileCache.prefetch50kmRadius(ctx, gps.latitude, gps.longitude)
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Mascot.color(0xE6020A0E))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onDismissRequest() }
                .swipeUpFromBottomToDismiss(onDismiss = onDismissRequest),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.96f)
                    .fillMaxHeight(0.94f)
                    .clip(CutCornerShape(18.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(Mascot.color(0xFF071922), Mascot.color(0xFF030D12))
                        )
                    )
                    .border(
                        1.2.dp,
                        Brush.linearGradient(
                            listOf(Mascot.color(0xFFFFC56B), Mascot.color(0xFF6FB0FF), Mascot.color(0xFFFFC56B))
                        ),
                        CutCornerShape(18.dp)
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {}
                    .padding(10.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Header Bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(CutCornerShape(6.dp))
                                    .background(Mascot.color(0xFFFFC56B).copy(alpha = 0.2f))
                                    .border(1.dp, Mascot.color(0xFFFFC56B), CutCornerShape(6.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.LocationOn,
                                    contentDescription = "GPS",
                                    tint = Mascot.color(0xFFFFC56B),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "GPS",
                                    color = Mascot.color(0xFFFFC56B),
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = AudiowideFont,
                                    letterSpacing = 0.8.sp
                                )
                                // Name the fix source the service actually used. isLocked is also
                                // true for a manual override, a restored last-known fix and an IP
                                // geolocation, so the old unconditional "SATELLITE 3D FIX" /
                                // "ACQUIRING SATELLITE CONSTELLATION..." claimed a GNSS fix (and a
                                // satellite search) that may never have happened.
                                val fixLabel = when {
                                    !gps.isLocked -> "NO FIX"
                                    gps.provider.equals("gps", true) -> "GNSS SATELLITE FIX"
                                    gps.provider.equals("fused", true) -> "FUSED FIX (GNSS / NETWORK)"
                                    gps.provider.equals("network", true) -> "NETWORK FIX (NO SATELLITES)"
                                    gps.provider.isBlank() -> "FIX SOURCE UNKNOWN"
                                    else -> "FIX: ${gps.provider.uppercase()}"
                                }
                                Text(
                                    text = fixLabel,
                                    color = if (gps.isLocked) Mascot.color(0xFF00FFCC) else Color(0xFFFFB300),
                                    fontSize = 7.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = OrbitronFont
                                )
                            }
                        }

                        // Close Button with Tactile Feedback
                        TactileIconButton(
                            onClick = onDismissRequest,
                            modifier = Modifier.size(26.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = Mascot.color(0xFF6FB0FF),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(6.dp))

                    // Tab Selector (Tactical Map vs Telemetry HUD)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(28.dp)
                            .clip(CutCornerShape(8.dp))
                            .background(Mascot.color(0xFF051218))
                            .border(0.8.dp, Mascot.color(0xFFFFC56B).copy(alpha = 0.35f), CutCornerShape(8.dp))
                            .padding(2.dp)
                    ) {
                        TabButton(
                            title = "TOPO MAP",
                            isSelected = selectedTab == 0,
                            modifier = Modifier.weight(1f)
                        ) { selectedTab = 0 }

                        TabButton(
                            title = "DETAILS",
                            isSelected = selectedTab == 1,
                            modifier = Modifier.weight(1f)
                        ) { selectedTab = 1 }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Main Content (Non-Scrolling Viewport)
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        if (selectedTab == 0) {
                            TacticalMapView(
                                gps = gps,
                                zoom = mapZoom,
                                panX = panOffsetX,
                                panY = panOffsetY,
                                onPan = { dx, dy ->
                                    panOffsetX += dx
                                    panOffsetY += dy
                                },
                                onZoomIn = { if (mapZoom < 14) mapZoom++ },
                                onZoomOut = { if (mapZoom > 10) mapZoom-- },
                                onCenter = {
                                    panOffsetX = 0f
                                    panOffsetY = 0f
                                }
                            )
                        } else {
                            GpsTelemetryHudView(gps = gps)
                        }
                    }

                    Spacer(Modifier.height(6.dp))

                    // Bottom Status Strip
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(CutCornerShape(6.dp))
                            .background(Mascot.color(0xFF040E14))
                            .border(0.6.dp, Mascot.color(0xFFFFC56B).copy(alpha = 0.25f), CutCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "CACHE: ${HioneTopoTileCache.getTileCount(ctx)} TILES (${HioneTopoTileCache.getCacheSizeBytes(ctx) / 1024} KB)",
                            color = Mascot.color(0xFFFFD59A),
                            fontSize = 7.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = OrbitronFont
                        )
                        Text(
                            // No reported accuracy = say so. "SEARCHING FIX" claimed an in-progress
                            // satellite search that nothing here actually observes.
                            text = if (gps.accuracyM > 0) "ACCURACY: ±${"%.1f".format(gps.accuracyM)}M" else "ACCURACY: —",
                            color = if (gps.accuracyM in 0.1f..15f) Mascot.color(0xFF00FFCC) else Color(0xFFFFB300),
                            fontSize = 7.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = OrbitronFont
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TacticalMapView(
    gps: HioneWeatherService.GpsTelemetry,
    zoom: Int,
    panX: Float,
    panY: Float,
    onPan: (Float, Float) -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onCenter: () -> Unit
) {
    val ctx = LocalContext.current
    val infiniteTransition = rememberInfiniteTransition(label = "gpsPulse")
    val pulseRadius by infiniteTransition.animateFloat(
        initialValue = 12f,
        targetValue = 28f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(CutCornerShape(10.dp))
            .background(Mascot.color(0xFF02090D))
            .border(1.dp, Mascot.color(0xFFFFC56B).copy(alpha = 0.4f), CutCornerShape(10.dp))
            // Edge-safe: the map fills the modal, so a pan that starts in a window edge band
            // (side = back, bottom = home, top = shade) is the nav layer's — not a map pan.
            .edgeSafePointerInput(Unit) { guard ->
                detectDragGesturesEdgeSafe(guard) { change, dragAmount ->
                    change.consume()
                    onPan(dragAmount.x, dragAmount.y)
                }
            }
    ) {
        // Map Canvas with Contours / Cached Tiles
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx = w / 2f + panX
            val cy = h / 2f + panY

            // 1. Draw Tactical Topo Grid
            val gridSize = 40f
            for (gx in 0..(w.toInt() / gridSize.toInt() + 1)) {
                val x = (gx * gridSize + (panX % gridSize))
                drawLine(
                    color = Mascot.color(0xFFFFC56B).copy(alpha = 0.08f),
                    start = Offset(x, 0f),
                    end = Offset(x, h),
                    strokeWidth = 0.8f
                )
            }
            for (gy in 0..(h.toInt() / gridSize.toInt() + 1)) {
                val y = (gy * gridSize + (panY % gridSize))
                drawLine(
                    color = Mascot.color(0xFFFFC56B).copy(alpha = 0.08f),
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = 0.8f
                )
            }

            // 2. Draw Cached Offline Topo Tiles if available
            if (gps.latitude != 0.0 && gps.longitude != 0.0) {
                val (tileX, tileY) = HioneTopoTileCache.latLonToTile(gps.latitude, gps.longitude, zoom)
                for (dx in -1..1) {
                    for (dy in -1..1) {
                        val bmp = HioneTopoTileCache.getCachedTile(ctx, zoom, tileX + dx, tileY + dy)
                        if (bmp != null) {
                            val imgBmp = bmp.asImageBitmap()
                            val destX = cx + (dx * 256f) - 128f
                            val destY = cy + (dy * 256f) - 128f
                            drawImage(
                                image = imgBmp,
                                dstOffset = androidx.compose.ui.unit.IntOffset(destX.toInt(), destY.toInt()),
                                dstSize = androidx.compose.ui.unit.IntSize(256, 256),
                                alpha = 0.85f
                            )
                        }
                    }
                }
            }

            // 3. Draw Procedural Topo Contour Rings
            for (ring in 1..4) {
                val r = ring * 60f
                drawCircle(
                    color = Mascot.color(0xFFFFC56B).copy(alpha = 0.12f),
                    radius = r,
                    center = Offset(cx, cy),
                    style = Stroke(width = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                )
            }

            // 4. Draw Crosshair Reticle
            drawLine(
                color = Mascot.color(0xFFFFC56B).copy(alpha = 0.4f),
                start = Offset(cx - 20f, cy),
                end = Offset(cx + 20f, cy),
                strokeWidth = 1.2f
            )
            drawLine(
                color = Mascot.color(0xFFFFC56B).copy(alpha = 0.4f),
                start = Offset(cx, cy - 20f),
                end = Offset(cx, cy + 20f),
                strokeWidth = 1.2f
            )

            // 5. GPS Pulse Accuracy Disc & Hero Beacon
            drawCircle(
                color = Mascot.color(0xFFFFC56B).copy(alpha = 0.25f * (1f - (pulseRadius - 12f) / 16f)),
                radius = pulseRadius,
                center = Offset(cx, cy)
            )
            drawCircle(
                color = Mascot.color(0xFF6FB0FF),
                radius = 6.5f,
                center = Offset(cx, cy)
            )
            drawCircle(
                color = Color.White,
                radius = 3f,
                center = Offset(cx, cy)
            )
        }

        // Top Overlay: Live Mini Coordinates
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 6.dp)
                .clip(CutCornerShape(6.dp))
                .background(Mascot.color(0xCC05141C))
                .border(0.6.dp, Mascot.color(0xFFFFC56B).copy(alpha = 0.5f), CutCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Text(
                text = "LAT: ${"%.5f".format(gps.latitude)}° | LON: ${"%.5f".format(gps.longitude)}° | ALT: ${gps.altitudeM.toInt()}M",
                color = Color.White,
                fontSize = 8.5.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = OrbitronFont
            )
        }

        // Bottom Map Controls
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TactileIconButton(onClick = onZoomIn, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.Add, "Zoom In", tint = Mascot.color(0xFFFFC56B), modifier = Modifier.size(16.dp))
            }
            TactileIconButton(onClick = onZoomOut, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.Remove, "Zoom Out", tint = Mascot.color(0xFFFFC56B), modifier = Modifier.size(16.dp))
            }
            TactileIconButton(onClick = onCenter, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.MyLocation, "Center", tint = Mascot.color(0xFF6FB0FF), modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun GpsTelemetryHudView(gps: HioneWeatherService.GpsTelemetry) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(CutCornerShape(10.dp))
            .background(Mascot.color(0xFF02090D))
            .border(1.dp, Mascot.color(0xFFFFC56B).copy(alpha = 0.4f), CutCornerShape(10.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Row 1: Primary Coordinates & Fix Status
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // Hemisphere from the SIGN of the real coordinate (was a hardcoded "N" / "W").
            GpsMetricCard(
                title = "LATITUDE",
                value = if (gps.isLocked && gps.latitude != 0.0) "${"%.5f".format(kotlin.math.abs(gps.latitude))}° ${if (gps.latitude >= 0) "N" else "S"}" else "—",
                color = Mascot.color(0xFFFFC56B),
                modifier = Modifier.weight(1f)
            )
            GpsMetricCard(
                title = "LONGITUDE",
                value = if (gps.isLocked && gps.longitude != 0.0) "${"%.5f".format(kotlin.math.abs(gps.longitude))}° ${if (gps.longitude >= 0) "E" else "W"}" else "—",
                color = Mascot.color(0xFFFFC56B),
                modifier = Modifier.weight(1f)
            )
        }

        // Row 2: Altitude & Ground Speed — "—" until there is a real fix (no "0 m / 0.0 mph" readings).
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GpsMetricCard(
                title = "ELEVATION / ALTITUDE",
                value = if (gps.isLocked) "${gps.altitudeM.toInt()} m (${(gps.altitudeM * 3.28084).toInt()} ft)" else "—",
                color = Mascot.color(0xFF00FFCC),
                modifier = Modifier.weight(1f)
            )
            GpsMetricCard(
                title = "GROUND SPEED",
                value = if (gps.isLocked) "${"%.1f".format(gps.speedMph)} mph (${(gps.speedMph * 1.60934).toInt()} km/h)" else "—",
                color = Mascot.color(0xFF6FB0FF),
                modifier = Modifier.weight(1f)
            )
        }

        // Row 3: Heading Compass & Accuracy
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GpsMetricCard(
                title = "BEARING / HEADING",
                value = if (gps.isLocked) "${gps.bearing.toInt()}° (${degreesToCompassText(gps.bearing.toInt())})" else "—",
                color = Color(0xFFFFD600),
                modifier = Modifier.weight(1f)
            )
            GpsMetricCard(
                title = "FIX ACCURACY",
                value = if (gps.isLocked && gps.accuracyM > 0f) "±${"%.1f".format(gps.accuracyM)} meters" else "—",
                color = Mascot.color(0xFFFFD59A),
                modifier = Modifier.weight(1f)
            )
        }

        // Row 4: Geocoded Location Address
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CutCornerShape(8.dp))
                .background(Mascot.color(0xFF05141C))
                .border(0.8.dp, Mascot.color(0xFFFFC56B).copy(alpha = 0.3f), CutCornerShape(8.dp))
                .padding(8.dp)
        ) {
            Column {
                Text(
                    text = "LOCATION",
                    color = Mascot.color(0xFFFFC56B),
                    fontSize = 7.5.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = AudiowideFont
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (gps.city.isNotEmpty()) "${gps.city}, ${gps.county} · ${gps.state} ${gps.country}" else gps.fuzzyLocation,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = OrbitronFont,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun GpsMetricCard(
    title: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(CutCornerShape(8.dp))
            .background(Mascot.color(0xFF05141C))
            .border(0.8.dp, color.copy(alpha = 0.35f), CutCornerShape(8.dp))
            .padding(6.dp)
    ) {
        Column {
            Text(
                text = title,
                color = color.copy(alpha = 0.8f),
                fontSize = 7.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = AudiowideFont
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = value,
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = OrbitronFont,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun TabButton(
    title: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(CutCornerShape(6.dp))
            .background(if (isSelected) Mascot.color(0xFFFFC56B).copy(alpha = 0.25f) else Color.Transparent)
            .border(
                0.8.dp,
                if (isSelected) Mascot.color(0xFFFFC56B) else Color.Transparent,
                CutCornerShape(6.dp)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            color = if (isSelected) Mascot.color(0xFFFFC56B) else Color.Gray,
            fontSize = 8.5.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = AudiowideFont
        )
    }
}

@Composable
private fun TactileIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .clip(CutCornerShape(6.dp))
            .background(if (isPressed) Mascot.color(0xFFFFC56B).copy(alpha = 0.3f) else Mascot.color(0xDD041218))
            .border(
                1.dp,
                if (isPressed) Mascot.color(0xFFFFC56B) else Mascot.color(0xFFFFC56B).copy(alpha = 0.4f),
                CutCornerShape(6.dp)
            )
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { isPressed = true },
                    onDragEnd = { isPressed = false; onClick() },
                    onDragCancel = { isPressed = false },
                    onDrag = { _, _ -> }
                )
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

private fun degreesToCompassText(deg: Int): String {
    val directions = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
    val idx = (((deg % 360) / 22.5) + 0.5).toInt() % 16
    return directions[idx]
}
