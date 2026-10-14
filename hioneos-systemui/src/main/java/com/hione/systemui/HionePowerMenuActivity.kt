package com.hione.systemui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * HioneOS Custom Themed Power Menu (Global Actions / Long Power Press Modal)
 * Provides tactile, cyberpunk-styled hardware power actions including Lockdown,
 * Fast Reboot, Power Off, Recovery, Bootloader, and SystemUI restart.
 */
class HionePowerMenuActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        } catch (_: Throwable) {}

        window.setBackgroundDrawableResource(android.R.color.transparent)
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DIM_BEHIND
        )
        window.setDimAmount(0.60f)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            HionePowerMenuScreen(
                onDismiss = { finish() }
            )
        }
    }

    override fun finish() {
        super.finish()
        try {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        } catch (_: Throwable) {}
    }
}

@Composable
fun HionePowerMenuScreen(
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var pendingActionName by remember { mutableStateOf<String?>(null) }
    var pendingActionCommand by remember { mutableStateOf<String?>(null) }
    var pendingActionAccent by remember { mutableStateOf(HioneTealBright) }
    var countdownSeconds by remember { mutableIntStateOf(3) }
    // Lockdown only means something with a PIN or password set. Without one the pill is a plain lock.
    val canLockdown = remember { HioneLockdown.available(ctx) }

    fun hapticTick() = HioneHaptics.confirm(ctx)

    fun executeAction(command: String) {
        hapticTick()
        scope.launch(Dispatchers.IO) {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            when (command) {
                // "lockdown" (credential set): PIN required for the next unlock, see HioneLockdown.
                // "lock": the screen off and the Hione lockscreen, as before.
                "lockdown", "lock" -> {
                    if (command == "lockdown") HioneLockdown.engage(ctx)
                    val lockIntent = Intent().apply {
                        setClassName("com.hione.launcher", "com.hione.launcher.lockscreen.HioneLockscreenActivity")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        // Arm it dark. Without this it turns the screen back on right after the sleep below.
                        putExtra("is_screen_off_transition", true)
                    }
                    try { ctx.startActivity(lockIntent) } catch (_: Throwable) {}
                    HioneLockdown.screenOff(ctx)
                    (ctx as? Activity)?.finish()
                }
                "systemui" -> {
                    try {
                        Runtime.getRuntime().exec(arrayOf("pkill", "-f", "com.hione.systemui"))
                    } catch (_: Throwable) {}
                    (ctx as? Activity)?.finish()
                }
                "reboot -p" -> {
                    // PowerManager.shutdown(confirm, reason, wait) is @hide but callable with REBOOT
                    // perm (which we hold); the ACTION_REQUEST_SHUTDOWN startActivity never resolved.
                    val ok = runCatching {
                        val m = android.os.PowerManager::class.java.getMethod("shutdown", Boolean::class.javaPrimitiveType, String::class.java, Boolean::class.javaPrimitiveType)
                        m.invoke(pm, false, "userrequested", false); true
                    }.getOrDefault(false)
                    if (!ok) runCatching {
                        ctx.sendBroadcast(Intent("android.intent.action.ACTION_REQUEST_SHUTDOWN").apply {
                            putExtra("android.intent.extra.KEY_CONFIRM", false); flags = Intent.FLAG_RECEIVER_FOREGROUND
                        })
                    }
                }
                else -> {
                    val reason = when (command) {
                        "reboot recovery" -> "recovery"
                        "reboot bootloader" -> "bootloader"
                        else -> null
                    }
                    try {
                        pm?.reboot(reason)
                    } catch (t: Throwable) {
                        android.util.Log.e("HionePowerMenu", "reboot($reason) failed", t)
                        // fallback: request reboot via the recovery/bootloader path or a shell as last resort
                        runCatching { Runtime.getRuntime().exec(arrayOf("svc", "power", "reboot", reason ?: "")) }
                    }
                }
            }
        }
    }

    // Battery level telemetry — null (shown as "—") when the sticky broadcast has no level; never 100% by default.
    val batteryPct: Int? = remember {
        try {
            val bi = ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            val lvl = bi?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = bi?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            if (lvl >= 0 && scale > 0) lvl * 100 / scale else null
        } catch (_: Throwable) { null }
    }

    // Breathing glow for the power core arc — static in audio_only / idle (no infinite transition)
    val coreGlow: Float = if (HioneMotion.quiet) 0.75f else {
        val infiniteTransition = rememberInfiniteTransition(label = "corePulse")
        val g by infiniteTransition.animateFloat(
            initialValue = 0.5f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(1400, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "coreGlow"
        )
        g
    }
    // Card entrance: 0.92 → 1 scale + fade, 180ms, then the pills stagger in 40ms apart
    val cardIn = remember { Animatable(0f) }
    LaunchedEffect(Unit) { cardIn.animateTo(1f, HioneMotion.ease(180)) }

    // Backdrop with tap-to-dismiss
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Mascot.color(0xCC03090C))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                if (pendingActionName != null) {
                    pendingActionName = null
                    pendingActionCommand = null
                } else {
                    onDismiss()
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // Futuristic Card Frame
        Box(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .wrapContentHeight()
                .graphicsLayer { alpha = cardIn.value; val sc = 0.92f + 0.08f * cardIn.value; scaleX = sc; scaleY = sc }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {} // Intercept taps inside card
                .drawBehind {
                    val rr = CornerRadius(22.dp.toPx(), 22.dp.toPx())
                    drawRoundRect(
                        Color(0x88000000),
                        topLeft = Offset(0f, 6.dp.toPx()),
                        size = size,
                        cornerRadius = rr
                    )
                }
                .clip(RoundedCornerShape(22.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Mascot.color(0xFA0B222A),
                            Mascot.color(0xF506141A),
                            Mascot.color(0xFA030B0F)
                        )
                    )
                )
                .border(
                    1.4.dp,
                    Brush.linearGradient(
                        listOf(
                            HioneTealBright.copy(alpha = 0.85f * coreGlow),
                            HionePurple.copy(alpha = 0.60f),
                            HionePinkBright.copy(alpha = 0.80f * coreGlow)
                        )
                    ),
                    RoundedCornerShape(22.dp)
                )
                .padding(horizontal = 16.dp, vertical = 18.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top Arc Reactor Core & Telemetry Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Cyan energy core icon
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        listOf(
                                            HioneTealBright.copy(alpha = 0.4f * coreGlow),
                                            Color.Transparent
                                        )
                                    )
                                )
                                .border(1.2.dp, HioneTealBright, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Bolt,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "POWER",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.2.sp
                            )
                            Text(
                                text = "${Mascot.osName} · tap, then 3s to cancel",
                                color = HioneTealBright.copy(alpha = 0.85f),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.6.sp
                            )
                        }
                    }

                    // Battery Telemetry Pill
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Mascot.color(0xFF081C22))
                            .border(1.dp, HioneTeal.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "${batteryPct?.let { "$it%" } ?: "--"}",
                            color = HioneTealBright,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Pending Action Countdown Dialog Overlay (if armed)
                if (pendingActionName != null && pendingActionCommand != null) {
                    LaunchedEffect(pendingActionCommand) {
                        countdownSeconds = 3
                        while (countdownSeconds > 0) {
                            delay(1000)
                            countdownSeconds--
                        }
                        executeAction(pendingActionCommand!!)
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Mascot.color(0xFF031015))
                            .border(1.2.dp, pendingActionAccent, RoundedCornerShape(16.dp))
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "$pendingActionName",
                            color = pendingActionAccent,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Starting in $countdownSeconds s",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 12.sp
                        )
                        Spacer(Modifier.height(14.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = {
                                    pendingActionName = null
                                    pendingActionCommand = null
                                },
                                modifier = Modifier.weight(1f).height(40.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0x33FFFFFF)),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.4f)),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("CANCEL", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            HoldToConfirm(
                                accent = pendingActionAccent,
                                modifier = Modifier.weight(1f)
                            ) { executeAction(pendingActionCommand!!) }
                        }
                    }
                } else {
                    // Pixel-11-style vertical stack of large pill actions (56dp, 28dp corners),
                    // destructive ones arm the 3s confirm countdown above.
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (canLockdown) {
                            PowerPill(0, "LOCKDOWN", "Lock now, PIN needed to open", Icons.Default.Shield, HioneTealBright) { executeAction("lockdown") }
                        } else {
                            PowerPill(0, "LOCK", "Lock the screen now", Icons.Default.Lock, HioneTealBright) { executeAction("lock") }
                        }
                        PowerPill(1, "REBOOT", "Restart", Icons.Default.RestartAlt, Mascot.color(0xFFFFC56B)) {
                            pendingActionName = "REBOOT"; pendingActionCommand = "reboot"; pendingActionAccent = Mascot.color(0xFFFFC56B)
                        }
                        PowerPill(2, "POWER OFF", "Turn off", Icons.Default.PowerSettingsNew, HionePinkBright) {
                            pendingActionName = "POWER OFF"; pendingActionCommand = "reboot -p"; pendingActionAccent = HionePinkBright
                        }
                        PowerPill(3, "RECOVERY", "Restart to recovery", Icons.Default.SettingsBackupRestore, HionePurple) {
                            pendingActionName = "RECOVERY"; pendingActionCommand = "reboot recovery"; pendingActionAccent = HionePurple
                        }
                        PowerPill(4, "FASTBOOT", "Restart to the bootloader", Icons.Default.DeveloperMode, HioneGold) {
                            pendingActionName = "FASTBOOT"; pendingActionCommand = "reboot bootloader"; pendingActionAccent = HioneGold
                        }
                        PowerPill(5, "RESTART SYSTEMUI", "Restart the shade and status bar", Icons.Default.Refresh, Color(0xFF00E676)) { executeAction("systemui") }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Bottom Close / Dismiss Pill
                Button(
                    onClick = {
                        hapticTick()
                        onDismiss()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0x18FFFFFF)),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "DISMISS",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }
            }
        }
    }
}

