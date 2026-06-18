package com.heartflow.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.heartflow.data.*

// ─── 设置项搜索 ──────────────────────────────────────

data class SearchableSetting(val tabIndex: Int, val title: String, val keywords: List<String>)

@Composable
fun SettingsSearchResults(query: String, tabNames: List<String>, tabIcons: List<ImageVector>, onSelectTab: (Int) -> Unit) {
    val scheme = LocalThemeScheme.current
    val allSettings = remember {
        listOf(
            SearchableSetting(0, "性格选择", listOf("性格", "个性", "人格", "personality")),
            SearchableSetting(0, "AI 性格预览", listOf("预览", "性格", "测试")),
            SearchableSetting(1, "个人画像", listOf("画像", "profile", "信息")),
            SearchableSetting(1, "姓名设置", listOf("姓名", "名字", "name")),
            SearchableSetting(1, "昵称设置", listOf("昵称", "称呼", "nickname")),
            SearchableSetting(1, "简介设置", listOf("简介", "bio", "描述")),
            SearchableSetting(1, "兴趣设置", listOf("兴趣", "爱好", "interests")),
            SearchableSetting(2, "显示模式", listOf("主题", "theme", "浅色", "深色", "dark", "light", "显示")),
            SearchableSetting(2, "主题配色", listOf("配色", "颜色", "color", "variant", "主题")),
            SearchableSetting(2, "字体大小", listOf("字体", "字号", "font", "size", "文字")),
            SearchableSetting(2, "语言设置", listOf("语言", "language", "中文", "英文")),
            SearchableSetting(3, "API 提供商", listOf("api", "provider", "提供商", "接口")),
            SearchableSetting(3, "API Key", listOf("api key", "密钥", "key", "认证")),
            SearchableSetting(3, "模型名称", listOf("模型", "model", "gpt")),
            SearchableSetting(3, "Temperature", listOf("temperature", "温度", "随机")),
            SearchableSetting(3, "Max Tokens", listOf("tokens", "token", "长度")),
            SearchableSetting(4, "工具总开关", listOf("工具", "tool", "启用")),
            SearchableSetting(4, "工具循环次数", listOf("循环", "loop", "工具")),
            SearchableSetting(4, "工具超时", listOf("超时", "timeout", "工具")),
            SearchableSetting(4, "内置工具开关", listOf("工具开关", "内置工具", "启用工具")),
            SearchableSetting(5, "技能管理", listOf("技能", "skill", "安装")),
            SearchableSetting(5, "内置工具列表", listOf("内置工具", "工具列表")),
            SearchableSetting(6, "媒体设置", listOf("媒体", "media", "图片")),
            SearchableSetting(6, "语音识别引擎", listOf("语音", "stt", "识别")),
            SearchableSetting(6, "媒体质量", listOf("质量", "压缩", "原图")),
            SearchableSetting(6, "自动下载媒体", listOf("下载", "auto", "媒体")),
            SearchableSetting(7, "字体大小 (高级)", listOf("字体", "字号", "size")),
            SearchableSetting(7, "自定义提示词", listOf("提示词", "prompt", "system")),
            SearchableSetting(7, "记忆统计", listOf("记忆", "memory", "统计")),
            SearchableSetting(7, "清除记忆", listOf("清除", "记忆", "删除")),
            SearchableSetting(7, "导出对话", listOf("导出", "export", "对话")),
            SearchableSetting(7, "关于心虫", listOf("关于", "version", "版本"))
        )
    }

    val q = query.lowercase()
    val results = allSettings.filter { s ->
        s.title.lowercase().contains(q) || s.keywords.any { it.lowercase().contains(q) }
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text("搜索「$query」共 ${results.size} 个结果", fontSize = 13.sp, color = scheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
        }
        if (results.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow)
                ) {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.SearchOff, null, modifier = Modifier.size(48.dp), tint = scheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Text("未找到匹配的设置项", color = scheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            items(results) { item ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectTab(item.tabIndex) },
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow)
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            tabIcons[item.tabIndex],
                            null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.title, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                            Text("位于「${tabNames[item.tabIndex]}」设置", fontSize = 11.sp, color = scheme.onSurfaceVariant)
                        }
                        Icon(Icons.Default.ChevronRight, null, modifier = Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// ─── 统一设置区块头部 ────────────────────────────────

@Composable
fun SettingsSectionHeader(
    icon: ImageVector,
    title: String,
    subtitle: String = "",
    modifier: Modifier = Modifier
) {
    val scheme = LocalThemeScheme.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            if (subtitle.isNotBlank()) {
                Text(subtitle, fontSize = 12.sp, color = scheme.onSurfaceVariant)
            }
        }
    }
}

