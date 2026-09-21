package com.slackoff.app.model

import kotlinx.serialization.Serializable

// 弹幕 API（jokkad-danmu）返回的是驼峰字段名，这里属性名与其保持一致，
// 不要加 snake_case 的 @SerialName，否则会因缺字段反序列化失败。

@Serializable
data class AnimeSearchItem(
    val animeId: Int,
    val bangumiId: String? = null,
    val animeTitle: String? = null,
    val imageUrl: String? = null,
    val type: String? = null,
    val episodeCount: Int? = null
) {
    val id: Int get() = animeId
    val displayTitle: String get() = animeTitle ?: "番剧 $animeId"
}

@Serializable
data class AnimeSearchResponse(
    val errorCode: Int? = null,
    val success: Boolean? = null,
    val errorMessage: String? = null,
    val animes: List<AnimeSearchItem>? = null
)

@Serializable
data class BangumiEpisode(
    val seasonId: String? = null,
    val episodeId: Int,
    val episodeTitle: String? = null,
    val episodeNumber: String? = null,
    val airDate: String? = null
) {
    val id: Int get() = episodeId
}

@Serializable
data class BangumiDetail(
    val animeId: Int? = null,
    val bangumiId: String? = null,
    val animeTitle: String? = null,
    val imageUrl: String? = null,
    val isOnAir: Boolean? = null,
    val type: String? = null,
    val episodes: List<BangumiEpisode>? = null
)

@Serializable
data class BangumiResponse(
    val errorCode: Int? = null,
    val success: Boolean? = null,
    val errorMessage: String? = null,
    val bangumi: BangumiDetail? = null
)

@Serializable
data class CommentItem(
    val cid: Int,
    val p: String? = null,
    val m: String? = null
)

@Serializable
data class CommentResponse(
    val count: Int? = null,
    val comments: List<CommentItem>? = null
)

data class DanmakuComment(
    val id: String = java.util.UUID.randomUUID().toString(),
    val text: String,
    val time: Double,
    val lane: Double,
    val type: Int,
    val color: Int
) {
    companion object {
        fun from(item: CommentItem): DanmakuComment? {
            val text = item.m ?: return null
            if (text.isEmpty()) return null
            val packed = item.p ?: return null
            val parts = packed.split(",")
            val time = parts.getOrNull(0)?.toDoubleOrNull() ?: return null
            val type = parts.getOrNull(1)?.toIntOrNull() ?: 1
            val color = parts.getOrNull(2)?.toIntOrNull() ?: 0xFFFFFF
            val lane = kotlin.math.abs(item.cid.hashCode() % 7).toDouble()
            return DanmakuComment(text = text, time = time, lane = lane, type = type, color = color)
        }
    }
}
