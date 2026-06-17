package com.heartflow.model

import com.heartflow.tool.ToolCall

/**
 * 模型消息基类
 * 定义消息的通用属性和方法
 */
sealed class ModelMessage(
    val content: String,
    val reasoningContent: String = "",
    val rawInputJson: String = ""
) {
    /**
     * 获取消息角色
     */
    abstract val role: String

    /**
     * 获取工具调用列表
     */
    open fun getToolCalls(): List<ToolCall> = emptyList()

    /**
     * 获取工具调用ID
     */
    open fun getToolCallId(): String = ""

    /**
     * 获取工具名称
     */
    open fun getToolName(): String = ""

    /**
     * 是否为工具错误消息
     */
    open fun isToolError(): Boolean = false
}

/**
 * 用户消息
 */
class UserModelMessage(
    content: String,
    rawInputJson: String = ""
) : ModelMessage(content, "", rawInputJson) {
    override val role: String = "user"
}

/**
 * 助手消息
 */
class AssistantModelMessage(
    content: String,
    reasoningContent: String = "",
    private val toolCalls: List<ToolCall> = emptyList()
) : ModelMessage(content, reasoningContent) {
    override val role: String = "assistant"
    override fun getToolCalls(): List<ToolCall> = toolCalls
}

/**
 * 系统消息
 */
class SystemModelMessage(
    content: String
) : ModelMessage(content) {
    override val role: String = "system"
}

/**
 * 工具结果消息
 */
class ToolModelMessage(
    content: String,
    private val toolCallId: String,
    private val toolName: String = "",
    private val isError: Boolean = false
) : ModelMessage(content) {
    override val role: String = "tool"
    override fun getToolCallId(): String = toolCallId
    override fun getToolName(): String = toolName
    override fun isToolError(): Boolean = isError
}

/**
 * 消息内容类型
 * 用于区分文本和图片等内容
 */
sealed class MessageContent {
    data class Text(val text: String) : MessageContent()
    data class ImageUrl(val url: String, val detail: String = "auto") : MessageContent()
}

/**
 * 用户消息内容（支持多模态）
 */
class UserModelMessageMultiModal(
    content: List<MessageContent>,
    rawInputJson: String = ""
) : ModelMessage(content.toString(), "", rawInputJson) {
    override val role: String = "user"

    /**
     * 获取消息内容列表
     */
    fun getContentList(): List<MessageContent> = contentList
    private val contentList: List<MessageContent> = content
}
