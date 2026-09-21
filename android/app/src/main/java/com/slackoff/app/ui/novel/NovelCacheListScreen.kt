package com.slackoff.app.ui.novel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slackoff.app.support.NovelCacheDownloadManager
import com.slackoff.app.support.NovelCacheDownloadState
import com.slackoff.app.ui.theme.Theme

@Composable
fun NovelCacheListScreen(onOpenBook: (String, String, String, String?, String?) -> Unit) {
    val records by NovelCacheDownloadManager.records.collectAsState()
    val states by NovelCacheDownloadManager.states.collectAsState()

    if (records.isEmpty()) {
        NovelStateView(message = "还没有缓存书籍")
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Theme.Cream),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(records, key = { it.key }) { record ->
            val state = states[record.key] ?: NovelCacheDownloadState(
                record.cachedCount, record.totalCount, isPaused = record.isPaused, failedCount = record.failedCount,
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White)
                    .clickable {
                        onOpenBook(record.source, record.bookId, record.title, record.author, record.category)
                    }
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NovelTextCover(
                        seed = record.key,
                        title = record.title,
                        author = record.author,
                        modifier = Modifier.width(64.dp),
                        cornerRadius = 8.dp,
                        titleScale = 0.14f,
                    )
                    Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(record.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Theme.Plum)
                        record.author?.let { Text(it, fontSize = 12.sp, color = Theme.Plum.copy(alpha = 0.55f)) }
                        Text("${state.cachedCount} / ${state.totalCount} 章", fontSize = 12.sp, color = Theme.Pink)
                    }
                    Button(
                        onClick = {
                            if (state.isCaching) NovelCacheDownloadManager.pause(record.source, record.bookId)
                            else NovelCacheDownloadManager.resume(record)
                        },
                        enabled = !state.isComplete,
                        colors = ButtonDefaults.buttonColors(containerColor = if (state.isCaching) Theme.Hairline else Theme.Pink),
                    ) {
                        Text(if (state.isCaching) "暂停" else "继续", color = if (state.isCaching) Theme.Plum else Color.White)
                    }
                }
                LinearProgressIndicator(
                    progress = { if (state.totalCount > 0) state.cachedCount.toFloat() / state.totalCount else 0f },
                    modifier = Modifier.fillMaxWidth(),
                    color = Theme.Pink,
                    trackColor = Theme.Hairline,
                )
                Text(
                    when {
                        state.isComplete -> "已完成"
                        state.isCaching -> "正在缓存"
                        state.isPaused -> "已暂停"
                        else -> "等待继续"
                    },
                    fontSize = 11.sp,
                    color = Theme.Plum.copy(alpha = 0.55f),
                )
            }
        }
    }
}
