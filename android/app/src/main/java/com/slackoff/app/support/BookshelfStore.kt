package com.slackoff.app.support

import android.content.Context
import android.content.SharedPreferences
import com.slackoff.app.model.NovelBook
import com.slackoff.app.model.NovelBookDetail
import com.slackoff.app.model.novelKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 书架上的一本书 + 精确阅读进度（章节 id / 章节序号 / 章内位置 / 全书百分比）。
 * 章内位置让「继续阅读」能回到上次那一页，而不只是那一章。
 */
@Serializable
data class ShelfBook(
    val source: String,
    val bookId: String,
    val title: String,
    val author: String? = null,
    val category: String? = null,
    val lastChapterId: String = "",
    val lastChapterTitle: String = "",
    /** 1-based 章节序号，与目录里的 index 对齐 */
    val lastChapterIndex: Int = 0,
    /** 目录总章数，用于算全书百分比；未知时为 0 */
    val totalChapters: Int = 0,
    /** 章内位置 0~1 */
    val positionInChapter: Double = 0.0,
    val updatedAt: Long = 0L,
) {
    val key: String get() = novelKey(source, bookId)

    /** 全书进度 0~1；总章数未知时退化为章内位置。 */
    val percent: Double
        get() {
            if (totalChapters <= 0) return positionInChapter
            val done = maxOf(lastChapterIndex - 1, 0).toDouble() + positionInChapter
            return (done / totalChapters).coerceIn(0.0, 1.0)
        }

    // 四舍五入，与 iOS 端 ShelfBook.percentText 保持一致：
    // 截断取整会让 9.95% 显示成 "9%"，两端同一本书进度显示不同。
    val percentText: String get() = "${kotlin.math.round(percent * 100).toInt()}%"

    val progressText: String
        get() =
            if (lastChapterIndex > 0) {
                "读至 第${lastChapterIndex}章"
            } else if (lastChapterTitle.isEmpty()) {
                "尚未开始"
            } else {
                "读至 $lastChapterTitle"
            }
}

/**
 * 书架与阅读进度的本地存储（SharedPreferences + StateFlow）。
 * 与 iOS 的 BookshelfStore 对齐：同一份语义、同一套 key 规则。
 */
object BookshelfStore {
    private const val PREFS_NAME = "com.slackoff.novel-shelf"
    private const val KEY = "books"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = true
    }

    private var prefs: SharedPreferences? = null
    private val _books = MutableStateFlow<List<ShelfBook>>(emptyList())

    /** 已按最后阅读时间倒序。 */
    val books: StateFlow<List<ShelfBook>> = _books.asStateFlow()

    /** 「继续阅读」卡片用：最近读过的那本。 */
    val mostRecent: ShelfBook? get() = _books.value.firstOrNull()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        val raw = p.getString(KEY, null) ?: return
        _books.value = decode(raw)
    }

    internal fun decode(raw: String): List<ShelfBook> {
        return try {
            json.decodeFromString<List<ShelfBook>>(raw).sortedByDescending { it.updatedAt }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun contains(key: String): Boolean = _books.value.any { it.key == key }

    fun contains(source: String, bookId: String): Boolean = contains(novelKey(source, bookId))

    fun book(key: String): ShelfBook? = _books.value.firstOrNull { it.key == key }

    fun book(source: String, bookId: String): ShelfBook? = book(novelKey(source, bookId))

    fun add(book: NovelBook) {
        add(
            source = book.source,
            bookId = book.bookId,
            title = book.title,
            author = book.author,
            category = book.category,
            chapterId = book.latestChapterId.orEmpty(),
            chapterTitle = book.latestChapter.orEmpty(),
            chapterIndex = 0,
        )
    }

    fun add(detail: NovelBookDetail) {
        add(
            source = detail.source,
            bookId = detail.bookId,
            title = detail.title,
            author = detail.author,
            category = detail.category,
            chapterId = detail.entryChapterId.orEmpty(),
            chapterTitle = detail.firstChapter ?: detail.latestChapter.orEmpty(),
            chapterIndex = if (detail.entryChapterId == detail.firstChapterId) 1 else 0,
            totalChapters = detail.chapterCount ?: 0,
        )
    }

    /** 加入书架；已在架则只更新元信息（书名/作者可能被源站修正），不动进度。 */
    fun add(
        source: String,
        bookId: String,
        title: String,
        author: String? = null,
        category: String? = null,
        chapterId: String,
        chapterTitle: String,
        chapterIndex: Int,
        totalChapters: Int = 0,
    ) {
        val key = novelKey(source, bookId)
        val existing = book(key)
        val next =
            existing?.copy(
                title = title,
                author = author ?: existing.author,
                category = category ?: existing.category,
                totalChapters = if (totalChapters > 0) totalChapters else existing.totalChapters,
                updatedAt = System.currentTimeMillis(),
            ) ?: ShelfBook(
                source = source,
                bookId = bookId,
                title = title,
                author = author,
                category = category,
                lastChapterId = chapterId,
                lastChapterTitle = chapterTitle,
                lastChapterIndex = chapterIndex,
                totalChapters = totalChapters,
                updatedAt = System.currentTimeMillis(),
            )
        replace(next)
    }

    /** 更新阅读进度（翻页节流 + 退出阅读器时落盘）。 */
    fun updateProgress(
        source: String,
        bookId: String,
        title: String,
        author: String? = null,
        category: String? = null,
        chapterId: String,
        chapterTitle: String,
        chapterIndex: Int,
        totalChapters: Int,
        positionInChapter: Double,
    ) {
        val key = novelKey(source, bookId)
        val existing = book(key)
        val next =
            existing?.copy(
                title = title,
                author = author ?: existing.author,
                category = category ?: existing.category,
                lastChapterId = chapterId,
                lastChapterTitle = chapterTitle,
                lastChapterIndex = chapterIndex,
                totalChapters = if (totalChapters > 0) totalChapters else existing.totalChapters,
                positionInChapter = positionInChapter.coerceIn(0.0, 1.0),
                updatedAt = System.currentTimeMillis(),
            ) ?: ShelfBook(
                source = source,
                bookId = bookId,
                title = title,
                author = author,
                category = category,
                lastChapterId = chapterId,
                lastChapterTitle = chapterTitle,
                lastChapterIndex = chapterIndex,
                totalChapters = totalChapters,
                positionInChapter = positionInChapter.coerceIn(0.0, 1.0),
                updatedAt = System.currentTimeMillis(),
            )
        replace(next)
    }

    fun remove(key: String) {
        _books.value = _books.value.filterNot { it.key == key }
        persist()
    }

    /** 清空书架（设置页用）。 */
    fun removeAll() {
        _books.value = emptyList()
        persist()
    }

    /** 覆盖同 key 条目并重新按 updatedAt 倒序（保持「最近读过」在最前）。 */
    private fun replace(book: ShelfBook) {
        val current = _books.value.toMutableList()
        val index = current.indexOfFirst { it.key == book.key }
        if (index >= 0) current[index] = book else current.add(book)
        _books.value = current.sortedByDescending { it.updatedAt }
        persist()
    }

    private fun persist() {
        val p = prefs ?: return
        val encoded = try {
            json.encodeToString<List<ShelfBook>>(_books.value)
        } catch (e: Exception) {
            return
        }
        p.edit().putString(KEY, encoded).apply()
    }
}
