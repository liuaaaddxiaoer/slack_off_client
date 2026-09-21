package com.slackoff.app.danmaku

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

enum class DanmakuColorPreset(val label: String, val tint: Color) {
    ORIGINAL("默认", Color.White),
    WHITE("白", Color.White),
    RED("红", Color.Red),
    YELLOW("黄", Color.Yellow),
    GREEN("绿", Color.Green),
    CYAN("青", Color.Cyan),
    PINK("粉", Color(0xFFFFC0CB)),
    PURPLE("紫", Color(0xFF800080));

    val override: Color?
        get() = if (this == ORIGINAL) null else tint
}

class DanmakuSettings {
    companion object {
        private const val PREFS_NAME = "com.slackoff.danmaku-settings"
    }

    // 用 Compose State 包装，设置变化时 UI 立即刷新（对齐 iOS 的 @Observable）
    var enabled: Boolean by mutableStateOf(true)
    var opacity: Float by mutableStateOf(1.0f)
    var speed: Float by mutableStateOf(1.0f)
    var region: Float by mutableStateOf(1.0f)
    var fontSize: Float by mutableStateOf(16.0f)
    var colorPreset: DanmakuColorPreset by mutableStateOf(DanmakuColorPreset.ORIGINAL)
    var antiOverlap: Boolean by mutableStateOf(true)

    private var prefs: SharedPreferences? = null

    fun load(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val p = prefs!!
        enabled = p.getBoolean("enabled", true)
        opacity = p.getFloat("opacity", 1.0f)
        speed = p.getFloat("speed", 1.0f)
        region = p.getFloat("region", 1.0f)
        fontSize = p.getFloat("fontSize", 16.0f)
        colorPreset = try {
            DanmakuColorPreset.valueOf(p.getString("colorPreset", "ORIGINAL") ?: "ORIGINAL")
        } catch (_: Exception) {
            DanmakuColorPreset.ORIGINAL
        }
        antiOverlap = p.getBoolean("antiOverlap", true)
    }

    fun save() {
        val p = prefs ?: return
        p.edit()
            .putBoolean("enabled", enabled)
            .putFloat("opacity", opacity)
            .putFloat("speed", speed)
            .putFloat("region", region)
            .putFloat("fontSize", fontSize)
            .putString("colorPreset", colorPreset.name)
            .putBoolean("antiOverlap", antiOverlap)
            .apply()
    }
}