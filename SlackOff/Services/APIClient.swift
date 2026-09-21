import Foundation

enum APIError: LocalizedError {
    case badStatus(Int)

    var errorDescription: String? {
        switch self {
        case .badStatus(let code): return "HTTP \(code)"
        }
    }
}

enum APIClient {
    static func loadData<T: Decodable>(
        _ url: URL,
        using decoder: JSONDecoder
    ) async throws -> T {
        let (data, response) = try await URLSession.shared.data(for: APIConfig.request(for: url))
        if let http = response as? HTTPURLResponse, !(200...299).contains(http.statusCode) {
            throw APIError.badStatus(http.statusCode)
        }
        return try decoder.decode(T.self, from: data)
    }
}

enum VideoService {
    static func home() async throws -> [VideoCard] {
        let url = APIConfig.videoBase.appending(path: "api/home")
        let response: HomeResponse = try await APIClient.loadData(url, using: APIConfig.videoDecoder)
        return response.items ?? []
    }

    static func search(_ query: String) async throws -> [VideoCard] {
        var components = URLComponents(url: APIConfig.videoBase.appending(path: "api/search"), resolvingAgainstBaseURL: false)!
        components.queryItems = [URLQueryItem(name: "q", value: query)]
        let response: SearchResponse = try await APIClient.loadData(components.url!, using: APIConfig.videoDecoder)
        return response.items ?? []
    }

    static func detail(slug: String) async throws -> VideoDetail? {
        let url = APIConfig.videoBase.appending(path: "api/detail/\(slug)")
        let response: DetailResponse = try await APIClient.loadData(url, using: APIConfig.videoDecoder)
        return response.detail
    }

    static func play(slug: String, episode: Int) async throws -> PlayInfo? {
        var components = URLComponents(url: APIConfig.videoBase.appending(path: "api/play/\(slug)"), resolvingAgainstBaseURL: false)!
        components.queryItems = [URLQueryItem(name: "episode", value: "\(episode)")]
        let response: PlayResponse = try await APIClient.loadData(components.url!, using: APIConfig.videoDecoder)
        return response.play
    }
}

enum DanmakuService {
    private static func danmuURL(_ path: String) -> URL {
        APIConfig.danmuBase.appending(path: "\(APIConfig.danmuToken)\(path)")
    }

    static func searchAnime(keyword: String) async throws -> [AnimeSearchItem] {
        var components = URLComponents(url: danmuURL("/api/v2/search/anime"), resolvingAgainstBaseURL: false)!
        components.queryItems = [URLQueryItem(name: "keyword", value: keyword)]
        let response: AnimeSearchResponse = try await APIClient.loadData(components.url!, using: APIConfig.danmuDecoder)
        return response.animes ?? []
    }

    static func bangumi(animeId: Int) async throws -> BangumiDetail? {
        let url = danmuURL("/api/v2/bangumi/\(animeId)")
        let response: BangumiResponse = try await APIClient.loadData(url, using: APIConfig.danmuDecoder)
        return response.bangumi
    }

    static func comments(episodeId: Int) async throws -> [DanmakuComment] {
        var components = URLComponents(url: danmuURL("/api/v2/comment/\(episodeId)"), resolvingAgainstBaseURL: false)!
        components.queryItems = [URLQueryItem(name: "format", value: "json")]
        let response: CommentResponse = try await APIClient.loadData(components.url!, using: APIConfig.danmuDecoder)
        return (response.comments ?? []).compactMap(DanmakuComment.from)
    }
}
