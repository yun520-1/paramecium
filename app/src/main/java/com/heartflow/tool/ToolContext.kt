package com.heartflow.tool

import com.heartflow.tool.builtin.TodoItem
import org.json.JSONObject

/**
 * 工具执行上下文
 */
class ToolContext private constructor(
    val homePath: String,
    val extraWriteRoots: List<String>,
    val agentRunner: AgentRunner?,
    val toolCallId: String,
    private val progressListener: ProgressListener?,
    val todoStateStore: TodoStateStore?
) {
    /**
     * 代理运行器接口
     */
    interface AgentRunner {
        fun runAgent(input: JSONObject, context: ToolContext): ToolResult
        fun runAgentPipeline(input: JSONObject, context: ToolContext): ToolResult
    }

    /**
     * 进度监听器接口
     */
    interface ProgressListener {
        fun onToolProgress(toolCallId: String, toolName: String, content: String, error: Boolean)
    }

    /**
     * 待办状态存储接口
     */
    interface TodoStateStore {
        fun replace(items: List<TodoItem>)
        fun totalCount(): Int
        fun completedCount(): Int
    }

    // extraWriteRoots 的 getter 由 Kotlin 自动生成

    /**
     * 创建带有新 toolCallId 的副本
     */
    fun withToolCallId(nextToolCallId: String): ToolContext {
        return ToolContext(
            homePath = homePath,
            extraWriteRoots = extraWriteRoots,
            agentRunner = agentRunner,
            toolCallId = nextToolCallId,
            progressListener = progressListener,
            todoStateStore = todoStateStore
        )
    }

    /**
     * 报告工具执行进度
     */
    fun reportToolProgress(toolName: String, content: String?, error: Boolean) {
        if (progressListener != null && toolCallId.isNotEmpty()) {
            progressListener.onToolProgress(toolCallId, toolName, content ?: "", error)
        }
    }

    companion object {
        /**
         * 简单构造：仅指定 homePath
         */
        operator fun invoke(homePath: String): ToolContext {
            return ToolContext(
                homePath = homePath,
                extraWriteRoots = emptyList(),
                agentRunner = null,
                toolCallId = "",
                progressListener = null,
                todoStateStore = null
            )
        }

        /**
         * 构造：homePath + agentRunner
         */
        operator fun invoke(homePath: String, agentRunner: AgentRunner?): ToolContext {
            return ToolContext(
                homePath = homePath,
                extraWriteRoots = emptyList(),
                agentRunner = agentRunner,
                toolCallId = "",
                progressListener = null,
                todoStateStore = null
            )
        }

        /**
         * 构造：homePath + agentRunner + toolCallId
         */
        operator fun invoke(homePath: String, agentRunner: AgentRunner?, toolCallId: String): ToolContext {
            return ToolContext(
                homePath = homePath,
                extraWriteRoots = emptyList(),
                agentRunner = agentRunner,
                toolCallId = toolCallId,
                progressListener = null,
                todoStateStore = null
            )
        }

        /**
         * 构造：homePath + agentRunner + toolCallId + progressListener
         */
        operator fun invoke(
            homePath: String,
            agentRunner: AgentRunner?,
            toolCallId: String,
            progressListener: ProgressListener?
        ): ToolContext {
            return ToolContext(
                homePath = homePath,
                extraWriteRoots = emptyList(),
                agentRunner = agentRunner,
                toolCallId = toolCallId,
                progressListener = progressListener,
                todoStateStore = null
            )
        }

        /**
         * 完整构造
         */
        operator fun invoke(
            homePath: String,
            extraWriteRoots: List<String>?,
            agentRunner: AgentRunner?,
            toolCallId: String,
            progressListener: ProgressListener?,
            todoStateStore: TodoStateStore?
        ): ToolContext {
            val safeHomePath = homePath
            val safeToolCallId = toolCallId
            return ToolContext(
                homePath = safeHomePath,
                extraWriteRoots = (extraWriteRoots ?: emptyList())
                    .filter { it.trim().isNotEmpty() }
                    .map { it.trim() }
                    .toList(),
                agentRunner = agentRunner,
                toolCallId = safeToolCallId,
                progressListener = progressListener,
                todoStateStore = todoStateStore
            )
        }
    }
}
