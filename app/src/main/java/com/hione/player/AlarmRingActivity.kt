package com.hione.player

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Full-screen alarm-firing UI — launched by [[AlarmRingService]]'s full-screen-intent
 * notification (the real "device was asleep/locked" path, USE_FULL_SCREEN_INTENT) or its direct
 * start, or by tapping the ringing notification. `setShowWhenLocked`/`setTurnScreenOn` are the
 * API 27+ replacements for the old WindowManager flags (both are applied: the manifest carries
 * android:showWhenLocked/turnScreenOn so the FIRST frame already has them, and the runtime calls
 * cover a re-delivered intent) — the same mechanism real alarm-clock and incoming-call apps use
 * to draw over the lockscreen, including the HioneOS launcher lockscreen, which sits below any
 * showWhenLocked window. FLAG_KEEP_SCREEN_ON keeps the panel lit for as long as this is showing.
 *
 * Physical buttons here match every other key path (see [[AlarmRingService.interceptMediaKey]]):
 * play/pause = snooze, next/prev = dismiss.
 */
class AlarmRingActivity : ComponentActivity() {
    private var alarmId by mutableIntStateOf(-1)

    private val stopListener: (Int) -> Unit = { _ ->
        // Whatever stopped the ring (notification action, physical key via the media-button
        // receiver, auto-dismiss), this screen has nothing left to show once nothing is ringing.
        if (!AlarmRingService.isRinging()) finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        alarmId = intent.getIntExtra(AlarmReceiver.EXTRA_ALARM_ID, -1)

        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        AlarmRingService.addStopListener(stopListener)
        // Opened after the ring already ended (stale notification tap / late FSI delivery): don't
        // show Snooze/Dismiss for an alarm that isn't ringing — that would be fake UI.
        if (!AlarmRingService.isRinging()) { finish(); return }

        val player = PlayerHolder.ensure(this)
        setContent {
            val alarm = remember(alarmId) { AlarmPreferences.loadAlarms(this).firstOrNull { it.id == alarmId } }
            AlarmRingScreen(
                label = (alarm?.label ?: AlarmRingService.ringingLabel ?: "Alarm").ifBlank { "Alarm" },
                snoozeMinutes = alarm?.snoozeMinutes ?: 9,
                player = player,
                onSnooze = { AlarmRingService.send(this, AlarmRingService.ACTION_SNOOZE_ALL); finish() },
                onDismiss = { AlarmRingService.send(this, AlarmRingService.ACTION_DISMISS_ALL); finish() }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        alarmId = intent.getIntExtra(AlarmReceiver.EXTRA_ALARM_ID, alarmId)
    }

    // M500 physical button integration — the ONE mapping shared with every other key path:
    // play/pause (headset hook too) = snooze, next/prev (FF/REW) = dismiss. Volume keys fall
    // through so the user can still turn the alarm up/down while it rings.
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event != null && event.repeatCount > 0 && AlarmRingService.isAlarmKey(keyCode)) return true // held key: act once, on the first down
        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                Haptics.tick(this); AlarmRingService.send(this, AlarmRingService.ACTION_SNOOZE_ALL); finish(); true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_STOP -> {
                Haptics.tick(this); AlarmRingService.send(this, AlarmRingService.ACTION_DISMISS_ALL); finish(); true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    // Swallow the matching key-up so it can't leak into MainActivity/MediaSession as a transport
    // command after this screen finishes.
    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_STOP -> true
        else -> super.onKeyUp(keyCode, event)
    }

    override fun onDestroy() {
        AlarmRingService.removeStopListener(stopListener)
        super.onDestroy()
    }
}

@Composable
private fun AlarmRingScreen(
    label: String,
    snoozeMinutes: Int,
    player: androidx.media3.exoplayer.ExoPlayer,
    onSnooze: () -> Unit,
    onDismiss: () -> Unit
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // Same fullscreen-viz-as-backdrop treatment as the regular Now Playing screen — the alarm's
    // own fade-in feeds real audio into the shared player, so the visualizer has something to
    // react to from the first frame, not just a static preset sitting idle at zero volume.
    val preset = remember { ProjectMPreset.entries.getOrElse(PlayerPreferences.loadProjectMPreset(ctx)) { ProjectMPreset.CYBER_TUNNEL } }
    var clock by remember { mutableStateOf("") }
    var nowPlaying by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            val c = java.util.Calendar.getInstance()
            clock = String.format("%02d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
            nowPlaying = runCatching {
                val m = player.mediaMetadata
                listOfNotNull(m.title?.toString(), m.artist?.toString()).filter { it.isNotBlank() }.joinToString(" — ").ifBlank { null }
            }.getOrNull()
            delay(1000L)
        }
    }

    Box(Modifier.fillMaxSize().background(Ground), contentAlignment = Alignment.Center) {
        ProjectMVisualizerView(
            sessionId = player.audioSessionId,
            preset = preset,
            modifier = Modifier.fillMaxSize()
        )
        // Scrim behind the label/buttons so they stay legible over whatever the preset is doing.
        Box(Modifier.fillMaxSize().background(Color(0x80000000)))

        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 24.dp)) {
            Icon(Icons.Default.Alarm, "Alarm", tint = HionePink, modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(10.dp))
            Text(clock, color = Color.White, fontSize = 52.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
            Spacer(Modifier.height(4.dp))
            Text(label, color = HioneTealBright, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(nowPlaying?.let { "$it" } ?: "${Mascot.musicAppName} Alarm", color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center, maxLines = 2)

            Spacer(Modifier.height(44.dp))

            Row(
                Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Surface1)
                    .clickable(onClick = onSnooze)
                    .padding(horizontal = 32.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Snooze, "Snooze", tint = HioneTealBright, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text("Snooze $snoozeMinutes min", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(16.dp))

            Row(
                Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(HionePink)
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 32.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Close, "Dismiss", tint = Color.White, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text("Dismiss", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(28.dp))
            Text("Side buttons: Play/Pause snoozes · Next or Prev dismisses", color = Muted, fontSize = 11.sp, textAlign = TextAlign.Center)
        }
    }
}
