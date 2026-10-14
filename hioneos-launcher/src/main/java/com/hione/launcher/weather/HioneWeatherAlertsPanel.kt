package com.hione.launcher.weather

import com.hione.launcher.Mascot
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.launcher.HioneCyan
import com.hione.launcher.HioneTextSecondary

private val WxMuted = Color(0xFF8BA6A9)

fun alertColor(severity: String): Color = when (HioneNwsAlerts.severityRank(severity)) {
    4 -> Mascot.color(0xFFFF2D6F)   // Extreme
    3 -> Color(0xFFFF5252)   // Severe
    2 -> Color(0xFFFFB300)   // Moderate
    1 -> Color(0xFFFFE066)   // Minor
    else -> Mascot.color(0xFFFFD59A)
}

/**
 * Top of the weather screen: active NWS alerts (severity colored, tap to expand), the nearest
 * NOAA Weather Radio transmitter, and the notification switch. Outside the US it renders nothing.
 */
@Composable
fun HioneWeatherAlertsPanel(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { HioneNwsAlerts.start(ctx) }
    val st by HioneNwsAlerts.state.collectAsState()
    if (!st.inUs) return
    var openAlert by remember { mutableStateOf<String?>(null) }
    var radiosOpen by remember { mutableStateOf(false) }
    var notify by remember { mutableStateOf(HioneNwsAlerts.notifyEnabled(ctx)) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (st.alerts.isEmpty()) {
            val checked = if (st.lastCheckMs > 0) " · checked ${HioneNwsAlerts.clock(st.lastCheckMs)}" else ""
            Text(
                when {
                    !st.hasLocation && st.lastCheckMs == 0L -> "Weather alerts: waiting for a location"
                    st.error != null && st.lastCheckMs == 0L -> "Weather alerts: ${st.error}"
                    else -> "No active weather alerts here$checked"
                },
                color = WxMuted, fontSize = 8.5.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                modifier = Modifier.clickable { HioneNwsAlerts.refreshNow(ctx) }
            )
        }
        st.alerts.forEach { a ->
            val c = alertColor(a.severity)
            val open = openAlert == a.id
            Row(
                Modifier.fillMaxWidth().clip(CutCornerShape(5.dp)).background(c.copy(alpha = 0.14f))
                    .border(1.dp, c.copy(alpha = 0.75f), CutCornerShape(5.dp))
                    .drawBehind { drawRect(c, size = androidx.compose.ui.geometry.Size(4.dp.toPx(), size.height)) }
                    .clickable { openAlert = if (open) null else a.id }
            ) {
                Column(Modifier.weight(1f).padding(start = 11.dp, end = 7.dp, top = 4.dp, bottom = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(a.shortLine, color = c, fontSize = 10.sp, fontWeight = FontWeight.Black,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(a.severity.uppercase(), color = c.copy(alpha = 0.85f), fontSize = 7.5.sp, fontWeight = FontWeight.Bold)
                    }
                    if (!open && a.areaDesc.isNotBlank()) {
                        Text(a.areaDesc, color = Color.White.copy(alpha = 0.7f), fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (open) {
                        Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()).padding(top = 3.dp)) {
                            if (a.headline.isNotBlank()) Text(a.headline, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            if (a.instruction.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text("What to do", color = c, fontSize = 8.sp, fontWeight = FontWeight.Black)
                                Text(a.instruction, color = Color.White.copy(alpha = 0.92f), fontSize = 9.sp)
                            }
                            if (a.description.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(a.description, color = Color.White.copy(alpha = 0.8f), fontSize = 8.5.sp)
                            }
                            Spacer(Modifier.height(4.dp))
                            val meta = listOf(
                                a.areaDesc,
                                listOf(a.urgency, a.certainty).filter { it.isNotBlank() }.joinToString(" · "),
                                a.senderName,
                            ).filter { it.isNotBlank() }.joinToString("\n")
                            Text(meta, color = WxMuted, fontSize = 7.5.sp)
                        }
                    }
                }
            }
        }

        // Nearest NOAA Weather Radio transmitter.
        val best = st.radios.firstOrNull { it.inService }
        if (best != null) {
            Column(
                Modifier.fillMaxWidth().clip(CutCornerShape(5.dp)).background(Mascot.color(0x1AFFC56B))
                    .border(1.dp, HioneCyan.copy(alpha = 0.35f), CutCornerShape(5.dp))
                    .clickable { radiosOpen = !radiosOpen }
                    .padding(horizontal = 7.dp, vertical = 4.dp)
            ) {
                Text(
                    "Your weather radio: ${best.call} · ${best.wx} ${best.mhzLabel} MHz · ${best.kmLabel}",
                    color = HioneCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (radiosOpen) {
                    Spacer(Modifier.height(3.dp))
                    st.radios.forEach { r ->
                        Text(
                            buildString {
                                append(r.wx).append(' ').append(r.mhzLabel).append(" MHz · ").append(r.call)
                                if (r.place.isNotBlank()) append(" · ").append(r.place)
                                append(" · ").append(r.kmLabel)
                                r.watts?.takeIf { it > 0 }?.let { append(" · ").append(it).append(" W") }
                                if (!r.inService) append(" · ").append(r.status.lowercase())
                            },
                            color = if (r.inService) Color.White.copy(alpha = 0.9f) else WxMuted,
                            fontSize = 8.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "For a weather radio, scanner or ham handheld. The FM radio in this player can't tune 162 MHz. The alerts above are the same ones these stations broadcast.",
                        color = WxMuted, fontSize = 7.5.sp
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                        Text("Notify me about severe alerts", color = HioneTextSecondary, fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Switch(
                            checked = notify,
                            onCheckedChange = { notify = it; HioneNwsAlerts.setNotifyEnabled(ctx, it) },
                            colors = SwitchDefaults.colors(checkedTrackColor = HioneCyan.copy(alpha = 0.6f))
                        )
                    }
                }
            }
        }
    }
}

/**
 * One line for the home screen weather tile: the top alert when one is active, otherwise the
 * nearest weather radio channel. Renders nothing outside the US.
 */
@Composable
fun HioneWeatherAlertTileLine(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { HioneNwsAlerts.start(ctx) }
    val st by HioneNwsAlerts.state.collectAsState()
    if (!st.inUs) return
    val top = st.alerts.firstOrNull()
    val radio = st.radios.firstOrNull { it.inService }
    if (top == null && radio == null) return
    Row(modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (top != null) {
            val c = alertColor(top.severity)
            Box(
                Modifier.clip(RoundedCornerShape(5.dp)).background(c.copy(alpha = 0.22f))
                    .border(1.dp, c, RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 1.dp)
            ) {
                Text(if (st.alerts.size > 1) "${st.alerts.size} ALERTS" else "ALERT", color = c, fontSize = 8.5.sp, fontWeight = FontWeight.Black, maxLines = 1)
            }
            Spacer(Modifier.width(5.dp))
            Text(top.shortLine, color = c, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (radio != null) {
            Spacer(Modifier.width(6.dp))
            Text("NOAA ${radio.wx} ${radio.mhzLabel}", color = WxMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}
