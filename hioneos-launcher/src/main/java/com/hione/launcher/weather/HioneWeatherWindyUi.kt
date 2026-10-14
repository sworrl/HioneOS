package com.hione.launcher.weather

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Grass
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hione.launcher.AudiowideFont
import com.hione.launcher.CyberGlassBorder
import com.hione.launcher.Mascot
import com.hione.launcher.HioneCyan
import com.hione.launcher.HioneNeonPink
import com.hione.launcher.HioneTextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * Weather screen pieces ported from the KDE Windy Weather widget: the rain nowcast radar, a free
 * map (Esri dark canvas + RainViewer, Windy layers once a key is in), Windy webcams, the Windy
 * GFS strip, and the card that explains what Windy adds. All copy is plain and short on purpose.
 */

private val Gold = Color(0xFFFFD54F)
private val Warn = Color(0xFFFFB300)

const val WINDY_PREMIUM_URL = "https://www.windy.com/subscription"
const val WINDY_KEYS_URL = "https://api.windy.com/keys"

private fun imperial() = HioneWeatherTileEngine.state.value.units == WxUnits.IMPERIAL

/** Open a page in whatever browser is installed. False when there is none (stock HioneOS ships without one). */
fun openWeb(ctx: Context, url: String): Boolean = runCatching {
    val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (i.resolveActivity(ctx.packageManager) == null) return false
    ctx.startActivity(i)
    true
}.getOrDefault(false)

// ---------------------------------------------------------------------------------- shared bits

@Composable
private fun Pill(text: String, color: Color, enabled: Boolean = true, filled: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (filled) color.copy(alpha = 0.28f) else Mascot.color(0x1104121A))
            .border(1.dp, color.copy(alpha = if (enabled) 0.9f else 0.3f), RoundedCornerShape(6.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 9.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (enabled) Color.White else Color.White.copy(alpha = 0.4f), fontSize = 9.5.sp,
            fontWeight = FontWeight.Bold, fontFamily = AudiowideFont, maxLines = 1)
    }
}

@Composable
private fun SectionLabel(text: String, color: Color = HioneCyan) {
    Text(text, color = color, fontSize = 9.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont, letterSpacing = 0.6.sp)
}

private fun ageText(ms: Long): String = if (ms <= 0) "" else WxFmt.age(ms)

private fun android.graphics.Paint.label(sizePx: Float, color: Int = android.graphics.Color.WHITE, center: Boolean = false) = apply {
    isAntiAlias = true
    textSize = sizePx
    this.color = color
    typeface = android.graphics.Typeface.DEFAULT_BOLD
    textAlign = if (center) android.graphics.Paint.Align.CENTER else android.graphics.Paint.Align.LEFT
    setShadowLayer(3f, 0f, 0f, android.graphics.Color.BLACK)
}

/** A web page inside the launcher, for when no browser is installed. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HioneWebPageDialog(url: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxWidth(0.97f)
                .fillMaxHeight(0.94f)
                .clip(CutCornerShape(10.dp))
                .background(Mascot.color(0xFF04121A))
                .border(1.dp, HioneCyan, CutCornerShape(10.dp))
        ) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(url.removePrefix("https://"), color = HioneTextSecondary, fontSize = 10.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Pill("CLOSE", HioneNeonPink, onClick = onDismiss)
            }
            AndroidView(
                factory = { c ->
                    WebView(c).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        val cm = android.webkit.CookieManager.getInstance()
                        cm.setAcceptCookie(true)
                        cm.setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                android.webkit.CookieManager.getInstance().flush()
                            }
                        }
                        loadUrl(url)
                    }
                },
                onRelease = {
                    android.webkit.CookieManager.getInstance().flush()
                    it.destroy()
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

// ---------------------------------------------------------------------------------- NOW tab pieces

/** "Rain in ~25 min from the SW" line. Tap opens the radar tab. */
@Composable
fun HioneRainNowcastLine(onOpenRadar: () -> Unit) {
    val n by HioneNowcastEngine.state.collectAsState()
    val s = n.summary
    val text = s.text.ifBlank { if (n.busy) n.status else n.error.ifBlank { "Checking the radar for rain" } }
    val c = Color(s.color)
    val alert = s.level == "now" || s.level == "soon"
    Row(
        Modifier
            .fillMaxWidth()
            .clip(CutCornerShape(6.dp))
            .background(if (alert) c.copy(alpha = 0.18f) else Mascot.color(0x22FFC56B))
            .border(if (alert) 1.5.dp else 0.6.dp, if (alert) c else HioneCyan.copy(alpha = 0.35f), CutCornerShape(6.dp))
            .clickable { onOpenRadar() }
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(c))
        Spacer(Modifier.width(6.dp))
        Text(text, color = if (s.level == "unknown") Color.White else c, fontSize = 11.sp,
            fontWeight = if (alert) FontWeight.Black else FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f))
        if (n.busy) CircularProgressIndicator(color = HioneCyan, strokeWidth = 1.5.dp, modifier = Modifier.size(12.dp))
        else if (n.fetchedMs > 0 && System.currentTimeMillis() - n.fetchedMs > 15 * 60_000L)
            Text("radar ${ageText(n.fetchedMs)}", color = HioneTextSecondary, fontSize = 8.5.sp)
    }
}

