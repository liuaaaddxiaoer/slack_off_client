import SwiftUI
import Testing
import UIKit
@testable import SlackOff

/// 小说频道的渲染检查。
///
/// 为什么不用「UIHostingController + drawHierarchy」那套（RenderCheck.swift 的做法）：
/// 实测在单元测试宿主里那样截出来的是**纯空白图**（7 张图字节数完全一样），
/// 断言「文件存在」根本发现不了。所以这里换成两条能真正出像素的路径：
/// - 阅读器页：直接调用 `NovelLayout.draw(page:textColor:in:)`，
///   也就是 `NovelPageUIView.draw(_:)` 内部走的同一条 CoreText 路径；
/// - 纯 SwiftUI 部分（文字封面 / 书架）：用 `ImageRenderer` 离屏渲染。
///
/// 每个用例都断言「与纸色不同的像素占比」，空白图会直接失败。
@MainActor
struct NovelRenderCheck {
    private let phoneSize = CGSize(width: 390, height: 844)

    /// 截图落地目录。`build/` 已在 .gitignore 里，不会污染仓库；
    /// 模拟器进程可以直接写宿主文件系统，省掉从容器里往外捞的步骤。
    private let outputDir: URL = {
        let url = URL(fileURLWithPath: "/Users/mac/Documents/ChatGPT/slack_off/build/novel-shots")
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }()

    // 真实正文样本（《牧神记》第一章前 22 段，1021 字），够排 2 页以上。
    private static let sampleTitle = "第一章 天黑别出门"
    private static let sampleParagraphs: [String] = [
            "天黑，别出门。",
            "这句话在残老村流传了很多年，具体是从什么时候传下来的，已经无从考证。不过这句话却是真理，无需怀疑。",
            "残老村的司婆婆看到夕阳一点点藏在山后，心里又紧张起来。随着夕阳落下，最后一缕阳光消失，天地间突然一下子寂静无比，没有任何声音。只见黑暗从西方缓缓的淹没过来，沿途吞噬山川河流道路树木，然后来到残老村，将残老村淹没。",
            "残老村的四个角竖着四个古老石像，石像斑驳，年代久远，即便是司婆婆也不知道这石像是何人雕琢，何时竖在这里。",
            "黑暗降临，四个石像在黑暗中散发出幽幽的光芒，石像依旧亮着，让司婆婆和村里的老者都松了口气。",
            "村外的黑暗越发浓郁，但有了石像的光，残老村便还算是安全的。",
            "突然，司婆婆耳朵动了动，呆了呆，失声道：“你们听，外面有个孩子的哭声！”",
            "旁边的马老摇头道：“不可能，你听错了……咦，真有婴儿的哭声！”",
            "村外的黑暗中传来婴儿的哭声，村里其他老人除了耳聋的都听到了这个哭声，老人们面面相觑，残老村偏僻荒凉，怎么会有婴儿出现在附近？",
            "“我去看看！”",
            "司婆婆激动起来，踮着小脚跑到村子的一个石像边，马老连忙过去：“司老太婆，你疯了？天黑了，出了村就是死！”",
            "“背着这个石像出村，黑暗里的东西怕石像，我一会半会死不了！”",
            "司婆婆弯腰，想要将石像背起，不过她是个驼背，背不起来。马老摇了摇头：“还是我来吧。我背着石像陪你去！”",
            "一旁又有一个老者一瘸一拐的走过来，道：“马爷，你只有一条胳膊，背石像撑不了多久，我两手齐全，还是我来背。”",
            "马老瞪他一眼：“死瘸子，你断了条腿，能走吗？我虽然只有一条胳膊，但这条胳膊力气大得很！”",
            "他独臂将石像抱起，稳了稳步子，石像难以想象的沉重：“司老太婆，咱们走！”",
            "“别叫我死老太婆！瘸子，哑巴，你们大家都要当心些，村里少了一个石像，千万不要被黑暗里的东西摸进来！”",
            "……",
            "马老和司婆婆走出残老村，黑暗中不知有什么古怪的东西围绕两人游走，但被石像的光芒一照，便吱吱怪叫退回黑暗之中。",
            "两人循着那哭声前进，走出百十步，来到一条大江边，那婴儿的哭声就是从江边传来。石像散发出幽幽的光芒，照不太远，两人细细捕捉声音方位，沿着这条江向上游走去，走出几十步，哭声就在附近，马老独臂已经很难支撑。司婆婆眼睛一亮，看到一丁点荧光，那是一个篮子停在江岸边，荧光从篮子里传来，哭声也是从篮子里传来。",
            "“真有一个孩子！”",
            "司婆婆上前，提起篮子，却微微一怔，没能提起来，那篮子下面是一条被江水泡得发白的手臂，正是这条手臂将篮子和篮子里的孩子托起，一直托到岸边。",
    ]

