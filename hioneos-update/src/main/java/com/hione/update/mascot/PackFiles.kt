package com.hione.update.mascot

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * The file side of a mascot pack, with no Android in it so it can be unit tested: path checks,
 * which pack file answers an art slot or a voice clip, and unpacking a downloaded zip.
 *
 * A pack is a directory (or an assets folder) with pack.json at its root. Paths inside it are
 * relative, forward-slash, and never leave it.
 */
object PackFiles {
    /** Pack ids are short lowercase words: they become directory names and URI segments. */
    val ID = Regex("^[a-z0-9_]{1,32}$")

    /** Caps for a downloaded pack. The Hione pack is about 9 MB unpacked. */
    const val MAX_UNPACKED_BYTES = 96L * 1024 * 1024
    const val MAX_ENTRIES = 4000

    /**
     * [path] cleaned to "a/b/c.webp", or null when it is empty, absolute, has a NUL or a backslash,
     * or any "." or ".." segment. Nothing that comes back can point outside the pack.
     */
    fun safeRel(path: String?): String? {
        if (path.isNullOrEmpty() || path.length > 512) return null
        if (path.indexOf('\u0000') >= 0 || path.indexOf('\\') >= 0) return null
        if (path.startsWith("/")) return null
        val parts = path.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
        return parts.joinToString("/")
    }

    // Density order for a drawable: the biggest that exists wins.
    private val DRAWABLE_DIRS = listOf(
        "res/drawable-nodpi", "res/drawable-xxxhdpi", "res/drawable-xxhdpi", "res/drawable-xhdpi",
        "res/drawable", "res/drawable-hdpi", "res/drawable-mdpi",
    )
    private val IMAGE_EXT = listOf("webp", "png", "jpg", "jpeg")

    /** Files that could stand in for drawable resource [name], best first. */
    fun drawableCandidates(name: String): List<String> {
        if (safeRel(name) == null || name.contains('/')) return emptyList()
        val out = ArrayList<String>()
        for (d in DRAWABLE_DIRS) for (e in IMAGE_EXT) out += "$d/$name.$e"
        out += "res/icons-xxxhdpi/$name.png"
        out += "res/mipmap-xxxhdpi/$name.png"
        for (e in IMAGE_EXT) out += "art/$name.$e"
        return out
    }

    /**
     * The pack file for art [key]: a slot in pack.json "art" ("avatar", "lockscreen",
     * "wallpaper.summer"), or a drawable resource name ("hione_wall_00"). A slot whose value is
     * "drawable:<name>" points at a drawable by name. Null when the pack has nothing for it.
     */
    fun resolveArt(pack: JSONObject, key: String, has: (String) -> Boolean): String? {
        val slot = slotValue(pack.optJSONObject("art"), key)
        if (slot != null) {
            if (slot.startsWith("drawable:")) {
                return drawableCandidates(slot.removePrefix("drawable:")).firstOrNull(has)
            }
            safeRel(slot)?.let { if (has(it)) return it }
        }
        return drawableCandidates(key).firstOrNull(has)
    }

    private fun slotValue(art: JSONObject?, key: String): String? {
        if (art == null) return null
        var node: Any? = art
        for (part in key.split('.')) {
            node = (node as? JSONObject)?.opt(part) ?: return null
        }
        return (node as? String)?.takeIf { it.isNotBlank() }
    }

    /**
     * The pack file for voice clip [name]: a sound category from pack.json "sounds.clips"
     * ("battery_low", "headphones_in"), else a raw resource name in voice/raw ("charging").
     */
    fun resolveVoice(pack: JSONObject, name: String, has: (String) -> Boolean): String? {
        val clip = pack.optJSONObject("sounds")?.optJSONObject("clips")?.optString(name, "")
        if (!clip.isNullOrBlank()) safeRel(clip)?.let { if (has(it)) return it }
        if (name.contains('/')) return null
        return listOf("voice/raw/$name.ogg", "voice/boot/$name.ogg").firstOrNull { safeRel(it) != null && has(it) }
    }

    /** A content type for a pack file, by extension. */
    fun mimeOf(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "webp" -> "image/webp"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "ogg" -> "audio/ogg"
        "json" -> "application/json"
        "mp4" -> "video/mp4"
        else -> "application/octet-stream"
    }

    /**
     * Unpacks [zip] into [dest] (which must not exist yet). Every entry name goes through
     * [safeRel]; anything else fails the whole unpack. Caps the total size and the entry count,
     * counted on the bytes actually written, not on what the zip header claims.
     */
    fun unzip(zip: File, dest: File, maxBytes: Long = MAX_UNPACKED_BYTES, maxEntries: Int = MAX_ENTRIES) {
        if (dest.exists()) throw IOException("${dest.name} already exists")
        if (!dest.mkdirs()) throw IOException("Could not create ${dest.name}")
        val root = dest.canonicalFile
        var total = 0L
        var entries = 0
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val e = zin.nextEntry ?: break
                if (++entries > maxEntries) throw IOException("The pack has too many files")
                val raw = e.name.removeSuffix("/")
                val rel = safeRel(raw) ?: throw IOException("The pack has a file with a bad name: ${e.name.take(80)}")
                val out = File(root, rel).canonicalFile
                if (!out.path.startsWith(root.path + File.separator)) throw IOException("The pack has a file outside its folder")
                if (e.isDirectory) {
                    out.mkdirs()
                    continue
                }
                out.parentFile?.mkdirs()
                out.outputStream().use { o ->
                    while (true) {
                        val n = zin.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) throw IOException("The pack is bigger than allowed")
                        o.write(buf, 0, n)
                    }
                }
            }
        }
    }

    /** Every file under [dir], as pack-relative paths. */
    fun listTree(dir: File): Set<String> {
        val root = dir.canonicalFile
        return root.walkTopDown().filter { it.isFile }
            .map { it.canonicalFile.path.removePrefix(root.path + File.separator).replace(File.separatorChar, '/') }
            .toSet()
    }
}
