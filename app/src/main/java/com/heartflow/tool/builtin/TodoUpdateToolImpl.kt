package com.heartflow.tool.builtin

import com.heartflow.tool.BaseTool
import com.heartflow.tool.ToolCategory
import com.heartflow.tool.ToolContext
import com.heartflow.tool.ToolResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * TODO 更新工具
 * - 维护会话 TODO 列表
 * - 状态注入到 system prompt
 */
class TodoUpdateToolImpl : BaseTool() {
    override fun getName(): String = "todo_update"

    override fun getDescription(): String =
        "维护当前会话的 TODO 列表。每次调用以完整列表覆盖旧列表；状态会作为 {{TODO_STATE}} 注入到下一轮 system prompt。" +
            "状态取值：pending（未开始）/ in_progress（进行中）/ completed（已完成）。" +
            "同一时刻最多 1 个 in_progress；新任务应放在列表底部；" +
            "任务完成时立即从列表移除或改为 completed。"

    override fun getCategory(): ToolCategory = ToolCategory.SYSTEM

    override fun requiresConfirmation(): Boolean = false

    override fun getParameters(): JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("items", JSONObject()
                .put("type", "array")
                .put("description", "完整 TODO 列表，会覆盖旧列表。")
                .put("items", JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject()
                        .put("content", JSONObject()
                            .put("type", "string")
                            .put("description", "任务内容，简洁、可验证"))
                        .put("status", JSONObject()
                            .put("type", "string")
                            .put("enum", JSONArray()
                                .put(TodoItem.STATUS_PENDING)
                                .put(TodoItem.STATUS_IN_PROGRESS)
                                .put(TodoItem.STATUS_COMPLETED))
                            .put("description", "任务状态：pending / in_progress / completed"))))))
        .put("required", JSONArray().put("items"))

    override fun execute(input: JSONObject, context: ToolContext): ToolResult {
        if (input == null) {
            return error("参数不能为空。")
        }
        val rawArray = input.optJSONArray("items")
        if (rawArray == null) {
            return error("缺少 items 数组。")
        }
        val parsed = mutableListOf<TodoItem>()
        for (i in 0 until rawArray.length()) {
            val obj = rawArray.optJSONObject(i) ?: continue
            val item = TodoItem.fromJson(obj)
            if (item != null) {
                parsed.add(item)
            }
        }
        val store = context?.todoStateStore
        if (store == null) {
            return error("TODO 状态存储未初始化。")
        }
        store.replace(parsed)
        val total = store.totalCount()
        val done = store.completedCount()
        val summary = if (total == 0) {
            "已清空 TODO 列表。"
        } else {
            "TODO 列表已更新，共 $total 项，已完成 $done 项。"
        }
        return ToolResult("", getName(), summary, false)
    }

    private fun error(content: String): ToolResult = ToolResult("", getName(), content, true)
}
