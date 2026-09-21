import Foundation

// MARK: - 数据源
//
// 小说服务（xiaoshuo_server，本地 :4321）目前接了三个笔趣阁系源：
//   bqg99（顶点小说网，priority 10，全能力）
//   blqvdu（顶点镜像，priority 15，无站内搜索）
//   biquge365（新笔趣阁，priority 20，无站内搜索、分类可深度翻页）
// 源清单不写死，设置页由 /api/sources 动态拉取。

struct NovelSourceInfo: Codable, Identifiable, Hashable {
    let id: String
    let name: String
    let baseUrl: String?
    let enabled: Bool?
    let priority: Int?
    let capabilities: [String: Bool]?
    let notes: String?

    /// 该源是否支持某能力；缺字段时按「支持」处理（服务端只会显式标 false）。
    func supports(_ capability: String) -> Bool {
        capabilities?[capability] ?? true
    }
}

// MARK: - 书籍

/// 列表页用的书籍摘要。
///
/// `bookId` / `chapterId` **必须是 String**：源站私有 id 可能带前导零，
/// 用整型会静默丢掉前导零导致 404。且 id 跨源不通用，所以每条数据都带 `source`，
/// 后续请求详情/目录/正文必须原样回传这个 source。
struct NovelBook: Codable, Identifiable, Hashable {
    let source: String
    let bookId: String
    let title: String
    let author: String?
    let category: String?
    /// 源站封面 URL。**v1 不使用**（该域名在设备上直连不通），封面统一走 TextCover 文字封面。
    let cover: String?
    let intro: String?
    let latestChapter: String?
    let latestChapterId: String?
    let updateTime: String?
    let url: String?

    var id: String { NovelBook.key(source: source, bookId: bookId) }

    static func key(source: String, bookId: String) -> String { "\(source):\(bookId)" }

    /// 文字封面的稳定取色种子：同一本书在书城/书架/详情/阅读器里颜色一致。
    var coverSeed: String { id }
}

/// 书籍详情，比 NovelBook 多连载状态、字数、章节数与首章信息。
struct NovelBookDetail: Codable, Identifiable, Hashable {
    let source: String
    let bookId: String
    let title: String
    let author: String?
    let category: String?
    let cover: String?
    let intro: String?
    let latestChapter: String?
    let latestChapterId: String?
    let updateTime: String?
    let url: String?
    let status: String?
    let wordCount: Int?
    let chapterCount: Int?
    let firstChapter: String?
    let firstChapterId: String?

    var id: String { NovelBook.key(source: source, bookId: bookId) }

    /// 详情页主按钮的入口章节：有书架进度时用进度章节，否则首章 → 最后兜底用最新章节。
    var entryChapterId: String? { firstChapterId ?? latestChapterId }

    /// 字数转「xx.x 万字」，源站缺字段时返回 nil。
    var wordCountText: String? {
        guard let wordCount, wordCount > 0 else { return nil }
        if wordCount >= 10_000 {
            return String(format: "%.1f 万字", Double(wordCount) / 10_000)
        }
        return "\(wordCount) 字"
    }
}

/// 分页书籍列表（分类 / 全本 / 搜索共用）。
struct NovelBookPage: Codable, Hashable {
    let source: String
    let kind: String?
    let name: String?
    let page: Int?
    let pageSize: Int?
    let totalPages: Int?
    let total: Int?
    let hasMore: Bool?
    let items: [NovelBook]?

    var books: [NovelBook] { items ?? [] }
    var more: Bool { hasMore ?? false }
}

// MARK: - 首页

struct NovelHomeCategoryBlock: Codable, Hashable, Identifiable {
    let name: String
    let books: [NovelBook]?

    var id: String { name }
    var items: [NovelBook] { books ?? [] }
}

struct NovelHomePage: Codable, Hashable {
    /// 服务端实际使用的源。列表接口传 `auto` 时由它决定故障转移到谁，
    /// 而 `/api/categories`、`/api/ranks` 的响应里**不带** source，
    /// 因此这两类请求必须用这里的 source 显式指定，否则拿到手的 book_id 无法定位源。
    let source: String
    let siteName: String?
    let hotBooks: [NovelBook]?
    let recommendBooks: [NovelBook]?
    let latestUpdates: [NovelBook]?
    let categoryBlocks: [NovelHomeCategoryBlock]?

    var hot: [NovelBook] { hotBooks ?? [] }
    var recommend: [NovelBook] { recommendBooks ?? [] }
    var latest: [NovelBook] { latestUpdates ?? [] }
    var blocks: [NovelHomeCategoryBlock] { categoryBlocks ?? [] }
}

