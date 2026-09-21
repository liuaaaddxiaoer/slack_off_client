package com.slackoff.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.slackoff.app.model.VideoCard
import com.slackoff.app.network.ApiResult
import com.slackoff.app.network.VideoService
import com.slackoff.app.support.HomeCache
import com.slackoff.app.ui.theme.Theme
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoHomeScreen(
    onNavigateToDetail: ((slug: String, title: String) -> Unit)? = null
) {
    var items by remember { mutableStateOf<List<VideoCard>>(HomeCache.items) }
    var isLoading by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<VideoCard>?>(null) }
    val coroutineScope = rememberCoroutineScope()

    // 只在首次进入时加载；从详情页返回时不再重刷、不再闪 loading
    LaunchedEffect(Unit) {
        if (items.isEmpty()) {
            isLoading = true
            when (val result = VideoService.home()) {
                is ApiResult.Success -> {
                    items = result.data
                    HomeCache.items = result.data
                }
                is ApiResult.Error -> { /* error handled silently */ }
            }
            isLoading = false
        }
    }

    // 执行搜索
    fun runSearch() {
        val query = searchText.trim()
        if (query.isEmpty()) {
            searchResults = null
            return
        }
        isLoading = true
        coroutineScope.launch {
            when (val result = VideoService.search(query)) {
                is ApiResult.Success -> searchResults = result.data
                is ApiResult.Error -> searchResults = emptyList()
            }
            isLoading = false
        }
    }

    val displayItems = searchResults ?: items

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("追剧小铺", fontWeight = FontWeight.Bold, color = Theme.Plum)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Theme.SoftPink.copy(alpha = 0.4f)
                )
            )
        },
        containerColor = Theme.Cream
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            Column {
                // Search bar
                OutlinedTextField(
                    value = searchText,
                    onValueChange = {
                        searchText = it
                        if (it.isEmpty()) searchResults = null
                    },
                    placeholder = { Text("搜索视频") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = androidx.compose.ui.graphics.Color.White,
                        unfocusedContainerColor = androidx.compose.ui.graphics.Color.White
                    ),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                    trailingIcon = {
                        IconButton(onClick = { runSearch() }) {
                            Icon(Icons.Default.Search, contentDescription = "搜索", tint = Theme.Pink)
                        }
                    }
                )

                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(displayItems, key = { it.id }) { card ->
                        VideoCardCell(
                            card = card,
                            onClick = {
                                val slug = card.slug ?: ""
                                val title = card.title ?: ""
                                onNavigateToDetail?.invoke(slug, title)
                            }
                        )
                    }
                }
            }

            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White)
                    ) {
                        Row(
                            modifier = Modifier.padding(20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(color = Theme.Pink, modifier = Modifier.size(24.dp))
                            Text("加载中…", color = Theme.Plum)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun VideoCardCell(
    card: VideoCard,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Box {
                AsyncImage(
                    model = card.coverUrl,
                    contentDescription = card.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(9.dp)),
                    contentScale = ContentScale.Crop
                )
                if (card.rating != null && card.rating.isNotEmpty()) {
                    Text(
                        card.rating,
                        color = androidx.compose.ui.graphics.Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .background(Theme.Peach, RoundedCornerShape(50))
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }
            }
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    card.title ?: "未知",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Theme.Plum,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (card.remark != null && card.remark.isNotEmpty()) {
                    Text(
                        card.remark,
                        fontSize = 12.sp,
                        color = Theme.Pink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}