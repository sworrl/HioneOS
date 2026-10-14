package com.caf.fmradio

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Mirrors FmSongId's private minimum, for the LISTENING progress bar only. */
private const val SONG_MIN_SECONDS = 6

// ============================================================================ cover art

/**
 * Album art for song-ID results: fetched through FmNet (this app's SELinux domain cannot open
 * sockets itself) on IO, decoded down to thumbnail size,
 * kept in a small in-memory LRU. No image library: this app has none and one cover per match
 * does not justify adding one.
 */
object FmCoverArt {
    private val cache = LruCache<String, ImageBitmap>(24)

    fun cached(url: String): ImageBitmap? = cache.get(url)

    suspend fun load(url: String, targetPx: Int = 256): ImageBitmap? {
        cache.get(url)?.let { return it }
        val img = withContext(Dispatchers.IO) {
            runCatching {
                val r = FmNet.request(url)
                if (r.code !in 200..299) return@runCatching null
                val bytes = r.body
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?.asImageBitmap()
            }.getOrNull()
        }
        if (img != null) cache.put(url, img)
        return img
    }
}

@Composable
fun rememberCoverArt(url: String?): ImageBitmap? {
    val img by produceState(url?.let { FmCoverArt.cached(it) }, url) {
        if (url != null && value == null) value = FmCoverArt.load(url)
    }
    return img
}

