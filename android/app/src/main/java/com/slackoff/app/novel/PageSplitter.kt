package com.slackoff.app.novel

/**
 * 一行的测量结果：字符区间 [start, end) + 行高。
 * 由平台测量层产出（Android: StaticLayout / iOS: CoreText），切页层只认这个结构。
 */
data class NovelLineMetric(
    val start: Int,
    val end: Int,
    val height: Float,
)

/**
 * 一页。区间一律半开（end exclusive），与 iOS 端 NovelPage 语义完全一致，
 * 两端共用同一组切页测试用例。
 */
data class NovelPage(
    val index: Int,
    val lineStart: Int,
    val lineEnd: Int,
    val charStart: Int,
    val charEnd: Int,
) {
    val isEmpty: Boolean get() = lineStart >= lineEnd
    val lineCount: Int get() = (lineEnd - lineStart).coerceAtLeast(0)
}

/**
 * 切页纯函数：把「行高序列」按可用高度切成页。
 *
 * 不含任何 Android API，可以直接跑 JVM 单测。语义：
 * - 每页尽可能塞满，但不把一行切开；
 * - **每页至少推进一行**，单行高度超过页高（超大字号 + 超小窗口）时不会死循环；
 * - 各页 charRange 首尾相接，拼回去等于原文。
 */
object PageSplitter {

    fun split(
        lines: List<NovelLineMetric>,
        pageHeight: Float,
        headerHeight: Float = 0f,
        footerHeight: Float = 0f,
    ): List<NovelPage> {
        if (lines.isEmpty()) {
            return listOf(NovelPage(0, 0, 0, 0, 0))
        }

        // 可用高度被边距吃光时退化为「每页一行」，保证仍能翻完全章而不是原地卡死
        val capacity = (pageHeight - headerHeight - footerHeight).coerceAtLeast(0f)

        val pages = ArrayList<NovelPage>()
        var startLine = 0

        while (startLine < lines.size) {
            var endLine = startLine
            var used = 0f

            while (endLine < lines.size) {
                val height = lines[endLine].height.coerceAtLeast(0f)
                // endLine > startLine 是关键：首行无条件收下，避免高行导致零推进
                if (endLine > startLine && used + height > capacity) break
                used += height
                endLine++
            }

            pages.add(
                NovelPage(
                    index = pages.size,
                    lineStart = startLine,
                    lineEnd = endLine,
                    charStart = lines[startLine].start,
                    charEnd = lines[endLine - 1].end,
                )
            )
            startLine = endLine
        }

        return pages
    }

    /** 给定字符 offset，返回它落在第几页；越界时就近取首页/末页。 */
    fun pageIndexForOffset(offset: Int, pages: List<NovelPage>): Int {
        if (pages.isEmpty()) return 0
        if (offset <= pages.first().charStart) return 0
        pages.firstOrNull { offset in it.charStart until it.charEnd }?.let { return it.index }
        return pages.lastOrNull { it.charStart <= offset }?.index ?: pages.last().index
    }
}
