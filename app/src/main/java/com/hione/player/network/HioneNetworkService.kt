package com.hione.player.network

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.*
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.hione.player.metrics.HioneMetricDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Oki Hione High-Granularity RF Network Telemetry & Complete Hardware Control Service.
 * Provides unrestricted, raw hardware Wi-Fi radio management, live channel spectrum scanning,
 * active network authentication, routing tables, and cellular telemetry.
 */
object HioneNetworkService {
    private const val TAG = "HioneNetworkService"

    data class ScannedAccessPoint(
        val ssid: String,
        val bssid: String,
        val rssiDbm: Int,
        val signalLevel5: Int, // 0..5 micro bars
        val signalPct: Int,
        val frequencyMhz: Int,
        val channel: Int,
        val bandLabel: String, // "2.4GHz" / "5GHz" / "6GHz"
        val standard: String,  // "Wi-Fi 6 (ax)", "Wi-Fi 5 (ac)", "Wi-Fi 4 (n)", "Legacy"
        val capabilities: String,
        val securityType: String,
        val isConnected: Boolean,
        val isSaved: Boolean
    )

    /** Defaults = nothing measured yet. Nullable numbers mean "not reported"; strings are empty. */
    data class WifiGranularState(
        val isEnabled: Boolean = false,
        val isConnected: Boolean = false,
        val ssid: String = "",
        val bssid: String = "",
        val rssiDbm: Int? = null,
        val signalLevel5: Int = 0,
        val signalPct: Int? = null,
        val linkSpeedMbps: Int = 0,
        val rxLinkSpeedMbps: Int = 0,
        val txLinkSpeedMbps: Int = 0,
        val frequencyMhz: Int = 0,
        val channel: Int = 0,
        val bandLabel: String = "",
        /** Real WifiInfo.wifiStandard label, or "" when the framework did not report one. */
        val standard: String = "",
        val ipAddress: String = "",
        val ipv6Address: String = "",
        val subnetMask: String = "",
        val gateway: String = "",
        val dns1: String = "",
        val dns2: String = "",
        val macAddress: String = ""
    )

    data class CellularGranularState(
        val isConnected: Boolean = false,
        /** True only when TelephonyManager reports the SIM can't do voice (data-only). */
        val isDataOnlySim: Boolean = false,
        val carrierName: String = "",
        val networkType: String = "",
        val signalDbm: Int? = null,
        val signalLevel5: Int = 0,
        val signalPct: Int? = null,
        /** Band is not readable from the public API on this device; "" = unknown. */
        val lteBand: String = "",
        val rsrpDbm: Int? = null,
        val rsrqDb: Int? = null,
        val simStateLabel: String = ""
    )

    data class ChannelOccupancy(
        val channel: Int,
        val band: String,
        val apCount: Int,
        val bestRssi: Int
    )

    data class NetworkState(
        val wifi: WifiGranularState = WifiGranularState(),
        val cellular: CellularGranularState = CellularGranularState(),
        val scannedAPs: List<ScannedAccessPoint> = emptyList(),
        val channelOccupancies: List<ChannelOccupancy> = emptyList(),
        val activeTransport: String = "NONE",
        val isScanning: Boolean = false,
        val isInternetReachable: Boolean = false,
        val latencyMs: Long = 0L,
        val lastUpdated: Long = 0L
    )

    private val _state = MutableStateFlow(NetworkState())
    val state: StateFlow<NetworkState> = _state.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isMonitoring = false
    private var scanReceiverRegistered = false

