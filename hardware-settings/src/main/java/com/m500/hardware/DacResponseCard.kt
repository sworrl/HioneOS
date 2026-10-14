package com.m500.hardware

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * "What the DAC does to the sound": the selected filter's frequency response and impulse,
 * drawn from DacFilterModel (CS43198 datasheet), with gain, high power and DRE explained, and the
 * listening profile of the chosen jack on top (its EQ, an imported headphone curve, a curve
 * measured with the built-in mic).
 *
 * Hooked into MainActivity's DAC section with one call. Colors come from dacTheme: each filter's
 * line uses the color the status bar badge would show for that filter.
 */

/** AUDIO is the IEM-chart view. TOP zooms on the top octaves, where the filters differ. FULL shows images. */
private enum class GraphRange(val label: String) { AUDIO("20 Hz to 20 kHz"), TOP("Top octaves"), FULL("Out to 2×Fs") }

private val BANDS = listOf(
    Triple("Sub bass", 20.0, 60.0), Triple("Bass", 60.0, 250.0), Triple("Low mid", 250.0, 500.0),
    Triple("Mid", 500.0, 2000.0), Triple("Mid high", 2000.0, 4000.0), Triple("High", 4000.0, 10000.0),
    Triple("Air", 10000.0, 20000.0), Triple("Ultrasonic", 20000.0, 1e7),
)
private val BAND_COLORS = listOf(0xFF7E57C2, 0xFF5C6BC0, 0xFF26A69A, 0xFF66BB6A, 0xFFD4E157, 0xFFFFA726, 0xFFEF5350, 0xFF78909C).map { Color(it) }

private const val POINTS = 260

