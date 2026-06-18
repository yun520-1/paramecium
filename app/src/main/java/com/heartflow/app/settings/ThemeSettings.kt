package com.heartflow.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.heartflow.data.Language
import com.heartflow.data.ThemeMode
import com.heartflow.data.ThemeVariant

/**
 * 主题与显示设置页面
 *
 * 包含显示模式（浅色/深色/跟随系统）、主题配色（5种配色方案）、
 * 字体大小滑块和语言选择器。
 */
@Composable
fun ThemeSettings(
    current: ThemeMode,
    onSetTheme: (ThemeMode) -> Unit,
    currentVariant: ThemeVariant,
    onSetVariant: (ThemeVariant) -> Unit,
    fontSize: Float,
    onSetFontSize: (Float) -> Unit
) {
    // 语言设置（暂存状态，未持久化）
    var selectedLang by remember { mutableStateOf(Language.CHINESE) }
    val scheme = LocalThemeScheme.current

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SettingsSectionHeader(
                icon = Icons.Default.Palette,
                title = "主题与显示",
                subtitle = "自定义你的视觉体验"
            )
        }

        // 显示模式卡片
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("显示模式", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    ThemeMode.entries.forEach { mode ->
                        val isSelected = mode == current
                        val bgColor by animateColorAsState(
                            targetValue = if (isSelected) scheme.primaryContainer else scheme.surface,
                            animationSpec = tween(durationMillis = 300),
                            label = "themeBg${mode.name}"
                        )
                        val iconTint by animateColorAsState(
                            targetValue = if (isSelected) scheme.primary else scheme.onSurfaceVariant,
                            animationSpec = tween(durationMillis = 300),
                            label = "themeIcon${mode.name}"
                        )
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSetTheme(mode) },
                            shape = RoundedCornerShape(12.dp),
                            color = bgColor,
                            tonalElevation = 0.dp
                        ) {
                            Row(
                                Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    when (mode) {
                                        ThemeMode.LIGHT -> Icons.Default.LightMode
                                        ThemeMode.DARK -> Icons.Default.DarkMode
                                        ThemeMode.SYSTEM -> Icons.Default.PhoneAndroid
                                    },
                                    null,
                                    tint = iconTint
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    when (mode) {
                                        ThemeMode.LIGHT -> "浅色模式"
                                        ThemeMode.DARK -> "深色模式"
                                        ThemeMode.SYSTEM -> "跟随系统"
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                                if (isSelected) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        null,
                                        tint = scheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 主题配色卡片
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("主题配色", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    // 使用 FlowRow 风格的多行 FilterChip
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ThemeVariant.entries.take(3).forEach { variant ->
                            FilterChip(
                                selected = variant == currentVariant,
                                onClick = { onSetVariant(variant) },
                                label = { Text(variant.displayName, fontSize = 12.sp) },
                                shape = RoundedCornerShape(8.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = scheme.surface,
                                    selectedContainerColor = scheme.primaryContainer,
                                    labelColor = scheme.onSurfaceVariant,
                                    selectedLabelColor = scheme.onPrimaryContainer
                                )
                            )
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ThemeVariant.entries.drop(3).forEach { variant ->
                            FilterChip(
                                selected = variant == currentVariant,
                                onClick = { onSetVariant(variant) },
                                label = { Text(variant.displayName, fontSize = 12.sp) },
                                shape = RoundedCornerShape(8.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = scheme.surface,
                                    selectedContainerColor = scheme.primaryContainer,
                                    labelColor = scheme.onSurfaceVariant,
                                    selectedLabelColor = scheme.onPrimaryContainer
                                )
                            )
                        }
                    }
                    // 配色预览色块
                    Spacer(Modifier.height(4.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val previewColors = mapOf(
                            ThemeVariant.AURORA_PURPLE to Color(0xFF6B4C9A),
                            ThemeVariant.OCEAN_BLUE to Color(0xFF2E7D8F),
                            ThemeVariant.FOREST_GREEN to Color(0xFF3E8E41),
                            ThemeVariant.SUNSET_ORANGE to Color(0xFFD4764E),
                            ThemeVariant.DARK_NIGHT to Color(0xFFBB86FC)
                        )
                        ThemeVariant.entries.forEach { variant ->
                            val isSelected = variant == currentVariant
                            val borderColor by animateColorAsState(
                                targetValue = if (isSelected) scheme.onSurface else Color.Transparent,
                                animationSpec = tween(durationMillis = 300),
                                label = "preview${variant.name}"
                            )
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(previewColors[variant] ?: scheme.error)
                                    .border(2.dp, borderColor, CircleShape)
                            )
                        }
                    }
                }
            }
        }

        // 字体大小卡片
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("字体大小", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("${fontSize.toInt()}sp", fontSize = 14.sp, color = scheme.primary)
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("A", fontSize = 12.sp, color = scheme.onSurfaceVariant)
                        Slider(
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                            value = fontSize,
                            onValueChange = onSetFontSize,
                            valueRange = 12f..22f,
                            steps = 9,
                            colors = SliderDefaults.colors(
                                thumbColor = scheme.primary,
                                activeTrackColor = scheme.primary,
                                inactiveTrackColor = scheme.surfaceVariant,
                                activeTickColor = scheme.onPrimary,
                                inactiveTickColor = scheme.onSurfaceVariant
                            )
                        )
                        Text("A", fontSize = 18.sp, color = scheme.onSurfaceVariant)
                    }
                }
            }
        }

        // 语言选择卡片
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("语言", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Language.entries.forEach { lang ->
                            FilterChip(
                                selected = lang == selectedLang,
                                onClick = { selectedLang = lang },
                                label = { Text(lang.displayName) },
                                shape = RoundedCornerShape(8.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = scheme.surface,
                                    selectedContainerColor = scheme.primaryContainer,
                                    labelColor = scheme.onSurfaceVariant,
                                    selectedLabelColor = scheme.onPrimaryContainer
                                )
                            )
                        }
                    }
                    Text(
                        "当前选择: ${selectedLang.displayName}",
                        fontSize = 12.sp,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}
