package com.heartflow.tool.builtin

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.WebResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 浏览器标签页数据
 */
data class BrowserTab(
    val id: Int,
    val session: GeckoSession,
    var url: String = "",
    var title: String = ""
)

/**
 * GeckoView 浏览器引擎单例
 *
 * 管理 GeckoRuntime + 多 GeckoSession（多标签页）的完整生命周期。
 * 状态通过 Compose mutableStateOf 暴露，可被 Compose UI 自动观察。
 * 支持同步内容提取（供后台工具线程使用）和异步观察（供 UI 层使用）。
 *
 * 功能：
 * - 多标签页：创建/切换/关闭标签页
 * - 广告过滤：基于 URL 域名黑名单
 * - 历史记录：自动记录最近访问的 URL
 * - 下载拦截：通过 ContentDelegate.onExternalResponse
 * - User-Agent 切换：移动端/桌面端
 * - 混合内容支持：允许 HTTP 资源加载
 */
class GeckoEngine private constructor(context: Context) {

    // ── 运行时 ──────────────────────────────────────────────
    val runtime: GeckoRuntime
    private var nextTabId = 1

    // ── Observable 状态（Compose 可自动观察）───────────────
    var url by mutableStateOf("")
    var pageTitle by mutableStateOf("")
    var isLoading by mutableStateOf(false)
    var progress by mutableIntStateOf(0)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var currentContent by mutableStateOf("")

    // ── 历史记录（最近访问的 URL）──────────────────────────
    private val _history = mutableListOf<String>()
    val history: List<String> get() = _history.toList()

    // ── 标签页管理 ──────────────────────────────────────────
    private val _tabs = mutableListOf<BrowserTab>()
    val tabs: List<BrowserTab> get() = _tabs.toList()
    var activeTabIndex by mutableIntStateOf(0)
        private set

    /** 当前激活的标签页 session */
    val session: GeckoSession
        get() {
            if (_tabs.isEmpty()) {
                // 罕见情况：所有标签页都被关闭，重新创建
                createTabInternal()
            }
            return _tabs[activeTabIndex].session
        }

    // ── User-Agent 模式 ─────────────────────────────────────
    var userAgentMode by mutableStateOf("mobile") // "mobile" | "desktop"

    // ── 广告过滤 ────────────────────────────────────────────
    var adBlockEnabled by mutableStateOf(true)

    // ── 页面查找 ────────────────────────────────────────────
    var isFindActive by mutableStateOf(false)
    var findQueryText by mutableStateOf("")
    var findResultText by mutableStateOf("")
        private set

    // ── 全屏模式 ──────────────────────────────────────────
    var isFullscreen by mutableStateOf(false)
        private set

    // ── 下载监听回调 ────────────────────────────────────────
    var onDownloadRequested: ((url: String, mimeType: String, contentLength: Long) -> Unit)? = null

    // 上下文菜单（长按链接/图片等）回调
    var onContextMenuRequested: ((linkUri: String?, linkText: String?, srcUri: String?, elementType: String) -> Unit)? = null

    // 页面加载完成后待提取内容的回调池
    private var pendingContentCallback: ((String) -> Unit)? = null

    // 用户是否手动关闭了浏览器（防止工具在被关闭的 session 上操作）
    @Volatile
    var isClosed = false
        private set

    // 标签页切换监听器（用于通知 UI 重新绑定 GeckoView）
    var onActiveTabChanged: ((GeckoSession) -> Unit)? = null