// ─── 设置页面 ──────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(viewModel: ChatViewModel, uiState: ChatUiState) {
    val scheme = LocalThemeScheme.current
    var selectedTab by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }

    val tabs = listOf("性格", "画像", "主题", "API", "工具", "技能", "媒体", "高级")
    val tabIcons = listOf(
        Icons.Default.FavoriteBorder,
        Icons.Default.Person,
        Icons.Default.Palette,
        Icons.Default.Key,
        Icons.Default.Build,
        Icons.Default.Extension,
        Icons.Default.PhotoLibrary,
        Icons.Default.Tune
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = scheme.glassSurface,
                    titleContentColor = scheme.onSurface
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = scheme.glassSurface
            ) {
                NavigationBarItem(icon = { Icon(Icons.AutoMirrored.Filled.Chat, "聊天") }, label = { Text("聊天") }, selected = false, onClick = { viewModel.setPage("chat") })
                NavigationBarItem(icon = { Icon(Icons.Default.Scanner, "扫描") }, label = { Text("扫描") }, selected = false, onClick = { viewModel.setPage("scanner") })
                NavigationBarItem(icon = { Icon(Icons.Default.History, "历史") }, label = { Text("历史") }, selected = false, onClick = { viewModel.setPage("history") })
                NavigationBarItem(icon = { Icon(Icons.Default.Public, "浏览器") }, label = { Text("浏览器") }, selected = false, onClick = { viewModel.setPage("browser") })
                NavigationBarItem(icon = { Icon(Icons.Default.Settings, "设置") }, label = { Text("设置") }, selected = true, onClick = { viewModel.setPage("settings") })
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding)) {
            // 设置搜索栏
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                placeholder = { Text("搜索设置...", fontSize = 14.sp) },
                leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(20.dp)) },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, "清除", modifier = Modifier.size(18.dp))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = scheme.outline.copy(alpha = 0.3f),
                    focusedBorderColor = scheme.primary.copy(alpha = 0.5f),
                    focusedContainerColor = scheme.surfaceContainerLow,
                    unfocusedContainerColor = scheme.surfaceContainerLow
                )
            )

            // Tab 导航栏
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                edgePadding = 8.dp,
                divider = {}
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title, fontSize = 13.sp) },
                        icon = { Icon(tabIcons[index], title, modifier = Modifier.size(18.dp)) }
                    )
                }
            }

            if (searchQuery.isBlank()) {
                when (selectedTab) {
                    0 -> PersonalitySettings(uiState.personality, viewModel::setPersonality)
                    1 -> ProfileSettings(uiState.userProfile, viewModel::updateProfile)
                    2 -> ThemeSettings(uiState.themeMode, viewModel::setTheme, uiState.themeVariant, viewModel::setThemeVariant, uiState.fontSize, viewModel::setFontSize)
                    3 -> ApiSettings(uiState.config, viewModel::updateConfig)
                    4 -> ToolSettings(viewModel)
                    5 -> SkillSettings(viewModel)
                    6 -> MediaSettings(viewModel)
                    7 -> AdvancedSettings(viewModel)
                }
            } else {
                // 搜索全局结果
                SettingsSearchResults(searchQuery, tabs, tabIcons, onSelectTab = { selectedTab = it })
            }
        }
    }
}
