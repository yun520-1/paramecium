package com.heartflow.tool.builtin

import com.heartflow.tool.BaseTool
import com.heartflow.tool.ToolCategory
import com.heartflow.tool.ToolContext
import com.heartflow.tool.ToolResult
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.charset.StandardCharsets

/**
 * 文件读取工具
 * - 支持大文件分段读取（50KB 阈值）
 * - 支持 start_line/end_line 行号范围
 * - 读取目录时返回目录树
 */
class FileReadTool : BaseTool() {
    companion object {
        private const val LARGE_FILE_THRESHOLD_BYTES = 50L * 1024L
        private const val DEFAULT_LIMIT = 2000
        private const val MAX_DIRECTORY_ITEMS = 400
    }

    override fun getName(): String = "file_read"

    override fun getDescription(): String =
        "读取文件内容。返回带行号的文件内容；大文件请通过 start_line/end_line 分段读取。读取目录时返回目录树。"

    override fun getCategory(): ToolCategory = ToolCategory.READ

    override fun getParameters(): JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("file_path", JSONObject().put("type", "string").put("description", "文件的绝对或相对路径"))
            .put("start_line", JSONObject().put("type", "number").put("description", "起始行号，从 1 开始，包含该行"))
            .put("end_line", JSONObject().put("type", "number").put("description", "结束行号，从 1 开始，包含该行"))
            .put("offset", JSONObject().put("type", "number").put("description", "兼容旧参数：起始行偏移，从 0 开始"))
            .put("limit", JSONObject().put("type", "number").put("description", "兼容旧参数：最大读取行数，默认 2000")))
        .put("required", org.json.JSONArray().put("file_path"))

    override fun execute(input: JSONObject, context: ToolContext): ToolResult {
        return try {
            val file = FileToolPathPolicy.resolve(context, input.optString("file_path"))
            if (!file.exists()) {
                return error("文件不存在: " + FileToolPathPolicy.displayPath(context.homePath, file))
            }
            if (file.isDirectory) {
                val builder = StringBuilder()
                val count = IntArray(1) { 0 }
                appendDirectory(builder, file, "", count)
                val list = if (builder.isEmpty()) "(空目录)" else builder.toString().trim()
                return ok("目录 " + FileToolPathPolicy.displayPath(context.homePath, file) + ":\n" + list
                    + "\n\n如需读取文件，请指定具体文件路径。")
            }
            val hasLineRange = input.has("start_line") || input.has("end_line")
            if (!hasLineRange && file.length() > LARGE_FILE_THRESHOLD_BYTES) {
                return error("文件 " + FileToolPathPolicy.displayPath(context.homePath, file)
                    + " 大小为 " + file.length() + " bytes，单次读取超过 50KB。\n"
                    + "请使用 start_line 和 end_line 指定行号范围，例如："
                    + "{\"file_path\":\"" + input.optString("file_path") + "\",\"start_line\":1,\"end_line\":200}")
            }
            val content = readUtf8(file)
            val lines = content.split("\n").toTypedArray()
            val range = resolveRange(input, lines.size)
            val result = StringBuilder()
            for (i in range.startIndex until range.endIndexExclusive) {
                result.append(i + 1).append('\t').append(lines[i])
                if (i + 1 < range.endIndexExclusive) {
                    result.append('\n')
                }
            }
            if (range.startIndex > 0 || range.endIndexExclusive < lines.size) {
                result.append("\n\n... (共 ").append(lines.size).append(" 行，显示 ")
                    .append(range.startIndex + 1).append('-').append(range.endIndexExclusive).append(')')
            }
            ok(result.toString())
        } catch (e: Exception) {
            error("读取文件失败: " + e.message)
        }
    }

    private fun appendDirectory(builder: StringBuilder, dir: File, parentPath: String, count: IntArray) {
        if (count[0] >= MAX_DIRECTORY_ITEMS) {
            builder.append("... (目录项过多，已截断)\n")
            return
        }
        val items = dir.listFiles() ?: return
        items.sortWith(Comparator { a, b ->
            when {
                a.isDirectory != b.isDirectory -> if (a.isDirectory) -1 else 1
                else -> a.name.compareTo(b.name, ignoreCase = true)
            }
        })
        for (item in items) {
            if (count[0] >= MAX_DIRECTORY_ITEMS) {
                builder.append("... (目录项过多，已截断)\n")
                return
            }
            val relative = if (parentPath.isEmpty()) item.name else "$parentPath/${item.name}"
            if (item.isDirectory) {
                builder.append("[DIR]  ").append(relative).append("/\n")
                count[0]++
                appendDirectory(builder, item, relative, count)
            } else {
                builder.append("[FILE] ").append(relative).append('\n')
                count[0]++
            }
        }
    }

    private fun resolveRange(input: JSONObject, totalLines: Int): Range {
        return if (input.has("start_line") || input.has("end_line")) {
            val startLine = maxOf(1, input.optInt("start_line", 1))
            val endLine = minOf(totalLines, maxOf(startLine, input.optInt("end_line", minOf(totalLines, startLine + DEFAULT_LIMIT - 1))))
            Range(startLine - 1, endLine)
        } else {
            val offset = maxOf(0, input.optInt("offset", 0))
            val limit = maxOf(1, input.optInt("limit", DEFAULT_LIMIT))
            Range(minOf(offset, totalLines), minOf(totalLines, offset + limit))
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

    private fun ok(content: String): ToolResult = ToolResult("", getName(), content, false)

    private fun error(content: String): ToolResult = ToolResult("", getName(), content, true)

    private data class Range(val startIndex: Int, val endIndexExclusive: Int)
}
