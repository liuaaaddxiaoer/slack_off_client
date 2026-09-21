package com.slackoff.app.ui.novel

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slackoff.app.support.BookshelfStore
import com.slackoff.app.support.ShelfBook
import com.slackoff.app.ui.theme.Theme

/**
 * 书架：顶部「继续阅读」大卡片 + 三列封面网格，按最后阅读时间倒序，长按可移出。
 * 网格用 chunked(3) + Row 而不是嵌套 LazyVerticalGrid，避免与外层 LazyColumn 的滚动冲突。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookshelfScreen(onOpenBookstore: () -> Unit) {
    val books by BookshelfStore.books.collectAsState()
    val navigator = LocalNovelNavigator.current
    var pendingDelete by remember { mutableStateOf<ShelfBook?>(null) }

    if (books.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = null,
                tint = Theme.Pink.copy(alpha = 0.6f),
                modifier = Modifier.size(46.dp),
            )
            Spacer(Modifier.height(14.dp))
            Text("书架还是空的", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Theme.Plum)
            Spacer(Modifier.height(6.dp))
            Text(
                "去书城挑一本，翻开就会自动加入书架",
                fontSize = 12.5.sp,
                color = Theme.Plum.copy(alpha = 0.55f),
            )
            Spacer(Modifier.height(18.dp))
            Text(
                "去逛逛书城",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Theme.Pink)
                    .combinedClickable(onClick = onOpenBookstore)
                    .padding(horizontal = 22.dp, vertical = 10.dp),
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Theme.Cream),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { ContinueReadingCard(books.first()) }

        item { NovelSectionHeader("我的书架", subtitle = "${books.size} 本") }

        books.chunked(3).forEach { rowBooks ->
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    rowBooks.forEach { book ->
                        ShelfCell(
                            book = book,
                            modifier = Modifier.weight(1f),
                            onLongPress = { pendingDelete = book },
                            onClick = {
                                navigator.openReader(
                                    book.source,
                                    book.bookId,
                                    book.title,
                                    book.author,
                                    book.category,
                                    book.lastChapterId.ifEmpty { null },
                                )
                            },
                        )
                    }
                    repeat(3 - rowBooks.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            confirmButton = {
                TextButton(onClick = {
                    BookshelfStore.remove(target.key)
                    pendingDelete = null
                }) {
                    Text("移出书架", color = Theme.Pink)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
            title = { Text("移出书架？") },
            text = { Text("《${target.title}》的阅读进度会一起删除。") },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContinueReadingCard(book: ShelfBook) {
    val navigator = LocalNovelNavigator.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White)
            .border(1.dp, Theme.Hairline, RoundedCornerShape(14.dp))
            .combinedClickable {
                navigator.openReader(
                    book.source,
                    book.bookId,
                    book.title,
                    book.author,
                    book.category,
                    book.lastChapterId.ifEmpty { null },
                )
            }
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NovelTextCover(
            shelf = book,
            modifier = Modifier.width(62.dp),
            cornerRadius = 7.dp,
            showAuthor = false,
            titleScale = 0.16f,
        )

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("继续阅读", fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = Theme.Pink)
            Text(
                text = book.title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Theme.Plum,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = book.lastChapterTitle.ifEmpty { book.progressText },
                fontSize = 11.5.sp,
                color = Theme.Plum.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LinearProgressIndicator(
                    progress = { book.percent.toFloat() },
                    color = Theme.Pink,
                    trackColor = Theme.Hairline,
                    modifier = Modifier
                        .width(120.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                )
                Text(book.percentText, fontSize = 10.5.sp, fontWeight = FontWeight.Medium, color = Theme.Plum.copy(alpha = 0.55f))
            }
        }

        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(50))
                .background(Theme.Pink),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = "继续阅读",
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShelfCell(book: ShelfBook, modifier: Modifier = Modifier, onLongPress: () -> Unit, onClick: () -> Unit) {
    Column(
        modifier = modifier.combinedClickable(onLongClick = onLongPress, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box {
            NovelTextCover(shelf = book, cornerRadius = 7.dp, showAuthor = false, titleScale = 0.15f)
            Text(
                text = book.percentText,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )
        }
        Text(
            text = book.title,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = Theme.Plum,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = book.progressText,
            fontSize = 10.sp,
            color = Theme.Plum.copy(alpha = 0.5f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
