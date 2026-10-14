package com.hione.launcher

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hione.launcher.bpm.HioneBpmEngine
import com.hione.launcher.bpm.HioneSecrets
import com.hione.launcher.bpm.HioneUnlocks
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

// Launcher-side consumers of the BPM game's hidden tier (see bpm/HioneSecrets.kt). Each one reads
// its id from the one store and changes real behaviour; nothing here can grant anything.

/**
 * Earned AND switched on, re-read whenever the store changes. The game writes in this process, so
 * [HioneUnlocks.version] ticks the moment a secret is earned or toggled and the home screen
 * updates without a restart or a poll.
 */
@Composable
fun rememberUnlockEnabled(id: String): Boolean {
    val ctx = LocalContext.current
    val v by HioneUnlocks.version.collectAsState()
    return remember(id, v) { HioneUnlocks.isEnabled(ctx, id) }
}

/**
 * The secrets that are whole apps (Riot mode, the HionePod eras) open from their card in the
 * Secrets list. This is the intent for that, or null when the secret is not an app or the app is
 * not installed. The app checks the unlock itself, so this never bypasses anything.
 */
fun secretOpenIntent(ctx: Context, id: String): Intent? {
    val i = when (id) {
        HioneSecrets.RIOT_MODE ->
            Intent("com.hione.riot.action.ENTER").setPackage("com.hione.riot")
        HioneSecrets.WHEEL_2001, HioneSecrets.WHEEL_2004, HioneSecrets.WHEEL_2005, HioneSecrets.WHEEL_2007 ->
            Intent("com.hione.wheel.action.ENTER").setPackage("com.hione.wheel")
                .putExtra("era", id.substringAfterLast('.'))
        HioneSecrets.DANKPODS_FRANK ->
            Intent(ctx, com.hione.launcher.bpm.HioneFrankSnakeActivity::class.java)
        else -> return null
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return if (i.resolveActivity(ctx.packageManager) != null) i else null
}

/**
 * 1-GRIT SCREEN BREAKER: Global trigger & non-blocking shattered glass + concrete rock impact
 * overlay! Fires automatically when [HioneSecrets.DANKPODS_ONE_GRIT] is unlocked or when the
 * player taps "SMASH" on The 1-Grit secret card.
 */
object HioneOneGritScreenBreaker {
    val lastSmashEpochMs = mutableLongStateOf(0L)

    fun smash(ctx: Context? = null) {
        lastSmashEpochMs.longValue = System.currentTimeMillis()
        ctx?.let { com.hione.launcher.haptics.HioneHaptics.unlock(it) }
    }
}

@Composable
fun HioneOneGritScreenBreakerOverlay(modifier: Modifier = Modifier) {
    val smashEpoch by HioneOneGritScreenBreaker.lastSmashEpochMs
    if (smashEpoch <= 0L) return
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    val ageMs = now.longValue - smashEpoch
    if (ageMs !in 0L..3400L) return

    LaunchedEffect(smashEpoch) {
        while (System.currentTimeMillis() - smashEpoch <= 3400L) {
            now.longValue = System.currentTimeMillis()
            delay(16L)
        }
    }

    Canvas(modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val cx = w * 0.5f
        val cy = h * 0.44f
        val t = (now.longValue - smashEpoch).coerceAtLeast(0L)

        // Phase 1 (0..220ms): The 1-Grit Concrete Rock hurtles down from above and slams the glass
        if (t < 240L) {
            val dropFrac = (t / 220f).coerceIn(0f, 1f)
            val rockY = -120f + (cy + 120f) * (dropFrac * dropFrac)
            val rockR = 54f + 42f * (1f - dropFrac)
            drawRoundRect(
                color = Color(0xFF6E6B64),
                topLeft = Offset(cx - rockR * 1.15f, rockY - rockR * 0.85f),
                size = Size(rockR * 2.3f, rockR * 1.7f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(16f, 16f)
            )
            drawRoundRect(
                color = Color(0xFFD0CCC2),
                topLeft = Offset(cx - rockR * 1.15f, rockY - rockR * 0.85f),
                size = Size(rockR * 2.3f, rockR * 1.7f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(16f, 16f),
                style = Stroke(width = 4f)
            )
        }

        // Phase 2 (190..3400ms): Impact Flash + Spiderweb Shattered Screen Cracks + Flying Grit Shards!
        if (t >= 190L) {
            val impactAge = (t - 190L).toFloat()
            val fade = (1f - (impactAge / 3200f)).coerceIn(0f, 1f)

            // Initial white/amber shock flash
            if (impactAge < 180f) {
                val flashAlpha = (1f - impactAge / 180f) * 0.55f
                drawRect(Color(0xFFFFF4D0).copy(alpha = flashAlpha), size = size)
            }

            // Concentric spiderweb glass rings around the impact crater
            val ringRadii = floatArrayOf(34f, 78f, 145f, 235f, 350f)
            for (r in ringRadii) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.65f * fade),
                    radius = r,
                    center = Offset(cx, cy),
                    style = Stroke(width = 2.2f)
                )
            }

            // 16 jagged radial glass fracture lines reaching the edges of the display
            val maxReach = maxOf(w, h) * 0.85f
            for (ray in 0 until 16) {
                val baseAngle = (ray * (2.0 * PI / 16.0)) + 0.13 * (ray % 3)
                var px = cx
                var py = cy
                val segments = 6
                for (seg in 1..segments) {
                    val dist = (seg.toFloat() / segments) * maxReach
                    val jitter = ((ray * 17 + seg * 31) % 19 - 9) * 0.022
                    val ang = baseAngle + jitter
                    val nx = cx + kotlin.math.cos(ang).toFloat() * dist
                    val ny = cy + kotlin.math.sin(ang).toFloat() * dist
                    // Cyan/ice edge refraction + bright white fracture core
                    drawLine(
                        color = Color(0xFF00F0FF).copy(alpha = 0.45f * fade),
                        start = Offset(px + 2f, py + 2f),
                        end = Offset(nx + 2f, ny + 2f),
                        strokeWidth = (5f - seg * 0.55f).coerceAtLeast(1.5f)
                    )
                    drawLine(
                        color = Color.White.copy(alpha = 0.92f * fade),
                        start = Offset(px, py),
                        end = Offset(nx, ny),
                        strokeWidth = (3.4f - seg * 0.42f).coerceAtLeast(1.2f)
                    )
                    px = nx
                    py = ny
                }
            }

            // Impact crater & 1-Grit rock resting in the center for the first 1.5s
            if (impactAge < 1600f) {
                val rockFade = (1f - impactAge / 1600f).coerceIn(0f, 1f)
                drawRoundRect(
                    color = Color(0xFF7B776F).copy(alpha = rockFade),
                    topLeft = Offset(cx - 58f, cy - 42f),
                    size = Size(116f, 84f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(14f, 14f)
                )
                drawRoundRect(
                    color = Color(0xFFFFD166).copy(alpha = rockFade),
                    topLeft = Offset(cx - 58f, cy - 42f),
                    size = Size(116f, 84f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(14f, 14f),
                    style = Stroke(width = 3.5f)
                )
            }

            // Flying concrete & glass debris shards
            for (s in 0 until 18) {
                val ang = s * (2.0 * PI / 18.0) + 0.2
                val speed = 0.22f + (s % 5) * 0.08f
                val dist = 30f + impactAge * speed
                val sx = cx + kotlin.math.cos(ang).toFloat() * dist
                val sy = cy + kotlin.math.sin(ang).toFloat() * dist + 0.00018f * impactAge * impactAge
                drawCircle(
                    color = (if (s % 2 == 0) Color(0xFFD8D3C8) else Color(0xFF00F0FF)).copy(alpha = 0.85f * fade),
                    radius = (4.5f + (s % 3) * 2f) * fade,
                    center = Offset(sx, sy)
                )
            }
        }
    }
}

/**
 * PKCELL BATTERY GLYPH: Replaces the status bar battery cell with an authentic metallic
 * emerald-green & gold PKCELL AA battery when [HioneSecrets.DANKPODS_PKCELL] is enabled!
 */
@Composable
fun HionePkcellBatteryGlyph(pct: Int, charging: Boolean, low: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.size(width = 26.dp, height = 11.dp)) {
        val w = size.width
        val h = size.height
        val bodyW = w - 3.dp.toPx()
        val r = androidx.compose.ui.geometry.CornerRadius(2.2.dp.toPx(), 2.2.dp.toPx())

        val pkGreen = if (low && !charging) Color(0xFFFF3B5C) else Color(0xFF00A859)
        val pkGold = Color(0xFFFFD166)
        val pkSilver = Color(0xFFE6EEF8)

        // Outer AA cylinder shell (metallic green base + gold top collar on right 26%)
        drawRoundRect(
            color = Color(0xFF062818),
            topLeft = Offset.Zero,
            size = Size(bodyW, h),
            cornerRadius = r
        )

        // Charge fill inside the cylinder
        val frac = if (pct < 0) 0f else (pct / 100f).coerceIn(0f, 1f)
        if (frac > 0f) {
            drawRoundRect(
                color = if (charging) Color(0xFF5CFF9D) else pkGreen,
                topLeft = Offset(1.dp.toPx(), 1.dp.toPx()),
                size = Size((bodyW - 2.dp.toPx()) * frac, h - 2.dp.toPx()),
                cornerRadius = r
            )
        }

        // Authentic PKCELL gold stripe near the positive terminal
        val goldW = bodyW * 0.24f
        drawRect(
            color = pkGold,
            topLeft = Offset(bodyW - goldW - 1.dp.toPx(), 1.dp.toPx()),
            size = Size(goldW, h - 2.dp.toPx())
        )

        // Crisp border & silver positive terminal nub
        drawRoundRect(
            color = pkGold.copy(alpha = 0.92f),
            topLeft = Offset.Zero,
            size = Size(bodyW, h),
            cornerRadius = r,
            style = Stroke(width = 1.2.dp.toPx())
        )
        drawRoundRect(
            color = pkSilver,
            topLeft = Offset(bodyW + 0.5.dp.toPx(), h * 0.26f),
            size = Size(2.4.dp.toPx(), h * 0.48f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.2f, 1.2f)
        )
    }
}

/** The Hione Music icon to draw: gold once [HioneSecrets.GOLDEN_ICON] is earned and on. */
fun hioneMusicIconRes(golden: Boolean): Int =
    if (golden) R.drawable.ic_hione_music_golden else R.drawable.ic_hione_music_brand

/** Any app icon resource, with the Hione Music brand icon swapped for gold when earned. */
fun displayIconRes(resId: Int?, golden: Boolean): Int? =
    if (golden && resId == R.drawable.ic_hione_music_brand) R.drawable.ic_hione_music_golden else resId

/**
 * Concert Mode's on/off. Toggled by double-tapping the home hearts clock once the secret is
 * earned; not persisted, on purpose — it is a party trick for now, not a setting that should
 * still be running tomorrow when you have forgotten how you turned it on.
 */
object HioneConcertMode {
    val active = mutableStateOf(false)
}

/**
 * NEGI BATTERY: the battery glyph as a leek lying on its side. White bulb and roots on the left,
 * the stalk fills from white to green with charge, and the leaves fan out at the top end.
 * Same information as the four bars it replaces — level, charging, low — just a lot more leek.
 * Under a pack without Hione lore it draws Hione's coda reticle and ice shard instead
 * ([CodaShardBatteryGlyph]), switching live with the pack.
 */
@Composable
fun HioneNegiBatteryGlyph(pct: Int, charging: Boolean, low: Boolean, modifier: Modifier = Modifier) {
    if (!Mascot.hasHioneLore) {
        CodaShardBatteryGlyph(pct, charging, low, modifier)
        return
    }
    Canvas(modifier.size(width = 26.dp, height = 11.dp)) {
        val w = size.width
        val h = size.height
        val cy = h / 2f
        val bulbR = h * 0.36f
        val bulbX = bulbR + 1f
        // Roots
        for (k in -1..1) {
            drawLine(Color(0xFFE8E2D0), Offset(bulbX - bulbR * 0.6f, cy + k * 2.2f), Offset(0f, cy + k * 3.4f), strokeWidth = 1f)
        }
        val stalkStart = bulbX
        val stalkEnd = w * 0.72f
        val stalkH = h * 0.42f
        // Stalk outline
        drawRoundRectCompat(Offset(stalkStart, cy - stalkH / 2f), Size(stalkEnd - stalkStart, stalkH), Color(0x55FFFFFF), stroke = true)
        // Fill: the charge. Unknown (pct < 0) draws an empty stalk, never a flat battery.
        val frac = if (pct < 0) 0f else (pct / 100f).coerceIn(0f, 1f)
        if (frac > 0f) {
            val fillCol = when {
                charging -> Color(0xFF7CFF6B)
                low -> Color(0xFFFF6B6B)
                else -> Color(0xFF8BE36A)
            }
            drawRoundRectCompat(Offset(stalkStart, cy - stalkH / 2f), Size((stalkEnd - stalkStart) * frac, stalkH), fillCol, stroke = false)
        }
        // Bulb on top of the stalk's start
        drawCircle(Color(0xFFF5F2E6), bulbR, Offset(bulbX, cy))
        // Leaves fanning from the stalk end
        val leaf = Color(0xFF3FAF4A)
        drawLine(leaf, Offset(stalkEnd, cy), Offset(w, cy - h * 0.42f), strokeWidth = 2.2f, cap = StrokeCap.Round)
        drawLine(leaf, Offset(stalkEnd, cy), Offset(w, cy), strokeWidth = 2.2f, cap = StrokeCap.Round)
        drawLine(Color(0xFF2E8B3A), Offset(stalkEnd, cy), Offset(w - 2f, cy + h * 0.40f), strokeWidth = 2.2f, cap = StrokeCap.Round)
        if (charging) drawCircle(Color.White.copy(alpha = 0.9f), 1.6f, Offset(stalkStart + (stalkEnd - stalkStart) * frac, cy))
    }
}

private fun DrawScope.drawRoundRectCompat(topLeft: Offset, size: Size, color: Color, stroke: Boolean) {
    val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2f, size.height / 2f)
    if (stroke) drawRoundRect(color, topLeft, size, r, style = Stroke(width = 1f))
    else drawRoundRect(color, topLeft, size, r)
}

