package com.hione.tools.clock

import com.hione.tools.Mascot
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.AlarmOff
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.tools.ui.EmptyState
import com.hione.tools.ui.GlassDialog
import com.hione.tools.ui.GlassIconButton
import com.hione.tools.ui.GlassPanel
import com.hione.tools.ui.GlassRow
import com.hione.tools.ui.GlassSegmented
import com.hione.tools.ui.GlassTextButton
import com.hione.tools.ui.Hione
import com.hione.tools.ui.HioneMono
import com.hione.tools.ui.HioneSwitch
import com.hione.tools.ui.HioneTopBar
import com.hione.tools.ui.SectionLabel
import com.hione.tools.ui.glass
import com.hione.tools.ui.pressable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Calendar

@Composable
fun AlarmsTab(
    openAlarmId: Int,
    onOpenHandled: () -> Unit,
    createNew: Boolean,
    onCreateHandled: () -> Unit,
    onSettings: () -> Unit,
) {
    val ctx = LocalContext.current
    val rev = rememberClockRevision()
    val alarms = remember(rev) { ClockStore.alarms(ctx) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(20_000); now = System.currentTimeMillis() } }

    var editing by remember { mutableStateOf<ClockAlarm?>(null) }
    var pickingNewTime by remember { mutableStateOf(false) }

    if (openAlarmId >= 0) {
        LaunchedEffect(openAlarmId) { editing = ClockStore.alarm(ctx, openAlarmId); onOpenHandled() }
    }
    if (createNew) {
        LaunchedEffect(Unit) { pickingNewTime = true; onCreateHandled() }
    }

    val hasHione = remember { HioneMusicBridge.isInstalled(ctx) }
    val hioneNext = remember(rev, now) { if (hasHione) HioneMusicBridge.nextHioneAlarm(ctx) else null }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 96.dp)) {
            item {
                HioneTopBar("Alarms", actions = { GlassIconButton(Icons.Outlined.Settings, "Clock settings", onSettings) })
            }
            item { NextAlarmHero(alarms, now) }
            if (hasHione) item { HioneMusicCard(hioneNext) }
            if (alarms.isEmpty()) {
                item { EmptyState(Icons.Outlined.AlarmOff, "No alarms yet", "Tap + to set one.") }
            }
            items(alarms, key = { it.id }) { a ->
                AlarmCard(a, now, onClick = { editing = a }, onToggle = { on ->
                    ClockStore.updateAlarm(ctx, a.id) { it.copy(enabled = on, snoozedUntil = 0, skipUntil = 0) }
                    AlarmScheduler.sync(ctx)
                })
            }
        }
        Box(
            Modifier.align(Alignment.BottomEnd).padding(18.dp).size(68.dp)
                .pressable({ pickingNewTime = true })
                .clip(CircleShape)
                .background(Brush.verticalGradient(listOf(Hione.PinkSoft, Hione.Pink)))
                .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Filled.Add, "New alarm", tint = Color.White, modifier = Modifier.size(34.dp)) }
    }

    if (pickingNewTime) {
        val c = Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, 1) }
        TimePickDialog(c.get(Calendar.HOUR_OF_DAY), 0, onDismiss = { pickingNewTime = false }) { h, m ->
            pickingNewTime = false
            val a = ClockAlarm(id = ClockStore.newAlarmId(ctx), hour = h, minute = m)
            ClockStore.upsertAlarm(ctx, a)
            AlarmScheduler.sync(ctx)
            editing = a
        }
    }
    editing?.let { a -> AlarmEditorDialog(a, onDismiss = { editing = null }) }
}

