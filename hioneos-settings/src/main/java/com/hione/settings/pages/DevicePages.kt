package com.hione.settings.pages

import com.hione.settings.Mascot
import android.app.Activity
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.UiModeManager
import android.app.usage.StorageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Process
import android.os.storage.StorageManager
import android.provider.Settings
import android.text.format.Formatter
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hione.settings.HioneSettingsActivity
import com.hione.settings.sys.Hidden
import com.hione.settings.ui.*

private fun openSection(ctx: Context, section: String) =
    ctx.startActivity(Intent(ctx, HioneSettingsActivity::class.java).putExtra("section", section))

/** Small helpers for Settings.System / Secure / Global ints that show "Unknown" when unset. */
private class Prefs(val ctx: Context, val onWrite: () -> Unit) {
    val cr = ctx.contentResolver
    fun sys(k: String): Int? = try { Settings.System.getInt(cr, k) } catch (_: Throwable) { null }
    fun sec(k: String): Int? = try { Settings.Secure.getInt(cr, k) } catch (_: Throwable) { null }
    fun glob(k: String): Int? = try { Settings.Global.getInt(cr, k) } catch (_: Throwable) { null }
    private fun done(ok: Boolean) { if (!ok) toast(ctx, "That setting was refused"); onWrite() }
    fun putSys(k: String, v: Int) = done(runCatching { Settings.System.putInt(cr, k, v) }.getOrDefault(false))
    fun putSec(k: String, v: Int) = done(runCatching { Settings.Secure.putInt(cr, k, v) }.getOrDefault(false))
    fun putGlob(k: String, v: Int) = done(runCatching { Settings.Global.putInt(cr, k, v) }.getOrDefault(false))
}

// ---------------------------------------------------------------- Sound

