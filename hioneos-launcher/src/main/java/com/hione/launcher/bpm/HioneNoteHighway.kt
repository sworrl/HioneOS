package com.hione.launcher.bpm

import com.hione.launcher.Mascot
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.android.awaitFrame
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Vertical Multi-Lane Note Highway (Top to Bottom).
 *
 * Supports 1x up to 6x simultaneous noteways (and hidden 8x / 10x past Level 1000)
 * with full two-thumb multitouch support.
 *
 * HOW IT WORKS:
 * - Notes travel from TOP (Y = 0) to BOTTOM, arriving at the hit line (Y = hitY).
 * - Multi-lane distribution: notes are rhythmically assigned across lanes. On downbeats,
 *   chords spawn across both halves so players hit notes simultaneously with two thumbs!
 * - Two-thumb Multitouch: pointerInput detects independent touch downs across all lanes.
 * - Reactive skin styling: note silhouettes, vertical scrolling textures, and hit effects.
 */
/**
 * Phase-locked, monotonic beat grid for the vertical Note Highway.
 *
 * WHY THIS EXISTS:
 * Previously, notes were indexed by relative offset `i` from `lastPulseEpochMs`. Every time a new
 * beat pulse arrived, `lastPulseEpochMs` jumped forward by one beat, shifting `i` by 1 for every
 * note on screen and causing notes to teleport between lanes on every beat!
 *
 * [HioneHighwayGrid] maintains a continuous `originEpochMs` and assigns every beat an unchanging
 * absolute integer index (`absBeatIdx`). Beat #1042 stays Beat #1042 in its assigned lane from the
 * moment it enters the top of the screen until it passes the bottom receptor.
 */
object HioneHighwayGrid {
    @Volatile private var originEpochMs: Long = 0L
    @Volatile private var currentPeriodMs: Long = 500L
    private val hitLaneMaskByBeat = java.util.concurrent.ConcurrentHashMap<Long, Int>()

    fun syncAnchor(lastPulseEpochMs: Long, beatIntervalMs: Long, nowMs: Long): Long {
        val period = beatIntervalMs.coerceIn(60L, 3000L)
        if (originEpochMs <= 0L || abs(period - currentPeriodMs) > 6L) {
            currentPeriodMs = period
            originEpochMs = if (lastPulseEpochMs > 0L && abs(nowMs - lastPulseEpochMs) < 10_000L) {
                lastPulseEpochMs
            } else {
                nowMs
            }
            return originEpochMs
        }
        currentPeriodMs = period
        if (lastPulseEpochMs > 0L && abs(nowMs - lastPulseEpochMs) < 5_000L) {
            val k = Math.round((lastPulseEpochMs - originEpochMs).toDouble() / period.toDouble())
            val predicted = originEpochMs + k * period
            val err = lastPulseEpochMs - predicted
            // Smoothly slew phase without ever changing the integer beat index k
            originEpochMs += if (abs(err) > period / 2) err else (err * 0.20).toLong()
        }
        return originEpochMs
    }

    fun nearestBeatIndex(targetEpochMs: Long, beatIntervalMs: Long): Long {
        val period = beatIntervalMs.coerceIn(60L, 3000L)
        val origin = if (originEpochMs > 0L) originEpochMs else syncAnchor(0L, period, targetEpochMs)
        return Math.round((targetEpochMs - origin).toDouble() / period.toDouble())
    }

    fun beatEpochAt(absBeatIdx: Long, beatIntervalMs: Long): Long {
        val period = beatIntervalMs.coerceIn(60L, 3000L)
        return originEpochMs + absBeatIdx * period
    }

    fun markLaneHit(absBeatIdx: Long, lane: Int) {
        val bit = 1 shl lane.coerceIn(0, 15)
        val prev = hitLaneMaskByBeat[absBeatIdx] ?: 0
        hitLaneMaskByBeat[absBeatIdx] = prev or bit
        if (hitLaneMaskByBeat.size > 64) {
            val cutoff = absBeatIdx - 24L
            val it = hitLaneMaskByBeat.keys.iterator()
            while (it.hasNext()) {
                if (it.next() < cutoff) it.remove()
            }
        }
    }

