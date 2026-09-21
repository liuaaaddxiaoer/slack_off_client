import Foundation

/// 书架上的一本书 + 精确阅读进度。
///
/// 进度粒度：章节 id / 章节序号 / 章内位置（0~1，页序比例）/ 全书百分比。
/// 章内位置让「继续阅读」能精确回到上次那一页，而不只是那一章。
struct ShelfBook: Codable, Identifiable, Hashable {
    let source: String
    let bookId: String
    var title: String
    var author: String?
    var category: String?

    var lastChapterId: String
    var lastChapterTitle: String
    /// 1-based 章节序号，与目录里的 index 对齐
    var lastChapterIndex: Int
    /// 目录总章数，用于算全书百分比；未知时为 0
    var totalChapters: Int
    /// 章内位置 0~1
    var positionInChapter: Double
    var updatedAt: Date

    var id: String { NovelBook.key(source: source, bookId: bookId) }

    /// 全书进度 0~1。总章数未知时退化为「章内位置」。
    var percent: Double {
        guard totalChapters > 0 else { return positionInChapter }
        let done = Double(max(lastChapterIndex - 1, 0)) + positionInChapter
        return min(max(done / Double(totalChapters), 0), 1)
    }

    var percentText: String { "\(Int((percent * 100).rounded()))%" }

    /// 书架格子上的「读至 第x章」文案。
    var progressText: String {
        if lastChapterIndex > 0 { return "读至 第\(lastChapterIndex)章" }
        return lastChapterTitle.isEmpty ? "尚未开始" : "读至 \(lastChapterTitle)"
    }
}

/// 书架与阅读进度的本地存储（UserDefaults，与 EpisodeStore 同风格）。
///
/// 用 @Observable 而非纯静态 enum：书架页需要在阅读器退出后立刻刷新，
/// 书城/详情页的「已在书架」状态也要跟着变。
@Observable
final class BookshelfStore {
    static let shared = BookshelfStore()

    static let defaultStorageKey = "com.slackoff.novel-shelf"
    private let storageKey: String
    /// 可注入的 UserDefaults：单测用独立 suite，避免污染真机书架数据。
    private let defaults: UserDefaults
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    private(set) var books: [ShelfBook] = []

    init(
        defaults: UserDefaults = .standard,
        storageKey: String = BookshelfStore.defaultStorageKey
    ) {
        self.defaults = defaults
        self.storageKey = storageKey
        guard
            let data = defaults.data(forKey: storageKey),
            let decoded = try? decoder.decode([ShelfBook].self, from: data)
        else { return }
        books = decoded.sorted { $0.updatedAt > $1.updatedAt }
    }

    // MARK: 查询

    /// 按最后阅读时间倒序（书架页直接用）。
    var sorted: [ShelfBook] { books }

    /// 「继续阅读」卡片用：最近读过的那本。
    var mostRecent: ShelfBook? { books.first }

    func contains(_ id: String) -> Bool {
        books.contains { $0.id == id }
    }

    func contains(source: String, bookId: String) -> Bool {
        contains(NovelBook.key(source: source, bookId: bookId))
    }

    func book(_ id: String) -> ShelfBook? {
        books.first { $0.id == id }
    }

    func book(source: String, bookId: String) -> ShelfBook? {
        book(NovelBook.key(source: source, bookId: bookId))
    }

    // MARK: 写入

    /// 加入书架；已在架则只更新元信息（书名/作者可能被源站修正），**不动进度**。
    @discardableResult
    func add(_ novel: NovelBook) -> ShelfBook {
        add(
            source: novel.source,
            bookId: novel.bookId,
            title: novel.title,
            author: novel.author,
            category: novel.category,
            chapterId: novel.latestChapterId ?? "",
            chapterTitle: novel.latestChapter ?? "",
            chapterIndex: 0
        )
    }

    @discardableResult
    func add(_ detail: NovelBookDetail) -> ShelfBook {
        add(
            source: detail.source,
            bookId: detail.bookId,
            title: detail.title,
            author: detail.author,
            category: detail.category,
            chapterId: detail.entryChapterId ?? "",
            chapterTitle: detail.firstChapter ?? detail.latestChapter ?? "",
            chapterIndex: detail.entryChapterId == detail.firstChapterId ? 1 : 0,
            totalChapters: detail.chapterCount ?? 0
        )
    }

    @discardableResult
    func add(
        source: String,
        bookId: String,
        title: String,
        author: String? = nil,
        category: String? = nil,
        chapterId: String,
        chapterTitle: String,
        chapterIndex: Int,
        totalChapters: Int = 0
    ) -> ShelfBook {
        let id = NovelBook.key(source: source, bookId: bookId)
        if let existing = book(id) {
            var updated = existing
            updated.title = title
            updated.author = author ?? existing.author
            updated.category = category ?? existing.category
            if totalChapters > 0 { updated.totalChapters = totalChapters }
            replace(updated)
            return updated
        }
        let new = ShelfBook(
            source: source,
            bookId: bookId,
            title: title,
            author: author,
            category: category,
            lastChapterId: chapterId,
            lastChapterTitle: chapterTitle,
            lastChapterIndex: chapterIndex,
            totalChapters: totalChapters,
            positionInChapter: 0,
            updatedAt: .now
        )
        books.insert(new, at: 0)
        persist()
        return new
    }

    /// 更新阅读进度（翻页节流调用 + 退出阅读器时落盘）。
    @discardableResult
    func updateProgress(
        source: String,
        bookId: String,
        title: String,
        author: String? = nil,
        category: String? = nil,
        chapterId: String,
        chapterTitle: String,
        chapterIndex: Int,
        totalChapters: Int,
        positionInChapter: Double
    ) -> ShelfBook {
        var target = book(source: source, bookId: bookId) ?? ShelfBook(
            source: source,
            bookId: bookId,
            title: title,
            author: author,
            category: category,
            lastChapterId: chapterId,
            lastChapterTitle: chapterTitle,
            lastChapterIndex: chapterIndex,
            totalChapters: totalChapters,
            positionInChapter: positionInChapter,
            updatedAt: .now
        )
        target.title = title
        if let author { target.author = author }
        if let category { target.category = category }
        target.lastChapterId = chapterId
        target.lastChapterTitle = chapterTitle
        target.lastChapterIndex = chapterIndex
        if totalChapters > 0 { target.totalChapters = totalChapters }
        target.positionInChapter = min(max(positionInChapter, 0), 1)
        target.updatedAt = .now
        replace(target)
        return target
    }

    func remove(_ id: String) {
        books.removeAll { $0.id == id }
        persist()
    }

    /// 清空书架（设置页用）。
    func removeAll() {
        books.removeAll()
        persist()
    }

    // MARK: 内部

    /// 覆盖同 id 条目并重新按 updatedAt 倒序（保持「最近读过」在最前）。
    private func replace(_ book: ShelfBook) {
        if let index = books.firstIndex(where: { $0.id == book.id }) {
            books[index] = book
        } else {
            books.append(book)
        }
        books.sort { $0.updatedAt > $1.updatedAt }
        persist()
    }

    private func persist() {
        guard let data = try? encoder.encode(books) else { return }
        defaults.set(data, forKey: storageKey)
    }
}
