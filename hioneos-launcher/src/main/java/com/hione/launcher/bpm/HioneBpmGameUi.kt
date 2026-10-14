package com.hione.launcher.bpm

import com.hione.launcher.Mascot
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.launcher.AudiowideFont
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// The game's newer UI pieces live here rather than inside HioneBpmObservatoryModal: that file is
// already the largest composable in the launcher, and ART refuses to JIT a method over 16384 dex
// instructions, so anything new goes beside it as small composables.

private val Gold = Color(0xFFFFD166)
private val Mint get() = Mascot.color(0xFFFFAB2E)
private val Muted = Color(0xFFD8B4E2)
private val Pink get() = Mascot.color(0xFFFF3385)

/**
 * Sheet shake, as a horizontal pixel offset. Read inside graphicsLayer so the shake moves the
 * layer without recomposing what is on it. A short decaying wobble, never more than ~9 px: enough
 * to feel in the thumb, not enough to make the notes hard to read.
 */
@Composable
fun rememberGameShake(): State<Float> {
    val shake by HioneGameFx.shake.collectAsState()
    val anim = remember { Animatable(0f) }
    LaunchedEffect(shake.first) {
        val k = shake.second
        if (shake.first == 0L || k <= 0f) return@LaunchedEffect
        val amp = 9f * k
        for (s in listOf(1f, -0.8f, 0.55f, -0.3f, 0.12f, 0f)) {
            anim.animateTo(amp * s, tween(34, easing = LinearEasing))
        }
    }
    return anim.asState()
}

/**
 * The big centre pop ("x32!", "FEVER!", "LUCKY!", "NEW BEST!"): springs in large, floats up and
 * fades. One at a time; a newer pop replaces the older one, which is what you want mid-run.
 */
@Composable
fun HioneGamePopLayer(modifier: Modifier = Modifier) {
    val pop by HioneGameFx.pop.collectAsState()
    val p = pop ?: return
    val scale = remember(p.id) { Animatable(0.4f) }
    val alpha = remember(p.id) { Animatable(1f) }
    val rise = remember(p.id) { Animatable(0f) }
    LaunchedEffect(p.id) {
        launch { scale.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium)) }
        launch { rise.animateTo(-26f, tween(900, easing = FastOutSlowInEasing)) }
        delay(520)
        alpha.animateTo(0f, tween(380))
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(
            p.text,
            color = Color(p.color),
            fontSize = if (p.big) 30.sp else 20.sp,
            fontWeight = FontWeight.Black,
            fontFamily = AudiowideFont,
            textAlign = TextAlign.Center,
            modifier = Modifier.graphicsLayer {
                scaleX = scale.value; scaleY = scale.value
                this.alpha = alpha.value
                translationY = rise.value * density
                shadowElevation = 0f
            }
        )
    }
}

/**
 * The unlock moment — rendered as a NON-BLOCKING top arcade banner so active rhythm gameplay
 * on the note highway below is never interrupted, and rapid thumb beat-tapping never accidentally
 * dismisses the unlock before the player can read it.
 *
 * Features a 1.2s accidental-tap guard on the dismiss button and a 7.5s auto-advance progress bar.
 */
