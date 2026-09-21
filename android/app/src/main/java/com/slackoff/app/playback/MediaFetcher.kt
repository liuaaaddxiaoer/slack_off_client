package com.slackoff.app.playback

import com.slackoff.app.network.ApiConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object MediaFetcher {
    private val session: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .cache(null)
        .build()

    fun fetch(
        url: String,
        extraHeaders: Map<String, String> = emptyMap(),
        range: IntRange? = null
    ): ByteArray? {
        val candidates = headerCandidates(url, extraHeaders)
        var lastError: Exception? = null

        for (headers in candidates) {
            try {
                val requestBuilder = Request.Builder().url(url)
                for ((key, value) in headers) {
                    requestBuilder.header(key, value)
                }
                if (range != null) {
                    requestBuilder.header("Range", "bytes=${range.first}-${range.last}")
                }
                val response = session.newCall(requestBuilder.build()).execute()
                if (response.isSuccessful) {
                    val body = response.body?.bytes()
                    if (body != null && body.isNotEmpty() && !looksLikeErrorPage(body)) {
                        return body
                    }
                }
                lastError = Exception("HTTP ${response.code}")
            } catch (e: Exception) {
                lastError = e
            }
        }
        return null
    }

    private fun headerCandidates(url: String, extraHeaders: Map<String, String>): List<Map<String, String>> {
        val userAgent = mapOf("User-Agent" to ApiConfig.userAgent)
        val candidates = mutableListOf<Map<String, String>>()

        val uri = java.net.URI(url)
        val scheme = uri.scheme
        val host = uri.host
        if (scheme != null && host != null) {
            candidates.add(userAgent + mapOf("Referer" to "$scheme://$host/"))
        }
        candidates.add(userAgent)
        if (extraHeaders.isNotEmpty()) {
            candidates.add(extraHeaders + userAgent)
        }
        return candidates.distinct()
    }

    private fun looksLikeErrorPage(data: ByteArray): Boolean {
        if (data.size < 64) return false
        val head = String(data, 0, minOf(64, data.size), Charsets.UTF_8).lowercase()
        return head.startsWith("<!doctype") || head.startsWith("<html")
    }
}