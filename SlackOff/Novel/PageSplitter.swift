import CoreGraphics
import Foundation

/// 一行的测量结果：字符区间 [start, end) + 行高。
/// 由平台测量层产出（iOS: CoreText / Android: StaticLayout），切页层只认这个结构。
struct NovelLineMetric: Equatable, Sendable {
    let start: Int
    let end: Int
    let height: CGFloat
}

/// 一页：覆盖的行区间与字符区间。
struct NovelPage: Equatable, Identifiable, Sendable {
    /// 0-based 页序
    let index: Int
    /// 行区间（半开），指向 NovelLineMetric 数组
    let lineRange: Range<Int>
    /// 字符区间（半开）
    let charRange: Range<Int>

    var id: Int { index }

    var isEmpty: Bool { lineRange.isEmpty }
}

/// 切页纯函数：把「行高序列」按可用高度切成页。
///
/// 不含任何平台 API，两端（iOS / Android）共用同一套语义与测试用例：
/// - 每页尽可能塞满，但不会把一行切开；
/// - **每页至少推进一行**，因此单行高度超过页高（超大字号 + 超小窗口）时不会死循环；
/// - 各页 `charRange` 首尾相接，拼回去等于原文。
enum PageSplitter {
    /// - Parameters:
    ///   - lines: 行测量结果，必须按字符顺序且区间连续。
    ///   - pageHeight: 整页高度（含页眉页脚区）。
    ///   - headerHeight: 页眉占用高度（章节标题区之外的页顶留白/页眉）。
    ///   - footerHeight: 页脚占用高度。
    /// - Returns: 页数组；`lines` 为空时返回单个空页（章节总得有一页可渲染）。
    nonisolated static func split(
        lines: [NovelLineMetric],
        pageHeight: CGFloat,
        headerHeight: CGFloat = 0,
        footerHeight: CGFloat = 0
    ) -> [NovelPage] {
        guard !lines.isEmpty else {
            return [NovelPage(index: 0, lineRange: 0..<0, charRange: 0..<0)]
        }

        let available = pageHeight - headerHeight - footerHeight
        // 可用高度被边距吃光时退化为「每页一行」，保证仍能翻完全章而不是原地卡死。
        let capacity = max(available, 0)

        var pages: [NovelPage] = []
        var startLine = 0

        while startLine < lines.count {
            var endLine = startLine
            var used: CGFloat = 0

            while endLine < lines.count {
                let height = max(lines[endLine].height, 0)
                // endLine > startLine 是关键：首行无条件收下，避免高行导致零推进。
                if endLine > startLine, used + height > capacity { break }
                used += height
                endLine += 1
            }

            let startChar = lines[startLine].start
            let endChar = lines[endLine - 1].end
            pages.append(
                NovelPage(index: pages.count, lineRange: startLine..<endLine, charRange: startChar..<endChar)
            )
            startLine = endLine
        }

        return pages
    }

    /// 给定字符 offset，返回它落在第几页（找不到时返回最后一页）。
    /// 换设备/换字号重排后用它把读者放回原来的位置。
    nonisolated static func pageIndex(forCharOffset offset: Int, in pages: [NovelPage]) -> Int {
        guard !pages.isEmpty else { return 0 }
        if offset <= pages[0].charRange.lowerBound { return 0 }
        if let page = pages.first(where: { $0.charRange.contains(offset) }) { return page.index }
        // offset 落在页与页之间（理论上不会，行区间连续）或超出末尾 → 就近取前一页
        return pages.last { $0.charRange.lowerBound <= offset }?.index ?? pages[pages.count - 1].index
    }
}