@Composable
fun HioneCelebrationOverlay(modifier: Modifier = Modifier) {
    com.hione.launcher.HioneOneGritScreenBreakerOverlay(modifier)
    val queue by HioneCelebrations.queue.collectAsState()
    val item = queue.firstOrNull() ?: return
    val progress = remember(item) { Animatable(0f) }
    val enter = remember(item) { Animatable(0f) }
    var canDismiss by remember(item) { mutableStateOf(false) }

    LaunchedEffect(item) {
        canDismiss = false
        if (item.secret) HioneStagePerformance.onSecretFound()
        if (item.title.contains("1-Grit", ignoreCase = true)) {
            com.hione.launcher.HioneOneGritScreenBreaker.smash()
        }
        launch { enter.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow)) }
        launch {
            delay(1200L)
            canDismiss = true
        }
        progress.animateTo(1f, tween(7500, easing = LinearEasing))
        HioneCelebrations.dismiss()
    }

    // Confetti, generated once per celebration (not per frame), drawn in a pass-through layer.
    val confetti = remember(item) {
        val palette = if (item.secret) listOf(0xFFFFAB2E, 0xFFFF4FA3, 0xFFB8FFF9, 0xFFFFD166)
        else listOf(0xFFFFD166, 0xFFFF85B3, 0xFFFFAB2E, 0xFFDFB8FF)
        List(32) {
            floatArrayOf(
                Random.nextFloat(),                       // x 0..1
                -Random.nextFloat() * 0.25f,              // y start
                (Random.nextFloat() - 0.5f) * 0.20f,      // drift
                0.5f + Random.nextFloat() * 0.7f,         // fall speed
                Random.nextFloat() * 6.28f                // spin phase
            ) to Color(palette[it % palette.size])
        }
    }

    // Pass-through outer Box: NO clickable on the full screen, so the note highway below keeps 100% of touches!
    Box(
        modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val t = progress.value
            for ((f, c) in confetti) {
                val x = (f[0] + f[2] * t) * size.width
                val y = (f[1] + f[3] * t * 1.8f) * size.height
                if (y < -10f || y > size.height + 10f) continue
                val a = f[4] + t * 18f
                val half = 6f
                drawLine(
                    c.copy(alpha = (1f - t * 0.75f).coerceIn(0f, 1f)),
                    Offset(x - cos(a) * half, y - sin(a) * half),
                    Offset(x + cos(a) * half, y + sin(a) * half),
                    strokeWidth = 4.5f
                )
            }
        }

        Column(
            Modifier
                .padding(top = 6.dp, start = 10.dp, end = 10.dp)
                .fillMaxWidth()
                .graphicsLayer {
                    val e = enter.value
                    scaleX = 0.88f + 0.12f * e
                    scaleY = 0.88f + 0.12f * e
                    translationY = (1f - e) * -80f
                    alpha = e.coerceIn(0f, 1f)
                }
                .clip(RoundedCornerShape(18.dp))
                .background(
                    Brush.verticalGradient(
                        if (item.secret) listOf(Mascot.color(0xF5062A2C), Color(0xF5140818))
                        else listOf(Color(0xF52A1A06), Color(0xF5140818))
                    )
                )
                .border(2.dp, if (item.secret) Mint else Gold, RoundedCornerShape(18.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        if (item.secret) Icons.Default.LockOpen else Icons.Default.EmojiEvents,
                        contentDescription = null,
                        tint = if (item.secret) Mint else Gold,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        val remaining = queue.size - 1
                        Text(
                            text = (if (item.secret) "SECRET FOUND" else "REWARD UNLOCKED") +
                                if (remaining > 0) " (+$remaining MORE)" else "",
                            color = if (item.secret) Mint else Gold,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = AudiowideFont,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = item.title,
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = AudiowideFont,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (canDismiss) (if (item.secret) Mint else Gold) else Color(0x33FFFFFF))
                        .clickable(enabled = canDismiss) { HioneCelebrations.dismiss() }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (queue.size > 1) "NEXT ▸" else "GOT IT",
                        color = if (canDismiss) Color(0xFF121318) else Muted,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = "${item.where} — ${item.howTo}",
                color = Mascot.color(0xFFE8F4F2),
                fontSize = 11.5.sp,
                lineHeight = 14.5.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            // Auto-dismiss countdown progress bar
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0x33FFFFFF))
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth((1f - progress.value).coerceIn(0f, 1f))
                        .background(if (item.secret) Mint else Gold)
                )
            }
        }
    }
}

/**
 * The secrets, as the player sees them: a mystery card that says how many remain and gives
 * today's clue — never the list — followed by every secret already found, with its switch.
 */
