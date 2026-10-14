package com.hione.player.stats

import com.hione.player.Mascot
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.hione.player.CyberDarkBg
import com.hione.player.loadArtThumb
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sin

/**
 * Hione Rewind card renderers. Pure presentation over [RewindStory]: nothing in here computes a
 * statistic — every number displayed was produced by [RewindBuilder] on IO.
 *
 * Sized for the M500's 3.5" 480x800 portrait panel: big numerals, short lines, one idea per card.
 */

// ============================================================================================ palette

val RewindTeal get() = Mascot.color(0xFFFFAB2E)
val RewindTealBright get() = Mascot.color(0xFFFFD08A)
val RewindPink get() = Mascot.color(0xFFFF2277)
val RewindPinkSoft get() = Mascot.color(0xFFFF6FA6)
val RewindInk get() = Mascot.color(0xFFF2FBFA)
val RewindMuted get() = Mascot.color(0xFF9CC4BF)

/** Card-to-card background variety without per-card art: alternate teal-led / pink-led washes. */
private fun washFor(index: Int): List<Color> = when (index % 4) {
    0 -> listOf(Mascot.color(0xFF0B3A40), CyberDarkBg, Color(0xFF12071B))
    1 -> listOf(Mascot.color(0xFF3A0A22), CyberDarkBg, Mascot.color(0xFF062A2E))
    2 -> listOf(Mascot.color(0xFF07262B), Color(0xFF12071B), CyberDarkBg)
    else -> listOf(Color(0xFF1C0A2E), CyberDarkBg, Mascot.color(0xFF0B3A40))
}

// ============================================================================================ particles

/**
 * A fixed-size particle field: positions live in preallocated FloatArrays and are advanced
 * in place once per frame — zero allocations after construction. Ticks only while [active]
 * (the page is the visible one) AND the host is RESUMED, so an off-screen page or a backgrounded
 * activity costs nothing.
 */
private class ParticleField(n: Int, seed: Int) {
    val n = n
    val x = FloatArray(n); val y = FloatArray(n); val vx = FloatArray(n); val vy = FloatArray(n)
    val r = FloatArray(n); val phase = FloatArray(n); val pink = BooleanArray(n)
    init {
        val rnd = java.util.Random(seed.toLong() * 7919L + 17L)
        for (i in 0 until n) {
            x[i] = rnd.nextFloat(); y[i] = rnd.nextFloat()
            vx[i] = (rnd.nextFloat() - 0.5f) * 0.012f
            vy[i] = -0.006f - rnd.nextFloat() * 0.014f
            r[i] = 1.2f + rnd.nextFloat() * 2.6f
            phase[i] = rnd.nextFloat() * 6.283f
            pink[i] = rnd.nextInt(5) == 0
        }
    }
    fun step(dt: Float) {
        for (i in 0 until n) {
            x[i] += vx[i] * dt; y[i] += vy[i] * dt
            if (y[i] < -0.05f) { y[i] = 1.05f; x[i] = (x[i] + 0.37f) % 1f }
            if (x[i] < -0.05f) x[i] = 1.05f else if (x[i] > 1.05f) x[i] = -0.05f
        }
    }
}

