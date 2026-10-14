package com.hione.media.gallery

import android.app.Activity
import android.app.WallpaperManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.media.ui.GlassButton
import com.hione.media.ui.HioneSurface2
import com.hione.media.ui.HioneTeal
import com.hione.media.ui.HioneSounds
import com.hione.media.ui.HioneTheme
import com.hione.media.ui.TitleStyle
import com.hione.media.ui.glass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * "Set as wallpaper" for ATTACH_DATA on images and the system SET_WALLPAPER chooser entry.
 *
 * The image is centre-cropped to the panel's aspect and scaled to its real pixel size before it
 * is handed to WallpaperManager. Handing over the full photo would make the system keep a 12 MP
 * bitmap in memory for the wallpaper, and leave the crop to whatever the launcher decides.
 */
class WallpaperActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val start = if (intent.action == Intent.ACTION_ATTACH_DATA) intent.data else null
        HioneSounds.init(this)
        setContent {
            HioneTheme {
                WallpaperFlow(start, onDone = { ok ->
                    setResult(if (ok) Activity.RESULT_OK else Activity.RESULT_CANCELED)
                    finish()
                })
            }
        }
    }

    @Composable
    private fun WallpaperFlow(start: Uri?, onDone: (Boolean) -> Unit) {
        var uri by remember { mutableStateOf(start) }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val picked = r.data?.data
            if (r.resultCode == Activity.RESULT_OK && picked != null) uri = picked else onDone(false)
        }
        LaunchedEffect(Unit) {
            if (uri == null) {
                // SET_WALLPAPER carries no image: pick one from our own gallery first.
                picker.launch(Intent(Intent.ACTION_PICK).setClass(this@WallpaperActivity, GalleryActivity::class.java).setType("image/*"))
            }
        }
        uri?.let { Sheet(it, onDone) }
    }

    @Composable
    private fun Sheet(uri: Uri, onDone: (Boolean) -> Unit) {
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        var busy by remember { mutableStateOf(false) }
        val preview by produceState<ImageBitmap?>(null, uri) { value = MediaActions.decodeImageBitmap(ctx, uri, 1024) }

        fun apply(which: Int) {
            busy = true
            scope.launch {
                val ok = setWallpaper(uri, which)
                Toast.makeText(ctx, if (ok) "Wallpaper set" else "Could not set wallpaper", Toast.LENGTH_SHORT).show()
                // The line outlives this dialog: HioneSounds plays from the process, not the activity.
                if (ok) HioneSounds.say("gallery", "wallpaper_set")
                onDone(ok)
            }
        }

        Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
            Column(
                Modifier.fillMaxWidth().glass(26.dp, fillAlpha = 0.9f).padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("SET WALLPAPER", style = TitleStyle.copy(fontSize = 16.sp))
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier.width(150.dp).height(266.dp).clip(RoundedCornerShape(14.dp)).background(HioneSurface2),
                    contentAlignment = Alignment.Center
                ) {
                    preview?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                        ?: CircularProgressIndicator(color = HioneTeal, modifier = Modifier.size(32.dp))
                }
                Spacer(Modifier.height(16.dp))
                if (busy) {
                    CircularProgressIndicator(color = HioneTeal)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassButton("Home", icon = Icons.Rounded.Home, modifier = Modifier.weight(1f)) { apply(WallpaperManager.FLAG_SYSTEM) }
                        GlassButton("Lock", icon = Icons.Rounded.Lock, modifier = Modifier.weight(1f)) { apply(WallpaperManager.FLAG_LOCK) }
                    }
                    Spacer(Modifier.height(8.dp))
                    GlassButton("Both", icon = Icons.Rounded.Smartphone, filled = true, modifier = Modifier.fillMaxWidth()) {
                        apply(WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
                    }
                    Spacer(Modifier.height(8.dp))
                    GlassButton("Cancel", modifier = Modifier.fillMaxWidth()) { onDone(false) }
                }
            }
        }
    }

    private suspend fun setWallpaper(uri: Uri, which: Int): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val m = resources.displayMetrics
            val bounds = windowManager.maximumWindowMetrics.bounds
            val sw = maxOf(bounds.width(), m.widthPixels).coerceAtLeast(1)
            val sh = maxOf(bounds.height(), m.heightPixels).coerceAtLeast(1)
            val src = MediaActions.decodeForDisplay(this@WallpaperActivity, uri, maxEdge = maxOf(sw, sh) * 2, software = true)
                ?: return@runCatching false
            val cropped = centerCrop(src, sw, sh)
            val wm = WallpaperManager.getInstance(this@WallpaperActivity)
            wm.setBitmap(cropped, Rect(0, 0, cropped.width, cropped.height), true, which)
            true
        }.getOrDefault(false)
    }

    private fun centerCrop(src: Bitmap, w: Int, h: Int): Bitmap {
        val target = w / h.toFloat()
        val have = src.width / src.height.toFloat()
        val (cw, ch) = if (have > target) (src.height * target).roundToInt() to src.height else src.width to (src.width / target).roundToInt()
        val x = (src.width - cw) / 2
        val y = (src.height - ch) / 2
        val c = Bitmap.createBitmap(src, x, y, cw.coerceAtMost(src.width), ch.coerceAtMost(src.height))
        return if (c.width > w) Bitmap.createScaledBitmap(c, w, h, true) else c
    }
}
