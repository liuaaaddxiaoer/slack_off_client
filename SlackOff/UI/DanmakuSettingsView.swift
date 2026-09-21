import SwiftUI

/// 弹幕样式设置面板：颜色 / 字号 / 速度 / 显示区域 / 透明度。
/// 作为右侧抽屉的内容嵌入，标题和关闭按钮由外层抽屉提供。
struct DanmakuSettingsView: View {
    let settings: DanmakuSettings

    var body: some View {
        @Bindable var settings = settings

        Form {
            Section {
                Toggle("显示弹幕", isOn: $settings.enabled)
                    .tint(Theme.pink)
                Toggle("防重叠", isOn: $settings.antiOverlap)
                    .tint(Theme.pink)
            }

            Section("字号") {
                Picker("字号", selection: $settings.fontSize) {
                    ForEach([14.0, 16.0, 18.0, 22.0, 26.0], id: \.self) { size in
                        Text("\(Int(size))").tag(size)
                    }
                }
                .pickerStyle(.segmented)
            }

            Section("弹幕颜色") {
                colorPicker
            }

            Section("弹幕速度") {
                Picker("速度", selection: $settings.speed) {
                    Text("慢").tag(0.5)
                    Text("正常").tag(1.0)
                    Text("快").tag(1.5)
                    Text("很快").tag(2.2)
                }
                .pickerStyle(.segmented)
            }

            Section("显示区域") {
                VStack(alignment: .leading, spacing: 6) {
                    Slider(value: $settings.region, in: 0.25...1.0)
                        .tint(Theme.pink)
                    HStack {
                        Text("\(Int(settings.region * 100))% 屏")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        Spacer()
                        ForEach([0.5, 0.75, 1.0], id: \.self) { value in
                            Button {
                                settings.region = value
                            } label: {
                                Text("\(Int(value * 100))%")
                                    .font(.caption2.bold())
                                    .padding(.horizontal, 6)
                                    .padding(.vertical, 3)
                                    .background(
                                        settings.region == value ? Theme.pink : Color.clear,
                                        in: Capsule()
                                    )
                                    .foregroundStyle(settings.region == value ? .white : .secondary)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }

            Section("不透明度") {
                Slider(value: $settings.opacity, in: 0.1...1.0) {
                    Text("不透明度")
                }
                .tint(Theme.pink)
            }
        }
        .scrollContentBackground(.hidden)
        .background(Color(white: 0.12))
        .preferredColorScheme(.dark)
    }

    private var colorPicker: some View {
        HStack(spacing: 8) {
            ForEach(DanmakuColorPreset.allCases) { preset in
                Button {
                    settings.colorPreset = preset
                } label: {
                    VStack(spacing: 4) {
                        swatch(preset)
                        Text(preset.label)
                            .font(.caption2)
                            .foregroundStyle(settings.colorPreset == preset ? Theme.pink : .secondary)
                    }
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.vertical, 4)
    }

    @ViewBuilder
    private func swatch(_ preset: DanmakuColorPreset) -> some View {
        ZStack {
            if preset == .original {
                Circle().fill(
                    LinearGradient(colors: [.white, .gray.opacity(0.6)], startPoint: .top, endPoint: .bottom)
                )
                Text("原")
                    .font(.caption2.bold())
                    .foregroundStyle(.black)
            } else {
                Circle().fill(preset.tint)
            }
        }
        .frame(width: 30, height: 30)
        .overlay {
            if settings.colorPreset == preset {
                Circle().strokeBorder(Theme.pink, lineWidth: 2.5)
            } else {
                Circle().strokeBorder(Color.black.opacity(0.14), lineWidth: 1)
            }
        }
    }
}