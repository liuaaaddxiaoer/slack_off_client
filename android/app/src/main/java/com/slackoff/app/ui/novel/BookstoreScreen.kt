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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slackoff.app.model.NovelBook
import com.slackoff.app.model.NovelCategory
import com.slackoff.app.model.NovelHomePage
import com.slackoff.app.model.NovelRankBoard
import com.slackoff.app.network.ApiResult
import com.slackoff.app.network.NovelService
import com.slackoff.app.support.NovelSettingsStore
import com.slackoff.app.ui.theme.Theme
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 书城：搜索胶囊 → 金刚区分类 → Banner 轮播 → 强力推荐 → 排行榜 → 分类精选 → 最近更新。
 *
 * 首屏只打两个请求（/api/home + /api/categories），因为服务端上游并发只有 8，
 * 一次并发五六个请求会把回源打满、整体变慢；排行榜等滚进可视区再拉。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookstoreScreen(onOpenShelf: () -> Unit) {
    val navigator = LocalNovelNavigator.current
    val settings by NovelSettingsStore.settings.collectAsState()

    var home by remember { mutableStateOf<NovelHomePage?>(null) }
    var categories by remember { mutableStateOf<List<NovelCategory>>(emptyList()) }
    var ranks by remember { mutableStateOf<List<NovelRankBoard>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var activeSource by remember { mutableStateOf(settings.lastActiveSource) }

    suspend fun load(force: Boolean) {
        if (!force && home != null) return
        if (force) isRefreshing = true else isLoading = true
        errorMessage = null

        val listSource = settings.sourceChoice
        // 分类字典的响应不带 source，slug 又跨源不通用，所以先用上次命中的活跃源
        val categoriesSource = if (listSource == "auto") activeSource else listSource

        coroutineScope {
            val homeDeferred = async { NovelService.home(listSource) }
            val categoriesDeferred = async { NovelService.categories(categoriesSource) }

            when (val homeResult = homeDeferred.await()) {
                is ApiResult.Success -> {
                    home = homeResult.data
                    activeSource = homeResult.data.source
                    NovelSettingsStore.setLastActiveSource(homeResult.data.source)
                }
                is ApiResult.Error -> {
                    errorMessage = homeResult.message
                    if (force) isRefreshing = false else isLoading = false
                    return@coroutineScope
                }
            }

            when (val categoriesResult = categoriesDeferred.await()) {
                is ApiResult.Success -> categories = categoriesResult.data
                // 分类字典失败不阻塞首屏，金刚区只剩「排行 / 全本」两个入口
                is ApiResult.Error -> {}
            }
        }

        // slug 跨源不通用：若 home 命中的源和刚才请求分类用的源不一致，必须重拉，
        // 否则点进分类会拿另一个源的 slug 去查，直接 404。
        if (activeSource != categoriesSource) {
            when (val refreshed = NovelService.categories(activeSource)) {
                is ApiResult.Success -> categories = refreshed.data
                is ApiResult.Error -> {}
            }
            ranks = emptyList()
        }

        if (force) isRefreshing = false else isLoading = false
    }

    // 首次进入加载一次；之后用户切换数据源时强制重载
    var hasLoadedOnce by remember { mutableStateOf(false) }
    LaunchedEffect(settings.sourceChoice) {
        load(force = hasLoadedOnce)
        hasLoadedOnce = true
    }

    // 排行榜懒加载：滚到该区块才请求（由下方 item 的 LaunchedEffect 触发）
    suspend fun loadRanks() {
        if (ranks.isNotEmpty() || home == null) return
        when (val result = NovelService.ranks(source = activeSource)) {
            is ApiResult.Success -> ranks = result.data
            is ApiResult.Error -> ranks = emptyList()
        }
    }

    val scope = rememberCoroutineScope()
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = { scope.launch { load(force = true) } },
        modifier = Modifier
            .fillMaxSize()
            .background(Theme.Cream),
    ) {
        if (isLoading && home == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(color = Theme.Pink, modifier = Modifier.size(28.dp))
                    Text("正在回源加载…", fontSize = 12.sp, color = Theme.Plum.copy(alpha = 0.6f))
                }
            }
        } else if (errorMessage != null && home == null) {
            NovelStateView(
                message = errorMessage ?: "加载失败",
                isError = true,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            BookstoreList(
                home = home,
                categories = categories,
                ranks = ranks,
                activeSource = activeSource,
                onLoadRanks = { loadRanks() },
            )
        }
    }
}

