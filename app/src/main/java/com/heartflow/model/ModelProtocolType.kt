package com.heartflow.model

/**
 * 模型协议类型枚举
 * 定义支持的API协议类型
 */
enum class ModelProtocolType(val label: String) {
    /** OpenAI兼容协议（chat/completions端点） */
    OPENAI_COMPATIBLE("OpenAI兼容"),

    /** Anthropic Messages API协议 */
    ANTHROPIC_MESSAGES("Anthropic"),

    /** Codex Responses API协议 */
    CODEX_RESPONSES("Codex"),

    /** 本地GGUF模型协议 */
    LOCAL_GGUF("本地模型");

    companion object {
        /**
         * 从存储值恢复协议类型
         */
        fun fromStorage(value: String): ModelProtocolType {
            if (value.isBlank()) return OPENAI_COMPATIBLE
            return entries.find {
                it.name.equals(value, ignoreCase = true) ||
                it.label.equals(value, ignoreCase = true)
            } ?: OPENAI_COMPATIBLE
        }

        /**
         * 根据baseUrl和modelId自动检测协议类型
         */
        fun autoDetect(baseUrl: String, modelId: String): ModelProtocolType {
            val url = baseUrl.lowercase()
            val model = modelId.lowercase()

            // Anthropic检测
            if (url.contains("anthropic") || url.contains("claude")) {
                return ANTHROPIC_MESSAGES
            }

            // 本地模型检测
            if (url.contains("localhost") || url.contains("127.0.0.1") ||
                model.contains("llama") || model.contains("gguf") ||
                model.contains("mistral") || model.contains("qwen")) {
                return LOCAL_GGUF
            }

            // 默认OpenAI兼容
            return OPENAI_COMPATIBLE
        }
    }
}
