package com.hione.launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * KDE Compositing control menu. For each transition EVENT (lock, app-open, settings,
 * …) pick "VARIED" (cycle the palette) or pin one effect, and PREVIEW it live — the
 * preview launches a throwaway activity using that exact window animation so you see
 * the real thing on-device. Choices persist via [HioneCompositing].
 */
class HioneCompositingMenuActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HioneCompositing.attach(this)
        setContent { CompositingMenu(this) }
    }
}

private val HioneTeal get() = Mascot.color(0xFFFFC56B)
private val HionePink get() = Mascot.color(0xFFFF80AB)
private val PanelBg = Color(0xFF0A0F1A)

@androidx.compose.runtime.Composable
private fun CompositingMenu(activity: Activity) {
    val events = remember { HioneCompositing.allEvents() }
    val effects = remember { HioneCompositing.allEffects() }
    // recomposition key bumped whenever a choice is saved
    var rev by remember { mutableIntStateOf(0) }

    Column(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF04070E), Color(0xFF0E0A18))))
            .padding(16.dp)
    ) {
        Text("KDE COMPOSITING", color = HioneTeal, fontSize = 20.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
        Text("Window transitions for each event. Pick VARIED or pin one, then Preview.",
            color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
        Spacer(Modifier.height(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.weight(1f)) {
            items(events) { ev ->
                val current = remember(rev, ev) {
                    HioneCompositing.run { attach(activity); }
                    activity.getSharedPreferences(HioneCompositing.PREFS, 0).getString(ev.name, "VARIED") ?: "VARIED"
                }
                Column(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(PanelBg)
                        .border(1.dp, HioneTeal.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
                        .padding(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(ev.name, color = HionePink, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(current, color = HioneTeal, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // VARIED chip
                        item { Chip("VARIED", selected = current == "VARIED") {
                            HioneCompositing.setChoice(activity, ev, "VARIED"); rev++
                        } }
                        items(effects) { fx ->
                            Chip(fx.name, selected = current == fx.name) {
                                HioneCompositing.setChoice(activity, ev, fx.name); rev++
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier.clip(RoundedCornerShape(10.dp))
                            .background(HioneTeal.copy(alpha = 0.15f))
                            .border(1.dp, HioneTeal, RoundedCornerShape(10.dp))
                            .clickable {
                                // Launch the throwaway preview activity with THIS event's effect
                                // so the actual window animation plays.
                                val fx = HioneCompositing.nextEffect(ev)
                                val i = Intent(activity, HioneCompositingPreviewActivity::class.java)
                                    .putExtra("label", "${ev.name} · ${fx.name}")
                                activity.startActivity(i, HioneCompositing.optionsFor(activity, fx))
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) { Text("PREVIEW", color = HioneTeal, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(20.dp))
            .background(if (selected) HionePink else Color(0xFF161C28))
            .border(1.dp, if (selected) HionePink else Color.White.copy(alpha = 0.15f), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(label, color = if (selected) Mascot.color(0xFF10040A) else Color.White.copy(alpha = 0.8f),
            fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}
