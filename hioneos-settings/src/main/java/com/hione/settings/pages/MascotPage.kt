package com.hione.settings.pages

import android.content.Context
import android.graphics.BitmapFactory
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.settings.Mascot
import com.hione.settings.MascotPack
import com.hione.settings.MascotPacks
import com.hione.settings.HioneMuted
import com.hione.settings.HioneTealBright
import com.hione.settings.R
import com.hione.settings.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Theme & mascot (route "mascot", or intent action com.hione.settings.action.MASCOT).
 *
 * The pack list is the built-in packs plus any pack Hione Update has downloaded. Picking one
 * writes Settings.Global hione_mascot_pack through Mascot.select, which also switches the
 * home-screen labels. Every HioneOS app watches that setting and changes colors, names and art
 * while it runs. This page reads Mascot colors too, so it changes as soon as the pick lands.
 *
 * The holiday section writes the keys the launcher's holiday engine reads: hione_holiday_scope,
 * hione_holiday_custom, hione_holiday_art and hione_holiday_reminders.
 */
@Composable
fun MascotPage() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val active = Mascot.pack
    var packs by remember { mutableStateOf(MascotPacks.BUILT_IN) }
    var confirm by remember { mutableStateOf<MascotPack?>(null) }
    LaunchedEffect(Unit) {
        packs = withContext(Dispatchers.IO) { runCatching { Mascot.available(ctx) }.getOrDefault(MascotPacks.BUILT_IN) }
    }

    PageList {
        item {
            Section {
                BodyText("Names, colors and art change in every app right away. The boot screen, " +
                    "the system popups and app names in Android's own settings follow the system image.")
            }
        }
        items(packs, key = { it.id }) { p ->
            PackCard(p, selected = p.id == active.id) { if (p.id != active.id) confirm = p }
        }
        item { HolidaySection() }
    }

    confirm?.let { p ->
        HioneAlert(
            title = "Switch to ${p.osName}?",
            text = "${p.fullName} becomes the mascot. The music app is called ${p.musicAppName}. " +
                "You can switch back here at any time.",
            confirm = "Switch",
            onConfirm = {
                confirm = null
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { Mascot.select(ctx, p.id) }
                    if (!ok) toast(ctx, "That setting was refused")
                }
            },
            onDismiss = { confirm = null }
        )
    }
}