@Composable
fun SoundPage() {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    val am = remember { ctx.getSystemService(AudioManager::class.java) }
    var refresh by remember { mutableIntStateOf(0) }
    val tick = BroadcastTick("android.media.VOLUME_CHANGED_ACTION", AudioManager.RINGER_MODE_CHANGED_ACTION)
    val p = remember { Prefs(ctx) { refresh++ } }
    var ringerPick by remember { mutableStateOf(false) }
    var pickType by remember { mutableIntStateOf(0) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val uri: Uri? = r.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            if (runCatching { RingtoneManager.setActualDefaultRingtoneUri(ctx, pickType, uri) }.isFailure) toast(ctx, "Sound was not changed")
            refresh++
        }
    }
    fun pick(type: Int, title: String) {
        pickType = type
        try {
            picker.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, type)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, title)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, RingtoneManager.getActualDefaultRingtoneUri(ctx, type)))
        } catch (_: Throwable) { toast(ctx, "Sound picker not available") }
    }
    fun soundName(type: Int): String = try {
        RingtoneManager.getActualDefaultRingtoneUri(ctx, type)?.let { RingtoneManager.getRingtone(ctx, it)?.getTitle(ctx) } ?: "None"
    } catch (_: Throwable) { UNKNOWN }

    PageList {
        item {
            Section {
                NavRow("DAC settings", "Filter, gain, DRE and USB DAC, in the Hardware app", Icons.Default.Headphones) { com.hione.settings.DacSettingsLink.open(ctx) }
                NavRow("${Mascot.osName} sounds", "Charger, battery, lock, screenshot and other system sounds", Icons.Default.MusicNote) { nav.push("hione_sounds") }
            }
        }
        item {
            key(tick, refresh) {
                Section("Volume", note = "${Mascot.osName} may hold media volume at the hardware level. These sliders set the Android stream volume.") {
                    listOf(
                        AudioManager.STREAM_MUSIC to "Media",
                        AudioManager.STREAM_RING to "Ring",
                        AudioManager.STREAM_NOTIFICATION to "Notifications",
                        AudioManager.STREAM_ALARM to "Alarms"
                    ).forEach { (s, label) ->
                        val max = am.getStreamMaxVolume(s)
                        var v by remember { mutableFloatStateOf(am.getStreamVolume(s).toFloat()) }
                        SliderRow(label, "${v.toInt()} / $max", v, 0f..max.toFloat(), steps = (max - 1).coerceAtLeast(0), onChange = {
                            v = it
                            runCatching { am.setStreamVolume(s, it.toInt(), 0) }.onFailure { toast(ctx, "$label volume was not changed") }
                        })
                    }
                }
            }
        }
        item {
            key(tick, refresh) {
                Section {
                    NavRow("Ring mode", when (am.ringerMode) { AudioManager.RINGER_MODE_SILENT -> "Silent"; AudioManager.RINGER_MODE_VIBRATE -> "Vibrate"; else -> "Sound" }) { ringerPick = true }
                    NavRow("Do Not Disturb", null, Icons.Default.DoNotDisturbOn) { nav.push("dnd") }
                    NavRow("Phone ringtone", soundName(RingtoneManager.TYPE_RINGTONE)) { pick(RingtoneManager.TYPE_RINGTONE, "Ringtone") }
                    NavRow("Default notification sound", soundName(RingtoneManager.TYPE_NOTIFICATION)) { pick(RingtoneManager.TYPE_NOTIFICATION, "Notification sound") }
                    NavRow("Default alarm sound", soundName(RingtoneManager.TYPE_ALARM)) { pick(RingtoneManager.TYPE_ALARM, "Alarm sound") }
                }
            }
        }
        item {
            key(refresh) {
                Section("Other sounds and vibrations") {
                    ToggleRow("Touch sounds", "Click when you tap", p.sys(Settings.System.SOUND_EFFECTS_ENABLED)?.let { it == 1 }) {
                        p.putSys(Settings.System.SOUND_EFFECTS_ENABLED, if (it) 1 else 0)
                        runCatching { if (it) am.loadSoundEffects() else am.unloadSoundEffects() }
                    }
                    ToggleRow("Touch vibration", "Short vibration on taps", p.sys(Settings.System.HAPTIC_FEEDBACK_ENABLED)?.let { it == 1 }) { p.putSys(Settings.System.HAPTIC_FEEDBACK_ENABLED, if (it) 1 else 0) }
                    // HioneOS haptics: checked by the haptics helper in SystemUI, the launcher and
                    // Hione Music. Turning it on also turns on Touch vibration, which HiBy ships off.
                    ToggleRow("${Mascot.osName} haptics", "Clicks and ticks in ${Mascot.osName}, the launcher and ${Mascot.musicAppName}", (p.glob("hione_haptics") ?: 1) == 1) {
                        p.putGlob("hione_haptics", if (it) 1 else 0)
                        if (it) p.putSys(Settings.System.HAPTIC_FEEDBACK_ENABLED, 1)
                    }
                    ToggleRow("Screen lock sound", null, p.sys("lockscreen_sounds_enabled")?.let { it == 1 }) { p.putSys("lockscreen_sounds_enabled", if (it) 1 else 0) }
                    ToggleRow("Charging sounds and vibration", null, p.glob("charging_sounds_enabled")?.let { it == 1 }) { p.putGlob("charging_sounds_enabled", if (it) 1 else 0) }
                    ToggleRow("Mono audio", "Plays the same sound in both channels", p.sys("master_mono")?.let { it == 1 }) { p.putSys("master_mono", if (it) 1 else 0) }
                }
            }
        }
    }
    if (ringerPick) ChoiceDialog("Ring mode", listOf(AudioManager.RINGER_MODE_NORMAL to "Sound", AudioManager.RINGER_MODE_VIBRATE to "Vibrate", AudioManager.RINGER_MODE_SILENT to "Silent"),
        am.ringerMode, onPick = {
            ringerPick = false
            if (runCatching { am.ringerMode = it }.isFailure) toast(ctx, "Ring mode was not changed"); refresh++
        }, onDismiss = { ringerPick = false })
}

// ---------------------------------------------------------------- Notifications & DND

