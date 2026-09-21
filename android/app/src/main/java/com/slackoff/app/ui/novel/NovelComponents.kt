package com.slackoff.app.ui.novel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.HistoryEdu
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slackoff.app.model.NovelBook
import com.slackoff.app.model.NovelRankEntry
import com.slackoff.app.ui.theme.Theme

/**
 * 小说频道的导航回调集合。用 CompositionLocal 下发，避免每层组件都手传 lambda
 * （书城 → 区块 → 卡片 → 详情页 → 阅读器，链路很深）。
 */
data class NovelNavigator(
    val openBook: (source: String, bookId: String, title: String, author: String?, category: String?) -> Unit,
    val openReader: (
        source: String,
        bookId: String,
        title: String,
        author: String?,
        category: String?,
        chapterId: String?,
    ) -> Unit,
    val openCategory: (slug: String, name: String, source: String) -> Unit,
    val openRanks: (source: String) -> Unit,
    val openFull: (source: String) -> Unit,
    val openLocalList: (title: String, books: List<NovelBook>) -> Unit,
    val openSearch: () -> Unit,
)

val LocalNovelNavigator = staticCompositionLocalOf<NovelNavigator> {
    error("NovelNavigator not provided")
}

/** 点书就进详情，是书城/书架/榜单/搜索结果共用的入口。 */
@Composable
fun rememberBookClick(book: NovelBook): () -> Unit {
    val navigator = LocalNovelNavigator.current
    return { navigator.openBook(book.source, book.bookId, book.title, book.author, book.category) }
}

// MARK: - 分类图标

/**
 * 分类图标：两个源的 slug 命名完全不同（bqg99 是 `xuanhuan`，biquge365 是数字 `1~8`），
 * 所以按**中文名关键字**匹配，换源也不会掉成一堆同样的图标。
 * 返回 Material Icons 的名字，由 [categoryIcon] 映射到具体图标。
 */
fun categoryIconKey(name: String, slug: String = ""): String {
    val text = name + slug
    return when {
        text.contains("玄幻") || text.contains("奇幻") || text.contains("xuanhuan") -> "fantasy"
        text.contains("武侠") || text.contains("仙侠") || text.contains("修真") -> "wuxia"
        text.contains("都市") || text.contains("言情") || text.contains("现实") -> "city"
        text.contains("历史") || text.contains("军事") -> "history"
        text.contains("网游") || text.contains("游戏") || text.contains("动漫") -> "game"
        text.contains("科幻") || text.contains("灵异") || text.contains("恐怖") -> "scifi"
        text.contains("女生") || text.contains("现言") || text.contains("古言") -> "girl"
        text.contains("悬疑") || text.contains("推理") -> "mystery"
        else -> "books"
    }
}

// MARK: - 区块标题

@Composable
fun NovelSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(14.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Theme.Pink)
        )
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Theme.Plum)
        if (subtitle != null) {
            Text(subtitle, fontSize = 11.sp, color = Theme.Plum.copy(alpha = 0.5f))
        }
    }
}

// MARK: - 书籍卡片 / 行

