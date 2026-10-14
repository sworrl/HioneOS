package com.hione.player

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.player.AudiowideFont
import com.hione.player.CyberGlassBorder
import com.hione.player.HioneCyan
import com.hione.player.HioneNeonPink
import com.hione.player.HioneTextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class FnLockHudActivity : ComponentActivity() {

    private var observer: ContentObserver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show over lockscreen, keep screen active
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        var isLockedState by mutableStateOf(intent.getBooleanExtra(EXTRA_IS_LOCKED, true))
        val allowVolume = intent.getBooleanExtra(EXTRA_ALLOW_VOLUME, false)
        val lockPower = intent.getBooleanExtra(EXTRA_LOCK_POWER, true)


        // Real-time hardware switch observer while lock activity is active
        val cr = contentResolver
        observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                val status = try {
                    Settings.Global.getInt(cr, HionePocketLockManager.SETTING_FN_STATUS, 0)
                } catch (_: Throwable) { 0 }
                val newLock = status == 1
                if (newLock != isLockedState) {
                    isLockedState = newLock
                }
            }
        }
        try {
            cr.registerContentObserver(
                Settings.Global.getUriFor(HionePocketLockManager.SETTING_FN_STATUS),
                false,
                observer!!
            )
        } catch (_: Throwable) {}

        setContent {
            FnLockBespokeHioneModal(
                isLocked = isLockedState,
                allowVolume = allowVolume,
                lockPower = lockPower,
                onDismiss = {
                    finish()
                    overridePendingTransition(0, android.R.anim.fade_out)
                }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    override fun onDestroy() {
        super.onDestroy()
        observer?.let {
            try { contentResolver.unregisterContentObserver(it) } catch (_: Throwable) {}
        }
    }

    companion object {
        const val EXTRA_IS_LOCKED = "extra_is_locked"
        const val EXTRA_ALLOW_VOLUME = "extra_allow_volume"
        const val EXTRA_LOCK_POWER = "extra_lock_power"
    }
}

/**
 * Oki Hione Bespoke Hardware Pocket Lock & Touch Shield Modal.
 * Unified 3D double-beveled cyber container with task-specific Hione artwork,
 * persistent touch barrier protection, animated status glowing badges,
 * and tactile kawaii feedback on touch attempts.
 */
@Composable
fun FnLockBespokeHioneModal(
    isLocked: Boolean,
    allowVolume: Boolean,
    lockPower: Boolean,
    onDismiss: () -> Unit
) {
    // Intercept back key when locked to prevent dismissing the physical lock
    BackHandler(enabled = isLocked) {
        // Consumed to prevent hardware key bypass
    }

    val coroutineScope = rememberCoroutineScope()
    val alphaAnim = remember { Animatable(0f) }
    val scaleAnim = remember { Animatable(0.88f) }
    val shakeAnim = remember { Animatable(0f) }

    // When unlocked, automatically fade out and dismiss after 1.3 seconds
    LaunchedEffect(isLocked) {
        alphaAnim.animateTo(1f, tween(200, easing = FastOutSlowInEasing))
        scaleAnim.animateTo(1f, tween(200, easing = FastOutSlowInEasing))
        if (!isLocked) {
            delay(1200)
            alphaAnim.animateTo(0f, tween(250, easing = FastOutSlowInEasing))
            onDismiss()
        }
    }

    val primaryColor = if (isLocked) HioneNeonPink else Mascot.color(0xFFFFC56B)
    val secondaryColor = if (isLocked) Color(0xFFFF5252) else Mascot.color(0xFF00FFCC)

    val infiniteTransition = rememberInfiniteTransition(label = "LockGlowPulse")
    val pulseGlow by infiniteTransition.animateFloat(
        initialValue = 0.65f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Pulse"
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(Mascot.color(0xEE02090D))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                if (isLocked) {
                    coroutineScope.launch {
                        // Playful kawaii shake feedback when tapping locked screen
                        shakeAnim.snapTo(-8f)
                        shakeAnim.animateTo(8f, tween(50, easing = LinearEasing))
                        shakeAnim.animateTo(-4f, tween(50, easing = LinearEasing))
                        shakeAnim.animateTo(0f, tween(50, easing = FastOutSlowInEasing))
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // Outer 3D Beveled Modal Shell matching Observatory signature style
        Box(
            Modifier
                .offset(x = shakeAnim.value.dp)
                .fillMaxWidth(0.92f)
                .wrapContentHeight()
                .scale(scaleAnim.value)
                .clip(CutCornerShape(16.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.25f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.75f)
                        )
                    )
                )
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(1.dp)
                    .clip(CutCornerShape(15.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Mascot.color(0xFA09202A),
                                Mascot.color(0xFF04121A),
                                Mascot.color(0xFF02090D)
                            )
                        )
                    )
                    .border(
                        BorderStroke(
                            1.2.dp,
                            Brush.verticalGradient(
                                listOf(
                                    primaryColor.copy(alpha = pulseGlow),
                                    CyberGlassBorder.copy(alpha = 0.4f),
                                    secondaryColor.copy(alpha = 0.7f)
                                )
                            )
                        ),
                        CutCornerShape(15.dp)
                    )
                    .padding(16.dp)
            ) {
                // Frosted Watermark of Task-Specific Hione Art
                Image(
                    painter = mascotPainter(
                        if (isLocked) R.drawable.hione_pose_headphones else R.drawable.hione_pose_peace
                    ),
                    contentDescription = "${Mascot.name} artwork",
                    modifier = Modifier
                        .size(130.dp)
                        .align(Alignment.BottomEnd)
                        .offset(x = 18.dp, y = 18.dp),
                    alpha = 0.18f,
                    contentScale = ContentScale.Fit
                )

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Header Bar with Bespoke Hione Avatar
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(36.dp)
                                    .clip(CutCornerShape(8.dp))
                                    .background(primaryColor.copy(alpha = 0.2f))
                                    .border(1.dp, primaryColor, CutCornerShape(8.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    painter = mascotPainter(
                                        if (isLocked) R.drawable.hione_chibi_hearts else R.drawable.hione_sprite
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = if (isLocked) "POCKET LOCK ON" else "POCKET LOCK OFF",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = AudiowideFont,
                                    letterSpacing = 0.8.sp
                                )
                                Text(
                                    text = if (isLocked) "Fn switch is on" else "Touch and buttons work again",
                                    color = primaryColor,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = AudiowideFont
                                )
                            }
                        }

                        // Status Pill
                        Box(
                            Modifier
                                .clip(CutCornerShape(6.dp))
                                .background(primaryColor.copy(alpha = 0.25f))
                                .border(1.dp, primaryColor, CutCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = if (isLocked) "LOCKED" else "ACTIVE",
                                color = primaryColor,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = AudiowideFont
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    // Hero Illuminated Lock Glyph
                    Box(
                        Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(primaryColor.copy(alpha = 0.18f))
                            .border(1.5.dp, primaryColor, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = null,
                            tint = primaryColor,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    Text(
                        text = if (isLocked)
                            "TOUCHSCREEN AND BUTTONS LOCKED"
                        else
                            "TOUCH AND BUTTONS UNLOCKED",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = AudiowideFont,
                        textAlign = TextAlign.Center
                    )

                    Spacer(Modifier.height(4.dp))

                    Text(
                        text = if (isLocked)
                            "Touches and power presses are blocked. Music keeps playing.\nFlip the Fn switch up to unlock."
                        else
                            "Touchscreen, buttons and gestures all work.",
                        color = HioneTextSecondary,
                        fontSize = 9.sp,
                        lineHeight = 13.sp,
                        textAlign = TextAlign.Center
                    )

                    if (isLocked) {
                        Spacer(Modifier.height(14.dp))

                        // 3D Embossed Hardware Telemetry Badges
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Touch Digitizer Status Card
                            Box(
                                Modifier
                                    .weight(1f)
                                    .clip(CutCornerShape(6.dp))
                                    .background(Mascot.color(0xEE07161E))
                                    .border(0.8.dp, CyberGlassBorder, CutCornerShape(6.dp))
                                    .padding(8.dp)
                            ) {
                                Column {
                                    Text("TOUCHSCREEN", color = HioneTextSecondary, fontSize = 7.5.sp, fontWeight = FontWeight.Bold)
                                    Text("SHIELDED", color = primaryColor, fontSize = 8.5.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
                                }
                            }

                            // Volume Knob Status Card
                            Box(
                                Modifier
                                    .weight(1f)
                                    .clip(CutCornerShape(6.dp))
                                    .background(Mascot.color(0xEE07161E))
                                    .border(0.8.dp, CyberGlassBorder, CutCornerShape(6.dp))
                                    .padding(8.dp)
                            ) {
                                Column {
                                    Text("VOLUME KNOB", color = HioneTextSecondary, fontSize = 7.5.sp, fontWeight = FontWeight.Bold)
                                    Text(if (allowVolume) "ACTIVE" else "LOCKED", color = if (allowVolume) HioneCyan else primaryColor, fontSize = 8.5.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
                                }
                            }

                            // Power Button Status Card
                            Box(
                                Modifier
                                    .weight(1f)
                                    .clip(CutCornerShape(6.dp))
                                    .background(Mascot.color(0xEE07161E))
                                    .border(0.8.dp, CyberGlassBorder, CutCornerShape(6.dp))
                                    .padding(8.dp)
                            ) {
                                Column {
                                    Text("POWER BUTTON", color = HioneTextSecondary, fontSize = 7.5.sp, fontWeight = FontWeight.Bold)
                                    Text(if (lockPower) "BLOCKED" else "ACTIVE", color = if (lockPower) primaryColor else HioneCyan, fontSize = 8.5.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

