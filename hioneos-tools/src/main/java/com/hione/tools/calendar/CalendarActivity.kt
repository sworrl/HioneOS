package com.hione.tools.calendar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.tools.ui.GlassDialog
import com.hione.tools.ui.GlassIconButton
import com.hione.tools.ui.GlassSegmented
import com.hione.tools.ui.GlassTextButton
import com.hione.tools.ui.Hione
import com.hione.tools.ui.HioneBackground
import com.hione.tools.ui.HioneTheme
import com.hione.tools.ui.HioneTopBar
import com.hione.tools.ui.SectionLabel
import com.hione.tools.ui.pressable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

enum class CalMode { MONTH, WEEK, DAY, AGENDA }

class CalendarActivity : ComponentActivity() {
    private val granted = mutableStateOf(false)
    private val deniedForever = mutableStateOf(false)
    private val jumpTo = mutableStateOf<LocalDate?>(null)
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted.value = CalendarRepo.hasPermission(this)
        if (!granted.value) deniedForever.value = !shouldShowRequestPermissionRationale(Manifest.permission.READ_CALENDAR)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        granted.value = CalendarRepo.hasPermission(this)
        readIntent(intent)
        if (!granted.value) requestCalendar()
        setContent {
            HioneTheme {
                HioneBackground {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        if (granted.value) CalendarApp(jumpTo.value) { jumpTo.value = null }
                        else PermissionGate(this@CalendarActivity, deniedForever.value) { requestCalendar() }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        granted.value = CalendarRepo.hasPermission(this)
    }

    /** VIEW content://com.android.calendar/time/<millis>: open on that day. */
    private fun readIntent(i: Intent?) {
        val data = i?.data ?: return
        if (data.authority == CalendarContract.AUTHORITY && data.pathSegments.firstOrNull() == "time") {
            data.lastPathSegment?.toLongOrNull()?.let {
                jumpTo.value = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
            }
        }
    }

    private fun requestCalendar() {
        val perms = mutableListOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // Event reminders are notifications; ask once alongside.
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        permissionLauncher.launch(perms.toTypedArray())
    }
}

private val weekStart get() = WeekFields.of(Locale.getDefault()).firstDayOfWeek

@Composable
private fun CalendarApp(jump: LocalDate?, onJumpHandled: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("calendar", 0) }
    var mode by remember { mutableStateOf(runCatching { CalMode.valueOf(prefs.getString("mode", "MONTH")!!) }.getOrDefault(CalMode.MONTH)) }
    var selected by remember { mutableStateOf(LocalDate.now()) }
    var dir by remember { mutableIntStateOf(0) }
    var showCalendars by remember { mutableStateOf(false) }
    LaunchedEffect(jump) { if (jump != null) { selected = jump; onJumpHandled() } }

    val rev = rememberCalendarRevision()
    val zone = ZoneId.systemDefault()

    // Range of days the current view needs.
    val (rangeStart, rangeEnd) = when (mode) {
        CalMode.MONTH -> {
            val first = YearMonth.from(selected).atDay(1).with(TemporalAdjusters.previousOrSame(weekStart))
            first to first.plusDays(42)
        }
        CalMode.WEEK -> {
            val s = selected.with(TemporalAdjusters.previousOrSame(weekStart)); s to s.plusDays(7)
        }
        CalMode.DAY -> selected to selected.plusDays(1)
        CalMode.AGENDA -> selected to selected.plusDays(60)
    }
    val insts by produceState(emptyList<Inst>(), rangeStart, rangeEnd, rev) {
        // A day of slack either side catches all-day events, which are stored in UTC.
        value = withContext(Dispatchers.IO) {
            CalendarRepo.instances(ctx, rangeStart.minusDays(1).startMillis(zone), rangeEnd.plusDays(1).startMillis(zone))
        }
    }

    fun step(n: Int) {
        dir = n
        selected = when (mode) {
            CalMode.MONTH -> selected.plusMonths(n.toLong())
            CalMode.WEEK -> selected.plusWeeks(n.toLong())
            CalMode.DAY -> selected.plusDays(n.toLong())
            CalMode.AGENDA -> selected.plusDays(30L * n)
        }
    }

    val title = when (mode) {
        CalMode.DAY -> CalFormat.date(selected)
        CalMode.WEEK -> {
            val s = rangeStart; val e = rangeEnd.minusDays(1)
            if (s.month == e.month) s.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))
            else s.format(DateTimeFormatter.ofPattern("MMM", Locale.getDefault())) + " – " + e.format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault()))
        }
        else -> selected.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            HioneTopBar(title, actions = {
                GlassIconButton(Icons.Outlined.Today, "Today", { dir = if (LocalDate.now().isAfter(selected)) 1 else -1; selected = LocalDate.now() })
                GlassIconButton(Icons.Outlined.Layers, "Calendars", { showCalendars = true })
            })
            GlassSegmented(
                listOf("Month", "Week", "Day", "List"), mode.ordinal,
                { mode = CalMode.entries[it]; prefs.edit().putString("mode", mode.name).apply() },
                Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            )
            var dragTotal by remember { mutableStateOf(0f) }
            Box(
                Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)
                    .pointerInput(mode) {
                        detectHorizontalDragGestures(
                            onDragStart = { dragTotal = 0f },
                            onDragEnd = { if (dragTotal > 140) step(-1) else if (dragTotal < -140) step(1) },
                        ) { _, dx -> dragTotal += dx }
                    }
            ) {
                AnimatedContent(
                    // Keyed on the period, not the selected day: picking a day inside the month
                    // must not slide the whole month in again.
                    targetState = Triple(mode, rangeStart, if (mode == CalMode.DAY) selected else rangeStart),
                    transitionSpec = {
                        val d = dir
                        (slideInHorizontally(tween(220)) { w -> w / 4 * d } + fadeIn(tween(220))) togetherWith
                            (slideOutHorizontally(tween(180)) { w -> -w / 4 * d } + fadeOut(tween(160)))
                    },
                    label = "period"
                ) { (m, start, day) ->
                    when (m) {
                        // The grid starts up to six days before the 1st, so a week in is always
                        // inside the month this content belongs to.
                        CalMode.MONTH -> MonthView(YearMonth.from(start.plusDays(7)), selected, insts, onSelect = { selected = it })
                        CalMode.WEEK -> TimeGridView((0 until 7).map { start.plusDays(it.toLong()) }, insts, compact = true,
                            onDayTap = { selected = it; mode = CalMode.DAY })
                        CalMode.DAY -> TimeGridView(listOf(day), insts, compact = false, onDayTap = {})
                        CalMode.AGENDA -> AgendaView(start, insts)
                    }
                }
            }
        }
        // New event: on the selected day, at the next full hour if that is today.
        Box(
            Modifier.align(Alignment.BottomEnd).padding(18.dp).size(64.dp)
                .pressable({
                    val now = LocalDateTime.now()
                    val startAt = if (selected == now.toLocalDate()) now.toLocalDate().atTime(now.hour, 0).plusHours(1)
                    else selected.atTime(LocalTime.of(9, 0))
                    ctx.startActivity(newEventIntent(ctx, startAt.millis(zone), startAt.plusHours(1).millis(zone)))
                })
                .clip(CircleShape)
                .background(Brush.verticalGradient(listOf(Hione.PinkSoft, Hione.Pink)))
                .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Filled.Add, "New event", tint = Color.White, modifier = Modifier.size(32.dp)) }
    }

    if (showCalendars) CalendarsDialog { showCalendars = false }
}

