import Observation
import SwiftUI

/// 弹幕样式：颜色 / 速度 / 显示区域 / 透明度 / 字号。
/// 所有选项都会持久化到 UserDefaults，下次启动/进入播放页自动恢复。
@Observable
final class DanmakuSettings {
    private static let storageKey = "com.slackoff.danmaku-settings"

    var enabled = true { didSet { save() } }
    var opacity = 1.0 { didSet { save() } }
    var speed = 1.0 { didSet { save() } }
    var region = 1.0 { didSet { save() } }
    var fontSize = 16.0 { didSet { save() } }
    var colorPreset: DanmakuColorPreset = .original { didSet { save() } }
    /// 防重叠：让同时出现的弹幕尽量落到不同轨道。
    var antiOverlap = true { didSet { save() } }

    init() {
        load()
    }

    private func save() {
        let dict: [String: Any] = [
            "enabled": enabled,
            "opacity": opacity,
            "speed": speed,
            "region": region,
            "fontSize": fontSize,
            "colorPreset": colorPreset.rawValue,
            "antiOverlap": antiOverlap
        ]
        UserDefaults.standard.set(dict, forKey: Self.storageKey)
    }

    private func load() {
        guard let dict = UserDefaults.standard.dictionary(forKey: Self.storageKey) else { return }
        enabled = dict["enabled"] as? Bool ?? true
        opacity = dict["opacity"] as? Double ?? 1.0
        speed = dict["speed"] as? Double ?? 1.0
        region = dict["region"] as? Double ?? 1.0
        fontSize = dict["fontSize"] as? Double ?? 16.0
        colorPreset = (dict["colorPreset"] as? String).flatMap(DanmakuColorPreset.init(rawValue:)) ?? .original
        antiOverlap = dict["antiOverlap"] as? Bool ?? true
    }
}

/// 弹幕颜色预设（`.original` 表示沿用每条弹幕自身的颜色）。
enum DanmakuColorPreset: String, CaseIterable, Identifiable {
    case original, white, red, yellow, green, cyan, pink, purple

    var id: String { rawValue }

    var label: String {
        switch self {
        case .original: return "默认"
        case .white: return "白"
        case .red: return "红"
        case .yellow: return "黄"
        case .green: return "绿"
        case .cyan: return "青"
        case .pink: return "粉"
        case .purple: return "紫"
        }
    }

    /// 弹幕实际显示用的颜色；`.original` 返回 nil，表示不强覆盖。
    var override: Color? {
        self == .original ? nil : tint
    }

    var tint: Color {
        switch self {
        case .original: return .white
        case .white: return .white
        case .red: return .red
        case .yellow: return .yellow
        case .green: return .green
        case .cyan: return .cyan
        case .pink: return .pink
        case .purple: return .purple
        }
    }
}