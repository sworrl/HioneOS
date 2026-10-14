package com.hione.launcher.ui

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Apps with notifications in the Hione shade, for the top bar. Read from HioneSystemUI's
 * HioneNotificationTrayProvider (one row per app, newest first). The provider is guarded by
 * android.permission.STATUS_BAR, which this platform-signed app holds.
 */
object HioneNotificationTray {
    val URI: Uri = Uri.parse("content://com.hione.systemui.notifications/apps")

    data class App(val pkg: String, val count: Int, val icon: ImageBitmap?)

    fun read(ctx: Context): List<Pair<String, Int>> = runCatching {
        ctx.contentResolver.query(URI, null, null, null, null)?.use { c ->
            val out = ArrayList<Pair<String, Int>>()
            val iPkg = c.getColumnIndex("pkg"); val iCount = c.getColumnIndex("count")
            while (c.moveToNext()) out += c.getString(iPkg) to c.getInt(iCount)
            out
        } ?: emptyList()
    }.getOrDefault(emptyList())
}

private val iconCache = HashMap<String, ImageBitmap?>()

private fun appIcon(ctx: Context, pkg: String, px: Int): ImageBitmap? = synchronized(iconCache) {
    iconCache.getOrPut(pkg) {
        runCatching { ctx.packageManager.getApplicationIcon(pkg).toBitmap(px, px).asImageBitmap() }.getOrNull()
    }
}

/** Live list of apps with notifications. Empty when HioneSystemUI is not reachable. */
@Composable
fun rememberNotificationTray(): List<HioneNotificationTray.App> {
    val ctx = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { version++ }
        }
        runCatching { ctx.contentResolver.registerContentObserver(HioneNotificationTray.URI, false, obs) }
        onDispose { runCatching { ctx.contentResolver.unregisterContentObserver(obs) } }
    }
    val px = (14 * ctx.resources.displayMetrics.density).toInt().coerceAtLeast(16)
    val apps by produceState(initialValue = emptyList<HioneNotificationTray.App>(), version) {
        value = withContext(Dispatchers.IO) {
            HioneNotificationTray.read(ctx).map { (pkg, n) -> HioneNotificationTray.App(pkg, n, appIcon(ctx, pkg, px)) }
        }
    }
    return apps
}

/** Up to [max] app icons, then "+N" for the rest. */
@Composable
fun HioneNotificationTrayIcons(apps: List<HioneNotificationTray.App>, max: Int = 5, size: Dp = 13.dp, textColor: Color) {
    if (apps.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        apps.take(max).forEach { a ->
            val bmp = a.icon
            if (bmp != null) Image(bmp, contentDescription = a.pkg, modifier = Modifier.size(size).clip(RoundedCornerShape(3.dp)))
        }
        if (apps.size > max) {
            Text("+${apps.size - max}", color = textColor, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
        }
    }
}
