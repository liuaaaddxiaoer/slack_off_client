package com.slackoff.app.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 小说聚合 API（本地 xiaoshuo_server :4321）的响应模型。
 *
 * 与 iOS 端 NovelModels.swift 一一对应：字段名、可空性、语义都保持一致。
 * `bookId` / `chapterId` 必须是 String —— 源站私有 id 可能带前导零，用整型会静默丢零；
 * 且 id 跨源不通用，所以每条数据都带 `source`，请求详情/目录/正文时必须原样回传。
 */

/** 书架与详情页共用的稳定 key。 */
fun novelKey(source: String, bookId: String): String = "$source:$bookId"

@Serializable
data class NovelSourceInfo(
    val id: String,
    val name: String,
    @SerialName("base_url") val baseUrl: String? = null,
    val enabled: Boolean? = null,
    val priority: Int? = null,
    val capabilities: Map<String, Boolean>? = null,
    val notes: String? = null,
) {
    /** 缺字段时按「支持」处理，服务端只会显式标 false。 */
    fun supports(capability: String): Boolean = capabilities?.get(capability) ?: true
}

@Serializable
data class NovelBook(
    val source: String,
    @SerialName("book_id") val bookId: String,
    val title: String,
    val author: String? = null,
    val category: String? = null,
    /** 源站封面 URL，v1 不使用（该域名在设备上直连不通），封面统一走文字封面。 */
    val cover: String? = null,
    val intro: String? = null,
    @SerialName("latest_chapter") val latestChapter: String? = null,
    @SerialName("latest_chapter_id") val latestChapterId: String? = null,
    @SerialName("update_time") val updateTime: String? = null,
    val url: String? = null,
) {
    val key: String get() = novelKey(source, bookId)

    /** 文字封面的稳定取色种子。 */
    val coverSeed: String get() = key
}

@Serializable
data class NovelBookDetail(
    val source: String,
    @SerialName("book_id") val bookId: String,
    val title: String,
    val author: String? = null,
    val category: String? = null,
    val cover: String? = null,
    val intro: String? = null,
    @SerialName("latest_chapter") val latestChapter: String? = null,
    @SerialName("latest_chapter_id") val latestChapterId: String? = null,
    @SerialName("update_time") val updateTime: String? = null,
    val url: String? = null,
    val status: String? = null,
    @SerialName("word_count") val wordCount: Int? = null,
    @SerialName("chapter_count") val chapterCount: Int? = null,
    @SerialName("first_chapter") val firstChapter: String? = null,
    @SerialName("first_chapter_id") val firstChapterId: String? = null,
) {
    val key: String get() = novelKey(source, bookId)

    val entryChapterId: String? get() = firstChapterId ?: latestChapterId

    /** 字数转「xx.x 万字」，源站没给就返回 null（不显示 0 字）。 */
    val wordCountText: String?
        get() {
            val count = wordCount ?: return null
            if (count <= 0) return null
            return if (count >= 10_000) String.format("%.1f 万字", count / 10_000.0) else "$count 字"
        }
}

@Serializable
data class NovelBookPage(
    val source: String,
    val kind: String? = null,
    val name: String? = null,
    val page: Int? = null,
    @SerialName("page_size") val pageSize: Int? = null,
    @SerialName("total_pages") val totalPages: Int? = null,
    val total: Int? = null,
    @SerialName("has_more") val hasMore: Boolean? = null,
    val items: List<NovelBook>? = null,
) {
    val books: List<NovelBook> get() = items ?: emptyList()
    val more: Boolean get() = hasMore ?: false
}

@Serializable
data class NovelHomeCategoryBlock(
    val name: String,
    val books: List<NovelBook>? = null,
) {
    val items: List<NovelBook> get() = books ?: emptyList()
}

@Serializable
data class NovelHomePage(
    /** 服务端实际命中的源。分类字典与排行榜的响应里不带 source，必须靠它显式指定。 */
    val source: String,
    @SerialName("site_name") val siteName: String? = null,
    @SerialName("hot_books") val hotBooks: List<NovelBook>? = null,
    @SerialName("recommend_books") val recommendBooks: List<NovelBook>? = null,
    @SerialName("latest_updates") val latestUpdates: List<NovelBook>? = null,
    @SerialName("category_blocks") val categoryBlocks: List<NovelHomeCategoryBlock>? = null,
) {
    val hot: List<NovelBook> get() = hotBooks ?: emptyList()
    val recommend: List<NovelBook> get() = recommendBooks ?: emptyList()
    val latest: List<NovelBook> get() = latestUpdates ?: emptyList()
    val blocks: List<NovelHomeCategoryBlock> get() = categoryBlocks ?: emptyList()
}

