package com.hione.settings.pages

import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.service.notification.NotificationListenerService
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hione.settings.sys.Hidden
import com.hione.settings.ui.*

/**
 * "Special app access" kinds. Most are app-ops backed by a permission the app must request;
 * the op string and whether stock Settings writes it per uid or per package are taken from the
 * stock implementation so the result is identical.
 */
enum class AccessKind(
    val key: String,
    val title: String,
    val summary: String,
    val permission: String?,
    val op: String?,
    val perUid: Boolean = false
) {
    OVERLAY("overlay", "Display over other apps", "Lets the app draw on top of other apps", "android.permission.SYSTEM_ALERT_WINDOW", "android:system_alert_window"),
    WRITE("write", "Modify system settings", "Lets the app change system settings", "android.permission.WRITE_SETTINGS", "android:write_settings"),
    INSTALL("install", "Install unknown apps", "Lets the app install other apps", "android.permission.REQUEST_INSTALL_PACKAGES", "android:request_install_packages"),
    FILES("files", "All files access", "Lets the app read and change every file on storage", "android.permission.MANAGE_EXTERNAL_STORAGE", "android:manage_external_storage", perUid = true),
    USAGE("usage", "Usage access", "Lets the app see which apps you use and how often", "android.permission.PACKAGE_USAGE_STATS", "android:get_usage_stats"),
    ALARMS("alarms", "Alarms & reminders", "Lets the app set exact alarms", "android.permission.SCHEDULE_EXACT_ALARM", "android:schedule_exact_alarm", perUid = true),
    MEDIA("media", "Media management", "Lets the app edit or delete media files without asking", "android.permission.MANAGE_MEDIA", "android:manage_media", perUid = true),
    PIP("pip", "Picture-in-picture", "Lets the app keep playing video in a small window", null, "android:picture_in_picture"),
    SCREENON("screenon", "Turn screen on", "Lets the app wake the screen", "android.permission.TURN_SCREEN_ON", "android:turn_screen_on"),
    FULLSCREEN("fullscreen", "Full screen notifications", "Lets the app show full screen alerts", "android.permission.USE_FULL_SCREEN_INTENT", "android:use_full_screen_intent"),
    LONGJOBS("longjobs", "Long background tasks", "Lets the app run long tasks in the background", "android.permission.RUN_USER_INITIATED_JOBS", "android:run_user_initiated_jobs"),
    DND("dnd", "Do Not Disturb access", "Lets the app turn Do Not Disturb on and off", "android.permission.ACCESS_NOTIFICATION_POLICY", null),
    BATTERY("battery", "Unrestricted battery", "Lets the app skip battery optimization", null, null);

    companion object { fun of(key: String) = values().firstOrNull { it.key == key } }
}

object AccessOps {
    private fun appOps(ctx: Context) = ctx.getSystemService(AppOpsManager::class.java)

    /** null = could not be read. */
    fun allowed(ctx: Context, k: AccessKind, pkg: String, uid: Int): Boolean? {
        return when (k) {
            AccessKind.BATTERY -> AppOps.ignoringBatteryOpt(ctx, pkg)
            AccessKind.DND -> Hidden.call(ctx.getSystemService(NotificationManager::class.java), "isNotificationPolicyAccessGrantedForPackage", pkg) as? Boolean
            else -> try {
                when (appOps(ctx).unsafeCheckOpNoThrow(k.op!!, uid, pkg)) {
                    AppOpsManager.MODE_ALLOWED, AppOpsManager.MODE_FOREGROUND -> true
                    AppOpsManager.MODE_DEFAULT -> k.permission?.let { ctx.packageManager.checkPermission(it, pkg) == PackageManager.PERMISSION_GRANTED } ?: true
                    else -> false
                }
            } catch (_: Throwable) { null }
        }
    }