/** Pollen + frost badges. Draws nothing when neither applies. */
@Composable
fun HioneWeatherExtraBadges() {
    val x by HioneWeatherExtras.state.collectAsState()
    val pollen = HioneWeatherExtras.pollenLabel(x.air)
    val frost = x.frost
    if (pollen == null && frost == null) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (pollen != null) WeatherMetricBadge(
            modifier = Modifier.weight(1f),
            title = "POLLEN",
            value = pollen.replaceFirstChar { it.uppercase() },
            sub = HioneWeatherExtras.pollenDetail(x.air) ?: "Open-Meteo, CAMS model",
            icon = Icons.Default.Grass,
            color = com.hione.launcher.ui.HioneIdentity.Leek
        )
        if (frost != null) WeatherMetricBadge(
            modifier = Modifier.weight(1f),
            title = "FROST",
            value = frost.text,
            sub = "Low ${HioneWeatherExtras.tempLabel(frost.minC)} around ${SimpleDateFormat("h a", Locale.US).format(Date(frost.atMs))}",
            icon = Icons.Default.AcUnit,
            color = Color(0xFF7FB2E5)
        )
    }
}

// ---------------------------------------------------------------------------------- Windy card

/**
 * What Windy adds, with the two links and the key fields. Folds down to one line on "Not now",
 * and to a one-line "Windy connected" status once a key is in.
 */
@Composable
fun HioneWindyCard(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val keys by HioneWindyKeys.state.collectAsState()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { HioneWindyKeys.ensureLoaded(ctx) } }
    val x by HioneWeatherExtras.state.collectAsState()
    var folded by remember { mutableStateOf(HioneWeatherExtras.windyCardFolded(ctx)) }
    var editing by remember { mutableStateOf(false) }
    var webUrl by remember { mutableStateOf<String?>(null) }
    var webcamText by remember { mutableStateOf("") }
    var pointText by remember { mutableStateOf("") }
    var mapText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    val pm = ctx.packageManager
    val installedWindyApp = remember(ctx) {
        listOf("com.windytv.android", "com.windyapp.android", "co.windyapp").firstOrNull { pkg ->
            runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess
        }
    }
    val isSubscriber = remember(webUrl) {
        val cookie = runCatching { android.webkit.CookieManager.getInstance().getCookie("https://www.windy.com") }.getOrNull()
        !cookie.isNullOrBlank() && (cookie.contains("remember_token") || cookie.contains("session") || cookie.contains("user"))
    }

    fun open(url: String) { if (!openWeb(ctx, url)) webUrl = url }

    val shape = CutCornerShape(8.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Mascot.color(0x336FB0FF), Mascot.color(0x2204121A))))
            .border(1.dp, HioneNeonPink.copy(alpha = 0.55f), shape)
            .padding(9.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val connected = keys.connected && !keys.pointFromBuild || keys.hasWebcam || keys.hasMap || isSubscriber
        when {
            connected && !editing -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        SectionLabel(if (isSubscriber) "WINDY PREMIUM ACTIVE" else "WINDY CONNECTED", HioneNeonPink)
                        val parts = buildList {
                            if (isSubscriber) add("subscriber logged in")
                            add(if (keys.hasWebcam) "webcams on" else "no webcam key")
                            add(if (keys.hasPoint) "point forecast on" else "no point forecast key")
                            if (keys.hasMap) add("map layers on")
                        }
                        Text(parts.joinToString(", "), color = Color.White, fontSize = 10.sp)
                        if (x.windy?.warning?.isNotBlank() == true)
                            Text("Your point key is on Windy's free testing plan, so Windy sends shuffled sample numbers. The forecast here stays on Open-Meteo until the key is on a paid plan.", color = Warn, fontSize = 9.sp)
                        if (x.windyError.isNotBlank()) Text(x.windyError, color = Warn, fontSize = 9.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (installedWindyApp != null) {
                            Pill("OPEN APP", HioneCyan) {
                                pm.getLaunchIntentForPackage(installedWindyApp)?.let {
                                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    ctx.startActivity(it)
                                }
                            }
                        }
                        Pill("EDIT", HioneNeonPink) { editing = true; webcamText = ""; pointText = ""; mapText = ""; note = "" }
                    }
                }
            }
            folded && !editing -> {
                Row(Modifier.fillMaxWidth().clickable { folded = false; HioneWeatherExtras.setWindyCardFolded(ctx, false) },
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Windy not connected. Tap to log in or add API keys.", color = HioneTextSecondary, fontSize = 10.sp, modifier = Modifier.weight(1f))
                    Text("SHOW", color = HioneNeonPink, fontSize = 9.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
                }
            }
            else -> {
                SectionLabel("WINDY & PREMIUM SUBSCRIPTION", HioneNeonPink)
                if (!connected) {
                    Text("Everything on this screen runs on free data: Open-Meteo, RainViewer, NWS and free Esri/OpenStreetMap maps. Windy adds premium high-res models, maps and live webcams.",
                        color = Color.White, fontSize = 10.5.sp)
                    Text("Windy Premium gets you 1-hour forecast steps, 4x daily model updates, 10-day lookaheads and full access on windy.com and in the Windy app.",
                        color = HioneTextSecondary, fontSize = 10.sp)
                    Text("Enter your API keys below or log in with your Windy subscription account to unlock premium layers.",
                        color = HioneTextSecondary, fontSize = 10.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill("LOG IN (PREMIUM)", HioneNeonPink, filled = true) { open("https://www.windy.com/login") }
                        if (installedWindyApp != null) {
                            Pill("OPEN WINDY APP", HioneCyan, filled = true) {
                                pm.getLaunchIntentForPackage(installedWindyApp)?.let {
                                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    ctx.startActivity(it)
                                }
                            }
                        }
                        Pill("GET PREMIUM", HioneTextSecondary) { open(WINDY_PREMIUM_URL) }
                        Pill("GET API KEYS", HioneCyan) { open(WINDY_KEYS_URL) }
                    }
                }
                KeyField("Webcam key", webcamText, if (keys.hasWebcam) "saved ${HioneWindyKeys.masked(keys.webcam)}" else "paste the Webcams API key") { webcamText = it }
                KeyField("Point Forecast key", pointText, when {
                    keys.pointFromBuild -> "using the key from the build"
                    keys.hasPoint -> "saved ${HioneWindyKeys.masked(keys.point)}"
                    else -> "paste the Point Forecast API key"
                }) { pointText = it }
                KeyField("Map Forecast key", mapText, if (keys.hasMap) "saved ${HioneWindyKeys.masked(keys.map)}" else "paste the Map Forecast API key") { mapText = it }
                if (note.isNotBlank()) Text(note, color = Warn, fontSize = 9.5.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill("SAVE KEYS", HioneCyan, enabled = webcamText.isNotBlank() || pointText.isNotBlank() || mapText.isNotBlank(), filled = true) {
                        val w = webcamText.trim().ifBlank { null }
                        val p = pointText.trim().ifBlank { null }
                        val m = mapText.trim().ifBlank { null }
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { HioneWindyKeys.set(ctx, w, p, m).also { if (it) HioneWindyKeys.applied(ctx) } }
                            if (ok) { webcamText = ""; pointText = ""; mapText = ""; note = ""; editing = false }
                            else note = "Not saved. A Windy key is letters, digits, - and _ only."
                        }
                    }
                    if (editing && keys.connected) Pill("CLEAR KEYS", Warn) {
                        scope.launch {
                            withContext(Dispatchers.IO) { HioneWindyKeys.set(ctx, "", "", "").also { if (it) HioneWindyKeys.applied(ctx) } }
                            editing = false
                        }
                    }
                    if (editing) Pill("DONE", HioneTextSecondary) { editing = false }
                    else if (!connected) Pill("NOT NOW", HioneTextSecondary) { folded = true; HioneWeatherExtras.setWindyCardFolded(ctx, true) }
                }
                Text("Keys and sessions stay on this device, encrypted in the Android Keystore.", color = HioneTextSecondary.copy(alpha = 0.7f), fontSize = 8.5.sp)
            }
        }
    }
    webUrl?.let { u -> HioneWebPageDialog(u) { webUrl = null } }
}

