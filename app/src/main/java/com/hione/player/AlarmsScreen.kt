package com.hione.player

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Settings → Alarms. Everything shown here about "armed" state comes from AlarmManager itself
 * (AlarmScheduler.systemArmedNext → getNextAlarmClock), never from our own bookkeeping alone, so
 * the screen can't claim an alarm is set when the OS disagrees. Every write goes through
 * AlarmPreferences' atomic helpers and is followed by AlarmScheduler.rescheduleAll(), and the
 * screen observes the prefs file so a one-shot alarm auto-disabling itself in AlarmRingService
 * shows up live while this is open.
 */

private val ALARM_DAYS = listOf(1 to "Su", 2 to "Mo", 3 to "Tu", 4 to "We", 5 to "Th", 6 to "Fr", 7 to "Sa")
private val WEEKDAYS = setOf(2, 3, 4, 5, 6)
private val WEEKEND = setOf(1, 7)
private val EVERY_DAY = (1..7).toSet()
private val AlarmWarn = Color(0xFFFF6B6B)
private val RowBg = Color.White.copy(alpha = 0.05f)

private fun alarmTimeText(a: Alarm): String = when (a.mode) {
    AlarmTriggerMode.CLOCK_TIME -> String.format("%02d:%02d", a.hour, a.minute)
    AlarmTriggerMode.SUNRISE -> "Sunrise"
    AlarmTriggerMode.SUNSET -> "Sunset"
    AlarmTriggerMode.SUNRISE_OR_SUNSET -> "Sun ↑↓"
}

private fun alarmDaysText(days: Set<Int>): String = when {
    days.isEmpty() -> "Once"
    days == EVERY_DAY -> "Every day"
    days == WEEKDAYS -> "Weekdays"
    days == WEEKEND -> "Weekends"
    else -> ALARM_DAYS.filter { it.first in days }.joinToString(" ") { it.second }
}

private fun canUseFullScreenIntent(ctx: Context): Boolean =
    if (Build.VERSION.SDK_INT >= 34) runCatching {
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).canUseFullScreenIntent()
    }.getOrDefault(true) else true

/** Settings' Alarms section: system-truth "next alarm" header, permission banners, the alarm
 *  list (toggle / edit / delete), add button, options, and the add/edit dialog. */
