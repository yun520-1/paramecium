package com.heartflow.tool.builtin

import com.heartflow.tool.BaseTool
import com.heartflow.tool.ToolCategory
import com.heartflow.tool.ToolContext
import com.heartflow.tool.ToolResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 文件删除工具
 * - 需要提供删除原因
 * - 需要用户确认
 * - 递归删除目录
 */
class FileDeleteTool : BaseTool() {
    override fun getName(): String = "file_delete"

    override fun getDescription(): String = "删除文件或目录。必须提供删除原因 reason；执行前会请求用户确认。"

    override fun getCategory(): ToolCategory = ToolCategory.WRITE

    override fun requiresConfirmation(): Boolean = true

    override fun getParameters(): JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("reason", JSONObject().put("type", "string").put("description", "删除原因，会展示给用户确认"))
            .put("paths", JSONObject()
                .put("type", "array")
                .put("items", JSONObject().put("type", "string"))
                .put("description", "要删除的文件或目录路径列表")))
        .put("required", org.json.JSONArray().put("reason").put("paths"))

    override fun execute(input: JSONObject, context: ToolContext): ToolResult {
        val paths = paths(input)
        val reason = input.optString("reason").trim()
        if (reason.isEmpty()) {
            return error("删除原因 reason 不能为空")
        }
        if (paths.isEmpty()) {
            return error("paths 不能为空")
        }

        val deleted = mutableListOf<String>()
        val errors = mutableListOf<String>()
        for (path in paths) {
            try {
                val target = FileToolPathPolicy.resolve(context, path)
                if (!target.exists()) {
                    errors.add("路径不存在: $path")
                    continue
                }
                deleteRecursive(target)
                deleted.add(FileToolPathPolicy.displayPath(context.homePath, target))
            } catch (e: Exception) {
                errors.add("删除 $path 失败: " + e.message)
            }
        }

        val builder = StringBuilder()
        if (deleted.isNotEmpty()) {
            builder.append("成功删除 ").append(deleted.size).append(" 项:\n")
            for (path in deleted) {
                builder.append("- ").append(path).append('\n')
            }
        }
        if (errors.isNotEmpty()) {
            if (builder.isNotEmpty()) {
                builder.append('\n')
            }
            builder.append("失败 ").append(errors.size).append(" 项:\n")
            for (err in errors) {
                builder.append("- ").append(err).append('\n')
            }
        }
        return ToolResult(
            "",
            getName(),
            if (builder.isEmpty()) "没有删除任何文件" else builder.toString().trim(),
            errors.isNotEmpty() && deleted.isEmpty()
        )
    }

    private fun paths(input: JSONObject): List<String> {
        val values = mutableListOf<String>()
        val array = input.optJSONArray("paths")
        if (array != null) {
            for (i in 0 until array.length()) {
                val value = array.optString(i).trim()
                if (value.isNotEmpty()) {
                    values.add(value)
                }
            }
        }
        val filePath = input.optString("file_path").trim()
        if (filePath.isNotEmpty()) {
            values.add(filePath)
        }
        val path = input.optString("path").trim()
        if (path.isNotEmpty()) {
            values.add(path)
        }
        return values
    }

    @Throws(java.io.IOException::class)
    private fun deleteRecursive(file: File) {
        if (file.isDirectory) {
            val children = file.listFiles()
            if (children != null) {
                for (child in children) {
                    deleteRecursive(child)
                }
            }
        }
        if (!file.delete()) {
            throw java.io.IOException("无法删除 " + file.path)
        }
    }

    private fun error(content: String): ToolResult = ToolResult("", getName(), content, true)
}
