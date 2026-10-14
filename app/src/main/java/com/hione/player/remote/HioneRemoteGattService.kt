package com.hione.player.remote

import com.hione.player.Mascot
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.hione.player.FastLibraryStore
import com.hione.player.LikeStore
import com.hione.player.MainActivity
import com.hione.player.PlayerHolder
import com.hione.player.R
import com.hione.player.Track
import com.hione.player.TrackTech
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Foreground service (type connectedDevice) that turns the M500 into a BLE GATT peripheral
 * exposing the "Hione Remote" service — see [HioneRemoteProtocol] for the wire contract and
 * tools/remote-pwa for the phone side (Web Bluetooth, no app install).
 *
 * Security model:
 *  - Advertised ONLY while the Settings toggle is on (default off). Stopping the service also
 *    stops advertising and drops every connection.
 *  - Every control/read characteristic refuses a connection until it has either presented the
 *    current 6-digit code (shown in Settings, single-use, rate-limited) or a token from a
 *    previous pairing. Authorization is per-connection and evaporates on disconnect.
 *  - Pairing tokens are stored hashed (HioneRemotePreferences); "Forget" in Settings revokes
 *    one and kicks it if connected.
 *
 * Threading: GATT callbacks arrive on binder threads; everything that touches the ExoPlayer is
 * hopped to the main looper (Media3 requires it). Notifications are serialised through one
 * queue because the Android stack rejects a second notify before onNotificationSent fires.
 */
class HioneRemoteGattService : Service() {

    companion object {
        private const val TAG = "HioneRemoteGatt"
        private const val CHANNEL_ID = "hione_remote_ble"
        private const val NOTIF_ID = 0x4D52 // "MR"

        const val ACTION_START = "com.hione.player.remote.START"
        const val ACTION_STOP = "com.hione.player.remote.STOP"
        const val ACTION_NEW_CODE = "com.hione.player.remote.NEW_CODE"
        const val ACTION_KICK_PAIRED = "com.hione.player.remote.KICK_PAIRED"
        const val ACTION_DISCONNECT = "com.hione.player.remote.DISCONNECT"
        const val EXTRA_PAIRED_ID = "paired_id"
        const val EXTRA_ADDRESS = "address"
        /** With ACTION_STOP: also clear the Settings toggle (notification "Turn off" / card). */
        const val EXTRA_DISABLE = "disable"

        private const val POSITION_TICK_MS = 1000L
        private const val STATE_DEBOUNCE_MS = 120L
        private const val PAIR_MAX_FAILURES = 5
        private const val PAIR_LOCKOUT_MS = 60_000L

        /** Runtime permissions the BLE peripheral needs on this API level. */
        fun requiredPermissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= 31) {
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
            } else {
                emptyArray()
            }

        fun hasPermissions(ctx: Context): Boolean = requiredPermissions().all {
            ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
        }

        /** Called from MainActivity.onCreate: (re)start the peripheral if the user left it on. */
        fun startIfEnabled(ctx: Context) {
            if (!HioneRemotePreferences.isEnabled(ctx)) return
            start(ctx)
        }

        fun start(ctx: Context) {
            val i = Intent(ctx, HioneRemoteGattService::class.java).setAction(ACTION_START)
            try {
                ContextCompat.startForegroundService(ctx, i)
            } catch (t: Throwable) {
                Log.e(TAG, "startForegroundService failed", t)
                HioneRemoteStatus.update { it.copy(lastError = "Could not start: ${t.message}") }
            }
        }

        /** Tears the peripheral down without touching the Settings toggle (callers own that). */
        fun stop(ctx: Context) {
            if (!HioneRemoteStatus.state.value.running) return
            try {
                ctx.startService(Intent(ctx, HioneRemoteGattService::class.java).setAction(ACTION_STOP))
            } catch (_: Throwable) {
                // Service not running (or process restrictions) — nothing to stop.
            }
        }

        fun requestNewCode(ctx: Context) {
            if (!HioneRemoteStatus.state.value.running) return
            try { ctx.startService(Intent(ctx, HioneRemoteGattService::class.java).setAction(ACTION_NEW_CODE)) } catch (_: Throwable) {}
        }

        /** After HioneRemotePreferences.forget(id): drop any live connection that used that token. */
        fun kickPaired(ctx: Context, pairedId: String) {
            if (!HioneRemoteStatus.state.value.running) return
            try {
                ctx.startService(
                    Intent(ctx, HioneRemoteGattService::class.java).setAction(ACTION_KICK_PAIRED).putExtra(EXTRA_PAIRED_ID, pairedId)
                )
            } catch (_: Throwable) {}
        }

