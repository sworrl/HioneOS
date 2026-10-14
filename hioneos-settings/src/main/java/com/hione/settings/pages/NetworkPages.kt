package com.hione.settings.pages

import com.hione.settings.Mascot
import android.annotation.SuppressLint
import android.app.usage.NetworkStatsManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.net.wifi.SoftApConfiguration
import android.nfc.NfcAdapter
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hione.settings.sys.Hidden
import com.hione.settings.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.Executor

// ---------------------------------------------------------------- Mobile network

object MobileOps {
    fun tm(ctx: Context): TelephonyManager? = ctx.getSystemService(TelephonyManager::class.java)

    @SuppressLint("MissingPermission")
    fun subs(ctx: Context): List<SubscriptionInfo>? = try {
        ctx.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList ?: emptyList()
    } catch (_: Throwable) { null }

    fun dataOn(ctx: Context): Boolean? = try { tm(ctx)?.isDataEnabled } catch (_: Throwable) { null }

    /** setDataEnabledForReason(USER=0, …) needs MODIFY_PHONE_STATE; older name kept as fallback. */
    fun setData(ctx: Context, on: Boolean): Boolean {
        val tm = tm(ctx)
        return Hidden.tryCall(tm, "setDataEnabledForReason", 0, on).isSuccess || Hidden.tryCall(tm, "setDataEnabled", on).isSuccess
    }

    fun roamingOn(ctx: Context): Boolean? = try { tm(ctx)?.isDataRoamingEnabled } catch (_: Throwable) { null }
    fun setRoaming(ctx: Context, on: Boolean) = Hidden.tryCall(tm(ctx), "setDataRoamingEnabled", on).isSuccess

    const val LTE_MASK = (1L shl 12) or (1L shl 18)     // NETWORK_TYPE_LTE, NETWORK_TYPE_LTE_CA
    fun allowedTypes(ctx: Context): Long? = Hidden.call(tm(ctx), "getAllowedNetworkTypesForReason", 0) as? Long
    fun supportedTypes(ctx: Context): Long? = Hidden.call(tm(ctx), "getSupportedRadioAccessFamily") as? Long
    fun setAllowedTypes(ctx: Context, mask: Long) = Hidden.tryCall(tm(ctx), "setAllowedNetworkTypesForReason", 0, mask).isSuccess

    @SuppressLint("MissingPermission")
    fun netTypeName(ctx: Context): String? = try {
        when (tm(ctx)?.dataNetworkType) {
            TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
            TelephonyManager.NETWORK_TYPE_NR -> "5G"
            TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
            TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
            TelephonyManager.NETWORK_TYPE_UNKNOWN, null -> null
            else -> "Other"
        }
    } catch (_: Throwable) { null }
}

@SuppressLint("MissingPermission")
@Composable
fun MobileNetworkPage() {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(5000); refresh++ } }
    val subs = remember(refresh) { MobileOps.subs(ctx) }
    val tm = remember { MobileOps.tm(ctx) }
    var typePick by remember { mutableStateOf(false) }
    val allowed = remember(refresh) { MobileOps.allowedTypes(ctx) }
    val supported = remember { MobileOps.supportedTypes(ctx) }
    PageList {
        item {
            Section("SIM", note = when {
                subs == null -> "SIM details could not be read."
                subs.isEmpty() -> "No active SIM."
                else -> null
            }) {
                subs?.forEach { s ->
                    InfoRow("Carrier", s.carrierName?.toString())
                    InfoRow("Name", s.displayName?.toString())
                    InfoRow("Number", try { ctx.getSystemService(SubscriptionManager::class.java).getPhoneNumber(s.subscriptionId) } catch (_: Throwable) { null })
                    InfoRow("Country", s.countryIso?.uppercase())
                }
                InfoRow("Network", tm?.networkOperatorName?.takeIf { it.isNotBlank() })
                InfoRow("Connection type", MobileOps.netTypeName(ctx))
                InfoRow("Signal", try { tm?.signalStrength?.level?.let { "$it of 4" } } catch (_: Throwable) { null })
            }
        }
        item {
            Section {
                ToggleRow("Mobile data", "Use the cell network for data", MobileOps.dataOn(ctx)) {
                    if (!MobileOps.setData(ctx, it)) toast(ctx, "Mobile data was not changed"); refresh++
                }
                ToggleRow("Roaming", "Use data when on another carrier's network. Charges may apply.", MobileOps.roamingOn(ctx)) {
                    if (!MobileOps.setRoaming(ctx, it)) toast(ctx, "Roaming was not changed"); refresh++
                }
                NavRow("Preferred network type", when {
                    allowed == null -> UNKNOWN
                    allowed and MobileOps.LTE_MASK.inv() == 0L -> "LTE only"
                    else -> "Automatic"
                }) { typePick = true }
            }
        }
        item {
            Section("Not built yet") {
                StockRow("Access point names (APN)", ".Settings\$ApnSettingsActivity")
                StockRow("SIM lock", ".Settings\$IccLockSettingsActivity")
                StockRow("All mobile network settings", ".Settings\$MobileNetworkActivity")
            }
        }
    }
    if (typePick) ChoiceDialog("Preferred network type", listOf(0 to "Automatic", 1 to "LTE only"),
        if (allowed != null && allowed and MobileOps.LTE_MASK.inv() == 0L) 1 else 0,
        onPick = {
            typePick = false
            val mask = if (it == 1) MobileOps.LTE_MASK else (supported ?: -1L)
            if (!MobileOps.setAllowedTypes(ctx, mask)) toast(ctx, "Network type was not changed")
            refresh++
        }, onDismiss = { typePick = false })
}

