package com.heartflow.tool.builtin

import com.heartflow.tool.BaseTool
import com.heartflow.tool.ToolCategory
import com.heartflow.tool.ToolContext
import com.heartflow.tool.ToolResult
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream

/**
 * 图片理解工具
 * - 读取本地图片
 * - 使用视觉模型理解图片内容
 */
class ImageUnderstandingToolImpl : BaseTool() {
    companion object {
        private const val MAX_IMAGE_BYTES = 10L * 1024 * 1024
    }

    override fun getName(): String = "image_understanding"

    override fun getDescription(): String =
        "读取本地图片文件并理解图片内容。需要配置视觉模型。"

    override fun getCategory(): ToolCategory = ToolCategory.READ

    override fun getParameters(): JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("path", JSONObject()
                .put("type", "string")
                .put("description", "图片路径"))
            .put("prompt", JSONObject()
                .put("type", "string")
                .put("description", "希望模型回答的问题")))
        .put("required", org.json.JSONArray().put("path"))

    override fun execute(input: JSONObject, context: ToolContext): ToolResult {
        val path = firstOf(input, "path", "image_path", "file_path")
        if (path.isEmpty()) {
            return error("图片路径不能为空。")
        }
        return error("图片理解功能需要在设置中配置视觉模型。当前版本暂不支持此功能。")
    }

    private fun firstOf(input: JSONObject?, vararg keys: String): String {
        if (input == null) return ""
        for (key in keys) {
            val value = input.optString(key).trim()
            if (value.isNotEmpty()) return value
        }
        return ""
    }

    @Throws(Exception::class)
    private fun readBytes(file: File): ByteArray {
        FileInputStream(file).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } >= 0) {
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }

    private fun mimeType(path: String): String {
        val name = path.lowercase()
        return when {
            name.endsWith(".png") -> "image/png"
            name.endsWith(".jpg") || name.endsWith(".jpeg") -> "image/jpeg"
            name.endsWith(".webp") -> "image/webp"
            name.endsWith(".gif") -> "image/gif"
            else -> ""
        }
    }

    private fun ok(content: String): ToolResult = ToolResult("", getName(), content, false)

    private fun error(content: String): ToolResult = ToolResult("", getName(), content, true)
}