    fun isLaneHit(absBeatIdx: Long, lane: Int): Boolean {
        val mask = hitLaneMaskByBeat[absBeatIdx] ?: return false
        return (mask and (1 shl lane.coerceIn(0, 15))) != 0
    }

    fun areAllLanesHit(absBeatIdx: Long, requiredLanes: List<Int>): Boolean {
        val mask = hitLaneMaskByBeat[absBeatIdx] ?: return false
        return requiredLanes.all { l -> (mask and (1 shl l.coerceIn(0, 15))) != 0 }
    }
}

@Composable
fun HioneNoteHighway(
    lastPulseEpochMs: Long,
    beatIntervalMs: Long,
    calibrationMs: Int,
    windows: HioneRhythmTiming.Windows,
    lastTapMs: Long,
    lastOffsetMs: Int,
    accent: Color,
    accent2: Color = accent,
    laneHeight: androidx.compose.ui.unit.Dp = 230.dp,
    hitFraction: Float = 0.82f,
    laneCount: Int = 1,
    lastTappedLane: Int = 0,
    isPlaying: Boolean = true,
    onTapLane: ((laneIndex: Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
    skin: HioneBeatClickerEngine.BpmSkin = HioneBeatClickerEngine.BpmSkin.SAKURA_DREAM,
    fever: Boolean = false,
    chaos: HioneStagePerformance.Chaos = HioneStagePerformance.Chaos.NONE,
    comboRung: Int = 0,
    lastAccuracy: HitAccuracy? = null,
    content: (@Composable androidx.compose.foundation.layout.BoxScope.() -> Unit)? = null
) {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    val laneSparkAge = remember { FloatArray(10) { 1f } }
    val lanePressed = remember { BooleanArray(10) { false } }
    val lastSeenTap = remember { mutableLongStateOf(0L) }

    val activeLanes = laneCount.coerceIn(1, 10)
    // Keep the callback reference updated without restarting pointerInput on recomposition!
    val currentOnTapLane = androidx.compose.runtime.rememberUpdatedState(onTapLane)

    val notePath = remember(skin.nodeShape) { unitShapePath(skin.nodeShape) }
    val hexPath = remember { unitShapePath(HioneBeatClickerEngine.NodeShape.HEX) }

    LaunchedEffect(Unit) {
        while (true) {
            awaitFrame()
            val curNow = System.currentTimeMillis()
            now.longValue = curNow
            HioneHighwayGrid.syncAnchor(lastPulseEpochMs, beatIntervalMs, curNow)
            if (lastSeenTap.longValue != lastTapMs && lastTapMs > 0L) {
                lastSeenTap.longValue = lastTapMs
                val l = lastTappedLane.coerceIn(0, activeLanes - 1)
                laneSparkAge[l] = 0f
            }
            for (k in 0 until 10) {
                if (laneSparkAge[k] < 1f) {
                    laneSparkAge[k] = (laneSparkAge[k] + 0.055f).coerceAtMost(1f)
                }
            }
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(laneHeight)
            .pointerInput(activeLanes) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val laneW = (size.width.toFloat() / activeLanes).coerceAtLeast(1f)
                        for (change in event.changes) {
                            val lane = (change.position.x / laneW).toInt().coerceIn(0, activeLanes - 1)
                            if (change.changedToDown()) {
                                lanePressed[lane] = true
                                laneSparkAge[lane] = 0f
                                currentOnTapLane.value?.invoke(lane)
                                change.consume()
                            } else if (!change.pressed) {
                                lanePressed[lane] = false
                            }
                        }
                    }
                }
            },
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        Canvas(Modifier.fillMaxWidth().height(laneHeight)) {
            val w = size.width
            val h = size.height

            // Hit line sits near bottom (Y = h * hitFraction)
            val hitY = h * hitFraction
            val period = beatIntervalMs.coerceIn(60L, 3000L).toFloat()
            val live = isPlaying && beatIntervalMs in 60..3000

            val runway = hitY
            val speed = if (chaos == HioneStagePerformance.Chaos.DOUBLE_TIME) 0.5f else 1f
            val lookaheadMs = (period * 2.3f).coerceIn(750f, 2400f) * speed
            val pxPerMs = runway / lookaheadMs

            val t = now.longValue - calibrationMs
            val originMs = HioneHighwayGrid.syncAnchor(lastPulseEpochMs, beatIntervalMs, now.longValue)
            val sinceOrigin = (t - originMs).toFloat()
            val centerBeatIdx = Math.round((t - originMs).toDouble() / period.toDouble())

            val phase = ((sinceOrigin.mod(period)) / period).coerceIn(0f, 1f)
            val beat = (1f - phase).coerceIn(0f, 1f).let { it * it }

            // Vertical background wash
            drawVerticalLane(w, h, hitY, accent, accent2, fever)

            // Texture scrolling downwards
            val scrollPx = (t.mod(60_000L)).toFloat() * pxPerMs
            drawVerticalLaneTexture(skin.laneStyle, w, h, scrollPx, beat, accent, accent2, hexPath)

            val laneW = w / activeLanes

            // Highlight pressed / recently tapped lane columns (Guitar Hero fret column glow)
            if (activeLanes > 1) {
                for (k in 0 until activeLanes) {
                    val fAge = laneSparkAge[k]
                    val colAlpha = if (lanePressed[k]) 0.22f else if (fAge < 0.5f) (0.5f - fAge) * 0.35f else 0f
                    if (colAlpha > 0f) {
                        val fretC = laneFretColor(k, activeLanes, accent, accent2)
                        drawRect(
                            brush = Brush.verticalGradient(
                                listOf(Color.Transparent, fretC.copy(alpha = colAlpha), fretC.copy(alpha = colAlpha * 1.4f))
                            ),
                            topLeft = Offset(k * laneW, 0f),
                            size = Size(laneW, h)
                        )
                    }
                }
            }

            // Multiple lane dividers
            for (k in 1 until activeLanes) {
                val divX = k * laneW
                val divAlpha = 0.28f + 0.05f * comboRung.coerceIn(0, 6) + 0.15f * beat
                drawLine(
                    accent.copy(alpha = divAlpha),
                    Offset(divX, 0f),
                    Offset(divX, h),
                    strokeWidth = 2.2f
                )
            }

            // Window timing bands around hit line
            drawVerticalWindowBands(w, hitY, pxPerMs, windows, accent, accent2)

            // Receptors on the hit line for each lane (colored per fret in multi-lane mode!)
            for (k in 0 until activeLanes) {
                val receptorX = (k + 0.5f) * laneW
                val fretColor = laneFretColor(k, activeLanes, accent, accent2)
                val tappedNow = lanePressed[k] || laneSparkAge[k] < 0.32f
                drawReceptor(
                    receptorX, hitY,
                    (laneW * 0.38f).coerceAtMost(46f),
                    fretColor, accent2, beat, fever, tappedNow
                )
            }

            // Hit line across all lanes
            drawVerticalHitLine(w, hitY, if (fever) Color.White else accent, phase)

            if (!live) return@Canvas

            // Notes flowing downwards — indexed by monotonic absBeatIdx so notes NEVER jump lanes!
            for (step in -1..10) {
                val absBeatIdx = centerBeatIdx + step
                val beatAt = originMs + absBeatIdx * beatIntervalMs
                val msAway = (beatAt - t).toFloat()
                if (msAway > lookaheadMs) break
                val y = hitY - msAway * pxPerMs
                if (y < -40f || y > h + 40f) continue

                val idxInt = absBeatIdx.toInt()
                val strong = (idxInt.mod(4)) == 0
                val lucky = HioneRhythmTiming.isLuckyBeat(beatAt, beatIntervalMs)

                val fog = if (chaos.hidesBeat) ((msAway / lookaheadMs - 0.35f) / 0.25f).coerceIn(0f, 1f) else 1f
                if (fog <= 0f) continue

                // Deterministic lane assignment for this absolute beat index
                val targetLanes = noteLanesForBeat(idxInt, activeLanes)
                val isChord = targetLanes.size > 1

                // Draw chord tie-bar connecting simultaneous 2-thumb notes (like Guitar Hero power chords)
                if (isChord && !HioneHighwayGrid.areAllLanesHit(absBeatIdx, targetLanes)) {
                    val firstX = (targetLanes.first() + 0.5f) * laneW
                    val lastX = (targetLanes.last() + 0.5f) * laneW
                    drawLine(
                        Color.White.copy(alpha = 0.65f * fog),
                        Offset(firstX, y),
                        Offset(lastX, y),
                        strokeWidth = 4.5f
                    )
                }

                for (lane in targetLanes) {
                    // Consumed notes that were already hit explode at the receptor and don't fall past
                    if (HioneHighwayGrid.isLaneHit(absBeatIdx, lane)) continue

                    val laneColor = laneFretColor(lane, activeLanes, accent, accent2)
                    val noteColor = when {
                        lucky -> LUCKY_GOLD
                        fever -> Color.hsv(((idxInt * 47f + t / 9f) % 360f + 360f) % 360f, 0.75f, 1f)
                        activeLanes > 1 -> laneColor
                        else -> lerpColor(accent, accent2, (idxInt.mod(8)) / 8f)
                    }
                    val noteX = (lane + 0.5f) * laneW
                    drawVerticalNote(
                        noteX, y, (laneW * 0.42f).coerceAtMost(50f), msAway, lookaheadMs,
                        strong || isChord, noteColor, notePath, skin.nodeShape, fog, lucky, t
                    )
                }
            }

            // Per-lane hit sparks (supports simultaneous two-thumb sparks!)
            for (lane in 0 until activeLanes) {
                val age = laneSparkAge[lane]
                if (age < 1f) {
                    val sparkX = (lane + 0.5f) * laneW
                    val sparkY = hitY - (if (lane == lastTappedLane) lastOffsetMs else 0) * pxPerMs
                    val a = 1f - age
                    val fretC = laneFretColor(lane, activeLanes, accent, accent2)
                    val c = if (activeLanes > 1 && lastAccuracy?.isGreatOrBetter == true) fretC else sparkColor(lastAccuracy, fretC)

                    drawCircle(c.copy(alpha = 0.65f * a), radius = (laneW * 0.36f).coerceAtMost(48f) * (0.8f + 0.55f * age), center = Offset(sparkX, sparkY))
                    drawLine(c.copy(alpha = a), Offset(sparkX - laneW * 0.42f, sparkY), Offset(sparkX + laneW * 0.42f, sparkY), strokeWidth = 3f)

                    if (lastAccuracy?.isGreatOrBetter == true) {
                        drawCircle(
                            c.copy(alpha = 0.45f * a),
                            radius = (laneW * 0.46f).coerceAtMost(58f) * (1f + 0.85f * age),
                            center = Offset(sparkX, sparkY),
                            style = Stroke(width = 2.5f)
                        )
                    }
                    if (lastAccuracy == HitAccuracy.PERFECT) {
                        val r1 = (laneW * 0.25f).coerceAtMost(32f) * (1f + 0.6f * age)
                        val r2 = r1 + (laneW * 0.32f).coerceAtMost(40f) * a
                        for (k in 0 until 8) {
                            val ang = k * 0.785398f + age
                            drawLine(
                                Color.White.copy(alpha = 0.88f * a),
                                Offset(sparkX + cos(ang) * r1, sparkY + sin(ang) * r1),
                                Offset(sparkX + cos(ang) * r2, sparkY + sin(ang) * r2),
                                strokeWidth = 2.2f
                            )
                        }
                    }
                }
            }
        }

        // Overlay content (e.g. central beat node for 1-lane mode)
        if (content != null && activeLanes == 1) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(laneHeight)
                    .layout { measurable, constraints ->
                        val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                        val x = (constraints.maxWidth - p.width) / 2
                        val y = (constraints.maxHeight * hitFraction).toInt() - p.height / 2
                        layout(constraints.maxWidth, constraints.maxHeight) { p.place(x, y) }
                    },
                contentAlignment = androidx.compose.ui.Alignment.Center,
                content = content
            )
        }
    }
}

