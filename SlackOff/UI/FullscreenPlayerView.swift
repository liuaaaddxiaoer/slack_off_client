import SwiftUI
import AVKit
import AVFoundation
import UIKit

struct FullscreenPlayerView: View {
    let model: PlayerModel
    @Binding var controlsVisible: Bool
    let onExit: () -> Void

    @State private var sidePanel: PlayerSidePanel?
    @State private var isLocked = false
    @State private var dragAxis: PlayerDragAxis?
    @State private var dragStart: CGFloat?
    @State private var hud: PlayerHUD = .idle
    @State private var brightness: CGFloat = 1.0
    @State private var centerIcon: String?
    @State private var toast: String?

    var body: some View {
        @Bindable var model = model

        GeometryReader { geo in
            let landscape = geo.size.width > geo.size.height

            ZStack {
                Color.black.ignoresSafeArea()

                // 竖屏/横屏同一套布局：画面等比缩放铺满，上下/两侧留黑边。
                mediaLayer

                if controlsVisible {
                    topBar(isLandscape: landscape)
                    VStack { Spacer(); bottomBar }
                }

                gestureHUD

                if isLocked || controlsVisible {
                    lockButton
                }
            }
            .contentShape(Rectangle())
            .simultaneousGesture(playerGesture(in: geo.size))
            .onTapGesture(count: 2) {
                guard !isLocked else { return }
                model.togglePlay()
                flashCenterIcon()
            }
            .onTapGesture(count: 1) {
                guard !isLocked else { return }
                withAnimation(.easeInOut(duration: 0.18)) {
                    controlsVisible.toggle()
                }
            }
        }
        .preferredColorScheme(.dark)
        .onAppear {
            model.engine.startTicking()
        }
        .task(id: controlsVisible) {
            guard controlsVisible, model.engine.isPlaying, !isLocked else { return }
            try? await Task.sleep(for: .seconds(4))
            if !Task.isCancelled, controlsVisible, !isLocked {
                withAnimation(.easeInOut(duration: 0.2)) { controlsVisible = false }
            }
        }
        .overlay {
            if let panel = sidePanel {
                sidePanelOverlay(panel)
            }
        }
        .animation(.easeInOut(duration: 0.24), value: sidePanel)
        .overlay {
            if let icon = centerIcon {
                Image(systemName: icon)
                    .font(.system(size: 58, weight: .bold))
                    .foregroundStyle(.white)
                    .frame(width: 110, height: 110)
                    .background(.black.opacity(0.4), in: Circle())
                    .transition(.scale.combined(with: .opacity))
            }
        }
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
        .animation(.easeInOut(duration: 0.18), value: centerIcon)
        .animation(.easeInOut(duration: 0.22), value: toast)
    }

    // MARK: - 画面层

    @ViewBuilder
    private var mediaLayer: some View {
        ZStack {
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
                        PlayerLayerView(player: player, videoGravity: videoAspect.gravity)
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

            // 亮度遮罩：模拟器上系统亮度不生效，用遮罩给可见反馈。
            #if targetEnvironment(simulator)
            Color.black.opacity(Double(1 - brightness) * 0.85)
                .allowsHitTesting(false)
            #endif
        }
    }

    private func placeholder(_ text: String) -> some View {
        ZStack {
            Theme.gradient
            Text(text).foregroundStyle(.white)
        }
    }

    // MARK: - 控制条