@Composable
fun AlarmsSettingsContent(ctx: Context, tracks: List<Track>) {
    val libraryTracks = remember(tracks) { tracks.ifEmpty { runCatching { FastLibraryStore.loadSync(ctx) }.getOrNull().orEmpty() } }
    var alarms by remember { mutableStateOf(AlarmPreferences.loadAlarms(ctx)) }
    var snoozeId by remember { mutableStateOf(AlarmPreferences.loadActiveSnooze(ctx)) }
    var snoozeUntil by remember { mutableLongStateOf(AlarmPreferences.loadSnoozeUntil(ctx)) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var systemNext by remember { mutableStateOf(AlarmScheduler.systemArmedNext(ctx)) }
    var exactOk by remember { mutableStateOf(AlarmScheduler.canScheduleExact(ctx)) }
    var fsiOk by remember { mutableStateOf(canUseFullScreenIntent(ctx)) }
    var upcomingNotif by remember { mutableStateOf(AlarmPreferences.loadUpcomingNotificationEnabled(ctx)) }
    var editing by remember { mutableStateOf<Alarm?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var confirmDeleteId by remember { mutableStateOf<Int?>(null) }

    fun refresh() {
        alarms = AlarmPreferences.loadAlarms(ctx)
        snoozeId = AlarmPreferences.loadActiveSnooze(ctx)
        snoozeUntil = AlarmPreferences.loadSnoozeUntil(ctx)
        now = System.currentTimeMillis()
        systemNext = AlarmScheduler.systemArmedNext(ctx)
        exactOk = AlarmScheduler.canScheduleExact(ctx)
        fsiOk = canUseFullScreenIntent(ctx)
    }
    fun rearm() { AlarmScheduler.rescheduleAll(ctx); refresh() }

    DisposableEffect(ctx) {
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }
        AlarmPreferences.registerListener(ctx, l)
        onDispose { AlarmPreferences.unregisterListener(ctx, l) }
    }
    // Countdown tick; also re-checks the two system permissions after the user comes back from
    // the Settings pages the banners open.
    LaunchedEffect(Unit) { while (true) { delay(10_000L); refresh() } }

    val soonest = remember(alarms, now, snoozeId, snoozeUntil) { AlarmScheduler.soonest(ctx, alarms) }
    val anyEnabled = alarms.any { it.enabled }

    // ---- Status card ----------------------------------------------------------------------
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Surface1).padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Alarm, null, tint = HioneTealBright, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("NEXT ALARM", color = HioneTealBright, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
        Spacer(Modifier.height(8.dp))
        when {
            !anyEnabled -> Text("Nothing armed. Add an alarm or switch one on.", color = Muted, fontSize = 12.5.sp)
            soonest == null -> Text("Enabled alarms are sunrise/sunset-based and still waiting for a coarse location fix.", color = HioneGold, fontSize = 12.sp, lineHeight = 16.sp)
            systemNext != null && abs(systemNext!! - soonest.second) < 60_000L -> {
                val (a, at) = soonest
                Text(AlarmScheduler.formatWhen(at, now), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
                Text(
                    "${AlarmScheduler.formatCountdown(at, now)} · ${a.label.ifBlank { "Alarm" }}" + if (snoozeId == a.id) " · snoozed" else "",
                    color = HioneTealBright, fontSize = 12.5.sp
                )
                Spacer(Modifier.height(4.dp))
                Text("Armed in AlarmManager as an alarm-clock (exact, Doze-exempt, wakes the device).", color = Muted, fontSize = 10.5.sp, lineHeight = 14.sp)
            }
            else -> Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(AlarmWarn.copy(alpha = 0.15f)).clickable { rearm() }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Warning, null, tint = AlarmWarn, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    "Expected ${AlarmScheduler.formatWhen(soonest.second, now)} but the system has NO alarm armed for ${Mascot.musicAppName}. Tap to re-arm.",
                    color = AlarmWarn, fontSize = 11.5.sp, lineHeight = 15.sp
                )
            }
        }

        if (!exactOk) {
            Spacer(Modifier.height(10.dp))
            AlarmBanner("Exact alarms aren't allowed, so alarms can't be set. Tap to fix in system Settings.") {
                runCatching {
                    ctx.startActivity(
                        Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                            .setData(android.net.Uri.parse("package:${ctx.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        }
        if (!fsiOk) {
            Spacer(Modifier.height(10.dp))
            AlarmBanner("Full-screen alarm permission is off, so a ringing alarm only shows as a small notification on a locked screen. Tap to fix.") {
                runCatching {
                    val action = if (Build.VERSION.SDK_INT >= 34) android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT else android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                    ctx.startActivity(Intent(action).setData(android.net.Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }
    }

    Spacer(Modifier.height(12.dp))

    // ---- Alarm list -----------------------------------------------------------------------
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Surface1).padding(16.dp)
    ) {
        if (alarms.isEmpty()) {
            Text("No alarms yet. Wake up to any track, album, playlist, your Liked Songs, a Daily Mix, or the built-in ${Mascot.name} Chime. Alarms can fade in, snooze, and follow sunrise or sunset.", color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
            Spacer(Modifier.height(12.dp))
        } else {
            alarms.sortedWith(compareBy({ it.mode != AlarmTriggerMode.CLOCK_TIME }, { it.hour * 60 + it.minute })).forEachIndexed { idx, a ->
                if (idx > 0) Spacer(Modifier.height(8.dp))
                val next = remember(a, now, snoozeId, snoozeUntil) { AlarmScheduler.effectiveNextMillis(ctx, a) }
                AlarmRow(
                    alarm = a,
                    nextMillis = next,
                    now = now,
                    snoozed = snoozeId == a.id && snoozeUntil > now,
                    sourceText = AlarmLibrary.describeSource(a.source, a.sourceRef, libraryTracks),
                    confirmingDelete = confirmDeleteId == a.id,
                    onClick = { editing = a; showEditor = true },
                    onToggle = { on ->
                        AlarmPreferences.updateAlarm(ctx, a.id) { it.copy(enabled = on) }
                        rearm()
                    },
                    onDelete = {
                        if (confirmDeleteId == a.id) {
                            AlarmScheduler.cancel(ctx, a.id)
                            AlarmPreferences.removeAlarm(ctx, a.id)
                            confirmDeleteId = null
                            rearm()
                        } else confirmDeleteId = a.id
                    }
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(HioneTealBright.copy(alpha = 0.15f))
                .clickable { editing = null; showEditor = true }.padding(vertical = 11.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Text("+ Add alarm", color = HioneTealBright, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }

    Spacer(Modifier.height(12.dp))

    // ---- Options --------------------------------------------------------------------------
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Surface1).padding(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Upcoming-alarm notification", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("Quiet, low-priority reminder only while an alarm is due within 24 h", color = Muted, fontSize = 11.sp, lineHeight = 14.sp)
            }
            Spacer(Modifier.width(12.dp))
            AlarmSwitch(checked = upcomingNotif) { on ->
                upcomingNotif = on
                AlarmPreferences.saveUpcomingNotificationEnabled(ctx, on)
                runCatching { AlarmUpcomingNotifier.refresh(ctx) }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("While an alarm rings, the side buttons work even with the screen off: Play/Pause snoozes, Next or Prev dismisses. Audio goes through the normal bit-perfect player path; the volume cap only scales the final gain.", color = Muted, fontSize = 10.5.sp, lineHeight = 14.sp)
    }

    if (showEditor) {
        AlarmEditorDialog(
            ctx = ctx,
            tracks = libraryTracks,
            initial = editing,
            onDismissRequest = { showEditor = false },
            onSave = { a ->
                AlarmPreferences.upsertAlarm(ctx, a)
                showEditor = false
                rearm()
            },
            onPreview = { a ->
                runCatching {
                    ContextCompat.startForegroundService(
                        ctx,
                        Intent(ctx, AlarmRingService::class.java)
                            .setAction(AlarmRingService.ACTION_PREVIEW)
                            .putExtra(AlarmRingService.EXTRA_ALARM_JSON, a.toJson().toString())
                    )
                }
            }
        )
    }
}

@Composable
private fun AlarmBanner(text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(AlarmWarn.copy(alpha = 0.15f)).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Warning, "Warning", tint = AlarmWarn, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, color = AlarmWarn, fontSize = 11.5.sp, lineHeight = 15.sp)
    }
}

@Composable
private fun AlarmRow(
    alarm: Alarm,
    nextMillis: Long?,
    now: Long,
    snoozed: Boolean,
    sourceText: String,
    confirmingDelete: Boolean,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    val primary = if (alarm.enabled) Color.White else Muted
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(RowBg).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(alarmTimeText(alarm), color = primary, fontSize = 22.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
                Spacer(Modifier.width(10.dp))
                Text(alarm.label.ifBlank { "Alarm" }, color = primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 3.dp), maxLines = 1)
            }
            Text("${alarmDaysText(alarm.repeatDays)} · $sourceText", color = Muted, fontSize = 11.sp, maxLines = 1)
            val (nextText, nextColor) = when {
                !alarm.enabled -> "Off" to Muted
                snoozed && nextMillis != null -> "Snoozed · rings ${AlarmScheduler.formatWhen(nextMillis, now)} (${AlarmScheduler.formatCountdown(nextMillis, now)})" to HioneGold
                nextMillis != null -> "${AlarmScheduler.formatWhen(nextMillis, now)} · ${AlarmScheduler.formatCountdown(nextMillis, now)}" to HioneTealBright
                else -> "Waiting for a location fix" to HioneGold
            }
            Text(nextText, color = nextColor, fontSize = 11.sp, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        if (confirmingDelete) {
            Text(
                "Delete?", color = AlarmWarn, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(AlarmWarn.copy(alpha = 0.18f)).clickable(onClick = onDelete).padding(horizontal = 8.dp, vertical = 5.dp)
            )
        } else {
            Icon(Icons.Default.Close, "Delete", tint = Muted, modifier = Modifier.size(18.dp).clickable(onClick = onDelete))
        }
        Spacer(Modifier.width(12.dp))
        AlarmSwitch(checked = alarm.enabled, onCheckedChange = onToggle)
    }
}

/** Teal pill switch in the app's own style (no Material ripple/track colors to fight). */
@Composable
private fun AlarmSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val track by animateColorAsState(if (checked) HioneTeal else Color.White.copy(alpha = 0.14f), label = "track")
    val thumbX by animateDpAsState(if (checked) 20.dp else 2.dp, label = "thumb")
    Box(
        Modifier.size(width = 42.dp, height = 24.dp).clip(RoundedCornerShape(12.dp)).background(track)
            .clickable { onCheckedChange(!checked) }
    ) {
        Box(
            Modifier.padding(start = thumbX, top = 2.dp).size(20.dp).clip(CircleShape)
                .background(if (checked) Color.White else Muted)
        )
    }
}

@Composable
private fun AlarmPill(text: String, selected: Boolean, accent: Color = HioneTealBright, onClick: () -> Unit) {
    Text(
        text,
        color = if (selected) Color.White else Muted,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) accent.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.06f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    )
}

@Composable
private fun AlarmSectionLabel(text: String) {
    Text(text, color = HioneTealBright, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(6.dp))
}

/** Press-and-hold auto-repeat button (fires once on press, then every 80 ms after 400 ms). */
@Composable
private fun AlarmRepeatButton(text: String, modifier: Modifier = Modifier, onStep: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    LaunchedEffect(pressed) {
        if (pressed) {
            onStep()
            delay(400L)
            while (true) { onStep(); delay(80L) }
        }
    }
    Box(
        modifier.clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.06f))
            .clickable(interactionSource = src, indication = null, onClick = {}),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = HioneTealBright, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AlarmStepper(value: Int, min: Int, max: Int, step: Int = 1, suffix: String = "", onChange: (Int) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.06f)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AlarmRepeatButton("−", Modifier.size(width = 40.dp, height = 36.dp)) { onChange((value - step).coerceAtLeast(min)) }
        Text("$value$suffix", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.widthIn(min = 44.dp), textAlign = TextAlign.Center)
        AlarmRepeatButton("+", Modifier.size(width = 40.dp, height = 36.dp)) { onChange((value + step).coerceAtMost(max)) }
    }
}

/** 24-hour time picker: two big digit drums with hold-to-scroll arrows, plus quick presets.
 *  Intentionally 24-hour only — no AM/PM anywhere in the alarm editor, so there's no 12/24
 *  ambiguity to get wrong at 10:00 vs 22:00. */
@Composable
private fun AlarmTimePicker(hour: Int, minute: Int, onChange: (Int, Int) -> Unit) {
    @Composable
    fun Drum(value: Int, onDelta: (Int) -> Unit) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AlarmRepeatButton("▲", Modifier.size(width = 72.dp, height = 34.dp)) { onDelta(1) }
            Text(
                value.toString().padStart(2, '0'), color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Black,
                fontFamily = AudiowideFont, modifier = Modifier.padding(vertical = 2.dp)
            )
            AlarmRepeatButton("▼", Modifier.size(width = 72.dp, height = 34.dp)) { onDelta(-1) }
        }
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Drum(hour) { d -> onChange(((hour + d) % 24 + 24) % 24, minute) }
            Text(":", color = HioneTealBright, fontSize = 40.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 10.dp))
            Drum(minute) { d -> onChange(hour, ((minute + d) % 60 + 60) % 60) }
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(5 to 30, 6 to 0, 6 to 30, 7 to 0, 7 to 30, 8 to 0).forEach { (h, m) ->
                AlarmPill(String.format("%02d:%02d", h, m), hour == h && minute == m) { onChange(h, m) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AlarmPill(":00", minute == 0) { onChange(hour, 0) }
            AlarmPill(":15", minute == 15) { onChange(hour, 15) }
            AlarmPill(":30", minute == 30) { onChange(hour, 30) }
            AlarmPill(":45", minute == 45) { onChange(hour, 45) }
        }
    }
}

@Composable
private fun AlarmTextField(value: String, onValueChange: (String) -> Unit, placeholder: String, keyboard: KeyboardType = KeyboardType.Text) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
        cursorBrush = SolidColor(HioneTealBright),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.06f)).padding(horizontal = 12.dp, vertical = 10.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(placeholder, color = Muted, fontSize = 14.sp)
                inner()
            }
        }
    )
}

