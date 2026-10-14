package com.hione.update.ota

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Keeps the APK that was running before an update, one per package, in filesDir/rollback.
 *
 * Android will not downgrade a /data update on a user build (ro.debuggable=0), so "go back one
 * version" is two steps: uninstall the update (the image copy comes back), then, if the kept APK
 * is newer than the image copy, install it on top. Both steps are upgrades as far as the package
 * manager is concerned. If the kept APK was the image version itself, nothing is kept and the
 * rollback is just the uninstall.
 */
class RollbackStore(private val ctx: Context) {
    private val dir = File(ctx.filesDir, "rollback").apply { mkdirs() }

    data class Kept(val pkg: String, val versionCode: Long, val versionName: String, val sha256: String, val file: File)

    /** Before installing an update over [pkg], keep the currently installed /data copy, if any. */
    fun keepCurrent(pkg: String) {
        val info = ApkVerifier.installedInfo(ctx, pkg) ?: return
        if (!ApkVerifier.isUpdatedSystemApp(info)) {
            // Running the image copy: rolling back is just "uninstall updates", nothing to keep.
            forget(pkg)
            return
        }
        val ai = info.applicationInfo ?: return
        if (!ai.splitSourceDirs.isNullOrEmpty()) return // split installs are not ours; do not half-keep one
        val src = File(ai.sourceDir ?: return)
        if (!src.isFile) return
        val pkgDir = File(dir, pkg).apply { mkdirs() }
        val tmp = File(pkgDir, "incoming.tmp")
        src.inputStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
        val vc = ApkVerifier.longVersion(info)
        val dest = File(pkgDir, "$vc.apk")
        pkgDir.listFiles()?.filter { it != tmp }?.forEach { it.delete() }
        tmp.renameTo(dest)
        val meta = JSONObject()
            .put("versionCode", vc)
            .put("versionName", info.versionName ?: "")
            .put("sha256", Http.sha256(dest))
        File(pkgDir, "meta.json").writeText(meta.toString())
    }

    fun kept(pkg: String): Kept? {
        val pkgDir = File(dir, pkg)
        val meta = File(pkgDir, "meta.json").takeIf { it.isFile } ?: return null
        return runCatching {
            val o = JSONObject(meta.readText())
            val vc = o.getLong("versionCode")
            val f = File(pkgDir, "$vc.apk")
            if (!f.isFile) return null
            Kept(pkg, vc, o.optString("versionName"), o.getString("sha256"), f)
        }.getOrNull()
    }

    fun forget(pkg: String) {
        File(dir, pkg).deleteRecursively()
    }
}
