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
 * 提供两个操作：
 * - `browser_open(url)` — 打开 URL，等待页面加载完成，返回页面文本
 * - `browser_action(action, url)` — 执行导航操作（back/forward/reload/close）
 *
 * 与轻量级 [WebFetchToolImpl] 不同，本工具可以渲染 JS 密集页面。
 */
class BrowserTool : BaseTool() {

    override fun getName(): String = "browser"

    override fun getDescription(): String =
        "使用完整浏览器引擎打开网页。支持 JS 渲染，可访问复杂网页。返回页面文本内容。操作：open（打开URL）、back（后退）、forward（前进）、reload（刷新）、close（关闭）。"

    override fun getCategory(): ToolCategory = ToolCategory.BROWSER

    override fun requiresConfirmation(): Boolean = true

    override fun getParameters(): JSONObject {
        val properties = JSONObject()
        properties.put("action", JSONObject().put("type", "string")
            .put("description", "操作类型：open（打开网页）、back（后退）、forward（前进）、reload（刷新）、close（关闭）")
            .put("enum", JSONArray().put("open").put("back").put("forward").put("reload").put("close")))
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
                "open" -> {
                    val url = input.optString("url").trim()
                    if (url.isEmpty()) {
                        return error("action=open 时 URL 不能为空。")
                    }
                    val formattedUrl = normalizeUrl(url)
                    val content = engine.loadUrlAndExtract(formattedUrl)

                    // 构建返回信息
                    val sb = StringBuilder()
                    sb.appendLine("=== 浏览器结果 ===")
                    sb.appendLine("URL: ${engine.url}")
                    sb.appendLine("标题: ${engine.pageTitle}")
                    sb.appendLine("进度: ${engine.progress}%")
                    sb.appendLine()
                    if (content.isNotEmpty()) {
                        sb.appendLine("--- 页面内容 ---")
                        sb.appendLine(content)
                    }
                    ok(sb.toString())
                }

                "back" -> {
                    engine.goBack()
                    ok("浏览器已后退。请使用 action=open 重新打开当前页面获取内容。")
                }

                "forward" -> {
                    engine.goForward()
                    ok("浏览器已前进。请使用 action=open 重新打开当前页面获取内容。")
                }

                "reload" -> {
                    engine.reload()
                    ok("浏览器正在刷新当前页面。请使用 action=open 重新获取内容。")
                }

                "close" -> {
                    engine.close()
                    ok("浏览器会话已关闭。下次 action=open 将新建会话。")
                }

                else -> error("不支持的操作: $action。可用操作：open, back, forward, reload, close")
            }
        } catch (e: Exception) {
            error("浏览器操作失败: ${e.message}")
        }
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
