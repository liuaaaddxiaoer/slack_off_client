import Foundation

struct M3U8Segment: Identifiable, Equatable {
    let id = UUID()
    let url: URL
    let duration: Double
}

nonisolated enum M3U8Parser {
    static func parse(_ text: String, baseURL: URL) -> [M3U8Segment] {
        var segments: [M3U8Segment] = []
        var pendingDuration = 0.0

        // 注意：Swift 里 "\r\n" 是一个 Character，用 split(separator: "\n") 切不动 CRLF 播放列表
        for rawLine in text.split(whereSeparator: \.isNewline) {
            let line = rawLine.trimmingCharacters(in: .whitespacesAndNewlines)
            if line.isEmpty { continue }

            if line.hasPrefix("#EXTINF:") {
                let value = line.dropFirst("#EXTINF:".count).trimmingCharacters(in: .whitespaces)
                pendingDuration = Double(value.split(separator: ",").first ?? "") ?? 0
            } else if line.hasPrefix("#") {
                continue
            } else if let url = resolve(line, baseURL: baseURL) {
                segments.append(M3U8Segment(url: url, duration: max(pendingDuration, 1)))
                pendingDuration = 0
            }
        }
        return segments
    }

    static func resolve(_ value: String, baseURL: URL) -> URL? {
        if value.hasPrefix("/ets/") {
            if let decoded = decodeETSTail(value), let url = URL(string: decoded) {
                return url
            }
        }
        if let url = URL(string: value), url.scheme != nil {
            return url
        }
        return URL(string: value, relativeTo: baseURL)?.absoluteURL
    }

    private static func decodeETSTail(_ value: String) -> String? {
        let tail = value.split(separator: "/").last.map(String.init) ?? ""
        var encoded = tail
        while encoded.count % 4 != 0 { encoded += "=" }
        guard let data = Data(base64Encoded: encoded),
              let decoded = String(data: data, encoding: .utf8),
              decoded.hasPrefix("http") else { return nil }
        return decoded
    }
}
