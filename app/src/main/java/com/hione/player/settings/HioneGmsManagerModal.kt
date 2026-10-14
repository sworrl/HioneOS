package com.hione.player.settings

import com.hione.player.Mascot
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hione.player.R
import com.hione.player.AudiowideFont
import com.hione.player.CyberGlassBorder
import com.hione.player.HioneCyan
import com.hione.player.HioneNeonPink
import com.hione.player.HioneTextSecondary
import com.hione.player.ui.swipeUpFromBottomToDismiss
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class GoogleAppTarget(
    val packageName: String,
    val displayName: String,
    val description: String,
    val isEssentialGms: Boolean = false
)

val GOOGLE_ECOSYSTEM_TARGETS = listOf(
    GoogleAppTarget("com.google.android.gms", "Google Play Services (GMS)", "Core sync, auth, push & location provider", isEssentialGms = true),
    GoogleAppTarget("com.android.vending", "Google Play Store", "App store, licensing & auto-updates"),
    GoogleAppTarget("com.google.android.gsf", "Google Services Framework", "Cloud messaging & account sync framework", isEssentialGms = true),
    GoogleAppTarget("com.google.android.googlequicksearchbox", "Google App / Search", "Assistant, search widget & discover"),
    GoogleAppTarget("com.google.android.apps.bard", "Google Gemini AI", "Gemini assistant & generative AI", isEssentialGms = false),
    GoogleAppTarget("com.android.chrome", "Google Chrome", "Chromium web browser engine"),
    GoogleAppTarget("com.google.android.GoogleCamera", "Pixel Camera / GCam", "Camera processing & HDR+ pipeline"),
    GoogleAppTarget("com.google.android.apps.photos", "Google Photos", "Cloud photo library & backup"),
    GoogleAppTarget("com.google.android.apps.maps", "Google Maps", "Navigation, POI & offline map cache"),
    GoogleAppTarget("com.google.android.youtube", "YouTube", "Video streaming client"),
    GoogleAppTarget("com.google.android.tts", "Google Speech & TTS", "Speech recognition & Text-to-Speech"),
    GoogleAppTarget("com.google.android.inputmethod.latin", "GBoard Keyboard", "Google virtual keyboard"),
    GoogleAppTarget("com.google.android.apps.docs", "Google Drive", "Cloud storage & document sync")
)

/**
 * Checks if a package is enabled on the system.
 */
