package com.hione.tv

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.*
import java.net.*
import java.nio.ByteBuffer

/**
 * 3rd-Party Streaming Bridge Server for Hione TV.
 *
 * Provides:
 * 1. UPnP / DLNA MediaRenderer discovery (SSDP on multicast 239.255.255.250:1900).
 *    Allows apps like BubbleUPnP, Tidal (via DLNA/BubbleUPnP), VLC, mconnect, Audirvana,
 *    and Spotify (via DLNA bridge/AirMusic) to discover "Hione TV Player" and cast music.
 * 2. HTTP Server on port 8798 for UPnP control (AVTransport / RenderingControl).
 * 3. Direct HTTP Audio Stream Ingest (POST /stream or POST /api/stream) for lossless PCM pipes.
 * 4. REST API (POST /api/play, POST /api/meta, GET /api/status) for direct webhooks & third-party tools.
 * 5. Built-in MediaExtractor/MediaCodec audio decoder to stream and decode remote HTTP URLs.
 */
class HioneTvBridgeServer(
    private val ctx: Context,
    private val client: HioneCastClient
) {
    companion object {
        private const val TAG = "HioneTvBridge"
        const val HTTP_PORT = 8798
        const val SSDP_PORT = 1900
        const val SSDP_MULTICAST = "239.255.255.250"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var httpJob: Job? = null
    private var ssdpJob: Job? = null
    private var streamJob: Job? = null
    private var serverSocket: ServerSocket? = null
    private var ssdpSocket: MulticastSocket? = null

    @Volatile private var currentStreamUri: String? = null
    @Volatile private var currentTitle: String = "External Stream"
    @Volatile private var currentArtist: String = ""
    @Volatile private var currentAlbum: String = ""
    @Volatile private var currentArtUrl: String = ""
    @Volatile private var transportState = "STOPPED"

    fun start() {
        if (httpJob?.isActive == true) return
        httpJob = scope.launch { runHttpServer() }
        ssdpJob = scope.launch { runSsdpResponder() }
    }

    fun stop() {
        httpJob?.cancel(); httpJob = null
        ssdpJob?.cancel(); ssdpJob = null
        stopCurrentStream()
        runCatching { serverSocket?.close() }
        runCatching { ssdpSocket?.close() }
        serverSocket = null
        ssdpSocket = null
    }

    private fun stopCurrentStream() {
        streamJob?.cancel()
        streamJob = null
        transportState = "STOPPED"
        client.onExternalStop()
    }

    // ---- HTTP Server & Endpoints -------------------------------------------

    private suspend fun runHttpServer() {
        try {
            val server = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress("0.0.0.0", HTTP_PORT), 8)
            }
            serverSocket = server
            Log.i(TAG, "Bridge HTTP Server listening on port $HTTP_PORT")

            while (scope.isActive && !server.isClosed) {
                val s = try { server.accept() } catch (t: Throwable) { break }
                scope.launch { handleHttpClient(s) }
            }
        } catch (t: Throwable) {
            if (scope.isActive) Log.w(TAG, "HTTP Server stopped: ${t.message}")
        }
    }

    private fun handleHttpClient(socket: Socket) {
        try {
            socket.soTimeout = 15000
            val input = BufferedReader(InputStreamReader(socket.getInputStream()))
            val rawIn = socket.getInputStream()
            val out = BufferedOutputStream(socket.getOutputStream())

            val reqLine = input.readLine() ?: return
            val parts = reqLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val uri = parts[1]

            val headers = mutableMapOf<String, String>()
            var line: String?
            var contentLength = 0
            while (input.readLine().also { line = it } != null) {
                if (line!!.isEmpty()) break
                val colon = line!!.indexOf(':')
                if (colon > 0) {
                    val k = line!!.substring(0, colon).trim().lowercase()
                    val v = line!!.substring(colon + 1).trim()
                    headers[k] = v
                    if (k == "content-length") contentLength = v.toIntOrNull() ?: 0
                }
            }

            when {
                // UPnP Device Description
                method == "GET" && uri == "/upnp/desc.xml" -> {
                    val xml = getDeviceDescriptionXml(socket.localAddress.hostAddress ?: "127.0.0.1")
                    sendHttpResponse(out, 200, "text/xml; charset=\"utf-8\"", xml)
                }

                // UPnP Service SCPDs
                method == "GET" && uri == "/upnp/scpd/avtransport.xml" -> {
                    sendHttpResponse(out, 200, "text/xml; charset=\"utf-8\"", getAvTransportScpd())
                }
                method == "GET" && uri == "/upnp/scpd/renderingcontrol.xml" -> {
                    sendHttpResponse(out, 200, "text/xml; charset=\"utf-8\"", getRenderingControlScpd())
                }

                // UPnP SOAP Control
                method == "POST" && uri.startsWith("/upnp/control/avtransport") -> {
                    val body = readBody(input, contentLength)
                    val responseXml = handleAvTransportSoap(body)
                    sendHttpResponse(out, 200, "text/xml; charset=\"utf-8\"", responseXml)
                }
                method == "POST" && uri.startsWith("/upnp/control/renderingcontrol") -> {
                    val body = readBody(input, contentLength)
                    val responseXml = handleRenderingControlSoap(body)
                    sendHttpResponse(out, 200, "text/xml; charset=\"utf-8\"", responseXml)
                }

                // Direct PCM Audio Streaming Ingest
                method == "POST" && (uri == "/stream" || uri == "/api/stream") -> {
                    handleDirectStreamIngest(rawIn, headers, out)
                }

                // REST API: /api/play
                method == "POST" && uri == "/api/play" -> {
                    val body = readBody(input, contentLength)
                    handleRestPlay(body, out)
                }

                // REST API: /api/status
                method == "GET" && uri == "/api/status" -> {
                    val status = JSONObject()
                        .put("status", transportState)
                        .put("title", currentTitle)
                        .put("artist", currentArtist)
                        .put("album", currentAlbum)
                        .put("streamUri", currentStreamUri ?: "")
                        .put("cacheMs", client.targetCacheMs)
                        .toString()
                    sendHttpResponse(out, 200, "application/json", status)
                }

                else -> {
                    sendHttpResponse(out, 404, "text/plain", "Not Found")
                }
            }
        } catch (t: Throwable) {
            Log.d(TAG, "HTTP client error: ${t.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun readBody(reader: BufferedReader, length: Int): String {
        if (length <= 0) return ""
        val buf = CharArray(length)
        var read = 0
        while (read < length) {
            val n = reader.read(buf, read, length - read)
            if (n < 0) break
            read += n
        }
        return String(buf, 0, read)
    }

    private fun sendHttpResponse(out: OutputStream, code: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val statusText = if (code == 200) "OK" else if (code == 404) "Not Found" else "Internal Error"
        val header = "HTTP/1.1 $code $statusText\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    // ---- Direct Streaming Ingest (POST /stream) ---------------------------

    private fun handleDirectStreamIngest(inStream: InputStream, headers: Map<String, String>, out: OutputStream) {
        val sampleRate = headers["x-sample-rate"]?.toIntOrNull() ?: 48000
        val bytesPerSample = headers["x-bytes-per-sample"]?.toIntOrNull() ?: 2
        val channels = headers["x-channels"]?.toIntOrNull() ?: 2
        val title = headers["x-title"] ?: "Direct Stream"
        val artist = headers["x-artist"] ?: ""

        currentTitle = title
        currentArtist = artist
        currentAlbum = "Hione Stream Ingest"
        transportState = "PLAYING"

        client.onExternalFormat(sampleRate, bytesPerSample, channels)
        client.onExternalMeta(title, artist, currentAlbum, 0L, true)

        val buf = ByteArray(4096)
        try {
            while (scope.isActive) {
                val n = inStream.read(buf)
                if (n <= 0) break
                val chunk = if (n == buf.size) buf.copyOf() else buf.copyOfRange(0, n)
                client.onExternalPcm(chunk)
            }
        } catch (_: Throwable) {}

        sendHttpResponse(out, 200, "text/plain", "Stream Finished")
    }

    // ---- REST API (POST /api/play) ----------------------------------------

    private fun handleRestPlay(jsonStr: String, out: OutputStream) {
        runCatching {
            val j = JSONObject(jsonStr)
            val url = j.getString("url")
            val title = j.optString("title", "Remote Stream")
            val artist = j.optString("artist", "")
            val album = j.optString("album", "")
            val artUrl = j.optString("artUrl", "")

            startStreamingUri(url, title, artist, album, artUrl)
            sendHttpResponse(out, 200, "application/json", """{"status":"ok","playing":true}""")
        }.onFailure {
            sendHttpResponse(out, 400, "application/json", """{"error":"${it.message}"}""")
        }
    }

    // ---- Stream Decoder Engine --------------------------------------------

    private fun startStreamingUri(
        uri: String,
        title: String,
        artist: String,
        album: String,
        artUrl: String
    ) {
        stopCurrentStream()
        currentStreamUri = uri
        currentTitle = title
        currentArtist = artist
        currentAlbum = album
        currentArtUrl = artUrl
        transportState = "PLAYING"

        // Fetch artwork if present
        if (artUrl.isNotBlank()) {
            scope.launch {
                runCatching {
                    val url = URL(artUrl)
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    conn.inputStream.use { inp ->
                        val bmp = BitmapFactory.decodeStream(inp)
                        if (bmp != null) {
                            client.onExternalMeta(
                                title = currentTitle,
                                artist = currentArtist,
                                album = currentAlbum,
                                durationMs = 0L,
                                isPlaying = true,
                                artBitmap = bmp.asImageBitmap()
                            )
                        }
                    }
                }
            }
        }

        streamJob = scope.launch(Dispatchers.IO) {
            decodeAndPlayStream(uri)
        }
    }

    private suspend fun decodeAndPlayStream(urlStr: String) = withContext(Dispatchers.IO) {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(urlStr)

            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }

            if (trackIndex < 0 || format == null) {
                Log.w(TAG, "No audio track found in $urlStr")
                return@withContext
            }

            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

            client.onExternalFormat(sampleRate, 2, channelCount)
            client.onExternalMeta(
                title = currentTitle,
                artist = currentArtist,
                album = currentAlbum,
                durationMs = durationUs / 1000L,
                isPlaying = true
            )

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var isEos = false

            while (isActive && !isEos) {
                val inIndex = codec.dequeueInputBuffer(10000)
                if (inIndex >= 0) {
                    val buf = codec.getInputBuffer(inIndex)
                    if (buf != null) {
                        val sampleSize = extractor.readSampleData(buf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isEos = true
                        } else {
                            val pts = extractor.sampleTime
                            codec.queueInputBuffer(inIndex, 0, sampleSize, pts, 0)
                            extractor.advance()
                        }
                    }
                }

                var outIndex = codec.dequeueOutputBuffer(info, 10000)
                while (outIndex >= 0) {
                    val outBuf = codec.getOutputBuffer(outIndex)
                    if (outBuf != null && info.size > 0) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        val pcm = ByteArray(info.size)
                        outBuf.get(pcm)
                        client.onExternalPcm(pcm)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    outIndex = codec.dequeueOutputBuffer(info, 0)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Stream playback interrupted: ${t.message}")
        } finally {
            runCatching { codec?.stop(); codec?.release() }
            runCatching { extractor?.release() }
            transportState = "STOPPED"
        }
    }

    // ---- UPnP / DLNA SOAP Handling -----------------------------------------

    private fun handleAvTransportSoap(body: String): String {
        if (body.contains("SetAVTransportURI")) {
            val uri = extractXmlTag(body, "CurrentURI")
            val metaXml = extractXmlTag(body, "CurrentURIMetaData")
            val title = extractXmlTag(metaXml, "dc:title").ifBlank { "DLNA Stream" }
            val artist = extractXmlTag(metaXml, "upnp:artist")
            val album = extractXmlTag(metaXml, "upnp:album")
            val artUrl = extractXmlTag(metaXml, "upnp:albumArtURI")

            currentStreamUri = uri
            currentTitle = title
            currentArtist = artist
            currentAlbum = album
            currentArtUrl = artUrl

            return wrapSoapResponse("SetAVTransportURIResponse", "")
        } else if (body.contains("Play")) {
            currentStreamUri?.let { uri ->
                startStreamingUri(uri, currentTitle, currentArtist, currentAlbum, currentArtUrl)
            }
            return wrapSoapResponse("PlayResponse", "")
        } else if (body.contains("Pause")) {
            transportState = "PAUSED_PLAYBACK"
            client.send("pause")
            return wrapSoapResponse("PauseResponse", "")
        } else if (body.contains("Stop")) {
            stopCurrentStream()
            return wrapSoapResponse("StopResponse", "")
        } else if (body.contains("GetTransportInfo")) {
            val content = "<CurrentTransportState>$transportState</CurrentTransportState>" +
                    "<CurrentTransportStatus>OK</CurrentTransportStatus>" +
                    "<CurrentSpeed>1</CurrentSpeed>"
            return wrapSoapResponse("GetTransportInfoResponse", content)
        } else if (body.contains("GetPositionInfo")) {
            val pos = formatDuration(client.state.value.positionMs)
            val dur = formatDuration(client.state.value.durationMs)
            val content = "<Track>1</Track>" +
                    "<TrackDuration>$dur</TrackDuration>" +
                    "<TrackMetaData></TrackMetaData>" +
                    "<TrackURI>${currentStreamUri ?: ""}</TrackURI>" +
                    "<RelTime>$pos</RelTime>" +
                    "<AbsTime>$pos</AbsTime>" +
                    "<RelCount>0</RelCount>" +
                    "<AbsCount>0</AbsCount>"
            return wrapSoapResponse("GetPositionInfoResponse", content)
        }
        return wrapSoapResponse("ActionResponse", "")
    }

    private fun handleRenderingControlSoap(body: String): String {
        if (body.contains("GetVolume")) {
            return wrapSoapResponse("GetVolumeResponse", "<CurrentVolume>100</CurrentVolume>")
        } else if (body.contains("SetVolume")) {
            return wrapSoapResponse("SetVolumeResponse", "")
        }
        return wrapSoapResponse("RenderingControlResponse", "")
    }

    private fun wrapSoapResponse(action: String, body: String): String {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\n" +
                "  <s:Body>\n" +
                "    <u:$action xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">\n" +
                "      $body\n" +
                "    </u:$action>\n" +
                "  </s:Body>\n" +
                "</s:Envelope>"
    }

    private fun extractXmlTag(xml: String, tag: String): String {
        val startTag = "<$tag"
        val startIdx = xml.indexOf(startTag)
        if (startIdx < 0) return ""
        val closeBracket = xml.indexOf('>', startIdx)
        if (closeBracket < 0) return ""
        val endTag = "</$tag>"
        val endIdx = xml.indexOf(endTag, closeBracket)
        if (endIdx < 0) return ""
        return xml.substring(closeBracket + 1, endIdx).trim()
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
    }

    private fun formatDuration(ms: Long): String {
        val s = (ms / 1000) % 60
        val m = (ms / 60000) % 60
        val h = ms / 3600000
        return "%02d:%02d:%02d".format(h, m, s)
    }

    // ---- SSDP Discovery Responder -----------------------------------------

    private suspend fun runSsdpResponder() = withContext(Dispatchers.IO) {
        val group = InetAddress.getByName(SSDP_MULTICAST)
        val socket = MulticastSocket(SSDP_PORT).apply {
            reuseAddress = true
            joinGroup(group)
        }
        ssdpSocket = socket
        Log.i(TAG, "SSDP Discovery Listener joined multicast group $SSDP_MULTICAST:$SSDP_PORT")

        val buf = ByteArray(2048)
        while (isActive && !socket.isClosed) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                socket.receive(packet)
                val msg = String(packet.data, 0, packet.length, Charsets.US_ASCII)

                if (msg.startsWith("M-SEARCH", ignoreCase = true)) {
                    val localIp = getLocalIpAddress()
                    val st = extractHeader(msg, "ST")
                    if (st.contains("MediaRenderer") || st.contains("AVTransport") || st == "ssdp:all" || st == "upnp:rootdevice") {
                        val response = "HTTP/1.1 200 OK\r\n" +
                                "CACHE-CONTROL: max-age=1800\r\n" +
                                "EXT:\r\n" +
                                "LOCATION: http://$localIp:$HTTP_PORT/upnp/desc.xml\r\n" +
                                "SERVER: Android/34 UPnP/1.0 HioneTV/1.0\r\n" +
                                "ST: $st\r\n" +
                                "USN: uuid:hione-tv-bridge-01::$st\r\n\r\n"
                        val resBytes = response.toByteArray(Charsets.US_ASCII)
                        val reply = DatagramPacket(resBytes, resBytes.size, packet.address, packet.port)
                        socket.send(reply)
                    }
                }
            } catch (t: Throwable) {
                if (!isActive) break
            }
        }
    }

    private fun extractHeader(msg: String, header: String): String {
        for (line in msg.lines()) {
            if (line.startsWith("$header:", ignoreCase = true)) {
                return line.substring(header.length + 1).trim()
            }
        }
        return ""
    }

    private fun getLocalIpAddress(): String {
        return runCatching {
            val en = NetworkInterface.getNetworkInterfaces()
            while (en != null && en.hasMoreElements()) {
                val intf = en.nextElement()
                if (intf.isLoopback || !intf.isUp) continue
                val addrs = intf.inetAddresses
                while (addrs != null && addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: ""
                    }
                }
            }
            "10.3.64.222"
        }.getOrDefault("10.3.64.222")
    }

    // ---- UPnP Device and Service XMLs --------------------------------------

    private fun getDeviceDescriptionXml(ip: String): String {
        return "<?xml version=\"1.0\"?>\n" +
                "<root xmlns=\"urn:schemas-upnp-org:device-1-0\">\n" +
                "  <specVersion><major>1</major><minor>0</minor></specVersion>\n" +
                "  <device>\n" +
                "    <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>\n" +
                "    <friendlyName>Hione TV Player</friendlyName>\n" +
                "    <manufacturer>FalconTechnix</manufacturer>\n" +
                "    <modelName>Hione Hits TV Bridge</modelName>\n" +
                "    <modelNumber>1.0</modelNumber>\n" +
                "    <UDN>uuid:hione-tv-bridge-01</UDN>\n" +
                "    <serviceList>\n" +
                "      <service>\n" +
                "        <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>\n" +
                "        <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>\n" +
                "        <SCPDURL>/upnp/scpd/avtransport.xml</SCPDURL>\n" +
                "        <controlURL>/upnp/control/avtransport</controlURL>\n" +
                "        <eventSubURL>/upnp/event/avtransport</eventSubURL>\n" +
                "      </service>\n" +
                "      <service>\n" +
                "        <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>\n" +
                "        <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>\n" +
                "        <SCPDURL>/upnp/scpd/renderingcontrol.xml</SCPDURL>\n" +
                "        <controlURL>/upnp/control/renderingcontrol</controlURL>\n" +
                "        <eventSubURL>/upnp/event/renderingcontrol</eventSubURL>\n" +
                "      </service>\n" +
                "    </serviceList>\n" +
                "  </device>\n" +
                "</root>"
    }

    private fun getAvTransportScpd(): String {
        return "<?xml version=\"1.0\"?>\n" +
                "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">\n" +
                "  <specVersion><major>1</major><minor>0</minor></specVersion>\n" +
                "  <actionList>\n" +
                "    <action><name>SetAVTransportURI</name></action>\n" +
                "    <action><name>Play</name></action>\n" +
                "    <action><name>Pause</name></action>\n" +
                "    <action><name>Stop</name></action>\n" +
                "    <action><name>GetTransportInfo</name></action>\n" +
                "    <action><name>GetPositionInfo</name></action>\n" +
                "  </actionList>\n" +
                "</scpd>"
    }

    private fun getRenderingControlScpd(): String {
        return "<?xml version=\"1.0\"?>\n" +
                "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">\n" +
                "  <specVersion><major>1</major><minor>0</minor></specVersion>\n" +
                "  <actionList>\n" +
                "    <action><name>GetVolume</name></action>\n" +
                "    <action><name>SetVolume</name></action>\n" +
                "  </actionList>\n" +
                "</scpd>"
    }
}
