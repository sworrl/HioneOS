package com.hione.systemui

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.net.wifi.ScanResult
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.hardware.display.DisplayManager
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Surface
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

data class QsTile(
    val id: String,
    val label: String,
    val subtitle: String,
    val icon: ImageVector,
    val isActive: Boolean,
    val onClick: () -> Unit,
    val onLongClick: (() -> Unit)? = null,
    /** False when the state cannot be read (radio missing, codec not answering). Drawn dimmed; a tap still tries. */
    val isAvailable: Boolean = true,
    /** Long-press opens another app's screen, so the shade should get out of the way. */
    val longPressOpensUi: Boolean = true,
    /** Tap opens another app's screen: the shade collapses first, then [onClick] runs. */
    val clickOpensUi: Boolean = false,
    /** Beat period in ms to pulse the icon at while music plays; 0 = no pulse. */
    val beatMs: Int = 0,
    /** ARGB accent for this tile, 0 = the shade's own. The DAC tiles use dacTheme's primary. */
    val accent: Int = 0
)

object QuickSettingsModel {

    fun openHioneSettings(ctx: Context, section: String? = null) {
        try {
            val intent = Intent().setClassName("com.hione.settings", "com.hione.settings.HioneSettingsActivity").apply {
                if (section != null) putExtra("extra_section", section)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            ctx.startActivity(intent)
        } catch (_: Throwable) {
            try {
                val fallback = ctx.packageManager.getLaunchIntentForPackage("com.hione.settings")?.apply {
                    if (section != null) putExtra("extra_section", section)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                if (fallback != null) ctx.startActivity(fallback)
            } catch (_: Throwable) {}
        }
    }

    data class WifiState(val isEnabled: Boolean, val isAssociated: Boolean, val ssid: String, val level: Int?)

    fun getWifiState(ctx: Context): WifiState {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val isEnabled = wm?.isWifiEnabled == true
        @Suppress("DEPRECATION")
        val info = wm?.connectionInfo
        // Associated (not just radio-on) — subtitle said "Connected" whenever the radio was on.
        val associated = isEnabled && (info?.networkId ?: -1) != -1
        val rawSsid = info?.ssid?.replace("\"", "") ?: ""
        val ssid = if (!associated || rawSsid.isBlank() || rawSsid == "<unknown ssid>") "Wi-Fi" else rawSsid
        // Signal level only when actually associated; the old code turned "no link" into -100 dBm → level 0.
        val level = if (associated) info?.rssi?.let { WifiManager.calculateSignalLevel(it, 5) } else null
        return WifiState(isEnabled, associated, ssid, level)
    }

    fun toggleWifi(ctx: Context, enable: Boolean) {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        runCatching { wm?.isWifiEnabled = enable }
        RootShell.execFast("svc wifi " + (if (enable) "enable" else "disable"))
    }

    fun getBluetoothInfo(ctx: Context): Pair<Boolean, String> {
        val bt = runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull()
        val isEnabled = runCatching { bt?.isEnabled == true }.getOrDefault(false)
        var connectedName = ""
        if (isEnabled && bt != null) {
            runCatching {
                // Real CONNECTED device (was showing any PAIRED device as "Active").
                val a2dp = bt.getProfileConnectionState(android.bluetooth.BluetoothProfile.A2DP)
                val hs = bt.getProfileConnectionState(android.bluetooth.BluetoothProfile.HEADSET)
                if (a2dp == android.bluetooth.BluetoothProfile.STATE_CONNECTED ||
                    hs == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                    // Name of the device that is ACTUALLY connected (BluetoothDevice.isConnected,
                    // SystemApi via reflection). The old predicate never looked at the device, so
                    // it named whichever bonded device came first — often the wrong one.
                    connectedName = bt.bondedDevices?.firstOrNull { dev ->
                        runCatching { dev.javaClass.getMethod("isConnected").invoke(dev) as? Boolean }.getOrNull() == true
                    }?.let { runCatching { it.name }.getOrNull() } ?: "Connected"
                }
            }
        }
        val label = if (connectedName.isNotBlank()) connectedName else if (isEnabled) "Bluetooth On" else "Bluetooth Off"
        return Pair(isEnabled, label)
    }

    fun toggleBluetooth(ctx: Context, enable: Boolean) {
        val bt = runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull()
        runCatching {
            if (enable) bt?.enable() else bt?.disable()
        }
        RootShell.execFast("svc bluetooth " + (if (enable) "enable" else "disable"))
    }

    // ------------------------------------------------------------------ rotation

    fun isAutoRotate(ctx: Context): Boolean =
        runCatching { Settings.System.getInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1 }.getOrDefault(false)

    /** Surface.ROTATION_* the default display is showing right now. */
    fun displayRotation(ctx: Context): Int = runCatching {
        // DisplayManager, not ctx.display: this runs from the accessibility service context too,
        // which is not a visual context and throws on getDisplay().
        (ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY).rotation
    }.getOrDefault(Surface.ROTATION_0)

    /** USER_ROTATION, i.e. the orientation a locked display is held in. */
    fun lockedRotation(ctx: Context): Int =
        runCatching { Settings.System.getInt(ctx.contentResolver, Settings.System.USER_ROTATION, Surface.ROTATION_0) }.getOrDefault(Surface.ROTATION_0)

    /**
     * Auto-rotate on/off, the way AOSP's RotationPolicy does it but through the settings provider:
     * DisplayRotation observes both keys and applies them, so no IWindowManager call is needed.
     * Locking first pins USER_ROTATION to the CURRENT rotation, otherwise turning auto-rotate off
     * while sideways would snap the screen back to whatever USER_ROTATION held last time.
     * Needs WRITE_SETTINGS (signature|appop, granted by the platform signature); root shell fallback.
     */
    fun setAutoRotate(ctx: Context, enable: Boolean) {
        val cr = ctx.contentResolver
        val rot = displayRotation(ctx)
        val ok = runCatching {
            if (!enable) Settings.System.putInt(cr, Settings.System.USER_ROTATION, rot)
            Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, if (enable) 1 else 0)
        }.getOrDefault(false)
        if (!ok) {
            Log.w("QuickSettingsModel", "Settings.System rotation write refused; root shell fallback")
            RootShell.execFast(
                (if (!enable) "settings put system user_rotation $rot; " else "") +
                    "settings put system accelerometer_rotation " + (if (enable) 1 else 0)
            )
        }
        // Saved so the choice survives a reboot (see HioneRotationKeeper).
        HioneRotationKeeper.record(ctx, enable, if (enable) lockedRotation(ctx) else rot)
    }

    private fun rotationName(rot: Int) = when (rot) {
        Surface.ROTATION_90 -> "Landscape"
        Surface.ROTATION_180 -> "Portrait (flipped)"
        Surface.ROTATION_270 -> "Landscape (flipped)"
        else -> "Portrait"
    }

    fun getTiles(ctx: Context, scope: CoroutineScope, onRefresh: () -> Unit): List<QsTile> {
        val list = mutableListOf<QsTile>()

        // 1. Wi-Fi (Internet) — "Connected" only when actually associated with a network.
        val wifi = getWifiState(ctx)
        val isWifiOn = wifi.isEnabled
        list.add(
            QsTile(
                id = "wifi",
                label = if (wifi.isAssociated) wifi.ssid else if (isWifiOn) "Wi-Fi" else "Internet",
                subtitle = when {
                    !isWifiOn -> "Off"
                    wifi.isAssociated -> wifi.level?.let { "Connected · signal $it/4" } ?: "Connected"
                    else -> "On · not connected"
                },
                icon = if (isWifiOn) Icons.Default.Wifi else Icons.Default.WifiOff,
                isActive = isWifiOn,
                isAvailable = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) != null,
                onClick = {
                    val next = !isWifiOn
                    toggleWifi(ctx, next)
                    onRefresh()
                },
                onLongClick = { openHioneSettings(ctx, "wireless") }
            )
        )

        // 2. Bluetooth
        val (isBtOn, btLabel) = getBluetoothInfo(ctx)
        list.add(
            QsTile(
                id = "bluetooth",
                label = if (isBtOn) btLabel else "Bluetooth",
                subtitle = if (isBtOn) "On" else "Off",
                icon = if (isBtOn) Icons.Default.Bluetooth else Icons.Default.BluetoothDisabled,
                isActive = isBtOn,
                isAvailable = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter != null,
                onClick = {
                    val next = !isBtOn
                    toggleBluetooth(ctx, next)
                    onRefresh()
                },
                onLongClick = { openHioneSettings(ctx, "bluetooth") }
            )
        )

        // 3-6. DAC tiles. State is what the DAC was last told (persist.vendor.audio.hione.*, the same
        //    values Hione Music and the Hardware app show). Writes go through com.hione.sysbridge.
        //    A tile is dimmed when the bridge is missing, and a failed set says so in a toast.
        val dacBridgeOk = DacBridge.available(ctx)
        // All four DAC tiles take their accent from the applied combo (dacTheme), the same colors
        // as the status bar badge and the Hardware app.
        val dacAccent = dacTheme(HioneDacBadge.readState(ctx)).primary
        fun dacResult(r: DacBridge.Result) {
            if (r == DacBridge.Result.CONFIRMED) return
            val msg = DacBridge.lastProblem ?: return
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(ctx.applicationContext, msg, android.widget.Toast.LENGTH_LONG).show()
            }
        }
        val currentFilter = CirrusLogicManager.getDigitalFilter(ctx)
        list.add(
            QsTile(
                id = "cs43198_filter",
                accent = dacAccent,
                label = "DAC Filter",
                subtitle = when (currentFilter) {
                    CirrusLogicManager.DigitalFilter.FAST_LINEAR -> "Fast Linear"
                    CirrusLogicManager.DigitalFilter.FAST_MINIMUM -> "Fast Min"
                    CirrusLogicManager.DigitalFilter.SLOW_LINEAR -> "Slow Linear"
                    CirrusLogicManager.DigitalFilter.SLOW_MINIMUM -> "Slow Min"
                    CirrusLogicManager.DigitalFilter.NOS -> "NOS"
                    null -> "Not set, tap to set"
                },
                icon = Icons.Default.GraphicEq,
                isActive = currentFilter != null,
                isAvailable = dacBridgeOk,
                onClick = {
                    val all = CirrusLogicManager.DigitalFilter.values()
                    val nextIdx = if (currentFilter == null) 0 else (currentFilter.ordinal + 1) % all.size
                    val nextFilter = all[nextIdx]
                    scope.launch {
                        dacResult(CirrusLogicManager.setDigitalFilter(ctx, nextFilter))
                        onRefresh()
                    }
                },
                onLongClick = { DacSettingsLink.open(ctx) }
            )
        )

