package com.hione.tools.clock

import com.hione.tools.Mascot
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.HourglassBottom
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.tools.ui.GlassButton
import com.hione.tools.ui.GlassTextButton
import com.hione.tools.ui.Hione
import com.hione.tools.ui.HioneBackground
import com.hione.tools.ui.HioneMono
import com.hione.tools.ui.HioneTheme
import kotlinx.coroutines.delay
import java.util.Calendar

/**
 * Full-screen ring UI, shown over the lock screen by the ring notification's full-screen intent.
 * Two big buttons, nothing else to hit by accident with a sleepy thumb.
 *
 * Hardware keys, matching Hione Music's alarm so the M500's side buttons behave the same for
 * either app: play/pause snoozes, next/previous dismisses; volume keys follow the Clock setting.
 */
class RingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Back must not dismiss an alarm; it just leaves it ringing in the notification.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { moveTaskToBack(true) }
        })
        setContent { HioneTheme { RingScreen(onEmpty = { finish() }) } }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val snap = RingService.state.value
        if (snap.isEmpty) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE -> {
                when (ClockStore.volumeKeyAction(this)) {
                    "snooze" -> snoozeOrStop(snap)
                    "dismiss" -> dismissOrStop(snap)
                    else -> return super.onKeyDown(keyCode, event)
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                snoozeOrStop(snap); return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { dismissOrStop(snap); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun snoozeOrStop(s: RingSnapshot) {
        val a = s.alarms.firstOrNull()
        if (a != null) RingService.send(this, RingService.ACTION_SNOOZE, a.id)
        else s.timers.firstOrNull()?.let { RingService.send(this, RingService.ACTION_TIMER_STOP, it.id) }
    }

    private fun dismissOrStop(s: RingSnapshot) {
        val a = s.alarms.firstOrNull()
        if (a != null) RingService.send(this, RingService.ACTION_DISMISS, a.id)
        else s.timers.firstOrNull()?.let { RingService.send(this, RingService.ACTION_TIMER_STOP, it.id) }
    }
}

@Composable
private fun RingScreen(onEmpty: () -> Unit) {
    val ctx = LocalContext.current
    val snap by RingService.state.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(250) } }
    LaunchedEffect(snap.isEmpty) {
        // Give the service a beat to publish its first state before deciding there is nothing.
        if (snap.isEmpty) { delay(1500); if (RingService.state.value.isEmpty) onEmpty() }
    }

    val pulse = rememberInfiniteTransition(label = "pulse")
    val glow by pulse.animateFloat(0.25f, 0.85f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Reverse), label = "glow")

    val alarm = snap.alarms.firstOrNull()
    val timer = if (alarm == null) snap.timers.firstOrNull() else null
    val others = snap.alarms.size + snap.timers.size - 1

    HioneBackground {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.weight(0.6f))
            Box(Modifier.size(250.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val r = size.minDimension / 2
                    drawCircle(
                        Brush.radialGradient(listOf(Hione.Teal.copy(alpha = glow * 0.35f), Color.Transparent), center = center, radius = r),
                        radius = r
                    )
                    drawCircle(Hione.TealGlow.copy(alpha = glow), radius = r * 0.86f, style = Stroke(width = 3.dp.toPx()))
                    drawCircle(Hione.Pink.copy(alpha = 0.5f * glow), radius = r * 0.93f, style = Stroke(width = 1.dp.toPx()),
                        center = Offset(center.x, center.y))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(if (timer != null) Icons.Outlined.HourglassBottom else Icons.Outlined.Alarm, null, tint = Hione.TealGlow, modifier = Modifier.size(34.dp))
                    val c = Calendar.getInstance().apply { timeInMillis = now }
                    Text(
                        ClockFormat.time(ctx, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)),
                        color = Hione.Text, fontSize = 64.sp, fontFamily = HioneMono, fontWeight = FontWeight.Light
                    )
                    val ampm = ClockFormat.amPm(ctx, c.get(Calendar.HOUR_OF_DAY))
                    if (ampm.isNotEmpty()) Text(ampm, color = Hione.Muted, fontSize = 18.sp)
                }
            }
            Spacer(Modifier.height(18.dp))
            when {
                alarm != null -> {
                    Text(alarm.label.ifBlank { "Alarm" }, color = Hione.Text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    if (snap.music) Text("Playing from ${Mascot.musicAppName}", color = Hione.PinkSoft, fontSize = 15.sp)
                }
                timer != null -> {
                    Text("Time's up", color = Hione.Text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                    if (timer.label.isNotBlank()) Text(timer.label, color = Hione.TextDim, fontSize = 18.sp)
                    // Re-read every tick: `now` changing is what recomposes this.
                    val over = if (now > 0) SystemClock.elapsedRealtime() - timer.endElapsed else 0L
                    Text(ClockFormat.duration(-over.coerceAtLeast(0)), color = Hione.PinkSoft, fontSize = 30.sp, fontFamily = HioneMono)
                }
            }
            if (others > 0) {
                Spacer(Modifier.height(6.dp))
                Text(if (others == 1) "1 more ringing" else "$others more ringing", color = Hione.Muted, fontSize = 14.sp)
            }
            Spacer(Modifier.weight(1f))

            if (alarm != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    GlassButton(
                        onClick = { RingService.send(ctx, RingService.ACTION_SNOOZE, alarm.id) },
                        modifier = Modifier.weight(1f), minHeight = 96.dp, shape = RoundedCornerShape(30.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Snooze", color = Hione.Text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                            Text("${alarm.snoozeMinutes} min", color = Hione.Muted, fontSize = 14.sp)
                        }
                    }
                    GlassButton(
                        onClick = { RingService.send(ctx, RingService.ACTION_DISMISS, alarm.id) },
                        modifier = Modifier.weight(1f), minHeight = 96.dp, filled = true, accent = Hione.PinkSoft, shape = RoundedCornerShape(30.dp)
                    ) {
                        Text("Dismiss", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (snap.music) {
                    Spacer(Modifier.height(12.dp))
                    GlassTextButton("Dismiss and keep the music playing", {
                        RingService.send(ctx, RingService.ACTION_DISMISS, alarm.id, keepMusic = true)
                    }, Modifier.fillMaxWidth())
                }
            } else if (timer != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    GlassButton(
                        onClick = { RingService.send(ctx, RingService.ACTION_TIMER_ADD_MINUTE, timer.id) },
                        modifier = Modifier.weight(1f), minHeight = 96.dp, shape = RoundedCornerShape(30.dp)
                    ) { Text("+1 min", color = Hione.Text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold) }
                    GlassButton(
                        onClick = { RingService.send(ctx, RingService.ACTION_TIMER_STOP, timer.id) },
                        modifier = Modifier.weight(1f), minHeight = 96.dp, filled = true, accent = Hione.PinkSoft, shape = RoundedCornerShape(30.dp)
                    ) { Text("Stop", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
