import Foundation
import SwiftUI

/// 书城状态：首页聚合 + 分类字典 + 排行榜（懒加载）。
///
/// 首屏只打两个请求（`/api/home` + `/api/categories`），因为服务端上游并发只有 8，
/// 一次并发五六个请求会把回源打满、整体变慢；排行榜等滚动进可视区再拉。
@Observable
@MainActor
final class BookstoreModel {
    private(set) var home: NovelHomePage?
    private(set) var categories: [NovelCategory] = []
    private(set) var ranks: [NovelRankBoard] = []
    private(set) var isLoading = false
    private(set) var isRanksLoading = false
    private(set) var errorMessage: String?
    /// `/api/home` 实际命中的源，详情类请求与分类/排行都要用它
    private(set) var activeSource: String

    private let settings: NovelSettings

    init(settings: NovelSettings = .shared) {
        self.settings = settings
        activeSource = settings.lastActiveSource
    }

    var hot: [NovelBook] { home?.hot ?? [] }
    var recommend: [NovelBook] { home?.recommend ?? [] }
    var latest: [NovelBook] { home?.latest ?? [] }
    var blocks: [NovelHomeCategoryBlock] { home?.blocks ?? [] }
    var siteName: String? { home?.siteName }

    /// 请求分类/排行时该用的源：用户指定了就用指定值，`auto` 时用上次命中的活跃源。
    var resolvedSource: String {
        settings.sourceChoice == "auto" ? activeSource : settings.sourceChoice
    }

    func load(force: Bool = false) async {
        guard force || home == nil else { return }
        isLoading = true
        errorMessage = nil

        let listSource = settings.sourceChoice
        let categoriesSource = resolvedSource

        do {
            async let homeRequest = NovelService.home(source: listSource)
            async let categoriesRequest = NovelService.categories(source: categoriesSource)
            let (homeResult, categoriesResult) = try await (homeRequest, categoriesRequest)

            home = homeResult
            activeSource = homeResult.source
            settings.lastActiveSource = homeResult.source
            categories = categoriesResult

            // slug 跨源不通用：若 home 命中的源和刚才请求分类用的源不一致，必须重拉分类，
            // 否则点进分类会拿另一个源的 slug 去查，直接 404。
            if homeResult.source != categoriesSource {
                if let refreshed = try? await NovelService.categories(source: homeResult.source) {
                    categories = refreshed
                }
            }
            // 源变了，排行榜也要跟着失效
            if homeResult.source != categoriesSource { ranks = [] }
        } catch {
            errorMessage = NovelError.describe(error)
        }

        isLoading = false
    }

    /// 排行榜懒加载：只拉一次，失败静默（区块显示重试按钮）。
    func loadRanksIfNeeded() async {
        guard ranks.isEmpty, !isRanksLoading, home != nil else { return }
        isRanksLoading = true
        do {
            ranks = try await NovelService.ranks(source: resolvedSource)
        } catch {
            ranks = []
        }
        isRanksLoading = false
    }

    func reloadRanks() async {
        ranks = []
        await loadRanksIfNeeded()
    }
}
