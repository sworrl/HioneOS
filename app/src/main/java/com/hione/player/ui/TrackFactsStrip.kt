package com.hione.player.ui

import com.hione.player.Mascot
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.exoplayer.ExoPlayer
import com.hione.player.AudiowideFont
import com.hione.player.CirrusLogicManager
import com.hione.player.Haptics
import com.hione.player.LikeStore
import com.hione.player.HioneDirectAudio
import com.hione.player.HioneGold
import com.hione.player.HionePink
import com.hione.player.HionePowerGovernor
import com.hione.player.HioneTeal
import com.hione.player.HioneTealBright
import com.hione.player.HioneUsbDacOutput
import com.hione.player.Muted
import com.hione.player.OrbitronFont
import com.hione.player.PlayerPreferences
import com.hione.player.Track
import com.hione.player.TrackTech
import com.hione.player.bpm.HioneBpmAnalyzer
import com.hione.player.releaseTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/** One line of the data-verbose strip. Every value here is REAL — a fact is simply omitted when
 *  its source has nothing (no "—", no guesses, no defaults). */
data class TrackFact(val label: String, val value: String, val color: Color = Mascot.color(0xFFD4ECE9))

/**
 * Real audio facts for the current track, gathered from the sources the rest of the app already
 * trusts: MediaStore/Track (codec, bitrate, size, duration), [TrackTech] (true bit depth + sample
 * rate parsed from the file header, the same source as the quality badges), the live ExoPlayer
 * decoded format, AudioManager's actual output device, the CS43198 DAC's own sysfs nodes (read
 * directly — never through su), the HiBy HAL's direct-output flag, [HioneUsbDacOutput], and
 * [HioneBpmAnalyzer] (real onset analysis, only when it returns a tempo).
 */
object TrackFacts {
    private const val SYSFS = CirrusLogicManager.SYSFS_BASE

    /** Plain file read of a DAC sysfs node — null when unreadable. NO RootShell fallback (no su). */
    private fun sysfs(node: String): String? = runCatching {
        val f = File("$SYSFS/$node")
        if (f.exists() && f.canRead()) f.readText().trim().ifBlank { null } else null
    }.getOrNull()

    // Real BPM cache (path → bpm) so a re-expand doesn't re-decode; only real analyzer results land here.
    private val bpmCache = object : android.util.LruCache<String, Float>(64) {}
    @Volatile private var bpmInFlight: String? = null

    fun cachedBpm(path: String): Float? = bpmCache.get(path)

    /** Runs the REAL analyzer off-thread (decode + onset autocorrelation). Null = no tempo found. */
    suspend fun resolveBpm(path: String): Float? {
        if (path.isBlank()) return null
        bpmCache.get(path)?.let { return it }
        if (bpmInFlight == path) return null
        bpmInFlight = path
        return try {
            withContext(Dispatchers.IO) { runCatching { HioneBpmAnalyzer.analyze(path) }.getOrNull() }
                ?.takeIf { it in 40f..260f }
                ?.also { bpmCache.put(path, it) }
        } finally { if (bpmInFlight == path) bpmInFlight = null }
    }

    private fun encodingLabel(enc: Int): String? = when (enc) {
        AudioFormat.ENCODING_PCM_16BIT -> "16-bit PCM"
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> "24-bit PCM"
        AudioFormat.ENCODING_PCM_32BIT -> "32-bit PCM"
        AudioFormat.ENCODING_PCM_FLOAT -> "32-bit float"
        AudioFormat.ENCODING_PCM_8BIT -> "8-bit PCM"
        else -> null
    }

    private fun deviceTypeLabel(t: Int): String? = when (t) {
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Headphone jack"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "Line out"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Built-in speaker"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        26 /* TYPE_BLE_HEADSET */ -> "Bluetooth LE Audio"
        27 /* TYPE_BLE_SPEAKER */ -> "Bluetooth LE speaker"
        else -> null
    }

    /** Priority when several outputs are attached: what the framework would actually route to. */
    private fun routePriority(t: Int): Int = when (t) {
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> 0
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, 26, 27 -> 1
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_LINE_ANALOG -> 2
        AudioDeviceInfo.TYPE_HDMI -> 3
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 4
        else -> 9
    }