@Composable
private fun NextAlarmHero(alarms: List<ClockAlarm>, now: Long) {
    val ctx = LocalContext.current
    val next = AlarmMath.soonest(alarms, now)
    GlassPanel(Modifier.fillMaxWidth().padding(vertical = 6.dp), shape = RoundedCornerShape(24.dp), padding = PaddingValues(18.dp)) {
        Text("NEXT ALARM", color = Hione.Teal, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
        Spacer(Modifier.height(4.dp))
        if (next == null) {
            Text("None set", color = Hione.TextDim, fontSize = 22.sp)
        } else {
            Text(ClockFormat.whenText(ctx, next.second, now), color = Hione.Text, fontSize = 26.sp, fontFamily = HioneMono)
            Text(ClockFormat.until(next.second, now).replaceFirstChar { it.uppercase() } +
                if (next.first.label.isNotBlank()) "  ·  ${next.first.label}" else "",
                color = Hione.TealGlow, fontSize = 15.sp)
        }
    }
}

/** Hione Music has its own alarms (library playlists, sunrise triggers). Clock does not copy or
 *  edit them; it shows the next one when the system knows it and links straight there. */
@Composable
private fun HioneMusicCard(next: Long?) {
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp)
            .glass(RoundedCornerShape(20.dp), accent = Hione.PinkSoft)
            .clickable { HioneMusicBridge.openHioneAlarms(ctx) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.LibraryMusic, null, tint = Hione.PinkSoft, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("${Mascot.musicAppName} alarms", color = Hione.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(
                if (next != null) "Next: ${ClockFormat.whenText(ctx, next)}" else "Wake to your library. Set them up in ${Mascot.musicAppName}.",
                color = Hione.Muted, fontSize = 13.sp, maxLines = 2
            )
        }
        Text("Open", color = Hione.PinkSoft, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun AlarmCard(a: ClockAlarm, now: Long, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val dim = !a.enabled
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp)
            .glass(RoundedCornerShape(22.dp), accent = if (dim) Hione.Faint else Hione.Teal, rimAlpha = if (dim) 0.25f else 0.45f)
            .clickable(onClick = onClick)
            .padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(ClockFormat.time(ctx, a.hour, a.minute), color = if (dim) Hione.Muted else Hione.Text, fontSize = 44.sp, fontFamily = HioneMono, fontWeight = FontWeight.Light)
                val ap = ClockFormat.amPm(ctx, a.hour)
                if (ap.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    Text(ap, color = Hione.Muted, fontSize = 16.sp, modifier = Modifier.padding(bottom = 8.dp))
                }
            }
            val line = buildList {
                if (a.label.isNotBlank()) add(a.label)
                add(ClockFormat.daysSummary(a.days))
                if (a.sound != WakeSound.TONE) add("${Mascot.musicAppName}")
            }.joinToString("  ·  ")
            Text(line, color = if (dim) Hione.Faint else Hione.TextDim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val status = when {
                a.snoozedUntil > now -> "Snoozed until ${ClockFormat.instant(ctx, a.snoozedUntil)}"
                a.enabled && a.repeating && a.skipUntil > now -> "Skipping once, next ${AlarmMath.nextFire(a, now)?.let { ClockFormat.whenText(ctx, it, now) }}"
                else -> null
            }
            if (status != null) Text(status, color = Hione.PinkSoft, fontSize = 13.sp)
        }
        HioneSwitch(a.enabled, onToggle)
    }
}

// ---------------------------------------------------------------------------------------------
// Editor
// ---------------------------------------------------------------------------------------------

