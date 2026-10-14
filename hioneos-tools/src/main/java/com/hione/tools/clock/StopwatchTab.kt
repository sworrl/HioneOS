package com.hione.tools.clock

import com.hione.tools.Mascot
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.tools.ui.GlassButton
import com.hione.tools.ui.Hione
import com.hione.tools.ui.HioneMono
import com.hione.tools.ui.HioneTopBar
import com.hione.tools.ui.glass
import kotlinx.coroutines.delay

@Composable
fun StopwatchTab() {
    val ctx = LocalContext.current
    val rev = rememberClockRevision()
    val sw = remember(rev) { ClockStore.stopwatch(ctx) }
    var nowE by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    // ~30 fps while running is plenty for hundredths that nobody can read anyway, and keeps
    // this cheap on the M500's GPU. Nothing ticks while stopped.
    LaunchedEffect(sw.running) {
        while (sw.running) { withFrameMillis { }; nowE = SystemClock.elapsedRealtime(); delay(30) }
    }
    val total = sw.totalNow(nowE)

    fun save(s: Stopwatch) { ClockStore.saveStopwatch(ctx, s); StopwatchNotifier.sync(ctx) }

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        HioneTopBar("Stopwatch")
        Box(Modifier.fillMaxWidth().height(250.dp), contentAlignment = Alignment.Center) {
            val secFrac = (total % 60_000) / 60_000f
            Canvas(Modifier.size(236.dp)) {
                val st = 6.dp.toPx()
                drawArc(Color(0x22FFFFFF), 0f, 360f, false, style = Stroke(st))
                drawArc(Hione.Teal, -90f, 360f * secFrac, false, style = Stroke(st, cap = StrokeCap.Round))
            }
            Box(Modifier.size(200.dp).glass(RoundedCornerShape(100.dp)), contentAlignment = Alignment.Center) {
                Text(ClockFormat.duration(total, showHundredths = true), color = Hione.Text,
                    fontSize = if (total >= 3_600_000) 26.sp else 34.sp, fontFamily = HioneMono, fontWeight = FontWeight.Light)
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Left: Lap while running, Reset while paused.
            GlassButton(
                onClick = {
                    if (sw.running) save(sw.copy(laps = sw.laps + sw.totalNow()))
                    else save(Stopwatch(false, 0, 0, emptyList()))
                },
                modifier = Modifier.weight(1f), minHeight = 64.dp, enabled = sw.running || total > 0
            ) {
                Icon(if (sw.running) Icons.Outlined.Flag else Icons.Outlined.Replay, null, tint = Hione.TealGlow)
                Spacer(Modifier.width(8.dp))
                Text(if (sw.running) "Lap" else "Reset", color = Hione.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
            GlassButton(
                onClick = {
                    val n = SystemClock.elapsedRealtime()
                    if (sw.running) save(sw.copy(running = false, accumulatedMs = sw.totalNow(n)))
                    else save(sw.copy(running = true, startElapsed = n))
                },
                modifier = Modifier.weight(1f), minHeight = 64.dp, filled = true, accent = if (sw.running) Hione.PinkSoft else Hione.Teal
            ) {
                Icon(if (sw.running) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = Mascot.color(0xFF041513))
                Spacer(Modifier.width(8.dp))
                Text(if (sw.running) "Pause" else if (total > 0) "Resume" else "Start", color = Mascot.color(0xFF041513), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        if (sw.laps.isNotEmpty()) {
            val splits = sw.laps.mapIndexed { i, t -> t - (if (i == 0) 0 else sw.laps[i - 1]) }
            val best = if (splits.size > 1) splits.min() else -1
            val worst = if (splits.size > 1) splits.max() else -1
            LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 8.dp)) {
                itemsIndexed(sw.laps.reversed()) { ri, totalAt ->
                    val i = sw.laps.size - 1 - ri
                    val split = splits[i]
                    val color = when (split) { best -> Hione.TealGlow; worst -> Hione.PinkSoft; else -> Hione.Text }
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp).glass(RoundedCornerShape(14.dp), rimAlpha = 0.2f).padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Lap ${i + 1}", color = Hione.Muted, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Text(ClockFormat.duration(split, true), color = color, fontSize = 17.sp, fontFamily = HioneMono, modifier = Modifier.weight(1f))
                        Text(ClockFormat.duration(totalAt, true), color = Hione.Muted, fontSize = 15.sp, fontFamily = HioneMono)
                    }
                }
            }
        }
    }
}
