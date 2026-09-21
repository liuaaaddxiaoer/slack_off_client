package com.slackoff.app.ui.novel

import android.app.Activity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.slackoff.app.novel.NovelLayout
import com.slackoff.app.novel.NovelReaderViewModel
import com.slackoff.app.novel.PageCurlView
import com.slackoff.app.support.BookshelfStore
import com.slackoff.app.support.NovelPagingMode
import com.slackoff.app.support.NovelPaper
import com.slackoff.app.support.NovelSettingsStore
import com.slackoff.app.support.ScreenInsets
import com.slackoff.app.ui.components.CompactSlider
import com.slackoff.app.ui.theme.Theme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 沉浸式阅读器：全屏纸色 + 三种翻页模式 + 点击唤出的上下菜单 + 目录抽屉 + 设置面板。
 * 与 iOS 端 NovelReaderView.swift 对齐（同一套交互与排版参数）。
 */
@Composable
fun NovelReaderScreen(
    source: String,
    bookId: String,
    title: String,
    author: String?,
    category: String?,
    chapterId: String?,
    onBack: () -> Unit,
) {
    val viewModel: NovelReaderViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    val settings by NovelSettingsStore.settings.collectAsState()
    val context = LocalContext.current
    val activity = remember(context) { ScreenInsets.findActivity(context) }
    val density = LocalDensity.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var menuVisible by remember { mutableStateOf(false) }
    var showCatalog by remember { mutableStateOf(false) }
    var showSettingsPanel by remember { mutableStateOf(false) }
    var showBrightness by remember { mutableStateOf(false) }
    var scrubChapter by remember { mutableFloatStateOf(1f) }
    var isScrubbing by remember { mutableStateOf(false) }

    LaunchedEffect(source, bookId, chapterId) {
        viewModel.start(source, bookId, title, author, category, chapterId)
    }

    // 进阅读器应用亮度设置；退出时不强行恢复（与视频播放器一致）
    LaunchedEffect(settings.brightness, activity) {
        val value = settings.brightness ?: return@LaunchedEffect
        activity?.window?.let { window ->
            val attrs = window.attributes
            attrs.screenBrightness = value.coerceIn(0.05f, 1f)
            window.attributes = attrs
        }
    }

    // 退出 / 切后台立刻落盘，避免被系统杀掉丢进度
    DisposableEffect(Unit) {
        onDispose { viewModel.flushProgress() }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _: LifecycleOwner, event: Lifecycle.Event ->
            if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_PAUSE) {
                viewModel.flushProgress()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 4s 自动隐藏菜单（与播放器控制条一致的手感）
    LaunchedEffect(menuVisible, showSettingsPanel, showBrightness) {
        if (menuVisible && !showSettingsPanel && !showBrightness) {
            delay(4000)
            menuVisible = false
        }
    }

    LaunchedEffect(state.chapterIndex) {
        scrubChapter = (state.chapterIndex + 1).toFloat()
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(settings.paper.background)
    ) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val insets = WindowInsets.safeDrawing.asPaddingValues()
        val safeTopPx = maxOf(
            with(density) { insets.calculateTopPadding().toPx() },
            ScreenInsets.top(activity),
        )
        val safeBottomPx = maxOf(
            with(density) { insets.calculateBottomPadding().toPx() },
            WindowInsets.navigationBars.getBottom(density).toFloat(),
            ScreenInsets.bottom(activity),
        )
        val safeBottomDp = with(density) { safeBottomPx.toDp() }

        LaunchedEffect(widthPx, heightPx, safeTopPx, safeBottomPx) {
            viewModel.updateMetrics(widthPx, heightPx, safeTopPx, safeBottomPx)
        }

        val toggleMenu: () -> Unit = {
            showSettingsPanel = false
            menuVisible = !menuVisible
        }

        when {
            state.errorMessage != null && state.layout == null -> ReaderError(
                message = state.errorMessage ?: "加载失败",
                paper = settings.paper,
                onRetry = { viewModel.retryCurrentChapter() },
                onBack = onBack,
            )

            state.layout == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(color = settings.paper.text, modifier = Modifier.size(26.dp))
                    Text(
                        text = if (state.isLoadingCatalog) "正在加载目录…" else "正在加载章节…",
                        fontSize = 13.sp,
                        color = settings.paper.secondaryText,
                    )
                }
            }

            else -> when (settings.pagingMode) {
                NovelPagingMode.CURL -> CurlReader(state = state, viewModel = viewModel, onCenterTap = toggleMenu)
                NovelPagingMode.COVER -> CoverReader(state = state, viewModel = viewModel, onCenterTap = toggleMenu)
                NovelPagingMode.SCROLL -> ScrollReader(state = state, viewModel = viewModel, onCenterTap = toggleMenu)
            }
        }

        // 章内错误重试条：正文已渲染时不清空内容，只在下方给一条提示
        if (state.errorMessage != null && state.layout != null) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 90.dp, start = 24.dp, end = 24.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = state.errorMessage ?: "",
                    fontSize = 11.sp,
                    color = Color.White,
                    maxLines = 2,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "重试",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier.clickable { viewModel.retryCurrentChapter() },
                )
            }
        }

        if (state.isLoadingChapter && state.layout != null) {
            Text(
                text = "加载章节…",
                fontSize = 12.sp,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 130.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }

        state.edgeHint?.let { hint ->
            Text(
                text = hint,
                fontSize = 13.sp,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 120.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            )
        }

        if (menuVisible) {
            ReaderMenus(
                state = state,
                viewModel = viewModel,
                onBack = onBack,
                onOpenCatalog = {
                    showSettingsPanel = false
                    showCatalog = true
                },
                onOpenSettings = {
                    showCatalog = false
                    showSettingsPanel = !showSettingsPanel
                },
                showBrightness = showBrightness,
                onToggleBrightness = { showBrightness = !showBrightness },
                scrubChapter = scrubChapter,
                onScrubChange = { scrubChapter = it },
                onScrubbingChange = { isScrubbing = it },
                isScrubbing = isScrubbing,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(bottom = safeBottomDp),
            )
        }

        if (showCatalog) {
            ReaderCatalogDrawer(
                state = state,
                onClose = { showCatalog = false },
                onSelect = { index ->
                    showCatalog = false
                    menuVisible = false
                    viewModel.goToChapter(index)
                },
            )
        }

        if (showSettingsPanel) {
            Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                ReaderSettingsPanel()
            }
        }
    }
}

