package com.slackoff.app.ui.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.slackoff.app.model.Episode
import com.slackoff.app.playback.PlayerViewModel
import com.slackoff.app.playback.PlaybackMode
import com.slackoff.app.support.ScreenInsets
import com.slackoff.app.ui.components.VideoSlider
import com.slackoff.app.ui.danmaku.DanmakuSettingsSheet
import com.slackoff.app.danmaku.DanmakuView
import com.slackoff.app.ui.theme.Theme
import kotlin.math.roundToInt

@Composable
fun PlayerScreen(
    slug: String,
    episode: Int,
    title: String,
    episodes: List<Episode>,
    onBack: () -> Unit,
    viewModel: PlayerViewModel = viewModel()
) {
    val context = LocalContext.current
    val activity = remember { ScreenInsets.findActivity(context) }
    val currentEp = viewModel.currentEpisode.collectAsState().value
    var fullscreenMode by remember { mutableStateOf<FullscreenMode?>(null) }
    var showDanmakuSettings by remember { mutableStateOf(false) }

    LaunchedEffect(slug, episode) {
        viewModel.init(slug, title, episodes, episode)
        viewModel.load()
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.engine.stop()
        }
    }

    // 进入后台时暂停播放，回到前台时恢复（仅当本次暂停是由后台引起时）
    val wasPlayingBeforeBackground = remember { mutableStateOf(false) }
    DisposableEffect(activity) {
        val lifecycleOwner = activity as? LifecycleOwner
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    if (viewModel.engine.isPlaying.value) {
                        wasPlayingBeforeBackground.value = true
                        viewModel.engine.pause()
                    } else {
                        wasPlayingBeforeBackground.value = false
                    }
                }
                Lifecycle.Event.ON_START -> {
                    if (wasPlayingBeforeBackground.value) {
                        wasPlayingBeforeBackground.value = false
                        viewModel.engine.play()
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose {
            lifecycleOwner?.lifecycle?.removeObserver(observer)
        }
    }

    // 返回手势处理：全屏时先退出全屏，而不是直接返回详情页
    BackHandler(enabled = fullscreenMode != null || showDanmakuSettings) {
        if (showDanmakuSettings) {
            showDanmakuSettings = false
        } else if (fullscreenMode != null) {
            fullscreenMode = null
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Main content
        Scaffold(containerColor = Color.Black) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // Player area
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .background(Color.Black)
                        .pointerInput(Unit) {
                            // 双击播放/暂停
                            detectTapGestures(onDoubleTap = { viewModel.togglePlay() })
                        }
                ) {
                    // Media layer
                    // 全屏时不渲染竖屏的 PlayerView：两个 SurfaceView 同时绑定同一个
                    // ExoPlayer 会互相抢 surface，导致进出全屏时画面定格/不同步
                    if (fullscreenMode == null) {
                        MediaLayer(viewModel)
                    }

                    // Play button overlay
                    val isPlaying = viewModel.engine.isPlaying.collectAsState().value
                    val mode = viewModel.engine.mode.collectAsState().value
                    if (mode == PlaybackMode.EXOPLAYER && !isPlaying) {
                        IconButton(
                            onClick = { viewModel.togglePlay() },
                            modifier = Modifier.align(Alignment.Center)
                        ) {
                            Icon(
                                Icons.Default.PlayCircle,
                                contentDescription = "播放",
                                tint = Color.White,
                                modifier = Modifier.size(66.dp)
                            )
                        }
                    }

                    // Top bar
                    TopBar(
                        title = viewModel.title.collectAsState().value,
                        episodeNumber = viewModel.currentEpisode.collectAsState().value,
                        episodeCount = viewModel.episodes.collectAsState().value.size,
                        onBack = onBack,
                        onDanmakuSettings = { showDanmakuSettings = true }
                    )

                    // Bottom controls
                    BottomControls(
                        viewModel = viewModel,
                        isPlaying = isPlaying,
                        onEnterPortraitFullscreen = { fullscreenMode = FullscreenMode.PORTRAIT },
                        onEnterLandscapeFullscreen = { fullscreenMode = FullscreenMode.LANDSCAPE },
                        onDanmakuSettings = { showDanmakuSettings = true },
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }

                // Episode strip
                EpisodeStrip(
                    episodes = episodes,
                    currentEp = currentEp,
                    onSelect = { viewModel.selectEpisode(it) }
                )
            }
        }

        // Fullscreen overlay
        fullscreenMode?.let { mode ->
            FullscreenPlayerOverlay(
                viewModel = viewModel,
                isLandscape = mode == FullscreenMode.LANDSCAPE,
                onExit = { fullscreenMode = null }
            )
        }

        // Danmaku settings drawer
        if (showDanmakuSettings) {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f))
                        .clickable { showDanmakuSettings = false }
                )
                Row(modifier = Modifier.fillMaxSize()) {
                    Spacer(modifier = Modifier.weight(1f))
                    DanmakuSettingsSheet(
                        settings = viewModel.danmaku,
                        onDismiss = { showDanmakuSettings = false }
                    )
                }
            }
        }
    }
}