@Composable
private fun AlarmEditorDialog(original: ClockAlarm, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var hour by remember { mutableStateOf(original.hour) }
    var minute by remember { mutableStateOf(original.minute) }
    val days = remember { mutableStateListOf<Int>().apply { addAll(original.days) } }
    var label by remember { mutableStateOf(original.label) }
    var ringtone by remember { mutableStateOf(original.ringtone) }
    var vibrate by remember { mutableStateOf(original.vibrate) }
    var gradual by remember { mutableStateOf(original.gradualSeconds) }
    var snooze by remember { mutableStateOf(original.snoozeMinutes) }
    var sound by remember { mutableStateOf(original.sound) }
    var pickTime by remember { mutableStateOf(false) }
    var pickTone by remember { mutableStateOf(false) }
    val hasHione = remember { HioneMusicBridge.isInstalled(ctx) }

    fun save() {
        val timeChanged = hour != original.hour || minute != original.minute || days.toSet() != original.days
        ClockStore.updateAlarm(ctx, original.id) { cur ->
            cur.copy(
                hour = hour, minute = minute, days = days.toSet(), label = label.trim(), ringtone = ringtone,
                vibrate = vibrate, gradualSeconds = gradual, snoozeMinutes = snooze, sound = sound,
                // Editing an alarm means you want it: turn it on, and a new time drops any snooze/skip.
                enabled = true,
                snoozedUntil = if (timeChanged) 0 else cur.snoozedUntil,
                skipUntil = if (timeChanged) 0 else cur.skipUntil,
                deleteAfterUse = false,
            )
        }
        AlarmScheduler.sync(ctx)
        onDismiss()
    }

    GlassDialog(onDismiss, buttons = {
        GlassTextButton("Delete", {
            ClockStore.removeAlarm(ctx, original.id); AlarmScheduler.sync(ctx)
            ClockNotify.nm(ctx).cancel(ClockNotify.idUpcoming(original.id))
            onDismiss()
        }, icon = Icons.Outlined.DeleteOutline, accent = Hione.PinkSoft)
        Spacer(Modifier.weight(1f))
        GlassTextButton("Save", { save() }, icon = Icons.Outlined.Check, filled = true)
    }) {
        // Time
        Box(
            Modifier.fillMaxWidth().glass(RoundedCornerShape(22.dp)).clickable { pickTime = true }.padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(ClockFormat.time(ctx, hour, minute), color = Hione.Text, fontSize = 60.sp, fontFamily = HioneMono, fontWeight = FontWeight.Light)
                val ap = ClockFormat.amPm(ctx, hour)
                if (ap.isNotEmpty()) { Spacer(Modifier.width(6.dp)); Text(ap, color = Hione.Muted, fontSize = 20.sp, modifier = Modifier.padding(bottom = 12.dp)) }
            }
        }

        SectionLabel("Repeat")
        DayPicker(days.toSet()) { d -> if (d in days) days.remove(d) else days.add(d) }

        SectionLabel("Label")
        OutlinedTextField(
            label, { label = it.take(60) }, Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text("Alarm", color = Hione.Faint) },
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Hione.Teal, unfocusedBorderColor = Hione.Faint, cursorColor = Hione.Teal),
            shape = RoundedCornerShape(16.dp)
        )

        SectionLabel("Sound")
        if (hasHione) {
            val opts = listOf(WakeSound.TONE, WakeSound.MUSIC_SHUFFLE, WakeSound.MUSIC_RESUME)
            GlassSegmented(listOf("Tone", "Shuffle", "Resume"), opts.indexOf(sound), { sound = opts[it] }, Modifier.fillMaxWidth())
            Text(
                when (sound) {
                    WakeSound.TONE -> "Plays an alarm sound."
                    WakeSound.MUSIC_SHUFFLE -> "${Mascot.musicAppName} plays a random song from your library. The tone plays if no music starts."
                    WakeSound.MUSIC_RESUME -> "${Mascot.musicAppName} picks up where you left off. The tone plays if no music starts."
                },
                color = Hione.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp, start = 4.dp)
            )
            Spacer(Modifier.height(8.dp))
        }
        Box(Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp), rimAlpha = 0.25f)) {
            GlassRow(
                if (sound == WakeSound.TONE) "Alarm sound" else "Backup sound",
                value = ringtoneTitle(ctx, ringtone),
                icon = Icons.Outlined.MusicNote,
                onClick = { pickTone = true }
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp), rimAlpha = 0.25f)) {
            GlassRow("Vibrate", trailing = { HioneSwitch(vibrate, { vibrate = it }) }, onClick = { vibrate = !vibrate })
        }

        SectionLabel("Fade in")
        val gradOpts = listOf(0, 15, 30, 60, 120)
        GlassSegmented(listOf("Off", "15 s", "30 s", "1 m", "2 m"), gradOpts.indexOf(gradual).coerceAtLeast(0), { gradual = gradOpts[it] }, Modifier.fillMaxWidth())

        SectionLabel("Snooze length")
        val snzOpts = listOf(5, 10, 15, 20, 30)
        GlassSegmented(snzOpts.map { "$it m" }, snzOpts.indexOf(snooze).coerceAtLeast(1), { snooze = snzOpts[it] }, Modifier.fillMaxWidth())

        if (original.enabled && original.repeating) {
            Spacer(Modifier.height(14.dp))
            val now = System.currentTimeMillis()
            val next = AlarmMath.nextFire(original, now)
            if (original.skipUntil > now) {
                GlassTextButton("Don't skip", {
                    ClockStore.updateAlarm(ctx, original.id) { it.copy(skipUntil = 0) }; AlarmScheduler.sync(ctx); onDismiss()
                }, Modifier.fillMaxWidth())
            } else if (next != null) {
                GlassTextButton("Skip ${ClockFormat.whenText(ctx, next, now)}", {
                    AlarmScheduler.dismissEarly(ctx, original.id); onDismiss()
                }, Modifier.fillMaxWidth())
            }
        }
    }

    if (pickTime) TimePickDialog(hour, minute, onDismiss = { pickTime = false }) { h, m -> hour = h; minute = m; pickTime = false }
    if (pickTone) RingtonePickerDialog(ringtone, onPick = { ringtone = it }, onDismiss = { pickTone = false })
}

