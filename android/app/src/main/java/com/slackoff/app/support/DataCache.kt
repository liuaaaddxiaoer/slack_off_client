package com.slackoff.app.support

import com.slackoff.app.model.VideoCard
import com.slackoff.app.model.VideoDetail

/** 首页视频列表缓存：返回首页时不再重新请求、不闪 loading */
object HomeCache {
    var items: List<VideoCard> = emptyList()
}

/** 视频详情缓存：从播放页返回详情页时不再重新请求 */
object DetailCache {
    private val cache = mutableMapOf<String, VideoDetail>()

    fun get(slug: String): VideoDetail? = cache[slug]

    fun put(slug: String, detail: VideoDetail) {
        cache[slug] = detail
    }
}