    /** AppOpsManager.setMode / setUidMode are @SystemApi (MANAGE_APP_OPS_MODES). */
    fun set(ctx: Context, k: AccessKind, pkg: String, uid: Int, allow: Boolean): Boolean = when (k) {
        AccessKind.BATTERY -> AppOps.setIgnoreBatteryOpt(ctx, pkg, allow)
        AccessKind.DND -> Hidden.tryCall(ctx.getSystemService(NotificationManager::class.java), "setNotificationPolicyAccessGranted", pkg, allow).isSuccess
        else -> {
            val mode = if (allow) AppOpsManager.MODE_ALLOWED else AppOpsManager.MODE_ERRORED
            val ops = appOps(ctx)
            if (k.perUid) Hidden.tryCall(ops, "setUidMode", k.op!!, uid, mode).isSuccess
            else Hidden.tryCall(ops, "setMode", k.op!!, uid, pkg, mode).isSuccess
        }
    }

    fun eligible(ctx: Context, k: AccessKind, pkg: String): Boolean = when (k) {
        AccessKind.BATTERY -> true
        AccessKind.PIP -> try {
            ctx.packageManager.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES).activities?.any {
                Hidden.call(it, "supportsPictureInPicture") == true
            } == true
        } catch (_: Throwable) { false }
        else -> AppOps.requests(ctx, pkg, k.permission!!)
    }
}

@Composable
fun SpecialAccessPage() {
    val nav = LocalNav.current
    PageList {
        item {
            Section {
                AccessKind.values().forEach { k -> NavRow(k.title, k.summary) { nav.push("access/${k.key}") } }
                NavRow("Notification read, reply & control", "Apps that can read your notifications") { nav.push("access/listener") }
                NavRow("Device admin apps", "Apps with device management rights") { nav.push("device_admins") }
                NavRow("Accessibility services", "Apps that can control the screen") { nav.push("a11y") }
            }
        }
    }
}

fun accessTitle(key: String?): String = when (key) {
    "listener" -> "Notification access"
    null -> "Special app access"
    else -> AccessKind.of(key)?.title ?: "Special app access"
}

@Composable
fun AccessPage(key: String, pkg: String?) {
    if (key == "listener") { ListenerListPage(); return }
    val kind = AccessKind.of(key)
    if (kind == null) { PageList { item { Section("Unknown access type") { BodyText(key) } } }; return }
    if (pkg != null) { AccessAppPage(kind, pkg); return }
    val ctx = LocalContext.current
    val nav = LocalNav.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeTick { tick++ }
    AppPicker(
        filter = { AccessOps.eligible(ctx, kind, it.pkg) },
        subtitle = { r -> when (AccessOps.allowed(ctx, kind, r.pkg, r.ai.uid)) { true -> "Allowed"; false -> "Not allowed"; null -> UNKNOWN } },
        header = { Section(kind.title) { BodyText(kind.summary) } },
        defaultShowSystem = kind == AccessKind.BATTERY,
        refreshKey = tick
    ) { nav.push("access/${kind.key}/${Uri.encode(it.pkg)}") }
}

/** Single-app toggle; this is the screen apps land on with a package: URI. */
@Composable
fun AccessAppPage(kind: AccessKind, pkg: String) {
    val ctx = LocalContext.current
    val ai = remember(pkg) { AppOps.info(ctx, pkg) }
    var refresh by remember { mutableIntStateOf(0) }
    if (ai == null) { PageList { item { Section("App not found") { BodyText(pkg) } } }; return }
    val icon = remember(ai) { AppOps.icon(ctx, ai) }
    val state = remember(refresh) { AccessOps.allowed(ctx, kind, pkg, ai.uid) }
    val eligible = remember { AccessOps.eligible(ctx, kind, pkg) }
    PageList {
        item {
            Section {
                androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    AppIcon(icon, 40); Spacer(Modifier.width(12.dp))
                    androidx.compose.material3.Text(AppOps.label(ctx, ai), color = androidx.compose.ui.graphics.Color.White)
                }
                Spacer(Modifier.height(8.dp))
                ToggleRow(kind.title, kind.summary, state, enabled = eligible) {
                    if (!AccessOps.set(ctx, kind, pkg, ai.uid, it)) toast(ctx, "${kind.title} was not changed")
                    refresh++
                }
                if (!eligible) BodyText("This app does not ask for this access.")
            }
        }
    }
}

object ListenerOps {
    fun components(ctx: Context): List<ComponentName> = try {
        ctx.packageManager.queryIntentServices(Intent(NotificationListenerService.SERVICE_INTERFACE), PackageManager.GET_META_DATA)
            .filter { it.serviceInfo.permission == "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" }
            .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
    } catch (_: Throwable) { emptyList() }