        // 4. Headphone Gain (PO Gain)
        val currentGain = CirrusLogicManager.getGainMode(ctx)
        val isHighGain = currentGain == CirrusLogicManager.GainMode.HIGH
        list.add(
            QsTile(
                id = "cs43198_gain",
                accent = dacAccent,
                label = "PO Gain",
                subtitle = when (currentGain) {
                    CirrusLogicManager.GainMode.HIGH -> "High (0 dB)"
                    CirrusLogicManager.GainMode.LOW -> "Low (-12 dB)"
                    null -> "Not set, tap to set"
                },
                icon = Icons.Default.VolumeUp,
                isActive = isHighGain,
                isAvailable = dacBridgeOk,
                onClick = {
                    val next = if (isHighGain) CirrusLogicManager.GainMode.LOW else CirrusLogicManager.GainMode.HIGH
                    scope.launch {
                        dacResult(CirrusLogicManager.setGainMode(ctx, next))
                        onRefresh()
                    }
                },
                onLongClick = { DacSettingsLink.open(ctx) }
            )
        )

        // 5. Audio Turbo High Power
        val isTurbo = CirrusLogicManager.isHighPowerEnabled(ctx)
        list.add(
            QsTile(
                id = "audio_turbo",
                accent = dacAccent,
                label = "High power",
                subtitle = if (isTurbo) "On" else "Off",
                icon = Icons.Default.FlashOn,
                isActive = isTurbo,
                isAvailable = dacBridgeOk,
                onClick = {
                    scope.launch {
                        dacResult(CirrusLogicManager.setHighPowerEnabled(ctx, !isTurbo))
                        onRefresh()
                    }
                },
                onLongClick = { DacSettingsLink.open(ctx) }
            )
        )

