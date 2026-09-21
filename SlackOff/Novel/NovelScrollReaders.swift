import SwiftUI

/// 章边界的「哨兵页」：滚到它就触发上/下一章。
///
/// 覆盖模式里哨兵必须占满整页宽，否则 `.paging` 行为会按错误的步长吸附；
/// 滚动模式里它是一条窄提示条。
struct ChapterEdgeSentinel: View {
    enum Kind {
        case previous
        case next
    }

    let kind: Kind
    let paper: NovelPaper
    let chapterTitle: String?
    let enabled: Bool
    let compact: Bool

    var body: some View {
        VStack(spacing: 6) {
            if compact {
                Image(systemName: kind == .previous ? "chevron.up" : "chevron.down")
                    .font(.system(size: 12, weight: .semibold))
                Text(label)
                    .font(.system(size: 12))
            } else {
                Image(systemName: kind == .previous ? "chevron.left" : "chevron.right")
                    .font(.system(size: 20, weight: .medium))
                Text(label)
                    .font(.system(size: 13))
                if let chapterTitle, !chapterTitle.isEmpty {
                    Text(chapterTitle)
                        .font(.system(size: 12))
                        .lineLimit(1)
                        .opacity(0.7)
                }
            }
        }
        .foregroundStyle(enabled ? paper.text : paper.text.opacity(0.35))
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(paper.background)
    }

    private var label: String {
        guard enabled else {
            return kind == .previous ? "已是第一章" : "已是最后一章"
        }
        return kind == .previous ? "上一章" : "下一章"
    }
}

/// 覆盖滑动：左右整页平移，边界哨兵触发跨章。
struct CoverPagingReader: View {
    let model: NovelReaderModel
    let paper: NovelPaper
    let bookTitle: String
    let onCenterTap: () -> Void

    @State private var scrollID: Int?

    var body: some View {
        GeometryReader { proxy in
            if let layout = model.layout {
                ScrollView(.horizontal) {
                    LazyHStack(spacing: 0) {
                        ChapterEdgeSentinel(
                            kind: .previous,
                            paper: paper,
                            chapterTitle: model.chapterIndex > 0 ? model.chapters[safe: model.chapterIndex - 1]?.title : nil,
                            enabled: model.chapterIndex > 0,
                            compact: false
                        )
                        .frame(width: proxy.size.width, height: proxy.size.height)
                        .id(-1)

                        ForEach(layout.pages) { page in
                            NovelPageBody(
                                layout: layout,
                                pageIndex: page.index,
                                paper: paper,
                                bookTitle: bookTitle,
                                onCenterTap: onCenterTap
                            )
                            .frame(width: proxy.size.width, height: proxy.size.height)
                            .id(page.index)
                        }

                        ChapterEdgeSentinel(
                            kind: .next,
                            paper: paper,
                            chapterTitle: model.chapters[safe: model.chapterIndex + 1]?.title,
                            enabled: model.chapterIndex + 1 < model.totalChapters,
                            compact: false
                        )
                        .frame(width: proxy.size.width, height: proxy.size.height)
                        .id(layout.pageCount)
                    }
                    .scrollTargetLayout()
                }
                .scrollTargetBehavior(.paging)
                .scrollPosition(id: $scrollID)
                .scrollIndicators(.hidden)
            } else {
                Color.clear
            }
        }
        .onAppear { scrollID = model.currentPage }
        .onChange(of: scrollID) { _, newValue in
            handleScrollID(newValue)
        }
        // 外部改页（点目录、跨章、重排）时把滚动位置拉回去
        .onChange(of: model.currentPage) { _, newValue in
            if scrollID != newValue { scrollID = newValue }
        }
        .onChange(of: model.body?.chapterId) { _, _ in
            scrollID = model.currentPage
        }
    }

    private func handleScrollID(_ id: Int?) {
        guard let id, let layout = model.layout else { return }
        if id == -1 {
            scrollID = 0
            Task { await model.retreat() }
        } else if id == layout.pageCount {
            scrollID = 0
            Task { await model.advance() }
        } else if id != model.currentPage {
            model.goToPage(id)
        }
    }
}

/// 上下滚动：连续长条，页块之间留 12pt 缝隙，首尾窄条触发跨章。
struct VerticalScrollReader: View {
    let model: NovelReaderModel
    let paper: NovelPaper
    let bookTitle: String
    let onCenterTap: () -> Void

    @State private var scrollID: Int?

    var body: some View {
        GeometryReader { proxy in
            if let layout = model.layout {
                ScrollView(.vertical) {
                    LazyVStack(spacing: 12) {
                        ChapterEdgeSentinel(
                            kind: .previous,
                            paper: paper,
                            chapterTitle: model.chapters[safe: model.chapterIndex - 1]?.title,
                            enabled: model.chapterIndex > 0,
                            compact: true
                        )
                        .frame(width: proxy.size.width, height: 52)
                        .id(-1)

                        ForEach(layout.pages) { page in
                            NovelPageBody(
                                layout: layout,
                                pageIndex: page.index,
                                paper: paper,
                                bookTitle: bookTitle,
                                onCenterTap: onCenterTap
                            )
                            .frame(width: proxy.size.width, height: proxy.size.height)
                            .clipShape(RoundedRectangle(cornerRadius: 6))
                            .id(page.index)
                        }

                        ChapterEdgeSentinel(
                            kind: .next,
                            paper: paper,
                            chapterTitle: model.chapters[safe: model.chapterIndex + 1]?.title,
                            enabled: model.chapterIndex + 1 < model.totalChapters,
                            compact: true
                        )
                        .frame(width: proxy.size.width, height: 52)
                        .id(layout.pageCount)
                    }
                    .scrollTargetLayout()
                }
                .scrollPosition(id: $scrollID)
                .scrollIndicators(.hidden)
            } else {
                Color.clear
            }
        }
        .onAppear { scrollID = model.currentPage }
        .onChange(of: scrollID) { _, newValue in
            guard let id = newValue, let layout = model.layout else { return }
            if id == -1 {
                Task { await model.retreat() }
            } else if id == layout.pageCount {
                Task { await model.advance() }
            } else if id != model.currentPage {
                model.goToPage(id)
            }
        }
        .onChange(of: model.currentPage) { _, newValue in
            if scrollID != newValue { scrollID = newValue }
        }
        .onChange(of: model.body?.chapterId) { _, _ in
            scrollID = model.currentPage
        }
    }
}

/// 安全下标：越界返回 nil，避免目录/章节切换时的竞态崩溃。
extension Array {
    subscript(safe index: Int) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}
