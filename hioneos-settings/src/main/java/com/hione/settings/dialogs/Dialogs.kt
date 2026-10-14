package com.hione.settings.dialogs

import android.annotation.SuppressLint
import android.app.Activity
import android.app.admin.DeviceAdminInfo
import android.app.admin.DevicePolicyManager
import android.appwidget.AppWidgetManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.hione.settings.*
import com.hione.settings.bluetooth.HioneBluetoothController
import com.hione.settings.pages.AppOps
import com.hione.settings.pages.WifiOps
import com.hione.settings.sys.Hidden
import com.hione.settings.ui.*
import java.time.Duration

/**
 * Base for the small permission and confirmation dialogs other apps and the system launch.
 * Translucent activity + one Compose AlertDialog. Every path ends in setResult + finish so a
 * caller waiting in startActivityForResult always gets an answer.
 */
abstract class HioneDialogActivity : ComponentActivity() {
    protected val caller: String? by lazy { Hidden.launchingPackage(this) }
    protected val callerLabel: String by lazy { Hidden.appLabel(this, caller) }

    @Composable abstract fun Content()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Box(Modifier.fillMaxSize()) { Content() } }
    }

    fun done(result: Int, data: Intent? = null) {
        setResult(result, data)
        finish()
    }

    @Composable
    fun Ask(title: String, text: String, allow: String = "Allow", deny: String = "Deny", extra: (@Composable () -> Unit)? = null, onAllow: () -> Unit, onDeny: () -> Unit = { done(Activity.RESULT_CANCELED) }) {
        AlertDialog(
            onDismissRequest = onDeny,
            containerColor = HioneCardBg, titleContentColor = Color.White, textContentColor = HioneMuted,
            title = { Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp) },
            text = { Column { Text(text); if (extra != null) { Spacer(Modifier.height(8.dp)); extra() } } },
            confirmButton = { TextButton(onClick = onAllow) { Text(allow, color = HioneTealBright, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = onDeny) { Text(deny, color = HioneMuted) } }
        )
    }
}

// ---------------------------------------------------------------- Battery optimization request

/** android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS with package:<pkg>. */
class RequestIgnoreBatteryOptActivity : HioneDialogActivity() {
    private val pkg by lazy { intent?.data?.schemeSpecificPart }

    override fun onCreate(savedInstanceState: Bundle?) {
        val p = pkg
        // Same gate as stock: the named app must declare REQUEST_IGNORE_BATTERY_OPTIMIZATIONS.
        if (p.isNullOrBlank() || !AppOps.requests(this, p, "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS")) {
            Log.w("HioneSettings", "battery optimization request for $p refused: app does not declare the permission")
            super.onCreate(savedInstanceState); done(Activity.RESULT_CANCELED); return
        }
        if (AppOps.ignoringBatteryOpt(this, p) == true) { super.onCreate(savedInstanceState); done(Activity.RESULT_OK); return }
        super.onCreate(savedInstanceState)
    }

    @Composable override fun Content() {
        val p = pkg ?: return
        val label = Hidden.appLabel(this, p)
        Ask("Let $label always run in the background?",
            "This may use more battery. You can change it later in Settings, Apps.",
            onAllow = { done(if (AppOps.setIgnoreBatteryOpt(this, p, true)) Activity.RESULT_OK else Activity.RESULT_CANCELED) })
    }
}

// ---------------------------------------------------------------- Device admin activation

