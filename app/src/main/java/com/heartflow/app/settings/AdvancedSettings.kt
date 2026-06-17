package com.heartflow.app

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.heartflow.data.*

@Composable
fun AdvancedSettings(viewModel: ChatViewModel) {
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
        verticalArrangement = Arrangement.spacedBy(12.dp)
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
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("A", fontSize = 10.sp, color = Color.Gray)
                        Slider(
                            value = uiState.fontSize,
                            onValueChange = { viewModel.setFontSize(it) },
                            valueRange = 10f..24f,
                            steps = 13,
                            modifier = Modifier.weight(1f)
                        )
                        Text("A", fontSize = 18.sp, color = Color.Gray)
                    }
                    Text(
                        "当前: ${"%.0f".format(uiState.fontSize)}sp",
                        fontSize = 12.sp,
                        color = Color.Gray
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
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = customPrompt,
                        onValueChange = { customPrompt = it; promptSaved = false },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 6,
                        placeholder = { Text("例如：请使用简洁的语言回答...") }
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            viewModel.saveCustomPrompt(customPrompt)
                            promptSaved = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("保存提示词") }
                    if (promptSaved) {
                        Text("✓ 已保存", color = Color(0xFF4CAF50), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                    }
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
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("三层记忆系统", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("工作记忆", fontSize = 13.sp)
                        Text("${memoryStats.workingCount} 条", fontSize = 13.sp, color = Color.Gray)
                    }
                    Divider(modifier = Modifier.padding(vertical = 4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("情景记忆", fontSize = 13.sp)
                        Text("${memoryStats.episodicCount} 条", fontSize = 13.sp, color = Color.Gray)
                    }
                    Divider(modifier = Modifier.padding(vertical = 4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("核心记忆", fontSize = 13.sp)
                        Text("${memoryStats.coreCount} 条", fontSize = 13.sp, color = Color.Gray)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("总计: ${memoryStats.totalMemories} 条记忆", fontSize = 12.sp, color = Color.Gray)

                    Spacer(Modifier.height(12.dp))
                    Divider()
                    Spacer(Modifier.height(12.dp))

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { viewModel.clearWorkingMemory() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF6B6B))
                        ) { Text("清除工作记忆") }
                        Button(
                            onClick = { showClearConfirm = true },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF5350))
                        ) { Text("清除全部记忆") }
                    }

                    Spacer(Modifier.height(8.dp))

                    // 逐条管理按钮
                    OutlinedButton(
                        onClick = { viewModel.setPage("memory") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                    ) { Text("逐条管理记忆") }

                    Spacer(Modifier.height(8.dp))

                    Button(
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
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) { Text("导出对话历史（JSON）") }
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
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    val versionName = remember {
                        try {
                            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "未知"
                        } catch (e: Exception) {
                            "未知"
                        }
                    }
                    Text("版本: $versionName", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text("心虫 - 一个会思考自己怎么活的 AI", fontSize = 13.sp, color = Color.Gray)
                    Spacer(Modifier.height(4.dp))
                    Text("三层记忆: 工作记忆 / 情景记忆 / 核心记忆", fontSize = 13.sp, color = Color.Gray)
                    Spacer(Modifier.height(4.dp))
                    Text("一个能感知自己存在、能判断对错、能从错误中升级的完整认知引擎。", fontSize = 13.sp, color = Color.Gray)
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
            title = { Text("确认清除") },
            text = { Text("确定要清除所有记忆吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearMemory()
                    showClearConfirm = false
                }) { Text("确认清除", color = Color(0xFFEF5350)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            }
        )
    }
}
