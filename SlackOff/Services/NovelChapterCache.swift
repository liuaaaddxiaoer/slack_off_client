import Foundation

/// 小说目录与章节正文的持久化缓存，存放在应用私有 Application Support 目录。
@MainActor
final class NovelChapterCache {
    static let shared = NovelChapterCache()

    private let fileManager: FileManager
    private let root: URL
    private let encoder: JSONEncoder
    private let decoder: JSONDecoder

    init(fileManager: FileManager = .default) {
        self.fileManager = fileManager
        let applicationSupport = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        root = applicationSupport.appending(path: "novel-chapters", directoryHint: .isDirectory)
        encoder = JSONEncoder()
        decoder = JSONDecoder()
        try? fileManager.createDirectory(at: root, withIntermediateDirectories: true)
    }

    func catalog(source: String, bookId: String) -> NovelChapterList? {
        decode(NovelChapterList.self, from: bookDirectory(source: source, bookId: bookId).appending(path: "catalog.json"))
    }

    func storeCatalog(_ catalog: NovelChapterList, source: String, bookId: String) {
        encode(catalog, to: bookDirectory(source: source, bookId: bookId).appending(path: "catalog.json"))
    }

    func chapter(source: String, bookId: String, chapterId: String) -> NovelChapterBody? {
        decode(NovelChapterBody.self, from: chapterURL(source: source, bookId: bookId, chapterId: chapterId))
    }

    func storeChapter(_ chapter: NovelChapterBody, source: String, bookId: String) {
        encode(chapter, to: chapterURL(source: source, bookId: bookId, chapterId: chapter.chapterId))
    }

    func cachedChapterCount(source: String, bookId: String) -> Int {
        let directory = bookDirectory(source: source, bookId: bookId).appending(path: "bodies", directoryHint: .isDirectory)
        return (try? fileManager.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil))?
            .count(where: { $0.pathExtension == "json" }) ?? 0
    }

    private func bookDirectory(source: String, bookId: String) -> URL {
        root.appending(path: stableFileName("\(source):\(bookId)"), directoryHint: .isDirectory)
    }

    private func chapterURL(source: String, bookId: String, chapterId: String) -> URL {
        bookDirectory(source: source, bookId: bookId)
            .appending(path: "bodies", directoryHint: .isDirectory)
            .appending(path: "\(stableFileName(chapterId)).json")
    }

    private func decode<T: Decodable>(_ type: T.Type, from url: URL) -> T? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? decoder.decode(type, from: data)
    }

    private func encode<T: Encodable>(_ value: T, to url: URL) {
        guard let data = try? encoder.encode(value) else { return }
        try? fileManager.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? data.write(to: url, options: .atomic)
    }

    /// FNV-1a 64 位散列仅用于生成安全、稳定的文件名；读取后仍由 Codable 校验实体内容。
    private func stableFileName(_ value: String) -> String {
        var hash: UInt64 = 14_695_981_039_346_656_037
        for byte in value.utf8 {
            hash ^= UInt64(byte)
            hash &*= 1_099_511_628_211
        }
        return String(hash, radix: 16)
    }
}
