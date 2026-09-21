import Foundation
import Observation

@Observable @MainActor
final class PlayerModel {
    let slug: String
    let title: String
    let episodes: [Episode]

    private(set) var currentEpisode: Int
    let engine = PlaybackEngine()
    private(set) var playInfo: PlayInfo?
    private(set) var comments: [DanmakuComment] = []
    private(set) var matchedAnime: AnimeSearchItem?

    /// 弹幕样式设置（颜色 / 速度 / 区域 / 透明度 / 字号）。
    let danmaku = DanmakuSettings()

    /// 快捷开关，直接映射到 `danmaku.enabled`。
    var showDanmaku: Bool {
        get { danmaku.enabled }
        set { danmaku.enabled = newValue }
    }

    var statusMessage: String?
    var speed: Double = 1.0 {
        didSet { engine.speed = speed }
    }

    init(slug: String, title: String, episodes: [Episode], episode: Int) {
        self.slug = slug
        self.title = title
        self.episodes = episodes
        self.currentEpisode = episode
    }

    var qualityTitle: String {
        playInfo?.streams?.last(where: { $0.streamURL != nil })?.title ?? "自动"
    }

    var timeText: String {
        "\(format(engine.currentTime)) / \(format(engine.totalDuration))"
    }

    func load() async {
        await reload()
    }

    func togglePlay() {
        engine.isPlaying ? engine.pause() : engine.play()
    }

    func next() {
        guard let index = episodes.firstIndex(where: { $0.number == currentEpisode }),
              index + 1 < episodes.count else { return }
        currentEpisode = episodes[index + 1].number
        EpisodeStore.remember(episode: currentEpisode, forSlug: slug)
        Task { await reload() }
    }

    func previous() {
        guard let index = episodes.firstIndex(where: { $0.number == currentEpisode }),
              index > 0 else { return }
        currentEpisode = episodes[index - 1].number
        EpisodeStore.remember(episode: currentEpisode, forSlug: slug)
        Task { await reload() }
    }

    func selectEpisode(_ number: Int) {
        guard number != currentEpisode else { return }
        currentEpisode = number
        EpisodeStore.remember(episode: number, forSlug: slug)
        Task { await reload() }
    }

    func setSpeed(_ value: Double) {
        speed = value
    }

    func seek(to time: Double) {
        engine.seek(to: time)
    }

    func seek(toFraction fraction: Double) {
        engine.seek(toFraction: fraction)
    }

    private func reload() async {
        statusMessage = nil
        comments = []
        matchedAnime = nil

        do {
            guard let info = try await VideoService.play(slug: slug, episode: currentEpisode) else {
                statusMessage = "没有播放信息"
                return
            }
            playInfo = info
            guard let stream = info.streams?.last(where: { $0.streamURL != nil }),
                  let url = stream.streamURL else {
                statusMessage = "没有可用清晰度"
                return
            }
            await engine.load(streamURL: url, headers: stream.headers ?? [:], mtype: stream.mtype ?? "m3u8")
            if let message = engine.errorMessage {
                statusMessage = message
            }
            engine.play()
        } catch {
            statusMessage = error.localizedDescription
        }

        await loadDanmaku()
    }

    private func loadDanmaku() async {
        guard let animes = try? await DanmakuService.searchAnime(keyword: title),
              !animes.isEmpty else { return }
        let match = animes.first(where: isAnimeMatch) ?? animes.first
        guard let match else { return }
        await bindDanmaku(match)
    }

    private func isAnimeMatch(_ anime: AnimeSearchItem) -> Bool {
        let animeTitle = anime.animeTitle ?? ""
        let cleanTitle = title.trimmingCharacters(in: .whitespacesAndNewlines)
        return animeTitle.contains(cleanTitle) || cleanTitle.contains(animeTitle)
    }

    private func bindDanmaku(_ anime: AnimeSearchItem) async {
        matchedAnime = anime
        guard let bangumi = try? await DanmakuService.bangumi(animeId: anime.animeId),
              let list = bangumi.episodes, !list.isEmpty else {
            comments = []
            return
        }
        let target = list.first { $0.episodeNumber == "\(currentEpisode)" } ?? list.first
        guard let episodeId = target?.episodeId else {
            comments = []
            return
        }
        comments = (try? await DanmakuService.comments(episodeId: episodeId)) ?? []
    }

    private func format(_ seconds: Double) -> String {
        let total = Int(seconds)
        return String(format: "%d:%02d", total / 60, total % 60)
    }
}
