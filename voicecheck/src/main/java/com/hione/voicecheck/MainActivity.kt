package com.hione.voicecheck

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Play every generated clip on the device and mark it: Like, or Fix with a note.
 *
 * One verdict per file (a line in three voices is three files, because a voice can garble a word
 * the others say fine). Verdicts are written to getExternalFilesDir()/verdicts.json on every
 * change, so `adb pull /sdcard/Android/data/com.hione.voicecheck/files/verdicts.json` hands them
 * straight to tools/voice for re-rendering.
 */
class MainActivity : ComponentActivity() {

    private val teal = Color(0xFFFFAB2E)
    private val pink = Color(0xFF8EC5FF)
    private val bg = Color(0xFF111216)
    private val card = Color(0xFF13232A)

    /** One playable file. */
    data class Clip(val key: String, val app: String, val id: String, val voice: String?, val text: String, val path: String)
    data class Verdict(val state: String, val note: String) // state: like | fix

    /**
     * Clips the off-device speech check misheard in the first build (tools/voice, 2026-10-10).
     * All of them were re-rendered and now pass. Still badged so they get a listen first; they
     * are not pre-judged.
     */
    private val suspect = mapOf(
        "cyber:iem" to "re-rendered, speech check now hears \"IEMs\" (was \"IAMs\" / \"I am\")",
        "cyber:mode" to "re-rendered, speech check now hears \"mode\" (was \"mood\")",
        "hoshi:wifi_connected" to "re-rendered, speech check now hears \"Wi-Fi connected\" (was \"Wife I connected\")",
        "cyber:say_cheese" to "re-rendered, speech check now hears \"Say cheese!\" (was \"Cheezer\")",
        "cyber:count_2" to "re-rendered, speech check now hears \"Two\" (was \"To\")",
        "cyber:countdown_timed" to "rebuilt with the new \"Two\", speech check hears \"3, 2, 1\"",
        "cyber:miss" to "re-rendered, speech check now hears \"Miss\" (was \"Mess up\")",
        "cyber:fm_radio_on" to "new take, speech check hears \"FM radio on\"",
    )

    /** Problems the off-device checks recorded for OS sounds (os_sounds/index.json), by clip key. */
    private val osNotes = HashMap<String, String>()

    private fun suspectNote(c: Clip): String? = osNotes[c.key] ?: suspect.entries.firstOrNull { (k, _) ->
        val (v, part) = k.split(":")
        c.voice == v && c.id.contains(part)
    }?.value

    private var player: MediaPlayer? = null

