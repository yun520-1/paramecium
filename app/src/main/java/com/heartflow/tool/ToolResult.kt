package com.heartflow.tool

/**
 * 工具结果数据类
 */
data class ToolResult(
    val toolCallId: String,
    val toolName: String,
    val content: String,
    val error: Boolean,
    val diffId: String = "",
    val reviewState: String = "",
    val reviewMessage: String = ""
) {
    /**
     * 创建成功结果
     */
    companion object {
        fun success(toolName: String, content: String): ToolResult {
            return ToolResult(
                toolCallId = "",
                toolName = toolName,
                content = content,
                error = false
            )
        }

        fun error(toolName: String, message: String): ToolResult {
            return ToolResult(
                toolCallId = "",
                toolName = toolName,
                content = message,
                error = true
            )
        }
    }

    /**
     * 创建带有调用信息的副本
     */
    fun withCall(nextToolCallId: String, nextToolName: String): ToolResult {
        return copy(
            toolCallId = nextToolCallId,
            toolName = nextToolName
        )
    }

    /**
     * 创建带有 diffId 的副本
     */
    fun withDiffId(nextDiffId: String): ToolResult {
        return copy(diffId = nextDiffId)
    }

    /**
     * 创建带有审查信息的副本
     */
    fun withReview(nextReviewState: String, nextReviewMessage: String): ToolResult {
        return copy(
            reviewState = nextReviewState,
            reviewMessage = nextReviewMessage
        )
    }

    fun isSuccess(): Boolean = !error
}
