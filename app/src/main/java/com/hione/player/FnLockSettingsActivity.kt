package com.hione.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.player.CyberDarkBg
import com.hione.player.CyberGlassBorder
import com.hione.player.HioneCyan
import com.hione.player.HioneNeonPink
import com.hione.player.HioneTextPrimary
import com.hione.player.HioneTextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class FnLockSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        CrashSentinel.install(this)
        super.onCreate(savedInstanceState)
        setContent {
            FnLockSettingsScreen(onBack = { finish() })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FnLockSettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val cr = ctx.contentResolver
    val scope = rememberCoroutineScope()

    var fnMode by remember {
        mutableStateOf(android.provider.Settings.Global.getString(cr, "fn_settings") ?: "touch_and_key_lock")
    }
    var allowVolume by remember {
        mutableStateOf(android.provider.Settings.Global.getInt(cr, "m500_fn_allow_volume_wheel", 0) == 1)
    }
    var lockPower by remember {
        mutableStateOf(android.provider.Settings.Global.getInt(cr, "m500_fn_lock_power_button", 1) == 1)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(CyberDarkBg)
    ) {
        // Bespoke Oki Hione Pose Backdrop
        Image(
            painter = mascotPainter(id = R.drawable.hione_pose_headphones),
            contentDescription = "${Mascot.name} artwork",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // Frosted Dark Cyan Gradient Overlay
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Mascot.color(0xEE0C0D10),
                            Color(0xBB000000),
                            Mascot.color(0xF80C0D10)
                        )
                    )
                )
        )

        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
        ) {
            // Header Bar
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                com.hione.player.ui.HioneBackButton(onClick = onBack)

                Text(
                    "FN KEY & POCKET LOCK",
                    color = HioneCyan,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = AudiowideFont,
                    letterSpacing = 1.sp
                )

                // Status Badge
                Box(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Mascot.color(0x33FFC56B))
                        .border(1.dp, HioneCyan, RoundedCornerShape(12.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        "HARDWARE",
                        color = HioneCyan,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = AudiowideFont
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Info Banner
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(Mascot.color(0xDD0A1E26))
                            .border(1.5.dp, HioneCyan, RoundedCornerShape(18.dp))
                            .padding(14.dp)
                    ) {
                        Text(
                            "FN SWITCH",
                            color = Color.White,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = AudiowideFont
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Sets what the side switch locks, so the player doesn't skip tracks or wake up in your pocket.",
                            color = HioneTextSecondary,
                            fontSize = 10.5.sp,
                            lineHeight = 14.5.sp
                        )
                    }
                }

                // Switch Behavior Mode Selection
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Mascot.color(0xDD0A1E26))
                            .border(1.dp, CyberGlassBorder, RoundedCornerShape(16.dp))
                            .padding(14.dp)
                    ) {
                        Text(
                            "WHAT THE SWITCH LOCKS",
                            color = HioneCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = AudiowideFont
                        )
                        Spacer(Modifier.height(10.dp))

                        val modes = listOf(
                            Triple("touch_and_key_lock", "Touchscreen & Key Lock (Recommended)", "Locks the touchscreen and side buttons. The volume wheel still works."),
                            Triple("key_lock", "Key Lock Only", "Locks the skip and play/pause buttons. The touchscreen still works."),
                            Triple("touch_lock", "Touchscreen Lock Only", "Locks the touchscreen. The playback buttons still work.")
                        )

                        modes.forEach { (id, label, desc) ->
                            val isSel = fnMode == id
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSel) Mascot.color(0x33FFC56B) else Color.Transparent)
                                    .clickable {
                                        fnMode = id
                                        try {
                                            android.provider.Settings.Global.putString(cr, "fn_settings", id)
                                        } catch (t: Throwable) {
                                            // WRITE_SECURE_SETTINGS is a platform grant on this build; a
                                            // failure here is a permission bug to fix, not something a
                                            // (non-existent) su shell can paper over.
                                            android.util.Log.w("FnLockSettings", "fn_settings write refused: $t")
                                        }
                                        val isCurrentlyLocked = android.provider.Settings.Global.getInt(cr, "fn_status", 0) == 1
                                        if (isCurrentlyLocked) {
                                            HionePocketLockManager.applyLockState(ctx, isLocked = true, showHud = false)
                                        }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSel,
                                    onClick = {
                                        fnMode = id
                                        try {
                                            android.provider.Settings.Global.putString(cr, "fn_settings", id)
                                        } catch (t: Throwable) {
                                            // WRITE_SECURE_SETTINGS is a platform grant on this build; a
                                            // failure here is a permission bug to fix, not something a
                                            // (non-existent) su shell can paper over.
                                            android.util.Log.w("FnLockSettings", "fn_settings write refused: $t")
                                        }
                                        val isCurrentlyLocked = android.provider.Settings.Global.getInt(cr, "fn_status", 0) == 1
                                        if (isCurrentlyLocked) {
                                            HionePocketLockManager.applyLockState(ctx, isLocked = true, showHud = false)
                                        }
                                    },
                                    colors = RadioButtonDefaults.colors(selectedColor = HioneCyan, unselectedColor = Color.Gray)
                                )
                                Spacer(Modifier.width(6.dp))
                                Column {
                                    Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Text(desc, color = HioneTextSecondary, fontSize = 10.sp, lineHeight = 13.5.sp)
                                }
                            }
                        }
                    }
                }

                // Granular Button Lock Safeguards
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Mascot.color(0xDD0A1E26))
                            .border(1.dp, CyberGlassBorder, RoundedCornerShape(16.dp))
                            .padding(14.dp)
                    ) {
                        Text(
                            "POCKET OPTIONS",
                            color = HioneCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = AudiowideFont
                        )
                        Spacer(Modifier.height(10.dp))

                        // Allow Rotary Volume Wheel
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Allow Volume Wheel in Pocket", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text("The volume wheel keeps working while Fn lock is on.", color = HioneTextSecondary, fontSize = 10.sp)
                            }
                            Switch(
                                checked = allowVolume,
                                onCheckedChange = {
                                    allowVolume = it
                                    val v = if (it) 1 else 0
                                    try {
                                        android.provider.Settings.Global.putInt(cr, "m500_fn_allow_volume_wheel", v)
                                    } catch (t: Throwable) {
                                        android.util.Log.w("FnLockSettings", "m500_fn_allow_volume_wheel write refused: $t")
                                    }
                                    val isCurrentlyLocked = android.provider.Settings.Global.getInt(cr, "fn_status", 0) == 1
                                    if (isCurrentlyLocked) {
                                        HionePocketLockManager.applyLockState(ctx, isLocked = true, showHud = false)
                                    }
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = HioneCyan, checkedTrackColor = Mascot.color(0xFF00695C))
                            )
                        }

                        Spacer(Modifier.height(10.dp))
                        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                        Spacer(Modifier.height(10.dp))

                        // Lock Power Button
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Lock Power Key When Switched", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text("Stops the power button from waking the screen in your pocket.", color = HioneTextSecondary, fontSize = 10.sp)
                            }
                            Switch(
                                checked = lockPower,
                                onCheckedChange = {
                                    lockPower = it
                                    val v = if (it) 1 else 0
                                    try {
                                        android.provider.Settings.Global.putInt(cr, "m500_fn_lock_power_button", v)
                                    } catch (t: Throwable) {
                                        android.util.Log.w("FnLockSettings", "m500_fn_lock_power_button write refused: $t")
                                    }
                                    val isCurrentlyLocked = android.provider.Settings.Global.getInt(cr, "fn_status", 0) == 1
                                    if (isCurrentlyLocked) {
                                        HionePocketLockManager.applyLockState(ctx, isLocked = true, showHud = false)
                                    }
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = HioneNeonPink, checkedTrackColor = Mascot.color(0xFF880E4F))
                            )
                        }
                    }
                }

                item {
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}
