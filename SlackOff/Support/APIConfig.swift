import Foundation

nonisolated enum APIConfig {
    static let videoBase = URL(string: "https://tanlang008-up14load.hf.space")!
    static let danmuBase = URL(string: "https://jokkad-danmu-api.hf.space")!
    static let danmuToken = "123456"

    static let referer = "https://www.4kvm.org/"
    static let userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    static let videoDecoder: JSONDecoder = {
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        return decoder
    }()

    static let danmuDecoder = JSONDecoder()

    // MARK: - 小说服务（本地 xiaoshuo_server）

    /// 小说接口全是 snake_case，与视频接口同策略，但用独立 decoder 避免耦合。
    static let novelDecoder: JSONDecoder = {
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        return decoder
    }()

    /// 小说请求：超时 60s（首次回源要实时抓源站 HTML，比视频接口慢得多），
    /// 且**不带** Referer —— 视频那套 `https://www.4kvm.org/` 对本地小说服务没有意义。
    static func novelRequest(for url: URL) -> URLRequest {
        var request = URLRequest(url: url)
        request.timeoutInterval = 60
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        return request
    }

    static func request(for url: URL) -> URLRequest {
        var request = URLRequest(url: url)
        request.timeoutInterval = 30
        request.setValue(referer, forHTTPHeaderField: "Referer")
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        return request
    }
}
