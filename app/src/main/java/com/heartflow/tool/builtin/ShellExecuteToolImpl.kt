package com.heartflow.tool.builtin

import com.heartflow.tool.BaseTool
import com.heartflow.tool.ToolCategory
import com.heartflow.tool.ToolContext
import com.heartflow.tool.ToolResult
import org.json.JSONObject

/**
 * Shell 命令执行工具（简化版）
 * - Android 环境下受限
 * - 仅用于提示功能限制
 */
class ShellExecuteToolImpl : BaseTool() {
    override fun getName(): String = "shell_execute"

    override fun getDescription(): String =
        "执行 shell 命令。由于 Android 安全限制，此功能在移动端受限。"

    override fun getCategory(): ToolCategory = ToolCategory.SYSTEM

    override fun requiresConfirmation(): Boolean = true

    override fun getParameters(): JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("command", JSONObject()
                .put("type", "string")
                .put("description", "要执行的 shell 命令"))
            .put("cwd", JSONObject()
                .put("type", "string")
                .put("description", "可选工作目录"))
            .put("timeoutMs", JSONObject()
                .put("type", "number")
                .put("description", "超时时间，单位毫秒")))
        .put("required", org.json.JSONArray().put("command"))

    override fun execute(input: JSONObject, context: ToolContext): ToolResult {
        val command = input.optString("command", "").trim()
        if (command.isEmpty()) {
            return error("命令不能为空")
        }
        return error("Shell 执行功能在 Android 环境下不可用。如需执行脚本，请使用代码编辑工具修改文件。")
    }

    private fun error(content: String): ToolResult = ToolResult("", getName(), content, true)
}