@Composable
fun RewindBackdrop(index: Int, active: Boolean, modifier: Modifier = Modifier) {
    val field = remember(index) { ParticleField(28, index) }
    var tick by remember { mutableLongStateOf(0L) }
    val lifecycleOwner = LocalLifecycleOwner.current
    if (active) {
        LaunchedEffect(field, lifecycleOwner) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                var last = 0L
                while (true) {
                    withFrameNanos { now ->
                        if (last != 0L) field.step(((now - last) / 1_000_000_000f).coerceAtMost(0.05f))
                        last = now
                        tick = now
                    }
                }
            }
        }
    }
    val wash = remember(index) { washFor(index) }
    Canvas(modifier.fillMaxSize().background(Brush.verticalGradient(wash))) {
        @Suppress("UNUSED_VARIABLE") val t = tick   // read the frame state so the canvas redraws
        val w = size.width; val h = size.height
        // soft glow blobs
        drawCircle(Brush.radialGradient(listOf(RewindTeal.copy(alpha = 0.22f), Color.Transparent), center = Offset(w * 0.15f, h * 0.18f), radius = w * 0.7f), radius = w * 0.7f, center = Offset(w * 0.15f, h * 0.18f))
        drawCircle(Brush.radialGradient(listOf(RewindPink.copy(alpha = 0.18f), Color.Transparent), center = Offset(w * 0.9f, h * 0.85f), radius = w * 0.8f), radius = w * 0.8f, center = Offset(w * 0.9f, h * 0.85f))
        val sec = tick / 1_000_000_000f
        for (i in 0 until field.n) {
            val a = 0.35f + 0.3f * sin(sec * 1.7f + field.phase[i])
            drawCircle(
                color = (if (field.pink[i]) RewindPinkSoft else RewindTealBright).copy(alpha = a.coerceIn(0.08f, 0.7f)),
                radius = field.r[i], center = Offset(field.x[i] * w, field.y[i] * h)
            )
        }
    }
}

// ============================================================================================ art

@Composable
fun RewindArt(trackId: Long?, path: String, size: androidx.compose.ui.unit.Dp, corner: androidx.compose.ui.unit.Dp = 10.dp) {
    val ctx = LocalContext.current
    val art by produceState<ImageBitmap?>(initialValue = null, trackId, path) {
        value = if (trackId == null) null else runCatching { loadArtThumb(ctx, trackId, path) }.getOrNull()
    }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(corner))
            .background(Brush.linearGradient(listOf(RewindTeal.copy(alpha = 0.35f), RewindPink.copy(alpha = 0.25f))))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(corner)),
        contentAlignment = Alignment.Center
    ) {
        val a = art
        if (a != null) Image(a, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Default.MusicNote, null, tint = RewindInk.copy(alpha = 0.6f), modifier = Modifier.size(size * 0.45f))
    }
}

// ============================================================================================ card frame

@Composable
private fun Eyebrow(text: String, color: Color = RewindTealBright) {
    Text(text.uppercase(), color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.5.sp)
}

@Composable
private fun Headline(text: String, size: Int = 40, color: Color = RewindInk) {
    Text(text, color = color, fontSize = size.sp, fontWeight = FontWeight.Black, lineHeight = (size + 4).sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun Body(text: String, color: Color = RewindMuted, size: Int = 14) {
    Text(text, color = color, fontSize = size.sp, lineHeight = (size + 5).sp)
}

/** Every card ends in the same brand line so a shared PNG is self-identifying. */
@Composable
private fun Footer(period: RewindPeriod) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(RewindTeal))
        Spacer(Modifier.width(6.dp))
        Text("${Mascot.nameUpper} REWIND · ${period.label.uppercase()}", color = RewindMuted, fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun CardFrame(period: RewindPeriod, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 18.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f, fill = false)) { content() }
        Footer(period)
    }
}

// ============================================================================================ dispatcher

@Composable
fun RewindStoryCard(story: RewindStory, period: RewindPeriod) {
    when (story) {
        is RewindStory.NotEnough -> NotEnoughCard(story, period)
        is RewindStory.TotalTime -> TotalTimeCard(story, period)
        is RewindStory.Top -> TopCard(story, period)
        is RewindStory.Clock -> ClockCard(story, period)
        is RewindStory.Streak -> StreakCard(story, period)
        is RewindStory.Discoveries -> DiscoveriesCard(story, period)
        is RewindStory.Skips -> SkipsCard(story, period)
        is RewindStory.Finished -> FinishedCard(story, period)
        is RewindStory.Hearts -> HeartsCard(story, period)
        is RewindStory.Quality -> QualityCard(story, period)
        is RewindStory.Outputs -> OutputsCard(story, period)
        is RewindStory.Genres -> GenresCard(story, period)
        is RewindStory.Summary -> SummaryCard(story, period)
    }
}

