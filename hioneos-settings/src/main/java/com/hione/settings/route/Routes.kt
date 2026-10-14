package com.hione.settings.route

import com.hione.settings.Mascot
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Where every settings intent lands.
 *
 * HioneSettings mirrors the stock Settings intent filters (see the generated block in the
 * manifest), so these intents reach [SettingsRouterActivity] first. The router then picks:
 *  - [Dest.Page]: a HioneOS page in HionePageActivity,
 *  - [Dest.Section]: one of the original HioneSettingsActivity screens,
 *  - [Dest.External]: another app that really owns the flow (wallpaper picker, PermissionController),
 *  - anything else: the stock component from [StockMap] as an interim deep link, and if that is
 *    gone (stock Settings removed) a HioneOS page that says the screen is not built yet.
 */
sealed class Dest {
    data class Page(val route: String) : Dest()
    data class Section(val section: String?) : Dest()
    data class External(val intent: Intent) : Dest()
    /** Not reimplemented. [why] is shown if the stock page is missing too. */
    data class Stock(val why: String) : Dest()
}

object Routes {
    const val EXTRA_APP_PACKAGE = "android.provider.extra.APP_PACKAGE"
    const val EXTRA_CHANNEL_ID = "android.provider.extra.CHANNEL_ID"
    const val EXTRA_LISTENER = "android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME"

    /** Interim: page not rebuilt yet; stock opens while it exists. */
    private val LATER get() = Dest.Stock("This screen is not built into ${Mascot.osName} yet.")
    /** The feature does not apply to this device. */
    private fun na(why: String) = Dest.Stock(why)

    private fun pkgOf(intent: Intent): String? =
        intent.data?.takeIf { it.scheme == "package" }?.schemeSpecificPart?.takeIf { it.isNotBlank() }
            ?: intent.getStringExtra(EXTRA_APP_PACKAGE)
            ?: intent.getStringExtra("app_package")
            ?: intent.getStringExtra("package")

    /** "access/<kind>" or "access/<kind>/<pkg>" when the intent names an app. */
    private fun access(kind: String, intent: Intent): Dest.Page =
        Dest.Page(pkgOf(intent)?.let { "access/$kind/${Uri.encode(it)}" } ?: "access/$kind")

    private fun app(intent: Intent): Dest = pkgOf(intent)?.let { Dest.Page("app/${Uri.encode(it)}") } ?: Dest.Page("apps")

