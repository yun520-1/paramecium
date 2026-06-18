package com.heartflow.tool.builtin

import com.heartflow.app.browser.Bookmark
import com.heartflow.app.browser.BookmarkManager
import com.heartflow.tool.BaseTool
import com.heartflow.tool.ToolCategory
import com.heartflow.tool.ToolContext
import com.heartflow.tool.ToolResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * 浏览器工具 — 可被心虫 AI 调用的完整浏览器引擎
 *
 * 使用 GeckoView（Mozilla 完整浏览器引擎）渲染页面并执行 JS，
 * 适用于需要 JavaScript 渲染的复杂网页访问。
 *
 * 提供操作：
 * - `open` — 打开 URL，等待页面加载完成，返回页面文本
 * - `back` — 后退
 * - `forward` — 前进
 * - `reload` — 刷新
 * - `close` — 关闭浏览器
 * - `get_content` — 获取当前页面文本内容（不重新加载）
 * - `get_info` — 获取当前页面信息（URL、标题、历史等）
 * - `bookmark_current` — 收藏当前页面
 * - `bookmarks_list` — 列出所有书签
 * - `bookmark_remove` — 移除指定书签
 * - `find_in_page` — 在页面内查找文字
 * - `find_clear` — 清除查找高亮
 * - `get_links` — 提取页面所有链接 URL
 *
 * 与轻量级 [WebFetchToolImpl] 不同，本工具可以渲染 JS 密集页面。
 */
class BrowserTool : BaseTool() {

    override fun getName(): String = "browser"

    override fun getDescription(): String =
        "使用完整浏览器引擎打开网页。支持 JS 渲染，可访问复杂网页。" +
        "操作：open（打开URL）、back（后退）、forward（前进）、reload（刷新）、" +
        "close（关闭）、get_content（获取当前页面内容）、get_info（获取页面信息）、" +
        "bookmark_current（收藏当前页）、bookmarks_list（列出书签）、" +
        "bookmark_remove（移除书签）、find_in_page（页面查找）、" +
        "find_clear（清除查找）、get_links（提取页面链接）。"

    override fun getCategory(): ToolCategory = ToolCategory.BROWSER

    override fun requiresConfirmation(): Boolean = true

    override fun getParameters(): JSONObject {
        val actions = JSONArray()
            .put("open").put("back").put("forward").put("reload")
            .put("close").put("get_content").put("get_info")
            .put("bookmark_current").put("bookmarks_list").put("bookmark_remove")
            .put("find_in_page").put("find_clear").put("get_links")

        val properties = JSONObject()
        properties.put("action", JSONObject().put("type", "string")
            .put("description", "操作类型，可用操作见 description")
            .put("enum", actions))
        properties.put("url", JSONObject().put("type", "string")
            .put("description", "要打开的网页 URL（仅 action=open 时需要）"))
        properties.put("query", JSONObject().put("type", "string")
            .put("description", "查找关键词（仅 action=find_in_page 时需要）"))
        properties.put("bookmark_url", JSONObject().put("type", "string")
            .put("description", "要移除的书签 URL（仅 action=bookmark_remove 时需要）"))
        return JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .put("required", JSONArray().put("action"))
    }

    override fun execute(input: JSONObject, context: ToolContext): ToolResult {
        val action = input.optString("action", "open").trim().lowercase()

        return try {
            val engine = GeckoEngine.getInstance()

            when (action) {
                "open" -> executeOpen(engine, input)
                "back" -> {
                    engine.goBack()
                    ok("浏览器已后退。请使用 action=open 或 action=get_content 获取当前页面内容。")
                }
                "forward" -> {
                    engine.goForward()
                    ok("浏览器已前进。请使用 action=open 或 action=get_content 获取当前页面内容。")
                }
                "reload" -> {
                    engine.reload()
                    ok("浏览器正在刷新当前页面。请使用 action=open 或 action=get_content 获取内容。")
                }
                "close" -> {
                    engine.close()
                    ok("浏览器会话已关闭。下次 action=open 将新建会话。")
                }
                "get_content" -> executeGetContent(engine)
                "get_info" -> executeGetInfo(engine)
                "bookmark_current" -> executeBookmarkCurrent(engine)
                "bookmarks_list" -> executeBookmarksList()
                "bookmark_remove" -> executeBookmarkRemove(input)
                "find_in_page" -> executeFindInPage(engine, input)
                "find_clear" -> {
                    engine.clearFindInPage()
                    ok("已清除页面查找高亮。")
                }
                "get_links" -> executeGetLinks(engine)
                else -> error("不支持的操作: $action。可用操作：open, back, forward, reload, close, get_content, get_info, bookmark_current, bookmarks_list, bookmark_remove, find_in_page, find_clear, get_links")
            }
        } catch (e: Exception) {
            error("浏览器操作失败: ${e.message}")
        }
    }

