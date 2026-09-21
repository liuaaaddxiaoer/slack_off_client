package com.slackoff.app.novel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 切页纯函数测试。用例与 iOS 端 PageSplitterTests 完全一致，
 * 两端算法必须给出同样的分页结果，否则同一本书在两个平台翻页手感会不一样。
 */
class PageSplitterTest {

    private fun lines(heights: List<Float>, stride: Int = 10): List<NovelLineMetric> =
        heights.mapIndexed { index, height ->
            NovelLineMetric(start = index * stride, end = (index + 1) * stride, height = height)
        }

    @Test
    fun `splits into multiple pages`() {
        val metrics = lines(List(10) { 24f })
        // 页高 100、无边距 → 每页 4 行（4×24=96 ≤ 100，第 5 行会到 120）
        val pages = PageSplitter.split(metrics, pageHeight = 100f)
        assertEquals(3, pages.size)
        assertEquals(0, pages[0].lineStart)
        assertEquals(4, pages[0].lineEnd)
        assertEquals(4, pages[1].lineStart)
        assertEquals(8, pages[1].lineEnd)
        assertEquals(8, pages[2].lineStart)
        assertEquals(10, pages[2].lineEnd)
    }

    @Test
    fun `page ranges are contiguous and cover everything`() {
        val metrics = lines(listOf(20f, 30f, 25f, 40f, 15f, 35f, 22f, 18f, 27f, 33f, 19f, 24f))
        val pages = PageSplitter.split(metrics, pageHeight = 70f)

        for (index in 1 until pages.size) {
            assertEquals(pages[index - 1].lineEnd, pages[index].lineStart)
            assertEquals(pages[index - 1].charEnd, pages[index].charStart)
        }
        assertEquals(0, pages.first().charStart)
        assertEquals(metrics.last().end, pages.last().charEnd)
        assertEquals(pages.indices.toList(), pages.map { it.index })
    }

    @Test
    fun `oversized single line still advances`() {
        // 一行比整页还高（超大字号 + 超小窗口）：必须每页塞一行，绝不能原地打转
        val metrics = lines(listOf(500f, 500f, 500f))
        val pages = PageSplitter.split(metrics, pageHeight = 100f)
        assertEquals(3, pages.size)
        assertEquals(listOf(0 to 1, 1 to 2, 2 to 3), pages.map { it.lineStart to it.lineEnd })
    }

    @Test
    fun `empty lines yield single empty page`() {
        val pages = PageSplitter.split(emptyList(), pageHeight = 600f)
        assertEquals(1, pages.size)
        assertTrue(pages[0].isEmpty)
        assertEquals(0, pages[0].charStart)
        assertEquals(0, pages[0].charEnd)
    }

    @Test
    fun `header and footer reduce capacity`() {
        val metrics = lines(List(10) { 20f })
        val withoutInsets = PageSplitter.split(metrics, pageHeight = 100f)
        val withInsets = PageSplitter.split(metrics, pageHeight = 100f, headerHeight = 20f, footerHeight = 30f)
        assertEquals(2, withoutInsets.size)   // 净高 100 → 每页 5 行
        assertEquals(5, withInsets.size)      // 净高 50 → 每页 2 行
        assertEquals(2, withInsets[0].lineEnd)
    }

    @Test
    fun `insets eating whole page degrade to line per page`() {
        val metrics = lines(listOf(20f, 20f, 20f))
        val pages = PageSplitter.split(metrics, pageHeight = 100f, headerHeight = 80f, footerHeight = 80f)
        assertEquals(3, pages.size)
    }

    @Test
    fun `zero height lines are tolerated`() {
        val metrics = lines(listOf(0f, 0f, 25f, 0f, 25f))
        val pages = PageSplitter.split(metrics, pageHeight = 30f)
        assertTrue(pages.size >= 2)
        assertEquals(0, pages.first().charStart)
        assertEquals(metrics.last().end, pages.last().charEnd)
    }

    @Test
    fun `page index lookup finds owning page`() {
        val metrics = lines(List(10) { 24f })
        val pages = PageSplitter.split(metrics, pageHeight = 100f)
        assertEquals(3, pages.size)

        assertEquals(0, PageSplitter.pageIndexForOffset(0, pages))
        assertEquals(0, PageSplitter.pageIndexForOffset(39, pages))
        assertEquals(1, PageSplitter.pageIndexForOffset(40, pages))
        assertEquals(2, PageSplitter.pageIndexForOffset(85, pages))
        // 超出末尾 → 最后一页；负数 → 第一页；空列表 → 0
        assertEquals(2, PageSplitter.pageIndexForOffset(9999, pages))
        assertEquals(0, PageSplitter.pageIndexForOffset(-5, pages))
        assertEquals(0, PageSplitter.pageIndexForOffset(0, emptyList()))
    }
}
