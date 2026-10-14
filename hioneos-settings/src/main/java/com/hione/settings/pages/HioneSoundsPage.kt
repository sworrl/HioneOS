package com.hione.settings.pages

import com.hione.settings.Mascot
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.settings.HioneMuted
import com.hione.settings.HioneTealBright
import com.hione.settings.ui.*
import org.json.JSONObject
import java.io.File

/**
 * HioneOS sounds: a picker per OS sound event (charger, battery, USB DAC, headphones, lock,
 * screenshot, boot, shutdown, volume keys). The catalog is the image's
 * /system/media/audio/ui/hione/index.json. A pick is stored in Settings.Global
 * hione_sound_<category> as the option id or "off"; com.hione.sysbridge (SoundEvents) plays it
 * and turns the stock version of the sound off.
 *
 * Ringtone, notification and alarm keep Android's own pickers, which list the HioneOS files
 * from /system/media/audio. Touch and key clicks are a pack chosen when the image is built.
 */
private object SoundCatalog {
    const val INDEX = "/system/media/audio/ui/hione/index.json"
    const val PREFIX = "hione_sound_"
    const val MASTER = "hione_sounds_enabled"

    class Opt(val id: String, val name: String, val group: String, val voice: String?, val text: String?, val style: String, val path: String)
    class Cat(val id: String, val title: String, val summary: String, val kind: String, val picker: String,
              val default: String, val options: List<Opt>)

    @Volatile private var cache: List<Cat>? = null

    fun load(): List<Cat> = cache ?: runCatching {
        val root = JSONObject(File(INDEX).readText())
        val base = root.optString("device_base", "/system/media/audio")
        val cats = root.getJSONObject("categories")
        cats.keys().asSequence().map { cid ->
            val c = cats.getJSONObject(cid)
            val arr = c.getJSONArray("options")
            Cat(cid, c.optString("title", cid), c.optString("summary"), c.optString("kind"), c.optString("picker", "hione"),
                c.optString("default", "off"),
                (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
                    Opt(o.getString("id"), o.optString("name"), o.optString("group", "Chimes"),
                        o.optString("voice").takeIf { it.isNotBlank() && it != "null" },
                        o.optString("text").takeIf { it.isNotBlank() && it != "null" }, o.optString("style"),
                        "$base/${o.getString("device")}")
                })
        }.toList()
    }.getOrDefault(emptyList()).also { if (it.isNotEmpty()) cache = it }

    fun choice(ctx: Context, c: Cat): String = Settings.Global.getString(ctx.contentResolver, PREFIX + c.id) ?: c.default

    fun choiceLabel(ctx: Context, c: Cat): String = when (val v = choice(ctx, c)) {
        "off" -> "Off"
        "anim" -> "Welcome voice in the boot animation"
        else -> c.options.firstOrNull { it.id == v }?.let { o -> if (o.voice != null) "${o.voice}: ${o.text}" else o.name } ?: "Off"
    }

    fun set(ctx: Context, c: Cat, value: String): Boolean =
        runCatching { Settings.Global.putString(ctx.contentResolver, PREFIX + c.id, value) }.getOrDefault(false)
}

/** One preview at a time, through the same usage the real event uses. */
private object Preview {
    private var mp: MediaPlayer? = null
    fun play(path: String) {
        stop()
        runCatching {
            mp = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(path)
                setOnCompletionListener { stop() }
                prepare()
                start()
            }
        }
    }
    fun stop() { mp?.let { runCatching { it.stop() }; it.release() }; mp = null }
}

private fun findInternalAudio(ctx: Context, path: String): Uri? = runCatching {
    val uri = MediaStore.Audio.Media.INTERNAL_CONTENT_URI
    ctx.contentResolver.query(uri, arrayOf(MediaStore.Audio.Media._ID), "${MediaStore.MediaColumns.DATA}=?", arrayOf(path), null)?.use {
        if (it.moveToFirst()) android.content.ContentUris.withAppendedId(uri, it.getLong(0)) else null
    }
}.getOrNull()

private fun sysProp(key: String): String = runCatching {
    Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, key) as String
}.getOrDefault("")

