import SwiftUI
import UIKit

/// 站内搜索：搜索框 + 历史 + 空态热门推荐 + 分页结果。
///
/// 只有 bqg99 支持站内搜索（blqvdu / biquge365 源站没有搜索页），
/// 所以这里固定用 `auto`：服务端会自动落到支持搜索的源。
struct NovelSearchView: View {
    @State private var keyword = ""
    @State private var results: [NovelBook] = []
    @State private var page = 1
    @State private var hasMore = false
    @State private var isSearching = false
    @State private var errorMessage: String?
    @State private var hasSearched = false
    @State private var resolvedSource = ""
    @State private var history: [String] = NovelSearchView.loadHistory()
    @State private var hotBooks: [NovelBook] = []
    @FocusState private var isFocused: Bool

    private let historyKey = "com.slackoff.novel-search-history"

    var body: some View {
        VStack(spacing: 0) {
            searchBar
            Divider()
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 16) {
                    if isSearching && results.isEmpty {
                        ProgressView("搜索中…")
                            .frame(maxWidth: .infinity, minHeight: 200)
                            .foregroundStyle(.secondary)
                    } else if let errorMessage, results.isEmpty {
                        NovelStateView(kind: .error, message: errorMessage) {
                            Task { await search(reset: true) }
                        }
                    } else if hasSearched && results.isEmpty {
                        NovelStateView(kind: .empty, message: "没有找到「\(keyword)」相关的书")
                    } else if results.isEmpty {
                        idleState
                    } else {
                        resultHeader
                        VStack(spacing: 0) {
                            ForEach(results) { book in
                                NovelBookRow(book: book)
                                Divider().padding(.leading, 67)
                            }
                        }
                        .padding(.horizontal, 12)
                        .background(Theme.card, in: RoundedRectangle(cornerRadius: 12))
                        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Theme.hairline, lineWidth: 1))
                        .padding(.horizontal, 14)

                        if hasMore {
                            ProgressView()
                                .frame(maxWidth: .infinity, minHeight: 56)
                                .task { await search(reset: false) }
                        } else {
                            Text("已经到底了")
                                .font(.system(size: 11))
                                .foregroundStyle(.tertiary)
                                .frame(maxWidth: .infinity, minHeight: 46)
                        }
                    }
                }
                .padding(.vertical, 12)
            }
        }
        .background(Theme.cream)
        .navigationTitle("搜索")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(Theme.softPink.opacity(0.4), for: .navigationBar)
        .task { await loadHotBooks() }
    }

    // MARK: - 搜索框

    private var searchBar: some View {
        HStack(spacing: 10) {
            HStack(spacing: 7) {
                Image(systemName: "magnifyingglass")
                    .font(.system(size: 13.5))
                    .foregroundStyle(.secondary)
                TextField("书名或作者", text: $keyword)
                    .font(.system(size: 14))
                    .focused($isFocused)
                    .submitLabel(.search)
                    .onSubmit { Task { await search(reset: true) } }
                if !keyword.isEmpty {
                    Button {
                        keyword = ""
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.system(size: 14))
                            .foregroundStyle(.secondary.opacity(0.6))
                    }
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 9)
            .background(Theme.card, in: Capsule())
            .overlay(Capsule().strokeBorder(Theme.hairline, lineWidth: 1))

            Button {
                if isFocused && !keyword.isEmpty {
                    Task { await search(reset: true) }
                } else {
                    isFocused = false
                    dismiss()
                }
            } label: {
                Text(isFocused ? "搜索" : "取消")
                    .font(.system(size: 14, weight: .medium))
                    .foregroundStyle(isFocused ? Theme.pink : Color.secondary)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 9)
    }

    @Environment(\.dismiss) private var dismiss

    // MARK: - 空态（历史 + 热门）

    private var idleState: some View {
        VStack(alignment: .leading, spacing: 18) {
            if !history.isEmpty {
                VStack(alignment: .leading, spacing: 10) {
                    HStack {
                        NovelSectionHeader(title: "搜索历史")
                        Spacer()
                        Button {
                            history = []
                            UserDefaults.standard.removeObject(forKey: historyKey)
                        } label: {
                            Image(systemName: "trash")
                                .font(.system(size: 13))
                                .foregroundStyle(.secondary)
                        }
                    }

                    FlowLayout(spacing: 8) {
                        ForEach(history, id: \.self) { item in
                            Button {
                                keyword = item
                                Task { await search(reset: true) }
                            } label: {
                                Text(item)
                                    .font(.system(size: 12.5))
                                    .foregroundStyle(Theme.plum)
                                    .padding(.horizontal, 12)
                                    .padding(.vertical, 7)
                                    .background(Theme.card, in: Capsule())
                                    .overlay(Capsule().strokeBorder(Theme.hairline, lineWidth: 1))
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
                .padding(.horizontal, 14)
            }

            VStack(alignment: .leading, spacing: 10) {
                NovelSectionHeader(title: "大家都在看", subtitle: resolvedSource.isEmpty ? nil : resolvedSource)
                    .padding(.horizontal, 14)

                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(spacing: 12) {
                        ForEach(hotBooks) { book in
                            NovelBookCard(book: book)
                                .frame(width: 96)
                        }
                    }
                    .padding(.horizontal, 14)
                }
            }

            if hotBooks.isEmpty {
                Text("输入书名或作者开始搜索\n只有顶点小说网（bqg99）提供站内搜索")
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity)
                    .padding(.top, 30)
            }
        }
    }

    private var resultHeader: some View {
        HStack {
            Text("找到 \(results.count)\(hasMore ? "+" : "") 本")
                .font(.system(size: 12))
                .foregroundStyle(.secondary)
            Spacer()
            if !resolvedSource.isEmpty {
                Text("源：\(resolvedSource)")
                    .font(.system(size: 10.5))
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal, 16)
    }

    // MARK: - 请求

    private func search(reset: Bool) async {
        let query = keyword.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty, !isSearching else { return }

        if reset {
            page = 1
            hasMore = false
            errorMessage = nil
            isFocused = false
            rememberHistory(query)
        } else {
            guard hasMore else { return }
        }

        isSearching = true
        do {
            let result = try await NovelService.search(
                keyword: query,
                page: reset ? 1 : page + 1,
                source: "auto"
            )
            if reset {
                results = result.books
            } else {
                let existing = Set(results.map(\.id))
                results.append(contentsOf: result.books.filter { !existing.contains($0.id) })
            }
            page = result.page ?? (reset ? 1 : page + 1)
            hasMore = result.more
            resolvedSource = result.source
            hasSearched = true
        } catch {
            errorMessage = NovelError.describe(error)
            hasSearched = true
        }
        isSearching = false
    }

    private func loadHotBooks() async {
        guard hotBooks.isEmpty else { return }
        // 空态推荐直接借首页的封面推荐位，一次请求，失败就留空（不影响搜索本身）
        if let home = try? await NovelService.home(source: NovelSettings.shared.sourceChoice) {
            hotBooks = home.hot.isEmpty ? home.recommend : home.hot
            resolvedSource = home.source
        }
    }

    private func rememberHistory(_ query: String) {
        var updated = history.filter { $0 != query }
        updated.insert(query, at: 0)
        history = Array(updated.prefix(10))
        UserDefaults.standard.set(history, forKey: historyKey)
    }

    static func loadHistory() -> [String] {
        UserDefaults.standard.stringArray(forKey: "com.slackoff.novel-search-history") ?? []
    }
}

/// 简易流式布局（搜索历史标签换行用）。iOS 16+ 有原生 Layout 协议，自己写十行就够，
/// 不必为此引第三方库。
struct FlowLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        var x: CGFloat = 0
        var y: CGFloat = 0
        var rowHeight: CGFloat = 0

        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x + size.width > maxWidth, x > 0 {
                x = 0
                y += rowHeight + spacing
                rowHeight = 0
            }
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
        return CGSize(width: maxWidth == .infinity ? x : maxWidth, height: y + rowHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX
        var y = bounds.minY
        var rowHeight: CGFloat = 0

        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x + size.width > bounds.maxX, x > bounds.minX {
                x = bounds.minX
                y += rowHeight + spacing
                rowHeight = 0
            }
            subview.place(at: CGPoint(x: x, y: y), proposal: .unspecified)
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
    }
}