fun openSongPage(ctx: Context, url: String?) {
    if (url.isNullOrBlank()) {
        Toast.makeText(ctx, "No link for this track", Toast.LENGTH_SHORT).show(); return
    }
    runCatching {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { Toast.makeText(ctx, "Nothing on this device can open the link", Toast.LENGTH_SHORT).show() }
}

// ============================================================================ the button

/**
 * The song-ID control in the tool row: a wide pill so it reads as the feature it is rather
 * than one more round icon. It shows the engine's phase in place: seconds of audio buffered
 * while listening, a spinner while matching. Tap identifies (or retries); long-press opens the
 * history.
 */
@Composable
fun SongIdButton(state: FmSongId.State, onHistory: () -> Unit, modifier: Modifier = Modifier) {
    val busy = state.phase == FmSongId.Phase.LISTENING || state.phase == FmSongId.Phase.MATCHING
    Row(
        modifier
            .height(46.dp)
            .glass(
                RoundedCornerShape(23.dp), accent = HionePink, accent2 = HioneTeal,
                fill = if (busy) Color(0xD0301030) else Color(0xC0200E22),
                rimAlpha = if (busy) 1f else 0.75f, shine = if (busy) 1.4f else 1f,
            )
            .pressable(onLongClick = onHistory) { if (!busy) FmRadioManager.identifySong() }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when (state.phase) {
                FmSongId.Phase.LISTENING -> ProgressRing((state.bufferedSeconds / SONG_MIN_SECONDS.toFloat()).coerceIn(0f, 1f))
                FmSongId.Phase.MATCHING -> Spinner()
                else -> Icon(Icons.Default.GraphicEq, null, tint = HionePink, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(
            when (state.phase) {
                FmSongId.Phase.LISTENING -> "${state.bufferedSeconds}s"
                FmSongId.Phase.MATCHING -> "MATCH…"
                else -> "SONG ID"
            },
            color = Color.White, fontSize = 9.5.sp, fontWeight = FontWeight.Bold,
            fontFamily = AudiowideFont, maxLines = 1,
        )
    }
}

@Composable
private fun ProgressRing(frac: Float) {
    Canvas(Modifier.size(20.dp)) {
        val sw = 2.5.dp.toPx()
        val d = size.minDimension - sw
        val tl = Offset(sw / 2, sw / 2)
        drawArc(Color.White.copy(alpha = 0.15f), 0f, 360f, false, tl, Size(d, d), style = Stroke(sw))
        drawArc(HionePink, -90f, 360f * frac, false, tl, Size(d, d), style = Stroke(sw, cap = StrokeCap.Round))
    }
}

/** Only composed while matching, so its infinite animation exists only then. */
@Composable
private fun Spinner() {
    val t = rememberInfiniteTransition(label = "spin")
    val angle = t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "a")
    Canvas(Modifier.size(20.dp).graphicsLayer { rotationZ = angle.value }) {
        val sw = 2.5.dp.toPx()
        val d = size.minDimension - sw
        drawArc(
            Brush.sweepGradient(listOf(Color.Transparent, HioneTeal, HionePink)),
            0f, 300f, false, Offset(sw / 2, sw / 2), Size(d, d), style = Stroke(sw, cap = StrokeCap.Round)
        )
    }
}

// ============================================================================ result card

/**
 * The result, floated over the bottom of the spectrum panel so it costs no layout height.
 * Shown for every phase except IDLE; dismissing it returns the engine to IDLE.
 */
@Composable
fun SongIdCard(state: FmSongId.State, onHistory: () -> Unit, modifier: Modifier = Modifier) {
    if (state.phase == FmSongId.Phase.IDLE) return
    val ctx = LocalContext.current
    Box(
        modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(14.dp), accent = HionePink, accent2 = HioneTeal,
                   fill = Color(0xEE07141B), rimAlpha = 0.9f)
            .padding(8.dp)
    ) {
        when (state.phase) {
            FmSongId.Phase.LISTENING, FmSongId.Phase.MATCHING -> Column(Modifier.padding(end = 40.dp)) {
                Text(
                    if (state.phase == FmSongId.Phase.LISTENING) "LISTENING · ${state.bufferedSeconds} s OF AUDIO"
                    else "MATCHING ${state.bufferedSeconds} s AGAINST THE CATALOG…",
                    color = HionePink, fontSize = 8.5.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont,
                )
                Spacer(Modifier.height(6.dp))
                if (state.phase == FmSongId.Phase.LISTENING) {
                    LinearProgressIndicator(
                        progress = { (state.bufferedSeconds / SONG_MIN_SECONDS.toFloat()).coerceIn(0f, 1f) },
                        color = HionePink, trackColor = Color(0x338EC5FF),
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    )
                } else {
                    LinearProgressIndicator(
                        color = HioneTeal, trackColor = Color(0x33FFAB2E),
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    )
                }
            }
            FmSongId.Phase.FOUND -> state.match?.let { m ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 34.dp)) {
                    Cover(m.coverUrl, 58.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(m.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                             maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(m.artist, color = HioneTealBright, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(m.album, "${fmtMhz(m.freqKHz)} MHz").joinToString(" · "),
                            color = HioneTextSecondary, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            LikeButton(m, compact = true)
                            CardAction("OPEN", Icons.AutoMirrored.Filled.OpenInNew, HionePink) { openSongPage(ctx, m.webUrl) }
                            CardAction("HISTORY", Icons.Default.History, HioneTeal, onHistory)
                        }
                    }
                }
            }
            else -> Column(Modifier.padding(end = 40.dp)) {
                Text(
                    if (state.phase == FmSongId.Phase.NOT_FOUND) "NO MATCH" else "SONG ID UNAVAILABLE",
                    color = if (state.phase == FmSongId.Phase.NOT_FOUND) CyberAmber else HioneNeonPink,
                    fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont,
                )
                state.message?.let {
                    Text(it, color = HioneTextPrimary, fontSize = 8.5.sp, lineHeight = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CardAction("TRY AGAIN", Icons.Default.Refresh, HionePink) { FmRadioManager.identifySong() }
                    if (state.history.isNotEmpty()) CardAction("HISTORY", Icons.Default.History, HioneTeal, onHistory)
                }
            }
        }
        Box(
            Modifier.align(Alignment.TopEnd).size(36.dp).clip(CircleShape)
                .pressable { FmRadioManager.dismissSongId() },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Close, "Dismiss", tint = HioneTextSecondary, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun CardAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, accent: Color, onClick: () -> Unit) {
    Row(
        Modifier.height(30.dp)
            .glass(RoundedCornerShape(15.dp), accent = accent, rimAlpha = 0.8f, fill = accent.copy(alpha = 0.14f))
            .pressable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = accent, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, color = accent, fontSize = 8.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
    }
}

@Composable
private fun Cover(url: String?, size: androidx.compose.ui.unit.Dp) {
    val img = rememberCoverArt(url)
    Box(
        Modifier.size(size).clip(RoundedCornerShape(10.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF0E5A66), Color(0xFF3A1030)))),
        contentAlignment = Alignment.Center
    ) {
        if (img != null) {
            Image(img, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Default.MusicNote, null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(size * 0.45f))
        }
    }
}

// ============================================================================ history sheet

@Composable
fun ColumnScope.FmSongHistory(state: FmSongId.State) {
    val ctx = LocalContext.current
    Text("SONGS IDENTIFIED", color = HioneCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold,
         fontFamily = AudiowideFont, letterSpacing = 1.sp)
    Spacer(Modifier.height(2.dp))
    Text(
        "This session, newest first. Tap one to open it. Identification sends a fingerprint of the " +
            "last few seconds of radio audio (not the audio) to Shazam's service.",
        color = HioneTextSecondary, fontSize = 8.sp, lineHeight = 11.sp,
    )
    Spacer(Modifier.height(8.dp))
    if (state.history.isEmpty()) {
        Text("Nothing yet. Tap SONG ID while music is playing.", color = HioneTextPrimary, fontSize = 9.sp)
        return
    }
    val fmt = SimpleDateFormat("HH:mm", Locale.US)
    LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(state.history.distinctBy { "${it.atMs}${it.title}" }, key = { "${it.atMs}${it.title}" }) { m ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .glass(RoundedCornerShape(12.dp), accent = HionePink, rimAlpha = 0.35f, shine = 0.6f)
                    .pressable(pressedScale = 0.98f) { openSongPage(ctx, m.webUrl) }
                    .padding(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cover(m.coverUrl, 46.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(m.title, color = Color.White, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(m.artist, color = HioneTealBright, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    m.album?.let { Text(it, color = HioneTextSecondary, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                LikeButton(m, compact = true)
                Column(horizontalAlignment = Alignment.End) {
                    Text(fmt.format(Date(m.atMs)), color = HioneTextSecondary, fontSize = 8.sp, fontFamily = AudiowideFont)
                    Text("${fmtMhz(m.freqKHz)}", color = HionePink, fontSize = 8.sp, fontFamily = AudiowideFont)
                }
            }
        }
    }
}

// ============================================================================ like

/**
 * The heart. Liking keeps the track with the station, frequency, time and place it was heard,
 * and hands it to Hione Music as something to get. The liked state is read and written off the
 * main thread (it is a small JSON file in prefs plus a hand-off to the other app).
 */
@Composable
fun LikeButton(m: FmSongId.Match, compact: Boolean = false) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var liked by remember(m.title, m.artist) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(m.title, m.artist) {
        liked = withContext(Dispatchers.IO) { runCatching { FmRadioManager.isSongLiked(ctx, m) }.getOrDefault(false) }
    }
    val on = liked == true
    val toggle: () -> Unit = {
        scope.launch {
            val now = withContext(Dispatchers.IO) { runCatching { FmRadioManager.toggleLikeSong(ctx, m) }.getOrNull() }
            if (now != null) {
                liked = now
                Toast.makeText(ctx, if (now) "Liked. Added to Wanted in Hione Music" else "Removed from Hione Music likes",
                    Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(ctx, "Could not save the like", Toast.LENGTH_SHORT).show()
            }
        }
    }
    if (compact) {
        Box(Modifier.size(44.dp).clip(CircleShape).pressable(onClick = toggle), contentAlignment = Alignment.Center) {
            Icon(if (on) Icons.Default.Favorite else Icons.Default.FavoriteBorder, if (on) "Unlike" else "Like",
                 tint = if (on) HionePink else HioneTextSecondary, modifier = Modifier.size(20.dp))
        }
    } else {
        CardAction(if (on) "LIKED" else "LIKE", if (on) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                   if (on) HioneNeonPink else HionePink, toggle)
    }
}
