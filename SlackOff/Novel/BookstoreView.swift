import SwiftUI

/// 书城：搜索胶囊 → 金刚区分类 → Banner 轮播 → 强力推荐 → 排行榜 → 分类精选 → 最近更新。
struct BookstoreView: View {
    let onOpenShelf: () -> Void

    @State private var model = BookstoreModel()
    @State private var settings = NovelSettings.shared
    @State private var bannerIndex = 0
    @State private var bannerTask: Task<Void, Never>?

    /// 最近更新在书城首屏只显示这么多条，其余进本地列表页看。
    private let latestPreviewCount = 15

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 20) {
                searchCapsule

                if model.isLoading && model.home == nil {
                    ProgressView("正在回源加载…")
                        .frame(maxWidth: .infinity, minHeight: 200)
                        .foregroundStyle(.secondary)
                } else if let errorMessage = model.errorMessage, model.home == nil {
                    NovelStateView(kind: .error, message: errorMessage) {
                        Task { await model.load(force: true) }
                    }
                } else if model.home != nil {
                    categoryGrid
                    bannerCarousel
                    recommendSection
                    ranksSection
                    categoryBlockSections
                    latestSection
                }
            }
            .padding(.horizontal, 14)
            .padding(.bottom, 28)
        }
        .background(Theme.cream)
        .refreshable { await model.load(force: true) }
        .task { await model.load() }
        .onDisappear { stopBannerRotation() }
    }

    // MARK: - 搜索

    private var searchCapsule: some View {
        NavigationLink {
            NovelSearchView()
        } label: {
            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass")
                    .font(.system(size: 14))
                    .foregroundStyle(.secondary)
                Text("搜索书名 / 作者")
                    .font(.system(size: 13.5))
                    .foregroundStyle(.secondary)
                Spacer(minLength: 0)
                Text("搜索")
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 5)
                    .background(Theme.pink, in: Capsule())
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 9)
            .background(Theme.card, in: Capsule())
            .overlay(Capsule().strokeBorder(Theme.hairline, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .padding(.top, 4)
    }

    // MARK: - 金刚区

    /// 7 个分类（来自 /api/categories）+ 排行 + 全本，共 9 个入口。
    private var categoryGrid: some View {
        let entries = quickEntries
        return LazyVGrid(
            columns: Array(repeating: GridItem(.flexible(), spacing: 6), count: 5),
            spacing: 14
        ) {
            ForEach(entries) { entry in
                NavigationLink {
                    destination(for: entry)
                } label: {
                    VStack(spacing: 6) {
                        ZStack {
                            Circle()
                                .fill(entry.tint.opacity(0.16))
                                .frame(width: 44, height: 44)
                            Image(systemName: entry.icon)
                                .font(.system(size: 18))
                                .foregroundStyle(entry.tint)
                        }
                        Text(entry.title)
                            .font(.system(size: 10.5))
                            .foregroundStyle(Theme.plum)
                            .lineLimit(1)
                    }
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.vertical, 4)
    }

    private struct QuickEntry: Identifiable {
        enum Target {
            case category(slug: String, name: String)
            case ranks
            case full
        }

        let id: String
        let title: String
        let icon: String
        let tint: Color
        let target: Target
    }

    private static let quickTints: [Color] = [
        Theme.pink, Theme.peach, Theme.lavender, Theme.mint,
        Theme.pink, Theme.peach, Theme.lavender,
    ]

    private var quickEntries: [QuickEntry] {
        var entries: [QuickEntry] = model.categories.prefix(7).enumerated().map { index, category in
            QuickEntry(
                id: "cat-\(category.slug)",
                title: category.name,
                icon: NovelCategoryIcon.systemImage(forName: category.name, slug: category.slug),
                tint: Self.quickTints[index % Self.quickTints.count],
                target: .category(slug: category.slug, name: category.name)
            )
        }
        entries.append(
            QuickEntry(
                id: "ranks",
                title: "排行",
                icon: "trophy.fill",
                tint: Theme.peach,
                target: .ranks
            )
        )
        entries.append(
            QuickEntry(
                id: "full",
                title: "全本",
                icon: "checkmark.seal.fill",
                tint: Theme.mint,
                target: .full
            )
        )
        return entries
    }

    @ViewBuilder
    private func destination(for entry: QuickEntry) -> some View {
        switch entry.target {
        case .category(let slug, let name):
            NovelPagedBookListView(kind: .category(slug: slug, name: name), sourceOverride: model.resolvedSource)
        case .ranks:
            NovelRanksView(sourceOverride: model.resolvedSource, preloaded: model.ranks)
        case .full:
            NovelPagedBookListView(kind: .full, sourceOverride: model.resolvedSource)
        }
    }

    // MARK: - Banner 轮播

    private var bannerCarousel: some View {
        VStack(alignment: .leading, spacing: 10) {
            NovelSectionHeader(title: "今日推荐", subtitle: model.siteName)

            if model.hot.isEmpty {
                Text("源站首页没给出封面推荐位")
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, minHeight: 100)
            } else {
                TabView(selection: $bannerIndex) {
                    ForEach(Array(model.hot.enumerated()), id: \.element.id) { index, book in
                        NavigationLink {
                            NovelBookDetailView(
                                source: book.source,
                                bookId: book.bookId,
                                title: book.title,
                                author: book.author,
                                category: book.category
                            )
                        } label: {
                            bannerCard(book)
                        }
                        .buttonStyle(.plain)
                        .tag(index)
                    }
                }
                .tabViewStyle(.page(indexDisplayMode: .always))
                .indexViewStyle(.page(backgroundDisplayMode: .interactive))
                .frame(height: 132)
                .onAppear { startBannerRotation() }
            }
        }
    }

    private func bannerCard(_ book: NovelBook) -> some View {
        HStack(alignment: .top, spacing: 12) {
            NovelTextCover(book: book, cornerRadius: 8, showAuthor: false, titleScale: 0.17)
                .frame(width: 78)

            VStack(alignment: .leading, spacing: 6) {
                Text(book.title)
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(Theme.plum)
                    .lineLimit(1)

                if let author = book.author, !author.isEmpty {
                    Text(author)
                        .font(.system(size: 11.5))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }

                Text(book.intro ?? "源站没给这本书写简介")
                    .font(.system(size: 11.5))
                    .foregroundStyle(.secondary)
                    .lineLimit(3)
                    .multilineTextAlignment(.leading)

                Spacer(minLength: 0)

                Text("立即阅读")
                    .font(.system(size: 11.5, weight: .medium))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 5)
                    .background(Theme.pink, in: Capsule())
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(12)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Theme.card, in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(Theme.hairline, lineWidth: 1))
        .padding(.horizontal, 2)
    }

    /// 5s 自动轮播；用户手滑时也会被下一轮接管，所以只在页面可见期间跑。
    private func startBannerRotation() {
        stopBannerRotation()
        guard model.hot.count > 1 else { return }
        bannerTask = Task {
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(5))
                guard !Task.isCancelled else { return }
                let count = model.hot.count
                guard count > 1 else { continue }
                withAnimation(.easeInOut(duration: 0.35)) {
                    bannerIndex = (bannerIndex + 1) % count
                }
            }
        }
    }

    private func stopBannerRotation() {
        bannerTask?.cancel()
        bannerTask = nil
    }

    // MARK: - 强力推荐

    private var recommendSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            NovelSectionHeader(title: "强力推荐")

            if model.recommend.isEmpty {
                Text("当前源没有强力推荐位")
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(spacing: 12) {
                        ForEach(model.recommend) { book in
                            NovelBookCard(book: book)
                                .frame(width: 96)
                        }
                    }
                    .padding(.vertical, 2)
                }
            }
        }
    }

    // MARK: - 排行榜（懒加载）

    private var ranksSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            NovelSectionHeader(title: "排行榜")

            RanksInlineBlock(model: model)
        }
        .task { await model.loadRanksIfNeeded() }
    }

    // MARK: - 分类精选

    private var categoryBlockSections: some View {
        ForEach(model.blocks) { block in
            VStack(alignment: .leading, spacing: 10) {
                NovelSectionHeader(title: block.name)

                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(spacing: 12) {
                        ForEach(block.items) { book in
                            NovelBookCard(book: book)
                                .frame(width: 96)
                        }
                    }
                    .padding(.vertical, 2)
                }

                // 「查看全部」：能匹配到分类 slug 就走分页列表，否则退化成块内本地列表
                if let slug = slug(forBlockName: block.name) {
                    NavigationLink {
                        NovelPagedBookListView(
                            kind: .category(slug: slug, name: block.name),
                            sourceOverride: model.resolvedSource
                        )
                    } label: {
                        seeAllLabel
                    }
                    .buttonStyle(.plain)
                } else {
                    NavigationLink {
                        NovelLocalBookListView(title: block.name, books: block.items)
                    } label: {
                        seeAllLabel
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    private var seeAllLabel: some View {
        HStack {
            Spacer()
            Text("查看全部")
                .font(.system(size: 12))
                .foregroundStyle(.secondary)
            Image(systemName: "chevron.right")
                .font(.system(size: 9, weight: .semibold))
                .foregroundStyle(.secondary)
            Spacer()
        }
        .padding(.vertical, 8)
        .background(Theme.card, in: RoundedRectangle(cornerRadius: 9))
        .overlay(RoundedRectangle(cornerRadius: 9).strokeBorder(Theme.hairline, lineWidth: 1))
    }

    /// 首页分类块的 name（「玄幻奇幻」）与分类字典的 name（「玄幻」）不完全一致，
    /// 所以先精确匹配，再做包含匹配；都匹配不上就返回 nil，走本地列表降级。
    private func slug(forBlockName name: String) -> String? {
        if let exact = model.categories.first(where: { $0.name == name }) { return exact.slug }
        return model.categories.first { name.contains($0.name) || $0.name.contains(name) }?.slug
    }

    // MARK: - 最近更新

    private var latestSection: some View {
        VStack(alignment: .leading, spacing: 6) {
            NovelSectionHeader(title: "最近更新", subtitle: "\(model.latest.count) 本")

            VStack(spacing: 0) {
                ForEach(model.latest.prefix(latestPreviewCount)) { book in
                    NovelBookRow(book: book)
                    Divider().padding(.leading, 67)
                }
            }
            .padding(.horizontal, 12)
            .background(Theme.card, in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Theme.hairline, lineWidth: 1))

            if model.latest.count > latestPreviewCount {
                NavigationLink {
                    NovelLocalBookListView(title: "最近更新", books: model.latest)
                } label: {
                    seeAllLabel
                }
                .buttonStyle(.plain)
            }
        }
    }
}

/// 排行榜内联区块：横向切榜 + 前 10 条 + 「查看全部」。
private struct RanksInlineBlock: View {
    let model: BookstoreModel
    @State private var boardIndex = 0

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if model.isRanksLoading && model.ranks.isEmpty {
                ProgressView()
                    .frame(maxWidth: .infinity, minHeight: 120)
            } else if model.ranks.isEmpty {
                NovelStateView(kind: .error, message: "榜单加载失败（源站排行页偶发不可用）") {
                    Task { await model.reloadRanks() }
                }
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(Array(model.ranks.enumerated()), id: \.element.id) { index, board in
                            Button {
                                withAnimation(.easeInOut(duration: 0.18)) { boardIndex = index }
                            } label: {
                                Text(board.board)
                                    .font(.system(size: 12, weight: boardIndex == index ? .semibold : .regular))
                                    .foregroundStyle(boardIndex == index ? .white : Theme.plum)
                                    .padding(.horizontal, 12)
                                    .padding(.vertical, 6)
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
                }

                let board = model.ranks[min(boardIndex, model.ranks.count - 1)]
                VStack(spacing: 0) {
                    ForEach(board.entries.prefix(10)) { entry in
                        NovelRankRow(source: model.resolvedSource, entry: entry)
                        Divider().padding(.leading, 37)
                    }
                }
                .padding(.horizontal, 12)
                .background(Theme.card, in: RoundedRectangle(cornerRadius: 12))
                .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Theme.hairline, lineWidth: 1))

                NavigationLink {
                    NovelRanksView(sourceOverride: model.resolvedSource, preloaded: model.ranks)
                } label: {
                    HStack {
                        Spacer()
                        Text("查看全部榜单")
                            .font(.system(size: 12))
                            .foregroundStyle(.secondary)
                        Image(systemName: "chevron.right")
                            .font(.system(size: 9, weight: .semibold))
                            .foregroundStyle(.secondary)
                        Spacer()
                    }
                    .padding(.vertical, 8)
                    .background(Theme.card, in: RoundedRectangle(cornerRadius: 9))
                    .overlay(RoundedRectangle(cornerRadius: 9).strokeBorder(Theme.hairline, lineWidth: 1))
                }
                .buttonStyle(.plain)
            }
        }
    }
}
