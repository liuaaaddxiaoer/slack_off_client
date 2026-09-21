package com.slackoff.app.ui.novel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slackoff.app.model.NovelBook
import com.slackoff.app.support.ShelfBook
import com.slackoff.app.ui.theme.Theme

/**
 * 文字封面：3:4 圆角「书封」，底色由书名稳定哈希决定，中央书名 + 底部作者 + 左侧书脊。
 *
 * 为什么不用真实封面图：源站封面挂在 www.bqg99.cc 这类域名下，设备直连不通
 * （要手配 Wi-Fi 代理），与其大面积裂图，不如统一文字封面 ——
 * 夸克/微信读书的书架占位也是这个路子。与 iOS 端 NovelTextCover.swift 同色板同算法。
 */
@Composable
fun NovelTextCover(
    seed: String,
    title: String,
    author: String? = null,
    modifier: Modifier = Modifier,
    titleScale: Float = 0.135f,
    cornerRadius: Dp = 8.dp,
    showAuthor: Boolean = true,
    showSpine: Boolean = true,
) {
    val colors = remember(seed) { novelCoverPalette(seed) }
    val shape = RoundedCornerShape(cornerRadius)

    BoxWithConstraints(
        modifier = modifier
            .aspectRatio(3f / 4f)
            .clip(shape)
            .background(Brush.linearGradient(colors))
            .border(1.dp, Theme.Hairline, shape)
    ) {
        val widthValue = maxWidth.value

        // 细密斜纹，避免大色块过于平淡
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .alpha(0.10f)
        ) {
            val step = 6.dp.toPx()
            var x = -size.height
            while (x < size.width) {
                drawLine(
                    color = Color.White,
                    start = Offset(x, size.height),
                    end = Offset(x + size.height, 0f),
                    strokeWidth = 0.6.dp.toPx(),
                )
                x += step
            }
        }

        // 左侧书脊：深色竖带 + 高光，模拟装帧
        if (showSpine) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width((widthValue * 0.055f).coerceAtLeast(3f).dp)
                    .background(Color.Black.copy(alpha = 0.22f))
            )
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(1.dp)
                    .padding(start = 0.dp)
                    .background(Color.White.copy(alpha = 0.18f))
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = (widthValue * if (showSpine) 0.14f else 0.10f).dp,
                    end = (widthValue * 0.10f).dp,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(Modifier.height((widthValue * 0.06f).dp))
            Text(
                text = title,
                fontSize = (widthValue * titleScale).coerceAtLeast(9f).sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                lineHeight = (widthValue * titleScale * 1.25f).coerceAtLeast(11f).sp,
            )
            Spacer(Modifier.weight(1f))
            if (showAuthor && !author.isNullOrEmpty()) {
                Text(
                    text = author,
                    fontSize = (widthValue * 0.085f).coerceAtLeast(7f).sp,
                    color = Color.White.copy(alpha = 0.82f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height((widthValue * 0.10f).dp))
            } else {
                Spacer(Modifier.height((widthValue * 0.10f).dp))
            }
        }
    }
}

@Composable
fun NovelTextCover(book: NovelBook, modifier: Modifier = Modifier, cornerRadius: Dp = 8.dp, showAuthor: Boolean = true, titleScale: Float = 0.135f) {
    NovelTextCover(
        seed = book.coverSeed,
        title = book.title,
        author = book.author,
        modifier = modifier,
        titleScale = titleScale,
        cornerRadius = cornerRadius,
        showAuthor = showAuthor,
    )
}

@Composable
fun NovelTextCover(shelf: ShelfBook, modifier: Modifier = Modifier, cornerRadius: Dp = 8.dp, showAuthor: Boolean = true, titleScale: Float = 0.135f) {
    NovelTextCover(
        seed = shelf.key,
        title = shelf.title,
        author = shelf.author,
        modifier = modifier,
        titleScale = titleScale,
        cornerRadius = cornerRadius,
        showAuthor = showAuthor,
    )
}

// MARK: - 取色（纯函数，可 JVM 单测）

/** 8 组渐变（亮 → 暗），色值与 iOS 端 NovelTextCover.palette 完全一致。 */
val novelCoverPaletteColors: List<List<Color>> = listOf(
    listOf(Color(0xFFFA7899), Color(0xFFB83366)),  // 玫红
    listOf(Color(0xFFFFA861), Color(0xFFD95C38)),  // 橙
    listOf(Color(0xFFEDC26B), Color(0xFF9E7038)),  // 琥珀
    listOf(Color(0xFF73C799), Color(0xFF2B7359)),  // 森绿
    listOf(Color(0xFF6BC7D9), Color(0xFF266B94)),  // 青
    listOf(Color(0xFF788FED), Color(0xFF38429E)),  // 靛蓝
    listOf(Color(0xFFB88CED), Color(0xFF6B40A8)),  // 紫
    listOf(Color(0xFF9EADBF), Color(0xFF47546B)),  // 石板蓝灰
)

/**
 * 跨启动稳定的字符串哈希（FNV-1a 32 位）。
 * 不能用 Kotlin 的 `String.hashCode()`？可以用（它规定死了算法），
 * 但为了让两端封面颜色一致，这里显式实现同一套 FNV-1a。
 */
fun stableCoverHash(value: String): Int {
    var hash = 2166136261.toInt()
    for (byte in value.toByteArray(Charsets.UTF_8)) {
        hash = hash xor (byte.toInt() and 0xFF)
        hash *= 16777619
    }
    return hash and 0x7FFFFFFF
}

fun novelCoverPalette(seed: String): List<Color> =
    novelCoverPaletteColors[stableCoverHash(seed) % novelCoverPaletteColors.size]
