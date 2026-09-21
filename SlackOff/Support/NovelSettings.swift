import SwiftUI
import UIKit

// MARK: - 阅读器外观枚举

/// 阅读背景纸色。深灰 / 夜黑两档下正文自动转浅色。
enum NovelPaper: String, CaseIterable, Codable, Identifiable {
    case white
    case cream
    case green
    case gray
    case night

    var id: String { rawValue }

    var name: String {
        switch self {
        case .white: "白"
        case .cream: "米黄"
        case .green: "护眼绿"
        case .gray: "深灰"
        case .night: "夜黑"
        }
    }

    var isDark: Bool { self == .gray || self == .night }

    var background: Color {
        switch self {
        case .white: Color(red: 1.00, green: 1.00, blue: 1.00)
        case .cream: Color(red: 0.96, green: 0.93, blue: 0.87)
        case .green: Color(red: 0.80, green: 0.91, blue: 0.81)
        case .gray: Color(red: 0.23, green: 0.23, blue: 0.23)
        case .night: Color(red: 0.08, green: 0.07, blue: 0.10)
        }
    }

    /// 正文色。
    var text: Color {
        switch self {
        case .white: Color(red: 0.13, green: 0.13, blue: 0.15)
        case .cream: Color(red: 0.25, green: 0.21, blue: 0.16)
        case .green: Color(red: 0.16, green: 0.24, blue: 0.18)
        case .gray: Color(red: 0.82, green: 0.82, blue: 0.84)
        case .night: Color(red: 0.62, green: 0.62, blue: 0.68)
        }
    }

    /// 页脚 / 章节标题等次要文字色。
    var secondaryText: Color { text.opacity(0.55) }

    /// 上下工具栏面板底色：纸色掺 10% 文字色。
    ///
    /// 直接铺 `background` 与纸面同色 → 看不出面板边界；
    /// 掺一点文字色后与纸色对比约 1.25，边界可辨，且 5 种纸色下按钮文字
    /// 对比度仍有 7.9~12.7。**返回的是不透明实色**，正文不会从工具栏下透出来。
    var barSurface: Color { Color(uiColor: UIColor(background).blended(with: UIColor(text), ratio: 0.10)) }
}

private extension UIColor {
    /// 按比例把 `other` 混进自身（ratio=0 返回自身，1 返回 other）。
    func blended(with other: UIColor, ratio: CGFloat) -> UIColor {
        let r = min(max(ratio, 0), 1)
        var r1: CGFloat = 0, g1: CGFloat = 0, b1: CGFloat = 0, a1: CGFloat = 0
        var r2: CGFloat = 0, g2: CGFloat = 0, b2: CGFloat = 0, a2: CGFloat = 0
        getRed(&r1, green: &g1, blue: &b1, alpha: &a1)
        other.getRed(&r2, green: &g2, blue: &b2, alpha: &a2)
        return UIColor(
            red: r1 + (r2 - r1) * r,
            green: g1 + (g2 - g1) * r,
            blue: b1 + (b2 - b1) * r,
            alpha: a1 + (a2 - a1) * r
        )
    }
}

/// 行距三档：行高 = 字号 × multiplier。
enum NovelLineSpacing: String, CaseIterable, Codable, Identifiable {
    case compact
    case standard
    case loose

    var id: String { rawValue }

    var name: String {
        switch self {
        case .compact: "紧凑"
        case .standard: "标准"
        case .loose: "宽松"
        }
    }

    var multiplier: CGFloat {
        switch self {
        case .compact: 1.2
        case .standard: 1.5
        case .loose: 1.8
        }
    }
}

/// 翻页方式。
enum NovelPagingMode: String, CaseIterable, Codable, Identifiable {
    /// 仿真翻页（iOS: UIPageViewController .pageCurl）
    case curl
    /// 覆盖滑动（左右整页平移）
    case cover
    /// 上下滚动（连续长条）
    case scroll

    var id: String { rawValue }

    var name: String {
        switch self {
        case .curl: "仿真"
        case .cover: "覆盖"
        case .scroll: "滚动"
        }
    }

    var systemImage: String {
        switch self {
        case .curl: "book.pages"
        case .cover: "rectangle.split.2x1"
        case .scroll: "arrow.up.and.down.square"
        }
    }
}

// MARK: - 设置存储

/// 小说频道的全局设置：服务地址、数据源偏好、阅读器排版与外观。
///
/// 全部本地持久化（UserDefaults），跨书生效、跨启动保留。
/// 服务地址之所以可配：模拟器直连 `127.0.0.1:4321` 即可，
/// 真机的 127.0.0.1 是手机自己，必须改填 Mac 的局域网 IP。
@Observable
final class NovelSettings {
    static let shared = NovelSettings()

    static let defaultStorageKey = "com.slackoff.novel-reader-settings"
    private let storageKey: String
    /// 可注入的 UserDefaults，理由同 BookshelfStore。
    private let defaults: UserDefaults
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    // 服务地址
    var apiHost: String { didSet { persist() } }
    var apiPort: Int { didSet { persist() } }

    /// `auto` 或具体源 id（bqg99 / blqvdu / biquge365）。
    var sourceChoice: String { didSet { persist() } }

