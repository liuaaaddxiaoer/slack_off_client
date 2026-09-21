package com.slackoff.app.novel

import android.graphics.Canvas
import android.graphics.Paint
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import com.slackoff.app.support.NovelLineSpacing
import com.slackoff.app.support.NovelSettingsData

/** 排版参数。由 NovelSettingsData 派生，决定分页结果。与 iOS 的 NovelTypography 对齐。 */
data class NovelTypography(
    val fontSizePx: Float = 54f,
    val lineSpacing: NovelLineSpacing = NovelLineSpacing.STANDARD,
    /** 左右边距 */
    val horizontalInsetPx: Float = 56f,
    /** 顶部留白（含状态栏，由调用方叠加） */
    val topInsetPx: Float = 112f,
    /** 底部留白（含页脚区） */
    val bottomInsetPx: Float = 112f,
    /** 首行缩进字符数（网文标准 2 字符） */
    val firstLineIndentChars: Float = 2f,
    /** 章节标题字号 = fontSize × 该比例 */
    val titleScale: Float = 1.3f,
    /** 章节标题段后间距 */
    val titleSpacingAfterPx: Float = 45f,
    /** 正文段后间距 */
    val paragraphSpacingRatio: Float = 0.3f,
) {
    val paragraphSpacingPx: Float get() = fontSizePx * paragraphSpacingRatio

    companion object {
        /** dp → px 的换算交给调用方（需要 Context），这里只提供从设置构造的工厂。 */
        fun from(settings: NovelSettingsData, density: Float, fontSizeDp: Float = settings.fontSize): NovelTypography {
            val sp2px = density  // 项目里字号按 dp/sp 等价处理，避免系统字体缩放打乱分页
            return NovelTypography(
                fontSizePx = fontSizeDp * sp2px,
                lineSpacing = settings.lineSpacing,
                horizontalInsetPx = 20f * density,
                topInsetPx = 40f * density,
                bottomInsetPx = 40f * density,
                titleSpacingAfterPx = 16f * density,
            )
        }
    }
}

/**
 * 一章的完整排版结果。
 *
 * 与 iOS 的差异：Android 用**一个** StaticLayout 承载整章（含标题段），
 * 分页只按行切；渲染时用 clip + translate 只画出该页的行范围。
 * 这样测量与渲染天然是同一个对象，不会出现「测一套、画一套」的错位。
 */
class NovelLayout(
    val staticLayout: StaticLayout,
    val lineMetrics: List<NovelLineMetric>,
    val pages: List<NovelPage>,
    val pageWidthPx: Float,
    val pageHeightPx: Float,
    val typography: NovelTypography,
    val chapterTitle: String,
    val fullText: String,
) {
    val pageCount: Int get() = pages.size

    /** 正文明区高度（扣掉上下留白）。 */
    val availableHeightPx: Float get() = pageHeightPx - typography.topInsetPx - typography.bottomInsetPx

    fun charOffsetOfPage(index: Int): Int {
        if (pages.isEmpty()) return 0
        return pages[index.coerceIn(0, pages.size - 1)].charStart
    }

    fun pageIndexForCharOffset(offset: Int): Int = PageSplitter.pageIndexForOffset(offset, pages)

    /** 章内进度 0~1（按页序，末页计为 1）。 */
    fun progressAtPage(index: Int): Double {
        if (pages.size <= 1) return 1.0
        return index.coerceIn(0, pages.size - 1).toDouble() / (pages.size - 1)
    }

    /**
     * 绘制某页正文。
     * `textColor` 由调用方给（切纸色/夜间模式时不必重新分页）。
     */
    fun drawPage(canvas: Canvas, pageIndex: Int, textColor: Int) {
        if (pageIndex !in pages.indices) return
        val page = pages[pageIndex]

        val paint = staticLayout.paint
        val originalColor = paint.color
        paint.color = textColor

        val topOffset = staticLayout.getLineTop(page.lineStart)

        canvas.save()
        canvas.clipRect(
            typography.horizontalInsetPx,
            typography.topInsetPx,
            pageWidthPx - typography.horizontalInsetPx,
            pageHeightPx - typography.bottomInsetPx,
        )
        canvas.translate(typography.horizontalInsetPx, typography.topInsetPx - topOffset)
        staticLayout.draw(canvas)
        canvas.restore()

        paint.color = originalColor
    }
}