/** android.app.action.ADD_DEVICE_ADMIN. */
class DeviceAdminAddActivity : HioneDialogActivity() {
    private val dpm by lazy { getSystemService(DevicePolicyManager::class.java) }
    private var admin: ComponentName? = null
    private var info: DeviceAdminInfo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        @Suppress("DEPRECATION")
        admin = intent?.getParcelableExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN)
        info = admin?.let { cn ->
            try {
                val ri = packageManager.queryBroadcastReceivers(Intent(android.app.admin.DeviceAdminReceiver.ACTION_DEVICE_ADMIN_ENABLED).setComponent(cn),
                    PackageManager.GET_META_DATA).firstOrNull { it.activityInfo.permission == "android.permission.BIND_DEVICE_ADMIN" }
                ri?.let { DeviceAdminInfo(this, it) }
            } catch (t: Throwable) { Log.w("HioneSettings", "bad device admin $cn: ${t.message}"); null }
        }
        super.onCreate(savedInstanceState)
        if (info == null) { done(Activity.RESULT_CANCELED); return }
        if (dpm.isAdminActive(admin!!)) done(Activity.RESULT_OK)
    }

    @Composable override fun Content() {
        val i = info ?: return
        val label = Hidden.appLabel(this, admin!!.packageName)
        val explanation = intent?.getCharSequenceExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION)?.toString()
        val policies = listOf(
            DeviceAdminInfo.USES_POLICY_LIMIT_PASSWORD to "Set password rules",
            DeviceAdminInfo.USES_POLICY_WATCH_LOGIN to "Monitor screen unlock attempts",
            DeviceAdminInfo.USES_POLICY_RESET_PASSWORD to "Change the screen lock",
            DeviceAdminInfo.USES_POLICY_FORCE_LOCK to "Lock the screen",
            DeviceAdminInfo.USES_POLICY_WIPE_DATA to "Erase all data",
            DeviceAdminInfo.USES_POLICY_EXPIRE_PASSWORD to "Set screen lock expiration",
            7 /* USES_POLICY_ENCRYPTED_STORAGE, @hide */ to "Require storage encryption",
            DeviceAdminInfo.USES_POLICY_DISABLE_CAMERA to "Turn off cameras",
            DeviceAdminInfo.USES_POLICY_DISABLE_KEYGUARD_FEATURES to "Turn off lock screen features",
        ).filter { (p, _) -> runCatching { i.usesPolicy(p) }.getOrDefault(false) }.map { it.second }
        Ask("Activate device admin app?",
            listOfNotNull(explanation, "$label will be able to:", policies.joinToString("\n") { "· $it" }.ifBlank { "No special policies" }).joinToString("\n\n"),
            allow = "Activate", deny = "Cancel",
            onAllow = {
                // DevicePolicyManager.setActiveAdmin is @hide; MANAGE_DEVICE_ADMINS.
                val ok = Hidden.tryCall(dpm, "setActiveAdmin", admin, false).isSuccess && dpm.isAdminActive(admin!!)
                if (!ok) toast(this, "Device admin was not activated")
                done(if (ok) Activity.RESULT_OK else Activity.RESULT_CANCELED)
            })
    }
}

// ---------------------------------------------------------------- Bluetooth pairing

/**
 * Manifest receiver for android.bluetooth.device.action.PAIRING_REQUEST.
 *
 * The Bluetooth stack sends it as an ordered, implicit broadcast with
 * FLAG_RECEIVER_INCLUDE_BACKGROUND (BondStateMachine.sendDisplayPinIntent on this build), so a
 * manifest receiver gets it. Priority 1000 puts us before stock Settings' receiver (priority 0);
 * abortBroadcast() then stops the stock dialog from also appearing.
 */
class PairingRequestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_PAIRING_REQUEST) return
        @Suppress("DEPRECATION")
        val device: BluetoothDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) ?: return
        val variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)
        // Keep the old behavior for pairing the user just started from our Bluetooth page:
        // "just works" and numeric-comparison are confirmed directly.
        val ours = HioneBluetoothController.connectingAddress.value == device.address
        if (ours && (variant == 3 || variant == 2)) {
            @SuppressLint("MissingPermission")
            val ok = runCatching { device.setPairingConfirmation(true) }.getOrDefault(false)
            Log.i("HioneSettings", "auto-confirmed pairing started from HioneOS with ${device.address}: $ok")
        } else {
            try {
                context.startActivity(Intent(context, BluetoothPairingActivity::class.java).apply {
                    putExtras(intent)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY)
                })
            } catch (t: Throwable) {
                // Do not abort if we could not show anything: let stock Settings try.
                Log.e("HioneSettings", "pairing dialog failed to start; leaving the request to the next receiver", t)
                return
            }
        }
        if (isOrderedBroadcast) abortBroadcast()
    }
}