/** One pack, drawn in its own colors so it previews the same whichever pack is active. */
@Composable
private fun PackCard(p: MascotPack, selected: Boolean, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val primary = Color(p.palette.primary)
    val accent = Color(p.palette.accent)
    val dark = Color(p.palette.dark)
    val light = Color(p.palette.light)
    val avatar by produceState<ImageBitmap?>(null, p.id) {
        value = if (p.id == "hione") null else withContext(Dispatchers.IO) { loadAvatar(ctx, p.id) }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(dark)
            .border(if (selected) 2.dp else 1.dp, if (selected) primary else primary.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(64.dp).clip(CircleShape).background(Color(p.palette.secondary)).border(1.5.dp, primary, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                val bmp = avatar
                when {
                    p.id == "hione" -> Image(painterResource(R.mipmap.ic_launcher), contentDescription = null,
                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    bmp != null -> Image(bmp, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    else -> Text(p.name.take(1), color = primary, fontSize = 26.sp, fontWeight = FontWeight.Black)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(p.osName, color = primary, fontSize = 18.sp, fontWeight = FontWeight.Black)
                Text("${p.fullName} · ${p.fullNameJa}", color = light, fontSize = 12.5.sp)
                Text("Music app: ${p.musicAppName}", color = light.copy(alpha = 0.7f), fontSize = 11.sp)
                if (p.source != "builtin") Text("Downloaded pack, version ${p.version}", color = light.copy(alpha = 0.6f), fontSize = 10.5.sp)
            }
            if (selected) Text("In use", color = dark, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(primary).padding(horizontal = 8.dp, vertical = 3.dp))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(p.palette.primary, p.palette.accent, p.palette.secondary, p.palette.dark, p.palette.light).forEach { c ->
                Box(Modifier.weight(1f).height(18.dp).clip(RoundedCornerShape(5.dp)).background(Color(c))
                    .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(5.dp)))
            }
        }
    }
}

private fun loadAvatar(ctx: Context, packId: String): ImageBitmap? = runCatching {
    Mascot.openArt(ctx, "avatar", packId)?.use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
}.getOrNull()

// ── Holidays ───────────────────────────────────────────────────────────────────────────────────

private class HolidayItem(val id: String, val name: String, val regions: List<String>)

/** Holidays from the bundled calendar.json, or empty when this build has none. */
private fun loadHolidays(ctx: Context): List<HolidayItem> = runCatching {
    val text = ctx.assets.open("calendar.json").bufferedReader().use { it.readText() }
    val root = text.trim()
    val arr: JSONArray = when {
        root.startsWith("[") -> JSONArray(root)
        else -> JSONObject(root).let { o ->
            o.optJSONArray("holidays") ?: o.optJSONObject("holidays")?.let { m ->
                JSONArray().apply { m.keys().forEach { k -> put(m.getJSONObject(k).apply { if (!has("id")) put("id", k) }) } }
            } ?: JSONArray()
        }
    }
    (0 until arr.length()).mapNotNull { i ->
        val h = arr.optJSONObject(i) ?: return@mapNotNull null
        val id = h.optString("id").ifBlank { return@mapNotNull null }
        HolidayItem(id, holidayName(h) ?: id, holidayRegions(h))
    }
}.getOrDefault(emptyList())

private fun holidayName(h: JSONObject): String? {
    for (k in listOf("name", "title", "names", "label")) {
        if (!h.has(k)) continue
        h.optJSONObject(k)?.let { o -> o.optString("en").ifBlank { null }?.let { return it } }
        h.optString(k).takeIf { it.isNotBlank() && !it.startsWith("{") }?.let { return it }
    }
    return null
}

private fun holidayRegions(h: JSONObject): List<String> {
    for (k in listOf("regions", "region", "countries", "country")) {
        h.optJSONArray(k)?.let { a -> return (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }
        h.optString(k).takeIf { it.isNotBlank() }?.let { return it.split(',').map { s -> s.trim() } }
    }
    return emptyList()
}

private fun regionLabel(r: String): String = when (r.lowercase()) {
    "us" -> "US"
    "uk", "gb" -> "UK"
    "jp" -> "Japan"
    "intl", "international", "world" -> "International"
    else -> r.uppercase()
}

@Composable
private fun HolidaySection() {
    val ctx = LocalContext.current
    val cr = ctx.contentResolver
    fun read(k: String): String? = runCatching { Settings.Global.getString(cr, k) }.getOrNull()
    fun write(k: String, v: String): Boolean {
        val ok = runCatching { Settings.Global.putString(cr, k, v) }.getOrDefault(false)
        if (!ok) toast(ctx, "That setting was refused")
        return ok
    }
    var scope by remember { mutableStateOf(read(Mascot.HOLIDAY_SCOPE)?.takeIf { it in setOf("intl", "local", "custom", "off") } ?: "local") }
    var art by remember { mutableStateOf(read(Mascot.HOLIDAY_ART) != "0") }
    var reminders by remember { mutableStateOf(read(Mascot.HOLIDAY_REMINDERS) != "0") }
    var custom by remember {
        mutableStateOf(read(Mascot.HOLIDAY_CUSTOM).orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet())
    }
    val holidays by produceState(emptyList<HolidayItem>()) { value = withContext(Dispatchers.IO) { loadHolidays(ctx) } }

    fun setScope(v: String) { if (write(Mascot.HOLIDAY_SCOPE, v)) scope = v }

    Column {
        Section("Holidays", note = "Which holidays get their own wallpaper, lock screen art and reminder.") {
            RadioRow("International", "Holidays from every region on the list", scope == "intl") { setScope("intl") }
            RadioRow("Local", "Holidays where you are, from location, SIM and language", scope == "local") { setScope("local") }
            RadioRow("Custom", "Only the holidays you check", scope == "custom") { setScope("custom") }
            RadioRow("Off", "No holiday art or reminders", scope == "off") { setScope("off") }
        }
        Section {
            ToggleRow("Holiday art", "Wallpaper and lock screen art on the day", art, enabled = scope != "off") {
                if (write(Mascot.HOLIDAY_ART, if (it) "1" else "0")) art = it
            }
            ToggleRow("Reminders", "A notification on the day, or the day before for days of remembrance. Follows Do Not Disturb.",
                reminders, enabled = scope != "off") {
                if (write(Mascot.HOLIDAY_REMINDERS, if (it) "1" else "0")) reminders = it
            }
        }
        if (scope == "custom") {
            Section("Pick holidays") {
                if (holidays.isEmpty()) BodyText("No holiday list in this build.")
                holidays.forEach { h ->
                    val on = h.id in custom
                    CheckRow(h.name, h.regions.joinToString(", ") { regionLabel(it) }.ifBlank { null }, on) { want ->
                        val next = if (want) custom + h.id else custom - h.id
                        if (write(Mascot.HOLIDAY_CUSTOM, next.sorted().joinToString(","))) custom = next
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckRow(title: String, summary: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onChange(!checked) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange,
            colors = CheckboxDefaults.colors(checkedColor = HioneTealBright, uncheckedColor = HioneMuted))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 13.sp)
            if (summary != null) Text(summary, color = HioneMuted, fontSize = 11.sp)
        }
    }
}
