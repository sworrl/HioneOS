package com.hione.wheel

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A small Breakout-style game in the screen's own pixels. The wheel moves the paddle, the
 * center button serves. All state is primitive fields so a frame allocates nothing.
 */
class BrickGame(val w: Int, val h: Int, private val fieldTop: Int) {
    val cols = 8
    val rows = 5
    val brickH = maxOf(4, h / 30)
    val brickTop = fieldTop + brickH * 2
    val bricks = BooleanArray(cols * rows)
    val paddleW = w / 6f
    val paddleH = maxOf(3f, h / 48f)
    val paddleY = h - paddleH * 3
    val ball = maxOf(3f, w / 64f)

    var paddleX = w / 2f
    var bx = 0f; var by = 0f
    var vx = 0f; var vy = 0f
    var served = false
    var lives = 3
    var score = 0
    var level = 1
    var over = false
    var paused = false

    init { reset(full = true) }

    fun reset(full: Boolean) {
        if (full) { lives = 3; score = 0; level = 1; over = false }
        for (i in bricks.indices) bricks[i] = true
        park()
    }

    private fun park() {
        served = false
        bx = paddleX; by = paddleY - ball
        vx = 0f; vy = 0f
    }

    /** Wheel detents move the paddle. */
    fun move(steps: Int) {
        if (paused) return
        paddleX = (paddleX + steps * w / 26f).coerceIn(paddleW / 2, w - paddleW / 2)
        if (!served) bx = paddleX
    }

    /** Center button: serve, or start over after game over. */
    fun press() {
        if (over) { reset(full = true); return }
        if (paused) { paused = false; return }
        if (!served) {
            served = true
            val speed = speed()
            vx = speed * 0.55f * (if (paddleX < w / 2f) 1 else -1)
            vy = -speed * 0.83f
        }
    }

    private fun speed() = h * (0.85f + 0.12f * (level - 1))

    fun brickW() = w.toFloat() / cols

    fun step(dtIn: Float) {
        if (!served || over || paused) return
        val dt = dtIn.coerceAtMost(0.05f)
        bx += vx * dt
        by += vy * dt
        val r = ball / 2
        if (bx - r < 0) { bx = r; vx = abs(vx) }
        if (bx + r > w) { bx = w - r; vx = -abs(vx) }
        if (by - r < fieldTop) { by = fieldTop + r; vy = abs(vy) }

        // Paddle: the further from center you hit, the steeper the bounce.
        if (vy > 0 && by + r >= paddleY && by + r <= paddleY + paddleH + 4 &&
            bx >= paddleX - paddleW / 2 - r && bx <= paddleX + paddleW / 2 + r) {
            val off = ((bx - paddleX) / (paddleW / 2)).coerceIn(-1f, 1f)
            val s = sqrt(vx * vx + vy * vy)
            vx = s * off * 0.8f
            vy = -sqrt((s * s - vx * vx).coerceAtLeast(s * s * 0.2f))
            by = paddleY - r
        }

        // Bricks: one hit per frame is plenty at these speeds.
        val bw = brickW()
        val row = ((by - brickTop) / brickH).toInt()
        val col = (bx / bw).toInt()
        if (by >= brickTop && row in 0 until rows && col in 0 until cols) {
            val i = row * cols + col
            if (bricks[i]) {
                bricks[i] = false
                score += (rows - row) * 10
                vy = -vy
                if (bricks.none { it }) { level++; reset(full = false) }
            }
        }

        if (by - r > h) {
            lives--
            if (lives <= 0) { over = true; served = false } else park()
        }
    }
}
