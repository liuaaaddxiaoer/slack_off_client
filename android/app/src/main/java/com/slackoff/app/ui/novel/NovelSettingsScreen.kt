package com.slackoff.app.ui.novel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slackoff.app.model.NovelCacheStats
import com.slackoff.app.model.NovelSourceInfo
import com.slackoff.app.network.ApiResult
import com.slackoff.app.network.NovelService
import com.slackoff.app.support.BookshelfStore
import com.slackoff.app.support.NovelSettingsStore
import com.slackoff.app.ui.theme.Theme
import kotlinx.coroutines.launch

/** 小说频道设置：服务地址、数据源、阅读器默认排版、书架管理、服务状态。 */
@Composable
fun NovelSettingsScreen(onOpenCacheList: () -> Unit = {}) {
    val settings by NovelSettingsStore.settings.collectAsState()
    val shelfBooks by BookshelfStore.books.collectAsState()
    val scope = rememberCoroutineScope()

    var hostText by remember { mutableStateOf(settings.apiHost) }
    var portText by remember { mutableStateOf(settings.apiPort.toString()) }
    var sources by remember { mutableStateOf<List<NovelSourceInfo>>(emptyList()) }
    var stats by remember { mutableStateOf<NovelCacheStats?>(null) }
    var testState by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }

    suspend fun refresh() {
        when (val result = NovelService.sources()) {
            is ApiResult.Success -> sources = result.data
            is ApiResult.Error -> {}
        }
        when (val result = NovelService.cacheStats()) {
            is ApiResult.Success -> stats = result.data
            is ApiResult.Error -> {}
        }
    }

    LaunchedEffect(Unit) { refresh() }

    fun applyAddress() {
        NovelSettingsStore.setApiHost(hostText.trim())
        NovelSettingsStore.setApiPort(portText.toIntOrNull() ?: 4321)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Theme.Cream)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsCard("小说服务地址") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("主机", fontSize = 13.sp, color = Theme.Plum, modifier = Modifier.width(46.dp))
                    OutlinedTextField(
                        value = hostText,
                        onValueChange = { hostText = it },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("端口", fontSize = 13.sp, color = Theme.Plum, modifier = Modifier.width(46.dp))
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it.filter(Char::isDigit) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "保存并测试连接",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Theme.Pink,
                        modifier = Modifier.clickable {
                            applyAddress()
                            testing = true
                            testState = null
                            scope.launch {
                                val started = System.currentTimeMillis()
                                when (val result = NovelService.ping()) {
                                    is ApiResult.Success ->
                                        testState = if (result.data.ok == true) "${System.currentTimeMillis() - started}ms" else "异常"
                                    is ApiResult.Error -> testState = result.message
                                }
                                testing = false
                                refresh()
                            }
                        },
                    )
                    Spacer(Modifier.weight(1f))
                    if (testing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Theme.Pink)
                    } else if (testState != null) {
                        val ok = testState?.endsWith("ms") == true
                        Text(
                            text = testState ?: "",
                            fontSize = 12.sp,
                            color = if (ok) Theme.Mint else Color(0xFFD32F2F),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                SettingsFootnote("模拟器填 127.0.0.1 即可；真机的 127.0.0.1 是手机自己，必须填电脑在同一 Wi-Fi 下的局域网 IP（服务默认监听 0.0.0.0）。首次回源要实时抓源站，慢则十几秒属正常。")
            }
        }

        SettingsCard("数据源") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SourceRow(
                    id = "auto",
                    name = "自动（推荐）",
                    note = "按健康度与优先级自动故障转移",
                    selected = settings.sourceChoice == "auto",
                    onClick = { NovelSettingsStore.setSourceChoice("auto") },
                )
                sources.forEach { source ->
                    SourceRow(
                        id = source.id,
                        name = source.name,
                        note = source.notes,
                        capabilities = source.capabilities,
                        selected = settings.sourceChoice == source.id,
                        onClick = { NovelSettingsStore.setSourceChoice(source.id) },
                    )
                }
                if (sources.isEmpty()) {
                    Text("拉不到源清单（服务未连通）", fontSize = 12.sp, color = Theme.Plum.copy(alpha = 0.5f))
                }
                SettingsFootnote("book_id 与分类 slug 都是源站私有、跨源不通用，所以列表页会用首页命中的那个源；这里的选择只影响书城首屏从哪个源开始。")
            }
        }

        SettingsCard("阅读器默认") {
            ReaderSettingsPanel(modifier = Modifier.fillMaxWidth())
            SettingsFootnote("阅读时也能在底部菜单的设置面板里临时调整，两处是同一份配置。")
        }

        SettingsCard("缓存管理") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenCacheList)
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("已缓存书籍", fontSize = 14.sp, color = Theme.Plum)
                Spacer(Modifier.weight(1f))
                Text("查看与管理 ›", fontSize = 12.sp, color = Theme.Pink)
            }
        }

        SettingsCard("书架") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row {
                    Text("书架藏书", fontSize = 14.sp, color = Theme.Plum)
                    Spacer(Modifier.weight(1f))
                    Text("${shelfBooks.size} 本", fontSize = 14.sp, color = Theme.Plum.copy(alpha = 0.55f))
                }
                Text(
                    text = "清空书架与阅读进度",
                    fontSize = 14.sp,
                    color = if (shelfBooks.isEmpty()) Theme.Plum.copy(alpha = 0.3f) else Color(0xFFD32F2F),
                    modifier = Modifier.clickable(enabled = shelfBooks.isNotEmpty()) { showClearConfirm = true },
                )
            }
        }

        SettingsCard("服务状态") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                stats?.let {
                    StatusLine("缓存条目", "${it.cache?.entries ?: 0}")
                    StatusLine("命中 / 未命中", "${it.cache?.hits ?: 0} / ${it.cache?.misses ?: 0}")
                    it.fetcher?.proxyMode?.let { mode -> StatusLine("代理模式", mode) }
                } ?: Text("拉不到统计", fontSize = 12.sp, color = Theme.Plum.copy(alpha = 0.5f))
                StatusLine("接口文档", "${settings.baseUrl}/docs")
                SettingsFootnote("「未命中」一直涨而缓存条目为 0，通常说明服务进程没配代理（XS_PROXY），回源全部失败。")
            }
        }

        NovelSettingsStore.lastLoadError?.let { error ->
            SettingsCard("配置恢复异常") {
                Text(error, fontSize = 12.sp, color = Color(0xFFD32F2F))
            }
        }

        Spacer(Modifier.height(8.dp))
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            confirmButton = {
                TextButton(onClick = {
                    BookshelfStore.removeAll()
                    showClearConfirm = false
                }) { Text("清空", color = Color(0xFFD32F2F)) }
            },
            dismissButton = { TextButton(onClick = { showClearConfirm = false }) { Text("取消") } },
            title = { Text("清空书架？") },
            text = { Text("会同时删除所有书的阅读进度，无法恢复。") },
        )
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White)
            .border(1.dp, Theme.Hairline, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Theme.Pink)
        content()
    }
}

