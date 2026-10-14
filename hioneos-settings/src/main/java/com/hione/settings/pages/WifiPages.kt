package com.hione.settings.pages

import com.hione.settings.Mascot
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.hione.settings.*
import com.hione.settings.sys.Hidden
import com.hione.settings.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Wi-Fi, done with the same WifiManager calls stock Settings uses.
 *
 * HioneSettings is a system app holding NETWORK_SETTINGS, which is what lets it toggle Wi-Fi,
 * read scan results without the location gate, see saved networks, and call the @SystemApi
 * connect/forget. The deprecated add/enable/remove calls are kept only as fallbacks; for a
 * system app they still work on Android 14.
 */
enum class WifiSec(val label: String, val type: Int) {
    OPEN("Open", 0), WEP("WEP", 1), PSK("WPA/WPA2", 2), EAP("Enterprise", 3), SAE("WPA3", 4), OWE("Enhanced open", 6);
    val needsPassword get() = this == WEP || this == PSK || this == SAE
}

data class WifiNet(val ssid: String, val level: Int, val freq: Int, val sec: WifiSec, val savedId: Int?)

object WifiOps {
    fun wm(ctx: Context): WifiManager? = ctx.applicationContext.getSystemService(WifiManager::class.java)

    fun secOf(caps: String): WifiSec = when {
        caps.contains("EAP") -> WifiSec.EAP
        caps.contains("SAE") -> WifiSec.SAE
        caps.contains("PSK") -> WifiSec.PSK
        caps.contains("WEP") -> WifiSec.WEP
        caps.contains("OWE") -> WifiSec.OWE
        else -> WifiSec.OPEN
    }

    fun unquote(s: String?): String = s?.removeSurrounding("\"") ?: ""

    @Suppress("DEPRECATION")
    fun saved(ctx: Context): List<WifiConfiguration> = try {
        wm(ctx)?.configuredNetworks ?: emptyList()
    } catch (_: Throwable) { emptyList() }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    fun networks(ctx: Context): List<WifiNet> {
        val saved = saved(ctx).associateBy { unquote(it.SSID) }
        val results: List<ScanResult> = try { wm(ctx)?.scanResults ?: emptyList() } catch (_: Throwable) { emptyList() }
        return results.filter { !it.SSID.isNullOrBlank() }
            .groupBy { it.SSID }
            .map { (ssid, list) ->
                val best = list.maxBy { it.level }
                WifiNet(ssid, best.level, best.frequency, secOf(best.capabilities ?: ""), saved[ssid]?.networkId)
            }
            .sortedWith(compareByDescending<WifiNet> { it.savedId != null }.thenByDescending { it.level })
    }

    @Suppress("DEPRECATION")
    fun setEnabled(ctx: Context, on: Boolean): Boolean = try { wm(ctx)?.setWifiEnabled(on) == true } catch (_: Throwable) { false }

    fun bars(level: Int): Int = WifiManager.calculateSignalLevel(level, 5)

    /** Connect to a saved network by id. */
    @Suppress("DEPRECATION")
    fun connectSaved(ctx: Context, netId: Int): Boolean {
        val wm = wm(ctx) ?: return false
        if (Hidden.tryCall(wm, "connect", netId, null).isSuccess) return true
        return try { wm.enableNetwork(netId, true) } catch (_: Throwable) { false }
    }

    /** Save and join a new network. Returns false if the framework refused the configuration. */
    @Suppress("DEPRECATION")
    fun connectNew(ctx: Context, ssid: String, sec: WifiSec, password: String, hidden: Boolean = false): Boolean {
        val wm = wm(ctx) ?: return false
        val conf = WifiConfiguration().apply {
            SSID = "\"$ssid\""
            hiddenSSID = hidden
            when (sec) {
                WifiSec.PSK, WifiSec.SAE -> preSharedKey = "\"$password\""
                WifiSec.WEP -> {
                    val hex = password.length in setOf(10, 26, 58) && password.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
                    wepKeys[0] = if (hex) password else "\"$password\""
                    wepTxKeyIndex = 0
                }
                else -> {}
            }
            try { setSecurityParams(sec.type) } catch (_: Throwable) {}
        }
        if (Hidden.tryCall(wm, "connect", conf, null).isSuccess) return true
        return try {
            val id = wm.addNetwork(conf)
            id >= 0 && wm.enableNetwork(id, true)
        } catch (_: Throwable) { false }
    }

