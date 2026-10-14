package com.caf.fmradio

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What sits behind the tuner UI. Persisted in the FM prefs ("hione_fm"). */
enum class FmBackgroundMode(val label: String) {
    HIONE_ART("Hione art"),
    PROJECTM("projectM visualizer"),
}

object FmBackgroundPrefs {
    private const val KEY = "background_mode"
    private val _mode = MutableStateFlow<FmBackgroundMode?>(null)

    /** Default is the visualizer. Read once, then kept in memory. */
    fun mode(ctx: Context): StateFlow<FmBackgroundMode> {
        if (_mode.value == null) {
            val saved = ctx.getSharedPreferences("hione_fm", Context.MODE_PRIVATE).getString(KEY, null)
            _mode.value = FmBackgroundMode.entries.firstOrNull { it.name == saved } ?: FmBackgroundMode.PROJECTM
        }
        @Suppress("UNCHECKED_CAST")
        return _mode as StateFlow<FmBackgroundMode>
    }

    fun set(ctx: Context, m: FmBackgroundMode) {
        _mode.value = m
        ctx.getSharedPreferences("hione_fm", Context.MODE_PRIVATE).edit().putString(KEY, m.name).apply()
    }
}

/**
 * The FM screen's background: projectM (as in Hione Music's cassette mode) or the Hione art.
 *
 * Put it where the art is drawn now, as the first child of the screen's root Box, and pass the
 * existing art as [fallback]:
 *
 *     FmVisualizerBackground(fallback = { FmBackdrop() })
 *
 * [fallback] is drawn when the setting says Hione art, and also when libfmprojectm did not load,
 * so the screen is never left with a blank background.
 *
 * The visualizer is a GLSurfaceView below the window; Compose content drawn after this composable
 * shows on top of it. The scrim that keeps the UI readable is drawn inside the GL pass ([dim]).
 * It runs only while the activity is RESUMED, which also covers the screen going off.
 */
@Composable
fun FmVisualizerBackground(
    modifier: Modifier = Modifier.fillMaxSize(),
    dim: Float = 0.5f,
    fallback: @Composable () -> Unit,
) {
    val ctx = LocalContext.current
    val mode by remember { FmBackgroundPrefs.mode(ctx.applicationContext) }.collectAsState()
    if (mode != FmBackgroundMode.PROJECTM || !FmProjectMNative.available) {
        fallback()
        return
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val holder = remember { arrayOfNulls<FmProjectMSurfaceView>(1) }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> holder[0]?.onResume()
                Lifecycle.Event.ON_PAUSE -> holder[0]?.onPause()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    Box(modifier) {
        AndroidView(
            factory = { c ->
                FmProjectMSurfaceView(c, dim).also { v ->
                    holder[0] = v
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) v.onResume()
                }
            },
            update = { v -> v.setDim(dim) },
            onRelease = { v -> v.onPause(); if (holder[0] === v) holder[0] = null },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Settings row for the background choice. Drop it into FmSettings (FmSheets.kt) anywhere in the
 * column; it reads and writes the preference itself.
 */
@Composable
fun FmBackgroundSetting() {
    val ctx = LocalContext.current
    val mode by remember { FmBackgroundPrefs.mode(ctx.applicationContext) }.collectAsState()
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text("BACKGROUND", color = HioneTextSecondary, fontSize = 7.5.sp, fontFamily = AudiowideFont)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            FmBackgroundMode.entries.forEach { m ->
                val sel = m == mode
                val enabled = m != FmBackgroundMode.PROJECTM || FmProjectMNative.available
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                        .background(if (sel) HioneCyan else Color(0x22FFC56B))
                        .border(1.dp, if (sel) HioneCyan else CyberGlassBorder, RoundedCornerShape(8.dp))
                        .clickable(enabled = enabled) { FmBackgroundPrefs.set(ctx.applicationContext, m) }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        m.label,
                        color = when { sel -> Color.Black; enabled -> HioneTextSecondary; else -> CyberMuted },
                        fontSize = 8.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont,
                    )
                }
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            if (FmProjectMNative.available)
                "The visualizer is ${FmProjectMNative.version}, driven by the station you are " +
                    "listening to. With the tuner off or muted it drifts slowly on its own. It " +
                    "lowers its own resolution if the screen starts to struggle."
            else "The visualizer library is not available in this build, so the art is used.",
            color = HioneTextSecondary.copy(alpha = 0.8f), fontSize = 7.5.sp, lineHeight = 10.sp,
        )
    }
}
