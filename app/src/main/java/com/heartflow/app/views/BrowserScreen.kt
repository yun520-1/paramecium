package com.heartflow.app.views

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.heartflow.app.LocalThemeScheme
import com.heartflow.tool.builtin.GeckoEngine
import org.mozilla.geckoview.GeckoView

/**
 * 内置浏览器屏幕 — GeckoView 完整浏览器引擎
 *
 * 使用 Mozilla GeckoView 代替系统 WebView，提供更完整的网页渲染能力。
 * 状态直接从 [GeckoEngine] 可观察属性读取。
 *
 * 功能：
 * - 多标签页（顶栏标签切换）
 * - 导航：后退/前进/刷新/主页/关闭
 * - 地址栏：显示 URL / 编辑跳转
 * - 加载进度条 + 页面标题显示
 * - 更多菜单：桌面版/移动版切换、历史记录、广告过滤开关
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    onBack: () -> Unit = {},
    viewModel: BrowserViewModel = viewModel()
) {
    val scheme = LocalThemeScheme.current
    var textFieldValue by remember { mutableStateOf(TextFieldValue(viewModel.url)) }
    var isEditing by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showHistoryDialog by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    // 上下文菜单状态
    var contextMenuState by remember { mutableStateOf<ContextMenuInfo?>(null) }

    // 从 GeckoEngine 读取实时状态
    val engine = remember { GeckoEngine.getInstance() }

    // 当引擎 URL 更新且用户不在编辑状态时，同步到输入框
    LaunchedEffect(engine.url) {
        if (!isEditing) {
            textFieldValue = TextFieldValue(engine.url.ifEmpty { viewModel.url })
        }
    }

    // 监听外部浏览器命令（工具调用通过 SharedFlow 发送）
    LaunchedEffect(Unit) {
        viewModel.browserCommand.collect { cmd ->
            when (cmd) {
                is BrowserCommand.LoadUrl -> viewModel.loadUrl(cmd.url)
                is BrowserCommand.GoBack -> viewModel.goBack()
                is BrowserCommand.GoForward -> viewModel.goForward()
                is BrowserCommand.Refresh -> viewModel.refresh()
                is BrowserCommand.GoHome -> viewModel.goHome()
            }
        }
    }

    // 监听标签页切换，重新绑定 GeckoView
    LaunchedEffect(engine.activeTabIndex) {
        viewModel.bindGeckoView()
    }

    // 处理地址栏回车跳转
    val keyboardActions = KeyboardActions(
        onGo = {
            viewModel.loadUrl(textFieldValue.text)
            isEditing = false
            focusManager.clearFocus()
        }
    )

    // 当 engine.url 发生变化且不是编辑模式，清除焦点回到显示模式
    LaunchedEffect(engine.url) {
        if (!isEditing) {
            focusManager.clearFocus()
        }
    }

    Scaffold(
        topBar = {
            Column {
                // ── 标签页条 ─────────────────────────────────
                if (engine.tabs.size > 1) {
                    TabStrip(
                        tabs = engine.tabs,
                        activeIndex = engine.activeTabIndex,
                        onSwitchTab = { viewModel.switchTab(it) },
                        onCloseTab = { viewModel.closeTab(it) },
                        onNewTab = { viewModel.createTab() }
                    )
                }

                // ── URL 地址栏 ───────────────────────────────
                CenterAlignedTopAppBar(
                    title = {
                        OutlinedTextField(
                            value = textFieldValue,
                            onValueChange = { newValue ->
                                if (!isEditing) {
                                    isEditing = true
                                    textFieldValue = newValue.copy(
                                        selection = TextRange(0, newValue.text.length)
                                    )
                                } else {
                                    textFieldValue = newValue
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { focusState ->
                                    if (!focusState.isFocused) {
                                        isEditing = false
                                        textFieldValue = TextFieldValue(
                                            engine.url.ifEmpty { viewModel.url }
                                        )
                                    } else if (!isEditing) {
                                        isEditing = true
                                        textFieldValue = textFieldValue.copy(
                                            selection = TextRange(0, textFieldValue.text.length)
                                        )
                                    }
                                },
                            maxLines = 1,
                            singleLine = true,
                            placeholder = {
                                Text("输入网址或搜索关键词", fontSize = 13.sp)
                            },
                            textStyle = TextStyle(fontSize = 13.sp),
                            leadingIcon = {
                                Icon(
                                    if (engine.url.startsWith("https"))
                                        Icons.Default.Lock else Icons.Default.Public,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = if (engine.url.startsWith("https"))
                                        Color(0xFF4CAF50)
                                    else
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            },
                            trailingIcon = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    // 更多菜单按钮
                                    Box {
                                        IconButton(
                                            onClick = { showOverflowMenu = true },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.MoreVert,
                                                contentDescription = "更多",
                                                modifier = Modifier.size(18.dp),
                                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                            )
                                        }
                                        DropdownMenu(
                                            expanded = showOverflowMenu,
                                            onDismissRequest = { showOverflowMenu = false }
                                        ) {
                                            // User-Agent 切换
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        if (engine.userAgentMode == "mobile") "桌面版视图"
                                                        else "移动版视图",
                                                        fontSize = 14.sp
                                                    )
                                                },
                                                onClick = {
                                                    viewModel.toggleUserAgent()
                                                    showOverflowMenu = false
                                                },
                                                leadingIcon = {
                                                    Icon(
                                                        if (engine.userAgentMode == "mobile")
                                                            Icons.Default.PhoneAndroid
                                                        else
                                                            Icons.Default.Computer,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            )
                                            // 广告过滤开关
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        if (engine.adBlockEnabled) "广告过滤：开启"
                                                        else "广告过滤：关闭",
                                                        fontSize = 14.sp
                                                    )
                                                },
                                                onClick = {
                                                    engine.adBlockEnabled = !engine.adBlockEnabled
                                                    showOverflowMenu = false
                                                },
                                                leadingIcon = {
                                                    Icon(
                                                        if (engine.adBlockEnabled)
                                                            Icons.Default.Shield
                                                        else
                                                            Icons.Outlined.Shield,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            )
                                            // 历史记录
                                            DropdownMenuItem(
                                                text = { Text("历史记录", fontSize = 14.sp) },
                                                onClick = {
                                                    showOverflowMenu = false
                                                    showHistoryDialog = true
                                                },
                                                leadingIcon = {
                                                    Icon(
                                                        Icons.Default.History,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            )
                                        }
                                    }

                                    // 跳转按钮
                                    IconButton(
                                        onClick = {
                                            viewModel.loadUrl(textFieldValue.text)
                                            isEditing = false
                                            focusManager.clearFocus()
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.ArrowForward,
                                            contentDescription = "跳转",
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = keyboardActions,
                            shape = RoundedCornerShape(20.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = scheme.surfaceContainerLow,
                                unfocusedContainerColor = scheme.surfaceContainerLow,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                focusedBorderColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = scheme.glassSurface
                    )
                )
            }
        },
        bottomBar = {
            // ── 底栏：后退 / 前进 / 刷新 / 主页 / 新标签 / 关闭 ──
            NavigationBar(
                containerColor = scheme.glassSurface,
                tonalElevation = 0.dp
            ) {
                // 后退
                NavigationBarItem(
                    icon = {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack, "后退",
                            tint = if (engine.canGoBack) MaterialTheme.colorScheme.onSurface
                                   else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                    },
                    label = { Text("后退", fontSize = 10.sp) },
                    selected = false,
                    onClick = { viewModel.goBack() },
                    enabled = engine.canGoBack
                )
                // 前进
                NavigationBarItem(
                    icon = {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward, "前进",
                            tint = if (engine.canGoForward) MaterialTheme.colorScheme.onSurface
                                   else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                    },
                    label = { Text("前进", fontSize = 10.sp) },
                    selected = false,
                    onClick = { viewModel.goForward() },
                    enabled = engine.canGoForward
                )
                // 刷新/停止
                NavigationBarItem(
                    icon = {
                        Icon(
                            if (engine.isLoading) Icons.Default.Close else Icons.Default.Refresh,
                            if (engine.isLoading) "停止" else "刷新"
                        )
                    },
                    label = { Text(if (engine.isLoading) "停止" else "刷新", fontSize = 10.sp) },
                    selected = false,
                    onClick = {
                        if (engine.isLoading) viewModel.stopLoading()
                        else viewModel.refresh()
                    }
                )
                // 主页
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Home, "主页") },
                    label = { Text("主页", fontSize = 10.sp) },
                    selected = false,
                    onClick = { viewModel.goHome() }
                )
                // 新标签
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Add, "新标签") },
                    label = { Text("新标签", fontSize = 10.sp) },
                    selected = false,
                    onClick = { viewModel.createTab() }
                )
                // 关闭
                NavigationBarItem(
                    icon = { Icon(Icons.AutoMirrored.Filled.ExitToApp, "关闭") },
                    label = { Text("关闭", fontSize = 10.sp) },
                    selected = false,
                    onClick = onBack
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ── 加载进度条 ──────────────────────────────
            if (engine.isLoading) {
                LinearProgressIndicator(
                    progress = { engine.progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = scheme.surfaceContainerHighest
                )
            }

            // ── 页面标题 ────────────────────────────────
            if (!engine.isLoading && engine.pageTitle.isNotBlank()) {
                Text(
                    text = engine.pageTitle,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // ── GeckoView 主内容 ──────────────────────────
            AndroidView(
                factory = { context ->
                    val geckoView = GeckoView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )

                        // 绑定到当前标签页 session
                        if (!engine.session.isOpen) {
                            engine.session.open(engine.runtime)
                        }
                        setSession(engine.session)
                    }

                    // 保存引用供 ViewModel 使用
                    viewModel.geckoViewRef = geckoView

                    // 监听标签页切换
                    engine.onActiveTabChanged = { newSession ->
                        geckoView.setSession(newSession)
                    }

                    // 监听下载请求
                    engine.onDownloadRequested = { url, mimeType, contentLength ->
                        viewModel.lastDownloadInfo = "$url ($mimeType, ${contentLength / 1024}KB)"
                    }

                    // 监听上下文菜单（长按链接/图片等）
                    engine.onContextMenuRequested = { linkUri, linkText, srcUri, elementType ->
                        contextMenuState = ContextMenuInfo(
                            linkUri = linkUri,
                            linkText = linkText,
                            srcUri = srcUri,
                            elementType = elementType
                        )
                    }

                    // 加载初始 URL
                    engine.loadUrl(viewModel.url)

                    geckoView
                },
                modifier = Modifier.fillMaxSize()
            )

            // ── 下载提醒条 ──────────────────────────────
            val lastDownload = viewModel.lastDownloadInfo
            AnimatedVisibility(
                visible = lastDownload != null,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    tonalElevation = 2.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.FileDownload,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "检测到下载：${lastDownload?.take(80)}",
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            onClick = { viewModel.lastDownloadInfo = null },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Text("关闭", fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }

    // ── 历史记录对话框 ─────────────────────────────
    if (showHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showHistoryDialog = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("历史记录")
                    TextButton(onClick = {
                        viewModel.clearHistory()
                    }) {
                        Text("清除", fontSize = 12.sp)
                    }
                }
            },
            text = {
                val history = viewModel.history
                if (history.isEmpty()) {
                    Text("暂无浏览历史", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                } else {
                    Column {
                        history.take(50).forEach { entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.loadUrl(entry.url)
                                        showHistoryDialog = false
                                    }
                                    .padding(vertical = 6.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Language,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = entry.title.ifEmpty { entry.url },
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = entry.url,
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showHistoryDialog = false }) {
                    Text("关闭")
                }
            }
        )
    }

    // ── 上下文菜单（长按链接） ─────────────────────────
    if (contextMenuState != null) {
        AlertDialog(
            onDismissRequest = { contextMenuState = null },
            title = {
                Text(
                    text = when (contextMenuState!!.elementType) {
                        "image" -> "图片操作"
                        "video" -> "视频操作"
                        "audio" -> "音频操作"
                        else -> "链接操作"
                    },
                    fontSize = 16.sp
                )
            },
            text = {
                Column {
                    // 链接标题
                    if (!contextMenuState!!.linkText.isNullOrBlank()) {
                        Text(
                            text = contextMenuState!!.linkText!!.take(60),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(4.dp))
                    }

                    // 链接 URL
                    if (!contextMenuState!!.linkUri.isNullOrBlank()) {
                        Text(
                            text = contextMenuState!!.linkUri!!.take(80),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    // 操作按钮
                    if (!contextMenuState!!.linkUri.isNullOrBlank()) {
                        // 打开链接
                        Button(
                            onClick = {
                                viewModel.loadUrl(contextMenuState!!.linkUri!!)
                                contextMenuState = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("打开链接")
                        }
                        Spacer(Modifier.height(8.dp))

                        // 复制链接
                        OutlinedButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(contextMenuState!!.linkUri!!))
                                contextMenuState = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("复制链接")
                        }
                    }

                    // 图片相关操作
                    if (contextMenuState!!.elementType == "image" && !contextMenuState!!.srcUri.isNullOrBlank()) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(contextMenuState!!.srcUri!!))
                                contextMenuState = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("复制图片地址")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { contextMenuState = null }) {
                    Text("取消")
                }
            }
        )
    }
}

// ══════════════════════════════════════════════════════════════
// 标签页条组件
// ══════════════════════════════════════════════════════════════

@Composable
private fun TabStrip(
    tabs: List<com.heartflow.tool.builtin.BrowserTab>,
    activeIndex: Int,
    onSwitchTab: (Int) -> Unit,
    onCloseTab: (Int) -> Unit,
    onNewTab: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 1.dp,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .height(40.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEachIndexed { index, tab ->
                val isActive = index == activeIndex
                Surface(
                    modifier = Modifier
                        .padding(horizontal = 2.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onSwitchTab(index) },
                    color = if (isActive) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 标签图标
                        Icon(
                            Icons.Default.Public,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
                                   else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Spacer(Modifier.width(4.dp))
                        // 标签标题
                        Text(
                            text = tab.title.ifEmpty {
                                tab.url.ifEmpty { "新标签页" }
                            }.take(12),
                            fontSize = 11.sp,
                            maxLines = 1,
                            color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal
                        )
                        // 关闭按钮
                        IconButton(
                            onClick = { onCloseTab(index) },
                            modifier = Modifier
                                .size(18.dp)
                                .padding(start = 2.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "关闭标签页",
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }

            // 新建标签按钮
            IconButton(
                onClick = onNewTab,
                modifier = Modifier
                    .size(28.dp)
                    .padding(start = 2.dp)
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "新建标签页",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/**
 * 上下文菜单信息 — 长按链接/图片时传递的数据
 */
data class ContextMenuInfo(
    val linkUri: String?,
    val linkText: String?,
    val srcUri: String?,
    val elementType: String // "link" | "image" | "video" | "audio"
)
