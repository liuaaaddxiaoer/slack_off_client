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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slackoff.app.model.NovelBook
import com.slackoff.app.model.NovelHomePage
import com.slackoff.app.network.ApiResult
import com.slackoff.app.network.NovelService
import com.slackoff.app.support.NovelSearchHistory
import com.slackoff.app.support.NovelSettingsStore
import com.slackoff.app.ui.theme.Theme
import kotlinx.coroutines.launch

/**
 * 站内搜索：搜索框 + 历史 + 空态热门推荐 + 分页结果。
 *
 * 只有 bqg99 支持站内搜索（blqvdu / biquge365 源站没有搜索页），
 * 所以这里固定用 `auto`：服务端会自动落到支持搜索的源。
 */
@Composable
fun NovelSearchScreen() {
    val navigator = LocalNovelNavigator.current
    val settings by NovelSettingsStore.settings.collectAsState()
    val history by NovelSearchHistory.terms.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()

    var keyword by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<NovelBook>>(emptyList()) }
    var page by remember { mutableIntStateOf(1) }
    var hasMore by remember { mutableStateOf(false) }
    var isSearching by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var hasSearched by remember { mutableStateOf(false) }
    var resolvedSource by remember { mutableStateOf("") }
    var hotBooks by remember { mutableStateOf<List<NovelBook>>(emptyList()) }

    suspend fun search(query: String, requestPage: Int, reset: Boolean) {
        if (query.isBlank() || isSearching) return
        if (!reset && !hasMore) return
        isSearching = true
        if (reset) {
            errorMessage = null
            keyboard?.hide()
            NovelSearchHistory.remember(query)
        }
        when (val result = NovelService.search(query.trim(), requestPage, "auto")) {
            is ApiResult.Success -> {
                val data = result.data
                results = if (reset) {
                    data.books
                } else {
                    val existing = results.map { it.key }.toSet()
                    results + data.books.filterNot { it.key in existing }
                }
                page = data.page ?: requestPage
                hasMore = data.more
                resolvedSource = data.source
            }
            is ApiResult.Error -> errorMessage = result.message
        }
        hasSearched = true
        isSearching = false
    }

    // 空态推荐直接借首页的封面推荐位，一次请求，失败就留空（不影响搜索本身）
    LaunchedEffect(Unit) {
        if (hotBooks.isEmpty()) {
            when (val result = NovelService.home(settings.sourceChoice)) {
                is ApiResult.Success -> {
                    val home: NovelHomePage = result.data
                    hotBooks = home.hot.ifEmpty { home.recommend }
                    resolvedSource = home.source
                }
                is ApiResult.Error -> {}
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Theme.Cream)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White)
                    .border(1.dp, Theme.Hairline, RoundedCornerShape(50))
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = Theme.Plum.copy(alpha = 0.45f),
                    modifier = Modifier.size(15.dp),
                )
                TextField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    placeholder = { Text("书名或作者", fontSize = 14.sp, color = Theme.Plum.copy(alpha = 0.4f)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { scope.launch { search(keyword, 1, true) } }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
                if (keyword.isNotEmpty()) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "清空",
                        tint = Theme.Plum.copy(alpha = 0.4f),
                        modifier = Modifier
                            .size(16.dp)
                            .clickable { keyword = "" },
                    )
                }
            }

            Text(
                text = if (keyword.isNotBlank()) "搜索" else "返回",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (keyword.isNotBlank()) Theme.Pink else Theme.Plum.copy(alpha = 0.5f),
                modifier = Modifier.clickable {
                    if (keyword.isNotBlank()) scope.launch { search(keyword, 1, true) }
                },
            )
        }

        Box(Modifier.height(0.5.dp).fillMaxWidth().background(Theme.Hairline))

        when {
            isSearching && results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Theme.Pink)
            }

            errorMessage != null && results.isEmpty() -> NovelStateView(
                message = errorMessage ?: "搜索失败",
                isError = true,
                modifier = Modifier.fillMaxSize(),
                onRetry = { scope.launch { search(keyword, 1, true) } },
            )

            hasSearched && results.isEmpty() -> NovelStateView(
                message = "没有找到「$keyword」相关的书",
                modifier = Modifier.fillMaxSize(),
            )

            results.isEmpty() -> IdleSearchState(
                history = history,
                hotBooks = hotBooks,
                onPickHistory = { term ->
                    keyword = term
                    scope.launch { search(term, 1, true) }
                },
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 12.dp),
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(
                            "找到 ${results.size}${if (hasMore) "+" else ""} 本",
                            fontSize = 12.sp,
                            color = Theme.Plum.copy(alpha = 0.55f),
                        )
                        Spacer(Modifier.weight(1f))
                        if (resolvedSource.isNotEmpty()) {
                            Text("源：$resolvedSource", fontSize = 10.5.sp, color = Theme.Plum.copy(alpha = 0.55f))
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
                        results.forEachIndexed { index, book ->
                            NovelBookRow(book = book)
                            if (index < results.lastIndex) {
                                Box(Modifier.fillMaxWidth().padding(start = 67.dp).height(0.5.dp).background(Theme.Hairline))
                            }
                        }
                    }
                }
                item {
                    if (hasMore) {
                        Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Theme.Pink, modifier = Modifier.size(20.dp))
                            LaunchedEffect(page) { search(keyword, page + 1, false) }
                        }
                    } else {
                        Text(
                            "已经到底了",
                            fontSize = 11.sp,
                            color = Theme.Plum.copy(alpha = 0.35f),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IdleSearchState(
    history: List<String>,
    hotBooks: List<NovelBook>,
    onPickHistory: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        if (history.isNotEmpty()) {
            item {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        NovelSectionHeader("搜索历史", modifier = Modifier.weight(1f))
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "清空历史",
                            tint = Theme.Plum.copy(alpha = 0.45f),
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { NovelSearchHistory.clear() },
                        )
                    }
                    // Compose 的 FlowRow 在 1.7 仍是 experimental，用 chunked 折行足够稳
                    history.chunked(4).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { term ->
                                Text(
                                    text = term,
                                    fontSize = 12.5.sp,
                                    color = Theme.Plum,
                                    maxLines = 1,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(Color.White)
                                        .border(1.dp, Theme.Hairline, RoundedCornerShape(50))
                                        .clickable { onPickHistory(term) }
                                        .padding(horizontal = 12.dp, vertical = 7.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        if (hotBooks.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    NovelSectionHeader("大家都在看", modifier = Modifier.padding(horizontal = 14.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(hotBooks, key = { it.key }) { book ->
                            NovelBookCard(book = book, modifier = Modifier.width(96.dp))
                        }
                    }
                }
            }
        }

        if (hotBooks.isEmpty()) {
            item {
                Text(
                    text = "输入书名或作者开始搜索\n只有顶点小说网（bqg99）提供站内搜索",
                    fontSize = 12.sp,
                    color = Theme.Plum.copy(alpha = 0.5f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 30.dp),
                )
            }
        }
    }
}
