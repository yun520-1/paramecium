package com.heartflow.app.views

import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * 浏览器导航命令 — 工具调用通过 SharedFlow 发送此命令控制浏览器
 */
sealed class BrowserCommand {
    /** 加载指定 URL */
    data class LoadUrl(val url: String) : BrowserCommand()
    /** 后退 */
    data object GoBack : BrowserCommand()
    /** 前进 */
    data object GoForward : BrowserCommand()
    /** 刷新页面 */
    data object Refresh : BrowserCommand()
    /** 回到主页 */
    data object GoHome : BrowserCommand()
}

/**
 * 浏览器 ViewModel — 管理 WebView 的状态
 *
 * 通过 Compose 可观察属性驱动 UI 更新。
 * WebView 本身在 BrowserScreen 的 AndroidView 中创建，
 * ViewModel 仅持有对它的引用用于导航控制。
 *
 * ⚠️ 重要：所有 WebViewClient/WebChromeClient 回调运行在 WebView 线程（非主线程），
 * 必须用 viewModelScope.launch {} 包装状态更新，防止 Compose 在非主线程重组导致卡死。
 */
class BrowserViewModel : ViewModel() {

    /** 外部浏览器命令管道 — 用于接收来自工具调用的导航命令 */
    private val _browserCommand = MutableSharedFlow<BrowserCommand>(
        replay = 1,  // 新订阅者也能收到最新命令
        extraBufferCapacity = 16,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val browserCommand: SharedFlow<BrowserCommand> = _browserCommand.asSharedFlow()

    /** 地址栏当前显示/输入的 URL */
    var url by mutableStateOf("https://wm.m.sm.cn/?from=wm828952")

    /** WebView 实际加载的 URL */
    var currentUrl by mutableStateOf("")

    /** 当前页面标题 */
    var pageTitle by mutableStateOf("浏览器")

    /** 是否正在加载页面 */
    var isLoading by mutableStateOf(false)

    /** 加载进度 0-100 */
    var progress by mutableIntStateOf(0)

    /** 能否后退 */
    var canGoBack by mutableStateOf(false)

    /** 能否前进 */
    var canGoForward by mutableStateOf(false)

    /** WebView 实例引用 — 由 BrowserScreen 在创建时赋值 */
    var webViewRef by mutableStateOf<WebView?>(null)

    /** 默认主页 URL（更友好的显示） */
    val homeUrl = "https://wm.m.sm.cn/?from=wm828952"

    /** 主页显示名称 */
    val homeDisplayName = "wm.m.sm.cn"

    override fun onCleared() {
        super.onCleared()
        webViewRef?.destroy()
        webViewRef = null
    }

    /** 加载指定 URL，自动补全 http/https */
    fun loadUrl(inputUrl: String) {
        val formattedUrl = when {
            inputUrl.startsWith("http://") || inputUrl.startsWith("https://") -> inputUrl
            inputUrl.contains(".") -> "https://$inputUrl"
            else -> "https://wm.m.sm.cn/s?wd=$inputUrl"
        }
        url = formattedUrl
        currentUrl = formattedUrl
        webViewRef?.loadUrl(formattedUrl)
    }

    /** 回到主页 */
    fun goHome() {
        loadUrl(homeUrl)
    }

    /** 后退 */
    fun goBack() {
        webViewRef?.goBack()
    }

    /** 前进 */
    fun goForward() {
        webViewRef?.goForward()
    }

    /** 刷新 */
    fun refresh() {
        webViewRef?.reload()
    }

    /**
     * 发送浏览器命令 — 供 ChatViewModel/工具调用使用
     * 调用后浏览器自动加载对应 URL 或执行导航
     */
    fun sendCommand(command: BrowserCommand) {
        viewModelScope.launch {
            _browserCommand.emit(command)
        }
    }

    /**
     * 启动命令监听器 — 在 BrowserScreen 的 LaunchedEffect 中调用
     * 监听 browserCommand 管道并执行对应操作
     */
    fun startCommandListener() {
        viewModelScope.launch {
            browserCommand.collect { cmd ->
                when (cmd) {
                    is BrowserCommand.LoadUrl -> loadUrl(cmd.url)
                    is BrowserCommand.GoBack -> goBack()
                    is BrowserCommand.GoForward -> goForward()
                    is BrowserCommand.Refresh -> refresh()
                    is BrowserCommand.GoHome -> goHome()
                }
            }
        }
    }

    // ── WebViewClient 回调（必须主线程调度）─────────────────────────────

    fun onPageStarted(view: WebView, url: String) {
        // WebView 回调在非主线程，必须用 launch{} 调度到主线程
        viewModelScope.launch {
            isLoading = true
            progress = 10
            currentUrl = url
            this@BrowserViewModel.url = url
        }
    }

    fun onPageFinished(view: WebView, url: String) {
        viewModelScope.launch {
            isLoading = false
            progress = 100
            pageTitle = view.title ?: "浏览器"
            canGoBack = view.canGoBack()
            canGoForward = view.canGoForward()
        }
    }

    fun onProgressChanged(progress: Int) {
        viewModelScope.launch {
            this@BrowserViewModel.progress = progress
        }
    }
}
