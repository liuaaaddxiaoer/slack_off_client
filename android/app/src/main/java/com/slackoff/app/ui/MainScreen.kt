package com.slackoff.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slackoff.app.ui.home.VideoHomeScreen
import com.slackoff.app.ui.novel.BookshelfScreen
import com.slackoff.app.ui.novel.BookstoreScreen
import com.slackoff.app.ui.novel.LocalNovelNavigator
import com.slackoff.app.ui.novel.NovelHomeScreen
import com.slackoff.app.ui.novel.NovelNavigator
import com.slackoff.app.ui.theme.Theme
import androidx.compose.runtime.CompositionLocalProvider

@Composable
fun MainScreen(
    onNavigateToDetail: (slug: String, title: String) -> Unit = { _, _ -> },
    novelNavigator: NovelNavigator,
    onOpenNovelSettings: () -> Unit = {},
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            Column(modifier = Modifier.navigationBarsPadding()) {
                NavigationBar(
                    modifier = Modifier.height(64.dp),
                    containerColor = Theme.Cream,
                    contentColor = Theme.Pink,
                    windowInsets = WindowInsets(0, 0, 0, 0),
                ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.PlayCircle, contentDescription = "视频") },
                    label = { Text("视频") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Theme.Pink,
                        selectedTextColor = Theme.Pink,
                        indicatorColor = Theme.SoftPink.copy(alpha = 0.4f)
                    )
                )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Default.Book, contentDescription = "小说") },
                        label = { Text("小说") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Theme.Pink,
                            selectedTextColor = Theme.Pink,
                            indicatorColor = Theme.SoftPink.copy(alpha = 0.4f)
                        )
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (selectedTab) {
                0 -> VideoHomeScreen(onNavigateToDetail = onNavigateToDetail)
                1 -> CompositionLocalProvider(LocalNovelNavigator provides novelNavigator) {
                    NovelHomeScreen(onOpenSettings = onOpenNovelSettings) { tab, switchTab ->
                        if (tab == 0) {
                            BookstoreScreen(onOpenShelf = { switchTab(1) })
                        } else {
                            BookshelfScreen(onOpenBookstore = { switchTab(0) })
                        }
                    }
                }
            }
        }
    }
}
