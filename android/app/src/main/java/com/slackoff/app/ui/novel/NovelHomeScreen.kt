package com.slackoff.app.ui.novel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slackoff.app.support.BookshelfStore
import com.slackoff.app.ui.theme.Theme

/**
 * 小说频道入口：顶部分段「书城 / 书架」，替换原来的占位页。
 *
 * 层级刻意做浅 —— 小说本身已经是 App 的一个底部 tab，再嵌一层底部 tab bar 会出现双 tab，
 * 所以用顶部分段控件，这也更接近夸克小说频道的进入体验。
 */
@Composable
fun NovelHomeScreen(
    onOpenSettings: () -> Unit,
    content: @Composable (tab: Int, switchTab: (Int) -> Unit) -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val books by BookshelfStore.books.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Theme.Cream)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Theme.SoftPink.copy(alpha = 0.35f))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SegmentedTabs(
                tab = tab,
                onTabChange = { tab = it },
                modifier = Modifier.width(210.dp),
            )

            Spacer(Modifier.weight(1f))

            if (books.isNotEmpty()) {
                Text(
                    text = "${books.size}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Theme.Plum.copy(alpha = 0.55f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Theme.Hairline)
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }

            IconButton(onClick = onOpenSettings, modifier = Modifier.size(34.dp)) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "小说设置",
                    tint = Theme.Plum.copy(alpha = 0.75f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            content(tab) { tab = it }
        }
    }
}

@Composable
private fun SegmentedTabs(tab: Int, onTabChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val labels = listOf("书城", "书架")
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(Theme.Hairline)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val selected = tab == index
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (selected) Theme.Cream else androidx.compose.ui.graphics.Color.Transparent)
                    .noRippleClickable { onTabChange(index) }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) Theme.Pink else Theme.Plum.copy(alpha = 0.6f),
                )
            }
        }
    }
}

/** 分段控件不需要水波纹，用 indication = null 的 clickable。 */
@Composable
fun Modifier.noRippleClickable(onClick: () -> Unit): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return this.clickable(
        interactionSource = interactionSource,
        indication = null,
        onClick = onClick,
    )
}
