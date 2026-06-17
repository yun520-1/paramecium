package com.heartflow.protocol

import android.util.Log
import com.heartflow.model.*

/**
 * 协议自动选择器
 * 根据API配置自动选择最合适的协议
 */
object ProtocolAutoSelector {

    private const val TAG = "ProtocolAutoSelector"

    /**
     * 自动选择协议
     */
    fun select(config: ModelConfig): ModelProtocolType {
        val url = config.baseUrl.lowercase()
        val model = config.modelId.lowercase()

        Log.d(TAG, "自动选择协议: url=$url, model=$model")

        // 优先级1：Anthropic API
        if (isAnthropicApi(url)) {
            Log.d(TAG, "选择: ANTHROPIC_MESSAGES (检测到Anthropic API)")
            return ModelProtocolType.ANTHROPIC_MESSAGES
        }

        // 优先级2：Codex API
        if (isCodexApi(url, model)) {
            Log.d(TAG, "选择: CODEX_RESPONSES (检测到Codex API)")
            return ModelProtocolType.CODEX_RESPONSES
        }

        // 优先级3：本地模型
        if (isLocalModel(url, model)) {
            Log.d(TAG, "选择: LOCAL_GGUF (检测到本地模型)")
            return ModelProtocolType.LOCAL_GGUF
        }

        // 优先级4：根据模型名称检测
        val modelType = detectFromModelName(model)
        if (modelType != ModelProtocolType.OPENAI_COMPATIBLE) {
            Log.d(TAG, "选择: $modelType (根据模型名称)")
            return modelType
        }

        // 默认：OpenAI兼容协议
        Log.d(TAG, "选择: OPENAI_COMPATIBLE (默认)")
        return ModelProtocolType.OPENAI_COMPATIBLE
    }

    /**
     * 检测是否为Anthropic API
     */
    private fun isAnthropicApi(url: String): Boolean {
        return url.contains("anthropic") ||
               url.contains("claude") ||
               url.contains(".anthropic.com") ||
               url.contains("api.anthropic")
    }

    /**
     * 检测是否为Codex API
     */
    private fun isCodexApi(url: String, model: String): Boolean {
        return (url.contains("openai") && url.contains("responses")) ||
               model.contains("codex") ||
               model.contains("o1") ||
               model.contains("o3")
    }

    /**
     * 检测是否为本地模型
     */
    private fun isLocalModel(url: String, model: String): Boolean {
        val localIndicators = listOf(
            "localhost", "127.0.0.1",
            "ollama", "lmstudio", "llama.cpp",
            "text-gen-webui"
        )

        // URL检测
        if (localIndicators.any { url.contains(it) }) {
            return true
        }

        // 模型名称检测（常见本地模型）
        val localModels = listOf(
            "llama", "mistral", "phi", "qwen",
            "gemma", "codellama", "yi", "baichuan",
            "chatglm", "falcon", "mixtral", "stablelm"
        )

        return localModels.any { model.contains(it) }
    }

    /**
     * 根据模型名称检测协议类型
     */
    private fun detectFromModelName(model: String): ModelProtocolType {
        // Claude系列 - Anthropic
        if (model.contains("claude")) {
            return ModelProtocolType.ANTHROPIC_MESSAGES
        }

        // o1/o3系列 - Codex
        if (model.startsWith("o1") || model.startsWith("o3")) {
            return ModelProtocolType.CODEX_RESPONSES
        }

        // 本地模型 - Ollama等
        if (model.contains("llama") || model.contains("mistral") || model.contains("phi")) {
            return ModelProtocolType.LOCAL_GGUF
        }

        return ModelProtocolType.OPENAI_COMPATIBLE
    }

    /**
     * 获取推荐的系统提示模板
     */
    fun getSystemPromptTemplate(protocolType: ModelProtocolType): String {
        return when (protocolType) {
            ModelProtocolType.ANTHROPIC_MESSAGES -> {
                """
你是一个有帮助的AI助手。请用简洁、清晰的语言回答用户的问题。
如果需要使用工具来完成任务，请明确调用相应的工具。
                """.trimIndent()
            }
            ModelProtocolType.CODEX_RESPONSES -> {
                """
你是一个专业的AI编程助手。请帮助用户解决编程问题，编写高质量的代码。
在回答时，请提供清晰的解释和完整的代码示例。
                """.trimIndent()
            }
            ModelProtocolType.LOCAL_GGUF -> {
                """
你是一个本地AI助手。请用简洁的方式回答问题。
本地模型可能有Token限制，请尽量简洁。
                """.trimIndent()
            }
            ModelProtocolType.OPENAI_COMPATIBLE -> {
                """
你是一个有帮助的AI助手。请用清晰、友好的语言回答用户的问题。
尽可能提供准确和有用的信息。
                """.trimIndent()
            }
        }
    }

    /**
     * 获取协议推荐的最大Token数
     */
    fun getRecommendedMaxTokens(protocolType: ModelProtocolType): Int {
        return when (protocolType) {
            ModelProtocolType.ANTHROPIC_MESSAGES -> 8192
            ModelProtocolType.CODEX_RESPONSES -> 4096
            ModelProtocolType.LOCAL_GGUF -> 4096
            ModelProtocolType.OPENAI_COMPATIBLE -> 8192
        }
    }
}

