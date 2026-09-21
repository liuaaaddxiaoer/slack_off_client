package com.slackoff.app.playback

import java.io.*
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class HlsLocalServer {
    companion object {
        @Volatile
        private var _instance: HlsLocalServer? = null

        @JvmStatic
        fun getInstance(): HlsLocalServer {
            if (_instance == null) {
                synchronized(HlsLocalServer::class) {
                    if (_instance == null) {
                        _instance = HlsLocalServer()
                    }
                }
            }
            return _instance!!
        }

        fun relativePath(token: String, id: Int, fileExtension: String): String =
            "/hls/$token/$id.$fileExtension"

        fun mount(
            origin: String,
            headers: Map<String, String> = emptyMap(),
            segmentExtension: String = "ts",
            playlist: ByteArray? = null
        ): Pair<String, HlsMediaSource> {
            val server = getInstance()
            server.start()
            val source = HlsMediaSource(
                origin = origin,
                headers = headers,
                segmentExtension = segmentExtension,
                playlist = playlist
            )
            server.register(source)
            val path = relativePath(source.token, 0, source.fileExtension(0))
            return Pair("${server.baseUrl}$path", source)
        }
    }

    private val sources = ConcurrentHashMap<String, HlsMediaSource>()
    private var serverSocket: ServerSocket? = null
    private var port: Int = 0
    private val executor = Executors.newCachedThreadPool()

    val baseUrl: String get() = "http://127.0.0.1:$port"

    @Synchronized
    fun start() {
        if (serverSocket != null) return
        try {
            serverSocket = ServerSocket(0, 32, java.net.InetAddress.getByName("127.0.0.1"))
            port = serverSocket!!.localPort
            executor.submit { acceptLoop() }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized
    fun register(source: HlsMediaSource) {
        // Unregister old sources, keep only the new one
        val toRemove = sources.keys.filter { it != source.token }
        for (token in toRemove) {
            sources.remove(token)?.cleanup()
        }
        sources[source.token] = source
    }

    fun unregister(source: HlsMediaSource) {
        sources.remove(source.token)
        source.cleanup()
    }

    private fun acceptLoop() {
        while (true) {
            try {
                val client = serverSocket?.accept() ?: break
                executor.submit { handle(client) }
            } catch (e: Exception) {
                break
            }
        }
    }

    private fun handle(client: java.net.Socket) {
        try {
            client.use { socket ->
                val input = BufferedReader(InputStreamReader(socket.getInputStream()))
                val output = socket.getOutputStream()

                val requestLine = input.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val path = parts[1]

                // Read headers
                val headers = mutableMapOf<String, String>()
                var line: String?
                while (input.readLine().also { line = it } != null) {
                    if (line!!.isEmpty()) break
                    val colonIdx = line!!.indexOf(":")
                    if (colonIdx > 0) {
                        val name = line!!.substring(0, colonIdx).trim().lowercase()
                        val value = line!!.substring(colonIdx + 1).trim()
                        headers[name] = value
                    }
                }

                val route = parseRoute(path)
                if (route == null) {
                    writeResponse(output, "404 Not Found", "text/plain", ByteArray(0), method)
                    return
                }

                val (token, id) = route
                val source = sources[token]
                if (source == null) {
                    writeResponse(output, "404 Not Found", "text/plain", ByteArray(0), method)
                    return
                }

                val body = source.body(id)
                if (body == null) {
                    writeResponse(output, "502 Bad Gateway", "text/plain", ByteArray(0), method)
                    return
                }

                val contentType = contentType(source.fileExtension(id))
                writeResponse(output, "200 OK", contentType, body, method, headers["range"])
            }
        } catch (e: Exception) {
            // connection closed
        }
    }

    private fun parseRoute(path: String): Pair<String, Int>? {
        val cleanPath = path.substringBefore("?")
        val components = cleanPath.split("/").filter { it.isNotEmpty() }
        if (components.size != 3 || components[0] != "hls") return null
        val name = components[2]
        val dotIdx = name.lastIndexOf(".")
        if (dotIdx < 0) return null
        val id = name.substring(0, dotIdx).toIntOrNull() ?: return null
        return Pair(components[1], id)
    }

    private fun contentType(ext: String): String = when (ext) {
        "m3u8" -> "application/vnd.apple.mpegurl"
        "ts" -> "video/mp2t"
        "mp4", "m4s" -> "video/mp4"
        else -> "application/octet-stream"
    }

    private fun writeResponse(
        output: OutputStream,
        status: String,
        contentType: String,
        body: ByteArray,
        method: String,
        rangeHeader: String? = null
    ) {
        var statusLine = status
        var payload = body
        var contentRange: String? = null

        if (rangeHeader != null && rangeHeader.startsWith("bytes=") && body.isNotEmpty()) {
            val range = parseRange(rangeHeader, body.size)
            if (range != null && range.first < body.size) {
                statusLine = "206 Partial Content"
                payload = body.copyOfRange(range.first, range.last)
                contentRange = "Content-Range: bytes ${range.first}-${range.last - 1}/${body.size}"
            }
        }

        val sb = StringBuilder()
        sb.append("HTTP/1.1 $statusLine\r\n")
        sb.append("Content-Type: $contentType\r\n")
        sb.append("Content-Length: ${payload.size}\r\n")
        sb.append("Accept-Ranges: bytes\r\n")
        if (contentRange != null) sb.append("$contentRange\r\n")
        sb.append("Connection: close\r\n\r\n")

        output.write(sb.toString().toByteArray(Charsets.UTF_8))
        if (method != "HEAD") {
            output.write(payload)
        }
        output.flush()
    }

    private fun parseRange(header: String, length: Int): IntRange? {
        if (length <= 0) return null
        val value = header.removePrefix("bytes=")
        val parts = value.split("-")
        if (parts.size != 2) return null

        return if (parts[0].isEmpty()) {
            val suffix = parts[1].toIntOrNull() ?: return null
            if (suffix <= 0) return null
            maxOf(length - suffix, 0) until length
        } else {
            val start = parts[0].toIntOrNull() ?: return null
            if (start >= length) return null
            val end = if (parts[1].isEmpty()) length else minOf(parts[1].toIntOrNull() ?: length, length)
            if (end <= start) return null
            start until end
        }
    }
}