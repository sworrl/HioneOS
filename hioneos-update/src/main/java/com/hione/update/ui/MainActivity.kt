package com.hione.update.ui

import com.hione.update.Mascot
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.update.BuildConfig
import com.hione.update.ota.AbUpdateEnginePath
import com.hione.update.ota.AbUpdater
import com.hione.update.ota.Installer
import com.hione.update.ota.OtaConfig
import com.hione.update.ota.OtaEngine
import com.hione.update.ota.OtaEngine.PackPhase as Phase2
import com.hione.update.ota.OtaEngine.Phase
import com.hione.update.ota.Prefs
import com.hione.update.ota.SignatureVerifier
import com.hione.update.ota.WebInstallerPath
import com.hione.update.work.UpdateWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HioneTheme { UpdateScreen() } }
    }

    override fun onResume() {
        super.onResume()
        // If PackageInstaller ever asks for a confirmation while we are on screen, show it here
        // instead of as a notification.
        Installer.onUserAction = { ctx, confirm, _ ->
            runCatching { startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { com.hione.update.ota.Notifier.userActionNeeded(ctx, confirm, "") }
        }
        OtaEngine.refreshInstalled(applicationContext)
    }

    override fun onPause() {
        Installer.onUserAction = null
        super.onPause()
    }

    companion object {
        /**
         * Work started from the screen keeps going if the user leaves it: it runs in the app's
         * process scope, not the activity's, so a rotation or a back press does not cut an install
         * in half.
         */
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }
}

private fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

private fun size(ctx: Context, bytes: Long) = Formatter.formatShortFileSize(ctx, bytes)

@Composable
fun UpdateScreen() {
    val ctx = LocalContext.current.applicationContext
    val s by OtaEngine.state.collectAsState()
    val ab by AbUpdater.state.collectAsState()
    val prefs = remember { Prefs(ctx) }
    var settingsTick by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        // Connects to update_engine (its current status comes straight back) and reports what
        // happened to a system update that was pending before the last restart.
        AbUpdater.afterBoot(ctx)
        OtaEngine.refreshInstalled(ctx)
        OtaEngine.refreshPacks(ctx)
        if (s.manifest == null && s.busy == null) MainActivity.appScope.launch { OtaEngine.check(ctx) }
    }
    LaunchedEffect(s.notice) {
        s.notice?.let { toast(ctx, it); OtaEngine.clearNotice() }
    }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Mascot.color(0xFF0A1A1D), HioneDarkBg, HioneDarkBg)))) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 10.dp, bottom = 28.dp),
        ) {
            item { Header(s) }
            item { StatusCard(ctx, s, prefs) }
            if (s.systemUpdate != null || ab.phase != AbUpdater.Phase.IDLE || ab.message != null) {
                item { SystemUpdateCard(ctx, s.systemUpdate, s.blockedByMinBuild, ab, s.deviceBuild) }
            }
            if (s.items.isNotEmpty()) item { UpdatesCard(ctx, s) }
            if (s.installed.isNotEmpty()) item { InstalledCard(ctx, s) }
            if (s.packs.isNotEmpty()) item { PacksCard(ctx, s) }
            item {
                // settingsTick forces a re-read of prefs after a change.
                SettingsCard(ctx, prefs, settingsTick) { settingsTick++ }
            }
            item { AboutCard(ctx, s, prefs, settingsTick) { settingsTick++ } }
        }
    }
}

@Composable
private fun Header(s: OtaEngine.State) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${Mascot.nameUpper}", color = HioneTealBright, fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            Spacer(Modifier.width(6.dp))
            Text("UPDATE", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        }
        Spacer(Modifier.height(2.dp))
        val build = s.deviceBuild?.let { "HioneOS $it" } ?: "${Mascot.osName} build unknown"
        Text("$build  ·  ${Prefs(LocalContext.current).channel} channel", color = HioneMuted, fontSize = 14.sp)
    }
}