    // MARK: - 渲染工具

    private func makeLayout(
        fontSize: Double = 18,
        lineSpacing: NovelLineSpacing = .standard,
        size: CGSize? = nil
    ) -> NovelLayout {
        var typography = NovelTypography()
        typography.fontSize = fontSize
        typography.lineSpacing = lineSpacing
        typography.topInset = 54
        typography.bottomInset = 54
        return NovelTextPaginator.paginate(
            title: Self.sampleTitle,
            paragraphs: Self.sampleParagraphs,
            typography: typography,
            pageSize: size ?? phoneSize
        )
    }

    /// 走真实 CoreText 绘制路径出一张阅读器页截图。
    @discardableResult
    private func renderReaderPage(
        layout: NovelLayout,
        paper: NovelPaper,
        pageIndex: Int,
        name: String
    ) throws -> (url: URL, inkRatio: CGFloat, inkContrast: CGFloat) {
        let size = layout.pageSize
        let renderer = UIGraphicsImageRenderer(size: size)
        let image = renderer.image { context in
            UIColor(paper.background).setFill()
            context.fill(CGRect(origin: .zero, size: size))
            layout.draw(page: pageIndex, textColor: UIColor(paper.text), in: context.cgContext)
        }
        return try write(image, name: name, background: paper.background)
    }

    /// 纯 SwiftUI 视图离屏渲染。
    @discardableResult
    private func renderSwiftUI<V: View>(
        _ view: V,
        name: String,
        background: Color = Theme.cream,
        size: CGSize? = nil
    ) throws -> (url: URL, inkRatio: CGFloat, inkContrast: CGFloat) {
        let target = size ?? phoneSize
        let renderer = ImageRenderer(
            content: view
                .frame(width: target.width, height: target.height)
                .background(background)
        )
        renderer.proposedSize = ProposedViewSize(target)
        renderer.scale = 2
        let image = try #require(renderer.uiImage, "ImageRenderer 没能出图：\(name)")
        return try write(image, name: name, background: background)
    }

    private func write(
        _ image: UIImage,
        name: String,
        background: Color
    ) throws -> (url: URL, inkRatio: CGFloat, inkContrast: CGFloat) {
        let data = try #require(image.pngData(), "PNG 编码失败：\(name)")
        let url = outputDir.appendingPathComponent("novel-\(name).png")
        try data.write(to: url)
        let uiBackground = UIColor(background)
        return (
            url,
            Self.inkRatio(of: image, background: uiBackground),
            Self.inkContrast(of: image, background: uiBackground)
        )
    }

    /// 与背景色明显不同的像素占比。空白图 = 0，正文页通常在 3%~20%。
    ///
    /// 这条断言是整个渲染检查的价值所在：没有它，「截图成功」只能证明文件写出来了。
    nonisolated static func inkRatio(of image: UIImage, background: UIColor) -> CGFloat {
        guard let cgImage = image.cgImage else { return 0 }
        let width = cgImage.width
        let height = cgImage.height
        guard width > 0, height > 0 else { return 0 }

        var pixels = [UInt8](repeating: 0, count: width * height * 4)
        guard let context = CGContext(
            data: &pixels,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: width * 4,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ) else { return 0 }
        context.draw(cgImage, in: CGRect(x: 0, y: 0, width: width, height: height))

        // 四个分量必须是独立变量：对同一数组的多个元素同时取 inout
        // 会触发 Swift 独占访问检查（overlapping accesses to 'bg'）。
        var red: CGFloat = 0
        var green: CGFloat = 0
        var blue: CGFloat = 0
        var alpha: CGFloat = 0
        background.getRed(&red, green: &green, blue: &blue, alpha: &alpha)
        let bgRed = UInt8((red * 255).rounded())
        let bgGreen = UInt8((green * 255).rounded())
        let bgBlue = UInt8((blue * 255).rounded())

        // 全图逐像素太慢，按步长采样
        let step = max(1, (width * height) / 40_000)
        var sampled = 0
        var ink = 0
        var index = 0
        while index < width * height {
            let pixelOffset = index * 4
            let dr = abs(Int(pixels[pixelOffset]) - Int(bgRed))
            let dg = abs(Int(pixels[pixelOffset + 1]) - Int(bgGreen))
            let db = abs(Int(pixels[pixelOffset + 2]) - Int(bgBlue))
            let delta = dr + dg + db
            sampled += 1
            if delta > 24 { ink += 1 }
            index += step
        }
        return sampled == 0 ? 0 : CGFloat(ink) / CGFloat(sampled)
    }

