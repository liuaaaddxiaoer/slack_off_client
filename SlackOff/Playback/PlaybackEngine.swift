import AVFoundation
import Foundation
import Observation

/// 播放引擎。
///
/// 视频走系统播放器：真实 m3u8 交给 `HLSLocalServer` 挂到本地回环地址上，
/// 由它下载分片、绕过图床防盗链、把「伪装成图片的 TS」修好再喂给 AVPlayer。
/// MP4 单文件源不走这套代理，发现是 mp4 就直接交给 AVPlayer 播。
/// 只有确认分片真的是图片时，才退回图片轮播。
@Observable @MainActor
final class PlaybackEngine {
    enum Mode: Equatable {
        case idle
        case slideshow
        case avplayer
    }

    private(set) var mode: Mode = .idle
    private(set) var segments: [M3U8Segment] = []
    private(set) var currentIndex = 0
    private(set) var elapsed: Double = 0
    private(set) var isLoading = false
    private(set) var isBuffering = false
    private(set) var player: AVPlayer?

    var isPlaying = false
    var speed: Double = 1.0 {
        didSet { applyRate() }
    }
    /// 播放器音量（0...1），手势右滑上下调节的就是它。
    var volume: Float = 1.0 {
        didSet { player?.volume = volume }
    }
    /// 静音开关。
    var isMuted = false {
        didSet { player?.isMuted = isMuted }
    }
    var errorMessage: String?

    private var tickTask: Task<Void, Never>?
    private var source: HLSMediaSource?
    private var playerTime: Double = 0
    private var playerDuration: Double = 0

    var currentSegment: M3U8Segment? {
        segments.indices.contains(currentIndex) ? segments[currentIndex] : nil
    }

    /// 播放列表里 EXTINF 累加出来的时长，真实时长拿到之前先用它顶着。
    var playlistDuration: Double {
        segments.reduce(0) { $0 + $1.duration }
    }

    var totalDuration: Double {
        if mode == .avplayer, playerDuration > 0 { return playerDuration }
        return playlistDuration
    }

    var currentTime: Double {
        if mode == .avplayer { return playerTime }
        let previous = segments.prefix(currentIndex).reduce(0) { $0 + $1.duration }
        return previous + min(elapsed, currentSegment?.duration ?? 0)
    }

    var progress: Double {
        totalDuration > 0 ? min(max(currentTime / totalDuration, 0), 1) : 0
    }

    // MARK: - 加载

    func load(streamURL url: URL, headers: [String: String] = [:], mtype: String = "m3u8") async {
        stopTicking()
        teardown()
        isLoading = true
        errorMessage = nil

        let isMP4 = mtype == "mp4" || url.pathExtension.lowercased() == "mp4"

        if isMP4 {
            startMP4(origin: url)
            isLoading = false
            startTicking()
            return
        }

        do {
            let playlistData = try await MediaFetcher.data(from: url, extraHeaders: headers)
            let text = String(data: playlistData, encoding: .utf8) ?? ""
            let parsed = M3U8Parser.parse(text, baseURL: url)
            reset()
            segments = parsed

            let kind = await Self.probe(parsed, headers: headers)
            if kind == .image {
                mode = .slideshow
            } else {
                try await startVideo(origin: url, headers: headers, playlist: playlistData, kind: kind)
            }
        } catch {
            reset()
            errorMessage = error.localizedDescription
        }

        isLoading = false
        startTicking()
    }

    /// 只取分片头部 64KB 判断真实类型：图片 / TS / 伪装成图片的 TS / fMP4。
    private static func probe(_ segments: [M3U8Segment], headers: [String: String]) async -> SegmentKind {
        guard let first = segments.first,
              let data = try? await MediaFetcher.data(from: first.url, extraHeaders: headers, range: 0 ..< 65_536) else {
            return .unknown
        }
        return TSSegmentRepair.kind(of: data)
    }

    private func startVideo(
        origin: URL,
        headers: [String: String],
        playlist: Data,
        kind: SegmentKind
    ) async throws {
        let (url, source) = try await HLSLocalServer.mount(
            origin: origin,
            headers: headers,
            segmentExtension: kind == .fragmentedMP4 ? "m4s" : "ts",
            playlist: playlist
        )
        self.source = source

        let player = AVPlayer(playerItem: AVPlayerItem(asset: AVURLAsset(url: url)))
        player.automaticallyWaitsToMinimizeStalling = true
        player.volume = volume
        player.isMuted = isMuted
        self.player = player
        mode = .avplayer
        isPlaying = true
        configureAudioSession()
        applyRate()
    }

