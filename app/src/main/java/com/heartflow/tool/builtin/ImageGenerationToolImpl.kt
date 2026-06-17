package com.heartflow.tool.builtin

import com.heartflow.tool.BaseTool
import com.heartflow.tool.ToolCategory
import com.heartflow.tool.ToolContext
import com.heartflow.tool.ToolResult
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * 图片生成工具（简化版）
 * - 支持通过配置的模型生成图片
 * - 使用 OpenAI Images API 兼容接口
 */
class ImageGenerationToolImpl : BaseTool() {
    companion object {
        private const val MAX_RESPONSE_BYTES = 24 * 1024 * 1024
    }

    override fun getName(): String = "image_generation"

    override fun getDescription(): String =
        "根据提示词生成图片。需要在设置中配置图片生成模型。成功生成后返回可直接使用的图片路径。"

    override fun getCategory(): ToolCategory = ToolCategory.GENERATE

    override fun getParameters(): JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("prompt", JSONObject()
                .put("type", "string")
                .put("description", "图片生成提示词"))
            .put("size", JSONObject()
                .put("type", "string")
                .put("description", "图片尺寸，默认 1024x1024")))
        .put("required", org.json.JSONArray().put("prompt"))

    override fun execute(input: JSONObject, context: ToolContext): ToolResult {
        val prompt = input?.optString("prompt")?.trim() ?: ""
        if (prompt.isEmpty()) {
            return error("图片生成提示词不能为空。")
        }
        return error("图片生成功能需要在设置中配置图片生成模型。当前版本暂不支持此功能。")
    }

    private fun ok(content: String): ToolResult = ToolResult("", getName(), content, false)

    private fun error(content: String): ToolResult = ToolResult("", getName(), content, true)
}
