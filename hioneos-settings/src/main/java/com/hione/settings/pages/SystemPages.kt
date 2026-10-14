package com.hione.settings.pages

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.LocaleList
import android.os.UserManager
import android.provider.Settings
import android.text.format.DateFormat
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.settings.*
import com.hione.settings.sys.Hidden
import com.hione.settings.ui.*
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

@Composable
fun SystemPage() {
    val nav = LocalNav.current
    PageList {
        item {
            Section {
                NavRow("Languages", "System language and per-app languages", Icons.Default.Language) { nav.push("languages") }
                NavRow("Keyboard", "On-screen keyboards and input", Icons.Default.Keyboard) { nav.push("keyboard") }
                NavRow("Date & time", "Time zone, clock format and network time", Icons.Default.Schedule) { nav.push("datetime") }
                NavRow("Accounts", "Google and other accounts, sync", Icons.Default.AccountCircle) { nav.push("accounts") }
                NavRow("Users", "Who uses this device", Icons.Default.People) { nav.push("users") }
                NavRow("Developer options", "USB debugging, animations and more", Icons.Default.DeveloperMode) { nav.push("developer") }
                NavRow("Reset options", "Reset network settings or erase everything", Icons.Default.RestartAlt) { nav.push("reset") }
            }
        }
    }
}

// ---------------------------------------------------------------- Date & time

@Composable
fun DateTimePage() {
    val ctx = LocalContext.current
    val cr = ctx.contentResolver
    val tick = BroadcastTick(Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_TICK)
    var refresh by remember { mutableIntStateOf(0) }
    fun g(k: String): Boolean? = try { Settings.Global.getInt(cr, k) == 1 } catch (_: Throwable) { null }
    val autoTime = remember(refresh) { g(Settings.Global.AUTO_TIME) }
    val autoZone = remember(refresh) { g(Settings.Global.AUTO_TIME_ZONE) }
    val is24 = remember(refresh) { DateFormat.is24HourFormat(ctx) }
    val now = remember(tick, refresh) { Calendar.getInstance() }
    var tzPick by remember { mutableStateOf(false) }
    val am = remember { ctx.getSystemService(AlarmManager::class.java) }
    val themed = remember { android.view.ContextThemeWrapper(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert) }

    if (tzPick) { TimeZonePicker(onPick = { id ->
        tzPick = false
        // AlarmManager.setTimeZone needs SET_TIME_ZONE (signature|privileged).
        if (runCatching { am.setTimeZone(id) }.isFailure) toast(ctx, "Time zone was not changed")
        refresh++
    }, onCancel = { tzPick = false }); return }

    PageList {
        item {
            Section {
                ToggleRow("Set time automatically", "Use network-provided time", autoTime) {
                    if (runCatching { Settings.Global.putInt(cr, Settings.Global.AUTO_TIME, if (it) 1 else 0) }.isFailure) toast(ctx, "Setting was refused"); refresh++
                }
                NavRow("Date", DateFormat.getLongDateFormat(ctx).format(now.time)) {
                    if (autoTime == true) { toast(ctx, "Turn off automatic time first"); return@NavRow }
                    DatePickerDialog(themed, { _, y, m, d ->
                        val c = Calendar.getInstance().apply { set(y, m, d) }
                        if (runCatching { am.setTime(c.timeInMillis) }.isFailure) toast(ctx, "Date was not changed"); refresh++
                    }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).show()
                }
                NavRow("Time", DateFormat.getTimeFormat(ctx).format(now.time)) {
                    if (autoTime == true) { toast(ctx, "Turn off automatic time first"); return@NavRow }
                    TimePickerDialog(themed, { _, h, min ->
                        val c = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, min); set(Calendar.SECOND, 0) }
                        if (runCatching { am.setTime(c.timeInMillis) }.isFailure) toast(ctx, "Time was not changed"); refresh++
                    }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), is24).show()
                }
            }
        }
        item {
            Section {
                ToggleRow("Set time zone automatically", "Use the network or location", autoZone) {
                    if (runCatching { Settings.Global.putInt(cr, Settings.Global.AUTO_TIME_ZONE, if (it) 1 else 0) }.isFailure) toast(ctx, "Setting was refused"); refresh++
                }
                NavRow("Time zone", TimeZone.getDefault().let { "${it.getDisplayName(it.inDaylightTime(now.time), TimeZone.LONG)} (${it.id})" }) {
                    if (autoZone == true) toast(ctx, "Turn off automatic time zone first") else tzPick = true
                }
                ToggleRow("Use 24-hour format", if (is24) "13:00" else "1:00 PM", is24) {
                    if (runCatching { Settings.System.putString(cr, Settings.System.TIME_12_24, if (it) "24" else "12") }.isFailure) toast(ctx, "Setting was refused")
                    // Stock (uid 1000) also broadcasts TIME_SET so clocks redraw. That broadcast is
                    // protected and refused for our uid; apps that watch time_12_24 update anyway.
                    runCatching { ctx.sendBroadcast(Intent(Intent.ACTION_TIME_CHANGED).putExtra("android.intent.extra.TIME_PREF_24_HOUR_FORMAT", if (it) 1 else 0)) }
                    refresh++
                }
            }
        }
    }
}

