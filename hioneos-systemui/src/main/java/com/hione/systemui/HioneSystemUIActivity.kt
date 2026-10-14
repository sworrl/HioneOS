package com.hione.systemui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class HioneSystemUIActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HioneSystemUIConfigScreen(onExit = { finish() })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HioneSystemUIConfigScreen(onExit: () -> Unit) {
    val ctx = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${Mascot.nameUpper}", color = HioneTealBright, fontSize = 17.sp, fontWeight = FontWeight.Black)
                        Text("OS SYSTEMUI", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onExit) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Exit", tint = HioneTeal)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = HioneDarkBg)
            )
        },
        containerColor = HioneDarkBg
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Column(Modifier.hioneGlassCard().padding(14.dp)) {
                    Text("QUICK SETTINGS", color = HioneTealBright, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))

                    ConfigRow(
                        icon = Icons.Default.OpenInBrowser,
                        title = "Open the shade",
                        subtitle = "Quick settings tiles, notifications and sliders",
                        onClick = {
                            val intent = Intent(ctx, HioneShadeActivity::class.java)
                            ctx.startActivity(intent)
                        }
                    )
                }
            }

            item {
                Column(Modifier.hioneGlassCard().padding(14.dp)) {
                    Text("PERMISSIONS", color = HioneTealBright, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))

                    ConfigRow(
                        icon = Icons.Default.Layers,
                        title = "Display over other apps",
                        subtitle = "Needed to show the shade",
                        onClick = {
                            try {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${ctx.packageName}")
                                )
                                ctx.startActivity(intent)
                            } catch (_: Throwable) {}
                        }
                    )

                    Spacer(Modifier.height(8.dp))

                    ConfigRow(
                        icon = Icons.Default.AccessibilityNew,
                        title = "Accessibility service",
                        subtitle = "Needed for the pull-down from the top edge",
                        onClick = {
                            try {
                                ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            } catch (_: Throwable) {}
                        }
                    )

                    Spacer(Modifier.height(8.dp))

                    ConfigRow(
                        icon = Icons.Default.Notifications,
                        title = "Notification access",
                        subtitle = "Needed to show notifications in the shade",
                        onClick = {
                            try {
                                ctx.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                            } catch (_: Throwable) {}
                        }
                    )
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
fun ConfigRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = HioneTealBright, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = HioneMuted, fontSize = 11.sp)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = HioneMuted, modifier = Modifier.size(18.dp))
    }
}