// ============================================================================================ cards

@Composable
private fun NotEnoughCard(s: RewindStory.NotEnough, period: RewindPeriod) {
    CardFrame(period) {
        Eyebrow(s.card, RewindPinkSoft)
        Spacer(Modifier.height(18.dp))
        Headline("Not enough listening yet", 30)
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(s.have.toString(), color = RewindTealBright, fontSize = 54.sp, fontWeight = FontWeight.Black, lineHeight = 56.sp)
            Text("  of ${s.need}", color = RewindMuted, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
        }
        Body("${s.unit} ${period.inWords}. This card only shows real numbers. Keep listening and it fills in.")
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.08f))) {
            Box(Modifier.fillMaxWidth((s.have.toFloat() / s.need).coerceIn(0f, 1f)).height(6.dp).background(Brush.horizontalGradient(listOf(RewindTeal, RewindPink))))
        }
    }
}

@Composable
private fun TotalTimeCard(s: RewindStory.TotalTime, period: RewindPeriod) {
    CardFrame(period) {
        Eyebrow("Time well spent")
        Spacer(Modifier.height(14.dp))
        Text(fmtBigMinutes(s.playedMs), color = RewindInk, fontSize = 52.sp, fontWeight = FontWeight.Black, lineHeight = 54.sp, maxLines = 2)
        Text("of music ${period.inWords}", color = RewindTealBright, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(18.dp))
        if (s.albumsBackToBack >= 1 && s.albumTitle != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RewindArt(s.albumArtTrackId, s.albumArtPath, 60.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("That's ${s.albumTitle}", color = RewindInk, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("back to back ${s.albumsBackToBack}×", color = RewindPinkSoft, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text("(${fmtMinutes(s.albumMs)} end to end)", color = RewindMuted, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(14.dp))
        }
        Body("${s.listens} listens · ${s.distinctTracks} tracks · ${s.distinctArtists} artists")
    }
}

@Composable
private fun TopCard(s: RewindStory.Top, period: RewindPeriod) {
    val (eyebrow, head) = when (s.kind) {
        RewindStory.TopKind.TRACKS -> "On repeat" to "Your top tracks"
        RewindStory.TopKind.ARTISTS -> "Heavy rotation" to "Your top artists"
        RewindStory.TopKind.ALBUMS -> "Front to back" to "Your top albums"
    }
    CardFrame(period) {
        Eyebrow(eyebrow, if (s.kind == RewindStory.TopKind.ARTISTS) RewindPinkSoft else RewindTealBright)
        Spacer(Modifier.height(6.dp))
        Headline(head, 28)
        Spacer(Modifier.height(12.dp))
        s.entries.forEachIndexed { i, e ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", color = if (i == 0) RewindPink else RewindTealBright, fontSize = if (i == 0) 26.sp else 18.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(26.dp))
                RewindArt(e.artTrackId, e.artPath, if (i == 0) 54.dp else 40.dp, if (s.kind == RewindStory.TopKind.ARTISTS) 27.dp else 8.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.title, color = RewindInk, fontSize = if (i == 0) 16.sp else 13.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!e.subtitle.isNullOrBlank()) Text(e.subtitle, color = RewindMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (e.qualifiedPlays > 0) "${e.qualifiedPlays} full play${if (e.qualifiedPlays == 1) "" else "s"} · ${fmtMinutes(e.playedMs)}"
                        else "${e.plays} play${if (e.plays == 1) "" else "s"} · ${fmtMinutes(e.playedMs)}",
                        color = RewindTealBright.copy(alpha = 0.85f), fontSize = 10.5.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun ClockCard(s: RewindStory.Clock, period: RewindPeriod) {
    CardFrame(period) {
        Eyebrow("Your listening clock")
        Spacer(Modifier.height(6.dp))
        Headline(s.persona, 34, RewindPinkSoft)
        Spacer(Modifier.height(6.dp))
        Body(s.blurb, size = 12)
        Spacer(Modifier.height(14.dp))
        Heatmap7x24(s.heat)
    }
}

/** 7 rows (Sun..Sat) x 24 columns, cell intensity = played_ms / max. One Canvas, no per-cell composables. */
@Composable
fun Heatmap7x24(heat: Array<LongArray>) {
    val max = remember(heat) { heat.maxOf { row -> row.maxOrNull() ?: 0L }.coerceAtLeast(1L) }
    val days = remember { listOf("S", "M", "T", "W", "T", "F", "S") }
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.width(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            days.forEach { Text(it, color = RewindMuted, fontSize = 9.sp, lineHeight = 9.sp, modifier = Modifier.height(15.dp)) }
        }
        Column(Modifier.weight(1f)) {
            Canvas(Modifier.fillMaxWidth().height(15.dp * 7)) {
                val gap = 1.5.dp.toPx()
                val cw = (size.width - gap * 23) / 24f
                val ch = (size.height - gap * 6) / 7f
                val rad = CornerRadius(2.dp.toPx())
                for (d in 0..6) for (h in 0..23) {
                    val v = heat[d][h]
                    val f = if (v == 0L) 0f else (0.18f + 0.82f * (v.toFloat() / max)).coerceIn(0f, 1f)
                    val c = if (v == 0L) Color.White.copy(alpha = 0.05f)
                    else if (f > 0.66f) RewindPink.copy(alpha = 0.35f + 0.65f * f) else RewindTeal.copy(alpha = 0.25f + 0.75f * f)
                    drawRoundRect(c, topLeft = Offset(h * (cw + gap), d * (ch + gap)), size = Size(cw, ch), cornerRadius = rad)
                }
            }
            Spacer(Modifier.height(3.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("12am", "6am", "12pm", "6pm", "11pm").forEach { Text(it, color = RewindMuted, fontSize = 9.sp) }
            }
        }
    }
}

@Composable
private fun StreakCard(s: RewindStory.Streak, period: RewindPeriod) {
    val f = remember { SimpleDateFormat("MMM d", Locale.getDefault()) }
    CardFrame(period) {
        Eyebrow("Longest streak", RewindPinkSoft)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(s.longestDays.toString(), color = RewindInk, fontSize = 72.sp, fontWeight = FontWeight.Black, lineHeight = 72.sp)
            Text("  days\n  in a row", color = RewindTealBright, fontSize = 18.sp, fontWeight = FontWeight.Bold, lineHeight = 20.sp, modifier = Modifier.padding(bottom = 10.dp))
        }
        Spacer(Modifier.height(8.dp))
        Body("${f.format(Date(s.streakStart))} → ${f.format(Date(s.streakEnd))}", RewindInk, 15)
        Spacer(Modifier.height(12.dp))
        Body("You listened on ${s.activeDays} of ${s.spanDays} day${if (s.spanDays == 1) "" else "s"} ${period.inWords} (30 s or more counts).", size = 12)
        Spacer(Modifier.height(12.dp))
        StreakDots(s.activeDays, s.spanDays)
    }
}

@Composable
private fun StreakDots(active: Int, span: Int) {
    val n = span.coerceIn(1, 60)
    val lit = (active.toFloat() / span * n).toInt().coerceIn(0, n)
    Canvas(Modifier.fillMaxWidth().height(if (n > 30) 34.dp else 16.dp)) {
        val perRow = 30
        val gap = 3.dp.toPx()
        val d = (size.width - gap * (perRow - 1)) / perRow
        for (i in 0 until n) {
            val row = i / perRow; val col = i % perRow
            drawCircle(if (i < lit) RewindTeal else Color.White.copy(alpha = 0.08f), radius = d / 2, center = Offset(col * (d + gap) + d / 2, row * (d + gap) + d / 2))
        }
    }
}

@Composable
private fun DiscoveriesCard(s: RewindStory.Discoveries, period: RewindPeriod) {
    CardFrame(period) {
        Eyebrow("New to you")
        Spacer(Modifier.height(8.dp))
        if (s.count >= Threshold.DISCOVERIES) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(s.count.toString(), color = RewindInk, fontSize = 48.sp, fontWeight = FontWeight.Black, lineHeight = 50.sp)
                Text("  first-ever listens", color = RewindTealBright, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
            }
            Spacer(Modifier.height(6.dp))
            s.newest.take(if (s.obsession != null) 3 else 5).forEach { e ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    RewindArt(e.artTrackId, e.artPath, 34.dp, 7.dp)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.title, color = RewindInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${e.subtitle.orEmpty()} · ${e.plays}×", color = RewindMuted, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        } else {
            Body("${s.count} of ${Threshold.DISCOVERIES} first-ever listens. Not enough to list yet.", size = 12)
        }
        val o = s.obsession
        if (o != null) {
            Spacer(Modifier.height(14.dp))
            Eyebrow("Your newest obsession", RewindPinkSoft)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                RewindArt(o.artTrackId, o.artPath, 64.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(o.title, color = RewindInk, fontSize = 17.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(o.subtitle.orEmpty(), color = RewindMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${s.obsessionRecent} plays in the last ${s.obsessionWindowDays} day${if (s.obsessionWindowDays == 1) "" else "s"}" +
                            (if (s.obsessionEarlier == 0) ", up from nothing" else " vs ${s.obsessionEarlier} before"),
                        color = RewindPinkSoft, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun SkipsCard(s: RewindStory.Skips, period: RewindPeriod) {
    val pct = (s.rate * 100).toInt()
    CardFrame(period) {
        Eyebrow("Skip rate", RewindPinkSoft)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("$pct%", color = RewindInk, fontSize = 64.sp, fontWeight = FontWeight.Black, lineHeight = 66.sp)
            Text("  skipped", color = RewindTealBright, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 10.dp))
        }
        Body("${s.skipped} of ${s.listens} listens ended early ${period.inWords}. ${skipVerdict(pct)}", size = 13)
        val m = s.mostSkipped
        if (m != null) {
            Spacer(Modifier.height(16.dp))
            Eyebrow("Most skipped")
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                RewindArt(m.artTrackId, m.artPath, 56.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(m.title, color = RewindInk, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(m.subtitle.orEmpty(), color = RewindMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("skipped ${s.mostSkippedCount}× before halfway (${m.plays} plays)", color = RewindPinkSoft, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        } else {
            Spacer(Modifier.height(12.dp))
            Body("No single track was skipped ${Threshold.MOST_SKIPPED}+ times before halfway.", size = 11)
        }
    }
}

private fun skipVerdict(pct: Int) = when {
    pct <= 10 -> "You commit."
    pct <= 25 -> "Picky, but fair."
    pct <= 45 -> "Restless thumb."
    else -> "Shuffle is your co-pilot."
}

@Composable
private fun FinishedCard(s: RewindStory.Finished, period: RewindPeriod) {
    val e = s.entry
    CardFrame(period) {
        Eyebrow("The one you finished most")
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { RewindArt(e.artTrackId, e.artPath, 150.dp, 16.dp) }
        Spacer(Modifier.height(16.dp))
        Text(e.title, color = RewindInk, fontSize = 24.sp, fontWeight = FontWeight.Black, lineHeight = 27.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Text(e.subtitle.orEmpty(), color = RewindMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Text("${e.qualifiedPlays} full plays", color = RewindPinkSoft, fontSize = 22.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Body("of ${e.plays} starts, heard to at least 94% without scrubbing, ${fmtMinutes(e.playedMs)} in total.", size = 12)
    }
}

@Composable
private fun HeartsCard(s: RewindStory.Hearts, period: RewindPeriod) {
    CardFrame(period) {
        Eyebrow("Hearts given", RewindPinkSoft)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Favorite, null, tint = RewindPink, modifier = Modifier.size(56.dp))
            Spacer(Modifier.width(10.dp))
            Text(s.hearts.toString(), color = RewindInk, fontSize = 64.sp, fontWeight = FontWeight.Black, lineHeight = 66.sp)
        }
        Body("${s.hearts} heart${if (s.hearts == 1) "" else "s"} across ${s.tracksHearted} track${if (s.tracksHearted == 1) "" else "s"} ${period.inWords}.", size = 13)
        val t = s.top
        if (t != null && s.topCount > 0) {
            Spacer(Modifier.height(16.dp))
            Eyebrow("Most hearted")
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                RewindArt(t.artTrackId, t.artPath, 56.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.title, color = RewindInk, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(t.subtitle.orEmpty(), color = RewindMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${s.topCount} hearts", color = RewindPinkSoft, fontSize = 13.sp, fontWeight = FontWeight.Black)
                }
            }
        }
    }
}

@Composable
private fun QualityCard(s: RewindStory.Quality, period: RewindPeriod) {
    val total = (s.hiResMs + s.losslessMs + s.lossyMs).coerceAtLeast(1L)
    val hiPct = (s.hiResMs * 100 / total).toInt()
    val llPct = (s.losslessMs * 100 / total).toInt()
    val lossyPct = 100 - hiPct - llPct
    val lead = when {
        hiPct >= 50 -> "Hi-res, mostly"
        hiPct + llPct >= 50 -> "Lossless, mostly"
        else -> "Lossy, mostly"
    }
    CardFrame(period) {
        Eyebrow("Audio quality")
        Spacer(Modifier.height(6.dp))
        Headline(lead, 32)
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().height(18.dp).clip(RoundedCornerShape(9.dp)).background(Color.White.copy(alpha = 0.08f))) {
            Row(Modifier.fillMaxSize()) {
                if (hiPct > 0) Box(Modifier.weight(hiPct.toFloat()).fillMaxSize().background(RewindPink))
                if (llPct > 0) Box(Modifier.weight(llPct.toFloat()).fillMaxSize().background(RewindTeal))
                if (lossyPct > 0) Box(Modifier.weight(lossyPct.toFloat()).fillMaxSize().background(RewindMuted.copy(alpha = 0.5f)))
            }
        }
        Spacer(Modifier.height(12.dp))
        Legend(RewindPink, "Hi-res (>48 kHz or >16-bit)", "$hiPct% · ${fmtMinutes(s.hiResMs)}")
        Legend(RewindTeal, "Lossless (CD quality)", "$llPct% · ${fmtMinutes(s.losslessMs)}")
        Legend(RewindMuted.copy(alpha = 0.5f), "Lossy", "$lossyPct% · ${fmtMinutes(s.lossyMs)}")
        Spacer(Modifier.height(12.dp))
        val best = listOfNotNull(s.bestRateHz?.let { fmtRate(it) }, s.bestBits?.let { "$it-bit" }).joinToString(" / ")
        Body(if (best.isNotBlank()) "Highest played: $best · ${s.knownListens} listens with a known format" else "${s.knownListens} listens with a known format", size = 11)
    }
}

@Composable
private fun Legend(c: Color, label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(c))
        Spacer(Modifier.width(8.dp))
        Text(label, color = RewindInk, fontSize = 12.5.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, color = RewindMuted, fontSize = 11.sp)
    }
}

private fun fmtRate(hz: Int): String = if (hz % 1000 == 0) "${hz / 1000} kHz" else String.format(Locale.US, "%.1f kHz", hz / 1000f)

@Composable
private fun OutputsCard(s: RewindStory.Outputs, period: RewindPeriod) {
    val total = s.shares.sumOf { it.playedMs }.coerceAtLeast(1L)
    val top = s.shares.firstOrNull()
    CardFrame(period) {
        Eyebrow("How you listened", RewindPinkSoft)
        Spacer(Modifier.height(6.dp))
        Headline(top?.let { outputName(it.output) } ?: "—", 36)
        if (top != null) Body("${(top.playedMs * 100 / total).toInt()}% of your listening ${period.inWords}", RewindTealBright, 14)
        Spacer(Modifier.height(16.dp))
        s.shares.take(5).forEach { o ->
            val frac = o.playedMs.toFloat() / total
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text(outputName(o.output), color = RewindInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("${(frac * 100).toInt()}% · ${fmtMinutes(o.playedMs)} · ${o.listens}", color = RewindMuted, fontSize = 11.sp)
                }
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.07f))) {
                    Box(Modifier.fillMaxWidth(frac.coerceIn(0.02f, 1f)).height(8.dp).background(Brush.horizontalGradient(listOf(RewindTeal, RewindPink))))
                }
            }
        }
    }
}

@Composable
private fun GenresCard(s: RewindStory.Genres, period: RewindPeriod) {
    val total = s.totalMs.coerceAtLeast(1L)
    CardFrame(period) {
        Eyebrow("Your taste profile")
        Spacer(Modifier.height(6.dp))
        Headline(s.shares.firstOrNull()?.first ?: "—", 34, RewindPinkSoft)
        Body("is your sound ${period.inWords}", RewindTealBright, 14)
        Spacer(Modifier.height(16.dp))
        s.shares.forEachIndexed { i, (g, ms) ->
            val frac = ms.toFloat() / total
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", color = RewindTealBright, fontSize = 14.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(22.dp))
                Column(Modifier.weight(1f)) {
                    Text(g, color = RewindInk, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.07f))) {
                        Box(Modifier.fillMaxWidth(frac.coerceIn(0.02f, 1f)).height(6.dp).background(if (i == 0) RewindPink else RewindTeal))
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text("${(frac * 100).toInt()}%", color = RewindMuted, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Body("From the genre tags in your own files (${s.taggedListens} tagged listens).", size = 10)
    }
}

@Composable
private fun SummaryCard(s: RewindStory.Summary, period: RewindPeriod) {
    CardFrame(period) {
        Eyebrow("${Mascot.name} Rewind", RewindPinkSoft)
        Spacer(Modifier.height(4.dp))
        Headline(period.label, 34)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            RewindArt(s.artTrackId, s.artPath, 92.dp, 14.dp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(fmtBigMinutes(s.minutes * 60_000L), color = RewindInk, fontSize = 30.sp, fontWeight = FontWeight.Black, lineHeight = 32.sp)
                Text("${s.listens} listens", color = RewindTealBright, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(14.dp))
        SummaryRow("Top track", s.topTrack)
        SummaryRow("Top artist", s.topArtist)
        SummaryRow("Top album", s.topAlbum)
        SummaryRow("Top genre", s.topGenre)
        SummaryRow("Clock", s.persona)
        SummaryRow("Streak", if (s.longestStreak >= Threshold.STREAK_DAYS) "${s.longestStreak} days" else null)
        SummaryRow("Hearts", if (s.hearts > 0) s.hearts.toString() else null)
        SummaryRow("Discoveries", if (s.discoveries >= Threshold.DISCOVERIES) s.discoveries.toString() else null)
    }
}

@Composable
private fun SummaryRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 2.5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label.uppercase(), color = RewindMuted, fontSize = 9.5.sp, letterSpacing = 1.2.sp, modifier = Modifier.width(78.dp))
        Text(value, color = RewindInk, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ============================================================================================ helpers

/** "3h 12m" / "1d 4h" / "42 min" — for the giant headline number. */
internal fun fmtBigMinutes(ms: Long): String {
    val min = ms / 60_000L
    return when {
        min < 60 -> "$min min"
        min < 60 * 24 -> "${min / 60}h ${min % 60}m"
        else -> "${min / 1440}d ${(min % 1440) / 60}h"
    }
}