@Composable
fun HioneSoundsPage() {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    var refresh by remember { mutableIntStateOf(0) }
    val cats = remember { SoundCatalog.load() }
    var pickType by remember { mutableIntStateOf(0) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val uri: Uri? = r.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            if (runCatching { RingtoneManager.setActualDefaultRingtoneUri(ctx, pickType, uri) }.isFailure) toast(ctx, "Sound was not changed")
            refresh++
        }
    }
    fun androidPick(type: Int, title: String) {
        pickType = type
        runCatching {
            picker.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, type)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, title)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, RingtoneManager.getActualDefaultRingtoneUri(ctx, type)))
        }.onFailure { toast(ctx, "Sound picker not available") }
    }
    fun currentName(type: Int): String = runCatching {
        RingtoneManager.getActualDefaultRingtoneUri(ctx, type)?.let { RingtoneManager.getRingtone(ctx, it)?.getTitle(ctx) } ?: "None"
    }.getOrDefault(UNKNOWN)

    DisposableEffect(Unit) { onDispose { Preview.stop() } }

    PageList {
        if (cats.isEmpty()) {
            item { Section { BodyText("This ${Mascot.osName} image has no sound catalog. The sounds come with the image, so this page fills in after an update.") } }
            return@PageList
        }
        item {
            key(refresh) {
                Section {
                    ToggleRow("${Mascot.osName} sounds", "Charger, battery, USB DAC, lock, screenshot and the rest. Silent mode and Do Not Disturb mute them.",
                        runCatching { Settings.Global.getInt(ctx.contentResolver, SoundCatalog.MASTER, 1) }.getOrDefault(1) == 1) {
                        runCatching { Settings.Global.putInt(ctx.contentResolver, SoundCatalog.MASTER, if (it) 1 else 0) }
                        refresh++
                    }
                }
            }
        }
        item {
            key(refresh) {
                Section("Events") {
                    cats.filter { it.picker == "hione" }.forEach { c ->
                        NavRow(c.title, SoundCatalog.choiceLabel(ctx, c)) { nav.push("hione_sound/${c.id}") }
                    }
                }
            }
        }
        item {
            key(refresh) {
                Section("Ringtone, notification and alarm", note = "These use Android's picker. The ${Mascot.osName} sounds start with ${Mascot.name}.") {
                    NavRow("Ringtone", currentName(RingtoneManager.TYPE_RINGTONE)) { androidPick(RingtoneManager.TYPE_RINGTONE, "Ringtone") }
                    NavRow("Notification sound", currentName(RingtoneManager.TYPE_NOTIFICATION)) { androidPick(RingtoneManager.TYPE_NOTIFICATION, "Notification sound") }
                    NavRow("Alarm sound", currentName(RingtoneManager.TYPE_ALARM)) { androidPick(RingtoneManager.TYPE_ALARM, "Alarm sound") }
                    Spacer(Modifier.height(6.dp))
                    ActionButton("Use the ${Mascot.osName} defaults for all three") {
                        var ok = 0
                        listOf("ringtone" to RingtoneManager.TYPE_RINGTONE, "notification" to RingtoneManager.TYPE_NOTIFICATION,
                            "alarm" to RingtoneManager.TYPE_ALARM).forEach { (cid, type) ->
                            val c = cats.firstOrNull { it.id == cid } ?: return@forEach
                            val o = c.options.firstOrNull { it.id == c.default } ?: return@forEach
                            val uri = findInternalAudio(ctx, o.path)
                            if (uri != null && runCatching { RingtoneManager.setActualDefaultRingtoneUri(ctx, type, uri) }.isSuccess) ok++
                        }
                        toast(ctx, if (ok == 3) "Done" else "Set $ok of 3. The media scanner may not have found the rest yet.")
                        refresh++
                    }
                }
            }
        }
        cats.firstOrNull { it.picker == "build" }?.let { c ->
            item {
                Section(c.title, note = "The click pack is chosen when the ${Mascot.osName} image is built. Tap one to hear it.") {
                    val inUse = sysProp("ro.hione.click_pack")
                    c.options.forEach { o ->
                        OptionRow(o.name, if (o.id == inUse) "In use" else null, selected = o.id == inUse, onPreview = { Preview.play(o.path) }) { Preview.play(o.path) }
                    }
                }
            }
        }
    }
}

@Composable
fun HioneSoundCategoryPage(cid: String) {
    val ctx = LocalContext.current
    val c = remember { SoundCatalog.load().firstOrNull { it.id == cid } }
    var sel by remember { mutableStateOf(c?.let { SoundCatalog.choice(ctx, it) } ?: "off") }
    DisposableEffect(Unit) { onDispose { Preview.stop() } }
    if (c == null) { PageList { item { Section { BodyText("This sound is not in this ${Mascot.osName} image.") } } }; return }
    fun pick(v: String, path: String?) {
        if (SoundCatalog.set(ctx, c, v)) sel = v else toast(ctx, "That setting was refused")
        if (path != null) Preview.play(path) else Preview.stop()
    }
    PageList {
        item {
            Section(note = c.summary) {
                if (c.id == "boot") OptionRow("Welcome voice in the boot animation", "No extra sound after start-up", sel == "anim", null) { pick("anim", null) }
                OptionRow("Off", null, sel == "off", null) { pick("off", null) }
            }
        }
        listOf("Chimes", "Voices").forEach { g ->
            val opts = c.options.filter { it.group == g }
            if (opts.isNotEmpty()) item {
                Section(g) {
                    opts.forEach { o ->
                        val title = if (o.voice != null) (o.text ?: o.name) else o.name
                        val style = when (o.style) { "sung", "bank" -> "sung"; "chime" -> "with a chime"; else -> null }
                        val sub = if (o.voice != null) listOfNotNull(o.voice, style).joinToString(", ") else null
                        OptionRow(title, sub, sel == o.id, onPreview = { Preview.play(o.path) }) { pick(o.id, o.path) }
                    }
                }
            }
        }
    }
}

/** Radio row with a separate play button. */
@Composable
private fun OptionRow(title: String, summary: String?, selected: Boolean, onPreview: (() -> Unit)?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (selected) HioneTealBright.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick, colors = RadioButtonDefaults.colors(selectedColor = HioneTealBright, unselectedColor = HioneMuted))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 13.sp)
            if (summary != null) Text(summary, color = HioneMuted, fontSize = 11.sp)
        }
        if (onPreview != null) IconButton(onClick = onPreview) {
            Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = HioneTealBright)
        }
    }
}

/** Page title for hione_sound/<category>. */
fun soundCategoryTitle(cid: String?): String = SoundCatalog.load().firstOrNull { it.id == cid }?.title ?: "Sound"