    fun startMonitoring(ctx: Context) {
        if (isMonitoring) return
        isMonitoring = true

        val appContext = ctx.applicationContext
        registerScanReceiver(appContext)

        // Continuous Network Telemetry Poller
        scope.launch {
            while (isActive) {
                try {
                    pollNetworkTelemetry(appContext)
                } catch (t: Throwable) {
                    Log.w(TAG, "Poll network error: ${t.message}")
                }
                delay(2500L)
            }
        }

        // Autonomous Intelligent Auto-Rejoin Daemon
        scope.launch {
            // Pre-seed system suggestions from persistent vault
            val savedSsids = HioneWifiVault.getAllSavedSsids(appContext)
            savedSsids.forEach { ssid ->
                val pass = HioneWifiVault.getPassword(appContext, ssid) ?: ""
                val sec = HioneWifiVault.getSecurityType(appContext, ssid)
                injectSystemSuggestion(appContext, ssid, pass, sec)
            }

            while (isActive) {
                try {
                    val currentSsid = _state.value.wifi.ssid
                    val isConnected = _state.value.wifi.isConnected
                    val isWifiEnabled = _state.value.wifi.isEnabled
                    if (isWifiEnabled && (!isConnected || currentSsid.isEmpty() || currentSsid == "<unknown ssid>")) {
                        val scanList = _state.value.scannedAPs
                        val knownCandidate = scanList
                            .filter { HioneWifiVault.hasCredential(appContext, it.ssid) }
                            .maxByOrNull { it.rssiDbm }

                        if (knownCandidate != null) {
                            Log.i(TAG, "Autonomous Auto-Rejoin: Found known AP ${knownCandidate.ssid} (${knownCandidate.rssiDbm} dBm). Reconnecting...")
                            val pass = HioneWifiVault.getPassword(appContext, knownCandidate.ssid) ?: ""
                            val sec = HioneWifiVault.getSecurityType(appContext, knownCandidate.ssid)
                            connectToNetwork(appContext, knownCandidate.ssid, pass, sec)
                        } else if (HioneWifiVault.getAllSavedSsids(appContext).isNotEmpty()) {
                            triggerScan(appContext)
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Auto-rejoin error: ${t.message}")
                }
                delay(4000L)
            }
        }

        // Trigger initial AP scan
        triggerScan(appContext)
    }

    private fun registerScanReceiver(ctx: Context) {
        if (scanReceiverRegistered) return
        try {
            val filter = IntentFilter().apply {
                addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
                addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
                addAction(ConnectivityManager.CONNECTIVITY_ACTION)
            }
            ctx.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    scope.launch {
                        processScanResults(context)
                        pollNetworkTelemetry(context)
                    }
                }
            }, filter)
            scanReceiverRegistered = true
        } catch (_: Throwable) {}
    }

    fun triggerScan(ctx: Context) {
        if (!com.hione.player.HionePowerGovernor.allowBackgroundWork) return   // no Wi-Fi scans while screen-off / idle
        scope.launch {
            try {
                _state.value = _state.value.copy(isScanning = true)
                val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                @Suppress("DEPRECATION")
                wm?.startScan()
                delay(1200L)
                processScanResults(ctx.applicationContext)
            } catch (_: Throwable) {
            } finally {
                _state.value = _state.value.copy(isScanning = false)
            }
        }
    }

    /**
     * Permanent Oki Hione Wi-Fi Vault.
     * Persists all known pre-shared keys and security profiles locally across updates,
     * so the user is NEVER asked to re-enter a password for a known network.
     */
    object HioneWifiVault {
        private const val PREFS_NAME = "hione_wifi_vault"
        private const val KEY_PREFIX_PASS = "pass_"
        private const val KEY_PREFIX_SEC = "sec_"

        fun saveCredential(ctx: Context, ssid: String, password: String, securityType: String) {
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_PREFIX_PASS + ssid, password)
                .putString(KEY_PREFIX_SEC + ssid, securityType)
                .commit() // Immediate synchronous write to flash

            // Secondary file backup for 100% durability across package updates
            try {
                val file = java.io.File(ctx.filesDir, "hione_wifi_vault.json")
                val json = if (file.exists()) org.json.JSONObject(file.readText()) else org.json.JSONObject()
                val entry = org.json.JSONObject().apply {
                    put("password", password)
                    put("security", securityType)
                }
                json.put(ssid, entry)
                file.writeText(json.toString())
            } catch (_: Throwable) {}
        }

        fun getPassword(ctx: Context, ssid: String): String? {
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val pass = prefs.getString(KEY_PREFIX_PASS + ssid, null)
            if (pass != null) return pass

            // Check JSON file backup
            return try {
                val file = java.io.File(ctx.filesDir, "hione_wifi_vault.json")
                if (file.exists()) {
                    val json = org.json.JSONObject(file.readText())
                    json.optJSONObject(ssid)?.optString("password", null)
                } else null
            } catch (_: Throwable) { null }
        }

        fun getSecurityType(ctx: Context, ssid: String): String {
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val sec = prefs.getString(KEY_PREFIX_SEC + ssid, null)
            if (sec != null) return sec

            return try {
                val file = java.io.File(ctx.filesDir, "hione_wifi_vault.json")
                if (file.exists()) {
                    val json = org.json.JSONObject(file.readText())
                    json.optJSONObject(ssid)?.optString("security", "WPA2") ?: "WPA2"
                } else "WPA2"
            } catch (_: Throwable) { "WPA2" }
        }

        fun hasCredential(ctx: Context, ssid: String): Boolean {
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.contains(KEY_PREFIX_PASS + ssid)) return true
            return try {
                val file = java.io.File(ctx.filesDir, "hione_wifi_vault.json")
                file.exists() && org.json.JSONObject(file.readText()).has(ssid)
            } catch (_: Throwable) { false }
        }

        fun getAllSavedSsids(ctx: Context): Set<String> {
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val prefSsids = prefs.all.keys
                .filter { it.startsWith(KEY_PREFIX_PASS) }
                .map { it.removePrefix(KEY_PREFIX_PASS) }
                .toSet()

            val fileSsids = try {
                val file = java.io.File(ctx.filesDir, "hione_wifi_vault.json")
                if (file.exists()) {
                    val json = org.json.JSONObject(file.readText())
                    val set = mutableSetOf<String>()
                    json.keys().forEach { set.add(it) }
                    set
                } else emptySet()
            } catch (_: Throwable) { emptySet() }

            return prefSsids + fileSsids
        }

        fun forgetCredential(ctx: Context, ssid: String) {
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .remove(KEY_PREFIX_PASS + ssid)
                .remove(KEY_PREFIX_SEC + ssid)
                .commit()

            try {
                val file = java.io.File(ctx.filesDir, "hione_wifi_vault.json")
                if (file.exists()) {
                    val json = org.json.JSONObject(file.readText())
                    json.remove(ssid)
                    file.writeText(json.toString())
                }
            } catch (_: Throwable) {}
        }
    }

    private fun injectSystemSuggestion(ctx: Context, ssid: String, password: String, securityType: String) {
        val escSsid = ssid.replace("\"", "")
        val sec = if (securityType.contains("WPA3") || securityType.contains("SAE")) "wpa3"
                  else if (securityType.contains("Open")) "open"
                  else "wpa2"
        try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wm != null && Build.VERSION.SDK_INT >= 29) {
                val suggestionBuilder = WifiNetworkSuggestion.Builder()
                    .setSsid(escSsid)
                    .setIsInitialAutojoinEnabled(true)
                    .setIsAppInteractionRequired(false)
                    .setIsUserInteractionRequired(false)

                if (sec == "wpa3" && password.isNotEmpty()) suggestionBuilder.setWpa3Passphrase(password)
                else if (sec == "wpa2" && password.isNotEmpty()) suggestionBuilder.setWpa2Passphrase(password)

                val suggestions = listOf(suggestionBuilder.build())
                wm.removeNetworkSuggestions(suggestions)
                wm.addNetworkSuggestions(suggestions)
            }

            if (sec == "open" || password.isEmpty()) {
                runShellCommand("cmd wifi add-suggestion \"$escSsid\" open -s")
            } else {
                runShellCommand("cmd wifi add-suggestion \"$escSsid\" $sec \"$password\" -s")
            }
        } catch (_: Throwable) {}
    }

    private suspend fun processScanResults(ctx: Context) = withContext(Dispatchers.IO) {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return@withContext

        try {
            var results: List<ScanResult> = emptyList()
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                results = wm.scanResults ?: emptyList()
            }

            val currentInfo = wm.connectionInfo
            val currentBssid = currentInfo?.bssid ?: ""
            val currentSsid = currentInfo?.ssid?.replace("\"", "") ?: ""

            // Combine system configured networks and Hione persistent vault
            @Suppress("DEPRECATION")
            val configuredSsids = try {
                wm.configuredNetworks?.mapNotNull { it.SSID?.replace("\"", "") }?.toSet() ?: emptySet()
            } catch (_: Throwable) { emptySet() }
            val vaultSsids = HioneWifiVault.getAllSavedSsids(ctx)
            val allSavedSsids = configuredSsids + vaultSsids

            val apList = mutableListOf<ScannedAccessPoint>()

            if (results.isNotEmpty()) {
                val mapped = results
                    .filter { it.SSID != null && it.SSID.isNotBlank() }
                    .groupBy { it.SSID }
                    .map { (ssid, bssids) ->
                        val best = bssids.maxByOrNull { it.level } ?: bssids.first()
                        val freq = best.frequency
                        val channel = frequencyToChannel(freq)
                        val band = when {
                            bssids.any { it.frequency >= 5925 } -> "6GHz"
                            bssids.any { it.frequency >= 4900 } -> "5GHz"
                            else -> "2.4GHz"
                        }
                        val standard = when {
                            Build.VERSION.SDK_INT >= 30 && best.wifiStandard == ScanResult.WIFI_STANDARD_11AX -> "Wi-Fi 6 (ax)"
                            Build.VERSION.SDK_INT >= 30 && best.wifiStandard == ScanResult.WIFI_STANDARD_11AC -> "Wi-Fi 5 (ac)"
                            Build.VERSION.SDK_INT >= 30 && best.wifiStandard == ScanResult.WIFI_STANDARD_11N -> "Wi-Fi 4 (n)"
                            freq >= 4900 -> "802.11ac"
                            else -> "802.11n"
                        }
                        val sec = when {
                            best.capabilities.contains("WPA3") || best.capabilities.contains("SAE") -> "WPA3 Personal"
                            best.capabilities.contains("WPA2") || best.capabilities.contains("PSK") -> "WPA2 Personal"
                            best.capabilities.contains("WEP") -> "WEP"
                            best.capabilities.contains("EAP") -> "WPA-Enterprise"
                            else -> "Open Network"
                        }
                        val rssi = best.level
                        val level5 = when {
                            rssi >= -55 -> 5
                            rssi >= -65 -> 4
                            rssi >= -75 -> 3
                            rssi >= -85 -> 2
                            rssi >= -95 -> 1
                            else -> 0
                        }
                        val pct = ((rssi + 100) * 2).coerceIn(0, 100)
                        val isConn = (best.BSSID == currentBssid && currentBssid.isNotEmpty()) || (ssid == currentSsid && currentSsid.isNotEmpty())
                        val isSaved = isConn || allSavedSsids.contains(ssid)

                        ScannedAccessPoint(
                            ssid = ssid,
                            bssid = best.BSSID ?: "",
                            rssiDbm = rssi,
                            signalLevel5 = level5,
                            signalPct = pct,
                            frequencyMhz = freq,
                            channel = channel,
                            bandLabel = band,
                            standard = standard,
                            capabilities = best.capabilities ?: "",
                            securityType = sec,
                            isConnected = isConn,
                            isSaved = isSaved
                        )
                    }
                apList.addAll(mapped)
            } else {
                // Root Shell Fallback: Query cmd wifi list-scan-results or wpa_cli
                try {
                    val out = runShellCommand("cmd wifi list-scan-results || wpa_cli scan_results")
                    val lines = out.lines()
                    for (line in lines) {
                        val parts = line.trim().split(Regex("\\s+"))
                        if (parts.size >= 5 && parts[0].contains(":")) {
                            val bssid = parts[0]
                            val freq = parts[1].toIntOrNull() ?: 2412
                            val rssi = parts[2].toIntOrNull() ?: -70
                            val flags = parts[3]
                            val ssid = parts.subList(4, parts.size).joinToString(" ")
                            if (ssid.isNotBlank() && !ssid.startsWith("[") && apList.none { it.ssid == ssid }) {
                                val channel = frequencyToChannel(freq)
                                val band = if (freq >= 4900) "5GHz" else "2.4GHz"
                                val sec = when {
                                    flags.contains("WPA3") || flags.contains("SAE") -> "WPA3 Personal"
                                    flags.contains("WPA2") || flags.contains("PSK") -> "WPA2 Personal"
                                    flags.contains("WEP") -> "WEP"
                                    flags.contains("EAP") -> "WPA-Enterprise"
                                    else -> "Open Network"
                                }
                                val level5 = when {
                                    rssi >= -55 -> 5
                                    rssi >= -65 -> 4
                                    rssi >= -75 -> 3
                                    rssi >= -85 -> 2
                                    rssi >= -95 -> 1
                                    else -> 0
                                }
                                val pct = ((rssi + 100) * 2).coerceIn(0, 100)
                                val isConn = (bssid.equals(currentBssid, ignoreCase = true)) || (ssid == currentSsid && currentSsid.isNotEmpty())
                                val isSaved = isConn || allSavedSsids.contains(ssid)

                                apList.add(
                                    ScannedAccessPoint(
                                        ssid = ssid,
                                        bssid = bssid,
                                        rssiDbm = rssi,
                                        signalLevel5 = level5,
                                        signalPct = pct,
                                        frequencyMhz = freq,
                                        channel = channel,
                                        bandLabel = band,
                                        standard = "",   // the shell scan output carries no PHY standard — don't infer one from the band
                                        capabilities = flags,
                                        securityType = sec,
                                        isConnected = isConn,
                                        isSaved = isSaved
                                    )
                                )
                            }
                        }
                    }
                } catch (_: Throwable) {}
            }

            val sortedList = apList.sortedWith(compareByDescending<ScannedAccessPoint> { it.isConnected }.thenByDescending { it.isSaved }.thenByDescending { it.rssiDbm })

            // Compute Channel Congestion Spectrogram
            val occMap = mutableMapOf<Int, MutableList<ScannedAccessPoint>>()
            sortedList.forEach { ap ->
                occMap.getOrPut(ap.channel) { mutableListOf() }.add(ap)
            }
            val occupancies = occMap.map { (ch, list) ->
                ChannelOccupancy(
                    channel = ch,
                    band = if (ch > 14) "5GHz" else "2.4GHz",
                    apCount = list.size,
                    bestRssi = list.maxOfOrNull { it.rssiDbm } ?: -100
                )
            }.sortedBy { it.channel }

            _state.value = _state.value.copy(
                scannedAPs = sortedList,
                channelOccupancies = occupancies
            )
        } catch (_: Throwable) {}
    }

    private suspend fun pollNetworkTelemetry(ctx: Context) = withContext(Dispatchers.IO) {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

        val isWifiEnabled = wm?.isWifiEnabled == true
        val wifiInfo: WifiInfo? = wm?.connectionInfo
        val activeNet = cm?.activeNetwork
        val caps = cm?.getNetworkCapabilities(activeNet)
        val hasWifiCap = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

        var hasWlanIp = false
        var wlanIpStr = ""
        try {
            val wlanIf = NetworkInterface.getByName("wlan0")
            if (wlanIf != null && wlanIf.isUp) {
                val addr = wlanIf.inetAddresses.asSequence().firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                if (addr != null) {
                    hasWlanIp = true
                    wlanIpStr = addr.hostAddress ?: ""
                }
            }
        } catch (_: Throwable) {}

        val isWifi = isWifiEnabled && (hasWifiCap || hasWlanIp || (wifiInfo != null && wifiInfo.networkId != -1 && wifiInfo.bssid != null))

        // RSSI only when the framework actually reports one (no "-60 dBm" stand-in while connected).
        val wifiRssi: Int? = if (isWifi) wifiInfo?.rssi?.takeIf { it in -100..0 } else null
        val wifiPct: Int? = wifiRssi?.let { ((it + 100) * 2).coerceIn(0, 100) }
        val wifiLevel5 = when {
            wifiRssi == null -> 0
            wifiRssi >= -55 -> 5
            wifiRssi >= -65 -> 4
            wifiRssi >= -75 -> 3
            wifiRssi >= -85 -> 2
            wifiRssi >= -95 -> 1
            else -> 0
        }

        val freq = if (isWifi) (wifiInfo?.frequency?.takeIf { it > 0 } ?: 0) else 0
        val channel = frequencyToChannel(freq)
        val band = when {
            freq >= 5925 -> "6GHz"
            freq >= 4900 -> "5GHz"
            freq in 2400..2500 -> "2.4GHz"
            else -> ""   // frequency not reported → band unknown, not guessed
        }
        val wifiStandard = if (isWifi && Build.VERSION.SDK_INT >= 30) when (wifiInfo?.wifiStandard) {
            ScanResult.WIFI_STANDARD_11AX -> "Wi-Fi 6 (ax)"
            ScanResult.WIFI_STANDARD_11AC -> "Wi-Fi 5 (ac)"
            ScanResult.WIFI_STANDARD_11N -> "Wi-Fi 4 (n)"
            ScanResult.WIFI_STANDARD_LEGACY -> "Legacy"
            else -> ""
        } else ""

        // The connected SSID is whatever the framework reports — never a saved SSID or a made-up name.
        var cleanSsid = if (isWifi) (wifiInfo?.ssid?.replace("\"", "") ?: "") else ""
        if (cleanSsid == "<unknown ssid>") cleanSsid = ""

        val ipInt = if (isWifi) (wifiInfo?.ipAddress ?: 0) else 0
        val calcIpStr = if (ipInt != 0) {
            String.format(
                "%d.%d.%d.%d",
                ipInt and 0xff,
                ipInt shr 8 and 0xff,
                ipInt shr 16 and 0xff,
                ipInt shr 24 and 0xff
            )
        } else wlanIpStr
        val ipStr = if (calcIpStr.isNotEmpty() && calcIpStr != "0.0.0.0") calcIpStr else ""

        // Gateway & DNS inspection from active network link (empty until the link reports them —
        // no public-resolver placeholders).
        var gateway = ""
        var dns1 = ""
        var dns2 = ""
        try {
            val net = cm?.activeNetwork
            val linkProps = cm?.getLinkProperties(net)
            val gw = linkProps?.routes?.firstOrNull { it.isDefaultRoute }?.gateway?.hostAddress
            if (!gw.isNullOrBlank()) gateway = gw
            val dnsList = linkProps?.dnsServers?.mapNotNull { it.hostAddress } ?: emptyList()
            if (dnsList.isNotEmpty()) dns1 = dnsList[0]
            if (dnsList.size > 1) dns2 = dnsList[1]
        } catch (_: Throwable) {}

        val wifiState = WifiGranularState(
            isEnabled = isWifiEnabled,
            isConnected = isWifi,
            ssid = cleanSsid,
            bssid = if (isWifi) wifiInfo!!.bssid ?: "" else "",
            rssiDbm = wifiRssi,
            signalLevel5 = wifiLevel5,
            signalPct = wifiPct,
            linkSpeedMbps = if (isWifi) wifiInfo!!.linkSpeed else 0,
            rxLinkSpeedMbps = if (Build.VERSION.SDK_INT >= 29 && isWifi) wifiInfo!!.rxLinkSpeedMbps else (wifiInfo?.linkSpeed ?: 0),
            txLinkSpeedMbps = if (Build.VERSION.SDK_INT >= 29 && isWifi) wifiInfo!!.txLinkSpeedMbps else (wifiInfo?.linkSpeed ?: 0),
            frequencyMhz = freq,
            channel = channel,
            bandLabel = band,
            standard = wifiStandard,
            ipAddress = ipStr,
            gateway = gateway,
            dns1 = dns1,
            dns2 = dns2
        )

        val simState = tm?.simState ?: TelephonyManager.SIM_STATE_UNKNOWN
        val isSimReady = simState == TelephonyManager.SIM_STATE_READY
        var cellLevel5 = 0
        var cellDbm: Int? = null
        // Real network type from the modem (READ_PHONE_STATE) — was hardcoded "LTE+ 4G".
        var cellNetworkType = try {
            val nt = tm?.let { if (android.os.Build.VERSION.SDK_INT >= 30) it.dataNetworkType else @Suppress("DEPRECATION") it.networkType } ?: 0
            when (nt) {
                android.telephony.TelephonyManager.NETWORK_TYPE_NR -> "5G"
                android.telephony.TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
                android.telephony.TelephonyManager.NETWORK_TYPE_HSPAP -> "H+"
                android.telephony.TelephonyManager.NETWORK_TYPE_HSPA, android.telephony.TelephonyManager.NETWORK_TYPE_HSDPA, android.telephony.TelephonyManager.NETWORK_TYPE_HSUPA, android.telephony.TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
                android.telephony.TelephonyManager.NETWORK_TYPE_EDGE, android.telephony.TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
                else -> ""
            }
        } catch (_: Throwable) { "" }

        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val signal = tm?.signalStrength
                if (signal != null) {
                    val rawLevel = signal.level // 0..4
                    cellLevel5 = rawLevel.coerceIn(0, 4)
                    val cellSignalStrengths = signal.cellSignalStrengths
                    val firstDbm = cellSignalStrengths.firstOrNull()?.dbm
                    if (firstDbm != null && firstDbm < 0 && firstDbm > -150) {
                        cellDbm = firstDbm
                    }
                }
            }
        } catch (_: Throwable) {}

        val cellPct: Int? = cellDbm?.let { ((it + 120) * (100.0 / 70.0)).toInt().coerceIn(0, 100) }

        // Operator name as reported by the SIM / network — "" when neither reports one.
        val simOperator = (tm?.simOperatorName?.takeIf { it.isNotBlank() } ?: tm?.networkOperatorName?.takeIf { it.isNotBlank() } ?: "")
        // Data-only is a real TelephonyManager fact (voice capability), not an assumption.
        val dataOnly = isSimReady && runCatching { tm?.isVoiceCapable == false }.getOrDefault(false)
        val simLabel = when {
            !isSimReady -> when (simState) {
                TelephonyManager.SIM_STATE_ABSENT -> "NO SIM"
                TelephonyManager.SIM_STATE_PIN_REQUIRED, TelephonyManager.SIM_STATE_PUK_REQUIRED -> "SIM LOCKED"
                TelephonyManager.SIM_STATE_NOT_READY -> "SIM NOT READY"
                else -> ""
            }
            dataOnly -> "DATA-ONLY SIM"
            else -> "SIM READY"
        }
        val cellState = CellularGranularState(
            isConnected = isSimReady,
            isDataOnlySim = dataOnly,
            carrierName = simOperator,
            networkType = cellNetworkType,
            signalDbm = cellDbm,
            signalLevel5 = cellLevel5,
            signalPct = cellPct,
            lteBand = "",   // not exposed by the public API here — never derived from the RAT label
            simStateLabel = simLabel
        )

        val hasCellData = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) } == true
        val activeTransport = when {
            isWifi -> "WIFI"
            hasCellData -> "CELLULAR"
            else -> "NONE"
        }

        // Reachability is the measured probe, not an assumption.
        val ping = measurePingMs()
        _state.value = _state.value.copy(
            wifi = wifiState,
            cellular = cellState,
            activeTransport = activeTransport,
            isInternetReachable = ping >= 0L,
            latencyMs = ping,
            lastUpdated = System.currentTimeMillis()
        )

        // Store sample in historical metric database
        try {
            HioneMetricDatabase.getInstance(ctx).insertNetwork(
                HioneMetricDatabase.NetworkRecord(
                    timestamp = System.currentTimeMillis(),
                    transport = activeTransport,
                    wifiRssiDbm = if (isWifi) wifiRssi else null,
                    wifiSsid = if (isWifi) cleanSsid else null,
                    wifiFreqMhz = if (isWifi) freq else null,
                    wifiLinkSpeedMbps = if (isWifi) wifiInfo?.linkSpeed else null,
                    cellularDbm = if (isSimReady) cellDbm else null,
                    cellularType = cellNetworkType.ifEmpty { "—" },
                    cellularOperator = simOperator.ifEmpty { "—" },
                    isConnected = isWifi || isSimReady
                )
            )
        } catch (_: Throwable) {}
    }

    // ================================================================
    // UNRESTRICTED HARDWARE & CONNECTION CONTROLS
    // ================================================================

    fun setWifiEnabled(ctx: Context, enable: Boolean) {
        scope.launch {
            try {
                val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                @Suppress("DEPRECATION")
                wm?.setWifiEnabled(enable)
            } catch (_: Throwable) {}

            // Direct Root HAL Fallback
            try {
                val stateStr = if (enable) "enable" else "disable"
                runShellCommand("cmd wifi set-wifi-enabled $stateStr || svc wifi $stateStr")
            } catch (_: Throwable) {}

            delay(1000L)
            pollNetworkTelemetry(ctx.applicationContext)
            if (enable) triggerScan(ctx.applicationContext)
        }
    }

    fun connectToNetwork(ctx: Context, ssid: String, password: String = "", securityType: String = "WPA2") {
        scope.launch {
            val escSsid = ssid.replace("\"", "")
            val finalPassword = if (password.isNotEmpty()) {
                HioneWifiVault.saveCredential(ctx, escSsid, password, securityType)
                password
            } else {
                HioneWifiVault.getPassword(ctx, escSsid) ?: ""
            }

            val sec = if (securityType.contains("WPA3") || securityType.contains("SAE")) "wpa3"
                      else if (securityType.contains("Open")) "open"
                      else "wpa2"

            try {
                // 1. Android 10+ Native WifiNetworkSuggestion (Permanent System Suggestion)
                val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                if (wm != null && Build.VERSION.SDK_INT >= 29) {
                    val suggestionBuilder = WifiNetworkSuggestion.Builder()
                        .setSsid(escSsid)
                        .setIsInitialAutojoinEnabled(true)
                        .setIsAppInteractionRequired(false)
                        .setIsUserInteractionRequired(false)

                    if (sec == "wpa3") {
                        if (finalPassword.isNotEmpty()) suggestionBuilder.setWpa3Passphrase(finalPassword)
                    } else if (sec == "wpa2") {
                        if (finalPassword.isNotEmpty()) suggestionBuilder.setWpa2Passphrase(finalPassword)
                    }

                    // Remove existing suggestion for this SSID to overwrite cleanly
                    val currentSuggestions = listOf(suggestionBuilder.build())
                    wm.removeNetworkSuggestions(currentSuggestions)
                    val status = wm.addNetworkSuggestions(currentSuggestions)
                    Log.d(TAG, "addNetworkSuggestions for $escSsid returned status: $status")
                }

                // 2. Android Command-line direct connect & suggestion injection via system shell
                if (sec == "open" || finalPassword.isEmpty()) {
                    runShellCommand("cmd wifi connect-network \"$escSsid\" open || cmd wifi add-suggestion \"$escSsid\" open -s")
                } else {
                    runShellCommand("cmd wifi connect-network \"$escSsid\" $sec \"$finalPassword\" || cmd wifi add-suggestion \"$escSsid\" $sec \"$finalPassword\" -s")
                }

                // 3. Legacy WifiConfiguration fallback for direct association
                if (wm != null) {
                    @Suppress("DEPRECATION")
                    val existing = wm.configuredNetworks?.find { it.SSID == "\"$escSsid\"" }
                    if (existing != null) {
                        @Suppress("DEPRECATION")
                        wm.enableNetwork(existing.networkId, true)
                        @Suppress("DEPRECATION")
                        wm.reconnect()
                    } else {
                        @Suppress("DEPRECATION")
                        val config = WifiConfiguration().apply {
                            SSID = "\"$escSsid\""
                            if (finalPassword.isNotEmpty()) {
                                preSharedKey = "\"$finalPassword\""
                            } else {
                                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                            }
                        }
                        @Suppress("DEPRECATION")
                        val netId = wm.addNetwork(config)
                        if (netId != -1) {
                            @Suppress("DEPRECATION")
                            wm.enableNetwork(netId, true)
                            @Suppress("DEPRECATION")
                            wm.reconnect()
                        }
                    }
                    @Suppress("DEPRECATION")
                    wm.reassociate()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Connect error: ${t.message}")
            }

            delay(2000L)
            pollNetworkTelemetry(ctx.applicationContext)
            triggerScan(ctx.applicationContext)
        }
    }

    fun disconnectWifi(ctx: Context) {
        scope.launch {
            try {
                val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                @Suppress("DEPRECATION")
                wm?.disconnect()
                runShellCommand("cmd wifi disconnect")
            } catch (_: Throwable) {}
            delay(1000L)
            pollNetworkTelemetry(ctx.applicationContext)
        }
    }

    fun forgetNetwork(ctx: Context, ssid: String) {
        scope.launch {
            val escSsid = ssid.replace("\"", "")
            HioneWifiVault.forgetCredential(ctx, escSsid)
            try {
                val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                if (wm != null) {
                    @Suppress("DEPRECATION")
                    val existing = wm.configuredNetworks?.find { it.SSID == "\"$escSsid\"" }
                    if (existing != null) {
                        @Suppress("DEPRECATION")
                        wm.removeNetwork(existing.networkId)
                    }
                    if (Build.VERSION.SDK_INT >= 29) {
                        val dummy = WifiNetworkSuggestion.Builder().setSsid(escSsid).build()
                        wm.removeNetworkSuggestions(listOf(dummy))
                    }
                }
                runShellCommand("cmd wifi remove-suggestion \"$escSsid\"")
            } catch (_: Throwable) {}
            delay(1000L)
            pollNetworkTelemetry(ctx.applicationContext)
            triggerScan(ctx.applicationContext)
        }
    }

    // FAKE-DATA FIX: setPowerSaveMode() used to run `su -c "iw dev wlan0 set power_save ..."`
    // and then set isPowerSaveOn to whatever was asked for regardless of the result. There is no
    // su and no `iw` on this device, so the write always failed while the UI reported it applied.
    // Removed along with its switch; the observatory now only reads the radio state back.

    fun runShellCommand(cmd: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            process.waitFor()
            output.toString().trim()
        } catch (_: Throwable) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val output = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    output.append(line).append("\n")
                }
                process.waitFor()
                output.toString().trim()
            } catch (_: Throwable) {
                ""
            }
        }
    }

    // FAKE-DATA FIX: setBandPreference() only wrote its own state field — it never called the
    // Wi-Fi stack, so the highlighted AUTO / 5G / 2.4G chip was pure theatre. Removed; the real
    // band the radio is associated on is already published as WifiGranularState.bandLabel.

    fun frequencyToChannel(freq: Int): Int {
        return when {
            freq == 2484 -> 14
            freq in 2412..2472 -> (freq - 2412) / 5 + 1
            freq in 5170..5825 -> (freq - 5170) / 5 + 34
            freq in 5945..7125 -> (freq - 5945) / 5 + 1
            else -> 0
        }
    }

    /** Best-effort real RTT (ms) via a short socket connect to a DNS host — replaces the old
     *  hardcoded 12ms "ping". Returns the measured value, or -1 when unreachable. */
    private fun measurePingMs(): Long = try {
        val host = java.net.InetSocketAddress("1.1.1.1", 53)
        val t0 = System.nanoTime()
        java.net.Socket().use { it.connect(host, 800) }
        ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
    } catch (_: Throwable) { -1L }

}