@Composable
fun NotificationsPage() {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeTick { refresh++ }
    val p = remember { Prefs(ctx) { refresh++ } }
    AppPicker(
        subtitle = { r -> when (NotifOps.enabled(r.pkg, r.ai.uid)) { true -> "Notifications on"; false -> "Notifications off"; null -> UNKNOWN } },
        refreshKey = refresh,
        header = {
            key(refresh) {
                Section {
                    NavRow("Do Not Disturb", null, Icons.Default.DoNotDisturbOn) { nav.push("dnd") }
                    NavRow("Notification read, reply & control", "Apps with notification access", Icons.Default.NotificationsActive) { nav.push("access/listener") }
                    ToggleRow("Notifications on lock screen", null, p.sec("lock_screen_show_notifications")?.let { it == 1 }) { p.putSec("lock_screen_show_notifications", if (it) 1 else 0) }
                    ToggleRow("Notification dot on app icon", null, p.sec("notification_badging")?.let { it == 1 }) { p.putSec("notification_badging", if (it) 1 else 0) }
                    ToggleRow("Allow notification snoozing", null, p.sec("show_notification_snooze")?.let { it == 1 }) { p.putSec("show_notification_snooze", if (it) 1 else 0) }
                    StockRow("Notification history", "com.android.settings.notification.history.NotificationHistoryActivity")
                }
            }
        }
    ) { nav.push("appnotif/${Uri.encode(it.pkg)}") }
}

