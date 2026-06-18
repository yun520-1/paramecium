package com.heartflow.app.views

import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange
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
 * 布局：
 * ┌─────────────────────────────────┐
 * │  [🔒]  [URL 地址栏 / 输入框] [→] │  ← 顶栏
 * │  ████████████░░░░░░░░ 45%       │  ← 进度条
 * ├─────────────────────────────────┤
 * │                                 │
 * │       GeckoView 内容             │  ← 主内容区
 * │                                 │
 * ├─────────────────────────────────┤
 * │     [←] [→] [↻] [🏠] [✕]      │  ← 底栏
 * └─────────────────────────────────┘
 *
 * 地址栏支持：
 * - 自动显示当前页面 URL
 * - 点击进入编辑模式（全选文本，方便修改）
 * - 键盘回车或箭头按钮跳转
 * - 页面导航时自动同步回显示模式
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
    val focusManager = LocalFocusManager.current

    // 从 GeckoEngine 读取实时状态
    val engine = remember { GeckoEngine.getInstance() }

    // 当引擎 URL 更新且用户不在编辑状态时，同步到输入框
    LaunchedEffect(engine.url) {
        if (!isEditing) {
            textFieldValue = TextFieldValue(engine.url.ifEmpty { viewModel.url })
        }
    }

    // ── 监听外部浏览器命令（工具调用通过 SharedFlow 发送）───────────────
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

    // ── 处理地址栏回车跳转 ──────────────────────────────────────────────
    val keyboardActions = KeyboardActions(
        onGo = {
            viewModel.loadUrl(textFieldValue.text)
            isEditing = false
            focusManager.clearFocus()
        }
    )

    // ── 处理从引擎同步来的新 URL（页面加载后主动回到显示模式）──────────
    // 当 engine.url 发生变化且不是编辑模式，清除焦点回到显示模式
    LaunchedEffect(engine.url) {
        if (!isEditing) {
            focusManager.clearFocus()
        }
    }

    Scaffold(
        topBar = {
            // ── 顶栏：独立的 URL 地址栏 ───────────────────────────────────
            CenterAlignedTopAppBar(
                title = {
                    OutlinedTextField(
                        value = textFieldValue,
                        onValueChange = { newValue ->
                            if (!isEditing) {
                                isEditing = true
                                // 进入编辑模式时全选文本，方便直接覆盖输入
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
                                    // 失去焦点时回到显示模式
                                    isEditing = false
                                    // 恢复显示当前引擎 URL
                                    textFieldValue = TextFieldValue(
                                        engine.url.ifEmpty { viewModel.url }
                                    )
                                } else if (!isEditing) {
                                    // 获得焦点时自动进入编辑模式并全选
                                    isEditing = true
                                    textFieldValue = textFieldValue.copy(
                                        selection = TextRange(0, textFieldValue.text.length)
                                    )
                                }
                            },
                        maxLines = 1,
                        singleLine = true,
                        placeholder = {
                            Text(
                                "输入网址或搜索关键词",
                                fontSize = 13.sp,
                                maxLines = 1
                            )
                        },
                        textStyle = TextStyle(fontSize = 13.sp),
                        // 根据链接是否加密显示不同图标
                        leadingIcon = {
                            Icon(
                                if (engine.url.startsWith("https"))
                                    Icons.Default.Lock else Icons.Default.Public,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (engine.url.startsWith("https"))
                                    Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        },
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    viewModel.loadUrl(textFieldValue.text)
                                    isEditing = false
                                    focusManager.clearFocus()
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.ArrowForward,
                                    contentDescription = "跳转",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
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
        },
        bottomBar = {
            // ── 底栏：后退 / 前进 / 刷新 / 主页 / 关闭 ──
            NavigationBar(
                containerColor = scheme.glassSurface,
                tonalElevation = 0.dp
            ) {
                // 后退
                NavigationBarItem(
                    icon = {
                        Icon(
                            Icons.Default.ArrowBack,
                            "后退",
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
                            Icons.Default.ArrowForward,
                            "前进",
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
                        if (engine.isLoading) {
                            engine.session.stop()
                        } else {
                            viewModel.refresh()
                        }
                    }
                )
                // 主页
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Home, "主页") },
                    label = { Text("主页", fontSize = 10.sp) },
                    selected = false,
                    onClick = { viewModel.goHome() }
                )
                // 关闭浏览器
                NavigationBarItem(
                    icon = { Icon(Icons.Default.ExitToApp, "关闭") },
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

            // ── 页面标题（仅加载完成后显示） ────────────
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
                    // 获取 GeckoEngine 实例
                    val engine = GeckoEngine.getInstance()

                    // 创建 GeckoView
                    val geckoView = GeckoView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )

                        // 设置 session（如果 session 未打开则重新打开）
                        if (!engine.session.isOpen) {
                            engine.session.open(engine.runtime)
                        }
                        setSession(engine.session)
                    }

                    // 保存引用供 ViewModel 使用
                    viewModel.geckoViewRef = geckoView

                    // 加载初始 URL
                    engine.loadUrl(viewModel.url)

                    geckoView
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
