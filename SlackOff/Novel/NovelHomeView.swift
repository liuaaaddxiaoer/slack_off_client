import SwiftUI

/// 小说频道入口：顶部分段「书城 / 书架」，替换原来的占位页。
///
/// 层级刻意做浅——小说本身已经是 App 的一个 tab，再嵌一层底部 tab bar 会出现双 tab，
/// 所以用顶部分段控件，这也更接近夸克小说频道的进入体验。
struct NovelHomeView: View {
    enum Tab: String, CaseIterable, Identifiable {
        case bookstore = "书城"
        case shelf = "书架"

        var id: String { rawValue }
        var systemImage: String {
            switch self {
            case .bookstore: "square.grid.2x2.fill"
            case .shelf: "books.vertical.fill"
            }
        }
    }

    @State private var tab: Tab = .bookstore
    @State private var showSettings = false
    @State private var shelf = BookshelfStore.shared

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                header
                Group {
                    switch tab {
                    case .bookstore:
                        BookstoreView(onOpenShelf: { tab = .shelf })
                    case .shelf:
                        BookshelfView(onOpenBookstore: { tab = .bookstore })
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            .background(Theme.cream)
            .toolbar(.hidden, for: .navigationBar)
            .sheet(isPresented: $showSettings) {
                NavigationStack {
                    NovelSettingsView()
                }
                .presentationDetents([.medium, .large])
            }
        }
        .tint(Theme.pink)
    }

    private var header: some View {
        HStack(spacing: 12) {
            Picker("频道", selection: $tab) {
                ForEach(Tab.allCases) { item in
                    Text(item.rawValue).tag(item)
                }
            }
            .pickerStyle(.segmented)
            .frame(maxWidth: 210)

            Spacer(minLength: 0)

            // 书架角标：让读者一眼看到收藏了几本
            if !shelf.books.isEmpty {
                Text("\(shelf.books.count)")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 7)
                    .padding(.vertical, 3)
                    .background(Theme.hairline, in: Capsule())
            }

            Button {
                showSettings = true
            } label: {
                Image(systemName: "gearshape.fill")
                    .font(.system(size: 16))
                    .foregroundStyle(Theme.plum.opacity(0.75))
                    .frame(width: 34, height: 34)
                    .contentShape(Rectangle())
            }
            .accessibilityLabel("小说设置")
        }
        .padding(.horizontal, 16)
        .padding(.top, ScreenInsets.top + 4)
        .padding(.bottom, 10)
        .background(Theme.softPink.opacity(0.35))
    }
}
