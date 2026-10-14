package com.hione.media.recorder

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.media.ui.BareIconButton
import com.hione.media.ui.GlassButton
import com.hione.media.ui.GlassIconButton
import com.hione.media.ui.GlassSegments
import com.hione.media.ui.LabelStyle
import com.hione.media.ui.MessagePane
import com.hione.media.ui.HioneDanger
import com.hione.media.ui.HioneHeader
import com.hione.media.ui.HioneSounds
import com.hione.media.ui.SoundSettingsSheet
import com.hione.media.ui.HioneMuted
import com.hione.media.ui.HionePink
import com.hione.media.ui.HioneSurface1
import com.hione.media.ui.HioneTeal
import com.hione.media.ui.HioneTheme
import com.hione.media.ui.HioneWhite
import com.hione.media.ui.Orbitron
import com.hione.media.ui.formatBytes
import com.hione.media.ui.formatDuration
import com.hione.media.ui.glass
import com.hione.media.ui.hasPermission
import com.hione.media.ui.hioneBackground
import com.hione.media.ui.pressable
import com.hione.media.ui.rememberPermissionGate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * A caller wants one recording back: MediaStore RECORD_SOUND, or a GET_CONTENT for the AMR types
 * old messaging apps attach. [forced] pins the format when the caller asked for a specific type.
 */
data class RecRequest(val forced: RecFormat?, val maxBytes: Long)

class RecorderActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val request = parseRequest(intent)
        setContent {
            HioneTheme {
                RecorderApp(request, onReturn = ::returnRecording)
            }
        }
    }

    private fun parseRequest(i: Intent): RecRequest? {
        val maxBytes = i.getLongExtra(MediaStore.Audio.Media.EXTRA_MAX_BYTES, 0L)
        return when (i.action) {
            MediaStore.Audio.Media.RECORD_SOUND_ACTION -> RecRequest(null, maxBytes)
            Intent.ACTION_GET_CONTENT -> {
                val t = (i.type ?: "").lowercase()
                RecRequest(if (t == "audio/3gpp") RecFormat.THREE_GPP else RecFormat.AMR, maxBytes)
            }
            else -> null
        }
    }

    private fun returnRecording(uri: Uri?) {
        if (uri == null) {
            setResult(Activity.RESULT_CANCELED)
        } else {
            setResult(Activity.RESULT_OK, Intent().setData(uri).apply {
                clipData = ClipData.newRawUri(null, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }
        finish()
    }
}

private const val PREFS = "recorder"
private const val KEY_FORMAT = "format"

@Composable
private fun RecorderApp(request: RecRequest?, onReturn: (Uri?) -> Unit) {
    val ctx = LocalContext.current
    val gate = rememberPermissionGate(
        arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_MEDIA_AUDIO)
    )
    val canRecord = remember(gate.version) { ctx.hasPermission(Manifest.permission.RECORD_AUDIO) }
    LaunchedEffect(Unit) { if (!ctx.hasPermission(Manifest.permission.RECORD_AUDIO)) gate.request() }

    val state by RecorderService.state.collectAsState()
    val prefs = remember { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var format by remember { mutableStateOf(request?.forced ?: RecFormat.entries.getOrElse(prefs.getInt(KEY_FORMAT, 0)) { RecFormat.M4A }) }

    // In request mode, the first recording saved after this screen opened is the answer.
    val seqAtOpen = remember { state.savedSeq }
    val result = if (request != null && state.savedSeq > seqAtOpen) state.lastSaved else null

    val idle = state.status == RecStatus.IDLE
    var showSettings by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { HioneSounds.preload(ctx, "recorder") }

    LaunchedEffect(state.error) {
        state.error?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() }
    }

    BackHandler(enabled = request != null && !idle) {
        // Leaving a for-result recorder mid-take: keep nothing, hand nothing back.
        RecorderService.send(ctx, RecorderService.ACTION_DISCARD)
        onReturn(null)
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().hioneBackground().statusBarsPadding().navigationBarsPadding()) {
        HioneHeader(
            title = "Recorder",
            subtitle = if (request != null) "Record a clip to attach" else null,
            leading = if (request != null) ({ BareIconButton(Icons.Rounded.Close, "Cancel") {
                if (!idle) RecorderService.send(ctx, RecorderService.ACTION_DISCARD)
                onReturn(null)
            } }) else null,
            actions = { BareIconButton(Icons.Rounded.Settings, "Sound settings") { showSettings = true } }
        )

        if (!canRecord) {
            MessagePane(Icons.Rounded.Mic, "Microphone is off", "Allow microphone access to record.", "Allow") { gate.request() }
            return@Column
        }

        if (request?.forced == null) {
            GlassSegments(
                listOf("M4A", "WAV lossless"),
                if (format == RecFormat.WAV) 1 else 0,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 6.dp)
            ) {
                if (idle) {
                    format = if (it == 1) RecFormat.WAV else RecFormat.M4A
                    prefs.edit().putInt(KEY_FORMAT, format.ordinal).apply()
                }
            }
        }

        // ------------- meter panel -------------
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp).glass(24.dp).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                formatDuration(state.elapsedMs),
                fontFamily = Orbitron, fontSize = 44.sp, fontWeight = FontWeight.Bold,
                color = when (state.status) { RecStatus.RECORDING -> HioneWhite; RecStatus.PAUSED -> HioneMuted; else -> HioneWhite.copy(alpha = 0.6f) }
            )
            Text(
                when (state.status) {
                    RecStatus.RECORDING -> state.description
                    RecStatus.PAUSED -> "Paused"
                    RecStatus.ARMING -> "Get ready"
                    RecStatus.IDLE -> if (format == RecFormat.WAV) "WAV, 48 kHz, best quality the mic allows" else format.label
                },
                style = LabelStyle
            )
            Spacer(Modifier.height(12.dp))
            Waveform(Modifier.fillMaxWidth().height(96.dp))
            Spacer(Modifier.height(10.dp))
            LevelBar(Modifier.fillMaxWidth().height(8.dp))
        }

        // ------------- transport -------------
        if (result != null) {
            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Recording saved", color = HioneWhite, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlassButton("Record again", icon = Icons.Rounded.Refresh) {
                        runCatching { ctx.contentResolver.delete(result, null, null) }
                        RecorderService.send(ctx, RecorderService.ACTION_START) {
                            putExtra(RecorderService.EXTRA_FORMAT, format.ordinal)
                            putExtra(RecorderService.EXTRA_MAX_BYTES, request?.maxBytes ?: 0L)
                        }
                    }
                    GlassButton("Use", icon = Icons.Rounded.Check, filled = true) { onReturn(result) }
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(56.dp)) {
                    if (!idle) GlassIconButton(Icons.Rounded.Delete, "Discard", size = 56.dp, tint = HionePink) {
                        RecorderService.send(ctx, RecorderService.ACTION_DISCARD)
                    }
                }
                RecordButton(state.status) {
                    if (idle) {
                        RecorderService.send(ctx, RecorderService.ACTION_START) {
                            putExtra(RecorderService.EXTRA_FORMAT, format.ordinal)
                            putExtra(RecorderService.EXTRA_MAX_BYTES, request?.maxBytes ?: 0L)
                        }
                    } else {
                        RecorderService.send(ctx, RecorderService.ACTION_STOP)
                    }
                }
                Box(Modifier.size(56.dp)) {
                    if (!idle && state.status != RecStatus.ARMING) {
                        val paused = state.status == RecStatus.PAUSED
                        GlassIconButton(if (paused) Icons.Rounded.Mic else Icons.Rounded.Pause, if (paused) "Resume" else "Pause", size = 56.dp) {
                            RecorderService.send(ctx, if (paused) RecorderService.ACTION_RESUME else RecorderService.ACTION_PAUSE)
                        }
                    }
                }
            }
        }

        if (request == null) {
            RecordingList(Modifier.weight(1f))
        }
    }
    if (showSettings) {
        SoundSettingsSheet("recorder", "Recorder", "recording_started", showTimerCue = false) { showSettings = false }
    }
    }
}

