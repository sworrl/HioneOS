package com.hione.settings.pages

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.role.RoleManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.LocaleList
import android.os.PowerManager
import android.os.Process
import android.os.storage.StorageManager
import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.hione.settings.*
import com.hione.settings.sys.Hidden
import com.hione.settings.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** App management calls. All are what stock Settings' InstalledAppDetails does, minus uid 1000. */
object AppOps {
    fun info(ctx: Context, pkg: String): ApplicationInfo? = try {
        ctx.packageManager.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_UNINSTALLED_PACKAGES)
    } catch (_: Throwable) { null }

    fun label(ctx: Context, ai: ApplicationInfo): String = try { ctx.packageManager.getApplicationLabel(ai).toString() } catch (_: Throwable) { ai.packageName }

    fun icon(ctx: Context, ai: ApplicationInfo, px: Int = 96): ImageBitmap? = try {
        ctx.packageManager.getApplicationIcon(ai).toBitmap(px, px).asImageBitmap()
    } catch (_: Throwable) { null }

    fun isSystem(ai: ApplicationInfo) = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0

    /** ActivityManager.forceStopPackage is @hide; needs FORCE_STOP_PACKAGES. */
    fun forceStop(ctx: Context, pkg: String): Boolean =
        Hidden.tryCall(ctx.getSystemService(ActivityManager::class.java), "forceStopPackage", pkg).isSuccess

    /** ActivityManager.clearApplicationUserData(String, IPackageDataObserver) is @hide; CLEAR_APP_USER_DATA. */
    fun clearData(ctx: Context, pkg: String): Boolean =
        Hidden.tryCall(ctx.getSystemService(ActivityManager::class.java), "clearApplicationUserData", pkg, null).getOrNull() as? Boolean ?: false

    /** PackageManager.deleteApplicationCacheFiles is @hide; DELETE_CACHE_FILES. */
    fun clearCache(ctx: Context, pkg: String): Boolean =
        Hidden.tryCall(ctx.packageManager, "deleteApplicationCacheFiles", pkg, null).isSuccess

    fun setEnabled(ctx: Context, pkg: String, enabled: Boolean): Boolean = try {
        ctx.packageManager.setApplicationEnabledSetting(
            pkg,
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT else PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            0
        ); true
    } catch (_: Throwable) { false }

    data class Sizes(val app: Long, val data: Long, val cache: Long)
    fun sizes(ctx: Context, ai: ApplicationInfo): Sizes? = try {
        val ssm = ctx.getSystemService(StorageStatsManager::class.java)
        val s = ssm.queryStatsForPackage(ai.storageUuid, ai.packageName, Process.myUserHandle())
        Sizes(s.appBytes, s.dataBytes, s.cacheBytes)
    } catch (_: Throwable) { null }

    fun requests(ctx: Context, pkg: String, perm: String): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS).requestedPermissions?.contains(perm) == true
    } catch (_: Throwable) { false }

    /** Battery optimization allow list: PowerExemptionManager (@SystemApi, DEVICE_POWER). */
    fun ignoringBatteryOpt(ctx: Context, pkg: String): Boolean? = try {
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(pkg)
    } catch (_: Throwable) { null }

    fun setIgnoreBatteryOpt(ctx: Context, pkg: String, allow: Boolean): Boolean {
        val pem = try { ctx.getSystemService(Class.forName("android.os.PowerExemptionManager")) } catch (_: Throwable) { null }
        val r = Hidden.tryCall(pem, if (allow) "addToPermanentAllowList" else "removeFromPermanentAllowList", pkg)
        if (r.isSuccess) return true
        // Older path, same server call.
        val idle = Hidden.serviceInterface("deviceidle", "android.os.IDeviceIdleController")
        return Hidden.tryCall(idle, if (allow) "addPowerSaveWhitelistApp" else "removePowerSaveWhitelistApp", pkg).isSuccess
    }

    /** Metered background data: NetworkPolicyManager uid policy POLICY_ALLOW_METERED_BACKGROUND (4). */
    fun unrestrictedData(ctx: Context, uid: Int): Boolean? {
        val npm = try { ctx.getSystemService(Class.forName("android.net.NetworkPolicyManager")) } catch (_: Throwable) { null }
        return (Hidden.call(npm, "getUidPolicy", uid) as? Int)?.let { it and 4 != 0 }
    }
    fun setUnrestrictedData(ctx: Context, uid: Int, on: Boolean): Boolean {
        val npm = try { ctx.getSystemService(Class.forName("android.net.NetworkPolicyManager")) } catch (_: Throwable) { null }
        return Hidden.tryCall(npm, if (on) "addUidPolicy" else "removeUidPolicy", uid, 4).isSuccess
    }

    fun linkHandlingAllowed(ctx: Context, pkg: String): Boolean? = try {
        val dvm = ctx.getSystemService(android.content.pm.verify.domain.DomainVerificationManager::class.java)
        dvm.getDomainVerificationUserState(pkg)?.isLinkHandlingAllowed
    } catch (_: Throwable) { null }
    fun setLinkHandling(ctx: Context, pkg: String, allowed: Boolean): Boolean = try {
        val dvm = ctx.getSystemService(android.content.pm.verify.domain.DomainVerificationManager::class.java)
        Hidden.tryCall(dvm, "setDomainVerificationLinkHandlingAllowed", pkg, allowed).isSuccess
    } catch (_: Throwable) { false }

    fun fmt(ctx: Context, b: Long?) = b?.let { Formatter.formatShortFileSize(ctx, it) }
}

