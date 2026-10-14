package com.hione.systemui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Shade host. When opened by the nav service's top-strip pull, the panel FOLLOWS THE FINGER 1:1
 * through [HioneShadeDrag] (the strip keeps the pointer for the whole gesture): the panel sits
 * with its bottom edge at the finger until released, then settles with a 400/0.85 spring using
 * the real release velocity, or — if released early / flung upward — slides back out and finishes.
 * Pulling further than the panel's height keeps expanding QS continuously (see the view).
 */
class HioneShadeActivity : ComponentActivity() {
    companion object {
        /** Pixels the finger had already pulled down when the nav service opened us. */
        const val EXTRA_DRAG_OFFSET_PX = "hione.shade.drag_offset_px"
        /** Open straight into the full quick-settings grid (second pull / QS tile). */
        const val EXTRA_START_EXPANDED = "hione.shade.start_expanded"
    }

    private var instantFinish = false
    /** Bumped by onNewIntent so a fresh pull on an already-open shade re-arms the finger follow. */
    private val followKey = androidx.compose.runtime.mutableIntStateOf(0)
    private var followOffset = -1

    private fun hideSystemBars() {
        try {
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
            )
        } catch (_: Throwable) {}
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dragOffset = intent?.getIntExtra(EXTRA_DRAG_OFFSET_PX, -1) ?: -1
        followOffset = dragOffset
        try {
            if (dragOffset >= 0) overridePendingTransition(0, 0)
            else overridePendingTransition(R.anim.slide_down_in, R.anim.fade_out)
        } catch (_: Throwable) {}

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.addFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        hideSystemBars()

        setContent {
            val screenH = resources.displayMetrics.heightPixels.toFloat()
            val follow = dragOffset >= 0
            var panelH by remember { mutableFloatStateOf(screenH * 0.94f) }
            // Panel offset (px, ≤ 0): bottom edge rides the finger while it is down.
            val ty = remember { Animatable(if (follow) -(screenH * 0.94f - dragOffset).coerceAtLeast(0f) else 0f) }
            var dismissing by remember { mutableStateOf(false) }
            val key by followKey

            if (follow || key > 0) {
                LaunchedEffect(key) {
                    val serial = HioneShadeDrag.state.value.serial
                    var wasDown = true
                    HioneShadeDrag.state.collect { s ->
                        if (s.serial != serial) return@collect
                        if (s.fingerDown) {
                            wasDown = true
                            ty.snapTo((s.dragPx - panelH).coerceAtMost(0f))
                        } else if (wasDown) {
                            wasDown = false
                            val v = s.velocityPxS
                            val revealed = ((panelH + ty.value) / panelH).coerceIn(0f, 1f)
                            if (!dismissing && (v < -900f || (revealed < 0.45f && v < 600f))) {
                                dismissing = true
                                ty.animateTo(-panelH - 40f, tween(HioneMotion.ms(150), easing = FastOutSlowInEasing))
                                instantFinish = true
                                finish()
                            } else {
                                ty.animateTo(0f, HioneMotion.settle(), initialVelocity = v.coerceIn(-4000f, 4000f))
                            }
                        }
                    }
                }
            }

            HioneNotificationShadeView(
                onDismiss = { finish() },
                onOpenSettings = {
                    try {
                        val intent = packageManager.getLaunchIntentForPackage("com.hione.settings")
                        if (intent != null) startActivity(intent)
                    } catch (_: Throwable) {}
                },
                onOpenPower = {
                    try {
                        startActivity(Intent(this@HioneShadeActivity, HionePowerMenuActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION
                        })
                    } catch (_: Throwable) {}
                },
                startExpanded = intent?.getBooleanExtra(EXTRA_START_EXPANDED, false) == true,
                followFinger = follow,
                panelOffsetPx = { ty.value },
                revealProgress = { ((panelH + ty.value) / panelH).coerceIn(0f, 1f) },
                onPanelHeight = { h -> if (h > 0) panelH = h.toFloat() }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val off = intent.getIntExtra(EXTRA_DRAG_OFFSET_PX, -1)
        if (off >= 0) { followOffset = off; followKey.intValue++ }   // grab the open panel with the new finger
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        try {
            if (instantFinish) overridePendingTransition(0, 0)
            else overridePendingTransition(R.anim.fade_in, R.anim.slide_up_out)
        } catch (_: Throwable) {}
    }
}
