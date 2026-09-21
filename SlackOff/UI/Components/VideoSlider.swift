import SwiftUI

/// 细轨道的进度条：3pt 轨道 + 10pt 小圆点，替代系统 Slider 那个大圆钮。
struct VideoSlider: View {
    @Binding var value: Double            // 0...1
    var trackTint: Color = .white.opacity(0.25)
    var progressTint: Color = Theme.pink

    var body: some View {
        GeometryReader { geo in
            let width = geo.size.width
            let x = min(max(CGFloat(value) * width, 0), width)

            ZStack(alignment: .leading) {
                Capsule()
                    .fill(trackTint)
                    .frame(height: 3)
                Capsule()
                    .fill(progressTint)
                    .frame(width: max(x, 0), height: 3)
                Circle()
                    .fill(.white)
                    .frame(width: 10, height: 10)
                    .shadow(color: .black.opacity(0.35), radius: 1.5, x: 0, y: 1)
                    .position(x: x, y: geo.size.height / 2)
            }
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { g in
                        value = min(max(Double(g.location.x / max(width, 1)), 0), 1)
                    }
            )
        }
        .frame(height: 18)
    }
}