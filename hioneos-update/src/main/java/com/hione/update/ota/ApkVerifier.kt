package com.hione.update.ota

import com.hione.update.Mascot
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

/**
 * The checks every APK passes before it reaches PackageInstaller. Any failure refuses the APK.
 *
 *  1. SHA-256 and size equal the signed manifest's (done by the caller right after download).
 *  2. The package is on the HioneOS allowlist and is not the FM app (OtaConfig).
 *  3. The APK's package name and versionCode are exactly what the manifest says.
 *  4. The package is already installed as a system app, i.e. it ships in this image. Hione Update
 *     only ever updates image apps; it never adds new ones.
 *  5. The APK's signing certificates equal the installed app's, compared as SHA-256 digests.
 *     Android would refuse a mismatched update anyway; checking first gives a clear message and
 *     means nothing unexpected ever gets as far as a session.
 *  6. The APK's minSdk fits this device, and its versionCode is newer than what is installed.
 */
object ApkVerifier {

    class Refused(message: String) : Exception(message)

    fun installedInfo(ctx: Context, pkg: String): PackageInfo? = try {
        ctx.packageManager.getPackageInfo(pkg, signingFlags())
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    /** PackageManager.MATCH_FACTORY_ONLY: @SystemApi, so not in the public SDK stubs. */
    private const val MATCH_FACTORY_ONLY = 0x00200000

    /** The copy in the system image, even when a /data update is installed over it. */
    fun factoryInfo(ctx: Context, pkg: String): PackageInfo? = try {
        ctx.packageManager.getPackageInfo(pkg, MATCH_FACTORY_ONLY or signingFlags())
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    fun isSystemApp(info: PackageInfo): Boolean =
        (info.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM != 0

    fun isUpdatedSystemApp(info: PackageInfo): Boolean =
        (info.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0

    /** Throws [Refused] unless [apk] is safe to install as [entry]. */
    fun verify(ctx: Context, apk: File, expectedPkg: String, expectedVersionCode: Long?) {
        if (!OtaConfig.isUpdatable(expectedPkg)) throw Refused("$expectedPkg is not a ${Mascot.osName} app this updater may touch")

        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        val archive = pm.getPackageArchiveInfo(apk.path, signingFlags())
            ?: throw Refused("The download for $expectedPkg is not a readable APK")
        if (archive.packageName != expectedPkg) {
            throw Refused("The download claims to be ${archive.packageName}, not $expectedPkg")
        }
        if (expectedVersionCode != null && longVersion(archive) != expectedVersionCode) {
            throw Refused("The download for $expectedPkg is version ${longVersion(archive)}, the manifest says $expectedVersionCode")
        }

        val installed = installedInfo(ctx, expectedPkg)
            ?: throw Refused("$expectedPkg is not in this image. New apps come with a full system update.")
        if (!isSystemApp(installed)) throw Refused("$expectedPkg here is not the system copy, so it is left alone")

        val want = signerDigests(installed)
        val got = signerDigests(archive)
        if (want.isEmpty() || got.isEmpty() || want != got) {
            throw Refused("The download for $expectedPkg is signed with a different key than the installed app")
        }

        val minSdk = archive.applicationInfo?.minSdkVersion ?: 0
        if (minSdk > Build.VERSION.SDK_INT) throw Refused("$expectedPkg needs Android SDK $minSdk")
    }

    fun longVersion(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()

    /**
     * Both flags: some releases only collect an archive's certificates when GET_SIGNATURES is set,
     * so asking for both gets signingInfo where it exists and signatures everywhere.
     */
    @Suppress("DEPRECATION")
    private fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        else PackageManager.GET_SIGNATURES

    /** SHA-256 of each current signer certificate. A rotated lineage is not accepted as "equal". */
    @Suppress("DEPRECATION")
    fun signerDigests(info: PackageInfo): Set<String> {
        val si = if (Build.VERSION.SDK_INT >= 28) info.signingInfo else null
        val sigs = when {
            si == null -> info.signatures
            si.hasMultipleSigners() -> si.apkContentsSigners
            else -> si.signingCertificateHistory?.takeLast(1)?.toTypedArray()
        }
        if (sigs.isNullOrEmpty()) return emptySet()
        return sigs.map { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }
}
