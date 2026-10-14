package com.hione.launcher.onboarding

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import com.hione.launcher.RootShell
import kotlinx.coroutines.delay
import java.io.File

/**
 * Non-Compose install plumbing for the onboarding wizard's Step 4 ("Install Apps").
 *
 * Responsibilities:
 *  - Detect which selected apps are BUNDLED on the ROM or storage partitions.
 *  - Drive root-accelerated silent "pm install" for 100% reliable one-tap background installs.
 *  - Fall back to standard PackageInstaller sessions for unrooted environments.
 *  - Hand off unbundled apps to the Play Store / Store endpoints when requested.
 *  - Accurately poll package presence so UI state transitions are rock-solid.
 */

private const val TAG = "HioneAppInstaller"

/** Per-app install state shown on Step 4 and used to gate the wizard's NEXT button. */
enum class InstallPhase { NotStarted, Installing, Installed, Failed, Skipped }

/** Phases that count as "resolved" — NEXT unlocks only once every selected app is here. */
val TerminalInstallPhases = setOf(InstallPhase.Installed, InstallPhase.Skipped)

/** Google Play Store — a bundled prerequisite that must exist before any pulled app can install. */
const val PLAY_STORE_PKG = "com.android.vending"

/** Broadcast action + extra used by the PackageInstaller commit status callback. */
const val INSTALL_STATUS_ACTION = "com.hione.launcher.onboarding.ACTION_APK_INSTALLED"
const val EXTRA_INSTALL_APP_ID = "com.hione.launcher.onboarding.EXTRA_APP_ID"

/**
 * Candidate on-ROM & storage directories that may hold bundled or cached APKs.
 */
val OptionalApkDirs = listOf(
    "/system/etc/hioneos-optional-apks",
    "/system/system/etc/hioneos-optional-apks",
    "/product/etc/hioneos-optional-apks",
    "/system_ext/etc/hioneos-optional-apks",
    "/vendor/etc/hioneos-optional-apks",
    "/odm/etc/hioneos-optional-apks",
    "/data/preloaded_apks",
    "/sdcard/apks",
    "/sdcard/Download",
    "/sdcard/hioneos-optional-apks",
    "/storage/emulated/0/apks",
    "/storage/emulated/0/Download",
    "/storage/emulated/0/hioneos-optional-apks",
    "/data/local/tmp"
)

/**
 * Scans all candidate directories plus any attached external SD card directories
 * for .apk files.
 */
fun findAllOptionalApkFiles(): List<File> {
    val result = mutableListOf<File>()
    val searchDirs = ArrayList<File>()

    OptionalApkDirs.forEach { path ->
        val dir = File(path)
        if (dir.exists() && dir.isDirectory) {
            searchDirs.add(dir)
        }
    }

    // Also check any mounted SD card storage under /storage/
    try {
        val storageRoot = File("/storage")
        if (storageRoot.exists() && storageRoot.isDirectory) {
            storageRoot.listFiles()?.forEach { sub ->
                if (sub.isDirectory && sub.name != "emulated" && sub.name != "self") {
                    listOf("apks", "hioneos-optional-apks", "Download").forEach { folder ->
                        val cardDir = File(sub, folder)
                        if (cardDir.exists() && cardDir.isDirectory) {
                            searchDirs.add(cardDir)
                        }
                    }
                    searchDirs.add(sub)
                }
            }
        }
    } catch (_: Throwable) {}

    searchDirs.forEach { dir ->
        try {
            dir.listFiles { f -> f.isFile && f.extension.equals("apk", ignoreCase = true) }?.forEach { apk ->
                result.add(apk)
            }
        } catch (_: Throwable) {}
    }

    return result
}

/**
 * Runtime index of the bundled APKs.
 */
