import Foundation

/// 小说聚合 API（本地 `xiaoshuo_server`）的端点封装。
///
/// 源策略（重要）：
/// - 列表类接口可传 `auto`，服务端按健康度 + 优先级自动故障转移；
///   其中 `/api/home`、`/api/search`、`/api/categories/{slug}`、`/api/full` 的响应里**带** `source`，
///   点进书籍时用响应里的 source 即可。
/// - `/api/categories`（分类字典）与 `/api/ranks`（排行榜）的响应里**不带** source，
///   而 slug 与 book_id 都是源站私有、跨源不通用，
///   所以这两个请求必须显式指定源 —— 书城统一使用 `/api/home` 响应里的「活跃源」。
/// - 详情 / 目录 / 正文一律显式回传资源自带的 source（服务端对这三类不做回退）。
enum NovelService {

    // MARK: - URL 构造（纯函数，便于单测）

    /// 拼装请求 URL。
    /// - path 的每一段都做百分号编码，保证含前导零的 book_id、中文 slug 不被吞。
    /// - query 中 value 为 nil 的项直接省略（例如 `board` 不传就是全部榜单）。
    nonisolated static func makeURL(
        base: URL,
        path: String,
        query: [(name: String, value: String?)] = []
    ) -> URL {
        var components = URLComponents(url: base, resolvingAgainstBaseURL: false) ?? URLComponents()
        components.scheme = components.scheme ?? "http"
        let encodedPath = path
            .split(separator: "/", omittingEmptySubsequences: true)
            .map { $0.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? String($0) }
            .joined(separator: "/")
        components.percentEncodedPath = "/" + encodedPath
        let items = query.compactMap { item -> URLQueryItem? in
            guard let value = item.value else { return nil }
            return URLQueryItem(name: item.name, value: value)
        }
        components.queryItems = items.isEmpty ? nil : items
        return components.url ?? base.appendingPathComponent(path)
    }

    // MARK: - 请求

    /// 发起请求并解码。非 2xx 时解析服务端 `{"detail": ...}` 原文抛给 UI 展示，
    /// 这样「代理没开 / 源站宕机」能直接在错误页看到原因，而不是一个干巴巴的 HTTP 502。
    static func load<T: Decodable>(
        _ path: String,
        query: [(name: String, value: String?)] = [],
        base: URL? = nil,
        as type: T.Type = T.self
    ) async throws -> T {
        let resolvedBase = base ?? NovelSettings.shared.baseURL
        let url = makeURL(base: resolvedBase, path: path, query: query)
        let (data, response) = try await URLSession.shared.data(for: APIConfig.novelRequest(for: url))
        if let http = response as? HTTPURLResponse, !(200...299).contains(http.statusCode) {
            let payload = try? APIConfig.novelDecoder.decode(NovelErrorPayload.self, from: data)
            let detail = payload?.detail?.message ?? ""
            throw NovelAPIError(status: http.statusCode, detail: detail)
        }
        do {
            return try APIConfig.novelDecoder.decode(T.self, from: data)
        } catch {
            throw NovelAPIError(
                status: (response as? HTTPURLResponse)?.statusCode ?? 0,
                detail: "响应解析失败（\(path)）：\(error.localizedDescription)"
            )
        }
    }

    // MARK: - 服务

    /// 存活探测。设置页的「测试连接」用它，比拉首页快得多。
    static func ping(base: URL? = nil) async throws -> NovelPing {
        try await load("api/ping", base: base)
    }

    /// 缓存与抓取层统计（设置页排障用：能看出是不是代理没配导致 misses 一直涨）。
    static func cacheStats(base: URL? = nil) async throws -> NovelCacheStats {
        try await load("api/cache/stats", base: base)
    }

    /// 数据源清单与能力矩阵（设置页的源切换选项）。
    static func sources(base: URL? = nil) async throws -> [NovelSourceInfo] {
        try await load("api/sources", base: base)
    }

    // MARK: - 发现

    /// 首页聚合：热门推荐（有封面/简介）+ 强力推荐 + 最近更新 + 分类精选块。
    /// - Parameter source: `auto` 或具体源 id；响应的 `source` 字段即「活跃源」。
    static func home(source: String = "auto", base: URL? = nil) async throws -> NovelHomePage {
        try await load("api/home", query: [(name: "source", value: source)], base: base)
    }

    /// 分类字典（slug ↔ 中文名）。slug 跨源不通用，必须显式指定源。
    static func categories(source: String, base: URL? = nil) async throws -> [NovelCategory] {
        try await load("api/categories", query: [(name: "source", value: source)], base: base)
    }

    /// 分类下的书籍列表。`hasMore` 决定是否还能翻页（bqg99/blqvdu 单页固定，biquge365 可深翻）。
    static func categoryBooks(
        slug: String,
        page: Int = 1,
        source: String,
        base: URL? = nil
    ) async throws -> NovelBookPage {
        try await load(
            "api/categories/\(slug)",
            query: [(name: "page", value: "\(page)"), (name: "source", value: source)],
            base: base
        )
    }

    /// 排行榜。board 为 nil 时返回全部榜单（实测 8 榜 × 15 条）。
    /// 响应不带 source，调用方必须自己记住用的是哪个源。
    static func ranks(
        board: String? = nil,
        source: String,
        base: URL? = nil
    ) async throws -> [NovelRankBoard] {
        try await load(
            "api/ranks",
            query: [(name: "board", value: board), (name: "source", value: source)],
            base: base
        )
    }

    /// 全本小说列表。
    static func fullBooks(page: Int = 1, source: String, base: URL? = nil) async throws -> NovelBookPage {
        try await load(
            "api/full",
            query: [(name: "page", value: "\(page)"), (name: "source", value: source)],
            base: base
        )
    }

    // MARK: - 检索

    /// 站内搜索。只有 bqg99 支持；传 `auto` 时服务端会自动落到支持搜索的源。
    static func search(keyword: String, page: Int = 1, source: String = "auto", base: URL? = nil) async throws -> NovelBookPage {
        try await load(
            "api/search",
            query: [
                (name: "kw", value: keyword),
                (name: "page", value: "\(page)"),
                (name: "source", value: source),
            ],
            base: base
        )
    }

    // MARK: - 书籍

    static func bookDetail(source: String, bookId: String, base: URL? = nil) async throws -> NovelBookDetail {
        try await load(
            "api/book/\(bookId)",
            query: [(name: "source", value: source)],
            base: base
        )
    }

    /// 完整目录。`limit = 0` 表示一次拉全（实测《牧神记》1920 章 0.07s，缓存命中后更快）。
    static func chapters(
        source: String,
        bookId: String,
        offset: Int = 0,
        limit: Int = 0,
        base: URL? = nil
    ) async throws -> NovelChapterList {
        try await load(
            "api/book/\(bookId)/chapters",
            query: [
                (name: "offset", value: "\(offset)"),
                (name: "limit", value: "\(limit)"),
                (name: "source", value: source),
            ],
            base: base
        )
    }

    // MARK: - 阅读

    static func chapter(
        source: String,
        bookId: String,
        chapterId: String,
        cleanAds: Bool = true,
        base: URL? = nil
    ) async throws -> NovelChapterBody {
        try await load(
            "api/chapter/\(bookId)/\(chapterId)",
            query: [
                (name: "source", value: source),
                (name: "clean_ads", value: cleanAds ? "true" : "false"),
            ],
            base: base
        )
    }
}
