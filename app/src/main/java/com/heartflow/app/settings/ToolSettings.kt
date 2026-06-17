package com.heartflow.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.heartflow.data.*

@Composable
fun ToolSettings(viewModel: ChatViewModel) {
    var enableTools by remember { mutableStateOf(viewModel.getEnableTools()) }
    var maxToolLoops by remember { mutableStateOf(viewModel.getMaxToolLoops().toString()) }
    var toolTimeout by remember { mutableStateOf((viewModel.getToolTimeoutMs() / 1000).toString()) }
    // 从持久化状态初始化每个工具的开关（默认启用）
    val disabledTools = remember { mutableStateOf(viewModel.getDisabledTools()) }
    var savedMessage by remember { mutableStateOf("") }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── 参数设置 ──
        item {
            SettingsSectionHeader(
                icon = Icons.Default.Tune,
                title = "参数设置",
                subtitle = "控制工具调用的基本行为参数"
            )
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    // 总开关
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("启用工具调用", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("启用后，心虫可以使用内置工具完成各种任务", fontSize = 12.sp, color = Color.Gray)
                        }
                        Switch(checked = enableTools, onCheckedChange = {
                            enableTools = it
                            viewModel.setEnableTools(it)
                            savedMessage = "已${if (it) "启用" else "禁用"}工具调用"
                        })
                    }

                    Spacer(Modifier.height(12.dp))
                    Divider()
                    Spacer(Modifier.height(12.dp))

                    // 最大循环次数
                    OutlinedTextField(
                        value = maxToolLoops,
                        onValueChange = { maxToolLoops = it.filter { c -> c.isDigit() } },
                        label = { Text("最大工具循环次数") },
                        supportingText = { Text("默认 3，限制工具调用链长度") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )

                    Spacer(Modifier.height(8.dp))

                    // 超时时间
                    OutlinedTextField(
                        value = toolTimeout,
                        onValueChange = { toolTimeout = it.filter { c -> c.isDigit() } },
                        label = { Text("单次工具超时（秒）") },
                        supportingText = { Text("默认 15 秒") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )

                    Spacer(Modifier.height(12.dp))

                    Button(
                        onClick = {
                            viewModel.setEnableTools(enableTools)
                            val loops = maxToolLoops.toIntOrNull()
                            if (loops != null && loops > 0) viewModel.setMaxToolLoops(loops)
                            val timeout = toolTimeout.toLongOrNull()
                            if (timeout != null && timeout > 0) viewModel.setToolTimeoutMs(timeout * 1000)
                            savedMessage = "✅ 工具设置已保存"
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("保存工具设置") }

                    if (savedMessage.isNotBlank()) {
                        Text(
                            savedMessage,
                            fontSize = 12.sp,
                            color = if (savedMessage.startsWith("✅")) Color(0xFF4CAF50) else Color.Gray,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }

        // ── 工具管理 ──
        item {
            SettingsSectionHeader(
                icon = Icons.Default.Build,
                title = "工具管理",
                subtitle = "点击开关单独启用/禁用各内置工具"
            )
        }

        val categories = ToolRegistry.getAll().groupBy { it.category }
        categories.forEach { (category, tools) ->
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("📁 $category", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))
                        tools.forEachIndexed { idx, tool ->
                            val isEnabled = tool.name !in disabledTools.value
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(tool.name, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text(tool.description, fontSize = 11.sp, color = Color.Gray)
                                }
                                Switch(
                                    checked = isEnabled,
                                    onCheckedChange = { checked ->
                                        if (checked) {
                                            disabledTools.value = disabledTools.value - tool.name
                                        } else {
                                            disabledTools.value = disabledTools.value + tool.name
                                        }
                                        viewModel.setToolEnabled(tool.name, checked)
                                        savedMessage = "已${if (checked) "启用" else "禁用"}「${tool.name}」"
                                    }
                                )
                            }
                            if (idx < tools.size - 1) {
                                Divider(modifier = Modifier.padding(vertical = 4.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