@Composable
private fun TimeZonePicker(onPick: (String) -> Unit, onCancel: () -> Unit) {
    val now = System.currentTimeMillis()
    val zones = remember {
        TimeZone.getAvailableIDs().filter { it.contains('/') && !it.startsWith("Etc/") && !it.startsWith("SystemV/") }
            .map { TimeZone.getTimeZone(it) }.sortedWith(compareBy({ it.getOffset(now) }, { it.id }))
    }
    var q by remember { mutableStateOf("") }
    androidx.activity.compose.BackHandler { onCancel() }
    PageList {
        item { SearchField(q, "Search city or region") { q = it } }
        items(zones.filter { q.isBlank() || it.id.contains(q, true) || it.getDisplayName(Locale.getDefault()).contains(q, true) }, key = { it.id }) { z ->
            val off = z.getOffset(now) / 60000
            NavRow(z.id.substringAfterLast('/').replace('_', ' '), "GMT%s%02d:%02d · %s".format(if (off < 0) "-" else "+", Math.abs(off) / 60, Math.abs(off) % 60, z.getDisplayName(Locale.getDefault()))) { onPick(z.id) }
        }
    }
}

@Composable
fun SearchField(q: String, hint: String, onChange: (String) -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(HioneSurface1)
            .border(1.dp, HioneTeal.copy(alpha = 0.35f), RoundedCornerShape(24.dp)).padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        BasicTextField(q, onChange, singleLine = true, modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 13.5.sp),
            decorationBox = { inner -> if (q.isEmpty()) Text(hint, color = HioneMuted, fontSize = 13.sp); inner() })
    }
}

// ---------------------------------------------------------------- Languages

object LocaleOps {
    /** The locales the system image has resources for, via the same internal LocalePicker stock uses. */
    fun supported(ctx: Context): List<Locale> {
        @Suppress("UNCHECKED_CAST")
        val tags = (Hidden.callStatic("com.android.internal.app.LocalePicker", "getSupportedLocales", ctx) as? Array<String>)
            ?: (Hidden.call(android.content.res.Resources.getSystem().assets, "getLocales") as? Array<String>)
            ?: emptyArray()
        return tags.map { Locale.forLanguageTag(it.replace('_', '-')) }
            .filter { it.language.isNotBlank() && it.country !in setOf("XA", "XB") }   // drop pseudo-locales
            .distinctBy { it.toLanguageTag() }
            .sortedBy { it.getDisplayName(it).lowercase() }
    }

    fun current(): LocaleList = android.content.res.Resources.getSystem().configuration.locales

