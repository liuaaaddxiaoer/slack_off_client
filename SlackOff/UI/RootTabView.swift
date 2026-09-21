import SwiftUI

struct RootTabView: View {
    var body: some View {
        TabView {
            VideoHomeView()
                .tabItem {
                    Label("视频", systemImage: "play.rectangle.fill")
                }

            NovelHomeView()
                .tabItem {
                    Label("小说", systemImage: "book.fill")
                }
        }
        .tint(Theme.pink)
    }
}

