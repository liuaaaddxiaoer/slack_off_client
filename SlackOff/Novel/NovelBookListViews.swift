import SwiftUI

/// 分页书籍列表：分类页 / 全本页 / 搜索结果共用。
///
/// 翻页由响应的 `has_more` 驱动：bqg99 与 blqvdu 的分类页源站不给翻页（单页固定 30 条，
/// `has_more` 恒为 false），biquge365 能一路翻到 1769 页，同一套代码自然适配。
struct NovelPagedBookListView: View {
    enum Kind: Equatable {
        case category(slug: String, name: String)
        case full
        case search(keyword: String)

        var navigationTitle: String {
            switch self {
            case .category(_, let name): name
            case .full: "全本小说"
            case .search(let keyword): "“\(keyword)”"
            }
        }
    }

    let kind: Kind
    /// 列表请求用的源。分类 slug 跨源不通用，必须由书城把「活跃源」传下来。
    let sourceOverride: String

    @State private var books: [NovelBook] = []
    @State private var page = 1
    @State private var hasMore = false
    @State private var isLoading = false
    @State private var errorMessage: String?
    @State private var resolvedSource: String = ""
    @State private var totalText: String?

    var body: some View {
        ScrollView {
            LazyVStack(spacing: 0) {
                if isLoading && books.isEmpty {
                    ProgressView("加载中…")
                        .frame(maxWidth: .infinity, minHeight: 220)
                        .foregroundStyle(.secondary)
                } else if let errorMessage, books.isEmpty {
                    NovelStateView(kind: .error, message: errorMessage) {
                        Task { await load(reset: true) }
                    }
                } else if books.isEmpty {
                    NovelStateView(kind: .empty, message: "这个列表是空的")
                } else {
                    header
                    VStack(spacing: 0) {
                        ForEach(books) { book in
                            NovelBookRow(book: book)
                            Divider().padding(.leading, 67)
                        }
                    }
                    .padding(.horizontal, 12)
                    .background(Theme.card, in: RoundedRectangle(cornerRadius: 12))
                    .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Theme.hairline, lineWidth: 1))
                    .padding(.horizontal, 14)

                    loadMoreFooter
                }
            }
            .padding(.vertical, 12)
        }
        .background(Theme.cream)
        .navigationTitle(kind.navigationTitle)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(Theme.softPink.opacity(0.4), for: .navigationBar)
        .task { await load(reset: true) }
    }

    private var header: some View {
        HStack(spacing: 8) {
            Text("共 \(books.count)\(hasMore ? "+" : "") 本")
                .font(.system(size: 12))
                .foregroundStyle(.secondary)
            if let totalText {
                Text(totalText)
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
            }
            Spacer()
            if !resolvedSource.isEmpty {
                Text(resolvedSource)
                    .font(.system(size: 10, weight: .medium))
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 7)
                    .padding(.vertical, 3)
                    .background(Theme.hairline, in: Capsule())
            }
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 8)
    }

    /// 末尾哨兵：滚到就自动拉下一页。
    private var loadMoreFooter: some View {
        Group {
            if hasMore {
                ProgressView()
                    .frame(maxWidth: .infinity, minHeight: 56)
                    .task { await load(reset: false) }
            } else if !books.isEmpty {
                Text("已经到底了")
                    .font(.system(size: 11))
                    .foregroundStyle(.tertiary)
                    .frame(maxWidth: .infinity, minHeight: 56)
            }
        }
    }

    private func load(reset: Bool) async {
        guard !isLoading else { return }
        if reset {
            page = 1
            hasMore = false
            errorMessage = nil
        } else {
            guard hasMore else { return }
        }

        isLoading = true
        let requestPage = reset ? 1 : page + 1
        do {
            let result: NovelBookPage
            switch kind {
            case .category(let slug, _):
                result = try await NovelService.categoryBooks(slug: slug, page: requestPage, source: sourceOverride)
            case .full:
                result = try await NovelService.fullBooks(page: requestPage, source: sourceOverride)
            case .search(let keyword):
                result = try await NovelService.search(keyword: keyword, page: requestPage, source: sourceOverride)
            }

            let incoming = result.books
            if reset {
                books = incoming
            } else {
                // 源站偶尔会把同一本书在不同页重复给出，按 id 去重再追加
                let existing = Set(books.map(\.id))
                books.append(contentsOf: incoming.filter { !existing.contains($0.id) })
            }
            page = result.page ?? requestPage
            hasMore = result.more
            resolvedSource = result.source
            if let total = result.total { totalText = "/ \(total) 本" }
            else if let totalPages = result.totalPages { totalText = "/ \(totalPages) 页" }
        } catch {
            errorMessage = NovelError.describe(error)
        }
        isLoading = false
    }
}