@Composable
private fun StatusCard(ctx: Context, s: OtaEngine.State, prefs: Prefs) {
    val ready = s.installable
    val hot = s.error != null
    Section(title = "Status", hot = hot) {
        val headline = when {
            s.busy != null -> s.busy
            s.error != null -> "Something went wrong"
            s.manifest == null -> "Not checked yet"
            ready.isNotEmpty() -> if (ready.size == 1) "1 app update ready" else "${ready.size} app updates ready"
            s.items.any { it.phase == Phase.BLOCKED } -> "App updates are waiting on a system update"
            s.systemUpdate != null -> "Apps are up to date"
            else -> "Everything is up to date"
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (s.busy != null) {
                CircularProgressIndicator(Modifier.padding(end = 10.dp).width(18.dp).height(18.dp), color = HioneTealBright, strokeWidth = 2.dp)
            }
            Text(headline, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        s.error?.let {
            Spacer(Modifier.height(4.dp))
            BodyText(it, HionePinkBright)
        }
        Spacer(Modifier.height(4.dp))
        val last = prefs.lastCheckMillis
        BodyText(
            if (last == 0L) "No check yet."
            else "Last checked " + DateUtils.getRelativeTimeSpanString(last, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS) + "."
        )
        Spacer(Modifier.height(10.dp))
        ButtonRow {
            ActionButton("Check now", enabled = s.busy == null) {
                MainActivity.appScope.launch { OtaEngine.check(ctx) }
            }
            if (ready.isNotEmpty()) {
                val total = ready.sumOf { it.entry.size }
                ActionButton("Install all (${size(ctx, total)})", enabled = s.busy == null) {
                    MainActivity.appScope.launch { OtaEngine.installAll(ctx, includeHeld = true) }
                }
            }
            if (s.busy != null && s.items.any { it.phase == Phase.DOWNLOADING }) {
                ActionButton("Stop", danger = true) { OtaEngine.requestCancel() }
            }
        }
    }
}

@Composable
private fun SystemUpdateCard(
    ctx: Context,
    su: com.hione.update.ota.OtaManifest.SystemUpdate?,
    blocked: Boolean,
    ab: AbUpdater.State,
    deviceBuild: String?,
) {
    val payload = remember(su, deviceBuild) { su?.let { AbUpdateEnginePath.choosePayload(ctx, it, deviceBuild) } }
    val build = ab.build ?: su?.build ?: ""
    val needed = su != null && (su.required || blocked)
    val title = when (ab.phase) {
        AbUpdater.Phase.NEED_REBOOT -> "Restart to finish"
        AbUpdater.Phase.MERGING -> "Finishing the system update"
        AbUpdater.Phase.DOWNLOADING, AbUpdater.Phase.VERIFYING, AbUpdater.Phase.APPLYING, AbUpdater.Phase.FINALIZING -> "Installing a system update"
        else -> if (su == null) "System update" else if (needed) "System update needed" else "System update available"
    }
    var confirm by remember { mutableStateOf<String?>(null) }
    var showPc by remember { mutableStateOf(false) }
    Section(title = title, note = build.takeIf { it.isNotBlank() }?.let { "HioneOS $it" }, hot = true) {
        when (ab.phase) {
            AbUpdater.Phase.DOWNLOADING, AbUpdater.Phase.VERIFYING, AbUpdater.Phase.APPLYING, AbUpdater.Phase.FINALIZING -> {
                val line = when (ab.phase) {
                    AbUpdater.Phase.DOWNLOADING -> "Downloading" + if (ab.delta) " the changes since your build." else "."
                    AbUpdater.Phase.VERIFYING -> "Checking the download against its signed hash."
                    AbUpdater.Phase.APPLYING -> "Writing the new system next to the current one. Keep using the M500 if you like."
                    else -> "Almost done."
                }
                BodyText(line, Color.White)
                Spacer(Modifier.height(8.dp))
                val frac = if (ab.phase == AbUpdater.Phase.DOWNLOADING && ab.total > 0) ab.done.toFloat() / ab.total
                else if (ab.phase == AbUpdater.Phase.APPLYING || ab.phase == AbUpdater.Phase.FINALIZING) ab.percent else -1f
                if (frac >= 0f) {
                    LinearProgressIndicator(
                        progress = { frac.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = HionePink, trackColor = HioneSurface1,
                    )
                    Text(
                        if (ab.phase == AbUpdater.Phase.DOWNLOADING) "${size(ctx, ab.done)} of ${size(ctx, ab.total)}" else "${(frac * 100).toInt()}%",
                        color = HioneMuted, fontSize = 13.5.sp,
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)), color = HioneTealBright, trackColor = HioneSurface1)
                }
                Spacer(Modifier.height(10.dp))
                ButtonRow { ActionButton("Stop", danger = true) { AbUpdater.cancelOrUndo(ctx) } }
            }
            AbUpdater.Phase.NEED_REBOOT -> {
                BodyText("${Mascot.osName} $build is written to the spare slot. Restart to switch to it. If it does not start, the M500 goes back to the system it runs now by itself.", Color.White)
                Spacer(Modifier.height(10.dp))
                ButtonRow {
                    ActionButton("Restart now") { AbUpdater.restart(ctx) }
                    ActionButton("Undo", danger = true) { confirm = "undo" }
                }
            }
            AbUpdater.Phase.MERGING -> {
                BodyText("The new system is running. update_engine is folding it into place in the background. The M500 works as normal meanwhile.", Color.White)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { ab.percent.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = HioneTealBright, trackColor = HioneSurface1,
                )
            }
            else -> {
                if (su != null) {
                    BodyText(
                        when {
                            blocked -> "This device is on an older ${Mascot.osName} than these app updates were built for. Put ${Mascot.osName} ${su.build} on first, then app updates pick up again here."
                            payload != null -> "This one installs right here. Music, settings and apps stay. It takes a restart at the end."
                            else -> "Some changes, like the FM app, drivers and system settings, only come with a full image. That goes on from a computer, not from here."
                        },
                        Color.White,
                    )
                    if (su.notes.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        BodyText(su.notes)
                    }
                    if (payload != null) {
                        Spacer(Modifier.height(10.dp))
                        ButtonRow {
                            ActionButton("${AbUpdateEnginePath.actionLabel} (${size(ctx, payload.size)})") { confirm = "install" }
                        }
                    }
                }
            }
        }
        ab.message?.let {
            Spacer(Modifier.height(8.dp))
            BodyText(it, if (ab.phase == AbUpdater.Phase.FAILED) HionePinkBright else HioneMuted)
        }
        if (su != null && !ab.busy && ab.phase != AbUpdater.Phase.NEED_REBOOT && ab.phase != AbUpdater.Phase.MERGING) {
            if (payload != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    if (showPc) "Hide the computer option" else "Or put it on from a computer",
                    color = HioneMuted, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { showPc = !showPc }.padding(vertical = 4.dp),
                )
            }
            if (payload == null || showPc) WebInstallerBlock(ctx, WebInstallerPath(su.installerUrl, su.build))
        }
    }
    when (confirm) {
        "install" -> if (su != null && payload != null) {
            HioneAlert(
            title = "Install ${Mascot.osName} ${su.build}?",
            text = "This downloads ${size(ctx, payload.size)}" + (if (payload.isFull) "" else " (only what changed since your build)") +
                " and writes the new system next to the one you run now. Music, settings and apps stay. When it is done, a restart switches over. If the new system does not start, the M500 comes back to this one.",
            confirm = "Install",
            onConfirm = {
                confirm = null
                MainActivity.appScope.launch {
                    val url = OtaEngine.manifestUrl()
                    val err = if (url == null) "Check for updates first." else AbUpdater.start(ctx, su, url)
                    err?.let { OtaEngine.setNotice(it) }
                }
            },
            onDismiss = { confirm = null },
            )
        }
        "undo" -> HioneAlert(
            title = "Undo the update?",
            text = "This throws away the new system that was written to the spare slot. The M500 stays on the system it runs now.",
            confirm = "Undo",
            danger = true,
            onConfirm = { confirm = null; AbUpdater.cancelOrUndo(ctx) },
            onDismiss = { confirm = null },
        )
    }
}