    fun resolve(intent: Intent): Dest {
        val a = intent.action ?: return Dest.Section(null)
        return when (a) {
            Settings.ACTION_SETTINGS -> Dest.Section(null)

            // ---- HioneOS: Theme & mascot (pack picker, holiday settings)
            "com.hione.settings.action.MASCOT" -> Dest.Page("mascot")
            // ---- HioneOS: Home screen (where the launcher's status card sits, pull up, auto pop)
            "com.hione.settings.action.HOME_LAYOUT" -> Dest.Page("home_layout")

            // ---- Network & internet
            Settings.ACTION_WIRELESS_SETTINGS, Settings.ACTION_AIRPLANE_MODE_SETTINGS,
            "android.settings.panel.action.INTERNET_CONNECTIVITY" -> Dest.Page("internet")
            Settings.ACTION_WIFI_SETTINGS, "android.settings.NETWORK_PROVIDER_SETTINGS",
            "android.settings.WIFI_DETAILS_SETTINGS", "android.net.wifi.PICK_WIFI_NETWORK",
            "android.settings.panel.action.WIFI" -> Dest.Page("wifi")
            "android.settings.WIFI_SAVED_NETWORK_SETTINGS" -> Dest.Page("wifi_saved")
            Settings.ACTION_WIFI_IP_SETTINGS -> Dest.Page("wifi_prefs")
            "android.settings.WIFI_SCANNING_SETTINGS", "android.settings.LOCATION_SCANNING_SETTINGS",
            Settings.ACTION_LOCATION_SOURCE_SETTINGS -> Dest.Page("location")
            "android.settings.NETWORK_OPERATOR_SETTINGS", Settings.ACTION_DATA_ROAMING_SETTINGS,
            "android.settings.MOBILE_NETWORK_LIST", "android.settings.MANAGE_ALL_SIM_PROFILES_SETTINGS",
            "android.settings.MMS_MESSAGE_SETTING" -> Dest.Page("mobile")
            "android.settings.TETHER_SETTINGS", "com.android.settings.WIFI_TETHER_SETTINGS" -> Dest.Page("hotspot")
            Settings.ACTION_VPN_SETTINGS, "android.net.vpn.SETTINGS" -> Dest.Page("vpn")
            Settings.ACTION_DATA_USAGE_SETTINGS, "android.settings.MOBILE_DATA_USAGE",
            "android.settings.DATA_SAVER_SETTINGS" -> Dest.Page("data_usage")
            Settings.ACTION_NFC_SETTINGS, Settings.ACTION_NFCSHARING_SETTINGS,
            "android.settings.panel.action.NFC" -> Dest.Page("nfc")
            "com.android.settings.ADVANCED_CONNECTED_DEVICE_SETTINGS" -> Dest.Page("connected")

            // ---- Bluetooth (existing HioneOS screen)
            Settings.ACTION_BLUETOOTH_SETTINGS, "android.settings.BLUETOOTH_NEW_SETTINGS",
            "com.android.settings.BLUETOOTH_NEW_SETTINGS", "android.settings.BLUETOOTH_PAIRING_SETTINGS",
            "com.android.settings.BLUETOOTH_DEVICE_DETAIL_SETTINGS" -> Dest.Section("bluetooth")

            // ---- Apps
            Settings.ACTION_APPLICATION_SETTINGS, Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS,
            Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS, "android.intent.action.MANAGE_PACKAGE_STORAGE",
            "android.settings.MANAGE_DOMAIN_URLS" -> Dest.Page("apps")
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "android.intent.action.AUTO_REVOKE_PERMISSIONS",
            Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, "com.android.settings.APP_OPEN_BY_DEFAULT_SETTINGS",
            Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS,
            "android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL" -> app(intent)
            Settings.ACTION_APP_LOCALE_SETTINGS -> pkgOf(intent)?.let { Dest.Page("applocale/${Uri.encode(it)}") } ?: Dest.Page("languages")
            "android.settings.MANAGE_DEFAULT_APPS_SETTINGS" -> Dest.Page("default_apps")
            Settings.ACTION_VOICE_INPUT_SETTINGS -> Dest.Page("default_apps")

            // ---- Special app access
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "android.settings.MANAGE_APP_OVERLAY_PERMISSION" -> access("overlay", intent)
            Settings.ACTION_MANAGE_WRITE_SETTINGS -> access("write", intent)
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES -> access("install", intent)
            Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION, Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION -> access("files", intent)
            Settings.ACTION_USAGE_ACCESS_SETTINGS -> access("usage", intent)
            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM -> access("alarms", intent)
            "android.settings.PICTURE_IN_PICTURE_SETTINGS" -> access("pip", intent)
            Settings.ACTION_REQUEST_MANAGE_MEDIA -> access("media", intent)
            "android.settings.TURN_SCREEN_ON_SETTINGS" -> access("screenon", intent)
            "android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT" -> access("fullscreen", intent)
            "android.settings.MANAGE_APP_LONG_RUNNING_JOBS" -> access("longjobs", intent)
            Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS -> Dest.Page("access/battery")
            Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS, "android.settings.NOTIFICATION_POLICY_ACCESS_DETAIL_SETTINGS" -> access("dnd", intent)
            Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS -> Dest.Page("access/listener")
            Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS ->
                intent.getStringExtra(EXTRA_LISTENER)?.let { Dest.Page("listener/${Uri.encode(it)}") } ?: Dest.Page("access/listener")

            // ---- Notifications & sound
            "android.settings.NOTIFICATION_SETTINGS", Settings.ACTION_ALL_APPS_NOTIFICATION_SETTINGS -> Dest.Page("notifications")
            Settings.ACTION_APP_NOTIFICATION_SETTINGS -> pkgOf(intent)?.let { Dest.Page("appnotif/${Uri.encode(it)}") } ?: Dest.Page("notifications")
            Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS -> {
                val p = pkgOf(intent); val ch = intent.getStringExtra(EXTRA_CHANNEL_ID)
                when {
                    p != null && ch != null -> Dest.Page("channel/${Uri.encode(p)}/${Uri.encode(ch)}")
                    p != null -> Dest.Page("appnotif/${Uri.encode(p)}")
                    else -> Dest.Page("notifications")
                }
            }
            Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS, "android.settings.ZEN_MODE_SETTINGS" -> Dest.Page("dnd")
            Settings.ACTION_SOUND_SETTINGS, "com.android.settings.SOUND_SETTINGS", "android.settings.ACTION_OTHER_SOUND_SETTINGS",
            "android.settings.panel.action.VOLUME" -> Dest.Page("sound")
            "android.settings.AUDIO_SETTINGS" -> Dest.Section("audio_dac")

            // ---- Display
            Settings.ACTION_DISPLAY_SETTINGS, "com.android.settings.DISPLAY_SETTINGS",
            "com.android.settings.NAVIGATION_MODE_SETTINGS", "com.android.settings.GESTURE_NAVIGATION_SETTINGS",
            "com.android.settings.BUTTON_NAVIGATION_SETTINGS" -> Dest.Section("display")
            "android.settings.ADAPTIVE_BRIGHTNESS_SETTINGS", "android.settings.AUTO_ROTATE_SETTINGS",
            Settings.ACTION_NIGHT_DISPLAY_SETTINGS, "android.settings.DARK_THEME_SETTINGS" -> Dest.Page("display_more")
            "android.settings.WALLPAPER_SETTINGS" -> Dest.External(Intent(Intent.ACTION_SET_WALLPAPER))

            // ---- Battery
            Settings.ACTION_BATTERY_SAVER_SETTINGS, "android.intent.action.POWER_USAGE_SUMMARY" -> Dest.Section("battery")

            // ---- Storage
            Settings.ACTION_INTERNAL_STORAGE_SETTINGS, Settings.ACTION_MEMORY_CARD_SETTINGS,
            "android.provider.action.DOCUMENT_ROOT_SETTINGS" -> Dest.Page("storage")

            // ---- Security, privacy, location, accessibility
            Settings.ACTION_SECURITY_SETTINGS, "android.settings.LOCK_SCREEN_SETTINGS", Settings.ACTION_PRIVACY_SETTINGS,
            "android.settings.PRIVACY_ADVANCED_SETTINGS", "android.settings.PRIVACY_CONTROLS",
            "com.android.settings.security.SECURITY_ADVANCED_SETTINGS", "com.android.settings.MORE_SECURITY_PRIVACY_SETTINGS" -> Dest.Page("security")
            Settings.ACTION_ACCESSIBILITY_SETTINGS, "android.settings.ACCESSIBILITY_SETTINGS_FOR_SUW",
            "android.settings.TEXT_READING_SETTINGS", "com.android.settings.ACCESSIBILITY_COLOR_SPACE_SETTINGS",
            "android.settings.REDUCE_BRIGHT_COLORS_SETTINGS", "android.settings.COLOR_INVERSION_SETTINGS",
            "android.settings.ACCESSIBILITY_COLOR_MOTION_SETTINGS", Settings.ACTION_CAPTIONING_SETTINGS -> Dest.Page("a11y")
            "android.settings.ACCESSIBILITY_DETAILS_SETTINGS" ->
                intent.getStringExtra(Intent.EXTRA_COMPONENT_NAME)?.let { Dest.Page("a11y/${Uri.encode(it)}") } ?: Dest.Page("a11y")

            // ---- System
            Settings.ACTION_DATE_SETTINGS -> Dest.Page("datetime")
            Settings.ACTION_LOCALE_SETTINGS, "android.settings.LANGUAGE_SETTINGS" -> Dest.Page("languages")
            Settings.ACTION_INPUT_METHOD_SETTINGS, Settings.ACTION_INPUT_METHOD_SUBTYPE_SETTINGS,
            Settings.ACTION_HARD_KEYBOARD_SETTINGS -> Dest.Page("keyboard")
            Settings.ACTION_SYNC_SETTINGS -> Dest.Page("accounts")
            Settings.ACTION_ADD_ACCOUNT -> Dest.Page("accounts_add")
            "android.settings.USER_SETTINGS" -> Dest.Page("users")
            Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, "com.android.settings.APPLICATION_DEVELOPMENT_SETTINGS" -> Dest.Page("developer")
            "com.android.settings.action.FACTORY_RESET" -> Dest.Page("reset")
            Settings.ACTION_DEVICE_INFO_SETTINGS, "android.settings.DEVICE_NAME", "android.settings.HIBY_SETTINGS_ABOUT",
            "com.android.settings.ANDROID_VERSION_SETTINGS" -> Dest.Section("about")

            // ---- Not applicable to a DAP with no biometrics, work profile, calling or stylus
            Settings.ACTION_FINGERPRINT_ENROLL, "android.settings.FINGERPRINT_SETTINGS", "android.settings.FINGERPRINT_SETUP",
            "android.settings.FACE_SETTINGS", "android.settings.FACE_ENROLL", Settings.ACTION_BIOMETRIC_ENROLL ->
                na("This device has no fingerprint or face sensor.")
            "android.settings.WORK_MODE_VIEW", "android.settings.MANAGED_PROFILE_SETTINGS", "android.settings.MANAGE_CROSS_PROFILE_ACCESS",
            "android.settings.ENTERPRISE_PRIVACY_SETTINGS", "android.settings.SHOW_REMOTE_BUGREPORT_DIALOG" ->
                na("${Mascot.osName} does not use work profiles or device management.")
            "android.settings.WIFI_CALLING_SETTINGS", "android.telephony.ims.action.SHOW_CAPABILITY_DISCOVERY_OPT_IN" ->
                na("This device has no phone dialer, so calling features are off.")
            "android.settings.ASSIST_GESTURE_SETTINGS", "android.settings.COMMUNAL_SETTINGS", "com.android.settings.STYLUS_USI_DETAILS_SETTINGS",
            "android.settings.action.ONE_HANDED_SETTINGS", "android.settings.VR_LISTENER_SETTINGS", "android.settings.MANAGE_CLONED_APPS_SETTINGS",
            "android.settings.ADVANCED_MEMORY_PROTECTION_SETTINGS", "com.android.settings.action.SUPPORT_SETTINGS",
            "android.settings.development.START_DSU_LOADER" -> na("This feature is not used on this device.")

            else -> LATER
        }
    }

    /** Stock component for an action, honoring the data scheme / type the stock filters split on. */
    fun stockClassFor(intent: Intent): String? {
        val a = intent.action ?: return null
        val scheme = intent.data?.scheme
        val keys = buildList {
            if (scheme != null && intent.type != null) add("$a|$scheme|mime")
            if (intent.type != null) add("$a|mime")
            if (scheme != null) add("$a|$scheme")
            add(a)
        }
        return keys.firstNotNullOfOrNull { StockMap.byAction[it] }
    }
}