    /// 上次 `/api/home` 实际命中的源。
    ///
    /// 分类字典与排行榜的响应里不带 source，而 slug / book_id 跨源不通用，
    /// 所以首屏要在拿到 home 之前就并发请求它们，只能沿用上次记住的活跃源；
    /// home 返回后若发现源变了，再重拉一次分类。
    var lastActiveSource: String { didSet { persist() } }

    // 阅读器排版
    var fontSize: Double { didSet { persist() } }
    var lineSpacing: NovelLineSpacing { didSet { persist() } }

    // 阅读器外观
    var paper: NovelPaper { didSet { persist() } }
    var pagingMode: NovelPagingMode { didSet { persist() } }
    /// nil = 不干预系统亮度；非 nil 时进入阅读器即应用。
    var brightness: Double? { didSet { persist() } }

    /// 上次从磁盘恢复配置失败的原因（nil = 正常）。
    private(set) var lastLoadError: String?

    /// 初始化 / 恢复快照期间置 true，用于抑制 persist()。
    ///
    /// 必须抑制，否则会踩两个坑（都实测复现过）：
    /// 1. `apply()` 里逐字段赋值触发 didSet → persist()，此刻后面的字段还是默认值，
    ///    写回磁盘的是「半成品快照」；
    /// 2. 更隐蔽的是 `brightness: Double?` —— Optional 存储属性在 Swift 里自动初始化为 nil，
    ///    所以 init 里的 `brightness = nil` 属于**第二次赋值**，didSet 照样触发，
    ///    在读盘之前就把「全默认值快照」盖掉了磁盘上原本正确的配置。
    private var isRestoring = false

    struct Snapshot: Codable {
        var apiHost: String
        var apiPort: Int
        var sourceChoice: String
        var lastActiveSource: String?
        var fontSize: Double
        var lineSpacing: NovelLineSpacing
        var paper: NovelPaper
        var pagingMode: NovelPagingMode
        var brightness: Double?
    }

    init(
        defaults: UserDefaults = .standard,
        storageKey: String = NovelSettings.defaultStorageKey
    ) {
        self.defaults = defaults
        self.storageKey = storageKey
        // 整个 init 期间都不许写盘（原因见 isRestoring 注释）
        isRestoring = true
        // 先给默认值，再用磁盘上的快照覆盖。
        apiHost = "127.0.0.1"
        apiPort = 4321
        sourceChoice = "auto"
        lastActiveSource = "bqg99"
        fontSize = 18
        lineSpacing = .standard
        paper = .cream
        pagingMode = .scroll
        brightness = nil

        defer { isRestoring = false }

        guard let data = defaults.data(forKey: storageKey) else { return }
        do {
            let snapshot = try decoder.decode(Snapshot.self, from: data)
            apply(snapshot)
        } catch {
            // 配置损坏时不抛，退回默认值即可；错误留痕便于设置页排障
            lastLoadError = error.localizedDescription
        }
    }

    private func apply(_ snapshot: Snapshot) {
        apiHost = snapshot.apiHost
        apiPort = snapshot.apiPort
        sourceChoice = snapshot.sourceChoice
        if let savedSource = snapshot.lastActiveSource, !savedSource.isEmpty {
            lastActiveSource = savedSource
        }
        fontSize = snapshot.fontSize
        lineSpacing = snapshot.lineSpacing
        paper = snapshot.paper
        pagingMode = snapshot.pagingMode
        brightness = snapshot.brightness
    }

    private func persist() {
        guard !isRestoring else { return }
        let snapshot = Snapshot(
            apiHost: apiHost,
            apiPort: apiPort,
            sourceChoice: sourceChoice,
            lastActiveSource: lastActiveSource,
            fontSize: fontSize,
            lineSpacing: lineSpacing,
            paper: paper,
            pagingMode: pagingMode,
            brightness: brightness
        )
        guard let data = try? encoder.encode(snapshot) else { return }
        defaults.set(data, forKey: storageKey)
    }

    /// 字号允许范围（设置面板滑杆用）。
    static let fontSizeRange: ClosedRange<Double> = 14...30

    /// 服务基址。host 允许用户直接粘 `http://192.168.1.5` 这种带 scheme 的形式。
    var baseURL: URL {
        NovelSettings.makeBaseURL(host: apiHost, port: apiPort)
    }

    /// 纯函数版基址构造，便于单测。
    nonisolated static func makeBaseURL(host: String, port: Int) -> URL {
        let trimmed = host.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.contains("://"), let url = URL(string: trimmed) {
            let scheme = url.scheme ?? "http"
            let realHost = url.host ?? trimmed
            let realPort = url.port ?? port
            return URL(string: "\(scheme)://\(realHost):\(realPort)")!
        }
        let bare = trimmed.isEmpty ? "127.0.0.1" : trimmed
        return URL(string: "http://\(bare):\(port)")!
    }

    /// 夜间模式开关：打开即切到夜黑纸色，关闭回到米黄。
    var isNightMode: Bool {
        get { paper == .night }
        set { paper = newValue ? .night : .cream }
    }
}