    // ══════════════════════════════════════════════════════════
    // 页面操作
    // ══════════════════════════════════════════════════════════

    private fun executeOpen(engine: GeckoEngine, input: JSONObject): ToolResult {
        val url = input.optString("url").trim()
        if (url.isEmpty()) {
            return error("action=open 时 URL 不能为空。")
        }
        val formattedUrl = normalizeUrl(url)
        val content = engine.loadUrlAndExtract(formattedUrl)

        val sb = StringBuilder()
        sb.appendLine("=== 浏览器结果 ===")
        sb.appendLine("URL: ${engine.url}")
        sb.appendLine("标题: ${engine.pageTitle}")
        sb.appendLine("进度: ${engine.progress}%")
        sb.appendLine("标签页数: ${engine.tabs.size}")
        sb.appendLine()
        if (content.isNotEmpty()) {
            sb.appendLine("--- 页面内容 ---")
            sb.appendLine(content)
        }
        return ok(sb.toString())
    }

    private fun executeGetContent(engine: GeckoEngine): ToolResult {
        val content = if (engine.currentContent.isNotBlank()) {
            engine.currentContent
        } else {
            "(当前页面无内容)"
        }
        return buildString {
            appendLine("=== 当前页面内容 ===")
            appendLine("URL: ${engine.url}")
            appendLine("标题: ${engine.pageTitle}")
            appendLine()
            appendLine(content)
        }.let { ok(it) }
    }

    private fun executeGetInfo(engine: GeckoEngine): ToolResult {
        return buildString {
            appendLine("=== 浏览器状态 ===")
            appendLine("URL: ${engine.url}")
            appendLine("标题: ${engine.pageTitle}")
            appendLine("加载中: ${engine.isLoading}")
            appendLine("进度: ${engine.progress}%")
            appendLine("可后退: ${engine.canGoBack}")
            appendLine("可前进: ${engine.canGoForward}")
            appendLine("标签页数: ${engine.tabs.size}")
            appendLine("活跃标签: ${engine.activeTabIndex + 1}")
            appendLine("UA模式: ${engine.userAgentMode}")
            appendLine("广告过滤: ${if (engine.adBlockEnabled) "开" else "关"}")
            appendLine("历史记录: ${engine.history.size}条")
        }.let { ok(it) }
    }

    // ══════════════════════════════════════════════════════════
    // 书签操作
    // ══════════════════════════════════════════════════════════

    private fun getBookmarkManager(): BookmarkManager? {
        return try {
            BookmarkManager.getInstance()
        } catch (e: Exception) {
            null
        }
    }

    /** 收藏当前页面 */
    private fun executeBookmarkCurrent(engine: GeckoEngine): ToolResult {
        val bm = getBookmarkManager()
        if (bm == null) return error("书签管理器未初始化，请先打开浏览器并访问页面。")

        val url = engine.url
        if (url.isBlank()) {
            return error("当前没有打开的页面。请先使用 action=open 打开一个页面。")
        }

        val title = engine.pageTitle.ifEmpty { url }
        val isNowBookmarked = bm.toggleBookmark(url, title)

        return if (isNowBookmarked) {
            ok("✅ 已收藏当前页面：$title\nURL: $url\n当前书签数：${bm.count()}")
        } else {
            ok("已取消收藏：$title\n当前书签数：${bm.count()}")
        }
    }

    /** 列出所有书签 */
    private fun executeBookmarksList(): ToolResult {
        val bm = getBookmarkManager()
        if (bm == null) return error("书签管理器未初始化。")

        val bookmarks = bm.getAllBookmarks()
        if (bookmarks.isEmpty()) {
            return ok("暂无书签。浏览网页时使用 bookmark_current 操作可收藏当前页面。")
        }

        val sb = StringBuilder()
        sb.appendLine("=== 书签列表 (${bookmarks.size}条) ===")
        sb.appendLine()
        bookmarks.forEachIndexed { index, bookmark ->
            sb.appendLine("${index + 1}. ${bookmark.title.ifEmpty { "(无标题)" }}")
            sb.appendLine("   URL: ${bookmark.url}")
        }
        return ok(sb.toString())
    }

