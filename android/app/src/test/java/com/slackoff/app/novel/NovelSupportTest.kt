package com.slackoff.app.novel

import com.slackoff.app.model.NovelBook
import com.slackoff.app.model.novelKey
import com.slackoff.app.model.parseNovelErrorDetail
import com.slackoff.app.support.ShelfBook
import com.slackoff.app.ui.novel.novelCoverPalette
import com.slackoff.app.ui.novel.novelCoverPaletteColors
import com.slackoff.app.ui.novel.stableCoverHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 封面取色、书架进度计算、错误体解析（都是纯逻辑）。 */
class NovelSupportTest {

    // MARK: 文字封面取色

    @Test
    fun `stable hash is deterministic`() {
        val seed = "bqg99:2639610"
        val first = stableCoverHash(seed)
        repeat(100) { assertEquals(first, stableCoverHash(seed)) }
        assertTrue(first >= 0)
    }

    @Test
    fun `stable hash is fnv1a`() {
        // FNV-1a("") 的偏移基准是 2166136261，固定住算法本身，防止哪天被换实现
        assertEquals(2166136261.toInt() and 0x7FFFFFFF, stableCoverHash(""))
    }

    @Test
    fun `different books spread across palette`() {
        val buckets = (0 until 200).map { stableCoverHash("bqg99:$it") % novelCoverPaletteColors.size }.toSet()
        // 200 本书只落到一两种颜色就说明哈希分布有问题
        assertTrue("分布过于集中: $buckets", buckets.size >= 6)
        (0 until 200).forEach {
            assertEquals(2, novelCoverPalette("bqg99:$it").size)
        }
    }

    @Test
    fun `same book always gets same colors across pages`() {
        val book = NovelBook(source = "bqg99", bookId = "2639610", title = "牧神记")
        val fromBook = novelCoverPalette(book.coverSeed)
        val fromShelf = novelCoverPalette(novelKey(book.source, book.bookId))
        assertEquals(fromBook, fromShelf)
    }

    // MARK: 书架进度

    private fun shelf(
        chapterIndex: Int,
        totalChapters: Int,
        position: Double,
    ) = ShelfBook(
        source = "bqg99",
        bookId = "1",
        title = "书",
        lastChapterId = "c$chapterIndex",
        lastChapterTitle = "第${chapterIndex}章",
        lastChapterIndex = chapterIndex,
        totalChapters = totalChapters,
        positionInChapter = position,
    )

    @Test
    fun `percent computed from chapter index and position`() {
        // (100 - 1 + 0.5) / 1000 = 0.0995
        val entry = shelf(chapterIndex = 100, totalChapters = 1000, position = 0.5)
        assertEquals(0.0995, entry.percent, 0.0001)
        assertEquals("10%", entry.percentText)
        assertEquals("读至 第100章", entry.progressText)
    }

    @Test
    fun `percent falls back to position when total unknown`() {
        assertEquals(0.4, shelf(1, 0, 0.4).percent, 0.0001)
    }

    @Test
    fun `percent clamps at both ends`() {
        assertEquals(0.0, shelf(0, 100, 0.0).percent, 0.0001)
        // 最后一章读完 = 100%
        assertEquals(1.0, shelf(100, 100, 1.0).percent, 0.0001)
    }

    @Test
    fun `progress text falls back when no chapter yet`() {
        assertEquals("尚未开始", shelf(0, 0, 0.0).copy(lastChapterTitle = "").progressText)
        assertEquals("读至 第一章", shelf(0, 0, 0.0).copy(lastChapterTitle = "第一章").progressText)
    }

    @Test
    fun `key includes source because ids are not portable across sources`() {
        assertEquals("bqg99:2639610", novelKey("bqg99", "2639610"))
        assertEquals("blqvdu:2639610", novelKey("blqvdu", "2639610"))
        assertTrue(novelKey("bqg99", "1") != novelKey("biquge365", "1"))
    }

    // MARK: 错误体解析

    @Test
    fun `parses text detail from upstream failure`() {
        val body = """{"detail":"重试 4 次后仍失败: PoolTimeout | https://www.bqg99.cc/"}"""
        val message = parseNovelErrorDetail(body)
        assertTrue(message.contains("PoolTimeout"))
    }

    @Test
    fun `parses validation detail array`() {
        val body = """{"detail":[{"loc":["query","kw"],"msg":"Field required","type":"missing"}]}"""
        assertEquals("Field required", parseNovelErrorDetail(body))
    }

    @Test
    fun `returns empty string for unparseable body`() {
        assertEquals("", parseNovelErrorDetail("<html>502 Bad Gateway</html>"))
        assertEquals("", parseNovelErrorDetail(""))
    }
}
