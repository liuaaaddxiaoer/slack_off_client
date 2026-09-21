import XCTest

/// 全屏播放中切换集数的回归测试。
///
/// 复现路径：首页 → 详情 → 播放 → 横屏全屏 → 选集面板 → 点另一集。
/// 通过标准：切集后 12 秒内 App 仍然活着（没崩）。
/// 首页第一张卡片如果不足 2 集，跳过（XCTSkip），不判失败。
nonisolated final class FullscreenEpisodeSwitchUITests: XCTestCase {
    func testSwitchEpisodeInFullscreenDoesNotCrash() throws {
        let app = XCUIApplication()
        app.launch()

        XCTAssertTrue(app.navigationBars.firstMatch.waitForExistence(timeout: 20), "首页导航栏没出现")
        Thread.sleep(forTimeInterval: 5)
        let homeCard = any("home.card", in: app).firstMatch
        guard homeCard.waitForExistence(timeout: 10) else {
            throw XCTSkip("首页没加载出卡片（接口不可用）")
        }
        homeCard.tap()

        // 详情页：等第 1 集按钮出来，点进播放页。
        let episodeOne = any("detail.episode.1", in: app)
        guard episodeOne.waitForExistence(timeout: 25) else {
            throw XCTSkip("详情页没加载出选集（接口不可用）")
        }
        episodeOne.tap()

        // 播放页：等播放器起来，进横屏全屏。
        let fullscreenButton = app.buttons["player.fullscreen.landscape"]
        guard fullscreenButton.waitForExistence(timeout: 30) else {
            throw XCTSkip("播放页没出现")
        }
        Thread.sleep(forTimeInterval: 8)
        fullscreenButton.tap()

        // 全屏：打开选集面板。
        let panelButton = app.buttons["player.episodes.panel"]
        guard panelButton.waitForExistence(timeout: 15) else {
            throw XCTSkip("全屏底栏没出现")
        }
        panelButton.tap()

        // 面板里找一个非当前集（当前是第 1 集，优先 2、3、4、5）。
        var target: XCUIElement?
        for number in 2...5 {
            let candidate = any("player.episode.\(number)", in: app)
            if candidate.waitForExistence(timeout: 2) {
                target = candidate
                break
            }
        }
        guard let target else {
            throw XCTSkip("首页第一张卡片不足 2 集，无法测切集")
        }
        target.tap()

        // 切集后 App 必须一直活着。
        var alive = false
        let deadline = Date().addingTimeInterval(12)
        while Date() < deadline {
            if app.state == .runningForeground { alive = true }
            Thread.sleep(forTimeInterval: 1)
        }
        XCTAssertEqual(app.state, .runningForeground, "全屏切集后 App 崩了")
        XCTAssertTrue(alive, "全屏切集后 App 一度退出前台")
    }

    /// 按 identifier 找任意类型的元素（SwiftUI 里同一个标识可能落在 Button/Link/Other 上）。
    private func any(_ identifier: String, in app: XCUIApplication) -> XCUIElement {
        app.descendants(matching: .any)[identifier]
    }
}
