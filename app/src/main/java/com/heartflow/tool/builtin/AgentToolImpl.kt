package com.heartflow.tool.builtin

import com.heartflow.tool.BaseTool
import com.heartflow.tool.ToolCategory
import com.heartflow.tool.ToolContext
import com.heartflow.tool.ToolResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * Agent 分派工具
 * - explore: 只读探索
 * - sub-coding: 可写编程任务
 */
class AgentToolImpl : BaseTool() {
    companion object {
        const val TYPE_EXPLORE = "explore"
        const val TYPE_SUB_CODING = "sub-coding"
    }

    override fun getName(): String = "agent"

    override fun getDescription(): String =
        "分派一个子 Agent 处理任务。explore 只能只读探索；sub-coding 必须有明确且唯一的写入范围，不能和其他 Agent 操纵同一文件。"

    override fun getCategory(): ToolCategory = ToolCategory.SYSTEM

    override fun getParameters(): JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("type", JSONObject()
                .put("type", "string")
                .put("enum", JSONArray().put(TYPE_EXPLORE).put(TYPE_SUB_CODING))
                .put("description", "Agent 类型：explore 只读探索，sub-coding 编程子任务"))
            .put("description", JSONObject()
                .put("type", "string")
                .put("description", "3-8 个词的任务标题"))
            .put("prompt", JSONObject()
                .put("type", "string")
                .put("description", "分派给 Agent 的详细任务、范围、限制和验收方式。必须写明不能修改未授权文件；如需修改范围外文件必须停止并汇报"))
            .put("read_scope", JSONObject()
                .put("type", "array")
                .put("items", JSONObject().put("type", "string"))
                .put("description", "允许读取的文件或目录路径列表"))
            .put("write_scope", JSONObject()
                .put("type", "array")
                .put("items", JSONObject().put("type", "string"))
                .put("description", "sub-coding 允许写入的唯一文件或目录路径列表；explore 必须留空")))
        .put("required", JSONArray().put("type").put("description").put("prompt"))

    override fun execute(input: JSONObject, context: ToolContext): ToolResult {
        val type = normalizeType(input.optString("type"))
        val description = input.optString("description").trim()
        val prompt = input.optString("prompt").trim()

        if (type != TYPE_EXPLORE && type != TYPE_SUB_CODING) {
            return error("Agent 类型只能是 explore 或 sub-coding。")
        }
        if (type == TYPE_EXPLORE && hasScope(input.optJSONArray("write_scope"))) {
            return error("explore Agent 不能声明 write_scope，也不能写入文件。")
        }
        if (description.isEmpty()) {
            return error("Agent description 不能为空。")
        }
        if (prompt.isEmpty()) {
            return error("Agent prompt 不能为空。")
        }
        if (context == null || context.agentRunner == null) {
            return error("Agent 执行器未接入，无法运行子 Agent。")
        }
        return try {
            val normalized = JSONObject()
                .put("type", type)
                .put("description", description)
                .put("prompt", prompt)
                .put("read_scope", input.opt("read_scope"))
                .put("write_scope", input.opt("write_scope"))
            context.agentRunner.runAgent(normalized, context)
        } catch (e: Exception) {
            error("Agent 参数解析失败: ${e.message}")
        }
    }

    private fun normalizeType(value: String): String {
        return when (value.trim().lowercase()) {
            "sub_coding", "subcoding", "coding" -> TYPE_SUB_CODING
            else -> value.trim().lowercase()
        }
    }

    private fun hasScope(array: JSONArray?): Boolean {
        if (array == null) return false
        for (i in 0 until array.length()) {
            if (array.optString(i).trim().isNotEmpty()) return true
        }
        return false
    }

    private fun error(content: String): ToolResult = ToolResult("", getName(), content, true)
}