/**
 * 协议配置助手
 * 帮助用户配置API
 */
object ProtocolConfigHelper {

    /**
     * 检测API配置是否完整
     */
    fun isConfigComplete(config: ModelConfig): ConfigCheckResult {
        val errors = mutableListOf<String>()

        if (config.baseUrl.isBlank()) {
            errors.add("Base URL不能为空")
        }

        if (config.modelId.isBlank()) {
            errors.add("模型ID不能为空")
        }

        if (config.apiKey.isBlank()) {
            errors.add("API密钥不能为空")
        }

        // 检查URL格式
        if (config.baseUrl.isNotBlank()) {
            if (!isValidUrl(config.baseUrl)) {
                errors.add("Base URL格式无效")
            }
        }

        return if (errors.isEmpty()) {
            ConfigCheckResult.Valid
        } else {
            ConfigCheckResult.Invalid(errors)
        }
    }

    /**
     * 验证URL格式
     */
    private fun isValidUrl(url: String): Boolean {
        return try {
            val cleanUrl = url.removePrefix("https://").removePrefix("http://")
            cleanUrl.isNotBlank() && cleanUrl.contains(".")
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 格式化Base URL
     */
    fun normalizeBaseUrl(url: String): String {
        var normalized = url.trim()

        // 移除末尾斜杠
        if (normalized.endsWith("/")) {
            normalized = normalized.dropLast(1)
        }

        // 添加https://如果缺失
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "https://$normalized"
        }

        return normalized
    }

    /**
     * 获取协议的默认端点
     */
    fun getDefaultEndpoint(protocolType: ModelProtocolType): String {
        return when (protocolType) {
            ModelProtocolType.ANTHROPIC_MESSAGES -> "https://api.anthropic.com/v1/messages"
            ModelProtocolType.CODEX_RESPONSES -> "https://api.openai.com/v1/responses"
            ModelProtocolType.LOCAL_GGUF -> "http://localhost:11434/api/chat"
            ModelProtocolType.OPENAI_COMPATIBLE -> "https://api.openai.com/v1/chat/completions"
        }
    }

    /**
     * 获取常用API模板
     */
    fun getApiTemplates(): List<ApiTemplate> {
        return listOf(
            // OpenAI兼容
            ApiTemplate(
                name = "OpenAI",
                baseUrl = "https://api.openai.com",
                endpoint = "/v1/chat/completions",
                modelPlaceholder = "gpt-4",
                protocolType = ModelProtocolType.OPENAI_COMPATIBLE
            ),
            ApiTemplate(
                name = "通义千问 (DashScope)",
                baseUrl = "https://dashscope.aliyuncs.com",
                endpoint = "/compatible-mode/v1/chat/completions",
                modelPlaceholder = "qwen-plus",
                protocolType = ModelProtocolType.OPENAI_COMPATIBLE
            ),
            ApiTemplate(
                name = "DeepSeek",
                baseUrl = "https://api.deepseek.com",
                endpoint = "/v1/chat/completions",
                modelPlaceholder = "deepseek-chat",
                protocolType = ModelProtocolType.OPENAI_COMPATIBLE
            ),
            ApiTemplate(
                name = "Moonshot (月之暗面)",
                baseUrl = "https://api.moonshot.cn",
                endpoint = "/v1/chat/completions",
                modelPlaceholder = "moonshot-v1-8k",
                protocolType = ModelProtocolType.OPENAI_COMPATIBLE
            ),
            ApiTemplate(
                name = "智谱AI (GLM)",
                baseUrl = "https://open.bigmodel.cn",
                endpoint = "/api/paas/v4/chat/completions",
                modelPlaceholder = "glm-4",
                protocolType = ModelProtocolType.OPENAI_COMPATIBLE
            ),
            // Anthropic
            ApiTemplate(
                name = "Anthropic (Claude)",
                baseUrl = "https://api.anthropic.com",
                endpoint = "/v1/messages",
                modelPlaceholder = "claude-3-5-sonnet",
                protocolType = ModelProtocolType.ANTHROPIC_MESSAGES
            ),
            // Codex
            ApiTemplate(
                name = "OpenAI o1/o3",
                baseUrl = "https://api.openai.com",
                endpoint = "/v1/responses",
                modelPlaceholder = "o1-preview",
                protocolType = ModelProtocolType.CODEX_RESPONSES
            ),
            // 本地
            ApiTemplate(
                name = "Ollama (本地)",
                baseUrl = "http://localhost:11434",
                endpoint = "/api/chat",
                modelPlaceholder = "llama3",
                protocolType = ModelProtocolType.LOCAL_GGUF
            )
        )
    }
}

/**
 * API模板
 */
data class ApiTemplate(
    val name: String,
    val baseUrl: String,
    val endpoint: String,
    val modelPlaceholder: String,
    val protocolType: ModelProtocolType
)

/**
 * 配置检查结果
 */
sealed class ConfigCheckResult {
    data object Valid : ConfigCheckResult()
    data class Invalid(val errors: List<String>) : ConfigCheckResult()

    fun isValid(): Boolean = this is Valid
}
