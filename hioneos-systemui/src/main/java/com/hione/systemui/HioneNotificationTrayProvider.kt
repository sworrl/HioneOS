package com.hione.systemui

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * Read-only summary of the notifications the Hione shade holds, for the launcher's top bar tray.
 * One row per app that has notifications: package, how many, newest post time, whether any can
 * be cleared. Ordered newest first. The full notifications never leave this process.
 *
 * Guarded by android.permission.STATUS_BAR (signature|privileged) in the manifest, so only
 * platform-signed HioneOS apps can read it. Observers get notifyChange on every change.
 */
class HioneNotificationTrayProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "com.hione.systemui.notifications"
        val URI: Uri = Uri.parse("content://$AUTHORITY/apps")
        val COLUMNS = arrayOf("pkg", "count", "latest", "clearable")

        fun notifyChanged(ctx: Context) {
            runCatching { ctx.contentResolver.notifyChange(URI, null) }
        }
    }

    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val c = MatrixCursor(COLUMNS)
        val byPkg = LinkedHashMap<String, MutableList<HioneNotif>>()
        HioneNotificationStore.items.value
            .sortedByDescending { it.postTime }
            .forEach { byPkg.getOrPut(it.pkg) { ArrayList() }.add(it) }
        byPkg.forEach { (pkg, list) ->
            c.addRow(arrayOf<Any>(pkg, list.size, list.maxOf { it.postTime }, if (list.any { it.isClearable }) 1 else 0))
        }
        c.setNotificationUri(context!!.contentResolver, URI)
        return c
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.hione.notification-app"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
