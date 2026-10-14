package com.hione.player.bluetooth

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
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
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
    /** Last reported signal strength in dBm, or null when the scan reported none (never a stand-in). */
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
    private const val TAG = "HionePlayer_Bluetooth"

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
                BluetoothProfile.A2DP -> {
                    a2dpProfile = proxy
                    // Sinks already connected when we bound get the codec policy re-asserted now.
                    enforceCodecPolicy(reason = "a2dp proxy bound", delayMs = 500)
                }
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
                    // Every A2DP connect re-asserts the codec policy (max unless the user lowered
                    // it) so the link never sits on a silently-negotiated lower codec/bitrate.
                    if (action == "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED" &&
                        state == BluetoothProfile.STATE_CONNECTED) {
                        enforceCodecPolicy(reason = "a2dp connected", delayMs = 1500)
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

    private val BT_RUNTIME_PERMS = listOf(
        "android.permission.BLUETOOTH_CONNECT",
        "android.permission.BLUETOOTH_SCAN",
        "android.permission.BLUETOOTH_ADVERTISE",
        "android.permission.ACCESS_FINE_LOCATION"
    )
    private val HIONE_PACKAGES = listOf(
        "com.hione.player",
        "com.hione.settings",
        "com.hione.systemui",
        "com.hione.launcher"
    )

    @Volatile private var permsEnsured = false

    /**
     * Grant the Hione suite its Bluetooth runtime permissions WITHOUT a shell.
     *
     * The old implementation shelled `pm grant ...` through su, which does not exist on HioneOS, so
     * a denied BLUETOOTH_CONNECT stayed denied while scanning silently returned nothing. The
     * root-free equivalent is PackageManager.grantRuntimePermission (hidden, guarded by the
     * signature permission GRANT_RUNTIME_PERMISSIONS) - which this platform-signed build holds.
     * Anything the platform refuses is logged, never papered over. Runs once per process.
     */
    private fun ensurePermissions() {
        if (permsEnsured) return
        permsEnsured = true
        val ctx = appContext ?: return
        scope.launch(Dispatchers.IO) {
            val pm = ctx.packageManager
            val grant = runCatching {
                pm.javaClass.getMethod(
                    "grantRuntimePermission",
                    String::class.java, String::class.java, android.os.UserHandle::class.java
                )
            }.getOrNull()
            if (grant == null) {
                Log.w(TAG, "ensurePermissions: PackageManager.grantRuntimePermission unavailable - relying on manifest/user grants")
                return@launch
            }
            val user = android.os.Process.myUserHandle()
            for (pkg in HIONE_PACKAGES) {
                val installed = runCatching { pm.getPackageInfo(pkg, 0); true }.getOrDefault(false)
                if (!installed) continue
                for (perm in BT_RUNTIME_PERMS) {
                    if (pm.checkPermission(perm, pkg) == android.content.pm.PackageManager.PERMISSION_GRANTED) continue
                    val ok = runCatching { grant.invoke(pm, pkg, perm, user) }.isSuccess
                    if (!ok) Log.w(TAG, "ensurePermissions: platform refused $perm for $pkg")
                }
            }
        }
    }

    fun toggleBluetooth(enable: Boolean) {
        scope.launch(Dispatchers.IO) {
            try {
                // BluetoothAdapter.enable()/disable() IS the privileged path here: this build is
                // platform-signed and holds BLUETOOTH_CONNECT + BLUETOOTH_PRIVILEGED. The `svc` /
                // `cmd bluetooth_manager` shell-outs that used to follow needed su and never ran.
                if (enable) {
                    @Suppress("DEPRECATION")
                    bluetoothAdapter?.enable()
                } else {
                    stopScan()
                    @Suppress("DEPRECATION")
                    bluetoothAdapter?.disable()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Bluetooth enable/disable refused by the platform: ${t.message}")
            }
            kotlinx.coroutines.delay(600)
            withContext(Dispatchers.Main) {
                _isBluetoothEnabled.value = bluetoothAdapter?.isEnabled == true
                refreshDevices()
            }
        }
    }

    fun startScan() {
        if (!com.hione.player.HionePowerGovernor.allowBackgroundWork) return   // no BT discovery while screen-off / idle
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

                // (The old `cmd bluetooth_manager connect` shell-out that sat here needed su and
                // never ran; the profile-proxy connect() calls above are the real, privileged path.)

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
                val name = try { dev.name ?: "Audio device (${dev.address.takeLast(5)})" } catch (_: Throwable) { "Bluetooth Device (${dev.address.takeLast(5)})" }
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
                // No RSSI reported stays null. It used to become a fabricated -70 dBm, which the
                // settings list then printed as this device's measured signal strength.
                rssi = rssi.takeIf { it != Short.MIN_VALUE.toInt() && it in -127..0 },
                deviceType = resolveDeviceType(dev)
            )
            discoveredMap[dev.address] = item
            // Devices with no reported RSSI sort last instead of sorting as if they were at -70.
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

    // ------------------------------------------------------------------------------------------
    // BT CODEC POLICY (AUDIO LOCKDOWN, user directive 2026-08-27 / 2026-09-13)
    //
    // "Force BT to the highest quality unless the user sets lower in settings." The policy is a
    // handful of Settings.Global rows; a row that is ABSENT means "maximum". It is enforced (not
    // merely selectable) on every A2DP connect and every A2DP proxy bind, through the root-free
    // BluetoothA2dp.setCodecConfigPreference path (BLUETOOTH_PRIVILEGED, held by this
    // platform-signed build). The stack's own local priority order is already
    // LDAC > aptX HD > aptX > AAC > SBC, so re-asserting LDAC at HIGHEST priority with the
    // 990 kbps quality mode pins the best codec the sink offers; a sink that only speaks SBC
    // simply keeps SBC (the stack ignores a preference the peer cannot select).
    // ------------------------------------------------------------------------------------------
    const val KEY_BT_LOCKED = "hione_bt_quality_locked"   // 1 (default): downgrades need confirmed=true
    const val KEY_BT_LDAC = "hione_bt_ldac_quality"       // 1000 990k (default) / 1001 660k / 1002 330k / 1003 ABR
    const val KEY_BT_APTX = "hione_bt_aptx_enabled"       // 1 (default) / 0
    const val KEY_BT_AAC = "hione_bt_aac_enabled"         // 1 (default) / 0

    private const val CODEC_TYPE_SBC = 0
    private const val CODEC_TYPE_AAC = 1
    private const val CODEC_TYPE_APTX = 2
    private const val CODEC_TYPE_APTX_HD = 3
    private const val CODEC_TYPE_LDAC = 4                // BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC
    private const val CODEC_PRIORITY_DISABLED = -1
    private const val CODEC_PRIORITY_DEFAULT = 0
    private const val CODEC_PRIORITY_HIGHEST = 1000000
    const val LDAC_990 = 1000L
    const val LDAC_660 = 1001L
    const val LDAC_330 = 1002L
    const val LDAC_ABR = 1003L

    data class CodecPolicy(val ldacSpecific1: Long, val aptx: Boolean, val aac: Boolean) {
        val isMax: Boolean get() = ldacSpecific1 == LDAC_990 && aptx && aac
        val ldacLabel: String get() = ldacLabelFor(ldacSpecific1)
    }

    fun ldacLabelFor(specific1: Long): String = when (specific1) {
        LDAC_990 -> "Sound Quality (990 kbps)"
        LDAC_660 -> "Balanced (660 kbps)"
        LDAC_330 -> "Connection (330 kbps)"
        else -> "Adaptive Bitrate"
    }

    fun ldacSpecific1For(label: String): Long = when {
        label.contains("990") -> LDAC_990
        label.contains("660") -> LDAC_660
        label.contains("330") -> LDAC_330
        label.contains("Adaptive", ignoreCase = true) -> LDAC_ABR
        else -> LDAC_990
    }

    /** The policy the enforcer applies. Missing rows = maximum, so a fresh image is at max. */
    fun readCodecPolicy(): CodecPolicy {
        val cr = appContext?.contentResolver ?: return CodecPolicy(LDAC_990, aptx = true, aac = true)
        val ldac = runCatching { Settings.Global.getLong(cr, KEY_BT_LDAC) }.getOrDefault(LDAC_990)
            .takeIf { it in LDAC_990..LDAC_ABR } ?: LDAC_990
        val aptx = runCatching { Settings.Global.getInt(cr, KEY_BT_APTX) }.getOrDefault(1) == 1
        val aac = runCatching { Settings.Global.getInt(cr, KEY_BT_AAC) }.getOrDefault(1) == 1
        return CodecPolicy(ldac, aptx, aac)
    }

    fun isQualityLocked(): Boolean {
        val cr = appContext?.contentResolver ?: return true
        return runCatching { Settings.Global.getInt(cr, KEY_BT_LOCKED, 1) == 1 }.getOrDefault(true)
    }

    /**
     * Change the BT codec policy. While the lock is on (default), anything BELOW the maximum
     * (LDAC 990 with aptX/aptX HD and AAC enabled) is REFUSED unless [confirmed] is true — the
     * settings UI shows an explicit confirmation dialog and calls back with confirmed=true.
     * Raising quality always applies silently. Returns true if applied, false if blocked.
     */
    fun applyCodecConfig(ldacQuality: String, aptx: Boolean, aac: Boolean, confirmed: Boolean = false): Boolean {
        val wanted = CodecPolicy(ldacSpecific1For(ldacQuality), aptx, aac)
        if (isQualityLocked() && !wanted.isMax && !confirmed) {
            Log.w(TAG, "codec downgrade to $wanted BLOCKED by audio lockdown (needs explicit GUI confirm)")
            return false
        }
        val cr = appContext?.contentResolver ?: return false
        val before = readCodecPolicy()
        runCatching {
            Settings.Global.putLong(cr, KEY_BT_LDAC, wanted.ldacSpecific1)
            Settings.Global.putInt(cr, KEY_BT_APTX, if (wanted.aptx) 1 else 0)
            Settings.Global.putInt(cr, KEY_BT_AAC, if (wanted.aac) 1 else 0)
        }.onFailure { Log.w(TAG, "applyCodecConfig: Settings.Global write refused: $it") }
        // A codec the user just re-enabled has to be pushed back to DEFAULT priority once; the
        // connect-time enforcer only re-asserts disables (the stack's default is "enabled").
        val reenable = (wanted.aptx && !before.aptx) || (wanted.aac && !before.aac)
        enforceCodecPolicy(reason = "user change", pushEnables = reenable)
        return true
    }

    /**
     * Re-assert the codec policy on every connected A2DP sink. Called on A2DP proxy bind, on
     * every A2DP connect, and after a policy change. Idempotent: pushing a preference the link
     * already satisfies causes no reconfiguration, so this never interrupts audio needlessly.
     */
    fun enforceCodecPolicy(reason: String = "enforce", pushEnables: Boolean = false, delayMs: Long = 0L) {
        scope.launch(Dispatchers.IO) {
            if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
            val a2dp = a2dpProfile ?: run {
                Log.i(TAG, "enforceCodecPolicy($reason): no A2DP proxy bound yet - will apply on bind")
                return@launch
            }
            val devices = runCatching { a2dp.connectedDevices }.getOrNull().orEmpty()
            if (devices.isEmpty()) {
                Log.i(TAG, "enforceCodecPolicy($reason): no connected A2DP sink - will apply on connect")
                return@launch
            }
            val policy = readCodecPolicy()
            for (d in devices) {
                val status = currentCodec(a2dp, d)
                val ldacSelectable = status?.selectable?.contains(CODEC_TYPE_LDAC) ?: true
                val needLdac = when {
                    !ldacSelectable -> false                       // sink cannot do LDAC: nothing to force
                    status == null -> true                         // unknown state: assert the policy
                    status.type != CODEC_TYPE_LDAC -> true         // LDAC available but not in use
                    else -> status.specific1 != policy.ldacSpecific1 // wrong bitrate mode
                }
                var pushed = 0
                if (needLdac && pushPreference(a2dp, d, CODEC_TYPE_LDAC, CODEC_PRIORITY_HIGHEST, policy.ldacSpecific1)) pushed++
                // Disables the user chose (each is a confirmed downgrade) are re-asserted every
                // connect; enables are pushed only right after the user flips a codec back on.
                for (t in listOf(CODEC_TYPE_APTX_HD, CODEC_TYPE_APTX)) {
                    if (!policy.aptx) { if (pushPreference(a2dp, d, t, CODEC_PRIORITY_DISABLED, 0L)) pushed++ }
                    else if (pushEnables) { if (pushPreference(a2dp, d, t, CODEC_PRIORITY_DEFAULT, 0L)) pushed++ }
                }
                if (!policy.aac) { if (pushPreference(a2dp, d, CODEC_TYPE_AAC, CODEC_PRIORITY_DISABLED, 0L)) pushed++ }
                else if (pushEnables) { if (pushPreference(a2dp, d, CODEC_TYPE_AAC, CODEC_PRIORITY_DEFAULT, 0L)) pushed++ }
                Log.i(TAG, "enforceCodecPolicy($reason) ${d.address}: current=${status?.describe() ?: "unknown"} " +
                    "policy=${policy.ldacLabel} aptx=${policy.aptx} aac=${policy.aac} pushed=$pushed")
            }
        }
    }

    private class CodecSnapshot(val type: Int, val specific1: Long, val selectable: Set<Int>) {
        fun describe(): String = "${codecName(type)}(specific1=$specific1) selectable=${selectable.map { codecName(it) }}"
    }

    private fun codecName(type: Int): String = when (type) {
        CODEC_TYPE_SBC -> "SBC"; CODEC_TYPE_AAC -> "AAC"; CODEC_TYPE_APTX -> "aptX"
        CODEC_TYPE_APTX_HD -> "aptX HD"; CODEC_TYPE_LDAC -> "LDAC"; 5 -> "LC3"; 6 -> "Opus"
        else -> "codec#$type"
    }

    /** What the stack is REALLY using for [d] (BluetoothA2dp.getCodecStatus, SystemApi). Null = unknown. */
    private fun currentCodec(a2dp: BluetoothProfile, d: BluetoothDevice): CodecSnapshot? = runCatching {
        val status = a2dp.javaClass.getMethod("getCodecStatus", BluetoothDevice::class.java).invoke(a2dp, d) ?: return null
        val cfg = status.javaClass.getMethod("getCodecConfig").invoke(status) ?: return null
        val type = cfg.javaClass.getMethod("getCodecType").invoke(cfg) as Int
        val spec1 = (cfg.javaClass.getMethod("getCodecSpecific1").invoke(cfg) as? Long) ?: 0L
        val selectable = runCatching {
            @Suppress("UNCHECKED_CAST")
            val list = status.javaClass.getMethod("getCodecsSelectableCapabilities").invoke(status) as? List<Any>
            list.orEmpty().mapNotNull { c -> runCatching { c.javaClass.getMethod("getCodecType").invoke(c) as Int }.getOrNull() }.toSet()
        }.getOrDefault(emptySet())
        CodecSnapshot(type, spec1, selectable)
    }.getOrNull()

    /**
     * Build a BluetoothCodecConfig for [codecType] at [priority] (+ LDAC quality in [specific1]).
     * Fully reflective so this compiles against any SDK level; null when the platform exposes
     * neither the public Builder nor the legacy constructor. 0 for sample rate / bits / channel
     * mode means "no preference": the stack keeps the highest mutually supported values, so a
     * preference never downgrades the link.
     */
    private fun buildCodecConfig(codecType: Int, priority: Int, specific1: Long): Any? = runCatching {
        val builderCls = runCatching { Class.forName("android.bluetooth.BluetoothCodecConfig\$Builder") }.getOrNull()
        if (builderCls != null) {
            val b = builderCls.getDeclaredConstructor().newInstance()
            builderCls.getMethod("setCodecType", Int::class.javaPrimitiveType).invoke(b, codecType)
            builderCls.getMethod("setCodecPriority", Int::class.javaPrimitiveType).invoke(b, priority)
            builderCls.getMethod("setCodecSpecific1", Long::class.javaPrimitiveType).invoke(b, specific1)
            return@runCatching builderCls.getMethod("build").invoke(b)
        }
        val cfgCls = Class.forName("android.bluetooth.BluetoothCodecConfig")
        val ctor = cfgCls.getConstructor(
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Long::class.javaPrimitiveType, Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType, Long::class.javaPrimitiveType
        )
        ctor.newInstance(codecType, priority, 0, 0, 0, specific1, 0L, 0L, 0L)
    }.getOrNull()

    /** setCodecConfigPreference on one sink. True only if the stack accepted the call. */
    private fun pushPreference(a2dp: BluetoothProfile, d: BluetoothDevice, codecType: Int, priority: Int, specific1: Long): Boolean {
        val cfg = buildCodecConfig(codecType, priority, specific1) ?: run {
            Log.w(TAG, "pushPreference: BluetoothCodecConfig not constructible on this platform")
            return false
        }
        return runCatching {
            val m = a2dp.javaClass.getMethod("setCodecConfigPreference", BluetoothDevice::class.java, cfg.javaClass)
            m.isAccessible = true
            m.invoke(a2dp, d, cfg)
        }.onFailure { Log.w(TAG, "setCodecConfigPreference(${codecName(codecType)} prio=$priority) refused for ${d.address}: $it") }.isSuccess
    }

    /** Re-assert the policy (max unless the user lowered it). Kept for existing callers. */
    fun enforceMaxCodec() = enforceCodecPolicy(reason = "enforceMaxCodec")
}
