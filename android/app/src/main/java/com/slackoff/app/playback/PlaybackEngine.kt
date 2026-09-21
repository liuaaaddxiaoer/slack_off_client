package com.slackoff.app.playback

import android.app.Application
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PlaybackMode {
    IDLE, SLIDESHOW, EXOPLAYER
}

class PlaybackEngine(private val application: Application) {
    private var _mode = MutableStateFlow(PlaybackMode.IDLE)
    val mode: StateFlow<PlaybackMode> = _mode.asStateFlow()

    private var _segments = MutableStateFlow<List<M3u8Segment>>(emptyList())
    val segments: StateFlow<List<M3u8Segment>> = _segments.asStateFlow()

    private var _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private var _elapsed = MutableStateFlow(0.0)
    val elapsed: StateFlow<Double> = _elapsed.asStateFlow()

    private var _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private var _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private var _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private var _speed = MutableStateFlow(1.0)
    val speed: StateFlow<Double> = _speed.asStateFlow()

    private var _volume = MutableStateFlow(1.0f)
    val volume: StateFlow<Float> = _volume.asStateFlow()

    private var _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private var _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private var _playerDuration = MutableStateFlow(0.0)
    val playerDuration: StateFlow<Double> = _playerDuration.asStateFlow()

    private var _playerTime = MutableStateFlow(0.0)
    val playerTime: StateFlow<Double> = _playerTime.asStateFlow()

    /** 供 UI 观察的当前播放时间（tick / seek 时刷新）。 */
    private val _currentTimeFlow = MutableStateFlow(0.0)
    val currentTimeFlow: StateFlow<Double> = _currentTimeFlow.asStateFlow()

    private fun publishTime() {
        _currentTimeFlow.value = currentTime
    }

    var exoPlayer: ExoPlayer? = null
        private set

    private var source: HlsMediaSource? = null
    private var tickJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    val currentSegment: M3u8Segment?
        get() {
            val segs = _segments.value
            val idx = _currentIndex.value
            return if (idx in segs.indices) segs[idx] else null
        }

    val playlistDuration: Double
        get() = _segments.value.sumOf { it.duration }

    val totalDuration: Double
        get() {
            if (_mode.value == PlaybackMode.EXOPLAYER && _playerDuration.value > 0) {
                return _playerDuration.value
            }
            return playlistDuration
        }

    val currentTime: Double
        get() {
            if (_mode.value == PlaybackMode.EXOPLAYER) return _playerTime.value
            val segs = _segments.value
            val idx = _currentIndex.value
            val previous = segs.take(idx).sumOf { it.duration }
            return previous + minOf(_elapsed.value, segs.getOrNull(idx)?.duration ?: 0.0)
        }

    val progress: Float
        get() {
            val dur = totalDuration
            if (dur <= 0) return 0f
            return (currentTime / dur).toFloat().coerceIn(0f, 1f)
        }

    fun setSpeed(speed: Double) {
        _speed.value = speed
        exoPlayer?.setPlaybackSpeed(speed.toFloat())
    }

    fun setVolume(volume: Float) {
        _volume.value = volume
        if (!_isMuted.value) {
            exoPlayer?.volume = volume
        }
    }

    fun setMuted(muted: Boolean) {
        _isMuted.value = muted
        exoPlayer?.volume = if (muted) 0f else _volume.value
    }

    suspend fun load(streamUrl: String, headers: Map<String, String> = emptyMap()) {
        stopTicking()
        teardown()
        _isLoading.value = true
        _errorMessage.value = null

        try {
            // 判断是否是 MP4 直链（不是 M3U8 播放列表）
            val isDirectMp4 = isDirectVideoUrl(streamUrl)

            if (isDirectMp4) {
                // MP4 直链：直接用 ExoPlayer 播放，不需要 HLS 本地代理
                startDirectVideo(streamUrl, headers)
            } else {
                // M3U8 播放列表：走 HLS 本地代理流程
                val playlistData = withContext(Dispatchers.IO) {
                    MediaFetcher.fetch(streamUrl, headers)
                }
                if (playlistData == null) {
                    _errorMessage.value = "无法加载播放列表"
                    _isLoading.value = false
                    return
                }
                val text = String(playlistData, Charsets.UTF_8)
                val parsed = M3u8Parser.parse(text, streamUrl)
                _segments.value = parsed
                _currentIndex.value = 0
                _elapsed.value = 0.0

                val kind = withContext(Dispatchers.IO) {
                    probe(parsed, headers)
                }

                if (kind == SegmentKind.IMAGE) {
                    _mode.value = PlaybackMode.SLIDESHOW
                } else {
                    startVideo(streamUrl, headers, playlistData, kind)
                }
            }
        } catch (e: Exception) {
            _errorMessage.value = e.message
            _mode.value = PlaybackMode.IDLE
        }

        _isLoading.value = false
        startTicking()
    }

    /** 判断是否是直接的视频文件（MP4 等），而不是 M3U8 播放列表 */
    private fun isDirectVideoUrl(url: String): Boolean {
        val path = url.substringBefore("?").substringBefore("#").lowercase()
        return path.endsWith(".mp4") ||
               path.endsWith(".m4v") ||
               path.endsWith(".mov") ||
               path.endsWith(".webm") ||
               path.endsWith(".mkv") ||
               path.endsWith(".avi")
    }

    /** 直接播放视频文件（MP4 等），不经过 HLS 本地代理 */
    private suspend fun startDirectVideo(url: String, headers: Map<String, String>) =
        withContext(Dispatchers.Main) {
            val player = ExoPlayer.Builder(application).build()
            player.volume = if (_isMuted.value) 0f else _volume.value
            player.setPlaybackSpeed(_speed.value.toFloat())

            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    _isBuffering.value = state == Player.STATE_BUFFERING
                    _isPlaying.value = state == Player.STATE_READY && player.playWhenReady
                }

