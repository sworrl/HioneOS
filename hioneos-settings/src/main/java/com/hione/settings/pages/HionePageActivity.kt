package com.hione.settings.pages

import com.hione.settings.Mascot
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.settings.*
import com.hione.settings.route.SettingsRouterActivity
import com.hione.settings.ui.*

/**
 * Host for the HioneOS pages that replace stock Settings screens.
 *
 * Routes are plain strings ("wifi", "app/<pkg>", "access/overlay/<pkg>") so the router can build
 * them from any intent. Pages push further routes onto a small back stack; back pops it and
 * finishes the activity when it is empty, which returns the caller to where it was.
 */
class HionePageActivity : ComponentActivity() {
    companion object {
        const val EXTRA_ROUTE = "com.hione.settings.extra.ROUTE"
        const val EXTRA_WHY = "com.hione.settings.extra.WHY"
        const val EXTRA_SOURCE = "com.hione.settings.extra.SOURCE"

        fun intent(ctx: Context, route: String, source: Intent? = null): Intent =
            Intent(ctx, HionePageActivity::class.java).apply {
                putExtra(EXTRA_ROUTE, route)
                if (source != null) putExtra(EXTRA_SOURCE, sanitized(source))
            }

        /**
         * Only the parts pages read. Copying the caller's whole extras bundle could carry a
         * Parcelable class that does not exist in this process and crash the page on unparcel.
         */
        private fun sanitized(src: Intent): Intent = Intent(src.action).apply {
            setDataAndType(src.data, src.type)
            runCatching { src.getStringArrayExtra("account_types") }.getOrNull()?.let { putExtra("account_types", it) }
        }

        fun open(ctx: Context, route: String) {
            ctx.startActivity(intent(ctx, route).addFlags(if (ctx is android.app.Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val start = intent?.getStringExtra(EXTRA_ROUTE) ?: "unavailable"
        @Suppress("DEPRECATION")
        val source: Intent? = intent?.getParcelableExtra(EXTRA_SOURCE)
        val why = intent?.getStringExtra(EXTRA_WHY)
        setContent {
            val stack = remember { mutableStateListOf(start) }
            val nav = remember {
                Nav(push = { stack.add(it) }, pop = {
                    if (stack.size > 1) stack.removeAt(stack.lastIndex) else finish()
                }, finish = { finish() })
            }
            CompositionLocalProvider(LocalNav provides nav, LocalSource provides source, LocalWhy provides why) {
                PageScaffold(stack.last())
            }
        }
    }
}

class Nav(val push: (String) -> Unit, val pop: () -> Unit, val finish: () -> Unit)
val LocalNav = staticCompositionLocalOf { Nav({}, {}, {}) }
/** The intent that opened this page, for pages that read caller extras. */
val LocalSource = staticCompositionLocalOf<Intent?> { null }
val LocalWhy = staticCompositionLocalOf<String?> { null }

fun seg(route: String, i: Int): String? = route.split('/').getOrNull(i)?.let { Uri.decode(it) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PageScaffold(route: String) {
    val nav = LocalNav.current
    BackHandler { nav.pop() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(pageTitle(route), color = HioneTealBright, fontSize = 17.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = { nav.pop() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = HioneTeal)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = HioneDarkBg)
            )
        },
        containerColor = HioneDarkBg
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).padding(horizontal = 14.dp).hioneEdgeSwipeBack { nav.pop() }) {
            key(route) { PageContent(route) }
        }
    }
}

fun pageTitle(route: String): String = when (route.substringBefore('/')) {
    "wifi" -> "Wi-Fi"
    "wifi_saved" -> "Saved networks"
    "wifi_prefs" -> "Wi-Fi preferences"
    "internet" -> "Internet"
    "mobile" -> "Mobile network"
    "hotspot" -> "Hotspot & tethering"
    "vpn" -> "VPN"
    "data_usage" -> "Data usage"
    "nfc" -> "NFC"
    "connected" -> "Connection preferences"
    "apps" -> "Apps"
    "app" -> "App info"
    "appnotif" -> "App notifications"
    "channel" -> "Notification category"
    "applocale" -> "App language"
    "default_apps" -> "Default apps"
    "special" -> "Special app access"
    "access" -> accessTitle(seg(route, 1))
    "listener" -> "Notification access"
    "notifications" -> "Notifications"
    "dnd" -> "Do Not Disturb"
    "sound" -> "Sound & vibration"
    "hione_sounds" -> "${Mascot.osName} sounds"
    "hione_sound" -> soundCategoryTitle(seg(route, 1))
    "display_more" -> "More display settings"
    "mascot" -> "Theme & mascot"
    "home_layout" -> "Home screen"
    "storage" -> "Storage"
    "location" -> "Location"
    "security" -> "Security & privacy"
    "screenlock" -> "Screen lock"
    "device_admins" -> "Device admin apps"
    "a11y" -> "Accessibility"
    "datetime" -> "Date & time"
    "languages" -> "Languages"
    "keyboard" -> "Keyboard"
    "accounts" -> "Accounts"
    "accounts_add" -> "Add an account"
    "users" -> "Users"
    "developer" -> "Developer options"
    "reset" -> "Reset options"
    "system" -> "System"
    "unavailable" -> "Not available"
    else -> "Settings"
}

@Composable
private fun PageContent(route: String) {
    when (route.substringBefore('/')) {
        "wifi" -> WifiPage()
        "wifi_saved" -> WifiSavedPage()
        "wifi_prefs" -> WifiPrefsPage()
        "internet" -> InternetPage()
        "mobile" -> MobileNetworkPage()
        "hotspot" -> HotspotPage()
        "vpn" -> VpnPage()
        "data_usage" -> DataUsagePage()
        "nfc" -> NfcPage()
        "connected" -> ConnectedPrefsPage()
        "apps" -> AppListPage()
        "app" -> AppInfoPage(seg(route, 1) ?: "")
        "appnotif" -> AppNotificationsPage(seg(route, 1) ?: "")
        "channel" -> ChannelPage(seg(route, 1) ?: "", seg(route, 2) ?: "")
        "applocale" -> AppLocalePage(seg(route, 1) ?: "")
        "default_apps" -> DefaultAppsPage()
        "special" -> SpecialAccessPage()
        "access" -> AccessPage(seg(route, 1) ?: "", seg(route, 2))
        "listener" -> ListenerDetailPage(seg(route, 1) ?: "")
        "notifications" -> NotificationsPage()
        "dnd" -> DndPage()
        "sound" -> SoundPage()
        "hione_sounds" -> HioneSoundsPage()
        "hione_sound" -> HioneSoundCategoryPage(seg(route, 1) ?: "")
        "display_more" -> DisplayMorePage()
        "mascot" -> MascotPage()
        "home_layout" -> HomeLayoutPage()
        "storage" -> StoragePage()
        "location" -> LocationPage()
        "security" -> SecurityPage()
        "screenlock" -> ScreenLockPage()
        "device_admins" -> DeviceAdminsPage()
        "a11y" -> seg(route, 1)?.let { A11yServicePage(it) } ?: AccessibilityPage()
        "datetime" -> DateTimePage()
        "languages" -> LanguagesPage()
        "keyboard" -> KeyboardPage()
        "accounts" -> AccountsPage()
        "accounts_add" -> AddAccountPage()
        "users" -> UsersPage()
        "developer" -> DeveloperPage()
        "reset" -> ResetPage()
        "system" -> SystemPage()
        else -> UnavailablePage()
    }
}

/** Shown when a screen is neither rebuilt in HioneOS nor present in stock Settings. */
@Composable
private fun UnavailablePage() {
    val src = LocalSource.current
    val why = LocalWhy.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    PageList {
        item {
            Section("Not available") {
                BodyText(why ?: "This screen is not built into ${Mascot.osName} yet.")
                Spacer(Modifier.height(8.dp))
                InfoRow("Requested", src?.action ?: UNKNOWN)
                src?.data?.let { InfoRow("Data", it.toString()) }
            }
        }
        item {
            NavRow("Open ${Mascot.settingsName}", "Go to the main settings list") {
                ctx.startActivity(Intent(ctx, HioneSettingsActivity::class.java))
            }
        }
    }
}

/**
 * Opens a stock Settings page by class name for rows that are still interim deep links.
 * Says so on screen when the stock page is gone instead of failing silently.
 */
fun openStockPage(ctx: Context, cls: String, action: String? = null, extras: Bundle? = null): Boolean {
    val full = if (cls.startsWith(".")) "com.android.settings$cls" else cls
    return try {
        ctx.startActivity(Intent(action ?: Intent.ACTION_MAIN).apply {
            setClassName("com.android.settings", full)
            if (extras != null) putExtras(extras)
            if (ctx !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        true
    } catch (_: Throwable) {
        toast(ctx, "That stock Settings page is not on this device")
        false
    }
}

fun stockPresent(ctx: Context, cls: String): Boolean =
    SettingsRouterActivity.stockAvailable(ctx, if (cls.startsWith(".")) "com.android.settings$cls" else cls)

/** Row for screens still served by stock Settings. Labelled so nobody mistakes it for ours. */
@Composable
fun StockRow(title: String, cls: String, summary: String? = null, action: String? = null) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val present = remember(cls) { stockPresent(ctx, cls) }
    NavRow(title, listOfNotNull(summary, if (present) "Opens the stock Settings page" else "Stock page not on this device").joinToString(". ")) {
        openStockPage(ctx, cls, action)
    }
}