/** Distinct Guitar Hero / Arcade Fret Colors per lane when in multi-lane mode. */
private fun laneFretColor(lane: Int, laneCount: Int, accent: Color, accent2: Color): Color {
    if (laneCount <= 1) return accent
    return when (lane % 6) {
        0 -> Color(0xFF00FFCC) // Left Fret 1: Cyber Mint / Green
        1 -> Color(0xFFFF3385) // Right Fret 2: Neon Pink / Red
        2 -> Color(0xFFFFD166) // Fret 3: Solar Gold / Yellow
        3 -> Color(0xFF00B8FF) // Fret 4: Electric Blue
        4 -> Color(0xFFFF6600) // Fret 5: Hyper Orange
        else -> Color(0xFFCC66FF) // Fret 6: Plasma Purple
    }
}

/**
 * Guitar-Hero style musical riff generator for [absBeatIdx].
 *
 * Because [absBeatIdx] is monotonic from [HioneHighwayGrid], a note's lane never changes while
 * falling down the screen.
 * - 1x mode (Easy): Single central lane.
 * - 2x mode (Medium): Alternating L/R grooves, double-taps (L-L / R-R), and 2-thumb Power Chords!
 * - 3x..10x mode (Hard/Expert): Melodic step riffs across all lanes + downbeat 2-thumb chords.
 */