@Composable
private fun WebInstallerBlock(ctx: Context, path: WebInstallerPath) {
    Spacer(Modifier.height(10.dp))
    BodyText("On a computer with Chrome or Edge, open the address below (or scan the code), plug the M500 in with USB, and follow the steps. Your music and settings stay unless you pick a clean install.")
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        QrCode(path.link(), 132.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(path.link(), color = HioneTealBright, fontSize = 13.5.sp, fontFamily = FontFamily.Monospace, lineHeight = 17.sp)
            Spacer(Modifier.height(8.dp))
            ActionButton(path.actionLabel) {
                try {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(path.link())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: ActivityNotFoundException) {
                    toast(ctx, "No browser here. Scan the code or type the address on a computer.")
                }
            }
        }
    }
}

@Composable
private fun UpdatesCard(ctx: Context, s: OtaEngine.State) {
    val m = s.manifest
    Section(title = "App updates", note = m?.let { "From the ${it.channel} channel, ${Mascot.osName} ${it.build}" }) {
        if (m != null && m.changelog.isNotBlank()) {
            var open by remember { mutableStateOf(false) }
            Text(
                if (open) "Hide what changed" else "What changed",
                color = HionePinkBright, fontSize = 14.5.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { open = !open }.padding(vertical = 4.dp),
            )
            if (open) {
                Text(
                    m.changelog, color = HioneWhite, fontSize = 14.sp, lineHeight = 18.sp,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(HioneSurface2).padding(10.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
        }
        s.items.forEach { item -> UpdateRow(ctx, item, s.busy == null) }
    }
}

@Composable
private fun UpdateRow(ctx: Context, item: OtaEngine.Item, idle: Boolean) {
    val e = item.entry
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(HioneSurface2).padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "${item.installedVersionName.ifBlank { item.installedVersionCode.toString() }} to ${e.versionName.ifBlank { e.versionCode.toString() }}  ·  ${size(ctx, e.size)}",
                    color = HioneMuted, fontSize = 13.5.sp,
                )
            }
            when (item.phase) {
                Phase.DONE -> Badge("Installed", HioneTealBright)
                Phase.BLOCKED -> Badge("Waiting", HioneGold)
                Phase.HELD -> Badge("Held", HioneGold)
                Phase.FAILED -> Badge("Failed", HionePinkBright)
                Phase.DOWNLOADING -> Badge("Downloading", HioneTealBright)
                Phase.VERIFYING -> Badge("Checking", HioneTealBright)
                Phase.INSTALLING -> Badge("Installing", HioneTealBright)
                Phase.READY -> Text(
                    "Install", color = HioneTealBright, fontSize = 14.5.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = idle) {
                        MainActivity.appScope.launch { OtaEngine.installOne(ctx, e.packageName) }
                    }.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
        if (item.phase == Phase.DOWNLOADING || item.phase == Phase.VERIFYING || item.phase == Phase.INSTALLING) {
            Spacer(Modifier.height(6.dp))
            if (item.phase == Phase.DOWNLOADING && e.size > 0) {
                LinearProgressIndicator(
                    progress = { (item.done.toFloat() / e.size).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = HionePink, trackColor = HioneSurface1,
                )
                Text("${size(ctx, item.done)} of ${size(ctx, e.size)}", color = HioneMuted, fontSize = 13.5.sp)
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)), color = HioneTealBright, trackColor = HioneSurface1)
            }
        }
        if (item.message.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(item.message, color = if (item.phase == Phase.FAILED) HionePinkBright else HioneMuted, fontSize = 13.5.sp, lineHeight = 17.sp)
        }
        if (item.phase == Phase.HELD && idle) {
            Text(
                "Install anyway", color = HioneTealBright, fontSize = 14.5.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { MainActivity.appScope.launch { OtaEngine.installOne(ctx, e.packageName) } }.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun PacksCard(ctx: Context, s: OtaEngine.State) {
    Section(
        title = "Content packs",
        note = "Mascots and their art, names and voice. Pick the active one in Settings, Theme & mascot.",
    ) {
        s.packs.forEach { p -> PackRow(ctx, p, s.busy == null) }
    }
}

@Composable
private fun PackRow(ctx: Context, p: OtaEngine.PackItem, idle: Boolean) {
    val e = p.offered
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(HioneSurface2).padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                val have = when {
                    p.installedVersion != null -> "Downloaded, version ${p.installedVersion}"
                    p.builtInVersion != null -> "Built in, version ${p.builtInVersion}"
                    else -> "Not on this device"
                }
                val offer = e?.let { "  ·  version ${it.version} is available, ${size(ctx, it.size)}" }
                    ?.takeIf { p.phase == Phase2.AVAILABLE || p.phase == Phase2.UPDATE || p.phase == Phase2.BLOCKED } ?: ""
                Text(have + offer, color = HioneMuted, fontSize = 13.5.sp)
            }
            when (p.phase) {
                Phase2.AVAILABLE, Phase2.UPDATE, Phase2.FAILED -> if (e != null) Text(
                    if (p.phase == Phase2.UPDATE) "Update" else if (p.phase == Phase2.FAILED) "Try again" else "Download",
                    color = HioneTealBright, fontSize = 14.5.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = idle) {
                        MainActivity.appScope.launch { OtaEngine.installPack(ctx, p.id) }
                    }.padding(horizontal = 10.dp, vertical = 6.dp),
                )
                Phase2.DOWNLOADING -> Badge("Downloading", HioneTealBright)
                Phase2.VERIFYING -> Badge("Checking", HioneTealBright)
                Phase2.BLOCKED -> Badge("Waiting", HioneGold)
                Phase2.INSTALLED -> Badge("Downloaded", HioneTealBright)
                Phase2.BUILT_IN -> Badge("Built in", HioneMuted)
            }
        }
        if (p.phase == Phase2.DOWNLOADING && e != null && e.size > 0) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { (p.done.toFloat() / e.size).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = HionePink, trackColor = HioneSurface1,
            )
        }
        if (p.message.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(p.message, color = if (p.phase == Phase2.FAILED) HionePinkBright else HioneMuted, fontSize = 13.5.sp, lineHeight = 17.sp)
        }
        if (p.installedVersion != null && idle && p.phase != Phase2.DOWNLOADING && p.phase != Phase2.VERIFYING) {
            Text(
                if (p.builtInVersion != null) "Remove the download (back to the built-in copy)" else "Remove",
                color = HionePinkBright, fontSize = 14.5.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable {
                    val ok = OtaEngine.removePack(ctx, p.id)
                    toast(ctx, if (ok) "Removed" else "Could not remove it")
                }.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun InstalledCard(ctx: Context, s: OtaEngine.State) {
    var confirm by remember { mutableStateOf<Pair<OtaEngine.InstalledApp, Boolean>?>(null) }
    Section(
        title = "Installed ${Mascot.osName} apps",
        note = "Image means the copy that came with ${Mascot.osName}. Updated means a newer copy is installed over it.",
    ) {
        s.installed.forEach { app ->
            Column(
                Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp)).background(HioneSurface2).padding(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(app.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(app.versionName.ifBlank { app.versionCode.toString() }, color = HioneMuted, fontSize = 13.5.sp)
                    }
                    if (app.pkg in OtaConfig.NEVER_UPDATE) Badge("Image only", HioneMuted)
                    else if (app.isUpdate) Badge("Updated", HionePinkBright) else Badge("Image", HioneTeal)
                }
                if (app.isUpdate && s.busy == null) {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        val kept = app.kept
                        if (kept != null && app.pkg != ctx.packageName) {
                            Text(
                                "Back to ${kept.versionName.ifBlank { kept.versionCode.toString() }}",
                                color = HioneTealBright, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable { confirm = app to false },
                            )
                        }
                        Text(
                            "Back to image version" + (app.imageVersionName?.let { " ($it)" } ?: ""),
                            color = HionePinkBright, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable { confirm = app to true },
                        )
                    }
                }
            }
        }
    }
    confirm?.let { (app, toImage) ->
        val target = if (toImage) "the version that came with ${Mascot.osName}" else "${app.kept?.versionName ?: "the previous version"}"
        HioneAlert(
            title = "Roll back ${app.name}?",
            text = "This puts ${app.name} back to $target. Its settings and data stay, but an older version may not understand everything a newer one saved. Automatic updates will skip ${app.versionName} from now on.",
            confirm = "Roll back",
            danger = true,
            onConfirm = {
                confirm = null
                MainActivity.appScope.launch {
                    val msg = OtaEngine.rollback(ctx, app.pkg, toImage)
                    OtaEngine.setNotice(msg)
                }
            },
            onDismiss = { confirm = null },
        )
    }
}

