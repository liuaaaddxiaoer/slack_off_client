package com.slackoff.app.support

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 阅读背景纸色。深灰 / 夜黑两档下正文自动转浅色。 */
enum class NovelPaper(
    val label: String,
    val background: Color,
    val text: Color,
    val secondaryText: Color,
    val isDark: Boolean,
) {
    WHITE("白", Color(0xFFFFFFFF), Color(0xFF212126), Color(0x8C212126), false),
    CREAM("米黄", Color(0xFFF5EEDF), Color(0xFF403529), Color(0x8C403529), false),
    GREEN("护眼绿", Color(0xFFCCE8CF), Color(0xFF293D2E), Color(0x8C293D2E), false),
    GRAY("深灰", Color(0xFF3A3A3A), Color(0xFFD1D1D6), Color(0x8CD1D1D6), true),
    NIGHT("夜黑", Color(0xFF14121A), Color(0xFF9E9EAE), Color(0x8C9E9EAE), true),
}

/** 行距三档：行高 = 字号 × multiplier。 */
enum class NovelLineSpacing(val label: String, val multiplier: Float) {
    COMPACT("紧凑", 1.2f),
    STANDARD("标准", 1.5f),
    LOOSE("宽松", 1.8f),
}

/** 翻页方式。 */
enum class NovelPagingMode(val label: String) {
    /** 仿真翻页（drawBitmapMesh 卷曲） */
    CURL("仿真"),

    /** 覆盖滑动（HorizontalPager） */
    COVER("覆盖"),

    /** 上下滚动（LazyColumn） */
    SCROLL("滚动"),
}

@Serializable
data class NovelSettingsData(
    val apiHost: String = "127.0.0.1",
    val apiPort: Int = 4321,
    val sourceChoice: String = "auto",
    /**
     * 上次 /api/home 命中的源。分类字典与排行榜的响应里不带 source，
     * 而 slug / book_id 跨源不通用，所以要在拿到 home 之前就能并发请求它们。
     */
    val lastActiveSource: String = "bqg99",
    val fontSize: Float = 18f,
    val lineSpacing: NovelLineSpacing = NovelLineSpacing.STANDARD,
    val paper: NovelPaper = NovelPaper.CREAM,
    val pagingMode: NovelPagingMode = NovelPagingMode.SCROLL,
    /** null = 不干预系统亮度。 */
    val brightness: Float? = null,
) {
    val baseUrl: String get() = makeBaseUrl(apiHost, apiPort)
    val isNightMode: Boolean get() = paper == NovelPaper.NIGHT

    companion object {
        val FONT_SIZE_RANGE = 14f..30f

        /**
         * 纯字符串实现（不依赖 okhttp），便于 JVM 单测。
         * host 允许用户直接粘 `http://192.168.1.5:4321` 这种带 scheme 的形式。
         */
        fun makeBaseUrl(host: String, port: Int): String {
            val trimmed = host.trim()
            if (trimmed.contains("://")) {
                val scheme = trimmed.substringBefore("://").ifEmpty { "http" }
                val authority = trimmed.substringAfter("://").substringBefore("/")
                val hostPart = authority.substringBefore(":").ifEmpty { "127.0.0.1" }
                val portPart = authority.substringAfter(":", "").toIntOrNull() ?: port
                return "$scheme://$hostPart:$portPart"
            }
            val bare = trimmed.ifEmpty { "127.0.0.1" }
            return "http://$bare:$port"
        }
    }
}

/**
 * 小说频道全局设置（服务地址 / 数据源 / 阅读器排版与外观），SharedPreferences 持久化。
 * 与 iOS 的 NovelSettings 对齐；不引入 DataStore，项目现有存储都是 SharedPreferences。
 */
object NovelSettingsStore {
    private const val PREFS_NAME = "com.slackoff.novel-reader-settings"
    private const val KEY = "snapshot"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = true
    }

    private var prefs: SharedPreferences? = null
    private val _settings = MutableStateFlow(NovelSettingsData())
    val settings: StateFlow<NovelSettingsData> = _settings.asStateFlow()

    /** 最近一次从磁盘恢复失败的原因，设置页排障用。 */
    var lastLoadError: String? = null
        private set

    /** 当前服务基址，供 NovelService 拼 URL。 */
    val base: String get() = _settings.value.baseUrl

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        val raw = p.getString(KEY, null) ?: return
        _settings.value = decode(raw)
    }

    /** 用旧版配置也能解出来：任何未知字段忽略，缺失字段回落默认值。 */
    internal fun decode(raw: String): NovelSettingsData {
        return try {
            json.decodeFromString(NovelSettingsData.serializer(), raw)
        } catch (e: Exception) {
            lastLoadError = e.message
            NovelSettingsData()
        }
    }

    fun update(transform: (NovelSettingsData) -> NovelSettingsData) {
        val next = transform(_settings.value)
        _settings.value = next
        persist(next)
    }

    private fun persist(data: NovelSettingsData) {
        val p = prefs ?: return
        val encoded = try {
            json.encodeToString(NovelSettingsData.serializer(), data)
        } catch (e: Exception) {
            return
        }
        p.edit().putString(KEY, encoded).apply()
    }

    // 便捷写入
    fun setApiHost(value: String) = update { it.copy(apiHost = value) }
    fun setApiPort(value: Int) = update { it.copy(apiPort = value) }
    fun setSourceChoice(value: String) = update { it.copy(sourceChoice = value) }
    fun setLastActiveSource(value: String) = update { it.copy(lastActiveSource = value) }
    fun setFontSize(value: Float) =
        update { it.copy(fontSize = value.coerceIn(NovelSettingsData.FONT_SIZE_RANGE)) }

    fun setLineSpacing(value: NovelLineSpacing) = update { it.copy(lineSpacing = value) }
    fun setPaper(value: NovelPaper) = update { it.copy(paper = value) }
    fun setPagingMode(value: NovelPagingMode) = update { it.copy(pagingMode = value) }
    fun setBrightness(value: Float?) = update { it.copy(brightness = value) }
    fun toggleNightMode() =
        update {
            it.copy(paper = if (it.isNightMode) NovelPaper.CREAM else NovelPaper.NIGHT)
        }
}
