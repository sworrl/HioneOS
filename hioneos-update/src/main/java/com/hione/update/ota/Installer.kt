package com.hione.update.ota

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.VersionedPackage
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * PackageInstaller sessions and uninstalls, turned into suspend calls.
 *
 * Silent: as the system UID this app is an installer that never needs user action
 * (PackageInstallerSession lets SYSTEM_UID through), and it also holds INSTALL_PACKAGES.
 * If a future build ever does answer STATUS_PENDING_USER_ACTION, the confirmation activity is
 * handed to [onUserAction] (the UI shows it, or a notification asks for a tap) and the call keeps
 * waiting for the final result.
 */
object Installer {
    private const val TAG = "HioneUpdate"
    const val ACTION_RESULT = "com.hione.update.INSTALL_RESULT"
    const val EXTRA_TOKEN = "token"

    class Result(val ok: Boolean, val status: Int, val message: String)

    private val tokens = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<Result>>()

    /** Set by the UI/notifier. Receives the system's confirmation intent if one is ever required. */
    @Volatile var onUserAction: ((Context, Intent, String) -> Unit)? = null

    suspend fun install(ctx: Context, apk: File, pkg: String): Result {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(pkg)
            setSize(apk.length())
            setInstallReason(PackageManager.INSTALL_REASON_UNKNOWN)
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
            }
        }
        val sessionId = pi.createSession(params)
        try {
            pi.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out, 64 * 1024) }
                    session.fsync(out)
                }
                val (token, deferred) = register()
                session.commit(sender(ctx, token, pkg))
                return await(token, deferred)
            }
        } catch (e: Exception) {
            runCatching { pi.abandonSession(sessionId) }
            Log.w(TAG, "install $pkg failed", e)
            return Result(false, PackageInstaller.STATUS_FAILURE, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Removes the /data update of a system app, which puts the image version back. Flags 0 means
     * "uninstall the update and roll back to the system copy" (DELETE_SYSTEM_APP would instead hide
     * the app for this user); the hidden overload adds DELETE_ALL_USERS so every user goes back.
     */
    suspend fun uninstallUpdates(ctx: Context, pkg: String): Result {
        val pi = ctx.packageManager.packageInstaller
        val (token, deferred) = register()
        val sender = sender(ctx, token, pkg)
        val vp = VersionedPackage(pkg, PackageManager.VERSION_CODE_HIGHEST)
        val viaHidden = runCatching {
            val m = PackageInstaller::class.java.getMethod(
                "uninstall", VersionedPackage::class.java, Int::class.javaPrimitiveType, IntentSender::class.java
            )
            m.invoke(pi, vp, DELETE_ALL_USERS, sender)
        }.isSuccess
        if (!viaHidden) {
            try {
                pi.uninstall(vp, sender)
            } catch (e: Exception) {
                pending.remove(token)
                return Result(false, PackageInstaller.STATUS_FAILURE, e.message ?: "uninstall failed")
            }
        }
        return await(token, deferred)
    }

    private const val DELETE_ALL_USERS = 0x00000002

    private fun register(): Pair<Int, CompletableDeferred<Result>> {
        val token = tokens.getAndIncrement()
        val d = CompletableDeferred<Result>()
        pending[token] = d
        return token to d
    }

    private suspend fun await(token: Int, d: CompletableDeferred<Result>): Result {
        val r = withTimeoutOrNull(10 * 60 * 1000L) { d.await() }
        pending.remove(token)
        return r ?: Result(false, PackageInstaller.STATUS_FAILURE, "No answer from the package installer")
    }

    private fun sender(ctx: Context, token: Int, pkg: String): IntentSender {
        val intent = Intent(ctx, InstallResultReceiver::class.java)
            .setAction(ACTION_RESULT)
            .setPackage(ctx.packageName)
            .putExtra(EXTRA_TOKEN, token)
            .putExtra("pkg", pkg)
        // Mutable: PackageInstaller fills in the status extras.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(ctx, token, intent, flags).intentSender
    }

    internal fun onResult(ctx: Context, intent: Intent) {
        val token = intent.getIntExtra(EXTRA_TOKEN, -1)
        val pkg = intent.getStringExtra("pkg") ?: "?"
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            Log.w(TAG, "$pkg: installer asked for user action")
            if (confirm != null) {
                val handler = onUserAction
                if (handler != null) handler(ctx, confirm, pkg) else Notifier.userActionNeeded(ctx, confirm, pkg)
            }
            return // the final status arrives on the same PendingIntent later
        }
        Log.i(TAG, "$pkg: status=$status $msg")
        pending.remove(token)?.complete(
            Result(status == PackageInstaller.STATUS_SUCCESS, status, msg.ifBlank { statusText(status) })
        )
    }

    fun statusText(status: Int): String = when (status) {
        PackageInstaller.STATUS_SUCCESS -> "Done"
        PackageInstaller.STATUS_FAILURE_ABORTED -> "Cancelled"
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "Blocked by the system"
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "Conflicts with what is installed"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Not compatible with this device"
        PackageInstaller.STATUS_FAILURE_INVALID -> "The APK is invalid"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage"
        else -> "Failed ($status)"
    }
}

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Installer.ACTION_RESULT) Installer.onResult(context.applicationContext, intent)
    }
}
