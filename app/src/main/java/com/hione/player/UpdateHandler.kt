package com.hione.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.exoplayer.ExoPlayer

/**
 * "Graceful swap" update handling — see the design note in build.gradle.kts history for why this
 * is the shape it is: Android kills EVERY process of a package the instant a replacement APK is
 * installed, full stop, no exceptions, unrelated to minification — there is no supported way for
 * any part of this app (including a separate foreground service) to keep running through the
 * actual install step. What IS achievable, and what this delivers: the update never looks like a
 * crash. Before the kill, save exactly what's playing (full queue, index, position, play state)
 * and show a screen that says so. After Android silently relaunches this app post-install (see
 * [UpdateReceiver]), restore that exact state and dismiss the screen automatically — so the lived
 * experience is "screen said Updating… → screen said Resuming… → same song, same place," never a
 * silent freeze or a jump back to track 1.
 */
enum class UpdateOverlayMode { NONE, UPDATING, RESUMING }

object UpdateOverlay {
    var mode = mutableStateOf(UpdateOverlayMode.NONE)
    var versionLabel = mutableStateOf("")
}

object UpdateManager {
    private const val PREF_NAME = "hione_update_prefs"
    private const val KEY_PENDING = "update_pending"
    const val ACTION_UPDATE_STARTING = "com.hione.player.ACTION_UPDATE_STARTING"
    const val EXTRA_JUST_UPDATED = "just_updated"
    const val EXTRA_TARGET_VERSION = "target_version"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /** Snapshot the ENTIRE live queue (not just current track) — order, every item, current index. */
    fun saveQueueSnapshot(ctx: Context, player: ExoPlayer) {
        if (player.mediaItemCount == 0) return
        val ids = (0 until player.mediaItemCount).mapNotNull { i ->
            player.getMediaItemAt(i).mediaId.toLongOrNull()
        }
        if (ids.isEmpty()) return
        PlayerPreferences.saveQueue(ctx, ids, player.currentMediaItemIndex)
        PlayerPreferences.saveLastPlayback(ctx, ids.getOrElse(player.currentMediaItemIndex) { ids.first() }, player.currentPosition)
        PlayerPreferences.saveWasPlaying(ctx, player.isPlaying)
    }

    /** Called when an update is about to happen (dev: adb broadcast; future: the real OTA
     *  installer, right before it hands off to PackageInstaller). Shows the overlay, saves state,
     *  and marks "an update is pending" so the post-install relaunch knows to show Resuming. */
    fun beginGracefulUpdate(ctx: Context, player: ExoPlayer, targetVersion: String) {
        saveQueueSnapshot(ctx, player)
        prefs(ctx).edit().putBoolean(KEY_PENDING, player.isPlaying).apply()
        android.util.Log.i("HioneUpdate", "beginGracefulUpdate: isPlaying=${player.isPlaying} mediaItemCount=${player.mediaItemCount} target=$targetVersion")
        UpdateOverlay.versionLabel.value = targetVersion
        UpdateOverlay.mode.value = UpdateOverlayMode.UPDATING
    }

    /** True if this cold start was auto-triggered by [UpdateReceiver] because playback was live
     *  when the update kicked off — i.e. "was mid-song, needs the Resuming treatment." */
    fun wasPendingUpdate(ctx: Context): Boolean {
        val pending = prefs(ctx).getBoolean(KEY_PENDING, false)
        if (pending) prefs(ctx).edit().remove(KEY_PENDING).apply()
        return pending
    }
}

/** System delivers ACTION_MY_PACKAGE_REPLACED to the app's own package right after a replacement
 *  APK finishes installing — the one broadcast guaranteed even to a "stopped" freshly-updated app,
 *  no special permission needed. Only prompts if the user was actually mid-listen when the update
 *  started (see beginGracefulUpdate) — a notification after every unrelated background update
 *  would be its own annoyance, not a fix.
 *
 *  Confirmed live: a manifest broadcast receiver calling startActivity() directly gets silently
 *  blocked by Android's background-activity-launch restrictions ("Background activity launch
 *  blocked... callingUidProcState: RECEIVER... realCallingUidHasAnyVisibleWindow: false") — this
 *  is a deliberate, non-negotiable OS policy (the same one that stops any app from popping its own
 *  UI open uninvited), not something worth fighting with exemptions built for alarms/calls. The
 *  correct, always-permitted mechanism is a notification tap — that counts as user-initiated, so
 *  its PendingIntent is allowed to launch the Activity. One tap resumes exactly where it left off.
 */
class UpdateReceiver : BroadcastReceiver() {
    private val CHANNEL_ID = "hione_update"

    override fun onReceive(context: Context, intent: Intent) {
        android.util.Log.i("HioneUpdate", "UpdateReceiver.onReceive action=${intent.action}")
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!UpdateManager.wasPendingUpdate(context)) {
            android.util.Log.i("HioneUpdate", "no pending update — skipping resume notification")
            return
        }
        android.util.Log.i("HioneUpdate", "pending update found — posting resume notification")

        val launch = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(UpdateManager.EXTRA_JUST_UPDATED, true)
        }
        val pi = android.app.PendingIntent.getActivity(
            context, 0, launch,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                android.app.NotificationChannel(CHANNEL_ID, "Updates", android.app.NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val notif = androidx.core.app.NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("${Mascot.musicAppName} Player updated")
            .setContentText("Tap to pick up where you left off")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        nm.notify(7300, notif)
        android.util.Log.i("HioneUpdate", "resume notification posted")
    }
}

@Composable
fun UpdateOverlayScreen(modifier: Modifier = Modifier) {
    val mode = UpdateOverlay.mode.value
    // Safety net: if beginGracefulUpdate() fires but the anticipated install never actually
    // happens (a cancelled/failed OTA download, a stray test broadcast), this process is still
    // alive and would otherwise be stuck showing "Updating…" forever with no way out.
    LaunchedEffect(mode) {
        if (mode == UpdateOverlayMode.UPDATING) {
            kotlinx.coroutines.delay(60_000)
            if (UpdateOverlay.mode.value == UpdateOverlayMode.UPDATING) UpdateOverlay.mode.value = UpdateOverlayMode.NONE
        } else if (mode == UpdateOverlayMode.RESUMING) {
            kotlinx.coroutines.delay(800)
            UpdateOverlay.mode.value = UpdateOverlayMode.NONE
        }
    }
    if (mode == UpdateOverlayMode.NONE) return
    val (headline, sub) = when (mode) {
        UpdateOverlayMode.UPDATING -> "Updating ${Mascot.musicAppName} Player" to
            (UpdateOverlay.versionLabel.value.takeIf { it.isNotBlank() }?.let { "to $it. Tap the notification afterward to pick up where you left off" }
                ?: "Tap the notification afterward to pick up where you left off")
        UpdateOverlayMode.RESUMING -> "Resuming…" to "Picking up where you left off"
        UpdateOverlayMode.NONE -> "" to ""
    }
    Box(
        modifier.fillMaxSize().background(Mascot.color(0xFF071115)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = HioneTeal, modifier = Modifier.size(40.dp))
            Box(Modifier.padding(top = 20.dp)) {
                Text(headline, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center, fontFamily = AudiowideFont)
            }
            Box(Modifier.padding(top = 8.dp, start = 24.dp, end = 24.dp)) {
                Text(sub, color = HioneTeal.copy(alpha = 0.8f), fontSize = 13.sp, textAlign = TextAlign.Center)
            }
        }
    }
}