    /** MAIN THREAD (touches the player). Cheap: a handful of getters + one sysfs read per DAC line. */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun collect(ctx: Context, track: Track, player: ExoPlayer?): List<TrackFact> {
        val out = ArrayList<TrackFact>(16)
        val app = ctx.applicationContext

        // ---- The file ----
        val fmt = track.mime.substringAfterLast('/').uppercase().removePrefix("X-")
        if (fmt.isNotBlank()) out += TrackFact("Container", fmt, HioneTealBright)
        val bits = TrackTech.bitsFor(app, track)
        val sr = TrackTech.sampleRateFor(app, track)
        if (bits != null && bits > 0) out += TrackFact("Bit depth", "$bits-bit", TrackTech.color(bits))
        if (sr != null && sr > 0) out += TrackFact("Sample rate", TrackTech.formatSampleRate(sr), TrackTech.rateColor(sr))
        if (track.bitrateKbps > 0) out += TrackFact("Bitrate", "${track.bitrateKbps} kbps")
        if (track.sizeBytes > 0) out += TrackFact("File size", "%.1f MB".format(track.sizeBytes / 1e6))
        if (track.durationMs > 0) {
            val s = track.durationMs / 1000
            out += TrackFact("Length", "${s / 60}:${(s % 60).toString().padStart(2, '0')}")
        }
        releaseTag(track.album)?.let { out += TrackFact("Pressing", it, com.hione.player.ReleaseTagColor) }
        if (track.isDiscImage || track.parentId > 0L) out += TrackFact("Source", if (track.parentId > 0L) "CUE track of disc image" else "Whole-disc image", com.hione.player.DiscImageColor)
        if (TrackTech.isVinyl(track)) out += TrackFact("Master", "Vinyl rip", HioneGold)

        // ---- What the decoder is actually producing right now ----
        runCatching {
            val f = player?.audioFormat
            if (f != null) {
                val codec = (f.codecs ?: f.sampleMimeType?.substringAfterLast('/'))?.uppercase()
                if (!codec.isNullOrBlank()) out += TrackFact("Decoder", codec, HioneTeal)
                val parts = ArrayList<String>(3)
                if (f.sampleRate > 0) parts += TrackTech.formatSampleRate(f.sampleRate)
                encodingLabel(f.pcmEncoding)?.let { parts += it }
                if (f.channelCount > 0) parts += when (f.channelCount) { 1 -> "mono"; 2 -> "stereo"; else -> "${f.channelCount}ch" }
                if (parts.isNotEmpty()) out += TrackFact("Decoded PCM", parts.joinToString(" · "), HioneTeal)
            }
        }

        // ---- Where it is going ----
        val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        var routeType = -1
        runCatching {
            val devs = am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.toList().orEmpty()
            val d = devs.filter { deviceTypeLabel(it.type) != null }.minByOrNull { routePriority(it.type) }
            if (d != null) {
                routeType = d.type
                val name = d.productName?.toString()?.trim().orEmpty()
                val typeLabel = deviceTypeLabel(d.type) ?: "Output"
                val isBt = d.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || d.type == 26 || d.type == 27
                val shown = if (name.isNotBlank() && !name.equals(android.os.Build.MODEL, true) && isBt) "$typeLabel · $name" else typeLabel
                out += TrackFact("Output", shown, if (isBt) HionePink else HioneTealBright)
                val maxSr = d.sampleRates.maxOrNull()
                if (isBt && maxSr != null && maxSr > 0) out += TrackFact("Link rate", "up to ${TrackTech.formatSampleRate(maxSr)}", HionePink)
            }
        }
        if (HioneUsbDacOutput.routed) {
            HioneUsbDacOutput.device?.let { d ->
                val mixer = HioneUsbDacOutput.mixerMode.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
                out += TrackFact("USB DAC", "${d.name} · ${d.bitsLabel} ${d.ratesLabel}$mixer".trim(), HioneGold)
            }
        }
        runCatching { am?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() }.getOrNull()
            ?.takeIf { it > 0 }?.let { out += TrackFact("Mixer rate", TrackTech.formatSampleRate(it)) }
        runCatching {
            val st = HioneDirectAudio.status(app)
            val holder = st.holderProcess.trim()
            val ours = holder.isNotBlank() && holder.contains("hione", true)
            out += TrackFact(
                "Direct path",
                when {
                    st.directFlagEnabled && ours -> "ON · bit-perfect (held by us)"
                    st.directFlagEnabled && holder.isNotBlank() -> "ON · held by $holder"
                    st.directFlagEnabled -> "ON"
                    else -> "OFF · Android mixer"
                },
                if (st.directFlagEnabled) HioneGold else Muted
            )
        }

        // ---- The DAC itself (only meaningful on the analog outputs) ----
        val analog = routeType == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || routeType == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            routeType == AudioDeviceInfo.TYPE_LINE_ANALOG || routeType == -1
        if (analog) {
            sysfs("out_mode")?.let { v ->
                CirrusLogicManager.OutputMode.values().firstOrNull { it.sysfsValue == v.lowercase() }?.let { out += TrackFact("DAC output", it.label, HioneTealBright) }
            }
            sysfs("digital_filter")?.let { v ->
                CirrusLogicManager.DigitalFilter.values().firstOrNull { it.id == v.lowercase() }?.let { out += TrackFact("DAC filter", it.label) }
            }
            sysfs("gain")?.let { v ->
                CirrusLogicManager.GainMode.values().firstOrNull { it.sysfsValue == v.lowercase() }?.let { out += TrackFact("Gain", it.label) }
            }
            sysfs("dre_mode")?.let { v -> out += TrackFact("DRE", if (v == "dremode_enable" || v == "1") "On" else "Off") }
            sysfs("high_power_mode")?.let { v -> out += TrackFact("High power", if (v == "hpower_enable" || v == "1") "On" else "Off") }
        }

        // ---- Listening history ----
        cachedBpm(track.path)?.let { out += TrackFact("Tempo", "${it.toInt()} BPM", HionePink) }
        val hearts = LikeStore.heartCount(app, track.id)
        if (hearts > 0) out += TrackFact("Hearts", hearts.toString(), HionePink)
        val plays = PlayerPreferences.loadPlayCount(app, track.id)
        if (plays > 0) out += TrackFact("Plays", plays.toString())
        val folder = track.path.substringBeforeLast('/', "").substringAfterLast('/')
        if (folder.isNotBlank()) out += TrackFact("Folder", folder, Muted)
        return out
    }
}

