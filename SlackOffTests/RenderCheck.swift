import SwiftUI
import Testing
import UIKit
@testable import SlackOff

@MainActor
struct RenderCheck {
    @Test(.timeLimit(.minutes(5))) func rendersPlayingVideo() async throws {
        guard let card = try? await VideoService.home().first(where: { $0.slug != nil }),
              let slug = card.slug,
              let detail = try? await VideoService.detail(slug: slug),
              let episodes = detail.episodes, !episodes.isEmpty else { return }

        let view = PlayerView(
            slug: slug,
            episode: episodes.first?.number ?? 1,
            title: detail.title ?? card.title ?? "",
            episodes: episodes
        )
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        window.rootViewController = UIHostingController(rootView: NavigationStack { view })
        window.makeKeyAndVisible()

        try await Task.sleep(for: .seconds(30))

        let renderer = UIGraphicsImageRenderer(bounds: window.bounds)
        let image = renderer.image { _ in window.drawHierarchy(in: window.bounds, afterScreenUpdates: true) }
        let data = try #require(image.pngData())
        let target = FileManager.default.temporaryDirectory.appendingPathComponent("player-render.png")
        try data.write(to: target)
        NSLog("[render] saved \(target.path) \(data.count) bytes")
    }
}