/**
 * Home-screen secret effects drawn over the wallpaper: NEGI SNOW (leeks drifting down, or under
 * a pack without Hione lore, Hione's ice shards and coda marks: [drawCodaSnow]) and
 * CONCERT MODE (penlights swaying and stage beams sweeping on the beat).
 *
 * POWER. This sits on the home screen, which can be on for hours, so it is deliberately cheap:
 * about thirty primitive draws, invalidated at ~30 fps by a delay loop (not every vsync), and the
 * loop does not run at all while the ambient/low-power gate is closed, the launcher is covered, or
 * neither effect is on. Concert Mode only performs while music is actually playing.
 */
@Composable
fun HioneSecretHomeFx(isPlaying: Boolean, accent: Color, modifier: Modifier = Modifier) {
    val snow = rememberUnlockEnabled(HioneSecrets.NEGI_SNOW)
    val concertEarned = rememberUnlockEnabled(HioneSecrets.CONCERT_MODE)
    val concert = concertEarned && HioneConcertMode.active.value && isPlaying
    if (!snow && !concert) return
    val gate by com.hione.launcher.ui.rememberAmbientGate()
    val clock = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(gate, snow, concert) {
        if (gate) return@LaunchedEffect
        while (true) {
            clock.longValue = System.currentTimeMillis()
            delay(33L)
        }
    }
    val bpmState by HioneBpmEngine.state.collectAsState()
    Canvas(modifier.fillMaxSize()) {
        val t = clock.longValue
        if (snow) {
            // Read in the draw pass, so a pack switch redraws on the next frame.
            if (Mascot.hasHioneLore) drawNegiSnow(t, if (isPlaying) 2.2f else 1f)
            else drawCodaSnow(t, if (isPlaying) 2.2f else 1f)
        }
        if (concert) drawConcert(t, bpmState.beatIntervalMs, bpmState.lastPulseEpochMs, bpmState.bpm > 0f, accent)
    }
}

