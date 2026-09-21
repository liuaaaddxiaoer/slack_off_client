import Foundation

enum MediaFetchError: LocalizedError {
    case blocked(Int)
    case emptyResponse

    var errorDescription: String? {
        switch self {
        case .blocked(let status): return "媒体源拒绝访问 (HTTP \(status))"
        case .emptyResponse: return "媒体源返回空数据"
        }
    }
}

/// 媒体下载器。
///
/// 各家图床 CDN 的 Referer ACL 完全相反：`file.icve.com.cn` 带上站点 Referer 直接 403，
/// `p.ananas.chaoxing.com` 不带 Referer 又 403。所以这里按顺序尝试几组请求头，
/// 任何一组拿到正常字节就返回。
nonisolated enum MediaFetcher {
    private static let session: URLSession = {
        let configuration = URLSessionConfiguration.default
        configuration.httpAdditionalHeaders = ["Accept": "*/*"]
        configuration.httpMaximumConnectionsPerHost = 12
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.urlCache = nil
        configuration.timeoutIntervalForRequest = 30
        configuration.timeoutIntervalForResource = 120
        return URLSession(configuration: configuration)
    }()

    static func data(
        from url: URL,
        extraHeaders: [String: String] = [:],
        range: Range<Int>? = nil
    ) async throws -> Data {
        try await withCheckedThrowingContinuation { continuation in
            fetch(url, extraHeaders: extraHeaders, range: range) { result in
                continuation.resume(with: result)
            }
        }
    }

    /// 同步版本：给本地服务的 IO 线程用。
    static func dataSync(
        from url: URL,
        extraHeaders: [String: String] = [:],
        range: Range<Int>? = nil
    ) throws -> Data {
        let box = ResultBox()
        let semaphore = DispatchSemaphore(value: 0)
        fetch(url, extraHeaders: extraHeaders, range: range) { result in
            box.result = result
            semaphore.signal()
        }
        semaphore.wait()
        return try (box.result ?? .failure(MediaFetchError.emptyResponse)).get()
    }

    private final class ResultBox: @unchecked Sendable {
        var result: Result<Data, Error>?
    }

    static func fetch(
        _ url: URL,
        extraHeaders: [String: String] = [:],
        range: Range<Int>? = nil,
        completion: @escaping @Sendable (Result<Data, Error>) -> Void
    ) {
        attempt(
            url,
            candidates: headerCandidates(for: url, extraHeaders: extraHeaders),
            range: range,
            index: 0,
            lastError: MediaFetchError.emptyResponse,
            completion: completion
        )
    }

    // MARK: - 内部实现

    private static func attempt(
        _ url: URL,
        candidates: [[String: String]],
        range: Range<Int>?,
        index: Int,
        lastError: Error,
        completion: @escaping @Sendable (Result<Data, Error>) -> Void
    ) {
        guard index < candidates.count else {
            completion(.failure(lastError))
            return
        }

        var request = URLRequest(url: url)
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.timeoutInterval = 30
        for (field, value) in candidates[index] {
            request.setValue(value, forHTTPHeaderField: field)
        }
        if let range {
            request.setValue("bytes=\(range.lowerBound)-\(range.upperBound - 1)", forHTTPHeaderField: "Range")
        }

        session.dataTask(with: request) { data, response, error in
            let http = response as? HTTPURLResponse
            let status = http?.statusCode ?? 0
            let statusOK = http == nil || (200...299).contains(status)
            if error == nil, statusOK, let data, !data.isEmpty, !looksLikeErrorPage(data) {
                completion(.success(data))
                return
            }
            let failure = error ?? MediaFetchError.blocked(status)
            attempt(url, candidates: candidates, range: range, index: index + 1, lastError: failure, completion: completion)
        }.resume()
    }

    /// 优先用「资源自己域名」当 Referer，其次不带 Referer，最后才用接口下发的头。
    static func headerCandidates(for url: URL, extraHeaders: [String: String]) -> [[String: String]] {
        let userAgent = ["User-Agent": APIConfig.userAgent]
        var candidates: [[String: String]] = []

        if let scheme = url.scheme, let host = url.host {
            candidates.append(userAgent.merging(["Referer": "\(scheme)://\(host)/"]) { current, _ in current })
        }
        candidates.append(userAgent)
        if !extraHeaders.isEmpty {
            candidates.append(extraHeaders.merging(userAgent) { _, current in current })
        }

        var seen: Set<[String: String]> = []
        return candidates.filter { seen.insert($0).inserted }
    }

    /// 403/防盗链页面经常是 200 之外的状态码，但也有直接吐 HTML 的，这里兜一层。
    private static func looksLikeErrorPage(_ data: Data) -> Bool {
        let head = String(decoding: data.prefix(64), as: UTF8.self).lowercased()
        return head.hasPrefix("<!doctype") || head.hasPrefix("<html")
    }
}
