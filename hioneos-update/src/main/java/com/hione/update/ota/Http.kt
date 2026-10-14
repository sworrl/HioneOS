package com.hione.update.ota

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Plain HttpURLConnection. Small surface on purpose: GET a small file, or GET a big one with resume. */
object Http {
    private const val UA = "HioneUpdate/1"
    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 30_000

    private fun open(url: URL): HttpURLConnection {
        require(url.protocol == "https" || url.protocol == "http") { "Unsupported URL $url" }
        return (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Cache-Control", "no-cache")
        }
    }

    /** GET into memory, refusing anything bigger than [maxBytes]. */
    fun getBytes(url: URL, maxBytes: Int): ByteArray {
        val c = open(url)
        try {
            val code = c.responseCode
            if (code != 200) throw IOException("HTTP $code for $url")
            val out = ByteArrayOutputStream()
            c.inputStream.use { input ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > maxBytes) throw IOException("$url is bigger than expected")
                }
            }
            return out.toByteArray()
        } finally {
            c.disconnect()
        }
    }

    /**
     * Downloads [url] into [dest] with resume. Partial data sits in [dest].part; a Range request
     * picks up where it stopped. If the server ignores Range (200 instead of 206) the part file is
     * restarted. Never writes more than [expectedSize] bytes. Returns the SHA-256 of the whole file.
     */
    fun download(
        url: URL,
        dest: File,
        expectedSize: Long,
        onProgress: (done: Long, total: Long) -> Unit,
        isCancelled: () -> Boolean = { false },
    ): String {
        val part = File(dest.path + ".part")
        dest.parentFile?.mkdirs()
        if (part.length() > expectedSize) part.delete()
        var attempt = 0
        while (true) {
            attempt++
            try {
                if (part.length() < expectedSize) fetchRange(url, part, expectedSize, onProgress, isCancelled)
                break
            } catch (e: IOException) {
                // Two quiet retries for a dropped connection; the part file keeps what arrived.
                if (attempt >= 3 || isCancelled()) throw e
                Thread.sleep(2_000L * attempt)
            }
        }
        if (part.length() != expectedSize) throw IOException("Size mismatch: got ${part.length()}, expected $expectedSize")
        val sha = sha256(part)
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) throw IOException("Could not move ${part.name} into place")
        return sha
    }

    private fun fetchRange(
        url: URL,
        part: File,
        expectedSize: Long,
        onProgress: (Long, Long) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        val have = part.length()
        val c = open(url)
        if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
        try {
            val code = c.responseCode
            val append = when {
                code == 206 && have > 0 -> true
                code == 200 -> false
                code == 416 && have == expectedSize -> return
                else -> throw IOException("HTTP $code for $url")
            }
            if (append) {
                // A 206 for some other range would splice the wrong bytes in. The hash would catch
                // it, but only after a wasted download, so check the header up front.
                val cr = c.getHeaderField("Content-Range") ?: ""
                if (!cr.startsWith("bytes $have-")) throw IOException("Server answered the wrong range ($cr)")
            }
            var done = if (append) have else 0L
            FileOutputStream(part, append).use { out ->
                c.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    var lastReport = 0L
                    while (true) {
                        if (isCancelled()) throw IOException("Cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        if (done + n > expectedSize) throw IOException("Server sent more than the manifest size")
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastReport > 256 * 1024 || done == expectedSize) {
                            lastReport = done
                            onProgress(done, expectedSize)
                        }
                    }
                }
                out.fd.sync()
            }
        } finally {
            c.disconnect()
        }
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
