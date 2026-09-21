import Foundation

/// 记录每个视频（slug）当前/上次选中的集数，退出详情页再进来也能恢复。
enum EpisodeStore {
    private static let storageKey = "com.slackoff.episode-selection"

    private static var map: [String: Int] =
        UserDefaults.standard.dictionary(forKey: storageKey) as? [String: Int] ?? [:]

    static func selectedEpisode(forSlug slug: String) -> Int? {
        map[slug]
    }

    static func remember(episode: Int, forSlug slug: String) {
        guard !slug.isEmpty, map[slug] != episode else { return }
        map[slug] = episode
        UserDefaults.standard.set(map, forKey: storageKey)
    }
}