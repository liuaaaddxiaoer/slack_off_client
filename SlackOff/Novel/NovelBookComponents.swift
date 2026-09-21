import SwiftUI

/// 分类图标：两个源的 slug 命名完全不同（bqg99 是 `xuanhuan`，biquge365 是数字 `1~8`），
/// 所以按**中文名关键字**匹配图标，换源也不会掉成一堆同样的图标。
enum NovelCategoryIcon {
    static func systemImage(forName name: String, slug: String = "") -> String {
        let text = name + slug
        if text.contains("玄幻") || text.contains("奇幻") || text.contains("xuanhuan") { return "sparkles" }
        if text.contains("武侠") || text.contains("仙侠") || text.contains("修真") { return "figure.martial.arts" }
        if text.contains("都市") || text.contains("言情") || text.contains("现实") { return "building.2.fill" }
        if text.contains("历史") || text.contains("军事") { return "scroll.fill" }
        if text.contains("网游") || text.contains("游戏") || text.contains("动漫") { return "gamecontroller.fill" }
        // rocket.fill 在 iOS 26 已不存在（UIImage(systemName:) 返回 nil → 图标渲染为空），改用 atom
        if text.contains("科幻") || text.contains("灵异") || text.contains("恐怖") { return "atom" }
        if text.contains("女生") || text.contains("现言") || text.contains("古言") { return "heart.fill" }
        if text.contains("悬疑") || text.contains("推理") { return "magnifyingglass" }
        return "books.vertical.fill"
    }
}

/// 区块标题（可选「查看全部」）。
struct NovelSectionHeader: View {
    let title: String
    var subtitle: String?
    var actionTitle: String?
    var action: (() -> Void)?

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            RoundedRectangle(cornerRadius: 2)
                .fill(Theme.pink)
                .frame(width: 3, height: 14)
            Text(title)
                .font(.system(size: 17, weight: .bold))
                .foregroundStyle(Theme.plum)
            if let subtitle {
                Text(subtitle)
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
            if let actionTitle, let action {
                Button(action: action) {
                    HStack(spacing: 2) {
                        Text(actionTitle)
                            .font(.system(size: 12))
                        Image(systemName: "chevron.right")
                            .font(.system(size: 9, weight: .semibold))
                    }
                    .foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
            }
        }
    }
}

/// 竖向书籍卡片：文字封面 + 书名（2 行）+ 作者。书城横向流与网格共用。
struct NovelBookCard: View {
    let book: NovelBook
    var showAuthor = true

    var body: some View {
        NavigationLink {
            NovelBookDetailView(
                source: book.source,
                bookId: book.bookId,
                title: book.title,
                author: book.author,
                category: book.category
            )
        } label: {
            VStack(alignment: .leading, spacing: 6) {
                NovelTextCover(book: book, cornerRadius: 7)
                Text(book.title)
                    .font(.system(size: 12.5, weight: .medium))
                    .foregroundStyle(Theme.plum)
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if showAuthor, let author = book.author, !author.isEmpty {
                    Text(author)
                        .font(.system(size: 10.5))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                } else if let category = book.category, !category.isEmpty {
                    Text(category)
                        .font(.system(size: 10.5))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel(book.title)
            // UI 测试按 identifier 取第一张卡片，不依赖具体书名（源站推荐位每天在变）
            .accessibilityIdentifier("novel.card")
        }
        .buttonStyle(.plain)
    }
}

/// 横向书籍行：小封面 + 书名 + 作者/分类 + 最新章节 + 更新时间。
struct NovelBookRow: View {
    let book: NovelBook
    var showLatest = true

    var body: some View {
        NavigationLink {
            NovelBookDetailView(
                source: book.source,
                bookId: book.bookId,
                title: book.title,
                author: book.author,
                category: book.category
            )
        } label: {
            HStack(alignment: .top, spacing: 11) {
                NovelTextCover(book: book, cornerRadius: 5, showAuthor: false, titleScale: 0.16)
                    .frame(width: 56)

                VStack(alignment: .leading, spacing: 4) {
                    Text(book.title)
                        .font(.system(size: 14.5, weight: .semibold))
                        .foregroundStyle(Theme.plum)
                        .lineLimit(1)

                    HStack(spacing: 6) {
                        if let author = book.author, !author.isEmpty {
                            Text(author)
                                .lineLimit(1)
                        }
                        if let category = book.category, !category.isEmpty {
                            Text(category)
                                .padding(.horizontal, 5)
                                .padding(.vertical, 1)
                                .background(Theme.pink.opacity(0.14), in: Capsule())
                                .foregroundStyle(Theme.pink)
                                .lineLimit(1)
                        }
                    }
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)

                    if showLatest, let latest = book.latestChapter, !latest.isEmpty {
                        Text("最新：\(latest)")
                            .font(.system(size: 11))
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    } else if let intro = book.intro, !intro.isEmpty {
                        Text(intro)
                            .font(.system(size: 11))
                            .foregroundStyle(.secondary)
                            .lineLimit(2)
                    }

                    if let time = book.updateTime, !time.isEmpty {
                        Text(time)
                            .font(.system(size: 10))
                            .foregroundStyle(.tertiary)
                    }
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(.quaternary)
                    .padding(.top, 4)
            }
            .padding(.vertical, 9)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// 排行榜条目：前 3 名序号用品牌粉高亮。
struct NovelRankRow: View {
    let source: String
    let entry: NovelRankEntry

    var body: some View {
        NavigationLink {
            NovelBookDetailView(
                source: source,
                bookId: entry.bookId,
                title: entry.title,
                author: entry.author,
                category: entry.category
            )
        } label: {
            HStack(spacing: 11) {
                Text("\(entry.rank ?? 0)")
                    .font(.system(size: 15, weight: .heavy, design: .rounded))
                    .foregroundStyle(isTop ? Theme.pink : Color.secondary.opacity(0.55))
                    .frame(width: 26, alignment: .center)

                VStack(alignment: .leading, spacing: 3) {
                    Text(entry.title)
                        .font(.system(size: 14, weight: .medium))
                        .foregroundStyle(Theme.plum)
                        .lineLimit(1)
                    if let author = entry.author, !author.isEmpty {
                        Text(author)
                            .font(.system(size: 11))
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    } else if let category = entry.category, !category.isEmpty {
                        Text(category)
                            .font(.system(size: 11))
                            .foregroundStyle(.secondary)
                    }
                }

                Spacer(minLength: 0)

                if let category = entry.category, !category.isEmpty, entry.author != nil {
                    Text(category)
                        .font(.system(size: 10))
                        .foregroundStyle(.secondary)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(Theme.hairline, in: Capsule())
                }
            }
            .padding(.vertical, 8)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private var isTop: Bool { (entry.rank ?? 99) <= 3 }
}

/// 统一的空态 / 错误态视图（书城各区块与列表页共用）。
struct NovelStateView: View {
    enum Kind {
        case empty
        case error
    }

    let kind: Kind
    let message: String
    var retryTitle: String = "重试"
    var onRetry: (() -> Void)?

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: kind == .error ? "exclamationmark.triangle" : "books.vertical")
                .font(.system(size: 30))
                .foregroundStyle(.secondary.opacity(0.6))
            Text(message)
                .font(.system(size: 13))
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 28)
            if let onRetry {
                Button(action: onRetry) {
                    Text(retryTitle)
                        .font(.system(size: 13, weight: .medium))
                        .padding(.horizontal, 20)
                        .padding(.vertical, 8)
                        .background(Theme.pink, in: Capsule())
                        .foregroundStyle(.white)
                }
                .buttonStyle(.plain)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 34)
    }
}
