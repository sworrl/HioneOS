package com.m500.hardware

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhonelinkLock
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class PocketHudActivity : ComponentActivity() {
    private var allowVolumeWheel = false
    private val unlockReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            val action = intent?.action ?: return
            if (action == ACTION_UNLOCK_POCKET_HUD) {
                finish()
            } else if (action == "FN_BUTTON_STATE_CHANGE") {
                val isCovered = intent.getBooleanExtra("fnCoverd", false) ||
                        intent.getBooleanExtra("isCovered", false) ||
                        intent.getBooleanExtra("state", false)
                if (!isCovered) {
                    finish()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on, show over keyguard, and capture all touch inputs
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        val mode = intent.getStringExtra("EXTRA_FN_MODE") ?: PocketLockManager.getFnMode(this)
        allowVolumeWheel = intent.getBooleanExtra("EXTRA_ALLOW_VOL", PocketLockManager.isAllowVolumeWheel(this))

        setContent {
            CyberPocketHudScreen(mode = mode, allowVolumeWheel = allowVolumeWheel)
        }

        val filter = android.content.IntentFilter().apply {
            addAction(ACTION_UNLOCK_POCKET_HUD)
            addAction("FN_BUTTON_STATE_CHANGE")
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(unlockReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(unlockReceiver, filter)
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("EXTRA_ACTION_UNLOCK", false)) {
            finish()
        }
    }

    private var lastTapTime = 0L

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        // Double-tap emergency escape
        if (ev.action == android.view.MotionEvent.ACTION_DOWN) {
            val now = System.currentTimeMillis()
            if (now - lastTapTime < 400) {
                finish()
                return true
            }
            lastTapTime = now
        }
        return true
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        return true
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (allowVolumeWheel && (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_MUTE)) {
            return super.dispatchKeyEvent(event)
        }
        // Consume all other keys (Prev, Play, Next, Home, Back)
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(unlockReceiver) } catch (_: Throwable) {}
    }

    companion object {
        const val ACTION_UNLOCK_POCKET_HUD = "com.m500.hardware.action.UNLOCK_POCKET_HUD"
    }
}

@Composable
fun CyberPocketHudScreen(mode: String, allowVolumeWheel: Boolean) {
    val isBoth = mode == "touch_and_key_lock" || mode == "Both"
    val isTouch = mode == "touch_lock"

    val infiniteTransition = rememberInfiniteTransition()
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    val primaryColor = when {
        isBoth -> Color(0xFFFFB300) // Master Amber
        isTouch -> Mascot.color(0xFFFFC56B) // Hione Cyan
        else -> Mascot.color(0xFF06D6A0) // Emerald Jade
    }

    val title = when {
        isBoth -> "POCKET LOCK ACTIVE"
        isTouch -> "TOUCH LOCK ACTIVE"
        else -> "KEY LOCK ACTIVE"
    }

    val subtitle = when {
        isBoth -> "Touchscreen & side buttons locked"
        isTouch -> "Touchscreen locked • Side buttons work"
        else -> "Side buttons locked • Touchscreen works"
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xCC050A0E)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .width(320.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF0B141C))
                .border(2.dp, primaryColor.copy(alpha = 0.8f), RoundedCornerShape(24.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Glowing Cyber Icon
            Box(
                Modifier
                    .size(72.dp)
                    .scale(pulseScale)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(primaryColor.copy(alpha = 0.35f), Color.Transparent)
                        )
                    )
                    .border(2.dp, primaryColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isBoth) Icons.Default.Lock else if (isTouch) Icons.Default.TouchApp else Icons.Default.PhonelinkLock,
                    contentDescription = null,
                    tint = primaryColor,
                    modifier = Modifier.size(36.dp)
                )
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = title,
                color = primaryColor,
                fontSize = 17.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(6.dp))

            Text(
                text = subtitle,
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                lineHeight = 16.sp
            )

            if (isBoth && allowVolumeWheel) {
                Spacer(Modifier.height(14.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF13222E))
                        .border(1.dp, Mascot.color(0xFFFFC56B).copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        .padding(vertical = 6.dp, horizontal = 10.dp)
                ) {
                    Text(
                        text = "Volume wheel still works",
                        color = Mascot.color(0xFFFFC56B),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