@Composable
private fun KeyField(label: String, value: String, hint: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.trim()) },
        label = { Text(label, fontSize = 9.sp) },
        placeholder = { Text(hint, color = Color.Gray, fontSize = 10.sp) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
            focusedBorderColor = HioneCyan, unfocusedBorderColor = Mascot.color(0x66FFC56B),
            focusedLabelColor = HioneCyan, unfocusedLabelColor = HioneTextSecondary
        )
    )
}

// ---------------------------------------------------------------------------------- FORECAST tab: Windy GFS strip

@Composable
fun HioneWindyPointStrip() {
    val ctx = LocalContext.current
    val keys by HioneWindyKeys.state.collectAsState()
    val x by HioneWeatherExtras.state.collectAsState()
    if (!keys.hasPoint) return
    val units = HioneWeatherTileEngine.state.collectAsState().value.units
    val w = x.windy
    Column(
        Modifier
            .fillMaxWidth()
            .clip(CutCornerShape(8.dp))
            .background(Mascot.color(0x33020A0F))
            .border(0.8.dp, HioneNeonPink.copy(alpha = 0.5f), CutCornerShape(8.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("WINDY ${w?.model?.uppercase(Locale.US) ?: "GFS"}, 3-HOUR STEPS", HioneNeonPink)
            Spacer(Modifier.weight(1f))
            if (x.windyBusy) CircularProgressIndicator(color = HioneNeonPink, strokeWidth = 1.5.dp, modifier = Modifier.size(12.dp))
            else Text(ageText(x.windyFetchedMs), color = HioneTextSecondary, fontSize = 8.5.sp,
                modifier = Modifier.clickable { HioneWeatherExtras.refresh(ctx, force = true) })
        }
        if (w?.warning?.isNotBlank() == true)
            Text("Testing plan: Windy sends shuffled sample numbers, not a real forecast.", color = Warn, fontSize = 9.sp)
        if (x.windyError.isNotBlank()) Text(x.windyError, color = Warn, fontSize = 9.sp)
        if (w != null && w.hourly.isNotEmpty()) {
            val fmt = remember { SimpleDateFormat("h a", Locale.US) }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                items(w.hourly.take(9)) { h ->
                    Column(
                        Modifier
                            .width(56.dp)
                            .clip(CutCornerShape(6.dp))
                            .background(Mascot.color(0x226FB0FF))
                            .padding(vertical = 5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(fmt.format(Date(h.epochMs)), color = HioneTextSecondary, fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
                        Text(WxFmt.temp(h.tempC, units), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
                        Text(HioneWmo.text(h.wmoCode), color = HioneTextSecondary, fontSize = 7.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${WxFmt.compass(h.windDirDeg)} ${WxFmt.wind(h.windKmh, units).substringBefore(' ')}", color = HioneCyan, fontSize = 8.5.sp)
                        if ((h.precipMm ?: 0.0) > 0.05) Text(WxFmt.precip(h.precipMm, units), color = Color(0xFF3D8FD6), fontSize = 8.sp)
                    }
                }
            }
        } else if (!x.windyBusy && x.windyError.isBlank()) {
            Text("Waiting on Windy.", color = HioneTextSecondary, fontSize = 9.sp)
        }
    }
}

// ---------------------------------------------------------------------------------- RADAR tab: rain nowcast

/**
 * Nowcast radar: RainViewer frames decoded to rain intensity and redrawn in Windy's palette over a
 * dark Esri basemap, with storm-motion arrows, the arrival line, and a frame scrubber.
 * Replaces the old one-tile radar, which had no motion, no arrival estimate and a brand-colour legend.
 */
@Composable
fun HioneNowcastRadarTab(gps: HioneWeatherService.GpsTelemetry) {
    val ctx = LocalContext.current
    val n by HioneNowcastEngine.state.collectAsState()
    val hasFix = gps.latitude != 0.0 || gps.longitude != 0.0
    var frameIndex by remember { mutableIntStateOf(-1) } // -1 = latest past frame
    var playing by remember { mutableStateOf(false) }
    var rainNotify by remember { mutableStateOf(HioneWeatherExtras.notifyRain(ctx)) }
    var frostNotify by remember { mutableStateOf(HioneWeatherExtras.notifyFrost(ctx)) }
    val shown = if (frameIndex in n.frames.indices) frameIndex else n.latestIndex

    LaunchedEffect(gps.latitude, gps.longitude) { if (hasFix) HioneNowcastEngine.refresh(ctx, gps.latitude, gps.longitude) }
    LaunchedEffect(n.fetchedMs) { frameIndex = -1 }
    LaunchedEffect(playing, n.frames.size) {
        while (playing && n.frames.size > 1) {
            delay(700L)
            frameIndex = ((if (frameIndex < 0) n.latestIndex else frameIndex) + 1) % n.frames.size
        }
    }

    val base = remember(n.basemap) { n.basemap?.asImageBitmap() }
    val overlay = remember(n.frames, shown) { n.frames.getOrNull(shown)?.overlay?.asImageBitmap() }
    val res = n.result
    val s = n.summary

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(Color(s.color)))
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(s.text.ifBlank { n.status.ifBlank { "Rain nowcast" } }, color = if (s.level == "unknown") Color.White else Color(s.color),
                    fontSize = 11.5.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val sub = listOfNotNull(
                    res?.takeIf { it.speedKmh > 0 }?.let { "storms moving ${it.heading} at " + if (imperial()) "${(it.speedKmh * 0.621371).roundToInt()} mph" else "${it.speedKmh.roundToInt()} km/h" },
                    s.detail.takeIf { it.isNotBlank() }
                ).joinToString(". ")
                if (sub.isNotBlank()) Text(sub, color = HioneTextSecondary, fontSize = 9.sp, maxLines = 2)
            }
            Pill(if (n.busy) "..." else "REFRESH", HioneCyan, enabled = !n.busy && hasFix) {
                HioneNowcastEngine.refresh(ctx, gps.latitude, gps.longitude, force = true)
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(250.dp)
                .clip(CutCornerShape(12.dp))
                .background(Color(0xFF0B1420))
                .border(1.dp, HioneCyan.copy(alpha = 0.8f), CutCornerShape(12.dp))
        ) {
            val m = n.mosaic
            if (m != null) Canvas(Modifier.fillMaxSize()) {
                val sc = max(size.width / m.size, size.height / m.size)
                val ox = (size.width - m.size * sc) / 2f
                val oy = (size.height - m.size * sc) / 2f
                val dst = IntSize((m.size * sc).roundToInt(), (m.size * sc).roundToInt())
                base?.let { drawImage(it, dstOffset = IntOffset(ox.roundToInt(), oy.roundToInt()), dstSize = dst) }
                drawRect(Color(0x2E000000))
                overlay?.let {
                    drawImage(it, srcOffset = IntOffset.Zero, srcSize = IntSize(it.width, it.height),
                        dstOffset = IntOffset(ox.roundToInt(), oy.roundToInt()), dstSize = dst, alpha = 0.9f, filterQuality = FilterQuality.Low)
                }
                // Storm motion arrows from the latest pair, shown on every frame.
                if (n.motion.quality >= 0.05) {
                    val len = 9f + min(28f, ((res?.speedKmh ?: 0.0) / 2.5).toFloat())
                    val gf = n.gridFactor
                    for (a in n.arrows) {
                        drawArrow(ox + (a.cx * gf).toFloat() * sc, oy + (a.cy * gf).toFloat() * sc, a.ux.toFloat(), a.uy.toFloat(), len, if (a.local) 1f else 0.7f)
                    }
                }
                // Location marker
                val mx = ox + m.px.toFloat() * sc
                val my = oy + m.py.toFloat() * sc
                drawCircle(Color.White.copy(alpha = 0.9f), radius = 11f, center = Offset(mx, my), style = Stroke(2f))
                drawCircle(Color(0xFFFF4D4D), radius = 4.5f, center = Offset(mx, my))
                // Where the rain is coming from
                if (res?.status == "eta" && res.fromDeg != null) {
                    val ang = Math.toRadians(res.fromDeg - 90)
                    val d = min(90f, ((res.distKm ?: 30.0) * 1000 / (m.mpp / sc)).toFloat())
                    drawLine(Color(s.color), Offset(mx, my), Offset(mx + cos(ang).toFloat() * d, my + sin(ang).toFloat() * d),
                        strokeWidth = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
                }
                drawCompass(size.width - 30f, 34f)
                // Lifted clear of the provider credit along the bottom edge.
                drawScaleBar(14f, size.height - 44f, m.mpp / sc)
                drawLegend(size.width - 12f, size.height - 38f)
                n.frames.getOrNull(shown)?.let { f ->
                    val mins = ((System.currentTimeMillis() / 1000 - f.time) / 60.0).roundToInt()
                    val badge = when {
                        f.kind == "nowcast" -> "FORECAST +${-mins} min"
                        shown == n.latestIndex -> "LATEST"
                        else -> "-$mins min"
                    }
                    drawContext.canvas.nativeCanvas.drawText(SimpleDateFormat("h:mm a", Locale.US).format(Date(f.time * 1000)) + "  $badge",
                        14f, 30f, android.graphics.Paint().label(26f, if (f.kind == "nowcast") 0xFFFFD166.toInt() else 0xFFE8EEF7.toInt()))
                }
            }
            when {
                !hasFix -> Overlay("NO LOCATION YET, SO NO RADAR")
                n.busy && m == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = HioneCyan, modifier = Modifier.size(24.dp))
                        Text(n.status, color = HioneTextSecondary, fontSize = 9.sp)
                    }
                }
                m == null && n.error.isNotBlank() -> Overlay("RADAR DID NOT LOAD: ${n.error.uppercase(Locale.US)}")
            }
            if (m != null) MapCredit("${WxTiles.MAP_CREDIT} | ${WxTiles.RADAR_CREDIT}")
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill(if (playing) "PAUSE" else "PLAY", HioneCyan, enabled = n.frames.size > 1) { playing = !playing }
            if (n.frames.size > 1) Slider(
                value = shown.coerceAtLeast(0).toFloat(),
                onValueChange = { playing = false; frameIndex = it.roundToInt().coerceIn(0, n.frames.size - 1) },
                valueRange = 0f..(n.frames.size - 1).toFloat(),
                steps = (n.frames.size - 2).coerceAtLeast(0),
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(thumbColor = HioneCyan, activeTrackColor = HioneCyan, inactiveTrackColor = Mascot.color(0x33FFC56B))
            ) else Spacer(Modifier.weight(1f))
            Pill("LATEST", HioneTextSecondary, enabled = frameIndex >= 0) { playing = false; frameIndex = -1 }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("NOTIFY ME", color = HioneTextSecondary, fontSize = 8.5.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
            Pill(if (rainNotify) "RAIN SOON: ON" else "RAIN SOON: OFF", HioneCyan, filled = rainNotify) {
                rainNotify = !rainNotify; HioneWeatherExtras.setNotifyRain(ctx, rainNotify)
            }
            Pill(if (frostNotify) "FROST: ON" else "FROST: OFF", Color(0xFF7FB2E5), filled = frostNotify) {
                frostNotify = !frostNotify; HioneWeatherExtras.setNotifyFrost(ctx, frostNotify)
            }
        }
        Text(
            "Radar from RainViewer, 10-minute frames. Storm motion comes from matching the last few frames, so treat anything past an hour as a rough guess. ${WxTiles.ATTRIBUTION}" +
                if (n.fetchedMs > 0) " Updated ${ageText(n.fetchedMs)}." else "",
            color = HioneTextSecondary.copy(alpha = 0.75f), fontSize = 8.5.sp
        )
    }
}

/** Provider credit in the map corner, small, the way OSM/Esri/RainViewer/Windy ask for it. */
@Composable
private fun BoxScope.MapCredit(text: String, alignment: Alignment = Alignment.BottomEnd) {
    Text(
        text, color = Color.White.copy(alpha = 0.85f), fontSize = 7.sp, maxLines = 1,
        modifier = Modifier
            .align(alignment)
            .background(Color(0x99000000), RoundedCornerShape(topStart = 4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp)
    )
}

@Composable
private fun BoxScope.Overlay(text: String) {
    Box(Modifier.fillMaxSize().background(Color(0xAA000000)), contentAlignment = Alignment.Center) {
        Text(text, color = Warn, fontSize = 9.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont,
            textAlign = TextAlign.Center, modifier = Modifier.padding(12.dp))
    }
}

private fun DrawScope.drawArrow(x: Float, y: Float, ux: Float, uy: Float, len: Float, alpha: Float) {
    val hx = x + ux * len / 2; val hy = y + uy * len / 2
    val tx = x - ux * len / 2; val ty = y - uy * len / 2
    val px = -uy; val py = ux
    val hs = max(5f, len * 0.32f)
    for (pass in 0..1) {
        val c = if (pass == 0) Color.Black.copy(alpha = 0.55f * alpha) else Color.White.copy(alpha = 0.95f * alpha)
        val w = if (pass == 0) 5f else 2.5f
        drawLine(c, Offset(tx, ty), Offset(hx, hy), w, StrokeCap.Round)
        drawLine(c, Offset(hx - ux * hs + px * hs * 0.55f, hy - uy * hs + py * hs * 0.55f), Offset(hx, hy), w, StrokeCap.Round)
        drawLine(c, Offset(hx - ux * hs - px * hs * 0.55f, hy - uy * hs - py * hs * 0.55f), Offset(hx, hy), w, StrokeCap.Round)
    }
}

private fun DrawScope.drawCompass(x: Float, y: Float) {
    drawCircle(Color(0x73000000), 18f, Offset(x, y))
    val north = androidx.compose.ui.graphics.Path().apply { moveTo(x, y - 14f); lineTo(x + 5f, y + 2f); lineTo(x, y); lineTo(x - 5f, y + 2f); close() }
    val south = androidx.compose.ui.graphics.Path().apply { moveTo(x, y + 14f); lineTo(x + 5f, y - 2f); lineTo(x, y); lineTo(x - 5f, y - 2f); close() }
    drawPath(north, Color(0xFFFF4D4D))
    drawPath(south, Color.White.copy(alpha = 0.8f))
    drawContext.canvas.nativeCanvas.drawText("N", x, y - 22f, android.graphics.Paint().label(20f, center = true))
}

private fun DrawScope.drawScaleBar(x: Float, y: Float, mppScreen: Double) {
    val imp = imperial()
    val unitM = if (imp) 1609.344 else 1000.0
    val target = 140 * mppScreen / unitM
    val best = listOf(1, 2, 5, 10, 20, 50, 100, 200, 500).lastOrNull { it <= target } ?: 1
    val w = (best * unitM / mppScreen).toFloat()
    drawRect(Color(0x80000000), Offset(x - 5f, y - 26f), androidx.compose.ui.geometry.Size(w + 10f, 34f))
    drawRect(Color.White, Offset(x, y - 2f), androidx.compose.ui.geometry.Size(w, 4f))
    drawContext.canvas.nativeCanvas.drawText("$best ${if (imp) "mi" else "km"}", x + w / 2, y - 8f, android.graphics.Paint().label(19f, center = true))
}

private fun DrawScope.drawLegend(right: Float, bottom: Float) {
    val stops = listOf(5f, 15f, 25f, 35f, 45f, 55f, 65f)
    val sw = 20f; val h = 10f
    val x0 = right - stops.size * sw
    drawRect(Color(0x80000000), Offset(x0 - 5f, bottom - h - 26f), androidx.compose.ui.geometry.Size(stops.size * sw + 10f, h + 30f))
    stops.forEachIndexed { i, d -> drawRect(Color(HioneNowcast.windyColor(d)), Offset(x0 + i * sw, bottom - h), androidx.compose.ui.geometry.Size(sw, h)) }
    drawContext.canvas.nativeCanvas.drawText("light to heavy", x0 + stops.size * sw / 2, bottom - h - 8f, android.graphics.Paint().label(17f, center = true))
}

// ---------------------------------------------------------------------------------- MAP tab

private data class WindyLayer(val value: String, val label: String, val product: String = "")

private val WINDY_LAYERS = listOf(
    WindyLayer("wind", "WIND"), WindyLayer("temp", "TEMP"), WindyLayer("rain", "RAIN"), WindyLayer("clouds", "CLOUDS"),
    WindyLayer("gust", "GUSTS"), WindyLayer("pressure", "PRESSURE"), WindyLayer("rh", "HUMIDITY"),
    WindyLayer("radar", "WINDY RADAR", "radar"), WindyLayer("satellite", "SATELLITE", "satellite")
)

private fun windyEmbedUrl(lat: Double, lon: Double, zoom: Int, layer: WindyLayer): String {
    val imp = imperial()
    val ll = String.format(Locale.US, "%.4f", lat) to String.format(Locale.US, "%.4f", lon)
    return "https://embed.windy.com/embed.html?type=map&location=coordinates" +
        "&metricRain=${if (imp) "in" else "mm"}&metricTemp=${Uri.encode(if (imp) "°F" else "°C")}&metricWind=${Uri.encode(if (imp) "mph" else "km/h")}" +
        "&lat=${ll.first}&lon=${ll.second}&zoom=$zoom&overlay=${layer.value}" + (if (layer.product.isNotEmpty()) "&product=${layer.product}" else "") +
        "&level=surface&calendar=now&message=true&detailLat=${ll.first}&detailLon=${ll.second}&marker=true"
}

/**
 * Map: free by default (Esri dark canvas tiles + the latest RainViewer frame),
 * drag to pan, buttons to zoom. RainViewer stops at z7, so closer zooms stretch the z7 radar tile.
 * With a Windy key the Windy layers (an embed.windy.com page) show up as extra choices.
 */
@Composable
fun HioneWeatherMapTab(gps: HioneWeatherService.GpsTelemetry) {
    val ctx = LocalContext.current
    val keys by HioneWindyKeys.state.collectAsState()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { HioneWindyKeys.ensureLoaded(ctx) } }
    val hasFix = gps.latitude != 0.0 || gps.longitude != 0.0
    var layer by remember { mutableStateOf<WindyLayer?>(null) } // null = free radar map
    var zoom by remember { mutableIntStateOf(7) }
    var center by remember(gps.latitude, gps.longitude) { mutableStateOf(gps.latitude to gps.longitude) }
    var drag by remember { mutableStateOf(Offset.Zero) }
    var viewPx by remember { mutableStateOf(IntSize.Zero) }
    var rv by remember { mutableStateOf<Pair<String, String>?>(null) } // host to latest path
    var rvTime by remember { mutableStateOf(0L) }
    val tiles = remember { mutableStateMapOf<String, ImageBitmap>() }
    val failed = remember { mutableStateMapOf<String, Boolean>() }
    var webUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching {
                val j = WxHttp.json("https://api.rainviewer.com/public/weather-maps.json")
                val past = j.optJSONObject("radar")?.optJSONArray("past")
                val last = past?.optJSONObject(past.length() - 1)
                if (last != null) { rv = j.optString("host", "https://tilecache.rainviewer.com") to last.optString("path"); rvTime = last.optLong("time") }
            }
        }
    }

    // Tiles the current view needs.
    val needed: List<Triple<Int, Int, Pair<Float, Float>>> = remember(center, zoom, viewPx, drag) {
        if (!hasFix || viewPx.width == 0) emptyList() else {
            val cx = HioneNowcast.lonToTileX(center.second, zoom) * 256 - drag.x
            val cy = HioneNowcast.latToTileY(center.first, zoom) * 256 - drag.y
            val left = cx - viewPx.width / 2.0
            val top = cy - viewPx.height / 2.0
            val max = 1 shl zoom
            val out = ArrayList<Triple<Int, Int, Pair<Float, Float>>>()
            for (ty in floor(top / 256).toInt()..floor((top + viewPx.height) / 256).toInt()) {
                if (ty !in 0 until max) continue
                for (tx in floor(left / 256).toInt()..floor((left + viewPx.width) / 256).toInt()) {
                    out.add(Triple(tx, ty, (tx * 256 - left).toFloat() to (ty * 256 - top).toFloat()))
                }
            }
            out
        }
    }
    fun wrapX(x: Int) = ((x % (1 shl zoom)) + (1 shl zoom)) % (1 shl zoom)
    fun baseUrl(x: Int, y: Int) = WxTiles.base(zoom, wrapX(x), y)
    fun labelUrl(x: Int, y: Int) = WxTiles.labels(zoom, wrapX(x), y)
    fun rainUrl(x: Int, y: Int): String? {
        val (host, path) = rv ?: return null
        val z = min(zoom, HioneNowcast.RAIN_MAX_ZOOM)
        val d = zoom - z
        val wrapped = ((x % (1 shl zoom)) + (1 shl zoom)) % (1 shl zoom)
        return HioneNowcast.rainviewerTile(host, path, z, wrapped shr d, y shr d, true)
    }
    LaunchedEffect(needed, rv, layer) {
        if (layer != null) return@LaunchedEffect
        val urls = needed.flatMap { (x, y, _) -> listOfNotNull(baseUrl(x, y), rainUrl(x, y), labelUrl(x, y)) }.distinct().filter { it !in tiles && it !in failed }
        withContext(Dispatchers.IO) {
            for (u in urls) {
                val b = WxHttp.bitmap(u)
                if (b != null) tiles[u] = b.asImageBitmap() else failed[u] = true
            }
        }
        if (tiles.size > 160) tiles.keys.take(tiles.size - 120).forEach { tiles.remove(it) }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Pill("RAIN RADAR", HioneCyan, filled = layer == null) { layer = null }
            WINDY_LAYERS.forEach { l ->
                Pill(l.label, HioneNeonPink, enabled = keys.connected, filled = layer == l) { layer = l }
            }
        }
        if (!keys.connected)
            Text("Wind, temperature, clouds and the other layers come from Windy. Connect Windy on the NOW tab to turn them on.",
                color = HioneTextSecondary, fontSize = 9.sp)

        Box(
            Modifier
                .fillMaxWidth()
                .height(290.dp)
                .clip(CutCornerShape(12.dp))
                .background(Color(0xFF0B1420))
                .border(1.dp, (if (layer == null) HioneCyan else HioneNeonPink).copy(alpha = 0.8f), CutCornerShape(12.dp))
                .onSizeChanged { viewPx = it }
        ) {
            val l = layer
            if (!hasFix) Overlay("NO LOCATION YET, SO NO MAP")
            else if (l != null) {
                val url = windyEmbedUrl(center.first, center.second, zoom, l)
                WindyEmbed(url)
                MapCredit("Layer: Windy.com", Alignment.BottomStart)
            } else {
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(zoom) {
                            detectDragGestures(
                                onDragEnd = {
                                    // Fold the drag into the centre and start over from the new spot.
                                    val cx = HioneNowcast.lonToTileX(center.second, zoom) - drag.x / 256.0
                                    val cy = HioneNowcast.latToTileY(center.first, zoom) - drag.y / 256.0
                                    center = HioneNowcast.tileYToLat(cy, zoom).coerceIn(-84.0, 84.0) to
                                        ((HioneNowcast.tileXToLon(cx, zoom) + 540.0) % 360.0 - 180.0)
                                    drag = Offset.Zero
                                }
                            ) { change, amount -> change.consume(); drag += amount }
                        }
                ) {
                    for ((x, y, pos) in needed) {
                        val o = IntOffset(pos.first.roundToInt(), pos.second.roundToInt())
                        tiles[baseUrl(x, y)]?.let { drawImage(it, dstOffset = o, dstSize = IntSize(256, 256)) }
                    }
                    drawRect(Color(0x2E000000))
                    val d = zoom - min(zoom, HioneNowcast.RAIN_MAX_ZOOM)
                    for ((x, y, pos) in needed) {
                        val u = rainUrl(x, y) ?: continue
                        val img = tiles[u] ?: continue
                        val sub = 256 shr d
                        val wrapped = ((x % (1 shl zoom)) + (1 shl zoom)) % (1 shl zoom)
                        val sx = (wrapped and ((1 shl d) - 1)) * sub
                        val sy = (y and ((1 shl d) - 1)) * sub
                        drawImage(img, srcOffset = IntOffset(sx, sy), srcSize = IntSize(sub, sub),
                            dstOffset = IntOffset(pos.first.roundToInt(), pos.second.roundToInt()), dstSize = IntSize(256, 256),
                            alpha = 0.85f, filterQuality = FilterQuality.Low)
                    }
                    for ((x, y, pos) in needed) {
                        tiles[labelUrl(x, y)]?.let { drawImage(it, dstOffset = IntOffset(pos.first.roundToInt(), pos.second.roundToInt()), dstSize = IntSize(256, 256)) }
                    }
                    // You are here
                    val cx = HioneNowcast.lonToTileX(gps.longitude, zoom) * 256 - (HioneNowcast.lonToTileX(center.second, zoom) * 256 - drag.x - size.width / 2)
                    val cy = HioneNowcast.latToTileY(gps.latitude, zoom) * 256 - (HioneNowcast.latToTileY(center.first, zoom) * 256 - drag.y - size.height / 2)
                    drawCircle(Color.White.copy(alpha = 0.9f), 11f, Offset(cx.toFloat(), cy.toFloat()), style = Stroke(2f))
                    drawCircle(Color(0xFFFF4D4D), 4.5f, Offset(cx.toFloat(), cy.toFloat()))
                    drawScaleBar(14f, size.height - 44f, HioneNowcast.metersPerPixel(center.first, zoom))
                    if (rvTime > 0) drawContext.canvas.nativeCanvas.drawText(
                        "Radar " + SimpleDateFormat("h:mm a", Locale.US).format(Date(rvTime * 1000)) + if (zoom > HioneNowcast.RAIN_MAX_ZOOM) ", stretched past z7" else "",
                        14f, 30f, android.graphics.Paint().label(24f))
                }
                MapCredit("${WxTiles.MAP_CREDIT} | ${WxTiles.RADAR_CREDIT}")
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill("ZOOM OUT", HioneCyan, enabled = zoom > 3) { zoom--; drag = Offset.Zero }
            Text("z$zoom", color = Color.White, fontSize = 10.sp, fontFamily = AudiowideFont)
            Pill("ZOOM IN", HioneCyan, enabled = zoom < 12) { zoom++; drag = Offset.Zero }
            Pill("CENTER", HioneTextSecondary) { center = gps.latitude to gps.longitude; drag = Offset.Zero }
            Spacer(Modifier.weight(1f))
            Pill("WINDY.COM", HioneNeonPink) {
                val u = String.format(Locale.US, "https://www.windy.com/?%.3f,%.3f,%d", center.first, center.second, zoom)
                if (!openWeb(ctx, u)) webUrl = u
            }
        }
        Text(
            if (layer == null) "${WxTiles.ATTRIBUTION} Radar from RainViewer. Drag to move around."
            else "Layer from Windy.com. Pinch and drag inside the map. It runs in a web view, so it is heavier on the battery than the radar map.",
            color = HioneTextSecondary.copy(alpha = 0.75f), fontSize = 8.5.sp
        )
    }
    webUrl?.let { u -> HioneWebPageDialog(u) { webUrl = null } }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WindyEmbed(url: String) {
    AndroidView(
        factory = { c ->
            WebView(c).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = WebViewClient()
                setBackgroundColor(android.graphics.Color.rgb(11, 20, 32))
                loadUrl(url)
                tag = url
            }
        },
        update = { if (it.tag != url) { it.tag = url; it.loadUrl(url) } },
        onRelease = { it.destroy() },
        modifier = Modifier.fillMaxSize()
    )
}

