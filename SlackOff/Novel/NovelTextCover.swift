import SwiftUI

/// 文字封面：3:4 圆角「书封」，底色由书名稳定哈希决定，中央书名 + 底部作者 + 左侧书脊。
///
/// 为什么不用真实封面图：源站封面挂在 `www.bqg99.cc` 这类域名下，
/// 设备直连不通（模拟器要借宿主系统代理、真机要手配 Wi-Fi 代理），
/// 与其大面积裂图，不如统一做文字封面 —— 夸克/微信读书的书架占位也是这个路子。
struct NovelTextCover: View {
    let seed: String
    let title: String
    var author: String?
    /// 书名字号随封面宽度缩放的比例；小卡片调小避免挤。
    var titleScale: CGFloat = 0.135
    var cornerRadius: CGFloat = 8
    var showAuthor = true
    var showSpine = true

    var body: some View {
        GeometryReader { proxy in
            let width = proxy.size.width
            let colors = NovelTextCover.palette(for: seed)
            ZStack(alignment: .leading) {
                LinearGradient(colors: colors, startPoint: .topLeading, endPoint: .bottomTrailing)

                // 细密斜纹，避免大色块过于平淡
                NovelTextCover.texture
                    .opacity(0.10)

                if showSpine {
                    // 左侧书脊：一条深色竖带 + 高光，模拟装帧
                    HStack(spacing: 0) {
                        Rectangle()
                            .fill(.black.opacity(0.22))
                            .frame(width: max(width * 0.055, 3))
                        Rectangle()
                            .fill(.white.opacity(0.18))
                            .frame(width: 1)
                        Spacer(minLength: 0)
                    }
                }

                VStack(spacing: width * 0.03) {
                    Spacer(minLength: width * 0.10)
                    Text(title)
                        .font(.system(size: max(width * titleScale, 9), weight: .semibold))
                        .foregroundStyle(.white)
                        .multilineTextAlignment(.center)
                        .lineLimit(3)
                        .minimumScaleFactor(0.6)
                        .shadow(color: .black.opacity(0.35), radius: 1.5, y: 1)
                        .padding(.horizontal, width * (showSpine ? 0.14 : 0.10))
                    Spacer(minLength: width * 0.06)
                    if showAuthor, let author, !author.isEmpty {
                        Text(author)
                            .font(.system(size: max(width * 0.085, 7)))
                            .foregroundStyle(.white.opacity(0.82))
                            .lineLimit(1)
                            .padding(.horizontal, width * 0.12)
                            .padding(.bottom, width * 0.10)
                    }
                }
                .frame(width: width, alignment: .center)
            }
            .overlay(
                RoundedRectangle(cornerRadius: cornerRadius)
                    .strokeBorder(Theme.hairline, lineWidth: 1)
            )
            .clipShape(RoundedRectangle(cornerRadius: cornerRadius))
        }
        .aspectRatio(3.0 / 4.0, contentMode: .fit)
    }

    // MARK: - 取色

    /// 8 组渐变（亮 → 暗）。同一本书在任何页面取到同一组。
    private static let palette: [[Color]] = [
        [Color(red: 0.98, green: 0.47, blue: 0.60), Color(red: 0.72, green: 0.20, blue: 0.40)],  // 玫红
        [Color(red: 1.00, green: 0.66, blue: 0.38), Color(red: 0.85, green: 0.36, blue: 0.22)],  // 橙
        [Color(red: 0.93, green: 0.76, blue: 0.42), Color(red: 0.62, green: 0.44, blue: 0.22)],  // 琥珀
        [Color(red: 0.45, green: 0.78, blue: 0.60), Color(red: 0.17, green: 0.45, blue: 0.35)],  // 森绿
        [Color(red: 0.42, green: 0.78, blue: 0.85), Color(red: 0.15, green: 0.42, blue: 0.58)],  // 青
        [Color(red: 0.47, green: 0.56, blue: 0.93), Color(red: 0.22, green: 0.26, blue: 0.62)],  // 靛蓝
        [Color(red: 0.72, green: 0.55, blue: 0.93), Color(red: 0.42, green: 0.25, blue: 0.66)],  // 紫
        [Color(red: 0.62, green: 0.68, blue: 0.75), Color(red: 0.28, green: 0.33, blue: 0.42)],  // 石板蓝灰
    ]

    /// 跨启动稳定的字符串哈希。
    ///
    /// **不能用 `String.hashValue`**：Swift 的 Hasher 每次进程启动都换随机种子，
    /// 同一本书重启后会换颜色，书架看起来像在闪。这里用 FNV-1a（32 位）。
    nonisolated static func stableHash(_ string: String) -> Int {
        var hash: UInt32 = 2_166_136_261
        for byte in string.utf8 {
            hash ^= UInt32(byte)
            hash = hash &* 16_777_619
        }
        return Int(hash & 0x7FFF_FFFF)
    }

    nonisolated static func palette(for seed: String) -> [Color] {
        palette[stableHash(seed) % palette.count]
    }

    /// 45° 细斜纹（纯 Canvas 绘制，无图片资源）。
    private static var texture: some View {
        Canvas { context, size in
            var path = Path()
            let step: CGFloat = 6
            var x = -size.height
            while x < size.width {
                path.move(to: CGPoint(x: x, y: size.height))
                path.addLine(to: CGPoint(x: x + size.height, y: 0))
                x += step
            }
            context.stroke(path, with: .color(.white), lineWidth: 0.6)
        }
    }
}

/// 从 NovelBook / ShelfBook 快速生成封面。
extension NovelTextCover {
    init(book: NovelBook, cornerRadius: CGFloat = 8, showAuthor: Bool = true, titleScale: CGFloat = 0.135) {
        self.init(
            seed: book.coverSeed,
            title: book.title,
            author: book.author,
            titleScale: titleScale,
            cornerRadius: cornerRadius,
            showAuthor: showAuthor
        )
    }

    init(shelf: ShelfBook, cornerRadius: CGFloat = 8, showAuthor: Bool = true, titleScale: CGFloat = 0.135) {
        self.init(
            seed: shelf.id,
            title: shelf.title,
            author: shelf.author,
            titleScale: titleScale,
            cornerRadius: cornerRadius,
            showAuthor: showAuthor
        )
    }

    init(seed: String, title: String, cornerRadius: CGFloat, showAuthor: Bool, titleScale: CGFloat) {
        self.init(
            seed: seed,
            title: title,
            author: nil,
            titleScale: titleScale,
            cornerRadius: cornerRadius,
            showAuthor: showAuthor
        )
    }
}

#Preview("文字封面") {
    ScrollView {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 100), spacing: 12)], spacing: 16) {
            ForEach(
                [
                    ("bqg99:2639610", "牧神记", "宅猪"),
                    ("bqg99:6357328", "我是至尊", "风凌天下"),
                    ("bqg99:1944105", "黎明之剑", "远瞳"),
                    ("bqg99:1577189", "振南明", "一袖乾坤"),
                    ("bqg99:1836466917", "阴阳石", nil),
                    ("bqg99:1733848427", "寰宇之证", "变了"),
                    ("bqg99:1136852999", "秦沉陆天雪", nil),
                    ("bqg99:1036912951", "姬紫月小说", "辰东"),
                    ("bqg99:0001234", "0001234 前导零书名测试", "作者"),
                ],
                id: \.0
            ) { seed, title, author in
                NovelTextCover(seed: seed, title: title, author: author)
            }
        }
        .padding(16)
    }
    .background(Theme.cream)
}
