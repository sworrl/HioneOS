package com.hione.media.ui

import com.hione.media.Mascot
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Sound settings sheet, shared by the three apps. Voice choice and the Sounds switch are global
 * (one setting for the whole Hione Media package); "Spoken lines" is per app, because a voice that
 * is welcome in the camera can be one too many in the gallery.
 */
@Composable
fun SoundSettingsSheet(
    appName: String,
    appLabel: String,
    previewLine: String,
    showTimerCue: Boolean,
    onClose: () -> Unit
) {
    val ctx = LocalContext.current
    val prefs = remember { HioneSounds.prefs(ctx) }
    val scope = rememberCoroutineScope()
    var sounds by remember { mutableStateOf(HioneSounds.soundsOn) }
    var voice by remember { mutableStateOf(HioneSounds.voice) }
    var lines by remember { mutableStateOf(HioneSounds.linesOn(appName)) }
    var cueVoice by remember { mutableStateOf(HioneSounds.timerCueVoice) }

    BackHandler(onBack = onClose)

    Box(
        Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose)
    ) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(10.dp)
                .glass(24.dp, fillAlpha = 0.92f)
                .clickable(remember { MutableInteractionSource() }, indication = null) { }
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("SOUND", style = TitleStyle.copy(fontSize = 15.sp), modifier = Modifier.weight(1f))
                BareIconButton(Icons.Rounded.Close, "Close", tint = HioneTeal, onClick = onClose)
            }

            SwitchRow("Sounds", if (appName == "camera") "Shutter, beeps and chimes" else "Chimes and effects", sounds) {
                sounds = it; prefs.edit().putBoolean(HioneSounds.KEY_SOUNDS, it).apply()
            }

            Spacer(Modifier.height(12.dp))
            Text("Voice", color = HioneWhite, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            val options = listOf("Off") + HioneSounds.VOICES.map { HioneSounds.voiceLabel(it) }
            GlassSegments(options, voice?.let { HioneSounds.VOICES.indexOf(it) + 1 } ?: 0) { i ->
                val v = if (i == 0) "off" else HioneSounds.VOICES[i - 1]
                prefs.edit().putString(HioneSounds.KEY_VOICE, v).apply()
                voice = v.takeIf { it != "off" }
                // Let people hear the voice they just picked.
                voice?.let { picked -> scope.launch { HioneSounds.speak(appName, previewLine, voiceOverride = picked, force = true) } }
            }

            if (voice != null) {
                Spacer(Modifier.height(4.dp))
                SwitchRow("Spoken lines in $appLabel", null, lines) {
                    lines = it; prefs.edit().putBoolean(HioneSounds.linesKey(appName), it).apply()
                }
            }

            if (showTimerCue) {
                Spacer(Modifier.height(12.dp))
                Text("Self-timer cue", color = HioneWhite, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (voice == null && cueVoice) "Uses beeps while the voice is off." else "Counts down out loud or with beeps, never both.",
                    style = LabelStyle
                )
                Spacer(Modifier.height(6.dp))
                GlassSegments(listOf("Voice", "Beeps"), if (cueVoice) 0 else 1) { i ->
                    cueVoice = i == 0
                    prefs.edit().putString(HioneSounds.KEY_TIMER_CUE, if (cueVoice) "voice" else "beeps").apply()
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = HioneWhite, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, style = LabelStyle)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Mascot.color(0xFF00201D), checkedTrackColor = HioneTeal)
        )
    }
}