/** Searchable single-choice list used for artist / album / playlist / track picking. Lazy so a
 *  7k-track library is fine. */
@Composable
private fun AlarmPickList(options: List<Pair<String, String>>, selectedKey: String?, emptyText: String, onPick: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(options, query) {
        val q = query.trim()
        if (q.isEmpty()) options else options.filter { it.second.contains(q, ignoreCase = true) }
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.05f)).padding(6.dp)) {
        if (options.size > 8) {
            AlarmTextField(query, { query = it }, "Search…")
            Spacer(Modifier.height(4.dp))
        }
        if (filtered.isEmpty()) {
            Text(if (options.isEmpty()) emptyText else "No matches.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(8.dp))
        } else {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 200.dp)) {
                items(filtered, key = { it.first }) { (key, label) ->
                    val sel = key == selectedKey
                    Text(
                        label,
                        color = if (sel) HioneTealBright else Color.White,
                        fontSize = 12.5.sp,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(key) }.padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AlarmEditorDialog(
    ctx: Context,
    tracks: List<Track>,
    initial: Alarm?,
    onDismissRequest: () -> Unit,
    onSave: (Alarm) -> Unit,
    onPreview: (Alarm) -> Unit
) {
    var hour by remember { mutableStateOf(initial?.hour ?: 7) }
    var minute by remember { mutableStateOf(initial?.minute ?: 0) }
    var mode by remember { mutableStateOf(initial?.mode ?: AlarmTriggerMode.CLOCK_TIME) }
    var repeatDays by remember { mutableStateOf(initial?.repeatDays ?: emptySet()) }
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var fadeInSeconds by remember { mutableStateOf(initial?.fadeInSeconds ?: 30) }
    var snoozeMinutes by remember { mutableStateOf(initial?.snoozeMinutes ?: 9) }
    var source by remember { mutableStateOf(initial?.source ?: AlarmSource.HIONE_CHIME) }
    var sourceRef by remember { mutableStateOf(initial?.sourceRef) }
    var volumeCap by remember { mutableStateOf(initial?.volumeCap ?: 1f) }
    var vibrate by remember { mutableStateOf(initial?.vibrate ?: true) }
    var error by remember { mutableStateOf<String?>(null) }

    val artistNames = remember(tracks) { tracks.map { it.artist }.filter { it.isNotBlank() }.distinct().sorted() }
    val albumNames = remember(tracks) { tracks.map { it.album }.filter { it.isNotBlank() }.distinct().sorted() }
    val playlistNames = remember { runCatching { PlayerPreferences.loadPlaylists(ctx).keys.toList() }.getOrDefault(emptyList()) }
    val trackOptions = remember(tracks) { tracks.sortedBy { it.title.lowercase() }.map { it.id.toString() to "${it.title} · ${it.artist}" } }
    val needsRef = source == AlarmSource.ARTIST || source == AlarmSource.ALBUM || source == AlarmSource.PLAYLIST || source == AlarmSource.TRACK

    fun build(): Alarm? {
        if (needsRef && sourceRef.isNullOrBlank()) {
            error = when (source) {
                AlarmSource.ARTIST -> "Pick an artist."; AlarmSource.ALBUM -> "Pick an album."
                AlarmSource.PLAYLIST -> "Pick a playlist."; else -> "Pick a track."
            }
            return null
        }
        error = null
        return Alarm(
            id = initial?.id ?: AlarmPreferences.nextAlarmId(ctx),
            enabled = initial?.enabled ?: true,
            hour = hour, minute = minute, mode = mode, repeatDays = repeatDays,
            label = label.trim().ifBlank { "Alarm" }, fadeInSeconds = fadeInSeconds,
            snoozeMinutes = snoozeMinutes, source = source,
            sourceRef = if (needsRef) sourceRef else null,
            volumeCap = volumeCap.coerceIn(Alarm.MIN_VOLUME_CAP, 1f),
            vibrate = vibrate
        )
    }

    val previewNext = remember(hour, minute, mode, repeatDays) {
        AlarmScheduler.nextTriggerMillis(ctx, Alarm(id = -1, hour = hour, minute = minute, mode = mode, repeatDays = repeatDays))
    }

    // A real platform Dialog rather than an in-app Box overlay: this is invoked from inside
    // SettingsScreen's LazyColumn, which hands every item() an unbounded max height — a
    // Box(fillMaxSize()) there would inherit that infinity straight through to the scrolling
    // Column and Compose hard-crashes. A Dialog draws in its own window with bounded constraints.
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.92f)
                .clip(RoundedCornerShape(20.dp))
                .background(Surface1)
                .border(1.dp, HioneTeal.copy(alpha = 0.25f), RoundedCornerShape(20.dp))
                .padding(18.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(if (initial == null) "New Alarm" else "Edit Alarm", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
            Spacer(Modifier.height(14.dp))

            AlarmSectionLabel("Trigger")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    AlarmTriggerMode.CLOCK_TIME to "Time",
                    AlarmTriggerMode.SUNRISE to "Sunrise",
                    AlarmTriggerMode.SUNSET to "Sunset",
                    AlarmTriggerMode.SUNRISE_OR_SUNSET to "Both"
                ).forEach { (m, txt) -> AlarmPill(txt, mode == m) { mode = m } }
            }

            Spacer(Modifier.height(14.dp))
            if (mode == AlarmTriggerMode.CLOCK_TIME) {
                AlarmSectionLabel("Time (24h)")
                AlarmTimePicker(hour, minute) { h, m -> hour = h; minute = m }
            } else {
                Text(
                    "Uses a coarse last-known location (never an active GPS fix) to compute the next sunrise/sunset. If none is cached yet the alarm stays unarmed and says so in the list.",
                    color = Muted, fontSize = 10.5.sp, lineHeight = 14.sp
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                previewNext?.let { "First ring: ${AlarmScheduler.formatWhen(it)} · ${AlarmScheduler.formatCountdown(it)}" } ?: "First ring: needs a location fix",
                color = if (previewNext != null) HioneTealBright else HioneGold, fontSize = 11.5.sp
            )

            Spacer(Modifier.height(14.dp))
            AlarmSectionLabel("Repeat")
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                ALARM_DAYS.forEach { (d, txt) ->
                    AlarmPill(txt, d in repeatDays) { repeatDays = if (d in repeatDays) repeatDays - d else repeatDays + d }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AlarmPill("Once", repeatDays.isEmpty()) { repeatDays = emptySet() }
                AlarmPill("Weekdays", repeatDays == WEEKDAYS) { repeatDays = WEEKDAYS }
                AlarmPill("Weekends", repeatDays == WEEKEND) { repeatDays = WEEKEND }
                AlarmPill("Daily", repeatDays == EVERY_DAY) { repeatDays = EVERY_DAY }
            }

            Spacer(Modifier.height(14.dp))
            AlarmSectionLabel("Sound")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                fun pick(s: AlarmSource) { if (source != s) { source = s; sourceRef = null; error = null } }
                AlarmPill("${Mascot.name} Chime", source == AlarmSource.HIONE_CHIME, HionePink) { pick(AlarmSource.HIONE_CHIME) }
                AlarmPill("Shuffle library", source == AlarmSource.SHUFFLE_ALL) { pick(AlarmSource.SHUFFLE_ALL) }
                AlarmPill("Liked Songs", source == AlarmSource.LIKED_SONGS) { pick(AlarmSource.LIKED_SONGS) }
                AlarmPill("Daily Mix", source == AlarmSource.DAILY_MIX) { pick(AlarmSource.DAILY_MIX) }
                AlarmPill("Artist", source == AlarmSource.ARTIST) { pick(AlarmSource.ARTIST) }
                AlarmPill("Album", source == AlarmSource.ALBUM) { pick(AlarmSource.ALBUM) }
                AlarmPill("Playlist", source == AlarmSource.PLAYLIST) { pick(AlarmSource.PLAYLIST) }
                AlarmPill("Track", source == AlarmSource.TRACK) { pick(AlarmSource.TRACK) }
            }
            when (source) {
                AlarmSource.HIONE_CHIME -> {
                    Spacer(Modifier.height(6.dp))
                    Text("Built-in bell arpeggio, 48 kHz. Always available, even before the library is scanned.", color = Muted, fontSize = 10.5.sp, lineHeight = 14.sp)
                }
                AlarmSource.ARTIST, AlarmSource.ALBUM, AlarmSource.PLAYLIST, AlarmSource.TRACK -> {
                    Spacer(Modifier.height(8.dp))
                    val chosen = sourceRef?.let { ref -> AlarmLibrary.describeSource(source, ref, tracks) }
                    if (chosen != null) {
                        Text(chosen, color = HioneTealBright, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Spacer(Modifier.height(6.dp))
                    }
                    val options = when (source) {
                        AlarmSource.ARTIST -> artistNames.map { it to it }
                        AlarmSource.ALBUM -> albumNames.map { it to it }
                        AlarmSource.PLAYLIST -> playlistNames.map { it to it }
                        else -> trackOptions
                    }
                    val empty = if (source == AlarmSource.PLAYLIST) "No playlists yet." else "Nothing in your library yet."
                    AlarmPickList(options, sourceRef, empty) { sourceRef = it; error = null }
                }
                else -> {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        when (source) {
                            AlarmSource.LIKED_SONGS -> "Shuffled from your Liked Songs (falls back to the whole library if none)."
                            AlarmSource.DAILY_MIX -> "A fresh Daily Mix built from your listening taste."
                            else -> "Any track from the whole library, shuffled."
                        },
                        color = Muted, fontSize = 10.5.sp, lineHeight = 14.sp
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            AlarmSectionLabel("Label")
            AlarmTextField(label, { label = it }, "Alarm")

            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    AlarmSectionLabel("Fade-in")
                    AlarmStepper(fadeInSeconds, 0, 300, step = 5, suffix = "s") { fadeInSeconds = it }
                }
                Column {
                    AlarmSectionLabel("Snooze")
                    AlarmStepper(snoozeMinutes, 1, 30, suffix = "m") { snoozeMinutes = it }
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Volume cap", color = HioneTealBright, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("${(volumeCap * 100).roundToInt()}%", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = volumeCap,
                onValueChange = { volumeCap = it },
                valueRange = Alarm.MIN_VOLUME_CAP..1f,
                colors = SliderDefaults.colors(
                    thumbColor = HioneTealBright, activeTrackColor = HioneTealBright,
                    inactiveTrackColor = Color.White.copy(alpha = 0.12f)
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Text("Ceiling for the fade-in, relative to the current media volume. If the volume was turned all the way down at bedtime, the alarm lifts it to an audible floor and puts it back afterwards.", color = Muted, fontSize = 10.sp, lineHeight = 13.sp)

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Vibrate", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("Buzz pattern alongside the music", color = Muted, fontSize = 10.5.sp)
                }
                AlarmSwitch(checked = vibrate) { vibrate = it }
            }

            error?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = AlarmWarn, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.08f))
                        .clickable { build()?.let(onPreview) }.padding(vertical = 13.dp),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.PlayArrow, null, tint = HioneTealBright, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Preview", color = HioneTealBright, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                Row(
                    Modifier.weight(1.4f).clip(RoundedCornerShape(14.dp)).background(HionePink)
                        .clickable { build()?.let { runCatching { AlarmLocation.refreshIfStale(ctx) }; onSave(it) } }.padding(vertical = 13.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text("Save", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("Preview rings for real (fade, cap, sound) without saving; dismiss it like any alarm.", color = Muted, fontSize = 10.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}
