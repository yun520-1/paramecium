package com.heartflow.app.views

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 内置浏览器屏幕 — 完整的 WebView 浏览器体验
 *
 * 布局：
 * ┌─────────────────────────────────┐
 * │  [←]  [地址栏 / URL 输入框]  [→] │  ← 顶栏
 * │  ████████████░░░░░░░░ 45%       │  ← 进度条（加载时显示）
 * ├─────────────────────────────────┤
 * │                                 │
 * │         WebView 内容             │  ← 主内容区
 * │                                 │
 * ├─────────────────────────────────┤
 * │  [←]  [→]  [↻]  [🏠]  [✕]     │  ← 底栏
 * └─────────────────────────────────┘
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    onBack: () -> Unit = {},
    viewModel: BrowserViewModel = viewModel()
) {
    var urlInput by remember { mutableStateOf(viewModel.url) }
    var isEditing by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    // 当 ViewModel 中的 URL 变化时（如点击链接后退），同步到输入框
    LaunchedEffect(viewModel.url) {
        if (!isEditing) {
            urlInput = viewModel.url
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
            viewModel.loadUrl(urlInput)
            isEditing = false
            focusManager.clearFocus()
        }
    )

    Scaffold(
        topBar = {
            // ── 顶栏：独立导航按钮 + URL显示/输入 ──────────
            TopAppBar(
                title = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // 第一行：导航按钮
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // 左侧导航按钮组
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // 后退
                                IconButton(
                                    onClick = { viewModel.goBack() },
                                    enabled = viewModel.canGoBack,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        Icons.Default.ArrowBack,
                                        contentDescription = "后退",
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                // 前进
                                IconButton(
                                    onClick = { viewModel.goForward() },
                                    enabled = viewModel.canGoForward,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        Icons.Default.ArrowForward,
                                        contentDescription = "前进",
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                // 刷新
                                IconButton(
                                    onClick = {
                                        if (viewModel.isLoading) {
                                            viewModel.webViewRef?.stopLoading()
                                        } else {
                                            viewModel.refresh()
                                        }
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        if (viewModel.isLoading) Icons.Default.Close else Icons.Default.Refresh,
                                        contentDescription = if (viewModel.isLoading) "停止" else "刷新",
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }

                            // 主页按钮（显示为图标+文字）
                            FilledTonalButton(
                                onClick = { viewModel.goHome() },
                                modifier = Modifier.height(32.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Icon(
                                    Icons.Default.Home,
                                    contentDescription = "主页",
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("主页", fontSize = 12.sp)
                            }
                        }

                        // 第二行：URL输入框
                        OutlinedTextField(
                            value = urlInput,
                            onValueChange = { newUrl ->
                                isEditing = true
                                urlInput = newUrl
                            },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 1,
                            placeholder = {
                                Text(
                                    "输入网址或搜索关键词",
                                    fontSize = 13.sp
                                )
                            },
                            leadingIcon = {
                                // 安全锁图标表示HTTPS
                                Icon(
                                    if (viewModel.url.startsWith("https"))
                                        Icons.Default.Lock else Icons.Default.Public,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = if (viewModel.url.startsWith("https"))
                                        Color(0xFF4CAF50) else Color.Gray
                                )
                            },
                            trailingIcon = {
                                if (isEditing) {
                                    IconButton(
                                        onClick = {
                                            viewModel.loadUrl(urlInput)
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
                                }
                            },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Go
                            ),
                            keyboardActions = keyboardActions,
                            shape = RoundedCornerShape(20.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                focusedBorderColor = MaterialTheme.colorScheme.primary
                            ),
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 13.sp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            // ── 底栏：后退 / 前进 / 刷新 / 主页 / 关闭 ──
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                tonalElevation = 0.dp
            ) {
                // 后退
                NavigationBarItem(
                    icon = {
                        Icon(
                            Icons.Default.ArrowBack,
                            "后退",
                            tint = if (viewModel.canGoBack) MaterialTheme.colorScheme.onSurface
                                   else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                    },
                    label = { Text("后退", fontSize = 10.sp) },
                    selected = false,
                    onClick = { viewModel.goBack() },
                    enabled = viewModel.canGoBack
                )
                // 前进
                NavigationBarItem(
                    icon = {
                        Icon(
                            Icons.Default.ArrowForward,
                            "前进",
                            tint = if (viewModel.canGoForward) MaterialTheme.colorScheme.onSurface
                                   else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                    },
                    label = { Text("前进", fontSize = 10.sp) },
                    selected = false,
                    onClick = { viewModel.goForward() },
                    enabled = viewModel.canGoForward
                )
                // 刷新
                NavigationBarItem(
                    icon = {
                        Icon(
                            if (viewModel.isLoading) Icons.Default.Close else Icons.Default.Refresh,
                            if (viewModel.isLoading) "停止" else "刷新"
                        )
                    },
                    label = { Text(if (viewModel.isLoading) "停止" else "刷新", fontSize = 10.sp) },
                    selected = false,
                    onClick = {
                        if (viewModel.isLoading) {
                            viewModel.webViewRef?.stopLoading()
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
            if (viewModel.isLoading) {
                LinearProgressIndicator(
                    progress = { viewModel.progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }

            // ── 页面标题（仅加载完成后显示） ────────────
            if (!viewModel.isLoading && viewModel.pageTitle.isNotBlank()) {
                Text(
                    text = viewModel.pageTitle,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // ── WebView 主内容 ──────────────────────────
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )

                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            loadWithOverviewMode = true
                            useWideViewPort = true
                            builtInZoomControls = true
                            displayZoomControls = false
                            setSupportZoom(true)
                            allowFileAccess = false
                            allowContentAccess = false
                            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                            userAgentString = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                                "(KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"
                        }

                        setBackgroundColor(android.graphics.Color.TRANSPARENT)

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                url?.let { viewModel.onPageStarted(view!!, it) }
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                url?.let { viewModel.onPageFinished(view!!, it) }
                            }
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                viewModel.onProgressChanged(newProgress)
                            }

                            override fun onReceivedTitle(view: WebView?, title: String?) {
                                // ⚠️ WebChromeClient 回调在非主线程，禁止直接赋值 ViewModel 状态
                                // 由 onPageFinished 统一处理状态更新
                            }
                        }

                        // 加载初始 URL
                        loadUrl(viewModel.url)
                        viewModel.webViewRef = this
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
