import AVFoundation
import SwiftUI
import UIKit

/// 只画画面的播放器视图。
///
/// 用 `AVPlayerLayer` 而不是 SwiftUI 的 `VideoPlayer`：后者会自带一套系统控制条，
/// 和这里的自定义进度条/弹幕/全屏手势打架，而且 body 每次重算都会新建一个 AVPlayer。
struct PlayerLayerView: UIViewRepresentable {
    let player: AVPlayer
    var videoGravity: AVLayerVideoGravity = .resizeAspect

    func makeUIView(context: Context) -> PlayerLayerHostView {
        let view = PlayerLayerHostView()
        view.backgroundColor = .black
        view.playerLayer.player = player
        view.playerLayer.videoGravity = videoGravity
        return view
    }

    func updateUIView(_ view: PlayerLayerHostView, context: Context) {
        if view.playerLayer.player !== player {
            view.playerLayer.player = player
        }
        view.playerLayer.videoGravity = videoGravity
    }
}

final class PlayerLayerHostView: UIView {
    override static var layerClass: AnyClass { AVPlayerLayer.self }

    var playerLayer: AVPlayerLayer {
        layer as! AVPlayerLayer
    }
}