// ---------------------------------------------------------------- Hotspot & tethering

object TetherOps {
    const val WIFI = 0; const val USB = 1; const val BT = 2

    private fun tethering(ctx: Context): Any? = try { ctx.getSystemService("tethering") } catch (_: Throwable) { null }

    fun wifiApOn(ctx: Context): Boolean? = Hidden.call(WifiOps.wm(ctx), "isWifiApEnabled") as? Boolean

    fun ifaces(ctx: Context): List<String>? {
        @Suppress("UNCHECKED_CAST")
        val a = Hidden.call(tethering(ctx), "getTetheredIfaces") as? Array<String>
            ?: Hidden.call(ctx.getSystemService(ConnectivityManager::class.java), "getTetheredIfaces") as? Array<String>
        return a?.toList()
    }

    /**
     * TetheringManager.startTethering(int, Executor, StartTetheringCallback) is @SystemApi
     * (TETHER_PRIVILEGED). The callback is an interface, so a Proxy stands in for it and
     * reports the failure code instead of swallowing it.
     */
    fun start(ctx: Context, type: Int, onResult: (String?) -> Unit): Boolean {
        val tm = tethering(ctx) ?: return false
        return try {
            val cbCls = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
            val cb = java.lang.reflect.Proxy.newProxyInstance(cbCls.classLoader, arrayOf(cbCls)) { _, m, args ->
                when (m.name) {
                    "onTetheringStarted" -> onResult(null)
                    "onTetheringFailed" -> onResult("error ${args?.getOrNull(0)}")
                }
                null
            }
            val main = ctx.mainExecutor as Executor
            Hidden.tryCall(tm, "startTethering", type, main, cb).isSuccess
        } catch (t: Throwable) { false }
    }

    fun stop(ctx: Context, type: Int): Boolean = Hidden.tryCall(tethering(ctx), "stopTethering", type).isSuccess

    fun apConfig(ctx: Context): SoftApConfiguration? = Hidden.call(WifiOps.wm(ctx), "getSoftApConfiguration") as? SoftApConfiguration

    /** SoftApConfiguration.Builder is @SystemApi; writing needs NETWORK_SETTINGS / OVERRIDE_WIFI_CONFIG. */
    fun saveAp(ctx: Context, base: SoftApConfiguration, ssid: String?, pass: String?): Boolean = try {
        val b = Class.forName("android.net.wifi.SoftApConfiguration\$Builder").getConstructor(SoftApConfiguration::class.java).newInstance(base)
        if (ssid != null) Hidden.call(b, "setSsid", ssid)
        if (pass != null) Hidden.call(b, "setPassphrase", pass, SoftApConfiguration.SECURITY_TYPE_WPA2_PSK)
        val cfg = Hidden.call(b, "build")
        Hidden.call(WifiOps.wm(ctx), "setSoftApConfiguration", cfg) == true
    } catch (_: Throwable) { false }
}