fun noteLanesForBeat(absBeatIdx: Int, laneCount: Int): List<Int> {
    if (laneCount <= 1) return listOf(0)
    val m16 = absBeatIdx.mod(16)
    if (laneCount == 2) {
        return when (m16) {
            0 -> listOf(0)
            1 -> listOf(1)
            2 -> listOf(0)
            3 -> listOf(1)
            4 -> listOf(0)
            5 -> listOf(0) // Double Left
            6 -> listOf(1)
            7 -> listOf(0, 1) // 2-Thumb Power Chord!
            8 -> listOf(1)
            9 -> listOf(0)
            10 -> listOf(1)
            11 -> listOf(1) // Double Right
            12 -> listOf(0)
            13 -> listOf(1)
            14 -> listOf(0)
            else -> listOf(0, 1) // 2-Thumb Power Chord!
        }
    }
    // 3 to 10 lanes: Power chord every 8th beat, melodic staircase/alternating riff otherwise
    if (m16 == 7 || m16 == 15) {
        val half = (laneCount / 2).coerceAtLeast(1)
        val left = (absBeatIdx / 8).mod(half)
        val right = half + ((absBeatIdx / 8 + 1).mod((laneCount - half).coerceAtLeast(1)))
        return if (left != right) listOf(left, right) else listOf(0, laneCount - 1)
    }
    // Smooth Guitar Hero style stepping pattern across 0 until laneCount
    val cycle = (laneCount * 2 - 2).coerceAtLeast(2)
    val pos = (absBeatIdx + (absBeatIdx / 8)).mod(cycle)
    val primary = if (pos < laneCount) pos else cycle - pos
    return listOf(primary.coerceIn(0, laneCount - 1))
}

