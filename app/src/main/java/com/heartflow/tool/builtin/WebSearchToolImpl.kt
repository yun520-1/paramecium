package com.heartflow.tool.builtin

import com.heartflow.tool.BaseTool
import com.heartflow.tool.ToolCategory
import com.heartflow.tool.ToolContext
import com.heartflow.tool.ToolResult
import org.json.JSONObject

/**
 * 网页搜索工具
 * - 需要用户先在设置中配置搜索 API
 * - 返回格式化的搜索结果
 */
class WebSearchToolImpl : BaseTool() {
    private val webSearchService = WebSearchService()

    override fun getName(): String = "web_search"

    override fun getDescription(): String =
        "搜索互联网信息。需要用户先在设置中配置搜索 API、模型/搜索源和密钥。适合查询最新事实、文档、新闻和网页资料。"

    override fun getCategory(): ToolCategory = ToolCategory.READ

    override fun getParameters(): JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("query", JSONObject().put("type", "string").put("description", "搜索关键词或问题"))
            .put("limit", JSONObject().put("type", "number").put("description", "返回结果数量，1-10，默认 5")))
        .put("required", org.json.JSONArray().put("query"))

    override fun execute(input: JSONObject, context: ToolContext): ToolResult {
        val query = input.optString("query").trim()
        if (query.isEmpty()) {
            return error("搜索关键词不能为空。")
        }
        val limit = input.optInt("limit", 5)
        return try {
            val results = webSearchService.search(WebSearchConfig.defaultConfig(), query, limit)
            if (results.isEmpty()) {
                return ok("未搜索到与 \"$query\" 相关的网页结果。")
            }
            val content = StringBuilder()
            results.forEachIndexed { i, item ->
                if (i > 0) content.append("\n\n")
                content.append("${i + 1}. ${item.title}\n")
                    .append("URL: ${item.url}")
                if (item.publishedDate.isNotEmpty()) {
                    content.append("\nDate: ${item.publishedDate}")
                }
                if (item.snippet.isNotEmpty()) {
                    content.append("\nSnippet: ${item.snippet}")
                }
            }
            ok(content.toString())
        } catch (e: Exception) {
            error("网页搜索失败: ${e.message}")
        }
    }

    private fun ok(content: String): ToolResult = ToolResult("", getName(), content, false)

    private fun error(content: String): ToolResult = ToolResult("", getName(), content, true)
}
