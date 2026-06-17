package com.heartflow.tool.builtin

import com.heartflow.tool.BaseTool
import com.heartflow.tool.ToolCategory
import com.heartflow.tool.ToolContext
import com.heartflow.tool.ToolResult
import org.json.JSONObject

/**
 * 网页内容获取工具
 * - 抓取并提取网页文本内容
 * - 支持 maxChars 限制返回长度
 */
class WebFetchToolImpl : BaseTool() {
    private val webSearchService = WebSearchService()

    override fun getName(): String = "web_fetch"

    override fun getDescription(): String =
        "查看并提取指定网页的文本内容。URL 必须使用 HTTPS，或使用 localhost/127.0.0.1/10.0.2.2 的 HTTP。"

    override fun getCategory(): ToolCategory = ToolCategory.READ

    override fun getParameters(): JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("url", JSONObject().put("type", "string").put("description", "要查看的网页 URL"))
            .put("maxChars", JSONObject().put("type", "number").put("description", "最多返回字符数，默认 12000，最大 30000")))
        .put("required", org.json.JSONArray().put("url"))

    override fun execute(input: JSONObject, context: ToolContext): ToolResult {
        val url = input.optString("url").trim()
        if (url.isEmpty()) {
            return error("URL 不能为空。")
        }
        return try {
            val content = webSearchService.fetchPage(url, input.optInt("maxChars", 12000))
            ok("URL: $url\n\n$content")
        } catch (e: Exception) {
            error("网页查看失败: ${e.message}")
        }
    }

    private fun ok(content: String): ToolResult = ToolResult("", getName(), content, false)

    private fun error(content: String): ToolResult = ToolResult("", getName(), content, true)
}
