package com.heartflow.tool.builtin

import com.heartflow.tool.BaseTool
import com.heartflow.tool.ToolCategory
import com.heartflow.tool.ToolContext
import com.heartflow.tool.ToolResult
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

/**
 * 文件编辑工具
 * - old_string/new_string 搜索替换
 * - 替换所有匹配项
 */
class FileEditTool : BaseTool() {
    override fun getName(): String = "file_edit"

    override fun getDescription(): String = "编辑文件内容。通过 old_string/new_string 搜索替换修改文件。"

    override fun getCategory(): ToolCategory = ToolCategory.WRITE

    override fun getParameters(): JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("file_path", JSONObject().put("type", "string").put("description", "文件的绝对或相对路径"))
            .put("old_string", JSONObject().put("type", "string").put("description", "要搜索的原始文本，必须唯一或明确"))
            .put("new_string", JSONObject().put("type", "string").put("description", "替换后的新文本")))
        .put("required", org.json.JSONArray().put("file_path").put("old_string").put("new_string"))

    override fun execute(input: JSONObject, context: ToolContext): ToolResult {
        return try {
            val path = input.optString("file_path")
            val oldString = input.optString("old_string")
            val newString = input.optString("new_string")
            if (path.trim().isEmpty()) {
                return error("file_path 不能为空")
            }
            if (oldString.isEmpty()) {
                return error("old_string 不能为空")
            }
            val file = FileToolPathPolicy.resolve(context, path)
            if (!file.exists()) {
                return error("文件不存在: " + FileToolPathPolicy.displayPath(context.homePath, file))
            }
            if (file.isDirectory) {
                return error("路径是一个目录，无法编辑文件: $path\n如需编辑文件，请指定具体文件路径。")
            }
            val content = readUtf8(file)
            if (!content.contains(oldString)) {
                return error("未找到匹配的文本")
            }
            val count = countOccurrences(content, oldString)
            val next = content.replace(oldString, newString)
            FileOutputStream(file, false).use { output ->
                output.write(next.toByteArray(StandardCharsets.UTF_8))
            }
            ok("成功编辑文件 " + FileToolPathPolicy.displayPath(context.homePath, file)
                + " (" + count + " 处匹配已替换)")
        } catch (e: Exception) {
            error("编辑文件失败: " + e.message)
        }
    }

    @Throws(Exception::class)
    private fun readUtf8(file: File): String {
        FileInputStream(file).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                output.write(buffer, 0, read)
            }
            return output.toString(StandardCharsets.UTF_8.name())
        }
    }

    private fun countOccurrences(content: String, value: String): Int {
        var count = 0
        var index = 0
        while (content.indexOf(value, index).also { index = it } >= 0) {
            count++
            index += value.length
        }
        return count
    }

    private fun ok(content: String): ToolResult = ToolResult("", getName(), content, false)

    private fun error(content: String): ToolResult = ToolResult("", getName(), content, true)
}
