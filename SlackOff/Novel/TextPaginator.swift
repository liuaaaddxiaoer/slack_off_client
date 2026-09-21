import CoreGraphics
import CoreText
import Foundation
import UIKit

/// 排版参数。由 NovelSettings 派生，决定分页结果。
struct NovelTypography: Equatable, Sendable {
    var fontSize: Double = 18
    var lineSpacing: NovelLineSpacing = .standard

    /// 左右边距
    var horizontalInset: CGFloat = 20
    /// 顶部留白（含状态栏安全区，由调用方叠加）
    var topInset: CGFloat = 40
    /// 底部留白（含页脚区）
    var bottomInset: CGFloat = 40

    /// 首行缩进字符数（网文标准 2 字符）
    var firstLineIndentChars: CGFloat = 2
    /// 章节标题字号 = fontSize × 该比例
    var titleScale: CGFloat = 1.3
    /// 章节标题段后间距
    var titleSpacingAfter: CGFloat = 16
    /// 正文段后间距（靠它 + 首行缩进区分段落）
    var paragraphSpacingRatio: CGFloat = 0.3

    /// 行高 = 字号 × 行距倍数
    var lineHeight: CGFloat { CGFloat(fontSize) * lineSpacing.multiplier }
    /// 段后间距
    var paragraphSpacing: CGFloat { CGFloat(fontSize) * paragraphSpacingRatio }
    /// 首行缩进绝对值
    var firstLineIndent: CGFloat { CGFloat(fontSize) * firstLineIndentChars }
}

extension NovelTypography {
    init(settings: NovelSettings) {
        self.init(fontSize: settings.fontSize, lineSpacing: settings.lineSpacing)
    }
}

/// 一行：CoreText 行对象 + 测量结果。
///
/// 保留 `ctLine` 是为了「测量与渲染严格一致」——渲染时直接 `CTLineDraw` 同一个对象，
/// 不会出现测量用一套断行、绘制又断一次导致末页溢出的问题。
struct NovelLine {
    let ctLine: CTLine
    /// 该行的原始属性串。绘制时要按纸色重设前景色，而 `CTLine` 取不回属性串，故一并存下。
    let attributed: NSAttributedString
    let metric: NovelLineMetric
    let ascent: CGFloat
    let descent: CGFloat
    /// 该行底部额外间距（段末行才有）
    let trailingSpacing: CGFloat
    let isTitle: Bool
    /// 段首行的缩进量（网文标准 2 字符）。
    ///
    /// CTTypesetter 断行**不考虑** firstLineHeadIndent（缩进只在 CTFramesetter
    /// 整页排版时才生效），所以这里手动模拟：测量首行时把可用宽度扣掉缩进，
    /// 绘制时再把这个量加回 x 起点，保证「测多少字就画多少字」。
    let leadingIndent: CGFloat

    /// 含段后间距的总高，切页用它。
    var height: CGFloat { metric.height }
}

/// 一章的完整排版结果。
///
/// 所有字符 offset 均为 **UTF-16 code unit**（CoreText 的原生索引），
/// 中文在 BMP 内占 1 个 unit，与「字符数」一致；emoji 会占 2，故统一用它避免错位。
@MainActor
final class NovelLayout {
    let lines: [NovelLine]
    let pages: [NovelPage]
    let pageSize: CGSize
    let typography: NovelTypography
    let chapterTitle: String
    /// 全文（标题 + 段落以 \n 连接），用于校验与「按 offset 找回位置」。
    let fullText: String

    init(
        lines: [NovelLine],
        pages: [NovelPage],
        pageSize: CGSize,
        typography: NovelTypography,
        chapterTitle: String,
        fullText: String
    ) {
        self.lines = lines
        self.pages = pages
        self.pageSize = pageSize
        self.typography = typography
        self.chapterTitle = chapterTitle
        self.fullText = fullText
    }

    var pageCount: Int { pages.count }

    /// 正文明区高度（扣掉上下留白）。
    var availableHeight: CGFloat {
        pageSize.height - typography.topInset - typography.bottomInset
    }

    /// 某页首字符 offset（跨尺寸重排后用它把读者放回原位）。
    func charOffset(ofPage index: Int) -> Int {
        guard !pages.isEmpty else { return 0 }
        let clamped = min(max(index, 0), pages.count - 1)
        return pages[clamped].charRange.lowerBound
    }

