package com.heartflow.tool.builtin

import com.heartflow.tool.BaseTool
import com.heartflow.tool.ToolCategory
import com.heartflow.tool.ToolContext
import com.heartflow.tool.ToolResult
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

/**
 * 文件写入工具
 * - 自动创建父目录
 * - 覆盖模式写入
 */
class FileWriteTool : BaseTool() {
    override fun getName(): String = "file_write"

    override fun getDescription(): String = "将内容写入文件。如果文件或目录不存在会自动创建。"

    override fun getCategory(): ToolCategory = ToolCategory.WRITE

    override fun getParameters(): JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("file_path", JSONObject().put("type", "string").put("description", "文件的绝对或相对路径"))
            .put("content", JSONObject().put("type", "string").put("description", "要写入的内容")))
        .put("required", org.json.JSONArray().put("file_path").put("content"))

    override fun execute(input: JSONObject, context: ToolContext): ToolResult {
        return try {
            val path = input.optString("file_path")
            if (path.trim().isEmpty()) {
                return error("file_path 不能为空")
            }
            val file = FileToolPathPolicy.resolve(context, path)
            if (file.exists() && file.isDirectory) {
                return error("路径是一个目录，无法写入文件: $path\n如需创建文件，请指定完整文件路径。")
            }
            val parent = file.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return error("无法创建父目录: " + parent.path)
            }
            val existed = file.exists()
            val content = input.optString("content")
            FileOutputStream(file, false).use { output ->
                output.write(content.toByteArray(StandardCharsets.UTF_8))
            }
            val lineCount = content.split("\n").size
            ok((if (existed) "成功更新文件 " else "成功创建文件 ") + path + " (" + lineCount + " 行)")
        } catch (e: Exception) {
            error("写入文件失败: " + e.message)
        }
    }

    private fun ok(content: String): ToolResult = ToolResult("", getName(), content, false)

    private fun error(content: String): ToolResult = ToolResult("", getName(), content, true)
}
