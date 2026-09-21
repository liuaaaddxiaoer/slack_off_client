import Foundation
import SwiftUI

/// 跨章页引用。仿真翻页要能在「本章最后一页 → 下一章第一页」之间连续翻，
/// 所以页的身份必须是（章下标, 章内页序）二元组，而不是单纯的页码。
struct NovelPageRef: Equatable, Hashable {
    let chapterIndex: Int
    let pageIndex: Int
}

/// 阅读器状态机：目录、正文、分页、跨章导航、相邻章预取、进度落盘。
@Observable
@MainActor
final class NovelReaderModel {
    let source: String
    let bookId: String

    // MARK: 状态

    private(set) var bookTitle: String
    private(set) var bookAuthor: String?
    private(set) var bookCategory: String?
    private(set) var chapters: [NovelChapterItem] = []
    private(set) var chapterIndex: Int = 0
    private(set) var body: NovelChapterBody?
    private(set) var layout: NovelLayout?
    private(set) var currentPage: Int = 0
    private(set) var isLoadingChapter = false
    private(set) var isLoadingCatalog = false
    private(set) var errorMessage: String?
    private(set) var hasStarted = false
    /// 翻到第一章顶部再往前时的轻提示
    private(set) var edgeHint: String?

    /// 刘海/底部横条高度。页顶页底留白要在它之外再加，否则正文会被状态栏压住。
    var safeAreaTop: CGFloat = 0
    var safeAreaBottom: CGFloat = 0

    /// 由视图用 GeometryReader 写入；变化即重排（横竖屏、字号、行距都走这条路）。
    var pageSize: CGSize = .zero {
        didSet {
            guard pageSize != oldValue, pageSize.width > 0, pageSize.height > 0 else { return }
            relayout()
        }
    }

    // MARK: 依赖

    private let settings: NovelSettings
    private let shelf: BookshelfStore

    /// 正文缓存：chapterId -> body（当前章 + 预取的相邻章）
    private var bodyCache: [String: NovelChapterBody] = [:]
    /// 排版缓存：章下标 -> layout（仿真翻页跨章取页时用）
    private var layoutCache: [Int: NovelLayout] = [:]
    /// 进入阅读器时要恢复的章内进度（0~1），来自书架
    private var pendingResumeProgress: Double?
    private var persistTask: Task<Void, Never>?
    private var lastPersistedSignature: String = ""

    init(
        source: String,
        bookId: String,
        bookTitle: String,
        bookAuthor: String? = nil,
        bookCategory: String? = nil,
        settings: NovelSettings = .shared,
        shelf: BookshelfStore = .shared
    ) {
        self.source = source
        self.bookId = bookId
        self.bookTitle = bookTitle
        self.bookAuthor = bookAuthor
        self.bookCategory = bookCategory
        self.settings = settings
        self.shelf = shelf
    }

    var totalChapters: Int { chapters.count }

    var currentChapter: NovelChapterItem? {
        chapters.indices.contains(chapterIndex) ? chapters[chapterIndex] : nil
    }

    var currentChapterId: String {
        body?.chapterId ?? currentChapter?.chapterId ?? ""
    }

    var pageCount: Int { layout?.pageCount ?? 0 }

    /// 全书进度 0~1
    var overallProgress: Double {
        guard totalChapters > 0 else { return 0 }
        let chapterProgress = layout.map { $0.progress(atPage: currentPage) } ?? 0
        return min(max((Double(chapterIndex) + chapterProgress) / Double(totalChapters), 0), 1)
    }

    var currentRef: NovelPageRef { NovelPageRef(chapterIndex: chapterIndex, pageIndex: currentPage) }

    /// 当前排版参数：基础值来自设置，上下留白叠加安全区。
    func currentTypography() -> NovelTypography {
        var typography = NovelTypography(settings: settings)
        typography.topInset = max(typography.topInset, safeAreaTop + 18)
        typography.bottomInset = max(typography.bottomInset, safeAreaBottom + 26)
        return typography
    }

