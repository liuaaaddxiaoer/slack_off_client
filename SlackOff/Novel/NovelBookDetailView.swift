import SwiftUI

/// 书籍详情：文字封面 + 元信息 + 简介折叠 + 完整目录 + 底部固定操作栏。
struct NovelBookDetailView: View {
    let source: String
    let bookId: String
    let title: String
    let author: String?
    let category: String?

    @State private var detail: NovelBookDetail?
    @State private var chapters: [NovelChapterItem] = []
    @State private var isLoading = false
    @State private var errorMessage: String?
    @State private var introExpanded = false
    @State private var reversedCatalog = false
    @State private var cacheManager = NovelCacheDownloadManager.shared
    /// 目录渐进展示：《牧神记》1920 章、《百炼飞升录》8684 章，
    /// 一次全渲染会让首帧卡顿，所以先给 60 条，再按需 +300。
    @State private var visibleChapterCount = 60

    @State private var shelf = BookshelfStore.shared

    private let chapterBatch = 300

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if isLoading && detail == nil {
                    ProgressView("加载中…")
                        .frame(maxWidth: .infinity, minHeight: 300)
                        .foregroundStyle(.secondary)
                } else if let errorMessage, detail == nil {
                    NovelStateView(kind: .error, message: errorMessage) {
                        Task { await load() }
                    }
                } else if let detail {
                    header(detail)
                    actions(detail)
                    cacheAllCard
                    intro(detail)
                    catalog
                }
            }
            .padding(16)
        }
        .background(Theme.cream)
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(Theme.softPink.opacity(0.4), for: .navigationBar)
        .toolbar(.hidden, for: .tabBar)
        .safeAreaInset(edge: .bottom) {
            if detail != nil { bottomBar }
        }
        .task { await load() }
    }

    // MARK: - 头部

    private func header(_ detail: NovelBookDetail) -> some View {
        HStack(alignment: .top, spacing: 14) {
            NovelTextCover(
                seed: detail.id,
                title: detail.title,
                author: detail.author,
                titleScale: 0.15,
                cornerRadius: 10
            )
            .frame(width: 104)
            .shadow(color: .black.opacity(0.12), radius: 6, y: 3)

            VStack(alignment: .leading, spacing: 6) {
                Text(detail.title)
                    .font(.system(size: 19, weight: .bold))
                    .foregroundStyle(Theme.plum)
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)

                if let author = detail.author, !author.isEmpty {
                    Text(author)
                        .font(.system(size: 13))
                        .foregroundStyle(.secondary)
                }

                HStack(spacing: 6) {
                    if let category = detail.category, !category.isEmpty {
                        tag(category, tint: Theme.pink)
                    }
                    if let status = detail.status, !status.isEmpty {
                        tag(status, tint: status.contains("完") ? Theme.mint : Theme.lavender)
                    }
                    Text(detail.source)
                        .font(.system(size: 10, weight: .medium))
                        .foregroundStyle(.secondary)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(Theme.hairline, in: Capsule())
                }

                if let words = detail.wordCountText {
                    metaLine(icon: "textformat.123", text: words)
                }
                if let count = detail.chapterCount, count > 0 {
                    metaLine(icon: "list.number", text: "\(count) 章")
                }
                if let latest = detail.latestChapter, !latest.isEmpty {
                    metaLine(icon: "bell.fill", text: "最新 \(latest)")
                }
                if let time = detail.updateTime, !time.isEmpty {
                    metaLine(icon: "clock", text: "更新 \(time)")
                }

                Spacer(minLength: 0)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .frame(minHeight: 150)
    }

    private func tag(_ text: String, tint: Color) -> some View {
        Text(text)
            .font(.system(size: 10.5, weight: .medium))
            .foregroundStyle(tint)
            .padding(.horizontal, 7)
            .padding(.vertical, 2.5)
            .background(tint.opacity(0.15), in: Capsule())
    }

    private func metaLine(icon: String, text: String) -> some View {
        HStack(spacing: 5) {
            Image(systemName: icon)
                .font(.system(size: 10))
            Text(text)
                .font(.system(size: 11.5))
                .lineLimit(1)
        }
        .foregroundStyle(.secondary)
    }

    // MARK: - 操作区

    private func actions(_ detail: NovelBookDetail) -> some View {
        HStack(spacing: 10) {
            NavigationLink {
                reader(for: detail, fromShelfProgress: true)
            } label: {
                Label(primaryActionTitle, systemImage: "book.fill")
                    .font(.system(size: 14.5, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .background(Theme.pink, in: RoundedRectangle(cornerRadius: 11))
            }
            .buttonStyle(.plain)

            Button {
                toggleShelf(detail)
            } label: {
                VStack(spacing: 3) {
                    Image(systemName: isInShelf ? "checkmark.circle.fill" : "plus.circle")
                        .font(.system(size: 17))
                    Text(isInShelf ? "已加入" : "书架")
                        .font(.system(size: 10))
                }
                .foregroundStyle(isInShelf ? Theme.mint : Theme.plum.opacity(0.8))
                .frame(width: 62)
                .padding(.vertical, 7)
                .background(Theme.card, in: RoundedRectangle(cornerRadius: 11))
                .overlay(RoundedRectangle(cornerRadius: 11).strokeBorder(Theme.hairline, lineWidth: 1))
            }
            .buttonStyle(.plain)
        }
    }

    private var cacheAllCard: some View {
        let state = cacheManager.state(source: source, bookId: bookId)
        let completed = !chapters.isEmpty && state.cachedCount >= chapters.count
        return Button {
            if state.isCaching {
                cacheManager.pause(source: source, bookId: bookId)
            } else if state.isPaused,
                      let record = cacheManager.records.first(where: { $0.id == shelfKey }) {
                cacheManager.resume(record)
            } else {
                cacheManager.start(
                    source: source,
                    bookId: bookId,
                    title: detail?.title ?? title,
                    author: detail?.author ?? author,
                    category: detail?.category ?? category,
                    chapters: chapters
                )
            }
        } label: {
            HStack(spacing: 12) {
                if state.isCaching {
                    ProgressView().tint(Theme.pink)
                } else {
                    Image(systemName: completed ? "checkmark.circle.fill" : "arrow.down.circle")
                        .font(.system(size: 20))
                        .foregroundStyle(completed ? Theme.mint : Theme.pink)
                }
                VStack(alignment: .leading, spacing: 3) {
                    Text(completed ? "全书已缓存" : state.isCaching ? "正在缓存 \(state.cachedCount) / \(chapters.count) 章（点击暂停）" : state.isPaused ? "缓存已暂停（点击继续）" : "缓存全书")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(Theme.plum)
                    Text(state.message ?? "已缓存 \(state.cachedCount) / \(chapters.count) 章，缓存后可离线阅读")
                        .font(.system(size: 11.5))
                        .foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
            }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.card, in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Theme.hairline, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .disabled(completed || chapters.isEmpty)
    }

    private var shelfKey: String { NovelBook.key(source: source, bookId: bookId) }
    private var isInShelf: Bool { shelf.contains(shelfKey) }
    private var shelfBook: ShelfBook? { shelf.book(shelfKey) }

    private var primaryActionTitle: String {
        guard let entry = shelfBook, entry.lastChapterIndex > 0 else { return "开始阅读" }
        return "续读 第\(entry.lastChapterIndex)章"
    }

    private func toggleShelf(_ detail: NovelBookDetail) {
        if isInShelf {
            shelf.remove(shelfKey)
        } else {
            shelf.add(detail)
        }
    }

    /// 起始章：有书架进度就带上进度章节，否则从首章开始。
    ///
    /// 单独抽成普通函数而不是写在 @ViewBuilder 里 —— ViewBuilder 会把 `let x: String?` +
    /// if/else 赋值当成视图条件语句解析，报 "type '()' cannot conform to 'View'"。
    private func entryChapterId(for detail: NovelBookDetail, fromShelfProgress: Bool) -> String? {
        if fromShelfProgress, let entry = shelfBook, !entry.lastChapterId.isEmpty {
            return entry.lastChapterId
        }
        return detail.firstChapterId ?? chapters.first?.chapterId
    }

    private func reader(for detail: NovelBookDetail, fromShelfProgress: Bool) -> some View {
        NovelReaderView(
            source: detail.source,
            bookId: detail.bookId,
            title: detail.title,
            author: detail.author,
            category: detail.category,
            chapterId: entryChapterId(for: detail, fromShelfProgress: fromShelfProgress)
        )
    }

    // MARK: - 简介

    private func intro(_ detail: NovelBookDetail) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            NovelSectionHeader(title: "内容简介")
            let text = (detail.intro ?? "源站没有提供简介").trimmingCharacters(in: .whitespacesAndNewlines)
            Text(text)
                .font(.system(size: 13))
                .foregroundStyle(Theme.plum.opacity(0.85))
                .lineSpacing(4)
                .lineLimit(introExpanded ? nil : 3)
                .frame(maxWidth: .infinity, alignment: .leading)

            if text.count > 60 {
                Button {
                    withAnimation(.easeInOut(duration: 0.2)) { introExpanded.toggle() }
                } label: {
                    Text(introExpanded ? "收起" : "展开全部")
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(Theme.pink)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.card, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Theme.hairline, lineWidth: 1))
    }

    // MARK: - 目录

    private var catalog: some View {
        VStack(alignment: .leading, spacing: 10) {
            NovelSectionHeader(title: "目录", subtitle: "共 \(chapters.count) 章")

            HStack(spacing: 8) {
                Button {
                    withAnimation(.easeInOut(duration: 0.2)) { reversedCatalog.toggle() }
                } label: {
                    HStack(spacing: 4) {
                        Image(systemName: "arrow.up.arrow.down")
                            .font(.system(size: 11))
                        Text(reversedCatalog ? "倒序" : "正序")
                            .font(.system(size: 11.5))
                    }
                    .foregroundStyle(Theme.plum.opacity(0.8))
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(Theme.card, in: Capsule())
                    .overlay(Capsule().strokeBorder(Theme.hairline, lineWidth: 1))
                }
                .buttonStyle(.plain)

                if let entry = shelfBook, entry.lastChapterIndex > 0 {
                    Text("上次读到 第\(entry.lastChapterIndex)章")
                        .font(.system(size: 11))
                        .foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
            }

            if chapters.isEmpty {
                if isLoading {
                    ProgressView()
                        .frame(maxWidth: .infinity, minHeight: 80)
                } else {
                    NovelStateView(kind: .empty, message: "目录为空")
                }
            } else {
                VStack(spacing: 0) {
                    ForEach(visibleChapters, id: \.offset) { entry in
                        NavigationLink {
                            NovelReaderView(
                                source: source,
                                bookId: bookId,
                                title: detail?.title ?? title,
                                author: detail?.author ?? author,
                                category: detail?.category ?? category,
                                chapterId: entry.item.chapterId
                            )
                        } label: {
                            chapterRow(entry: entry)
                        }
                        .buttonStyle(.plain)
                        Divider().padding(.leading, 46)
                    }
                }
                .padding(.horizontal, 12)
                .background(Theme.card, in: RoundedRectangle(cornerRadius: 12))
                .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Theme.hairline, lineWidth: 1))

                if visibleChapterCount < chapters.count {
                    Button {
                        visibleChapterCount += chapterBatch
                    } label: {
                        Text("显示更多（还有 \(chapters.count - visibleChapterCount) 章）")
                            .font(.system(size: 12.5, weight: .medium))
                            .foregroundStyle(Theme.pink)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 11)
                            .background(Theme.card, in: RoundedRectangle(cornerRadius: 10))
                            .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(Theme.hairline, lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    /// 目录项 = (原始下标, 章节)；倒序只影响显示，跳转仍按原始下标定位。
    private var visibleChapters: [(offset: Int, item: NovelChapterItem)] {
        let entries = chapters.enumerated().map { (offset: $0.offset, item: $0.element) }
        let ordered = reversedCatalog ? Array(entries.reversed()) : entries
        return Array(ordered.prefix(visibleChapterCount))
    }

    private func chapterRow(entry: (offset: Int, item: NovelChapterItem)) -> some View {
        let isCurrent = shelfBook?.lastChapterId == entry.item.chapterId
        return HStack(spacing: 10) {
            Text("\(entry.offset + 1)")
                .font(.system(size: 11, weight: .medium))
                .foregroundStyle(.secondary.opacity(0.6))
                .frame(width: 34, alignment: .trailing)
            Text(entry.item.title)
                .font(.system(size: 13.5))
                .foregroundStyle(isCurrent ? Theme.pink : Theme.plum.opacity(0.9))
                .lineLimit(1)
            Spacer(minLength: 0)
            if isCurrent {
                Text("上次")
                    .font(.system(size: 10, weight: .medium))
                    .foregroundStyle(Theme.pink)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(Theme.pink.opacity(0.14), in: Capsule())
            }
        }
        .padding(.vertical, 10)
        .contentShape(Rectangle())
    }

    // MARK: - 底部固定栏

    private var bottomBar: some View {
        HStack(spacing: 10) {
            Button {
                if let detail { toggleShelf(detail) }
            } label: {
                VStack(spacing: 3) {
                    Image(systemName: isInShelf ? "checkmark.circle.fill" : "plus.circle")
                        .font(.system(size: 18))
                    Text(isInShelf ? "已在书架" : "加入书架")
                        .font(.system(size: 10))
                }
                .foregroundStyle(isInShelf ? Theme.mint : Theme.plum.opacity(0.8))
                .frame(width: 76)
            }
            .buttonStyle(.plain)

            if let detail {
                NavigationLink {
                    reader(for: detail, fromShelfProgress: true)
                } label: {
                    Text(primaryActionTitle)
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 13)
                        .background(Theme.gradient, in: RoundedRectangle(cornerRadius: 12))
                }
                .buttonStyle(.plain)

                NavigationLink {
                    reader(for: detail, fromShelfProgress: false)
                } label: {
                    Text("从第一章")
                        .font(.system(size: 13, weight: .medium))
                        .foregroundStyle(Theme.plum)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 13)
                        .background(Theme.card, in: RoundedRectangle(cornerRadius: 12))
                        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Theme.hairline, lineWidth: 1))
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 14)
        .padding(.top, 10)
        .padding(.bottom, ScreenInsets.bottom + 8)
        .background(.ultraThinMaterial)
    }

    // MARK: - 加载

    private func load() async {
        guard detail == nil else { return }
        isLoading = true
        errorMessage = nil
        do {
            // 详情与目录没有依赖关系，并发拉；目录 limit=0 表示一次全量
            async let detailRequest = NovelService.bookDetail(source: source, bookId: bookId)
            async let chaptersRequest = NovelService.chapters(source: source, bookId: bookId)
            detail = try await detailRequest
            let catalog = try await chaptersRequest
            chapters = catalog.items
            NovelChapterCache.shared.storeCatalog(catalog, source: source, bookId: bookId)
        } catch {
            errorMessage = NovelError.describe(error)
        }
        cacheManager.refresh(source: source, bookId: bookId, totalCount: chapters.count)
        isLoading = false
    }
}
