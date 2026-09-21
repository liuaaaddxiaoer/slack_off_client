import Foundation

struct VideoCard: Codable, Identifiable, Hashable {
    let slug: String?
    let title: String?
    let url: String?
    let cover: String?
    let rating: String?
    let remark: String?
    let tag: String?
    let section: String?

    var id: String { slug ?? url ?? title ?? UUID().uuidString }
    var coverURL: URL? { cover.flatMap(URL.init(string:)) }
}

struct HomeResponse: Codable {
    let code: Int?
    let message: String?
    let items: [VideoCard]?
}

struct SearchResponse: Codable {
    let code: Int?
    let message: String?
    let items: [VideoCard]?
}

struct Episode: Codable, Identifiable, Hashable {
    let index: Int?
    let name: String?
    let url: String?
    let dataid: String?

    var id: Int { index ?? 0 }
    var number: Int { index ?? 0 }
    var displayName: String { name ?? "\(number)" }
}

struct VideoDetail: Codable {
    let slug: String?
    let title: String?
    let cover: String?
    let score: String?
    let director: String?
    let writer: String?
    let actor: String?
    let typeName: String?
    let area: String?
    let lang: String?
    let release: String?
    let duration: String?
    let alsoKnown: String?
    let description: String?
    let episodeCount: Int?
    let updateInfo: String?
    let episodes: [Episode]?

    var coverURL: URL? { cover.flatMap(URL.init(string:)) }
}

struct DetailResponse: Codable {
    let code: Int?
    let message: String?
    let detail: VideoDetail?
}

struct Stream: Codable, Hashable {
    let mtype: String?
    let bitrate: Int?
    let title: String?
    let description: String?
    let isVip: Bool?
    let locked: Bool?
    let url: String?
    let headers: [String: String]?

    var streamURL: URL? { url.flatMap(URL.init(string:)) }
}

struct PlayInfo: Codable {
    let slug: String?
    let dataid: String?
    let title: String?
    let episode: Int?
    let quality: String?
    let subtitleUrl: String?
    let streams: [Stream]?
}

struct PlayResponse: Codable {
    let code: Int?
    let message: String?
    let play: PlayInfo?
}