@Composable
private fun RecordButton(status: RecStatus, onClick: () -> Unit) {
    val recording = status != RecStatus.IDLE
    Box(
        Modifier.size(88.dp).pressable(onClick = onClick).glass(44.dp, if (recording) HionePink else HioneTeal).padding(14.dp),
        contentAlignment = Alignment.Center
    ) {
        if (recording) {
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(HioneDanger))
        } else {
            Box(Modifier.fillMaxSize().clip(CircleShape).background(Brush.verticalGradient(listOf(HionePink, HioneDanger))))
        }
    }
}

/** Scrolling bars of the last ~5 seconds of input. Drawn straight from the ring buffer. */
@Composable
private fun Waveform(modifier: Modifier) {
    val tick by RecorderBus.waveTick.collectAsState()
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") tick
        val n = RecorderBus.WAVE_SIZE
        val slot = size.width / n
        val bar = (slot * 0.6f).coerceAtLeast(1.5f)
        val mid = size.height / 2f
        val head = RecorderBus.waveHead
        for (i in 0 until n) {
            val v = RecorderBus.wave[(head + i) % n]
            val h = (v * size.height).coerceAtLeast(2f)
            val color = if (v > 0.85f) HionePink else HioneTeal.copy(alpha = 0.45f + 0.55f * (i / n.toFloat()))
            drawRoundRect(
                color,
                topLeft = Offset(i * slot + (slot - bar) / 2f, mid - h / 2f),
                size = Size(bar, h),
                cornerRadius = CornerRadius(bar / 2f)
            )
        }
    }
}

@Composable
private fun LevelBar(modifier: Modifier) {
    val level by RecorderBus.level.collectAsState()
    Canvas(modifier.clip(RoundedCornerShape(4.dp))) {
        drawRect(HioneSurface1)
        drawRect(
            Brush.horizontalGradient(listOf(HioneTeal, HioneTeal, Color(0xFFFFD54F), HionePink), endX = size.width),
            size = Size(size.width * level, size.height)
        )
    }
}

// ---------------------------------------------------------------- list + playback

private class Playback(private val ctx: Context) {
    var playingId by mutableLongStateOf(-1L)
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    var isPlaying by mutableStateOf(false)
    private var mp: MediaPlayer? = null

    fun toggle(r: Recording) {
        if (playingId == r.id && mp != null) {
            val p = mp!!
            if (p.isPlaying) { p.pause(); isPlaying = false } else { p.start(); isPlaying = true }
            return
        }
        release()
        val p = MediaPlayer()
        runCatching {
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            p.setDataSource(ctx, r.uri)
            p.setOnCompletionListener { isPlaying = false; position = 0; it.seekTo(0) }
            p.prepare()
            p.start()
        }.onFailure {
            p.release()
            Toast.makeText(ctx, "Cannot play this recording", Toast.LENGTH_SHORT).show()
            return
        }
        mp = p
        playingId = r.id
        duration = p.duration.toLong()
        isPlaying = true
    }

    fun seek(ms: Long) { mp?.seekTo(ms.toInt()); position = ms }
    fun poll() { mp?.let { runCatching { position = it.currentPosition.toLong() } } }

    fun release() {
        mp?.release(); mp = null
        playingId = -1; isPlaying = false; position = 0
    }
}

