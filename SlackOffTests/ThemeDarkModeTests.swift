import UIKit
import SwiftUI
import Testing
@testable import SlackOff

struct ThemeDarkModeTests {
    /// 动态色闭包必须能在非主线程被同步调用。
    ///
    /// SwiftUI 的异步渲染线程（ViewGraph.updateOutputsAsync）会解析颜色，
    /// 若 `Theme.dynamic` 的闭包继承了默认的 MainActor 隔离，离主线程调用时
    /// 会触发 `dispatch_assert_queue_fail` 直接 SIGTRAP——全屏切集等大量重绘
    /// 的场景必现（见 SlackOff-*.ips 崩溃报告）。
    ///
    /// 这里在主 Actor 上把动态色包成 UIColor（不触发解析），再在脱离主 Actor
    /// 的任务里反复 `resolvedColor(with:)`，应全程不崩。
    @Test func dynamicColorResolvesOffMainThread() async {
        let darkTraits = UITraitCollection(userInterfaceStyle: .dark)
        let lightTraits = UITraitCollection(userInterfaceStyle: .light)

        let colors: [UIColor] = [
            UIColor(Theme.cream),
            UIColor(Theme.plum),
            UIColor(Theme.card),
            UIColor(Theme.hairline),
            UIColor(Theme.softPink),
        ]

        await Task.detached(priority: .userInitiated) {
            for _ in 0 ..< 500 {
                for color in colors {
                    _ = color.resolvedColor(with: darkTraits)
                    _ = color.resolvedColor(with: lightTraits)
                }
            }
        }.value
    }
}