@Composable
fun HotspotPage() {
    val ctx = LocalContext.current
    val tick = BroadcastTick("android.net.wifi.WIFI_AP_STATE_CHANGED", "android.net.conn.TETHER_STATE_CHANGED")
    var refresh by remember { mutableIntStateOf(0) }
    val ap = remember(tick, refresh) { TetherOps.wifiApOn(ctx) }
    val ifaces = remember(tick, refresh) { TetherOps.ifaces(ctx) }
    val cfg = remember(tick, refresh) { TetherOps.apConfig(ctx) }
    @Suppress("DEPRECATION")
    val ssid = cfg?.ssid
    var edit by remember { mutableStateOf<String?>(null) }
    fun toggle(type: Int, on: Boolean, name: String) {
        val ok = if (on) TetherOps.start(ctx, type) { err -> if (err != null) toast(ctx, "$name did not start ($err)"); refresh++ } else TetherOps.stop(ctx, type)
        if (!ok) toast(ctx, "$name was not changed")
        refresh++
    }
    PageList {
        item {
            Section("Wi-Fi hotspot") {
                ToggleRow("Use Wi-Fi hotspot", "Share this device's internet over Wi-Fi", ap) { toggle(TetherOps.WIFI, it, "Hotspot") }
                NavRow("Hotspot name", ssid ?: UNKNOWN) { if (cfg != null) edit = "ssid" }
                NavRow("Hotspot password", if (cfg?.passphrase.isNullOrEmpty()) "None" else "Tap to change") { if (cfg != null) edit = "pass" }
                InfoRow("Security", when (cfg?.securityType) {
                    SoftApConfiguration.SECURITY_TYPE_OPEN -> "None"
                    SoftApConfiguration.SECURITY_TYPE_WPA2_PSK -> "WPA2"
                    SoftApConfiguration.SECURITY_TYPE_WPA3_SAE -> "WPA3"
                    SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION -> "WPA2/WPA3"
                    else -> null
                })
            }
        }
        item {
            Section("Other tethering") {
                ToggleRow("USB tethering", "Share internet with a computer over USB", ifaces?.any { it.startsWith("rndis") || it.startsWith("ncm") || it.startsWith("usb") }) { toggle(TetherOps.USB, it, "USB tethering") }
                ToggleRow("Bluetooth tethering", "Share internet over Bluetooth", ifaces?.any { it.startsWith("bt-pan") }) { toggle(TetherOps.BT, it, "Bluetooth tethering") }
            }
        }
    }
    when (edit) {
        "ssid" -> TextInputDialog("Hotspot name", "Name", initial = ssid ?: "", minLength = 1, onConfirm = {
            edit = null; if (cfg == null || !TetherOps.saveAp(ctx, cfg, it, null)) toast(ctx, "Name was not saved"); refresh++
        }, onDismiss = { edit = null })
        "pass" -> TextInputDialog("Hotspot password", "Password (8 or more characters)", password = true, minLength = 8, onConfirm = {
            edit = null; if (cfg == null || !TetherOps.saveAp(ctx, cfg, null, it)) toast(ctx, "Password was not saved"); refresh++
        }, onDismiss = { edit = null })
    }
}

// ---------------------------------------------------------------- VPN

object VpnOps {
    private fun vpnManager(ctx: Context): Any? = try { ctx.getSystemService("vpn_management") } catch (_: Throwable) { null }

    fun apps(ctx: Context): List<String> = try {
        ctx.packageManager.queryIntentServices(Intent(VpnService.SERVICE_INTERFACE), 0)
            .filter { it.serviceInfo.permission == "android.permission.BIND_VPN_SERVICE" }
            .map { it.serviceInfo.packageName }.distinct()
    } catch (_: Throwable) { emptyList() }

    fun alwaysOn(ctx: Context): String? = Hidden.call(vpnManager(ctx), "getAlwaysOnVpnPackageForUser", Hidden.myUserId()) as? String
    fun lockdown(ctx: Context): Boolean? = Hidden.call(vpnManager(ctx), "isVpnLockdownEnabled", Hidden.myUserId()) as? Boolean

    /** VpnManager.setAlwaysOnVpnPackageForUser is @hide (CONTROL_ALWAYS_ON_VPN). */
    fun setAlwaysOn(ctx: Context, pkg: String?, lockdown: Boolean): Boolean =
        Hidden.call(vpnManager(ctx), "setAlwaysOnVpnPackageForUser", Hidden.myUserId(), pkg, lockdown, null) == true

    fun active(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    } catch (_: Throwable) { false }
}