/** INotificationManager calls stock Settings makes; gated on STATUS_BAR_SERVICE (signature). */
object NotifOps {
    private fun svc(): Any? = Hidden.callStatic("android.app.NotificationManager", "getService")

    fun enabled(pkg: String, uid: Int): Boolean? = Hidden.call(svc(), "areNotificationsEnabledForPackage", pkg, uid) as? Boolean
    fun setEnabled(pkg: String, uid: Int, on: Boolean): Boolean =
        Hidden.tryCall(svc(), "setNotificationsEnabledForPackage", pkg, uid, on).isSuccess

    fun badges(pkg: String, uid: Int): Boolean? = Hidden.call(svc(), "canShowBadge", pkg, uid) as? Boolean
    fun setBadges(pkg: String, uid: Int, on: Boolean) = Hidden.tryCall(svc(), "setShowBadge", pkg, uid, on).isSuccess

    @Suppress("UNCHECKED_CAST")
    fun channels(pkg: String, uid: Int): List<NotificationChannel>? {
        val slice = Hidden.call(svc(), "getNotificationChannelsForPackage", pkg, uid, false) ?: return null
        return (Hidden.call(slice, "getList") as? List<NotificationChannel>)
    }

    fun channel(pkg: String, uid: Int, id: String): NotificationChannel? =
        (Hidden.call(svc(), "getNotificationChannelForPackage", pkg, uid, id, null, false) as? NotificationChannel)
            ?: channels(pkg, uid)?.firstOrNull { it.id == id }

    /** Stock locks the importance field so the app cannot change it back. USER_LOCKED_IMPORTANCE = 4. */
    fun update(pkg: String, uid: Int, ch: NotificationChannel): Boolean {
        Hidden.call(ch, "lockFields", 4)
        return Hidden.tryCall(svc(), "updateNotificationChannelForPackage", pkg, uid, ch).isSuccess
    }
}

@Composable
fun AppIcon(bmp: ImageBitmap?, size: Int = 36) {
    if (bmp != null) Image(bmp, contentDescription = null, modifier = Modifier.size(size.dp).clip(RoundedCornerShape(8.dp)))
    else Box(Modifier.size(size.dp).clip(RoundedCornerShape(8.dp)).background(HioneSurface2))
}

data class AppRow(val pkg: String, val label: String, val system: Boolean, val enabled: Boolean, val ai: ApplicationInfo)

fun loadApps(ctx: Context): List<AppRow> {
    val pm = ctx.packageManager
    return try {
        pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS).map {
            AppRow(it.packageName, AppOps.label(ctx, it), AppOps.isSystem(it), it.enabled, it)
        }.sortedBy { it.label.lowercase() }
    } catch (_: Throwable) { emptyList() }
}