        fun disconnect(ctx: Context, address: String) {
            if (!HioneRemoteStatus.state.value.running) return
            try {
                ctx.startService(
                    Intent(ctx, HioneRemoteGattService::class.java).setAction(ACTION_DISCONNECT).putExtra(EXTRA_ADDRESS, address)
                )
            } catch (_: Throwable) {}
        }
    }

    // ------------------------------------------------------------------ state

    private val main = Handler(Looper.getMainLooper())
    private val qualityExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "hione-remote-quality").apply { isDaemon = true } }

    private var btManager: BluetoothManager? = null
    private var adapter: BluetoothAdapter? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null

    private var charNowPlaying: BluetoothGattCharacteristic? = null
    private var charCommand: BluetoothGattCharacteristic? = null
    private var charVolume: BluetoothGattCharacteristic? = null
    private var charPair: BluetoothGattCharacteristic? = null
    private var charStatus: BluetoothGattCharacteristic? = null

    private data class Conn(
        val device: BluetoothDevice,
        var mtu: Int = 23,
        var authorized: Boolean = false,
        var pairedId: String? = null,
        var label: String? = null,
        val subscriptions: MutableSet<UUID> = HashSet()
    )

    private val lock = Any()
    private val conns = HashMap<String, Conn>()

    private var pairingCode: String = ""
    private var pairFailures = 0
    private var pairLockedUntil = 0L

    private var started = false
    private var advertising = false
    private var renamedAdapter = false

    private var attachedPlayer: Player? = null
    private var lastTrackIdForQuality: Long? = null
    @Volatile private var qualityCache: Pair<Long, String>? = null
    @Volatile private var lastVolumePct = -1

    // Serialised notification queue (address, characteristic, payload)
    private data class Pending(val address: String, val ch: BluetoothGattCharacteristic, val bytes: ByteArray)
    private val notifyQueue = ArrayDeque<Pending>()
    private var notifyInFlight = false

    // ------------------------------------------------------------------ lifecycle

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // Must promote within the ANR budget of startForegroundService(); do it before any BT work.
        try {
            val notif = buildNotification("Starting Bluetooth remote…")
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground failed", t)
            HioneRemoteStatus.update { it.copy(lastError = "Foreground start refused: ${t.message}") }
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                if (intent?.getBooleanExtra(EXTRA_DISABLE, false) == true) HioneRemotePreferences.setEnabled(this, false)
                teardown()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_NEW_CODE -> { regenerateCode("user"); return START_STICKY }
            ACTION_KICK_PAIRED -> { intent?.getStringExtra(EXTRA_PAIRED_ID)?.let { kick(it) }; return START_STICKY }
            ACTION_DISCONNECT -> { intent?.getStringExtra(EXTRA_ADDRESS)?.let { disconnectAddress(it) }; return START_STICKY }
            else -> {
                if (!HioneRemotePreferences.isEnabled(this)) {
                    // Restarted by the system after the user turned it off — never advertise unasked.
                    teardown(); stopSelf(); return START_NOT_STICKY
                }
                if (!started) startPeripheral()
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        teardown()
        qualityExecutor.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ bring-up / teardown

    private fun startPeripheral() {
        if (!hasPermissions(this)) {
            fail("Bluetooth permission not granted. Tap the toggle again to grant it.")
            stopSelf(); return
        }
        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val ad = bm?.adapter
        if (bm == null || ad == null) { fail("No Bluetooth adapter."); stopSelf(); return }
        btManager = bm; adapter = ad

        registerReceivers()
        pairingCode = HioneRemotePreferences.randomPairingCode()
        started = true
        HioneRemoteStatus.update {
            it.copy(running = true, pairingCode = pairingCode, lastError = null, advertising = false, connected = emptyList())
        }

        if (!ad.isEnabled) {
            HioneRemoteStatus.update { it.copy(lastError = "Bluetooth is off. Turn it on to advertise.") }
            updateNotification()
            return // ACTION_STATE_CHANGED receiver brings us up when BT turns on
        }
        openServerAndAdvertise()
    }

    @Suppress("MissingPermission")
    private fun openServerAndAdvertise() {
        val bm = btManager ?: return
        val ad = adapter ?: return
        if (gattServer == null) {
            val server = try { bm.openGattServer(this, gattCallback) } catch (t: Throwable) { null }
            if (server == null) { fail("Could not open GATT server."); return }
            gattServer = server
            server.addService(buildService())
        }
        applyBrandedName()
        startAdvertising()
        main.post { attachPlayerListener(); scheduleTick() }
        updateNotification()
    }

    @Suppress("MissingPermission")
    private fun teardown() {
        if (!started && gattServer == null) return
        started = false
        main.removeCallbacksAndMessages(null)
        try { attachedPlayer?.removeListener(playerListener) } catch (_: Throwable) {}
        attachedPlayer = null
        stopAdvertising()
        synchronized(lock) {
            conns.values.forEach { c -> try { gattServer?.cancelConnection(c.device) } catch (_: Throwable) {} }
            conns.clear()
            notifyQueue.clear(); notifyInFlight = false
        }
        try { gattServer?.clearServices() } catch (_: Throwable) {}
        try { gattServer?.close() } catch (_: Throwable) {}
        gattServer = null
        restoreAdapterName()
        unregisterReceivers()
        HioneRemoteStatus.reset()
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) {}
    }

    private fun fail(msg: String) {
        Log.w(TAG, msg)
        HioneRemoteStatus.update { it.copy(lastError = msg) }
    }

    // ------------------------------------------------------------------ GATT service definition

    private fun buildService(): BluetoothGattService {
        val svc = BluetoothGattService(HioneRemoteProtocol.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        charNowPlaying = BluetoothGattCharacteristic(
            HioneRemoteProtocol.CHAR_NOW_PLAYING,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).also { it.addDescriptor(cccd()) }

        charCommand = BluetoothGattCharacteristic(
            HioneRemoteProtocol.CHAR_COMMAND,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        charVolume = BluetoothGattCharacteristic(
            HioneRemoteProtocol.CHAR_VOLUME,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).also { it.addDescriptor(cccd()) }

        charPair = BluetoothGattCharacteristic(
            HioneRemoteProtocol.CHAR_PAIR,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        ).also { it.addDescriptor(cccd()) }

        charStatus = BluetoothGattCharacteristic(
            HioneRemoteProtocol.CHAR_STATUS,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        svc.addCharacteristic(charNowPlaying)
        svc.addCharacteristic(charCommand)
        svc.addCharacteristic(charVolume)
        svc.addCharacteristic(charPair)
        svc.addCharacteristic(charStatus)
        return svc
    }

    private fun cccd() = BluetoothGattDescriptor(
        HioneRemoteProtocol.CCCD_UUID,
        BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
    )

    // ------------------------------------------------------------------ advertising

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            advertising = true
            HioneRemoteStatus.update { it.copy(advertising = true, lastError = null, advertisedName = currentAdapterName()) }
            updateNotification()
        }

        override fun onStartFailure(errorCode: Int) {
            advertising = false
            val why = when (errorCode) {
                AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "advertise data too large"
                AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "too many advertisers"
                AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "already advertising"
                AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "internal Bluetooth error"
                AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "BLE advertising unsupported"
                else -> "error $errorCode"
            }
            if (errorCode == AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED) {
                advertising = true
                HioneRemoteStatus.update { it.copy(advertising = true) }
            } else {
                fail("Advertising failed: $why")
                HioneRemoteStatus.update { it.copy(advertising = false) }
            }
            updateNotification()
        }
    }

    @Suppress("MissingPermission")
    private fun startAdvertising() {
        val ad = adapter ?: return
        if (advertising) return
        val adv = ad.bluetoothLeAdvertiser
        if (adv == null) { fail("This Bluetooth radio can't advertise (BLE peripheral unsupported)."); return }
        advertiser = adv
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        // 128-bit UUID (18 bytes) + flags (3) leaves no room for a name in the 31-byte ADV PDU,
        // so the name rides in the scan response — Web Bluetooth's chooser reads both.
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(HioneRemoteProtocol.SERVICE_UUID))
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()
        try {
            adv.startAdvertising(settings, data, scanResponse, advertiseCallback)
        } catch (t: Throwable) {
            fail("Advertising failed: ${t.message}")
        }
    }

    @Suppress("MissingPermission")
    private fun stopAdvertising() {
        try { advertiser?.stopAdvertising(advertiseCallback) } catch (_: Throwable) {}
        advertising = false
        HioneRemoteStatus.update { it.copy(advertising = false) }
    }

    @Suppress("MissingPermission")
    private fun currentAdapterName(): String = try { adapter?.name ?: "" } catch (_: Throwable) { "" }

    /** Rename the adapter to "Hione M500" for the session (opt-out in Settings); always restored. */
    @Suppress("MissingPermission")
    private fun applyBrandedName() {
        val ad = adapter ?: return
        if (!HioneRemotePreferences.advertiseBrandedName(this)) return
        try {
            val cur = ad.name ?: return
            if (cur == HioneRemoteProtocol.LOCAL_NAME) return
            if (HioneRemotePreferences.getOriginalBtName(this) == null) HioneRemotePreferences.setOriginalBtName(this, cur)
            if (ad.setName(HioneRemoteProtocol.LOCAL_NAME)) renamedAdapter = true
        } catch (t: Throwable) {
            Log.w(TAG, "setName failed: ${t.message}")
        }
    }

    @Suppress("MissingPermission")
    private fun restoreAdapterName() {
        val original = HioneRemotePreferences.getOriginalBtName(this) ?: return
        try {
            val ad = adapter
            if (ad != null && ad.isEnabled && ad.name == HioneRemoteProtocol.LOCAL_NAME) ad.setName(original)
        } catch (_: Throwable) {}
        HioneRemotePreferences.setOriginalBtName(this, null)
        renamedAdapter = false
    }

    // ------------------------------------------------------------------ GATT server callbacks

    private val gattCallback = object : BluetoothGattServerCallback() {

        @Suppress("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val addr = device.address ?: return
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                synchronized(lock) { if (!conns.containsKey(addr)) conns[addr] = Conn(device) }
                Log.i(TAG, "Central connected: $addr")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                synchronized(lock) {
                    conns.remove(addr)
                    notifyQueue.removeAll { it.address == addr }
                }
                Log.i(TAG, "Central disconnected: $addr (status $status)")
                // Some stacks pause advertising once a central connects; make sure we're visible again.
                if (started && adapter?.isEnabled == true && !advertising) main.post { startAdvertising() }
            }
            publishConnections()
            updateNotification()
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            synchronized(lock) { conns[device.address]?.mtu = mtu }
            publishConnections()
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic
        ) {
            val conn = synchronized(lock) { conns[device.address] }
            val authorized = conn?.authorized == true
            val bytes: ByteArray = when (characteristic.uuid) {
                HioneRemoteProtocol.CHAR_NOW_PLAYING ->
                    if (authorized) nowPlayingJsonOnMain() else "{\"${HioneRemoteProtocol.NowPlayingKeys.AUTH}\":false}".toByteArray()
                HioneRemoteProtocol.CHAR_VOLUME ->
                    if (authorized) volumeJson() else "{\"${HioneRemoteProtocol.NowPlayingKeys.AUTH}\":false}".toByteArray()
                HioneRemoteProtocol.CHAR_PAIR -> (if (authorized) "authorized" else "unauthorized").toByteArray()
                HioneRemoteProtocol.CHAR_STATUS -> statusJson(authorized)
                else -> { respond(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, 0, null); return }
            }
            if (offset > bytes.size) { respond(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null); return }
            respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, bytes.copyOfRange(offset, bytes.size))
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            val text = value?.toString(Charsets.UTF_8)?.trim() ?: ""
            val conn = synchronized(lock) { conns[device.address] }
            var status = BluetoothGatt.GATT_SUCCESS
            when (characteristic.uuid) {
                HioneRemoteProtocol.CHAR_PAIR -> handlePairWrite(device, conn, text)
                HioneRemoteProtocol.CHAR_COMMAND -> {
                    if (conn?.authorized == true) {
                        handleCommand(device, text)
                    } else {
                        status = BluetoothGatt.GATT_WRITE_NOT_PERMITTED
                        charPair?.let { enqueueNotify(device.address, it, "err:unauthorized".toByteArray()) }
                    }
                }
                else -> status = BluetoothGatt.GATT_WRITE_NOT_PERMITTED
            }
            if (responseNeeded) respond(device, requestId, status, offset, value)
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
            val subscribed = synchronized(lock) { conns[device.address]?.subscriptions?.contains(descriptor.characteristic.uuid) == true }
            val v = if (subscribed) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            respond(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, v)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            if (descriptor.uuid == HioneRemoteProtocol.CCCD_UUID) {
                val enable = value != null && value.isNotEmpty() && (value[0].toInt() and 0x01) != 0
                val uuid = descriptor.characteristic.uuid
                synchronized(lock) {
                    conns[device.address]?.let { c -> if (enable) c.subscriptions.add(uuid) else c.subscriptions.remove(uuid) }
                }
                if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                if (enable) {
                    // Send the current state straight away so the PWA doesn't need a separate read.
                    val authorized = synchronized(lock) { conns[device.address]?.authorized == true }
                    if (authorized) main.post { pushTo(device.address, uuid) }
                }
            } else if (responseNeeded) {
                respond(device, requestId, BluetoothGatt.GATT_WRITE_NOT_PERMITTED, offset, value)
            }
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            // Prepared (long) writes aren't used by the protocol; acknowledge so the client isn't stuck.
            respond(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            synchronized(lock) { notifyInFlight = false }
            drainNotifyQueue()
        }
    }

    @Suppress("MissingPermission")
    private fun respond(device: BluetoothDevice, requestId: Int, status: Int, offset: Int, value: ByteArray?) {
        try { gattServer?.sendResponse(device, requestId, status, offset, value) } catch (t: Throwable) {
            Log.w(TAG, "sendResponse failed: ${t.message}")
        }
    }

    // ------------------------------------------------------------------ pairing / auth

    private fun handlePairWrite(device: BluetoothDevice, conn: Conn?, text: String) {
        val pairChar = charPair ?: return
        val addr = device.address
        fun reply(msg: String) = enqueueNotify(addr, pairChar, msg.toByteArray())

        if (conn == null) { reply("err:no-connection"); return }
        val parts = text.split(':', limit = 3)
        when (parts.getOrNull(0)?.lowercase()) {
            "pair" -> {
                val code = parts.getOrNull(1)?.trim() ?: ""
                val label = parts.getOrNull(2)?.trim().orEmpty().ifBlank { "Phone" }
                val now = System.currentTimeMillis()
                if (now < pairLockedUntil) { reply("err:locked"); return }
                if (code.length == 6 && HioneRemotePreferences.constantTimeEquals(code, pairingCode)) {
                    val (phone, token) = HioneRemotePreferences.addPaired(this, label)
                    synchronized(lock) { conn.authorized = true; conn.pairedId = phone.id; conn.label = phone.label }
                    pairFailures = 0
                    reply("ok:$token")
                    Log.i(TAG, "Paired new phone '${phone.label}' ($addr)")
                    regenerateCode("paired") // single-use
                    HioneRemoteStatus.update { it.copy(pairedGeneration = it.pairedGeneration + 1) }
                    publishConnections(); updateNotification()
                    main.post { pushAllTo(addr) }
                } else {
                    pairFailures++
                    Log.w(TAG, "Bad pairing code from $addr (${pairFailures}/$PAIR_MAX_FAILURES)")
                    if (pairFailures >= PAIR_MAX_FAILURES) {
                        pairFailures = 0
                        pairLockedUntil = now + PAIR_LOCKOUT_MS
                        HioneRemoteStatus.update { it.copy(pairLockedUntilMs = pairLockedUntil) }
                        regenerateCode("lockout")
                        reply("err:locked")
                    } else {
                        reply("err:bad-code")
                    }
                }
            }
            "auth" -> {
                val token = parts.getOrNull(1)?.trim() ?: ""
                val phone = if (token.length == 32) HioneRemotePreferences.findByToken(this, token) else null
                if (phone != null) {
                    synchronized(lock) { conn.authorized = true; conn.pairedId = phone.id; conn.label = phone.label }
                    HioneRemotePreferences.touch(this, phone.id)
                    reply("ok:auth")
                    Log.i(TAG, "Phone '${phone.label}' authenticated ($addr)")
                    HioneRemoteStatus.update { it.copy(pairedGeneration = it.pairedGeneration + 1) }
                    publishConnections(); updateNotification()
                    main.post { pushAllTo(addr) }
                } else {
                    reply("err:unknown-token")
                }
            }
            "unpair" -> {
                val id = synchronized(lock) { conn.pairedId }
                if (id != null) HioneRemotePreferences.forget(this, id)
                synchronized(lock) { conn.authorized = false; conn.pairedId = null }
                HioneRemoteStatus.update { it.copy(pairedGeneration = it.pairedGeneration + 1) }
                reply("ok:unpaired")
                publishConnections(); updateNotification()
            }
            else -> reply("err:bad-request")
        }
    }

    private fun regenerateCode(reason: String) {
        pairingCode = HioneRemotePreferences.randomPairingCode()
        Log.d(TAG, "New pairing code ($reason)")
        HioneRemoteStatus.update { it.copy(pairingCode = pairingCode) }
        updateNotification()
    }

    @Suppress("MissingPermission")
    private fun kick(pairedId: String) {
        val victims = synchronized(lock) { conns.values.filter { it.pairedId == pairedId } }
        victims.forEach { c ->
            synchronized(lock) { c.authorized = false; c.pairedId = null }
            try { gattServer?.cancelConnection(c.device) } catch (_: Throwable) {}
        }
        HioneRemoteStatus.update { it.copy(pairedGeneration = it.pairedGeneration + 1) }
        publishConnections()
    }

    @Suppress("MissingPermission")
    private fun disconnectAddress(address: String) {
        val c = synchronized(lock) { conns[address] } ?: return
        try { gattServer?.cancelConnection(c.device) } catch (_: Throwable) {}
    }

    // ------------------------------------------------------------------ commands (main thread)

    private fun handleCommand(device: BluetoothDevice, text: String) {
        val verb = text.substringBefore(':').lowercase()
        val arg = text.substringAfter(':', "").trim()
        val addr = device.address
        main.post {
            val p = PlayerHolder.player
            when (verb) {
                HioneRemoteProtocol.Commands.PLAY -> p?.play()
                HioneRemoteProtocol.Commands.PAUSE -> p?.pause()
                HioneRemoteProtocol.Commands.TOGGLE -> p?.let { if (it.isPlaying) it.pause() else it.play() }
                HioneRemoteProtocol.Commands.NEXT -> p?.seekToNextMediaItem()
                HioneRemoteProtocol.Commands.PREV -> p?.seekToPreviousMediaItem()
                HioneRemoteProtocol.Commands.SEEK -> arg.toLongOrNull()?.let { ms -> if (ms >= 0) p?.seekTo(ms) }
                HioneRemoteProtocol.Commands.LIKE -> {
                    val id = p?.currentMediaItem?.mediaId?.toLongOrNull()
                    if (id != null && id > 0) {
                        LikeStore.init(this)
                        val track = findTrack(id)
                        if (track != null) LikeStore.toggle(this, track) else LikeStore.toggle(this, id)
                    }
                }
                HioneRemoteProtocol.Commands.VOL -> arg.toIntOrNull()?.let { pct ->
                    if (pct in 0..100) com.hione.player.volume.HioneVolumeManager.setVolume(this, pct)
                }
                HioneRemoteProtocol.Commands.VOL_UP -> com.hione.player.volume.HioneVolumeManager.triggerHud(this, +1)
                HioneRemoteProtocol.Commands.VOL_DOWN -> com.hione.player.volume.HioneVolumeManager.triggerHud(this, -1)
                HioneRemoteProtocol.Commands.MUTE -> com.hione.player.volume.HioneVolumeManager.toggleMute(this)
                HioneRemoteProtocol.Commands.SHUFFLE -> p?.let {
                    it.shuffleModeEnabled = when (arg.lowercase()) { "on", "1", "true" -> true; "off", "0", "false" -> false; else -> !it.shuffleModeEnabled }
                }
                HioneRemoteProtocol.Commands.REPEAT -> p?.let {
                    it.repeatMode = when (arg.lowercase()) {
                        "off" -> Player.REPEAT_MODE_OFF
                        "all" -> Player.REPEAT_MODE_ALL
                        "one" -> Player.REPEAT_MODE_ONE
                        else -> when (it.repeatMode) {
                            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                            else -> Player.REPEAT_MODE_OFF
                        }
                    }
                }
                HioneRemoteProtocol.Commands.REFRESH -> { pushAllTo(addr); return@post }
                else -> Log.d(TAG, "Unknown command '$text' from $addr")
            }
            // Media3 fires listener callbacks for most of these; volume/like are covered by
            // receivers. A short-delay push here still guarantees the phone sees the result.
            scheduleStatePush()
        }
    }

    // ------------------------------------------------------------------ player observation

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) { scheduleStatePush(); scheduleTick() }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { scheduleStatePush() }
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) { scheduleStatePush() }
        override fun onPlaybackStateChanged(playbackState: Int) { scheduleStatePush() }
        override fun onRepeatModeChanged(repeatMode: Int) { scheduleStatePush() }
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) { scheduleStatePush() }
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) { scheduleStatePush() }
    }

    /** Main thread only. Re-attaches if PlayerHolder rebuilt the player (release() + ensure()). */
    private fun attachPlayerListener() {
        val p = PlayerHolder.player
        if (p === attachedPlayer) return
        try { attachedPlayer?.removeListener(playerListener) } catch (_: Throwable) {}
        attachedPlayer = p
        p?.addListener(playerListener)
    }

    private val statePushRunnable = Runnable { pushToAll(HioneRemoteProtocol.CHAR_NOW_PLAYING) }

    private fun scheduleStatePush() {
        main.removeCallbacks(statePushRunnable)
        main.postDelayed(statePushRunnable, STATE_DEBOUNCE_MS)
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!started) return
            attachPlayerListener()
            val p = attachedPlayer
            val anySubscriber = synchronized(lock) {
                conns.values.any { it.authorized && it.subscriptions.contains(HioneRemoteProtocol.CHAR_NOW_PLAYING) }
            }
            if (p != null && p.isPlaying && anySubscriber) pushToAll(HioneRemoteProtocol.CHAR_NOW_PLAYING)
            // Catch volume changes that arrive without a broadcast (hardware wheel paths).
            val vol = volumePct()
            if (vol != lastVolumePct) { lastVolumePct = vol; pushToAll(HioneRemoteProtocol.CHAR_VOLUME) }
            main.postDelayed(this, POSITION_TICK_MS)
        }
    }

    private fun scheduleTick() {
        main.removeCallbacks(tickRunnable)
        main.postDelayed(tickRunnable, POSITION_TICK_MS)
    }

    // ------------------------------------------------------------------ receivers

    private var receiversRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val st = intent?.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) ?: BluetoothAdapter.ERROR
                    if (st == BluetoothAdapter.STATE_ON && started) {
                        HioneRemoteStatus.update { it.copy(lastError = null) }
                        openServerAndAdvertise()
                    } else if (st == BluetoothAdapter.STATE_TURNING_OFF || st == BluetoothAdapter.STATE_OFF) {
                        advertising = false
                        synchronized(lock) { conns.clear(); notifyQueue.clear(); notifyInFlight = false }
                        try { gattServer?.close() } catch (_: Throwable) {}
                        gattServer = null
                        HioneRemoteStatus.update { it.copy(advertising = false, connected = emptyList(), lastError = "Bluetooth is off. Turn it on to advertise.") }
                        updateNotification()
                    }
                }
                BluetoothAdapter.ACTION_LOCAL_NAME_CHANGED ->
                    HioneRemoteStatus.update { it.copy(advertisedName = currentAdapterName()) }
                "android.media.VOLUME_CHANGED_ACTION" -> main.post {
                    val vol = volumePct()
                    if (vol != lastVolumePct) { lastVolumePct = vol; pushToAll(HioneRemoteProtocol.CHAR_VOLUME) }
                }
                "com.hione.player.action.LIKE_STATE_CHANGED" -> scheduleStatePush()
            }
        }
    }

    private fun registerReceivers() {
        if (receiversRegistered) return
        val f = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_LOCAL_NAME_CHANGED)
            addAction("android.media.VOLUME_CHANGED_ACTION")
            addAction("com.hione.player.action.LIKE_STATE_CHANGED")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(receiver, f)
        }
        receiversRegistered = true
    }

    private fun unregisterReceivers() {
        if (!receiversRegistered) return
        try { unregisterReceiver(receiver) } catch (_: Throwable) {}
        receiversRegistered = false
    }

    // ------------------------------------------------------------------ payloads

    private fun findTrack(id: Long): Track? =
        try { FastLibraryStore.loadSync(this)?.firstOrNull { it.id == id } } catch (_: Throwable) { null }

    /** Main thread only (reads the ExoPlayer). */
    private fun nowPlayingJson(): ByteArray {
        val p = PlayerHolder.player
        val meta = p?.mediaMetadata
        val id = p?.currentMediaItem?.mediaId?.toLongOrNull() ?: -1L
        val liked = if (id > 0) { LikeStore.init(this); LikeStore.isLiked(id) } else false
        val quality = qualityFor(id)
        val dur = p?.duration?.takeIf { it > 0 } ?: 0L
        val json = JSONObject().apply {
            put(HioneRemoteProtocol.NowPlayingKeys.ID, id)
            put(HioneRemoteProtocol.NowPlayingKeys.TITLE, meta?.title?.toString() ?: "")
            put(HioneRemoteProtocol.NowPlayingKeys.ARTIST, meta?.artist?.toString() ?: "")
            put(HioneRemoteProtocol.NowPlayingKeys.ALBUM, meta?.albumTitle?.toString() ?: "")
            put(HioneRemoteProtocol.NowPlayingKeys.POS, p?.currentPosition ?: 0L)
            put(HioneRemoteProtocol.NowPlayingKeys.DUR, dur)
            put(HioneRemoteProtocol.NowPlayingKeys.PLAYING, p?.isPlaying == true)
            put(HioneRemoteProtocol.NowPlayingKeys.LIKED, liked)
            put(HioneRemoteProtocol.NowPlayingKeys.QUALITY, quality)
            put(HioneRemoteProtocol.NowPlayingKeys.SHUFFLE, p?.shuffleModeEnabled == true)
            put(HioneRemoteProtocol.NowPlayingKeys.REPEAT, when (p?.repeatMode) {
                Player.REPEAT_MODE_ALL -> "all"; Player.REPEAT_MODE_ONE -> "one"; else -> "off"
            })
            put(HioneRemoteProtocol.NowPlayingKeys.VOL, volumePct())
            put(HioneRemoteProtocol.NowPlayingKeys.ART, "none")
        }
        return fitAttr(json)
    }

    /** Callable from any thread; blocks briefly for the main-thread read. */
    private fun nowPlayingJsonOnMain(): ByteArray {
        if (Looper.myLooper() == Looper.getMainLooper()) return nowPlayingJson()
        val latch = java.util.concurrent.CountDownLatch(1)
        var out: ByteArray = "{}".toByteArray()
        main.post { try { out = nowPlayingJson() } finally { latch.countDown() } }
        try { latch.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Throwable) {}
        return out
    }

    /** Shrink title/artist/album until the JSON fits the 512-byte attribute limit. */
    private fun fitAttr(json: JSONObject): ByteArray {
        var bytes = json.toString().toByteArray(Charsets.UTF_8)
        val keys = listOf(HioneRemoteProtocol.NowPlayingKeys.ALBUM, HioneRemoteProtocol.NowPlayingKeys.ARTIST, HioneRemoteProtocol.NowPlayingKeys.TITLE)
        var guard = 0
        while (bytes.size > HioneRemoteProtocol.MAX_ATTR_BYTES && guard++ < 40) {
            val k = keys.maxByOrNull { json.optString(it).length } ?: break
            val s = json.optString(k)
            if (s.length <= 8) break
            json.put(k, s.take((s.length * 3) / 4).trimEnd() + "…")
            bytes = json.toString().toByteArray(Charsets.UTF_8)
        }
        return bytes
    }

    private fun qualityFor(id: Long): String {
        if (id <= 0) return ""
        qualityCache?.let { if (it.first == id) return it.second }
        if (lastTrackIdForQuality != id) {
            lastTrackIdForQuality = id
            qualityExecutor.execute {
                val q = try {
                    val t = findTrack(id)
                    if (t == null) "" else {
                        val bits = TrackTech.bitsFor(this, t)
                        val rate = TrackTech.sampleRateFor(this, t)
                        val fmt = t.mime.substringAfterLast('/').uppercase()
                            .replace("X-", "").replace("MPEG", "MP3").take(6)
                        val rateStr = rate?.let { hz -> if (hz % 1000 == 0) "${hz / 1000}k" else String.format(java.util.Locale.US, "%.1fk", hz / 1000.0) }
                        listOfNotNull(
                            if (bits != null && rateStr != null) "$bits/$rateStr" else rateStr,
                            fmt.takeIf { it.isNotBlank() }
                        ).joinToString(" ")
                    }
                } catch (_: Throwable) { "" }
                qualityCache = id to q
                main.post { scheduleStatePush() }
            }
        }
        return ""
    }

    private fun volumePct(): Int {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return 0
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return (am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100) / max
    }

    private fun volumeJson(): ByteArray {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val muted = am != null && (am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0 || am.isStreamMute(AudioManager.STREAM_MUSIC))
        return JSONObject().put("vol", volumePct()).put("muted", muted).toString().toByteArray(Charsets.UTF_8)
    }

    private fun statusJson(authorized: Boolean): ByteArray {
        val bi = try { registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) } catch (_: Throwable) { null }
        val level = bi?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bi?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val status = bi?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (_: Throwable) { "" }
        return JSONObject().apply {
            put("name", HioneRemoteProtocol.LOCAL_NAME)
            put("model", "HiBy M500")
            put("app", "${Mascot.musicAppName} $ver")
            put("battery", pct)
            put("charging", charging)
            put("auth", authorized)
        }.toString().toByteArray(Charsets.UTF_8)
    }

    // ------------------------------------------------------------------ notify plumbing

    /** Main thread. Push one characteristic to every authorized subscriber. */
    private fun pushToAll(uuid: UUID) {
        val targets = synchronized(lock) { conns.values.filter { it.authorized && it.subscriptions.contains(uuid) }.map { it.device.address } }
        if (targets.isEmpty()) return
        val ch = charFor(uuid) ?: return
        val payload = when (uuid) {
            HioneRemoteProtocol.CHAR_NOW_PLAYING -> nowPlayingJson()
            HioneRemoteProtocol.CHAR_VOLUME -> volumeJson()
            else -> return
        }
        targets.forEach { enqueueNotify(it, ch, payload) }
    }

    /** Main thread. Push one characteristic to a single connection (if subscribed + authorized). */
    private fun pushTo(address: String, uuid: UUID) {
        val ok = synchronized(lock) { conns[address]?.let { it.authorized && it.subscriptions.contains(uuid) } == true }
        if (!ok) return
        val ch = charFor(uuid) ?: return
        val payload = when (uuid) {
            HioneRemoteProtocol.CHAR_NOW_PLAYING -> nowPlayingJson()
            HioneRemoteProtocol.CHAR_VOLUME -> volumeJson()
            else -> return
        }
        enqueueNotify(address, ch, payload)
    }

    private fun pushAllTo(address: String) {
        pushTo(address, HioneRemoteProtocol.CHAR_NOW_PLAYING)
        pushTo(address, HioneRemoteProtocol.CHAR_VOLUME)
    }

    private fun charFor(uuid: UUID): BluetoothGattCharacteristic? = when (uuid) {
        HioneRemoteProtocol.CHAR_NOW_PLAYING -> charNowPlaying
        HioneRemoteProtocol.CHAR_VOLUME -> charVolume
        HioneRemoteProtocol.CHAR_PAIR -> charPair
        else -> null
    }

    private fun enqueueNotify(address: String, ch: BluetoothGattCharacteristic, bytes: ByteArray) {
        synchronized(lock) {
            val conn = conns[address] ?: return
            // A notification is capped at MTU-3 bytes; anything bigger is silently truncated by
            // the stack, so send the "read me" marker instead and let the client do a long read.
            val limit = (conn.mtu - 3).coerceAtLeast(20)
            val payload = if (bytes.size <= limit) bytes else HioneRemoteProtocol.NOTIFY_READ_MARKER.toByteArray()
            // Coalesce: a newer value for the same characteristic replaces a queued older one.
            notifyQueue.removeAll { it.address == address && it.ch.uuid == ch.uuid }
            notifyQueue.addLast(Pending(address, ch, payload))
        }
        drainNotifyQueue()
    }

    @Suppress("MissingPermission", "DEPRECATION")
    private fun drainNotifyQueue() {
        val next: Pending
        val device: BluetoothDevice
        synchronized(lock) {
            if (notifyInFlight) return
            next = notifyQueue.pollFirst() ?: return
            device = conns[next.address]?.device ?: run { notifyInFlight = false; return }
            notifyInFlight = true
        }
        val server = gattServer ?: run { synchronized(lock) { notifyInFlight = false }; return }
        val ok = try {
            if (Build.VERSION.SDK_INT >= 33) {
                server.notifyCharacteristicChanged(device, next.ch, false, next.bytes) == BluetoothGatt.GATT_SUCCESS
            } else {
                next.ch.value = next.bytes
                server.notifyCharacteristicChanged(device, next.ch, false)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "notify failed: ${t.message}"); false
        }
        if (!ok) {
            synchronized(lock) { notifyInFlight = false }
            // Skip this one and keep the queue moving.
            main.post { drainNotifyQueue() }
        }
    }

    // ------------------------------------------------------------------ status / notification

    @Suppress("MissingPermission")
    private fun publishConnections() {
        val list = synchronized(lock) {
            conns.values.map { c ->
                HioneRemoteStatus.Phone(
                    address = c.device.address ?: "?",
                    btName = try { c.device.name } catch (_: Throwable) { null },
                    label = c.label,
                    authorized = c.authorized,
                    mtu = c.mtu
                )
            }
        }
        HioneRemoteStatus.update { it.copy(connected = list) }
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Phone Remote (Bluetooth LE)", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while the M500 is reachable from a paired phone"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, HioneRemoteGattService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_DISABLE, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_hione_monitor_status)
            .setContentTitle("${Mascot.name} Remote · Bluetooth")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, "Turn off", stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        if (!started) return
        val s = HioneRemoteStatus.state.value
        val authorized = s.connected.filter { it.authorized }
        val text = when {
            s.lastError != null && !s.advertising -> s.lastError ?: "Error"
            authorized.isNotEmpty() -> "Connected: " + authorized.joinToString { it.displayName }
            s.advertising -> "Ready to pair · code ${s.pairingCode.chunked(3).joinToString(" ")}"
            else -> "Preparing…"
        }
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification(text))
        } catch (_: Throwable) {}
    }
}
