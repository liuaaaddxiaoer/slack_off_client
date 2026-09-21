import XCTest

/// 小说频道的真实运行截图。
///
/// 离屏渲染（NovelRenderCheck）覆盖不到 ScrollView / LazyVGrid 这类需要 viewport 的布局，
/// 也拿不到真实网络数据，所以这里在模拟器里真的把 App 跑起来：
/// 切小说 tab → 等书城回源 → 截图 → 进详情 → 进阅读器 → 唤出菜单。
/// 截图直接写到 build/novel-shots/（模拟器进程能写宿主文件系统）。
nonisolated final class NovelChannelUITests: XCTestCase {
    private let shotsDir = URL(fileURLWithPath: "/Users/mac/Documents/ChatGPT/slack_off/build/novel-shots")

    override func setUpWithError() throws {
        // 截图测试里某一步找不到元素不该让整个用例红，跳过即可
        continueAfterFailure = true
    }

    func testNovelChannelScreenshots() throws {
        let app = XCUIApplication()
        app.launch()

        let novelTab = app.tabBars.buttons["小说"]
        guard novelTab.waitForExistence(timeout: 20) else {
            throw XCTSkip("找不到小说 tab")
        }
        novelTab.tap()

        // 书城首屏要实时回源抓笔趣阁，给足时间
        Thread.sleep(forTimeInterval: 14)
        try save(app, "ui-bookstore")

        app.swipeUp()
        Thread.sleep(forTimeInterval: 3)
        try save(app, "ui-bookstore-scrolled")

        // 进详情：点第一张书籍卡片。不依赖具体书名 ——
        // 服务端的健康度路由会在源站抖动时换源，推荐位内容每天都在变。
        let firstCard = app.descendants(matching: .any)
            .matching(identifier: "novel.card").firstMatch
        guard firstCard.waitForExistence(timeout: 8) else {
            throw XCTSkip("书城没加载出书籍卡片，可能服务未连通")
        }
        firstCard.tap()
        Thread.sleep(forTimeInterval: 10)
        try save(app, "ui-detail")

        // 按钮文案随书架进度在「开始阅读 / 续读 第x章」之间变，两种都接受
        let startReading = app.buttons.matching(
            NSPredicate(format: "label BEGINSWITH '开始阅读' OR label BEGINSWITH '续读'")
        ).firstMatch
        guard startReading.waitForExistence(timeout: 8) else {
            throw XCTSkip("详情页没出现阅读按钮")
        }
        startReading.tap()
        Thread.sleep(forTimeInterval: 10)
        try save(app, "ui-reader")

        // 点页面中央唤出上下菜单
        app.windows.firstMatch
            .coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
            .tap()
        Thread.sleep(forTimeInterval: 1)
        try save(app, "ui-reader-menu")

        // 打开设置面板
        let settingsButton = app.buttons["设置"].firstMatch
        if settingsButton.waitForExistence(timeout: 5) {
            settingsButton.tap()
            Thread.sleep(forTimeInterval: 1)
            try save(app, "ui-reader-settings")
        }
    }

    private func save(_ app: XCUIApplication, _ name: String) throws {
        let data = app.screenshot().pngRepresentation
        let url = shotsDir.appendingPathComponent("\(name).png")
        try data.write(to: url)
        XCTAssertTrue(data.count > 10_000, "\(name) 截图疑似空白（\(data.count) 字节）")
    }
}
