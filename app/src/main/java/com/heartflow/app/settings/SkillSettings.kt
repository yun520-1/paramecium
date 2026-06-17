package com.heartflow.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.heartflow.data.*
import kotlinx.coroutines.launch

@Composable
fun SkillSettings(viewModel: ChatViewModel) {
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
        verticalArrangement = Arrangement.spacedBy(12.dp)
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
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = installUrl,
                        onValueChange = { installUrl = it },
                        label = { Text("GitHub URL 或 SKILL.md 直链") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !isInstalling
                    )
                    Spacer(Modifier.height(12.dp))
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
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    if (isLoading) {
                        Text("加载中...", fontSize = 13.sp, color = Color.Gray)
                    } else if (installedSkills.isEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("暂未安装任何技能", fontSize = 13.sp, color = Color.Gray)
                        }
                    } else {
                        installedSkills.forEachIndexed { index, skill ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("📦", fontSize = 18.sp)
                                Spacer(Modifier.width(8.dp))
                                Text(skill, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            }
                            if (index < installedSkills.size - 1) {
                                Divider(modifier = Modifier.padding(vertical = 2.dp))
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
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
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
                            Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("🔧", fontSize = 14.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(tool, fontSize = 13.sp)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "这些工具在对话中会根据需要自动调用，无需手动安装。",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                }
            }
        }

        // 状态消息
        if (message.isNotBlank()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (message.startsWith("✅")) Color(0xFFE8F5E9)
                        else if (message.startsWith("❌")) Color(0xFFFFEBEE)
                        else Color(0xFFE3F2FD)
                    )
                ) {
                    Text(message, modifier = Modifier.padding(16.dp), fontSize = 13.sp)
                }
            }
        }
    }
}