@Composable
fun DndPage() {
    val ctx = LocalContext.current
    val nm = remember { ctx.getSystemService(NotificationManager::class.java) }
    val tick = BroadcastTick(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
    var refresh by remember { mutableIntStateOf(0) }
    val filter = remember(tick, refresh) { try { nm.currentInterruptionFilter } catch (_: Throwable) { null } }
    // NotificationManager.setZenMode(int, Uri, String) is @hide; system/SystemUI only (STATUS_BAR_SERVICE).
    fun setZen(mode: Int) {
        if (Hidden.tryCall(nm, "setZenMode", mode, null, "HioneSettings").isFailure) toast(ctx, "Do Not Disturb was not changed")
        refresh++
    }
    PageList {
        item {
            Section {
                ToggleRow("Do Not Disturb", "Silences notifications except what you allow",
                    filter?.let { it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN }) { setZen(if (it) 1 else 0) }
                if (filter != null && filter != NotificationManager.INTERRUPTION_FILTER_ALL) {
                    RadioRow("Priority only", selected = filter == NotificationManager.INTERRUPTION_FILTER_PRIORITY) { setZen(1) }
                    RadioRow("Alarms only", selected = filter == NotificationManager.INTERRUPTION_FILTER_ALARMS) { setZen(3) }
                    RadioRow("Total silence", selected = filter == NotificationManager.INTERRUPTION_FILTER_NONE) { setZen(2) }
                }
            }
        }
        item {
            Section("Not built yet") {
                StockRow("What can interrupt", ".Settings\$ZenModeSettingsActivity")
                StockRow("Schedules", ".Settings\$ZenModeAutomationSettingsActivity")
            }
        }
    }
}

// ---------------------------------------------------------------- Display extras

@Composable
fun DisplayMorePage() {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val p = remember { Prefs(ctx) { refresh++ } }
    val ui = remember { ctx.getSystemService(UiModeManager::class.java) }
    val color = remember { try { ctx.getSystemService("color_display") } catch (_: Throwable) { null } }
    var timeoutPick by remember { mutableStateOf(false) }
    val timeouts = listOf(15_000 to "15 seconds", 30_000 to "30 seconds", 60_000 to "1 minute", 120_000 to "2 minutes",
        300_000 to "5 minutes", 600_000 to "10 minutes", 1_800_000 to "30 minutes")
    PageList {
        item {
            key(refresh) {
                Section {
                    val to = p.sys(Settings.System.SCREEN_OFF_TIMEOUT)
                    NavRow("Screen timeout", timeouts.firstOrNull { it.first == to }?.second ?: to?.let { "${it / 1000} seconds" } ?: UNKNOWN) { timeoutPick = true }
                    ToggleRow("Dark theme", "Dark backgrounds in apps that follow the system", try { ui.nightMode == UiModeManager.MODE_NIGHT_YES } catch (_: Throwable) { null }) {
                        // MODIFY_DAY_NIGHT_MODE (signature|privileged).
                        if (runCatching { ui.nightMode = if (it) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO }.isFailure) toast(ctx, "Dark theme was not changed")
                        refresh++
                    }
                    ToggleRow("Auto-rotate screen", "${Mascot.osName} ships locked to portrait", p.sys(Settings.System.ACCELEROMETER_ROTATION)?.let { it == 1 }) { p.putSys(Settings.System.ACCELEROMETER_ROTATION, if (it) 1 else 0) }
                    ToggleRow("Android adaptive brightness", "${Mascot.osName} has its own ambient light control on the Display & Light page. Turning both on makes them fight.",
                        p.sys(Settings.System.SCREEN_BRIGHTNESS_MODE)?.let { it == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC }) {
                        p.putSys(Settings.System.SCREEN_BRIGHTNESS_MODE, if (it) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                    }
                    // ColorDisplayManager is @hide; CONTROL_DISPLAY_COLOR_TRANSFORMS.
                    ToggleRow("Night Light", "Tints the screen amber", Hidden.call(color, "isNightDisplayActivated") as? Boolean) {
                        if (Hidden.call(color, "setNightDisplayActivated", it) != true) toast(ctx, "Night Light was not changed"); refresh++
                    }
                }
            }
        }
        item {
            Section {
                NavRow("Brightness, display size and text size", "On the Display & Light page", Icons.Default.BrightnessMedium) { openSection(ctx, "display") }
                NavRow("Wallpaper", "Opens the wallpaper picker", Icons.Default.Wallpaper) {
                    try { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Set wallpaper")) } catch (_: Throwable) { toast(ctx, "No wallpaper picker found") }
                }
                StockRow("Screen saver", ".Settings\$DreamSettingsActivity")
            }
        }
    }
    if (timeoutPick) ChoiceDialog("Screen timeout", timeouts, p.sys(Settings.System.SCREEN_OFF_TIMEOUT), onPick = {
        timeoutPick = false; p.putSys(Settings.System.SCREEN_OFF_TIMEOUT, it)
    }, onDismiss = { timeoutPick = false })
}

// ---------------------------------------------------------------- Storage

@Composable
fun StoragePage() {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    val tick = BroadcastTick(Intent.ACTION_MEDIA_MOUNTED, Intent.ACTION_MEDIA_UNMOUNTED, Intent.ACTION_MEDIA_EJECT, Intent.ACTION_MEDIA_REMOVED)
    var refresh by remember { mutableIntStateOf(0) }
    val sm = remember { ctx.getSystemService(StorageManager::class.java) }
    val ssm = remember { ctx.getSystemService(StorageStatsManager::class.java) }
    val vols = remember(tick, refresh) { try { sm.storageVolumes } catch (_: Throwable) { emptyList() } }
    PageList {
        vols.forEach { v ->
            item {
                val total: Long?; val free: Long?
                if (v.isPrimary) {
                    total = try { ssm.getTotalBytes(StorageManager.UUID_DEFAULT) } catch (_: Throwable) { null }
                    free = try { ssm.getFreeBytes(StorageManager.UUID_DEFAULT) } catch (_: Throwable) { null }
                } else {
                    total = v.directory?.totalSpace?.takeIf { it > 0 }
                    free = v.directory?.freeSpace?.takeIf { total != null }
                }
                Section(v.getDescription(ctx)) {
                    InfoRow("State", v.state)
                    InfoRow("Used", if (total != null && free != null) "${Formatter.formatShortFileSize(ctx, total - free)} of ${Formatter.formatShortFileSize(ctx, total)}" else null)
                    InfoRow("Free", free?.let { Formatter.formatShortFileSize(ctx, it) })
                    if (v.isRemovable) {
                        Spacer(Modifier.height(6.dp))
                        val id = Hidden.call(v, "getId") as? String
                        val mounted = v.state == android.os.Environment.MEDIA_MOUNTED || v.state == android.os.Environment.MEDIA_MOUNTED_READ_ONLY
                        // StorageManager.mount/unmount(volId) are @hide; MOUNT_UNMOUNT_FILESYSTEMS.
                        ActionButton(if (mounted) "Eject" else "Mount", modifier = Modifier.fillMaxWidth()) {
                            if (id == null || Hidden.tryCall(sm, if (mounted) "unmount" else "mount", id).isFailure) toast(ctx, "The card was not ${if (mounted) "ejected" else "mounted"}")
                            refresh++
                        }
                    }
                }
            }
        }
        item {
            Section {
                NavRow("Apps", "See what each app uses and clear it", Icons.Default.Apps) { nav.push("apps") }
                NavRow("Browse files", "Opens the Files app", Icons.Default.Folder) {
                    try {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(
                            Uri.parse("content://com.android.externalstorage.documents/root/primary"), "vnd.android.document/root"))
                    } catch (_: Throwable) { toast(ctx, "No file browser found") }
                }
                StockRow("Format or set up the SD card", ".Settings\$StorageDashboardActivity")
            }
        }
    }
}

// ---------------------------------------------------------------- Location

@Composable
fun LocationPage() {
    val ctx = LocalContext.current
    val lm = remember { ctx.getSystemService(LocationManager::class.java) }
    val tick = BroadcastTick(LocationManager.MODE_CHANGED_ACTION)
    var refresh by remember { mutableIntStateOf(0) }
    val p = remember { Prefs(ctx) { refresh++ } }
    PageList {
        item {
            key(tick, refresh) {
                Section {
                    // LocationManager.setLocationEnabledForUser is @SystemApi (WRITE_SECURE_SETTINGS).
                    ToggleRow("Use location", "Lets apps with permission find this device", try { lm.isLocationEnabled } catch (_: Throwable) { null }) {
                        if (Hidden.tryCall(lm, "setLocationEnabledForUser", it, Process.myUserHandle()).isFailure) toast(ctx, "Location was not changed"); refresh++
                    }
                    NavRow("App location permissions", "Opens the system permission manager") {
                        try {
                            ctx.startActivity(Intent("android.intent.action.MANAGE_PERMISSION_APPS").putExtra(Intent.EXTRA_PERMISSION_GROUP_NAME, "android.permission-group.LOCATION"))
                        } catch (_: Throwable) { toast(ctx, "Permission manager not available") }
                    }
                }
            }
        }
        item {
            key(refresh) {
                Section("Scanning") {
                    ToggleRow("Wi-Fi scanning", "Apps can scan for Wi-Fi networks even when Wi-Fi is off", p.glob("wifi_scan_always_enabled")?.let { it == 1 }) { p.putGlob("wifi_scan_always_enabled", if (it) 1 else 0) }
                    ToggleRow("Bluetooth scanning", "Apps can scan for Bluetooth devices even when Bluetooth is off", p.glob("ble_scan_always_enabled")?.let { it == 1 }) { p.putGlob("ble_scan_always_enabled", if (it) 1 else 0) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Security & privacy

@Composable
fun SecurityPage() {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeTick { refresh++ }
    val p = remember { Prefs(ctx) { refresh++ } }
    PageList {
        item {
            key(refresh) {
                Section("Device security") {
                    NavRow("Screen lock", com.hione.settings.lock.HioneLock.typeName(com.hione.settings.lock.HioneLock.credentialType(ctx)).let {
                        if (it == "None") "None. Set a PIN or password" else "$it. Change it, remove it, or choose when it is asked"
                    }, Icons.Default.Lock) { nav.push("screenlock") }
                    InfoRow("Encryption", Hidden.sysprop("ro.crypto.state")?.let { s -> s.replaceFirstChar { it.uppercase() } + (Hidden.sysprop("ro.crypto.type")?.let { " ($it)" } ?: "") })
                    InfoRow("Security update", android.os.Build.VERSION.SECURITY_PATCH)
                }
            }
        }
        item {
            key(refresh) {
                Section("Apps and permissions") {
                    NavRow("Permission manager", "See which apps can use the mic, location and more", Icons.Default.Shield) {
                        try { ctx.startActivity(Intent("android.intent.action.MANAGE_PERMISSIONS")) } catch (_: Throwable) { toast(ctx, "Permission manager not available") }
                    }
                    NavRow("Install unknown apps", null) { nav.push("access/install") }
                    NavRow("Device admin apps", null) { nav.push("device_admins") }
                    NavRow("Special app access", null) { nav.push("special") }
                    ToggleRow("App pinning", "Keep one app on screen until you unpin it", p.sec("lock_to_app_enabled")?.let { it == 1 }) { p.putSec("lock_to_app_enabled", if (it) 1 else 0) }
                    ToggleRow("Show passwords", "Show characters briefly as you type", p.sys("show_password")?.let { it == 1 }) { p.putSys("show_password", if (it) 1 else 0) }
                }
            }
        }
        item {
            Section("Not built yet") {
                StockRow("Trusted certificates", ".Settings\$TrustedCredentialsSettingsActivity")
                StockRow("Install a certificate", ".security.CredentialStorage", action = "com.android.credentials.INSTALL")
                StockRow("SIM lock", ".Settings\$IccLockSettingsActivity")
            }
        }
    }
}

// ---------------------------------------------------------------- Accessibility

object A11yOps {
    fun enabled(ctx: Context): Set<String> =
        (Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
            .split(':').filter { it.isNotBlank() }.toSet()

    /** Same storage stock Settings writes; the list keeps every other enabled service untouched. */
    fun set(ctx: Context, flat: String, on: Boolean): Boolean {
        val cn = ComponentName.unflattenFromString(flat)
        val cur = enabled(ctx).filter { ComponentName.unflattenFromString(it) != cn }.toMutableList()
        if (on) cur.add(flat)
        return runCatching {
            Settings.Secure.putString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, cur.joinToString(":"))
            if (cur.isNotEmpty()) Settings.Secure.putInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        }.isSuccess
    }

    fun isOn(ctx: Context, flat: String): Boolean {
        val cn = ComponentName.unflattenFromString(flat)
        return enabled(ctx).any { ComponentName.unflattenFromString(it) == cn }
    }
}

@Composable
fun AccessibilityPage() {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeTick { refresh++ }
    val p = remember { Prefs(ctx) { refresh++ } }
    val services = remember(refresh) {
        try { ctx.getSystemService(AccessibilityManager::class.java).installedAccessibilityServiceList } catch (_: Throwable) { emptyList() }
    }
    var daltonPick by remember { mutableStateOf(false) }
    PageList {
        item {
            key(refresh) {
                Section("Services", note = if (services.isEmpty()) "No accessibility services are installed." else null) {
                    services.forEach { s ->
                        val flat = ComponentName(s.resolveInfo.serviceInfo.packageName, s.resolveInfo.serviceInfo.name).flattenToString()
                        NavRow(s.resolveInfo.loadLabel(ctx.packageManager).toString(), if (A11yOps.isOn(ctx, flat)) "On" else "Off") {
                            nav.push("a11y/${Uri.encode(flat)}")
                        }
                    }
                }
            }
        }
        item {
            key(refresh) {
                Section("Display") {
                    NavRow("Text and display size", "On the Display & Light page", Icons.Default.TextFields) { openSection(ctx, "display") }
                    ToggleRow("Color inversion", "Swaps light and dark colors", p.sec("accessibility_display_inversion_enabled")?.let { it == 1 }) { p.putSec("accessibility_display_inversion_enabled", if (it) 1 else 0) }
                    ToggleRow("Color correction", "Adjusts colors for color blindness", p.sec("accessibility_display_daltonizer_enabled")?.let { it == 1 }) { p.putSec("accessibility_display_daltonizer_enabled", if (it) 1 else 0) }
                    NavRow("Correction mode", when (p.sec("accessibility_display_daltonizer")) { 11 -> "Red-green (deuteranomaly)"; 12 -> "Red-green (protanomaly)"; 13 -> "Blue-yellow"; 0 -> "Grayscale"; else -> "Default" }) { daltonPick = true }
                    ToggleRow("High contrast text", null, p.sec("high_text_contrast_enabled")?.let { it == 1 }) { p.putSec("high_text_contrast_enabled", if (it) 1 else 0) }
                    ToggleRow("Extra dim", "Dims the screen below the lowest brightness", p.sec("reduce_bright_colors_activated")?.let { it == 1 }) { p.putSec("reduce_bright_colors_activated", if (it) 1 else 0) }
                    ToggleRow("Remove animations", "Turns off window and transition animations",
                        // Unset means the framework default of 1.0, so "not removed".
                        (try { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE) } catch (_: Throwable) { 1f }) == 0f) { off ->
                        val v = if (off) 0f else 1f
                        val ok = runCatching {
                            listOf(Settings.Global.WINDOW_ANIMATION_SCALE, Settings.Global.TRANSITION_ANIMATION_SCALE, Settings.Global.ANIMATOR_DURATION_SCALE)
                                .forEach { Settings.Global.putFloat(ctx.contentResolver, it, v) }
                        }.isSuccess
                        if (!ok) toast(ctx, "Animations were not changed"); refresh++
                    }
                }
            }
        }
        item {
            key(refresh) {
                Section("Interaction and audio") {
                    ToggleRow("Magnification", "Triple-tap the screen to zoom", p.sec("accessibility_display_magnification_enabled")?.let { it == 1 }) { p.putSec("accessibility_display_magnification_enabled", if (it) 1 else 0) }
                    ToggleRow("Captions", "Show captions where apps support them", p.sec("accessibility_captioning_enabled")?.let { it == 1 }) { p.putSec("accessibility_captioning_enabled", if (it) 1 else 0) }
                    ToggleRow("Mono audio", "Plays the same sound in both channels", p.sys("master_mono")?.let { it == 1 }) { p.putSys("master_mono", if (it) 1 else 0) }
                    StockRow("Text-to-speech output", ".Settings\$TextToSpeechSettingsActivity")
                }
            }
        }
    }
    if (daltonPick) ChoiceDialog("Correction mode", listOf(11 to "Red-green (deuteranomaly)", 12 to "Red-green (protanomaly)", 13 to "Blue-yellow (tritanomaly)", 0 to "Grayscale"),
        p.sec("accessibility_display_daltonizer"), onPick = { daltonPick = false; p.putSec("accessibility_display_daltonizer", it) }, onDismiss = { daltonPick = false })
}

@Composable
fun A11yServicePage(flat: String) {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var ask by remember { mutableStateOf(false) }
    val cn = remember(flat) { ComponentName.unflattenFromString(flat) }
    val info = remember(flat) {
        try {
            ctx.getSystemService(AccessibilityManager::class.java).installedAccessibilityServiceList
                .firstOrNull { it.resolveInfo.serviceInfo.packageName == cn?.packageName && it.resolveInfo.serviceInfo.name == cn.className }
        } catch (_: Throwable) { null }
    }
    if (cn == null || info == null) { PageList { item { Section("Service not found") { BodyText(flat) } } }; return }
    val label = info.resolveInfo.loadLabel(ctx.packageManager).toString()
    val on = remember(refresh) { A11yOps.isOn(ctx, flat) }
    val isHione = cn.packageName.startsWith("com.hione.")
    PageList {
        item {
            Section(label) {
                ToggleRow("Use $label", null, on) {
                    if (it) ask = true else { if (!A11yOps.set(ctx, flat, false)) toast(ctx, "Service was not changed"); refresh++ }
                }
                if (isHione) BodyText("This is part of ${Mascot.osName}. Turning it off can break the notification shade and gestures.")
                info.loadDescription(ctx.packageManager)?.let { Spacer(Modifier.height(6.dp)); BodyText(it) }
                info.settingsActivityName?.let { act ->
                    NavRow("Settings", null) {
                        try { ctx.startActivity(Intent().setComponent(ComponentName(cn.packageName, act))) } catch (_: Throwable) { toast(ctx, "Settings not available") }
                    }
                }
            }
        }
    }
    if (ask) HioneAlert("Allow $label to have full control of your device?",
        "It can read everything on screen, show content over other apps, and track and perform actions on your behalf. Only allow apps you trust.",
        "Allow", onConfirm = { ask = false; if (!A11yOps.set(ctx, flat, true)) toast(ctx, "Service was not changed"); refresh++ }, onDismiss = { ask = false })
}
