package com.slackoff.app.playback

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.slackoff.app.model.*
import com.slackoff.app.network.ApiResult
import com.slackoff.app.network.DanmakuService
import com.slackoff.app.network.VideoService
import com.slackoff.app.danmaku.DanmakuSettings
import com.slackoff.app.support.EpisodeStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "SlackOffDanmaku"
    }

    val engine = PlaybackEngine(application)

    private var _slug = MutableStateFlow("")
    val slug: StateFlow<String> = _slug.asStateFlow()

    private var _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    private var _episodes = MutableStateFlow<List<Episode>>(emptyList())
    val episodes: StateFlow<List<Episode>> = _episodes.asStateFlow()

    private var _currentEpisode = MutableStateFlow(1)
    val currentEpisode: StateFlow<Int> = _currentEpisode.asStateFlow()

    private var _playInfo = MutableStateFlow<PlayInfo?>(null)
    val playInfo: StateFlow<PlayInfo?> = _playInfo.asStateFlow()

    private var _comments = MutableStateFlow<List<DanmakuComment>>(emptyList())
    val comments: StateFlow<List<DanmakuComment>> = _comments.asStateFlow()

    private var _matchedAnime = MutableStateFlow<AnimeSearchItem?>(null)
    val matchedAnime: StateFlow<AnimeSearchItem?> = _matchedAnime.asStateFlow()

    val danmaku = DanmakuSettings().also { it.load(application) }

    private var _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    val qualityTitle: String
        get() = _playInfo.value?.streams?.lastOrNull { it.streamUrl != null }?.title ?: "自动"

    val timeText: String
        get() = "${formatTime(engine.currentTime)} / ${formatTime(engine.totalDuration)}"

    private var _speed = MutableStateFlow(1.0)
    val speed: StateFlow<Double> = _speed.asStateFlow()

    fun init(slug: String, title: String, episodes: List<Episode>, episode: Int) {
        _slug.value = slug
        _title.value = title
        _episodes.value = episodes
        _currentEpisode.value = episode
    }

    fun load() {
        viewModelScope.launch { reload() }
    }

    fun togglePlay() {
        if (engine.isPlaying.value) engine.pause() else engine.play()
    }

    fun next() {
        val eps = _episodes.value
        val current = _currentEpisode.value
        val index = eps.indexOfFirst { it.number == current }
        if (index < 0 || index + 1 >= eps.size) return
        _currentEpisode.value = eps[index + 1].number
        EpisodeStore.remember(_currentEpisode.value, _slug.value)
        viewModelScope.launch { reload() }
    }

    fun previous() {
        val eps = _episodes.value
        val current = _currentEpisode.value
        val index = eps.indexOfFirst { it.number == current }
        if (index <= 0) return
        _currentEpisode.value = eps[index - 1].number
        EpisodeStore.remember(_currentEpisode.value, _slug.value)
        viewModelScope.launch { reload() }
    }

    fun selectEpisode(number: Int) {
        if (number == _currentEpisode.value) return
        _currentEpisode.value = number
        EpisodeStore.remember(number, _slug.value)
        viewModelScope.launch { reload() }
    }

    fun setSpeed(value: Double) {
        _speed.value = value
        engine.setSpeed(value)
    }

    fun seekTo(time: Double) {
        engine.seekTo(time)
    }

    fun seekToFraction(fraction: Float) {
        engine.seekToFraction(fraction)
    }

    private suspend fun reload() {
        _statusMessage.value = null
        _comments.value = emptyList()
        _matchedAnime.value = null

        when (val result = VideoService.play(_slug.value, _currentEpisode.value)) {
            is ApiResult.Success -> {
                val info = result.data
                if (info == null) {
                    _statusMessage.value = "没有播放信息"
                    return
                }
                _playInfo.value = info
                val stream = info.streams?.lastOrNull { it.streamUrl != null }
                if (stream == null || stream.streamUrl == null) {
                    _statusMessage.value = "没有可用清晰度"
                    return
                }
                engine.load(stream.streamUrl!!, stream.headers ?: emptyMap())
                if (engine.errorMessage.value != null) {
                    _statusMessage.value = engine.errorMessage.value
                }
                engine.play()
            }
            is ApiResult.Error -> {
                _statusMessage.value = result.message
            }
        }

        loadDanmaku()
    }

    private suspend fun loadDanmaku() {
        val title = _title.value
        when (val result = DanmakuService.searchAnime(title)) {
            is ApiResult.Success -> {
                val animes = result.data
                android.util.Log.d(TAG, "弹幕搜索 '$title' -> ${animes.size} 条结果")
                if (animes.isEmpty()) return
                val match = animes.firstOrNull { isAnimeMatch(it) } ?: animes.firstOrNull() ?: return
                android.util.Log.d(TAG, "匹配到: id=${match.animeId} ${match.animeTitle}")
                bindDanmaku(match)
            }
            is ApiResult.Error -> android.util.Log.w(TAG, "弹幕搜索失败: ${result.message}")
        }
    }

    private fun isAnimeMatch(anime: AnimeSearchItem): Boolean {
        val animeTitle = anime.animeTitle ?: ""
        val cleanTitle = _title.value.trim()
        return animeTitle.contains(cleanTitle) || cleanTitle.contains(animeTitle)
    }

    private suspend fun bindDanmaku(anime: AnimeSearchItem) {
        _matchedAnime.value = anime
        when (val result = DanmakuService.bangumi(anime.animeId)) {
            is ApiResult.Success -> {
                val bangumi = result.data
                val list = bangumi?.episodes
                if (list == null || list.isEmpty()) {
                    android.util.Log.w(TAG, "剧集列表为空")
                    _comments.value = emptyList()
                    return
                }
                val target = list.firstOrNull { it.episodeNumber == "${_currentEpisode.value}" }
                    ?: list.firstOrNull()
                val episodeId = target?.episodeId ?: run {
                    _comments.value = emptyList()
                    return
                }
                when (val cResult = DanmakuService.comments(episodeId)) {
                    is ApiResult.Success -> {
                        android.util.Log.d(TAG, "弹幕加载成功: ${cResult.data.size} 条 (episodeId=$episodeId)")
                        _comments.value = cResult.data
                    }
                    is ApiResult.Error -> {
                        android.util.Log.w(TAG, "弹幕加载失败: ${cResult.message}")
                        _comments.value = emptyList()
                    }
                }
            }
            is ApiResult.Error -> {
                android.util.Log.w(TAG, "bangumi 详情失败: ${result.message}")
                _comments.value = emptyList()
            }
        }
    }

    private fun formatTime(seconds: Double): String {
        val total = seconds.toInt()
        return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
    }

    override fun onCleared() {
        super.onCleared()
        engine.stop()
    }
}