@Composable
private fun SettingsFootnote(text: String) {
    Text(text, fontSize = 11.sp, color = Theme.Plum.copy(alpha = 0.45f), lineHeight = 16.sp)
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, fontSize = 12.5.sp, color = Theme.Plum.copy(alpha = 0.65f))
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            fontSize = 12.sp,
            color = Theme.Plum,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SourceRow(
    id: String,
    name: String,
    note: String?,
    selected: Boolean,
    capabilities: Map<String, Boolean>? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .border(
                    if (selected) 5.dp else 1.5.dp,
                    if (selected) Theme.Pink else Theme.Plum.copy(alpha = 0.3f),
                    CircleShape,
                )
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(name, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = Theme.Plum)
                Text(
                    text = id,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = Theme.Plum.copy(alpha = 0.5f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Theme.Hairline)
                        .padding(horizontal = 5.dp, vertical = 1.5.dp),
                )
            }
            if (!note.isNullOrEmpty()) {
                Text(note, fontSize = 11.sp, color = Theme.Plum.copy(alpha = 0.5f), lineHeight = 15.sp)
            }
            if (capabilities != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    CapabilityBadge("搜索", capabilities["search"] ?: true)
                    CapabilityBadge("排行", capabilities["ranks"] ?: true)
                    CapabilityBadge("全本", capabilities["full_books"] ?: true)
                }
            }
        }
    }
}

@Composable
private fun CapabilityBadge(text: String, supported: Boolean) {
    Text(
        text = if (supported) text else "无$text",
        fontSize = 9.5.sp,
        fontWeight = FontWeight.Medium,
        color = if (supported) Theme.Mint else Theme.Plum.copy(alpha = 0.45f),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (supported) Theme.Mint.copy(alpha = 0.16f) else Theme.Hairline)
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}
