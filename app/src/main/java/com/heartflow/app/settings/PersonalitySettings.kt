package com.heartflow.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.heartflow.data.Personalities
import com.heartflow.data.Personality

/**
 * 性格选择设置页面
 *
 * 显示所有可用性格的卡片列表，包含表情头像、名称、描述和特质标签。
 * 选中状态以高亮卡片和勾选图标表示。
 */
@Composable
fun PersonalitySettings(current: Personality, onSelect: (Personality) -> Unit) {
    val scheme = LocalThemeScheme.current

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            SettingsSectionHeader(
                icon = Icons.Default.FavoriteBorder,
                title = "性格选择",
                subtitle = "每个性格有独特的回应风格和温度"
            )
            Spacer(Modifier.height(4.dp))
        }
        items(Personalities.all, key = { it.id }) { p ->
            val selected = p.id == current.id
            val containerColor by animateColorAsState(
                targetValue = if (selected)
                    MaterialTheme.colorScheme.primaryContainer
                else scheme.surfaceContainerLow,
                label = "personalityCardColor"
            )

            Surface(
                onClick = { onSelect(p) },
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 0.dp,
                color = containerColor
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 表情符号头像
                    Surface(
                        shape = RoundedCornerShape(50),
                        tonalElevation = 0.dp,
                        color = if (selected)
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        else scheme.surfaceContainerLow,
                        modifier = Modifier.size(52.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(p.emoji, fontSize = 24.sp)
                        }
                    }

                    Spacer(Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            p.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            p.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            maxLines = 2
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            p.traits.forEach { trait ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    tonalElevation = 0.dp,
                                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                                ) {
                                    Text(
                                        trait,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }
                        }
                    }

                    if (selected) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "已选",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    } else {
                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}
