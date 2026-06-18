package com.heartflow.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.heartflow.app.LocalThemeScheme
import kotlinx.coroutines.launch

@Composable
fun SkillSettings(viewModel: ChatViewModel) {
    val scheme = LocalThemeScheme.current
    var installedSkills by remember { mutableStateOf(listOf<String>()) }
    var isLoading by remember { mutableStateOf(false) }
    var isInstalling by remember { mutableStateOf(false) }
    var installUrl by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    val coroutineScope = rememberCoroutineScope()

    fun refreshInstalledSkills() {
        installedSkills = viewModel.getInstalledSkillNames()
    }

    LaunchedEffect(Unit) {
        isLoading = true
        try {
            refreshInstalledSkills()
        } catch (e: Exception) {
            message = "加载失败: ${e.message}"
        }
        isLoading = false
    }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── 安装新技能 ──
        item {
            SettingsSectionHeader(
                icon = Icons.Default.Add,
                title = "安装新技能",
                subtitle = "输入技能 URL 即可直接安装"
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
                        value = installUrl,
                        onValueChange = { installUrl = it },
                        label = { Text("GitHub URL 或 SKILL.md 直链") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !isInstalling
                    )
                    Button(
                        onClick = {
                            if (installUrl.isNotBlank() && !isInstalling) {
                                val url = installUrl
                                installUrl = ""
                                isInstalling = true
                                message = "正在安装..."
                                coroutineScope.launch {
                                    try {
                                        val result = viewModel.installSkill(url)
                                        message = result
                                        refreshInstalledSkills()
                                    } catch (e: Exception) {
                                        message = "安装失败: ${e.message}"
                                    } finally {
                                        isInstalling = false
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = installUrl.isNotBlank() && !isInstalling
                    ) { Text(if (isInstalling) "安装中..." else "安装技能") }
                }
            }
        }

        // ── 已安装技能 ──
        item {
            SettingsSectionHeader(
                icon = Icons.Default.CheckCircle,
                title = "已安装技能",
                subtitle = "当前已安装 ${installedSkills.size} 个技能"
            )
        }

        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 0.dp,
                color = scheme.surfaceContainerLow
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(16.dp)) {
                    if (isLoading) {
                        Text(
                            "加载中...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    } else if (installedSkills.isEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                "暂未安装任何技能",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    } else {
                        installedSkills.forEachIndexed { index, skill ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("📦", style = MaterialTheme.typography.bodyLarge)
                                Text(skill, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                OutlinedButton(
                                    onClick = {
                                        installedSkills = installedSkills - skill
                                    },
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    ),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                ) {
                                    Text("移除", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            if (index < installedSkills.size - 1) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 2.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── 内置工具 ──
        item {
            SettingsSectionHeader(
                icon = Icons.Default.Build,
                title = "内置工具",
                subtitle = "对话中自动可用，无需手动安装"
            )
        }

        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 0.dp,
                color = scheme.surfaceContainerLow
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(16.dp)) {
                    val tools = listOf(
                        "calculator - 数学计算",
                        "search_web - 搜索网页信息",
                        "fetch_url - 获取网页内容",
                        "current_time - 获取当前时间",
                        "store_memory - 存储记忆",
                        "recall_memory - 回忆记忆",
                        "search_memory - 搜索记忆",
                        "dream - 深度思考",
                        "evolve - 自我优化",
                        "store_knowledge - 存储知识",
                        "search_knowledge - 搜索知识库",
                        "github_repo - GitHub 仓库信息",
                        "github_search - 搜索 GitHub",
                        "code_review - 代码审查",
                        "system_info - 系统信息",
                        "baidu_search - 百度AI搜索",
                        "baidu_baike - 百度百科",
                        "baidu_miaodong - 秒懂百科",
                        "baidu_ai_chat - 百度AI智能问答",
                        "baidu_smart_search - 百度智能搜索"
                    )
                    tools.forEach { tool ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("🔧", style = MaterialTheme.typography.bodyMedium)
                            Text(tool, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "这些工具在对话中会根据需要自动调用，无需手动安装。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
        }

        // 状态消息
        if (message.isNotBlank()) {
            item {
                val msgBackground = when {
                    message.startsWith("✅") -> scheme.primaryContainer
                    message.startsWith("❌") -> scheme.error.copy(alpha = 0.15f)
                    else -> scheme.tertiary.copy(alpha = 0.12f)
                }
                val animatedBg by animateColorAsState(
                    targetValue = msgBackground,
                    label = "msgBg"
                )
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    tonalElevation = 0.dp,
                    color = animatedBg
                ) {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }

        // 底部间距
        item { Spacer(Modifier.height(8.dp)) }
    }
}
