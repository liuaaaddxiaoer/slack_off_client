import UIKit

final class AppDelegate: NSObject, UIApplicationDelegate {
    nonisolated(unsafe) static var orientationLock = UIInterfaceOrientationMask.all

    func application(
        _ application: UIApplication,
        supportedInterfaceOrientationsFor window: UIWindow?
    ) -> UIInterfaceOrientationMask {
        AppDelegate.orientationLock
    }
}

enum OrientationLocker {
    static func lockLandscape() {
        AppDelegate.orientationLock = .landscape
        rotate(to: .landscapeRight)
    }

    /// 退出全屏时强制回到竖屏（不再允许横屏，避免退出后还躺着）。
    static func lockPortrait() {
        AppDelegate.orientationLock = .portrait
        rotate(to: .portrait)
    }

    /// 进入竖屏全屏：保持竖屏，但允许用户自己旋转。
    static func unlock() {
        AppDelegate.orientationLock = .all
        rotate(to: .portrait)
    }

    /// iOS 16+ 强制旋转的标准做法：
    /// 1) 先让 window 的根控制器重新上报 supportedInterfaceOrientations（否则系统不认新掩码）；
    /// 2) 再 requestGeometryUpdate 请求目标朝向；
    /// 3) 失败或转场吞请求时，用 KVC 兜底 + 延时补发一次。
    private static func rotate(to orientation: UIInterfaceOrientation) {
        let mask: UIInterfaceOrientationMask = orientation.isLandscape ? .landscape : .portrait
        guard let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene else { return }

        if let root = scene.keyWindow?.rootViewController {
            root.setNeedsUpdateOfSupportedInterfaceOrientations()
        }

        let preferences = UIWindowScene.GeometryPreferences.iOS(interfaceOrientations: mask)
        scene.requestGeometryUpdate(preferences) { error in
            NSLog("[OrientationLocker] requestGeometryUpdate 失败: \(error.localizedDescription) → KVC 兜底")
            // requestGeometryUpdate 失败时的兜底（经典 KVC 方法）
            force(orientation: orientation)
        }

        // 转场动画可能吞掉第一次请求，稍后再补一次。
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
            scene.requestGeometryUpdate(preferences) { error in
                NSLog("[OrientationLocker] 重试 requestGeometryUpdate 失败: \(error.localizedDescription) → KVC 兜底")
                force(orientation: orientation)
            }
        }
    }

    private static func force(orientation: UIInterfaceOrientation) {
        UIDevice.current.setValue(orientation.rawValue, forKey: "orientation")
    }
}