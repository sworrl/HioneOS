package com.hione.systemui.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.hione.systemui.RootShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

data class HioneBtDevice(
    val device: BluetoothDevice,
    val name: String,
    val address: String,
    val bondState: Int,
    val isConnected: Boolean,
    val isConnecting: Boolean = false,
    /** Real dBm from the scan result; null when the stack reported no RSSI (never a made-up value). */
    val rssi: Int? = null,
    val deviceType: DeviceType = DeviceType.AUDIO_HEADSET
)

enum class DeviceType {
    AUDIO_HEADSET,
    AUDIO_SPEAKER,
    AUDIO_DAC,
    INPUT_KEYBOARD_MOUSE,
    PHONE_WATCH,
    GENERIC
}

@SuppressLint("MissingPermission")
object HioneBluetoothController {
    private const val TAG = "HioneSystemUI_Bluetooth"

    private var appContext: Context? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    private val _isBluetoothEnabled = MutableStateFlow(false)
    val isBluetoothEnabled: StateFlow<Boolean> = _isBluetoothEnabled.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _pairedDevices = MutableStateFlow<List<HioneBtDevice>>(emptyList())
    val pairedDevices: StateFlow<List<HioneBtDevice>> = _pairedDevices.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<HioneBtDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<HioneBtDevice>> = _discoveredDevices.asStateFlow()

    private val _connectingAddress = MutableStateFlow<String?>(null)
    val connectingAddress: StateFlow<String?> = _connectingAddress.asStateFlow()

    private val discoveredMap = ConcurrentHashMap<String, HioneBtDevice>()