@Composable
private fun RecordingList(modifier: Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<Recording>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) { items = RecordingsRepo.list(ctx) }
    LaunchedEffect(Unit) { RecordingsRepo.changes(ctx).collectLatest { reload++ } }

    val playback = remember { Playback(ctx) }
    DisposableEffect(Unit) { onDispose { playback.release() } }
    LaunchedEffect(playback.isPlaying) {
        while (playback.isPlaying) { playback.poll(); delay(200) }
    }

    var renaming by remember { mutableStateOf<Recording?>(null) }
    var pendingRename by remember { mutableStateOf<Pair<Recording, String>?>(null) }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val pr = pendingRename
        pendingRename = null
        if (r.resultCode == Activity.RESULT_OK && pr != null) {
            scope.launch { RecordingsRepo.rename(ctx, pr.first, pr.second); reload++ }
        } else reload++
    }

    Column(modifier) {
        Text("RECORDINGS", style = LabelStyle.copy(fontFamily = Orbitron, color = HioneTeal, fontSize = 12.sp), modifier = Modifier.padding(start = 18.dp, bottom = 6.dp))
        if (items.isEmpty()) {
            Text("Nothing recorded yet.", style = LabelStyle, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(items, key = { it.id }) { r ->
                val active = playback.playingId == r.id
                Column(Modifier.fillMaxWidth().glass(18.dp, if (active) HionePink else HioneTeal).padding(start = 6.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BareIconButton(if (active && playback.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            if (active && playback.isPlaying) "Pause" else "Play", tint = HioneTeal) { playback.toggle(r) }
                        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                            Text(r.name.substringBeforeLast('.'), color = HioneWhite, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOf(
                                    formatDuration(r.durationMs),
                                    r.name.substringAfterLast('.', "").uppercase(),
                                    formatBytes(r.size),
                                    DateFormat.getDateInstance(DateFormat.SHORT).format(Date(r.dateAdded))
                                ).filter { it.isNotEmpty() }.joinToString("  ·  "),
                                style = LabelStyle.copy(fontSize = 12.sp), maxLines = 1
                            )
                        }
                        BareIconButton(Icons.Rounded.Share, "Share") { share(ctx, r) }
                        BareIconButton(Icons.Rounded.Edit, "Rename") { renaming = r }
                        BareIconButton(Icons.Rounded.Delete, "Delete", tint = HionePink) {
                            if (active) playback.release()
                            scope.launch {
                                when (val w = RecordingsRepo.delete(ctx, r)) {
                                    is WriteResult.NeedsConsent -> consent.launch(IntentSenderRequest.Builder(w.sender).build())
                                    else -> reload++
                                }
                            }
                        }
                    }
                    if (active) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 10.dp)) {
                            Text(formatDuration(playback.position), style = LabelStyle.copy(fontSize = 12.sp))
                            Slider(
                                value = if (playback.duration > 0) (playback.position.toFloat() / playback.duration).coerceIn(0f, 1f) else 0f,
                                onValueChange = { playback.seek((it * playback.duration).toLong()) },
                                modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                                colors = SliderDefaults.colors(thumbColor = HionePink, activeTrackColor = HionePink, inactiveTrackColor = HioneMuted.copy(alpha = 0.4f))
                            )
                            Text(formatDuration(playback.duration), style = LabelStyle.copy(fontSize = 12.sp))
                        }
                    }
                }
            }
        }
    }

    renaming?.let { r ->
        var text by remember(r.id) { mutableStateOf(r.name.substringBeforeLast('.')) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            containerColor = HioneSurface1,
            title = { Text("Rename", color = HioneWhite) },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = HioneTeal, cursorColor = HioneTeal, focusedTextColor = HioneWhite, unfocusedTextColor = HioneWhite)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    renaming = null
                    scope.launch {
                        when (val w = RecordingsRepo.rename(ctx, r, text)) {
                            is WriteResult.NeedsConsent -> {
                                pendingRename = r to text
                                consent.launch(IntentSenderRequest.Builder(w.sender).build())
                            }
                            WriteResult.Failed -> Toast.makeText(ctx, "Could not rename", Toast.LENGTH_SHORT).show()
                            WriteResult.Done -> reload++
                        }
                    }
                }) { Text("Save", color = HioneTeal) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel", color = HioneMuted) } }
        )
    }
}

private fun share(ctx: Context, r: Recording) {
    val send = Intent(Intent.ACTION_SEND).setType(r.mime).putExtra(Intent.EXTRA_STREAM, r.uri).apply {
        clipData = ClipData.newRawUri(null, r.uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(send, null))
}
