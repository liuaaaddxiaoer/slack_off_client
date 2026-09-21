import SwiftUI
import UIKit

/// CoreText 页画布：把 `NovelLayout` 的某一页正文绘制到 UIView 上。
///
/// 用 `UIView.draw(_:)` 而不是 SwiftUI Text，是因为分页结果本身就来自 CoreText 逐行测量，
/// 渲染复用同一批 `CTLine` 才能保证「测多少行就画多少行」，末页绝不溢出。
struct NovelPageCanvas: UIViewRepresentable {
    let layout: NovelLayout
    let pageIndex: Int
    let textColor: Color

    func makeUIView(context: Context) -> NovelPageUIView {
        let view = NovelPageUIView()
        view.backgroundColor = .clear
        view.isOpaque = false
        return view
    }

    func updateUIView(_ view: NovelPageUIView, context: Context) {
        view.layout = layout
        view.pageIndex = pageIndex
        view.textColor = UIColor(textColor)
        view.setNeedsDisplay()
    }
}

final class NovelPageUIView: UIView {
    var layout: NovelLayout?
    var pageIndex: Int = 0
    var textColor: UIColor = .label

    override func draw(_ rect: CGRect) {
        guard
            let context = UIGraphicsGetCurrentContext(),
            let layout,
            layout.pages.indices.contains(pageIndex)
        else { return }
        layout.draw(page: pageIndex, textColor: textColor, in: context)
    }
}

/// 一页的完整视觉：纸色背景 + 正文画布 + 页眉/页脚。
///
/// 页眉页脚用 SwiftUI 而不是画进 CoreText：切换显示（沉浸/菜单态）时不必重新分页。
struct NovelPageContent: View {
    let layout: NovelLayout
    let pageIndex: Int
    let paper: NovelPaper
    var bookTitle: String?
    var showHeader: Bool = false
    var showFooter: Bool = true

    var body: some View {
        ZStack {
            paper.background

            NovelPageCanvas(
                layout: layout,
                pageIndex: pageIndex,
                textColor: paper.text
            )

            VStack(spacing: 0) {
                if showHeader {
                    Text(bookTitle ?? layout.chapterTitle)
                        .font(.system(size: 11))
                        .foregroundStyle(paper.secondaryText)
                        .lineLimit(1)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, layout.typography.horizontalInset)
                        .padding(.top, 14)
                }
                Spacer(minLength: 0)
                if showFooter {
                    footer
                }
            }
        }
    }

    private var footer: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(layout.chapterTitle)
                .lineLimit(1)
                .truncationMode(.tail)
            Spacer(minLength: 8)
            Text("\(pageIndex + 1)/\(layout.pageCount)")
        }
        .font(.system(size: 11))
        .foregroundStyle(paper.secondaryText)
        .padding(.horizontal, layout.typography.horizontalInset)
        .padding(.bottom, 14)
    }
}
