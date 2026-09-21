package com.slackoff.app.ui.novel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.slackoff.app.novel.NovelReaderViewModel
import com.slackoff.app.support.NovelSettingsStore
import com.slackoff.app.ui.theme.Theme
import kotlinx.coroutines.launch

/**
 * 目录抽屉：从右侧滑出的面板，配色跟随当前纸张（日间/夜间），不再固定暗黑。
 *
 * 目录一次拉全（实测《牧神记》1920 章 0.07s），所以这里是纯本地列表，
 * 用 LazyListState 自动定位到当前章，长目录也不需要分页请求。
 */
@Composable
fun ReaderCatalogDrawer(
    state: NovelReaderViewModel.UiState,
    onClose: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    var reversed by remember { mutableStateOf(false) }
    val settings by NovelSettingsStore.settings.collectAsState()
    val paper = settings.paper
    val panelBackground = paper.background
    val primaryText = paper.text
    val secondaryText = paper.secondaryText
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.chapterIndex, reversed) {
        val position = if (reversed) state.totalChapters - 1 - state.chapterIndex else state.chapterIndex
        if (position in 0 until state.totalChapters) {
            listState.scrollToItem(position.coerceAtLeast(0))
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f))
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = onClose,
                )
        )

        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(320.dp)
                .background(panelBackground)
                .navigationBarsPadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("目录", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = primaryText)
                        Text(
                            text = "共 ${state.totalChapters} 章 · ${state.bookTitle}",
                            fontSize = 11.sp,
                            color = secondaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "关闭",
                        tint = secondaryText,
                        modifier = Modifier
                            .size(18.dp)
                            .clickable(onClick = onClose),
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DrawerChip(Icons.Filled.SwapVert, if (reversed) "倒序" else "正序", primaryText) { reversed = !reversed }
                    DrawerChip(Icons.Filled.MyLocation, "当前章", primaryText) {
                        val position = if (reversed) state.totalChapters - 1 - state.chapterIndex else state.chapterIndex
                        // animateScrollToItem 是 suspend，不能在 clickable 里直接调
                        scope.launch { listState.animateScrollToItem(position.coerceAtLeast(0)) }
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${state.chapterIndex + 1}/${state.totalChapters.coerceAtLeast(1)}",
                        fontSize = 11.sp,
                        color = secondaryText,
                    )
                }
            }

            Box(Modifier.fillMaxWidth().height(0.5.dp).background(primaryText.copy(alpha = 0.14f)))

            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                // 倒序只是显示顺序，回传的仍是原始下标
                val indices = if (reversed) {
                    (state.totalChapters - 1 downTo 0).toList()
                } else {
                    (0 until state.totalChapters).toList()
                }
                itemsIndexed(indices, key = { _, original -> original }) { _, originalIndex ->
                    val item = state.chapters.getOrNull(originalIndex) ?: return@itemsIndexed
                    val isCurrent = originalIndex == state.chapterIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (isCurrent) Theme.Pink.copy(alpha = 0.12f) else Color.Transparent)
                            .clickable { onSelect(originalIndex) }
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "${originalIndex + 1}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = secondaryText.copy(alpha = 0.6f),
                            modifier = Modifier.width(36.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        )
                        Text(
                            text = item.title,
                            fontSize = 13.sp,
                            color = if (isCurrent) Theme.Pink else primaryText.copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (isCurrent) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.MenuBook,
                                contentDescription = null,
                                tint = Theme.Pink,
                                modifier = Modifier.size(12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(tint.copy(alpha = 0.10f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint.copy(alpha = 0.85f), modifier = Modifier.size(12.dp))
        Text(text, fontSize = 11.sp, color = tint.copy(alpha = 0.85f))
    }
}
