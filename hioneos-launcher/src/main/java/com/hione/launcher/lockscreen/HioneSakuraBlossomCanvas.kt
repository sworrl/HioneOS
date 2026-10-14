package com.hione.launcher.lockscreen

import com.hione.launcher.Mascot
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import kotlinx.coroutines.isActive
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin

/**
 * Ultra-Smooth Zero-Lag Oki Hione Sakura Blossom Particle Engine.
 * Features frame-by-frame Choreographer physics, true 3D fluttering,
 * swirling wind breeze, and iridescent cherry blossom glow.
 *
 * Ported verbatim from the app source into the launcher's lockscreen package — fully
 * self-contained (no app dependencies).
 */
class SakuraParticle(
    var x: Float,
    var y: Float,
    val size: Float,
    val speedY: Float,
    val speedX: Float,
    var rotDeg: Float,
    val rotSpeed: Float,
    var tumblePhase: Float,
    val tumbleSpeed: Float,
    var swayPhase: Float,
    val swaySpeed: Float,
    val swayAmp: Float,
    val color: Color,
    val alpha: Float,
    val isWholeFlower: Boolean = false
)

@Composable
fun HioneSakuraBlossomCanvas(
    modifier: Modifier = Modifier,
    petalCount: Int = 42
) {
    val sakuraColors = remember {
        listOf(
            Color(0xFFFFB7C5), // Soft Classic Sakura
            Mascot.color(0xFFFF69B4), // Hatsune Neon Pink
            Mascot.color(0xFFFF80AB), // Vibrant Cyber Pink
            Mascot.color(0xFFFFF0F5), // Pearl White Blossom
            Mascot.color(0xFFFFF3E0), // Soft Cyan Shimmer
            Mascot.color(0xFFFFC56B)  // Cyber Cyan Glow
        )
    }

    // Static Pre-built Cherry Blossom Petal Path (Normalized around 0,0)
    val staticPetalPath = remember {
        Path().apply {
            moveTo(0f, 22f) // Stem base
            cubicTo(-14f, 7f, -16f, -12f, -6f, -24f) // Left lobe
            lineTo(0f, -17f) // Center notch
            lineTo(6f, -24f) // Right lobe
            cubicTo(16f, -12f, 14f, 7f, 0f, 22f)
            close()
        }
    }

    // Static Pre-built 5-Petal Whole Blossom Flower Path
    val staticFlowerPath = remember {
        Path().apply {
            val r = 24f
            for (i in 0 until 5) {
                val angle = Math.toRadians((i * 72.0) - 90.0)
                val nextAngle = Math.toRadians(((i + 1) * 72.0) - 90.0)
                val midAngle = Math.toRadians((i * 72.0 + 36.0) - 90.0)

                val x0 = (r * cos(angle)).toFloat()
                val y0 = (r * sin(angle)).toFloat()
                val x1 = ((r * 1.35f) * cos(midAngle)).toFloat()
                val y1 = ((r * 1.35f) * sin(midAngle)).toFloat()
                val x2 = (r * cos(nextAngle)).toFloat()
                val y2 = (r * sin(nextAngle)).toFloat()

                if (i == 0) moveTo(x0, y0)
                cubicTo(x0 * 1.2f, y0 * 1.2f, x1, y1, x2, y2)
            }
            close()
        }
    }

    val particles = remember {
        val rand = Random(42)
        val list = mutableListOf<SakuraParticle>()
        for (i in 0 until petalCount) {
            val isFlower = (i % 6 == 0) // 1 in 6 is a full 5-petal blossom
            val sz = if (isFlower) rand.nextFloat() * 10f + 18f else rand.nextFloat() * 14f + 12f
            list.add(
                SakuraParticle(
                    x = rand.nextFloat() * 760f - 20f,
                    y = rand.nextFloat() * 1350f - 50f,
                    size = sz,
                    speedY = rand.nextFloat() * 65f + 45f,
                    speedX = rand.nextFloat() * 32f + 15f,
                    rotDeg = rand.nextFloat() * 360f,
                    rotSpeed = (rand.nextFloat() * 70f - 35f),
                    tumblePhase = rand.nextFloat() * 6.28f,
                    tumbleSpeed = rand.nextFloat() * 2.8f + 1.2f,
                    swayPhase = rand.nextFloat() * 6.28f,
                    swaySpeed = rand.nextFloat() * 2.0f + 0.8f,
                    swayAmp = rand.nextFloat() * 30f + 15f,
                    color = sakuraColors[i % sakuraColors.size],
                    alpha = rand.nextFloat() * 0.35f + 0.60f,
                    isWholeFlower = isFlower
                )
            )
        }
        list
    }

    // High-precision Choreographer Frame Clock Loop
    var frameTick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        var lastNanos = 0L
        while (isActive) {
            withFrameNanos { frameTimeNanos ->
                if (lastNanos != 0L) {
                    val dt = ((frameTimeNanos - lastNanos) / 1_000_000_000f).coerceIn(0.001f, 0.05f)

                    // Update particle physics
                    particles.forEach { p ->
                        p.y += p.speedY * dt
                        p.swayPhase += p.swaySpeed * dt
                        p.x += (p.speedX + sin(p.swayPhase) * p.swayAmp) * dt
                        p.rotDeg = (p.rotDeg + p.rotSpeed * dt) % 360f
                        p.tumblePhase += p.tumbleSpeed * dt

                        // Screen boundary wrap (720x1280 base display)
                        if (p.y > 1350f) {
                            p.y = -50f
                            p.x = (p.x + 200f) % 760f - 20f
                        }
                        if (p.x > 780f) {
                            p.x = -40f
                        } else if (p.x < -60f) {
                            p.x = 760f
                        }
                    }
                    frameTick++
                }
                lastNanos = frameTimeNanos
            }
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        // Read frameTick to trigger redraw on every single frame
        if (frameTick >= 0L) {
            particles.forEach { p ->
                val scaleFactor = p.size / 24f
                // 3D tumble: narrow the petal edge-on but NEVER hit 0 or go negative.
                // Raw cos(phase) crosses 0 (degenerate transform -> Skia smears the path
                // into a wide skewed sliver = the "glitch") and goes negative (mirror
                // flip). Map |cos| into [0.18, 1.0] so the petal only ever foreshortens.
                val tumbleFactor = 0.18f + 0.82f * kotlin.math.abs(cos(p.tumblePhase))

                translate(p.x, p.y) {
                    rotate(p.rotDeg, pivot = Offset.Zero) {
                        scale(
                            scaleX = scaleFactor * tumbleFactor,
                            scaleY = scaleFactor,
                            pivot = Offset.Zero
                        ) {
                            drawPath(
                                path = if (p.isWholeFlower) staticFlowerPath else staticPetalPath,
                                color = p.color,
                                alpha = p.alpha
                            )
                        }
                    }
                }
            }
        }
    }
}