@Composable
fun BpmSecretMysteryCard() {
    val ctx = LocalContext.current
    val found by HioneSecrets.foundCount.collectAsState()
    val total = HioneSecrets.ALL.size
    val hint = remember(found) { HioneSecrets.hintOfTheDay(ctx) }
    Box(
        Modifier
            .width(200.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.linearGradient(listOf(Mascot.color(0x55FFAB2E), Mascot.color(0x55FF4FA3))))
            .border(1.dp, Mint.copy(alpha = 0.8f), RoundedCornerShape(14.dp))
            .padding(6.dp)
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Text(
                if (found >= total) "All $total secrets found" else "${total - found} secrets left to find",
                color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black, maxLines = 1
            )
            Text(
                hint?.let { "Clue: $it" } ?: "You found everything. ${Mascot.name} is impressed and a little scared.",
                color = Mascot.color(0xFFFFF3E0), fontSize = 9.sp, lineHeight = 11.sp,
                maxLines = 3, overflow = TextOverflow.Ellipsis
            )
            Text("$found of $total found. New clue every day.", color = Gold, fontSize = 8.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
fun BpmSecretCard(secret: HioneSecrets.Secret) {
    val ctx = LocalContext.current
    var enabled by remember(secret.id) { mutableStateOf(HioneUnlocks.isEnabled(ctx, secret.id)) }
    Box(
        Modifier
            .width(188.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0x33000000))
            .border(1.dp, Mint.copy(alpha = if (enabled || !secret.toggleable) 0.8f else 0.3f), RoundedCornerShape(14.dp))
            .padding(6.dp)
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    secret.title, color = Color.White, fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (secret.id == HioneSecrets.DANKPODS_ONE_GRIT) {
                    Text(
                        "SMASH",
                        color = Mascot.color(0xFF121318),
                        fontSize = 9.sp, fontWeight = FontWeight.Black,
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Gold)
                            .clickable {
                                com.hione.launcher.HioneOneGritScreenBreaker.smash(ctx)
                            }
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
                val openIntent = remember(secret.id) { com.hione.launcher.secretOpenIntent(ctx, secret.id) }
                if (openIntent != null) {
                    Text(
                        "OPEN",
                        color = Mascot.color(0xFF121318),
                        fontSize = 9.sp, fontWeight = FontWeight.Black,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Mint)
                            .clickable {
                                com.hione.launcher.haptics.HioneHaptics.tick(ctx)
                                runCatching { ctx.startActivity(openIntent) }
                            }
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
                if (secret.toggleable) {
                    Text(
                        if (enabled) "ON" else "OFF",
                        color = if (enabled) Mascot.color(0xFF121318) else Muted,
                        fontSize = 9.sp, fontWeight = FontWeight.Black,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (enabled) Mint else Color(0x33FFFFFF))
                            .clickable {
                                val want = !enabled
                                if (HioneUnlocks.setEnabled(ctx, secret.id, want)) {
                                    enabled = want
                                    com.hione.launcher.haptics.HioneHaptics.tick(ctx)
                                }
                            }
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
            }
            Text(secret.where, color = Mascot.color(0xFF66E0D8), fontSize = 8.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(secret.howTo, color = Muted, fontSize = 8.5.sp, lineHeight = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Run and personal best for the track that is playing: "RUN 3,120 · B   BEST 9,880 · A FC".
 * The single line that turns a song into a level.
 */
@Composable
fun BpmRunLine() {
    val run by HioneSongRecords.run.collectAsState()
    val best by HioneSongRecords.best.collectAsState()
    if (run.key == null) {
        Text(
            "No track info, so this run isn't saved",
            color = Muted, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, maxLines = 1
        )
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(
            "RUN ${fmt(run.score)} · ${run.grade}" + if (run.taps in 1 until HioneSongRecords.MIN_GRADED_TAPS) " (${run.taps}/${HioneSongRecords.MIN_GRADED_TAPS})" else "",
            color = if (run.newBest) Gold else Color.White,
            fontSize = 10.5.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont, maxLines = 1
        )
        val b = best
        Text(
            if (b == null) "BEST -" else "BEST ${fmt(b.score)} · ${b.grade.ifBlank { "-" }}${if (b.fullCombo) " FC" else ""}",
            color = Gold, fontSize = 10.5.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont, maxLines = 1
        )
    }
}

/** Today's challenge, one line: what, how far, and the reward. */
@Composable
fun BpmDailyChallengeLine() {
    val c by HioneBeatClickerEngine.dailyChallenge.collectAsState()
    if (c.dateKey.isEmpty()) return
    val text = if (c.claimed) "Daily challenge done. New one tomorrow."
    else Mascot.lore("Daily: ${c.kind.label} · ${c.progress.coerceAtMost(c.kind.target)}/${c.kind.target} · +12K leeks, +1 shield", "Daily: ${c.kind.label} · ${c.progress.coerceAtMost(c.kind.target)}/${c.kind.target} · +12K notes, +1 shield")
    Text(
        text,
        color = if (c.claimed) Gold else Pink.copy(alpha = 0.95f),
        fontSize = 9.5.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 2.dp)
    )
}

private fun fmt(n: Long): String = String.format(Locale.US, "%,d", n)
