package com.slackoff.app.playback

import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class HlsMediaSource(
    val origin: String,
    val headers: Map<String, String> = emptyMap(),
    val segmentExtension: String = "ts",
    playlist: ByteArray? = null
) {
    companion object {
        const val PLAYLIST_EXTENSION = "m3u8"
        private const val MAX_CACHED_FILES = 160
        private const val PRUNE_STRIDE = 16

        fun fileExtension(url: String, fallback: String): String {
            val path = url.substringBefore("?").substringBefore("#")
            return when (path.substringAfterLast(".").lowercase()) {
                "m3u8", "m3u" -> PLAYLIST_EXTENSION
                "mp4", "m4v", "mov" -> "mp4"
                "m4s" -> "m4s"
                "ts" -> "ts"
                else -> fallback
            }
        }
    }

    val token: String = UUID.randomUUID().toString().lowercase()
    private val directory: File

    private val remoteUrls = ConcurrentHashMap<Int, String>()
    private val idsByUrl = ConcurrentHashMap<String, Int>()
    private val extensions = ConcurrentHashMap<Int, String>()
    private val order = mutableListOf<Int>()
    private var nextId = 1
    private var storesSincePrune = 0
    private var recentId: Int? = null
    private var recentBody: ByteArray? = null

    init {
        directory = File(System.getProperty("java.io.tmpdir"), "hls-cache/$token")
        directory.mkdirs()

        remoteUrls[0] = origin
        idsByUrl[origin] = 0
        extensions[0] = fileExtension(origin, PLAYLIST_EXTENSION)

        if (playlist != null) {
            val text = String(playlist, Charsets.UTF_8)
            if (text.contains("#EXT-X-ENDLIST")) {
                val body = rewritePlaylist(text, origin)
                File(directory, "0.$PLAYLIST_EXTENSION").writeBytes(body)
                synchronized(order) { order.add(0) }
                remember(0, body)
            }
        }
    }

    fun cleanup() {
        directory.deleteRecursively()
    }

    fun fileExtension(id: Int): String = extensions[id] ?: segmentExtension

    fun body(id: Int): ByteArray? {
        synchronized(this) {
            cachedBody(id)?.let { return it }
        }

        val remote = remoteUrls[id] ?: origin
        return try {
            val raw = MediaFetcher.fetch(remote, headers) ?: return null
            synchronized(this) { prepare(raw, id) }
        } catch (e: Exception) {
            null
        }
    }

    private fun cachedBody(id: Int): ByteArray? {
        if (recentId == id && recentBody != null) return recentBody
        val ext = extensions[id] ?: return null
        val file = File(directory, "$id.$ext")
        if (!file.exists()) return null
        val data = file.readBytes()
        remember(id, data)
        return data
    }

    private fun prepare(raw: ByteArray, id: Int): ByteArray {
        val base = remoteUrls[id] ?: origin
        val kind = TsSegmentRepair.kindOf(raw)

        return when (kind) {
            SegmentKind.PLAYLIST -> {
                val text = String(raw, Charsets.UTF_8)
                val body = rewritePlaylist(text, base)
                if (text.contains("#EXT-X-ENDLIST")) {
                    store(id, body, PLAYLIST_EXTENSION)
                }
                body
            }
            SegmentKind.TRANSPORT_STREAM, SegmentKind.WRAPPED_TRANSPORT_STREAM -> {
                val ts = TsSegmentRepair.repairedSegment(raw) ?: raw
                store(id, ts, "ts")
                ts
            }
            SegmentKind.FRAGMENTED_MP4 -> {
                store(id, raw, "mp4")
                raw
            }
            else -> raw
        }
    }

    private fun rewritePlaylist(text: String, base: String): ByteArray {
        val lines = mutableListOf<String>()
        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#")) {
                lines.add(rewriteTag(line, base))
            } else {
                val url = M3u8Parser.resolve(line, base) ?: continue
                lines.add(localPath(register(url)))
            }
        }
        return (lines.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8)
    }

    private fun rewriteTag(line: String, base: String): String {
        if (!line.contains("URI=\"")) return line
        val sb = StringBuilder()
        var cursor = 0
        while (true) {
            val keyIdx = line.indexOf("URI=\"", cursor)
            if (keyIdx < 0) {
                sb.append(line.substring(cursor))
                break
            }
            sb.append(line.substring(cursor, keyIdx))
            val valueStart = keyIdx + 5
            val valueEnd = line.indexOf("\"", valueStart)
            if (valueEnd < 0) {
                sb.append(line.substring(valueStart))
                cursor = line.length
                break
            }
            val value = line.substring(valueStart, valueEnd)
            val url = M3u8Parser.resolve(value, base)
            if (url != null) {
                sb.append("URI=\"${localPath(register(url))}\"")
            } else {
                sb.append("URI=\"$value\"")
            }
            cursor = valueEnd + 1
        }
        return sb.toString()
    }

    private fun localPath(id: Int): String =
        HlsLocalServer.relativePath(token, id, extensions[id] ?: segmentExtension)

    private fun register(url: String): Int {
        idsByUrl[url]?.let { return it }
        val id = nextId++
        remoteUrls[id] = url
        idsByUrl[url] = id
        extensions[id] = fileExtension(url, segmentExtension)
        return id
    }

    private fun store(id: Int, body: ByteArray, fileExtension: String) {
        extensions[id] = fileExtension
        val file = File(directory, "$id.$fileExtension")
        try {
            file.writeBytes(body)
        } catch (_: Exception) {
            remember(id, body)
            return
        }
        synchronized(order) { order.add(id) }
        remember(id, body)

        storesSincePrune++
        if (storesSincePrune >= PRUNE_STRIDE) {
            storesSincePrune = 0
            prune()
        }
    }

    private fun remember(id: Int, body: ByteArray) {
        recentId = id
        recentBody = body
    }

    private fun prune() {
        synchronized(order) {
            while (order.size > MAX_CACHED_FILES) {
                val id = order.removeAt(0)
                val ext = extensions[id] ?: continue
                if (recentId == id) {
                    recentId = null
                    recentBody = null
                }
                File(directory, "$id.$ext").delete()
            }
        }
    }

    // fileExtension is now in the companion object above
}