@Composable
private fun BookstoreList(
    home: NovelHomePage?,
    categories: List<NovelCategory>,
    ranks: List<NovelRankBoard>,
    activeSource: String,
    onLoadRanks: suspend () -> Unit,
) {
    val navigator = LocalNovelNavigator.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item { SearchCapsule(onClick = navigator.openSearch) }

        item {
            QuickEntryGrid(
                categories = categories,
                activeSource = activeSource,
            )
        }

        if (!home?.hot.isNullOrEmpty()) {
            item { BannerCarousel(books = home?.hot ?: emptyList(), siteName = home?.siteName) }
        }

        if (!home?.recommend.isNullOrEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    NovelSectionHeader("强力推荐")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(home?.recommend ?: emptyList(), key = { it.key }) { book ->
                            NovelBookCard(book = book, modifier = Modifier.width(96.dp))
                        }
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                NovelSectionHeader("排行榜")
                LaunchedEffect(Unit) { onLoadRanks() }
                if (ranks.isEmpty()) {
                    Text(
                        "正在抓 8 个榜单…",
                        fontSize = 12.sp,
                        color = Theme.Plum.copy(alpha = 0.5f),
                        modifier = Modifier.padding(vertical = 18.dp),
                    )
                } else {
                    RanksInlineBlock(ranks = ranks, activeSource = activeSource)
                }
            }
        }

        (home?.blocks ?: emptyList()).forEach { block ->
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    NovelSectionHeader(block.name)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(block.items, key = { it.key }) { book ->
                            NovelBookCard(book = book, modifier = Modifier.width(96.dp))
                        }
                    }
                    SeeAllButton {
                        // 首页分类块的 name（「玄幻奇幻」）与分类字典的 name（「玄幻」）不完全一致，
                        // 先精确匹配再做包含匹配；都匹配不上就退化成块内本地列表。
                        val matched = categories.firstOrNull { it.name == block.name }
                            ?: categories.firstOrNull { block.name.contains(it.name) || it.name.contains(block.name) }
                        if (matched != null) {
                            navigator.openCategory(matched.slug, block.name, activeSource)
                        } else {
                            navigator.openLocalList(block.name, block.items)
                        }
                    }
                }
            }
        }

        if (!home?.latest.isNullOrEmpty()) {
            item {
                val latest = home?.latest ?: emptyList()
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    NovelSectionHeader("最近更新", subtitle = "${latest.size} 本")
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White)
                            .border(1.dp, Theme.Hairline, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp)
                    ) {
                        latest.take(15).forEachIndexed { index, book ->
                            NovelBookRow(book = book)
                            if (index < latest.take(15).lastIndex) {
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
                    if (latest.size > 15) {
                        SeeAllButton { navigator.openLocalList("最近更新", latest) }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun SearchCapsule(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(Color.White)
            .border(1.dp, Theme.Hairline, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            tint = Theme.Plum.copy(alpha = 0.45f),
            modifier = Modifier.size(15.dp),
        )
        Text(
            "搜索书名 / 作者",
            fontSize = 13.5.sp,
            color = Theme.Plum.copy(alpha = 0.45f),
            modifier = Modifier.weight(1f),
        )
        Text(
            "搜索",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Theme.Pink)
                .padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun QuickEntryGrid(categories: List<NovelCategory>, activeSource: String) {
    val navigator = LocalNovelNavigator.current
    val tints = listOf(Theme.Pink, Theme.Peach, Theme.Lavender, Theme.Mint, Theme.Pink, Theme.Peach, Theme.Lavender)

    data class Entry(val id: String, val title: String, val iconKey: String, val tint: Color, val onClick: () -> Unit)

    val entries = buildList {
        categories.take(7).forEachIndexed { index, category ->
            add(
                Entry(
                    id = "cat-${category.slug}",
                    title = category.name,
                    iconKey = categoryIconKey(category.name, category.slug),
                    tint = tints[index % tints.size],
                    onClick = { navigator.openCategory(category.slug, category.name, activeSource) },
                )
            )
        }
        add(Entry("ranks", "排行", "ranks", Theme.Peach) { navigator.openRanks(activeSource) })
        add(Entry("full", "全本", "full", Theme.Mint) { navigator.openFull(activeSource) })
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        entries.chunked(5).forEach { rowEntries ->
            Row(modifier = Modifier.fillMaxWidth()) {
                rowEntries.forEach { entry ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(onClick = entry.onClick),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        NovelCircleIcon(iconKey = entry.iconKey, tint = entry.tint)
                        Text(
                            text = entry.title,
                            fontSize = 10.5.sp,
                            color = Theme.Plum,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // 不满一行时补空位，保证每个格子等宽
                repeat(5 - rowEntries.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun BannerCarousel(books: List<NovelBook>, siteName: String?) {
    val pagerState = rememberPagerState(pageCount = { books.size })

    // 5s 自动轮播，用户手滑后由下一轮接管
    LaunchedEffect(pagerState, books.size) {
        if (books.size <= 1) return@LaunchedEffect
        while (true) {
            delay(5000)
            val next = (pagerState.currentPage + 1) % books.size
            pagerState.animateScrollToPage(next)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        NovelSectionHeader("今日推荐", subtitle = siteName)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
        ) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                BannerCard(books[page])
            }
            // 页码指示点
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                books.forEachIndexed { index, _ ->
                    Box(
                        modifier = Modifier
                            .size(if (pagerState.currentPage == index) 6.dp else 5.dp)
                            .clip(CircleShape)
                            .background(
                                if (pagerState.currentPage == index) Theme.Pink
                                else Theme.Plum.copy(alpha = 0.25f)
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun BannerCard(book: NovelBook) {
    val onClick = rememberBookClick(book)
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White)
            .border(1.dp, Theme.Hairline, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NovelTextCover(book = book, modifier = Modifier.width(78.dp), cornerRadius = 8.dp, showAuthor = false, titleScale = 0.17f)

        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = book.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Theme.Plum,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!book.author.isNullOrEmpty()) {
                Text(book.author, fontSize = 11.5.sp, color = Theme.Plum.copy(alpha = 0.55f), maxLines = 1)
            }
            Text(
                text = book.intro ?: "源站没给这本书写简介",
                fontSize = 11.5.sp,
                color = Theme.Plum.copy(alpha = 0.55f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 16.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "立即阅读",
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Theme.Pink)
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun RanksInlineBlock(ranks: List<NovelRankBoard>, activeSource: String) {
    var boardIndex by remember { mutableIntStateOf(0) }
    val navigator = LocalNovelNavigator.current

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(ranks, key = { _, board -> board.board }) { index, board ->
                val selected = boardIndex == index
                Text(
                    text = board.board,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) Color.White else Theme.Plum,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) Theme.Pink else Color.White)
                        .border(1.dp, if (selected) Color.Transparent else Theme.Hairline, RoundedCornerShape(50))
                        .clickable { boardIndex = index }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        val board = ranks.getOrElse(boardIndex) { ranks.first() }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White)
                .border(1.dp, Theme.Hairline, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp)
        ) {
            board.entries.take(10).forEachIndexed { index, entry ->
                NovelRankRow(source = activeSource, entry = entry)
                if (index < board.entries.take(10).lastIndex) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 37.dp)
                            .height(0.5.dp)
                            .background(Theme.Hairline)
                    )
                }
            }
        }

        SeeAllButton { navigator.openRanks(activeSource) }
    }
}

@Composable
private fun SeeAllButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(Color.White)
            .border(1.dp, Theme.Hairline, RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("查看全部", fontSize = 12.sp, color = Theme.Plum.copy(alpha = 0.55f))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = Theme.Plum.copy(alpha = 0.55f),
            modifier = Modifier.size(13.dp),
        )
    }
}
