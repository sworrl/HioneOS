package com.hione.settings

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface

object WirelessAdbManager {
    private const val DEFAULT_PORT = 5555

    /**
     * REAL TCP port adbd is bound to, read via SystemProperties (no root needed). The old
     * `RootShell.execOut("getprop …")` needs an `su` session, which this no-root OS never has,
     * so the toggle was permanently "off" regardless of the real adbd state.
     */
    fun currentPort(): Int? = try {
        (Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            .invoke(null, "service.adb.tcp.port") as? String)?.trim()?.toIntOrNull()?.takeIf { it > 0 }
    } catch (_: Throwable) { null }

    fun isEnabled(): Boolean = currentPort() != null

    /**
     * Was a stub: it fired the setprop through RootShell (no su on this device, so a no-op) and
     * returned `true` regardless, i.e. it reported "wireless ADB is now on" without checking.
     * It now waits for adbd to come back and returns what the port property ACTUALLY says.
     */
    suspend fun setEnabled(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        val port = if (enabled) "$DEFAULT_PORT" else "-1"
        RootShell.execFast("setprop service.adb.tcp.port $port; stop adbd; start adbd")
        kotlinx.coroutines.delay(800)          // adbd restart
        (currentPort() != null) == enabled
    }

    fun getWifiIpAddress(context: Context): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isUp && !iface.isLoopback && (iface.name.startsWith("wlan") || iface.name.startsWith("eth"))) {
                    val addresses = iface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val addr = addresses.nextElement()
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            return addr.hostAddress
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
        return null
    }
}