/** Gain offset in dB, from HiBy's driver (hiby-audio-knobs.md 3.1: -24/-12/0 half-dB units). */
private fun gainDb(gain: String?): Float = when (gain) { "low" -> -12f; "middle" -> -6f; else -> 0f }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DacResponseCard(
    filterId: String?,
    gain: String?,
    dre: Boolean?,
    highPower: Boolean?,
    primary: Color,
    secondary: Color,
    bg: Color,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val applied = DacFilterModel.Filter.of(filterId)

    var tab by remember { mutableIntStateOf(0) }
    var range by remember { mutableStateOf(GraphRange.AUDIO) }
    var preview by remember { mutableStateOf<DacFilterModel.Filter?>(null) }
    val shown = preview ?: applied ?: DacFilterModel.Filter.FAST_LL
    var port by remember { mutableStateOf(OutputProfiles.PORT_35) }
    var showEars by remember { mutableStateOf(true) }

    // Sample rate: Hione Music's now-playing format when it publishes one, else 44.1 kHz.
    var nowPlayingRate by remember { mutableStateOf<Int?>(null) }
    var pickedRate by remember { mutableStateOf<Int?>(null) }
    var outputs by remember { mutableStateOf(OutputProfiles.Snapshot(null, emptyList())) }
    DisposableEffect(Unit) {
        fun reload() {
            nowPlayingRate = parseNowPlayingRate(runCatching { Settings.Global.getString(ctx.contentResolver, "hione_now_playing_format") }.getOrNull())
            outputs = OutputProfiles.read(ctx)
        }
        reload()
        val obs = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = reload()
        }
        runCatching {
            ctx.contentResolver.registerContentObserver(Settings.Global.getUriFor("hione_now_playing_format"), false, obs)
            ctx.contentResolver.registerContentObserver(Settings.Global.getUriFor(OutputProfiles.KEY), false, obs)
        }
        onDispose { runCatching { ctx.contentResolver.unregisterContentObserver(obs) } }
    }
    // Start on the jack Hione Music says is in use.
    LaunchedEffect(outputs.active) { outputs.active?.takeIf { it == OutputProfiles.PORT_35 || it == OutputProfiles.PORT_44 }?.let { port = it } }
    val rate = pickedRate ?: nowPlayingRate ?: 44100

    // Models: computed once per rate (cached inside DacFilterModel), off the main thread.
    var models by remember { mutableStateOf<Map<DacFilterModel.Filter, DacFilterModel.Model>>(emptyMap()) }
    LaunchedEffect(rate) {
        models = withContext(Dispatchers.Default) { DacFilterModel.Filter.entries.associateWith { DacFilterModel.model(it, rate) } }
    }

    val entry = outputs.forPort(port)
    val storeKey = entry?.storeKey ?: "port-$port"
    var headphone by remember { mutableStateOf<ResponseStore.Stored?>(null) }
    var measured by remember { mutableStateOf<ResponseStore.Stored?>(null) }
    var storeVersion by remember { mutableIntStateOf(0) }
    LaunchedEffect(storeKey, storeVersion) {
        withContext(Dispatchers.IO) {
            headphone = ResponseStore.load(ctx, storeKey, ResponseStore.HEADPHONE)
            measured = ResponseStore.load(ctx, storeKey, ResponseStore.MEASURED)
        }
    }

    val fromHz = if (range == GraphRange.TOP) 2000.0 else 20.0
    val toHz = when (range) { GraphRange.FULL -> 2.0 * rate; GraphRange.TOP -> 0.6 * rate; GraphRange.AUDIO -> 20000.0 }
    val g by animateFloatAsState(gainDb(gain), tween(500), label = "gain")

    // Curves for the plot, cached per (models, range).
    val curves: Map<DacFilterModel.Filter, FloatArray> = remember(models, range, rate) {
        models.mapValues { (_, m) -> DacFilterModel.logCurve(m, fromHz, toHz, POINTS) }
    }
    // The transducer: a measurement beats an imported curve.
    val transducer = measured?.curve ?: headphone?.curve?.normalized()
    val earsExtra: FloatArray? = remember(entry, transducer, measured, range, rate) {
        val eqOn = entry?.eqAudible == true
        // With the EQ off, "at your ears" would only repeat the measured line: skip it then.
        if (!eqOn && (transducer == null || measured != null)) null else FloatArray(POINTS) { i ->
            val f = fromHz * (toHz / fromHz).pow(i / (POINTS - 1.0))
            var d = 0.0
            if (eqOn) d += ResponseMath.eqDb(entry!!.preampDb, entry.bands, min(f, 20000.0), 48000)
            if (transducer != null) d += transducer.at(min(f, 20000.0))
            d.toFloat()
        }
    }
    val measuredArr: FloatArray? = remember(measured, range, rate) {
        measured?.curve?.let { c -> FloatArray(POINTS) { i -> c.at(min(fromHz * (toHz / fromHz).pow(i / (POINTS - 1.0)), 20000.0)).toFloat() } }
    }
    // Animate the bold curve from the old filter's shape to the new one.
    val bold = rememberAnimatedCurve(curves[shown])

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(lerp(HwSurface1, bg, 0.5f))
            .border(1.dp, primary.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
            .padding(12.dp)
    ) {
        Text("WHAT THE DAC DOES TO THE SOUND", color = primary, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("Frequency", tab == 0, primary) { tab = 0 }
            Chip("Impulse", tab == 1, primary) { tab = 1 }
            Spacer(Modifier.weight(1f))
            Chip("3.5 mm", port == OutputProfiles.PORT_35, secondary) { port = OutputProfiles.PORT_35 }
            Chip("4.4 mm", port == OutputProfiles.PORT_44, secondary) { port = OutputProfiles.PORT_44 }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip(if (nowPlayingRate != null) "Now playing ${fmtRate(nowPlayingRate!!)}" else "Auto 44.1 kHz", pickedRate == null, primary) { pickedRate = null }
            DacFilterModel.RATES.forEach { r -> Chip(fmtRate(r).removeSuffix(" kHz") + "k", pickedRate == r, primary) { pickedRate = r } }
        }
        Spacer(Modifier.height(8.dp))

        val m = models[shown]
        if (tab == 0) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GraphRange.entries.forEach { r -> Chip(r.label, range == r, primary) { range = r } }
                if (earsExtra != null || measuredArr != null) Chip(if (earsExtra != null) "At your ears" else "Measured", showEars, secondary) { showEars = !showEars }
            }
            Spacer(Modifier.height(6.dp))
            FrequencyGraph(
                curves = curves, bold = bold, shown = shown, gainDb = g, range = range, fromHz = fromHz, toHz = toHz, rate = rate,
                ears = if (showEars) earsExtra else null, measured = if (showEars) measuredArr else null,
                gain = gain, highPower = highPower, dre = dre, secondary = secondary,
            )
        } else {
            ImpulseGraph(m, shown, dacColor(shown, gain, dre, highPower), secondary)
        }
        Spacer(Modifier.height(8.dp))

        // Legend: tap a filter to preview it here without changing the DAC.
        Text(if (preview == null) "Tap a filter to preview it. The DAC is not changed." else "Previewing ${shown.shortLabel.removeSuffix(".")}. Tap it again to go back to the applied filter.",
            color = HwMuted, fontSize = 13.5.sp, lineHeight = 18.sp)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            DacFilterModel.Filter.entries.forEach { f ->
                val c = dacColor(f, gain, dre, highPower)
                Row(
                    Modifier.clip(RoundedCornerShape(8.dp))
                        .background(if (f == shown) c.copy(alpha = 0.22f) else HwSurface2)
                        .border(1.dp, if (f == applied) c else Color.Transparent, RoundedCornerShape(8.dp))
                        .clickable { preview = if (preview == f || f == applied) null else f }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(width = 14.dp, height = if (f == shown) 4.dp else 2.dp).background(c))
                    Spacer(Modifier.width(6.dp))
                    Text(f.shortLabel + if (f == applied) " (on)" else "", color = Color.White, fontSize = 13.5.sp, fontWeight = if (f == shown) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        if (tab == 0 && (shown == DacFilterModel.Filter.FAST_LL || shown == DacFilterModel.Filter.FAST_PC || shown == DacFilterModel.Filter.SLOW_LL || shown == DacFilterModel.Filter.SLOW_PC)) {
            Spacer(Modifier.height(4.dp))
            Text("The two ${if (shown.slow) "slow" else "fast"} filters have the same frequency response, so their lines overlap. They differ in timing: see Impulse.",
                color = HwMuted, fontSize = 13.5.sp, lineHeight = 18.sp)
        }
        if (tab == 0) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 14.dp, height = 4.dp).background(dacColor(shown, gain, dre, highPower)))
                Spacer(Modifier.width(5.dp))
                Text("At the ${if (port == OutputProfiles.PORT_44) "4.4 mm" else "3.5 mm"} jack: ${shown.shortLabel.removeSuffix(".")} at ${fmtRate(rate)}, " +
                    when (gain) { "low" -> "low gain"; "middle" -> "middle gain"; else -> "high gain" },
                    color = HwMuted, fontSize = 13.5.sp, lineHeight = 18.sp)
            }
        }
        if (measuredArr != null && tab == 0) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 14.dp, height = 2.dp).background(Color.White))
                Spacer(Modifier.width(5.dp))
                Text(if (entry?.isLineOut == true) "Measured in the car (built-in mic, shape only)" else "Measured at the listening position (built-in mic, shape only)",
                    color = HwMuted, fontSize = 13.5.sp, lineHeight = 18.sp)
            }
        }
        if (earsExtra != null && tab == 0) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 14.dp, height = 2.dp).background(secondary))
                Spacer(Modifier.width(5.dp))
                Text(earsLegend(entry, measured, headphone), color = HwMuted, fontSize = 13.5.sp, lineHeight = 18.sp)
            }
        }

        Spacer(Modifier.height(10.dp))
        Notes(m, shown, rate, gain, highPower, dre, port, entry, primary)

        Spacer(Modifier.height(10.dp))
        ProfileTools(port, entry, storeKey, measured, headphone, secondary, primary, onChanged = { storeVersion++ })
    }
}

