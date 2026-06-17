package com.heartflow.context

import android.util.Log
import com.heartflow.model.*

/**
 * 上下文压缩服务
 * 负责在Token超限时智能压缩对话历史
 */
class ContextCompactionService(
    private val targetRatio: Float = 0.7f
) {
    companion object {
        private const val TAG = "ContextCompactionService"
    }

    /**
     * 压缩上下文
     * @return 压缩结果
     */
    fun compact(contextManager: ContextManager): CompactionResult {
        val beforeTokens = contextManager.getCurrentTokens()
        val beforeCount = contextManager.getMessages().size

        // 策略1：简单裁剪 - 移除最早的1/3消息
        val messages = contextManager.getMessages()
        if (messages.size < 3) {
            Log.w(TAG, "消息太少，跳过压缩")
            return CompactionResult(didCompact = false)
        }

        val keepCount = (messages.size * 0.6).toInt().coerceAtLeast(1)
        var removedCount = 0

        while (contextManager.getMessages().size > keepCount) {
            contextManager.trimTo(contextManager.getMessages().size - 1)
            removedCount++
        }

        // 如果还是太大，继续裁剪
        while (contextManager.needsCompaction() && contextManager.getMessages().size > 1) {
            contextManager.trimTo(contextManager.getMessages().size - 1)
            removedCount++
        }

        val afterTokens = contextManager.getCurrentTokens()

        Log.d(TAG, "压缩完成: $beforeTokens -> $afterTokens tokens, " +
                  "移除 $removedCount 条消息, 节省 ${beforeTokens - afterTokens} tokens")

        return CompactionResult(
            didCompact = true,
            removedCount = removedCount,
            savedTokens = beforeTokens - afterTokens
        )
    }

    /**
     * 智能压缩 - 保留重要消息
     */
    fun smartCompact(
        contextManager: ContextManager,
        importanceSelector: (ModelMessage) -> Float = { msg ->
            // 默认：用户消息权重更高，助手消息次之，系统消息最高
            when (msg.role) {
                "system" -> 1.0f
                "user" -> 0.8f
                "assistant" -> 0.5f
                "tool" -> 0.3f
                else -> 0.5f
            }
        }
    ): CompactionResult {
        val beforeTokens = contextManager.getCurrentTokens()
        val messages = contextManager.getMessages()

        if (messages.size < 3) {
            return CompactionResult(didCompact = false)
        }

        // 计算每条消息的重要性分数
        val scoredMessages = messages.mapIndexed { index, msg ->
            val baseScore = importanceSelector(msg)
            // 最近的消息权重更高
            val recencyScore = (index.toFloat() / messages.size) * 0.3f
            MessageScore(index, msg, baseScore + recencyScore)
        }.sortedByDescending { it.score }

        // 保留重要消息
        val keepCount = (messages.size * targetRatio).toInt().coerceAtLeast(1)
        val keepIndices = scoredMessages.take(keepCount).map { it.index }.toSet()

        // 重新构建消息列表（保持原始顺序）
        val newMessages = messages.filterIndexed { index, _ -> index in keepIndices }
        val removedCount = messages.size - newMessages.size

        // 清除并重新添加
        contextManager.clear()
        contextManager.getMessages().let {
            // 清空上下文管理器
            val tempManager = ContextManager()
            tempManager.setSystemPrompt(contextManager.getSystemPrompt())
            newMessages.forEach { msg -> tempManager.addMessage(msg) }

            // 复制回来
            contextManager.clear()
            contextManager.setSystemPrompt(tempManager.getSystemPrompt())
            newMessages.forEach { msg -> contextManager.addMessage(msg) }
        }

        val afterTokens = contextManager.getCurrentTokens()

        return CompactionResult(
            didCompact = true,
            removedCount = removedCount,
            savedTokens = beforeTokens - afterTokens
        )
    }

    /**
     * 摘要压缩 - 将多条消息合并为一条摘要
     */
    fun summarizeCompact(
        contextManager: ContextManager,
        summarizer: (List<ModelMessage>) -> String
    ): CompactionResult {
        val beforeTokens = contextManager.getCurrentTokens()
        val messages = contextManager.getMessages()

        if (messages.size < 5) {
            return CompactionResult(didCompact = false)
        }

        // 保留系统消息和最近2条对话
        val systemMessage = messages.find { it.role == "system" }
        val recentMessages = messages.takeLast(2)

        // 中间消息进行摘要
        val middleMessages = messages.dropLast(2).filter { it.role != "system" }
        if (middleMessages.isNotEmpty()) {
            val summary = summarizer(middleMessages)
            val summaryMessage = SystemModelMessage(
                content = "[历史摘要]\n$summary"
            )

            // 重建上下文
            contextManager.clear()
            contextManager.addMessage(summaryMessage)
            recentMessages.forEach { contextManager.addMessage(it) }
        }

        val afterTokens = contextManager.getCurrentTokens()

        return CompactionResult(
            didCompact = true,
            removedCount = middleMessages.size,
            savedTokens = beforeTokens - afterTokens
        )
    }

    /**
     * 消息评分
     */
    private data class MessageScore(
        val index: Int,
        val message: ModelMessage,
        val score: Float
    )
}

/**
 * 压缩策略
 */
enum class CompactionStrategy {
    /** 简单裁剪 - 移除最早的消息 */
    SIMPLE_TRIM,

    /** 智能压缩 - 根据重要性保留 */
    SMART_KEEP,

    /** 摘要压缩 - 合并历史为摘要 */
    SUMMARIZE
}

/**
 * 压缩策略配置
 */
data class CompactionConfig(
    val strategy: CompactionStrategy = CompactionStrategy.SIMPLE_TRIM,
    val targetRatio: Float = 0.7f,
    val minMessagesKept: Int = 2,
    val preserveSystemPrompt: Boolean = true
) {
    companion object {
        val DEFAULT = CompactionConfig()
        val AGGRESSIVE = CompactionConfig(
            strategy = CompactionStrategy.SMART_KEEP,
            targetRatio = 0.5f,
            minMessagesKept = 1
        )
        val CONSERVATIVE = CompactionConfig(
            strategy = CompactionStrategy.SIMPLE_TRIM,
            targetRatio = 0.8f,
            minMessagesKept = 4
        )
    }
}