    /** 移除指定书签 */
    private fun executeBookmarkRemove(input: JSONObject): ToolResult {
        val bm = getBookmarkManager()
        if (bm == null) return error("书签管理器未初始化。")

        val url = input.optString("bookmark_url").trim()
        if (url.isEmpty()) {
            return error("action=bookmark_remove 时 URL 不能为空。")
        }

        if (!bm.isBookmarked(url)) {
            return ok("该书签不存在：$url")
        }

        bm.removeBookmark(url)
        return ok("✅ 已移除书签：$url\n当前书签数：${bm.count()}")
    }

    // ══════════════════════════════════════════════════════════
    // 页面查找
    // ══════════════════════════════════════════════════════════

    /** 页面内查找文字 */
    private fun executeFindInPage(engine: GeckoEngine, input: JSONObject): ToolResult {
        val query = input.optString("query").trim()
        if (query.isEmpty()) {
            return error("action=find_in_page 时 query（查找关键词）不能为空。")
        }

        engine.findInPage(query)

        // 等待片刻让 GeckoView 完成查找并返回结果
        Thread.sleep(500)

        val resultText = engine.findResultText
        return if (resultText.isNotEmpty()) {
            ok("查找「$query」${resultText}")
        } else {
            ok("正在查找「$query」... 请稍后使用 action=get_info 查看当前页面信息。")
        }
    }

    // ══════════════════════════════════════════════════════════
    // 链接提取
    // ══════════════════════════════════════════════════════════

    /** 从当前页面提取所有链接 URL */
    private fun executeGetLinks(engine: GeckoEngine): ToolResult {
        val content = engine.currentContent
        if (content.isBlank() || content == "(页面暂无文本内容)") {
            return ok("当前页面无可提取的文本内容。请先使用 action=open 打开一个页面。")
        }

        // 从页面文本内容中提取 URL 链接
        val links = extractUrls(content)

        if (links.isEmpty()) {
            return ok("当前页面文本中未发现链接。")
        }

        val sb = StringBuilder()
        sb.appendLine("=== 页面链接 (${links.size}个) ===")
        sb.appendLine("URL: ${engine.url}")
        sb.appendLine("标题: ${engine.pageTitle}")
        sb.appendLine()
        links.forEachIndexed { index, link ->
            sb.appendLine("${index + 1}. $link")
        }
        return ok(sb.toString())
    }

    /**
     * 从文本中提取 URL 链接
     *
     * 由于 GeckoView 151 移除了 evaluateJavascript API，
     * 无法通过 JS 直接获取 DOM 中的所有链接。
     * 改为从页面文本内容中提取 HTTP/HTTPS URL。
     */
    private fun extractUrls(text: String): List<String> {
        // URL 正则匹配：http/https/ftp 等协议链接
        val regex = Regex(
            """https?://[a-zA-Z0-9\-._~:/?#\[\]@!$&'()*+,;%=]+[a-zA-Z0-9\-._~:/?#\[\]@!$&'()*+,;%=]"""
        )
        // 常见 URL 结尾字符过滤
        val urlEndChars = setOf('.', ',', '!', '?', ':', ';', ')', ']', '>', '"', '\'', '）', '」', '》')

        val rawUrls = regex.findAll(text).map { it.value }.toList()

        // 去重 + 清理尾部标点 + 过滤
        val seen = mutableSetOf<String>()
        val cleaned = mutableListOf<String>()

        for (url in rawUrls) {
            var clean = url
            // 去除末尾常见标点
            while (clean.isNotEmpty() && clean.last() in urlEndChars) {
                clean = clean.dropLast(1)
            }
            // 去重
            if (clean.isNotBlank() && clean !in seen) {
                seen.add(clean)
                cleaned.add(clean)
            }
        }

        return cleaned
    }

    // ══════════════════════════════════════════════════════════
    // 工具方法
    // ══════════════════════════════════════════════════════════

    /**
     * 自动补全 URL 格式
     */
    private fun normalizeUrl(url: String): String {
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.contains(".") -> "https://$url"
            else -> "https://www.baidu.com/s?wd=$url"
        }
    }

    private fun ok(content: String): ToolResult = ToolResult("", getName(), content, false)

    private fun error(message: String): ToolResult = ToolResult("", getName(), message, true)
}
