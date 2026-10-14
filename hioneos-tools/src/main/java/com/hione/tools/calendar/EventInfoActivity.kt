package com.hione.tools.calendar

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.tools.ui.GlassDialog
import com.hione.tools.ui.GlassIconButton
import com.hione.tools.ui.GlassTextButton
import com.hione.tools.ui.Hione
import com.hione.tools.ui.HioneBackground
import com.hione.tools.ui.HioneTheme
import com.hione.tools.ui.HioneTopBar
import com.hione.tools.ui.glass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * VIEW content://com.android.calendar/events/<id>, optionally with EXTRA_EVENT_BEGIN_TIME /
 * END_TIME naming which occurrence of a recurring event was tapped.
 */
class EventInfoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val id = intent?.data?.let { runCatching { ContentUris.parseId(it) }.getOrNull() } ?: -1L
        if (id <= 0) { finish(); return }
        val begin = intent.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, 0L)
        val end = intent.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, 0L)
        if (!CalendarRepo.hasPermission(this)) {
            // Other apps can link here before Calendar was ever opened; send them through the
            // main screen's permission request once.
            startActivity(Intent(this, CalendarActivity::class.java))
            Toast.makeText(this, "Allow calendar access first", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        setContent { HioneTheme { HioneBackground { EventInfoScreen(id, begin, end) { finish() } } } }
    }
}

@Composable
private fun EventInfoScreen(id: Long, instBegin: Long, instEnd: Long, close: () -> Unit) {
    val ctx = LocalContext.current
    val rev = rememberCalendarRevision()
    val scope = rememberCoroutineScope()
    // (loaded at least once, event). Null after a load means it was deleted (here or elsewhere).
    val state by produceState<Pair<Boolean, EventFull?>>(false to null, rev) {
        value = true to withContext(Dispatchers.IO) { CalendarRepo.event(ctx, id) }
    }
    val ev = state.second
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(state) { if (state.first && state.second == null) close() }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        HioneTopBar("Event", onBack = close, actions = {
            val e = ev
            if (e != null && e.canEdit) {
                GlassIconButton(Icons.Outlined.Edit, "Edit", {
                    ctx.startActivity(Intent(Intent.ACTION_EDIT, CalendarRepo.eventUri(id), ctx, EditEventActivity::class.java)
                        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, instBegin)
                        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, instEnd))
                })
                GlassIconButton(Icons.Outlined.DeleteOutline, "Delete", { confirmDelete = true }, tint = Hione.PinkSoft)
            }
        })
        val e = ev
        if (e == null) return@Column
        val begin = if (instBegin > 0) instBegin else e.dtStart
        val end = if (instEnd > 0) instEnd else begin + e.lengthMs
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
            Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp), accent = e.color.asColor()).padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(e.color.asColor()))
                    Spacer(Modifier.width(10.dp))
                    Text(e.calendarName, color = Hione.Muted, fontSize = 13.sp)
                }
                Spacer(Modifier.height(8.dp))
                SelectionContainer {
                    Text(e.title.ifBlank { "(No title)" }, color = Hione.Text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(10.dp))
            InfoLine(Icons.Outlined.Schedule, CalFormat.range(ctx, begin, end, e.allDay),
                sub = if (e.allDay) null else CalFormat.date(java.time.Instant.ofEpochMilli(begin).atZone(java.time.ZoneId.systemDefault()).toLocalDate()))
            CalFormat.rrule(e.rrule)?.let { InfoLine(Icons.Outlined.Repeat, it) }
            if (e.location.isNotBlank()) {
                InfoLine(Icons.Outlined.Place, e.location, onClick = {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(e.location)))) }
                        .onFailure { Toast.makeText(ctx, "No map app installed", Toast.LENGTH_SHORT).show() }
                })
            }
            if (e.reminders.isNotEmpty()) InfoLine(Icons.Outlined.Notifications, e.reminders.sorted().joinToString("\n") { CalFormat.reminder(it) })
            if (e.guests.isNotEmpty()) {
                InfoLine(Icons.Outlined.People, e.guests.joinToString("\n") { g ->
                    val who = g.name.ifBlank { g.email }
                    val st = when (g.status) {
                        Attendees.ATTENDEE_STATUS_ACCEPTED -> "going"
                        Attendees.ATTENDEE_STATUS_DECLINED -> "not going"
                        Attendees.ATTENDEE_STATUS_TENTATIVE -> "maybe"
                        else -> "invited"
                    }
                    if (g.organizer) "$who (organizer)" else "$who, $st"
                })
            }
            if (e.description.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp), rimAlpha = 0.25f).padding(16.dp)) {
                    SelectionContainer { Text(e.description, color = Hione.TextDim, fontSize = 15.sp) }
                }
            }
            InfoLine(Icons.Outlined.CalendarToday, if (e.availability == CalendarContract.Events.AVAILABILITY_FREE) "Shows as free" else "Shows as busy")
            Spacer(Modifier.height(40.dp))
        }

        if (confirmDelete) {
            val recurring = e.rrule != null && instBegin > 0
            GlassDialog({ confirmDelete = false }, title = "Delete event?", buttons = {
                GlassTextButton("Cancel", { confirmDelete = false })
                if (recurring) GlassTextButton("This one", {
                    scope.launch {
                        withContext(Dispatchers.IO) { CalendarRepo.deleteOccurrence(ctx, id, instBegin) }
                        close()
                    }
                }, accent = Hione.PinkSoft)
                GlassTextButton(if (recurring) "All" else "Delete", {
                    scope.launch {
                        withContext(Dispatchers.IO) { CalendarRepo.delete(ctx, id) }
                        close()
                    }
                }, filled = true, accent = Hione.PinkSoft)
            }) {
                Text(
                    if (recurring) "This event repeats. Delete only this one, or every occurrence?" else "\"${e.title.ifBlank { "(No title)" }}\" will be removed from ${e.calendarName}.",
                    color = Hione.TextDim, fontSize = 15.sp
                )
            }
        }
    }
}

@Composable
private fun InfoLine(icon: ImageVector, text: String, sub: String? = null, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 6.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(icon, null, tint = Hione.Teal, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(text, color = if (onClick != null) Hione.TealGlow else Hione.Text, fontSize = 16.sp)
            if (sub != null) Text(sub, color = Hione.Muted, fontSize = 13.sp)
        }
    }
}