/** Pairing dialog. Also handles the PAIRING_REQUEST activity intent (BLUETOOTH_PRIVILEGED). */
class BluetoothPairingActivity : HioneDialogActivity() {
    private val device: BluetoothDevice? by lazy {
        @Suppress("DEPRECATION") intent?.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
    }
    private val variant by lazy { intent?.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1) ?: -1 }
    private val key by lazy { intent?.getIntExtra(BluetoothDevice.EXTRA_PAIRING_KEY, BluetoothDevice.ERROR) ?: BluetoothDevice.ERROR }
    private var closer: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (device == null) { done(Activity.RESULT_CANCELED); return }
        // Close if the bond finishes or the remote cancels, as stock does.
        closer = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                @Suppress("DEPRECATION")
                val d: BluetoothDevice? = i?.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                if (d?.address != device?.address) return
                val state = i?.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                if (i?.action == "android.bluetooth.device.action.PAIRING_CANCEL" || state == BluetoothDevice.BOND_BONDED || state == BluetoothDevice.BOND_NONE) finish()
            }
        }
        ContextCompat.registerReceiver(this, closer, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED); addAction("android.bluetooth.device.action.PAIRING_CANCEL")
        }, ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onDestroy() {
        closer?.let { runCatching { unregisterReceiver(it) } }
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun cancel() {
        Hidden.call(device, "cancelBondProcess")
        done(Activity.RESULT_CANCELED)
    }

    @SuppressLint("MissingPermission")
    @Composable override fun Content() {
        val d = device ?: return
        val name = remember { runCatching { d.alias ?: d.name }.getOrNull() ?: d.address }
        val code = when (variant) { 5 -> "%04d".format(key); else -> "%06d".format(key) }
        when (variant) {
            0, 1, 7 -> TextInputDialog(   // PIN, PASSKEY, PIN_16_DIGITS
                title = "Pair with $name?", label = if (variant == 1) "Passkey" else "PIN",
                numeric = variant != 7, confirm = "Pair", minLength = if (variant == 1) 1 else 4,
                message = "Type the PIN shown on $name, or 0000 or 1234 if it has no screen.",
                onConfirm = { pin ->
                    if (!runCatching { d.setPin(pin.toByteArray(Charsets.UTF_8)) }.getOrDefault(false)) toast(this, "Pairing PIN was rejected")
                    done(Activity.RESULT_OK)
                },
                onDismiss = { cancel() })
            2 -> Ask("Pair with $name?", "Make sure $name shows this code:\n\n$code", allow = "Pair", deny = "Cancel",
                onAllow = { runCatching { d.setPairingConfirmation(true) }; done(Activity.RESULT_OK) }, onDeny = { cancel() })
            4, 5 -> {
                // The other device types or confirms this code. DISPLAY_PIN also hands it to the stack.
                LaunchedEffect(Unit) { if (variant == 5) runCatching { d.setPin(code.toByteArray(Charsets.UTF_8)) } }
                Ask("Pair with $name", "Type $code on $name, then press Enter or Return.", allow = "OK", deny = "Cancel",
                    onAllow = { done(Activity.RESULT_OK) }, onDeny = { cancel() })
            }
            else -> Ask("Pair with $name?", "$name wants to pair with this device.", allow = "Pair", deny = "Cancel",
                onAllow = { runCatching { d.setPairingConfirmation(true) }; done(Activity.RESULT_OK) }, onDeny = { cancel() })
        }
    }
}

/** android.bluetooth.adapter.action.REQUEST_ENABLE / REQUEST_DISABLE / REQUEST_DISCOVERABLE. */
class BluetoothRequestActivity : HioneDialogActivity() {
    private val adapter: BluetoothAdapter? by lazy { getSystemService(BluetoothManager::class.java)?.adapter }

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val a = adapter
        if (a == null) { done(Activity.RESULT_CANCELED); return }
        when (intent?.action) {
            BluetoothAdapter.ACTION_REQUEST_ENABLE -> if (a.isEnabled) done(Activity.RESULT_OK)
            "android.bluetooth.adapter.action.REQUEST_DISABLE" -> if (!a.isEnabled) done(Activity.RESULT_OK)
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    @Composable override fun Content() {
        val a = adapter ?: return
        when (intent?.action) {
            BluetoothAdapter.ACTION_REQUEST_ENABLE -> Ask("$callerLabel wants to turn on Bluetooth", "Bluetooth will stay on until you turn it off.",
                onAllow = { done(if (runCatching { a.enable() }.getOrDefault(false)) Activity.RESULT_OK else Activity.RESULT_CANCELED) })
            "android.bluetooth.adapter.action.REQUEST_DISABLE" -> Ask("$callerLabel wants to turn off Bluetooth", "Connected headphones and devices will disconnect.",
                onAllow = { done(if (runCatching { a.disable() }.getOrDefault(false)) Activity.RESULT_OK else Activity.RESULT_CANCELED) })
            BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE -> {
                val secs = (intent?.getIntExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120) ?: 120).let { if (it <= 0) 120 else it.coerceAtMost(3600) }
                Ask("$callerLabel wants to make this device visible", "Other Bluetooth devices can see this device for $secs seconds.${if (!a.isEnabled) " Bluetooth will be turned on." else ""}",
                    onAllow = {
                        if (!a.isEnabled) runCatching { a.enable() }
                        // setDiscoverableTimeout / setScanMode are @SystemApi (BLUETOOTH_PRIVILEGED).
                        Hidden.call(a, "setDiscoverableTimeout", Duration.ofSeconds(secs.toLong()))
                        val ok = Hidden.call(a, "setScanMode", BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE)
                        // The documented result for this request is the duration in seconds.
                        done(if (ok == 0 || ok == true) secs else Activity.RESULT_CANCELED)
                    })
            }
            else -> done(Activity.RESULT_CANCELED)
        }
    }
}

