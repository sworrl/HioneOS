package com.hione.settings.sys

import android.app.Activity
import android.content.Context
import android.os.UserHandle
import android.util.Log
import java.lang.reflect.Method

/**
 * Reflection helpers for the @SystemApi / @hide calls the stock Settings app makes.
 *
 * HioneSettings is platform-signed, so hidden-API enforcement does not apply to it and the
 * signature-level permissions behind these calls are granted. The SDK stubs simply do not
 * declare them, which is the only reason reflection is needed. Every call returns null (or the
 * given default) on failure and logs why, so a page can show "Unknown" instead of a guess.
 */
object Hidden {
    const val TAG = "HioneSettings"

    private fun box(c: Class<*>): Class<*> = when (c) {
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        java.lang.Short.TYPE -> java.lang.Short::class.java
        java.lang.Byte.TYPE -> java.lang.Byte::class.java
        java.lang.Character.TYPE -> java.lang.Character::class.java
        else -> c
    }

    /** First public method named [name] whose parameters accept [args] (nulls match any object type). */
    fun find(cls: Class<*>, name: String, args: Array<out Any?>): Method? =
        (cls.methods.asSequence() + cls.declaredMethods.asSequence()).firstOrNull { m ->
            m.name == name && m.parameterTypes.size == args.size &&
                m.parameterTypes.indices.all { i ->
                    val a = args[i]
                    val p = m.parameterTypes[i]
                    if (a == null) !p.isPrimitive else box(p).isAssignableFrom(a.javaClass)
                }
        }?.also { it.isAccessible = true }

    /** Call an instance method; null when it does not exist or throws. */
    fun call(target: Any?, name: String, vararg args: Any?): Any? {
        if (target == null) return null
        return try {
            val m = find(target.javaClass, name, args)
                ?: throw NoSuchMethodException("${target.javaClass.name}.$name/${args.size}")
            m.invoke(target, *args)
        } catch (t: Throwable) {
            Log.w(TAG, "call $name failed: ${(t.cause ?: t).javaClass.simpleName}: ${(t.cause ?: t).message}")
            null
        }
    }

    /** Like [call], but reports success separately from a null return value. */
    fun tryCall(target: Any?, name: String, vararg args: Any?): Result<Any?> {
        if (target == null) return Result.failure(IllegalStateException("no target for $name"))
        return try {
            val m = find(target.javaClass, name, args)
                ?: return Result.failure(NoSuchMethodException("${target.javaClass.name}.$name/${args.size}"))
            Result.success(m.invoke(target, *args))
        } catch (t: Throwable) {
            val root = t.cause ?: t
            Log.w(TAG, "tryCall $name failed: ${root.javaClass.simpleName}: ${root.message}")
            Result.failure(root)
        }
    }

    fun callStatic(className: String, name: String, vararg args: Any?): Any? = try {
        val cls = Class.forName(className)
        val m = find(cls, name, args) ?: throw NoSuchMethodException("$className.$name/${args.size}")
        m.invoke(null, *args)
    } catch (t: Throwable) {
        Log.w(TAG, "callStatic $className.$name failed: ${(t.cause ?: t).message}")
        null
    }

    fun staticInt(className: String, field: String): Int? = try {
        Class.forName(className).getField(field).getInt(null)
    } catch (_: Throwable) { null }

    fun sysprop(key: String): String? = try {
        (Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            .invoke(null, key) as? String)?.trim()?.takeIf { it.isNotEmpty() }
    } catch (_: Throwable) { null }

    fun myUserId(): Int = try {
        UserHandle::class.java.getMethod("myUserId").invoke(null) as Int
    } catch (_: Throwable) { 0 }

    fun userHandleOf(userId: Int): UserHandle? = try {
        UserHandle::class.java.getMethod("of", Int::class.javaPrimitiveType).invoke(null, userId) as UserHandle
    } catch (_: Throwable) { null }

    /** Binder interface for a system service, e.g. ("notification", "android.app.INotificationManager"). */
    fun serviceInterface(serviceName: String, stubOwner: String): Any? = try {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, serviceName) as android.os.IBinder?
        val stub = Class.forName("$stubOwner\$Stub")
        stub.getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)
    } catch (t: Throwable) {
        Log.w(TAG, "serviceInterface $serviceName failed: ${t.message}")
        null
    }

    /**
     * Package of the app that started [activity]. getCallingPackage() is only set for
     * startActivityForResult; stock Settings asks ActivityClient for the launching package, which
     * a platform-signed caller is allowed to read. Falls back to the API 34 public getter.
     */
    fun launchingPackage(activity: Activity): String? {
        activity.callingPackage?.let { return it }
        try {
            val token = Activity::class.java.getMethod("getActivityToken").invoke(activity)
            val client = Class.forName("android.app.ActivityClient").getMethod("getInstance").invoke(null)
            (call(client, "getLaunchedFromPackage", token) as? String)?.let { return it }
        } catch (_: Throwable) {}
        return try { activity.launchedFromPackage } catch (_: Throwable) { null }
    }

    fun appLabel(ctx: Context, pkg: String?): String {
        if (pkg.isNullOrBlank()) return "An app"
        return try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Throwable) { pkg }
    }
}