@Composable
private fun DayPicker(selected: Set<Int>, onToggle: (Int) -> Unit) {
    val first = Calendar.getInstance().firstDayOfWeek
    val order = (0 until 7).map { ((first - 1 + it) % 7) + 1 }
    val letters = arrayOf("S", "M", "T", "W", "T", "F", "S")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        order.forEach { d ->
            val on = d in selected
            Box(
                Modifier.size(42.dp)
                    .pressable({ onToggle(d) })
                    .clip(CircleShape)
                    .background(if (on) Hione.Teal.copy(alpha = 0.85f) else Color(0x22FFFFFF))
                    .border(1.dp, if (on) Hione.TealGlow else Hione.Faint, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(letters[d - 1], color = if (on) Mascot.color(0xFF02201D) else Hione.TextDim, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }
    Text(ClockFormat.daysSummary(selected), color = Hione.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
}

@Composable
fun TimePickDialog(hour: Int, minute: Int, onDismiss: () -> Unit, onPick: (Int, Int) -> Unit) {
    val ctx = LocalContext.current
    val state = rememberTimePickerState(hour, minute, ClockFormat.is24(ctx))
    GlassDialog(onDismiss, scrollable = true, buttons = {
        GlassTextButton("Cancel", onDismiss)
        GlassTextButton("OK", { onPick(state.hour, state.minute) }, filled = true)
    }) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            TimePicker(
                state,
                colors = TimePickerDefaults.colors(
                    clockDialColor = Mascot.color(0xFF13232A),
                    clockDialSelectedContentColor = Mascot.color(0xFF02201D),
                    clockDialUnselectedContentColor = Hione.TextDim,
                    selectorColor = Hione.Teal,
                    periodSelectorBorderColor = Hione.Faint,
                    periodSelectorSelectedContainerColor = Hione.Teal.copy(alpha = 0.3f),
                    periodSelectorSelectedContentColor = Hione.Text,
                    periodSelectorUnselectedContentColor = Hione.Muted,
                    timeSelectorSelectedContainerColor = Hione.Teal.copy(alpha = 0.3f),
                    timeSelectorUnselectedContainerColor = Mascot.color(0xFF13232A),
                    timeSelectorSelectedContentColor = Hione.Text,
                    timeSelectorUnselectedContentColor = Hione.TextDim,
                )
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Ringtones
// ---------------------------------------------------------------------------------------------

fun ringtoneTitle(ctx: Context, spec: String?): String = when (spec) {
    null -> "Default alarm sound"
    ClockAlarm.RINGTONE_SILENT -> "Silent"
    ClockAlarm.RINGTONE_CHIME -> "${Mascot.name} chime"
    else -> runCatching { RingtoneManager.getRingtone(ctx, Uri.parse(spec))?.getTitle(ctx) }.getOrNull() ?: "Custom sound"
}

private data class ToneOption(val title: String, val spec: String?)

/** Our own picker rather than the system one, so it matches the rest of Clock and offers the
 *  built-in chime. Tapping an entry previews it on the alarm stream. */
@Composable
fun RingtonePickerDialog(current: String?, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var selected by remember { mutableStateOf(current) }
    val options = remember { mutableStateListOf(
        ToneOption("Default alarm sound", null),
        ToneOption("${Mascot.name} chime", ClockAlarm.RINGTONE_CHIME),
        ToneOption("Silent", ClockAlarm.RINGTONE_SILENT),
    ) }
    LaunchedEffect(Unit) {
        val list = withContext(Dispatchers.IO) {
            runCatching {
                val rm = RingtoneManager(ctx).apply { setType(RingtoneManager.TYPE_ALARM) }
                val c = rm.cursor
                val out = ArrayList<ToneOption>()
                while (c.moveToNext()) {
                    val title = c.getString(RingtoneManager.TITLE_COLUMN_INDEX)
                    val uri = rm.getRingtoneUri(c.position)
                    out += ToneOption(title, uri.toString())
                }
                out
            }.getOrDefault(emptyList())
        }
        options.addAll(list)
    }
    val preview = remember { arrayOfNulls<MediaPlayer>(1) }
    fun stopPreview() { preview[0]?.let { runCatching { it.stop() }; it.release() }; preview[0] = null }
    DisposableEffect(Unit) { onDispose { stopPreview() } }

    fun play(spec: String?) {
        stopPreview()
        val uri = when (spec) {
            ClockAlarm.RINGTONE_SILENT -> return
            ClockAlarm.RINGTONE_CHIME -> HioneChime.file(ctx)?.let { Uri.fromFile(it) }
            null -> RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_ALARM)
            else -> Uri.parse(spec)
        } ?: return
        runCatching {
            preview[0] = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(ctx, uri); prepare(); start()
            }
        }
    }

    GlassDialog(onDismiss, title = "Sound", scrollable = false, buttons = {
        GlassTextButton("Cancel", { stopPreview(); onDismiss() })
        GlassTextButton("OK", { stopPreview(); onPick(selected); onDismiss() }, filled = true)
    }) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
            items(options) { o ->
                val sel = o.spec == selected
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 2.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (sel) Hione.Teal.copy(alpha = 0.18f) else Color.Transparent)
                        .clickable { selected = o.spec; play(o.spec) }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(o.title, color = if (sel) Hione.TealGlow else Hione.Text, fontSize = 16.sp, modifier = Modifier.weight(1f).animateContentSize())
                    if (sel) Icon(Icons.Outlined.Check, null, tint = Hione.TealGlow)
                }
            }
        }
    }
}