// ---------------------------------------------------------------- Wi-Fi requests

/** android.net.wifi.action.REQUEST_ENABLE / REQUEST_DISABLE / REQUEST_SCAN_ALWAYS_AVAILABLE. */
class WifiRequestActivity : HioneDialogActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val on = WifiOps.wm(this)?.isWifiEnabled
        when (intent?.action) {
            "android.net.wifi.action.REQUEST_ENABLE" -> if (on == true) done(Activity.RESULT_OK)
            "android.net.wifi.action.REQUEST_DISABLE" -> if (on == false) done(Activity.RESULT_OK)
            "android.net.wifi.action.REQUEST_SCAN_ALWAYS_AVAILABLE" ->
                if (runCatching { Settings.Global.getInt(contentResolver, "wifi_scan_always_enabled") }.getOrNull() == 1) done(Activity.RESULT_OK)
        }
    }

    @Composable override fun Content() {
        when (intent?.action) {
            "android.net.wifi.action.REQUEST_ENABLE" -> Ask("$callerLabel wants to turn on Wi-Fi", "Wi-Fi stays on until you turn it off.",
                onAllow = { done(if (WifiOps.setEnabled(this, true)) Activity.RESULT_OK else Activity.RESULT_CANCELED) })
            "android.net.wifi.action.REQUEST_DISABLE" -> Ask("$callerLabel wants to turn off Wi-Fi", "You will lose any Wi-Fi connection.",
                onAllow = { done(if (WifiOps.setEnabled(this, false)) Activity.RESULT_OK else Activity.RESULT_CANCELED) })
            "android.net.wifi.action.REQUEST_SCAN_ALWAYS_AVAILABLE" -> Ask("$callerLabel wants to scan for networks",
                "Apps and location services can scan for Wi-Fi networks even when Wi-Fi is off.",
                onAllow = {
                    val ok = runCatching { Settings.Global.putInt(contentResolver, "wifi_scan_always_enabled", 1) }.getOrDefault(false)
                    done(if (ok) Activity.RESULT_OK else Activity.RESULT_CANCELED)
                })
            else -> done(Activity.RESULT_CANCELED)
        }
    }
}

// ---------------------------------------------------------------- Widget binding

/** android.appwidget.action.APPWIDGET_BIND: a launcher asks to place a widget. */
class AppWidgetBindActivity : HioneDialogActivity() {
    private val awm by lazy { AppWidgetManager.getInstance(this) }
    private val id by lazy { intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID }
    @Suppress("DEPRECATION")
    private val provider: ComponentName? by lazy { intent?.getParcelableExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER) }
    @Suppress("DEPRECATION")
    private val profile: UserHandle by lazy { intent?.getParcelableExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE) ?: android.os.Process.myUserHandle() }

    private fun result(ok: Boolean) = done(if (ok) Activity.RESULT_OK else Activity.RESULT_CANCELED,
        Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))

    private fun bind(): Boolean = try {
        awm.bindAppWidgetIdIfAllowed(id, profile, provider, intent?.getBundleExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS))
    } catch (t: Throwable) { Log.w("HioneSettings", "bind widget failed: ${t.message}"); false }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID || provider == null || caller == null) { result(false); return }
        // Already allowed for this launcher (hasBindAppWidgetPermission is @hide).
        if (Hidden.call(awm, "hasBindAppWidgetPermission", caller) == true) result(bind())
    }

    @Composable override fun Content() {
        var always by remember { mutableStateOf(false) }
        val widgetApp = Hidden.appLabel(this, provider?.packageName)
        Ask("Allow $callerLabel to create widgets?", "$callerLabel will be able to show widgets from $widgetApp and read their data.",
            allow = "Create", deny = "Cancel",
            extra = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(always, { always = it }, colors = CheckboxDefaults.colors(checkedColor = HioneTealBright))
                    Text("Always allow $callerLabel to create widgets", color = HioneMuted, fontSize = 12.sp)
                }
            },
            onAllow = {
                // setBindAppWidgetPermission is @hide; MODIFY_APPWIDGET_BIND_PERMISSIONS.
                if (always) Hidden.call(awm, "setBindAppWidgetPermission", caller, true)
                result(bind())
            }, onDeny = { result(false) })
    }
}