// ---------------------------------------------------------------------------------- CAMS tab

/** Windy webcams within 80 km, cycling every 10 s like the widget. Without a key: what it needs and the card. */
@Composable
fun HioneWebcamTab() {
    val ctx = LocalContext.current
    val keys by HioneWindyKeys.state.collectAsState()
    val x by HioneWeatherExtras.state.collectAsState()
    var index by remember { mutableIntStateOf(0) }
    var auto by remember { mutableStateOf(true) }
    var webUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { HioneWindyKeys.ensureLoaded(ctx) } }
    LaunchedEffect(keys.hasWebcam) {
        while (keys.hasWebcam) {
            HioneWeatherExtras.refreshWebcams(ctx)
            delay(60_000L)
        }
    }
    LaunchedEffect(x.webcams.size, auto) {
        while (auto && x.webcams.size > 1) {
            delay(10_000L)
            index = (index + 1) % x.webcams.size
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!keys.hasWebcam) {
            Text("Webcams come from Windy and need a Windy Webcams API key. The key has a free tier.",
                color = Color.White, fontSize = 10.5.sp)
            HioneWindyCard()
            return@Column
        }
        val cams = x.webcams
        val cam = cams.getOrNull(index.coerceIn(0, (cams.size - 1).coerceAtLeast(0)))
        Box(
            Modifier
                .fillMaxWidth()
                .height(230.dp)
                .clip(CutCornerShape(12.dp))
                .background(Color(0xFF02090E))
                .border(1.dp, HioneNeonPink.copy(alpha = 0.7f), CutCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (cam != null) coil.compose.AsyncImage(
                model = cam.previewUrl,
                contentDescription = cam.title,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
            when {
                x.webcamBusy && cam == null -> CircularProgressIndicator(color = HioneNeonPink, modifier = Modifier.size(24.dp))
                cam == null -> Text(x.webcamError.ifBlank { "No webcams yet." }.uppercase(Locale.US), color = Warn, fontSize = 9.sp,
                    fontWeight = FontWeight.Black, fontFamily = AudiowideFont, textAlign = TextAlign.Center, modifier = Modifier.padding(12.dp))
            }
            if (cam != null) MapCredit("Webcam: windy.com", Alignment.TopEnd)
            if (cams.size > 1) LinearProgressIndicator(
                progress = { (index + 1) / cams.size.toFloat() },
                color = HioneNeonPink, trackColor = Color.Transparent,
                modifier = Modifier.fillMaxWidth().height(3.dp).align(Alignment.BottomCenter)
            )
        }
        if (cam != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("PREV", HioneCyan, enabled = cams.size > 1) { auto = false; index = (index - 1 + cams.size) % cams.size }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(cam.title, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(cam.place.ifBlank { null }, "${index + 1} of ${cams.size}",
                        cam.updatedMs?.let { "updated ${WxFmt.age(it)}" }).joinToString(", "),
                        color = HioneTextSecondary, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Pill("NEXT", HioneCyan, enabled = cams.size > 1) { auto = false; index = (index + 1) % cams.size }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill(if (auto) "CYCLING" else "CYCLE", HioneTextSecondary, filled = auto) { auto = !auto }
                Pill("RELOAD", HioneTextSecondary) { HioneWeatherExtras.refreshWebcams(ctx, force = true) }
                if (cam.detailUrl.isNotBlank()) Pill("ON WINDY.COM", HioneNeonPink) { if (!openWeb(ctx, cam.detailUrl)) webUrl = cam.detailUrl }
            }
        }
        Text("Webcams provided by windy.com.", color = HioneTextSecondary.copy(alpha = 0.75f), fontSize = 8.5.sp)
    }
    webUrl?.let { u -> HioneWebPageDialog(u) { webUrl = null } }
}
