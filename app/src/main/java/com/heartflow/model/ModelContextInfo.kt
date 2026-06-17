package com.heartflow.model

/**
 * 模型上下文信息
 * 包含模型ID和上下文窗口大小
 */
data class ModelContextInfo(
    val apiModelId: String,
    val contextTokens: Int,
    val contextSize: String
) {
    companion object {
        private const val DEFAULT_CONTEXT_TOKENS = 250000
        private val CONTEXT_SUFFIX = Regex("\\[([0-9]+(?:\\.[0-9]+)?)([kKmM]?)\\]$")

        /**
         * 解析模型ID，提取上下文窗口信息
         * 支持格式如: gpt-4[128k], qwen-plus[200k]
         */
        fun parse(modelId: String): ModelContextInfo {
            val trimmed = modelId.trim()
            val matcher = CONTEXT_SUFFIX.find(trimmed)

            if (matcher == null) {
                return ModelContextInfo(trimmed, DEFAULT_CONTEXT_TOKENS, formatContextSize(DEFAULT_CONTEXT_TOKENS))
            }

            val rawNumber = matcher.groupValues[1].toDoubleOrNull() ?: DEFAULT_CONTEXT_TOKENS.toDouble()
            val unit = matcher.groupValues[2].lowercase()
            val multiplier = when (unit) {
                "m" -> 1000000.0
                "k" -> 1000.0
                else -> 1.0
            }
            val contextTokens = maxOf(1, kotlin.math.round(rawNumber * multiplier).toInt())
            val apiModelId = trimmed.substringBeforeLast("[").trim()

            return ModelContextInfo(
                apiModelId = apiModelId.ifEmpty { trimmed },
                contextTokens = contextTokens,
                contextSize = formatContextSize(contextTokens)
            )
        }

        /**
         * 格式化上下文大小为人类可读字符串
         */
        fun formatContextSize(tokens: Int): String {
            return when {
                tokens >= 1_000_000 -> {
                    val value = tokens / 1_000_000.0
                    "${formatNumber(value)}m"
                }
                tokens >= 1000 -> {
                    val value = tokens / 1000.0
                    "${formatNumber(value)}k"
                }
                else -> tokens.toString()
            }
        }

        private fun formatNumber(value: Double): String {
            return if (kotlin.math.abs(value - kotlin.math.round(value)) < 0.00001) {
                kotlin.math.round(value).toLong().toString()
            } else {
                String.format("%.1f", value)
            }
        }
    }
}
