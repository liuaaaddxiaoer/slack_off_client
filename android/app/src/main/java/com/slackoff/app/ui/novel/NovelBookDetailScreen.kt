package com.slackoff.app.ui.novel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slackoff.app.model.NovelBookDetail
import com.slackoff.app.model.NovelChapterItem
import com.slackoff.app.network.ApiResult
import com.slackoff.app.network.NovelService
import com.slackoff.app.support.BookshelfStore
import com.slackoff.app.support.NovelCacheDownloadManager
import com.slackoff.app.support.NovelCacheDownloadState
import com.slackoff.app.support.NovelChapterCache
import com.slackoff.app.ui.theme.Theme
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** 书籍详情：文字封面 + 元信息 + 简介折叠 + 完整目录 + 底部固定操作栏。 */
@Composable
fun NovelBookDetailScreen(
    source: String,
    bookId: String,
    title: String,
    author: String?,
    category: String?,
) {
    val navigator = LocalNovelNavigator.current
    val shelfBooks by BookshelfStore.books.collectAsState()

    var detail by remember { mutableStateOf<NovelBookDetail?>(null) }
    var chapters by remember { mutableStateOf<List<NovelChapterItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var introExpanded by remember { mutableStateOf(false) }
    var reversedCatalog by remember { mutableStateOf(false) }
    val cacheStates by NovelCacheDownloadManager.states.collectAsState()
    val cacheState = cacheStates[com.slackoff.app.model.novelKey(source, bookId)] ?: NovelCacheDownloadState()
    // 目录渐进展示：《牧神记》1920 章、《百炼飞升录》8684 章，
    // 一次全渲染会让首帧卡顿，先给 60 条，再按需 +300。
    var visibleChapterCount by remember { mutableIntStateOf(60) }

    suspend fun load() {
        isLoading = true
        errorMessage = null
        coroutineScope {
            val detailDeferred = async { NovelService.bookDetail(source, bookId) }
            val chaptersDeferred = async { NovelService.chapters(source, bookId) }
            when (val result = detailDeferred.await()) {
                is ApiResult.Success -> detail = result.data
                is ApiResult.Error -> errorMessage = result.message
            }
            when (val result = chaptersDeferred.await()) {
                is ApiResult.Success -> {
                    chapters = result.data.items
                    NovelChapterCache.putCatalog(source, bookId, result.data)
                }
                is ApiResult.Error -> if (detail == null) errorMessage = result.message
            }
        }
        NovelCacheDownloadManager.refresh(source, bookId, chapters.size)
        isLoading = false
    }

    LaunchedEffect(source, bookId) { load() }

    val shelfKey = com.slackoff.app.model.novelKey(source, bookId)
    val shelfBook = shelfBooks.firstOrNull { it.key == shelfKey }
    val inShelf = shelfBook != null

    fun entryChapterId(fromShelf: Boolean): String? {
        if (fromShelf && !shelfBook?.lastChapterId.isNullOrEmpty()) return shelfBook?.lastChapterId
        return detail?.firstChapterId ?: chapters.firstOrNull()?.chapterId
    }

    fun toggleShelf() {
        val current = detail ?: return
        if (inShelf) BookshelfStore.remove(shelfKey) else BookshelfStore.add(current)
    }

    val primaryTitle =
        if ((shelfBook?.lastChapterIndex ?: 0) > 0) "续读 第${shelfBook?.lastChapterIndex}章" else "开始阅读"

    Scaffold(
        containerColor = Theme.Cream,
        bottomBar = {
            if (detail != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White)
                        .navigationBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier
                            .width(76.dp)
                            .clickable { toggleShelf() },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Icon(
                            imageVector = if (inShelf) Icons.Filled.CheckCircle else Icons.Filled.AddCircleOutline,
                            contentDescription = if (inShelf) "已在书架" else "加入书架",
                            tint = if (inShelf) Theme.Mint else Theme.Plum.copy(alpha = 0.8f),
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            if (inShelf) "已在书架" else "加入书架",
                            fontSize = 10.sp,
                            color = if (inShelf) Theme.Mint else Theme.Plum.copy(alpha = 0.8f),
                        )
                    }

                    Button(
                        onClick = {
                            navigator.openReader(
                                source, bookId, detail?.title ?: title,
                                detail?.author ?: author, detail?.category ?: category,
                                entryChapterId(fromShelf = true),
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Theme.Pink),
                    ) {
                        Text(primaryTitle, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White)
                            .border(1.dp, Theme.Hairline, RoundedCornerShape(12.dp))
                            .clickable {
                                navigator.openReader(
                                    source, bookId, detail?.title ?: title,
                                    detail?.author ?: author, detail?.category ?: category,
                                    entryChapterId(fromShelf = false),
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "从第一章",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = Theme.Plum,
                        )
                    }
                }
            }
        },
    ) { padding ->
        when {
            isLoading && detail == null -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(color = Theme.Pink) }

            detail == null -> NovelStateView(
                message = errorMessage ?: "加载失败",
                isError = true,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                onRetry = null,
            )

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item { DetailHeader(detail!!) }
                item { DetailActions(primaryTitle, inShelf, onPrimary = {
                    navigator.openReader(
                        source, bookId, detail?.title ?: title,
                        detail?.author ?: author, detail?.category ?: category,
                        entryChapterId(fromShelf = true),
                    )
                }, onToggleShelf = { toggleShelf() }) }
                item {
                    CacheAllCard(
                        cachedCount = cacheState.cachedCount,
                        totalCount = chapters.size,
                        isCaching = cacheState.isCaching,
                        message = cacheState.message,
                        isPaused = cacheState.isPaused,
                        onCacheAll = {
                            NovelCacheDownloadManager.start(
                                source, bookId, detail?.title ?: title,
                                detail?.author ?: author, detail?.category ?: category, chapters,
                            )
                        },
                        onPause = { NovelCacheDownloadManager.pause(source, bookId) },
                        onResume = {
                            NovelCacheDownloadManager.records.value.firstOrNull {
                                it.source == source && it.bookId == bookId
                            }?.let(NovelCacheDownloadManager::resume)
                        },
                    )
                }
                item { IntroCard(detail!!, introExpanded) { introExpanded = it } }
                item {
                    CatalogSection(
                        chapters = chapters,
                        reversed = reversedCatalog,
                        onToggleReversed = { reversedCatalog = !reversedCatalog },
                        visibleCount = visibleChapterCount,
                        onShowMore = { visibleChapterCount += 300 },
                        currentChapterId = shelfBook?.lastChapterId,
                        onOpenChapter = { chapterId ->
                            navigator.openReader(
                                source, bookId, detail?.title ?: title,
                                detail?.author ?: author, detail?.category ?: category, chapterId,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailHeader(detail: NovelBookDetail) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        NovelTextCover(
            seed = detail.key,
            title = detail.title,
            author = detail.author,
            modifier = Modifier.width(104.dp),
            cornerRadius = 10.dp,
            titleScale = 0.15f,
        )

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = detail.title,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                color = Theme.Plum,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!detail.author.isNullOrEmpty()) {
                Text(detail.author, fontSize = 13.sp, color = Theme.Plum.copy(alpha = 0.6f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                detail.category?.takeIf { it.isNotEmpty() }?.let { DetailTag(it, Theme.Pink) }
                detail.status?.takeIf { it.isNotEmpty() }?.let {
                    DetailTag(it, if (it.contains("完")) Theme.Mint else Theme.Lavender)
                }
                Text(
                    text = detail.source,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = Theme.Plum.copy(alpha = 0.5f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Theme.Hairline)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            detail.wordCountText?.let { MetaLine(it) }
            (detail.chapterCount ?: 0).takeIf { it > 0 }?.let { MetaLine("$it 章") }
            detail.latestChapter?.takeIf { it.isNotEmpty() }?.let { MetaLine("最新 $it") }
            detail.updateTime?.takeIf { it.isNotEmpty() }?.let { MetaLine("更新 $it") }
        }
    }
}

@Composable
private fun DetailTag(text: String, tint: Color) {
    Text(
        text = text,
        fontSize = 10.5.sp,
        fontWeight = FontWeight.Medium,
        color = tint,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(tint.copy(alpha = 0.15f))
            .padding(horizontal = 7.dp, vertical = 2.5.dp),
    )
}

@Composable
private fun MetaLine(text: String) {
    Text(text, fontSize = 11.5.sp, color = Theme.Plum.copy(alpha = 0.55f), maxLines = 1)
}

@Composable
private fun DetailActions(
    primaryTitle: String,
    inShelf: Boolean,
    onPrimary: () -> Unit,
    onToggleShelf: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = onPrimary,
            modifier = Modifier
                .weight(1f)
                .height(46.dp),
            shape = RoundedCornerShape(11.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Theme.Pink),
        ) {
            Text(primaryTitle, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        }

        Column(
            modifier = Modifier
                .width(62.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(Color.White)
                .border(1.dp, Theme.Hairline, RoundedCornerShape(11.dp))
                .clickable(onClick = onToggleShelf)
                .padding(vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Icon(
                imageVector = if (inShelf) Icons.Filled.CheckCircle else Icons.Filled.AddCircleOutline,
                contentDescription = null,
                tint = if (inShelf) Theme.Mint else Theme.Plum.copy(alpha = 0.8f),
                modifier = Modifier.size(18.dp),
            )
            Text(
                if (inShelf) "已加入" else "书架",
                fontSize = 10.sp,
                color = if (inShelf) Theme.Mint else Theme.Plum.copy(alpha = 0.8f),
            )
        }
    }
}

@Composable
private fun CacheAllCard(
    cachedCount: Int,
    totalCount: Int,
    isCaching: Boolean,
    isPaused: Boolean,
    message: String?,
    onCacheAll: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
) {
    val completed = totalCount > 0 && cachedCount >= totalCount
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .border(1.dp, Theme.Hairline, RoundedCornerShape(12.dp))
            .clickable(
                enabled = !completed,
                onClick = when {
                    isCaching -> onPause
                    isPaused -> onResume
                    else -> onCacheAll
                },
            )
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isCaching) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = Theme.Pink)
        } else {
            Icon(
                imageVector = if (completed) Icons.Filled.CheckCircle else Icons.Filled.Download,
                contentDescription = "缓存全书",
                tint = if (completed) Theme.Mint else Theme.Pink,
                modifier = Modifier.size(22.dp),
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = when {
                    completed -> "全书已缓存"
                    isCaching -> "正在缓存 $cachedCount / $totalCount 章（点击暂停）"
                    isPaused -> "缓存已暂停（点击继续）"
                    else -> "缓存全书"
                },
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Theme.Plum,
            )
            Text(
                text = message ?: "已缓存 $cachedCount / $totalCount 章，缓存后可离线阅读",
                fontSize = 11.5.sp,
                color = Theme.Plum.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun IntroCard(detail: NovelBookDetail, expanded: Boolean, onToggle: (Boolean) -> Unit) {
    val text = (detail.intro ?: "源站没有提供简介").trim()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .border(1.dp, Theme.Hairline, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NovelSectionHeader("内容简介")
        Text(
            text = text,
            fontSize = 13.sp,
            color = Theme.Plum.copy(alpha = 0.85f),
            lineHeight = 20.sp,
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (text.length > 60) {
            Text(
                text = if (expanded) "收起" else "展开全部",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Theme.Pink,
                modifier = Modifier.clickable { onToggle(!expanded) },
            )
        }
    }
}

@Composable
private fun CatalogSection(
    chapters: List<NovelChapterItem>,
    reversed: Boolean,
    onToggleReversed: () -> Unit,
    visibleCount: Int,
    onShowMore: () -> Unit,
    currentChapterId: String?,
    onOpenChapter: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        NovelSectionHeader("目录", subtitle = "共 ${chapters.size} 章")

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = if (reversed) "倒序" else "正序",
                fontSize = 11.5.sp,
                color = Theme.Plum.copy(alpha = 0.8f),
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.White)
                    .border(1.dp, Theme.Hairline, RoundedCornerShape(50))
                    .clickable(onClick = onToggleReversed)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }

        if (chapters.isEmpty()) {
            NovelStateView(message = "目录为空")
        } else {
            val entries = chapters.withIndex().toList()
            val ordered = if (reversed) entries.asReversed() else entries
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White)
                    .border(1.dp, Theme.Hairline, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp)
            ) {
                ordered.take(visibleCount).forEach { entry ->
                    val isCurrent = entry.value.chapterId == currentChapterId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenChapter(entry.value.chapterId) }
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${entry.index + 1}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = Theme.Plum.copy(alpha = 0.35f),
                            modifier = Modifier.width(34.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        )
                        Text(
                            text = entry.value.title,
                            fontSize = 13.5.sp,
                            color = if (isCurrent) Theme.Pink else Theme.Plum.copy(alpha = 0.9f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (isCurrent) {
                            Text(
                                text = "上次",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color = Theme.Pink,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(Theme.Pink.copy(alpha = 0.14f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    if (entry.index < ordered.take(visibleCount).last().index) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 46.dp)
                                .height(0.5.dp)
                                .background(Theme.Hairline)
                        )
                    }
                }
            }

            if (visibleCount < chapters.size) {
                Text(
                    text = "显示更多（还有 ${chapters.size - visibleCount} 章）",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = Theme.Pink,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White)
                        .border(1.dp, Theme.Hairline, RoundedCornerShape(10.dp))
                        .clickable(onClick = onShowMore)
                        .padding(vertical = 11.dp),
                )
            }
        }
    }
}
