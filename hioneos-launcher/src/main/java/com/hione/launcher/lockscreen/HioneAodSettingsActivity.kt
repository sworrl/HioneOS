package com.hione.launcher.lockscreen

import com.hione.launcher.Mascot
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.launcher.AudiowideFont
import com.hione.launcher.HioneCyan
import com.hione.launcher.HioneNeonPink

/** AOD options: engage mode (off / while charging / always) + what the AOD face shows. */
class HioneAodSettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val ctx = LocalContext.current
            var mode by remember { mutableStateOf(HioneLockscreenPrefs.getAodMode(ctx)) }
            var showWeather by remember { mutableStateOf(HioneLockscreenPrefs.getAodShowWeather(ctx)) }
            var showNp by remember { mutableStateOf(HioneLockscreenPrefs.getAodShowNowPlaying(ctx)) }

            Box(Modifier.fillMaxSize().background(Mascot.color(0xFF0C0D10))) {
                Column(Modifier.fillMaxSize().padding(18.dp)) {
                    Text(
                        "ALWAYS-ON DISPLAY",
                        color = HioneCyan, fontSize = 16.sp,
                        fontWeight = FontWeight.Black, fontFamily = AudiowideFont
                    )
                    Text(
                        "Clock, weather and now-playing on a pure-black face at minimum panel brightness. \"While charging\" costs no battery.",
                        color = Color(0xFF89ACA7), fontSize = 10.sp
                    )
                    Spacer(Modifier.height(16.dp))

                    listOf(
                        HioneLockscreenPrefs.AOD_OFF to "Off",
                        HioneLockscreenPrefs.AOD_CHARGING to "While charging",
                        HioneLockscreenPrefs.AOD_ALWAYS to "Always"
                    ).forEach { (id, label) ->
                        val selected = mode == id
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (selected) Mascot.color(0x33FFC56B) else Mascot.color(0xCC07131A))
                                .border(
                                    1.dp,
                                    if (selected) HioneCyan else Color(0x33FFFFFF),
                                    RoundedCornerShape(12.dp)
                                )
                                .clickable {
                                    mode = id
                                    HioneLockscreenPrefs.setAodMode(ctx, id)
                                }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                label,
                                color = if (selected) HioneCyan else Color.White,
                                fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                fontFamily = AudiowideFont
                            )
                        }
                    }

                    Spacer(Modifier.height(18.dp))
                    Text("AOD FACE", color = HioneNeonPink, fontSize = 12.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
                    Spacer(Modifier.height(6.dp))

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Weather line", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Switch(
                            checked = showWeather,
                            onCheckedChange = { showWeather = it; HioneLockscreenPrefs.setAodShowWeather(ctx, it) },
                            colors = SwitchDefaults.colors(checkedThumbColor = HioneCyan, checkedTrackColor = Mascot.color(0xFF00695C))
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Now playing", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Switch(
                            checked = showNp,
                            onCheckedChange = { showNp = it; HioneLockscreenPrefs.setAodShowNowPlaying(ctx, it) },
                            colors = SwitchDefaults.colors(checkedThumbColor = HioneNeonPink, checkedTrackColor = Mascot.color(0xFF880E4F))
                        )
                    }
                }
            }
        }
    }
}