class BundledApkIndex(
    val byPackage: Map<String, File>,
    val byStem: Map<String, File>
) {
    fun apkFor(app: ProvisionableApp): File? {
        return byPackage[app.packageName]
            ?: byStem[app.id.lowercase()]
            ?: byStem[app.packageName.substringAfterLast('.').lowercase()]
            ?: byStem[app.name.lowercase().replace(" ", "")]
            ?: byStem[app.name.lowercase().replace(" ", "_")]
    }

    fun apkForPackage(pkg: String): File? = byPackage[pkg]

    companion object {
        fun scan(pm: PackageManager): BundledApkIndex {
            val apks = findAllOptionalApkFiles()
            val byPackage = HashMap<String, File>()
            val byStem = HashMap<String, File>()

            apks.forEach { f ->
                val stem = f.nameWithoutExtension.lowercase()
                byStem[stem] = f
                byStem[stem.replace("-", "_")] = f
                byStem[stem.replace("_", "")] = f

                try {
                    val archiveInfo = pm.getPackageArchiveInfo(f.absolutePath, 0)
                    val realPkg = archiveInfo?.packageName
                    if (!realPkg.isNullOrBlank()) {
                        byPackage[realPkg] = f
                    }
                } catch (_: Throwable) {}
            }

            Log.i(TAG, "Scanned ${apks.size} APKs: indexed ${byPackage.size} packages, ${byStem.size} stems")
            return BundledApkIndex(byPackage, byStem)
        }
    }
}

/** True if [pkg] is currently installed and visible to us. */
fun isPackageInstalled(pm: PackageManager, pkg: String): Boolean {
    return try {
        pm.getPackageInfo(pkg, 0)
        true
    } catch (_: Throwable) {
        if (RootShell.isAvailable()) {
            val out = RootShell.execOut("pm path $pkg")
            !out.isNullOrBlank() && out.contains("package:")
        } else {
            false
        }
    }
}

/**
 * Executes high-speed installation of an APK file.
 * Prioritizes silent root `pm install -r -d -g` if RootShell is active.
 * Falls back to PackageInstaller session if unrooted.
 */
fun performAppInstall(ctx: Context, apk: File, appId: String): Boolean {
    // 1. Root Shell Silent Installation (Instant & 100% Reliable on HioneOS)
    if (RootShell.isAvailable()) {
        try {
            Log.i(TAG, "Attempting root install for $appId from ${apk.absolutePath}")
            val out = RootShell.execOut("pm install -r -d -g \"${apk.absolutePath}\"")
            if (out != null && out.contains("Success", ignoreCase = true)) {
                Log.i(TAG, "Root install succeeded for $appId: $out")
                return true
            } else {
                Log.w(TAG, "Root install returned non-success for $appId: $out, falling back")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Root install threw exception for $appId", t)
        }
    }

    // 2. PackageInstaller Fallback for Unrooted Runs
    return try {
        commitBundledInstall(ctx, apk, appId)
        true
    } catch (e: Throwable) {
        Log.e(TAG, "PackageInstaller session failed for $appId", e)
        false
    }
}

/**
 * Streams a bundled APK into a PackageInstaller session and commits it.
 */
fun commitBundledInstall(ctx: Context, apk: File, appId: String) {
    val pi = ctx.packageManager.packageInstaller
    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
    val sid = pi.createSession(params)
    pi.openSession(sid).use { session ->
        apk.inputStream().use { input ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                input.copyTo(out)
                session.fsync(out)
            }
        }
        val intent = Intent(INSTALL_STATUS_ACTION)
            .setPackage(ctx.packageName)
            .putExtra(EXTRA_INSTALL_APP_ID, appId)
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pending = PendingIntent.getBroadcast(
            ctx, sid, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutable
        )
        session.commit(pending.intentSender)
    }
}

/** Opens the Play Store details page for [pkg] (falls back to the web store). */
fun openPlayStore(ctx: Context, pkg: String) {
    val newTask = Intent.FLAG_ACTIVITY_NEW_TASK
    try {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
                .setPackage(PLAY_STORE_PKG)
                .addFlags(newTask)
        )
    } catch (_: Throwable) {
        try {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))
                    .addFlags(newTask)
            )
        } catch (_: Throwable) { }
    }
}

/** Polls until [pkg] appears or [timeoutMs] elapses. Returns final presence. */
suspend fun awaitPackageInstalled(
    pm: PackageManager,
    pkg: String,
    timeoutMs: Long,
    intervalMs: Long = 1000L
): Boolean {
    val start = System.currentTimeMillis()
    while (System.currentTimeMillis() - start < timeoutMs) {
        if (isPackageInstalled(pm, pkg)) return true
        delay(intervalMs)
    }
    return isPackageInstalled(pm, pkg)
}