private fun earsLegend(e: OutputProfiles.Entry?, measured: ResponseStore.Stored?, hp: ResponseStore.Stored?): String = buildList {
    add("At your ears")
    if (e?.name != null) add(e.name + if (e.eqAudible) ", EQ on" else ", EQ off")
    if (measured != null) add("measured curve") else if (hp != null) add("curve: ${hp.label}")
}.joinToString(" · ")

/** The color dacTheme gives [f] with the current gain/DRE/high power. */
private fun dacColor(f: DacFilterModel.Filter, gain: String?, dre: Boolean?, hp: Boolean?): Color =
    Color(dacTheme(f.id, gain ?: "high", dre, hp).primary)

@Composable
private fun Chip(label: String, selected: Boolean, accent: Color, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp))
            .background(if (selected) accent else HwSurface2)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) Color.Black else Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

/** Animates between successive [target] arrays (same length) over 450 ms. */
@Composable
private fun rememberAnimatedCurve(target: FloatArray?): FloatArray? {
    var from by remember { mutableStateOf<FloatArray?>(null) }
    var to by remember { mutableStateOf<FloatArray?>(null) }
    val t = remember { Animatable(1f) }
    LaunchedEffect(target) {
        if (target == null) return@LaunchedEffect
        val current = blend(from, to, t.value)
        from = if (current != null && current.size == target.size) current else target
        to = target
        t.snapTo(0f)
        t.animateTo(1f, tween(450, easing = FastOutSlowInEasing))
    }
    return blend(from, to, t.value)
}

private fun blend(a: FloatArray?, b: FloatArray?, t: Float): FloatArray? {
    if (b == null) return a
    if (a == null || a.size != b.size || t >= 1f) return b
    return FloatArray(b.size) { a[it] + (b[it] - a[it]) * t }
}

// ---------------------------------------------------------------------------------- frequency