/** Searchable app list. [subtitle] and [onPick] let special-access pages reuse it. */
@Composable
fun AppPicker(
    filter: (AppRow) -> Boolean = { true },
    subtitle: (AppRow) -> String? = { null },
    header: (@Composable () -> Unit)? = null,
    defaultShowSystem: Boolean = false,
    refreshKey: Any? = null,
    onPick: (AppRow) -> Unit
) {
    val ctx = LocalContext.current
    var all by remember { mutableStateOf<List<AppRow>?>(null) }
    var q by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(defaultShowSystem) }
    LaunchedEffect(refreshKey) { all = withContext(Dispatchers.IO) { loadApps(ctx).filter(filter) } }
    val rows = (all ?: emptyList()).filter { (showSystem || !it.system) && (q.isBlank() || it.label.contains(q, true) || it.pkg.contains(q, true)) }
    PageList {
        if (header != null) item { header() }
        item {
            Column {
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(HioneSurface1)
                        .border(1.dp, HioneTeal.copy(alpha = 0.35f), RoundedCornerShape(24.dp)).padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    BasicTextField(q, { q = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 13.5.sp),
                        decorationBox = { inner -> if (q.isEmpty()) Text("Search apps", color = HioneMuted, fontSize = 13.sp); inner() })
                }
                ToggleRow("Show system apps", null, showSystem) { showSystem = it }
            }
        }
        if (all == null) item { BodyText("Loading apps") }
        items(rows, key = { it.pkg }) { r -> AppListRow(r, subtitle(r)) { onPick(r) } }
    }
}

