import SwiftUI

/// 小说频道设置：服务地址、数据源、阅读器默认排版、书架管理。
struct NovelSettingsView: View {
    @State private var settings = NovelSettings.shared
    @State private var shelf = BookshelfStore.shared

    @State private var hostText = NovelSettings.shared.apiHost
    @State private var portText = "\(NovelSettings.shared.apiPort)"

    @State private var sources: [NovelSourceInfo] = []
    @State private var stats: NovelCacheStats?
    @State private var testState: TestState = .idle
    @State private var showClearConfirm = false

    private enum TestState: Equatable {
        case idle
        case testing
        case ok(String)
        case failed(String)
    }

    var body: some View {
        List {
            connectionSection
            sourceSection
            readerSection
            cacheSection
            shelfSection
            aboutSection
        }
        .scrollContentBackground(.hidden)
        .background(Theme.cream)
        .navigationTitle("小说设置")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("完成") { dismiss() }
                    .foregroundStyle(Theme.pink)
            }
        }
        .task {
            hostText = settings.apiHost
            portText = "\(settings.apiPort)"
            await refreshServiceInfo()
        }
    }

    @Environment(\.dismiss) private var dismiss

    // MARK: - 服务连接

    private var connectionSection: some View {
        Section {
            HStack {
                Text("主机")
                    .frame(width: 46, alignment: .leading)
                TextField("127.0.0.1", text: $hostText)
                    .keyboardType(.URL)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .font(.system(.body, design: .monospaced))
            }

            HStack {
                Text("端口")
                    .frame(width: 46, alignment: .leading)
                TextField("4321", text: $portText)
                    .keyboardType(.numberPad)
                    .font(.system(.body, design: .monospaced))
            }

            Button {
                applyAddress()
                Task { await testConnection() }
            } label: {
                HStack {
                    Text("保存并测试连接")
                        .foregroundStyle(Theme.pink)
                    Spacer()
                    switch testState {
                    case .idle:
                        EmptyView()
                    case .testing:
                        ProgressView().scaleEffect(0.8)
                    case .ok(let text):
                        Label(text, systemImage: "checkmark.circle.fill")
                            .font(.system(size: 12))
                            .foregroundStyle(Theme.mint)
                    case .failed(let text):
                        Label(text, systemImage: "xmark.circle.fill")
                            .font(.system(size: 12))
                            .foregroundStyle(.red)
                            .lineLimit(1)
                    }
                }
            }

            if case .failed(let message) = testState, message.count > 12 {
                Text(message)
                    .font(.system(size: 11.5))
                    .foregroundStyle(.secondary)
            }
        } header: {
            Text("小说服务地址")
        } footer: {
            Text("模拟器填 127.0.0.1 即可；真机的 127.0.0.1 是手机自己，必须填 Mac 在同一 Wi-Fi 下的局域网 IP（服务默认监听 0.0.0.0，无需改服务端）。首次回源要实时抓源站，慢则十几秒属正常。")
        }
    }

    // MARK: - 数据源

    private var sourceSection: some View {
        Section {
            sourceRow(id: "auto", name: "自动（推荐）", note: "按健康度与优先级自动故障转移", capabilities: nil)

            ForEach(sources) { source in
                sourceRow(
                    id: source.id,
                    name: source.name,
                    note: source.notes,
                    capabilities: source.capabilities
                )
            }

            if sources.isEmpty {
                Text("拉不到源清单（服务未连通）")
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
            }
        } header: {
            Text("数据源")
        } footer: {
            Text("book_id 与分类 slug 都是源站私有、跨源不通用，所以列表页会用首页命中的那个源；这里的选择只影响书城首屏从哪个源开始。")
        }
    }

    private func sourceRow(id: String, name: String, note: String?, capabilities: [String: Bool]?) -> some View {
        Button {
            settings.sourceChoice = id
        } label: {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: settings.sourceChoice == id ? "largecircle.fill.circle" : "circle")
                    .foregroundStyle(settings.sourceChoice == id ? Theme.pink : Color.secondary.opacity(0.5))
                    .padding(.top, 2)

                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 6) {
                        Text(name)
                            .font(.system(size: 14.5, weight: .medium))
                            .foregroundStyle(Theme.plum)
                        Text(id)
                            .font(.system(size: 10, weight: .medium))
                            .foregroundStyle(.secondary)
                            .padding(.horizontal, 5)
                            .padding(.vertical, 1.5)
                            .background(Theme.hairline, in: Capsule())
                    }
                    if let note, !note.isEmpty {
                        Text(note)
                            .font(.system(size: 11))
                            .foregroundStyle(.secondary)
                            .lineLimit(2)
                    }
                    if let capabilities {
                        HStack(spacing: 5) {
                            capabilityBadge("搜索", supported: capabilities["search"] ?? true)
                            capabilityBadge("排行", supported: capabilities["ranks"] ?? true)
                            capabilityBadge("全本", supported: capabilities["full_books"] ?? true)
                        }
                    }
                }
                Spacer(minLength: 0)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func capabilityBadge(_ text: String, supported: Bool) -> some View {
        Text(supported ? text : "无\(text)")
            .font(.system(size: 9.5, weight: .medium))
            .foregroundStyle(supported ? Theme.mint : Color.secondary)
            .padding(.horizontal, 5)
            .padding(.vertical, 2)
            .background((supported ? Theme.mint.opacity(0.16) : Theme.hairline), in: Capsule())
    }

    // MARK: - 阅读器默认

    private var readerSection: some View {
        Section {
            VStack(alignment: .leading, spacing: 14) {
                fontSizeControl
                segmentedRow(
                    title: "行距",
                    options: NovelLineSpacing.allCases,
                    selected: settings.lineSpacing
                ) { settings.lineSpacing = $0 }
                segmentedRow(
                    title: "翻页",
                    options: NovelPagingMode.allCases,
                    selected: settings.pagingMode
                ) { settings.pagingMode = $0 }

                VStack(alignment: .leading, spacing: 8) {
                    Text("背景")
                        .font(.system(size: 12))
                        .foregroundStyle(.secondary)
                    HStack(spacing: 12) {
                        ForEach(NovelPaper.allCases) { paper in
                            Button {
                                settings.paper = paper
                            } label: {
                                Circle()
                                    .fill(paper.background)
                                    .frame(width: 32, height: 32)
                                    .overlay(
                                        Circle().strokeBorder(
                                            settings.paper == paper ? Theme.pink : Theme.hairline,
                                            lineWidth: settings.paper == paper ? 2.5 : 1
                                        )
                                    )
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(paper.name)
                        }
                        Spacer()
                    }
                }
            }
            .padding(.vertical, 4)
        } header: {
            Text("阅读器默认")
        } footer: {
            Text("阅读时也能在底部菜单的设置面板里临时调整，两处是同一份配置。")
        }
    }

    private var fontSizeControl: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text("字号")
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
                Spacer()
                Text("\(Int(settings.fontSize))")
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(Theme.plum)
            }
            Slider(value: $settings.fontSize, in: NovelSettings.fontSizeRange, step: 1)
                .tint(Theme.pink)
        }
    }

    private func segmentedRow<Option: CaseIterable & Identifiable & Hashable>(
        title: String,
        options: [Option],
        selected: Option,
        onChange: @escaping (Option) -> Void
    ) -> some View where Option.AllCases: RandomAccessCollection {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(.system(size: 12))
                .foregroundStyle(.secondary)
            Picker(title, selection: Binding(get: { selected }, set: { onChange($0) })) {
                ForEach(options) { option in
                    Text(label(for: option)).tag(option)
                }
            }
            .pickerStyle(.segmented)
        }
    }

    /// 枚举的显示名（各枚举都有 name 属性，但协议里没法表达，这里按类型分派）。
    private func label<Option>(for option: Option) -> String {
        switch option {
        case let value as NovelLineSpacing: value.name
        case let value as NovelPagingMode: value.name
        case let value as NovelPaper: value.name
        default: "\(option)"
        }
    }

    // MARK: - 缓存管理

    private var cacheSection: some View {
        Section {
            NavigationLink {
                NovelCacheListView()
            } label: {
                HStack {
                    Label("已缓存书籍", systemImage: "arrow.down.circle")
                    Spacer()
                    Text("\(NovelCacheDownloadManager.shared.records.count) 本")
                        .foregroundStyle(.secondary)
                }
            }
        } header: {
            Text("缓存管理")
        }
    }

    // MARK: - 书架

    private var shelfSection: some View {
        Section {
            HStack {
                Text("书架藏书")
                Spacer()
                Text("\(shelf.books.count) 本")
                    .foregroundStyle(.secondary)
            }
            Button(role: .destructive) {
                showClearConfirm = true
            } label: {
                Text("清空书架与阅读进度")
            }
            .disabled(shelf.books.isEmpty)
            .confirmationDialog(
                "清空书架？",
                isPresented: $showClearConfirm,
                titleVisibility: .visible
            ) {
                Button("清空", role: .destructive) { shelf.removeAll() }
                Button("取消", role: .cancel) {}
            } message: {
                Text("会同时删除所有书的阅读进度，无法恢复。")
            }
        } header: {
            Text("书架")
        }
    }

    // MARK: - 关于

    private var aboutSection: some View {
        Section {
            if let stats {
                LabeledContent("缓存条目") {
                    Text("\(stats.cache?.entries ?? 0)")
                }
                LabeledContent("命中 / 未命中") {
                    Text("\(stats.cache?.hits ?? 0) / \(stats.cache?.misses ?? 0)")
                }
                if let proxyMode = stats.fetcher?.proxyMode {
                    LabeledContent("代理模式") {
                        Text(proxyMode)
                            .font(.system(size: 11.5))
                            .lineLimit(1)
                    }
                }
            }
            LabeledContent("接口文档") {
                Text("\(settings.baseURL.absoluteString)/docs")
                    .font(.system(size: 11.5, design: .monospaced))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }
        } header: {
            Text("服务状态")
        } footer: {
            Text("「未命中」一直涨而缓存条目为 0，通常说明服务进程没配代理（XS_PROXY），回源全部失败。")
        }
    }

    // MARK: - 行为

    private func applyAddress() {
        settings.apiHost = hostText.trimmingCharacters(in: .whitespacesAndNewlines)
        settings.apiPort = Int(portText) ?? 4321
    }

    private func testConnection() async {
        applyAddress()
        testState = .testing
        let started = Date.now
        do {
            let ping = try await NovelService.ping()
            let elapsed = Int(Date.now.timeIntervalSince(started) * 1000)
            testState = .ok(ping.ok == true ? "\(elapsed)ms" : "异常")
            await refreshServiceInfo()
        } catch {
            testState = .failed(NovelError.describe(error))
        }
    }

    private func refreshServiceInfo() async {
        sources = (try? await NovelService.sources()) ?? []
        stats = try? await NovelService.cacheStats()
    }
}
