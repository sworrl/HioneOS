package com.hione.media.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

fun Context.hasPermission(p: String): Boolean =
    checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

object MediaPerms {
    val visual = arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        Manifest.permission.ACCESS_MEDIA_LOCATION
    )

    /** Full library access: both READ_MEDIA_* granted. */
    fun fullVisual(ctx: Context) =
        ctx.hasPermission(Manifest.permission.READ_MEDIA_IMAGES) &&
            ctx.hasPermission(Manifest.permission.READ_MEDIA_VIDEO)

    /**
     * "Select photos" on Android 14 grants only READ_MEDIA_VISUAL_USER_SELECTED. MediaStore then
     * returns just the chosen items, so the gallery still works on that subset and offers a way
     * to widen it.
     */
    fun partialVisual(ctx: Context) =
        !fullVisual(ctx) && ctx.hasPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)

    fun anyVisual(ctx: Context) = fullVisual(ctx) || partialVisual(ctx)
}

/**
 * Permission state that re-checks on every resume, because the user can change grants in
 * Settings while the app sits in the background. [version] bumps on each change so callers can
 * key effects (a MediaStore reload) on it.
 */
class PermissionGate(
    val version: Int,
    val request: () -> Unit
)

@Composable
fun rememberPermissionGate(perms: Array<String>, onResult: (Map<String, Boolean>) -> Unit = {}): PermissionGate {
    val ctx = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        version++
        onResult(it)
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        var last = perms.map { ctx.hasPermission(it) }
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                val now = perms.map { ctx.hasPermission(it) }
                if (now != last) { last = now; version++ }
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return PermissionGate(version) { launcher.launch(perms) }
}