// MARK: - 单页内容

@Composable
fun NovelPageContent(
    layout: NovelLayout,
    pageIndex: Int,
    paper: NovelPaper,
    modifier: Modifier = Modifier,
    bookTitle: String? = null,
    showFooter: Boolean = true,
    onCenterTap: (() -> Unit)? = null,
) {
    val density = LocalDensity.current
    val footerBottomPadding = with(density) {
        (layout.typography.bottomInsetPx - 16.dp.toPx())
            .coerceAtLeast(14.dp.toPx())
            .toDp()
    }
    Box(
        modifier = modifier
            .background(paper.background)
            .then(
                if (onCenterTap != null) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onCenterTap,
                    )
                } else {
                    Modifier
                }
            )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            layout.drawPage(drawContext.canvas.nativeCanvas, pageIndex, paper.text.toArgb())
        }

        if (showFooter) {
            Column(modifier = Modifier.fillMaxSize()) {
                Spacer(Modifier.weight(1f))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, bottom = footerBottomPadding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = layout.chapterTitle,
                        fontSize = 11.sp,
                        color = paper.secondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${pageIndex + 1}/${layout.pageCount}",
                        fontSize = 11.sp,
                        color = paper.secondaryText,
                    )
                }
            }
        }
    }
}

// MARK: - 三种翻页模式

@Composable
private fun CurlReader(
    state: NovelReaderViewModel.UiState,
    viewModel: NovelReaderViewModel,
    onCenterTap: () -> Unit,
) {
    val settings by NovelSettingsStore.settings.collectAsState()
    val layout = state.layout ?: return
    val pageCount = layout.pageCount

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            PageCurlView(context).apply {
                setOnClickListener { onCenterTap() }
                listener = object : PageCurlView.Listener {
                    override fun onTurnPage(delta: Int) {
                        if (delta > 0) viewModel.advance() else viewModel.retreat()
                    }
                }
            }
        },
        update = { view ->
            view.layout = layout
            view.currentIndex = state.currentPage
            view.nextIndex = if (state.currentPage + 1 < pageCount) state.currentPage + 1 else -1
            view.prevIndex = if (state.currentPage > 0) state.currentPage - 1 else -1
            view.textColor = settings.paper.text.toArgb()
            view.paperColor = settings.paper.background.toArgb()
        },
    )
}

