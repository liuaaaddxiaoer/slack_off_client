package com.slackoff.app.danmaku

import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slackoff.app.model.DanmakuComment
import kotlin.math.roundToInt

@Composable
fun DanmakuView(
    comments: List<DanmakuComment>,
    currentTime: Double,
    settings: DanmakuSettings,
    topInsetDp: Float = 24f,
    modifier: Modifier = Modifier
) {
    if (!settings.enabled) return
    val density = LocalDensity.current
    // 顶部安全区域偏移（全屏时由调用方传入真实状态栏高度，避免弹幕被刘海/状态栏遮挡）
    val topInsetPx = with(density) { topInsetDp.dp.toPx() }

    val scrollSpeed = (150 * maxOf(settings.speed, 0.25f)).toDouble()
    val lineHeight = with(density) { (maxOf(settings.fontSize, 14f) + 8).sp.toPx() }

    var containerSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .alpha(settings.opacity)
            .onSizeChanged { containerSize = it }
    ) {
        if (containerSize.width == 0) return@Box

        val laneCount = maxOf(1, ((containerSize.height * settings.region - topInsetPx) / lineHeight).toInt())
        val maxDrift = containerSize.width / scrollSpeed + 8.0

        val visible = comments
            .filter { it.time <= currentTime && currentTime < it.time + maxDrift }
            .sortedBy { it.time }

        val laneMemory = remember { mutableMapOf<String, Int>() }
        val freeAt = DoubleArray(laneCount) { 0.0 }
        val placed = mutableListOf<Pair<DanmakuComment, Int>>()

        for (comment in visible) {
            val lane = if (laneMemory.containsKey(comment.id)) {
                laneMemory[comment.id]!!
            } else if (settings.antiOverlap) {
                val busy = (60.0 + textWidth(comment.text, settings.fontSize)) / scrollSpeed
                var chosen = -1
                var bestLane = 0
                var bestFree = Double.MAX_VALUE
                for (i in 0 until laneCount) {
                    if (freeAt[i] <= comment.time) { chosen = i; break }
                    if (freeAt[i] < bestFree) { bestFree = freeAt[i]; bestLane = i }
                }
                if (chosen < 0) chosen = bestLane
                laneMemory[comment.id] = chosen
                chosen
            } else {
                (comment.lane.toInt() % laneCount).also { laneMemory[comment.id] = it }
            }
            freeAt[lane] = maxOf(freeAt[lane], comment.time) + (60.0 + textWidth(comment.text, settings.fontSize)) / scrollSpeed
            placed.add(comment to lane)
        }

        for ((comment, laneIndex) in placed) {
            val drift = currentTime - comment.time
            val x = (containerSize.width + 60 - drift * scrollSpeed).toFloat()
            val y = topInsetPx + lineHeight * laneIndex + lineHeight / 2

            if (x < -120) continue

            val textColor = settings.colorPreset.override ?: comment.colorValue

            Box(
                modifier = Modifier
                    .offset { IntOffset(x.roundToInt(), y.roundToInt() - (lineHeight / 2).roundToInt()) }
            ) {
                androidx.compose.material3.Text(
                    text = comment.text,
                    style = TextStyle(
                        fontSize = settings.fontSize.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = textColor,
                        shadow = Shadow(
                            color = Color.Black.copy(alpha = 0.55f),
                            blurRadius = 1.2f,
                            offset = Offset(0f, 1f)
                        )
                    ),
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

private val textWidthCache = mutableMapOf<String, Float>()

private fun textWidth(text: String, fontSize: Float): Float {
    val key = "${fontSize.toInt()}|$text"
    return textWidthCache.getOrPut(key) {
        var width = 0f
        for (ch in text) {
            width += if (ch.code > 127) fontSize * 1.0f else fontSize * 0.6f
        }
        width + 8f
    }
}

val DanmakuComment.colorValue: Color
    get() {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return Color(r, g, b)
    }