    // MARK: - 启动

    /// 加载目录 → 定位起始章 → 加载正文 → 分页 → 恢复进度 → 预取相邻章。
    /// - Parameter chapterId: 指定起始章；为 nil 时用书架进度，再退回第一章。
    func start(chapterId: String?) async {
        isLoadingCatalog = true
        do {
            let list: NovelChapterList
            if let cached = NovelChapterCache.shared.catalog(source: source, bookId: bookId) {
                list = cached
            } else {
                list = try await NovelService.chapters(source: source, bookId: bookId)
                NovelChapterCache.shared.storeCatalog(list, source: source, bookId: bookId)
            }
            chapters = list.items
            if bookTitle.isEmpty { bookTitle = list.title ?? "" }
        } catch {
            errorMessage = NovelError.describe(error)
            isLoadingCatalog = false
            return
        }
        isLoadingCatalog = false
        guard !chapters.isEmpty else {
            errorMessage = "这本书的目录是空的"
            return
        }

        // 书架进度：既用于选章，也用于选章内页
        let shelfBook = shelf.book(source: source, bookId: bookId)
        if bookAuthor == nil { bookAuthor = shelfBook?.author }

        var targetIndex = 0
        if let chapterId, let index = chapters.firstIndex(where: { $0.chapterId == chapterId }) {
            targetIndex = index
        } else if let lastId = shelfBook?.lastChapterId,
                  !lastId.isEmpty,
                  let index = chapters.firstIndex(where: { $0.chapterId == lastId }) {
            targetIndex = index
            pendingResumeProgress = shelfBook?.positionInChapter
        }

        await loadChapter(at: targetIndex, restoreProgress: pendingResumeProgress)
        pendingResumeProgress = nil
        hasStarted = true

        // 进阅读器即入书架（夸克行为），随后每 10s 节流落盘一次进度
        persistProgress(force: true)
        startPeriodicPersist()
    }

    func stop() {
        persistTask?.cancel()
        persistTask = nil
        persistProgress(force: true)
    }

