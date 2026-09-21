package com.slackoff.app.network

import com.slackoff.app.model.NovelBook
import com.slackoff.app.model.NovelBookDetail
import com.slackoff.app.model.NovelBookPage
import com.slackoff.app.model.NovelCacheStats
import com.slackoff.app.model.NovelCategory
import com.slackoff.app.model.NovelChapterBody
import com.slackoff.app.model.NovelChapterList
import com.slackoff.app.model.NovelHomePage
import com.slackoff.app.model.NovelPing
import com.slackoff.app.model.NovelRankBoard
import com.slackoff.app.model.NovelSourceInfo
import com.slackoff.app.model.parseNovelErrorDetail
import com.slackoff.app.support.NovelSettingsStore
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 小说服务专用配置：60s 读超时（首次回源要实时抓源站 HTML，比视频接口慢得多），
 * 且不带视频那套 Referer —— `https://www.4kvm.org/` 对本地小说服务没有意义。
 */
object NovelApiConfig {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
}

/**
 * 小说聚合 API 端点封装（与 iOS 端 NovelService.swift 对齐）。
 *
 * 源策略：
 * - 列表类接口可传 `auto`，服务端按健康度 + 优先级自动故障转移；
 *   `/api/home`、`/api/search`、`/api/categories/{slug}`、`/api/full` 的响应带 source。
 * - `/api/categories`（分类字典）与 `/api/ranks`（排行榜）的响应**不带** source，
 *   而 slug 与 book_id 都是源站私有、跨源不通用，必须显式指定源 ——
 *   书城统一用 `/api/home` 响应里的「活跃源」。
 * - 详情 / 目录 / 正文一律显式回传资源自带的 source（服务端对这三类不做回退）。
 */
object NovelService {

    /**
     * 拼装请求 URL（纯函数，便于单测）。
     * 用 HttpUrl.Builder 逐段 addPathSegment，含前导零的 book_id 与中文 slug 都不会被吞。
     */
    fun makeUrl(
        path: String,
        query: List<Pair<String, String?>> = emptyList(),
        base: String = NovelSettingsStore.base,
    ): String {
        val builder = base.toHttpUrl().newBuilder()
        // 先清掉 base 自带的 / 之外的路径（base 只有 host:port，这里防御性处理）
        path.split("/").filter { it.isNotEmpty() }.forEach { builder.addPathSegment(it) }
        query.forEach { (name, value) -> if (value != null) builder.addQueryParameter(name, value) }
        return builder.build().toString()
    }

    private fun describe(error: Exception): String = when (error) {
        is SocketTimeoutException -> "请求超时：源站回源较慢，可稍后重试。"
        is ConnectException ->
            "连不上小说服务。请确认 4321 服务已启动，真机需在设置里填电脑的局域网 IP。"
        else -> error.message ?: error.javaClass.simpleName
    }

    private suspend inline fun <reified T> load(
        path: String,
        query: List<Pair<String, String?>> = emptyList(),
    ): ApiResult<T> = withContext(Dispatchers.IO) {
        val url = try {
            makeUrl(path, query)
        } catch (e: Exception) {
            return@withContext ApiResult.Error("服务地址无效：${NovelSettingsStore.base}")
        }
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", ApiConfig.userAgent)
                .build()
            NovelApiConfig.client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val detail = parseNovelErrorDetail(body)
                    return@withContext ApiResult.Error(
                        detail.ifEmpty { "HTTP ${response.code}" },
                        response.code,
                    )
                }
                try {
                    ApiResult.Success(NovelApiConfig.json.decodeFromString<T>(body))
                } catch (e: Exception) {
                    ApiResult.Error("响应解析失败（$path）：${e.message}")
                }
            }
        } catch (e: Exception) {
            ApiResult.Error(describe(e))
        }
    }

    // 服务
    suspend fun ping(): ApiResult<NovelPing> = load("api/ping")
    suspend fun cacheStats(): ApiResult<NovelCacheStats> = load("api/cache/stats")
    suspend fun sources(): ApiResult<List<NovelSourceInfo>> = load("api/sources")

    // 发现
    suspend fun home(source: String = "auto"): ApiResult<NovelHomePage> =
        load("api/home", listOf("source" to source))

    suspend fun categories(source: String): ApiResult<List<NovelCategory>> =
        load("api/categories", listOf("source" to source))

    suspend fun categoryBooks(slug: String, page: Int = 1, source: String): ApiResult<NovelBookPage> =
        load(
            "api/categories/$slug",
            listOf("page" to page.toString(), "source" to source),
        )

    suspend fun ranks(board: String? = null, source: String): ApiResult<List<NovelRankBoard>> =
        load("api/ranks", listOf("board" to board, "source" to source))

    suspend fun fullBooks(page: Int = 1, source: String): ApiResult<NovelBookPage> =
        load("api/full", listOf("page" to page.toString(), "source" to source))

    // 检索
    suspend fun search(keyword: String, page: Int = 1, source: String = "auto"): ApiResult<NovelBookPage> =
        load(
            "api/search",
            listOf("kw" to keyword, "page" to page.toString(), "source" to source),
        )

    // 书籍
    suspend fun bookDetail(source: String, bookId: String): ApiResult<NovelBookDetail> =
        load("api/book/$bookId", listOf("source" to source))

    /** limit = 0 表示一次拉全（实测《牧神记》1920 章 0.07s）。 */
    suspend fun chapters(
        source: String,
        bookId: String,
        offset: Int = 0,
        limit: Int = 0,
    ): ApiResult<NovelChapterList> = load(
        "api/book/$bookId/chapters",
        listOf(
            "offset" to offset.toString(),
            "limit" to limit.toString(),
            "source" to source,
        ),
    )

    // 阅读
    suspend fun chapter(
        source: String,
        bookId: String,
        chapterId: String,
        cleanAds: Boolean = true,
    ): ApiResult<NovelChapterBody> = load(
        "api/chapter/$bookId/$chapterId",
        listOf("source" to source, "clean_ads" to cleanAds.toString()),
    )
}