    func pageIndex(forCharOffset offset: Int) -> Int {
        PageSplitter.pageIndex(forCharOffset: offset, in: pages)
    }

    /// 章内进度 0~1（按页序，末页计为 1）。
    func progress(atPage index: Int) -> Double {
        guard pages.count > 1 else { return 1 }
        return Double(min(max(index, 0), pages.count - 1)) / Double(pages.count - 1)
    }

    /// 用 CoreText 绘制某页正文到给定上下文（左上原点）。
    ///
    /// 文字色必须作为 `.foregroundColor` 属性传给 CTLine：实测 `CTLineDraw` **完全忽略**
    /// 上下文的 `setFillColor`，属性缺省时按黑色画（对照度 1.13 ≈ 看不见，夜黑纸整页糊死）。
    /// 分页时建的 CTLine 不带颜色，这里临时复制一份加属性，切纸色就不必重新分页。
    func draw(page index: Int, textColor: UIColor, in context: CGContext) {
        guard pages.indices.contains(index) else { return }
        let page = pages[index]

        context.textMatrix = .identity
        context.saveGState()
        // CoreText 用左下原点，这里翻成 UIView 的左上原点
        context.translateBy(x: 0, y: pageSize.height)
        context.scaleBy(x: 1, y: -1)

        var y = typography.topInset
        for lineIndex in page.lineRange {
            guard lines.indices.contains(lineIndex) else { break }
            let line = lines[lineIndex]
            let colored = NSMutableAttributedString(attributedString: line.attributed)
            colored.addAttribute(
                .foregroundColor,
                value: textColor,
                range: NSRange(location: 0, length: colored.length)
            )
            let ctLine = CTLineCreateWithAttributedString(colored)

            let baselineFromTop = y + line.ascent
            context.textPosition = CGPoint(
                x: typography.horizontalInset + line.leadingIndent,
                y: pageSize.height - baselineFromTop
            )
            CTLineDraw(ctLine, context)
            y += line.height
        }
        context.restoreGState()
    }
}

/// iOS 侧的分页引擎：CoreText 逐段逐行测量 → 交给 `PageSplitter` 纯函数切页。
///
/// 逐段（而不是整章一次）测量的原因：段落边界必须明确，才能精确施加
/// 首行缩进、段后间距与章节标题的额外间距；整章喂给 CTTypesetter 拿不到段边界。
enum NovelTextPaginator {

    static func paginate(
        title: String,
        paragraphs: [String],
        typography: NovelTypography,
        pageSize: CGSize
    ) -> NovelLayout {
        let textWidth = max(pageSize.width - typography.horizontalInset * 2, 1)
        let titleFontSize = CGFloat(typography.fontSize) * typography.titleScale
        let bodyFontSize = CGFloat(typography.fontSize)
        let lineSpacingExtra = typography.lineHeight - bodyFontSize

        var lines: [NovelLine] = []
        var segments: [String] = []
        var offset = 0

        // 章节标题作为首行前的粗体标题块，参与分页
        if !title.isEmpty {
            append(
                text: title,
                offset: offset,
                fontSize: titleFontSize,
                bold: true,
                firstLineIndent: 0,
                lineSpacingExtra: titleFontSize * (typography.lineSpacing.multiplier - 1),
                trailingSpacing: typography.titleSpacingAfter,
                isTitle: true,
                separatorLength: 1,
                width: textWidth,
                into: &lines
            )
            segments.append(title)
            offset += utf16Length(title) + 1  // +1 是段落间的 \n
        }

        for (index, paragraph) in paragraphs.enumerated() {
            let isLast = index == paragraphs.count - 1
            append(
                text: paragraph,
                offset: offset,
                fontSize: bodyFontSize,
                bold: false,
                firstLineIndent: typography.firstLineIndent,
                lineSpacingExtra: lineSpacingExtra,
                // 最后一段不加段后间距，避免末页多出一段空白
                trailingSpacing: isLast ? 0 : typography.paragraphSpacing,
                isTitle: false,
                separatorLength: isLast ? 0 : 1,
                width: textWidth,
                into: &lines
            )
            segments.append(paragraph)
            offset += utf16Length(paragraph) + (isLast ? 0 : 1)
        }

        let metrics = lines.map(\.metric)
        let pages = PageSplitter.split(
            lines: metrics,
            pageHeight: pageSize.height,
            headerHeight: typography.topInset,
            footerHeight: typography.bottomInset
        )

        return NovelLayout(
            lines: lines,
            pages: pages,
            pageSize: pageSize,
            typography: typography,
            chapterTitle: title,
            fullText: segments.joined(separator: "\n")
        )
    }

