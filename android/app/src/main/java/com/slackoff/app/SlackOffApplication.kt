package com.slackoff.app

import android.app.Application
import com.slackoff.app.danmaku.DanmakuSettings
import com.slackoff.app.support.BookshelfStore
import com.slackoff.app.support.EpisodeStore
import com.slackoff.app.support.NovelCacheDownloadManager
import com.slackoff.app.support.NovelChapterCache
import com.slackoff.app.support.NovelSearchHistory
import com.slackoff.app.support.NovelSettingsStore

class SlackOffApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        EpisodeStore.init(this)
        // 小说频道：服务地址 / 阅读器设置、书架与进度、搜索历史
        NovelSettingsStore.init(this)
        BookshelfStore.init(this)
        NovelSearchHistory.init(this)
        NovelChapterCache.init(this)
        NovelCacheDownloadManager.init(this)
        // Initialize HLS local server
        com.slackoff.app.playback.HlsLocalServer.getInstance().start()
    }
}