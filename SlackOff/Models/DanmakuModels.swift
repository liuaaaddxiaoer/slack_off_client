import Foundation

struct AnimeSearchItem: Codable, Identifiable, Hashable {
    let animeId: Int
    let bangumiId: String?
    let animeTitle: String?
    let imageUrl: String?
    let type: String?
    let episodeCount: Int?

    var id: Int { animeId }
    var displayTitle: String { animeTitle ?? "番剧 \(animeId)" }
}

struct AnimeSearchResponse: Codable {
    let errorCode: Int?
    let success: Bool?
    let errorMessage: String?
    let animes: [AnimeSearchItem]?
}

struct BangumiEpisode: Codable, Identifiable, Hashable {
    let seasonId: String?
    let episodeId: Int
    let episodeTitle: String?
    let episodeNumber: String?
    let airDate: String?

    var id: Int { episodeId }
}

struct BangumiDetail: Codable {
    let animeId: Int?
    let bangumiId: String?
    let animeTitle: String?
    let imageUrl: String?
    let isOnAir: Bool?
    let type: String?
    let episodes: [BangumiEpisode]?
}

struct BangumiResponse: Codable {
    let errorCode: Int?
    let success: Bool?
    let errorMessage: String?
    let bangumi: BangumiDetail?
}

struct CommentItem: Codable, Hashable {
    let cid: Int
    let p: String?
    let m: String?
}

struct CommentResponse: Codable {
    let count: Int?
    let comments: [CommentItem]?
}

struct DanmakuComment: Identifiable, Equatable {
    let id = UUID()
    let text: String
    let time: Double
    let lane: Double
    let type: Int
    let color: UInt32

    static func from(_ item: CommentItem) -> DanmakuComment? {
        guard let text = item.m, !text.isEmpty, let packed = item.p else { return nil }
        let parts = packed.split(separator: ",")
        guard let time = Double(parts[0]) else { return nil }
        let type = parts.count > 1 ? Int(parts[1]) ?? 1 : 1
        let color = parts.count > 2 ? UInt32(parts[2]) ?? 0xFFFFFF : 0xFFFFFF
        let lane = Double(abs(item.cid.hashValue % 7))
        return DanmakuComment(text: text, time: time, lane: lane, type: type, color: color)
    }
}