@Composable
fun AppListRow(r: AppRow, subtitle: String?, onClick: () -> Unit) {
    val ctx = LocalContext.current
    var icon by remember(r.pkg) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(r.pkg) { icon = withContext(Dispatchers.IO) { AppOps.icon(ctx, r.ai) } }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(HioneSurface2).clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(icon)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(r.label, color = if (r.enabled) Color.White else HioneMuted, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle ?: if (!r.enabled) "Disabled" else r.pkg, color = HioneMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun AppListPage() {
    val nav = LocalNav.current
    AppPicker(header = {
        Section {
            NavRow("Default apps", "Home, browser, assistant and more", Icons.Default.AppSettingsAlt) { nav.push("default_apps") }
            NavRow("Special app access", "Overlays, all files, usage access and more", Icons.Default.Security) { nav.push("special") }
        }
    }) { nav.push("app/${Uri.encode(it.pkg)}") }
}

@Composable
fun AppInfoPage(pkg: String) {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    var refresh by remember { mutableIntStateOf(0) }
    val ai = remember(pkg, refresh) { AppOps.info(ctx, pkg) }
    if (ai == null) {
        PageList { item { Section("App not found") { BodyText("$pkg is not installed for this user.") } } }
        return
    }
    val pm = ctx.packageManager
    val pi = remember(pkg, refresh) { try { pm.getPackageInfo(pkg, 0) } catch (_: Throwable) { null } }
    val label = remember(ai) { AppOps.label(ctx, ai) }
    val icon = remember(ai) { AppOps.icon(ctx, ai, 128) }
    var sizes by remember { mutableStateOf<AppOps.Sizes?>(null) }
    var sizesTried by remember { mutableStateOf(false) }
    LaunchedEffect(pkg, refresh) { sizes = withContext(Dispatchers.IO) { AppOps.sizes(ctx, ai) }; sizesTried = true }
    var confirm by remember { mutableStateOf<String?>(null) }
    val launch = remember(pkg) { pm.getLaunchIntentForPackage(pkg) }
    val notifOn = remember(pkg, refresh) { NotifOps.enabled(pkg, ai.uid) }

    PageList {
        item {
            Column(Modifier.fillMaxWidth().hioneHeroCard().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                AppIcon(icon, 64)
                Spacer(Modifier.height(8.dp))
                Text(label, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(pkg, color = HioneMuted, fontSize = 11.sp)
                if (!ai.enabled) Text("Disabled", color = HioneGold, fontSize = 11.sp)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (launch != null) ActionButton("Open", modifier = Modifier.weight(1f)) {
                        try { ctx.startActivity(launch) } catch (_: Throwable) { toast(ctx, "Could not open $label") }
                    }
                    ActionButton("Force stop", danger = true, modifier = Modifier.weight(1f)) { confirm = "stop" }
                    if (AppOps.isSystem(ai)) ActionButton(if (ai.enabled) "Disable" else "Enable", modifier = Modifier.weight(1f)) {
                        if (ai.enabled) confirm = "disable" else { if (!AppOps.setEnabled(ctx, pkg, true)) toast(ctx, "Could not enable $label"); refresh++ }
                    } else ActionButton("Uninstall", danger = true, modifier = Modifier.weight(1f)) {
                        try { ctx.startActivity(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pkg, null))) } catch (_: Throwable) { toast(ctx, "No uninstaller found") }
                    }
                }
            }
        }
        item {
            Section {
                NavRow("Notifications", when (notifOn) { true -> "Allowed"; false -> "Off"; null -> UNKNOWN }, Icons.Default.Notifications) { nav.push("appnotif/${Uri.encode(pkg)}") }
                NavRow("Permissions", "Opens the system permission manager", Icons.Default.Shield) {
                    try {
                        ctx.startActivity(Intent("android.intent.action.MANAGE_APP_PERMISSIONS").putExtra(Intent.EXTRA_PACKAGE_NAME, pkg))
                    } catch (_: Throwable) { toast(ctx, "Permission manager not available") }
                }
                NavRow("App language", "Pick a language just for this app", Icons.Default.Language) { nav.push("applocale/${Uri.encode(pkg)}") }
            }
        }
        item {
            Section("Storage", note = if (sizesTried && sizes == null) "Sizes could not be read." else null) {
                InfoRow("App", AppOps.fmt(ctx, sizes?.app))
                InfoRow("User data", AppOps.fmt(ctx, sizes?.data))
                InfoRow("Cache", AppOps.fmt(ctx, sizes?.cache))
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("Clear storage", danger = true, modifier = Modifier.weight(1f)) { confirm = "data" }
                    ActionButton("Clear cache", modifier = Modifier.weight(1f)) {
                        if (!AppOps.clearCache(ctx, pkg)) toast(ctx, "Cache was not cleared")
                        refresh++
                    }
                }
            }
        }
        item {
            key(refresh) {
                Section("Battery & data") {
                    ToggleRow("Unrestricted battery", "Lets the app run in the background without battery optimization", AppOps.ignoringBatteryOpt(ctx, pkg)) {
                        if (!AppOps.setIgnoreBatteryOpt(ctx, pkg, it)) toast(ctx, "Battery setting was not changed")
                        refresh++
                    }
                    ToggleRow("Unrestricted mobile data", "Lets the app use mobile data in the background when Data Saver is on", AppOps.unrestrictedData(ctx, ai.uid)) {
                        if (!AppOps.setUnrestrictedData(ctx, ai.uid, it)) toast(ctx, "Data setting was not changed")
                        refresh++
                    }
                    ToggleRow("Open supported links", "Links this app can handle open in the app", AppOps.linkHandlingAllowed(ctx, pkg)) {
                        if (!AppOps.setLinkHandling(ctx, pkg, it)) toast(ctx, "Link setting was not changed")
                        refresh++
                    }
                }
            }
        }
        item {
            key(refresh) {
                val kinds = AccessKind.values().filter { k -> k.permission != null && AppOps.requests(ctx, pkg, k.permission) }
                if (kinds.isNotEmpty()) Section("Special access") {
                    kinds.forEach { k ->
                        ToggleRow(k.title, k.summary, AccessOps.allowed(ctx, k, pkg, ai.uid)) {
                            if (!AccessOps.set(ctx, k, pkg, ai.uid, it)) toast(ctx, "${k.title} was not changed")
                            refresh++
                        }
                    }
                }
            }
        }
        item {
            Section("Details") {
                InfoRow("Version", pi?.versionName)
                InfoRow("Version code", pi?.longVersionCode?.toString())
                InfoRow("Target SDK", ai.targetSdkVersion.toString())
                InfoRow("Installed", pi?.firstInstallTime?.takeIf { it > 0 }?.let { DateFormat.getDateInstance().format(Date(it)) })
                InfoRow("Updated", pi?.lastUpdateTime?.takeIf { it > 0 }?.let { DateFormat.getDateInstance().format(Date(it)) })
                InfoRow("Installed by", try { pm.getInstallSourceInfo(pkg).installingPackageName } catch (_: Throwable) { null } ?: if (AppOps.isSystem(ai)) "System image" else null)
                InfoRow("UID", ai.uid.toString())
            }
        }
    }

    when (confirm) {
        "stop" -> HioneAlert("Force stop?", "If you force stop an app, it may misbehave until it is opened again.", "Force stop", danger = true,
            onConfirm = { confirm = null; if (!AppOps.forceStop(ctx, pkg)) toast(ctx, "$label was not stopped") }, onDismiss = { confirm = null })
        "disable" -> HioneAlert("Disable $label?", "Other apps and parts of the system that rely on it may stop working.", "Disable", danger = true,
            onConfirm = { confirm = null; if (!AppOps.setEnabled(ctx, pkg, false)) toast(ctx, "$label was not disabled"); refresh++ }, onDismiss = { confirm = null })
        "data" -> HioneAlert("Clear app storage?", "All of this app's data, accounts, files and settings are deleted.", "Delete", danger = true,
            onConfirm = { confirm = null; if (!AppOps.clearData(ctx, pkg)) toast(ctx, "Storage was not cleared"); refresh++ }, onDismiss = { confirm = null })
    }
}

