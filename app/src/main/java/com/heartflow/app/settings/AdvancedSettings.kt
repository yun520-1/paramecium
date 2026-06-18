package com.heartflow.app

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.heartflow.app.LocalThemeScheme

@Composable
fun AdvancedSettings(viewModel: ChatViewModel) {
    val scheme = LocalThemeScheme.current
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val memoryStats = viewModel.memorySystem.getStats()

    var customPrompt by remember { mutableStateOf("") }
    var promptSaved by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // 从 prefs 加载当前提示词
    }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── 字体设置 ──
        item {
            SettingsSectionHeader(
                icon = Icons.Default.TextFields,
                title = "字体设置",
                subtitle = "调整聊天界面的字体大小"
            )
        }

        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 0.dp,
                color = scheme.surfaceContainerLow
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "A",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                        Slider(
                            value = uiState.fontSize,
                            onValueChange = { viewModel.setFontSize(it) },
                            valueRange = 10f..24f,
                            steps = 13,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "A",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                    Text(
                        "当前: ${"%.0f".format(uiState.fontSize)}sp",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
        }

        // ── 自定义提示词 ──
        item {
            SettingsSectionHeader(
                icon = Icons.Default.Edit,
                title = "自定义提示词",
                subtitle = "附加到系统提示词末尾，影响 AI 行为"
            )
        }

        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 0.dp,
                color = scheme.surfaceContainerLow
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = customPrompt,
                        onValueChange = { customPrompt = it; promptSaved = false },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 6,
                        placeholder = { Text("例如：请使用简洁的语言回答...") }
                    )
                    Button(
                        onClick = {
                            viewModel.saveCustomPrompt(customPrompt)
                            promptSaved = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("保存提示词") }
                    if (promptSaved) {
                        Text(
                            "✓ 已保存",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.primary,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }

        // ── 浏览器设置 ──
        item {
            SettingsSectionHeader(
                icon = Icons.Default.Public,
                title = "浏览器设置",
                subtitle = "设置内置浏览器的默认主页"
            )
        }

        item {
            var browserHomeUrl by remember { mutableStateOf(viewModel.getBrowserHomeUrl()) }
            var homeUrlSaved by remember { mutableStateOf(false) }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 0.dp,
                color = scheme.surfaceContainerLow
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = browserHomeUrl,
                        onValueChange = { browserHomeUrl = it; homeUrlSaved = false },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 2,
                        singleLine = true,
                        placeholder = { Text("例如: https://www.baidu.com") },
                        leadingIcon = { Icon(Icons.Default.Home, null, modifier = Modifier.size(18.dp)) }
                    )
                    Button(
                        onClick = {
                            viewModel.setBrowserHomeUrl(browserHomeUrl)
                            homeUrlSaved = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("保存主页") }
                    if (homeUrlSaved) {
                        Text(
                            "✓ 已保存",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.primary,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    Text(
                        "修改后需要重新打开浏览器生效",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
        }

        // ── 数据管理 ──
        item {
            SettingsSectionHeader(
                icon = Icons.Default.Storage,
                title = "数据管理",
                subtitle = "查看和管理三层记忆系统中的数据"
            )
        }

        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 0.dp,
                color = scheme.surfaceContainerLow
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(16.dp)) {
                    // 记忆统计标题
                    Text("三层记忆系统", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)

                    // 统计行
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        tonalElevation = 0.dp,
                        color = scheme.glassSurface
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(12.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("工作记忆", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${memoryStats.workingCount} 条",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("情景记忆", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${memoryStats.episodicCount} 条",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("核心记忆", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${memoryStats.coreCount} 条",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }

                    Text(
                        "总计: ${memoryStats.totalMemories} 条记忆",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    // 操作按钮
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { viewModel.clearWorkingMemory() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) { Text("清除工作记忆") }
                        Button(
                            onClick = { showClearConfirm = true },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) { Text("清除全部记忆") }
                    }

                    OutlinedButton(
                        onClick = { viewModel.setPage("memory") },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("逐条管理记忆") }

                    // 导出按钮
                    OutlinedButton(
                        onClick = {
                            try {
                                val json = viewModel.exportConversations()
                                Toast.makeText(
                                    viewModel.getApplication(),
                                    "对话已导出 (${json.length} 字符)",
                                    Toast.LENGTH_LONG
                                ).show()
                            } catch (e: Exception) {
                                Toast.makeText(
                                    viewModel.getApplication(),
                                    "导出失败: ${e.message}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("导出对话历史（JSON）")
                    }
                }
            }
        }

        // ── 关于 ──
        item {
            SettingsSectionHeader(
                icon = Icons.Default.Info,
                title = "关于",
                subtitle = "心虫引擎版本信息"
            )
        }

        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 0.dp,
                color = scheme.surfaceContainerLow
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    val versionName = remember {
                        try {
                            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "未知"
                        } catch (e: Exception) {
                            "未知"
                        }
                    }

                    // 版本号卡片
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        tonalElevation = 0.dp,
                        color = scheme.glassSurface
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                tint = scheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("心虫", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                Text("版本: $versionName", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                            }
                        }
                    }

                    Text(
                        "心虫 - 一个会思考自己怎么活的 AI",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Text(
                        "三层记忆: 工作记忆 / 情景记忆 / 核心记忆",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    Text(
                        "一个能感知自己存在、能判断对错、能从错误中升级的完整认知引擎。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
        }

        // 底部间距
        item { Spacer(Modifier.height(8.dp)) }
    }

    // 清除确认对话框
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            icon = {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = { Text("确认清除", fontWeight = FontWeight.Bold) },
            text = { Text("确定要清除所有记忆吗？此操作不可撤销。") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearMemory()
                        showClearConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("确认清除") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showClearConfirm = false }) { Text("取消") }
            },
            shape = RoundedCornerShape(16.dp),
            containerColor = scheme.surfaceContainerLow
        )
    }
}
