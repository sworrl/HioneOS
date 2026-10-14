package com.hione.player

import android.app.ActivityManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import com.hione.player.bluetooth.*
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.hione.player.*
import com.hione.player.network.HioneNetworkObservatoryModal
import com.hione.player.ui.HioneBackButton
import com.hione.player.ui.HioneTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HioneSettingsActivity : ComponentActivity() {

    private fun setupSystemBars() {
        try {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
            val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            // Never the stock nav bar: our accessibility home pill IS the navigation on this
            // device, and showing both puts a dead white pill next to the live one.
            insetsController.hide(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
            insetsController.show(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            insetsController.isAppearanceLightStatusBars = false
            insetsController.isAppearanceLightNavigationBars = false
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            window.statusBarColor = android.graphics.Color.TRANSPARENT
        } catch (_: Throwable) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashSentinel.install(this)
        super.onCreate(savedInstanceState)
        setupSystemBars()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = HioneCyan,
                    secondary = HioneNeonPink,
                    background = CyberDarkBg,
                    surface = Mascot.color(0xF50C0D10),
                    surfaceVariant = Mascot.color(0xFF071E28),
                    onPrimary = Color.Black,
                    onSecondary = Color.Black,
                    onBackground = Color.White,
                    onSurface = Color.White
                )
            ) {
                HioneSettingsScreen(onBack = { finish() })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        setupSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) setupSystemBars()
    }
}

data class SettingsCategoryItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val badge: String? = null,
    val accentColor: Color = HioneCyan,
    val onClick: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HioneSettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as? ComponentActivity
    var searchQuery by remember { mutableStateOf("") }
    var chibiReaction by remember { mutableStateOf("Hi there") }

    var isBrainModalOpen by remember { mutableStateOf(false) }
    var isMonitorModalOpen by remember { mutableStateOf(false) }
    var isWifiStationOpen by remember { mutableStateOf(false) }
    var isAboutDeviceOpen by remember { mutableStateOf(activity?.intent?.getBooleanExtra("open_about_device", false) == true) }
    var isDevOptionsOpen by remember { mutableStateOf(activity?.intent?.getBooleanExtra("open_dev_options", false) == true) }
    var isDisplayModalOpen by remember { mutableStateOf(false) }
    var isBluetoothModalOpen by remember { mutableStateOf(false) }
    var isStorageModalOpen by remember { mutableStateOf(false) }
    var isAppsModalOpen by remember { mutableStateOf(false) }
    var isGmsModalOpen by remember { mutableStateOf(false) }
    var isSecurityModalOpen by remember { mutableStateOf(false) }
    var isLicenseModalOpen by remember { mutableStateOf(false) }

    val chibiReactions = listOf(
        "Hi there",
        "Dual CS43198 DAC",
        "Plays up to DSD256",
        "Search is at the top",
        "Lossless playback",
        "Pocket lock ready",
        "Data SIM ready",
        "Developer options ready"
    )

    val categories = remember {
        listOf(
            SettingsCategoryItem(
                id = "network",
                title = "Network & internet",
                subtitle = "Mobile, Wi-Fi, hotspot & radio diagnostics",
                icon = Icons.Default.Wifi,
                badge = "Wi-Fi 5G",
                accentColor = HioneCyan,
                onClick = { isWifiStationOpen = true }
            ),
            SettingsCategoryItem(
                id = "bluetooth",
                title = "Bluetooth",
                subtitle = "Bluetooth, pairing, LDAC, aptX HD & AAC codecs",
                icon = Icons.Default.Bluetooth,
                badge = "LDAC",
                accentColor = Color(0xFF2979FF),
                onClick = { isBluetoothModalOpen = true }
            ),
            SettingsCategoryItem(
                id = "usb",
                title = "USB Preferences",
                subtitle = "USB DAC mode, in the Hardware app",
                icon = Icons.Default.Usb,
                badge = "UAC2",
                accentColor = Mascot.color(0xFFFFC56B),
                onClick = { DacSettingsLink.open(ctx) }
            ),
            SettingsCategoryItem(
                id = "audio",
                title = "DAC settings",
                subtitle = "Filter, gain, DRE and high power, in the Hardware app",
                icon = Icons.Default.Headphones,
                badge = "CS43198",
                accentColor = Color(0xFF7C4DFF),
                onClick = { DacSettingsLink.open(ctx) }
            ),
            SettingsCategoryItem(
                id = "fn_lock",
                title = "Hardware Keys & Wheel",
                subtitle = "Fn switch, volume wheel and key mapping",
                icon = Icons.Default.Tune,
                badge = "Hardware",
                accentColor = Color(0xFF00E676),
                onClick = { ctx.startActivity(Intent(ctx, FnLockSettingsActivity::class.java)) }
            ),
            SettingsCategoryItem(
                id = "apps",
                title = "Apps & Permissions",
                subtitle = "Installed apps, default apps and storage",
                icon = Icons.Default.Apps,
                accentColor = Color(0xFFFF9100),
                onClick = { isAppsModalOpen = true }
            ),
            SettingsCategoryItem(
                id = "battery",
                title = "Battery & power",
                subtitle = "Battery gauge (CW2015) and charger (MP2731) readings",
                icon = Icons.Default.BatteryChargingFull,
                badge = "CW2015",
                accentColor = Color(0xFF00E676),
                onClick = {
                    val intent = ctx.packageManager.getLaunchIntentForPackage("com.hione.launcher")?.apply {
                        putExtra("open_battery", true)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    }
                    if (intent != null) try { ctx.startActivity(intent) } catch (_: Throwable) {}
                }
            ),
            SettingsCategoryItem(
                id = "storage",
                title = "Storage",
                subtitle = "MicroSD and internal storage",
                icon = Icons.Default.SdCard,
                accentColor = Mascot.color(0xFFFFC56B),
                onClick = { isStorageModalOpen = true }
            ),
            SettingsCategoryItem(
                id = "sound",
                title = "Sound & Volume",
                subtitle = "Volume HUD, output routing and sound profiles",
                icon = Icons.Default.VolumeUp,
                accentColor = HioneCyan,
                onClick = { com.hione.player.volume.HioneVolumeManager.triggerHud(ctx) }
            ),
            SettingsCategoryItem(
                id = "display",
                title = "Display",
                subtitle = "Brightness, screen timeout, dark theme & refresh",
                icon = Icons.Default.BrightnessMedium,
                accentColor = Color(0xFFFFD600),
                onClick = { isDisplayModalOpen = true }
            ),
            SettingsCategoryItem(
                id = "security",
                title = "Security & Screen Lock",
                subtitle = "Lock screen effects, credentials and keys",
                icon = Icons.Default.Lock,
                accentColor = Color(0xFFE040FB),
                onClick = { isSecurityModalOpen = true }
            ),
            SettingsCategoryItem(
                id = "gms_manager",
                title = "Google Services (GMS)",
                subtitle = "Turn Play Services, Play Store, Chrome and Pixel apps on or off",
                icon = Icons.Default.CloudSync,
                badge = "GMS Controls",
                accentColor = Color(0xFF4285F4),
                onClick = { isGmsModalOpen = true }
            ),
            SettingsCategoryItem(
                id = "license",
                title = "License & Entitlement",
                subtitle = "Remote allow/disallow status, signed verdicts, device ID & owner override",
                icon = Icons.Default.VerifiedUser,
                badge = if (com.hione.player.entitlement.EntitlementConfig.isConfigured) "Signed" else "Not configured",
                accentColor = Color(0xFFFFD740),
                onClick = { isLicenseModalOpen = true }
            ),
            SettingsCategoryItem(
                id = "dev_options",
                title = "Developer Options & ADB",
                subtitle = "Wireless ADB on port 5555, USB debugging, root and animation scales",
                icon = Icons.Default.DeveloperMode,
                badge = "ADB 5555",
                accentColor = Color(0xFF00E676),
                onClick = { isDevOptionsOpen = true }
            ),
            SettingsCategoryItem(
                id = "brain",
                title = "Watchdog",
                subtitle = "Process health, daemon monitors and memory checks",
                icon = Icons.Default.Shield,
                badge = "Bones: 8",
                accentColor = Color(0xFF00E676),
                onClick = { isBrainModalOpen = true }
            ),
            SettingsCategoryItem(
                id = "ingress",
                title = "Network sync",
                subtitle = "Rsync transfers, worker queues and card sync",
                icon = Icons.Default.Sync,
                badge = "Port 8787",
                accentColor = HioneNeonPink,
                onClick = { isMonitorModalOpen = true }
            ),
            SettingsCategoryItem(
                id = "device_info",
                title = "About HiBy M500 DAP",
                // FAKE-DATA FIX: was "Snapdragon 665 · Dual CS43131 · Android 13 Core · Kernel",
                // a spec claim on the menu row with the wrong DAC part and the wrong Android
                // version. Describe the screen instead; the screen itself reads the real values.
                subtitle = "SoC, RAM, panel, firmware, kernel and IDs, read from the OS",
                icon = Icons.Default.Info,
                badge = "M500 DAP",
                accentColor = HioneCyan,
                onClick = { isAboutDeviceOpen = true }
            )
        )
    }

    val filteredCategories = remember(searchQuery) {
        if (searchQuery.isBlank()) categories
        else categories.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
            it.subtitle.contains(searchQuery, ignoreCase = true) ||
            (it.badge?.contains(searchQuery, ignoreCase = true) == true)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(CyberDarkBg)
    ) {
        Image(
            painter = mascotPainter(id = R.drawable.hione_bg_page1),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Mascot.color(0xEE0C0D10),
                            Mascot.color(0xCC0C0D10),
                            Mascot.color(0xF00C0D10)
                        )
                    )
                )
        )

        Column(
            Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(top = 6.dp)
        ) {
            // ============================================================
            // TOP HEADER BAR (STOCK HIBY DISPLAY STYLE)
            // ============================================================
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HioneBackButton(onClick = onBack)

                Spacer(Modifier.width(10.dp))

                Text(
                    text = "Settings",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = AudiowideFont,
                    letterSpacing = 0.5.sp,
                    modifier = Modifier.weight(1f)
                )

                // Interactive Animated Chibi Hione Companion Header Badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Mascot.color(0x44FFC56B))
                        .border(1.dp, HioneCyan, RoundedCornerShape(16.dp))
                        .clickable {
                            chibiReaction = chibiReactions.random()
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Image(
                        painter = mascotPainter(id = R.drawable.ic_search_head_hione),
                        contentDescription = "Chibi Companion",
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = chibiReaction,
                        color = HioneCyan,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            // ============================================================
            // STOCK HIBY SEARCH SETTINGS PILL BAR
            // ============================================================
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Mascot.color(0xEE0D222A))
                    .border(1.dp, CyberGlassBorder, RoundedCornerShape(22.dp))
                    .padding(horizontal = 12.dp, vertical = 2.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = HioneCyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    TextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search settings", color = HioneTextSecondary, fontSize = 13.sp) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear", tint = HioneCyan, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ============================================================
            // STOCK HIBY SQUARE HARDWARE BOXES / CATEGORIES LIST
            // ============================================================
            LazyColumn(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredCategories, key = { it.id }) { item ->
                    Cyber3dSettingsCard(item = item)
                }

                item {
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            val legacyIntent = ctx.packageManager.getLaunchIntentForPackage("com.android.settings")
                                ?: Intent(Settings.ACTION_SETTINGS).setPackage("com.android.settings")
                            try { ctx.startActivity(legacyIntent) } catch (_: Throwable) {
                                Toast.makeText(ctx, "Android Settings not found", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.08f)),
                        border = BorderStroke(1.dp, CyberGlassBorder),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Open stock Android Settings", color = HioneTextSecondary, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(16.dp))
                    // Bottom spacing padding for OS-wide Hione gesture overlay
                    Spacer(Modifier.height(18.dp))
                }
            }

        }

        // Modals
        if (isAboutDeviceOpen) {
            HioneAboutDeviceModal(
                onDismissRequest = { isAboutDeviceOpen = false },
                onOpenDevOptions = {
                    isAboutDeviceOpen = false
                    isDevOptionsOpen = true
                }
            )
        }
        if (isDevOptionsOpen) {
            HioneDeveloperOptionsModal(
                onDismissRequest = { isDevOptionsOpen = false }
            )
        }
        if (isDisplayModalOpen) {
            HioneDisplaySettingsModal(onDismissRequest = { isDisplayModalOpen = false })
        }
        if (isBluetoothModalOpen) {
            HioneBluetoothSettingsModal(onDismissRequest = { isBluetoothModalOpen = false })
        }
        if (isStorageModalOpen) {
            HioneStorageSettingsModal(onDismissRequest = { isStorageModalOpen = false })
        }
        if (isAppsModalOpen) {
            HioneAppsSettingsModal(onDismissRequest = { isAppsModalOpen = false })
        }
        if (isGmsModalOpen) {
            com.hione.player.settings.HioneGmsManagerModal(onDismissRequest = { isGmsModalOpen = false })
        }
        if (isSecurityModalOpen) {
            HioneSecuritySettingsModal(onDismissRequest = { isSecurityModalOpen = false })
        }
        if (isLicenseModalOpen) {
            com.hione.player.entitlement.HioneLicenseModal(onDismissRequest = { isLicenseModalOpen = false })
        }
        if (isBrainModalOpen) {
            HioneBrainModal(onDismissRequest = { isBrainModalOpen = false })
        }
        if (isMonitorModalOpen) {
            HioneMonitorModal(onDismissRequest = { isMonitorModalOpen = false })
        }
        if (isWifiStationOpen) {
            HioneNetworkObservatoryModal(onDismissRequest = { isWifiStationOpen = false })
        }
    }
}

