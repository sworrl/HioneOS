package com.hione.player.api

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import java.net.Inet4Address

/**
 * Finds the Wi-Fi interface's IPv4 address so the remote API and cast server can bind to it
 * instead of 0.0.0.0. No Wi-Fi network means no LAN listener at all, so nothing is ever
 * reachable over cellular, USB tethering or a VPN interface.
 */
object HioneLanBinding {
    private const val TAG = "HioneLanBinding"

    fun wifiIpv4(ctx: Context): Inet4Address? {
        val cm = ctx.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return null
        return try {
            @Suppress("DEPRECATION")
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) continue
                val addr = cm.getLinkProperties(n)?.let { ipv4Of(it) } ?: continue
                return addr
            }
            null
        } catch (t: Throwable) {
            Log.w(TAG, "could not read Wi-Fi address: ${t.message}")
            null
        }
    }

    private fun ipv4Of(lp: LinkProperties): Inet4Address? =
        lp.linkAddresses.map { it.address }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress } as? Inet4Address

    /** Calls [onChange] whenever a Wi-Fi network appears, changes address or goes away. */
    fun watch(ctx: Context, onChange: () -> Unit): ConnectivityManager.NetworkCallback? {
        val cm = ctx.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return null
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onChange()
            override fun onLost(network: Network) = onChange()
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = onChange()
        }
        return try {
            cm.registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), cb
            )
            cb
        } catch (t: Throwable) {
            Log.w(TAG, "network watch failed: ${t.message}")
            null
        }
    }

    fun unwatch(ctx: Context, cb: ConnectivityManager.NetworkCallback?) {
        cb ?: return
        val cm = ctx.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching { cm.unregisterNetworkCallback(cb) }
    }
}