private enum class FullscreenMode { PORTRAIT, LANDSCAPE }

@Composable
private fun MediaLayer(viewModel: PlayerViewModel) {
    val mode = viewModel.engine.mode.collectAsState().value
    val currentTime = viewModel.engine.currentTimeFlow.collectAsState().value
    when (mode) {
        PlaybackMode.SLIDESHOW -> {
            val segment = viewModel.engine.currentSegment
            if (segment != null) {
                AsyncImage(
                    model = segment.url,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
            if (viewModel.danmaku.enabled) {
                DanmakuView(
                    comments = viewModel.comments.collectAsState().value,
                    currentTime = currentTime,
                    settings = viewModel.danmaku
                )
            }
        }
        PlaybackMode.EXOPLAYER -> {
            val player = viewModel.engine.exoPlayer
            if (player != null) {
                PlayerLayerView(
                    player = player,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black))
            }
            if (viewModel.engine.isBuffering.collectAsState().value) {
                CircularProgressIndicator(
                    modifier = Modifier.fillMaxSize().let { Modifier.wrapContentSize(Alignment.Center) },
                    color = Color.White
                )
            }
            if (viewModel.danmaku.enabled) {
                DanmakuView(
                    comments = viewModel.comments.collectAsState().value,
                    currentTime = currentTime,
                    settings = viewModel.danmaku
                )
            }
        }
        PlaybackMode.IDLE -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                val msg = if (viewModel.engine.isLoading.collectAsState().value) "加载中…"
                else (viewModel.statusMessage.collectAsState().value ?: "点击播放")
                Text(msg, color = Color.White, fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun TopBar(
    title: String,
    episodeNumber: Int,
    episodeCount: Int,
    onBack: () -> Unit,
    onDanmakuSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent))
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = Color.White)
        }
        Text(
            title,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        // 显示集数（紧跟标题）
        if (episodeCount > 1) {
            Text(
                "第 $episodeNumber 集",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 12.sp,
                modifier = Modifier
                    .background(Color.White.copy(alpha = 0.16f), CircleShape)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        IconButton(onClick = onDanmakuSettings) {
            Icon(Icons.Default.Tune, contentDescription = "弹幕设置", tint = Color.White)
        }
    }
}

@Composable
private fun BottomControls(
    viewModel: PlayerViewModel,
    isPlaying: Boolean,
    onEnterPortraitFullscreen: () -> Unit,
    onEnterLandscapeFullscreen: () -> Unit,
    onDanmakuSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 观察播放时间，驱动进度条 / 时间文案刷新（读取 value 才会建立重组依赖）
    @Suppress("UNUSED_VARIABLE")
    val currentTime = viewModel.engine.currentTimeFlow.collectAsState().value
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)))
            )
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        VideoSlider(
            value = viewModel.engine.progress,
            onValueChange = { viewModel.seekToFraction(it) }
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Play/Pause
            IconButton(
                onClick = { viewModel.togglePlay() },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
            // Mute
            val isMuted = viewModel.engine.isMuted.collectAsState().value
            IconButton(
                onClick = { viewModel.engine.setMuted(!isMuted) },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
            // Time
            Text(
                viewModel.timeText,
                color = Color.White,
                fontSize = 11.sp,
                maxLines = 1,
                softWrap = false
            )
            Spacer(modifier = Modifier.weight(1f))
            // Danmaku toggle
            IconButton(
                onClick = {
                    viewModel.danmaku.enabled = !viewModel.danmaku.enabled
                    viewModel.danmaku.save()
                },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    if (viewModel.danmaku.enabled) Icons.Default.ClosedCaption else Icons.Default.ClosedCaptionDisabled,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
            // 弹幕设置
            IconButton(
                onClick = onDanmakuSettings,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Default.Tune,
                    contentDescription = "弹幕设置",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
            // Portrait fullscreen
            IconButton(
                onClick = onEnterPortraitFullscreen,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Default.Fullscreen,
                    contentDescription = "竖屏全屏",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
            // Landscape fullscreen
            IconButton(
                onClick = onEnterLandscapeFullscreen,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Default.FullscreenExit,
                    contentDescription = "横屏全屏",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun EpisodeStrip(
    episodes: List<Episode>,
    currentEp: Int,
    onSelect: (Int) -> Unit
) {
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    // 记录每个集数按钮在行内的 x 偏移，切集时把当前集滚动到可见位置
    val chipPositions = remember { mutableStateMapOf<Int, Int>() }
    val currentX = chipPositions[currentEp]

    LaunchedEffect(currentEp, currentX) {
        val x = currentX ?: return@LaunchedEffect
        val margin = with(density) { 16.dp.roundToPx() }
        scrollState.animateScrollTo(maxOf(0, x - margin))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .background(Theme.Cream)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (ep in episodes) {
            val isSelected = ep.number == currentEp
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(ep.number) },
                label = {
                    Text(
                        ep.displayName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        maxLines = 1,
                        softWrap = false,
                        textAlign = TextAlign.Center
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Theme.Pink,
                    selectedLabelColor = Color.White,
                    containerColor = Color.White,
                    labelColor = Theme.Plum
                ),
                shape = CircleShape,
                modifier = Modifier.onGloballyPositioned { coords ->
                    val x = coords.positionInParent().x.roundToInt()
                    if (chipPositions[ep.number] != x) {
                        chipPositions[ep.number] = x
                    }
                }
            )
        }
    }
}

@Composable
private fun FullscreenPlayerOverlay(
    viewModel: PlayerViewModel,
    isLandscape: Boolean,
    onExit: () -> Unit
) {
    val context = LocalContext.current
    val activity = remember { ScreenInsets.findActivity(context) }

    LaunchedEffect(isLandscape) {
        if (isLandscape) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            // 退出全屏时恢复系统栏，并还原浅色主题的状态栏图标
            activity?.window?.let { window ->
                androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
                    show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                    isAppearanceLightStatusBars = true
                }
            }
        }
    }

    var controlsVisible by remember { mutableStateOf(true) }
    var sidePanel by remember { mutableStateOf<SidePanel?>(null) }

    // 系统栏跟随工具栏联动：显示工具栏时也显示状态栏（浅色图标），隐藏工具栏时进入沉浸
    LaunchedEffect(controlsVisible) {
        activity?.window?.let { window ->
            androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
                systemBarsBehavior =
                    androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (controlsVisible) {
                    show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                } else {
                    hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                }
                // 视频画面偏暗，状态栏用浅色图标
                isAppearanceLightStatusBars = false
            }
        }
    }

    // 状态栏安全区高度
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    // Gesture state
    var dragAxis by remember { mutableStateOf<DragAxis?>(null) }
    var overlaySize by remember { mutableStateOf(IntSize.Zero) }
    var hudBrightness by remember { mutableFloatStateOf(0.5f) }
    var hudVolume by remember { mutableFloatStateOf(1f) }

    // Read initial window brightness
    LaunchedEffect(Unit) {
        activity?.let { act ->
            try {
                val b = act.window.attributes.screenBrightness
                hudBrightness = if (b < 0) 0.5f else b
            } catch (_: Exception) { hudBrightness = 0.5f }
        }
        hudVolume = viewModel.engine.volume.value
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { overlaySize = it }
            .pointerInput(sidePanel) {
                // 侧边面板打开时不响应背景手势，避免点击穿透
                if (sidePanel != null) return@pointerInput
                detectDragGestures(
                    onDragStart = {
                        dragAxis = null
                    },
                    onDragEnd = {
                        dragAxis = null
                    },
                    onDragCancel = {
                        dragAxis = null
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        if (overlaySize.width == 0) return@detectDragGestures

                        // First move: decide axis
                        if (dragAxis == null) {
                            val dx = kotlin.math.abs(dragAmount.x)
                            val dy = kotlin.math.abs(dragAmount.y)
                            if (dx > dy) {
                                dragAxis = DragAxis.SEEK
                            } else if (change.position.x < overlaySize.width / 2f) {
                                dragAxis = DragAxis.BRIGHTNESS
                            } else {
                                dragAxis = DragAxis.VOLUME
                            }
                        }

                        // Apply incremental delta to current value
                        when (dragAxis) {
                            DragAxis.SEEK -> {
                                val delta = dragAmount.x / overlaySize.width
                                val next = (viewModel.engine.progress + delta).coerceIn(0f, 1f)
                                viewModel.seekToFraction(next)
                            }
                            DragAxis.BRIGHTNESS -> {
                                val delta = -dragAmount.y / overlaySize.height * 1.5f
                                val next = (hudBrightness + delta).coerceIn(0.05f, 1f)
                                hudBrightness = next
                                activity?.let { act ->
                                    try {
                                        val lp = act.window.attributes
                                        lp.screenBrightness = next
                                        act.window.attributes = lp
                                    } catch (_: Exception) {}
                                }
                            }
                            DragAxis.VOLUME -> {
                                val delta = -dragAmount.y / overlaySize.height
                                val next = (hudVolume + delta).coerceIn(0f, 1f)
                                hudVolume = next
                                viewModel.engine.setVolume(next)
                            }
                            null -> {}
                        }
                    }
                )
            }
    ) {
        // Media layer
        FullscreenMediaLayer(viewModel)

        // Tap to toggle controls, double-tap to play/pause (below controls so buttons stay clickable)
        if (dragAxis == null && sidePanel == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { controlsVisible = !controlsVisible },
                            onDoubleTap = { viewModel.togglePlay() }
                        )
                    }
            )
        }

        // HUD
        dragAxis?.let { axis ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                when (axis) {
                    DragAxis.SEEK -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                viewModel.timeText,
                                color = Color.White,
                                fontSize = 30.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { viewModel.engine.progress },
                                modifier = Modifier.width(160.dp),
                                color = Theme.Pink,
                                trackColor = Color.White.copy(alpha = 0.3f)
                            )
                        }
                    }
                    DragAxis.BRIGHTNESS -> {
                        HUDBox {
                            Icon(Icons.Default.BrightnessHigh, contentDescription = null, tint = Color(0xFFFFD700))
                            Spacer(modifier = Modifier.height(10.dp))
                            LinearProgressIndicator(
                                progress = { hudBrightness },
                                modifier = Modifier.width(150.dp),
                                color = Color(0xFFFFD700),
                                trackColor = Color.White.copy(alpha = 0.3f)
                            )
                            Text(
                                "亮度 ${(hudBrightness * 100).toInt()}%",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    DragAxis.VOLUME -> {
                        HUDBox {
                            Icon(
                                if (hudVolume == 0f) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                                contentDescription = null,
                                tint = Color.White
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            LinearProgressIndicator(
                                progress = { hudVolume },
                                modifier = Modifier.width(150.dp),
                                color = Color.White,
                                trackColor = Color.White.copy(alpha = 0.3f)
                            )
                            Text(
                                "音量 ${(hudVolume * 100).toInt()}%",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // Controls (auto-hide after 4s)
        if (controlsVisible && dragAxis == null) {
            LaunchedEffect(controlsVisible) {
                kotlinx.coroutines.delay(4000)
                controlsVisible = false
            }

            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent))
                    )
                    .padding(horizontal = 16.dp)
                    .padding(top = statusBarTop + 8.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                IconButton(onClick = onExit) {
                    Icon(
                        if (isLandscape) Icons.Default.ArrowBack else Icons.Default.ArrowDropDown,
                        contentDescription = "退出全屏",
                        tint = Color.White
                    )
                }
                Text(
                    viewModel.title.collectAsState().value,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                // 显示集数（紧跟标题）
                if (viewModel.episodes.collectAsState().value.size > 1) {
                    Text(
                        "第 ${viewModel.currentEpisode.collectAsState().value} 集",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 12.sp,
                        modifier = Modifier
                            .background(Color.White.copy(alpha = 0.16f), CircleShape)
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
            }

            // Bottom controls
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)))
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                // 观察播放时间，驱动进度条 / 时间文案刷新（读取 value 才会建立重组依赖）
                @Suppress("UNUSED_VARIABLE")
                val currentTime = viewModel.engine.currentTimeFlow.collectAsState().value
                VideoSlider(
                    value = viewModel.engine.progress,
                    onValueChange = { viewModel.seekToFraction(it) }
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isPlaying = viewModel.engine.isPlaying.collectAsState().value
                    IconButton(onClick = { viewModel.togglePlay() }) {
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    val isMuted = viewModel.engine.isMuted.collectAsState().value
                    IconButton(onClick = { viewModel.engine.setMuted(!isMuted) }) {
                        Icon(
                            if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Text(
                        viewModel.timeText,
                        color = Color.White,
                        fontSize = 12.sp
                    )
                    // 右侧按钮组：横向可滚动，确保倍速/选集在竖屏也能显示
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End
                    ) {
                        IconButton(onClick = {
                            viewModel.danmaku.enabled = !viewModel.danmaku.enabled
                            viewModel.danmaku.save()
                        }) {
                            Icon(
                                if (viewModel.danmaku.enabled) Icons.Default.ClosedCaption else Icons.Default.ClosedCaptionDisabled,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        // 弹幕设置
                        IconButton(onClick = { sidePanel = SidePanel.SETTINGS }) {
                            Icon(Icons.Default.Tune, contentDescription = "弹幕设置", tint = Color.White, modifier = Modifier.size(24.dp))
                        }
                        // 倍速
                        TextButton(onClick = { sidePanel = SidePanel.SPEED }) {
                            Text("倍速", color = Color.White, fontSize = 12.sp)
                        }
                        // 选集
                        TextButton(onClick = { sidePanel = SidePanel.EPISODES }) {
                            Text("选集", color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Side panel overlay
        sidePanel?.let { panel ->
            // 半透明遮罩，点击关闭面板
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable { sidePanel = null }
            )
            // 面板锚定在右侧，自身消费点击避免穿透到背景
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
            ) {
                when (panel) {
                    SidePanel.EPISODES -> FullscreenEpisodePanel(viewModel) { sidePanel = null }
                    SidePanel.SETTINGS -> FullscreenSettingsPanel(viewModel) { sidePanel = null }
                    SidePanel.SPEED -> FullscreenSpeedPanel(viewModel) { sidePanel = null }
                }
            }
        }
    }
}

private enum class DragAxis { SEEK, BRIGHTNESS, VOLUME }

private enum class SidePanel { EPISODES, SETTINGS, SPEED }

@Composable
private fun FullscreenMediaLayer(viewModel: PlayerViewModel) {
    val mode = viewModel.engine.mode.collectAsState().value
    val currentTime = viewModel.engine.currentTimeFlow.collectAsState().value
    // 全屏播放器铺满整屏（含状态栏区域），弹幕需要避开顶部安全区
    val statusBarTopDp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding().value
    when (mode) {
        PlaybackMode.EXOPLAYER -> {
            val player = viewModel.engine.exoPlayer
            if (player != null) {
                PlayerLayerView(
                    player = player,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black))
            }
            if (viewModel.engine.isBuffering.collectAsState().value) {
                CircularProgressIndicator(
                    modifier = Modifier.fillMaxSize().let { Modifier.wrapContentSize(Alignment.Center) },
                    color = Color.White
                )
            }
            if (viewModel.danmaku.enabled) {
                DanmakuView(
                    comments = viewModel.comments.collectAsState().value,
                    currentTime = currentTime,
                    settings = viewModel.danmaku,
                    topInsetDp = statusBarTopDp
                )
            }
        }
        PlaybackMode.SLIDESHOW -> {
            val segment = viewModel.engine.currentSegment
            if (segment != null) {
                AsyncImage(
                    model = segment.url,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
            if (viewModel.danmaku.enabled) {
                DanmakuView(
                    comments = viewModel.comments.collectAsState().value,
                    currentTime = currentTime,
                    settings = viewModel.danmaku,
                    topInsetDp = statusBarTopDp
                )
            }
        }
        PlaybackMode.IDLE -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("加载中…", color = Color.White)
            }
        }
    }
}

@Composable
private fun HUDBox(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.Black.copy(alpha = 0.6f),
        modifier = Modifier.widthIn(min = 160.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content
        )
    }
}

@Composable
private fun FullscreenEpisodePanel(viewModel: PlayerViewModel, onClose: () -> Unit) {
    val episodes = viewModel.episodes.collectAsState().value
    val currentEp = viewModel.currentEpisode.collectAsState().value

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(300.dp)
            .background(Color(0xFF1C1C1E))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("选集", color = Color.White, fontWeight = FontWeight.Bold)
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = null, tint = Color.White)
            }
        }
        val chunkSize = 4
        val listState = rememberLazyListState()
        val currentPos = episodes.indexOfFirst { it.number == currentEp }
        LaunchedEffect(currentPos) {
            if (currentPos >= 0) {
                listState.animateScrollToItem(currentPos / chunkSize)
            }
        }
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            for (chunkStart in episodes.indices step chunkSize) {
                val chunkEnd = minOf(chunkStart + chunkSize, episodes.size)
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        for (i in chunkStart until chunkEnd) {
                            val ep = episodes[i]
                            val isSelected = ep.number == currentEp
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    viewModel.selectEpisode(ep.number)
                                    onClose()
                                },
                                label = {
                                    Text(
                                        ep.displayName,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        softWrap = false,
                                        textAlign = TextAlign.Center,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Theme.Pink,
                                    selectedLabelColor = Color.White,
                                    containerColor = Color.White,
                                    labelColor = Theme.Plum
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        repeat(chunkSize - (chunkEnd - chunkStart)) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FullscreenSettingsPanel(viewModel: PlayerViewModel, onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(320.dp)
            .background(Color.Black)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("弹幕设置", color = Color.White, fontWeight = FontWeight.Bold)
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = null, tint = Color.White)
            }
        }
        DanmakuSettingsSheet(settings = viewModel.danmaku, onDismiss = onClose)
    }
}

@Composable
private fun FullscreenSpeedPanel(viewModel: PlayerViewModel, onClose: () -> Unit) {
    val currentSpeed = viewModel.speed.collectAsState().value

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(220.dp)
            .background(Color(0xFF1C1C1E))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("倍速", color = Color.White, fontWeight = FontWeight.Bold)
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = null, tint = Color.White)
            }
        }
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            for (speed in listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0)) {
                val isSelected = currentSpeed == speed
                Button(
                    onClick = {
                        viewModel.setSpeed(speed)
                        onClose()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White.copy(alpha = if (isSelected) 0.16f else 0.06f)
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "%.2fx".format(speed),
                            color = if (isSelected) Theme.Pink else Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        if (isSelected) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = Theme.Pink)
                        }
                    }
                }
            }
        }
    }
}