@Composable
fun VpnPage() {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeTick { refresh++ }
    val apps = remember(refresh) { VpnOps.apps(ctx) }
    val always = remember(refresh) { VpnOps.alwaysOn(ctx) }
    val lock = remember(refresh) { VpnOps.lockdown(ctx) }
    PageList {
        item {
            Section("VPN apps", note = if (apps.isEmpty()) "No VPN app is installed." else if (VpnOps.active(ctx)) "A VPN is connected right now." else null) {
                apps.forEach { p ->
                    ToggleRow(Hidden.appLabel(ctx, p), "Always-on VPN: start at boot and stay connected", always == p) {
                        if (!VpnOps.setAlwaysOn(ctx, if (it) p else null, it && lock == true)) toast(ctx, "Always-on VPN was not changed")
                        refresh++
                    }
                }
            }
        }
        if (always != null) item {
            Section {
                ToggleRow("Block connections without VPN", "No internet unless the always-on VPN is connected", lock) {
                    if (!VpnOps.setAlwaysOn(ctx, always, it)) toast(ctx, "Setting was not changed"); refresh++
                }
            }
        }
        item {
            Section("Not built yet") { StockRow("Built-in VPN profiles (IKEv2)", ".Settings\$VpnSettingsActivity") }
        }
    }
}

// ---------------------------------------------------------------- Data usage

object DataOps {
    fun monthStart(): Long = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Suppress("DEPRECATION")
    fun total(ctx: Context, type: Int): Long? = try {
        val nsm = ctx.getSystemService(NetworkStatsManager::class.java)
        val b = nsm.querySummaryForDevice(type, null, monthStart(), System.currentTimeMillis())
        b.rxBytes + b.txBytes
    } catch (_: Throwable) { null }

    private fun npm(ctx: Context): Any? = try { ctx.getSystemService(Class.forName("android.net.NetworkPolicyManager")) } catch (_: Throwable) { null }
    fun saverOn(ctx: Context): Boolean? = Hidden.call(npm(ctx), "getRestrictBackground") as? Boolean
    fun setSaver(ctx: Context, on: Boolean) = Hidden.tryCall(npm(ctx), "setRestrictBackground", on).isSuccess
}

@Composable
fun DataUsagePage() {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    var refresh by remember { mutableIntStateOf(0) }
    var mobile by remember { mutableStateOf<Long?>(null) }
    var wifi by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            @Suppress("DEPRECATION")
            mobile = DataOps.total(ctx, ConnectivityManager.TYPE_MOBILE)
            @Suppress("DEPRECATION")
            wifi = DataOps.total(ctx, ConnectivityManager.TYPE_WIFI)
        }
    }
    PageList {
        item {
            Section("This month") {
                InfoRow("Mobile data", mobile?.let { Formatter.formatShortFileSize(ctx, it) })
                InfoRow("Wi-Fi data", wifi?.let { Formatter.formatShortFileSize(ctx, it) })
            }
        }
        item {
            key(refresh) {
                Section {
                    ToggleRow("Data Saver", "Stops most apps from using mobile data in the background", DataOps.saverOn(ctx)) {
                        if (!DataOps.setSaver(ctx, it)) toast(ctx, "Data Saver was not changed"); refresh++
                    }
                    NavRow("Unrestricted data", "Pick apps from each app's info page") { nav.push("apps") }
                }
            }
        }
        item {
            Section("Not built yet") { StockRow("Per-app usage, warnings and limits", ".Settings\$DataUsageSummaryActivity") }
        }
    }
}

// ---------------------------------------------------------------- NFC & connection preferences

@Composable
fun NfcPage() {
    val ctx = LocalContext.current
    val tick = BroadcastTick("android.nfc.action.ADAPTER_STATE_CHANGED")
    val nfc = remember { try { NfcAdapter.getDefaultAdapter(ctx) } catch (_: Throwable) { null } }
    val on = remember(tick) { try { nfc?.isEnabled } catch (_: Throwable) { null } }
    PageList {
        item {
            Section(note = if (nfc == null) "No NFC hardware was found." else null) {
                // NfcAdapter.enable()/disable() are @SystemApi; NfcService checks WRITE_SECURE_SETTINGS.
                ToggleRow("Use NFC", "Read and exchange data with tags and nearby devices", if (nfc == null) null else on, enabled = nfc != null) {
                    if (Hidden.tryCall(nfc, if (it) "enable" else "disable").getOrNull() != true) toast(ctx, "NFC was not changed")
                }
            }
        }
        item { Section("Not built yet") { StockRow("Contactless payments", ".Settings\$PaymentSettingsActivity") } }
    }
}

