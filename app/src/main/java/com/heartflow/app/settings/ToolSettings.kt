package com.heartflow.app

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.heartflow.app.ChatViewModel
import com.heartflow.data.ToolRegistry

/**
 * 工具调用设置页面
 *
 * 包含工具调用的全局开关、参数设置以及各工具的独立启用/禁用。
 */
@Composable
fun ToolSettings(viewModel: ChatViewModel) {
    val scheme = LocalThemeScheme.current
    var enableTools by remember { mutableStateOf(viewModel.getEnableTools()) }
    var maxToolLoops by remember { mutableStateOf(viewModel.getMaxToolLoops().toString()) }
    var toolTimeout by remember { mutableStateOf((viewModel.getToolTimeoutMs() / 1000).toString()) }
    // 从持久化状态初始化每个工具的开关（默认启用）
    val disabledTools = remember { mutableStateOf(viewModel.getDisabledTools()) }
    var savedMessage by remember { mutableStateOf("") }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
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
            Surface(
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 0.dp,
                color = scheme.surfaceContainerLow
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 总开关
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "启用工具调用",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "启用后，心虫可以使用内置工具完成各种任务",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                        Switch(
                            checked = enableTools,
                            onCheckedChange = {
                                enableTools = it
                                viewModel.setEnableTools(it)
                                savedMessage = "已${if (it) "启用" else "禁用"}工具调用"
                            }
                        )
                    }

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                    )

                    // 最大循环次数
                    OutlinedTextField(
                        value = maxToolLoops,
                        onValueChange = { maxToolLoops = it.filter { c -> c.isDigit() } },
                        label = { Text("最大工具循环次数") },
                        supportingText = { Text("默认 3，限制工具调用链长度") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )

                    // 超时时间
                    OutlinedTextField(
                        value = toolTimeout,
                        onValueChange = { toolTimeout = it.filter { c -> c.isDigit() } },
                        label = { Text("单次工具超时（秒）") },
                        supportingText = { Text("默认 15 秒") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )

                    // 保存按钮
                    Button(
                        onClick = {
                            viewModel.setEnableTools(enableTools)
                            val loops = maxToolLoops.toIntOrNull()
                            if (loops != null && loops > 0) viewModel.setMaxToolLoops(loops)
                            val timeout = toolTimeout.toLongOrNull()
                            if (timeout != null && timeout > 0) viewModel.setToolTimeoutMs(timeout * 1000)
                            savedMessage = "✅ 工具设置已保存"
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(
                            Icons.Default.Save,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("保存工具设置")
                    }

                    if (savedMessage.isNotBlank()) {
                        Text(
                            savedMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (savedMessage.startsWith("✅"))
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = 4.dp)
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
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    tonalElevation = 0.dp,
                    color = scheme.surfaceContainerLow
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // 分类标题
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Build,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                category,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        tools.forEachIndexed { idx, tool ->
                            val isEnabled = tool.name !in disabledTools.value

                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                tonalElevation = 0.dp,
                                color = if (isEnabled)
                                    scheme.glassSurface
                                else scheme.surfaceContainerLow
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            tool.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            color = if (isEnabled)
                                                MaterialTheme.colorScheme.onSurface
                                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                        )
                                        Text(
                                            tool.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                            maxLines = 1
                                        )
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
                            }

                            if (idx < tools.size - 1) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 4.dp),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