    // Bluetooth profile proxies
    private var a2dpProfile: BluetoothProfile? = null
    private var headsetProfile: BluetoothProfile? = null
    private var hidHostProfile: BluetoothProfile? = null
    private var hearingAidProfile: BluetoothProfile? = null
    private var leAudioProfile: BluetoothProfile? = null

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            when (profile) {
                BluetoothProfile.A2DP -> a2dpProfile = proxy
                BluetoothProfile.HEADSET -> headsetProfile = proxy
                4 -> hidHostProfile = proxy // HID_HOST = 4
                21 -> hearingAidProfile = proxy // HEARING_AID = 21
                22 -> leAudioProfile = proxy // LE_AUDIO = 22
            }
            refreshDevices()
        }

        override fun onServiceDisconnected(profile: Int) {
            when (profile) {
                BluetoothProfile.A2DP -> a2dpProfile = null
                BluetoothProfile.HEADSET -> headsetProfile = null
                4 -> hidHostProfile = null
                21 -> hearingAidProfile = null
                22 -> leAudioProfile = null
            }
            refreshDevices()
        }
    }

    private val leScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val dev = result?.device ?: return
            handleDeviceFound(dev, result.rssi)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { res ->
                res.device?.let { handleDeviceFound(it, res.rssi) }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "LE scan failed with code: $errorCode")
        }
    }

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    val enabled = state == BluetoothAdapter.STATE_ON
                    _isBluetoothEnabled.value = enabled
                    if (enabled) {
                        refreshDevices()
                    } else {
                        _isScanning.value = false
                        _pairedDevices.value = emptyList()
                        _discoveredDevices.value = emptyList()
                        discoveredMap.clear()
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    _isScanning.value = true
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    _isScanning.value = false
                }
                BluetoothDevice.ACTION_FOUND -> {
                    val dev: BluetoothDevice? = try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                    } catch (_: Throwable) { null }
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                    if (dev != null) {
                        handleDeviceFound(dev, rssi)
                    }
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val dev: BluetoothDevice? = try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                    } catch (_: Throwable) { null }
                    val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                    if (bondState == BluetoothDevice.BOND_BONDED && dev != null) {
                        Log.i(TAG, "Device bonded successfully: ${dev.address}, auto-connecting profiles...")
                        connectDevice(dev)
                    }
                    refreshDevices()
                }
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_ACL_DISCONNECTED,
                "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED",
                "android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED",
                "android.bluetooth.hearingaid.profile.action.CONNECTION_STATE_CHANGED" -> {
                    val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                    if (state == BluetoothProfile.STATE_CONNECTED || state == BluetoothProfile.STATE_DISCONNECTED) {
                        _connectingAddress.value = null
                    }
                    refreshDevices()
                }
            }
        }
    }

    fun init(context: Context) {
        if (appContext != null) {
            refreshDevices()
            return
        }
        appContext = context.applicationContext

        ensurePermissions()

        val bm = appContext?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        @Suppress("DEPRECATION")
        bluetoothAdapter = bm?.adapter ?: BluetoothAdapter.getDefaultAdapter()

        _isBluetoothEnabled.value = try { bluetoothAdapter?.isEnabled == true } catch (_: Throwable) { false }

        // Register profile proxies for audio & input endpoints
        bluetoothAdapter?.let { adapter ->
            try { adapter.getProfileProxy(appContext, profileListener, BluetoothProfile.A2DP) } catch (_: Throwable) {}
            try { adapter.getProfileProxy(appContext, profileListener, BluetoothProfile.HEADSET) } catch (_: Throwable) {}
            try { adapter.getProfileProxy(appContext, profileListener, 4) } catch (_: Throwable) {} // HID_HOST
            try { adapter.getProfileProxy(appContext, profileListener, 21) } catch (_: Throwable) {} // HEARING_AID
            try { adapter.getProfileProxy(appContext, profileListener, 22) } catch (_: Throwable) {} // LE_AUDIO
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.hearingaid.profile.action.CONNECTION_STATE_CHANGED")
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.registerReceiver(appContext!!, btReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
            } else {
                appContext?.registerReceiver(btReceiver, filter)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Receiver registration warning: ${t.message}")
        }

        refreshDevices()
    }

    private fun ensurePermissions() {
        scope.launch(Dispatchers.IO) {
            try {
                RootShell.execFast(
                    "pm grant com.hione.settings android.permission.BLUETOOTH_CONNECT 2>/dev/null; " +
                    "pm grant com.hione.settings android.permission.BLUETOOTH_SCAN 2>/dev/null; " +
                    "pm grant com.hione.settings android.permission.BLUETOOTH_ADVERTISE 2>/dev/null; " +
                    "pm grant com.hione.settings android.permission.ACCESS_FINE_LOCATION 2>/dev/null; " +
                    "pm grant com.hione.player android.permission.BLUETOOTH_CONNECT 2>/dev/null; " +
                    "pm grant com.hione.player android.permission.BLUETOOTH_SCAN 2>/dev/null; " +
                    "pm grant com.hione.player android.permission.BLUETOOTH_ADVERTISE 2>/dev/null; " +
                    "pm grant com.hione.player android.permission.ACCESS_FINE_LOCATION 2>/dev/null; " +
                    "pm grant com.hione.systemui android.permission.BLUETOOTH_CONNECT 2>/dev/null; " +
                    "pm grant com.hione.systemui android.permission.BLUETOOTH_SCAN 2>/dev/null; " +
                    "pm grant com.hione.launcher android.permission.BLUETOOTH_CONNECT 2>/dev/null; " +
                    "pm grant com.hione.launcher android.permission.BLUETOOTH_SCAN 2>/dev/null"
                )
            } catch (_: Throwable) {}
        }
    }

    fun toggleBluetooth(enable: Boolean) {
        scope.launch(Dispatchers.IO) {
            try {
                if (enable) {
                    @Suppress("DEPRECATION")
                    bluetoothAdapter?.enable()
                    RootShell.execFast("svc bluetooth enable 2>/dev/null || cmd bluetooth_manager enable 2>/dev/null")
                } else {
                    stopScan()
                    @Suppress("DEPRECATION")
                    bluetoothAdapter?.disable()
                    RootShell.execFast("svc bluetooth disable 2>/dev/null || cmd bluetooth_manager disable 2>/dev/null")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Standard enable/disable failed: ${t.message}")
                try {
                    RootShell.execFast(if (enable) "svc bluetooth enable" else "svc bluetooth disable")
                } catch (_: Throwable) {}
            }
            kotlinx.coroutines.delay(600)
            withContext(Dispatchers.Main) {
                _isBluetoothEnabled.value = bluetoothAdapter?.isEnabled == true
                refreshDevices()
            }
        }
    }

    fun startScan() {
        val adapter = bluetoothAdapter ?: return
        try {
            if (!adapter.isEnabled) return
            ensurePermissions()
            discoveredMap.clear()
            _discoveredDevices.value = emptyList()
            _isScanning.value = true

            // 1. Classic BR/EDR discovery
            if (adapter.isDiscovering) {
                adapter.cancelDiscovery()
            }
            adapter.startDiscovery()

            // 2. BLE discovery for modern wireless audio / peripherals
            try {
                adapter.bluetoothLeScanner?.startScan(leScanCallback)
            } catch (t: Throwable) {
                Log.w(TAG, "BLE scanner start: ${t.message}")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "startScan failed: ${t.message}")
        }
    }

    fun stopScan() {
        val adapter = bluetoothAdapter ?: return
        try {
            if (adapter.isDiscovering) {
                adapter.cancelDiscovery()
            }
            try {
                adapter.bluetoothLeScanner?.stopScan(leScanCallback)
            } catch (_: Throwable) {}
            _isScanning.value = false
        } catch (t: Throwable) {
            Log.e(TAG, "stopScan failed: ${t.message}")
        }
    }

    fun pairDevice(device: BluetoothDevice) {
        scope.launch(Dispatchers.IO) {
            try {
                stopScan()
                _connectingAddress.value = device.address
                val method: Method? = try { device.javaClass.getMethod("createBond") } catch (_: Throwable) { null }
                val res = method?.invoke(device) as? Boolean
                if (res != true) {
                    connectDevice(device)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "pairDevice failed: ${t.message}")
                _connectingAddress.value = null
            }
        }
    }

    fun unpairDevice(device: BluetoothDevice) {
        scope.launch(Dispatchers.IO) {
            try {
                val method: Method? = device.javaClass.getMethod("removeBond")
                method?.invoke(device)
                refreshDevices()
            } catch (t: Throwable) {
                Log.e(TAG, "unpairDevice failed: ${t.message}")
            }
        }
    }

    fun connectDevice(device: BluetoothDevice) {
        scope.launch(Dispatchers.IO) {
            _connectingAddress.value = device.address
            try {
                if (device.bondState != BluetoothDevice.BOND_BONDED) {
                    try {
                        device.javaClass.getMethod("createBond").invoke(device)
                    } catch (_: Throwable) {}
                }

                // 1. Direct SystemApi BluetoothDevice.connect() reflection
                try {
                    val connectMethod = device.javaClass.getMethod("connect")
                    connectMethod.isAccessible = true
                    connectMethod.invoke(device)
                } catch (_: Throwable) {}

                // 2. Profile proxies
                a2dpProfile?.let { a2dp ->
                    try {
                        val method = a2dp.javaClass.getMethod("connect", BluetoothDevice::class.java)
                        method.isAccessible = true
                        method.invoke(a2dp, device)
                        Log.i(TAG, "A2DP connect proxy called for ${device.address}")
                    } catch (t: Throwable) {
                        Log.w(TAG, "A2DP connect failed: ${t.message}")
                    }
                }

                headsetProfile?.let { hfp ->
                    try {
                        val method = hfp.javaClass.getMethod("connect", BluetoothDevice::class.java)
                        method.isAccessible = true
                        method.invoke(hfp, device)
                    } catch (_: Throwable) {}
                }

                hidHostProfile?.let { hid ->
                    try {
                        val method = hid.javaClass.getMethod("connect", BluetoothDevice::class.java)
                        method.isAccessible = true
                        method.invoke(hid, device)
                    } catch (_: Throwable) {}
                }

                hearingAidProfile?.let { ha ->
                    try {
                        val method = ha.javaClass.getMethod("connect", BluetoothDevice::class.java)
                        method.isAccessible = true
                        method.invoke(ha, device)
                    } catch (_: Throwable) {}
                }

                leAudioProfile?.let { le ->
                    try {
                        val method = le.javaClass.getMethod("connect", BluetoothDevice::class.java)
                        method.isAccessible = true
                        method.invoke(le, device)
                    } catch (_: Throwable) {}
                }

                // 3. Command-line fallback via cmd bluetooth_manager
                RootShell.execFast("cmd bluetooth_manager connect ${device.address} 2>/dev/null")

                kotlinx.coroutines.delay(1200)
                refreshDevices()
            } catch (t: Throwable) {
                Log.e(TAG, "connectDevice failed: ${t.message}")
            } finally {
                kotlinx.coroutines.delay(3000)
                if (_connectingAddress.value == device.address) {
                    _connectingAddress.value = null
                }
            }
        }
    }

    fun disconnectDevice(device: BluetoothDevice) {
        scope.launch(Dispatchers.IO) {
            try {
                try {
                    val disconnectMethod = device.javaClass.getMethod("disconnect")
                    disconnectMethod.isAccessible = true
                    disconnectMethod.invoke(device)
                } catch (_: Throwable) {}

                a2dpProfile?.let { a2dp ->
                    try {
                        val method = a2dp.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
                        method.isAccessible = true
                        method.invoke(a2dp, device)
                    } catch (_: Throwable) {}
                }

                headsetProfile?.let { hfp ->
                    try {
                        val method = hfp.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
                        method.isAccessible = true
                        method.invoke(hfp, device)
                    } catch (_: Throwable) {}
                }

                hidHostProfile?.let { hid ->
                    try {
                        val method = hid.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
                        method.isAccessible = true
                        method.invoke(hid, device)
                    } catch (_: Throwable) {}
                }

                RootShell.execFast("cmd bluetooth_manager disconnect ${device.address} 2>/dev/null")
                kotlinx.coroutines.delay(500)
                refreshDevices()
            } catch (t: Throwable) {
                Log.e(TAG, "disconnectDevice failed: ${t.message}")
            }
        }
    }

    fun isDeviceConnected(device: BluetoothDevice): Boolean {
        try {
            val method = device.javaClass.getMethod("isConnected")
            method.isAccessible = true
            val connected = method.invoke(device) as? Boolean
            if (connected == true) return true
        } catch (_: Throwable) {}

        a2dpProfile?.let { a2dp ->
            try {
                if (a2dp.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED) return true
            } catch (_: Throwable) {}
        }

        headsetProfile?.let { hfp ->
            try {
                if (hfp.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED) return true
            } catch (_: Throwable) {}
        }

        hidHostProfile?.let { hid ->
            try {
                if (hid.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED) return true
            } catch (_: Throwable) {}
        }

        hearingAidProfile?.let { ha ->
            try {
                if (ha.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED) return true
            } catch (_: Throwable) {}
        }

        leAudioProfile?.let { le ->
            try {
                if (le.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED) return true
            } catch (_: Throwable) {}
        }

        return false
    }

    fun refreshDevices() {
        val adapter = bluetoothAdapter ?: return
        try {
            if (!adapter.isEnabled) {
                _pairedDevices.value = emptyList()
                return
            }

            val connAddr = _connectingAddress.value
            val bonded = try { adapter.bondedDevices ?: emptySet() } catch (_: SecurityException) {
                ensurePermissions()
                emptySet()
            } catch (_: Throwable) { emptySet() }

            val list = bonded.map { dev ->
                val isConn = isDeviceConnected(dev)
                val isConnecting = connAddr == dev.address && !isConn
                val name = try { dev.name ?: "Bluetooth device (${dev.address.takeLast(5)})" } catch (_: Throwable) { "Bluetooth device (${dev.address.takeLast(5)})" }
                val bondState = try { dev.bondState } catch (_: Throwable) { BluetoothDevice.BOND_BONDED }
                HioneBtDevice(
                    device = dev,
                    name = name,
                    address = dev.address,
                    bondState = bondState,
                    isConnected = isConn,
                    isConnecting = isConnecting,
                    deviceType = resolveDeviceType(dev)
                )
            }.sortedWith(compareByDescending<HioneBtDevice> { it.isConnected }.thenByDescending { it.isConnecting }.thenBy { it.name })

            _pairedDevices.value = list
        } catch (t: Throwable) {
            Log.e(TAG, "refreshDevices failed: ${t.message}")
        }
    }

    private fun handleDeviceFound(dev: BluetoothDevice, rssi: Int) {
        try {
            val bondState = try { dev.bondState } catch (_: Throwable) { BluetoothDevice.BOND_NONE }
            if (bondState == BluetoothDevice.BOND_BONDED) {
                return
            }
            val name = try { dev.name ?: "" } catch (_: Throwable) { "" }
            val displayName = if (name.isNotBlank()) name else "Bluetooth device (${dev.address.takeLast(5)})"
            val item = HioneBtDevice(
                device = dev,
                name = displayName,
                address = dev.address,
                bondState = bondState,
                isConnected = false,
                isConnecting = _connectingAddress.value == dev.address,
                rssi = if (rssi == Short.MIN_VALUE.toInt()) null else rssi,
                deviceType = resolveDeviceType(dev)
            )
            discoveredMap[dev.address] = item
            _discoveredDevices.value = discoveredMap.values.sortedByDescending { it.rssi ?: Int.MIN_VALUE }
        } catch (t: Throwable) {
            Log.e(TAG, "handleDeviceFound error: ${t.message}")
        }
    }

    private fun resolveDeviceType(dev: BluetoothDevice): DeviceType {
        return try {
            val devClass = dev.bluetoothClass ?: return DeviceType.AUDIO_HEADSET
            when (devClass.majorDeviceClass) {
                BluetoothClass.Device.Major.AUDIO_VIDEO -> {
                    when (devClass.deviceClass) {
                        BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER -> DeviceType.AUDIO_SPEAKER
                        BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES,
                        BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET -> DeviceType.AUDIO_HEADSET
                        BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO -> DeviceType.AUDIO_DAC
                        else -> DeviceType.AUDIO_HEADSET
                    }
                }
                BluetoothClass.Device.Major.PERIPHERAL -> DeviceType.INPUT_KEYBOARD_MOUSE
                BluetoothClass.Device.Major.PHONE -> DeviceType.PHONE_WATCH
                BluetoothClass.Device.Major.WEARABLE -> DeviceType.PHONE_WATCH
                else -> DeviceType.AUDIO_HEADSET
            }
        } catch (_: Throwable) {
            DeviceType.AUDIO_HEADSET
        }
    }
}
