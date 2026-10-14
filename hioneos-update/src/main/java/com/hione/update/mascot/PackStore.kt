package com.hione.update.mascot

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Where mascot packs live on the device.
 *
 *   built in    assets/mascot/<id>/ inside this APK (copied from hioneos/mascot/packs at build time)
 *   downloaded  filesDir/mascot/<id>/, from a content pack in the signed OTA manifest
 *
 * A downloaded pack of a built-in id wins when its version is higher. Other apps read packs only
 * through [MascotPackProvider]; this directory is private to Hione Update.
 */
object PackStore {
    private const val TAG = "HionePacks"
    const val ASSET_ROOT = "mascot"
    val PACKS_URI: Uri = Uri.parse("content://${MascotPackProvider.AUTHORITY}/packs")

    class Pack(
        val id: String,
        val version: Int,
        /** builtin or download. */
        val source: String,
        val json: String,
        val obj: JSONObject,
        /** assets path for a built-in pack, else null. */
        val assetDir: String?,
        /** directory for a downloaded pack, else null. */
        val dir: File?,
        /** Every file in the pack, relative to its root. */
        val files: Set<String>,
    ) {
        val name: String get() = obj.optString("name", id)
        fun has(rel: String) = rel in files
    }

    @Volatile private var cache: List<Pack>? = null

    fun root(ctx: Context) = File(ctx.filesDir, ASSET_ROOT)

