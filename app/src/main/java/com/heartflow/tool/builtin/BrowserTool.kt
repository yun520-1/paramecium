package com.heartflow.tool.builtin

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
 *
 * 与轻量级 [WebFetchToolImpl] 不同，本工具可以渲染 JS 密集页面。
 */
class BrowserTool : BaseTool() {

    override fun getName(): String = "browser"

    override fun getDescription(): String =
        "使用完整浏览器引擎打开网页。支持 JS 渲染，可访问复杂网页。" +
        "操作：open（打开URL）、back（后退）、forward（前进）、reload（刷新）、" +
        "close（关闭）、get_content（获取当前页面内容）、get_info（获取页面信息）。"

    override fun getCategory(): ToolCategory = ToolCategory.BROWSER

    override fun requiresConfirmation(): Boolean = true

    override fun getParameters(): JSONObject {
        val actions = JSONArray()
            .put("open").put("back").put("forward").put("reload")
            .put("close").put("get_content").put("get_info")

        val properties = JSONObject()
        properties.put("action", JSONObject().put("type", "string")
            .put("description", "操作类型，可用操作见 description")
            .put("enum", actions))
        properties.put("url", JSONObject().put("type", "string")
            .put("description", "要打开的网页 URL（仅 action=open 时需要）"))
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
                "get_content" -> {
                    val content = if (engine.currentContent.isNotBlank()) {
                        engine.currentContent
                    } else {
                        "(当前页面无内容)"
                    }
                    buildString {
                        appendLine("=== 当前页面内容 ===")
                        appendLine("URL: ${engine.url}")
                        appendLine("标题: ${engine.pageTitle}")
                        appendLine()
                        appendLine(content)
                    }.let { ok(it) }
                }
                "get_info" -> {
                    buildString {
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
                else -> error("不支持的操作: $action。可用操作：open, back, forward, reload, close, get_content, get_info")
            }
        } catch (e: Exception) {
            error("浏览器操作失败: ${e.message}")
        }
    }

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