private fun importanceLabel(i: Int) = when (i) {
    NotificationManager.IMPORTANCE_NONE -> "Off"
    NotificationManager.IMPORTANCE_MIN -> "Silent, minimized"
    NotificationManager.IMPORTANCE_LOW -> "Silent"
    NotificationManager.IMPORTANCE_DEFAULT -> "Default, makes sound"
    NotificationManager.IMPORTANCE_HIGH, NotificationManager.IMPORTANCE_MAX -> "Pops on screen"
    else -> "App decides"
}

@Composable
fun AppNotificationsPage(pkg: String) {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    val ai = remember(pkg) { AppOps.info(ctx, pkg) }
    if (ai == null) { PageList { item { Section("App not found") { BodyText(pkg) } } }; return }
    var refresh by remember { mutableIntStateOf(0) }
    val on = remember(refresh) { NotifOps.enabled(pkg, ai.uid) }
    val badges = remember(refresh) { NotifOps.badges(pkg, ai.uid) }
    val channels = remember(refresh) { NotifOps.channels(pkg, ai.uid) }
    PageList {
        item {
            Section(AppOps.label(ctx, ai)) {
                ToggleRow("Allow notifications", null, on) {
                    if (!NotifOps.setEnabled(pkg, ai.uid, it)) toast(ctx, "Notifications were not changed")
                    refresh++
                }
                ToggleRow("Notification dot on app icon", null, badges) {
                    if (!NotifOps.setBadges(pkg, ai.uid, it)) toast(ctx, "Setting was not changed")
                    refresh++
                }
            }
        }
        item {
            Section("Categories", note = when {
                channels == null -> "Categories could not be read."
                channels.isEmpty() -> "This app has not created any notification categories."
                else -> null
            }) {
                channels?.forEach { ch ->
                    NavRow(ch.name?.toString() ?: ch.id, importanceLabel(ch.importance)) { nav.push("channel/${Uri.encode(pkg)}/${Uri.encode(ch.id)}") }
                }
            }
        }
    }
}

