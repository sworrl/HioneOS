package com.hione.launcher.bpm

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.launcher.haptics.HioneHaptics
import com.hione.launcher.AudiowideFont
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.random.Random

/**
 * FRANK THE SNAKE — Playable DankPods Arcade Mini-Game!
 *
 * Unlocked via [HioneSecrets.DANKPODS_FRANK] ("Frank the Snake").
 * Guide Frank (the beloved carpet python) around the green iPad cover / Cashies arena:
 *  - Eat green & gold PKCELL AA batteries ("Oh, my PKCELL!") to grow and score +15 pts
 *  - Catch rare Golden Maracas / Nuggets for +50 pts
 *  - Dodge the abrasive 1-Grit concrete rocks that drop onto the arena!
 */
class HioneFrankSnakeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FrankSnakeArcadeScreen(onExit = { finish() })
        }
    }
}

private enum class SnakeDir(val dx: Int, val dy: Int) {
    UP(0, -1),
    DOWN(0, 1),
    LEFT(-1, 0),
    RIGHT(1, 0);

    fun isOpposite(other: SnakeDir): Boolean = (dx + other.dx == 0) && (dy + other.dy == 0)
}

private data class GridPt(val x: Int, val y: Int)

@Composable
fun FrankSnakeArcadeScreen(onExit: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("hione_frank_snake", Context.MODE_PRIVATE) }
    var highScore by remember { mutableIntStateOf(prefs.getInt("high_score", 0)) }

    val cols = 16
    val rows = 18

    var snake by remember {
        mutableStateOf(listOf(GridPt(8, 9), GridPt(7, 9), GridPt(6, 9), GridPt(5, 9)))
    }
    var dir by remember { mutableStateOf(SnakeDir.RIGHT) }
    var nextDir by remember { mutableStateOf(SnakeDir.RIGHT) }
    var pkcell by remember { mutableStateOf(GridPt(12, 9)) }
    var goldenBonus by remember { mutableStateOf<GridPt?>(null) }
    var oneGrits by remember { mutableStateOf<List<GridPt>>(emptyList()) }
    var score by remember { mutableIntStateOf(0) }
    var pkcellsEaten by remember { mutableIntStateOf(0) }
    var gameOver by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var statusCallout by remember { mutableStateOf("SHE DOESN'T HAVE ARMS OR LEGS, MATE!") }
    var tickCount by remember { mutableIntStateOf(0) }

    fun randomFreeCell(occupied: Set<GridPt>, head: GridPt): GridPt {
        for (attempt in 0 until 150) {
            val pt = GridPt(Random.nextInt(cols), Random.nextInt(rows))
            if (pt !in occupied && (abs(pt.x - head.x) + abs(pt.y - head.y)) > 2) return pt
        }
        return GridPt(0, 0)
    }

    fun resetGame() {
        snake = listOf(GridPt(8, 9), GridPt(7, 9), GridPt(6, 9), GridPt(5, 9))
        dir = SnakeDir.RIGHT
        nextDir = SnakeDir.RIGHT
        oneGrits = emptyList()
        goldenBonus = null
        score = 0
        pkcellsEaten = 0
        gameOver = false
        paused = false
        statusCallout = "EAT THE PKCELLS · DODGE THE 1-GRIT!"
        pkcell = GridPt(12, 9)
    }

    fun steer(wanted: SnakeDir) {
        if (!wanted.isOpposite(dir)) {
            nextDir = wanted
            HioneHaptics.tick(ctx)
        }
    }

    LaunchedEffect(gameOver, paused) {
        if (gameOver || paused) return@LaunchedEffect
        while (!gameOver && !paused) {
            val stepDelay = (165L - (pkcellsEaten * 3L)).coerceIn(78L, 165L)
            delay(stepDelay)
            tickCount++
            dir = nextDir
            val head = snake.first()
            val nx = (head.x + dir.dx + cols) % cols
            val ny = (head.y + dir.dy + rows) % rows
            val nextHead = GridPt(nx, ny)

            if (nextHead in snake.dropLast(1) || nextHead in oneGrits) {
                gameOver = true
                statusCallout = if (nextHead in oneGrits) "💥 SMASHED BY THE 1-GRIT!" else "🐍 OOPS! FRANK BIT HER OWN TAIL!"
                HioneHaptics.unlock(ctx)
                if (score > highScore) {
                    highScore = score
                    prefs.edit().putInt("high_score", score).apply()
                }
            } else {
                val atePkcell = nextHead == pkcell
                val ateGold = goldenBonus != null && nextHead == goldenBonus
                val newSnake = buildList {
                    add(nextHead)
                    addAll(if (atePkcell || ateGold) snake else snake.dropLast(1))
                }
                snake = newSnake
                val occupied = (newSnake + oneGrits).toSet()

                if (atePkcell) {
                    pkcellsEaten++
                    score += 15
                    HioneHaptics.confirm(ctx)
                    statusCallout = when (pkcellsEaten % 5) {
                        0 -> "🔋 OH, MY PKCELL! (+15)"
                        1 -> "🐍 FRANK LOVES AAA BATTERIES! (+15)"
                        2 -> "🇦🇺 CASHIES SPECIAL! (+15)"
                        3 -> "🎶 SCARLET FIRE INTENSIFIES! (+15)"
                        else -> "🔋 GENUINE PKCELL POWER! (+15)"
                    }
                    if (score > highScore) {
                        highScore = score
                        prefs.edit().putInt("high_score", score).apply()
                    }
                    pkcell = randomFreeCell(occupied, nextHead)
                    if (pkcellsEaten % 4 == 0 && oneGrits.size < 10) {
                        val rock = randomFreeCell(occupied + pkcell, nextHead)
                        oneGrits = oneGrits + rock
                        statusCallout = "🪨 WATCH OUT! A 1-GRIT ROCK DROPPED!"
                    }
                    if (pkcellsEaten % 5 == 0 && goldenBonus == null) {
                        goldenBonus = randomFreeCell(occupied + pkcell, nextHead)
                    }
                } else if (ateGold) {
                    score += 50
                    goldenBonus = null
                    HioneHaptics.like(ctx)
                    statusCallout = "🪇 GOLDEN BABY MARACA! +50 PTS!"
                    if (score > highScore) {
                        highScore = score
                        prefs.edit().putInt("high_score", score).apply()
                    }
                }
            }
        }
    }

    val shrekGreen = Color(0xFF7BB31A)
    val pkcellEmerald = Color(0xFF00B862)
    val frankGold = Color(0xFFFFD166)

    Column(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0B1911), Color(0xFF07110B), Color(0xFF050B08))
                )
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top Header Bar
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    "🐍 FRANK THE SNAKE",
                    color = frankGold,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = AudiowideFont
                )
                Text(
                    "DANKPODS SECRET ARCADE · PKCELL HUNTER",
                    color = shrekGreen,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0x33FFFFFF))
                        .clickable { paused = !paused }
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    Text(
                        if (paused) "RESUME" else "PAUSE",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont
                    )
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFFFF3366))
                        .clickable { onExit() }
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    Text(
                        "EXIT",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Score & Callout Strip
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF102418))
                .border(1.5.dp, shrekGreen.copy(alpha = 0.65f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "SCORE $score",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Black,
                fontFamily = AudiowideFont
            )
            Text(
                "🔋 ×$pkcellsEaten   🪨 ×${oneGrits.size}",
                color = pkcellEmerald,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "BEST $highScore",
                color = frankGold,
                fontSize = 15.sp,
                fontWeight = FontWeight.Black,
                fontFamily = AudiowideFont
            )
        }

        Spacer(Modifier.height(4.dp))
        Text(
            statusCallout,
            color = Color(0xFFE0FFEA),
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
        Spacer(Modifier.height(6.dp))

        // Arcade Arena Board (supports swipe gestures anywhere on the board)
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF0A1810))
                .border(2.dp, shrekGreen, RoundedCornerShape(16.dp))
                .pointerInput(Unit) {
                    var dragX = 0f
                    var dragY = 0f
                    detectDragGestures(
                        onDragStart = { dragX = 0f; dragY = 0f },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragX += dragAmount.x
                            dragY += dragAmount.y
                            if (abs(dragX) > 28f || abs(dragY) > 28f) {
                                if (abs(dragX) > abs(dragY)) {
                                    steer(if (dragX > 0) SnakeDir.RIGHT else SnakeDir.LEFT)
                                } else {
                                    steer(if (dragY > 0) SnakeDir.DOWN else SnakeDir.UP)
                                }
                                dragX = 0f
                                dragY = 0f
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.fillMaxSize().padding(6.dp)) {
                val cellW = size.width / cols
                val cellH = size.height / rows

                // Subtle Shrek-green iPad case grid lines
                for (c in 0..cols) {
                    drawLine(
                        Color(0x187BB31A),
                        Offset(c * cellW, 0f),
                        Offset(c * cellW, size.height),
                        strokeWidth = 1f
                    )
                }
                for (r in 0..rows) {
                    drawLine(
                        Color(0x187BB31A),
                        Offset(0f, r * cellH),
                        Offset(size.width, r * cellH),
                        strokeWidth = 1f
                    )
                }

                // Draw 1-Grit Concrete Rocks
                for (rock in oneGrits) {
                    val rx = rock.x * cellW
                    val ry = rock.y * cellH
                    drawRoundRect(
                        color = Color(0xFF7D7A72),
                        topLeft = Offset(rx + 2f, ry + 2f),
                        size = Size(cellW - 4f, cellH - 4f),
                        cornerRadius = CornerRadius(4f, 4f)
                    )
                    drawRoundRect(
                        color = Color(0xFFBDB9B0),
                        topLeft = Offset(rx + 2f, ry + 2f),
                        size = Size(cellW - 4f, cellH - 4f),
                        cornerRadius = CornerRadius(4f, 4f),
                        style = Stroke(width = 2f)
                    )
                    // Jagged crack on the 1-Grit rock
                    drawLine(
                        Color(0xFF3E3C38),
                        Offset(rx + cellW * 0.25f, ry + cellH * 0.3f),
                        Offset(rx + cellW * 0.75f, ry + cellH * 0.7f),
                        strokeWidth = 2.2f
                    )
                }

                // Draw PKCELL AA Battery pickup
                run {
                    val bx = pkcell.x * cellW + cellW * 0.12f
                    val by = pkcell.y * cellH + cellH * 0.22f
                    val bw = cellW * 0.76f
                    val bh = cellH * 0.56f
                    // Metallic emerald green body
                    drawRoundRect(
                        color = Color(0xFF00B862),
                        topLeft = Offset(bx, by),
                        size = Size(bw * 0.75f, bh),
                        cornerRadius = CornerRadius(4f, 4f)
                    )
                    // Gold PKCELL top band
                    drawRoundRect(
                        color = Color(0xFFFFD166),
                        topLeft = Offset(bx + bw * 0.65f, by),
                        size = Size(bw * 0.25f, bh),
                        cornerRadius = CornerRadius(3f, 3f)
                    )
                    // Silver positive terminal nub
                    drawRoundRect(
                        color = Color(0xFFE5ECF4),
                        topLeft = Offset(bx + bw * 0.90f, by + bh * 0.25f),
                        size = Size(bw * 0.12f, bh * 0.50f),
                        cornerRadius = CornerRadius(2f, 2f)
                    )
                }

                // Draw Golden Maraca Bonus if active
                goldenBonus?.let { gb ->
                    val gx = (gb.x + 0.5f) * cellW
                    val gy = (gb.y + 0.5f) * cellH
                    drawCircle(
                        color = Color(0xFFFFD700),
                        radius = minOf(cellW, cellH) * 0.42f,
                        center = Offset(gx, gy)
                    )
                    drawCircle(
                        color = Color.White,
                        radius = minOf(cellW, cellH) * 0.18f,
                        center = Offset(gx - 3f, gy - 3f)
                    )
                }

                // Draw Frank the Snake!
                snake.forEachIndexed { idx, seg ->
                    val sx = seg.x * cellW
                    val sy = seg.y * cellH
                    val cx = sx + cellW * 0.5f
                    val cy = sy + cellH * 0.5f
                    if (idx == 0) {
                        // Frank's Head (warm golden-yellow python head with flicking red forked tongue!)
                        drawRoundRect(
                            color = Color(0xFFFFD166),
                            topLeft = Offset(sx + 1f, sy + 1f),
                            size = Size(cellW - 2f, cellH - 2f),
                            cornerRadius = CornerRadius(cellW * 0.35f, cellH * 0.35f)
                        )
                        // Eyes
                        drawCircle(Color(0xFF141414), radius = 3.2f, center = Offset(cx - cellW * 0.18f, cy - cellH * 0.15f))
                        drawCircle(Color(0xFF141414), radius = 3.2f, center = Offset(cx + cellW * 0.18f, cy - cellH * 0.15f))
                        // Flicking forked red tongue on even ticks
                        if (tickCount % 2 == 0) {
                            val tx = cx + dir.dx * cellW * 0.62f
                            val ty = cy + dir.dy * cellH * 0.62f
                            drawLine(
                                Color(0xFFFF2A55),
                                Offset(cx + dir.dx * cellW * 0.3f, cy + dir.dy * cellH * 0.3f),
                                Offset(tx, ty),
                                strokeWidth = 2.5f
                            )
                        }
                    } else {
                        // Python patterned scales (alternating warm gold and olive-brown carpet python spots)
                        val scaleColor = if (idx % 2 == 0) Color(0xFFE6B84D) else Color(0xFF8C6226)
                        val inset = (2f + (idx.toFloat() / snake.size) * 2.5f).coerceAtMost(5f)
                        drawRoundRect(
                            color = scaleColor,
                            topLeft = Offset(sx + inset, sy + inset),
                            size = Size(cellW - inset * 2f, cellH - inset * 2f),
                            cornerRadius = CornerRadius(6f, 6f)
                        )
                    }
                }
            }

            if (gameOver) {
                Column(
                    Modifier
                        .fillMaxWidth(0.86f)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xEE101812))
                        .border(2.dp, Color(0xFFFF3366), RoundedCornerShape(18.dp))
                        .padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "GAME OVER, MATE!",
                        color = Color(0xFFFF3366),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        statusCallout,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Score: $score  ·  PKCELLs: $pkcellsEaten  ·  Best: $highScore",
                        color = frankGold,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(14.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(shrekGreen)
                            .clickable { resetGame() }
                            .padding(horizontal = 24.dp, vertical = 10.dp)
                    ) {
                        Text(
                            "PLAY AGAIN 🐍",
                            color = Color(0xFF08120B),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = AudiowideFont
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Two-Thumb Arcade Directional Pad
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ArcadeDirButton("◄ LEFT", Modifier.weight(1f), shrekGreen) { steer(SnakeDir.LEFT) }
            ArcadeDirButton("▲ UP", Modifier.weight(1f), frankGold) { steer(SnakeDir.UP) }
            ArcadeDirButton("▼ DOWN", Modifier.weight(1f), frankGold) { steer(SnakeDir.DOWN) }
            ArcadeDirButton("RIGHT ►", Modifier.weight(1f), shrekGreen) { steer(SnakeDir.RIGHT) }
        }
    }
}

@Composable
private fun ArcadeDirButton(label: String, modifier: Modifier, accent: Color, onClick: () -> Unit) {
    Box(
        modifier
            .height(50.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF142A1D))
            .border(1.5.dp, accent.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Black,
            fontFamily = AudiowideFont
        )
    }
}
