import SwiftUI
import UIKit

/// 阅读器设置面板：字号 / 行距 / 纸色 / 翻页模式 / 亮度，从底部弹出，改完即时生效并持久化。
struct ReaderSettingsPanel: View {
    @Bindable var settings: NovelSettings

    var body: some View {
        VStack(spacing: 18) {
            Capsule()
                .fill(panelText.opacity(0.25))
                .frame(width: 36, height: 4)
                .padding(.top, 8)

            fontSizeRow
            lineSpacingRow
            paperRow
            pagingRow
            brightnessRow
        }
        .padding(.horizontal, 20)
        .padding(.bottom, ScreenInsets.bottom + 18)
        .background(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .fill(settings.paper.background)
                .overlay(
                    RoundedRectangle(cornerRadius: 20, style: .continuous)
                        .strokeBorder(Theme.hairline, lineWidth: 1)
                )
                .shadow(color: .black.opacity(0.22), radius: 18, y: -4)
        )
        .padding(.horizontal, 8)
    }

    private var panelText: Color { settings.paper.text }

    // MARK: 字号

    private var fontSizeRow: some View {
        row(title: "字号", value: "\(Int(settings.fontSize))") {
            HStack(spacing: 12) {
                Button {
                    settings.fontSize = max(
                        NovelSettings.fontSizeRange.lowerBound,
                        settings.fontSize - 1
                    )
                } label: {
                    Text("A")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(panelText)
                        .frame(width: 32, height: 32)
                        .background(panelText.opacity(0.10), in: Circle())
                }

                Slider(value: $settings.fontSize, in: NovelSettings.fontSizeRange, step: 1)
                    .tint(panelText.opacity(0.75))

                Button {
                    settings.fontSize = min(
                        NovelSettings.fontSizeRange.upperBound,
                        settings.fontSize + 1
                    )
                } label: {
                    Text("A")
                        .font(.system(size: 19, weight: .semibold))
                        .foregroundStyle(panelText)
                        .frame(width: 38, height: 38)
                        .background(panelText.opacity(0.10), in: Circle())
                }
            }
        }
    }

    // MARK: 行距

    private var lineSpacingRow: some View {
        row(title: "行距") {
            HStack(spacing: 8) {
                ForEach(NovelLineSpacing.allCases) { spacing in
                    Button {
                        settings.lineSpacing = spacing
                    } label: {
                        Text(spacing.name)
                            .font(.system(size: 12, weight: settings.lineSpacing == spacing ? .semibold : .regular))
                            .foregroundStyle(settings.lineSpacing == spacing ? .white : panelText.opacity(0.8))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 8)
                            .background(
                                settings.lineSpacing == spacing
                                    ? AnyShapeStyle(Theme.pink)
                                    : AnyShapeStyle(panelText.opacity(0.10)),
                                in: RoundedRectangle(cornerRadius: 9)
                            )
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    // MARK: 纸色

    private var paperRow: some View {
        row(title: "背景") {
            HStack(spacing: 12) {
                ForEach(NovelPaper.allCases) { paper in
                    Button {
                        settings.paper = paper
                    } label: {
                        ZStack {
                            Circle()
                                .fill(paper.background)
                                .frame(width: 34, height: 34)
                                .overlay(
                                    Circle().strokeBorder(
                                        settings.paper == paper ? Theme.pink : Theme.hairline,
                                        lineWidth: settings.paper == paper ? 2.5 : 1
                                    )
                                )
                            Text(String(paper.name.prefix(1)))
                                .font(.system(size: 11, weight: .medium))
                                .foregroundStyle(paper.text)
                        }
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(paper.name)
                }
                Spacer(minLength: 0)
            }
        }
    }

    // MARK: 翻页模式

    private var pagingRow: some View {
        row(title: "翻页") {
            HStack(spacing: 8) {
                ForEach(NovelPagingMode.allCases) { mode in
                    Button {
                        settings.pagingMode = mode
                    } label: {
                        VStack(spacing: 4) {
                            Image(systemName: mode.systemImage)
                                .font(.system(size: 15))
                            Text(mode.name)
                                .font(.system(size: 11))
                        }
                        .foregroundStyle(settings.pagingMode == mode ? .white : panelText.opacity(0.8))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 9)
                        .background(
                            settings.pagingMode == mode
                                ? AnyShapeStyle(Theme.pink)
                                : AnyShapeStyle(panelText.opacity(0.10)),
                            in: RoundedRectangle(cornerRadius: 9)
                        )
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    // MARK: 亮度

    private var brightnessRow: some View {
        row(title: "亮度") {
            HStack(spacing: 10) {
                Image(systemName: "sun.min")
                    .font(.system(size: 11))
                    .foregroundStyle(panelText.opacity(0.65))
                Slider(
                    value: Binding(
                        get: { settings.brightness ?? Double(UIScreen.main.brightness) },
                        set: { newValue in
                            settings.brightness = newValue
                            UIScreen.main.brightness = CGFloat(newValue)
                        }
                    ),
                    in: 0.05...1.0
                )
                .tint(panelText.opacity(0.75))
                Image(systemName: "sun.max")
                    .font(.system(size: 14))
                    .foregroundStyle(panelText.opacity(0.65))
                if settings.brightness != nil {
                    Button("跟随系统") {
                        settings.brightness = nil
                    }
                    .font(.system(size: 11))
                    .foregroundStyle(Theme.pink)
                }
            }
        }
    }

    // MARK: 布局骨架

    private func row<Content: View>(
        title: String,
        value: String? = nil,
        @ViewBuilder content: () -> Content
    ) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(title)
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(panelText.opacity(0.65))
                Spacer()
                if let value {
                    Text(value)
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(panelText.opacity(0.85))
                }
            }
            content()
        }
    }
}
