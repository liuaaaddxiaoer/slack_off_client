import Foundation

struct NovelCacheRecord: Codable, Identifiable {
    let source: String
    let bookId: String
    var title: String
    var author: String?
    var category: String?
    var cachedCount: Int
    var totalCount: Int
    var isPaused: Bool
    var failedCount: Int
    var updatedAt: Date

    var id: String { NovelBook.key(source: source, bookId: bookId) }
    var isComplete: Bool { totalCount > 0 && cachedCount >= totalCount }
}

struct NovelCacheDownloadState {
    var cachedCount = 0
    var totalCount = 0
    var isCaching = false
    var isPaused = false
    var failedCount = 0
    var isComplete: Bool { totalCount > 0 && cachedCount >= totalCount }
    var message: String? {
        if isCaching { return nil }
        if isComplete { return "全书缓存完成" }
        if isPaused { return "缓存已暂停" }
        if failedCount > 0 { return "缓存完成，\(failedCount) 章失败，可继续重试" }
        return nil
    }
}

/// App 级整本缓存任务及持久化缓存列表。
@Observable
@MainActor
final class NovelCacheDownloadManager {
    static let shared = NovelCacheDownloadManager()
    private static let recordsKey = "com.slackoff.novel-cache-downloads.records"

    private(set) var states: [String: NovelCacheDownloadState] = [:]
    private(set) var records: [NovelCacheRecord] = []
    private var tasks: [String: Task<Void, Never>] = [:]

    private init() {
        if let data = UserDefaults.standard.data(forKey: Self.recordsKey),
           let decoded = try? JSONDecoder().decode([NovelCacheRecord].self, from: data) {
            records = decoded.sorted { $0.updatedAt > $1.updatedAt }
            states = Dictionary(uniqueKeysWithValues: records.map {
                ($0.id, NovelCacheDownloadState(
                    cachedCount: $0.cachedCount,
                    totalCount: $0.totalCount,
                    isPaused: $0.isPaused || (!$0.isComplete && $0.cachedCount > 0),
                    failedCount: $0.failedCount
                ))
            })
        }
    }

    func state(source: String, bookId: String) -> NovelCacheDownloadState {
        states[NovelBook.key(source: source, bookId: bookId)] ?? NovelCacheDownloadState()
    }

    func refresh(source: String, bookId: String, totalCount: Int) {
        let key = NovelBook.key(source: source, bookId: bookId)
        guard tasks[key] == nil else { return }
        let cached = min(NovelChapterCache.shared.cachedChapterCount(source: source, bookId: bookId), totalCount)
        var state = states[key] ?? NovelCacheDownloadState()
        state.cachedCount = cached
        state.totalCount = totalCount
        states[key] = state
        if var record = records.first(where: { $0.id == key }) {
            record.cachedCount = cached
            record.totalCount = totalCount
            save(record)
        }
    }

    func start(
        source: String,
        bookId: String,
        title: String,
        author: String?,
        category: String?,
        chapters: [NovelChapterItem]
    ) {
        guard !chapters.isEmpty else { return }
        let key = NovelBook.key(source: source, bookId: bookId)
        guard tasks[key] == nil else { return }
        let existing = records.first(where: { $0.id == key })
        save(NovelCacheRecord(
            source: source, bookId: bookId, title: title, author: author, category: category,
            cachedCount: existing?.cachedCount ?? 0, totalCount: chapters.count,
            isPaused: false, failedCount: 0, updatedAt: .now
        ))
        let catalog = NovelChapterList(
            source: source, bookId: bookId, title: title, total: chapters.count,
            offset: 0, limit: 0, returned: chapters.count, chapters: chapters
        )
        NovelChapterCache.shared.storeCatalog(catalog, source: source, bookId: bookId)
        launch(key: key, source: source, bookId: bookId, chapters: chapters)
    }

    func pause(source: String, bookId: String) {
        let key = NovelBook.key(source: source, bookId: bookId)
        tasks[key]?.cancel()
        tasks[key] = nil
        var state = states[key] ?? NovelCacheDownloadState()
        state.isCaching = false
        state.isPaused = true
        states[key] = state
        if var record = records.first(where: { $0.id == key }) {
            record.isPaused = true
            record.updatedAt = .now
            save(record)
        }
    }

    func resume(_ record: NovelCacheRecord) {
        guard tasks[record.id] == nil,
              let catalog = NovelChapterCache.shared.catalog(source: record.source, bookId: record.bookId) else { return }
        var resumed = record
        resumed.isPaused = false
        resumed.failedCount = 0
        resumed.updatedAt = .now
        save(resumed)
        launch(key: record.id, source: record.source, bookId: record.bookId, chapters: catalog.items)
    }

    private func launch(key: String, source: String, bookId: String, chapters: [NovelChapterItem]) {
        tasks[key] = Task { [weak self] in
            guard let self else { return }
            let missing = chapters.filter {
                NovelChapterCache.shared.chapter(source: source, bookId: bookId, chapterId: $0.chapterId) == nil
            }
            states[key] = NovelCacheDownloadState(
                cachedCount: chapters.count - missing.count,
                totalCount: chapters.count,
                isCaching: !missing.isEmpty
            )
            guard !missing.isEmpty else { tasks[key] = nil; return }

            let results = await withTaskGroup(of: Bool.self, returning: [Bool].self) { group in
                var iterator = missing.makeIterator()
                for _ in 0..<min(4, missing.count) {
                    if let chapter = iterator.next() {
                        group.addTask { await Self.cacheChapter(chapter, source: source, bookId: bookId) }
                    }
                }
                var values: [Bool] = []
                while let result = await group.next() {
                    if Task.isCancelled { group.cancelAll(); break }
                    values.append(result)
                    if result {
                        var state = states[key] ?? NovelCacheDownloadState(totalCount: chapters.count)
                        state.cachedCount += 1
                        states[key] = state
                        if var record = records.first(where: { $0.id == key }) {
                            record.cachedCount = state.cachedCount
                            record.updatedAt = .now
                            save(record)
                        }
                    }
                    if let chapter = iterator.next() {
                        group.addTask { await Self.cacheChapter(chapter, source: source, bookId: bookId) }
                    }
                }
                return values
            }
            guard !Task.isCancelled else { return }
            let cached = NovelChapterCache.shared.cachedChapterCount(source: source, bookId: bookId)
            states[key] = NovelCacheDownloadState(
                cachedCount: min(cached, chapters.count), totalCount: chapters.count,
                failedCount: results.filter { !$0 }.count
            )
            if var record = records.first(where: { $0.id == key }) {
                record.cachedCount = min(cached, chapters.count)
                record.failedCount = results.filter { !$0 }.count
                record.updatedAt = .now
                save(record)
            }
            tasks[key] = nil
        }
    }

    private func save(_ record: NovelCacheRecord) {
        records.removeAll { $0.id == record.id }
        records.append(record)
        records.sort { $0.updatedAt > $1.updatedAt }
        if let data = try? JSONEncoder().encode(records) {
            UserDefaults.standard.set(data, forKey: Self.recordsKey)
        }
    }

    private nonisolated static func cacheChapter(_ chapter: NovelChapterItem, source: String, bookId: String) async -> Bool {
        if await NovelChapterCache.shared.chapter(source: source, bookId: bookId, chapterId: chapter.chapterId) != nil { return true }
        guard let body = try? await NovelService.chapter(source: source, bookId: bookId, chapterId: chapter.chapterId) else { return false }
        await NovelChapterCache.shared.storeChapter(body, source: source, bookId: bookId)
        return true
    }
}