/**
 * The data-verbose strip: a slim "TRACK FACTS" toggle row that expands into a two-column ledger
 * of the real facts above. Refreshes every 2 s while open (route/decoder state can change under
 * it) and kicks the real BPM analysis on first open — the tempo line appears only if it resolves.
 */
@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun TrackFactsStrip(
    track: Track,
    player: ExoPlayer?,
    expanded: Boolean,
    accent: Color,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    var facts by remember(track.id) { mutableStateOf<List<TrackFact>>(emptyList()) }
    LaunchedEffect(track.id, expanded) {
        if (!expanded) return@LaunchedEffect
        facts = TrackFacts.collect(ctx, track, player)
        // Real tempo: decode ~22 s + onset analysis (HioneBpmAnalyzer) on the IO pool — a null
        // result simply adds no line. Gated: a 2.5 s settle so skipping through tracks never
        // queues decodes, and only while the power governor allows background work.
        if (TrackFacts.cachedBpm(track.path) == null) {
            delay(2500)
            if (HionePowerGovernor.allowBackgroundWork) TrackFacts.resolveBpm(track.path)
        }
        while (true) {
            facts = TrackFacts.collect(ctx, track, player)
            delay(2000)
        }
    }

    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { Haptics.tick(ctx); onToggle() }
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("TRACK FACTS", color = accent, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp, fontFamily = AudiowideFont)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Default.ExpandMore, if (expanded) "Collapse" else "Expand", tint = accent, modifier = Modifier.size(16.dp).rotate(if (expanded) 180f else 0f))
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Mascot.color(0x66020A0C))
                    .padding(horizontal = 8.dp, vertical = 5.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (facts.isEmpty()) {
                    Text("Reading…", color = Muted, fontSize = 10.sp)
                }
                facts.forEach { f ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            f.label.uppercase(), color = Muted, fontSize = 8.5.sp, fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp, modifier = Modifier.width(78.dp), maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            f.value, color = f.color, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, fontFamily = OrbitronFont,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}
