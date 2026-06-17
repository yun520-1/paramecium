package com.heartflow.context

import android.util.Log
import com.heartflow.model.*
import com.heartflow.model.ModelContextInfo

/**
 * 上下文管理器
 * 负责管理对话历史和Token预算
 */
open class ContextManager(
    private val maxTokens: Int = 8000,
    private val reserveTokens: Int = 2000
) {
    companion object {
        private const val TAG = "ContextManager"

        /** 默认Token预算 */
        const val DEFAULT_MAX_TOKENS = 8000

        /** 保留Token空间 */
        const val DEFAULT_RESERVE_TOKENS = 2000

        /** 系统提示Token估算 */
        const val SYSTEM_PROMPT_ESTIMATE = 500
    }

    private var systemPrompt: String = ""
    private val messages = mutableListOf<ModelMessage>()
    private var currentTokens = 0

    /**
     * 设置系统提示
     */
    fun setSystemPrompt(prompt: String) {
        systemPrompt = prompt
        recalculateTokens()
    }

    /**
     * 添加消息
     */
    fun addMessage(message: ModelMessage) {
        messages.add(message)
        recalculateTokens()
    }

    /**
     * 添加消息列表
     */
    fun addMessages(newMessages: List<ModelMessage>) {
        messages.addAll(newMessages)
        recalculateTokens()
    }

    /**
     * 获取所有消息
     */
    fun getMessages(): List<ModelMessage> {
        return messages.toList()
    }

    /**
     * 获取系统提示
     */
    fun getSystemPrompt(): String = systemPrompt

    /**
     * 获取当前Token使用量
     */
    fun getCurrentTokens(): Int = currentTokens

    /**
     * 获取可用Token空间
     */
    fun getAvailableTokens(): Int {
        return (maxTokens - currentTokens - reserveTokens).coerceAtLeast(0)
    }

    /**
     * 是否需要压缩
     */
    fun needsCompaction(): Boolean {
        return currentTokens > (maxTokens - reserveTokens)
    }

    /**
     * 获取使用率
     */
    fun getUsageRatio(): Float {
        return currentTokens.toFloat() / maxTokens.toFloat()
    }

    /**
     * 清理历史（保留最近N条）
     */
    fun trimTo(keepCount: Int) {
        if (messages.size > keepCount) {
            val removed = messages.removeAt(0)
            Log.d(TAG, "移除旧消息: ${removed.role}")
            recalculateTokens()
        }
    }

    /**
     * 清除所有消息
     */
    fun clear() {
        messages.clear()
        currentTokens = 0
    }

    /**
     * 获取历史摘要
     */
    fun summarize(): String {
        return buildString {
            appendLine("=== 上下文摘要 ===")
            appendLine("系统提示长度: ${systemPrompt.length}")
            appendLine("消息数量: ${messages.size}")
            appendLine("当前Token: $currentTokens / $maxTokens")
            appendLine("使用率: ${String.format("%.1f", getUsageRatio() * 100)}%")
            appendLine("可用空间: ${getAvailableTokens()}")

            if (messages.isNotEmpty()) {
                appendLine("\n=== 最近消息 ===")
                val recent = messages.takeLast(3)
                for (msg in recent) {
                    val preview = if (msg.content.length > 50) {
                        msg.content.take(50) + "..."
                    } else {
                        msg.content
                    }
                    appendLine("[${msg.role}]: $preview")
                }
            }
        }
    }

    /**
     * 重新计算Token使用量
     */
    private fun recalculateTokens() {
        currentTokens = estimateTokens(systemPrompt)
        for (msg in messages) {
            currentTokens += estimateTokens(msg.content)
            if (msg.reasoningContent.isNotEmpty()) {
                currentTokens += estimateTokens(msg.reasoningContent)
            }
        }
    }

    /**
     * 估算Token数量（简单实现：中文按2倍计算）
     */
    private fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        val chineseChars = text.count { it in '一'..'鿿' }
        val otherChars = text.length - chineseChars
        return (chineseChars * 2 + otherChars) / 4 // 约等于
    }
}

/**
 * Token计算工具
 */
object TokenEstimator {

    /**
     * 估算Token数量
     */
    fun estimate(text: String): Int {
        if (text.isEmpty()) return 0
        val chineseChars = text.count { it in '一'..'鿿' }
        val otherChars = text.length - chineseChars
        return (chineseChars * 2 + otherChars) / 4
    }

    /**
     * 估算消息列表总Token
     */
    fun estimateMessages(messages: List<ModelMessage>): Int {
        return messages.sumOf { estimate(it.content) }
    }

    /**
     * 估算上下文可用空间
     */
    fun estimateAvailableSpace(
        contextWindow: Int,
        systemPromptTokens: Int,
        messages: List<ModelMessage>
    ): Int {
        val usedTokens = systemPromptTokens + estimateMessages(messages)
        return (contextWindow - usedTokens - 500).coerceAtLeast(0) // 保留500 buffer
    }
}

/**
 * 滑动窗口上下文管理
 * 自动保留最近的上下文
 */
class SlidingWindowContextManager(
    contextWindow: Int = 128000,
    reserveTokens: Int = 2000
) : ContextManager(contextWindow, reserveTokens) {

    private val compactionService = ContextCompactionService()

    /**
     * 添加消息，自动压缩如果需要
     */
    fun addMessageWithAutoCompaction(message: ModelMessage): CompactionResult {
        addMessage(message)

        if (needsCompaction()) {
            val compacted = compaction()
            return CompactionResult(
                didCompact = true,
                removedCount = compacted.removedCount,
                savedTokens = compacted.savedTokens
            )
        }

        return CompactionResult(didCompact = false)
    }

    /**
     * 压缩上下文
     */
    fun compaction(): CompactionResult {
        val beforeTokens = getCurrentTokens()
        compactionService.compact(this)
        val afterTokens = getCurrentTokens()

        return CompactionResult(
            didCompact = true,
            removedCount = 0,
            savedTokens = beforeTokens - afterTokens
        )
    }
}

/**
 * 压缩结果
 */
data class CompactionResult(
    val didCompact: Boolean,
    val removedCount: Int = 0,
    val savedTokens: Int = 0
)