/**
 * 3D Tactile Action Card for the Power Core Modal
 */
@Composable
fun PowerActionTile(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    onClick: () -> Unit
) {
    val ctx = LocalContext.current
    Box(
        modifier = modifier
            .height(76.dp)
            .drawBehind {
                val rr = CornerRadius(14.dp.toPx(), 14.dp.toPx())
                drawRoundRect(
                    Color(0x66000000),
                    topLeft = Offset(0f, 3.dp.toPx()),
                    size = size,
                    cornerRadius = rr
                )
            }
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        Mascot.color(0xFF0B2129),
                        Mascot.color(0xFF05131A),
                        Mascot.color(0xFF02090D)
                    )
                )
            )
            .border(
                1.2.dp,
                Brush.verticalGradient(
                    listOf(
                        accentColor.copy(alpha = 0.75f),
                        accentColor.copy(alpha = 0.20f),
                        Color.Transparent
                    )
                ),
                RoundedCornerShape(14.dp)
            )
            .clickable {
                HioneHaptics.confirm(ctx)
                onClick()
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon Badge with glowing halo
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(accentColor.copy(alpha = 0.18f))
                    .border(1.dp, accentColor.copy(alpha = 0.5f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = accentColor,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(Modifier.width(8.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.6.sp,
                    maxLines = 1
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    text = subtitle,
                    color = HioneMuted,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
    }
}


/** Pixel-style full-width pill action: 56dp tall, icon disc left, label + hint, accent border. */
@Composable
fun PowerPill(
    index: Int,
    title: String,
    hint: String,
    icon: ImageVector,
    accent: Color,
    onClick: () -> Unit
) {
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    // Stagger-in: 40ms per row, 220ms ease, 24dp rise (HioneMotion)
    val t = remember { Animatable(if (HioneMotion.quiet) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (t.value < 1f) { delay(HioneMotion.STAGGER_MS * index); t.animateTo(1f, HioneMotion.ease(220)) }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .graphicsLayer {
                alpha = t.value
                translationY = (1f - t.value) * 24.dp.toPx()
                val sc = 0.96f + 0.04f * t.value; scaleX = sc; scaleY = sc
            }
            .pressScale(interaction)
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.22f), Mascot.color(0xFF07171E))))
            .border(1.dp, accent.copy(alpha = 0.7f), RoundedCornerShape(28.dp))
            .clickable(interactionSource = interaction, indication = null) {
                HioneHaptics.confirm(view)
                onClick()
            }
            .padding(start = 8.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(CircleShape).background(accent.copy(alpha = 0.25f)).border(1.dp, accent.copy(alpha = 0.6f), CircleShape),
            contentAlignment = Alignment.Center
        ) { Icon(icon, contentDescription = title, tint = accent, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 0.8.sp, maxLines = 1)
            Text(hint, color = HioneMuted, fontSize = 11.sp, maxLines = 1)
        }
        Text("›", color = accent.copy(alpha = 0.8f), fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}


/**
 * Pixel-style hold-to-confirm: press and hold 1.2s; the fill ring grows linearly with a light
 * haptic tick at 25 / 50 / 75 % and a strong pop at 100 %, then [onConfirmed] runs. Releasing
 * early springs the fill back to zero.
 */
@Composable
private fun HoldToConfirm(accent: Color, modifier: Modifier = Modifier, onConfirmed: () -> Unit) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    var holding by remember { mutableStateOf(false) }
    Box(
        modifier
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(accent.copy(alpha = 0.18f))
            .border(1.2.dp, accent, RoundedCornerShape(10.dp))
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    holding = true
                    HioneHaptics.tick(view)
                    val job = scope.launch {
                        var ticked = 0
                        progress.animateTo(1f, tween(HioneMotion.ms(1200), easing = LinearEasing)) {
                            val q = (value * 4f).toInt()
                            if (q > ticked && q in 1..3) { ticked = q; HioneHaptics.tick(view) }
                        }
                        if (progress.value >= 0.999f) { HioneHaptics.pop(view); onConfirmed() }
                    }
                    tryAwaitRelease()
                    holding = false
                    if (progress.value < 0.999f) {
                        job.cancel()
                        scope.launch { progress.animateTo(0f, HioneMotion.bouncy()) }
                    }
                })
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress.value.coerceIn(0f, 1f))
                .background(accent.copy(alpha = 0.45f))
                .align(Alignment.CenterStart)
        )
        Text(
            if (holding) "Holding  ${(progress.value * 100).toInt()}%" else "HOLD TO CONFIRM",
            color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp, maxLines = 1
        )
    }
}