/// 排行榜页：横向切榜 + 每榜完整 15 条。
///
/// `/api/ranks` 的响应里不带 source，而 book_id 跨源不通用，
/// 所以源必须由调用方（书城）显式传入，不能在这里用 auto 再猜一次。
struct NovelRanksView: View {
    let sourceOverride: String
    var preloaded: [NovelRankBoard] = []

    @State private var boards: [NovelRankBoard] = []
    @State private var boardIndex = 0
    @State private var isLoading = false
    @State private var errorMessage: String?

    var body: some View {
        Group {
            if isLoading && boards.isEmpty {
                ProgressView("正在抓 8 个榜单…")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .foregroundStyle(.secondary)
            } else if let errorMessage, boards.isEmpty {
                NovelStateView(kind: .error, message: errorMessage) {
                    Task { await load() }
                }
            } else if boards.isEmpty {
                NovelStateView(kind: .empty, message: "暂无榜单数据")
            } else {
                content
            }
        }
        .background(Theme.cream)
        .navigationTitle("排行榜")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(Theme.softPink.opacity(0.4), for: .navigationBar)
        .task {
            if preloaded.isEmpty { await load() } else { boards = preloaded }
        }
    }

    private var content: some View {
        VStack(spacing: 0) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(Array(boards.enumerated()), id: \.element.id) { index, board in
                        Button {
                            withAnimation(.easeInOut(duration: 0.18)) { boardIndex = index }
                        } label: {
                            Text(board.board)
                                .font(.system(size: 12.5, weight: boardIndex == index ? .semibold : .regular))
                                .foregroundStyle(boardIndex == index ? .white : Theme.plum)
                                .padding(.horizontal, 13)
                                .padding(.vertical, 7)
                                .background(
                                    boardIndex == index ? AnyShapeStyle(Theme.pink) : AnyShapeStyle(Theme.card),
                                    in: Capsule()
                                )
                                .overlay(
                                    Capsule().strokeBorder(
                                        boardIndex == index ? Color.clear : Theme.hairline,
                                        lineWidth: 1
                                    )
                                )
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
            }

            let board = boards[min(boardIndex, boards.count - 1)]
            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(board.entries) { entry in
                        NovelRankRow(source: sourceOverride, entry: entry)
                            .padding(.horizontal, 14)
                        Divider().padding(.leading, 51)
                    }
                }
                .padding(.vertical, 6)
            }
        }
    }

    private func load() async {
        isLoading = true
        errorMessage = nil
        do {
            boards = try await NovelService.ranks(source: sourceOverride)
        } catch {
            errorMessage = NovelError.describe(error)
        }
        isLoading = false
    }
}

/// 本地列表页：数据已经在手上（首页的最近更新、分类精选块），只做展示，不再请求。
struct NovelLocalBookListView: View {
    let title: String
    let books: [NovelBook]

    var body: some View {
        ScrollView {
            LazyVStack(spacing: 0) {
                HStack {
                    Text("共 \(books.count) 本")
                        .font(.system(size: 12))
                        .foregroundStyle(.secondary)
                    Spacer()
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 8)

                VStack(spacing: 0) {
                    ForEach(books) { book in
                        NovelBookRow(book: book)
                        Divider().padding(.leading, 67)
                    }
                }
                .padding(.horizontal, 12)
                .background(Theme.card, in: RoundedRectangle(cornerRadius: 12))
                .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Theme.hairline, lineWidth: 1))
                .padding(.horizontal, 14)
            }
            .padding(.vertical, 12)
        }
        .background(Theme.cream)
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(Theme.softPink.opacity(0.4), for: .navigationBar)
    }
}
