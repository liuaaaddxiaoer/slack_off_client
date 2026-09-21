package com.slackoff.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.slackoff.app.ui.theme.Theme

@Composable
fun VideoSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    trackColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.25f),
    progressColor: androidx.compose.ui.graphics.Color = Theme.Pink
) {
    var sliderWidth by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .onSizeChanged { sliderWidth = it.width }
            .pointerInput(sliderWidth) {
                if (sliderWidth <= 0) return@pointerInput
                detectHorizontalDragGestures { change, _ ->
                    val fraction = (change.position.x / sliderWidth).coerceIn(0f, 1f)
                    onValueChange(fraction)
                }
            }
    ) {
        val x = (value * sliderWidth).coerceIn(0f, sliderWidth.toFloat())
        val pxX = with(density) { x.toDp() }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .align(androidx.compose.ui.Alignment.CenterStart)
                .background(trackColor, RoundedCornerShape(2.dp))
        )
        Box(
            modifier = Modifier
                .width(pxX)
                .height(3.dp)
                .align(androidx.compose.ui.Alignment.CenterStart)
                .background(progressColor, RoundedCornerShape(2.dp))
        )
        Box(
            modifier = Modifier
                .offset(x = pxX - 5.dp)
                .size(10.dp)
                .shadow(1.5.dp, CircleShape)
                .background(androidx.compose.ui.graphics.Color.White, CircleShape)
                .align(androidx.compose.ui.Alignment.CenterStart)
        )
    }
}