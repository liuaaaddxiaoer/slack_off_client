import SwiftUI

struct VideoHomeView: View {
    @State private var items: [VideoCard] = []
    @State private var isLoading = false
    @State private var errorMessage: String?
    @State private var searchText = ""
    @State private var searchResults: [VideoCard]?
    /// 搜索框是否已被滚动带走（滚出可视区域）
    @State private var searchScrolledAway = false
    /// 程序化展开/聚焦搜索框（点击导航栏搜索按钮时使用）
    @State private var isSearchPresented = false

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVGrid(columns: [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)], spacing: 12) {
                    ForEach(searchResults ?? items) { card in
                        VideoCardCell(card: card)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
            }
            .onScrollGeometryChange(for: Double.self) { geometry in
                // 相对初始顶部的滚动距离（刚进页面时为 0）
                geometry.contentOffset.y + geometry.contentInsets.top
            } action: { _, offsetFromTop in
                // 带滞回区间，避免在临界点来回抖动：
                // 滚过 50pt 认为搜索框已看不见；回到 16pt 以内认为搜索框重新可见
                if offsetFromTop > 50 {
                    if !searchScrolledAway { searchScrolledAway = true }
                } else if offsetFromTop < 16 {
                    if searchScrolledAway { searchScrolledAway = false }
                }
            }
            .background(Theme.cream)
            .navigationTitle("追剧小铺")
            .toolbarBackground(Theme.softPink.opacity(0.4), for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    if searchScrolledAway && !isSearchPresented {
                        Button {
                            isSearchPresented = true
                        } label: {
                            Image(systemName: "magnifyingglass")
                        }
                        .accessibilityLabel("搜索")
                    }
                }
            }
            .searchable(text: $searchText, isPresented: $isSearchPresented, prompt: "搜索视频")
            .onSubmit(of: .search) {
                Task { await runSearch() }
            }
            .onChange(of: searchText) {
                if searchText.isEmpty {
                    searchResults = nil
                }
            }
            .overlay {
                if isLoading {
                    ProgressView("加载中…")
                        .padding(20)
                        .background(Theme.card, in: RoundedRectangle(cornerRadius: 14))
                        .allowsHitTesting(false)
                }
            }
            .task {
                await loadHomeIfNeeded()
            }
        }
    }

    private func loadHomeIfNeeded() async {
        // 只在首次进入时加载；从详情页返回时不再重刷、不再闪 loading。
        guard items.isEmpty else { return }
        await loadHome()
    }

    private func loadHome() async {
        isLoading = true
        do {
            items = try await VideoService.home()
        } catch {
            errorMessage = error.localizedDescription
        }
        isLoading = false
    }

    private func runSearch() async {
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else {
            searchResults = nil
            return
        }
        isLoading = true
        do {
            searchResults = try await VideoService.search(query)
        } catch {
            searchResults = []
        }
        isLoading = false
    }
}

struct VideoCardCell: View {
    let card: VideoCard

    var body: some View {
        NavigationLink {
            VideoDetailView(slug: card.slug ?? "", title: card.title ?? "")
        } label: {
            VStack(alignment: .leading, spacing: 8) {
                ZStack(alignment: .topTrailing) {
                    cover
                    if let rating = card.rating, !rating.isEmpty {
                        Text(rating)
                            .font(.caption.bold())
                            .foregroundStyle(.white)
                            .padding(.horizontal, 7)
                            .padding(.vertical, 3)
                            .background(Theme.peach, in: Capsule())
                            .padding(6)
                    }
                }
                VStack(alignment: .leading, spacing: 3) {
                    Text(card.title ?? "未知")
                        .font(.subheadline.bold())
                        .foregroundStyle(Theme.plum)
                        .lineLimit(2, reservesSpace: true)
                    if let remark = card.remark, !remark.isEmpty {
                        Text(remark)
                            .font(.caption)
                            .foregroundStyle(Theme.pink)
                            .lineLimit(1)
                    }
                }
            }
            .padding(8)
            .background(Theme.card)
            .clipShape(RoundedRectangle(cornerRadius: 14))
            .shadow(color: Theme.lavender.opacity(0.22), radius: 6, x: 0, y: 3)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("home.card")
    }

    private var cover: some View {
        Color.clear
            .overlay {
                AsyncImage(url: card.coverURL) { phase in
                    switch phase {
                    case .success(let image):
                        image.resizable().scaledToFill()
                    case .failure:
                        placeholder
                    default:
                        placeholder
                    }
                }
            }
            .aspectRatio(2.0 / 3.0, contentMode: .fit)
            .clipShape(RoundedRectangle(cornerRadius: 9))
    }

    private var placeholder: some View {
        ZStack {
            LinearGradient(colors: [Theme.softPink, Theme.lavender.opacity(0.7)], startPoint: .top, endPoint: .bottom)
            Image(systemName: "film")
                .font(.title2)
                .foregroundStyle(.white)
        }
    }
}
