package com.hione.settings

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.hione.settings.bluetooth.*
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// The old openAospSettings() hand-off is gone: every screen it opened in stock Settings now has a
// HioneOS page (pages/), and anything not rebuilt yet goes through route/SettingsRouterActivity,
// which deep-links to the exact stock component and explains when stock Settings is absent.

/**
 * Home tiles. A tile with a [route] opens a HionePageActivity page (the screens that replace stock
 * Settings); a tile without one opens its original in-activity screen.
 */
enum class SettingsSection(val title: String, val icon: ImageVector, val desc: String, val route: String? = null) {
    AUDIO_DAC("DAC", Icons.Default.Headphones, "Opens DAC settings in the Hardware app"),
    // Was "dynamic modes & BPM pulse": this screen stores a mode, it does not animate anything,
    // and the indicator is non-functional on this unit.
    FN_SWITCH("FN Switch & Keys", Icons.Default.ToggleOn, "Hardware Fn lock switch and what it locks"),
    NETWORK("Network & internet", Icons.Default.Language, "Wi-Fi, mobile data, hotspot, VPN and data usage", "internet"),
    WIRELESS("Network & ADB", Icons.Default.Wifi, "Wireless ADB, Google Fi network tools"),
    BLUETOOTH("Bluetooth", Icons.Default.Bluetooth, "Codecs, LDAC, aptX and paired devices"),
    CONNECTED("Connected devices", Icons.Default.Usb, "USB mode, NFC, Bluetooth device name", "connected"),
    STORAGE_APPS("Apps & Storage", Icons.Default.Storage, "App info, default apps, special access, internal storage and SD card"),
    NOTIFICATIONS("Notifications", Icons.Default.Notifications, "App notifications and Do Not Disturb", "notifications"),
    SOUND("Sound & vibration", Icons.Default.VolumeUp, "Volume, ring mode, ringtones and system sounds", "sound"),
    DISPLAY("Display & Light", Icons.Default.BrightnessMedium, "Brightness, light sensor, screen timeout and theme"),
    MASCOT("Theme & mascot", Icons.Default.Palette, "Mascot, colors and names, holiday art and reminders", "mascot"),
    HOME_LAYOUT("Home screen", Icons.Default.Dashboard, "Where the status card sits, pull up and pop up", "home_layout"),
    BATTERY("Battery & Power", Icons.Default.BatteryChargingFull, "Live readings, battery saver and app battery use"),
    SECURITY("Security & privacy", Icons.Default.Lock, "Screen lock, permissions, device admins", "security"),
    LOCATION("Location", Icons.Default.LocationOn, "Location on or off, app access and scanning", "location"),
    ACCESSIBILITY("Accessibility", Icons.Default.Accessibility, "Services, color, text and interaction", "a11y"),
    SYSTEM("System", Icons.Default.SettingsApplications, "Languages, keyboard, date and time, accounts, users, reset", "system"),
    SYSTEM_ABOUT("About ${Mascot.osName}", Icons.Default.Info, "Build, kernel, SoC and permissions as the system reports them")
}

class HioneSettingsActivity : ComponentActivity() {
    companion object { private val routingClaimed = java.util.concurrent.atomic.AtomicBoolean(false) }

    private fun applyImmersiveMode() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
        )
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyImmersiveMode()
        // Once per process: make sure tied settings intents resolve here (cheap, off the UI thread).
        if (routingClaimed.compareAndSet(false, true)) {
            val app = applicationContext
            Thread { runCatching { com.hione.settings.route.PreferredRouting.claim(app) } }.start()
        }
        val targetAction = intent?.action ?: ""
        val extraSec = intent?.getStringExtra("extra_section") ?: intent?.getStringExtra("section") ?: ""
        val initialSection = when {
            extraSec.equals("wireless", ignoreCase = true) || extraSec.equals("wifi", ignoreCase = true) || targetAction == Settings.ACTION_WIFI_SETTINGS || targetAction == Settings.ACTION_WIRELESS_SETTINGS -> SettingsSection.WIRELESS
            extraSec.equals("bluetooth", ignoreCase = true) || extraSec.equals("bt", ignoreCase = true) || targetAction == Settings.ACTION_BLUETOOTH_SETTINGS -> SettingsSection.BLUETOOTH
            extraSec.equals("audio_dac", ignoreCase = true) || extraSec.equals("dac", ignoreCase = true) || targetAction == Settings.ACTION_SOUND_SETTINGS || targetAction == "android.media.action.DISPLAY_AUDIO_EFFECT_CONTROL_PANEL" -> SettingsSection.AUDIO_DAC
            extraSec.equals("fn_switch", ignoreCase = true) || extraSec.equals("fn", ignoreCase = true) || targetAction == "com.m500.hardware.action.FN_SETTINGS" -> SettingsSection.FN_SWITCH
            extraSec.equals("display", ignoreCase = true) || targetAction == Settings.ACTION_DISPLAY_SETTINGS -> SettingsSection.DISPLAY
            extraSec.equals("storage_apps", ignoreCase = true) || extraSec.equals("storage", ignoreCase = true) || targetAction == Settings.ACTION_APPLICATION_SETTINGS || targetAction == Settings.ACTION_INTERNAL_STORAGE_SETTINGS -> SettingsSection.STORAGE_APPS
            extraSec.equals("battery", ignoreCase = true) || targetAction == Settings.ACTION_BATTERY_SAVER_SETTINGS -> SettingsSection.BATTERY
            extraSec.equals("system_about", ignoreCase = true) || extraSec.equals("about", ignoreCase = true) || targetAction == Settings.ACTION_DEVICE_INFO_SETTINGS -> SettingsSection.SYSTEM_ABOUT
            else -> null
        }

        setContent {
            HioneOSSettingsApp(initialSection = initialSection, onExit = { finish() })
        }
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveMode()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HioneOSSettingsApp(initialSection: SettingsSection?, onExit: () -> Unit) {
    val ctx = LocalContext.current
    var currentSection by remember { mutableStateOf(initialSection) }
    var searchQuery by remember { mutableStateOf("") }

    val handleBack = {
        if (currentSection != null) {
            currentSection = null
        } else {
            onExit()
        }
    }

    BackHandler(enabled = true) {
        handleBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (currentSection == null) "${Mascot.nameUpper}" else currentSection!!.title,
                            color = HioneTealBright,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Black
                        )
                        if (currentSection == null) {
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "OS SETTINGS",
                                color = Color.White,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { handleBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = HioneTeal)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = HioneDarkBg)
            )
        },
        containerColor = HioneDarkBg
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .hioneEdgeSwipeBack { handleBack() }
        ) {
            if (currentSection == null) {
                // AOSP / Pixel-Style Master Settings List
                val filteredSections = remember(searchQuery) {
                    if (searchQuery.isBlank()) SettingsSection.values().toList()
                    else SettingsSection.values().filter {
                        it.title.contains(searchQuery, ignoreCase = true) || it.desc.contains(searchQuery, ignoreCase = true)
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 1. Pixel-style Search Bar
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(24.dp))
                                .background(HioneSurface1)
                                .border(1.dp, HioneTeal.copy(alpha = 0.35f), RoundedCornerShape(24.dp))
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = "Search",
                                    tint = HioneTealBright,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                BasicTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    textStyle = androidx.compose.ui.text.TextStyle(
                                        color = Color.White,
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.Medium
                                    ),
                                    decorationBox = { innerTextField ->
                                        if (searchQuery.isEmpty()) {
                                            Text(
                                                "Search ${Mascot.osName} settings...",
                                                color = HioneMuted,
                                                fontSize = 13.sp
                                            )
                                        }
                                        innerTextField()
                                    }
                                )
                            }
                        }
                    }

                    // 2. Settings Category Tiles
                    items(filteredSections.size) { idx ->
                        val sec = filteredSections[idx]
                        val iconColors = listOf(
                            HioneTealBright,
                            HionePurple,
                            HioneTeal,
                            Color(0xFF00FF88),
                            HioneGold,
                            Color(0xFFFF5252),
                            HionePurpleDeep,
                            HionePink
                        )
                        val tileAccent = iconColors[sec.ordinal % iconColors.size]

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(HioneCardBg)
                                .border(1.dp, tileAccent.copy(alpha = 0.30f), RoundedCornerShape(16.dp))
                                .clickable {
                                    if (sec.route != null) com.hione.settings.pages.HionePageActivity.open(ctx, sec.route)
                                    else currentSection = sec
                                }
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(tileAccent.copy(alpha = 0.18f))
                                    .border(1.dp, tileAccent.copy(alpha = 0.45f), RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    sec.icon,
                                    contentDescription = null,
                                    tint = tileAccent,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            Spacer(Modifier.width(14.dp))

                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = sec.title,
                                    color = Color.White,
                                    fontSize = 14.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = sec.desc,
                                    color = HioneMuted,
                                    fontSize = 11.5.sp,
                                    maxLines = 2,
                                    lineHeight = 14.sp
                                )
                            }

                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = null,
                                tint = HioneMuted,
                                modifier = Modifier
                                    .size(16.dp)
                                    .graphicsLayer { rotationZ = 180f }
                            )
                        }
                    }

                    item {
                        Spacer(Modifier.height(16.dp))
                    }
                }
            } else {
                // Section Detail Screen
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp)
                ) {
                    when (currentSection) {
                        SettingsSection.AUDIO_DAC -> AudioDacScreen(ctx)
                        SettingsSection.FN_SWITCH -> FnSwitchScreen(ctx)
                        SettingsSection.WIRELESS -> WirelessScreen(ctx)
                        SettingsSection.BLUETOOTH -> BluetoothScreen(ctx)
                        SettingsSection.DISPLAY -> DisplayScreen(ctx)
                        SettingsSection.BATTERY -> BatteryScreen(ctx)
                        SettingsSection.STORAGE_APPS -> StorageAppsScreen(ctx)
                        SettingsSection.SYSTEM_ABOUT -> AboutScreen(ctx)
                        else -> {}
                    }
                }
            }

            val infinitePulse = rememberInfiniteTransition(label = "verPulse")
            val verColor by infinitePulse.animateColor(
                initialValue = Color(0x9989ACA7),
                targetValue = HioneTealBright.copy(alpha = 0.85f),
                animationSpec = infiniteRepeatable(
                    animation = tween(2500, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "verColor"
            )
            Text(
                text = "v${BuildConfig.VERSION_NAME}",
                color = verColor,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 4.dp, bottom = 4.dp)
            )
        }
    }
}

