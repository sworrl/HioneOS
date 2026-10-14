package com.hione.launcher.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.hione.launcher.R
import com.hione.launcher.Mascot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HioneProfileModal(
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { HioneProfileStore.init(ctx) }
    val activeProfile by HioneProfileStore.activeProfile.collectAsState()
    val allProfiles by HioneProfileStore.allProfiles.collectAsState()

    var isEditing by remember { mutableStateOf(false) }
    var isCreating by remember { mutableStateOf(false) }
    var editName by remember(activeProfile.id) { mutableStateOf(activeProfile.name) }
    var editHandle by remember(activeProfile.id) { mutableStateOf(activeProfile.handle) }

    val pickImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                val bmp: Bitmap = if (Build.VERSION.SDK_INT >= 28) {
                    val src = ImageDecoder.createSource(ctx.contentResolver, uri)
                    ImageDecoder.decodeBitmap(src) { decoder, _, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Media.getBitmap(ctx.contentResolver, uri)
                }
                HioneProfileStore.saveCustomAvatar(ctx, activeProfile.id, bmp)
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .wrapContentHeight()
                .clip(RoundedCornerShape(26.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF0F1A24), Color(0xFF060B0F))))
                .border(1.5.dp, Color(0xFF39C5BB).copy(alpha = 0.5f), RoundedCornerShape(26.dp))
                .padding(20.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AccountCircle, null, tint = Color(0xFF39C5BB), modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "LOCAL USER PROFILES",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Avatar with Camera / Photo change overlay
                Box(
                    modifier = Modifier
                        .size(86.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1B2A38))
                        .border(2.dp, Color(0xFF39C5BB), CircleShape)
                        .clickable { pickImageLauncher.launch("image/*") },
                    contentAlignment = Alignment.Center
                ) {
                    val customBmp = remember(activeProfile.avatarPath) {
                        HioneProfileStore.loadAvatarBitmap(activeProfile.avatarPath)
                    }
                    if (customBmp != null) {
                        Image(
                            bitmap = customBmp.asImageBitmap(),
                            contentDescription = "Avatar",
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = "Avatar",
                            tint = Color(0xFF39C5BB),
                            modifier = Modifier.size(48.dp)
                        )
                    }
                    // Photo Badge Overlay
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF39C5BB))
                            .border(1.5.dp, Color(0xFF0F1A24), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.PhotoCamera, null, tint = Color.Black, modifier = Modifier.size(14.dp))
                    }
                }

                Spacer(Modifier.height(10.dp))

                if (isEditing) {
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text("Display Name", fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(0.9f)
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = editHandle,
                        onValueChange = { editHandle = it },
                        label = { Text("Handle (@user)", fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(0.9f)
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                HioneProfileStore.updateProfile(
                                    ctx,
                                    activeProfile.copy(name = editName.trim(), handle = editHandle.trim())
                                )
                                isEditing = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF39C5BB), contentColor = Color.Black)
                        ) {
                            Text("SAVE", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(onClick = { isEditing = false }) {
                            Text("CANCEL", fontSize = 11.sp, color = Color.White)
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(activeProfile.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black)
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Default.Edit,
                            null,
                            tint = Color(0xFF39C5BB),
                            modifier = Modifier.size(16.dp).clickable { isEditing = true }
                        )
                    }
                    Text(activeProfile.handle, color = Color(0xFF39C5BB), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }

                Spacer(Modifier.height(14.dp))

                // Stats row
                val hours = activeProfile.totalListeningSeconds / 3600
                val dateStr = SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(activeProfile.createdEpochMs))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF14222E))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("LISTENING", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text("${hours}h", color = Color(0xFF39C5BB), fontSize = 14.sp, fontWeight = FontWeight.Black)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("LIKED SONGS", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text("${activeProfile.likedTrackIds.size}", color = Color(0xFFFF5FA2), fontSize = 14.sp, fontWeight = FontWeight.Black)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("JOINED", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text(dateStr, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Switch profile section
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("OTHER PROFILES", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "+ ADD PROFILE",
                        color = Color(0xFF39C5BB),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.clickable { isCreating = true }
                    )
                }

                Spacer(Modifier.height(8.dp))

                if (isCreating) {
                    var newName by remember { mutableStateOf("") }
                    var newHandle by remember { mutableStateOf("") }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF182A3A))
                            .padding(12.dp)
                    ) {
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            placeholder = { Text("Name (e.g. Justin)", fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = newHandle,
                            onValueChange = { newHandle = it },
                            placeholder = { Text("Handle (e.g. @justin)", fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    if (newName.isNotBlank()) {
                                        HioneProfileStore.createProfile(ctx, newName, newHandle)
                                        isCreating = false
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF39C5BB), contentColor = Color.Black)
                            ) {
                                Text("CREATE", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(onClick = { isCreating = false }) {
                                Text("CANCEL", fontSize = 11.sp, color = Color.White)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 140.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(allProfiles) { p ->
                        val isSel = p.id == activeProfile.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSel) Color(0xFF39C5BB).copy(alpha = 0.2f) else Color(0xFF101920))
                                .border(1.dp, if (isSel) Color(0xFF39C5BB) else Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                                .clickable { HioneProfileStore.switchProfile(ctx, p.id) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (isSel) Icons.Default.CheckCircle else Icons.Default.AccountCircle,
                                null,
                                tint = if (isSel) Color(0xFF39C5BB) else Color.White.copy(alpha = 0.5f),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Text(p.handle, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                            }
                            if (isSel) {
                                Text("ACTIVE", color = Color(0xFF39C5BB), fontSize = 10.sp, fontWeight = FontWeight.Black)
                            }
                        }
                    }
                }
            }
        }
    }
}
