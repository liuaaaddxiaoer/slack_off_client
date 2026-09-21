package com.slackoff.app.ui.novel

import com.slackoff.app.model.NovelBook

/**
 * 本地列表页的待展示数据。
 *
 * 为什么不跟着路由传 JSON：首页的「最近更新」有 100 本、分类精选块也有 13 本，
 * 序列化成 route 字符串能到几十 KB，Navigation Compose 每条 route 都要解析一遍，
 * 既慢又容易触发路由长度问题。播放器那边传 episodesJson 是因为数据量小（几十条短字段）。
 */
object NovelLocalListPayload {
    var title: String = ""
    var books: List<NovelBook> = emptyList()

    fun set(title: String, books: List<NovelBook>) {
        this.title = title
        this.books = books
    }
}