@Composable
private fun FrequencyGraph(
    curves: Map<DacFilterModel.Filter, FloatArray>,
    bold: FloatArray?,
    shown: DacFilterModel.Filter,
    gainDb: Float,
    range: GraphRange,
    fromHz: Double,
    toHz: Double,
    rate: Int,
    ears: FloatArray?,
    measured: FloatArray?,
    gain: String?,
    highPower: Boolean?,
    dre: Boolean?,
    secondary: Color,
) {
    val tm = rememberTextMeasurer()
    val density = LocalDensity.current
    // The y window follows the gain, so a gain change slides the scale (labels are absolute dB).
    val (yTopRel, yBotRel, yStep) = when (range) {
        GraphRange.AUDIO -> Triple(1f, -6f, 1f)
        GraphRange.TOP -> Triple(1f, -10f, 1f)
        GraphRange.FULL -> Triple(10f, -150f, 20f)
    }
    // "At your ears" can go well outside the zoomed window: widen to fit it.
    var top = yTopRel
    var bot = yBotRel
    if ((ears != null || measured != null) && bold != null && range != GraphRange.FULL) {
        var lo = 0f; var hi = 0f
        ears?.let { e -> for (i in e.indices) { val v = e[i] + bold[i]; lo = min(lo, v); hi = max(hi, v) } }
        measured?.let { m -> for (v in m) { lo = min(lo, v); hi = max(hi, v) } }
        if (hi > top) top = (hi + 1f).coerceAtMost(25f)
        if (lo < bot) bot = (lo - 1f).coerceAtLeast(-40f)
    }
    val yTop = top + gainDb
    val yBot = bot + gainDb
    val step = if (top - bot > 12f && range != GraphRange.FULL) 3f else yStep

    Canvas(Modifier.fillMaxWidth().height(250.dp)) {
        val left = with(density) { 34.dp.toPx() }
        val right = with(density) { 6.dp.toPx() }
        val topPad = with(density) { 18.dp.toPx() }
        val bottom = with(density) { 18.dp.toPx() }
        val w = size.width - left - right
        val h = size.height - topPad - bottom
        fun x(f: Double) = left + (ln(f / fromHz) / ln(toHz / fromHz) * w).toFloat()
        fun y(db: Float) = topPad + (yTop - db) / (yTop - yBot) * h
        val label = TextStyle(color = HwMuted, fontSize = 9.sp)

        drawRect(Mascot.color(0xFF071C21), Offset(left, topPad), Size(w, h))

        // Band row across the top, like an IEM chart.
        BANDS.forEachIndexed { i, (name, a, b) ->
            if (a >= toHz || b <= fromHz) return@forEachIndexed
            val x0 = x(max(a, fromHz)); val x1 = x(min(b, toHz))
            drawRect(BAND_COLORS[i].copy(alpha = 0.55f), Offset(x0, 0f), Size(x1 - x0 - 1f, topPad - 4f))
            // Narrow bands (Mid high is one octave) fall back to a smaller font, then a short name.
            val style = TextStyle(color = Color.White, fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
            val t = listOf(tm.measure(name, style), tm.measure(name, style.copy(fontSize = 7.sp)), tm.measure(name.replace("Mid high", "Mid hi"), style.copy(fontSize = 7.sp)))
                .firstOrNull { it.size.width < x1 - x0 - 2 }
            if (t != null) drawText(t, topLeft = Offset((x0 + x1 - t.size.width) / 2, (topPad - 4f - t.size.height) / 2))
        }

        // Log grid: 1-2-3-4-5-6-8 per decade, labels on 20/50/100/200/500/1k...
        var dec = 10.0
        while (dec < toHz * 10) {
            for (m in listOf(1, 2, 3, 4, 5, 6, 8)) {
                val f = dec * m
                if (f < fromHz || f > toHz) continue
                val major = m == 1 || m == 2 || m == 5
                drawLine(Color.White.copy(alpha = if (major) 0.16f else 0.07f), Offset(x(f), topPad), Offset(x(f), topPad + h), 1f)
                if (major) {
                    val s = if (f >= 1000) "${trimNum(f / 1000)}k" else trimNum(f)
                    val t = tm.measure(s, label)
                    val tx = (x(f) - t.size.width / 2).coerceIn(left - 4f, size.width - t.size.width.toFloat())
                    drawText(t, topLeft = Offset(tx, topPad + h + 3f))
                }
            }
            dec *= 10
        }
        // dB grid with absolute labels.
        var d = kotlin.math.ceil(yBot / step) * step
        while (d <= yTop + 0.001f) {
            val yy = y(d)
            drawLine(Color.White.copy(alpha = if (abs(d) < 0.01f) 0.28f else 0.09f), Offset(left, yy), Offset(left + w, yy), 1f)
            val t = tm.measure(trimNum(d.toDouble()), label)
            drawText(t, topLeft = Offset(left - t.size.width - 4f, yy - t.size.height / 2))
            d += step
        }
        val dbUnit = tm.measure("dB", label)
        drawText(dbUnit, topLeft = Offset(2f, topPad - dbUnit.size.height - 1f))

        clipRect(left, topPad, left + w, topPad + h) {
            // Fs/2, Fs, 2 Fs markers when they are on the chart.
            listOf(0.5 to "Fs/2", 1.0 to "Fs", 2.0 to "2 Fs").forEach { (k, s) ->
                val f = k * rate
                if (f in fromHz..toHz * 0.999) {
                    drawLine(Color(0xFFFFC107).copy(alpha = 0.5f), Offset(x(f), topPad), Offset(x(f), topPad + h), 1.5f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                    val t = tm.measure(s, TextStyle(color = Color(0xFFFFC107), fontSize = 9.sp))
                    drawText(t, topLeft = Offset(x(f) - t.size.width - 3f, topPad + h - t.size.height - 2f))
                }
            }
            // Gain level line: where 0 dB of the filter sits at this gain.
            if (abs(gainDb) > 0.01f) {
                drawLine(Color.White.copy(alpha = 0.55f), Offset(left, y(gainDb)), Offset(left + w, y(gainDb)), 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f)))
            }
            // Every filter faint, the shown one bold.
            curves.forEach { (f, arr) ->
                if (f != shown) drawCurve(arr, gainDb, ::y, left, w, dacColor(f, gain, dre, highPower).copy(alpha = 0.35f), 1.6f)
            }
            // The measurement already went through the DAC, so it is drawn as is, at the gain level.
            if (measured != null) drawCurve(measured, gainDb, ::y, left, w, Color.White.copy(alpha = 0.85f), 2f, dotted = true)
            if (ears != null && bold != null) {
                val sum = FloatArray(bold.size) { bold[it] + ears[it] }
                drawCurve(sum, gainDb, ::y, left, w, secondary, 2.2f, dashed = true)
            }
            bold?.let { drawCurve(it, gainDb, ::y, left, w, dacColor(shown, gain, dre, highPower), 3.2f) }
        }
        // Gain label at the right end of the level line.
        val gl = when (gain) { "low" -> "Low gain −12 dB"; "middle" -> "Middle gain −6 dB"; else -> "High gain 0 dB" }
        val gt = tm.measure(gl, TextStyle(color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold))
        val gy = (y(gainDb) - gt.size.height - 2f).coerceIn(topPad, topPad + h - gt.size.height)
        drawText(gt, topLeft = Offset(left + 6f, gy))
        if (range != GraphRange.FULL) {
            val nt = tm.measure(if (measured != null) "filters: no smoothing, measured: 1/6 octave" else "no smoothing", TextStyle(color = HwMuted, fontSize = 8.5.sp))
            drawText(nt, topLeft = Offset(left + 4f, topPad + h - nt.size.height - 2f))
        }
    }
}

