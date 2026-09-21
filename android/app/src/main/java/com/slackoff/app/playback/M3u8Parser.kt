package com.slackoff.app.playback

data class M3u8Segment(
    val id: String = java.util.UUID.randomUUID().toString(),
    val url: String,
    val duration: Double
)

object M3u8Parser {
    fun parse(text: String, baseUrl: String): List<M3u8Segment> {
        val segments = mutableListOf<M3u8Segment>()
        var pendingDuration = 0.0

        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            if (line.startsWith("#EXTINF:")) {
                val value = line.removePrefix("#EXTINF:").trim()
                pendingDuration = value.split(",").firstOrNull()?.toDoubleOrNull() ?: 0.0
            } else if (line.startsWith("#")) {
                continue
            } else {
                val url = resolve(line, baseUrl) ?: continue
                segments.add(M3u8Segment(url = url, duration = maxOf(pendingDuration, 1.0)))
                pendingDuration = 0.0
            }
        }
        return segments
    }

    fun resolve(value: String, baseUrl: String): String? {
        if (value.startsWith("/ets/")) {
            val decoded = decodeETSTail(value)
            if (decoded != null && decoded.startsWith("http")) return decoded
        }
        return if (value.startsWith("http://") || value.startsWith("https://")) {
            value
        } else {
            val base = baseUrl.substringBeforeLast("/")
            "$base/$value"
        }
    }

    private fun decodeETSTail(value: String): String? {
        val tail = value.split("/").lastOrNull() ?: return null
        var encoded = tail
        while (encoded.length % 4 != 0) encoded += "="
        return try {
            val decoded = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
            String(decoded, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}