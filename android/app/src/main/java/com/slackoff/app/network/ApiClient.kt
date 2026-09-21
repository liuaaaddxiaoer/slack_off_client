package com.slackoff.app.network

import com.slackoff.app.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Error(val message: String, val code: Int? = null) : ApiResult<Nothing>()
}

object ApiClient {
    internal suspend fun <T> loadData(
        url: String,
        json: Json,
        deserializer: (String) -> T
    ): ApiResult<T> = withContext(Dispatchers.IO) {
        try {
            val request = ApiConfig.request(url)
            val response = ApiConfig.client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext ApiResult.Error("Empty response")
            if (!response.isSuccessful) {
                return@withContext ApiResult.Error("HTTP ${response.code}", response.code)
            }
            val data = deserializer(body)
            ApiResult.Success(data)
        } catch (e: Exception) {
            ApiResult.Error(e.message ?: "Unknown error")
        }
    }
}

object VideoService {
    suspend fun home(): ApiResult<List<VideoCard>> {
        val result = ApiClient.loadData(
            url = "${ApiConfig.videoBase}/api/home",
            json = ApiConfig.videoJson
        ) { body ->
            ApiConfig.videoJson.decodeFromString<HomeResponse>(body)
        }
        return when (result) {
            is ApiResult.Success -> ApiResult.Success(result.data.items ?: emptyList())
            is ApiResult.Error -> result
        }
    }

    suspend fun search(query: String): ApiResult<List<VideoCard>> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val result = ApiClient.loadData(
            url = "${ApiConfig.videoBase}/api/search?q=$encodedQuery",
            json = ApiConfig.videoJson
        ) { body ->
            ApiConfig.videoJson.decodeFromString<SearchResponse>(body)
        }
        return when (result) {
            is ApiResult.Success -> ApiResult.Success(result.data.items ?: emptyList())
            is ApiResult.Error -> result
        }
    }

    suspend fun detail(slug: String): ApiResult<VideoDetail?> {
        val result = ApiClient.loadData(
            url = "${ApiConfig.videoBase}/api/detail/$slug",
            json = ApiConfig.videoJson
        ) { body ->
            ApiConfig.videoJson.decodeFromString<DetailResponse>(body)
        }
        return when (result) {
            is ApiResult.Success -> ApiResult.Success(result.data.detail)
            is ApiResult.Error -> result
        }
    }

    suspend fun play(slug: String, episode: Int): ApiResult<PlayInfo?> {
        val result = ApiClient.loadData(
            url = "${ApiConfig.videoBase}/api/play/$slug?episode=$episode",
            json = ApiConfig.videoJson
        ) { body ->
            ApiConfig.videoJson.decodeFromString<PlayResponse>(body)
        }
        return when (result) {
            is ApiResult.Success -> ApiResult.Success(result.data.play)
            is ApiResult.Error -> result
        }
    }
}

object DanmakuService {
    private fun danmuUrl(path: String): String =
        "${ApiConfig.danmuBase}/${ApiConfig.danmuToken}$path"

    suspend fun searchAnime(keyword: String): ApiResult<List<AnimeSearchItem>> {
        val encodedKeyword = java.net.URLEncoder.encode(keyword, "UTF-8")
        val result = ApiClient.loadData(
            url = "${danmuUrl("/api/v2/search/anime")}?keyword=$encodedKeyword",
            json = ApiConfig.danmuJson
        ) { body ->
            ApiConfig.danmuJson.decodeFromString<AnimeSearchResponse>(body)
        }
        return when (result) {
            is ApiResult.Success -> ApiResult.Success(result.data.animes ?: emptyList())
            is ApiResult.Error -> result
        }
    }

    suspend fun bangumi(animeId: Int): ApiResult<BangumiDetail?> {
        val result = ApiClient.loadData(
            url = danmuUrl("/api/v2/bangumi/$animeId"),
            json = ApiConfig.danmuJson
        ) { body ->
            ApiConfig.danmuJson.decodeFromString<BangumiResponse>(body)
        }
        return when (result) {
            is ApiResult.Success -> ApiResult.Success(result.data.bangumi)
            is ApiResult.Error -> result
        }
    }

    suspend fun comments(episodeId: Int): ApiResult<List<DanmakuComment>> {
        // 弹幕服务对超长响应会被代理截断（热门剧集上万条弹幕），甚至偶尔 500，
        // 因此解析要能容忍「半截 JSON」，失败时再重试几次。
        var last: ApiResult<List<DanmakuComment>> = ApiResult.Error("未请求")
        repeat(3) { attempt ->
            val result = ApiClient.loadData(
                url = "${danmuUrl("/api/v2/comment/$episodeId")}?format=json",
                json = ApiConfig.danmuJson
            ) { body ->
                parseCommentsTolerant(body)
            }
            when (result) {
                is ApiResult.Success -> return ApiResult.Success(
                    (result.data.comments ?: emptyList()).mapNotNull { DanmakuComment.from(it) }
                )
                is ApiResult.Error -> last = result
            }
            if (attempt < 2) kotlinx.coroutines.delay(500)
        }
        return last
    }

    /** 正常解析；失败时按「最后一个完整弹幕对象」截断并补 `]}` 重试。 */
    private fun parseCommentsTolerant(body: String): CommentResponse {
        val json = ApiConfig.danmuJson
        try {
            return json.decodeFromString<CommentResponse>(body)
        } catch (_: Exception) {
            // 落到截断修复路径
        }
        var end = body.length
        var attempt = 0
        while (attempt < 50) {
            val cut = body.lastIndexOf("},{", end - 1)
            if (cut < 0) break
            val candidate = body.substring(0, cut + 1) + "]}"
            try {
                return json.decodeFromString<CommentResponse>(candidate)
            } catch (_: Exception) {
                end = cut
                attempt++
            }
        }
        throw IllegalStateException("弹幕数据无法解析（响应可能被截断）")
    }
}