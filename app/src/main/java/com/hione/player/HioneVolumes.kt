package com.hione.player

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File

/**
 * Live storage-volume resolution — the M500's storage UI must reflect what is actually
 * inserted/mounted, never a hardcoded per-card UUID like /storage/EAFF-98FE (each card
 * has its own FAT volume id, and an ejected/unmountable card must show as absent).
 */
object HioneVolumes {

    /** Mount root of the removable MicroSD (e.g. /storage/1234-ABCD), or null when no
     *  card is inserted/mounted. */
    fun removableRoot(ctx: Context): File? {
        try {
            for (d in ctx.getExternalFilesDirs(null)) {
                if (d != null && Environment.isExternalStorageRemovable(d)) {
                    val root = File(d.absolutePath.substringBefore("/Android"))
                    if (root.exists()) return root
                }
            }
        } catch (_: Throwable) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as? android.os.storage.StorageManager
                val vol = sm?.storageVolumes?.firstOrNull { it.isRemovable && !it.isPrimary }
                val dir = vol?.directory
                if (dir != null && dir.exists()) return dir
            } catch (_: Throwable) {}
        }
        return null
    }

    /** Human label for the card slot: resolved mount path when mounted, else null. */
    fun removableLabel(ctx: Context): String? = removableRoot(ctx)?.absolutePath
}