        // 6. Dynamic Range Enhancement (DRE)
        val isDre = CirrusLogicManager.isDreEnabled(ctx)
        list.add(
            QsTile(
                id = "dre_mode",
                accent = dacAccent,
                label = "DRE",
                subtitle = if (isDre) "On" else "Off",
                icon = Icons.Default.Tune,
                isActive = isDre,
                isAvailable = dacBridgeOk,
                onClick = {
                    scope.launch {
                        dacResult(CirrusLogicManager.setDreEnabled(ctx, !isDre))
                        onRefresh()
                    }
                },
                onLongClick = { DacSettingsLink.open(ctx) }
            )
        )


        // 8. Wireless ADB — the port shown is the one adbd is really bound to (service.adb.tcp.port)
        val adbPort = WirelessAdbManager.currentPort()
        val isAdb = adbPort != null
        list.add(
            QsTile(
                id = "wireless_adb",
                label = "Wireless ADB",
                subtitle = if (adbPort != null) "Port $adbPort" else "Off",
                icon = Icons.Default.Cable,
                isActive = isAdb,
                onClick = {
                    scope.launch {
                        WirelessAdbManager.setEnabled(!isAdb)
                        onRefresh()
                    }
                },
                onLongClick = { openHioneSettings(ctx, "system_about") }
            )
        )