@Composable
fun ChannelPage(pkg: String, id: String) {
    val ctx = LocalContext.current
    val ai = remember(pkg) { AppOps.info(ctx, pkg) }
    var refresh by remember { mutableIntStateOf(0) }
    val ch = remember(refresh) { ai?.let { NotifOps.channel(pkg, it.uid, id) } }
    if (ai == null || ch == null) { PageList { item { Section("Category not found") { BodyText("$pkg / $id") } } }; return }
    val levels = listOf(
        NotificationManager.IMPORTANCE_HIGH, NotificationManager.IMPORTANCE_DEFAULT,
        NotificationManager.IMPORTANCE_LOW, NotificationManager.IMPORTANCE_MIN, NotificationManager.IMPORTANCE_NONE
    )
    PageList {
        item {
            Section(ch.name?.toString() ?: id, note = ch.description) {
                levels.forEach { lvl ->
                    RadioRow(importanceLabel(lvl), selected = ch.importance == lvl) {
                        ch.importance = lvl
                        if (!NotifOps.update(pkg, ai.uid, ch)) toast(ctx, "Category was not changed")
                        refresh++
                    }
                }
            }
        }
        item {
            Section {
                InfoRow("App", AppOps.label(ctx, ai))
                InfoRow("Category ID", ch.id)
                InfoRow("Sound", ch.sound?.toString() ?: "None")
                InfoRow("Vibration", if (ch.shouldVibrate()) "On" else "Off")
            }
        }
    }
}

@Composable
fun AppLocalePage(pkg: String) {
    val ctx = LocalContext.current
    val lm = remember { ctx.getSystemService(android.app.LocaleManager::class.java) }
    var refresh by remember { mutableIntStateOf(0) }
    val current = remember(refresh) { (Hidden.call(lm, "getApplicationLocales", pkg) as? LocaleList)?.takeIf { !it.isEmpty }?.get(0) }
    val locales = remember { LocaleOps.supported(ctx) }
    val label = remember(pkg) { AppOps.info(ctx, pkg)?.let { AppOps.label(ctx, it) } ?: pkg }
    PageList {
        item {
            Section(label, note = "Apps that do not include a language keep using the system language.") {
                RadioRow("System default", selected = current == null) {
                    if (Hidden.tryCall(lm, "setApplicationLocales", pkg, LocaleList.getEmptyLocaleList()).isFailure) toast(ctx, "Language was not changed")
                    refresh++
                }
            }
        }
        items(locales) { loc ->
            RadioRow(loc.getDisplayName(loc).replaceFirstChar { it.uppercase() }, loc.getDisplayName(java.util.Locale.getDefault()), selected = current?.toLanguageTag() == loc.toLanguageTag()) {
                if (Hidden.tryCall(lm, "setApplicationLocales", pkg, LocaleList(loc)).isFailure) toast(ctx, "Language was not changed")
                refresh++
            }
        }
    }
}

/**
 * Default apps. The choosers themselves belong to PermissionController (stock Settings opens
 * the same activity), so each row hands off to it. Reading holders needs MANAGE_ROLE_HOLDERS.
 */
@Composable
fun DefaultAppsPage() {
    val ctx = LocalContext.current
    val rm = remember { ctx.getSystemService(RoleManager::class.java) }
    val roles = listOf(
        RoleManager.ROLE_HOME to "Home app",
        RoleManager.ROLE_BROWSER to "Browser app",
        RoleManager.ROLE_ASSISTANT to "Digital assistant app",
        RoleManager.ROLE_SMS to "SMS app",
        RoleManager.ROLE_DIALER to "Phone app",
        RoleManager.ROLE_CALL_SCREENING to "Caller ID and spam app",
    )
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeTick { tick++ }
    PageList {
        item {
            Section(note = "Choosing an app opens the system role picker.") {
                roles.filter { (r, _) -> try { rm.isRoleAvailable(r) } catch (_: Throwable) { false } }.forEach { (role, title) ->
                    @Suppress("UNCHECKED_CAST")
                    val holders = remember(tick) { Hidden.call(rm, "getRoleHolders", role) as? List<String> }
                    val holderLabel = when {
                        holders == null -> UNKNOWN
                        holders.isEmpty() -> "None"
                        else -> holders.joinToString { Hidden.appLabel(ctx, it) }
                    }
                    NavRow(title, holderLabel) {
                        try {
                            ctx.startActivity(Intent("android.intent.action.MANAGE_DEFAULT_APP").putExtra("android.intent.extra.ROLE_NAME", role))
                        } catch (_: Throwable) { toast(ctx, "Role picker not available") }
                    }
                }
            }
        }
    }
}

/** Calls [onResume] each time the hosting activity resumes, e.g. after a picker returns. */
@Composable
fun LifecycleResumeTick(onResume: () -> Unit) {
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) onResume() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
}

