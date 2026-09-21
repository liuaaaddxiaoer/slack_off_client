import SwiftUI

struct VideoDetailView: View {
    let slug: String
    let title: String

    @State private var detail: VideoDetail?
    @State private var isLoading = false
    @State private var errorMessage: String?
    @State private var selectedEpisode = 1

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if isLoading {
                    ProgressView("加载中…")
                        .frame(maxWidth: .infinity, minHeight: 280)
                } else if let detail {
                    header(detail)
                    info(detail)
                    episodes(detail)
                } else {
                    Text(errorMessage ?? "加载失败")
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, minHeight: 280)
                }
            }
            .padding(16)
        }
        .background(Theme.cream)
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .tabBar)
        .task {
            await loadIfNeeded()
        }
    }

    private func header(_ detail: VideoDetail) -> some View {
        HStack(alignment: .top, spacing: 14) {
            AsyncImage(url: detail.coverURL) { phase in
                if case .success(let image) = phase {
                    image.resizable().scaledToFill()
                } else {
                    LinearGradient(colors: [Theme.softPink, Theme.lavender.opacity(0.7)], startPoint: .top, endPoint: .bottom)
                }
            }
            .frame(width: 116, height: 174)
            .clipShape(RoundedRectangle(cornerRadius: 12))

            VStack(alignment: .leading, spacing: 6) {
                Text(detail.title ?? title)
                    .font(.title3.bold())
                    .foregroundStyle(Theme.plum)
                if let score = detail.score {
                    Text("评分 \(score)")
                        .font(.subheadline.bold())
                        .foregroundStyle(.white)
                        .padding(.horizontal, 9)
                        .padding(.vertical, 4)
                        .background(Theme.peach, in: Capsule())
                }
                infoLine("类型", detail.typeName)
                infoLine("地区", detail.area)
                infoLine("年份", detail.release)
                if let count = detail.episodeCount {
                    Text("全 \(count) 集")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            Spacer(minLength: 0)
        }
    }

    private func infoLine(_ label: String, _ value: String?) -> some View {
        Group {
            if let value, !value.isEmpty {
                Text("\(label) · \(value)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }
        }
    }

    private func info(_ detail: VideoDetail) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            if let description = detail.description, !description.isEmpty {
                Text("简介")
                    .font(.headline)
                    .foregroundStyle(Theme.plum)
                Text(description)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineSpacing(3)
            }
            if let actor = detail.actor, !actor.isEmpty {
                Text("主演: \(actor)")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.card)
        .clipShape(RoundedRectangle(cornerRadius: 14))
    }

    private func episodes(_ detail: VideoDetail) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("选集")
                .font(.headline)
                .foregroundStyle(Theme.plum)
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 10), count: 6), spacing: 10) {
                ForEach(detail.episodes ?? []) { episode in
                    let isSelected = episode.number == selectedEpisode
                    NavigationLink {
                        PlayerView(
                            slug: slug,
                            episode: episode.number,
                            title: detail.title ?? title,
                            episodes: detail.episodes ?? []
                        )
                    } label: {
                        Text(episode.displayName)
                            .font(.subheadline.bold())
                            .foregroundStyle(isSelected ? Color.white : Theme.plum)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 10)
                            .background(
                                isSelected ? AnyShapeStyle(Theme.pink) : AnyShapeStyle(Theme.card),
                                in: RoundedRectangle(cornerRadius: 10)
                            )
                            .overlay {
                                RoundedRectangle(cornerRadius: 10)
                                    .strokeBorder(
                                        isSelected ? Theme.pink : Theme.hairline,
                                        lineWidth: 1.5
                                    )
                            }
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("detail.episode.\(episode.number)")
                    .simultaneousGesture(TapGesture().onEnded {
                        selectedEpisode = episode.number
                        EpisodeStore.remember(episode: episode.number, forSlug: slug)
                    })
                }
            }
        }
    }

    private func loadIfNeeded() async {
        // 从播放页返回时不再重新请求、不刷掉当前详情。
        guard detail == nil else { return }
        await load()
    }

    private func load() async {
        isLoading = true
        do {
            detail = try await VideoService.detail(slug: slug)
            if let first = detail?.episodes?.first {
                selectedEpisode = EpisodeStore.selectedEpisode(forSlug: slug) ?? first.number
            }
        } catch {
            errorMessage = error.localizedDescription
        }
        isLoading = false
    }
}
