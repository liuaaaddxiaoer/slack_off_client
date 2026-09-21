import Foundation

/// 分片字节的真实类型（后缀名在这里完全不可信）。
nonisolated enum SegmentKind: Equatable {
    case playlist
    case transportStream          // 标准 TS
    case wrappedTransportStream   // 伪装成图片的 TS，需要裁掉头尾
    case fragmentedMP4
    case image
    case unknown
}

/// 「伪装成图片的 TS 分片」修复器。
///
/// 参考 https://zhuanlan.zhihu.com/p/619829579 ：
/// 有人为了蹭公共图床的免费 CDN，把 HLS 的 TS 切片伪装成 png/bmp 上传。真实文件结构是
///
///     [假图片头 + 0xFF 填充] + [真正的 TS 包：0x47 开头、188 字节一个] + [0x00 填充 / IEND 尾巴]
///
/// FFmpeg 靠 `mpegts_probe` / `mpegts_resync` 找 0x47 就能照常播放，
/// 但 AVPlayer 和 ExoPlayer 都按文件头判定，会当成图片直接失败（iOS 上就是 -1016 无法解码）。
/// 这里做的是 iOS 版的 resync：定位真正的 TS 区间，把伪装的头和尾全部裁掉。
nonisolated enum TSSegmentRepair {
    static let syncByte: UInt8 = 0x47
    static let standardPacketSize = 188

    /// TS 包长度：188 最常见，192（带 4 字节时间戳）和 204（带 FEC）也要认。
    private static let candidatePacketSizes = [188, 192, 204]
    /// 连续确认多少个包，避免把图片头里偶然出现的 0x47 当成起点。
    private static let confirmPackets = 4
    /// 假头不会太长，最多往后扫 64KB。
    private static let maxHeaderScan = 64 * 1024

    struct Layout: Equatable {
        let packetSize: Int
        let range: Range<Int>

        var packetCount: Int { (range.upperBound - range.lowerBound) / packetSize }
    }

    static func kind(of data: Data) -> SegmentKind {
        guard !data.isEmpty else { return .unknown }

        if hasPrefix(data, "#EXTM3U") { return .playlist }

        if let layout = layout(of: data) {
            return layout.range.lowerBound == 0 && layout.range.upperBound >= data.count - layout.packetSize
                ? .transportStream
                : .wrappedTransportStream
        }

        if hasPrefix(data, "ftyp", at: 4) { return .fragmentedMP4 }
        if isImage(data) { return .image }
        return .unknown
    }

    /// 裁掉头尾伪装字节，返回可以直接喂给 AVPlayer 的纯 TS；不是 TS 时返回 nil。
    static func repairedSegment(from data: Data) -> Data? {
        guard let layout = layout(of: data) else { return nil }
        if layout.range.lowerBound == 0, layout.range.upperBound == data.count { return data }
        return data.subdata(in: layout.range)
    }

    static func isTransportStream(_ data: Data) -> Bool {
        layout(of: data) != nil
    }

    /// 找出 TS 有效区间；标准 TS 返回从 0 开始的对齐区间。
    static func layout(of data: Data) -> Layout? {
        let bytes = [UInt8](data)
        let count = bytes.count
        guard count >= standardPacketSize * 2 else { return nil }
        guard let start = firstSyncStart(in: bytes) else { return nil }
        guard let packetSize = packetSize(at: start, in: bytes) else { return nil }

        var end = start
        var resyncs = 0
        while end + packetSize <= count {
            if bytes[end] == syncByte {
                end += packetSize
                continue
            }
            // 中途撞到填充或坏包：在两个包长度内重新同步，找不到就认为 TS 到此结束。
            if resyncs < 2, let next = resync(after: end, packetSize: packetSize, in: bytes) {
                end = next
                resyncs += 1
                continue
            }
            break
        }

        guard end - start >= packetSize * 2 else { return nil }
        return Layout(packetSize: packetSize, range: start..<end)
    }

    // MARK: - 内部实现

    private static func firstSyncStart(in bytes: [UInt8]) -> Int? {
        let limit = min(bytes.count - standardPacketSize * confirmPackets, maxHeaderScan)
        guard limit >= 0 else { return nil }

        // 第一遍：优先认 PAT 包（PID == 0）。标准 HLS 每个分片的第一个包都是 PAT，
        // 而假图片头里偶尔会有一个能「首尾相接」成 188 对齐链的 0x47 —— 比如 chaoxing
        // 的 origin.jpg，PNG 的 tEXt("TS_RAW") 文本块里那个 0x47 恰好落在真 PAT 之前
        // 188 字节，+188/+376/+564 全踩在真 TS 的同步字节上，旧逻辑会把它误当包头，
        // 把 PAT 挤到第二个包，导致有声音没画面。PAT 的 PID 一定是 0，假头的不是。
        var index = 0
        while index <= limit {
            if bytes[index] == syncByte, isPAT(at: index, in: bytes), packetSize(at: index, in: bytes) != nil {
                return index
            }
            index += 1
        }

        // 第二遍：没找到 PAT 就退回「第一个能成链的 0x47」（兼容极少数开头不放 PAT 的源）。
        index = 0
        while index <= limit {
            if bytes[index] == syncByte, packetSize(at: index, in: bytes) != nil {
                return index
            }
            index += 1
        }
        return nil
    }

    /// 是不是 PAT 包：TS 头 4 字节里，sync_byte 之后的 13 位 PID 是不是 0。
    private static func isPAT(at index: Int, in bytes: [UInt8]) -> Bool {
        bytes[index] == syncByte && pid(at: index, in: bytes) == 0
    }

    /// 提取 TS 包头里 13 位的 PID。
    private static func pid(at index: Int, in bytes: [UInt8]) -> Int {
        guard index + 2 < bytes.count else { return -1 }
        return (Int(bytes[index + 1]) & 0x1F) << 8 | Int(bytes[index + 2])
    }

    private static func packetSize(at index: Int, in bytes: [UInt8]) -> Int? {
        for size in candidatePacketSizes {
            var matched = true
            for step in 1..<confirmPackets {
                let at = index + size * step
                if at >= bytes.count || bytes[at] != syncByte {
                    matched = false
                    break
                }
            }
            if matched { return size }
        }
        return nil
    }

    private static func resync(after index: Int, packetSize: Int, in bytes: [UInt8]) -> Int? {
        let limit = min(index + packetSize * 2, bytes.count - packetSize * confirmPackets)
        var candidate = index + 1
        while candidate <= limit {
            if bytes[candidate] == syncByte, Self.packetSize(at: candidate, in: bytes) == packetSize {
                return candidate
            }
            candidate += 1
        }
        return nil
    }

    private static func isImage(_ data: Data) -> Bool {
        hasPrefix(data, [0x89, 0x50, 0x4E, 0x47])            // PNG
            || hasPrefix(data, [0xFF, 0xD8, 0xFF])           // JPEG
            || hasPrefix(data, [0x47, 0x49, 0x46, 0x38])     // GIF
            || hasPrefix(data, [0x42, 0x4D])                 // BMP
            || hasPrefix(data, [0x52, 0x49, 0x46, 0x46])     // RIFF / WebP
    }

    private static func hasPrefix(_ data: Data, _ text: String, at offset: Int = 0) -> Bool {
        hasPrefix(data, Array(text.utf8), at: offset)
    }

    private static func hasPrefix(_ data: Data, _ bytes: [UInt8], at offset: Int = 0) -> Bool {
        guard offset >= 0, data.count >= offset + bytes.count else { return false }
        let start = data.startIndex + offset
        return data[start ..< start + bytes.count].elementsEqual(bytes)
    }
}