private fun DrawScope.drawCurve(arr: FloatArray, offset: Float, y: (Float) -> Float, left: Float, w: Float, color: Color, width: Float, dashed: Boolean = false, dotted: Boolean = false) {
    val p = Path()
    for (i in arr.indices) {
        val xx = left + w * i / (arr.size - 1)
        val yy = y((arr[i] + offset).coerceAtLeast(-400f))
        if (i == 0) p.moveTo(xx, yy) else p.lineTo(xx, yy)
    }
    drawPath(p, color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round,
        pathEffect = when {
            dashed -> PathEffect.dashPathEffect(floatArrayOf(12f, 7f))
            dotted -> PathEffect.dashPathEffect(floatArrayOf(2f, 5f))
            else -> null
        }))
}

private fun trimNum(v: Double): String =
    if (abs(v - Math.rint(v)) < 1e-6) v.toLong().toString() else String.format(java.util.Locale.US, "%.1f", v)

// ---------------------------------------------------------------------------------- impulse

@Composable
private fun ImpulseGraph(m: DacFilterModel.Model?, shown: DacFilterModel.Filter, color: Color, secondary: Color) {
    val tm = rememberTextMeasurer()
    val density = LocalDensity.current
    if (m == null) { Box(Modifier.fillMaxWidth().height(250.dp)); return }
    val ring = remember(m) { DacFilterModel.ringing(m) }
    // Peak-aligned window of -26 to +53 input samples (-0.6 to +1.2 ms at 44.1 kHz), so every
    // filter fits and the shapes compare at any rate. The axis is in ms, so higher rates show
    // as shorter ringing.
    val t0 = -26.0 / m.rateHz
    val t1 = 53.0 / m.rateHz
    val tickStep = niceStep((t1 - t0) / 6)
    val pts = remember(m) {
        val peak = m.impulse[m.peakIndex]
        val n = 600
        FloatArray(n) { i ->
            val t = t0 + (t1 - t0) * i / (n - 1)
            val idx = m.peakIndex + t / m.dt
            val k = idx.toInt()
            if (k < 0 || k >= m.impulse.size - 1) 0f
            else ((m.impulse[k] * (1 - (idx - k)) + m.impulse[k + 1] * (idx - k)) / peak).toFloat()
        }
    }
    val anim = rememberAnimatedCurve(pts)
    Canvas(Modifier.fillMaxWidth().height(250.dp)) {
        val left = with(density) { 30.dp.toPx() }
        val topPad = with(density) { 22.dp.toPx() }
        val bottom = with(density) { 18.dp.toPx() }
        val w = size.width - left - 6f
        val h = size.height - topPad - bottom
        val yMax = 1.15f; val yMin = -0.6f
        fun x(t: Double) = left + ((t - t0) / (t1 - t0) * w).toFloat()
        fun y(v: Float) = topPad + (yMax - v) / (yMax - yMin) * h
        val label = TextStyle(color = HwMuted, fontSize = 9.sp)
        drawRect(Mascot.color(0xFF071C21), Offset(left, topPad), Size(w, h))
        var t = kotlin.math.ceil(t0 / tickStep) * tickStep
        while (t <= t1 + 1e-12) {
            drawLine(Color.White.copy(alpha = if (abs(t) < 1e-9) 0.3f else 0.1f), Offset(x(t), topPad), Offset(x(t), topPad + h), 1f)
            val s = tm.measure(fmtTick(t, tickStep), label)
            drawText(s, topLeft = Offset((x(t) - s.size.width / 2).coerceIn(left - 6f, size.width - s.size.width), topPad + h + 3f))
            t += tickStep
        }
        listOf(1f, 0.5f, 0f, -0.5f).forEach { v ->
            drawLine(Color.White.copy(alpha = if (v == 0f) 0.25f else 0.08f), Offset(left, y(v)), Offset(left + w, y(v)), 1f)
            val s = tm.measure(trimNum(v.toDouble()), label)
            drawText(s, topLeft = Offset(left - s.size.width - 4f, y(v) - s.size.height / 2))
        }
        // Shade and label the ringing either side of the peak.
        val amber = Color(0xFFFFC107)
        if (ring.preSeconds > 2e-5) {
            val a = x(-ring.preSeconds).coerceAtLeast(left)
            drawRect(amber.copy(alpha = 0.10f), Offset(a, topPad), Size(x(0.0) - a, h))
            bracket(tm, "Pre-ringing ${fmtMs(ring.preSeconds)}", a, x(0.0), topPad, amber, alignRight = true)
        }
        val isNos = shown == DacFilterModel.Filter.NOS && !m.choiceIgnored
        if (ring.postSeconds > 2e-5 && !isNos) {
            val b = x(ring.postSeconds).coerceAtMost(left + w)
            drawRect(secondary.copy(alpha = 0.08f), Offset(x(0.0), topPad), Size(b - x(0.0), h))
            bracket(tm, "Post-ringing ${fmtMs(ring.postSeconds)}", x(0.0), b, topPad, secondary, alignRight = false)
        }
        clipRect(left, topPad, left + w, topPad + h) {
            anim?.let { arr ->
                val p = Path()
                for (i in arr.indices) {
                    val xx = left + w * i / (arr.size - 1)
                    if (i == 0) p.moveTo(xx, y(arr[i])) else p.lineTo(xx, y(arr[i]))
                }
                drawPath(p, color, style = Stroke(width = 3f, join = StrokeJoin.Round))
            }
        }
        val cap = tm.measure("Ringing counted while above 1% of the peak", TextStyle(color = HwMuted, fontSize = 8.5.sp))
        drawText(cap, topLeft = Offset(left + w - cap.size.width - 4f, topPad + h - cap.size.height - 2f))
        if (isNos) {
            val s = tm.measure("Each sample held for ${fmtUs(1.0 / m.rateHz)}. No filter ringing, only a small overshoot.", TextStyle(color = Color.White, fontSize = 10.sp))
            drawText(s, topLeft = Offset(left + 6f, topPad + 4f))
        } else if (ring.preSeconds <= 2e-5) {
            val s = tm.measure("No pre-ringing: everything comes after the peak.", TextStyle(color = Color.White, fontSize = 10.sp))
            drawText(s, topLeft = Offset(left + 6f, topPad + 4f))
        }
    }
}

