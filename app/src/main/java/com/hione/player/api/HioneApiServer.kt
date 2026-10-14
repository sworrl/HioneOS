package com.hione.player.api

import com.hione.player.Mascot
import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The Wi-Fi remote API (default port 8765).
 *
 * Off unless the user turned it on in Settings ([HioneApiSecurity.isApiEnabled]). When on it binds
 * to the Wi-Fi IPv4 address and to loopback, never 0.0.0.0, and follows Wi-Fi as it comes, goes
 * and changes address. No Wi-Fi means no LAN listener.
 *
 * Every route needs the device token ([HioneApiSecurity]) except the page shell and the pairing
 * call. No CORS headers are sent: the only browser client is the page this server serves, which
 * is same-origin. Requests whose Host isn't this server's address, or whose Origin isn't this
 * server, are refused, which also stops DNS rebinding.
 */
object HioneApiServer {
    private const val TAG = "HioneApiServer"
    private const val SERVICE_TYPE = "_hione-remote._tcp."

    private const val MAX_LINE = 8 * 1024
    private const val MAX_HEADERS = 64
    private const val MAX_BODY = 64 * 1024

    /** Paths anyone may GET: the page shell (no data in it) so a new browser can pair. */
    private val PUBLIC_PAGES = setOf("", "/", "/remote", "/connect", "/tv", "/stage", "/favicon.ico")
    private const val PAIR_PATH = "/api/v1/pair"

    data class ServerState(
        val running: Boolean = false,
        val lanAddress: String = "",
        val port: Int = HioneApiSecurity.DEFAULT_PORT,
        val https: Boolean = false,
        val error: String = "",
    ) {
        val baseUrl: String get() = if (lanAddress.isEmpty()) "" else "${if (https) "https" else "http"}://$lanAddress:$port"
    }

    private val _state = MutableStateFlow(ServerState())
    val state: StateFlow<ServerState> = _state

    private val sockets = mutableListOf<ServerSocket>()
    private var acceptPool = Executors.newCachedThreadPool()
    private var workers: ThreadPoolExecutor? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var router: HioneApiRouter? = null
    @Volatile private var allowedHosts: Set<String> = emptySet()
    @Volatile private var started = false
    private var appContext: Context? = null

    @Synchronized
    fun start(context: Context) {
        val app = context.applicationContext
        if (!HioneApiSecurity.isApiEnabled(app)) {
            Log.i(TAG, "remote API is off in Settings")
            return
        }
        if (started) return
        if (HioneApiSecurity.getOrCreateToken(app) == null) {
            _state.value = ServerState(error = "Secure storage unavailable")
            return
        }
        started = true
        appContext = app
        router = HioneApiRouter(app)
        workers = ThreadPoolExecutor(12, 12, 30, TimeUnit.SECONDS, ArrayBlockingQueue(16)).apply { allowCoreThreadTimeOut(true) }
        netCallback = HioneLanBinding.watch(app) { rebindIfChanged() }
        bind(app)
    }

    @Synchronized
    fun stop(context: Context) {
        if (!started) return
        started = false
        HioneLanBinding.unwatch(context, netCallback)
        netCallback = null
        closeSockets()
        unregisterNsdService()
        workers?.shutdownNow()
        workers = null
        _state.value = ServerState()
        Log.i(TAG, "remote API stopped")
    }

    fun isServerRunning(): Boolean = _state.value.running

    @Synchronized
    private fun rebindIfChanged() {
        val app = appContext ?: return
        if (!started) return
        val ip = HioneLanBinding.wifiIpv4(app)?.hostAddress ?: ""
        if (ip != _state.value.lanAddress) {
            Log.i(TAG, "Wi-Fi address changed (${_state.value.lanAddress} -> $ip), rebinding")
            bind(app)
        }
    }

