package com.heartflow.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.heartflow.app.views.BrowserScreen
import com.heartflow.app.views.BrowserViewModel
import com.heartflow.app.views.MemoryManagementScreen
import com.heartflow.app.views.DocumentScannerScreen
import com.heartflow.data.*
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeartFlowApp(viewModel: ChatViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    // ── 浏览器 ViewModel — 与 ChatViewModel 共享同一个实例 ──────────────
    val browserViewModel: BrowserViewModel = viewModel()

    // ── 修复返回手势：拦截系统返回键 ──────────────────────────────────
    BackHandler {
        when (uiState.currentPage) {
            "chat" -> {
                // chat页面不处理，让系统默认退出
            }
            "history" -> viewModel.setPage("chat")
            "terminal" -> viewModel.setPage("chat")
            "browser" -> viewModel.setPage("chat")
            "scanner" -> viewModel.setPage("chat")
            "memory" -> viewModel.setPage("settings")
            "settings" -> viewModel.setPage("chat")
            else -> viewModel.setPage("chat")
        }
    }

    // 监听 ChatViewModel 的浏览器命令：工具调用时自动跳转到浏览器
    LaunchedEffect(Unit) {
        viewModel.browserCommand.collect { cmd ->
            browserViewModel.sendCommand(cmd)
            viewModel.setPage("browser")
        }
    }

    var showSplash by remember { mutableStateOf(true) }

    // 启动动画：1.5秒后关闭 Splash
    LaunchedEffect(Unit) {
        delay(1500L)
        showSplash = false
    }

    val colorScheme = appliedColorScheme(uiState.themeMode, uiState.themeVariant, isSystemInDarkTheme())

    MaterialTheme(colorScheme = colorScheme) {
        AnimatedContent(
            targetState = showSplash,
            transitionSpec = { fadeIn(animationSpec = tween(800)) togetherWith fadeOut(animationSpec = tween(300)) },
            label = "splashAnimation"
        ) { isSplash ->
            if (isSplash) {
                // ── Splash 画面 ───────────────────────────
                Box(
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        // 心虫 logo 动画（从0.3→1.0 透明度 + 轻微上浮）
                        val logoAlpha by animateFloatAsState(
                            targetValue = 1f,
                            animationSpec = tween(800, easing = EaseOutCubic)
                        )
                        val logoOffset by animateDpAsState(
                            targetValue = 0.dp,
                            animationSpec = tween(600, easing = EaseOutCubic)
                        )
                        Text(
                            text = "🐛",
                            fontSize = 64.sp,
                            modifier = Modifier
                                .alpha(logoAlpha)
                                .offset(y = logoOffset - 20.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "心虫",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.alpha(logoAlpha)
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Paramecium",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                            modifier = Modifier.alpha(logoAlpha)
                        )
                    }
                }
            } else {
                // ── 主界面：页面级别的 AnimatedContent ─────
                AnimatedContent(
                    targetState = uiState.currentPage,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(250))
                    },
                    label = "pageTransition"
                ) { page ->
                    when (page) {
                        "chat" -> ChatPage(viewModel, uiState)
                        "history" -> HistoryPage(viewModel, uiState)
                        "terminal" -> TerminalScreen(onBack = { viewModel.setPage("chat") })
                        "browser" -> BrowserScreen(
                            onBack = { viewModel.setPage("chat") },
                            viewModel = browserViewModel
                        )
                        "scanner" -> DocumentScannerScreen(onBack = { viewModel.setPage("chat") })
                        "memory" -> MemoryManagementScreen(viewModel, onBack = { viewModel.setPage("settings") })
                        "settings" -> SettingsPage(viewModel, uiState)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatPage(viewModel: ChatViewModel, uiState: ChatUiState) {
    var showEditDialog by remember { mutableStateOf(false) }
    var editMessageIndex by remember { mutableIntStateOf(-1) }
    var editText by remember { mutableStateOf("") }
    var showImageViewer by remember { mutableStateOf(false) }
    var viewingAttachment by remember { mutableStateOf<MediaAttachment?>(null) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 头像 Emoji（缩小尺寸）
                        Text(uiState.personality.emoji, fontSize = 22.sp)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(uiState.personality.name, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Text(
                                if (uiState.config?.apiKey?.isNotBlank() == true)
                                    "已连接 ${uiState.config?.provider}" else "未配置API",
                                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                actions = {
                    IconButton(onClick = { viewModel.newChat() }) {
                        Icon(Icons.Default.Add, "新对话", modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { viewModel.setPage("settings") }) {
                        Icon(Icons.Default.Settings, "设置", modifier = Modifier.size(20.dp))
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                tonalElevation = 0.dp
            ) {
                NavigationBarItem(
                    icon = { Icon(Icons.AutoMirrored.Filled.Chat, "聊天") },
                    label = { Text("聊天") },
                    selected = uiState.currentPage == "chat",
                    onClick = { viewModel.setPage("chat") }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Scanner, "扫描") },
                    label = { Text("扫描") },
                    selected = uiState.currentPage == "scanner",
                    onClick = { viewModel.setPage("scanner") }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.History, "历史") },
                    label = { Text("历史") },
                    selected = uiState.currentPage == "history",
                    onClick = { viewModel.setPage("history") }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Public, "浏览器") },
                    label = { Text("浏览器") },
                    selected = uiState.currentPage == "browser",
                    onClick = { viewModel.setPage("browser") }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Settings, "设置") },
                    label = { Text("设置") },
                    selected = uiState.currentPage == "settings",
                    onClick = { viewModel.setPage("settings") }
                )
            }
        }
    ) { padding ->
        val listState = rememberLazyListState()

        LaunchedEffect(uiState.messages.size) {
            if (uiState.messages.isNotEmpty()) {
                listState.animateScrollToItem(uiState.messages.lastIndex)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surface,
                            MaterialTheme.colorScheme.background
                        )
                    )
                )
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) { itemsIndexed(uiState.messages) { index, message ->
                    ChatMessageItem(
                        message = message,
                        index = index,
                        speakingMessageIndex = uiState.speakingMessageIndex,
                        onEdit = { idx ->
                            editMessageIndex = idx
                            editText = message.content
                            showEditDialog = true
                        },
                        onResend = { idx ->
                            viewModel.resendMessage(idx)
                        },
                        onImageClick = { attachment ->
                            viewingAttachment = attachment
                            showImageViewer = true
                        },
                        onCancelTool = { idx ->
                            viewModel.cancelToolCall(idx)
                        },
                        onSpeak = { idx ->
                            viewModel.speakMessage(idx)
                        },
                        onStopSpeak = {
                            viewModel.stopSpeaking()
                        }
                    )
            } }

            ChatInput(
                isProcessing = uiState.isProcessing,
                voiceState = uiState.voiceState,
                onSend = { viewModel.sendMessage(it) },
                onStop = { viewModel.stopGeneration() },
                onDream = { viewModel.startDream() },
                onEvolve = { viewModel.evolve() },
                onVoiceToggle = { viewModel.updateVoiceState(it) },
                onVoiceSend = { viewModel.sendVoiceMessage(it) },
                onAttachment = { text, attachment, data ->
                    viewModel.sendAttachment(text, attachment, data)
                }
            )
        }

        if (uiState.showSettings) {
            QuickSettingsDialog(
                currentConfig = uiState.config,
                personality = uiState.personality,
                onDismiss = { viewModel.toggleSettings() },
                onSaveConfig = { viewModel.updateConfig(it) },
                onSelectPersonality = { viewModel.setPersonality(it) }
            )
        }

        // 编辑消息对话框
        if (showEditDialog) {
            AlertDialog(
                onDismissRequest = { showEditDialog = false },
                title = { Text("编辑消息") },
                text = {
                    OutlinedTextField(
                        value = editText,
                        onValueChange = { editText = it },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 10
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (editText.isNotBlank()) {
                            viewModel.editMessage(editMessageIndex, editText)
                        }
                        showEditDialog = false
                    }) { Text("发送") }
                },
                dismissButton = {
                    TextButton(onClick = { showEditDialog = false }) { Text("取消") }
                }
            )
        }

        // 全屏图片查看器
        if (showImageViewer && viewingAttachment != null) {
            ImageViewer(
                attachment = viewingAttachment!!,
                onDismiss = { showImageViewer = false }
            )
        }
    }
}
