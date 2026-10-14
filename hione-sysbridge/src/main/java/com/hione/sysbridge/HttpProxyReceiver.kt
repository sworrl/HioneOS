package com.hione.sysbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/**
 * HTTPS requests on behalf of the FM tuner, which cannot make them itself.
 *
 * Hione FM has to run in HiBy's vendor_fm_app SELinux domain, because that is the only domain
 * allowed to open /dev/radio0. The same domain is not a net_domain: on 0.1.17 every socket()
 * from it was denied ({ create } udp_socket, { write } dnsproxyd), so terrain profiles, song ID
 * and cover art all failed with "Permission denied (missing INTERNET permission?)" even though
 * INTERNET was granted. This package is system_app, which has the network.
 *
 * Request (action com.hione.sysbridge.HTTP, sender needs com.hione.permission.SYSTEM_BRIDGE):
 *   id, url, method (GET/POST), headers (Bundle of String), body (ByteArray), reply_pkg.
 * Reply (action <reply_pkg>.HTTP_RESULT, sent only to reply_pkg, receiver needs the same
 * permission): id, code (HTTP status, or -1 on failure), body (ByteArray), error (String).
 *
 * Only HTTPS, only the hosts the tuner actually uses, and bodies are capped so a reply always
 * fits in one binder transaction.
 */
class HttpProxyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_HTTP) return
        val id = intent.getStringExtra("id") ?: return
        val replyPkg = intent.getStringExtra("reply_pkg")?.takeIf { it in CALLERS } ?: return
        val app = context.applicationContext
        val pending = goAsync()
        Thread({
            val reply = Intent("$replyPkg.HTTP_RESULT").setPackage(replyPkg).putExtra("id", id)
            try {
                var url = URL(intent.getStringExtra("url") ?: throw IllegalArgumentException("no url"))
                checkAllowed(url)
                var c = url.openConnection() as HttpURLConnection
                try {
                    // Redirects are followed by hand so every hop is held to the same https and
                    // host list. HttpURLConnection on its own follows https redirects to any host.
                    var hops = 0
                    var code = -1
                    while (true) {
                        c.instanceFollowRedirects = false
                        c.requestMethod = intent.getStringExtra("method") ?: "GET"
                        c.connectTimeout = 10_000
                        c.readTimeout = 15_000
                        intent.getBundleExtra("headers")?.let { h ->
                            for (k in h.keySet()) h.getString(k)?.let { c.setRequestProperty(k, it) }
                        }
                        intent.getByteArrayExtra("body")?.let { b ->
                            c.doOutput = true
                            c.outputStream.use { it.write(b) }
                        }
                        code = c.responseCode
                        val loc = c.getHeaderField("Location")
                        if (code !in 300..399 || loc == null || hops >= MAX_REDIRECTS) break
                        url = URL(url, loc)
                        checkAllowed(url)
                        c.disconnect()
                        c = url.openConnection() as HttpURLConnection
                        hops++
                    }
                    val stream = if (code in 200..399) c.inputStream else c.errorStream
                    val body = stream?.use { readCapped(it) } ?: ByteArray(0)
                    if (body.size > MAX_BODY) throw java.io.IOException("response over ${MAX_BODY / 1024} KB")
                    reply.putExtra("code", code).putExtra("body", body)
                } finally {
                    c.disconnect()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "proxy request failed: $t")
                reply.putExtra("code", -1).putExtra("error", "${t.javaClass.simpleName}: ${t.message}")
            } finally {
                app.sendBroadcast(reply, PERMISSION)
                pending.finish()
            }
        }, "HioneSysBridgeHttp").start()
    }

    private fun checkAllowed(url: URL) {
        require(url.protocol == "https") { "https only" }
        require(HOSTS.any { url.host == it || url.host.endsWith(".$it") }) { "host ${url.host} not allowed" }
    }

    private fun readCapped(s: java.io.InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (out.size() <= MAX_BODY) {
            val n = s.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    companion object {
        const val TAG = "HioneSysBridge"
        const val ACTION_HTTP = "com.hione.sysbridge.HTTP"
        const val PERMISSION = "com.hione.permission.SYSTEM_BRIDGE"
        private val CALLERS = setOf("com.caf.fmradio")
        private val HOSTS = listOf("open-meteo.com", "shazam.com", "mzstatic.com", "weather.gov")
        /** Replies travel as a broadcast extra; binder transactions top out at 1 MB. */
        const val MAX_BODY = 600 * 1024
        private const val MAX_REDIRECTS = 3
    }
}
