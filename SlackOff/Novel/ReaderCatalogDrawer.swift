import SwiftUI

/// 目录抽屉：从右侧滑出的面板，配色跟随当前纸张（日间/夜间），不再固定暗黑。
///
/// 目录一次拉全（实测《牧神记》1920 章 0.07s），所以这里是纯本地列表，
/// 用 ScrollViewReader 自动定位到当前章，长目录也不需要分页请求。
struct ReaderCatalogDrawer: View {
    let model: NovelReaderModel
    let onClose: () -> Void
    let onSelect: (Int) -> Void

    @State private var reversed = false
    @State private var settings = NovelSettings.shared

    private var paper: NovelPaper { settings.paper }
    private var primaryText: Color { paper.text }
    private var secondaryText: Color { paper.secondaryText }

    var body: some View {
        // ScrollViewReader 提到最外层：header 里的「当前章」按钮和列表 onAppear
        // 都要用同一个 proxy 滚动定位。
        ScrollViewReader { proxy in
            ZStack(alignment: .trailing) {
                Color.black.opacity(0.4)
                    .ignoresSafeArea()
                    .onTapGesture { onClose() }

                VStack(alignment: .leading, spacing: 0) {
                    header(proxy: proxy)
                    Divider().overlay(primaryText.opacity(0.14))
                    chapterList(proxy: proxy)
                }
                .frame(width: 320)
                .frame(maxHeight: .infinity, alignment: .top)
                .background(paper.background)
                .transition(.move(edge: .trailing))
            }
        }
        .transition(.opacity)
    }

    // MARK: - 头部

    private func header(proxy: ScrollViewProxy) -> some View {
        VStack(spacing: 10) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("目录")
                        .font(.headline)
                        .foregroundStyle(primaryText)
                    Text("共 \(model.totalChapters) 章 · \(model.bookTitle)")
                        .font(.system(size: 11))
                        .foregroundStyle(secondaryText)
                        .lineLimit(1)
                }
                Spacer()
                Button(action: onClose) {
                    Image(systemName: "xmark")
                        .font(.system(size: 14, weight: .medium))
                        .foregroundStyle(secondaryText)
                        .frame(width: 30, height: 30)
                        .contentShape(Rectangle())
                }
            }

            HStack(spacing: 8) {
                chip(icon: "arrow.up.arrow.down", text: reversed ? "倒序" : "正序") {
                    withAnimation(.easeInOut(duration: 0.2)) { reversed.toggle() }
                }
                chip(icon: "location.fill", text: "当前章") {
                    withAnimation(.easeInOut(duration: 0.25)) {
                        proxy.scrollTo(model.chapterIndex, anchor: .center)
                    }
                }
                Spacer()
                Text("\(model.chapterIndex + 1)/\(max(model.totalChapters, 1))")
                    .font(.system(size: 11))
                    .foregroundStyle(secondaryText)
            }
        }
        .padding(16)
    }

    private func chip(icon: String, text: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 4) {
                Image(systemName: icon)
                    .font(.system(size: 11))
                Text(text)
                    .font(.system(size: 11))
            }
            .foregroundStyle(primaryText.opacity(0.85))
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(primaryText.opacity(0.10), in: Capsule())
        }
        .buttonStyle(.plain)
    }

    // MARK: - 列表

    private func chapterList(proxy: ScrollViewProxy) -> some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                ForEach(displayOrder, id: \.offset) { entry in
                    Button {
                        onSelect(entry.offset)
                    } label: {
                        row(entry: entry)
                    }
                    .buttonStyle(.plain)
                    .id(entry.offset)
                }
            }
        }
        .onAppear {
            // 打开抽屉就停在当前章，1920 章不用手动找
            proxy.scrollTo(model.chapterIndex, anchor: .center)
        }
        .onChange(of: reversed) { _, _ in
            proxy.scrollTo(model.chapterIndex, anchor: .center)
        }
    }

    private func row(entry: (offset: Int, item: NovelChapterItem)) -> some View {
        let isCurrent = entry.offset == model.chapterIndex
        return HStack(alignment: .center, spacing: 10) {
            Text("\(entry.offset + 1)")
                .font(.system(size: 11, weight: .medium))
                .foregroundStyle(secondaryText.opacity(0.6))
                .frame(width: 36, alignment: .trailing)
            Text(entry.item.title)
                .font(.system(size: 13))
                .foregroundStyle(isCurrent ? Theme.pink : primaryText.opacity(0.85))
                .lineLimit(1)
            Spacer(minLength: 0)
            if isCurrent {
                Image(systemName: "book.fill")
                    .font(.system(size: 10))
                    .foregroundStyle(Theme.pink)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 11)
        .background(isCurrent ? Theme.pink.opacity(0.12) : Color.clear)
        .contentShape(Rectangle())
    }

    /// 目录项 = (原始下标, 章节)。倒序只影响显示顺序，选中回传的仍是原始下标。
    private var displayOrder: [(offset: Int, item: NovelChapterItem)] {
        let entries = model.chapters.enumerated().map { (offset: $0.offset, item: $0.element) }
        return reversed ? Array(entries.reversed()) : entries
    }
}