object UsbOps {
    private fun um(ctx: Context): Any? = ctx.getSystemService(Context.USB_SERVICE)
    fun current(ctx: Context): Long? = Hidden.call(um(ctx), "getCurrentFunctions") as? Long
    /** UsbManager.setCurrentFunctions(long) is @SystemApi (MANAGE_USB). */
    fun set(ctx: Context, f: Long) = Hidden.tryCall(um(ctx), "setCurrentFunctions", f).isSuccess
    val options = listOf(0L to "No data transfer", 4L to "File transfer (MTP)", 16L to "Photos (PTP)", 32L to "USB tethering", 8L to "MIDI")
}

/**
 * Android Auto ships in the image. It hides media apps that did not come from Play (Hione Music,
 * Hione FM) until its own developer setting "Unknown sources" is on. That pref lives in gearhead's
 * provider, which rejects callers that are not Google-signed, so the user has to flip it.
 */
object AndroidAutoOps {
    const val PKG = "com.google.android.projection.gearhead"

    fun installed(ctx: Context): Boolean =
        try { ctx.packageManager.getApplicationInfo(PKG, 0); true } catch (_: Throwable) { false }

    /** gearhead's DefaultSettingsActivity answers both of these (checked with resolve-activity on 0.3.0). */
    fun openSettings(ctx: Context): Boolean {
        val tries = listOf(
            Intent("com.google.android.projection.gearhead.SETTINGS").setPackage(PKG),
            Intent(Intent.ACTION_APPLICATION_PREFERENCES).setPackage(PKG),
        )
        for (i in tries) {
            try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true } catch (_: Throwable) {}
        }
        return false
    }
}

@SuppressLint("MissingPermission")
@Composable
fun ConnectedPrefsPage() {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    val bt = remember { try { ctx.getSystemService(BluetoothManager::class.java).adapter } catch (_: Throwable) { null } }
    var refresh by remember { mutableIntStateOf(0) }
    val btName = remember(refresh) { try { bt?.name } catch (_: Throwable) { null } }
    val usb = remember(refresh) { UsbOps.current(ctx) }
    var edit by remember { mutableStateOf(false) }
    var usbPick by remember { mutableStateOf(false) }
    val autoInstalled = remember(refresh) { AndroidAutoOps.installed(ctx) }
    PageList {
        item {
            Section {
                NavRow("Bluetooth device name", btName ?: UNKNOWN, Icons.Default.Bluetooth) { if (bt != null) edit = true }
                NavRow("USB", UsbOps.options.firstOrNull { it.first == usb }?.second ?: if (usb == null) UNKNOWN else "Custom ($usb)", Icons.Default.Usb) { usbPick = true }
                NavRow("NFC", null, Icons.Default.Nfc) { nav.push("nfc") }
            }
        }
        if (autoInstalled) item {
            Section("Android Auto") {
                BodyText("Android Auto hides ${Mascot.musicAppName} and ${Mascot.name} FM until its \"Unknown sources\" setting is on. Only you can turn it on.")
                Spacer(Modifier.height(8.dp))
                BodyText("1. Open Android Auto settings.\n2. Tap Version about 10 times until developer settings unlock.\n3. Open the menu and pick Developer settings.\n4. Turn on Unknown sources.")
                Spacer(Modifier.height(10.dp))
                ActionButton("Open Android Auto settings") {
                    // Falls back to our app info page, e.g. when Android Auto is disabled.
                    if (!AndroidAutoOps.openSettings(ctx)) nav.push("app/${android.net.Uri.encode(AndroidAutoOps.PKG)}")
                }
            }
        }
        item {
            Section("Not built yet") {
                StockRow("Cast", ".Settings\$WifiDisplaySettingsActivity")
                StockRow("Printing", ".Settings\$PrintSettingsActivity")
            }
        }
    }
    if (edit) TextInputDialog("Device name", "Name other Bluetooth devices see", initial = btName ?: "", minLength = 1, onConfirm = {
        edit = false; if (runCatching { bt?.setName(it) }.getOrNull() != true) toast(ctx, "Name was not changed"); refresh++
    }, onDismiss = { edit = false })
    if (usbPick) ChoiceDialog("Use USB for", UsbOps.options, usb, onPick = {
        usbPick = false; if (!UsbOps.set(ctx, it)) toast(ctx, "USB mode was not changed"); refresh++
    }, onDismiss = { usbPick = false })
}