/**
 * HiBy-Style Square Hardware Box Setting Card.
 * Features a distinct square icon box on the left, high-contrast title & subtitle,
 * and clean cyber glass borders.
 */
@Composable
fun Cyber3dSettingsCard(item: SettingsCategoryItem) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(
                        Mascot.color(0xEE091D26),
                        Mascot.color(0xFF041218)
                    )
                )
            )
            .border(
                BorderStroke(
                    1.dp,
                    Brush.horizontalGradient(
                        listOf(
                            item.accentColor.copy(alpha = 0.5f),
                            CyberGlassBorder.copy(alpha = 0.25f)
                        )
                    )
                ),
                RoundedCornerShape(12.dp)
            )
            .clickable { item.onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Square Hardware Icon Box (HiBy Style)
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(item.accentColor.copy(alpha = 0.16f))
                    .border(1.dp, item.accentColor.copy(alpha = 0.6f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    item.icon,
                    contentDescription = item.title,
                    tint = item.accentColor,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(Modifier.width(12.dp))

            // Title & Subtitle
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = item.title,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (item.badge != null) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(item.accentColor.copy(alpha = 0.2f))
                                .border(0.5.dp, item.accentColor, RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.5.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = item.badge,
                                color = item.accentColor,
                                fontSize = 7.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = item.subtitle,
                    color = HioneTextSecondary,
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.width(6.dp))

            // Subtle Right Chevron Indicator
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = HioneCyan.copy(alpha = 0.45f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/* ========================================================================= */
/* 1. HIONE DISPLAY SETTINGS MODAL                                            */
/* ========================================================================= */

@Composable
fun HioneDisplaySettingsModal(onDismissRequest: () -> Unit) {
    val ctx = LocalContext.current
    var brightness by remember {
        mutableFloatStateOf(
            try {
                val b = Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
                b / 255f
            } catch (_: Throwable) { 0.5f }
        )
    }
    var autoBrightness by remember {
        mutableStateOf(
            try {
                Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            } catch (_: Throwable) { false }
        )
    }
    var screenTimeoutMs by remember {
        mutableIntStateOf(
            try {
                Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 60000)
            } catch (_: Throwable) { 60000 }
        )
    }

    val timeoutOptions = listOf(
        15000 to "15s",
        30000 to "30s",
        60000 to "1m",
        120000 to "2m",
        300000 to "5m",
        600000 to "10m",
        -1 to "Never"
    )

    AlertDialog(
        onDismissRequest = onDismissRequest,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        containerColor = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth(0.96f)
            .fillMaxHeight(0.90f)
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(listOf(Mascot.color(0xF0081C24), Mascot.color(0xF50C0D10))))
            .border(1.5.dp, Brush.horizontalGradient(listOf(Color(0xFFFFD600), HioneCyan)), RoundedCornerShape(24.dp)),
        title = null,
        text = {
            Column(Modifier.fillMaxSize().padding(top = 4.dp)) {
                HioneTopBar(title = "Display & Screen", onBack = onDismissRequest)
                Spacer(Modifier.height(8.dp))

                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Brightness Card
                    item {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(CyberGlassCard)
                                .border(1.dp, CyberGlassBorder, RoundedCornerShape(14.dp))
                                .padding(12.dp)
                        ) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Display Brightness", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                                Text("${(brightness * 100).toInt()}%", color = Color(0xFFFFD600), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(8.dp))
                            Slider(
                                value = brightness,
                                onValueChange = { next ->
                                    brightness = next
                                    val intVal = (next * 255).toInt().coerceIn(1, 255)
                                    try {
                                        Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, intVal)
                                    } catch (t: Throwable) {
                                        // WRITE_SETTINGS is granted to this platform-signed build; if it
                                        // ever fails, say so rather than shelling out to a su that does
                                        // not exist on HioneOS.
                                        android.util.Log.w("HioneSettings", "screen_brightness write refused: $t")
                                    }
                                },
                                colors = SliderDefaults.colors(
                                    thumbColor = Color(0xFFFFD600),
                                    activeTrackColor = Color(0xFFFFD600),
                                    inactiveTrackColor = Color(0x33FFD600)
                                )
                            )
                        }
                    }

                    // Auto Brightness Switch
                    item {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(CyberGlassCard)
                                .border(1.dp, CyberGlassBorder, RoundedCornerShape(14.dp))
                                .padding(12.dp)
                        ) {
                            CyberSwitchRow(
                                title = "Adaptive Brightness",
                                subtitle = "Adjusts brightness from the light sensor",
                                checked = autoBrightness,
                                onCheckedChange = {
                                    val hasCamera = ctx.checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                                    if (it && !hasCamera) {
                                        // The M500 has no hardware ALS; adaptive brightness is driven by the
                                        // camera-luma sensor, which needs CAMERA. Don't flip the toggle on until granted.
                                        Toast.makeText(ctx, "Camera permission needed for adaptive brightness", Toast.LENGTH_LONG).show()
                                        (ctx as? android.app.Activity)?.requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 4021)
                                    } else {
                                        autoBrightness = it
                                        // Drive the real camera-luma auto-brightness engine (SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                                        // alone is a no-op on this device — there's no hardware ambient light sensor).
                                        com.hione.player.brightness.HioneAmbientLightService.setEnabled(ctx, it)
                                        val mode = if (it) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                                        try { Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, mode) }
                                        catch (t: Throwable) { android.util.Log.w("HioneSettings", "screen_brightness_mode write refused: $t") }
                                    }
                                }
                            )
                        }
                    }

                    // Screen Off Timeout
                    item {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(CyberGlassCard)
                                .border(1.dp, CyberGlassBorder, RoundedCornerShape(14.dp))
                                .padding(12.dp)
                        ) {
                            Text("Screen Sleep Timeout", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                timeoutOptions.take(4).forEach { (ms, label) ->
                                    val isSel = screenTimeoutMs == ms
                                    Box(
                                        Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (isSel) Color(0xFFFFD600) else Color.White.copy(alpha = 0.08f))
                                            .border(1.dp, if (isSel) Color(0xFFFFD600) else CyberGlassBorder, RoundedCornerShape(8.dp))
                                            .clickable {
                                                screenTimeoutMs = ms
                                                val v = if (ms == -1) Int.MAX_VALUE else ms
                                                try { Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, v) }
                                                catch (t: Throwable) { android.util.Log.w("HioneSettings", "screen_off_timeout write refused: $t") }
                                            }
                                            .padding(vertical = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(label, color = if (isSel) Color.Black else Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                timeoutOptions.drop(4).forEach { (ms, label) ->
                                    val isSel = screenTimeoutMs == ms
                                    Box(
                                        Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (isSel) Color(0xFFFFD600) else Color.White.copy(alpha = 0.08f))
                                            .border(1.dp, if (isSel) Color(0xFFFFD600) else CyberGlassBorder, RoundedCornerShape(8.dp))
                                            .clickable {
                                                screenTimeoutMs = ms
                                                val v = if (ms == -1) 2147483647 else ms
                                                try { Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, v) }
                                                catch (t: Throwable) { android.util.Log.w("HioneSettings", "screen_off_timeout write refused: $t") }
                                            }
                                            .padding(vertical = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(label, color = if (isSel) Color.Black else Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    // Display Specs Card
                    item {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(CyberGlassCard)
                                .border(1.dp, CyberGlassBorder, RoundedCornerShape(14.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Every row below is read from the OS. It used to be a hardcoded spec
                            // sheet ("1080 x 540", "300 PPI / 60 Hz", "Vulkan & OpenGLES 3.2
                            // Active") presented as this device's live display readout.
                            val dm = remember { ctx.resources.displayMetrics }
                            val refreshHz = remember {
                                runCatching {
                                    val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
                                    wm.defaultDisplay.refreshRate
                                }.getOrNull()
                            }
                            val glEsVersion = remember {
                                runCatching {
                                    val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                                    val v = am.deviceConfigurationInfo.reqGlEsVersion
                                    "${v shr 16}.${v and 0xFFFF}"
                                }.getOrNull()
                            }
                            val hasVulkan = remember {
                                ctx.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)
                            }
                            CyberInfoRow("Resolution", "${dm.widthPixels} x ${dm.heightPixels} px")
                            CyberInfoRow(
                                "Pixel Density",
                                "${dm.densityDpi} dpi" + (refreshHz?.let { " / ${"%.0f".format(it)} Hz" } ?: " / — Hz")
                            )
                            CyberInfoRow("Theme Mode", "Dark (${Mascot.name})")
                            CyberInfoRow(
                                "Graphics Support",
                                (glEsVersion?.let { "OpenGL ES $it" } ?: "OpenGL ES —") +
                                    (if (hasVulkan) " · Vulkan" else " · no Vulkan")
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {}
    )
}

/* ========================================================================= */
/* 2. HIONE BLUETOOTH SETTINGS MODAL                                          */
/* ========================================================================= */

@Composable
fun HioneBluetoothSettingsModal(onDismissRequest: () -> Unit) {
    val ctx = LocalContext.current

    LaunchedEffect(Unit) {
        com.hione.player.bluetooth.HioneBluetoothController.init(ctx)
    }

    val isBtEnabled by com.hione.player.bluetooth.HioneBluetoothController.isBluetoothEnabled.collectAsState()
    val isScanning by com.hione.player.bluetooth.HioneBluetoothController.isScanning.collectAsState()
    val pairedDevices by com.hione.player.bluetooth.HioneBluetoothController.pairedDevices.collectAsState()
    val discoveredDevices by com.hione.player.bluetooth.HioneBluetoothController.discoveredDevices.collectAsState()

    AlertDialog(
        onDismissRequest = onDismissRequest,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        containerColor = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth(0.96f)
            .fillMaxHeight(0.90f)
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(listOf(Mascot.color(0xF0081C24), Mascot.color(0xF50C0D10))))
            .border(1.5.dp, Brush.horizontalGradient(listOf(Color(0xFF2979FF), HioneCyan)), RoundedCornerShape(24.dp)),
        title = null,
        text = {
            Column(Modifier.fillMaxSize().padding(top = 4.dp)) {
                HioneTopBar(title = "Bluetooth & Codecs", onBack = onDismissRequest)
                Spacer(Modifier.height(8.dp))

                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // 1. Master Bluetooth Switch Card
                    item {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (isBtEnabled) Color(0x222979FF) else CyberGlassCard)
                                .border(1.dp, if (isBtEnabled) Color(0xFF2979FF) else CyberGlassBorder, RoundedCornerShape(14.dp))
                                .padding(12.dp)
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("Bluetooth", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                                    Text(if (isBtEnabled) "On" else "Off", color = if (isBtEnabled) Color(0xFF2979FF) else HioneTextSecondary, fontSize = 10.5.sp)
                                }
                                Switch(
                                    checked = isBtEnabled,
                                    onCheckedChange = { next ->
                                        com.hione.player.bluetooth.HioneBluetoothController.toggleBluetooth(next)
                                    },
                                    colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = Color(0xFF2979FF))
                                )
                            }
                        }
                    }

                    if (isBtEnabled) {
                        // 2. Paired Devices Card
                        item {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(CyberGlassCard)
                                    .border(1.dp, CyberGlassBorder, RoundedCornerShape(14.dp))
                                    .padding(12.dp)
                            ) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("PAIRED BLUETOOTH DEVICES (${pairedDevices.size})", color = Color(0xFF2979FF), fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                                    
                                    Button(
                                        onClick = {
                                            if (isScanning) {
                                                com.hione.player.bluetooth.HioneBluetoothController.stopScan()
                                            } else {
                                                com.hione.player.bluetooth.HioneBluetoothController.startScan()
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = if (isScanning) Mascot.color(0x336FB0FF) else Color(0x332979FF)),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.height(26.dp)
                                    ) {
                                        Text(if (isScanning) "Stop Scan" else "Scan", color = if (isScanning) Mascot.color(0xFFFF80AB) else Color(0xFF2979FF), fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                Spacer(Modifier.height(8.dp))

                                if (pairedDevices.isEmpty()) {
                                    Text("No paired devices.", color = HioneTextSecondary, fontSize = 11.sp)
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        pairedDevices.forEach { devItem ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(if (devItem.isConnected) Color(0x222979FF) else Color(0x11FFFFFF))
                                                    .border(1.dp, if (devItem.isConnected) Color(0xFF2979FF) else Color.Transparent, RoundedCornerShape(10.dp))
                                                    .clickable {
                                                        if (!devItem.isConnected && !devItem.isConnecting) {
                                                            com.hione.player.bluetooth.HioneBluetoothController.connectDevice(devItem.device)
                                                        }
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
                                                    tint = if (devItem.isConnected) Mascot.color(0xFFFFC56B) else Color.White,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                                Spacer(Modifier.width(10.dp))
                                                Column(Modifier.weight(1f)) {
                                                    Text(devItem.name, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                    Text(
                                                        text = when {
                                                            devItem.isConnected -> "Connected for audio"
                                                            devItem.isConnecting -> "Connecting…"
                                                            else -> devItem.address
                                                        },
                                                        color = if (devItem.isConnected) Mascot.color(0xFFFFC56B) else if (devItem.isConnecting) Color(0xFFFFD54F) else HioneTextSecondary,
                                                        fontSize = 9.5.sp
                                                    )
                                                }

                                                Spacer(Modifier.width(6.dp))

                                                if (devItem.isConnected) {
                                                    Button(
                                                        onClick = { com.hione.player.bluetooth.HioneBluetoothController.disconnectDevice(devItem.device) },
                                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0x33FF5252)),
                                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                        shape = RoundedCornerShape(8.dp),
                                                        modifier = Modifier.height(26.dp)
                                                    ) {
                                                        Text("Disconnect", color = Color(0xFFFF8A80), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                                    }
                                                } else if (devItem.isConnecting) {
                                                    CircularProgressIndicator(
                                                        color = Color(0xFF2979FF),
                                                        modifier = Modifier.size(18.dp),
                                                        strokeWidth = 2.dp
                                                    )
                                                } else {
                                                    Button(
                                                        onClick = { com.hione.player.bluetooth.HioneBluetoothController.connectDevice(devItem.device) },
                                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2979FF)),
                                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                        shape = RoundedCornerShape(8.dp),
                                                        modifier = Modifier.height(26.dp)
                                                    ) {
                                                        Text("Connect", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                                    }
                                                }

                                                Spacer(Modifier.width(4.dp))

                                                IconButton(
                                                    onClick = { com.hione.player.bluetooth.HioneBluetoothController.unpairDevice(devItem.device) },
                                                    modifier = Modifier.size(26.dp)
                                                ) {
                                                    Icon(Icons.Default.DeleteOutline, contentDescription = "Forget", tint = HioneTextSecondary, modifier = Modifier.size(16.dp))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // 3. Live Discovered Nearby Gear
                        item {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(CyberGlassCard)
                                    .border(1.dp, CyberGlassBorder, RoundedCornerShape(14.dp))
                                    .padding(12.dp)
                            ) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("NEARBY DEVICES", color = Color(0xFF2979FF), fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                                        if (isScanning) {
                                            Spacer(Modifier.width(6.dp))
                                            CircularProgressIndicator(color = Color(0xFF2979FF), modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                                        }
                                    }

                                    Button(
                                        onClick = {
                                            if (isScanning) {
                                                com.hione.player.bluetooth.HioneBluetoothController.stopScan()
                                            } else {
                                                com.hione.player.bluetooth.HioneBluetoothController.startScan()
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = if (isScanning) Mascot.color(0x336FB0FF) else Color(0x332979FF)),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.height(26.dp)
                                    ) {
                                        Text(if (isScanning) "Stop" else "Scan", color = if (isScanning) Mascot.color(0xFFFF80AB) else Color(0xFF2979FF), fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                Spacer(Modifier.height(8.dp))

                                if (discoveredDevices.isEmpty()) {
                                    Text(if (isScanning) "Looking for headphones, IEMs and wireless DACs…" else "Tap Scan to find nearby devices.", color = HioneTextSecondary, fontSize = 11.sp)
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        discoveredDevices.forEach { devItem ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(Color(0x11FFFFFF))
                                                    .clickable {
                                                        com.hione.player.bluetooth.HioneBluetoothController.pairDevice(devItem.device)
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
                                                    tint = Color(0xFF2979FF),
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(Modifier.width(10.dp))
                                                Column(Modifier.weight(1f)) {
                                                    Text(devItem.name, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                    Text(
                                                        "${devItem.address} · Signal " + (devItem.rssi?.let { "$it dBm" } ?: "—"),
                                                        color = HioneTextSecondary,
                                                        fontSize = 9.5.sp
                                                    )
                                                }
                                                Button(
                                                    onClick = { com.hione.player.bluetooth.HioneBluetoothController.pairDevice(devItem.device) },
                                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0x332979FF)),
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                    shape = RoundedCornerShape(8.dp),
                                                    modifier = Modifier.height(26.dp)
                                                ) {
                                                    Text("Pair & Connect", color = Color(0xFF82B1FF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // 4. Hi-Res Audio Codecs Priority
                        item {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(CyberGlassCard)
                                    .border(1.dp, CyberGlassBorder, RoundedCornerShape(14.dp))
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text("Codecs", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                listOf(
                                    "LDAC (990 / 660 / 330 kbps 96kHz/24-Bit)" to Color(0xFF00E676),
                                    "Qualcomm aptX HD (576 kbps 48kHz/24-Bit)" to Mascot.color(0xFFFFC56B),
                                    "aptX Adaptive (Low Latency / Dynamic)" to Color(0xFF2979FF),
                                    "AAC / SBC (Standard Audio Codec)" to HioneTextSecondary
                                ).forEach { (codec, col) ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(8.dp).clip(CircleShape).background(col))
                                        Spacer(Modifier.width(8.dp))
                                        Text(codec, color = Color.White, fontSize = 10.5.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {}
    )
}

/* ========================================================================= */
/* 3. HIONE STORAGE SETTINGS MODAL                                            */
/* ========================================================================= */

@Composable
fun HioneStorageSettingsModal(onDismissRequest: () -> Unit) {
    val ctx = LocalContext.current
    val internalStats = remember {
        try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val total = (stat.blockCountLong * stat.blockSizeLong) / (1024 * 1024 * 1024)
            val free = (stat.availableBlocksLong * stat.blockSizeLong) / (1024 * 1024 * 1024)
            val used = total - free
            Triple(used, total, free)
        } catch (_: Throwable) { Triple(24L, 64L, 40L) }
    }

    // Resolve the actually-inserted card (never hardcode a per-card FAT UUID).
    val sdRoot = remember { HioneVolumes.removableRoot(ctx) }
    val sdStats = remember {
        try {
            val sdDir = sdRoot
            if (sdDir != null && sdDir.exists()) {
                val stat = StatFs(sdDir.path)
                val total = (stat.blockCountLong * stat.blockSizeLong) / (1024 * 1024 * 1024)
                val free = (stat.availableBlocksLong * stat.blockSizeLong) / (1024 * 1024 * 1024)
                val used = total - free
                Triple(used, total, free)
            } else Triple(0L, 0L, 0L)
        } catch (_: Throwable) { Triple(0L, 0L, 0L) }
    }

    var isRescanning by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        containerColor = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth(0.96f)
            .fillMaxHeight(0.90f)
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(listOf(Mascot.color(0xF0081C24), Mascot.color(0xF50C0D10))))
            .border(1.5.dp, Brush.horizontalGradient(listOf(Mascot.color(0xFFFFC56B), HionePink)), RoundedCornerShape(24.dp)),
        title = null,
        text = {
            Column(Modifier.fillMaxSize().padding(top = 4.dp)) {
                HioneTopBar(title = "Storage & MicroSD", onBack = onDismissRequest)
                Spacer(Modifier.height(8.dp))

                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Internal Storage Card
                    item {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(CyberGlassCard)
                                .border(1.dp, CyberGlassBorder, RoundedCornerShape(14.dp))
                                .padding(12.dp)
                        ) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Internal eMMC Flash", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                                Text("${internalStats.first} GB / ${internalStats.second} GB", color = HioneCyan, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { (internalStats.first.toFloat() / internalStats.second.toFloat()).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                                color = HioneCyan,
                                trackColor = Mascot.color(0xFF102830)
                            )
                            Spacer(Modifier.height(4.dp))
                            Text("${internalStats.third} GB free", color = HioneTextSecondary, fontSize = 10.sp)
                        }
                    }

                    // MicroSD Storage Card
                    item {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(CyberGlassCard)
                                .border(1.dp, CyberGlassBorder, RoundedCornerShape(14.dp))
                                .padding(12.dp)
                        ) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(if (sdRoot != null) "MicroSD Card (${sdRoot.absolutePath})" else "MicroSD Card (not inserted)", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                                Text(if (sdStats.second > 0) "${sdStats.first} GB / ${sdStats.second} GB" else "Not mounted", color = HioneNeonPink, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(6.dp))
                            if (sdStats.second > 0) {
                                LinearProgressIndicator(
                                    progress = { (sdStats.first.toFloat() / sdStats.second.toFloat()).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                                    color = HioneNeonPink,
                                    trackColor = Mascot.color(0xFF301020)
                                )
                                Spacer(Modifier.height(4.dp))
                                Text("${sdStats.third} GB free", color = HioneTextSecondary, fontSize = 10.sp)
                            } else {
                                Text("Insert a FAT32 or exFAT microSD card for more music storage", color = HioneTextSecondary, fontSize = 10.sp)
                            }
                        }
                    }

                    // Rescan Media Button
                    item {
                        Button(
                            onClick = {
                                isRescanning = true
                                kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                                    try {
                                        val intent = Intent(ctx, LibraryScanService::class.java)
                                        ctx.startService(intent)
                                    } catch (_: Throwable) {}
                                    kotlinx.coroutines.delay(2000)
                                    isRescanning = false
                                }
                                Toast.makeText(ctx, "Library scan started", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = HioneCyan.copy(alpha = 0.2f)),
                            border = BorderStroke(1.dp, HioneCyan),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = HioneCyan, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (isRescanning) "Scanning library…" else "Rescan library", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                        }
                    }
                }
            }
        },
        confirmButton = {}
    )
}

/* ========================================================================= */
/* 4. HIONE APPS SETTINGS MODAL                                               */
/* ========================================================================= */

@Composable
fun HioneAppsSettingsModal(onDismissRequest: () -> Unit) {
    val ctx = LocalContext.current
    val pm = ctx.packageManager
    val installedApps = remember {
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || it.packageName == "com.hione.player" }
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        containerColor = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth(0.96f)
            .fillMaxHeight(0.90f)
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(listOf(Mascot.color(0xF0081C24), Mascot.color(0xF50C0D10))))
            .border(1.5.dp, Brush.horizontalGradient(listOf(Color(0xFFFF9100), HioneCyan)), RoundedCornerShape(24.dp)),
        title = null,
        text = {
            Column(Modifier.fillMaxSize().padding(top = 4.dp)) {
                HioneTopBar(title = "Installed Applications", onBack = onDismissRequest)
                Spacer(Modifier.height(8.dp))

                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(installedApps, key = { it.packageName }) { app ->
                        val label = remember { pm.getApplicationLabel(app).toString() }
                        val icon = remember { try { pm.getApplicationIcon(app) } catch (_: Throwable) { null } }

                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(CyberGlassCard)
                                .border(1.dp, CyberGlassBorder, RoundedCornerShape(12.dp))
                                .clickable {
                                    try {
                                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                            data = Uri.parse("package:${app.packageName}")
                                        }
                                        ctx.startActivity(intent)
                                    } catch (_: Throwable) {}
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                if (icon != null) {
                                    Image(bitmap = icon.toBitmap(48, 48).asImageBitmap(), contentDescription = null, modifier = Modifier.size(36.dp))
                                } else {
                                    Icon(Icons.Default.Apps, contentDescription = null, tint = Color(0xFFFF9100), modifier = Modifier.size(36.dp))
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Text(app.packageName, color = HioneTextSecondary, fontSize = 9.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = HioneTextSecondary, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {}
    )
}

/* ========================================================================= */
/* 5. HIONE SECURITY SETTINGS MODAL                                           */
/* ========================================================================= */

@Composable
fun HioneSecuritySettingsModal(onDismissRequest: () -> Unit) {
    val ctx = LocalContext.current
    var doubleTapPowerCamera by remember {
        mutableStateOf(
            try {
                Settings.Secure.getInt(ctx.contentResolver, "camera_double_tap_power_gesture_disabled", 0) == 0
            } catch (_: Throwable) { true }
        )
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        containerColor = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth(0.96f)
            .fillMaxHeight(0.90f)
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(listOf(Mascot.color(0xF0081C24), Mascot.color(0xF50C0D10))))
            .border(1.5.dp, Brush.horizontalGradient(listOf(Color(0xFFE040FB), HioneCyan)), RoundedCornerShape(24.dp)),
        title = null,
        text = {
            Column(Modifier.fillMaxSize().padding(top = 4.dp)) {
                HioneTopBar(title = "Security & Gestures", onBack = onDismissRequest)
                Spacer(Modifier.height(8.dp))

                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Double Tap Power
                    item {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(CyberGlassCard)
                                .border(1.dp, CyberGlassBorder, RoundedCornerShape(14.dp))
                                .padding(12.dp)
                        ) {
                            CyberSwitchRow(
                                title = "Double-press power for camera",
                                subtitle = "Press the power button twice to open the camera",
                                checked = doubleTapPowerCamera,
                                onCheckedChange = {
                                    doubleTapPowerCamera = it
                                    val disabled = if (it) 0 else 1
                                    try {
                                        Settings.Secure.putInt(ctx.contentResolver, "camera_double_tap_power_gesture_disabled", disabled)
                                    } catch (t: Throwable) {
                                        android.util.Log.w("HioneSettings", "camera_double_tap_power_gesture_disabled write refused: $t")
                                    }
                                }
                            )
                        }
                    }


                    // Security Info
                    item {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(CyberGlassCard)
                                .border(1.dp, CyberGlassBorder, RoundedCornerShape(14.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // FAKE-DATA FIX: these three rows were string literals -- "Qualcomm
                            // TrustZone AES-256 Active", "PIN / Pattern Lock Active" and
                            // "Enforcing (Custom Magisk Sandbox)" -- shown as this device's live
                            // security posture no matter what the device was actually doing (the
                            // SELinux row in particular claimed Enforcing unconditionally). Each
                            // one is now a real read, and "—" when the OS will not say.
                            val cryptoState: String = remember {
                                val state: String? = readSystemProperty("ro.crypto.state")
                                val type: String? = readSystemProperty("ro.crypto.type")
                                when (state) {
                                    null -> "—"
                                    "encrypted" -> "Encrypted" + (type?.let { " ($it)" } ?: "")
                                    "unencrypted" -> "Not encrypted"
                                    "unsupported" -> "Not supported"
                                    else -> state.toString()
                                }
                            }
                            val lockState: String = remember {
                                val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
                                val secure = runCatching { km?.isDeviceSecure }.getOrNull()
                                val fp = ctx.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_FINGERPRINT)
                                when (secure) {
                                    true -> if (fp) "Screen lock set · fingerprint HW present" else "Screen lock set · no fingerprint HW"
                                    false -> if (fp) "No screen lock · fingerprint HW present" else "No screen lock · no fingerprint HW"
                                    null -> "—"
                                }
                            }
                            val selinuxState: String = remember {
                                runCatching {
                                    val f = File("/sys/fs/selinux/enforce")
                                    if (!f.canRead()) null else when (f.readText().trim()) {
                                        "1" -> "Enforcing"
                                        "0" -> "Permissive"
                                        else -> null
                                    }
                                }.getOrNull() ?: "—"
                            }
                            CyberInfoRow("Encryption Status", cryptoState)
                            CyberInfoRow("Screen Lock / Biometrics", lockState)
                            CyberInfoRow("SELinux Policy", selinuxState)
                        }
                    }
                }
            }
        },
        confirmButton = {}
    )
}

/* ========================================================================= */
/* 6. ABOUT DEVICE & DEVELOPER MODALS                                        */
/* ========================================================================= */

@Composable
fun HioneAboutDeviceModal(
    onDismissRequest: () -> Unit,
    onOpenDevOptions: () -> Unit
) {
    val ctx = LocalContext.current
    var devTapCount by remember { mutableIntStateOf(0) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

    val uptimeFormatted = remember {
        val sec = SystemClock.elapsedRealtime() / 1000
        val hrs = sec / 3600
        val mins = (sec % 3600) / 60
        "${hrs}h ${mins}m"
    }

    val kernelVersion = remember {
        try {
            val v = File("/proc/version").readText()
            v.substringBefore(" (").replace("Linux version ", "")
        } catch (_: Throwable) {
            // FAKE-DATA FIX: the fallback used to be the literal string "4.19.157", which is not
            // this device's kernel. Unknown now reads "—".
            System.getProperty("os.version") ?: "—"
        }
    }

    val wifiIp = remember {
        try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ipInt = wm?.connectionInfo?.ipAddress ?: 0
            if (ipInt != 0) {
                String.format(
                    Locale.US,
                    "%d.%d.%d.%d",
                    ipInt and 0xff,
                    ipInt shr 8 and 0xff,
                    ipInt shr 16 and 0xff,
                    ipInt shr 24 and 0xff
                )
            } else ""
        } catch (_: Throwable) { "" }
    }

    /** Real wlan0 hardware address. Android returns the 02:00:00:00:00:00 sentinel to apps that
     *  don't hold LOCAL_MAC_ADDRESS — that is "not available", never an address to print. */
    val wifiMac = remember {
        val hidden = "02:00:00:00:00:00"
        runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .firstOrNull { it.name.equals("wlan0", ignoreCase = true) }
                ?.hardwareAddress
                ?.joinToString(":") { b -> "%02X".format(b) }
        }.getOrNull()
            ?.takeIf { it.isNotBlank() && !it.equals(hidden, ignoreCase = true) }
            ?: "Not exposed to apps"
    }

    @Suppress("DEPRECATION", "MissingPermission", "HardwareIds")
    val btMac = remember {
        val hidden = "02:00:00:00:00:00"
        val fromSettings = runCatching {
            Settings.Secure.getString(ctx.contentResolver, "bluetooth_address")
        }.getOrNull()
        val fromAdapter = runCatching {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            adapter?.address
        }.getOrNull()
        listOfNotNull(fromSettings, fromAdapter)
            .firstOrNull { it.isNotBlank() && !it.equals(hidden, ignoreCase = true) }
            ?.uppercase(Locale.US)
            ?: "Not exposed to apps"
    }

    @Suppress("DEPRECATION", "MissingPermission", "HardwareIds")
    val serialNumber = remember {
        val fromBuild = runCatching {
            if (Build.VERSION.SDK_INT >= 26) Build.getSerial() else Build.SERIAL
        }.getOrNull()
        val candidates = listOfNotNull(
            fromBuild,
            readSystemProperty("ro.serialno"),
            readSystemProperty("ro.boot.serialno")
        )
        candidates.firstOrNull { it.isNotBlank() && !it.equals(Build.UNKNOWN, ignoreCase = true) } ?: "—"
    }

    /** Carrier straight from TelephonyManager, with the real SIM state — no assumed carrier. */
    val simCarrier = remember {
        runCatching {
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
            if (tm == null) "—" else when (tm.simState) {
                android.telephony.TelephonyManager.SIM_STATE_ABSENT -> "No SIM"
                android.telephony.TelephonyManager.SIM_STATE_READY -> {
                    val name = tm.simOperatorName?.takeIf { it.isNotBlank() }
                        ?: tm.networkOperatorName?.takeIf { it.isNotBlank() }
                    name ?: "SIM ready · carrier not reported"
                }
                android.telephony.TelephonyManager.SIM_STATE_PIN_REQUIRED,
                android.telephony.TelephonyManager.SIM_STATE_PUK_REQUIRED -> "SIM locked"
                android.telephony.TelephonyManager.SIM_STATE_NOT_READY -> "SIM not ready"
                else -> "—"
            }
        }.getOrDefault("—")
    }

    val storageStats = remember {
        try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val total = (stat.blockCountLong * stat.blockSizeLong) / (1024 * 1024 * 1024)
            val free = (stat.availableBlocksLong * stat.blockSizeLong) / (1024 * 1024 * 1024)
            val used = total - free
            Pair("${used} GB / ${total} GB", "${free} GB Free")
        } catch (_: Throwable) {
            // FAKE-DATA FIX: this used to fall back to "24.5 GB / 64 GB" / "39.5 GB Free", a made-up
            // storage report rendered exactly like the real StatFs one.
            Pair("—", "—")
        }
    }

    // ---------------------------------------------------------------------------------------
    // FAKE-DATA FIX: every row in this modal used to be a hand-written spec sheet -- SoC, core
    // count/clock, RAM, panel, battery capacity, Android version, security patch, baseband and
    // firmware string were all string literals presented as this device's readout, and several
    // were simply wrong (it claimed "Android 13 / API 33" and "CS43131"). They are read from the
    // OS now, and anything the OS will not tell us reads "—".
    // ---------------------------------------------------------------------------------------
    val socName = remember {
        val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN } else null
        val manu = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER.takeIf { it.isNotBlank() && it != Build.UNKNOWN } else null
        val board = Build.BOARD.takeIf { it.isNotBlank() && it != Build.UNKNOWN }
        val hw = Build.HARDWARE.takeIf { it.isNotBlank() && it != Build.UNKNOWN }
        listOfNotNull(manu, soc ?: board ?: hw).joinToString(" ").ifBlank { "—" }
    }

    val cpuSummary = remember {
        val cores = Runtime.getRuntime().availableProcessors()
        val maxKhz = (0 until cores).mapNotNull { i ->
            runCatching {
                File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
                    .takeIf { it.canRead() }?.readText()?.trim()?.toLongOrNull()
            }.getOrNull()
        }.maxOrNull()
        val abi = Build.SUPPORTED_ABIS.firstOrNull()?.let { if (it.contains("64")) "64-bit" else "32-bit" } ?: "—"
        if (maxKhz != null) "${cores} cores · up to ${"%.2f".format(Locale.US, maxKhz / 1_000_000.0)} GHz · $abi"
        else "${cores} cores · clock — · $abi"
    }

    val ramSummary = remember {
        runCatching {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            "%.1f GB total · %.1f GB free".format(
                Locale.US, mi.totalMem / 1073741824.0, mi.availMem / 1073741824.0
            )
        }.getOrDefault("—")
    }

    val panelSummary = remember {
        runCatching {
            val dm = ctx.resources.displayMetrics
            val hz = (ctx.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
                .defaultDisplay.refreshRate
            "${dm.widthPixels} x ${dm.heightPixels} · ${dm.densityDpi} dpi · ${"%.0f".format(Locale.US, hz)} Hz"
        }.getOrDefault("—")
    }

    /** Design capacity in mAh from the platform power profile — "—" when the profile is absent. */
    val batteryCapacity = remember {
        runCatching {
            val cls = Class.forName("com.android.internal.os.PowerProfile")
            val inst = cls.getConstructor(Context::class.java).newInstance(ctx)
            val mah = cls.getMethod("getBatteryCapacity").invoke(inst) as Double
            if (mah > 0.0) "${mah.toInt()} mAh (design)" else "—"
        }.getOrDefault("—")
    }

    val dacSummary = remember {
        // The DAC part is a fixed fact of this hardware; what varies is whether this process can
        // actually see its kernel nodes, so say that instead of asserting a live link.
        val readable = runCatching { File(CirrusLogicManager.SYSFS_BASE).exists() }.getOrDefault(false)
        "Dual Cirrus Logic CS43198 · sysfs " + if (readable) "present" else "not visible"
    }

    val androidSummary = remember {
        "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    }
    val securityPatch = remember { Build.VERSION.SECURITY_PATCH.takeIf { !it.isNullOrBlank() } ?: "—" }
    val basebandVersion = remember {
        runCatching { Build.getRadioVersion() }.getOrNull()?.takeIf { it.isNotBlank() } ?: "—"
    }
    val firmwareRelease = remember {
        Build.DISPLAY.takeIf { it.isNotBlank() } ?: Build.ID.takeIf { it.isNotBlank() } ?: "—"
    }

    /** True only if an `su` binary actually exists on PATH — the badge used to claim "ROOTED DAP"
     *  unconditionally, on a device that has no su at all. */
    val hasSuBinary = remember {
        listOf("/sbin/su", "/system/bin/su", "/system/xbin/su", "/su/bin/su", "/debug_ramdisk/su")
            .any { runCatching { File(it).exists() }.getOrDefault(false) }
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        containerColor = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth(0.96f)
            .fillMaxHeight(0.92f)
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(listOf(Mascot.color(0xF0081C24), Mascot.color(0xF50C0D10))))
            .border(1.5.dp, Brush.horizontalGradient(listOf(HioneCyan, HioneNeonPink)), RoundedCornerShape(24.dp)),
        title = null,
        text = {
            Column(Modifier.fillMaxSize().padding(top = 4.dp)) {
                HioneTopBar(title = "About HiBy M500 DAP", onBack = onDismissRequest)
                Spacer(Modifier.height(8.dp))

                LazyColumn(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Hero Branding Card
                    item {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(Brush.horizontalGradient(listOf(Mascot.color(0x33FFC56B), Mascot.color(0x336FB0FF))))
                                .border(1.dp, CyberGlassBorder, RoundedCornerShape(16.dp))
                                .padding(14.dp)
                        ) {
                            Column {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "HiBy M500 (Hione_San)",
                                        color = HioneCyan,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Black,
                                        fontFamily = AudiowideFont
                                    )
                                    Box(
                                        Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Color(0xFF00E676).copy(alpha = 0.2f))
                                            .border(1.dp, Color(0xFF00E676), RoundedCornerShape(6.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        // FAKE-DATA FIX: this badge read "ROOTED DAP" unconditionally.
                                        // HioneOS is platform-signed and has no su; the badge now
                                        // reports what an actual probe for an su binary found.
                                        Text(
                                            if (hasSuBinary) "ROOTED DAP" else "PLATFORM-SIGNED",
                                            color = Color(0xFF00E676),
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "${Mascot.osName} · ${Mascot.edition}",
                                    color = HioneTextSecondary,
                                    fontSize = 10.5.sp
                                )
                            }
                        }
                    }

                    // Hardware Specs Section
                    item {
                        Text(
                            "HARDWARE",
                            color = HioneCyan,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = AudiowideFont,
                            letterSpacing = 1.sp
                        )
                    }

                    item {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(CyberGlassCard)
                                .border(1.dp, CyberGlassBorder, RoundedCornerShape(14.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CyberInfoRow("SoC Processor", socName)
                            CyberInfoRow("CPU Cores", cpuSummary)
                            CyberInfoRow("Audio DAC", dacSummary)
                            CyberInfoRow("RAM Memory", ramSummary)
                            CyberInfoRow("Internal Flash", storageStats.first + " (${storageStats.second})")
                            CyberInfoRow("MicroSD Storage", HioneVolumes.removableLabel(ctx)?.let { "$it (Mounted)" } ?: "No card inserted")
                            CyberInfoRow("Display Panel", panelSummary)
                            CyberInfoRow("Battery Cell", batteryCapacity)
                        }
                    }

                    // Software & Firmware Section
                    item {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "SOFTWARE & FIRMWARE",
                            color = HioneNeonPink,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = AudiowideFont,
                            letterSpacing = 1.sp
                        )
                    }

                    item {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(CyberGlassCard)
                                .border(1.dp, CyberGlassBorder, RoundedCornerShape(14.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CyberInfoRow("Android Version", androidSummary)
                            CyberInfoRow("Security Patch", securityPatch)
                            CyberInfoRow("Linux Kernel", kernelVersion)
                            CyberInfoRow("Baseband / Modem", basebandVersion)
                            CyberInfoRow("Firmware Release", firmwareRelease)

                            // Build Number (Interactive 7-Tap Developer Unlock)
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(HioneCyan.copy(alpha = 0.08f))
                                    .clickable {
                                        devTapCount++
                                        Haptics.tick(ctx)
                                        if (devTapCount >= 7) {
                                            Toast.makeText(ctx, "Developer options are on", Toast.LENGTH_LONG).show()
                                            onOpenDevOptions()
                                        } else if (devTapCount >= 3) {
                                            Toast.makeText(ctx, "Tap ${7 - devTapCount} more times for developer options", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text("Build number", color = HioneCyan, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                        Text("M500_HIONE_AUDIOPHILE_V2.0_20260818", color = Color.White, fontSize = 10.sp)
                                    }
                                    Text(
                                        if (devTapCount >= 7) "UNLOCKED" else "${devTapCount}/7",
                                        color = if (devTapCount >= 7) Color(0xFF00E676) else HioneNeonPink,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    // Network Telemetry Section
                    item {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "NETWORK",
                            color = Color(0xFF00E676),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = AudiowideFont,
                            letterSpacing = 1.sp
                        )
                    }

                    item {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(CyberGlassCard)
                                .border(1.dp, CyberGlassBorder, RoundedCornerShape(14.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // FAKE-DATA FIX: the MAC, Bluetooth MAC, serial number and carrier rows
                            // were literals -- "74:97:79:D3:A2:18", the same address +1 for BT,
                            // "M500A192800472" and "Google Fi (Data-Only Shield Active)" -- printed
                            // as this unit's identity regardless of the hardware or the SIM. Each is
                            // now a real read; Android's per-app MAC randomisation sentinel
                            // (02:00:00:00:00:00) counts as "not available", not as an address.
                            CyberInfoRow("Wi-Fi IP Address", wifiIp.ifBlank { "—" })
                            CyberInfoRow("Wi-Fi MAC Address", wifiMac)
                            CyberInfoRow("Bluetooth MAC", btMac)
                            CyberInfoRow("System Uptime", uptimeFormatted)
                            CyberInfoRow("Serial Number", serialNumber)
                            CyberInfoRow("SIM carrier", simCarrier)
                        }
                    }
                }
            }
        },
        confirmButton = {}
    )
}

@Composable
fun HioneDeveloperOptionsModal(
    onDismissRequest: () -> Unit
) {
    val ctx = LocalContext.current
    var isAdbEnabled by remember {
        mutableStateOf(
            try {
                val p = Runtime.getRuntime().exec("getprop service.adb.tcp.port")
                p.inputStream.bufferedReader().readText().trim() == "5555"
            } catch (_: Throwable) { false }
        )
    }

    val wifiIp = remember {
        try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ipInt = wm?.connectionInfo?.ipAddress ?: 0
            if (ipInt != 0) {
                String.format(
                    Locale.US,
                    "%d.%d.%d.%d",
                    ipInt and 0xff,
                    ipInt shr 8 and 0xff,
                    ipInt shr 16 and 0xff,
                    ipInt shr 24 and 0xff
                )
            } else ""
        } catch (_: Throwable) { "" }
    }


    var usbDebugging by remember {
        mutableStateOf(
            // FAKE-DATA FIX: both the missing-row default and the catch used to be "on", so an
            // unreadable setting rendered as "USB Debugging enabled". AOSP's real default is off.
            try {
                Settings.Global.getInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
            } catch (_: Throwable) { false }
        )
    }

    var stayAwake by remember {
        mutableStateOf(
            try {
                Settings.Global.getInt(ctx.contentResolver, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 0) != 0
            } catch (_: Throwable) { false }
        )
    }

    var pointerLocation by remember {
        mutableStateOf(
            try {
                Settings.System.getInt(ctx.contentResolver, "pointer_location", 0) == 1
            } catch (_: Throwable) { false }
        )
    }

    // FAKE-DATA FIX: animScale was hardcoded to 0.5f, so this screen always claimed the device was
    // running at "0.5x (FAST)" and highlighted that chip regardless of the real system value. Read
    // the live Settings.Global scale instead (1.0 is the platform default when unset).
    var animScale by remember {
        mutableFloatStateOf(
            runCatching {
                Settings.Global.getFloat(ctx.contentResolver, Settings.Global.WINDOW_ANIMATION_SCALE, 1.0f)
            }.getOrDefault(1.0f)
        )
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        containerColor = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth(0.96f)
            .fillMaxHeight(0.92f)
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(listOf(Mascot.color(0xF0081C24), Mascot.color(0xF50C0D10))))
            .border(1.5.dp, Brush.horizontalGradient(listOf(Color(0xFF00E676), HioneCyan)), RoundedCornerShape(24.dp)),
        title = null,
        text = {
            Column(Modifier.fillMaxSize().padding(top = 4.dp)) {
                HioneTopBar(title = "Developer Options & ADB", onBack = onDismissRequest)
                Spacer(Modifier.height(8.dp))

                LazyColumn(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Wireless ADB Port 5555 Card
                    item {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (isAdbEnabled) Color(0x3300E676) else CyberGlassCard)
                                .border(1.5.dp, if (isAdbEnabled) Color(0xFF00E676) else CyberGlassBorder, RoundedCornerShape(16.dp))
                                .padding(14.dp)
                        ) {
                            Column {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "Wireless ADB (Port 5555)",
                                            color = Color.White,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = AudiowideFont
                                        )
                                        Text(
                                            if (isAdbEnabled) "On at $wifiIp:5555" else "Off",
                                            color = if (isAdbEnabled) Color(0xFF00E676) else HioneNeonPink,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }

                                    Switch(
                                        checked = isAdbEnabled,
                                        onCheckedChange = { next ->
                                            // FAKE-DATA FIX: this used to set isAdbEnabled = next and
                                            // immediately toast "⚡ Wireless ADB Started on <ip>:5555"
                                            // while the only actual work was `su -c setprop ...`. There
                                            // is no su on this device, so the property never changed and
                                            // the card sat there reading "🟢 Active" over a closed port.
                                            // The switch now writes the property directly (this app is
                                            // platform-signed), then READS service.adb.tcp.port back and
                                            // reports only what the read says.
                                            kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                                                val port = if (next) "5555" else "-1"
                                                runCatching {
                                                    val cls = Class.forName("android.os.SystemProperties")
                                                    cls.getMethod("set", String::class.java, String::class.java)
                                                        .invoke(null, "service.adb.tcp.port", port)
                                                }
                                                runCatching {
                                                    Runtime.getRuntime()
                                                        .exec(arrayOf("setprop", "service.adb.tcp.port", port))
                                                        .waitFor()
                                                }
                                                kotlinx.coroutines.delay(400L)
                                                val livePort = readSystemProperty("service.adb.tcp.port")
                                                val applied = livePort == "5555"
                                                kotlinx.coroutines.withContext(Dispatchers.Main) {
                                                    isAdbEnabled = applied
                                                    Toast.makeText(
                                                        ctx,
                                                        when {
                                                            applied && next -> "Wireless ADB listening on $wifiIp:5555\nRun: adb connect $wifiIp:5555"
                                                            !applied && !next -> "Wireless ADB stopped"
                                                            else -> "Couldn't change the ADB TCP port. service.adb.tcp.port is still " +
                                                                (livePort ?: "unset")
                                                        },
                                                        Toast.LENGTH_LONG
                                                    ).show()
                                                }
                                            }
                                        },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color.Black,
                                            checkedTrackColor = Color(0xFF00E676)
                                        )
                                    )
                                }

                                if (isAdbEnabled) {
                                    Spacer(Modifier.height(8.dp))
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Mascot.color(0xFF04131A))
                                            .padding(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Text(
                                            "Connect command:\n  adb connect $wifiIp:5555",
                                            color = HioneCyan,
                                            fontSize = 10.5.sp,
                                            fontFamily = AudiowideFont
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // System Debugging Toggles
                    item {
                        Text(
                            "DEBUGGING & POWER CONTROLS",
                            color = HioneCyan,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = AudiowideFont,
                            letterSpacing = 1.sp
                        )
                    }

                    item {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(CyberGlassCard)
                                .border(1.dp, CyberGlassBorder, RoundedCornerShape(14.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CyberSwitchRow(
                                title = "USB Debugging",
                                subtitle = "ADB over USB-C",
                                checked = usbDebugging,
                                onCheckedChange = {
                                    usbDebugging = it
                                    try {
                                        Settings.Global.putInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, if (it) 1 else 0)
                                    } catch (_: Throwable) {}
                                }
                            )

                            CyberSwitchRow(
                                title = "Stay Awake While Charging",
                                subtitle = "The screen stays on while charging",
                                checked = stayAwake,
                                onCheckedChange = {
                                    stayAwake = it
                                    try {
                                        Settings.Global.putInt(ctx.contentResolver, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, if (it) 7 else 0)
                                    } catch (_: Throwable) {}
                                }
                            )

                            CyberSwitchRow(
                                title = "Pointer Location Overlay",
                                subtitle = "Show screen coordinates and touch trails",
                                checked = pointerLocation,
                                onCheckedChange = {
                                    pointerLocation = it
                                    try {
                                        Settings.System.putInt(ctx.contentResolver, "pointer_location", if (it) 1 else 0)
                                    } catch (_: Throwable) {}
                                }
                            )
                        }
                    }

                    // Animation & Speed Scales
                    item {
                        Text(
                            "GRAPHICS & ANIMATION SPEED",
                            color = HioneNeonPink,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = AudiowideFont,
                            letterSpacing = 1.sp
                        )
                    }

                    item {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(CyberGlassCard)
                                .border(1.dp, CyberGlassBorder, RoundedCornerShape(14.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Window & Transition Scale: ${animScale}x", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(0.0f to "OFF", 0.5f to "0.5x (FAST)", 1.0f to "1.0x (DEFAULT)").forEach { (scale, label) ->
                                    val isSelected = animScale == scale
                                    Box(
                                        Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (isSelected) HioneCyan else Color.White.copy(alpha = 0.08f))
                                            .border(1.dp, if (isSelected) HioneCyan else CyberGlassBorder, RoundedCornerShape(8.dp))
                                            .clickable {
                                                // FAKE-DATA FIX: this used to move the highlight to the
                                                // tapped chip and then push the three scales through
                                                // `su -c settings put ...`, which cannot run here (no su),
                                                // so the selection never matched the system. Write them
                                                // directly, then read the real value back and show that.
                                                runCatching {
                                                    Settings.Global.putFloat(ctx.contentResolver, Settings.Global.WINDOW_ANIMATION_SCALE, scale)
                                                    Settings.Global.putFloat(ctx.contentResolver, Settings.Global.TRANSITION_ANIMATION_SCALE, scale)
                                                    Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
                                                }
                                                animScale = runCatching {
                                                    Settings.Global.getFloat(ctx.contentResolver, Settings.Global.WINDOW_ANIMATION_SCALE, 1.0f)
                                                }.getOrDefault(animScale)
                                            }
                                            .padding(vertical = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(label, color = if (isSelected) Color.Black else Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {}
    )
}

/**
 * Real read of an Android system property (android.os.SystemProperties by reflection, falling back
 * to `getprop`). Returns null when the property is absent or empty — callers must render "—", never
 * a guessed value.
 */
private fun readSystemProperty(key: String): String? {
    runCatching {
        val cls = Class.forName("android.os.SystemProperties")
        val v = cls.getMethod("get", String::class.java).invoke(null, key) as? String
        if (!v.isNullOrBlank()) return v
    }
    return runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("getprop", key))
        p.inputStream.bufferedReader().readText().trim().takeIf { it.isNotEmpty() }
    }.getOrNull()
}

@Composable
fun CyberInfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = HioneTextSecondary, fontSize = 11.sp)
        Text(value, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun CyberSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = HioneTextSecondary, fontSize = 10.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = Color(0xFF00E676)
            )
        )
    }
}