    private fun play(path: String) {
        player?.release()
        player = runCatching {
            val afd = assets.openFd(path)
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                setOnCompletionListener { it.release(); if (player === it) player = null }
                prepare(); start()
            }
        }.getOrNull()
    }

    private fun loadClips(): List<Clip> {
        val idx = JSONObject(assets.open("sounds_index.json").bufferedReader().readText())
        val out = ArrayList<Clip>()
        val voice = idx.getJSONArray("voice")
        for (i in 0 until voice.length()) {
            val r = voice.getJSONObject(i)
            val files = r.getJSONObject("files")
            for (v in files.keys()) {
                val path = files.getString(v)
                out += Clip("$path", r.getString("app"), r.getString("id") + if (r.optInt("alt") > 0) " (alt ${r.optInt("alt")})" else "",
                    v, r.optString("text"), path)
            }
        }
        val sfx = idx.getJSONArray("sfx")
        for (i in 0 until sfx.length()) {
            val r = sfx.getJSONObject(i)
            val path = r.getString("file")
            out += Clip(path, r.getString("app"), r.getString("id"), null, "(sound effect)", path)
        }
        out += loadOsSounds()
        return out
    }

    /**
     * HioneOS system sounds (charger, lock, screenshot, ringtones...), from os_sounds/index.json.
     * app is "os:<category>"; voiced options carry their voice name, chimes show as sfx.
     */
    private fun loadOsSounds(): List<Clip> = runCatching {
        val idx = JSONObject(assets.open("os_sounds/index.json").bufferedReader().readText())
        val cats = idx.getJSONObject("categories")
        val out = ArrayList<Clip>()
        for (cid in cats.keys()) {
            val c = cats.getJSONObject(cid)
            val opts = c.getJSONArray("options")
            for (i in 0 until opts.length()) {
                val o = opts.getJSONObject(i)
                val voice = o.optString("voice").takeIf { it.isNotBlank() && it != "null" }?.lowercase()
                val pack = o.optJSONObject("pack")
                if (pack != null) {
                    for (f in pack.keys()) {
                        val path = "os_sounds/" + pack.getJSONObject(f).getString("file")
                        out += Clip(path, "os:$cid", "${o.getString("id")}/$f", null, "${o.optString("name")} ($f)", path)
                    }
                    continue
                }
                val path = "os_sounds/" + o.getString("file")
                o.optJSONArray("problems")?.let { pr ->
                    if (pr.length() > 0) osNotes[path] = "check: " + (0 until pr.length()).joinToString(" / ") { pr.getString(it) }
                }
                val text = if (voice != null) o.optString("text") + (o.optString("style").takeIf { it == "sung" || it == "bank" }?.let { " (sung)" }
                    ?: if (o.optString("style") == "chime") " (with chime)" else "") else o.optString("name")
                out += Clip(path, "os:$cid", o.getString("id"), voice, text, path)
            }
        }
        out
    }.getOrDefault(emptyList())

    private val store by lazy { File(getExternalFilesDir(null), "verdicts.json") }

    private fun loadVerdicts(): Map<String, Verdict> = runCatching {
        val o = JSONObject(store.readText())
        o.keys().asSequence().associateWith { k -> o.getJSONObject(k).let { Verdict(it.getString("state"), it.optString("note")) } }
    }.getOrDefault(emptyMap())

    private fun saveVerdicts(all: Map<String, Verdict>, clips: List<Clip>) {
        val byKey = clips.associateBy { it.key }
        val o = JSONObject()
        for ((k, v) in all) {
            val c = byKey[k]
            o.put(k, JSONObject().put("state", v.state).put("note", v.note)
                .put("app", c?.app).put("id", c?.id).put("voice", c?.voice ?: "sfx").put("text", c?.text))
        }
        store.writeText(o.toString(2))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val clips = loadClips()
        setContent {
            var verdicts by remember { mutableStateOf(loadVerdicts()) }
            var filter by remember { mutableStateOf("todo") }
            var voiceFilter by remember { mutableStateOf("all") }
            var editing by remember { mutableStateOf<String?>(null) }
            val shown = clips.filter {
                (voiceFilter == "all" || (it.voice ?: "sfx") == voiceFilter || (voiceFilter == "os" && it.app.startsWith("os:"))) && when (filter) {
                    "todo" -> verdicts[it.key] == null
                    "like" -> verdicts[it.key]?.state == "like"
                    "fix" -> verdicts[it.key]?.state == "fix"
                    "check" -> suspectNote(it) != null
                    else -> true
                }
            }
            fun set(key: String, v: Verdict?) {
                verdicts = if (v == null) verdicts - key else verdicts + (key to v)
                saveVerdicts(verdicts, clips)
            }
            Column(Modifier.fillMaxSize().background(bg).padding(12.dp)) {
                Text("Voice Check", color = teal, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                val liked = verdicts.values.count { it.state == "like" }
                val fix = verdicts.values.count { it.state == "fix" }
                Text("${clips.size} clips · $liked liked · $fix to fix · ${clips.size - liked - fix} left",
                    color = Color(0xFFB8D8D4), fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("todo" to "Left", "check" to "Check first", "fix" to "Fix", "like" to "Liked", "all" to "All").forEach { (k, l) ->
                        Chip(l, filter == k) { filter = k }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Voices come from the clips, so a new voicebank gets its own chip. "os" is every HioneOS system sound.
                    val chips = remember { listOf("all", "os") + clips.map { it.voice ?: "sfx" }.distinct().sortedBy { it == "sfx" } }
                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(chips) { k -> Chip(if (k == "os") "OS sounds" else k.replaceFirstChar { it.uppercase() }, voiceFilter == k) { voiceFilter = k } }
                    }
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(shown, key = { it.key }) { c ->
                        val v = verdicts[c.key]
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(card)
                            .border(1.dp, when (v?.state) { "like" -> teal; "fix" -> pink; else -> Color(0x33FFAB2E) }, RoundedCornerShape(14.dp))
                            .padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(52.dp).clip(RoundedCornerShape(26.dp)).background(teal).clickable { play(c.path) },
                                    contentAlignment = Alignment.Center) {
                                    Icon(Icons.Filled.PlayArrow, "Play", tint = Color.Black, modifier = Modifier.size(30.dp))
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(c.text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                    Text("${c.app} · ${c.id} · ${c.voice ?: "sfx"}", color = Color(0xFF8FB3AE), fontSize = 11.sp)
                                    suspectNote(c)?.let { Text(it, color = Color(0xFFFFC266), fontSize = 11.sp) }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Pill("Like", Icons.Filled.Favorite, teal, v?.state == "like") {
                                    set(c.key, if (v?.state == "like") null else Verdict("like", ""))
                                }
                                Pill("Fix", Icons.Filled.Build, pink, v?.state == "fix") {
                                    set(c.key, Verdict("fix", v?.note ?: "")); editing = c.key
                                }
                            }
                            if (v?.state == "fix" || editing == c.key) {
                                var note by remember(c.key) { mutableStateOf(v?.note ?: "") }
                                Spacer(Modifier.height(8.dp))
                                BasicTextField(
                                    value = note,
                                    onValueChange = { note = it; set(c.key, Verdict("fix", it)) },
                                    textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                                    cursorBrush = SolidColor(pink),
                                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF1C3038)).padding(10.dp),
                                    decorationBox = { inner ->
                                        if (note.isEmpty()) Text("What's wrong? (word, tone, speed, artifact)", color = Color(0xFF6E8F8A), fontSize = 14.sp)
                                        inner()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
        Text(label, color = if (on) Color.Black else teal, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) teal else Color(0xFF16302F))
                .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp))
    }

    @Composable
    private fun Pill(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, on: Boolean, onClick: () -> Unit) {
        Row(Modifier.clip(RoundedCornerShape(20.dp)).background(if (on) color else Color(0xFF1C3038))
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (on) Color.Black else color, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, color = if (on) Color.Black else color, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }

    override fun onDestroy() { player?.release(); super.onDestroy() }
}