    @Suppress("DEPRECATION")
    fun forget(ctx: Context, netId: Int): Boolean {
        val wm = wm(ctx) ?: return false
        if (Hidden.tryCall(wm, "forget", netId, null).isSuccess) return true
        return try { wm.removeNetwork(netId) } catch (_: Throwable) { false }
    }

    fun ipString(ip: Int): String? = if (ip == 0) null else
        "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}"

    fun airplaneOn(ctx: Context): Boolean? = try {
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON) == 1
    } catch (_: Throwable) { null }

    /** ConnectivityManager.setAirplaneMode is @SystemApi (NETWORK_SETTINGS). */
    fun setAirplane(ctx: Context, on: Boolean): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        return Hidden.tryCall(cm, "setAirplaneMode", on).isSuccess
    }
}

/** Re-runs [onEvent] whenever any of [actions] is broadcast while the page is visible. */
@Composable
fun BroadcastTick(vararg actions: String): Int {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(actions.joinToString()) {
        val r = object : BroadcastReceiver() { override fun onReceive(c: Context?, i: Intent?) { tick++ } }
        val f = IntentFilter().apply { actions.forEach { addAction(it) } }
        try { ContextCompat.registerReceiver(ctx, r, f, ContextCompat.RECEIVER_EXPORTED) } catch (_: Throwable) {}
        onDispose { try { ctx.unregisterReceiver(r) } catch (_: Throwable) {} }
    }
    return tick
}