    /** LocalePicker.updateLocales(LocaleList): CHANGE_CONFIGURATION, persists like stock. */
    fun setPrimary(loc: Locale): Boolean {
        val cur = current()
        val list = buildList { add(loc); for (i in 0 until cur.size()) if (cur[i].toLanguageTag() != loc.toLanguageTag()) add(cur[i]) }
        return runCatching {
            Class.forName("com.android.internal.app.LocalePicker").getMethod("updateLocales", LocaleList::class.java)
                .invoke(null, LocaleList(*list.toTypedArray()))
        }.isSuccess
    }
}

@Composable
fun LanguagesPage() {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    var q by remember { mutableStateOf("") }
    val all = remember { LocaleOps.supported(ctx) }
    var refresh by remember { mutableIntStateOf(0) }
    val cur = remember(refresh) { LocaleOps.current() }
    var pick by remember { mutableStateOf<Locale?>(null) }
    PageList {
        item {
            Section("System language") {
                InfoRow("Current", cur[0]?.let { it.getDisplayName(it) })
                NavRow("App languages", "Pick a language for one app", Icons.Default.Apps) { nav.push("apps") }
                NavRow("Keyboard", null, Icons.Default.Keyboard) { nav.push("keyboard") }
            }
        }
        item { SearchField(q, "Search languages") { q = it } }
        if (all.isEmpty()) item { BodyText("The list of system languages could not be read.") }
        items(all.filter { q.isBlank() || it.getDisplayName(it).contains(q, true) || it.getDisplayName(Locale.US).contains(q, true) }, key = { it.toLanguageTag() }) { loc ->
            RadioRow(loc.getDisplayName(loc).replaceFirstChar { it.uppercase() }, loc.getDisplayName(Locale.getDefault()), cur[0]?.toLanguageTag() == loc.toLanguageTag()) { pick = loc }
        }
    }
    pick?.let { loc ->
        HioneAlert("Change language?", "The system language becomes ${loc.getDisplayName(loc)}. Open apps may restart.", "Change",
            onConfirm = { pick = null; if (!LocaleOps.setPrimary(loc)) toast(ctx, "Language was not changed"); refresh++ }, onDismiss = { pick = null })
    }
}

// ---------------------------------------------------------------- Keyboard

@Composable
fun KeyboardPage() {
    val ctx = LocalContext.current
    val cr = ctx.contentResolver
    val imm = remember { ctx.getSystemService(InputMethodManager::class.java) }
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeTick { refresh++ }
    val all: List<InputMethodInfo> = remember(refresh) { try { imm.inputMethodList } catch (_: Throwable) { emptyList() } }
    val enabledIds = remember(refresh) { try { imm.enabledInputMethodList.map { it.id }.toSet() } catch (_: Throwable) { emptySet() } }
    val default = remember(refresh) { Settings.Secure.getString(cr, Settings.Secure.DEFAULT_INPUT_METHOD) }
    fun writeEnabled(ids: Set<String>): Boolean = runCatching { Settings.Secure.putString(cr, Settings.Secure.ENABLED_INPUT_METHODS, ids.joinToString(":")) }.isSuccess
    PageList {
        item {
            Section("Default keyboard") {
                NavRow(all.firstOrNull { it.id == default }?.loadLabel(ctx.packageManager)?.toString() ?: default ?: UNKNOWN, "Tap to switch keyboards") {
                    try { imm.showInputMethodPicker() } catch (_: Throwable) { toast(ctx, "Keyboard picker not available") }
                }
            }
        }
        item {
            Section("On-screen keyboards") {
                all.forEach { im ->
                    val label = im.loadLabel(ctx.packageManager).toString()
                    ToggleRow(label, if (im.id == default) "In use" else im.packageName, im.id in enabledIds, enabled = im.id != default) { on ->
                        val next = if (on) enabledIds + im.id else enabledIds - im.id
                        if (!writeEnabled(next)) toast(ctx, "Keyboard list was not changed"); refresh++
                    }
                    if (im.settingsActivity != null) NavRow("$label settings", null) {
                        try { ctx.startActivity(Intent(Intent.ACTION_MAIN).setComponent(ComponentName(im.packageName, im.settingsActivity))) } catch (_: Throwable) { toast(ctx, "Settings not available") }
                    }
                }
            }
        }
        item {
            key(refresh) {
                Section {
                    ToggleRow("Show on-screen keyboard", "Keep it on screen while a physical keyboard is connected",
                        try { Settings.Secure.getInt(cr, "show_ime_with_hard_keyboard") == 1 } catch (_: Throwable) { null }) {
                        if (runCatching { Settings.Secure.putInt(cr, "show_ime_with_hard_keyboard", if (it) 1 else 0) }.isFailure) toast(ctx, "Setting was refused"); refresh++
                    }
                }
            }
        }
        item { Section("Not built yet") {
            StockRow("Physical keyboard layouts", ".Settings\$PhysicalKeyboardActivity")
            StockRow("Personal dictionary", ".Settings\$UserDictionarySettingsActivity")
        } }
    }
}

