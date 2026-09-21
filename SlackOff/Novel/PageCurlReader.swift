import SwiftUI
import UIKit

/// 仿真翻页：苹果自带的 iBooks 式卷曲效果，用 `UIPageViewController(.pageCurl)`。
///
/// 自己撸卷曲动画要重写圆柱映射 + 阴影，成本高且容易掉帧；系统这个 transitionStyle
/// 就是为「书页」设计的， spineLocation = .min 时是竖屏单页卷曲，正好是阅读器形态。
/// 代价是数据源必须**同步**给出前后页，因此跨章取页依赖 model 的正文缓存与排版缓存
/// （进章时已预取相邻章，`layout(forChapter:)` 可同步构建）。
struct PageCurlReader: UIViewControllerRepresentable {
    let model: NovelReaderModel
    let paper: NovelPaper
    let bookTitle: String
    let onCenterTap: () -> Void

    func makeUIViewController(context: Context) -> UIPageViewController {
        let controller = UIPageViewController(
            transitionStyle: .pageCurl,
            navigationOrientation: .horizontal,
            options: [
                // spineLocation = .min → 竖屏单页卷曲（双页需要 .max，那是 iPad 横屏书籍形态）
                .spineLocation: UIPageViewController.SpineLocation.min.rawValue,
            ]
        )
        controller.dataSource = context.coordinator
        controller.delegate = context.coordinator
        controller.view.backgroundColor = UIColor(paper.background)
        return controller
    }

    func updateUIViewController(_ controller: UIPageViewController, context: Context) {
        controller.view.backgroundColor = UIColor(paper.background)
        let coordinator = context.coordinator
        coordinator.paper = paper
        coordinator.bookTitle = bookTitle
        coordinator.onCenterTap = onCenterTap

        // 只有「外部」改变当前页时才强制跳转（点目录、改字号、切章）；
        // 用户手势翻页由 delegate 反向同步进 model，若这里再 setViewControllers 会打断动画。
        let signature = coordinator.signature(for: model)
        guard signature != coordinator.lastSignature else { return }
        coordinator.lastSignature = signature

        guard let layout = model.layout(forChapter: model.chapterIndex) else { return }
        let page = NovelPageViewController(
            ref: model.currentRef,
            layout: layout,
            paper: paper,
            bookTitle: bookTitle,
            onCenterTap: onCenterTap
        )
        controller.setViewControllers([page], direction: .forward, animated: false) { _ in }
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(model: model, paper: paper, bookTitle: bookTitle, onCenterTap: onCenterTap)
    }

    // MARK: - Coordinator

    @MainActor
    final class Coordinator: NSObject, UIPageViewControllerDataSource, UIPageViewControllerDelegate {
        let model: NovelReaderModel
        var paper: NovelPaper
        var bookTitle: String
        var onCenterTap: () -> Void = {}
        /// 上一次同步到 UIPageViewController 的状态签名，用于避免重复 setViewControllers
        var lastSignature: String = ""

        init(
            model: NovelReaderModel,
            paper: NovelPaper,
            bookTitle: String,
            onCenterTap: @escaping () -> Void
        ) {
            self.model = model
            self.paper = paper
            self.bookTitle = bookTitle
            self.onCenterTap = onCenterTap
        }

        /// 签名里带上排版相关项：字号/行距变了必须重建页面，否则卷曲出来的还是旧排版。
        func signature(for model: NovelReaderModel) -> String {
            let settings = NovelSettings.shared
            return [
                "\(model.chapterIndex)",
                "\(model.currentPage)",
                "\(model.layout?.pageCount ?? -1)",
                "\(settings.fontSize)",
                settings.lineSpacing.rawValue,
                settings.paper.rawValue,
            ].joined(separator: "|")
        }

        private func makePage(for ref: NovelPageRef) -> UIViewController? {
            guard let layout = model.layout(forChapter: ref.chapterIndex),
                  layout.pages.indices.contains(ref.pageIndex)
            else { return nil }
            return NovelPageViewController(
                ref: ref,
                layout: layout,
                paper: paper,
                bookTitle: bookTitle,
                onCenterTap: onCenterTap
            )
        }

        func pageViewController(
            _ pageViewController: UIPageViewController,
            viewControllerBefore viewController: UIViewController
        ) -> UIViewController? {
            guard let page = viewController as? NovelPageViewController else { return nil }
            guard let previous = model.ref(before: page.ref) else { return nil }
            return makePage(for: previous)
        }

        func pageViewController(
            _ pageViewController: UIPageViewController,
            viewControllerAfter viewController: UIViewController
        ) -> UIViewController? {
            guard let page = viewController as? NovelPageViewController else { return nil }
            guard let next = model.ref(after: page.ref) else { return nil }
            return makePage(for: next)
        }

        func pageViewController(
            _ pageViewController: UIPageViewController,
            didFinishAnimating finished: Bool,
            previousViewControllers: [UIViewController],
            transitionCompleted completed: Bool
        ) {
            guard completed,
                  let page = pageViewController.viewControllers?.first as? NovelPageViewController
            else { return }
            // 先写签名再改 model：model 变化会触发 updateUIViewController，
            // 签名一致就不会再次 setViewControllers 把动画打断。
            model.adopt(ref: page.ref)
            lastSignature = signature(for: model)
        }
    }
}

/// 卷曲翻页里的单页宿主。把 ref 挂在 VC 上，dataSource 才能知道「这是哪一页」。
final class NovelPageViewController: UIHostingController<NovelPageBody> {
    let ref: NovelPageRef

    init(
        ref: NovelPageRef,
        layout: NovelLayout,
        paper: NovelPaper,
        bookTitle: String,
        onCenterTap: @escaping () -> Void
    ) {
        self.ref = ref
        super.init(
            rootView: NovelPageBody(
                layout: layout,
                pageIndex: ref.pageIndex,
                paper: paper,
                bookTitle: bookTitle,
                onCenterTap: onCenterTap
            )
        )
    }

    @MainActor
    @preconcurrency
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }
}

/// 单页内容 + 中央点击手势（显隐菜单）。
struct NovelPageBody: View {
    let layout: NovelLayout
    let pageIndex: Int
    let paper: NovelPaper
    let bookTitle: String
    let onCenterTap: () -> Void

    var body: some View {
        NovelPageContent(
            layout: layout,
            pageIndex: pageIndex,
            paper: paper,
            bookTitle: bookTitle
        )
        .contentShape(Rectangle())
        .onTapGesture { onCenterTap() }
    }
}
