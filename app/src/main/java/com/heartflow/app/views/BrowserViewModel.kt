package com.heartflow.app.views

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heartflow.tool.builtin.BrowserTab
import com.heartflow.tool.builtin.GeckoEngine
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
 * 历史记录条目
 */
data class HistoryEntry(
    val url: String,
    val title: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 下载任务信息
 */
data class DownloadInfo(
    val url: String,
    val mimeType: String,
    val contentLength: Long,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 浏览器 ViewModel — 管理浏览器的状态和导航
 *
 * 通过 Compose 可观察属性驱动 UI 更新。
 * 实际渲染引擎由 [GeckoEngine] 管理（GeckoView 完整浏览器引擎），
 * ViewModel 作为 UI 层与引擎之间的协调层。
 *
 * 外部命令通过 [BrowserCommand] SharedFlow 传入。
 *
 * 功能增强：
 * - 历史记录追踪
 * - 下载任务管理
 * - 标签页管理同步
 * - User-Agent 切换
 */
class BrowserViewModel : ViewModel() {

    /** 外部浏览器命令管道 — 用于接收来自工具调用的导航命令 */
    private val _browserCommand = MutableSharedFlow<BrowserCommand>(
        replay = 1,
        extraBufferCapacity = 16,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val browserCommand: SharedFlow<BrowserCommand> = _browserCommand.asSharedFlow()

    /** 地址栏当前显示/输入的 URL */
    var url by mutableStateOf("https://wm.m.sm.cn/?from=wm828952")

    /** 名称固定的主页 URL */
    var homeUrl = "https://wm.m.sm.cn/?from=wm828952"

    /** GeckoView 实例引用 — 由 BrowserScreen 在创建时赋值 */
    var geckoViewRef by mutableStateOf<org.mozilla.geckoview.GeckoView?>(null)

    /** Gecko 引擎单例引用 */
    private val engine: GeckoEngine
        get() = GeckoEngine.getInstance()

    // ══════════════════════════════════════════════════════════
    // 历史记录
    // ══════════════════════════════════════════════════════════

    private val _history = mutableListOf<HistoryEntry>()
    val history: List<HistoryEntry> get() = _history.toList().reversed() // 最新在前

    /** 添加历史记录 */
    fun addHistory(url: String, title: String) {
        _history.removeAll { it.url == url }
        _history.add(HistoryEntry(url, title))
        // 最多保留 500 条
        if (_history.size > 500) _history.removeAt(0)
    }

    /** 清除历史 */
    fun clearHistory() {
        _history.clear()
        engine.clearHistory()
    }

    // ══════════════════════════════════════════════════════════
    // 下载管理
    // ══════════════════════════════════════════════════════════

    private val _downloads = mutableListOf<DownloadInfo>()
    val downloads: List<DownloadInfo> get() = _downloads.toList()

    /** 最近一个下载任务的 URL（用于 UI 显示提醒） */
    var lastDownloadInfo by mutableStateOf<String?>(null)

    // ══════════════════════════════════════════════════════════
    // 标签页管理
    // ══════════════════════════════════════════════════════════

    val tabs: List<BrowserTab> get() = engine.tabs
    var activeTabIndex by mutableStateOf(0)
        private set

    /** 创建新标签页 */
    fun createTab(): Int {
        val tabId = engine.createTab()
        activeTabIndex = engine.activeTabIndex
        return tabId
    }

    /** 切换标签页 */
    fun switchTab(index: Int) {
        if (engine.switchTab(index)) {
            activeTabIndex = engine.activeTabIndex
            // 更新地址栏显示
            url = engine.url
        }
    }

    /** 关闭标签页 */
    fun closeTab(index: Int) {
        if (engine.closeTab(index)) {
            activeTabIndex = engine.activeTabIndex
            url = engine.url
        }
    }

    // ══════════════════════════════════════════════════════════
    // User-Agent 模式
    // ══════════════════════════════════════════════════════════

    var userAgentMode by mutableStateOf(engine.userAgentMode)

    /** 切换 User-Agent 模式 */
    fun toggleUserAgent() {
        engine.toggleUserAgent()
        userAgentMode = engine.userAgentMode
    }

    // ══════════════════════════════════════════════════════════
    // 生命周期
    // ══════════════════════════════════════════════════════════

    override fun onCleared() {
        super.onCleared()
    }

    // ══════════════════════════════════════════════════════════
    // 导航方法
    // ══════════════════════════════════════════════════════════

    /** 加载指定 URL，自动补全 http/https */
    fun loadUrl(inputUrl: String) {
        val formattedUrl = when {
            inputUrl.startsWith("http://") || inputUrl.startsWith("https://") -> inputUrl
            inputUrl.contains(".") -> "https://$inputUrl"
            else -> "https://www.baidu.com/s?wd=$inputUrl"
        }
        url = formattedUrl
        engine.loadUrl(formattedUrl)
        bindGeckoView()
    }

    /** 回到主页 */
    fun goHome() {
        loadUrl(homeUrl)
    }

    /** 后退 */
    fun goBack() {
        engine.goBack()
    }

    /** 前进 */
    fun goForward() {
        engine.goForward()
    }

    /** 刷新 */
    fun refresh() {
        engine.reload()
    }

    /** 停止加载 */
    fun stopLoading() {
        engine.stop()
    }

    /**
     * 确保 GeckoView 绑定到当前 session
     */
    fun bindGeckoView() {
        geckoViewRef?.let { gv ->
            if (!engine.session.isOpen) {
                engine.session.open(engine.runtime)
            }
            gv.setSession(engine.session)
        }
    }

    /**
     * 发送浏览器命令 — 供 ChatViewModel/工具调用使用
     */
    fun sendCommand(command: BrowserCommand) {
        viewModelScope.launch {
            _browserCommand.emit(command)
        }
    }

    /**
     * 启动命令监听器 — 在 BrowserScreen 的 LaunchedEffect 中调用
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
}