/** Pre label ends at the peak, post label starts there, so the two never overlap. */
private fun DrawScope.bracket(tm: TextMeasurer, text: String, x0: Float, x1: Float, top: Float, color: Color, alignRight: Boolean) {
    val yb = top - 6f
    drawLine(color, Offset(x0, yb), Offset(x1, yb), 2f)
    drawLine(color, Offset(x0, yb - 4f), Offset(x0, yb + 4f), 2f)
    drawLine(color, Offset(x1, yb - 4f), Offset(x1, yb + 4f), 2f)
    val t = tm.measure(text, TextStyle(color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold))
    val cx = (if (alignRight) x1 - t.size.width - 6f else x0 + 6f).coerceIn(0f, size.width - t.size.width)
    drawText(t, topLeft = Offset(cx, yb - t.size.height - 3f))
}

private fun niceStep(raw: Double): Double {
    val p = 10.0.pow(kotlin.math.floor(kotlin.math.log10(raw)))
    return listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * p }.first { it >= raw }
}

private fun fmtTick(t: Double, step: Double): String {
    val ms = t * 1000
    val digits = when { step * 1000 >= 0.5 -> 1; step * 1000 >= 0.05 -> 2; else -> 3 }
    return String.format(java.util.Locale.US, "%.${digits}f ms", if (abs(ms) < 1e-9) 0.0 else ms)
}

private fun fmtMs(s: Double) = String.format(java.util.Locale.US, "%.2f ms", s * 1000)
private fun fmtUs(s: Double) = String.format(java.util.Locale.US, "%.1f µs", s * 1e6)

// ---------------------------------------------------------------------------------- notes

