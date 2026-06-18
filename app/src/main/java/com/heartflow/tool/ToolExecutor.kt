package com.heartflow.tool

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.charset.StandardCharsets

/**
 * 工具执行器
 * 负责执行工具调用并返回结果
 */
class ToolExecutor(
    private val registry: ToolRegistry,
    private val settingsRepository: ToolSettingsRepository?,
    private val diffRepository: DiffRepository?
) {

    /**
     * 执行工具调用
     */
    fun execute(toolCall: ToolCall, context: ToolContext?): ToolResult {
        return execute(toolCall, context, confirmed = false)
    }

    /**
     * 执行已确认的工具调用
     */
    fun executeConfirmed(toolCall: ToolCall, context: ToolContext?): ToolResult {
        return execute(toolCall, context, confirmed = true)
    }

    /**
     * 内部执行方法
     */
    private fun execute(toolCall: ToolCall, context: ToolContext?, confirmed: Boolean): ToolResult {
        if (toolCall.getId().isEmpty()) {
            return ToolResult.error("", "工具调用ID为空")
        }
        val tool = registry.get(toolCall.getName())

        if (tool == null) {
            return ToolResult.error(toolCall.getId(), "未知工具: ${toolCall.getName()}")
        }

        // 检查权限
        val permission = settingsRepository?.canExecuteTool(tool.getName(), tool.getCategory())
            ?: PermissionResult.allowed()
        if (!permission.isAllowed()) {
            return ToolResult.error(toolCall.getId(), permission.reason)
        }

        // 检查是否需要确认
        if (tool.requiresConfirmation() &&
            (settingsRepository?.needsConfirmation(tool.getName()) == true) &&
            !confirmed) {
            return ToolResult.error(toolCall.getId(), "工具需要确认后才能执行: ${tool.getName()}")
        }

        try {
            val argsStr = toolCall.getArguments()
            val input = if (argsStr.trim().isEmpty()) {
                JSONObject()
            } else {
                JSONObject(argsStr)
            }

            val safeContext = context ?: return ToolResult.error(toolCall.getId(), "工具上下文不可用")
            val result = if (shouldRecordDiff(tool.getName())) {
                executeWithDiff(tool, input, safeContext)
            } else {
                tool.execute(input, safeContext)
            }

            return result.withCall(toolCall.getId(), tool.getName())
        } catch (e: Exception) {
            return ToolResult.error(toolCall.getId(), "参数解析失败: ${e.message}")
        }
    }

    /**
     * 是否应该记录 diff
     */
    private fun shouldRecordDiff(toolName: String): Boolean {
        return diffRepository != null && (toolName == "file_write" || toolName == "file_edit")
    }

    /**
     * 执行并记录 diff
     */
    private fun executeWithDiff(tool: BaseTool, input: JSONObject, context: ToolContext): ToolResult {
        val path = input.optString("file_path", "")
        val file: File
        val existed: Boolean
        var oldContent = ""

        try {
            file = FileToolPathPolicy.resolve(context, path)
            existed = file.exists()
            if (existed && file.isDirectory) {
                return ToolResult.error(tool.getName(), "路径是一个目录，无法写入文件: $path")
            }
            if (existed) {
                oldContent = readUtf8(file)
            }
        } catch (e: Exception) {
            return ToolResult.error(tool.getName(), "无法读取原文件: $path\n${e.message}")
        }

        val result = tool.execute(input, context)
        if (result.error) {
            return result
        }

        val newContent = try {
            if (file.exists()) readUtf8(file) else ""
        } catch (_: Exception) {
            input.optString("content", "")
        }

        if (oldContent != newContent && diffRepository != null) {
            val diff = diffRepository.recordDiff(file.absolutePath, oldContent, newContent, existed)
            return result.withDiffId(diff.id)
        }
        return result
    }

    /**
     * 读取 UTF-8 文件内容
     */
    @Throws(Exception::class)
    private fun readUtf8(file: File): String {
        FileInputStream(file).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } >= 0) {
                output.write(buffer, 0, read)
            }
            return String(output.toByteArray(), StandardCharsets.UTF_8)
        }
    }

    /**
     * 工具设置仓库接口
     */
    interface ToolSettingsRepository {
        fun canExecuteTool(toolName: String, category: ToolCategory): PermissionResult
        fun needsConfirmation(toolName: String): Boolean
    }

    /**
     * Diff 仓库接口
     */
    interface DiffRepository {
        fun recordDiff(path: String, oldContent: String, newContent: String, existed: Boolean): DiffRecord
    }

    /**
     * Diff 记录
     */
    data class DiffRecord(val id: String)
}
