package com.slackoff.app.network

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object ApiConfig {
    val videoBase = "https://tanlang008-up14load.hf.space"
    val danmuBase = "https://jokkad-danmu-api.hf.space"
    const val danmuToken = "123456"

    const val referer = "https://www.4kvm.org/"
    const val userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    val videoJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    val danmuJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun request(url: String): Request = Request.Builder()
        .url(url)
        .header("Referer", referer)
        .header("User-Agent", userAgent)
        .build()
}