    private func topBar(isLandscape: Bool) -> some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                Button {
                    onExit()
                } label: {
                    Image(systemName: isLandscape ? "chevron.left" : "chevron.down")
                        .font(.title3.weight(.semibold))
                }
                Text(model.title)
                    .font(.subheadline.bold())
                    .lineLimit(1)
                if model.episodes.count > 1 {
                    Text("第 \(model.currentEpisode) 集")
                        .font(.caption)
                        .foregroundStyle(.white.opacity(0.85))
                        .padding(.horizontal, 8)
                        .padding(.vertical, 3)
                        .background(.white.opacity(0.16), in: Capsule())
                }
                Spacer()
                Button { openPanel(.aspect) } label: {
                    Image(systemName: "ellipsis.circle")
                        .font(.title3)
                }
            }
            .foregroundStyle(.white)
            .padding(.horizontal, 16)
            .padding(.top, ScreenInsets.top + 8)
            .padding(.bottom, 8)
            Spacer()
        }
        .background(
            LinearGradient(colors: [.black.opacity(0.7), .clear], startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea(edges: .top)
        )
    }

    private var bottomBar: some View {
        VStack(spacing: 6) {
            VideoSlider(value: Binding(get: { model.engine.progress }, set: { model.seek(toFraction: $0) }))

            HStack(spacing: 14) {
                Button { model.togglePlay() } label: {
                    Image(systemName: model.engine.isPlaying ? "pause.fill" : "play.fill")
                        .font(.title2)
                        .frame(width: 34, height: 34)
                }
                Button {
                    model.engine.isMuted.toggle()
                } label: {
                    Image(systemName: model.engine.isMuted ? "speaker.slash.fill" : "speaker.wave.2.fill")
                        .frame(width: 34, height: 34)
                }
                Text(model.timeText)
                    .font(.caption.monospacedDigit())
                Spacer()
                Button { toggleDanmaku() } label: {
                    Image(systemName: model.showDanmaku ? "captions.bubble.fill" : "captions.bubble")
                        .frame(width: 34, height: 34)
                }
                Button { openPanel(.settings) } label: {
                    Image(systemName: "slider.horizontal.3")
                        .frame(width: 34, height: 34)
                }
                Button { openPanel(.speed) } label: {
                    Text("倍速").font(.caption)
                        .frame(width: 40, height: 34)
                }
                Button { openPanel(.episodes) } label: {
                    Text("选集").font(.caption)
                        .frame(width: 40, height: 34)
                }
                .accessibilityIdentifier("player.episodes.panel")
            }
            .foregroundStyle(.white)
            .frame(height: 34)
            .padding(.horizontal, 16)
            .padding(.bottom, ScreenInsets.bottom + 12)
        }
        .background(
            LinearGradient(colors: [.clear, .black.opacity(0.7)], startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea(edges: .bottom)
        )
    }

    // MARK: - 锁

    private func toggleLock() {
        sidePanel = nil
        withAnimation(.easeInOut(duration: 0.18)) {
            isLocked.toggle()
            controlsVisible = !isLocked
        }
    }

    private var lockButton: some View {
        Button {
            toggleLock()
        } label: {
            Image(systemName: isLocked ? "lock.fill" : "lock.open")
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 34, height: 34)
                .background(.black.opacity(0.4), in: Circle())
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .padding(.leading, 8)
    }

    // MARK: - 右侧抽屉

    private enum PlayerSidePanel: Equatable {
        case episodes, settings, speed, aspect
    }

    private func openPanel(_ panel: PlayerSidePanel) {
        withAnimation(.easeInOut(duration: 0.24)) { sidePanel = panel }
    }

    private func closePanel() {
        withAnimation(.easeInOut(duration: 0.24)) { sidePanel = nil }
    }

    @ViewBuilder
    private func sidePanelOverlay(_ panel: PlayerSidePanel) -> some View {
        ZStack(alignment: .trailing) {
            Color.black.opacity(0.45).ignoresSafeArea()
                .contentShape(Rectangle())
                .onTapGesture { closePanel() }
                .transition(.opacity)

            switch panel {
            case .episodes:
                episodesPanel
            case .settings:
                settingsPanel
            case .speed:
                speedPanel
            case .aspect:
                aspectPanel
            }
        }
    }

    private var episodesPanel: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("选集")
                    .font(.headline)
                    .foregroundStyle(.white)
                Spacer()
                Button { closePanel() } label: {
                    Image(systemName: "xmark").foregroundStyle(.white.opacity(0.85))
                }
            }
            .padding(16)

            ScrollView {
                episodeGrid
                    .padding(.horizontal, 16)
                    .padding(.bottom, 24)
            }
        }
        .frame(width: 300)
        .frame(maxHeight: .infinity, alignment: .top)
        .background(Color(white: 0.11))
        .transition(.move(edge: .trailing))
    }

    private var settingsPanel: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("弹幕设置")
                    .font(.headline)
                    .foregroundStyle(.white)
                Spacer()
                Button { closePanel() } label: {
                    Image(systemName: "xmark").foregroundStyle(.white.opacity(0.85))
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

    private var speedPanel: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("倍速")
                    .font(.headline)
                    .foregroundStyle(.white)
                Spacer()
                Button { closePanel() } label: {
                    Image(systemName: "xmark").foregroundStyle(.white.opacity(0.85))
                }
            }
            .padding(16)

            VStack(spacing: 10) {
                ForEach([0.5, 0.75, 1.0, 1.25, 1.5, 2.0], id: \.self) { value in
                    Button {
                        model.setSpeed(value)
                        closePanel()
                    } label: {
                        HStack {
                            Text("\(value, specifier: "%.2f")x")
                                .font(.subheadline.bold())
                            Spacer()
                            if model.speed == value {
                                Image(systemName: "checkmark")
                                    .foregroundStyle(Theme.pink)
                            }
                        }
                        .foregroundStyle(model.speed == value ? Theme.pink : Color.white)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 12)
                        .background(
                            Color.white.opacity(model.speed == value ? 0.16 : 0.06),
                            in: RoundedRectangle(cornerRadius: 10)
                        )
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 24)
        }
        .frame(width: 220)
        .frame(maxHeight: .infinity, alignment: .top)
        .background(Color(white: 0.11))
        .transition(.move(edge: .trailing))
    }

    private var episodeGrid: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 10), count: 6), spacing: 10) {
            ForEach(model.episodes) { episode in
                let isSelected = episode.number == model.currentEpisode
                Button {
                    model.selectEpisode(episode.number)
                    closePanel()
                } label: {
                    Text(episode.displayName)
                        .font(.subheadline.bold())
                        .foregroundStyle(isSelected ? Color.white : Theme.plum)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                        .background(
                            isSelected ? AnyShapeStyle(Theme.pink) : AnyShapeStyle(Theme.card),
                            in: RoundedRectangle(cornerRadius: 10)
                        )
                        .overlay {
                            if !isSelected {
                                RoundedRectangle(cornerRadius: 10)
                                    .strokeBorder(Theme.hairline, lineWidth: 1)
                            }
                        }
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("player.episode.\(episode.number)")
            }
        }
    }

    // MARK: - 手势

    private enum PlayerDragAxis {
        case seek, brightness, volume
    }

    private enum PlayerHUD: Equatable {
        case idle
        case seek
        case brightness(Double)
        case volume(Double)
    }

    private func playerGesture(in size: CGSize) -> some Gesture {
        DragGesture(minimumDistance: 12)
            .onChanged { value in
                guard !isLocked else { return }
                if dragAxis == nil {
                    let dx = value.translation.width
                    let dy = value.translation.height
                    if abs(dx) > abs(dy) {
                        dragAxis = .seek
                        dragStart = CGFloat(model.engine.progress)
                    } else if value.startLocation.x < size.width / 2 {
                        dragAxis = .brightness
                        dragStart = brightness
                    } else {
                        dragAxis = .volume
                        dragStart = CGFloat(model.engine.volume)
                    }
                }

                switch dragAxis {
                case .seek:
                    let delta = Double(value.translation.width / max(size.width, 1))
                    let next = min(max((Double(dragStart ?? 0)) + delta, 0), 1)
                    model.seek(toFraction: next)
                    hud = .seek
                case .brightness:
                    let delta = -value.translation.height / max(size.height, 1) * 1.5
                    let next = min(max((dragStart ?? 0.5) + delta, 0.05), 1.0)
                    brightness = next
                    #if !targetEnvironment(simulator)
                    UIScreen.main.brightness = next
                    #endif
                    hud = .brightness(Double(next))
                case .volume:
                    let delta = -value.translation.height / max(size.height, 1)
                    let next = min(max((dragStart ?? 1.0) + delta, 0.0), 1.0)
                    model.engine.volume = Float(next)
                    hud = .volume(Double(next))
                case nil:
                    break
                }
            }
            .onEnded { _ in
                guard !isLocked else { return }
                dragAxis = nil
                dragStart = nil
                hud = .idle
            }
    }

    @ViewBuilder
    private var gestureHUD: some View {
        switch hud {
        case .idle:
            EmptyView()

        case .seek:
            VStack(spacing: 10) {
                Text("\(Self.clock(model.engine.currentTime))/\(Self.clock(model.engine.totalDuration))")
                    .font(.system(size: 17, weight: .bold, design: .monospaced))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 9)
                    .background(.black.opacity(0.75), in: RoundedRectangle(cornerRadius: 10))
                ProgressView(value: model.engine.progress)
                    .progressViewStyle(.linear)
                    .tint(Theme.pink)
                    .frame(width: 180)
            }
            .foregroundStyle(.white)

        case .brightness(let value):
            VStack(spacing: 10) {
                Image(systemName: "sun.max.fill").font(.title2).foregroundStyle(.yellow)
                ProgressView(value: value)
                    .progressViewStyle(.linear)
                    .tint(.yellow)
                    .frame(width: 150)
                Text("亮度 \(Int(value * 100))%")
                    .font(.headline.monospacedDigit())
            }
            .padding(.horizontal, 18)
            .padding(.vertical, 14)
            .background(.black.opacity(0.6), in: RoundedRectangle(cornerRadius: 12))
            .foregroundStyle(.white)

        case .volume(let value):
            VStack(spacing: 10) {
                Image(systemName: value == 0 ? "speaker.slash.fill" : "speaker.wave.2.fill")
                    .font(.title2)
                ProgressView(value: value)
                    .progressViewStyle(.linear)
                    .frame(width: 150)
                Text("音量 \(Int(value * 100))%")
                    .font(.headline.monospacedDigit())
            }
            .padding(.horizontal, 18)
            .padding(.vertical, 14)
            .background(.black.opacity(0.6), in: RoundedRectangle(cornerRadius: 12))
            .foregroundStyle(.white)
        }
    }

    // MARK: - 辅助函数

    private func toggleDanmaku() {
        model.showDanmaku.toggle()
        showToast(model.showDanmaku ? "弹幕已开启" : "弹幕已关闭")
    }

    private func flashCenterIcon() {
        centerIcon = nil
        withAnimation(.easeOut(duration: 0.12)) {
            centerIcon = model.engine.isPlaying ? "play.fill" : "pause.fill"
        }
        Task {
            try? await Task.sleep(for: .milliseconds(700))
            if !Task.isCancelled {
                withAnimation(.easeIn(duration: 0.2)) { centerIcon = nil }
            }
        }
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

    private static func clock(_ seconds: Double) -> String {
        let total = max(0, Int(seconds.rounded()))
        if total >= 3600 {
            return String(format: "%d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60)
        }
        return String(format: "%02d:%02d", total / 60, total % 60)
    }

    // MARK: - 画面尺寸

    private enum VideoAspect: String, CaseIterable {
        case fit, fill, stretch, widescreen, standard
        var label: String {
            switch self {
            case .fit: return "适应"
            case .fill: return "填充"
            case .stretch: return "拉伸"
            case .widescreen: return "16:9"
            case .standard: return "4:3"
            }
        }
        var gravity: AVLayerVideoGravity {
            switch self {
            case .fit: return .resizeAspect
            case .fill: return .resizeAspectFill
            case .stretch: return .resize
            case .widescreen, .standard: return .resizeAspect
            }
        }
        var aspectRatio: CGFloat? {
            switch self {
            case .widescreen: return 16.0 / 9.0
            case .standard: return 4.0 / 3.0
            default: return nil
            }
        }
    }

    @State private var videoAspect: VideoAspect = .fit

    private var aspectPanel: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("画面尺寸")
                    .font(.headline)
                    .foregroundStyle(.white)
                Spacer()
                Button { closePanel() } label: {
                    Image(systemName: "xmark").foregroundStyle(.white.opacity(0.85))
                }
            }
            .padding(16)

            VStack(spacing: 10) {
                ForEach(VideoAspect.allCases, id: \.self) { mode in
                    Button {
                        videoAspect = mode
                        closePanel()
                    } label: {
                        HStack {
                            Text(mode.label)
                                .font(.subheadline.bold())
                            Spacer()
                            if videoAspect == mode {
                                Image(systemName: "checkmark").foregroundStyle(Theme.pink)
                            }
                        }
                        .foregroundStyle(videoAspect == mode ? Theme.pink : Color.white)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 12)
                        .background(
                            Color.white.opacity(videoAspect == mode ? 0.16 : 0.06),
                            in: RoundedRectangle(cornerRadius: 10)
                        )
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 24)
        }
        .frame(width: 220)
        .frame(maxHeight: .infinity, alignment: .top)
        .background(Color(white: 0.11))
        .transition(.move(edge: .trailing))
    }
}