@Composable
fun WifiPage() {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    val tick = BroadcastTick(
        WifiManager.SCAN_RESULTS_AVAILABLE_ACTION, WifiManager.WIFI_STATE_CHANGED_ACTION,
        WifiManager.NETWORK_STATE_CHANGED_ACTION, "android.net.wifi.CONFIGURED_NETWORKS_CHANGE"
    )
    val wm = remember { WifiOps.wm(ctx) }
    val enabled = remember(tick) { try { wm?.isWifiEnabled } catch (_: Throwable) { null } }
    var nets by remember { mutableStateOf<List<WifiNet>>(emptyList()) }
    @Suppress("DEPRECATION")
    val info = remember(tick) { try { wm?.connectionInfo } catch (_: Throwable) { null } }
    val currentSsid = WifiOps.unquote(info?.ssid).takeIf { it.isNotBlank() && it != "<unknown ssid>" }
    LaunchedEffect(tick) { nets = withContext(Dispatchers.IO) { WifiOps.networks(ctx) } }
    LaunchedEffect(enabled) {
        // Stock Settings rescans every 10 s while the picker is open; do the same.
        while (enabled == true) {
            @Suppress("DEPRECATION") try { wm?.startScan() } catch (_: Throwable) {}
            kotlinx.coroutines.delay(10_000)
        }
    }
    var joining by remember { mutableStateOf<WifiNet?>(null) }
    var details by remember { mutableStateOf<WifiNet?>(null) }
    var addHidden by remember { mutableStateOf(false) }

    PageList {
        item {
            Section {
                ToggleRow("Use Wi-Fi", if (enabled == true) currentSsid?.let { "Connected to $it" } ?: "On, not connected" else "Off", enabled) {
                    if (!WifiOps.setEnabled(ctx, it)) toast(ctx, "Wi-Fi did not change. The system refused the request.")
                }
            }
        }
        if (enabled == true) {
            item {
                Section("Networks", note = if (nets.isEmpty()) "Scanning. Results show up as the radio finds them." else null) {
                    nets.forEach { n ->
                        WifiRow(n, connected = n.ssid == currentSsid) {
                            when {
                                n.ssid == currentSsid || n.savedId != null -> details = n
                                n.sec == WifiSec.EAP -> openStockPage(ctx, ".Settings\$WifiSettingsActivity")
                                n.sec.needsPassword -> joining = n
                                else -> if (!WifiOps.connectNew(ctx, n.ssid, n.sec, "")) toast(ctx, "Could not join ${n.ssid}")
                            }
                        }
                    }
                }
            }
            item {
                Section {
                    NavRow("Add network", "Join a hidden network by name", Icons.Default.Add) { addHidden = true }
                    NavRow("Saved networks", "Networks this device remembers", Icons.Default.Bookmarks) { nav.push("wifi_saved") }
                    NavRow("Wi-Fi preferences", "Scanning, auto turn-on and addresses", Icons.Default.Tune) { nav.push("wifi_prefs") }
                }
            }
        }
    }

    joining?.let { n ->
        TextInputDialog(
            title = n.ssid, label = "Password", password = true, confirm = "Connect",
            minLength = if (n.sec == WifiSec.WEP) 5 else 8,
            message = "Security: ${n.sec.label}",
            onConfirm = { pw ->
                joining = null
                if (!WifiOps.connectNew(ctx, n.ssid, n.sec, pw)) toast(ctx, "Could not save ${n.ssid}")
            },
            onDismiss = { joining = null }
        )
    }
    details?.let { n ->
        val connected = n.ssid == currentSsid
        AlertDialog(
            onDismissRequest = { details = null },
            containerColor = HioneCardBg, titleContentColor = Color.White, textContentColor = HioneMuted,
            title = { Text(n.ssid, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    InfoRow("Status", if (connected) "Connected" else "Saved")
                    InfoRow("Signal", "${n.level} dBm")
                    InfoRow("Band", if (n.freq >= 5900) "6 GHz" else if (n.freq >= 4900) "5 GHz" else if (n.freq > 0) "2.4 GHz" else null)
                    InfoRow("Security", n.sec.label)
                    if (connected) {
                        InfoRow("Link speed", info?.linkSpeed?.takeIf { it > 0 }?.let { "$it Mbps" })
                        InfoRow("IP address", WifiOps.ipString(info?.ipAddress ?: 0))
                    }
                }
            },
            confirmButton = {
                Row {
                    if (!connected && n.savedId != null) TextButton(onClick = { details = null; WifiOps.connectSaved(ctx, n.savedId) }) { Text("Connect", color = HioneTealBright) }
                    if (connected) TextButton(onClick = { details = null; @Suppress("DEPRECATION") wm?.disconnect() }) { Text("Disconnect", color = HioneTealBright) }
                    val id = n.savedId ?: WifiOps.saved(ctx).firstOrNull { WifiOps.unquote(it.SSID) == n.ssid }?.networkId
                    if (id != null) TextButton(onClick = {
                        details = null
                        if (!WifiOps.forget(ctx, id)) toast(ctx, "Could not forget ${n.ssid}")
                    }) { Text("Forget", color = HionePinkBright) }
                }
            },
            dismissButton = { TextButton(onClick = { details = null }) { Text("Close", color = HioneMuted) } }
        )
    }
    if (addHidden) AddNetworkDialog(onDone = { addHidden = false })
}