    @Synchronized
    private fun bind(app: Context) {
        closeSockets()
        unregisterNsdService()
        val port = HioneApiSecurity.getApiPort(app)
        val https = HioneApiSecurity.isHttpsEnabled(app)
        val wifi = HioneLanBinding.wifiIpv4(app)
        val addrs = buildList {
            wifi?.let { add(it) }
            add(InetAddress.getByName("127.0.0.1"))
        }
        val opened = mutableListOf<ServerSocket>()
        var err = ""
        for (a in addrs) {
            try {
                val ss = if (https) {
                    HioneTlsContext.getOrCreateSslContext(app).serverSocketFactory.createServerSocket(port, 32, a)
                } else {
                    ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(a, port), 32) }
                }
                opened += ss
            } catch (t: Throwable) {
                err = "Could not open port $port on ${a.hostAddress}: ${t.message}"
                Log.e(TAG, err)
            }
        }
        sockets += opened
        val lan = wifi?.hostAddress ?: ""
        allowedHosts = buildSet {
            if (lan.isNotEmpty()) add("$lan:$port")
            add("127.0.0.1:$port"); add("localhost:$port")
        }
        opened.forEach { ss -> acceptPool.execute { acceptLoop(app, ss) } }
        val lanUp = wifi != null && opened.any { it.inetAddress == wifi }
        _state.value = ServerState(
            running = lanUp,
            lanAddress = if (lanUp) lan else "",
            port = port,
            https = https,
            error = when {
                wifi == null -> "Waiting for Wi-Fi"
                !lanUp -> err.ifEmpty { "Could not open port $port" }
                else -> ""
            },
        )
        if (lanUp) {
            registerNsdService(app, port)
            Log.i(TAG, "remote API on $lan:$port and loopback (https=$https)")
        } else {
            Log.i(TAG, "remote API: no LAN listener (${_state.value.error})")
        }
    }

    private fun closeSockets() {
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
    }

    private fun acceptLoop(context: Context, ss: ServerSocket) {
        while (!ss.isClosed) {
            val socket = try { ss.accept() } catch (_: Throwable) { break }
            val pool = workers
            try {
                if (pool == null) throw RejectedExecutionException()
                pool.execute { handleClientSocket(context, socket) }
            } catch (_: RejectedExecutionException) {
                runCatching { socket.close() }
            }
        }
    }

    // ------------------------------------------------------------------ request handling

    private class BadRequest(val code: Int, msg: String) : Exception(msg)

    private fun handleClientSocket(context: Context, socket: Socket) {
        val ip = socket.inetAddress?.hostAddress ?: "?"
        try {
            socket.soTimeout = 10_000
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            try {
                serve(context, ip, input, output)
            } catch (e: BadRequest) {
                send(output, ApiResponse.error(e.code, e.message ?: "Bad request"))
            }
        } catch (e: Throwable) {
            Log.d(TAG, "client $ip: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun serve(context: Context, ip: String, input: InputStream, output: OutputStream) {
        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 3) throw BadRequest(400, "Bad request line")
        val method = parts[0].uppercase()
        val rawPath = parts[1]

        val headers = mutableMapOf<String, String>()
        var count = 0
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            if (++count > MAX_HEADERS) throw BadRequest(431, "Too many headers")
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }

        // Host must be this server. Blocks DNS rebinding and stray virtual-host traffic.
        val host = headers["host"]?.lowercase() ?: throw BadRequest(400, "Missing Host")
        if (host !in allowedHosts) throw BadRequest(421, "Wrong host")

        if (headers.containsKey("transfer-encoding")) throw BadRequest(411, "Chunked bodies are not accepted")
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength < 0 || contentLength > MAX_BODY) throw BadRequest(413, "Body too large")

        val path = rawPath.substringBefore("?").let { if (it.length > 1) it.trimEnd('/') else it }
        val query = parseQueryParams(rawPath)
        val isPublicPage = (method == "GET" || method == "HEAD") && path in PUBLIC_PAGES

        // No CORS. A cross-origin preflight gets a plain refusal, so the browser blocks the call.
        if (method == "OPTIONS") { send(output, ApiResponse.error(405, "Method not allowed")); return }

        if (!isPublicPage) {
            // Same-origin only. Browsers send Origin on POST and on cross-origin GET.
            val scheme = if (_state.value.https) "https" else "http"
            headers["origin"]?.let { if (it.lowercase() != "$scheme://$host") throw BadRequest(403, "Cross-origin request refused") }
            if (headers["sec-fetch-site"] == "cross-site") throw BadRequest(403, "Cross-site request refused")
        }

        // The page shell stays reachable so a blocked browser can still show what is wrong.
        if (!isPublicPage && HioneApiSecurity.isBlocked(ip)) {
            send(output, ApiResponse.error(429, "Too many failed attempts. Try again in a few minutes."))
            return
        }

        val body = readBody(input, contentLength)

        if (path == PAIR_PATH) {
            if (method != "POST") throw BadRequest(405, "Use POST")
            send(output, pair(context, ip, body))
            return
        }

        if (!isPublicPage) {
            val ok = HioneApiSecurity.checkBearer(context, headers) ||
                HioneApiSecurity.checkSignedUrl(context, method, path, query)
            if (!ok) {
                HioneApiSecurity.recordFailure(ip)
                Log.w(TAG, "401 $method $path from $ip")
                send(output, ApiResponse.error(401, "Not paired. Pair this browser in ${Mascot.musicAppName} Settings."))
                return
            }
        }

        val routeMethod = if (method == "HEAD") "GET" else method
        val r = router ?: HioneApiRouter(context)
        val response = try {
            r.handleRequest(routeMethod, path, query, body)
        } catch (e: Throwable) {
            Log.e(TAG, "error handling $method $path", e)
            ApiResponse.error(500, "Internal error")
        }
        send(output, response, isHead = method == "HEAD", range = headers["range"])
    }

    private fun pair(context: Context, ip: String, body: ByteArray): ApiResponse {
        val code = runCatching { JSONObject(String(body, Charsets.UTF_8)).optString("code") }.getOrDefault("")
        return when (val r = HioneApiSecurity.tryPair(context, code)) {
            is HioneApiSecurity.PairResult.Ok -> {
                Log.i(TAG, "browser at $ip paired")
                ApiResponse.json(200, JSONObject().put("token", r.token))
            }
            HioneApiSecurity.PairResult.BadCode -> {
                HioneApiSecurity.recordFailure(ip)
                ApiResponse.json(401, JSONObject().put("error", "bad-code"))
            }
            HioneApiSecurity.PairResult.Locked -> ApiResponse.json(429, JSONObject().put("error", "locked"))
            HioneApiSecurity.PairResult.Closed -> {
                HioneApiSecurity.recordFailure(ip)
                ApiResponse.json(403, JSONObject().put("error", "closed"))
            }
            HioneApiSecurity.PairResult.Unavailable -> ApiResponse.json(503, JSONObject().put("error", "unavailable"))
        }
    }

    private fun readBody(input: InputStream, len: Int): ByteArray {
        if (len <= 0) return ByteArray(0)
        val buf = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = input.read(buf, read, len - read)
            if (n <= 0) break
            read += n
        }
        return buf
    }

    // ------------------------------------------------------------------ responses

    private fun statusText(code: Int) = when (code) {
        200 -> "OK"; 204 -> "No Content"; 206 -> "Partial Content"
        400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"
        405 -> "Method Not Allowed"; 411 -> "Length Required"; 413 -> "Payload Too Large"
        416 -> "Range Not Satisfiable"; 421 -> "Misdirected Request"; 429 -> "Too Many Requests"
        431 -> "Request Header Fields Too Large"; 500 -> "Internal Server Error"; 503 -> "Service Unavailable"
        else -> "OK"
    }

    private fun send(output: OutputStream, res: ApiResponse, isHead: Boolean = false, range: String? = null) {
        val file = res.file
        var code = res.statusCode
        var start = 0L
        var length = res.body.size.toLong()
        var total = length
        if (file != null) {
            total = file.length()
            length = total
            val m = range?.let { Regex("^bytes=(\\d*)-(\\d*)$").find(it.trim()) }
            if (m != null) {
                val (a, b) = m.destructured
                val s: Long; val e: Long
                if (a.isEmpty()) { // suffix range: last N bytes
                    val n = b.toLongOrNull() ?: 0L
                    s = (total - n).coerceAtLeast(0L); e = total - 1
                } else {
                    s = a.toLong(); e = (b.toLongOrNull() ?: (total - 1)).coerceAtMost(total - 1)
                }
                if (s >= total || s > e) {
                    writeHead(output, 416, res.contentType, 0, mapOf("Content-Range" to "bytes */$total"))
                    output.flush()
                    return
                }
                code = 206; start = s; length = e - s + 1
            }
        }
        val extra = LinkedHashMap(res.extraHeaders)
        if (file != null) {
            extra["Accept-Ranges"] = "bytes"
            if (code == 206) extra["Content-Range"] = "bytes $start-${start + length - 1}/$total"
        }
        writeHead(output, code, res.contentType, length, extra)
        if (!isHead) {
            if (file != null) {
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(start)
                    val buf = ByteArray(64 * 1024)
                    var left = length
                    while (left > 0) {
                        val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        left -= n
                    }
                }
            } else if (res.body.isNotEmpty()) {
                output.write(res.body)
            }
        }
        output.flush()
    }

    private fun writeHead(output: OutputStream, code: Int, contentType: String, length: Long, extra: Map<String, String>) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $code ${statusText(code)}\r\n")
        sb.append("Content-Type: $contentType\r\n")
        sb.append("Content-Length: $length\r\n")
        sb.append("X-Content-Type-Options: nosniff\r\n")
        sb.append("Referrer-Policy: no-referrer\r\n")
        sb.append("X-Frame-Options: DENY\r\n")
        if (!extra.containsKey("Cache-Control")) sb.append("Cache-Control: no-store\r\n")
        if (code == 401) sb.append("WWW-Authenticate: Bearer realm=\"hione\"\r\n")
        if (contentType.startsWith("text/html")) {
            sb.append(
                "Content-Security-Policy: default-src 'self'; script-src 'self' 'unsafe-inline'; " +
                    "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src https://fonts.gstatic.com; " +
                    "img-src 'self' data:; media-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'\r\n"
            )
        }
        extra.forEach { (k, v) -> sb.append("$k: $v\r\n") }
        sb.append("Connection: close\r\n\r\n")
        output.write(sb.toString().toByteArray(Charsets.UTF_8))
    }

    private fun readLine(input: InputStream): String? {
        val baos = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) { if (baos.size() == 0) return null; break }
            if (b == '\n'.code) break
            if (b != '\r'.code) baos.write(b)
            if (baos.size() > MAX_LINE) throw BadRequest(431, "Line too long")
        }
        return baos.toString(Charsets.UTF_8.name())
    }

    private fun parseQueryParams(url: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val q = url.substringAfter('?', "")
        if (q.isEmpty()) return map
        for (p in q.split("&")) {
            if (p.isEmpty()) continue
            val k = runCatching { URLDecoder.decode(p.substringBefore('='), "UTF-8") }.getOrNull() ?: continue
            val v = runCatching { URLDecoder.decode(p.substringAfter('=', ""), "UTF-8") }.getOrNull() ?: continue
            map[k] = v
        }
        return map
    }

    // ------------------------------------------------------------------ mDNS

    private fun registerNsdService(context: Context, port: Int) {
        try {
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "HioneMusic-M500"
                serviceType = SERVICE_TYPE
                setPort(port)
            }
            val l = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) { Log.i(TAG, "mDNS registered ${serviceInfo.serviceName}") }
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { Log.w(TAG, "mDNS registration failed: $errorCode") }
                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            }
            registrationListener = l
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Throwable) {
            Log.w(TAG, "mDNS registration failed", e)
        }
    }

    private fun unregisterNsdService() {
        runCatching { registrationListener?.let { nsdManager?.unregisterService(it) } }
        registrationListener = null
        nsdManager = null
    }
}
