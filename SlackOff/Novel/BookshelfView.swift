import SwiftUI
import UIKit

/// 书架：顶部「继续阅读」大卡片 + 三列封面网格，按最后阅读时间倒序，长按可移出。
struct BookshelfView: View {
    let onOpenBookstore: () -> Void

    @State private var shelf = BookshelfStore.shared

    private let columns = [
        GridItem(.flexible(), spacing: 12),
        GridItem(.flexible(), spacing: 12),
        GridItem(.flexible(), spacing: 12),
    ]

    var body: some View {
        Group {
            if shelf.books.isEmpty {
                emptyState
            } else {
                content
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Theme.cream)
    }

    private var content: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 16) {
                if let recent = shelf.mostRecent {
                    NavigationLink {
                        reader(for: recent)
                    } label: {
                        BookshelfContinueCard(book: recent)
                    }
                    .buttonStyle(.plain)
                }

                NovelSectionHeader(title: "我的书架", subtitle: "\(shelf.books.count) 本")

                LazyVGrid(columns: columns, spacing: 16) {
                    ForEach(shelf.sorted) { book in
                        NavigationLink {
                            reader(for: book)
                        } label: {
                            BookshelfCell(book: book)
                        }
                        .buttonStyle(.plain)
                        .contextMenu {
                            Button(role: .destructive) {
                                shelf.remove(book.id)
                            } label: {
                                Label("移出书架", systemImage: "trash")
                            }
                            Button {
                                // 复制书名，方便去别的源搜同一本书（book_id 跨源不通用）
                                UIPasteboard.general.string = book.title
                            } label: {
                                Label("复制书名", systemImage: "doc.on.doc")
                            }
                        }
                        // 注：swipeActions 只在 List 里生效，网格 cell 上不会响应，删除只走长按菜单
                    }
                }
            }
            .padding(.horizontal, 14)
            .padding(.bottom, 24)
        }
    }

    private func reader(for book: ShelfBook) -> some View {
        NovelReaderView(
            source: book.source,
            bookId: book.bookId,
            title: book.title,
            author: book.author,
            category: book.category,
            chapterId: book.lastChapterId.isEmpty ? nil : book.lastChapterId
        )
    }

    private var emptyState: some View {
        VStack(spacing: 14) {
            Image(systemName: "books.vertical")
                .font(.system(size: 46))
                .foregroundStyle(Theme.pink.opacity(0.6))
            Text("书架还是空的")
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(Theme.plum)
            Text("去书城挑一本，翻开就会自动加入书架")
                .font(.system(size: 12.5))
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            Button(action: onOpenBookstore) {
                Text("去逛逛书城")
                    .font(.system(size: 14, weight: .medium))
                    .padding(.horizontal, 22)
                    .padding(.vertical, 10)
                    .background(Theme.pink, in: Capsule())
                    .foregroundStyle(.white)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 40)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// 「继续阅读」大卡片的内容部分。
///
/// 单独拆出来（不含 NavigationLink）是为了可离屏渲染：书架页整体是
/// ScrollView + LazyVGrid，在 ImageRenderer 下拿不到 viewport 会渲染成空白。
struct BookshelfContinueCard: View {
    let book: ShelfBook

    var body: some View {
        HStack(spacing: 12) {
            NovelTextCover(shelf: book, cornerRadius: 7, showAuthor: false, titleScale: 0.16)
                .frame(width: 62)

            VStack(alignment: .leading, spacing: 5) {
                Text("继续阅读")
                    .font(.system(size: 10.5, weight: .semibold))
                    .foregroundStyle(Theme.pink)
                Text(book.title)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(Theme.plum)
                    .lineLimit(1)
                Text(book.lastChapterTitle.isEmpty ? book.progressText : book.lastChapterTitle)
                    .font(.system(size: 11.5))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)

                HStack(spacing: 6) {
                    // 自绘进度条：ProgressView 的 .tint 在 ImageRenderer 离屏渲染下不生效
                    // （截图里是系统默认黄），自绘 Capsule 既可控又更贴近夸克的细条样式。
                    GeometryReader { proxy in
                        ZStack(alignment: .leading) {
                            Capsule()
                                .fill(Theme.hairline)
                            Capsule()
                                .fill(Theme.pink)
                                .frame(width: max(proxy.size.width * book.percent, 4))
                        }
                    }
                    .frame(maxWidth: 120, maxHeight: 4)
                    Text(book.percentText)
                        .font(.system(size: 10.5, weight: .medium))
                        .foregroundStyle(.secondary)
                }
            }

            Spacer(minLength: 0)

            Image(systemName: "play.fill")
                .font(.system(size: 15))
                .foregroundStyle(.white)
                .frame(width: 36, height: 36)
                .background(Theme.pink, in: Circle())
        }
        .padding(12)
        .background(Theme.card, in: RoundedRectangle(cornerRadius: 14))
        .overlay(
            RoundedRectangle(cornerRadius: 14).strokeBorder(Theme.hairline, lineWidth: 1)
        )
    }
}

/// 书架格子的内容部分（封面 + 进度角标 + 书名 + 读至第几章）。
struct BookshelfCell: View {
    let book: ShelfBook

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            ZStack(alignment: .bottomTrailing) {
                NovelTextCover(shelf: book, cornerRadius: 7, showAuthor: false, titleScale: 0.15)
                Text(book.percentText)
                    .font(.system(size: 9, weight: .semibold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 5)
                    .padding(.vertical, 2)
                    .background(.black.opacity(0.55), in: Capsule())
                    .padding(4)
            }

            Text(book.title)
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(Theme.plum)
                .lineLimit(1)
            Text(book.progressText)
                .font(.system(size: 10))
                .foregroundStyle(.secondary)
                .lineLimit(1)
        }
    }
}

/// 书架视觉的离屏预览组合：非 lazy 布局，供渲染检查出图（产品页面仍用 LazyVGrid）。
struct BookshelfPreviewGrid: View {
    let books: [ShelfBook]

    private let columns = [
        GridItem(.flexible(), spacing: 12),
        GridItem(.flexible(), spacing: 12),
        GridItem(.flexible(), spacing: 12),
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            if let recent = books.first {
                BookshelfContinueCard(book: recent)
            }
            NovelSectionHeader(title: "我的书架", subtitle: "\(books.count) 本")
            // 渲染检查用非 lazy 的 VStack + HStack：LazyVGrid 在 ImageRenderer 下不出内容
            VStack(spacing: 16) {
                ForEach(books.chunked(into: 3), id: \.first?.id) { row in
                    HStack(alignment: .top, spacing: 12) {
                        ForEach(row) { book in
                            BookshelfCell(book: book)
                                .frame(maxWidth: .infinity)
                        }
                        ForEach(0..<(3 - row.count), id: \.self) { _ in
                            Color.clear.frame(maxWidth: .infinity)
                        }
                    }
                }
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 16)
    }
}

extension Array {
    /// 按固定大小切块（书架三列网格用）。
    func chunked(into size: Int) -> [[Element]] {
        guard size > 0 else { return [] }
        return stride(from: 0, to: count, by: size).map {
            Array(self[$0..<Swift.min($0 + size, count)])
        }
    }
}