@Composable
private fun Notes(
    m: DacFilterModel.Model?, f: DacFilterModel.Filter, rate: Int, gain: String?, hp: Boolean?, dre: Boolean?,
    port: String, entry: OutputProfiles.Entry?, accent: Color,
) {
    @Composable
    fun line(title: String, body: String) {
        Text(buildString { append(title.removeSuffix(".")); append(". "); append(body) }, color = Color.White.copy(alpha = 0.88f), fontSize = 14.sp, lineHeight = 19.sp)
        Spacer(Modifier.height(6.dp))
    }
    if (m == null) return
    val r = fmtRate(rate)
    // Measured 2026-10-10 (Hione, docs/PRESS_KIT.md): the PAL audio stack runs the wired DAC at
    // 192 kHz for every track, so these curves describe the filter at the file's rate, which is
    // not what reaches the DAC yet. Say so before the theory.
    line("Right now", "The DAC runs at 192 kHz for every track, so the filter only changes the sound above about 80 kHz and all five sound the same. The curves show each filter at the file's rate, for when native-rate output lands.")
    if (m.choiceIgnored) {
        line("At $r", "The chip has one fixed filter at 352.8 and 384 kHz and ignores the filter choice. It droops about 0.04 dB by 20 kHz.")
    } else when (f) {
        DacFilterModel.Filter.NOS -> line("NOS",
            "Holds each sample until the next one. At $r the top end is ${String.format(java.util.Locale.US, "%.1f", -m.dbAt(20000.0))} dB down at 20 kHz, and the copies of the music above ${fmtRate(rate / 2)} are left in (see Out to 2×Fs). No ringing.")
        DacFilterModel.Filter.FAST_PC, DacFilterModel.Filter.FAST_LL -> line(f.shortLabel,
            "Flat to ${fmtRate((m.spec.passbandEdge * rate).toInt())} within 0.01 dB, then a steep cut. " +
                if (f.linearPhase) "Linear phase: it rings evenly before and after a sharp edge." else "Minimum phase: no ringing before an edge, more after, and less delay.")
        DacFilterModel.Filter.SLOW_PC, DacFilterModel.Filter.SLOW_LL -> line(f.shortLabel,
            "Flat to ${fmtRate((m.spec.passbandEdge * rate).toInt())}, then a gentler cut: ${String.format(java.util.Locale.US, "%.2f", -m.dbAt(20000.0))} dB down at 20 kHz in this model, shorter ringing. " +
                if (f.linearPhase) "Linear phase: rings evenly both sides." else "Minimum phase: no ringing before an edge.")
    }
    m.datasheetDelay?.let { d ->
        line("Delay", "${String.format(java.util.Locale.US, "%.2f", d / rate * 1000)} ms at $r (datasheet group delay: ${trimNum(d)} samples).")
    }
    line("Gain", when (gain) {
        "low" -> "Low puts the whole curve 12 dB lower, so each volume step is finer. It is a digital offset: same shape, same noise floor."
        "middle" -> "Middle puts the whole curve 6 dB lower. Digital offset: same shape, same noise floor."
        else -> "High is no offset, the curve sits at 0 dB. Low would move it 12 dB down. Same shape and noise floor either way."
    })
    line("High power ${if (hp == true) "on" else "off"}", "Same curve. HiBy's driver switches in an extra amp stage and its supply, so there is more voltage swing for hard-to-drive headphones and the same volume step plays louder. No output figures are published.")
    line("DRE ${if (dre == true) "on" else "off"}", "Same curve. It is meant to lower the noise floor in quiet passages. HiBy sets it through a hidden Cirrus register and the effect is not documented, so the graph cannot show it.")
    line(if (port == OutputProfiles.PORT_44) "4.4 mm balanced" else "3.5 mm",
        "Both jacks get the same filter and gain. 4.4 mm also runs the second CS43198 with the same settings. No documented level or impedance difference between the jacks, so the graph treats them the same.")
    if (entry?.impedanceOhm != null) {
        line("Impedance", "${entry.name ?: "This profile"} is ${trimNum(entry.impedanceOhm)} Ω. The M500's output impedance is not documented, so how it shifts this headphone's response is left out.")
    }
    if (entry?.isLineOut == true) {
        line("Line out", "Car and stereo aux inputs expect consumer line level, roughly 1 to 2 V at most. If it distorts at high volume, use low gain and keep high power off. The M500's output voltage is not published.")
    }
    Text("Source: Cirrus CS43198 datasheet DS1156F2, Table 3-7 and section 9.1. The curves are models built to that table, not measurements of this unit.",
        color = HwMuted, fontSize = 12.5.sp, lineHeight = 17.sp)
}

// ---------------------------------------------------------------------------------- profile tools