/** 竖向书籍卡片：文字封面 + 书名（2 行）+ 作者。书城横向流与网格共用。 */
@Composable
fun NovelBookCard(
    book: NovelBook,
    modifier: Modifier = Modifier,
    showAuthor: Boolean = true,
) {
    val onClick = rememberBookClick(book)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        NovelTextCover(book = book, cornerRadius = 7.dp)
        Text(
            text = book.title,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            color = Theme.Plum,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val secondary = book.author?.takeIf { it.isNotEmpty() } ?: book.category.orEmpty()
        if (showAuthor && secondary.isNotEmpty()) {
            Text(
                text = secondary,
                fontSize = 10.5.sp,
                color = Theme.Plum.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 横向书籍行：小封面 + 书名 + 作者/分类 + 最新章节 + 更新时间。 */
@Composable
fun NovelBookRow(
    book: NovelBook,
    modifier: Modifier = Modifier,
    showLatest: Boolean = true,
) {
    val onClick = rememberBookClick(book)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        NovelTextCover(
            book = book,
            modifier = Modifier.width(56.dp),
            cornerRadius = 5.dp,
            showAuthor = false,
            titleScale = 0.16f,
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = book.title,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = Theme.Plum,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!book.author.isNullOrEmpty()) {
                    Text(
                        text = book.author,
                        fontSize = 11.sp,
                        color = Theme.Plum.copy(alpha = 0.55f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                if (!book.category.isNullOrEmpty()) {
                    Text(
                        text = book.category,
                        fontSize = 10.sp,
                        color = Theme.Pink,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Theme.Pink.copy(alpha = 0.14f))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                        maxLines = 1,
                    )
                }
            }

            val detail = if (showLatest && !book.latestChapter.isNullOrEmpty()) {
                "最新：${book.latestChapter}"
            } else {
                book.intro.orEmpty()
            }
            if (detail.isNotEmpty()) {
                Text(
                    text = detail,
                    fontSize = 11.sp,
                    color = Theme.Plum.copy(alpha = 0.55f),
                    maxLines = if (showLatest) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!book.updateTime.isNullOrEmpty()) {
                Text(
                    text = book.updateTime,
                    fontSize = 10.sp,
                    color = Theme.Plum.copy(alpha = 0.35f),
                )
            }
        }

        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = Theme.Plum.copy(alpha = 0.25f),
            modifier = Modifier.size(16.dp),
        )
    }
}

/** 排行榜条目：前 3 名序号用品牌粉高亮。 */
@Composable
fun NovelRankRow(
    source: String,
    entry: NovelRankEntry,
    modifier: Modifier = Modifier,
) {
    val navigator = LocalNovelNavigator.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable {
                navigator.openBook(source, entry.bookId, entry.title, entry.author, entry.category)
            }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${entry.rank ?: 0}",
            fontSize = 15.sp,
            fontWeight = FontWeight.Black,
            color = if ((entry.rank ?: 99) <= 3) Theme.Pink else Theme.Plum.copy(alpha = 0.4f),
            modifier = Modifier.width(26.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = entry.title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Theme.Plum,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val secondary = entry.author?.takeIf { it.isNotEmpty() } ?: entry.category.orEmpty()
            if (secondary.isNotEmpty()) {
                Text(secondary, fontSize = 11.sp, color = Theme.Plum.copy(alpha = 0.55f), maxLines = 1)
            }
        }

        if (!entry.category.isNullOrEmpty() && !entry.author.isNullOrEmpty()) {
            Text(
                text = entry.category,
                fontSize = 10.sp,
                color = Theme.Plum.copy(alpha = 0.55f),
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Theme.Hairline)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

// MARK: - 空态 / 错误态

/** 统一的空态 / 错误态视图（书城各区块与列表页共用）。 */
@Composable
fun NovelStateView(
    message: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    retryLabel: String = "重试",
    onRetry: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 34.dp, horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = Theme.Plum.copy(alpha = if (isError) 0.45f else 0.3f),
            modifier = Modifier.size(30.dp),
        )
        Text(
            text = message,
            fontSize = 13.sp,
            color = Theme.Plum.copy(alpha = 0.6f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (onRetry != null) {
            Button(
                onClick = onRetry,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = Theme.Pink),
            ) {
                Text(retryLabel, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** 圆底图标（金刚区用）。 */
@Composable
fun NovelCircleIcon(
    iconKey: String,
    tint: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = novelIconVector(iconKey),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * iconKey → Material Icons。
 * 只用 material-icons-extended 里稳定存在的图标，避免为了小说频道再挑一套字体图标。
 */
fun novelIconVector(key: String): androidx.compose.ui.graphics.vector.ImageVector = when (key) {
    "fantasy" -> Icons.Filled.AutoAwesome
    "wuxia" -> Icons.Filled.Gavel
    "city" -> Icons.Filled.LocationCity
    "history" -> Icons.Filled.HistoryEdu
    "game" -> Icons.Filled.SportsEsports
    "scifi" -> Icons.Filled.RocketLaunch
    "girl" -> Icons.Filled.Favorite
    "mystery" -> Icons.Filled.Search
    "ranks" -> Icons.Filled.EmojiEvents
    "full" -> Icons.Filled.Verified
    else -> Icons.AutoMirrored.Filled.MenuBook
}