    fun granted(ctx: Context, cn: ComponentName): Boolean? = try {
        ctx.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(cn)
    } catch (_: Throwable) { null }

    /** NotificationManager.setNotificationListenerAccessGranted is @SystemApi (MANAGE_NOTIFICATION_LISTENERS). */
    fun set(ctx: Context, cn: ComponentName, on: Boolean): Boolean =
        Hidden.tryCall(ctx.getSystemService(NotificationManager::class.java), "setNotificationListenerAccessGranted", cn, on).isSuccess
}

@Composable
fun ListenerListPage() {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeTick { tick++ }
    val list = remember(tick) { ListenerOps.components(ctx) }
    PageList {
        item {
            Section("Notification access", note = if (list.isEmpty()) "No installed app offers a notification listener." else
                "These apps can read every notification, including messages and codes.") {
                list.forEach { cn ->
                    NavRow(Hidden.appLabel(ctx, cn.packageName), when (ListenerOps.granted(ctx, cn)) { true -> "Allowed"; false -> "Not allowed"; null -> UNKNOWN }) {
                        nav.push("listener/${Uri.encode(cn.flattenToString())}")
                    }
                }
            }
        }
    }
}

@Composable
fun ListenerDetailPage(flat: String) {
    val ctx = LocalContext.current
    val cn = remember(flat) { ComponentName.unflattenFromString(flat) }
    var refresh by remember { mutableIntStateOf(0) }
    var ask by remember { mutableStateOf(false) }
    if (cn == null) { PageList { item { Section("Unknown listener") { BodyText(flat) } } }; return }
    val label = Hidden.appLabel(ctx, cn.packageName)
    val on = remember(refresh) { ListenerOps.granted(ctx, cn) }
    PageList {
        item {
            Section(label) {
                ToggleRow("Allow notification access", "Lets the app read, dismiss and act on notifications", on) {
                    if (it) ask = true else { if (!ListenerOps.set(ctx, cn, false)) toast(ctx, "Access was not changed"); refresh++ }
                }
                InfoRow("Service", cn.shortClassName)
            }
        }
    }
    if (ask) HioneAlert("Allow notification access for $label?",
        "$label will be able to read all notifications, including personal info like contact names and message text. It can also dismiss notifications and tap their buttons.",
        "Allow", onConfirm = { ask = false; if (!ListenerOps.set(ctx, cn, true)) toast(ctx, "Access was not changed"); refresh++ }, onDismiss = { ask = false })
}

@Composable
fun DeviceAdminsPage() {
    val ctx = LocalContext.current
    val dpm = remember { ctx.getSystemService(DevicePolicyManager::class.java) }
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeTick { tick++ }
    val receivers = remember(tick) {
        try {
            ctx.packageManager.queryBroadcastReceivers(Intent(DeviceAdminReceiver.ACTION_DEVICE_ADMIN_ENABLED), PackageManager.GET_META_DATA)
                .filter { it.activityInfo.permission == "android.permission.BIND_DEVICE_ADMIN" }
                .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
        } catch (_: Throwable) { emptyList() }
    }
    var remove by remember { mutableStateOf<ComponentName?>(null) }
    PageList {
        item {
            Section("Device admin apps", note = if (receivers.isEmpty()) "No installed app offers device admin." else null) {
                receivers.forEach { cn ->
                    val active = try { dpm.isAdminActive(cn) } catch (_: Throwable) { null }
                    ToggleRow(Hidden.appLabel(ctx, cn.packageName), cn.shortClassName, active) { on ->
                        if (on) ctx.startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).setClassName(ctx, "com.hione.settings.dialogs.DeviceAdminAddActivity")
                            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, cn))
                        else remove = cn
                    }
                }
            }
        }
    }
    remove?.let { cn ->
        HioneAlert("Deactivate this device admin?", "${Hidden.appLabel(ctx, cn.packageName)} loses its device management rights.", "Deactivate", danger = true,
            onConfirm = {
                remove = null
                // removeActiveAdmin from a non-admin caller needs MANAGE_DEVICE_ADMINS.
                if (runCatching { dpm.removeActiveAdmin(cn) }.isFailure) toast(ctx, "Device admin was not removed")
                tick++
            }, onDismiss = { remove = null })
    }
}

