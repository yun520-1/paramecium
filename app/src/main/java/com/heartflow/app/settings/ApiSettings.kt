package com.heartflow.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.heartflow.app.ApiConfig

/**
 * Token 限制选项
 */
enum class TokenLimitOption(val label: String, val value: Int?) {
    AUTO("自动推荐", null),
    TOKENS_128K("128K (131072)", 131072),
    TOKENS_256K("256K (262144)", 262144),
    TOKENS_512K("512K (524288)", 524288),
    TOKENS_1M("1M (1048576)", 1048576)
}

/**
 * API 配置设置页面
 *
 * 提供商下拉选择（ExposedDropdownMenuBox）、API Key 输入（PasswordVisualTransformation）、
 * 基础 URL、模型名称、Temperature 滑块和自动 max_tokens 推荐显示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiSettings(config: ApiConfig?, onSave: (ApiConfig) -> Unit) {
    var provider by remember { mutableStateOf(config?.provider ?: "moonshot") }
    var apiKey by remember { mutableStateOf(config?.apiKey ?: "") }
    var baseUrl by remember { mutableStateOf(config?.baseUrl ?: "") }
    var model by remember { mutableStateOf(config?.model ?: "") }
    var temperature by remember { mutableStateOf(config?.temperature?.toFloat() ?: 0.7f) }
    var expanded by remember { mutableStateOf(false) }
    var tokenLimitExpanded by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf("") }

    // Token 限制选项：null=自动，262144=256K，1048576=1M
    val currentTokenOption = remember(config?.maxTokens) {
        TokenLimitOption.entries.find { it.value == config?.maxTokens } ?: TokenLimitOption.AUTO
    }
    var selectedTokenOption by remember(config?.maxTokens) { mutableStateOf(currentTokenOption) }

    // 根据当前选择模型自动推导的推荐 max_tokens（仅显示用）
    val effectiveModelForTokens = remember(provider, model) {
        val m = model.trim()
        if (m.isNotBlank() && m != "default") m else when (provider) {
            "openai" -> "gpt-3.5-turbo"
            "deepseek" -> "deepseek-chat"
            "qwen" -> "qwen-turbo"
            "zhipu" -> "glm-4-flash"
            "moonshot" -> "moonshot-v1-8k"
            else -> "moonshot-v1-8k"
        }
    }
    val recommendedMaxTokens = remember(effectiveModelForTokens) {
        ApiConfig.getRecommendedMaxTokens(effectiveModelForTokens)
    }
    // 上下文窗口上限（用于显示）
    val contextWindow = remember(effectiveModelForTokens) {
        ApiConfig.getRecommendedContextWindow(effectiveModelForTokens)
    }

    val providers = listOf("moonshot", "deepseek", "openai", "qwen", "zhipu", "custom")
    val providerNames = mapOf(
        "moonshot" to "Moonshot", "deepseek" to "DeepSeek", "openai" to "OpenAI",
        "qwen" to "通义千问", "zhipu" to "智谱GLM", "custom" to "自定义"
    )

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SettingsSectionHeader(
                icon = Icons.Default.Key,
                title = "API 配置",
                subtitle = "配置 AI 模型接口"
            )
        }

        // 提供商选择卡片
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("API 提供商", fontWeight = FontWeight.Bold, fontSize = 14.sp)

                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = it }
                    ) {
                        OutlinedTextField(
                            value = providerNames[provider] ?: provider,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("选择提供商") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                            modifier = Modifier
                                .menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable, enabled = true)
                                .fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            providers.forEach { p ->
                                DropdownMenuItem(
                                    text = { Text(providerNames[p] ?: p) },
                                    onClick = {
                                        provider = p
                                        expanded = false
                                        // 自动填充常用配置
                                        when (p) {
                                            "moonshot" -> { baseUrl = "https://api.moonshot.cn/v1"; model = "moonshot-v1-8k" }
                                            "deepseek" -> { baseUrl = "https://api.deepseek.com/v1"; model = "deepseek-chat" }
                                            "openai" -> { baseUrl = "https://api.openai.com/v1"; model = "gpt-4o-mini" }
                                            "qwen" -> { baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1"; model = "qwen-turbo" }
                                            "zhipu" -> { baseUrl = "https://open.bigmodel.cn/api/paas/v4"; model = "glm-4-flash" }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // 认证信息卡片
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("认证信息", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("API Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        visualTransformation = if (apiKey.length > 8)
                            PasswordVisualTransformation()
                        else
                            VisualTransformation.None
                    )
                }
            }
        }

        // 模型配置卡片
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("模型配置", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text(if (provider == "custom") "API地址（必填）" else "自定义URL（可选）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp)
                    )
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text(if (provider == "custom") "模型名称（必填）" else "模型名称（可选）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp)
                    )
                }
            }
        }

        // 参数调节卡片
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("参数调节", fontWeight = FontWeight.Bold, fontSize = 14.sp)

                    // Temperature
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Temperature", fontSize = 14.sp)
                        Text("%.1f".format(temperature), fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("0.0", fontSize = 12.sp, color = Color.Gray)
                        Slider(
                            value = temperature,
                            onValueChange = { temperature = it },
                            valueRange = 0f..2.0f,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                        )
                        Text("2.0", fontSize = 12.sp, color = Color.Gray)
                    }

                    // Max Tokens（可选择自动或手动设置）
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("最大输出 tokens", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (selectedTokenOption == TokenLimitOption.AUTO) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("推荐 ", fontSize = 12.sp, color = Color.Gray)
                                    Text(
                                        ApiConfig.formatTokens(recommendedMaxTokens),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        " (上下文 ${ApiConfig.formatTokens(contextWindow)})",
                                        fontSize = 11.sp,
                                        color = Color.Gray
                                    )
                                }
                            } else {
                                Text(
                                    ApiConfig.formatTokens(selectedTokenOption.value ?: 8192),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        ExposedDropdownMenuBox(
                            expanded = tokenLimitExpanded,
                            onExpandedChange = { tokenLimitExpanded = it },
                            modifier = Modifier.width(150.dp)
                        ) {
                            OutlinedTextField(
                                value = selectedTokenOption.label,
                                onValueChange = {},
                                readOnly = true,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(tokenLimitExpanded) },
                                modifier = Modifier
                                    .menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable, enabled = true),
                                shape = RoundedCornerShape(8.dp),
                                textStyle = MaterialTheme.typography.bodySmall
                            )
                            ExposedDropdownMenu(
                                expanded = tokenLimitExpanded,
                                onDismissRequest = { tokenLimitExpanded = false }
                            ) {
                                TokenLimitOption.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.label, fontSize = 13.sp) },
                                        onClick = {
                                            selectedTokenOption = option
                                            tokenLimitExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 保存按钮
        item {
            Button(
                onClick = {
                    if (apiKey.isNotBlank()) {
                        onSave(ApiConfig(
                            provider = provider, apiKey = apiKey,
                            baseUrl = baseUrl.ifBlank { null },
                            model = model.ifBlank { "default" },
                            temperature = temperature.toDouble(),
                            maxTokens = selectedTokenOption.value
                        ))
                        saveMessage = "✅ 配置已保存"
                    } else {
                        saveMessage = "⚠️ API Key 不能为空"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Save, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("保存配置")
            }
            if (saveMessage.isNotBlank()) {
                Text(
                    saveMessage,
                    fontSize = 13.sp,
                    color = if (saveMessage.startsWith("✅")) Color(0xFF4CAF50) else Color(0xFFFF6B6B),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}
