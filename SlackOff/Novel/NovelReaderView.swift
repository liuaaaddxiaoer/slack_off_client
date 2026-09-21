import SwiftUI
import UIKit

/// 沉浸式阅读器：全屏纸色 + 三种翻页模式 + 点击唤出的上下菜单 + 目录抽屉 + 设置面板。
struct NovelReaderView: View {
    @State private var model: NovelReaderModel
    @State private var settings = NovelSettings.shared
    @State private var shelf = BookshelfStore.shared

    @State private var menuVisible = false
    @State private var hideMenuTask: Task<Void, Never>?
    @State private var showCatalog = false
    @State private var showSettings = false
    @State private var showBrightness = false
    @State private var scrubChapter: Double = 0
    @State private var isScrubbing = false

    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.dismiss) private var dismiss

    private let initialChapterId: String?

    init(
        source: String,
        bookId: String,
        title: String,
        author: String? = nil,
        category: String? = nil,
        chapterId: String? = nil
    ) {
        _model = State(
            initialValue: NovelReaderModel(
                source: source,
                bookId: bookId,
                bookTitle: title,
                bookAuthor: author,
                bookCategory: category
            )
        )
        initialChapterId = chapterId
    }

    private var paper: NovelPaper { settings.paper }

    var body: some View {
        GeometryReader { proxy in
            ZStack {
                paper.background.ignoresSafeArea()

                readerContent

                overlays

                if menuVisible {
                    menus
                }
            }
            .onAppear {
                model.safeAreaTop = ScreenInsets.top
                model.safeAreaBottom = ScreenInsets.bottom
                model.pageSize = proxy.size
                applyBrightness()
            }
            .onChange(of: proxy.size) { _, newSize in
                model.safeAreaTop = ScreenInsets.top
                model.safeAreaBottom = ScreenInsets.bottom
                model.pageSize = newSize
            }
        }
        .ignoresSafeArea()
        .statusBarHidden(!menuVisible)
        .toolbar(.hidden, for: .navigationBar)
        .toolbar(.hidden, for: .tabBar)
        .task {
            await model.start(chapterId: initialChapterId)
            scrubChapter = Double(model.chapterIndex + 1)
        }
        .onDisappear { model.stop() }
        .onChange(of: scenePhase) { _, phase in
            // 切后台立刻落盘，避免被系统杀掉丢进度
            if phase != .active { model.flushProgress() }
        }
        // 字号/行距变化 → 重排并保持读者所在位置
        .onChange(of: settings.fontSize) { _, _ in model.typographyChanged() }
        .onChange(of: settings.lineSpacing) { _, _ in model.typographyChanged() }
        .overlay {
            if showCatalog { catalogDrawer }
        }
        .overlay(alignment: .bottom) {
            if showSettings { settingsPanel }
        }
    }

    // MARK: - 内容区

    @ViewBuilder
    private var readerContent: some View {
        if let errorMessage = model.errorMessage, model.layout == nil {
            errorState(errorMessage)
        } else if model.layout == nil {
            loadingState
        } else {
            switch settings.pagingMode {
            case .curl:
                PageCurlReader(
                    model: model,
                    paper: paper,
                    bookTitle: model.bookTitle,
                    onCenterTap: toggleMenu
                )
            case .cover:
                CoverPagingReader(
                    model: model,
                    paper: paper,
                    bookTitle: model.bookTitle,
                    onCenterTap: toggleMenu
                )
            case .scroll:
                VerticalScrollReader(
                    model: model,
                    paper: paper,
                    bookTitle: model.bookTitle,
                    onCenterTap: toggleMenu
                )
            }
        }
    }

    private var loadingState: some View {
        VStack(spacing: 12) {
            ProgressView()
                .tint(paper.text)
            Text(model.isLoadingCatalog ? "正在加载目录…" : "正在加载章节…")
                .font(.system(size: 13))
                .foregroundStyle(paper.secondaryText)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .contentShape(Rectangle())
        .onTapGesture { toggleMenu() }
    }

    private func errorState(_ message: String) -> some View {
        VStack(spacing: 14) {
            Image(systemName: "exclamationmark.triangle")
                .font(.system(size: 34))
                .foregroundStyle(paper.secondaryText)
            Text("章节加载失败")
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(paper.text)
            Text(message)
                .font(.system(size: 12))
                .foregroundStyle(paper.secondaryText)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
            Button {
                Task { await model.retryCurrentChapter() }
            } label: {
                Text("重试")
                    .font(.system(size: 14, weight: .medium))
                    .padding(.horizontal, 22)
                    .padding(.vertical, 9)
                    .background(Theme.pink, in: Capsule())
                    .foregroundStyle(.white)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .contentShape(Rectangle())
        .onTapGesture { toggleMenu() }
    }

    // MARK: - 浮层（加载指示 / 提示 / 章内错误重试条）

    @ViewBuilder
    private var overlays: some View {
        if model.isLoadingChapter, model.layout != nil {
            VStack {
                Spacer()
                ProgressView("加载章节…")
                    .font(.system(size: 12))
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
                    .background(.black.opacity(0.6), in: Capsule())
                    .foregroundStyle(.white)
                    .padding(.bottom, 90)
            }
            .allowsHitTesting(false)
        }

        if let hint = model.edgeHint {
            VStack {
                Spacer()
                Text(hint)
                    .font(.system(size: 13))
                    .padding(.horizontal, 16)
                    .padding(.vertical, 9)
                    .background(.black.opacity(0.7), in: Capsule())
                    .foregroundStyle(.white)
                    .padding(.bottom, 120)
            }
            .allowsHitTesting(false)
            .transition(.opacity)
        }

        // 正文已渲染但当前章拉取失败：页内占位重试条，不清空已有内容
        if let errorMessage = model.errorMessage, model.layout != nil {
            VStack {
                Spacer()
                HStack(spacing: 10) {
                    Text(errorMessage)
                        .font(.system(size: 11))
                        .lineLimit(2)
                    Button("重试") { Task { await model.retryCurrentChapter() } }
                        .font(.system(size: 12, weight: .semibold))
                }
                .foregroundStyle(.white)
                .padding(.horizontal, 14)
                .padding(.vertical, 9)
                .background(.black.opacity(0.72), in: RoundedRectangle(cornerRadius: 10))
                .padding(.horizontal, 24)
                .padding(.bottom, 90)
            }
            .allowsHitTesting(false)
        }
    }

    // MARK: - 上下菜单

    private var menus: some View {
        VStack(spacing: 0) {
            topBar
            Spacer(minLength: 0)
            bottomBar
        }
        .transition(.opacity)
    }

    private var topBar: some View {
        VStack(spacing: 2) {
            HStack(spacing: 12) {
                Button {
                    dismiss()
                } label: {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(barText)
                        .frame(width: 34, height: 34)
                        .contentShape(Rectangle())
                }

                VStack(alignment: .leading, spacing: 2) {
                    Text(model.bookTitle)
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(barText)
                        .lineLimit(1)
                    Text(model.body?.title ?? model.currentChapter?.title ?? "")
                        .font(.system(size: 11))
                        .foregroundStyle(barText.opacity(0.65))
                        .lineLimit(1)
                }

                Spacer(minLength: 8)

                // 源标识：book_id 跨源不通用，让读者知道这本是从哪个源解析的
                Text(model.source)
                    .font(.system(size: 10, weight: .medium))
                    .foregroundStyle(barText.opacity(0.8))
                    .padding(.horizontal, 7)
                    .padding(.vertical, 3)
                    .background(barText.opacity(0.14), in: Capsule())
            }
            .padding(.horizontal, 12)
            .padding(.top, ScreenInsets.top + 4)
            .padding(.bottom, 10)
        }
        .background(barBackground)
        .overlay(alignment: .bottom) {
            Rectangle().fill(paper.text.opacity(0.14)).frame(height: 0.5)
        }
    }

    private var bottomBar: some View {
        VStack(spacing: 12) {
            if showBrightness {
                brightnessRow
            }
            progressRow
            HStack(spacing: 0) {
                barButton("list.bullet", "目录") {
                    showSettings = false
                    withAnimation(.easeInOut(duration: 0.24)) { showCatalog = true }
                }
                barButton(
                    settings.isNightMode ? "sun.max.fill" : "moon.fill",
                    settings.isNightMode ? "日间" : "夜间"
                ) {
                    settings.isNightMode.toggle()
                }
                barButton("sun.min", "亮度") {
                    withAnimation(.easeInOut(duration: 0.2)) { showBrightness.toggle() }
                }
                barButton("textformat.size", "设置") {
                    showCatalog = false
                    withAnimation(.easeInOut(duration: 0.24)) { showSettings.toggle() }
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 12)
        .padding(.bottom, ScreenInsets.bottom + 12)
        .background(barBackground)
        .overlay(alignment: .top) {
            Rectangle().fill(paper.text.opacity(0.14)).frame(height: 0.5)
        }
    }

    private var brightnessRow: some View {
        HStack(spacing: 10) {
            Image(systemName: "sun.min")
                .font(.system(size: 12))
                .foregroundStyle(barText.opacity(0.7))
            Slider(
                value: Binding(
                    get: { settings.brightness ?? Double(UIScreen.main.brightness) },
                    set: { newValue in
                        settings.brightness = newValue
                        UIScreen.main.brightness = CGFloat(newValue)
                    }
                ),
                in: 0.05...1.0
            )
            .tint(barText.opacity(0.8))
            Image(systemName: "sun.max")
                .font(.system(size: 14))
                .foregroundStyle(barText.opacity(0.7))
        }
    }

    /// 章节进度条：拖动时气泡显示「第 x/y 章 · zz%」，松手跳章。
    private var progressRow: some View {
        VStack(spacing: 4) {
            if isScrubbing {
                Text("第 \(Int(scrubChapter))/\(max(model.totalChapters, 1)) 章 · \(Int(model.overallProgress * 100))%")
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(barText)
                    .padding(.horizontal, 9)
                    .padding(.vertical, 4)
                    .background(barText.opacity(0.14), in: Capsule())
            }
            HStack(spacing: 10) {
                Button {
                    Task { await model.retreat() }
                } label: {
                    Image(systemName: "backward.end.fill")
                        .font(.system(size: 14))
                        .foregroundStyle(barText.opacity(0.85))
                        .frame(width: 30, height: 30)
                        .contentShape(Rectangle())
                }
                .disabled(model.chapterIndex == 0)
                .opacity(model.chapterIndex == 0 ? 0.4 : 1)

                Slider(
                    value: $scrubChapter,
                    in: 1...Double(max(model.totalChapters, 1)),
                    step: 1
                ) { editing in
                    isScrubbing = editing
                    if !editing {
                        let target = Int(scrubChapter) - 1
                        if target != model.chapterIndex {
                            Task { await model.goToChapter(target) }
                        }
                    }
                }
                .tint(barText.opacity(0.8))
                .disabled(model.totalChapters <= 1)

                Button {
                    Task { await model.advance() }
                } label: {
                    Image(systemName: "forward.end.fill")
                        .font(.system(size: 14))
                        .foregroundStyle(barText.opacity(0.85))
                        .frame(width: 30, height: 30)
                        .contentShape(Rectangle())
                }
                .disabled(model.chapterIndex + 1 >= model.totalChapters)
                .opacity(model.chapterIndex + 1 >= model.totalChapters ? 0.4 : 1)
            }
            HStack {
                Text("\(model.chapterIndex + 1)/\(max(model.totalChapters, 1)) 章")
                Spacer()
                Text("\(Int(model.overallProgress * 100))%")
            }
            .font(.system(size: 10))
            .foregroundStyle(barText.opacity(0.6))
        }
    }

    private func barButton(_ icon: String, _ label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 4) {
                Image(systemName: icon)
                    .font(.system(size: 17))
                Text(label)
                    .font(.system(size: 10))
            }
            .foregroundStyle(barText)
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
        }
    }

    /// 菜单配色跟着纸色走：深色纸用浅字，浅色纸用深字。
    private var barText: Color { paper.isDark ? Color.white.opacity(0.92) : Color.black.opacity(0.82) }

    /// 上下工具栏底色：纸色掺 10% 文字色压成的不透明实心面板（见 `NovelPaper.barSurface`）。
    /// 早先用 `paper.text.opacity(0.10)` 半透明叠色，正文会从工具栏底下透出来，
    /// 底部进度条那行尤其糊；直接铺纸色又会与纸面同色、看不出边界。
    private var barBackground: Color { paper.barSurface }

    private func toggleMenu() {
        showSettings = false
        withAnimation(.easeInOut(duration: 0.2)) { menuVisible.toggle() }
        scheduleAutoHide()
    }

    /// 4s 自动隐藏（与播放器控制条一致的手感）。
    private func scheduleAutoHide() {
        hideMenuTask?.cancel()
        guard menuVisible else { return }
        hideMenuTask = Task {
            try? await Task.sleep(for: .seconds(4))
            guard !Task.isCancelled else { return }
            if menuVisible && !showSettings && !showBrightness {
                withAnimation(.easeInOut(duration: 0.25)) { menuVisible = false }
            }
        }
    }

    private func applyBrightness() {
        if let brightness = settings.brightness {
            UIScreen.main.brightness = CGFloat(brightness)
        }
    }

    // MARK: - 目录抽屉

    private var catalogDrawer: some View {
        ReaderCatalogDrawer(model: model) {
            withAnimation(.easeInOut(duration: 0.24)) { showCatalog = false }
        } onSelect: { index in
            withAnimation(.easeInOut(duration: 0.24)) { showCatalog = false }
            menuVisible = false
            Task { await model.goToChapter(index) }
        }
        .transition(.opacity)
    }

    // MARK: - 设置面板

    private var settingsPanel: some View {
        ReaderSettingsPanel(settings: settings)
            .transition(.move(edge: .bottom))
    }
}