// ----------------------------------------------------
// Section 1: DAC. The DAC page lives in the Hardware app (com.m500.hardware); this is a link to it.
// ----------------------------------------------------
@Composable
fun AudioDacScreen(ctx: Context) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Mascot.color(0xFF0B242A))
                .border(1.dp, HioneTealBright.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                .clickable { DacSettingsLink.open(ctx) }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Headphones, contentDescription = null, tint = HioneTealBright, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("DAC settings", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("Filter, gain, DRE, high power and USB DAC. Opens the Hardware app.", color = HioneMuted, fontSize = 11.5.sp)
            }
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = HioneTealBright, modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = 180f })
        }
    }
}

// ----------------------------------------------------

// ----------------------------------------------------
// Section 2.5: Physical FN Hardware Switch & Key Lock
// ----------------------------------------------------
@Composable
fun FnSwitchScreen(ctx: Context) {
    val scope = rememberCoroutineScope()
    val cr = ctx.contentResolver
    
    // IDs MUST match HionePocketLockManager / FnLockSettingsActivity in Hione Music - the lock
    // manager is what actually acts on fn_settings. (The old list here used different IDs plus
    // three modes - speaker mute / display flip / recorder - that nothing implemented, so the
    // real value "touch_and_key_lock" matched nothing and the screen mislabelled the mode.)
    val fnOptions = listOf(
        "touch_and_key_lock" to ("Screen and keys (default)" to "Locks the touchscreen, side buttons and power button so nothing gets pressed in a pocket. The volume wheel still works unless you turn that off in ${Mascot.musicAppName}."),
        "touch_lock" to ("Touchscreen only" to "Locks the touchscreen. The side buttons and volume wheel still work"),
        "key_lock" to ("Keys only" to "Locks the side buttons. The touchscreen still works")
    )

    var currentMode by remember {
        mutableStateOf(
            try {
                val mode = Settings.Global.getString(cr, "fn_settings") ?: "touch_and_key_lock"
                // legacy id written by an older build of this screen
                if (mode.isBlank() || mode == "screen_and_keys") "touch_and_key_lock" else mode
            } catch (_: Throwable) { "touch_and_key_lock" }
        )
    }

    var fnStatus by remember {
        mutableStateOf(
            try {
                Settings.Global.getInt(cr, "fn_status", 0) == 1
            } catch (_: Throwable) { false }
        )
    }

    // Monitor live hardware Fn switch position
    DisposableEffect(Unit) {
        val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                fnStatus = try {
                    Settings.Global.getInt(cr, "fn_status", 0) == 1
                } catch (_: Throwable) { false }
            }
        }
        cr.registerContentObserver(Settings.Global.getUriFor("fn_status"), false, observer)
        onDispose {
            cr.unregisterContentObserver(observer)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Hardware Status Hero Card
        item {
            Column(Modifier.hioneHeroCard().padding(16.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (fnStatus) Icons.Default.Lock else Icons.Default.LockOpen, contentDescription = null, tint = if (fnStatus) HionePinkBright else HioneTealBright, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                "Fn switch",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                if (fnStatus) "ON (locked)" else "OFF (normal)",
                                color = if (fnStatus) HionePinkBright else HioneTealBright,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (fnStatus) HionePinkBright.copy(alpha = 0.2f) else HioneTealBright.copy(alpha = 0.2f))
                            .border(1.dp, if (fnStatus) HionePinkBright else HioneTealBright, RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            if (fnStatus) "LOCKED" else "READY",
                            color = if (fnStatus) HionePinkBright else HioneTealBright,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Mode Selection Card
        item {
            Column(Modifier.hioneCard().padding(14.dp)) {
                Text(
                    "WHAT THE FN SWITCH LOCKS",
                    color = HioneTealBright,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.height(10.dp))

                fnOptions.forEach { (modeKey, info) ->
                    val (title, desc) = info
                    val isSel = currentMode == modeKey
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSel) HioneTealBright.copy(alpha = 0.15f) else Color.Transparent)
                            .clickable {
                                currentMode = modeKey
                                scope.launch(Dispatchers.IO) {
                                    runCatching { Settings.Global.putString(cr, "fn_settings", modeKey) }
                                    runCatching { Settings.Global.putString(cr, "vendor.hiby.fn_settings", modeKey) }
                                    RootShell.execFast(
                                        "settings put global fn_settings $modeKey; " +
                                        "setprop persist.vendor.fn_mode $modeKey; " +
                                        "setprop vendor.fn_mode $modeKey; " +
                                        "setprop vendor.hiby.fn_settings $modeKey"
                                    )
                                }
                            }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSel,
                            onClick = {
                                currentMode = modeKey
                                scope.launch(Dispatchers.IO) {
                                    runCatching { Settings.Global.putString(cr, "fn_settings", modeKey) }
                                    runCatching { Settings.Global.putString(cr, "vendor.hiby.fn_settings", modeKey) }
                                    RootShell.execFast(
                                        "settings put global fn_settings $modeKey; " +
                                        "setprop persist.vendor.fn_mode $modeKey; " +
                                        "setprop vendor.fn_mode $modeKey; " +
                                        "setprop vendor.hiby.fn_settings $modeKey"
                                    )
                                }
                            },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = HioneTealBright,
                                unselectedColor = HioneMuted
                            )
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(title, color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                            Text(desc, color = HioneMuted, fontSize = 11.sp, lineHeight = 14.sp)
                        }
                    }
                }
            }
        }

        // Screen-Off Physical Buttons Card
        item {
            var mediaLockOff by remember {
                mutableStateOf(
                    try { Settings.System.getInt(cr, "media_lock", 0) == 0 } catch (_: Throwable) { true }
                )
            }
            var volumeLockOff by remember {
                mutableStateOf(
                    try { Settings.System.getInt(cr, "volume_lock", 0) == 0 } catch (_: Throwable) { true }
                )
            }

            Column(Modifier.hioneCard().padding(14.dp)) {
                Text(
                    "BUTTONS WITH THE SCREEN OFF",
                    color = HioneTealBright,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Let the play/pause, next and previous buttons and the volume knob work while the screen is off or locked.",
                    color = HioneMuted,
                    fontSize = 11.sp,
                    lineHeight = 14.sp
                )
                Spacer(Modifier.height(10.dp))

                // Media buttons screen-off toggle
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(HioneSurface2)
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Media buttons", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (mediaLockOff) "Work with the screen off (0)" else "Locked with the screen off (1)", color = if (mediaLockOff) HioneTealBright else HioneMuted, fontSize = 11.sp)
                    }
                    Switch(
                        checked = mediaLockOff,
                        onCheckedChange = { enable ->
                            mediaLockOff = enable
                            scope.launch(Dispatchers.IO) {
                                val value = if (enable) 0 else 1
                                try { Settings.System.putInt(cr, "media_lock", value) } catch (_: Throwable) {}
                                RootShell.execFast("settings put system media_lock $value; chmod 666 /dev/input/event*")
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = HioneTealBright,
                            checkedTrackColor = HioneTeal.copy(alpha = 0.5f),
                            uncheckedThumbColor = Color.Gray,
                            uncheckedTrackColor = HioneSurface2
                        )
                    )
                }

                Spacer(Modifier.height(8.dp))

                // Volume wheel screen-off toggle
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(HioneSurface2)
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Volume knob", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (volumeLockOff) "Work with the screen off (0)" else "Locked with the screen off (1)", color = if (volumeLockOff) HioneTealBright else HioneMuted, fontSize = 11.sp)
                    }
                    Switch(
                        checked = volumeLockOff,
                        onCheckedChange = { enable ->
                            volumeLockOff = enable
                            scope.launch(Dispatchers.IO) {
                                val value = if (enable) 0 else 1
                                try { Settings.System.putInt(cr, "volume_lock", value) } catch (_: Throwable) {}
                                RootShell.execFast("settings put system volume_lock $value; chmod 666 /dev/input/event*")
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = HioneTealBright,
                            checkedTrackColor = HioneTeal.copy(alpha = 0.5f),
                            uncheckedThumbColor = Color.Gray,
                            uncheckedTrackColor = HioneSurface2
                        )
                    )
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ----------------------------------------------------
// Section 3: Wireless, Networks & Wireless ADB
// ----------------------------------------------------
@Composable
fun WirelessScreen(ctx: Context) {
    val scope = rememberCoroutineScope()
    // Real adbd TCP port from service.adb.tcp.port (null = USB-only); re-read after every toggle.
    var adbPort by remember { mutableStateOf(WirelessAdbManager.currentPort()) }
    val adbEnabled = adbPort != null
    val wifiIp = remember { WirelessAdbManager.getWifiIpAddress(ctx) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Wireless ADB Card
        item {
            Column(Modifier.hioneHeroCard().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.DeveloperMode, contentDescription = null, tint = HioneTealBright)
                    Spacer(Modifier.width(8.dp))
                    Text("Wireless ADB", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Run adb over your local network, no USB cable needed.",
                    color = HioneMuted,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(12.dp))

                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(HioneSurface2)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(adbPort?.let { "ADB over TCP · port $it" } ?: "ADB over TCP (USB only)", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            when {
                                adbPort != null && wifiIp != null -> "Connect: adb connect $wifiIp:$adbPort"
                                adbPort != null -> "Connect to Wi-Fi first"
                                else -> "adbd is not listening on TCP"
                            },
                            color = if (adbPort != null && wifiIp != null) HioneTealBright else HionePink,
                            fontSize = 11.5.sp
                        )
                    }
                    Switch(
                        checked = adbEnabled,
                        onCheckedChange = {
                            scope.launch {
                                WirelessAdbManager.setEnabled(it)
                                kotlinx.coroutines.delay(800)          // adbd restart
                                adbPort = WirelessAdbManager.currentPort()
                            }
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = HioneTealBright, checkedTrackColor = Mascot.color(0xFF0F3238))
                    )
                }
            }
        }

        // Quick System Wi-Fi & Hotspot Links
        item {
            Column(Modifier.hioneCard().padding(14.dp)) {
                Text("NETWORK & CONNECTIONS", color = HioneTealBright, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))

                SettingsLinkRow(
                    icon = Icons.Default.Wifi,
                    title = "Wi-Fi",
                    subtitle = "Scan and connect to 2.4 GHz and 5 GHz networks",
                    onClick = { com.hione.settings.pages.HionePageActivity.open(ctx, "wifi") }
                )

                Spacer(Modifier.height(8.dp))

                SettingsLinkRow(
                    icon = Icons.Default.WifiTethering,
                    title = "Hotspot & tethering",
                    subtitle = "Share LTE mobile data with other devices",
                    onClick = { com.hione.settings.pages.HionePageActivity.open(ctx, "hotspot") }
                )

                Spacer(Modifier.height(8.dp))

                SettingsLinkRow(
                    icon = Icons.Default.AirplanemodeActive,
                    title = "Airplane mode & internet",
                    subtitle = "Airplane mode, mobile network, VPN and private DNS",
                    onClick = { com.hione.settings.pages.HionePageActivity.open(ctx, "internet") }
                )
            }
        }

        // Google Fi status. Fi data needs the Google Fi app: it provisions the SIM, holds the
        // carrier privileges and writes Fi's APNs. When the line isn't fully activated, the
        // networks refuse data with cause 7 (EPS services not allowed) whatever the phone does,
        // so this card only shows the state and opens the Fi app. (Forcing T-Mobile isn't
        // supported by this modem, and the old "Radio saver" switch had nothing behind it.)
        item {
            Column(Modifier.hioneCard().padding(14.dp)) {
                Text("GOOGLE FI", color = HioneTealBright, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                val tm = remember { ctx.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager }
                var opInfo by remember { mutableStateOf("") }
                LaunchedEffect(Unit) {
                    while (true) {
                        opInfo = try {
                            val op = tm?.networkOperatorName ?: ""
                            val reg = tm?.dataState
                            "Network: ${op.ifBlank { "searching…" }}. Data ${if (reg == android.telephony.TelephonyManager.DATA_CONNECTED) "connected" else "not connected"}."
                        } catch (_: Throwable) { "" }
                        kotlinx.coroutines.delay(5000)
                    }
                }
                Text(
                    "Fi data needs the Google Fi app. If data won't connect, open it and finish activation.",
                    color = Color(0xB3FFFFFF), fontSize = 11.sp, lineHeight = 14.sp
                )
                if (opInfo.isNotBlank()) { Spacer(Modifier.height(4.dp)); Text(opInfo, color = Mascot.color(0xFF7BE8DF), fontSize = 11.sp) }
                Spacer(Modifier.height(8.dp))
                val fiIntent = remember { ctx.packageManager.getLaunchIntentForPackage("com.google.android.apps.tycho") }
                if (fiIntent != null) {
                    Text(
                        "Open Google Fi",
                        color = HioneTealBright, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable {
                            runCatching { ctx.startActivity(fiIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }.padding(vertical = 6.dp)
                    )
                } else {
                    Text("The Google Fi app isn't installed.", color = Color(0x80FFFFFF), fontSize = 11.sp)
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ----------------------------------------------------
// Section 4: Bluetooth Audio & Device Cockpit
// ----------------------------------------------------
@Composable
fun BluetoothScreen(ctx: Context) {
    val scope = rememberCoroutineScope()
    
    LaunchedEffect(Unit) {
        com.hione.settings.bluetooth.HioneBluetoothController.init(ctx)
    }

    val isBtEnabled by com.hione.settings.bluetooth.HioneBluetoothController.isBluetoothEnabled.collectAsState()
    val isScanning by com.hione.settings.bluetooth.HioneBluetoothController.isScanning.collectAsState()
    val pairedDevices by com.hione.settings.bluetooth.HioneBluetoothController.pairedDevices.collectAsState()
    val discoveredDevices by com.hione.settings.bluetooth.HioneBluetoothController.discoveredDevices.collectAsState()

    // Codec policy (Settings.Global hione_bt_*): absent rows mean MAXIMUM, and the controller
    // enforces this on every A2DP connect - so what is shown here is what the link gets.
    val codecPolicy = remember { com.hione.settings.bluetooth.HioneBluetoothController.readCodecPolicy() }
    var ldacQuality by remember { mutableStateOf(codecPolicy.ldacLabel) }
    var aptxEnabled by remember { mutableStateOf(codecPolicy.aptx) }
    var aacEnabled by remember { mutableStateOf(codecPolicy.aac) }
    // AUDIO LOCKDOWN: any change BELOW maximum quality is held here until the user confirms it in
    // the dialog below; raising quality applies immediately. Never a silent downgrade.
    var pendingDowngrade by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    fun requestCodecChange(ldac: String, aptx: Boolean, aac: Boolean, what: String) {
        val applied = com.hione.settings.bluetooth.HioneBluetoothController.applyCodecConfig(ldac, aptx, aac)
        if (applied) {
            ldacQuality = ldac; aptxEnabled = aptx; aacEnabled = aac
        } else {
            pendingDowngrade = what to {
                com.hione.settings.bluetooth.HioneBluetoothController.applyCodecConfig(ldac, aptx, aac, confirmed = true)
                ldacQuality = ldac; aptxEnabled = aptx; aacEnabled = aac
            }
        }
    }
    pendingDowngrade?.let { (what, confirm) ->
        AlertDialog(
            onDismissRequest = { pendingDowngrade = null },
            containerColor = HioneCardBg,
            titleContentColor = Color.White,
            textContentColor = HioneMuted,
            title = { Text("Lower Bluetooth audio quality?", fontWeight = FontWeight.Bold) },
            text = {
                Text("$what\n\n${Mascot.osName} keeps Bluetooth on the highest quality your headphones support. " +
                    "This change lets the link use a lower-quality codec or bitrate until you raise it again.")
            },
            confirmButton = {
                TextButton(onClick = { confirm(); pendingDowngrade = null }) {
                    Text("Lower quality", color = HionePinkBright, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDowngrade = null }) { Text("Keep maximum", color = HioneTealBright) }
            }
        )
    }
    // The codec A2DP is really negotiating right now (BluetoothA2dp.getCodecStatus) — null when idle.
    var activeCodec by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pairedDevices) {
        while (true) {
            activeCodec = withContext(Dispatchers.IO) { runCatching { com.hione.settings.bluetooth.HioneBluetoothController.activeCodecSummary() }.getOrNull() }
            kotlinx.coroutines.delay(3000)
        }
    }
    val connectedCount = pairedDevices.count { it.isConnected }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Master Bluetooth Radio Toggle Card
        item {
            Column(Modifier.hioneHeroCard().padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isBtEnabled) HioneTeal.copy(alpha = 0.25f) else Color(0x22FFFFFF))
                                .border(1.dp, if (isBtEnabled) HioneTealBright else Color(0x33FFFFFF), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bluetooth,
                                contentDescription = null,
                                tint = if (isBtEnabled) HioneTealBright else HioneMuted,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Bluetooth",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                // Real state only: radio on + how many bonded devices are actually connected,
                                // plus the negotiated codec when A2DP is streaming. No "Hi-Res Ready" claim.
                                text = when {
                                    !isBtEnabled -> "Disabled"
                                    activeCodec != null -> "Active · $connectedCount connected · $activeCodec"
                                    connectedCount > 0 -> "Active · $connectedCount connected"
                                    else -> "Active · nothing connected"
                                },
                                color = if (isBtEnabled) HioneTealBright else HioneMuted,
                                fontSize = 11.5.sp
                            )
                        }
                    }

                    Switch(
                        checked = isBtEnabled,
                        onCheckedChange = { enable ->
                            com.hione.settings.bluetooth.HioneBluetoothController.toggleBluetooth(enable)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = HioneTealBright,
                            checkedTrackColor = Mascot.color(0xFF0F3238)
                        )
                    )
                }
            }
        }

        if (isBtEnabled) {
            // 2. Paired Gear & Audio DACs
            item {
                Column(Modifier.hioneCard().padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "PAIRED DEVICES (${pairedDevices.size})",
                            color = HioneTealBright,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = if (isScanning) "Searching..." else "+ Scan",
                            color = if (isScanning) Mascot.color(0xFF6FB0FF) else HioneTealBright,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isScanning) Mascot.color(0x226FB0FF) else Mascot.color(0x22FFC56B))
                                .clickable {
                                    if (isScanning) {
                                        com.hione.settings.bluetooth.HioneBluetoothController.stopScan()
                                    } else {
                                        com.hione.settings.bluetooth.HioneBluetoothController.startScan()
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    if (pairedDevices.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(HioneSurface2)
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No paired Bluetooth audio devices.\nTap '+ Scan' to find one.",
                                color = HioneMuted,
                                fontSize = 12.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            pairedDevices.forEach { devItem ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (devItem.isConnected) Mascot.color(0x22FFC56B) else HioneSurface2)
                                        .border(1.dp, if (devItem.isConnected) HioneTealBright else Color.Transparent, RoundedCornerShape(12.dp))
                                        .clickable {
                                            if (!devItem.isConnected && !devItem.isConnecting) {
                                                com.hione.settings.bluetooth.HioneBluetoothController.connectDevice(devItem.device)
                                            }
                                        }
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(34.dp)
                                            .clip(CircleShape)
                                            .background(HioneTeal.copy(alpha = 0.2f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = when (devItem.deviceType) {
                                                DeviceType.AUDIO_HEADSET, DeviceType.AUDIO_DAC -> Icons.Default.Headphones
                                                DeviceType.AUDIO_SPEAKER -> Icons.Default.Speaker
                                                DeviceType.PHONE_WATCH -> Icons.Default.PhoneAndroid
                                                DeviceType.INPUT_KEYBOARD_MOUSE -> Icons.Default.Computer
                                                else -> Icons.Default.Bluetooth
                                            },
                                            contentDescription = null,
                                            tint = if (devItem.isConnected) HioneTealBright else Color.White,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }

                                    Spacer(Modifier.width(12.dp))

                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = devItem.name,
                                            color = Color.White,
                                            fontSize = 13.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = when {
                                                devItem.isConnected -> "Connected"
                                                devItem.isConnecting -> "Connecting..."
                                                else -> devItem.address
                                            },
                                            color = if (devItem.isConnected) HioneTealBright else if (devItem.isConnecting) Color(0xFFFFD54F) else HioneMuted,
                                            fontSize = 10.5.sp
                                        )
                                    }

                                    Spacer(Modifier.width(6.dp))

                                    if (devItem.isConnected) {
                                        Button(
                                            onClick = { com.hione.settings.bluetooth.HioneBluetoothController.disconnectDevice(devItem.device) },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0x33FF5252)),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.height(28.dp)
                                        ) {
                                            Text("Disconnect", color = Color(0xFFFF8A80), fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                                        }
                                    } else if (devItem.isConnecting) {
                                        CircularProgressIndicator(
                                            color = HioneTealBright,
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Button(
                                            onClick = { com.hione.settings.bluetooth.HioneBluetoothController.connectDevice(devItem.device) },
                                            colors = ButtonDefaults.buttonColors(containerColor = HioneTealBright),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.height(28.dp)
                                        ) {
                                            Text("Connect", color = Color.Black, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }

                                    Spacer(Modifier.width(4.dp))

                                    IconButton(
                                        onClick = { com.hione.settings.bluetooth.HioneBluetoothController.unpairDevice(devItem.device) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.DeleteOutline, contentDescription = "Forget", tint = HioneMuted, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 3. Live Discovered Nearby Devices
            item {
                Column(Modifier.hioneCard().padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "NEARBY DEVICES",
                                color = HioneTealBright,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (isScanning) {
                                Spacer(Modifier.width(8.dp))
                                CircularProgressIndicator(
                                    color = HioneTealBright,
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        }

                        Button(
                            onClick = {
                                if (isScanning) {
                                    com.hione.settings.bluetooth.HioneBluetoothController.stopScan()
                                } else {
                                    com.hione.settings.bluetooth.HioneBluetoothController.startScan()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = if (isScanning) Mascot.color(0x336FB0FF) else Mascot.color(0x33FFC56B)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 3.dp),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text(
                                text = if (isScanning) "Stop" else "Scan",
                                color = if (isScanning) Mascot.color(0xFFFF80AB) else HioneTealBright,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    if (discoveredDevices.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(HioneSurface2)
                                .padding(14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (isScanning) "Scanning for headphones, speakers and DACs..." else "Tap '+ Scan' to look for nearby Bluetooth devices.",
                                color = HioneMuted,
                                fontSize = 11.5.sp
                            )
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            discoveredDevices.forEach { devItem ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(HioneSurface2)
                                        .clickable {
                                            com.hione.settings.bluetooth.HioneBluetoothController.pairDevice(devItem.device)
                                        }
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = when (devItem.deviceType) {
                                            DeviceType.AUDIO_HEADSET, DeviceType.AUDIO_DAC -> Icons.Default.Headphones
                                            DeviceType.AUDIO_SPEAKER -> Icons.Default.Speaker
                                            DeviceType.PHONE_WATCH -> Icons.Default.PhoneAndroid
                                            DeviceType.INPUT_KEYBOARD_MOUSE -> Icons.Default.Computer
                                            else -> Icons.Default.Bluetooth
                                        },
                                        contentDescription = null,
                                        tint = HioneTealBright,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(devItem.name, color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${devItem.address} · Signal ${devItem.rssi?.let { "$it dBm" } ?: "—"}", color = HioneMuted, fontSize = 10.sp)
                                    }
                                    Button(
                                        onClick = { com.hione.settings.bluetooth.HioneBluetoothController.pairDevice(devItem.device) },
                                        colors = ButtonDefaults.buttonColors(containerColor = Mascot.color(0x33FFC56B)),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.height(26.dp)
                                    ) {
                                        Text("Pair & Connect", color = HioneTealBright, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 4. Audiophile Hi-Res Bluetooth Codec Engine
            item {
                Column(Modifier.hioneCard().padding(14.dp)) {
                    Text(
                        text = "BLUETOOTH CODECS",
                        color = HioneTealBright,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(10.dp))

                    Text(
                        text = "LDAC bitrate",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        if (aptxEnabled && aacEnabled && ldacQuality.contains("990")) "Locked to maximum. Set again on every connection"
                        else "Lowered by you. ${Mascot.osName} uses this instead of the maximum",
                        color = if (aptxEnabled && aacEnabled && ldacQuality.contains("990")) HioneTealBright else HioneGold,
                        fontSize = 11.sp
                    )
                    Spacer(Modifier.height(6.dp))

                    val ldacOptions = listOf(
                        "Sound Quality (990 kbps)" to "Best quality, up to 96kHz/24-bit",
                        "Balanced (660 kbps)" to "Middle ground",
                        "Connection (330 kbps)" to "Most stable connection",
                        "Adaptive Bitrate" to "Adjusts to signal quality"
                    )

                    ldacOptions.forEach { (opt, desc) ->
                        val isSel = ldacQuality == opt
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSel) HioneTealBright.copy(alpha = 0.15f) else Color.Transparent)
                                .clickable { requestCodecChange(opt, aptxEnabled, aacEnabled, "Set LDAC bitrate to $opt") }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSel,
                                onClick = { requestCodecChange(opt, aptxEnabled, aacEnabled, "Set LDAC bitrate to $opt") },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = HioneTealBright,
                                    unselectedColor = HioneMuted
                                )
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(opt, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text(desc, color = HioneMuted, fontSize = 11.sp)
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = Color(0x22FFFFFF), thickness = 0.8.dp)
                    Spacer(Modifier.height(12.dp))

                    // Qualcomm aptX & aptX HD Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Qualcomm aptX / aptX HD", color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                            Text(if (aptxEnabled) "aptX HD or aptX on headphones that support it (off = AAC/SBC)" else "Turned off by you. aptX-only headphones fall back to AAC/SBC", color = if (aptxEnabled) HioneMuted else HioneGold, fontSize = 11.5.sp)
                        }
                        Switch(
                            checked = aptxEnabled,
                            onCheckedChange = { requestCodecChange(ldacQuality, it, aacEnabled, "Disable Qualcomm aptX / aptX HD") },
                            colors = SwitchDefaults.colors(checkedThumbColor = HioneTealBright, checkedTrackColor = Mascot.color(0xFF0F3238))
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    // AAC Stream Codec Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("AAC", color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                            Text(if (aacEnabled) "For AirPods and similar headphones (off = SBC on AAC-only gear)" else "Turned off by you. AAC-only headphones fall back to SBC", color = if (aacEnabled) HioneMuted else HioneGold, fontSize = 11.5.sp)
                        }
                        Switch(
                            checked = aacEnabled,
                            onCheckedChange = { requestCodecChange(ldacQuality, aptxEnabled, it, "Disable AAC") },
                            colors = SwitchDefaults.colors(checkedThumbColor = HioneTealBright, checkedTrackColor = Mascot.color(0xFF0F3238))
                        )
                    }
                }
            }

            // 5. Hardware Pipeline & RF Telemetry
            item {
                Column(Modifier.hioneCard().padding(14.dp)) {
                    Text(
                        text = "BLUETOOTH DETAILS",
                        color = HioneTealBright,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(10.dp))

                    // Live rows come from the stack; hardware spec rows are static datasheet facts.
                    AboutSpecRow("Active A2DP Codec", activeCodec ?: (if (connectedCount > 0) "connected · codec not reported" else "nothing streaming"))
                    AboutSpecRow("Connected Devices", if (connectedCount > 0) "$connectedCount" else "none")
                    // Static datasheet claims, not probed - say so; the two rows above them ARE live.
                    AboutSpecRow("RF Transceiver (spec)", "Qualcomm WCN3988 (SM6225 companion)")
                    AboutSpecRow("Bluetooth Version (spec)", "Bluetooth 5.0 / BLE")

                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            com.hione.settings.pages.HionePageActivity.open(ctx, "connected")
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = HioneSurface2),
                        border = BorderStroke(1.dp, HioneTeal.copy(alpha = 0.3f)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().height(38.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Tune, contentDescription = null, tint = HioneTealBright, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Device name, USB and NFC", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ----------------------------------------------------
// Section 5: Display & Ambient Light
// ----------------------------------------------------
@Composable
fun DisplayScreen(ctx: Context) {
    val cr = ctx.contentResolver
    // null when Settings.System.screen_brightness cannot be read (shown as "—", not a fake 128).
    var brightness by remember {
        mutableStateOf<Int?>(
            try {
                Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS)
            } catch (_: Throwable) { null }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column(Modifier.hioneCard().padding(14.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("SCREEN BRIGHTNESS", color = HioneTealBright, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("${brightness ?: "--"} / 255", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                Slider(
                    value = (brightness ?: 5).toFloat(),
                    onValueChange = {
                        brightness = it.toInt()
                        try {
                            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, it.toInt())
                        } catch (_: Throwable) {
                            RootShell.execFast("settings put system screen_brightness ${it.toInt()}")
                        }
                    },
                    valueRange = 5f..255f,
                    colors = SliderDefaults.colors(thumbColor = HioneTealBright, activeTrackColor = HioneTeal, inactiveTrackColor = HioneSurface2)
                )
            }
        }

        item {
            var isProtectorMode by remember {
                mutableStateOf(
                    try {
                        Settings.Secure.getInt(cr, "touch_sensitivity_enabled", 0) == 1 ||
                        Settings.System.getInt(cr, "touch_sensitivity_enabled", 0) == 1 ||
                        Settings.System.getInt(cr, "screen_protector_mode", 0) == 1
                    } catch (_: Throwable) { false }
                )
            }

            Column(Modifier.hioneCard().padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "SCREEN PROTECTOR MODE",
                            color = HioneTealBright,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Increases touch sensitivity when using glass screen protectors",
                            color = HioneMuted,
                            fontSize = 10.sp
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = isProtectorMode,
                        onCheckedChange = { enabled ->
                            val v = if (enabled) 1 else 0
                            // Was: one try/catch that swallowed every failure, then an unconditional
                            // "…Enabled" toast and a switch set straight from the tap. If all three
                            // writes were refused the user was still told touch boost was on. Now
                            // each write is tracked and the switch shows what actually persisted.
                            var wrote = false
                            runCatching { Settings.Secure.putInt(cr, "touch_sensitivity_enabled", v) }.onSuccess { wrote = true }
                            runCatching { Settings.System.putInt(cr, "touch_sensitivity_enabled", v) }.onSuccess { wrote = true }
                            runCatching { Settings.System.putInt(cr, "screen_protector_mode", v) }.onSuccess { wrote = true }
                            RootShell.execFast(
                                "settings put secure touch_sensitivity_enabled $v; " +
                                "settings put system touch_sensitivity_enabled $v; " +
                                "settings put system screen_protector_mode $v; " +
                                "setprop persist.sys.screen_protector $v; " +
                                "setprop persist.sys.touch_sensitivity $v"
                            )
                            val readBack = try {
                                Settings.Secure.getInt(cr, "touch_sensitivity_enabled", -1) == v ||
                                Settings.System.getInt(cr, "touch_sensitivity_enabled", -1) == v ||
                                Settings.System.getInt(cr, "screen_protector_mode", -1) == v
                            } catch (_: Throwable) { false }
                            isProtectorMode = if (readBack) enabled else !enabled
                            Toast.makeText(
                                ctx,
                                when {
                                    !wrote || !readBack -> "Could not change screen protector mode. The setting was refused"
                                    enabled -> "touch_sensitivity_enabled = 1 (works if the touch firmware honors it)"
                                    else -> "touch_sensitivity_enabled = 0"
                                },
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = HioneTealBright,
                            uncheckedThumbColor = HioneMuted,
                            uncheckedTrackColor = HioneSurface2
                        )
                    )
                }
            }
        }

        item {
            // Double-tap to wake. The switch shows the saved choice. The touch chip picks it up
            // the next time the screen turns off. See TouchWakeBridge.
            val scope = rememberCoroutineScope()
            var tapWake by remember { mutableStateOf(runCatching { TouchWakeBridge.isOn(ctx) }.getOrDefault(true)) }
            var busy by remember { mutableStateOf(false) }

            Column(Modifier.hioneCard().padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("DOUBLE-TAP TO WAKE", color = HioneTealBright, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(2.dp))
                        Text("Double-tap the screen while it's off to turn it on", color = HioneMuted, fontSize = 10.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = tapWake,
                        enabled = !busy,
                        onCheckedChange = { on ->
                            busy = true
                            scope.launch(Dispatchers.IO) {
                                val r = TouchWakeBridge.set(ctx, on)
                                val saved = runCatching { TouchWakeBridge.isOn(ctx) }.getOrDefault(!on)
                                withContext(Dispatchers.Main) {
                                    tapWake = saved
                                    busy = false
                                    Toast.makeText(
                                        ctx,
                                        when (r) {
                                            TouchWakeBridge.Result.APPLIED -> if (on) "Double-tap to wake is on" else "Double-tap to wake is off"
                                            TouchWakeBridge.Result.SAVED_FOR_REBOOT -> "Saved. It takes effect after a restart"
                                            TouchWakeBridge.Result.FAILED -> "Could not change double-tap to wake"
                                        },
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = HioneTealBright,
                            uncheckedThumbColor = HioneMuted,
                            uncheckedTrackColor = HioneSurface2
                        )
                    )
                }
            }
        }

        item {
            Column(Modifier.hioneCard().padding(14.dp)) {
                Text("DISPLAY SIZE", color = HioneTealBright, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("Makes buttons, touch targets and the rest of the UI bigger", color = HioneMuted, fontSize = 10.sp)
                Spacer(Modifier.height(10.dp))

                val currentDpi = ctx.resources.displayMetrics.densityDpi
                var selectedDpi by remember { mutableIntStateOf(currentDpi) }

                val dpiOptions = listOf(
                    270 to "270 DPI\n(Compact)",
                    320 to "320 DPI\n(Standard)",
                    360 to "360 DPI\n(Native)",
                    400 to "400 DPI\n(Large)",
                    440 to "440 DPI\n(Huge UI)"
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    dpiOptions.forEach { (dpi, label) ->
                        val isSelected = selectedDpi == dpi || (selectedDpi !in dpiOptions.map { it.first } && dpi == 320)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSelected) HioneTealBright.copy(alpha = 0.25f) else HioneSurface2)
                                .border(
                                    1.dp,
                                    if (isSelected) HioneTealBright else Color.Transparent,
                                    RoundedCornerShape(6.dp)
                                )
                                .clickable {
                                    selectedDpi = dpi
                                    applyDisplayDensity(cr, dpi)
                                    Toast.makeText(ctx, "Display size set to $dpi DPI", Toast.LENGTH_SHORT).show()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                color = if (isSelected) HioneTealBright else Color.White,
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                lineHeight = 11.sp
                            )
                        }
                    }
                }
            }
        }

        item {
            Column(Modifier.hioneCard().padding(14.dp)) {
                Text("FONT SIZE", color = HioneTealBright, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("Makes text bigger across apps and the launcher", color = HioneMuted, fontSize = 10.sp)
                Spacer(Modifier.height(10.dp))

                var currentFontScale by remember {
                    mutableFloatStateOf(
                        try {
                            Settings.System.getFloat(cr, Settings.System.FONT_SCALE)
                        } catch (_: Throwable) { 1.0f }
                    )
                }

                val fontScaleOptions = listOf(
                    1.0f to "1.0x\n(Default)",
                    1.15f to "1.15x\n(Large)",
                    1.30f to "1.30x\n(XL Text)",
                    1.45f to "1.45x\n(Huge Text)"
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    fontScaleOptions.forEach { (scale, label) ->
                        val isSelected = Math.abs(currentFontScale - scale) < 0.05f
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSelected) HioneTealBright.copy(alpha = 0.25f) else HioneSurface2)
                                .border(
                                    1.dp,
                                    if (isSelected) HioneTealBright else Color.Transparent,
                                    RoundedCornerShape(6.dp)
                                )
                                .clickable {
                                    currentFontScale = scale
                                    applyFontScale(cr, scale)
                                    Toast.makeText(ctx, "Font size set to ${scale}x", Toast.LENGTH_SHORT).show()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                color = if (isSelected) HioneTealBright else Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }

        item {
            Column(Modifier.hioneCard().padding(14.dp)) {
                Text("SYSTEM NAVIGATION MODE", color = HioneTealBright, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Sets Settings.Secure navigation_mode. Swapping the navbar OVERLAY itself needs root, so the bar may not change appearance even when the value takes.",
                    color = HioneMuted, fontSize = 10.sp, lineHeight = 13.sp
                )
                Spacer(Modifier.height(10.dp))

                // null = navigation_mode not set on this device → neither option pre-selected
                // (it used to show "Gesture" selected from a hard-coded default of 2).
                var isGestureNav by remember {
                    mutableStateOf<Boolean?>(
                        try {
                            Settings.Secure.getString(cr, "navigation_mode")?.trim()?.toIntOrNull()?.let { it == 2 }
                        } catch (_: Throwable) { null }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Gesture Navigation Option
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isGestureNav == true) HioneTealBright.copy(alpha = 0.25f) else HioneSurface2)
                            .border(
                                1.dp,
                                if (isGestureNav == true) HioneTealBright else Color.Transparent,
                                RoundedCornerShape(8.dp)
                            )
                            .clickable {
                                // Was: RootShell-ONLY (`cmd overlay ...` / `settings put secure ...`)
                                // plus an immediate selection and a "...Enabled" toast. There is no
                                // su on this device, so every one of those commands was a silent
                                // no-op while the UI reported the mode as applied. Write through the
                                // root-free ContentResolver path, then report what actually stuck.
                                val ok = applyNavigationMode(cr, gesture = true)
                                isGestureNav = readNavigationMode(cr)
                                Toast.makeText(
                                    ctx,
                                    if (ok) "navigation_mode set to gesture (2)"
                                    else "Could not change navigation mode. The write was refused",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            .padding(vertical = 10.dp, horizontal = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("GESTURE NAV", color = if (isGestureNav == true) HioneTealBright else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(2.dp))
                            Text("Swipe from the edge", color = HioneMuted, fontSize = 9.sp, textAlign = TextAlign.Center)
                        }
                    }

                    // 3-Button Navigation Option
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isGestureNav == false) HioneTealBright.copy(alpha = 0.25f) else HioneSurface2)
                            .border(
                                1.dp,
                                if (isGestureNav == false) HioneTealBright else Color.Transparent,
                                RoundedCornerShape(8.dp)
                            )
                            .clickable {
                                // Same fix as the gesture option above: real write, then report the
                                // value the system actually holds rather than the one we asked for.
                                val ok = applyNavigationMode(cr, gesture = false)
                                isGestureNav = readNavigationMode(cr)
                                Toast.makeText(
                                    ctx,
                                    if (ok) "navigation_mode set to 3-button (0)"
                                    else "Could not change navigation mode. The write was refused",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            .padding(vertical = 10.dp, horizontal = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("3-BUTTON BAR", color = if (isGestureNav == false) HioneTealBright else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(2.dp))
                            Text("Back, home, recents", color = HioneMuted, fontSize = 9.sp, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }

        item {
            Column(Modifier.hioneCard().padding(14.dp)) {
                Text("OTHER DISPLAY SETTINGS", color = HioneTealBright, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))

                SettingsLinkRow(
                    icon = Icons.Default.DisplaySettings,
                    title = "More display settings",
                    subtitle = "Screen timeout, dark theme, Night Light, auto-rotate, wallpaper",
                    onClick = { com.hione.settings.pages.HionePageActivity.open(ctx, "display_more") }
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

fun Modifier.hioneEdgeSwipeBack(
    edgeZoneDp: androidx.compose.ui.unit.Dp = 32.dp,
    minSwipeDp: androidx.compose.ui.unit.Dp = 36.dp,
    onBack: () -> Unit
): Modifier = this.pointerInput(onBack) {
    awaitEachGesture {
        val down = awaitFirstDown(pass = PointerEventPass.Initial)
        val startX = down.position.x
        val edgeZonePx = edgeZoneDp.toPx()
        val minSwipePx = minSwipeDp.toPx()
        val isLeft = startX <= edgeZonePx
        val isRight = startX >= (size.width - edgeZonePx)

        if (isLeft || isRight) {
            var triggered = false
            while (true) {
                val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                val change = event.changes.firstOrNull() ?: break
                if (!change.pressed) break

                val deltaX = change.position.x - startX
                if (isLeft && deltaX > minSwipePx && !triggered) {
                    triggered = true
                    change.consume()
                    onBack()
                    break
                } else if (isRight && deltaX < -minSwipePx && !triggered) {
                    triggered = true
                    change.consume()
                    onBack()
                    break
                }
            }
        }
    }
}

// ----------------------------------------------------
// Section 6: Battery & Power Observatory
// ----------------------------------------------------
@Composable
fun BatteryScreen(ctx: Context) {
    val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
    // Primary source = the sticky ACTION_BATTERY_CHANGED broadcast (BatteryService-fed, always
    // correct). BATTERY_PROPERTY_CAPACITY goes through the health HAL directly and returns 0
    // on this vendor for normal apps — that was the "battery stuck / not reading" bug.
    // Every value below comes from the sticky broadcast / BatteryManager; anything the HAL does not
    // report is shown as "—" (it used to print 0% / 0 mA as if measured). Re-read every 2 s so the
    // screen is actually live, as its section title promises.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(2000); tick++ } }
    val sticky = remember(tick) {
        try { ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)) } catch (_: Throwable) { null }
    }
    val pct: Int? = remember(sticky) {
        val lvl = sticky?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (lvl >= 0 && scale > 0) (lvl * 100) / scale
        else bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 1..100 }
    }
    // CURRENT_NOW: Int.MIN_VALUE = unsupported; 0 on this vendor means "not reported", not 0 mA.
    val currentMa: Int? = remember(tick) {
        bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            ?.takeIf { it != Int.MIN_VALUE && it != 0 }?.let { it / 1000 }
    }
    val voltageMv: Int? = remember(sticky) { sticky?.getIntExtra(android.os.BatteryManager.EXTRA_VOLTAGE, -1)?.takeIf { it > 0 } }
    val tempC: Float? = remember(sticky) { sticky?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE && it != 0 }?.let { it / 10f } }
    val batHealth = remember(sticky) {
        when (sticky?.getIntExtra(android.os.BatteryManager.EXTRA_HEALTH, 0) ?: 0) { 2 -> "Good"; 3 -> "Overheat"; 4 -> "Dead"; 5 -> "Over-volt"; 6 -> "Failure"; 7 -> "Cold"; else -> "—" }
    }
    val batStatus = remember(sticky) {
        when (sticky?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, 0) ?: 0) { 2 -> "Charging"; 3 -> "Discharging"; 4 -> "Not charging"; 5 -> "Full"; else -> "—" }
    }
    val plugged = remember(sticky) {
        when (sticky?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0) { 1 -> "AC"; 2 -> "USB"; 4 -> "Wireless"; 8 -> "Dock"; 0 -> "Unplugged"; else -> "—" }
    }
    val technology = remember(sticky) { sticky?.getStringExtra(android.os.BatteryManager.EXTRA_TECHNOLOGY)?.takeIf { it.isNotBlank() } ?: "—" }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Column(Modifier.hioneHeroCard().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("BATTERY", color = HioneTealBright, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(pct?.let { "$it%" } ?: "—%", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Black)
            }
            Icon(
                Icons.Default.BatteryChargingFull,
                contentDescription = null,
                // Was "(pct ?: 100) > 20": an UNKNOWN level was tinted as a healthy battery.
                tint = when { pct == null -> HioneMuted; pct > 20 -> HioneTealBright; else -> HionePinkBright },
                modifier = Modifier.size(48.dp)
            )
        }

        Spacer(Modifier.height(14.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricPill(label = "CURRENT", value = currentMa?.let { "$it mA" } ?: "—")
            MetricPill(label = "VOLTAGE", value = voltageMv?.let { String.format("%.2f V", it / 1000f) } ?: "—")
            MetricPill(label = "TEMP", value = tempC?.let { String.format("%.1f °C", it) } ?: "—")
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricPill(label = "HEALTH", value = batHealth)
            MetricPill(label = "STATUS", value = batStatus)
            MetricPill(label = "SOURCE", value = plugged)
        }
        Spacer(Modifier.height(8.dp))
        Text("Chemistry: $technology · a dash means the battery HAL did not report it", color = HioneMuted, fontSize = 10.5.sp)
    }
    BatteryControls(ctx, tick)
    }
}

/** Battery saver and app battery controls that used to live only in stock Settings. */
@Composable
private fun BatteryControls(ctx: Context, tick: Int) {
    val pm = remember { ctx.getSystemService(android.os.PowerManager::class.java) }
    var refresh by remember { mutableIntStateOf(0) }
    val saver = remember(tick, refresh) { try { pm.isPowerSaveMode } catch (_: Throwable) { null } }
    com.hione.settings.ui.Section("Power") {
        // PowerManager.setPowerSaveModeEnabled is @SystemApi (DEVICE_POWER / POWER_SAVER).
        com.hione.settings.ui.ToggleRow("Battery Saver", "Limits background activity and some visual effects", saver) {
            if (com.hione.settings.sys.Hidden.call(pm, "setPowerSaveModeEnabled", it) != true) com.hione.settings.ui.toast(ctx, "Battery Saver was not changed")
            refresh++
        }
        com.hione.settings.ui.NavRow("App battery use", "Which apps may run unrestricted in the background") {
            com.hione.settings.pages.HionePageActivity.open(ctx, "access/battery")
        }
        com.hione.settings.pages.StockRow("Battery usage history", ".Settings\$PowerUsageSummaryActivity")
    }
    Spacer(Modifier.height(12.dp))
    ChargeLimitControls(ctx, tick)
}

/**
 * Charge limit. Stored in Settings.Global hione_charge_limit (0 = off, 80, 85, 90). The HioneOS
 * system bridge (com.hione.sysbridge, persistent) watches it and the battery, and reports whether
 * it is holding in hione_charge_hold. See hione-sysbridge ChargeLimiter.kt for what a hold does.
 */
@Composable
private fun ChargeLimitControls(ctx: Context, tick: Int) {
    val cr = ctx.contentResolver
    var refresh by remember { mutableIntStateOf(0) }
    val limit = remember(tick, refresh) {
        try { Settings.Global.getInt(cr, "hione_charge_limit", 0) } catch (_: Throwable) { 0 }
    }
    val holding = remember(tick, refresh) {
        try { Settings.Global.getInt(cr, "hione_charge_hold", 0) == 1 } catch (_: Throwable) { false }
    }
    com.hione.settings.ui.Section(
        "Charge limit",
        "Stops topping up the battery at the level you pick and starts again 5% below it. " +
            "Good for a player that lives on a charger."
    ) {
        for ((value, label) in listOf(0 to "Off", 80 to "80%", 85 to "85%", 90 to "90%")) {
            com.hione.settings.ui.RadioRow(label, selected = limit == value) {
                val ok = try { Settings.Global.putInt(cr, "hione_charge_limit", value) } catch (_: Throwable) { false }
                if (!ok) com.hione.settings.ui.toast(ctx, "Charge limit was not changed")
                refresh++
            }
        }
        if (limit != 0) {
            Spacer(Modifier.height(6.dp))
            com.hione.settings.ui.BodyText(
                if (holding) "Holding now. The charger is cut to its lowest setting (100 mA), so the level stays about where it is with the screen off and may drop slowly with it on."
                else "Charging normally. It holds once the battery reaches $limit%."
            )
        }
    }
}

// ----------------------------------------------------
// Section 7: Apps & Storage
// ----------------------------------------------------
@Composable
fun StorageAppsScreen(ctx: Context) {
    Column(Modifier.hioneCard().padding(14.dp)) {
        Text("APPS & STORAGE", color = HioneTealBright, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))

        SettingsLinkRow(
            icon = Icons.Default.Apps,
            title = "Apps",
            subtitle = "App info, permissions, notifications, storage and force stop",
            onClick = { com.hione.settings.pages.HionePageActivity.open(ctx, "apps") }
        )

        Spacer(Modifier.height(8.dp))

        SettingsLinkRow(
            icon = Icons.Default.SdCard,
            title = "Storage & microSD card",
            subtitle = "Internal storage and the memory card",
            onClick = { com.hione.settings.pages.HionePageActivity.open(ctx, "storage") }
        )

        Spacer(Modifier.height(8.dp))

        SettingsLinkRow(
            icon = Icons.Default.AppSettingsAlt,
            title = "Default apps",
            subtitle = "Home, browser, assistant and SMS",
            onClick = { com.hione.settings.pages.HionePageActivity.open(ctx, "default_apps") }
        )

        Spacer(Modifier.height(8.dp))

        SettingsLinkRow(
            icon = Icons.Default.Security,
            title = "Special app access",
            subtitle = "Overlays, all files, usage access, battery and more",
            onClick = { com.hione.settings.pages.HionePageActivity.open(ctx, "special") }
        )
    }
}

// ----------------------------------------------------
// Section 8: About HioneOS
// ----------------------------------------------------
@Composable
fun AboutScreen(ctx: Context) {
    // Everything here is read from the running system (SystemProperties / Build / kernel). A value
    // that is not set on this image prints "unknown" — the old screen printed a literal "0.1.0",
    // "Android 14 GKI", "M500_MIKU_4G", "Snapdragon 680 8-Core" and "Magisk Privileged" whatever
    // the device actually was.
    fun prop(key: String): String? = try {
        (Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            .invoke(null, key) as? String)?.trim()?.takeIf { it.isNotEmpty() }
    } catch (_: Throwable) { null }
    val hioneosVersion = prop("ro.hioneos.version")
    val model = android.os.Build.MODEL?.takeIf { it.isNotBlank() && !it.contains("qssi", ignoreCase = true) }
    val androidRelease = android.os.Build.VERSION.RELEASE ?: "unknown"
    val buildDisplay = android.os.Build.DISPLAY?.takeIf { it.isNotBlank() } ?: "unknown"
    val socModel = (if (android.os.Build.VERSION.SDK_INT >= 31) android.os.Build.SOC_MODEL?.takeIf { it.isNotBlank() && it != android.os.Build.UNKNOWN } else null)
        ?: prop("ro.soc.model") ?: prop("ro.board.platform") ?: "unknown"
    val cores = Runtime.getRuntime().availableProcessors()
    val kernel = System.getProperty("os.version")?.takeIf { it.isNotBlank() } ?: "unknown"
    val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
    val uid = android.os.Process.myUid()
    val hasSecure = ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == android.content.pm.PackageManager.PERMISSION_GRANTED
    val privilege = when {
        uid == 1000 -> "system uid 1000 · platform-signed"
        hasSecure -> "uid $uid · platform permissions granted"
        else -> "uid $uid · standard app permissions"
    }
    val gkiTag = prop("ro.kernel.version")?.let { " (GKI $it)" } ?: ""

    // Scrolls: the page is taller than the M500's 720x1280 screen, and without this everything
    // below the Blackjack picture (its text, the Monitor link, Open Home) was out of reach.
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 16.dp).hioneCard().padding(16.dp)) {
        Text("ABOUT ${Mascot.osNameUpper}", color = HioneTealBright, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(HioneTealBright.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Text("39", color = HioneTealBright, fontSize = 24.sp, fontWeight = FontWeight.Black)
            }

            Spacer(Modifier.width(14.dp))
            Column {
                Text(model ?: android.os.Build.DEVICE ?: "unknown device", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("${Mascot.osName} ${hioneosVersion?.let { "v$it" } ?: "(version prop not set)"} · Android $androidRelease", color = HioneTeal, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(16.dp))

        AboutSpecRow("Device Identity", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (${android.os.Build.DEVICE} / ${android.os.Build.PRODUCT})")
        AboutSpecRow("${Mascot.osName} Version", hioneosVersion ?: "unknown (ro.hioneos.version not set)")
        AboutSpecRow("Android Release", "$androidRelease (API ${android.os.Build.VERSION.SDK_INT})$gkiTag")
        AboutSpecRow("Build", buildDisplay)
        AboutSpecRow("SoC", "$socModel · $cores cores")
        // Not read from the running system like the rows around them - these are datasheet facts
        // about the board, so they are labelled as such rather than sitting in a list the section
        // header calls "as reported by the running system".
        AboutSpecRow("DAC Hardware (spec)", "Dual Cirrus Logic CS43198 MasterHIFI™")
        AboutSpecRow("RGB Controller (spec)", "SGM31324 LED driver · indicator inactive on this unit")
        AboutSpecRow("Linux Kernel", "$kernel ($abi)")
        AboutSpecRow("Privilege", privilege)
        AboutSpecRow("Security Patch", android.os.Build.VERSION.SECURITY_PATCH ?: "unknown")
        // Read from the running kernel: our own driver shows as loaded only when its module is.
        val blackjackLoaded = DacSettingsLink.blackjackLoaded()
        AboutSpecRow("Audio Driver", if (blackjackLoaded) "Blackjack (ours) · blackjack_codec + blackjack_hooks"
            else "HiBy cs43198 · Blackjack, our own driver, is not in this build yet")

        Spacer(Modifier.height(14.dp))

        // Blackjack: the clean-room replacement for HiBy's DAC driver. The name is a Bender joke.
        Text("BLACKJACK", color = HioneTealBright, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.height(8.dp))
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(R.drawable.blackjack_meme),
            contentDescription = "Bender: I'm gonna go build my own audio driver. With blackjack and hook(er)s! In fact, forget the driver!",
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)),
            contentScale = androidx.compose.ui.layout.ContentScale.FillWidth
        )
        Spacer(Modifier.height(8.dp))
        Text("HiBy's DAC driver runs both DACs at 192 kHz for every track. HiBy lets us replace anything but won't share source, " +
            "so we're writing our own, clean room: blackjack_codec for the CS43198s and blackjack_hooks for the platform glue. " +
            "The goal is native rate. A driver is mostly a pile of hooks anyway.",
            color = HioneTeal, fontSize = 11.sp)

        Spacer(Modifier.height(10.dp))
        SettingsLinkRow(
            icon = Icons.Default.Memory,
            title = "Open Blackjack Monitor",
            subtitle = if (blackjackLoaded) "See the driver live: output, power, rate and register writes"
                else "See which DAC driver runs and what it is doing",
            onClick = { DacSettingsLink.openBlackjackMonitor(ctx) }
        )

        Spacer(Modifier.height(14.dp))

        // This row used to write "hione_onboarding_completed=false" into com.hione.settings' OWN
        // SharedPreferences and then go Home. The wizard flag lives in com.hione.launcher's sandbox,
        // which this app cannot touch, so the wizard never re-opened: it was a dressed-up Home
        // button that claimed to re-run provisioning. The dead write is gone and the row now says
        // exactly what it does (the launcher has no re-run entry point to call).
        SettingsLinkRow(
            icon = Icons.Default.Home,
            title = "Open ${Mascot.osName} Home",
            subtitle = "Run the first-boot setup again from the launcher",
            onClick = {
                try {
                    val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    ctx.startActivity(homeIntent)
                } catch (_: Throwable) {}
            }
        )

        Spacer(Modifier.height(8.dp))

        SettingsLinkRow(
            icon = Icons.Default.Code,
            title = "Developer options",
            subtitle = "USB debugging, wireless debugging, animation scales",
            onClick = { com.hione.settings.pages.HionePageActivity.open(ctx, "developer") }
        )

        Spacer(Modifier.height(8.dp))

        SettingsLinkRow(
            icon = Icons.Default.Gavel,
            title = "Legal information",
            subtitle = "Open source licenses. Opens the stock Settings page.",
            onClick = { com.hione.settings.pages.openStockPage(ctx, ".SettingsLicenseActivity", "android.settings.LICENSE") }
        )
    }
}

// ----------------------------------------------------
// Helper Composables
// ----------------------------------------------------
@Composable
fun SettingsLinkRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(HioneSurface2)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = HioneTealBright, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = HioneMuted, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = null,
            tint = HioneMuted,
            modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = 180f }
        )
    }
}

@Composable
fun MetricPill(label: String, value: String) {
    Column(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(HioneSurface2)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, color = HioneMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(2.dp))
        Text(value, color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun AboutSpecRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = HioneMuted, fontSize = 12.sp)
        Text(value, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** The value the system actually holds; null when navigation_mode is unset/unreadable. */
fun readNavigationMode(cr: android.content.ContentResolver): Boolean? = try {
    Settings.Secure.getString(cr, "navigation_mode")?.trim()?.toIntOrNull()?.let { it == 2 }
} catch (_: Throwable) { null }

/**
 * Root-free navigation_mode write, VERIFIED. Returns true only when the setting reads back as the
 * requested value. RootShell is kept as an optional extra for rooted units (it also flips the
 * navbar overlay, which the ContentResolver path cannot do) but is never treated as success.
 */
fun applyNavigationMode(cr: android.content.ContentResolver, gesture: Boolean): Boolean {
    val want = if (gesture) 2 else 0
    runCatching { Settings.Secure.putInt(cr, "navigation_mode", want) }
    if (gesture) {
        runCatching { Settings.Secure.putInt(cr, "back_gesture_inset_scale_left", 2) }
        runCatching { Settings.Secure.putInt(cr, "back_gesture_inset_scale_right", 2) }
    }
    RootShell.execFast(
        if (gesture)
            "cmd overlay enable com.android.internal.systemui.navbar.gestural; " +
            "cmd overlay disable com.android.internal.systemui.navbar.threebutton"
        else
            "cmd overlay enable com.android.internal.systemui.navbar.threebutton; " +
            "cmd overlay disable com.android.internal.systemui.navbar.gestural"
    )
    return readNavigationMode(cr) == gesture
}

fun applyDisplayDensity(cr: android.content.ContentResolver, dpi: Int) {
    android.util.Log.i("HioneSettings", "applyDisplayDensity: setting density to $dpi")
    try {
        val wmClass = Class.forName("android.view.WindowManagerGlobal")
        val getWm = wmClass.getMethod("getWindowManagerService")
        val wmService = getWm.invoke(null)
        if (wmService != null) {
            var invoked = false
            for (m in wmService.javaClass.methods) {
                if (m.name.startsWith("setForcedDisplayDensity")) {
                    m.isAccessible = true
                    val paramTypes = m.parameterTypes
                    android.util.Log.i("HioneSettings", "Found method: ${m.name} with params: ${paramTypes.map { it.simpleName }}")
                    try {
                        when (paramTypes.size) {
                            2 -> {
                                m.invoke(wmService, 0, dpi)
                                invoked = true
                                android.util.Log.i("HioneSettings", "Invoked 2-param method successfully")
                            }
                            3 -> {
                                m.invoke(wmService, 0, dpi, 0)
                                invoked = true
                                android.util.Log.i("HioneSettings", "Invoked 3-param method successfully")
                            }
                        }
                    } catch (invokeEx: Throwable) {
                        android.util.Log.e("HioneSettings", "Method invocation threw: ${invokeEx.message}", invokeEx)
                    }
                    if (invoked) break
                }
            }
        }
    } catch (e: Throwable) {
        android.util.Log.e("HioneSettings", "WindowManagerGlobal density reflection: ${e.message}", e)
    }
    try {
        Settings.Secure.putString(cr, "display_density_forced", dpi.toString())
        Settings.Secure.putInt(cr, "display_density_forced", dpi)
    } catch (_: Throwable) {}
    RootShell.execFast("wm density $dpi")
}

fun applyFontScale(cr: android.content.ContentResolver, scale: Float) {
    try {
        val amClass = Class.forName("android.app.ActivityManager")
        val getService = amClass.getMethod("getService")
        val am = getService.invoke(null)
        if (am != null) {
            val getConf = am.javaClass.getMethod("getConfiguration")
            val config = getConf.invoke(am) as android.content.res.Configuration
            config.fontScale = scale
            val updateConf = am.javaClass.getMethod(
                "updatePersistentConfiguration",
                android.content.res.Configuration::class.java
            )
            updateConf.isAccessible = true
            updateConf.invoke(am, config)
        }
    } catch (e: Throwable) {
        android.util.Log.w("HioneSettings", "ActivityManager fontScale reflection: ${e.message}")
    }
    try {
        Settings.System.putFloat(cr, Settings.System.FONT_SCALE, scale)
    } catch (_: Throwable) {}
    RootShell.execFast("settings put system font_scale $scale")
}



/** Small colored status dot, used in place of the old emoji circles. */
@Composable
fun StatusDot(color: Color, size: androidx.compose.ui.unit.Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}
