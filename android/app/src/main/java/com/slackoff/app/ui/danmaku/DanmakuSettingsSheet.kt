package com.slackoff.app.ui.danmaku

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slackoff.app.danmaku.DanmakuColorPreset
import com.slackoff.app.danmaku.DanmakuSettings
import com.slackoff.app.ui.components.CompactSlider
import com.slackoff.app.ui.theme.Theme

@Composable
fun DanmakuSettingsSheet(
    settings: DanmakuSettings,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .background(Color(0xFF1C1C1E))
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Enabled toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("显示弹幕", color = Color.White, fontSize = 16.sp)
            Switch(
                checked = settings.enabled,
                onCheckedChange = { settings.enabled = it; settings.save() },
                colors = SwitchDefaults.colors(checkedTrackColor = Theme.Pink)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("防重叠", color = Color.White, fontSize = 16.sp)
            Switch(
                checked = settings.antiOverlap,
                onCheckedChange = { settings.antiOverlap = it; settings.save() },
                colors = SwitchDefaults.colors(checkedTrackColor = Theme.Pink)
            )
        }

        // Font size
        Text("字号", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (size in listOf(14f, 16f, 18f, 22f, 26f)) {
                FilterChip(
                    selected = settings.fontSize == size,
                    onClick = { settings.fontSize = size; settings.save() },
                    label = { Text("${size.toInt()}", fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Theme.Pink,
                        selectedLabelColor = Color.White,
                        containerColor = Color.White.copy(alpha = 0.1f),
                        labelColor = Color.White
                    )
                )
            }
        }

        // Color
        Text("弹幕颜色", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (preset in DanmakuColorPreset.entries) {
                val selected = settings.colorPreset == preset
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clickable { settings.colorPreset = preset; settings.save() }
                        .clip(RoundedCornerShape(8.dp))
                        .then(
                            if (selected) Modifier.background(Theme.Pink.copy(alpha = 0.3f))
                            else Modifier
                        )
                        .padding(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(
                                brush = if (preset == DanmakuColorPreset.ORIGINAL)
                                    Brush.linearGradient(listOf(Color.White, Color.Gray))
                                else
                                    Brush.linearGradient(listOf(preset.tint, preset.tint))
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (preset == DanmakuColorPreset.ORIGINAL) {
                            Text("原", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                        }
                    }
                    Text(preset.label, fontSize = 10.sp, color = if (selected) Theme.Pink else Color.Gray)
                }
            }
        }

        // Speed
        Text("弹幕速度", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((label, speed) in listOf("慢" to 0.5f, "正常" to 1.0f, "快" to 1.5f, "很快" to 2.2f)) {
                FilterChip(
                    selected = settings.speed == speed,
                    onClick = { settings.speed = speed; settings.save() },
                    label = { Text(label, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Theme.Pink,
                        selectedLabelColor = Color.White,
                        containerColor = Color.White.copy(alpha = 0.1f),
                        labelColor = Color.White
                    )
                )
            }
        }

        // Region
        Text("显示区域", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Column {
            CompactSlider(
                value = settings.region,
                onValueChange = { settings.region = it; settings.save() },
                valueRange = 0.25f..1.0f,
                inactiveColor = Color.White.copy(alpha = 0.2f),
            )
            Text("${(settings.region * 100).toInt()}% 屏", color = Color.Gray, fontSize = 12.sp)
        }

        // Opacity
        Text("不透明度", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        CompactSlider(
            value = settings.opacity,
            onValueChange = { settings.opacity = it; settings.save() },
            valueRange = 0.1f..1.0f,
            inactiveColor = Color.White.copy(alpha = 0.2f),
        )
    }
}