        // Rotation lock. Active = auto-rotate on (AOSP semantics); off shows the held orientation.
        val autoRotate = isAutoRotate(ctx)
        val held = lockedRotation(ctx)
        val heldLandscape = held == Surface.ROTATION_90 || held == Surface.ROTATION_270
        list.add(
            QsTile(
                id = "rotation",
                label = if (autoRotate) "Auto-rotate" else "Rotation lock",
                subtitle = if (autoRotate) "On" else "Locked · " + rotationName(held),
                icon = when {
                    autoRotate -> Icons.Default.ScreenRotation
                    heldLandscape -> Icons.Default.ScreenLockLandscape
                    else -> Icons.Default.ScreenLockPortrait
                },
                isActive = autoRotate,
                onClick = {
                    setAutoRotate(ctx, !autoRotate)
                    onRefresh()
                },
                onLongClick = { openHioneSettings(ctx, "display") }
            )
        )

        // 9. Airplane Mode
        val isAir = Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        list.add(
            QsTile(
                id = "airplane_mode",
                label = "Airplane Mode",
                subtitle = if (isAir) "On" else "Off",
                icon = Icons.Default.Flight,
                isActive = isAir,
                onClick = {
                    val next = if (isAir) 0 else 1
                    Settings.Global.putInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, next)
                    RootShell.execFast("settings put global airplane_mode_on $next; am broadcast -a android.intent.action.AIRPLANE_MODE --ez state " + (next == 1))
                    onRefresh()
                },
                onLongClick = { openHioneSettings(ctx, "wireless") }
            )
        )

        return list
    }
}