    /// 正文与纸色的**亮度对比度**（WCAG 定义），取画面里最极端的那个墨色像素。
    ///
    /// 为什么必须补这条：`inkRatio` 只看「与背景的 RGB 距离 > 24」，黑字画在近黑纸上
    /// （delta=64）照样算「有墨」，于是正文纯黑、肉眼全糊的 bug 能一路通过测试。
    /// 这条按亮度比值判定，才能真正表达「看得见」。
    nonisolated static func inkContrast(of image: UIImage, background: UIColor) -> CGFloat {
        guard let cgImage = image.cgImage else { return 0 }
        let width = cgImage.width
        let height = cgImage.height
        guard width > 0, height > 0 else { return 0 }

        var pixels = [UInt8](repeating: 0, count: width * height * 4)
        guard let context = CGContext(
            data: &pixels,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: width * 4,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ) else { return 0 }
        context.draw(cgImage, in: CGRect(x: 0, y: 0, width: width, height: height))

        func relativeLuminance(_ r: UInt8, _ g: UInt8, _ b: UInt8) -> Double {
            func channel(_ value: UInt8) -> Double {
                let c = Double(value) / 255
                return c <= 0.03928 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
        }

        var red: CGFloat = 0
        var green: CGFloat = 0
        var blue: CGFloat = 0
        var alpha: CGFloat = 0
        background.getRed(&red, green: &green, blue: &blue, alpha: &alpha)
        let bgLuminance = relativeLuminance(
            UInt8((red * 255).rounded()),
            UInt8((green * 255).rounded()),
            UInt8((blue * 255).rounded())
        )

        // 找与背景亮度差最大的像素 —— 那就是正文墨色
        let step = max(1, (width * height) / 40_000)
        var extreme = bgLuminance
        var index = 0
        while index < width * height {
            let offset = index * 4
            let luminance = relativeLuminance(pixels[offset], pixels[offset + 1], pixels[offset + 2])
            if abs(luminance - bgLuminance) > abs(extreme - bgLuminance) { extreme = luminance }
            index += step
        }

        let lighter = max(bgLuminance, extreme)
        let darker = min(bgLuminance, extreme)
        return CGFloat((lighter + 0.05) / (darker + 0.05))
    }

    // MARK: - 用例

    @Test(.timeLimit(.minutes(4)))
    func rendersReaderPageInEveryPaper() throws {
        let layout = makeLayout()
        #expect(layout.pageCount >= 2, "1021 字的样本在 390×844 上应该排出至少 2 页")

        for paper in NovelPaper.allCases {
            let result = try renderReaderPage(layout: layout, paper: paper, pageIndex: 0, name: "reader-\(paper.rawValue)")
            #expect(
                result.inkRatio > 0.01,
                "\(paper.name) 纸色下正文几乎是空白（ink=\(result.inkRatio)）"
            )
            // 正文必须与纸色有足够亮度差。此前 CoreText 漏设前景色 → 一律画黑，
            // 夜黑纸下对比度仅 1.13（纯黑压近黑，肉眼全糊），而 inkRatio 照样通过。
            #expect(
                result.inkContrast >= 4.5,
                "\(paper.name) 纸色下正文对比度不足（\(result.inkContrast) < 4.5），正文可能没上色"
            )
        }
    }

    @Test(.timeLimit(.minutes(3)))
    func rendersSecondPageWithoutRepeatingFirst() throws {
        let layout = makeLayout()
        try #require(layout.pageCount >= 2)

        let first = try renderReaderPage(layout: layout, paper: .cream, pageIndex: 0, name: "reader-page1")
        let second = try renderReaderPage(layout: layout, paper: .cream, pageIndex: 1, name: "reader-page2")
        #expect(first.inkRatio > 0.01)
        #expect(second.inkRatio > 0.01)

        // 两页内容必须不同（页码/行区间都变了），否则说明翻页没换内容
        let firstData = try Data(contentsOf: first.url)
        let secondData = try Data(contentsOf: second.url)
        #expect(firstData != secondData)

        // 每页正文都不能画进上下留白区
        let typography = layout.typography
        for page in layout.pages {
            var height: CGFloat = 0
            for lineIndex in page.lineRange { height += layout.lines[lineIndex].height }
            #expect(height <= layout.availableHeight + 0.5)
            _ = typography
        }
    }

    @Test(.timeLimit(.minutes(3)))
    func rendersReaderAcrossFontSizesAndLineSpacing() throws {
        let small = makeLayout(fontSize: 14, lineSpacing: .compact)
        let medium = makeLayout(fontSize: 18, lineSpacing: .standard)
        let large = makeLayout(fontSize: 30, lineSpacing: .loose)

        // 字号越大、行距越松，页数越多
        #expect(medium.pageCount > small.pageCount)
        #expect(large.pageCount > medium.pageCount)

        try renderReaderPage(layout: small, paper: .white, pageIndex: 0, name: "reader-font14")
        try renderReaderPage(layout: medium, paper: .white, pageIndex: 0, name: "reader-font18")
        let largeShot = try renderReaderPage(layout: large, paper: .white, pageIndex: 0, name: "reader-font30")
        #expect(largeShot.inkRatio > 0.01)
    }

    @Test(.timeLimit(.minutes(3)))
    func rendersLandscapePage() throws {
        let landscape = CGSize(width: 844, height: 390)
        let layout = makeLayout(size: landscape)
        let result = try renderReaderPage(layout: layout, paper: .green, pageIndex: 0, name: "reader-landscape")
        #expect(result.inkRatio > 0.01)
    }

    @Test(.timeLimit(.minutes(3)))
    func rendersTextCoverGrid() throws {
        let seeds: [(String, String, String?)] = [
            ("bqg99:2639610", "牧神记", "宅猪"),
            ("bqg99:6357328", "我是至尊", "风凌天下"),
            ("bqg99:1944105", "黎明之剑", "远瞳"),
            ("bqg99:1577189", "振南明", "一袖乾坤"),
            ("bqg99:1836466917", "阴阳石", nil),
            ("bqg99:1733848427", "寰宇之证", "变了"),
            ("bqg99:1136852999", "秦沉陆天雪", nil),
            ("bqg99:1036912951", "姬紫月小说", "辰东"),
            ("bqg99:0012345", "前导零 id 的书名会比较长一点用来测截断", "作者名"),
        ]

        // 用非 lazy 的 VStack + HStack：LazyVGrid/ScrollView 在 ImageRenderer 下拿不到
        // viewport，会渲染成一片空白（实测 inkRatio = 0）。
        let rows = seeds.chunked(into: 3)
        let grid = VStack(spacing: 16) {
            ForEach(rows, id: \.first?.0) { row in
                HStack(alignment: .top, spacing: 12) {
                    ForEach(row, id: \.0) { seed, title, author in
                        NovelTextCover(seed: seed, title: title, author: author)
                            .frame(maxWidth: .infinity)
                    }
                    ForEach(0..<(3 - row.count), id: \.self) { _ in
                        Color.clear.frame(maxWidth: .infinity)
                    }
                }
            }
        }
        .padding(16)

        let result = try renderSwiftUI(grid, name: "textcovers", size: CGSize(width: 390, height: 900))
        // 9 张彩色封面 + 白字，墨迹占比应该相当高
        #expect(result.inkRatio > 0.15, "文字封面网格几乎是空白（ink=\(result.inkRatio)）")
    }

    @Test(.timeLimit(.minutes(3)))
    func rendersBookshelf() throws {
        let shelf = BookshelfStore.shared
        shelf.updateProgress(
            source: "bqg99", bookId: "2639610", title: "牧神记", author: "宅猪",
            category: "玄幻", chapterId: "637338569", chapterTitle: "第一章 天黑别出门",
            chapterIndex: 128, totalChapters: 1920, positionInChapter: 0.42)
        shelf.updateProgress(
            source: "bqg99", bookId: "1944105", title: "黎明之剑", author: "远瞳",
            category: "科幻", chapterId: "1", chapterTitle: "第一章",
            chapterIndex: 3, totalChapters: 1400, positionInChapter: 0.1)
        shelf.updateProgress(
            source: "blqvdu", bookId: "0012345", title: "前导零书", author: nil,
            category: nil, chapterId: "9", chapterTitle: "第九章",
            chapterIndex: 9, totalChapters: 200, positionInChapter: 0)
        #expect(shelf.books.count >= 3)

        let result = try renderSwiftUI(
            BookshelfPreviewGrid(books: Array(shelf.sorted.prefix(6))),
            name: "bookshelf"
        )
        #expect(result.inkRatio > 0.05, "书架页几乎是空白（ink=\(result.inkRatio)）")
    }

    @Test(.timeLimit(.minutes(3)))
    func rendersReaderSettingsPanel() throws {
        let settings = NovelSettings.shared
        let original = settings.paper
        defer { settings.paper = original }
        settings.paper = .night

        let result = try renderSwiftUI(
            ReaderSettingsPanel(settings: settings),
            name: "settings-panel",
            background: Color.black,
            size: CGSize(width: 390, height: 420)
        )
        #expect(result.inkRatio > 0.02, "设置面板几乎是空白（ink=\(result.inkRatio)）")
    }
}
