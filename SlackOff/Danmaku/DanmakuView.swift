import SwiftUI

struct DanmakuView: View {
    let comments: [DanmakuComment]
    let currentTime: Double
    let settings: DanmakuSettings

    /// 基础滚动速度（字号/速度倍率影响后的 px/s）。
    private var scrollSpeed: CGFloat {
        CGFloat(150 * max(settings.speed, 0.25))
    }

    /// 单条滚动弹幕的轨道行高。
    private var lineHeight: CGFloat {
        max(settings.fontSize + 8, 22)
    }

    var body: some View {
        GeometryReader { geo in
            let laneCount = self.laneCount(size: geo.size)
            let placed = placedComments(size: geo.size, laneCount: laneCount)

            ZStack(alignment: .topLeading) {
                ForEach(placed) { item in
                    DanmakuLabel(
                        comment: item.comment,
                        drift: currentTime - item.comment.time,
                        laneIndex: item.laneIndex,
                        laneCount: laneCount,
                        lineHeight: lineHeight,
                        size: geo.size,
                        settings: settings
                    )
                }
            }
            .clipShape(Rectangle())
        }
        .opacity(settings.opacity)
        .allowsHitTesting(false)
    }

    private struct PlacedItem: Identifiable {
        let id: UUID
        let comment: DanmakuComment
        let laneIndex: Int
    }

    // MARK: - 轨道分配（纯确定性 round-robin，零状态、无跳动、无额外渲染）

    private func placedComments(size: CGSize, laneCount: Int) -> [PlacedItem] {
        let speed = Double(scrollSpeed)
        let maxDrift = Double(size.width) / speed + 8
        let visible = comments
            .filter { $0.time <= currentTime && currentTime < $0.time + maxDrift }
            .sorted { $0.time < $1.time }

        return visible.enumerated().map { index, comment in
            let lane: Int
            if settings.antiOverlap {
                // 防重叠：按时间戳排序后依次分到 0,1,2… 轨道，连续弹幕不撞轨
                lane = index % laneCount
            } else {
                lane = Int(comment.lane) % laneCount
            }
            return PlacedItem(id: comment.id, comment: comment, laneIndex: lane)
        }
    }

    /// 按「显示区域」高度动态决定能塞几行轨道。
    private func laneCount(size: CGSize) -> Int {
        let topInset: CGFloat = 24
        let usable = size.height * settings.region
        return max(1, Int((usable - topInset) / lineHeight))
    }
}

private struct DanmakuLabel: View {
    let comment: DanmakuComment
    let drift: Double
    let laneIndex: Int
    let laneCount: Int
    let lineHeight: CGFloat
    let size: CGSize
    let settings: DanmakuSettings

    var body: some View {
        let speed = Double(150 * max(settings.speed, 0.25))
        let x = size.width + 60 - drift * speed
        Text(comment.text)
            .font(.system(size: settings.fontSize, weight: .semibold))
            .foregroundStyle(textColor)
            .lineLimit(1)
            .fixedSize()
            .position(x: x, y: laneY)
            .opacity(x < -120 ? 0 : 1)
    }

    private var textColor: Color {
        settings.colorPreset.override ?? comment.colorValue
    }

    /// 滚动弹幕：按轨道序号落到固定行高；顶部/底部固定轨道独立。
    private var laneY: CGFloat {
        switch comment.type {
        case 5:
            return 24
        case 4:
            return size.height - 24
        default:
            let band = min(max(laneIndex, 0), max(laneCount - 1, 0))
            let topInset: CGFloat = 24
            return topInset + lineHeight * CGFloat(band) + lineHeight / 2
        }
    }
}

extension DanmakuComment {
    var colorValue: Color {
        let r = Double((color >> 16) & 0xFF) / 255
        let g = Double((color >> 8) & 0xFF) / 255
        let b = Double(color & 0xFF) / 255
        return Color(red: r, green: g, blue: b)
    }
}