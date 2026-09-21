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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slackoff.app.model.NovelBook
import com.slackoff.app.model.NovelBookPage
import com.slackoff.app.model.NovelRankBoard
import com.slackoff.app.network.ApiResult
import com.slackoff.app.network.NovelService
import com.slackoff.app.ui.theme.Theme
import kotlinx.coroutines.launch

/** 列表页种类。分类 slug 跨源不通用，所以每种都带上请求用的源。 */
sealed class NovelListKind {
    data class Category(val slug: String, val name: String) : NovelListKind()
    data object Full : NovelListKind()
    data class Search(val keyword: String) : NovelListKind()

    val navigationTitle: String
        get() = when (this) {
            is Category -> name
            Full -> "全本小说"
            is Search -> "“$keyword”"
        }
}

/**
 * 分页书籍列表：分类页 / 全本页 / 搜索结果共用。
 *
 * 翻页由响应的 `has_more` 驱动：bqg99 与 blqvdu 的分类页源站不给翻页（单页固定 30 条，
 * has_more 恒为 false），biquge365 能一路翻到 1769 页，同一套代码自然适配。
 */
@Composable
fun PagedBookListScreen(kind: NovelListKind, source: String) {
    var books by remember { mutableStateOf<List<NovelBook>>(emptyList()) }
    var page by remember { mutableIntStateOf(1) }
    var hasMore by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var resolvedSource by remember { mutableStateOf("") }
    var totalText by remember { mutableStateOf<String?>(null) }

    suspend fun fetch(requestPage: Int, reset: Boolean) {
        if (isLoading) return
        if (!reset && !hasMore) return
        isLoading = true
        if (reset) errorMessage = null

        val result: ApiResult<NovelBookPage> = when (kind) {
            is NovelListKind.Category -> NovelService.categoryBooks(kind.slug, requestPage, source)
            NovelListKind.Full -> NovelService.fullBooks(requestPage, source)
            is NovelListKind.Search -> NovelService.search(kind.keyword, requestPage, source)
        }

        when (result) {
            is ApiResult.Success -> {
                val data = result.data
                if (reset) {
                    books = data.books
                } else {
                    // 源站偶尔把同一本书在不同页重复给出，按 key 去重再追加
                    val existing = books.map { it.key }.toSet()
                    books = books + data.books.filterNot { it.key in existing }
                }
                page = data.page ?: requestPage
                hasMore = data.more
                resolvedSource = data.source
                totalText = when {
                    data.total != null -> "/ ${data.total} 本"
                    data.totalPages != null -> "/ ${data.totalPages} 页"
                    else -> null
                }
            }
            is ApiResult.Error -> errorMessage = result.message
        }
        isLoading = false
    }

    // LaunchedEffect 是 Composable，不能塞进 onRetry 这种普通 lambda，重试走协程作用域
    val retryScope = rememberCoroutineScope()
    LaunchedEffect(kind, source) { fetch(1, reset = true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Theme.Cream)
    ) {
        when {
            isLoading && books.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Theme.Pink, modifier = Modifier.size(28.dp))
            }

            errorMessage != null && books.isEmpty() -> NovelStateView(
                message = errorMessage ?: "加载失败",
                isError = true,
                modifier = Modifier.fillMaxSize(),
                onRetry = { retryScope.launch { fetch(1, true) } },
            )

            books.isEmpty() -> NovelStateView(
                message = "这个列表是空的",
                modifier = Modifier.fillMaxSize(),
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 12.dp),
            ) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "共 ${books.size}${if (hasMore) "+" else ""} 本",
                            fontSize = 12.sp,
                            color = Theme.Plum.copy(alpha = 0.55f),
                        )
                        totalText?.let {
                            Text(it, fontSize = 12.sp, color = Theme.Plum.copy(alpha = 0.55f))
                        }
                        Spacer(Modifier.weight(1f))
                        if (resolvedSource.isNotEmpty()) {
                            Text(
                                resolvedSource,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color = Theme.Plum.copy(alpha = 0.55f),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(Theme.Hairline)
                                    .padding(horizontal = 7.dp, vertical = 3.dp),
                            )
                        }
                    }
                }

                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White)
                            .border(1.dp, Theme.Hairline, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp)
                    ) {
                        books.forEachIndexed { index, book ->
                            NovelBookRow(book = book)
                            if (index < books.lastIndex) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(start = 67.dp)
                                        .height(0.5.dp)
                                        .background(Theme.Hairline)
                                )
                            }
                        }
                    }
                }

                item {
                    if (hasMore) {
                        // 末尾哨兵：滚到就自动拉下一页
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(color = Theme.Pink, modifier = Modifier.size(20.dp))
                            LaunchedEffect(page) { fetch(page + 1, reset = false) }
                        }
                    } else {
                        Text(
                            "已经到底了",
                            fontSize = 11.sp,
                            color = Theme.Plum.copy(alpha = 0.35f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 20.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 排行榜页：横向切榜 + 每榜完整 15 条。
 * /api/ranks 的响应里不带 source，而 book_id 跨源不通用，所以源必须由调用方显式传入。
 */
@Composable
fun RanksScreen(source: String, preloaded: List<NovelRankBoard> = emptyList()) {
    var boards by remember { mutableStateOf(preloaded) }
    var boardIndex by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(preloaded.isEmpty()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val ranksRetryScope = rememberCoroutineScope()
    LaunchedEffect(source) {
        if (boards.isNotEmpty()) return@LaunchedEffect
        when (val result = NovelService.ranks(source = source)) {
            is ApiResult.Success -> boards = result.data
            is ApiResult.Error -> errorMessage = result.message
        }
        isLoading = false
    }

    when {
        isLoading && boards.isEmpty() -> Box(
            Modifier
                .fillMaxSize()
                .background(Theme.Cream),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator(color = Theme.Pink) }

        errorMessage != null && boards.isEmpty() -> NovelStateView(
            message = errorMessage ?: "加载失败",
            isError = true,
            modifier = Modifier
                .fillMaxSize()
                .background(Theme.Cream),
            onRetry = {
                ranksRetryScope.launch {
                    isLoading = true
                    errorMessage = null
                    when (val result = NovelService.ranks(source = source)) {
                        is ApiResult.Success -> boards = result.data
                        is ApiResult.Error -> errorMessage = result.message
                    }
                    isLoading = false
                }
            },
        )

        boards.isEmpty() -> NovelStateView(
            message = "暂无榜单数据",
            modifier = Modifier
                .fillMaxSize()
                .background(Theme.Cream),
        )

        else -> Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Theme.Cream)
        ) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(boards, key = { _, board -> board.board }) { index, board ->
                    val selected = boardIndex == index
                    Text(
                        text = board.board,
                        fontSize = 12.5.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) Color.White else Theme.Plum,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (selected) Theme.Pink else Color.White)
                            .border(1.dp, if (selected) Color.Transparent else Theme.Hairline, RoundedCornerShape(50))
                            .clickable { boardIndex = index }
                            .padding(horizontal = 13.dp, vertical = 7.dp),
                    )
                }
            }

            val board = boards.getOrElse(boardIndex) { boards.first() }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 6.dp),
            ) {
                itemsIndexed(board.entries, key = { _, entry -> entry.key }) { index, entry ->
                    Column {
                        NovelRankRow(
                            source = source,
                            entry = entry,
                            modifier = Modifier.padding(horizontal = 14.dp),
                        )
                        if (index < board.entries.lastIndex) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(start = 51.dp)
                                    .height(0.5.dp)
                                    .background(Theme.Hairline)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 本地列表页：数据已经在手上（首页的最近更新、分类精选块），只做展示，不再请求。 */
@Composable
fun LocalBookListScreen(title: String, books: List<NovelBook>) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Theme.Cream),
        contentPadding = PaddingValues(vertical = 12.dp),
    ) {
        item {
            Text(
                "共 ${books.size} 本",
                fontSize = 12.sp,
                color = Theme.Plum.copy(alpha = 0.55f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White)
                    .border(1.dp, Theme.Hairline, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp)
            ) {
                books.forEachIndexed { index, book ->
                    NovelBookRow(book = book)
                    if (index < books.lastIndex) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 67.dp)
                                .height(0.5.dp)
                                .background(Theme.Hairline)
                        )
                    }
                }
            }
        }
    }
}