@Composable
private fun SettingsCard(ctx: Context, prefs: Prefs, tick: Int, changed: () -> Unit) {
    @Suppress("UNUSED_EXPRESSION") tick
    fun apply(block: () -> Unit) {
        block()
        UpdateWorker.schedule(ctx)
        changed()
    }
    Section(title = "Settings") {
        Text("Channel", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        val descriptions = mapOf(
            "stable" to "Tested builds. The right pick for most people.",
            "beta" to "Next release, mostly finished. Expect the odd rough edge.",
            "dev" to "Whatever was built last. Things will break sometimes.",
        )
        OtaConfig.CHANNELS.forEach { ch ->
            RadioRow(ch.replaceFirstChar { it.uppercase() }, descriptions[ch], prefs.channel == ch) {
                if (prefs.channel != ch) {
                    apply { prefs.channel = ch }
                    MainActivity.appScope.launch { OtaEngine.check(ctx) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        ToggleRow("Install app updates on their own", "Installs in the background after a scheduled check. Off means you get a notification instead.", prefs.autoInstall) {
            apply { prefs.autoInstall = it }
        }
        ToggleRow("Check automatically", null, prefs.scheduleEnabled) { apply { prefs.scheduleEnabled = it } }
        if (prefs.scheduleEnabled) {
            Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(6 to "6 h", 12 to "12 h", 24 to "Daily", 168 to "Weekly").forEach { (h, label) ->
                    Chip(label, prefs.intervalHours == h) { apply { prefs.intervalHours = h } }
                }
            }
            ToggleRow("Only on Wi-Fi", "Skip scheduled checks on metered connections.", prefs.wifiOnly) { apply { prefs.wifiOnly = it } }
            ToggleRow("Only while charging", null, prefs.chargingOnly) { apply { prefs.chargingOnly = it } }
        }
    }
}

@Composable
private fun AboutCard(ctx: Context, s: OtaEngine.State, prefs: Prefs, tick: Int, changed: () -> Unit) {
    @Suppress("UNUSED_EXPRESSION") tick
    val keys = remember { runCatching { SignatureVerifier.load(ctx).keyInfo() }.getOrDefault(emptyList()) }
    var showDev by remember { mutableStateOf(prefs.baseUrlOverride.isNotBlank()) }
    Section(title = "About") {
        Text("${Mascot.updateName} ${BuildConfig.VERSION_NAME}", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        BodyText("Every update list is signed, and every app is checked against its signed hash and against the key the installed app was signed with before anything installs. The FM app is never updated here. It only comes with a system image.")
        Spacer(Modifier.height(6.dp))
        s.manifest?.let { m ->
            val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
            BodyText("List published ${fmt.format(m.publishedAt)}, signed with the ${s.signedBy ?: "?"} key.")
        }
        keys.forEach { (name, fp) -> BodyText("Trusted $name key  $fp") }
        Spacer(Modifier.height(6.dp))
        Text(
            if (showDev) "Hide test server" else "Test server",
            color = HioneMuted, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable { showDev = !showDev }.padding(vertical = 4.dp),
        )
        if (showDev) {
            BodyText("Point at a test server on your network, like http://192.168.1.20:8000/ota/. Lists there still have to be signed with the real keys. Leave it empty for the normal server.")
            var text by remember { mutableStateOf(prefs.baseUrlOverride) }
            Spacer(Modifier.height(6.dp))
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 14.sp, fontFamily = FontFamily.Monospace),
                cursorBrush = SolidColor(HioneTealBright),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(HioneSurface2).padding(10.dp),
                decorationBox = { inner ->
                    if (text.isEmpty()) Text(OtaConfig.DEFAULT_BASE_URL, color = HioneMuted, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                    inner()
                },
            )
            Spacer(Modifier.height(6.dp))
            ButtonRow {
                ActionButton("Use this server") {
                    val t = text.trim()
                    if (t.isNotEmpty() && !(t.startsWith("http://") || t.startsWith("https://"))) {
                        toast(ctx, "That needs to start with http:// or https://")
                    } else {
                        prefs.baseUrlOverride = t
                        // A different server has its own publish history; start the replay check over.
                        OtaConfig.CHANNELS.forEach { prefs.setLastPublished(it, 0L) }
                        changed()
                        MainActivity.appScope.launch { OtaEngine.check(ctx) }
                    }
                }
                ActionButton("Reset", danger = true) {
                    text = ""
                    prefs.baseUrlOverride = ""
                    OtaConfig.CHANNELS.forEach { prefs.setLastPublished(it, 0L) }
                    changed()
                    MainActivity.appScope.launch { OtaEngine.check(ctx) }
                }
            }
            BodyText("Now using ${prefs.baseUrl}")
        }
    }
}
