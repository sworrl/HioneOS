package com.hione.player.remote

import com.hione.player.Mascot
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.player.AudiowideFont
import com.hione.player.Haptics
import com.hione.player.HionePink
import com.hione.player.HioneTeal
import com.hione.player.HioneTealBright
import com.hione.player.Muted
import kotlinx.coroutines.delay

/**
 * Settings card for the phone-as-Bluetooth-remote feature. Everything shown here comes straight
 * from [HioneRemoteStatus] (fed by real GATT/advertise callbacks) and [HioneRemotePreferences] —
 * there is no optimistic "connected" state.
 */
@Composable
fun HioneRemoteSettingsCard(ctx: Context) {
    val status by HioneRemoteStatus.state.collectAsState()
    var enabled by remember { mutableStateOf(HioneRemotePreferences.isEnabled(ctx)) }
    var brandedName by remember { mutableStateOf(HioneRemotePreferences.advertiseBrandedName(ctx)) }
    var pwaUrl by remember { mutableStateOf(HioneRemotePreferences.getPwaUrl(ctx)) }
    var showUrlEditor by remember { mutableStateOf(false) }
    val paired = remember(status.pairedGeneration) { HioneRemotePreferences.listPaired(ctx) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(status.pairLockedUntilMs) {
        while (status.pairLockedUntilMs > System.currentTimeMillis()) { now = System.currentTimeMillis(); delay(1000L) }
        now = System.currentTimeMillis()
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val ok = HioneRemoteGattService.requiredPermissions().all { result[it] == true } || HioneRemoteGattService.hasPermissions(ctx)
        if (ok) {
            HioneRemotePreferences.setEnabled(ctx, true)
            enabled = true
            HioneRemoteGattService.start(ctx)
        } else {
            enabled = false
            HioneRemotePreferences.setEnabled(ctx, false)
            HioneRemoteStatus.update { it.copy(lastError = "Bluetooth permission denied. The remote can't advertise without it.") }
        }
    }

    fun setOn(on: Boolean) {
        Haptics.tick(ctx)
        if (on) {
            if (HioneRemoteGattService.hasPermissions(ctx)) {
                HioneRemotePreferences.setEnabled(ctx, true)
                enabled = true
                HioneRemoteGattService.start(ctx)
            } else {
                permissionLauncher.launch(HioneRemoteGattService.requiredPermissions())
            }
        } else {
            enabled = false
            HioneRemotePreferences.setEnabled(ctx, false)
            HioneRemoteGattService.stop(ctx)
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(listOf(Mascot.color(0xFF07272B), Mascot.color(0xFF041417))))
            .border(1.dp, if (status.advertising) HioneTealBright.copy(alpha = 0.55f) else HioneTealBright.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        // ---- header + master toggle
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("📱", fontSize = 16.sp)
                    Spacer(Modifier.width(8.dp))
                    Text("Phone Remote · Bluetooth LE", color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                }
                Text(
                    "Control playback from any phone's browser, no app to install. Off by default. Nothing is advertised until you turn it on.",
                    color = Muted, fontSize = 10.5.sp
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = enabled,
                onCheckedChange = { setOn(it) },
                colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = HioneTeal)
            )
        }

        // ---- live status pill
        Spacer(Modifier.height(10.dp))
        val (pillText, pillColor) = when {
            !enabled && !status.running -> "OFF" to Muted
            status.lastError != null && !status.advertising -> "Error: ${status.lastError}" to HionePink
            status.connected.any { it.authorized } -> "CONNECTED" to HioneTealBright
            status.advertising -> "ADVERTISING AS \"${status.advertisedName.ifBlank { HioneRemoteProtocol.LOCAL_NAME }}\"" to HioneTealBright
            status.running -> "STARTING…" to Muted
            else -> "STARTING…" to Muted
        }
        Box(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(pillColor.copy(alpha = 0.18f))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(pillText, color = pillColor, fontSize = 9.5.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }

        if (status.running) {
            // ---- pairing code
            Spacer(Modifier.height(12.dp))
            SectionLabel("PAIRING CODE")
            val locked = status.pairLockedUntilMs > now
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Mascot.color(0xFF04131A))
                        .border(1.dp, if (locked) HionePink.copy(alpha = 0.5f) else HioneTeal.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (locked) "LOCKED ${((status.pairLockedUntilMs - now) / 1000).coerceAtLeast(0)}s"
                        else status.pairingCode.chunked(3).joinToString("  "),
                        color = if (locked) HionePink else HioneTealBright,
                        fontSize = if (locked) 14.sp else 26.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont,
                        letterSpacing = 2.sp
                    )
                }
                Spacer(Modifier.width(8.dp))
                SmallButton("New code") { Haptics.tick(ctx); HioneRemoteGattService.requestNewCode(ctx) }
            }
            Text(
                if (locked) "Too many wrong codes. Pairing pauses for a minute and the code has been replaced."
                else "Single-use. Type it into the remote page on your phone when it asks. A new code appears after each pairing.",
                color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp)
            )

            // ---- QR to the PWA
            Spacer(Modifier.height(12.dp))
            SectionLabel("SCAN ON YOUR PHONE")
            val qrPayload = remember(pwaUrl, status.pairingCode) {
                val base = pwaUrl.trim()
                if (status.pairingCode.isBlank()) base else "$base#code=${status.pairingCode}"
            }
            val qr = remember(qrPayload) { try { HioneQrEncoder.encode(qrPayload, HioneQrEncoder.Ecc.MEDIUM) } catch (_: Throwable) { null } }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(132.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White)
                        .padding(6.dp)
                ) {
                    if (qr != null) QrCanvas(qr, Modifier.size(120.dp))
                    else Text("URL too long", color = Color.Black, fontSize = 10.sp, modifier = Modifier.align(Alignment.Center))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Opens the ${Mascot.name} Remote web page with this code pre-filled, then tap Connect and pick \"${HioneRemoteProtocol.LOCAL_NAME}\".", color = Color.White, fontSize = 11.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(pwaUrl, color = HioneTealBright, fontSize = 10.sp, fontFamily = AudiowideFont, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    Text("Needs Chrome or Edge (Android, Windows, macOS, ChromeOS, Linux). iOS Safari has no Web Bluetooth.", color = Muted, fontSize = 9.5.sp)
                    Spacer(Modifier.height(6.dp))
                    SmallButton(if (showUrlEditor) "Done" else "Change URL") { showUrlEditor = !showUrlEditor }
                }
            }
            if (showUrlEditor) {
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicTextField(
                        value = pwaUrl,
                        onValueChange = { pwaUrl = it; HioneRemotePreferences.setPwaUrl(ctx, it) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = TextStyle(color = Mascot.color(0xFFE8F4F2), fontSize = 12.5.sp),
                        cursorBrush = SolidColor(HioneTeal),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrect = false),
                        decorationBox = { inner ->
                            if (pwaUrl.isEmpty()) Text(HioneRemotePreferences.DEFAULT_PWA_URL, color = Muted.copy(alpha = 0.85f), fontSize = 12.sp)
                            inner()
                        }
                    )
                }
                Text("Where you host tools/remote-pwa (must be HTTPS).", color = Muted, fontSize = 9.5.sp, modifier = Modifier.padding(top = 3.dp))
            }

            // ---- connected phones
            Spacer(Modifier.height(12.dp))
            SectionLabel("CONNECTED NOW")
            if (status.connected.isEmpty()) {
                Text("No phone connected.", color = Muted, fontSize = 11.sp)
            } else {
                status.connected.forEach { phone ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (phone.authorized) "🟢" else "🟡", fontSize = 11.sp)
                        Spacer(Modifier.width(6.dp))
                        Column(Modifier.weight(1f)) {
                            Text(phone.displayName, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                (if (phone.authorized) "paired" else "waiting for code") + " · MTU ${phone.mtu} · ${phone.address}",
                                color = Muted, fontSize = 9.5.sp
                            )
                        }
                        SmallButton("Disconnect") { Haptics.tick(ctx); HioneRemoteGattService.disconnect(ctx, phone.address) }
                    }
                }
            }
        }

        // ---- paired list (visible even when off, so phones can be forgotten any time)
        Spacer(Modifier.height(12.dp))
        SectionLabel("PAIRED PHONES (${paired.size})")
        if (paired.isEmpty()) {
            Text("None yet. Pair from the remote page with the code above.", color = Muted, fontSize = 11.sp)
        } else {
            paired.forEach { p ->
                val live = status.connected.any { it.authorized && it.label == p.label }
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "paired ${relativeTime(p.pairedAtMs, now)} · last seen ${relativeTime(p.lastSeenMs, now)}" + if (live) " · online" else "",
                            color = Muted, fontSize = 9.5.sp
                        )
                    }
                    SmallButton("Forget", accent = HionePink) {
                        Haptics.tick(ctx)
                        HioneRemotePreferences.forget(ctx, p.id)
                        HioneRemoteGattService.kickPaired(ctx, p.id)
                        HioneRemoteStatus.update { it.copy(pairedGeneration = it.pairedGeneration + 1) }
                    }
                }
            }
        }

        // ---- options
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Bluetooth name \"${HioneRemoteProtocol.LOCAL_NAME}\" while on", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("Renames the adapter so the phone's picker reads ${Mascot.bluetoothName}; the original name is restored when the remote is turned off. Takes effect on next start.", color = Muted, fontSize = 10.sp)
            }
            Switch(
                checked = brandedName,
                onCheckedChange = { brandedName = it; HioneRemotePreferences.setAdvertiseBrandedName(ctx, it) },
                colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = HioneTeal)
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) = Text(
    text, color = HioneTealBright, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont, letterSpacing = 1.sp,
    modifier = Modifier.padding(bottom = 6.dp)
)

@Composable
private fun SmallButton(label: String, accent: Color = HioneTeal, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(accent.copy(alpha = 0.2f))
            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(label, color = if (accent == HionePink) HionePink else HioneTealBright, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

/** Draws a QR matrix into the given box; the white quiet zone is the parent's padding. */
@Composable
private fun QrCanvas(qr: HioneQrEncoder.Matrix, modifier: Modifier) {
    Canvas(modifier) {
        val n = qr.size
        val cell = minOf(size.width, size.height) / n
        val ox = (size.width - cell * n) / 2f
        val oy = (size.height - cell * n) / 2f
        val dark = Mascot.color(0xFF0A1416)
        for (y in 0 until n) for (x in 0 until n) {
            if (qr.get(x, y)) {
                // Overdraw a hair to avoid anti-aliasing seams between adjacent modules.
                drawRect(dark, topLeft = Offset(ox + x * cell, oy + y * cell), size = Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}

private fun relativeTime(then: Long, now: Long): String {
    if (then <= 0L) return "never"
    val s = (now - then) / 1000
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60}m ago"
        s < 86400 -> "${s / 3600}h ago"
        else -> "${s / 86400}d ago"
    }
}