private val LUCKY_GOLD = Color(0xFFFFD700)

private fun sparkColor(a: HitAccuracy?, accent: Color): Color = when (a) {
    HitAccuracy.PERFECT -> Mascot.color(0xFFFF3385)
    HitAccuracy.GREAT -> accent
    HitAccuracy.GOOD -> Mascot.color(0xFF66E0D8)
    HitAccuracy.OK -> Color(0xFFFFD166)
    HitAccuracy.MISS -> Color(0xFFFF4D5E)
    null -> accent
}

private fun lerpColor(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = 1f
)

internal fun unitShapePath(shape: HioneBeatClickerEngine.NodeShape): Path {
    val p = Path()
    fun poly(sides: Int, rot: Double) {
        for (i in 0 until sides) {
            val a = Math.toRadians(rot + i * 360.0 / sides)
            val x = cos(a).toFloat(); val y = sin(a).toFloat()
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
    }
    fun star(points: Int, inner: Float) {
        val steps = points * 2
        for (i in 0 until steps) {
            val r = if (i % 2 == 0) 1f else inner
            val a = Math.toRadians(-90.0 + i * 360.0 / steps)
            val x = (r * cos(a)).toFloat(); val y = (r * sin(a)).toFloat()
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
    }
    when (shape) {
        HioneBeatClickerEngine.NodeShape.ORB -> p.addOval(androidx.compose.ui.geometry.Rect(-1f, -1f, 1f, 1f))
        HioneBeatClickerEngine.NodeShape.DIAMOND -> poly(4, -90.0)
        HioneBeatClickerEngine.NodeShape.HEX -> poly(6, -90.0)
        HioneBeatClickerEngine.NodeShape.STAR -> star(5, 0.46f)
        HioneBeatClickerEngine.NodeShape.SNOWFLAKE -> star(6, 0.55f)
        HioneBeatClickerEngine.NodeShape.HEART -> {
            p.moveTo(0f, 0.85f)
            p.cubicTo(-1.25f, 0.05f, -0.75f, -1.05f, 0f, -0.45f)
            p.cubicTo(0.75f, -1.05f, 1.25f, 0.05f, 0f, 0.85f)
            p.close()
        }
    }
    return p
}

private fun DrawScope.drawVerticalLane(w: Float, h: Float, hitY: Float, accent: Color, accent2: Color, fever: Boolean) {
    val lift = if (fever) 0.12f else 0f
    drawRect(
        brush = Brush.verticalGradient(
            listOf(
                accent2.copy(alpha = 0.04f + lift),
                accent.copy(alpha = 0.12f + lift),
                accent2.copy(alpha = 0.22f + lift),
                accent.copy(alpha = 0.32f + lift)
            )
        ),
        size = Size(w, h)
    )
}

private fun DrawScope.drawVerticalLaneTexture(
    style: HioneBeatClickerEngine.LaneStyle,
    w: Float, h: Float, scrollPx: Float, beat: Float,
    accent: Color, accent2: Color, hexPath: Path
) {
    when (style) {
        HioneBeatClickerEngine.LaneStyle.PETAL_STREAM -> {
            val spacing = h / 7f
            for (i in 0 until 9) {
                val y = ((i * spacing + scrollPx) % h + h) % h
                val x = w * (0.2f + 0.6f * (((i * 37) % 10) / 10f)) + sin((y / h) * 6.28f + i) * w * 0.04f
                drawCircle(accent.copy(alpha = 0.16f), radius = 6f, center = Offset(x, y))
                drawCircle(accent2.copy(alpha = 0.10f), radius = 9f, center = Offset(x + 5f, y))
            }
        }
        HioneBeatClickerEngine.LaneStyle.NEON_GRID -> {
            val spacing = h * 0.18f
            val n = (h / spacing).toInt() + 2
            val off = ((scrollPx % spacing) + spacing) % spacing
            for (i in 0..n) {
                val y = i * spacing + off
                drawLine(accent.copy(alpha = 0.10f + 0.12f * beat), Offset(0f, y), Offset(w, y), strokeWidth = 1f)
            }
        }
        HioneBeatClickerEngine.LaneStyle.HONEYCOMB -> {
            val r = 16f
            val spacing = r * 2.2f
            val n = (h / spacing).toInt() + 2
            val off = ((scrollPx % spacing) + spacing) % spacing
            for (i in 0..n) {
                val y = i * spacing + off
                val x = (w * 0.5f) + sin(i * 1.5f) * (w * 0.3f)
                translate(x, y) {
                    scale(r, r, pivot = Offset.Zero) {
                        drawPath(hexPath, accent.copy(alpha = 0.08f + 0.06f * beat))
                    }
                }
            }
        }
        HioneBeatClickerEngine.LaneStyle.VELVET_LACE -> {
            drawRect(accent2.copy(alpha = 0.08f * beat), size = Size(w, h))
            val spacing = h * 0.12f
            val n = (h / spacing).toInt() + 2
            val off = ((scrollPx % spacing) + spacing) % spacing
            for (i in 0..n) {
                val y = i * spacing + off
                val big = i % 2 == 0
                drawCircle(accent.copy(alpha = if (big) 0.28f else 0.15f), radius = if (big) 3.5f else 2f, center = Offset(w * 0.1f, y))
                drawCircle(accent.copy(alpha = if (big) 0.28f else 0.15f), radius = if (big) 3.5f else 2f, center = Offset(w * 0.9f, y))
            }
        }
        HioneBeatClickerEngine.LaneStyle.FROST_STREAK -> {
            val spacing = h / 6f
            for (i in 0 until 8) {
                val y = ((i * spacing + scrollPx) % h + h) % h
                val x = w * (0.2f + 0.15f * (i % 4))
                drawLine(
                    Color.White.copy(alpha = 0.12f + 0.08f * beat),
                    Offset(x, y), Offset(x + w * 0.15f, y + h * 0.1f),
                    strokeWidth = 1.2f
                )
            }
        }
        HioneBeatClickerEngine.LaneStyle.HOLO_SCAN -> {
            val band = h * 0.10f
            val roll = ((scrollPx * 0.2f) % (band * 2f) + band * 2f) % (band * 2f)
            var y = -band * 2f + roll
            while (y < h) {
                drawRect(accent.copy(alpha = 0.06f), topLeft = Offset(0f, y), size = Size(w, band))
                y += band * 2f
            }
        }
    }
}

private fun DrawScope.drawReceptor(
    x: Float, hitY: Float, radius: Float,
    accent: Color, accent2: Color, beat: Float, fever: Boolean, tapped: Boolean
) {
    val r = radius * (if (tapped) 1.15f else 1.0f)
    val alpha = if (tapped) 0.85f else (0.25f + 0.25f * beat)

    // Outer glow ring
    drawCircle(
        brush = Brush.radialGradient(
            listOf(accent.copy(alpha = alpha), Color.Transparent),
            center = Offset(x, hitY),
            radius = r * 1.5f
        ),
        radius = r * 1.5f,
        center = Offset(x, hitY)
    )

    // Receptor base
    drawCircle(
        color = Color(0x3310121A),
        radius = r,
        center = Offset(x, hitY)
    )

    // Target ring
    drawCircle(
        color = if (fever) Color.White else accent.copy(alpha = alpha),
        radius = r,
        center = Offset(x, hitY),
        style = Stroke(width = if (tapped) 3f else 2f)
    )

    // Inner pip
    drawCircle(
        color = (if (fever) Color.White else accent2).copy(alpha = 0.4f + 0.4f * beat),
        radius = r * 0.35f,
        center = Offset(x, hitY)
    )
}

private fun DrawScope.drawVerticalHitLine(w: Float, hitY: Float, c: Color, phase: Float) {
    val beat = (1f - phase).coerceIn(0f, 1f).let { it * it }
    drawLine(
        c.copy(alpha = 0.55f + 0.45f * beat),
        Offset(0f, hitY),
        Offset(w, hitY),
        strokeWidth = 3f + 1.5f * beat
    )
}

private fun DrawScope.drawVerticalWindowBands(
    w: Float, hitY: Float, pxPerMs: Float,
    windows: HioneRhythmTiming.Windows, accent: Color, accent2: Color
) {
    bandV(w, hitY, windows.okMs * pxPerMs, accent2.copy(alpha = 0.05f))
    bandV(w, hitY, windows.goodMs * pxPerMs, accent2.copy(alpha = 0.10f))
    bandV(w, hitY, windows.greatMs * pxPerMs, accent.copy(alpha = 0.18f))
    bandV(w, hitY, windows.perfectMs * pxPerMs, accent.copy(alpha = 0.30f))
}

private fun DrawScope.bandV(w: Float, hitY: Float, halfPx: Float, color: Color) {
    if (halfPx <= 0.5f) return
    drawRect(color = color, topLeft = Offset(0f, hitY - halfPx), size = Size(w, halfPx * 2f))
}

private fun DrawScope.drawVerticalNote(
    x: Float, y: Float, maxRadius: Float, msAway: Float, lookaheadMs: Float,
    strong: Boolean, color: Color, unitPath: Path,
    shape: HioneBeatClickerEngine.NodeShape, fog: Float, lucky: Boolean, t: Long
) {
    val near = (1f - (msAway / lookaheadMs)).coerceIn(0f, 1f)
    val past = msAway < 0f
    val fade = (if (past) (1f + msAway / 260f).coerceIn(0f, 1f) else 1f) * fog
    if (fade <= 0f) return

    val baseR = maxRadius * (if (strong || lucky) 0.65f else 0.50f)
    val r = baseR * (0.6f + 0.4f * near)
    val alpha = (0.35f + 0.65f * near) * fade

    // Downward trail behind the note
    if (!past && near > 0.15f) {
        drawLine(
            color.copy(alpha = 0.22f * alpha),
            Offset(x, y - r * 2.2f),
            Offset(x, y),
            strokeWidth = max(2f, r * 0.6f)
        )
    }

    // Outer glow
    drawCircle(color.copy(alpha = 0.25f * alpha), radius = r * 1.6f, center = Offset(x, y))

    // Shape body
    if (shape == HioneBeatClickerEngine.NodeShape.ORB) {
        drawCircle(color.copy(alpha = alpha), radius = r, center = Offset(x, y))
    } else {
        translate(x, y) {
            scale(r * 1.15f, r * 1.15f, pivot = Offset.Zero) {
                drawPath(unitPath, color.copy(alpha = alpha))
            }
        }
    }

    // Specular center dot
    drawCircle(Color.White.copy(alpha = 0.75f * alpha * near), radius = r * 0.35f, center = Offset(x, y))

    if (strong && !lucky) {
        drawCircle(color.copy(alpha = 0.6f * alpha), radius = r * 1.3f, center = Offset(x, y), style = Stroke(width = 1.5f))
    }

    if (lucky) {
        val spin = (t % 2000L) / 2000f * 6.283f
        val r1 = r * 1.3f
        val r2 = r * 2.1f
        for (k in 0 until 4) {
            val a = spin + k * 1.5708f
            drawLine(
                Color.White.copy(alpha = 0.85f * alpha),
                Offset(x + cos(a) * r1, y + sin(a) * r1),
                Offset(x + cos(a) * r2, y + sin(a) * r2),
                strokeWidth = 2f
            )
        }
        drawCircle(LUCKY_GOLD.copy(alpha = 0.6f * alpha), radius = r * 1.5f, center = Offset(x, y), style = Stroke(width = 2f))
    }
}
