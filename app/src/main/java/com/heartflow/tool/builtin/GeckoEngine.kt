package com.heartflow.tool.builtin

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.ContentDelegate
import org.mozilla.geckoview.GeckoSession.NavigationDelegate
import org.mozilla.geckoview.GeckoSession.ProgressDelegate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * GeckoView 浏览器引擎单例
 *
 * 管理 GeckoRuntime + GeckoSession 的完整生命周期。
 * 状态通过 Compose mutableStateOf 暴露，可被 Compose UI 自动观察。
 * 支持同步内容提取（供后台工具线程使用）和异步观察（供 UI 层使用）。
 *
 * 使用方式：
 *   1. [MainActivity.onCreate] 中调用 GeckoEngine.init(this)
 *   2. 工具调用 GeckoEngine.getInstance()
 *   3. UI 层直接从 engine 实例读取状态属性
 *
 * GeckoView 151 移除了 evaluateJavascript API，内容提取改用
 * [SessionPageExtractor.getPageContent]，JS 执行需走 WebExtension。
 */
class GeckoEngine private constructor(context: Context) {

    // ── 运行时 ──────────────────────────────────────────
    val runtime: GeckoRuntime
    val session: GeckoSession = GeckoSession()

    // ── Observable 状态（Compose 可自动观察）───────────
    var url by mutableStateOf("")
    var pageTitle by mutableStateOf("")
    var isLoading by mutableStateOf(false)
    var progress by mutableIntStateOf(0)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var currentContent by mutableStateOf("")

    // 页面加载完成后待提取内容的回调池
    private var pendingContentCallback: ((String) -> Unit)? = null

    // 用户是否手动关闭了浏览器（防止工具在被关闭的 session 上操作）
    @Volatile
    var isClosed = false
        private set

    init {
        // GeckoRuntime 设置：允许混合内容 + 调试
        val settings = GeckoRuntimeSettings.Builder()
            .remoteDebuggingEnabled(true)
            .build()
        runtime = GeckoRuntime.create(context, settings)
        session.open(runtime)

        // ── 内容代理：标题、预览等 ──────────────────────
        session.contentDelegate = object : ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                postOnMain { pageTitle = title ?: "" }
            }
        }

        // ── 进度代理：页面加载状态 ──────────────────────
        session.progressDelegate = object : ProgressDelegate {
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
                        // 页面加载完成 → 通过 SessionPageExtractor 提取内容
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

        // ── 导航代理：前进/后退能力 ────────────────────
        session.navigationDelegate = object : NavigationDelegate {
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
                postOnMain { this@GeckoEngine.url = url ?: "" }
            }
        }
    }

    // ── 导航方法 ────────────────────────────────────────

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

        // 超时时用已有缓存
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

    /** 关闭浏览器会话 */
    fun close() {
        isClosed = true
        postOnMain {
            session.close()
        }
    }

    // ── 内容提取 ────────────────────────────────────────

    /**
     * 通过 GeckoView 内置的 [PageExtractionController.SessionPageExtractor]
     * 异步提取页面文本内容。
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

    // ── 内部工具 ────────────────────────────────────────

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun postOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    // ── 单例 ────────────────────────────────────────────

    companion object {
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
}
