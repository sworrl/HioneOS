package com.hione.update.mascot

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.FileNotFoundException

/**
 * Serves mascot packs to the other HioneOS apps (Mascot.kt in each of them). Read only. The
 * manifest guards it with the signature permission com.hione.permission.MASCOT_PACKS, which only
 * apps signed with the platform key can hold.
 *
 *   content://com.hione.update.mascot/packs                    id, version, source, json
 *   content://com.hione.update.mascot/art/<pack>/<key>         an art slot ("avatar",
 *                                                             "wallpaper.summer") or a drawable
 *                                                             name ("hione_wall_00")
 *   content://com.hione.update.mascot/voice/<pack>/<name>      a sound category from
 *                                                             sounds.clips, or voice/raw/<name>.ogg
 *   content://com.hione.update.mascot/file/<pack>/<path>       any file in the pack
 *
 * Every path is checked by PackFiles.safeRel and must be a file the pack lists, so nothing
 * outside a pack can be read through here.
 */
class MascotPackProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "com.hione.update.mascot"
        val COLUMNS = arrayOf("id", "version", "source", "json")
    }

    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val ctx = context ?: return null
        val seg = uri.pathSegments
        if (seg.isEmpty() || seg[0] != "packs") return null
        val c = MatrixCursor(COLUMNS)
        val only = seg.getOrNull(1)
        for (p in PackStore.all(ctx)) {
            if (only != null && p.id != only) continue
            c.addRow(arrayOf<Any>(p.id, p.version, p.source, p.json))
        }
        c.setNotificationUri(ctx.contentResolver, PackStore.PACKS_URI)
        return c
    }

    /** The pack and the pack-relative file a URI names, or null. */
    private fun locate(uri: Uri): Pair<PackStore.Pack, String>? {
        val ctx = context ?: return null
        val seg = uri.pathSegments
        if (seg.size < 3) return null
        val pack = PackStore.find(ctx, seg[1]) ?: return null
        val rest = seg.drop(2).joinToString("/")
        val rel = when (seg[0]) {
            "art" -> PackFiles.resolveArt(pack.obj, rest, pack::has)
            "voice" -> PackFiles.resolveVoice(pack.obj, rest, pack::has)
            "file" -> PackFiles.safeRel(rest)?.takeIf(pack::has)
            else -> null
        } ?: return null
        return pack to rel
    }

    override fun getType(uri: Uri): String? {
        if (uri.pathSegments.firstOrNull() == "packs") return "vnd.android.cursor.dir/vnd.com.hione.mascot.pack"
        return locate(uri)?.second?.let(PackFiles::mimeOf)
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        if (mode != "r") throw SecurityException("Read only")
        val ctx = context ?: throw FileNotFoundException(uri.toString())
        val (pack, rel) = locate(uri) ?: throw FileNotFoundException(uri.toString())
        return PackStore.open(ctx, pack, rel)
    }

    /** Plain fd callers (openFileDescriptor): an asset fd would be the whole APK, so assets come through a pipe. */
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw SecurityException("Read only")
        val ctx = context ?: throw FileNotFoundException(uri.toString())
        val (pack, rel) = locate(uri) ?: throw FileNotFoundException(uri.toString())
        return PackStore.openPlain(ctx, pack, rel)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw SecurityException("Read only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw SecurityException("Read only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw SecurityException("Read only")
}