    /// MP4 单文件播放：不走 m3u8 那套本地代理逻辑，AVPlayer 直接播远程地址。
    private func startMP4(origin: URL) {
        let player = AVPlayer(playerItem: AVPlayerItem(asset: AVURLAsset(url: origin)))
        player.automaticallyWaitsToMinimizeStalling = true
        player.volume = volume
        player.isMuted = isMuted
        self.player = player
        mode = .avplayer
        isPlaying = true
        configureAudioSession()
        applyRate()
    }

    // 播放状态（status / duration / error / 缓冲）统一在 tick 里读，省掉一套 KVO。

    // MARK: - 播控

    func play() {
        switch mode {
        case .slideshow:
            if currentIndex == segments.count - 1, elapsed >= (currentSegment?.duration ?? 0) {
                seek(toFraction: 0)
            }
            isPlaying = true
            startTicking()
        case .avplayer:
            isPlaying = true
            applyRate()
        case .idle:
            break
        }
    }

    func pause() {
        isPlaying = false
        if mode == .avplayer { player?.pause() }
    }

    func tick(delta: Double) {
        switch mode {
        case .slideshow:
            guard isPlaying, let segment = currentSegment else { return }
            elapsed += delta * max(speed, 0.25)
            if elapsed >= segment.duration {
                if currentIndex + 1 < segments.count {
                    currentIndex += 1
                    elapsed = 0
                } else {
                    elapsed = segment.duration
                    isPlaying = false
                }
            }
        case .avplayer:
            guard let player else { return }
            let seconds = player.currentTime().seconds
            if seconds.isFinite, seconds >= 0 { playerTime = seconds }
            if let item = player.currentItem {
                let duration = item.duration.seconds
                if duration.isFinite, duration > 0 { playerDuration = duration }
                if item.status == .failed, errorMessage == nil {
                    errorMessage = item.error?.localizedDescription
                }
            }
            isBuffering = player.timeControlStatus == .waitingToPlayAtSpecifiedRate
            isPlaying = player.timeControlStatus == .playing
        case .idle:
            break
        }
    }

    func step() {
        guard mode == .slideshow, currentIndex + 1 < segments.count else { return }
        currentIndex += 1
        elapsed = 0
    }

    func back() {
        guard mode == .slideshow, currentIndex > 0 else { return }
        currentIndex -= 1
        elapsed = 0
    }

    func seek(toFraction fraction: Double) {
        let fraction = min(max(fraction, 0), 1)
        switch mode {
        case .slideshow:
            guard !segments.isEmpty else { return }
            var remaining = fraction * totalDuration
            var index = segments.count - 1
            for (i, segment) in segments.enumerated() {
                if remaining <= segment.duration {
                    index = i
                    break
                }
                remaining -= segment.duration
            }
            currentIndex = min(index, segments.count - 1)
            elapsed = min(remaining, segments[currentIndex].duration)
        case .avplayer:
            guard totalDuration > 0, let player else { return }
            playerTime = fraction * totalDuration
            player.seek(
                to: CMTime(seconds: playerTime, preferredTimescale: 600),
                toleranceBefore: .zero,
                toleranceAfter: .zero
            )
        case .idle:
            break
        }
    }

    func seek(to time: Double) {
        guard totalDuration > 0 else { return }
        seek(toFraction: time / totalDuration)
    }

    // MARK: - 生命周期

    func startTicking() {
        guard tickTask == nil else { return }
        tickTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(50))
                self?.tick(delta: 0.05)
            }
        }
    }

    func stopTicking() {
        tickTask?.cancel()
        tickTask = nil
    }

    /// 彻底放手：停播放器、卸载本地挂载、清缓存。
    func stop() {
        stopTicking()
        teardown()
    }

    private func teardown() {
        player?.pause()
        player?.replaceCurrentItem(with: nil)
        player = nil
        if let source {
            HLSLocalServer.shared.unregister(source)
        }
        source = nil
        reset()
    }

    private func reset() {
        mode = .idle
        segments = []
        currentIndex = 0
        elapsed = 0
        isPlaying = false
        isBuffering = false
        playerTime = 0
        playerDuration = 0
    }

    private func applyRate() {
        guard mode == .avplayer, let player else { return }
        player.rate = isPlaying ? Float(max(speed, 0.25)) : 0
    }

    private func configureAudioSession() {
        #if os(iOS)
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: .moviePlayback)
        try? session.setActive(true)
        #endif
    }
}