fun isPackageEnabled(ctx: Context, packageName: String): Boolean {
    return try {
        val appInfo = ctx.packageManager.getApplicationInfo(packageName, 0)
        appInfo.enabled
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}

/**
 * Checks if a package is installed on the system (even if disabled).
 */
fun isPackageInstalled(ctx: Context, packageName: String): Boolean {
    // MATCH_UNINSTALLED_PACKAGES also matches packages that are installed but disabled/hidden -
    // exactly the state this screen toggles. (The old `pm list packages` shell-out needed su and
    // never ran on HioneOS, so a disabled package looked "not installed".)
    return try {
        ctx.packageManager.getPackageInfo(packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (_: Throwable) {
        false
    }
}

/**
 * Enable or disable an application root-free.
 *
 * PackageManager.setApplicationEnabledSetting is the platform path: it needs
 * CHANGE_COMPONENT_ENABLED_STATE (signature|privileged), which this platform-signed build holds.
 * The previous implementation shelled `pm enable` / `pm disable-user` through su, which does not
 * exist on HioneOS - so nothing ever changed. Returns true only when the OS accepted the call;
 * the caller still re-reads the real state before telling the user anything.
 */
private fun setApplicationEnabled(ctx: Context, pkg: String, enable: Boolean): Boolean = try {
    ctx.packageManager.setApplicationEnabledSetting(
        pkg,
        if (enable) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
        0
    )
    true
} catch (t: Throwable) {
    android.util.Log.w("HioneGmsManager", "setApplicationEnabledSetting($pkg, $enable) refused: $t")
    false
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HioneGmsManagerModal(
    onDismissRequest: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var packageStates by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var installedSet by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isProcessing by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("Ready") }

    fun refreshStates() {
        val stateMap = mutableMapOf<String, Boolean>()
        val installed = mutableSetOf<String>()
        for (target in GOOGLE_ECOSYSTEM_TARGETS) {
            val isInst = isPackageInstalled(ctx, target.packageName)
            if (isInst) {
                installed.add(target.packageName)
                stateMap[target.packageName] = isPackageEnabled(ctx, target.packageName)
            }
        }
        packageStates = stateMap
        installedSet = installed
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            refreshStates()
        }
    }

    // This screen used to report "Enabled $pkg" / "All Google Apps Enabled" unconditionally after
    // firing `pm enable` through su — a success message for a write that did nothing. The write is
    // now the platform call [setApplicationEnabled], and every message below is derived from a
    // PackageManager re-read AFTER the attempt, so the screen states what the OS actually says.
    fun setPackageState(pkg: String, enable: Boolean) {
        isProcessing = true
        statusMessage = if (enable) "Enabling $pkg..." else "Disabling $pkg..."
        scope.launch(Dispatchers.IO) {
            setApplicationEnabled(ctx, pkg, enable)
            // Let PackageManager settle before reading the result back, so the verdict below
            // reflects the OS rather than a race.
            kotlinx.coroutines.delay(200)
            refreshStates()
            val actual = isPackageEnabled(ctx, pkg)
            withContext(Dispatchers.Main) {
                isProcessing = false
                statusMessage = when {
                    actual == enable && enable -> "Enabled $pkg"
                    actual == enable -> "Disabled $pkg"
                    else -> "Unchanged: $pkg is still ${if (actual) "enabled" else "disabled"}. The OS refused the change (no system privileges)"
                }
            }
        }
    }

    fun setBulkState(enable: Boolean) {
        isProcessing = true
        statusMessage = if (enable) "Turning on all Google apps…" else "Turning off all Google apps…"
        scope.launch(Dispatchers.IO) {
            val attempted = GOOGLE_ECOSYSTEM_TARGETS.filter { installedSet.contains(it.packageName) }
            for (target in attempted) {
                setApplicationEnabled(ctx, target.packageName, enable)
            }
            kotlinx.coroutines.delay(300)
            refreshStates()
            val changed = attempted.count { isPackageEnabled(ctx, it.packageName) == enable }
            withContext(Dispatchers.Main) {
                isProcessing = false
                statusMessage = when {
                    attempted.isEmpty() -> "No targeted Google packages are installed"
                    changed == attempted.size && enable -> "All ${attempted.size} Google apps enabled"
                    changed == attempted.size -> "All ${attempted.size} Google apps disabled"
                    changed == 0 -> "Nothing changed. The OS refused all ${attempted.size} changes (no system privileges)"
                    else -> "$changed of ${attempted.size} ${if (enable) "enabled" else "disabled"}, the rest were refused by the OS"
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Mascot.color(0xEE030D12))
                .clickable { onDismissRequest() }
                .swipeUpFromBottomToDismiss(onDismiss = onDismissRequest),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.96f)
                    .fillMaxHeight(0.92f)
                    .clip(CutCornerShape(16.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = 0.22f),
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.75f)
                            )
                        )
                    )
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(1.dp)
                        .clip(CutCornerShape(15.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color(0xF8081622),
                                    Color(0xF8040F18),
                                    Mascot.color(0xFF02090D)
                                )
                            )
                        )
                        .border(
                            BorderStroke(
                                1.dp,
                                Brush.verticalGradient(
                                    listOf(
                                        Color(0xFF4285F4).copy(alpha = 0.9f),
                                        CyberGlassBorder.copy(alpha = 0.35f),
                                        HioneNeonPink.copy(alpha = 0.7f)
                                    )
                                )
                            ),
                            CutCornerShape(15.dp)
                        )
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Header
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(CutCornerShape(8.dp))
                                        .background(Color(0x334285F4))
                                        .border(1.dp, Color(0xFF4285F4), CutCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CloudSync,
                                        contentDescription = null,
                                        tint = Color(0xFF4285F4),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "GOOGLE APPS",
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Black,
                                        fontFamily = AudiowideFont,
                                        letterSpacing = 0.8.sp
                                    )
                                    Text(
                                        text = "Turn Google apps on or off, one at a time or all at once",
                                        color = Color(0xFF4285F4),
                                        fontSize = 7.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = AudiowideFont
                                    )
                                }
                            }

                            com.hione.player.network.Cyber3dIconButton(
                                onClick = onDismissRequest,
                                icon = Icons.Default.Close,
                                contentDescription = "Close",
                                accentColor = HioneNeonPink
                            )
                        }

                        // Status Banner
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(CutCornerShape(6.dp))
                                .background(Mascot.color(0xFF041824))
                                .border(0.8.dp, CyberGlassBorder.copy(alpha = 0.4f), CutCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "STATUS: $statusMessage",
                                color = if (isProcessing) Color(0xFFFFD600) else HioneCyan,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = AudiowideFont
                            )
                            val activeCount = packageStates.values.count { it }
                            Text(
                                text = "Active: $activeCount / ${packageStates.size}",
                                color = Color.White,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = AudiowideFont
                            )
                        }

                        // Bulk Action Buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            com.hione.player.CyberTactileButton(
                                text = "TURN ALL OFF",
                                onClick = { setBulkState(false) },
                                accentColor = Color(0xFFFF1744),
                                modifier = Modifier.weight(1f)
                            )
                            com.hione.player.CyberTactileButton(
                                text = "TURN ALL ON",
                                onClick = { setBulkState(true) },
                                accentColor = Color(0xFF00E676),
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Divider(color = CyberGlassBorder.copy(alpha = 0.3f), thickness = 0.8.dp)

                        // Granular List of Google Apps
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(GOOGLE_ECOSYSTEM_TARGETS) { target ->
                                val isInstalled = installedSet.contains(target.packageName)
                                val isEnabled = packageStates[target.packageName] == true

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(CutCornerShape(8.dp))
                                        .background(if (isEnabled) Mascot.color(0xFF041926) else Mascot.color(0xFF10080C))
                                        .border(
                                            0.8.dp,
                                            if (isEnabled) Color(0xFF4285F4).copy(alpha = 0.6f) else Color(0x44FF1744),
                                            CutCornerShape(8.dp)
                                        )
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = target.displayName,
                                                    color = if (isEnabled) Color.White else Color.White.copy(alpha = 0.5f),
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Black,
                                                    fontFamily = AudiowideFont
                                                )
                                                if (target.isEssentialGms) {
                                                    Spacer(Modifier.width(4.dp))
                                                    Box(
                                                        modifier = Modifier
                                                            .clip(CutCornerShape(3.dp))
                                                            .background(Color(0x44FFD600))
                                                            .padding(horizontal = 3.dp, vertical = 1.dp)
                                                    ) {
                                                        Text("CORE GMS", color = Color(0xFFFFD600), fontSize = 6.sp, fontWeight = FontWeight.Bold)
                                                    }
                                                }
                                            }
                                            Text(
                                                text = target.description,
                                                color = HioneTextSecondary,
                                                fontSize = 7.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = target.packageName,
                                                color = if (isEnabled) Color(0xFF4285F4) else Color(0xFFFF5252),
                                                fontSize = 6.5.sp,
                                                fontFamily = AudiowideFont
                                            )
                                        }

                                        if (isInstalled) {
                                            Switch(
                                                checked = isEnabled,
                                                onCheckedChange = { checked ->
                                                    setPackageState(target.packageName, checked)
                                                },
                                                colors = SwitchDefaults.colors(
                                                    checkedThumbColor = Color.White,
                                                    checkedTrackColor = Color(0xFF4285F4),
                                                    uncheckedThumbColor = Color(0xFFFF5252),
                                                    uncheckedTrackColor = Color(0xFF28080E)
                                                ),
                                                modifier = Modifier.height(24.dp)
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .clip(CutCornerShape(4.dp))
                                                    .background(Color(0x22FFFFFF))
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text("NOT INSTALLED", color = HioneTextSecondary, fontSize = 7.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
