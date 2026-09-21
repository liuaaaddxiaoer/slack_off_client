import SwiftUI
import AVKit
import AVFoundation

struct PlayerView: View {
    @Environment(\.dismiss) private var dismiss

    @State private var model: PlayerModel
    @State private var fullscreenMode: FullscreenMode?
    @State private var controlsVisible = true
    @State private var showDanmakuSettings = false
    @State private var toast: String?

    init(slug: String, episode: Int, title: String, episodes: [Episode]) {
        _model = State(initialValue: PlayerModel(slug: slug, title: title, episodes: episodes, episode: episode))
    }

    private enum FullscreenMode: Equatable {
        case portrait, landscape
    }

    var body: some View {
        @Bindable var model = model

        VStack(spacing: 0) {
            player

            VStack(spacing: 14) {
                episodeStrip
                Spacer(minLength: 0)
            }
            .padding(.vertical, 10)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Theme.cream)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.black)   // 状态栏区域与视频同色，画面不被顶部遮挡
        .toolbar(.hidden, for: .navigationBar)
        .toolbar(.hidden, for: .tabBar)
        .statusBarHidden(fullscreenMode != nil && !controlsVisible)
        .persistentSystemOverlays(fullscreenMode == nil ? .automatic : (controlsVisible ? .visible : .hidden))
        .overlay {
            if let mode = fullscreenMode {
                FullscreenPlayerView(model: model, controlsVisible: $controlsVisible) {
                    exitFullscreen()
                }
                .ignoresSafeArea()
                .transition(fullscreenTransition(for: mode))
                .zIndex(10)
            }
        }
        .overlay {
            if showDanmakuSettings {
                settingsDrawer
            }
        }
        .animation(.easeInOut(duration: 0.24), value: showDanmakuSettings)
        .overlay(alignment: .top) {
            if let toast {
                Text(toast)
                    .font(.subheadline.bold())
                    .foregroundStyle(.white)
                    .padding(.horizontal, 18)
                    .padding(.vertical, 10)
                    .background(.black.opacity(0.72), in: Capsule())
                    .padding(.top, ScreenInsets.top + 14)
                    .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
        .animation(.easeInOut(duration: 0.22), value: toast)
        .task {
            await model.load()
        }
        .onDisappear {
            // 进全屏时不要真的停掉播放
            if fullscreenMode == nil {
                model.engine.stop()
            }
        }
    }

    // MARK: - 沉浸式内嵌播放器

    private var player: some View {
        ZStack {
            mediaLayer

            if model.engine.mode == .avplayer, !model.engine.isPlaying {
                Button {
                    model.togglePlay()
                } label: {
                    Image(systemName: "play.circle.fill")
                        .font(.system(size: 66))
                        .foregroundStyle(.white)
                        .shadow(color: .black.opacity(0.35), radius: 8)
                }
            }

            VStack {
                inlineTopBar
                Spacer()
                bottomControls
            }
        }
        .frame(maxWidth: .infinity)
        .aspectRatio(16.0 / 9.0, contentMode: .fit)
        .background(.black)
    }

    private var inlineTopBar: some View {
        HStack(spacing: 12) {
            Button {
                dismiss()
            } label: {
                Image(systemName: "chevron.left")
                    .font(.title3.weight(.semibold))
            }
            Text(model.title)
                .font(.subheadline.bold())
                .lineLimit(1)
            Spacer()
            Button {
                withAnimation(.easeInOut(duration: 0.24)) { showDanmakuSettings = true }
            } label: {
                Image(systemName: "slider.horizontal.3")
            }
        }
        .foregroundStyle(.white)
        .padding(.horizontal, 16)
        .padding(.top, 8)
        .padding(.bottom, 8)
        .background(
            LinearGradient(colors: [.black.opacity(0.55), .clear], startPoint: .top, endPoint: .bottom)
        )
    }

    private var bottomControls: some View {
        VStack(spacing: 2) {
            VideoSlider(value: Binding(get: { model.engine.progress }, set: { model.seek(toFraction: $0) }))
            HStack(spacing: 14) {
                Button { model.togglePlay() } label: {
                    Image(systemName: model.engine.isPlaying ? "pause.fill" : "play.fill")
                        .font(.title3)
                        .frame(width: 34, height: 34)
                }
                Button {
                    model.engine.isMuted.toggle()
                } label: {
                    Image(systemName: model.engine.isMuted ? "speaker.slash.fill" : "speaker.wave.2.fill")
                        .frame(width: 34, height: 34)
                }
                Text(model.timeText)
                    .font(.caption2.monospacedDigit())
                    .lineLimit(1)
                Spacer()
                Button { toggleDanmaku() } label: {
                    Image(systemName: model.showDanmaku ? "captions.bubble.fill" : "captions.bubble")
                        .font(.body)
                }
                Button { enterFullscreen(landscape: false) } label: {
                    Image(systemName: "rectangle.portrait.arrowtriangle.2.outward")
                        .font(.body)
                }
                Button { enterFullscreen(landscape: true) } label: {
                    Image(systemName: "arrow.up.left.and.arrow.down.right")
                        .font(.body)
                }
                .accessibilityIdentifier("player.fullscreen.landscape")
            }
            .foregroundStyle(.white)
            .frame(height: 34)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(
            LinearGradient(colors: [.clear, .black.opacity(0.6)], startPoint: .top, endPoint: .bottom)
        )
    }

    @ViewBuilder
    private var mediaLayer: some View {
        switch model.engine.mode {
        case .slideshow:
            ZStack {
                AsyncImage(url: model.engine.currentSegment?.url) { phase in
                    switch phase {
                    case .success(let image):
                        image.resizable().scaledToFit()
                    case .failure:
                        placeholder("图片加载失败")
                    default:
                        ProgressView().tint(.white)
                    }
                }
                if model.showDanmaku {
                    DanmakuView(comments: model.comments, currentTime: model.engine.currentTime, settings: model.danmaku)
                }
            }
        case .avplayer:
            ZStack {
                if let player = model.engine.player {
                    PlayerLayerView(player: player)
                } else {
                    Color.black
                }
                if model.engine.isBuffering {
                    ProgressView().tint(.white)
                }
                if model.showDanmaku {
                    DanmakuView(comments: model.comments, currentTime: model.engine.currentTime, settings: model.danmaku)
                }
                if let message = model.engine.errorMessage {
                    placeholder(message)
                }
            }
        case .idle:
            placeholder(model.engine.isLoading ? "加载中…" : (model.statusMessage ?? "点击播放"))
        }
    }

    private func placeholder(_ text: String) -> some View {
        ZStack {
            Theme.gradient
            Text(text).font(.subheadline).foregroundStyle(.white)
        }
    }

    private var episodeStrip: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(model.episodes) { episode in
                    let isSelected = episode.number == model.currentEpisode
                    Button {
                        model.selectEpisode(episode.number)
                    } label: {
                        Text(episode.displayName)
                            .font(.footnote.bold())
                            .foregroundStyle(isSelected ? Color.white : Theme.plum)
                            .padding(.horizontal, 13)
                            .padding(.vertical, 8)
                            .background(
                                isSelected ? AnyShapeStyle(Theme.pink) : AnyShapeStyle(Theme.card),
                                in: Capsule()
                            )
                            .overlay {
                                if !isSelected {
                                    Capsule().strokeBorder(Theme.hairline, lineWidth: 1)
                                }
                            }
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 16)
        }
    }

    // MARK: - 弹幕设置抽屉

    private var settingsDrawer: some View {
        ZStack(alignment: .trailing) {
            Color.black.opacity(0.4)
                .onTapGesture {
                    withAnimation(.easeInOut(duration: 0.24)) { showDanmakuSettings = false }
                }
            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    Text("弹幕设置")
                        .font(.headline)
                    Spacer()
                    Button {
                        withAnimation(.easeInOut(duration: 0.24)) { showDanmakuSettings = false }
                    } label: {
                        Image(systemName: "xmark")
                            .foregroundStyle(.secondary)
                    }
                }
                .padding(16)
                Divider()
                DanmakuSettingsView(settings: model.danmaku)
            }
            .frame(width: 320)
            .frame(maxHeight: .infinity, alignment: .top)
            .background(Color.black)
            .preferredColorScheme(.dark)
            .transition(.move(edge: .trailing))
        }
        .transition(.opacity)
    }

    // MARK: - 全屏

    private func fullscreenTransition(for mode: FullscreenMode) -> AnyTransition {
        switch mode {
        case .portrait:
            return .scale(scale: 0.5, anchor: .top).combined(with: .opacity)
        case .landscape:
            // 横屏：旋转 + 放大，模拟手机转横屏的动画。
            return .rotateToLandscape
        }
    }

    private func enterFullscreen(landscape: Bool) {
        if landscape {
            OrientationLocker.lockLandscape()
        } else {
            OrientationLocker.unlock()
        }
        controlsVisible = true
        withAnimation(.spring(response: 0.42, dampingFraction: 0.88)) {
            fullscreenMode = landscape ? .landscape : .portrait
        }
    }

    private func exitFullscreen() {
        OrientationLocker.lockPortrait()
        withAnimation(.spring(response: 0.32, dampingFraction: 0.9)) {
            fullscreenMode = nil
        }
    }

    private func toggleDanmaku() {
        model.showDanmaku.toggle()
        showToast(model.showDanmaku ? "弹幕已开启" : "弹幕已关闭")
    }

    private func showToast(_ text: String) {
        toast = nil
        withAnimation(.easeOut(duration: 0.12)) { toast = text }
        Task {
            try? await Task.sleep(for: .seconds(1.2))
            if !Task.isCancelled, self.toast == text {
                withAnimation(.easeIn(duration: 0.2)) { self.toast = nil }
            }
        }
    }
}

// MARK: - 横屏旋转转场

private struct PlayerRotateModifier: ViewModifier {
    let angle: Double
    let scale: CGFloat
    let opacity: Double

    func body(content: Content) -> some View {
        content
            .rotationEffect(.degrees(angle))
            .scaleEffect(scale)
            .opacity(opacity)
    }
}

extension AnyTransition {
    /// 横屏进入：从 -90° 旋转回正 + 放大 + 淡入，模拟手机转横屏。
    static var rotateToLandscape: AnyTransition {
        .modifier(
            active: PlayerRotateModifier(angle: -90, scale: 0.8, opacity: 0),
            identity: PlayerRotateModifier(angle: 0, scale: 1, opacity: 1)
        )
    }
}