private fun DrawScope.drawNegiSnow(t: Long, speedMul: Float) {
    val w = size.width
    val h = size.height
    for (i in 0 until 16) {
        val seed = (i * 0.618034f) % 1f
        val speed = (22f + 18f * ((i * 7) % 5)) * speedMul      // px per second
        val travel = h + 80f
        val y = ((seed * travel + (t % 600_000L) / 1000f * speed) % travel) - 40f
        val x = (((i * 97) % 100) / 100f) * w + sin(t / 1400.0 + i).toFloat() * 14f
        val tilt = sin(t / 1100.0 + i * 1.3).toFloat() * 25f + (i % 3 - 1) * 15f
        val len = 20f + (i % 4) * 5f
        rotate(tilt, pivot = Offset(x, y)) {
            // Stalk (white into pale green), bulb, and two leaves.
            drawLine(Color(0xCCF2F0E4), Offset(x, y), Offset(x, y + len * 0.55f), strokeWidth = 4f, cap = StrokeCap.Round)
            drawLine(Color(0xCC9BDD7A), Offset(x, y + len * 0.55f), Offset(x, y + len), strokeWidth = 4f, cap = StrokeCap.Round)
            drawLine(Color(0xCC3FAF4A), Offset(x, y + len), Offset(x - 5f, y + len + 9f), strokeWidth = 2.5f, cap = StrokeCap.Round)
            drawLine(Color(0xCC2E8B3A), Offset(x, y + len), Offset(x + 5f, y + len + 9f), strokeWidth = 2.5f, cap = StrokeCap.Round)
            drawCircle(Color(0xDDFFFFFF), 3.2f, Offset(x, y))
        }
    }
}

