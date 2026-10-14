package com.caf.fmradio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The tuner's only way onto the network.
 *
 * This app runs in HiBy's vendor_fm_app SELinux domain (the one domain allowed to open
 * /dev/radio0), and that domain may not create sockets or reach the DNS resolver: measured on
 * 0.1.17, every request failed with "Permission denied (missing INTERNET permission?)" while
 * INTERNET was granted. So requests go to com.hione.sysbridge (system_app, which has the network)
 * as a signature-protected broadcast, and the answer comes back the same way. Blocking; call
 * off the main thread. Falls back to a direct connection when the bridge is not installed,
 * which is the case on anything that is not a HioneOS image.
 */
object FmNet {

    class Response(val code: Int, val body: ByteArray) {
        val text: String get() = String(body, Charsets.UTF_8)
    }

    private const val BRIDGE = "com.hione.sysbridge"
    private const val ACTION = "com.hione.sysbridge.HTTP"
    private const val PERMISSION = "com.hione.permission.SYSTEM_BRIDGE"

    private class Pending { val latch = CountDownLatch(1); var reply: Intent? = null }
    private val pending = ConcurrentHashMap<String, Pending>()
    @Volatile private var registered = false
    @Volatile private var appContext: Context? = null

    /** Called once when the engine starts; the call sites have no Context of their own. */
    fun init(ctx: Context) { appContext = ctx.applicationContext }

    private fun ensureReceiver(app: Context) {
        if (registered) return
        synchronized(this) {
            if (registered) return
            val rx = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    val id = i?.getStringExtra("id") ?: return
                    pending[id]?.let { it.reply = i; it.latch.countDown() }
                }
            }
            val f = IntentFilter("${app.packageName}.HTTP_RESULT")
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(rx, f, PERMISSION, null, Context.RECEIVER_EXPORTED)
            else app.registerReceiver(rx, f, PERMISSION, null)
            registered = true
        }
    }

    private fun bridgePresent(ctx: Context): Boolean = runCatching {
        ctx.packageManager.getPackageInfo(BRIDGE, 0); true
    }.getOrDefault(false)

    fun request(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        timeoutMs: Long = 25_000,
    ): Response {
        val app = appContext
        if (app == null || !bridgePresent(app)) return direct(url, method, headers, body)
        ensureReceiver(app)
        val id = UUID.randomUUID().toString()
        val p = Pending()
        pending[id] = p
        try {
            val i = Intent(ACTION).setPackage(BRIDGE)
                .putExtra("id", id).putExtra("url", url).putExtra("method", method)
                .putExtra("reply_pkg", app.packageName)
                .putExtra("headers", Bundle().apply { headers.forEach { (k, v) -> putString(k, v) } })
            if (body != null) i.putExtra("body", body)
            app.sendBroadcast(i)
            if (!p.latch.await(timeoutMs, TimeUnit.MILLISECONDS)) throw IOException("no answer from the system bridge")
            val r = p.reply ?: throw IOException("empty reply")
            val code = r.getIntExtra("code", -1)
            if (code < 0) throw IOException(r.getStringExtra("error") ?: "request failed")
            return Response(code, r.getByteArrayExtra("body") ?: ByteArray(0))
        } finally {
            pending.remove(id)
        }
    }

    private fun direct(url: String, method: String, headers: Map<String, String>, body: ByteArray?): Response {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body) } }
            val code = c.responseCode
            val bytes = (if (code in 200..399) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
            return Response(code, bytes)
        } finally {
            c.disconnect()
        }
    }
}
