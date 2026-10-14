package com.hione.launcher.crash

import com.hione.launcher.Mascot
import com.hione.launcher.mascotPainter
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.launcher.R
import kotlin.system.exitProcess

/**
 * Oki Hione OS Fullscreen Kernel & Subsystem Panic Crash Screen.
 * Renders Morty "Get your s**t together!" fullscreen with diagnostic telemetry
 * and instant recovery controls.
 */
class HioneCrashActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Immersive black canvas
        window.addFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK

        val crashDetails = intent.getStringExtra("crash_details") ?: "No crash details were passed in"
        val errorType = intent.getStringExtra("error_type") ?: "${Mascot.osName} Panic"

        setContent {
            HioneCrashScreen(
                errorType = errorType,
                crashDetails = crashDetails,
                onRestartOs = {
                    try {
                        val launchIntent = packageManager.getLaunchIntentForPackage("com.hione.launcher")?.apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        }
                        if (launchIntent != null) startActivity(launchIntent)
                    } catch (_: Throwable) {}
                    finishAffinity()
                    Process.killProcess(Process.myPid())
                    exitProcess(10)
                }
            )
        }
    }
}

@Composable
fun HioneCrashScreen(
    errorType: String,
    crashDetails: String,
    onRestartOs: () -> Unit
) {
    val ctx = LocalContext.current
    var showDetails by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Morty "Get your s**t together" Fullscreen Visual Asset
            Image(
                painter = mascotPainter(id = R.drawable.img_os_crash),
                contentDescription = "${Mascot.name} OS Panic",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .clip(RoundedCornerShape(12.dp))
            )

            Spacer(Modifier.height(18.dp))

            // Cyber Diagnostics Banner
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Mascot.color(0xFF100407))
                    .border(1.dp, com.hione.launcher.ui.HioneIdentity.Coral.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
                    .padding(10.dp)
            ) {
                Column {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${Mascot.osName} crashed: $errorType",
                            color = com.hione.launcher.ui.HioneIdentity.Coral,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            if (showDetails) "HIDE LOGS" else "SHOW LOGS",
                            color = Mascot.color(0xFFFFC56B),
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable { showDetails = !showDetails }
                        )
                    }

                    if (showDetails) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = crashDetails,
                            color = Color(0xFFCCCCCC),
                            fontSize = 7.5.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 14
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            // Action Recovery Buttons Row
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Restart Hione OS Button
                Button(
                    onClick = onRestartOs,
                    colors = ButtonDefaults.buttonColors(containerColor = Mascot.color(0xFFFFC56B)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f).height(44.dp)
                ) {
                    Text(
                        "RESTART",
                        color = Color.Black,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black
                    )
                }

                // Copy Logs Button
                OutlinedButton(
                    onClick = {
                        try {
                            val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cb.setPrimaryClip(ClipData.newPlainText("${Mascot.osName} Crash Log", crashDetails))
                            Toast.makeText(ctx, "Crash log copied", Toast.LENGTH_SHORT).show()
                        } catch (_: Throwable) {}
                    },
                    border = BorderStroke(1.dp, Mascot.color(0xFF6FB0FF)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f).height(44.dp)
                ) {
                    Text(
                        "COPY LOGS",
                        color = Mascot.color(0xFF6FB0FF),
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