    // MARK: - 逐行测量

    private static func append(
        text: String,
        offset: Int,
        fontSize: CGFloat,
        bold: Bool,
        firstLineIndent: CGFloat,
        lineSpacingExtra: CGFloat,
        trailingSpacing: CGFloat,
        isTitle: Bool,
        /// 该段之后还有几个字符的分隔符（\n = 1，最后一段 = 0）。
        /// 段末行的 charRange 要把它含进去，页区间才能无缝覆盖全文。
        separatorLength: Int,
        width: CGFloat,
        into lines: inout [NovelLine]
    ) {
        let paragraphStyle = NSMutableParagraphStyle()
        paragraphStyle.firstLineHeadIndent = firstLineIndent
        paragraphStyle.headIndent = 0
        paragraphStyle.tailIndent = 0
        paragraphStyle.lineBreakMode = .byCharWrapping
        paragraphStyle.alignment = .justified

        let attributes: [NSAttributedString.Key: Any] = [
            .font: bold
                ? UIFont.boldSystemFont(ofSize: fontSize)
                : UIFont.systemFont(ofSize: fontSize),
            .paragraphStyle: paragraphStyle,
            // 中文两端对齐时避免标点被压扁
            .kern: 0,
        ]

        let attributed = NSAttributedString(string: text, attributes: attributes)
        let length = attributed.length
        guard length > 0 else {
            // 空段落也占一行高度，保证 offset 推进与视觉留白一致
            let height = fontSize + lineSpacingExtra + trailingSpacing
            let blank = NSAttributedString(string: " ", attributes: attributes)
            lines.append(
                NovelLine(
                    ctLine: CTLineCreateWithAttributedString(blank as CFAttributedString),
                    attributed: blank,
                    metric: NovelLineMetric(start: offset, end: offset + separatorLength, height: height),
                    ascent: fontSize,
                    descent: 0,
                    trailingSpacing: trailingSpacing,
                    isTitle: isTitle,
                    leadingIndent: 0
                )
            )
            return
        }

        let typesetter = CTTypesetterCreateWithAttributedString(attributed)
        var start = 0

        while start < length {
            // 段首行要扣掉缩进宽度：CTTypesetter 不会自己考虑 firstLineHeadIndent
            let isFirstLineOfParagraph = start == 0
            let effectiveWidth = width - (isFirstLineOfParagraph ? firstLineIndent : 0)
            // CTTypesetterSuggestLineBreak 返回的是**从 startIndex 起的长度**，
            // 不是绝对 index（实测：start=19 时它返回 19 而不是 38）。
            // 当成绝对 index 用会让 end == start，触发下面的防死循环保护，
            // 结果每行只排一个字（截图里表现为「竖排」）。
            let suggestedLength = CTTypesetterSuggestLineBreak(typesetter, start, effectiveWidth)
            var end = start + suggestedLength
            // 超长不可断词（如一串英文/数字）时可能返回 0，强制推进防死循环
            if end <= start { end = start + 1 }
            end = min(end, length)

            let line = CTTypesetterCreateLine(typesetter, CFRange(location: start, length: end - start))
            var ascent: CGFloat = 0
            var descent: CGFloat = 0
            var leading: CGFloat = 0
            CTLineGetTypographicBounds(line, &ascent, &descent, &leading)

            // 段末行承担段后间距（含章节标题的 16pt），charRange 也顺延吃掉分隔符
            let isLastLineOfParagraph = end >= length
            let extra = isLastLineOfParagraph ? trailingSpacing : 0
            let height = ascent + descent + leading + lineSpacingExtra + extra
            let lineEnd = offset + end + (isLastLineOfParagraph ? separatorLength : 0)

            lines.append(
                NovelLine(
                    ctLine: line,
                    attributed: attributed.attributedSubstring(from: NSRange(location: start, length: end - start)),
                    metric: NovelLineMetric(start: offset + start, end: lineEnd, height: height),
                    ascent: ascent,
                    descent: descent,
                    trailingSpacing: extra,
                    isTitle: isTitle,
                    leadingIndent: isFirstLineOfParagraph ? firstLineIndent : 0
                )
            )

            start = end
        }
    }

    private static func utf16Length(_ string: String) -> Int {
        (string as NSString).length
    }
}
