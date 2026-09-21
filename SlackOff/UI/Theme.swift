import SwiftUI
import UIKit

/// 全局主题色：全部是「浅色 / 暗黑」双变体的动态色，
/// 跟随系统外观自动切换，页面里不要再写死 `.white` / `.black`。
enum Theme {
    /// 页面大背景。浅色是奶油粉，暗黑是深李色。
    static let cream = dynamic(
        light: Color(red: 1.00, green: 0.96, blue: 0.97),
        dark: Color(red: 0.10, green: 0.08, blue: 0.12)
    )

    /// 卡片 / 选集格子等浮起表面的底色。浅色是纯白，暗黑是比背景亮一档的深灰紫。
    static let card = dynamic(
        light: .white,
        dark: Color(red: 0.16, green: 0.13, blue: 0.19)
    )

    /// 主文字色。浅色是深李，暗黑是近白。
    static let plum = dynamic(
        light: Color(red: 0.29, green: 0.23, blue: 0.32),
        dark: Color(red: 0.96, green: 0.93, blue: 0.97)
    )

    /// 品牌粉（强调色 / 选中态）。暗黑下略微提亮，深底上更跳。
    static let pink = dynamic(
        light: Color(red: 1.00, green: 0.55, blue: 0.74),
        dark: Color(red: 1.00, green: 0.62, blue: 0.79)
    )

    /// 浅粉（导航栏底色 / 占位渐变）。暗黑下压成深粉，保证上面的白字可读。
    static let softPink = dynamic(
        light: Color(red: 1.00, green: 0.82, blue: 0.90),
        dark: Color(red: 0.45, green: 0.28, blue: 0.38)
    )

    static let lavender = dynamic(
        light: Color(red: 0.72, green: 0.65, blue: 1.00),
        dark: Color(red: 0.60, green: 0.54, blue: 0.90)
    )

    static let mint = dynamic(
        light: Color(red: 0.56, green: 0.89, blue: 0.81),
        dark: Color(red: 0.62, green: 0.92, blue: 0.85)
    )

    static let peach = dynamic(
        light: Color(red: 1.00, green: 0.70, blue: 0.60),
        dark: Color(red: 0.95, green: 0.60, blue: 0.50)
    )

    /// 细描边（未选中的格子 / 胶囊）。浅色用极淡的黑，暗黑用极淡的白。
    static let hairline = dynamic(
        light: Color.black.opacity(0.08),
        dark: Color.white.opacity(0.12)
    )

    static let gradient = LinearGradient(
        colors: [pink, lavender],
        startPoint: .topLeading,
        endPoint: .bottomTrailing
    )

    /// 生成跟随系统深浅色自动切换的颜色。
    ///
    /// 必须 `nonisolated`：UIKit 会在任何解析该颜色的线程上同步调用
    /// `dynamicProvider` 闭包，包括 SwiftUI 的异步渲染线程
    /// （ViewGraph.updateOutputsAsync）。若闭包继承默认的主 Actor 隔离，
    /// 离主线程解析时会触发 `dispatch_assert_queue` 断言，直接 SIGTRAP 崩溃
    /// （全屏切集等大量重绘的场景必现）。
    nonisolated private static func dynamic(light: Color, dark: Color) -> Color {
        Color(uiColor: UIColor { traits in
            traits.userInterfaceStyle == .dark ? UIColor(dark) : UIColor(light)
        })
    }
}