@Composable
private fun ProfileTools(
    port: String, entry: OutputProfiles.Entry?, storeKey: String,
    measured: ResponseStore.Stored?, headphone: ResponseStore.Stored?,
    secondary: Color, primary: Color, onChanged: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var askMeasure by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var progressText by remember { mutableStateOf("") }
    var cuts by remember(storeKey, measured) { mutableStateOf(measured?.curve?.let { ResponseMath.suggestCuts(it) }) }

    val portLabel = if (port == OutputProfiles.PORT_44) "4.4 mm" else "3.5 mm"
    Text("PROFILE ON $portLabel".uppercase(), color = primary, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
    Text(
        when {
            entry?.name != null -> "${entry.name}${if (entry.pinned) " (pinned to this jack)" else " (last used on this jack)"}. EQ ${if (entry.eqAudible) "on, ${entry.bands.count { abs(it.gainDb) > 1e-9 }} bands" else "off"}. Set profiles per jack in ${Mascot.musicAppName}, Listening profiles."
            else -> "${Mascot.musicAppName} has not published a profile for this jack yet. Pick one in ${Mascot.musicAppName}, Listening profiles, and it shows up here."
        },
        color = HwMuted, fontSize = 14.sp, lineHeight = 19.sp,
    )
    Spacer(Modifier.height(6.dp))

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val text = ctx.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() } ?: ""
                    val c = ResponseMath.parseCsv(text) ?: return@runCatching "That file has no frequency,dB columns I could read."
                    val display = ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    }
                    val name = (display ?: uri.lastPathSegment?.substringAfterLast('/') ?: "imported").substringBeforeLast('.')
                    ResponseStore.save(ctx, storeKey, ResponseStore.HEADPHONE, ResponseStore.Stored(c, name, System.currentTimeMillis()))
                    "Imported $name (${c.hz.size} points)."
                }.getOrElse { "Could not read that file: ${it.message}" }
            }
            status = r
            onChanged()
        }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) askMeasure = true else status = "Measuring needs the microphone permission."
    }

    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip(if (headphone == null) "Import curve (CSV)" else "Replace curve", false, secondary) {
            importer.launch(arrayOf("text/*", "text/csv", "text/comma-separated-values", "application/csv", "application/octet-stream"))
        }
        if (headphone != null) Chip("Remove curve", false, secondary) {
            ResponseStore.delete(ctx, storeKey, ResponseStore.HEADPHONE); status = null; onChanged()
        }
        Chip(if (measured == null) "Measure with the mic" else "Measure again", false, secondary) {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) askMeasure = true
            else micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        if (measured != null) Chip("Remove measurement", false, secondary) {
            ResponseStore.delete(ctx, storeKey, ResponseStore.MEASURED); status = null; onChanged()
        }
    }
    if (headphone != null) Text("Imported curve: ${headphone.label}. Only the shape is used (300 Hz to 3 kHz set to 0 dB).", color = HwMuted, fontSize = 13.5.sp, lineHeight = 18.sp)
    if (measured != null) Text("Measured ${android.text.format.DateFormat.format("MMM d, h:mm a", measured.atMillis)}: ${measured.label}.", color = HwMuted, fontSize = 13.5.sp, lineHeight = 18.sp)

    if (running) {
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth(), color = secondary, trackColor = HwSurface2)
        Text(progressText, color = HwMuted, fontSize = 13.5.sp)
    }
    status?.let { Spacer(Modifier.height(4.dp)); Text(it, color = Color(0xFFF1C40F), fontSize = 14.sp, lineHeight = 19.sp) }

    cuts?.takeIf { it.isNotEmpty() && measured != null }?.let { list ->
        Spacer(Modifier.height(6.dp))
        Text("Suggested correction (cuts only, at most 6 dB, nothing below 40 Hz):", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        list.forEach { b -> Text("  ${trimNum(b.hz)} Hz  ${String.format(java.util.Locale.US, "%.1f", b.gainDb)} dB  Q ${b.q}", color = HwMuted, fontSize = 13.5.sp) }
        if (entry?.profileId != null) {
            Spacer(Modifier.height(4.dp))
            Chip("Put these on ${entry.name}'s EQ in ${Mascot.musicAppName}", false, secondary) {
                OutputProfiles.sendEqToHioneMusic(ctx, entry.profileId, list, 0.0)
                status = "Sent to ${Mascot.musicAppName}. It replaces that profile's EQ with these cuts and turns EQ on. Undo it there with Reset to recommended."
            }
        }
    }

    if (askMeasure) {
        var level by remember { mutableIntStateOf(1) }
        val levels = listOf("Quiet (−30 dBFS)" to 0.0316, "Normal (−20 dBFS)" to 0.1, "Loud (−12 dBFS)" to 0.25)
        AlertDialog(
            onDismissRequest = { askMeasure = false },
            containerColor = HwSurface1,
            titleContentColor = Color.White,
            textContentColor = Color.White.copy(alpha = 0.88f),
            title = { Text("Measure the $portLabel output") },
            text = {
                Column {
                    Text("Plays three 8-second sine sweeps, 20 Hz to 20 kHz, out of the $portLabel jack and records them with the M500's built-in mic.", fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("Start with the car or amp volume LOW and turn it up only if the result says the mic barely heard it. Sweeps at high volume can damage speakers and hearing.", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("Hold or place the M500 at your listening position, mic facing the speakers, and keep quiet. The built-in mic is not calibrated: trust the shape, not the absolute level, and the top octave least of all. Music pauses during the sweeps.", fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    levels.forEachIndexed { i, (s, _) ->
                        Row(Modifier.fillMaxWidth().clickable { level = i }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.RadioButton(selected = level == i, onClick = { level = i },
                                colors = androidx.compose.material3.RadioButtonDefaults.colors(selectedColor = secondary, unselectedColor = HwMuted))
                            Text(s, fontSize = 13.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    askMeasure = false
                    running = true
                    status = null
                    val amp = levels[level].second
                    scope.launch {
                        val out = SweepMeasurement.run(ctx, port, 3, amp) { s, f ->
                            progress = (s + f) / 3f
                            progressText = "Sweep ${s + 1} of 3"
                        }
                        running = false
                        if (out.curve != null) {
                            val where = entry?.name ?: portLabel
                            ResponseStore.save(ctx, storeKey, ResponseStore.MEASURED,
                                ResponseStore.Stored(out.curve, "$where, ${out.sweepsUsed} sweeps averaged, 1/6 octave", System.currentTimeMillis()))
                            cuts = ResponseMath.suggestCuts(out.curve)
                            onChanged()
                        }
                        status = (listOfNotNull(out.error) + out.notes).joinToString(" ")
                    }
                }) { Text("Start", color = secondary, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { askMeasure = false }) { Text("Cancel", color = HwMuted) } },
        )
    }
}