@Composable
private fun CoverReader(
    state: NovelReaderViewModel.UiState,
    viewModel: NovelReaderViewModel,
    onCenterTap: () -> Unit,
) {
    val settings by NovelSettingsStore.settings.collectAsState()
    val layout = state.layout ?: return
    val pageCount = layout.pageCount
    val scope = rememberCoroutineScope()

    // 首尾各加一个哨兵页：滑到它就跨章（Pager 只能整页吸附，哨兵也要占满一页宽）
    val pagerState = rememberPagerState(
        initialPage = (state.currentPage + 1).coerceIn(0, (pageCount + 1).coerceAtLeast(1)),
        pageCount = { pageCount + 2 },
    )

    LaunchedEffect(state.body?.chapterId, pageCount) {
        val target = (state.currentPage + 1).coerceIn(0, pageCount + 1)
        if (pagerState.currentPage != target) pagerState.scrollToPage(target)
    }

    LaunchedEffect(pagerState.settledPage) {
        val page = pagerState.settledPage
        when {
            page == 0 -> {
                pagerState.scrollToPage(1)
                viewModel.retreat()
            }
            page >= pageCount + 1 -> {
                pagerState.scrollToPage(pageCount.coerceAtLeast(1))
                viewModel.advance()
            }
            else -> viewModel.goToPage(page - 1)
        }
    }

    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
        when {
            page == 0 -> EdgeSentinel(
                kind = "上一章",
                enabled = state.chapterIndex > 0,
                title = state.chapters.getOrNull(state.chapterIndex - 1)?.title,
                paper = settings.paper,
            )
            page >= pageCount + 1 -> EdgeSentinel(
                kind = "下一章",
                enabled = state.chapterIndex + 1 < state.totalChapters,
                title = state.chapters.getOrNull(state.chapterIndex + 1)?.title,
                paper = settings.paper,
            )
            else -> NovelPageContent(
                layout = layout,
                pageIndex = page - 1,
                paper = settings.paper,
                modifier = Modifier.fillMaxSize(),
                onCenterTap = onCenterTap,
            )
        }
    }
}

@Composable
private fun ScrollReader(
    state: NovelReaderViewModel.UiState,
    viewModel: NovelReaderViewModel,
    onCenterTap: () -> Unit,
) {
    val settings by NovelSettingsStore.settings.collectAsState()
    val layout = state.layout ?: return
    val pageCount = layout.pageCount

    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (state.currentPage + 1).coerceIn(0, pageCount + 1)
    )

    LaunchedEffect(state.body?.chapterId, pageCount) {
        val target = (state.currentPage + 1).coerceIn(0, pageCount + 1)
        if (listState.firstVisibleItemIndex != target) listState.scrollToItem(target)
    }

    LaunchedEffect(listState.firstVisibleItemIndex) {
        val index = listState.firstVisibleItemIndex
        when {
            index == 0 -> viewModel.retreat()
            index >= pageCount + 1 -> viewModel.advance()
            else -> viewModel.goToPage(index - 1)
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val pageHeight = maxHeight
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                EdgeSentinel(
                    kind = "上一章",
                    enabled = state.chapterIndex > 0,
                    title = state.chapters.getOrNull(state.chapterIndex - 1)?.title,
                    paper = settings.paper,
                    compact = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                )
            }
            items(pageCount) { index ->
                NovelPageContent(
                    layout = layout,
                    pageIndex = index,
                    paper = settings.paper,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(pageHeight)
                        .clip(RoundedCornerShape(6.dp)),
                    onCenterTap = onCenterTap,
                )
            }
            item {
                EdgeSentinel(
                    kind = "下一章",
                    enabled = state.chapterIndex + 1 < state.totalChapters,
                    title = state.chapters.getOrNull(state.chapterIndex + 1)?.title,
                    paper = settings.paper,
                    compact = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                )
            }
        }
    }
}

@Composable
private fun EdgeSentinel(
    kind: String,
    enabled: Boolean,
    title: String?,
    paper: NovelPaper,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(paper.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = if (enabled) kind else if (kind == "上一章") "已是第一章" else "已是最后一章",
                fontSize = if (compact) 12.sp else 13.sp,
                color = if (enabled) paper.text else paper.text.copy(alpha = 0.35f),
            )
            if (!compact && !title.isNullOrEmpty()) {
                Text(
                    text = title,
                    fontSize = 12.sp,
                    color = paper.text.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// MARK: - 错误态

@Composable
private fun ReaderError(message: String, paper: NovelPaper, onRetry: () -> Unit, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(paper.background)
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = paper.secondaryText,
            modifier = Modifier.size(34.dp),
        )
        Spacer(Modifier.height(14.dp))
        Text("章节加载失败", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = paper.text)
        Spacer(Modifier.height(8.dp))
        Text(message, fontSize = 12.sp, color = paper.secondaryText, textAlign = TextAlign.Center)
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onBack, colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)) {
                Text("返回", color = paper.text)
            }
            Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = Theme.Pink)) {
                Text("重试", color = Color.White)
            }
        }
    }
}

// MARK: - 上下菜单

