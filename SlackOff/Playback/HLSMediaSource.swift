import Foundation

/// 一集视频在本地的映射。
///
/// 负责三件事：
/// 1. 用能通过 CDN 防盗链的请求头去下载真实资源（`MediaFetcher`）；
/// 2. 把播放列表里的每个 URI 改写成指向本地服务的地址；
/// 3. 把「伪装成图片的 TS」裁成标准 TS（`TSSegmentRepair`），并做磁盘缓存。
nonisolated final class HLSMediaSource: @unchecked Sendable {
    static let playlistExtension = "m3u8"

    /// 调试用日志钩子（默认关闭）。
    nonisolated(unsafe) static var logger: (@Sendable (String) -> Void)?

    private static func log(_ message: @autoclosure () -> String) {
        logger?(message())
    }

    let token: String
    let origin: URL
    let headers: [String: String]
    /// 分片后缀，由上层嗅探结果决定（`ts` / `m4s` / `mp4`）。
    let segmentExtension: String
    let queue: DispatchQueue

    /// 最多缓存多少个文件，超出按写入顺序淘汰。
    private static let maxCachedFiles = 160
    private static let pruneStride = 16

    private let directory: URL
    private var remoteURLs: [Int: URL] = [:]
    private var idsByURL: [URL: Int] = [:]
    private var extensions: [Int: String] = [:]
    private var order: [Int] = []
    private var nextID = 1
    private var storesSincePrune = 0
    private var recentID: Int?
    private var recentBody: Data?

    /// - Parameter playlist: 已经下载好的播放列表原文；传进来就不会再下一次（省一次首屏等待）。
    init(
        origin: URL,
        headers: [String: String] = [:],
        segmentExtension: String = "ts",
        playlist: Data? = nil
    ) {
        self.origin = origin
        self.headers = headers
        self.segmentExtension = segmentExtension
        token = UUID().uuidString.lowercased()
        queue = DispatchQueue(label: "com.slackoff.hls.source.\(token)")
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("hls-cache", isDirectory: true)
            .appendingPathComponent(token, isDirectory: true)

        remoteURLs[0] = origin
        idsByURL[origin] = 0
        extensions[0] = Self.fileExtension(for: origin, fallback: Self.playlistExtension)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)

        if let playlist, let text = String(data: playlist, encoding: .utf8), text.contains("#EXT-X-ENDLIST") {
            let body = rewrite(playlist: text, base: origin)
            try? body.write(to: directory.appendingPathComponent("0.\(Self.playlistExtension)"), options: .atomic)
            order.append(0)
            remember(id: 0, body: body)
        }
    }

    func cleanup() {
        queue.async {
            try? FileManager.default.removeItem(at: self.directory)
        }
    }

    func fileExtension(forID id: Int) -> String {
        extensions[id] ?? segmentExtension
    }

    /// 取某个资源的最终字节（缓存 → 下载 → 修复/改写）。
    /// 同步版本：由本地服务的 IO 线程调用，网络等待发生在它自己的线程上。
    func bodySync(forID id: Int) throws -> Data {
        if let cached: Data = queue.sync(execute: { cachedBody(id: id) }) { return cached }

        let remote: URL = queue.sync { remoteURLs[id] ?? origin }
        Self.log("下载 id=\(id) \(remote.absoluteString)")
        do {
            let raw = try MediaFetcher.dataSync(from: remote, extraHeaders: headers)
            return try queue.sync { try self.prepare(raw, id: id) }
        } catch {
            Self.log("下载失败 id=\(id) \(remote.absoluteString): \(error.localizedDescription)")
            throw error
        }
    }

    // MARK: - 字节处理

    private func prepare(_ raw: Data, id: Int) throws -> Data {
        let base = remoteURLs[id] ?? origin

        let kind = TSSegmentRepair.kind(of: raw)
        Self.log("拿到 id=\(id) \(raw.count)B kind=\(kind)")
        switch kind {
        case .playlist:
            guard let text = String(data: raw, encoding: .utf8) else {
                throw URLError(.cannotDecodeContentData)
            }
            let body = rewrite(playlist: text, base: base)
            if text.contains("#EXT-X-ENDLIST") {
                store(id: id, body: body, fileExtension: Self.playlistExtension)
            }
            return body

        case .transportStream, .wrappedTransportStream:
            guard let ts = TSSegmentRepair.repairedSegment(from: raw) else {
                throw URLError(.cannotDecodeContentData)
            }
            store(id: id, body: ts, fileExtension: "ts")
            return ts

        case .fragmentedMP4:
            store(id: id, body: raw, fileExtension: "mp4")
            return raw

        case .image, .unknown:
            // 源确实没给视频字节，原样回吐让播放器报它自己的错。
            return raw
        }
    }

    /// 把播放列表里的每个 URI 换成回到本地服务的地址。
    private func rewrite(playlist text: String, base: URL) -> Data {
        var lines: [String] = []
        for rawLine in text.split(whereSeparator: \.isNewline) {
            let line = rawLine.trimmingCharacters(in: .whitespacesAndNewlines)
            if line.isEmpty { continue }
            if line.hasPrefix("#") {
                lines.append(rewriteTag(line, base: base))
            } else if let url = M3U8Parser.resolve(line, baseURL: base) {
                lines.append(localPath(for: register(url)))
            }
        }
        return Data((lines.joined(separator: "\n") + "\n").utf8)
    }

    /// 处理 `#EXT-X-KEY` / `#EXT-X-MAP` 里的 URI="..."。
    private func rewriteTag(_ line: String, base: URL) -> String {
        guard line.contains("URI=\"") else { return line }

        var result = ""
        var cursor = line.startIndex
        while let key = line.range(of: "URI=\"", range: cursor ..< line.endIndex) {
            result += line[cursor ..< key.lowerBound]
            let valueStart = key.upperBound
            guard let valueEnd = line[valueStart...].firstIndex(of: "\"") else {
                result += line[valueStart...]
                cursor = line.endIndex
                break
            }
            let value = String(line[valueStart ..< valueEnd])
            if let url = M3U8Parser.resolve(value, baseURL: base) {
                result += "URI=\"\(localPath(for: register(url)))\""
            } else {
                result += "URI=\"\(value)\""
            }
            cursor = line.index(after: valueEnd)
        }
        if cursor < line.endIndex { result += line[cursor...] }
        return result
    }

    private func localPath(for id: Int) -> String {
        HLSLocalServer.relativePath(token: token, id: id, fileExtension: extensions[id] ?? segmentExtension)
    }

    private func register(_ url: URL) -> Int {
        if let existing = idsByURL[url] { return existing }
        let id = nextID
        nextID += 1
        remoteURLs[id] = url
        idsByURL[url] = id
        extensions[id] = Self.fileExtension(for: url, fallback: segmentExtension)
        return id
    }

    /// AVFoundation 按 URL 后缀选解复用器，所以本地地址也必须带对的后缀。
    static func fileExtension(for url: URL, fallback: String) -> String {
        switch url.pathExtension.lowercased() {
        case "m3u8", "m3u": return playlistExtension
        case "mp4", "m4v", "mov": return "mp4"
        case "m4s": return "m4s"
        case "ts": return "ts"
        default: return fallback
        }
    }

    // MARK: - 缓存

    private func cachedBody(id: Int) -> Data? {
        if recentID == id, let recentBody { return recentBody }
        guard let fileExtension = extensions[id] else { return nil }
        let file = directory.appendingPathComponent("\(id).\(fileExtension)")
        guard let data = try? Data(contentsOf: file) else { return nil }
        remember(id: id, body: data)
        return data
    }

    private func store(id: Int, body: Data, fileExtension: String) {
        extensions[id] = fileExtension
        let file = directory.appendingPathComponent("\(id).\(fileExtension)")
        do {
            try body.write(to: file, options: .atomic)
        } catch {
            remember(id: id, body: body)
            return
        }
        order.append(id)
        remember(id: id, body: body)

        storesSincePrune += 1
        if storesSincePrune >= Self.pruneStride {
            storesSincePrune = 0
            prune()
        }
    }

    private func remember(id: Int, body: Data) {
        recentID = id
        recentBody = body
    }

    private func prune() {
        while order.count > Self.maxCachedFiles {
            let id = order.removeFirst()
            guard let fileExtension = extensions[id] else { continue }
            if recentID == id {
                recentID = nil
                recentBody = nil
            }
            try? FileManager.default.removeItem(at: directory.appendingPathComponent("\(id).\(fileExtension)"))
        }
    }
}
