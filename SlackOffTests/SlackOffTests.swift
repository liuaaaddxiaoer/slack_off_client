import AVFoundation
import Foundation
import Testing
@testable import SlackOff

struct SlackOffTests {
    @Test func m3u8ParsesImagesAndETSRedirects() {
        let text = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXTINF:4.25,
        https://sns-open-qc.xhscdn.com/a.png
        #EXTINF:3.0,
        /ets/1788576644-token/aHR0cHM6Ly9maWxlLmljdmUuY29tLmNuL3gucG5n
        """
        let base = URL(string: "https://oss.douyinbit.com/m3u8/x.m3u8")!
        let segments = M3U8Parser.parse(text, baseURL: base)

        #expect(segments.count == 2)
        #expect(segments[0].duration == 4.25)
        #expect(segments[0].url.absoluteString == "https://sns-open-qc.xhscdn.com/a.png")
        #expect(segments[1].duration == 3.0)
        #expect(segments[1].url.absoluteString.hasPrefix("https://file.icve.com.cn/"))
    }

    /// 超星那批播放列表是 CRLF 换行，Swift 里 "\r\n" 是一个 Character，
    /// 用 split(separator: "\n") 切不开，整条列表会被当成一行 → 0 个分片 → -1016。
    @Test func m3u8ParserHandlesCRLFPlaylists() {
        let text = "#EXTM3U\r\n#EXT-X-VERSION:3\r\n#EXTINF:3.000000,\r\n"
            + "https://p.ananas.chaoxing.com/star4/abc/origin.jpg\r\n#EXT-X-ENDLIST\r\n"
        let segments = M3U8Parser.parse(text, baseURL: URL(string: "https://oss.douyinbit.com/m3u8/x.m3u8")!)

        #expect(segments.count == 1)
        #expect(segments[0].duration == 3.0)
        #expect(segments[0].url.host == "p.ananas.chaoxing.com")
    }

    /// 伪装成 png 的 TS：假图片头 + 0x47/188 字节的真 TS 包 + IEND 尾巴。
    @Test func tsRepairStripsFakeImageWrapper() {
        let ts = Self.fakeTransportStream(packets: 6)

        var wrapper = Data([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A])
        wrapper.append(Data(repeating: 0xFF, count: 53))          // 61 字节假 PNG 头
        wrapper.append(ts)
        wrapper.append(Data([0x00, 0x00, 0x00, 0x00]))            // PNG IEND
        wrapper.append(Data("IEND".utf8))
        wrapper.append(Data([0xAE, 0x42, 0x60, 0x82]))

        #expect(TSSegmentRepair.kind(of: wrapper) == .wrappedTransportStream)
        #expect(TSSegmentRepair.repairedSegment(from: wrapper) == ts)
        #expect(TSSegmentRepair.kind(of: ts) == .transportStream)
        #expect(TSSegmentRepair.repairedSegment(from: ts) == ts)
        #expect(TSSegmentRepair.kind(of: Data([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A])) == .image)
    }

    /// 伪装头里那个能凑成 188 对齐链的假 0x47，不能把真正的 PAT（PID=0）挤成第二个包。
    /// 对应超星 origin.jpg：PNG tEXt 块里的 0x47 恰好在真 PAT 之前 188 字节，
    /// 旧逻辑会把它当包头，导致 PAT 错位、有声音没画面。
    @Test func tsRepairPrefersRealPATOverFakeSync() {
        var ts = Data()
        ts.append(Self.tsPacket(0x40, 0x00))                    // PAT：PID == 0
        for _ in 1..<8 { ts.append(Self.tsPacket(0x41, 0x00)) } // 视频：PID == 256

        // 假头正好 188 字节，第一个字节也是 0x47，+188/+376/+564 全落在真同步字节上。
        var head = Data(repeating: 0xFF, count: 188)
        head[0] = 0x47
        head[1] = 0x1F   // PID = 0x1FFF，不是 PAT
        head[2] = 0xFF

        let wrapped = head + ts
        #expect(TSSegmentRepair.kind(of: wrapped) == .wrappedTransportStream)
        #expect(TSSegmentRepair.repairedSegment(from: wrapped) == ts)
    }

    /// 图床防盗链各家规则相反：icve 带站点 Referer 会 403，超星不带 Referer 会 403。
    @Test func mediaFetcherTriesOwnOriginRefererFirst() {
        let url = URL(string: "https://p.ananas.chaoxing.com/star4/abc/origin.jpg")!
        let candidates = MediaFetcher.headerCandidates(for: url, extraHeaders: ["Referer": APIConfig.referer])

        #expect(candidates.count == 3)
        #expect(candidates[0]["Referer"] == "https://p.ananas.chaoxing.com/")
        #expect(candidates[1]["Referer"] == nil)
        #expect(candidates[2]["Referer"] == APIConfig.referer)
    }

    /// 端到端：真实源 → 本地回环服务 → AVPlayer。
    /// 顺带验证 ATS 不会拦 127.0.0.1 的 http。
    @Test(.timeLimit(.minutes(4))) func playsWrappedTransportStreamThroughLocalServer() async throws {
        let origin = URL(string: "https://oss.douyinbit.com/m3u8/45a33ed7aae4fef85d4ae67f78790c2c.m3u8")!
        guard let playlist = try? await MediaFetcher.data(from: origin) else {
            return  // 断网或源失效时不判失败
        }

        let (url, source) = try await HLSLocalServer.mount(
            origin: origin,
            segmentExtension: "ts",
            playlist: playlist
        )
        defer { HLSLocalServer.shared.unregister(source) }
        #expect(url.scheme == "http")
        #expect(url.host() == "127.0.0.1")

        // 回环 http 必须能通（ATS 不能拦）
        let (probe, probeResponse) = try await URLSession.shared.data(from: url)
        #expect((probeResponse as? HTTPURLResponse)?.statusCode == 200)
        #expect(String(data: probe.prefix(7), encoding: .utf8) == "#EXTM3U")

        let item = AVPlayerItem(asset: AVURLAsset(url: url))
        let player = AVPlayer(playerItem: item)
        player.rate = 1

        let deadline = Date().addingTimeInterval(120)
        while item.status == .unknown && Date() < deadline {
            try await Task.sleep(for: .milliseconds(200))
        }
        #expect(item.status == .readyToPlay, "播放失败: \(String(describing: item.error))")
        #expect(item.tracks.contains { $0.assetTrack?.mediaType == .video })
        #expect(CMTimeGetSeconds(item.duration) > 60)

        try await Task.sleep(for: .seconds(4))
        #expect(player.currentTime().seconds > 0.5, "时间没有推进")

        await player.seek(
            to: CMTime(seconds: CMTimeGetSeconds(item.duration) * 0.5, preferredTimescale: 600),
            toleranceBefore: .zero,
            toleranceAfter: .zero
        )
        try await Task.sleep(for: .seconds(4))
        #expect(player.currentTime().seconds > CMTimeGetSeconds(item.duration) * 0.4)
        player.pause()
    }

    /// 走 App 自己的完整链路：接口取播放地址 → 引擎嗅探 → 本地服务 → AVPlayer。
    @Test(.timeLimit(.minutes(4))) func enginePlaysEpisodeFromAPI() async throws {
        guard let card = try? await VideoService.home().first(where: { $0.slug != nil }),
              let slug = card.slug,
              let detail = try? await VideoService.detail(slug: slug),
              let episodes = detail.episodes, !episodes.isEmpty else {
            return  // 接口不可用时不判失败
        }

        let model = PlayerModel(
            slug: slug,
            title: detail.title ?? card.title ?? "",
            episodes: episodes,
            episode: episodes.first?.number ?? 1
        )
        await model.load()

        let deadline = Date().addingTimeInterval(120)
        while model.engine.mode == .idle && Date() < deadline {
            try await Task.sleep(for: .milliseconds(300))
        }
        #expect(
            model.engine.mode != .idle,
            "没有进入播放: status=\(model.statusMessage ?? "nil") error=\(model.engine.errorMessage ?? "nil")"
        )
        guard model.engine.mode == .avplayer else { return }   // 图片轮播源不继续判

        #expect(model.engine.player != nil)
        let playDeadline = Date().addingTimeInterval(90)
        while model.engine.currentTime < 0.5 && Date() < playDeadline {
            try await Task.sleep(for: .milliseconds(300))
        }
        #expect(model.engine.currentTime > 0.5, "时间没有推进: \(model.engine.errorMessage ?? "nil")")
        #expect(model.engine.totalDuration > 30)

        model.seek(toFraction: 0.5)
        try await Task.sleep(for: .seconds(6))
        #expect(model.engine.progress > 0.4, "拖动没有生效")
        model.engine.stop()
    }

    private static func fakeTransportStream(packets: Int) -> Data {
        var data = Data()
        for index in 0 ..< packets {
            var packet = Data(repeating: 0xFF, count: 188)
            packet[0] = 0x47
            packet[1] = UInt8(index % 256)
            data.append(packet)
        }
        return data
    }

    private static func tsPacket(_ byte1: UInt8, _ byte2: UInt8) -> Data {
        var packet = Data(repeating: 0xFF, count: 188)
        packet[0] = 0x47
        packet[1] = byte1
        packet[2] = byte2
        return packet
    }
}

struct DanmakuAndModelTests {
    @Test func danmakuCommentParsesPackedFields() {
        let item = CommentItem(cid: 42, p: "12.50,4,16777215,[qiyi]", m: "来了")
        let comment = DanmakuComment.from(item)

        #expect(comment?.text == "来了")
        #expect(comment?.time == 12.5)
        #expect(comment?.type == 4)
        #expect(comment?.color == 0xFFFFFF)
    }

    @Test func detailDecodesSnakeCaseFields() throws {
        let json = """
        {"code":0,"message":"ok","detail":{"slug":"abc","title":"师兄太稳健","type_name":"剧情","episode_count":30,"episodes":[{"index":1,"name":"1","url":"https://x","dataid":"37864"}]}}
        """
        let data = Data(json.utf8)
        let response = try APIConfig.videoDecoder.decode(DetailResponse.self, from: data)

        #expect(response.detail?.typeName == "剧情")
        #expect(response.detail?.episodeCount == 30)
        #expect(response.detail?.episodes?.first?.number == 1)
    }
}