private val BEAM_PINK get() = Mascot.color(0xFFFF4FA3)
private val BEAM_TEAL get() = Mascot.color(0xFFFFAB2E)

private fun DrawScope.drawConcert(t: Long, periodMs: Long, anchorMs: Long, hasTempo: Boolean, accent: Color) {
    val w = size.width
    val h = size.height
    // Beat phase from the real beat clock when there is one. Without a tempo the crowd still
    // waves, slowly and evenly, without pretending to be on a beat nobody measured.
    val period = if (hasTempo && periodMs in 200..2000) periodMs.toDouble() else 1400.0
    val since = if (hasTempo && anchorMs > 0L) (t - anchorMs).toDouble() else t.toDouble()
    val beats = since / period
    val beatPulse = (1.0 - (beats - kotlin.math.floor(beats))).let { it * it }.toFloat()

    // Stage beams from the top corners, sweeping on a two-bar cycle.
    val sweep = sin(beats * PI / 4.0).toFloat()
    for (k in 0 until 3) {
        val ox = w * (0.1f + 0.4f * k)
        val ang = 90f + sweep * (28f - 8f * k) * (if (k % 2 == 0) 1f else -1f)
        rotate(ang - 90f, pivot = Offset(ox, 0f)) {
            drawRect(
                (if (k == 0) accent else if (k == 1) BEAM_PINK else BEAM_TEAL).copy(alpha = 0.07f + 0.08f * beatPulse),
                topLeft = Offset(ox - 18f, 0f), size = Size(36f, h * 0.85f)
            )
        }
    }
    // Penlight crowd along the bottom: alternating colours, swaying one way per beat.
    val n = 14
    for (i in 0 until n) {
        val bx = w * (i + 0.5f) / n
        val by = h * 0.97f
        val lean = sin((beats + (i % 2) * 0.5) * PI).toFloat() * 22f
        val col = if (i % 3 == 0) Mascot.color(0xFFFFAB2E) else if (i % 3 == 1) Mascot.color(0xFFFF4FA3) else accent
        rotate(lean, pivot = Offset(bx, by)) {
            drawCircle(col.copy(alpha = 0.16f + 0.18f * beatPulse), 16f, Offset(bx, by - 52f))
            drawLine(col.copy(alpha = 0.85f), Offset(bx, by), Offset(bx, by - 58f), strokeWidth = 5f, cap = StrokeCap.Round)
            drawLine(Color.White.copy(alpha = 0.55f), Offset(bx, by - 30f), Offset(bx, by - 56f), strokeWidth = 1.6f, cap = StrokeCap.Round)
        }
    }
    // A flash on the downbeat-ish (every fourth pulse), honestly just "every fourth beat".
    if (hasTempo && (kotlin.math.floor(beats).toLong() % 4L == 0L)) {
        drawRect(Color.White.copy(alpha = 0.05f * beatPulse), size = size)
    }
}