@Composable
private fun ReaderMenus(
    state: NovelReaderViewModel.UiState,
    viewModel: NovelReaderViewModel,
    onBack: () -> Unit,
    onOpenCatalog: () -> Unit,
    onOpenSettings: () -> Unit,
    showBrightness: Boolean,
    onToggleBrightness: () -> Unit,
    scrubChapter: Float,
    onScrubChange: (Float) -> Unit,
    onScrubbingChange: (Boolean) -> Unit,
    isScrubbing: Boolean,
    modifier: Modifier = Modifier,
) {
    val settings by NovelSettingsStore.settings.collectAsState()
    val paper = settings.paper
    val context = LocalContext.current
    val activity = remember(context) { ScreenInsets.findActivity(context) }
    val barText = if (paper.isDark) Color.White.copy(alpha = 0.92f) else Color.Black.copy(alpha = 0.82f)
    val barBackground = if (paper.isDark) Color(0xFF242229) else Color(0xFFFFFBFC)
    val scope = rememberCoroutineScope()
    val total = state.totalChapters.coerceAtLeast(1)

    Column(modifier = modifier.fillMaxSize()) {
        // 顶部
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(barBackground)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = barText,
                    modifier = Modifier
                        .size(22.dp)
                        .clickable(onClick = onBack),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.bookTitle,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = barText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = state.body?.title ?: state.chapters.getOrNull(state.chapterIndex)?.title ?: "",
                        fontSize = 11.sp,
                        color = barText.copy(alpha = 0.65f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 源标识：book_id 跨源不通用，让读者知道这本是从哪个源解析的
                Text(
                    text = state.source,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = barText.copy(alpha = 0.8f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(barText.copy(alpha = 0.14f))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }
        }

        Spacer(Modifier.weight(1f))

        // 底部
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(barBackground)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (showBrightness) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Filled.LightMode, contentDescription = null, tint = barText.copy(alpha = 0.7f), modifier = Modifier.size(14.dp))
                    CompactSlider(
                        value = (settings.brightness ?: 0.5f),
                        onValueChange = { value ->
                            NovelSettingsStore.setBrightness(value)
                            activity?.window?.let { window ->
                                val attrs = window.attributes
                                attrs.screenBrightness = value.coerceIn(0.05f, 1f)
                                window.attributes = attrs
                            }
                        },
                        valueRange = 0.05f..1f,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (isScrubbing) {
                    Text(
                        text = "第 ${scrubChapter.toInt()}/$total 章 · ${(state.overallProgress * 100).toInt()}%",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = barText,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .clip(RoundedCornerShape(50))
                            .background(barText.copy(alpha = 0.14f))
                            .padding(horizontal = 9.dp, vertical = 4.dp),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.SkipPrevious,
                        contentDescription = "上一章",
                        tint = barText.copy(alpha = if (state.chapterIndex == 0) 0.35f else 0.85f),
                        modifier = Modifier
                            .size(20.dp)
                            .clickable(enabled = state.chapterIndex > 0) { viewModel.retreat() },
                    )
                    CompactSlider(
                        value = scrubChapter,
                        onValueChange = { onScrubChange(it) },
                        onValueChangeFinished = {
                            onScrubbingChange(false)
                            val target = scrubChapter.toInt() - 1
                            if (target != state.chapterIndex) viewModel.goToChapter(target)
                        },
                        valueRange = 1f..total.toFloat(),
                        // 不设 steps：1920 章会生成 1918 个刻度，滑动时直接卡死；
                        // 连续滑动 + 松手取整跳章，手感也更接近夸克。
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                    )
                    Icon(
                        imageVector = Icons.Filled.SkipNext,
                        contentDescription = "下一章",
                        tint = barText.copy(alpha = if (state.chapterIndex + 1 >= state.totalChapters) 0.35f else 0.85f),
                        modifier = Modifier
                            .size(20.dp)
                            .clickable(enabled = state.chapterIndex + 1 < state.totalChapters) { viewModel.advance() },
                    )
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text("${state.chapterIndex + 1}/$total 章", fontSize = 10.sp, color = barText.copy(alpha = 0.6f))
                    Spacer(Modifier.weight(1f))
                    Text("${(state.overallProgress * 100).toInt()}%", fontSize = 10.sp, color = barText.copy(alpha = 0.6f))
                }
            }

            Row(modifier = Modifier.fillMaxWidth()) {
                MenuButton(Icons.AutoMirrored.Filled.List, "目录", barText, Modifier.weight(1f), onOpenCatalog)
                MenuButton(
                    if (settings.isNightMode) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                    if (settings.isNightMode) "日间" else "夜间",
                    barText,
                    Modifier.weight(1f),
                ) { NovelSettingsStore.toggleNightMode() }
                MenuButton(Icons.Filled.Brightness6, "亮度", barText, Modifier.weight(1f), onToggleBrightness)
                MenuButton(Icons.Filled.FormatSize, "设置", barText, Modifier.weight(1f), onOpenSettings)
            }
        }
    }
}

@Composable
private fun MenuButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(19.dp))
        Text(label, fontSize = 10.sp, color = tint)
    }
}
