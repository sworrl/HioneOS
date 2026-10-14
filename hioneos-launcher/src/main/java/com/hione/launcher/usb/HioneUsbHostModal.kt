package com.hione.launcher.usb

import com.hione.launcher.Mascot
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hione.launcher.AudiowideFont
import com.hione.launcher.HioneCyan
import com.hione.launcher.HioneNeonPink
import com.hione.launcher.ui.swipeUpFromBottomToDismiss
import kotlinx.coroutines.launch

/**
 * Cyberpunk Themed System-Level USB Host Controller Modal.
 * Offers clean mode switching (Direct ALSA USB DAC, MTP Files, Charge Only)
 * with robust persistence options (24 hours, 7 days, forever on this PC).
 */
@Composable
fun HioneUsbHostModal(
    onDismissRequest: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedDismissOption by remember { mutableStateOf(HioneUsbPreferences.DismissOption.ONCE) }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x99000000))
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null
                ) { onDismissRequest() }
                // System-gesture-style dismiss: swipe up starting at the bottom edge of the modal.
                .swipeUpFromBottomToDismiss(onDismiss = onDismissRequest),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Mascot.color(0xFF061418))
                    .border(
                        1.2.dp,
                        Brush.verticalGradient(
                            listOf(HioneCyan.copy(alpha = 0.8f), HioneNeonPink.copy(alpha = 0.6f))
                        ),
                        RoundedCornerShape(22.dp)
                    )
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null
                    ) {} // swallow clicks
                    .padding(18.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Header Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(HioneCyan.copy(alpha = 0.15f))
                                    .border(1.dp, HioneCyan.copy(alpha = 0.5f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Usb,
                                    contentDescription = null,
                                    tint = HioneCyan,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    "USB HOST CONNECTED",
                                    color = Color.White,
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = AudiowideFont,
                                    letterSpacing = 0.5.sp
                                )
                                Text(
                                    "Pick what the USB connection does",
                                    color = HioneCyan.copy(alpha = 0.7f),
                                    fontSize = 10.sp
                                )
                            }
                        }

                        IconButton(
                            onClick = onDismissRequest,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Close",
                                tint = Color.White.copy(alpha = 0.6f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    Text(
                        "Mode for the connected computer:",
                        color = Color(0xFFB0BEC5),
                        fontSize = 11.5.sp,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(12.dp))

                    // Mode Selection Options
                    // 1. USB DAC Master Mode
                    ModeButton(
                        title = "USB DAC (direct ALSA)",
                        // No rate/bit-depth claim: setUsbDacMode only writes Settings.Global work_mode
                        // and sys.usb.config=uac2 — nothing reads back the gadget's advertised format,
                        // and the host picks the rate anyway. "192kHz/32-bit Bit-Perfect" was invented.
                        subtitle = "The player shows up as a UAC2 sound card. The computer picks the sample rate.",
                        accentColor = HioneCyan,
                        onClick = {
                            // The sheet used to close immediately and save the preference as
                            // though the switch had happened — the setter's Boolean was thrown
                            // away and, before the fix in HioneUsbAudioHostManager, was an
                            // unconditional `true` anyway. Persist and dismiss only on a
                            // verified switch; otherwise say it did not take.
                            scope.launch {
                                val ok = HioneUsbAudioHostManager.setUsbDacMode(ctx, true)
                                if (ok) {
                                    HioneUsbPreferences.savePreference(ctx, HioneUsbPreferences.MODE_DAC, selectedDismissOption)
                                    onDismissRequest()
                                } else {
                                    android.widget.Toast.makeText(
                                        ctx, "USB DAC mode could not be applied on this build",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    )

                    Spacer(Modifier.height(8.dp))

                    // 2. MTP File Transfer
                    ModeButton(
                        title = "File transfer (MTP)",
                        // "High-speed" was never measured (the negotiated link speed is not read).
                        subtitle = "Copy files to internal storage and the SD card",
                        accentColor = Color(0xFF80D8FF),
                        onClick = {
                            scope.launch {
                                val ok = HioneUsbAudioHostManager.setMtpMode(ctx)
                                if (ok) {
                                    HioneUsbPreferences.savePreference(ctx, HioneUsbPreferences.MODE_MTP, selectedDismissOption)
                                    onDismissRequest()
                                } else {
                                    android.widget.Toast.makeText(
                                        ctx, "MTP mode could not be applied on this build",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    )

                    Spacer(Modifier.height(8.dp))

                    // 3. Charge Only
                    ModeButton(
                        title = "Charge only",
                        subtitle = "Charges the battery, no data",
                        accentColor = Color(0xFF69F0AE),
                        onClick = {
                            scope.launch {
                                val ok = HioneUsbAudioHostManager.setChargeOnlyMode(ctx)
                                if (ok) {
                                    HioneUsbPreferences.savePreference(ctx, HioneUsbPreferences.MODE_CHARGE, selectedDismissOption)
                                    onDismissRequest()
                                } else {
                                    android.widget.Toast.makeText(
                                        ctx, "Charge-only mode could not be applied on this build",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    )

                    Spacer(Modifier.height(14.dp))

                    // Persistence / Dismiss Options
                    Text(
                        "REMEMBER THIS CHOICE:",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = AudiowideFont,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(6.dp))

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Mascot.color(0xFF030D10))
                            .border(0.8.dp, Mascot.color(0xFF1E3A42), RoundedCornerShape(12.dp))
                            .padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        HioneUsbPreferences.DismissOption.values().forEach { option ->
                            val selected = selectedDismissOption == option
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) HioneCyan.copy(alpha = 0.12f) else Color.Transparent)
                                    .clickable { selectedDismissOption = option }
                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clip(CircleShape)
                                        .border(1.dp, if (selected) HioneCyan else Color.Gray, CircleShape)
                                        .background(if (selected) HioneCyan else Color.Transparent),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (selected) {
                                        Box(
                                            Modifier
                                                .size(5.dp)
                                                .clip(CircleShape)
                                                .background(Color.Black)
                                        )
                                    }
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    option.label,
                                    color = if (selected) Color.White else Color(0xFF90A4AE),
                                    fontSize = 10.5.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeButton(
    title: String,
    subtitle: String,
    accentColor: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Mascot.color(0xFF0A1F26))
            .border(1.dp, accentColor.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Column {
            Text(
                title,
                color = accentColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                color = Color(0xFFB0BEC5),
                fontSize = 10.sp
            )
        }
    }
}