    /** The packs other apps see: one per id, the newest of built-in and downloaded. */
    fun all(ctx: Context): List<Pack> {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val byId = LinkedHashMap<String, Pack>()
            for (p in builtIns(ctx) + downloads(ctx)) {
                val have = byId[p.id]
                if (have == null || p.version > have.version) byId[p.id] = p
            }
            return byId.values.toList().also { cache = it }
        }
    }

    fun find(ctx: Context, id: String): Pack? = all(ctx).firstOrNull { it.id == id }

    fun builtIns(ctx: Context): List<Pack> = runCatching {
        val am = ctx.assets
        (am.list(ASSET_ROOT) ?: emptyArray()).sorted().mapNotNull { id ->
            if (!PackFiles.ID.matches(id)) return@mapNotNull null
            val base = "$ASSET_ROOT/$id"
            runCatching {
                val json = am.open("$base/pack.json").use { it.readBytes().toString(Charsets.UTF_8) }
                val o = JSONObject(json)
                if (o.optString("id") != id) return@runCatching null
                Pack(id, o.optInt("version", 1), "builtin", json, o, base, null, listAssets(ctx, base))
            }.onFailure { Log.w(TAG, "built-in pack $id unreadable: $it") }.getOrNull()
        }
    }.getOrDefault(emptyList())

    private fun listAssets(ctx: Context, base: String): Set<String> {
        val out = HashSet<String>()
        fun walk(rel: String) {
            val path = if (rel.isEmpty()) base else "$base/$rel"
            val kids = ctx.assets.list(path) ?: emptyArray()
            if (kids.isEmpty()) {
                if (rel.isNotEmpty()) out += rel
                return
            }
            for (k in kids) walk(if (rel.isEmpty()) k else "$rel/$k")
        }
        walk("")
        return out
    }

    fun downloads(ctx: Context): List<Pack> {
        val dirs = root(ctx).listFiles { f -> f.isDirectory && PackFiles.ID.matches(f.name) } ?: return emptyList()
        return dirs.sortedBy { it.name }.mapNotNull { d ->
            runCatching {
                val json = File(d, "pack.json").readText()
                val o = JSONObject(json)
                if (o.optString("id") != d.name) return@runCatching null
                Pack(d.name, o.optInt("version", 1), "download", json, o, null, d, PackFiles.listTree(d))
            }.onFailure { Log.w(TAG, "downloaded pack ${d.name} unreadable: $it") }.getOrNull()
        }
    }

    /** Opens [rel] in [pack] for reading. Throws FileNotFoundException when it is not there. */
    fun open(ctx: Context, pack: Pack, rel: String): AssetFileDescriptor {
        val safe = PackFiles.safeRel(rel) ?: throw FileNotFoundException("bad path")
        if (!pack.has(safe)) throw FileNotFoundException("$safe not in ${pack.id}")
        pack.dir?.let { d ->
            val f = File(d, safe)
            val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
            return AssetFileDescriptor(pfd, 0, f.length())
        }
        val path = "${pack.assetDir}/$safe"
        return try {
            ctx.assets.openFd(path) // stored uncompressed (noCompress), so this is a real fd
        } catch (e: IOException) {
            AssetFileDescriptor(pipeAsset(ctx, path), 0, AssetFileDescriptor.UNKNOWN_LENGTH)
        }
    }

    /** Like [open], but a bare fd of the file itself: assets always go through a pipe. */
    fun openPlain(ctx: Context, pack: Pack, rel: String): ParcelFileDescriptor {
        val safe = PackFiles.safeRel(rel) ?: throw FileNotFoundException("bad path")
        if (!pack.has(safe)) throw FileNotFoundException("$safe not in ${pack.id}")
        pack.dir?.let { return ParcelFileDescriptor.open(File(it, safe), ParcelFileDescriptor.MODE_READ_ONLY) }
        return pipeAsset(ctx, "${pack.assetDir}/$safe")
    }

    private fun pipeAsset(ctx: Context, path: String): ParcelFileDescriptor {
        val (r, w) = ParcelFileDescriptor.createPipe()
        Thread {
            runCatching {
                ctx.assets.open(path).use { input ->
                    ParcelFileDescriptor.AutoCloseOutputStream(w).use { out -> input.copyTo(out) }
                }
            }.onFailure { runCatching { w.close() } }
        }.apply { isDaemon = true }.start()
        return r
    }

    /**
     * Installs a downloaded pack zip. Unpacks next to the live copy, checks pack.json carries
     * [id] and [version], then swaps directories, so a pack in use is never half replaced.
     */
    fun install(ctx: Context, zip: File, id: String, version: Int) {
        require(PackFiles.ID.matches(id)) { "Bad pack id" }
        val root = root(ctx).apply { mkdirs() }
        val staging = File(root, ".staging-$id")
        val old = File(root, ".old-$id")
        staging.deleteRecursively()
        old.deleteRecursively()
        try {
            PackFiles.unzip(zip, staging)
            val o = JSONObject(File(staging, "pack.json").readText())
            if (o.optString("id") != id) throw IOException("The pack inside the download is not $id")
            if (o.optInt("version", 1) != version) throw IOException("The pack inside the download is not version $version")
            synchronized(this) {
                val live = File(root, id)
                if (live.exists() && !live.renameTo(old)) throw IOException("Could not move the old pack aside")
                if (!staging.renameTo(live)) {
                    old.renameTo(live)
                    throw IOException("Could not move the new pack into place")
                }
                cache = null
            }
            old.deleteRecursively()
        } finally {
            staging.deleteRecursively()
        }
        changed(ctx)
    }

    /** Removes a downloaded pack. A built-in pack of the same id stays available. */
    fun remove(ctx: Context, id: String): Boolean {
        if (!PackFiles.ID.matches(id)) return false
        val d = File(root(ctx), id)
        if (!d.exists()) return false
        val ok = synchronized(this) {
            val gone = File(root(ctx), ".old-$id")
            gone.deleteRecursively()
            val moved = d.renameTo(gone)
            cache = null
            if (moved) gone.deleteRecursively()
            moved
        }
        changed(ctx)
        return ok
    }

    fun invalidate() { cache = null }

    private fun changed(ctx: Context) {
        cache = null
        runCatching { ctx.contentResolver.notifyChange(PACKS_URI, null) }
    }
}
