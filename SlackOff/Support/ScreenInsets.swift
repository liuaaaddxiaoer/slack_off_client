import UIKit

/// 读取当前窗口的安全区，供「视频顶到状态栏后面」的沉浸式布局使用。
enum ScreenInsets {
    static var top: CGFloat {
        keyWindow?.safeAreaInsets.top ?? 20
    }

    static var bottom: CGFloat {
        keyWindow?.safeAreaInsets.bottom ?? 0
    }

    private static var keyWindow: UIWindow? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let windows = scenes.flatMap(\.windows)
        return windows.first(where: \.isKeyWindow) ?? windows.first
    }
}