// ---------------------------------------------------------------- Accounts

@Composable
fun AccountsPage() {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeTick { refresh++ }
    val am = remember { AccountManager.get(ctx) }
    // GET_ACCOUNTS_PRIVILEGED lets us see every account, not only our own.
    val accounts: List<Account>? = remember(refresh) { try { am.accounts.toList() } catch (_: Throwable) { null } }
    val types = remember(refresh) { try { am.authenticatorTypes.associateBy { it.type } } catch (_: Throwable) { emptyMap() } }
    PageList {
        item {
            Section("Accounts", note = when { accounts == null -> "Accounts could not be read."; accounts.isEmpty() -> "No accounts on this device."; else -> null }) {
                accounts?.forEach { a ->
                    val typeLabel = types[a.type]?.let { d -> try { ctx.packageManager.getResourcesForApplication(d.packageName).getString(d.labelId) } catch (_: Throwable) { null } } ?: a.type
                    NavRow(a.name, "$typeLabel. Sync and remove open the stock page") {
                        openStockPage(ctx, ".Settings\$AccountSyncSettingsActivity", "android.settings.ACCOUNT_SYNC_SETTINGS", Bundle().apply { putParcelable("account", a) })
                    }
                }
                NavRow("Add account", null, Icons.Default.Add) { nav.push("accounts_add") }
            }
        }
        item {
            key(refresh) {
                Section {
                    ToggleRow("Automatically sync app data", "Apps refresh data in the background", try { ContentResolver.getMasterSyncAutomatically() } catch (_: Throwable) { null }) {
                        if (runCatching { ContentResolver.setMasterSyncAutomatically(it) }.isFailure) toast(ctx, "Sync setting was refused"); refresh++
                    }
                }
            }
        }
    }
}

/**
 * android.settings.ADD_ACCOUNT_SETTINGS. Play Store and setup flows send this when no account
 * exists. Honors the "account_types" filter extra like stock, and answers RESULT_OK when an
 * account was added so callers that wait for a result continue.
 */
@Composable
fun AddAccountPage() {
    val ctx = LocalContext.current
    val src = LocalSource.current
    val activity = ctx as? Activity
    val am = remember { AccountManager.get(ctx) }
    val wanted = remember { src?.getStringArrayExtra("account_types")?.toSet() }
    val types = remember {
        try { am.authenticatorTypes.filter { wanted == null || it.type in wanted } } catch (_: Throwable) { emptyList() }
    }
    fun label(d: android.accounts.AuthenticatorDescription) =
        try { ctx.packageManager.getResourcesForApplication(d.packageName).getString(d.labelId) } catch (_: Throwable) { d.type }
    fun add(type: String) {
        am.addAccount(type, null, null, null, activity, { f ->
            val ok = try { f.result.containsKey(AccountManager.KEY_ACCOUNT_NAME) } catch (_: Throwable) { false }
            if (ok) { activity?.setResult(Activity.RESULT_OK); activity?.finish() }
        }, null)
    }
    LaunchedEffect(Unit) { if (types.size == 1) add(types[0].type) }
    PageList {
        item {
            Section("Add an account", note = if (types.isEmpty()) "No app on this device can add that kind of account." else null) {
                types.forEach { d -> NavRow(label(d), d.packageName) { add(d.type) } }
            }
        }
    }
}

