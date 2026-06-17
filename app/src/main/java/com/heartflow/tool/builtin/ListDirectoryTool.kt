package com.heartflow.tool.builtin

import com.heartflow.tool.BaseTool
import com.heartflow.tool.ToolCategory
import com.heartflow.tool.ToolContext
import com.heartflow.tool.ToolResult
import org.json.JSONObject
import java.io.File

/**
 * 目录列表工具
 * - 列出目录下的直接文件/文件夹
 * - 按名称排序，目录优先
 */
class ListDirectoryTool : BaseTool() {
    override fun getName(): String = "list_dir"

    override fun getDescription(): String = "列出目录下的直接文件和文件夹。"

    override fun getCategory(): ToolCategory = ToolCategory.READ

    override fun getParameters(): JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("path", JSONObject().put("type", "string").put("description", "目录的绝对或相对路径，可选，默认为 home 目录")))

    override fun execute(input: JSONObject, context: ToolContext): ToolResult {
        return try {
            val dir = FileToolPathPolicy.resolve(context, input.optString("path"))
            if (!dir.exists()) {
                return error("目录不存在: " + input.optString("path", "."))
            }
            if (!dir.isDirectory) {
                return error("路径不是目录: " + input.optString("path", "."))
            }
            val items = dir.listFiles()
            if (items == null || items.isEmpty()) {
                return ok("目录 " + FileToolPathPolicy.displayPath(context.homePath, dir) + ":\n(空目录)")
            }
            items.sortWith(Comparator { a, b ->
                when {
                    a.isDirectory != b.isDirectory -> if (a.isDirectory) -1 else 1
                    else -> a.name.compareTo(b.name, ignoreCase = true)
                }
            })
            val builder = StringBuilder()
            builder.append("目录 ").append(FileToolPathPolicy.displayPath(context.homePath, dir)).append(":\n")
            for (item in items) {
                builder.append(if (item.isDirectory) "[DIR]  " else "[FILE] ")
                    .append(item.name)
                    .append(if (item.isDirectory) "/" else "")
                    .append('\n')
            }
            ok(builder.toString().trim())
        } catch (e: Exception) {
            error("列目录失败: " + e.message)
        }
    }

    private fun ok(content: String): ToolResult = ToolResult("", getName(), content, false)

    private fun error(content: String): ToolResult = ToolResult("", getName(), content, true)
}