    init {
        // 内容屏蔽设置：禁用 ETP（URL 广告过滤由 NavigationDelegate 接管），
        // 允许所有 cookie，防止 ERR_BLOCKED_BY_ORB 等问题
        val contentBlockingSettings = ContentBlocking.Settings.Builder()
            .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.NONE)
            .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_ALL)
            .build()

        val settingsBuilder = GeckoRuntimeSettings.Builder()
            .remoteDebuggingEnabled(true)
            .allowInsecureConnections(GeckoRuntimeSettings.ALLOW_ALL) // 允许不安全的连接（HTTP 混合内容），修复部分页面加载问题
            .contentBlocking(contentBlockingSettings)

        // 创建运行时
        runtime = GeckoRuntime.create(context, settingsBuilder.build())

        // 创建初始标签页
        createTabInternal()
    }

    // ══════════════════════════════════════════════════════════
    // 标签页管理
    // ══════════════════════════════════════════════════════════

    /** 创建新标签页，返回标签页 ID */
    fun createTab(): Int {
        val tab = createTabInternal()
        return tab.id
    }

    private fun createTabInternal(): BrowserTab {
        val id = nextTabId++
        val newSession = GeckoSession()
        newSession.open(runtime)

        setupSessionDelegates(newSession)
        applyUserAgentToSession(newSession)

        val tab = BrowserTab(id, newSession, "", "")
        _tabs.add(tab)

        if (_tabs.size == 1) {
            activeTabIndex = 0
        }

        return tab
    }

    /** 切换到指定索引的标签页 */
    fun switchTab(index: Int): Boolean {
        if (index < 0 || index >= _tabs.size || index == activeTabIndex) return false

        // 保存当前标签页状态
        _tabs[activeTabIndex] = _tabs[activeTabIndex].copy(
            url = this.url,
            title = pageTitle
        )

        activeTabIndex = index

        // 从目标标签页同步状态
        val target = _tabs[activeTabIndex]
        url = target.url
        pageTitle = target.title

        // 通知 UI 重新绑定 GeckoView
        postOnMain {
            onActiveTabChanged?.invoke(session)
        }

        return true
    }

    /** 关闭指定索引的标签页 */
    fun closeTab(index: Int): Boolean {
        if (_tabs.size <= 1) return false // 至少保留一个
        if (index < 0 || index >= _tabs.size) return false

        val tab = _tabs.removeAt(index)
        postOnMain { tab.session.close() }

        // 校正 activeTabIndex
        when {
            index < activeTabIndex -> activeTabIndex--
            index == activeTabIndex && activeTabIndex >= _tabs.size -> activeTabIndex = _tabs.size - 1
        }

        // 从新激活标签同步状态
        if (_tabs.isNotEmpty()) {
            val target = _tabs[activeTabIndex]
            url = target.url
            pageTitle = target.title
        }

        postOnMain {
            onActiveTabChanged?.invoke(session)
        }

        return true
    }

    /** 关闭当前标签页 */
    fun closeCurrentTab(): Boolean = closeTab(activeTabIndex)

    // ══════════════════════════════════════════════════════════
    // Session 代理设置
    // ══════════════════════════════════════════════════════════

    private fun setupSessionDelegates(session: GeckoSession) {
        // ── 内容代理：标题、外部响应（下载）、上下文菜单等 ──
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                postOnMain {
                    pageTitle = title ?: ""
                    if (activeTabIndex < _tabs.size) {
                        _tabs[activeTabIndex] = _tabs[activeTabIndex].copy(title = pageTitle)
                    }
                }
            }

            override fun onExternalResponse(
                session: GeckoSession,
                response: WebResponse
            ) {
                // 拦截文件下载（非 HTML 资源）
                postOnMain {
                    val mimeType = response.headers["Content-Type"] ?: "application/octet-stream"
                    val contentLength = response.headers["Content-Length"]?.toLongOrNull() ?: -1L
                    onDownloadRequested?.invoke(
                        response.uri,
                        mimeType,
                        contentLength
                    )
                }
            }

            override fun onContextMenu(
                session: GeckoSession,
                screenX: Int,
                screenY: Int,
                element: GeckoSession.ContentDelegate.ContextElement
            ) {
                // 长按链接/图片等弹出上下文菜单
                postOnMain {
                    onContextMenuRequested?.invoke(
                        element.linkUri,
                        element.linkText,
                        element.srcUri,
                        when (element.type) {
                            GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE -> "image"
                            GeckoSession.ContentDelegate.ContextElement.TYPE_VIDEO -> "video"
                            GeckoSession.ContentDelegate.ContextElement.TYPE_AUDIO -> "audio"
                            else -> "link"
                        }
                    )
                }
            }
        }

        // ── 进度代理：页面加载状态 ────────────────────────
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                postOnMain {
                    isLoading = true
                    progress = 5
                    currentContent = ""
                }
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                postOnMain {
                    isLoading = false
                    progress = 100
                    if (success) {
                        // 记录历史
                        val currentUrl = this@GeckoEngine.url
                        if (currentUrl.isNotBlank()) {
                            _history.remove(currentUrl)
                            _history.add(currentUrl)
                            if (_history.size > 200) _history.removeAt(0)
                        }

                        // 提取页面内容
                        extractContent { content ->
                            currentContent = content
                            pendingContentCallback?.invoke(content)
                            pendingContentCallback = null
                        }
                    }
                }
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                postOnMain { this@GeckoEngine.progress = progress }
            }
        }

        // ── 导航代理：前进/后退/位置变更/广告过滤 ────────
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                postOnMain { this@GeckoEngine.canGoBack = canGoBack }
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                postOnMain { this@GeckoEngine.canGoForward = canGoForward }
            }

            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                permissions: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGranted: Boolean
            ) {
                postOnMain {
                    this@GeckoEngine.url = url ?: ""
                    if (activeTabIndex < _tabs.size) {
                        _tabs[activeTabIndex] = _tabs[activeTabIndex].copy(
                            url = this@GeckoEngine.url
                        )
                    }
                }
            }

            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<AllowOrDeny>? {
                // 广告过滤：在请求加载前拦截已知广告域名
                if (adBlockEnabled && request.uri != null) {
                    val uri = request.uri
                    if (AD_DOMAINS.any { domain -> uri.contains(domain, ignoreCase = true) }) {
                        return GeckoResult.fromValue(AllowOrDeny.DENY)
                    }
                }
                return null // null = 允许导航，由 GeckoView 自行决定
            }
        }

        }

    // ══════════════════════════════════════════════════════════
    // 导航方法
    // ══════════════════════════════════════════════════════════

    /** 加载指定 URL，状态自动更新 */
    fun loadUrl(url: String) {
        if (url.isBlank()) return
        this.url = url
        isClosed = false
        postOnMain {
            isLoading = true
            progress = 10
            currentContent = ""
            session.loadUri(url)
        }
    }

    /** 加载 URL 并同步等待提取内容（供后台工具线程使用） */
    fun loadUrlAndExtract(url: String, timeoutSec: Long = 30): String {
        val latch = CountDownLatch(1)
        var result = ""

        pendingContentCallback = { content ->
            result = content
            latch.countDown()
        }

        loadUrl(url)
        latch.await(timeoutSec, TimeUnit.SECONDS)

        if (result.isEmpty()) result = currentContent
        return result
    }

    /** 后退 */
    fun goBack() {
        isClosed = false
        postOnMain { session.goBack() }
    }

    /** 前进 */
    fun goForward() {
        isClosed = false
        postOnMain { session.goForward() }
    }

    /** 刷新当前页 */
    fun reload() {
        if (url.isNotBlank()) loadUrl(url)
    }

    /** 停止加载 */
    fun stop() {
        postOnMain { session.stop() }
    }

    // ══════════════════════════════════════════════════════════
    // 页面查找
    // ══════════════════════════════════════════════════════════

    /** 页面内查找 */
    fun findInPage(query: String) {
        if (query.isBlank()) {
            clearFindInPage()
            return
        }
        findQueryText = query
        isFindActive = true
        postOnMain {
            val finder = session.getFinder()
            // 设置显示选项：高亮所有匹配
            finder.setDisplayFlags(1) // FLAG_HIGHLIGHT_ALL
            // 执行查找（flags: 0 = 向前查找）
            finder.find(query, 0).accept({ result ->
                postOnMain {
                    if (result != null) {
                        findResultText = if (result.total == 0) "未找到"
                        else "第 ${result.current}/${result.total} 个匹配"
                    } else {
                        findResultText = "未找到"
                    }
                }
            })
        }
    }

    /** 清除查找高亮并关闭查找模式 */
    fun clearFindInPage() {
        isFindActive = false
        findQueryText = ""
        findResultText = ""
        postOnMain { session.getFinder().clear() }
    }

    // ══════════════════════════════════════════════════════════
    // 全屏模式
    // ══════════════════════════════════════════════════════════

    /** 切换全屏模式 */
    fun toggleFullscreen() {
        isFullscreen = !isFullscreen
    }

    /** 退出全屏模式 */
    fun exitFullscreen() {
        isFullscreen = false
    }

    /** 关闭浏览器所有标签页 */
    fun close() {
        isClosed = true
        postOnMain {
            _tabs.forEach { it.session.close() }
            _tabs.clear()
        }
    }

    /** 切换 User-Agent（移动端/桌面端），并立即应用到所有已打开的标签页 */
    fun toggleUserAgent() {
        userAgentMode = if (userAgentMode == "mobile") "desktop" else "mobile"
        // 将新 UA 应用到所有已存在的 session
        _tabs.forEach { applyUserAgentToSession(it.session) }
    }

    /** 清除历史记录 */
    fun clearHistory() {
        _history.clear()
    }

    /**
     * 将当前 User-Agent 模式应用到指定 session
     *
     * 设置 GeckoSessionSettings 的 USER_AGENT_MODE 和 VIEWPORT_MODE，
     * 使 Gecko 引擎使用对应的 User-Agent 字符串和视口行为。
     */
    private fun applyUserAgentToSession(session: GeckoSession) {
        val settings = session.settings
        if (userAgentMode == "desktop") {
            settings.setUserAgentMode(
                GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
            )
            settings.setViewportMode(
                GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
            )
        } else {
            settings.setUserAgentMode(
                GeckoSessionSettings.USER_AGENT_MODE_MOBILE
            )
            settings.setViewportMode(
                GeckoSessionSettings.VIEWPORT_MODE_MOBILE
            )
        }
    }

    // ══════════════════════════════════════════════════════════
    // 内容提取
    // ══════════════════════════════════════════════════════════

    /**
     * 通过 GeckoView 内置的 [SessionPageExtractor] 异步提取页面文本内容。
     *
     * GeckoView 151 移除了 evaluateJavascript API，
     * 内容提取必须走 SessionPageExtractor。
     */
    private fun extractContent(callback: (String) -> Unit) {
        session.getSessionPageExtractor().getPageContent()
            .withHandler(mainHandler)
            .accept { content ->
                val text = content?.trim()?.ifEmpty { null }
                    ?: "(页面暂无文本内容)"
                callback(text)
            }
    }

    // ══════════════════════════════════════════════════════════
    // 广告过滤域名列表（EasyList 精选子集）
    // ══════════════════════════════════════════════════════════

    companion object {
        /** 广告/追踪域名黑名单（EasyList 精选子集） */
        private val AD_DOMAINS = setOf(
            // Google 广告联盟
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "googleanalytics.com", "googletagmanager.com",
            "pagead2.googlesyndication.com", "adservice.google.com",
            "googleadsserving.cn",
            // 通用广告平台
            "ad-delivery.net", "adnxs.com", "adsrvr.org",
            "adserver.com", "adtech.de", "advertising.com", "adzerk.net",
            "amazon-adsystem.com", "amazonadsi.com",
            "appnexus.com", "casalemedia.com", "contextweb.com",
            "criteo.com", "criteo.net",
            "exelator.com", "facebook.com/tr",
            "imrworldwide.com", "indexww.com", "lijit.com",
            "moatads.com", "openx.net", "pubmatic.com",
            "quantserve.com", "rubiconproject.com", "scorecardresearch.com",
            "servedbyadbutler.com", "sharethis.com", "smaato.net",
            "taboola.com", "tapad.com",
            "tribalfusion.com", "turn.com",
            "yieldmo.com", "yieldtraffic.com", "yumenetworks.com",
            // 中文广告/统计
            "cnzz.com", "cnzz.mmstat.com", "growingio.com",
            "sensorsdata.cn", "tanx.com", "umeng.com", "umtrack.com",
            "ad.xiaomi.com", "mi.gdt.qq.com", "gdt.qq.com",
            "pangle.io", "pangleglobal.com",
            // 用户追踪
            "hotjar.com", "mouseflow.com", "fullstory.com",
            "crazyegg.com", "luckyorange.com", "clicktale.net",
            "optimizely.com", "mixpanel.com", "segment.io",
            "amplitude.com", "heap.io", "intercom.io",
            "newrelic.com", "datadoghq.com"
        )

        @Volatile
        private var instance: GeckoEngine? = null
        private var appContext: Context? = null

        /**
         * 初始化引擎上下文（必须在 [MainActivity.onCreate] 调用）
         */
        fun init(context: Context) {
            appContext = context.applicationContext
        }

        /**
         * 获取引擎实例（首次调用时会同步创建 GeckoRuntime）
         */
        @JvmStatic
        fun getInstance(): GeckoEngine {
            val ctx = appContext
                ?: throw IllegalStateException(
                    "GeckoEngine 未初始化。请在 MainActivity.onCreate 中调用 GeckoEngine.init(this)"
                )
            return instance ?: synchronized(this) {
                instance ?: GeckoEngine(ctx).also { instance = it }
            }
        }
    }

    // ── 内部工具 ────────────────────────────────────────────

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun postOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }
}
