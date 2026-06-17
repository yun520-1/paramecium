package com.heartflow.app

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
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
        items(Personalities.all) { p ->
            val selected = p.id == current.id
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(p) },
                shape = RoundedCornerShape(14.dp),
                elevation = CardDefaults.cardElevation(
                    defaultElevation = if (selected) 4.dp else 1.dp
                ),
                colors = CardDefaults.cardColors(
                    containerColor = if (selected)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surface
                )
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    // 表情符号头像
                    Surface(
                        shape = CircleShape,
                        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                               else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            Text(p.emoji, fontSize = 24.sp)
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(p.description, fontSize = 12.sp, color = Color.Gray)
                        Spacer(Modifier.height(5.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            p.traits.forEach { trait ->
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                                           else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                ) {
                                    Text(
                                        trait,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                        fontSize = 11.sp,
                                        color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                                               else Color.Gray
                                    )
                                }
                            }
                        }
                    }
                    if (selected) {
                        Icon(
                            Icons.Default.CheckCircle,
                            "已选",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    } else {
                        Icon(
                            Icons.Default.ChevronRight,
                            null,
                            tint = Color.Gray.copy(alpha = 0.4f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}
