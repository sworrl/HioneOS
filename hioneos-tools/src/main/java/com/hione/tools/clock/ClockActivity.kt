package com.hione.tools.clock

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.tools.ui.GlassDialog
import com.hione.tools.ui.GlassSegmented
import com.hione.tools.ui.GlassTabBar
import com.hione.tools.ui.GlassTextButton
import com.hione.tools.ui.Hione
import com.hione.tools.ui.HioneBackground
import com.hione.tools.ui.HioneTheme
import com.hione.tools.ui.SectionLabel
import com.hione.tools.ui.TabItem

class ClockActivity : ComponentActivity() {

    /** Requests that arrive while the activity is already open (singleTask) land here. */
    private val tabRequest = mutableIntStateOf(-1)
    private val alarmRequest = mutableIntStateOf(-1)
    private val newAlarmRequest = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        readIntent(intent)
        val startTab = if (tabRequest.intValue >= 0) tabRequest.intValue else ClockStore.prefs(this).getInt("lastTab", TAB_ALARMS)
        tabRequest.intValue = -1
        askNotificationPermission()
        setContent {
            HioneTheme {
                ClockRoot(
                    startTab = startTab,
                    tabRequest = tabRequest.intValue,
                    onTabRequestHandled = { tabRequest.intValue = -1 },
                    alarmRequest = alarmRequest.intValue,
                    onAlarmRequestHandled = { alarmRequest.intValue = -1 },
                    newAlarmRequest = newAlarmRequest.value,
                    onNewAlarmHandled = { newAlarmRequest.value = false },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Cheap and idempotent: catches anything that drifted while we were away (time change
        // during direct boot, permission granted in Settings, and so on).
        Thread { runCatching { AlarmScheduler.sync(applicationContext); TimerScheduler.sync(applicationContext) } }.start()
    }

    private fun readIntent(i: Intent?) {
        i ?: return
        if (i.hasExtra(EXTRA_TAB)) tabRequest.intValue = i.getIntExtra(EXTRA_TAB, TAB_ALARMS)
        if (i.hasExtra(EXTRA_ALARM_ID)) alarmRequest.intValue = i.getIntExtra(EXTRA_ALARM_ID, -1)
        if (i.getBooleanExtra(EXTRA_NEW_ALARM, false)) newAlarmRequest.value = true
        // Consume one-shot extras so a configuration change does not replay them.
        i.removeExtra(EXTRA_ALARM_ID); i.removeExtra(EXTRA_NEW_ALARM)
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    companion object {
        const val EXTRA_TAB = "com.hione.tools.clock.TAB"
        const val EXTRA_ALARM_ID = "com.hione.tools.clock.ALARM_ID"
        const val EXTRA_NEW_ALARM = "com.hione.tools.clock.NEW_ALARM"
        const val TAB_ALARMS = 0
        const val TAB_CLOCK = 1
        const val TAB_TIMER = 2
        const val TAB_STOPWATCH = 3
    }
}

@Composable
private fun ClockRoot(
    startTab: Int,
    tabRequest: Int,
    onTabRequestHandled: () -> Unit,
    alarmRequest: Int,
    onAlarmRequestHandled: () -> Unit,
    newAlarmRequest: Boolean,
    onNewAlarmHandled: () -> Unit,
) {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(startTab) }
    LaunchedEffect(tabRequest) { if (tabRequest >= 0) { tab = tabRequest; onTabRequestHandled() } }
    val setTab = { t: Int -> tab = t; ClockStore.prefs(ctx).edit().putInt("lastTab", t).apply() }
    var showSettings by remember { mutableStateOf(false) }

    HioneBackground {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Crossfade(tab, animationSpec = tween(160), label = "tab") { t ->
                    when (t) {
                        ClockActivity.TAB_ALARMS -> AlarmsTab(
                            openAlarmId = alarmRequest, onOpenHandled = onAlarmRequestHandled,
                            createNew = newAlarmRequest, onCreateHandled = onNewAlarmHandled,
                            onSettings = { showSettings = true }
                        )
                        ClockActivity.TAB_CLOCK -> WorldClockTab(onSettings = { showSettings = true })
                        ClockActivity.TAB_TIMER -> TimerTab()
                        else -> StopwatchTab()
                    }
                }
            }
            GlassTabBar(
                listOf(
                    TabItem("Alarms", Icons.Outlined.Alarm),
                    TabItem("Clock", Icons.Outlined.Public),
                    TabItem("Timer", Icons.Outlined.HourglassEmpty),
                    TabItem("Stopwatch", Icons.Outlined.Timer),
                ),
                tab, setTab
            )
        }
    }
    if (showSettings) ClockSettingsDialog { showSettings = false }
}

@Composable
private fun ClockSettingsDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var silence by remember { mutableIntStateOf(ClockStore.autoSilenceMinutes(ctx)) }
    var volKeys by remember { mutableStateOf(ClockStore.volumeKeyAction(ctx)) }
    var timerTone by remember { mutableStateOf(ClockStore.timerRingtone(ctx)) }
    var pickTone by remember { mutableStateOf(false) }

    GlassDialog(onDismiss, title = "Clock settings", buttons = { GlassTextButton("Done", onDismiss, filled = true) }) {
        SectionLabel("Stop ringing after")
        val silenceOpts = listOf(5, 10, 15, 30, 0)
        GlassSegmented(silenceOpts.map { if (it == 0) "Never" else "$it m" }, silenceOpts.indexOf(silence).coerceAtLeast(0), {
            silence = silenceOpts[it]; ClockStore.setAutoSilenceMinutes(ctx, silence)
        }, Modifier.fillMaxWidth())

        SectionLabel("Volume buttons while ringing")
        val volOpts = listOf("snooze", "dismiss", "none")
        GlassSegmented(listOf("Snooze", "Dismiss", "Volume"), volOpts.indexOf(volKeys).coerceAtLeast(0), {
            volKeys = volOpts[it]; ClockStore.setVolumeKeyAction(ctx, volKeys)
        }, Modifier.fillMaxWidth())

        SectionLabel("Timer sound")
        GlassTextButton(ringtoneTitle(ctx, timerTone), { pickTone = true }, Modifier.fillMaxWidth())

        if (!AlarmScheduler.canScheduleExact(ctx)) {
            SectionLabel("Permission", color = Hione.PinkSoft)
            Text("Alarms may ring late until exact alarms are allowed for Clock.", color = Hione.TextDim, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            GlassTextButton("Open settings", {
                runCatching {
                    ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + ctx.packageName)))
                }
            }, Modifier.fillMaxWidth(), accent = Hione.PinkSoft)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.Start) {
            Text("Side buttons while ringing: play/pause snoozes, next or previous dismisses.", color = Hione.Muted, fontSize = 13.sp)
        }
    }
    if (pickTone) RingtonePickerDialog(timerTone, onPick = { timerTone = it; ClockStore.setTimerRingtone(ctx, it) }, onDismiss = { pickTone = false })
}
