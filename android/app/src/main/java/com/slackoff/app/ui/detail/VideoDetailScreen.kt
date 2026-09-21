package com.slackoff.app.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.slackoff.app.model.Episode
import com.slackoff.app.model.VideoDetail
import com.slackoff.app.network.ApiResult
import com.slackoff.app.network.VideoService
import com.slackoff.app.support.DetailCache
import com.slackoff.app.support.EpisodeStore
import com.slackoff.app.ui.theme.Theme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoDetailScreen(
    slug: String,
    title: String,
    onNavigateToPlayer: ((slug: String, episode: Int, title: String, episodes: List<Episode>) -> Unit)? = null,
    onBack: (() -> Unit)? = null
) {
    var detail by remember { mutableStateOf(DetailCache.get(slug)) }
    var isLoading by remember { mutableStateOf(false) }
    var selectedEpisode by remember { mutableIntStateOf(EpisodeStore.selectedEpisode(slug) ?: 1) }

    // 从播放页返回时，把播放器里切换过的集数同步回选中状态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(slug) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                EpisodeStore.selectedEpisode(slug)?.let { selectedEpisode = it }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 从播放页返回时不再重新请求、不刷掉当前详情
    LaunchedEffect(slug) {
        if (detail == null) {
            isLoading = true
            when (val result = VideoService.detail(slug)) {
                is ApiResult.Success -> {
                    detail = result.data
                    result.data?.let { DetailCache.put(slug, it) }
                    val first = result.data?.episodes?.firstOrNull()
                    if (first != null) {
                        selectedEpisode = EpisodeStore.selectedEpisode(slug) ?: first.number
                    }
                }
                is ApiResult.Error -> { /* error */ }
            }
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    TextButton(onClick = { onBack?.invoke() }) {
                        Text("← 返回", color = Theme.Pink)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Theme.Cream
                )
            )
        },
        containerColor = Theme.Cream
    ) { padding ->
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Theme.Pink)
            }
        } else {
            val d = detail
            if (d != null) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(vertical = 16.dp)
                ) {
                    // Header
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            AsyncImage(
                                model = d.coverUrl,
                                contentDescription = d.title,
                                modifier = Modifier
                                    .width(116.dp)
                                    .height(174.dp)
                                    .clip(RoundedCornerShape(12.dp)),
                                contentScale = ContentScale.Crop
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    d.title ?: title,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Theme.Plum
                                )
                                d.score?.let { score ->
                                    Text(
                                        "评分 $score",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = androidx.compose.ui.graphics.Color.White,
                                        modifier = Modifier
                                            .background(Theme.Peach, RoundedCornerShape(50))
                                            .padding(horizontal = 9.dp, vertical = 4.dp)
                                    )
                                }
                                d.typeName?.let { InfoLine("类型", it) }
                                d.area?.let { InfoLine("地区", it) }
                                d.release?.let { InfoLine("年份", it) }
                                d.episodeCount?.let { count ->
                                    Text("全 $count 集", fontSize = 12.sp, color = androidx.compose.ui.graphics.Color.Gray)
                                }
                            }
                        }
                    }

                    // Description
                    d.description?.let { desc ->
                        if (desc.isNotEmpty()) {
                            item {
                                Card(
                                    shape = RoundedCornerShape(14.dp),
                                    colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White)
                                ) {
                                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("简介", fontWeight = FontWeight.Bold, color = Theme.Plum)
                                        Text(desc, fontSize = 14.sp, color = androidx.compose.ui.graphics.Color.Gray, lineHeight = 20.sp)
                                        d.actor?.let { actor ->
                                            if (actor.isNotEmpty()) {
                                                Text("主演: $actor", fontSize = 12.sp, color = androidx.compose.ui.graphics.Color.Gray)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Episodes header
                    item {
                        Text("选集", fontWeight = FontWeight.Bold, color = Theme.Plum)
                    }

                    // Episodes: single horizontal scrollable row
                    val episodes = d.episodes ?: emptyList()
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            for (episode in episodes) {
                                val isSelected = episode.number == selectedEpisode
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        selectedEpisode = episode.number
                                        EpisodeStore.remember(episode.number, slug)
                                        onNavigateToPlayer?.invoke(
                                            slug,
                                            episode.number,
                                            d.title ?: title,
                                            episodes
                                        )
                                    },
                                    label = {
                                        Text(
                                            episode.displayName,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Theme.Pink,
                                        selectedLabelColor = androidx.compose.ui.graphics.Color.White,
                                        containerColor = androidx.compose.ui.graphics.Color.White,
                                        labelColor = Theme.Plum
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        borderColor = if (isSelected) Theme.Pink else androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.08f),
                                        selectedBorderColor = Theme.Pink,
                                        enabled = true,
                                        selected = isSelected
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Text(
        "$label · $value",
        fontSize = 12.sp,
        color = androidx.compose.ui.graphics.Color.Gray,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}