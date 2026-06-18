package com.heartflow.app

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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.heartflow.app.ApiConfig

/**
 * Token 限制选项
 */
enum class TokenLimitOption(val label: String, val value: Int) {
    TOKEN_4K("4K", 4096),
    TOKEN_8K("8K", 8192),
    TOKEN_16K("16K", 16384),
    TOKEN_32K("32K", 32768)
}

/**
 * API 配置设置页面
 *
 * 提供商下拉选择（ExposedDropdownMenuBox）、API Key 输入（PasswordVisualTransformation）、
 * 基础 URL、模型名称、Temperature 滑块和 Token 限制下拉选择。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiSettings(config: ApiConfig?, onSave: (ApiConfig) -> Unit) {
    val scheme = LocalThemeScheme.current
    var provider by remember { mutableStateOf(config?.provider ?: "moonshot") }
    var apiKey by remember { mutableStateOf(config?.apiKey ?: "") }
    var baseUrl by remember { mutableStateOf(config?.baseUrl ?: "") }
    var model by remember { mutableStateOf(config?.model ?: "") }
    var temperature by remember { mutableStateOf(config?.temperature?.toFloat() ?: 0.7f) }
    var expanded by remember { mutableStateOf(false) }
    var tokenLimitExpanded by remember { mutableStateOf(false) }
    var showApiKey by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf("") }
    var testMessage by remember { mutableStateOf("") }

    // Token 限制选项
    var selectedTokenOption by remember(config?.maxTokens) {
        mutableStateOf(
            TokenLimitOption.entries.find { it.value == config?.maxTokens } ?: TokenLimitOption.TOKEN_8K
        )
    }

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
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ── 页面头部 ──
        item {
            SettingsSectionHeader(
                icon = Icons.Default.Key,
                title = "API 配置",
                subtitle = "配置 AI 模型接口"
            )
        }

        // ── 提供商选择卡片 ──
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
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "API 提供商",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

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
                            shape = RoundedCornerShape(12.dp)
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

        // ── 认证信息卡片 ──
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
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Key,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "认证信息",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("API Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        visualTransformation = if (showApiKey) VisualTransformation.None
                            else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showApiKey = !showApiKey }) {
                                Icon(
                                    if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (showApiKey) "隐藏" else "显示",
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            }
                        }
                    )
                }
            }
        }

        // ── 模型配置卡片 ──
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
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "模型配置",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text(if (provider == "custom") "API地址（必填）" else "自定义URL（可选）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text(if (provider == "custom") "模型名称（必填）" else "模型名称（可选）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }
        }

        // ── 参数调节卡片 ──
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
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "参数调节",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // Temperature 滑块
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Temperature",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        Text(
                            "%.1f".format(temperature),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = temperature,
                        onValueChange = { temperature = it },
                        valueRange = 0f..2.0f,
                        modifier = Modifier.fillMaxWidth()
                    )

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                    )

                    // Max Tokens 选择
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                "最大输出 Tokens",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                            Text(
                                "推荐 ${ApiConfig.formatTokens(recommendedMaxTokens)} (上下文 ${ApiConfig.formatTokens(contextWindow)})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                            )
                        }
                        ExposedDropdownMenuBox(
                            expanded = tokenLimitExpanded,
                            onExpandedChange = { tokenLimitExpanded = it },
                            modifier = Modifier.width(130.dp)
                        ) {
                            OutlinedTextField(
                                value = selectedTokenOption.label,
                                onValueChange = {},
                                readOnly = true,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(tokenLimitExpanded) },
                                modifier = Modifier
                                    .menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable, enabled = true),
                                shape = RoundedCornerShape(10.dp),
                                textStyle = MaterialTheme.typography.bodySmall
                            )
                            ExposedDropdownMenu(
                                expanded = tokenLimitExpanded,
                                onDismissRequest = { tokenLimitExpanded = false }
                            ) {
                                TokenLimitOption.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.label) },
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

        // ── 操作按钮 ──
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // 测试连接按钮
                OutlinedButton(
                    onClick = {
                        testMessage = if (apiKey.isNotBlank()) {
                            "🔍 正在测试连接 ${providerNames[provider] ?: provider}..."
                        } else {
                            "⚠️ 请先输入 API Key"
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("测试连接")
                }

                if (testMessage.isNotBlank()) {
                    Text(
                        testMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (testMessage.startsWith("⚠️"))
                            MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }

                // 保存按钮
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
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(
                        Icons.Default.Save,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("保存配置")
                }

                if (saveMessage.isNotBlank()) {
                    Text(
                        saveMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (saveMessage.startsWith("✅"))
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        }
    }
}
