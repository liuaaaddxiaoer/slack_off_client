import SwiftUI

struct NovelCacheListView: View {
    @State private var manager = NovelCacheDownloadManager.shared

    var body: some View {
        Group {
            if manager.records.isEmpty {
                NovelStateView(kind: .empty, message: "还没有缓存书籍")
            } else {
                List(manager.records) { record in
                    let state = manager.state(source: record.source, bookId: record.bookId)
                    NavigationLink {
                        NovelBookDetailView(
                            source: record.source,
                            bookId: record.bookId,
                            title: record.title,
                            author: record.author,
                            category: record.category
                        )
                    } label: {
                        HStack(spacing: 12) {
                            NovelTextCover(
                                seed: record.id,
                                title: record.title,
                                author: record.author,
                                titleScale: 0.14,
                                cornerRadius: 8
                            )
                            .frame(width: 62)

                            VStack(alignment: .leading, spacing: 6) {
                                Text(record.title)
                                    .font(.system(size: 16, weight: .semibold))
                                    .foregroundStyle(Theme.plum)
                                    .lineLimit(1)
                                if let author = record.author {
                                    Text(author).font(.system(size: 12)).foregroundStyle(.secondary)
                                }
                                ProgressView(value: Double(state.cachedCount), total: Double(max(state.totalCount, 1)))
                                    .tint(Theme.pink)
                                Text("\(state.cachedCount) / \(state.totalCount) 章 · \(status(state))")
                                    .font(.system(size: 11.5))
                                    .foregroundStyle(.secondary)
                            }

                            Button(state.isCaching ? "暂停" : "继续") {
                                if state.isCaching {
                                    manager.pause(source: record.source, bookId: record.bookId)
                                } else {
                                    manager.resume(record)
                                }
                            }
                            .buttonStyle(.borderedProminent)
                            .tint(state.isCaching ? .secondary : Theme.pink)
                            .disabled(state.isComplete)
                        }
                        .padding(.vertical, 5)
                    }
                }
                .scrollContentBackground(.hidden)
            }
        }
        .background(Theme.cream)
        .navigationTitle("缓存管理")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func status(_ state: NovelCacheDownloadState) -> String {
        if state.isComplete { return "已完成" }
        if state.isCaching { return "正在缓存" }
        if state.isPaused { return "已暂停" }
        return "等待继续"
    }
}