                override fun onPlayerError(error: PlaybackException) {
                    _errorMessage.value = error.message
                }
            })

            val mediaItem = MediaItem.fromUri(url)
            player.setMediaItem(mediaItem)
            player.prepare()
            player.playWhenReady = true

            exoPlayer = player
            _mode.value = PlaybackMode.EXOPLAYER
            _isPlaying.value = true
        }

    private suspend fun probe(segments: List<M3u8Segment>, headers: Map<String, String>): SegmentKind =
        withContext(Dispatchers.IO) {
            val first = segments.firstOrNull() ?: return@withContext SegmentKind.UNKNOWN
            val data = MediaFetcher.fetch(first.url, headers, 0 until 65536)
                ?: return@withContext SegmentKind.UNKNOWN
            TsSegmentRepair.kindOf(data)
        }

    private suspend fun startVideo(
        origin: String,
        headers: Map<String, String>,
        playlist: ByteArray,
        kind: SegmentKind
    ) = withContext(Dispatchers.Main) {
        val ext = if (kind == SegmentKind.FRAGMENTED_MP4) "m4s" else "ts"
        val (url, src) = HlsLocalServer.mount(
            origin = origin,
            headers = headers,
            segmentExtension = ext,
            playlist = playlist
        )
        source = src

        val player = ExoPlayer.Builder(application).build()
        player.volume = if (_isMuted.value) 0f else _volume.value
        player.setPlaybackSpeed(_speed.value.toFloat())

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                _isBuffering.value = state == Player.STATE_BUFFERING
                _isPlaying.value = state == Player.STATE_READY && player.playWhenReady
            }

            override fun onPlayerError(error: PlaybackException) {
                _errorMessage.value = error.message
            }
        })

        val mediaItem = MediaItem.fromUri(url)
        player.setMediaItem(mediaItem)
        player.prepare()
        player.playWhenReady = true

        exoPlayer = player
        _mode.value = PlaybackMode.EXOPLAYER
        _isPlaying.value = true
    }

    fun play() {
        when (_mode.value) {
            PlaybackMode.SLIDESHOW -> {
                val segs = _segments.value
                val idx = _currentIndex.value
                if (idx == segs.size - 1 && _elapsed.value >= (segs.getOrNull(idx)?.duration ?: 0.0)) {
                    seekToFraction(0f)
                }
                _isPlaying.value = true
                startTicking()
            }
            PlaybackMode.EXOPLAYER -> {
                exoPlayer?.playWhenReady = true
                _isPlaying.value = true
            }
            PlaybackMode.IDLE -> {}
        }
    }

    fun pause() {
        _isPlaying.value = false
        exoPlayer?.playWhenReady = false
    }

    fun tick(delta: Double) {
        when (_mode.value) {
            PlaybackMode.SLIDESHOW -> {
                if (!_isPlaying.value) return
                val segs = _segments.value
                val idx = _currentIndex.value
                val segment = segs.getOrNull(idx) ?: return
                _elapsed.value = _elapsed.value + delta * maxOf(_speed.value, 0.25)
                if (_elapsed.value >= segment.duration) {
                    if (idx + 1 < segs.size) {
                        _currentIndex.value = idx + 1
                        _elapsed.value = 0.0
                    } else {
                        _elapsed.value = segment.duration
                        _isPlaying.value = false
                    }
                }
            }
            PlaybackMode.EXOPLAYER -> {
                val player = exoPlayer ?: return
                val seconds = player.currentPosition / 1000.0
                if (seconds.isFinite() && seconds >= 0) _playerTime.value = seconds
                val duration = player.duration / 1000.0
                if (duration.isFinite() && duration > 0) _playerDuration.value = duration
            }
            PlaybackMode.IDLE -> {}
        }
        publishTime()
    }

    fun seekToFraction(fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        when (_mode.value) {
            PlaybackMode.SLIDESHOW -> {
                val segs = _segments.value
                if (segs.isEmpty()) return
                var remaining = clamped.toDouble() * totalDuration
                var index = segs.size - 1
                for ((i, seg) in segs.withIndex()) {
                    if (remaining <= seg.duration) {
                        index = i
                        break
                    }
                    remaining -= seg.duration
                }
                _currentIndex.value = index.coerceIn(0, segs.size - 1)
                _elapsed.value = remaining.coerceAtMost(segs[_currentIndex.value].duration)
            }
            PlaybackMode.EXOPLAYER -> {
                val dur = totalDuration
                if (dur > 0) {
                    exoPlayer?.seekTo((clamped * dur * 1000).toLong())
                }
            }
            PlaybackMode.IDLE -> {}
        }
        publishTime()
    }

    fun seekTo(time: Double) {
        if (totalDuration > 0) {
            seekToFraction((time / totalDuration).toFloat())
        }
    }

    fun startTicking() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive) {
                delay(50)
                tick(0.05)
            }
        }
    }

    private fun stopTicking() {
        tickJob?.cancel()
        tickJob = null
    }

    fun stop() {
        stopTicking()
        teardown()
    }

    private fun teardown() {
        exoPlayer?.stop()
        exoPlayer?.release()
        exoPlayer = null
        source?.let { HlsLocalServer.getInstance().unregister(it) }
        source = null
        _mode.value = PlaybackMode.IDLE
        _segments.value = emptyList()
        _currentIndex.value = 0
        _elapsed.value = 0.0
        _isPlaying.value = false
        _isBuffering.value = false
        _playerTime.value = 0.0
        _playerDuration.value = 0.0
        _currentTimeFlow.value = 0.0
    }
}