@Serializable
data class NovelCategory(
    val slug: String,
    val name: String,
    val url: String? = null,
    val paginated: Boolean? = null,
)

@Serializable
data class NovelRankEntry(
    val rank: Int? = null,
    @SerialName("book_id") val bookId: String,
    val title: String,
    val category: String? = null,
    val author: String? = null,
    val url: String? = null,
) {
    /** 响应里没有 source，由请求方用当前活跃源补齐后才能跳详情。 */
    val key: String get() = "$bookId-${rank ?: 0}"
}

@Serializable
data class NovelRankBoard(
    val board: String,
    val total: Int? = null,
    val items: List<NovelRankEntry>? = null,
) {
    val entries: List<NovelRankEntry> get() = items ?: emptyList()
}

@Serializable
data class NovelChapterItem(
    val index: Int,
    @SerialName("chapter_id") val chapterId: String,
    val title: String,
    val url: String? = null,
)

@Serializable
data class NovelChapterList(
    val source: String,
    @SerialName("book_id") val bookId: String,
    val title: String? = null,
    val total: Int? = null,
    val offset: Int? = null,
    val limit: Int? = null,
    val returned: Int? = null,
    val chapters: List<NovelChapterItem>? = null,
) {
    val items: List<NovelChapterItem> get() = chapters ?: emptyList()
}

@Serializable
data class NovelChapterBody(
    val source: String,
    @SerialName("book_id") val bookId: String,
    @SerialName("chapter_id") val chapterId: String,
    val title: String,
    /** 纯文本正文，段落以 \n 分隔（服务端已 clean_ads）。 */
    val content: String,
    @SerialName("content_html") val contentHtml: String? = null,
    @SerialName("word_count") val wordCount: Int? = null,
    @SerialName("prev_chapter_id") val prevChapterId: String? = null,
    @SerialName("prev_chapter_title") val prevChapterTitle: String? = null,
    @SerialName("next_chapter_id") val nextChapterId: String? = null,
    @SerialName("next_chapter_title") val nextChapterTitle: String? = null,
    @SerialName("catalog_url") val catalogUrl: String? = null,
) {
    /** 正文按换行切成段落，供分页引擎逐行测量。 */
    val paragraphs: List<String> get() = content.split("\n").filter { it.isNotEmpty() }
}

@Serializable
data class NovelPing(
    val ok: Boolean? = null,
    val service: String? = null,
    val version: String? = null,
)

@Serializable
data class NovelCacheStats(
    val cache: Cache? = null,
    @SerialName("hit_rate") val hitRate: Double? = null,
    val fetcher: Fetcher? = null,
) {
    @Serializable
    data class Cache(
        val entries: Int? = null,
        val hits: Int? = null,
        val misses: Int? = null,
    )

    @Serializable
    data class Fetcher(
        val retries: Int? = null,
        @SerialName("timeout_s") val timeoutS: Double? = null,
        val concurrency: Int? = null,
        @SerialName("proxy_mode") val proxyMode: String? = null,
    )
}

/**
 * 服务端错误体。正常错误是 `{"detail":"重试 4 次后仍失败: PoolTimeout | ..."}`，
 * FastAPI 参数校验错误是 `{"detail":[{"msg":...}]}`，两种都要能解析出可读文案。
 */
@Serializable
data class NovelErrorDetailItem(val msg: String? = null)

fun parseNovelErrorDetail(body: String): String {
    return try {
        val wrapper = kotlinx.serialization.json.Json.parseToJsonElement(body)
        val detail = wrapper.jsonObjectOrNull()?.get("detail") ?: return ""
        when {
            detail is kotlinx.serialization.json.JsonPrimitive && detail.isString -> detail.content
            detail is kotlinx.serialization.json.JsonArray ->
                detail.mapNotNull { it.jsonObjectOrNull()?.get("msg")?.toString()?.trim('"') }
                    .joinToString("; ")
            else -> detail.toString()
        }
    } catch (_: Exception) {
        ""
    }
}

private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull() = this as? kotlinx.serialization.json.JsonObject