/** 段后间距：只对该段最后一行增加 descent。 */
private class ParagraphSpacingSpan(private val spacingPx: Int) : LineHeightSpan {
    override fun chooseHeight(
        text: CharSequence,
        start: Int,
        end: Int,
        spanstartv: Int,
        v: Int,
        fm: Paint.FontMetricsInt,
    ) {
        val spanEnd = if (text is Spanned) text.getSpanEnd(this) else end
        // 当前行覆盖了 span 的结尾 → 这就是段末行
        if (end >= spanEnd) {
            fm.descent += spacingPx
            fm.bottom += spacingPx
        }
    }
}

/**
 * Android 侧的分页引擎：整章一个 StaticLayout 逐行测量 → 交给 [PageSplitter] 纯函数切页。
 */
object TextPaginator {

    fun paginate(
        title: String,
        paragraphs: List<String>,
        typography: NovelTypography,
        pageWidthPx: Float,
        pageHeightPx: Float,
    ): NovelLayout {
        val textWidth = (pageWidthPx - typography.horizontalInsetPx * 2).coerceAtLeast(1f)

        val builder = SpannableStringBuilder()
        val segments = ArrayList<String>()
        val titleFontSize = typography.fontSizePx * typography.titleScale
        val indentPx = (typography.fontSizePx * typography.firstLineIndentChars).toInt()
        val paragraphSpacingPx = typography.paragraphSpacingPx.toInt()
        val titleSpacingPx = typography.titleSpacingAfterPx.toInt()

        // 章节标题作为首行前的粗体标题块，参与分页
        if (title.isNotEmpty()) {
            val start = builder.length
            builder.append(title)
            val end = builder.length
            builder.setSpan(StyleSpan(android.graphics.Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            builder.setSpan(RelativeSizeSpan(typography.titleScale), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            builder.setSpan(ParagraphSpacingSpan(titleSpacingPx), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            segments.add(title)
            builder.append("\n")
        }

        paragraphs.forEachIndexed { index, paragraph ->
            val start = builder.length
            builder.append(paragraph)
            val end = builder.length
            // 首行缩进 2 字符；段后间距只加在段末行
            builder.setSpan(LeadingMarginSpan.Standard(indentPx), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val spacing = if (index == paragraphs.lastIndex) 0 else paragraphSpacingPx
            if (spacing > 0) {
                builder.setSpan(ParagraphSpacingSpan(spacing), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            segments.add(paragraph)
            if (index != paragraphs.lastIndex) builder.append("\n")
        }

        val fullText = segments.joinToString("\n")

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            // 标题用 RelativeSizeSpan 放大，这里给正文字号即可
            textSize = typography.fontSizePx
            color = android.graphics.Color.BLACK
        }

        val lineSpacingAdd = typography.fontSizePx * (typography.lineSpacing.multiplier - 1f)

        val staticLayout = StaticLayout.Builder
            .obtain(builder, 0, builder.length, paint, textWidth.toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(lineSpacingAdd, 1f)
            .setIncludePad(false)
            .build()

        // 逐行提取字符区间与行高
        val lineMetrics = ArrayList<NovelLineMetric>(staticLayout.lineCount)
        for (line in 0 until staticLayout.lineCount) {
            val top = staticLayout.getLineTop(line).toFloat()
            val bottom = staticLayout.getLineBottom(line).toFloat()
            lineMetrics.add(
                NovelLineMetric(
                    start = staticLayout.getLineStart(line),
                    end = staticLayout.getLineEnd(line),
                    height = (bottom - top).coerceAtLeast(0f),
                )
            )
        }

        val pages = PageSplitter.split(
            lines = lineMetrics,
            pageHeight = pageHeightPx,
            headerHeight = typography.topInsetPx,
            footerHeight = typography.bottomInsetPx,
        )

        return NovelLayout(
            staticLayout = staticLayout,
            lineMetrics = lineMetrics,
            pages = pages,
            pageWidthPx = pageWidthPx,
            pageHeightPx = pageHeightPx,
            typography = typography,
            chapterTitle = title,
            fullText = fullText,
        )
    }
}
