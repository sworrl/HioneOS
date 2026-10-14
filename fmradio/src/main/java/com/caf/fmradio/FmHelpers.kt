package com.caf.fmradio

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Minimal helpers so the FM app (com.caf.fmradio) is self-contained — it can't depend on the
 * com.hione.player app module, so the few utilities it used are reproduced here.
 *
 * There used to be a RootShell here. Nothing called it, and nothing should: everything this app
 * needs from the platform it gets from being platform-signed, and shelling out would only have
 * produced results nobody checked.
 */

/** Installs a last-resort uncaught-exception guard so a tuner glitch closes cleanly
 *  instead of hard-crashing the process. */
object CrashSentinel {
    fun install(activity: Activity) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            android.util.Log.e("FmRadio", "uncaught in ${t.name}", e)
            prev?.uncaughtException(t, e)
        }
    }
}

@Composable
fun HioneBackButton(onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp)
            .clip(CircleShape)
            .background(Color(0x22FFC56B))
            .border(1.dp, HioneCyan, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Back", tint = HioneCyan, modifier = Modifier.size(26.dp))
    }
}