@Composable
private fun AddNetworkDialog(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var ssid by remember { mutableStateOf("") }
    var sec by remember { mutableStateOf(WifiSec.PSK) }
    var stage by remember { mutableIntStateOf(0) }
    when (stage) {
        0 -> TextInputDialog(title = "Add network", label = "Network name", confirm = "Next", minLength = 1,
            onConfirm = { ssid = it; stage = 1 }, onDismiss = onDone)
        1 -> ChoiceDialog("Security", listOf(WifiSec.OPEN, WifiSec.PSK, WifiSec.SAE, WifiSec.WEP, WifiSec.OWE).map { it to it.label }, sec,
            onPick = { sec = it; if (it.needsPassword) stage = 2 else { WifiOps.connectNew(ctx, ssid, it, "", hidden = true); onDone() } },
            onDismiss = onDone)
        else -> TextInputDialog(title = ssid, label = "Password", password = true, confirm = "Connect",
            minLength = if (sec == WifiSec.WEP) 5 else 8,
            onConfirm = {
                if (!WifiOps.connectNew(ctx, ssid, sec, it, hidden = true)) toast(ctx, "Could not save $ssid")
                onDone()
            }, onDismiss = onDone)
    }
}

@Composable
private fun WifiRow(n: WifiNet, connected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp))
            .background(if (connected) Mascot.color(0x22FFC56B) else HioneSurface2)
            .clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val b = WifiOps.bars(n.level)
        Icon(
            when (b) { 0, 1 -> Icons.Default.NetworkWifi1Bar; 2 -> Icons.Default.NetworkWifi2Bar; 3 -> Icons.Default.NetworkWifi3Bar; else -> Icons.Default.Wifi },
            contentDescription = null, tint = if (connected) HioneTealBright else Color.White, modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(n.ssid, color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(if (connected) "Connected" else if (n.savedId != null) "Saved" else null, n.sec.label).joinToString(" · "),
                color = if (connected) HioneTealBright else HioneMuted, fontSize = 11.sp
            )
        }
        if (n.sec != WifiSec.OPEN && n.sec != WifiSec.OWE) Icon(Icons.Default.Lock, null, tint = HioneMuted, modifier = Modifier.size(14.dp))
    }
}

@Composable
fun WifiSavedPage() {
    val ctx = LocalContext.current
    val tick = BroadcastTick("android.net.wifi.CONFIGURED_NETWORKS_CHANGE", WifiManager.NETWORK_STATE_CHANGED_ACTION)
    val saved = remember(tick) { WifiOps.saved(ctx).sortedBy { WifiOps.unquote(it.SSID).lowercase() } }
    var forget by remember { mutableStateOf<WifiConfiguration?>(null) }
    PageList {
        item {
            Section("Saved networks", note = if (saved.isEmpty()) "No saved networks, or the list could not be read." else null) {
                saved.forEach { c ->
                    NavRow(WifiOps.unquote(c.SSID), "Tap to forget", Icons.Default.Wifi) { forget = c }
                }
            }
        }
    }
    forget?.let { c ->
        HioneAlert("Forget ${WifiOps.unquote(c.SSID)}?", "The password is deleted and the device stops joining this network.", "Forget", danger = true,
            onConfirm = { forget = null; if (!WifiOps.forget(ctx, c.networkId)) toast(ctx, "Could not forget the network") },
            onDismiss = { forget = null })
    }
}

@Composable
fun WifiPrefsPage() {
    val ctx = LocalContext.current
    val cr = ctx.contentResolver
    var t by remember { mutableIntStateOf(0) }
    fun g(k: String): Boolean? = try { Settings.Global.getInt(cr, k) == 1 } catch (_: Throwable) { null }
    fun put(k: String, on: Boolean) {
        if (runCatching { Settings.Global.putInt(cr, k, if (on) 1 else 0) }.isFailure) toast(ctx, "That setting was refused")
        t++
    }
    @Suppress("DEPRECATION")
    val info = remember { try { WifiOps.wm(ctx)?.connectionInfo } catch (_: Throwable) { null } }
    PageList {
        item {
            key(t) {
                Section {
                    ToggleRow("Turn on Wi-Fi automatically", "Wi-Fi comes back on near saved networks", g("wifi_wakeup_enabled")) { put("wifi_wakeup_enabled", it) }
                    ToggleRow("Notify for public networks", "Get a notification when an open network is nearby", g("wifi_networks_available_notification_on")) { put("wifi_networks_available_notification_on", it) }
                    ToggleRow("Wi-Fi scanning", "Apps and location can scan for networks even when Wi-Fi is off", g("wifi_scan_always_enabled")) { put("wifi_scan_always_enabled", it) }
                }
            }
        }
        item {
            Section("Addresses") {
                InfoRow("IP address", WifiOps.ipString(info?.ipAddress ?: 0))
                InfoRow("Wi-Fi MAC", info?.macAddress?.takeIf { it != "02:00:00:00:00:00" })
                InfoRow("Frequency", info?.frequency?.takeIf { it > 0 }?.let { "$it MHz" })
            }
        }
        item {
            Section("Not built yet") {
                StockRow("Install certificates and other Wi-Fi tools", ".Settings\$ConfigureWifiSettingsActivity")
            }
        }
    }
}