    private func startPeriodicPersist() {
        persistTask?.cancel()
        persistTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(10))
                guard !Task.isCancelled else { return }
                self?.persistProgress(force: false)
            }
        }
    }

    // MARK: - 章节加载

    /// 切到指定章。`restoreProgress` 非 nil 时按章内比例定位页码。
    func loadChapter(at index: Int, restoreProgress: Double? = nil) async {
        guard chapters.indices.contains(index) else { return }
        chapterIndex = index
        let item = chapters[index]

        isLoadingChapter = true
        do {
            // `??` 右侧不能直接跟 `try await`，只能显式分支
            let fetched: NovelChapterBody
            if let cached = bodyCache[item.chapterId] {
                fetched = cached
            } else if let diskCached = NovelChapterCache.shared.chapter(
                source: source,
                bookId: bookId,
                chapterId: item.chapterId
            ) {
                fetched = diskCached
                bodyCache[item.chapterId] = diskCached
            } else {
                fetched = try await NovelService.chapter(
                    source: source,
                    bookId: bookId,
                    chapterId: item.chapterId
                )
                bodyCache[item.chapterId] = fetched
                NovelChapterCache.shared.storeChapter(fetched, source: source, bookId: bookId)
            }
            body = fetched
            errorMessage = nil
        } catch {
            // 正文失败只在页内占位重试，不清空已有内容
            errorMessage = NovelError.describe(error)
            isLoadingChapter = false
            return
        }
        isLoadingChapter = false

        buildLayout(restoreProgress: restoreProgress)
        prefetchNeighbors()
        persistProgress(force: false)
    }

    func retryCurrentChapter() async {
        guard chapters.indices.contains(chapterIndex) else { return }
        let item = chapters[chapterIndex]
        bodyCache[item.chapterId] = nil
        await loadChapter(at: chapterIndex)
    }

    /// 重排当前章。字号/行距/纸面尺寸变化后调用，保持读者所在字符位置不变。
    func relayout(keepPosition: Bool = true) {
        layoutCache.removeAll()
        let offset = keepPosition ? layout.map { $0.charOffset(ofPage: currentPage) } : nil
        buildLayout(restoreCharOffset: offset)
    }

    /// 设置面板改了字号/行距后调用。
    func typographyChanged() {
        relayout(keepPosition: true)
    }

    private func buildLayout(
        restoreProgress: Double? = nil,
        restoreCharOffset: Int? = nil
    ) {
        guard let body, pageSize.width > 0, pageSize.height > 0 else { return }
        let typography = currentTypography()
        let newLayout = NovelTextPaginator.paginate(
            title: body.title,
            paragraphs: body.paragraphs,
            typography: typography,
            pageSize: pageSize
        )
        layout = newLayout
        layoutCache[chapterIndex] = newLayout

        if let restoreCharOffset {
            currentPage = newLayout.pageIndex(forCharOffset: restoreCharOffset)
        } else if let restoreProgress, newLayout.pageCount > 1 {
            currentPage = Int((restoreProgress * Double(newLayout.pageCount - 1)).rounded())
        } else {
            currentPage = 0
        }
        currentPage = min(max(currentPage, 0), newLayout.pageCount - 1)
    }

    /// 后台预取上一章 / 下一章正文，失败静默（翻过去时会重新拉）。
    private func prefetchNeighbors() {
        for offset in [1, -1] {
            let index = chapterIndex + offset
            guard chapters.indices.contains(index) else { continue }
            let chapterId = chapters[index].chapterId
            guard bodyCache[chapterId] == nil else { continue }
            Task { [source, bookId] in
                if let cached = NovelChapterCache.shared.chapter(
                    source: source,
                    bookId: bookId,
                    chapterId: chapterId
                ) {
                    self.bodyCache[chapterId] = cached
                    return
                }
                if let fetched = try? await NovelService.chapter(
                    source: source,
                    bookId: bookId,
                    chapterId: chapterId
                ) {
                    self.bodyCache[chapterId] = fetched
                    NovelChapterCache.shared.storeChapter(fetched, source: source, bookId: bookId)
                }
            }
        }
    }

    // MARK: - 翻页

    func goToPage(_ index: Int) {
        guard let layout else { return }
        currentPage = min(max(index, 0), layout.pageCount - 1)
        persistProgress(force: false)
    }

    /// 章内下一页；已是末页则返回 nil（由调用方决定跨章）。
    func nextPageInChapter() -> Int? {
        guard let layout else { return nil }
        let next = currentPage + 1
        return next < layout.pageCount ? next : nil
    }

    func previousPageInChapter() -> Int? {
        let previous = currentPage - 1
        return previous >= 0 ? previous : nil
    }

    /// 跨章前进：优先章内翻页，到末页则进下一章第一页。
    func advance() async {
        if let next = nextPageInChapter() {
            goToPage(next)
        } else if chapters.indices.contains(chapterIndex + 1) {
            await loadChapter(at: chapterIndex + 1)
        } else {
            flashHint("已经是最后一章了")
        }
    }

    /// 跨章后退：优先章内翻页，到第一页则回上一章最后一页。
    func retreat() async {
        if let previous = previousPageInChapter() {
            goToPage(previous)
        } else if chapterIndex > 0 {
            await loadChapter(at: chapterIndex - 1, restoreProgress: 1.0)
            if let layout { goToPage(layout.pageCount - 1) }
        } else {
            flashHint("已经是第一章了")
        }
    }

    func goToChapter(_ index: Int) async {
        guard chapters.indices.contains(index), index != chapterIndex else { return }
        await loadChapter(at: index)
    }

    // MARK: - 跨章页取用（仿真翻页用）

    /// 某页的上一页引用，跨章；到头返回 nil。
    func ref(before ref: NovelPageRef) -> NovelPageRef? {
        if ref.pageIndex > 0 {
            return NovelPageRef(chapterIndex: ref.chapterIndex, pageIndex: ref.pageIndex - 1)
        }
        guard ref.chapterIndex > 0 else { return nil }
        let previousChapter = ref.chapterIndex - 1
        guard let previousLayout = layout(forChapter: previousChapter) else { return nil }
        return NovelPageRef(chapterIndex: previousChapter, pageIndex: previousLayout.pageCount - 1)
    }

    /// 某页的下一页引用，跨章；到尾返回 nil。
    func ref(after ref: NovelPageRef) -> NovelPageRef? {
        if let currentLayout = layout(forChapter: ref.chapterIndex),
           ref.pageIndex + 1 < currentLayout.pageCount {
            return NovelPageRef(chapterIndex: ref.chapterIndex, pageIndex: ref.pageIndex + 1)
        }
        guard chapters.indices.contains(ref.chapterIndex + 1) else { return nil }
        let nextChapter = ref.chapterIndex + 1
        guard let nextLayout = layout(forChapter: nextChapter), nextLayout.pageCount > 0 else { return nil }
        return NovelPageRef(chapterIndex: nextChapter, pageIndex: 0)
    }

    /// 取某章的排版结果：当前章直接返回，其它章需正文已缓存才能算（未缓存返回 nil）。
    func layout(forChapter index: Int) -> NovelLayout? {
        if index == chapterIndex { return layout }
        if let cachedLayout = layoutCache[index] { return cachedLayout }
        guard chapters.indices.contains(index) else { return nil }
        let chapterId = chapters[index].chapterId
        guard let cachedBody = bodyCache[chapterId], pageSize.width > 0 else { return nil }
        let built = NovelTextPaginator.paginate(
            title: cachedBody.title,
            paragraphs: cachedBody.paragraphs,
            typography: currentTypography(),
            pageSize: pageSize
        )
        layoutCache[index] = built
        return built
    }

    /// 仿真翻页翻到别的章时，把「当前章」同步过去（不重新拉网络，正文已在缓存）。
    func adopt(ref: NovelPageRef) {
        guard ref.chapterIndex != chapterIndex else {
            if let layout { currentPage = min(max(ref.pageIndex, 0), layout.pageCount - 1) }
            return
        }
        guard chapters.indices.contains(ref.chapterIndex) else { return }
        let chapterId = chapters[ref.chapterIndex].chapterId
        guard let cachedBody = bodyCache[chapterId] else { return }
        chapterIndex = ref.chapterIndex
        body = cachedBody
        if let cachedLayout = layoutCache[ref.chapterIndex] {
            layout = cachedLayout
        } else {
            buildLayout()
        }
        if let layout { currentPage = min(max(ref.pageIndex, 0), layout.pageCount - 1) }
        prefetchNeighbors()
        persistProgress(force: false)
    }

    // MARK: - 进度落盘

    private func persistProgress(force: Bool) {
        guard hasStarted || force, let body, !chapters.isEmpty else { return }
        let position = layout.map { $0.progress(atPage: currentPage) } ?? 0
        // 签名去重：位置没变就不写盘，避免 10s 定时器无谓刷 UserDefaults
        let signature = "\(chapterIndex)|\(currentPage)|\(Int(position * 1000))"
        guard force || signature != lastPersistedSignature else { return }
        lastPersistedSignature = signature

        shelf.updateProgress(
            source: source,
            bookId: bookId,
            title: bookTitle,
            author: bookAuthor,
            category: bookCategory,
            chapterId: body.chapterId,
            chapterTitle: body.title,
            chapterIndex: chapterIndex + 1,
            totalChapters: totalChapters,
            positionInChapter: position
        )
    }

    /// 退出/切后台时调用，确保进度不丢。
    func flushProgress() {
        persistProgress(force: true)
    }

    private func flashHint(_ text: String) {
        edgeHint = text
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(1.6))
            guard let self else { return }
            if self.edgeHint == text { self.edgeHint = nil }
        }
    }
}