// ---------------------------------------------------------------- Users

@Composable
fun UsersPage() {
    val ctx = LocalContext.current
    val um = remember { ctx.getSystemService(UserManager::class.java) }
    val users = remember { (Hidden.call(um, "getUsers") as? List<*>)?.mapNotNull { u ->
        try { (u!!.javaClass.getField("id").getInt(u)) to (u.javaClass.getField("name").get(u) as? String) } catch (_: Throwable) { null }
    } }
    val me = Hidden.myUserId()
    PageList {
        item {
            Section("Users", note = if (users == null) "The user list could not be read." else null) {
                users?.forEach { (id, name) -> InfoRow(name ?: "User $id", if (id == me) "You (user $id)" else "User $id") }
                InfoRow("Multiple users supported", if (UserManager.supportsMultipleUsers()) "Yes" else "No")
            }
        }
        item { Section("Not built yet") { StockRow("Add user or guest", ".Settings\$UserSettingsActivity") } }
    }
}

// ---------------------------------------------------------------- Developer options

@Composable
fun DeveloperPage() {
    val ctx = LocalContext.current
    val cr = ctx.contentResolver
    var refresh by remember { mutableIntStateOf(0) }
    fun gi(k: String): Int? = try { Settings.Global.getInt(cr, k) } catch (_: Throwable) { null }
    fun gf(k: String): Float? = try { Settings.Global.getFloat(cr, k) } catch (_: Throwable) { null }
    fun si(k: String): Int? = try { Settings.System.getInt(cr, k) } catch (_: Throwable) { null }
    fun putG(k: String, v: Int) { if (runCatching { Settings.Global.putInt(cr, k, v) }.isFailure) toast(ctx, "Setting was refused"); refresh++ }
    fun putS(k: String, v: Int) { if (runCatching { Settings.System.putInt(cr, k, v) }.isFailure) toast(ctx, "Setting was refused"); refresh++ }
    var scalePick by remember { mutableStateOf<Pair<String, String>?>(null) }
    var revoke by remember { mutableStateOf(false) }
    val adbPort = remember(refresh) { com.hione.settings.WirelessAdbManager.currentPort() }
    PageList {
        item {
            key(refresh) {
                Section {
                    ToggleRow("Developer options", "Shows these settings across the system", gi(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED)?.let { it == 1 }) { putG(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, if (it) 1 else 0) }
                }
            }
        }
        item {
            key(refresh) {
                Section("Debugging") {
                    ToggleRow("USB debugging", "Lets a computer run adb over USB", gi(Settings.Global.ADB_ENABLED)?.let { it == 1 }) { putG(Settings.Global.ADB_ENABLED, if (it) 1 else 0) }
                    ToggleRow("Wireless debugging", "Android pairing-code adb over Wi-Fi", gi("adb_wifi_enabled")?.let { it == 1 }) { putG("adb_wifi_enabled", if (it) 1 else 0) }
                    InfoRow("adb over TCP (port 5555 mode)", adbPort?.let { "Listening on port $it" } ?: "Off")
                    NavRow("adb over TCP and Wi-Fi ADB tools", "In Network & ADB", Icons.Default.Wifi) {
                        ctx.startActivity(Intent(ctx, HioneSettingsActivity::class.java).putExtra("section", "wireless"))
                    }
                    NavRow("Revoke USB debugging authorizations", "Computers must be approved again") { revoke = true }
                    ToggleRow("Stay awake while charging", "Screen never sleeps while plugged in", gi(Settings.Global.STAY_ON_WHILE_PLUGGED_IN)?.let { it != 0 }) { putG(Settings.Global.STAY_ON_WHILE_PLUGGED_IN, if (it) 7 else 0) }
                }
            }
        }
        item {
            key(refresh) {
                Section("Input & drawing") {
                    ToggleRow("Show taps", "Draws a dot where you touch", si("show_touches")?.let { it == 1 }) { putS("show_touches", if (it) 1 else 0) }
                    ToggleRow("Pointer location", "Overlay with touch coordinates", si("pointer_location")?.let { it == 1 }) { putS("pointer_location", if (it) 1 else 0) }
                    listOf(
                        Settings.Global.WINDOW_ANIMATION_SCALE to "Window animation scale",
                        Settings.Global.TRANSITION_ANIMATION_SCALE to "Transition animation scale",
                        Settings.Global.ANIMATOR_DURATION_SCALE to "Animator duration scale"
                    ).forEach { (k, t) -> NavRow(t, gf(k)?.let { "${it}x" } ?: "1.0x (default)") { scalePick = k to t } }
                    ToggleRow("Don't keep activities", "Closes every activity as soon as you leave it", gi(Settings.Global.ALWAYS_FINISH_ACTIVITIES)?.let { it == 1 }) { on ->
                        // AMS only reads the setting at boot; setAlwaysFinish applies it now (SET_ALWAYS_FINISH).
                        val am = Hidden.callStatic("android.app.ActivityManager", "getService")
                        if (Hidden.tryCall(am, "setAlwaysFinish", on).isFailure) putG(Settings.Global.ALWAYS_FINISH_ACTIVITIES, if (on) 1 else 0) else refresh++
                    }
                }
            }
        }
        item {
            Section("Not built yet") {
                StockRow("All developer options", ".Settings\$DevelopmentSettingsDashboardActivity", "OEM unlocking, mock location, logging and more", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                StockRow("Running services", ".Settings\$RunningServicesActivity")
            }
        }
    }
    scalePick?.let { (k, t) ->
        val opts = listOf(0f, 0.5f, 1f, 1.5f, 2f, 5f, 10f)
        ChoiceDialog(t, opts.map { it to if (it == 0f) "Animation off" else "${it}x" }, gf(k) ?: 1f, onPick = {
            scalePick = null
            if (runCatching { Settings.Global.putFloat(cr, k, it) }.isFailure) toast(ctx, "Setting was refused"); refresh++
        }, onDismiss = { scalePick = null })
    }
    if (revoke) HioneAlert("Revoke authorizations?", "Every computer you approved for USB debugging has to be approved again.", "Revoke", danger = true,
        onConfirm = {
            revoke = false
            val adb = Hidden.serviceInterface("adb", "android.debug.IAdbManager")
            if (Hidden.tryCall(adb, "clearDebuggingKeys").isFailure) toast(ctx, "Authorizations were not revoked")
        }, onDismiss = { revoke = false })
}

// ---------------------------------------------------------------- Reset

object ResetOps {
    /**
     * Network reset, the same calls stock ResetNetworkConfirm makes. Each part is reported
     * separately because any one of them can be refused.
     */
    fun resetNetwork(ctx: Context): List<Pair<String, Boolean>> {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        val wm = WifiOps.wm(ctx)
        val bt = try { ctx.getSystemService(android.bluetooth.BluetoothManager::class.java).adapter } catch (_: Throwable) { null }
        val tm = MobileOps.tm(ctx)
        val vpn = try { ctx.getSystemService("vpn_management") } catch (_: Throwable) { null }
        return listOf(
            "Connectivity" to Hidden.tryCall(cm, "factoryReset").isSuccess,
            "Wi-Fi" to Hidden.tryCall(wm, "factoryReset").isSuccess,
            "Bluetooth" to (Hidden.tryCall(bt, "clearBluetooth").isSuccess || Hidden.tryCall(bt, "factoryReset").isSuccess),
            "Mobile network" to Hidden.tryCall(tm, "resetSettings").isSuccess,
            "VPN" to Hidden.tryCall(vpn, "factoryReset").isSuccess,
        )
    }

    /**
     * Erase everything. Mirrors stock MainClearConfirm: wipe the factory-reset-protection block
     * when OEM unlocking is off (so the reset is treated as owner-approved), then ask the system
     * MasterClearReceiver to reboot into recovery. ACTION_FACTORY_RESET is not a protected
     * broadcast on this build; its receiver requires MASTER_CLEAR, which platform signing grants.
     */
    fun factoryReset(ctx: Context, eraseSd: Boolean) {
        Thread {
            try {
                val pdb = ctx.getSystemService("persistent_data_block")
                val oemAllowed = Hidden.call(pdb, "getOemUnlockEnabled") as? Boolean
                if (pdb != null && oemAllowed == false) Hidden.call(pdb, "wipe")
            } catch (_: Throwable) {}
            ctx.sendBroadcast(Intent("android.intent.action.FACTORY_RESET").apply {
                setPackage("android")
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                putExtra("android.intent.extra.REASON", "HioneSettings")
                putExtra("android.intent.extra.WIPE_EXTERNAL_STORAGE", eraseSd)
                putExtra("com.android.internal.intent.extra.WIPE_ESIMS", false)
            })
        }.start()
    }
}

@Composable
fun ResetPage() {
    val ctx = LocalContext.current
    var stage by remember { mutableStateOf<String?>(null) }
    var eraseSd by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    PageList {
        item {
            Section("Reset Wi-Fi, mobile & Bluetooth", note = "Deletes saved Wi-Fi networks and paired Bluetooth devices, and resets mobile network settings.") {
                ActionButton("Reset network settings", danger = true, modifier = Modifier.fillMaxWidth()) { stage = "net" }
                result?.let { Spacer(Modifier.height(6.dp)); BodyText(it) }
            }
        }
        item {
            Section("Erase all data (factory reset)", note = "Deletes all apps, accounts, music and settings on internal storage. This cannot be undone.") {
                ToggleRow("Also erase the SD card", "Deletes music and files on the memory card", eraseSd) { eraseSd = it }
                ActionButton("Erase all data", danger = true, modifier = Modifier.fillMaxWidth()) { stage = "wipe1" }
            }
        }
        item { Section("Not built yet") { BodyText("Reset app preferences is not built yet. The stock app has no direct entry point for it.") } }
    }
    when (stage) {
        "net" -> HioneAlert("Reset network settings?", "Saved Wi-Fi networks, paired Bluetooth devices and mobile settings are removed.", "Reset", danger = true,
            onConfirm = {
                stage = null
                val r = ResetOps.resetNetwork(ctx)
                result = r.joinToString(", ") { (k, ok) -> "$k ${if (ok) "reset" else "not reset"}" }
            }, onDismiss = { stage = null })
        "wipe1" -> HioneAlert("Erase all data?", "Everything on internal storage${if (eraseSd) " and the SD card" else ""} is deleted and the device restarts.", "Continue", danger = true,
            onConfirm = { stage = "wipe2" }, onDismiss = { stage = null })
        "wipe2" -> TextInputDialog("Type ERASE to confirm", "ERASE", confirm = "Erase everything", minLength = 5,
            onConfirm = { if (it.trim() == "ERASE") { stage = null; ResetOps.factoryReset(ctx, eraseSd); toast(ctx, "Erasing. The device will restart.") } else toast(ctx, "Type ERASE in capital letters") },
            onDismiss = { stage = null })
    }
}