@Composable
fun InternetPage() {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    val tick = BroadcastTick(Intent.ACTION_AIRPLANE_MODE_CHANGED, WifiManager.WIFI_STATE_CHANGED_ACTION)
    val airplane = remember(tick) { WifiOps.airplaneOn(ctx) }
    val cr = ctx.contentResolver
    var dnsMode by remember { mutableStateOf(Settings.Global.getString(cr, "private_dns_mode")) }
    var dnsHost by remember { mutableStateOf(Settings.Global.getString(cr, "private_dns_specifier")) }
    var dnsPick by remember { mutableStateOf(false) }
    var dnsEdit by remember { mutableStateOf(false) }
    PageList {
        item {
            Section {
                NavRow("Wi-Fi", if (WifiOps.wm(ctx)?.isWifiEnabled == true) "On" else "Off", Icons.Default.Wifi) { nav.push("wifi") }
                NavRow("Mobile network", "SIM, mobile data and roaming", Icons.Default.SignalCellularAlt) { nav.push("mobile") }
                NavRow("Hotspot & tethering", "Share this device's connection", Icons.Default.WifiTethering) { nav.push("hotspot") }
                NavRow("Data usage", "How much data apps used and data saver", Icons.Default.DataUsage) { nav.push("data_usage") }
                NavRow("VPN", "VPN apps and always-on VPN", Icons.Default.VpnKey) { nav.push("vpn") }
            }
        }
        item {
            Section {
                ToggleRow("Airplane mode", "Turns off cell, Wi-Fi and Bluetooth radios", airplane) {
                    if (!WifiOps.setAirplane(ctx, it)) toast(ctx, "Airplane mode did not change. The system refused the request.")
                }
                NavRow("Private DNS", when (dnsMode) {
                    "off" -> "Off"; "hostname" -> dnsHost ?: "Custom provider"; "opportunistic" -> "Automatic"; null -> "Automatic (default)"; else -> dnsMode!!
                }) { dnsPick = true }
            }
        }
    }
    if (dnsPick) ChoiceDialog("Private DNS", listOf("off" to "Off", "opportunistic" to "Automatic", "hostname" to "Use a provider hostname"), dnsMode ?: "opportunistic",
        onPick = {
            dnsPick = false
            if (it == "hostname") dnsEdit = true
            else if (runCatching { Settings.Global.putString(cr, "private_dns_mode", it) }.isSuccess) dnsMode = it
            else toast(ctx, "Private DNS was not changed")
        }, onDismiss = { dnsPick = false })
    if (dnsEdit) TextInputDialog("Private DNS provider", "Hostname, e.g. dns.google", initial = dnsHost ?: "", minLength = 3,
        onConfirm = { h ->
            dnsEdit = false
            val ok = runCatching {
                Settings.Global.putString(cr, "private_dns_specifier", h.trim())
                Settings.Global.putString(cr, "private_dns_mode", "hostname")
            }.isSuccess
            if (ok) { dnsMode = "hostname"; dnsHost = h.trim() } else toast(ctx, "Private DNS was not changed")
        }, onDismiss = { dnsEdit = false })
}