@Composable
private fun CalendarsDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val rev = rememberCalendarRevision()
    var local by remember { mutableIntStateOf(0) }
    val cals by produceState(emptyList<CalInfo>(), rev, local) { value = withContext(Dispatchers.IO) { CalendarRepo.calendars(ctx) } }
    GlassDialog(onDismiss, title = "Calendars", buttons = {
        GlassIconButton(Icons.Outlined.Refresh, "Sync now", {
            CalendarRepo.requestSync(ctx)
            Toast.makeText(ctx, "Syncing", Toast.LENGTH_SHORT).show()
        })
        Spacer(Modifier.weight(1f))
        GlassTextButton("Done", onDismiss, filled = true)
    }) {
        if (cals.isEmpty()) {
            Text("No calendars yet. Add an account to sync one, or events you create will go to a calendar on this device.", color = Hione.TextDim, fontSize = 14.sp)
        }
        cals.groupBy { it.accountName }.forEach { (acct, list) ->
            SectionLabel(if (list.first().accountType == CalendarContract.ACCOUNT_TYPE_LOCAL) "On this device" else acct)
            list.forEach { c ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable {
                        CalendarRepo.setVisible(ctx, c.id, !c.visible); local++
                    }.padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(c.color.asColor()))
                    Spacer(Modifier.width(12.dp))
                    Text(c.name, color = Hione.Text, fontSize = 16.sp, modifier = Modifier.weight(1f), fontWeight = if (c.primary) FontWeight.SemiBold else FontWeight.Normal)
                    Checkbox(c.visible, { CalendarRepo.setVisible(ctx, c.id, it); local++ },
                        colors = CheckboxDefaults.colors(checkedColor = c.color.asColor(), uncheckedColor = Hione.Faint, checkmarkColor = Color.Black))
                }
            }
        }
        Spacer(Modifier.size(8.dp))
        GlassTextButton("Add an account", {
            runCatching {
                ctx.startActivity(Intent(Settings.ACTION_ADD_ACCOUNT).putExtra(Settings.EXTRA_AUTHORITIES, arrayOf(CalendarContract.AUTHORITY))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }, Modifier.fillMaxWidth())
    }
}
