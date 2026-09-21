import Foundation

/// 只监听 127.0.0.1 的极小 HTTP 服务。
///
/// 为什么要自己起服务：AVPlayer 只有拿到真实 http 地址才会走完整的 HLS 流程；
/// 用 `AVAssetResourceLoader` 拦自定义 scheme 时播放列表能过、分片一律 -12881 失败
/// （连 Apple 官方示例流也一样）。而 AVPlayer 又不能自定义请求头，图床防盗链绕不过去。
/// 所以这里用回环服务把「下载 + 修复」后的字节喂给系统播放器。
///
/// 用的是裸 BSD socket 而不是 Network.framework：iOS 沙盒里 `NWListener`
/// 会因为 `SO_NECP_LISTENUUID` 失败而收不到任何连接。
nonisolated final class HLSLocalServer: @unchecked Sendable {
    static let shared = HLSLocalServer()

    /// 调试用日志钩子（默认关闭）。
    nonisolated(unsafe) static var logger: (@Sendable (String) -> Void)?

    private let controlQueue = DispatchQueue(label: "com.slackoff.hls.control")
    private let ioQueue = DispatchQueue(label: "com.slackoff.hls.io", qos: .userInitiated, attributes: .concurrent)
    private var sources: [String: HLSMediaSource] = [:]
    private var listenFD: Int32 = -1
    private var port: UInt16 = 0

    private init() {}

    var baseURL: URL? {
        controlQueue.sync { port == 0 ? nil : URL(string: "http://127.0.0.1:\(port)") }
    }

    /// 启动服务（幂等），返回形如 `http://127.0.0.1:53211` 的根地址。
    func start() async throws -> URL {
        try await withCheckedThrowingContinuation { continuation in
            controlQueue.async {
                continuation.resume(with: Result { try self.startLocked() })
            }
        }
    }

    // MARK: - 挂载

    /// 启动服务并挂载一条流，返回可以直接交给 AVPlayer 的地址。
    static func mount(
        origin: URL,
        headers: [String: String] = [:],
        segmentExtension: String = "ts",
        playlist: Data? = nil
    ) async throws -> (url: URL, source: HLSMediaSource) {
        let server = shared
        let base = try await server.start()
        let source = HLSMediaSource(
            origin: origin,
            headers: headers,
            segmentExtension: segmentExtension,
            playlist: playlist
        )
        server.register(source)
        let path = relativePath(token: source.token, id: 0, fileExtension: source.fileExtension(forID: 0))
        return (base.appendingPathComponent(path), source)
    }

    /// 同一时刻只播一条流，挂载新的就把旧的卸掉，避免缓存目录越堆越多。
    func register(_ source: HLSMediaSource) {
        controlQueue.async {
            for (token, existing) in self.sources where existing !== source {
                self.sources.removeValue(forKey: token)
                existing.cleanup()
            }
            self.sources[source.token] = source
        }
    }

    func unregister(_ source: HLSMediaSource) {
        controlQueue.async {
            if self.sources[source.token] === source {
                self.sources.removeValue(forKey: source.token)
            }
            source.cleanup()
        }
    }

    static func relativePath(token: String, id: Int, fileExtension: String) -> String {
        "/hls/\(token)/\(id).\(fileExtension)"
    }

    // MARK: - socket

    private func startLocked() throws -> URL {
        if port != 0, let url = URL(string: "http://127.0.0.1:\(port)") { return url }

        // 往已关闭的 socket 写数据会收到 SIGPIPE，默认行为是直接杀进程，必须忽略。
        signal(SIGPIPE, SIG_IGN)

        let fd = socket(AF_INET, SOCK_STREAM, 0)
        guard fd >= 0 else { throw URLError(.cannotConnectToHost) }

        var yes: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &yes, socklen_t(MemoryLayout<Int32>.size))

        var address = sockaddr_in()
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = 0                          // 让系统分配端口
        address.sin_addr.s_addr = inet_addr("127.0.0.1")   // 只绑回环，外面访问不到

        let bound = withUnsafePointer(to: &address) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard bound == 0 else {
            close(fd)
            throw URLError(.cannotConnectToHost)
        }
        guard listen(fd, 32) == 0 else {
            close(fd)
            throw URLError(.cannotConnectToHost)
        }

        var assigned = sockaddr_in()
        var length = socklen_t(MemoryLayout<sockaddr_in>.size)
        _ = withUnsafeMutablePointer(to: &assigned) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                getsockname(fd, $0, &length)
            }
        }
        let assignedPort = UInt16(bigEndian: assigned.sin_port)
        guard assignedPort != 0, let url = URL(string: "http://127.0.0.1:\(assignedPort)") else {
            close(fd)
            throw URLError(.cannotConnectToHost)
        }

        listenFD = fd
        port = assignedPort

        let thread = Thread { [weak self] in
            self?.acceptLoop(fd: fd)
        }
        thread.name = "com.slackoff.hls.accept"
        thread.qualityOfService = .userInitiated
        thread.start()

        Self.log("本地服务已启动 \(url.absoluteString)")
        return url
    }

    private func acceptLoop(fd: Int32) {
        while true {
            let client = accept(fd, nil, nil)
            if client < 0 {
                if errno == EINTR { continue }
                Self.log("accept 失败 errno=\(errno)")
                return
            }
            ioQueue.async { [weak self] in
                self?.handle(client)
            }
        }
    }

    private func handle(_ fd: Int32) {
        defer { close(fd) }
        var noPipe: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &noPipe, socklen_t(MemoryLayout<Int32>.size))

        guard let request = readRequest(fd) else { return }
        Self.log("\(request.method) \(request.path) range=\(request.headers["range"] ?? "-")")

        guard request.method == "GET" || request.method == "HEAD" else {
            write(fd, status: "405 Method Not Allowed", contentType: "text/plain", body: Data(), method: request.method)
            return
        }
        guard let route = Self.route(request.path) else {
            write(fd, status: "404 Not Found", contentType: "text/plain", body: Data(), method: request.method)
            return
        }
        guard let source = controlQueue.sync(execute: { sources[route.token] }) else {
            write(fd, status: "404 Not Found", contentType: "text/plain", body: Data(), method: request.method)
            return
        }

        do {
            let body = try source.bodySync(forID: route.id)
            let contentType = Self.contentType(forExtension: source.fileExtension(forID: route.id))
            write(fd, status: "200 OK", contentType: contentType, body: body, rangeHeader: request.headers["range"], method: request.method)
        } catch {
            Self.log("上游失败 id=\(route.id): \(error.localizedDescription)")
            write(fd, status: "502 Bad Gateway", contentType: "text/plain; charset=utf-8", body: Data(), method: request.method)
        }
    }

    private func readRequest(_ fd: Int32) -> HTTPRequest? {
        var buffer = Data()
        var chunk = [UInt8](repeating: 0, count: 8192)
        while buffer.count < 65_536 {
            let count = chunk.withUnsafeMutableBufferPointer { recv(fd, $0.baseAddress, $0.count, 0) }
            if count <= 0 { break }
            chunk.withUnsafeBufferPointer { buffer.append($0.baseAddress!, count: count) }
            if buffer.range(of: Data("\r\n\r\n".utf8)) != nil { break }
        }
        return HTTPRequest.parse(buffer)
    }

    private func write(
        _ fd: Int32,
        status: String,
        contentType: String,
        body: Data,
        rangeHeader: String? = nil,
        method: String = "GET"
    ) {
        var statusLine = status
        var payload = body
        var contentRange: String?

        if let rangeHeader, let range = Self.parseRange(rangeHeader, length: body.count), range.lowerBound < body.count {
            statusLine = "206 Partial Content"
            payload = body.subdata(in: range)
            contentRange = "Content-Range: bytes \(range.lowerBound)-\(range.upperBound - 1)/\(body.count)"
        }

        var head = "HTTP/1.1 \(statusLine)\r\n"
        head += "Content-Type: \(contentType)\r\n"
        head += "Content-Length: \(payload.count)\r\n"
        head += "Accept-Ranges: bytes\r\n"
        if let contentRange { head += "\(contentRange)\r\n" }
        head += "Connection: close\r\n\r\n"

        var response = Data(head.utf8)
        if method != "HEAD" { response.append(payload) }
        Self.log("响应 \(statusLine) \(payload.count)B \(contentType)")

        var remaining = response
        while !remaining.isEmpty {
            let sent = remaining.withUnsafeBytes { raw -> Int in
                guard let base = raw.baseAddress else { return -1 }
                return send(fd, base, raw.count, 0)
            }
            if sent <= 0 { return }
            remaining = remaining.dropFirst(sent)
        }
    }

    // MARK: - 工具

    private static func route(_ path: String) -> (token: String, id: Int)? {
        let components = path.split(separator: "/", omittingEmptySubsequences: true).map(String.init)
        guard components.count == 3, components[0] == "hls", components[2].contains(".") else { return nil }
        let name = components[2] as NSString
        guard let id = Int(name.deletingPathExtension) else { return nil }
        return (components[1], id)
    }

    private static func contentType(forExtension fileExtension: String) -> String {
        switch fileExtension {
        case "m3u8": return "application/vnd.apple.mpegurl"
        case "ts": return "video/mp2t"
        case "mp4", "m4s": return "video/mp4"
        default: return "application/octet-stream"
        }
    }

    /// 解析 `bytes=0-1023` / `bytes=1024-` / `bytes=-500`。
    static func parseRange(_ header: String, length: Int) -> Range<Int>? {
        guard header.hasPrefix("bytes="), length > 0 else { return nil }
        let value = header.dropFirst("bytes=".count)
        let parts = value.split(separator: "-", omittingEmptySubsequences: false).map(String.init)
        guard parts.count == 2 else { return nil }

        if parts[0].isEmpty {
            guard let suffix = Int(parts[1]), suffix > 0 else { return nil }
            return max(length - suffix, 0) ..< length
        }
        guard let start = Int(parts[0]), start < length else { return nil }
        let end = parts[1].isEmpty ? length - 1 : min(Int(parts[1]) ?? length - 1, length - 1)
        guard end >= start else { return nil }
        return start ..< end + 1
    }

    private static func log(_ message: @autoclosure () -> String) {
        logger?(message())
    }
}

/// 极简 HTTP 请求解析，只需要请求行和 Range 头。
nonisolated struct HTTPRequest {
    let method: String
    let path: String
    let headers: [String: String]

    static func parse(_ data: Data) -> HTTPRequest? {
        guard let terminator = data.range(of: Data("\r\n\r\n".utf8)) else { return nil }
        guard let head = String(data: data[data.startIndex ..< terminator.lowerBound], encoding: .utf8) else { return nil }

        var lines = head.components(separatedBy: "\r\n")
        let requestLine = lines.removeFirst().split(separator: " ").map(String.init)
        guard requestLine.count >= 2 else { return nil }

        var headers: [String: String] = [:]
        for line in lines {
            guard let colon = line.firstIndex(of: ":") else { continue }
            let name = String(line[line.startIndex ..< colon]).lowercased()
            let value = String(line[line.index(after: colon)...]).trimmingCharacters(in: .whitespaces)
            headers[name] = value
        }
        return HTTPRequest(method: requestLine[0].uppercased(), path: requestLine[1], headers: headers)
    }
}
