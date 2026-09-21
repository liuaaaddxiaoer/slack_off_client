package com.slackoff.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.slackoff.app.ui.theme.Theme

/** 统一的轻量滑块：3dp 轨道 + 14dp 滑块，保留 36dp 触摸区域。 */
@Composable
fun CompactSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null,
    activeColor: Color = Theme.Pink,
    inactiveColor: Color = Theme.Hairline,
) {
    var widthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val range = valueRange.endInclusive - valueRange.start
    val fraction = if (range > 0f) ((value - valueRange.start) / range).coerceIn(0f, 1f) else 0f

    fun update(x: Float) {
        if (widthPx <= 0) return
        val next = valueRange.start + (x / widthPx).coerceIn(0f, 1f) * range
        onValueChange(next)
    }

    Box(
        modifier = modifier
            .height(36.dp)
            .onSizeChanged { widthPx = it.width }
            .pointerInput(valueRange, widthPx) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    update(down.position.x)
                    drag(down.id) { change ->
                        update(change.position.x)
                        change.consume()
                    }
                    onValueChangeFinished?.invoke()
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(inactiveColor, RoundedCornerShape(2.dp))
        )
        if (widthPx > 0) {
            val progressWidth = with(density) { (widthPx * fraction).toDp() }
            Box(
                Modifier
                    .width(progressWidth)
                    .height(3.dp)
                    .background(activeColor, RoundedCornerShape(2.dp))
            )
            Box(
                Modifier
                    .offset(x = progressWidth - 7.dp)
                    .size(14.dp)
                    .shadow(1.dp, CircleShape)
                    .background(activeColor, CircleShape)
            )
        }
    }
}
