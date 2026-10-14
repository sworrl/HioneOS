package com.hione.launcher.arco

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * mDNS/DNS-SD discovery of the arcobocconotto daemon via Android [NsdManager]
 * (PAIRING_SPEC.md §1: `_arcobocconotto._tcp.local`, port 8000). Manual
 * IP/hostname entry is the fallback UI path (see HioneArcoSettingsActivity),
 * kept entirely separate from this class.
 */
class ArcoDiscovery(context: Context) {
    private val TAG = "ArcoDiscovery"
    private val appContext = context.applicationContext
    private val nsdManager by lazy { appContext.getSystemService(Context.NSD_SERVICE) as NsdManager }

    private val _discovered = MutableStateFlow<List<ArcoDiscoveredServer>>(emptyList())
    val discovered: StateFlow<List<ArcoDiscoveredServer>> = _discovered.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private var listener: NsdManager.DiscoveryListener? = null

    private val resolveListenerFactory: () -> NsdManager.ResolveListener = {
        object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
            }

            override fun onServiceResolved(resolved: NsdServiceInfo) {
                val host = resolved.host?.hostAddress ?: return
                val port = resolved.port
                val name = resolved.serviceName ?: "arcobocconotto"
                _discovered.update { current ->
                    val entry = ArcoDiscoveredServer(name = name, host = host, port = port)
                    if (current.any { it.host == host && it.port == port }) current
                    else current + entry
                }
            }
        }
    }

    fun startDiscovery() {
        if (listener != null) return
        _discovered.value = emptyList()
        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                _isDiscovering.value = true
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceType.contains("_arcobocconotto")) return
                try {
                    resolveService(serviceInfo)
                } catch (t: Throwable) {
                    Log.w(TAG, "resolveService threw", t)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                // Leave already-resolved entries in the list; a lost mDNS
                // advertisement doesn't necessarily mean the server is gone
                // (flaky multicast on some APs), and the REST/WS calls will
                // surface a real failure if it actually is.
            }

            override fun onDiscoveryStopped(serviceType: String) {
                _isDiscovering.value = false
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Start discovery failed: $errorCode")
                _isDiscovering.value = false
                listener = null
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                _isDiscovering.value = false
                listener = null
            }
        }
        listener = discoveryListener
        try {
            nsdManager.discoverServices(ArcoConfig.MDNS_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (t: Throwable) {
            Log.w(TAG, "discoverServices threw", t)
            _isDiscovering.value = false
            listener = null
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveService(serviceInfo: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            nsdManager.resolveService(serviceInfo, appContext.mainExecutor, resolveListenerFactory())
        } else {
            nsdManager.resolveService(serviceInfo, resolveListenerFactory())
        }
    }

    fun stopDiscovery() {
        val l = listener ?: return
        try {
            nsdManager.stopServiceDiscovery(l)
        } catch (t: Throwable) {
            Log.w(TAG, "stopServiceDiscovery threw", t)
        }
        listener = null
        _isDiscovering.value = false
    }
}