// MARK: - 分类 / 排行

struct NovelCategory: Codable, Hashable, Identifiable {
    let slug: String
    let name: String
    let url: String?
    let paginated: Bool?

    var id: String { slug }
}

struct NovelRankEntry: Codable, Hashable, Identifiable {
    let rank: Int?
    let bookId: String
    let title: String
    let category: String?
    let author: String?
    let url: String?

    /// 响应里没有 source，由请求方（书城）用当前活跃源补齐后才能跳详情。
    var id: String { "\(bookId)-\(rank ?? 0)" }
}

struct NovelRankBoard: Codable, Hashable, Identifiable {
    let board: String
    let total: Int?
    let items: [NovelRankEntry]?

    var id: String { board }
    var entries: [NovelRankEntry] { items ?? [] }
}

// MARK: - 目录 / 正文

struct NovelChapterItem: Codable, Hashable, Identifiable {
    /// 序号，从 1 开始
    let index: Int
    let chapterId: String
    let title: String
    let url: String?

    var id: String { chapterId }
}

struct NovelChapterList: Codable, Hashable {
    let source: String
    let bookId: String
    let title: String?
    let total: Int?
    let offset: Int?
    let limit: Int?
    let returned: Int?
    let chapters: [NovelChapterItem]?

    var items: [NovelChapterItem] { chapters ?? [] }
}

struct NovelChapterBody: Codable, Hashable {
    let source: String
    let bookId: String
    let chapterId: String
    let title: String
    /// 纯文本正文，段落以 \n 分隔（服务端已 clean_ads）
    let content: String
    let contentHtml: String?
    let wordCount: Int?
    let prevChapterId: String?
    let prevChapterTitle: String?
    let nextChapterId: String?
    let nextChapterTitle: String?
    let catalogUrl: String?

    /// 正文按空行/换行切成段落，供分页引擎逐行测量。
    var paragraphs: [String] {
        content.split(whereSeparator: \.isNewline).map(String.init)
    }
}

// MARK: - 运维

struct NovelPing: Codable, Hashable {
    let ok: Bool?
    let service: String?
    let version: String?
}

struct NovelCacheStats: Codable, Hashable {
    struct Cache: Codable, Hashable {
        let entries: Int?
        let hits: Int?
        let misses: Int?
    }

    struct Fetcher: Codable, Hashable {
        let retries: Int?
        let timeoutS: Double?
        let concurrency: Int?
        let proxyMode: String?
    }

    let cache: Cache?
    let hitRate: Double?
    let fetcher: Fetcher?
}

// MARK: - 错误

/// 服务端错误体。正常错误是 `{"detail": "重试 4 次后仍失败: PoolTimeout | https://..."}`，
/// FastAPI 参数校验错误是 `{"detail": [{...}]}`，两种都要能解析出可读文案。
struct NovelErrorPayload: Decodable {
    let detail: Detail?

    enum Detail: Decodable {
        case text(String)
        case validation([ValidationItem])

        init(from decoder: Decoder) throws {
            let container = try decoder.singleValueContainer()
            if let text = try? container.decode(String.self) {
                self = .text(text)
                return
            }
            self = .validation((try? container.decode([ValidationItem].self)) ?? [])
        }

        var message: String {
            switch self {
            case .text(let text): return text
            case .validation(let items):
                return items.map(\.msg).joined(separator: "; ")
            }
        }
    }

    struct ValidationItem: Decodable {
        let msg: String
    }
}

struct NovelAPIError: LocalizedError, Equatable {
    let status: Int
    let detail: String

    var errorDescription: String? {
        detail.isEmpty ? "HTTP \(status)" : detail
    }
}

enum NovelError {
    /// 服务未启动 / 局域网地址填错时的友好提示。
    static func describe(_ error: any Error) -> String {
        if let apiError = error as? NovelAPIError { return apiError.localizedDescription }
        if let urlError = error as? URLError {
            switch urlError.code {
            case .cannotConnectToHost, .networkConnectionLost, .notConnectedToInternet:
                return "连不上小说服务（\(urlError.code.rawValue)）。请确认 4321 服务已启动，真机需在设置里填 Mac 的局域网 IP。"
            case .timedOut:
                return "请求超时：源站回源较慢，可稍后重试。"
            default:
                return urlError.localizedDescription
            }
        }
        return error.localizedDescription
    }
}
