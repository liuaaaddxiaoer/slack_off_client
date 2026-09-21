package com.slackoff.app.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class VideoCard(
    val slug: String? = null,
    val title: String? = null,
    val url: String? = null,
    val cover: String? = null,
    val rating: String? = null,
    val remark: String? = null,
    val tag: String? = null,
    val section: String? = null
) {
    val id: String get() = slug ?: url ?: title ?: kotlin.random.Random.nextInt().toString()
    val coverUrl: String? get() = cover
}

@Serializable
data class HomeResponse(
    val code: Int? = null,
    val message: String? = null,
    val items: List<VideoCard>? = null
)

@Serializable
data class SearchResponse(
    val code: Int? = null,
    val message: String? = null,
    val items: List<VideoCard>? = null
)

@Serializable
data class Episode(
    val index: Int? = null,
    val name: String? = null,
    val url: String? = null,
    val dataid: String? = null
) {
    val id: Int get() = index ?: 0
    val number: Int get() = index ?: 0
    val displayName: String get() = name ?: "$number"
}

@Serializable
data class VideoDetail(
    val slug: String? = null,
    val title: String? = null,
    val cover: String? = null,
    val score: String? = null,
    val director: String? = null,
    val writer: String? = null,
    val actor: String? = null,
    @SerialName("type_name") val typeName: String? = null,
    val area: String? = null,
    val lang: String? = null,
    val release: String? = null,
    val duration: String? = null,
    @SerialName("also_known") val alsoKnown: String? = null,
    val description: String? = null,
    @SerialName("episode_count") val episodeCount: Int? = null,
    @SerialName("update_info") val updateInfo: String? = null,
    val episodes: List<Episode>? = null
) {
    val coverUrl: String? get() = cover
}

@Serializable
data class DetailResponse(
    val code: Int? = null,
    val message: String? = null,
    val detail: VideoDetail? = null
)

@Serializable
data class Stream(
    val mtype: String? = null,
    val bitrate: Int? = null,
    val title: String? = null,
    val description: String? = null,
    @SerialName("is_vip") val isVip: Boolean? = null,
    val locked: Boolean? = null,
    val url: String? = null,
    val headers: Map<String, String>? = null
) {
    val streamUrl: String? get() = url
}

@Serializable
data class PlayInfo(
    val slug: String? = null,
    val dataid: String? = null,
    val title: String? = null,
    val episode: Int? = null,
    val quality: String? = null,
    @SerialName("subtitle_url") val subtitleUrl: String? = null,
    val streams: List<Stream>? = null
)

@Serializable
data class PlayResponse(
    val code: Int? = null,
    val message: String? = null,
    val play: PlayInfo? = null
)