package com.slackoff.app.ui.novel

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slackoff.app.support.BookshelfStore
import com.slackoff.app.support.NovelLineSpacing
import com.slackoff.app.support.NovelPagingMode
import com.slackoff.app.support.NovelPaper
import com.slackoff.app.support.NovelSettingsData
import com.slackoff.app.support.NovelSettingsStore
import com.slackoff.app.support.ScreenInsets
import com.slackoff.app.ui.components.CompactSlider
import com.slackoff.app.ui.theme.Theme

/** 阅读器设置面板：字号 / 行距 / 纸色 / 翻页模式 / 亮度，改完即时生效并持久化。 */
@Composable
fun ReaderSettingsPanel(modifier: Modifier = Modifier) {
    val settings by NovelSettingsStore.settings.collectAsState()
    val panelText = settings.paper.text

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(settings.paper.background)
            .border(1.dp, Theme.Hairline, RoundedCornerShape(20.dp))
            .padding(horizontal = 20.dp)
            .padding(bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 8.dp)
                .width(36.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(panelText.copy(alpha = 0.25f))
        )

        // 字号
        SettingRow("字号", value = "${settings.fontSize.toInt()}") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StepButton("A", 13.sp, panelText, Modifier.size(32.dp)) {
                    NovelSettingsStore.setFontSize(settings.fontSize - 1f)
                }
                CompactSlider(
                    value = settings.fontSize,
                    onValueChange = { NovelSettingsStore.setFontSize(it) },
                    valueRange = NovelSettingsData.FONT_SIZE_RANGE,
                    modifier = Modifier.weight(1f),
                )
                StepButton("A", 19.sp, panelText, Modifier.size(38.dp)) {
                    NovelSettingsStore.setFontSize(settings.fontSize + 1f)
                }
            }
        }

        // 行距
        SettingRow("行距") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NovelLineSpacing.entries.forEach { spacing ->
                    OptionChip(
                        label = spacing.label,
                        selected = settings.lineSpacing == spacing,
                        textColor = panelText,
                        modifier = Modifier.weight(1f),
                    ) { NovelSettingsStore.setLineSpacing(spacing) }
                }
            }
        }

        // 纸色
        SettingRow("背景") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NovelPaper.entries.forEach { paper ->
                    val selected = settings.paper == paper
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(paper.background)
                            .border(
                                if (selected) 2.5.dp else 1.dp,
                                if (selected) Theme.Pink else Theme.Hairline,
                                CircleShape,
                            )
                            .clickable { NovelSettingsStore.setPaper(paper) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = paper.label.take(1),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = paper.text,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
            }
        }

        // 翻页模式
        SettingRow("翻页") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NovelPagingMode.entries.forEach { mode ->
                    OptionChip(
                        label = mode.label,
                        selected = settings.pagingMode == mode,
                        textColor = panelText,
                        modifier = Modifier.weight(1f),
                    ) { NovelSettingsStore.setPagingMode(mode) }
                }
            }
        }

        // 亮度
        SettingRow("亮度") {
            val context = LocalContext.current
            val activity = remember(context) { ScreenInsets.findActivity(context) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(
                    imageVector = Icons.Filled.LightMode,
                    contentDescription = null,
                    tint = panelText.copy(alpha = 0.65f),
                    modifier = Modifier.size(14.dp),
                )
                CompactSlider(
                    value = settings.brightness ?: 0.5f,
                    onValueChange = { value ->
                        NovelSettingsStore.setBrightness(value)
                        applyBrightness(activity, value)
                    },
                    valueRange = 0.05f..1f,
                    modifier = Modifier.weight(1f),
                )
                if (settings.brightness != null) {
                    Text(
                        text = "跟随系统",
                        fontSize = 11.sp,
                        color = Theme.Pink,
                        modifier = Modifier.clickable { NovelSettingsStore.setBrightness(null) },
                    )
                }
            }
        }
    }
}

fun applyBrightness(activity: Activity?, value: Float) {
    val window = activity?.window ?: return
    val attrs = window.attributes
    attrs.screenBrightness = value.coerceIn(0.05f, 1f)
    window.attributes = attrs
}

@Composable
private fun StepButton(label: String, fontSize: androidx.compose.ui.unit.TextUnit, tint: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.10f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = fontSize, fontWeight = FontWeight.SemiBold, color = tint)
    }
}

@Composable
private fun OptionChip(
    label: String,
    selected: Boolean,
    textColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) Theme.Pink else textColor.copy(alpha = 0.10f))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) Color.White else textColor.copy(alpha = 0.8f),
        )
    }
}

@Composable
private fun SettingRow(
    title: String,
    value: String? = null,
    content: @Composable () -> Unit,
) {
    val settings by NovelSettingsStore.settings.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = settings.paper.text.copy(alpha = 0.65f))
            Spacer(Modifier.weight(1f))
            if (value != null) {
                Text(value, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = settings.paper.text.copy(alpha = 0.85f))
            }
        }
        content()
    }
}
